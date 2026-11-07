/*
 * CPM Plugin+ "CPM+ Modelar" - Juntar cubos: colour cubes that touch or overlap, with the same colour, the same turn
 * (and pivot when turned), inflate, glow and visibility, inside the same group, become as few boxes as possible - the
 * model looks exactly the same with fewer parts. Painted cubes stay as they are (their painting would need new skin
 * space); groups are never mixed (animations move groups). Shows before / after and a 3D preview; one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var STEPS = [1, 0.5, 0.25, 0.125, 0.0625], MAX_CELLS = 600000;

  function recolour(c) { return typeof c.cpm_recolor === 'number' && c.cpm_recolor !== -1 ? c.cpm_recolor & 0xFFFFFF : null; }
  function isColourCube(c) { return !!c.box_uv && Object.keys(c.faces || {}).every(function (k) { return !c.faces[k] || !c.faces[k].texture; }) && recolour(c) !== null; }
  function turned(c) { var r = c.rotation || [0, 0, 0]; return !!(r[0] || r[1] || r[2]); }
  /** the player's own base cubes (head, body, arms, legs) are never touched nor counted */
  function isVanilla(c) { return M.isBase(c); }
  /** cubes with the same key may share boxes: same group, turn (+ pivot when turned), inflate, glow, visibility */
  function bucketKey(c) {
    var p = c.parent && c.parent !== 'root' ? c.parent.uuid : 'root';
    return [p, turned(c) ? c.rotation.join(',') + '@' + c.origin.join(',') : '-', c.inflate || 0, c.cpm_glow ? 1 : 0, c.visibility === false ? 0 : 1].join('|');
  }
  function onGrid(v, s) { var q = v / s; return Math.abs(q - Math.round(q)) < 1e-4; }
  /**
   * the plan for some cubes: [{cubes (the ones that go), boxes: [{from, to, colour, name}], parent, rotation, origin,
   * inflate, glow, visible}] + what stays as it is and why {painted, overlap, grid, alone}
   */
  function plan(cubes) {
    var buckets = {}, order = [], stay = { painted: 0, overlap: 0, grid: 0, flat: 0 };
    cubes.forEach(function (c) {
      if (!M.isCube(c) || c.export === false || isVanilla(c)) return;
      if (!isColourCube(c)) { stay.painted++; return; }
      // a plane (no thickness) is not made of cells: it stays as it is
      if (Math.abs(c.to[0] - c.from[0]) < 1e-6 || Math.abs(c.to[1] - c.from[1]) < 1e-6 || Math.abs(c.to[2] - c.from[2]) < 1e-6) { stay.flat++; return; }
      var k = bucketKey(c);
      if (!buckets[k]) { buckets[k] = []; order.push(k); }
      buckets[k].push(c);
    });
    var out = [];
    order.forEach(function (k) {
      var list = buckets[k]; if (list.length < 2) return;
      var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
      list.forEach(function (c) { for (var i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], c.from[i], c.to[i]); hi[i] = Math.max(hi[i], c.from[i], c.to[i]); } });
      // the largest grid step every corner sits on
      var s = null;
      for (var si = 0; si < STEPS.length && s === null; si++) {
        var st = STEPS[si], ok = list.every(function (c) { for (var i = 0; i < 3; i++) if (!onGrid(c.from[i] - lo[i], st) || !onGrid(c.to[i] - lo[i], st)) return false; return true; });
        if (ok) s = st;
      }
      var n = s ? [Math.round((hi[0] - lo[0]) / s), Math.round((hi[1] - lo[1]) / s), Math.round((hi[2] - lo[2]) / s)] : null;
      if (!s || n[0] * n[1] * n[2] > MAX_CELLS || n.some(function (v) { return v < 1; })) { stay.grid += list.length; return; }
      var cells = new Int32Array(n[0] * n[1] * n[2]), conflict = false;
      function at(x, y, z) { return (y * n[2] + z) * n[0] + x; }
      list.forEach(function (c) {
        var key = recolour(c) + 1, a = [], b = [];
        for (var i = 0; i < 3; i++) { a.push(Math.round((Math.min(c.from[i], c.to[i]) - lo[i]) / s)); b.push(Math.round((Math.max(c.from[i], c.to[i]) - lo[i]) / s)); }
        for (var y = a[1]; y < b[1]; y++) for (var z = a[2]; z < b[2]; z++) for (var x = a[0]; x < b[0]; x++) {
          var i2 = at(x, y, z);
          if (cells[i2] && cells[i2] !== key) conflict = true;
          cells[i2] = key;
        }
      });
      // two colours in the same place: which one shows is up to the renderer - left as it is
      if (conflict) { stay.overlap += list.length; return; }
      var boxes = M.greedyBoxes(n[0], n[1], n[2], function (x, y, z) { return cells[at(x, y, z)]; });
      if (boxes.length >= list.length) return;
      var first = list[0], names = {};
      list.forEach(function (c) { var col = recolour(c); if (!names[col]) names[col] = String(c.name || 'cube').replace(/[_\s]*\d+$/, '') || 'cube'; });
      var used = {};
      out.push({
        cubes: list, parent: first.parent, rotation: first.rotation.slice(), origin: first.origin.slice(), inflate: first.inflate || 0, glow: !!first.cpm_glow, visible: first.visibility !== false,
        boxes: boxes.map(function (b) {
          var col = b.k - 1, nm = names[col]; used[nm] = (used[nm] || 0) + 1;
          return { from: [M.snap(lo[0] + b.x * s), M.snap(lo[1] + b.y * s), M.snap(lo[2] + b.z * s)], to: [M.snap(lo[0] + (b.x + b.w) * s), M.snap(lo[1] + (b.y + b.h) * s), M.snap(lo[2] + (b.z + b.d) * s)], colour: col, name: nm, n: used[nm] };
        })
      });
    });
    // a name number only where a colour needs more than one box
    out.forEach(function (g) {
      var count = {}; g.boxes.forEach(function (b) { count[b.name] = (count[b.name] || 0) + 1; });
      g.boxes.forEach(function (b) { b.label = count[b.name] > 1 ? b.name + '_' + b.n : b.name; });
    });
    var before = 0, after = 0; out.forEach(function (g) { before += g.cubes.length; after += g.boxes.length; });
    return { groups: out, before: before, after: after, saved: before - after, stay: stay };
  }
  /** the cubes the tool works on: the selection (groups: everything inside), or the whole model */
  function targetCubes(whole) { return whole ? M.allCubes() : M.cubesOf(M.selNodes()); }
  /** joins the cubes of a plan (one undo entry) -> the new cubes */
  function apply(p) {
    if (!p.groups.length) return [];
    var gone = [], made = [];
    p.groups.forEach(function (g) { g.cubes.forEach(function (c) { gone.push(c); }); });
    M.edit(T('merge'), { outliner: true, elements: gone }, function () {
      p.groups.forEach(function (g) {
        g.boxes.forEach(function (b) {
          var c = M.newCube({ name: b.label, from: b.from, to: b.to, origin: g.origin, rotation: g.rotation, inflate: g.inflate, colour: b.colour }, g.parent);
          if (g.glow) c.cpm_glow = true;
          if (!g.visible) c.visibility = false;
          made.push(c);
        });
        g.cubes.forEach(function (c) { c.remove(); });
      });
      made.forEach(function (c) { M.forgetIds(c); });
    }, function () { return { outliner: true, elements: made }; });
    M.refresh();
    M.selectNodes(made);
    return made;
  }
  /** the 3D preview of a plan: the new boxes in their groups' frames */
  function previewPlan(p) {
    var boxes = [];
    p.groups.forEach(function (g) {
      var m = null, T3 = G.THREE;
      // the group chain, then the cubes' own turn around their pivot
      var chain = [];
      for (var q = g.parent; q && q !== 'root'; q = q.parent) chain.unshift(q);
      chain.forEach(function (n) { m = turn(m, n.origin, n.rotation); });
      m = turn(m, g.origin, g.rotation);
      g.boxes.forEach(function (b) { boxes.push({ from: [b.from[0] - g.inflate, b.from[1] - g.inflate, b.from[2] - g.inflate], to: [b.to[0] + g.inflate, b.to[1] + g.inflate, b.to[2] + g.inflate], matrix: m, col: b.colour }); });
      function turn(mm, o, r) {
        if (!r || (!r[0] && !r[1] && !r[2])) return mm;
        var t = new T3.Matrix4().makeTranslation(o[0], o[1], o[2]).multiply(new T3.Matrix4().makeRotationFromEuler(new T3.Euler(r[0] * M.DEG, r[1] * M.DEG, r[2] * M.DEG, 'ZYX'))).multiply(new T3.Matrix4().makeTranslation(-o[0], -o[1], -o[2]));
        return mm ? mm.clone().multiply(t) : t;
      }
    });
    M.preview(boxes, undefined, { opacity: 0.85 });
  }

  // =================================================================== the window
  var dlg = null;
  function stayText(st) {
    var out = [];
    // lang: merge.stay.{painted|overlap|grid|flat}
    ['painted', 'overlap', 'grid', 'flat'].forEach(function (k) { if (st[k]) out.push(T('merge.stay.' + k, st[k])); });
    return out;
  }
  function open() {
    var whole = !M.selNodes().length;
    dlg = new G.Dialog({
      id: 'cpmplus_model_merge', title: T('merge'), width: 480, buttons: [T('merge.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('merge.tip')) + '</p>', '<div class="cmp_merge_sum"></div>'],
      form: { what: { label: T('merge.f.what'), type: 'select', options: { sel: T('merge.f.sel'), all: T('merge.f.all') }, value: whole ? 'all' : 'sel' } },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        var p = plan(targetCubes(f.what === 'all'));
        M.clearPreview(); dlg = null;
        if (!p.groups.length) { G.Blockbench.showQuickMessage(T('merge.none'), 2500); return; }
        apply(p);
        G.Blockbench.showQuickMessage(T('merge.done', p.before, p.after), 2500);
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var p = plan(targetCubes(f.what === 'all')), box = dlg.object.querySelector('.cmp_merge_sum'), lines = [];
      if (p.groups.length) lines.push('<p class="cmp_merge_big">' + L.esc(T('merge.sum', p.before, p.after, p.saved)) + '</p>');
      else lines.push('<p class="cmp_merge_big">' + L.esc(T('merge.none')) + '</p>');
      stayText(p.stay).forEach(function (s) { lines.push('<p class="cmp_merge_stay">' + L.esc(s) + '</p>'); });
      if (box) box.innerHTML = lines.join('');
      previewPlan(p);
      M.dialogNote(dlg, T('merge.after'), M.allCubes().length - p.saved, false);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('merge preview failed', e); }
    return dlg;
  }
  var STYLE = 'cmp_merge_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_merge_big{font-size:1.15em;font-weight:600;margin:6px 0}\n.cmp_merge_stay{opacity:.8;margin:2px 0}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_merge', sec: 'organize', icon: 'join_full', key: 'merge', mode: 'edit', spots: ['edit', 'cube', 'group'], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.plan = plan; api.apply = apply; api.open = open; api.dialog = function () { return dlg; }; api.targetCubes = targetCubes;
  return api;
})
