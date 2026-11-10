# Landsat in the series: implementation plan

10 November 2026. Approved by the owner ("proceed with A"). Branch `landsat-series`, from `landsat-photo-offset`.

**Goal:** each field's series also holds its Landsat 8 and 9 readings, put on the Sentinel-2 scale, so harvest dating
sees every clear photo from both satellites.

**Evidence:** `docs/superpowers/notes/2026-11-10-landsat-fit.md` (a fixed line per measure holds across seasons and
areas), `2026-11-10-landsat-photo-offset.md` (no per-photo correction), `2026-11-10-kokshetau-harvest-test.md`
(Landsat narrows dated windows from about 15 to 6 days).

## Design

Downloading stays apart from measuring, as with `season` and `series`:

```
season  --bbox ... --year Y --out S          (unchanged)
landsat --stack S --out L                    (new: Landsat scenes of the stack's area and season, on disk)
fields  --stack S --out F                    (unchanged)
series  --stack S --fields F [--landsat L [--landsat-fit FILE]] --out R
```

- **Landsat folder.** One sub-folder per scene (Landsat 8 and 9, Collection 2 level 2, Planetary Computer, anonymous
  token), holding the scene's 30 m window over the area: `qa_pixel` always, and `blue green red nir08 swir16 swir22`
  when at least 1% of the area is clear. `index.csv`: `scene,date,satellite,clear_share,status,scale,offset,nodata`;
  `landsat.properties`: the area (bbox, year, epsg) copied from the stack. Resumable like the season stack. Scenes in
  another UTM zone than the stack are left out and reported.
- **Reading a field.** As the Landsat experiment and the fit did: each 10 m interior pixel of the field (the same
  20 m shrink) takes the Landsat pixel its centre falls in; scenes of the same satellite and day are joined (the
  first clear value wins); clear is QA clear (no fill, dilated cloud, cirrus, cloud, shadow or snow bit, and clear or
  water) and no snow by the snow test; 60% of the interior clear, or the row has no values.
- **Adjustment.** `sentinel = slope x landsat + intercept` per pixel, for NDVI, B11, B12, NIR and blue (equal to
  adjusting the medians, since the lines rise). NDTI from the adjusted B11 and B12. Default: the Kokshetau 2025 fit,
  built in; `--landsat-fit FILE` reads another in `LandsatFit`'s format.
- **Series rows.** A new last column `satellite`: `S2`, `LC08` or `LC09`. Landsat rows leave `brightness` and the
  three texture columns blank (no green and red fit; 30 m is too coarse for combine tracks). Each field's rows by
  date, Sentinel-2 first on a shared date. Without `--landsat` the series is as before apart from the new column.
- **Summary.** Landsat days used, the fit, and the harvest-window (1 August to 30 September) longest gap of the typical
  field with and without Landsat.

## Tasks

1. **Planetary Computer token** (`pipeline/landsat/PlanetaryToken`): moved from the experiment, plus fetching
   `{token, msft:expiry}` from the token URL. Tests: signs with `?` or `&`; refetches 5 minutes before expiry;
   parses the token reply from a test server; a non-200 reply is an error naming the status.
2. **Landsat pixels** (`pipeline/landsat/LandsatQa`, `LandsatGrid`, `LandsatDay`): moved with their tests.
3. **Adjustment** (`pipeline/landsat/LandsatAdjustment`): default lines; reads a fit file; refuses a file missing a
   measure; applies per measure. Tests for each.
4. **Landsat folder** (`LandsatEntry`, `LandsatIndex`, `LandsatFolder`, `LandsatDownloader`): search, keep LC08/LC09,
   leave out other zones, window, QA clear share, store, index, resume. Tests against a fake Planetary Computer (STAC
   search, token, served TIFFs): stores a clear scene's seven layers on the scene's grid; a cloudy scene keeps only
   `qa_pixel`; Landsat 7 is left out; another zone is left out and reported; a second run downloads nothing; a folder
   for another area is refused.
5. **Series with Landsat** (`SeriesRow.satellite`, `SeriesBuilder`, `SeriesOutputs`). Tests with a hand-made Landsat
   folder on the hand stack: Landsat rows carry the adjusted medians; brightness and texture blank; 59% clear gives
   no values and 60% does; two scenes of one day join; Sentinel-2 rows unchanged; rows ordered; the CSV has the
   `satellite` column; summary lines.
6. **Commands** (`landsat`, `series --landsat --landsat-fit`): argument tests, as for the other commands.
7. **Real runs and checks.** Kokshetau 2023-2026 and Kostanay 2023-2026. Check: the series' Landsat rows equal the
   experiment's readings put through the same line (to 4 decimals, same fields and dates). Notes, results page.

## Review focus

- A scene whose window only partly covers the area (edge of a path): pixels outside get no value, not zero.
- Landsat `nodata` 0 in a band while `qa_pixel` says clear: that pixel is not clear.
- A field with no interior: Landsat rows like Sentinel-2 ones (blank, NaN clear share).
- The stack's grid and the Landsat folder's area disagree (another season or area): refused with a clear message.
- Planetary Computer token expiry during a long download.
