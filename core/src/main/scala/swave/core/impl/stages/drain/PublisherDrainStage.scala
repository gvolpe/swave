/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core.impl.stages.drain

import java.util.concurrent.atomic.AtomicReference
import org.reactivestreams.{ Subscription, Publisher, Subscriber }
import scala.annotation.tailrec
import swave.core.{ UnsupportedSecondSubscriptionException, PipeElem }
import swave.core.impl.{ StreamRunner, Inport }
import swave.core.impl.rs.{ RSCompliance, ForwardToRunnerSubscription }
import swave.core.impl.stages.StreamTermination
import swave.core.macros.StageImpl
import swave.core.util._

// format: OFF
@StageImpl
private[core] final class PublisherDrainStage extends DrainStage with PipeElem.Drain.Publisher {
  import PublisherDrainStage.SyncSubscription

  def pipeElemType: String = "Drain.toPublisher"
  def pipeElemParams: List[Any] = Nil

  // holds exactly one of these values:
  // - `null`, when the stage is unstarted and no subscription requests has been received yet
  // - a `Subscriber` instance, when a subscription request has been received before the stage was started
  // - `stage.runner`, when the stage was started
  private[this] val refPub =
    new AtomicReference[AnyRef] with Publisher[AnyRef] {
      @tailrec def subscribe(subscriber: Subscriber[_ >: AnyRef]): Unit = {
        RSCompliance.verifyNonNull(subscriber, "Subscriber", "1.9")
        get match {
          case null => if (!compareAndSet(null, subscriber)) subscribe(subscriber)
          case x: StreamRunner => x.enqueueXEvent(PublisherDrainStage.this, subscriber)
          case _ => signalError(subscriber, new UnsupportedSecondSubscriptionException)
        }
      }
    }

  def publisher: Publisher[AnyRef] = refPub

  connectInAndSealWith { (ctx, in) ⇒
    ctx.registerForRunnerAssignment(this)
    ctx.registerForXStart(this)
    awaitingXStart(in)
  }

  def awaitingXStart(in: Inport) = state(
    xStart = () => {
      @tailrec def rec(): State =
        refPub.get match {
          case null if refPub.compareAndSet(null, runner) => awaitingSubscriber(in, StreamTermination.None)
          case null => rec()
          case sub: Subscriber[_] =>
            refPub.set(runner)
            becomeRunning(in, sub)
        }
      rec()
    })

  def awaitingSubscriber(in: Inport, termination: StreamTermination): State = state(
    onComplete = _ => awaitingSubscriber(in, StreamTermination.Completed),
    onError = (e, _) => awaitingSubscriber(in, StreamTermination.Error(e)),

    xEvent = { case sub: Subscriber[_] =>
      termination match {
        case StreamTermination.None =>
          becomeRunning(in, sub)

        case StreamTermination.Completed =>
          val s = new SyncSubscription
          sub.onSubscribe(s)
          if (!s.cancelled) sub.onComplete()
          stop()

        case StreamTermination.Error(e) =>
          signalError(sub, e)
          stop(e)
      }
    })

  def becomeRunning(in: Inport, sub: Subscriber[_]): State = {
    sub.onSubscribe(new ForwardToRunnerSubscription(this))
    running(in, sub.asInstanceOf[Subscriber[AnyRef]])
  }

  def running(in: Inport, subscriber: Subscriber[AnyRef]) = state(
    intercept = false,

    request = requestF(in),
    cancel = stopCancelF(in),

    onNext = (elem, _) => {
      subscriber.onNext(elem)
      stay()
    },

    onComplete = _ => {
      subscriber.onComplete()
      stop()
    },

    onError = (e, _) => {
      subscriber.onError(e)
      stop(e)
    },

    xEvent = {
      case sub: Subscriber[_] =>
        signalError(sub, new UnsupportedSecondSubscriptionException)
        stay()

      case ForwardToRunnerSubscription.IllegalRequest(n) =>
        subscriber.onError(new RSCompliance.IllegalRequestCountException)
        stopCancel(in)
    })

  private def signalError(sub: Subscriber[_], e: Throwable): Unit = {
    val s = new SyncSubscription
    sub.onSubscribe(s)
    if (!s.cancelled) sub.onError(e)
  }
}

private[core] object PublisherDrainStage {

  private class SyncSubscription extends Subscription {
    var cancelled = false
    var requested = 0L

    def request(n: Long) = {
      requested ⊹= n
    }

    def cancel() = cancelled = true
  }
}