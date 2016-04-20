package eu.stratosphere.emma.compiler.ir.lnf

trait Comprehensions {
  self: Language =>

  import universe._
  import Term._
  import Tree._

  private[emma] object Comprehension {

    /**
     * Resugars monad ops into mock-comprehension syntax.
     *
     * == Preconditions ==
     *
     * - The input [[Tree]] is in ANF.
     *
     * == Postconditions ==
     *
     * - A [[Tree]] where monad operations are resugared into one-generator mock-comprehensions.
     *
     * @param monad The [[Symbol]] of the monad to be resugared.
     * @param tree  The [[Tree]] to be resugared.
     * @return The input [[Tree]] with resugared comprehensions.
     */
    def resugar(monad: Symbol)(tree: Tree): Tree = {
      // construct comprehension syntax helper for the given monad
      val cs = new Comprehension.Syntax(monad: Symbol)

      object fn {
        val meta = new LNF.Meta(tree)

        def unapply(tree: Tree): Option[(ValDef, Tree)] = tree match {
          case q"(${arg: ValDef}) => ${body: Tree}" =>
            Some(arg, body)
          case Term.ref(sym) =>
            meta.valdef(sym) map {
              case ValDef(_, _, _, Function(arg :: Nil, body)) =>
                (arg, body)
            }
        }
      }

      val transform: Tree => Tree = preWalk {
        case cs.map(xs, fn(arg, body)) =>
          cs.comprehension(
            List(
              cs.generator(Term sym arg, xs)),
            cs.head(body))

        case cs.flatMap(xs, fn(arg, body)) =>
          cs.flatten(
            cs.comprehension(
              List(
                cs.generator(Term sym arg, xs)),
              cs.head(body)))

        case cs.withFilter(xs, fn(arg, body)) =>
          cs.comprehension(
            List(
              cs.generator(Term sym arg, xs),
              cs.guard(body)),
            cs.head(ref(Term sym arg)))
      }

      (transform andThen LNF.dce) (tree)
    }

    /**
     * Desugars mock-comprehension syntax into monad ops.
     *
     * == Preconditions ==
     *
     * - An ANF [[Tree]] with mock-comprehensions.
     *
     * == Postconditions ==
     *
     * - A [[Tree]] where mock-comprehensions are desugared into comprehension operator calls.
     *
     * @param monad The [[Symbol]] of the monad syntax to be desugared.
     * @param tree  The [[Tree]] to be desugared.
     * @return The input [[Tree]] with desugared comprehensions.
     */
    def desugar(monad: Symbol)(tree: Tree): Tree = {
      // construct comprehension syntax helper for the given monad
      val cs = new Syntax(monad: Symbol)

      val transform: Tree => Tree = postWalk {
        case t@cs.comprehension(cs.generator(sym, rhs) :: qs, cs.head(expr)) =>

          val (guards, rest) = qs span {
            case cs.guard(_) => true
            case _ => false
          }

          // accumulate filters in a prefix of valdefs and keep track of the tail valdef symbol
          val (tail, prefix) = {
            ({
              // step (1) accumulate the result
              (_: List[Tree]).foldLeft((Term sym rhs, List.empty[Tree]))((res, guard) => {
                val (currSym, currPfx) = res
                val cs.guard(expr) = guard

                val funcRhs = lambda(sym)(expr)
                val funcNme = Term.name.fresh("anonfun")
                val funcSym = Term.sym.free(funcNme, funcRhs.tpe)
                val funcVal = val_(funcSym, funcRhs)

                val nextNme = Term.name.fresh(cs.withFilter.symbol.name)
                val nextSym = Term.sym.free(nextNme, currSym.info)
                val nextVal = val_(nextSym, cs.withFilter(ref(currSym))(ref(funcSym)))

                (nextSym, nextVal :: funcVal :: currPfx)
              })
            } andThen {
              // step (2) reverse the prefix
              case (currSym, currPfx) => (currSym, currPfx.reverse)
            }) (guards)
          }

          if (expr.symbol == sym) {
            // trivial head expression consisting of the matched sym 'x'
            // omit the resulting trivial mapper

            LNF.simplify(block(
              prefix,
              ref(tail)))

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

            val func = lambda(sym)(body)
            val term = Term.sym.free(Term.name.lambda, func.tpe)

            block(
              prefix,
              val_(term, func),
              op(ref(tail))(ref(term)))
          }

        case t@cs.flatten(Block(stats, expr@cs.map(xs, fn))) =>

          block(
            stats,
            cs.flatMap(xs)(fn))
      }

      (transform andThen LNF.flatten) (tree)
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

    // -------------------------------------------------------------------------
    // Helpers for mock comprehension syntax construction / destruction
    // -------------------------------------------------------------------------

    trait MonadOp {
      val symbol: TermSymbol

      def apply(xs: Tree)(fn: Tree): Tree

      def unapply(tree: Tree): Option[(Tree, Tree)]
    }

    class Syntax(val monad: Symbol) {

      val monadTpe /*  */ = monad.asType.toType.typeConstructor
      val moduleSel /* */ = resolve(IR.module)

      // -----------------------------------------------------------------------
      // Monad Ops
      // -----------------------------------------------------------------------

      object map extends MonadOp {

        override val symbol =
          Term.member(monad, Term name "map")

        override def apply(xs: Tree)(f: Tree): Tree =
          Method.call(xs, symbol, Type.arg(2, f))(f :: Nil)

        override def unapply(apply: Tree): Option[(Tree, Tree)] = apply match {
          case Method.call(xs, `symbol`, _, Seq(f)) => Some(xs, f)
          case _ => None
        }
      }

      object flatMap extends MonadOp {

        override val symbol =
          Term.member(monad, Term name "flatMap")

        override def apply(xs: Tree)(f: Tree): Tree =
          Method.call(xs, symbol, Type.arg(1, Type.arg(2, f)))(f :: Nil)

        override def unapply(tree: Tree): Option[(Tree, Tree)] = tree match {
          case Method.call(xs, `symbol`, _, Seq(f)) => Some(xs, f)
          case _ => None
        }
      }

      object withFilter extends MonadOp {

        override val symbol =
          Term.member(monad, Term name "withFilter")

        override def apply(xs: Tree)(p: Tree): Tree =
          Method.call(xs, symbol)(p :: Nil)

        override def unapply(tree: Tree): Option[(Tree, Tree)] = tree match {
          case Method.call(xs, `symbol`, _, Seq(p)) => Some(xs, p)
          case _ => None
        }
      }

      // -----------------------------------------------------------------------
      // Mock Comprehension Ops
      // -----------------------------------------------------------------------

      /** Con- and destructs a comprehension from/to a list of qualifiers `qs` and a head expression `hd`. */
      object comprehension {
        val symbol = IR.comprehension

        def apply(qs: List[Tree], hd: Tree): Tree =
          Method.call(moduleSel, symbol, Type of hd, monadTpe)(block(qs, hd) :: Nil)

        def unapply(tree: Tree): Option[(List[Tree], Tree)] = tree match {
          case Apply(fun, Block(qs, hd) :: Nil)
            if Term.sym(fun) == symbol => Some(qs, hd)
          case _ =>
            None
        }
      }

      /** Con- and destructs a generator from/to a [[Tree]]. */
      object generator {
        val symbol = IR.generator

        def apply(lhs: TermSymbol, rhs: Tree): Tree =
          val_(lhs, Method.call(moduleSel, symbol, Type.arg(1, rhs), monadTpe)(rhs :: Nil))

        def unapply(tree: Tree): Option[(TermSymbol, Tree)] = tree match {
          case val_(lhs, Apply(fun, arg :: Nil), _)
            if Term.sym(fun) == symbol => Some(lhs, arg)
          case _ =>
            None
        }
      }

      /** Con- and destructs a guard from/to a [[Tree]]. */
      object guard {
        val symbol = IR.guard

        def apply(expr: Tree): Tree =
          Method.call(moduleSel, symbol)(expr :: Nil)

        def unapply(tree: Tree): Option[Tree] = tree match {
          case Apply(fun, expr :: Nil)
            if Term.sym(fun) == symbol => Some(expr)
          case _ =>
            None
        }
      }

      /** Con- and destructs a head from/to a [[Tree]]. */
      object head {
        val symbol = IR.head

        def apply(expr: Tree): Tree =
          Method.call(moduleSel, symbol, Type of expr)(expr :: Nil)

        def unapply(tree: Tree): Option[Tree] = tree match {
          case Apply(fun, expr :: Nil)
            if Term.sym(fun) == symbol => Some(expr)
          case _ =>
            None
        }
      }

      /** Con- and destructs a flatten from/to a [[Tree]]. */
      object flatten {
        val symbol = IR.flatten

        def apply(expr: Tree): Tree =
          Method.call(moduleSel, symbol, Type.arg(1, Type.arg(1, expr)), monadTpe)(expr :: Nil)

        def unapply(tree: Tree): Option[Tree] = tree match {
          case Apply(fun, expr :: Nil)
            if Term.sym(fun) == symbol => Some(expr)
          case _ =>
            None
        }
      }

    }

  }

}
