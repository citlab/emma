package eu.stratosphere.emma.macros.program


import eu.stratosphere.emma.api.Algorithm
import eu.stratosphere.emma.macros.program.comprehension.Comprehension
import eu.stratosphere.emma.macros.program.controlflow.ControlFlow

import scala.language.existentials
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

class WorkflowMacros(val c: blackbox.Context) {

  /**
   * Entry macro for emma algorithms.
   */
  def parallelize[T: c.WeakTypeTag](e: c.Expr[T]): c.Expr[Algorithm[T]] = {
    new LiftHelper[c.type](c).parallelize[T](e)
  }

  /**
   * Debug macro: print all comprehensions in an algorithm.
   */
  def comprehend[T: c.WeakTypeTag](e: c.Expr[T]): c.Expr[Unit] = {
    new LiftHelper[c.type](c).comprehend[T](e)
  }

  private class LiftHelper[C <: blackbox.Context](val c: C)
    extends ContextHolder[c.type]
    with ControlFlow[c.type]
    with Comprehension[c.type] {

    import c.universe._

    /**
     * Translates an Emma expression to an Algorithm.
     *
     * @return
     */
    def parallelize[T: c.WeakTypeTag](root: Expr[T]): Expr[Algorithm[T]] = {

      // Create a normalized version of the original tree
      val normalizedTree = normalize(root.tree)

      // ----------------------------------------------------------------------
      // Code analysis
      // ----------------------------------------------------------------------

      // 1. Create control flow graph
      implicit var cfGraph = createControlFlowGraph(normalizedTree)

      // 2. Identify and isolate maximal comprehensions
      implicit var comprehensionView = createComprehensionView(cfGraph)

      // ----------------------------------------------------------------------
      // Code optimizations
      // ----------------------------------------------------------------------

      // 1. Inline comprehensions
      val optimizedTree = inlineComprehensions(normalizedTree)
      cfGraph = createControlFlowGraph(optimizedTree)
      comprehensionView = createComprehensionView(cfGraph)

      // 2. Apply Fold-Group-Fusion where possible
      foldGroupFusion(optimizedTree)

      // ----------------------------------------------------------------------
      // Final object assembly
      // ----------------------------------------------------------------------

      // construct algorithm object
      val algorithmCode =
        q"""
        new eu.stratosphere.emma.api.Algorithm[${c.weakTypeOf[T]}] {
           import scala.reflect._

           def run(engine: eu.stratosphere.emma.runtime.Engine): ${c.weakTypeOf[T]} = engine match {
             case _: eu.stratosphere.emma.runtime.Native => runNative()
             case _ => runParallel(engine)
           }

           private def runNative(): ${c.weakTypeOf[T]} = {
             ${untypecheck(root.tree)}
           }

           private def runParallel(engine: eu.stratosphere.emma.runtime.Engine): ${c.weakTypeOf[T]} = {
             ${compile(optimizedTree, cfGraph, comprehensionView)}
           }
        }
        """

      c.Expr[Algorithm[T]](c.typecheck(algorithmCode))
    }

    /**
     * Translates an Emma expression to an Algorithm.
     *
     * @return
     */
    def comprehend[T: c.WeakTypeTag](root: Expr[T]): Expr[Unit] = {

      // Create a normalized version of the original tree
      val normalizedTree = normalize(root.tree)

      // ----------------------------------------------------------------------
      // Code analysis
      // ----------------------------------------------------------------------

      // 1. Create control flow graph
      implicit var cfGraph = createControlFlowGraph(normalizedTree)

      // 2. Identify and isolate maximal comprehensions
      implicit var comprehensionView = createComprehensionView(cfGraph)

      // ----------------------------------------------------------------------
      // Code optimizations
      // ----------------------------------------------------------------------

      // 1. Inline comprehensions
      val optimizedTree = inlineComprehensions(normalizedTree)
      cfGraph = createControlFlowGraph(optimizedTree)
      comprehensionView = createComprehensionView(cfGraph)

      // 2. Apply Fold-Group-Fusion where possible
      foldGroupFusion(optimizedTree)

      // ----------------------------------------------------------------------
      // Final result assembly
      // ----------------------------------------------------------------------

      val code =
        q"""
        { ..${
          for (t <- comprehensionView.terms) yield
            q"""
            println("~" * 80)
            println("~ Comprehension `" + ${t.id.toString} + "` (original):")
            println("~" * 80)
            println(${t.comprehension.toString})
            println("~" * 80)
            println("~ Comprehension `" + ${t.id.toString} + "` (combinatros):")
            println("~" * 80)
            println(${combine(t.comprehension).toString})
            println("~" * 80)
            println("")
            println("~ Comprehension `" + ${t.id.toString} + "` (IR):")
            println("~" * 80)
            println(${showCode(compile(optimizedTree, cfGraph, comprehensionView))})
            println("~" * 80)
            println("")
            """
        }}"""


      c.Expr[Unit](c.typecheck(code))
    }
  }

}