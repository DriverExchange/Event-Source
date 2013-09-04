package jobs

import play.api.libs.json._
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee._
import play.api.libs.json._

import akka.actor.{ Actor, Props }
import scala.concurrent.{ Future, Promise }

case class ChannelId(appId: String, channelName: String) {
  override def toString = appId + ":" + channelName
}

trait Channels {
  import scala.collection.mutable.Map

  type BroadcastChannel = (Enumerator[JsValue], Concurrent.Channel[JsValue])

  private val channels = Map[String, BroadcastChannel]()

  private def selectChannel(channelId: ChannelId): BroadcastChannel = {
    channels.get(channelId.toString) match {
      case None =>
        val channel = Concurrent.broadcast[JsValue]
        channels(channelId.toString) = channel
        channel
      case Some(channel) =>
        channel
    }
  }

  def pushEvent(channelId: ChannelId)(data: JsValue): Unit = selectChannel(channelId)._2.push(data)
  def listenEvents(channelId: ChannelId): Enumerator[JsValue] = selectChannel(channelId)._1
}

class EventManager extends Actor with Channels {
  import EventManager._

  def receive = {
    case NewEvent(channelId, data) => pushEvent(channelId)(data)
    case Connect(channelId, channel) => channel.success(listenEvents(channelId))
  }
}

object EventManager {
  import play.api.Play.current

  private val actor = Akka.system.actorOf(Props(new EventManager()))

  final case class NewEvent(channelId: ChannelId, data: JsValue)
  final case class Connect(channelId: ChannelId, selectedChannel: Promise[Enumerator[JsValue]])

  def event(appId: String, channelName: String, data: JsValue): Unit = {
    val channelId = ChannelId(appId, channelName)
    actor ! NewEvent(channelId, data)
  }

  def listenEvents(appId: String, channelName: String): Future[Enumerator[JsValue]] = {
    val channel = Promise[Enumerator[JsValue]]()
    val channelId = ChannelId(appId, channelName)
    actor ! Connect(channelId, channel)
    channel.future
  }
}
