#!/usr/bin/env bash
# Compiles the BPM detector on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/bpm-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/audio/BpmDetector.kt \
        tools/bpm-test/BpmTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
