# KazAgroSat — Project Plan

Exported 2026-11-06 from the living doc, which also holds the two diagrams: https://claude.ai/artifact/JpJw7ANa5caTaBhDAx4amq

## Vision and client

KazAgroSat (working name) is a Java platform that turns free satellite imagery of Kazakhstan's grain belt into sales and service intelligence for an agro-holding that sells farm machinery.

The client doesn't buy "vegetation indices". It buys answers to four questions:

- **Who needs machines?** Farms that harvest too slowly for their area are under-equipped.
- **Where is the market growing?** Cultivated area and crop mix per district, year over year.
- **When will demand hit?** In-season harvest timing per district, so service teams and spare parts are in place early.
- **Can this customer pay?** Independent check of a leasing applicant's real cultivated area and crop history.

Initial focus area: the northern grain belt (Kostanay, Akmola and North Kazakhstan oblasts). Fields there are large and flat, so 10 m Sentinel‑2 imagery is enough.

Principle: build a technically strong product first and monetize later. The first goal is a convincing demo for one district, not a full platform.

## Product features

Harvest-speed lead generation is the flagship feature. It turns images into a list of customers to call, and the rest of the features build on its data.

| # | Feature | What the dealer gets | Depends on | Priority |
| --- | --- | --- | --- | --- |
| 1 | Harvest-speed leads | Map and ranked list of farms whose harvest took much longer than neighbours' | Field boundaries, harvest dating, farm linking | Must (MVP) |
| 2 | Market sizing | Cultivated hectares and crop type per district, year over year | Field boundaries, crop classification | Must |
| 3 | Harvest progress forecast | In-season "% harvested" per district and expected start dates | Harvest dating on recent images | Should |
| 4 | Leasing / credit check | 3–5 year history of a farm's cultivated area and crop vigour vs district average | Farm linking, NDVI time series | Should |
| 5 | Productivity zones | Per-field zone maps from multi-year NDVI, exported for variable-rate seeders and sprayers | NDVI time series, export formats | Could (premium) |
| 6 | Sales dashboard | Web map and lists for sales reps, filtered by region | Features 1–4 | Should |

The satellite shows fields, not machines. Detecting individual combines would need costly sub-metre imagery and isn't needed.

## Data sources

Sentinel‑2 and the DEM are free with no account; Sentinel‑1 on Earth Search is not. The hard part is linking fields to farms, not getting pixels.

| Source | What it gives | Access | Use |
| --- | --- | --- | --- |
| Sentinel‑2 L2A (optical, 10 m, ~5-day revisit) | Surface reflectance bands + SCL cloud/snow mask | [Earth Search STAC](https://github.com/Element84/earth-search) on AWS, collection `sentinel-2-c1-l2a`, Cloud-Optimized GeoTIFFs, no account. Backup: [Copernicus Data Space STAC](https://stac.dataspace.copernicus.eu/v1/collections/sentinel-2-l2a) | NDVI and other indices, harvest dating, field boundaries |
| Sentinel‑1 GRD (radar, sees through clouds) | Backscatter, sensitive to crop structure and harvest | Earth Search collection `sentinel-1-grd`, but requester-pays: needs an AWS account and costs money to read. Check Copernicus Data Space as the free route before phase 2 | Candidate cut signal for harvest dating (tested in phase 2), then fills cloud gaps (phase 3+) |
| Copernicus DEM | Elevation and slope | Earth Search `cop-dem-glo-30` | Productivity zones, excluding non-farmland |
| Public Cadastral Map of Kazakhstan | Registered land plots and boundaries | [map.gov4c.kz/egkn](https://map.gov4c.kz/egkn/), per [eGov](https://egov.kz/cms/en/services/land_relations/pass625-11_msh) | Linking fields to plots; owner data access and terms must be checked |
| Dealer's CRM | Customers, their farms, machines sold | From the client | Linking fields to customers, validating leads |

Start with Earth Search: it supports reading just the needed window of each image over HTTP, so an MVP needs no big downloads.

## Architecture and Java stack

One Java 21 codebase, split into Maven modules, runs a batch pipeline into PostGIS and serves results through a REST API.

_Diagram: system architecture, 4 stages. See the living doc._

Each column is a package or module. Harvest dating is the core algorithm the sales features depend on.

| Layer | Choice | Why |
| --- | --- | --- |
| Language and build | Java 21, Maven multi-module: `core`, `pipeline`, `cli`, `api` | Modern Java, clean separation |
| HTTP and JSON | `java.net.http` + Jackson | Built in, no heavy SDK needed for STAC |
| Raster reading | Own pure-Java COG window reader at first; GeoTools later if needed | Reads only the needed pixels over HTTP; full control |
| Geometry | JTS Topology Suite | Field polygons, areas, intersections |
| Projections | GeoTools referencing or Proj4J | WGS84 ↔ UTM zones 39–45 N |
| Database | PostgreSQL + PostGIS | Spatial queries on fields and districts |
| API | Spring Boot REST | Standard, well supported |
| Web map | Leaflet or MapLibre GL | Lightweight map for the sales dashboard |
| Tests | JUnit 5 with small synthetic rasters | Every algorithm step testable offline |

## Core algorithms

Harvest dating per field is the key algorithm. Everything before it prepares clean data, and everything after it aggregates.

1. **Scene search.** Query STAC by bounding box, date range and max cloud cover. Group results by date and UTM tile. Kazakhstan spans UTM zones 39–45 N, so convert lat/lon to UTM before reading pixels.
2. **Windowed reading.** Read only the pixel window covering the area of interest from each COG via HTTP range requests. Bands: red (B04), NIR (B08), green (B03), SWIR (B11, B12), SCL.
3. **Scaling.** Convert digital numbers to reflectance using the scale/offset in the STAC metadata. Don't hard-code it: the offset changed with newer processing baselines.
4. **Cloud and snow masking.** Keep SCL classes 4 (vegetation), 5 (bare soil) and 6 (water). Drop clouds, shadows, cirrus and snow (11). Resample SCL from 20 m to 10 m.
5. **Indices.** NDVI = (NIR − Red) / (NIR + Red) for vigour, NDMI from NIR and SWIR for moisture stress, NDWI from green and NIR for water, NDTI from B11 and B12 for crop residue.
6. **Field boundaries.** First version: segment a mid-season NDVI image plus an after-harvest image into contiguous parcels (threshold, edge detection, connected components, drop parcels under ~5 ha). Later: refine with cadastre polygons where available.
7. **Per-field time series.** For each field and each clear date, store median NDVI and valid-pixel share. Skip dates with under ~60% clear pixels.
8. **Harvest dating.** NDVI alone can't date the cut, because cereals lose NDVI as they ripen, often weeks before harvest. Use the NDVI drop (for example below 0.3 after a peak above 0.6) only to mark ripening. A field then counts as harvested at the first clear date after that where a cut signal changes. Candidate signals are NDTI, a residue index from B11 and B12, and Sentinel‑1 backscatter. Phase 2 tests which works, against ground truth. The harvest date is that date, with the gap since the last clear image as its uncertainty.
9. **Harvest speed per farm.** Group fields by farm. Speed = days between the first and last field harvested, relative to total hectares. Compare against the district median to flag outliers.
10. **Validation.** Check against ground truth: dealer knowledge, farmer interviews, or fields visible in high-resolution basemaps. Report accuracy honestly in the demo.

Thresholds above are starting points to tune on real data, not fixed values.

## Roadmap

Five phases. Each one ends with a check before moving on, and phases 1–3 produce a demo before any platform work.

_Diagram: roadmap, 5 phases and 4 gates. See the living doc._

Text version for handing to Claude Code:

1. **Single-date prototype.** STAC search, COG window reading, cloud mask, NDVI for a test area. CLI writes PNG + GeoTIFF. *Gate:* map matches high-resolution basemaps.
2. **Season time series and harvest dating.** All clear dates May–October for one district, automatic field boundaries, per-field NDVI curves, test of cut signals (NDTI, Sentinel‑1), harvest-date map. *Gate:* validated against ground truth.
3. **Lead list demo.** Fields linked to farms, harvest speed per farm, Sentinel‑1 for cloud gaps, ranked lead list with map. *Gate:* a dealer confirms the leads are useful.
4. **Region-scale platform.** PostGIS, scheduled ingestion, REST API, dashboard, market sizing, harvest progress. *Gate:* pilot agreed with one dealer.
5. **Premium features.** Leasing/credit reports, productivity zone maps, pricing model.

## Risks and open questions

The biggest risk is commercial, not technical: knowing that a field was harvested slowly is worthless until you know whose field it is.

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Can't link fields to farms | Leads have no names, so feature 1 fails | Check cadastre access early. Use the dealer's CRM and sales reps' knowledge. Offer "map your customers' fields" as onboarding |
| Cloud gaps in harvest season | Harvest dates uncertain by a week or more | Report uncertainty. Add Sentinel‑1 radar in phase 3 |
| Harvest dating inaccurate | Wrong leads damage trust | Validate on one district with ground truth before showing to a client |
| Dealer doesn't act on the data | No sales, no revenue | Design the demo around their sales workflow. Show a ranked call list, not a science map |
| Data and privacy rules for owner data | Legal exposure | Get local legal advice before storing owner names |
| Free data source changes or goes down | Pipeline breaks | Keep the STAC client generic. Support Copernicus Data Space as a fallback |

**Open questions**

- [ ] What does the public cadastral map expose for agricultural plots, and under what terms can it be used commercially?
- [ ] Which district for the MVP, and who can provide ground truth there?
- [ ] Which agro-holding or dealer to approach first, and through whom?
- [ ] Which crops matter most to them: spring wheat only, or also barley, oilseeds and others?

## Next steps

Start Phase 1 in Claude Code with one district and one past season. Export this doc as Markdown and give it to Claude Code as `PLAN.md` in the repo root.

- [ ] Pick the MVP district and a ~20 × 20 km test area inside it
- [ ] Create the Maven multi-module skeleton (`core`, `pipeline`, `cli`) on Java 21
- [ ] Ask Claude Code to build, in this order: STAC client → UTM conversion → COG window reader → NDVI + cloud mask → PNG export → a CLI command that runs it for one date
- [ ] Extend to the full 2025 or 2026 season (May–October) and plot NDVI curves for a few fields
- [ ] Implement harvest dating and render a "late harvest" map
- [ ] Write unit tests with small synthetic rasters for every algorithm step

A good first prompt for Claude Code: *"Read PLAN.md. Implement Phase 1, step by step, starting with the STAC client. Write tests for each step."*
