/*
 * CPM Plugin+ "CPM+ Modelar" - Construir com blocos / puxar face: a tool in Blockbench's row of tools (after Medir):
 * - click a face of a cube: a new block against it, the same section as that face, 1 px deep, in the same frame (same
 *   group, turn and pivot), the cube's colour (colour cubes) or the colour picked in the Color panel;
 * - drag a face: the face is pulled out / pushed in along its normal (the cube grows / shrinks, whole pixels; Ctrl =
 *   quarter pixels), live;
 * - Shift + drag a face: a new block pulled out of the face as deep as you drag.
 * One undo step per click / drag; Esc while dragging puts everything back. Empty space still turns the camera.
 * Loaded after the modelling engine; the tool id is in art_core's TOOL_ROW (kept in the row of tools).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var TOOL = 'cpmplus_model_build';
  var AXIS_FACE = { east: [0, 1], west: [0, -1], up: [1, 1], down: [1, -1], south: [2, 1], north: [2, -1] };
  var DRAG_PX = 5;
  var st = { on: false, down: null, drag: null, label: null, listeners: [] };

  // =================================================================== picking a face under the mouse
  function previewOf(target) { var all = (G.Preview && G.Preview.all) || []; for (var i = 0; i < all.length; i++) if (all[i].canvas === target) return all[i]; return null; }
  function frameQuat(c) { var q = M.parentQuat(c), own = M.nodeQuat(c); if (own) q.multiply(own); return q; }
  /** the cube face under a mouse event: {cube, face, axis, dir (cube's own frame), point, normal (model space), preview} */
  function hitAt(ev, pv) {
    pv = pv || previewOf(ev.target) || (G.Preview && G.Preview.selected);
    var THREE = G.THREE; if (!pv || !pv.canvas || !THREE) return null;
    var r = pv.canvas.getBoundingClientRect(); if (!r.width || !r.height) return null;
    var mouse = new THREE.Vector2((ev.clientX - r.left) / r.width * 2 - 1, -((ev.clientY - r.top) / r.height) * 2 + 1);
    var rc = new THREE.Raycaster(); rc.setFromCamera(mouse, pv.camera);
    var cubes = M.allCubes().filter(function (c) { return c.visibility !== false && c.mesh && !c.locked; }), meshes = cubes.map(function (c) { return c.mesh; });
    var hits = rc.intersectObjects(meshes, false);
    for (var i = 0; i < hits.length; i++) {
      var h = hits[i], c = cubes[meshes.indexOf(h.object)]; if (!c || !h.face) continue;
      var n = h.face.normal, a = [Math.abs(n.x), Math.abs(n.y), Math.abs(n.z)], k = a.indexOf(Math.max.apply(null, a)), dir = [n.x, n.y, n.z][k] > 0 ? 1 : -1;
      var face = null; Object.keys(AXIS_FACE).forEach(function (f) { if (AXIS_FACE[f][0] === k && AXIS_FACE[f][1] === dir) face = f; });
      var root = (G.Project && G.Project.model_3d) || null, p = h.point.clone();
      if (root) { root.updateMatrixWorld(true); p.applyMatrix4(new THREE.Matrix4().copy(root.matrixWorld).invert()); }
      var nl = [0, 0, 0]; nl[k] = dir;
      return { cube: c, face: face, axis: k, dir: dir, point: p, normal: M.V3(nl[0], nl[1], nl[2]).applyQuaternion(frameQuat(c)).normalize(), preview: pv };
    }
    return null;
  }
  function screenOf(pv, p) {
    var v = p.clone(), root = (G.Project && G.Project.model_3d) || null, r = pv.canvas.getBoundingClientRect();
    if (root) { root.updateMatrixWorld(true); v.applyMatrix4(root.matrixWorld); }
    v.project(pv.camera);
    return [r.left + (v.x + 1) / 2 * r.width, r.top + (1 - v.y) / 2 * r.height];
  }

  // =================================================================== the box of a cube (its own frame)
  function lohi(c) { var lo = [], hi = []; for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i])); hi.push(Math.max(c.from[i], c.to[i])); } return { lo: lo, hi: hi }; }
  function colourOf(c) {
    if (typeof c.cpm_recolor === 'number' && c.cpm_recolor !== -1) return c.cpm_recolor & 0xFFFFFF;
    try { var p = G.ColorPanel && G.ColorPanel.get ? G.ColorPanel.get() : null; if (p) return M.rgbOf(p); } catch (e) { }
    return 0xc8a070;
  }
  /** a new block against a face: o = {depth}; returns the cube (inside the caller's undo edit) */
  function makeBlock(hit, depth) {
    var c = hit.cube, b = lohi(c), inf = c.inflate || 0, a = hit.axis, from = [], to = [];
    for (var i = 0; i < 3; i++) { from.push(b.lo[i] - inf); to.push(b.hi[i] + inf); }
    if (hit.dir > 0) { from[a] = b.hi[a] + inf; to[a] = from[a] + depth; } else { to[a] = b.lo[a] - inf; from[a] = to[a] - depth; }
    var n = M.newCube({ name: T('build.name', c.name), from: from.map(M.snap), to: to.map(M.snap), origin: c.origin.slice(), rotation: c.rotation.slice(), colour: M.isCpm() ? colourOf(c) : undefined }, c.parent && c.parent !== 'root' ? c.parent : 'root');
    return n;
  }
  /** click: a 1 px block against the face (one undo step) */
  function addBlock(hit, depth) {
    var made = null;
    M.edit(T('build.add'), { outliner: true, elements: [], groups: [] }, function () { made = makeBlock(hit, depth || 1); }, function () { return { outliner: true, elements: [made], groups: [] }; });
    M.refresh();
    M.selectNodes([made]);
    return made;
  }
  function updateMesh(c) {
    try { if (G.Canvas.updateView) G.Canvas.updateView({ elements: [c], element_aspects: { geometry: true, uv: true, transform: true } }); else G.Canvas.updateAll(); }
    catch (e) { try { G.Canvas.updateAll(); } catch (x) { } }
  }
  /** moves the face of a cube: amount px outward (negative = inward); never past the other side */
  function setFace(c, start, axis, dir, amount) {
    var maxSide = c.to[axis] >= c.from[axis] ? 'to' : 'from', minSide = maxSide === 'to' ? 'from' : 'to';
    if (dir > 0) c[maxSide][axis] = M.snap(Math.max(start.lo[axis], start.hi[axis] + amount));
    else c[minSide][axis] = M.snap(Math.min(start.hi[axis], start.lo[axis] - amount));
  }

  // =================================================================== dragging
  function stepOf(ev) { return ev.ctrlKey ? 0.25 : 1; }
  /** how far (px of the model) the mouse went along the face normal since the drag started */
  function amountOf(ev) {
    var d = st.down, s0 = screenOf(d.hit.preview, d.hit.point), s1 = screenOf(d.hit.preview, d.hit.point.clone().add(d.hit.normal));
    var u = [s1[0] - s0[0], s1[1] - s0[1]], uu = u[0] * u[0] + u[1] * u[1], dx = ev.clientX - d.x, dy = ev.clientY - d.y;
    // a face seen straight on: up / down with the mouse
    var raw = uu > 4 ? (dx * u[0] + dy * u[1]) / uu : -dy / 20, step = stepOf(ev);
    return Math.round(raw / step) * step;
  }
  function showLabel(ev, text) {
    if (!st.label) { st.label = document.createElement('div'); st.label.className = 'cmp_build_label'; document.body.appendChild(st.label); }
    st.label.textContent = text; st.label.style.left = Math.round(ev.clientX + 14) + 'px'; st.label.style.top = Math.round(ev.clientY + 14) + 'px'; st.label.style.display = '';
  }
  function hideLabel() { if (st.label) st.label.style.display = 'none'; }
  function startDrag(ev) {
    var d = st.down, c = d.hit.cube;
    if (d.shift) {
      G.Undo.initEdit({ outliner: true, elements: [], groups: [] });
      var n = makeBlock(d.hit, stepOf(ev));
      st.drag = { kind: 'new', cube: n, start: lohi(n), base: d.hit };
      // the new block grows from the face: its outer side moves
      st.drag.axis = d.hit.axis; st.drag.dir = d.hit.dir;
    } else {
      G.Undo.initEdit({ elements: [c] });
      st.drag = { kind: 'pull', cube: c, start: lohi(c), axis: d.hit.axis, dir: d.hit.dir };
    }
    try { if (d.hit.preview.controls) { st.drag.controls = d.hit.preview.controls.enabled; d.hit.preview.controls.enabled = false; } } catch (e) { }
  }
  function moveDrag(ev) {
    var g = st.drag, amount = amountOf(ev);
    if (g.kind === 'new') {
      // as deep as the mouse went (at least one step)
      var depth = Math.max(stepOf(ev), amount), b = g.start, c = g.cube;
      if (g.dir > 0) c.to[g.axis] = M.snap(b.lo[g.axis] + depth); else c.from[g.axis] = M.snap(b.hi[g.axis] - depth);
      updateMesh(c);
      showLabel(ev, T('build.label.new', M.num(depth)));
    } else {
      setFace(g.cube, g.start, g.axis, g.dir, amount);
      updateMesh(g.cube);
      var size = Math.abs(g.cube.to[g.axis] - g.cube.from[g.axis]);
      showLabel(ev, T('build.label.pull', (amount > 0 ? '+' : '') + M.num(amount), M.num(size)));
    }
    g.amount = amount;
  }
  function endDrag(commit) {
    var g = st.drag; st.drag = null; hideLabel();
    try { if (g.controls !== undefined && st.down && st.down.hit.preview.controls) st.down.hit.preview.controls.enabled = g.controls; } catch (e) { }
    if (!g) return;
    if (commit && (g.kind === 'new' || g.amount)) {
      if (g.kind === 'new') { G.Undo.finishEdit(T('build.add'), { outliner: true, elements: [g.cube], groups: [] }); M.refresh(); M.selectNodes([g.cube]); }
      else { G.Undo.finishEdit(T('build.pull'), { elements: [g.cube] }); M.refresh(); }
      return;
    }
    // nothing moved / Esc: the step is dropped and the cube is as it was
    if (g.kind === 'pull') { var c = g.cube; for (var i = 0; i < 3; i++) { var side = c.to[i] >= c.from[i] ? 1 : 0; c.from[i] = side ? g.start.lo[i] : g.start.hi[i]; c.to[i] = side ? g.start.hi[i] : g.start.lo[i]; } }
    try { G.Undo.cancelEdit(); } catch (e) { }
    if (g.kind === 'new') { try { if (M.allCubes().indexOf(g.cube) >= 0) g.cube.remove(); } catch (e) { } }
    M.refresh();
  }

  // =================================================================== mouse / keys while the tool is on
  function isTool() { return !!(G.Toolbox && G.Toolbox.selected && G.Toolbox.selected.id === TOOL); }
  function onDown(ev) {
    if (!isTool() || ev.button !== 0 || !previewOf(ev.target)) return;
    var hit = hitAt(ev); if (!hit) return;// empty space: the camera turns as usual
    ev.preventDefault(); ev.stopPropagation();
    st.down = { hit: hit, x: ev.clientX, y: ev.clientY, shift: ev.shiftKey };
  }
  function onMove(ev) {
    if (!st.down) return;
    if (!st.drag) {
      if (Math.abs(ev.clientX - st.down.x) + Math.abs(ev.clientY - st.down.y) < DRAG_PX) return;
      try { startDrag(ev); } catch (e) { L.warn('build: drag failed', e); st.down = null; return; }
    }
    ev.preventDefault();
    try { moveDrag(ev); } catch (e) { L.warn('build: drag failed', e); }
  }
  function onUp(ev) {
    if (!st.down) return;
    var d = st.down;
    try {
      if (st.drag) endDrag(true);
      else addBlock(d.hit, 1);
    } catch (e) { L.warn('build failed', e); }
    st.down = null;
    ev.preventDefault(); ev.stopPropagation();
  }
  function onKey(ev) { if (ev.key === 'Escape' && st.drag) { endDrag(false); st.down = null; ev.preventDefault(); } }
  function listen(target, type, fn, capture) { target.addEventListener(type, fn, capture); st.listeners.push([target, type, fn, capture]); }
  function on() {
    if (st.on) return; st.on = true;
    listen(document, 'pointerdown', onDown, true);
    listen(window, 'pointermove', onMove, true);
    listen(window, 'pointerup', onUp, true);
    listen(document, 'keydown', onKey, true);
    G.Blockbench.showQuickMessage(T('build.hint'), 3500);
  }
  function off() {
    if (st.drag) endDrag(false);
    st.down = null; st.on = false;
    st.listeners.splice(0).forEach(function (l) { l[0].removeEventListener(l[1], l[2], l[3]); });
    hideLabel();
  }

  var STYLE = 'cmp_build_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var s = document.createElement('style'); s.id = STYLE;
      s.textContent = '.cmp_build_label{position:fixed;z-index:30;background:rgba(20,20,24,.88);color:#80deea;padding:2px 7px;border-radius:4px;font-size:12px;pointer-events:none;white-space:nowrap;border:1px solid #80deea}';
      document.head.appendChild(s);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var tool = L.mk('Tool', TOOL, {
      name: T('build'), description: T('build.desc'), icon: 'add_box', category: 'tools', modes: ['edit'], cursor: 'copy',
      transformerMode: 'hidden', selectFace: false, selectElements: false,
      onSelect: function () { on(); }, onUnselect: function () { off(); }
    });
    L.place('tools', tool, 'cpmplus_model_measure');
    L.onUninstall(function () { off(); if (st.label) { st.label.remove(); st.label = null; } });
    M.register({ id: 'cpmplus_model_build_pick', sec: 'adjust', icon: 'add_box', key: 'build', mode: 'edit', spots: [], run: function () { L.selectTool(TOOL); } });
  };
  api.uninstall = function () { off(); };
  // QA: the same steps the mouse takes
  api.hitAt = hitAt; api.addBlock = addBlock; api.screenOf = screenOf; api.state = function () { return { on: st.on, down: !!st.down, drag: st.drag ? st.drag.kind : null }; };
  return api;
})
