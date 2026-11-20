# Hay and sown grass group (owner 2026-11-16)

Goal: show sown grass and hay fields (perennial grasses, сенокос) as their own group, instead of grain or "not
farmland". Feed oats/barley and silage stay in grain (they look like grain from space). Fields stay where they are;
only their group changes.

## What the simple signals do (seen labels, 2026-11-16)
`hay_features.py`: May greenness (median NDVI 15-31 May), straight edges, mowing cuts (NDVI drop >= 0.12 between
clear dates <= 12 days apart, 1 Jun - 31 Jul, with regrowth >= 0.06 within 30 days).
On the 1,072 regions already labelled by eye (Tayynsha and Esil): 8 of 10 hay are green in late May, but so are 162
of 222 pasture and 54 of 103 wetland; the mowing cut finds only 2 of 10 hay (mowing is spread over weeks and parts of a
field, so the whole-region curve rarely drops sharply). Hay is about 1% of a random sample.
May green + straight edges (>= 0.5) + 20 ha or more: Tayynsha 455 regions, 90,500 ha; Esil 524 regions, 86,000 ha.
Tayynsha's official fodder crops are about 40,000 ha (all kinds), so this set is at least half pasture.

## Plan (written before any new sample)
1. Tuning look: 60 Tayynsha + 40 Esil regions drawn at random from the May-green-straight set (seed 20261116),
   labelled by eye on the season photo sheets (same criteria file). Used to choose the rule; not a test.
2. Rule written here, then a fresh blind sample: 60 regions the rule calls hay and 60 drawn from the rest of the
   May-green-straight set, none seen before (seed 20261117), labelled blind, hash recorded before scoring.
3. Pass marks: (H1) of regions the rule calls hay, at least 70% are hay by eye; (H2) of eye-hay in the second
   half, at most half are missed; (H3) of eye-crop regions anywhere in the sample, at most 5% are called hay.
   Reported: Tayynsha's hay area beside the official ~40,000 ha of fodder crops.
4. Only if it passes: a "hay and grass" group on the site (Tayynsha and Esil first), owner's yes before publishing.

## Quick check: sudden yellowing pixel by pixel (owner 2026-11-16, before deciding hay vs one grass group)
Sign: the share of a region's pixels that go from green (NDVI >= 0.40) to pale (NDVI <= 0.28) between two clear dates
at most 10 days apart, 1 June - 10 August (a mown strip does this in days; drying steppe slowly; grain ripens later
and slower). Feature: the largest such share over the date pairs (both dates at least 60% clear over the region).
Checked on the Esil regions already labelled by eye (seen labels: hay vs pasture, wetland, crop). Decision rule fixed
now: worth a hay group only if a threshold exists that catches at least 3 of the 4 hay and at most 10% of pasture.

Result (Esil seen labels, threshold on the largest pale-turning share):

| share >= | hay | pasture | crop | wetland | fallow |
|---|---|---|---|---|---|
| 0.3 | 2/4 | 6/80 | 9/76 | 1/17 | 1/15 |
| 0.5 | 2/4 | 4/80 | 2/76 | 1/17 | 0/15 |
| 0.7 | 2/4 | 3/80 | 0/76 | 0/17 | 0/15 |

The two hay fields that were mown show it very clearly (0.83, 0.98 of their pixels turn pale within days); the two
others (low-confidence labels, "green from May, dense dark in August") show nothing, as if not mown this year, and
look like pasture. A few pasture regions show a strong cut too (likely mown meadows). By the rule fixed above (3 of 4
hay at most 10% pasture) it fails: the sign finds land mown this season, not hay fields as such. Hay that is not
mown in a year cannot be told from pasture.

## Owner's choice (2026-11-16): one grass group, "grass: hay and pasture"
Rule (written before its sample): a region of the field map that is not a field or kept fallow (land "other", or
fallow the site hides as idle: two seasons running, or green or damp in spring), with
- area at least 20 ha and its centre in its square's core;
- not water: median NDVI over its clear dates from May to September at least 0.15, and open water (MNDWI > 0) on under
  30% of it on its median date;
- green in spring: median NDVI over its clear dates 15 May - 15 June at least 0.25, or median NDMI up to 15 June at
  least -0.15.
Blind check: 40 regions the rule calls grass (Esil 20, Tayynsha 20) and 20 'other' regions it leaves out
(Esil 10, Tayynsha 10), seed 20261118, labelled by the criteria file, hash before scoring.
Pass: G1 at least 85% of rule-grass regions are pasture, hay or wet meadow by eye (not crop, fallow, water, village);
G2 at most 5% of rule-grass regions are crop by eye. Reported: what the left-out 'other' regions are.

Change before drawing the sample (2026-11-16): Tayynsha's field maps hold only field and fallow regions (land outside
fields is not outlined there), so the rule finds no grass regions in Tayynsha. The blind sample is drawn from Esil
only: 40 rule-grass and 20 left-out 'other' regions, same seed and pass marks. Tayynsha (and any district whose maps
have no 'other' regions) gets no grass layer for now.

Grass labels saved: `labels-grass.csv` (60), SHA-256 280907d66239cf3346096d89749f47ff64bf05cde355848fbe9fcd27cbb28f83.
Result: rule-grass 40 = 25 pasture, 8 wet meadow, 6 village, 1 crop. **G1 33/40 = 82%, needed 85%: FAIL**; G2 crop
1/40 = 2%: pass. Left-out 'other' 20 = 9 pasture, 7 crop (fields the map missed), 4 fallow. The failure is villages
(shelterbelts, roads, yards are green in spring too). Not retuned; a v2 that leaves villages out needs a fresh sample.

## v2 (written before its fresh sample)
v1 plus: leave a region out when it is patchy like a village or a shelterbelt: uniformity under 0.25 or July NDVI
spread (sd_july, evidence.py) over 0.12. On the seen labels this drops 6 of 6 villages, 22 of 125 pasture, 10 of 23
wet meadow (they stay unmarked, as now). Fresh blind sample: 40 v2-grass regions of Esil none seen before (seed
20261119); pass G1 >= 85% pasture/hay/wet meadow, G2 <= 5% crop.

v2 labels saved: `labels-grass2.csv` (40), SHA-256 5785a252952b9151dea33df6023a8d8d8c4831b8bc8dfa3a09699217095751ab.
Result: 32 pasture, 4 wet meadow, 1 hay, 2 crop, 1 fallow. **G1 37/40 = 92% (need 85%) PASS; G2 crop 2/40 = 5%
(max 5%) PASS, at the limit.** Esil 2026: 1,487 grass regions, 290,800 ha. Goes on the site as one group, "grass:
hay and pasture"; Tayynsha has no such regions in its maps.

## Sandyktau (2026-11-16, before its sample): the same v2 rule, its own blind check
30 v2-grass regions of Sandyktau, none seen before (seed 20261120), labelled blind; same pass marks G1 >= 85%,
G2 <= 5% crop (with 30 regions: at most 1 crop).

Sandyktau labels saved: `labels-grass-s.csv` (30), SHA-256 04e092efd37e107fd87bb8dcacabd64a8dbeec7c82062da4ed9d1b5cad318ce9.
Result: 14 pasture, 3 wet meadow, 13 groves/forest/village, 0 crop. **G1 17/30 = 57%: FAIL.** Sandyktau's north has
birch forest and groves (колки), which are green from May like grass and smooth, so v2 takes them. No grass layer for
Sandyktau. A v3 would need a forest signal (forest stays dark green into September, high NDVI all season, low SWIR);
fresh samples in both districts before it reaches the site.

## v3 (2026-11-16, written before its fresh samples): v2 without forest
Seen labels (Sandyktau 30, Esil 100): forest and groves keep a high NDVI into August (median NDVI 1-15 Aug, `aug`,
0.6-0.85), steppe and meadow are 0.2-0.45 by then. v3 = v2 plus: leave out a region whose `aug` is at least 0.55.
On the seen labels this drops 11 of 12 forest/groves; it also drops thick wet hollows (5 wetland), which then stay
unmarked as now. Fresh blind samples, none seen before (seed 20261121): Sandyktau 30, Esil 20 v3-grass regions.
Pass, in each district: G1 at least 85% pasture/hay/wet meadow; G2 at most one crop region.

v3 labels saved: `labels-grass3.csv` (50), SHA-256 14379c588b95f6830c243fbb59afcc8c887af29f1376a56d74e963db969ce2ec.
- **Sandyktau v3: 28/30 = 93% grass (20 pasture, 7 wet meadow, 1 hay), 1 crop, 1 wooded slope. G1 and G2 PASS.**
  1,158 regions, 141,100 ha.
- **Esil v3: 18/20 = 90% grass (G1 pass), 2 crop (G2 needs at most 1): FAIL.** v3 only removes regions from v2, so this
  is the crop share the Esil layer already had (v2 check: 2 of 40); over both fresh Esil samples 4 of 60 regions
  (7%) are crop fields the map missed. Owner to decide whether the Esil layer (v2, passed on its own sample) stays.
