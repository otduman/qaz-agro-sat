/* Field Atlas map: fields on a flat kilometre grid (SVG viewBox), a Sentinel-2 picture underneath, pan and zoom. */
const FieldMap = (() => {
  const NS = 'http://www.w3.org/2000/svg';
  const DETAIL_BELOW_KM = 70;
  let svg, scaleEl, onPick, D = null, ui = null, vb = null, ext = null;
  let els = [], gImg, gTiles, gF, townDot, townLabel, loaded = new Set();
  let state = { mode: 'crop', sat: true, sel: null };

  const mk = (tag, attrs) => { const e = document.createElementNS(NS, tag); for (const k in attrs) e.setAttribute(k, attrs[k]); return e; };
  const pathOf = p => { let d = 'M' + p[0] + ' ' + p[1]; for (let i = 2; i < p.length; i += 2) d += 'L' + p[i] + ' ' + p[i + 1]; return d + 'Z'; };
  const ratio = () => { const w = svg.clientWidth, h = svg.clientHeight; return w && h ? w / h : 1.6; };

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

  // Insets: screen pixels on the left and right covered by panes laid over the map; the district is fitted into
  // the free middle so no field starts hidden under a pane.
  let insets = { left: 0, right: 0 };
  function fit() {
    const pad = 2, w = ext.x1 - ext.x0 + 2 * pad, h = ext.y1 - ext.y0 + 2 * pad, x = ext.x0 - pad, y = ext.y0 - pad;
    const W = svg.clientWidth || 1, H = svg.clientHeight || 1;
    // cover, not contain: the district fills the whole map box and its far edges are panned to (owner 2026-11-17);
    // the view is centred on the free middle between the panes
    const want = W / H;
    let fw = w, fh = h, fx = x, fy = y;
    if (w / h > want) { fw = h * want; fx = x + (w - fw) / 2; } else { fh = w / want; fy = y + (h - fh) / 2; }
    const shift = (insets.left - insets.right) / 2 / W * fw;   // the panes' middle, in map km
    return { x: fx - shift, y: fy, w: fw, h: fh };
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
    if (frame) { cancelAnimationFrame(frame); frame = 0; }
    svg.style.transform = ''; svg.style.willChange = '';
    svg.setAttribute('viewBox', `${vb.x} ${vb.y} ${vb.w} ${vb.h}`);
    shown = { ...vb };
    const kmPerPx = vb.w / (svg.clientWidth || 1);
    if (townLabel) {
      townLabel.setAttribute('font-size', 13 * kmPerPx);
      townLabel.setAttribute('x', D.town[0] + 8 * kmPerPx); townLabel.setAttribute('y', D.town[1] + 4 * kmPerPx);
      townDot.setAttribute('r', 4 * kmPerPx);
    }
    scaleBar();
    loadTiles();
  }

  function paint() {
    els.forEach((e, i) => e.setAttribute('class', 'f ' + classOf(D.fields[i], state.mode) + (state.sel === i ? ' on' : '')
      + (state.hl && state.hl(D.fields[i]) ? ' hl' : '')));
    svg.parentNode.classList.toggle('hlmode', !!state.hl);
    svg.parentNode.classList.toggle('sat', state.sat && !!D.imagery);
    gImg.style.display = state.sat ? '' : 'none';
    loadTiles();
  }

  function load(data, strings, townName) {
    D = data; ui = strings; state.sel = null; state.hl = null; loaded = new Set();
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
    // grass: hay and pasture (owner 2026-11-16), a soft wash under everything else
    // 2026-11-19: one see-through group per use (pasture / old field / steppe; 'all' when no use is listed), no
    // outline of its own, so touching pieces read as one area
    const gGrass = mk('g', {});
    const useG = {};
    (D.grass || []).forEach(q => {
      const u = q.u || 'all';
      if (!useG[u]) { useG[u] = mk('g', { class: `grassuse u-${u}` }); gGrass.appendChild(useG[u]); }
      useG[u].appendChild(mk('path', { d: pathOf(q.p), class: 'grasspatch' }));
    });
    const gFallow = mk('g', {});
    (D.fallow || []).forEach(q => gFallow.appendChild(mk('path', { d: pathOf(q.p), class: 'fallowpatch' })));
    const gOut = mk('g', {});
    D.outline.forEach(r => gOut.appendChild(mk('path', { d: pathOf(r), class: 'district' })));
    gF = mk('g', {});
    // a dark edge under each field's coloured outline, so fields stand out on a bright photo (owner 2026-11-16)
    const gEdge = mk('g', {});
    els = D.fields.map((f, i) => {
      const d = pathOf(f.p);
      gEdge.appendChild(mk('path', { d, class: 'edge' }));
      const e = mk('path', { d }); e.dataset.i = i; gF.appendChild(e); return e;
    });
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
    svg.append(defs, gSurvey, gImg, gGrass, gFallow, gOut, gEdge, gF, gPlots, gTown);
    vb = fit(); setVB(); paint();
  }

  // Pan and zoom (owner 2026-11-22: "very laggy", "hard to scroll past the map on phones"). While a gesture runs
  // the drawn map is only moved and scaled by the browser (a CSS transform of the SVG box, no re-draw); the viewBox,
  // which re-draws every field, is set once when the gesture ends. One finger and the plain wheel scroll the page
  // (a hint says how to move the map); two fingers, a mouse drag, Ctrl/Cmd + wheel (a trackpad pinch) and the
  // buttons move it. In full screen the plain wheel zooms.
  const pts = new Map(); let moved = 0, last = null, rect = null, frame = 0, settle = 0, busy = false;
  let shown = null, two = null, one = null, hintEl = null, hintTimer = 0;
  function measure() {
    const b = svg.parentNode.getBoundingClientRect();
    return { left: b.left, top: b.top, width: svg.clientWidth || 1, height: svg.clientHeight || 1 };
  }
  function toMap(cx, cy) { const r = rect || measure(); return { x: vb.x + (cx - r.left) / r.width * vb.w, y: vb.y + (cy - r.top) / r.height * vb.h }; }
  function scaleBar() {
    const kmPerPx = vb.w / (svg.clientWidth || 1);
    const nice = [1, 2, 5, 10, 20, 50]; const km = nice.find(n => n / kmPerPx >= 60) || 50;
    scaleEl.innerHTML = `<span style="display:inline-block;width:${Math.round(km / kmPerPx)}px;border-top:2px solid currentColor;vertical-align:middle;margin-right:6px"></span>${km} ${ui ? ui.map.km : 'km'}`;
  }
  function draw() {
    if (frame) return;
    frame = requestAnimationFrame(() => {
      frame = 0;
      if (!shown || !vb) return;
      const W = svg.clientWidth || 1, H = svg.clientHeight || 1, s = shown.w / vb.w;
      const tx = (shown.x - vb.x) / vb.w * W, ty = (shown.y - vb.y) / vb.h * H;
      svg.style.transform = `translate(${tx}px, ${ty}px) scale(${s})`;
      scaleBar();
    });
  }
  function begin() { if (!busy) { busy = true; rect = measure(); svg.style.willChange = 'transform'; } }
  function end() { busy = false; rect = null; setVB(); }
  function zoomAt(k, cx, cy, live) {
    if (!vb) return;
    const nw = Math.min((ext.x1 - ext.x0) * 2.5, Math.max(1.5, vb.w / k)); const kk = vb.w / nw;
    const p = cx == null ? { x: vb.x + vb.w / 2, y: vb.y + vb.h / 2 } : toMap(cx, cy);
    vb = { x: p.x - (p.x - vb.x) / kk, y: p.y - (p.y - vb.y) / kk, w: nw, h: nw / ratio() };
    if (live) draw(); else setVB();
  }
  function hint(kind) {
    if (!hintEl || !ui) return;
    const mac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
    hintEl.textContent = kind === 'touch' ? ui.map.two_fingers : ui.map.ctrl_zoom.replace('{key}', mac ? '⌘' : 'Ctrl');
    hintEl.classList.add('show');
    clearTimeout(hintTimer); hintTimer = setTimeout(() => hintEl.classList.remove('show'), 1600);
  }
  const mid = ts => { const a = ts[0], b = ts[1]; return { x: (a.clientX + b.clientX) / 2, y: (a.clientY + b.clientY) / 2, d: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY) || 1 }; };

  function init(svgEl, scaleElement, pick) {
    svg = svgEl; scaleEl = scaleElement; onPick = pick;
    hintEl = document.createElement('div'); hintEl.className = 'maphint'; hintEl.setAttribute('role', 'status');
    svg.parentNode.appendChild(hintEl);
    svg.addEventListener('wheel', e => {
      const full = document.fullscreenElement && document.fullscreenElement.contains(svg);
      if (!(e.ctrlKey || e.metaKey || full)) { hint('wheel'); return; }
      e.preventDefault(); begin();
      zoomAt(Math.exp(-e.deltaY * (e.deltaMode ? 0.05 : 0.0025)), e.clientX, e.clientY, true);
      clearTimeout(settle); settle = setTimeout(end, 200);
    }, { passive: false });
    svg.addEventListener('pointerdown', e => {
      if (e.pointerType !== 'touch') { svg.setPointerCapture(e.pointerId); begin(); }
      pts.set(e.pointerId, { x: e.clientX, y: e.clientY }); moved = pts.size > 1 ? 99 : 0; last = { x: e.clientX, y: e.clientY };
    });
    svg.addEventListener('pointermove', e => {
      if (!pts.has(e.pointerId) || !vb) return;
      const dx = e.clientX - last.x, dy = e.clientY - last.y; moved += Math.abs(dx) + Math.abs(dy);
      last = { x: e.clientX, y: e.clientY }; pts.set(e.pointerId, last);
      if (e.pointerType === 'touch') return;      // one finger scrolls the page; two are read from touch events
      if (moved > 4) svg.classList.add('drag');
      const r = rect || measure();
      vb.x -= dx / r.width * vb.w; vb.y -= dy / r.height * vb.h; draw();
    });
    function up(e) {
      if (pts.size === 1 && moved <= 4 && e.type === 'pointerup') {
        const t = document.elementFromPoint(e.clientX, e.clientY);
        if (t && t.dataset && t.dataset.i != null) { state.sel = +t.dataset.i; paint(); onPick(D.fields[state.sel]); }
      }
      pts.delete(e.pointerId);
      if (!pts.size) { svg.classList.remove('drag'); if (e.pointerType !== 'touch' && busy) end(); }
    }
    svg.addEventListener('pointerup', up); svg.addEventListener('pointercancel', up);
    svg.addEventListener('touchstart', e => {
      if (e.touches.length >= 2) { two = mid(e.touches); one = null; begin(); }
      else if (e.touches.length === 1) one = { x: e.touches[0].clientX, y: e.touches[0].clientY, told: false };
    }, { passive: true });
    svg.addEventListener('touchmove', e => {
      if (e.touches.length >= 2 && vb) {
        e.preventDefault();
        const m = mid(e.touches);
        if (!two) { two = m; begin(); return; }
        const r = rect || measure();
        vb.x -= (m.x - two.x) / r.width * vb.w; vb.y -= (m.y - two.y) / r.height * vb.h;
        zoomAt(m.d / two.d, m.x, m.y, true); two = m;
      } else if (one && !two && e.touches.length === 1) {
        const t = e.touches[0];
        if (!one.told && Math.hypot(t.clientX - one.x, t.clientY - one.y) > 12) { one.told = true; hint('touch'); }
      }
    }, { passive: false });
    const lift = e => { if (e.touches.length < 2 && two) { two = null; if (busy) end(); } if (!e.touches.length) one = null; };
    svg.addEventListener('touchend', lift); svg.addEventListener('touchcancel', lift);
    window.addEventListener('resize', () => { if (vb) { vb.h = vb.w / ratio(); setVB(); } });
  }

  return {
    init, load, classOf,
    get data() { return D; },
    // select a field as a click would (the card shows it); highlight the fields a test picks (null clears)
    select: i => { state.sel = i; paint(); onPick(D.fields[i]); },
    highlight: pred => { state.hl = pred; if (D) paint(); },
    setInsets: (l, rr) => { insets = { left: l, right: rr }; },
    zoom: k => zoomAt(k),
    reset: () => { if (ext) { vb = fit(); setVB(); } },
    setMode: m => { state.mode = m; if (D) paint(); },
    setSat: on => { state.sat = on; if (D) paint(); },
    get mode() { return state.mode; },
    get sat() { return state.sat; }
  };
})();
