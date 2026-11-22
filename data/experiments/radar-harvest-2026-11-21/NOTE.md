# Radar harvest dates + rain on the pace chart (owner 2026-11-21: ideas 1 and 2, "2 should be together with 1")

Why: after rain the stubble darkens and the optical rule loses the cut; in cloudy weeks it only gives a long window.
Tayynsha 2026 grain fields: 2 583 dated, 2 036 "cut in a cloud gap" (window > 8 days), 1 186 "no cut found"
(Sandyktau 1 549 / 1 563 / 481, Esil 1 189 / 1 327 / 673). Sentinel-1 radar sees through cloud.

## What the radar archive holds (Earth Engine COPERNICUS/S1_GRD_FLOAT, checked 2026-11-21)
- 2024: no Sentinel-1 pass over any of the three districts (none from 15 Jul to 31 Oct). Radar cannot help 2024.
- 2025: Sentinel-1C only, 10-13 passes 15 Jul - 31 Oct (orbits 49D + 122D; Esil 20D + 122D).
- 2026: Sentinel-1C only, 18 passes 15 Jul - 31 Oct, two descending orbits, each every 12 days, together about every
  6 days. So a radar cut date is a window of about 6 days at best, never the 1-3 days of a clear optical pair.
  What radar can add: whether and roughly when a field the photos lost was cut, and narrower windows for long
  cloud gaps that a pass falls into.

## Rain (idea 2)
Daily precipitation per district from ERA5-Land (ECMWF/ERA5_LAND/DAILY_AGGR, total_precipitation_sum, mean over the
district outline), 15 Jul - 31 Oct of each shown season, drawn as bars under the pace line. The text-given rain spell
band stays where the content has one. ERA5-Land is a model reanalysis (about 9 km): district means, not field rain.

## Radar rule: development, then a blind test (written before any radar value is read)
- Per field: mean linear VV and VH over the field shrunk 20 m, every IW pass 1 Jul - 31 Oct 2026, both orbits;
  converted to dB after averaging.
- Development set: Tayynsha 2026 grain fields with an optical "dated" cut and window <= 5 days, 300 drawn with seed
  20261121. The rule's form and thresholds are chosen on these only and written below before the test is drawn.
- Test set: Sandyktau 2026 and Esil 2026 grain fields with an optical "dated" cut and window <= 5 days, 200 each,
  seed 20261122, never looked at while tuning.
- Agreement: the optical cut middle lies inside the radar window (last pass before the cut, first pass after it],
  widened by 3 days on each side.
- Pass: the rule makes a call on at least 85% of the test fields AND agrees on at least 75% of its calls.
- If it passes: radar windows are used only where the photos fail ("no cut found"; "cut in a cloud gap" windows are
  narrowed to their overlap with the radar window), and the result is compared with the official pace points before
  anything reaches the site. If it fails: nothing changes; rain bars still go on the chart.

## Development result (2026-11-21): no usable rule; blind test not run; radar not used
- Archive over Tayynsha 2026 is in fact 26 Sentinel-1C passes 1 Jul - 31 Oct on three descending orbits (49D 9-12,
  122D, 151D only at the edge): about every 5 days, but each orbit every 12 days.
- dev-series.csv: 300 Tayynsha fields, about 19 passes each. Around the optical cut the field VH drops by about
  0.6 dB on average (explore.py), but from one pass to the next a field swings by 2-7 dB (soil moisture after rain,
  different orbit geometry, VH near the radar's noise floor on bare stubble).
- Step rules tried on dev (dev_rules.py, dev_rules2.py: VV, VH, VH-VV; raw, pass-normalised, field-and-orbit
  anomalies; 1-3 passes each side; both signs; single orbits): agreement 10-24% with all orbits mixed, against about
  13-15% for a random split; the best single orbit (122D, VH) 39% against 28% at random. Nowhere near the 75% bar, so
  no rule was frozen and the test set was not drawn.
- The radar signal that does mark harvest in the literature is interferometric coherence (needs Sentinel-1 SLC
  scenes from ASF with a NASA Earthdata account and heavy processing); backscatter at this pass rate does not.
