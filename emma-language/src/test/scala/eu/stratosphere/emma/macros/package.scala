package eu.stratosphere.emma

import eu.stratosphere.emma.api.Algorithm
import eu.stratosphere.emma.macros.program.comprehension.TestMacros

import java.io.File

import scala.reflect.runtime.currentMirror
import scala.tools.reflect.ToolBox

package object macros {
  import scala.language.experimental.macros

  def parallelize[T](expr: T): Algorithm[T] = macro TestMacros.parallelize[T]
  def comprehend[T](expr: T): Unit = macro TestMacros.comprehend[T]
  def reComprehend[T](expr: T): Algorithm[T] = macro TestMacros.reComprehend[T]

  def mkToolBox(options: String = s"-cp $toolboxClasspath") =
    currentMirror.mkToolBox(options = options)

  def toolboxClasspath = {
    val file = new File(sys.props("emma.test.macro.toolboxcp"))
    if (!file.exists) sys.error(s"Output directory ${file.getAbsolutePath} does not exist")
    file.getAbsolutePath
  }
}
