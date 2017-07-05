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
package api

import alg.Alg

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import scala.language.implicitConversions
import scala.util.hashing.MurmurHash3

/** A `DataBag` implementation backed by a Spark `Dataset`. */
class SparkDataset[A: Meta] private[api](@transient private[emmalanguage] val rep: Dataset[A]) extends DataBag[A] {

  import Meta.Projections._
  import SparkDataset.encoderForType
  import SparkDataset.wrap
  import api.spark.fromRDD

  import rep.sparkSession.sqlContext.implicits._

  @transient override val m = implicitly[Meta[A]]
  private[emmalanguage] implicit def spark = this.rep.sparkSession

  // -----------------------------------------------------
  // Structural recursion
  // -----------------------------------------------------

  override def fold[B: Meta](alg: Alg[A, B]): B =
    try {
      rep.map(x => alg.init(x)).reduce(alg.plus)
    } catch {
      case e: UnsupportedOperationException if e.getMessage == "empty collection" => alg.zero
      case e: Throwable => throw e
    }

  // -----------------------------------------------------
  // Monad Ops
  // -----------------------------------------------------

  override def map[B: Meta](f: (A) => B): DataBag[B] =
    rep.map(f)

  override def flatMap[B: Meta](f: (A) => DataBag[B]): DataBag[B] =
    rep.flatMap((x: A) => f(x).collect())

  def withFilter(p: (A) => Boolean): DataBag[A] =
    rep.filter(p)

  // -----------------------------------------------------
  // Grouping
  // -----------------------------------------------------

  override def groupBy[K: Meta](k: (A) => K): DataBag[Group[K, DataBag[A]]] =
    DataBag.from(rep.rdd).groupBy(k)

  // -----------------------------------------------------
  // Set operations
  // -----------------------------------------------------

  override def union(that: DataBag[A]): DataBag[A] = that match {
    case dbag: ScalaSeq[A] => this union SparkDataset(dbag.rep)
    case dbag: SparkRDD[A] => this.rep union dbag.rep.toDS()
    case dbag: SparkDataset[A] => this.rep union dbag.rep
    case _ => throw new IllegalArgumentException(s"Unsupported rhs for `union` of type: ${that.getClass}")
  }

  override def distinct: DataBag[A] =
    rep.distinct

  // -----------------------------------------------------
  // Partition-based Ops
  // -----------------------------------------------------

  def sample(k: Int, seed: Long = 5394826801L): Vector[A] = {
    // counts per partition, sorted by partition ID
    val Seq(hd, tl@_*) = rep.rdd.zipWithIndex()
      .mapPartitionsWithIndex({ (pid, it) =>
        val sample = Array.fill(k)(Option.empty[A])
        for ((e, i) <- it) {
          if (i >= k) {
            val j = util.RanHash(seed).at(i).nextLong(i + 1)
            if (j < k) sample(j.toInt) = Some(e)
          } else sample(i.toInt) = Some(e)
        }
        Seq(pid -> sample).toIterator
      }).collect().sortBy(_._1).map(_._2).toSeq

    // merge the sequence of samples and filter None values
    val rs = for {
      Some(v) <- tl.foldLeft(hd)((xs, ys) => for ((x, y) <- xs zip ys) yield y orElse x)
    } yield v

    rs.toVector
  }

  def zipWithIndex(): DataBag[(A, Long)] =
    DataBag.from(rep.rdd.zipWithIndex())

  // -----------------------------------------------------
  // Sinks
  // -----------------------------------------------------

  override def writeCSV(path: String, format: CSV)
    (implicit converter: CSVConverter[A]): Unit = rep.write
      .option("header", format.header)
      .option("delimiter", format.delimiter.toString)
      .option("charset", format.charset.toString)
      .option("quote", format.quote.getOrElse('"').toString)
      .option("escape", format.escape.getOrElse('\\').toString)
      .option("nullValue", format.nullValue)
      .mode("overwrite").csv(path)

  override def writeText(path: String): Unit =
    rep.write.text(path)

  def writeParquet(path: String, format: Parquet)
    (implicit converter: ParquetConverter[A]): Unit = rep.write
      .option("binaryAsString", format.binaryAsString)
      .option("int96AsTimestamp", format.int96AsTimestamp)
      .option("cacheMetadata", format.cacheMetadata)
      .option("codec", format.codec.toString)
      .mode("overwrite").parquet(path)

  def collect(): Seq[A] = collected

  private lazy val collected: Seq[A] =
    rep.collect()

  // -----------------------------------------------------
  // Pre-defined folds
  // -----------------------------------------------------

  override def reduceOption(p: (A, A) => A): Option[A] =
    try {
      Option(rep.reduce(p))
    } catch {
      case e: UnsupportedOperationException if e.getMessage == "empty collection" => None
      case e: Throwable => throw e
    }

  override def find(p: A => Boolean): Option[A] =
    try {
      Option(rep.filter(p).head())
    } catch {
      case e: NoSuchElementException if e.getMessage == "next on empty iterator" => None
      case e: Throwable => throw e
    }

  override def min(implicit o: Ordering[A]): A =
    reduceOption(o.min).get

  override def max(implicit o: Ordering[A]): A =
    reduceOption(o.max).get

  // -----------------------------------------------------
  // equals and hashCode
  // -----------------------------------------------------

  override def equals(o: Any): Boolean =
    super.equals(o)

  override def hashCode(): Int = {
    val (a, b, c, n) = rep
      .mapPartitions(it => {
        var a, b, n = 0
        var c = 1
        it foreach { x =>
          val h = x.##
          a += h
          b ^= h
          if (h != 0) c *= h
          n += 1
        }
        Some((a, b, c, n)).iterator
      })
      .collect()
      .fold((0, 0, 1, 0))((x, r) => (x, r) match {
        case ((a1, b1, c1, n1), (a2, b2, c2, n2)) => (a1 + a2, b1 ^ b2, c1 * c2, n1 + n2)
      })

    var h = MurmurHash3.traversableSeed
    h = MurmurHash3.mix(h, a)
    h = MurmurHash3.mix(h, b)
    h = MurmurHash3.mixLast(h, c)
    MurmurHash3.finalizeHash(h, n)
  }
}

object SparkDataset extends DataBagCompanion[SparkSession] {

  import Meta.Projections._

  implicit def encoderForType[T: Meta]: Encoder[T] =
    ExpressionEncoder[T]

  // ---------------------------------------------------------------------------
  // Constructors
  // ---------------------------------------------------------------------------

  def empty[A: Meta](
    implicit spark: SparkSession
  ): DataBag[A] = spark.emptyDataset[A]

  def apply[A: Meta](values: Seq[A])(
    implicit spark: SparkSession
  ): DataBag[A] = spark.createDataset(values)

  def readText(path: String)(
    implicit spark: SparkSession
  ): DataBag[String] = spark.read.textFile(path)

  def readCSV[A: Meta : CSVConverter](path: String, format: CSV)(
    implicit spark: SparkSession
  ): DataBag[A] = spark.read
    .option("header", format.header)
    .option("delimiter", format.delimiter.toString)
    .option("charset", format.charset.toString)
    .option("quote", format.quote.getOrElse('"').toString)
    .option("escape", format.escape.getOrElse('\\').toString)
    .option("comment", format.escape.map(_.toString).orNull)
    .option("nullValue", format.nullValue)
    .schema(encoderForType[A].schema)
    .csv(path).as[A]

  def readParquet[A: Meta : ParquetConverter](path: String, format: Parquet)(
    implicit spark: SparkSession
  ): DataBag[A] = spark.read
    .option("binaryAsString", format.binaryAsString)
    .option("int96AsTimestamp", format.int96AsTimestamp)
    .option("cacheMetadata", format.cacheMetadata)
    .option("codec", format.codec.toString)
    .schema(encoderForType[A].schema)
    .parquet(path).as[A]

  // ---------------------------------------------------------------------------
  // Implicit Rep -> DataBag conversion
  // ---------------------------------------------------------------------------

  implicit def wrap[A: Meta](rep: Dataset[A]): DataBag[A] =
    new SparkDataset(rep)
}
