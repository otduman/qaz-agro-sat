# Field lines: split fields joined across faint borders

**Goal:** stop step 2 (field boundaries) from making one region of several fields whose borders are too faint
for the edge measure.

**Why:** in the step 3 box check, every box off by more than 0.05 lay in an oversized region (2023-F0017,
3,633 ha; 2024-F0056, 1,229 ha). A diagnosis on 8 November showed the joining step is not the cause: F0017 is
2 watershed regions and F0056 is 1. Inside F0017, 95% of pixels have a season edge under the calm level of 0.15.
On the season-edge picture the borders between its fields are plainly there, as thin straight lines of about 0.10
to 0.13 against 0.05 to 0.09 inside the fields. They are too faint to stop the calm area, so one marker spans
several fields. Regions over 500 ha hold 5,545 ha (2025), 7,484 ha (2024) and 11,838 of 25,990 ha (2023) of the
field area.

**Method (owner chose option A on 8 November: find faint borders with our own photos, tune on 2025 only):**
field borders here are long and straight, and noise and wet hollows are not. So the season edge is read along
short lines: for each pixel, in each of 12 directions, the median season edge along a segment of 2r+1 pixels
centred on it, minus the mean season edge in the (2r+1) x (2r+1) square around it. The pixel's line strength
is the largest of these, and 0 if all are negative. A median ignores a blob covering a few pixels of the segment,
so only a line running the whole segment scores. Pixels whose line strength is at least `lineEdge` are not
calm: their season edge is raised to at least `markerEdge`. Markers then stop at them and floods meet on them.
Joining is unchanged.

A prototype (scratch code, 8 November) on 2025 gave these, against today's 27 of 34 boxes and 5,545 ha in
regions over 500 ha:

| r | lineEdge | regions | over 500 ha | boxes (two-field crossed, one-field not crossed) |
|---|---|---|---|---|
| 15 | 0.03 | 804 | 3,130 ha | 4 of 4, 23 of 30 |
| 25 | 0.025 | 872* | 1,084 ha | 4 of 4, 23 of 30 |
| 35 | 0.025 | 762 | 1,082 ha | 4 of 4, 23 of 30 |
| 35 | 0.03 | 806* | 1,421 ha | 4 of 4, 24 of 30 |

(* with a brightness check added to joining as well, which made no difference at r = 35 and was dropped.)
r = 35 split the joined fields as well as r = 25, with fewer ragged pieces by eye. Starting values: `lineRadius`
35, `lineEdge` 0.025, confirmed with the product code on 2025 in task 3.

**Tuning discipline:** values are chosen on 2025 only. 2024 and 2023 then run unchanged and are reported as
they come out. (2023-F0017 was looked at to diagnose the problem, before any value was chosen.)

## Global constraints

- Java 21, Maven, test-first; one branch (`field-lines`), merged only after the owner's OK.
- Never modify or delete `data/season-*` or `experiment-output/`. Current outputs are kept as
  `data/fields-<year>-before-lines` and `data/series-<year>-before-lines` before rerunning.
- Real runs use `java -Xmx1g`.
- Other step 2 values stay frozen.

## Task 1: `LineStrength` (core)

`core/.../fields/LineStrength.java`: `static float[] of(float[] seasonEdges, int width, int height, int radius)`.
NaN pixels give NaN and are skipped as samples. A direction counts only with more than `radius` samples.
Tests in `LineStrengthTest`:
- `flatEdgesGiveNoLines`: a uniform image gives 0 everywhere.
- `aFaintStraightLineStandsOut`: a 1-pixel line 0.05 above a 0.05 background, horizontal, vertical and at
  45 degrees, gives at least 0.045 on the line and under 0.005 at 10 pixels from it.
- `aBlobIsNotALine`: a 7 x 7 square of 0.4 on 0.05 gives under 0.01 at its centre.
- `noValueStaysNoValue`: a NaN pixel stays NaN, and a NaN neighbour does not make its neighbours NaN.
- `refusesWrongSizes`: arrays not matching width x height are refused.

## Task 2: use it in the field finder

`FieldParameters` gains `lineRadius` (int, at least 1) and `lineEdge` after `minMarkerPixels`; DEFAULTS
35 and 0.025. `FieldFinder` raises line pixels to `markerEdge` after gathering the season edge; the raised
edge is what the watershed, the joining and the edge picture use. Test in `FieldFinderTest`:
- `aFaintRoadBetweenTwoFieldsSplitsThem`: two wheat fields, the right one 0.08 lower on 3 dates (too little
  for an edge, enough to stop joining), with a 1-pixel road 0.1 lower in NDVI on every date between them,
  come back as two regions. Fails today: the road's edge (0.1) is calm, so one marker spans both.
- Every existing `FieldFinderTest` and `WatershedTest` case still passes.

## Task 3: real runs and notes

Rerun step 2 on 2025 and confirm the starting values with the box check, the area in regions over 500 ha and
the outline pictures. Then 2024 and 2023 unchanged. Rerun `FieldCheck` (boxes, stability), step 3 (`series`)
and `SeriesCheck` (box gaps; the four over 0.05 were all in oversized regions). Notes in the tuning log, a
results-page section, memory.
