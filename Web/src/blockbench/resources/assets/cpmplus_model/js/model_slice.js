/*
 * CPM Plugin+ "CPM+ Modelar" - Fatiar / Furar keeping the texture:
 * - Fatiar: cuts cubes along X / Y / Z at a point or into equal parts. Every piece shows exactly the pixels it showed
 *   before: per-face UVs are cut in proportion, painted box-UV cubes become per-face, colour cubes just split. The
 *   faces at a cut show the cube's own end face (a cap), so a piece that is moved later is never hollow.
 * - Furar: a rectangular hole through a cube; the material around it is rebuilt as up to 4 boxes (2 slabs + 2 sides)
 *   with the right UVs; the walls of the hole show the cube's own side faces.
 * Painted cubes cut on the texture's pixel grid (whole UV units: no resampled texture on export). Inflated cubes are
 * cut on what you see (the inflated box), so the pieces touch exactly. The first piece keeps the cube (its ids); the
 * other pieces are copies with fresh CPM ids. Live 3D preview; one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var AX = { x: 0, y: 1, z: 2 }, AXN = ['x', 'y', 'z'];
  var EPS = 1e-4;
  // the texture axes of each face as Blockbench draws it: s = left -> right, t = top -> bottom ([axis, +1 = grows with
  // the coordinate]); n = the axis the face looks along
  var FMAP = {
    north: { n: 2, s: [0, -1], t: [1, -1] }, south: { n: 2, s: [0, 1], t: [1, -1] },
    east: { n: 0, s: [2, -1], t: [1, -1] }, west: { n: 0, s: [2, 1], t: [1, -1] },
    up: { n: 1, s: [0, 1], t: [2, 1] }, down: { n: 1, s: [0, 1], t: [2, -1] }
  };
  // the corner of the uv rect (0 = u1 v1, 1 = u2 v1, 2 = u1 v2, 3 = u2 v2) that lands on the face's top-left,
  // top-right, bottom-left and bottom-right vertex, for each face rotation (read from Blockbench's own meshes)
  var CORNERS = { 0: [0, 1, 2, 3], 90: [2, 0, 3, 1], 180: [3, 2, 1, 0], 270: [1, 3, 0, 2] };

  // =================================================================== the cut math (pure)
  function rotOf(fc) { var r = ((Math.round((fc && fc.rotation) || 0) % 360) + 360) % 360; return CORNERS[r] || CORNERS[0]; }
  function corner(uv, k) { return [k & 1 ? uv[2] : uv[0], k & 2 ? uv[3] : uv[1]]; }
  function textured(fc) { return !!fc && fc.texture !== false && fc.texture !== null && fc.texture !== undefined; }
  function painted(c) { return FACES.some(function (f) { return textured(c.faces && c.faces[f]); }); }
  /** the box you see (inflate included), in the cube's own coordinates */
  function vbox(c) {
    var inf = c.inflate || 0, lo = [], hi = [];
    for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i]) - inf); hi.push(Math.max(c.from[i], c.to[i]) + inf); }
    return { lo: lo, hi: hi, inf: inf };
  }
  /** the uv rect of a face of a piece (PB, a part of the box VB): the same pixels the piece covered on the whole face */
  function subUV(fc, map, VB, PB) {
    var rot = rotOf(fc), V = rot.map(function (k) { return corner(fc.uv, k); });
    function range(a) {
      var axis = a[0], lo = VB.lo[axis], hi = VB.hi[axis], span = hi - lo;
      if (Math.abs(span) < 1e-9) return [0, 1];
      return a[1] > 0 ? [(PB.lo[axis] - lo) / span, (PB.hi[axis] - lo) / span] : [(hi - PB.hi[axis]) / span, (hi - PB.lo[axis]) / span];
    }
    var S = range(map.s), R = range(map.t);
    function at(s, t) { return [0, 1].map(function (i) { return V[0][i] * (1 - s) * (1 - t) + V[1][i] * s * (1 - t) + V[2][i] * (1 - s) * t + V[3][i] * s * t; }); }
    var W = [at(S[0], R[0]), at(S[1], R[0]), at(S[0], R[1]), at(S[1], R[1])], i0 = rot.indexOf(0), i3 = rot.indexOf(3);
    return [M.snap(W[i0][0]), M.snap(W[i0][1]), M.snap(W[i3][0]), M.snap(W[i3][1])];
  }
  /**
   * a function that moves a position along axis a (box coordinates) onto the texture's grid of whole UV units of a
   * painted face that runs along that axis; null for a colour cube (any position is fine)
   */
  function snapper(c, VB, a) {
    for (var i = 0; i < FACES.length; i++) {
      var f = FACES[i], fc = c.faces && c.faces[f], m = FMAP[f];
      if (!textured(fc) || !fc.uv) continue;
      var which = m.s[0] === a ? 's' : m.t[0] === a ? 't' : null; if (!which) continue;
      var rot = rotOf(fc), V = rot.map(function (k) { return corner(fc.uv, k); }), A = V[0], B = which === 's' ? V[1] : V[2];
      var comp = Math.abs(B[0] - A[0]) >= Math.abs(B[1] - A[1]) ? 0 : 1, g0 = A[comp], g1 = B[comp];
      if (Math.abs(g1 - g0) < 1e-9) continue;
      return (function (g0, g1, sign, lo, hi) {
        var span = hi - lo;
        return function (p) {
          if (Math.abs(span) < 1e-9) return p;
          var s = sign > 0 ? (p - lo) / span : (hi - p) / span, g = g0 + s * (g1 - g0), s2 = (Math.round(g) - g0) / (g1 - g0);
          return M.snap(sign > 0 ? lo + s2 * span : hi - s2 * span);
        };
      })(g0, g1, m[which][1], VB.lo[a], VB.hi[a]);
    }
    return null;
  }
  /** a piece: the box PB (what you see) -> {from, to, faces (painted cubes: the per-face uvs)} */
  function pieceSpec(c, VB, PB) {
    var from = [], to = [];
    for (var i = 0; i < 3; i++) { from.push(M.snap(PB.lo[i] + VB.inf)); to.push(M.snap(PB.hi[i] - VB.inf)); }
    var faces = null;
    if (painted(c)) {
      faces = {};
      FACES.forEach(function (f) {
        var fc = c.faces[f]; if (!fc) return;
        faces[f] = { uv: textured(fc) && fc.uv ? subUV(fc, FMAP[f], VB, PB) : (fc.uv || [0, 0, 0, 0]).slice(), rotation: fc.rotation || 0, texture: fc.texture };
      });
    }
    return { from: from, to: to, faces: faces, box: { lo: PB.lo.slice(), hi: PB.hi.slice() } };
  }
  function boxWith(VB, axis, lo, hi) { var b = { lo: VB.lo.slice(), hi: VB.hi.slice() }; b.lo[axis] = lo; b.hi[axis] = hi; return b; }
  /** why a cube cannot be cut (lang: slice.why.{base|mesh|extrude|flat}) or null */
  function refuse(c) {
    if (!M.isCube(c)) return 'mesh';
    if (M.isBase(c)) return 'base';
    if (c.cpm_extrude) return 'extrude';
    var VB = vbox(c);
    if (VB.hi.some(function (h, i) { return h - VB.lo[i] < EPS; })) return 'flat';
    return null;
  }

  // =================================================================== Fatiar
  /**
   * the plan for one cube: o = {axis: 'x'|'y'|'z', mode: 'at'|'equal', at (px from the start of what you see), parts}
   * -> {cube, pieces: [spec], snapped} or {cube, why} (lang: slice.why.{outside|thin|few})
   */
  function slicePlan(c, o) {
    var why = refuse(c); if (why) return { cube: c, why: why };
    var VB = vbox(c), a = AX[o.axis] !== undefined ? AX[o.axis] : 0, lo = VB.lo[a], hi = VB.hi[a], span = hi - lo, raw = [];
    if (o.mode === 'equal') {
      var n = Math.max(2, Math.min(64, Math.round(Number(o.parts) || 2)));
      for (var k = 1; k < n; k++) raw.push(lo + span * k / n);
    } else raw.push(lo + (Number(o.at) || 0));
    var sn = snapper(c, VB, a), cuts = [], snapped = false;
    raw.forEach(function (p) {
      var q = sn ? sn(p) : M.snap(p);
      if (Math.abs(q - p) > EPS) snapped = true;
      if (q > lo + EPS && q < hi - EPS && cuts.every(function (x) { return Math.abs(x - q) > EPS; })) cuts.push(q);
    });
    cuts.sort(function (x, y) { return x - y; });
    if (!cuts.length) return { cube: c, why: o.mode === 'equal' ? 'few' : 'outside' };
    if (o.mode === 'equal' && cuts.length < raw.length) return { cube: c, why: 'few' };
    var edges = [lo].concat(cuts, [hi]), pieces = [];
    for (var i = 0; i + 1 < edges.length; i++) {
      if (edges[i + 1] - edges[i] <= 2 * VB.inf + EPS) return { cube: c, why: 'thin' };
      pieces.push(pieceSpec(c, VB, boxWith(VB, a, edges[i], edges[i + 1])));
    }
    return { cube: c, pieces: pieces, snapped: snapped, axis: a };
  }

  // =================================================================== Furar
  /**
   * o = {axis (the hole goes along it), size_x/size_y/size_z (hole size on the two other axes), at_x/at_y/at_z (where
   * it starts, px from the start of what you see)} -> {cube, pieces, hole: {lo, hi}} or {cube, why}
   * (lang: slice.why.{nohole|all})
   */
  function holePlan(c, o) {
    var why = refuse(c); if (why) return { cube: c, why: why };
    var VB = vbox(c), h = AX[o.axis] !== undefined ? AX[o.axis] : 2, other = [0, 1, 2].filter(function (i) { return i !== h; });
    var hole = { lo: VB.lo.slice(), hi: VB.hi.slice() }, snapped = false;
    other.forEach(function (a) {
      var nm = AXN[a], size = Number(o['size_' + nm]) || 0, start = Number(o['at_' + nm]) || 0, sn = snapper(c, VB, a);
      var p1 = VB.lo[a] + start, p2 = p1 + size;
      var q1 = sn ? sn(p1) : M.snap(p1), q2 = sn ? sn(p2) : M.snap(p2);
      if (Math.abs(q1 - p1) > EPS || Math.abs(q2 - p2) > EPS) snapped = true;
      hole.lo[a] = Math.max(VB.lo[a], Math.min(q1, q2)); hole.hi[a] = Math.min(VB.hi[a], Math.max(q1, q2));
    });
    if (other.some(function (a) { return hole.hi[a] - hole.lo[a] < EPS; })) return { cube: c, why: 'nohole' };
    // the longer of the two other axes gets the full slabs (fewer seams)
    var P = other[0], Q = other[1];
    if (VB.hi[Q] - VB.lo[Q] > VB.hi[P] - VB.lo[P]) { P = other[1]; Q = other[0]; }
    var boxes = [];
    function add(pl, ph, ql, qh) {
      if (ph - pl < EPS || qh - ql < EPS) return;
      var b = { lo: VB.lo.slice(), hi: VB.hi.slice() }; b.lo[P] = pl; b.hi[P] = ph; b.lo[Q] = ql; b.hi[Q] = qh; boxes.push(b);
    }
    // two slabs across the whole P side (below / above the hole on Q), then the two sides next to the hole
    add(VB.lo[P], VB.hi[P], VB.lo[Q], hole.lo[Q]);
    add(VB.lo[P], VB.hi[P], hole.hi[Q], VB.hi[Q]);
    add(VB.lo[P], hole.lo[P], hole.lo[Q], hole.hi[Q]);
    add(hole.hi[P], VB.hi[P], hole.lo[Q], hole.hi[Q]);
    if (!boxes.length) return { cube: c, why: 'all' };
    for (var i = 0; i < boxes.length; i++) for (var k = 0; k < 3; k++) if (boxes[i].hi[k] - boxes[i].lo[k] <= 2 * VB.inf + EPS) return { cube: c, why: 'thin' };
    return { cube: c, pieces: boxes.map(function (b) { return pieceSpec(c, VB, b); }), hole: hole, snapped: snapped, axis: h };
  }

  // =================================================================== applying a plan (one undo entry)
  function setPiece(cube, spec, name) {
    cube.name = name;
    cube.from = spec.from.slice(); cube.to = spec.to.slice();
    if (spec.faces) {
      cube.box_uv = false;
      if (cube.autouv !== undefined) cube.autouv = 0;
      FACES.forEach(function (f) {
        var s = spec.faces[f], fc = cube.faces[f]; if (!s || !fc) return;
        fc.uv = s.uv.slice(); fc.rotation = s.rotation; fc.texture = s.texture;
      });
    }
  }
  /** plans = [{cube, pieces}] -> every piece (cubes), the first of each plan is the cube itself */
  function apply(plans, label) {
    plans = plans.filter(function (p) { return p.pieces && p.pieces.length > 1 || (p.pieces && p.pieces.length === 1 && p.hole); });
    if (!plans.length) return [];
    var olds = plans.map(function (p) { return p.cube; }), made = [], all = [];
    M.edit(label, { outliner: true, elements: olds }, function () {
      plans.forEach(function (p) {
        var c = p.cube, base = String(c.name || 'cube'), copies = [];
        // copies first (each lands right after the cube: made from the last one back, they end up in order)
        for (var k = p.pieces.length - 1; k >= 1; k--) copies.unshift(M.duplicateNode(c));
        var list = [c].concat(copies);
        list.forEach(function (cube, i) { setPiece(cube, p.pieces[i], base + '_' + (i + 1)); });
        copies.forEach(function (x) { made.push(x); });
        list.forEach(function (x) { all.push(x); });
      });
    }, function () { return { outliner: true, elements: olds.concat(made) }; });
    M.refresh();
    M.selectNodes(all);
    return all;
  }
  function targets() { return M.selCubes(); }

  // =================================================================== the 3D preview of plans
  /** model-space matrix of the frame a cube's coordinates are drawn in (its groups' turns, then its own turn) */
  function frameOf(c) {
    var T3 = G.THREE, m = null, chain = [];
    for (var q = c.parent; q && q !== 'root'; q = q.parent) chain.unshift(q);
    function turn(mm, o, r) {
      if (!r || (!r[0] && !r[1] && !r[2])) return mm;
      var t = new T3.Matrix4().makeTranslation(o[0], o[1], o[2]).multiply(new T3.Matrix4().makeRotationFromEuler(new T3.Euler(r[0] * M.DEG, r[1] * M.DEG, r[2] * M.DEG, 'ZYX'))).multiply(new T3.Matrix4().makeTranslation(-o[0], -o[1], -o[2]));
      return mm ? mm.clone().multiply(t) : t;
    }
    chain.forEach(function (n) { m = turn(m, n.origin, n.rotation); });
    return turn(m, c.origin, c.rotation);
  }
  var COLS = [0x4fc3f7, 0xffb74d];
  function previewPlans(plans) {
    var boxes = [];
    plans.forEach(function (p) {
      if (!p.pieces) return;
      var m = frameOf(p.cube), gap = 0.12;
      p.pieces.forEach(function (s, i) {
        // a hair apart along the cut so every piece reads on its own
        var lo = s.box.lo.slice(), hi = s.box.hi.slice();
        for (var k = 0; k < 3; k++) if (hi[k] - lo[k] > 3 * gap) { lo[k] += gap / 2; hi[k] -= gap / 2; }
        boxes.push({ from: lo, to: hi, matrix: m, col: COLS[i % 2] });
      });
      if (p.hole) boxes.push({ from: p.hole.lo.map(function (v) { return v + 0.03; }), to: p.hole.hi.map(function (v) { return v - 0.03; }), matrix: m, col: 0xe53935 });
    });
    M.preview(boxes, undefined, { opacity: 0.8 });
  }

  // =================================================================== the windows
  function longest(c) { var VB = vbox(c), s = [0, 1, 2].map(function (i) { return VB.hi[i] - VB.lo[i]; }); return AXN[s.indexOf(Math.max.apply(null, s))]; }
  function shortest(c) { var VB = vbox(c), s = [0, 1, 2].map(function (i) { return VB.hi[i] - VB.lo[i]; }); return AXN[s.indexOf(Math.min.apply(null, s))]; }
  function axisOptions() { return { x: T('slice.axis.x'), y: T('slice.axis.y'), z: T('slice.axis.z') }; }
  /** 'N cubes stay as they are: ...' for the cubes a plan leaves out */
  function whyText(plans) {
    var out = {};
    plans.forEach(function (p) { if (p.why) (out[p.why] = out[p.why] || []).push(p.cube.name); });
    // lang: slice.why.{base|mesh|extrude|flat|outside|thin|few|nohole|all}
    return Object.keys(out).map(function (k) { return T('slice.why.' + k, out[k].slice(0, 4).join(', ') + (out[k].length > 4 ? ' (+' + (out[k].length - 4) + ')' : '')); });
  }
  function summary(dlg, plans, key) {
    var ok = plans.filter(function (p) { return p.pieces; }), n = 0, extra = 0;
    ok.forEach(function (p) { n += p.pieces.length; extra += p.pieces.length - 1; });
    var box = dlg && dlg.object && dlg.object.querySelector('.cmp_slice_sum'), lines = [];
    lines.push('<p class="cmp_slice_big">' + L.esc(!ok.length ? T('slice.none') : key === 'hole' ? T('hole.sum', ok.length, n) : T('slice.sum', ok.length, n)) + '</p>');
    if (ok.some(function (p) { return p.snapped; })) lines.push('<p class="cmp_slice_note">' + L.esc(T('slice.snapped')) + '</p>');
    whyText(plans).forEach(function (s) { lines.push('<p class="cmp_slice_note">' + L.esc(s) + '</p>'); });
    if (box) box.innerHTML = lines.join('');
    M.dialogNote(dlg, T('slice.after'), M.allCubes().length + extra, false);
  }
  var sdlg = null, hdlg = null;
  function openSlice() {
    var cubes = targets();
    if (!cubes.length) { G.Blockbench.showQuickMessage(T('need.cubes'), 2500); return null; }
    var first = cubes[0], VB = vbox(first), ax = longest(first), half = function (a) { var s = VB.hi[AX[a]] - VB.lo[AX[a]]; return M.snap(Math.max(0.5, Math.round(s) / 2)); };
    sdlg = new G.Dialog({
      id: 'cpmplus_model_slice', title: T('slice'), width: 470, buttons: [T('slice.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('slice.tip')) + '</p>', '<div class="cmp_slice_sum"></div>'],
      form: {
        axis: { label: T('slice.f.axis'), type: 'select', options: axisOptions(), value: ax },
        mode: { label: T('slice.f.mode'), type: 'select', options: { at: T('slice.mode.at'), equal: T('slice.mode.equal') }, value: cubes.length > 1 ? 'equal' : 'at' },
        at: { label: T('slice.f.at'), type: 'number', value: half(ax), min: 0, step: 0.5, condition: function (f) { return f.mode === 'at'; } },
        parts: { label: T('slice.f.parts'), type: 'number', value: 2, min: 2, max: 64, step: 1, condition: function (f) { return f.mode === 'equal'; } }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        var plans = cubes.map(function (c) { return slicePlan(c, f); });
        M.clearPreview(); sdlg = null;
        if (!plans.some(function (p) { return p.pieces; })) { G.Blockbench.showQuickMessage(T('slice.none'), 2500); return; }
        var all = apply(plans, T('slice'));
        G.Blockbench.showQuickMessage(T('slice.done', all.length), 2200);
      },
      onCancel: function () { M.clearPreview(); sdlg = null; }
    });
    sdlg.show();
    var lastAxis = ax;
    function update(f) {
      // a new axis: the cut starts in the middle again
      if (f.axis !== lastAxis) { lastAxis = f.axis; try { sdlg.setFormValues({ at: half(f.axis) }); f = sdlg.getFormResult(); } catch (e) { } }
      var plans = cubes.map(function (c) { return slicePlan(c, f); });
      previewPlans(plans);
      summary(sdlg, plans, 'slice');
    }
    try { update(sdlg.getFormResult()); } catch (e) { L.warn('slice preview failed', e); }
    return sdlg;
  }
  function openHole() {
    var cubes = targets();
    if (!cubes.length) { G.Blockbench.showQuickMessage(T('need.cubes'), 2500); return null; }
    var first = cubes[0], VB = vbox(first), ax = shortest(first);
    var form = { axis: { label: T('hole.f.axis'), type: 'select', options: axisOptions(), value: ax } };
    AXN.forEach(function (nm, i) {
      var span = VB.hi[i] - VB.lo[i], size = Math.max(1, Math.round(span / 3)), at = M.snap(Math.max(0, Math.round((span - size) / 2)));
      var off = function (f) { return f.axis !== nm; };
      // lang: hole.f.size.{x|y|z}
      form['size_' + nm] = { label: T('hole.f.size.' + nm), type: 'number', value: Math.min(size, span), min: 0, step: 0.5, condition: off };
      // lang: hole.f.at.{x|y|z}
      form['at_' + nm] = { label: T('hole.f.at.' + nm), type: 'number', value: at, min: 0, step: 0.5, condition: off };
    });
    hdlg = new G.Dialog({
      id: 'cpmplus_model_hole', title: T('hole'), width: 470, buttons: [T('hole.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('hole.tip')) + '</p>', '<div class="cmp_slice_sum"></div>'],
      form: form,
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        var plans = cubes.map(function (c) { return holePlan(c, f); });
        M.clearPreview(); hdlg = null;
        if (!plans.some(function (p) { return p.pieces; })) { G.Blockbench.showQuickMessage(T('slice.none'), 2500); return; }
        var all = apply(plans, T('hole'));
        G.Blockbench.showQuickMessage(T('hole.done', all.length), 2200);
      },
      onCancel: function () { M.clearPreview(); hdlg = null; }
    });
    hdlg.show();
    function update(f) {
      var plans = cubes.map(function (c) { return holePlan(c, f); });
      previewPlans(plans);
      summary(hdlg, plans, 'hole');
    }
    try { update(hdlg.getFormResult()); } catch (e) { L.warn('hole preview failed', e); }
    return hdlg;
  }

  // =================================================================== registration
  var STYLE = 'cmp_slice_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_slice_big{font-size:1.1em;font-weight:600;margin:6px 0}\n.cmp_slice_note{opacity:.8;margin:2px 0}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_slice', sec: 'adjust', icon: 'content_cut', key: 'slice', mode: 'edit', need: 'cubes', spots: ['cube'], run: function () { openSlice(); } });
    M.register({ id: 'cpmplus_model_hole', sec: 'adjust', icon: 'crop_free', key: 'hole', mode: 'edit', need: 'cubes', spots: ['cube'], run: function () { openHole(); } });
  };
  api.uninstall = function () {
    [sdlg, hdlg].forEach(function (d) { if (d) { try { d.close(); } catch (e) { } } });
    sdlg = null; hdlg = null; M.clearPreview();
  };
  // QA / other modules
  api.slicePlan = slicePlan; api.holePlan = holePlan; api.apply = apply; api.subUV = subUV; api.vbox = vbox; api.frameOf = frameOf; api.FMAP = FMAP;
  api.slice = function (cubes, o) { return apply((cubes || targets()).map(function (c) { return slicePlan(c, o); }), T('slice')); };
  api.hole = function (cubes, o) { return apply((cubes || targets()).map(function (c) { return holePlan(c, o); }), T('hole')); };
  api.openSlice = openSlice; api.openHole = openHole;
  api.dialog = function () { return sdlg || hdlg; };
  return api;
})
