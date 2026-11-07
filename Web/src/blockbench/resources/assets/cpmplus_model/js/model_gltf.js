/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - reads glTF 2.0 models (.glb, or .gltf with its data inside) for 'Converter modelo
 * 3D em pecas' and 'Malha para cubos': Blockbench 5 has no glTF import, and the 3D models people download (Sketchfab,
 * Quaternius...) come as GLB. The model becomes the same description the converter reads from a .bbmodel: the skin's
 * joints as Blockbench-style armature bones (origin = offset from the parent's head in the parent's turned frame, rest
 * turn as euler ZYX, animation keys added to the rest), their bind frames from the inverse bind matrices, every triangle
 * primitive as a mesh with its UVs and vertex weights, the base colour picture (or the material's colour), and every
 * animation sampled at 30 frames a second (linear, step and cubic spline as glTF plays them).
 * Units and axes: 1 m = 16 px (one block); a glTF model faces +Z, a player faces -Z, so the model is turned half round
 * (its left side lands on the player's left). Data only (no Blockbench objects).
 */
(function (L) {
  'use strict';
  var M = L && L.core && L.core.modules && L.core.modules.model, T = M ? M.T : function (k) { return k; };
  var api = {};
  var SCALE = 16, FPS = 30, DEG = Math.PI / 180;

  // =================================================================== maths (4 x 4 column-major as glTF; 3 x 3 row-major as the converter)
  function I4() { return [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]; }
  function mul4(a, b) {
    var o = new Array(16);
    for (var c = 0; c < 4; c++) for (var r = 0; r < 4; r++) o[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
    return o;
  }
  function trs(t, q, s) {
    var x = q[0], y = q[1], z = q[2], w = q[3], sx = s[0], sy = s[1], sz = s[2];
    return [(1 - 2 * (y * y + z * z)) * sx, (2 * (x * y + z * w)) * sx, (2 * (x * z - y * w)) * sx, 0,
      (2 * (x * y - z * w)) * sy, (1 - 2 * (x * x + z * z)) * sy, (2 * (y * z + x * w)) * sy, 0,
      (2 * (x * z + y * w)) * sz, (2 * (y * z - x * w)) * sz, (1 - 2 * (x * x + y * y)) * sz, 0, t[0], t[1], t[2], 1];
  }
  function inv4(m) {
    var a = m, o = new Array(16);
    o[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
    o[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
    o[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
    o[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
    o[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
    o[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
    o[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
    o[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
    o[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
    o[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
    o[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
    o[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
    o[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
    o[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
    o[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
    o[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
    var det = a[0] * o[0] + a[1] * o[4] + a[2] * o[8] + a[3] * o[12];
    if (!det) return I4();
    for (var i = 0; i < 16; i++) o[i] /= det;
    return o;
  }
  // our world = the model turned half round (+Z front -> -Z front) and 16 px a metre
  var C4 = [-SCALE, 0, 0, 0, 0, SCALE, 0, 0, 0, 0, -SCALE, 0, 0, 0, 0, 1];
  function point(m, v) { return [m[0] * v[0] + m[4] * v[1] + m[8] * v[2] + m[12], m[1] * v[0] + m[5] * v[1] + m[9] * v[2] + m[13], m[2] * v[0] + m[6] * v[1] + m[10] * v[2] + m[14]]; }
  /** the turn of a 4 x 4 (scale taken out, columns made orthonormal) as a row-major 3 x 3, and its translation */
  function frameOf(m) {
    var c0 = [m[0], m[1], m[2]], c1 = [m[4], m[5], m[6]];
    var n0 = norm(c0), c1o = sub(c1, scl(n0, dot(n0, c1))), n1 = norm(c1o), n2 = cross(n0, n1);
    if (dot(n2, [m[8], m[9], m[10]]) < 0) n2 = scl(n2, -1);
    return { R: [n0[0], n1[0], n2[0], n0[1], n1[1], n2[1], n0[2], n1[2], n2[2]], P: [m[12], m[13], m[14]] };
  }
  function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
  function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  function scl(a, k) { return [a[0] * k, a[1] * k, a[2] * k]; }
  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function norm(a) { var l = Math.sqrt(dot(a, a)) || 1; return [a[0] / l, a[1] / l, a[2] / l]; }
  function tr3(A) { return [A[0], A[3], A[6], A[1], A[4], A[7], A[2], A[5], A[8]]; }
  function mul3(A, B) { var o = new Array(9); for (var r = 0; r < 3; r++) for (var c = 0; c < 3; c++) o[r * 3 + c] = A[r * 3] * B[c] + A[r * 3 + 1] * B[3 + c] + A[r * 3 + 2] * B[6 + c]; return o; }
  function app3(A, v) { return [A[0] * v[0] + A[1] * v[1] + A[2] * v[2], A[3] * v[0] + A[4] * v[1] + A[5] * v[2], A[6] * v[0] + A[7] * v[1] + A[8] * v[2]]; }
  /** Blockbench's euler (degrees, order ZYX: R = Rz Ry Rx) of a row-major 3 x 3 */
  function eulerOf(R) {
    var b = Math.asin(Math.max(-1, Math.min(1, -R[6])));
    if (Math.abs(R[6]) > 0.99999) return [0, b / DEG, Math.atan2(-R[1], R[4]) / DEG];
    return [Math.atan2(R[7], R[8]) / DEG, b / DEG, Math.atan2(R[3], R[0]) / DEG];
  }
  function slerp(a, b, f) {
    var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3], bb = b;
    if (d < 0) { d = -d; bb = [-b[0], -b[1], -b[2], -b[3]]; }
    if (d > 0.9995) { var o = [a[0] + (bb[0] - a[0]) * f, a[1] + (bb[1] - a[1]) * f, a[2] + (bb[2] - a[2]) * f, a[3] + (bb[3] - a[3]) * f], l = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2] + o[3] * o[3]) || 1; return [o[0] / l, o[1] / l, o[2] / l, o[3] / l]; }
    var th = Math.acos(d), s = Math.sin(th), wa = Math.sin((1 - f) * th) / s, wb = Math.sin(f * th) / s;
    return [a[0] * wa + bb[0] * wb, a[1] * wa + bb[1] * wb, a[2] * wa + bb[2] * wb, a[3] * wa + bb[3] * wb];
  }

  // =================================================================== the file: GLB, or glTF JSON with its buffers inside
  function fail() { throw new Error(T('convert.bad')); }
  function u8Of(buf) { return buf instanceof Uint8Array ? buf : new Uint8Array(buf); }
  function textOf(u8) { if (typeof TextDecoder !== 'undefined') return new TextDecoder().decode(u8); var s = ''; for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]); try { return decodeURIComponent(escape(s)); } catch (e) { return s; } }
  function fromB64(s) {
    if (typeof atob === 'function') { var bin = atob(s), u = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; }
    return new Uint8Array(Buffer.from(s, 'base64'));
  }
  function toB64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return typeof btoa === 'function' ? btoa(s) : Buffer.from(s, 'binary').toString('base64'); }
  /** -> {g (the glTF JSON), buffers: [Uint8Array]} */
  function parse(buf) {
    var u8 = u8Of(buf), g = null, bin = null;
    if (u8.length >= 20 && u8[0] === 0x67 && u8[1] === 0x6C && u8[2] === 0x54 && u8[3] === 0x46) {
      var dv = new DataView(u8.buffer, u8.byteOffset, u8.length), total = Math.min(dv.getUint32(8, true), u8.length), p = 12;
      while (p + 8 <= total) {
        var len = dv.getUint32(p, true), type = dv.getUint32(p + 4, true);
        if (p + 8 + len > total) fail();
        if (type === 0x4E4F534A) { try { g = JSON.parse(textOf(u8.subarray(p + 8, p + 8 + len))); } catch (e) { fail(); } }
        else if (type === 0x004E4942) bin = u8.subarray(p + 8, p + 8 + len);
        p += 8 + len;
      }
    } else { try { g = JSON.parse(textOf(u8)); } catch (e) { fail(); } }
    if (!g || typeof g !== 'object' || !g.asset || !Array.isArray(g.nodes)) fail();
    var buffers = (g.buffers || []).map(function (b, i) {
      if (b.uri === undefined && i === 0 && bin) return bin;
      var m = /^data:[^;]*;base64,(.*)$/.exec(b.uri || '');
      if (m) return fromB64(m[1]);
      var e = new Error(T('gltf.external')); e.code = 'external'; throw e;
    });
    return { g: g, buffers: buffers };
  }
  var NCOMP = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4, MAT4: 16 };
  /** an accessor's numbers as floats (normalized integers scaled to 0..1) -> {a: Float64Array, n (count), k (components)} */
  function accessor(F, i) {
    var acc = F.g.accessors && F.g.accessors[i]; if (!acc) fail();
    var k = NCOMP[acc.type] || 1, n = acc.count || 0, out = new Float64Array(n * k);
    if (acc.bufferView === undefined) return { a: out, n: n, k: k };
    var bv = F.g.bufferViews[acc.bufferView], src = F.buffers[bv.buffer]; if (!src) fail();
    var ct = acc.componentType, size = ct === 5126 || ct === 5125 ? 4 : ct === 5123 || ct === 5122 ? 2 : 1;
    var stride = bv.byteStride || size * k, base = (bv.byteOffset || 0) + (acc.byteOffset || 0);
    if (base + stride * Math.max(0, n - 1) + size * k > src.length) fail();
    var dv = new DataView(src.buffer, src.byteOffset, src.length), norm2 = !!acc.normalized;
    for (var e = 0; e < n; e++) for (var c = 0; c < k; c++) {
      var o = base + e * stride + c * size, v;
      if (ct === 5126) v = dv.getFloat32(o, true);
      else if (ct === 5125) v = dv.getUint32(o, true);
      else if (ct === 5123) { v = dv.getUint16(o, true); if (norm2) v /= 65535; }
      else if (ct === 5122) { v = dv.getInt16(o, true); if (norm2) v = Math.max(-1, v / 32767); }
      else if (ct === 5121) { v = dv.getUint8(o); if (norm2) v /= 255; }
      else { v = dv.getInt8(o); if (norm2) v = Math.max(-1, v / 127); }
      out[e * k + c] = v;
    }
    return { a: out, n: n, k: k };
  }

  // =================================================================== nodes and poses
  function nodeTRS(nd) {
    if (Array.isArray(nd.matrix) && nd.matrix.length === 16) return { m: nd.matrix.slice() };
    return { t: (nd.translation || [0, 0, 0]).slice(), r: (nd.rotation || [0, 0, 0, 1]).slice(), s: (nd.scale || [1, 1, 1]).slice() };
  }
  /** world matrices (our world: C4 applied) of every node, with some nodes' TRS replaced (over: {node: {t, r, s}}) */
  function worlds(g, parents, over) {
    var W = new Array(g.nodes.length);
    function get(i) {
      if (W[i]) return W[i];
      var b = nodeTRS(g.nodes[i]), o = over && over[i], local;
      if (b.m && !o) local = b.m;
      else { var t = (o && o.t) || b.t || [0, 0, 0], r = (o && o.r) || b.r || [0, 0, 0, 1], s = (o && o.s) || b.s || [1, 1, 1]; local = trs(t, r, s); }
      var p = parents[i];
      return (W[i] = p >= 0 ? mul4(get(p), local) : mul4(C4, local));
    }
    for (var i = 0; i < g.nodes.length; i++) get(i);
    return W;
  }
  /** a sampler's value at time t (LINEAR / STEP / CUBICSPLINE as glTF defines them; rotations slerped) */
  function sample(S, t, rot) {
    var times = S.inp, n = times.length, k = S.k, cubic = S.interp === 'CUBICSPLINE';
    function val(i) { var o = cubic ? (i * 3 + 1) * k : i * k, v = []; for (var c = 0; c < k; c++) v.push(S.out[o + c]); return v; }
    if (!n) return null;
    if (t <= times[0]) return val(0);
    if (t >= times[n - 1]) return val(n - 1);
    var lo = 0, hi = n - 1; while (hi - lo > 1) { var m = (lo + hi) >> 1; if (times[m] <= t) lo = m; else hi = m; }
    var dt = times[hi] - times[lo], f = dt > 0 ? (t - times[lo]) / dt : 0;
    if (S.interp === 'STEP') return val(lo);
    if (cubic) {
      var f2 = f * f, f3 = f2 * f, h00 = 2 * f3 - 3 * f2 + 1, h10 = f3 - 2 * f2 + f, h01 = -2 * f3 + 3 * f2, h11 = f3 - f2, out = [];
      for (var c = 0; c < k; c++) {
        var p0 = S.out[(lo * 3 + 1) * k + c], m0 = S.out[(lo * 3 + 2) * k + c] * dt, p1 = S.out[(hi * 3 + 1) * k + c], m1 = S.out[(hi * 3) * k + c] * dt;
        out.push(h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1);
      }
      if (rot) { var l = Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]) || 1; out = out.map(function (x) { return x / l; }); }
      return out;
    }
    var a = val(lo), b = val(hi);
    return rot ? slerp(a, b, f) : a.map(function (x, i) { return x + (b[i] - x) * f; });
  }

  // =================================================================== glTF -> the converter's model description
  function srgb(c) { c = Math.max(0, Math.min(1, c)); return Math.round(255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055)); }
  /**
   * reads a .glb / .gltf (ArrayBuffer or Uint8Array) -> src {name, bones, meshes, textures, anims, skipped} (see
   * model_convert fromJSON). Throws an Error with a readable message on a file it cannot use.
   */
  function read(buf, name) {
    var F = parse(buf), g = F.g, N = g.nodes.length, parents = new Array(N).fill(-1);
    g.nodes.forEach(function (nd, i) { (nd.children || []).forEach(function (c) { if (c >= 0 && c < N && parents[c] < 0 && c !== i) parents[c] = i; }); });
    // no loops
    for (var i0 = 0; i0 < N; i0++) { var seen = {}, p0 = parents[i0]; while (p0 >= 0) { if (seen[p0] || p0 === i0) { parents[i0] = -1; break; } seen[p0] = 1; p0 = parents[p0]; } }
    var skins = g.skins || [], isJoint = {}, ibm = {}, jointK = {};
    var rest0 = worlds(g, parents, null);
    function colScale(m) { return Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]); }
    skins.forEach(function (sk) {
      var mats = sk.inverseBindMatrices !== undefined ? accessor(F, sk.inverseBindMatrices) : null, ks = [];
      (sk.joints || []).forEach(function (j, k) {
        if (j < 0 || j >= N) return; isJoint[j] = 1;
        var m = mats ? Array.prototype.slice.call(mats.a, k * 16, k * 16 + 16) : I4(); if (!ibm[j]) ibm[j] = m;
        // the joint's size in the world over its size when bound (exporters that bind in a scaled mesh space: 100)
        ks.push(colScale(rest0[j]) / SCALE / (colScale(inv4(m)) || 1));
      });
      ks.sort(function (a, b) { return a - b; });
      sk.__k = ks.length ? ks[ks.length >> 1] : 1;
      (sk.joints || []).forEach(function (j) { if (jointK[j] === undefined) jointK[j] = sk.__k; });
    });
    var rest = rest0;
    function jointParent(i) { for (var p = parents[i]; p >= 0; p = parents[p]) if (isJoint[p]) return p; return -1; }
    function boneId(i) { return 'j' + i; }
    // ---- bones: Blockbench-style local values from the world frames (any scale in between is already in the positions)
    var bones = [], local = {};
    function localOf(Wd, i) {
      var f = frameOf(Wd[i]), jp = jointParent(i);
      if (jp < 0) return { r: eulerOf(f.R), o: f.P, R: f.R };
      var fp = frameOf(Wd[jp]), Rt = tr3(fp.R);
      return { r: eulerOf(mul3(Rt, f.R)), o: app3(Rt, sub(f.P, fp.P)), R: f.R };
    }
    Object.keys(isJoint).map(Number).sort(function (a, b) { return a - b; }).forEach(function (j) {
      var lv = localOf(rest, j); local[j] = lv;
      // the bind frame: where the skin was bound (the inverse bind matrix, in our world)
      var bm = inv4(ibm[j]), kk = jointK[j] || 1, bf = frameOf(mul4(C4, bm)); bf.P = point(C4, [bm[12] * kk, bm[13] * kk, bm[14] * kk]);
      var jp = jointParent(j);
      bones.push({ id: boneId(j), name: String(g.nodes[j].name || 'joint' + j).slice(0, 48), parent: jp >= 0 ? boneId(jp) : null, origin: lv.o, rotation: lv.r, weights: {}, bindW: bf.R, bindP: bf.P });
    });
    var byId = {}; bones.forEach(function (b) { byId[b.id] = b; });
    // ---- pictures: the base colour picture of each material, or its colour
    var textures = [], imgTex = {}, matTex = {};
    function imageTexture(ii) {
      if (imgTex[ii] !== undefined) return imgTex[ii];
      var im = g.images && g.images[ii], url = null;
      if (im && im.bufferView !== undefined) {
        var bv = g.bufferViews[im.bufferView], b = F.buffers[bv.buffer];
        if (b) url = 'data:' + (im.mimeType || 'image/png') + ';base64,' + toB64(b.subarray(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength));
      } else if (im && /^data:image\//.test(im.uri || '')) url = im.uri;
      if (!url) return (imgTex[ii] = null);
      textures.push({ name: String(im.name || 'picture ' + (ii + 1)), uvW: 1, uvH: 1, url: url, d: null });
      return (imgTex[ii] = textures.length - 1);
    }
    function materialTexture(mi) {
      if (mi === undefined || mi === null) mi = -1;
      if (matTex[mi] !== undefined) return matTex[mi];
      var mat = mi >= 0 && g.materials ? g.materials[mi] : null, pbr = (mat && mat.pbrMetallicRoughness) || {};
      var bt = pbr.baseColorTexture, tx = bt && g.textures && g.textures[bt.index];
      if (tx && tx.source !== undefined) { var t = imageTexture(tx.source); if (t !== null) return (matTex[mi] = { tex: t }); }
      var f = pbr.baseColorFactor || [0.8, 0.8, 0.8, 1];
      // a colour: a one-pixel picture (the converter paints from pictures)
      textures.push({ name: String((mat && mat.name) || 'colour'), uvW: 1, uvH: 1, w: 1, h: 1, d: new Uint8ClampedArray([srgb(f[0]), srgb(f[1]), srgb(f[2]), 255]) });
      return (matTex[mi] = { tex: textures.length - 1, flat: true });
    }
    // ---- meshes: one per node primitive (triangles); a skinned one in the skin's space, a still one placed by its node
    var meshes = [], count = 0, skipped = { molang: 0, scale: 0, prims: 0 };
    g.nodes.forEach(function (nd, ni) {
      if (nd.mesh === undefined || !g.meshes || !g.meshes[nd.mesh]) return;
      var gm = g.meshes[nd.mesh], skin = nd.skin !== undefined ? skins[nd.skin] : null, holder = isJoint[ni] ? ni : jointParent(ni);
      (gm.primitives || []).forEach(function (pr, pi) {
        if ((pr.mode !== undefined && pr.mode !== 4) || pr.attributes.POSITION === undefined) { skipped.prims++; return; }
        var pos = accessor(F, pr.attributes.POSITION), uv = pr.attributes.TEXCOORD_0 !== undefined ? accessor(F, pr.attributes.TEXCOORD_0) : null;
        var idx = pr.indices !== undefined ? accessor(F, pr.indices) : null, mt = materialTexture(pr.material), pre = 'g' + ('00000' + (count++).toString(36)).slice(-5);
        // a skinned mesh: in the skin's bind space (scaled like its joints), else placed by its node
        var kv = skin ? (skin.__k || 1) : 1, place = skin ? [C4[0] * kv, 0, 0, 0, 0, C4[5] * kv, 0, 0, 0, 0, C4[10] * kv, 0, 0, 0, 0, 1] : rest[ni], vertices = {}, uvs = {};
        for (var v = 0; v < pos.n; v++) {
          var k = 'v' + v; vertices[k] = point(place, [pos.a[v * 3], pos.a[v * 3 + 1], pos.a[v * 3 + 2]]);
          uvs[k] = mt.flat || !uv ? [0.5, 0.5] : [uv.a[v * 2], uv.a[v * 2 + 1]];
        }
        // weights: the skin's joints (up to 8 per vertex), or the whole primitive on the joint that holds it
        if (skin) {
          [['JOINTS_0', 'WEIGHTS_0'], ['JOINTS_1', 'WEIGHTS_1']].forEach(function (aw) {
            if (pr.attributes[aw[0]] === undefined || pr.attributes[aw[1]] === undefined) return;
            var J = accessor(F, pr.attributes[aw[0]]), Wt = accessor(F, pr.attributes[aw[1]]);
            for (var v2 = 0; v2 < pos.n; v2++) for (var c = 0; c < 4; c++) {
              var w = Wt.a[v2 * 4 + c]; if (!(w > 0)) continue;
              var jn = skin.joints[J.a[v2 * 4 + c]], b = byId[boneId(jn)]; if (!b) continue;
              b.weights[pre + ':v' + v2] = (b.weights[pre + ':v' + v2] || 0) + w;
            }
          });
        } else if (holder >= 0 && byId[boneId(holder)]) {
          var hb = byId[boneId(holder)]; for (var v3 = 0; v3 < pos.n; v3++) hb.weights[pre + ':v' + v3] = 1;
        }
        var faces = [], nIdx = idx ? idx.n : pos.n;
        for (var f = 0; f + 2 < nIdx; f += 3) {
          var a = idx ? idx.a[f] : f, b2 = idx ? idx.a[f + 1] : f + 1, c2 = idx ? idx.a[f + 2] : f + 2, ks = ['v' + a, 'v' + b2, 'v' + c2], fu = {};
          if (!vertices[ks[0]] || !vertices[ks[1]] || !vertices[ks[2]]) continue;
          ks.forEach(function (kk) { fu[kk] = uvs[kk]; });
          // the half turn mirrors nothing (two axes flip): the winding stays
          faces.push({ v: ks, uv: fu, tex: mt.tex });
        }
        meshes.push({ id: 'gltf' + ni + '_' + pi, name: String(nd.name || gm.name || 'mesh') + (gm.primitives.length > 1 ? '_' + (pi + 1) : ''), pre: pre, origin: [0, 0, 0], rotation: [0, 0, 0], chain: [], vertices: vertices, faces: faces, visible: true, colour: 0xb0b0b0 });
      });
    });
    // a joint that holds a still mesh (no skin) but has no inverse bind matrix: bound where it rests
    bones.forEach(function (b) { var j = Number(b.id.slice(1)); if (!skins.some(function (sk) { return (sk.joints || []).indexOf(j) >= 0 && sk.inverseBindMatrices !== undefined; })) { var f = frameOf(rest[j]); b.bindW = f.R; b.bindP = f.P; } });
    // ---- animations: sampled at 30 frames a second; keys = Blockbench-style local values less the rest (euler unwrapped)
    var anims = (g.animations || []).map(function (an, ai) {
      var samplers = (an.samplers || []).map(function (s) {
        var inp = accessor(F, s.input), out = accessor(F, s.output);
        return { inp: Array.prototype.slice.call(inp.a), out: out.a, k: out.k, interp: s.interpolation || 'LINEAR' };
      });
      var tracks = [], len = 0;
      (an.channels || []).forEach(function (ch) {
        var S = samplers[ch.sampler], tg = ch.target || {};
        if (!S || tg.node === undefined || ['translation', 'rotation', 'scale'].indexOf(tg.path) < 0) return;
        tracks.push({ node: tg.node, path: tg.path, S: S });
        if (S.inp.length) len = Math.max(len, S.inp[S.inp.length - 1]);
      });
      len = Math.max(len, 1 / FPS);
      var chan = {}, prev = {};
      for (var fi = 0; ; fi++) {
        var t = Math.min(len, fi / FPS);
        var over = {};
        tracks.forEach(function (tk) {
          var base = nodeTRS(g.nodes[tk.node]), o = over[tk.node] = over[tk.node] || { t: base.t, r: base.r, s: base.s };
          var v = sample(tk.S, t, tk.path === 'rotation'); if (!v) return;
          if (tk.path === 'translation') o.t = v; else if (tk.path === 'rotation') o.r = v; else o.s = v;
        });
        var Wd = worlds(g, parents, over);
        bones.forEach(function (b) {
          var j = Number(b.id.slice(1)), lv = localOf(Wd, j), r0 = local[j].r, o0 = local[j].o, c = chan[b.id] = chan[b.id] || { rotation: [], position: [] };
          var d = [lv.r[0] - r0[0], lv.r[1] - r0[1], lv.r[2] - r0[2]], pv = prev[b.id];
          // the same turn the shortest way from the last frame (no 360 jumps)
          for (var a3 = 0; a3 < 3; a3++) { var ref = pv ? pv[a3] : 0; while (d[a3] - ref > 180) d[a3] -= 360; while (d[a3] - ref < -180) d[a3] += 360; }
          prev[b.id] = d;
          c.rotation.push([Math.round(t * 1000) / 1000, d, 'linear']);
          c.position.push([Math.round(t * 1000) / 1000, sub(lv.o, o0), 'linear']);
        });
        if (t >= len) break;
      }
      // channels that never move are left out
      Object.keys(chan).forEach(function (id) {
        ['rotation', 'position'].forEach(function (k) { var list = chan[id][k], tol = k === 'rotation' ? 1e-3 : 1e-4; if (list.every(function (e) { return Math.abs(e[1][0]) < tol && Math.abs(e[1][1]) < tol && Math.abs(e[1][2]) < tol; })) chan[id][k] = []; });
        if (!chan[id].rotation.length && !chan[id].position.length) delete chan[id];
      });
      var nm = String(an.name || 'animation ' + (ai + 1)); if (nm.indexOf('|') >= 0) nm = nm.slice(nm.lastIndexOf('|') + 1);
      return { name: nm.slice(0, 64), length: Math.round(len * 1000) / 1000, loop: 'loop', snapping: FPS, chan: chan };
    });
    if (!meshes.length) throw new Error(T('convert.nomesh'));
    return { name: String(name || (g.asset && g.asset.name) || 'modelo').replace(/\.[^.]+$/, ''), bones: bones, meshes: meshes, textures: textures, anims: anims, skipped: skipped, gltf: true };
  }
  /** glTF's own skinning of a vertex at an animation time (QA: proves the bones above give the same mesh) */
  function skinnedAt(buf, animIndex, t) {
    var F = parse(buf), g = F.g, N = g.nodes.length, parents = new Array(N).fill(-1);
    g.nodes.forEach(function (nd) { (nd.children || []).forEach(function (c) { parents[c] = g.nodes.indexOf(nd); }); });
    var an = g.animations && g.animations[animIndex], over = {};
    if (an) (an.channels || []).forEach(function (ch) {
      var s = an.samplers[ch.sampler], inp = accessor(F, s.input), out = accessor(F, s.output), S = { inp: Array.prototype.slice.call(inp.a), out: out.a, k: out.k, interp: s.interpolation || 'LINEAR' };
      var nd = ch.target.node, base = nodeTRS(g.nodes[nd]), o = over[nd] = over[nd] || { t: base.t, r: base.r, s: base.s }, v = sample(S, t, ch.target.path === 'rotation');
      if (ch.target.path === 'translation') o.t = v; else if (ch.target.path === 'rotation') o.r = v; else if (ch.target.path === 'scale') o.s = v;
    });
    var Wd = worlds(g, parents, over), out2 = [];
    // the same order as read(): every triangle primitive of every mesh node (null = not skinned)
    g.nodes.forEach(function (nd) {
      if (nd.mesh === undefined || !g.meshes[nd.mesh]) return;
      var sk = nd.skin !== undefined ? g.skins[nd.skin] : null, mats = sk ? accessor(F, sk.inverseBindMatrices) : null;
      g.meshes[nd.mesh].primitives.forEach(function (pr) {
        if ((pr.mode !== undefined && pr.mode !== 4) || pr.attributes.POSITION === undefined) return;
        if (!sk || pr.attributes.JOINTS_0 === undefined) { out2.push(null); return; }
        var pos = accessor(F, pr.attributes.POSITION), J = accessor(F, pr.attributes.JOINTS_0), Wt = accessor(F, pr.attributes.WEIGHTS_0), list = [];
        for (var v = 0; v < pos.n; v++) {
          var acc = [0, 0, 0], tw = 0;
          for (var c = 0; c < 4; c++) {
            var w = Wt.a[v * 4 + c]; if (!(w > 0)) continue;
            var k = J.a[v * 4 + c], m = mul4(Wd[sk.joints[k]], Array.prototype.slice.call(mats.a, k * 16, k * 16 + 16)), q = point(m, [pos.a[v * 3], pos.a[v * 3 + 1], pos.a[v * 3 + 2]]);
            acc = [acc[0] + q[0] * w, acc[1] + q[1] * w, acc[2] + q[2] * w]; tw += w;
          }
          list.push(tw > 0 ? scl(acc, 1 / tw) : [0, 0, 0]);
        }
        out2.push(list);
      });
    });
    return out2;
  }
  api.read = read; api.parse = parse; api.skinnedAt = skinnedAt; api.SCALE = SCALE;
  return api;
})
