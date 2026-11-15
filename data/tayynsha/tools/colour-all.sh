#!/bin/sh
# Colour series for every square-season that has a harvest table. Run from the repository root.
for y in 2026 2025; do
  for d in data/tayynsha/E*/; do
    if [ -f "$d/harvest-$y/harvest-$y.csv" ] && [ ! -f "$d/colour-$y.csv" ]; then
      echo "$d $y"; python data/tayynsha/tools/colour_series.py "$d" "$y" > /dev/null || echo "FAILED $d $y"
    fi
  done
done
echo done
