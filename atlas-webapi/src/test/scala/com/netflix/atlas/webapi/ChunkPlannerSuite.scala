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

import munit.FunSuite

class ChunkPlannerSuite extends FunSuite {

  private val maxChunks = 256

  test("empty query produces no chunks but still has a signature") {
    val plan = ChunkPlanner.plan("", maxChunks)
    assert(plan.chunks.isEmpty)
    assert(plan.signature.nonEmpty)
    assertEquals(plan.signature.length, 64)
  }

  test("all-predictable query collapses to a single chunk") {
    val plan = ChunkPlanner.plan("name,sps,:eq,:sum", maxChunks)
    assertEquals(plan.chunks.size, 1)
    val c = plan.chunks.head
    assertEquals(c.index, 0)
    assertEquals(c.start, 0)
    assertEquals(c.end, 4)
    assertEquals(c.tokens, List("name", "sps", ":eq", ":sum"))
    assertEquals(c.splitBefore, None)
  }

  test("split inserted immediately before each unpredictable op") {
    val plan = ChunkPlanner.plan("1,:dup,:dup", maxChunks)
    assertEquals(plan.chunks.size, 3)
    assertEquals(plan.chunks.map(_.tokens), List(List("1"), List(":dup"), List(":dup")))
    assertEquals(plan.chunks.map(_.splitBefore), List(None, Some(":dup"), Some(":dup")))
  }

  test("leading unpredictable op: first chunk starts with it, no split marker") {
    val plan = ChunkPlanner.plan(":dup,name,:eq", maxChunks)
    assertEquals(plan.chunks.size, 1)
    val c = plan.chunks.head
    assertEquals(c.splitBefore, None)
    assertEquals(c.tokens, List(":dup", "name", ":eq"))
  }

  test("trailing unpredictable op: final chunk is just that op") {
    val plan = ChunkPlanner.plan("name,:eq,:dup", maxChunks)
    assertEquals(plan.chunks.size, 2)
    assertEquals(plan.chunks.last.tokens, List(":dup"))
    assertEquals(plan.chunks.last.splitBefore, Some(":dup"))
  }

  test("alternating predictable/unpredictable") {
    val plan = ChunkPlanner.plan("1,:dup,2,:dup,3", maxChunks)
    assertEquals(
      plan.chunks.map(_.tokens),
      List(
        List("1"),
        List(":dup", "2"),
        List(":dup", "3")
      )
    )
  }

  test("chunk cap exceeded throws") {
    val query = (1 to 10).map(_ => ":dup").mkString(",")
    intercept[ChunkPlanner.ChunkLimitExceeded] {
      ChunkPlanner.plan(query, 5)
    }
  }

  test("signature is stable across cosmetic whitespace differences") {
    val a = ChunkPlanner.plan("name,sps,:eq,:sum", maxChunks)
    val b = ChunkPlanner.plan("  name , sps , :eq , :sum  ", maxChunks)
    assertEquals(a.signature, b.signature)
  }

  test("signature differs when tokens differ") {
    val a = ChunkPlanner.plan("name,sps,:eq", maxChunks)
    val b = ChunkPlanner.plan("name,sps,:eq,:sum", maxChunks)
    assertNotEquals(a.signature, b.signature)
  }

  test("chunks partition the token list exactly") {
    val plan = ChunkPlanner.plan("1,:dup,2,3,:dup,4", maxChunks)
    assertEquals(plan.chunks.head.start, 0)
    assertEquals(plan.chunks.last.end, 6)
    plan.chunks.sliding(2).foreach {
      case Seq(a, b) => assertEquals(a.end, b.start)
      case _         =>
    }
  }
}
