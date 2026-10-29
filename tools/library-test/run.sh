#!/usr/bin/env bash
# Compiles the search/sort/arrange logic on its own and runs the checks. Needs `kotlinc` and Java.
# Usage: tools/library-test/run.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT="$(mktemp -d)"
kotlinc app/src/main/java/com/prism/musicplayer/data/LibraryOrdering.kt \
        app/src/main/java/com/prism/musicplayer/data/AlbumFolder.kt \
        tools/library-test/LibraryTest.kt \
        -include-runtime -d "$OUT/test.jar" 2>&1 | grep -v "^warning:" || true
java -jar "$OUT/test.jar"
