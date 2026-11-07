/*
 * CPM Plugin+ "CPM+ Modelar" - Bases prontas: ready-made body bases on the right body parts, with real bone chains
 * (a group per bone, its pivot on the joint):
 * - Taur: a horse body on the body with 4 legs; the front legs in the leg parts (they walk with the player), the back
 *   legs copy the turn of the opposite leg (CPM copy transform, a trot); a tail.
 * - Naga: a snake tail instead of the legs, a chain of 11 segments that gets thinner, a lighter belly.
 * - Digitigrade: legs with the extra backward joint (thigh, shin, ankle, foot), in the leg parts.
 * - Alta / Baixa: longer (16 px) or shorter (8 px) legs with a knee, painted with the skin's own legs (and pants
 *   layer), the head, body and arms moved up / down to match.
 * Colour bases are CPM colour cubes (no skin space). The normal legs are switched off (not exported, hidden) when a
 * base replaces them. A window with cards (picture + name), the colour, a live 3D preview; one Ctrl+Z.
 * Loaded after the modelling engine, model_texture, model_slice and model_library (its card pictures).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T, X = L.core.modules.model_texture, SL = L.core.modules.model_slice;
  var api = {};
  var DEF_PIVOT = { head: [0, 24, 0], body: [0, 24, 0], right_arm: [5, 22, 0], left_arm: [-5, 22, 0], right_leg: [1.9, 12, 0], left_leg: [-1.9, 12, 0] };
  var UPPER = ['head', 'body', 'right_arm', 'left_arm'], LEGS = ['right_leg', 'left_leg'];
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  // lang: bases.b.{taur|naga|digi|tall|short}
  var IDS = ['taur', 'naga', 'digi', 'tall', 'short'];
  var DEF_COLOUR = { taur: 0x8b5a2b, naga: 0x3f8f4f, digi: 0x9a6b44 };

  // =================================================================== helpers
  function rootOf(name) { var all = G.Group && G.Group.all ? G.Group.all : []; for (var i = 0; i < all.length; i++) if (all[i].parent === 'root' && all[i].name === name) return all[i]; return null; }
  function shade(rgb, k) {
    var r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
    function c(v) { return Math.max(0, Math.min(255, Math.round(k >= 1 ? v + (255 - v) * (k - 1) : v * k))); }
    return (c(r) << 16) | (c(g) << 8) | c(b);
  }
  /** a side's name: 'perna' + '_d' (right) / '_e' (left) */
  // lang: bases.side.{r|l}
  function sn(key, side) { return T('bases.n.' + key) + (side ? '_' + T(side > 0 ? 'bases.side.r' : 'bases.side.l') : ''); }
  function C(name, f, t, col, x) { return Object.assign({ c: name, f: f, t: t, col: col }, x || {}); }
  function Gp(name, o, kids, x) { return Object.assign({ g: name, o: o, kids: kids || [] }, x || {}); }
  /** x of a right-side value on side s (+1 right = +X in Blockbench, -1 left) */
  function sx(s, x) { return s * x; }
  function box(s, x0, x1) { return s > 0 ? [x0, x1] : [-x1, -x0]; }

  // =================================================================== the bases (Blockbench coordinates, feet at y 0, front = -Z, right = +X)
  // lang: bases.n.{barrel|chest|tail|leg|knee|thigh|shin|hoof|seg|belly|ankle|foot|meta|layer}
  function taur(col) {
    var D = shade(col, 0.62), H = 0x3b3430;
    function leg(s, z, copy) {
      var x = box(s, 0.5, 3.5), xi = box(s, 0.75, 3.25), px = sx(s, 2);
      return Gp(sn('leg', s), [px, 6, z], [
        C(sn('thigh', s), [x[0], 2.5, z - 1.5], [x[1], 6.5, z + 1.5], col),
        Gp(sn('knee', s), [px, 2.5, z], [C(sn('shin', s), [xi[0], 1, z - 1.25], [xi[1], 2.5, z + 1.25], col), C(sn('hoof', s), [x[0], 0, z - 1.5], [x[1], 1, z + 1.5], H)])
      ], copy ? { copy: copy } : null);
    }
    return {
      hide: LEGS, lift: 0, legPivot: { right_leg: [2, 6, 0], left_leg: [-2, 6, 0] },
      sets: [
        { root: 'body', nodes: [Gp(T('bases.n.barrel'), [0, 12, 0], [
          C(T('bases.n.barrel'), [-4, 5, -3], [4, 12, 14], col),
          C(T('bases.n.chest'), [-3.5, 5.5, -3.5], [3.5, 11.5, -3], shade(col, 1.12)),
          Gp(T('bases.n.tail'), [0, 11, 14], [C(T('bases.n.tail'), [-1, 3, 14], [1, 11, 16], D)], { r: [-35, 0, 0] }),
          // back legs: the turn of the opposite leg (the diagonal pairs of a trot)
          leg(1, 11, 'left_leg'), leg(-1, 11, 'right_leg')
        ])] },
        { root: 'right_leg', nodes: [leg(1, 0)] },
        { root: 'left_leg', nodes: [leg(-1, 0)] }
      ]
    };
  }
  function naga(col) {
    var B = shade(col, 1.35);
    // [pivot, from, to] of each segment, from the hips to the tip (each one inside the one before: a bone chain)
    var S = [
      [[0, 12, 0], [-4, 7, -2], [4, 12, 2]], [[0, 7, 0], [-3.75, 3.5, -2], [3.75, 7, 2.25]], [[0, 3.5, 0.25], [-3.5, 0, -1.75], [3.5, 3.5, 3.5]],
      [[0, 1.75, 3.5], [-3.25, 0, 3.5], [3.25, 3.25, 7.5]], [[0.5, 1.6, 7.5], [-2.5, 0, 7.5], [3.5, 3, 11.5]], [[1, 1.4, 11.5], [-1.75, 0, 11.5], [3.75, 2.75, 15]],
      [[0.75, 1.25, 15], [-1.5, 0, 15], [3, 2.5, 18.5]], [[0, 1.1, 18.5], [-2, 0, 18.5], [2, 2.25, 21.5]], [[-0.75, 1, 21.5], [-2.5, 0, 21.5], [1, 2, 24.5]],
      [[-1.25, 0.75, 24.5], [-2.5, 0, 24.5], [0, 1.5, 27.5]], [[-1.25, 0.5, 27.5], [-2, 0, 27.5], [-0.5, 1, 29.5]]
    ];
    var BELLY = { 0: [[-3, 7.5, -2.5], [3, 12, -2]], 1: [[-2.75, 3.5, -2.5], [2.75, 7, -2]], 2: [[-2.5, 0.5, -2.25], [2.5, 3.5, -1.75]] };
    var node = null;
    for (var i = S.length - 1; i >= 0; i--) {
      var kids = [C(T('bases.n.seg') + '_' + (i + 1), S[i][1], S[i][2], col)];
      if (BELLY[i]) kids.push(C(T('bases.n.belly') + '_' + (i + 1), BELLY[i][0], BELLY[i][1], B));
      if (node) kids.push(node);
      node = Gp(T('bases.n.seg') + '_' + (i + 1), S[i][0], kids);
    }
    node.g = T('bases.n.tail');
    return { hide: LEGS, lift: 0, legPivot: null, sets: [{ root: 'body', nodes: [node] }] };
  }
  function digi(col) {
    var D = shade(col, 0.62);
    function leg(s) {
      var p = sx(s, 1.9), a = box(s, -0.1, 3.9), b = box(s, 0.4, 3.4), c = box(s, 0.65, 3.15);
      // drawn straight down, folded by the joints: thigh forward 20, shin back (net -30), ankle up straight, foot flat
      return Gp(sn('thigh', s), [p, 12, 0], [
        C(sn('thigh', s), [a[0], 7, -2.25], [a[1], 12, 2.25], col),
        Gp(sn('shin', s), [p, 7, 0], [
          C(sn('shin', s), [b[0], 2, -1.5], [b[1], 7, 1.5], col),
          Gp(sn('ankle', s), [p, 2, 0], [
            C(sn('meta', s), [c[0], 0, -1.25], [c[1], 2, 1.25], D),
            Gp(sn('foot', s), [p, 0, 0], [C(sn('foot', s), [b[0], -1, -3.5], [b[1], 0, 1], D)])
          ], { r: [30, 0, 0] })
        ], { r: [-50, 0, 0] })
      ], { r: [20, 0, 0] });
    }
    return { hide: LEGS, lift: 0, legPivot: null, sets: [{ root: 'right_leg', nodes: [leg(1)] }, { root: 'left_leg', nodes: [leg(-1)] }] };
  }
  /** legs of length len (thigh + shin with a knee) painted with the skin's legs (and the pants layer) */
  function legsOf(len) {
    var half = len / 2;
    function leg(s) {
      var p = sx(s, 2), rootName = s > 0 ? 'right_leg' : 'left_leg';
      /**
       * a piece of the leg between y0 and y1 of what you SEE. The two legs touch in the middle (0..4 / -4..0, not the
       * vanilla 0.2 px overlap); the pants layer is inflated on its outer sides only and cut on the inflated box, so
       * nothing overlaps or shares a plane (no flicker): its pieces touch at the knee, its inner side and knee faces
       * are switched off (inside the leg / against the other leg)
       */
      function piece(name, y0, y1, layer) {
        var i = layer === 2 ? 0.25 : 0, x0 = s > 0 ? 0 : -4 - i, x1 = s > 0 ? 4 + i : 0;
        var full = { lo: [x0, -i, -2 - i], hi: [x1, len + i, 2 + i] }, v0 = y0 === 0 ? -i : y0, v1 = y1 === len ? len + i : y1, hide = [];
        if (layer === 2) { hide.push(s > 0 ? 'west' : 'east'); if (y0 > 0) hide.push('down'); if (y1 < len) hide.push('up'); }
        return C(name, [x0 + i, v0 + i, -2], [x1 - i, v1 - i, 2], null, { skin: { root: rootName, layer: layer, full: full, lo: v0, hi: v1, hide: hide }, inf: i });
      }
      return Gp(sn('thigh', s), [p, len, 0], [
        piece(sn('thigh', s), half, len, 1), piece(sn('thigh', s) + '_' + T('bases.n.layer'), half, len, 2),
        Gp(sn('knee', s), [p, half, 0], [piece(sn('shin', s), 0, half, 1), piece(sn('shin', s) + '_' + T('bases.n.layer'), 0, half, 2)])
      ]);
    }
    return { hide: LEGS, lift: len - 12, legPivot: { right_leg: [2, len, 0], left_leg: [-2, len, 0] }, sets: [{ root: 'right_leg', nodes: [leg(1)] }, { root: 'left_leg', nodes: [leg(-1)] }] };
  }
  function specOf(id, col) {
    if (id === 'taur') return taur(col);
    if (id === 'naga') return naga(col);
    if (id === 'digi') return digi(col);
    if (id === 'tall') return legsOf(16);
    return legsOf(8);
  }
  function usesColour(id) { return DEF_COLOUR[id] !== undefined; }

  // =================================================================== skin areas of the legs (tall / short)
  function boxFaceUV(u, v, w, h, d) {
    return { east: [u, v + d, u + d, v + d + h], north: [u + d, v + d, u + d + w, v + d + h], west: [u + d + w, v + d, u + 2 * d + w, v + d + h],
      south: [u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h], up: [u + d + w, v + d, u + d, v], down: [u + d + 2 * w, v, u + d + w, v + d] };
  }
  /** the face uvs of a leg on the skin: layer 1 = the leg (its base cube as Blockbench draws it), 2 = the pants */
  function legUV(rootName, layer) {
    var tex = X.mainTexture(), I = X.uvInfo(tex), legacy = I.uvH < I.uvW - 1e-6, r = rootOf(rootName);
    if (layer === 2) return legacy ? null : boxFaceUV(0, rootName === 'right_leg' ? 32 : 48, 4, 12, 4);
    var base = r && (r.children || []).filter(function (c) { return M.isCube(c) && M.isBase(c) && c.box_uv; })[0];
    if (base) { var out = {}; FACES.forEach(function (f) { out[f] = base.faces[f].uv.slice(); }); return out; }
    if (rootName === 'left_leg' && legacy) {
      // an old 64 x 32 skin: the left leg is the right one mirrored
      var m = boxFaceUV(0, 16, 4, 12, 4), sw = { east: 'west', west: 'east' }, o = {};
      FACES.forEach(function (f) { var q = m[sw[f] || f]; o[f] = [q[2], q[1], q[0], q[3]]; });
      return o;
    }
    return boxFaceUV(rootName === 'right_leg' ? 0 : 16, rootName === 'right_leg' ? 16 : 48, 4, 12, 4);
  }

  // =================================================================== building (one undo entry)
  function cubesIn(g) { return M.cubesOf([g]); }
  function groupsIn(g) { return M.groupsOf([g]); }
  /** the player's own cube of a body part and its 2nd-layer shells (what a base switches off) */
  function vanillaOf(root) {
    var kids = (root.children || []).filter(M.isCube), base = kids.filter(function (c) { return M.isBase(c); })[0];
    return kids.filter(function (c) {
      if (c === base) return true;
      var d = {}; try { d = c.cpm_data ? JSON.parse(c.cpm_data) : {}; } catch (e) { }
      if (d && d.l2) return true;
      // the vanilla 2nd layer: an inflated box around the base cube
      if (!base || (c.inflate || 0) <= (base.inflate || 0)) return false;
      for (var i = 0; i < 3; i++) if (Math.abs(Math.min(c.from[i], c.to[i]) - Math.min(base.from[i], base.to[i])) > 0.01 || Math.abs(Math.max(c.from[i], c.to[i]) - Math.max(base.from[i], base.to[i])) > 0.01) return false;
      return true;
    });
  }
  function shifted(p, d) { return [M.snap(p[0] + d[0]), M.snap(p[1] + d[1]), M.snap(p[2] + d[2])]; }
  function build(id, o) {
    o = o || {};
    if (!M.isCpm()) throw new Error(T('bases.cpm'));
    var col = M.rgbOf(o.colour !== undefined && o.colour !== null && o.colour !== '' ? o.colour : DEF_COLOUR[id] || 0x888888), spec = specOf(id, col);
    var roots = {}; ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'].forEach(function (n) { roots[n] = rootOf(n); });
    var missing = spec.sets.map(function (s) { return s.root; }).concat(spec.hide).filter(function (n) { return !roots[n]; });
    if (missing.length) throw new Error(T('bases.noroot', missing.join(', ')));
    var tex = X.mainTexture(), needSkin = id === 'tall' || id === 'short';
    if (needSkin && !tex) throw new Error(T('bases.notex'));
    // what changes: the switched-off cubes, the moved body parts (everything in them), the legs' pivots
    var moveRoots = spec.lift ? UPPER.filter(function (n) { return roots[n]; }) : [], els = [], grps = [];
    function addEl(c) { if (els.indexOf(c) < 0) els.push(c); }
    function addGr(g) { if (grps.indexOf(g) < 0) grps.push(g); }
    var off = [];
    spec.hide.forEach(function (n) { vanillaOf(roots[n]).forEach(function (c) { off.push(c); addEl(c); }); });
    moveRoots.forEach(function (n) { cubesIn(roots[n]).forEach(addEl); groupsIn(roots[n]).forEach(addGr); });
    if (spec.legPivot) LEGS.forEach(function (n) { addGr(roots[n]); });
    var made = [], madeG = [], tops = [], uv = {};
    if (needSkin) LEGS.forEach(function (n) { uv[n] = { 1: legUV(n, 1), 2: legUV(n, 2) }; });
    M.edit(T('bases'), { outliner: true, elements: els.slice(), groups: grps.slice() }, function () {
      off.forEach(function (c) { c.export = false; c.visibility = false; });
      if (spec.lift) moveRoots.forEach(function (n) { M.shiftGroup(roots[n], [0, spec.lift, 0]); });
      if (spec.legPivot) LEGS.forEach(function (n) { roots[n].origin = spec.legPivot[n].slice(); });
      spec.sets.forEach(function (set) {
        var r = roots[set.root];
        // a body part the user moved: the base follows it (the legs get their new pivot above)
        var d = spec.legPivot && LEGS.indexOf(set.root) >= 0 ? [0, 0, 0] : [r.origin[0] - DEF_PIVOT[set.root][0], r.origin[1] - DEF_PIVOT[set.root][1] - (moveRoots.indexOf(set.root) >= 0 ? spec.lift : 0), r.origin[2] - DEF_PIVOT[set.root][2]];
        set.nodes.forEach(function (n) { var x = make(n, r, d); tops.push(x); });
      });
      function make(n, parent, d) {
        if (n.g !== undefined) {
          var g = M.newGroup(String(n.g), shifted(n.o, d), parent);
          if (n.r) g.rotation = n.r.slice();
          g.cpm_data = JSON.stringify({ base: id });
          if (n.copy) g.cpm_copy_transform = JSON.stringify({ uuid: roots[n.copy].uuid, rx: true });
          madeG.push(g);
          n.kids.forEach(function (k) { make(k, g, d); });
          return g;
        }
        var origin = n.o ? shifted(n.o, d) : shifted([(n.f[0] + n.t[0]) / 2, (n.f[1] + n.t[1]) / 2, (n.f[2] + n.t[2]) / 2], d);
        var c;
        if (n.skin) {
          c = M.newCube({ name: String(n.c), from: shifted(n.f, d), to: shifted(n.t, d), origin: origin, inflate: n.inf || 0, texture: true }, parent);
          var faces = uv[n.skin.root][n.skin.layer];
          if (!faces) { c.remove(); return null; }
          c.box_uv = false; if (c.autouv !== undefined) c.autouv = 0;
          // the piece's part of the whole leg as you see it (the full width and depth, its own height)
          var VB = { lo: n.skin.full.lo, hi: n.skin.full.hi }, PB = { lo: [VB.lo[0], n.skin.lo, VB.lo[2]], hi: [VB.hi[0], n.skin.hi, VB.hi[2]] };
          FACES.forEach(function (f) { c.faces[f].uv = SL.subUV({ uv: faces[f], rotation: 0 }, SL.FMAP[f], VB, PB); c.faces[f].rotation = 0; });
          try { c.applyTexture(tex, true); } catch (e) { }
          (n.skin.hide || []).forEach(function (f) { c.faces[f].texture = null; });
          if (n.skin.layer === 2) c.cpm_data = JSON.stringify({ l2: true });
        } else c = M.newCube({ name: String(n.c), from: shifted(n.f, d), to: shifted(n.t, d), origin: origin, colour: n.col }, parent);
        if (n.r) c.rotation = n.r.slice();
        made.push(c);
        return c;
      }
    }, function () { return { outliner: true, elements: els.concat(made), groups: grps.concat(madeG) }; });
    M.refresh();
    M.selectNodes(tops.filter(Boolean));
    return { groups: madeG, cubes: made, off: off, tops: tops };
  }
  // =================================================================== boxes of a base (3D preview and card pictures)
  function rotAbout(p, r) {
    var T3 = G.THREE;
    return new T3.Matrix4().makeTranslation(p[0], p[1], p[2]).multiply(new T3.Matrix4().makeRotationFromEuler(new T3.Euler(r[0] * M.DEG, r[1] * M.DEG, r[2] * M.DEG, 'ZYX'))).multiply(new T3.Matrix4().makeTranslation(-p[0], -p[1], -p[2]));
  }
  var GREY = 0xb4c0cf, LEGCOL = 0x4a5a8a;
  /** [{from, to, matrix, col, inf}] in model space for a base on the standard body (+ the grey body parts that stay) */
  function boxesOf(id, col, withBody) {
    var spec = specOf(id, col), out = [];
    function walk(n, m) {
      if (n.g !== undefined) { var mm = n.r ? (m ? m.clone().multiply(rotAbout(n.o, n.r)) : rotAbout(n.o, n.r)) : m; n.kids.forEach(function (k) { walk(k, mm); }); return; }
      if (n.skin && n.skin.layer === 2) return;
      out.push({ from: n.f, to: n.t, matrix: m, col: n.skin ? LEGCOL : n.col, inf: n.inf || 0 });
    }
    spec.sets.forEach(function (s) { s.nodes.forEach(function (n) { walk(n, null); }); });
    if (withBody) {
      var dy = spec.lift || 0, B = [[[-4, 24, -4], [4, 32, 4]], [[-4, 12, -2], [4, 24, 2]], [[4, 12, -2], [8, 24, 2]], [[-8, 12, -2], [-4, 24, 2]]];
      B.forEach(function (b) { out.push({ from: [b[0][0], b[0][1] + dy, b[0][2]], to: [b[1][0], b[1][1] + dy, b[1][2]], col: GREY }); });
      if (spec.hide.indexOf('right_leg') < 0) { out.push({ from: [-0.1, 0, -2], to: [3.9, 12, 2], col: GREY }); out.push({ from: [-3.9, 0, -2], to: [0.1, 12, 2], col: GREY }); }
    }
    return out;
  }
  /**
   * the card picture: the parts library's z-buffer picture (same look as its cards) of the base on a grey body. The
   * spec nodes are already in the library's node format (g: group with o / r / kids, c: cube with f / t / col).
   */
  var pics = {};
  function pictureOf(id) {
    if (pics[id] !== undefined) return pics[id];
    var lib = L.core.modules.model_library, url = '';
    try {
      if (lib && typeof lib.thumbnail === 'function') {
        var spec = specOf(id, DEF_COLOUR[id] || 0), parts = [];
        var conv = function (n) {
          if (n.g !== undefined) return { g: n.g, o: n.o, r: n.r, kids: n.kids.map(conv).filter(Boolean) };
          if (n.skin && n.skin.layer === 2) return null;
          return { c: n.c, f: n.f, t: n.t, col: n.skin ? LEGCOL : n.col, inf: n.inf || 0 };
        };
        spec.sets.forEach(function (s) { s.nodes.forEach(function (n) { var x = conv(n); if (x) parts.push(x); }); });
        boxesOf(id, 0, true).filter(function (b) { return b.col === GREY; }).forEach(function (b, i) { parts.push({ c: 'body_' + i, f: b.from, t: b.to, col: GREY }); });
        url = lib.thumbnail({ sets: [{ root: '', anchor: [0, 0, 0], parts: parts }] });
      }
    } catch (e) { L.warn('base picture failed', e); url = ''; }
    pics[id] = url;
    return url;
  }
  function previewOf(id, col) {
    var spec = specOf(id, col), bx = boxesOf(id, col, false).map(function (b) { var i = b.inf || 0; return { from: [b.from[0] - i, b.from[1] - i, b.from[2] - i], to: [b.to[0] + i, b.to[1] + i, b.to[2] + i], matrix: b.matrix, col: b.col }; });
    // the body parts that move with the tall / short legs
    if (spec.lift) [[[-4, 24, -4], [4, 32, 4]], [[-4, 12, -2], [4, 24, 2]], [[4, 12, -2], [8, 24, 2]], [[-8, 12, -2], [-4, 24, 2]]].forEach(function (b) { bx.push({ from: [b[0][0], b[0][1] + spec.lift, b[0][2]], to: [b[1][0], b[1][1] + spec.lift, b[1][2]], col: GREY }); });
    M.preview(bx, undefined, { opacity: 0.8 });
    return bx.length;
  }

  // =================================================================== the window (cards)
  var dlg = null;
  function existing() { var out = []; (G.Group.all || []).forEach(function (g) { try { var d = g.cpm_data ? JSON.parse(g.cpm_data) : null; if (d && d.base && out.indexOf(d.base) < 0) out.push(d.base); } catch (e) { } }); return out; }
  function open() {
    if (!M.isCpm()) { G.Blockbench.showQuickMessage(T('bases.cpm'), 2500); return null; }
    var opts = {}; IDS.forEach(function (id) { opts[id] = T('bases.b.' + id); });
    var cards = IDS.map(function (id) {
      // lang: bases.d.{taur|naga|digi|tall|short}
      return '<div class="cmp_base_card" data-base="' + id + '" title="' + L.esc(T('bases.d.' + id)) + '"><img alt="" src="' + pictureOf(id) + '"><b>' + L.esc(T('bases.b.' + id)) + '</b><span>' + L.esc(T('bases.d.' + id)) + '</span></div>';
    }).join('');
    var have = existing();
    dlg = new G.Dialog({
      id: 'cpmplus_model_bases', title: T('bases'), width: 720, buttons: [T('bases.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('bases.tip')) + '</p>', '<div class="cmp_base_grid">' + cards + '</div>',
        have.length ? '<p class="cmp_base_note">' + L.esc(T('bases.have', have.map(function (b) { return IDS.indexOf(b) >= 0 ? T('bases.b.' + b) : b; }).join(', '))) + '</p>' : ''],
      form: {
        base: { label: T('bases.f.base'), type: 'select', options: opts, value: 'taur' },
        colour: { label: T('bases.f.colour'), type: 'color', value: M.hexOf(DEF_COLOUR.taur), condition: function (f) { return usesColour(f.base); } }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview(); dlg = null;
        try { build(f.base, { colour: f.colour }); G.Blockbench.showQuickMessage(T('bases.done', T('bases.b.' + f.base)), 2200); }
        catch (e) { G.Blockbench.showMessageBox({ title: T('bases'), message: e.message || String(e) }); }
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    var last = 'taur';
    var grid = dlg.object.querySelector('.cmp_base_grid');
    if (grid) grid.addEventListener('click', function (e) {
      var card = e.target && e.target.closest ? e.target.closest('.cmp_base_card') : null; if (!card || !dlg) return;
      dlg.setFormValues({ base: card.getAttribute('data-base') });
    });
    function update(f) {
      // a new base: its own colour
      if (f.base !== last) { last = f.base; if (usesColour(f.base)) { try { dlg.setFormValues({ colour: M.hexOf(DEF_COLOUR[f.base]) }); f = dlg.getFormResult(); } catch (e) { } } }
      [].forEach.call(dlg.object.querySelectorAll('.cmp_base_card'), function (c) { c.classList.toggle('picked', c.getAttribute('data-base') === f.base); });
      var n = 0; try { n = previewOf(f.base, M.rgbOf(f.colour || DEF_COLOUR[f.base] || 0x888888)); } catch (e) { L.warn('base preview failed', e); M.clearPreview(); }
      var spec = specOf(f.base, 0), count = 0; spec.sets.forEach(function (s) { (function walk(list) { list.forEach(function (k) { if (k.g !== undefined) walk(k.kids); else if (!(k.skin && k.skin.layer === 2)) count++; }); })(s.nodes); });
      M.dialogNote(dlg, T('bases.count', count), M.allCubes().length + count, false);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('bases window failed', e); }
    return dlg;
  }
  var STYLE = 'cmp_bases_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = [
        '.cmp_base_grid{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:8px;margin:6px 0 10px}',
        '.cmp_base_card{display:flex;flex-direction:column;align-items:center;gap:2px;padding:6px;border:2px solid var(--color-border);border-radius:6px;cursor:pointer;background:var(--color-back);text-align:center}',
        '.cmp_base_card:hover{border-color:var(--color-accent)}',
        '.cmp_base_card.picked{border-color:var(--color-accent);background:var(--color-selected)}',
        '.cmp_base_card img{width:100%;max-width:120px;aspect-ratio:1;image-rendering:auto}',
        '.cmp_base_card span{font-size:.85em;opacity:.8;line-height:1.2}',
        '.cmp_base_note{opacity:.85}'
      ].join('\n');
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_bases', sec: 'rig', icon: 'accessibility_new', key: 'bases', mode: 'edit', cpm: true, spots: [], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.IDS = IDS.slice(); api.build = build; api.open = open; api.dialog = function () { return dlg; }; api.pictureOf = pictureOf; api.boxesOf = boxesOf; api.specOf = specOf;
  return api;
})
