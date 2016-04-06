package eu.stratosphere.emma.compiler.ir.lnf

import eu.stratosphere.emma.compiler.ir.CommonIR

trait Comprehensions extends CommonIR {
  self: Language =>

  import universe._
  import Tree._

  private[emma] object Comprehension {

    // -------------------------------------------------------------------------
    // Helpers for mock comprehension syntax construction / destruction
    // -------------------------------------------------------------------------

    trait MonadOp {
      val symbol: TermSymbol

      def apply(xs: Tree)(fn: Function): Tree

      def unapply(tree: Tree): Option[(Tree, Function)]
    }

    class Syntax(val monad: Symbol) {

      val monadTpe /*  */ = monad.asType.toType.typeConstructor
      val moduleSel /* */ = resolve(IR.module)

      // -----------------------------------------------------------------------
      // Monad Ops
      // -----------------------------------------------------------------------

      object map extends MonadOp {
        override val symbol = monad.info.decl(TermName("map")).asTerm

        override def apply(xs: Tree)(fn: Function): Tree =
          app(Term.sel(xs, symbol), Type.arg(2, fn))(fn)

        override def unapply(apply: Tree): Option[(Tree, Function)] = apply match {
          case q"${map@Select(xs, _)}[$_](${fn: Function})"
            if Term.of(map) == symbol => Some(xs, fn)
          case _ =>
            Option.empty
        }
      }

      object flatMap extends MonadOp {
        override val symbol = monad.info.decl(TermName("flatMap")).asTerm

        override def apply(xs: Tree)(fn: Function): Tree =
          app(Term.sel(xs, symbol), Type.arg(1, Type.arg(2, fn)))(fn)

        override def unapply(tree: Tree): Option[(Tree, Function)] = tree match {
          case q"${flatMap@Select(xs, _)}[$_](${fn: Function})"
            if Term.of(flatMap) == symbol => Some(xs, fn)
          case _ =>
            Option.empty
        }
      }

      object withFilter extends MonadOp {
        override val symbol = monad.info.decl(TermName("withFilter")).asTerm

        override def apply(xs: Tree)(fn: Function): Tree =
          app(Term.sel(xs, symbol))(fn)

        override def unapply(tree: Tree): Option[(Tree, Function)] = tree match {
          case q"${withFilter@Select(xs, _)}(${fn: Function})"
            if Term.of(withFilter) == symbol => Some(xs, fn)
          case _ =>
            Option.empty
        }
      }

      // -----------------------------------------------------------------------
      // Mock Comprehension Ops
      // -----------------------------------------------------------------------

      /** Con- and destructs a comprehension from/to a list of qualifiers `qs` and a head expression `hd`. */
      object comprehension {
        val symbol = IR.comprehension

        def apply(qs: List[Tree], hd: Tree): Tree =
          call(moduleSel, symbol, Type.of(hd), monadTpe)(block(qs, hd))

        def unapply(tree: Tree): Option[(List[Tree], Tree)] = tree match {
          case Apply(fun, List(Block(qs, hd)))
            if Term.of(fun) == symbol => Some(qs, hd)
          case _ =>
            Option.empty
        }
      }

      /** Con- and destructs a generator from/to a [[Tree]]. */
      object generator {
        val symbol = IR.generator

        def apply(sym: TermSymbol, rhs: Tree): Tree =
          val_(sym, call(moduleSel, symbol, Type.arg(1, rhs), monadTpe)(rhs))

        def unapply(tree: Tree): Option[(TermSymbol, Tree)] = tree match {
          case vd@ValDef(_, _, _, Apply(fun, List(arg)))
            if Term.of(fun) == symbol => Some(vd.symbol.asTerm, arg)
          case _ =>
            Option.empty
        }
      }

      /** Con- and destructs a guard from/to a [[Tree]]. */
      object guard {
        val symbol = IR.guard

        def apply(expr: Tree): Tree =
          call(moduleSel, symbol)(expr)

        def unapply(tree: Tree): Option[Tree] = tree match {
          case Apply(fun, List(expr))
            if Term.of(fun) == symbol => Some(expr)
          case _ =>
            Option.empty
        }
      }

      object head {
        val symbol = IR.head

        def apply(expr: Tree): Tree =
          call(moduleSel, symbol, Type.of(expr))(expr)

        def unapply(tree: Tree): Option[Tree] = tree match {
          case Apply(fun, List(expr))
            if Term.of(fun) == symbol => Some(expr)
          case _ =>
            Option.empty
        }
      }

      object flatten {
        val symbol = IR.flatten

        def apply(expr: Tree): Tree =
          call(moduleSel, symbol, Type.arg(1, Type.arg(1, expr)), monadTpe)(expr)

        def unapply(tree: Tree): Option[Tree] = tree match {
          case Apply(fun, List(expr))
            if Term.of(fun) == symbol => Some(expr)
          case _ =>
            Option.empty
        }
      }

    }

    /**
     * Resugars monad ops into mock-comprehension syntax.
     *
     * @param monad The [[Symbol]] of the monad to be resugared.
     * @param tree  The [[Tree]] to be resugared.
     * @return The input [[Tree]] with resugared comprehensions.
     */
    def resugar(monad: Symbol)(tree: Tree): Tree = {
      // construct comprehension syntax helper for the given monad
      val cs = new Syntax(monad: Symbol)

      postWalk(tree) {
        case cs.map(xs, q"(${arg: ValDef}) => ${body: Tree}") =>
          cs.comprehension(
            List(
              cs.generator(Term.of(arg), xs)),
            cs.head(body))

        case cs.flatMap(xs, q"(${arg: ValDef}) => ${body: Tree}") =>
          cs.flatten(
            cs.comprehension(
              List(
                cs.generator(Term.of(arg), xs)),
              cs.head(body)))

        case cs.withFilter(xs, q"(${arg: ValDef}) => ${body: Tree}") =>
          cs.comprehension(
            List(
              cs.generator(Term.of(arg), xs),
              cs.guard(body)),
            cs.head(ref(Term.of(arg))))
      }
    }

    /**
     * Desugars mock-comprehension syntax into monad ops.
     *
     * @param monad The [[Symbol]] of the monad syntax to be desugared.
     * @param tree  The [[Tree]] to be desugared.
     * @return The input [[Tree]] with desugared comprehensions.
     */
    def desugar(monad: Symbol)(tree: Tree): Tree = {
      // construct comprehension syntax helper for the given monad
      val cs = new Syntax(monad: Symbol)

      val desugar = (tree: Tree) => postWalk(tree) {
        case t@cs.comprehension(cs.generator(sym, rhs) :: qs, cs.head(expr)) =>

          val (guards, rest) = qs span {
            case cs.guard(_) => true
            case _ => false
          }

          val (next, prefix) = guards.foldLeft((Term.of(rhs), List.empty[Tree]))((res, guard) => {
            val (curr, list) = res
            val cs.guard(expr) = guard

            val name = Term.fresh(cs.withFilter.symbol.name.encodedName)
            val next = Term.free(name.toString, curr.info)

            (next, list :+ val_(next, cs.withFilter(ref(curr))(lambda(sym)(expr))))
          })

          if (expr.symbol == sym) {
            // trivial head expression consisting of the matched sym 'x'
            // omit the resulting trivial mapper

            block(
              prefix,
              ref(next))

          } else {
            // append a map or a flatMap to the result depending on
            // the size of the residual qualifier sequence 'qs'

            val (op: MonadOp, body: Tree) = rest match {
              case Nil => (
                cs.map,
                expr)
              case _ => (
                cs.flatMap,
                cs.comprehension(
                  rest,
                  cs.head(expr)))
            }

            val name = Term.fresh(op.symbol.name.encodedName)
            val term = Term.free(name.toString, t.tpe)

            block(
              prefix :+ val_(term, op(ref(next))(lambda(sym)(body))),
              ref(term))
          }

        case t@cs.flatten(Block(stats, expr)) =>
          stats.last match {
            case vd@ValDef(_, _, _, cs.map(xs, fn)) if vd.symbol == expr.symbol =>
              val name = Term.fresh(cs.flatMap.symbol.name.encodedName)
              val term = Term.free(name.toString, t.tpe)

              block(
                stats.init :+ val_(term, cs.flatMap(xs)(fn)),
                ref(term))
            case _ =>
              t
          }
      }

      val unnestBlocks = (tree: Tree) => postWalk(tree) {
        case parent: Block if parent.stats.exists {
          case ValDef(_, _, _, _: Block) => true
          case _ => false
        } =>
          // flatten nested stats
          val flatStats = parent.stats.flatMap {
            case stat@ValDef(mods, name, tpt, child: Block) =>
              child.stats.last match {
                case n@ValDef(_, _, _, rhs) =>
                  child.stats.init :+ val_(Term.of(stat), rhs, mods.flags)
                case _ =>
                  List(stat)
              }
            case t =>
              List(t)
          }

          block(flatStats, parent.expr)
      }

      val res = (desugar andThen unnestBlocks) (tree)
      res
    }

    /**
     * Normalizes nested mock-comprehension syntax.
     *
     * @param monad The [[Symbol]] of the monad syntax to be normalized.
     * @param tree  The [[Tree]] to be resugared.
     * @return The input [[Tree]] with resugared comprehensions.
     */
    def normalize(monad: Symbol)(tree: Tree): Tree = {
      tree
    }
  }

}
