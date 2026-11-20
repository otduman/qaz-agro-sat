#!/bin/sh
# Line splits (lines first) for every square still to do, two workers per district, then the district files.
# Logs: run-line-splits-<district>-<n>.log
cd "/c/Programming Projects/qaz-agro-sat/data"
for d in tayynsha sandyktau esil-akmola; do
  todo=""
  for sq in $(tail -n +2 $d/squares.csv | cut -d, -f1); do
    [ -f $d/$sq/lines-2026/count.npz ] && [ ! -f $d/line-splits-2026/$sq.geojson ] && todo="$todo $sq"
  done
  a=""; b=""; i=0
  for sq in $todo; do if [ $((i % 2)) -eq 0 ]; then a="$a $sq"; else b="$b $sq"; fi; i=$((i+1)); done
  [ -n "$a" ] && python tayynsha/tools/line_splits.py $d 2026 $a > run-line-splits-$d-1.log 2>&1 &
  [ -n "$b" ] && python tayynsha/tools/line_splits.py $d 2026 $b > run-line-splits-$d-2.log 2>&1 &
done
wait
for d in tayynsha sandyktau esil-akmola; do python tayynsha/tools/line_splits.py $d 2026 > run-line-splits-$d.log 2>&1; tail -n 1 run-line-splits-$d.log; done
echo "all done"
