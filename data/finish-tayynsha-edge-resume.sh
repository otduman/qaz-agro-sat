#!/bin/sh
# Tayynsha edge squares (owner 2026-11-19: "continue with the tayynsha edge squares"; outer rows left out: E580N5880,
# Resume of finish-tayynsha-edge.sh from the line splits (owner paused it there 2026-11-19 ~18:00).
cd "/c/Programming Projects/qaz-agro-sat"
T=data/tayynsha/tools
D=data/tayynsha
B=$D/before-edge-2026-11-19
LOG="/c/Programming Projects/qaz-agro-sat/data/finish-tayynsha-edge.log"
NEW="E560N5900 E600N5900 E460N5920 E480N5920 E500N5920 E520N5920 E540N5920 E460N5940 E460N5960 E640N5960 E460N5980 E620N5980 E520N6000"
say() { echo "$* $(date +%T)" >> "$LOG"; }
cd data
say "resume"
all=$(cut -d, -f1 tayynsha/district-harvest-2026.csv | grep '^E' | sort -u)
a=""; b=""; c=""; i=0
for sq in $all; do case $((i % 3)) in 0) a="$a $sq";; 1) b="$b $sq";; 2) c="$c $sq";; esac; i=$((i+1)); done
python tayynsha/tools/line_splits.py tayynsha 2026 $a --redo > run-line-splits-tayynsha-1.log 2>&1 &
python tayynsha/tools/line_splits.py tayynsha 2026 $b --redo > run-line-splits-tayynsha-2.log 2>&1 &
python tayynsha/tools/line_splits.py tayynsha 2026 $c --redo > run-line-splits-tayynsha-3.log 2>&1 &
wait
python tayynsha/tools/line_splits.py tayynsha 2026 > run-line-splits-tayynsha.log 2>&1; say "line splits: $(tail -n 1 run-line-splits-tayynsha.log)"
python tayynsha/tools/simple_outlines.py tayynsha 2026 >> "$LOG" 2>&1 && say "simple outlines"

# 4. fallow and grass
python tayynsha/tools/fallow_both_years.py tayynsha 2026 2025 >> "$LOG" 2>&1 && say "fallow both years"
G=$(mktemp -d)
( cd $G && python "C:/Programming Projects/qaz-agro-sat/data/experiments/hay-2026-11-16/grass_rule.py" \
    "C:/Programming Projects/qaz-agro-sat/data/tayynsha" tayynsha ) >> "$LOG" 2>&1
if [ -f $G/grass-tayynsha.csv ]; then
  cp tayynsha/before-edge-2026-11-19/grass-2026.csv tayynsha/grass-2026.csv
  pat=$(echo $NEW | sed 's/ /,|^/g'); grep -E "^$pat," $G/grass-tayynsha.csv >> tayynsha/grass-2026.csv
  cp $G/grass-tayynsha.csv tayynsha/before-edge-2026-11-19/exp/grass-rule-all-squares.csv
  say "grass: $(grep -cE "^$pat," $G/grass-tayynsha.csv) regions in the new squares"
fi
python tayynsha/tools/grass_history.py tayynsha 2026 >> "$LOG" 2>&1 && say "grass history"
( cd experiments/grass-use-2026-11-19 && python use_run.py "C:/Programming Projects/qaz-agro-sat/data/tayynsha" ) >> "$LOG" 2>&1 && say "grass use"
say "all done"
