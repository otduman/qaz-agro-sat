# Lines first (owner 2026-11-18: "more fundamental than lazily fixing it ... cropping the fields at the same time too")

Why: today's fields come from grouping pixels that look alike, then a chain of repairs (joins, straightening, small
pieces, islands, duplicates). Borders appear where pixels differ (hollows, gullies, patchy crop), not where fields end,
so fields come out as puzzles. Here the borders come first: straight lines that show on many photos of two seasons.
Fields are the farmland those lines enclose; crop group per field from its own interior.

## Prototype v1 (written before running it)
Probe squares (one per district): Tayynsha E560N5960, Sandyktau E480N5780, Esil E340N5760. Season 2026, photos 2025+2026.
1. Evidence: for every clear date, |gradient| (Sobel, 10 m) of NDVI and of SWIR 1.6 (20 m, doubled), clouds blank;
   a pixel is an edge on that date when it is in the top 10% of that date's gradient; evidence = share of its clear
   dates it is an edge.
2. Segments: line segment detector (OpenCV LSD) on the evidence map; keep segments of at least 200 m.
3. A segment is a border when it shows (field_splits.shows: side medians 20-40 m apart differ, same sign, in at least
   70% of its 200 m chunks) on at least 2 clear dates.
4. Network: borders drawn 1 px wide, each end carried on up to 150 m until it meets another border or leaves farmland.
5. Farmland = pixels the current map calls field or fallow (which land is farmed does not change here).
   Fields = connected parts of farmland minus the network; parts under 5 ha join the neighbour they share most edge with.
6. Crop per field: median NDVI curve of its pixels at least 20 m inside its outline; the current crop-group rule on
   that curve (later step; v1 reports only how many fields get a clean curve).
Measures reported per probe square: fields before/after, share of drawn outline that is straight (as in
straight-borders), rectangle fill, farmland area unchanged.
Blind check (after the probe look, written before the sample): fields of the new map against the current map, A/B as in
square-fields (fresh seed, squares next to the probe squares, not the probe squares).

## Probe looking (2026-11-19, probe squares only) and what it changed
Evidence (evidence.py): share of clear dates a pixel is in the top 10% of that date's NDVI or SWIR gradient. Tayynsha
E560N5960, 77 dates: inside fields (>= 50 m from any border) median 0.03, 99th pct 0.60 (groves, ponds); on today's
borders median 0.56. Shows the field grid crisply; groves and hollows show as round blobs.
Line count (linecount.py, replaces the evidence map as LSD input): LSD on every clear photo (NDVI and SWIR 1.6),
segments >= 100 m drawn 3 px wide; count = dates a segment passed the pixel. Inside fields median 2, 90th pct 11;
on borders median 47. Weak crop borders (seen on 2-3 dates only) are not tried in v1: a border with no track or
belt and the same crop both sides is taken as one field (owner: fewer, whole fields).
Changes from the first draft, each after a look at a probe square:
- a kept line must be 300 m (not 200 m): 200-300 m pieces were mostly grove and pond edges;
- lines are first grown along their own direction while the count stays >= 15 (LSD breaks lines at junctions, which
  left 30-70 m gaps that let two fields leak into one); then the blind extension of up to 150 m runs to the next line
  or to the edge of the land (a bug stopped it short of the edge);
- strips narrower than 60 m between two lines (both sides of a road or belt) go to the parts on either side;
- the land divided is exactly the land the site shows as fields (joins as on the site), so counts and areas compare
  like with like; which land is a field, and where its outer edge runs, is not changed in v1.
Probe result before the shown-land change (fields only): Tayynsha 199 raw / 121 shown -> 206; Sandyktau 197 / 111 ->
214 (straight 0.716 -> 0.661, puzzle 59% -> 53%); Esil 206 / 84 -> 153 (straight 0.602 -> 0.684, puzzle 61% -> 56%).
By eye: inner wiggly borders go; L-shaped joins of two fields split along a seen line (Esil); strips get their own
borders; outer edges along hills, gullies and groves are unchanged (most of the puzzle look in Sandyktau).

## Fair comparison and the change of approach (2026-11-19, probe squares)
Same land as the site shows (fields only, joins as on the site), fields >= 5 ha with centre in the square's core:
rebuilding from lines alone gave more puzzle, not less (Tayynsha puzzle 10.7% -> 16.1%, straight 0.898 -> 0.868;
Sandyktau 58.6% -> 52.3% but straight 0.716 -> 0.655) and ~70 parts of 5-50 ha per square (the site hides < 50 ha).
Key finding: borders between today's shown fields are real: share of their length seen (count >= 10 within 1 px)
median 1.00, 10th pct 0.95-1.00 (Tayynsha, Sandyktau), 0.82 (Esil); the same test fires on 21-26% of spots inside
fields. So the puzzle look is (a) one shown field that is really two or three, and (b) ragged outer edges against
steppe, groves and gullies -- not fake cuts between fields.
Variant "edit" (edit.py): today's shown fields, (1) a border seen on under half its length is removed (fields join,
not across known holders), (2) a kept line crossing a field edge to edge splits it when both parts are >= 50 ha
(site minimum), at most 3 cuts per field, longest line first.
Probe result (fields, >= 50 ha, straight, fill, puzzle):
- Tayynsha  121/110 -> 144/133; 0.898 -> 0.903; 0.916 -> 0.921; 10.7% -> 9.7% (3 joins, 26 splits)
- Sandyktau 111/106 -> 129/125; 0.716 -> 0.732; 0.713 -> 0.734; 58.6% -> 53.5% (6 joins, 21 splits)
- Esil       84/74  ->  94/87;  0.602 -> 0.625; 0.726 -> 0.749; 60.7% -> 57.4% (2 joins, 13 splits)
Next: blind check of splits and joins on six neighbouring squares (with decoy lines to test the labeller), then
outer edges snapped to seen lines (tracks, roads, belts) as the separate step for (b).

## Edit v1 frozen and its blind check (written before any sample is drawn, 2026-11-19)
Rule = lines_first.edit (tests test_lines_first.py): join_unseen (SEEN_MIN 0.5, borders >= 20 px, never across two
known holders), then lines_of on the joined land and split_by_lines (both parts >= 50 ha, at most 3 cuts per field,
longest line first). Land = the fields as the site shows them (joins as on the site).
Check squares (never looked at while tuning): Tayynsha E560N5940, E540N5960; Sandyktau E500N5780, E460N5780;
Esil E320N5760, E340N5740. Only cuts, joins and decoys whose middle lies in the square's core (100 px in from each
edge) are drawn.
Sample (seed 20261119): 30 splits, up to 15 joins, 12 decoys, mixed and coded B01..; districts in proportion.
Decoy = a straight line through a field of today's map that the rule did not split: parallel to a side of its
smallest turned rectangle, at a random place in the middle 60% across, edge to edge, both parts >= 50 ha, and at least
30 m from every kept line along 80% of its length.
Sheet per item: the field (for a join: the two fields together) in yellow, the cut or the removed border in magenta;
six 2026 photos (May, June, early July, late July, August, September; clearest for the window) and two 2025 photos
(June, August). Labels saved and hashed before scoring:
- R "real border": along the magenta line the two sides differ on at least one photo (crop, colour, timing,
  tillage), or a track, road or belt runs along it;
- O "one field": the two sides look alike on every photo and nothing runs along the line;
- U unsure.
Pass: splits >= 80% R of decided and at most 10 of 30 U; decoys >= 70% O of decided (otherwise my labels are not to be
trusted and the check is void); joins >= 70% O of decided (if 6 or more are drawn; else reported only).
Reported: fields and shape measures before/after on the six squares. If the splits fail: not used, not retuned here.

## Blind check result (2026-11-19)
Run (run_edit.py, rule lines_first.edit) on the six check squares: joins 4/3/0/1/0/1, splits 21/23/20/23/13/14; in
the cores 101 splits, 8 joins, 36 decoys. Shape measures before -> after: straight up on all six (e.g. Sandyktau
E500N5780 0.695 -> 0.719), puzzle share down on five (Esil E340N5740 41.2% -> 43.2%).
Sample `b-sample.csv`, key `b-key.csv`, sheets `sheets/b-01..10.png` (bcheck.py), labels `b-labels.csv`
SHA-256 9030f18fc3d4148d0e65142b5455d11eb311db68fb3a5c6a699383d665da9ef8.
Limit of the blinding: a removed border follows today's outline and may wiggle, so joins could be guessed.
- Splits (30): 24 R, 0 O, 6 U -> 24/24 = 100% of decided (need 80%), U 6 (max 10). **PASS.** 4 of the 6 U are in
  Sandyktau E460N5780, the square with the fewest clear photos (51).
- Decoys (12): 8 O, 1 R, 3 U -> 8/9 = 89% O (need 70%): the labels can be trusted. The one R decoy lies on a real
  border the rule did not find (it misses some).
- Joins (8): 2 O, 6 R -> 25% O (need 70%). **FAIL.** A border that the line count does not see can still be real:
  the line count only sees straight stretches, and borders between fields of different crops that wiggle (hollows,
  gullies) are not straight. Joins are dropped.
Adopted: splits only (join step off). Next: line counts for every square, splits on the fields as the site shows them
(after part S), before/after per district, owner's OK before publishing.

## Owner 2026-11-19: simpler fields; the plan (written before building)
Owner: proceed with splits (each piece its own crop and dates); small fields under 50 ha are not dropped at once but
judged by their neighbours (harvest dates, crop, curves, cadastre owner, whether together they make a figure); groves
and hollows at the edges may be ignored for a simpler look (hectares stay the sown land); "do the runs yourself and be
thorough". Diagnosis on the probe squares (diag.py, puzzles.py): of 112 pairs with a puzzle field (fill < 0.75), 107
make a worse shape when joined; of 67 puzzle fields 22 have a look-alike neighbour and 8 make a more rectangular group.
Sheet of 16 random puzzle fields by cause: 4 two or three fields drawn as one (splits), 5 an arm or tail along a
neighbour, 3 ragged edges at groves, 1 a piece of another field set in, 1 two separate pieces counted as one, 1 a clean
trapezoid (not a puzzle), 1 messy.
Pieces' own facts (piece_facts.py, tests test_piece_facts.py): the jar's series and harvest on a label file of the
pieces, colour means, crop groups v1/v2 and harvest v2 on the square's fields and the pieces together. Check on
E500N5780: 38 pieces; pieces made of whole regions agree with their largest region: group 30/31, cut date within 3 d
20/23 (median 0 d).

## Evidence joins v1 and small fields (written before running beyond the probe squares)
Two touching fields as the site shows them (border >= 100 m) join when ALL hold:
- E1 the border is not a seen line: share of its pixels with line count >= 10 within 1 px under 0.5 (a seen line is a
  real border and is what a split cuts along);
- E2 same crop group, or one side unclear/no crop seen and that side is under 50 ha;
- E3 both cut within 5 days, or both without a cut date;
- E4 NDVI curves of their largest pieces agree: third-largest gap at most 0.12 over at least 5 common clear dates;
- E5 not two different known holders;
- E6 shape: together at least as rectangular as the better one less 0.02; when the smaller is under 50 ha, less 0.05.
Smallest pair first, one join at a time, groups re-measured after each join (chains allowed).
Small fields: a field still under 50 ha after the joins is shown when it is at least 20 ha and at least 60% of its whole
outline is a seen line (a real small field); otherwise it stays off the map as now.
Outputs: joins-YEAR.geojson (old kept as -j6), small-fields-YEAR.csv; then line splits run on the joined fields.
Blind check (fresh squares, not probe or check squares): joins as R/O (R = the two sides differ along the border on
some photo), pass >= 70% O of decided; shown small fields as "a field of its own" yes/no, pass >= 70% yes.

## Probe tuning of evidence joins and small fields (probe squares only, 2026-11-19)
Probe runs: Sandyktau E480N5780 2 joins, 6 small fields; Tayynsha E560N5960 1 join (271 + 310 ha, fill together
0.976, border seen 32%), 4 small fields; Esil E340N5760 0 / 0. Look at E480N5780 (sheets/ej-probe.png): 3 of 6 small
fields were ragged strips by groves or a river, 2 were cut by the square's edge. Added before any other square: a
small field must also fill its turned rectangle at least 0.75 and must not touch the square's edge; then 1 kept.
Fix: evidence joins always start from joins-YEAR-j6.geojson (a second run read its own output).
Evidence joins are rare (0-2 per square): most puzzle pairs have evidence against joining (diag.py).

## Simple outlines v4 (owner 2026-11-19: ignore bays at the edges, keep hectares the sown land; written before running)
Drawing only: hectares, crops, dates and holders do not change. For every field as finally drawn (after evidence
joins and line splits; fields split along inner corners are left as they are):
- candidate: its smallest turned rectangle when it fills it at least 0.85, else its convex hull (corners simplified
  20 m) when it fills that at least 0.85; else unchanged (L shapes and steps are split cases, not bays);
- pixels of other drawn fields are cut out again (neighbours keep their land);
- each patch of other land the outline adds (8-connected) is kept when it is at most 10 ha (a grove, hollow, pond,
  ragged edge); bigger patches (forest, lake, village) are cut back out;
- in all, the outline adds at most 15% of the field, else the field keeps its outline.
Check: an example page of about 20 fields before/after for the owner (a look judgement, not a photo fact), plus
hectares on the map unchanged (test) and the share of drawn area that is not sown, per district.
Blind check sheets for evidence joins (bcheck2.py, seed 20261120, squares other than probe and check squares): up to
25 joins and 10 decoys (touching fields not joined whose border is seen on at least 60%, both >= 50 ha), mixed, codes
J01..; up to 15 kept small fields, codes S01... Pass as written above (joins >= 70% O of decided; decoys must come out
mostly R or the labels are void; small fields >= 70% Y of decided).

## Evidence joins and small fields: blind result (2026-11-19)
Run: evidence joins Tayynsha 15, Sandyktau 5, Esil 6; small fields kept 90 / 24 / 15.
Sample: all 22 joins outside the probe and check squares, 10 decoys, mixed (`j-key.csv`, sheets `sheets/j-01..07.png`),
labels `j-labels.csv` SHA-256 1aebeae52bf24776f3b73ce0102931eb7d32fc023542317e4e16b31869116d79; 15 small fields
(`s-key.csv`, `sheets/s-01..03.png`), labels `s-labels.csv` SHA-256 f8de899f036a0727e59b199db0c01d03ae6d84c71d4624f5f5e64623e8bd2630.
- Decoys (10): 7 R, 1 O, 2 U -> the labels see real borders (7/8).
- Joins (22): 10 O, 7 R, 5 U -> 10/17 = 59% O (need 70%). **FAIL.** The 7 R: two fields that differ on one or two
  dates only (J13 June, J17 August), strips by a belt or track (J16, J27, J32), tillage pattern (J14). The curve test
  forgives two bad dates (third-largest gap) and so misses a one-date difference.
- Small fields (15): 14 Y, 1 U -> 100% of decided (need 70%). **PASS.**
Adopted: small fields kept; evidence joins off. Not retuned on this sample (the whole pool was used); a v2 (largest gap
over all clear dates, no join across a seen belt) would need fresh items, e.g. the 2025 season.

## Owner 2026-11-19: "more lenient towards joining; cadastre holder and the joined shape should decide"
joins_cadastre.py -> joins-cadastre.csv: every item of the join check against the cadastral plot lines (share of the
removed border within 30 m of a plot line, where the map was viewed) and the holders (holders.csv).
- My 10 right joins: no plot line on any (one area not viewed); holders same 5, unknown 5.
- My 7 wrong joins: no plot line on 6 (0-7%); only J16 has one (87%). Holders same 5, unknown 2, different 0.
- Undecided: J23 (84%) and J28 (75%) lie on a plot line; J07, J29 no; J18 not viewed.
- Decoys (real borders): Tayynsha 5 of 6 on a plot line (90-100%), 2 with different holders; Esil 0 of 3 and
  Sandyktau 0 of 1 (Esil plots are huge: 38 holders for 1,903 fields).
- Joined shape: wrong joins fill 0.77-0.98, right 0.65-1.00; two rectangles sharing a side always make a rectangle.
Reading: 6 of the 7 "wrong" joins are one cadastral plot of one holder, one crop, cut within 2 days. My labels were
"a line shows on at least one photo". Under the owner's definition (one plot, one holder, one crop and cut = one
field) this sample would score 16 of 17, but that is judged after seeing it; a v2 needs fresh cases.
Limit of the cadastre as a join sign (pilot 2026-11-15, cadastre-edges): only 38% of our field edges lie on plot
lines (one plot often holds several fields) and 68 of 230 fields have a plot line through them. No plot line along a
border is weak evidence of one field; a plot line is a fair sign of two. A v2 = v1 plus plot-line and belt/road vetoes
would make at most the 22 v1 candidates, minus the vetoed (about 17 in 3 districts, under 1% of ~6,200 fields).
Recommendation to the owner: hold joins until the split and outline results show how much puzzle is left.

## Rollout result (2026-11-19, joins off)
Line splits on all 89 squares with fields (data/run-line-splits.sh): Tayynsha 415 fields -> 934 pieces, Sandyktau
414 -> 976, Esil 285 -> 656; every piece has its own crop group, 76-85% its own harvest date. Simple outlines v4
(data/run-simple-outlines.sh): Tayynsha 3,072 outlines simplified (13,552 ha of bays drawn in), Sandyktau 1,877
(9,258 ha), Esil 1,638 (8,986 ha); drawing only. Site built locally, compare.py live -> new:
- Tayynsha  fields 2,526 -> 3,128; ha 657,271 -> 660,113 (small fields kept); straight 0.915 -> 0.938; fill 0.882 ->
  0.903; puzzle 16.2% -> 12.5%; oilseed 18.8% -> 19.1%; dated 82% -> 81%
- Sandyktau fields 1,928 -> 2,507; ha +712; straight 0.882 -> 0.910; fill 0.751 -> 0.789; puzzle 47.8% -> 38.6%;
  oilseed 14.3% -> 14.9%
- Esil      fields 1,775 -> 2,138; ha +467; straight 0.888 -> 0.906; fill 0.806 -> 0.833; puzzle 36.3% -> 31.1%;
  oilseed 8.7% -> 9.2%
Example sheets for the owner: examples/sp-<district>-NN.png (split_examples.py, seed 20261122) and
examples/ol-<district>-NN.png (outline_examples.py). Pin in test_district_data: 3,128. All site and tool tests pass.
