package controllers

import javax.inject.Inject

import api.DribbbleApi
import play.api.libs.json.JsArray
import play.api.mvc._

import scala.concurrent.ExecutionContext.Implicits.global

class Application @Inject()(api: DribbbleApi) extends Controller {

  def top10(user: String) = Action.async {
    api.top10(user) map { res =>
      Ok(JsArray(res))
    }
  }

}
