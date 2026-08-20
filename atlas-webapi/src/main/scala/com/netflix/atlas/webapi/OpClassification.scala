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

/**
  * Classifies ASL operators as predictable or unpredictable for the purpose of
  * picking chunk boundaries in the debug endpoint. An unpredictable operator is
  * one whose output stack size depends on runtime data (e.g. `:dup` doubles a
  * value, `:each` iterates a list, `:get` resolves a variable to an arbitrary
  * expression). Unknown operators default to predictable. Phase 1 is an
  * observability-only prototype that surfaces chunk plans in response headers;
  * once actual chunked evaluation is wired up, the default should flip to
  * unpredictable so new operators are always safely over-split until someone
  * reviews them.
  *
  * This table is a Phase 1 prototype. It intentionally lives in atlas-webapi
  * rather than on `Word` in atlas-core so the chunk planner can be validated
  * against real queries without a cross-module change; the drift risk is that
  * new operators added to atlas-core default to unpredictable here until
  * someone updates the table. A later phase is expected to move classification
  * onto `Word` itself.
  */
object OpClassification {

  private val unpredictable: Set[String] = Set(
    "dup",
    "each",
    "call",
    "fcall",
    "list",
    "nlist",
    "get",
    "map",
    "pick",
    "roll",
    "rot",
    "-rot",
    "over",
    "2over",
    "tuck",
    "swap",
    "nip",
    "ndrop",
    "drop",
    "clear",
    "depth",
    "freeze",
    "set",
    "sset"
  )

  /**
    * Returns true if the given token is an unpredictable operator and therefore
    * marks the start of a new chunk. Operands (non-`:`-prefixed tokens) are
    * always predictable because they just push a literal onto the stack.
    */
  def isUnpredictable(token: String): Boolean = {
    if (token.startsWith(":"))
      unpredictable.contains(token.substring(1))
    else
      false
  }
}
