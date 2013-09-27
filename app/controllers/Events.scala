package controllers

import play.api._
import play.api.mvc._

import play.api.libs.EventSource
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.concurrent._
import play.api.libs.iteratee._
import play.api.libs.json._
import play.api.libs.Codecs

import scala.concurrent.Future

import jobs._

import Play.current

object Events extends Controller {

  def Secured(username: String, password: String)(action: => Request[AnyContent] => Result) = Action { implicit request =>
    request.headers.get("Authorization").flatMap { authorization =>
      authorization.split(" ").drop(1).headOption.filter { encoded =>
        new String(org.apache.commons.codec.binary.Base64.decodeBase64(encoded.getBytes)).split(":").toList match {
          case u :: p :: Nil if u == username && password == p => true
          case _ => false
        }
      }.map(_ => action(request))
    } getOrElse Unauthorized.withHeaders("WWW-Authenticate" -> "Basic realm=\"Secured\"")
  }

  def publish(appId: String, channelName: String) = {
    Play.configuration.getString(s"dxes.$appId.appAuthToken").map { appAuthToken =>
      Secured(appId, appAuthToken) { implicit request =>
        val messageParam = request.body.asFormUrlEncoded.get("message").headOption
        val filtersParam = request.body.asFormUrlEncoded.get("filters").headOption
        if (messageParam.isDefined) {
          play.Logger.debug(s"Send new event:\nfilter: ${filtersParam.getOrElse("-")}\nfilter:\n${messageParam.getOrElse("-")}")
          EventManager.event(appId, channelName, Json.parse(messageParam.get), filtersParam.map(Json.parse(_)))
          Ok
        }
        else {
          BadRequest
        }
      }
    } getOrElse Action(NotFound)
  }

  def getSignedFilters(appId: String,
                       filtersParam: Option[String],
                       signatureParam: Option[String]): Option[JsValue] = {
    filtersParam.flatMap { filters =>
      Play.configuration.getString(s"dxes.$appId.appSecret").flatMap { appSecret =>
        val signature = signatureParam.get
        val checkSignature = Codecs.md5((filters + appSecret).getBytes("UTF-8"))
        if (checkSignature == signature) Some(Json.parse(filters))
        else None
      }
    }
  }

  def applyFilters(listenerFilters: Option[JsValue], messageFilters: Option[JsValue]): Boolean = {
    if (messageFilters.isDefined) {
      if (listenerFilters.isDefined) {
        val mapListenerFilters = listenerFilters.map(Json.fromJson[Map[String, Seq[String]]](_)).get.get
        val mapMessageFilters = messageFilters.map(Json.fromJson[Map[String, Seq[String]]](_)).get.get
        !mapMessageFilters.map { case (mFilterName, mFilterValues) =>
          if (mapListenerFilters.isDefinedAt(mFilterName)) {
            mapListenerFilters(mFilterName).exists(mFilterValues.contains(_))
          }
          else false
        }.exists(_ == false)
      }
      else false
    }
    else true
  }

  def listenEventsSSE(appId: String,
                      channelName: String,
                      filters: Option[JsValue] = None) = { implicit request: Request[AnyContent] =>
    EventManager.listenEvents(appId, channelName).map { chan =>
      Ok.chunked(chan
        .through(Enumeratee.filter((message: EventMessage) => applyFilters(filters, message.filters)))
        .through(Enumeratee.map(_.data))
        .through(EventSource())).withHeaders(
        CONTENT_TYPE -> "text/event-stream",
        "Access-Control-Allow-Origin" -> "*"
      )
    }
  }

  def listenEventsComet(appId: String,
                        channelName: String,
                        filters: Option[JsValue] = None) = { implicit request: Request[AnyContent] =>
    val callback = request.queryString.get("callback").flatMap(_.headOption).getOrElse("callback")
    val longPoll = EventManager.listenEvents(appId, channelName)
      .map(_
        .through(Enumeratee.take(1))
        .through(Enumeratee.map(message => s"""$callback("success", ${message.data});\r\n""")))
      .flatMap(_(Iteratee.consume()))
      .flatMap(_.run)
      .map(Ok(_).withHeaders(CONTENT_TYPE -> "text/javascript"))
    val timeout = Promise.timeout(Ok(s"""$callback("timeout");\r\n"""), 60 * 1000)
    Future.firstCompletedOf(Seq(longPoll, timeout))
  }

  type SubscribeAction = (String, String, Option[JsValue]) => Request[AnyContent] => Future[SimpleResult]

  def subscribe(appId: String,
                channelName: String,
                subscribeFunc: SubscribeAction) = {
    Action.async { implicit request: Request[AnyContent] =>
      val filtersParam = request.queryString.get("filters").map(_.head)
      val signatureParam = request.queryString.get("signature").map(_.head)
      if (filtersParam.isDefined && !filtersParam.get.isEmpty && !signatureParam.isDefined) {
        Future(BadRequest("If 'filters' is defined, it must not be empty and there must be a 'signature'."))
      }
      else {
        if (filtersParam.isDefined) {
          getSignedFilters(appId, filtersParam, signatureParam)
            .map((filters: JsValue) => subscribeFunc(appId, channelName, Some(filters))(request))
            .getOrElse(Future(BadRequest("The filters does not match the signature.")))
        }
        else subscribeFunc(appId, channelName, None)(request)
      }
    }
  }

  def subscribeSSE(appId: String, channelName: String) = subscribe(appId, channelName, listenEventsSSE)
  def subscribeComet(appId: String, channelName: String) = subscribe(appId, channelName, listenEventsComet)

}