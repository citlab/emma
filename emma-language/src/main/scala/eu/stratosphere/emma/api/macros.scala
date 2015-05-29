package eu.stratosphere.emma.api

import eu.stratosphere.emma.api.model.Identity
import eu.stratosphere.emma.macros.utility.UtilMacros
import eu.stratosphere.emma.macros.program.WorkflowMacros
/**
 * This is e playground specification of e function-oriented algebra for the Emma language.
 */
object emma {

  import scala.language.experimental.macros

  // -----------------------------------------------------
  // program macros
  // -----------------------------------------------------

  final def parallelize[T](e: T): Algorithm[T] = macro WorkflowMacros.parallelize[T]

  final def comprehend[T](e: T): Unit = macro WorkflowMacros.comprehend[T]

  final def desugar(e: Any): String = macro UtilMacros.desugar

  final def desugarRaw(e: Any): String = macro UtilMacros.desugarRaw

  final def analyze[T](e: T): T = macro UtilMacros.analyze[T]

  final def substituteClassNames[T](e: T): T = macro UtilMacros.substituteClassNames[T]
}