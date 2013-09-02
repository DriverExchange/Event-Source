package controllers

import play.api._
import play.api.mvc._

import play.api.libs.EventSource
import play.api.libs.concurrent.Execution.Implicits._

import jobs._

object Events extends Controller {

  def publish(appId: String, channelName: String) = Action {
    Ok
  }

  def subscribe(appId: String, channelName: String) = Action {
    Async {
      EventManager.listenEvents(appId, channelName).map { chan =>
        Ok.stream(chan &> EventSource()).withHeaders(CONTENT_TYPE -> "text/event-stream")
      }
    }
  }

}