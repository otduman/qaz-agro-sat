# Kokshetau run: plan

10 November 2026. Agreed with the owner; to be worked through unattended. Main area: Kokshetau square
(68.70-69.00 E, 53.80-54.00 N, `data/kokshetau`); check area: Kostanay (`data`). No one can supply real harvest
dates for now.

## Steps

1. **Per-photo Landsat correction** (branch `landsat-photo-offset`). Correct each Landsat photo by its own offset,
   measured on fields that did not change between the Sentinel-2 photos either side of it. Judged by hiding the
   same-day Sentinel-2 photo. Pass mark, fixed beforehand: the typical photo's leftover B11 offset under 0.01
   (median over photos of the absolute per-photo median difference). Designed on Kokshetau 2025; checked unchanged on
   Kokshetau 2023, 2024, 2026 and Kostanay 2023-2026 (download Kostanay 2026).
2. **Kokshetau field maps.** Look into the regions over 1,000 ha (2023, 2024, 2026); pick about 35 boxes
   automatically and read each by eye; year-to-year stability.
3. **Harvest dates by eye.** For the same boxes, the cut window from photo strips, blind to the rule; 2025 and 2026
   first.
4. **Frozen harvest rule v1 on Kokshetau, unchanged.** Sentinel-2 alone, then with corrected Landsat.
5. **Results page.** Kokshetau section, Landsat correction, harvest test.
6. **Plan for Landsat in the product**, only if steps 1 and 4 show it helps; written, not built.
7. **Cadastre research.** What the public land cadastre exposes for farm plots around Kokshetau and on what terms;
   reading only, no accounts, no owner data downloaded.

## Rules

- Nothing merged into `main`; one branch per step, stacked and pushed.
- Test-first; free data, no accounts; existing data never modified; `java -Xmx1g`.
- No retuning of product settings. A needed change goes on its own branch with before/after for both areas, for the
  owner to decide.
- A step that misses its pass mark is reported, not patched until it passes.
- Out of scope: telling wheat from other crops.
