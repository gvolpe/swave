/*
 * Copyright © 2016 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package swave.core.impl.stages.inout

import scala.util.control.NonFatal
import swave.core.PipeElem
import swave.core.impl.{ Outport, Inport }
import swave.core.macros.StageImpl

// format: OFF
@StageImpl
private[core] final class FoldStage(zero: AnyRef, f: (AnyRef, AnyRef) ⇒ AnyRef) extends InOutStage
  with PipeElem.InOut.Fold {

  def pipeElemType: String = "fold"
  def pipeElemParams: List[Any] = zero :: f :: Nil

  connectInOutAndSealWith { (ctx, in, out) ⇒ running(in, out) }

  def running(in: Inport, out: Outport) = {

    /**
     * Waiting for a request from downstream.
     */
    def awaitingDemand() = state(
      request = (_, _) ⇒ {
        in.request(Long.MaxValue)
        folding(zero)
      },

      cancel = stopCancelF(in),
      onComplete = stopCompleteF(out),
      onError = stopErrorF(out))

    /**
     * Applying the fold function to all incoming elements.
     *
     * @param acc the current fold state
     */
    def folding(acc: AnyRef): State = state(
      request = (_, _) ⇒ stay(),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        try folding(f(acc, elem))
        catch { case NonFatal(e) => { in.cancel(); stopError(e, out) } }
      },

      onComplete = _ ⇒ {
        out.onNext(acc)
        stopComplete(out)
      },

      onError = stopErrorF(out))

    awaitingDemand()
  }
}

