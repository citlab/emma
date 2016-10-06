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
package eu.stratosphere.emma.rewrite

trait RewriteEngine {

  type Expression

  abstract class Rule {

    type RuleMatch

    protected def bind(expr: Expression, root: Expression): Traversable[RuleMatch]

    protected def guard(rm: RuleMatch): Boolean

    protected def fire(rm: RuleMatch): Expression

    final def apply(expr: Expression, root: Expression): Option[Expression] = {
      for (rm <- bind(expr, root).find(guard)) yield fire(rm)
    }
  }

}
