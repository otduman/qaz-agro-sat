# Snap field edges to cadastral plot lines (owner 2026-11-21: "can we just test it from the data we already have?")

Idea from docs/superpowers/notes/2026-11-15-cadastre-edges.md ("What it would allow", 1): our edges lying within about
30 m of a plot line moved onto it, so they become the exact, straight, surveyed border. Never built until now.

Data: Tayynsha 2026 only (the squares with plot lines read from the 62 map views: lines/<sq>.tif, seen/<sq>.tif in
../cadastre-edges-2026-11-15, same 10 m grid as fields-2026.tif). Fields = the drawn outlines the site shows
(outlines-2026.geojson), rasterised on that grid. Drawing test only: nothing on the site changes.

## Rule (written before any result is seen)
- Plot lines thinned to one cell (skimage skeletonize); only cells some view showed (seen).
- D = 3 cells (30 m). For each cell p within D of a plot line (not on it): q = its nearest plot-line cell; the cell
  s = q + (D + 1.5) x unit(p - q), just beyond the band on p's side of the line; p takes s's label (field id or 0 =
  not a drawn field). So an edge lying inside the band moves onto the line; edges farther than D, and lines running
  through a field (same field on both sides), change nothing. Line cells keep their label.
- Changed cells: only where the old or the new label is a field.

## Measures (all 34 squares)
- Edge on plot line: share of our edge length (cells on a border between two labels, field side) within 1 cell of a
  plot line, before / after; share of plot-line length next to field land within 1 cell of our edges, before / after.
- Land moved: hectares changed hands, share of field area; fields whose area changes by more than 5%.

## Blind check (written before the sample is drawn)
30 changed patches (4-connected changed cells, at least 0.5 ha), seed 20261121, at most 3 per square. Sheet per patch:
two clear 2026 photos (June-July and August, >= 90% clear over the window) with both borders drawn, the old and the
snapped one, as A and B in random order (key kept apart). Labelled by eye before the key is read: which border follows
the field edge on the photo better: A, B, or same/can't tell. Labels hashed (SHA-256) into this note before scoring.
Pass: the snapped border is judged better in at least 60% of the decided patches AND worse in at most 15% of all 30.

## Blind labels (2026-11-21, before key.csv was read)
labels.csv SHA-256 18fd6efb48b517dd5d199f2c58d34b7db58809a6ff268c40361759f1a5dce989: 24 same/cannot tell, 6 decided (S03 B, S08 A, S10 A, S11 A, S12 B, S16 A).
Caveat: the snapped border often shows small raster jags from the traced plot lines, so the eye can sometimes guess
which one is snapped; labels judge only which border follows the field edge on the photo.

## Result: FAIL, not applied
- Measures (34 squares, 3 082 drawn fields, 640 729 ha counted per square): our edge on a plot line 17% -> 38%; plot
  line on our edge 43% -> 80%; 6 539 ha moved (1.0%); 67 fields changed by more than 5%; 3 228 patches >= 0.5 ha.
- Blind check: 24 of 30 same / can't tell; the 6 decided all favour the OLD border (snapped better 0/6, worse 6/30 =
  20% > 15%). FAIL by the written rule.
- Why: the plot lines were traced from screenshots at about 25 m per screen pixel, so they carry one to two cells of
  error and raster jags; our edges already sit on the photo's field edge. Snapping moves edges onto the cadastre's
  line as traced, not onto the field: neater numbers, no better borders. Sharper tracing (zoomed-in views) would be
  needed before this is worth another test.
