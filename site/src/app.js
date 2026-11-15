/* Field Atlas page: loads config, interface text, district text and data; district and language switches. */
(function () {
  const S = { lang: null, district: null, year: null };
  let cfg = null, ui = null, C = null, D = null;
  const cache = {};
  // A failed load is forgotten, so the next click asks the server again instead of failing from the cache.
  const get = url => cache[url] || (cache[url] = fetch(url)
    .then(r => { if (!r.ok) throw new Error(url + ' ' + r.status); return r.json(); })
    .catch(e => { delete cache[url]; throw e; }));
  const $ = id => document.getElementById(id);
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const store = { get: k => { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set: (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } } };

  const locale = () => S.lang === 'ru' ? 'ru-RU' : 'en-US';
  const fmt = n => Number(n).toLocaleString(locale());
  const n1 = v => v == null ? '—' : Number(v).toLocaleString(locale(), { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  const t = path => path.split('.').reduce((o, k) => (o == null ? o : o[k]), ui);

  const yearsOf = d => (cfg.years && cfg.years[d]) || [cfg.year];
  const mainYear = () => cfg.year;
  // The main season keeps the bare file names and link; another season adds -<year> / /<year>.
  const stem = () => S.year === mainYear() ? S.district : `${S.district}-${S.year}`;
  const hash = () => '#' + S.lang + '/' + S.district + (S.year === mainYear() ? '' : '/' + S.year);
  function fixYear() { if (!yearsOf(S.district).includes(S.year)) S.year = mainYear(); }
  function readHash() {
    const [l, d, y] = location.hash.replace(/^#/, '').split('/');
    if (cfg.languages.includes(l)) S.lang = l;
    if (cfg.districts.includes(d)) S.district = d;
    S.year = y || mainYear();
    fixYear();
  }

  function staticText() {
    document.documentElement.lang = S.lang;
    document.querySelectorAll('[data-t]').forEach(e => { e.textContent = t(e.dataset.t); });
    document.querySelectorAll('[data-t-aria]').forEach(e => e.setAttribute('aria-label', t(e.dataset.tAria)));
    $('brand').innerHTML = esc(cfg.title[S.lang]) + (cfg.subtitle ? ` <span class="sub">${esc(cfg.subtitle[S.lang])}</span>` : '');
    $('district-switch').innerHTML = cfg.districts.map(d => `<button type="button" data-d="${d}" aria-pressed="${d === S.district}">${esc(ui.districts[d])}</button>`).join('');
    const ys = yearsOf(S.district);
    $('year-switch').hidden = ys.length < 2;
    $('year-switch').innerHTML = ys.map(y => `<button type="button" data-y="${y}" aria-pressed="${y === S.year}">${y}</button>`).join('');
    $('lang-switch').innerHTML = cfg.languages.map(l => `<button type="button" data-l="${l}" aria-pressed="${l === S.lang}">${l.toUpperCase()}</button>`).join('');
    const a = $('author');
    a.hidden = !cfg.author;
    if (cfg.author) a.textContent = `${ui.footer.author}: ${cfg.author}`;
  }

  function districtText() {
    document.title = `${C.title} · ${cfg.title[S.lang]}`;
    $('eyebrow').textContent = C.eyebrow;
    $('title').textContent = C.title;
    $('lede').textContent = C.lede;
    $('meta').textContent = C.meta;
    $('stats').innerHTML = C.stats.map(s => `<div class="stat"><span class="n">${esc(s.n)}</span><span class="l">${esc(s.label)}</span></div>`).join('');
    [['map-h', 'map_h', 'map'], ['acc-h', 'accounts_h', 'accounts'], ['charts-h', 'charts_h', 'charts'], ['pace-h', 'pace_h', 'pace']]
      .forEach(([id, k, sec]) => { $(id).textContent = C[k] || t('sections.' + sec); });
    $('map-intro').textContent = C.map_intro;
    $('map-note').textContent = C.map_note;
    $('acc-intro').textContent = C.accounts_intro;
    $('callout').hidden = !C.callout;
    $('callout-text').innerHTML = C.callout || '';
    $('acc-caption').textContent = C.table_caption;
    $('private-note').textContent = C.private_note;
    $('charts-intro').textContent = C.charts_intro;
    $('pace-intro').textContent = C.pace_intro;
    const half = Math.ceil(C.trust.length / 2);
    const list = items => `<ul class="plain">${items.map(i => `<li><strong>${esc(i.label)}:</strong> ${esc(i.text)}</li>`).join('')}</ul>`;
    $('trust').innerHTML = list(C.trust.slice(0, half)) + list(C.trust.slice(half));
    $('sources').innerHTML = C.sources.map(s => `<li>${s}</li>`).join('');
  }

  function key() {
    const mode = FieldMap.mode, L = ui.legend;
    const spec = mode === 'crop'
      ? { title: L.crop_title, hint: L.crop_hint, items: [['c-grain', 'var(--grain)', L.grain], ['c-oilseed', 'var(--oil)', L.oilseed], ['c-other', 'var(--other)', L.other], ['c-none', 'var(--nochange)', L.none]] }
      : { title: L.time_title, hint: L.time_hint, items: [['t-1', 'var(--t1)', L.t1], ['t-2', 'var(--t2)', L.t2], ['t-3', 'var(--t3)', L.t3], ['t-4', 'var(--t4)', L.t4], ['t-5', 'var(--t5)', L.t5], ['t-0', 'var(--nochange)', L.t0]] };
    const ha = {}; let total = 0;
    D.fields.forEach(f => { const c = FieldMap.classOf(f, mode); ha[c] = (ha[c] || 0) + f.ha; total += f.ha; });
    $('key').innerHTML = `<h3>${esc(spec.title)}</h3><ul class="key">${spec.items.map(([c, col, lab]) => {
      const a = ha[c] || 0, sh = total ? a / total : 0;
      return `<li><i style="background:${col}"></i><span>${esc(lab)}</span><span class="v">${fmt(Math.round(a / 1000))} ${ui.map.thousand_ha} · ${Math.round(sh * 100)}%</span><span class="bar"><span style="width:${(sh * 100).toFixed(1)}%;background:${col}"></span></span></li>`;
    }).join('')}${(D.fallow || []).length ? `<li><i class="hatch"></i><span>${esc(ui.legend.fallow)}</span><span class="v">${fmt(Math.round(D.fallow.reduce((a, q) => a + q.ha, 0) / 1000))} ${ui.map.thousand_ha}</span></li>` : ''}${(D.plots || []).length ? `<li><i style="border:2px dashed var(--plot);background:none"></i><span>${esc(ui.map.plot_line)}</span></li>` : ''}</ul><p class="hint">${esc(spec.hint)}</p>`;
  }

  const dfmt = s => { const [, m, d] = s.split('-').map(Number); return `${d} ${ui.months[m - 1]}`; };
  function harvest(f) {
    const H = ui.harvest;
    if (f.s === 'dated') return H.dated.replace('{from}', dfmt(f.f)).replace('{to}', dfmt(f.t));
    if (f.s === 'cut in a cloud gap') return H.cloud.replace('{from}', dfmt(f.f)).replace('{to}', dfmt(f.t));
    if (f.s === 'no crop seen') return H.nocrop;
    return H.none;
  }
  function holder(f) {
    const K = ui.kinds;
    if (f.h) return esc(f.h) + (f.k === 'group' ? ` <span class="chip">${esc(K.group_chip)}</span>` : '');
    if (f.k) return esc(K[f.k] || K.other);
    return esc(f.r ? K.not_found : K.unread);
  }
  function emptyPanel() {
    $('panel').innerHTML = `<h3>${esc(ui.map.pick)}</h3><p class="hint">${esc(ui.map.pick_hint)}</p>`;
  }
  function showField(f) {
    const p = $('panel'); p.classList.remove('flash'); void p.offsetWidth; p.classList.add('flash');
    $('panel').innerHTML = `<h3>${esc(ui.map.field)} ${fmt(f.ha)} ${ui.map.ha}</h3><dl>
      <dt>${esc(ui.panel.crop)}</dt><dd>${esc(ui.legend[f.c])}</dd>
      <dt>${esc(ui.panel.harvest)}</dt><dd>${esc(harvest(f))}</dd>
      <dt>${esc(ui.panel.holder)}</dt><dd>${holder(f)}</dd></dl>`;
  }

  const TOP = 25;
  let showAll = false;
  function table() {
    const T = ui.table, hasAvg = D.accounts.some(a => a.avg != null);
    const rows = D.accounts.filter(a => a.ha >= 150), shown = showAll ? rows : rows.slice(0, TOP);
    $('acc').querySelector('thead tr').innerHTML = `<th>${esc(T.company)}</th><th class="n">${esc(T.fields_ha)}</th><th class="n">${esc(T.grain)}</th><th class="n">${esc(T.oilseed)}</th>`
      + (hasAvg ? `<th class="n">${esc(T.avg)}</th>` : '') + `<th class="n">${esc(T.modern)}</th><th>${esc(T.whole)}</th>`;
    $('acc').querySelector('tbody').innerHTML = shown.map(a => `<tr>
      <td>${esc(a.name)}${a.group ? `<span class="chip">${esc(ui.kinds.group_chip)}</span>` : ''}</td>
      <td class="n">${fmt(a.ha)}</td><td class="n">${fmt(a.grain)}</td><td class="n">${fmt(a.oil)}</td>
      ${hasAvg ? `<td class="n">${n1(a.avg)}</td>` : ''}<td class="n">${n1(a.modern[0])}–${n1(a.modern[1])}</td>
      <td>${a.whole ? `${esc(a.whole)}${a.url ? `<a class="src" href="${esc(a.url)}" rel="noopener">${esc(a.src || a.url)}</a>` : ''}` : '<span style="color:var(--muted)">—</span>'}</td></tr>`).join('');
    $('pair-note').hidden = !D.accounts.some(a => a.pair);
    $('pair-note').textContent = T.pair_note;
    const more = $('acc-more');
    more.hidden = rows.length <= TOP;
    more.textContent = showAll ? T.show_fewer : T.show_all.replace('{n}', fmt(rows.length));
    more.setAttribute('aria-expanded', String(showAll));
  }

  let seq = 0, shown = null;
  async function render() {
    const mine = ++seq;  // a slower, older load must not overwrite a newer choice
    $('page').setAttribute('aria-busy', 'true');
    if (ui) $('loading').textContent = ui.map.loading;
    location.replace(hash());
    try {
      const got = await Promise.all([get(`i18n/${S.lang}.json`), get(`content/${stem()}.${S.lang}.json`), get(`data/${stem()}.json`)]);
      if (mine !== seq) return;
      [ui, C, D] = got;
      staticText();
      districtText();
      FieldMap.load(D, ui, ui.towns[S.district]);
      key(); emptyPanel(); table();
      Charts.render(D, C, ui, { int: fmt, one: n1 });
      shown = { lang: S.lang, district: S.district, year: S.year };
      store.set('atlas.lang', S.lang); store.set('atlas.district', S.district);
      $('load-error').hidden = true;
    } catch (err) {
      if (mine !== seq) return;
      if (!shown) throw err;  // nothing on screen yet: start() reports it
      // Stay on what is shown, say the load failed; the next click tries again.
      S.lang = shown.lang; S.district = shown.district; S.year = shown.year;
      location.replace(hash());
      $('load-error').textContent = ui.nav.load_error;
      $('load-error').hidden = false;
    }
    $('page').setAttribute('aria-busy', 'false');
  }

  async function start() {
    cfg = await get('config.json');
    S.lang = store.get('atlas.lang') || cfg.default_language;
    S.district = store.get('atlas.district') || cfg.districts[0];
    if (!cfg.languages.includes(S.lang)) S.lang = cfg.default_language;
    if (!cfg.districts.includes(S.district)) S.district = cfg.districts[0];
    S.year = mainYear();
    readHash();
    FieldMap.init($('map'), $('scale'), showField);
    document.addEventListener('click', e => {
      const b = e.target.closest('button');
      if (!b) return;
      if (b.dataset.d && b.dataset.d !== S.district) { S.district = b.dataset.d; fixYear(); showAll = false; render(); }
      else if (b.dataset.y && b.dataset.y !== S.year) { S.year = b.dataset.y; showAll = false; render(); }
      else if (b.dataset.l && b.dataset.l !== S.lang) { S.lang = b.dataset.l; render(); }
      else if (b.dataset.mode) {
        FieldMap.setMode(b.dataset.mode);
        document.querySelectorAll('[data-mode]').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
        key();
      }
    });
    $('sat-toggle').addEventListener('click', e => { const on = !FieldMap.sat; FieldMap.setSat(on); e.currentTarget.setAttribute('aria-pressed', String(on)); });
    $('acc-more').addEventListener('click', () => { showAll = !showAll; table(); });
    $('z-in').addEventListener('click', () => FieldMap.zoom(1.5));
    $('z-out').addEventListener('click', () => FieldMap.zoom(1 / 1.5));
    $('z-reset').addEventListener('click', () => FieldMap.reset());
    window.addEventListener('hashchange', () => { const l = S.lang, d = S.district, y = S.year; readHash(); if (l !== S.lang || d !== S.district || y !== S.year) render(); });
    await render();
  }

  start().catch(err => { $('page').setAttribute('aria-busy', 'false'); $('lede').textContent = 'Could not load the atlas: ' + err.message; });
})();
