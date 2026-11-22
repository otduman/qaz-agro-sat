/* Field Atlas charts: hand-drawn SVG in the page's colour tokens, drawn at the card's real width so text stays 12px.
   Each mark carries a <title> for hover. */
const Charts = (() => {
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const NARROW = 560;
  let last = null, lastW = 0;

  const legendHTML = items => items.map(([col, lab, sq]) => `<span><i class="${sq ? 'sq' : ''}" style="background:${col}"></i>${esc(lab)}</span>`).join('');
  const shell = (id, title, hint, wide) => `<div class="chart${wide ? ' wide' : ''}"><h3>${esc(title)}</h3><p class="hint">${esc(hint)}</p><svg id="ch-${id}" role="img" aria-label="${esc(title)}"></svg><div class="legend" id="lg-${id}"></div></div>`;
  const widthOf = el => Math.max(260, Math.floor(el.getBoundingClientRect().width));
  const put = (svg, W, H, body) => { svg.setAttribute('viewBox', `0 0 ${W} ${H}`); svg.style.height = H + 'px'; svg.innerHTML = body; };

  // Horizontal bars: label to the left when there is room, above the bar when narrow.
  function hbars(svg, rows, valueText) {
    const W = widthOf(svg), narrow = W < NARROW;
    const L = narrow ? 0 : Math.min(260, Math.round(W * 0.34)), R = narrow ? 92 : 110;
    const rowH = narrow ? 40 : 26, barH = narrow ? 12 : 14;
    const H = rows.length * rowH + 4, max = Math.max(...rows.map(r => r.v)) || 1;
    let h = '';
    rows.forEach((r, i) => {
      const y = 2 + i * rowH, w = Math.max(2, r.v / max * (W - L - R));
      const by = narrow ? y + 20 : y + (rowH - barH) / 2, ty = by + barH / 2 + 4;
      h += `<g><title>${esc(r.title)}</title>`
        + (narrow ? `<text class="lab" x="0" y="${y + 13}">${esc(r.label)}</text>`
                  : `<text class="lab" x="${L - 10}" y="${ty}" text-anchor="end">${esc(r.label)}</text>`)
        + `<rect x="${L}" y="${by}" width="${w.toFixed(1)}" height="${barH}" rx="3" fill="${r.col}"/>`
        + `<text class="val" x="${(L + w + 6).toFixed(1)}" y="${ty}">${esc(valueText(r))}</text></g>`;
    });
    put(svg, W, H, h);
  }

  // Largest companies by field area, grain-group companies in the group colour.
  function companies(D, ui, F) {
    const svg = document.getElementById('ch-companies');
    const W = widthOf(svg), max = W < NARROW ? 40 : 34;
    const flat = D.accounts.flatMap(a => a.group_row ? a.members : [a]).sort((x, y) => y.ha - x.ha);  // companies one by one
    const rows = flat.slice(0, 15).map(a => ({
      v: a.ha, col: a.group ? 'var(--group)' : 'var(--muted)',
      label: a.name.length > max ? a.name.slice(0, max - 1) + '…' : a.name,
      title: `${a.name}: ${F.int(a.ha)} ${ui.map.ha}`
    }));
    hbars(svg, rows, r => F.one(r.v / 1000));
    document.getElementById('lg-companies').innerHTML = D.accounts.some(a => a.group)
      ? legendHTML([['var(--group)', ui.charts.group, 1], ['var(--muted)', ui.charts.others, 1]]) : '';
  }

  // Average field size by kind of land user.
  function sizes(D, ui, F) {
    const order = [['group', 'var(--group)', ui.charts.size_group], ['company', 'var(--company)', ui.charts.size_company],
      ['farm', 'var(--farm)', ui.charts.size_farm], ['unread', 'var(--nochange)', ui.charts.size_unread]];
    const rows = order.filter(([k]) => D.sizes[k] && D.sizes[k].fields).map(([k, col, lab]) => {
      const n = D.sizes[k].fields, avg = D.sizes[k].ha / n;
      return { v: avg, n, col, label: lab, title: `${lab}: ${F.int(Math.round(avg))} ${ui.map.ha}, ${F.int(n)} ${ui.charts.fields_n}` };
    });
    hbars(document.getElementById('ch-sizes'), rows, r => `${F.int(Math.round(r.v))} ${ui.map.ha} · ${F.int(r.n)}`);
  }

  // Land inside the district by kind: crops, fallow and grass (hay and pasture together), thousand ha.
  // A season whose crop split is unreliable (content crop_split: false) shows cereals and oilseeds as one bar.
  function land(D, C, ui, F) {
    const S = ui.legend.short, split = C.crop_split !== false;
    const l = split ? D.land_inside : { ...D.land_inside, crops: D.land_inside.grain + D.land_inside.oilseed };
    const crops = split ? [['grain', 'var(--grain)', S.grain], ['oilseed', 'var(--oil)', S.oilseed]]
      : [['crops', 'var(--grain)', S.crops]];
    const rows = [...crops, ['fallow', 'var(--fallow-line)', S.fallow], ['grass', 'var(--grass)', ui.charts.grass_long]]
      .filter(([k]) => l[k] > 0)
      .map(([k, col, lab]) => ({ v: l[k], col, label: lab, title: `${lab}: ${F.int(Math.round(l[k] / 1000))} ${ui.map.thousand_ha}` }));
    hbars(document.getElementById('ch-land'), rows, r => F.int(Math.round(r.v / 1000)));
  }

  // Crop mix: our shares against the official ones, two stacked bars.
  function crops(D, C, ui) {
    const svg = document.getElementById('ch-crops'), W = widthOf(svg);
    const c = D.crop_ha_inside || D.crop_ha, tot = (c.grain + c.oilseed + c.other) || 1;
    const ours = { grain: c.grain / tot, oilseed: c.oilseed / tot, other: c.other / tot };
    const keys = [['grain', 'var(--grain)'], ['oilseed', 'var(--oil)'], ['other', 'var(--other)']];
    // the third part is sown fodder (the official figure), not the grass layer of hay and pasture
    const name = k => k === 'other' ? ui.charts.fodder : ui.legend[k];
    const L = 100, R = 4, rowH = 46, H = 2 * rowH + 4;
    let h = '';
    [[ui.charts.ours, ours], [ui.charts.official, C.official.crop_share]].forEach(([lab, s], i) => {
      const y = 4 + i * rowH; let x = L;
      h += `<text class="lab" x="${L - 10}" y="${y + 23}" text-anchor="end">${esc(lab)}</text>`;
      keys.forEach(([k, col]) => {
        const w = s[k] * (W - L - R);
        if (w <= 0) return;
        const pct = Math.round(s[k] * 100);
        h += `<g><title>${esc(lab)} · ${esc(name(k))}: ${pct}%</title><rect x="${x.toFixed(1)}" y="${y + 6}" width="${Math.max(1, w - 2).toFixed(1)}" height="28" rx="3" fill="${col}"/>`
          + (w > 44 ? `<text x="${(x + 8).toFixed(1)}" y="${y + 25}" style="font:500 12px var(--font-data);fill:#fff">${pct}%</text>` : '') + '</g>';
        x += w;
      });
    });
    put(svg, W, H, h);
    document.getElementById('lg-crops').innerHTML = legendHTML(keys.map(([k, col]) => [col, name(k), 1]));
  }

  // Harvest pace: all fields, the grain group and the rest, plus the official point.
  function pace(D, C, ui) {
    const svg = document.getElementById('pace'), W = widthOf(svg), narrow = W < NARROW;
    const H = narrow ? 260 : 320, L = 44, R = narrow ? 12 : 170, T = 16, B = 30;
    const yr = D.year || '2026';
    const t0 = new Date(yr + '-07-25T00:00:00'), t1 = new Date(yr + '-10-31T00:00:00');
    const X = s => L + (new Date(s + 'T00:00:00') - t0) / (t1 - t0) * (W - L - R);
    const Y = v => T + (1 - v) * (H - T - B);
    let h = '';
    [0, 0.25, 0.5, 0.75, 1].forEach(v => { h += `<line class="grid" x1="${L}" x2="${W - R}" y1="${Y(v)}" y2="${Y(v)}"/><text class="ax" x="${L - 8}" y="${Y(v) + 4}" text-anchor="end">${v * 100}%</text>`; });
    [[yr + '-08-01', 7], [yr + '-09-01', 8], [yr + '-10-01', 9]].forEach(([d, m]) => { h += `<line class="grid" x1="${X(d)}" x2="${X(d)}" y1="${T}" y2="${H - B}"/><text class="ax" x="${X(d) + 4}" y="${H - B + 18}">${ui.months[m]}</text>`; });
    const rain = C.official && C.official.rain;  // [from, to] of a rain spell that stalled the harvest, if the text gives one
    if (rain) {
      h += `<rect x="${X(rain[0])}" y="${T}" width="${X(rain[1]) - X(rain[0])}" height="${H - T - B}" fill="var(--chip)"/><text class="ax" x="${(X(rain[0]) + X(rain[1])) / 2}" y="${T + 14}" text-anchor="middle">${esc(ui.charts.rain)}</text>`;
    }
    // Daily district rain (ERA5-Land mean over the district, owner 2026-11-21) as bars rising from the bottom axis,
    // capped at RAIN_MM a day (the tooltip keeps the real figure).
    const days = (D.rain || []).filter(([d, mm]) => d >= yr + '-07-25' && d <= yr + '-10-31' && mm >= 0.5);
    if (days.length) {
      const RAIN_MM = 20, top = (H - T - B) * 0.22, step = X(yr + '-07-26') - X(yr + '-07-25');
      days.forEach(([d, mm]) => {
        const hh = Math.max(1.5, Math.min(mm, RAIN_MM) / RAIN_MM * top), [, m, dd] = d.split('-').map(Number);
        h += `<rect class="rain" x="${(X(d) + 0.5).toFixed(1)}" y="${(Y(0) - hh).toFixed(1)}" width="${Math.max(1, step - 1).toFixed(1)}" height="${hh.toFixed(1)}" fill="var(--rain)"><title>${dd} ${ui.months[m - 1]}: ${mm} ${esc(ui.charts.rain_mm)}</title></rect>`;
      });
    }
    const hasGroup = D.sizes.group && D.sizes.group.fields > 0;
    const series = hasGroup
      ? [['all', 'var(--ink)', ui.charts.pace_all, 2.5], ['group', 'var(--group)', ui.charts.pace_group, 2], ['other', 'var(--company)', ui.charts.pace_other, 2]]
      : [['all', 'var(--grain)', ui.charts.pace_all, 2.5]];
    const ends = [];
    series.forEach(([k, col, lab, sw]) => {
      const s = D.pace[k], end = s[s.length - 1];
      h += `<path d="${s.map(([d, v], i) => (i ? 'L' : 'M') + X(d).toFixed(1) + ' ' + Y(v).toFixed(1)).join('')}" fill="none" stroke="${col}" stroke-width="${sw}" stroke-linejoin="round"><title>${esc(lab)} ${Math.round(end[1] * 100)}%</title></path>`;
      ends.push({ y: Y(end[1]), x: X(end[0]), col, lab, pct: Math.round(end[1] * 100) });
    });
    // Grain area with no cut date found: the gap between the 'all' line's end and 100%, as a bar at the right end.
    const allEnd = ends[0], unknown = 1 - D.pace.all[D.pace.all.length - 1][1];
    if (unknown > 0.005) {
      h += `<rect x="${allEnd.x - 7}" y="${Y(1)}" width="7" height="${allEnd.y - Y(1)}" fill="url(#pace-unknown)" stroke="var(--muted)" stroke-width="1"><title>${esc(ui.charts.pace_unknown)} ${Math.round(unknown * 100)}%</title></rect>`;
    }
    if (!narrow && series.length > 1) {
      // Direct labels at the line ends, nudged apart so they do not collide.
      const lab = ends.slice().sort((a, b) => a.y - b.y);
      for (let i = 1; i < lab.length; i++) if (lab[i].y - lab[i - 1].y < 16) lab[i].y = lab[i - 1].y + 16;
      lab.forEach(e => { h += `<text class="lab" x="${e.x + 8}" y="${e.y + 4}">${esc(e.lab)} ${e.pct}%</text>`; });
    }
    if (C.official && C.official.pace_point) {
      const p = C.official.pace_point, [, m, d] = p.date.split('-').map(Number);
      const text = `${ui.charts.pace_official} ${Math.round(p.share * 100)}%, ${d} ${ui.months[m - 1]}`;
      h += `<circle cx="${X(p.date)}" cy="${Y(p.share)}" r="5" fill="var(--ink)" stroke="var(--surface)" stroke-width="2"><title>${esc(text)}</title></circle>`
        + `<text class="lab" x="${X(p.date) + 10}" y="${Y(p.share) - 8}">${esc(text)}</text>`;
    }
    h = `<defs><pattern id="pace-unknown" patternUnits="userSpaceOnUse" width="5" height="5" patternTransform="rotate(45)"><rect width="5" height="5" fill="var(--surface)"/><line x1="0" y1="0" x2="0" y2="5" stroke="var(--muted)" stroke-width="2"/></pattern></defs>` + h;
    put(svg, W, H, h);
    svg.setAttribute('aria-label', ui.charts.pace_aria);
    const items = series.length > 1 || narrow ? ends.map(e => [e.col, `${e.lab} ${e.pct}%`]) : [];
    if (unknown > 0.005) items.push(['repeating-linear-gradient(45deg, var(--muted) 0 2px, transparent 2px 5px)', `${ui.charts.pace_unknown} ${Math.round(unknown * 100)}%`, 1]);
    if (days.length) items.push(['var(--rain)', ui.charts.rain_daily, 1]);
    document.getElementById('pace-legend').innerHTML = legendHTML(items);
  }

  function draw() {
    const { D, C, ui, F } = last;
    if (D.accounts.length) companies(D, ui, F);
    if (C.official) crops(D, C, ui);
    if (D.land_inside) land(D, C, ui, F);
    sizes(D, ui, F);
    pace(D, C, ui);
  }

  function render(D, C, ui, F) {
    last = { D, C, ui, F };
    document.getElementById('charts').innerHTML =
      (D.accounts.length ? shell('companies', ui.charts.companies_title, D.accounts.some(a => a.group) ? ui.charts.companies_note : ui.charts.companies_note_plain, true) : '')
      + (C.official ? shell('crops', ui.charts.crops_title, ui.charts.crops_note + ' ' + C.official.label, false) : '')
      + (D.land_inside ? shell('land', ui.charts.land_title, ui.charts.land_note, false) : '')
      + shell('sizes', ui.charts.sizes_title, ui.charts.sizes_note, !!C.official === !D.land_inside ? false : true);
    draw();
    lastW = document.getElementById('charts').clientWidth;
  }

  let timer = null;
  window.addEventListener('resize', () => {
    clearTimeout(timer);
    timer = setTimeout(() => { const w = document.getElementById('charts').clientWidth; if (last && w !== lastW) { lastW = w; draw(); } }, 150);
  });

  return { render };
})();
