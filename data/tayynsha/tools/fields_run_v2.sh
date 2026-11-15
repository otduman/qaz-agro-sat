#!/usr/bin/env bash
# Runs the fields command of a given jar on squares, one at a time, into the 2026-11-12 experiment folder
# (<district>/<square>/fields-2026-v2). Never writes to the squares' own folders.
# Usage: fields_run_v2.sh JAR SQUARE [SQUARE ...]
jar=$1; shift
D="/c/Programming Projects/qaz-agro-sat/data"
X="$D/experiments/fields-2026-11-12"
log="$X/run-v2.log"
for sq in "$@"; do
  case $sq in E440N5800|E460N5800|E460N5780) dist=sandyktau ;; *) dist=tayynsha ;; esac
  out="$X/$dist/$sq/fields-2026-v2"
  mkdir -p "$out"
  echo "$sq start $(date +%T)" >> "$log"
  java -Xmx3g -jar "$jar" fields --stack "$D/$dist/$sq/season-2026" --out "$out" > "$out.log" 2>&1
  echo "$sq exit $? $(date +%T)" >> "$log"
done
