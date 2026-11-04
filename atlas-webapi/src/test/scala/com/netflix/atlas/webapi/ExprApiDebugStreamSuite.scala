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

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

/**
  * Bounds for the streamed debug response. Each case runs in a forked JVM so that a small heap
  * can be used and an OutOfMemoryError cannot take down the build.
  */
class ExprApiDebugStreamSuite extends munit.FunSuite {

  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(10, TimeUnit.MINUTES)

  private def classpath: String = {
    val urls = Iterator
      .iterate(getClass.getClassLoader)(_.getParent)
      .takeWhile(_ != null)
      .flatMap {
        case u: URLClassLoader => u.getURLs.toList
        case _                 => Nil
      }
      .map(u => new File(u.toURI).getPath)
      .toList
      .distinct
    (urls ++ System.getProperty("java.class.path").split(File.pathSeparator)).distinct
      .mkString(File.pathSeparator)
  }

  /** Runs the harness in a child JVM and returns (exit code, combined output). */
  private def run(
    heap: String,
    repeats: Int,
    props: Map[String, String],
    warmup: Boolean = false
  ): (Int, String) = {
    val java = new File(System.getProperty("java.home"), "bin/java").getPath
    val cmd = List(java, s"-Xmx$heap", "-XX:+ExitOnOutOfMemoryError") ++
      props.map { case (k, v) => s"-D$k=$v" } ++
      List("-cp", classpath, "com.netflix.atlas.webapi.DebugStreamHarness", repeats.toString) ++
      (if (warmup) List("warmup") else Nil)
    val proc = new ProcessBuilder(cmd.asJava).redirectErrorStream(true).start()
    val out = new String(proc.getInputStream.readAllBytes())
    (proc.waitFor(), out)
  }

  private def field(out: String, name: String): Long = {
    val m = s"$name=(\\d+)".r.findFirstMatchIn(out)
    m.map(_.group(1).toLong).getOrElse(fail(s"no $name in output:\n$out"))
  }

  test("memory: large dup-drop query completes within a small heap") {
    val (code, out) = run("256m", 10000, Map("atlas.webapi.expr.debug.max-steps" -> "1000000"))
    assert(out.contains("status=200"), s"exit=$code\n${out.takeRight(2000)}")
    assert(!out.contains("OutOfMemoryError"), out.takeRight(2000))
  }

  test("time to first byte: grows roughly linearly with query size") {
    val props = Map("atlas.webapi.expr.debug.max-steps" -> "1000000")
    val n = 3000
    val small = field(run("6g", n, props, warmup = true)._2, "ttfbMs")
    val large = field(run("6g", 2 * n, props, warmup = true)._2, "ttfbMs")
    val ratio = large.toDouble / math.max(small, 1)
    println(s"ttfb small=${small}ms large=${large}ms ratio=$ratio")
    assert(ratio <= 2.5, s"TTFB(2N)/TTFB(N) = $ratio, expected <= 2.5")
  }

  test("step cap: a query past max-steps ends with an error") {
    val (_, out) = run("512m", 2000, Map("atlas.webapi.expr.debug.max-steps" -> "1000"))
    assert(out.contains("failed="), s"expected the stream to fail:\n${out.takeRight(2000)}")
  }
}
