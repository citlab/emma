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
package org.emmalanguage
package ast

import scala.reflect.macros.{Attachments, blackbox}
import scala.tools.nsc.Global

/**
 * Implements various utility functions that mitigate and/or workaround deficiencies in Scala's
 * macros APIs, e.g. non- idempotent type checking, lack of hygiene, capture-avoiding
 * substitution, fully-qualified names, fresh name generation, identifying closures, etc.
 */
trait MacroAST extends AST {

  val c: blackbox.Context
  override val universe: c.universe.type = c.universe

  import universe._
  import internal._
  import decorators._

  /** Shows `tree` in a Swing AST browser. */
  def browse(tree: Tree): Unit = universe match {
    case global: Global =>
      val gt = tree.asInstanceOf[global.Tree]
      import global.treeBrowsers._
      val frame = new BrowserFrame("macro-expand")
      val lock = new concurrent.Lock
      frame.setTreeModel(new ASTTreeModel(gt))
      frame.createFrame(lock)
      lock.acquire()
    case _ =>
  }

  override def abort(msg: String, pos: Position = NoPosition): Nothing =
    c.abort(pos, msg)

  override def warning(msg: String, pos: Position = NoPosition): Unit =
    c.warning(pos, msg)

  // ---------------------------
  // Parsing and type-checking
  // ---------------------------

  private[emmalanguage] override def parse(code: String): Tree =
    c.parse(code)

  private[emmalanguage] override def typeCheck(tree: Tree, typeMode: Boolean = false): Tree =
    if (typeMode) c.typecheck(tree, c.TYPEmode) else c.typecheck(tree)

  private[emmalanguage] override def inferImplicit(tpe: Type): Tree =
    c.inferImplicitValue(tpe)

  // ------------------------
  // Abstract wrapper methods
  // ------------------------

  override val get: Getter = new Getter {
    override def enclosingOwner: Symbol = c.internal.enclosingOwner
    override def meta(sym: Symbol): Attachments = attachments(sym)
    override def meta(tree: Tree): Attachments = attachments(tree)
  }

  private[ast] override val set: Setter = new Setter {
    override def name(sym: Symbol, name: Name): Unit = setName(sym, name)
    override def original(tpt: TypeTree, original: Tree): Unit = setOriginal(tpt, original)
    override def owner(sym: Symbol, owner: Symbol): Unit = setOwner(sym, owner)
    override def pos(sym: Symbol, pos: Position): Unit = updateAttachment(sym, pos)
    override def pos(tree: Tree, pos: Position): Unit = setPos(tree, pos)
    override def flags(sym: Symbol, flags: FlagSet): Unit = {
      resetFlag(sym, sym.flags)
      setFlag(sym, flags)
    }
  }
}
