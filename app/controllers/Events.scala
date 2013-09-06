package controllers

import play.api._
import play.api.mvc._

import play.api.libs.EventSource
import play.api.libs.Comet
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.libs.Codecs

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

  def getSignedFilters(filtersParam: Option[String], signatureParam: Option[String]): Option[JsValue] = {
    filtersParam.flatMap { filters =>
      val signature = signatureParam.get
      val appSecret = "very_secret_key"
      val checkSignature = Codecs.md5((filters + appSecret).getBytes("UTF-8"))
      if (checkSignature == signature) Some(Json.toJson(filters))
      else None
    }
  }

  def listenEventsSSE(appId: String, channelName: String, filters: Option[JsValue] = None) = { implicit request: Request[AnyContent] =>
    Async {
      EventManager.listenEvents(appId, channelName).map { chan =>
        Ok.stream(chan &> EventSource()).withHeaders(
          CONTENT_TYPE -> "text/event-stream",
          "Access-Control-Allow-Origin" -> "*"
        )
      }
    }
  }

  def listenEventsComet(appId: String, channelName: String, filters: Option[JsValue] = None) = { implicit request: Request[AnyContent] =>
    val callback = request.queryString.get("callback").flatMap(_.headOption).getOrElse("callback")
    val longPoll = EventManager.listenEvents(appId, channelName)
      .map(_
        .through(Enumeratee.take(1))
        .through(Enumeratee.map(chunk => s"""$callback("success", $chunk);\r\n""")))
      .flatMap(_(Iteratee.consume()))
      .flatMap(_.run)
      .map(Ok(_))
    val timeout = Promise.timeout(Ok(s"""$callback("timeout");\r\n"""), 60 * 1000)
    val futureResult = Future.firstCompletedOf(Seq(longPoll, timeout))

    Async(futureResult).withHeaders(CONTENT_TYPE -> "text/javascript")
  }

  def subscribe(appId: String, channelName: String, subscribeFunc: (String, String, Option[JsValue]) => Request[AnyContent] => Result) = Action { implicit request =>
    val filtersParam = request.queryString.get("filters").map(_.head)
    val signatureParam = request.queryString.get("signature").map(_.head)
    if (filtersParam.isDefined && !filtersParam.get.isEmpty && !signatureParam.isDefined) {
      BadRequest("If 'filters' is defined, it must not be empty and there must be a 'signature'.")
    }
    else {
      if (filtersParam.isDefined) {
        getSignedFilters(filtersParam, signatureParam)
          .map((filters: JsValue) => subscribeFunc(appId, channelName, Some(filters))(request))
          .getOrElse(BadRequest("The filters does not match the signature."))
      }
      else subscribeFunc(appId, channelName, None)(request)
    }
  }

  def subscribeSSE(appId: String, channelName: String) = subscribe(appId, channelName, listenEventsSSE)
  def subscribeComet(appId: String, channelName: String) = subscribe(appId, channelName, listenEventsComet)

}