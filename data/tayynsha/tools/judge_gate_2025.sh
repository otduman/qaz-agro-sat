#!/usr/bin/env bash
# Held-out test of the crop gate (commit a844774, frozen before any 2025 result): for each square finished in 2025,
# rerun harvest with the gate into harvest-2025-gate and compare with the run's own harvest-2025 (no gate).
cd "/c/Programming Projects/qaz-agro-sat"
J=data/tayynsha/kazagrosat-cropgate.jar
for d in data/tayynsha/E*/; do
  sq=$(basename $d)
  [ -f $d/harvest-2025/summary-2025.txt ] || continue
  java -Xmx1g -jar $J harvest --series $d/series-2025 --fields $d/fields-2025 --out $d/harvest-2025-gate > /dev/null 2>&1 || echo "$sq gate run failed"
  before=$(grep 'Bare fallow' $d/harvest-2025/summary-2025.txt | sed 's/.*found: //')
  after=$(grep 'Bare fallow' $d/harvest-2025-gate/summary-2025.txt | sed 's/.*found: //')
  nocrop=$(grep -E '^  no crop seen' $d/harvest-2025-gate/summary-2025.txt | sed 's/^  //')
  fields=$(($(wc -l < $d/harvest-2025/harvest-2025.csv) - 1))
  echo "$sq | bare fallow cut: $before -> $after | $nocrop of $fields field regions"
done
