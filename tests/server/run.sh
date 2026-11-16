#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
TEST_CLASSES="$(mktemp -d "${TMPDIR:-/tmp}/airbridge-tests.XXXXXX")"
trap 'rm -rf "$TEST_CLASSES"' EXIT HUP INT TERM
javac -encoding UTF-8 --release 8 -Xlint:-options -d "$TEST_CLASSES" \
  app/src/main/java/dev/airbridge/server/*.java tests/server/LocalServerTest.java
java -cp "$TEST_CLASSES" LocalServerTest
