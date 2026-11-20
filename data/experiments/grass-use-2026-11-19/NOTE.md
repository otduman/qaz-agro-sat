# Grass by use: mown land as hay (owner 2026-11-19)

Owner: the grass layer looks like pieces of steppe and leftover land, much less like hay and pastures.

Measured on the live layer (site data of 2026-11-18):
- Tayynsha 1,072 pieces, 378k ha; Sandyktau 1,002, 129k ha; Esil 1,291, 260k ha. Rectangle fill about 0.6 (fields
  0.88). Joining pieces that touch (within 40 m) leaves 233 / 352 / 222 areas: the steppe is one continuous area cut
  into pieces by the field-finding step (Tayynsha's largest such area: 178k ha in 208 pieces).
- Pieces are not thin strips (only 1-3 pieces under 150 m across). 184 / 232 / 193 pieces have at least 70% of their
  outline along fields (leftover land between fields).
- The rule only says "green in spring, not water, not village or forest". Eye labels called most of it pasture, but
  from space that means natural grass; grazing itself is not seen.

## Probe 1 (a look, not a test): mown pixels
Earlier check (hay-2026-11-16): on mown hay fields 83% and 98% of pixels turned from green to pale within days; only
3 of 80 pasture regions did. Here the same sign per pixel, to see whether mown land comes out as hayfield shapes.
- A pixel is mown on a clear date (SCL 4/5) when its NDVI is at most 0.28 and it was at least 0.40 on a clear date
  at most 10 days before; dates 1 June - 31 July (grain turns pale later, in August).
- Patches: mown pixels closed by 1 px, at least 5 ha. Same for 2025; a patch mown in both years is a strong sign.
- Squares: Esil E300N5720 and E260N5700 (eye-labelled hay seen before), Tayynsha E560N5960.
Settings are not tuned on any result; this only shows whether the idea is worth a rule and a blind check.

Probe 1 result (E300N5720, E260N5700): the per-pixel sign is noisy. In the dry south, steppe drying crosses 0.40 ->
0.28 in speckles; fallow worked in June (outside the grass layer) comes out as whole blocks. Mown land in the grass
layer: 899 of 7,079 ha and 1,111 of 6,321 ha, mostly speckle.

## Probe 2 (a look, settings chosen after probe 1, not a test)
Only inside the grass layer. A pixel is mown when between two clear dates at most 10 days apart (15 June - 10 August)
NDVI falls by at least 0.18 from at least 0.45, and stays at least 0.12 after (cut grass and straw, not bare tilled
soil). Patches (closed by 1 px): at least 5 ha, mowing days within 6 days of each other (half the pixels), and the
patch fills its turned rectangle at least 0.6 (mowers leave straight edges).

Probe 2 result: almost nothing passes. Mown patches inside the grass layer: E300N5720 0, E260N5700 0 (2025: 1, 5 ha),
Sandyktau E440N5800 2 (12 ha), Tayynsha E560N5960 1 (6 ha), E540N5940 1 (6 ha); grass on these squares 3,500-11,000 ha
each. Clean mowing in natural grass is rare here; hay is mostly sown grass on arable land (fields, group "other").
The loose per-pixel sign on E540N5940 (raw-E540N5940.png) marks blobs, not strips: the land browns around a livestock
farm, with tracks fanning out from it to a lake. That is grazing, and it is visible: farm buildings, a track network
and trampled land near water. A pasture sign could build on that (grass near a farm or village with tracks).

## Owner 2026-11-19: agreed
After the line-splits rollout: (1) a pasture test: find livestock farms and villages on our own photos, grass within
reach of them with tracks and trampled ground = pasture, the rest "steppe, no use seen"; rule written here first,
blind check before the site; (2) simpler grass drawing: touching pieces joined into whole areas, cut at roads and
rivers, crumbs dropped (hectares unchanged). Hay stays in one group with pasture.

## Stage 1: livestock places from our own photos (probe, written before running)
Goal: farms (livestock stations, зимовки) and villages, the places grazing starts from. From the 2026 season stack:
- built mask: pixels never green (90th percentile NDVI over clear dates 15 May - 15 Sep under 0.20), not water
  (MNDWI on the median date under 0), outside land the field map calls field or fallow;
- places: connected parts of the built mask (closed by 1 px) of 0.3 ha or more; a place under 50 ha is a farm
  candidate, 50 ha or more a village candidate. Saline flats and quarries are bare too: the probe pictures show how
  many of the candidates are buildings and yards.
Probe squares: Tayynsha E540N5940 (farm seen in probe 1), E560N5960; Esil E300N5720; Sandyktau E440N5800.
Pictures: places outlined on a July photo, for a look only; settings may change after it, then a blind check.

Stage 1 result (tuning look on E540N5940, E560N5960, E300N5720, E440N5800): "never green" blobs find livestock yards
and farm complexes, plus quarries, sand bars and salt patches; villages are missed (gardens are green). Track density
(lines seen on >= 8 dates) does not separate use (median 0.43 near places, 0.39 far). A grazing gradient shows in
Tayynsha (late-July NDVI of grass 0.37 within 500 m of a place, 0.50 beyond 5 km; E560N5960 0.36 -> 0.43), weakly in
Esil, not in Sandyktau.

## Rule v1: grass by use (written before its blind check; settings from the tuning look above)
Places (places2.py):
- yards: pixels never green (NDVI >= 0.20 on under 10% of clear dates 15 May - 15 Sep), not water, not field or fallow
  of 30 ha or more; their share in a 310 m window >= 0.06; closed 3 px; parts >= 2 ha;
- villages: on the clearest date 20 Jun - 15 Jul, bright bare pixels (red > 0.10, NDVI < 0.25, not field/large fallow)
  >= 1% of a 310 m window, NDVI spread (std in 70 m, mean over 310 m) >= 0.08, under half of the window farmland;
  opened 3 px; parts >= 5 ha, >= 100 m from edge to middle, at most 4:1 long;
- both: parts mostly (>= 50%) within 100 m of open water dropped (sand bars, lake shores); parts with a median red
  spread (std in 70 m) under 0.012 dropped (uniform salt or eroded patches); village parts count only in groups
  (within 300 m) of 25 ha or more.
Classes per grass piece of the live layer (>= 20 ha), classes.py:
- pasture: at least half of the piece within 2 km of a place;
- old field: otherwise, when it fills its turned rectangle >= 0.80 and is >= 50 ha;
- steppe: the rest.
Tuning squares: pasture 32-60% of grass area, old field 0-14%, steppe 29-68%.

## Blind check of rule v1 (written before drawing the sample)
Fresh squares: 3 per district drawn at random (seed 20261124) from squares with grass pieces, not the tuning or
probe squares. Sample: up to 15 pasture, 10 old field, 15 steppe pieces, mixed and coded G01...; each a row of four
photos (early June, late July, mid September 2026; August 2025), window 2.5 times the piece, the piece outlined, no
places or classes shown. Labels (criteria):
- P grazing seen: tracks converging or many paths, trampled or worn patches, a livestock yard or corral at the edge
  with paths into the piece, cover grazed down unevenly compared with land beside it;
- H old field: straight field sides, even cover like a sown or former field, mowing strips;
- S natural, no use seen: natural uneven cover (gully slopes, wet hollows, riverside meadow, grove fringe), no
  tracks or wear beyond a single road;
- O not grass (missed crop field, fallow, village, forest, water); U cannot tell.
Labels saved and hashed before the key is opened. Pass: pasture class P >= 70% of decided (P/H/S/O); old field class
H >= 70% of decided; steppe class P <= 30% of decided; O <= 10% of all decided. Limit: the window shows the
surroundings, so a village beside a piece can be seen; P needs signs in the piece itself.

Labels saved: g-labels.csv (40), SHA-256 f407e66919680cdbc27fb680c1562442c416933b090563692cbae5c9e67576cf.
Result (fresh squares E600N5980, E500N5960, E580N5980, E420N5760, E420N5780, E440N5760, E340N5720, E320N5740,
E320N5760): pasture class 15 = 8 P, 4 S, 3 U -> **P 8/12 = 67%, needed 70%: FAIL**; old field class 10 = 7 H, 2 O
(worked strips), 1 P -> **H 7/10 = 70%: PASS**; steppe class 16 = 9 S, 2 H, 1 P, 3 U(+1) -> **P 1/12 = 8%: PASS**;
O 2/34 decided = 6%: pass. The P labels are almost all pieces touching a village or farm (G06, G12, G14, G18, G23,
G24, G32, G35); the four pasture-class S are gully strips and stream meadows 1-2 km from a place (G09, G16, G17,
G28). Not retuned on this sample.

## Rule v2 (written before its fresh sample): pasture within 1 km
v1 with pasture = at least half of the piece within 1 km of a place (was 2 km); old field and steppe as v1.
Fresh sample: 3 more squares per district (seed 20261125), none used before; up to 15 pasture, 10 old field, 10
steppe pieces; same sheets, criteria and pass marks. Codes K01.., labels k-labels.csv.
Labels saved: k-labels.csv (35), SHA-256 af75607a0f834463d8df8974133ad65577980e5cc88947c0f8b831aa79848bb3.
Result v2 (fresh squares E480N5940, E540N5960, E560N5980, E520N5820, E460N5820, E420N5820, E340N5700, E320N5700,
E280N5740): pasture class 15 = 7 P, 4 S, 1 H, 3 U -> **P 7/12 = 58%: FAIL**; old field class 10 = 8 H, 2 O (worked
strips) -> **H 8/10 = 80%: PASS**; steppe class 10 = 8 S, 2 P -> **P 2/10 = 20%: PASS**; O 2/32 = 6%: pass.
Both samples together: pasture 15/24 = 63% grazing seen. The misses are small riverside or lake-shore meadows next to
villages: likely grazed, but no sign shows at 10 m. A pasture class cannot be confirmed by eye from these photos.

## Adopted (2026-11-19): two kinds of grass
- old fields under grass: v2 old field (not within 1 km of a place, fills its turned rectangle >= 0.80, >= 50 ha),
  passed twice (7/10, 8/10);
- steppe, meadows and pastures: everything else in the grass layer (pasture and steppe classes together).
Written to <district>/grass-use-2026.csv (square, region, cls = oldfield | steppe) by use_run.py for every square.
Drawing (site): one see-through group per kind, no outline per piece, so touching pieces read as one area.
Full run (data/run-grass-use.sh): old fields under grass Tayynsha 81 pieces / 19,125 ha, Sandyktau 21 / 2,893 ha,
Esil 49 / 12,709 ha; the rest steppe, meadows and pastures. Site built, all site tests pass; published with the
owner's standing OK ("publish it if the results are good to your standards", 2026-11-19).
