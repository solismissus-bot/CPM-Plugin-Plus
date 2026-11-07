/*
 * CPM Plugin+ beta33 "CPM+ Modelar" - Biblioteca de pecas (parts library):
 * - ready-made parts made of CPM colour cubes (they take no skin space), each one fitted to the body part it belongs
 *   to: cat ears, bunny ears, horns, halo, crown, top hat, witch hat, glasses, backpack, bat wings, cat tail (a chain of
 *   groups, ready to animate)
 * - your own parts: 'Salvar na biblioteca' keeps the selection (cubes, groups, rotations, colours, glow, the painting
 *   of textured cubes, the body part each piece sits on). Put on any model, a part lands on the same body part, its
 *   painted cubes get free skin space with their pixels (HD textures scaled), 'Espelhar' puts it on the other side.
 *   Kept in this Blockbench (IndexedDB); export / import a file to share them.
 * - the window: a card with a picture of every part, a 3D preview on the model of the one picked, where it goes;
 *   one Ctrl+Z takes a placed part away (pixels too).
 * Loaded after the texture module (model_texture) and the shapes module (model_shapes).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  function X() { return L.core.modules.model_texture; }
  function S() { return L.core.modules.model_shapes; }

  // =================================================================== ready-made parts
  // Coordinates are relative to the pivot of the body part (a CPM model: head and body pivot at 0, 24, 0; from there
  // the head box is x -4..4, y 0..8, z -4..4 and the body box x -4..4, y -12..0, z -2..2). The face looks at -Z, +X is
  // the player's right. Nodes: cube {c, n, f (from), t (to), o (pivot), r (rotation), col, glow, inf}; group {g, n, o,
  // r, kids, mirror (name of a copy on the other side)}; shape {s (Formas prontas options), at (centre), n, col, glow}.
  // Names '@key' / '@key_2' come from the lang (lib.n.key).
  function C(n, f, t, col, x) { return Object.assign({ c: 1, n: n, f: f, t: t, col: col }, x || {}); }
  var GINGER = 0xe08a3c, GINGER_D = 0xc26c25, PINK = 0xf48fb1, WHITE = 0xf4f4f4, GOLD = 0xf2c335, GOLD_L = 0xf9d65e, HAT = 0x4a3369;
  /** a cat tail: five segments, each a group (pivot at its start) inside the one before - bend it like bones */
  function catTail() {
    var seg = null;
    for (var i = 4; i >= 0; i--) {
      var z0 = 2 + 2.5 * i, s = { g: 1, n: '@tail_' + (i + 1), o: [0, -10, z0], r: [i ? -18 : 25, 0, 0],
        kids: [C('@tail_' + (i + 1), [-0.5, -10.5, z0], [0.5, -9.5, z0 + 2.6], i % 2 ? GINGER_D : GINGER)] };
      if (seg) s.kids.push(seg);
      seg = s;
    }
    return seg;
  }
  function lerpCol(a, b, f) {
    var r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f), g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
    var bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
    return (r << 16) | (g << 8) | bl;
  }
  /**
   * a curved horn: a chain of segments, each a group turned a little more than the one before (a smooth bend), getting
   * thinner towards the tip; every segment reaches down into the one before so no gap opens at the joints.
   * o = {n segments, len (first segment), w0 / w1 (thickness at base / tip), start (turn of the first joint), bend (turn
   * of every next joint), at (centre of the base, on the head), cols [base, tip], rings (every other segment a bit
   * darker), name, mirror}
   */
  function curvedHorn(o) {
    var top = null, up = null, y = o.at[1];
    for (var i = 0; i < o.n; i++) {
      var f = i / Math.max(1, o.n - 1), w = M.snap(o.w0 + (o.w1 - o.w0) * f), h = w / 2, len = M.snap(o.len * (1 - (o.taper || 0.3) * f));
      var col = lerpCol(o.cols[0], o.cols[1], f);
      if (o.rings && i % 2) col = lerpCol(col, 0x000000, 0.12);
      var sink = i ? Math.min(h * 0.9, len * 0.45) : 0.6;
      var g = { g: 1, n: i ? '@seg_' + i : o.name, o: [o.at[0], M.snap(y), o.at[2]], r: (i ? o.bend : o.start).slice(),
        kids: [C(i ? '@seg_' + i : '@base', [M.snap(o.at[0] - h), M.snap(y - sink), M.snap(o.at[2] - h)], [M.snap(o.at[0] + h), M.snap(y + len), M.snap(o.at[2] + h)], col)] };
      if (i) up.kids.push(g); else top = g;
      up = g; y += len;
    }
    if (o.mirror) top.mirror = o.mirror;
    return top;
  }
  // three kinds of horns (the right one; the left is its mirror)
  function demonHorns() { return [curvedHorn({ name: '@horn_r', mirror: '@horn_l', at: [2.6, 7.6, -1.6], n: 10, len: 1.05, w0: 1.9, w1: 0.3, start: [-6, 0, -38], bend: [3, 0, 9.5], cols: [0x2a1b1b, 0x8e2b22] })]; }
  function ramHorns() { return [curvedHorn({ name: '@horn_r', mirror: '@horn_l', at: [3.1, 7.4, -0.2], n: 13, len: 1.35, w0: 2.3, w1: 0.55, taper: 0.45, start: [-12, 0, -24], bend: [27, 0, -2.5], cols: [0xd9cfb6, 0x8b7f68], rings: true })]; }
  function dragonHorns() { return [curvedHorn({ name: '@horn_r', mirror: '@horn_l', at: [2.4, 7.5, 0.6], n: 9, len: 1.6, w0: 1.7, w1: 0.25, start: [62, 0, -14], bend: [-7.5, 0, 1.5], cols: [0x3a3a40, 0xe6dfc8] })]; }
  /** a cat ear: thin layers getting narrower (a smooth triangle), the pink inside made the same way */
  function catEar() {
    var kids = [], n = 8;
    for (var i = 0; i < n; i++) {
      var w = 3.6 - 0.42 * i, x0 = M.snap(2.3 - w / 2), x1 = M.snap(2.3 + w / 2);
      kids.push(C(i < n - 2 ? '@base' : '@tip', [x0, i ? 8 + 0.5 * i : 7.5, -1.75], [x1, 8.5 + 0.5 * i, -0.75], i < n - 2 ? GINGER : GINGER_D));
    }
    for (var j = 0; j < 5; j++) {
      var wi = 2.18 - 0.42 * j;
      kids.push(C('@inner', [M.snap(2.3 - wi / 2), 8 + 0.5 * j, -1.85], [M.snap(2.3 + wi / 2), 8.5 + 0.5 * j, -1.75], PINK));
    }
    return { g: 1, n: '@ear_r', o: [2.3, 8, -1.25], r: [-6, 0, -12], mirror: '@ear_l', kids: kids };
  }
  function crown() {
    var out = [C('@band', [-4.5, 6, -4.5], [4.5, 8, -4], GOLD), C('@band', [-4.5, 6, 4], [4.5, 8, 4.5], GOLD),
      C('@band', [-4.5, 6, -4], [-4, 8, 4], GOLD), C('@band', [4, 6, -4], [4.5, 8, 4], GOLD)];
    [[-1, -1], [1, -1], [-1, 1], [1, 1]].forEach(function (s) { var x = s[0] * 4, z = s[1] * 4; out.push(C('@spike', [x - 0.5, 8, z - 0.5], [x + 0.5, 9.5, z + 0.5], GOLD_L)); });
    out.push(C('@spike', [-0.5, 8, -4.5], [0.5, 10, -4], GOLD_L), C('@spike', [-0.5, 8, 4], [0.5, 10, 4.5], GOLD_L),
      C('@spike', [-4.5, 8, -0.5], [-4, 10, 0.5], GOLD_L), C('@spike', [4, 8, -0.5], [4.5, 10, 0.5], GOLD_L));
    out.push(C('@gem', [-0.75, 6.5, -4.7], [0.75, 7.5, -4.5], 0xe53935), C('@gem', [-4.7, 6.5, -0.75], [-4.5, 7.5, 0.75], 0x1e88e5),
      C('@gem', [4.5, 6.5, -0.75], [4.7, 7.5, 0.75], 0x1e88e5));
    return out;
  }
  function witchHat() {
    return [C('@brim', [-6, 8, -6], [6, 8.75, 6], 0x3d2a57), C('@top', [-3.5, 8.75, -3.5], [3.5, 11, 3.5], HAT),
      C('@band', [-3.6, 8.75, -3.6], [3.6, 9.75, 3.6], 0xc9a227),
      { g: 1, n: '@top', o: [0, 11, 0], r: [10, 0, 0], kids: [C('@top', [-2.5, 10.75, -2.5], [2.5, 13, 2.5], HAT),
        { g: 1, n: '@top', o: [0, 13, 0], r: [18, 0, 0], kids: [C('@top', [-1.5, 12.75, -1.5], [1.5, 15, 1.5], HAT),
          { g: 1, n: '@tip', o: [0, 15, 0], r: [28, 0, 0], kids: [C('@tip', [-0.75, 14.75, -0.75], [0.75, 17, 0.75], HAT)] }] }] }];
  }
  var BUILTIN = [
    { id: 'cat_ears', root: 'head', cat: 'head', parts: [catEar()] },
    { id: 'bunny_ears', root: 'head', cat: 'head', parts: [{ g: 1, n: '@ear_r', o: [2, 8, 0], r: [12, 0, -8], mirror: '@ear_l', kids: [
      C('@base', [1, 7.5, -0.5], [3, 15, 0.5], WHITE), C('@tip', [1.5, 15, -0.5], [2.5, 15.75, 0.5], WHITE), C('@inner', [1.5, 9, -0.6], [2.5, 14.5, -0.5], PINK)] }] },
    { id: 'horns_demon', root: 'head', cat: 'head', parts: demonHorns() },
    { id: 'horns_ram', root: 'head', cat: 'head', parts: ramHorns() },
    { id: 'horns_dragon', root: 'head', cat: 'head', parts: dragonHorns() },
    { id: 'halo', root: 'head', cat: 'head', parts: [{ s: { shape: 'ring', size: 10, thick: 1, res: '0.5' }, at: [0, 11.5, 0.5], r: [-12, 0, 0], n: '@halo', col: 0xffd54f, glow: true }] },
    { id: 'crown', root: 'head', cat: 'head', parts: crown() },
    { id: 'top_hat', root: 'head', cat: 'head', parts: [C('@brim', [-5.5, 8, -5.5], [5.5, 8.75, 5.5], 0x1e1e22),
      C('@top', [-3.5, 8.75, -3.5], [3.5, 15, 3.5], 0x29292e), C('@band', [-3.6, 8.75, -3.6], [3.6, 10, 3.6], 0xb71c1c)] },
    { id: 'witch_hat', root: 'head', cat: 'head', parts: witchHat() },
    { id: 'cat_tail', root: 'body', cat: 'tail', parts: [catTail()] }
  ];
  // lang: lib.p.{cat_ears|bunny_ears|horns_demon|horns_ram|horns_dragon|halo|crown|top_hat|witch_hat|cat_tail}
  var CATS = ['head', 'face', 'body', 'tail', 'wings', 'other'];
  // community parts (model_parts.js, generated from credited .bbmodel files, with their animations): next to ours, or
  // instead of the one they replace; kinds stay together in the window
  (function () {
    var mp = L.core.modules.model_parts, extra = mp && Array.isArray(mp.items) ? mp.items.slice() : [];
    // generated parts (wings, tails... with their animations)
    var gen = L.core.modules.model_presets;
    try { if (gen && typeof gen.items === 'function') extra = extra.concat(gen.items()); } catch (e) { L.warn('generated parts', e); }
    if (!extra.length) return;
    var gone = {};
    extra.forEach(function (it) { it.user = false; if (it.replaces) gone[it.replaces] = 1; });
    var all = BUILTIN.filter(function (b) { return !gone[b.id]; }).concat(extra);
    all.sort(function (a, b) { return (CATS.indexOf(a.cat) - CATS.indexOf(b.cat)) || ((a.order || 0) - (b.order || 0)); });
    BUILTIN.length = 0;
    all.forEach(function (x) { BUILTIN.push(x); });
  })();
  // lang: lib.cat.{head|face|body|tail|wings|other}
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  // lang: lib.root.{head|body|right_arm|left_arm|right_leg|left_leg|none}
  // where the body parts of a CPM model are (pivots) and their base boxes (the grey shape in the pictures)
  var PIVOT = { head: [0, 24, 0], body: [0, 24, 0], right_arm: [5, 22, 0], left_arm: [-5, 22, 0], right_leg: [1.9, 12, 0], left_leg: [-1.9, 12, 0] };
  var GHOST = { head: [[-4, 24, -4], [4, 32, 4]], body: [[-4, 12, -2], [4, 24, 2]], right_arm: [[4, 12, -2], [8, 24, 2]], left_arm: [[-8, 12, -2], [-4, 24, 2]],
    right_leg: [[-0.1, 0, -2], [3.9, 12, 2]], left_leg: [[-3.9, 0, -2], [0.1, 12, 2]] };

  // lang: lib.n.{base|mid|tip|inner|ear_r|ear_l|horn_r|horn_l|seg|halo|band|spike|gem|brim|top|lens_r|lens_l|frame|arm|bridge|bag|flap|pocket|buckle|strap_r|strap_l|strap|wing_r|wing_l|bone|claw|finger|skin|tail}
  /** the name of a part: '@key' / '@key_2' = lang name (+ number); anything else as it is */
  function nameOf(s) {
    s = String(s === undefined || s === null ? '' : s);
    var m = /^@([a-z_]+?)(?:_(\d+))?$/.exec(s);
    if (!m) return s || 'part';
    var key = 'cpmplus.model.lib.n.' + m[1], v = String(L.t(key));
    if (!v || v === key) return s;
    return v + (m[2] ? '_' + m[2] : '');
  }
  /** text as a part name: plain letters, numbers and '_' */
  function slug(s) {
    var v = String(s || '');
    try { v = v.normalize('NFD').replace(/[̀-ͯ]/g, ''); } catch (e) { }
    return v.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  }

  // =================================================================== spec helpers
  function swapSides(o) { var r = Object.assign({}, o), e = o.east; r.east = o.west; r.west = e; return r; }
  /** a box-UV painting as per-face UV: every face its own rect (same pixels, same turn) - packs into small free corners */
  function perFaceOf(t) {
    if (!t || !t.box) return t;
    return Object.assign({}, t, { box: false, off: [0, 0], faces: t.faces.map(function (f) { return Object.assign({}, f, { rel: [0, 0, f.rel[2] - f.rel[0], f.rel[3] - f.rel[1]] }); }) });
  }
  /**
   * a painting mirrored to the other side (as per-face UV): east / west swap, every face flips left-right (a face
   * whose UV is turned 90 / 270 degrees flips along the texture's other axis)
   */
  function mirrorTex(t) {
    if (!t) return t;
    var p = perFaceOf(t), sw = { east: 'west', west: 'east' };
    return Object.assign({}, p, { fs: p.fs ? swapSides(p.fs) : p.fs, faces: p.faces.map(function (f) {
      var side = (Number(f.rot) || 0) % 180 !== 0;
      return Object.assign({}, f, { face: sw[f.face] || f.face, flip: side ? [!!f.flip[0], !f.flip[1]] : [!f.flip[0], !!f.flip[1]] });
    }) });
  }
  /** a spec name for the other side: '@x_r' <-> '@x_l' (also with a number: '@x_r_3'); other names stay */
  function sideName(s) {
    if (typeof s !== 'string') return s;
    var m = /^@([a-z_]+?)_([rl])(_\d+)?$/.exec(s);
    return m ? '@' + m[1] + '_' + (m[2] === 'r' ? 'l' : 'r') + (m[3] || '') : s;
  }
  /** a spec node mirrored across X (the other side): x flips, turns around Y and Z go the other way, paintings mirror */
  function mirrored(node, name) {
    function flipR(r) { return r ? [r[0], -r[1], -r[2]] : r; }
    function walk(n) {
      var o = Object.assign({}, n);
      if (n.c) {
        o.f = [-n.t[0], n.f[1], n.f[2]]; o.t = [-n.f[0], n.t[1], n.t[2]];
        if (n.o) o.o = [-n.o[0], n.o[1], n.o[2]];
        o.r = flipR(n.r);
        if (n.tex) { o.tex = mirrorTex(n.tex); delete o.mirror_uv; }
        if (name) o.n = sideName(n.n);
      } else if (n.g) {
        var p = n.o || [0, 0, 0];
        o.o = [-p[0], p[1], p[2]]; o.r = flipR(n.r);
        // the copy on the other side gets its own animation id; its tracks are mirrored from the original's
        if (n.aid && name) o.aid = n.aid + '~m';
        // and its side in the names ('@forewing_r' -> '@forewing_l', '@prim_r_3' -> '@prim_l_3')
        if (name) o.n = sideName(n.n);
        o.kids = (n.kids || []).map(walk);
      } else if (n.s) {
        o.at = [-n.at[0], n.at[1], n.at[2]]; o.r = flipR(n.r);
      }
      if (o.r === undefined) delete o.r;
      return o;
    }
    var m = walk(node);
    if (name) m.n = name;
    return m;
  }
  function expand(list) {
    var out = [];
    (list || []).forEach(function (p) {
      if (!p) return;
      var q = Object.assign({}, p); delete q.mirror;
      out.push(q);
      if (p.mirror) out.push(mirrored(q, p.mirror));
    });
    return out;
  }
  /** the spec with every mirrored copy spelled out, groups too */
  function prepare(list) {
    return expand(list).map(function (n) { if (!n.g) return n; var g = Object.assign({}, n); g.kids = prepare(n.kids || []); return g; });
  }
  /** an item's sets: [{root (body part name), anchor (its pivot where it was saved), parts}] */
  function setsOf(item) { return Array.isArray(item.sets) ? item.sets : [{ root: item.root || '', anchor: null, parts: item.parts || [] }]; }
  function add(a, b) { return [M.snap(a[0] + b[0]), M.snap(a[1] + b[1]), M.snap(a[2] + b[2])]; }
  function eachCube(list, fn) { (list || []).forEach(function (n) { if (n.c) fn(n); else if (n.g) eachCube(n.kids, fn); }); }
  /** how many cubes an item puts in the model */
  var counts = new WeakMap();
  function countOf(item) {
    if (counts.has(item)) return counts.get(item);
    var n = 0;
    setsOf(item).forEach(function (s) {
      (function walk(list) { list.forEach(function (p) { if (p.c) n++; else if (p.g) walk(p.kids || []); else if (p.s) n += S().boxes(p.s, p.at).length; }); })(prepare(s.parts));
    });
    counts.set(item, n);
    return n;
  }
  function itemName(item) { return item.user ? String(item.name || '') : T('lib.p.' + item.id); }
  // lang: lib.cubes.{one} lib.groups.{one} lib.anims.{one}
  function cubesText(n) { return n === 1 ? T('lib.cubes.one') : T('lib.cubes', n); }
  function groupsText(n) { return n === 1 ? T('lib.groups.one') : T('lib.groups', n); }
  function animsText(n) { return n === 1 ? T('lib.anims.one') : T('lib.anims', n); }
  /** 'Modelo de <author> (<license>)' for parts made by someone else */
  function creditText(item) { var c = item && item.credit; return c && c.author ? T('lib.by', c.author, c.license || '-') : ''; }

  // =================================================================== the model's body parts
  function rootGroup(name) {
    if (!name || !G.Group) return null;
    for (var i = 0; i < G.Group.all.length; i++) { var g = G.Group.all[i]; if (g.parent === 'root' && g.name === name) return g; }
    return null;
  }
  function rootOf(node) { var p = node; while (p && p.parent && p.parent !== 'root') p = p.parent; return M.isGroup(p) && p.parent === 'root' ? p : null; }
  function rootLabel(name) {
    if (!name) return T('lib.root.none');
    return ROOTS.indexOf(name) >= 0 ? T('lib.root.' + name) : String(name);
  }
  /** right_arm <-> left_arm, right_leg <-> left_leg */
  function oppositeSides(a, b) {
    var re = /^(left|right)_(.+)$/, x = re.exec(a || ''), y = re.exec(b || '');
    return !!(x && y && x[1] !== y[1] && x[2] === y[2]);
  }

  // =================================================================== geometry of a spec (pictures, 3D preview)
  function rotAbout(p, r) {
    var T3 = G.THREE, m = new T3.Matrix4().makeTranslation(p[0], p[1], p[2]);
    m.multiply(new T3.Matrix4().makeRotationFromEuler(new T3.Euler(r[0] * M.DEG, r[1] * M.DEG, r[2] * M.DEG, 'ZYX')));
    return m.multiply(new T3.Matrix4().makeTranslation(-p[0], -p[1], -p[2]));
  }
  /** m followed by a turn r around p (Blockbench: a node turns its content around its pivot) */
  function chain(m, p, r) {
    if (!r || (!r[0] && !r[1] && !r[2])) return m;
    var q = rotAbout(p, r);
    return m ? m.clone().multiply(q) : q;
  }
  /** the turns of a group and every group above it, as one matrix (null = none) */
  function nodeChain(node) {
    var list = [];
    for (var p = node; p && p !== 'root'; p = p.parent) list.unshift(p);
    var m = null;
    list.forEach(function (g) { m = chain(m, g.origin, g.rotation); });
    return m;
  }
  // average colour of a painted face (pictures / preview), cached per painting
  var avgCache = new WeakMap();
  function avgOfFace(f) {
    if (avgCache.has(f)) return avgCache.get(f);
    var d = unb64(f.px), r = 0, g = 0, b = 0, n = 0;
    for (var i = 0; i < d.length; i += 4) if (d[i + 3] > 8) { r += d[i]; g += d[i + 1]; b += d[i + 2]; n++; }
    var v = n ? ((Math.round(r / n) << 16) | (Math.round(g / n) << 8) | Math.round(b / n)) : 0x9e9e9e;
    avgCache.set(f, v);
    return v;
  }
  function colourOf(n) {
    if (typeof n.col === 'number') return n.col;
    if (n.tex && n.tex.faces && n.tex.faces.length) { var big = n.tex.faces.reduce(function (a, f) { return f.w * f.h > a.w * a.h ? f : a; }); return avgOfFace(big); }
    return 0x9e9e9e;
  }
  function faceColours(n) {
    if (!n.tex || typeof n.col === 'number') return null;
    var out = {}; n.tex.faces.forEach(function (f) { out[f.face] = avgOfFace(f); });
    return out;
  }
  function boxOf(f, t, inf, m, n) {
    var i = inf || 0;
    return { lo: [Math.min(f[0], t[0]) - i, Math.min(f[1], t[1]) - i, Math.min(f[2], t[2]) - i], hi: [Math.max(f[0], t[0]) + i, Math.max(f[1], t[1]) + i, Math.max(f[2], t[2]) + i],
      m: m, col: colourOf(n), fcol: faceColours(n), tex: typeof n.col === 'number' ? null : n.tex || null };
  }
  // the pixels of a painting (pictures), decoded once per picture
  var pxCache = new Map();
  function pixelsOfFace(f) {
    if (!f || !f.px) return null;
    var p = pxCache.get(f.px);
    if (!p) { p = { w: f.w, h: f.h, d: unb64(f.px) }; if (pxCache.size > 64) pxCache.clear(); pxCache.set(f.px, p); }
    return p;
  }
  // the UV (0..1, top-left = 0, 0) of the 4 corners of a box face as Blockbench maps a face's picture (seen from outside)
  var FACEUV = { south: [[0, 1], [1, 1], [1, 0], [0, 0]], north: [[1, 1], [0, 1], [0, 0], [1, 0]], east: [[1, 1], [1, 0], [0, 0], [0, 1]],
    west: [[0, 1], [0, 0], [1, 0], [1, 1]], up: [[0, 0], [1, 0], [1, 1], [0, 1]], down: [[0, 1], [1, 1], [1, 0], [0, 0]] };
  var OPP = { north: 'south', south: 'north', east: 'west', west: 'east', up: 'down', down: 'up' };
  /** the corner UVs of a painted face: its picture's flips and turn applied */
  function faceUVs(name, f) {
    var rot = ((Number(f.rot) || 0) % 360 + 360) % 360, sub = Array.isArray(f.sub) && f.sub.length === 4 ? f.sub : null;
    var rw = f.rel ? f.rel[2] - f.rel[0] : 1, rh = f.rel ? f.rel[3] - f.rel[1] : 1;
    return FACEUV[name].map(function (q) {
      var u = q[0], v = q[1];
      if (f.flip && f.flip[0]) u = 1 - u;
      if (f.flip && f.flip[1]) v = 1 - v;
      if (rot === 90) { var a = u; u = v; v = 1 - a; } else if (rot === 180) { u = 1 - u; v = 1 - v; } else if (rot === 270) { var b = u; u = 1 - v; v = b; }
      // a face that uses part of a shared strip (f.sub, skin pixels inside the picture's area)
      if (sub) { u = (sub[0] + u * (sub[2] - sub[0])) / rw; v = (sub[1] + v * (sub[3] - sub[1])) / rh; }
      return [u, v];
    });
  }
  /** the boxes of prepared spec nodes in model space: anchor = the body part's pivot, m = matrix of the group they are in */
  function specBoxes(nodes, anchor, m, out) {
    nodes.forEach(function (n) {
      if (n.c) out.push(boxOf(add(anchor, n.f), add(anchor, n.t), n.inf, chain(m, add(anchor, n.o || n.f), n.r), n));
      else if (n.g) specBoxes(n.kids || [], anchor, chain(m, add(anchor, n.o || [0, 0, 0]), n.r), out);
      else if (n.s) { var at = add(anchor, n.at), sm = chain(m, at, n.r); S().boxes(n.s, at).forEach(function (b) { out.push(boxOf(b.from, b.to, n.inf, sm, n)); }); }
    });
    return out;
  }

  // =================================================================== pictures (a small z-buffer renderer, outlined faces)
  var R = 256, OUT = 128;
  // faces of a box: corner indices (bit 0 = x, 1 = y, 2 = z), the outward normal, the Blockbench face
  var BOXF = [[0, 2, 6, 4, -1, 0, 0, 'west'], [1, 3, 7, 5, 1, 0, 0, 'east'], [0, 1, 5, 4, 0, -1, 0, 'down'], [2, 3, 7, 6, 0, 1, 0, 'up'],
    [0, 1, 3, 2, 0, 0, -1, 'north'], [4, 5, 7, 6, 0, 0, 1, 'south']];
  /**
   * a picture of an item (PNG data URL, OUT x OUT): the parts from the front (or from the back when they sit on the
   * back) a bit from above, lit from the top, every face outlined, over a see-through grey shape of the body part
   */
  function thumbnail(item) {
    var T3 = G.THREE, parts = [], ghosts = [];
    setsOf(item).forEach(function (set) {
      var anchor = PIVOT[set.root] || set.anchor || [0, 0, 0];
      specBoxes(prepare(set.parts), anchor, null, parts);
      var gb = GHOST[set.root];
      if (gb && !ghosts.some(function (g) { return g.root === set.root; })) ghosts.push({ root: set.root, lo: gb[0], hi: gb[1], m: null, col: 0xb4c0cf });
    });
    var cv = document.createElement('canvas'); cv.width = cv.height = OUT;
    if (!parts.length || !T3) return cv.toDataURL('image/png');
    var zc = 0; parts.forEach(function (b) { zc += (b.lo[2] + b.hi[2]) / 2; }); zc /= parts.length;
    var gz = 0; ghosts.forEach(function (g) { gz += (g.lo[2] + g.hi[2]) / 2; }); if (ghosts.length) gz /= ghosts.length;
    var back = zc > gz + 0.5;
    var V = new T3.Matrix4().makeRotationX(22 * M.DEG).multiply(new T3.Matrix4().makeRotationY((back ? 35 : 145) * M.DEG));
    var Lt = new T3.Vector3(-0.35, 0.85, 0.45).normalize();
    var faces = [], minx = Infinity, maxx = -Infinity, miny = Infinity, maxy = -Infinity;
    function collect(b, ghost) {
      var pts = [];
      for (var i = 0; i < 8; i++) {
        var v = new T3.Vector3(i & 1 ? b.hi[0] : b.lo[0], i & 2 ? b.hi[1] : b.lo[1], i & 4 ? b.hi[2] : b.lo[2]);
        if (b.m) v.applyMatrix4(b.m);
        v.applyMatrix4(V); pts.push(v);
        minx = Math.min(minx, v.x); maxx = Math.max(maxx, v.x); miny = Math.min(miny, -v.y); maxy = Math.max(maxy, -v.y);
      }
      // a painted face shows its picture (see-through pixels are holes: feathers, leaves); a flat plane shows both of its
      // sides, so its faces are kept whichever way they point
      var flat = (b.hi[0] - b.lo[0]) < 1e-4 || (b.hi[1] - b.lo[1]) < 1e-4 || (b.hi[2] - b.lo[2]) < 1e-4;
      BOXF.forEach(function (fc) {
        var nrm = new T3.Vector3(fc[4], fc[5], fc[6]);
        if (b.m) nrm.transformDirection(b.m);
        nrm.transformDirection(V);
        var pic = null, tf = null;
        if (b.tex && b.tex.faces) { for (var q = 0; q < b.tex.faces.length; q++) if (b.tex.faces[q].face === fc[7]) { tf = b.tex.faces[q]; break; } }
        if (tf) pic = pixelsOfFace(tf);
        if (nrm.z <= 0.001 && !(flat && pic)) return;// facing away
        // the back of a plane only when its front has no picture
        if (nrm.z <= 0.001 && b.tex.faces.some(function (o) { return o.face === OPP[fc[7]] && o.px; })) return;
        if (b.tex && b.tex.fs && b.tex.fs[fc[7]] === -1) return;// switched off
        if (flat && !pic && b.tex) return;// the unpainted rim of a picture plane
        var col = b.fcol && typeof b.fcol[fc[7]] === 'number' ? b.fcol[fc[7]] : b.col, k = 0.6 + 0.4 * Math.abs(nrm.dot(Lt));
        // one colour reads as one surface: outlines only where the colour changes, around the shape and at steps in
        // depth; the light tells the faces of a cube apart
        var key = pic ? 'p' + faces.length : String(col);
        if (!(key in keys)) keys[key] = nk++;
        faces.push({ p: [pts[fc[0]], pts[fc[1]], pts[fc[2]], pts[fc[3]]], r: ((col >> 16) & 255) * k, g: ((col >> 8) & 255) * k, b: (col & 255) * k, ghost: ghost, key: keys[key],
          pic: pic, uv: pic ? faceUVs(fc[7], tf) : null, k: k, back: nrm.z <= 0.001 });
      });
    }
    var keys = {}, nk = 0;
    parts.forEach(function (b) { collect(b, false); });
    ghosts.forEach(function (b) { collect(b, true); });
    var pad = 14, sc = (R - 2 * pad) / Math.max(maxx - minx, maxy - miny, 1e-3);
    var ox = (R - (maxx - minx) * sc) / 2 - minx * sc, oy = (R - (maxy - miny) * sc) / 2 - miny * sc;
    var N = R * R, zb = new Float32Array(N).fill(-Infinity), id = new Int32Array(N).fill(-1), col = new Float32Array(N * 3);
    var gzb = new Float32Array(N).fill(-Infinity), gid = new Int32Array(N).fill(-1), gcol = new Float32Array(N * 3);
    function tri(a, b, c, f, fi, ta, tb, tc) {
      var area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
      if (Math.abs(area) < 1e-9) return;
      var x0 = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0]))), x1 = Math.min(R - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
      var y0 = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1]))), y1 = Math.min(R - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
      var pic = f.pic;
      for (var y = y0; y <= y1; y++) for (var x = x0; x <= x1; x++) {
        var px = x + 0.5, py = y + 0.5;
        var w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
        var w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
        var w2 = 1 - w0 - w1;
        if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
        var z = w0 * a[2] + w1 * b[2] + w2 * c[2], i = y * R + x;
        var r = f.r, g = f.g, bl = f.b;
        if (pic) {
          // the picture's pixel here; see-through = a hole
          var u = w0 * ta[0] + w1 * tb[0] + w2 * tc[0], v = w0 * ta[1] + w1 * tb[1] + w2 * tc[1];
          var sx = Math.min(pic.w - 1, Math.max(0, Math.floor(u * pic.w))), sy = Math.min(pic.h - 1, Math.max(0, Math.floor(v * pic.h))), j = (sy * pic.w + sx) * 4;
          if (pic.d[j + 3] < 128) continue;
          r = pic.d[j] * f.k; g = pic.d[j + 1] * f.k; bl = pic.d[j + 2] * f.k;
        }
        if (f.ghost) { if (z > gzb[i]) { gzb[i] = z; gid[i] = fi; gcol[i * 3] = r; gcol[i * 3 + 1] = g; gcol[i * 3 + 2] = bl; } }
        else if (z > zb[i]) { zb[i] = z; id[i] = f.key; col[i * 3] = r; col[i * 3 + 1] = g; col[i * 3 + 2] = bl; }
      }
    }
    faces.forEach(function (f, fi) {
      var s = f.p.map(function (v) { return [v.x * sc + ox, -v.y * sc + oy, v.z]; }), t = f.uv || [null, null, null, null];
      tri(s[0], s[1], s[2], f, fi, t[0], t[1], t[2]); tri(s[0], s[2], s[3], f, fi, t[0], t[2], t[3]);
    });
    function edge(buf, i) {
      var x = i % R, y = (i / R) | 0, v = buf[i];
      return (x > 0 && buf[i - 1] !== v) || (x < R - 1 && buf[i + 1] !== v) || (y > 0 && buf[i - R] !== v) || (y < R - 1 && buf[i + R] !== v);
    }
    // an outline also where one surface passes in front of another one like it (a step in depth)
    var gap = 0.6;
    function depthEdge(i) {
      var x = i % R, y = (i / R) | 0, z = zb[i];
      return (x > 0 && id[i - 1] >= 0 && Math.abs(zb[i - 1] - z) > gap) || (x < R - 1 && id[i + 1] >= 0 && Math.abs(zb[i + 1] - z) > gap) ||
        (y > 0 && id[i - R] >= 0 && Math.abs(zb[i - R] - z) > gap) || (y < R - 1 && id[i + R] >= 0 && Math.abs(zb[i + R] - z) > gap);
    }
    // the parts (opaque, outlined), then the grey body part over what is behind it
    var P = new Float32Array(N * 4);
    for (var i = 0; i < N; i++) {
      var r = 0, g = 0, b = 0, a = 0;
      if (id[i] >= 0) { r = col[i * 3]; g = col[i * 3 + 1]; b = col[i * 3 + 2]; a = 1; if (edge(id, i) || depthEdge(i)) { r *= 0.5; g *= 0.5; b *= 0.5; } }
      if (gid[i] >= 0 && gzb[i] > zb[i]) {
        var ga = edge(gid, i) ? 0.55 : 0.3, oa = ga + a * (1 - ga);
        r = (gcol[i * 3] * ga + r * a * (1 - ga)) / oa; g = (gcol[i * 3 + 1] * ga + g * a * (1 - ga)) / oa; b = (gcol[i * 3 + 2] * ga + b * a * (1 - ga)) / oa; a = oa;
      }
      P[i * 4] = r * a; P[i * 4 + 1] = g * a; P[i * 4 + 2] = b * a; P[i * 4 + 3] = a;
    }
    // 2 x 2 down (smooth edges)
    var ctx = cv.getContext('2d'), img = ctx.createImageData(OUT, OUT), d = img.data, k2 = R / OUT;
    for (var oy2 = 0; oy2 < OUT; oy2++) for (var ox2 = 0; ox2 < OUT; ox2++) {
      var sr = 0, sg = 0, sb = 0, sa = 0;
      for (var yy = 0; yy < k2; yy++) for (var xx = 0; xx < k2; xx++) { var j = ((oy2 * k2 + yy) * R + ox2 * k2 + xx) * 4; sr += P[j]; sg += P[j + 1]; sb += P[j + 2]; sa += P[j + 3]; }
      var o4 = (oy2 * OUT + ox2) * 4;
      if (sa > 0) { d[o4] = sr / sa; d[o4 + 1] = sg / sa; d[o4 + 2] = sb / sa; d[o4 + 3] = 255 * sa / (k2 * k2); }
    }
    ctx.putImageData(img, 0, 0);
    return cv.toDataURL('image/png');
  }
  var thumbs = {};
  function okThumb(s) { return typeof s === 'string' && s.length < 400000 && /^data:image\/png;base64,[A-Za-z0-9+\/=]+$/.test(s); }
  function thumbOf(item) {
    if (item.user && okThumb(item.thumb)) return item.thumb;
    var key = (item.user ? 'u:' : 'b:') + item.id;
    if (!thumbs[key]) { try { thumbs[key] = thumbnail(item); } catch (e) { L.warn('could not draw the part ' + item.id, e); thumbs[key] = ''; } }
    return thumbs[key];
  }

  // =================================================================== putting parts in the model
  function b64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }
  function unb64(s) { var bin = atob(s), u8 = new Uint8ClampedArray(bin.length); for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i); return u8; }
  /** a pixel block scaled to w x h (nearest pixel: pixel art stays sharp) */
  function fit(img, w, h) {
    if (img.width === w && img.height === h) return img;
    var out = new ImageData(w, h), s = img.data, d = out.data;
    for (var y = 0; y < h; y++) {
      var sy = Math.min(img.height - 1, Math.floor(y * img.height / h));
      for (var x = 0; x < w; x++) {
        var sx = Math.min(img.width - 1, Math.floor(x * img.width / w)), i = (y * w + x) * 4, j = (sy * img.width + sx) * 4;
        d[i] = s[j]; d[i + 1] = s[j + 1]; d[i + 2] = s[j + 2]; d[i + 3] = s[j + 3];
      }
    }
    return out;
  }
  /** builds prepared spec nodes inside parent; painted cubes are listed in out.tex for the texture step */
  function build(nodes, parent, anchor, out) {
    var cpm = M.isCpm();
    nodes.forEach(function (n) {
      if (n.g) {
        var g = M.newGroup(nameOf(n.n), add(anchor, n.o || [0, 0, 0]), parent);
        if (n.r) g.rotation = n.r.slice();
        if (n.vis === false) g.visibility = false;
        if (n.hidden && cpm) g.cpm_hidden = true;
        out.groups.push(g);
        if (n.aid) out.byAid[n.aid] = g;
        build(n.kids || [], g, anchor, out);
      } else if (n.c) {
        // out.colours: a painted cube goes in as a colour cube (its main colour) - no skin space needed
        var painted = !!n.tex && !out.colours;
        var colour = painted ? undefined : typeof n.col === 'number' ? n.col : typeof n.alt === 'number' ? n.alt : n.tex ? colourOf(n) : 0x9e9e9e;
        var c = M.newCube({ name: nameOf(n.n), from: add(anchor, n.f), to: add(anchor, n.t), origin: add(anchor, n.o || n.f), rotation: n.r, inflate: n.inf || 0, colour: colour }, parent);
        if (cpm) {
          if (painted && typeof n.recol === 'number') c.cpm_recolor = n.recol;
          if (n.glow) c.cpm_glow = true;
          if (n.extrude) c.cpm_extrude = true;
          if (n.cpm) c.cpm_data = JSON.stringify(n.cpm);
        }
        if (n.mirror_uv && painted) c.mirror_uv = true;
        if (n.vis === false) c.visibility = false;
        out.cubes.push(c);
        if (painted) out.tex.push({ cube: c, tex: n.tex });
      } else if (n.s) {
        var at = add(anchor, n.at), gs = M.newGroup(nameOf(n.n), at, parent);
        if (n.r) gs.rotation = n.r.slice();
        out.groups.push(gs);
        S().boxes(n.s, at).forEach(function (b, i) {
          var cs = M.newCube({ name: nameOf(n.n) + '_' + (i + 1), from: b.from, to: b.to, origin: at, inflate: n.inf || 0, colour: n.col }, gs);
          if (n.glow && cpm) cs.cpm_glow = true;
          out.cubes.push(cs);
        });
      }
    });
  }
  /**
   * free skin space for every painted cube (planned before anything changes: no room = nothing happens). A box-UV cube
   * keeps its box template when it fits; when it does not, each face goes into a free corner of its own (per-face UV).
   */
  /** faces with the same picture key share one area (every feather of a kind: one picture on the skin) */
  function shareKey(f) { return f.key ? f.key + '|' + (f.rel[2] - f.rel[0]) + 'x' + (f.rel[3] - f.rel[1]) : null; }
  function planPaint(list, tex) {
    var tx = X(), map = tx.occupancy(tex), shared = {};
    return list.map(function (t) {
      if (t.box) {
        var at = tx.allocShape(map, t.faces.map(function (f) { return f.rel; }));
        if (at) return { at: at };
        t = perFaceOf(t);
      }
      var spots = t.faces.map(function (f) {
        var k = shareKey(f);
        if (k && shared[k]) return shared[k];
        var s = tx.alloc(map, f.rel[2] - f.rel[0], f.rel[3] - f.rel[1]);
        if (k && s) shared[k] = s;
        return s;
      });
      if (spots.some(function (s) { return !s; })) throw new Error(T('lib.noroom'));
      return { spots: spots, perFace: true };
    });
  }
  /**
   * a pixel block made smaller by averaging (HD pictures on a skin with fewer pixels: the shape and its outline stay;
   * see-through where less than half of the area was painted)
   */
  function shrink(img, w, h) {
    var out = new ImageData(w, h), s = img.data, d = out.data, kx = img.width / w, ky = img.height / h;
    for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) {
      var x0 = x * kx, x1 = (x + 1) * kx, y0 = y * ky, y1 = (y + 1) * ky, r = 0, g = 0, b = 0, a = 0, area = 0;
      for (var sy = Math.floor(y0); sy < Math.ceil(y1); sy++) {
        var wy = Math.min(y1, sy + 1) - Math.max(y0, sy); if (wy <= 0) continue;
        for (var sx = Math.floor(x0); sx < Math.ceil(x1); sx++) {
          var wx = Math.min(x1, sx + 1) - Math.max(x0, sx); if (wx <= 0) continue;
          var wgt = wx * wy, j = (sy * img.width + sx) * 4, al = s[j + 3] / 255;
          r += s[j] * al * wgt; g += s[j + 1] * al * wgt; b += s[j + 2] * al * wgt; a += al * wgt; area += wgt;
        }
      }
      var i = (y * w + x) * 4;
      if (a > 0 && a / area >= 0.5) { d[i] = Math.round(r / a); d[i + 1] = Math.round(g / a); d[i + 2] = Math.round(b / a); d[i + 3] = 255; }
    }
    return out;
  }
  /** points the new cubes at their areas and writes their pixels (inside the undo edit) -> {before, after} blocks */
  function paint(list, plans, tex) {
    var tx = X(), I = tx.uvInfo(tex), ops = [], written = {};
    list.forEach(function (e, i) {
      var c = e.cube, p = plans[i], t = p.perFace ? perFaceOf(e.tex) : e.tex;
      if (t.box) {
        c.box_uv = true; c.uv_offset = [p.at[0] + t.off[0], p.at[1] + t.off[1]];
        try { if (typeof c.updateUV === 'function') c.updateUV(); } catch (x) { }
        t.faces.forEach(function (f) { ops.push({ f: f, u: p.at[0] + f.rel[0], v: p.at[1] + f.rel[1] }); });
      } else {
        c.box_uv = false; c.mirror_uv = false;
        t.faces.forEach(function (f, k) {
          var fc = c.faces[f.face]; if (!fc) return;
          var at = p.spots[k], dw = f.rel[2] - f.rel[0], dh = f.rel[3] - f.rel[1];
          // f.sub = the face uses only this part of its (shared) picture: one strip painted once for a whole chain (a
          // horn: the rings run on from one segment to the next)
          var x0 = at[0], y0 = at[1], x1 = at[0] + dw, y1 = at[1] + dh;
          if (Array.isArray(f.sub) && f.sub.length === 4) { x0 = at[0] + f.sub[0]; y0 = at[1] + f.sub[1]; x1 = at[0] + f.sub[2]; y1 = at[1] + f.sub[3]; }
          fc.uv = [f.flip[0] ? x1 : x0, f.flip[1] ? y1 : y0, f.flip[0] ? x0 : x1, f.flip[1] ? y0 : y1];
          fc.rotation = Number(f.rot) || 0;
          // a shared picture is written once (every face of its kind points at the same area)
          var k = shareKey(f);
          if (!k || !written[k]) ops.push({ f: f, u: at[0], v: at[1] });
          if (k) written[k] = true;
        });
      }
      // faces the saved part had without painting (blank) or switched off stay that way
      if (t.fs) FACES.forEach(function (k) { var fc = c.faces[k]; if (!fc) return; if (t.fs[k] === 0) fc.texture = false; else if (t.fs[k] === -1) fc.texture = null; });
    });
    var blocks = ops.map(function (o) {
      var w = Math.max(1, Math.round((o.f.rel[2] - o.f.rel[0]) * I.sx)), h = Math.max(1, Math.round((o.f.rel[3] - o.f.rel[1]) * I.sy));
      var src = new ImageData(unb64(o.f.px), o.f.w, o.f.h);
      // HD pictures (f.hd) going onto a skin with fewer pixels are averaged down; pixel art stays sharp (nearest)
      return { x: Math.round(o.u * I.sx), y: Math.round(o.v * I.sy), img: o.f.hd && (w < o.f.w || h < o.f.h) ? shrink(src, w, h) : fit(src, w, h) };
    });
    if (!blocks.length) return { before: [], after: [] };
    var rects = blocks.map(function (b) { return [b.x, b.y, b.img.width, b.img.height]; });
    var before = tx.grabBlocks(tex, rects);
    tx.putBlocks(tex, blocks);
    return { before: before, after: tx.grabBlocks(tex, rects) };
  }
  // =================================================================== animations that come with a part
  // item.anims = [{name ('@key' = lang name, or text), type (CPM: 'global' = always playing, 'walking',
  // 'creative_flying', 'gesture'...), additive, loop ('loop' | 'once' | 'hold'), length (s), snapping (fps), tracks:
  // [{aid (the 'aid' of a spec group), keys: [{ch ('rotation' | 'position' | 'scale'), t, i (interpolation), v: [x, y,
  // z], bz?: {linked, lt, lv, rt, rv} (bezier handles)}]}]}]
  function mirrorVec(ch, v) { return ch === 'rotation' ? [v[0], -v[1], -v[2]] : ch === 'position' ? [-v[0], v[1], v[2]] : v.slice(); }
  /** a key for the other side: turns around Y and Z go the other way, moves along X flip */
  function mirrorKey(k) {
    if (!k.v) return k;
    var o = Object.assign({}, k, { v: mirrorVec(k.ch, k.v) });
    if (k.bz) o.bz = Object.assign({}, k.bz, { lv: mirrorVec(k.ch, k.bz.lv), rv: mirrorVec(k.ch, k.bz.rv) });
    return o;
  }
  function animCount(item) { return Array.isArray(item.anims) ? item.anims.length : 0; }
  function uniqueAnimName(base) {
    var all = (G.Animation && G.Animation.all) || [], n = base, i = 2;
    function used(x) { return all.some(function (a) { return a && a.name === x; }); }
    while (used(n)) { n = base + ' ' + i; i++; }
    return n;
  }
  function addKey(ba, k) {
    // k.v = x / y / z of a rotation, position or scale key; k.d = any other data point (visibility, colour)
    var dp = k.v ? { x: k.v[0], y: k.v[1], z: k.v[2] } : Object.assign({}, k.d || {}), i = k.i || 'linear';
    var kf = typeof ba.addKeyframe === 'function' ? ba.addKeyframe({ channel: k.ch, time: k.t, interpolation: i, data_points: [dp] }) : ba.createKeyframe(null, k.t, k.ch, false, false);
    if (!kf) return;
    kf.time = k.t; kf.interpolation = i;
    if (kf.data_points && kf.data_points[0]) { var p = kf.data_points[0]; for (var key in dp) if (Object.prototype.hasOwnProperty.call(dp, key)) p[key] = dp[key]; }
    if (k.bz) {
      kf.bezier_linked = !!k.bz.linked;
      kf.bezier_left_time = k.bz.lt.slice(); kf.bezier_left_value = k.bz.lv.slice();
      kf.bezier_right_time = k.bz.rt.slice(); kf.bezier_right_value = k.bz.rv.slice();
    }
  }
  /**
   * the item's animations for the groups just built (inside the undo edit): byAid = groups by their spec 'aid' (a
   * mirrored pair's copy is aid + '~m'); mirror = the whole part went to the other side. -> the new animations
   */
  function makeAnims(item, byAid, mirror) {
    var made = [];
    (item.anims || []).forEach(function (A) {
      var tracks = [];
      (A.tracks || []).forEach(function (tr) {
        var keys = tr.keys || [];
        // tr.side: 'r' = only the part as made (its right side), 'l' = only its mirrored copy (one slider per wing)
        if (byAid[tr.aid] && tr.side !== 'l') tracks.push({ g: byAid[tr.aid], keys: mirror ? keys.map(mirrorKey) : keys });
        if (byAid[tr.aid + '~m'] && tr.side !== 'r') tracks.push({ g: byAid[tr.aid + '~m'], keys: mirror ? keys : keys.map(mirrorKey) });
      });
      if (!tracks.length) return;
      var len = Math.max(0.05, Number(A.length) || 1);
      // a part put on the other side: an animation for one side changes name with it ('@open_r' <-> '@open_l')
      var an = mirror ? sideName(A.name || 'anim') : A.name || 'anim';
      var a = new G.Animation({ name: uniqueAnimName(itemName(item) + ' - ' + nameOf(an)) });
      a = a.add(false) || a;
      a.loop = A.loop === 'once' || A.loop === 'hold' ? A.loop : 'loop';
      a.length = len;
      // CPM keeps at most 255 frames per animation
      a.snapping = Math.max(1, Math.min(Number(A.snapping) || 20, Math.floor(255 / len)));
      a.cpm_type = A.type || 'global';
      a.cpm_additive = A.additive !== false;
      // a slider (value layer): where it starts (0..1)
      if (typeof A.layerDefault === 'number') a.cpm_layerDefault = Math.max(0, Math.min(1, A.layerDefault));
      tracks.forEach(function (tr) { var ba = a.getBoneAnimator(tr.g); tr.keys.forEach(function (k) { addKey(ba, k); }); });
      made.push(a);
    });
    return made;
  }

  /**
   * puts an item in the model (one undo entry, pixels and animations included): o.where = a body part name ('auto' /
   * none = each set onto its own body part), o.mirror = on the other side, o.anims = false leaves its animations out.
   * -> {groups (the new top groups), cubes, all (every new group), anims (the new animations)}
   */
  /** how many texture pixels per UV unit the item's pictures were drawn with (0 = no HD pictures) */
  function hdOf(item) { return Number(item && item.hd) || 0; }
  /** the item has HD pictures and the skin has fewer pixels than they were drawn with */
  function wantsHD(item) {
    var k = hdOf(item); if (!k) return false;
    var tex = X().mainTexture();
    return !!tex && X().density(tex) < k - 1e-6;
  }
  function insert(item, o) {
    o = o || {};
    var tx = X(), sets = setsOf(item), chosen = sets.length === 1 && o.where && o.where !== 'auto' ? rootGroup(o.where) : null;
    var targets = sets.map(function (s) {
      var parts = prepare(s.parts);
      if (o.mirror) parts = parts.map(function (p) { return mirrored(p); });
      var parent = chosen || rootGroup(s.root) || 'root';
      var anchor = parent !== 'root' && parent.origin ? parent.origin.slice() : (PIVOT[s.root] || s.anchor || [0, 0, 0]).slice();
      // a saved single group needs no extra group around it
      var wrap = !(item.user && parts.length === 1 && parts[0].g);
      return { parts: parts, parent: parent, anchor: anchor, wrap: wrap };
    });
    // o.paint = 'colours': painted cubes go in as colour cubes (no skin space needed)
    var colours = o.paint === 'colours', painted = [];
    if (!colours) targets.forEach(function (tg) { eachCube(tg.parts, function (n) { if (n.tex) painted.push(n.tex); }); });
    var tex = painted.length ? tx.mainTexture() : null;
    if (painted.length && !tex) throw new Error(T('texmap.none'));
    var plans = [];
    // o.grow: no room on the skin -> the texture's area doubles (64 -> 128 -> 256, the pixels kept in place, its own undo
    // step) until the part fits
    for (var tries = 0; painted.length; tries++) {
      try { plans = planPaint(painted, tex); break; }
      catch (e) {
        if (!o.grow || tries >= 2 || (e && e.message) !== T('lib.noroom')) throw e;
        tx.growUV(tex, 2);
      }
    }
    // o.hd: the texture first gets the pixels the pictures were drawn with - after the room check (UV units: the plan
    // stays right), as its own undo step (Ctrl+Z once takes the part out, twice gives the texture its old size back)
    if (o.hd && painted.length && wantsHD(item)) tx.makeHD(tex, hdOf(item));
    var out = { cubes: [], groups: [], tex: [], byAid: {}, colours: colours }, tops = [], px = { before: [], after: [] }, anims = [];
    var wrapName = slug(itemName(item)) || 'part', withAnims = o.anims !== false && animCount(item) > 0 && !!G.Animation;
    function aspects(cubes, groups, an) {
      var a = Object.assign({ outliner: true, elements: cubes, groups: groups }, tex ? tx.pixelAspects(tex) : {});
      if (withAnims) a.animations = an;
      return a;
    }
    M.edit(T('lib.insert'), aspects([], [], []), function () {
      targets.forEach(function (tg) {
        if (tg.wrap) {
          var w = M.newGroup(wrapName, tg.anchor.slice(), tg.parent);
          out.groups.push(w); tops.push(w);
          build(tg.parts, w, tg.anchor, out);
        } else {
          var n0 = out.groups.length;
          build(tg.parts, tg.parent, tg.anchor, out);
          if (out.groups[n0]) tops.push(out.groups[n0]);
        }
      });
      if (out.tex.length) px = paint(out.tex, plans, tex);
      if (withAnims) anims = makeAnims(item, out.byAid, !!o.mirror);
    }, function () { return aspects(out.cubes, out.groups, anims); });
    if (tex && px.before.length) tx.bindPixels(tex, px.before, px.after);
    M.refresh();
    try { if (tex && typeof tex.updateLayerChanges === 'function') tex.updateLayerChanges(true); } catch (e) { }
    M.selectNodes(tops);
    return { groups: tops, cubes: out.cubes, all: out.groups, anims: anims };
  }
  /** the boxes the 3D preview shows for an item put with options o (same rules as insert) */
  function previewBoxes(item, o) {
    o = o || {};
    var sets = setsOf(item), chosen = sets.length === 1 && o.where && o.where !== 'auto' ? rootGroup(o.where) : null, out = [];
    sets.forEach(function (s) {
      var parts = prepare(s.parts);
      if (o.mirror) parts = parts.map(function (p) { return mirrored(p); });
      var parent = chosen || rootGroup(s.root) || 'root';
      var anchor = parent !== 'root' && parent.origin ? parent.origin.slice() : (PIVOT[s.root] || s.anchor || [0, 0, 0]).slice();
      specBoxes(parts, anchor, parent !== 'root' ? nodeChain(parent) : null, out);
    });
    return out.map(function (b) { return { from: b.lo, to: b.hi, matrix: b.m, col: b.col }; });
  }

  // =================================================================== saving the selection
  function isVanilla(c) { return M.isBase(c); }
  function isColourCube(c) { return !!c.box_uv && FACES.every(function (k) { var f = c.faces && c.faces[k]; return !f || !f.texture; }); }
  /** the CPM data of a cube a copy may keep (its ids and the 'vanilla part' mark stay behind) */
  function cpmExtras(c) {
    try {
      var d = c.cpm_data ? JSON.parse(c.cpm_data) : null;
      if (!d || typeof d !== 'object') return null;
      delete d.sid; delete d.vanilla;
      return Object.keys(d).length ? d : null;
    } catch (e) { return null; }
  }
  /** the painting of a cube: its faces' pixels, where they sit in its UV template and how they are turned */
  function cubePixels(c) {
    var tx = X(), tex = tx.textureOf(c); if (!tex) return null;
    var I = tx.uvInfo(tex), rects = tx.faceRects(c, tex); if (!rects.length) return null;
    var box = !!c.box_uv, bu = Infinity, bv = Infinity;
    rects.forEach(function (f) { bu = Math.min(bu, f.r[0]); bv = Math.min(bv, f.r[1]); });
    if (box && c.uv_offset) { bu = Math.min(bu, c.uv_offset[0]); bv = Math.min(bv, c.uv_offset[1]); }
    var blocks = tx.grabBlocks(tex, rects.map(function (f) {
      return [Math.round(f.r[0] * I.sx), Math.round(f.r[1] * I.sy), Math.max(1, Math.round((f.r[2] - f.r[0]) * I.sx)), Math.max(1, Math.round((f.r[3] - f.r[1]) * I.sy))];
    }));
    var fs = {}; FACES.forEach(function (k) { var fc = c.faces[k]; fs[k] = !fc || fc.texture === null || fc.texture === undefined ? -1 : fc.texture === false ? 0 : 1; });
    return { box: box, off: box && c.uv_offset ? [c.uv_offset[0] - bu, c.uv_offset[1] - bv] : [0, 0], fs: fs, scale: [I.sx, I.sy],
      faces: rects.map(function (f, i) {
        var fc = c.faces[f.face], w = f.r[2] - f.r[0], h = f.r[3] - f.r[1];
        return { face: f.face, rel: box ? [f.r[0] - bu, f.r[1] - bv, f.r[2] - bu, f.r[3] - bv] : [0, 0, w, h], flip: [fc.uv[0] > fc.uv[2], fc.uv[1] > fc.uv[3]],
          rot: box ? 0 : (Number(fc.rotation) || 0), w: blocks[i].w, h: blocks[i].h, px: b64(new Uint8Array(blocks[i].img.data.buffer)) };
      }) };
  }
  function toSpec(node, anchor) {
    function rel(v) { return [M.snap(v[0] - anchor[0]), M.snap(v[1] - anchor[1]), M.snap(v[2] - anchor[2])]; }
    function rot(r) { return r && (r[0] || r[1] || r[2]) ? [M.snap(r[0]), M.snap(r[1]), M.snap(r[2])] : undefined; }
    var cpm = M.isCpm();
    if (M.isGroup(node)) {
      var g = { g: 1, n: String(node.name), o: rel(node.origin), aid: String(node.uuid || ''),
        kids: (node.children || []).filter(function (k) { return M.isGroup(k) || (M.isCube(k) && !isVanilla(k)); }).map(function (k) { return toSpec(k, anchor); }) };
      if (!g.aid) delete g.aid;
      var gr = rot(node.rotation); if (gr) g.r = gr;
      if (node.visibility === false) g.vis = false;
      if (cpm && node.cpm_hidden) g.hidden = true;
      return g;
    }
    var c = { c: 1, n: String(node.name), f: rel(node.from), t: rel(node.to), o: rel(node.origin) };
    var cr = rot(node.rotation); if (cr) c.r = cr;
    if (node.inflate) c.inf = node.inflate;
    if (node.visibility === false) c.vis = false;
    if (node.mirror_uv) c.mirror_uv = true;
    var rc = null;
    if (cpm) {
      if (node.cpm_glow) c.glow = true;
      if (node.cpm_extrude) c.extrude = true;
      var extra = cpmExtras(node); if (extra) c.cpm = extra;
      if (typeof node.cpm_recolor === 'number' && node.cpm_recolor !== -1) rc = node.cpm_recolor & 0xFFFFFF;
    }
    if (rc !== null && isColourCube(node)) c.col = rc;
    else {
      var p = cubePixels(node);
      if (p) { c.tex = p; if (rc !== null) c.recol = rc; }
      else c.col = rc !== null ? rc : 0x9e9e9e;
    }
    return c;
  }
  /** what saving takes from the selection: a whole body part gives its own parts (never the player's base box) */
  function partsToSave(nodes) {
    var out = [];
    (nodes || []).forEach(function (n) {
      if (M.isGroup(n) && n.parent === 'root') (n.children || []).forEach(function (k) { if (M.isGroup(k) || (M.isCube(k) && !isVanilla(k))) out.push(k); });
      else if (M.isGroup(n) || (M.isCube(n) && !isVanilla(n))) out.push(n);
    });
    return out;
  }
  function guessCat(item) {
    var name = String(item.name || '').toLowerCase(), roots = setsOf(item).map(function (s) { return s.root; });
    if (/tail|rabo|cauda/.test(name)) return 'tail';
    if (/wing|asa/.test(name)) return 'wings';
    if (/glass|oculos|óculos|mask|mascara|máscara|face|rosto/.test(name)) return 'face';
    if (roots[0] === 'head') return 'head';
    if (roots[0] === 'body') return 'body';
    return 'other';
  }
  function newId() { return 'u' + Date.now().toString(36) + Math.floor(Math.random() * 1e9).toString(36); }
  var CHANNELS = ['rotation', 'position', 'scale'];
  /** the keys of a Blockbench keyframe list as plain data (Molang expressions cannot be kept: left out) */
  function keysOf(list) {
    var keys = [];
    (list || []).forEach(function (kf) {
      var dp = kf.data_points && kf.data_points[0]; if (!dp) return;
      var key = { ch: String(kf.channel), t: M.snap(Number(kf.time) || 0), i: kf.interpolation || 'linear' };
      if (CHANNELS.indexOf(kf.channel) >= 0) {
        var v = ['x', 'y', 'z'].map(function (ax) { return dp[ax] === undefined || dp[ax] === '' ? 0 : Number(dp[ax]); });
        if (v.some(function (n) { return !isFinite(n); })) return;
        key.v = v;
      } else {
        var d = {};
        Object.keys(dp).forEach(function (k) { var x = dp[k]; if (typeof x === 'number' || typeof x === 'boolean' || (typeof x === 'string' && x.length < 64)) d[k] = x; });
        key.d = d;
      }
      if (key.i === 'bezier' && Array.isArray(kf.bezier_left_time)) key.bz = { linked: !!kf.bezier_linked, lt: kf.bezier_left_time.slice(), lv: kf.bezier_left_value.slice(), rt: kf.bezier_right_time.slice(), rv: kf.bezier_right_value.slice() };
      keys.push(key);
    });
    return keys;
  }
  /** the animations that move the saved groups (only their tracks) */
  function animsOf(sets) {
    var ids = {}, out = [];
    sets.forEach(function (s) { (function walk(list) { list.forEach(function (n) { if (n.g) { if (n.aid) ids[n.aid] = 1; walk(n.kids || []); } }); })(s.parts); });
    ((G.Animation && G.Animation.all) || []).forEach(function (a) {
      var tracks = [];
      Object.keys(a.animators || {}).forEach(function (uuid) {
        var an = a.animators[uuid];
        if (!ids[uuid] || !an || !an.keyframes) return;
        var keys = keysOf(an.keyframes);
        if (keys.length) tracks.push({ aid: uuid, keys: keys });
      });
      if (tracks.length) out.push({ name: String(a.name || 'anim').slice(0, 64), type: a.cpm_type || 'global', additive: a.cpm_additive !== false, loop: a.loop || 'loop',
        length: Number(a.length) || 1, snapping: Number(a.snapping) || 20, tracks: tracks });
    });
    return out;
  }
  /** the selection as a library item (not stored yet) */
  function makeItem(name, cat, nodes) {
    var list = partsToSave(nodes || M.selNodes());
    if (!list.length) throw new Error(T('lib.save.empty'));
    var sets = [], byRoot = {};
    list.forEach(function (n) {
      var r = rootOf(n), key = r ? r.name : '';
      if (!byRoot[key]) { byRoot[key] = { root: key, anchor: r ? r.origin.slice() : [0, 0, 0], parts: [] }; sets.push(byRoot[key]); }
      byRoot[key].parts.push(toSpec(n, byRoot[key].anchor));
    });
    var item = { v: 1, id: newId(), user: true, name: String(name || list[0].name || 'part').slice(0, 64), cat: 'other', sets: sets, created: Date.now() };
    var anims = animsOf(sets); if (anims.length) item.anims = anims;
    item.cat = CATS.indexOf(cat) >= 0 ? cat : guessCat(item);
    item.thumb = thumbnail(item);
    return item;
  }

  // =================================================================== parts from another .bbmodel (community models, your other projects)
  // body part names other formats use (Figura / Bedrock / Java) -> the CPM body part
  var ROOT_NAMES = { head: 'head', body: 'body', torso: 'body', chest: 'body', rightarm: 'right_arm', right_arm: 'right_arm', leftarm: 'left_arm', left_arm: 'left_arm',
    rightleg: 'right_leg', right_leg: 'right_leg', leftleg: 'left_leg', left_leg: 'left_leg' };
  function rootFromName(n) { return ROOT_NAMES[String(n || '').toLowerCase().replace(/[\s\-]/g, '')] || null; }
  /** a texture of the file as pixels (Promise of {img: ImageData, sx, sy} or null) */
  function decodeTexture(t, res) {
    return new Promise(function (ok) {
      if (!t || typeof t.source !== 'string' || !/^data:image\/(png|jpe?g|gif|webp);base64,/.test(t.source)) { ok(null); return; }
      var im = new Image();
      im.onload = function () {
        try {
          var cv = document.createElement('canvas'); cv.width = im.naturalWidth; cv.height = im.naturalHeight;
          var g = cv.getContext('2d'); g.drawImage(im, 0, 0);
          var uvW = Number(t.uv_width) || (res && Number(res.width)) || im.naturalWidth, uvH = Number(t.uv_height) || (res && Number(res.height)) || im.naturalHeight;
          ok({ img: g.getImageData(0, 0, cv.width, cv.height), sx: cv.width / uvW, sy: cv.height / uvH });
        } catch (e) { ok(null); }
      };
      im.onerror = function () { ok(null); };
      im.src = t.source;
    });
  }
  function cropBlock(src, x, y, w, h) {
    var out = new ImageData(w, h), s = src.data, d = out.data;
    for (var yy = 0; yy < h; yy++) for (var xx = 0; xx < w; xx++) {
      var sx = x + xx, sy = y + yy, i = (yy * w + xx) * 4;
      if (sx < 0 || sy < 0 || sx >= src.width || sy >= src.height) continue;
      var j = (sy * src.width + sx) * 4; d[i] = s[j]; d[i + 1] = s[j + 1]; d[i + 2] = s[j + 2]; d[i + 3] = s[j + 3];
    }
    return out;
  }
  function avgOfImage(img) {
    var d = img.data, r = 0, g = 0, b = 0, n = 0;
    for (var i = 0; i < d.length; i += 4) if (d[i + 3] > 8) { r += d[i]; g += d[i + 1]; b += d[i + 2]; n++; }
    return n ? ((Math.round(r / n) << 16) | (Math.round(g / n) << 8) | Math.round(b / n)) : null;
  }
  /**
   * the groups of a .bbmodel (its JSON) as a library item. o = {groups: [group names or uuids] (default: the whole
   * model), root (CPM body part; default: guessed from the group above, e.g. 'Body'), anchor (that body part's pivot in
   * the file; default the CPM one), name, cat, credit, anims (false = leave the animations out), types ({animation name:
   * CPM type})} -> Promise of {item, skipped: {meshes, molang}}
   */
  function fromBBModel(j, o) {
    o = o || {};
    if (!j || typeof j !== 'object' || !Array.isArray(j.elements) || !Array.isArray(j.outliner)) return Promise.reject(new Error(T('lib.bb.bad')));
    var groupsBy = {}, elBy = {}, skipped = { meshes: 0, molang: 0 };
    (Array.isArray(j.groups) ? j.groups : []).forEach(function (g) { if (g && g.uuid) groupsBy[g.uuid] = g; });
    j.elements.forEach(function (e) { if (e && e.uuid) elBy[e.uuid] = e; });
    // the outliner as a tree of {g (group data), kids, up} / {el}
    function tree(list, up) {
      return (list || []).map(function (n) {
        if (typeof n === 'string') return elBy[n] ? { el: elBy[n], up: up } : null;
        if (!n || typeof n !== 'object') return null;
        var d = n.name !== undefined ? n : (groupsBy[n.uuid] || n), node = { g: d, up: up };
        node.kids = tree(n.children, node);
        return node;
      }).filter(Boolean);
    }
    var all = tree(j.outliner, null), picked = [];
    var want = Array.isArray(o.groups) && o.groups.length ? o.groups.map(String) : null;
    (function find(list) {
      list.forEach(function (n) {
        if (!n.g) { if (!want) picked.push(n); return; }
        if (!want || want.indexOf(String(n.g.name)) >= 0 || want.indexOf(String(n.g.uuid)) >= 0) picked.push(n);
        else find(n.kids);
      });
    })(all);
    if (!picked.length) return Promise.reject(new Error(T('lib.bb.nogroups')));
    // o.root: a body part name, '' = none (as placed in the file), null / undefined = guess from the groups above
    var root = typeof o.root === 'string' ? o.root : null;
    if (root === null) for (var p = picked[0]; p && !root; p = p.up) if (p.g) root = rootFromName(p.g.name);
    root = root || '';
    var anchor = Array.isArray(o.anchor) ? o.anchor : (PIVOT[root] || [0, 0, 0]);
    // o.scale: bigger / smaller around the first group's pivot (o.scalePivot), then moved by o.offset (model units)
    var sc = Number(o.scale) > 0 ? Number(o.scale) : 1, off = Array.isArray(o.offset) ? o.offset : [0, 0, 0], uvDiv = Number(o.uvDiv) > 1 ? Number(o.uvDiv) : 1;
    var SP = Array.isArray(o.scalePivot) ? o.scalePivot : (picked[0].g && Array.isArray(picked[0].g.origin) ? picked[0].g.origin : [0, 0, 0]);
    function tp(v, i) { return Number(SP[i]) + (Number(v[i]) - Number(SP[i])) * sc + (Number(off[i]) || 0); }
    var textures = Array.isArray(j.textures) ? j.textures : [];
    return Promise.all(textures.map(function (t) { return decodeTexture(t, j.resolution); })).then(function (decoded) {
      function rel(v) { return [M.snap(tp(v, 0) - anchor[0]), M.snap(tp(v, 1) - anchor[1]), M.snap(tp(v, 2) - anchor[2])]; }
      function rot(r) { return Array.isArray(r) && (r[0] || r[1] || r[2]) ? [Number(r[0]) || 0, Number(r[1]) || 0, Number(r[2]) || 0] : undefined; }
      var ids = {};
      function cube(e) {
        if (e.type && e.type !== 'cube') { if (e.type === 'mesh') skipped.meshes++; return null; }// locators / null objects: nothing to draw
        if (!Array.isArray(e.from) || !Array.isArray(e.to) || e.export === false) return null;
        var c = { c: 1, n: String(e.name || 'cube').slice(0, 64), f: rel(e.from), t: rel(e.to), o: rel(e.origin || e.from) };
        var r = rot(e.rotation); if (r) c.r = r;
        if (e.inflate) c.inf = M.snap((Number(e.inflate) || 0) * sc);
        if (e.visibility === false) c.vis = false;
        if (e.mirror_uv) c.mirror_uv = true;
        var box = e.box_uv !== undefined ? !!e.box_uv : !!(j.meta && j.meta.box_uv);
        // the texture most faces use
        var count = {}, best = null;
        FACES.forEach(function (k) { var f = e.faces && e.faces[k]; if (f && f.texture !== null && f.texture !== undefined && f.texture !== false) { count[f.texture] = (count[f.texture] || 0) + 1; if (best === null || count[f.texture] > count[best]) best = f.texture; } });
        var dt = best !== null ? decoded[typeof best === 'number' ? best : textures.findIndex(function (t) { return t.uuid === best; })] : null;
        if (!dt) { c.col = 0x9e9e9e; return c; }
        var faces = [], fs = {}, bu = Infinity, bv = Infinity;
        FACES.forEach(function (k) {
          var f = e.faces && e.faces[k];
          if (!f || f.texture === null || f.texture === undefined) { fs[k] = -1; return; }
          if (f.texture === false || f.texture !== best || !Array.isArray(f.uv)) { fs[k] = 0; return; }
          var x1 = Math.min(f.uv[0], f.uv[2]), x2 = Math.max(f.uv[0], f.uv[2]), y1 = Math.min(f.uv[1], f.uv[3]), y2 = Math.max(f.uv[1], f.uv[3]);
          // a face with no UV area shows the one texture pixel under its corner: see-through there = the face is off
          if (x2 - x1 <= 0 || y2 - y1 <= 0) {
            var px0 = Math.max(0, Math.min(dt.img.width - 1, Math.floor(x1 * dt.sx))), py0 = Math.max(0, Math.min(dt.img.height - 1, Math.floor(y1 * dt.sy)));
            fs[k] = dt.img.data[(py0 * dt.img.width + px0) * 4 + 3] < 128 ? -1 : 0;
            return;
          }
          fs[k] = 1;
          faces.push({ face: k, r: [x1, y1, x2, y2], flip: [f.uv[0] > f.uv[2], f.uv[1] > f.uv[3]], rot: box ? 0 : ([90, 180, 270].indexOf(Number(f.rotation)) >= 0 ? Number(f.rotation) : 0) });
          bu = Math.min(bu, x1); bv = Math.min(bv, y1);
        });
        if (!faces.length) { c.col = 0x9e9e9e; return c; }
        if (box && Array.isArray(e.uv_offset)) { bu = Math.min(bu, e.uv_offset[0]); bv = Math.min(bv, e.uv_offset[1]); }
        var biggest = null, ti = typeof best === 'number' ? best : textures.findIndex(function (t) { return t.uuid === best; });
        var tf = faces.map(function (f) {
          var w = Math.max(1, Math.round((f.r[2] - f.r[0]) * dt.sx)), h = Math.max(1, Math.round((f.r[3] - f.r[1]) * dt.sy));
          var block = cropBlock(dt.img, Math.round(f.r[0] * dt.sx), Math.round(f.r[1] * dt.sy), w, h);
          if (!biggest || w * h > biggest.width * biggest.height) biggest = block;
          var t = { face: f.face, rel: box && uvDiv === 1 ? [f.r[0] - bu, f.r[1] - bv, f.r[2] - bu, f.r[3] - bv] : [0, 0, (f.r[2] - f.r[0]) / uvDiv, (f.r[3] - f.r[1]) / uvDiv], flip: f.flip, rot: f.rot, w: w, h: h,
            px: b64(new Uint8Array(block.data.buffer)) };
          // o.uvDiv: the painting keeps all its pixels on a smaller skin area (an HD part); faces that use the same spot of
          // the same texture (a mirrored wing) share one area
          if (uvDiv > 1) { t.hd = true; t.key = 'bb' + ti + ':' + f.r.join(','); }
          return t;
        });
        // a flat plane painted on one side only (Blockbench shows it from both sides, the game does not): the other side
        // gets the same picture, mirrored, like one sheet seen from behind
        var dims = [Math.abs(e.to[0] - e.from[0]), Math.abs(e.to[1] - e.from[1]), Math.abs(e.to[2] - e.from[2])], PAIR = [['east', 'west'], ['up', 'down'], ['north', 'south']];
        for (var ax = 0; ax < 3; ax++) {
          if (dims[ax] > 0.01) continue;
          var pr = PAIR[ax], has = tf.filter(function (q) { return q.face === pr[0] || q.face === pr[1]; });
          if (has.length !== 1) continue;
          var other = has[0].face === pr[0] ? pr[1] : pr[0];
          tf.push(Object.assign({}, has[0], { face: other, flip: ax === 1 ? [has[0].flip[0], !has[0].flip[1]] : [!has[0].flip[0], has[0].flip[1]] }));
          fs[other] = 1;
        }
        c.tex = { box: box && uvDiv === 1, off: box && uvDiv === 1 && Array.isArray(e.uv_offset) ? [e.uv_offset[0] - bu, e.uv_offset[1] - bv] : [0, 0], fs: fs, scale: [dt.sx, dt.sy], faces: tf };
        // a resized box-UV cube no longer matches its box template: every face keeps its own painting (per-face UV)
        if (sc !== 1 && box) c.tex = perFaceOf(c.tex);
        // the colour it gets when it is put on as a colour cube (no skin space)
        var avg = biggest ? avgOfImage(biggest) : null;
        if (avg !== null) c.alt = avg;
        return c;
      }
      function node(n) {
        if (n.el) return cube(n.el);
        var d = n.g, g = { g: 1, n: String(d.name || 'group').slice(0, 64), o: rel(d.origin || [0, 0, 0]), kids: n.kids.map(node).filter(Boolean) };
        var r = rot(d.rotation); if (r) g.r = r;
        if (d.visibility === false) g.vis = false;
        if (d.uuid) { g.aid = String(d.uuid); ids[g.aid] = 1; }
        return g;
      }
      var parts = picked.map(node).filter(Boolean);
      var item = { v: 1, id: newId(), user: true, name: String(o.name || (picked[0].g && picked[0].g.name) || j.name || 'part').slice(0, 64), cat: 'other',
        sets: [{ root: root, anchor: anchor.slice(), parts: parts }], created: Date.now() };
      if (o.credit) item.credit = o.credit;
      // pictures drawn with more pixels than the skin area they get: the part asks for Textura em HD
      if (uvDiv > 1) { var d0 = decoded.filter(Boolean)[0]; item.hd = Math.round(uvDiv * (d0 ? Math.max(d0.sx, d0.sy) : 1)); }
      if (o.anims !== false) {
        var anims = [];
        (Array.isArray(j.animations) ? j.animations : []).forEach(function (a) {
          if (!a || !a.animators) return;
          var tracks = [];
          Object.keys(a.animators).forEach(function (uuid) {
            var an = a.animators[uuid]; if (!ids[uuid] || !an || !Array.isArray(an.keyframes)) return;
            var before = an.keyframes.length, keys = keysOf(an.keyframes);
            skipped.molang += before - keys.length;
            // moves scale with the part
            if (sc !== 1) keys.forEach(function (k) {
              if (k.ch !== 'position' || !k.v) return;
              k.v = k.v.map(function (x) { return M.snap(x * sc); });
              if (k.bz) { k.bz.lv = k.bz.lv.map(function (x) { return x * sc; }); k.bz.rv = k.bz.rv.map(function (x) { return x * sc; }); }
            });
            if (keys.length) tracks.push({ aid: uuid, keys: keys });
          });
          if (!tracks.length) return;
          var nm = String(a.name || 'anim').slice(0, 64), type = (o.types && o.types[nm]) || guessAnimType(nm);
          anims.push({ name: nm, type: type, additive: true, loop: a.loop === 'once' || a.loop === 'hold' ? a.loop : 'loop', length: Number(a.length) || 1, snapping: Number(a.snapping) || 20, tracks: tracks });
        });
        if (anims.length) item.anims = anims;
      }
      item.cat = CATS.indexOf(o.cat) >= 0 ? o.cat : guessCat(item);
      item.thumb = thumbnail(item);
      return { item: item, skipped: skipped };
    });
  }
  /** every group of a .bbmodel, in outliner order: [{uuid, name, depth, cubes (inside it, all levels), root (guessed)}] */
  function bbGroups(j) {
    var groupsBy = {}, out = [];
    (Array.isArray(j.groups) ? j.groups : []).forEach(function (g) { if (g && g.uuid) groupsBy[g.uuid] = g; });
    var isCube = {}; (j.elements || []).forEach(function (e) { if (e && e.uuid && (!e.type || e.type === 'cube')) isCube[e.uuid] = 1; });
    (function walk(list, depth, rootName) {
      (list || []).forEach(function (n) {
        if (!n || typeof n !== 'object') return;
        var d = n.name !== undefined ? n : (groupsBy[n.uuid] || n), r = rootFromName(d.name) || rootName;
        var row = { uuid: String(d.uuid || n.uuid || ''), name: String(d.name || 'group'), depth: depth, cubes: 0, root: r || '' };
        (function count(kids) { (kids || []).forEach(function (k) { if (typeof k === 'string') { if (isCube[k]) row.cubes++; } else if (k && typeof k === 'object') count(k.children); }); })(n.children);
        out.push(row);
        walk(n.children, depth + 1, r);
      });
    })(j.outliner, 0, null);
    return out;
  }
  var bdlg = null;
  /** 'Peca de outro .bbmodel': choose the file, tick the groups to bring, saved in Minhas pecas */
  function importBB() {
    G.Blockbench.import({ extensions: ['bbmodel', 'json'], type: T('lib.bb.file'), readtype: 'text' }, function (files) {
      var f = files && files[0]; if (!f) return;
      var text = typeof f.content === 'string' ? f.content : new TextDecoder().decode(f.content), j = null;
      try { j = JSON.parse(text); } catch (e) { j = null; }
      if (!j || !Array.isArray(j.outliner) || !Array.isArray(j.elements)) { G.Blockbench.showMessageBox({ title: T('lib.bb'), message: T('lib.bb.bad') }); return; }
      openBBPicker(j, String(f.name || 'part').replace(/\.[^.]+$/, '').slice(0, 64));
    });
  }
  function openBBPicker(j, fileName) {
    var rows = bbGroups(j);
    if (!rows.length) { G.Blockbench.showMessageBox({ title: T('lib.bb'), message: T('lib.bb.nogroups') }); return null; }
    var roots = { auto: T('lib.bb.auto') }; ROOTS.forEach(function (r) { roots[r] = rootLabel(r); }); roots.none = T('lib.root.none');
    var nAnims = (Array.isArray(j.animations) ? j.animations : []).length;
    var list = rows.map(function (r) {
      return '<label class="cmp_bb_row" style="padding-left:' + (6 + r.depth * 16) + 'px"><input type="checkbox" data-uuid="' + L.esc(r.uuid) + '"> <span class="nm">' + L.esc(r.name) +
        '</span> <span class="ct">' + L.esc(cubesText(r.cubes) + (r.root ? ' · ' + rootLabel(r.root) : '')) + '</span></label>';
    }).join('');
    bdlg = new G.Dialog({
      id: 'cpmplus_model_lib_bb', title: T('lib.bb'), width: 520, buttons: [T('lib.bb.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('lib.bb.tip')) + '</p>', '<div class="cmp_bb_list">' + list + '</div>'],
      form: {
        name: { label: T('lib.f.name'), type: 'text', value: fileName },
        root: { label: T('lib.f.where'), type: 'select', options: roots, value: 'auto' },
        anims: { label: T('lib.f.anims'), type: 'checkbox', value: true, condition: function () { return nAnims > 0; } }
      },
      onConfirm: function (f) {
        var d = bdlg, ids = [].slice.call(d.object.querySelectorAll('.cmp_bb_list input:checked')).map(function (i) { return i.getAttribute('data-uuid'); });
        if (!ids.length) { G.Blockbench.showQuickMessage(T('lib.bb.pickone'), 2200); return false; }
        var root = f.root === 'auto' ? null : f.root === 'none' ? '' : f.root;
        bdlg = null;
        fromBBModel(j, { groups: ids, root: root, name: String(f.name || fileName).trim() || fileName, anims: f.anims !== false }).then(function (r) {
          return saveUser(r.item).then(function () {
            var notes = [];
            if (r.skipped.meshes) notes.push(T('lib.bb.meshes', r.skipped.meshes));
            if (r.skipped.molang) notes.push(T('lib.bb.molang', r.skipped.molang));
            G.Blockbench.showQuickMessage(T('lib.saved', r.item.name), 2200);
            if (notes.length) G.Blockbench.showMessageBox({ title: T('lib.bb'), message: notes.join('\n') });
            openLibrary('user');
          });
        }).catch(function (e) { G.Blockbench.showMessageBox({ title: T('lib.bb'), message: e && e.message ? e.message : String(e) }); });
      },
      onCancel: function () { bdlg = null; }
    });
    bdlg.show();
    // ticking a group ticks what is inside it too (shown greyed: it comes with the group)
    try {
      var boxes = [].slice.call(bdlg.object.querySelectorAll('.cmp_bb_list input'));
      boxes.forEach(function (b, i) {
        b.addEventListener('change', function () {
          var depth = rows[i].depth;
          for (var k = i + 1; k < rows.length && rows[k].depth > depth; k++) { boxes[k].checked = false; boxes[k].disabled = b.checked; }
        });
      });
    } catch (e) { }
    return bdlg;
  }
  /** a CPM animation type from an animation's name: idle loops play always, walking / flying ones with that pose, the rest are gestures */
  function guessAnimType(n) {
    var s = String(n || '').toLowerCase();
    if (/idle|sway|wag|loop|breath|ambient|default|parado/.test(s)) return 'global';
    if (/walk|andar/.test(s)) return 'walking';
    if (/run|sprint|correr/.test(s)) return 'running';
    if (/fly|flap|flight|glide|voar|voo/.test(s)) return 'creative_flying';
    if (/sneak|crouch|agach/.test(s)) return 'sneaking';
    if (/swim|nadar/.test(s)) return 'swimming';
    return 'gesture';
  }

  // =================================================================== storage (this Blockbench: IndexedDB) + files
  var DB = 'cpmplus_parts', STORE = 'items';
  function db() {
    return new Promise(function (res, rej) {
      if (!window.indexedDB) { rej(new Error('IndexedDB')); return; }
      var rq = window.indexedDB.open(DB, 1);
      rq.onupgradeneeded = function () { var d = rq.result; if (!d.objectStoreNames.contains(STORE)) d.createObjectStore(STORE, { keyPath: 'id' }); };
      rq.onsuccess = function () { res(rq.result); };
      rq.onerror = function () { rej(rq.error || new Error('IndexedDB')); };
    });
  }
  function run(mode, fn) {
    return db().then(function (d) {
      return new Promise(function (res, rej) {
        var t = d.transaction(STORE, mode), r = fn(t.objectStore(STORE));
        t.oncomplete = function () { d.close(); res(r && r.result !== undefined ? r.result : undefined); };
        t.onerror = t.onabort = function () { d.close(); rej(t.error || new Error('IndexedDB')); };
      });
    });
  }
  function listUser() { return run('readonly', function (s) { return s.getAll(); }).then(function (r) { return (r || []).filter(function (it) { return it && it.user; }).sort(function (a, b) { return (a.created || 0) - (b.created || 0); }); }); }
  function saveUser(item) { return run('readwrite', function (s) { return s.put(item); }); }
  function deleteUser(id) { return run('readwrite', function (s) { return s.delete(id); }); }

  // ---- files: a parts file is checked piece by piece (it may come from anyone)
  function num(v, lo, hi) { return typeof v === 'number' && isFinite(v) && v >= lo && v <= hi ? v : null; }
  function vec(v) { if (!Array.isArray(v) || v.length !== 3) return null; var a = v.map(function (x) { return num(x, -4096, 4096); }); return a.some(function (x) { return x === null; }) ? null : a; }
  function rgb(v) { return typeof v === 'number' && isFinite(v) ? (v & 0xFFFFFF) : null; }
  function cleanTex(t) {
    if (!t || typeof t !== 'object' || !Array.isArray(t.faces)) return null;
    var faces = t.faces.slice(0, 6).map(function (f) {
      if (!f || FACES.indexOf(f.face) < 0 || typeof f.px !== 'string') return null;
      var w = f.w | 0, h = f.h | 0; if (w < 1 || h < 1 || w > 1024 || h > 1024) return null;
      var rel = Array.isArray(f.rel) && f.rel.length === 4 ? f.rel.map(function (x) { return num(x, 0, 4096); }) : null;
      if (!rel || rel.some(function (x) { return x === null; }) || rel[2] <= rel[0] || rel[3] <= rel[1]) return null;
      try { if (atob(f.px).length !== w * h * 4) return null; } catch (e) { return null; }
      var o = { face: f.face, rel: rel, flip: [!!(f.flip && f.flip[0]), !!(f.flip && f.flip[1])], rot: [90, 180, 270].indexOf(f.rot) >= 0 ? f.rot : 0, w: w, h: h, px: f.px };
      // a picture shared by several faces (a mirrored wing: one skin area) and a picture drawn in HD stay so
      if (typeof f.key === 'string' && /^[\w.:,|~\-]{1,96}$/.test(f.key)) o.key = f.key;
      if (f.hd === true) o.hd = true;
      return o;
    }).filter(Boolean);
    if (!faces.length) return null;
    var off = Array.isArray(t.off) && t.off.length === 2 ? [num(t.off[0], 0, 4096) || 0, num(t.off[1], 0, 4096) || 0] : [0, 0];
    var fs = null;
    if (t.fs && typeof t.fs === 'object') { fs = {}; FACES.forEach(function (k) { fs[k] = t.fs[k] === 0 ? 0 : t.fs[k] === -1 ? -1 : 1; }); }
    return { box: !!t.box, off: off, fs: fs, faces: faces };
  }
  function cleanNode(n, depth) {
    if (!n || typeof n !== 'object' || depth > 40) return null;
    var name = (typeof n.n === 'string' ? n.n : 'part').replace(/^@+/, '').slice(0, 64) || 'part';
    var r = vec(n.r);
    if (n.g) {
      var g = { g: 1, n: name, o: vec(n.o) || [0, 0, 0], kids: (Array.isArray(n.kids) ? n.kids : []).slice(0, 2000).map(function (k) { return cleanNode(k, depth + 1); }).filter(Boolean) };
      if (r) g.r = r;
      if (n.vis === false) g.vis = false;
      if (n.hidden === true) g.hidden = true;
      if (typeof n.aid === 'string' && /^[\w~.\-]{1,64}$/.test(n.aid)) g.aid = n.aid;
      return g;
    }
    if (n.c) {
      var f = vec(n.f), t = vec(n.t); if (!f || !t) return null;
      var c = { c: 1, n: name, f: f, t: t, o: vec(n.o) || f.slice() };
      if (r) c.r = r;
      var inf = num(n.inf, -16, 16); if (inf) c.inf = inf;
      if (n.vis === false) c.vis = false;
      if (n.mirror_uv === true) c.mirror_uv = true;
      if (n.glow === true) c.glow = true;
      if (n.extrude === true) c.extrude = true;
      if (n.cpm && typeof n.cpm === 'object' && !Array.isArray(n.cpm)) {
        try { var s = JSON.stringify(n.cpm); if (s.length < 4096) { var d = JSON.parse(s); delete d.sid; delete d.vanilla; if (Object.keys(d).length) c.cpm = d; } } catch (e) { }
      }
      var tex = cleanTex(n.tex);
      if (tex) { c.tex = tex; if (rgb(n.recol) !== null) c.recol = rgb(n.recol); if (rgb(n.alt) !== null) c.alt = rgb(n.alt); }
      else c.col = rgb(n.col) !== null ? rgb(n.col) : 0x9e9e9e;
      return c;
    }
    return null;
  }
  function vec3(v) { if (!Array.isArray(v) || v.length !== 3) return null; var a = v.map(function (x) { return num(x, -1e5, 1e5); }); return a.some(function (x) { return x === null; }) ? null : a; }
  var INTERP = ['linear', 'catmullrom', 'bezier', 'step'], KEY_CH = ['rotation', 'position', 'scale', 'cpm_visibility', 'cpm_color'];
  function cleanKey(k) {
    if (!k || typeof k !== 'object' || KEY_CH.indexOf(k.ch) < 0) return null;
    var t = num(k.t, 0, 600); if (t === null) return null;
    var o = { ch: k.ch, t: t, i: INTERP.indexOf(k.i) >= 0 ? k.i : 'linear' };
    if (CHANNELS.indexOf(k.ch) >= 0) { o.v = vec3(k.v); if (!o.v) return null; }
    else {
      if (!k.d || typeof k.d !== 'object') return null;
      o.d = {};
      Object.keys(k.d).slice(0, 8).forEach(function (n) { var x = k.d[n]; if (/^[\w]{1,32}$/.test(n) && (typeof x === 'boolean' || (typeof x === 'number' && isFinite(x)) || (typeof x === 'string' && x.length < 64))) o.d[n] = x; });
    }
    if (o.i === 'bezier' && k.bz && typeof k.bz === 'object') {
      var lt = vec3(k.bz.lt), lv = vec3(k.bz.lv), rt = vec3(k.bz.rt), rv = vec3(k.bz.rv);
      if (lt && lv && rt && rv) o.bz = { linked: !!k.bz.linked, lt: lt, lv: lv, rt: rt, rv: rv };
    }
    return o;
  }
  function cleanAnims(list) {
    if (!Array.isArray(list)) return null;
    var out = list.slice(0, 32).map(function (A) {
      if (!A || typeof A !== 'object' || !Array.isArray(A.tracks)) return null;
      var tracks = A.tracks.slice(0, 512).map(function (tr) {
        if (!tr || typeof tr.aid !== 'string' || !/^[\w~.\-]{1,64}$/.test(tr.aid) || !Array.isArray(tr.keys)) return null;
        var keys = tr.keys.slice(0, 4000).map(cleanKey).filter(Boolean);
        return keys.length ? { aid: tr.aid, keys: keys } : null;
      }).filter(Boolean);
      if (!tracks.length) return null;
      return { name: (typeof A.name === 'string' ? A.name.replace(/^@+/, '') : 'anim').slice(0, 64) || 'anim', type: typeof A.type === 'string' && /^[a-z_]{1,40}$/.test(A.type) ? A.type : 'global',
        additive: A.additive !== false, loop: A.loop === 'once' || A.loop === 'hold' ? A.loop : 'loop', length: num(A.length, 0.05, 32.767) || 1, snapping: Math.round(num(A.snapping, 1, 500) || 20), tracks: tracks };
    }).filter(Boolean);
    return out.length ? out : null;
  }
  /** an item from a file, checked: only known fields, numbers in range, pictures only as PNG data */
  function cleanItem(it) {
    if (!it || typeof it !== 'object') return null;
    var rawSets = Array.isArray(it.sets) ? it.sets : (Array.isArray(it.parts) ? [{ root: it.root, anchor: null, parts: it.parts }] : null);
    if (!rawSets) return null;
    var sets = rawSets.slice(0, 16).map(function (s) {
      if (!s || !Array.isArray(s.parts)) return null;
      var parts = s.parts.slice(0, 2000).map(function (p) { return cleanNode(p, 0); }).filter(Boolean);
      return parts.length ? { root: typeof s.root === 'string' ? s.root.slice(0, 64) : '', anchor: vec(s.anchor) || null, parts: parts } : null;
    }).filter(Boolean);
    if (!sets.length) return null;
    var item = { v: 1, id: newId(), user: true, name: (typeof it.name === 'string' && it.name.trim() ? it.name.trim() : 'part').slice(0, 64),
      cat: CATS.indexOf(it.cat) >= 0 ? it.cat : 'other', sets: sets, created: Date.now() };
    var anims = cleanAnims(it.anims); if (anims) item.anims = anims;
    // pictures drawn with more pixels per skin unit (HD): the skin gets that density when the part goes in
    var hd = num(it.hd, 1, 16); if (hd) item.hd = hd;
    // who made it (shown on the card): plain text, a link only when it is https
    if (it.credit && typeof it.credit === 'object') {
      var cr = {};
      ['author', 'license', 'title'].forEach(function (k) { if (typeof it.credit[k] === 'string' && it.credit[k].trim()) cr[k] = it.credit[k].trim().slice(0, 120); });
      if (typeof it.credit.url === 'string' && /^https:\/\/[^\s"'<>]{1,300}$/.test(it.credit.url)) cr.url = it.credit.url;
      if (Object.keys(cr).length) item.credit = cr;
    }
    item.thumb = okThumb(it.thumb) ? it.thumb : thumbnail(item);
    return item;
  }
  function fileText(items) { return JSON.stringify({ cpmplus_parts: 1, items: items.map(function (it) { var o = Object.assign({}, it); delete o.id; return o; }) }); }
  function exportFile(items) {
    var content = fileText(items);
    G.Blockbench.export({ type: T('lib.file'), extensions: ['json'], name: 'cpm_pecas', content: content });
    return content;
  }
  /** reads a parts file (its text) and stores its parts -> Promise of how many */
  function importText(text) {
    var o; try { o = JSON.parse(text); } catch (e) { throw new Error(T('lib.badfile')); }
    if (!o || !Array.isArray(o.items)) throw new Error(T('lib.badfile'));
    var items = o.items.slice(0, 500).map(cleanItem).filter(Boolean);
    if (!items.length) throw new Error(T('lib.badfile'));
    return items.reduce(function (p, it, i) { it.created = Date.now() + i; return p.then(function () { return saveUser(it); }); }, Promise.resolve()).then(function () { return items.length; });
  }

  // =================================================================== the library window
  var STYLE = 'cmp_library_style';
  function installStyle() {
    if (document.getElementById(STYLE)) return;
    var st = document.createElement('style'); st.id = STYLE;
    st.textContent = [
      '.cmp_lib_tabs{display:flex;gap:6px;margin:2px 0 8px}',
      '.cmp_lib_tabs button{padding:3px 14px;border-radius:4px;border:1px solid var(--color-border);background:var(--color-back);color:var(--color-text);cursor:pointer}',
      '.cmp_lib_tabs button.on{background:var(--color-accent);border-color:var(--color-accent);color:var(--color-accent_text, #fff)}',
      '.cmp_lib_grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:8px;max-height:52vh;overflow-y:auto;padding:2px 2px 4px}',
      '.cmp_lib_card{position:relative;min-width:0;padding:6px 4px 5px;border-radius:6px;border:2px solid transparent;background:var(--color-back);cursor:pointer;text-align:center}',
      '.cmp_lib_card:hover{border-color:var(--color-border)}',
      '.cmp_lib_card.on{border-color:var(--color-accent);background:var(--color-selected)}',
      '.cmp_lib_card img{display:block;width:88px;height:88px;margin:0 auto 3px}',
      '.cmp_lib_card .nm{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:.92em}',
      '.cmp_lib_card .ct{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:.78em;opacity:.65}',
      '.cmp_lib_del{position:absolute;top:2px;right:2px;font-size:18px !important;padding:2px;border-radius:4px;opacity:.5}',
      '.cmp_lib_anim{position:absolute;top:4px;left:4px;font-size:16px !important;color:var(--color-accent);opacity:.9}',
      '.cmp_lib_del:hover{opacity:1;background:var(--color-button);color:var(--color-close, #e53935)}',
      '.cmp_lib_empty{grid-column:1/-1;padding:16px 10px;text-align:center;opacity:.85;line-height:1.5}',
      '.cmp_lib_bar{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0 2px}',
      '.cmp_lib_bar button{display:inline-flex;align-items:center;gap:4px;padding:3px 10px;cursor:pointer}',
      '.cmp_lib_bar button .material-icons{font-size:18px}',
      '.cmp_lib_savepic{display:flex;gap:12px;align-items:center;margin:4px 0 10px}',
      '.cmp_lib_savepic img{width:96px;height:96px;flex:0 0 96px;border-radius:6px;background:var(--color-back)}',
      '.cmp_lib_savepic div{line-height:1.5}',
      '.cmp_bb_list{max-height:44vh;overflow-y:auto;border:1px solid var(--color-border);border-radius:4px;padding:4px 0;margin:4px 0 10px}',
      '.cmp_bb_row{display:flex;align-items:center;gap:6px;padding:2px 8px;cursor:pointer;white-space:nowrap}',
      '.cmp_bb_row:hover{background:var(--color-selected)}',
      '.cmp_bb_row .nm{overflow:hidden;text-overflow:ellipsis}',
      '.cmp_bb_row .ct{opacity:.6;font-size:.85em}',
      '.cmp_bb_row input:disabled + .nm{opacity:.55}'
    ].join('\n');
    document.head.appendChild(st);
    L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
  }
  var dlg = null, view = { tab: 'builtin', pick: null, user: [], where: 'auto', busy: false };
  function find(id) {
    for (var i = 0; i < BUILTIN.length; i++) if (BUILTIN[i].id === id) return BUILTIN[i];
    for (var j = 0; j < view.user.length; j++) if (view.user[j].id === id) return view.user[j];
    return null;
  }
  function whereOptions() {
    var opts = { auto: T('lib.where.auto') }, seen = {};
    ROOTS.forEach(function (r) { if (rootGroup(r)) { opts[r] = rootLabel(r); seen[r] = 1; } });
    if (G.Group) G.Group.all.forEach(function (g) { if (g.parent === 'root' && !seen[g.name] && g.name !== 'auto') { opts[g.name] = String(g.name); seen[g.name] = 1; } });
    return opts;
  }
  /** 'Cabeca' / 'Cabeca, Corpo' / the chosen body part - where the picked part goes */
  function whereText(item, where) {
    var sets = setsOf(item);
    if (sets.length === 1 && where && where !== 'auto') return rootLabel(rootGroup(where) ? where : '');
    return sets.map(function (s) { return rootLabel(rootGroup(s.root) ? s.root : ''); }).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(', ');
  }
  function cardHtml(item) {
    var name = itemName(item), cat = T('lib.cat.' + (CATS.indexOf(item.cat) >= 0 ? item.cat : 'other')), n = countOf(item);
    var by = creditText(item);
    return '<div class="cmp_lib_card' + (view.pick === item ? ' on' : '') + '" data-id="' + L.esc(item.id) + '" title="' + L.esc(name + ' · ' + cat + ' · ' + cubesText(n) + (by ? '\n' + by : '')) + '">' +
      '<img src="' + thumbOf(item) + '" alt="" draggable="false">' +
      '<div class="nm">' + L.esc(name) + '</div><div class="ct">' + L.esc(cat + ' · ' + cubesText(n)) + '</div>' +
      (animCount(item) ? '<i class="material-icons cmp_lib_anim" title="' + L.esc(animsText(animCount(item))) + '">animation</i>' : '') +
      (item.user ? '<i class="material-icons cmp_lib_del" title="' + L.esc(T('lib.del')) + '">delete</i>' : '') + '</div>';
  }
  function renderGrid() {
    var root = dlg && dlg.object; if (!root) return;
    var grid = root.querySelector('.cmp_lib_grid'), bar = root.querySelector('.cmp_lib_bar');
    root.querySelectorAll('.cmp_lib_tabs button').forEach(function (b) { b.classList.toggle('on', b.getAttribute('data-tab') === view.tab); });
    var ut = root.querySelector('.cmp_lib_tabs button[data-tab="user"]');
    if (ut) ut.textContent = T('lib.tab.user') + (view.user.length ? ' (' + view.user.length + ')' : '');
    if (bar) bar.style.display = view.tab === 'user' ? '' : 'none';
    var items = view.tab === 'user' ? view.user : BUILTIN;
    if (grid) grid.innerHTML = items.length ? items.map(cardHtml).join('') : '<div class="cmp_lib_empty">' + L.esc(T('lib.empty')) + '</div>';
    var save = root.querySelector('.cmp_lib_bar button[data-act="save"]');
    if (save) save.disabled = !M.selNodes().length;
    note();
  }
  function note() {
    if (!dlg) return;
    var total = M.allCubes().length;
    if (!view.pick) { M.dialogNote(dlg, T('lib.pick.hint'), total, false); return; }
    var n = countOf(view.pick), na = animCount(view.pick), by = creditText(view.pick);
    M.dialogNote(dlg, T('lib.goes', whereText(view.pick, view.where), cubesText(n) + (na ? ' · ' + animsText(na) : '')) + (by ? ' · ' + by : ''), total + n, false);
  }
  function showPreview(f) {
    if (!view.pick) { M.clearPreview(); return; }
    try { M.preview(previewBoxes(view.pick, { where: f.where, mirror: !!f.mirror }), undefined, { opacity: 0.8 }); } catch (e) { L.warn('library preview failed', e); M.clearPreview(); }
  }
  function pick(item) {
    view.pick = item || null;
    var root = dlg && dlg.object;
    if (root) root.querySelectorAll('.cmp_lib_card').forEach(function (c) { c.classList.toggle('on', !!item && c.getAttribute('data-id') === item.id); });
    // the 'Com animacao' line shows only for a part that has animations (Blockbench 5 checks the form's conditions in
    // form.update(); older versions in updateFormValues())
    if (dlg) {
      view.busy = true;
      try { if (dlg.form && typeof dlg.form.update === 'function') dlg.form.update(); else if (typeof dlg.updateFormValues === 'function') dlg.updateFormValues(); } catch (e) { }
      view.busy = false;
    }
    var f = {}; try { f = dlg ? dlg.getFormResult() || {} : {}; } catch (e) { }
    showPreview(f);
    note();
  }
  function refreshUser() {
    return listUser().then(function (items) {
      view.user = items;
      if (view.pick && view.pick.user && !items.some(function (x) { return x.id === view.pick.id; })) { view.pick = null; M.clearPreview(); }
      renderGrid();
      return items;
    }, function (e) { L.warn('could not read the saved parts', e); G.Blockbench.showQuickMessage(T('lib.dberr', e && e.message ? e.message : String(e)), 4000); return []; });
  }
  function askDelete(item) {
    if (!item) return;
    G.Blockbench.showMessageBox({ title: T('lib.del'), icon: 'delete', message: T('lib.del.ask', itemName(item)), buttons: [T('lib.del'), 'dialog.cancel'], confirm: 0, cancel: 1 }, function (b) {
      if (b !== 0) return;
      deleteUser(item.id).then(refreshUser).catch(function (e) { G.Blockbench.showQuickMessage(T('lib.dberr', String(e)), 4000); });
    });
  }
  function doImport() {
    G.Blockbench.import({ extensions: ['json'], type: T('lib.file'), readtype: 'text' }, function (files) {
      var f = files && files[0]; if (!f) return;
      var text = typeof f.content === 'string' ? f.content : new TextDecoder().decode(f.content);
      var p;
      try { p = importText(text); } catch (e) { G.Blockbench.showMessageBox({ title: T('lib.import'), message: e.message || String(e) }); return; }
      // lang: lib.imported.{one}
      p.then(function (n) { G.Blockbench.showQuickMessage(n === 1 ? T('lib.imported.one') : T('lib.imported', n), 2500); view.tab = 'user'; return refreshUser(); })
        .catch(function (e) { G.Blockbench.showMessageBox({ title: T('lib.import'), message: T('lib.dberr', String(e)) }); });
    });
  }
  function wire() {
    var root = dlg.object;
    root.querySelector('.cmp_lib_tabs').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-tab]'); if (!b) return;
      view.tab = b.getAttribute('data-tab'); L.setOpt('model.lib.tab', view.tab);
      pick(null); renderGrid();
    });
    var grid = root.querySelector('.cmp_lib_grid');
    grid.addEventListener('click', function (e) {
      var card = e.target.closest('.cmp_lib_card'); if (!card) return;
      var item = find(card.getAttribute('data-id'));
      if (e.target.closest('.cmp_lib_del')) { askDelete(item); return; }
      pick(item);
    });
    grid.addEventListener('dblclick', function (e) {
      var card = e.target.closest('.cmp_lib_card'); if (!card || e.target.closest('.cmp_lib_del')) return;
      pick(find(card.getAttribute('data-id')));
      if (view.pick) dlg.confirm();
    });
    root.querySelector('.cmp_lib_bar').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-act]'); if (!b || b.disabled) return;
      var act = b.getAttribute('data-act');
      if (act === 'save') { var d = dlg; dlg = null; M.clearPreview(); try { d.close(); } catch (x) { try { d.hide(); } catch (y) { } } openSave(true); }
      else if (act === 'import') doImport();
      else if (act === 'bb') { var d2 = dlg; dlg = null; M.clearPreview(); try { d2.hide(); } catch (x) { } importBB(); }
      else if (act === 'export') {
        if (!view.user.length) { G.Blockbench.showQuickMessage(T('lib.export.none'), 2500); return; }
        try { exportFile(view.user); } catch (x) { G.Blockbench.showMessageBox({ title: T('lib.export'), message: String(x) }); }
      }
    });
  }
  function openLibrary(tab) {
    if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; }
    view.tab = tab || L.opt('model.lib.tab', 'builtin'); if (view.tab !== 'user') view.tab = 'builtin';
    view.pick = null; view.where = 'auto';
    // lang: lib.tab.{builtin|user}
    dlg = new G.Dialog({
      id: 'cpmplus_model_library', title: T('lib'), width: 780,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('lib.tip')) + '</p>',
        '<div class="cmp_lib_tabs"><button type="button" data-tab="builtin">' + L.esc(T('lib.tab.builtin')) + '</button><button type="button" data-tab="user">' + L.esc(T('lib.tab.user')) + '</button></div>',
        '<div class="cmp_lib_grid"></div>',
        '<div class="cmp_lib_bar">' +
          '<button type="button" data-act="save"><i class="material-icons">bookmark_add</i>' + L.esc(T('lib.save.here')) + '</button>' +
          '<button type="button" data-act="bb"><i class="material-icons">move_to_inbox</i>' + L.esc(T('lib.bb')) + '</button>' +
          '<button type="button" data-act="import"><i class="material-icons">file_upload</i>' + L.esc(T('lib.import')) + '</button>' +
          '<button type="button" data-act="export"><i class="material-icons">file_download</i>' + L.esc(T('lib.export')) + '</button></div>'],
      form: {
        where: { label: T('lib.f.where'), type: 'select', options: whereOptions(), value: 'auto' },
        mirror: { label: T('lib.f.mirror'), type: 'checkbox', value: false },
        // only for a part that comes with animations
        anims: { label: T('lib.f.anims'), type: 'checkbox', value: true, condition: function () { return !!(view.pick && animCount(view.pick)); } },
        // only for a part with HD pictures when the skin has fewer pixels
        hd: { label: T('lib.f.hd'), description: T('lib.f.hd.desc'), type: 'checkbox', value: true, condition: function () { return !!(view.pick && wantsHD(view.pick)); } }
      },
      buttons: [T('lib.put'), 'dialog.cancel'],
      onFormChange: function (f) {
        if (view.busy) return;
        var was = view.where; view.where = f.where;
        // putting a right-arm part on the left arm (and back): mirror it, once per change of place
        if (view.pick && was !== f.where && setsOf(view.pick).length === 1) {
          var want = oppositeSides(setsOf(view.pick)[0].root, f.where);
          if (!!f.mirror !== want) { view.busy = true; try { dlg.setFormValues({ mirror: want }); f = dlg.getFormResult(); } catch (e) { } view.busy = false; }
        }
        showPreview(f); note();
      },
      onConfirm: function (f) {
        if (!view.pick) { G.Blockbench.showQuickMessage(T('lib.pick'), 2200); return false; }
        var item = view.pick;
        M.clearPreview();
        var opts = { where: f.where, mirror: !!f.mirror, anims: f.anims !== false, hd: f.hd !== false };
        function put(o2) {
          var r = insert(item, o2);
          G.Blockbench.showQuickMessage(T('lib.done', itemName(item), cubesText(r.cubes.length)) + (r.anims.length ? ' + ' + animsText(r.anims.length) : ''), 2600);
        }
        try { put(opts); }
        catch (e) {
          var full = (e && e.message) === T('lib.noroom');
          // no free skin space: offer more texture space (the area doubles, nothing moves) or the part as colour cubes
          G.Blockbench.showMessageBox({ title: T('lib'), icon: full ? 'photo_size_select_large' : 'error', message: full ? T('lib.noroom.ask') : (e.message || String(e)),
            buttons: full ? [T('lib.grow'), T('lib.colours'), 'dialog.cancel'] : ['dialog.ok'], confirm: 0, cancel: full ? 2 : 0 }, function (b) {
            if (!full || b === 2) { showPreview(f); return; }
            try { put(Object.assign({}, opts, b === 0 ? { grow: true } : { paint: 'colours' })); } catch (x) { G.Blockbench.showMessageBox({ title: T('lib'), message: x.message || String(x) }); return; }
            var d = dlg; dlg = null; M.clearPreview();
            if (d) { try { d.hide(); } catch (x2) { try { d.close(); } catch (x3) { } } }
          });
          M.clearPreview();
          return false;
        }
        dlg = null;
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    try { wire(); } catch (e) { L.warn('library window', e); }
    renderGrid();
    refreshUser();
    return dlg;
  }

  // ---- 'Salvar na biblioteca'
  var sdlg = null;
  function openSave(back) {
    var item;
    try { item = makeItem('', '', M.selNodes()); }
    catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3500); if (back) openLibrary('user'); return null; }
    var cubes = 0, groups = 0, painted = 0;
    item.sets.forEach(function (s) { (function walk(list) { list.forEach(function (n) { if (n.g) { groups++; walk(n.kids || []); } else if (n.c) { cubes++; if (n.tex) painted++; } }); })(s.parts); });
    var where = item.sets.map(function (s) { return rootLabel(s.root); }).join(', ');
    var cats = {}; CATS.forEach(function (c) { cats[c] = T('lib.cat.' + c); });
    var sum = cubesText(cubes) + (groups ? ' · ' + groupsText(groups) : '') + (animCount(item) ? ' · ' + animsText(animCount(item)) : '') + ' · ' + T('lib.save.where', where);
    // lang: lib.save.painted.{one}
    var paintedText = painted === 1 ? T('lib.save.painted.one') : T('lib.save.painted', painted);
    sdlg = new G.Dialog({
      id: 'cpmplus_model_lib_save', title: T('lib.save'), width: 460,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('lib.save.tip')) + '</p>',
        '<div class="cmp_lib_savepic"><img src="' + item.thumb + '" alt=""><div>' + L.esc(sum) +
          (painted ? '<br>' + L.esc(paintedText) : '') + '</div></div>'],
      form: {
        name: { label: T('lib.f.name'), type: 'text', value: item.name },
        cat: { label: T('lib.f.cat'), type: 'select', options: cats, value: item.cat }
      },
      onConfirm: function (f) {
        item.name = (String(f.name || '').trim() || item.name).slice(0, 64);
        item.cat = CATS.indexOf(f.cat) >= 0 ? f.cat : item.cat;
        sdlg = null;
        saveUser(item).then(function () {
          G.Blockbench.showQuickMessage(T('lib.saved', item.name), 2200);
          if (back) openLibrary('user');
        }, function (e) { G.Blockbench.showMessageBox({ title: T('lib.save'), message: T('lib.dberr', e && e.message ? e.message : String(e)) }); });
      },
      onCancel: function () { sdlg = null; if (back) openLibrary('user'); }
    });
    sdlg.show();
    return sdlg;
  }

  // =================================================================== registration
  api.install = function () {
    installStyle();
    M.register({ id: 'cpmplus_model_library', sec: 'create', icon: 'widgets', key: 'lib', mode: 'edit', cpm: true, spots: ['outliner'], after: { outliner: 'cpmplus_model_shapes' },
      run: function () { openLibrary(); } });
    M.register({ id: 'cpmplus_model_lib_save', sec: 'create', icon: 'bookmark_add', key: 'lib.save', mode: 'edit', cpm: true, need: 'sel', spots: ['cube', 'group'],
      run: function () { openSave(false); } });
  };
  api.uninstall = function () {
    [dlg, sdlg, bdlg].forEach(function (d) { if (d) { try { d.close(); } catch (e) { } } });
    dlg = null; sdlg = null; bdlg = null; M.clearPreview();
  };
  // for QA and the other modules
  api.BUILTIN = BUILTIN.map(function (b) { return b.id; });
  api.builtin = function (id) { for (var i = 0; i < BUILTIN.length; i++) if (BUILTIN[i].id === id) return BUILTIN[i]; return null; };
  api.insert = insert; api.makeItem = makeItem; api.thumbnail = thumbnail; api.thumbOf = thumbOf; api.countOf = countOf; api.previewBoxes = previewBoxes;
  api.listUser = listUser; api.saveUser = saveUser; api.deleteUser = deleteUser; api.exportFile = exportFile; api.importText = importText; api.fileText = fileText;
  api.cleanItem = cleanItem; api.prepare = prepare; api.mirrored = mirrored; api.nameOf = nameOf; api.rootGroup = rootGroup; api.fit = fit; api.whereOptions = whereOptions;
  api.open = openLibrary; api.openSave = openSave; api.dialog = function () { return dlg; }; api.saveDialog = function () { return sdlg; };
  api.fromBBModel = fromBBModel; api.bbGroups = bbGroups; api.openBBPicker = openBBPicker; api.importBB = importBB; api.bbDialog = function () { return bdlg; };
  api.guessAnimType = guessAnimType; api.animCount = animCount;
  api.hdOf = hdOf; api.wantsHD = wantsHD; api.shrink = shrink; api.sideName = sideName; api.planPaint = planPaint;
  api.view = function () { return { tab: view.tab, pick: view.pick ? view.pick.id : null, user: view.user.length, where: view.where }; };
  api.pick = function (id) { pick(find(id)); return !!view.pick; };
  api.refreshUser = refreshUser;
  return api;
})
