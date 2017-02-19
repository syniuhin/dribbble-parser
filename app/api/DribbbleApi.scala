package api

import javax.inject.{Inject, Named, Singleton}

import actor.DribbbleActor.ApiRequest
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.contrib.throttle.Throttler._
import akka.contrib.throttle.TimerBasedThrottler
import akka.pattern.{after, ask}
import akka.util.Timeout
import model._
import play.api.{Configuration, Logger}
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}
import play.mvc.Http

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object DribbbleApi {

  type Page = Seq[JsValue]

  // It's weird, but dribbble API tells a time with ~100 sec lag.
  val dribbbleLag: FiniteDuration = 100.seconds
  // Extreme case when daily rate limit is exceeded.
  implicit val timeout: Timeout = 1.day

  trait ApiResponse

  case class Ok(result: JsObject) extends ApiResponse

  class ApiError(message: String) extends Exception(message) with ApiResponse

  case class RateLimitError(releasedAt: Long) extends ApiError("Too many requests")

  case class UnknownError(message: String) extends ApiError(message)

}

/**
  * Contains methods to request an API and handle its responses.
  */
@Singleton
class DribbbleApi @Inject()(config: Configuration,
                            system: ActorSystem,
                            @Named("dribbble-actor") dribbbleActor: ActorRef,
                            wsClient: WSClient) {

  import DribbbleApi._

  private val endpoint = config.getString("dribbble.endpoint").get

  private val requestActor = system.actorOf(Props(classOf[TimerBasedThrottler], 60 msgsPer 1.minute))
  // Set the target
  requestActor ! SetTarget(Some(dribbbleActor))

  /**
    * Parses link from a response header.
    */
  def parseNextLink(content: String): Option[String] = {
    content.split(",").toList.map { s =>
      (s.substring(s.indexOf("<") + 1, s.indexOf(">")),
        s.substring(s.indexOf("\"") + 1, s.lastIndexOf("\"")))
    }.filter(_._2 == "next") match {
      case next :: _ => Some(next._1)
      case _ => None
    }
  }

  /**
    * Requests single page of data from an API.
    */
  def requestPage(url: String): Future[(Seq[JsValue], Option[String])] =
    requestActor ? ApiRequest(url) map {
      _.asInstanceOf[WSResponse]
    } map { response =>
      if (response.status == Http.Status.OK) {
        val requestsRemaining = response.header("X-RateLimit-Remaining").getOrElse("-1").toLong
        Logger.debug(s"Requests remaining: $requestsRemaining")
        response.json.validate[JsArray] match {
          case s: JsSuccess[JsArray] => (s.value.value, response.header("Link") match {
            case Some(link) => parseNextLink(link)
            case _ => None
          })
          case e: JsError => throw UnknownError(JsError.toJson(e).toString)
        }
      } else if (response.status == 429) {
        val resetTimeOpt = response.header("X-RateLimit-Reset")
        Logger.warn(s"Rate limit exceeded at ${System.currentTimeMillis().milliseconds.toSeconds} with reset at " +
          s"${resetTimeOpt.getOrElse("<unknown>")}")
        throw RateLimitError(resetTimeOpt.getOrElse("0").toLong)
      } else {
        throw UnknownError(s"Unknown response code: $response")
      }
    } recoverWith {
      case rle: RateLimitError =>
        // Warning: reset time is not updated properly: it remains the same even when the rate limit is exceeded.
        // Therefore this strategy is a fallback to a throttling.
        after(rle.releasedAt.seconds - System.currentTimeMillis().milliseconds + dribbbleLag,
          system.scheduler)(requestPage(url))
    }

  def getRequestUrl(entity: Entity[_]): String = (entity : @unchecked) match {
    case _ if entity.url != null => entity.url
    case User(username, _) => endpoint + s"users/$username/followers"
    case Follower(id, _) => endpoint + s"users/$id/shots"
    case Shot(id, _) => endpoint + s"shots/$id/likes"
  }

  def process[T](entity: Entity[T]): Future[Seq[Page]] = {
    requestPage(getRequestUrl(entity)) flatMap {
      case (currPage, nextPageLink) =>
        lazy val res = (entity : @unchecked) match {
          case User(_, _) =>
            Future.sequence(currPage map { f =>
              process(Follower((f \ "follower" \ "id").as[Long]))
            }).map(_.flatten)
          case Follower(_, _) =>
            Future.sequence(currPage map { s =>
              process(Shot((s \ "id").as[Long]))
            }).map(_.flatten)
          case Shot(_, _) =>
            Future(Seq(currPage.map(s => (s \ "user").as[JsObject])))
        }
        Future.sequence(nextPageLink match {
          case Some(link) => Seq(process(entity.copy(url = link)), res)
          case _ => Seq(res)
        }).map(_.flatten)
    }
  }

  def top10(username: String): Future[Seq[JsObject]] = {
    process(User(username)) map { likers =>
      likers.flatten
        .groupBy(identity)
        .map { case (key, value) => Json.obj("user" -> key, "count" -> value.size) }
        .toList
        .sortBy(obj => -(obj \ "count").as[Int])
        .take(10)
    }
  }
}
