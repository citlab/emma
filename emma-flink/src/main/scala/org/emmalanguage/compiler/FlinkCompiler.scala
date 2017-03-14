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
package compiler

trait FlinkCompiler extends Compiler {

  override lazy val implicitTypes: Set[u.Type] = API.implicitTypes ++ FlinkAPI.implicitTypes

  object FlinkAPI extends BackendAPI {

    lazy val implicitTypes = Set(
      api.Type[org.apache.flink.api.common.typeinfo.TypeInformation[Any]].typeConstructor,
      api.Type[org.apache.flink.api.scala.ExecutionEnvironment]
    )

    lazy val DataBag = new DataBagAPI(api.Sym[org.emmalanguage.api.FlinkDataSet[Any]].asClass)

    lazy val DataBag$ = new DataBag$API(api.Sym[org.emmalanguage.api.FlinkDataSet.type].asModule)

    lazy val MutableBag = new MutableBagAPI(api.Sym[org.emmalanguage.api.FlinkMutableBag[Any, Any]].asClass)

    lazy val MutableBag$ = new MutableBag$API(api.Sym[org.emmalanguage.api.FlinkMutableBag.type].asModule)

    lazy val Ops = new OpsAPI(api.Sym[org.emmalanguage.api.flink.FlinkOps.type].asModule)
  }

}
