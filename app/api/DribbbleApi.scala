package api

import javax.inject.{Inject, Singleton}

import akka.actor.ActorSystem
import akka.pattern.after
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Created by syniuhin with love <3.
  */
object DribbbleApi {

  val endpoint = "https://api.dribbble.com/v1/"
  val clientAccessToken = "c786d23609b4adb68a0e2a23a6e26ab95c0d448043855de9386c4b8d526ab7a9"
  // It's weird, but dribbble API tells a time with ~100 sec lag.
  val dribbbleLag = 100.seconds

  trait ApiResponse

  case class Ok(result: JsObject) extends ApiResponse

  class ApiError(message: String) extends Exception(message) with ApiResponse

  case class RateLimitError(releasedAt: Long) extends ApiError("Too many requests")

  case class UnknownError(message: String) extends ApiError(message)

}

@Singleton
class DribbbleApi @Inject()(system: ActorSystem,
                            wsClient: WSClient) {

  import DribbbleApi._

  def apiRequest(url: String): Future[WSResponse] = {
    Logger.debug("Requesting " + url)
    wsClient.url(url).withQueryString(("access_token", clientAccessToken), ("per_page", "100")).get()
  }

  def nextLink(content: String): Option[String] = {
    content.split(",").toList.map { s =>
      (s.substring(s.indexOf("<") + 1, s.indexOf(">")),
        s.substring(s.indexOf("\"") + 1, s.lastIndexOf("\"")))
    }.filter(_._2 == "next") match {
      case next :: _ => Some(next._1)
      case _ => None
    }
  }

  def pagedMethod(url: String): Future[(Stream[JsValue], Option[String])] =
    apiRequest(url) map { response =>
      val requestsRemaining = response.header("X-RateLimit-Remaining").getOrElse("-1").toLong
      Logger.debug(s"Requests remaining: $requestsRemaining")
      if (response.status == 200) {
        response.json.validate[JsArray] match {
          case s: JsSuccess[JsArray] => (s.value.value.toStream, response.header("Link") match {
            case Some(link) => nextLink(link)
            case None => None
          })
          case e: JsError => throw UnknownError(JsError.toJson(e).toString)
        }
      } else if (response.status == 429) {
        Logger.warn(s"Rate limit exceeded at ${System.currentTimeMillis() / 1000} with reset at ${response.header("X-RateLimit-Reset").getOrElse("<unknown>").toLong}")
        throw RateLimitError(response.header("X-RateLimit-Reset").getOrElse("0").toLong)
      } else {
        throw UnknownError(s"Unknown response code: $response")
      }
    } recoverWith {
      case rle: RateLimitError =>
        // Reset time is not updated properly. TODO: Implement using akka throttler.
        after(rle.releasedAt.seconds - System.currentTimeMillis().milliseconds + dribbbleLag,
          system.scheduler)(pagedMethod(url))
    }

  def method(url: String): Future[Stream[JsValue]] = {
    pagedMethod(url) flatMap {
      // TODO: Unroll the sequence.
      case (seq, Some(nextLink)) => Future.sequence(Stream(Future(seq), method(nextLink))).map(_.flatten)
      case (seq, None) => Future(seq)
    }
  }

  def top10(user: String): Future[Seq[JsObject]] = {
    method(endpoint + s"users/$user/followers").map { followers =>
      followers.map { follower =>
        (follower \ "follower" \ "id").as[Long]
      }
    } flatMap { followers =>
      Future.sequence(followers.map { follower =>
        method(endpoint + s"users/$follower/shots").map { shots =>
          shots.map { shot =>
            (shot \ "id").as[Long]
          }
        }
      }).map(_.flatten)
    } flatMap { shots =>
      Future.sequence(shots.map { shot =>
        method(endpoint + s"shots/$shot/likes").map { likes =>
          likes.map { like =>
            (like \ "user").as[JsObject]
          }
        }
      })
    } map { likers =>
      likers.flatten
        .groupBy(identity)
        .map { case (key, value) => Json.obj("user" -> key, "count" -> value.size) }
        .toList
        .sortBy(obj => -(obj \ "count").as[Int])
        .take(10)
    }
  }
}
