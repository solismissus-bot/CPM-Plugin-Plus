/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Recortar com um cubo: the cutter (by default the cube selected last) takes its
 * volume out of the other selected cubes, which are rebuilt as boxes that keep exactly the same pixels (Fatiar / Furar
 * generalised: a notch, a hole, a slot, a cavity - up to 6 boxes per cube).
 * - the cutter's box is taken into each cube's own frame (its groups' turns and its own turn): exact when the two are
 *   aligned (same turn, or turned by multiples of 90 degrees); a cube turned another way stays as it is, with a clear
 *   message (boxes cannot follow a slanted cut);
 * - painted cubes are cut on the texture's pixel grid (the cut moves to the nearest whole pixel, said in the window);
 *   every piece shows the pixels it showed, the walls of the cut show the cube's own side faces (like Fatiar);
 * - a cube the cutter covers whole goes away; the first piece keeps the cube (its ids), the others are copies with fresh
 *   CPM ids; the cutter is deleted, or kept (a checkbox);
 * - live 3D preview (pieces in blue / orange, the part taken out in red); one Ctrl+Z.
 * Loaded after model_slice (it uses its texture math: subUV, FMAP, frameOf, vbox).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var EPS = 1e-4;
  // the corner of the uv rect that lands on the face's top-left / top-right / bottom-left / bottom-right vertex, per face
  // rotation (the same table as Fatiar)
  var CORNERS = { 0: [0, 1, 2, 3], 90: [2, 0, 3, 1], 180: [3, 2, 1, 0], 270: [1, 3, 0, 2] };
  function SL() { return L.core.modules.model_slice; }
  var last = { keep: false };

  // =================================================================== texture math (as Fatiar)
  function rotOf(fc) { var r = ((Math.round((fc && fc.rotation) || 0) % 360) + 360) % 360; return CORNERS[r] || CORNERS[0]; }
  function corner(uv, k) { return [k & 1 ? uv[2] : uv[0], k & 2 ? uv[3] : uv[1]]; }
  function textured(fc) { return !!fc && fc.texture !== false && fc.texture !== null && fc.texture !== undefined; }
  function painted(c) { return FACES.some(function (f) { return textured(c.faces && c.faces[f]); }); }
  function vbox(c) { return SL().vbox(c); }
  /** moves a position along axis a onto the texture's grid of whole UV units (painted cubes) - null for colour cubes */
  function snapper(c, VB, a) {
    var FMAP = SL().FMAP;
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
  /** a piece: the box PB (what you see) -> {from, to, faces (painted cubes: per-face uvs), box} */
  function pieceSpec(c, VB, PB) {
    var from = [], to = [], FMAP = SL().FMAP;
    for (var i = 0; i < 3; i++) { from.push(M.snap(PB.lo[i] + VB.inf)); to.push(M.snap(PB.hi[i] - VB.inf)); }
    var faces = null;
    if (painted(c)) {
      faces = {};
      FACES.forEach(function (f) {
        var fc = c.faces[f]; if (!fc) return;
        faces[f] = { uv: textured(fc) && fc.uv ? SL().subUV(fc, FMAP[f], VB, PB) : (fc.uv || [0, 0, 0, 0]).slice(), rotation: fc.rotation || 0, texture: fc.texture };
      });
    }
    return { from: from, to: to, faces: faces, box: { lo: PB.lo.slice(), hi: PB.hi.slice() } };
  }

  // =================================================================== the plan
  /** why a cube cannot be cut (lang: cut.why.{base|mesh|extrude|flat}) or null */
  function refuse(c) {
    if (!M.isCube(c)) return 'mesh';
    if (M.isBase(c)) return 'base';
    if (c.cpm_extrude) return 'extrude';
    var VB = vbox(c);
    if (VB.hi.some(function (h, i) { return h - VB.lo[i] < EPS; })) return 'flat';
    return null;
  }
  /**
   * the cutter's box (what you see) in the frame cube c's coordinates are written in, or null when the two are turned
   * differently (the box would be slanted there)
   */
  function cutterIn(c, K) {
    var F = SL().frameOf(c), inv = F ? F.clone().invert() : null;
    var pts = M.cubeCorners(K).map(function (p) { return inv ? p.clone().applyMatrix4(inv) : p.clone(); });
    var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    pts.forEach(function (p) { for (var i = 0; i < 3; i++) { var v = p.getComponent(i); lo[i] = Math.min(lo[i], v); hi[i] = Math.max(hi[i], v); } });
    var aligned = pts.every(function (p) { for (var i = 0; i < 3; i++) { var v = p.getComponent(i); if (Math.abs(v - lo[i]) > 1e-3 && Math.abs(v - hi[i]) > 1e-3) return false; } return true; });
    return aligned ? { lo: lo.map(M.snap), hi: hi.map(M.snap) } : null;
  }
  /**
   * cube c minus cutter K -> {cube, pieces: [spec], cut: {lo, hi} (the part taken out, c's frame), snapped} | {cube,
   * all: true, pieces: [], cut} (K covers it whole: it goes away) | {cube, why} (lang: cut.why.{turned|miss|thin})
   */
  function cutPlan(c, K) {
    var why = refuse(c); if (why) return { cube: c, why: why };
    var C = cutterIn(c, K); if (!C) return { cube: c, why: 'turned' };
    var VB = vbox(c), I = { lo: [0, 0, 0], hi: [0, 0, 0] }, snapped = false;
    for (var a = 0; a < 3; a++) {
      var lo = Math.max(VB.lo[a], C.lo[a]), hi = Math.min(VB.hi[a], C.hi[a]);
      if (hi - lo < EPS) return { cube: c, why: 'miss' };
      var sn = snapper(c, VB, a);
      if (sn) {
        var l2 = Math.max(VB.lo[a], sn(lo)), h2 = Math.min(VB.hi[a], sn(hi));
        if (Math.abs(l2 - lo) > EPS || Math.abs(h2 - hi) > EPS) snapped = true;
        lo = l2; hi = h2;
        if (hi - lo < EPS) return { cube: c, why: 'miss' };
      }
      I.lo[a] = lo; I.hi[a] = hi;
    }
    var full = [0, 1, 2].map(function (k) { return I.lo[k] <= VB.lo[k] + EPS && I.hi[k] >= VB.hi[k] - EPS; });
    if (full[0] && full[1] && full[2]) return { cube: c, all: true, pieces: [], cut: I, snapped: snapped };
    // the axes the cut goes all the way through come last (a hole = 4 boxes, like Furar); the others longest first
    var axes = [0, 1, 2].sort(function (x, y) { return (full[x] - full[y]) || ((VB.hi[y] - VB.lo[y]) - (VB.hi[x] - VB.lo[x])) || (x - y); });
    var boxes = [], cur = { lo: VB.lo.slice(), hi: VB.hi.slice() };
    axes.forEach(function (k) {
      if (I.lo[k] > cur.lo[k] + EPS) { var b = { lo: cur.lo.slice(), hi: cur.hi.slice() }; b.hi[k] = I.lo[k]; boxes.push(b); }
      if (I.hi[k] < cur.hi[k] - EPS) { var b2 = { lo: cur.lo.slice(), hi: cur.hi.slice() }; b2.lo[k] = I.hi[k]; boxes.push(b2); }
      cur.lo[k] = I.lo[k]; cur.hi[k] = I.hi[k];
    });
    for (var i = 0; i < boxes.length; i++) for (var j = 0; j < 3; j++) if (boxes[i].hi[j] - boxes[i].lo[j] <= 2 * VB.inf + EPS) return { cube: c, why: 'thin' };
    return { cube: c, pieces: boxes.map(function (b) { return pieceSpec(c, VB, b); }), cut: I, snapped: snapped };
  }
  /** the plans for the targets (every selected cube but the cutter) */
  function planAll(targets, K) { return targets.filter(function (c) { return c !== K; }).map(function (c) { return cutPlan(c, K); }); }

  // =================================================================== applying (one undo entry)
  function setPiece(cube, spec, name) {
    cube.name = name;
    cube.from = spec.from.slice(); cube.to = spec.to.slice();
    if (spec.faces) {
      cube.box_uv = false;
      if (cube.autouv !== undefined) cube.autouv = 0;
      FACES.forEach(function (f) { var s = spec.faces[f], fc = cube.faces[f]; if (!s || !fc) return; fc.uv = s.uv.slice(); fc.rotation = s.rotation; fc.texture = s.texture; });
    }
  }
  /** -> {pieces (cubes left), removed (cubes the cutter covered whole), cutter: kept | removed} or null */
  function apply(plans, K, keep) {
    plans = plans.filter(function (p) { return p.pieces; });
    if (!plans.length) return null;
    var olds = plans.map(function (p) { return p.cube; }), copies = [], pieces = [], removed = [];
    var before = olds.concat(keep || !K ? [] : [K]);
    M.edit(T('cut'), { outliner: true, elements: before }, function () {
      plans.forEach(function (p) {
        var c = p.cube, base = String(c.name || 'cube');
        if (p.all) { c.remove(); removed.push(c); return; }
        var made = [];
        // copies first (each lands right after the cube: made from the last one back, they end up in order)
        for (var k = p.pieces.length - 1; k >= 1; k--) made.unshift(M.duplicateNode(c));
        var list = [c].concat(made);
        list.forEach(function (cube, i) { setPiece(cube, p.pieces[i], list.length > 1 ? base + '_' + (i + 1) : base); });
        made.forEach(function (x) { copies.push(x); });
        list.forEach(function (x) { pieces.push(x); });
      });
      if (K && !keep) K.remove();
    }, function () { return { outliner: true, elements: pieces }; });
    M.refresh();
    if (pieces.length) M.selectNodes(pieces);
    return { pieces: pieces, copies: copies, removed: removed, cutter: keep ? 'kept' : 'removed' };
  }

  // =================================================================== the 3D preview
  var COLS = [0x4fc3f7, 0xffb74d];
  function previewPlans(plans) {
    var boxes = [];
    plans.forEach(function (p) {
      if (!p.pieces) return;
      var m = SL().frameOf(p.cube), gap = 0.12;
      p.pieces.forEach(function (s, i) {
        var lo = s.box.lo.slice(), hi = s.box.hi.slice();
        for (var k = 0; k < 3; k++) if (hi[k] - lo[k] > 3 * gap) { lo[k] += gap / 2; hi[k] -= gap / 2; }
        boxes.push({ from: lo, to: hi, matrix: m, col: COLS[i % 2] });
      });
      if (p.cut) boxes.push({ from: p.cut.lo.map(function (v) { return v + 0.03; }), to: p.cut.hi.map(function (v) { return v - 0.03; }), matrix: m, col: 0xe53935 });
    });
    M.preview(boxes, undefined, { opacity: 0.8 });
  }

  // =================================================================== the window
  function names(list) { return list.slice(0, 4).join(', ') + (list.length > 4 ? ' (+' + (list.length - 4) + ')' : ''); }
  var dlg = null;
  function open() {
    var cubes = M.selCubes();
    if (cubes.length < 2) { G.Blockbench.showQuickMessage(T('cut.need'), 3000); return null; }
    var options = {}; cubes.forEach(function (c) { options[c.uuid] = c.name; });
    var byId = function (id) { for (var i = 0; i < cubes.length; i++) if (cubes[i].uuid === id) return cubes[i]; return cubes[cubes.length - 1]; };
    dlg = new G.Dialog({
      id: 'cpmplus_model_cut', title: T('cut'), width: 480, buttons: [T('cut.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('cut.tip')) + '</p>', '<div class="cmp_cut_sum"></div>'],
      form: {
        cutter: { label: T('cut.f.cutter'), type: 'select', options: options, value: cubes[cubes.length - 1].uuid },
        keep: { label: T('cut.f.keep'), type: 'checkbox', value: !!last.keep }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        var K = byId(f.cutter), plans = planAll(cubes, K);
        M.clearPreview(); dlg = null;
        last.keep = !!f.keep;
        if (!plans.some(function (p) { return p.pieces; })) { G.Blockbench.showQuickMessage(T('cut.none'), 3000); return; }
        var r = apply(plans, K, !!f.keep);
        if (r) G.Blockbench.showQuickMessage(T('cut.done', r.pieces.length), 2200);
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var K = byId(f.cutter), plans = planAll(cubes, K), ok = plans.filter(function (p) { return p.pieces && !p.all; }), all = plans.filter(function (p) { return p.all; });
      var n = 0; ok.forEach(function (p) { n += p.pieces.length; });
      var lines = ['<p class="cmp_cut_big">' + L.esc(ok.length || all.length ? T('cut.sum', ok.length, n) : T('cut.none')) + '</p>'];
      if (all.length) lines.push('<p class="cmp_cut_note">' + L.esc(T('cut.all', names(all.map(function (p) { return p.cube.name; })))) + '</p>');
      if (plans.some(function (p) { return p.snapped && p.pieces; })) lines.push('<p class="cmp_cut_note">' + L.esc(T('cut.snapped')) + '</p>');
      var why = {};
      plans.forEach(function (p) { if (p.why) (why[p.why] = why[p.why] || []).push(p.cube.name); });
      // lang: cut.why.{base|mesh|extrude|flat|turned|miss|thin}
      Object.keys(why).forEach(function (k) { lines.push('<p class="cmp_cut_note">' + L.esc(T('cut.why.' + k, names(why[k]))) + '</p>'); });
      var box = dlg && dlg.object && dlg.object.querySelector('.cmp_cut_sum'); if (box) box.innerHTML = lines.join('');
      var after = M.allCubes().length + n - ok.length - all.length - (f.keep ? 0 : 1);
      M.dialogNote(dlg, T('cut.after'), after, false);
      previewPlans(plans);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('cut preview failed', e); }
    return dlg;
  }

  // =================================================================== registration
  var STYLE = 'cmp_cut_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_cut_big{font-size:1.1em;font-weight:600;margin:6px 0}\n.cmp_cut_note{opacity:.8;margin:2px 0}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_cut', sec: 'adjust', icon: 'difference', key: 'cut', mode: 'edit', need: 'two', spots: ['cube'], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  // QA / other modules
  api.cutPlan = cutPlan; api.cutterIn = cutterIn; api.planAll = planAll; api.apply = apply; api.open = open;
  api.cut = function (targets, K, keep) { return apply(planAll(targets, K), K, keep); };
  api.dialog = function () { return dlg; };
  return api;
})
