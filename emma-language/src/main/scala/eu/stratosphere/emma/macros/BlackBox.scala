/*
 * Copyright © 2014 TU Berlin (emma@dima.tu-berlin.de)
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
package eu.stratosphere.emma.macros

import scala.reflect.macros.blackbox

/**
 * Trait to mix in with black box macros. Once a [[blackbox.Context]] has been provided, its
 * universe will be available in the body of extending classes.
 */
@deprecated("Use `emma.compiler.MacroUtil` instead", "24.02.2016")
trait BlackBox {
  val c: blackbox.Context
  val universe: c.universe.type = c.universe
}
