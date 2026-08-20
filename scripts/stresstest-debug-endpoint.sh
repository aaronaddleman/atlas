#!/bin/bash
#
# Stress test for /api/v1/expr/debug step-count memory/response growth.
#
# The stack-doubling attack (:each/:fcall/:dup used to exponentially grow
# the stack) is already blocked on main by the maxStackSize guard added in
# nextStep() (see Interpreter.scala, PR #1892) - that returns a fast 400
# well before any real memory pressure.
#
# This test drives two different attack shapes, because the chunk-based
# debug endpoint on this branch defends against them with two independent
# limits that don't overlap:
#
#   dup-drop  A stack-pinned ,:dup,:drop chain (stack size stays at 1) so
#             it never trips the stack-size guard, while still driving the
#             step/token count arbitrarily high. Since :dup and :drop are
#             both "unpredictable" operators, every repeat creates a new
#             chunk boundary, so this shape is expected to trip
#             max-chunks-per-query (ChunkPlanner.ChunkLimitExceeded).
#
#   literal   A stack-pinned n,x,:eq,:and chain (query vocabulary). :eq and
#             :and both have fixed arity (2 in, 1 out) so they're "predictable"
#             per OpClassification - the whole query collapses into a single
#             chunk regardless of N, so max-chunks-per-query never fires for
#             this shape. Because each block nets the stack back down to a
#             single query, it also never trips the unrelated pre-existing
#             maxStackSize guard (unlike a naive 1,1,1,... chain, where every
#             token pushes and nothing pops, tripping that guard around N=1024
#             long before max-steps could ever be reached). This is expected
#             to instead trip max-steps (ExprApi.StepLimitExceeded).
#
# On main, /api/v1/expr/debug returns one JSON entry per step for both
# shapes, so response size and memory grow linearly (or worse, once JSON
# encoding overhead is counted) with N. On this branch, both shapes should
# instead fail fast with a bounded 400 response once N crosses the
# relevant limit, rather than letting the response grow without bound.
#
# Usage:
#   ./scripts/stresstest.sh [--wait] [pattern] [step] [repeats]
#
# pattern  "dup-drop" (default) or "literal" - selects which attack shape
#          to drive, see above.
# --wait   Poll the healthcheck until Atlas reports healthy instead of
#          failing immediately. Useful because atlas-lwcapi's
#          StartupDelayService holds healthcheck at 500 for a warm-up
#          period (startup-delay, 3m by default) after the process starts.
#
# Examples:
#   ./scripts/stresstest.sh                  # dup-drop, step=100, repeats=2
#   ./scripts/stresstest.sh literal          # literal, step=100, repeats=2
#   ./scripts/stresstest.sh dup-drop 50      # dup-drop, step=50, repeats=2
#   ./scripts/stresstest.sh literal 200 3    # literal, step=200, repeats=3
#   ./scripts/stresstest.sh --wait           # wait for healthcheck, then dup-drop, step=100, repeats=2

ATLAS_HOST="${ATLAS_HOST:-localhost:7101}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-300}"

WAIT=0
args=()
for arg in "$@"; do
  if [ "$arg" = "--wait" ]; then
    WAIT=1
  else
    args+=("$arg")
  fi
done

case "${args[0]:-}" in
  dup-drop|literal)
    PATTERN="${args[0]}"
    args=("${args[@]:1}")
    ;;
  *)
    PATTERN="dup-drop"
    ;;
esac

STEP="${args[0]:-100}"
REPEATS="${args[1]:-2}"

if [ "$WAIT" -eq 1 ]; then
  echo "Waiting for Atlas at $ATLAS_HOST to report healthy (timeout ${WAIT_TIMEOUT}s)..."
  waited=0
  while true; do
    health_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://$ATLAS_HOST/healthcheck")
    echo "  [${waited}s] healthcheck status: ${health_code:-000}"
    if [ "$health_code" = "200" ]; then
      echo "Atlas is healthy after ${waited}s."
      break
    fi
    if [ "$waited" -ge "$WAIT_TIMEOUT" ]; then
      echo "Timed out after ${WAIT_TIMEOUT}s waiting for Atlas to become healthy (last status: '$health_code')." >&2
      exit 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
else
  health_code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 5 "http://$ATLAS_HOST/healthcheck")
  if [ "$health_code" != "200" ]; then
    echo "Atlas is not reachable at $ATLAS_HOST (healthcheck returned '$health_code')." >&2
    echo "Start it first, e.g.: project/sbt \"atlas-standalone/run conf/memory.conf\"" >&2
    echo "Or pass --wait to poll until it warms up (StartupDelayService can hold this for a few minutes)." >&2
    exit 1
  fi
fi

echo "=== /api/v1/expr/debug step-count growth stress test ==="
echo ""
if [ "$PATTERN" = "literal" ]; then
  echo "Using a stack-pinned n,x,:eq,:and chain (vocab=query). :eq/:and are"
  echo "both 'predictable' (fixed 2-in/1-out arity) and each block nets the"
  echo "stack back to a single query, so this never trips max-chunks-per-query"
  echo "or the unrelated stack-size guard. Expected to trip max-steps"
  echo "(StepLimitExceeded)."
else
  echo "Using a stack-pinned ,:dup,:drop chain (stack size stays at 1) so the"
  echo "stack-size guard never trips, while step/token count grows with N."
  echo "Since :dup/:drop are both unpredictable operators, this is expected"
  echo "to trip max-chunks-per-query (ChunkLimitExceeded)."
fi
echo "Pattern: $PATTERN, step increment: $STEP, repeats: $REPEATS"
echo ""
printf "%-8s  %-6s  %-10s  %-14s  %-10s\n" "depth" "run" "time(s)" "resp_bytes" "bytes/N^2"
printf "%-8s  %-6s  %-10s  %-14s  %-10s\n" "-----" "---" "-------" "----------" "---------"

n=0
while true; do
  n=$((n + STEP))

  if [ "$PATTERN" = "literal" ]; then
    q="n,x,:eq$(printf ',n,x,:eq,:and%.0s' $(seq "$n"))"
    url="http://$ATLAS_HOST/api/v1/expr/debug?q=$q&vocab=query"
  else
    q="1$(printf ',:dup,:drop%.0s' $(seq "$n"))"
    url="http://$ATLAS_HOST/api/v1/expr/debug?q=$q"
  fi

  for r in $(seq 1 "$REPEATS"); do
    result=$(curl -s -w "\n%{http_code} %{time_total} %{size_download}" \
      --max-time 120 \
      "$url")

    http_code=$(echo "$result" | tail -1 | awk '{print $1}')
    time_total=$(echo "$result" | tail -1 | awk '{print $2}')
    size_bytes=$(echo "$result" | tail -1 | awk '{print $3}')

    if [ "$http_code" != "200" ]; then
      body=$(echo "$result" | sed '$d')
      message=$(echo "$body" | grep -o '"message":"[^"]*"' | head -1)
      printf "%-8s  %-6s  %-10s  http %-9s  ---\n" "$n" "$r" "$time_total" "$http_code"
      echo ""
      echo "Failed at depth $n (http $http_code). Stopping."
      echo "Response $message"
      if [ "$PATTERN" = "literal" ]; then
        expected="StepLimitExceeded"
      else
        expected="ChunkLimitExceeded"
      fi
      case "$message" in
        *"$expected"*) echo "Matches expected limit for pattern '$PATTERN' ($expected)." ;;
        *) echo "WARNING: expected '$expected' for pattern '$PATTERN' but got a different message - failure may be unrelated to the limit under test." ;;
      esac
      exit 1
    else
      ratio=$(echo "scale=4; $size_bytes / ($n * $n)" | bc 2>/dev/null || echo "---")
      printf "%-8s  %-6s  %-10s  %-14s  %-10s\n" "$n" "$r" "$time_total" "$size_bytes" "$ratio"
    fi
  done
done