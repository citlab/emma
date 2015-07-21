package eu.stratosphere.emma.examples.graphs

import java.io.{PrintWriter, File}

import eu.stratosphere.emma.runtime
import eu.stratosphere.emma.testutil._

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalacheck.Arbitrary
import org.scalacheck.Gen._
import org.scalacheck.Prop.forAllNoShrink
import org.scalacheck.Test
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.prop.Checkers.check

@RunWith(classOf[JUnitRunner])
class SparseMatrixVectorMultiplicationTest extends FunSuite with Matchers {
  import SparseMatrixVectorMultiplicationTest._
  // default parameters
  val dir  = "/graphs/spmv"
  val path = tempPath(dir)
  val rt   = runtime.default()
  val eps  = 1e-9
  // initialize resources
  new File(path).mkdirs()

  test("Sparse matrix-vector multiplication") {
    check(
      forAllNoShrink(mvGen) { case (mat, vec) =>
        writeMatVec(mat, vec)
        1 to 3 forall { exp =>
          val result = new SparseMatrixVectorMultiplication(
              path, s"$path/result", exp, rt).algorithm.run(rt).fetch()

          val vecMap = result.map {
            vv => vv.index -> vv.value
          }.toMap withDefaultValue 0.0

          val actual = (0l to vecMap.keySet.max map vecMap).toVector
          val expect = mat ** (vec, exp)
          FileUtils.deleteQuietly(new File(s"$path/result"))
          println(s"actual: $actual")
          println(s"expect: $expect")
          actual zip expect forall {
            case (x, y) => (x / y - 1).abs < eps
          }
        }
      }, Test.Parameters.default withMinSuccessfulTests 5
    )
  }

  def writeMatVec(mat: Mat, vec: Vec) = {
    val vecWriter = new PrintWriter(s"$path/vector")
    val matWriter = new PrintWriter(s"$path/matrix")

    try for { // write vector
        i <- vec.indices
        if vec(i) != 0
      } vecWriter println s"$i\t${vec(i)}"
    finally vecWriter.close()

    try for { // write matrix
        i <- mat.indices
        j <- mat(i).indices
        if mat(i)(j) != 0
      } matWriter println s"$i\t$j\t${mat(i)(j)}"
    finally matWriter.close()
  }
}

object SparseMatrixVectorMultiplicationTest {
  type Vec = Vector[Double]
  type Mat = Vector[Vec   ]
  
  def arb[A: Arbitrary] = implicitly[Arbitrary[A]].arbitrary

  implicit class VectorOps(val self: Vec) extends AnyVal {
    def dot(that: Vec) = (self zip that map { case (x, y) => x * y }).sum
  }

  implicit class MatrixOps(val self: Mat) extends AnyVal {
    def * (that: Vec) =
      self map { _ dot that }

    def **(that: Vec, exp: Int = 1) =
      Stream.iterate(that) { self * _ } (exp)
  }

  val mvGen = for {
    n     <- posNum[Int]
    double = oneOf(posNum[Double], negNum[Double])
    vector = containerOfN[Vector, Double](n, double)
    matrix = containerOfN[Vector, Vec   ](n, vector)
    vec   <- vector
    mat   <- matrix
  } yield (mat, vec)
}
