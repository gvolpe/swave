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

package swave.core

trait Cancellable {

  /**
   * Cancels this instance and returns true if that was successful,
   * i.e. if the instance was not already expired or cancelled.
   */
  def cancel(): Boolean

  /**
   * Returns true if this instance is not active (anymore).
   */
  def isCancelled: Boolean

}
