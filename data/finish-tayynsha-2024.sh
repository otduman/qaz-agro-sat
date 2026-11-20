#!/bin/sh
# Tayynsha 2024 as a site season (owner 2026-11-20: "go ahead with 1, 2, 3"): the same district steps 2025 had
# (rejoin-v1dates.sh): join on v1 dates -> duplicates -> field joins v3 (other season 2025) -> readability joins ->
# small-field joins -> table on v2 dates. Waits for finish-tayynsha-edge.sh. Log: data/finish-tayynsha-2024.log
cd "/c/Programming Projects/qaz-agro-sat"
JAR=data/kazagrosat-fields-v2.jar
T=data/tayynsha/tools
D=data/tayynsha
LOG="/c/Programming Projects/qaz-agro-sat/data/finish-tayynsha-2024.log"
say() { echo "$* $(date +%T)" >> "$LOG"; }
until grep -q "^all done" data/finish-tayynsha-edge.log; do sleep 60; done
y=2024; o=2025
mkdir -p $D/before-2024-2026-11-20
cp data/experiments/plot-joins-2026-11-15/joins-2024-v4.geojson data/experiments/plot-joins-2026-11-15/pairs-2024.csv \
   data/experiments/small-fields-2026-11-15/joins-tayynsha-2024-v5.geojson \
   data/experiments/small-fields-2026-11-15/absorbed-tayynsha-2024.csv $D/before-2024-2026-11-20/ 2>/dev/null
sqs=$(for h in $D/E*/harvest-$y/harvest-$y.csv; do q=$(dirname $(dirname $h)); [ -f $q/harvest-$y/harvest-$y-v1.csv ] && basename $q; done)
say "$y: $(echo $sqs | wc -w) squares"
for s in $sqs; do h=$D/$s/harvest-$y; cp $h/harvest-$y.csv $h/harvest-$y-v2.csv && cp $h/harvest-$y-v1.csv $h/harvest-$y.csv; done
java -Xmx2g -jar $JAR district join --district $D --year $y >> "$LOG" 2>&1 && say "joined on v1 dates"
python $T/square_duplicates.py $D $y >> "$LOG" 2>&1
python $T/field_joins.py $D $y $o >> "$LOG" 2>&1
YEAR=$y python data/experiments/plot-joins-2026-11-15/plotjoins.py >> "$LOG" 2>&1 \
  && cp data/experiments/plot-joins-2026-11-15/joins-$y-v4.geojson $D/joins-$y.geojson
python data/experiments/small-fields-2026-11-15/absorb.py $D $y >> "$LOG" 2>&1 \
  && cp data/experiments/small-fields-2026-11-15/joins-tayynsha-$y-v5.geojson $D/joins-$y.geojson
for s in $sqs; do h=$D/$s/harvest-$y; [ -f $h/harvest-$y-v2.csv ] && mv $h/harvest-$y-v2.csv $h/harvest-$y.csv; done
java -Xmx2g -jar $JAR district join --district $D --year $y >> "$LOG" 2>&1 && say "table on v2 dates"
say "all done"
