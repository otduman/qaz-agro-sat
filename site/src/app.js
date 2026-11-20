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
    $('brand').innerHTML = `<span class="name">${esc(cfg.title[S.lang])}</span>` + (cfg.subtitle ? `<span class="sub">${esc(cfg.subtitle[S.lang])}</span>` : '');
    // districts: a list on the map, and the same list in the headline's picker
    $('mpick-name').textContent = ui.districts[S.district];
    $('district-switch').innerHTML = cfg.districts.map(d => `<button type="button" data-d="${d}" aria-pressed="${d === S.district}">${esc(ui.districts[d])}</button>`).join('');
    $('district-menu').innerHTML = cfg.districts.map(d => `<button type="button" data-dm="${d}" aria-pressed="${d === S.district}">${esc(ui.districts[d])}</button>`).join('');
    $('method-steps').innerHTML = (ui.method.steps || []).map(s => `<li><b>${esc(s[0])}</b><span>${esc(s[1])}</span></li>`).join('');
    const ys = yearsOf(S.district);
    // seasons: one row of year buttons over the bottom of the map, oldest first
    const asc = [...ys].sort();
    $('year-switch').hidden = ys.length < 2;
    $('year-switch').innerHTML = `<span class="lab">${esc(t('nav.year'))}</span><div class="seg">`
      + asc.map(y => `<button type="button" data-y="${y}" aria-pressed="${y === S.year}">${y}</button>`).join('') + '</div>';
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
      ? { title: L.crop_title, hint: L.crop_hint, items: [['c-grain', 'var(--grain)', L.short.grain], ['c-oilseed', 'var(--oil)', L.short.oilseed]] }
      : { title: L.time_title, hint: L.time_hint, items: [['t-1', 'var(--t1)', L.t1], ['t-2', 'var(--t2)', L.t2], ['t-3', 'var(--t3)', L.t3], ['t-4', 'var(--t4)', L.t4], ['t-5', 'var(--t5)', L.t5], ['t-0', 'var(--nochange)', L.t0]] };
    const ha = {}; let total = 0;
    D.fields.forEach(f => { const c = FieldMap.classOf(f, mode); ha[c] = (ha[c] || 0) + f.ha; total += f.ha; });
    // sown grass fields and the grass layer are one group: hay and pasture
    const grassHa = (D.grass || []).reduce((a, q) => a + q.ha, 0) + (mode === 'crop' ? (ha['c-other'] || 0) : 0);
    const row = (icon, lab, v, sub, tip) => `<li${tip ? ` title="${esc(tip)}"` : ''}>${icon}<span>${esc(lab)}${sub ? `<small>${esc(sub)}</small>` : ''}</span><span class="v">${v}</span></li>`;
    const kha = a => `${fmt(Math.round(a / 1000))} ${ui.map.thousand_ha}`;
    // 2026-11-19: grass by use when the data says it (pasture near villages and farms / old fields / steppe)
    const grassRows = () => {
      const g = D.grass || [];
      if (!g.length) return '';
      if (!g.some(q => q.u)) return row('<i class="grass"></i>', L.short.grass, kha(grassHa), L.grass_note);
      const sown = mode === 'crop' ? (ha['c-other'] || 0) : 0;
      return ['pasture', 'oldfield', 'steppe'].map(u => {
        const s = g.filter(q => q.u === u).reduce((x, q) => x + q.ha, 0) + (u === 'oldfield' ? sown : 0);
        return s ? row(`<i class="grass u-${u}"></i>`, L.grass_use[u], kha(s), '', L.grass_use[u + '_tip']) : '';
      }).join('');
    };
    $('key').innerHTML = `<h3>${esc(spec.title)}</h3><ul class="key">${spec.items.map(([c, col, lab]) => {
      const a = ha[c] || 0;
      return row(`<i style="background:${col}"></i>`, lab, mode === 'crop' ? kha(a) : `${Math.round((total ? a / total : 0) * 100)}%`);
    }).join('')}${grassRows()}${(D.fallow || []).length ? row('<i class="hatch"></i>', L.short.fallow, kha(D.fallow.reduce((a, q) => a + q.ha, 0))) : ''}${(D.plots || []).length ? row('<i style="border:2px dashed var(--plot);background:none"></i>', L.short.plot, '') : ''}</ul>`;
  }

  const dfmt = s => { const [, m, d] = s.split('-').map(Number); return `${d} ${ui.months[m - 1]}`; };
  function harvest(f) {
    const H = ui.harvest_short, span = (a, b) => {
      const [, ma, da] = a.split('-').map(Number), [, mb, db] = b.split('-').map(Number);
      return ma === mb ? `${da}–${db} ${ui.months[mb - 1]}` : `${da} ${ui.months[ma - 1]} – ${db} ${ui.months[mb - 1]}`;
    };
    if (f.s === 'dated') return span(f.f, f.t);
    if (f.s === 'cut in a cloud gap') return H.cloud.replace('{span}', span(f.f, f.t));
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
    FieldMap.highlight(null);
    $('panel').innerHTML = `<span class="eyebrow">${esc(ui.map.field)}</span><h3>${esc(ui.map.pick)}</h3><p class="hint">${esc(ui.map.pick_hint)}</p>`;
  }
  // the field's class in each season of the district (field history, owner 2026-11-20)
  function histLabel(c) {
    if (c == null) return ui.panel.nodata;
    if (c === 'other' || c === 'grass') return ui.legend.short.grass;
    return ui.legend.short[c] || ui.legend[c] || ui.panel.nodata;
  }
  function history(f) {
    const years = [D.year, ...Object.keys(f.y || {})].sort().reverse();
    return `<div class="hists">${years.map(y => `<span class="hist"><b>${esc(y)}</b>${esc(histLabel(y === D.year ? f.c : f.y[y]))}</span>`).join('')}</div>`;
  }
  // greenness through the season: the field's Sentinel-2 NDVI, 1 May - 31 October, with the harvest date if known
  const curveCache = new Map();
  function curveSVG(dates, v, f) {
    const W = 260, H = 90, L = 22, R = 6, T = 6, B = 16, year = +D.year;
    const day = s => (Date.UTC(year, +s.slice(0, 2) - 1, +s.slice(3, 5)) - Date.UTC(year, 4, 1)) / 864e5;
    const X = d => L + d / 183 * (W - L - R), Y = n => T + (1 - n / 90) * (H - T - B);
    let h = [30, 60].map(n => `<line class="grid" x1="${L}" x2="${W - R}" y1="${Y(n)}" y2="${Y(n)}"/><text class="ax" x="2" y="${Y(n) + 3}">${(n / 100).toFixed(1)}</text>`).join('');
    h += [0, 31, 61, 92, 123, 153].map((d, k) => `<text class="ax" x="${X(d)}" y="${H - 3}">${esc(ui.months[4 + k])}</text>`).join('');
    if (f.m) h += `<line class="cut" x1="${X(day(f.m.slice(5)))}" x2="${X(day(f.m.slice(5)))}" y1="${T}" y2="${H - B}"><title>${esc(ui.panel.cut)} ${esc(dfmt(f.m))}</title></line>`;
    const pts = dates.map((s, k) => [s, v[k]]).filter(([, n]) => n != null);
    if (pts.length) {
      h += `<path class="line" d="${pts.map(([s, n], k) => `${k ? 'L' : 'M'}${X(day(s)).toFixed(1)} ${Y(n).toFixed(1)}`).join('')}"/>`;
      h += pts.map(([s, n]) => `<circle class="dot" r="1.6" cx="${X(day(s)).toFixed(1)}" cy="${Y(n).toFixed(1)}"/>`).join('');
    }
    return `<svg class="curve" viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(ui.panel.curve)}">${h}</svg>`;
  }
  async function curve(f, token) {
    if (!f.q) return;
    const key = `${stem()}/${f.q}`;
    try {
      if (!curveCache.has(key)) curveCache.set(key, get(`data/curves/${key}.json`));
      const c = await curveCache.get(key), v = c.f[String(f.i)];
      if (token !== pickToken || !v) return;
      $('curve-box').innerHTML = curveSVG(c.d, v, f);
    } catch (e) { /* no curve for this square: the card stays without it */ }
  }
  let pickToken = 0;
  function showField(f) {
    FieldMap.highlight(null);
    const p = $('panel'); p.classList.remove('flash'); void p.offsetWidth; p.classList.add('flash');
    const crop = f.c === 'other' ? ui.legend.short.grass : (ui.legend.short[f.c] || ui.legend[f.c]);
    const who = holder(f), plain = who.replace(/<[^>]+>/g, '').replace(/"/g, '&quot;');
    $('panel').innerHTML = `<span class="eyebrow">${esc(ui.map.field)}</span><h3>${esc(crop)} · ${fmt(f.ha)} ${ui.map.ha}</h3><dl>
      <dt>${esc(ui.panel.harvest)}</dt><dd>${esc(harvest(f))}</dd>
      <dt>${esc(ui.panel.holder)}</dt><dd class="clip" title="${plain}">${who}</dd>
      <dt>${esc(ui.panel.history)}</dt><dd>${history(f)}</dd>
      <dt>${esc(ui.panel.curve)}</dt><dd id="curve-box"></dd></dl>`;
    curve(f, ++pickToken);
  }
  // a company's card: its fields on the map, area, crops and how much the satellite saw harvested
  function showCompany(names, title) {
    pickToken++;
    const set = new Set(names), mine = D.fields.filter(f => f.h && set.has(f.h));
    FieldMap.highlight(f => f.h && set.has(f.h));
    const ha = k => mine.filter(f => f.c === k).reduce((a, f) => a + f.ha, 0);
    const grain = ha('grain'), cut = mine.filter(f => f.c === 'grain' && f.m).reduce((a, f) => a + f.ha, 0);
    const P = ui.company, split = C.crop_split !== false;
    $('panel').innerHTML = `<div class="company"><span class="eyebrow">${esc(P.title)}</span><h3 title="${esc(title)}">${esc(title)}</h3><dl>
      <dt>${esc(P.fields)}</dt><dd>${fmt(mine.length)}</dd>
      <dt>${esc(P.area)}</dt><dd>${fmt(mine.reduce((a, f) => a + f.ha, 0))} ${ui.map.ha}</dd>
      ${split ? `<dt>${esc(P.grain)} / ${esc(P.oil)}</dt><dd>${fmt(grain)} / ${fmt(ha('oilseed'))} ${ui.map.ha}</dd>` : ''}
      <dt>${esc(P.cut)}</dt><dd>${grain ? Math.round(cut / grain * 100) : 0}% ${esc(ui.legend.short.grain).toLowerCase()}</dd></dl>
      <p class="hint">${esc(P.on_map)}</p></div>`;
  }

  const TOP = 25;
  const dash = '<span style="color:var(--muted)">—</span>';
  let showAll = false;
  const openGroups = new Set();
  function accRow(a, T, hasAvg, cls, hasReg) {
    const also = (a.also || []).length ? `<span class="also">${esc(T.same_farm.replace('{names}', a.also.join(', ')))}</span>` : '';
    let label;
    if (a.group_row) {
      const open = openGroups.has(a.name);
      label = `<button type="button" class="grp" aria-expanded="${open}" data-g="${esc(a.name)}"><span class="tw" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg></span><span class="gname">${esc(a.name)}</span></button>`
        + `<span class="chip">${esc(T.members.replace('{n}', fmt(a.members_n)))}</span>`;
    } else {
      label = `<button type="button" class="cname" data-n="${esc(a.name)}">${esc(a.name)}</button>`;
    }
    return `<tr class="${cls}">
      <td>${label}${also}</td>
      <td class="n">${fmt(a.ha)}</td><td class="n">${fmt(a.grain)}</td><td class="n">${fmt(a.oil)}</td>
      ${hasAvg ? `<td class="n">${n1(a.avg)}</td>` : ''}${hasReg ? `<td class="n">${a.tax != null ? fmt(Math.round(a.tax)) : dash}</td><td class="n">${a.rev != null ? n1(a.rev) : dash}</td><td class="n">${a.staff ? esc(a.staff) : dash}</td>` : ''}
      <td>${a.whole ? `${esc(a.whole)}${a.url ? `<a class="src" href="${esc(a.url)}" rel="noopener">${esc(a.src || a.url)}</a>` : ''}` : '<span style="color:var(--muted)">—</span>'}</td></tr>`;
  }
  function table() {
    const T = ui.table, hasAvg = D.accounts.some(a => a.avg != null), hasReg = D.accounts.some(a => a.tax != null);
    const q = ($('acc-search').value || '').trim().toLowerCase();
    let rows = D.accounts.filter(a => a.ha >= 150);
    if (q) {   // a search lists the matching companies, members of a group as their own rows
      rows = rows.flatMap(a => a.group_row ? (a.name.toLowerCase().includes(q) ? [a] : a.members.filter(m => m.name.toLowerCase().includes(q)))
        : (a.name.toLowerCase().includes(q) ? [a] : []));
    }
    const shown = showAll || q ? rows : rows.slice(0, TOP);
    $('acc').querySelector('thead tr').innerHTML = `<th>${esc(T.company)}</th><th class="n">${esc(T.fields_ha)}</th><th class="n">${esc(T.grain)}</th><th class="n">${esc(T.oilseed)}</th>`
      + (hasAvg ? `<th class="n">${esc(T.avg)}</th>` : '')
      + (hasReg ? `<th class="n">${esc(T.tax)}</th><th class="n">${esc(T.rev)}</th><th class="n">${esc(T.staff)}</th>` : '') + `<th>${esc(T.whole)}</th>`;
    $('acc').querySelector('tbody').innerHTML = shown.map(a => {
      let html = accRow(a, T, hasAvg, a.group_row ? 'grouprow' : '', hasReg);
      if (a.group_row && openGroups.has(a.name)) html += a.members.map(m => accRow(m, T, hasAvg, 'member', hasReg)).join('');
      return html;
    }).join('') || `<tr><td colspan="9" class="hint">${esc(T.none)}</td></tr>`;
    for (const btn of $('acc').querySelectorAll('button.cname')) {
      btn.addEventListener('click', () => {
        showCompany([btn.dataset.n], btn.dataset.n);
        document.getElementById('mapbox').scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
    }
    for (const btn of $('acc').querySelectorAll('button.grp')) {
      btn.addEventListener('click', () => {
        const g = btn.dataset.g;
        if (openGroups.has(g)) openGroups.delete(g); else openGroups.add(g);
        table();
        const again = [...$('acc').querySelectorAll('button.grp')].find(x => x.dataset.g === g);
        if (again) again.focus();
      });
    }
    const anyAlso = D.accounts.some(a => (a.also || []).length || (a.members || []).some(m => (m.also || []).length));
    $('pair-note').hidden = !anyAlso;
    $('pair-note').textContent = T.pair_note;
    const more = $('acc-more');
    more.hidden = !!q || rows.length <= TOP;
    more.textContent = showAll ? T.show_fewer : T.show_all.replace('{n}', fmt(rows.length));
    more.setAttribute('aria-expanded', String(showAll));
  }

  // the panes over the map (wide screens only): the district is fitted beside them
  function setInsets() {
    const box = $('mapbox').getBoundingClientRect();
    const l = document.querySelector('.ov-left'), r = document.querySelector('.ov-right');
    const over = getComputedStyle(l).position === 'absolute';
    FieldMap.setInsets(over ? l.getBoundingClientRect().right - box.left + 8 : 0,
                       over ? box.right - r.getBoundingClientRect().left + 8 : 0);
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
      setInsets();
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
      const dd = b.dataset.d || b.dataset.dm;
      if (b.dataset.dm) $('dpick').open = false;
      if (b.dataset.d) $('mpick').open = false;
      if (dd && dd !== S.district) { S.district = dd; fixYear(); showAll = false; render(); }
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
    $('acc-search').addEventListener('input', () => table());
    $('z-in').addEventListener('click', () => FieldMap.zoom(1.5));
    $('z-out').addEventListener('click', () => FieldMap.zoom(1 / 1.5));
    $('z-reset').addEventListener('click', () => { setInsets(); FieldMap.reset(); });
    $('z-full').addEventListener('click', () => {
      const box = $('mapbox');
      if (document.fullscreenElement) document.exitFullscreen();
      else if (box.requestFullscreen) box.requestFullscreen().catch(() => {});
    });
    // a picker closes on Escape or a click elsewhere
    const pickers = ['dpick', 'mpick'];
    document.addEventListener('keydown', e => { if (e.key === 'Escape') pickers.forEach(id => { $(id).open = false; }); });
    document.addEventListener('click', e => pickers.forEach(id => { if ($(id).open && !$(id).contains(e.target)) $(id).open = false; }));
    window.addEventListener('hashchange', () => { const l = S.lang, d = S.district, y = S.year; readHash(); if (l !== S.lang || d !== S.district || y !== S.year) render(); });
    await render();
  }

  start().catch(err => { $('page').setAttribute('aria-busy', 'false'); $('lede').textContent = 'Could not load the atlas: ' + err.message; });
})();
