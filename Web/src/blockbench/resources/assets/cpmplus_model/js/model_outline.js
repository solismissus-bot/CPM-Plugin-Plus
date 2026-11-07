/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Contorno: a cartoon outline around the selected cubes / groups, made of thin dark
 * bars on the edges of their shape (colour cubes: no skin space, any thickness).
 * Why bars and not a bigger inside-out copy (the 'inverted hull' of Blockbench's Outline Creator / Bedrock models): CPM
 * draws the player's parts with Minecraft's entity_translucent render type, which has back-face culling OFF (and
 * Blockbench's CPM format draws both sides too, like the game), so a bigger inside-out shell is drawn from the front as
 * well and paints the whole model black; the export also turns inside-out cubes (to < from) back into normal ones.
 * Bars on the edges look the same in Blockbench and in the game, from every side.
 * - cubes that touch, in the same group with the same turn, are outlined as ONE shape: only the edges of the shape get
 *   a bar (no line across a flat face where two cubes meet); collinear pieces become one long bar;
 * - each bar is centred on its edge (half of it outside: the silhouette grows by half the thickness), never lying on a
 *   face (no z-fighting);
 * - the bars go in a group 'contorno' inside the group of the cubes (they follow its animations), with the cubes' own
 *   turn; the eye in the Outliner shows / hides them; running it again replaces that outline;
 * - thickness 0.25 - 1 px, colour (default near-black); Mais opcoes: lines in the inner corners, each cube on its own.
 * Live 3D preview; one Ctrl+Z. Loaded after the modelling engine (L.core.modules.model) and model_slice.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var EPS = 1e-4, MAX_CELLS = 1500000;
  // names an outline group can have (both languages): such groups are never outlined again, and are replaced
  var NAMES = ['contorno', 'outline'];
  var last = { thick: 0.5, colour: '#1a1a1a', concave: false, each: false };

  // =================================================================== what gets an outline
  /** the box you see (inflate included), in the cube's own coordinates */
  function vbox(c) {
    var inf = c.inflate || 0, lo = [], hi = [];
    for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i]) - inf); hi.push(Math.max(c.from[i], c.to[i]) + inf); }
    return { lo: lo, hi: hi };
  }
  function isOutlineGroup(g) {
    if (!M.isGroup(g)) return false;
    var n = String(g.name || '').toLowerCase();
    return NAMES.indexOf(n) >= 0 || n === String(T('outline.group')).toLowerCase();
  }
  function inOutline(n) { for (var p = n; p && p !== 'root'; p = p.parent) if (isOutlineGroup(p)) return true; return false; }
  function turned(c) { var r = c.rotation || [0, 0, 0]; return !!(r[0] || r[1] || r[2]); }
  /** why a cube gets no outline (lang: outline.why.{mesh|extrude|flat}) or null */
  function refuse(c) {
    if (!M.isCube(c)) return 'mesh';
    if (c.cpm_extrude) return 'extrude';
    var b = vbox(c);
    for (var i = 0; i < 3; i++) if (b.hi[i] - b.lo[i] < EPS) return 'flat';
    return null;
  }
  /** cubes with the same key are one shape: same group, same turn (and pivot when turned) */
  function bucketKey(c) {
    var p = c.parent && c.parent !== 'root' ? c.parent.uuid : 'root';
    return p + '|' + (turned(c) ? c.rotation.join(',') + '@' + c.origin.join(',') : '-');
  }
  function kidsOf(p) { return p && p !== 'root' ? (p.children || []) : ((G.Outliner && G.Outliner.root) || []); }
  /** the outline group already inside a group (or at the top of the model) */
  function existingOutline(p) { var k = kidsOf(p); for (var i = 0; i < k.length; i++) if (isOutlineGroup(k[i])) return k[i]; return null; }

  // =================================================================== the edges of a shape (pure)
  function uniq(vals) {
    vals.sort(function (a, b) { return a - b; });
    var out = [];
    vals.forEach(function (v) { if (!out.length || v - out[out.length - 1] > EPS) out.push(v); });
    return out;
  }
  function indexIn(arr, v) {
    var lo = 0, hi = arr.length - 1;
    while (lo <= hi) { var m = (lo + hi) >> 1; if (Math.abs(arr[m] - v) <= EPS) return m; if (arr[m] < v) lo = m + 1; else hi = m - 1; }
    return -1;
  }
  /**
   * the edges of the shape that the boxes ([{lo, hi}], one frame) make together: [{a (axis), at: [b, c] (where the line
   * is on the two other axes), from, to (along a)}] - convex edges, plus the inner corners when concave. The space is
   * cut only at the boxes' own planes, so any size works exactly. null when the shape is too complex.
   */
  function shapeEdges(boxes, concave) {
    var C = [0, 1, 2].map(function (a) { var v = []; boxes.forEach(function (b) { v.push(b.lo[a], b.hi[a]); }); return uniq(v); });
    var N = C.map(function (c) { return c.length - 1; });
    if (N[0] < 1 || N[1] < 1 || N[2] < 1 || N[0] * N[1] * N[2] > MAX_CELLS) return null;
    var fill = new Uint8Array(N[0] * N[1] * N[2]);
    function at(x, y, z) { return (z * N[1] + y) * N[0] + x; }
    boxes.forEach(function (b) {
      var i0 = [0, 1, 2].map(function (a) { return indexIn(C[a], b.lo[a]); }), i1 = [0, 1, 2].map(function (a) { return indexIn(C[a], b.hi[a]); });
      for (var z = i0[2]; z < i1[2]; z++) for (var y = i0[1]; y < i1[1]; y++) for (var x = i0[0]; x < i1[0]; x++) fill[at(x, y, z)] = 1;
    });
    var p = [0, 0, 0];
    function q(a, b, c, ia, ib, ic) {
      if (ib < 0 || ic < 0 || ib >= N[b] || ic >= N[c]) return 0;
      p[a] = ia; p[b] = ib; p[c] = ic;
      return fill[at(p[0], p[1], p[2])];
    }
    function edgeAt(a, b, c, ia, jb, jc) {
      var q00 = q(a, b, c, ia, jb - 1, jc - 1), q10 = q(a, b, c, ia, jb, jc - 1), q01 = q(a, b, c, ia, jb - 1, jc), q11 = q(a, b, c, ia, jb, jc);
      var n = q00 + q10 + q01 + q11;
      if (n === 1) return true;
      // two cells touching only along this line: two edges at the same place
      if (n === 2) return (q00 && q11) || (q10 && q01) ? true : false;
      if (n === 3) return !!concave;
      return false;
    }
    var out = [];
    for (var a = 0; a < 3; a++) {
      var b = (a + 1) % 3, c = (a + 2) % 3;
      for (var jb = 0; jb <= N[b]; jb++) for (var jc = 0; jc <= N[c]; jc++) {
        var run = -1;
        for (var ia = 0; ia <= N[a]; ia++) {
          var e = ia < N[a] && edgeAt(a, b, c, ia, jb, jc);
          if (e && run < 0) run = ia;
          else if (!e && run >= 0) {
            var at2 = [0, 0, 0]; at2[b] = C[b][jb]; at2[c] = C[c][jc];
            out.push({ a: a, at: at2, from: C[a][run], to: C[a][ia] });
            run = -1;
          }
        }
      }
    }
    return out;
  }
  /**
   * the boxes that touch or overlap (also only along an edge or at a corner) in groups of indices: two groups never
   * share an edge, so each one is outlined on its own (small grids, the same result as the whole bucket)
   */
  function components(boxes) {
    var up = boxes.map(function (b, i) { return i; });
    function root(i) { while (up[i] !== i) { up[i] = up[up[i]]; i = up[i]; } return i; }
    for (var i = 0; i < boxes.length; i++) for (var j = i + 1; j < boxes.length; j++) {
      var a = boxes[i], b = boxes[j], touch = true;
      for (var k = 0; k < 3 && touch; k++) touch = a.lo[k] <= b.hi[k] + EPS && b.lo[k] <= a.hi[k] + EPS;
      if (touch) up[root(i)] = root(j);
    }
    var out = {}, order = [];
    boxes.forEach(function (b, i) { var r = root(i); if (!out[r]) { out[r] = []; order.push(r); } out[r].push(i); });
    return order.map(function (r) { return out[r]; });
  }
  /** a bar t thick centred on an edge, a little longer at both ends (t / 2) so the corners close */
  function barOf(e, t) {
    var h = t / 2, lo = [0, 0, 0], hi = [0, 0, 0];
    for (var i = 0; i < 3; i++) {
      if (i === e.a) { lo[i] = e.from - h; hi[i] = e.to + h; }
      else { lo[i] = e.at[i] - h; hi[i] = e.at[i] + h; }
    }
    return { a: e.a, lo: lo.map(M.snap), hi: hi.map(M.snap) };
  }
  function clampT(v) { v = Number(v); if (!(v > 0)) v = 0.5; return Math.max(0.25, Math.min(1, v)); }

  // =================================================================== the plan
  /**
   * o = {thick, concave, each} -> {buckets: [{parent, rotation, origin, turned, cubes, edges, bars, split}], bars (count),
   * why: {reason: [names]}, existing: [outline groups in the groups the bars go to]}
   */
  function plan(cubes, o) {
    o = o || {};
    var t = clampT(o.thick), buckets = {}, order = [], why = {};
    (cubes || []).forEach(function (c) {
      if (!c || c.export === false || inOutline(c)) return;
      var w = refuse(c);
      if (w) { (why[w] = why[w] || []).push(c.name); return; }
      var k = o.each ? 'cube:' + c.uuid : bucketKey(c);
      if (!buckets[k]) { buckets[k] = { parent: c.parent || 'root', rotation: (c.rotation || [0, 0, 0]).slice(), origin: c.origin.slice(), turned: turned(c), cubes: [] }; order.push(k); }
      buckets[k].cubes.push(c);
    });
    var out = [], total = 0;
    order.forEach(function (k) {
      var bk = buckets[k], boxes = bk.cubes.map(vbox), edges = [];
      components(boxes).forEach(function (idx) {
        var bs = idx.map(function (i) { return boxes[i]; }), e = shapeEdges(bs, o.concave);
        if (!e) {
          // too complex to see as one shape: every cube of it on its own
          bk.split = true;
          bs.forEach(function (b) { edges = edges.concat(shapeEdges([b], false) || []); });
        } else edges = edges.concat(e);
      });
      bk.edges = edges;
      bk.bars = edges.map(function (e) { return barOf(e, t); });
      total += bk.bars.length;
      out.push(bk);
    });
    var existing = [];
    out.forEach(function (bk) { var g = existingOutline(bk.parent); if (g && existing.indexOf(g) < 0) existing.push(g); });
    return { buckets: out, bars: total, why: why, existing: existing, t: t };
  }
  /** the cubes the tool works on: everything inside the selection */
  function targetCubes() { return M.cubesOf(M.selNodes()); }

  // =================================================================== applying (one undo entry)
  /** o = {colour, replace (the outline groups there now are replaced; else the new bars are added to them)} */
  function apply(p, o) {
    o = o || {};
    if (!p || !p.bars) return null;
    var colour = M.rgbOf(o.colour === undefined ? last.colour : o.colour), old = o.replace === false ? [] : p.existing.slice();
    var oldCubes = M.cubesOf(old), oldGroups = M.groupsOf(old), madeCubes = [], madeGroups = [];
    M.edit(T('outline'), { outliner: true, elements: oldCubes, groups: oldGroups }, function () {
      old.forEach(function (g) { g.remove(); });
      var groupOf = new Map(), count = new Map();
      p.buckets.forEach(function (bk) {
        var g = groupOf.get(bk.parent);
        if (!g) {
          // adding to an outline group that stays: it is not new (the Outliner aspect keeps its new children)
          g = o.replace === false ? existingOutline(bk.parent) : null;
          if (!g) {
            var po = bk.parent && bk.parent !== 'root' && bk.parent.origin ? bk.parent.origin.slice() : [0, 0, 0];
            g = M.newGroup(T('outline.group'), po, bk.parent);
            madeGroups.push(g);
          }
          groupOf.set(bk.parent, g);
          count.set(g, M.cubesOf([g]).length);
        }
        var origin = bk.turned ? bk.origin : g.origin.slice();
        bk.bars.forEach(function (b) {
          var n = count.get(g) + 1; count.set(g, n);
          madeCubes.push(M.newCube({ name: T('outline.bar') + '_' + n, from: b.lo, to: b.hi, origin: origin, rotation: bk.turned ? bk.rotation : [0, 0, 0], colour: colour }, g));
        });
      });
      madeCubes.forEach(function (c) { M.forgetIds(c); });
    }, function () { return { outliner: true, elements: madeCubes, groups: madeGroups }; });
    M.refresh();
    return { cubes: madeCubes, groups: madeGroups, replaced: old.length };
  }

  // =================================================================== the 3D preview (the bars as they will look)
  var pv = null;
  function render() { try { if (G.Preview && G.Preview.all) G.Preview.all.forEach(function (x) { if (x.render) x.render(); }); } catch (e) { } }
  function clearPv() {
    if (!pv) return;
    try { if (pv.parent) pv.parent.remove(pv); } catch (e) { }
    pv.traverse(function (x) { try { if (x.geometry) x.geometry.dispose(); } catch (e) { } });
    (pv.userData.mats || []).forEach(function (m) { try { m.dispose(); } catch (e) { } });
    pv = null; render();
  }
  function previewPlan(p, colour) {
    M.clearPreview(); clearPv();
    var root = G.Project && G.Project.model_3d, THREE = G.THREE, S = L.core.modules.model_slice;
    if (!root || !THREE || !p) return;
    var grp = new THREE.Group(); grp.name = 'cpmplus_model_preview';
    var mat = new THREE.MeshBasicMaterial({ color: new THREE.Color(M.rgbOf(colour)), transparent: true, opacity: 0.95 });
    grp.userData.mats = [mat];
    p.buckets.forEach(function (bk) {
      var holder = new THREE.Object3D(), m = S && S.frameOf ? S.frameOf(bk.cubes[0]) : null;
      if (m) { holder.matrixAutoUpdate = false; holder.matrix.copy(m); }
      bk.bars.forEach(function (b) {
        var mesh = new THREE.Mesh(new THREE.BoxGeometry(b.hi[0] - b.lo[0], b.hi[1] - b.lo[1], b.hi[2] - b.lo[2]), mat);
        mesh.position.set((b.lo[0] + b.hi[0]) / 2, (b.lo[1] + b.hi[1]) / 2, (b.lo[2] + b.hi[2]) / 2);
        mesh.userData.bar = true;
        holder.add(mesh);
      });
      grp.add(holder);
    });
    root.add(grp); pv = grp;
    render();
  }

  // =================================================================== the window
  var dlg = null;
  function names(list) { return list.slice(0, 4).join(', ') + (list.length > 4 ? ' (+' + (list.length - 4) + ')' : ''); }
  function groupLabel(g) { var p = g.parent; return p && p !== 'root' ? p.name : T('outline.top'); }
  function open() {
    var cubes = targetCubes();
    if (!cubes.length) { G.Blockbench.showQuickMessage(T('need.sel'), 2500); return null; }
    var first = plan(cubes, last);
    dlg = new G.Dialog({
      id: 'cpmplus_model_outline', title: T('outline'), width: 480, buttons: [T('outline.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('outline.tip')) + '</p>', '<div class="cmp_outline_sum"></div>'],
      form: {
        thick: { label: T('outline.f.thick'), type: 'number', value: last.thick, min: 0.25, max: 1, step: 0.25 },
        colour: { label: T('outline.f.colour'), type: 'color', value: last.colour },
        replace: { label: T('outline.f.replace'), type: 'checkbox', value: true, condition: function () { return first.existing.length > 0; } },
        more: { label: T('outline.f.more'), type: 'checkbox', value: !!(last.concave || last.each) },
        concave: { label: T('outline.f.concave'), description: T('outline.f.concave.desc'), type: 'checkbox', value: last.concave, condition: function (f) { return !!f.more; } },
        each: { label: T('outline.f.each'), description: T('outline.f.each.desc'), type: 'checkbox', value: last.each, condition: function (f) { return !!f.more; } }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        var o = opts(f), p = plan(cubes, o);
        clearPv(); M.clearPreview(); dlg = null;
        if (!p.bars) { G.Blockbench.showQuickMessage(T('outline.none'), 2500); return; }
        last = { thick: o.thick, colour: M.hexOf(M.rgbOf(o.colour)), concave: o.concave, each: o.each };
        var r = apply(p, { colour: o.colour, replace: f.replace !== false });
        G.Blockbench.showQuickMessage(T('outline.done', r.cubes.length), 2200);
      },
      onCancel: function () { clearPv(); M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function opts(f) { return { thick: clampT(f.thick), colour: f.colour || last.colour, concave: !!(f.more && f.concave), each: !!(f.more && f.each) }; }
    function update(f) {
      var o = opts(f), p = plan(cubes, o), box = dlg && dlg.object && dlg.object.querySelector('.cmp_outline_sum'), lines = [];
      var parents = []; p.buckets.forEach(function (bk) { if (parents.indexOf(bk.parent) < 0) parents.push(bk.parent); });
      lines.push('<p class="cmp_outline_big">' + L.esc(p.bars ? T('outline.sum', p.bars, parents.length) : T('outline.none')) + '</p>');
      if (p.existing.length && f.replace !== false) lines.push('<p class="cmp_outline_note">' + L.esc(T('outline.replaces', p.existing.map(groupLabel).join(', '))) + '</p>');
      if (p.buckets.some(function (bk) { return bk.split; })) lines.push('<p class="cmp_outline_note">' + L.esc(T('outline.split')) + '</p>');
      // lang: outline.why.{mesh|extrude|flat}
      Object.keys(p.why).forEach(function (k) { lines.push('<p class="cmp_outline_note">' + L.esc(T('outline.why.' + k, names(p.why[k]))) + '</p>'); });
      if (box) box.innerHTML = lines.join('');
      var gone = p.existing.length && f.replace !== false ? M.cubesOf(p.existing).length : 0;
      M.dialogNote(dlg, T('outline.after'), M.allCubes().length + p.bars - gone, false);
      previewPlan(p, o.colour);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('outline preview failed', e); }
    return dlg;
  }

  // =================================================================== registration
  var STYLE = 'cmp_outline_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_outline_big{font-size:1.1em;font-weight:600;margin:6px 0}\n.cmp_outline_note{opacity:.8;margin:2px 0}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_outline', sec: 'create', icon: 'border_outer', key: 'outline', mode: 'edit', need: 'sel', cpm: true, spots: ['cube', 'group'], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } clearPv(); };
  // QA / other modules
  api.plan = plan; api.apply = apply; api.shapeEdges = shapeEdges; api.barOf = barOf; api.isOutlineGroup = isOutlineGroup; api.open = open;
  api.outline = function (nodes, o) { o = o || {}; return apply(plan(M.cubesOf(nodes || M.selNodes()), o), o); };
  api.dialog = function () { return dlg; };
  return api;
})
