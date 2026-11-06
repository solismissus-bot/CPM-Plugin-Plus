/*
 * CPM Plugin+ "Arte e cores" core (beta29): pixel-art painting tools for Blockbench's Paint mode.
 * Loaded by tools/art/ArtCore.java from the plugin resources: this file is ONE expression that evaluates to the API
 * object (no globals). The brushes use Blockbench's own Brush API (Tool.brush.changePixel / draw / onStrokeStart), so
 * they get the stroke, size, shape, softness, opacity, mirror painting, pixel-perfect, 3D + UV painting and the
 * Ctrl+Z of the native brush for free. Everything this file creates is removed by api.uninstall().
 */
(function () {
  'use strict';
  var G = (typeof window !== 'undefined') ? window : globalThis;
  var api = { version: 1 };
  var env = { t: function (k) { return k; }, get: function () { return null; }, set: function () { }, test: false };
  var created = [];   // BarItems made here (deleted on uninstall)
  var undoers = [];   // listeners / patches to undo on uninstall
  var installed = false;

  /** translation with %s placeholders */
  function t(key) {
    var s = String(env.t(key)), a = arguments, i = 1;
    return s.replace(/%s/g, function () { return i < a.length ? String(a[i++]) : '%s'; });
  }
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function warn(msg, e) { try { console.warn('CPM+ arte: ' + msg, e || ''); } catch (x) { } }

  // =================================================================== state (prefs)
  var STATE_KEY = 'cpmplus.art.state';
  var state = { ramps: [], active: -1, tb: {}, opt: {}, hint: false };
  function loadState() {
    try {
      var v = env.get(STATE_KEY);
      if (typeof v === 'string' && v) v = JSON.parse(v);
      if (v && typeof v === 'object') state = v;
    } catch (e) { state = {}; }
    if (!Array.isArray(state.ramps)) state.ramps = [];
    if (typeof state.active !== 'number') state.active = -1;
    if (!state.tb || typeof state.tb !== 'object') state.tb = {};
    if (!state.opt || typeof state.opt !== 'object') state.opt = {};
  }
  function saveState() { try { env.set(STATE_KEY, JSON.stringify(state)); } catch (e) { warn('could not save the settings', e); } }
  function opt(name, def) { var v = state.opt[name]; return v === undefined ? def : v; }
  function setOpt(name, v) { state.opt[name] = v; saveState(); }

  // =================================================================== colour math (sRGB <-> OKLab / OKLCH, HSV, HSL)
  function s2l(c) { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }
  function l2s(c) { var v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055; return v * 255; }
  function rgbToOklab(r, g, b) {
    var R = s2l(r), Gc = s2l(g), B = s2l(b);
    var l = Math.cbrt(0.4122214708 * R + 0.5363325363 * Gc + 0.0514459929 * B);
    var m = Math.cbrt(0.2119034982 * R + 0.6806995451 * Gc + 0.1073969566 * B);
    var s = Math.cbrt(0.0883024619 * R + 0.2817188376 * Gc + 0.6299787005 * B);
    return [0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
      1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
      0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s];
  }
  function oklabToRgbF(L, a, b) {
    var l = L + 0.3963377774 * a + 0.2158037573 * b;
    var m = L - 0.1055613458 * a - 0.0638541728 * b;
    var s = L - 0.0894841775 * a - 1.2914855480 * b;
    l = l * l * l; m = m * m * m; s = s * s * s;
    return [l2s(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      l2s(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      l2s(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)];
  }
  function byte(v) { v = Math.round(v); return v < 0 ? 0 : v > 255 ? 255 : v; }
  function oklabToRgb(L, a, b) { var f = oklabToRgbF(L, a, b); return [byte(f[0]), byte(f[1]), byte(f[2])]; }
  function toLch(r, g, b) {
    var o = rgbToOklab(r, g, b), C = Math.sqrt(o[1] * o[1] + o[2] * o[2]);
    var H = Math.atan2(o[2], o[1]) * 180 / Math.PI; if (H < 0) H += 360;
    return { L: o[0], C: C, H: H };
  }
  function inGamut(f) { return f[0] >= -0.5 && f[0] <= 255.5 && f[1] >= -0.5 && f[1] <= 255.5 && f[2] >= -0.5 && f[2] <= 255.5; }
  function lchToRgbF(L, C, H) { var h = H * Math.PI / 180; return oklabToRgbF(L, C * Math.cos(h), C * Math.sin(h)); }
  /** OKLCH -> sRGB bytes; out of gamut: chroma reduced (binary search), never a clipped channel */
  function lchToRgb(L, C, H) {
    L = clamp(L, 0, 1); C = Math.max(0, C);
    var f = lchToRgbF(L, C, H);
    if (!inGamut(f)) {
      var lo = 0, hi = C;
      for (var i = 0; i < 22; i++) { var mid = (lo + hi) / 2; if (inGamut(lchToRgbF(L, mid, H))) lo = mid; else hi = mid; }
      f = lchToRgbF(L, lo, H);
    }
    return [byte(f[0]), byte(f[1]), byte(f[2])];
  }
  function rgbToHsv(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn, h = 0;
    if (d > 1e-9) {
      if (mx === r) h = ((g - b) / d) % 6; else if (mx === g) h = (b - r) / d + 2; else h = (r - g) / d + 4;
      h *= 60; if (h < 0) h += 360;
    }
    return { h: h, s: mx <= 1e-9 ? 0 : d / mx, v: mx };
  }
  function hsvToRgb(h, s, v) {
    h = ((h % 360) + 360) % 360; s = clamp(s, 0, 1); v = clamp(v, 0, 1);
    var c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c, r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; } else if (h < 120) { r = x; g = c; } else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; } else if (h < 300) { r = x; b = c; } else { r = c; b = x; }
    return [byte((r + m) * 255), byte((g + m) * 255), byte((b + m) * 255)];
  }
  function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, d = mx - mn, h = 0, s = 0;
    if (d > 1e-9) {
      s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      if (mx === r) h = ((g - b) / d) % 6; else if (mx === g) h = (b - r) / d + 2; else h = (r - g) / d + 4;
      h *= 60; if (h < 0) h += 360;
    }
    return { h: h, s: s, l: l };
  }
  function hslToRgb(h, s, l) {
    h = ((h % 360) + 360) % 360; s = clamp(s, 0, 1); l = clamp(l, 0, 1);
    var c = (1 - Math.abs(2 * l - 1)) * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = l - c / 2, r = 0, g = 0, b = 0;
    if (h < 60) { r = c; g = x; } else if (h < 120) { r = x; g = c; } else if (h < 180) { g = c; b = x; }
    else if (h < 240) { g = x; b = c; } else if (h < 300) { r = x; b = c; } else { r = c; b = x; }
    return [byte((r + m) * 255), byte((g + m) * 255), byte((b + m) * 255)];
  }
  function hex(c) { return '#' + [c[0], c[1], c[2]].map(function (v) { return ('0' + byte(v).toString(16)).slice(-2); }).join(''); }
  function parseHex(s) {
    if (typeof s !== 'string') return null;
    var m = /^\s*#?([0-9a-f]{6})([0-9a-f]{2})?\s*$/i.exec(s);
    if (!m) return null;
    var n = parseInt(m[1], 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function ckey(r, g, b) { return (r << 16) | (g << 8) | b; }
  function okDist(a, b) { var A = rgbToOklab(a[0], a[1], a[2]), B = rgbToOklab(b[0], b[1], b[2]); return Math.sqrt((A[0] - B[0]) * (A[0] - B[0]) + (A[1] - B[1]) * (A[1] - B[1]) + (A[2] - B[2]) * (A[2] - B[2])); }
  function lerpRgb(a, b, w) { return [a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w]; }
  /** hue moved toward a target by at most 'by' degrees along the short way */
  function hueToward(h, target, by) {
    var d = ((target - h + 540) % 360) - 180;
    if (Math.abs(d) <= by) return target;
    return (h + (d > 0 ? by : -by) + 360) % 360;
  }

  // =================================================================== shading: lighter / darker with hue shift (no palette needed)
  var WARM = 60, COOL = 245;
  /**
   * One shading step. dir +1 = lighter (toward warm yellow, less saturated), -1 = darker (toward cool blue, more
   * saturated). k = strength 0..1 (0.15 = 15% of the way to white / black). Greys get the hue of the light / shadow.
   */
  function hueShade(rgb, dir, k) {
    var c = rgbToHsv(rgb[0], rgb[1], rgb[2]), h = c.h, s = c.s, v = c.v;
    k = clamp(k, 0, 1);
    var turn = 60 * k;
    if (dir > 0) {
      h = s < 0.02 ? WARM : hueToward(h, WARM, turn);
      s = s < 0.02 ? Math.min(0.06, k * 0.3) * (v < 0.98 ? 1 : 0) : s * (1 - k * 0.5);
      v = v + (1 - v) * k;
    } else {
      h = s < 0.02 ? COOL : hueToward(h, COOL, turn);
      s = s < 0.02 ? Math.min(0.12, k * 0.5) * (v > 0.02 ? 1 : 0) : s + (1 - s) * k * 0.5;
      v = v * (1 - k);
    }
    return hsvToRgb(h, s, v);
  }
  function hslShift(rgb, dh, ds, dl) {
    var c = rgbToHsl(rgb[0], rgb[1], rgb[2]);
    return hslToRgb(c.h + dh, c.s + ds, c.l + dl);
  }

  // =================================================================== colour families (ramps found in a texture)
  /**
   * Groups colours into families of the same hue (greys together), each sorted dark -> light by OKLab lightness.
   * colours: array of [r,g,b]. -> {families: [[[r,g,b]...]...], index: {key: [family, position]}}
   */
  function buildFamilies(colours) {
    var seen = {}, items = [];
    colours.forEach(function (c) {
      var k = ckey(c[0], c[1], c[2]); if (seen[k]) return; seen[k] = 1;
      var l = toLch(c[0], c[1], c[2]); items.push({ c: [c[0], c[1], c[2]], k: k, L: l.L, C: l.C, H: l.H });
    });
    var greys = items.filter(function (i) { return i.C < 0.035; });
    var hues = items.filter(function (i) { return i.C >= 0.035; }).sort(function (a, b) { return a.H - b.H; });
    var groups = [];
    if (hues.length) {
      // start after the biggest hue gap (circular), then split where the gap is above 24 degrees
      var bestGap = -1, start = 0;
      for (var i = 0; i < hues.length; i++) {
        var nx = hues[(i + 1) % hues.length], gap = (nx.H - hues[i].H + 360) % 360;
        if (hues.length === 1) gap = 360;
        if (gap > bestGap) { bestGap = gap; start = (i + 1) % hues.length; }
      }
      var cur = [hues[start]];
      for (var j = 1; j < hues.length; j++) {
        var a = hues[(start + j - 1) % hues.length], b = hues[(start + j) % hues.length];
        if ((b.H - a.H + 360) % 360 > 24) { groups.push(cur); cur = []; }
        cur.push(b);
      }
      groups.push(cur);
    }
    if (greys.length) groups.push(greys);
    var fams = [], index = {};
    groups.forEach(function (g) {
      g.sort(function (a, b) { return a.L - b.L || a.k - b.k; });
      var f = g.map(function (i) { return i.c; });
      g.forEach(function (it, pos) { index[it.k] = [fams.length, pos]; });
      fams.push(f);
    });
    return { families: fams, index: index };
  }
  /** unique opaque colours of an ImageData (alpha > 0), capped */
  function coloursOf(data, cap) {
    var seen = {}, out = [], d = data.data || data;
    for (var i = 0; i < d.length; i += 4) {
      if (d[i + 3] === 0) continue;
      var k = ckey(d[i], d[i + 1], d[i + 2]);
      if (seen[k]) continue; seen[k] = 1; out.push([d[i], d[i + 1], d[i + 2]]);
      if (cap && out.length >= cap) break;
    }
    return out;
  }
  var famCache = { key: null, fam: null };
  function textureCanvasInfo(tex) {
    try { var a = tex.getActiveCanvas(); if (a && a.canvas && a.ctx) return a; } catch (e) { }
    return tex && tex.canvas ? { canvas: tex.canvas, ctx: tex.canvas.getContext('2d'), offset: [0, 0] } : null;
  }
  /** families of the colours of a texture (cached per stroke / dialog) */
  function textureFamilies(tex, refresh) {
    if (!tex) return buildFamilies([]);
    var info = textureCanvasInfo(tex); if (!info) return buildFamilies([]);
    var k = tex.uuid + ':' + info.canvas.width + 'x' + info.canvas.height;
    if (!refresh && famCache.key === k && famCache.fam) return famCache.fam;
    var data = info.ctx.getImageData(0, 0, info.canvas.width, info.canvas.height);
    famCache = { key: k, fam: buildFamilies(coloursOf(data, 4096)) };
    return famCache.fam;
  }
  /**
   * One step along a ramp: saved ramps first (exact colour), then the families. Steps over colours whose lightness
   * is almost the same (min dL) so a step is always visible.
   * -> {c:[r,g,b]} next tone, {end:true} already the darkest / lightest tone of its ramp, or null (not in any ramp)
   */
  function rampStep(rgb, dir, fam, ramps) {
    var k = ckey(rgb[0], rgb[1], rgb[2]);
    if (ramps) for (var r = 0; r < ramps.length; r++) {
      var cols = ramps[r];
      for (var i = 0; i < cols.length; i++) {
        var c = cols[i];
        if (c && ckey(c[0], c[1], c[2]) === k) {
          // saved ramps are stored dark -> light
          var j = i + (dir > 0 ? 1 : -1);
          return j >= 0 && j < cols.length ? { c: cols[j].slice() } : { end: true };
        }
      }
    }
    if (!fam) return null;
    var at = fam.index[k]; if (!at) return null;
    var f = fam.families[at[0]], L0 = toLch(rgb[0], rgb[1], rgb[2]).L;
    if (f.length < 2) return null;
    for (var p = at[1] + (dir > 0 ? 1 : -1); p >= 0 && p < f.length; p += (dir > 0 ? 1 : -1)) {
      if (Math.abs(toLch(f[p][0], f[p][1], f[p][2]).L - L0) >= 0.025) return { c: f[p].slice() };
    }
    return { end: true };
  }
  function savedRampColours() {
    return state.ramps.map(function (r) { return (r.colors || []).map(parseHex).filter(function (c) { return !!c; }); }).filter(function (r) { return r.length > 1; });
  }

  // =================================================================== ramp generator (hue-shifted, OKLCH)
  /**
   * opts: {mode:'one'|'two', a:[r,g,b], b:[r,g,b], steps, hue (degrees of shift at the ends), contrast (0.2..0.9 of
   * OKLab lightness), sat (0..1 how much chroma drops at the ends)} -> array of [r,g,b] dark -> light
   */
  function makeRamp(o) {
    var n = clamp(Math.round(o.steps || 7), 2, 32), out = [];
    if (o.mode === 'two' && o.b) {
      var A = toLch(o.a[0], o.a[1], o.a[2]), B = toLch(o.b[0], o.b[1], o.b[2]);
      if (A.L > B.L) { var tmp = A; A = B; B = tmp; }
      var dh = ((B.H - A.H + 540) % 360) - 180;
      if (A.C < 0.02) A.H = B.H; if (B.C < 0.02) B.H = A.H;
      dh = ((B.H - A.H + 540) % 360) - 180;
      for (var i = 0; i < n; i++) {
        var w = n === 1 ? 0 : i / (n - 1);
        out.push(lchToRgb(A.L + (B.L - A.L) * w, A.C + (B.C - A.C) * w, (A.H + dh * w + 360) % 360));
      }
      return out;
    }
    var base = toLch(o.a[0], o.a[1], o.a[2]);
    var contrast = clamp(o.contrast === undefined ? 0.6 : o.contrast, 0.05, 0.95);
    var hue = clamp(o.hue === undefined ? 40 : o.hue, 0, 120), sat = clamp(o.sat === undefined ? 0.45 : o.sat, 0, 1);
    var mid = Math.floor((n - 1) / 2);
    // lightness: centred on the base colour, spread by 'contrast', moved inside [0.10, 0.97]
    var lo = base.L - contrast * (mid / (n - 1)), hi = base.L + contrast * ((n - 1 - mid) / (n - 1));
    if (lo < 0.10) { hi += 0.10 - lo; lo = 0.10; }
    if (hi > 0.97) { lo -= hi - 0.97; hi = 0.97; }
    lo = Math.max(0.06, lo);
    for (var j = 0; j < n; j++) {
      if (j === mid) { out.push([o.a[0], o.a[1], o.a[2]]); continue; }
      var L = n === 1 ? base.L : (j < mid ? base.L - (base.L - lo) * ((mid - j) / Math.max(1, mid)) : base.L + (hi - base.L) * ((j - mid) / Math.max(1, n - 1 - mid)));
      var far = j < mid ? (mid - j) / Math.max(1, mid) : (j - mid) / Math.max(1, n - 1 - mid);
      var H = base.C < 0.02 ? (j < mid ? COOL : WARM) : hueToward(base.H, j < mid ? 265 : 75, hue * far);
      var C = base.C < 0.02 ? Math.min(0.03, 0.02 + far * 0.02) * (hue > 0 ? 1 : 0) : base.C * (1 - sat * far * far);
      out.push(lchToRgb(L, C, H));
    }
    return out;
  }

  // =================================================================== Blockbench UI helpers
  function bb(name) { return G[name]; }
  function isTool(id) { try { return !!(G.Toolbox && G.Toolbox.selected && G.Toolbox.selected.id === id); } catch (e) { return false; } }
  function mk(kind, id, data) {
    var Ctor = bb(kind);
    if (typeof Ctor !== 'function') throw new Error(kind + ' is not available');
    if (G.BarItems && G.BarItems[id]) { try { G.BarItems[id].delete(); } catch (e) { } }
    var item = new Ctor(id, data);
    created.push(item);
    adoptStale(item);
    return item;
  }
  /**
   * An earlier copy of the plugin (reloaded in the same session) can leave its item objects inside Blockbench's
   * toolbars even after delete(). The new item takes their place (same position = the user's layout is kept) and any
   * second copy is dropped, so an icon never shows twice.
   */
  function adoptStale(item) {
    var bars = G.Toolbars || {};
    Object.keys(bars).forEach(function (k) {
      var bar = bars[k], kids = bar && bar.children;
      if (!Array.isArray(kids)) return;
      var changed = false, seen = kids.indexOf(item) >= 0;
      for (var i = 0; i < kids.length; i++) {
        var c = kids[i];
        if (!c || typeof c !== 'object' || c === item || c.id !== item.id) continue;
        if (!seen) { kids[i] = item; seen = true; if (item.toolbars && item.toolbars.indexOf(bar) < 0) item.toolbars.push(bar); }
        else { kids.splice(i, 1); i--; }
        changed = true;
      }
      if (changed) { try { bar.update(true); bar.save(); } catch (e) { } }
    });
  }
  /** adds an item to a Blockbench toolbar ONCE (later the toolbar remembers it, and a user removal is respected) */
  function placeOnce(toolbarId, item, afterId) {
    try {
      var bar = G.Toolbars && G.Toolbars[toolbarId];
      if (!bar || !item) return false;
      var flag = toolbarId + ':' + item.id;
      if (bar.children.indexOf(item) >= 0) { if (!state.tb[flag]) { state.tb[flag] = true; saveState(); } return true; }
      if (state.tb[flag]) return false;
      var idx = -1;
      if (afterId) for (var i = 0; i < bar.children.length; i++) { var c = bar.children[i]; if (c && (c === afterId || c.id === afterId)) idx = i + 1; }
      if (idx < 0) bar.add(item); else bar.add(item, idx);
      state.tb[flag] = true; saveState();
      return true;
    } catch (e) { warn('could not place ' + (item && item.id) + ' on the toolbar ' + toolbarId, e); return false; }
  }
  /** puts every art item back on its toolbar (menu action 'show the art tools again') */
  function replaceAll() {
    Object.keys(state.tb).forEach(function (k) { delete state.tb[k]; });
    saveState();
    placements.forEach(function (p) { var it = typeof p[1] === 'string' ? G.BarItems[p[1]] : p[1]; placeOnce(p[0], it, p[2]); });
  }
  var placements = [];
  function place(toolbarId, item, afterId) {
    if (item && created.indexOf(item) < 0) adoptStale(item);// items made by Java (actions) are not built by mk()
    placements.push([toolbarId, item, afterId]);
    return placeOnce(toolbarId, item, afterId);
  }
  function slider(id, nameKey, min, max, def, optName, cond, extra) {
    var s = mk('NumSlider', id, Object.assign({
      name: t(nameKey), description: t(nameKey + '.desc'), category: 'paint', condition: cond,
      settings: { min: min, max: max, default: def, interval: 1, show_bar: true },
      onChange: function () { setOpt(optName, s.get()); }
    }, extra || {}));
    try { s.setValue(opt(optName, def)); } catch (e) { }
    return s;
  }
  function select(id, nameKey, options, def, optName, cond, onChange) {
    var o = {};
    options.forEach(function (k) { o[k] = t(nameKey + '.' + k); });
    var s = mk('BarSelect', id, {
      name: t(nameKey), description: t(nameKey + '.desc'), category: 'paint', condition: cond, options: o, value: def,
      onChange: function (sel) { setOpt(optName, sel.value); if (onChange) onChange(sel.value); refreshBrushBar(); }
    });
    var v = opt(optName, def); if (o[v] === undefined) v = def;
    try { s.set(v); } catch (e) { }
    return s;
  }
  function toggle(id, nameKey, icon, def, optName, cond) {
    var s = mk('Toggle', id, {
      name: t(nameKey), description: t(nameKey + '.desc'), icon: icon, category: 'paint', condition: cond, default: opt(optName, def),
      onChange: function (v) { setOpt(optName, !!v); }
    });
    return s;
  }
  function refreshBrushBar() { try { if (G.Toolbars && G.Toolbars.brush) G.Toolbars.brush.update(true); } catch (e) { } }
  function secondaryClick(event) {
    try {
      var ev = (G.Painter && G.Painter.current && G.Painter.current.start_event) || event;
      return !!(ev && G.Keybinds.extra.paint_secondary_color.keybind.isTriggered(ev));
    } catch (e) { return !!(event && (event.button === 2 || event.which === 3)); }
  }
  function brushBase() {
    return {
      shapes: true, size: true, softness: true, opacity: true, offset_even_radius: true, pixel_perfect: true, screen_space: true,
      floor_coordinates: function () { return G.BarItems.slider_brush_softness.get() == 0; }
    };
  }
  function makeTool(id, nameKey, icon, brush) {
    return mk('Tool', id, {
      name: t(nameKey), description: t(nameKey + '.desc'), icon: icon, category: 'tools', toolbar: 'brush',
      alt_tool: 'color_picker', cursor: 'crosshair', selectFace: true, transformerMode: 'hidden', paintTool: true,
      allowed_view_modes: ['textured', 'material'], modes: ['paint'], brush: brush,
      onCanvasClick: function (data) { G.Painter.startPaintToolCanvas(data, data.event); },
      onSelect: function () { try { G.Painter.updateNslideValues(); } catch (e) { } refreshBrushBar(); },
      onUnselect: function () { refreshBrushBar(); }
    });
  }

  // =================================================================== per-stroke memory ("once per stroke")
  /** original colour + strongest weight already applied, per pixel of the stroke */
  var stroke = { map: null, tex: null, fam: null, ramps: null, seed: 1, last: null, dab: 0, snap: null };
  var fixedSeed = null;
  function beginStroke(o) {
    stroke.map = new Map();
    stroke.tex = o && o.texture;
    stroke.fam = null; stroke.ramps = null; stroke.snap = null; stroke.dab = 0;
    stroke.seed = fixedSeed !== null ? fixedSeed : ((stroke.seed * 1103515245 + 12345 + Date.now()) & 0x7fffffff);
    stroke.last = null; stroke.lasts = [];
  }
  function strokeRecord(x, y, px, once) {
    var k = (y << 16) ^ x, rec = stroke.map ? stroke.map.get(k) : null;
    if (!rec) { rec = { r: px.r, g: px.g, b: px.b, a: px.a, w: 0 }; if (stroke.map) stroke.map.set(k, rec); }
    else if (!once) { rec.r = px.r; rec.g = px.g; rec.b = px.b; rec.a = px.a; rec.w = 0; }
    return rec;
  }
  /** blends the target colour over the original by w; w = 1 gives the exact colour (pixel-art safe) */
  function finish(px, rec, rgb, w) {
    if (w >= 0.999) return { r: rgb[0], g: rgb[1], b: rgb[2], a: px.a };
    var c = lerpRgb([rec.r, rec.g, rec.b], rgb, w);
    return { r: byte(c[0]), g: byte(c[1]), b: byte(c[2]), a: px.a };
  }
  function strokeFamilies(tex) {
    if (!stroke.fam) stroke.fam = textureFamilies(tex || stroke.tex, true);
    return stroke.fam;
  }

  // =================================================================== A2 Sombra (shading brush)
  var shade = {};
  function shadeStrength() { return (G.BarItems.cpmplus_art_shade_strength ? G.BarItems.cpmplus_art_shade_strength.get() : 15) / 100; }
  /** the shaded colour of one pixel (no blending): mode hue | ramp | hsl, dir +1 lighter / -1 darker */
  function shadeColour(rgb, dir, mode, k, tex) {
    if (mode === 'ramp') {
      if (!stroke.ramps) stroke.ramps = savedRampColours();
      var src = opt('shade.source', 'auto');
      var stepped = rampStep(rgb, dir, src === 'ramps' ? null : strokeFamilies(tex), stroke.ramps);
      if (stepped) return stepped.c || null;// at the end of its ramp: stays (like Aseprite's shading ink)
      return opt('shade.fallback', true) ? hueShade(rgb, dir, k) : null;
    }
    if (mode === 'hsl') {
      var dh = G.BarItems.cpmplus_art_hsl_h ? G.BarItems.cpmplus_art_hsl_h.get() : 0;
      var ds = (G.BarItems.cpmplus_art_hsl_s ? G.BarItems.cpmplus_art_hsl_s.get() : 0) / 100;
      var dl = (G.BarItems.cpmplus_art_hsl_l ? G.BarItems.cpmplus_art_hsl_l.get() : -10) / 100;
      return dir > 0 ? hslShift(rgb, dh, ds, dl) : hslShift(rgb, -dh, -ds, -dl);
    }
    return hueShade(rgb, dir, k);
  }
  function shadePixel(x, y, px, alpha, o) {
    if (!px || px.a <= 0) return px;
    var once = opt('shade.once', true);
    var rec = strokeRecord(x, y, px, once);
    var w = clamp(alpha * (o && o.opacity !== undefined ? o.opacity : 1), 0, 1);
    if (once) { if (w <= rec.w + 1e-6) return px; rec.w = w; }
    var mode = opt('shade.mode', 'hue');
    // hsl: the sliders are the left-click change; other modes: the 'Clique esquerdo' choice. Secondary click = opposite
    var dir = mode === 'hsl' ? 1 : (opt('shade.dir', 'darken') === 'darken' ? -1 : 1);
    if (secondaryClick(o && o.event)) dir = -dir;
    var target = shadeColour([rec.r, rec.g, rec.b], dir, mode, shadeStrength(), o && o.texture);
    if (!target) return px;
    return finish(px, rec, target, w);
  }
  function installShade() {
    var b = brushBase();
    b.onStrokeStart = function (o) { beginStroke(o); };
    b.changePixel = function (x, y, px, alpha, o) { return shadePixel(x, y, px, alpha, o); };
    shade.tool = makeTool('cpmplus_art_shade', 'cpmplus.art.shade', 'tonality', b);
    var onShade = function () { return isTool('cpmplus_art_shade'); };
    shade.mode = select('cpmplus_art_shade_mode', 'cpmplus.art.shade.mode', ['hue', 'ramp', 'hsl'], 'hue', 'shade.mode', onShade);
    shade.dir = select('cpmplus_art_shade_dir', 'cpmplus.art.shade.dir', ['darken', 'lighten'], 'darken', 'shade.dir',
      function () { return onShade() && opt('shade.mode', 'hue') !== 'hsl'; });
    shade.source = select('cpmplus_art_shade_source', 'cpmplus.art.shade.source', ['auto', 'ramps'], 'auto', 'shade.source',
      function () { return onShade() && opt('shade.mode', 'hue') === 'ramp'; });
    shade.strength = slider('cpmplus_art_shade_strength', 'cpmplus.art.shade.strength', 1, 100, 15, 'shade.strength',
      function () { return onShade() && opt('shade.mode', 'hue') !== 'hsl'; });
    var hslOn = function () { return onShade() && opt('shade.mode', 'hue') === 'hsl'; };
    shade.h = slider('cpmplus_art_hsl_h', 'cpmplus.art.shade.hslH', -180, 180, 0, 'shade.hslH', hslOn);
    shade.s = slider('cpmplus_art_hsl_s', 'cpmplus.art.shade.hslS', -100, 100, 0, 'shade.hslS', hslOn);
    shade.l = slider('cpmplus_art_hsl_l', 'cpmplus.art.shade.hslL', -100, 100, -10, 'shade.hslL', hslOn);
    shade.once = toggle('cpmplus_art_shade_once', 'cpmplus.art.shade.once', 'looks_one', true, 'shade.once', onShade);
    place('tools', shade.tool, 'brush_tool');
    var after = 'slider_brush_softness';
    [shade.mode, shade.dir, shade.source, shade.strength, shade.h, shade.s, shade.l, shade.once].forEach(function (it) { place('brush', it, after); after = it.id; });
  }

  // =================================================================== shared pixel helpers (brushes that read neighbours)
  /** deterministic 0..1 hash of a pixel for this stroke (same stroke + same pixel = same number) */
  function hash3(x, y, s) {
    var h = (x * 374761393 + y * 668265263 + s * 2246822519) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h = h ^ (h >>> 16);
    return ((h >>> 0) % 100000) / 100000;
  }
  /** a copy of the canvas around (x, y) in TEXTURE pixels (layer offset handled); get(x, y) -> [r,g,b,a] or null */
  function readArea(ctx, cx, cy, rad) {
    var off = (G.Painter && G.Painter.current && G.Painter.current.offset) || [0, 0];
    var x0 = Math.floor(cx) - rad, y0 = Math.floor(cy) - rad, w = rad * 2 + 2, h = rad * 2 + 2;
    var lx = x0 - off[0], ly = y0 - off[1], cw = ctx.canvas.width, ch = ctx.canvas.height;
    var sx = Math.max(0, lx), sy = Math.max(0, ly), ex = Math.min(cw, lx + w), ey = Math.min(ch, ly + h);
    var data = null, dw = ex - sx;
    if (ex > sx && ey > sy) data = ctx.getImageData(sx, sy, ex - sx, ey - sy).data;
    var face = G.Painter && G.Painter.editing_area;
    function get(x, y) {
      x = Math.round(x); y = Math.round(y);
      if (face && typeof face === 'object' && (x < Math.floor(face[0]) || y < Math.floor(face[1]) || x >= face[2] || y >= face[3])) return null;
      var px = x - off[0], py = y - off[1];
      if (!data || px < sx || py < sy || px >= ex || py >= ey) return null;
      var i = ((py - sy) * dw + (px - sx)) * 4;
      return [data[i], data[i + 1], data[i + 2], data[i + 3]];
    }
    return { get: get };
  }
  /** nearest colour of the stroke's palette (the colours of the texture at stroke start), cached */
  function snapper(tex) {
    var info = textureCanvasInfo(tex), list = [], cache = new Map();
    if (info) list = coloursOf(info.ctx.getImageData(0, 0, info.canvas.width, info.canvas.height), 4096);
    var labs = list.map(function (c) { return rgbToOklab(c[0], c[1], c[2]); });
    return function (rgb) {
      if (!list.length) return rgb;
      var k = ckey(byte(rgb[0]), byte(rgb[1]), byte(rgb[2])), hit = cache.get(k);
      if (hit) return hit;
      var L = rgbToOklab(rgb[0], rgb[1], rgb[2]), best = 0, bd = Infinity;
      for (var i = 0; i < labs.length; i++) {
        var d = (labs[i][0] - L[0]) * (labs[i][0] - L[0]) + (labs[i][1] - L[1]) * (labs[i][1] - L[1]) + (labs[i][2] - L[2]) * (labs[i][2] - L[2]);
        if (d < bd) { bd = d; best = i; }
      }
      cache.set(k, list[best]);
      return list[best];
    };
  }
  function brushSettings() {
    var max = (G.settings && G.settings.opacity_range && G.settings.opacity_range.value == '255') ? 255 : 100;
    return { opacity: G.BarItems.slider_brush_opacity.get() / max, shape: G.BarItems.brush_shape ? G.BarItems.brush_shape.value : 'square' };
  }
  function editBrush(o, fn) {
    var dims = typeof G.Painter.getBrushDimensions === 'function' ? G.Painter.getBrushDimensions(o.size) : o.size;
    if (brushSettings().shape === 'circle') G.Painter.editCircle(o.ctx, o.x, o.y, dims, o.softness * 1.8, fn);
    else G.Painter.editSquare(o.ctx, o.x, o.y, dims, o.softness * 1.8, fn);
  }

  // =================================================================== A3 Esfumar (smudge: smooth / drag pixels / jumble / blur)
  var smudge = {};
  /** one dab of the smudge brush (Blockbench Brush API 'draw'): reads the area before the dab, writes per pixel */
  /**
   * the previous dab of the same stroke near (x, y): mirror painting calls the brush on the other side too, so each
   * side keeps its own last position (the closest one within a few pixels)
   */
  function lastNear(x, y) {
    var L = stroke.lasts || (stroke.lasts = []), best = -1, bd = 1e9;
    for (var i = 0; i < L.length; i++) { var d = Math.abs(L[i][0] - x) + Math.abs(L[i][1] - y); if (d < bd) { bd = d; best = i; } }
    var near = best >= 0 && bd <= 6, prev = near ? L[best].slice() : null;
    if (near) L[best] = [x, y]; else { L.push([x, y]); if (L.length > 8) L.shift(); }
    return prev;
  }
  function smudgeDab(o) {
    var mode = opt('smudge.mode', 'smear'), last = lastNear(o.x, o.y);
    stroke.dab = (stroke.dab || 0) + 1;
    var dx = last ? o.x - last[0] : 0, dy = last ? o.y - last[1] : 0;
    if ((mode === 'smear' || mode === 'pixel') && (!last || (dx === 0 && dy === 0))) return;
    var strength = (G.BarItems.cpmplus_art_smudge_strength ? G.BarItems.cpmplus_art_smudge_strength.get() : 50) / 100;
    var bs = brushSettings(), snap = opt('smudge.snap', false) && (mode === 'smear' || mode === 'blur');
    if (snap && !stroke.snap) stroke.snap = snapper(o.texture);
    var rad = Math.ceil((o.size || 1) / 2) + Math.ceil(Math.max(Math.abs(dx), Math.abs(dy))) + 3;
    var area = readArea(o.ctx, o.x, o.y, rad), seed = stroke.seed, dab = stroke.dab;
    editBrush(o, function (px, local, x, y) {
      if (px.a <= 0) return px;// never paints where there is nothing: smudging keeps the shape (alpha) of the texture
      var a = clamp(strength * local * bs.opacity, 0, 1), rgb = null;
      if (mode === 'smear') {
        var sx = x - dx, sy = y - dy, fx = Math.floor(sx), fy = Math.floor(sy), wx = sx - fx, wy = sy - fy, acc = [0, 0, 0], wsum = 0;
        [[fx, fy, (1 - wx) * (1 - wy)], [fx + 1, fy, wx * (1 - wy)], [fx, fy + 1, (1 - wx) * wy], [fx + 1, fy + 1, wx * wy]].forEach(function (s) {
          if (s[2] <= 0) return;
          var c = area.get(s[0], s[1]); if (!c || c[3] === 0) return;
          acc[0] += c[0] * s[2]; acc[1] += c[1] * s[2]; acc[2] += c[2] * s[2]; wsum += s[2];
        });
        if (wsum <= 0) return px;
        var sm = [acc[0] / wsum, acc[1] / wsum, acc[2] / wsum];
        rgb = lerpRgb([px.r, px.g, px.b], sm, a);
      } else if (mode === 'blur') {
        var sum = [0, 0, 0], n = 0;
        for (var j = -1; j <= 1; j++) for (var i = -1; i <= 1; i++) {
          var c2 = area.get(x + i, y + j); if (!c2 || c2[3] === 0) continue;
          sum[0] += c2[0]; sum[1] += c2[1]; sum[2] += c2[2]; n++;
        }
        if (!n) return px;
        rgb = lerpRgb([px.r, px.g, px.b], [sum[0] / n, sum[1] / n, sum[2] / n], a);
      } else {
        // pixel / jumble: whole pixels only (never a new colour); 'a' is the chance that a pixel takes the sampled one
        if (hash3(x, y, seed + dab * 7919) >= a) return px;
        var c3;
        if (mode === 'pixel') c3 = area.get(Math.round(x - dx), Math.round(y - dy));
        else {
          var rx = Math.floor(hash3(x, y, seed + dab * 104729 + 1) * 3) - 1, ry = Math.floor(hash3(x, y, seed + dab * 15485863 + 2) * 3) - 1;
          c3 = area.get(x + rx - Math.round(dx / 4), y + ry - Math.round(dy / 4));
        }
        if (!c3 || c3[3] === 0) return px;
        return { r: c3[0], g: c3[1], b: c3[2], a: px.a };
      }
      if (snap) rgb = stroke.snap(rgb);
      return { r: byte(rgb[0]), g: byte(rgb[1]), b: byte(rgb[2]), a: px.a };
    });
  }
  function installSmudge() {
    var b = brushBase();
    b.blend_modes = false;
    b.interval = 1;
    b.onStrokeStart = function (o) { beginStroke(o); stroke.snap = null; stroke.dab = 0; };
    b.draw = function (o) { smudgeDab(o); };
    smudge.tool = makeTool('cpmplus_art_smudge', 'cpmplus.art.smudge', 'touch_app', b);
    var on = function () { return isTool('cpmplus_art_smudge'); };
    smudge.mode = select('cpmplus_art_smudge_mode', 'cpmplus.art.smudge.mode', ['smear', 'pixel', 'jumble', 'blur'], 'smear', 'smudge.mode', on);
    smudge.strength = slider('cpmplus_art_smudge_strength', 'cpmplus.art.smudge.strength', 1, 100, 50, 'smudge.strength', on);
    smudge.snap = toggle('cpmplus_art_smudge_snap', 'cpmplus.art.smudge.snap', 'palette', false, 'smudge.snap',
      function () { return on() && ['smear', 'blur'].indexOf(opt('smudge.mode', 'smear')) >= 0; });
    place('tools', smudge.tool, 'cpmplus_art_shade');
    var after = 'cpmplus_art_shade_once';
    [smudge.mode, smudge.strength, smudge.snap].forEach(function (it) { place('brush', it, after); after = it.id; });
  }

  // =================================================================== dither patterns (shared by the brush, gradient and auto shading)
  var BAYER2 = [[0, 2], [3, 1]];
  var BAYER4 = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];
  var BAYER8 = (function () {
    var m = [];
    for (var y = 0; y < 8; y++) {
      m.push([]);
      for (var x = 0; x < 8; x++) m[y].push(4 * BAYER4[y % 4][x % 4] + BAYER2[Math.floor(y / 4)][Math.floor(x / 4)]);
    }
    return m;
  })();
  /** threshold 0..1 of a pattern at a texture pixel (a cell is 'on' when threshold < density) */
  function ditherThreshold(pattern, x, y, shift) {
    // shift moves the pattern sideways (a diagonal shift would leave the checker unchanged)
    var sx = x + (shift || 0), sy = y, m = function (a, n) { return ((a % n) + n) % n; };
    switch (pattern) {
      case 'bayer4': return (BAYER4[m(sy, 4)][m(sx, 4)] + 0.5) / 16;
      case 'bayer8': return (BAYER8[m(sy, 8)][m(sx, 8)] + 0.5) / 64;
      case 'lines_h': return (m(sy, 4) + 0.5) / 4;
      case 'lines_v': return (m(sx, 4) + 0.5) / 4;
      case 'diag': return (m(sx + sy, 4) + 0.5) / 4;
      case 'noise': return hash3(sx, sy, 9973);
      default: return (BAYER2[m(sy, 2)][m(sx, 2)] + 0.5) / 4;
    }
  }

  // =================================================================== A4 Pontilhado (dither brush)
  var dither = {};
  function rgbObj(hexStr) { var c = parseHex(hexStr) || [0, 0, 0]; return { r: c[0], g: c[1], b: c[2], a: 1 }; }
  function ditherPixel(x, y, px, alpha, o) {
    var inv = secondaryClick(o && o.event);
    var dens = (G.BarItems.cpmplus_art_dither_density ? G.BarItems.cpmplus_art_dither_density.get() : 50) / 100;
    var shift = G.BarItems.cpmplus_art_dither_shift ? G.BarItems.cpmplus_art_dither_shift.get() : 0;
    var on = ditherThreshold(opt('dither.pattern', 'bayer2'), x, y, shift) < dens - 1e-9;
    if (inv) on = !on;
    var col;
    if (on) col = o && o.color ? { r: o.color.r, g: o.color.g, b: o.color.b, a: 1 } : rgbObj(G.ColorPanel.get(inv));
    else if (opt('dither.holes', 'keep') === 'second') col = rgbObj(G.ColorPanel.get(!inv));
    else return px;
    if (G.Painter.lock_alpha && px.a <= 0) return px;
    var rec = strokeRecord(x, y, px, true), w = clamp(alpha * (o && o.opacity !== undefined ? o.opacity : 1), 0, 1);
    if (w <= rec.w + 1e-6) return px;
    rec.w = w;
    var res = G.Painter.combineColors({ r: rec.r, g: rec.g, b: rec.b, a: rec.a }, col, w);
    if (G.Painter.lock_alpha) res.a = rec.a;
    return res;
  }
  function installDither() {
    var b = brushBase();
    b.onStrokeStart = function (o) { beginStroke(o); };
    b.changePixel = function (x, y, px, alpha, o) { return ditherPixel(x, y, px, alpha, o); };
    dither.tool = makeTool('cpmplus_art_dither', 'cpmplus.art.dither', 'apps', b);
    var on = function () { return isTool('cpmplus_art_dither'); };
    dither.pattern = select('cpmplus_art_dither_pattern', 'cpmplus.art.dither.pattern', ['bayer2', 'bayer4', 'bayer8', 'lines_h', 'lines_v', 'diag', 'noise'], 'bayer2', 'dither.pattern', on);
    dither.density = slider('cpmplus_art_dither_density', 'cpmplus.art.dither.density', 0, 100, 50, 'dither.density', on);
    dither.holes = select('cpmplus_art_dither_holes', 'cpmplus.art.dither.holes', ['keep', 'second'], 'keep', 'dither.holes', on);
    dither.shift = slider('cpmplus_art_dither_shift', 'cpmplus.art.dither.shift', 0, 7, 0, 'dither.shift', on);
    place('tools', dither.tool, 'cpmplus_art_smudge');
    var after = 'cpmplus_art_smudge_snap';
    [dither.pattern, dither.density, dither.holes, dither.shift].forEach(function (it) { place('brush', it, after); after = it.id; });
  }

  // =================================================================== A5 Degradê pixel art (a style of Blockbench's own Gradient tool)
  var grad = {};
  function activeRamp() {
    var r = state.ramps[state.active];
    var cols = r ? (r.colors || []).map(parseHex).filter(Boolean) : [];
    return cols.length > 1 ? cols : null;
  }
  /** the colours of the gradient, start -> end, each [r,g,b,alpha 0..1] */
  function gradientColours(event) {
    var swap = secondaryClick(event), kind = opt('grad.colors', 'two');
    var n = G.BarItems.cpmplus_art_grad_steps ? G.BarItems.cpmplus_art_grad_steps.get() : 5;
    var out = [];
    if (kind === 'ramp') {
      var r = activeRamp();
      if (r) { out = r.map(function (c) { return [c[0], c[1], c[2], 1]; }); return swap ? out.reverse() : out; }
      if (!grad.warned) { grad.warned = true; try { G.Blockbench.showQuickMessage(t('cpmplus.art.grad.noRamp'), 3000); } catch (e) { } }
      kind = 'two';
    }
    var a = primary(), b = secondary();
    if (swap) { var tmp = a; a = b; b = tmp; }
    n = clamp(Math.round(n), 2, 32);
    if (kind === 'fade') {
      for (var i = 0; i < n; i++) out.push([a[0], a[1], a[2], 1 - i / (n - 1)]);
      return out;
    }
    var A = rgbToOklab(a[0], a[1], a[2]), B = rgbToOklab(b[0], b[1], b[2]);
    for (var j = 0; j < n; j++) {
      var w = j / (n - 1), c = j === 0 ? a : j === n - 1 ? b : oklabToRgb(A[0] + (B[0] - A[0]) * w, A[1] + (B[1] - A[1]) * w, A[2] + (B[2] - A[2]) * w);
      out.push([c[0], c[1], c[2], 1]);
    }
    return out;
  }
  /** colour index of a pixel at position t (0..1): stepped, with ordered dither between the two neighbouring tones */
  function gradientIndex(t, n, x, y, pattern) {
    var pos = clamp(t, 0, 1) * (n - 1), i0 = Math.floor(pos), frac = pos - i0;
    if (i0 >= n - 1) return n - 1;
    if (pattern === 'none') return frac >= 0.5 ? i0 + 1 : i0;
    return frac > ditherThreshold(pattern, x, y, 0) ? i0 + 1 : i0;
  }
  function pixelGradient(tex, x, y, event, uvTag) {
    var P = G.Painter;
    P.brushChanges = true;
    tex.edit(function (canvas) {
      var cur = P.current, ctx = cur.ctx, off = cur.offset || [0, 0];
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.drawImage(cur.clear, 0, 0);
      var max = (G.settings && G.settings.opacity_range && G.settings.opacity_range.value == '255') ? 255 : 100;
      var opacity = G.BarItems.slider_brush_opacity.get() / max;
      var cols = gradientColours(event), n = cols.length, pattern = opt('grad.dither', 'none'), radial = opt('grad.shape', 'linear') === 'radial';
      function draw(sx, sy, ex, ey, faceTag) {
        var dx = ex - sx, dy = ey - sy;
        if (event && (event.shiftKey || (G.Pressing && G.Pressing.overrides && G.Pressing.overrides.shift))) {
          // same 8-direction snapping as Blockbench's gradient
          var len = Math.sqrt(dx * dx + dy * dy), q = Math.abs(dx) / Math.max(1e-9, Math.abs(dy));
          if (q < 0.25) { dx = 0; dy = len * Math.sign(dy || 1); }
          else if (q < 0.75) { var u = Math.round(len / 2.2361); dx = u * Math.sign(dx); dy = u * 2 * Math.sign(dy); }
          else if (q < 1.5) { var u2 = Math.round(Math.sqrt(len * len / 2)); dx = u2 * Math.sign(dx); dy = u2 * Math.sign(dy); }
          else if (q < 3) { var u3 = Math.round(len / 2.2361); dy = u3 * Math.sign(dy); dx = u3 * 2 * Math.sign(dx); }
          else { dx = len * Math.sign(dx || 1); dy = 0; }
        }
        var rx0, ry0, rx1, ry1;
        if (faceTag) { var r = P.setupRectFromFace(faceTag, tex); rx0 = Math.floor(r[0]); ry0 = Math.floor(r[1]); rx1 = Math.ceil(r[2]); ry1 = Math.ceil(r[3]); }
        else { var bb = tex.selection.getBoundingRect(true); rx0 = bb.start_x; ry0 = bb.start_y; rx1 = bb.start_x + bb.width; ry1 = bb.start_y + bb.height; }
        var lx0 = Math.max(0, rx0 - off[0]), ly0 = Math.max(0, ry0 - off[1]), lx1 = Math.min(canvas.width, rx1 - off[0]), ly1 = Math.min(canvas.height, ry1 - off[1]);
        if (lx1 <= lx0 || ly1 <= ly0) return [dx, dy];
        var img = ctx.getImageData(lx0, ly0, lx1 - lx0, ly1 - ly0), d = img.data, w = lx1 - lx0;
        var len2 = dx * dx + dy * dy, L = Math.sqrt(len2), cx = sx + 0.5, cy = sy + 0.5;
        for (var py = ly0; py < ly1; py++) for (var px = lx0; px < lx1; px++) {
          var tx = px + off[0], ty = py + off[1];
          if (faceTag === undefined && tex.selection && tex.selection.allow && !tex.selection.allow(tx, ty)) continue;
          var tt = len2 < 1e-9 ? 0 : radial ? Math.sqrt((tx + 0.5 - cx) * (tx + 0.5 - cx) + (ty + 0.5 - cy) * (ty + 0.5 - cy)) / L : ((tx + 0.5 - cx) * dx + (ty + 0.5 - cy) * dy) / len2;
          var c = cols[gradientIndex(tt, n, tx, ty, pattern)], i = ((py - ly0) * w + (px - lx0)) * 4;
          if (P.lock_alpha && d[i + 3] === 0) continue;
          var a = c[3] * opacity;
          if (a <= 0) continue;
          var res = P.combineColors({ r: d[i], g: d[i + 1], b: d[i + 2], a: d[i + 3] / 255 }, { r: c[0], g: c[1], b: c[2], a: 1 }, a);
          d[i] = res.r; d[i + 1] = res.g; d[i + 2] = res.b; d[i + 3] = P.lock_alpha ? d[i + 3] : Math.round(res.a * 255);
        }
        ctx.putImageData(img, lx0, ly0);
        return [dx, dy];
      }
      var dd = draw(P.startPixel[0], P.startPixel[1], x, y, uvTag);
      if (P.mirror_painting) {
        var m = P.getMirrorPaintTargets(tex, x, y, uvTag);
        if (m) {
          var s0 = P.getMirrorPaintTargets(tex, P.startPixel[0], P.startPixel[1], uvTag), el = P.current.element, fc = P.current.face;
          m.forEach(function (f, v) { var b = s0[v]; if (!b) return; P.current.element = f.element; draw(b.x, b.y, f.x, f.y, f.uv_tag); });
          P.current.element = el; P.current.face = fc;
        }
      }
      try { G.Blockbench.setStatusBarText(Math.round(dd[0]) + ' x ' + Math.round(dd[1]) + ', ' + t('cpmplus.art.grad.status', n)); } catch (e) { }
    }, { no_undo: true, use_cache: true });
  }
  function installGradient() {
    var onGrad = function () { return isTool('gradient_tool'); };
    var onPixel = function () { return onGrad() && opt('grad.style', 'smooth') === 'pixel'; };
    grad.style = select('cpmplus_art_grad_style', 'cpmplus.art.grad.style', ['smooth', 'pixel'], 'smooth', 'grad.style', onGrad);
    grad.colors = select('cpmplus_art_grad_colors', 'cpmplus.art.grad.colors', ['two', 'fade', 'ramp'], 'two', 'grad.colors', onPixel);
    grad.steps = slider('cpmplus_art_grad_steps', 'cpmplus.art.grad.steps', 2, 16, 5, 'grad.steps', function () { return onPixel() && opt('grad.colors', 'two') !== 'ramp'; });
    grad.dither = select('cpmplus_art_grad_dither', 'cpmplus.art.grad.dither', ['none', 'bayer2', 'bayer4', 'bayer8'], 'none', 'grad.dither', onPixel);
    grad.shape = select('cpmplus_art_grad_shape', 'cpmplus.art.grad.shape', ['linear', 'radial'], 'linear', 'grad.shape', onPixel);
    var after = 'cpmplus_art_dither_shift';
    [grad.style, grad.colors, grad.steps, grad.dither, grad.shape].forEach(function (it) { place('brush', it, after); after = it.id; });
    var P = G.Painter, orig = P && P.useGradientTool;
    if (typeof orig !== 'function') { warn('Painter.useGradientTool not found: the pixel art gradient is off'); return; }
    var patched = function (tex, x, y, event, uvTag) {
      if (opt('grad.style', 'smooth') !== 'pixel') return orig.apply(this, arguments);
      try { return pixelGradient(tex, x, y, event, uvTag); } catch (e) { warn('pixel gradient failed, using the normal one', e); return orig.apply(this, arguments); }
    };
    P.useGradientTool = patched;
    undoers.push(function () { if (P.useGradientTool === patched) P.useGradientTool = orig; });
  }

  // =================================================================== A6 Variação (noise brush) + Ruído na textura (dialog)
  var noise = {};
  /**
   * The varied colour of one pixel (no blending) or null to keep it. r1 = coverage roll, r2 = direction, r3 = amount.
   * mode: light (hue-shifted lighter / darker) | ramp (one step of the texture tones) | hue | rgb | mix (colour 1 / 2)
   */
  function noiseColour(rgb, mode, amount, cover, r1, r2, r3, fam, ramps) {
    if (r1 >= cover) return null;
    var dir = r2 < 0.5 ? -1 : 1;
    switch (mode) {
      case 'ramp': { var s = rampStep(rgb, dir, fam, ramps); return s && s.c ? s.c : null; }
      case 'hue': { var c = rgbToHsv(rgb[0], rgb[1], rgb[2]); return hsvToRgb(c.h + (r2 * 2 - 1) * amount * 60, c.s, c.v); }
      case 'rgb': {
        var f = amount * 80;
        return [byte(rgb[0] + (hash3(r3 * 1e5 | 0, 1, 7) * 2 - 1) * f), byte(rgb[1] + (hash3(r3 * 1e5 | 0, 2, 7) * 2 - 1) * f), byte(rgb[2] + (hash3(r3 * 1e5 | 0, 3, 7) * 2 - 1) * f)];
      }
      default: return hueShade(rgb, dir, amount * (0.35 + 0.65 * r3) * 0.6);
    }
  }
  function noisePixel(x, y, px, alpha, o) {
    var mode = opt('noise.mode', 'light');
    if (mode !== 'mix' && px.a <= 0) return px;
    if (mode === 'mix' && G.Painter.lock_alpha && px.a <= 0) return px;
    var rec = strokeRecord(x, y, px, true), w = clamp(alpha * (o && o.opacity !== undefined ? o.opacity : 1), 0, 1);
    if (w <= rec.w + 1e-6) return px;
    rec.w = w;
    var amount = (G.BarItems.cpmplus_art_noise_amount ? G.BarItems.cpmplus_art_noise_amount.get() : 25) / 100;
    var cover = (G.BarItems.cpmplus_art_noise_cover ? G.BarItems.cpmplus_art_noise_cover.get() : 60) / 100;
    var s = stroke.seed, r1 = hash3(x, y, s), r2 = hash3(x, y, s + 7), r3 = hash3(x, y, s + 13);
    if (mode === 'mix') {
      if (r1 >= cover) return px;
      var c = rgbObj(G.ColorPanel.get(r2 >= 0.5));
      var res = G.Painter.combineColors({ r: rec.r, g: rec.g, b: rec.b, a: rec.a }, c, w);
      if (G.Painter.lock_alpha) res.a = rec.a;
      return res;
    }
    if (mode === 'ramp' && !stroke.ramps) stroke.ramps = savedRampColours();
    var target = noiseColour([rec.r, rec.g, rec.b], mode, amount, cover, r1, r2, r3, mode === 'ramp' ? strokeFamilies(o && o.texture) : null, stroke.ramps);
    if (!target) return px;
    return finish(px, rec, target, w);
  }
  function installNoise() {
    var b = brushBase();
    b.onStrokeStart = function (o) { beginStroke(o); };
    b.changePixel = function (x, y, px, alpha, o) { return noisePixel(x, y, px, alpha, o); };
    noise.tool = makeTool('cpmplus_art_noise', 'cpmplus.art.noise', 'grain', b);
    var on = function () { return isTool('cpmplus_art_noise'); };
    noise.mode = select('cpmplus_art_noise_mode', 'cpmplus.art.noise.mode', ['light', 'ramp', 'hue', 'mix'], 'light', 'noise.mode', on);
    noise.amount = slider('cpmplus_art_noise_amount', 'cpmplus.art.noise.amount', 1, 100, 25, 'noise.amount', function () { return on() && ['light', 'hue'].indexOf(opt('noise.mode', 'light')) >= 0; });
    noise.cover = slider('cpmplus_art_noise_cover', 'cpmplus.art.noise.cover', 1, 100, 60, 'noise.cover', on);
    place('tools', noise.tool, 'cpmplus_art_dither');
    var after = 'cpmplus_art_grad_shape';
    [noise.mode, noise.amount, noise.cover].forEach(function (it) { place('brush', it, after); after = it.id; });
  }

  // ---- texture-wide helpers (dialogs): region masks, preview, one undo
  function currentTexture() { return G.Texture && (G.Texture.selected || (G.Texture.getDefault && G.Texture.getDefault())) || null; }
  /** cubes of the outliner selection (selected cubes + cubes inside selected groups) */
  function selectedCubes() {
    var out = [], seen = {};
    function add(c) { if (c && G.Cube && c instanceof G.Cube && !seen[c.uuid]) { seen[c.uuid] = 1; out.push(c); } }
    ((G.Outliner && G.Outliner.selected) || []).forEach(add);
    var gs = (G.Group && (G.Group.multi_selected || (G.Group.selected ? [].concat(G.Group.selected) : []))) || [];
    gs.forEach(function (g) { if (g && typeof g.forEachChild === 'function') g.forEachChild(add, G.Cube, true); });
    return out;
  }
  /** where a texture-wide edit applies: all | selection | parts -> function(x, y) bool (texture pixels) */
  function regionMask(tex, where) {
    if (where === 'selection') {
      var sel = tex.selection;
      if (sel && sel.override === null && typeof sel.allow === 'function') return function (x, y) { return !!sel.allow(x, y); };
      return function () { return true; };
    }
    if (where === 'parts') {
      var cubes = selectedCubes(), w = tex.width, mask = new Uint8Array(w * tex.height);
      var fx = tex.width / (tex.getUVWidth ? tex.getUVWidth() : tex.width), fy = tex.display_height / (tex.getUVHeight ? tex.getUVHeight() : tex.display_height);
      cubes.forEach(function (c) {
        Object.keys(c.faces || {}).forEach(function (k) {
          var f = c.faces[k]; if (!f || !f.uv) return;
          // a face without its own texture uses the project's default one (CPM: the skin)
          var ft = typeof f.getTexture === 'function' ? f.getTexture() : null;
          if (ft && ft !== tex) return;
          if (!ft && f.texture === false) return;
          var x0 = Math.floor(Math.min(f.uv[0], f.uv[2]) * fx), x1 = Math.ceil(Math.max(f.uv[0], f.uv[2]) * fx);
          var y0 = Math.floor(Math.min(f.uv[1], f.uv[3]) * fy), y1 = Math.ceil(Math.max(f.uv[1], f.uv[3]) * fy);
          for (var y = Math.max(0, y0); y < Math.min(tex.height, y1); y++) for (var x = Math.max(0, x0); x < Math.min(w, x1); x++) mask[y * w + x] = 1;
        });
      });
      return function (x, y) { return x >= 0 && y >= 0 && x < w && mask[y * w + x] === 1; };
    }
    return function () { return true; };
  }
  /** pixels of the active canvas of a texture (+ layer offset) */
  function grab(tex) {
    var info = textureCanvasInfo(tex);
    return { info: info, data: info.ctx.getImageData(0, 0, info.canvas.width, info.canvas.height), off: info.offset || [0, 0] };
  }
  /** shows pixels on the texture without an undo entry (live preview) */
  function previewPut(tex, imageData) {
    G.Painter.edit(tex, function () { G.Painter.current.ctx.putImageData(imageData, 0, 0); }, { no_undo: true, use_cache: true });
  }
  /** writes pixels as ONE undo entry */
  function commitPut(tex, imageData, name) {
    var layered = tex.layers_enabled && tex.layers && tex.layers[0];
    G.Undo.initEdit(layered ? { layers: [tex.getActiveLayer()], bitmap: true } : { textures: [tex], bitmap: true });
    G.Painter.edit(tex, function () { G.Painter.current.ctx.putImageData(imageData, 0, 0); }, { no_undo: true });
    G.Undo.finishEdit(name);
  }
  function cloneImageData(d) { return new ImageData(new Uint8ClampedArray(d.data), d.width, d.height); }
  /** the texture noise on a copy of the pixels: o = {mode, amount 0..1, cover 0..1, seed, where} -> {data, changed} */
  function noiseImage(tex, src, off, o) {
    var out = cloneImageData(src), d = out.data, w = out.width, mask = regionMask(tex, o.where), changed = 0;
    var fam = o.mode === 'ramp' ? buildFamilies(coloursOf(src, 4096)) : null, ramps = o.mode === 'ramp' ? savedRampColours() : null;
    for (var y = 0; y < out.height; y++) for (var x = 0; x < w; x++) {
      var i = (y * w + x) * 4; if (d[i + 3] === 0) continue;
      var tx = x + off[0], ty = y + off[1]; if (!mask(tx, ty)) continue;
      var c = noiseColour([d[i], d[i + 1], d[i + 2]], o.mode, o.amount, o.cover, hash3(tx, ty, o.seed), hash3(tx, ty, o.seed + 7), hash3(tx, ty, o.seed + 13), fam, ramps);
      if (!c || (c[0] === d[i] && c[1] === d[i + 1] && c[2] === d[i + 2])) continue;
      d[i] = c[0]; d[i + 1] = c[1]; d[i + 2] = c[2]; changed++;
    }
    return { data: out, changed: changed };
  }
  function openNoise() {
    var tex = currentTexture();
    if (!tex) { G.Blockbench.showQuickMessage(t('cpmplus.art.err.noTexture'), 2500); return null; }
    var g = grab(tex), committed = false;
    var model = { mode: opt('tnoise.mode', 'light'), amount: opt('tnoise.amount', 20), cover: opt('tnoise.cover', 70), seed: opt('tnoise.seed', 1), where: 'all', live: true, note: '',
      hasSel: !!(tex.selection && tex.selection.override === null), hasParts: selectedCubes().length > 0 };
    if (model.hasParts) model.where = 'parts'; else if (model.hasSel) model.where = 'selection';
    function params(vm) { return { mode: vm.mode, amount: (+vm.amount) / 100, cover: (+vm.cover) / 100, seed: +vm.seed, where: vm.where }; }
    function run(vm, final) {
      var r = noiseImage(tex, g.data, g.off, params(vm));
      vm.note = t('cpmplus.art.tnoise.count', r.changed);
      if (final) return r;
      if (vm.live) previewPut(tex, r.data); else previewPut(tex, g.data);
      return r;
    }
    var dlg = new G.Dialog({
      id: 'cpmplus_art_noise', title: t('cpmplus.art.tnoise.title'), width: 560,
      component: {
        data: function () { return model; },
        mounted: function () { run(this); },
        methods: {
          upd: function () { setOpt('tnoise.mode', this.mode); state.opt['tnoise.amount'] = +this.amount; state.opt['tnoise.cover'] = +this.cover; state.opt['tnoise.seed'] = +this.seed; saveState(); run(this); },
          reseed: function () { this.seed = 1 + Math.floor(Math.random() * 99999); this.upd(); }
        },
        template: [
          '<div>',
          '<p class="cpmplus_art_intro">' + esc(t('cpmplus.art.tnoise.intro')) + '</p>',
          '<div class="cpmplus_art_grid">',
          '<label>' + esc(t('cpmplus.art.tnoise.mode')) + '</label><select v-model="mode" @change="upd">',
          ['light', 'ramp', 'hue', 'rgb'].map(function (k) { return '<option value="' + k + '">' + esc(t('cpmplus.art.tnoise.mode.' + k)) + '</option>'; }).join(''),
          '</select>',
          '<label>' + esc(t('cpmplus.art.tnoise.amount')) + ' <b>{{amount}}</b></label><input type="range" min="1" max="100" v-model="amount" @input="upd">',
          '<label>' + esc(t('cpmplus.art.tnoise.cover')) + ' <b>{{cover}}%</b></label><input type="range" min="1" max="100" v-model="cover" @input="upd">',
          '<label>' + esc(t('cpmplus.art.tnoise.where')) + '</label><select v-model="where" @change="upd">',
          '<option value="all">' + esc(t('cpmplus.art.tnoise.where.all')) + '</option>',
          '<option value="selection" :disabled="!hasSel">' + esc(t('cpmplus.art.tnoise.where.selection')) + '</option>',
          '<option value="parts" :disabled="!hasParts">' + esc(t('cpmplus.art.tnoise.where.parts')) + '</option>',
          '</select>',
          '<label>' + esc(t('cpmplus.art.tnoise.seed')) + '</label><div class="cpmplus_art_row" style="margin:0"><input type="number" min="1" max="99999" v-model="seed" @change="upd" style="width:90px"><button @click="reseed">' + esc(t('cpmplus.art.tnoise.reseed')) + '</button></div>',
          '<label>' + esc(t('cpmplus.art.tnoise.live')) + '</label><input type="checkbox" v-model="live" @change="upd">',
          '</div>',
          '<p class="cpmplus_art_note">{{note}}</p>',
          '</div>'
        ].join('')
      },
      onConfirm: function () {
        var vm = dlg.content_vue, r = run(vm, true);
        previewPut(tex, g.data);// back to the original first: the undo entry starts from it
        if (r.changed) commitPut(tex, r.data, t('cpmplus.art.tnoise.title'));
        committed = true;
      },
      onCancel: function () { if (!committed) previewPut(tex, g.data); }
    });
    dlg.show();
    return dlg;
  }

  // =================================================================== A7 Trocar cores (a whole colour family, shading kept)
  /** families of an ImageData with their pixel counts, biggest first: [{colors:[[r,g,b]..], count, keys:{key:1}}] */
  function familiesWithCounts(src) {
    var d = src.data, counts = {};
    for (var i = 0; i < d.length; i += 4) { if (d[i + 3] === 0) continue; var k = ckey(d[i], d[i + 1], d[i + 2]); counts[k] = (counts[k] || 0) + 1; }
    var cols = Object.keys(counts).map(function (k) { k = +k; return [(k >> 16) & 255, (k >> 8) & 255, k & 255]; });
    var fam = buildFamilies(cols);
    return fam.families.map(function (f) {
      var keys = {}, n = 0;
      f.forEach(function (c) { var k = ckey(c[0], c[1], c[2]); keys[k] = 1; n += counts[k]; });
      return { colors: f, count: n, keys: keys };
    }).sort(function (a, b) { return b.count - a.count; });
  }
  /** the pixel-weighted middle tone of a family (OKLCH) */
  function familyMid(f) { return toLch.apply(null, f.colors[Math.floor((f.colors.length - 1) / 2)]); }
  /** o = {dh degrees, sat 0..3 (1 = same), dl -0.5..0.5 (OKLab L), where} -> {data, changed} */
  function swapImage(tex, src, off, fam, o) {
    var out = cloneImageData(src), d = out.data, w = out.width, mask = regionMask(tex, o.where), changed = 0, cache = {};
    for (var y = 0; y < out.height; y++) for (var x = 0; x < w; x++) {
      var i = (y * w + x) * 4; if (d[i + 3] === 0) continue;
      var k = ckey(d[i], d[i + 1], d[i + 2]); if (!fam.keys[k]) continue;
      if (!mask(x + off[0], y + off[1])) continue;
      var c = cache[k];
      if (!c) { var l = toLch(d[i], d[i + 1], d[i + 2]); c = cache[k] = lchToRgb(l.L + (o.dl || 0), l.C * (o.sat === undefined ? 1 : o.sat), (l.H + (o.dh || 0) + 360) % 360); }
      if (c[0] === d[i] && c[1] === d[i + 1] && c[2] === d[i + 2]) continue;
      d[i] = c[0]; d[i + 1] = c[1]; d[i + 2] = c[2]; changed++;
    }
    return { data: out, changed: changed };
  }
  /** hue / chroma / lightness change that moves the family's middle tone onto a target colour */
  function shiftToward(f, rgb) {
    var m = familyMid(f), t2 = toLch(rgb[0], rgb[1], rgb[2]);
    var dh = m.C < 0.02 || t2.C < 0.02 ? 0 : ((t2.H - m.H + 540) % 360) - 180;
    return { dh: Math.round(dh), sat: m.C < 0.02 ? 1 : clamp(t2.C / m.C, 0, 3), dl: clamp(t2.L - m.L, -0.5, 0.5) };
  }
  function openSwap() {
    var tex = currentTexture();
    if (!tex) { G.Blockbench.showQuickMessage(t('cpmplus.art.err.noTexture'), 2500); return null; }
    var g = grab(tex), fams = familiesWithCounts(g.data), committed = false;
    var model = {
      fams: fams.slice(0, 16).map(function (f, i) { return { i: i, count: f.count, colors: (f.colors.length > 18 ? f.colors.filter(function (c, j) { return j % Math.ceil(f.colors.length / 18) === 0; }) : f.colors).map(hex) }; }),
      pick: fams.length ? 0 : -1, dh: 0, sat: 100, dl: 0, where: 'all', live: true, note: '',
      hasSel: !!(tex.selection && tex.selection.override === null), hasParts: selectedCubes().length > 0
    };
    function params(vm) { return { dh: +vm.dh, sat: (+vm.sat) / 100, dl: (+vm.dl) / 100, where: vm.where }; }
    function run(vm, final) {
      if (vm.pick < 0) { vm.note = t('cpmplus.art.swap.none'); return { data: g.data, changed: 0 }; }
      var r = swapImage(tex, g.data, g.off, fams[vm.pick], params(vm));
      vm.note = t('cpmplus.art.swap.count', r.changed);
      if (!final) previewPut(tex, vm.live ? r.data : g.data);
      return r;
    }
    var dlg = new G.Dialog({
      id: 'cpmplus_art_swap', title: t('cpmplus.art.swap.title'), width: 640,
      component: {
        data: function () { return model; },
        mounted: function () { if (!this.fams.length) this.note = t('cpmplus.art.swap.empty'); else run(this); },
        methods: {
          upd: function () { run(this); },
          choose: function (i) { this.pick = i; this.dh = 0; this.sat = 100; this.dl = 0; run(this); },
          toColor1: function () {
            if (this.pick < 0) return;
            var s = shiftToward(fams[this.pick], primary());
            this.dh = s.dh; this.sat = Math.round(s.sat * 100); this.dl = Math.round(s.dl * 100); run(this);
          },
          reset: function () { this.dh = 0; this.sat = 100; this.dl = 0; run(this); }
        },
        template: [
          '<div>',
          '<p class="cpmplus_art_intro">' + esc(t('cpmplus.art.swap.intro')) + '</p>',
          '<div class="cpmplus_art_fams">',
          '<div v-for="f in fams" class="cpmplus_art_line cpmplus_art_pickable" :class="{active: f.i===pick}" @click="choose(f.i)">',
          '<div class="cpmplus_art_swatches"><div v-for="c in f.colors" class="cpmplus_art_sw" :style="{background:c}" :title="c"></div></div>',
          '<span class="cpmplus_art_hint">{{f.count}} px</span></div></div>',
          '<div class="cpmplus_art_grid">',
          '<label>' + esc(t('cpmplus.art.swap.hue')) + ' <b>{{dh}}°</b></label><input type="range" min="-180" max="180" v-model="dh" @input="upd">',
          '<label>' + esc(t('cpmplus.art.swap.sat')) + ' <b>{{sat}}%</b></label><input type="range" min="0" max="200" v-model="sat" @input="upd">',
          '<label>' + esc(t('cpmplus.art.swap.light')) + ' <b>{{dl}}</b></label><input type="range" min="-50" max="50" v-model="dl" @input="upd">',
          '<label>' + esc(t('cpmplus.art.tnoise.where')) + '</label><select v-model="where" @change="upd">',
          '<option value="all">' + esc(t('cpmplus.art.tnoise.where.all')) + '</option>',
          '<option value="selection" :disabled="!hasSel">' + esc(t('cpmplus.art.tnoise.where.selection')) + '</option>',
          '<option value="parts" :disabled="!hasParts">' + esc(t('cpmplus.art.tnoise.where.parts')) + '</option>',
          '</select>',
          '<label>' + esc(t('cpmplus.art.tnoise.live')) + '</label><input type="checkbox" v-model="live" @change="upd">',
          '</div>',
          '<div class="cpmplus_art_row"><button @click="toColor1">' + esc(t('cpmplus.art.swap.toColor1')) + '</button><button @click="reset">' + esc(t('cpmplus.art.swap.reset')) + '</button></div>',
          '<p class="cpmplus_art_note">{{note}}</p>',
          '</div>'
        ].join('')
      },
      onConfirm: function () {
        var vm = dlg.content_vue, r = run(vm, true);
        previewPut(tex, g.data);
        if (r.changed) commitPut(tex, r.data, t('cpmplus.art.swap.title'));
        committed = true;
      },
      onCancel: function () { if (!committed) previewPut(tex, g.data); }
    });
    dlg.show();
    return dlg;
  }

  // =================================================================== A8 Sombra automática (light, contact shadow and edges for cubes)
  function deg(v) { return (v || 0) * Math.PI / 180; }
  /** rest-pose matrix: node local space -> model space, from origin / rotation only (never the animated pose) */
  function restMatrix(node) {
    var T = G.THREE, chain = [], n = node;
    while (n && n !== 'root' && n.origin) { chain.unshift(n); n = n.parent; }
    var m = new T.Matrix4();
    for (var i = 0; i < chain.length; i++) {
      var c = chain[i], p = c.parent, po = (p && p !== 'root' && p.origin) ? p.origin : [0, 0, 0], r = c.rotation || [0, 0, 0];
      var local = new T.Matrix4().makeRotationFromEuler(new T.Euler(deg(r[0]), deg(r[1]), deg(r[2]), 'ZYX'));
      local.setPosition(c.origin[0] - po[0], c.origin[1] - po[1], c.origin[2] - po[2]);
      m.multiply(local);
    }
    return m;
  }
  var FACE_NORMAL = { east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0], south: [0, 0, 1], north: [0, 0, -1] };
  function faceOrder() { return (G.Canvas && G.Canvas.face_order) || ['east', 'west', 'up', 'down', 'south', 'north']; }
  function shadeCubes(scope) {
    var all = ((G.Cube && G.Cube.all) || []).filter(function (c) { return c.export !== false && c.visibility !== false; });
    if (scope === 'all') return all;
    var sel = selectedCubes().filter(function (c) { return all.indexOf(c) >= 0; });
    return sel.length ? sel : all;
  }
  function bilerp(C, a, b) {
    var w0 = (1 - a) * (1 - b), w1 = a * (1 - b), w2 = (1 - a) * b, w3 = a * b;
    return [C[0].x * w0 + C[1].x * w1 + C[2].x * w2 + C[3].x * w3, C[0].y * w0 + C[1].y * w1 + C[2].y * w2 + C[3].y * w3, C[0].z * w0 + C[1].z * w1 + C[2].z * w2 + C[3].z * w3];
  }
  /**
   * The texels of the cubes' faces in 3D (rest pose): {tex: {uuid: {tex, map: Map(idx -> {x, y, uses: [{p, n, edge, cube}]})}},
   * boxes: [{cube, inv, bmin, bmax, aabb}], ymin, ymax}. edge bits: 1 top border, 2 bottom border, 4 side border.
   */
  function collectTexels(cubes) {
    var T = G.THREE, FO = faceOrder(), tex = {}, boxes = [], ymin = Infinity, ymax = -Infinity;
    cubes.forEach(function (cube) {
      if (!cube.mesh || !cube.mesh.geometry || !cube.mesh.geometry.attributes || !cube.mesh.geometry.attributes.position) return;
      var pos = cube.mesh.geometry.attributes.position.array, M = restMatrix(cube), o = cube.origin, inf = cube.inflate || 0;
      var bmin = [Math.min(cube.from[0], cube.to[0]) - o[0] - inf, Math.min(cube.from[1], cube.to[1]) - o[1] - inf, Math.min(cube.from[2], cube.to[2]) - o[2] - inf];
      var bmax = [Math.max(cube.from[0], cube.to[0]) - o[0] + inf, Math.max(cube.from[1], cube.to[1]) - o[1] + inf, Math.max(cube.from[2], cube.to[2]) - o[2] + inf];
      var amin = [Infinity, Infinity, Infinity], amax = [-Infinity, -Infinity, -Infinity];
      for (var ci = 0; ci < 8; ci++) {
        var v = new T.Vector3(ci & 1 ? bmax[0] : bmin[0], ci & 2 ? bmax[1] : bmin[1], ci & 4 ? bmax[2] : bmin[2]).applyMatrix4(M);
        amin = [Math.min(amin[0], v.x), Math.min(amin[1], v.y), Math.min(amin[2], v.z)]; amax = [Math.max(amax[0], v.x), Math.max(amax[1], v.y), Math.max(amax[2], v.z)];
      }
      boxes.push({ cube: cube, inv: M.clone().invert(), bmin: bmin, bmax: bmax, amin: amin, amax: amax });
      var rot = new T.Matrix3().setFromMatrix4(M);
      FO.forEach(function (fkey, fi) {
        var face = cube.faces && cube.faces[fkey]; if (!face || !face.uv) return;
        var t3 = typeof face.getTexture === 'function' ? face.getTexture() : null; if (!t3) return;
        if ((t3.frameCount || 1) > 1) return;
        var sx = t3.width / (t3.getUVWidth ? t3.getUVWidth() : t3.width), sy = t3.display_height / (t3.getUVHeight ? t3.getUVHeight() : t3.display_height);
        var uv = face.uv, x1 = uv[0] * sx, y1 = uv[1] * sy, x2 = uv[2] * sx, y2 = uv[3] * sy;
        if (Math.abs(x2 - x1) < 1 - 1e-6 || Math.abs(y2 - y1) < 1 - 1e-6) return;
        var V = [];
        for (var k = 0; k < 4; k++) { var b = (fi * 4 + k) * 3; V.push(new T.Vector3(pos[b], pos[b + 1], pos[b + 2]).applyMatrix4(M)); }
        var perm = [0, 1, 2, 3], r = ((face.rotation || 0) % 360 + 360) % 360;
        while (r > 0) { var a0 = perm[0]; perm[0] = perm[2]; perm[2] = perm[3]; perm[3] = perm[1]; perm[1] = a0; r -= 90; }
        var C = []; for (var q = 0; q < 4; q++) C[perm[q]] = V[q];
        var nv = new T.Vector3().fromArray(FACE_NORMAL[fkey]).applyMatrix3(rot).normalize(), n = [nv.x, nv.y, nv.z];
        var entry = tex[t3.uuid] || (tex[t3.uuid] = { tex: t3, map: new Map() });
        var minx = Math.min(x1, x2), maxx = Math.max(x1, x2), miny = Math.min(y1, y2), maxy = Math.max(y1, y2), W = x2 - x1, H = y2 - y1;
        for (var py = Math.max(0, Math.floor(miny)); py < Math.min(t3.display_height, Math.ceil(maxy)); py++) {
          var cy = py + 0.5; if (cy <= miny + 1e-9 || cy >= maxy - 1e-9) continue;
          for (var px = Math.max(0, Math.floor(minx)); px < Math.min(t3.width, Math.ceil(maxx)); px++) {
            var cx = px + 0.5; if (cx <= minx + 1e-9 || cx >= maxx - 1e-9) continue;
            var aa = (cx - x1) / W, bb2 = (cy - y1) / H, P = bilerp(C, aa, bb2), edge = 0;
            [[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(function (d) {
              var ncx = cx + d[0], ncy = cy + d[1];
              if (ncx > minx && ncx < maxx && ncy > miny && ncy < maxy) return;
              var Pn = bilerp(C, aa + d[0] / W, bb2 + d[1] / H), dx = Pn[0] - P[0], dy = Pn[1] - P[1], dz = Pn[2] - P[2], len = Math.sqrt(dx * dx + dy * dy + dz * dz) || 1;
              edge |= dy > 0.5 * len ? 1 : dy < -0.5 * len ? 2 : 4;
            });
            var idx = py * t3.width + px, e = entry.map.get(idx);
            if (!e) { e = { x: px, y: py, uses: [] }; entry.map.set(idx, e); }
            e.uses.push({ p: P, n: n, edge: edge, cube: cube });
            if (P[1] < ymin) ymin = P[1]; if (P[1] > ymax) ymax = P[1];
          }
        }
      });
    });
    return { tex: tex, boxes: boxes, ymin: ymin, ymax: ymax };
  }
  /** 12 directions of the upper hemisphere (z up), fixed so the result never changes between runs */
  var HEMI = (function () {
    var out = [], n = 12, ga = Math.PI * (3 - Math.sqrt(5));
    for (var i = 0; i < n; i++) { var z = 0.25 + 0.75 * (i + 0.5) / n, r = Math.sqrt(1 - z * z), a = i * ga; out.push([Math.cos(a) * r, Math.sin(a) * r, z]); }
    return out;
  })();
  /** how covered a point is by the OTHER cubes (0..1): rays of length 'range' in the hemisphere of the normal */
  function occlusion(p, n, own, boxes, range) {
    var T = G.THREE, o = [p[0] + n[0] * 0.01, p[1] + n[1] * 0.01, p[2] + n[2] * 0.01];
    var near = boxes.filter(function (b) {
      return b.cube !== own && o[0] > b.amin[0] - range && o[0] < b.amax[0] + range && o[1] > b.amin[1] - range && o[1] < b.amax[1] + range && o[2] > b.amin[2] - range && o[2] < b.amax[2] + range;
    });
    if (!near.length) return 0;
    var a = Math.abs(n[0]) < 0.9 ? [1, 0, 0] : [0, 1, 0];
    var tx = [a[1] * n[2] - a[2] * n[1], a[2] * n[0] - a[0] * n[2], a[0] * n[1] - a[1] * n[0]], tl = Math.sqrt(tx[0] * tx[0] + tx[1] * tx[1] + tx[2] * tx[2]);
    tx = [tx[0] / tl, tx[1] / tl, tx[2] / tl];
    var by = [n[1] * tx[2] - n[2] * tx[1], n[2] * tx[0] - n[0] * tx[2], n[0] * tx[1] - n[1] * tx[0]];
    var locals = near.map(function (b) { var lo = new T.Vector3(o[0], o[1], o[2]).applyMatrix4(b.inv); return { b: b, o: [lo.x, lo.y, lo.z] }; });
    // a point inside another cube is fully covered (hidden texel)
    for (var k = 0; k < locals.length; k++) {
      var L = locals[k];
      if (L.o[0] > L.b.bmin[0] && L.o[0] < L.b.bmax[0] && L.o[1] > L.b.bmin[1] && L.o[1] < L.b.bmax[1] && L.o[2] > L.b.bmin[2] && L.o[2] < L.b.bmax[2]) return 1;
    }
    var hits = 0;
    HEMI.forEach(function (h) {
      var d = [tx[0] * h[0] + by[0] * h[1] + n[0] * h[2], tx[1] * h[0] + by[1] * h[1] + n[1] * h[2], tx[2] * h[0] + by[2] * h[1] + n[2] * h[2]];
      for (var j = 0; j < locals.length; j++) {
        var b = locals[j].b, lo = locals[j].o, dv = new T.Vector3(d[0], d[1], d[2]).transformDirection(b.inv), dl = [dv.x, dv.y, dv.z];
        var tmin = 0, tmax = range, ok = true;
        for (var ax = 0; ax < 3 && ok; ax++) {
          if (Math.abs(dl[ax]) < 1e-9) { if (lo[ax] < b.bmin[ax] || lo[ax] > b.bmax[ax]) ok = false; continue; }
          var t1 = (b.bmin[ax] - lo[ax]) / dl[ax], t2 = (b.bmax[ax] - lo[ax]) / dl[ax];
          if (t1 > t2) { var tt = t1; t1 = t2; t2 = tt; }
          if (t1 > tmin) tmin = t1; if (t2 < tmax) tmax = t2;
          if (tmin > tmax) ok = false;
        }
        if (ok) { hits++; return; }
      }
    });
    return hits / HEMI.length;
  }
  var LIGHT = { top: [0, 1, 0], top_front: [0, 0.8, -0.6], top_left: [0.6, 0.8, 0] };
  /** steps (+ lighter, - darker) of one texel use */
  function texelSteps(u, o, yrange, aoCache, boxes) {
    var s = 0, Lv = LIGHT[o.light] || LIGHT.top;
    if (o.face) s += o.face * (u.n[0] * Lv[0] + u.n[1] * Lv[1] + u.n[2] * Lv[2]);
    if (o.vertical && yrange[1] > yrange[0] + 1e-6) s += o.vertical * (2 * (u.p[1] - yrange[0]) / (yrange[1] - yrange[0]) - 1);
    if (o.ao) {
      var key = (o.range || 3) + '|' + u.p.join(',') + '|' + u.n.join(','), occ = aoCache.get(key);
      if (occ === undefined) { occ = occlusion(u.p, u.n, u.cube, boxes, o.range || 3); aoCache.set(key, occ); }
      s -= o.ao * occ;
    }
    if (o.edges === 'bevel') { if (u.edge & 1) s += o.edge || 1; if (u.edge & 2) s -= o.edge || 1; }
    else if (o.edges === 'contour' && u.edge) s -= o.edge || 1;
    return s;
  }
  function stepColour(rgb, k, tone, stepK, fam) {
    var c = rgb, dir = k > 0 ? 1 : -1;
    for (var i = 0; i < Math.abs(k); i++) {
      if (tone === 'ramp') {
        var s = rampStep(c, dir, fam, null);
        if (s && s.c) { c = s.c; continue; }
        if (s && s.end) break;
      }
      c = hueShade(c, dir, stepK);
    }
    return c;
  }
  /**
   * o = {scope, light, face, vertical, ao, range, edges, edge, tone 'hue'|'ramp', step (%), dither}
   * table = collectTexels(...) -> {byTex: {uuid: {tex, data, changed}}, texels}
   */
  function autoShadeImages(table, originals, o, aoCache) {
    var out = {}, texels = 0, yr = [table.ymin, table.ymax], stepK = clamp((o.step || 12) / 100, 0.02, 0.6);
    Object.keys(table.tex).forEach(function (uuid) {
      var e = table.tex[uuid], g = originals[uuid]; if (!g) return;
      var img = cloneImageData(g.data), d = img.data, w = img.width, changed = 0, off = g.off;
      var fam = o.tone === 'ramp' ? buildFamilies(coloursOf(g.data, 4096)) : null;
      e.map.forEach(function (tx) {
        var lx = tx.x - off[0], ly = tx.y - off[1];
        if (lx < 0 || ly < 0 || lx >= w || ly >= img.height) return;
        var i = (ly * w + lx) * 4; if (d[i + 3] === 0) return;
        var s = 0; tx.uses.forEach(function (u) { s += texelSteps(u, o, yr, aoCache, table.boxes); }); s /= tx.uses.length;
        texels++;
        var fl = Math.floor(s), fr = s - fl, thr = o.dither && o.dither !== 'none' ? ditherThreshold(o.dither, tx.x, tx.y, 0) : 0.5;
        var k = fl + (fr > thr ? 1 : 0);
        if (!k) return;
        var c = stepColour([d[i], d[i + 1], d[i + 2]], k, o.tone, stepK, fam);
        if (c[0] === d[i] && c[1] === d[i + 1] && c[2] === d[i + 2]) return;
        d[i] = c[0]; d[i + 1] = c[1]; d[i + 2] = c[2]; changed++;
      });
      out[uuid] = { tex: e.tex, data: img, changed: changed };
    });
    return { byTex: out, texels: texels };
  }
  /** one undo entry for several textures */
  function commitMany(list, name) {
    var tex = [], layers = [];
    list.forEach(function (r) { if (r.tex.layers_enabled && r.tex.layers && r.tex.layers[0]) layers.push(r.tex.getActiveLayer()); else tex.push(r.tex); });
    var u = { bitmap: true }; if (tex.length) u.textures = tex; if (layers.length) u.layers = layers;
    G.Undo.initEdit(u);
    list.forEach(function (r) { G.Painter.edit(r.tex, function () { G.Painter.current.ctx.putImageData(r.data, 0, 0); }, { no_undo: true }); });
    G.Undo.finishEdit(name);
  }
  function autoShadeDefaults() {
    return { scope: 'selected', light: opt('as.light', 'top'), face: opt('as.face', 1), vertical: opt('as.vertical', 0), ao: opt('as.ao', 1), range: opt('as.range', 3),
      edges: opt('as.edges', 'none'), edge: opt('as.edge', 1), tone: opt('as.tone', 'hue'), step: opt('as.step', 12), dither: opt('as.dither', 'none') };
  }
  function prepareAutoShade(scope) {
    var cubes = shadeCubes(scope), table = collectTexels(cubes), originals = {};
    Object.keys(table.tex).forEach(function (uuid) { var t3 = table.tex[uuid].tex; originals[uuid] = grab(t3); });
    return { cubes: cubes, table: table, originals: originals, ao: new Map() };
  }
  function openAutoShade() {
    var hasSel = selectedCubes().length > 0, prep = prepareAutoShade(hasSel ? 'selected' : 'all'), committed = false, timer = null;
    if (!Object.keys(prep.table.tex).length) { G.Blockbench.showQuickMessage(t('cpmplus.art.as.noCubes'), 3000); return null; }
    var model = Object.assign(autoShadeDefaults(), { scope: hasSel ? 'selected' : 'all', hasSel: hasSel, live: true, note: '', busy: false });
    function params(vm) { return { scope: vm.scope, light: vm.light, face: +vm.face, vertical: +vm.vertical, ao: +vm.ao, range: +vm.range, edges: vm.edges, edge: +vm.edge, tone: vm.tone, step: +vm.step, dither: vm.dither }; }
    function restoreAll() { Object.keys(prep.originals).forEach(function (u) { var g = prep.originals[u]; previewPut(prep.table.tex[u].tex, g.data); }); }
    function run(vm, final) {
      var r = autoShadeImages(prep.table, prep.originals, params(vm), prep.ao), n = 0;
      Object.keys(r.byTex).forEach(function (u) { n += r.byTex[u].changed; });
      vm.note = t('cpmplus.art.as.count', n, r.texels);
      if (!final) { if (vm.live) Object.keys(r.byTex).forEach(function (u) { previewPut(r.byTex[u].tex, r.byTex[u].data); }); else restoreAll(); }
      return r;
    }
    function later(vm) { if (timer) clearTimeout(timer); timer = setTimeout(function () { timer = null; run(vm); }, 90); }
    var opts = function (list, key) { return list.map(function (k) { return '<option value="' + k + '">' + esc(t('cpmplus.art.as.' + key + '.' + k)) + '</option>'; }).join(''); };
    var rng = function (key, min, max, unit) { return '<label title="' + esc(t('cpmplus.art.as.' + key + '.desc')) + '">' + esc(t('cpmplus.art.as.' + key)) + ' <b>{{' + key + '}}' + (unit || '') + '</b></label><input type="range" min="' + min + '" max="' + max + '" v-model="' + key + '" @input="upd">'; };
    var dlg = new G.Dialog({
      id: 'cpmplus_art_autoshade', title: t('cpmplus.art.as.title'), width: 640,
      component: {
        data: function () { return model; },
        mounted: function () { run(this); },
        methods: {
          upd: function () {
            var p = params(this);
            ['light', 'face', 'vertical', 'ao', 'range', 'edges', 'edge', 'tone', 'step', 'dither'].forEach(function (k) { state.opt['as.' + k] = p[k]; });
            saveState(); later(this);
          },
          rescope: function () { restoreAll(); prep = prepareAutoShade(this.scope); this.upd(); }
        },
        template: [
          '<div>',
          '<p class="cpmplus_art_intro">' + esc(t('cpmplus.art.as.intro')) + '</p>',
          '<div class="cpmplus_art_grid">',
          '<label>' + esc(t('cpmplus.art.as.scope')) + '</label><select v-model="scope" @change="rescope"><option value="selected" :disabled="!hasSel">' + esc(t('cpmplus.art.as.scope.selected')) + '</option><option value="all">' + esc(t('cpmplus.art.as.scope.all')) + '</option></select>',
          '<label>' + esc(t('cpmplus.art.as.light')) + '</label><select v-model="light" @change="upd">' + opts(['top', 'top_front', 'top_left'], 'light') + '</select>',
          rng('face', 0, 3), rng('vertical', 0, 3), rng('ao', 0, 3), rng('range', 1, 8, ' px'),
          '<label>' + esc(t('cpmplus.art.as.edges')) + '</label><select v-model="edges" @change="upd">' + opts(['none', 'bevel', 'contour'], 'edges') + '</select>',
          '<template v-if="edges!==\'none\'">' + rng('edge', 1, 2) + '</template>',
          '<label>' + esc(t('cpmplus.art.as.tone')) + '</label><select v-model="tone" @change="upd">' + opts(['hue', 'ramp'], 'tone') + '</select>',
          rng('step', 4, 40, '%'),
          '<label>' + esc(t('cpmplus.art.as.dither')) + '</label><select v-model="dither" @change="upd">' + opts(['none', 'bayer2', 'bayer4'], 'dither') + '</select>',
          '<label>' + esc(t('cpmplus.art.tnoise.live')) + '</label><input type="checkbox" v-model="live" @change="upd">',
          '</div>',
          '<p class="cpmplus_art_note">{{note}}</p>',
          '</div>'
        ].join('')
      },
      onConfirm: function () {
        if (timer) { clearTimeout(timer); timer = null; }
        var r = run(dlg.content_vue, true);
        restoreAll();
        var list = Object.keys(r.byTex).map(function (u) { return r.byTex[u]; }).filter(function (x) { return x.changed; });
        if (list.length) commitMany(list, t('cpmplus.art.as.title'));
        committed = true;
      },
      onCancel: function () { if (timer) { clearTimeout(timer); timer = null; } if (!committed) restoreAll(); }
    });
    dlg.show();
    return dlg;
  }

  // =================================================================== A1 Gerar rampa (dialog) + Minhas rampas
  function primary() { try { return parseHex(G.ColorPanel.get(false)) || [128, 128, 128]; } catch (e) { return [128, 128, 128]; } }
  function secondary() { try { return parseHex(G.ColorPanel.get(true)) || [64, 64, 64]; } catch (e) { return [64, 64, 64]; } }
  function addToPalette(cols) {
    try {
      if (G.StateMemory && G.StateMemory.color_palette_locked) { G.Blockbench.showQuickMessage(t('cpmplus.art.ramp.paletteLocked'), 2500); return 0; }
      var n = 0;
      cols.forEach(function (c) { var h = hex(c); if (G.ColorPanel.palette.indexOf(h) < 0) { G.ColorPanel.palette.push(h); n++; } });
      G.ColorPanel.saveLocalStorages();
      return n;
    } catch (e) { warn('could not add to the palette', e); return 0; }
  }
  function saveRamp(cols, name) {
    var list = cols.map(hex);
    state.ramps.push({ name: name || t('cpmplus.art.ramp.defaultName', state.ramps.length + 1), colors: list });
    state.active = state.ramps.length - 1;
    saveState();
    return state.active;
  }
  function setColour(c, second) { try { G.ColorPanel.set(hex(c), !!second); } catch (e) { warn('could not set the colour', e); } }
  function openRamp() {
    var p = primary(), s = secondary();
    var model = {
      mode: opt('ramp.mode', 'one'), steps: opt('ramp.steps', 7), hue: opt('ramp.hue', 40), contrast: opt('ramp.contrast', 60), sat: opt('ramp.sat', 45),
      a: hex(p), b: hex(s), colors: [], ramps: [], active: state.active, families: [], note: ''
    };
    function regen(vm) {
      var a = parseHex(vm.a) || p, b2 = parseHex(vm.b) || s;
      var r = makeRamp({ mode: vm.mode, a: a, b: b2, steps: +vm.steps, hue: +vm.hue, contrast: (+vm.contrast) / 100, sat: (+vm.sat) / 100 });
      vm.colors = r.map(hex);
      setOpt('ramp.mode', vm.mode); state.opt['ramp.steps'] = +vm.steps; state.opt['ramp.hue'] = +vm.hue; state.opt['ramp.contrast'] = +vm.contrast; state.opt['ramp.sat'] = +vm.sat; saveState();
    }
    function listRamps(vm) { vm.ramps = state.ramps.map(function (r, i) { return { name: r.name, colors: (r.colors || []).slice(), i: i }; }); vm.active = state.active; }
    var dlg = new G.Dialog({
      id: 'cpmplus_art_ramp', title: t('cpmplus.art.ramp.title'), width: 720, singleButton: true,
      component: {
        data: function () { return model; },
        mounted: function () { regen(this); listRamps(this); },
        methods: {
          regen: function () { regen(this); },
          pick: function (c, ev) { setColour(parseHex(c), ev && (ev.button === 2 || ev.shiftKey)); this.note = t('cpmplus.art.ramp.picked', c); },
          toPalette: function () { var n = addToPalette(this.colors.map(parseHex)); this.note = t('cpmplus.art.ramp.added', n); },
          save: function () { saveRamp(this.colors.map(parseHex)); listRamps(this); this.note = t('cpmplus.art.ramp.saved'); },
          useRamp: function (i) { state.active = i; saveState(); this.active = i; this.note = t('cpmplus.art.ramp.activeSet', state.ramps[i] ? state.ramps[i].name : ''); },
          delRamp: function (i) { state.ramps.splice(i, 1); if (state.active >= state.ramps.length) state.active = state.ramps.length - 1; saveState(); listRamps(this); },
          rampToPalette: function (i) { var n = addToPalette((state.ramps[i].colors || []).map(parseHex).filter(Boolean)); this.note = t('cpmplus.art.ramp.added', n); },
          fromTexture: function () {
            var tex = G.Texture && (G.Texture.selected || G.Texture.getDefault && G.Texture.getDefault());
            if (!tex) { this.note = t('cpmplus.art.err.noTexture'); this.families = []; return; }
            var fam = textureFamilies(tex, true).families.filter(function (f) { return f.length >= 2; });
            fam.sort(function (x, y) { return y.length - x.length; });
            this.families = fam.slice(0, 12).map(function (f) { return f.length > 16 ? f.filter(function (c, i) { return i % Math.ceil(f.length / 16) === 0; }) : f; }).map(function (f) { return f.map(hex); });
            this.note = this.families.length ? t('cpmplus.art.ramp.found', this.families.length) : t('cpmplus.art.ramp.foundNone');
          },
          saveFamily: function (i) { saveRamp(this.families[i].map(parseHex), t('cpmplus.art.ramp.fromTexName', i + 1)); listRamps(this); this.note = t('cpmplus.art.ramp.saved'); }
        },
        template: [
          '<div class="cpmplus_art_ramp">',
          '<p class="cpmplus_art_intro">' + esc(t('cpmplus.art.ramp.intro')) + '</p>',
          '<div class="cpmplus_art_row">',
          '<label><input type="radio" value="one" v-model="mode" @change="regen"> ' + esc(t('cpmplus.art.ramp.modeOne')) + '</label>',
          '<label><input type="radio" value="two" v-model="mode" @change="regen"> ' + esc(t('cpmplus.art.ramp.modeTwo')) + '</label>',
          '</div>',
          '<div class="cpmplus_art_row"><label>' + esc(t('cpmplus.art.ramp.colorA')) + ' <input type="color" v-model="a" @input="regen"></label>',
          '<label v-if="mode==\'two\'">' + esc(t('cpmplus.art.ramp.colorB')) + ' <input type="color" v-model="b" @input="regen"></label></div>',
          '<div class="cpmplus_art_grid">',
          '<label>' + esc(t('cpmplus.art.ramp.steps')) + ' <b>{{steps}}</b></label><input type="range" min="3" max="16" v-model="steps" @input="regen">',
          '<template v-if="mode==\'one\'">',
          '<label title="' + esc(t('cpmplus.art.ramp.hue.desc')) + '">' + esc(t('cpmplus.art.ramp.hue')) + ' <b>{{hue}}°</b></label><input type="range" min="0" max="90" v-model="hue" @input="regen">',
          '<label title="' + esc(t('cpmplus.art.ramp.contrast.desc')) + '">' + esc(t('cpmplus.art.ramp.contrast')) + ' <b>{{contrast}}%</b></label><input type="range" min="15" max="90" v-model="contrast" @input="regen">',
          '<label>' + esc(t('cpmplus.art.ramp.sat')) + ' <b>{{sat}}%</b></label><input type="range" min="0" max="100" v-model="sat" @input="regen">',
          '</template></div>',
          '<div class="cpmplus_art_swatches big"><div v-for="c in colors" class="cpmplus_art_sw" :style="{background:c}" :title="c" @click="pick(c,$event)" @contextmenu.prevent="pick(c,{button:2})"></div></div>',
          '<p class="cpmplus_art_hint">' + esc(t('cpmplus.art.ramp.clickHint')) + '</p>',
          '<div class="cpmplus_art_row"><button @click="toPalette">' + esc(t('cpmplus.art.ramp.toPalette')) + '</button>',
          '<button @click="save">' + esc(t('cpmplus.art.ramp.save')) + '</button>',
          '<button @click="fromTexture">' + esc(t('cpmplus.art.ramp.fromTexture')) + '</button></div>',
          '<div v-if="families.length"><h4>' + esc(t('cpmplus.art.ramp.texTitle')) + '</h4>',
          '<div v-for="(f,i) in families" class="cpmplus_art_line"><div class="cpmplus_art_swatches"><div v-for="c in f" class="cpmplus_art_sw" :style="{background:c}" :title="c" @click="pick(c,$event)" @contextmenu.prevent="pick(c,{button:2})"></div></div>',
          '<button @click="saveFamily(i)">' + esc(t('cpmplus.art.ramp.saveThis')) + '</button></div></div>',
          '<h4>' + esc(t('cpmplus.art.ramp.mine')) + '</h4>',
          '<p v-if="!ramps.length" class="cpmplus_art_hint">' + esc(t('cpmplus.art.ramp.mineEmpty')) + '</p>',
          '<div v-for="r in ramps" class="cpmplus_art_line" :class="{active: r.i===active}"><span class="cpmplus_art_name">{{r.name}}</span>',
          '<div class="cpmplus_art_swatches"><div v-for="c in r.colors" class="cpmplus_art_sw" :style="{background:c}" :title="c" @click="pick(c,$event)" @contextmenu.prevent="pick(c,{button:2})"></div></div>',
          '<button @click="useRamp(r.i)" :disabled="r.i===active">' + esc(t('cpmplus.art.ramp.use')) + '</button>',
          '<button @click="rampToPalette(r.i)">' + esc(t('cpmplus.art.ramp.toPaletteShort')) + '</button>',
          '<button @click="delRamp(r.i)">' + esc(t('cpmplus.art.ramp.delete')) + '</button></div>',
          '<p class="cpmplus_art_note" v-if="note">{{note}}</p>',
          '</div>'
        ].join('')
      }
    });
    dlg.show();
    return dlg;
  }

  // =================================================================== styles (dialogs)
  var STYLE_ID = 'cpmplus_art_style';
  function installStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var css = [
      '.cpmplus_art_intro{margin:0 0 8px;opacity:.85}',
      '.cpmplus_art_row{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:6px 0}',
      '.cpmplus_art_row button{min-width:0}',
      '.cpmplus_art_grid{display:grid;grid-template-columns:200px 1fr;gap:4px 10px;align-items:center;margin:8px 0}',
      '.cpmplus_art_swatches{display:flex;flex-wrap:nowrap;gap:2px;min-height:22px}',
      '.cpmplus_art_swatches.big .cpmplus_art_sw{width:38px;height:38px}',
      '.cpmplus_art_sw{width:22px;height:22px;border:1px solid rgba(0,0,0,.35);cursor:pointer;flex:0 0 auto}',
      '.cpmplus_art_sw:hover{outline:2px solid var(--color-accent)}',
      '.cpmplus_art_line{display:flex;gap:8px;align-items:center;margin:4px 0}',
      '.cpmplus_art_line.active{background:var(--color-selected);padding:2px 4px}',
      '.cpmplus_art_name{min-width:110px;max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.cpmplus_art_hint{font-size:.9em;opacity:.7;margin:4px 0}',
      '.cpmplus_art_note{color:var(--color-accent);margin:6px 0 0}',
      '.cpmplus_art_ramp h4{margin:12px 0 4px}',
      '.cpmplus_art_fams{max-height:260px;overflow-y:auto;margin:6px 0}',
      '.cpmplus_art_pickable{cursor:pointer;padding:2px 4px}',
      '.cpmplus_art_pickable:hover{background:var(--color-button)}'
    ].join('\n');
    var st = document.createElement('style'); st.id = STYLE_ID; st.textContent = css; document.head.appendChild(st);
    undoers.push(function () { var e = document.getElementById(STYLE_ID); if (e) e.remove(); });
  }

  // =================================================================== first-time hint
  function installHint() {
    function onMode(e) {
      try {
        // the QA browser opens Paint mode in many scenarios: the one-time hint would cover other notices there
        if (env.test && !G.__cpmplusArtHintInTests) return;
        if (state.hint || !G.Modes || !G.Modes.paint) return;
        var mode = e && e.mode ? e.mode : null, id = mode && mode.id ? mode.id : (G.Modes.selected && G.Modes.selected.id);
        if (id !== 'paint') return;
        state.hint = true; saveState();
        G.Blockbench.showToastNotification({ text: t('cpmplus.art.hint'), icon: 'palette', expire: 20000 });
      } catch (x) { }
    }
    try { G.Blockbench.on('select_mode', onMode); undoers.push(function () { G.Blockbench.removeListener('select_mode', onMode); }); } catch (e) { }
  }

  // =================================================================== install / uninstall
  /** java: {t, get, set, test, actions: {ramp, swap, noise, autoshade}} */
  api.init = function (bridge) {
    if (installed) return api;
    G.__cpmplusArtInits = (G.__cpmplusArtInits || 0) + 1;
    env = Object.assign(env, bridge || {});
    loadState();
    installStyle();
    var steps = [['shade', installShade], ['smudge', installSmudge], ['dither', installDither], ['gradient', installGradient], ['noise', installNoise], ['actions', installActions], ['hint', installHint]];
    steps.forEach(function (s) { try { s[1](); } catch (e) { warn('could not install ' + s[0], e); } });
    installed = true;
    return api;
  };
  /** the Java actions (palette / textures toolbars) */
  function installActions() {
    var a = env.actions || {};
    if (a.ramp && G.BarItems[a.ramp]) place('palette', G.BarItems[a.ramp], null);
    if (a.swap && G.BarItems[a.swap]) place('palette', G.BarItems[a.swap], a.ramp || null);
    // Blockbench 5 shows the palette in its own tab: also under the colour picker (always visible in the Colour tab)
    if (a.ramp && G.BarItems[a.ramp]) place('color_picker', G.BarItems[a.ramp], 'pick_screen_color');
    if (a.swap && G.BarItems[a.swap]) place('color_picker', G.BarItems[a.swap], a.ramp || 'pick_screen_color');
    if (a.noise && G.BarItems[a.noise]) place('texturelist', G.BarItems[a.noise], 'append_to_template');
    if (a.autoshade && G.BarItems[a.autoshade]) place('texturelist', G.BarItems[a.autoshade], a.noise || 'append_to_template');
  }
  // ---- modules (other plugin JS files that build on this core, e.g. the brush library)
  var modules = [];
  api.modules = {};
  function libFor() {
    return {
      G: G, t: t, esc: esc, warn: warn, clamp: clamp, byte: byte, hex: hex, parseHex: parseHex, hash3: hash3,
      rgbToHsv: rgbToHsv, hsvToRgb: hsvToRgb, rgbToOklab: rgbToOklab, oklabToRgb: oklabToRgb, hueShade: hueShade,
      ditherThreshold: ditherThreshold, textureCanvasInfo: textureCanvasInfo, primary: primary, secondary: secondary,
      opt: opt, setOpt: setOpt, mk: mk, place: place, select: select, slider: slider, toggle: toggle, makeTool: makeTool,
      brushBase: brushBase, secondaryClick: secondaryClick, isTool: isTool, refreshBrushBar: refreshBrushBar,
      selectTool: function (id) { return api.selectTool(id); }, test: function () { return !!env.test; },
      onUninstall: function (f) { undoers.push(f); },
      /** true once the first-time 'new art tools' toast was shown (it already lists every tool for new users) */
      artHintSeen: function () { return !!state.hint; }
    };
  }
  /** evaluates a module source '(function (L) {... return api;})' with this core's helpers and installs it */
  api.loadModule = function (name, src) {
    if (api.modules[name]) return true;
    try {
      var m = (new Function('L', 'return (' + src + '\n)(L);'))(libFor());
      if (m && typeof m.install === 'function') m.install();
      modules.push(m); api.modules[name] = m;
      return true;
    } catch (e) { warn('module ' + name + ' failed to start', e); return false; }
  };
  api.uninstall = function () {
    modules.splice(0).reverse().forEach(function (m) { try { if (m && m.uninstall) m.uninstall(); } catch (e) { } });
    api.modules = {};
    undoers.splice(0).reverse().forEach(function (f) { try { f(); } catch (e) { } });
    var mine = created.splice(0), bars = G.Toolbars || {}, off = mine.slice();
    placements.forEach(function (p) { if (p[1] && typeof p[1] === 'object' && off.indexOf(p[1]) < 0) off.push(p[1]); });
    // take our objects (and the Java actions we placed) out of every toolbar first: BarItem.delete only walks
    // item.toolbars, which can be incomplete; the Java actions themselves are deleted by PlusMenus
    Object.keys(bars).forEach(function (k) {
      var bar = bars[k], kids = bar && bar.children, hit = false;
      if (!Array.isArray(kids)) return;
      for (var i = kids.length - 1; i >= 0; i--) if (off.indexOf(kids[i]) >= 0) { kids.splice(i, 1); hit = true; }
      if (hit) { try { bar.update(true); } catch (e) { } }
    });
    mine.forEach(function (it) { try { it.delete(); } catch (e) { } });
    // the toolbars forget deleted items: place them again on the next install
    state.tb = {}; saveState();
    placements = [];
    installed = false;
  };
  api.open = function (what) {
    if (what === 'ramp') return openRamp();
    if (what === 'noise') return openNoise();
    if (what === 'swap') return openSwap();
    if (what === 'autoshade') return openAutoShade();
    throw new Error('unknown art dialog ' + what);
  };
  /** selects a paint tool, switching to Paint mode first when needed (menu entries) */
  api.selectTool = function (id) {
    var it = G.BarItems[id];
    if (!it || typeof it.select !== 'function') return false;
    try {
      if (typeof G.Condition === 'function' && !G.Condition(it.condition) && typeof it.switchModeAndSelect === 'function') it.switchModeAndSelect();
      else it.select();
    } catch (e) { warn('could not select ' + id, e); return false; }
    return true;
  };
  /** the Gradient tool in its Pixel art style (menu entry) */
  api.pixelGradient = function () {
    setOpt('grad.style', 'pixel');
    try { G.BarItems.cpmplus_art_grad_style.set('pixel'); } catch (e) { }
    var ok = api.selectTool('gradient_tool');
    refreshBrushBar();
    return ok;
  };
  api.showToolsAgain = function () { replaceAll(); return true; };
  api.state = function () { return JSON.parse(JSON.stringify(state)); };
  /** QA: fixed random seed for the brushes that pick pixels at random (jumble / variation) */
  api.setSeed = function (n) { fixedSeed = n; };
  /** texture noise without the dialog (QA / scripts): o = {mode, amount 0..100, cover 0..100, seed, where} -> changed */
  api.textureNoise = function (o) {
    var tex = currentTexture(); if (!tex) return -1;
    var g = grab(tex), r = noiseImage(tex, g.data, g.off, { mode: o.mode || 'light', amount: (o.amount || 20) / 100, cover: (o.cover === undefined ? 70 : o.cover) / 100, seed: o.seed || 1, where: o.where || 'all' });
    if (r.changed) commitPut(tex, r.data, t('cpmplus.art.tnoise.title'));
    return r.changed;
  };
  /**
   * colour family swap without the dialog (QA / scripts): o = {color '#rrggbb' (a colour of the family) | family index,
   * dh, sat (%), dl (-50..50), toColor1, where} -> changed
   */
  api.swapColors = function (o) {
    var tex = currentTexture(); if (!tex) return -1;
    var g = grab(tex), fams = familiesWithCounts(g.data), f = null;
    if (o.color) { var c = parseHex(o.color); if (c) { var k = ckey(c[0], c[1], c[2]); f = fams.filter(function (x) { return x.keys[k]; })[0] || null; } }
    else if (typeof o.family === 'number') f = fams[o.family] || null;
    if (!f) return -1;
    var p = o.toColor1 ? shiftToward(f, primary()) : { dh: o.dh || 0, sat: (o.sat === undefined ? 100 : o.sat) / 100, dl: (o.dl || 0) / 100 };
    var r = swapImage(tex, g.data, g.off, f, { dh: p.dh, sat: p.sat, dl: p.dl, where: o.where || 'all' });
    if (r.changed) commitPut(tex, r.data, t('cpmplus.art.swap.title'));
    return r.changed;
  };
  /** auto shading without the dialog (QA / scripts): o = autoShade options -> {changed, texels} */
  api.autoShade = function (o) {
    var p = prepareAutoShade(o.scope || 'selected'), oo = Object.assign(autoShadeDefaults(), o || {});
    var r = autoShadeImages(p.table, p.originals, oo, p.ao), list = [], n = 0;
    Object.keys(r.byTex).forEach(function (u) { if (r.byTex[u].changed) { list.push(r.byTex[u]); n += r.byTex[u].changed; } });
    if (list.length) commitMany(list, t('cpmplus.art.as.title'));
    return { changed: n, texels: r.texels };
  };
  /** QA: where a texel of a cube face is in 3D (rest pose) -> {p, n, edge} of its first use, or null */
  api.texelInfo = function (cubeName, x, y) {
    var c = ((G.Cube && G.Cube.all) || []).filter(function (k) { return k.name === cubeName; })[0]; if (!c) return null;
    var tb = collectTexels([c]), hit = null;
    Object.keys(tb.tex).forEach(function (u) { var e = tb.tex[u].map.get(y * tb.tex[u].tex.width + x); if (e && !hit) hit = { p: e.uses[0].p, n: e.uses[0].n, edge: e.uses[0].edge, uses: e.uses.length }; });
    return hit;
  };
  /** adds a ramp to My ramps (colours '#rrggbb' dark -> light) and makes it the active one; returns its index */
  api.addRamp = function (colors, name) { return saveRamp((colors || []).map(parseHex).filter(Boolean), name); };
  api.resetForTest = function () { state = { ramps: [], active: -1, tb: state.tb, opt: {}, hint: true }; saveState(); famCache = { key: null, fam: null }; };

  // pure helpers for the QA
  api._pure = {
    rgbToOklab: rgbToOklab, oklabToRgb: oklabToRgb, toLch: toLch, lchToRgb: lchToRgb, rgbToHsv: rgbToHsv, hsvToRgb: hsvToRgb,
    rgbToHsl: rgbToHsl, hslToRgb: hslToRgb, hex: hex, parseHex: parseHex, hueShade: hueShade, hslShift: hslShift,
    buildFamilies: buildFamilies, rampStep: rampStep, makeRamp: makeRamp, okDist: okDist, ditherThreshold: ditherThreshold
  };
  return api;
})()
