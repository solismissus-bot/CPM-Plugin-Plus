/*
 * CPM Plugin+ beta33 "CPM+ Modelar" - texture space for parts:
 * - free space on the skin: what is taken = the UV areas of every textured cube + (CPM, skin layout) the areas of the
 *   vanilla player, base and 2nd layer, even when no cube uses them (the game draws them) + every painted pixel. The
 *   empty corners of a box template stay free.
 * - Duplicar com textura própria: the copy gets its own free area with the same pixels (box UV or per-face UV, HD
 *   textures too), so painting it never changes the original; one Ctrl+Z for parts and pixels; nothing changes when
 *   there is no room (with a message).
 * - Mapa da textura: free / taken / shared areas at a glance.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];

  // =================================================================== texture / UV units
  function textureOf(cube) {
    for (var i = 0; i < FACES.length; i++) {
      var f = cube.faces && cube.faces[FACES[i]]; if (!f || f.texture === false || f.texture === null || f.texture === undefined) continue;
      var t = typeof f.getTexture === 'function' ? f.getTexture() : null;
      if (t) return t;
    }
    return null;
  }
  function mainTexture() { return (G.Texture && (G.Texture.getDefault ? G.Texture.getDefault() : (G.Texture.selected || G.Texture.all[0]))) || null; }
  /** UV size of a texture and how many texture pixels one UV unit is */
  function uvInfo(tex) {
    var uvW = (tex && tex.uv_width) || (G.Project && G.Project.texture_width) || 16, uvH = (tex && tex.uv_height) || (G.Project && G.Project.texture_height) || 16;
    var w = (tex && tex.width) || uvW, h = (tex && (tex.display_height || tex.height)) || uvH;
    return { uvW: uvW, uvH: uvH, sx: w / uvW, sy: h / uvH, w: w, h: h };
  }
  /** UV rects ([x1, y1, x2, y2], UV units) of the textured faces of a cube that use tex */
  function faceRects(cube, tex) {
    var out = [];
    FACES.forEach(function (k) {
      var f = cube.faces && cube.faces[k]; if (!f || !f.uv || f.texture === false || f.texture === null) return;
      var t = typeof f.getTexture === 'function' ? f.getTexture() : null;
      if (tex && t && t !== tex) return;
      var x1 = Math.min(f.uv[0], f.uv[2]), x2 = Math.max(f.uv[0], f.uv[2]), y1 = Math.min(f.uv[1], f.uv[3]), y2 = Math.max(f.uv[1], f.uv[3]);
      if (x2 - x1 <= 0 || y2 - y1 <= 0) return;
      out.push({ face: k, r: [x1, y1, x2, y2] });
    });
    return out;
  }
  // the vanilla player on a 64 x 64 skin: [u, v, width, height, depth] (box UV templates), base + 2nd layer
  var VANILLA = [[0, 0, 8, 8, 8], [32, 0, 8, 8, 8], [16, 16, 8, 12, 4], [16, 32, 8, 12, 4], [40, 16, 4, 12, 4], [40, 32, 4, 12, 4],
    [32, 48, 4, 12, 4], [48, 48, 4, 12, 4], [0, 16, 4, 12, 4], [0, 32, 4, 12, 4], [16, 48, 4, 12, 4], [0, 48, 4, 12, 4]];
  /** the face rects of a box UV template at (u, v) for a box w (x) * h (y) * d (z): the two top corners stay out */
  function boxRects(u, v, w, h, d) {
    return [[u + d, v, u + d + w, v + d], [u + d + w, v, u + d + 2 * w, v + d], [u, v + d, u + 2 * d + 2 * w, v + d + h]];
  }

  // =================================================================== what is taken
  /**
   * occupancy grid (UV units, 1 = taken) of a texture: textured cube faces (+ counts: 2 = shared by two parts),
   * vanilla player areas (CPM skins), painted pixels. o.skip = cubes left out (the ones being moved).
   */
  function occupancy(tex, o) {
    o = o || {};
    var I = uvInfo(tex), W = Math.ceil(I.uvW), H = Math.ceil(I.uvH), occ = new Uint8Array(W * H), owners = new Uint16Array(W * H);
    function mark(r, owner) {
      var x1 = Math.max(0, Math.floor(r[0] + 1e-6)), y1 = Math.max(0, Math.floor(r[1] + 1e-6)), x2 = Math.min(W, Math.ceil(r[2] - 1e-6)), y2 = Math.min(H, Math.ceil(r[3] - 1e-6));
      for (var y = y1; y < y2; y++) for (var x = x1; x < x2; x++) { var i = y * W + x; occ[i] = 1; if (owner) owners[i] = Math.min(65535, owners[i] + 1); }
    }
    M.allCubes().forEach(function (c) { if (o.skip && o.skip.indexOf(c) >= 0) return; faceRects(c, tex).forEach(function (f) { mark(f.r, true); }); });
    if (M.isCpm() && o.vanilla !== false) {
      // an HD skin has the vanilla layout scaled up; a texture given more space (Mais espaço na textura) keeps the 64 x 64
      // skin in its top left: the body's base parts tell which (their UVs stay inside 64 x 64)
      var k = I.uvW / 64, maxUV = 0;
      if (typeof M.isBase === 'function') M.allCubes().forEach(function (c) { if (M.isBase(c)) faceRects(c, tex).forEach(function (f) { maxUV = Math.max(maxUV, f.r[2], f.r[3]); }); });
      if (maxUV > 0 && maxUV <= 64.01) k = 1;
      if (Math.abs(I.uvH / I.uvW - 1) < 1e-6 || Math.abs(I.uvH / I.uvW - 0.5) < 1e-6) VANILLA.forEach(function (b) {
        if (b[1] * k >= I.uvH) return;
        boxRects(b[0], b[1], b[2], b[3], b[4]).forEach(function (r) { mark([r[0] * k, r[1] * k, r[2] * k, r[3] * k], false); });
      });
    }
    if (o.pixels !== false) {
      var data = pixelsOf(tex);
      if (data) {
        for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) {
          if (occ[y * W + x]) continue;
          var px1 = Math.floor(x * I.sx), py1 = Math.floor(y * I.sy), px2 = Math.ceil((x + 1) * I.sx), py2 = Math.ceil((y + 1) * I.sy), hit = false;
          for (var py = py1; py < py2 && !hit; py++) for (var px = px1; px < px2; px++) { if (data.data[(py * data.width + px) * 4 + 3] > 0) { hit = true; break; } }
          if (hit) occ[y * W + x] = 1;
        }
      }
    }
    return { W: W, H: H, occ: occ, owners: owners, info: I };
  }
  function canvasOf(tex) { try { return typeof tex.getActiveCanvas === 'function' ? tex.getActiveCanvas().canvas : tex.canvas; } catch (e) { return tex && tex.canvas; } }
  function pixelsOf(tex) {
    var cv = canvasOf(tex); if (!cv || !cv.width) return null;
    try { return cv.getContext('2d').getImageData(0, 0, cv.width, cv.height); } catch (e) { return null; }
  }
  /** the first free w * h rect (UV units, whole units) top-left first; marks it taken; null = no room */
  function alloc(map, w, h) { return allocShape(map, [[0, 0, w, h]]); }
  /**
   * the first spot (top-left first) where every rect of a shape (relative [x1, y1, x2, y2], UV units) is free - a box
   * template only needs its FACES free (its empty corners may sit on something else, like the vanilla skin); marks the
   * rects taken; null = no room
   */
  function allocShape(map, rects) {
    var W = map.W, H = map.H, occ = map.occ;
    var cells = rects.map(function (r) { return [Math.floor(r[0] + 1e-6), Math.floor(r[1] + 1e-6), Math.ceil(r[2] - 1e-6), Math.ceil(r[3] - 1e-6)]; }).filter(function (c) { return c[2] > c[0] && c[3] > c[1]; });
    if (!cells.length) return [0, 0];
    var bw = 0, bh = 0; cells.forEach(function (c) { bw = Math.max(bw, c[2]); bh = Math.max(bh, c[3]); });
    // summed-area table: a rect is free when its sum is 0
    var S = new Int32Array((W + 1) * (H + 1));
    for (var y = 0; y < H; y++) { var row = 0; for (var x = 0; x < W; x++) { row += occ[y * W + x]; S[(y + 1) * (W + 1) + x + 1] = S[y * (W + 1) + x + 1] + row; } }
    function free(x1, y1, x2, y2) { return !(S[y2 * (W + 1) + x2] - S[y1 * (W + 1) + x2] - S[y2 * (W + 1) + x1] + S[y1 * (W + 1) + x1]); }
    for (var y0 = 0; y0 + bh <= H; y0++) for (var x0 = 0; x0 + bw <= W; x0++) {
      var ok = true;
      for (var i = 0; i < cells.length && ok; i++) { var c = cells[i]; ok = free(x0 + c[0], y0 + c[1], x0 + c[2], y0 + c[3]); }
      if (!ok) continue;
      cells.forEach(function (c) { for (var yy = y0 + c[1]; yy < y0 + c[3]; yy++) for (var xx = x0 + c[0]; xx < x0 + c[2]; xx++) occ[yy * W + xx] = 1; });
      return [x0, y0];
    }
    return null;
  }

  // =================================================================== giving parts their own texture area
  /**
   * plans new areas for cubes (copies of originals): box UV = the whole template moves; per-face UV = every face on
   * its own. Returns [{cube, src, moves: [{face|null, from: [x1, y1, x2, y2], to: [x, y]}]}] or throws 'no room'.
   */
  function planAreas(pairs, tex, map) {
    return pairs.map(function (p) {
      var rects = faceRects(p.src, tex);
      if (!rects.length) return { cube: p.cube, src: p.src, moves: [] };
      if (p.src.box_uv) {
        // the template starts at uv_offset; only its faces need room
        var u0 = p.src.uv_offset ? p.src.uv_offset[0] : 0, v0 = p.src.uv_offset ? p.src.uv_offset[1] : 0;
        var x1 = Infinity, y1 = Infinity; rects.forEach(function (f) { x1 = Math.min(x1, f.r[0]); y1 = Math.min(y1, f.r[1]); });
        u0 = Math.min(u0, x1); v0 = Math.min(v0, y1);
        var rel = rects.map(function (f) { return [f.r[0] - u0, f.r[1] - v0, f.r[2] - u0, f.r[3] - v0]; });
        var at = allocShape(map, rel);
        if (!at) throw new Error('noroom');
        var off = p.src.uv_offset ? [at[0] + (p.src.uv_offset[0] - u0), at[1] + (p.src.uv_offset[1] - v0)] : at;
        return { cube: p.cube, src: p.src, moves: rel.map(function (r, i) { return { face: null, box: true, from: rects[i].r, to: [at[0] + r[0], at[1] + r[1]], offset: off }; }) };
      }
      return { cube: p.cube, src: p.src, moves: rects.map(function (f) {
        var at = alloc(map, f.r[2] - f.r[0], f.r[3] - f.r[1]);
        if (!at) throw new Error('noroom');
        return { face: f.face, from: f.r, to: at };
      }) };
    });
  }
  /**
   * copies the pixels of every move and points the cubes at their new areas (inside an undo edit); returns the pixel
   * blocks it wrote {before, after} (for bindPixels)
   */
  function applyAreas(plans, tex) {
    var I = uvInfo(tex), ops = [];
    plans.forEach(function (p) {
      p.moves.forEach(function (m) {
        var sx = Math.round(m.from[0] * I.sx), sy = Math.round(m.from[1] * I.sy), w = Math.round((m.from[2] - m.from[0]) * I.sx), h = Math.round((m.from[3] - m.from[1]) * I.sy);
        if (w > 0 && h > 0) ops.push({ sx: sx, sy: sy, w: w, h: h, dx: Math.round(m.to[0] * I.sx), dy: Math.round(m.to[1] * I.sy) });
        if (m.box) { p.cube.uv_offset = [m.offset[0], m.offset[1]]; }
        else {
          var f = p.cube.faces[m.face], dw = m.from[2] - m.from[0], dh = m.from[3] - m.from[1];
          var flipX = f.uv[0] > f.uv[2], flipY = f.uv[1] > f.uv[3];
          f.uv = [flipX ? m.to[0] + dw : m.to[0], flipY ? m.to[1] + dh : m.to[1], flipX ? m.to[0] : m.to[0] + dw, flipY ? m.to[1] : m.to[1] + dh];
        }
      });
      try { if (p.cube.box_uv && typeof p.cube.updateUV === 'function') p.cube.updateUV(); } catch (e) { }
    });
    if (!ops.length) return { before: [], after: [] };
    var rects = ops.map(function (o) { return [o.dx, o.dy, o.w, o.h]; });
    var before = grabBlocks(tex, rects);
    writeBlocks(tex, ops);
    return { before: before, after: grabBlocks(tex, rects) };
  }
  /**
   * copies pixel blocks inside a texture (ops: {sx, sy, w, h, dx, dy} in texture pixels) the way the CPM+ brushes write
   * (Painter.edit: synchronous, the active layer, its offset) - every block is read before any is written
   */
  function writeBlocks(tex, ops) {
    var run = function (ctx, off) {
      var blocks = ops.map(function (o) { return { o: o, img: ctx.getImageData(o.sx - off[0], o.sy - off[1], o.w, o.h) }; });
      blocks.forEach(function (b) { ctx.putImageData(b.img, b.o.dx - off[0], b.o.dy - off[1]); });
    };
    if (G.Painter && typeof G.Painter.edit === 'function') {
      G.Painter.edit(tex, function () { run(G.Painter.current.ctx, G.Painter.current.offset || [0, 0]); }, { no_undo: true });
    } else { var cv = canvasOf(tex); if (cv) run(cv.getContext('2d'), [0, 0]); }
  }
  // ---- pixels in the undo: Blockbench keeps the texture in the undo step, but on a CPM skin its canvas does not come
  // back on Ctrl+Z (seen with Blockbench's own paint strokes too). Every modelling tool that writes pixels also keeps the
  // blocks it wrote (before / after) with its undo step and puts them back on undo / redo itself.
  var pixelSteps = new Map();
  function grabBlocks(tex, rects) {
    var out = [];
    var read = function (ctx, off) { rects.forEach(function (r) { out.push({ x: r[0], y: r[1], w: r[2], h: r[3], img: ctx.getImageData(r[0] - off[0], r[1] - off[1], r[2], r[3]) }); }); };
    var ac = typeof tex.getActiveCanvas === 'function' ? tex.getActiveCanvas() : { ctx: canvasOf(tex).getContext('2d'), offset: [0, 0] };
    read(ac.ctx, ac.offset || [0, 0]);
    return out;
  }
  function putBlocks(tex, blocks) {
    var run = function (ctx, off) { blocks.forEach(function (b) { ctx.putImageData(b.img, b.x - off[0], b.y - off[1]); }); };
    if (G.Painter && typeof G.Painter.edit === 'function') G.Painter.edit(tex, function () { run(G.Painter.current.ctx, G.Painter.current.offset || [0, 0]); }, { no_undo: true });
    else { var cv = canvasOf(tex); if (cv) run(cv.getContext('2d'), [0, 0]); }
  }
  /** after M.edit: ties the before / after pixel blocks to the undo step just made */
  function bindPixels(tex, before, after) {
    var H = G.Undo && G.Undo.history, entry = H && H[H.length - 1];
    if (entry && before.length) pixelSteps.set(entry, { tex: tex, before: before, after: after });
  }
  function onUndo(d) {
    var s = d && d.entry && sizeSteps.get(d.entry); if (s) try { restoreSize(s.tex, s.before); } catch (e) { L.warn('could not put the texture size back', e); }
    var p = d && d.entry && pixelSteps.get(d.entry); if (p) try { putBlocks(p.tex, p.before); } catch (e) { L.warn('could not put the pixels back', e); }
  }
  function onRedo(d) {
    var s = d && d.entry && sizeSteps.get(d.entry); if (s) try { restoreSize(s.tex, s.after); } catch (e) { L.warn('could not redo the texture size', e); }
    var p = d && d.entry && pixelSteps.get(d.entry); if (p) try { putBlocks(p.tex, p.after); } catch (e) { L.warn('could not redo the pixels', e); }
  }

  // =================================================================== Textura em HD
  // more texture pixels per UV unit: every pixel becomes k x k (nearest), the UV size stays, so the model looks exactly the
  // same and new paintings (HD feathers, brushes) get room for detail. CPM keeps the UV size and the picture apart (the
  // exporter writes provider.size = UV size + the full picture). Like the paint steps, the size change keeps its own
  // before / after copies with its undo step and puts them back itself.
  var sizeSteps = new Map();
  function copyCanvas(src) { var c = document.createElement('canvas'); c.width = src.width; c.height = src.height; c.getContext('2d').drawImage(src, 0, 0); return c; }
  /** the pixel surfaces of a texture: its pixel layers, or its own canvas */
  function surfaces(tex) {
    if (tex.layers_enabled && tex.layers && tex.layers.length) return tex.layers.filter(function (l) { return l && l.ctx && (!l.type || l.type === 'pixel_layer'); }).map(function (l) { return { ctx: l.ctx, layer: l }; });
    var cv = tex.canvas || canvasOf(tex);
    return cv ? [{ ctx: tex.ctx || cv.getContext('2d'), layer: null }] : [];
  }
  function snapshot(tex) {
    return { w: tex.width, h: tex.height, uvw: tex.uv_width, uvh: tex.uv_height, s: surfaces(tex).map(function (s) { return { c: copyCanvas(s.ctx.canvas), off: s.layer && s.layer.offset ? s.layer.offset.slice() : null }; }) };
  }
  function refreshTexture(tex) {
    try { if (typeof tex.updateChangesAfterEdit === 'function') tex.updateChangesAfterEdit(); } catch (e) { }
    try { if (G.UVEditor && G.UVEditor.vue && typeof G.UVEditor.vue.updateTexture === 'function') G.UVEditor.vue.updateTexture(); } catch (e) { }
    try { if (G.Canvas && typeof G.Canvas.updateAllFaces === 'function') G.Canvas.updateAllFaces(tex); } catch (e) { }
  }
  function restoreSize(tex, snap) {
    var run = function () {
      surfaces(tex).forEach(function (s, i) {
        var b = snap.s[i]; if (!b) return;
        s.ctx.canvas.width = b.c.width; s.ctx.canvas.height = b.c.height; s.ctx.imageSmoothingEnabled = false; s.ctx.drawImage(b.c, 0, 0);
        if (s.layer && b.off) s.layer.offset = b.off.slice();
      });
      tex.width = snap.w; tex.height = snap.h;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    if (snap.uvw) { tex.uv_width = snap.uvw; tex.uv_height = snap.uvh; }
    refreshTexture(tex);
  }
  /** how many texture pixels one UV unit is (the smaller of the two directions) */
  function density(tex) { var I = uvInfo(tex); return Math.min(I.sx, I.sy); }
  /**
   * Textura em HD: k texture pixels per UV unit (nothing changes when it already has that many) -> true when it changed.
   * One undo step.
   */
  function makeHD(tex, k) {
    tex = tex || mainTexture();
    if (!tex) throw new Error(T('texmap.none'));
    var f = k / density(tex);
    if (!(f > 1.0001)) return false;
    var W = Math.round(tex.width * f), H = Math.round(tex.height * f), before = snapshot(tex);
    G.Undo.initEdit({ textures: [tex], bitmap: true });
    var run = function () {
      surfaces(tex).forEach(function (s) {
        var c = copyCanvas(s.ctx.canvas);
        s.ctx.canvas.width = Math.round(c.width * f); s.ctx.canvas.height = Math.round(c.height * f);
        s.ctx.imageSmoothingEnabled = false; s.ctx.drawImage(c, 0, 0, s.ctx.canvas.width, s.ctx.canvas.height);
        if (s.layer && s.layer.offset) s.layer.offset = [Math.round(s.layer.offset[0] * f), Math.round(s.layer.offset[1] * f)];
      });
      tex.width = W; tex.height = H; tex.keep_size = true;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    G.Undo.finishEdit(T('hd.step'));
    var hist = G.Undo.history, entry = hist && hist[hist.length - 1];
    if (entry) sizeSteps.set(entry, { tex: tex, before: before, after: snapshot(tex) });
    refreshTexture(tex);
    return true;
  }
  /**
   * Mais espaço na textura: the texture's UV area k times wider and taller (a 64 x 64 skin becomes 128 x 128), every
   * pixel kept where it is (top left), so every UV in the model stays right and the new area is free space. The same
   * number of texture pixels per UV unit (HD stays HD). One undo step.
   */
  function growUV(tex, k) {
    tex = tex || mainTexture();
    if (!tex) throw new Error(T('texmap.none'));
    k = k || 2;
    var I = uvInfo(tex), before = snapshot(tex);
    G.Undo.initEdit({ textures: [tex], bitmap: true });
    var run = function () {
      surfaces(tex).forEach(function (s) {
        var c = copyCanvas(s.ctx.canvas);
        s.ctx.canvas.width = c.width * k; s.ctx.canvas.height = c.height * k;
        s.ctx.imageSmoothingEnabled = false; s.ctx.clearRect(0, 0, s.ctx.canvas.width, s.ctx.canvas.height); s.ctx.drawImage(c, 0, 0);
      });
      tex.width = Math.round(I.w * k); tex.height = Math.round(I.h * k); tex.keep_size = true;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    tex.uv_width = I.uvW * k; tex.uv_height = I.uvH * k;
    G.Undo.finishEdit(T('grow.step'));
    var hist = G.Undo.history, entry = hist && hist[hist.length - 1];
    if (entry) sizeSteps.set(entry, { tex: tex, before: before, after: snapshot(tex) });
    refreshTexture(tex);
    try { if (G.Canvas && typeof G.Canvas.updateAllUVs === 'function') G.Canvas.updateAllUVs(); } catch (e) { }
    return true;
  }
  /** the undo aspects that keep a texture's pixels (its active layer when it has layers) */
  function pixelAspects(tex) {
    if (!tex) return {};
    var layered = tex.layers_enabled && tex.layers && tex.layers[0] && typeof tex.getActiveLayer === 'function';
    return layered ? { layers: [tex.getActiveLayer()], bitmap: true } : { textures: [tex], bitmap: true };
  }
  /** Duplicar com textura própria: copies of the parts, each cube with its own copy of its texture area; one undo entry */
  function duplicateWithTexture(nodes) {
    nodes = nodes || M.selNodes();
    if (!nodes.length) throw new Error(T('need.sel'));
    var tex = null; M.cubesOf(nodes).some(function (c) { tex = textureOf(c); return !!tex; });
    tex = tex || mainTexture();
    var copies = [], newCubes = [], newGroups = [], px = { before: [], after: [] };
    // plan first (no change at all when there is no room)
    var srcCubes = M.cubesOf(nodes), map = tex ? occupancy(tex) : null, plans = null;
    if (tex) {
      try { plans = planAreas(srcCubes.map(function (c) { return { cube: null, src: c }; }), tex, map); }
      catch (e) { if (e.message === 'noroom') throw new Error(T('duptex.noroom')); throw e; }
    }
    // the texture's pixels are in the same undo step ('bitmap'; without it Blockbench only keeps its properties)
    M.edit(T('duptex'), Object.assign({ outliner: true, elements: [], groups: [] }, pixelAspects(tex)), function () {
      nodes.forEach(function (n) { var c = M.duplicateNode(n); copies.push(c); });
      newCubes = M.cubesOf(copies);
      copies.forEach(function (c) { if (M.isGroup(c)) M.groupsOf([c]).forEach(function (g) { newGroups.push(g); }); });
      // copies come out in the same order as the originals: pair them cube by cube
      if (plans) { plans.forEach(function (p, i) { p.cube = newCubes[i]; }); px = applyAreas(plans.filter(function (p) { return p.cube; }), tex); }
    }, function () { return Object.assign({ outliner: true, elements: newCubes, groups: newGroups }, pixelAspects(tex)); });
    if (tex) bindPixels(tex, px.before, px.after);
    M.refresh();
    try { if (tex && typeof tex.updateLayerChanges === 'function') tex.updateLayerChanges(true); } catch (e) { }
    M.selectNodes(copies);
    return { copies: copies, cubes: newCubes, texture: tex };
  }

  // =================================================================== Mapa da textura
  function mapStats(tex) {
    var m = occupancy(tex, { pixels: true }), free = 0, shared = 0;
    for (var i = 0; i < m.occ.length; i++) { if (!m.occ[i]) free++; if (m.owners[i] > 1) shared++; }
    return { map: m, free: free, shared: shared, total: m.occ.length };
  }
  var mdlg = null;
  function openMap() {
    var tex = mainTexture();
    if (!tex) { G.Blockbench.showQuickMessage(T('texmap.none'), 2500); return null; }
    var st = mapStats(tex), m = st.map, scale = Math.max(2, Math.floor(384 / Math.max(m.W, m.H)));
    var cv = document.createElement('canvas'); cv.width = m.W * scale; cv.height = m.H * scale; cv.className = 'cmp_texmap';
    var g = cv.getContext('2d');
    try { g.imageSmoothingEnabled = false; g.globalAlpha = 0.35; g.drawImage(canvasOf(tex), 0, 0, cv.width, cv.height); g.globalAlpha = 1; } catch (e) { }
    for (var y = 0; y < m.H; y++) for (var x = 0; x < m.W; x++) {
      var i = y * m.W + x;
      g.fillStyle = m.owners[i] > 1 ? 'rgba(229,57,53,0.75)' : m.occ[i] ? 'rgba(120,120,120,0.55)' : 'rgba(67,160,71,0.55)';
      g.fillRect(x * scale, y * scale, scale, scale);
    }
    mdlg = new G.Dialog({
      id: 'cpmplus_model_texmap', title: T('texmap'), width: Math.max(440, cv.width + 60), buttons: ['dialog.close'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('texmap.tip')) + '</p>',
        '<p class="cmp_count">' + L.esc(T('texmap.stats', Math.round(st.free / st.total * 100), st.free, st.shared)) + '</p>'],
      onConfirm: function () { mdlg = null; }, onCancel: function () { mdlg = null; }
    });
    mdlg.show();
    try { var host = mdlg.object.querySelector('.dialog_content') || mdlg.object; host.appendChild(cv); cv.style.cssText = 'display:block;margin:8px auto;image-rendering:pixelated;border:1px solid var(--color-border)'; } catch (e) { }
    return mdlg;
  }

  /** Textura em HD window: how many pixels per UV unit (only the steps above what the texture has now) */
  var hdlg = null;
  function openHD() {
    var tex = mainTexture();
    if (!tex) { G.Blockbench.showQuickMessage(T('texmap.none'), 2500); return null; }
    var d = density(tex), I = uvInfo(tex), opts = {}, first = null;
    [2, 4, 8, 16].forEach(function (k) { if (k > d + 1e-6) { opts[String(k)] = T('hd.opt', k, Math.round(I.uvW * k), Math.round(I.uvH * k)); if (!first) first = String(k); } });
    if (!first) { G.Blockbench.showQuickMessage(T('hd.already', Math.round(d)), 2500); return null; }
    hdlg = new G.Dialog({
      id: 'cpmplus_model_hd', title: T('hd'), width: 480,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('hd.tip', Math.round(tex.width), Math.round(tex.height), Math.round(I.uvW), Math.round(I.uvH))) + '</p>'],
      form: { k: { label: T('hd.k'), type: 'select', options: opts, value: opts['8'] ? '8' : first } },
      onConfirm: function (r) {
        hdlg = null;
        try { if (makeHD(tex, Number(r.k))) G.Blockbench.showQuickMessage(T('hd.done', Math.round(tex.width), Math.round(tex.height)), 2200); }
        catch (e) { G.Blockbench.showMessageBox({ title: T('hd'), message: e.message || String(e) }); }
      },
      onCancel: function () { hdlg = null; }
    });
    hdlg.show();
    return hdlg;
  }

  // =================================================================== registration
  api.install = function () {
    try {
      G.Blockbench.on('undo', onUndo); G.Blockbench.on('redo', onRedo);
      L.onUninstall(function () { G.Blockbench.removeListener('undo', onUndo); G.Blockbench.removeListener('redo', onRedo); pixelSteps.clear(); sizeSteps.clear(); });
    } catch (e) { }
    M.register({ id: 'cpmplus_model_duptex', sec: 'texture', icon: 'library_add', key: 'duptex', mode: 'edit', need: 'sel', spots: ['edit', 'cube', 'group'], run: function () {
      try { var r = duplicateWithTexture(); G.Blockbench.showQuickMessage(T('duptex.done', r.cubes.length), 1800); }
      catch (e) { G.Blockbench.showMessageBox({ title: T('duptex'), message: e.message || String(e) }); }
    } });
    M.register({ id: 'cpmplus_model_texmap', sec: 'texture', icon: 'grid_view', key: 'texmap', spots: ['textures'], run: function () { openMap(); } });
    M.register({ id: 'cpmplus_model_hd', sec: 'texture', icon: 'hd', key: 'hd', spots: ['textures'], after: { textures: 'cpmplus_model_texmap' }, run: function () { openHD(); } });
  };
  api.uninstall = function () {
    if (mdlg) { try { mdlg.close(); } catch (e) { } mdlg = null; }
    if (hdlg) { try { hdlg.close(); } catch (e) { } hdlg = null; }
  };
  // for the other modules (library, merge, slice, 2nd layer) and QA
  api.uvInfo = uvInfo; api.faceRects = faceRects; api.occupancy = occupancy; api.alloc = alloc; api.allocShape = allocShape; api.planAreas = planAreas; api.applyAreas = applyAreas;
  api.writeBlocks = writeBlocks; api.pixelAspects = pixelAspects; api.grabBlocks = grabBlocks; api.putBlocks = putBlocks; api.bindPixels = bindPixels;
  api.textureOf = textureOf; api.mainTexture = mainTexture; api.boxRects = boxRects; api.pixelsOf = pixelsOf; api.canvasOf = canvasOf;
  api.duplicateWithTexture = function (nodes) { return duplicateWithTexture(nodes); };
  api.mapStats = mapStats; api.openMap = openMap;
  api.makeHD = makeHD; api.density = density; api.openHD = openHD; api.growUV = growUV;
  return api;
})
