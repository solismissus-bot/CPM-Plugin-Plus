/*
 * CPM Plugin+ beta33 "CPM+ Modelar": the modelling engine shared by the modelling modules.
 * - a registry of modelling actions: each one is a real Blockbench Action (action search, keybinds) and is placed where
 *   people look for it: the CPM+ Modelar panel (Edit mode), CPM+ > Modelagem, and its native spot inside Blockbench
 *   (Outliner toolbar next to 'Add cube / group', the Transform panel, the Transform / Edit menus, the right-click menu
 *   of cubes and groups, the row of tools)
 * - undo helpers (one Ctrl+Z per action), geometry in model space (boxes of rotated parts, moving a part inside rotated
 *   groups), CPM colour cubes, copies that forget their CPM ids, a live 3D preview layer, greedy box merging of voxels.
 * Loaded by the art core (api.loadModule); L = the core helpers.
 */
(function (L) {
  'use strict';
  var G = L.G, t = L.t;
  var api = {};
  var MENU_SEP = 'cpmplus_model';

  // =================================================================== text
  /** text of the modelling lang key k (cpmplus.model.k), %1.. replaced by the arguments */
  function T(k) {
    var s = t('cpmplus.model.' + k);
    for (var i = 1; i < arguments.length; i++) s = s.split('%' + i).join(String(arguments[i]));
    return s;
  }
  function num(v, d) { var f = Math.pow(10, d === undefined ? 2 : d); return String(Math.round(v * f) / f); }

  // =================================================================== project / selection
  function hasProject() { return !!G.Project; }
  function isCpm() { return !!(G.Format && G.Format.id === 'cpm'); }
  function inEdit() { return !!(G.Modes && G.Modes.edit); }
  function selCubes() { return (G.Cube && G.Cube.selected ? G.Cube.selected : []).slice(); }
  function selGroups() { var s = G.Group && G.Group.selected; return Array.isArray(s) ? s.slice() : (s ? [s] : []); }
  function isGroup(n) { return !!(n && G.Group && n instanceof G.Group); }
  function isCube(n) { return !!(n && G.Cube && n instanceof G.Cube); }
  /**
   * the player's own base cube of a body part (head, body, arms, legs): marked 'vanilla' in its CPM data (imported
   * projects) or, in a new project, a cube right inside a body part with that body part's name (the rule CPM uses)
   */
  function isBase(c) {
    if (!isCube(c) || !c.parent || c.parent === 'root' || c.parent.parent !== 'root') return false;
    try { var d = c.cpm_data ? JSON.parse(c.cpm_data) : null; if (d && d.vanilla) return true; } catch (e) { }
    return c.name === c.parent.name;
  }
  /** true when node is inside (or is) one of the groups */
  function inside(node, groups) {
    for (var p = node; p && p !== 'root'; p = p.parent) if (groups.indexOf(p) >= 0) return true;
    return false;
  }
  /** the selected top-level parts: the selected groups + the selected cubes that are not inside one of them */
  function selNodes() {
    var gs = selGroups().filter(function (g) { return !inside(g.parent, selGroups()); });
    var cs = selCubes().filter(function (c) { return !inside(c.parent, gs); });
    return gs.concat(cs);
  }
  /** every cube inside the nodes (groups walked) */
  function cubesOf(nodes) {
    var out = [];
    (nodes || []).forEach(function walk(n) {
      if (isCube(n)) { if (out.indexOf(n) < 0) out.push(n); }
      else if (isGroup(n)) (n.children || []).forEach(walk);
    });
    return out;
  }
  function groupsOf(nodes) {
    var out = [];
    (nodes || []).forEach(function walk(n) { if (isGroup(n)) { if (out.indexOf(n) < 0) out.push(n); (n.children || []).forEach(walk); } });
    return out;
  }
  function allCubes() { return (G.Cube && G.Cube.all ? G.Cube.all : []).slice(); }
  /** the group new parts go into: the first selected group, the parent of the first selected cube, or 'root' */
  function targetParent() {
    var g = selGroups()[0]; if (g) return g;
    var c = selCubes()[0]; if (c && c.parent && c.parent !== 'root') return c.parent;
    return 'root';
  }

  // =================================================================== undo (one entry per action)
  /**
   * runs fn inside one undo entry. before / after: Blockbench undo aspects ({elements, outliner, textures, ...}); after
   * may be a function of fn's result (new elements). On an error the edit is cancelled and the error rethrown.
   */
  function edit(name, before, fn, after) {
    G.Undo.initEdit(before);
    var r;
    try { r = fn(); }
    catch (e) { try { G.Undo.cancelEdit(); } catch (x) { } throw e; }
    var aft = typeof after === 'function' ? after(r) : (after || before);
    G.Undo.finishEdit(name, aft);
    return r;
  }
  function refresh() {
    try { G.Canvas.updateAll(); } catch (e) { }
    try { if (G.updateSelection) G.updateSelection(); } catch (e) { }
  }
  /** selects exactly these parts (groups and cubes) */
  function selectNodes(nodes) {
    try {
      if (typeof G.unselectAllElements === 'function') G.unselectAllElements();
      nodes.forEach(function (n, i) {
        if (i === 0) { n.select(); return; }
        try { n.select({ shiftKey: true, ctrlKey: true, ctrlOrCmd: true }); } catch (e) { }
      });
      // whatever the click logic did, the list is exact afterwards
      nodes.forEach(function (n) {
        if (isGroup(n)) { var ms = G.Group.multi_selected; if (Array.isArray(ms) && ms.indexOf(n) < 0) ms.push(n); }
        cubesOf([n]).forEach(function (c) { if (G.Outliner.selected.indexOf(c) < 0) G.Outliner.selected.push(c); });
      });
      if (G.updateSelection) G.updateSelection();
    } catch (e) { L.warn('could not select the new parts', e); }
  }

  // =================================================================== geometry (model space = cube coordinates)
  function V3(x, y, z) { return new G.THREE.Vector3(x, y, z); }
  function root3d() { return (G.Project && G.Project.model_3d) || null; }
  var DEG = Math.PI / 180;
  /** rotation of one node (cube or group) as Blockbench applies it (degrees, order ZYX, around its pivot) */
  function nodeQuat(n) {
    var r = n && n.rotation; if (!r || (!r[0] && !r[1] && !r[2])) return null;
    return new G.THREE.Quaternion().setFromEuler(new G.THREE.Euler(r[0] * DEG, r[1] * DEG, r[2] * DEG, 'ZYX'));
  }
  /** a point written in node's coordinates -> model space (the node's own rotation, then every group above it) */
  function toModelSpace(p, node, includeSelf) {
    var v = p.clone();
    for (var n = includeSelf ? node : node && node.parent; n && n !== 'root'; n = n.parent) {
      var q = nodeQuat(n); if (!q) continue;
      var o = V3(n.origin[0], n.origin[1], n.origin[2]);
      v.sub(o).applyQuaternion(q).add(o);
    }
    return v;
  }
  /** the 8 corners (model space) of a cube as you see it: inflate, the cube's rotation and its groups' rotations */
  function cubeCorners(c) {
    var inf = c.inflate || 0, lo = [], hi = [], out = [];
    for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i]) - inf); hi.push(Math.max(c.from[i], c.to[i]) + inf); }
    for (var k = 0; k < 8; k++) out.push(toModelSpace(V3(k & 1 ? hi[0] : lo[0], k & 2 ? hi[1] : lo[1], k & 4 ? hi[2] : lo[2]), c, true));
    return out;
  }
  /** box (model space) of a cube as you see it (computed from its data: exact, never a stale mesh) */
  function cubeBox(c) { return new G.THREE.Box3().setFromPoints(cubeCorners(c)); }
  /** box (model space) of a part: a cube, or every cube inside a group (empty group: its pivot) */
  function nodeBox(n) {
    var B = G.THREE.Box3, b = new B();
    cubesOf([n]).forEach(function (c) { b.union(cubeBox(c)); });
    if (b.isEmpty() && isGroup(n)) b.setFromPoints([V3(n.origin[0], n.origin[1], n.origin[2])]);
    return b;
  }
  function nodesBox(nodes) { var b = new G.THREE.Box3(); nodes.forEach(function (n) { b.union(nodeBox(n)); }); return b; }
  /** rotation (model space) of the frame the coordinates of node are written in (the rotations of its groups) */
  function parentQuat(node) {
    var q = new G.THREE.Quaternion();
    for (var n = node && node.parent; n && n !== 'root'; n = n.parent) { var r = nodeQuat(n); if (r) q.premultiply(r); }
    return q;
  }
  /** adds d (array of 3) to a cube's from / to / pivot */
  function shiftCube(c, d) {
    for (var i = 0; i < 3; i++) { c.from[i] += d[i]; c.to[i] += d[i]; c.origin[i] += d[i]; }
  }
  /** adds d to a group's pivot and everything inside (coordinates of the children are not relative in Blockbench) */
  function shiftGroup(g, d) {
    for (var i = 0; i < 3; i++) g.origin[i] += d[i];
    (g.children || []).forEach(function (k) { if (isGroup(k)) shiftGroup(k, d); else if (isCube(k)) shiftCube(k, d); else if (k.origin) for (var j = 0; j < 3; j++) k.origin[j] += d[j]; });
  }
  /** moves a part by a model-space vector (it can sit inside rotated groups: the vector is turned into their frame) */
  function moveBy(node, v) {
    var q = parentQuat(node).invert(), d = V3(v[0], v[1], v[2]).applyQuaternion(q), arr = [snap(d.x), snap(d.y), snap(d.z)];
    if (isGroup(node)) shiftGroup(node, arr); else if (isCube(node)) shiftCube(node, arr);
  }
  /** rounds away float noise (0.30000000004 -> 0.3) */
  function snap(x) { return Math.round(x * 10000) / 10000; }
  /** model-space point -> the coordinates node is written in (undoes the rotations of its groups, top first) */
  function toParentFrame(p, node) {
    var chain = [];
    for (var n = node && node.parent; n && n !== 'root'; n = n.parent) chain.push(n);
    var v = p.clone();
    for (var i = chain.length - 1; i >= 0; i--) {
      var q = nodeQuat(chain[i]); if (!q) continue;
      var o = V3(chain[i].origin[0], chain[i].origin[1], chain[i].origin[2]);
      v.sub(o).applyQuaternion(q.invert()).add(o);
    }
    return v;
  }
  /** pivot of a node in model space */
  function pivotOf(node) { return toModelSpace(V3(node.origin[0], node.origin[1], node.origin[2]), node, false); }
  /** sets a node's rotation (degrees, Blockbench order ZYX) from a quaternion */
  function setQuat(node, q) {
    var e = new G.THREE.Euler().setFromQuaternion(q, 'ZYX');
    node.rotation[0] = snap(e.x / DEG); node.rotation[1] = snap(e.y / DEG); node.rotation[2] = snap(e.z / DEG);
  }
  /**
   * turns a part around the line through C (model space) along axis (unit vector, model space) by deg degrees; the
   * part keeps its own orientation unless turnSelf; rise = extra move along the axis (spirals). Works inside rotated
   * groups (everything is done in the part's own frame).
   */
  function revolve(node, C, axis, deg, turnSelf, rise) {
    var qp = parentQuat(node), axisP = axis.clone().applyQuaternion(qp.clone().invert()).normalize();
    var R = new G.THREE.Quaternion().setFromAxisAngle(axisP, deg * DEG), Cp = toParentFrame(C, node);
    var O = V3(node.origin[0], node.origin[1], node.origin[2]);
    var O2 = O.clone().sub(Cp).applyQuaternion(R).add(Cp);
    if (rise) O2.add(axisP.clone().multiplyScalar(rise));
    var d = [snap(O2.x - O.x), snap(O2.y - O.y), snap(O2.z - O.z)];
    if (isGroup(node)) shiftGroup(node, d); else if (isCube(node)) shiftCube(node, d);
    if (turnSelf && node.rotation) { var q = nodeQuat(node) || new G.THREE.Quaternion(); setQuat(node, q.premultiply(R)); }
  }
  /** a copy of a part (cube or group with everything inside), right after it, with fresh CPM ids */
  function duplicateNode(node) {
    var copy = node.duplicate();
    if (!copy || copy === node) throw new Error('could not copy ' + node.name);
    forgetIds(copy);
    return copy;
  }
  /** matrix (model space) of a mesh: its world matrix with the model root taken out */
  function meshMatrix(mesh) {
    mesh.updateMatrixWorld(true);
    var r = root3d(), m = mesh.matrixWorld.clone();
    if (r) { r.updateMatrixWorld(true); m.premultiply(new G.THREE.Matrix4().copy(r.matrixWorld).invert()); }
    return m;
  }
  /** live preview made of copies of real meshes: items = [{mesh, matrix (model space)}] */
  function previewClones(items) {
    clearPreview();
    var r = root3d(); if (!r) return;
    var grp = new G.THREE.Group(); grp.name = 'cpmplus_model_preview';
    items.forEach(function (it) {
      if (!it.mesh) return;
      var m = new G.THREE.Mesh(it.mesh.geometry, it.mesh.material);
      m.userData.shared = true;// the real part's geometry: never disposed by clearPreview
      m.matrixAutoUpdate = false; m.matrix.copy(it.matrix);
      grp.add(m);
    });
    r.add(grp); previewObj = grp;
    try { if (G.Preview && G.Preview.all) G.Preview.all.forEach(function (p) { if (p.render) p.render(); }); } catch (e) { }
  }

  // =================================================================== creating parts
  /** a new group (inside parent, pivot at origin) - not selected, not in the undo yet */
  function newGroup(name, origin, parent) {
    var g = new G.Group({ name: name, origin: origin || [0, 0, 0] }).init();
    if (parent && parent !== 'root') g.addTo(parent); else g.addTo();
    return g;
  }
  /**
   * a new cube: o = {name, from, to, origin?, rotation?, colour (rgb int, CPM colour cube), inflate?}; in a CPM
   * project a cube without texture is a colour cube (box UV, the colour in Recolorir); elsewhere it gets the texture
   * the project paints with
   */
  function newCube(o, parent) {
    var c = new G.Cube({
      name: o.name || 'cube', from: o.from.slice(), to: o.to.slice(), origin: (o.origin || [(o.from[0] + o.to[0]) / 2, (o.from[1] + o.to[1]) / 2, (o.from[2] + o.to[2]) / 2]).slice(),
      rotation: (o.rotation || [0, 0, 0]).slice(), inflate: o.inflate || 0, box_uv: true, uv_offset: (o.uv_offset || [0, 0]).slice()
    }).init();
    if (parent && parent !== 'root') c.addTo(parent); else c.addTo();
    if (o.colour !== undefined && o.colour !== null && isCpm()) {
      for (var f in c.faces) if (c.faces[f]) c.faces[f].texture = false;
      c.cpm_recolor = o.colour;
    } else if (o.texture !== false) {
      var tex = (G.Texture && (G.Texture.getDefault ? G.Texture.getDefault() : (G.Texture.selected || G.Texture.all[0]))) || null;
      if (tex) { try { c.applyTexture(tex, true); } catch (e) { } }
    }
    return c;
  }
  /** a copy made by a modelling tool forgets its CPM ids (inside the undo entry) */
  function forgetIds(node) { if (isCpm()) { try { L.resetIds(node); } catch (e) { L.warn('could not reset the CPM ids', e); } } }
  /** 0xRRGGBB from '#rrggbb' / {r,g,b} / tinycolor */
  function rgbOf(c) {
    if (typeof c === 'number') return c & 0xFFFFFF;
    if (!c) return 0x7F7F7F;
    if (typeof c === 'string') { var h = c.replace('#', ''); if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2]; return parseInt(h.slice(0, 6), 16) & 0xFFFFFF; }
    if (typeof c.toHexString === 'function') return rgbOf(c.toHexString());
    if (c.r !== undefined) return ((c.r & 255) << 16) | ((c.g & 255) << 8) | (c.b & 255);
    return 0x7F7F7F;
  }
  function hexOf(rgb) { return '#' + ('000000' + (rgb & 0xFFFFFF).toString(16)).slice(-6); }

  // =================================================================== voxels -> few boxes (greedy)
  /**
   * fill(x, y, z) -> truthy key (same key = may share a box; e.g. a colour) for the cells of an nx * ny * nz grid.
   * Returns boxes {x, y, z, w, h, d, k} (cells), grown along x, then z, then y: few boxes for blocky shapes.
   */
  function greedyBoxes(nx, ny, nz, fill) {
    var n = nx * ny * nz, key = new Array(n), used = new Uint8Array(n), out = [];
    function at(x, y, z) { return (y * nz + z) * nx + x; }
    for (var y = 0; y < ny; y++) for (var z = 0; z < nz; z++) for (var x = 0; x < nx; x++) key[at(x, y, z)] = fill(x, y, z) || 0;
    for (var y1 = 0; y1 < ny; y1++) for (var z1 = 0; z1 < nz; z1++) for (var x1 = 0; x1 < nx; x1++) {
      var i0 = at(x1, y1, z1), k = key[i0];
      if (!k || used[i0]) continue;
      var w = 1; while (x1 + w < nx && key[at(x1 + w, y1, z1)] === k && !used[at(x1 + w, y1, z1)]) w++;
      var d = 1, ok;
      for (;;) { if (z1 + d >= nz) break; ok = true; for (var i = 0; i < w; i++) { var j = at(x1 + i, y1, z1 + d); if (key[j] !== k || used[j]) { ok = false; break; } } if (!ok) break; d++; }
      var h = 1;
      for (;;) {
        if (y1 + h >= ny) break; ok = true;
        for (var kz = 0; kz < d && ok; kz++) for (var kx = 0; kx < w; kx++) { var q = at(x1 + kx, y1 + h, z1 + kz); if (key[q] !== k || used[q]) { ok = false; break; } }
        if (!ok) break; h++;
      }
      for (var yy = 0; yy < h; yy++) for (var zz = 0; zz < d; zz++) for (var xx = 0; xx < w; xx++) used[at(x1 + xx, y1 + yy, z1 + zz)] = 1;
      out.push({ x: x1, y: y1, z: z1, w: w, h: h, d: d, k: k });
    }
    return out;
  }

  // =================================================================== live 3D preview (boxes drawn over the model, not parts yet)
  var previewObj = null;
  /**
   * boxes: [{from, to, rotation?, origin?, matrix? (THREE.Matrix4: model space, e.g. a chain of rotated groups), col?
   * (rgb int, this box's own colour)}] in model space; colour = rgb int of the boxes without col; opts.opacity
   */
  function preview(boxes, colour, opts) {
    clearPreview();
    var r = root3d(); if (!r || !G.THREE) return;
    var grp = new G.THREE.Group(); grp.name = 'cpmplus_model_preview';
    var own = grp.userData.ownMaterials = [], mats = {}, opacity = opts && opts.opacity ? opts.opacity : 0.55;
    function matFor(c) {
      var k = c === undefined || c === null ? 'base' : String(c);
      if (!mats[k]) { mats[k] = new G.THREE.MeshBasicMaterial({ color: new G.THREE.Color(k === 'base' ? (colour === undefined ? 0x4fc3f7 : colour) : c), transparent: true, opacity: opacity, depthWrite: false }); own.push(mats[k]); }
      return mats[k];
    }
    var edgeMat = new G.THREE.LineBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.8 }); own.push(edgeMat);
    boxes.forEach(function (b) {
      var sx = Math.abs(b.to[0] - b.from[0]) || 0.01, sy = Math.abs(b.to[1] - b.from[1]) || 0.01, sz = Math.abs(b.to[2] - b.from[2]) || 0.01;
      var geo = new G.THREE.BoxGeometry(sx, sy, sz), m = new G.THREE.Mesh(geo, matFor(b.col));
      var cx = (b.from[0] + b.to[0]) / 2, cy = (b.from[1] + b.to[1]) / 2, cz = (b.from[2] + b.to[2]) / 2;
      var holder = new G.THREE.Object3D();
      if (b.matrix) {
        holder.matrixAutoUpdate = false; holder.matrix.copy(b.matrix);
        m.position.set(cx, cy, cz);
      } else if (b.rotation && (b.rotation[0] || b.rotation[1] || b.rotation[2])) {
        var o = b.origin || [cx, cy, cz];
        holder.position.set(o[0], o[1], o[2]);
        holder.rotation.set(b.rotation[0] * Math.PI / 180, b.rotation[1] * Math.PI / 180, b.rotation[2] * Math.PI / 180, 'ZYX');
        m.position.set(cx - o[0], cy - o[1], cz - o[2]);
      } else m.position.set(cx, cy, cz);
      var e = new G.THREE.LineSegments(new G.THREE.EdgesGeometry(geo), edgeMat); m.add(e);
      holder.add(m); grp.add(holder);
    });
    r.add(grp); previewObj = grp;
    try { if (G.Canvas && G.Canvas.updateView) G.Canvas.updateView({}); } catch (e) { }
    try { if (G.Preview && G.Preview.all) G.Preview.all.forEach(function (p) { if (p.render) p.render(); }); } catch (e) { }
  }
  function clearPreview() {
    if (!previewObj) return;
    try { if (previewObj.parent) previewObj.parent.remove(previewObj); } catch (e) { }
    previewObj.traverse(function (o) { try { if (o.geometry && !o.userData.shared) o.geometry.dispose(); } catch (e) { } });
    // only the materials made for the preview (previewClones borrows the real parts' materials and geometry)
    (previewObj.userData.ownMaterials || []).forEach(function (m) { try { m.dispose(); } catch (e) { } });
    previewObj = null;
    try { if (G.Preview && G.Preview.all) G.Preview.all.forEach(function (p) { if (p.render) p.render(); }); } catch (e) { }
  }

  // =================================================================== actions: registry + where they show up
  var defs = [], created = [], cleanups = [];
  var SECTIONS = ['create', 'adjust', 'organize', 'texture', 'check', 'rig'];
  /**
   * d = {id, sec (panel section), icon, key (lang: cpmplus.model.<key>, .desc), run(event), need (string: 'sel' |
   * 'cubes' | 'groups' | 'two' | fn -> true when usable), cpm (CPM projects only), spots: ['outliner', 'position',
   * 'tools', 'transform', 'edit', 'view', 'cube', 'group'], after: {spot: afterId}}
   */
  function register(d) {
    var action = L.mk('Action', d.id, {
      name: T(d.key), description: T(d.key + '.desc'), icon: d.icon, category: 'edit',
      condition: function () { return usable(d); },
      click: function (e) { runDef(d, e); }
    });
    d.action = action; defs.push(d);
    placeAll(d);
    return action;
  }
  /** the part of the condition the panel shows greyed out (the action exists but needs a selection first) */
  function ready(d) {
    if (!hasProject()) return false;
    if (d.cpm && !isCpm()) return false;
    var n = d.need;
    if (!n) return true;
    if (typeof n === 'function') return !!n();
    if (n === 'sel') return selNodes().length > 0;
    if (n === 'cubes') return selCubes().length > 0;
    if (n === 'groups') return selGroups().length > 0;
    if (n === 'two') return selNodes().length > 1;
    return true;
  }
  function usable(d) { return hasProject() && (!d.cpm || isCpm()) && (!d.mode || d.mode === 'any' || (d.mode === 'edit' ? inEdit() : true)) && ready(d); }
  function runDef(d, e) {
    if (!ready(d)) { G.Blockbench.showQuickMessage(T('need.' + (typeof d.need === 'string' ? d.need : 'sel')), 2500); return; }
    try { d.run(e); } catch (x) { L.warn('modelling tool ' + d.id + ' failed', x); G.Blockbench.showMessageBox({ title: T(d.key), message: T('error') + '\n' + (x && x.message ? x.message : String(x)) }); }
  }
  function placeAll(d) {
    // a tool with choices (d.submenu = function -> menu items) shows as a submenu in the menus
    var item = d.submenu ? { id: d.id, name: T(d.key), description: T(d.key + '.desc'), icon: d.icon, condition: function () { return usable(d); }, children: d.submenu } : d.action;
    (d.spots || []).forEach(function (s) {
      try {
        if (s === 'outliner' || s === 'position') L.place(s === 'position' ? 'element_position' : 'outliner', d.action, (d.after && d.after[s]) || (s === 'outliner' ? 'add_group' : 'position_slider_per_element'));
        else if (s === 'tools') L.place('tools', d.action, (d.after && d.after.tools) || 'vertex_snap_tool');
        else if (s === 'textures') L.place('texturelist', d.action, (d.after && d.after.textures) || 'append_to_template');
        else if (s === 'transform' || s === 'edit' || s === 'view') menuBarAdd(s, item);
        else if (s === 'cube') ctxAdd(G.Cube && G.Cube.prototype.menu, item);
        else if (s === 'group') ctxAdd(G.Group && G.Group.prototype.menu, item);
      } catch (e) { L.warn('could not place ' + d.id + ' in ' + s, e); }
    });
    subMenuAdd(item);
  }
  /** a labelled separator 'CPM+ Modelar' once at the end of a menu structure, then our items after it */
  function sepIn(structure) {
    for (var i = 0; i < structure.length; i++) { var s = structure[i]; if (s && s.__cpmplusModel) return s; }
    var sep = typeof G.MenuSeparator === 'function' ? new G.MenuSeparator(MENU_SEP, T('menu.section')) : '_';
    if (sep && typeof sep === 'object') sep.__cpmplusModel = true;
    structure.push(sep);
    cleanups.push(function () { var k = structure.indexOf(sep); if (k >= 0) structure.splice(k, 1); });
    return sep;
  }
  function pushAfterSep(structure, item) {
    // drop a stale copy of the same id (a beta loaded twice)
    for (var i = structure.length - 1; i >= 0; i--) { var it = structure[i]; if (it && it !== item && it.id === item.id) structure.splice(i, 1); }
    if (structure.indexOf(item) >= 0) return;
    sepIn(structure);
    structure.push(item);
    cleanups.push(function () { var k = structure.indexOf(item); if (k >= 0) structure.splice(k, 1); });
  }
  function menuBarAdd(menuId, item) {
    var m = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus[menuId];
    if (!m || !Array.isArray(m.structure)) return;
    pushAfterSep(m.structure, item);
  }
  function ctxAdd(menu, item) { if (menu && Array.isArray(menu.structure)) pushAfterSep(menu.structure, item); }
  /** CPM+ > Modelagem */
  function subMenuAdd(item) {
    var m = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.cpm_plus_tools, sub = null;
    if (!m || !Array.isArray(m.structure)) return;
    for (var i = 0; i < m.structure.length; i++) { var s = m.structure[i]; if (s && s.id === 'cpmplus_sub_model') sub = s; }
    if (!sub || !Array.isArray(sub.children)) return;
    var kids = sub.children;
    for (var j = kids.length - 1; j >= 0; j--) { var it = kids[j]; if (it && it !== item && it.id === item.id) kids.splice(j, 1); }
    if (kids.indexOf(item) >= 0) return;
    if (!kids.some(function (x) { return x && x.__cpmplusModel; })) {
      var sep = typeof G.MenuSeparator === 'function' ? new G.MenuSeparator(MENU_SEP + '_sub', T('menu.section')) : '_';
      if (sep && typeof sep === 'object') sep.__cpmplusModel = true;
      kids.push(sep);
      cleanups.push(function () { var k = kids.indexOf(sep); if (k >= 0) kids.splice(k, 1); });
    }
    kids.push(item);
    cleanups.push(function () { var k = kids.indexOf(item); if (k >= 0) kids.splice(k, 1); });
  }

  // =================================================================== what the panel shows about the selection / model
  /** weight of the model for other players (CPM draws every part; past ~256 parts crowds get slow) */
  function weight(n) { return n <= 128 ? 'light' : n <= 256 ? 'medium' : 'heavy'; }
  function info() {
    var nodes = selNodes(), cubes = cubesOf(nodes), total = allCubes().length, o = { cubes: cubes.length, nodes: nodes.length, total: total, weight: weight(total) };
    if (nodes.length) {
      var b = nodesBox(nodes);
      if (!b.isEmpty()) {
        o.size = [snap(b.max.x - b.min.x), snap(b.max.y - b.min.y), snap(b.max.z - b.min.z)];
        o.center = [snap((b.min.x + b.max.x) / 2), snap((b.min.y + b.max.y) / 2), snap((b.min.z + b.max.z) / 2)];
      }
      if (nodes.length === 2) {
        var b1 = nodeBox(nodes[0]), b2 = nodeBox(nodes[1]), gap = [0, 0, 0];
        for (var i = 0; i < 3; i++) { var a = ['x', 'y', 'z'][i]; gap[i] = snap(Math.max(0, Math.max(b1.min[a], b2.min[a]) - Math.min(b1.max[a], b2.max[a]))); }
        var c1 = b1.getCenter(V3(0, 0, 0)), c2 = b2.getCenter(V3(0, 0, 0));
        o.gap = gap; o.dist = snap(c1.distanceTo(c2));
      }
    }
    return o;
  }

  /**
   * the line under a dialog's form: 'text - Modelo: N pecas (leve)'. The model's weight is information only (many
   * models are made for animations / videos, not crowded servers): never a warning colour; warn = something is wrong
   * with what the dialog would do.
   */
  function dialogNote(dlg, text, total, warn) {
    var root = dlg && dlg.object; if (!root) return;
    var p = root.querySelector('.cmp_count');
    if (!p) {
      p = document.createElement('p'); p.className = 'cmp_count';
      var content = root.querySelector('.dialog_content') || root; content.appendChild(p);
    }
    var w = weight(total);
    p.textContent = text + ' \u00B7 ' + T('weight.model', total, T('weight.' + w));
    p.title = T('weight.tip');
    p.classList.toggle('warn', !!warn);
  }
  var STYLE = 'cpmplus_model_style';
  function installStyle() {
    if (document.getElementById(STYLE)) return;
    var st = document.createElement('style'); st.id = STYLE;
    st.textContent = [
      '.cmp_dlg_tip{opacity:.8;margin:0 0 8px;font-size:.95em}',
      '.cmp_count{font-weight:600;margin:10px 0 0}',
      '.cmp_count.warn{color:var(--color-warning, #e8a33d)}'
    ].join('\n');
    document.head.appendChild(st);
    cleanups.push(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
  }

  // =================================================================== API for the modelling modules
  api.T = T; api.num = num; api.isCpm = isCpm; api.inEdit = inEdit; api.hasProject = hasProject;
  api.selCubes = selCubes; api.selGroups = selGroups; api.selNodes = selNodes; api.cubesOf = cubesOf; api.groupsOf = groupsOf; api.allCubes = allCubes;
  api.isGroup = isGroup; api.isCube = isCube; api.isBase = isBase; api.targetParent = targetParent;
  api.edit = edit; api.refresh = refresh; api.selectNodes = selectNodes;
  api.V3 = V3; api.cubeBox = cubeBox; api.nodeBox = nodeBox; api.nodesBox = nodesBox; api.moveBy = moveBy; api.shiftCube = shiftCube; api.shiftGroup = shiftGroup; api.snap = snap; api.parentQuat = parentQuat;
  api.cubeCorners = cubeCorners; api.toModelSpace = toModelSpace; api.toParentFrame = toParentFrame; api.pivotOf = pivotOf; api.nodeQuat = nodeQuat; api.setQuat = setQuat;
  api.revolve = revolve; api.duplicateNode = duplicateNode; api.meshMatrix = meshMatrix; api.previewClones = previewClones; api.DEG = DEG;
  api.newGroup = newGroup; api.newCube = newCube; api.forgetIds = forgetIds; api.rgbOf = rgbOf; api.hexOf = hexOf;
  api.greedyBoxes = greedyBoxes; api.preview = preview; api.clearPreview = clearPreview;
  api.register = register; api.defs = function () { return defs.slice(); }; api.sections = SECTIONS.slice(); api.ready = ready; api.usable = usable;
  api.info = info; api.weight = weight; api.dialogNote = dialogNote;
  /** runs a registered action as a click would (QA, panel) */
  api.run = function (id, e) { for (var i = 0; i < defs.length; i++) if (defs[i].id === id) { runDef(defs[i], e); return true; } return false; };
  api.install = function () { installStyle(); };
  api.uninstall = function () {
    clearPreview();
    cleanups.splice(0).reverse().forEach(function (f) { try { f(); } catch (e) { } });
    defs = [];
  };
  return api;
})
