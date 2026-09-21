#!/usr/bin/env bash
# Runs demo.Main under the object-tracker agent and prints the event trace.
set -euo pipefail
cd "$(dirname "$0")/../.."
./gradlew -q objectTrackerJars
D=examples/objecttracker; B=build/objecttracker-demo
rm -rf "$B" && mkdir -p "$B"
javac -d "$B" $D/demo/Main.java
OUT=${OUT:-/tmp/otrack-demo}; mkdir -p "$OUT"; rm -f "$OUT"/*.jsonl
java -javaagent:build/libs/object-tracker-agent.jar="classes=+demo.Main\$Acct;fields=*;interval=1;events=$OUT/events.jsonl;out=$OUT/stats.jsonl" \
     -Xverify:all -cp "$B" demo.Main
cat "$OUT/events.jsonl"
