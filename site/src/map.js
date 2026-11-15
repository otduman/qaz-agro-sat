/* Field Atlas map: fields on a flat kilometre grid (SVG viewBox), a Sentinel-2 picture underneath, pan and zoom. */
const FieldMap = (() => {
  const NS = 'http://www.w3.org/2000/svg';
  const DETAIL_BELOW_KM = 40;
  let svg, scaleEl, onPick, D = null, ui = null, vb = null, ext = null;
  let els = [], gImg, gTiles, gF, townDot, townLabel, loaded = new Set();
  let state = { mode: 'crop', sat: true, sel: null };

  const mk = (tag, attrs) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; };
  const pathOf = p => { let d = 'M' + p[0] + ' ' + p[1]; for (let i = 2; i < p.length; i += 2) d += 'L' + p[i] + ' ' + p[i + 1]; return d + 'Z'; };
  const ratio = () => { const r = svg.getBoundingClientRect(); return r.width && r.height ? r.width / r.height : 1.6; };

  function timeClass(f) {
    if (!f.m) return 't-0';
    const y = (D && D.year) || f.m.slice(0, 4);
    if (f.m <= y + '-08-10') return 't-1';
    if (f.m <= y + '-08-20') return 't-2';
    if (f.m <= y + '-08-31') return 't-3';
    if (f.m <= y + '-09-10') return 't-4';
    return 't-5';
  }
  const classOf = (f, mode) => mode === 'crop' ? 'c-' + f.c : timeClass(f);

  function fit() {
    const pad = 2, w = ext.x1 - ext.x0 + 2 * pad, h = ext.y1 - ext.y0 + 2 * pad, x = ext.x0 - pad, y = ext.y0 - pad, want = ratio();
    if (w / h > want) { const nh = w / want; return { x, y: y - (nh - h) / 2, w, h: nh }; }
    const nw = h * want; return { x: x - (nw - w) / 2, y, w: nw, h };
  }

  function loadTiles() {
    if (!D || !D.imagery || !state.sat || vb.w > DETAIL_BELOW_KM) return;
    D.imagery.tiles.forEach(t => {
      if (loaded.has(t.id)) return;
      if (t.x > vb.x + vb.w || t.x + t.w < vb.x || t.y > vb.y + vb.h || t.y + t.h < vb.y) return;
      loaded.add(t.id);
      gTiles.appendChild(mk('image', { href: t.href, x: t.x, y: t.y, width: t.w, height: t.h, preserveAspectRatio: 'none' }));
    });
  }

  function setVB() {
    svg.setAttribute('viewBox', `${vb.x} ${vb.y} ${vb.w} ${vb.h}`);
    const r = svg.getBoundingClientRect(); const kmPerPx = vb.w / (r.width || 1);
    if (townLabel) {
      townLabel.setAttribute('font-size', 13 * kmPerPx);
      townLabel.setAttribute('x', D.town[0] + 8 * kmPerPx); townLabel.setAttribute('y', D.town[1] + 4 * kmPerPx);
      townDot.setAttribute('r', 4 * kmPerPx);
    }
    const nice = [1, 2, 5, 10, 20, 50]; const km = nice.find(n => n / kmPerPx >= 60) || 50;
    scaleEl.innerHTML = `<span style="display:inline-block;width:${Math.round(km / kmPerPx)}px;border-top:2px solid currentColor;vertical-align:middle;margin-right:6px"></span>${km} ${ui ? ui.map.km : 'km'}`;
    loadTiles();
  }

  function paint() {
    els.forEach((e, i) => e.setAttribute('class', 'f ' + classOf(D.fields[i], state.mode) + (state.sel === i ? ' on' : '')));
    svg.parentNode.classList.toggle('sat', state.sat && !!D.imagery);
    gImg.style.display = state.sat ? '' : 'none';
    loadTiles();
  }

  function load(data, strings, townName) {
    D = data; ui = strings; state.sel = null; loaded = new Set();
    svg.innerHTML = '';
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    D.fields.forEach(f => { for (let i = 0; i < f.p.length; i += 2) { x0 = Math.min(x0, f.p[i]); x1 = Math.max(x1, f.p[i]); y0 = Math.min(y0, f.p[i + 1]); y1 = Math.max(y1, f.p[i + 1]); } });
    ext = { x0, y0, x1, y1 };
    const gSurvey = mk('g', {});
    D.squares.forEach(q => gSurvey.appendChild(mk('path', { d: pathOf(q.p), class: 'survey' })));
    gImg = mk('g', {});
    if (D.imagery) {
      const o = D.imagery.overview;
      gImg.appendChild(mk('image', { href: o.href, x: o.x, y: o.y, width: o.w, height: o.h, preserveAspectRatio: 'none' }));
    }
    gTiles = mk('g', {}); gImg.appendChild(gTiles);
    const gFallow = mk('g', {});
    (D.fallow || []).forEach(q => gFallow.appendChild(mk('path', { d: pathOf(q.p), class: 'fallowpatch' })));
    const gOut = mk('g', {});
    D.outline.forEach(r => gOut.appendChild(mk('path', { d: pathOf(r), class: 'district' })));
    gF = mk('g', {});
    els = D.fields.map((f, i) => { const e = mk('path', { d: pathOf(f.p) }); e.dataset.i = i; gF.appendChild(e); return e; });
    const gTown = mk('g', {});
    townDot = mk('circle', { class: 'townmark', cx: D.town[0], cy: D.town[1], r: 0.6 });
    townLabel = mk('text', { class: 'maplabel', x: D.town[0] + 1.2, y: D.town[1] + 0.5 });
    townLabel.textContent = townName;
    gTown.append(townDot, townLabel);
    const gPlots = mk('g', {});
    (D.plots || []).forEach(q => { const e = mk('path', { d: pathOf(q.p), class: 'plotline' }); const tt = mk('title', {}); tt.textContent = q.name; e.appendChild(tt); gPlots.appendChild(e); });
    // fallow is hatched, so it never reads as a crop colour
    const defs = mk('defs', {});
    const hatch = mk('pattern', { id: 'fallow-hatch', patternUnits: 'userSpaceOnUse', width: 0.3, height: 0.3, patternTransform: 'rotate(45)' });
    hatch.append(mk('rect', { width: 0.3, height: 0.3, class: 'hatch-bg' }), mk('rect', { width: 0.11, height: 0.3, class: 'hatch-line' }));
    defs.appendChild(hatch);
    svg.append(defs, gSurvey, gImg, gFallow, gOut, gF, gPlots, gTown);
    vb = fit(); setVB(); paint();
  }

  // Pan and zoom on the viewBox.
  const pts = new Map(); let moved = 0, last = null, pinch = null;
  function toMap(cx, cy) { const r = svg.getBoundingClientRect(); return { x: vb.x + (cx - r.left) / r.width * vb.w, y: vb.y + (cy - r.top) / r.height * vb.h }; }
  function zoomAt(k, cx, cy) {
    if (!vb) return;
    const nw = Math.min((ext.x1 - ext.x0) * 2.5, Math.max(1.5, vb.w / k)); const kk = vb.w / nw;
    const p = cx == null ? { x: vb.x + vb.w / 2, y: vb.y + vb.h / 2 } : toMap(cx, cy);
    vb = { x: p.x - (p.x - vb.x) / kk, y: p.y - (p.y - vb.y) / kk, w: nw, h: nw / ratio() }; setVB();
  }

  function init(svgEl, scaleElement, pick) {
    svg = svgEl; scaleEl = scaleElement; onPick = pick;
    svg.addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.deltaY < 0 ? 1.25 : 0.8, e.clientX, e.clientY); }, { passive: false });
    svg.addEventListener('pointerdown', e => { svg.setPointerCapture(e.pointerId); pts.set(e.pointerId, { x: e.clientX, y: e.clientY }); moved = 0; last = { x: e.clientX, y: e.clientY }; if (pts.size === 2) { const [a, b] = [...pts.values()]; pinch = Math.hypot(a.x - b.x, a.y - b.y); } });
    svg.addEventListener('pointermove', e => {
      if (!pts.has(e.pointerId) || !vb) return;
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (pts.size === 2 && pinch) { const [a, b] = [...pts.values()]; const d = Math.hypot(a.x - b.x, a.y - b.y); zoomAt(d / pinch, (a.x + b.x) / 2, (a.y + b.y) / 2); pinch = d; moved += 10; return; }
      const r = svg.getBoundingClientRect(); const dx = e.clientX - last.x, dy = e.clientY - last.y; moved += Math.abs(dx) + Math.abs(dy);
      if (moved > 4) svg.classList.add('drag');
      vb.x -= dx / r.width * vb.w; vb.y -= dy / r.height * vb.h; last = { x: e.clientX, y: e.clientY }; setVB();
    });
    function up(e) {
      if (pts.size === 1 && moved <= 4 && e.type === 'pointerup') {
        const t = document.elementFromPoint(e.clientX, e.clientY);
        if (t && t.dataset && t.dataset.i != null) { state.sel = +t.dataset.i; paint(); onPick(D.fields[state.sel]); }
      }
      pts.delete(e.pointerId); if (pts.size < 2) pinch = null; if (!pts.size) svg.classList.remove('drag');
    }
    svg.addEventListener('pointerup', up); svg.addEventListener('pointercancel', up);
    window.addEventListener('resize', () => { if (vb) { vb.h = vb.w / ratio(); setVB(); } });
  }

  return {
    init, load, classOf,
    zoom: k => zoomAt(k),
    reset: () => { if (ext) { vb = fit(); setVB(); } },
    setMode: m => { state.mode = m; if (D) paint(); },
    setSat: on => { state.sat = on; if (D) paint(); },
    get mode() { return state.mode; },
    get sat() { return state.sat; }
  };
})();
