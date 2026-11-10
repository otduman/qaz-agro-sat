#!/usr/bin/env bash
# Runs the five district steps for one square outside the district runner (for squares the runner cannot take
# while another run holds the lock). Usage: one-square.sh SQUARE YEAR JAR
cd "/c/Programming Projects/qaz-agro-sat"
sq=$1; y=$2; jar=$3; d=data/tayynsha/$sq
bbox=$(grep "^$sq," data/tayynsha/squares.csv | awk -F, '{printf "%s,%s,%s,%s", $6, $7, $8, $9}')
mkdir -p $d
log=data/tayynsha/one-square-$sq-$y.log
echo "start $(date +%T) $bbox" > $log
java -Xmx1g -jar $jar season --bbox $bbox --year $y --out $d/season-$y > $d/season-$y.log 2>&1 && echo "season $(date +%T)" >> $log \
 && java -Xmx1g -jar $jar fields --stack $d/season-$y --out $d/fields-$y > $d/fields-$y.log 2>&1 && echo "fields $(date +%T)" >> $log \
 && java -Xmx1g -jar $jar landsat --stack $d/season-$y --out $d/landsat-$y > $d/landsat-$y.log 2>&1 && echo "landsat $(date +%T)" >> $log \
 && java -Xmx1g -jar $jar series --stack $d/season-$y --fields $d/fields-$y --landsat $d/landsat-$y --out $d/series-$y > $d/series-$y.log 2>&1 && echo "series $(date +%T)" >> $log \
 && java -Xmx1g -jar $jar harvest --series $d/series-$y --fields $d/fields-$y --out $d/harvest-$y > $d/harvest-$y.log 2>&1 && echo "done $(date +%T)" >> $log \
 || echo "failed $(date +%T)" >> $log
