package controllers

import play.api._
import play.api.mvc._

import play.api.libs.EventSource
import play.api.libs.Comet
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.libs.json._

import scala.concurrent.Future

import jobs._

object Events extends Controller {

  def publish(appId: String, channelName: String) = Action { implicit request =>
    request.body.asFormUrlEncoded
      .flatMap(_.get("message"))
      .flatMap(_.headOption)
      .map { message =>
        EventManager.event(appId, channelName, Json.parse(message))
        Ok
      } getOrElse BadRequest
  }

  def subscribeSSE(appId: String, channelName: String) = Action { implicit request =>
    if (request.queryString.get("filters").isDefined
      && !request.queryString.get("filters").get.isEmpty
      && !request.queryString.get("signature").isDefined) {
      BadRequest("If 'filters' is defined, it must not be empty and there must be a 'signature'.")
    }
    else {
      // TODO
      // request.queryString.get("filters").map { filters =>
      //   val signature = request.queryString.get("signature").get
      //   val appSecret = "very_secret_key"
      //   val checkSignature = Codec.md5(filters + appSecret)
      //   if (checkSignature != signature) {

      //   }
      // }
      Async {
        play.Logger.info(s"SubscribeSSE: $appId, $channelName")
        EventManager.listenEvents(appId, channelName).map { chan =>
          Ok.stream(chan &> EventSource()).withHeaders(
            CONTENT_TYPE -> "text/event-stream",
            "Access-Control-Allow-Origin" -> "*"
          )
        }
      }
    }
  }

  def subscribeComet(appId: String, channelName: String) = Action { implicit request =>
    Async {

      val callback = request.queryString.get("callback").flatMap(_.headOption).getOrElse("callback")

      val longPoll = EventManager.listenEvents(appId, channelName)
        .map(_
          .through(Enumeratee.take(1))
          .through(Enumeratee.map(chunk => s"""$callback("success", $chunk);\r\n""")))
        .flatMap(_(Iteratee.consume()))
        .flatMap(_.run)
        .map(Ok(_))

      val timeout = Promise.timeout(Ok(s"""$callback("timeout");\r\n"""), 60 * 1000)

      Future.firstCompletedOf(Seq(longPoll, timeout))

    }.withHeaders(CONTENT_TYPE -> "text/javascript")

  }

}