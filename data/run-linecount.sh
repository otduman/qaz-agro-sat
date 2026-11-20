#!/bin/sh
# Line counts (lines first, data/experiments/lines-first-2026-11-18) for every square with seasons 2025 and 2026.
# Writes <district>/<sq>/lines-2026/count.npz; reuses the experiment's counts; 4 squares at a time. Log: run-linecount.log
cd "/c/Programming Projects/qaz-agro-sat/data"
EXP=experiments/lines-first-2026-11-18
for d in tayynsha sandyktau esil-akmola; do
  for sq in $(tail -n +2 $d/squares.csv | cut -d, -f1); do
    [ -d $d/$sq/season-2025 ] && [ -d $d/$sq/season-2026 ] && echo "$d $sq"
  done
done | xargs -P 4 -L 1 sh -c 'python tayynsha/tools/lines_first.py count "$0" "$1" 2026 '"$EXP"'/lc-"$1".npy' 
echo "all done"
