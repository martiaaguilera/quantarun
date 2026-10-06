#!/usr/bin/env bash
# Runs the concurrency test classes again and again against real PostgreSQL (Testcontainers), to find races that
# one CI run misses. A failing run keeps its surefire reports under target/race-runs/<n>/.
#
#   scripts/repeat-race-tests.sh [runs] [torture-seconds]
#
# Requires Docker. Prints one line per run and a summary; exits non-zero if any run failed.
set -euo pipefail

runs="${1:-20}"
torture_seconds="${2:-20}"
classes="JobConcurrencyTest,SchedulingCycleTest,LeaseRecoveryTest,FailureRacesTest,ReviveTest,CheckpointTest"
classes+=",FairShareSchedulingTest,ProjectLimitsTest,ChaosApiTest,JobEventStreamTest,ConcurrencyTortureTest"

root="$(cd "$(dirname "$0")/.." && pwd)"
out="$root/target/race-runs"
rm -rf "$out"
mkdir -p "$out"

"$root/mvnw" -q -f "$root/pom.xml" -pl apps/control-plane -am install -DskipTests -Dspotless.check.skip=true > "$out/build.log" \
    || { echo "Build failed; see $out/build.log"; exit 1; }

failed=0
for run in $(seq 1 "$runs"); do
    started=$(date +%s)
    if "$root/mvnw" -f "$root/pom.xml" -o -pl apps/control-plane test -Dspotless.check.skip=true \
        -Dsurefire.failIfNoSpecifiedTests=false -Dtest="$classes" -Dquantarun.torture.seconds="$torture_seconds" > "$out/run-$run.log" 2>&1; then
        result="pass"
    else
        result="FAIL"
        failed=$((failed + 1))
        mkdir -p "$out/$run"
        cp -r "$root/apps/control-plane/target/surefire-reports" "$out/$run/"
    fi
    tests=$(grep -E '^\[(INFO|ERROR|WARNING)\] Tests run: [0-9]+, Failures' "$out/run-$run.log" | tail -1 || true)
    echo "run $run/$runs: $result in $(($(date +%s) - started)) s. ${tests#*] }"
done

echo "$((runs - failed)) of $runs runs passed."
[ "$failed" -eq 0 ]
