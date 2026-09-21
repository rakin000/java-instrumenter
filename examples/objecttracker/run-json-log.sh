#!/usr/bin/env bash
# Runs demo.Main with classesJson= (classes.json) and logFile=; prints the agent log and the event trace.
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew -q objectTrackerJars
D=examples/objecttracker; B=build/objecttracker-demo
rm -rf "$B" && mkdir -p "$B"
javac -d "$B" $D/demo/Main.java
OUT=${OUT:-/tmp/otrack-demo-json}; mkdir -p "$OUT"; rm -f "$OUT"/*.jsonl "$OUT"/agent.log
java -javaagent:build/libs/object-tracker-agent.jar="classesJson=$D/classes.json;logFile=$OUT/agent.log;fields=*;interval=1;events=$OUT/events.jsonl;out=$OUT/stats.jsonl" \
     -cp "$B" demo.Main > /dev/null
echo "--- $OUT/agent.log"; cat "$OUT/agent.log"
echo "--- $OUT/events.jsonl"; cat "$OUT/events.jsonl"
