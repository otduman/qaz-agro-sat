#!/usr/bin/env bash
# addcard.sh SQUARE PLOT "HOLDER or -"   (holder "-" = the map hides it)
f="/c/Programming Projects/qaz-agro-sat/data/tayynsha/$1/plot-cards-2026.csv"
if [ "$3" = "-" ]; then echo "$2,,,,not shown on the map,holder hidden" >> "$f"
else n=$(printf '%s' "$3" | sed 's/"/""/g'); echo "$2,,,,\"ТОО \"\"$n\"\"\",company" >> "$f"; fi
