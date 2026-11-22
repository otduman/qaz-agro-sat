"""One resumable command per district (owner 2026-11-20, item 8): squares, then every district step, in the order the
published site was built (add-squares.sh, finish-new-squares.sh / rejoin-v1dates.sh, finish-tayynsha-edge.sh,
crop seen v3 inside harvest_v2.py).

  python data/tayynsha/tools/run_district.py DISTRICT [--years 2026,2025] [--from STEP] [--dry-run] [--adopt]

Run from the repository root. Stage 1, per season: add-squares.sh for every square of squares.csv that is not left out
and has no harvest-Y/harvest-Y-v1.csv yet (add-squares.sh is itself resumable). Stage 2, per season: the district
steps of the recipe; each finished step writes data/<district>/.run/<year>-<step>.done and is skipped next time.
New square harvests in a season make all of that season's district steps run again. --from STEP runs that step and
every step after it again. Log: data/run-<district>.log.

Checked 2026-11-20: Sandyktau 2025 rebuilt from scratch gave the same district table, duplicates and fallow lists as
published; the joins differed in 1 of 971 groups (field joins compare with the other season's map and holders,
which have changed since they were first run). The current state was then adopted (--adopt) so nothing reruns.

Left-out squares (owner's choices) must not have a harvest table, or the district join would count them; the runner
stops and names them. Holder lookups are not here: they need the cadastral map in the owner's Chrome.
The drawing steps (square_fields to outlines) run for every season since 2026-11-21 (owner: "apply to 2024 and 2025",
after the Google photo check data/experiments/google-borders-2026-11-21/NOTE.md); the grass steps stay 2026 only.
"""
import argparse
import csv
import datetime as dt
import os
import subprocess
import sys
from dataclasses import dataclass, field

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..'))
T = 'data/tayynsha/tools'
JAR = 'data/kazagrosat-fields-v2.jar'
EXP = 'data/experiments'
MAIN = '2026'


def other(year):
    """The season a step compares with: 2025 for 2026, else 2026."""
    return '2025' if year == MAIN else MAIN


@dataclass
class Step:
    name: str
    years: tuple = None                 # None = every season of the recipe
    make: object = None                 # (district_dir, year, recipe) -> [shell commands]

    def commands(self, ddir, year, recipe):
        return self.make(ddir, year, recipe)


@dataclass
class Recipe:
    name: str
    dir: str
    seasons: tuple
    left_out: dict = field(default_factory=dict)   # square -> seasons it stays out of
    district_steps: list = field(default_factory=list)


def _swap_in_v1(d, y, r):
    return [f'for h in {d}/E*/harvest-{y}/harvest-{y}-v1.csv; do q=$(dirname $(dirname $h)); '
            f'[ -f $q/harvest-{y}/harvest-{y}.csv ] || continue; [ -f $q/harvest-{y}/harvest-{y}-v2.csv ] || '
            f'cp $q/harvest-{y}/harvest-{y}.csv $q/harvest-{y}/harvest-{y}-v2.csv; '
            f'cp $h $q/harvest-{y}/harvest-{y}.csv; done',
            f'java -Xmx2g -jar {JAR} district join --district {d} --year {y}']


def _swap_back(d, y, r):
    return [f'for v in {d}/E*/harvest-{y}/harvest-{y}-v2.csv; do [ -f $v ] && mv $v $(dirname $v)/harvest-{y}.csv; done; true',
            f'java -Xmx2g -jar {JAR} district join --district {d} --year {y}']


def _aside(d, name):
    """Move a first-run copy a tool always starts from (joins-Y-j5/-j6) aside, so it is taken afresh."""
    stamp = dt.datetime.now().strftime('%Y%m%d-%H%M%S')
    return f'mkdir -p {d}/.run/old && {{ [ ! -f {d}/{name} ] || mv {d}/{name} {d}/.run/old/{stamp}-{name}; }}'


def _line_counts(d, y, r):
    """Line counts for squares without one: three at a time, then one by one for any a worker left (a failure stops
    the run). lines_first picks the two seasons and copies a count over the same seasons."""
    sq = f'$(cut -d, -f1 {d}/district-harvest-{y}.csv | grep "^E" | sort -u)'
    one = f'[ -f {d}/$q/lines-{y}/count.npz ] || python {T}/lines_first.py count {d} $q {y}'
    return [f'i=0; for q in {sq}; do echo "$q" >> {d}/.run/lc-$((i % 3)); i=$((i + 1)); done; '
            f'for k in 0 1 2; do [ -f {d}/.run/lc-$k ] && (for q in $(cat {d}/.run/lc-$k); do {one}; done) '
            f'> {d}/.run/line-counts-$k.log 2>&1 & done; wait; rm -f {d}/.run/lc-0 {d}/.run/lc-1 {d}/.run/lc-2',
            f'for q in {sq}; do {one} || exit 1; done']


def _line_splits(d, y, r):
    sq = f'$(cut -d, -f1 {d}/district-harvest-{y}.csv | grep "^E" | sort -u)'
    return [f'i=0; for q in {sq}; do echo "$q" >> {d}/.run/ls-$((i % 3)); i=$((i + 1)); done; '
            f'for k in 0 1 2; do [ -f {d}/.run/ls-$k ] && python {T}/line_splits.py {d} {y} $(cat {d}/.run/ls-$k) --redo '
            f'> {d}/.run/line-splits-$k.log 2>&1 & done; wait; rm -f {d}/.run/ls-0 {d}/.run/ls-1 {d}/.run/ls-2',
            f'python {T}/line_splits.py {d} {y}']


def _grass(d, y, r):
    """Grass rule (hay-2026-11-16/grass_rule.py) for squares with no row in grass-Y.csv yet; rows already there (each
    district's own grass version, Tayynsha's L2 'other' land) stay as they are."""
    return [f'python {T}/run_district.py --grass-new {d} {y}']


def _steps(plot_joins=False, esil=False):
    s = [Step('join_v1', None, _swap_in_v1),
         Step('duplicates', None, lambda d, y, r: [f'python {T}/square_duplicates.py {d} {y}']),
         Step('field_joins', None, lambda d, y, r: [f'python {T}/field_joins.py {d} {y} {other(y)}'])]
    if plot_joins:
        s.append(Step('plot_joins', None, lambda d, y, r: [
            f'YEAR={y} python {EXP}/plot-joins-2026-11-15/plotjoins.py',
            f'cp {EXP}/plot-joins-2026-11-15/joins-{y}-v4.geojson {d}/joins-{y}.geojson']))
    s += [Step('absorb', None, lambda d, y, r: [
              f'python {EXP}/small-fields-2026-11-15/absorb.py {d} {y}',
              f'cp {EXP}/small-fields-2026-11-15/joins-{os.path.basename(d)}-{y}-v5.geojson {d}/joins-{y}.geojson']),
          Step('join_v2', None, _swap_back),
          Step('square_fields', None, lambda d, y, r: [_aside(d, f'joins-{y}-j5.geojson'),
                                                          f'python {T}/square_fields.py {d} {y}']),
          Step('field_splits', None, lambda d, y, r: [f'python {T}/field_splits.py {d} {y}']),
          Step('line_counts', None, _line_counts),
          Step('evidence_joins', None, lambda d, y, r: [_aside(d, f'joins-{y}-j6.geojson'),
                                                           f'python {T}/evidence_joins.py {d} {y}']),
          Step('line_splits', None, _line_splits),
          Step('outlines', None, lambda d, y, r: [f'python {T}/simple_outlines.py {d} {y}']),
          Step('fallow_both', None, lambda d, y, r: [f'python {T}/fallow_both_years.py {d} {y} {other(y)}'])]
    if esil:
        s.append(Step('fallow_steppe', (MAIN,), lambda d, y, r: [f'python {T}/fallow_steppe.py {d} {y}']))
    s += [Step('grass', (MAIN,), _grass)]
    if esil:
        s.append(Step('fallow_crop', (MAIN,), lambda d, y, r: [
            f'cd {EXP}/esil-fallow-crop-2026-11-17 && python apply.py "{ROOT}/{d}" esil-akmola']))
    s += [Step('grass_history', (MAIN,), lambda d, y, r: [f'python {T}/grass_history.py {d} {y}']),
          Step('grass_use', (MAIN,), lambda d, y, r: [f'cd {EXP}/grass-use-2026-11-19 && python use_run.py "{ROOT}/{d}"'])]
    return s


EDGE_2024 = ('E560N5900', 'E600N5900', 'E460N5920', 'E480N5920', 'E500N5920', 'E520N5920', 'E540N5920', 'E460N5940',
             'E460N5960', 'E460N5980', 'E620N5980', 'E520N6000')
ALL = ('2026', '2025', '2024')
RECIPES = {
    # owner 2026-11-19/20: outer rows out, E640N5960 out (tidy only), edge squares not run for 2024
    'tayynsha': Recipe('tayynsha', 'data/tayynsha', ALL,
                       {**{s: ALL for s in ('E580N5880', 'E580N6020', 'E640N5940', 'E640N5960', 'E640N5980')},
                        'E560N6020': ('2024',), **{s: ('2024',) for s in EDGE_2024}},
                       _steps(plot_joins=True)),
    # owner 2026-11-19: Sandyktau 2024 dropped; six squares of squares.csv never run
    'sandyktau': Recipe('sandyktau', 'data/sandyktau', ('2026', '2025'),
                        {s: ALL for s in ('E480N5740', 'E400N5760', 'E400N5780', 'E440N5840', 'E460N5840', 'E480N5840')},
                        _steps()),
    'esil-akmola': Recipe('esil', 'data/esil-akmola', ALL,
                          {s: ALL for s in ('E360N5700', 'E360N5740', 'E360N5760', 'E360N5780')}, _steps(esil=True)),
}


def squares(ddir):
    return [r['square'] for r in csv.DictReader(open(f'{ddir}/squares.csv', encoding='utf-8'))]


def squares_to_run(ddir, recipe, year):
    return [s for s in squares(ddir) if year not in recipe.left_out.get(s, ())
            and not os.path.isfile(f'{ddir}/{s}/harvest-{year}/harvest-{year}-v1.csv')]


def left_out_problems(ddir, recipe):
    return [f'{s} {y}: left out but has harvest-{y}/harvest-{y}.csv' for s in squares(ddir)
            for y in recipe.seasons if y in recipe.left_out.get(s, ()) and os.path.isfile(f'{ddir}/{s}/harvest-{y}/harvest-{y}.csv')]


def _marker(ddir, year, step):
    return f'{ddir}/.run/{year}-{step}.done'


def mark_done(ddir, year, step):
    os.makedirs(f'{ddir}/.run', exist_ok=True)
    open(_marker(ddir, year, step), 'w').write(dt.datetime.now().isoformat(timespec='seconds') + '\n')


def invalidate(ddir, year, steps=None):
    """Forget finished steps of a season (all, or the named ones)."""
    if not os.path.isdir(f'{ddir}/.run'):
        return
    for n in os.listdir(f'{ddir}/.run'):
        if n.startswith(f'{year}-') and n.endswith('.done') and (steps is None or n[len(year) + 1:-5] in steps):
            os.remove(f'{ddir}/.run/{n}')


def adopt(ddir, recipe, year):
    """Mark every district step of a season done: for data that is already current (built by the older scripts)."""
    for s in recipe.district_steps:
        if s.years is None or year in s.years:
            mark_done(ddir, year, s.name)


def plan(ddir, recipe, year, start=None):
    """The district steps still to run for a season; with start, that step and every later one run again."""
    steps = [s for s in recipe.district_steps if s.years is None or year in s.years]
    if start:
        names = [s.name for s in steps]
        if start not in names:
            raise SystemExit(f'no step {start} for {year}; steps: {", ".join(names)}')
        return steps[names.index(start):]
    return [s for s in steps if not os.path.isfile(_marker(ddir, year, s.name))]


def grass_new(ddir, year):
    """Append grass-rule rows (hay-2026-11-16/grass_rule.py) for squares with none in grass-Y.csv yet."""
    import shutil
    import tempfile
    path = f'{ddir}/grass-{year}.csv'
    have = {r['square'] for r in csv.DictReader(open(path, encoding='utf-8'))} if os.path.isfile(path) else set()
    table = {r['square'] for r in csv.DictReader(open(f'{ddir}/district-harvest-{year}.csv', encoding='utf-8'))}
    todo = sorted(table - have)
    if not todo:
        print('grass: every square has its rows')
        return
    tmp = tempfile.mkdtemp()
    try:
        subprocess.run([sys.executable, f'{ROOT}/{EXP}/hay-2026-11-16/grass_rule.py', f'{ROOT}/{ddir}',
                        os.path.basename(ddir)], cwd=tmp, check=True)
        rows = [r for r in csv.DictReader(open(f'{tmp}/grass-{os.path.basename(ddir)}.csv', encoding='utf-8'))
                if r['square'] in todo]
    finally:
        shutil.rmtree(tmp)
    new = not os.path.isfile(path)
    with open(path, 'a', newline='', encoding='utf-8') as fh:
        w = csv.DictWriter(fh, fieldnames=['square', 'region', 'land', 'area_ha'])
        if new:
            w.writeheader()
        w.writerows(rows)
    print(f'grass: {len(rows)} regions added for {len(todo)} squares')


def run(cmd, log):
    with open(log, 'a', encoding='utf-8') as fh:
        fh.write(f'$ {cmd}\n')
        fh.flush()
        return subprocess.run(['sh', '-c', cmd], cwd=ROOT, stdout=fh, stderr=subprocess.STDOUT).returncode


def main():
    if len(sys.argv) > 1 and sys.argv[1] == '--grass-new':
        grass_new(sys.argv[2].rstrip('/'), sys.argv[3])
        return
    ap = argparse.ArgumentParser()
    ap.add_argument('district', choices=sorted(RECIPES))
    ap.add_argument('--years')
    ap.add_argument('--from', dest='start')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--adopt', action='store_true', help='mark the seasons done as they are now, run nothing')
    a = ap.parse_args()
    r = RECIPES[a.district]
    ddir = r.dir
    os.chdir(ROOT)
    years = a.years.split(',') if a.years else list(r.seasons)
    log = f'{ROOT}/data/run-{a.district}.log'
    bad = left_out_problems(ddir, r)
    if bad:
        raise SystemExit('left-out squares with a harvest table (move it aside first):\n  ' + '\n  '.join(bad))
    say = lambda m: (print(m, flush=True), open(log, 'a', encoding='utf-8').write(f'# {m} {dt.datetime.now():%H:%M:%S}\n'))
    if a.adopt:
        for y in years:
            adopt(ddir, r, y)
            say(f'{a.district} {y}: adopted as it is (every district step marked done)')
        return
    for y in years:
        todo = squares_to_run(ddir, r, y)
        steps = plan(ddir, r, y, a.start) if not todo else [s for s in r.district_steps if s.years is None or y in s.years]
        say(f'{a.district} {y}: {len(todo)} squares to run, district steps: {", ".join(s.name for s in steps) or "none"}')
        if a.dry_run:
            for s in steps:
                for c in s.commands(ddir, y, r):
                    print('   ', c)
            continue
        if todo:
            if run(f'sh data/add-squares.sh {os.path.basename(ddir)} {y} {" ".join(todo)}', log):
                raise SystemExit(f'add-squares failed for {y}; see {log}')
            invalidate(ddir, y)
            steps = plan(ddir, r, y)
        if a.start:
            invalidate(ddir, y, {s.name for s in steps})
        for s in steps:
            say(f'{y} {s.name}')
            for c in s.commands(ddir, y, r):
                if run(c, log):
                    raise SystemExit(f'{y} {s.name} failed; see {log}. Fix it and run again: finished steps are kept.')
            mark_done(ddir, y, s.name)
    say(f'{a.district}: all done')


if __name__ == '__main__':
    main()
