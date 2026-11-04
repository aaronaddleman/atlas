/*
 * Copyright 2014-2026 Netflix, Inc.
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
package com.netflix.atlas.webapi

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import com.netflix.atlas.core.stacklang.Interpreter
import com.netflix.atlas.json3.Json
import com.netflix.atlas.pekko.RequestHandler
import com.netflix.atlas.pekko.testkit.MUnitRouteSuite

/**
  * The debug response is streamed, but the bytes must match what encoding the full list of
  * steps as a single json array would produce.
  */
class ExprApiDebugFormatSuite extends MUnitRouteSuite {

  import scala.concurrent.duration.*

  private implicit val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.second)

  private val vocabulary = ApiSettings.graphVocabulary

  private val vocabularies = {
    vocabulary.dependencies.map(v => v.name -> v).toMap + (vocabulary.name -> vocabulary)
  }

  private def routes: Route = RequestHandler.standardOptions(new ExprApi().routes)

  private def expected(query: String, vocab: String): String = {
    val interpreter = new Interpreter(vocabularies(vocab).allWords)
    val steps = interpreter.debug(query).map { step =>
      val stack = step.context.stack.map {
        case v: com.netflix.atlas.core.model.Expr => v.exprString
        case v                                    => v.toString
      }
      val vars = step.context.variables.map {
        case (k, v: com.netflix.atlas.core.model.Expr) => k -> v.exprString
        case (k, v)                                    => k -> v.toString
      }
      Map("program" -> step.program, "context" -> Map("stack" -> stack, "variables" -> vars))
    }
    Json.encode(steps)
  }

  private def testSame(query: String, vocab: String): Unit = {
    test(s"same bytes as Json.encode: $vocab $query") {
      val uri = Uri("/api/v1/expr/debug").withQuery(Uri.Query("q" -> query, "vocab" -> vocab))
      Get(uri) ~> routes ~> check {
        assertEquals(response.status, StatusCodes.OK)
        assertEquals(responseAs[String], expected(query, vocab))
      }
    }
  }

  private val vocabularyName = vocabulary.name

  testSame("name,sps,:eq", vocabularyName)
  testSame("name,sps,:eq,:sum,$name,:legend", "style")
  testSame("name,sps,:eq,:sum,$name,:legend,foo,:sset,foo,:get", vocabularyName)
  testSame("name,sps,:eq,cluster,:has,:and", "query")
  testSame("1,:dup,:drop,:dup,:drop", "std")
  testSame("(,a,b,),(,:dup,),:each", "std")
}
