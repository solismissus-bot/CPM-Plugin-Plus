/*
 * CPM Plugin+ "CPM+ Modelar" - generated library parts with their animations: ears, halo, crown, hats, horns (real
 * and mythical) and tails. Every part is built from anatomy (bones as nested groups with pivots at the joints) and
 * comes with fluid animations baked from smooth periodic curves, overlapping action between joints.
 * Loaded before the library (L.core.modules.model_library reads api.items()).
 */
(function (L) {
  'use strict';
  var M = L.core.modules.model;
  var api = {};

  // =================================================================== helpers
  function sn(v) { return Math.round(v * 1000) / 1000; }
  function lerp(a, b, f) { return a + (b - a) * f; }
  function lerpCol(a, b, f) {
    f = Math.max(0, Math.min(1, f));
    return (Math.round(lerp((a >> 16) & 255, (b >> 16) & 255, f)) << 16) | (Math.round(lerp((a >> 8) & 255, (b >> 8) & 255, f)) << 8) | Math.round(lerp(a & 255, b & 255, f));
  }
  function shade(c, k) { return lerpCol(c, k > 0 ? 0xffffff : 0x000000, Math.abs(k)); }
  // (CPM writes a cube's size as (int)(v * 10) / 10: every size is snapped to 0.1 px here, keeping the centre, a hair
  // over the step so the game's cut lands on it - the shape in Blockbench is the shape in the game)
  function C(n, f, t, col, x) {
    var F = [], T = [];
    for (var k = 0; k < 3; k++) { var a = Math.min(f[k], t[k]), b = Math.max(f[k], t[k]), m = (a + b) / 2, s = Math.max(0.1, Math.round((b - a) * 10) / 10) + 0.004; F.push(sn(m - s / 2)); T.push(sn(m + s / 2)); }
    var c = Object.assign({ c: 1, n: n, f: F, t: T }, x || {}); if (typeof col === 'number') c.col = col; return c;
  }
  function Gp(n, o, r, kids, aid, x) { var g = Object.assign({ g: 1, n: n, o: [sn(o[0]), sn(o[1]), sn(o[2])], kids: kids || [] }, x || {}); if (r && (r[0] || r[1] || r[2])) g.r = r.map(sn); if (aid) g.aid = aid; return g; }
  function smooth(e0, e1, x) { var t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }

  /**
   * a smooth periodic curve through control points [[phase 0..1, value], ...] (Catmull-Rom, wraps around 1 -> 0):
   * the shape of one joint's motion over a cycle
   */
  function curve(pts) {
    var P = pts.slice().sort(function (a, b) { return a[0] - b[0]; }), n = P.length;
    return function (t) {
      t = ((t % 1) + 1) % 1;
      var i = 0; while (i < n - 1 && P[i + 1][0] <= t) i++;
      var p1 = P[i], p2 = i + 1 < n ? P[i + 1] : [P[0][0] + 1, P[0][1]];
      var p0 = i > 0 ? P[i - 1] : [P[n - 1][0] - 1, P[n - 1][1]], p3 = i + 2 < n ? P[i + 2] : i + 2 === n ? [P[0][0] + 1, P[0][1]] : [P[1][0] + 1, P[1][1]];
      var span = p2[0] - p1[0], u = span > 0 ? (t - p1[0]) / span : 0;
      // tangents scaled to the neighbouring spans (non-uniform Catmull-Rom: no overshoot spikes on uneven spacing)
      var m1 = (p2[1] - p0[1]) / (p2[0] - p0[0]) * span, m2 = (p3[1] - p1[1]) / (p3[0] - p1[0]) * span;
      var u2 = u * u, u3 = u2 * u;
      return (2 * u3 - 3 * u2 + 1) * p1[1] + (u3 - 2 * u2 + u) * m1 + (-2 * u3 + 3 * u2) * p2[1] + (u3 - u2) * m2;
    };
  }
  function wave(amp, phase, freq) { return function (t) { return amp * Math.sin(2 * Math.PI * ((freq || 1) * t + (phase || 0))); }; }
  /** f delayed by lag (a part of the cycle): the overlapping action from one joint to the next */
  function late(f, lag) { return function (t) { return f(t - lag); }; }
  /**
   * an animation from motion functions: def = {name, type, length (s), keys (per cycle), fps, joints: {aid: {r: [fx, fy,
   * fz] (degrees, added to the rest turn), p: [fx, fy, fz] (px)}}} where every f is phase (0..1) -> value or a number.
   * -> the library's animation format (catmullrom keys, the last key equal to the first: a seamless loop)
   */
  function bake(def) {
    var n = def.keys || 16, tracks = [];
    Object.keys(def.joints).forEach(function (aid) {
      var j = def.joints[aid], keys = [];
      ['r', 'p'].forEach(function (k) {
        var fs = j[k]; if (!fs) return;
        var ch = k === 'r' ? 'rotation' : 'position';
        for (var i = 0; i <= n; i++) {
          var ph = i / n, v = fs.map(function (f) { return typeof f === 'function' ? f(ph) : (f || 0); });
          keys.push({ ch: ch, t: sn(def.length * ph), i: 'catmullrom', v: v.map(function (x) { return Math.round(x * 100) / 100; }) });
        }
      });
      if (keys.length) tracks.push({ aid: aid, keys: keys });
    });
    return { name: def.name, type: def.type || 'global', additive: true, loop: 'loop', length: def.length, snapping: def.fps || 20, tracks: tracks };
  }

  // =================================================================== HD pictures (shared helpers)
  /** texture pixels per skin pixel the pictures are drawn with (the library offers Textura em HD to match) */
  var HD = 8;
  function rng(seed) { var s = (seed >>> 0) || 1; return function () { s ^= s << 13; s >>>= 0; s ^= s >>> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; }; }
  function ramp(stops, t) {
    if (t <= stops[0][0]) return stops[0][1];
    for (var i = 1; i < stops.length; i++) if (t <= stops[i][0]) return lerpCol(stops[i - 1][1], stops[i][1], (t - stops[i - 1][0]) / (stops[i][0] - stops[i - 1][0]));
    return stops[stops.length - 1][1];
  }
  function b64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }
  /** small smooth noise (value noise, repeatable): 0..1 */
  function noise2(seed) {
    var R = rng(seed), T = []; for (var i = 0; i < 256; i++) T.push(R());
    function h(x, y) { return T[((x * 73856093) ^ (y * 19349663)) & 255]; }
    return function (x, y) {
      var xi = Math.floor(x), yi = Math.floor(y), fx = x - xi, fy = y - yi, sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
      return lerp(lerp(h(xi, yi), h(xi + 1, yi), sx), lerp(h(xi, yi + 1), h(xi + 1, yi + 1), sx), sy);
    };
  }
  var DEG = Math.PI / 180;

  // =================================================================== horns (real and mythical)
  // (from the horn study: a horn is a chain of bones with the same turn at every joint. A constant bend with segments
  // shrinking by g = exp(-bend * cot(alpha)) draws a logarithmic spiral - Thompson's horn: alpha about 75 for a ram,
  // 70 for an ibex; bend plus roll draws a helix - kudu, markhor, a unicorn's twist. Thickness falls with a power of the
  // distance to the tip, t0 * (d / L)^p, p 0.4 - 0.8 for real horns; a section that is not square shows the twist; each
  // box reaches into the one before by the gap its bend opens, (t / 2) * tan(bend / 2). One painted strip per horn runs
  // along all of its segments, so the rings, the colour and the polished tip go on across the joints.)
  function vNorm(v) { var l = Math.hypot(v[0], v[1], v[2]) || 1; return [v[0] / l, v[1] / l, v[2] / l]; }
  function vDot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  function vCross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  // 3 x 3 matrices, row by row; a Blockbench turn [x, y, z] is R = Rz * Ry * Rx (x first, about the fixed axes)
  function mRot(axis, deg) {
    var a = deg * DEG, c = Math.cos(a), s = Math.sin(a);
    return axis === 0 ? [1, 0, 0, 0, c, -s, 0, s, c] : axis === 1 ? [c, 0, s, 0, 1, 0, -s, 0, c] : [c, -s, 0, s, c, 0, 0, 0, 1];
  }
  function mMul(A, B) { var o = []; for (var r = 0; r < 3; r++) for (var c = 0; c < 3; c++) o.push(A[r * 3] * B[c] + A[r * 3 + 1] * B[3 + c] + A[r * 3 + 2] * B[6 + c]); return o; }
  function mT(A) { return [A[0], A[3], A[6], A[1], A[4], A[7], A[2], A[5], A[8]]; }
  function mApply(A, v) { return [A[0] * v[0] + A[1] * v[1] + A[2] * v[2], A[3] * v[0] + A[4] * v[1] + A[5] * v[2], A[6] * v[0] + A[7] * v[1] + A[8] * v[2]]; }
  function eulerOf(R) {
    var b = Math.asin(Math.max(-1, Math.min(1, -R[6])));
    // straight up or down the y turn (gimbal lock): the x turn folds into z
    if (Math.abs(R[6]) > 0.99999) return [0, sn(b / DEG), sn(Math.atan2(-R[1], R[4]) / DEG)];
    return [sn(Math.atan2(R[7], R[8]) / DEG), sn(b / DEG), sn(Math.atan2(R[3], R[0]) / DEG)];
  }
  /** turn by deg about the unit axis k (Rodrigues) */
  function mAxis(k, deg) {
    var a = deg * DEG, c = Math.cos(a), s = Math.sin(a), t = 1 - c, x = k[0], y = k[1], z = k[2];
    return [c + x * x * t, x * y * t - z * s, x * z * t + y * s, y * x * t + z * s, c + y * y * t, y * z * t - x * s, z * x * t - y * s, z * y * t + x * s, c + z * z * t];
  }
  /** the frame whose +y points along d and whose +z leans towards b (the side the horn curls to) */
  function frameOf(d, b) {
    var Y = vNorm(d), bb = vNorm(b), k = vDot(bb, Y), Z = [bb[0] - Y[0] * k, bb[1] - Y[1] * k, bb[2] - Y[2] * k];
    if (Math.hypot(Z[0], Z[1], Z[2]) < 1e-4) Z = Math.abs(Y[2]) < 0.9 ? [0, 0, 1] : [1, 0, 0];
    Z = vNorm(Z); var X = vCross(Y, Z);
    return [X[0], Y[0], Z[0], X[1], Y[1], Z[1], X[2], Y[2], Z[2]];
  }
  /**
   * the geometry of one horn (the right one, head coordinates: +x the player's right, +y up, +z the back of the head).
   * o = {at, dir, curl (the side it bends to), L (length along the horn), t0 (thickness at the root), p (taper), n
   * (segments), turn (total bend, degrees), alpha (spiral angle: segments shrink like a real horn's), drift (degrees per
   * joint, outwards), roll (degrees per joint), bends ([degrees per joint] instead of turn), helix ({r, P, w (degrees per
   * joint), turns, axis, up}: a corkscrew around axis), section ([x, z] shape of the cut), tMin, tines ([{at (0..1 along
   * the horn), len (part of L), dir, curl, n, turn, t (part of the thickness there), p}])}
   * -> {segs: [{s, L, w, d, e, r (turn of its bone), W (frame in the head)}], L, sink}
   */
  function hornGeom(o) {
    var segs = [], n, Ls = [], Q = [], Ltot, i, S0;
    var sec = o.section || [1, 1], sk = Math.sqrt(sec[0] * sec[1]) || 1, sx = sec[0] / sk, sz = sec[1] / sk;
    if (o.helix) {
      var hw = o.helix.w * DEG, a2 = 2 * o.helix.r * Math.sin(hw / 2), b2 = o.helix.P * hw / (2 * Math.PI), Lh = Math.hypot(a2, b2), beta = Math.atan2(a2, b2);
      n = Math.max(2, Math.round(o.helix.turns * 360 / o.helix.w));
      var Qh = mMul(mRot(0, o.helix.w * Math.sin(beta)), mRot(1, o.helix.w * Math.cos(beta)));
      for (i = 0; i < n; i++) { Ls.push(Lh); Q.push(Qh); }
      Ltot = Lh * n;
      // the start frame: the corkscrew's own axis turned onto o.helix.axis, then turned about it so the horn leaves
      // the head as nearly along o.helix.up as it can
      var ax = vNorm([Qh[7] - Qh[5], Qh[2] - Qh[6], Qh[3] - Qh[1]]), A = vNorm(o.helix.axis), cr = vCross(ax, A), cl = Math.hypot(cr[0], cr[1], cr[2]);
      var Ra = cl < 1e-6 ? [1, 0, 0, 0, 1, 0, 0, 0, 1] : mAxis(vNorm(cr), Math.acos(Math.max(-1, Math.min(1, vDot(ax, A)))) / DEG), best = -9;
      for (var ph = 0; ph < 360; ph += 5) {
        var Sx = mMul(mAxis(A, ph), Ra), sc = vDot(mApply(Sx, [0, 1, 0]), vNorm(o.helix.up || [0, 1, 0]));
        if (sc > best) { best = sc; S0 = Sx; }
      }
    } else {
      n = o.n;
      var nb = Math.max(1, n - 1), th = (o.turn || 0) / nb, g = o.alpha ? Math.exp(-Math.abs(th) * DEG / Math.tan(o.alpha * DEG)) : 1;
      var L0 = g < 0.9999 ? o.L * (1 - g) / (1 - Math.pow(g, n)) : o.L / n;
      for (i = 0; i < n; i++) Ls.push(L0 * Math.pow(g, i));
      Ltot = o.L;
      S0 = frameOf(o.dir, o.curl || [0, 0, 1]);
      // outwards (+x, the player's right) seen from the horn's start: which way a turn about its z goes
      var X0 = [S0[0], S0[3], S0[6]], dS = X0[0] > 0 ? -1 : 1;
      for (i = 0; i < n; i++) Q.push(mMul(mRot(0, o.bends ? o.bends[i] || 0 : th), mMul(mRot(2, dS * (o.drift || 0)), mRot(1, o.roll || 0))));
    }
    var tMin = o.tMin || 0.45, sink = o.sink === undefined ? 0.7 : o.sink;
    function tAt(s) { return Math.max(tMin, o.t0 * Math.pow(Math.max(0, (Ltot - s) / Ltot), o.p || 0.7)); }
    var s = 0, W = S0;
    for (i = 0; i < n; i++) {
      if (i) W = mMul(W, Q[i]);
      var t = tAt(s + Ls[i] * 0.5), tPrev = i ? segs[i - 1].t : t;
      // a joint's bend opens a gap on the outside of the bend: this box reaches down into the one before by it
      var bendI = i ? Math.acos(Math.max(-1, Math.min(1, (Q[i][0] + Q[i][4] + Q[i][8] - 1) / 2))) : 0;
      var e = i ? tPrev / 2 * Math.tan(bendI / 2) + 0.12 : sink;
      segs.push({ s: s, L: Ls[i], t: t, w: t * sx, d: t * sz, e: e, r: eulerOf(i ? Q[i] : S0), W: W });
      s += Ls[i];
    }
    return { segs: segs, L: Ltot, sink: sink, tAt: tAt };
  }
  /**
   * the painted strip of a horn (the root at the bottom, the tip at the top; every box of the chain shows the part of
   * it for its stretch of the horn). o.look = {ramp (root -> tip), rings ({k (spacing against the thickness), end (part
   * of the length that has rings), depth}), spiral ({k, slope}: grooves winding round, unicorn), bands ([[from, to]]
   * darker bands), gloss (polished tip), fibre (grain along the horn), facets (crystal), pearls (bumpy antler base)}
   */
  function hornStrip(G, look, Wst, Hst, seed) {
    var Wt = Wst * HD, Ht = Hst * HD, d = new Uint8ClampedArray(Wt * Ht * 4), R = rng(seed), nz = noise2(seed * 3 + 1), Ltot = G.L, rings = [], k, s;
    if (look.rings && look.toon) { for (s = look.rings.k; s < look.rings.end * Ltot; s += look.rings.k) rings.push([s, 1]); }
    else if (look.rings) {
      // growth rings from the root up to 'end': spaced by the horn's thickness there, closer at the root (old horn
      // grows less each year), a little uneven
      s = 0.35;
      while (s < look.rings.end * Ltot) {
        rings.push([s + (R() - 0.5) * 0.12, look.rings.depth || 0.3]);
        s += Math.max(0.3, look.rings.k * G.tAt(s) * (0.55 + 0.45 * s / Ltot) * (0.8 + 0.4 * R()));
      }
    }
    // a faint ring exactly at every joint: the seam reads as one more ridge (not on smooth cones: there it would
    // only point at the steps)
    if (look.joints !== false && !look.toon) G.segs.forEach(function (sg, i) { if (i) rings.push([sg.s, (look.rings ? look.rings.depth || 0.3 : 0.25) * 0.5]); });
    for (var y = 0; y < Ht; y++) {
      var sp = Hst - (y + 0.5) / HD - G.sink, f = Math.max(0, Math.min(1, sp / Ltot));
      if (look.toon) { for (var xt = 0; xt < Wt; xt++) { var ct = toonHorn(look, G, sp, f, (xt + 0.5) / HD - Wst / 2, rings), jt = (y * Wt + xt) * 4; d[jt] = (ct >> 16) & 255; d[jt + 1] = (ct >> 8) & 255; d[jt + 2] = ct & 255; d[jt + 3] = 255; } continue; }
      for (var x = 0; x < Wt; x++) {
        var u = (x + 0.5) / HD - Wst / 2, col = ramp(look.ramp, f);
        // grain along the horn and fine growth lines across it
        col = shade(col, (nz(x * 0.45, sp * 1.1) - 0.5) * (look.fibre || 0.1));
        if (((sp * HD / 3.5) % 1 + 1) % 1 < 0.3) col = shade(col, -0.04 * (1 - f));
        // bands: darker stripes, or (b[3] = a colour) rings of another material - metal bands on a bone horn, with a
        // light edge on each side and a little wear
        if (look.bands) look.bands.forEach(function (b) {
          if (f < b[0] || f > b[1]) return;
          if (typeof b[3] === 'number') {
            var fb = (f - b[0]) / Math.max(1e-6, b[1] - b[0]);
            col = shade(b[3], (fb < 0.12 || fb > 0.88 ? 0.22 : 0) + (nz(x * 0.9 + 3, sp * 3) - 0.5) * 0.18 + 0.12 * Math.cos(u * 1.6));
          } else col = shade(col, b[2] || -0.35);
        });
        // rings: a groove one or two texels deep, the tip side of it a lighter lip; a little wavy across
        for (k = 0; k < rings.length; k++) {
          var dd = (sp - rings[k][0] - 0.1 * Math.sin(u * 2.3 + k * 1.7)) * HD;
          if (dd > -1.4 && dd <= 0) { col = shade(col, -rings[k][1]); break; }
          if (dd > 0 && dd < 1.5) { col = shade(col, 0.4 * rings[k][1]); break; }
        }
        if (look.spiral) {
          var q = ((sp * look.spiral.k + u * look.spiral.slope) % 1 + 1) % 1;
          col = q < 0.16 ? shade(col, -0.2) : q < 0.3 ? shade(col, 0.1) : col;
        }
        if (look.facets) { var fq = ((u * 1.4 + sp * 0.15) % 1 + 1) % 1; col = shade(col, fq < 0.5 ? 0.12 : -0.08); if (fq < 0.06 || Math.abs(fq - 0.5) < 0.04) col = shade(col, 0.35); }
        // the root dirtier (bumps of an antler's base), the tip polished
        if (f < 0.18) col = shade(col, -0.14 * (1 - f / 0.18) * nz(x * 0.8 + 5, sp * 2));
        if (look.pearls && f < 0.4 && nz(x * 0.9 + 9, sp * 2.2) > 0.72) col = shade(col, 0.16);
        if (look.gloss && f > 0.7) col = shade(col, look.gloss * smooth(0.7, 1, f) * (0.55 + 0.45 * Math.cos(u * 1.6)));
        var j = (y * Wt + x) * 4; d[j] = (col >> 16) & 255; d[j + 1] = (col >> 8) & 255; d[j + 2] = col & 255; d[j + 3] = 255;
      }
    }
    return { w: Wt, h: Ht, px: b64(d) };
  }
  /**
   * a cartoon horn's colour: the ramp cut into flat bands along it (look.toon.cuts), across each face a shadow side,
   * the base and a crisp light streak (cel shading, after toon shaders: 2-3 values per material), bold clean rings
   */
  function toonHorn(look, G, sp, f, u, rings) {
    var T = look.toon, cuts = T.cuts || [0.45, 0.8], q = 0;
    while (q < cuts.length && f >= cuts[q]) q++;
    var col = ramp(look.ramp, cuts.length ? (q === 0 ? cuts[0] * 0.5 : q === cuts.length ? (1 + cuts[q - 1]) / 2 : (cuts[q - 1] + cuts[q]) / 2) : f);
    if (T.alt) col = T.alt[q % T.alt.length];
    if (sp < 0.45) col = shade(col, -(T.root || 0.16));
    for (var k = 0; k < rings.length; k++) { var dd = (sp - rings[k][0]) * HD; if (dd > -1.6 && dd <= 0) { col = shade(col, -(T.ring || 0.22)); break; } }
    if (T.tip && f > T.tip[0]) col = T.tip[1];
    return col;
  }
  /**
   * the piece of a painted strip one box needs: whole skin pixels around its stretch, cut once (cached) so the boxes
   * of a chain each take a small area of the skin (they fit in the free corners) while the painting still runs on
   * across the joints, every box showing exactly its own stretch of it
   */
  var cutCache = {};
  function cutPic(key, pic, Wst, x0, y0, wpx, hpx) {
    var ck = key + '@' + x0 + ',' + y0 + ',' + wpx + 'x' + hpx;
    if (cutCache[ck]) return cutCache[ck];
    if (!pic.d) pic.d = unb64(pic.px);
    var W = wpx * HD, H = hpx * HD, d = new Uint8ClampedArray(W * H * 4), sx0 = Math.round(x0 * HD), sy0 = Math.round(y0 * HD);
    for (var y = 0; y < H; y++) {
      var sy = sy0 + y; if (sy < 0 || sy >= pic.h) continue;
      for (var x = 0; x < W; x++) {
        var sx = sx0 + x; if (sx < 0 || sx >= pic.w) continue;
        var i = (y * W + x) * 4, j = (sy * pic.w + sx) * 4;
        d[i] = pic.d[j]; d[i + 1] = pic.d[j + 1]; d[i + 2] = pic.d[j + 2]; d[i + 3] = pic.d[j + 3];
      }
    }
    return (cutCache[ck] = { w: W, h: H, px: b64(d), key: ck });
  }
  function unb64(s) { var bin = atob(s), u8 = new Uint8ClampedArray(bin.length); for (var i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i); return u8; }
  /** the faces of one box of a chain: its stretch [s0, s1] of the strip on the four sides, the ends a square of it */
  function hornTex(key, pic, Wst, Hst, sink, w, dz, s0, s1) {
    var vT = Math.max(0, Hst - sink - s1), vB = Math.min(Hst, Hst - sink - s0), top = Math.max(0, Math.floor(vT)), bot = Math.min(Hst, Math.max(top + 1, Math.ceil(vB)));
    var W = Math.max(1, Math.min(Wst, Math.ceil(Math.max(w, dz) - 0.001))), x0 = (Wst - W) / 2, h = bot - top, cut = cutPic(key, pic, Wst, x0, top, W, h);
    function F(face, ww, v0, v1) {
      var a = Math.max(0, Math.min(h - 0.05, v0 - top)), b = Math.max(a + 0.05, Math.min(h, v1 - top));
      return { face: face, rel: [0, 0, W, h], w: cut.w, h: cut.h, px: cut.px, flip: [false, false], rot: 0, key: cut.key, hd: true,
        sub: [sn(Math.max(0, W / 2 - ww / 2)), sn(a), sn(Math.min(W, W / 2 + ww / 2)), sn(b)] };
    }
    return { box: false, off: [0, 0], faces: [F('north', w, vT, vB), F('south', w, vT, vB), F('east', dz, vT, vB), F('west', dz, vT, vB),
      F('up', w, vT, vT + dz), F('down', w, vB - dz, vB)] };
  }
  /**
   * a horn item: every chain of the spec (the right side, mirrored to the left unless single), painted with one strip
   * per chain kind. spec = {chains: [hornGeom options + {single, name, tines, burr}], look, glow}
   */
  function hornParts(id, spec) {
    var out = [], key0 = 'hn_' + id;
    spec.chains.forEach(function (o, ci) {
      var G = hornGeom(o), maxT = 0;
      G.segs.forEach(function (sg) { maxT = Math.max(maxT, sg.w, sg.d); });
      var Wst = Math.max(1, Math.ceil(maxT * (o.burr ? 1.5 : 1) + 0.01)), Hst = Math.max(1, Math.ceil(G.L + G.sink + 0.01));
      var look = Object.assign({}, spec.look, o.look || {}), pic = hornStrip(G, look, Wst, Hst, 101 + ci * 37 + id.length * 7);
      var key = key0 + '_' + ci, alt = ramp(look.ramp, 0.5), gl = spec.glow ? { glow: true } : {};
      var nm = o.name || 'horn', cnt = 0;
      function cube(n, at, sg, s0, s1, wx, dz, y0, y1) {
        return C(n, [at[0] - wx / 2, y0, at[2] - dz / 2], [at[0] + wx / 2, y1, at[2] + dz / 2], undefined,
          Object.assign({ tex: hornTex(key, pic, Wst, Hst, G.sink, wx, dz, s0, s1), alt: alt }, gl));
      }
      // the bones: each one at the root of its segment, stacked straight up as Blockbench keeps them before turning
      function chain(Gc, at, sOff, first) {
        var top = null, up = null;
        Gc.segs.forEach(function (sg, i) {
          var n = i ? '@seg_' + (++cnt) : first, y = at[1] + sg.s;
          var kids = [cube(i ? n : '@base', at, sg, sOff + sg.s - sg.e, sOff + sg.s + sg.L, sg.w, sg.d, y - sg.e, y + sg.L)];
          // octagon: a second box turned 45 degrees about the segment - round cones without square ledges (not on the
          // root: sunk into the head, its corners would poke out through the forehead)
          if (o.octa && i) {
            var c2 = cube(i ? n : '@base', at, sg, sOff + sg.s - sg.e, sOff + sg.s + sg.L, sg.w, sg.d, y - sg.e, y + sg.L);
            c2.r = [0, 45, 0]; c2.o = [at[0], sn(y + sg.L / 2), at[2]];
            kids.push(c2);
          }
          var g = Gp(n, [at[0], y, at[2]], sg.r, kids);
          sg.node = g;
          if (up) up.kids.push(g); else top = g;
          up = g;
        });
        return top;
      }
      var top = chain(G, o.at, 0, '@' + nm + (o.single ? '' : '_r'));
      // an antler's burr: a rough ring round the base
      if (o.burr) {
        var b0 = G.segs[0], bw = b0.w * 1.45, bd = b0.d * 1.45;
        top.kids.push(cube('@burr', o.at, b0, 0, 0.8, bw, bd, o.at[1] - 0.15, o.at[1] + 0.55));
      }
      // tines: branches on the beam, each a short chain of its own, pointing where the spec says (in the head's frame)
      (o.tines || []).forEach(function (tn) {
        var sa = tn.at * G.L, k = 0; while (k < G.segs.length - 1 && G.segs[k + 1].s <= sa) k++;
        var host = G.segs[k], tl = tn.len * G.L;
        var Gt = hornGeom({ dir: [0, 1, 0], curl: tn.curl || [0, 0, -1], L: tl, t0: (tn.t || 0.7) * G.tAt(sa), p: tn.p || 0.8, n: tn.n || 3, turn: tn.turn || 20, sink: 0.25, tMin: 0.4 });
        // its first bone turned from the beam's frame at that point to the tine's own direction
        Gt.segs[0].r = eulerOf(mMul(mT(host.W), frameOf(tn.dir, tn.curl || [0, 0, -1])));
        var tat = [o.at[0], o.at[1] + sa, o.at[2]];
        host.node.kids.push(chain(Gt, tat, G.L - tl, '@tine'));
      });
      if (!o.single) top.mirror = '@' + nm + '_l';
      out.push(top);
    });
    return out;
  }
  // the horn kinds: dir / curl in the head's frame (+x the player's right, +y up, +z the back of the head); sizes in
  // pixels of an 8 px head; numbers after the horn study (real horns) and the usual fantasy designs built on them
  var HORNS = [
    // demon: swept back over the head, black to crimson, polished tip (study: L 8-14, t0 2-2.5, p 0.7, alpha 65-70,
    // turn 90-140, back 30-50 and out 10-20 at the root, keeled)
    { id: 'horns_demon', look: { ramp: [[0, 0x1a1214], [0.5, 0x2a1518], [0.8, 0x6a1a1d], [1, 0xc8423a]], rings: { k: 1.4, end: 0.55, depth: 0.16 }, gloss: 0.35, fibre: 0.08 },
      chains: [{ at: [2.6, 7.3, -1.2], dir: [0.36, 0.8, 0.48], curl: [0, -0.25, 1], L: 11, t0: 2.2, p: 0.7, n: 9, turn: 125, alpha: 68, drift: 2, section: [0.85, 1.3] }] },
    // devil: two short cones up and a little out
    { id: 'horns_devil', look: { ramp: [[0, 0x4a0b0c], [0.6, 0x9a1d18], [1, 0xe24a3c]], gloss: 0.3, fibre: 0.06, joints: false },
      chains: [{ at: [2.3, 7.5, -2.3], dir: [0.32, 0.95, -0.08], curl: [1, 0, 0], L: 3.8, t0: 1.9, p: 1, n: 6, turn: 32, section: [1, 1], octa: true }] },
    // oni: one horn on the brow, ivory with dark bands, nearly straight (study: L 3-6, t0 1.5-2.5, p 0.9)
    { id: 'horns_oni', look: { ramp: [[0, 0xd9c9a3], [1, 0xf5edda]], bands: [[0.28, 0.36, -0.4], [0.52, 0.58, -0.4]], gloss: 0.2, fibre: 0.08, joints: false },
      chains: [{ single: true, at: [0, 7.4, -2.6], dir: [0, 0.96, -0.28], curl: [0, 0, 1], L: 5.5, t0: 2.4, p: 0.9, n: 9, turn: 14, section: [1, 1], octa: true }] },
    // dragon: a main pair from the back corners of the head swept back, two smaller ones along the side (study: main
    // 10-14, the small 0.35-0.6 of it, fanned 15-25 apart, keeled, ridged)
    { id: 'horns_dragon', look: { ramp: [[0, 0x29292f], [0.5, 0x55524e], [0.85, 0xb2a894], [1, 0xebe3cd]], rings: { k: 1.1, end: 0.7, depth: 0.22 }, gloss: 0.15, fibre: 0.1 },
      chains: [
        { at: [2.8, 7.1, 1.3], dir: [0.36, 0.56, 0.75], curl: [0, -1, 0.3], L: 12, t0: 2.1, p: 0.7, n: 9, turn: 55, alpha: 70, drift: 1.5, section: [0.9, 1.25] },
        // (the small ones sweep back and up along the side of the head, like the main pair - not straight out)
        { name: 'hornlet', at: [3.4, 6.2, 0.2], dir: [0.5, 0.5, 0.71], curl: [0, 1, 0.2], L: 4.6, t0: 1.3, p: 0.8, n: 5, turn: 28, section: [0.9, 1.2] },
        { name: 'hornlow', at: [3.5, 4.8, 1.2], dir: [0.5, 0.36, 0.79], curl: [0, 1, 0.2], L: 3.3, t0: 1.0, p: 0.8, n: 4, turn: 22, section: [0.9, 1.2] }] },
    // unicorn: one straight horn on the forehead leaning forward, grooves winding round it (study: L 8-12, t0 1.5-2,
    // p 1, 20-35 forward; a narwhal's grooves at 67 degrees)
    { id: 'horns_unicorn', look: { ramp: [[0, 0xefe2c9], [0.5, 0xfaf3e4], [1, 0xfffdf8]], spiral: { k: 0.85, slope: 0.55 }, gloss: 0.12, fibre: 0.05, joints: false },
      chains: [{ single: true, at: [0, 7.3, -3.3], dir: [0, 0.9, -0.44], curl: [0, 0, -1], L: 10, t0: 1.8, p: 1, n: 12, turn: 0, roll: 12, section: [1, 1], octa: true }] },
    // ram (bighorn): a full curl back, down round the ear and forward, drifting out; deeper than wide; rings all along,
    // a pale worn tip (study: alpha 78, turn 340-380, 20 per joint, drift 2-3 per joint, t0 3-3.5, p 0.6)
    { id: 'horns_ram', look: { ramp: [[0, 0x6a5946], [0.35, 0x9a8466], [0.75, 0xc6b292], [0.95, 0xdccfb3], [1, 0xe9e1cf]], rings: { k: 0.7, end: 0.93, depth: 0.3 }, fibre: 0.12 },
      chains: [{ at: [3.0, 7.2, 0.4], dir: [0.48, 0.66, 0.58], curl: [0, -0.6, 0.8], L: 15, t0: 3.0, p: 0.6, n: 19, turn: 345, alpha: 78, drift: 3.6, section: [0.85, 1.25] }] },
    // ibex / goat (satyr, faun): a long scimitar curve back with knobbed ridges on the front (study: L 12-16, t0 2.5,
    // p 0.7, alpha 70, turn 120-150, up-back 20, spread 10-15)
    { id: 'horns_ibex', look: { ramp: [[0, 0x4c4134], [0.55, 0x766654], [1, 0xa59580]], rings: { k: 0.55, end: 0.86, depth: 0.36 }, fibre: 0.1 },
      chains: [{ at: [1.6, 7.5, -1.0], dir: [0.18, 0.93, 0.32], curl: [0, 0, 1], L: 13, t0: 2.4, p: 0.7, n: 12, turn: 140, alpha: 70, drift: 1.5, section: [0.8, 1.3] }] },
    // kudu (and markhor's open spiral): a corkscrew up and back in a V, dark with ivory tips (study: open helix r 1.5-2,
    // pitch 5-6, 30-36 per joint, 2-2.5 turns, keel)
    { id: 'horns_kudu', look: { ramp: [[0, 0x3a2b1f], [0.84, 0x4a3828], [0.9, 0xd8ccb4], [1, 0xf3ebda]], fibre: 0.1, gloss: 0.12 },
      chains: [{ at: [1.6, 7.5, 0.6], helix: { r: 1.5, P: 6.2, w: 30, turns: 1.6, axis: [0.42, 0.8, 0.43], up: [0, 1, 0.2] }, t0: 1.7, p: 0.8, section: [1, 1.35] }] },
    // bull (minotaur): out sideways, then up and forward, cream to a black tip (study: L 6-10, t0 2-2.5, p 0.7, out
    // 90, then forward-up 60-90)
    { id: 'horns_bull', look: { ramp: [[0, 0xe6d8bb], [0.68, 0xd0be9b], [0.8, 0x6a5946], [1, 0x1c1714]], fibre: 0.08, gloss: 0.2 },
      chains: [{ at: [3.5, 6.8, -0.3], dir: [1, 0.15, -0.05], curl: [0, 0.8, -0.6], L: 8.5, t0: 2.3, p: 0.7, n: 10, turn: 85, section: [1, 0.85] }] },
    // oryx: long straight spears back over the neck, black, rings on the lower part (study: L 14-20, t0 1.5-2, p 0.9,
    // back 60-70, V 5-10)
    { id: 'horns_oryx', look: { ramp: [[0, 0x1b1816], [1, 0x34302b]], rings: { k: 0.55, end: 0.42, depth: 0.32 }, gloss: 0.18, fibre: 0.08 },
      chains: [{ at: [1.3, 7.6, -0.6], dir: [0.12, 0.45, 0.88], curl: [0, -1, 0.2], L: 15, t0: 1.6, p: 0.9, n: 9, turn: 8, section: [1, 1] }] },
    // deer antlers (also wendigo, Cernunnos, kirin styles): a beam out and back curving forward in a C, tines standing up
    // from it, a rough burr at the base, ivory tips (study: beam 9-12, t0 1.25, p 0.3-0.5, tines G1-G4 at 0.08 / 0.3 /
    // 0.55 / 0.75 of the beam, 0.15 / 0.4 / 0.35 / 0.2 long)
    { id: 'antlers_deer', look: { ramp: [[0, 0x4f3d2c], [0.55, 0x85694c], [0.85, 0xc5b292], [1, 0xf1e8d6]], fibre: 0.16, pearls: true },
      chains: [{ name: 'antler', burr: true, at: [2.3, 7.7, 0.4], dir: [0.66, 0.55, 0.51], curl: [-0.35, 0.3, -0.88], L: 12, t0: 1.55, p: 0.35, n: 8, turn: 110, section: [1, 1],
        tines: [{ at: 0.09, len: 0.17, dir: [0.15, 0.55, -0.82], curl: [0, 1, 0], n: 3, turn: 25, t: 0.75 }, { at: 0.33, len: 0.42, dir: [0.08, 0.98, -0.18], curl: [0, 0, -1], n: 4, turn: 18, t: 0.75 },
          { at: 0.56, len: 0.36, dir: [0.05, 0.99, 0.12], curl: [0, 0, -1], n: 4, turn: 16, t: 0.75 }, { at: 0.77, len: 0.22, dir: [0.1, 0.97, 0.2], curl: [0, 0, -1], n: 3, turn: 12, t: 0.75 }] }] },
    // crystal: glowing shards growing from the head in short straight pieces (fantasy)
    { id: 'horns_crystal', glow: true, look: { ramp: [[0, 0x40246e], [0.5, 0x7a4fd0], [1, 0xd2c0ff]], facets: true, fibre: 0.04 },
      chains: [{ name: 'shard', at: [1.4, 7.3, -1.4], dir: [0.25, 0.95, -0.15], curl: [1, 0, 0], L: 6, t0: 1.6, p: 1, n: 3, turn: 26, roll: 45, section: [1, 1], sink: 0.5 },
        { name: 'shardside', at: [3.0, 7.0, 0.4], dir: [0.55, 0.8, 0.2], curl: [1, -0.2, 0], L: 4.2, t0: 1.3, p: 1, n: 3, turn: 30, roll: 45, section: [1, 1], sink: 0.5 },
        { name: 'shardback', at: [2.2, 7.3, 2.2], dir: [0.3, 0.8, 0.52], curl: [0.4, 0, 1], L: 3.0, t0: 1.0, p: 1, n: 2, turn: 20, roll: 45, section: [1, 1], sink: 0.5 }] }
  ];
  // cartoon horns (toon look: chunky, a few long segments, flat bands, a shadow side and a crisp gloss streak)
  HORNS.push(
    { id: 'horns_devil_toon', look: { ramp: [[0, 0xad113b], [0.5, 0xdb2733], [1, 0xf55b49]], toon: { cuts: [0.55, 0.85] }, joints: false },
      chains: [{ at: [2.4, 7.5, -1.6], dir: [0.42, 0.9, -0.05], curl: [-1, 0, 0], L: 5, t0: 3, p: 0.6, n: 8, turn: 40, section: [1, 1], octa: true, tMin: 0.25 }] },
    { id: 'horns_ram_toon', look: { ramp: [[0, 0xc4a78d], [0.5, 0xf2e2c2], [1, 0xfffceb]], rings: { k: 1.35, end: 0.82 }, toon: { cuts: [0.45, 0.8], ring: 0.2 } },
      chains: [{ at: [3.0, 7.0, 0.4], dir: [0.5, 0.66, 0.56], curl: [0, -0.6, 0.8], L: 13.5, t0: 3.7, p: 0.5, n: 13, turn: 330, alpha: 76, drift: 3.2, section: [0.92, 1.12] }] },
    { id: 'horns_unicorn_toon', look: { ramp: [[0, 0xf2e2c2], [1, 0xfff4dc]], toon: { cuts: [0.17, 0.34, 0.5, 0.67, 0.84], alt: [0xf7e8c8, 0xfcb1cc] }, joints: false },
      chains: [{ single: true, at: [0, 7.4, -3.2], dir: [0, 0.93, -0.37], curl: [0, 0, -1], L: 7, t0: 2.5, p: 0.9, n: 6, turn: 0, roll: 45, section: [1, 1], tMin: 0.4 }] },
    { id: 'horns_dragon_toon', look: { ramp: [[0, 0x4a2d6e], [0.55, 0x7a4fb0], [1, 0xd9c7ff]], toon: { cuts: [0.5, 0.82] }, joints: false },
      chains: [{ at: [2.8, 7.2, 1.0], dir: [0.3, 0.62, 0.72], curl: [0, -1, 0.3], L: 7, t0: 2.8, p: 0.7, n: 5, turn: 44, section: [1, 1.1], octa: true, tMin: 0.7 }] }
  );
  // lang: lib.p.{horns_devil_toon|horns_ram_toon|horns_unicorn_toon|horns_dragon_toon}
  function hornItem(spec, order) { return { id: spec.id, root: 'head', cat: 'head', order: 20 + order, hd: HD, replaces: spec.id, parts: hornParts(spec.id, spec) }; }
  // lang: lib.p.{horns_devil|horns_oni|horns_unicorn|horns_ibex|horns_kudu|horns_bull|horns_oryx|antlers_deer|horns_crystal}
  // lang: lib.n.{horn|horn_r|horn_l|hornlet_r|hornlet_l|hornlow_r|hornlow_l|antler_r|antler_l|shard_r|shard_l|shardside_r|shardside_l|shardback_r|shardback_l|tine|burr}

  // =================================================================== head accessories (ears, halo, crown, hats)
  // (materials as small painted tiles: every box shows a part of its material's tile, picked by where the box is so
  // neighbours differ, and all the boxes of a material share one small area of the skin; parts that grow in one
  // direction - ears, a hat's cone - use strips like the horns)
  function hash01(a, b, c) { var h = Math.sin(a * 12.9898 + b * 78.233 + c * 37.719) * 43758.5453; return h - Math.floor(h); }
  /** a T x T skin-pixel tile painted by fn(u, v (0..1), xt, yt, nz) -> colour, or null (see-through) */
  function tilePic(key, T, seed, fn) {
    var W = T * HD, d = new Uint8ClampedArray(W * W * 4), nz = noise2(seed), sum = [0, 0, 0], n = 0;
    for (var y = 0; y < W; y++) for (var x = 0; x < W; x++) {
      var c = fn((x + 0.5) / W, (y + 0.5) / W, x, y, nz); if (c === null || c === undefined) continue;
      var j = (y * W + x) * 4; d[j] = (c >> 16) & 255; d[j + 1] = (c >> 8) & 255; d[j + 2] = c & 255; d[j + 3] = 255;
      sum[0] += d[j]; sum[1] += d[j + 1]; sum[2] += d[j + 2]; n++;
    }
    return { key: key, T: T, w: W, h: W, px: b64(d), avg: n ? (Math.round(sum[0] / n) << 16) | (Math.round(sum[1] / n) << 8) | Math.round(sum[2] / n) : 0x808080 };
  }
  function gemTile(key, col, seed) {
    return tilePic(key, 2, seed, function (u, v) {
      var dd = Math.abs(u - 0.5) + Math.abs(v - 0.5), ring = Math.floor(dd * 6) % 2;
      var c = shade(col, (ring ? -0.18 : 0.08) - dd * 0.25);
      if (u + v < 0.6) c = shade(c, 0.25);
      if (Math.abs(u - 0.32) < 0.07 && Math.abs(v - 0.3) < 0.07) c = 0xffffff;
      return c;
    });
  }
  var TILES = {}, MAT = {
    // brushed gold: light from the top left, fine brushing lines, a few bright glints and darker wear
    gold: function () { return tilePic('mt_gold', 8, 71, function (u, v, x, y, nz) {
      var c = ramp([[0, 0xf7d873], [0.5, 0xdcaa3c], [1, 0xa3701f]], Math.min(1, v * 0.55 + u * 0.2 + nz(x * 0.15, y * 0.15) * 0.3));
      if (y % 3 === 0) c = shade(c, -0.05);
      if (nz(x * 0.6 + 9, y * 0.6) > 0.83) c = lerpCol(c, 0xfff4c8, 0.7);
      if (nz(x * 0.3 + 3, y * 0.3 + 7) < 0.18) c = shade(c, -0.14);
      return c;
    }); },
    goldGlow: function () { return tilePic('mt_goldglow', 4, 73, function (u, v, x, y, nz) {
      var c = ramp([[0, 0xfff1a6], [0.5, 0xffd24a], [1, 0xf0a020]], Math.min(1, v * 0.6 + nz(x * 0.2, y * 0.2) * 0.4));
      return Math.abs(v - 0.35) < 0.08 ? lerpCol(c, 0xffffff, 0.6) : c;
    }); },
    pearl: function () { return tilePic('mt_pearl', 2, 75, function (u, v) {
      var r2 = (u - 0.5) * (u - 0.5) + (v - 0.5) * (v - 0.5), c = shade(0xf1ece2, -0.9 * r2);
      return (u - 0.35) * (u - 0.35) + (v - 0.33) * (v - 0.33) < 0.01 ? 0xffffff : lerpCol(c, 0xe6dcf2, 0.15 * u);
    }); },
    ruby: function () { return gemTile('mt_ruby', 0xc4122c, 77); },
    sapphire: function () { return gemTile('mt_sapphire', 0x1f4fc4, 78); },
    emerald: function () { return gemTile('mt_emerald', 0x149a5a, 79); },
    // red velvet: soft folds, a faint sheen
    velvet: function () { return tilePic('mt_velvet', 6, 81, function (u, v, x, y, nz) {
      var c = shade(0x8a1022, (nz(x * 0.12, y * 0.05) - 0.5) * 0.45);
      return shade(c, 0.12 * Math.pow(Math.max(0, Math.sin((u * 3 + v) * Math.PI)), 6));
    }); },
    // black silk felt: fine grain, a long soft highlight down the middle (a top hat's sheen)
    silk: function () { return tilePic('mt_silk', 8, 83, function (u, v, x, y, nz) {
      var c = shade(0x18171c, (nz(x * 0.5, y * 0.5) - 0.5) * 0.25);
      return lerpCol(c, 0x5a5866, 0.55 * Math.exp(-Math.pow((u - 0.42) / 0.12, 2)));
    }); },
    satinRed: function () { return tilePic('mt_satin_red', 4, 85, function (u, v, x, y, nz) {
      return shade(0x9c1630, 0.2 * Math.sin(v * 9) + (nz(x * 0.3, y * 0.3) - 0.5) * 0.1);
    }); },
    satinDark: function () { return tilePic('mt_satin_dark', 4, 86, function (u, v, x, y, nz) {
      return shade(0x2a1438, 0.2 * Math.sin(v * 9) + (nz(x * 0.3, y * 0.3) - 0.5) * 0.1);
    }); },
    // purple witch felt: mottled, with fibres
    feltPurple: function () { return tilePic('mt_felt_purple', 8, 87, function (u, v, x, y, nz) {
      return shade(0x4a2a6a, (nz(x * 0.2, y * 0.2) - 0.5) * 0.35 + (nz(x * 1.3, y * 1.3) - 0.5) * 0.12);
    }); },
    // a sewn-on patch: brown cloth, dashed stitches round the edge
    patch: function () { return tilePic('mt_patch', 2, 88, function (u, v, x, y, nz) {
      var c = shade(0x6b4a2a, (nz(x * 0.4, y * 0.4) - 0.5) * 0.3), e = Math.min(u, 1 - u, v, 1 - v);
      if (e < 0.09 && ((x + y) % 4) < 2) c = 0xe8d9a8;
      return c;
    }); },
    // weathered bone: ivory, pores, a few hairline cracks
    bone: function () { return tilePic('mt_bone', 8, 89, function (u, v, x, y, nz) {
      var c = shade(0xe2d5b4, (nz(x * 0.12, y * 0.12) - 0.5) * 0.3 + (nz(x * 0.9, y * 0.9) - 0.5) * 0.1);
      if (nz(x * 1.1 + 4, y * 1.1) > 0.86) c = shade(c, -0.3);
      if (Math.abs(Math.sin(u * 7 + Math.sin(v * 9) * 1.3) - 0.95) < 0.012) c = shade(c, -0.35);
      return c;
    }); },
    dark: function () { return tilePic('mt_dark', 2, 91, function (u, v, x, y, nz) { return shade(0x17110e, (nz(x * 0.4, y * 0.4) - 0.5) * 0.2); }); },
    leather: function () { return tilePic('mt_leather', 4, 93, function (u, v, x, y, nz) {
      var c = shade(0x5b3922, (nz(x * 0.5, y * 0.5) - 0.5) * 0.3);
      if ((v < 0.1 || v > 0.9) && x % 4 < 2) c = 0xc9a777;
      return c;
    }); }
  };
  function tile(name) { return TILES[name] || (TILES[name] = MAT[name]()); }
  /** a box painted with a part of a material tile on every face (faces thinner than 0.02 switched off) */
  function tileTex(tl, dims, seed) {
    var T = tl.T, faces = [], fs = {};
    [['north', 0, 1], ['south', 0, 1], ['east', 2, 1], ['west', 2, 1], ['up', 0, 2], ['down', 0, 2]].forEach(function (q, i) {
      var fw = dims[q[1]], fh = dims[q[2]];
      if (fw < 0.02 || fh < 0.02) { fs[q[0]] = -1; return; }
      var w = Math.min(T, fw), h = Math.min(T, fh), ox = (T - w) * hash01(seed, i, 1), oy = (T - h) * hash01(seed, i, 2);
      faces.push({ face: q[0], rel: [0, 0, T, T], w: tl.w, h: tl.h, px: tl.px, flip: [false, false], rot: 0, key: tl.key, hd: true, sub: [sn(ox), sn(oy), sn(ox + w), sn(oy + h)] });
    });
    return { box: false, off: [0, 0], faces: faces, fs: Object.keys(fs).length ? fs : undefined };
  }
  /** a cube of a material: x = extra fields ({r, o, glow...}) */
  function mbox(n, f, t, mat, x) {
    // materials with a hand-painted palette are painted face by face (pixel art with light and shadow), not tiled
    var pm = PXALIAS[mat] || mat; if (PXMAT[pm]) return pbox(n, f, t, pm, x);
    var tl = tile(mat), dims = [Math.abs(t[0] - f[0]), Math.abs(t[1] - f[1]), Math.abs(t[2] - f[2])];
    return C(n, f, t, undefined, Object.assign({ tex: tileTex(tl, dims, f[0] * 3.1 + f[1] * 1.7 + f[2] * 2.3 + t[0]), alt: tl.avg }, x || {}));
  }
  /** a box centred on c (size s), turned by r about its own centre */
  function cbox(n, c, s, mat, r, x) { return mbox(n, [c[0] - s[0] / 2, c[1] - s[1] / 2, c[2] - s[2] / 2], [c[0] + s[0] / 2, c[1] + s[1] / 2, c[2] + s[2] / 2], mat, Object.assign({ r: r, o: c.slice() }, x || {})); }
  /** a ring of n boxes round the vertical axis through c (radius R, box length along the ring, its cut t x h), twin =
   * a second box turned 45 degrees about the ring (a round cut) */
  function ringBoxes(n, name, c, R, t, h, mat, twin, x) {
    var out = [], l = 2 * Math.PI * R / n * 1.1;
    for (var i = 0; i < n; i++) {
      var a = i * 360 / n, p = [c[0] + R * Math.sin(a * DEG), c[1], c[2] + R * Math.cos(a * DEG)];
      out.push(cbox(name, p, [l, h, t], mat, [0, sn(a), 0], x));
      if (twin) out.push(cbox(name, p, [l, h, t], mat, [45, sn(a), 0], x));
    }
    return out;
  }
  /**
   * a cube spec turned by k quarter turns round the vertical axis through the head's middle (Q): the box keeps its
   * own size, its pivot moves to Q * pivot and its turn becomes Q * turn (only for cubes that are not inside turned
   * groups)
   */
  function quarter(node, k) {
    if (!k || !node.c) return node;
    function pt(p) { var x = p[0], z = p[2]; for (var i = 0; i < k; i++) { var t = x; x = z; z = -t; } return [sn(x), p[1], sn(z)]; }
    var r = node.r || [0, 0, 0], R = mMul(mRot(1, 90 * k), mMul(mRot(2, r[2]), mMul(mRot(1, r[1]), mRot(0, r[0]))));
    var o0 = node.o || [(node.f[0] + node.t[0]) / 2, (node.f[1] + node.t[1]) / 2, (node.f[2] + node.t[2]) / 2], o1 = pt(o0);
    return Object.assign({}, node, { f: [sn(node.f[0] - o0[0] + o1[0]), node.f[1], sn(node.f[2] - o0[2] + o1[2])], t: [sn(node.t[0] - o0[0] + o1[0]), node.t[1], sn(node.t[2] - o0[2] + o1[2])], o: o1, r: eulerOf(R) });
  }

  // ---- halo: a smooth ring of 28 pieces (each twice, turned 45: a round cut), bright gold that glows, floating and
  // turning slowly
  function haloItem() {
    var C0 = [0, 11.6, 0.5], kids = ringBoxes(28, '@halo', C0, 4.6, 0.7, 0.7, 'goldGlow', true, { glow: true });
    var g = Gp('@halo', C0, [-12, 0, 0], kids, 'halo');
    var anim = bake({ name: '@anim_idle', type: 'global', length: 6, keys: 60, fps: 20, joints: { halo: {
      r: [function (t) { return 3 * Math.sin(2 * Math.PI * t); }, function (t) { return 360 * t; }, function (t) { return 2 * Math.sin(2 * Math.PI * (t + 0.25)); }],
      p: [0, function (t) { return 0.35 * Math.sin(2 * Math.PI * t); }, 0] } } });
    return { id: 'halo', root: 'head', cat: 'head', order: 3, hd: HD, replaces: 'halo', parts: [g], anims: [anim] };
  }

  // ---- crown: a band with rounded corners and mouldings, pearls along the rim, eight points (fleurs on the sides,
  // spikes with pearls on the corners), set gems, a velvet cap and two crossing arches with an orb and a cross
  function crownItem() {
    var out = [], y0 = 7.2, y1 = 9.6, k;
    // the band (front, then the same turned for the other three sides)
    var side = [mbox('@band', [-4.25, y0, -4.35], [4.25, y1, -4.05], 'gold'), mbox('@band', [-4.4, y0, -4.55], [4.4, y0 + 0.35, -4.3], 'gold'), mbox('@band', [-4.4, y1 - 0.35, -4.55], [4.4, y1, -4.3], 'gold')];
    for (var px = -3.2; px <= 3.21; px += 1.6) side.push(cbox('@pearl', [px, y1 + 0.18, -4.42], [0.45, 0.45, 0.45], 'pearl', [0, 45, 0]));
    // a fleur on the middle of the side: a stem, a diamond, two leaves, a pearl on top
    side.push(mbox('@spike', [-0.6, y1, -4.38], [0.6, y1 + 1.4, -4.03], 'gold'));
    side.push(cbox('@spike', [0, y1 + 2.0, -4.2], [0.95, 0.95, 0.32], 'gold', [0, 0, 45]));
    side.push(cbox('@leaf', [-0.8, y1 + 0.75, -4.2], [0.95, 0.38, 0.3], 'gold', [0, 0, 38]));
    side.push(cbox('@leaf', [0.8, y1 + 0.75, -4.2], [0.95, 0.38, 0.3], 'gold', [0, 0, -38]));
    side.push(cbox('@pearl', [0, y1 + 2.85, -4.2], [0.55, 0.55, 0.55], 'pearl', [45, 45, 0]));
    // a set gem: an octagon stone in a gold bezel
    function gem(mat, s) {
      return [cbox('@gem', [0, 8.4, -4.45], [s, s, 0.3], mat, [0, 0, 0]), cbox('@gem', [0, 8.4, -4.45], [s, s, 0.3], mat, [0, 0, 45]),
        mbox('@band', [-s / 2 - 0.25, 8.4 + s / 2 + 0.05, -4.5], [s / 2 + 0.25, 8.4 + s / 2 + 0.3, -4.3], 'gold'), mbox('@band', [-s / 2 - 0.25, 8.4 - s / 2 - 0.3, -4.5], [s / 2 + 0.25, 8.4 - s / 2 - 0.05, -4.3], 'gold')];
    }
    var gems = ['ruby', 'sapphire', 'emerald', 'sapphire'];
    for (k = 0; k < 4; k++) side.concat(gem(gems[k], k % 2 ? 1.1 : 1.5)).forEach(function (q) { out.push(quarter(q, k)); });
    // the corners: a turned post and a spike with a pearl
    [[1, -1], [1, 1], [-1, 1], [-1, -1]].forEach(function (s) {
      var cx = s[0] * 4.2, cz = s[1] * 4.2;
      out.push(cbox('@band', [cx, (y0 + y1) / 2, cz], [0.85, y1 - y0, 0.85], 'gold', [0, 45, 0]));
      out.push(cbox('@spike', [cx, y1 + 0.6, cz], [0.7, 1.2, 0.7], 'gold', [0, 45, 0]));
      out.push(cbox('@spike', [cx, y1 + 1.35, cz], [0.42, 0.6, 0.42], 'gold', [0, 45, 0]));
      out.push(cbox('@pearl', [cx, y1 + 1.85, cz], [0.5, 0.5, 0.5], 'pearl', [45, 45, 0]));
    });
    // the velvet cap rising inside the band
    out.push(mbox('@inner', [-3.95, 8, -3.95], [3.95, 9.5, 3.95], 'velvet'), mbox('@inner', [-3.3, 9.5, -3.3], [3.3, 10.5, 3.3], 'velvet'), mbox('@inner', [-2.4, 10.5, -2.4], [2.4, 11.1, 2.4], 'velvet'));
    // two arches crossing over the cap (an ellipse, front to back and side to side), an orb and a cross on top
    function arch(alongX) {
      var n = 12, R = 4.0, H = 2.7, cy = 9.5;
      for (var i = 0; i < n; i++) {
        var a0 = Math.PI * i / n, a1 = Math.PI * (i + 1) / n, p0 = [R * Math.cos(a0), cy + H * Math.sin(a0)], p1 = [R * Math.cos(a1), cy + H * Math.sin(a1)];
        var mx = (p0[0] + p1[0]) / 2, my = (p0[1] + p1[1]) / 2, len = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]) + 0.12, ang = Math.atan2(p1[1] - p0[1], p1[0] - p0[0]) / DEG;
        out.push(alongX ? cbox('@arch', [mx, my, 0], [len, 0.5, 0.6], 'gold', [0, 0, sn(ang)]) : cbox('@arch', [0, my, mx], [0.6, 0.5, len], 'gold', [sn(-ang), 0, 0]));
      }
    }
    arch(true); arch(false);
    out.push(cbox('@orb', [0, 12.75, 0], [1.3, 1.3, 1.3], 'gold', [0, 45, 0]), cbox('@orb', [0, 12.75, 0], [1.3, 1.3, 1.3], 'gold', [45, 0, 0]));
    out.push(mbox('@cross', [-0.18, 13.3, -0.18], [0.18, 14.9, 0.18], 'gold'), mbox('@cross', [-0.6, 14.15, -0.18], [0.6, 14.5, 0.18], 'gold'));
    return { id: 'crown', root: 'head', cat: 'head', order: 4, hd: HD, replaces: 'crown', parts: [Gp('@crown', [0, 8, 0], null, out, 'crown')] };
  }

  // ---- top hat: a 16-sided silk crown with a satin band and a buckle, a brim that curls up at the sides
  function topHatItem() {
    var kids = [], c = [0, 11, 0];
    kids = kids.concat(ringBoxes(16, '@top', c, 3.75, 0.35, 6.6, 'silk'));
    for (var k = 0; k < 8; k++) kids.push(cbox('@top', [0, 14.45, 0], [7.9, 0.32, 1.6], 'silk', [0, sn(k * 22.5), 0]));
    kids = kids.concat(ringBoxes(16, '@band', [0, 8.45, 0], 3.92, 0.2, 1.3, 'satinRed'));
    // the brim: 24 radial pieces, turned up at the sides (about their inner end)
    for (var i = 0; i < 24; i++) {
      var a = i * 15, s = Math.sin(a * DEG), co = Math.cos(a * DEG), curl = 24 * s * s, rin = 3.45, rout = 6.3, l = 2 * Math.PI * 4.9 / 24 * 1.15;
      var p = [rin * s, 7.75, rin * co];
      kids.push(mbox('@brim', [p[0] - l / 2, 7.6, p[2]], [p[0] + l / 2, 7.9, p[2] + (rout - rin)], 'silk', { r: [sn(-curl), sn(a), 0], o: p }));
    }
    // the buckle on the band, on the left
    var bx = -3.98;
    [[8.0, 8.2], [8.7, 8.9]].forEach(function (yy) { kids.push(mbox('@buckle', [bx - 0.12, yy[0], -0.7], [bx + 0.05, yy[1], 0.7], 'gold')); });
    kids.push(mbox('@buckle', [bx - 0.12, 8.0, -0.7], [bx + 0.05, 8.9, -0.5], 'gold'), mbox('@buckle', [bx - 0.12, 8.0, 0.5], [bx + 0.05, 8.9, 0.7], 'gold'));
    return { id: 'top_hat', root: 'head', cat: 'head', order: 5, hd: HD, replaces: 'top_hat', parts: [Gp('@hat', [0, 8, 0], [0, 0, -5], kids, 'hat')] };
  }

  // ---- witch hat: a wavy brim, a felt cone of octagon tiers that bends over in two joints (it sways), a band and a
  // buckle
  function witchHatItem() {
    var base = [], mid = [], tip = [], i;
    // the brim: an octagon plate, then a ring of twelve pieces drooping and waving at the edge (pivoting on their
    // inner end)
    base.push(pbox('@brim', [-5.6, 7.85, -5.6], [5.6, 8.15, 5.6], 'felt'), pbox('@brim', [-5.6, 7.85, -5.6], [5.6, 8.15, 5.6], 'felt', { r: [0, 45, 0], o: [0, 8, 0] }));
    for (i = 0; i < 12; i++) {
      var a = i * 30 + 15, s = Math.sin(a * DEG), co = Math.cos(a * DEG), rin = 5.2, droop = 13 + 7 * Math.sin(2 * a * DEG + 0.6), l = 2 * Math.PI * 6.3 / 12 * 1.18, p = [rin * s, 8.0, rin * co];
      base.push(pbox('@brim', [p[0] - l / 2, 7.86, p[2]], [p[0] + l / 2, 8.14, p[2] + 2.3], 'felt', { r: [sn(droop), sn(a), 0], o: p }));
    }
    // the cone: three smooth tapered pieces (no steps), the upper two in joints that bend it back and to the side, a
    // soft collar at each joint
    // (each piece starts a little inside the one below and a hair wider than its top, so the joints stay closed as the
    // cone bends; the bend grows piece by piece)
    var y1 = 8.3, y2 = 12.2, y3 = 15.2, y4 = 17.8, ys = [8.3, 9.6, 10.9, y2, y3, y4], mid2 = [];
    frustum('@top', [0, 0, 0], y1, 4.0, 2.9, y2 - y1, 'felt', 12).forEach(function (q) { base.push(q); });
    frustum('@top', [0, 0, 0], y2 - 0.2, 2.98, 1.95, y3 - y2 + 0.2, 'felt', 12, { shade: -0.02 }).forEach(function (q) { mid.push(q); });
    frustum('@top', [0, 0, 0], y3 - 0.2, 2.02, 1.05, y4 - y3 + 0.2, 'felt', 12, { shade: -0.04 }).forEach(function (q) { mid2.push(q); });
    frustum('@top', [0, 0, 0], y4 - 0.2, 1.1, 0.1, 2.6, 'felt', 12, { shade: -0.06 }).forEach(function (q) { tip.push(q); });
    // the band and its buckle (a gold frame round a dark middle), two sewn-on patches
    base.push(pbox('@band', [-4.25, 8.15, -4.25], [4.25, 9.35, 4.25], 'satin'), pbox('@band', [-4.25, 8.15, -4.25], [4.25, 9.35, 4.25], 'satin', { r: [0, 45, 0], o: [0, 8.75, 0] }));
    base.push(pbox('@buckle', [-1.0, 9.05, -4.5], [1.0, 9.3, -4.25], 'gold'), pbox('@buckle', [-1.0, 8.2, -4.5], [1.0, 8.45, -4.25], 'gold'),
      pbox('@buckle', [-1.0, 8.45, -4.5], [-0.75, 9.05, -4.25], 'gold'), pbox('@buckle', [0.75, 8.45, -4.5], [1.0, 9.05, -4.25], 'gold'),
      pbox('@buckle', [-0.75, 8.45, -4.32], [0.75, 9.05, -4.26], 'void'));
    base.push(pcbox('@patch', [1.25, 10.6, -3.55], [1.5, 1.4, 0.06], 'patch', [-9, 0, 0]));
    base.push(pbox('@patch', [3.6, 8.16, -4.8], [5.0, 8.2, -3.4], 'patch', { r: [0, -30, 0], o: [4.3, 8.18, -4.1] }));
    var gTip = Gp('@hat_tip', [0, y4, 0], [-14, 0, 18], tip, 'ht');
    var gMid2 = Gp('@hat_mid', [0, y3, 0], [-9, 0, 11], mid2.concat([gTip]), 'hm2');
    var gMid = Gp('@hat_mid', [0, y2, 0], [-6, 0, 6], mid.concat([gMid2]), 'hm');
    var hat = Gp('@hat', [0, 8, 0], [-3, 0, 5], base.concat([gMid]), 'hat');
    var anim = bake({ name: '@anim_idle', type: 'global', length: 3.6, keys: 36, fps: 20, joints: {
      hm: { r: [wave(1.5, 0.1), 0, wave(2, 0)] }, hm2: { r: [wave(2, 0.02), 0, wave(3, -0.08)] }, ht: { r: [wave(3, -0.05), 0, wave(5, -0.16)] } } });
    return { id: 'witch_hat', root: 'head', cat: 'head', order: 6, hd: HD, replaces: 'witch_hat', parts: [hat], anims: [anim] };
  }

  // ---- ears: layered like a real ear (fur outside, the pink inside set in, tufts of white fur), two bones each, with
  // twitches and the ears flattening when crouching or asleep
  function earStrip(key, ramp0, H, W, seed, veins) {
    return paintStrip(W, H, 0.3, H - 0.3, function (u, s, f, x) {
      var col = ramp(ramp0, f), nz0 = hash01(x * 0.37, Math.floor(s * 6), seed);
      col = shade(col, (nz0 - 0.5) * 0.12 + (((x * 7 + Math.floor(s * 3)) % 9) === 0 ? 0.08 : 0));
      if (veins) col = shade(col, -0.22 * Math.exp(-Math.pow(u / 0.18, 2)) - 0.08 * Math.exp(-Math.pow((Math.abs(u) - 0.55) / 0.08, 2)) * f);
      return [col, 1];
    });
  }
  function tuftStrip(H, seed) {
    var nz = noise2(seed);
    return paintStrip(1, H, 0, H, function (u, s, f, x) {
      // strands: thin wisps narrowing towards the top
      var w = 0.5 * (1 - f * 0.85), strand = nz(x * 0.9, s * 0.4) > 0.35 + 0.4 * f;
      if (Math.abs(u) > w || !strand) return null;
      return [shade(0xfbf7f0, -0.15 * (1 - f)), 1];
    });
  }
  function catEarsItem() {
    var H = 4, n = 11, fur = earStrip('ce_fur', [[0, 0xc8701f], [0.7, 0xdf8a35], [1, 0x8a4a16]], H, 4, 3), pink = earStrip('ce_pink', [[0, 0xf2a3b4], [1, 0xf6c0c9]], 3, 3, 5, true), tuft = tuftStrip(3, 7);
    var root = [2.4, 8, -0.4], lower = [], upper = [];
    for (var k = 0; k < n; k++) {
      var y0 = 8 + 0.32 * k - (k ? 0.02 : 0.3), y1 = 8 + 0.32 * (k + 1), w = 0.3 + 3.7 * Math.pow(1 - k / n, 1.1), d = lerp(1.2, 0.45, k / (n - 1)), cx = root[0] - 0.05 * k, cz = root[2] + 0.02 * k;
      var c = C(k ? '@seg_' + k : '@base', [cx - w / 2, y0, cz - d / 2], [cx + w / 2, y1, cz + d / 2], undefined, { tex: hornTex('ce_fur', fur, 4, H, 0.3, w, d, y0 - 8, y1 - 8), alt: 0xd27a28 });
      (k < 6 ? lower : upper).push(c);
      if (k >= 1 && k <= 8) {
        var pw = w * 0.6, z0 = cz - d / 2 - 0.04;
        var pc = C('@inner', [cx - pw / 2, y0 + 0.02, z0], [cx + pw / 2, y1, z0 + 0.12], undefined, { tex: hornTex('ce_pink', pink, 3, 3, 0.3, pw, 0.12, y0 - 8 - 0.32, y1 - 8 - 0.32), alt: 0xf2a8b6 });
        (k < 6 ? lower : upper).push(pc);
      }
    }
    // three tufts of white fur from the bottom of the inside, fanning up and out
    for (var j = 0; j < 3; j++) {
      var tx = root[0] - 0.55 + 0.5 * j, tz = root[2] - 0.7;
      lower.push(C('@fur', [tx - 0.35, 8.1, tz], [tx + 0.35, 10.3, tz + 0.01], undefined,
        { tex: cardTex('ce_tuft', tuft, 1, 3, 0, 0.7, 0.1, 2.3), alt: 0xf4efe6, r: [-8, 0, sn((j - 1) * 16)], o: [tx, 8.1, tz] }));
    }
    var tip = Gp('@ear_tip_r', [root[0] - 0.3, 8 + 0.32 * 6, root[2]], null, upper, 'eart');
    var ear = Gp('@ear_r', root, [-8, 0, -14], lower.concat([tip]), 'ear', { mirror: '@ear_l' });
    // twitches: the right ear at 22 % of the cycle, the left at 68 % (one track per side); a slow swivel under it
    function tw(at) { return function (t) { var dt = ((t - at) % 1 + 1) % 1; return 11 * Math.exp(-Math.pow((dt - 0.03) / 0.022, 2)) + 2 * Math.sin(2 * Math.PI * t); }; }
    function sides(name, type, len, keys, jr, jl) {
      var a = bake({ name: name, type: type, length: len, keys: keys, fps: 20, joints: jr }), b = bake({ name: name, type: type, length: len, keys: keys, fps: 20, joints: jl });
      a.tracks.forEach(function (t) { t.side = 'r'; }); b.tracks.forEach(function (t) { t.side = 'l'; a.tracks.push(t); });
      return a;
    }
    var idle = sides('@anim_idle', 'global', 5, 50, { ear: { r: [0, wave(2, 0.1), tw(0.22)] }, eart: { r: [function (t) { return 0.4 * tw(0.24)(t); }, 0, 0] } },
      { ear: { r: [0, wave(2, 0.4), tw(0.68)] }, eart: { r: [function (t) { return 0.4 * tw(0.7)(t); }, 0, 0] } });
    var flat = { ear: { r: [34, 0, -16] }, eart: { r: [10, 0, 0] } };
    var sneak = bake({ name: '@anim_sneak', type: 'sneaking', length: 1, keys: 4, fps: 20, joints: flat });
    var sleep = bake({ name: '@anim_sleep', type: 'sleeping', length: 1, keys: 4, fps: 20, joints: { ear: { r: [42, 0, -20] }, eart: { r: [14, 0, 0] } } });
    return { id: 'cat_ears', root: 'head', cat: 'head', order: 1, hd: HD, replaces: 'cat_ears', parts: [ear], anims: [idle, sneak, sleep] };
  }
  function bunnyEarsItem() {
    var H = 12, fur = earStrip('be_fur', [[0, 0xe9e6e2], [0.8, 0xf7f5f2], [1, 0xd9d5d0]], H, 3, 11), pink = earStrip('be_pink', [[0, 0xe99aac], [1, 0xf5c3cd]], 11, 2, 13, true);
    var root = [1.9, 8, 0], segs = [[0, 4.2], [4.2, 8.0], [8.0, 11.0]], groups = [];
    segs.forEach(function (sg, i) {
      var y0 = 8 + sg[0] - (i ? 0.25 : 0.4), y1 = 8 + sg[1], W = i < 2 ? 3.0 : 2.5, x = root[0], kids = [];
      kids.push(C(i ? '@seg_' + i : '@base', [x - W / 2, y0, -0.1], [x + W / 2, y1, 0.6], undefined, { tex: hornTex('be_fur', fur, 3, H, 0.3, W, 0.7, y0 - 8, y1 - 8), alt: 0xf0eeea }));
      kids.push(C('@seg_' + (i * 2 + 1), [x - W / 2, y0, -0.55], [x - W / 2 + 0.45, y1, -0.1], undefined, { tex: hornTex('be_fur', fur, 3, H, 0.3, 0.45, 0.45, y0 - 8, y1 - 8), alt: 0xf0eeea }));
      kids.push(C('@seg_' + (i * 2 + 2), [x + W / 2 - 0.45, y0, -0.55], [x + W / 2, y1, -0.1], undefined, { tex: hornTex('be_fur', fur, 3, H, 0.3, 0.45, 0.45, y0 - 8, y1 - 8), alt: 0xf0eeea }));
      var pw = W - 0.9;
      kids.push(C('@inner', [x - pw / 2, y0 + 0.2, -0.25], [x + pw / 2, y1 - (i === 2 ? 0.5 : 0), -0.1], undefined, { tex: hornTex('be_pink', pink, 2, 11, 0.3, pw, 0.15, y0 - 8, y1 - 8 - 0.5), alt: 0xf0aebb }));
      if (i === 2) kids.push(C('@tip', [x - 0.8, y1 - 0.05, -0.1], [x + 0.8, y1 + 0.6, 0.55], undefined, { tex: hornTex('be_fur', fur, 3, H, 0.3, 1.6, 0.65, y1 - 8 - 0.65, y1 - 8), alt: 0xf0eeea }));
      groups.push({ kids: kids, y: 8 + sg[0] });
    });
    var tipG = Gp('@ear_tip_r', [root[0], groups[2].y, 0.25], null, groups[2].kids, 'eart');
    var midG = Gp('@ear_mid_r', [root[0], groups[1].y, 0.25], null, groups[1].kids.concat([tipG]), 'earm');
    var ear = Gp('@ear_r', [root[0], 8, 0.25], [10, 0, -9], groups[0].kids.concat([midG]), 'ear', { mirror: '@ear_l' });
    // the chain sways like a soft ear: each joint a little after the one below, more at the tip
    function chain(P, amp, lag, base) {
      return { ear: { r: [function (t) { return (base ? base[0] : 0) + amp[0] * Math.sin(2 * Math.PI * t); }, 0, wave(amp[0] * 0.3, 0.2)] },
        earm: { r: [function (t) { return (base ? base[1] : 0) + amp[1] * Math.sin(2 * Math.PI * (t - lag)); }, 0, 0] },
        eart: { r: [function (t) { return (base ? base[2] : 0) + amp[2] * Math.sin(2 * Math.PI * (t - 2 * lag)); }, 0, 0] } };
    }
    var anims = [
      bake({ name: '@anim_idle', type: 'standing', length: 4, keys: 40, fps: 20, joints: chain(4, [2, 4, 6], 0.12) }),
      bake({ name: '@anim_walk', type: 'walking', length: 1, keys: 20, fps: 20, joints: chain(1, [3, 7, 10], 0.15) }),
      bake({ name: '@anim_run', type: 'running', length: 0.6, keys: 12, fps: 20, joints: chain(0.6, [5, 10, 12], 0.18, [30, 12, 8]) }),
      bake({ name: '@anim_sneak', type: 'sneaking', length: 4, keys: 20, fps: 20, joints: chain(4, [1, 2, 3], 0.12, [55, 18, 10]) }),
      bake({ name: '@anim_sleep', type: 'sleeping', length: 4, keys: 8, fps: 20, joints: chain(4, [0.5, 1, 1], 0.1, [65, 15, 10]) })
    ];
    return { id: 'bunny_ears', root: 'head', cat: 'head', order: 2, hd: HD, replaces: 'bunny_ears', parts: [ear], anims: anims };
  }

  // ---- elf ears: a human ear (rounded lobe, the helix rolling up from the front) drawn out into a point that sweeps up
  // and back. The outline is drawn from the side, like a sketch (a smooth closed curve through a few points), and
  // painted on thin plates laid along the ear's axis (see-through round it: a smooth shape, never a staircase of
  // slices): a root plate and a tip plate hinged at 55 % (twitch, droop); the realistic ear also has its helix as a
  // rolled strip. The ear swings out from the head about its front edge. Every face is painted in a reference skin tone
  // (TONE_REF, flag 'tone') that the library turns into the model's own skin colour when it puts the ears on.
  // Side frame: Y up, Z back, the origin at the front of the lobe where the ear meets the head.
  var TONE_REF = 0xf0c4a4;
  /** the closed outline of an ear (side frame) as a dense polyline, with which points belong to the free edge */
  function earOutline(pts) {
    var n = pts.length, out = [];
    for (var i = 0; i < n; i++) {
      var p0 = pts[(i - 1 + n) % n], p1 = pts[i], p2 = pts[(i + 1) % n], p3 = pts[(i + 2) % n];
      for (var k = 0; k < 12; k++) {
        var t = k / 12, t2 = t * t, t3 = t2 * t, q = [0, 1].map(function (j) { return 0.5 * (2 * p1[j] + (-p0[j] + p2[j]) * t + (2 * p0[j] - 5 * p1[j] + 4 * p2[j] - p3[j]) * t2 + (-p0[j] + 3 * p1[j] - 3 * p2[j] + p3[j]) * t3); });
        out.push({ y: q[0], z: q[1], free: !(p1[2] && p2[2]) });
      }
    }
    return out;
  }
  /** inside the outline? and how far from its free edge (the attached front edge does not count) */
  function earAt(E, Y, Z) {
    var inside = false, dFree = 1e9, n = E.length;
    for (var i = 0, j = n - 1; i < n; j = i++) {
      var a = E[i], b = E[j];
      if (((a.y > Y) !== (b.y > Y)) && (Z < (b.z - a.z) * (Y - a.y) / (b.y - a.y) + a.z)) inside = !inside;
      if (a.free && b.free) {
        var dy = b.y - a.y, dz = b.z - a.z, l2 = dy * dy + dz * dz || 1, t = Math.max(0, Math.min(1, ((Y - a.y) * dy + (Z - a.z) * dz) / l2));
        dFree = Math.min(dFree, Math.hypot(a.y + dy * t - Y, a.z + dz * t - Z));
      }
    }
    return { inside: inside, d: dFree };
  }
  /** the ear's outer side at (Y, Z): skin with the helix rim, the groove inside it, the antihelix, the hollow, a warm tip */
  function elfOuter(o, Y, Z) {
    var q = earAt(o.E, Y, Z); if (!q.inside) return null;
    var d = q.d, f = Math.max(0, Math.min(1, ((Y - o.root[0]) * o.ax[0] + (Z - o.root[1]) * o.ax[1]) / o.len)), col = TONE_REF;
    // the hollow (concha): an ellipse in the ear's own frame (s along the axis, w across), the canal its darkest point
    var sA = (Y - o.root[0]) * o.ax[0] + (Z - o.root[1]) * o.ax[1], wA = -(Y - o.root[0]) * o.ax[1] + (Z - o.root[1]) * o.ax[0];
    var cc = Math.pow((sA - o.concha[0] * o.len) / (o.concha[2] * o.len), 2) + Math.pow((wA - o.concha[1]) / o.concha[3], 2);
    if (o.toon) {
      // cel: the hollow one flat shadow shape, a light band along the top edge, a blush on the outer 30 %, and a 1-texel
      // ink line only on the back / bottom edge (outlines all round look like stickers)
      col = cc < 1 ? shade(col, -0.15) : d < 0.36 && wA < 0.1 ? shade(col, 0.09) : col;
      if (f > 0.7) col = lerpCol(col, 0xf5a3a3, f > 0.85 ? 0.38 : 0.2);           // the blush in two steps
      if (d < 0.13 && wA > -0.15) col = shade(col, -0.34);
      return col;
    }
    col = shade(col, 0.04 - 0.08 * Math.max(0, Math.min(1, (o.top - Y) / o.top)));
    if (d < 0.3) col = shade(col, 0.15);                                     // the rolled rim catches the light
    else if (d < 0.58) col = shade(col, -0.17);                              // the groove inside it
    else if (d < 0.95) col = shade(col, 0.07);                               // the antihelix ridge
    if (cc < 1) col = shade(col, cc < 0.35 ? -0.27 : -0.19);                 // the hollow, the canal deepest
    if (f > 0.66) col = lerpCol(col, 0xe9998a, 0.3 * smooth(0.66, 1, f));    // thin skin at the tip
    if (Y < 0.7 && Z > 0.2) col = lerpCol(col, 0xf3b7a3, 0.15);              // the soft lobe
    return col;
  }
  /** the inner side (towards the head): darker, the root in the head's shadow */
  function elfInner(o, Y, Z) {
    var q = earAt(o.E, Y, Z); if (!q.inside) return null;
    var f = Math.max(0, Math.min(1, ((Y - o.root[0]) * o.ax[0] + (Z - o.root[1]) * o.ax[1]) / o.len)), col = shade(TONE_REF, -0.08 - 0.12 * (1 - smooth(0, 0.35, f)));
    if (o.toon && q.d < 0.13 && (-(Y - o.root[0]) * o.ax[1] + (Z - o.root[1]) * o.ax[0]) > -0.15) col = shade(col, -0.3);
    if (f > 0.66) col = lerpCol(col, 0xe9998a, 0.25 * smooth(0.66, 1, f));
    return col;
  }
  /**
   * one plate of the ear: the stretch [s0, s1] along its axis (from the root), as wide as the outline there; a thin box
   * laid on the side of the head and turned (about x) along the axis; its outer / inner faces painted, the rim faces off
   */
  function elfPlate(o, key, name, x0, s0, s1) {
    var th = (90 - o.elev) * DEG, ax = o.ax, bx = [-ax[1], ax[0]]; // (Y, Z) of the axis and of the across direction
    var lo = 1e9, hi = -1e9, i, j;
    o.E.forEach(function (p) { var s = (p.y - o.root[0]) * ax[0] + (p.z - o.root[1]) * ax[1]; if (s < s0 - 0.3 || s > s1 + 0.3) return; var w = (p.y - o.root[0]) * bx[0] + (p.z - o.root[1]) * bx[1]; lo = Math.min(lo, w); hi = Math.max(hi, w); });
    lo = Math.floor((lo - 0.15) * HD) / HD; hi = Math.ceil((hi + 0.15) * HD) / HD;
    var hs = s1 - s0, hw = hi - lo, W = Math.max(1, Math.round(hw * HD)), H = Math.max(1, Math.round(hs * HD));
    // the box before its turn: y along the axis, z across; its centre in the side frame
    var cs = (s0 + s1) / 2, cw = (lo + hi) / 2, cY = o.root[0] + ax[0] * cs + bx[0] * cw, cZ = o.root[1] + ax[1] * cs + bx[1] * cw;
    function side(east, fn) {
      var d = new Uint8ClampedArray(W * H * 4);
      for (j = 0; j < H; j++) for (i = 0; i < W; i++) {
        // box frame: y down the rows (the far end at the top), z across the columns (east: +z on the left)
        var yl = hs / 2 - (j + 0.5) / HD, zl = east ? hw / 2 - (i + 0.5) / HD : -hw / 2 + (i + 0.5) / HD;
        // turned about x by th: +y goes along the axis, +z across (towards the back edge)
        var Y = cY + yl * Math.cos(th) - zl * Math.sin(th), Z = cZ + yl * Math.sin(th) + zl * Math.cos(th);
        var c = fn(o, Y, Z); if (c === null) continue;
        var k = (j * W + i) * 4; d[k] = (c >> 16) & 255; d[k + 1] = (c >> 8) & 255; d[k + 2] = c & 255; d[k + 3] = 255;
      }
      return { w: W, h: H, px: b64(d) };
    }
    function face(nm, p, k) { return { face: nm, rel: [0, 0, sn(hw), sn(hs)], w: p.w, h: p.h, px: p.px, flip: [false, false], rot: 0, key: k, hd: true, tone: 1 }; }
    var tex = { box: false, off: [0, 0], faces: [face('east', side(true, elfOuter), key + '_o'), face('west', side(false, elfInner), key + '_i')], fs: { north: -1, south: -1, up: -1, down: -1 } };
    return C(name, [x0 - o.T / 2, cY - hs / 2, cZ - hw / 2], [x0 + o.T / 2, cY + hs / 2, cZ + hw / 2], undefined, { tex: tex, alt: TONE_REF, r: [sn(o.elevTurn), 0, 0], o: [sn(x0), sn(cY), sn(cZ)] });
  }
  /** the helix as a rolled strip along the free top edge (from the upper root to the tip), in short straight pieces */
  function elfRim(o, key, x0, from, to, n) {
    var out = [], strip = paintStrip(1, 12, 0, 11, function (u, s, f) {
      var col = shade(TONE_REF, 0.09 - 0.2 * Math.abs(u));
      return [s > 6.5 ? lerpCol(col, 0xe9998a, 0.25 * smooth(6.5, 9, s)) : col, 1];
    });
    for (var k = 0; k < n; k++) {
      var a = o.helix(from + (to - from) * k / n), b = o.helix(from + (to - from) * (k + 1) / n), len = Math.hypot(b[0] - a[0], b[1] - a[1]) + 0.1;
      var cY = (a[0] + b[0]) / 2, cZ = (a[1] + b[1]) / 2, w = 0.4 * (1 - 0.4 * (from + (to - from) * (k + 0.5) / n)), thk = o.T + 0.22, xc = x0 + 0.11;
      var s0 = 1 + 9 * (from + (to - from) * k / n), tex = hornTex(key, strip, 1, 12, 0, thk, w, s0, s0 + len);
      tex.faces.forEach(function (f) { f.tone = 1; });
      out.push(C('@rim', [xc - thk / 2, cY - len / 2, cZ - w / 2], [xc + thk / 2, cY + len / 2, cZ + w / 2], undefined,
        { tex: tex, alt: TONE_REF, r: [sn(Math.atan2(b[1] - a[1], b[0] - a[0]) / DEG), 0, 0], o: [sn(xc), sn(cY), sn(cZ)] }));
    }
    return out;
  }
  /**
   * an elf ear item. st = {id, pts (outline in the side frame: [Y, Z, attached]), tip, concha [Y, Z, rY, rZ], T,
   * rim, toon, splay (degrees the ear swings out about its front edge), tilt, at [x, y, z] (the front of the lobe on
   * the head), twitch, droop [ear, tip], order}
   */
  function elfEarsItem(st0) {
    // the outline is drawn at any size and scaled (k) to the ear's length
    var k = st0.k || 1, st = Object.assign({}, st0, { pts: st0.pts.map(function (p) { return p.length > 2 ? [p[0] * k, p[1] * k, p[2]] : [p[0] * k, p[1] * k]; }), tip: [st0.tip[0] * k, st0.tip[1] * k] });
    var o = Object.assign({}, st), E = earOutline(st.pts);
    var top = 0; E.forEach(function (p) { top = Math.max(top, p.y); });
    // the axis: from the middle of the attached edge to the tip
    var att = st.pts.filter(function (p) { return p[2]; }), rY = 0, rZ = 0; att.forEach(function (p) { rY += p[0] / att.length; rZ += p[1] / att.length; });
    var dY = st.tip[0] - rY, dZ = st.tip[1] - rZ, len = Math.hypot(dY, dZ);
    o.E = E; o.top = top; o.root = [rY, rZ]; o.ax = [dY / len, dZ / len]; o.len = len;
    o.elev = Math.atan2(dY, dZ) / DEG; o.elevTurn = 90 - o.elev;
    // the helix: the free edge from the top of the attached edge round to the tip, as a function of 0..1
    var tipI = 0; st.pts.forEach(function (p, i) { if (p[0] === st.tip[0] && p[1] === st.tip[1]) tipI = i; });
    var upI = tipI + 1; while (upI < st.pts.length - 1 && !st.pts[upI][2]) upI++;
    var path = E.slice(tipI * 12, upI * 12 + 1).reverse();
    o.helix = function (t) { var x = Math.max(0, Math.min(path.length - 1, t * (path.length - 1))), i0 = Math.floor(x), i1 = Math.min(path.length - 1, i0 + 1), u = x - i0, a = path[i0], b = path[i1]; return [a.y + (b.y - a.y) * u - 0.2 * o.ax[1], a.z + (b.z - a.z) * u + 0.2 * o.ax[0]]; };
    var x0 = st.at[0] + st.T / 2 - 0.05, key = 'elf_' + st.id, hinge = 0.55 * len;
    // the side frame sits at the ear's place on the head
    function place(n) { if (n.c) { [n.f, n.t, n.o].forEach(function (v) { if (v) { v[1] = sn(v[1] + st.at[1]); v[2] = sn(v[2] + st.at[2]); } }); } else { n.o[1] = sn(n.o[1] + st.at[1]); n.o[2] = sn(n.o[2] + st.at[2]); n.kids.forEach(place); } }
    var lower = [elfPlate(o, key + '_a', '@base', x0, -0.6, hinge + 0.12)], upper = [elfPlate(o, key + '_b', '@tip', x0, hinge - 0.05, len + 0.25)];
    if (st.rim) { lower = lower.concat(elfRim(o, key + '_rim', x0, 0, 0.5, 3)); upper = upper.concat(elfRim(o, key + '_rim', x0, 0.5, 0.97, 3)); }
    var hp = [rY + o.ax[0] * hinge, rZ + o.ax[1] * hinge];
    var tip = Gp('@ear_tip_r', [x0, hp[0], hp[1]], null, upper, 'elft');
    var ear = Gp('@ear_r', [st.at[0], 0, 0], [0, st.splay, st.tilt || 0], lower.concat([tip]), 'elf', { mirror: '@ear_l' });
    place(ear);
    // twitches (each ear at its own moment: a quick flick, the tip a little after), a slow swivel; the ears droop when
    // crouching, more when asleep, and perk up when jumping. The tip folds about the hinge (across the ear)
    function pulse(dtSec) { if (dtSec < 0 || dtSec > 0.6) return 0; if (dtSec < 0.1) return W2(dtSec / 0.1); if (dtSec < 0.35) return 1 - 1.25 * W2((dtSec - 0.1) / 0.25); return -0.25 * (1 - W2((dtSec - 0.35) / 0.25)); }
    function W2(x) { return smooth(0, 1, x); }
    function tw(at, k) { return function (t) { var sec = t * 9.6, a = at * 9.6; return k * (st.twitch * pulse(sec - a) + 1.5 * Math.sin(2 * Math.PI * 3 * t)); }; }
    function sides(name, type, len, keys, jr, jl) {
      var a = bake({ name: name, type: type, length: len, keys: keys, fps: 20, joints: jr }), b = bake({ name: name, type: type, length: len, keys: keys, fps: 20, joints: jl });
      a.tracks.forEach(function (t) { t.side = 'r'; }); b.tracks.forEach(function (t) { t.side = 'l'; a.tracks.push(t); });
      return a;
    }
    // jumping: a dip (+6) for 0.08 s, up to -14 by 0.2 s, overshoot -18, settled by 0.45 s (+ x = down)
    function perk(sec) { if (sec < 0.08) return 6 * W2(sec / 0.08); if (sec < 0.2) return 6 - 24 * W2((sec - 0.08) / 0.12); if (sec < 0.3) return -18 + 4 * W2((sec - 0.2) / 0.1); return -14 * (1 - W2(Math.min(1, (sec - 0.3) / 0.2))); }
    // (the tip folds in the ear's own plane: + x turns it back and down)
    function tipJ(fk) { return { r: [fk, 0, 0] }; }
    var idle = sides('@anim_idle', 'global', 9.6, 192, { elf: { r: [0, tw(2.6 / 9.6, 1), wave(0.8, 0.1, 2)] }, elft: tipJ(function (t) { return 0.45 * tw(2.65 / 9.6, 1)(t); }) },
      { elf: { r: [0, tw(7.1 / 9.6, 1), wave(0.8, 0.45, 2)] }, elft: tipJ(function (t) { return 0.45 * tw(7.15 / 9.6, 1)(t); }) });
    // droop: the ear swings back flat against the head (less splay) and turns down; the tip folds
    var anims = [idle,
      bake({ name: '@anim_sneak', type: 'sneaking', length: 1, keys: 4, fps: 20, joints: { elf: { r: [st.droop[0], -st.splay * 0.35, 0] }, elft: tipJ(st.droop[1]) } }),
      bake({ name: '@anim_sleep', type: 'sleeping', length: 1, keys: 4, fps: 20, joints: { elf: { r: [st.droop[0] * 1.7, -st.splay * 0.6, 0] }, elft: tipJ(st.droop[1] * 1.6) } }),
      Object.assign(bake({ name: '@anim_jump', type: 'jumping', length: 0.5, keys: 10, fps: 20, joints: { elf: { r: [function (t) { return perk(t * 0.5); }, function (t) { return -0.3 * perk(t * 0.5); }, 0] }, elft: tipJ(function (t) { return 0.6 * perk(Math.max(0, (t * 0.5 - 0.05) / 0.45 * 0.5)); }) } }), { mustFinish: true }),
      bake({ name: '@anim_walk', type: 'walking', length: 1, keys: 20, fps: 20, joints: { elf: { r: [wave(3.5, 0, 2), 0, wave(1.5, 0.25, 2)] }, elft: tipJ(wave(5, -0.12, 2)) } })];
    return { id: st.id, root: 'head', cat: 'head', order: st.order, hd: HD, replaces: st.id, tone: true, parts: [ear], anims: anims };
  }
  // the kinds (side frame, px; [Y, Z, 1] = a point of the edge attached to the head). Realistic: a human ear (lobe,
  // the helix rolling up from the front) drawn out to a point up and back, about 6 px long, swung out 28 degrees.
  // Cartoon (anime): longer and more upright, narrow, flat cel bands and a darker line round it.
  var ELVES = [
    { id: 'ears_elf', pts: [[0, 0, 1], [-0.35, 0.65], [-0.1, 1.45], [0.6, 2.0], [1.9, 2.75], [3.5, 3.8], [5.25, 5.2], [6.8, 6.55], [6.15, 5.05], [5.4, 3.5], [4.7, 2.0], [4.05, 0.6, 1], [2.0, 0.05, 1]],
      tip: [6.8, 6.55], k: 0.62, concha: [0.2, 0.1, 0.17, 0.36], T: 0.1, splay: 32, tilt: -8, at: [4, 2.4, 0.4], twitch: 12, droop: [10, 8], order: 2.1 },
    { id: 'ears_elf_toon', toon: true, pts: [[0, 0, 1], [-0.3, 0.5], [0.05, 1.25], [1.2, 2.25], [2.8, 3.75], [4.5, 5.6], [6.3, 8.2], [5.6, 6.0], [4.9, 4.0], [4.2, 2.1], [3.5, 0.55, 1], [1.8, 0.05, 1]],
      tip: [6.3, 8.2], k: 0.65, concha: [0.33, 0.16, 0.26, 0.25], T: 0.1, splay: 58, tilt: -12, at: [4, 2.5, 0.3], twitch: 15, droop: [20, 12], order: 2.2 }
  ];
  /**
   * low-poly elf ears (16x pixel art, like a vanilla skin): three boxes stepping to the tip along the ear's axis, every
   * face painted pixel by pixel in four tones of the reference skin (the library tints them to the model's skin)
   */
  function elfLowItem() {
    var L1 = shade(TONE_REF, 0.08), B0 = TONE_REF, S1 = shade(TONE_REF, -0.13), S2 = shade(TONE_REF, -0.27), TIP = lerpCol(TONE_REF, 0xe9998a, 0.45);
    // [s0, s1 (along the axis), w0, w1 (across: - = the top / helix edge)], whole pixels
    var elev = 62, th = 90 - elev, x0 = 4.45, kids = [], INNER = lerpCol(S1, 0xd6a996, 0.5);
    function pic(w, h, fn) { var d = new Uint8ClampedArray(w * h * 4); for (var j = 0; j < h; j++) for (var i = 0; i < w; i++) { var c = fn(i, j, w, h), k = (j * w + i) * 4; d[k] = (c >> 16) & 255; d[k + 1] = (c >> 8) & 255; d[k + 2] = c & 255; d[k + 3] = 255; } return { w: w, h: h, px: b64(d) }; }
    function face(nm, p, key, uw, uh) { return { face: nm, rel: [0, 0, uw, uh], w: p.w, h: p.h, px: p.px, flip: [false, false], rot: 0, key: key, tone: 1 }; }
    function rx2(p, c, deg) { var a = deg * DEG, y = p[0] - c[0], z = p[1] - c[1]; return [c[0] + y * Math.cos(a) - z * Math.sin(a), c[1] + y * Math.sin(a) + z * Math.cos(a)]; }
    // [s0, s1 (along the axis), w0, w1 (across: - = the top edge)], whole pixels; extra turn after the root's
    var BOXES = [[0, 3, -1, 1, 0], [3, 5, -1, 0, 20], [5, 6, -1, 0, 65]], joint = rx2([3, -0.5], [0, 0], th);
    BOXES.forEach(function (bx, n) {
      var Ls = bx[1] - bx[0], Wd = bx[3] - bx[2], last = n === BOXES.length - 1;
      var c0 = rx2([(bx[0] + bx[1]) / 2, (bx[2] + bx[3]) / 2], [0, 0], th), c = n ? rx2(c0, joint, 20) : c0, turn = th + bx[4];
      var outer = pic(Wd, Ls, function (i, j) { return last ? TIP : n === 0 && i === Wd - 1 && j >= 1 ? INNER : B0; });
      var flat = function (col) { return function () { return col; }; };
      var key = 'elfl_' + n;
      var tex = { box: false, off: [0, 0], faces: [face('east', outer, key + 'o', Wd, Ls), face('west', pic(Wd, Ls, flat(S1)), key + 'i', Wd, Ls), face('north', pic(1, Ls, flat(last ? TIP : L1)), key + 'r', 1, Ls),
        face('south', pic(1, Ls, flat(S1)), key + 'b', 1, Ls), face('up', pic(1, Wd, flat(last ? TIP : L1)), key + 'u', 1, Wd), face('down', pic(1, Wd, flat(S2)), key + 'd', 1, Wd)] };
      kids.push(C(n ? '@seg_' + n : '@base', [x0 - 0.5, c[0] - Ls / 2, c[1] - Wd / 2], [x0 + 0.5, c[0] + Ls / 2, c[1] + Wd / 2], undefined, { tex: tex, alt: TONE_REF, r: [sn(turn), 0, 0], o: [x0, sn(c[0]), sn(c[1])] }));
    });
    // the middle and the tip twitch together about the joint
    var tipG = Gp('@ear_tip_r', [x0, sn(joint[0]), sn(joint[1])], null, kids.splice(1, 2), 'elft');
    var ear = Gp('@ear_r', [4, 3, 0.4], [0, 30, -6], kids.concat([tipG]), 'elf', { mirror: '@ear_l' });
    (function place(n) { if (n.c) { [n.f, n.t, n.o].forEach(function (v) { v[1] = sn(v[1] + 3); v[2] = sn(v[2] + 0.4); }); } else { if (n !== ear) { n.o[1] = sn(n.o[1] + 3); n.o[2] = sn(n.o[2] + 0.4); } n.kids.forEach(place); } })(ear);
    function tw(at) { return function (t) { var dt = ((t - at) % 1 + 1) % 1; return 8 * Math.exp(-Math.pow((dt - 0.03) / 0.025, 2)); }; }
    var idle = bake({ name: '@anim_idle', type: 'global', length: 6, keys: 60, fps: 20, joints: { elf: { r: [0, tw(0.3), 0] }, elft: { r: [function (t) { return 0.6 * tw(0.33)(t); }, 0, 0] } } });
    var anims = [idle,
      bake({ name: '@anim_sneak', type: 'sneaking', length: 1, keys: 4, fps: 20, joints: { elf: { r: [16, -12, 0] }, elft: { r: [12, 0, 0] } } }),
      bake({ name: '@anim_sleep', type: 'sleeping', length: 1, keys: 4, fps: 20, joints: { elf: { r: [26, -20, 0] }, elft: { r: [18, 0, 0] } } }),
      Object.assign(bake({ name: '@anim_jump', type: 'jumping', length: 0.5, keys: 10, fps: 20, joints: { elf: { r: [function (t) { return -7 * Math.sin(Math.PI * t); }, 0, 0] } } }), { mustFinish: true })];
    return { id: 'ears_elf_lowpoly', root: 'head', cat: 'head', order: 2.3, hd: 1, replaces: 'ears_elf_lowpoly', tone: true, parts: [ear], anims: anims };
  }
  // lang: lib.p.{ears_elf|ears_elf_toon|ears_elf_lowpoly}
  // lang: lib.n.{rim}

  // ---- hand-painted faces (pixel art): every face of a box gets its own picture, lit by its direction (top brightest,
  // the bottom in shadow), a light top edge, darker bottom edge and corners (contact shadow), the material's detail,
  // and every pixel snapped to the material's few tones (shadows shifted cooler, lights warmer) - how Blockbench artists
  // paint, instead of a noise tile
  var PXD = 4;
  var PXMAT = {
    bone: { tones: [0x2e221c, 0x5d4a3a, 0x8f7759, 0xbba383, 0xdcc9a6, 0xf2e8cf], base: 0.66, cracks: 1, pores: 1 },
    tooth: { tones: [0x4a3a2a, 0x8c7656, 0xc4ad85, 0xe6d8b6, 0xfbf4e2], base: 0.72, root: 1 },
    void: { tones: [0x07050a, 0x120d12, 0x1f171c, 0x2c2126], base: 0.25 },
    leather: { tones: [0x24150e, 0x3f2717, 0x5c3a22, 0x7d5232, 0x9c6c44], base: 0.5, stitch: 0xd8b98a },
    iron: { tones: [0x121216, 0x26262c, 0x3c3c44, 0x5d5d68, 0x8f8f9c], base: 0.45 },
    felt: { tones: [0x1c0f2a, 0x2f1946, 0x45275f, 0x5e3a7a, 0x7b5398, 0x9d78b6], base: 0.55, fibre: 1 },
    feltDark: { tones: [0x120a1c, 0x1f1230, 0x2f1d45, 0x3f2a59], base: 0.5, fibre: 1 },
    satin: { tones: [0x14090f, 0x2a1220, 0x441c33, 0x62294a, 0x84406a], base: 0.5, sheen: 1 },
    gold: { tones: [0x4a2c08, 0x7e5414, 0xb4821f, 0xdcae37, 0xf6d96a, 0xfff3b8], base: 0.6, sheen: 1 },
    goldGlow: { tones: [0xb4821f, 0xdcae37, 0xf6d96a, 0xfff1a6, 0xfffbe0], base: 0.6, sheen: 1 },
    pearl: { tones: [0x8c8478, 0xc9c1b4, 0xece6da, 0xfffcf4], base: 0.68 },
    velvet: { tones: [0x2a040c, 0x4c0816, 0x720d22, 0x96182f, 0xb8304a], base: 0.5, sheen: 1 },
    silk: { tones: [0x08080a, 0x141418, 0x222228, 0x34343c, 0x55555f], base: 0.42, sheen: 1, fibre: 1 },
    satinRed: { tones: [0x3a0610, 0x5e0c1c, 0x86142a, 0xaa2440, 0xcf4a62], base: 0.5, sheen: 1 },
    patch: { tones: [0x2e1d10, 0x4d3220, 0x6b4a2e, 0x8a643f], base: 0.55, stitch: 0xe8d9a8, border: 1 }
  };
  // tile names of the older parts -> the hand-painted material that replaces them
  var PXALIAS = { feltPurple: 'felt', satinDark: 'satin', dark: 'void' };
  var LIGHT = { up: 0.2, north: 0.06, east: 0, west: -0.04, south: -0.08, down: -0.3 };
  function paintFace(matId, face, fw, fh, seed, opt) {
    var m = PXMAT[matId], W = Math.max(1, Math.round(fw * PXD)), H = Math.max(1, Math.round(fh * PXD)), d = new Uint8ClampedArray(W * H * 4), nz = noise2(seed), R = rng(seed);
    var nt = m.tones.length, side = face !== 'up' && face !== 'down', o = opt || {};
    // cracks: a few random short wiggly lines; pores: scattered dark dots
    var cracks = [];
    if (m.cracks && W * H > 40) for (var c = 0; c < 1 + Math.floor(W * H / 260); c++) cracks.push([R() * W, R() * H, R() * Math.PI, 3 + R() * 8]);
    for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) {
      var v = m.base + LIGHT[face] + (nz(x * 0.35, y * 0.35) - 0.5) * 0.22 + (o.shade || 0);
      // vertical light gradient on the sides (lighter at the top), contact shadow at the bottom edge and the corners
      if (side) v += 0.1 * (1 - y / Math.max(1, H - 1)) - 0.05;
      if (y === 0 && side) v += 0.16;
      if (y === H - 1) v -= 0.18;
      if ((x === 0 || x === W - 1) && W > 2) v -= 0.08;
      if (m.pores && nz(x * 1.3 + 7, y * 1.3) > 0.91) v -= 0.18;
      for (var k = 0; k < cracks.length; k++) {
        var cr = cracks[k], dx = x - cr[0], dy = y - cr[1], along = dx * Math.cos(cr[2]) + dy * Math.sin(cr[2]), across = -dx * Math.sin(cr[2]) + dy * Math.cos(cr[2]) + Math.sin(along * 0.9) * 0.8;
        if (Math.abs(along) < cr[3] && Math.abs(across) < 0.5) { v -= 0.3; break; }
      }
      if (m.fibre && ((x * 3 + y * 7) % 11 === 0)) v += 0.06;
      if (m.sheen && side) v += 0.18 * Math.exp(-Math.pow((x / Math.max(1, W - 1) - 0.35) / 0.12, 2));
      if (m.root && o.root) v -= 0.35 * Math.max(0, 1 - y / Math.max(1, H * 0.35)) * (o.root === 'bottom' ? 0 : 1) + (o.root === 'bottom' ? -0.35 * Math.max(0, (y - H * 0.65) / (H * 0.35)) : 0);
      var ti = Math.max(0, Math.min(nt - 1, Math.round(v * (nt - 1)))), col = m.tones[ti];
      if (m.stitch && (m.border ? Math.min(x, y, W - 1 - x, H - 1 - y) === 1 : (y === 1 || y === H - 2)) && (x + y) % 3 === 0) col = m.stitch;
      var j = (y * W + x) * 4; d[j] = (col >> 16) & 255; d[j + 1] = (col >> 8) & 255; d[j + 2] = col & 255; d[j + 3] = 255;
    }
    return { w: W, h: H, px: b64(d) };
  }
  /** a box with a hand-painted picture on every face (thin faces switched off) */
  function pbox(n, f, t, mat, x, opt) {
    var dims = [Math.abs(t[0] - f[0]), Math.abs(t[1] - f[1]), Math.abs(t[2] - f[2])], faces = [], fs = {}, seed = Math.round((f[0] * 7.1 + f[1] * 3.3 + f[2] * 5.7 + t[0] * 1.9) * 100) & 0xffff;
    [['north', 0, 1], ['south', 0, 1], ['east', 2, 1], ['west', 2, 1], ['up', 0, 2], ['down', 0, 2]].forEach(function (q) {
      var fw = dims[q[1]], fh = dims[q[2]];
      if (fw < 0.02 || fh < 0.02) { fs[q[0]] = -1; return; }
      var pic = paintFace(mat, q[0], fw, fh, seed + q[0].length * 131, opt);
      faces.push({ face: q[0], rel: [0, 0, fw, fh], w: pic.w, h: pic.h, px: pic.px, flip: [false, false], rot: 0, hd: true, key: 'pf' + mat + seed + q[0] });
    });
    return C(n, f, t, undefined, Object.assign({ tex: { box: false, off: [0, 0], faces: faces, fs: Object.keys(fs).length ? fs : undefined }, alt: PXMAT[mat].tones[Math.floor(PXMAT[mat].tones.length * 0.6)] }, x || {}));
  }
  function pcbox(n, c, s, mat, r, x, opt) { return pbox(n, [c[0] - s[0] / 2, c[1] - s[1] / 2, c[2] - s[2] / 2], [c[0] + s[0] / 2, c[1] + s[1] / 2, c[2] + s[2] / 2], mat, Object.assign({ r: r, o: c.slice() }, x || {}), opt); }
  /**
   * a smooth tapered tube without steps (a pro trick): n planes leaning in round the axis, each painted as a trapezoid
   * (the picture's see-through sides cut it to shape); base radius r0 at y0, top radius r1, height h, round c ->
   * cube specs (the outside painted, the inside mirrored so it also shows from below)
   */
  function frustum(name, c, y0, r0, r1, h, mat, n, opt) {
    var out = [], half = Math.PI / n, ap0 = r0 * Math.cos(half), ap1 = r1 * Math.cos(half), wb = 2 * r0 * Math.sin(half), wt = 2 * r1 * Math.sin(half);
    var lean = Math.atan2(ap0 - ap1, h) / DEG, hs = Math.hypot(h, ap0 - ap1), W = Math.max(1, Math.round(wb * PXD)), H = Math.max(1, Math.round(hs * PXD));
    var m = PXMAT[mat], nt = m.tones.length, seed = Math.round(r0 * 97 + h * 31 + y0 * 7);
    for (var k = 0; k < n; k++) {
      var a = (k + 0.5) * 360 / n, nz = noise2(seed + k), d = new Uint8ClampedArray(W * H * 4);
      // light from the front and the top: the planes facing the front a little lighter
      var facing = Math.cos(a * DEG) * -0.08 + Math.sin(a * DEG) * 0.03;
      for (var y = 0; y < H; y++) {
        var v01 = 1 - (y + 0.5) / H, hw = (wb + (wt - wb) * v01) / 2 * PXD;
        for (var x = 0; x < W; x++) {
          var u = x + 0.5 - W / 2; if (Math.abs(u) > hw + 0.01) continue;
          var v = m.base + facing + 0.08 * v01 - 0.04 + (nz(x * 0.35, y * 0.35) - 0.5) * 0.2 + (opt && opt.shade || 0);
          if (Math.abs(u) > hw - 1.2) v -= 0.1;
          if (m.fibre && ((x * 3 + y * 7) % 11 === 0)) v += 0.06;
          var col = m.tones[Math.max(0, Math.min(nt - 1, Math.round(v * (nt - 1))))], j = (y * W + x) * 4;
          d[j] = (col >> 16) & 255; d[j + 1] = (col >> 8) & 255; d[j + 2] = col & 255; d[j + 3] = 255;
        }
      }
      var px = b64(d), key = 'fr' + mat + seed + '_' + k, s = Math.sin(a * DEG), co = Math.cos(a * DEG), p = [c[0] + ap0 * s, y0, c[2] + ap0 * co];
      var face = function (nm, flip) { return { face: nm, rel: [0, 0, wb, hs], w: W, h: H, px: px, flip: [flip, false], rot: 0, hd: true, key: key }; };
      out.push(C(name, [p[0] - wb / 2, y0, p[2] - 0.005], [p[0] + wb / 2, y0 + hs, p[2] + 0.005], undefined,
        { tex: { box: false, off: [0, 0], faces: [face('south', false), face('north', true)], fs: { east: -1, west: -1, up: -1, down: -1 } }, alt: m.tones[Math.floor(nt * 0.6)], r: [sn(-lean), sn(a), 0], o: p }));
    }
    return out;
  }
  /** a cube spec and its mirror on the other side of the head (x -> -x, turns about y and z the other way) */
  function both(node) {
    var m = Object.assign({}, node, { f: [-node.t[0], node.f[1], node.f[2]], t: [-node.f[0], node.t[1], node.t[2]] });
    if (node.o) m.o = [-node.o[0], node.o[1], node.o[2]];
    if (node.r) m.r = [node.r[0], -node.r[1], -node.r[2]];
    if (node.tex) m.tex = Object.assign({}, node.tex, { faces: node.tex.faces.map(function (f) { return Object.assign({}, f, { face: f.face === 'east' ? 'west' : f.face === 'west' ? 'east' : f.face, flip: [!f.flip[0], f.flip[1]] }); }), fs: node.tex.fs ? { north: node.tex.fs.north, south: node.tex.fs.south, east: node.tex.fs.west, west: node.tex.fs.east, up: node.tex.fs.up, down: node.tex.fs.down } : undefined });
    return [node, m];
  }

  // lang: lib.n.{pearl|leaf|arch|orb|cross|crown|hat|hat_mid|hat_tip|ear_tip_r|ear_tip_l|ear_mid_r|ear_mid_l|patch}

  // =================================================================== tails (with an animation for every player state)
  // (from the tail study: a chain of bones from the lower back, built straight along +y and turned back and down at
  // the root; fluffy tails = a core plus fur cards crossing on the axis like a star (each card a little longer than its
  // segment so it covers the next joint), the cards painted with alpha-cut V clumps, roots darker, tips lighter, light
  // streaks along the flow; thin and scaly tails = two boxes per segment turned 45 degrees (an octagon); one painted
  // strip per material along the whole tail. Motion: a wave running down the chain (each joint a little later than the
  // one before), roll smaller than the swing, two unrelated sines so it never looks looped, and one animation per
  // player state - standing, walking, running, crouching, swimming, falling, jumping, sitting, sleeping, flying.)
  // (frames: the root turn [90 + droop, yaw, 0] points the chain's +y back and down; its -z is then the top of the tail;
  // + x on a joint bends the tail down, - x curls it up; z swings it sideways)
  var TAIL_ROOT = [0, -10.4, 1.4];
  /** width of a tail at f (0 root .. 1 tip): base -> mid (widest at w.at) -> tip */
  function tailWidth(w, f) {
    return f <= w.at ? lerp(w.base, w.mid, smooth(0, 1, f / w.at)) : lerp(w.mid, w.tip, smooth(0, 1, (f - w.at) / (1 - w.at)));
  }
  /** a strip picture painted by fn(u (px from the middle), s (px from the root), f (0..1 along), xt, yt) -> [rgb, a] */
  function paintStrip(Wst, Hst, sink, Ltot, fn) {
    var Wt = Wst * HD, Ht = Hst * HD, d = new Uint8ClampedArray(Wt * Ht * 4);
    for (var y = 0; y < Ht; y++) {
      var s = Hst - (y + 0.5) / HD - sink, f = Math.max(0, Math.min(1, s / Ltot));
      for (var x = 0; x < Wt; x++) {
        var r = fn((x + 0.5) / HD - Wst / 2, s, f, x, y); if (!r || !r[1]) continue;
        var j = (y * Wt + x) * 4, c = r[0]; d[j] = (c >> 16) & 255; d[j + 1] = (c >> 8) & 255; d[j + 2] = c & 255; d[j + 3] = 255;
      }
    }
    return { w: Wt, h: Ht, px: b64(d) };
  }
  /** a flat plane (thin in z) cut from a strip: only its two big faces painted (the back mirrored), the rim off */
  function cardTex(key, pic, Wst, Hst, sink, w, s0, s1) {
    var t = hornTex(key, pic, Wst, Hst, sink, w, 0.01, s0, s1), keep = { north: 1, south: 1 };
    t.faces = t.faces.filter(function (f) { return keep[f.face]; });
    t.faces.forEach(function (f) { if (f.face === 'north') f.flip = [true, false]; });
    t.fs = { east: -1, west: -1, up: -1, down: -1 };
    return t;
  }
  /** a whole picture on the two big faces of a plane thin in z (spade) */
  function plateTex(key, pic, uw, uh) {
    function face(name, flipU) { return { face: name, rel: [0, 0, uw, uh], w: pic.w, h: pic.h, px: pic.px, flip: [flipU, false], rot: 0, key: key, hd: true }; }
    return { box: false, off: [0, 0], faces: [face('south', false), face('north', true)], fs: { east: -1, west: -1, up: -1, down: -1 } };
  }
  /**
   * fur card strip: the silhouette's half width follows the tail, its edges cut into V clumps pointing at the tip,
   * strands along the tail, darker at the roots (the middle of the card), lighter and streaked towards the edges
   */
  function furCards(look, width, Ltot, Wst, Hst, sink, seed) {
    var nz = noise2(seed), R = rng(seed + 5), phase = [R(), R()];
    return paintStrip(Wst, Hst, sink, Ltot, function (u, s, f, x) {
      var hw = tailWidth(width, Math.max(0, Math.min(1, s / Ltot))) / 2, side = u < 0 ? 0 : 1, a = Math.abs(u);
      // clumps: a V every clump length along each edge, deeper towards the tip, a little irregular
      var cl = look.clump || 0.75, q = s / cl + phase[side] + 0.35 * nz(x * 0.05 + side * 7, s * 0.3), saw = q - Math.floor(q);
      var cut = (look.cutK || 0.35) * hw * Math.pow(1 - saw, 1.6) * (0.6 + 0.4 * f);
      if (a > hw - cut || s > Ltot + 0.2) return null;
      // the very tip closes in a soft point
      if (s > Ltot - 0.6 && a > hw * (Ltot + 0.2 - s) / 0.8) return null;
      if (look.toon) return [toonTail(look, look.ramp, s, f, u / Math.max(0.01, hw), Ltot, x), 1];
      var col = ramp(look.ramp, f), k = a / Math.max(0.01, hw);
      col = shade(col, -0.24 * (1 - k) * (1 - k) + 0.12 * k * k);
      col = shade(col, (nz(x * 0.55, s * 0.35) - 0.5) * 0.18);
      if (((x * 7 + Math.floor(s * 1.3)) % 11) === 0) col = shade(col, 0.12);
      if (look.rings) col = ringsOn(look, col, s, Ltot, u, x);
      return [col, 1];
    });
  }
  /** the core of a tail (opaque): the fur colours in its own shadow, or the skin / scales of a bare tail */
  function coreStrip(look, Ltot, Wst, Hst, sink, seed) {
    var nz = noise2(seed);
    return paintStrip(Wst, Hst, sink, Ltot, function (u, s, f, x) {
      if (look.toon) return [toonTail(look, look.coreRamp || look.ramp, s, f, u / Math.max(0.2, Wst / 2), Ltot, x), 1];
      var col = ramp(look.coreRamp || look.ramp, f);
      if (look.scales) {
        // scales: staggered rows, a light upper rim and a dark lower edge, smaller towards the tip
        var sz = lerp(0.62, 0.32, f), row = Math.floor(s / sz), q = s / sz - row, ux = u / sz + (row % 2) * 0.5, uq = ux - Math.floor(ux);
        var d0 = Math.abs(uq - 0.5) * 2;
        col = shade(col, q > 0.82 ? 0.16 : q < 0.14 + 0.12 * d0 * d0 ? -0.22 : 0);
        col = shade(col, (nz(x * 0.4, s * 2) - 0.5) * 0.1);
      } else {
        col = shade(col, (nz(x * 0.55, s * 0.35) - 0.5) * 0.18 - (look.coreDark === undefined ? 0.18 : look.coreDark));
        if (((x * 5 + Math.floor(s * 1.7)) % 13) === 0) col = shade(col, 0.1);
      }
      if (look.rings) col = ringsOn(look, col, s, Ltot, u, x);
      if (look.gloss && f > 0.75) col = shade(col, look.gloss * smooth(0.75, 1, f));
      return [col, 1];
    });
  }
  /**
   * a cartoon tail's colour: flat bands of the ramp (the tip a clean other colour), a shadow side, a light streak,
   * bold rings with clean edges; un = -1..1 across the strip
   */
  function toonTail(look, rmp, s, f, un, Ltot, x) {
    var T = look.toon, cuts = T.cuts || [], q = 0, fz = f + (T.zig ? T.zig * (Math.abs((((x || 0) / HD * 1.5) % 2 + 2) % 2 - 1) - 0.5) * 2 : 0);
    while (q < cuts.length && fz >= cuts[q]) q++;
    var col = ramp(rmp, cuts.length ? (q === 0 ? cuts[0] * 0.5 : q === cuts.length ? (1 + cuts[q - 1]) / 2 : (cuts[q - 1] + cuts[q]) / 2) : f);
    if (T.alt) col = T.alt[q % T.alt.length];
    if (s < 0.4) col = shade(col, -(T.root || 0.14));
    if (look.rings) { var r = look.rings, qq = s / (Ltot / r.n), fr = qq - Math.floor(qq); if (s > r.from * Ltot && s < r.to * Ltot && fr < r.k) col = lerpCol(col, r.col, 0.85); }
    return col;
  }
  /** tabby / raccoon rings: zig-zag edged bands of a darker colour */
  function ringsOn(look, col, s, Ltot, u, x) {
    var r = look.rings, q = s / (Ltot / r.n) + 0.12 * Math.sin(u * 3.1 + x * 0.2), fr = q - Math.floor(q);
    if (s > r.from * Ltot && s < r.to * Ltot && fr < r.k) col = lerpCol(col, r.col, 0.8);
    return col;
  }
  /** a sail-shaped spine (fx: out from the tail, fy: 0 = towards the tip), dark at the root, bone at the point */
  function spinePic(look, seed) {
    var W = 3 * HD, H = 4 * HD, d = new Uint8ClampedArray(W * H * 4), nz = noise2(seed);
    for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) {
      var fx = (x + 0.5) / W, fy = (y + 0.5) / H, lim = Math.pow(fy, 1.4) * (1 - 0.15 * (1 - fy));
      if (fx > lim) continue;
      var col = lerpCol(look.spine[0], look.spine[1], fx);
      if (look.toon) col = fx > lim * 0.55 ? look.spine[1] : look.spine[0];
      else col = shade(col, (nz(x * 0.5, y * 0.3) - 0.5) * 0.15 + (fx > lim * 0.85 ? 0.15 : 0));
      var j = (y * W + x) * 4; d[j] = (col >> 16) & 255; d[j + 1] = (col >> 8) & 255; d[j + 2] = col & 255; d[j + 3] = 255;
    }
    return { w: W, h: H, px: b64(d) };
  }
  /** the spade at the tip: an arrowhead, or a heart (v 0 = its point, away from the tail) */
  function spadePic(look, seed) {
    var W = 4 * HD, H = 4 * HD, d = new Uint8ClampedArray(W * H * 4), nz = noise2(seed), heart = look.spadeShape === 'heart';
    for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) {
      var u = (x + 0.5) / W * 2 - 1, v = (y + 0.5) / H, inside;
      if (heart) { var hx = u * 1.25, hy = v * 2.4 - 1.2, q = hx * hx + hy * hy - 1; inside = q * q * q - hx * hx * hy * hy * hy < 0; }
      else inside = Math.abs(u) < (v < 0.62 ? v / 0.62 : 1 - (v - 0.62) / 0.38 * 0.75) * 0.95;
      if (!inside) continue;
      var col = lerpCol(look.spadeCol[0], look.spadeCol[1], v);
      if (look.toon) col = shade(look.spadeCol[0], u < -0.45 ? -0.2 : (u > 0.1 && u < 0.45 && v > 0.25 && v < 0.75) ? 0.22 : 0);
      else col = shade(col, 0.18 * (1 - Math.abs(u)) - 0.1 * v + (nz(x * 0.4, y * 0.4) - 0.5) * 0.1);
      var j = (y * W + x) * 4; d[j] = (col >> 16) & 255; d[j + 1] = (col >> 8) & 255; d[j + 2] = col & 255; d[j + 3] = 255;
    }
    return { w: W, h: H, px: b64(d) };
  }
  /**
   * one tail: bones '@tail' > '@tail_2'... (aid pre + i), the root at o.root (TAIL_ROOT) turned back and down by droop
   * and round the body by yaw; o = {n, L, width {base, mid, tip, at}, droop, bends [per joint], cards (a star of
   * planes), octa, coreK (core width against the silhouette), spines [root, tip height], spade [w, h], look}; pics =
   * the pictures shared by every tail of the item -> {node, bones: [aid]}
   */
  function tailChain(o, pics, pre, nm) {
    var n = o.n, Ls = o.L / n, bones = [], top = null, up = null, R0 = o.root || TAIL_ROOT, gl = o.glow ? { glow: true } : {};
    for (var i = 0; i < n; i++) {
      var s = i * Ls, f = (i + 0.5) / n, w = tailWidth(o.width, f), y = R0[1] + s, aid = pre + i;
      var core = (o.coreK || 0.5) * w, e = i ? Math.max(0.45, core * 0.5) : 0.6, kids = [], cn = i ? '@seg_' + i : '@base';
      // (a furry tail's core stops short of the tip: the fur closes over it in a soft point, no box end showing)
      var cEnd = i === n - 1 ? (o.cards ? Ls * 0.55 : Ls + 0.2) : Ls;
      var box = [[R0[0] - core / 2, y - e, R0[2] - core / 2], [R0[0] + core / 2, y + cEnd, R0[2] + core / 2]];
      kids.push(C(cn, box[0], box[1], undefined, Object.assign({ tex: hornTex(pics.coreKey, pics.core, pics.cw, pics.h, pics.sink, core, core, s - e, s + Ls), alt: ramp(o.look.ramp, f) }, gl)));
      if (o.octa) {
        var c2 = C(cn, box[0], box[1], undefined, Object.assign({ tex: hornTex(pics.coreKey, pics.core, pics.cw, pics.h, pics.sink, core, core, s - e, s + Ls), alt: ramp(o.look.ramp, f) }, gl));
        c2.r = [0, 45, 0]; c2.o = [R0[0], sn(y + Ls / 2), R0[2]];
        kids.push(c2);
      }
      // fur cards: planes through the axis turned round it like a star (a little different on every segment)
      for (var k = 0; k < (o.cards || 0); k++) {
        var cy1 = Math.min(R0[1] + o.L + 0.2, y + Ls * 1.45);
        var card = C('@fur', [R0[0] - w / 2, y - 0.35, R0[2]], [R0[0] + w / 2, cy1, R0[2]], undefined,
          { tex: cardTex(pics.furKey, pics.fur, pics.fw, pics.h, pics.sink, w, s - 0.35, cy1 - R0[1]), alt: ramp(o.look.ramp, f) });
        card.r = [0, sn(k * 180 / o.cards + (i % 2 ? 11 : -11)), 0]; card.o = [R0[0], sn(y + Ls / 2), R0[2]];
        kids.push(card);
      }
      // a spine on the top of the tail (the chain's -z), smaller towards the tip
      if (o.spines && i < n - 1) {
        var sh = lerp(o.spines[0], o.spines[1], i / Math.max(1, n - 2)), z1 = R0[2] - core / 2 + 0.1;
        kids.push(C('@spike', [R0[0] - 0.01, y + 0.05, z1 - sh], [R0[0] + 0.01, y + 0.05 + Ls * 0.95, z1], undefined,
          { tex: { box: false, off: [0, 0], faces: ['east', 'west'].map(function (fn) { return { face: fn, rel: [0, 0, 3, 4], w: pics.spine.w, h: pics.spine.h, px: pics.spine.px, flip: [fn === 'east', false], rot: 0, key: pics.spineKey, hd: true }; }),
            fs: { north: -1, south: -1, up: -1, down: -1 } }, alt: o.look.spine[0] }));
      }
      if (o.spade && i === n - 1) {
        var ty = y + Ls - 0.2;
        kids.push(C('@spade', [R0[0] - o.spade[0] / 2, ty, R0[2] - 0.01], [R0[0] + o.spade[0] / 2, ty + o.spade[1], R0[2] + 0.01], undefined,
          Object.assign({ tex: plateTex(pics.spadeKey, pics.spade, 4, 4), alt: o.look.spadeCol[0] }, o.spadeGlow ? { glow: true } : {})));
        if (o.spadeCross) kids.push(C('@spade', [R0[0] - o.spade[0] / 2, ty, R0[2] - 0.01], [R0[0] + o.spade[0] / 2, ty + o.spade[1], R0[2] + 0.01], undefined,
          Object.assign({ tex: plateTex(pics.spadeKey, pics.spade, 4, 4), alt: o.look.spadeCol[0], r: [0, 90, 0], o: [R0[0], sn(ty + o.spade[1] / 2), R0[2]] }, o.spadeGlow ? { glow: true } : {})));
      }
      var r = i ? [o.bends ? o.bends[i] || 0 : 0, 0, 0] : [90 + (o.droop || 0), o.yaw || 0, 0];
      var g = Gp(i ? '@tail_' + (i + 1) : nm, [R0[0], y, R0[2]], r, kids, aid);
      bones.push(aid);
      if (up) up.kids.push(g); else top = g;
      up = g;
    }
    return { node: top, bones: bones };
  }
  /** the pictures of a tail kind (shared by all its chains: the nine tails of a kitsune use one set) */
  function tailPics(id, o) {
    var maxW = 0, n = 40; for (var i = 0; i <= n; i++) maxW = Math.max(maxW, tailWidth(o.width, i / n));
    var core = (o.coreK || 0.5) * maxW, seed = 300 + id.length * 13;
    var p = { h: Math.ceil(o.L + 1.2), sink: 0.6, fw: Math.max(1, Math.ceil(maxW + 0.01)), cw: Math.max(1, Math.ceil(core + 0.01)) };
    p.coreKey = 'tl_' + id + '_core'; p.core = coreStrip(o.look, o.L, p.cw, p.h, p.sink, seed);
    if (o.cards) { p.furKey = 'tl_' + id + '_fur'; p.fur = furCards(o.look, o.width, o.L, p.fw, p.h, p.sink, seed + 1); }
    if (o.spines) { p.spineKey = 'tl_' + id + '_spine'; p.spine = spinePic(o.look, seed + 2); }
    if (o.spade) { p.spadeKey = 'tl_' + id + '_spade'; p.spade = spadePic(o.look, seed + 3); }
    return p;
  }
  /**
   * the motion of one chain for a state: p = {P (s), amp [root, tip] (side swing, degrees), lag (part of a cycle per
   * joint), pitch [root, tip] (up / down), pitch2 (bob twice per cycle), roll, lift (root: + lower / - raise), curl (per
   * joint, + down), side (per joint, curls to one side), flick ({at, k}: a quick tip flick), noise, ph (this chain's
   * phase)} -> joints for bake()
   */
  function tailMotion(bones, p) {
    var J = {}, n = bones.length, ph = p.ph || 0;
    bones.forEach(function (aid, i) {
      var f = n > 1 ? i / (n - 1) : 0, A = lerp(p.amp[0], p.amp[1], f), Pp = p.pitch ? lerp(p.pitch[0], p.pitch[1], f) : 0, Rr = p.roll ? lerp(p.roll[0], p.roll[1], f) : 0;
      var lag = i * (p.lag || 0.1) + ph, P2 = p.pitch2 ? lerp(p.pitch2[0], p.pitch2[1], f) : 0;
      var env = p.env ? function (t) { return Math.sin(Math.PI * t); } : function () { return 1; };
      function sw(t) {
        var v = A * Math.sin(2 * Math.PI * (t - lag)) * env(t);
        // two unrelated sines on top (it never looks looped)
        if (p.noise) v += A * 0.22 * Math.sin(2 * Math.PI * (2 * t - lag * 1.3 + 0.37)) + A * 0.12 * Math.sin(2 * Math.PI * (3 * t - lag * 0.7 + 0.11));
        if (p.flick) { var dt = ((t - p.flick.at - lag * 0.5) % 1 + 1) % 1; v += p.flick.k * f * f * Math.exp(-Math.pow((dt - 0.05) / 0.04, 2)); }
        return v + (i ? (p.side || 0) * (0.6 + 0.8 * f) : 0);
      }
      function pt(t) { return (Pp * Math.sin(2 * Math.PI * (t - lag) + 1.2) + P2 * Math.sin(4 * Math.PI * (t - lag * 0.5))) * env(t) + (i ? (p.curl || 0) : (p.lift || 0)) * (p.env ? env(t) : 1); }
      function rl(t) { return Rr * Math.sin(2 * Math.PI * (t - lag) + 1.9); }
      // root: x lifts / lowers, y swings round the body's vertical axis; the others: x bends, z swings sideways
      J[aid] = { r: i ? [pt, rl, sw] : [pt, sw, rl] };
    });
    return J;
  }
  // the player states, and each tail family's motion in them (after the study's tables: fluffy canid / fox, thin
  // feline / whip, heavy reptile; amplitudes per joint root -> tip in degrees, lag per joint as part of the cycle)
  var STATES = [
    ['standing', '@anim_idle'], ['walking', '@anim_walk'], ['running', '@anim_run'], ['sneaking', '@anim_sneak'], ['sneak_walk', '@anim_sneakwalk'],
    ['swimming', '@anim_swim'], ['falling', '@anim_fall'], ['jumping', '@anim_jump'], ['riding', '@anim_sit'], ['sleeping', '@anim_sleep'], ['creative_flying', '@anim_fly']
  ];
  var FAMILY = {
    // (second study, measured on free animated models and mods: running tails move up and down, hardly sideways;
    // swimming tails only sideways; walking lifts the root about 15 degrees; idle 3-4 s, the tip about 2.6x the root)
    fluffy: {
      standing: { P: 3.6, amp: [3, 9], lag: 0.1, pitch: [1, 3], roll: [0, 2], noise: true },
      walking: { P: 1, amp: [5, 12], lag: 0.12, pitch2: [2, 3], lift: -15 },
      running: { P: 0.6, amp: [1, 2.5], lag: 0.12, pitch: [6, 12], lift: -22 },
      sneaking: { P: 5, amp: [2, 5], lag: 0.1, lift: 18, noise: true },
      sneak_walk: { P: 1.3, amp: [3, 7], lag: 0.12, lift: 15 },
      swimming: { P: 1.4, amp: [4, 10], lag: 0.14, lift: -40 },
      falling: { P: 1, amp: [4, 12], lag: 0.15, pitch: [3, 8], lift: -42 },
      jumping: { P: 0.8, amp: [2, 5], lag: 0.12, lift: -25 },
      riding: { P: 5, amp: [1, 4], lag: 0.1, lift: 26, side: 16, flick: { at: 0.6, k: 10 } },
      sleeping: { P: 6, amp: [0.5, 2], lag: 0.08, lift: 30, side: 24 },
      creative_flying: { P: 2.4, amp: [2, 4], lag: 0.12, pitch: [3, 10], lift: -50 }
    },
    thin: {
      standing: { P: 4, amp: [3, 10], lag: 0.15, pitch: [1, 4], roll: [0, 3], noise: true, flick: { at: 0.62, k: 16 } },
      walking: { P: 1, amp: [4, 10], lag: 0.15, pitch2: [2, 3], lift: -16 },
      running: { P: 0.6, amp: [1, 2], lag: 0.2, pitch: [6, 12], lift: -26 },
      sneaking: { P: 4.5, amp: [2, 6], lag: 0.15, lift: 20, flick: { at: 0.4, k: 12 } },
      sneak_walk: { P: 1.3, amp: [3, 7], lag: 0.15, lift: 18 },
      swimming: { P: 1.2, amp: [5, 12], lag: 0.15, lift: -42 },
      falling: { P: 0.9, amp: [5, 14], lag: 0.18, pitch: [4, 10], lift: -40 },
      jumping: { P: 0.8, amp: [2, 6], lag: 0.15, lift: -30 },
      riding: { P: 5, amp: [1, 5], lag: 0.12, lift: 26, side: 18, flick: { at: 0.5, k: 14 } },
      sleeping: { P: 6, amp: [0.5, 2], lag: 0.08, lift: 30, side: 26 },
      creative_flying: { P: 2.2, amp: [2, 5], lag: 0.14, pitch: [3, 10], lift: -52 }
    },
    heavy: {
      standing: { P: 5, amp: [1.5, 5], lag: 0.05, pitch: [0.5, 1.5], roll: [0, 1], noise: true },
      walking: { P: 1.2, amp: [3, 7], lag: 0.06, pitch2: [1, 2], lift: -4 },
      running: { P: 0.8, amp: [1, 2], lag: 0.06, pitch: [2, 5], lift: -12 },
      sneaking: { P: 6, amp: [1, 3], lag: 0.05, lift: 10 },
      sneak_walk: { P: 1.5, amp: [2, 5], lag: 0.06, lift: 8 },
      swimming: { P: 1.2, amp: [6, 15], lag: 0.12, lift: -16 },
      falling: { P: 1.2, amp: [2, 6], lag: 0.08, pitch: [2, 5], lift: -22 },
      jumping: { P: 1, amp: [1, 3], lag: 0.06, lift: -12 },
      riding: { P: 6, amp: [0.5, 2], lag: 0.05, lift: 14, side: 10 },
      sleeping: { P: 7, amp: [0.3, 1.2], lag: 0.04, lift: 18, side: 16 },
      creative_flying: { P: 2.6, amp: [1.5, 3], lag: 0.08, pitch: [2, 7], lift: -26 }
    }
  };
  // cartoon motion (research: idle sway 1.6 - 2.4 s, walk 0.55 s, run 0.36 s; root 3-10, tip up to 10 per joint, lag
  // 25-45 degrees per joint): the same states, livelier
  FAMILY.fluffy_toon = { standing: { P: 1.6, amp: [3, 8], lag: 0.08, pitch: [1, 2], roll: [2, 6] }, walking: { P: 0.55, amp: [4, 10], lag: 0.085, pitch2: [2, 4], lift: -18 },
    running: { P: 0.36, amp: [2, 5], lag: 0.11, pitch: [6, 10], lift: -32 }, sneaking: { P: 2.4, amp: [1.5, 4], lag: 0.08, lift: 18 }, sneak_walk: { P: 0.7, amp: [3, 7], lag: 0.09, lift: 15 },
    swimming: { P: 0.8, amp: [4, 12], lag: 0.125, lift: -40 }, falling: { P: 0.5, amp: [3, 10], lag: 0.1, pitch: [3, 8], lift: -40 }, jumping: { P: 0.6, amp: [2, 6], lag: 0.1, lift: -35 },
    riding: { P: 3.2, amp: [1, 3], lag: 0.08, lift: 30, side: 14 }, sleeping: { P: 4, amp: [0.5, 1.5], lag: 0.06, lift: 30, side: 24 }, creative_flying: { P: 1.2, amp: [2, 8], lag: 0.12, pitch: [2, 6], lift: -55 } };
  FAMILY.thin_toon = { standing: { P: 2.4, amp: [3, 9], lag: 0.1, pitch: [1, 3], roll: [1, 4], flick: { at: 0.6, k: 22 } }, walking: { P: 0.55, amp: [3, 9], lag: 0.11, pitch2: [2, 4], lift: -16 },
    running: { P: 0.36, amp: [1.5, 4], lag: 0.13, pitch: [5, 10], lift: -26 }, sneaking: { P: 2.4, amp: [2, 6], lag: 0.1, lift: 20, flick: { at: 0.4, k: 16 } }, sneak_walk: { P: 0.7, amp: [3, 7], lag: 0.11, lift: 18 },
    swimming: { P: 0.8, amp: [4, 12], lag: 0.125, lift: -42 }, falling: { P: 0.4, amp: [4, 14], lag: 0.12, pitch: [4, 10], lift: -40 }, jumping: { P: 0.6, amp: [2, 6], lag: 0.12, lift: -30 },
    riding: { P: 3.2, amp: [1, 4], lag: 0.1, lift: 26, side: 18, flick: { at: 0.5, k: 16 } }, sleeping: { P: 4, amp: [0.5, 1.5], lag: 0.06, lift: 30, side: 26 }, creative_flying: { P: 1.2, amp: [2, 6], lag: 0.12, pitch: [3, 8], lift: -52 } };
  FAMILY.heavy_toon = { standing: { P: 2.4, amp: [2, 6], lag: 0.07, pitch: [0.5, 2], roll: [0, 2] }, walking: { P: 0.6, amp: [4, 9], lag: 0.08, pitch2: [1, 3], lift: -4 },
    running: { P: 0.4, amp: [1.5, 3], lag: 0.08, pitch: [2, 6], lift: -12 }, sneaking: { P: 3, amp: [1, 3], lag: 0.06, lift: 10 }, sneak_walk: { P: 0.8, amp: [2, 5], lag: 0.07, lift: 8 },
    swimming: { P: 0.8, amp: [6, 15], lag: 0.12, lift: -16 }, falling: { P: 0.5, amp: [2, 6], lag: 0.08, pitch: [2, 5], lift: -22 }, jumping: { P: 0.6, amp: [1, 4], lag: 0.07, lift: -15 },
    riding: { P: 3.2, amp: [0.5, 2], lag: 0.05, lift: 14, side: 10 }, sleeping: { P: 4, amp: [0.3, 1.2], lag: 0.04, lift: 18, side: 16 }, creative_flying: { P: 1.4, amp: [1.5, 4], lag: 0.08, pitch: [2, 6], lift: -26 } };
  /** every state's animation for the chains of a tail item (chains: [{bones, ph (phase), k (amplitude scale)}]) */
  function tailAnims(family, chains) {
    var fam = FAMILY[family];
    return STATES.map(function (st) {
      var p = Object.assign({}, fam[st[0]]), joints = {};
      var LIFT = 12, SIDE = 8;
      if (st[0] === 'jumping') { p.P = 0.5; p.env = true; p.lift = Math.max(-28, Math.min(28, p.lift || 0)); }
      else { if (p.lift) p.lift = Math.max(-LIFT, Math.min(LIFT, p.lift)); if (p.side) p.side = Math.max(-SIDE, Math.min(SIDE, p.side)); }
      chains.forEach(function (ch) {
        Object.assign(joints, tailMotion(ch.bones, Object.assign({}, p, { ph: ch.ph || 0, amp: [p.amp[0] * (ch.k || 1), p.amp[1] * (ch.k || 1)] })));
      });
      // a key every frame or two (or three on long calm cycles): smooth at 20 fps, within CPM's 255 frames
      var frames = Math.round(p.P * 20), keys = Math.max(8, Math.round(frames / (p.P > 3 ? 3 : p.P > 1.5 ? 2 : 1)));
      var an = bake({ name: st[1], type: st[0], length: p.P, keys: keys, fps: 20, joints: joints });
      if (st[0] === 'jumping') an.mustFinish = true;
      return an;
    });
  }
  // the tail kinds (sizes in px of the player: L = length, width base / mid / tip with the widest point at 'at')
  var TAILS = [
    // fox: a big round brush, widest past the middle, a white tip (study: 0.7 of the body -> 14, 3 / 5 / 3 widest at
    // 60 %, droop 40, the tip up 15)
    { id: 'tail_fox', family: 'fluffy', o: { n: 8, L: 15, width: { base: 2.8, mid: 5.8, tip: 2.4, at: 0.58 }, droop: 36, bends: [0, -3, -4, -4, -5, -6, -8, -10], cards: 5, coreK: 0.45,
      look: { ramp: [[0, 0xb9531c], [0.55, 0xdb6f26], [0.8, 0xe8853a], [0.83, 0xf3ede4], [1, 0xffffff]], coreRamp: [[0, 0x8a3c12], [0.8, 0xa9531c], [0.84, 0xd8d0c4], [1, 0xe8e2d8]], clump: 1.1, cutK: 0.2 } } },
    // kitsune: nine fox tails rising behind in a fan, golden with white tips, each swinging a little after the next
    { id: 'tail_kitsune', family: 'fluffy', fan: { n: 9, spread: 136, lift: -16, dx: 1.5 }, o: { n: 7, L: 12.5, width: { base: 2.2, mid: 4.4, tip: 2.0, at: 0.58 }, droop: -18, bends: [0, -6, -7, -8, -9, -10, -12], cards: 3, coreK: 0.5,
      look: { ramp: [[0, 0xd99a3a], [0.6, 0xf0c060], [0.8, 0xf7d58a], [0.84, 0xfbf3e2], [1, 0xffffff]], coreRamp: [[0, 0xa86f22], [0.8, 0xc58a33], [0.84, 0xe0d6c4], [1, 0xeee8dc]], clump: 1.05, cutK: 0.2 } } },
    // cat: long and thin, low with a J at the end, tabby rings (study: 12 px, 1.5 / 1.5 / 1.2, 6-10 rings)
    { id: 'cat_tail', family: 'thin', o: { n: 9, L: 12, width: { base: 1.6, mid: 1.5, tip: 1.25, at: 0.5 }, droop: 50, bends: [0, 2, 2, 0, -6, -10, -14, -16, -16], octa: true, coreK: 1,
      look: { ramp: [[0, 0xd9822b], [1, 0xe39a4a]], coreDark: 0, rings: { n: 9, k: 0.38, from: 0.08, to: 1.01, col: 0x9c4f15 } } } },
    // wolf / husky: a thick brush hanging low, grey with a dark tip (study: 7-8 px, 3 / 3.5 / 2, hangs 60-70)
    { id: 'tail_wolf', family: 'fluffy', o: { n: 7, L: 11, width: { base: 3, mid: 4.4, tip: 2.6, at: 0.5 }, droop: 52, bends: [0, -2, -3, -3, -4, -5, -6], cards: 5, coreK: 0.5,
      look: { ramp: [[0, 0x77726d], [0.45, 0x938e88], [0.8, 0x6f6a65], [1, 0x2c2927]], coreRamp: [[0, 0x55514d], [0.8, 0x4a4643], [1, 0x221f1e]], clump: 1.0, cutK: 0.2 } } },
    // dragon: long and heavy, tapering, a spine on every segment and a spade (study: 20-30 px, 5 / 3 / 1, droop 15,
    // one spine per segment 3 -> 1 px)
    { id: 'tail_dragon', family: 'heavy', o: { n: 12, L: 22, width: { base: 4.2, mid: 2.8, tip: 1.0, at: 0.4 }, droop: 22, bends: [0, -2, -2, -1, 0, 1, 2, 2, 2, 1, 0, -2], octa: true, coreK: 1, spines: [2.6, 0.8], spade: [3.2, 3.4],
      look: { ramp: [[0, 0x2f5a3a], [0.6, 0x3f7448], [1, 0x5f9a5a]], scales: true, spine: [0x2a3f2c, 0xc8c2a8], spadeCol: [0xd8d0b4, 0x3f6a44], gloss: 0.05 } } },
    // demon: a long thin whip in a loose S with a heart spade (study: 16-24 px, 1 / 1 / 0.75)
    { id: 'tail_demon', family: 'thin', o: { n: 11, L: 18, width: { base: 1.3, mid: 1.05, tip: 0.8, at: 0.4 }, droop: 34, bends: [0, 6, 8, 6, 0, -8, -12, -12, -8, 0, 6], octa: true, coreK: 1, spade: [3.6, 3.8], spadeGlow: true,
      look: { ramp: [[0, 0x1c0e10], [0.7, 0x2e1214], [1, 0x4a1418]], coreDark: 0, gloss: 0.15, spadeCol: [0xff3a3a, 0x8a0e14], spadeShape: 'heart' } } }
  ];
  // cartoon tails: chunkier shapes, flat colour bands (a clean white tip, bold stripes), a shadow side and a light
  // streak, big round fur clumps, a big heart; the same motion families
  TAILS.push(
    { id: 'tail_fox_toon', family: 'fluffy_toon', o: { n: 6, L: 16, width: { base: 3.0, mid: 7.4, tip: 2.6, at: 0.6 }, droop: 32, bends: [0, -8, -12, -12, -10, -10], cards: 2, coreK: 0.85,
      look: { ramp: [[0, 0xd2581a], [0.69, 0xed7a21], [0.7, 0xfcf8f0], [1, 0xfffefa]], coreRamp: [[0, 0xd2581a], [0.69, 0xed7a21], [0.7, 0xe3e8f0], [1, 0xfcf8f0]], clump: 3.5, cutK: 0.08, toon: { cuts: [0.7], zig: 0.035 } } } },
    { id: 'tail_cat_toon', family: 'thin_toon', o: { n: 9, L: 15, width: { base: 2.0, mid: 2.0, tip: 1.9, at: 0.5 }, droop: 30, bends: [0, 0, -10, -18, -24, -30, -36, -40, -40], octa: true, coreK: 1,
      look: { ramp: [[0, 0xe08a3a], [1, 0xf5a345]], rings: { n: 6, k: 0.3, from: 0.12, to: 0.96, col: 0x99301c }, toon: { cuts: [] } } } },
    { id: 'tail_devil_toon', family: 'thin_toon', o: { n: 12, L: 20, width: { base: 1.6, mid: 1.3, tip: 1.0, at: 0.4 }, droop: 30, bends: [0, -10, -12, -12, -8, 0, 8, 12, 14, 12, 8, 4], octa: true, coreK: 1, spade: [6.5, 6.5], spadeCross: true,
      look: { ramp: [[0, 0x221c2c], [1, 0x2f2738]], spadeCol: [0xdb2733, 0xad113b], spadeShape: 'heart', toon: { cuts: [] } } } },
    { id: 'tail_dragon_toon', family: 'heavy_toon', o: { n: 6, L: 18, width: { base: 5.0, mid: 3.8, tip: 1.6, at: 0.2 }, droop: 25, bends: [0, -5, -8, -10, -12, -12], octa: true, coreK: 1, spines: [3.0, 1.4], spade: [4.4, 4.0], spadeCross: true,
      look: { ramp: [[0, 0x29838a], [1, 0x69d1b2]], spine: [0xcc6a14, 0xffe14d], spadeCol: [0xfab62d, 0xcc6a14], toon: { cuts: [0.5] } } } }
  );
  // lang: lib.p.{tail_fox_toon|tail_cat_toon|tail_devil_toon|tail_dragon_toon}
  function tailItem(spec, order) {
    var pics = tailPics(spec.id, spec.o), parts = [], chains = [];
    if (spec.fan) {
      // a kitsune: the tails fanned round the root, the middle ones higher
      for (var j = 0; j < spec.fan.n; j++) {
        var fj = spec.fan.n > 1 ? j / (spec.fan.n - 1) : 0.5, yaw = lerp(-spec.fan.spread / 2, spec.fan.spread / 2, fj), mid = 1 - Math.abs(fj - 0.5) * 2;
        var o = Object.assign({}, spec.o, { yaw: yaw, droop: spec.o.droop + spec.fan.lift * mid, root: [TAIL_ROOT[0] + Math.sin(yaw * DEG) * (spec.fan.dx || 0.6), TAIL_ROOT[1] + 0.4 * mid, TAIL_ROOT[2]] });
        var ch = tailChain(o, pics, 'k' + j + '_', '@tail');
        parts.push(ch.node); chains.push({ bones: ch.bones, ph: j * 0.13, k: 0.85 + 0.3 * ((j * 7) % 5) / 4 });
      }
    } else {
      var one = tailChain(spec.o, pics, 't', '@tail');
      parts.push(one.node); chains.push({ bones: one.bones, ph: 0, k: 1 });
    }
    return { id: spec.id, root: 'body', cat: 'tail', order: 40 + order, hd: HD, replaces: spec.id, parts: parts, anims: tailAnims(spec.family, chains) };
  }
  // lang: lib.p.{tail_fox|tail_kitsune|tail_wolf|tail_dragon|tail_demon}
  // lang: lib.n.{tail|fur|spade|anim_idle|anim_walk|anim_run|anim_sneak|anim_sneakwalk|anim_swim|anim_fall|anim_jump|anim_sit|anim_sleep|anim_fly}

  // =================================================================== low poly (16x pixel art, like a vanilla skin)
  // boxes of whole pixels, every face painted pixel by pixel in four tones of one colour: the lit side light, the
  // other side in shadow, the front / back base, the far end light and the root end dark; stripes / a tip colour by
  // segment. The chains use the same bones and motion as the HD parts.
  function pxPic(w, h, fn) { w = Math.max(1, Math.round(w)); h = Math.max(1, Math.round(h)); var d = new Uint8ClampedArray(w * h * 4); for (var j = 0; j < h; j++) for (var i = 0; i < w; i++) { var c = fn(i, j, w, h), k = (j * w + i) * 4; d[k] = (c >> 16) & 255; d[k + 1] = (c >> 8) & 255; d[k + 2] = c & 255; d[k + 3] = 255; } return { w: w, h: h, px: b64(d) }; }
  /** a low-poly box along a chain (local +y), w x len x d px, coloured by tones t = {light, base, shadow, dark}; key = shared pictures */
  function pxBox(name, at, y0, y1, w, d, t, key, x) {
    var len = y1 - y0, L = Math.max(1, Math.round(len)), W = Math.max(1, Math.round(w)), D = Math.max(1, Math.round(d));
    function side(tone, k, ww) { var p = pxPic(ww, L, function () { return tone; }); return { pic: p, rel: [0, 0, ww, L], key: key + k }; }
    var F = { east: side(t.light, 'e', D), west: side(t.shadow, 'w', D), north: side(t.base, 'n', W), south: side(t.base, 's', W) };
    var up = pxPic(W, D, function () { return t.light; }), dn = pxPic(W, D, function () { return t.dark; });
    var faces = Object.keys(F).map(function (k) { return { face: k, rel: F[k].rel, w: F[k].pic.w, h: F[k].pic.h, px: F[k].pic.px, flip: [false, false], rot: 0, key: F[k].key }; });
    faces.push({ face: 'up', rel: [0, 0, W, D], w: up.w, h: up.h, px: up.px, flip: [false, false], rot: 0, key: key + 'u' }, { face: 'down', rel: [0, 0, W, D], w: dn.w, h: dn.h, px: dn.px, flip: [false, false], rot: 0, key: key + 'd' });
    return C(name, [at[0] - w / 2, y0, at[2] - d / 2], [at[0] + w / 2, y1, at[2] + d / 2], undefined, Object.assign({ tex: { box: false, off: [0, 0], faces: faces }, alt: t.base }, x || {}));
  }
  // 4-shade ramps (dark, shadow, base, light) after the research's hue-shift rule
  var LP = { fox: { light: 0xff9e42, base: 0xed7a21, shadow: 0xd16024, dark: 0xb04923 }, white: { light: 0xfffefa, base: 0xfcf8f0, shadow: 0xcad0db, dark: 0xa4abba },
    ginger: { light: 0xffbf66, base: 0xf5a345, shadow: 0xd98643, dark: 0xb86b3e }, teal: { light: 0x69d1be, base: 0x46b8a8, shadow: 0x409c97, dark: 0x36787a },
    gold: { light: 0xffcc4d, base: 0xfab62d, shadow: 0xde952f, dark: 0xbd752d }, devil: { light: 0xf5494c, base: 0xdb2733, shadow: 0xbf283f, dark: 0x9e2642 },
    ivory: { light: 0xfff9eb, base: 0xf2e2c2, shadow: 0xd6c7b2, dark: 0xb5a89c }, tan: { light: 0xe0c29d, base: 0xc79d73, shadow: 0xab8468, dark: 0x8a6958 },
    pink: { light: 0xffd1e0, base: 0xfcb1cc, shadow: 0xe0a4bf, dark: 0xbf91aa } };
  function palOf(c) { return typeof c === 'number' ? tones(c) : c; }
  function tones(c, k) { k = k || 1; return { light: shade(c, 0.14 * k), base: c, shadow: shade(c, -0.16 * k), dark: shade(c, -0.3 * k) }; }
  /** a low-poly horn: o = {at, dir, curl, turn, drift, roll, single, name, segs: [[w, len], ...], col (per segment index -> colour)} */
  function lowHornItem(spec, order) {
    var parts = [];
    spec.chains.forEach(function (o, ci) {
      var n = o.segs.length, L = o.segs.reduce(function (a, sg) { return a + sg[1]; }, 0);
      var G = hornGeom({ dir: o.dir, curl: o.curl, L: L, t0: 2, n: n, turn: o.turn || 0, drift: o.drift, roll: o.roll, sink: 1 }), top = null, up = null, y = o.at[1], nm = o.name || 'horn', cnt = 0;
      G.segs.forEach(function (sg, i) {
        var w = o.segs[i][0], len = o.segs[i][1], e = i ? w / 2 * Math.tan(Math.abs(o.turn || 0) / Math.max(1, n - 1) * DEG / 2) + 0.1 : 1;
        var t = palOf(spec.col(i, n));
        var g = Gp(i ? '@seg_' + (++cnt) : '@' + nm + (o.single ? '' : '_r'), [o.at[0], y, o.at[2]], sg.r, [pxBox(i ? '@seg_' + cnt : '@base', o.at, y - e, y + len, w, w, t, 'lh_' + spec.id + '_' + ci + '_' + i)]);
        if (up) up.kids.push(g); else top = g;
        up = g; y += len;
      });
      if (!o.single) top.mirror = '@' + nm + '_l';
      parts.push(top);
    });
    return { id: spec.id, root: 'head', cat: 'head', order: 36 + order, hd: 1, replaces: spec.id, parts: parts };
  }
  var LOW_HORNS = [
    { id: 'horns_devil_lowpoly', col: function (i, n) { return i === n - 1 ? { light: 0xff6a5c, base: 0xf5494c, shadow: 0xdb2733, dark: 0xbf283f } : LP.devil; }, chains: [{ at: [2.5, 7.5, -1.5], dir: [0.34, 0.94, 0], curl: [-1, 0, 0], turn: 30, segs: [[2, 2], [1, 2]] }] },
    { id: 'horns_ram_lowpoly', col: function (i, n) { return i === n - 1 ? LP.tan : LP.ivory; }, chains: [{ at: [4.2, 6.6, 0.6], dir: [0.8, 0.5, 0.33], curl: [0, -0.6, 0.8], turn: 200, drift: 3, segs: [[2, 3], [2, 2], [2, 3], [1, 2]] }] },
    { id: 'horns_unicorn_lowpoly', col: function (i) { return i % 2 ? LP.pink : LP.ivory; }, chains: [{ single: true, at: [0, 7.4, -3.2], dir: [0, 0.94, -0.34], curl: [0, 0, -1], turn: 0, roll: 45, segs: [[2, 2], [2, 2], [1, 3]] }] }
  ];
  // lang: lib.p.{horns_devil_lowpoly|horns_ram_lowpoly|horns_unicorn_lowpoly}
  /** a low-poly tail: whole-pixel boxes on the same bones as the HD tails (so the same motion), o = {segs: [[w, len]], droop, bends, col} */
  function lowTailItem(spec, order) {
    var o = spec.o, R0 = TAIL_ROOT, bones = [], top = null, up = null, y = R0[1];
    o.segs.forEach(function (sg, i) {
      var aid = 'lt' + i, w = sg[0], len = sg[1], t = palOf(o.col(i, o.segs.length)), e = i ? Math.min(1, w / 2) : 0.6;
      var kids = [pxBox(i ? '@seg_' + i : '@base', R0, y - e, y + len, w, w, t, 'ltl_' + spec.id + '_' + i)];
      // spikes on the top (the chain's -z): one small box per segment
      if (o.spike && i < o.segs.length - 1) { var sp = palOf(o.spike), ym = y + len / 2; kids.push(pxBox('@spike', [R0[0], 0, R0[2] - w / 2 - 0.5], ym - 0.5, ym + 0.5, 1, 1, sp, 'ltl_' + spec.id + '_sp')); }
      var r = i ? [o.bends ? o.bends[i] || 0 : 0, 0, 0] : [90 + (o.droop || 0), 0, 0];
      var g = Gp(i ? '@tail_' + (i + 1) : '@tail', [R0[0], y, R0[2]], r, kids, aid);
      bones.push(aid);
      if (up) up.kids.push(g); else top = g;
      up = g; y += len;
    });
    return { id: spec.id, root: 'body', cat: 'tail', order: 50 + order, hd: 1, replaces: spec.id, parts: [top], anims: tailAnims(spec.family, [{ bones: bones, ph: 0, k: 1 }]) };
  }
  // (research: vanilla-like 1-4 boxes - the fox a 3 x 3 x 4 root, a 6 x 6 x 8 brush and a white 4 x 4 x 4 tip; the cat 2 x 2
  // boxes in a '?' (1-px tails are too thin at player scale); the dragon 5 -> 2 px with gold spikes)
  var LOW_TAILS = [
    { id: 'tail_fox_lowpoly', family: 'fluffy_toon', o: { segs: [[3, 3], [5, 7], [3, 3]], droop: 40, bends: [0, -50, 0], col: function (i) { return i === 2 ? LP.white : LP.fox; } } },
    { id: 'tail_cat_lowpoly', family: 'thin_toon', o: { segs: [[2, 7], [2, 6], [2, 4]], droop: 50, bends: [0, -80, -35], col: function () { return LP.ginger; } } },
    { id: 'tail_dragon_lowpoly', family: 'heavy_toon', o: { segs: [[4, 6], [3, 6], [2, 5]], droop: 20, bends: [0, -6, -10], col: function () { return LP.teal; }, spike: LP.gold } }
  ];
  // lang: lib.p.{tail_fox_lowpoly|tail_cat_lowpoly|tail_dragon_lowpoly}

  // =================================================================== the items
  var cache = null;
  function build() {
    return [catEarsItem(), bunnyEarsItem()].concat(ELVES.map(elfEarsItem), [elfLowItem(), haloItem(), crownItem(), topHatItem(), witchHatItem()], HORNS.map(hornItem), LOW_HORNS.map(lowHornItem), TAILS.map(tailItem), LOW_TAILS.map(lowTailItem));
  }
  api.items = function () { if (!cache) cache = build(); return cache; };
  api.hornGeom = hornGeom; api.HORNS = HORNS;
  api.curve = curve; api.bake = bake; api.wave = wave; api.HD = HD;
  return api;
})
