package eu.stratosphere
package emma.compiler

import scala.reflect.macros.blackbox

/**
 * Implements various utility functions that mitigate and/or workaround deficiencies in Scala's
 * macros APIs, e.g. non- idempotent type checking, lack of hygiene, capture-avoiding
 * substitution, fully-qualified names, fresh name generation, identifying closures, etc.
 */
trait BlackBoxUtil extends ReflectUtil {

  val c: blackbox.Context
  val universe: c.universe.type = c.universe

  import universe._
  import c.internal._

  override def parse(code: String): Tree =
    c.parse(code)

  override def typeCheck(tree: Tree, typeMode: Boolean = false): Tree =
    if (typeMode) c.typecheck(tree, c.TYPEmode)
    else c.typecheck(tree)

  override def termSymbol(
    owner: Symbol,
    name: TermName,
    flags: FlagSet,
    pos: Position): TermSymbol = {

    newTermSymbol(owner, name, pos, flags)
  }

  override def typeSymbol(
    owner: Symbol,
    name: TypeName,
    flags: FlagSet,
    pos: Position): TypeSymbol = {

    newTypeSymbol(owner, name, pos, flags)
  }

  override def preWalk(tree: Tree)(pf: Tree ~> Tree): Tree =
    super.preWalk(tree)(pf.orElse {
      // NOTE:
      // - `TypeTree.original` is not transformed by default
      // - `setOriginal` is only available at compile-time
      case tt: TypeTree if tt.original != null =>
        setOriginal(tt, preWalk(tt.original)(pf))
    })

  override def postWalk(tree: Tree)(pf: Tree ~> Tree): Tree =
    super.postWalk(tree)(pf.orElse {
      // NOTE:
      // - `TypeTree.original` is not transformed by default
      // - `setOriginal` is only available at compile-time
      case tt: TypeTree if tt.original != null =>
        setOriginal(tt, postWalk(tt.original)(pf))
    })
}
