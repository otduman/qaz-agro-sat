"""Crop seen v3 (NOTE.md), development: features and the rule on a raw field series (rows of series-Y.csv).
crop = bare in spring AND green in summer AND not ploughed bare in July."""
SPRING = ('05-01', '06-10'); SPRING_MAX = 0.30          # sown land is bare in spring (steppe and grass are green)
GREEN = ('06-20', '08-20'); GREEN_NDVI = 0.55; GREEN_CLEAR = 0.40
JULY_END = '07-31'; PLOUGHED = 0.25                      # worked fallow: green weeds then bare soil before August
CLEAR = 0.60
SPREAD = 0.04; SPREAD_CLEAR = 0.80                 # a sown seedbed is even in spring; steppe, grass, wetland are patchy


def _v(r, clear):
    try:
        if float(r['clear_share'] or 0) < clear or float(r.get('snow_share') or 0) > 0.02 or r['ndvi_median'] == '':
            return None
        return float(r['ndvi_median'])
    except ValueError:
        return None


def features(rows):
    spring = [v for r in rows if SPRING[0] <= r['date'][5:] <= SPRING[1] and (v := _v(r, CLEAR)) is not None]
    green = [(r['date'][5:], v) for r in rows if GREEN[0] <= r['date'][5:] <= GREEN[1] and (v := _v(r, GREEN_CLEAR)) is not None]
    peak = max(green, key=lambda x: x[1]) if green else (None, 0.0)
    after = [v for r in rows if peak[0] and peak[0] < r['date'][5:] <= JULY_END and (v := _v(r, CLEAR)) is not None]
    sp = sorted(float(r['ndvi_p75']) - float(r['ndvi_p25']) for r in rows if SPRING[0] <= r['date'][5:] <= SPRING[1]
                and _v(r, SPREAD_CLEAR) is not None and r.get('ndvi_p75') not in (None, '') and r.get('ndvi_p25') not in (None, ''))
    return dict(spread=sp[len(sp) // 2] if sp else None, spring_max=max(spring) if spring else None, spring_min=min(spring) if spring else None,
                peak=peak[1], peak_date=peak[0], july_low=min(after) if after else None)


def crop_v3(rows):
    f = features(rows)
    if f['spring_max'] is not None and (f['spring_max'] >= SPRING_MAX or f['spring_min'] < 0):
        return False
    if f['spread'] is None or f['spread'] >= SPREAD:
        return False
    if f['peak'] < GREEN_NDVI:
        return False
    if f['july_low'] is not None and f['july_low'] < PLOUGHED:
        return False
    return True
