/*
 * CPM Plugin+ beta33 "CPM+ Modelar" - adjusting parts:
 * - Alinhar e distribuir: line parts up on X / Y / Z (lowest side, middle, highest side) with a chosen part, the whole
 *   selection or the model origin; spread them with equal gaps (or a fixed gap); make one touch another (on top,
 *   under, left, right, in front, behind, with a gap). Works by what you see (boxes of rotated parts, parts inside
 *   rotated groups), live preview, one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var AX = ['x', 'y', 'z'];

  // =================================================================== Alinhar / distribuir / encostar
  // lang: align.do.{align|spread|touch}
  var DOS = ['align', 'spread', 'touch'];
  // lang: align.side.{up|down|left|right|front|back}
  var SIDES = { up: ['y', 1], down: ['y', -1], left: ['x', -1], right: ['x', 1], front: ['z', -1], back: ['z', 1] };
  var ADEF = { what: 'align', ax: 'none', ay: 'none', az: 'none', ref: '0', spreadAxis: 'x', gapMode: 'equal', gap: 0, side: 'up', touchGap: 0 };
  function lo(b, a) { return b.min[a]; }
  function hi(b, a) { return b.max[a]; }
  function mid(b, a) { return (b.min[a] + b.max[a]) / 2; }
  /**
   * the moves (model space) that line the parts up: {node -> [dx, dy, dz]}. o.ref: index of the reference part in
   * nodes, 'sel' = the box of the whole selection, 'origin' = the model origin.
   */
  function plan(o, nodes) {
    o = Object.assign({}, ADEF, o || {});
    var moves = nodes.map(function () { return [0, 0, 0]; }), boxes = nodes.map(M.nodeBox);
    var refIdx = /^\d+$/.test(String(o.ref)) ? Number(o.ref) : -1, refBox = refIdx >= 0 ? boxes[refIdx] : o.ref === 'sel' ? M.nodesBox(nodes) : null;
    if (o.what === 'align') {
      AX.forEach(function (a, ai) {
        var how = o['a' + a]; if (!how || how === 'none') return;
        var target = refBox ? (how === 'min' ? lo(refBox, a) : how === 'max' ? hi(refBox, a) : mid(refBox, a)) : 0;
        nodes.forEach(function (n, i) {
          if (i === refIdx) return;
          var v = how === 'min' ? lo(boxes[i], a) : how === 'max' ? hi(boxes[i], a) : mid(boxes[i], a);
          moves[i][ai] = M.snap(target - v);
        });
      });
    } else if (o.what === 'spread') {
      var a = o.spreadAxis, ai = AX.indexOf(a), order = nodes.map(function (n, i) { return i; }).sort(function (p, q) { return mid(boxes[p], a) - mid(boxes[q], a); });
      if (order.length >= 2) {
        var first = boxes[order[0]], last = boxes[order[order.length - 1]], sizes = 0;
        order.forEach(function (i) { sizes += hi(boxes[i], a) - lo(boxes[i], a); });
        var gap = o.gapMode === 'fixed' ? Number(o.gap) || 0 : order.length > 1 ? ((hi(last, a) - lo(first, a)) - sizes) / (order.length - 1) : 0;
        var at = lo(first, a);
        order.forEach(function (i, k) {
          if (k === 0) { at = hi(boxes[i], a) + gap; return; }
          moves[i][ai] = M.snap(at - lo(boxes[i], a));
          at += hi(boxes[i], a) - lo(boxes[i], a) + gap;
        });
      }
    } else if (o.what === 'touch') {
      var s = SIDES[o.side] || SIDES.up, ax = s[0], axi = AX.indexOf(ax), dir = s[1], g = Number(o.touchGap) || 0;
      var rb = refBox || M.nodesBox(nodes.filter(function (n, i) { return i !== refIdx; }));
      nodes.forEach(function (n, i) {
        if (i === refIdx) return;
        var b = boxes[i];
        moves[i][axi] = M.snap(dir > 0 ? hi(rb, ax) + g - lo(b, ax) : lo(rb, ax) - g - hi(b, ax));
      });
    }
    return moves;
  }
  function apply(o, nodes) {
    nodes = nodes || M.selNodes();
    if (!nodes.length) throw new Error(T('need.sel'));
    var moves = plan(o, nodes), moved = nodes.filter(function (n, i) { return moves[i][0] || moves[i][1] || moves[i][2]; });
    if (!moved.length) return 0;
    var cubes = M.cubesOf(moved), groups = M.groupsOf(moved);
    M.edit(T('align'), { elements: cubes, outliner: true, groups: groups }, function () {
      nodes.forEach(function (n, i) { var d = moves[i]; if (d[0] || d[1] || d[2]) M.moveBy(n, d); });
    });
    M.refresh();
    return moved.length;
  }
  function preview(o, nodes) {
    var moves = plan(o, nodes), items = [], THREE = G.THREE;
    nodes.forEach(function (n, i) {
      var d = moves[i]; if (!d[0] && !d[1] && !d[2]) return;
      var m = new THREE.Matrix4().makeTranslation(d[0], d[1], d[2]);
      M.cubesOf([n]).forEach(function (c) { if (c.mesh) items.push({ mesh: c.mesh, matrix: m.clone().multiply(M.meshMatrix(c.mesh)) }); });
    });
    M.previewClones(items);
    return items.length;
  }
  var adlg = null;
  function openAlign() {
    var nodes = M.selNodes();
    if (!nodes.length) { G.Blockbench.showQuickMessage(T('need.sel'), 2500); return null; }
    var refs = {};
    nodes.forEach(function (n, i) { refs[String(i)] = T('align.ref.part', n.name); });
    refs.sel = T('align.ref.sel'); refs.origin = T('align.ref.origin');
    var whats = {}; DOS.forEach(function (d) { whats[d] = T('align.do.' + d); });
    // lang: align.how.{none|mid}
    function hows(a) { var o = { none: T('align.how.none'), min: T('align.how.min.' + a), mid: T('align.how.mid'), max: T('align.how.max.' + a) }; return o; }
    // lang: align.how.min.{x|y|z}
    // lang: align.how.max.{x|y|z}
    var sides = {}; Object.keys(SIDES).forEach(function (s) { sides[s] = T('align.side.' + s); });
    var isA = function (f) { return f.what === 'align'; }, isS = function (f) { return f.what === 'spread'; }, isT = function (f) { return f.what === 'touch'; };
    adlg = new G.Dialog({
      id: 'cpmplus_model_align', title: T('align'), width: 480,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('align.tip')) + '</p>'],
      form: {
        what: { label: T('align.f.what'), type: 'select', options: whats, value: nodes.length > 2 ? 'align' : 'align' },
        ref: { label: T('align.f.ref'), type: 'select', options: refs, value: nodes.length > 1 ? '0' : 'origin', condition: function (f) { return isA(f) || isT(f); } },
        ax: { label: T('align.f.x'), type: 'select', options: hows('x'), value: 'none', condition: isA },
        ay: { label: T('align.f.y'), type: 'select', options: hows('y'), value: 'none', condition: isA },
        az: { label: T('align.f.z'), type: 'select', options: hows('z'), value: 'none', condition: isA },
        spreadAxis: { label: T('align.f.axis'), type: 'select', options: { x: 'X', y: 'Y', z: 'Z' }, value: 'x', condition: isS },
        // lang: align.gap.{equal|fixed}
        gapMode: { label: T('align.f.gapMode'), type: 'select', options: { equal: T('align.gap.equal'), fixed: T('align.gap.fixed') }, value: 'equal', condition: isS },
        gap: { label: T('align.f.gap'), type: 'number', value: 1, step: 0.5, condition: function (f) { return isS(f) && f.gapMode === 'fixed'; } },
        side: { label: T('align.f.side'), type: 'select', options: sides, value: 'up', condition: isT },
        touchGap: { label: T('align.f.touchGap'), type: 'number', value: 0, step: 0.5, condition: isT }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview();
        try { apply(f, nodes); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3000); return false; }
        adlg = null;
      },
      onCancel: function () { M.clearPreview(); adlg = null; }
    });
    adlg.show();
    function update(f) {
      var n = 0; try { n = preview(f, nodes); } catch (e) { M.clearPreview(); }
      var moving = 0; try { moving = plan(f, nodes).filter(function (d) { return d[0] || d[1] || d[2]; }).length; } catch (e) { }
      M.dialogNote(adlg, T('align.count', moving), M.allCubes().length, false);
    }
    try { update(adlg.getFormResult()); } catch (e) { }
    return adlg;
  }

  // =================================================================== Medir: a ruler tool (click a point, click another)
  var MEASURE = 'cpmplus_model_measure';
  var meas = { list: [], start: null, hover: null, obj: null, labels: [], raf: 0, onKey: null, onMove: null, canvas: null };
  function preview3() { return G.Preview && (G.Preview.selected || (G.Preview.all && G.Preview.all[0])); }
  /** model-space point under the mouse: on a cube (snapped to its nearest corner within 0.75 px, else the 0.5 px grid) or on the ground */
  function pointAt(ev) {
    var pv = preview3(), THREE = G.THREE; if (!pv || !pv.canvas) return null;
    var r = pv.canvas.getBoundingClientRect(); if (!r.width || !r.height) return null;
    var mouse = new THREE.Vector2((ev.clientX - r.left) / r.width * 2 - 1, -((ev.clientY - r.top) / r.height) * 2 + 1);
    var rc = new THREE.Raycaster(); rc.setFromCamera(mouse, pv.camera);
    var cubes = M.allCubes().filter(function (c) { return c.visibility !== false && c.mesh; }), meshes = cubes.map(function (c) { return c.mesh; });
    var hits = rc.intersectObjects(meshes, false), root = (G.Project && G.Project.model_3d) || null;
    var toModel = function (v) { if (!root) return v; root.updateMatrixWorld(true); return v.clone().applyMatrix4(new THREE.Matrix4().copy(root.matrixWorld).invert()); };
    if (hits.length) {
      var p = toModel(hits[0].point), cube = cubes[meshes.indexOf(hits[0].object)], best = null, bd = 0.75;
      if (cube) M.cubeCorners(cube).forEach(function (c) { var d = c.distanceTo(p); if (d < bd) { bd = d; best = c; } });
      if (best) return { p: best.clone(), on: cube ? cube.name : '' };
      return { p: M.V3(Math.round(p.x * 2) / 2, Math.round(p.y * 2) / 2, Math.round(p.z * 2) / 2), on: cube ? cube.name : '' };
    }
    var plane = new THREE.Plane(new THREE.Vector3(0, 1, 0), 0), q = new THREE.Vector3();
    if (rc.ray.intersectPlane(plane, q)) { q = toModel(q); return { p: M.V3(Math.round(q.x * 2) / 2, 0, Math.round(q.z * 2) / 2), on: '' }; }
    return null;
  }
  function label(a, b) {
    var d = [b.x - a.x, b.y - a.y, b.z - a.z].map(function (v) { return M.snap(Math.abs(v)); }), len = M.snap(a.distanceTo(b));
    var s = T('measure.len', M.num(len), M.num(d[0]), M.num(d[1]), M.num(d[2]));
    if (len >= 16) s += ' \u00B7 ' + T('measure.blocks', M.num(len / 16));
    return s;
  }
  /** adds a finished measurement (also used by QA): a, b = model-space points; returns its text */
  function addMeasure(a, b) { var m = { a: a.clone(), b: b.clone(), text: label(a, b) }; meas.list.push(m); drawMeasures(); return m.text; }
  function clearMeasures() { meas.list = []; meas.start = null; meas.hover = null; drawMeasures(); }
  function drawMeasures() {
    var THREE = G.THREE, root = (G.Project && G.Project.model_3d) || null;
    if (meas.obj && meas.obj.parent) meas.obj.parent.remove(meas.obj);
    meas.labels.forEach(function (l) { l.el.remove(); }); meas.labels = [];
    meas.obj = null;
    if (!root) return;
    var grp = new THREE.Group(); grp.name = 'cpmplus_model_measure';
    var mat = new THREE.LineBasicMaterial({ color: 0xffd54f, depthTest: false, transparent: true }), dotMat = new THREE.MeshBasicMaterial({ color: 0xffd54f, depthTest: false });
    var all = meas.list.slice();
    if (meas.start && meas.hover) all.push({ a: meas.start, b: meas.hover, text: label(meas.start, meas.hover), live: true });
    else if (meas.start) all.push({ a: meas.start, b: meas.start, text: '', live: true });
    all.forEach(function (m) {
      var geo = new THREE.BufferGeometry().setFromPoints([m.a, m.b]), line = new THREE.Line(geo, mat); line.renderOrder = 999; grp.add(line);
      [m.a, m.b].forEach(function (p) { var s = new THREE.Mesh(new THREE.SphereGeometry(0.25, 8, 6), dotMat); s.position.copy(p); s.renderOrder = 999; grp.add(s); });
      if (m.text) {
        var el = document.createElement('div'); el.className = 'cmp_measure' + (m.live ? ' live' : ''); el.textContent = m.text;
        document.body.appendChild(el);
        meas.labels.push({ el: el, at: m.a.clone().add(m.b).multiplyScalar(0.5) });
      }
    });
    root.add(grp); meas.obj = grp;
    placeLabels();
  }
  function placeLabels() {
    var pv = preview3(), root = (G.Project && G.Project.model_3d) || null; if (!pv || !pv.canvas) return;
    var r = pv.canvas.getBoundingClientRect();
    meas.labels.forEach(function (l) {
      var v = l.at.clone(); if (root) { root.updateMatrixWorld(true); v.applyMatrix4(root.matrixWorld); }
      v.project(pv.camera);
      var x = r.left + (v.x + 1) / 2 * r.width, y = r.top + (1 - v.y) / 2 * r.height, vis = v.z < 1 && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom;
      l.el.style.left = Math.round(x) + 'px'; l.el.style.top = Math.round(y) + 'px'; l.el.style.display = vis ? '' : 'none';
    });
  }
  function loop() { placeLabels(); meas.raf = requestAnimationFrame(loop); }
  function measureOn() {
    var pv = preview3();
    meas.onKey = function (e) { if (e.key === 'Escape') clearMeasures(); };
    meas.onMove = function (e) { if (!meas.start) return; var h = pointAt(e); if (h) { meas.hover = h.p; drawMeasures(); } };
    document.addEventListener('keydown', meas.onKey);
    meas.canvas = pv && pv.canvas; if (meas.canvas) meas.canvas.addEventListener('mousemove', meas.onMove);
    if (!meas.raf) meas.raf = requestAnimationFrame(loop);
    G.Blockbench.showQuickMessage(T('measure.hint'), 3000);
  }
  function measureOff() {
    if (meas.onKey) document.removeEventListener('keydown', meas.onKey);
    if (meas.canvas && meas.onMove) meas.canvas.removeEventListener('mousemove', meas.onMove);
    meas.onKey = meas.onMove = meas.canvas = null;
    if (meas.raf) cancelAnimationFrame(meas.raf); meas.raf = 0;
    clearMeasures();
    if (meas.obj && meas.obj.parent) meas.obj.parent.remove(meas.obj); meas.obj = null;
  }
  /** a click with the ruler: first point, then the second (the measurement stays until Esc / another tool) */
  function measureClick(ev) {
    var h = pointAt(ev); if (!h) return null;
    if (!meas.start) { meas.start = h.p; meas.hover = null; drawMeasures(); return null; }
    var text = addMeasure(meas.start, h.p);
    meas.start = null; meas.hover = null; drawMeasures();
    return text;
  }
  function installMeasure() {
    var tool = L.mk('Tool', MEASURE, {
      name: T('measure'), description: T('measure.desc'), icon: 'straighten', category: 'tools', modes: ['edit'], cursor: 'crosshair',
      transformerMode: 'hidden', selectFace: false, selectElements: false,
      onCanvasClick: function (data) { var ev = data && data.event; if (ev) measureClick(ev); },
      onSelect: function () { measureOn(); },
      onUnselect: function () { measureOff(); }
    });
    L.place('tools', tool, 'vertex_snap_tool');
    L.onUninstall(function () { measureOff(); });
    if (!document.getElementById('cmp_measure_style')) {
      var st = document.createElement('style'); st.id = 'cmp_measure_style';
      st.textContent = '.cmp_measure{position:fixed;z-index:30;transform:translate(-50%,-130%);background:rgba(20,20,24,.85);color:#ffd54f;padding:2px 6px;border-radius:4px;font-size:12px;pointer-events:none;white-space:nowrap;border:1px solid #ffd54f}.cmp_measure.live{opacity:.85}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById('cmp_measure_style'); if (e) e.remove(); });
    }
    return tool;
  }

  // =================================================================== Copiar atributos (from one part to the others)
  // lang: copy.a.{size|rotation|pivot|inflate|uv|visibility|marker|recolor|glow}
  var ATTRS = ['size', 'rotation', 'pivot', 'inflate', 'uv', 'visibility', 'marker', 'recolor', 'glow'];
  var CPM_ATTRS = { recolor: 1, glow: 1 };
  /** what can be copied from src (cube or group) */
  function attrsFor(src) {
    if (M.isGroup(src)) return ['rotation', 'pivot', 'visibility'];
    return ATTRS.filter(function (a) { return !CPM_ATTRS[a] || M.isCpm(); });
  }
  function boxOf(c) { var lo = [], hi = []; for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i])); hi.push(Math.max(c.from[i], c.to[i])); } return { lo: lo, hi: hi }; }
  /** copies the checked attributes of src onto every target (one undo entry); returns how many targets changed */
  function copyAttrs(src, targets, which) {
    which = which || {};
    targets = targets.filter(function (t) { return t !== src; });
    if (!src || !targets.length) throw new Error(T('need.two'));
    var cubes = targets.filter(M.isCube), groups = targets.filter(M.isGroup);
    M.edit(T('copy'), { elements: cubes, groups: groups, outliner: true }, function () {
      targets.forEach(function (t) {
        var sameKind = M.isCube(t) === M.isCube(src), cubes2 = sameKind && M.isCube(t);
        if (which.rotation && t.rotation && src.rotation) for (var i = 0; i < 3; i++) t.rotation[i] = src.rotation[i];
        if (which.visibility) t.visibility = src.visibility;
        if (cubes2 && which.size) {
          // each target keeps its centre and takes the source's size
          var s = boxOf(src), b = boxOf(t);
          for (var a = 0; a < 3; a++) {
            var size = s.hi[a] - s.lo[a], mid = (b.lo[a] + b.hi[a]) / 2, nlo = M.snap(mid - size / 2), nhi = M.snap(mid + size / 2);
            if (t.from[a] <= t.to[a]) { t.from[a] = nlo; t.to[a] = nhi; } else { t.from[a] = nhi; t.to[a] = nlo; }
          }
        }
        // after the size: the pivot goes to the same point of the (new) box, e.g. the bottom middle of every feather
        if (which.pivot && t.origin && src.origin) {
          if (cubes2) {
            var bs = boxOf(src), bt = boxOf(t);
            for (var k = 0; k < 3; k++) { var span = bs.hi[k] - bs.lo[k], f = span ? (src.origin[k] - bs.lo[k]) / span : 0.5; t.origin[k] = M.snap(bt.lo[k] + f * (bt.hi[k] - bt.lo[k])); }
          } else for (var j = 0; j < 3; j++) t.origin[j] = src.origin[j];
        }
        if (!cubes2) return;
        if (which.inflate) t.inflate = src.inflate || 0;
        if (which.marker && src.color !== undefined) t.color = src.color;
        if (which.recolor && M.isCpm()) t.cpm_recolor = src.cpm_recolor;
        if (which.glow && M.isCpm()) t.cpm_glow = !!src.cpm_glow;
        if (which.uv) {
          t.box_uv = src.box_uv; t.mirror_uv = src.mirror_uv;
          if (src.uv_offset) t.uv_offset = src.uv_offset.slice();
          Object.keys(src.faces || {}).forEach(function (f) {
            var sf = src.faces[f], tf = t.faces && t.faces[f]; if (!sf || !tf) return;
            if (sf.uv) tf.uv = sf.uv.slice(); tf.texture = sf.texture; if (sf.rotation !== undefined) tf.rotation = sf.rotation;
          });
        }
      });
    });
    M.refresh();
    return targets.length;
  }
  var cdlg = null;
  function openCopy() {
    var nodes = M.selNodes();
    if (nodes.length < 2) { G.Blockbench.showQuickMessage(T('need.two'), 2500); return null; }
    var srcs = {}; nodes.forEach(function (n, i) { srcs[String(i)] = n.name + (M.isGroup(n) ? ' (' + T('copy.group') + ')' : ''); });
    var form = { src: { label: T('copy.f.src'), type: 'select', options: srcs, value: '0' } };
    ATTRS.forEach(function (a) {
      if (CPM_ATTRS[a] && !M.isCpm()) return;
      form['a_' + a] = { label: T('copy.a.' + a), type: 'checkbox', value: a === 'size', condition: function (f) { var s = nodes[Number(f.src)] || nodes[0]; return attrsFor(s).indexOf(a) >= 0; } };
    });
    cdlg = new G.Dialog({
      id: 'cpmplus_model_copy', title: T('copy'), width: 440,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('copy.tip')) + '</p>'],
      form: form,
      onConfirm: function (f) {
        var src = nodes[Number(f.src)] || nodes[0], which = {};
        attrsFor(src).forEach(function (a) { which[a] = !!f['a_' + a]; });
        try { copyAttrs(src, nodes, which); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3000); return false; }
        cdlg = null;
      },
      onCancel: function () { cdlg = null; }
    });
    cdlg.show();
    return cdlg;
  }

  // =================================================================== registration
  api.install = function () {
    M.register({ id: 'cpmplus_model_align', sec: 'adjust', icon: 'align_horizontal_center', key: 'align', mode: 'edit', need: 'sel', spots: ['position', 'transform', 'cube', 'group'], run: function () { openAlign(); } });
    M.register({ id: 'cpmplus_model_copy', sec: 'adjust', icon: 'content_paste_go', key: 'copy', mode: 'edit', need: 'two', spots: ['transform', 'cube', 'group'], run: function () { openCopy(); } });
    installMeasure();
    M.register({ id: 'cpmplus_model_measure_pick', sec: 'adjust', icon: 'straighten', key: 'measure', mode: 'edit', spots: [], run: function () { L.selectTool(MEASURE); } });
  };
  api.uninstall = function () {
    [adlg, cdlg].forEach(function (d) { if (d) { try { d.close(); } catch (e) { } } });
    adlg = null; cdlg = null; M.clearPreview(); measureOff();
  };
  api.copyAttrs = function (src, targets, which) { return copyAttrs(src, targets, which); };
  api.openCopy = openCopy;
  api.measure = { add: function (a, b) { return addMeasure(M.V3(a[0], a[1], a[2]), M.V3(b[0], b[1], b[2])); }, clear: clearMeasures, list: function () { return meas.list.map(function (m) { return m.text; }); },
    click: measureClick, pointAt: pointAt, labels: function () { return meas.labels.map(function (l) { return { text: l.el.textContent, shown: l.el.style.display !== 'none' }; }); } };
  api.plan = function (o, nodes) { return plan(o, nodes || M.selNodes()); };
  api.align = function (o, nodes) { return apply(o, nodes); };
  api.openAlign = openAlign;
  return api;
})
