#!/bin/sh
# Tayynsha edge squares (owner 2026-11-19: "continue with the tayynsha edge squares"; outer rows left out: E580N5880,
# E580N6020, E640N5940, E640N5980). The 13 new squares get the same district steps the 34 old ones went through:
#   2026 and 2025: district join on v1 dates -> duplicates -> field joins v3 -> readability joins -> small-field joins
#                  -> table on v2 dates (as rejoin-v1dates.sh)
#   2026 only:     squares-first outlines (square_fields) -> splits along inner corners (field_splits) -> line counts
#                  -> small fields (evidence_joins, joins off) -> line splits (all squares redone) -> simple outlines
#                  -> idle fallow (two seasons) -> grass for the new squares (grass rule v3, as Sandyktau)
#                  -> grass history -> grass by use
# Everything replaced is copied first to data/tayynsha/before-edge-2026-11-19/ (and the experiment outputs the joins
# steps overwrite). Log: data/finish-tayynsha-edge.log
cd "/c/Programming Projects/qaz-agro-sat"
JAR=data/kazagrosat-fields-v2.jar
T=data/tayynsha/tools
D=data/tayynsha
B=$D/before-edge-2026-11-19
LOG="/c/Programming Projects/qaz-agro-sat/data/finish-tayynsha-edge.log"
NEW="E560N5900 E600N5900 E460N5920 E480N5920 E500N5920 E520N5920 E540N5920 E460N5940 E460N5960 E640N5960 E460N5980 E620N5980 E520N6000"
say() { echo "$* $(date +%T)" >> "$LOG"; }

# wait for the 2025 edge run (run-night writes "edge done")
until grep -q "edge done" data/run-night-2026-11-19.log; do sleep 120; done
say "start"

# 0. the bottom square stays off the map (its 2026 run found no fields)
[ -d $D/E580N5880/harvest-2026 ] && mv $D/E580N5880/harvest-2026 $D/E580N5880/left-out-harvest-2026

# 1. copies of everything the steps replace
if [ ! -d $B ]; then
  mkdir -p $B/exp
  for f in district-harvest-2026.csv district-harvest-2025.csv district-summary-2026.txt district-summary-2025.txt \
           duplicates-2026.csv duplicates-2025.csv joins-2026.geojson joins-2025.geojson joins-2026-j5.geojson \
           joins-2026-j6.geojson splits-2026.geojson splits-2026.csv square-fields-2026.csv small-fields-2026.csv \
           evidence-joins-2026.csv line-splits-2026.geojson line-splits-2026.csv outlines-2026.geojson \
           fallow-both-2026.csv grass-2026.csv grass-was-field-2026.csv grass-use-2026.csv; do
    [ -f $D/$f ] && cp $D/$f $B/
  done
  cp -r $D/line-splits-2026 $B/
  cp data/experiments/plot-joins-2026-11-15/joins-202[56]-v4.geojson data/experiments/plot-joins-2026-11-15/pairs-202[56].csv \
     data/experiments/small-fields-2026-11-15/joins-tayynsha-202[56]-v5.geojson \
     data/experiments/small-fields-2026-11-15/absorbed-tayynsha-202[56].csv \
     data/experiments/grass-use-2026-11-19/grass-use-detail-tayynsha.csv $B/exp/ 2>/dev/null
  # square_fields and evidence_joins start from their first-run copies (-j5, -j6): move them aside so they are taken
  # afresh from the new joins
  mv $D/joins-2026-j5.geojson $D/joins-2026-j6.geojson $B/exp/
  say "copies kept in $B"
fi

# 2. joins and tables, both seasons
for y in 2026 2025; do
  [ $y = 2026 ] && o=2025 || o=2026
  sqs=$(for h in $D/E*/harvest-$y/harvest-$y.csv; do q=$(dirname $(dirname $h)); [ -f $q/harvest-$y/harvest-$y-v1.csv ] && basename $q; done)
  say "$y: $(echo $sqs | wc -w) squares"
  for s in $sqs; do h=$D/$s/harvest-$y; cp $h/harvest-$y.csv $h/harvest-$y-v2.csv && cp $h/harvest-$y-v1.csv $h/harvest-$y.csv; done
  java -Xmx2g -jar $JAR district join --district $D --year $y >> "$LOG" 2>&1 && say "$y joined on v1 dates"
  python $T/square_duplicates.py $D $y >> "$LOG" 2>&1
  python $T/field_joins.py $D $y $o >> "$LOG" 2>&1
  YEAR=$y python data/experiments/plot-joins-2026-11-15/plotjoins.py >> "$LOG" 2>&1 \
    && cp data/experiments/plot-joins-2026-11-15/joins-$y-v4.geojson $D/joins-$y.geojson
  python data/experiments/small-fields-2026-11-15/absorb.py $D $y >> "$LOG" 2>&1 \
    && cp data/experiments/small-fields-2026-11-15/joins-tayynsha-$y-v5.geojson $D/joins-$y.geojson
  for s in $sqs; do h=$D/$s/harvest-$y; [ -f $h/harvest-$y-v2.csv ] && mv $h/harvest-$y-v2.csv $h/harvest-$y.csv; done
  java -Xmx2g -jar $JAR district join --district $D --year $y >> "$LOG" 2>&1 && say "$y table on v2 dates"
done

# 3. 2026 drawing steps
cd data
python tayynsha/tools/square_fields.py tayynsha 2026 >> "$LOG" 2>&1 && say "square_fields"
python tayynsha/tools/field_splits.py tayynsha 2026 >> "$LOG" 2>&1 && say "field_splits"
EXP=experiments/lines-first-2026-11-18
for sq in $NEW; do echo $sq; done | xargs -P 3 -I{} sh -c '[ -f tayynsha/{}/lines-2026/count.npz ] || python tayynsha/tools/lines_first.py count tayynsha {} 2026 '"$EXP"'/lc-{}.npy >> "$0" 2>&1' "$LOG"
say "line counts: $(ls tayynsha/E*/lines-2026/count.npz | wc -l)"
python tayynsha/tools/evidence_joins.py tayynsha 2026 >> "$LOG" 2>&1 && say "evidence_joins (small fields)"
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
