package controllers

import play.api._
import play.api.mvc._

import play.api.libs.EventSource
import play.api.libs.Comet
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._

import jobs._

object Events extends Controller {

  def publish(appId: String, channelName: String) = Action { implicit request =>
    request.body.asFormUrlEncoded
      .flatMap(_.get("message"))
      .flatMap(_.headOption)
      .map { message =>
        EventManager.event(appId, channelName, message)
        Ok
      } getOrElse BadRequest
  }

  def subscribeSSE(appId: String, channelName: String) = Action {
    Async {
      EventManager.listenEvents(appId, channelName).map { chan =>
        Ok.stream(chan &> EventSource()).withHeaders(
          CONTENT_TYPE -> "text/event-stream",
          "Access-Control-Allow-Origin" -> "*"
        )
      }
    }
  }

  def subscribeComet(appId: String, channelName: String) = Action { implicit request =>
    Async {
      val callback = request.queryString.get("callback").flatMap(_.headOption).getOrElse("callback")
      EventManager.listenEvents(appId, channelName)
        .map(_
          .through(Enumeratee.take(1))
          .through(Enumeratee.map(chunk => s"$callback($chunk);\r\n")))
        .flatMap(_(Iteratee.consume()))
        .flatMap(_.run)
        .map(Ok(_).withHeaders(CONTENT_TYPE -> "text/javascript"))
    }
  }
}