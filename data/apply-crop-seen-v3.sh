#!/bin/sh
# Crop seen v3 (data/experiments/esil-nocrop-2024-11-20/NOTE.md, fresh check 20/22) on every district-season:
# per square harvest_v2 (re-dates 'no crop seen' fields v3 calls crops) -> crop groups v1+v2 (they read the status)
# -> harvest_v2 again (ripening from the new groups); then the district table. Copies: crops-Y*.csv -> *-c3.csv,
# district-harvest-Y.csv -> -c3. Log: data/apply-crop-seen-v3.log
cd "/c/Programming Projects/qaz-agro-sat"
T=data/tayynsha/tools
JAR=data/kazagrosat-fields-v2.jar
LOG="/c/Programming Projects/qaz-agro-sat/data/apply-crop-seen-v3.log"
for dy in tayynsha:2026 tayynsha:2025 tayynsha:2024 sandyktau:2026 sandyktau:2025 esil-akmola:2026 esil-akmola:2025 esil-akmola:2024; do
  d=${dy%:*}; y=${dy#*:}
  [ -f data/$d/district-harvest-$y-c3.csv ] || cp data/$d/district-harvest-$y.csv data/$d/district-harvest-$y-c3.csv
  for h in data/$d/E*/harvest-$y/harvest-$y-v1.csv; do
    q=$(dirname $(dirname $h)); [ -f $q/harvest-$y/harvest-$y.csv ] && [ -f $q/series-$y/series-$y.csv ] || continue
    for c in crops-$y crops-$y-v2; do [ -f $q/$c.csv ] && [ ! -f $q/$c-c3.csv ] && cp $q/$c.csv $q/$c-c3.csv; done
    python $T/harvest_v2.py $q $y >> "$LOG" 2>&1 && python $T/crop_groups.py $q $y >> "$LOG" 2>&1 \
      && python $T/crop_groups.py $q $y v2 $q/crops-$y-v2.csv >> "$LOG" 2>&1 && python $T/harvest_v2.py $q $y >> "$LOG" 2>&1 \
      || echo "$q $y FAILED" >> "$LOG"
  done
  java -Xmx2g -jar $JAR district join --district data/$d --year $y >> "$LOG" 2>&1 && echo "$d $y table $(date +%T)" >> "$LOG"
done
echo "all done $(date +%T)" >> "$LOG"
