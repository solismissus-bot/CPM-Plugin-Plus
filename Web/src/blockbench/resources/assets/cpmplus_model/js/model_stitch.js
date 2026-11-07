/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Juntar texturas: every texture of the project goes into the one CPM skin texture.
 * CPM has one texture per sheet (skin, cape, elytra, armor): cubes painted with any other texture export with the skin's
 * pixels at the same UV (the export warns 'unknown texture'). Typical case: a model imported with 2-5 textures.
 * - what moves: the textures whose name is not a CPM sheet (the skin is found with the export's own rule); the sheets
 *   (cape, elytra, armor1/2) stay; textures no cube uses are removed;
 * - the used areas of each texture become islands (faces that touch or overlap; all faces of a box-UV cube together),
 *   each island is copied pixel for pixel into free skin space (the F11 occupancy: faces, vanilla areas, painted
 *   pixels) and its faces move by the same whole-unit shift: box-UV cubes stay box UV, shared areas stay shared;
 * - pixels are never resampled down: a texture with more pixels per UV unit than the skin makes the skin HD first (each
 *   skin pixel k x k, the model looks the same); a source with fewer is scaled up by whole pixels;
 * - no room: the skin grows (64 -> 128 UV, pixels kept top left) - the window says so and asks (a checkbox);
 * - one Ctrl+Z puts the textures, the cubes and the skin (pixels and size) back; the skin pixels are restored
 *   explicitly on undo / redo (the F11 rule: Blockbench's own texture undo does not bring a CPM skin canvas back).
 * The window shows how the skin will look. Loaded after model_texture.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var SHEETS = ['skin', 'list_icon', 'cape', 'elytra', 'armor1', 'armor2'];
  var EPS = 1e-6, MAX_PX = 4096, MAX_GROW = 4;
  function MT() { return L.core.modules.model_texture; }

  // =================================================================== textures
  function sheetOf(name) {
    var n = String(name || '').toLowerCase();
    for (var i = 0; i < SHEETS.length; i++) if (n === SHEETS[i] || n === SHEETS[i] + '.png') return SHEETS[i];
    return null;
  }
  function textures() { return (G.Texture && G.Texture.all ? G.Texture.all : []).slice(); }
  /** the texture the export uses as the skin sheet (RootUtil.findSkinTexture): 'skin', else the only one, else the first without a sheet name */
  function skinTexture() {
    var all = textures(), i;
    for (i = 0; i < all.length; i++) if (sheetOf(all[i].name) === 'skin') return all[i];
    if (all.length === 1) return all[0];
    for (i = 0; i < all.length; i++) if (!sheetOf(all[i].name)) return all[i];
    return null;
  }
  function uvW(t) { return typeof t.getUVWidth === 'function' ? t.getUVWidth() : (t.uv_width || G.Project.texture_width); }
  function uvH(t) { return typeof t.getUVHeight === 'function' ? t.getUVHeight() : (t.uv_height || G.Project.texture_height); }
  /** texture pixels per UV unit [x, y] */
  function dens(t) { return [t.width / uvW(t), (t.display_height || t.height) / uvH(t)]; }
  /** the smallest whole number n of units with n * d a whole number of pixels (0 = none up to 16) */
  function stepFor(d) { for (var n = 1; n <= 16; n++) if (Math.abs(n * d - Math.round(n * d)) < 1e-6) return n; return 0; }
  function whole(v) { return Math.abs(v - Math.round(v)) < 1e-6; }
  function rectOf(f) { var u = f.uv; return [Math.min(u[0], u[2]), Math.min(u[1], u[3]), Math.max(u[0], u[2]), Math.max(u[1], u[3])]; }
  function textured(f) { return !!f && f.texture !== false && f.texture !== null && f.texture !== undefined && !!f.uv; }

  // =================================================================== the plan
  /** faces (and their cubes) per texture: Map tex -> [{cube, face, f}] */
  function facesByTexture() {
    var out = new Map();
    M.allCubes().forEach(function (c) {
      FACES.forEach(function (k) {
        var f = c.faces && c.faces[k]; if (!textured(f)) return;
        var t = typeof f.getTexture === 'function' ? f.getTexture() : null; if (!t) return;
        if (!out.has(t)) out.set(t, []);
        out.get(t).push({ cube: c, face: k, f: f });
      });
    });
    return out;
  }
  function gcd(a, b) { while (b) { var t = b; b = a % b; a = t; } return a; }
  function lcm(a, b) { return a * b / gcd(a, b); }
  /**
   * islands: faces (items {cube, face, f, tex}) that touch / overlap on the same texture, and every painted face of a
   * box-UV cube that moves as a whole (its template, also its faces on the skin), together. The box of an island is in
   * whole steps of UV units that are whole pixels of every texture in it and of the skin (exact copies and shifts).
   * main = the moving texture most of its faces use (its whole box is copied; faces of other textures go on top).
   */
  function islandsOf(items, stepK, boxMove, skin) {
    var up = items.map(function (x, i) { return i; }), rects = items.map(function (x) { return rectOf(x.f); });
    function root(i) { while (up[i] !== i) { up[i] = up[up[i]]; i = up[i]; } return i; }
    function join(a, b) { var ra = root(a), rb = root(b); if (ra !== rb) up[ra] = rb; }
    for (var i = 0; i < items.length; i++) for (var j = i + 1; j < items.length; j++) {
      if (items[i].cube === items[j].cube && boxMove.get(items[i].cube)) { join(i, j); continue; }
      if (items[i].tex !== items[j].tex) continue;
      var a = rects[i], b = rects[j];
      if (a[0] <= b[2] + EPS && b[0] <= a[2] + EPS && a[1] <= b[3] + EPS && b[1] <= a[3] + EPS) join(i, j);
    }
    var groups = {}, order = [];
    items.forEach(function (x, i) { var r = root(i); if (!groups[r]) { groups[r] = []; order.push(r); } groups[r].push(i); });
    return order.map(function (r) {
      var idx = groups[r], lo = [Infinity, Infinity], hi = [-Infinity, -Infinity], n = stepK || 1, count = new Map(), main = null;
      idx.forEach(function (i) {
        var q = rects[i], t = items[i].tex;
        lo[0] = Math.min(lo[0], q[0]); lo[1] = Math.min(lo[1], q[1]); hi[0] = Math.max(hi[0], q[2]); hi[1] = Math.max(hi[1], q[3]);
        n = lcm(n, stepFor(dens(t)[0]));
        if (t !== skin) { count.set(t, (count.get(t) || 0) + 1); if (!main || count.get(t) > count.get(main)) main = t; }
      });
      var uv = [Math.floor(lo[0] / n + EPS) * n, Math.floor(lo[1] / n + EPS) * n, Math.ceil(hi[0] / n - EPS) * n, Math.ceil(hi[1] / n - EPS) * n];
      return { tex: main, uv: uv, w: uv[2] - uv[0], h: uv[3] - uv[1], items: idx.map(function (i) { return items[i]; }) };
    });
  }
  /** the occupancy map (UV units) embedded top left in a bigger one (the grown skin: the new area is free) */
  function embed(base, W, H) {
    var occ = new Uint8Array(W * H);
    for (var y = 0; y < base.H; y++) for (var x = 0; x < base.W; x++) occ[y * W + x] = base.occ[y * base.W + x];
    return { W: W, H: H, occ: occ };
  }
  /** the first free w * h spot (top left first) on a grid of 'step' units; marks it taken; null = no room */
  function allocAligned(map, w, h, step) {
    var W = map.W, H = map.H, occ = map.occ;
    if (w > W || h > H) return null;
    if (w <= 0 || h <= 0) return [0, 0];
    var S = new Int32Array((W + 1) * (H + 1));
    for (var y = 0; y < H; y++) { var row = 0; for (var x = 0; x < W; x++) { row += occ[y * W + x]; S[(y + 1) * (W + 1) + x + 1] = S[y * (W + 1) + x + 1] + row; } }
    for (var y0 = 0; y0 + h <= H; y0 += step) for (var x0 = 0; x0 + w <= W; x0 += step) {
      if (S[(y0 + h) * (W + 1) + x0 + w] - S[y0 * (W + 1) + x0 + w] - S[(y0 + h) * (W + 1) + x0] + S[y0 * (W + 1) + x0]) continue;
      for (var yy = y0; yy < y0 + h; yy++) for (var xx = x0; xx < x0 + w; xx++) occ[yy * W + xx] = 1;
      return [x0, y0];
    }
    return null;
  }
  /**
   * -> {skin, used (textures that move), unused (removed), keep (CPM sheets that stay), bad: {anim|odd: [names]}, islands
   * [{tex, uv, w, h, k (skin pixels per source pixel), at (skin UV), items}], m (HD factor of the skin), D (skin pixels per
   * UV unit after), grow (doublings), fits, cubes (touched), uv/px before / after} or {why}
   */
  function plan() {
    var skin = skinTexture(), all = textures();
    if (!skin) return { why: 'noskin' };
    var srcs = all.filter(function (t) { return t !== skin && !sheetOf(t.name); });
    var keep = all.filter(function (t) { return t !== skin && sheetOf(t.name); });
    if (!srcs.length) return { why: 'one', skin: skin, keep: keep };
    var byTex = facesByTexture(), used = [], unused = [], bad = {};
    srcs.forEach(function (t) {
      var list = byTex.get(t) || [];
      if (!list.length) { unused.push(t); return; }
      var d = dens(t), why = null;
      if (t.frameCount > 1) why = 'anim';
      else if (Math.abs(d[0] - d[1]) > 1e-6 || !stepFor(d[0])) why = 'odd';
      // lang: stitch.why.{anim|odd}
      if (why) { (bad[why] = bad[why] || []).push(t.name); return; }
      used.push(t);
    });
    var dk = dens(skin);
    if (Math.abs(dk[0] - dk[1]) > 1e-6 || !stepFor(dk[0])) return { why: 'skinodd', skin: skin };
    // skin density after: dk * m, the first m where every source pixel is a whole number of skin pixels
    var m = 0;
    for (var mm = 1; mm <= 16 && !m; mm++) {
      var Dm = dk[0] * mm;
      if (stepFor(Dm) && used.every(function (t) { var q = Dm / dens(t)[0]; return q >= 1 - 1e-6 && whole(q); })) m = mm;
    }
    if (!m) return { why: 'density', skin: skin };
    var D = dk[0] * m, stepK = stepFor(D), moving = new Set(used), items = [], boxMove = new Map();
    used.forEach(function (t) { byTex.get(t).forEach(function (x) { items.push({ cube: x.cube, face: x.face, f: x.f, tex: t }); }); });
    // a box-UV cube with a face on a moving texture moves as a whole (stays box UV): its faces on the skin come along;
    // with a face on a texture that stays apart (a CPM sheet, a refused texture) it becomes per-face instead
    items.forEach(function (x) {
      var c = x.cube; if (!c.box_uv || boxMove.has(c)) return;
      boxMove.set(c, FACES.every(function (k) { var f = c.faces[k]; if (!textured(f)) return true; var t = f.getTexture(); return t === skin || moving.has(t); }));
    });
    boxMove.forEach(function (ok, c) {
      if (ok) FACES.forEach(function (k) { var f = c.faces[k]; if (textured(f) && f.getTexture() === skin) items.push({ cube: c, face: k, f: f, tex: skin }); });
    });
    var islands = islandsOf(items, stepK, boxMove, skin);
    islands.forEach(function (is) { is.k = Math.round(D / dens(is.tex)[0]); });
    // big first: fewer holes
    var order = islands.slice().sort(function (a, b) { return (b.h - a.h) || (b.w - a.w); });
    var base = MT().occupancy(skin), grow = 0, fits = false, tried = 0;
    for (var g = 0; g <= MAX_GROW && !fits; g++) {
      var W = base.W << g, H = base.H << g;
      if (g && (W * D > MAX_PX || H * D > MAX_PX)) break;
      tried = g;
      var map = embed(base, W, H), ok = true;
      for (var i = 0; i < order.length && ok; i++) { var at = allocAligned(map, Math.ceil(order[i].w), Math.ceil(order[i].h), stepK); if (at) order[i].at = at; else ok = false; }
      if (ok) { fits = true; grow = g; }
    }
    if (!fits) { grow = tried; islands.forEach(function (is) { is.at = null; }); }
    var cubes = [];
    islands.forEach(function (is) { is.items.forEach(function (x) { if (cubes.indexOf(x.cube) < 0) cubes.push(x.cube); }); });
    var f = 1 << grow;
    return {
      skin: skin, used: used, unused: unused, keep: keep, bad: bad, islands: islands, m: m, D: D, grow: grow, fits: fits, cubes: cubes, boxMove: boxMove,
      uvBefore: [uvW(skin), uvH(skin)], uvAfter: [uvW(skin) * f, uvH(skin) * f], pxBefore: [skin.width, skin.height], pxAfter: [Math.round(uvW(skin) * f * D), Math.round(uvH(skin) * f * D)]
    };
  }

  // =================================================================== pixels
  function copyCanvas(src) { var c = document.createElement('canvas'); c.width = src.width; c.height = src.height; var g = c.getContext('2d'); g.imageSmoothingEnabled = false; g.drawImage(src, 0, 0); return c; }
  /** the pixel surfaces of a texture: its pixel layers, or its own canvas */
  function surfaces(tex) {
    if (tex.layers_enabled && tex.layers && tex.layers.length) return tex.layers.filter(function (l) { return l && l.ctx && (!l.type || l.type === 'pixel_layer'); }).map(function (l) { return { ctx: l.ctx, layer: l }; });
    var cv = tex.canvas;
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
  function restore(tex, snap) {
    var run = function () {
      surfaces(tex).forEach(function (s, i) {
        var b = snap.s[i]; if (!b) return;
        s.ctx.canvas.width = b.c.width; s.ctx.canvas.height = b.c.height; s.ctx.imageSmoothingEnabled = false; s.ctx.drawImage(b.c, 0, 0);
        if (s.layer && b.off) s.layer.offset = b.off.slice();
      });
      tex.width = snap.w; tex.height = snap.h;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    tex.uv_width = snap.uvw; tex.uv_height = snap.uvh;
    refreshTexture(tex);
  }
  /** every skin pixel becomes m x m (the UV size stays: the model looks the same) */
  function scaleSkin(tex, m) {
    var W = Math.round(tex.width * m), H = Math.round(tex.height * m);
    var run = function () {
      surfaces(tex).forEach(function (s) {
        var c = copyCanvas(s.ctx.canvas);
        s.ctx.canvas.width = Math.round(c.width * m); s.ctx.canvas.height = Math.round(c.height * m);
        s.ctx.imageSmoothingEnabled = false; s.ctx.drawImage(c, 0, 0, s.ctx.canvas.width, s.ctx.canvas.height);
        if (s.layer && s.layer.offset) s.layer.offset = [Math.round(s.layer.offset[0] * m), Math.round(s.layer.offset[1] * m)];
      });
      tex.width = W; tex.height = H; tex.keep_size = true;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
  }
  /** the UV area f times wider and taller, every pixel kept where it is (top left): the new area is free space */
  function growSkin(tex, f) {
    var uw = uvW(tex), uh = uvH(tex), W = Math.round(tex.width * f), H = Math.round(tex.height * f);
    var run = function () {
      surfaces(tex).forEach(function (s) {
        var c = copyCanvas(s.ctx.canvas);
        s.ctx.canvas.width = c.width * f; s.ctx.canvas.height = c.height * f;
        s.ctx.imageSmoothingEnabled = false; s.ctx.clearRect(0, 0, s.ctx.canvas.width, s.ctx.canvas.height); s.ctx.drawImage(c, 0, 0);
      });
      tex.width = W; tex.height = H; tex.keep_size = true;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    tex.uv_width = uw * f; tex.uv_height = uh * f;
  }
  /** the pixels of a source texture (what it shows: its own canvas, else its image), x / y / w / h in its pixels */
  function readSource(tex, x, y, w, h) {
    var src = tex.canvas && tex.canvas.width ? tex.canvas : tex.img, c = document.createElement('canvas');
    c.width = w; c.height = h;
    var g = c.getContext('2d'); g.imageSmoothingEnabled = false;
    if (src) g.drawImage(src, -x, -y);
    return g.getImageData(0, 0, w, h);
  }
  /** each pixel k x k (nearest) */
  function scaled(img, k) {
    if (k === 1) return img;
    var out = new ImageData(img.width * k, img.height * k), s = img.data, d = out.data;
    for (var y = 0; y < out.height; y++) for (var x = 0; x < out.width; x++) {
      var si = ((Math.floor(y / k) * img.width) + Math.floor(x / k)) * 4, di = (y * out.width + x) * 4;
      d[di] = s[si]; d[di + 1] = s[si + 1]; d[di + 2] = s[si + 2]; d[di + 3] = s[si + 3];
    }
    return out;
  }
  /** img into dst at (x, y), clipped */
  function blit(dst, img, x, y) {
    for (var yy = 0; yy < img.height; yy++) {
      var ty = y + yy; if (ty < 0 || ty >= dst.height) continue;
      for (var xx = 0; xx < img.width; xx++) {
        var tx = x + xx; if (tx < 0 || tx >= dst.width) continue;
        var s = (yy * img.width + xx) * 4, d = (ty * dst.width + tx) * 4;
        dst.data[d] = img.data[s]; dst.data[d + 1] = img.data[s + 1]; dst.data[d + 2] = img.data[s + 2]; dst.data[d + 3] = img.data[s + 3];
      }
    }
  }
  /**
   * the pixel blocks of the islands, scaled to the skin, at their spots (skin pixels): the island's box from its main
   * texture, then the faces it has on other textures (a box template's faces on the skin) with their own pixels. Every
   * block is read before any is written. Densities are read live (the skin may be HD already: then k = 1 for it).
   */
  function blocks(p) {
    return p.islands.filter(function (is) { return is.w > 0 && is.h > 0 && is.at; }).map(function (is) {
      var dm = dens(is.tex)[0], km = Math.round(p.D / dm);
      var img = scaled(readSource(is.tex, Math.round(is.uv[0] * dm), Math.round(is.uv[1] * dm), Math.round(is.w * dm), Math.round(is.h * dm)), km);
      is.items.forEach(function (x) {
        if (x.tex === is.tex) return;
        var d = dens(x.tex)[0], k = Math.round(p.D / d), r = rectOf(x.f);
        var px = [Math.floor(r[0] * d + EPS), Math.floor(r[1] * d + EPS), Math.ceil(r[2] * d - EPS), Math.ceil(r[3] * d - EPS)];
        if (px[2] <= px[0] || px[3] <= px[1]) return;
        var part = scaled(readSource(x.tex, px[0], px[1], px[2] - px[0], px[3] - px[1]), k);
        blit(img, part, Math.round((px[0] / d - is.uv[0]) * p.D), Math.round((px[1] / d - is.uv[1]) * p.D));
      });
      return { img: img, x: Math.round(is.at[0] * p.D), y: Math.round(is.at[1] * p.D) };
    });
  }
  function writeBlocks(tex, list) {
    if (!list.length) return;
    var run = function (ctx, off) { list.forEach(function (b) { ctx.putImageData(b.img, b.x - off[0], b.y - off[1]); }); };
    if (G.Painter && typeof G.Painter.edit === 'function') G.Painter.edit(tex, function () { run(G.Painter.current.ctx, G.Painter.current.offset || [0, 0]); }, { no_undo: true });
    else run(tex.canvas.getContext('2d'), [0, 0]);
  }

  // =================================================================== applying (one undo entry)
  var steps = new Map();
  function remap(p) {
    var skin = p.skin, done = [];
    p.islands.forEach(function (is) {
      var sh = is.at ? [is.at[0] - is.uv[0], is.at[1] - is.uv[1]] : [0, 0];
      is.items.forEach(function (x) {
        var c = x.cube, f = x.f;
        // a box template moves as a whole: every face of the cube is in this island (decided in the plan)
        if (c.box_uv && p.boxMove.get(c)) {
          if (done.indexOf(c) < 0) {
            done.push(c);
            c.uv_offset = [c.uv_offset[0] + sh[0], c.uv_offset[1] + sh[1]];
            FACES.forEach(function (k) { var g = c.faces[k]; if (g && g.uv) g.uv = [g.uv[0] + sh[0], g.uv[1] + sh[1], g.uv[2] + sh[0], g.uv[3] + sh[1]]; if (textured(g)) g.texture = skin.uuid; });
          }
          return;
        }
        // a box-UV cube with a face on a texture that stays apart (a CPM sheet): per-face UV (the faces keep their rects)
        if (c.box_uv) { c.box_uv = false; if (c.autouv !== undefined) c.autouv = 0; }
        f.uv = [f.uv[0] + sh[0], f.uv[1] + sh[1], f.uv[2] + sh[0], f.uv[3] + sh[1]];
        f.texture = skin.uuid;
      });
    });
  }
  function apply(p) {
    if (!p || !p.skin || !p.fits) return null;
    var skin = p.skin, gone = p.used.concat(p.unused), before = snapshot(skin), after = null;
    var aspects = { elements: p.cubes.slice(), textures: [skin].concat(gone), bitmap: true };
    M.edit(T('stitch'), aspects, function () {
      if (p.m > 1) scaleSkin(skin, p.m);
      if (p.grow) growSkin(skin, 1 << p.grow);
      writeBlocks(skin, blocks(p));
      remap(p);
      gone.forEach(function (t) { t.remove(true); });
      refreshTexture(skin);
      after = snapshot(skin);
    }, function () { return { elements: p.cubes.slice(), textures: [skin], bitmap: true }; });
    var H = G.Undo && G.Undo.history, entry = H && H[H.length - 1];
    if (entry) steps.set(entry, { tex: skin, before: before, after: after });
    try { if (typeof skin.select === 'function') skin.select(); } catch (e) { }
    try { G.Canvas.updateAllFaces(); } catch (e) { }
    try { if (G.Canvas.updateAllUVs) G.Canvas.updateAllUVs(); } catch (e) { }
    try { if (G.TextureAnimator && G.TextureAnimator.updateButton) G.TextureAnimator.updateButton(); } catch (e) { }
    try { if (G.BARS && G.BARS.updateConditions) G.BARS.updateConditions(); } catch (e) { }
    M.refresh();
    return { cubes: p.cubes.length, islands: p.islands.length, removed: gone.length };
  }
  function onUndo(d) { var s = d && d.entry && steps.get(d.entry); if (s) try { restore(s.tex, s.before); } catch (e) { L.warn('could not put the skin back', e); } }
  function onRedo(d) { var s = d && d.entry && steps.get(d.entry); if (s) try { restore(s.tex, s.after); } catch (e) { L.warn('could not redo the skin', e); } }

  // =================================================================== the window
  /** how the skin will look (a canvas, its real pixels scaled to fit) */
  function picture(p) {
    var W = p.pxAfter[0], H = p.pxAfter[1], cv = document.createElement('canvas');
    cv.width = W; cv.height = H;
    var g = cv.getContext('2d'); g.imageSmoothingEnabled = false;
    try { g.drawImage(p.skin.canvas && p.skin.canvas.width ? p.skin.canvas : p.skin.img, 0, 0, p.pxBefore[0] * p.m, p.pxBefore[1] * p.m); } catch (e) { }
    try { blocks(p).forEach(function (b) { g.putImageData(b.img, b.x, b.y); }); } catch (e) { }
    var scale = Math.min(4, 360 / Math.max(W, H)), out = document.createElement('canvas');
    out.width = Math.max(1, Math.round(W * scale)); out.height = Math.max(1, Math.round(H * scale)); out.className = 'cmp_stitch_pic';
    var o = out.getContext('2d'); o.imageSmoothingEnabled = scale < 1;
    o.drawImage(cv, 0, 0, out.width, out.height);
    // the new areas framed
    o.strokeStyle = 'rgba(255,193,7,.9)'; o.lineWidth = 1;
    p.islands.forEach(function (is) { if (is.at && is.w > 0 && is.h > 0) o.strokeRect(Math.round(is.at[0] * p.D * scale) + 0.5, Math.round(is.at[1] * p.D * scale) + 0.5, Math.max(1, Math.round(is.w * p.D * scale) - 1), Math.max(1, Math.round(is.h * p.D * scale) - 1)); });
    return out;
  }
  function names(list) { return list.slice(0, 5).join(', ') + (list.length > 5 ? ' (+' + (list.length - 5) + ')' : ''); }
  var dlg = null;
  function open() {
    var p = plan();
    if (p.why) {
      // lang: stitch.why.{noskin|one|skinodd|density}
      G.Blockbench.showQuickMessage(T('stitch.why.' + p.why), 3000);
      return null;
    }
    var needGrow = p.grow > 0, lines = [], nothing = !p.islands.length && !p.unused.length;
    lines.push('<p class="cmp_dlg_tip">' + L.esc(T('stitch.tip')) + '</p>');
    lines.push('<p class="cmp_stitch_big">' + L.esc(nothing ? T('stitch.nothing') : p.islands.length ? T('stitch.sum', p.used.length + p.unused.length + 1, p.islands.length, p.cubes.length) : T('stitch.onlyunused')) + '</p>');
    if (p.islands.length && p.fits) lines.push('<p class="cmp_stitch_note">' + L.esc(T('stitch.result', Math.round(p.uvAfter[0]), Math.round(p.uvAfter[1]), p.pxAfter[0], p.pxAfter[1])) + '</p>');
    if (p.m > 1) lines.push('<p class="cmp_stitch_note">' + L.esc(T('stitch.hd', p.m)) + '</p>');
    if (p.unused.length) lines.push('<p class="cmp_stitch_note">' + L.esc(T('stitch.unused', names(p.unused.map(function (t) { return t.name; })))) + '</p>');
    if (p.keep.length) lines.push('<p class="cmp_stitch_note">' + L.esc(T('stitch.keep', names(p.keep.map(function (t) { return t.name; })))) + '</p>');
    Object.keys(p.bad).forEach(function (k) { lines.push('<p class="cmp_stitch_note">' + L.esc(T('stitch.why.' + k, names(p.bad[k]))) + '</p>'); });
    if (!p.fits) lines.push('<p class="cmp_stitch_note warn">' + L.esc(T('stitch.nofit', p.pxAfter[0], p.pxAfter[1])) + '</p>');
    lines.push('<div class="cmp_stitch_picbox"></div>');
    var form = {};
    if (needGrow && p.fits) form.grow = { label: T('stitch.f.grow', Math.round(p.uvBefore[0]), Math.round(p.uvBefore[1]), Math.round(p.uvAfter[0]), Math.round(p.uvAfter[1])), description: T('stitch.f.grow.desc'), type: 'checkbox', value: true };
    dlg = new G.Dialog({
      id: 'cpmplus_model_stitch', title: T('stitch'), width: 520, buttons: [T('stitch.go'), 'dialog.cancel'],
      lines: lines, form: form,
      onConfirm: function (f) {
        dlg = null;
        if (nothing) return;
        if (!p.fits) { G.Blockbench.showQuickMessage(T('stitch.nofit', p.pxAfter[0], p.pxAfter[1]), 3000); return; }
        if (needGrow && f && f.grow === false) { G.Blockbench.showQuickMessage(T('stitch.nogrow'), 3000); return; }
        try { var r = apply(plan()); if (r) G.Blockbench.showQuickMessage(T('stitch.done', r.removed + 1), 2500); }
        catch (e) { L.warn('stitch failed', e); G.Blockbench.showMessageBox({ title: T('stitch'), message: T('error') + '\n' + (e && e.message ? e.message : String(e)) }); }
      },
      onCancel: function () { dlg = null; }
    });
    dlg.show();
    try {
      var box = dlg.object.querySelector('.cmp_stitch_picbox');
      if (box && (p.islands.length || p.m > 1)) { var pc = picture(p); box.appendChild(document.createTextNode(T('stitch.pic'))); box.appendChild(pc); }
    } catch (e) { L.warn('stitch picture failed', e); }
    return dlg;
  }

  // =================================================================== registration
  var STYLE = 'cmp_stitch_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_stitch_big{font-size:1.1em;font-weight:600;margin:6px 0}\n.cmp_stitch_note{opacity:.85;margin:2px 0}\n.cmp_stitch_note.warn{color:var(--color-warning, #e8a33d);opacity:1}' +
        '\n.cmp_stitch_picbox{margin:8px 0 0;opacity:.9;font-size:.92em}\n.cmp_stitch_pic{display:block;margin:4px auto 0;image-rendering:pixelated;border:1px solid var(--color-border);background:repeating-conic-gradient(#3a3a3a 0% 25%, #2a2a2a 0% 50%) 50%/16px 16px}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    try {
      G.Blockbench.on('undo', onUndo); G.Blockbench.on('redo', onRedo);
      L.onUninstall(function () { G.Blockbench.removeListener('undo', onUndo); G.Blockbench.removeListener('redo', onRedo); steps.clear(); });
    } catch (e) { }
    var action = M.register({ id: 'cpmplus_model_stitch', sec: 'texture', icon: 'auto_awesome_mosaic', key: 'stitch', mode: 'edit', cpm: true, spots: ['textures'], after: { textures: 'cpmplus_model_hd' }, run: function () { open(); } });
    // also where people right-click a texture
    try {
      var menu = G.Texture && G.Texture.prototype.menu, s = menu && menu.structure;
      if (Array.isArray(s) && s.indexOf(action) < 0) {
        var sep = typeof G.MenuSeparator === 'function' ? new G.MenuSeparator('cpmplus_model_tex', T('menu.section')) : '_';
        s.push(sep, action);
        L.onUninstall(function () { [sep, action].forEach(function (x) { var k = s.indexOf(x); if (k >= 0) s.splice(k, 1); }); });
      }
    } catch (e) { L.warn('could not add Juntar texturas to the texture menu', e); }
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } };
  // QA / other modules
  api.plan = plan; api.apply = apply; api.skinTexture = skinTexture; api.sheetOf = sheetOf; api.open = open; api.dialog = function () { return dlg; };
  api.stitch = function () { return apply(plan()); };
  return api;
})
