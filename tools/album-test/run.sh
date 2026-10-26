#!/usr/bin/env bash
# Compiles the album naming rules on their own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/album-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/data/AlbumNaming.kt \
        tools/album-test/AlbumTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
