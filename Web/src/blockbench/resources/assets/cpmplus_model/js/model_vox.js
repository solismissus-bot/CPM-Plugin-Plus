/*
 * CPM Plugin+ "CPM+ Modelar" - Importar .vox (MagicaVoxel): reads the RIFF-style .vox file (SIZE / XYZI pairs, several
 * models, the RGBA palette or MagicaVoxel's default one, the scene graph nTRN / nGRP / nSHP for where each model sits),
 * turns every colour into as few CPM colour cubes as possible (greedy boxes per colour), converts the axes (vox Z up ->
 * Blockbench Y up, the vox front -Y -> the player's front -Z) and puts it in a new group, centred on the chosen body
 * part. Hostile / broken / huge files are refused with a clear message (sizes are checked before anything is read or
 * allocated). File > Import and the CPM+ Modelar panel; live preview; one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var MAX_BYTES = 16 * 1024 * 1024, MAX_SIZE = 256, MAX_VOXELS = 2000000, MAX_CELLS = 4194304, MAX_MODELS = 1024, MAX_CUBES = 20000, MAX_PREVIEW = 3000;

  // =================================================================== the file
  // lang: vox.bad.{magic|nomain|trunc|chunk|size|big|empty|cells}
  function fail(code) { var e = new Error(T('vox.bad.' + code)); e.code = code; throw e; }
  /** MagicaVoxel's default palette (colour index -> 0xRRGGBB): the 6x6x6 cube (no black), then red, green, blue, grey ramps */
  function defaultPalette() {
    var p = [0], S6 = [0xff, 0xcc, 0x99, 0x66, 0x33, 0x00], R = [0xee, 0xdd, 0xbb, 0xaa, 0x88, 0x77, 0x55, 0x44, 0x22, 0x11];
    for (var j = 0; j < 215; j++) p.push((S6[Math.floor(j / 36)] << 16) | (S6[Math.floor(j / 6) % 6] << 8) | S6[j % 6]);
    [16, 8, 0].forEach(function (sh) { R.forEach(function (v) { p.push(v << sh); }); });
    R.forEach(function (v) { p.push((v << 16) | (v << 8) | v); });
    return p;
  }
  /**
   * parses a .vox file (ArrayBuffer / Uint8Array) -> {models: [{size: [x, y, z], xyzi: Uint8Array, count}], palette (256
   * rgb ints, [0] unused), defaultPalette (true when the file has no RGBA), scene (instances [{model, R, t}] or null),
   * skipped (voxels outside their model or with colour 0)}. Throws an Error with .code on a bad file.
   */
  function parse(buf) {
    var u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf || new ArrayBuffer(0)), n = u8.length;
    if (n > MAX_BYTES) fail('big');
    if (n < 20) fail('magic');
    var dv = new DataView(u8.buffer, u8.byteOffset, n);
    var id = function (at) { return String.fromCharCode(u8[at], u8[at + 1], u8[at + 2], u8[at + 3]); };
    var i32 = function (at) { return dv.getInt32(at, true); };
    if (id(0) !== 'VOX ') fail('magic');
    if (id(8) !== 'MAIN') fail('nomain');
    var mc = i32(12), mk = i32(16);
    if (mc < 0 || mk < 0 || 20 + mc + mk > n) fail('trunc');
    var end = 20 + mc + mk, p = 20 + mc, models = [], pending = null, palette = null, nodes = {}, badNodes = false, chunks = 0, voxels = 0;
    while (p < end) {
      if (p + 12 > end) fail('trunc');
      var cid = id(p), cs = i32(p + 4), ck = i32(p + 8), body = p + 12;
      if (cs < 0 || ck < 0 || body + cs + ck > end) fail('trunc');
      if (++chunks > 500000) fail('chunk');
      if (cid === 'SIZE') {
        if (cs < 12) fail('chunk');
        var sz = [i32(body), i32(body + 4), i32(body + 8)];
        if (sz.some(function (v) { return v < 1 || v > MAX_SIZE; })) fail('size');
        pending = { size: sz };
      } else if (cid === 'XYZI') {
        if (!pending || cs < 4) fail('chunk');
        var nv = i32(body);
        if (nv < 0 || 4 + nv * 4 > cs) fail('trunc');
        voxels += nv;
        if (voxels > MAX_VOXELS) fail('big');
        pending.xyzi = u8.slice(body + 4, body + 4 + nv * 4); pending.count = nv;
        models.push(pending); pending = null;
        if (models.length > MAX_MODELS) fail('big');
      } else if (cid === 'RGBA') {
        if (cs < 1024) fail('chunk');
        palette = [0];
        for (var k = 0; k < 255; k++) { var q = body + k * 4; palette.push((u8[q] << 16) | (u8[q + 1] << 8) | u8[q + 2]); }
      } else if (cid === 'nTRN' || cid === 'nGRP' || cid === 'nSHP') {
        try { readNode(cid, body, body + cs); } catch (e) { badNodes = true; }
      }
      p = body + cs + ck;
    }
    if (!models.length) fail('empty');
    // ---- scene graph nodes (a broken graph only loses the placement: the models are laid side by side)
    function reader(at, lim) {
      var r = { p: at };
      r.i32 = function () { if (r.p + 4 > lim) throw new Error('node'); var v = i32(r.p); r.p += 4; return v; };
      r.str = function () { var len = r.i32(); if (len < 0 || len > 4096 || r.p + len > lim) throw new Error('node'); var s = ''; for (var i = 0; i < len; i++) s += String.fromCharCode(u8[r.p + i]); r.p += len; return s; };
      r.dict = function () { var c = r.i32(); if (c < 0 || c > 256) throw new Error('node'); var d = {}; for (var i = 0; i < c; i++) { var key = r.str(); d[key] = r.str(); } return d; };
      return r;
    }
    function readNode(cid, at, lim) {
      var r = reader(at, lim), nid = r.i32(); r.dict();
      if (nid < 0 || nid > 1000000) throw new Error('node');
      if (cid === 'nTRN') {
        var child = r.i32(); r.i32(); r.i32();
        var nf = r.i32(), f0 = nf > 0 ? r.dict() : {};
        nodes[nid] = { t: 'T', child: child, tr: f0._t, rot: f0._r };
      } else if (cid === 'nGRP') {
        var nc = r.i32(); if (nc < 0 || nc > 65536) throw new Error('node');
        var kids = []; for (var i = 0; i < nc; i++) kids.push(r.i32());
        nodes[nid] = { t: 'G', kids: kids };
      } else {
        var nm = r.i32(); if (nm < 1) throw new Error('node');
        nodes[nid] = { t: 'S', model: r.i32() };
      }
    }
    var scene = badNodes ? null : sceneOf(nodes, models.length);
    var skipped = 0;
    models.forEach(function (m) {
      for (var v = 0; v < m.count; v++) {
        var o = v * 4;
        if (m.xyzi[o] >= m.size[0] || m.xyzi[o + 1] >= m.size[1] || m.xyzi[o + 2] >= m.size[2] || !m.xyzi[o + 3]) skipped++;
      }
    });
    if (voxels - skipped <= 0) fail('empty');
    return { models: models, palette: palette || defaultPalette(), defaultPalette: !palette, scene: scene, skipped: skipped };
  }
  var ID3 = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];
  function mul3(A, B) { var C = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]; for (var i = 0; i < 3; i++) for (var j = 0; j < 3; j++) for (var k = 0; k < 3; k++) C[i][j] += A[i][k] * B[k][j]; return C; }
  function app3(A, v) { return [A[0][0] * v[0] + A[0][1] * v[1] + A[0][2] * v[2], A[1][0] * v[0] + A[1][1] * v[1] + A[1][2] * v[2], A[2][0] * v[0] + A[2][1] * v[1] + A[2][2] * v[2]]; }
  /** the vox rotation byte: the column of the 1 in rows 1 and 2, then the signs of the 3 rows */
  function rotOf(s) {
    var b = parseInt(s, 10);
    if (!(b >= 0 && b < 128)) return ID3;
    var i0 = b & 3, i1 = (b >> 2) & 3;
    if (i0 > 2 || i1 > 2 || i0 === i1) return ID3;
    var R = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
    R[0][i0] = b & 16 ? -1 : 1; R[1][i1] = b & 32 ? -1 : 1; R[2][3 - i0 - i1] = b & 64 ? -1 : 1;
    return R;
  }
  function transOf(s) {
    var p = String(s || '').trim().split(/\s+/).map(function (x) { return parseInt(x, 10); });
    return p.length === 3 && p.every(function (x) { return isFinite(x) && Math.abs(x) < 100000; }) ? p : [0, 0, 0];
  }
  /** instances of the models from the scene graph (node 0 = root transform), or null when there is none / it is broken */
  function sceneOf(nodes, count) {
    if (!nodes[0]) return null;
    var out = [], steps = 0;
    function walk(nid, R, t, depth) {
      if (depth > 64 || ++steps > 200000) throw new Error('scene');
      var nd = nodes[nid]; if (!nd) throw new Error('scene');
      if (nd.t === 'T') walk(nd.child, mul3(R, rotOf(nd.rot)), (function (lt) { var a = app3(R, lt); return [t[0] + a[0], t[1] + a[1], t[2] + a[2]]; })(transOf(nd.tr)), depth + 1);
      else if (nd.t === 'G') nd.kids.forEach(function (k) { walk(k, R, t, depth + 1); });
      else { if (nd.model < 0 || nd.model >= count) throw new Error('scene'); out.push({ model: nd.model, R: R, t: t }); }
    }
    try { walk(0, ID3, [0, 0, 0], 0); } catch (e) { return null; }
    return out.length ? out : null;
  }

  // =================================================================== voxels -> Blockbench cells -> boxes
  /** the instances to build: one model alone (which = index) or the whole scene (which = 'all') */
  function instancesOf(vox, which) {
    if (which !== 'all' && which !== undefined && which !== null) {
      var i = Number(which); if (!(i >= 0 && i < vox.models.length)) i = 0;
      return [{ model: i, R: ID3, t: null }];
    }
    if (vox.scene) return vox.scene;
    // no scene: side by side along X (2 empty voxels between), bottoms on the ground
    var x = 0;
    return vox.models.map(function (m, i) { var it = { model: i, R: ID3, t: null, off: [x, 0, 0] }; x += m.size[0] + 2; return it; });
  }
  /** vox voxel (x, y, z) of an instance -> world vox coordinates */
  function placeFn(inst, m) {
    if (!inst.t) { var o = inst.off || [0, 0, 0]; return function (x, y, z) { return [x + o[0], y + o[1], z + o[2]]; }; }
    var c = [Math.floor(m.size[0] / 2), Math.floor(m.size[1] / 2), Math.floor(m.size[2] / 2)];
    return function (x, y, z) { var w = app3(inst.R, [x - c[0], y - c[1], z - c[2]]); return [w[0] + inst.t[0], w[1] + inst.t[1], w[2] + inst.t[2]]; };
  }
  /**
   * the cells of the import in Blockbench axes: x = -vox x, y = vox z (up), z = vox y -> {nx, ny, nz, cells (Int32Array,
   * rgb + 1, 0 = empty), count, colours}
   */
  function voxelize(vox, which) {
    var insts = instancesOf(vox, which), lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity], any = false;
    function each(fn) {
      insts.forEach(function (inst) {
        var m = vox.models[inst.model], at = placeFn(inst, m), d = m.xyzi;
        for (var v = 0; v < m.count; v++) {
          var o = v * 4;
          if (d[o] >= m.size[0] || d[o + 1] >= m.size[1] || d[o + 2] >= m.size[2] || !d[o + 3]) continue;
          fn(at(d[o], d[o + 1], d[o + 2]), d[o + 3]);
        }
      });
    }
    each(function (w) { any = true; for (var i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], w[i]); hi[i] = Math.max(hi[i], w[i]); } });
    if (!any) fail('empty');
    var nx = hi[0] - lo[0] + 1, ny = hi[2] - lo[2] + 1, nz = hi[1] - lo[1] + 1;
    if (nx * ny * nz > MAX_CELLS) fail('cells');
    var cells = new Int32Array(nx * ny * nz), count = 0, colours = {};
    each(function (w, ci) {
      var gx = hi[0] - w[0], gy = w[2] - lo[2], gz = w[1] - lo[1], k = (gy * nz + gz) * nx + gx;
      var rgb = vox.palette[ci] & 0xFFFFFF;
      if (!cells[k]) count++;
      cells[k] = rgb + 1; colours[rgb] = 1;
    });
    return { nx: nx, ny: ny, nz: nz, cells: cells, count: count, colours: Object.keys(colours).length };
  }
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  function rootGroup(name) {
    if (!G.Group) return null;
    for (var i = 0; i < G.Group.all.length; i++) { var g = G.Group.all[i]; if (g.parent === 'root' && g.name === name) return g; }
    return null;
  }
  function whereOptions() {
    var o = {};
    if (M.selNodes().length) o.sel = T('text.where.sel');
    ROOTS.forEach(function (r) { if (rootGroup(r)) o['root:' + r] = T('lib.root.' + r); });
    (G.Group ? G.Group.all : []).forEach(function (g) { if (g.parent === 'root' && ROOTS.indexOf(g.name) < 0 && !o['root:' + g.name]) o['root:' + g.name] = String(g.name); });
    o.none = T('vox.where.none');
    return o;
  }
  function dataBox(cubes) {
    var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    cubes.forEach(function (c) { var f = c.inflate || 0; for (var i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], Math.min(c.from[i], c.to[i]) - f); hi[i] = Math.max(hi[i], Math.max(c.from[i], c.to[i]) + f); } });
    return lo[0] === Infinity ? null : [(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2];
  }
  /** {parent, centre (data coordinates), ground (true: standing on y = 0 at the middle of the world)} */
  function anchor(where) {
    if (where === 'sel') {
      var n = M.selNodes()[0];
      if (n) {
        if (M.isCube(n)) return { parent: n.parent && n.parent !== 'root' ? n.parent : 'root', centre: dataBox([n]) };
        return { parent: n, centre: dataBox(M.cubesOf([n])) || n.origin.slice() };
      }
    }
    if (where && where.indexOf('root:') === 0) {
      var g = rootGroup(where.slice(5));
      if (g) {
        var base = M.cubesOf([g]).filter(function (c) { return c.parent === g && M.isBase(c); });
        return { parent: g, centre: dataBox(base.length ? base : M.cubesOf([g])) || g.origin.slice() };
      }
    }
    return { parent: 'root', centre: null, ground: true };
  }
  /** the boxes to make (data coordinates): {boxes: [{from, to, colour}], parent, centre, size: [w, h, d] px, cells} */
  function plan(vox, o) {
    var v = voxelize(vox, o.model), s = Math.max(0.0625, Number(o.scale) || 1), an = anchor(o.where);
    var size = [v.nx * s, v.ny * s, v.nz * s];
    var base = an.centre ? [an.centre[0] - size[0] / 2, an.centre[1] - size[1] / 2, an.centre[2] - size[2] / 2] : [-size[0] / 2, 0, -size[2] / 2];
    var list = M.greedyBoxes(v.nx, v.ny, v.nz, function (x, y, z) { return v.cells[(y * v.nz + z) * v.nx + x]; });
    var boxes = list.map(function (b) {
      return {
        from: [M.snap(base[0] + b.x * s), M.snap(base[1] + b.y * s), M.snap(base[2] + b.z * s)],
        to: [M.snap(base[0] + (b.x + b.w) * s), M.snap(base[1] + (b.y + b.h) * s), M.snap(base[2] + (b.z + b.d) * s)], colour: b.k - 1
      };
    });
    var centre = [M.snap(base[0] + size[0] / 2), M.snap(base[1] + (an.centre ? size[1] / 2 : 0)), M.snap(base[2] + size[2] / 2)];
    return { boxes: boxes, parent: an.parent, centre: centre, size: size, cells: v.count, colours: v.colours };
  }
  function cleanName(s) {
    var t = String(s || '').replace(/\.vox$/i, '');
    t = t.normalize ? t.normalize('NFD').replace(/[\u0300-\u036f]/g, '') : t;
    return t.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 24) || 'modelo';
  }
  /** makes the cubes (one undo entry) -> {group, cubes} */
  function create(vox, o) {
    var p = plan(vox, o);
    if (!p.boxes.length) fail('empty');
    if (p.boxes.length > MAX_CUBES) throw new Error(T('vox.toomany', p.boxes.length, MAX_CUBES));
    var name = 'vox_' + cleanName(o.name), group = null;
    var made = M.edit(T('vox'), { outliner: true, elements: [], groups: [] }, function () {
      group = M.newGroup(name, p.centre, p.parent);
      return p.boxes.map(function (b, i) { return M.newCube({ name: name + '_' + (i + 1), from: b.from, to: b.to, origin: p.centre, colour: b.colour }, group); });
    }, function (cubes) { return { outliner: true, elements: cubes, groups: [group] }; });
    M.refresh();
    try { if (typeof G.unselectAllElements === 'function') G.unselectAllElements(); group.select(); } catch (e) { }
    return { group: group, cubes: made, plan: p };
  }

  // =================================================================== the window
  var dlg = null, lastError = null;
  function chainMatrix(parent) {
    var m = null, T3 = G.THREE, chain = [];
    for (var q = parent; q && q !== 'root'; q = q.parent) chain.unshift(q);
    chain.forEach(function (n) {
      var r = n.rotation, o = n.origin;
      if (!r || (!r[0] && !r[1] && !r[2])) return;
      var t = new T3.Matrix4().makeTranslation(o[0], o[1], o[2]).multiply(new T3.Matrix4().makeRotationFromEuler(new T3.Euler(r[0] * M.DEG, r[1] * M.DEG, r[2] * M.DEG, 'ZYX'))).multiply(new T3.Matrix4().makeTranslation(-o[0], -o[1], -o[2]));
      m = m ? m.multiply(t) : t;
    });
    return m;
  }
  function autoScale(vox) {
    var big = 0;
    vox.models.forEach(function (m) { big = Math.max(big, m.size[0], m.size[1], m.size[2]); });
    return big <= 32 ? 1 : big <= 64 ? 0.5 : big <= 128 ? 0.25 : 0.125;
  }
  function openWith(vox, fileName) {
    var models = {}, n = vox.models.length, total = 0;
    vox.models.forEach(function (m) { total += m.count; });
    if (n > 1) models.all = T('vox.model.all');
    vox.models.forEach(function (m, i) { models[String(i)] = T('vox.model.one', i + 1, m.size[0] + ' \u00d7 ' + m.size[1] + ' \u00d7 ' + m.size[2], m.count); });
    var where = M.selNodes().length ? 'sel' : rootGroup('body') ? 'root:body' : 'none';
    var info = T('vox.info', fileName, n, total) + (vox.defaultPalette ? ' ' + T('vox.info.palette') : '') + (vox.skipped ? ' ' + T('vox.info.skipped', vox.skipped) : '');
    var form = {
      model: { label: T('vox.f.model'), type: 'select', options: models, value: n > 1 ? 'all' : '0', condition: function () { return n > 1; } },
      scale: { label: T('vox.f.scale'), type: 'number', value: autoScale(vox), min: 0.0625, max: 4, step: 0.0625 },
      where: { label: T('vox.f.where'), type: 'select', options: whereOptions(), value: where }
    };
    dlg = new G.Dialog({
      id: 'cpmplus_model_vox', title: T('vox'), width: 480, buttons: [T('vox.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('vox.tip')) + '</p>', '<p class="cmp_vox_info">' + L.esc(info) + '</p>'],
      form: form,
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview();
        try { create(vox, { model: n > 1 ? f.model : '0', scale: f.scale, where: f.where, name: fileName }); }
        catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 4000); return false; }
        dlg = null;
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var p = null, err = null;
      try { p = plan(vox, { model: n > 1 ? f.model : '0', scale: f.scale, where: f.where }); } catch (e) { err = e; }
      if (!p) { M.clearPreview(); M.dialogNote(dlg, err ? err.message : '', M.allCubes().length, true); return; }
      var m = chainMatrix(p.parent), boxes = p.boxes.length <= MAX_PREVIEW ? p.boxes : [{ from: [p.centre[0] - p.size[0] / 2, p.centre[1] - p.size[1] / 2, p.centre[2] - p.size[2] / 2], to: [p.centre[0] + p.size[0] / 2, p.centre[1] + p.size[1] / 2, p.centre[2] + p.size[2] / 2] }];
      M.preview(boxes.map(function (b) { return { from: b.from, to: b.to, col: b.colour, matrix: m || undefined }; }), 0x4fc3f7, { opacity: 0.9 });
      var note = T('vox.count', p.boxes.length, p.cells, M.num(p.size[0]), M.num(p.size[1]), M.num(p.size[2]));
      if (p.boxes.length > MAX_CUBES) note += ' \u00b7 ' + T('vox.toomany', p.boxes.length, MAX_CUBES);
      M.dialogNote(dlg, note, M.allCubes().length + p.boxes.length, p.boxes.length > MAX_CUBES);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('vox preview failed', e); }
    return dlg;
  }
  /** reads a picked / dropped file: {name, content (ArrayBuffer)} -> the window, or a message why it cannot be read */
  function openFile(file) {
    lastError = null;
    if (!M.isCpm()) { G.Blockbench.showMessageBox({ title: T('vox'), message: T('vox.cpmonly') }); lastError = 'cpm'; return null; }
    var vox;
    try {
      if (!file || !file.content || typeof file.content === 'string') fail('magic');
      vox = parse(file.content);
    } catch (e) {
      lastError = e.code || 'error';
      G.Blockbench.showMessageBox({ title: T('vox'), message: T('vox.bad') + '\n' + (e.code ? e.message : T('vox.bad.trunc')) });
      return null;
    }
    return openWith(vox, String(file.name || 'modelo.vox').split(/[\\/]/).pop());
  }
  function pick() {
    G.Blockbench.import({ resource_id: 'cpmplus_vox', extensions: ['vox'], type: 'MagicaVoxel (.vox)', readtype: 'binary' }, function (files) {
      if (files && files[0]) openFile(files[0]);
    });
  }

  // =================================================================== File > Import
  function placeInImport(action) {
    var file = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.file, imp = null;
    if (!file || !Array.isArray(file.structure)) return;
    file.structure.forEach(function (x) { if (x && x.id === 'import') imp = x; });
    if (!imp || !Array.isArray(imp.children)) return;
    var kids = imp.children;
    for (var i = kids.length - 1; i >= 0; i--) if (kids[i] && kids[i] !== action && kids[i].id === action.id) kids.splice(i, 1);
    if (kids.indexOf(action) >= 0) return;
    var at = -1; kids.forEach(function (x, k) { if ((typeof x === 'string' ? x : x && x.id) === 'import_obj') at = k; });
    kids.splice(at >= 0 ? at + 1 : kids.length, 0, action);
    L.onUninstall(function () { var k = kids.indexOf(action); if (k >= 0) kids.splice(k, 1); });
  }
  var STYLE = 'cmp_vox_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_vox_info{font-weight:600;margin:4px 0 8px}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var action = M.register({ id: 'cpmplus_model_vox', sec: 'create', icon: 'view_in_ar', key: 'vox', spots: [], run: function () { pick(); } });
    placeInImport(action);
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.parse = parse; api.voxelize = voxelize; api.plan = plan; api.create = create; api.openFile = openFile; api.defaultPalette = defaultPalette;
  api.dialog = function () { return dlg; }; api.lastError = function () { return lastError; };
  api.limits = { bytes: MAX_BYTES, size: MAX_SIZE, voxels: MAX_VOXELS, cells: MAX_CELLS, cubes: MAX_CUBES };
  return api;
})
