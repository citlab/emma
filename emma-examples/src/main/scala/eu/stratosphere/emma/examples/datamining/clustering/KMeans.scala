package eu.stratosphere.emma.examples.datamining.clustering

import eu.stratosphere.emma.api._
import eu.stratosphere.emma.api.model._
import eu.stratosphere.emma.examples.Algorithm
import eu.stratosphere.emma.runtime.Engine
import net.sourceforge.argparse4j.inf.{Namespace, Subparser}
import org.apache.spark.util.Vector

import scala.util.Random

object KMeans {

  // constnats
  val SEED = 5431423142056L

  object Command {
    // argument names
    val KEY_K = "K"
    val KEY_EPSILON = "epsilon"
    val KEY_INPUT = "input-file"
    val KEY_OUTPUT = "output-file"
  }

  class Command extends Algorithm.Command[KMeans] {

    // algorithm names
    override def name = "k-means"

    override def description = "K-Means Clustering"

    override def setup(parser: Subparser) = {
      // basic setup
      super.setup(parser)

      // add arguments
      parser.addArgument(Command.KEY_K)
        .`type`[Integer](classOf[Integer])
        .dest(Command.KEY_K)
        .metavar("K")
        .help("number of clusters")
      parser.addArgument(Command.KEY_EPSILON)
        .`type`[Integer](classOf[Integer])
        .dest(Command.KEY_EPSILON)
        .metavar("EPSILON")
        .help("termination threshold")
      // add arguments
      parser.addArgument(Command.KEY_INPUT)
        .`type`[String](classOf[String])
        .dest(Command.KEY_INPUT)
        .metavar("INPUT")
        .help("input file")
      parser.addArgument(Command.KEY_OUTPUT)
        .`type`[String](classOf[String])
        .dest(Command.KEY_OUTPUT)
        .metavar("OUTPUT")
        .help("output file ")
    }
  }

  // --------------------------------------------------------------------------------------------
  // ----------------------------------- tpch -------------------------------------------------
  // --------------------------------------------------------------------------------------------

  object Schema {

    type PID = Long

    case class Point(@id id: PID, pos: Vector) extends Identity[PID] {
      def identity = id
    }

    case class Solution(point: Point, clusterID: PID) {}

  }

}

class KMeans(k: Int, epsilon: Double, inputUrl: String, outputUrl: String, rt: Engine) extends Algorithm(rt) {

  def this(ns: Namespace, rt: Engine) = this(
    ns.get[Int](KMeans.Command.KEY_K),
    ns.get[Double](KMeans.Command.KEY_EPSILON),
    ns.get[String](KMeans.Command.KEY_INPUT),
    ns.get[String](KMeans.Command.KEY_OUTPUT),
    rt)

  def run() = {

    import eu.stratosphere.emma.examples.datamining.clustering.KMeans.Schema._

    val algorithm = /*emma.parallelize*/ {
      // read input
      val points = read(inputUrl, new CSVInputFormat[Point])

      // initialize random cluster means
      val random = new Random(KMeans.SEED)
      var centroids = DataBag(for (i <- 1 to k) yield Point(i, Vector(random.nextDouble(), random.nextDouble(), random.nextDouble())))
      var change = 0.0

      // initialize solution
      var solution = for (p <- points) yield {
        val closestCentroid = centroids.minBy((m1, m2) => (p.pos squaredDist m1.pos) < (p.pos squaredDist m2.pos)).get
        Solution(p, closestCentroid.id)
      }

      do {
        // update means
        val newMeans = for (cluster <- solution.groupBy(_.clusterID)) yield {
          val sum = (for (p <- cluster.values) yield p.point.pos).reduce(Vector.zeros(3)) { _ + _ }
          val cnt = (for (p <- cluster.values) yield p.point.pos).count()
          Point(cluster.key, sum / cnt)
        }

        // compute change between the old and the new means
        change = {
          val distances = for (mean <- centroids; newMean <- newMeans; if mean.id == newMean.id) yield mean.pos squaredDist newMean.pos
          distances.sum()
        }

        // update solution: re-assign clusters
        solution = for (s <- solution) yield {
          val closestMean = centroids.minBy((m1, m2) => (s.point.pos squaredDist m1.pos) < (s.point.pos squaredDist m2.pos)).get
          s.copy(clusterID = closestMean.id)
        }

        // use new means for the next iteration
        centroids = newMeans
      } while (change < epsilon)

      // write result
      write(outputUrl, new CSVOutputFormat[(PID, PID)])(for (s <- solution) yield (s.point.id, s.clusterID))
    }

    //algorithm.run(rt)
  }
}

