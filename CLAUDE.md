# QazAgroSat — working rules

Read `CLAUDE.local.md` too (private, not in git) before any work.

## The project
- Satellite field maps, crop groups and harvest dates for districts of northern Kazakhstan; public site
  QazAgroSat «Атлас полей» / Field Atlas (`site/`, built by `site/build/build.py`).
- Pipeline: Java jar (`data/kazagrosat-fields-v2.jar`: season, fields, landsat, series, harvest, district join) plus
  Python tools in `data/tayynsha/tools/` (field post-steps, crop groups, harvest rule v2, joins). `data/` is not in
  git (large and private); tools under it are added with `git add -f`.

## How we work
- One question at a time to the owner, in plain language, with a recommendation.
- Every new rule: write it down first (a note with the settings), then check it blind against the owner's marks or an
  independent figure before it reaches the site. Results go on a private page for the owner.
- A branch (or worktree in `../qaz-agro-sat-wt/`) per change; merge only after the owner's OK.
- Test first (`python <tool>/test_*.py`, `python site/build/test_*.py`); run the whole site test set before a deploy.
- Never modify or delete `data/season-*` or `experiment-output/`. Move outputs aside instead of deleting them.
- Long runs go in the background with logs under `data/`; check them, do not poll in a loop.
- Helpers (sub-agents) only for separate jobs that do not share files, the browser or the CPU-heavy runs; a cheaper
  model is fine for mechanical work with a checker script (see the holder-lookup runbook in
  `data/experiments/cadastre-edges-2026-11-15/`).

## Never
- Commit attribution lines.
- Push local `main` history to GitHub: the public repo gets sanitized snapshots only (see `CLAUDE.local.md`).
- Deploy to production without the owner's yes for that change.
- Automated queries to government sites; public map lookups happen in the owner's Chrome at a human pace.
