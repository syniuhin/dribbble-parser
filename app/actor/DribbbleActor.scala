package actor

import javax.inject.Inject

import akka.actor._
import akka.pattern.pipe
import api.DribbbleApi
import play.api.Logger
import play.api.libs.ws.WSClient

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by syniuhin with love <3.
  */
object DribbbleActor {
  case class Top10(user: String)

  case class ApiRequest(url: String)
}

class DribbbleActor @Inject() (wsClient: WSClient) extends Actor {
  import DribbbleActor._

  override def receive: Receive = {
    case ApiRequest(url: String) =>
      Logger.debug("Requesting " + url)
      wsClient.url(url).withQueryString(("access_token", DribbbleApi.clientAccessToken),
        ("per_page", "100")).get() pipeTo sender
  }
}
