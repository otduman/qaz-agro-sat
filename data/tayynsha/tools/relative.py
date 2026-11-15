"""Cut rule v2: v1 first; where v1 finds no cut, v1 again on B11 relative to the same day's ripe cropped fields.
The rule and its tests are frozen in docs/superpowers/notes/2026-11-10-relative-cut.md. Built on rule.py (the
Python copy of the Java dating). Throwaway experiment code.

Readings are rule.py tuples: (date, satellite, clear share, ndvi, b11, blue, snow share).
"""
import os
import statistics as st
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rule  # noqa: E402

MIN_FIELDS = 20
CROP_NDVI = 0.5
CUT = ('dated', 'cut in a cloud gap')


def cropped(rows):
    """Fields whose NDVI reaches 0.5 on a usable reading from May to August (the harvest step's crop gate)."""
    return {k for k, rs in rows.items()
            if any(5 <= r[0].month <= 8 and r[2] >= rule.MIN_CLEAR and r[3] >= CROP_NDVI for r in rs)}


def reference(kept, crop):
    """date -> median B11 of the cropped fields with a usable, ripe (NDVI below 0.4) reading that date; only dates
    with at least MIN_FIELDS such fields."""
    by = {}
    for k, rs in kept.items():
        if k not in crop:
            continue
        for r in rs:
            if r[2] >= rule.MIN_CLEAR and r[3] < rule.RIPE:
                by.setdefault(r[0], []).append(r[4])
    return {d: st.median(v) for d, v in by.items() if len(v) >= MIN_FIELDS}


def relative(readings, ref):
    return [r[:4] + (r[4] - ref[r[0]],) + r[5:] for r in readings if r[0] in ref]


def judge(kept):
    """rule.date_all's verdict for one field's kept readings."""
    count = sum(1 for r in kept if r[0].month >= 8)
    step = rule.biggest(kept)
    if count < rule.MIN_READ or step is None:
        return 'unclear', None, None, step[2] if step else None
    if step[2] < rule.JUMP:
        return 'no cut found', None, None, step[2]
    status = 'dated' if (step[1] - step[0]).days <= rule.MAX_WINDOW else 'cut in a cloud gap'
    return status, step[0], step[1], step[2]


def date_fields(rows, crop, landsat=True):
    """field -> (status, from, to, rise, 'v1' or 'v2'). `crop`: fields that pass the crop gate."""
    v1, dropped, us = rule.date_all(rows, landsat)
    kept = {k: [r for r in rs if r[0] not in dropped and r[6] <= 0] for k, rs in us.items()}
    ref = reference(kept, crop)
    out = {}
    for k in rows:
        if k not in crop:
            out[k] = ('no crop seen', None, None, None, '-')
            continue
        if v1[k][0] in CUT:
            out[k] = v1[k] + ('v1',)
            continue
        v2 = judge(relative(kept[k], ref))
        out[k] = v2 + ('v2',) if v2[0] in CUT else v1[k] + ('v1',)
    return out
