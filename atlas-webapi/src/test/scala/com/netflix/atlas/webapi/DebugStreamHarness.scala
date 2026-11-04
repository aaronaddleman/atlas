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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.Sink
import com.netflix.atlas.pekko.RequestHandler

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
  * Entry point for a child JVM used by ExprApiDebugStreamSuite. Sends a single debug request
  * through the route and consumes the entity without retaining it, so the heap only reflects
  * what the endpoint itself holds. Prints a single result line:
  * `status=<code> bytes=<n> ttfbMs=<ms> totalMs=<ms>` or `failed=<message>` if the stream
  * terminates with an error.
  *
  * Args: `<dup-drop-repeats> [warmup]`. The query is `1` followed by that many
  * `:dup,:drop` pairs, which keeps the stack at a single item while the step count and the
  * remaining program grow with the repeats.
  */
object DebugStreamHarness {

  def dupDrop(repeats: Int): String = {
    val b = new java.lang.StringBuilder("1")
    var i = 0
    while (i < repeats) {
      b.append(",:dup,:drop")
      i += 1
    }
    b.toString
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("debug-stream-harness")
    try {
      val route = Route.toFunction(RequestHandler.standardOptions(new ExprApi().routes))
      def request(repeats: Int) = {
        val uri = Uri("/api/v1/expr/debug").withQuery(Uri.Query("q" -> dupDrop(repeats)))
        HttpRequest(uri = uri)
      }

      // Warm up in this JVM so class loading and JIT do not dominate the timed request
      if (args.length > 1 && args(1) == "warmup") {
        (1 to 3).foreach { _ =>
          val r = Await.result(route(request(args(0).toInt / 10)), 5.minutes)
          Await.result(r.entity.dataBytes.runWith(Sink.ignore), 5.minutes)
        }
      }

      val start = System.nanoTime()
      var first = -1L
      val response = Await.result(route(request(args(0).toInt)), 5.minutes)
      val done = response.entity.dataBytes
        .runWith(Sink.fold(0L) { (n, bs) =>
          if (first < 0) first = System.nanoTime()
          n + bs.size
        })
      try {
        val bytes = Await.result(done, 5.minutes)
        val end = System.nanoTime()
        val ttfb = (if (first < 0) end else first) - start
        println(
          s"status=${response.status.intValue} bytes=$bytes ttfbMs=${ttfb / 1000000} " +
            s"totalMs=${(end - start) / 1000000}"
        )
      } catch {
        case e: Exception => println(s"failed=${e.getMessage}")
      }
    } finally {
      Await.result(system.terminate(), 30.seconds)
    }
  }
}
