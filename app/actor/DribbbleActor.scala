package actor

import javax.inject.Inject

import akka.actor._
import akka.pattern.pipe
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logger}

import scala.concurrent.ExecutionContext.Implicits.global

object DribbbleActor {

  case class ApiRequest(url: String)

}

/**
  * Requests API and allows application to use throttling.
  */
class DribbbleActor @Inject()(config: Configuration,
                              wsClient: WSClient) extends Actor {

  import DribbbleActor._

  private val accessToken = config.getString("dribbble.accessToken").get

  override def receive: Receive = {
    case ApiRequest(url: String) =>
      Logger.debug("Requesting " + url)
      // 100 is a maximum value for per_page query parameter.
      wsClient.url(url).withQueryString(("access_token", accessToken),
        ("per_page", "100")).get() pipeTo sender
  }
}
