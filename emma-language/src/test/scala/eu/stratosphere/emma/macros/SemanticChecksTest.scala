package eu.stratosphere.emma.macros

import eu.stratosphere.emma.api._
import eu.stratosphere.emma.api.model._

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.reflect.ClassTag
import scala.tools.reflect.ToolBoxError

@RunWith(classOf[JUnitRunner])
class SemanticChecksTest extends FlatSpec with Matchers with RuntimeUtil {
  import SemanticChecksTest.Foo

  lazy val tb = mkToolBox()
  import universe._

  val imports =
    q"import _root_.eu.stratosphere.emma.api._" ::
    q"import _root_.eu.stratosphere.emma.ir._" ::
    q"import _root_.eu.stratosphere.emma.macros.SemanticChecksTest.Foo" :: Nil

  "The updateWith* functions" should
    "throw an exception when the stateful is accessed from the updateWith* UDF" in {
      val bag = DataBag(Foo(1, 1) :: Nil)

      // these should throw exceptions:

      intercept[StatefulAccessedFromUdfException] {
        val withState = stateful[Foo, Int](bag)
        withState updateWithZero { _ => withState.bag() }
      }

      val updates = DataBag(Foo(1, 2) :: Nil)

      intercept[StatefulAccessedFromUdfException] {
        val withState = stateful[Foo, Int](bag)
        withState.updateWithOne(updates)(_.id, (_, _) => withState.bag())
      }

      intercept[StatefulAccessedFromUdfException] {
        val withState = stateful[Foo, Int](bag)
        withState.updateWithMany(updates)(_.id, (_, _) => withState.bag())
      }

      // and these should not throw exceptions:
      val withState = stateful[Foo, Int](bag)
      withState.updateWithZero { _ => DataBag() }
      withState.updateWithOne(updates)(_.id, (_, _) => DataBag())
      withState.updateWithMany(updates)(_.id, (_, _) => DataBag())
    }

  "parallelize" should "check for the stateful being accessed from the updateWith* UDF" in {
    val bagDef = q"val bag = DataBag(Foo(1, 1) :: Nil)"
    val updDef = q"val updates = DataBag(Foo(1, 2) :: Nil)"

    q"""{
      $bagDef
      emma.parallelize {
        val withState = stateful[Foo, Int](bag)
        withState updateWithZero { _ => withState.bag() }
      }
    }""" should failWith[StatefulAccessedFromUdfException]

    q"""{
      $bagDef
      $updDef
      emma.parallelize {
        val withState = stateful[Foo, Int](bag)
        withState.updateWithOne(updates)(_.id, (_, _) => withState.bag())
      }
    }""" should failWith[StatefulAccessedFromUdfException]

    q"""{
      $bagDef
      $updDef
      emma.parallelize {
        val withState = stateful[Foo, Int](bag)
        withState.updateWithMany(updates)(_.id, (_, _) => withState.bag())
      }
    }""" should failWith[StatefulAccessedFromUdfException]

    // this should compile
    val bag = DataBag(Foo(1, 1) :: Nil)
    emma.parallelize {
      val withState = stateful[Foo, Int](bag)
      val updates = DataBag(Foo(1, 2) :: Nil)
      withState.updateWithZero { _ => DataBag() }
      withState.updateWithOne(updates)(_.id, (_, _) => DataBag())
      withState.updateWithMany(updates)(_.id, (_, _) => DataBag())
    }
  }

  it should "check write access and application of outer methods / functions / variables / properties" in {
    type E = UnstableApplicationToEnclosingScopeException
    q"""
        object SomeObject {
          var x = 0
          emma.parallelize {
            List(23,42,99).foreach { y => x = x + y }
          }
        }
      """ should failWith[E]

    q"""
        object SomeObject {
          var x = 0
          emma.parallelize {
            List(23,42,99).foreach { y => x += y }
          }
        }
      """ should failWith[E]

    q"""
        object SomeObject {
          var x = 0
          emma.parallelize {
            x += 1
          }
        }
      """ should failWith[E]

    q"""
        object SomeClosure {
          var x = 0
          emma.parallelize {
            val lst = List(23,99,42) // disambiguation default arguments / Loc
            x = lst.foldLeft(Int.MinValue)(Math.max(_,_))
          }
        }
      """ should failWith[E]

    q"""
      class MyClass(var x:Int = 0) {
        emma.parallelize {
          val lst = List(23,99,42)
          x = lst.foldLeft(Int.MinValue)(Math.max(_,_))
        }
      }
      """ should failWith[E]

    q"""
       class MyClass {
        private var _x:Int = 42
        def x = _x
        def x_=(newX:Int) { _x = newX}

        emma.parallelize {
          val lst = List(23,99,42)
          x = lst.foldLeft(Int.MinValue)(Math.max(_,_))
        }
       }
      """ should failWith[E]

    noException should be thrownBy {tb.typecheck(withImports(
      q"""
        object MyObject {
          def getFourtyTwo:Int = 42
          emma.parallelize {
            List(1,2,3,getFourtyTwo, 99).map(_ * 2)
          }
        }
      """))}
  }

  def failWith[E <: Throwable : ClassTag] =
    include (implicitly[ClassTag[E]].runtimeClass.getSimpleName) compose { (tree: Tree) =>
      intercept[ToolBoxError] { tb.typecheck(withImports(tree)) }.getMessage
    }

  def withImports(tree: universe.Tree): universe.Tree = q"{ ..$imports; $tree }"
}

object SemanticChecksTest {
  case class Foo(@id id: Int, var n: Int) extends Identity[Int] {
    override def identity = id
  }
}
