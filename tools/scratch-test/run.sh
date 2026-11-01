#!/usr/bin/env bash
# Compiles the scratch engine's sound maths on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/scratch-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/audio/ScratchRenderer.kt \
        app/src/main/java/com/prism/musicplayer/audio/PcmFormat.kt \
        tools/scratch-test/ScratchTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
