#!/usr/bin/env bash
# Compiles the drag maths on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/drag-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/ui/DragMath.kt \
        tools/drag-test/DragTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
