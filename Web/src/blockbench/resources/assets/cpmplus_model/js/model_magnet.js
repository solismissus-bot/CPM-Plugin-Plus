/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Encaixe magnetico (magnetic snapping): a toggle (Blockbench's row of tools next to
 * Medir / Construir, the panel's Ajustar section, CPM+ > Modelagem; off by default, remembered). While cubes / groups
 * are MOVED with Blockbench's move gizmo (and, an option on by default, while cubes are RESIZED with the resize gizmo),
 * the moving faces snap flush to the faces, edges and centres of the cubes nearby once they come closer than the
 * magnet's distance (0.5 px; the toggle's options: the small arrow next to it in the row of tools, or CPM+ > Modelagem).
 * - it works inside Blockbench's own gizmo: the Blockbench 5 TransformerModule 'edit' computes the drag distance along
 *   the gizmo axis in calculateOffset; the magnet wraps it and returns the distance that puts a face exactly on a
 *   neighbour. Blockbench still does the move, so the drag stays ONE Blockbench undo step;
 * - model space, rotated groups included: every cube is measured along the gizmo's own three axes (global, group or
 *   local space), from its real corners (inflate, its turn and its groups' turns);
 * - the moving parts themselves and hidden cubes are ignored; only cubes beside the moving part count (gap <= 4 px);
 * - with Blockbench's grid: within the magnet's distance of a face the magnet wins (an exact position, also off the
 *   grid), elsewhere the grid works as always; with Shift / Ctrl (finer steps) the magnet gets proportionally weaker;
 * - a guide while snapped: a pink plane where the faces meet and the outline of the cube it snapped to, plus that
 *   cube's name next to the distance at the mouse;
 * - Edit mode only (Animate, Pose and the pivot tool are never touched); the gizmo is hooked only while the toggle is
 *   on, and unhooked when it is turned off and on uninstall.
 * Loaded after the modelling engine (L.core.modules.model); the toggle id is in art_core's TOOL_ROW.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var ID = 'cpmplus_model_magnet', OPT = 'model.magnet', OPT_DIST = 'model.magnet.dist', OPT_RESIZE = 'model.magnet.resize';
  var NEAR = 4, COL = 0xff4fd8, MARGIN = 1.5, KIND = { touch: 0, align: 1, centre: 2 };
  var item = null, cfg = null, hooks = [], drag = null, guide = null, stats = { plans: 0, planMs: 0, snaps: 0 };

  function on() { return !!(item && item.value); }
  function dist() { var v = Number(L.opt(OPT_DIST, 0.5)); return v > 0 && v <= 16 ? v : 0.5; }
  function withResize() { return L.opt(OPT_RESIZE, true) !== false; }
  function toolId() { return G.Toolbox && G.Toolbox.selected ? G.Toolbox.selected.id : ''; }
  function editMode() { return !!(G.Modes && G.Modes.edit && (!G.Modes.id || G.Modes.id === 'edit')); }
  /** the gizmo drags the magnet works on: one axis of the move gizmo, one face of the resize gizmo, Edit mode */
  function active(ctx) {
    if (!on() || !editMode() || !ctx || !ctx.point || typeof ctx.axis !== 'string' || ctx.axis === 'e' || ctx.second_axis || typeof ctx.axis_number !== 'number') return false;
    var t = toolId();
    return t === 'move_tool' || (t === 'resize_tool' && withResize());
  }

  // =================================================================== geometry (model space)
  function V3(x, y, z) { return new G.THREE.Vector3(x, y, z); }
  function root3d() { return (G.Project && G.Project.model_3d) || null; }
  /** the gizmo's three axes in model space (unit vectors): Blockbench measures the drag along them */
  function gizmoAxes() {
    var TR = G.Transformer; if (!TR || typeof TR.getWorldQuaternion !== 'function') return null;
    var q = TR.getWorldQuaternion(new G.THREE.Quaternion()), r = root3d();
    if (r && typeof r.getWorldQuaternion === 'function') q.premultiply(r.getWorldQuaternion(new G.THREE.Quaternion()).invert());
    return [V3(1, 0, 0), V3(0, 1, 0), V3(0, 0, 1)].map(function (v) { return v.applyQuaternion(q).normalize(); });
  }
  function range(pts, a) { var lo = Infinity, hi = -Infinity; for (var i = 0; i < pts.length; i++) { var v = pts[i].dot(a); if (v < lo) lo = v; if (v > hi) hi = v; } return [lo, hi]; }
  /** a box measured along three axes: [[lo, hi] along each] */
  function boxAlong(pts, f) { return [range(pts, f[0]), range(pts, f[1]), range(pts, f[2])]; }
  function gap(a, b) { return Math.max(0, a[0] - b[1], b[0] - a[1]); }
  function shown(c) {
    if (c.visibility === false) return false;
    for (var p = c.parent; p && p !== 'root'; p = p.parent) if (p.visibility === false) return false;
    return true;
  }
  /** what the gizmo moves: the selected cubes and every cube inside the selected groups (resize: the cubes) */
  function movingCubes(tool) {
    var out = [];
    M.selCubes().forEach(function (c) { if (out.indexOf(c) < 0) out.push(c); });
    if (tool !== 'resize_tool') M.cubesOf(M.selGroups()).forEach(function (c) { if (out.indexOf(c) < 0) out.push(c); });
    return out;
  }
  /**
   * the snap targets of one drag, measured once when it starts: for every visible cube beside the moving part, the drag
   * distances that put a moving face on its faces / edges (touching or lined up) or a face / the middle on its middle
   */
  function makePlan(ctx, tool) {
    var t0 = performance.now(), ax = gizmoAxes(); if (!ax) return null;
    var k = ctx.axis_number, f = [ax[k], ax[(k + 1) % 3], ax[(k + 2) % 3]];
    var moving = movingCubes(tool); if (!moving.length) return null;
    var pts = []; moving.forEach(function (c) { M.cubeCorners(c).forEach(function (p) { pts.push(p); }); });
    var S = boxAlong(pts, f), skip = new Set(moving), cands = [], mid0 = (S[0][0] + S[0][1]) / 2, dir = ctx.direction === -1 ? -1 : 1;
    // resize: only the dragged face moves (the +axis face for the + handle, the -axis face for the - handle)
    var face = dir < 0 ? S[0][0] : S[0][1];
    M.allCubes().forEach(function (c) {
      if (skip.has(c) || !shown(c)) return;
      var cp = M.cubeCorners(c), B = boxAlong(cp, f), g1 = gap(S[1], B[1]), g2 = gap(S[2], B[2]);
      if (g1 * g1 + g2 * g2 > NEAR * NEAR) return;
      var lo = B[0][0], hi = B[0][1], mid = (lo + hi) / 2;
      var add = function (v, at, kind) { if (isFinite(v)) cands.push({ v: v, at: at, kind: kind, cube: c, box: B, corners: cp }); };
      if (tool === 'resize_tool') {
        add(lo - face, lo, dir > 0 ? 'touch' : 'align'); add(hi - face, hi, dir > 0 ? 'align' : 'touch'); add(mid - face, mid, 'centre');
      } else {
        add(lo - S[0][1], lo, 'touch'); add(hi - S[0][0], hi, 'touch');
        add(lo - S[0][0], lo, 'align'); add(hi - S[0][1], hi, 'align');
        add(mid - mid0, mid, 'centre'); add(mid - S[0][0], mid, 'centre'); add(mid - S[0][1], mid, 'centre');
      }
    });
    stats.plans++; stats.planMs = performance.now() - t0;
    return { tool: tool, axis: k, frame: f, S: S, cands: cands, base: 0, snapped: null };
  }
  /** the target nearest to the drag distance, within thr (a touching face first, then lined up, then middles) */
  function nearest(cands, disp, thr) {
    var best = null, bd = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var c = cands[i], dd = Math.abs(c.v - disp);
      if (dd > thr + 1e-9) continue;
      if (!best || dd < bd - 1e-9 || (Math.abs(dd - bd) <= 1e-9 && KIND[c.kind] < KIND[best.kind])) { best = c; bd = dd; }
    }
    return best;
  }
  /** Blockbench's grid step for this drag (Shift / Ctrl make it finer) */
  function gridStep(ev) {
    try {
      var P = (G.Pressing && G.Pressing.overrides) || {};
      var sh = !!((ev && ev.shiftKey) || P.shift), ct = !!((ev && (ev.ctrlOrCmd || ev.ctrlKey || ev.metaKey)) || P.ctrl);
      var s = typeof G.canvasGridSize === 'function' ? G.canvasGridSize(sh, ct) : 1;
      return s > 0 ? s : 1;
    } catch (e) { return 1; }
  }
  /**
   * calculateOffset's value -> the magnet's. Move: Blockbench moves by (value - the first value of the drag), so the
   * targets are drag distances from where the drag started plus that first value. Resize: the face moves by value.
   */
  function snapValue(mod, v, ctx) {
    var tool = toolId();
    if (mod.previous_value == null || !drag || drag.tool !== tool || drag.axis !== ctx.axis_number) {
      drag = makePlan(ctx, tool) || { none: true, tool: tool, axis: ctx.axis_number };
      drag.base = mod.previous_value != null ? mod.previous_value : v;
      hideGuide();
      return v;
    }
    if (drag.none) return v;
    var thr = dist() * Math.min(1, gridStep(ctx.event)), raw = ctx.point[ctx.axis];
    if (typeof raw !== 'number' || !isFinite(raw)) return v;
    var disp = tool === 'resize_tool' ? raw : raw - drag.base, c = nearest(drag.cands, disp, thr);
    if (!c) { if (drag.snapped) { drag.snapped = null; hideGuide(); } return v; }
    if (drag.snapped !== c) { drag.snapped = c; stats.snaps++; showGuide(drag, c); }
    return tool === 'resize_tool' ? c.v : c.v + drag.base;
  }
  /** after Blockbench's own move step: the cube it snapped to next to the distance at the mouse */
  function afterMove(ctx) {
    if (!drag || !drag.snapped || !active(ctx) || typeof ctx.value !== 'number') return;
    try {
      var val = toolId() === 'resize_tool' ? ctx.value * (ctx.direction || 1) : ctx.value;
      var txt = typeof G.trimFloatNumber === 'function' ? G.trimFloatNumber(val) : M.num(val, 3);
      G.Blockbench.setCursorTooltip(txt + '  \u00B7  ' + T('magnet.tip', drag.snapped.cube.name));
    } catch (e) { }
  }
  function endDrag() { drag = null; hideGuide(); }

  // =================================================================== the guide (drawn over the model while snapped)
  function render() { try { ((G.Preview && G.Preview.all) || []).forEach(function (p) { if (p && p.render) p.render(); }); } catch (e) { } }
  function ensureGuide() {
    if (guide) return guide;
    var THREE = G.THREE, gr = new THREE.Group(); gr.name = 'cpmplus_model_magnet_guide'; gr.visible = false;
    var geo = function (n, index) { var g = new THREE.BufferGeometry(); g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(n * 3), 3)); if (index) g.setIndex(index); return g; };
    var pg = geo(4, [0, 1, 2, 0, 2, 3]), eg = geo(4), bg = geo(24);
    var fill = new THREE.MeshBasicMaterial({ color: COL, transparent: true, opacity: 0.3, side: THREE.DoubleSide, depthTest: false, depthWrite: false });
    var line = new THREE.LineBasicMaterial({ color: COL, transparent: true, opacity: 1, depthTest: false, depthWrite: false });
    var plane = new THREE.Mesh(pg, fill), edge = new THREE.LineLoop(eg, line), box = new THREE.LineSegments(bg, line);
    [plane, edge, box].forEach(function (o, i) { o.renderOrder = 2000 + i; o.frustumCulled = false; gr.add(o); });
    guide = { group: gr, plane: plane, edge: edge, box: box, geos: [pg, eg, bg], mats: [fill, line] };
    return guide;
  }
  function write(geo, pts) {
    var a = geo.attributes.position;
    for (var i = 0; i < pts.length; i++) a.setXYZ(i, pts[i].x, pts[i].y, pts[i].z);
    a.needsUpdate = true; geo.computeBoundingSphere();
  }
  /** the plane where the faces meet (as wide as both parts) and the outline of the cube snapped to */
  function showGuide(d, c) {
    var r = root3d(); if (!r || !G.THREE) return;
    var g = ensureGuide(); if (g.group.parent !== r) r.add(g.group);
    var f = d.frame, a0 = Math.min(d.S[1][0], c.box[1][0]) - MARGIN, a1 = Math.max(d.S[1][1], c.box[1][1]) + MARGIN;
    var b0 = Math.min(d.S[2][0], c.box[2][0]) - MARGIN, b1 = Math.max(d.S[2][1], c.box[2][1]) + MARGIN;
    var P = function (a, b) { return f[0].clone().multiplyScalar(c.at).add(f[1].clone().multiplyScalar(a)).add(f[2].clone().multiplyScalar(b)); };
    var quad = [P(a0, b0), P(a1, b0), P(a1, b1), P(a0, b1)];
    write(g.plane.geometry, quad); write(g.edge.geometry, quad);
    var e = [];
    for (var k = 0; k < 8; k++) [1, 2, 4].forEach(function (bit) { if (!(k & bit)) e.push(c.corners[k], c.corners[k | bit]); });
    write(g.box.geometry, e);
    g.group.visible = true;
    render();
  }
  /** hidden = taken out of the scene (nothing of the magnet stays in the model's scene between drags) */
  function hideGuide() {
    if (!guide || (!guide.group.visible && !guide.group.parent)) return;
    guide.group.visible = false;
    try { if (guide.group.parent) guide.group.parent.remove(guide.group); } catch (e) { }
    render();
  }

  // =================================================================== hooking Blockbench's gizmo (edit module)
  function editModule() { var TM = G.TransformerModule; return TM && TM.modules ? TM.modules.edit || null : null; }
  /** wraps mod[prop]; a wrapper someone else wrapped again stays in the chain (passing through) until it is on top */
  function hookProp(mod, prop, make) {
    var rec = { mod: mod, prop: prop, orig: mod[prop], off: false };
    var w = make(rec);
    w.__cpmplus_magnet = true; w.__cpmplus_orig = rec.orig;
    rec.wrapper = w; mod[prop] = w; hooks.push(rec);
  }
  function hook() {
    var mod = editModule();
    if (!mod || typeof mod.calculateOffset !== 'function') return false;
    var have = {};
    hooks.forEach(function (h) { h.off = false; have[h.prop] = true; });
    if (!have.calculateOffset) hookProp(mod, 'calculateOffset', function (rec) {
      return function (ctx) {
        var v = rec.orig.apply(this, arguments);
        if (rec.off || typeof v !== 'number' || !isFinite(v) || !active(ctx)) return v;
        try { return snapValue(this, v, ctx); } catch (e) { L.warn('magnet: snapping failed', e); return v; }
      };
    });
    if (!have.onMove) hookProp(mod, 'onMove', function (rec) {
      return function (ctx) {
        var r = typeof rec.orig === 'function' ? rec.orig.apply(this, arguments) : undefined;
        if (!rec.off) afterMove(ctx);
        return r;
      };
    });
    ['onEnd', 'onCancel'].forEach(function (p) {
      if (!have[p]) hookProp(mod, p, function (rec) {
        return function () {
          try { return typeof rec.orig === 'function' ? rec.orig.apply(this, arguments) : undefined; }
          finally { if (!rec.off) endDrag(); }
        };
      });
    });
    return true;
  }
  function unhook() {
    hooks = hooks.filter(function (h) {
      h.off = true;
      if (h.mod[h.prop] === h.wrapper) { h.mod[h.prop] = h.orig; return false; }
      return true;
    });
    endDrag();
  }

  // =================================================================== the toggle and its options
  function refreshPanel() { try { var P = L.core.modules.model_panel; if (P && P.refresh) P.refresh(); } catch (e) { } }
  function syncCfg() { if (!cfg) return; cfg.options.enabled = on(); cfg.options.dist = dist(); cfg.options.resize = withResize(); try { cfg.save(); } catch (e) { } }
  /** the options window, next to the toggle in the row of tools when it is there */
  function openOptions() {
    if (!cfg) return null;
    syncCfg();
    var node = null;
    try { (item.nodes || []).forEach(function (n) { if (!node && n.isConnected && n.offsetParent !== null && n.closest && n.closest('.toolbar')) node = n; }); } catch (e) { }
    cfg.show(node || undefined);
    if (!node && cfg.object) { try { cfg.object.style.left = Math.max(0, (window.innerWidth - cfg.object.clientWidth) / 2) + 'px'; cfg.object.style.top = '120px'; } catch (e) { } }
    return cfg;
  }
  api.install = function () {
    var opts = {
      name: T('magnet'), description: T('magnet.desc'), icon: 'fa-magnet', category: 'transform', condition: { modes: ['edit'] }, default: !!L.opt(OPT, false),
      onChange: function (v) {
        L.setOpt(OPT, !!v);
        if (v) hook(); else unhook();
        syncCfg(); refreshPanel();
      }
    };
    if (typeof G.ToolConfig === 'function') {
      try {
        cfg = new G.ToolConfig(ID + '_options', {
          title: T('magnet'), width: 440,
          form: {
            enabled: { type: 'checkbox', label: T('magnet.f.on'), value: !!L.opt(OPT, false) },
            dist: { type: 'number', label: T('magnet.f.dist'), description: T('magnet.f.dist.desc'), value: dist(), min: 0.05, max: 4, step: 0.05 },
            resize: { type: 'checkbox', label: T('magnet.f.resize'), description: T('magnet.f.resize.desc'), value: withResize() }
          },
          onFormChange: function (r) {
            if (r.dist !== undefined && isFinite(+r.dist) && +r.dist > 0) L.setOpt(OPT_DIST, Math.min(4, Math.max(0.05, +r.dist)));
            if (r.resize !== undefined) L.setOpt(OPT_RESIZE, !!r.resize);
            if (r.enabled !== undefined && !!r.enabled !== on() && item) item.set(!!r.enabled);
          }
        });
        opts.tool_config = cfg;
      } catch (e) { cfg = null; L.warn('magnet: no options window', e); }
    }
    item = L.mk('Toggle', ID, opts);
    syncCfg();
    if (item.value) hook();
    // the row of tools, next to Medir / Construir (art_core TOOL_ROW keeps it there)
    L.place('tools', item, 'cpmplus_model_build');
    // CPM+ > Modelagem: the toggle, and its options
    try {
      var tools = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.cpm_plus_tools, sub = null;
      (tools && tools.structure || []).forEach(function (s) { if (s && s.id === 'cpmplus_sub_model') sub = s; });
      var kids = sub && sub.children;
      if (Array.isArray(kids) && kids.indexOf(item) < 0) { kids.push(item); L.onUninstall(function () { var k = kids.indexOf(item); if (k >= 0) kids.splice(k, 1); }); }
    } catch (e) { L.warn('could not place the magnet toggle', e); }
    if (cfg) M.register({ id: ID + '_opts', icon: 'tune', key: 'magnet.opts', mode: 'edit', spots: [], run: function () { openOptions(); } });
    var off = function () { endDrag(); };
    var evs = ['select_mode', 'select_project', 'unselect_project', 'undo', 'redo'];
    try { evs.forEach(function (e) { G.Blockbench.on(e, off); }); } catch (e) { }
    L.onUninstall(function () {
      try { evs.forEach(function (e) { G.Blockbench.removeListener(e, off); }); } catch (e) { }
      unhook();
    });
  };
  api.uninstall = function () {
    unhook();
    if (cfg) { try { if (G.Dialog && G.Dialog.open === cfg) cfg.hide(); } catch (e) { } try { cfg.delete(); } catch (e) { } cfg = null; }
    if (guide) {
      try { if (guide.group.parent) guide.group.parent.remove(guide.group); } catch (e) { }
      guide.geos.concat(guide.mats).forEach(function (x) { try { x.dispose(); } catch (e) { } });
      guide = null;
    }
    render();
  };
  // the panel shows the toggle in its Ajustar section (with its on / off state)
  api.panelItems = [{ sec: 'adjust', id: ID }];
  // QA
  api.hooked = function () { var m = editModule(); return !!(m && m.calculateOffset && m.calculateOffset.__cpmplus_magnet); };
  api.guide = function () { return guide ? guide.group : null; };
  api.drag = function () { return drag; };
  api.stats = function () { return Object.assign({}, stats); };
  api.openOptions = openOptions;
  api.options = function () { return cfg; };
  return api;
})
