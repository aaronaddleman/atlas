#!/bin/bash
#
# Stress test for /api/v1/expr/debug step-count memory/response growth.
#
# The stack-doubling attack (:each/:fcall/:dup used to exponentially grow
# the stack) is already blocked on main by the maxStackSize guard added in
# nextStep() (see Interpreter.scala, PR #1892) - that returns a fast 400
# well before any real memory pressure.
#
# This test instead uses a pattern that keeps the stack pinned at size 1
# (push once, then repeat ,:dup,:drop) so it never trips the stack-size
# guard, while still driving the step/token count arbitrarily high. On
# main, /api/v1/expr/debug returns one JSON entry per step, so response
# size and memory grow linearly (or worse, once JSON encoding overhead is
# counted) with N. The chunk-based debug endpoint on this branch should
# instead return a bounded number of chunk entries regardless of N.
#
# Usage:
#   ./scripts/stresstest.sh [--wait] [step] [repeats]
#
# --wait   Poll the healthcheck until Atlas reports healthy instead of
#          failing immediately. Useful because atlas-lwcapi's
#          StartupDelayService holds healthcheck at 500 for a warm-up
#          period (startup-delay, 3m by default) after the process starts.
#
# Examples:
#   ./scripts/stresstest.sh          # step=100, repeats=2
#   ./scripts/stresstest.sh 50       # step=50, repeats=2
#   ./scripts/stresstest.sh 200 3    # step=200, repeats=3
#   ./scripts/stresstest.sh --wait   # wait for healthcheck, then step=100, repeats=2

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
echo "Using a stack-pinned ,:dup,:drop chain (stack size stays at 1) so the"
echo "stack-size guard never trips, while step/token count grows with N."
echo "Step increment: $STEP, repeats: $REPEATS"
echo ""
printf "%-8s  %-6s  %-10s  %-14s  %-10s\n" "depth" "run" "time(s)" "resp_bytes" "bytes/N^2"
printf "%-8s  %-6s  %-10s  %-14s  %-10s\n" "-----" "---" "-------" "----------" "---------"

n=0
while true; do
  n=$((n + STEP))

  url="http://$ATLAS_HOST/api/v1/expr/debug?q=1$(printf ',:dup,:drop%.0s' $(seq $n))"

  for r in $(seq 1 "$REPEATS"); do
    result=$(curl -s -w "\n%{http_code} %{time_total} %{size_download}" \
      --max-time 120 \
      "$url")

    http_code=$(echo "$result" | tail -1 | awk '{print $1}')
    time_total=$(echo "$result" | tail -1 | awk '{print $2}')
    size_bytes=$(echo "$result" | tail -1 | awk '{print $3}')

    if [ "$http_code" != "200" ]; then
      printf "%-8s  %-6s  %-10s  http %-9s  ---\n" "$n" "$r" "$time_total" "$http_code"
      echo ""
      echo "Failed at depth $n (http $http_code). Stopping."
      exit 1
    else
      ratio=$(echo "scale=4; $size_bytes / ($n * $n)" | bc 2>/dev/null || echo "---")
      printf "%-8s  %-6s  %-10s  %-14s  %-10s\n" "$n" "$r" "$time_total" "$size_bytes" "$ratio"
    fi
  done
done