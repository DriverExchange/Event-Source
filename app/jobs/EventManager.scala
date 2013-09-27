package jobs

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.iteratee._
import play.api.libs.json._

import akka.actor.{ Actor, Props, ActorSystem }
import scala.concurrent.{ Future, Promise }
import scala.collection._

case class ChannelId(appId: String, channelName: String) {
  override def toString = appId + ":" + channelName
}

case class EventMessage(data: JsValue, filters: Option[JsValue] = None)

trait Channels {

  type BroadcastChannel = (Enumerator[EventMessage], Concurrent.Channel[EventMessage])

  private val channels = mutable.Map[String, BroadcastChannel]()

  private def selectChannel(channelId: ChannelId): BroadcastChannel = {
    channels.get(channelId.toString) match {
      case None =>
        val channel = Concurrent.broadcast[EventMessage]
        channels(channelId.toString) = channel
        channel
      case Some(channel) =>
        channel
    }
  }

  def pushEvent(channelId: ChannelId)(message: EventMessage): Unit = selectChannel(channelId)._2.push(message)
  def listenEvents(channelId: ChannelId): Enumerator[EventMessage] = selectChannel(channelId)._1
}

class EventManager extends Actor with Channels {
  import EventManager._

  def receive = {
    case NewEvent(channelId, message) =>
      play.Logger.debug(s"New event pushed to $channelId: $message)")
      pushEvent(channelId)(message)
    case Connect(channelId, channel) =>
      play.Logger.debug(s"New connection to $channelId")
      channel.success(listenEvents(channelId))
  }
}

object EventManager {

  private val actor = ActorSystem("event-manager").actorOf(Props(new EventManager()))

  final case class NewEvent(channelId: ChannelId, message: EventMessage)
  final case class Connect(channelId: ChannelId, selectedChannel: Promise[Enumerator[EventMessage]])

  def event(appId: String, channelName: String, data: JsValue, filters: Option[JsValue]): Unit = {
    val channelId = ChannelId(appId, channelName)
    actor ! NewEvent(channelId, EventMessage(data, filters))
  }

  def listenEvents(appId: String, channelName: String): Future[Enumerator[EventMessage]] = {
    val channel = Promise[Enumerator[EventMessage]]()
    val channelId = ChannelId(appId, channelName)
    actor ! Connect(channelId, channel)

    channel.future.onFailure {
      case e => play.Logger.error("manager: " + channelId.toString, e)
    }

    channel.future
  }
}
