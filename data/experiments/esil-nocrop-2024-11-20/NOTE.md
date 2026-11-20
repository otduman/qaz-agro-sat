# Esil 2024 "no crop seen" (2026-11-20; owner OK'd "go ahead with 1, 2, 3")

Why: Esil 2024 (live since 2026-11-19) has 212 fields / 141,456 ha "no crop seen" against 77 / 29,346 ha in 2025 and
72 / 14,246 ha in 2026; "unclear" 141 against 4 and 1. Either 2024 really had much idle land (spring 2024 floods), or
the 2024 run missed crops.

## Written before any field is looked at
Sample: 30 fields drawn at random (seed 20261120) from Esil 2024 district-harvest rows with status "no crop seen" and
area >= 50 ha, plus 10 decoys drawn the same way from status "dated" (>= 50 ha), mixed and coded N01..N40. Each a row
of clear photos 1 May - 30 Sep 2024 (true colour, field outlined). Labels by eye, without the key: crop (sown and grown
this season, any crop), no crop (steppe, fallow, flooded, bare all season), unsure. Labels hashed before the key is read.
- Pass (the label is right): at least 80% of the decided "no crop seen" fields labelled no crop.
- Decoys check the labelling: at least 8 of 10 decoys labelled crop, else the check is void.
If it fails: Esil 2024 is fixed before Tayynsha 2024 goes up (cause found first), and the live Esil 2024 gets a fix.
Reported beside it: clear photos per field May-Sep 2024 vs 2025 (is 2024 thinner?).

## Result (2026-11-20 ~05:15)
Labels saved before the key was read: n-labels.csv, SHA-256 beb635be271d5630cbb178656801b3a1d82e58f8f07b8b40b14c955b42c7e02c.
- Decoys: 8 of 10 labelled crop -> labelling valid.
- "No crop seen": 13 no crop, 9 crop, 8 unsure -> **13/22 = 59% (need 80%) FAIL.** By area the 9 missed crops are
  5,379 ha against 3,638 ha of true no-crop: the big fields are the missed ones (809-1,292 ha).
Cause (series of E300N5760 F0424, F0309): the crop peak sits on partly clear July dates (NDVI 0.67 on 18 Jul at clear
0.63; 0.58 on 23 Jul at clear 0.62). The jar's no-crop gate (HarvestDater.CROP_NDVI 0.5) only reads dates with clear
share >= 0.85 (CutRule.MIN_CLEAR); July 2024 in Esil had almost none, sowing was late (fields bare to mid June), and
the August readings (0.45-0.48) fall under 0.5. harvest_v2.py keeps 'no crop seen' as it is. The site shows these
fields as fallow (strict fields).

## Fix rule "crop seen v2" (written before any flip is counted or looked at)
A field the jar called "no crop seen" is a crop field when, from 1 May to 31 August, at least one reading of any
satellite with clear share >= 0.40 and snow share <= 0.02 has field NDVI median >= 0.55 (higher than 0.5 because a
partly clear date is noisier; clouds and haze lower NDVI, so a high value on the clear part is trustworthy). Such
fields are then dated by harvest rule v2 like any other field. Everything else unchanged.
Blind check on fresh fields (not the 40 above): 30 fields the rule flips, drawn at random (seed 20261121) over all
district-seasons, plus 10 decoys that stay "no crop seen" (>= 50 ha both), coded, same sheets, labels hashed.
- Pass: at least 80% of the decided flipped fields are crop by eye; decoys at least 7 of 10 no crop (else void).
If it passes: applied to every district-season (harvest_v2 + district tables), before/after counts to the owner.
If it fails: not applied; Esil 2024 gets a note on the site instead.

## Finding before the fresh check (development sample only, 2026-11-20 ~05:40)
The rule run on the 40 labelled fields (diagnostic, not the test): flipped 23 of the 30 "no crop" fields, of which my
eye said crop 7, no crop 9, unsure 7. But the series show the eye was blind the same way as the jar: sheets.py keeps
only photos with the field >= 90% clear, so the partly clear July dates never reached the sheets. N01 (eye: no crop)
has NDVI 0.75 in July, N03 0.79, N19 0.62 -- the eye saw a field bare to mid-June and nothing after. So the first
check's 59% is itself biased toward "no crop"; the jar's error is likely bigger.
Change to the check method (not to the rule): the fresh sheets show every date with the field >= 40% clear, cloud
pixels painted white, so July is visible. One wetland (N07, water in spring, reeds in July) shows the rule can flip
reeds; counted as a miss if it happens in the fresh sample.

## Fresh check of crop seen v2 (2026-11-20 ~06:00)
Sheets sheets2/ (every date >= 40% clear, clouds white), key m-key.csv, labels saved before the key was read:
m-labels.csv, SHA-256 a43dde441fc3624e7563a31088fb68dc8ef90b054d88d3bffd4d8cce4622ebf2.
- Flipped: 17 crop, 9 no crop, 4 unsure -> **17/26 = 65% (need 80%) FAIL.**
- Kept (should be no crop): 2 no crop, 3 crop, 5 unsure -> void by its own bar (7 needed); the old "no crop seen"
  also holds crops the rule does not reach.
Wrong flips: green in June then ploughed dark from late July (worked fallow with weeds: M10, M33, M34), grass green
already in early May (M02, M21, M26), wetland and reeds (M07, M15), one bare river-bend field (M06).
**Not applied**, as written. harvest_v2.py keeps crop_seen() with tests but rewrite() does not call it in main()
(raw not passed) -- see below.
What it shows: "no crop seen" is unreliable wherever July is cloudy (Esil 2024 most: the rule flips 147 of 212 fields,
114k of 141k ha; Esil 2025 14 fields 9.3k ha; Esil 2026 10 fields 3.4k ha; Tayynsha 2025 9 fields; others few).
Next rule (v3, needs its own fresh check): crop = bare in spring (May-10 June NDVI under ~0.3 on clear dates) AND
green (>= 0.55, partly clear allowed) between 20 June and 20 August AND not ploughed dark before 10 August; water in
spring rules it out. Dev data for it: the 80 labelled fields here (both samples); test on a third fresh sample.

## Rule v3 (frozen 2026-11-20 ~06:45 in v3.py, before the fresh sample is drawn)
Owner: "go ahead with v3 plus a note after". Built on the 80 development fields (n- and m- samples; the n- labels
are biased toward no crop, see above). A "no crop seen" field is a crop field when all hold:
1. bare in spring: on dates 1 May - 10 June with the field >= 60% clear, field NDVI never reaches 0.30 and never goes
   below 0 (water);
2. even in spring: median over 1 May - 10 June dates >= 80% clear of the NDVI spread (p75 - p25) under 0.04 (a sown
   seedbed is even; steppe, grass, wetland and patchy ground are not);
3. green in summer: some date 20 June - 20 August with the field >= 40% clear has NDVI >= 0.55;
4. not ploughed bare in July: no date after that peak up to 31 July (>= 60% clear) with NDVI under 0.25.
Development scores (not the test): m-sample 14 crop / 1 no crop among decided calls; n-sample 13 / 3; together 27/31.
Without rule 2 it was 29/39 (74%): the spread rule is what separates sown land from grass and wetland.
Fresh test: 30 v3 flips + 10 v3 kept from all district-seasons' "no crop seen" fields >= 50 ha, not the 80 dev
fields, seed 20261122, sheets as sheets2 (dates >= 40% clear, clouds white), labels hashed before scoring.
Pass: >= 80% of decided flips are crop; kept ones are reported (not a bar: the old status also holds missed crops).
If it passes: v3 goes into harvest_v2.py (rewrite re-dates those fields), every district-season re-run, before/after
to the owner. If it fails: not applied; the site gets the note on Esil 2024 either way.

## Fresh check of v3 (2026-11-20 ~07:10)
Sheets sheets3/ (as sheets2), key k-key.csv, labels saved before the key was read: k-labels.csv, SHA-256
59f201eeb0308d6d8ae14475ac03257832e2e141e934d6067517a1243154ab3f.
- Flips: 20 crop, 2 no crop, 8 unsure -> **20/22 = 91% (need 80%) PASS.**
- Kept: 7 crop, 3 no crop -> v3 is careful: most crops it does not flip stay hidden in "no crop seen".
So v3 fixes about half the missed crop area (Esil 2024: 88 of 212 fields, 69k of 141k ha) and the rest stays
unreliable; the site note on Esil 2024 stays needed. Applied per the plan above.
