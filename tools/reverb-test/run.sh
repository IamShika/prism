#!/usr/bin/env bash
# Compiles the reverb engine on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/reverb-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/audio/ReverbParams.kt \
        app/src/main/java/com/prism/musicplayer/audio/ReverbEngine.kt \
        app/src/main/java/com/prism/musicplayer/audio/ReverbPresets.kt \
        tools/reverb-test/ReverbTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
