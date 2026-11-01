#!/usr/bin/env bash
# Compiles the waveform maths on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/wave-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/audio/Waveform.kt \
        tools/wave-test/WaveTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
