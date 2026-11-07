/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Arrumar UV automatico (pack the UV): the painted areas of the chosen parts (the
 * selected cubes / groups, or the whole model when nothing is selected) are laid out again on the skin, compactly and
 * without overlaps.
 * - exact pixels: every painted area is copied pixel for pixel to its new spot and the UVs follow by the same shift
 *   (whole UV units: CPM keeps UVs as whole numbers; an HD skin moves by whole skin pixels, its density is kept);
 * - shared on purpose stays shared: faces / cubes that show the very same pixels (mirrored twins, repeated parts, one
 *   area inside another, the same box template) move together; a box-UV cube moves its whole template (stays box UV),
 *   per-face cubes are packed face by face;
 * - never moved: the player's own skin (head, body, arms, legs and the 2nd layer areas of the 64 x 64 layout) and what
 *   shares pixels with it, CPM animated-texture areas, the areas of parts outside the choice, pictures no part uses,
 *   faces on other textures; colour cubes have no texture area (untouched);
 * - accidental overlaps (two different paintings crossing each other: the 'uv' rows of Conferir modelo): each part gets
 *   its own copy of its pixels (a checkbox, on);
 * - the freed old areas become transparent (a checkbox, on); 'Mais espaco na textura' (64 -> 128 UV, pixels kept top
 *   left) when it does not fit;
 * - the window shows the skin before / after and the numbers (area used, largest free space, areas moved, pixels freed);
 * - ONE undo entry for pixels, UVs and texture size; the skin is restored explicitly on undo / redo (the F11 rule).
 * Loaded after model_texture and model_stitch.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var EPS = 1e-6, MAX_GROW = 2, MAX_PX = 4096, PIC = 320;
  // the vanilla player on a 64 x 64 skin: [u, v, width, height, depth] (box templates), base + 2nd layer
  var VANILLA = [[0, 0, 8, 8, 8], [32, 0, 8, 8, 8], [16, 16, 8, 12, 4], [16, 32, 8, 12, 4], [40, 16, 4, 12, 4], [40, 32, 4, 12, 4],
    [32, 48, 4, 12, 4], [48, 48, 4, 12, 4], [0, 16, 4, 12, 4], [0, 32, 4, 12, 4], [16, 48, 4, 12, 4], [0, 48, 4, 12, 4]];
  function X() { return L.core.modules.model_texture; }
  function ST() { return L.core.modules.model_stitch; }

  // =================================================================== textures / rects
  function skinTex() {
    var st = ST(), t = st && typeof st.skinTexture === 'function' ? st.skinTexture() : null;
    return t || (X() ? X().mainTexture() : null);
  }
  function sheetOf(t) { var st = ST(); return t && st && typeof st.sheetOf === 'function' ? st.sheetOf(t.name) : null; }
  function uvW(t) { return typeof t.getUVWidth === 'function' ? t.getUVWidth() : (t.uv_width || G.Project.texture_width); }
  function uvH(t) { return typeof t.getUVHeight === 'function' ? t.getUVHeight() : (t.uv_height || G.Project.texture_height); }
  /** texture pixels per UV unit [x, y] */
  function dens(t) { return [t.width / uvW(t), (t.display_height || t.height) / uvH(t)]; }
  /** the smallest whole number n of UV units with n * d a whole number of pixels (0 = none up to 16) */
  function stepFor(d) { for (var n = 1; n <= 16; n++) if (Math.abs(n * d - Math.round(n * d)) < 1e-6) return n; return 0; }
  function textured(f) { return !!f && f.texture !== false && f.texture !== null && f.texture !== undefined && !!f.uv; }
  function rectOf(f) { var u = f.uv; return [Math.min(u[0], u[2]), Math.min(u[1], u[3]), Math.max(u[0], u[2]), Math.max(u[1], u[3])]; }
  function area(r) { return Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]); }
  function ovArea(a, b) { var w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]), h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]); return w > EPS && h > EPS ? w * h : 0; }
  function sameR(a, b) { return Math.abs(a[0] - b[0]) < 1e-4 && Math.abs(a[1] - b[1]) < 1e-4 && Math.abs(a[2] - b[2]) < 1e-4 && Math.abs(a[3] - b[3]) < 1e-4; }
  function inR(a, b) { return a[0] >= b[0] - 1e-4 && a[1] >= b[1] - 1e-4 && a[2] <= b[2] + 1e-4 && a[3] <= b[3] + 1e-4; }
  /** the UV cells (whole units) a rect touches */
  function cellsOf(r) { return [Math.floor(r[0] + EPS), Math.floor(r[1] + EPS), Math.ceil(r[2] - EPS), Math.ceil(r[3] - EPS)]; }
  /** the size CPM gives a box-UV template (whole pixels, rounded up) */
  function ceilSize(c, i) { return Math.max(0, Math.ceil(Math.abs(c.to[i] - c.from[i]) - 1e-4)); }
  function sameTpl(a, b) { for (var i = 0; i < 5; i++) if (Math.abs(a[i] - b[i]) > 1e-4) return false; return true; }

  // =================================================================== what is on the skin
  /** the faces of a cube in the skin's UV space: [{face, r, other (another non-sheet texture)}] (CPM sheets are apart) */
  function skinFaces(c, skin) {
    var out = [];
    FACES.forEach(function (k) {
      var f = c.faces && c.faces[k]; if (!textured(f)) return;
      var t = typeof f.getTexture === 'function' ? f.getTexture() : null;
      if (t && t !== skin && sheetOf(t)) return;// a CPM sheet (cape, elytra, armor) is its own texture
      var r = rectOf(f); if (r[2] - r[0] <= EPS || r[3] - r[1] <= EPS) return;
      out.push({ face: k, r: r, other: !!(t && t !== skin) });
    });
    return out;
  }
  /** an HD skin has the vanilla layout scaled up; a skin given more room keeps it top left (the base cubes tell which) */
  function vanillaScale(skin, uw) {
    var k = uw / 64, maxUV = 0;
    M.allCubes().forEach(function (c) { if (M.isBase(c)) skinFaces(c, skin).forEach(function (f) { maxUV = Math.max(maxUV, f.r[2], f.r[3]); }); });
    if (maxUV > 0 && maxUV <= 64.01) k = 1;
    return k;
  }
  /** the CPM animated-texture areas of the skin (the shown area and the frames strip), UV units */
  function animRects(s) {
    var out = [];
    try {
      var pd = G.Project && G.Project.cpm_data ? JSON.parse(G.Project.cpm_data) : null, list = pd && pd.tex && pd.tex.skin && pd.tex.skin.anim;
      (Array.isArray(list) ? list : []).forEach(function (m) {
        var v = function (o, k) { return o && typeof o[k] === 'number' ? o[k] : 0; };
        var sx = v(m.start, 'x'), sy = v(m.start, 'y'), zw = v(m.size, 'x'), zh = v(m.size, 'y'), fx = v(m.from, 'x'), fy = v(m.from, 'y');
        var fc = typeof m.frameCount === 'number' && m.frameCount > 0 ? m.frameCount : 1;
        // AnimatedTex copies image pixels: back to UV units
        out.push([sx / s, sy / s, (sx + zw) / s, (sy + zh) / s]);
        out.push([fx / s, fy / s, (fx + (m.anX ? zw * fc : zw)) / s, (fy + (m.anX ? zh : zh * fc)) / s]);
      });
    } catch (e) { }
    return out.filter(function (r) { return area(r) > EPS; });
  }

  // =================================================================== analysis: items -> islands
  /**
   * items: a box-UV cube (its whole template), a per-face face, a vanilla template, an anchor (animated area, a face on
   * another texture: whatever overlaps it stays). Items sharing pixels on purpose (same rect, one inside the other, the
   * same box template) are joined; crossing areas are accidental: joined too unless fix (then each keeps its own copy).
   * Islands with something fixed (outside the choice, the player's skin, an anchor, outside the texture) stay.
   */
  function analyse(scope, fix) {
    var skin = skinTex();
    if (!skin) return { why: 'noskin' };
    if (skin.frameCount > 1) return { why: 'anim', skin: skin };
    var d = dens(skin), uw = uvW(skin), uh = uvH(skin), W = Math.round(uw), H = Math.round(uh);
    if (Math.abs(d[0] - d[1]) > 1e-6 || !stepFor(d[0]) || Math.abs(uw - W) > 1e-6 || Math.abs(uh - H) > 1e-6 || !W || !H) return { why: 'odd', skin: skin };
    var s = d[0], step = stepFor(s), inScope = new Set(scope), items = [], otherFaces = 0;
    function outside(r) { return r[0] < -EPS || r[1] < -EPS || r[2] > W + EPS || r[3] > H + EPS; }
    M.allCubes().forEach(function (c) {
      var fs = skinFaces(c, skin); if (!fs.length) return;// colour cubes / nothing on the skin
      var base = M.isBase(c), mine = inScope.has(c) && !base, other = fs.some(function (f) { return f.other; });
      fs.forEach(function (f) { if (f.other) otherFaces++; });
      if (c.box_uv && !other) {
        var u = c.uv_offset ? c.uv_offset[0] : 0, v = c.uv_offset ? c.uv_offset[1] : 0, cw = ceilSize(c, 0), ch = ceilSize(c, 1), cd = ceilSize(c, 2);
        var rects = X().boxRects(u, v, cw, ch, cd).filter(function (r) { return area(r) > EPS; });
        // Blockbench's own face rects too (never outside the template in practice, kept when they are)
        fs.forEach(function (f) { if (!rects.some(function (r) { return inR(f.r, r); })) rects.push(f.r); });
        var out = rects.some(outside);
        items.push({ cube: c, box: true, tpl: [u, v, cw, ch, cd], rects: rects, fixed: !mine || out, why: base ? 'base' : out ? 'outside' : mine ? null : 'out', mine: mine });
      } else {
        fs.forEach(function (f) {
          var o = outside(f.r);
          if (other) items.push({ cube: c, face: f.face, rects: [f.r], fixed: true, anchor: true, why: 'other', mine: mine });
          else items.push({ cube: c, face: f.face, rects: [f.r], fixed: !mine || o, why: base ? 'base' : o ? 'outside' : mine ? null : 'out', mine: mine });
        });
      }
    });
    if (M.isCpm() && (Math.abs(uh / uw - 1) < 1e-6 || Math.abs(uh / uw - 0.5) < 1e-6)) {
      var k = vanillaScale(skin, uw);
      VANILLA.forEach(function (b) {
        if (b[1] * k >= uh) return;
        var rs = X().boxRects(b[0], b[1], b[2], b[3], b[4]).map(function (r) { return [r[0] * k, r[1] * k, r[2] * k, r[3] * k]; });
        items.push({ vanilla: true, box: true, tpl: [b[0] * k, b[1] * k, b[2] * k, b[3] * k, b[4] * k], rects: rs, fixed: true, why: 'vanilla' });
      });
    }
    animRects(s).forEach(function (r) { items.push({ anchor: true, rects: [r], fixed: true, why: 'anim' }); });

    // ---- joining (only items that share a UV cell are compared)
    var n = items.length, up = new Int32Array(n), i;
    for (i = 0; i < n; i++) up[i] = i;
    function root(a) { while (up[a] !== a) { up[a] = up[up[a]]; a = up[a]; } return a; }
    function join(a, b) { var ra = root(a), rb = root(b); if (ra !== rb) up[ra] = rb; }
    function rel(a, b) {
      var anyOv = false, inten = false, acc = false;
      a.rects.forEach(function (x) { b.rects.forEach(function (y) { if (ovArea(x, y) <= 0) return; anyOv = true; if (sameR(x, y) || inR(x, y) || inR(y, x)) inten = true; else acc = true; }); });
      if (!anyOv) return 0;
      if (a.anchor || b.anchor) return 1;
      if (a.box && b.box) return sameTpl(a.tpl, b.tpl) ? 1 : 2;
      return inten ? 1 : acc ? 2 : 0;
    }
    var cells = new Map(), seen = new Set(), accPairs = [];
    items.forEach(function (it, idx) {
      it.rects.forEach(function (r) {
        var c = cellsOf(r);
        for (var y = Math.max(0, c[1]); y < Math.min(H, c[3]); y++) for (var x = Math.max(0, c[0]); x < Math.min(W, c[2]); x++) {
          var key = y * W + x, list = cells.get(key);
          if (!list) { list = []; cells.set(key, list); }
          for (var j = 0; j < list.length; j++) {
            var o = list[j]; if (o === idx) continue;
            var pk = o < idx ? o * n + idx : idx * n + o; if (seen.has(pk)) continue; seen.add(pk);
            var rl = rel(items[o], it);
            if (rl === 1) join(o, idx);
            else if (rl === 2) { if (fix) accPairs.push([o, idx]); else join(o, idx); }
          }
          if (list[list.length - 1] !== idx) list.push(idx);
        }
      });
    });
    // ---- islands
    var byRoot = new Map(), islands = [];
    var PRIO = { base: 6, vanilla: 5, anim: 4, other: 3, outside: 2, out: 1 };
    items.forEach(function (it, idx) {
      var r = root(idx), is = byRoot.get(r);
      if (!is) { is = { items: [], rects: [], cubes: [], fixed: false, why: null, mine: false, idx: islands.length }; byRoot.set(r, is); islands.push(is); }
      is.items.push(it); it.island = is;
      it.rects.forEach(function (q) { is.rects.push(q); });
      if (it.cube && is.cubes.indexOf(it.cube) < 0) is.cubes.push(it.cube);
      if (it.mine) is.mine = true;
      if (it.fixed) { is.fixed = true; if (it.why && (!is.why || PRIO[it.why] > PRIO[is.why])) is.why = it.why; }
    });
    // ---- what stays: fixed islands + painted pixels no item uses
    var fixOcc = new Uint8Array(W * H), covered = new Uint8Array(W * H);
    function markRect(occ, r) { var c = cellsOf(r); for (var y = Math.max(0, c[1]); y < Math.min(H, c[3]); y++) for (var x = Math.max(0, c[0]); x < Math.min(W, c[2]); x++) occ[y * W + x] = 1; }
    islands.forEach(function (is) { is.rects.forEach(function (r) { markRect(covered, r); if (is.fixed) markRect(fixOcc, r); }); });
    var px = pixelsOf(skin), stray = 0;
    if (px) {
      for (var cy = 0; cy < H; cy++) for (var cx = 0; cx < W; cx++) {
        if (covered[cy * W + cx]) continue;
        var hit = false, x1 = Math.floor(cx * s), y1 = Math.floor(cy * s), x2 = Math.ceil((cx + 1) * s), y2 = Math.ceil((cy + 1) * s);
        for (var py = y1; py < y2 && !hit; py++) for (var pxx = x1; pxx < x2; pxx++) if (px.data[(py * px.width + pxx) * 4 + 3] > 0) { hit = true; break; }
        if (hit) { fixOcc[cy * W + cx] = 1; stray++; }
      }
    }
    // ---- the islands that move: their shape on the grid (origin on the step grid)
    var movers = islands.filter(function (is) { return !is.fixed && is.rects.length; });
    movers.forEach(function (is) {
      var mx = Infinity, my = Infinity;
      is.rects.forEach(function (r) { mx = Math.min(mx, r[0]); my = Math.min(my, r[1]); });
      var o = [Math.floor(mx / step + EPS) * step, Math.floor(my / step + EPS) * step], list = [], w = 0, h = 0, occ = {};
      is.rects.forEach(function (r) {
        var c = cellsOf(r), q = [c[0] - o[0], c[1] - o[1], c[2] - o[0], c[3] - o[1]];
        if (q[2] <= q[0] || q[3] <= q[1]) return;
        list.push(q); w = Math.max(w, q[2]); h = Math.max(h, q[3]);
        for (var y = q[1]; y < q[3]; y++) for (var x = q[0]; x < q[2]; x++) occ[y * 4096 + x] = 1;
      });
      is.o = o; is.shape = { cells: list, w: w, h: h }; is.area = Object.keys(occ).length;
    });
    // ---- accidental overlaps between different islands: who has to move to get its own copy
    var pairs = 0, unfixable = 0, pk2 = new Set();
    accPairs.forEach(function (p) {
      var A = items[p[0]].island, B = items[p[1]].island; if (A === B) { unfixable++; return; }
      var key = Math.min(A.idx, B.idx) + ',' + Math.max(A.idx, B.idx); if (pk2.has(key)) return; pk2.add(key);
      if (A.fixed && B.fixed) { unfixable++; return; }
      pairs++;
      if (A.fixed) B.must = true; else if (B.fixed) A.must = true;
      else if (!A.must && !B.must) { if (A.area <= B.area) A.must = true; else B.must = true; }
    });
    var kept = {};
    // (a part that shares a body part's own area stays for the same reason as the player's skin)
    islands.forEach(function (is) { if (is.fixed && is.mine) { var w = is.why === 'base' ? 'vanilla' : is.why; kept[w] = (kept[w] || 0) + 1; } });
    return { skin: skin, s: s, step: step, W: W, H: H, uw: uw, uh: uh, items: items, islands: islands, movers: movers, fixOcc: fixOcc, pairs: pairs, unfixable: unfixable, kept: kept, otherFaces: otherFaces, stray: stray, fix: !!fix };
  }
  function pixelsOf(tex) {
    var cv = tex && tex.canvas; if (!cv || !cv.width) return null;
    try { return cv.getContext('2d').getImageData(0, 0, cv.width, cv.height); } catch (e) { return null; }
  }

  // =================================================================== layout (grid of UV units)
  function sat(occ, W, H) {
    var S = new Int32Array((W + 1) * (H + 1));
    for (var y = 0; y < H; y++) { var row = 0; for (var x = 0; x < W; x++) { row += occ[y * W + x]; S[(y + 1) * (W + 1) + x + 1] = S[y * (W + 1) + x + 1] + row; } }
    return S;
  }
  function markCells(occ, W, cells, x0, y0) { cells.forEach(function (c) { for (var y = y0 + c[1]; y < y0 + c[3]; y++) for (var x = x0 + c[0]; x < x0 + c[2]; x++) occ[y * W + x] = 1; }); }
  /** the first spot (top left first, on the step grid) where every cell rect of a shape is free; marks it; null = none */
  function alloc(occ, W, H, shape, step) {
    if (shape.w > W || shape.h > H) return null;
    var S = sat(occ, W, H), W1 = W + 1;
    for (var y0 = 0; y0 + shape.h <= H; y0 += step) for (var x0 = 0; x0 + shape.w <= W; x0 += step) {
      var ok = true;
      for (var i = 0; i < shape.cells.length && ok; i++) {
        var c = shape.cells[i], x1 = x0 + c[0], y1 = y0 + c[1], x2 = x0 + c[2], y2 = y0 + c[3];
        ok = !(S[y2 * W1 + x2] - S[y1 * W1 + x2] - S[y2 * W1 + x1] + S[y1 * W1 + x1]);
      }
      if (ok) { markCells(occ, W, shape.cells, x0, y0); return [x0, y0]; }
    }
    return null;
  }
  /** the largest free rectangle of a grid {area, w, h} */
  function largestFree(occ, W, H) {
    var hg = new Int32Array(W), best = { area: 0, w: 0, h: 0 };
    for (var y = 0; y < H; y++) {
      for (var x = 0; x < W; x++) hg[x] = occ[y * W + x] ? 0 : hg[x] + 1;
      var st = [];
      for (var x2 = 0; x2 <= W; x2++) {
        var h = x2 < W ? hg[x2] : 0, start = x2;
        while (st.length && st[st.length - 1][1] >= h) {
          var top = st.pop(), a = top[1] * (x2 - top[0]);
          if (a > best.area) best = { area: a, w: x2 - top[0], h: top[1] };
          start = top[0];
        }
        st.push([start, h]);
      }
    }
    return best;
  }
  function embed(occ, W, H, W2, H2) {
    if (W2 === W && H2 === H) return occ.slice();
    var out = new Uint8Array(W2 * H2);
    for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) out[y * W2 + x] = occ[y * W + x];
    return out;
  }
  var ORDERS = [
    function (a, b) { return (b.shape.h - a.shape.h) || (b.shape.w - a.shape.w) || (b.area - a.area); },
    function (a, b) { return (b.area - a.area) || (b.shape.h - a.shape.h); },
    function (a, b) { return (b.shape.w - a.shape.w) || (b.shape.h - a.shape.h); },
    function (a, b) { return (a.o[1] - b.o[1]) || (a.o[0] - b.o[0]); }
  ];
  /** every moving island packed again (several orders tried, the most compact kept); null = does not fit */
  function repack(A, W, H) {
    var base = embed(A.fixOcc, A.W, A.H, W, H), best = null;
    ORDERS.forEach(function (cmp) {
      var list = A.movers.slice().sort(cmp), occ = base.slice(), at = new Map();
      for (var i = 0; i < list.length; i++) { var p = alloc(occ, W, H, list[i].shape, A.step); if (!p) return; at.set(list[i], p); }
      var free = largestFree(occ, W, H), bottom = 0, dist = 0;
      at.forEach(function (p, is) { bottom = Math.max(bottom, p[1] + is.shape.h); dist += Math.abs(p[0] - is.o[0]) + Math.abs(p[1] - is.o[1]); });
      var score = [-free.area, bottom, dist];
      if (!best || score[0] < best.score[0] || (score[0] === best.score[0] && (score[1] < best.score[1] || (score[1] === best.score[1] && score[2] < best.score[2])))) best = { at: at, score: score };
    });
    return best ? { at: best.at, W: W, H: H, mode: 'pack' } : null;
  }
  /** everything stays where it is except the islands that must get their own copy; null = they do not fit */
  function keepLayout(A) {
    var occ = A.fixOcc.slice(), at = new Map();
    A.movers.forEach(function (is) { if (!is.must) { markCells(occ, A.W, is.shape.cells, is.o[0], is.o[1]); at.set(is, is.o.slice()); } });
    var list = A.movers.filter(function (is) { return is.must; }).sort(ORDERS[0]);
    for (var i = 0; i < list.length; i++) { var p = alloc(occ, A.W, A.H, list[i].shape, A.step); if (!p) return null; at.set(list[i], p); }
    return { at: at, W: A.W, H: A.H, mode: 'keep' };
  }

  // =================================================================== the plan
  /**
   * opts = {scope (cubes; default: the selection or the whole model), fix, clear, grow (true / false / undefined = the
   * default)} -> {why} or {A, mode: 'pack' | 'keep' | 'none', fits, grow (doublings), needGrow, growTo, moves, stats ...}
   */
  function plan(opts) {
    opts = opts || {};
    var sel = M.cubesOf(M.selNodes()), scope = opts.scope || (sel.length ? sel : M.allCubes()), all = !opts.scope && !sel.length;
    var fix = opts.fix !== false, A = analyse(scope, fix);
    if (A.why) return A;
    var p = { A: A, scope: scope, all: all, fix: fix, clear: opts.clear !== false, grow: 0, needGrow: false, growTo: 0, fits: true, mode: 'none', layout: null };
    if (!A.movers.length) return finish(p);
    var full = null, g;
    for (g = 0; g <= MAX_GROW; g++) {
      var W = A.W << g, H = A.H << g;
      if (g && (W * A.s > MAX_PX || H * A.s > MAX_PX)) break;
      full = repack(A, W, H);
      if (full) { full.grow = g; break; }
    }
    var kept = !full || full.grow ? keepLayout(A) : null;
    p.growTo = full ? full.grow : 0;
    p.needGrow = !!(full && full.grow);
    // the grow box: on when nothing fits without it, off when the overlaps can be fixed in place
    p.growDefault = p.needGrow && !kept;
    var wantGrow = opts.grow === undefined ? p.growDefault : !!opts.grow;
    if (full && (!full.grow || wantGrow)) { p.layout = full; p.grow = full.grow; }
    else if (kept) { p.layout = kept; kept.grow = 0; }
    else { p.fits = false; }
    if (p.layout) p.mode = p.layout.mode;
    return finish(p);
  }
  function finish(p) {
    var A = p.A, L0 = p.layout, f = 1 << p.grow, W = A.W * f, H = A.H * f;
    p.moves = [];
    if (L0) A.movers.forEach(function (is) { var at = L0.at.get(is); if (at) p.moves.push({ island: is, at: at, shift: [at[0] - is.o[0], at[1] - is.o[1]] }); });
    p.moved = p.moves.filter(function (m) { return m.shift[0] || m.shift[1]; });
    // occupancy before / after (cells), what gets cleared, the numbers
    var before = A.fixOcc.slice();
    A.movers.forEach(function (is) { markCells(before, A.W, is.shape.cells, is.o[0], is.o[1]); });
    var after = embed(A.fixOcc, A.W, A.H, W, H), oldCells = new Uint8Array(A.W * A.H), newCells = new Uint8Array(W * H);
    p.moves.forEach(function (m) { markCells(after, W, m.island.shape.cells, m.at[0], m.at[1]); markCells(newCells, W, m.island.shape.cells, m.at[0], m.at[1]); });
    p.moved.forEach(function (m) { markCells(oldCells, A.W, m.island.shape.cells, m.island.o[0], m.island.o[1]); });
    var clearCells = [];
    for (var i = 0; i < oldCells.length; i++) if (oldCells[i] && !A.fixOcc[i]) clearCells.push(i);
    if (!p.clear) clearCells.forEach(function (i) { var x = i % A.W, y = (i - x) / A.W; after[y * W + x] = 1; });
    p.clearCells = p.clear ? clearCells : [];
    var used = function (o) { var n = 0; for (var k = 0; k < o.length; k++) if (o[k]) n++; return n; };
    // pixels freed: painted pixels of the old areas that end up unused and transparent
    var freed = 0, px = p.clear ? pixelsOf(A.skin) : null;
    if (px) clearCells.forEach(function (i) {
      var x = i % A.W, y = (i - x) / A.W; if (newCells[y * W + x]) return;
      for (var py = Math.floor(y * A.s); py < Math.ceil((y + 1) * A.s); py++) for (var pxx = Math.floor(x * A.s); pxx < Math.ceil((x + 1) * A.s); pxx++) if (px.data[(py * px.width + pxx) * 4 + 3] > 0) freed++;
    });
    p.occBefore = before; p.occAfter = after; p.W = W; p.H = H;
    var ub = used(before), ua = used(after), px2 = A.s * A.s;
    p.stats = {
      usedBefore: ub / before.length, usedAfter: ua / after.length, usedPxBefore: Math.round(ub * px2), usedPxAfter: Math.round(ua * px2),
      freeBefore: largestFree(before, A.W, A.H), freeAfter: largestFree(after, W, H),
      moved: p.moved.length, split: p.fix ? A.pairs : 0, freed: freed, cubes: p.scope.length
    };
    p.nothing = p.fits && !p.moved.length && !p.grow;
    return p;
  }

  // =================================================================== pixels (exact copies; read everything first)
  function copyCanvas(src) { var c = document.createElement('canvas'); c.width = src.width; c.height = src.height; var g = c.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(src, 0, 0); return c; }
  /** the pixel surfaces of a texture: its pixel layers, or its own canvas */
  function surfaces(tex) {
    if (tex.layers_enabled && tex.layers && tex.layers.length) return tex.layers.filter(function (l) { return l && l.ctx && (!l.type || l.type === 'pixel_layer'); }).map(function (l) { return { ctx: l.ctx, layer: l }; });
    var cv = tex.canvas;
    return cv ? [{ ctx: tex.ctx || cv.getContext('2d'), layer: null }] : [];
  }
  /**
   * one surface after the plan: the canvas grown (pixels kept top left), the old areas cleared (when chosen, never a
   * fixed cell), then every moving island's rects copied from the ORIGINAL pixels to their new spot (islands that stay
   * are written back too: what a clear touched under them comes back exactly)
   */
  function compose(p, src, off) {
    var A = p.A, s = A.s, f = 1 << p.grow, out = document.createElement('canvas');
    out.width = src.width * f; out.height = src.height * f;
    var g = out.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(src, 0, 0);
    var sg = src.getContext('2d'), ox = off ? off[0] : 0, oy = off ? off[1] : 0;
    p.clearCells.forEach(function (i) { var x = i % A.W, y = (i - x) / A.W; g.clearRect(Math.floor(x * s) - ox, Math.floor(y * s) - oy, Math.ceil((x + 1) * s) - Math.floor(x * s), Math.ceil((y + 1) * s) - Math.floor(y * s)); });
    var blocks = [];
    p.moves.forEach(function (m) {
      var dx = Math.round(m.shift[0] * s), dy = Math.round(m.shift[1] * s);
      m.island.rects.forEach(function (r) {
        var x1 = Math.floor(r[0] * s + EPS), y1 = Math.floor(r[1] * s + EPS), x2 = Math.ceil(r[2] * s - EPS), y2 = Math.ceil(r[3] * s - EPS);
        if (x2 <= x1 || y2 <= y1) return;
        blocks.push({ img: sg.getImageData(x1 - ox, y1 - oy, x2 - x1, y2 - y1), x: x1 + dx - ox, y: y1 + dy - oy });
      });
    });
    blocks.forEach(function (b) { g.putImageData(b.img, b.x, b.y); });
    return out;
  }
  function snapshot(tex) {
    return { w: tex.width, h: tex.height, uvw: tex.uv_width, uvh: tex.uv_height, s: surfaces(tex).map(function (s) { return { c: copyCanvas(s.ctx.canvas), off: s.layer && s.layer.offset ? s.layer.offset.slice() : null }; }) };
  }
  function refreshTexture(tex) {
    try { if (typeof tex.updateChangesAfterEdit === 'function') tex.updateChangesAfterEdit(); } catch (e) { }
    try { if (G.UVEditor && G.UVEditor.vue && typeof G.UVEditor.vue.updateTexture === 'function') G.UVEditor.vue.updateTexture(); } catch (e) { }
    try { if (G.Canvas && typeof G.Canvas.updateAllFaces === 'function') G.Canvas.updateAllFaces(tex); } catch (e) { }
  }
  /** puts a snapshot on the texture (size, UV size, every pixel surface) */
  function restore(tex, snap) {
    var run = function () {
      surfaces(tex).forEach(function (s, i) {
        var b = snap.s[i]; if (!b) return;
        s.ctx.canvas.width = b.c.width; s.ctx.canvas.height = b.c.height; s.ctx.imageSmoothingEnabled = false; s.ctx.drawImage(b.c, 0, 0);
        if (s.layer && b.off) s.layer.offset = b.off.slice();
      });
      tex.width = snap.w; tex.height = snap.h;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    tex.uv_width = snap.uvw; tex.uv_height = snap.uvh;
    refreshTexture(tex);
    try { if (G.Canvas && typeof G.Canvas.updateAllUVs === 'function') G.Canvas.updateAllUVs(); } catch (e) { }
  }

  // =================================================================== applying (one undo entry)
  var steps = new Map();
  function cubesOfPlan(p) { var out = []; p.moved.forEach(function (m) { m.island.cubes.forEach(function (c) { if (out.indexOf(c) < 0) out.push(c); }); }); return out; }
  function remap(p) {
    p.moved.forEach(function (m) {
      var dx = m.shift[0], dy = m.shift[1];
      m.island.items.forEach(function (it) {
        var c = it.cube; if (!c) return;
        if (it.box) {
          c.uv_offset = [(c.uv_offset ? c.uv_offset[0] : 0) + dx, (c.uv_offset ? c.uv_offset[1] : 0) + dy];
          FACES.forEach(function (k) { var f = c.faces[k]; if (f && f.uv) f.uv = [f.uv[0] + dx, f.uv[1] + dy, f.uv[2] + dx, f.uv[3] + dy]; });
        } else {
          var f = c.faces[it.face]; f.uv = [f.uv[0] + dx, f.uv[1] + dy, f.uv[2] + dx, f.uv[3] + dy];
        }
      });
    });
  }
  /** the plan on the model: one undo entry (cubes, texture); null when there is nothing to do */
  function apply(p) {
    if (!p || p.why || !p.fits || p.nothing) return null;
    var A = p.A, skin = A.skin, f = 1 << p.grow, before = snapshot(skin);
    var after = { w: Math.round(skin.width * f), h: Math.round(skin.height * f), uvw: A.uw * f, uvh: A.uh * f, s: surfaces(skin).map(function (s) { var off = s.layer && s.layer.offset ? s.layer.offset : null; return { c: compose(p, s.ctx.canvas, off), off: off ? off.slice() : null }; }) };
    var cubes = cubesOfPlan(p), aspects = { elements: cubes, textures: [skin], bitmap: true };
    M.edit(T('uvpack'), aspects, function () {
      restore(skin, after);
      if (p.grow) skin.keep_size = true;
      remap(p);
    }, function () { return { elements: cubes, textures: [skin], bitmap: true }; });
    var H = G.Undo && G.Undo.history, entry = H && H[H.length - 1];
    if (entry) steps.set(entry, { tex: skin, before: before, after: after });
    try { G.Canvas.updateAllFaces(); } catch (e) { }
    try { if (G.Canvas.updateAllUVs) G.Canvas.updateAllUVs(); } catch (e) { }
    try { if (G.BARS && G.BARS.updateConditions) G.BARS.updateConditions(); } catch (e) { }
    M.refresh();
    return { moved: p.moved.length, cubes: cubes.length, grow: p.grow, freed: p.stats.freed, split: p.stats.split };
  }
  function onUndo(d) { var s = d && d.entry && steps.get(d.entry); if (s) try { restore(s.tex, s.before); } catch (e) { L.warn('could not put the skin back', e); } }
  function onRedo(d) { var s = d && d.entry && steps.get(d.entry); if (s) try { restore(s.tex, s.after); } catch (e) { L.warn('could not redo the skin', e); } }

  // =================================================================== the window
  /**
   * a picture of the skin (its real pixels, scaled to fit) with the texture map's colours: free cells green, the areas
   * that move framed in yellow (old spots dashed before, new spots after), crossing paintings red (before)
   */
  function picture(p, after) {
    var A = p.A, s = A.s, W = after ? p.W : A.W, H = after ? p.H : A.H, img = after ? compose(p, A.skin.canvas, null) : A.skin.canvas;
    var k = PIC / Math.max(W * s, H * s); if (k >= 1) k = Math.floor(k);
    var out = document.createElement('canvas'); out.width = Math.max(1, Math.round(W * s * k)); out.height = Math.max(1, Math.round(H * s * k));
    out.className = 'cmp_uvp_pic'; out.setAttribute('data-state', after ? 'after' : 'before');
    var g = out.getContext('2d'); g.imageSmoothingEnabled = false;
    try { g.drawImage(img, 0, 0, img.width * k, img.height * k); } catch (e) { }
    var occ = after ? p.occAfter : p.occBefore, u = s * k;
    g.fillStyle = 'rgba(67,160,71,0.30)';
    for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) if (!occ[y * W + x]) g.fillRect(x * u, y * u, u, u);
    if (!after && p.fix) {
      // the crossing paintings (the parts that get their own copy)
      g.fillStyle = 'rgba(229,57,53,0.45)';
      A.movers.forEach(function (is) { if (is.must) is.shape.cells.forEach(function (c) { g.fillRect((is.o[0] + c[0]) * u, (is.o[1] + c[1]) * u, (c[2] - c[0]) * u, (c[3] - c[1]) * u); }); });
    }
    g.strokeStyle = 'rgba(255,193,7,0.95)'; g.lineWidth = 1;
    if (!after) g.setLineDash([3, 2]);
    p.moved.forEach(function (m) {
      var at = after ? m.at : m.island.o;
      m.island.shape.cells.forEach(function (c) { g.strokeRect(Math.round((at[0] + c[0]) * u) + 0.5, Math.round((at[1] + c[1]) * u) + 0.5, Math.max(1, Math.round((c[2] - c[0]) * u) - 1), Math.max(1, Math.round((c[3] - c[1]) * u) - 1)); });
    });
    return out;
  }
  function pct(v) { return Math.round(v * 100) + '%'; }
  function statLines(p) {
    var st = p.stats, out = [];
    out.push(p.all ? T('uvpack.scope.all', st.cubes) : T('uvpack.scope.sel', st.cubes));
    if (!p.fits) return out;
    out.push(T('uvpack.used', st.usedPxBefore, st.usedPxAfter, pct(st.usedBefore), pct(st.usedAfter)));
    out.push(T('uvpack.free', st.freeBefore.w + '\u00D7' + st.freeBefore.h, st.freeAfter.w + '\u00D7' + st.freeAfter.h));
    if (st.split) out.push(T('uvpack.split', st.split));
    out.push(p.clear ? T('uvpack.freed', st.freed) : T('uvpack.freed.off'));
    return out;
  }
  /** what the colours of the pictures mean */
  function legend(p) {
    var box = document.createElement('div'); box.className = 'cmp_uvp_legend';
    var items = [['rgba(67,160,71,0.75)', T('uvpack.leg.free')], ['#ffc107', T('uvpack.leg.moved')]];
    if (p.fix && p.A.pairs) items.push(['rgba(229,57,53,0.85)', T('uvpack.leg.mixed')]);
    items.forEach(function (it) {
      var s = document.createElement('span'), sw = document.createElement('i');
      sw.style.background = it[0]; s.appendChild(sw); s.appendChild(document.createTextNode(it[1])); box.appendChild(s);
    });
    return box;
  }
  function noteLines(p) {
    var A = p.A, out = [];
    if (p.mode === 'keep') out.push({ t: T('uvpack.keepmode'), warn: false });
    if (p.grow) out.push({ t: T('uvpack.grown', Math.round(A.uw), Math.round(A.uh), Math.round(A.uw * (1 << p.grow)), Math.round(A.uh * (1 << p.grow))), warn: false });
    // lang: uvpack.kept.{vanilla|out|anim|other|outside}
    Object.keys(A.kept).forEach(function (k) { out.push({ t: T('uvpack.kept.' + k, A.kept[k]), warn: false }); });
    if (A.otherFaces) out.push({ t: T('uvpack.otherfaces', A.otherFaces), warn: true });
    if (A.unfixable && p.fix) out.push({ t: T('uvpack.unfixable', A.unfixable), warn: true });
    if (!p.fits) out.push({ t: p.needGrow ? T('uvpack.nogrow') : T('uvpack.nofit'), warn: true });
    return out;
  }
  var dlg = null, current = null;
  function render(box, p) {
    if (!box) return;
    box.innerHTML = '';
    var big = document.createElement('p'); big.className = 'cmp_uvp_big';
    big.textContent = p.nothing ? T('uvpack.tidy') : !p.fits ? T('uvpack.nofit.big') : T('uvpack.sum', p.stats.moved);
    box.appendChild(big);
    var ul = document.createElement('ul'); ul.className = 'cmp_uvp_stats';
    statLines(p).forEach(function (t) { var li = document.createElement('li'); li.textContent = t; ul.appendChild(li); });
    box.appendChild(ul);
    noteLines(p).forEach(function (n) { var q = document.createElement('p'); q.className = 'cmp_uvp_note' + (n.warn ? ' warn' : ''); q.textContent = n.t; box.appendChild(q); });
    var row = document.createElement('div'); row.className = 'cmp_uvp_pics';
    [false, true].forEach(function (after) {
      if (after && !p.fits) return;
      var fig = document.createElement('div'), cap = document.createElement('div');
      // the size in each caption: a grown skin is drawn smaller to fit
      var sz = ' (' + Math.round(after ? p.W : p.A.W) + '\u00D7' + Math.round(after ? p.H : p.A.H) + ')';
      cap.className = 'cmp_uvp_cap'; cap.textContent = (after ? T('uvpack.after') : T('uvpack.before')) + sz;
      fig.appendChild(cap);
      try { fig.appendChild(picture(p, after)); } catch (e) { L.warn('uv pack picture failed', e); }
      row.appendChild(fig);
    });
    box.appendChild(row);
    box.appendChild(legend(p));
  }
  function optsOf(f, base) { f = f || {}; return { fix: f.fix === undefined ? true : !!f.fix, clear: f.clear === undefined ? true : !!f.clear, grow: f.grow === undefined ? base.grow : !!f.grow }; }
  function open() {
    var probe = plan({ fix: true });
    if (probe.why) {
      // lang: uvpack.why.{noskin|anim|odd}
      G.Blockbench.showQuickMessage(T('uvpack.why.' + probe.why), 3000);
      return null;
    }
    if (!probe.A.movers.length) { G.Blockbench.showQuickMessage(T('uvpack.none'), 3000); return null; }
    current = probe;
    var form = {};
    form.clear = { label: T('uvpack.f.clear'), description: T('uvpack.f.clear.desc'), type: 'checkbox', value: true };
    if (probe.A.pairs) form.fix = { label: T('uvpack.f.fix', probe.A.pairs), description: T('uvpack.f.fix.desc'), type: 'checkbox', value: true };
    if (probe.needGrow) form.grow = { label: T('uvpack.f.grow', Math.round(probe.A.uw), Math.round(probe.A.uh), Math.round(probe.A.uw * (1 << probe.growTo)), Math.round(probe.A.uh * (1 << probe.growTo))), description: T('uvpack.f.grow.desc'), type: 'checkbox', value: !!probe.growDefault };
    var base = { grow: probe.growDefault };
    // the tip, then the choices (always in view), then the numbers and the pictures (Blockbench: lines, form, component)
    dlg = new G.Dialog({
      id: 'cpmplus_model_uvpack', title: T('uvpack'), width: 760, buttons: [T('uvpack.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('uvpack.tip')) + '</p>'],
      form: form,
      component: { template: '<div class="cmp_uvp_box"></div>' },
      onFormChange: function (f) {
        try { current = plan(optsOf(f, base)); render(dlg && dlg.object && dlg.object.querySelector('.cmp_uvp_box'), current); }
        catch (e) { L.warn('uv pack update failed', e); }
      },
      onConfirm: function (f) {
        var p = null;
        try { p = plan(optsOf(f, base)); } catch (e) { L.warn('uv pack failed', e); }
        if (!p || p.why) { dlg = null; return; }
        if (!p.fits) { G.Blockbench.showQuickMessage(p.needGrow ? T('uvpack.nogrow') : T('uvpack.nofit'), 3000); return false; }
        dlg = null;
        if (p.nothing) { G.Blockbench.showQuickMessage(T('uvpack.tidy'), 2500); return; }
        try { var r = apply(p); if (r) G.Blockbench.showQuickMessage(T('uvpack.done', r.moved), 2500); }
        catch (e) { L.warn('uv pack failed', e); G.Blockbench.showMessageBox({ title: T('uvpack'), message: T('error') + '\n' + (e && e.message ? e.message : String(e)) }); }
      },
      onCancel: function () { dlg = null; }
    });
    dlg.show();
    var first = function () { try { render(dlg && dlg.object && dlg.object.querySelector('.cmp_uvp_box'), current); } catch (e) { L.warn('uv pack window failed', e); } };
    if (dlg.object && dlg.object.querySelector('.cmp_uvp_box')) first(); else setTimeout(first, 0);
    return dlg;
  }

  // =================================================================== registration
  var STYLE = 'cmp_uvpack_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = ['.cmp_uvp_big{font-size:1.1em;font-weight:600;margin:6px 0 4px}',
        '.cmp_uvp_stats{margin:2px 0 4px 18px;padding:0;list-style:disc}',
        '.cmp_uvp_stats li{margin:1px 0}',
        '.cmp_uvp_note{opacity:.85;margin:2px 0}',
        '.cmp_uvp_note.warn{color:var(--color-warning, #e8a33d);opacity:1}',
        '.cmp_uvp_pics{display:flex;gap:14px;justify-content:center;flex-wrap:wrap;margin:8px 0 0}',
        '.cmp_uvp_cap{text-align:center;font-size:.92em;opacity:.85;margin-bottom:3px}',
        '.cmp_uvp_legend{display:flex;gap:16px;justify-content:center;flex-wrap:wrap;margin:6px 0 2px;font-size:.92em;opacity:.9}',
        '.cmp_uvp_legend span{display:flex;align-items:center;gap:5px}',
        '.cmp_uvp_legend i{display:inline-block;width:12px;height:12px;border:1px solid var(--color-border)}',
        '.cmp_uvp_pic{display:block;image-rendering:pixelated;border:1px solid var(--color-border);background:repeating-conic-gradient(#3a3a3a 0% 25%, #2a2a2a 0% 50%) 50%/16px 16px}'].join('\n');
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    try {
      G.Blockbench.on('undo', onUndo); G.Blockbench.on('redo', onRedo);
      L.onUninstall(function () { G.Blockbench.removeListener('undo', onUndo); G.Blockbench.removeListener('redo', onRedo); steps.clear(); });
    } catch (e) { }
    var action = M.register({ id: 'cpmplus_model_uvpack', sec: 'texture', icon: 'view_quilt', key: 'uvpack', mode: 'edit', cpm: true, spots: ['textures', 'cube', 'group'], after: { textures: 'cpmplus_model_stitch' }, run: function () { open(); } });
    // also where people right-click a texture (after 'Juntar texturas', under the same CPM+ Modelar line)
    try {
      var menu = G.Texture && G.Texture.prototype.menu, s = menu && menu.structure;
      if (Array.isArray(s) && s.indexOf(action) < 0) {
        var at = -1, added = [];
        for (var i = 0; i < s.length; i++) if (s[i] && s[i].id === 'cpmplus_model_stitch') at = i;
        if (at < 0) {
          var sep = typeof G.MenuSeparator === 'function' ? new G.MenuSeparator('cpmplus_model_tex_uvpack', T('menu.section')) : '_';
          s.push(sep); added.push(sep); at = s.length - 1;
        }
        s.splice(at + 1, 0, action); added.push(action);
        L.onUninstall(function () { added.forEach(function (x) { var k = s.indexOf(x); if (k >= 0) s.splice(k, 1); }); });
      }
    } catch (e) { L.warn('could not add Arrumar UV to the texture menu', e); }
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } };
  // QA / other modules
  api.plan = plan; api.apply = apply; api.open = open; api.dialog = function () { return dlg; }; api.current = function () { return current; };
  api.pack = function (opts) { return apply(plan(opts)); };
  api.largestFree = largestFree;
  return api;
})
