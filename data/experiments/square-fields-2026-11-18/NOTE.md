# Squares first (owner 2026-11-18: "prioritize making squares first, then other straight figures")

Owner: too many fields, many puzzle-like pieces; pieces that together make a square should be one field, and a field
that is clearly several rectangles should be split. Squares first; other straight shapes (roads etc.) later.

Baseline (live map 2026, rectangle fill = area / smallest turned rectangle): Tayynsha 2,503 fields (445 under 0.75,
780 at 0.75-0.9, 1,278 at 0.9+); Esil 1,748 (681 / 536 / 531); Sandyktau 1,913 (950 / 657 / 306).

## Part J: joins toward rectangles, v1 (written before running it)
Units: the fields as drawn now in one square = groups of joins-YEAR.geojson plus single field regions (district table,
not a duplicate copy). Fill = rectangle fill of a unit's outer outline (10 m raster, land_lib.rectangle_fill).
Two touching units (shared border at least 200 m) join when
- J1 together they fill their rectangle at least 0.90;
- J2 the less rectangular of the two fills under 0.85 (a puzzle piece; two whole rectangles side by side never join);
- J3 together they are at least as rectangular as the more rectangular of the two;
- J4 nothing says they differ: not two different known holders; not grain against oilseed (both known); not cut more
  than 14 days apart (both dated; the date of the unit's largest piece).
Three units: a unit under 0.85 with two touching neighbours joins both when the three together pass J1, J3 (against all
three) and J4 (every pair), and no pair of them passes on its own.
Greedy: the candidate with the highest joint fill first; candidates are recomputed after every join; until none is left.
Output: joins-YEAR.geojson with the new groups (old file kept as joins-YEAR-j5.geojson).

## Part S: splits along inner corners, v1 -- written in a later section before it runs.

## Blind check for part J (written before the sample)
45 touching pairs drawn at random (seed 20261118): 30 from the joins part J makes (pairs; a three-unit join counts as
its largest piece and its smallest), 15 from touching pairs it does not join where J1 holds (the look-alikes), mixed and
coded Q01-Q45. Sheet per pair: six 2026 true-colour photos (clearest dates) and two 2025 photos (June, August), the two
pieces outlined in magenta and cyan, no other outlines. Labels saved and hashed before scoring:
- O "one field": same cover and timing on every photo, no line between them on any photo;
- T "two fields": a line or a difference (crop, colour, timing, tillage) between them on at least one photo;
- U unsure.
Pass: among the joins with a decided label at least 80% O, and at most 10 of 30 U. Reported: O among the look-alikes,
the owner's answer key (fields marked "part of a bigger field" now joined; fields marked "right" joined by mistake),
fields before and after per district.
If it fails: not used; not retuned on this sample.

## Part J v1 result (2026-11-18): too few to matter, replaced by v2 before any blind check
Tayynsha 2026: 2,775 drawn units -> 2,766 (8 joins). In a probe square (E560N5960) only 28 of 122 units fill under
0.85 and none has a neighbour that completes a rectangle (best joint fill 0.909, then 0.90, 0.89...). Renders of the
worst squares (look-*.png: Esil E340N5760, Sandyktau E480N5780) show what reads as "puzzle": outlines that wind
around groves, scrub and bays inside a field that is a rectangle on the ground, and small pieces in those bays; two
whole fields making one rectangle is rare. The v1 joins file was not kept (joins-2026.geojson restored from -j5).

## Part J v2: straight outlines (written before running it beyond the three probe squares)
Probe squares used for looking: Esil E340N5760, Sandyktau E480N5780, Tayynsha E560N5960 (left out of the blind sample).
For each drawn unit of at least 20 ha (unit = as in v1):
- rectangle first: if it fills its smallest turned rectangle R at least 0.85 and other drawn units take at most 3% of
  R, it is drawn as R;
- else straight sides: if it fills its convex hull H at least 0.85 (solidity), it is drawn as H, its corners simplified
  (Douglas-Peucker, 20 m);
- in both cases pixels of other drawn units are cut out again (neighbours keep their land), and the outline may add at
  most 15% of non-field land (else the unit keeps its outline).
- pieces: a drawn unit with at least 60% of its pixels inside another unit's new outline, smaller than it, and J4
  agreeing (holders, grain/oilseed, cut within 14 days), joins it.
Hectares stay the sown pixels (the outline may now include groves or bays the farmer works around).
Units under 0.85 solidity (L-shapes, steps) are left to part S (splits).
Output: joins-YEAR.geojson with joined groups and single-unit outline changes (old: joins-YEAR-j5.geojson); the site
uses a one-member feature's outline in place of the field's own.

Blind check for v2 (replaces the part J check above): 40 changed fields drawn at random (seed 20261118, outside the
probe squares, the three districts in proportion), each shown on the same 2026 summer photo and one 2025 photo with
the old and the new outline side by side in random order (A/B), coded W01-W40. Label: which outline matches the field
on the ground better (A, B, same, unsure). Labels saved and hashed before scoring. Pass: new better in at least 2/3 of
the pairs with a preference, and new worse in at most 20% of all 40. If it fails: not used; not retuned on this sample.

## Part J v2 run and blind result (2026-11-18): FAILED, not used
Run: Tayynsha 2,395 outlines changed (1,312 as rectangles), Sandyktau 1,378 (229), Esil 1,213 (396); pieces joined:
3 / 1 / 1; non-field land taken into outlines 26.9k / 18.4k / 19.6k ha. Lists kept here as square-fields-<district>-2026.csv.
Blind A/B: `ab-sample.csv`, `ab-key.csv`, sheets `sheets/w-01..08.png` (abcheck.py; both outlines drawn the same way),
labels `ab-labels.csv` SHA-256 13fdf9d61068e1eabf490eca3aa4c7d12e7eeb2210add051e2b8d81329ff1034.
Of 40: new better 8, old better 13, same 18, unsure 1 -> new better in 8/21 = 38% (need 2/3), new worse 13/40 = 33%
(max 20%). **FAIL.** joins-2026.geojson restored from -j5 in all three districts.
What the eye saw: the new outline wins where it drops outlined holes inside a field (ponds, groves: W22, W23, W36) and
squares off ragged ends of strips (W25, W37); it loses where the straight side or hull cuts across a grove, a wet hollow
or a forest bay at the edge of the field (W05, W06, W16, W19, W30, W35, W38, W39) or across a gully (W01).
Learned: the "puzzle" look is mostly ragged edges against groves, wet hollows and gullies; the field really stops there,
so a straight outline is tidier but less true. Inner holes are different: filling them reads better.

## Part S: splits along inner corners, v1 (owner 2026-11-18: "try the splitting"; written before running it)
Why it might work where field-merges (0/5) and the DA combination (0/6) failed: those searched every pixel for a hidden
border; here only a few straight lines are tried, the ones the field's own shape points to, and a line is tested on all
photos of two seasons at once.
Candidates: drawn units (as in part J) of at least 60 ha. Outline simplified (Douglas-Peucker 20 m); an inner corner is
a vertex turning inward by 60-120 degrees with both sides at least 200 m long. Candidate cuts: from each inner corner,
each of its two sides carried straight on into the field until it leaves the field.
A cut is kept when
- S1 shape: it leaves exactly two pieces of at least 20 ha (crumbs under 1 ha dropped), each filling its smallest
  turned rectangle at least 0.80, and their area-weighted fill is at least the field's fill + 0.10;
- S2 photos: on at least 2 clear dates (2025 and 2026 seasons together) the line shows: cut into 200 m pieces, on at
  least 70% of the pieces with clear pixels the median NDVI 20-40 m on one side differs from the other side by at least
  0.06 with the same sign (or the median SWIR 1.6 by at least 0.02, same sign).
The best kept cut (most dates) is made; the two pieces are tried again (at most 3 cuts per field).
Pieces keep the field's crop group, dates and holder; hectares split by pixels.
Output: splits-YEAR.geojson (parent square + keep member, piece outlines, dates that showed the line).

Blind check (written before the sample): 45 cuts drawn at random (seed 20261118, outside the probe squares): 30 kept,
15 rejected by S2 only (shape passes, photos do not), mixed, coded K01-K45. Sheet: six 2026 and two 2025 true-colour
photos, the field outline in yellow and the cut in magenta. Labels saved and hashed before scoring:
- R "real border": on at least one photo the two sides clearly differ (crop, colour, timing, tillage) along the line;
- O "one field": the two sides look alike on every photo;
- U unsure.
Pass: among kept cuts with a decided label at least 80% R and at most 10 of 30 U. Reported: R among the rejected ones
(what S2 misses), fields before and after per district, the owner's 3 "split" marks.
If it fails: not used; not retuned on this sample.

## Part J v3: straighten only over land that is ragged edge or the field's own cover (owner 2026-11-18: "lets test it";
## written before running it)
Learned from v2's wins (W25, W31, W37: ragged ends, a gully crossing one crop) and losses (groves, wet hollows,
forest bays cut across). v3 = v2's outline (rectangle first, else hull; neighbours keep their land; pieces join as
before), then every patch of non-field land the new outline adds (connected, 8-neighbour) is tested:
- V1 enclosed: the patch lies wholly inside the field (a hole: pond, grove) -> taken in;
- V2 shallow: no pixel of it is more than 30 m from the old outline -> taken in (a ragged edge);
- V3 same cover: its median NDVI over the 2026 clear dates (patch at least 60% clear) follows the field's: third-
  largest gap at most 0.10 over at least 5 common dates -> taken in (crop the map missed);
- else it is cut back out (a grove, wet hollow, forest bay, gully with other cover).
Then v2's limit (added land at most 15% of the outline) and "nothing added or joined: unchanged".
Also on the site: grass and fallow patches whose centre lies inside a drawn field are not drawn (islands belong to the
field around them, owner 2026-11-13); about 100 patches, Tayynsha 1.5k ha, Esil 2.8k, Sandyktau 5.6k.
Blind check: as for v2 (40 changed fields, A/B old vs new, seed 20261119 so a fresh draw; fields of the v2 sample and
the probe squares left out), coded V01-V40, labels hashed before scoring. Pass: new better in at least 2/3 of the pairs
with a preference and new worse in at most 20% of all 40. If it fails: not used; not retuned on this sample.

## Part S run and blind result (2026-11-18): PASSED
Run (field_splits.py, 2026): Tayynsha 62 cuts passed the shape test, 23 kept (photos); Sandyktau 40 / 16; Esil 50 / 27.
66 fields split in two. Logs splits-<district>.log; every tried cut in <district>/splits-2026.csv.
Blind sample `k-sample.csv`, key `k-key.csv`, sheets `sheets/k-01..09.png` (splitcheck.py), labels `k-labels.csv`
SHA-256 f54595a0f875e9c01423233c9783661cbce6fb26beddc6c0fdc8b28fda43ecdf.
- Kept (30): 23 R, 3 O, 4 U -> 23/26 = 88% (need 80%), U 4 (max 10). **PASS.**
- Rejected by photos (15): 4 R, 6 O, 5 U -> S2 lets go about 4 in 10 real borders (cost: those fields stay whole).
Small in number: the shape test asks for two near-rectangles; most puzzle comes from elsewhere (see part J).

## Part J v3 run and blind result (2026-11-18): PASSED (small effect)
Run: Tayynsha 2,327 outlines changed, 6.6k ha taken in, 3,232 deep patches cut back out; Sandyktau 1,310 / 2.3k /
2,296; Esil 1,163 / 4.9k / 1,784 (v2 took in 26.9k / 18.4k / 19.6k ha). Lists in <district>/square-fields-2026.csv.
Blind A/B: `v-sample.csv`, `v-key.csv`, sheets `sheets/v-01..08.png` (abcheck3.py), labels `v-labels.csv` SHA-256
319abf0d6a87c462d8b82cd8196003b7bba51386f3f2fae97d9b7963c0abfd5d.
Of 40: new better 4, old better 0, same 34, unsure 2 -> new better 4/4 of pairs with a preference (need 2/3), new
worse 0/40 (max 20%). **PASS.** Wins: straight edge where a step was cut in the same crop (V03, V19), a pond inside the
field not outlined (V27), no stray specks (V14). Most changes are too small to see at sheet scale.
