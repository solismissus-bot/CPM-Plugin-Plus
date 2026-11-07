/*
 * CPM Plugin+ beta33 "CPM+ Modelar" - creating parts:
 * - Formas prontas: sphere, dome, cylinder, tube, cone, ring, arch, capsule, rounded box and stepped pyramid built from
 *   cubes (Blockbench's own primitives are meshes, which CPM cannot use). The shape is voxelised at the chosen detail
 *   and the voxels are merged into as few boxes as possible; in a CPM project the cubes are colour cubes (no skin space
 *   used). Live 3D preview with the number of parts, one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var SHAPES = ['sphere', 'dome', 'cylinder', 'tube', 'cone', 'ring', 'arch', 'capsule', 'rbox', 'pyramid'];
  // which fields each shape uses
  var USES = {
    sphere: { hollow: 1 }, dome: { hollow: 1 },
    cylinder: { height: 1, axis: 1, hollow: 1 }, tube: { height: 1, thick: 1, axis: 1 }, cone: { height: 1, axis: 1 },
    ring: { thick: 1, axis: 1 }, arch: { thick: 1, depth: 1, axis: 1 }, capsule: { height: 1, axis: 1 },
    rbox: { height: 1, depth: 1, radius: 1 }, pyramid: { height: 1, axis: 1 }
  };
  var DEF = { shape: 'sphere', size: 8, height: 8, depth: 4, thick: 1, radius: 2, hollow: false, axis: 'y', res: '1', name: '' };

  // =================================================================== voxels
  /** the cells of a shape: {nx, ny, nz, fill(x, y, z)} in its own frame (y = the shape's axis) */
  function cells(o) {
    var res = Number(o.res) || 1;
    var D = Math.max(1, Math.round(o.size / res)), H = Math.max(1, Math.round(o.height / res)), P = Math.max(1, Math.round(o.depth / res));
    var th = Math.max(1, Math.round(o.thick / res)), rad = Math.max(0, Math.round(o.radius / res)), r = D / 2;
    function c(i, n) { return i + 0.5 - n / 2; }
    switch (o.shape) {
      case 'sphere': return { nx: D, ny: D, nz: D, fill: function (x, y, z) {
        var d2 = sq(c(x, D)) + sq(c(y, D)) + sq(c(z, D));
        return d2 <= r * r && !(o.hollow && d2 < sq(r - th)) ? 1 : 0;
      } };
      case 'dome': { var ny = Math.max(1, Math.ceil(D / 2)); return { nx: D, ny: ny, nz: D, fill: function (x, y, z) {
        var d2 = sq(c(x, D)) + sq(y + 0.5) + sq(c(z, D));
        return d2 <= r * r && !(o.hollow && d2 < sq(r - th) && y > 0) ? 1 : 0;
      } }; }
      case 'cylinder': return { nx: D, ny: H, nz: D, fill: function (x, y, z) {
        var d2 = sq(c(x, D)) + sq(c(z, D));
        return d2 <= r * r && !(o.hollow && d2 < sq(r - th) && y > 0 && y < H - 1) ? 1 : 0;
      } };
      case 'tube': return { nx: D, ny: H, nz: D, fill: function (x, y, z) {
        var d2 = sq(c(x, D)) + sq(c(z, D));
        return d2 <= r * r && d2 >= sq(Math.max(0, r - th)) ? 1 : 0;
      } };
      case 'cone': return { nx: D, ny: H, nz: D, fill: function (x, y, z) {
        var rr = r * (1 - (y + 0.5) / H) + 0.35;// the tip keeps at least one cell
        return sq(c(x, D)) + sq(c(z, D)) <= rr * rr ? 1 : 0;
      } };
      case 'ring': { var R = r - th / 2, rm = th / 2; return { nx: D, ny: th, nz: D, fill: function (x, y, z) {
        var q = Math.sqrt(sq(c(x, D)) + sq(c(z, D))) - R;
        return q * q + sq(c(y, th)) <= rm * rm + 0.25 ? 1 : 0;
      } }; }
      case 'arch': { var nyA = Math.max(1, Math.ceil(D / 2)); return { nx: D, ny: nyA, nz: P, fill: function (x, y) {
        var d = Math.sqrt(sq(c(x, D)) + sq(y + 0.5));
        return d <= r && d >= r - th ? 1 : 0;
      } }; }
      case 'capsule': { var HC = Math.max(H, D); return { nx: D, ny: HC, nz: D, fill: function (x, y, z) {
        var py = y + 0.5, lo = r, hi = HC - r, cy = py < lo ? py - lo : py > hi ? py - hi : 0;
        return sq(c(x, D)) + sq(cy) + sq(c(z, D)) <= r * r ? 1 : 0;
      } }; }
      case 'rbox': { var rr2 = Math.min(rad, Math.floor(Math.min(D, H, P) / 2)); return { nx: D, ny: H, nz: P, fill: function (x, y, z) {
        var dx = Math.max(0, Math.abs(c(x, D)) - (D / 2 - rr2)), dy = Math.max(0, Math.abs(c(y, H)) - (H / 2 - rr2)), dz = Math.max(0, Math.abs(c(z, P)) - (P / 2 - rr2));
        return dx * dx + dy * dy + dz * dz <= rr2 * rr2 + 0.01 ? 1 : 0;
      } }; }
      case 'pyramid': return { nx: D, ny: H, nz: D, fill: function (x, y, z) {
        var w = r * (1 - y / H);
        return Math.abs(c(x, D)) <= w && Math.abs(c(z, D)) <= w ? 1 : 0;
      } };
    }
    throw new Error('unknown shape ' + o.shape);
  }
  function sq(v) { return v * v; }

  /**
   * the boxes of a shape in model space, centred on centre: [{from, to}] + the cell count. Axis x / z turn the shape's
   * own y axis (height) into the model's x / z axis.
   */
  function boxes(o, centre) {
    var res = Number(o.res) || 1, g = cells(o), list = M.greedyBoxes(g.nx, g.ny, g.nz, g.fill), axis = USES[o.shape] && USES[o.shape].axis ? o.axis : 'y';
    // size of the grid in model axes
    function map(x, y, z) { return axis === 'x' ? [y, x, z] : axis === 'z' ? [x, z, y] : [x, y, z]; }
    var n = map(g.nx, g.ny, g.nz), base = [centre[0] - n[0] * res / 2, centre[1] - n[1] * res / 2, centre[2] - n[2] * res / 2];
    return list.map(function (b) {
      var p = map(b.x, b.y, b.z), s = map(b.w, b.h, b.d), from = [], to = [];
      for (var i = 0; i < 3; i++) { from.push(M.snap(base[i] + p[i] * res)); to.push(M.snap(base[i] + (p[i] + s[i]) * res)); }
      return { from: from, to: to };
    });
  }

  // =================================================================== where it goes
  /** centre of the new shape: the middle of the selection, else the pivot of the group it goes into, else a spot in front */
  function centreFor() {
    var nodes = M.selNodes();
    if (nodes.length) { var b = M.nodesBox(nodes); if (!b.isEmpty()) { var c = b.getCenter(M.V3(0, 0, 0)); return [M.snap(c.x), M.snap(c.y), M.snap(c.z)]; } }
    var p = M.targetParent();
    if (p && p !== 'root' && p.origin) return p.origin.slice();
    return [0, 16, 0];
  }
  function nameFor(o) { return (o.name && String(o.name).trim()) || T('shapes.s.' + o.shape).toLowerCase().replace(/\s+/g, '_'); }

  /** creates the shape (one undo entry): a new group with the cubes, inside the target group; returns the group */
  function create(o, centre, parent) {
    o = Object.assign({}, DEF, o || {});
    centre = centre || centreFor();
    parent = parent || M.targetParent();
    var list = boxes(o, centre), colour = M.rgbOf(o.colour || currentColour());
    if (!list.length) throw new Error(T('shapes.empty'));
    var group = null;
    // Blockbench 5: a group made inside the edit must be in the 'groups' aspect, or Ctrl+Z leaves it behind (empty)
    var made = M.edit(T('shapes'), { outliner: true, elements: [], groups: [] }, function () {
      group = M.newGroup(nameFor(o), centre, parent);
      var cubes = list.map(function (b, i) { return M.newCube({ name: nameFor(o) + '_' + (i + 1), from: b.from, to: b.to, origin: centre, colour: colour }, group); });
      return cubes;
    }, function (cubes) { return { outliner: true, elements: cubes, groups: [group] }; });
    M.refresh();
    try { if (typeof G.unselectAllElements === 'function') G.unselectAllElements(); group.select(); } catch (e) { }
    return { group: group, cubes: made };
  }
  function currentColour() {
    try { var c = G.ColorPanel && G.ColorPanel.get ? G.ColorPanel.get() : null; if (c) return c; } catch (e) { }
    return '#c8a070';
  }

  // =================================================================== the dialog (live preview)
  var dlg = null;
  function open() {
    var saved = {}; try { saved = JSON.parse(L.opt('model.shapes', '{}') || '{}') || {}; } catch (e) { saved = {}; }
    var cpm = M.isCpm(), centre = centreFor(), parent = M.targetParent(), last = Object.assign({}, DEF, saved);
    if (SHAPES.indexOf(last.shape) < 0) last.shape = DEF.shape;
    var opts = {}; SHAPES.forEach(function (s) { opts[s] = T('shapes.s.' + s); });
    function when(field) { return function (f) { var u = USES[f.shape] || {}; return !!u[field]; }; }
    var form = {
      shape: { label: T('shapes.f.shape'), type: 'select', options: opts, value: last.shape },
      size: { label: T('shapes.f.size'), type: 'number', value: last.size, min: 1, max: 64, step: 1 },
      height: { label: T('shapes.f.height'), type: 'number', value: last.height, min: 1, max: 64, step: 1, condition: when('height') },
      depth: { label: T('shapes.f.depth'), type: 'number', value: last.depth, min: 1, max: 64, step: 1, condition: when('depth') },
      thick: { label: T('shapes.f.thick'), type: 'number', value: last.thick, min: 0.5, max: 16, step: 0.5, condition: function (f) { return !!(USES[f.shape] || {}).thick || (!!(USES[f.shape] || {}).hollow && f.hollow); } },
      radius: { label: T('shapes.f.radius'), type: 'number', value: last.radius, min: 0, max: 16, step: 1, condition: when('radius') },
      hollow: { label: T('shapes.f.hollow'), type: 'checkbox', value: !!last.hollow, condition: when('hollow') },
      axis: { label: T('shapes.f.axis'), type: 'select', options: { y: T('shapes.axis.y'), x: T('shapes.axis.x'), z: T('shapes.axis.z') }, value: last.axis, condition: when('axis') },
      res: { label: T('shapes.f.res'), type: 'select', options: { '0.5': T('shapes.res.half'), '1': T('shapes.res.one'), '2': T('shapes.res.two') }, value: String(last.res) },
      name: { label: T('shapes.f.name'), type: 'text', value: '', placeholder: T('shapes.f.name.ph') }
    };
    if (cpm) form.colour = { label: T('shapes.f.colour'), type: 'color', value: currentColour() };
    // lang: shapes.tip.{cpm}
    dlg = new G.Dialog({
      id: 'cpmplus_model_shapes', title: T('shapes'), width: 460,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('shapes.tip' + (cpm ? '.cpm' : ''))) + '</p>'],
      form: form,
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview(); L.setOpt('model.shapes', JSON.stringify({ shape: f.shape, size: f.size, height: f.height, depth: f.depth, thick: f.thick, radius: f.radius, hollow: f.hollow, axis: f.axis, res: f.res }));
        try { create(f, centre, parent); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3000); return false; }
        dlg = null;
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var o = Object.assign({}, DEF, f), list = [];
      try { list = boxes(o, centre); } catch (e) { list = []; }
      M.preview(list, M.rgbOf(o.colour || '#4fc3f7'));
      M.dialogNote(dlg, T('shapes.count', list.length), M.allCubes().length + list.length, false);
    }
    try { update(dlg.getFormResult()); } catch (e) { }
    setTimeout(function () { if (dlg) update(dlg.getFormResult()); }, 0);
    return dlg;
  }

  // =================================================================== Repetir: copies in a line, a circle or a spiral
  // lang: repeat.m.{line|circle|spiral}
  var RMODES = ['line', 'circle', 'spiral'];
  var AXES = { x: [1, 0, 0], y: [0, 1, 0], z: [0, 0, 1] };
  var RDEF = { mode: 'line', count: 3, sx: 0, sy: 0, sz: 0, axis: 'y', angle: 360, around: 'parent', cx: 0, cy: 0, cz: 0, turn: true, rise: 2, wrap: false };
  /** angle between two copies: a full turn shares the circle with the original, a part of it ends on the last copy */
  function angleStep(o) { var a = Number(o.angle) || 0, n = Math.max(1, Number(o.count) || 1); return Math.abs(Math.abs(a) - 360) < 1e-6 ? a / (n + 1) : a / n; }
  /** centre of the turn (model space) */
  function turnCentre(o, nodes) {
    if (o.around === 'custom') return M.V3(Number(o.cx) || 0, Number(o.cy) || 0, Number(o.cz) || 0);
    if (o.around === 'origin') return M.V3(0, 0, 0);
    var p = nodes[0] && nodes[0].parent;
    if (p && p !== 'root') return M.pivotOf(p);
    return M.V3(0, 0, 0);
  }
  /** the model-space matrix that moves copy k of a part with pivot P (model space) */
  function copyMatrix(o, k, C, P) {
    var THREE = G.THREE, m = new THREE.Matrix4();
    if (o.mode === 'line') return m.makeTranslation(o.sx * k, o.sy * k, o.sz * k);
    var ax = M.V3(AXES[o.axis][0], AXES[o.axis][1], AXES[o.axis][2]), a = angleStep(o) * k * M.DEG;
    var rise = o.mode === 'spiral' ? (Number(o.rise) || 0) * k : 0;
    var R = new THREE.Matrix4().makeRotationAxis(ax, a);
    if (o.turn) {
      m.makeTranslation(C.x, C.y, C.z).multiply(R).multiply(new THREE.Matrix4().makeTranslation(-C.x, -C.y, -C.z));
    } else {
      var P2 = P.clone().sub(C).applyMatrix4(R).add(C);
      m.makeTranslation(P2.x - P.x, P2.y - P.y, P2.z - P.z);
    }
    if (rise) m.premultiply(new THREE.Matrix4().makeTranslation(ax.x * rise, ax.y * rise, ax.z * rise));
    return m;
  }
  function repeatPreview(o, nodes) {
    var C = turnCentre(o, nodes), items = [];
    nodes.forEach(function (n) {
      var P = M.pivotOf(n);
      M.cubesOf([n]).forEach(function (c) {
        if (!c.mesh) return;
        var base = M.meshMatrix(c.mesh);
        for (var k = 1; k <= o.count; k++) items.push({ mesh: c.mesh, matrix: copyMatrix(o, k, C, P).multiply(base) });
      });
    });
    M.previewClones(items);
    return items.length;
  }
  /** makes the copies (one undo entry); returns the new parts */
  function repeat(o, nodes) {
    o = Object.assign({}, RDEF, o || {});
    nodes = nodes || M.selNodes();
    if (!nodes.length) throw new Error(T('need.sel'));
    var count = Math.max(1, Math.min(64, Math.round(Number(o.count) || 1)));
    var C = turnCentre(o, nodes), axis = M.V3(AXES[o.axis][0], AXES[o.axis][1], AXES[o.axis][2]);
    var copies = [], newCubes = [], newGroups = [], wrapper = null;
    M.edit(T('repeat'), { outliner: true, elements: [], groups: [] }, function () {
      nodes.forEach(function (n) {
        for (var k = 1; k <= count; k++) {
          var c = M.duplicateNode(n);
          c.name = n.name + '_' + (k + 1);
          if (o.mode === 'line') M.moveBy(c, [o.sx * k, o.sy * k, o.sz * k]);
          else M.revolve(c, C, axis, angleStep(o) * k, !!o.turn, o.mode === 'spiral' ? (Number(o.rise) || 0) * k : 0);
          copies.push(c);
        }
      });
      if (o.wrap) {
        var parent = nodes[0].parent && nodes[0].parent !== 'root' ? nodes[0].parent : 'root';
        wrapper = M.newGroup(T('repeat.group', nodes[0].name), [M.snap(C.x), M.snap(C.y), M.snap(C.z)], parent);
        nodes.concat(copies).forEach(function (x) { x.addTo(wrapper); });
        newGroups.push(wrapper);
      }
      copies.forEach(function (c) { if (M.isGroup(c)) { newGroups.push(c); M.groupsOf([c]).forEach(function (g) { if (newGroups.indexOf(g) < 0) newGroups.push(g); }); } });
      newCubes = M.cubesOf(copies);
    }, function () { return { outliner: true, elements: newCubes, groups: newGroups }; });
    M.refresh();
    M.selectNodes(wrapper ? [wrapper] : nodes.concat(copies));
    return { copies: copies, cubes: newCubes, groups: newGroups, wrapper: wrapper };
  }
  var rdlg = null;
  function openRepeat() {
    var nodes = M.selNodes();
    if (!nodes.length) { G.Blockbench.showQuickMessage(T('need.sel'), 2500); return null; }
    var b = M.nodesBox(nodes), size = [M.snap(b.max.x - b.min.x), M.snap(b.max.y - b.min.y), M.snap(b.max.z - b.min.z)];
    var parentIsGroup = nodes[0].parent && nodes[0].parent !== 'root';
    var C0 = turnCentre({ around: parentIsGroup ? 'parent' : 'origin' }, nodes);
    var modes = {}; RMODES.forEach(function (m) { modes[m] = T('repeat.m.' + m); });
    var isLine = function (f) { return f.mode === 'line'; }, isTurn = function (f) { return f.mode !== 'line'; };
    // lang: repeat.around.{parent|origin|custom}
    var around = { parent: T('repeat.around.parent'), origin: T('repeat.around.origin'), custom: T('repeat.around.custom') };
    if (!parentIsGroup) delete around.parent;
    rdlg = new G.Dialog({
      id: 'cpmplus_model_repeat', title: T('repeat'), width: 480,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('repeat.tip')) + '</p>'],
      form: {
        mode: { label: T('repeat.f.mode'), type: 'select', options: modes, value: 'line' },
        count: { label: T('repeat.f.count'), type: 'number', value: 3, min: 1, max: 64, step: 1 },
        sx: { label: T('repeat.f.sx'), type: 'number', value: size[0] || 1, step: 0.5, condition: isLine },
        sy: { label: T('repeat.f.sy'), type: 'number', value: 0, step: 0.5, condition: isLine },
        sz: { label: T('repeat.f.sz'), type: 'number', value: 0, step: 0.5, condition: isLine },
        axis: { label: T('repeat.f.axis'), type: 'select', options: { y: T('shapes.axis.y'), x: T('shapes.axis.x'), z: T('shapes.axis.z') }, value: 'y', condition: isTurn },
        angle: { label: T('repeat.f.angle'), type: 'number', value: 360, min: -360, max: 360, step: 5, condition: isTurn },
        around: { label: T('repeat.f.around'), type: 'select', options: around, value: parentIsGroup ? 'parent' : 'origin', condition: isTurn },
        cx: { label: 'X', type: 'number', value: M.snap(C0.x), step: 0.5, condition: function (f) { return isTurn(f) && f.around === 'custom'; } },
        cy: { label: 'Y', type: 'number', value: M.snap(C0.y), step: 0.5, condition: function (f) { return isTurn(f) && f.around === 'custom'; } },
        cz: { label: 'Z', type: 'number', value: M.snap(C0.z), step: 0.5, condition: function (f) { return isTurn(f) && f.around === 'custom'; } },
        turn: { label: T('repeat.f.turn'), type: 'checkbox', value: true, condition: isTurn },
        rise: { label: T('repeat.f.rise'), type: 'number', value: 2, step: 0.5, condition: function (f) { return f.mode === 'spiral'; } },
        wrap: { label: T('repeat.f.wrap'), type: 'checkbox', value: false }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview();
        try { repeat(f, nodes); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3000); return false; }
        rdlg = null;
      },
      onCancel: function () { M.clearPreview(); rdlg = null; }
    });
    rdlg.show();
    function update(f) {
      var o = Object.assign({}, RDEF, f); o.count = Math.max(1, Math.min(64, Math.round(Number(o.count) || 1)));
      var n = 0; try { n = repeatPreview(o, nodes); } catch (e) { M.clearPreview(); }
      M.dialogNote(rdlg, T('repeat.count', o.count, n), M.allCubes().length + n, false);
    }
    try { update(rdlg.getFormResult()); } catch (e) { }
    setTimeout(function () { if (rdlg) update(rdlg.getFormResult()); }, 0);
    return rdlg;
  }

  // =================================================================== registration
  api.install = function () {
    M.register({ id: 'cpmplus_model_shapes', sec: 'create', icon: 'category', key: 'shapes', mode: 'edit', spots: ['outliner'], run: function () { open(); } });
    M.register({ id: 'cpmplus_model_repeat', sec: 'create', icon: 'auto_awesome_motion', key: 'repeat', mode: 'edit', need: 'sel', spots: ['transform', 'cube', 'group'], run: function () { openRepeat(); } });
  };
  api.uninstall = function () {
    [dlg, rdlg].forEach(function (d) { if (d) { try { d.close(); } catch (e) { } } });
    dlg = null; rdlg = null; M.clearPreview();
  };
  api.repeat = function (o, nodes) { return repeat(o, nodes); };
  api.openRepeat = openRepeat;
  api.repeatDialog = function () { return rdlg; };
  api.copyMatrix = copyMatrix;
  // QA / other modules
  api.SHAPES = SHAPES.slice();
  api.boxes = function (o, centre) { return boxes(Object.assign({}, DEF, o || {}), centre || [0, 0, 0]); };
  api.create = function (o, centre, parent) { return create(o, centre, parent); };
  api.open = open;
  api.dialog = function () { return dlg; };
  return api;
})
