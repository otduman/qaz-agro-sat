#!/bin/sh
# Grass by use (data/experiments/grass-use-2026-11-19): one worker per district. Log: run-grass-use.log
cd "/c/Programming Projects/qaz-agro-sat/data/experiments/grass-use-2026-11-19"
for d in tayynsha sandyktau esil-akmola; do
  python use_run.py "/c/Programming Projects/qaz-agro-sat/data/$d" > "../../run-grass-use-$d.log" 2>&1 &
done
wait
echo "all done"
