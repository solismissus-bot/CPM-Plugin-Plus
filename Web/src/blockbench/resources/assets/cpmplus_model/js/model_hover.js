/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Destacar cubo sob o mouse (Blockbench issue #1289): a toggle (View menu, CPM+ >
 * Modelagem, the panel's Conferir section; off by default, remembered between sessions) that draws the outline of the
 * cube under the mouse in Edit mode: a bright box on its edges - lines 2.5 screen pixels thick at any zoom where you see
 * them, thin and faint behind other parts - like the block outline in Minecraft. (Blockbench's own 'Destacar cubos'
 * setting only tints the cube a little.)
 * Light on big models: at most one raycast per frame (requestAnimationFrame) and only when the mouse moved, against the
 * meshes Blockbench already has; one reused line object moved with a matrix (no new geometry per hover).
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var OPT = 'model.hoverbox', PAD = 0.04, COLOUR = 0xffc107, LINE_PX = 2.5;
  var item = null, obj = null, faintObj = null, bars = [], geos = [], mats = [], hovered = null, pending = null, raf = 0, lastXY = null, rc = null;
  var stats = { runs: 0, ms: 0, max: 0 };

  function on() { return !!(item && item.value); }
  function previews() { return (G.Preview && G.Preview.all) || []; }
  function previewOf(target) { var all = previews(); for (var i = 0; i < all.length; i++) if (all[i] && all[i].canvas === target) return all[i]; return null; }
  function render(pv) { try { (pv ? [pv] : previews()).forEach(function (p) { if (p && p.render) p.render(); }); } catch (e) { } }
  /**
   * the outline object: 12 bars on the edges you see (solid, drawn a little in front so the whole width shows on the
   * cube's own edges) + thin faint lines for the edges behind something. Built once, moved with matrices.
   */
  function ensureObj() {
    if (obj) return obj;
    var THREE = G.THREE, box = new THREE.BoxGeometry(1, 1, 1), edges = new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1));
    geos = [box, edges];
    var solid = new THREE.MeshBasicMaterial({ color: COLOUR, depthTest: true, polygonOffset: true, polygonOffsetFactor: -4, polygonOffsetUnits: -4 });
    var faint = new THREE.LineBasicMaterial({ color: COLOUR, transparent: true, opacity: 0.35, depthTest: false, depthWrite: false });
    mats = [solid, faint];
    obj = new THREE.Group(); obj.name = 'cpmplus_model_hover'; obj.matrixAutoUpdate = false; obj.visible = false;
    bars = [];
    for (var i = 0; i < 12; i++) { var b = new THREE.Mesh(box, solid); b.matrixAutoUpdate = false; b.renderOrder = 1000; obj.add(b); bars.push(b); }
    faintObj = new THREE.LineSegments(edges, faint); faintObj.matrixAutoUpdate = false; faintObj.renderOrder = 999; obj.add(faintObj);
    return obj;
  }
  /** world units per screen pixel at a point (perspective or orthographic view) */
  function unitsPerPixel(pv, p) {
    var cam = pv && pv.camera, h = (pv && (pv.height || (pv.canvas && pv.canvas.clientHeight))) || 600;
    if (!cam) return 0.05;
    if (cam.isOrthographicCamera) return (cam.top - cam.bottom) / (cam.zoom || 1) / h;
    var d = cam.position.distanceTo(p);
    return 2 * d * Math.tan((cam.fov || 45) * Math.PI / 360) / h;
  }
  /** lays the bars on the box of the hovered part, LINE_PX screen pixels thick at its distance */
  function layout(el, pv) {
    var THREE = G.THREE, mesh = el.mesh, g = mesh.geometry, size = new THREE.Vector3(), c = new THREE.Vector3();
    g.computeBoundingBox();
    g.boundingBox.getSize(size); g.boundingBox.getCenter(c);
    mesh.updateMatrixWorld(true);
    obj.matrix.copy(mesh.matrixWorld).multiply(new THREE.Matrix4().makeTranslation(c.x, c.y, c.z));
    obj.matrixWorldNeedsUpdate = true;
    var hx = size.x / 2 + PAD, hy = size.y / 2 + PAD, hz = size.z / 2 + PAD;
    var t = LINE_PX * unitsPerPixel(pv || (G.Preview && G.Preview.selected), c.clone().applyMatrix4(mesh.matrixWorld)), k = 0;
    var put = function (x, y, z, sx, sy, sz) { bars[k++].matrix.compose(new THREE.Vector3(x, y, z), new THREE.Quaternion(), new THREE.Vector3(sx, sy, sz)); };
    [-1, 1].forEach(function (a) {
      [-1, 1].forEach(function (b) {
        put(0, a * hy, b * hz, 2 * hx + t, t, t);
        put(a * hx, 0, b * hz, t, 2 * hy + t, t);
        put(a * hx, b * hy, 0, t, t, 2 * hz + t);
      });
    });
    bars.forEach(function (b) { b.matrixWorldNeedsUpdate = true; });
    faintObj.matrix.makeScale(2 * hx, 2 * hy, 2 * hz); faintObj.matrixWorldNeedsUpdate = true;
  }
  /** puts the outline on element el (its visible box, a hair outside), or hides it (el = null) */
  function show(el, pv) {
    if (!el && !obj) { hovered = null; return; }
    var o = ensureObj(), sc = G.Canvas && G.Canvas.scene;
    if (sc && o.parent !== sc) sc.add(o);
    if (!el || !el.mesh || !el.mesh.geometry) {
      hovered = null;
      if (o.visible) { o.visible = false; render(pv); }
      return;
    }
    layout(el, pv);
    o.visible = true; hovered = el;
    render(pv);
  }
  /** the element under the mouse in a preview (visible, unlocked, with a mesh) or null */
  function pick(pv, x, y) {
    var THREE = G.THREE, r = pv.canvas.getBoundingClientRect(); if (!r.width || !r.height) return null;
    if (!rc) rc = new THREE.Raycaster();
    rc.setFromCamera(new THREE.Vector2((x - r.left) / r.width * 2 - 1, -((y - r.top) / r.height) * 2 + 1), pv.camera);
    var els = (G.Outliner && G.Outliner.elements) || [], meshes = [], owner = new Map();
    for (var i = 0; i < els.length; i++) {
      var e = els[i], m = e && e.mesh;
      if (!m || !m.geometry || e.visibility === false || e.locked === true || m.visible === false) continue;
      meshes.push(m); owner.set(m, e);
    }
    var hits = rc.intersectObjects(meshes, false);
    return hits.length ? owner.get(hits[0].object) || null : null;
  }
  function tick() {
    raf = 0;
    var p = pending; pending = null;
    if (!p || !on()) return;
    if (lastXY && lastXY.pv === p.pv && lastXY.x === p.x && lastXY.y === p.y) return;
    lastXY = p;
    var t0 = performance.now(), el = null;
    try { el = pick(p.pv, p.x, p.y); } catch (e) { el = null; }
    var dt = performance.now() - t0;
    stats.runs++; stats.ms += dt; stats.max = Math.max(stats.max, dt);
    if (el !== hovered) show(el, p.pv);
    // the same part: only the line width follows the camera (Blockbench draws the next frame anyway)
    else if (el && obj) layout(el, p.pv);
  }
  function onMove(ev) {
    if (!on()) return;
    var pv = previewOf(ev.target);
    if (!pv) return;
    if (ev.buttons || !M.inEdit() || (G.Transformer && G.Transformer.dragging)) { lastXY = null; show(null, pv); return; }
    pending = { pv: pv, x: ev.clientX, y: ev.clientY };
    if (!raf) raf = requestAnimationFrame(tick);
  }
  /** zooming with the wheel: what is under the mouse and the line width change */
  function onWheel(ev) {
    if (!on() || !hovered) return;
    var pv = previewOf(ev.target); if (!pv) return;
    lastXY = null; pending = { pv: pv, x: ev.clientX, y: ev.clientY };
    if (!raf) raf = requestAnimationFrame(tick);
  }
  function onOut(ev) { if (hovered && previewOf(ev.target)) { lastXY = null; show(null); } }
  /** after an edit (moved / deleted / hidden part, undo): the box follows the part, or goes away */
  function follow() {
    if (!hovered) return;
    var alive = (G.Outliner && G.Outliner.elements || []).indexOf(hovered) >= 0;
    if (!on() || !M.inEdit() || !alive || hovered.visibility === false) { lastXY = null; show(null); return; }
    var el = hovered; setTimeout(function () { if (hovered === el) show(el); }, 0);
  }
  function hideNow() { lastXY = null; show(null); }

  // =================================================================== registration
  api.install = function () {
    item = L.mk('Toggle', 'cpmplus_model_hover', {
      name: T('hoverbox'), description: T('hoverbox.desc'), icon: 'highlight_alt', category: 'view', default: !!L.opt(OPT, false),
      onChange: function (v) {
        L.setOpt(OPT, !!v);
        if (!v) hideNow();
        try { var P = L.core.modules.model_panel; if (P && P.refresh) P.refresh(); } catch (e) { }
      }
    });
    document.addEventListener('pointermove', onMove, { capture: true, passive: true });
    document.addEventListener('pointerout', onOut, { capture: true, passive: true });
    document.addEventListener('wheel', onWheel, { capture: true, passive: true });
    var evs = ['finished_edit', 'undo', 'redo', 'update_selection'], offs = ['select_mode', 'select_project', 'unselect_project'];
    try { evs.forEach(function (e) { G.Blockbench.on(e, follow); }); offs.forEach(function (e) { G.Blockbench.on(e, hideNow); }); } catch (e) { }
    // the View menu (where Blockbench keeps its own view toggles) and CPM+ > Modelagem
    try {
      var view = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.view, vs = view && view.structure;
      if (Array.isArray(vs) && vs.indexOf(item) < 0) { vs.push(item); L.onUninstall(function () { var k = vs.indexOf(item); if (k >= 0) vs.splice(k, 1); }); }
      var tools = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.cpm_plus_tools, sub = null;
      (tools && tools.structure || []).forEach(function (s) { if (s && s.id === 'cpmplus_sub_model') sub = s; });
      var kids = sub && sub.children;
      if (Array.isArray(kids) && kids.indexOf(item) < 0) { kids.push(item); L.onUninstall(function () { var k = kids.indexOf(item); if (k >= 0) kids.splice(k, 1); }); }
    } catch (e) { L.warn('could not place the hover outline toggle', e); }
    L.onUninstall(function () {
      document.removeEventListener('pointermove', onMove, { capture: true });
      document.removeEventListener('pointerout', onOut, { capture: true });
      document.removeEventListener('wheel', onWheel, { capture: true });
      try { evs.forEach(function (e) { G.Blockbench.removeListener(e, follow); }); offs.forEach(function (e) { G.Blockbench.removeListener(e, hideNow); }); } catch (e) { }
    });
  };
  api.uninstall = function () {
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
    pending = null; hovered = null; lastXY = null;
    if (obj) { try { if (obj.parent) obj.parent.remove(obj); } catch (e) { } }
    geos.concat(mats).forEach(function (x) { try { x.dispose(); } catch (e) { } });
    obj = null; faintObj = null; bars = []; geos = []; mats = [];
    render();
  };
  // the panel shows the toggle in its Conferir section (with its on / off state)
  api.panelItems = [{ sec: 'check', id: 'cpmplus_model_hover' }];
  // QA
  api.hovered = function () { return hovered; };
  api.object = function () { return obj; };
  api.stats = function () { return { runs: stats.runs, avg: stats.runs ? stats.ms / stats.runs : 0, max: stats.max }; };
  api.resetStats = function () { stats = { runs: 0, ms: 0, max: 0 }; };
  api.pick = pick;
  return api;
})
