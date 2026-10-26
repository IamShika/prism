#!/usr/bin/env bash
# Compiles the crossfade logic on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/crossfade-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/audio/CrossfadeLogic.kt \
        tools/crossfade-test/CrossfadeTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
