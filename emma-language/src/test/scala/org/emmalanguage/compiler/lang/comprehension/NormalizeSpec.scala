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
package compiler.lang.comprehension

import api._
import compiler.BaseCompilerSpec
import compiler.ir.ComprehensionSyntax._
import test.schema.Marketing._

import java.time.Instant

/** A spec for comprehension normalization. */
class NormalizeSpec extends BaseCompilerSpec {

  import compiler._
  import universe.reify

  val lnfPipeline: u.Expr[Any] => u.Tree =
    pipeline(typeCheck = true)(
      Core.lnf
    ).compose(_.tree)

  val resugarPipeline: u.Expr[Any] => u.Tree =
    pipeline(typeCheck = true)(
      Core.lnf,
      Comprehension.resugar(API.DataBag.sym)
    ).compose(_.tree)

  val normalizePipeline: u.Expr[Any] => u.Tree =
    pipeline(typeCheck = true)(
      Core.lnf,
      Comprehension.resugar(API.DataBag.sym),
      tree => time(Comprehension.normalize(API.DataBag.sym)(tree), "normalize")
    ).compose(_.tree)

  // ---------------------------------------------------------------------------
  // Spec data: a collection of (desugared, resugared) trees
  // ---------------------------------------------------------------------------

  // hard coded: 2 generators
  val (inp1, exp1) = {
    val inp = reify {
      val users$1 = users
      val names = flatten[(User, Click), DataBag] {
        comprehension[DataBag[(User, Click)], DataBag] {
          val u = generator(users$1)
          head {
            val clicks$1 = clicks
            val compr$1 = comprehension[(User, Click), DataBag] {
              val c = generator(clicks$1)
              head(u, c)
            }
            compr$1
          }
        }
      }
      names
    }

    val exp = reify {
      val users$1 = users
      val clicks$1 = clicks
      val names = comprehension[(User, Click), DataBag] {
        val u = generator(users$1)
        val c = generator(clicks$1)
        head(u, c)
      }
      names
    }

    (inp, exp)
  }

  // hard coded: 3 generators
  val (inp2, exp2) = {
    val inp = reify {
      val users$1 = users
      val names = flatten[(Ad, User, Click), DataBag] {
        comprehension[DataBag[(Ad, User, Click)], DataBag] {
          val u = generator(users$1)
          head {
            val clicks$1 = clicks
            val flatten$1 = flatten[(Ad, User, Click), DataBag] {
              comprehension[DataBag[(Ad, User, Click)], DataBag] {
                val c = generator(clicks$1)
                head {
                  val ads$1 = ads
                  val compr$1 = comprehension[(Ad, User, Click), DataBag] {
                    val a = generator(ads$1)
                    head(a, u, c)
                  }
                  compr$1
                }
              }
            }
            flatten$1
          }
        }
      }
      names
    }

    val exp = reify {
      val users$1 = users
      val clicks$1 = clicks
      val ads$1 = ads
      val names = comprehension[(Ad, User, Click), DataBag] {
        val u = generator(users$1)
        val c = generator(clicks$1)
        val a = generator(ads$1)
        head(a, u, c)
      }
      names
    }

    (inp, exp)
  }

  // hard-coded: 3 generators and a filter
  val (inp3, exp3) = {
    val inp = reify {
      val users$1 = users
      val names = flatten[(Ad, User, Click), DataBag] {
        comprehension[DataBag[(Ad, User, Click)], DataBag] {
          val u = generator(users$1)
          head {
            val clicks$1 = clicks
            val compr$1 = comprehension[Click, DataBag] {
              val c = generator(clicks$1)
              guard(c.userID == u.id)
              head(c)
            }
            val flatten$1 = flatten[(Ad, User, Click), DataBag] {
              comprehension[DataBag[(Ad, User, Click)], DataBag] {
                val c = generator(compr$1)
                head {
                  val ads$1 = ads
                  val compr$2 = comprehension[(Ad, User, Click), DataBag] {
                    val a = generator(ads$1)
                    head(a, u, c)
                  }
                  compr$2
                }
              }
            }
            flatten$1
          }
        }
      }
      names
    }

    val exp = reify {
      val users$1 = users
      val clicks$1 = clicks
      val ads$1 = ads
      val names = comprehension[(Ad, User, Click), DataBag] {
        val u = generator(users$1)
        val c = generator(clicks$1)
        guard(c.userID == u.id)
        val a = generator(ads$1)
        head(a, u, c)
      }
      names
    }

    (inp, exp)
  }

  // resugared: three generators and two filters at the end
  val (inp4, exp4) = {
    val inp = reify {
      for {
        u <- users
        a <- ads
        c <- clicks
        if u.id == c.userID
        if a.id == c.adID
      } yield (c.time, a.`class`)
    }

    val exp = reify {
      val users$1 = users
      val ads$1 = ads
      val clicks$1 = clicks
      val compr$1 = comprehension[(Instant, AdClass.Value), DataBag] {
        val u = generator(users$1)
        val a = generator(ads$1)
        val c = generator(clicks$1)
        guard(u.id == c.userID)
        guard(a.id == c.adID)
        head(c.time, a.`class`)
      }
      compr$1
    }

    (inp, exp)
  }

  // resugared: three generators and two interleaved filters
  val (inp5, exp5) = {
    val inp = reify {
      for {
        c <- clicks
        u <- users
        if u.id == c.userID
        a <- ads
        if a.id == c.adID
      } yield (c.time, a.`class`)
    }

    val exp = reify {
      val clicks$1 = clicks
      val users$1 = users
      val ads$1 = ads
      val compr$1 = comprehension[(Instant, AdClass.Value), DataBag] {
        val c = generator(clicks$1)
        val u = generator(users$1)
        guard(u.id == c.userID)
        val a = generator(ads$1)
        guard(a.id == c.adID)
        head(c.time, a.`class`)
      }
      compr$1
    }

    (inp, exp)
  }

  // resugared: one patmat generator and no filters
  val (inp6, exp6) = {
    val inp = reify {
      for {
        Click(adID, _, time) <- clicks
      } yield (adID, time)
    }

    val exp = reify {
      val clicks$1 = clicks
      val compr$1 = comprehension[(Long, Instant), DataBag] {
        val c = generator(clicks$1)
        head(c.adID, c.time)
      }
      compr$1
    }

    (inp, exp)
  }

  // resugared: patmat with two generators and one filter
  val (inp7, exp7) = {
    val inp = reify {
      for {
        Click(adID, _, time) <- clicks
        Ad(id, _, _) <- ads
        if id == adID
      } yield (adID, time)
    }

    val exp = reify {
      val clicks$1 = clicks
      val ads$1 = ads
      val compr$1 = comprehension[(Long, Instant), DataBag] {
        val c = generator(clicks$1)
        val a = generator(ads$1)
        guard {
          val adID = c.adID
          val id = a.id
          id == adID
        }
        head(c.adID, c.time)
      }
      compr$1
    }

    (inp, exp)
  }

  val (inp8, exp8) = {
    val inp = reify {
      val xs = for {
        Click(adID, _, time) <- clicks
        Ad(id, _, _) <- ads
        if id == adID
      } yield time.toEpochMilli
      xs.fetch()
    }

    val exp = reify {
      val clicks$1 = clicks
      val ads$1 = ads
      val xs = comprehension[Long, DataBag] {
        val c = generator(clicks$1)
        val a = generator(ads$1)
        guard {
          val adID = c.adID
          val id = a.id
          id == adID
        }
        head(c.time.toEpochMilli)
      }
      xs.fetch()
    }

    (inp, exp)
  }

  val (inp9, exp9) = {
    val inp = reify {
      val xs = for {
        Ad(_, name, _) <- ads
        token <- DataBag(name.split("\\s+"))
      } yield token
      xs.fetch()
    }

    val exp = reify {
      val ads$1 = ads
      val xs = comprehension[String, DataBag]({
        val a = generator(ads$1)
        val token = generator(DataBag(a.name.split("\\s+")))
        head(token)
      })
      xs.fetch()
    }

    (inp, exp)
  }

  val (inp10, exp10) = {
    val inp = reify {
      // pre-process
      val xs = for {
        Ad(id, name, _) <- ads
      } yield (id, name)
      // self-join
      val ys = for {
        (id1, name1) <- xs
        (id2, name2) <- xs
        if id1 == id2
      } yield (name1, name2)
      // fetch
      ys.fetch()
    }

    val exp = reify {
      val ads$1 = ads
      // pre-process
      val xs = comprehension[(Long, String), DataBag] {
        val a = generator(ads$1)
        head(a.id, a.name)
      }
      // self-join
      val ys = comprehension[(String, String), DataBag] {
        val x$1 = generator(xs)
        val x$2 = generator(xs)
        guard(x$1._1 == x$2._1)
        head(x$1._2, x$2._2)
      }
      ys.fetch()
    }

    (inp, exp)
  }

  // with two correlated generators
  val (inp11, exp11) = {
    val inp = reify {
      for {
        x <- xs
        y <- DataBag(Seq(x, x))
      } yield (x, y)
    }

    val exp = reify {
      val xs$1 = xs
      val compr$1 = comprehension[(Int, Int), DataBag] {
        val x = generator(xs$1)
        val y = generator(DataBag(Seq(x, x)))
        head(x, y)
      }
      compr$1
    }

    (inp, exp)
  }

  // ---------------------------------------------------------------------------
  // Spec tests
  // ---------------------------------------------------------------------------

  "normalize hard-coded comprehensions" - {
    "with two generators" in {
      normalizePipeline(inp1) shouldBe alphaEqTo(lnfPipeline(exp1))
    }
    "with three generators" in {
      normalizePipeline(inp2) shouldBe alphaEqTo(lnfPipeline(exp2))
    }
    "with three generators and two filters" in {
      normalizePipeline(inp3) shouldBe alphaEqTo(lnfPipeline(exp3))
    }
  }

  "normalize resugared comprehensions" - {
    "with three generators and two filters at the end" in {
      normalizePipeline(inp4) shouldBe alphaEqTo(resugarPipeline(exp4))
    }
    "with three generators and two interleaved filters" in {
      normalizePipeline(inp5) shouldBe alphaEqTo(resugarPipeline(exp5))
    }
    "with one patmat generator and no filters" in {
      normalizePipeline(inp6) shouldBe alphaEqTo(resugarPipeline(exp6))
    }
    "with two patmat generators and one filter (at the end)" in {
      normalizePipeline(inp7) shouldBe alphaEqTo(resugarPipeline(exp7))
    }
    "with two patmat generators and one filter (itermediate result)" in {
      normalizePipeline(inp8) shouldBe alphaEqTo(resugarPipeline(exp8))
    }
    "with two correlated generators and no filters" in {
      normalizePipeline(inp9) shouldBe alphaEqTo(resugarPipeline(exp9))
    }
    "with a dag-shaped self-join" in {
      normalizePipeline(inp10) shouldBe alphaEqTo(resugarPipeline(exp10))
    }
    "with two correlated generators" in {
      normalizePipeline(inp11) shouldBe alphaEqTo(resugarPipeline(exp11))
    }
  }
}
