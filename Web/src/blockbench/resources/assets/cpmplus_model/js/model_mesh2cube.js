/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Malha para cubos: the meshes of a 3D model (another Blockbench tab: a model opened
 * with File > Open, an OBJ imported into a Generic model; or a .bbmodel file - no bones needed) become CPM cubes. The
 * surface is cut into little cubes of the size chosen (voxels), coloured from the mesh's texture (or its marker colour),
 * filled inside (a solid shape needs far fewer boxes), then joined into as few boxes as possible (the greedy merge of
 * Juntar cubos). Two results: CPM colour cubes (no skin space; the colours brought down to a palette so boxes can join)
 * or painted cubes (any colours: every box is painted with the exact colours of its little cubes, HD when they are
 * smaller than a pixel). Live count and 3D preview; one Ctrl+Z.
 * Loaded after model_convert (it reads the models the same way and puts the result in with its one-step insert).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  function CV() { return L.core.modules.model_convert; }
  function Lb() { return L.core.modules.model_library; }
  var MAX_CELLS = 3000000, MAX_PREVIEW = 3000, MAX_CUBES = 20000, PAD = 1;
  var DEG = Math.PI / 180;

  // =================================================================== the surface (the model's own pose), the texture colours
  function rot(a, deg) { var c = Math.cos(deg * DEG), s = Math.sin(deg * DEG); return a === 0 ? [1, 0, 0, 0, c, -s, 0, s, c] : a === 1 ? [c, 0, s, 0, 1, 0, -s, 0, c] : [c, -s, 0, s, c, 0, 0, 0, 1]; }
  function mul(A, B) { var o = new Array(9); for (var r = 0; r < 3; r++) for (var c = 0; c < 3; c++) o[r * 3 + c] = A[r * 3] * B[c] + A[r * 3 + 1] * B[3 + c] + A[r * 3 + 2] * B[6 + c]; return o; }
  function app(A, v) { return [A[0] * v[0] + A[1] * v[1] + A[2] * v[2], A[3] * v[0] + A[4] * v[1] + A[5] * v[2], A[6] * v[0] + A[7] * v[1] + A[8] * v[2]]; }
  function euler(r) { r = r || [0, 0, 0]; return mul(rot(2, r[2]), mul(rot(1, r[1]), rot(0, r[0]))); }
  /** every triangle of the chosen meshes in the model's space: [{p: [3 points], uv: [3], tex, col (marker colour)}] */
  function trianglesOf(src, meshIds) {
    var out = [];
    src.meshes.forEach(function (m) {
      if (meshIds && meshIds.indexOf(m.id) < 0) return;
      var R = euler(m.rotation), chain = m.chain || [];
      function place(v) { var p = app(R, [Number(v[0]) || 0, Number(v[1]) || 0, Number(v[2]) || 0]); p = [p[0] + m.origin[0], p[1] + m.origin[1], p[2] + m.origin[2]]; for (var c = chain.length - 1; c >= 0; c--) { var q = app(chain[c].R, p); p = [q[0] + chain[c].t[0], q[1] + chain[c].t[1], q[2] + chain[c].t[2]]; } return p; }
      m.faces.forEach(function (f) {
        var vk = f.v, pts = [], uvs = [];
        for (var i = 0; i < vk.length; i++) { var v = m.vertices[vk[i]]; if (!v) return; pts.push(place(v)); var u = f.uv && f.uv[vk[i]]; uvs.push(u ? [Number(u[0]) || 0, Number(u[1]) || 0] : [0, 0]); }
        var tris = vk.length === 3 ? [[0, 1, 2]] : [];
        if (vk.length === 4) { var q = quadOrder(pts); tris = [[q[0], q[1], q[2]], [q[0], q[2], q[3]]]; }
        else if (vk.length > 4) for (var k = 1; k + 1 < vk.length; k++) tris.push([0, k, k + 1]);
        tris.forEach(function (t) { out.push({ p: [pts[t[0]], pts[t[1]], pts[t[2]]], uv: [uvs[t[0]], uvs[t[1]], uvs[t[2]]], tex: f.tex, col: m.colour }); });
      });
    });
    return out;
  }
  function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function len(a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }
  function quadOrder(pts) {
    var best = null;
    [[0, 1, 2, 3], [0, 1, 3, 2], [0, 2, 1, 3]].forEach(function (o) {
      var a = pts[o[0]], b = pts[o[1]], c = pts[o[2]], d = pts[o[3]], h1 = cross(sub(b, a), sub(c, a)), h2 = cross(sub(c, a), sub(d, a));
      var area = h1[0] * h2[0] + h1[1] * h2[1] + h1[2] * h2[2] < 0 ? -1 : len(h1) + len(h2);
      if (!best || area > best.area) best = { o: o, area: area };
    });
    return best.o;
  }
  /** the box of some triangles */
  function boxOf(tris) {
    var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    tris.forEach(function (t) { t.p.forEach(function (q) { for (var a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], q[a]); hi[a] = Math.max(hi[a], q[a]); } }); });
    return lo[0] === Infinity ? null : { lo: lo, hi: hi };
  }

  // =================================================================== voxels
  /**
   * the little cubes a surface makes: cell = their size (source units). Every triangle is sampled at a quarter of a cell
   * (no gaps), each sample takes the texture's colour where it is (see-through texture = nothing). solid: the inside is
   * filled too (from the outside, everything not reachable through empty cells), coloured like the nearest surface.
   * -> {nx, ny, nz, lo, cell, fill (Uint8Array: 0 empty, 1 surface, 2 inside), rgb (Int32Array), count}
   */
  function voxelize(tris, src, cell, solid) {
    var b = boxOf(tris); if (!b) return null;
    var lo = [b.lo[0] - PAD * cell, b.lo[1] - PAD * cell, b.lo[2] - PAD * cell];
    var nx = Math.max(1, Math.ceil((b.hi[0] - b.lo[0]) / cell - 1e-9)) + 2 * PAD, ny = Math.max(1, Math.ceil((b.hi[1] - b.lo[1]) / cell - 1e-9)) + 2 * PAD, nz = Math.max(1, Math.ceil((b.hi[2] - b.lo[2]) / cell - 1e-9)) + 2 * PAD;
    if (nx * ny * nz > MAX_CELLS) { var e = new Error(T('m2c.toobig')); e.code = 'cells'; throw e; }
    var n = nx * ny * nz, sr = new Float64Array(n), sg = new Float64Array(n), sb = new Float64Array(n), sn = new Uint32Array(n), texs = src.textures, cc = [0, 0, 0, 0];
    function at(x, y, z) { return (y * nz + z) * nx + x; }
    tris.forEach(function (t) {
      var a = t.p[0], bb = t.p[1], c = t.p[2], m = Math.max(len(sub(bb, a)), len(sub(c, a)), len(sub(c, bb))), k = Math.max(1, Math.ceil(m / (cell * 0.25)));
      // a face lying right on a cell border goes to the cell behind it (inside the shape): samples nudged a hair against its normal
      var nrm = cross(sub(bb, a), sub(c, a)), nl = len(nrm) || 1, sh = [-nrm[0] / nl * cell * 0.02, -nrm[1] / nl * cell * 0.02, -nrm[2] / nl * cell * 0.02];
      var Tx = typeof t.tex === 'number' && texs[t.tex] && texs[t.tex].d ? texs[t.tex] : null;
      for (var i = 0; i <= k; i++) for (var j = 0; j <= k - i; j++) {
        // a hair inside the triangle (a sample on an edge would fall into the cell past it)
        var u = (i + 0.001) / (k + 0.003), v = (j + 0.001) / (k + 0.003), w = 1 - u - v;
        var px = a[0] * w + bb[0] * u + c[0] * v + sh[0], py = a[1] * w + bb[1] * u + c[1] * v + sh[1], pz = a[2] * w + bb[2] * u + c[2] * v + sh[2];
        var cr, cg, cb2;
        if (Tx) {
          var tu = t.uv[0][0] * w + t.uv[1][0] * u + t.uv[2][0] * v, tv = t.uv[0][1] * w + t.uv[1][1] * u + t.uv[2][1] * v;
          var x0 = Math.floor(tu * Tx.w / Tx.uvW), y0 = Math.floor(tv * Tx.h / Tx.uvH);
          x0 = Math.max(0, Math.min(Tx.w - 1, x0)); y0 = Math.max(0, Math.min(Tx.h - 1, y0));
          var o = (y0 * Tx.w + x0) * 4; if (Tx.d[o + 3] < 128) continue;
          cr = Tx.d[o]; cg = Tx.d[o + 1]; cb2 = Tx.d[o + 2];
        } else { cr = (t.col >> 16) & 255; cg = (t.col >> 8) & 255; cb2 = t.col & 255; }
        var gx = Math.floor((px - lo[0]) / cell), gy = Math.floor((py - lo[1]) / cell), gz = Math.floor((pz - lo[2]) / cell);
        if (gx < 0 || gy < 0 || gz < 0 || gx >= nx || gy >= ny || gz >= nz) continue;
        var id = at(gx, gy, gz); sr[id] += cr; sg[id] += cg; sb[id] += cb2; sn[id]++;
      }
    });
    var fill = new Uint8Array(n), rgb = new Int32Array(n), count = 0;
    for (var q = 0; q < n; q++) if (sn[q]) { fill[q] = 1; count++; rgb[q] = (Math.round(sr[q] / sn[q]) << 16) | (Math.round(sg[q] / sn[q]) << 8) | Math.round(sb[q] / sn[q]); }
    if (solid) {
      // the outside: every empty cell reachable from the border
      var out = new Uint8Array(n), stack = [];
      function seed(x, y, z) { var id2 = at(x, y, z); if (!fill[id2] && !out[id2]) { out[id2] = 1; stack.push(id2); } }
      for (var y = 0; y < ny; y++) for (var z = 0; z < nz; z++) { seed(0, y, z); seed(nx - 1, y, z); }
      for (var x = 0; x < nx; x++) for (var z2 = 0; z2 < nz; z2++) { seed(x, 0, z2); seed(x, ny - 1, z2); }
      for (var x2 = 0; x2 < nx; x2++) for (var y2 = 0; y2 < ny; y2++) { seed(x2, y2, 0); seed(x2, y2, nz - 1); }
      while (stack.length) {
        var id3 = stack.pop(), xx = id3 % nx, rest = (id3 - xx) / nx, zz = rest % nz, yy = (rest - zz) / nz;
        if (xx > 0) seed(xx - 1, yy, zz); if (xx < nx - 1) seed(xx + 1, yy, zz);
        if (yy > 0) seed(xx, yy - 1, zz); if (yy < ny - 1) seed(xx, yy + 1, zz);
        if (zz > 0) seed(xx, yy, zz - 1); if (zz < nz - 1) seed(xx, yy, zz + 1);
      }
      // the inside, coloured like the nearest surface (layer by layer inwards)
      var front = [];
      for (var i2 = 0; i2 < n; i2++) if (!fill[i2] && !out[i2]) { fill[i2] = 2; count++; }
      for (var i3 = 0; i3 < n; i3++) if (fill[i3] === 1) front.push(i3);
      var done = new Uint8Array(n); front.forEach(function (i4) { done[i4] = 1; });
      while (front.length) {
        var next = [];
        front.forEach(function (i5) {
          var x5 = i5 % nx, r5 = (i5 - x5) / nx, z5 = r5 % nz, y5 = (r5 - z5) / nz, nb = [];
          if (x5 > 0) nb.push(i5 - 1); if (x5 < nx - 1) nb.push(i5 + 1);
          if (z5 > 0) nb.push(i5 - nx); if (z5 < nz - 1) nb.push(i5 + nx);
          if (y5 > 0) nb.push(i5 - nx * nz); if (y5 < ny - 1) nb.push(i5 + nx * nz);
          nb.forEach(function (j5) { if (fill[j5] === 2 && !done[j5]) { done[j5] = 1; rgb[j5] = rgb[i5]; next.push(j5); } });
        });
        front = next;
      }
    }
    return { nx: nx, ny: ny, nz: nz, lo: lo, cell: cell, fill: fill, rgb: rgb, count: count };
  }

  // =================================================================== colours: a palette (k-means), so neighbouring cubes can join
  function paletteOf(vox, k) {
    var cols = [];
    for (var i = 0; i < vox.fill.length; i++) if (vox.fill[i] === 1) cols.push(vox.rgb[i]);
    if (!cols.length) return function (c) { return c; };
    var step = Math.max(1, Math.floor(cols.length / 20000)), pts = [];
    for (var j = 0; j < cols.length; j += step) pts.push([(cols[j] >> 16) & 255, (cols[j] >> 8) & 255, cols[j] & 255]);
    // seeds: spread over the colours (farthest point first)
    var C = [pts[0]], dist = pts.map(function (p) { return d2(p, C[0]); });
    while (C.length < Math.min(k, pts.length)) {
      var far = 0; for (var q = 1; q < pts.length; q++) if (dist[q] > dist[far]) far = q;
      if (dist[far] <= 0) break;
      C.push(pts[far]); for (var q2 = 0; q2 < pts.length; q2++) dist[q2] = Math.min(dist[q2], d2(pts[q2], pts[far]));
    }
    for (var it = 0; it < 8; it++) {
      var sum = C.map(function () { return [0, 0, 0, 0]; });
      pts.forEach(function (p) { var b = nearest(p); sum[b][0] += p[0]; sum[b][1] += p[1]; sum[b][2] += p[2]; sum[b][3]++; });
      C = C.map(function (c, ci) { var s = sum[ci]; return s[3] ? [s[0] / s[3], s[1] / s[3], s[2] / s[3]] : c; });
    }
    function d2(a, b) { var x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2]; return x * x + y * y + z * z; }
    function nearest(p) { var b = 0, bd = Infinity; for (var i2 = 0; i2 < C.length; i2++) { var d = d2(p, C[i2]); if (d < bd) { bd = d; b = i2; } } return b; }
    var cache = {};
    return function (c) {
      if (cache[c] !== undefined) return cache[c];
      var p = C[nearest([(c >> 16) & 255, (c >> 8) & 255, c & 255])];
      return (cache[c] = (Math.round(p[0]) << 16) | (Math.round(p[1]) << 8) | Math.round(p[2]));
    };
  }

  /**
   * as few boxes as possible for coloured cells (the greedy of Juntar cubos: along x, then z, then y), with wildcards:
   * key(i) > 0 a colour, -1 a hidden inside cell (joins a box of any colour), 0 empty. Leftover inside cells get boxes of
   * the most common colour. -> [{x, y, z, w, h, d, k}]
   */
  function greedyWild(nx, ny, nz, keyOf) {
    var n = nx * ny * nz, key = new Int32Array(n), used = new Uint8Array(n), out = [], counts = {};
    for (var i = 0; i < n; i++) { key[i] = keyOf(i); if (key[i] > 0) counts[key[i]] = (counts[key[i]] || 0) + 1; }
    var common = 0, best = -1; Object.keys(counts).forEach(function (k) { if (counts[k] > best) { best = counts[k]; common = Number(k); } });
    function at(x, y, z) { return (y * nz + z) * nx + x; }
    function ok(j, k) { return !used[j] && (key[j] === k || key[j] === -1); }
    for (var pass = 0; pass < 2; pass++) {
      for (var y1 = 0; y1 < ny; y1++) for (var z1 = 0; z1 < nz; z1++) for (var x1 = 0; x1 < nx; x1++) {
        var i0 = at(x1, y1, z1), k0 = key[i0];
        if (used[i0] || (pass === 0 ? k0 <= 0 : k0 !== -1)) continue;
        var k = pass === 0 ? k0 : (common || 1), w = 1, d = 1, h = 1, good;
        while (x1 + w < nx && ok(at(x1 + w, y1, z1), k)) w++;
        for (;;) { if (z1 + d >= nz) break; good = true; for (var a = 0; a < w; a++) if (!ok(at(x1 + a, y1, z1 + d), k)) { good = false; break; } if (!good) break; d++; }
        for (;;) {
          if (y1 + h >= ny) break; good = true;
          for (var c = 0; c < d && good; c++) for (var b = 0; b < w; b++) if (!ok(at(x1 + b, y1 + h, z1 + c), k)) { good = false; break; }
          if (!good) break; h++;
        }
        for (var yy = 0; yy < h; yy++) for (var zz = 0; zz < d; zz++) for (var xx = 0; xx < w; xx++) used[at(x1 + xx, y1 + yy, z1 + zz)] = 1;
        out.push({ x: x1, y: y1, z: z1, w: w, h: h, d: d, k: k });
      }
    }
    return out;
  }  // =================================================================== boxes, as a library item
  // lang: m2c.mode.{colour|paint}
  var FACES = [['west', 0, -1], ['east', 0, 1], ['down', 1, -1], ['up', 1, 1], ['north', 2, -1], ['south', 2, 1]];
  function b64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }
  /**
   * the cubes: o = {mode ('colour' | 'paint'), colours (palette size, 0 = every colour), sc (model px per source unit),
   * anchorOff, root, name} -> {item, boxes (count), cells}
   */
  function plan(vox, o) {
    var nx = vox.nx, ny = vox.ny, nz = vox.nz, fill = vox.fill, rgb = vox.rgb, paint = o.mode === 'paint';
    function at(x, y, z) { return (y * nz + z) * nx + x; }
    var pal = !paint && o.colours > 0 ? paletteOf(vox, o.colours) : null;
    // painted: the shape alone (Juntar cubos' greedy boxes); colour cubes: each colour, the hidden inside joining any of them
    var boxes = paint ? M.greedyBoxes(nx, ny, nz, function (x, y, z) { return fill[at(x, y, z)] ? 1 : 0; })
      : greedyWild(nx, ny, nz, function (i) { return !fill[i] ? 0 : fill[i] === 2 ? -1 : (pal ? pal(rgb[i]) : rgb[i]) + 1; });
    var s = vox.cell * o.sc, off = o.anchorOff || [0, 0, 0], base = [vox.lo[0] * o.sc + off[0], vox.lo[1] * o.sc + off[1], vox.lo[2] * o.sc + off[2]];
    function r4(v) { return Math.round(v * 10000) / 10000; }
    var cubes = [], key = 'm2c' + Date.now().toString(36);
    boxes.forEach(function (b, bi) {
      var f = [r4(base[0] + b.x * s), r4(base[1] + b.y * s), r4(base[2] + b.z * s)], t = [r4(base[0] + (b.x + b.w) * s), r4(base[1] + (b.y + b.h) * s), r4(base[2] + (b.z + b.d) * s)];
      var cube = { c: 1, n: partName(o.name) + '_' + (bi + 1), f: f, t: t };
      if (!paint) { cube.col = b.k - 1; cubes.push(cube); return; }
      // painted: each face that can be seen gets the colours of the little cubes along it (hidden faces stay off)
      var faces = [], fs = {}, dims = [b.w, b.h, b.d];
      FACES.forEach(function (F) {
        var name = F[0], ax = F[1], sign = F[2], ua = ax === 0 ? 2 : 0, va = ax === 1 ? 2 : 1, W = dims[ua], H = dims[va];
        var px = new Uint8ClampedArray(W * H * 4), seen = false;
        for (var v = 0; v < H; v++) for (var u = 0; u < W; u++) {
          // the little cube of the box under picture pixel (u, v) as Blockbench maps a face seen from outside (v = 0 at
          // the top), and the one just outside the face
          var c = name === 'south' ? [b.x + u, b.y + H - 1 - v, b.z + b.d - 1] : name === 'north' ? [b.x + W - 1 - u, b.y + H - 1 - v, b.z]
            : name === 'east' ? [b.x + b.w - 1, b.y + H - 1 - v, b.z + W - 1 - u] : name === 'west' ? [b.x, b.y + H - 1 - v, b.z + u]
            : name === 'up' ? [b.x + u, b.y + b.h - 1, b.z + v] : [b.x + u, b.y, b.z + H - 1 - v];
          var o2 = c.slice(); o2[ax] += sign;
          var outside = o2[0] < 0 || o2[1] < 0 || o2[2] < 0 || o2[0] >= nx || o2[1] >= ny || o2[2] >= nz || !fill[at(o2[0], o2[1], o2[2])];
          if (outside) seen = true;
          var col = rgb[at(c[0], c[1], c[2])], p = (v * W + u) * 4;
          px[p] = (col >> 16) & 255; px[p + 1] = (col >> 8) & 255; px[p + 2] = col & 255; px[p + 3] = 255;
        }
        if (!seen) { fs[name] = -1; return; }
        faces.push({ face: name, rel: [0, 0, r4(W * s), r4(H * s)], w: W, h: H, px: b64(new Uint8Array(px.buffer)), flip: [false, false], rot: 0, hd: true, key: key + '_' + bi + name.charAt(0) });
      });
      if (!faces.length) return;
      cube.tex = { box: false, off: [0, 0], faces: faces, fs: fs };
      cubes.push(cube);
    });
    var item = { v: 1, id: key, user: true, name: String(o.name || 'modelo').slice(0, 64), cat: 'other', sets: [{ root: o.root || '', anchor: (o.anchor || [0, 0, 0]).slice(), parts: [{ g: 1, n: partName(o.name), o: off.map(r4), kids: cubes }] }], created: Date.now() };
    // painted little cubes smaller than a pixel: HD pictures (that many texture pixels per unit)
    if (paint) item.hd = Math.max(1, Math.round(1 / s));
    return { item: item, boxes: cubes.length, cells: vox.count };
  }
  function partName(s) { return String(s || 'modelo').replace(/[^A-Za-z0-9_.\-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 40) || 'modelo'; }
  // =================================================================== the window
  // lang: m2c.cell.{q|h|1|2}
  var CELLS = { q: 0.25, h: 0.5, '1': 1, '2': 2 };
  var PIVOT = { head: [0, 24, 0], body: [0, 24, 0], right_arm: [5, 22, 0], left_arm: [-5, 22, 0], right_leg: [1.9, 12, 0], left_leg: [-1.9, 12, 0] };
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  var dlg = null, cur = null, lastResult = null, lastError = null;
  function whereOptions(guess) {
    var o = { auto: T('convert.where.auto', T('lib.root.' + guess)) };
    ROOTS.forEach(function (r) { if (Lb().rootGroup(r)) o[r] = T('lib.root.' + r); });
    return o;
  }
  /** the cubes for the window's choices (the little cubes kept while only the colours / place change) */
  function planFor(c, f) {
    var meshes = f.meshes === 'all' || !f.meshes ? null : f.meshes === 'sel' ? c.src.meshes.filter(function (m) { return m.selected; }).map(function (m) { return m.id; }) : [f.meshes];
    var size = Math.max(1, Number(f.size) || 1), sc = Math.abs(size - c.size0) < 1e-9 ? c.sc0 : size / c.pl.maxDim;
    var cell = (CELLS[f.cell] || 1) / sc, solid = f.solid !== false, key = JSON.stringify([meshes, cell, solid]);
    if (c.key !== key) {
      var mk = JSON.stringify(meshes);
      if (c.trisKey !== mk) { c.tris = trianglesOf(c.src, meshes); c.trisKey = mk; }
      c.vox = voxelize(c.tris, c.src, cell, solid); c.key = key;
    }
    if (!c.vox) throw new Error(T('convert.nomesh'));
    var root = f.where && f.where !== 'auto' && ROOTS.indexOf(f.where) >= 0 ? f.where : c.guess;
    // where it hangs from: the bottom middle (a model made around the player, or one going on the head: it stands on it), else its middle
    var b = boxOf(c.tris) || c.box, mid = [(b.lo[0] + b.hi[0]) / 2, (b.lo[1] + b.hi[1]) / 2, (b.lo[2] + b.hi[2]) / 2], pl = { built: c.pl.built, maxDim: c.pl.maxDim, attach: c.pl.built || root === 'head' ? [mid[0], b.lo[1], mid[2]] : mid };
    var off = [Number(f.offx) || 0, Number(f.offy) || 0, Number(f.offz) || 0], anchorOff = CV().engine.anchorOffOf(pl, root, sc, off);
    var p = plan(c.vox, { mode: f.mode === 'paint' ? 'paint' : 'colour', colours: Number(f.colours) || 0, sc: sc, anchorOff: anchorOff, root: root, anchor: (PIVOT[root] || [0, 0, 0]).slice(), name: c.label });
    p.root = root; p.sc = sc;
    // the skin space painted cubes need (UV units)
    p.need = 0; if (f.mode === 'paint') (function walk(list) { list.forEach(function (n) { if (n.g) walk(n.kids); else if (n.tex) n.tex.faces.forEach(function (fc) { p.need += Math.ceil(fc.rel[2] - 1e-6) * Math.ceil(fc.rel[3] - 1e-6); }); }); })(p.item.sets[0].parts);
    return p;
  }
  // lang: lib.cubes.{one}
  function cubesText(n) { return n === 1 ? T('lib.cubes.one') : T('lib.cubes', n); }
  function update(f) {
    var c = cur; if (!dlg || !c) return;
    var p;
    try { p = planFor(c, f); } catch (e) { M.clearPreview(); M.dialogNote(dlg, (e && e.message) || String(e), M.allCubes().length, true); return; }
    c.plan = p;
    var many = p.boxes > MAX_CUBES, note = T('m2c.count', cubesText(p.boxes), p.cells) + (p.need ? T('m2c.count.tex', p.need) : '') + (many ? ' \u00b7 ' + T('m2c.many', MAX_CUBES) : '');
    M.dialogNote(dlg, note, M.allCubes().length + p.boxes, many);
    try {
      var boxes = Lb().previewBoxes(p.item, { where: p.root });
      if (boxes.length <= MAX_PREVIEW) M.preview(boxes.map(function (b) { return { from: b.from, to: b.to, matrix: b.matrix || undefined, col: b.col }; }), 0x4fc3f7, { opacity: 0.9 });
      else M.clearPreview();
    } catch (e) { L.warn('mesh to cubes preview failed', e); }
  }
  function sourceOptions(c) {
    var o = {};
    CV().sourceTabs().forEach(function (p) { o['tab:' + p.uuid] = T('convert.src.tab', String(p.name || T('convert.src.noname'))); });
    if (c.kind === 'file') o.file = T('convert.src.file', c.label);
    o.pick = T('convert.src.pick');
    return o;
  }
  function open(info) {
    if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; }
    M.clearPreview();
    var src = info.src, c = { src: src, kind: info.kind, label: info.label, project: info.project };
    c.tris = trianglesOf(src, null); c.trisKey = 'null';
    var b = boxOf(c.tris); c.box = b;
    if (!b) { G.Blockbench.showMessageBox({ title: T('m2c'), message: T('convert.nomesh') }); return null; }
    var maxDim = Math.max(b.hi[0] - b.lo[0], b.hi[1] - b.lo[1], b.hi[2] - b.lo[2]) || 1;
    var built = b.hi[0] > -12 && b.lo[0] < 12 && b.hi[1] > 0 && b.lo[1] < 40 && b.hi[2] > -12 && b.lo[2] < 12 && maxDim <= 96 && b.lo[1] > -16 && !src.gltf;
    c.pl = { built: built, maxDim: maxDim, attach: [(b.lo[0] + b.hi[0]) / 2, (b.lo[1] + b.hi[1]) / 2, (b.lo[2] + b.hi[2]) / 2] };
    c.size0 = built ? Math.round(maxDim * 10) / 10 : Math.max(1, Math.round(Math.min(maxDim, 32)));
    c.sc0 = built ? 1 : c.size0 / maxDim;
    c.guess = CV().engine.guessRoot({ box: b }, [c.label, src.name].concat(src.meshes.map(function (m) { return m.name; })).join(' '), built);
    var sel = src.meshes.filter(function (m) { return m.selected; }).length, meshOpts = { all: T('convert.meshes.all', src.meshes.length) };
    if (sel && sel < src.meshes.length) meshOpts.sel = T('convert.meshes.sel', sel);
    if (src.meshes.length <= 16) src.meshes.forEach(function (m) { meshOpts[m.id] = m.name; });
    function more(f) { return !!(f && f.more); }
    var form = {
      source: { label: T('convert.f.source'), type: 'select', options: sourceOptions(c), value: c.kind === 'tab' ? 'tab:' + c.project.uuid : 'file' },
      meshes: { label: T('convert.f.meshes'), type: 'select', options: meshOpts, value: 'all', condition: function () { return src.meshes.length > 1; } },
      where: { label: T('convert.f.where'), type: 'select', options: whereOptions(c.guess), value: 'auto' },
      size: { label: T('convert.f.size'), type: 'number', value: c.size0, min: 1, max: 512, step: 1 },
      cell: { label: T('m2c.f.cell'), type: 'select', options: { q: T('m2c.cell.q'), h: T('m2c.cell.h'), '1': T('m2c.cell.1'), '2': T('m2c.cell.2') }, value: '1' },
      mode: { label: T('m2c.f.mode'), type: 'select', options: { colour: T('m2c.mode.colour'), paint: T('m2c.mode.paint') }, value: 'colour' },
      colours: { label: T('m2c.f.colours'), type: 'select', options: { '8': T('m2c.colours.n', 8), '16': T('m2c.colours.n', 16), '32': T('m2c.colours.n', 32), '64': T('m2c.colours.n', 64), '0': T('m2c.colours.all') }, value: '16', condition: function (f) { return !f || f.mode !== 'paint'; } },
      solid: { label: T('m2c.f.solid'), type: 'checkbox', value: true },
      more: { label: T('convert.f.more'), type: 'checkbox', value: false },
      offx: { label: T('convert.f.offx'), type: 'number', value: 0, step: 0.5, min: -256, max: 256, condition: more },
      offy: { label: T('convert.f.offy'), type: 'number', value: 0, step: 0.5, min: -256, max: 256, condition: more },
      offz: { label: T('convert.f.offz'), type: 'number', value: 0, step: 0.5, min: -256, max: 256, condition: more }
    };
    var tris = 0; src.meshes.forEach(function (m) { tris += m.faces.length; });
    cur = c;
    dlg = new G.Dialog({
      id: 'cpmplus_model_mesh2cube', title: T('m2c'), width: 520, buttons: [T('m2c.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('m2c.tip')) + '</p>', '<p class="cmp_cv_info">' + L.esc(T('m2c.info', c.label, src.meshes.length, tris)) + '</p>'],
      form: form,
      onFormChange: function (f) {
        if (f.source && f.source !== form.source.value) {
          var pick = f.source;
          setTimeout(function () {
            if (pick === 'pick') { close(); pickFile(); return; }
            var p = CV().sourceTabs().filter(function (x) { return 'tab:' + x.uuid === pick; })[0];
            if (p) { close(); CV().fromTab(p).then(open); }
          }, 0);
          return;
        }
        update(f);
      },
      onConfirm: function (f) {
        var p = null;
        try { p = planFor(c, f); } catch (e) { G.Blockbench.showQuickMessage((e && e.message) || String(e), 4000); return false; }
        if (p.boxes > MAX_CUBES) { G.Blockbench.showQuickMessage(T('m2c.many', MAX_CUBES), 4000); return false; }
        M.clearPreview(); dlg = null; cur = null;
        try {
          var res = CV().insertItem(p.item, { hd: p.item.hd, where: p.root });
          res.item = p.item; lastResult = res; lastError = null;
          G.Blockbench.showQuickMessage(T('m2c.done', cubesText(res.cubes.length)), 2500);
        } catch (e) { lastError = (e && e.message) || String(e); G.Blockbench.showMessageBox({ title: T('m2c'), message: T('error') + '\n' + lastError }); }
      },
      onCancel: function () { M.clearPreview(); dlg = null; cur = null; }
    });
    function close() { if (dlg) { try { dlg.close(); } catch (e) { } } M.clearPreview(); dlg = null; cur = null; }
    dlg.show();
    try { update(dlg.getFormResult()); } catch (e) { L.warn('mesh to cubes window failed', e); }
    return dlg;
  }
  function pickFile() {
    G.Blockbench.import({ resource_id: 'cpmplus_convert', extensions: ['bbmodel', 'glb', 'gltf'], type: T('convert.file'), readtype: 'binary' }, function (files) {
      var f = files && files[0]; if (!f) return;
      CV().fromFile(f).then(open, function (e) { lastError = e && e.message; G.Blockbench.showMessageBox({ title: T('m2c'), message: e && e.message ? e.message : String(e) }); });
    });
  }
  function run() {
    if (!M.isCpm()) {
      var cpmTab = (G.ModelProject && G.ModelProject.all || []).filter(function (p) { return p && p.format && p.format.id === 'cpm'; })[0], here = G.Project;
      if (cpmTab && here && (here.elements || []).some(function (e) { return e && e.type === 'mesh'; })) { cpmTab.select(); setTimeout(function () { CV().fromTab(here).then(open); }, 50); return; }
      G.Blockbench.showMessageBox({ title: T('m2c'), message: T('m2c.cpmonly') }); lastError = 'cpm'; return;
    }
    var tabs = CV().sourceTabs();
    if (tabs.length) CV().fromTab(tabs[tabs.length - 1]).then(open, function (e) { G.Blockbench.showMessageBox({ title: T('m2c'), message: (e && e.message) || String(e) }); });
    else pickFile();
  }
  /** File > Import: right after 'Converter modelo 3D' */
  function placeInImport(action) {
    var file = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.file, imp = null;
    if (!file || !Array.isArray(file.structure)) return;
    file.structure.forEach(function (x) { if (x && x.id === 'import') imp = x; });
    if (!imp || !Array.isArray(imp.children)) return;
    var kids = imp.children;
    for (var i = kids.length - 1; i >= 0; i--) if (kids[i] && kids[i] !== action && kids[i].id === action.id) kids.splice(i, 1);
    if (kids.indexOf(action) >= 0) return;
    var at = -1; kids.forEach(function (x, k) { var id = typeof x === 'string' ? x : x && x.id; if (id === 'cpmplus_model_convert' || (at < 0 && (id === 'cpmplus_model_vox' || id === 'import_obj'))) at = k; });
    kids.splice(at >= 0 ? at + 1 : kids.length, 0, action);
    L.onUninstall(function () { var k = kids.indexOf(action); if (k >= 0) kids.splice(k, 1); });
  }
  api.install = function () {
    var action = M.register({ id: 'cpmplus_model_mesh2cube', sec: 'create', icon: 'view_comfy', key: 'm2c', spots: [], run: function () { run(); } });
    placeInImport(action);
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.trianglesOf = trianglesOf; api.voxelize = voxelize; api.plan = plan; api.paletteOf = paletteOf; api.planFor = planFor;
  api.run = run; api.open = open; api.dialog = function () { return dlg; }; api.current = function () { return cur; };
  api.lastResult = function () { return lastResult; }; api.lastError = function () { return lastError; };
  api.limits = { cells: MAX_CELLS, cubes: MAX_CUBES };
  return api;
})
