#!/bin/sh
# Lines first rollout for one district (data/experiments/lines-first-2026-11-18/NOTE.md): evidence joins and small
# fields, then splits along seen lines with each piece's own crop and dates. Log: run-fields-lines-<district>.log
cd "/c/Programming Projects/qaz-agro-sat/data"
D=$1
python tayynsha/tools/evidence_joins.py $D 2026 && python tayynsha/tools/line_splits.py $D 2026 && echo "$D all done"
