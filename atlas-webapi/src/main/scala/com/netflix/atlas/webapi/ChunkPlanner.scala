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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import com.netflix.atlas.core.stacklang.Interpreter

/**
  * Splits an ASL query into a sequence of chunks at unpredictable-operator
  * boundaries. See [[OpClassification]] for the policy used to pick those
  * boundaries. Phase 1 uses the plan for observability only — the debug
  * endpoint still evaluates the full query in one pass.
  */
object ChunkPlanner {

  /** A contiguous slice of tokens that can be evaluated as one unit. */
  final case class Chunk(
    index: Int,
    start: Int,
    end: Int,
    tokens: List[String],
    splitBefore: Option[String]
  )

  /**
    * Plan for evaluating a query in chunks.
    *
    * @param signature SHA-256 hex digest over the canonical token list. Used
    *                  to bind a client to the exact query that was planned.
    * @param chunks    Ordered list of chunks covering the entire token list.
    *                  Empty when the query produces no tokens.
    */
  final case class Plan(signature: String, chunks: List[Chunk])

  /** Thrown when a query's plan would exceed the configured chunk cap. */
  class ChunkLimitExceeded(val total: Int, val limit: Int)
      extends IllegalArgumentException(
        s"query produces $total chunks, exceeds limit of $limit"
      )

  /**
    * Split `query` into chunks. Tokenization uses `Interpreter.splitAndTrim`,
    * which strips comments and whitespace, so the resulting signature is
    * stable across cosmetic differences in the input string.
    */
  def plan(query: String, maxChunks: Int): Plan = {
    val tokens = Interpreter.splitAndTrim(query)
    val canonical = tokens.mkString(",")
    val signature = sha256Hex(canonical)

    if (tokens.isEmpty) {
      Plan(signature, Nil)
    } else {
      val boundaries = 0 :: tokens.zipWithIndex.collect {
        case (tok, i) if i > 0 && OpClassification.isUnpredictable(tok) => i
      }

      val chunkCount = boundaries.size
      if (chunkCount > maxChunks) {
        throw new ChunkLimitExceeded(chunkCount, maxChunks)
      }

      val ends = boundaries.drop(1) :+ tokens.size
      val chunks = boundaries.zip(ends).zipWithIndex.map {
        case ((start, end), idx) =>
          val slice = tokens.slice(start, end)
          val split =
            if (idx == 0) None
            else Some(tokens(start))
          Chunk(idx, start, end, slice, split)
      }
      Plan(signature, chunks)
    }
  }

  private def sha256Hex(input: String): String = {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.getBytes(StandardCharsets.UTF_8))
    val sb = new StringBuilder(bytes.length * 2)
    var i = 0
    while (i < bytes.length) {
      sb.append(f"${bytes(i) & 0xFF}%02x")
      i += 1
    }
    sb.result()
  }
}
