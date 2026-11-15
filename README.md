# QazAgroSat — Field Atlas

Field outlines, crop groups and harvest dates for grain districts of northern Kazakhstan, from free satellite
imagery. The results are published as a bilingual (Russian / English) map site:
**[qazagrosat.vercel.app](https://qazagrosat.vercel.app)**.

The site currently covers three districts: Tayynsha (North Kazakhstan region), Sandyktau and Esil (Akmola region), for
the 2024–2026 seasons.

## What it does

1. **Downloads a season of imagery.** Sentinel-2 L2A (10 m) from the public Earth Search STAC catalogue, one
   21 × 21 km square at a time, plus Landsat for extra clear dates.
2. **Finds fields.** Groups pixels whose greenness curves through the season move together, then cleans the result:
   land labels (field, fallow, other), a water guard, straight borders, and joins of pieces that belong to one field.
3. **Dates the harvest.** After a field ripens, its cut is the first sharp change in its colour between two clear
   photos (short-wave infrared, brightness, residue, stubble lines; brighter or darker alike). Changes the whole area
   makes on one day (haze, light) are removed first. The cut date is the middle of that window; a cut hidden by a
   long cloudy spell gets the whole spell.
4. **Sorts crops into groups.** Short-season (spring wheat, barley), long-season (flax, sunflower), yellow-flowering
   (rapeseed), by when each field turns green, flowers and ripens.
5. **Builds the site.** One page per district and season: the field map over a summer photo, crop groups, harvest
   pace through the season and the land users that are companies or cooperatives.

Peasant farms and private land users are never named on the site, only shown by kind.

## Repository layout

| Folder | What is in it |
|---|---|
| `core/` | Java: season stacks, field finding, harvest dating |
| `pipeline/` | Java: STAC search, downloads, Landsat |
| `cli/` | Java command line (`season`, `fields`, `landsat`, `series`, `harvest`, `district plan/run/join`) |
| `experiment/` | Java probes used while tuning |
| `data/tayynsha/tools/` | Python tools for the steps after the Java pipeline (land labels, joins, crop groups, holders) |
| `site/` | The Field Atlas site: `build/` (Python build and tests), `src/` (page, map, charts, translations) |
| `docs/districts/` | District outlines (GeoJSON) |

Downloaded imagery, intermediate maps, cadastral readings and district texts live in `data/` and are not part of the
repository.

## Requirements

- Java 21 and Maven (the wrapper `mvnw` is included)
- Python 3.12 with `numpy`, `scipy`, `tifffile`, `opencv-python`, `Pillow`, `scikit-image`
- About 2 GB of disk per square and season

## Running it

Build the Java tools:

```sh
./mvnw -q package
```

Plan and run a district (squares, downloads, fields, series, harvest), then join the squares:

```sh
java -jar cli/target/kazagrosat.jar district plan --boundary docs/districts/tayynsha.geojson --out data/tayynsha
java -jar cli/target/kazagrosat.jar district run  --district data/tayynsha --year 2026
java -jar cli/target/kazagrosat.jar district join --district data/tayynsha --year 2026
```

Then the Python steps per square and district (crop groups, duplicates across overlapping squares, field joins):

```sh
python data/tayynsha/tools/crop_groups.py data/tayynsha/E560N5960 2026 v2 data/tayynsha/E560N5960/crops-2026-v2.csv
python data/tayynsha/tools/square_duplicates.py data/tayynsha 2026
python data/tayynsha/tools/field_joins.py data/tayynsha 2026
```

Build and test the site:

```sh
python site/build/build.py
python site/build/test_build.py
python site/build/test_district_data.py
python site/build/test_page.py      # needs Playwright and a Chromium-based browser
```

The built site is written to `site/dist/`.

## How accurate is it

Checked against a hand-marked sample of 101 fields from the three districts (2026):

- every sampled area drawn as a field was farmland;
- before the latest joins, 51 of 87 decided fields (about two thirds of their area) had the right outline; most
  errors were small pieces of a larger field, which the small-field joins now merge;
- harvest dates rely on clear photos; in Tayynsha 2026 about 84% of the grain area gets a date, and the share cut by
  7 September (68%) sits a little under the official 72%. A single field's date can be a week or more off.

Every change to the field rules is checked the same way: the rule is written down first, then compared blind against
the photos at random points, and kept only if it wins at least two times in three.

## Data sources

- Sentinel-2 L2A: Copernicus / ESA, via [Earth Search](https://earth-search.aws.element84.com/v1)
- Landsat Collection 2: USGS
- District outlines: OpenStreetMap contributors
- Field outline model used in Tayynsha: Delineate Anything (AGPL-3.0), run as a separate step

## Licence

No licence has been chosen yet; all rights reserved until one is added.
