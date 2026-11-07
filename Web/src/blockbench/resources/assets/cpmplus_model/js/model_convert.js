/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Converter modelo 3D em pecas CPM: a rigged 3D model (Blockbench meshes moved by
 * armature bones with vertex weights, and its animations) becomes CPM parts - a group per bone (same pivots, same
 * turns), the mesh flattened into painted HD planes (one per bone, more where the surface bends), and CPM animations
 * made from the model's own. Where the model comes from: another open Blockbench tab (a model opened with File > Open,
 * an OBJ imported into a Generic model...) or a .bbmodel file; a CPM project cannot hold meshes itself.
 * How it looks right (the offline Lord of Moths baker, verified in Blockbench):
 * - the planes are built in the animation's mean pose (the bones rest there, the keys lose the same amount: the
 *   motion is exact), not in the bind pose (rigs often never come back to it);
 * - every plane also paints its neighbours' triangles two rings out, behind its own (a "seam band": no gap opens when
 *   the bones bend apart), planes that overlap along a seam sit a hair apart;
 * - a mesh with two skins (front and back art as separate triangle layers) gives each face of a plane its own picture;
 * - left / right twins (names like _L / _R, Left / Right, ending in l / r, at mirrored places) share one picture: the
 *   right planes are the left ones mirrored in world space, then expressed in the right bone's own frame;
 * - a loop that does not close is eased into its first pose over its last second; keys are reduced by tolerance.
 * Heavy work runs in small chunks (Blockbench never freezes; a progress bar with Cancel); one Ctrl+Z takes it all back
 * (parts, pixels, the texture's size, animations). Loaded after the parts library (it puts the result in the model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  function Lb() { return L.core.modules.model_library; }
  function X() { return L.core.modules.model_texture; }

  // =================================================================== maths (Blockbench turn = Rz * Ry * Rx, degrees)
  var DEG = Math.PI / 180, ID = [1, 0, 0, 0, 1, 0, 0, 0, 1], SX = [-1, 0, 0, 0, 1, 0, 0, 0, 1];
  function rot(a, deg) { var c = Math.cos(deg * DEG), s = Math.sin(deg * DEG); return a === 0 ? [1, 0, 0, 0, c, -s, 0, s, c] : a === 1 ? [c, 0, s, 0, 1, 0, -s, 0, c] : [c, -s, 0, s, c, 0, 0, 0, 1]; }
  function mul(A, B) { var o = new Array(9); for (var r = 0; r < 3; r++) for (var c = 0; c < 3; c++) o[r * 3 + c] = A[r * 3] * B[c] + A[r * 3 + 1] * B[3 + c] + A[r * 3 + 2] * B[6 + c]; return o; }
  function app(A, v) { return [A[0] * v[0] + A[1] * v[1] + A[2] * v[2], A[3] * v[0] + A[4] * v[1] + A[5] * v[2], A[6] * v[0] + A[7] * v[1] + A[8] * v[2]]; }
  function tr(A) { return [A[0], A[3], A[6], A[1], A[4], A[7], A[2], A[5], A[8]]; }
  function euler(r) { r = r || [0, 0, 0]; return mul(rot(2, r[2]), mul(rot(1, r[1]), rot(0, r[0]))); }
  function eulerOf(R) {
    var b = Math.asin(Math.max(-1, Math.min(1, -R[6])));
    if (Math.abs(R[6]) > 0.99999) return [0, b / DEG, Math.atan2(-R[1], R[4]) / DEG];
    return [Math.atan2(R[7], R[8]) / DEG, b / DEG, Math.atan2(R[3], R[0]) / DEG];
  }
  function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
  function add(a, b) { return [a[0] + b[0], a[1] + b[1], a[2] + b[2]]; }
  function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
  function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
  function len(a) { return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); }
  function norm(a) { var l = len(a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; }
  function scl(a, k) { return [a[0] * k, a[1] * k, a[2] * k]; }
  function mir(v) { return [-v[0], v[1], v[2]]; }
  function r3(v) { return Math.round(v * 1000) / 1000; }
  function r2(v) { return Math.round(v * 100) / 100; }
  function vec3(v) { return Array.isArray(v) && v.length >= 3 ? [Number(v[0]) || 0, Number(v[1]) || 0, Number(v[2]) || 0] : [0, 0, 0]; }
  function numOf(v) { var n = typeof v === 'number' ? v : parseFloat(v); return isFinite(n) && (typeof v === 'number' || /^\s*-?[\d.]+(e-?\d+)?\s*$/i.test(String(v))) ? n : NaN; }
  /** symmetric n x n -> eigenvalues and eigenvectors (cyclic Jacobi turns) */
  function eigenN(Mx, n) {
    var a = Mx.slice(), V = [], i, k;
    for (i = 0; i < n * n; i++) V.push(i % (n + 1) === 0 ? 1 : 0);
    for (var sweep = 0; sweep < 60; sweep++) {
      var off = 0; for (var p = 0; p < n; p++) for (var q = p + 1; q < n; q++) off += Math.abs(a[p * n + q]);
      if (off < 1e-13) break;
      for (var p2 = 0; p2 < n; p2++) for (var q2 = p2 + 1; q2 < n; q2++) {
        var apq = a[p2 * n + q2]; if (Math.abs(apq) < 1e-16) continue;
        var th = (a[q2 * n + q2] - a[p2 * n + p2]) / (2 * apq), t = (th < 0 ? -1 : 1) / (Math.abs(th) + Math.sqrt(th * th + 1)), c = 1 / Math.sqrt(t * t + 1), s = t * c;
        for (k = 0; k < n; k++) { var akp = a[k * n + p2], akq = a[k * n + q2]; a[k * n + p2] = c * akp - s * akq; a[k * n + q2] = s * akp + c * akq; }
        for (k = 0; k < n; k++) { var apk = a[p2 * n + k], aqk = a[q2 * n + k]; a[p2 * n + k] = c * apk - s * aqk; a[q2 * n + k] = s * apk + c * aqk; }
        for (k = 0; k < n; k++) { var vkp = V[k * n + p2], vkq = V[k * n + q2]; V[k * n + p2] = c * vkp - s * vkq; V[k * n + q2] = s * vkp + c * vkq; }
      }
    }
    var vals = [], vecs = [];
    for (i = 0; i < n; i++) { vals.push(a[i * n + i]); var col = []; for (k = 0; k < n; k++) col.push(V[k * n + i]); vecs.push(col); }
    return { vals: vals, vecs: vecs };
  }
  /** a node's turn about its pivot as {R, t}: x -> R x + t */
  function pivotTurn(origin, rotation) { var R = euler(rotation), o = vec3(origin); return { R: R, t: sub(o, app(R, o)) }; }
  function compose(A, B) { return { R: mul(A.R, B.R), t: add(app(A.R, B.t), A.t) }; }

  // =================================================================== the 3D model, read the same way from every source
  // src = {name, bones: [{id, name, parent, origin, rotation, weights {'<mesh uuid, 6 chars>:<vertex>': w}}], meshes:
  // [{id, name, pre, origin, rotation, chain (turns of the groups above it, {R, t}), vertices {k: [x, y, z]}, faces [{v
  // [keys], uv {k: [u, v]}, tex (texture index or null)}]}], textures [{name, uvW, uvH, uuid, url | canvas, w, h, d
  // (RGBA, filled by loadTextures)}], anims [{name, length, loop, snapping, chan {bone id: {rotation: [[t, [x, y, z], i]],
  // position: [...]}}}], skipped {molang, scale}}
  function chanOf(animators, isBone, skipped) {
    var chan = {};
    Object.keys(animators || {}).forEach(function (id) {
      if (!isBone(id)) return;
      var an = animators[id], kfs = an && an.keyframes; if (!Array.isArray(kfs)) return;
      var c = { rotation: [], position: [] };
      kfs.forEach(function (k) {
        if (!k) return;
        if (k.channel === 'scale') { skipped.scale++; return; }
        if (!c[k.channel]) return;
        var dp = k.data_points && k.data_points[0]; if (!dp) return;
        var v = [numOf(dp.x), numOf(dp.y), numOf(dp.z)];
        if (v.some(function (x) { return !isFinite(x); })) { skipped.molang++; return; }
        c[k.channel].push([Number(k.time) || 0, v, String(k.interpolation || 'linear')]);
      });
      c.rotation.sort(function (a, b) { return a[0] - b[0]; }); c.position.sort(function (a, b) { return a[0] - b[0]; });
      if (c.rotation.length || c.position.length) chan[id] = c;
    });
    return chan;
  }
  function animOf(a, isBone, skipped) {
    return { name: String(a.name || 'animation').slice(0, 64), length: Math.max(0.05, Number(a.length) || 1), loop: a.loop === 'once' || a.loop === 'hold' ? a.loop : 'loop', snapping: Number(a.snapping) || 20, chan: chanOf(a.animators, isBone, skipped) };
  }
  /** a mesh's marker colour (the colour its faces show without a texture) as rgb */
  function markerRGB(i) {
    var mc = G && G.markerColors, c = mc && mc[Number(i) || 0], s = c && (c.standard || c.pastel || c);
    s = typeof s === 'string' ? s.replace('#', '') : '';
    return /^[0-9a-f]{6}$/i.test(s) ? parseInt(s, 16) : 0xb0b0b0;
  }
  /** a .bbmodel (its JSON) -> src. The bone tree may sit in the bones' own child lists, in the outliner, or both */
  function fromJSON(j, name) {
    if (!j || typeof j !== 'object' || !Array.isArray(j.elements)) throw new Error(T('convert.bad'));
    var els = j.elements, res = j.resolution || {}, skipped = { molang: 0, scale: 0 };
    var textures = (Array.isArray(j.textures) ? j.textures : []).map(function (t, i) {
      return { name: String(t.name || 'texture ' + (i + 1)), uuid: t.uuid, uvW: Number(t.uv_width) || Number(res.width) || Number(t.width) || 16, uvH: Number(t.uv_height) || Number(res.height) || Number(t.height) || 16, url: typeof t.source === 'string' ? t.source : null, d: null };
    });
    function texIndex(ref) {
      if (typeof ref === 'number') return ref >= 0 && ref < textures.length ? ref : null;
      if (typeof ref === 'string') for (var i = 0; i < textures.length; i++) if (textures[i].uuid === ref) return i;
      return null;
    }
    var bones = [], byId = {}, groupBy = {};
    (Array.isArray(j.groups) ? j.groups : []).forEach(function (g) { if (g && g.uuid) groupBy[g.uuid] = g; });
    els.forEach(function (e) {
      if (!e || e.type !== 'armature_bone' || !e.uuid) return;
      var b = { id: e.uuid, name: String(e.name || 'bone'), parent: null, origin: vec3(e.origin), rotation: vec3(e.rotation), weights: e.vertex_weights && typeof e.vertex_weights === 'object' ? e.vertex_weights : {} };
      bones.push(b); byId[b.id] = b;
    });
    els.forEach(function (e) { if (e && e.type === 'armature_bone' && Array.isArray(e.children)) e.children.forEach(function (c) { if (byId[c] && !byId[c].parent && c !== e.uuid) byId[c].parent = e.uuid; }); });
    // the outliner: bones under bones, meshes under groups (their turns)
    var chainOf = {};
    (function walk(list, up, chain) {
      (list || []).forEach(function (n) {
        var id = typeof n === 'string' ? n : n && n.uuid, bone = id && byId[id] ? id : null;
        if (bone && up && !byId[bone].parent && bone !== up) byId[bone].parent = up;
        if (typeof n === 'string') { chainOf[n] = chain; return; }
        if (!n || typeof n !== 'object') return;
        var c = chain;
        // a group (not a bone, not an armature) turns what is inside it
        var gd = !bone && (n.name !== undefined || groupBy[n.uuid]) ? (groupBy[n.uuid] || n) : null;
        var isArm = els.some(function (e) { return e && e.uuid === n.uuid && e.type === 'armature'; });
        if (gd && !isArm && Array.isArray(gd.rotation) && (gd.rotation[0] || gd.rotation[1] || gd.rotation[2])) c = chain.concat([pivotTurn(gd.origin, gd.rotation)]);
        if (Array.isArray(n.children)) walk(n.children, bone || up, c);
      });
    })(j.outliner, null, []);
    var meshes = els.filter(function (e) { return e && e.type === 'mesh' && e.vertices && e.faces; }).map(function (e) {
      var faces = [];
      Object.keys(e.faces).forEach(function (fk) { var f = e.faces[fk]; if (f && Array.isArray(f.vertices) && f.vertices.length >= 3) faces.push({ v: f.vertices.slice(), uv: f.uv || {}, tex: texIndex(f.texture) }); });
      return { id: String(e.uuid), name: String(e.name || 'mesh'), pre: String(e.uuid || '').slice(0, 6), origin: vec3(e.origin), rotation: vec3(e.rotation), chain: chainOf[e.uuid] || [], vertices: e.vertices, faces: faces, visible: e.visibility !== false, colour: markerRGB(e.color) };
    });
    var anims = (Array.isArray(j.animations) ? j.animations : []).map(function (a) { return animOf(a, function (id) { return !!byId[id]; }, skipped); });
    return { name: String(name || j.name || 'modelo'), bones: bones, meshes: meshes, textures: textures, anims: anims, skipped: skipped };
  }
  /** another open project (a Blockbench tab) -> src, read from its live parts without switching to it */
  function fromProject(p) {
    var els = (p && p.elements) || [], skipped = { molang: 0, scale: 0 };
    var textures = (p.textures || []).map(function (t, i) { return { name: String(t.name || 'texture ' + (i + 1)), uuid: t.uuid, uvW: Number(t.uv_width) || Number(p.texture_width) || t.width || 16, uvH: Number(t.uv_height) || Number(p.texture_height) || t.height || 16, canvas: t.canvas || null, d: null }; });
    function texIndex(ref) {
      if (ref && typeof ref === 'object' && ref.uuid) ref = ref.uuid;
      if (typeof ref === 'string') for (var i = 0; i < textures.length; i++) if (textures[i].uuid === ref) return i;
      return null;
    }
    var bones = [], byId = {};
    els.forEach(function (e) {
      if (!e || e.type !== 'armature_bone') return;
      var b = { id: e.uuid, name: String(e.name || 'bone'), parent: e.parent && e.parent !== 'root' && e.parent.type === 'armature_bone' ? e.parent.uuid : null, origin: vec3(e.origin), rotation: vec3(e.rotation), weights: e.vertex_weights || {} };
      bones.push(b); byId[b.id] = b;
    });
    var meshes = els.filter(function (e) { return e && e.type === 'mesh' && e.vertices && e.faces; }).map(function (e) {
      var faces = [], chain = [];
      Object.keys(e.faces).forEach(function (fk) { var f = e.faces[fk]; if (f && Array.isArray(f.vertices) && f.vertices.length >= 3) faces.push({ v: f.vertices.slice(), uv: f.uv || {}, tex: texIndex(f.texture) }); });
      for (var q = e.parent; q && q !== 'root'; q = q.parent) if (q.type === 'group' && Array.isArray(q.rotation) && (q.rotation[0] || q.rotation[1] || q.rotation[2])) chain.unshift(pivotTurn(q.origin, q.rotation));
      return { id: String(e.uuid), name: String(e.name || 'mesh'), pre: String(e.uuid || '').slice(0, 6), origin: vec3(e.origin), rotation: vec3(e.rotation), chain: chain, vertices: e.vertices, faces: faces, visible: e.visibility !== false, selected: !!e.selected, colour: markerRGB(e.color) };
    });
    var anims = (p.animations || []).map(function (a) { return animOf(a, function (id) { return !!byId[id]; }, skipped); });
    return { name: String(p.name || 'modelo'), bones: bones, meshes: meshes, textures: textures, anims: anims, skipped: skipped };
  }
  /** the textures the chosen meshes use (indices) */
  function usedTextures(src, meshIds) {
    var used = {};
    src.meshes.forEach(function (m) { if (meshIds && meshIds.indexOf(m.id) < 0) return; m.faces.forEach(function (f) { if (f.tex !== null && f.tex !== undefined) used[f.tex] = 1; }); });
    return Object.keys(used).map(Number);
  }
  /** fills the pixels of the textures listed (Promise): a canvas (open tab) or a data URL (file) */
  function loadTextures(src, list) {
    return Promise.all(list.map(function (i) {
      var t = src.textures[i]; if (!t || t.d) return null;
      if (t.canvas && t.canvas.width) {
        try { var img = t.canvas.getContext('2d').getImageData(0, 0, t.canvas.width, t.canvas.height); t.w = img.width; t.h = img.height; t.d = img.data; } catch (e) { }
        return null;
      }
      if (!t.url || !/^data:image\/(png|jpe?g|gif|webp);base64,/.test(t.url)) return null;
      return new Promise(function (ok) {
        var im = new Image();
        im.onload = function () {
          try {
            var cv = document.createElement('canvas'); cv.width = im.naturalWidth; cv.height = im.naturalHeight;
            var g = cv.getContext('2d'); g.drawImage(im, 0, 0);
            var img2 = g.getImageData(0, 0, cv.width, cv.height); t.w = img2.width; t.h = img2.height; t.d = img2.data;
          } catch (e) { }
          ok(null);
        };
        im.onerror = function () { ok(null); };
        im.src = t.url;
      });
    }));
  }

  // =================================================================== reading the rig: sides, poses, skinning
  var SIDE_TOK = [['left', 'right'], ['Left', 'Right'], ['LEFT', 'RIGHT']];
  /** names of the other side's twin that a bone name could have ('Wing_L' -> 'Wing_R', 'LeftArm' -> 'RightArm', 'basel' -> 'baser') */
  function twinNames(n) {
    var out = [];
    SIDE_TOK.forEach(function (p) {
      [0, 1].forEach(function (s) { var a = p[s], b = p[1 - s], i = n.indexOf(a); while (i >= 0) { out.push({ name: n.slice(0, i) + b + n.slice(i + a.length), side: s ? 'r' : 'l' }); i = n.indexOf(a, i + 1); } });
    });
    // a single l / r letter: alone between separators, at the start before one, or at the very end
    var re = /(^|[_.\-\s:])([lLrR])(?=$|[_.\-\s:\d])/g, m;
    while ((m = re.exec(n))) { var at = m.index + m[1].length, ch = m[2]; out.push({ name: n.slice(0, at) + swapLR(ch) + n.slice(at + 1), side: /[lL]/.test(ch) ? 'l' : 'r' }); }
    var last = n.charAt(n.length - 1);
    if (/[lLrR]/.test(last) && n.length > 1) out.push({ name: n.slice(0, -1) + swapLR(last), side: /[lL]/.test(last) ? 'l' : 'r' });
    return out;
  }
  function swapLR(c) { return c === 'l' ? 'r' : c === 'r' ? 'l' : c === 'L' ? 'R' : 'L'; }
  /**
   * The rig, ready for any reference pose: bone order, parents, children, the bind frames, the channels of the
   * animations, the left / right pairs (named twins at mirrored places).
   */
  function rigOf(src) {
    var bones = src.bones, by = {}, byName = {}, byBase = {}, kids = {};
    // names also without a trailing number ('b_LeftArm_09' twins 'b_RightArm_06')
    function base(n) { return String(n).replace(/[_.\-]?\d+$/, ''); }
    bones.forEach(function (b) { by[b.id] = b; byName[b.name] = b; var k = base(b.name); if (k !== b.name) (byBase[k] = byBase[k] || []).push(b); });
    bones.forEach(function (b) { if (b.parent && !by[b.parent]) b.parent = null; if (b.parent) (kids[b.parent] = kids[b.parent] || []).push(b.id); });
    // no loops in the tree (a broken file): a bone whose chain comes back to it loses its parent
    bones.forEach(function (b) { var seen = {}, p = b.parent; while (p) { if (seen[p] || p === b.id) { b.parent = null; break; } seen[p] = 1; p = by[p] && by[p].parent; } });
    kids = {}; bones.forEach(function (b) { if (b.parent) (kids[b.parent] = kids[b.parent] || []).push(b.id); });
    var rig = { src: src, bones: bones, by: by, kids: kids, roots: bones.filter(function (b) { return !b.parent; }).map(function (b) { return b.id; }) };
    rig.bind = poseAt(rig, function () { return null; });
    // a glTF skin is bound where its inverse bind matrices say (not always its rest pose)
    bones.forEach(function (b) { if (b.bindW && b.bindP) { rig.bind.W[b.id] = b.bindW; rig.bind.P[b.id] = b.bindP; } });
    rig.bindT = {}; bones.forEach(function (b) { rig.bindT[b.id] = tr(rig.bind.W[b.id]); });
    // the vertex weights by vertex: '<mesh>:<vertex>' -> [[bone, w]]
    var wOf = {};
    bones.forEach(function (b) { Object.keys(b.weights || {}).forEach(function (k) { var w = Number(b.weights[k]); if (w > 0) (wOf[k] = wOf[k] || []).push([b.id, w]); }); });
    rig.weightOf = wOf;
    // left / right twins: named alike, at mirrored places in the bind pose
    var size = 0; bones.forEach(function (b) { var p = rig.bind.P[b.id]; size = Math.max(size, Math.abs(p[0]), Math.abs(p[1]), Math.abs(p[2])); });
    var tol = Math.max(0.05, size * 0.03);
    rig.twin = {}; rig.side = {};
    bones.forEach(function (b) {
      if (rig.twin[b.id]) return;
      var cands = twinNames(b.name);
      for (var i = 0; i < cands.length; i++) {
        var o = byName[cands[i].name] || (byBase[base(cands[i].name)] || []).filter(function (x) { return x !== b && !rig.twin[x.id]; })[0]; if (!o || o === b || rig.twin[o.id]) continue;
        var pa = rig.bind.P[b.id], pb = rig.bind.P[o.id];
        if (Math.abs(pa[0] + pb[0]) > tol || Math.abs(pa[1] - pb[1]) > tol || Math.abs(pa[2] - pb[2]) > tol) continue;
        if (Math.abs(pa[0]) < tol * 0.5 && Math.abs(len(sub(pa, pb))) < tol * 0.5 && b.parent === o.parent) continue;
        rig.twin[b.id] = o.id; rig.twin[o.id] = b.id;
        rig.side[b.id] = cands[i].side; rig.side[o.id] = cands[i].side === 'l' ? 'r' : 'l';
        break;
      }
    });
    return rig;
  }
  /** world frames {W (turn), P (head)} of every bone with off(id) = {r, p} (or null) added to its rest */
  function poseAt(rig, off) {
    var W = {}, P = {};
    function solve(id) {
      if (W[id]) return;
      var b = rig.by[id], q = off(id), R = euler(q ? add(b.rotation, q.r) : b.rotation), o = q ? add(b.origin, q.p) : b.origin;
      if (b.parent) { solve(b.parent); W[id] = mul(W[b.parent], R); P[id] = add(P[b.parent], app(W[b.parent], o)); } else { W[id] = R; P[id] = o; }
    }
    rig.bones.forEach(function (b) { solve(b.id); });
    return { W: W, P: P };
  }
  /** a channel's value at time t (linear, step, catmull-rom as Blockbench plays them; held before the first / after the last key) */
  function valAt(list, t) {
    if (!list || !list.length) return [0, 0, 0];
    var n = list.length;
    if (t <= list[0][0]) return list[0][1];
    if (t >= list[n - 1][0]) return list[n - 1][1];
    var lo = 0, hi = n - 1;
    while (hi - lo > 1) { var m = (lo + hi) >> 1; if (list[m][0] <= t) lo = m; else hi = m; }
    var a = list[lo], b = list[hi], f = (t - a[0]) / ((b[0] - a[0]) || 1);
    if (a[2] === 'step') return a[1];
    if (a[2] === 'catmullrom' || b[2] === 'catmullrom') {
      var p0 = list[Math.max(0, lo - 1)][1], p3 = list[Math.min(n - 1, hi + 1)][1], f2 = f * f, f3 = f2 * f;
      return [0, 1, 2].map(function (i) { var P0 = p0[i], P1 = a[1][i], P2 = b[1][i], P3 = p3[i]; return 0.5 * ((2 * P1) + (-P0 + P2) * f + (2 * P0 - 5 * P1 + 4 * P2 - P3) * f2 + (-P0 + 3 * P1 - 3 * P2 + P3) * f3); });
    }
    return [a[1][0] + (b[1][0] - a[1][0]) * f, a[1][1] + (b[1][1] - a[1][1]) * f, a[1][2] + (b[1][2] - a[1][2]) * f];
  }
  /** the mean of every bone's keys over an animation (its reference pose); none = the bind pose */
  function meanOf(rig, A) {
    var mean = {}, NS = 64;
    rig.bones.forEach(function (b) {
      var c = A && A.chan[b.id]; if (!c) return;
      var r = [0, 0, 0], p = [0, 0, 0];
      for (var s = 0; s < NS; s++) { var t = A.length * s / NS, a = valAt(c.rotation, t), q = valAt(c.position, t); for (var i = 0; i < 3; i++) { r[i] += a[i] / NS; p[i] += q[i] / NS; } }
      if (r.some(function (x) { return Math.abs(x) > 1e-4; }) || p.some(function (x) { return Math.abs(x) > 1e-5; })) mean[b.id] = { r: r, p: p };
    });
    return mean;
  }
  /** a vertex (bind position v, weights ws) in pose X: the weighted mean of where each bone takes it */
  function skin(rig, v, ws, X) {
    var s0 = 0, s1 = 0, s2 = 0, tw = 0;
    for (var i = 0; i < ws.length; i++) {
      var id = ws[i][0], w = ws[i][1], d = sub(v, rig.bind.P[id]), q = add(X.P[id], app(X.W[id], app(rig.bindT[id], d)));
      s0 += q[0] * w; s1 += q[1] * w; s2 += q[2] * w; tw += w;
    }
    return tw > 0 ? [s0 / tw, s1 / tw, s2 / tw] : v;
  }

  // =================================================================== the surface: triangles in the reference pose, who owns them
  /** quad corners in the order that goes round it (Blockbench keeps a quad's vertices in any order) */
  function quadOrder(pts) {
    var best = null, orders = [[0, 1, 2, 3], [0, 1, 3, 2], [0, 2, 1, 3]];
    orders.forEach(function (o) {
      var a = pts[o[0]], b = pts[o[1]], c = pts[o[2]], d = pts[o[3]], n = add(add(cross(sub(b, a), sub(c, a)), cross(sub(c, a), sub(d, a))), [0, 0, 0]), area = len(n);
      // a crossing order has its two halves facing apart
      var h1 = cross(sub(b, a), sub(c, a)), h2 = cross(sub(c, a), sub(d, a));
      if (dot(h1, h2) < 0) area = -1;
      if (!best || area > best.area) best = { o: o, area: area };
    });
    return best.o;
  }
  /**
   * Level A (depends on the source, the meshes, mirroring and the reference animation): the reference pose and every
   * triangle skinned into it, with the bone that moves it most. o = {meshes: [ids] | null, mirror, ref (anim index or -1)}
   */
  function analyse(src, o) {
    o = o || {};
    var rig = rigOf(src), A = o.ref >= 0 ? src.anims[o.ref] : null, mean = meanOf(rig, A);
    var X = poseAt(rig, function (id) { return mean[id] || null; });
    var st = { src: src, rig: rig, mean: mean, ref: X, refAnim: A, faces: [], verts: {}, statics: {}, owners: {}, mirror: !!o.mirror };
    // a static mesh (no weights): its own still 'bone' at the model's origin
    function staticBone(m) {
      var id = '__static_' + m.id;
      if (!st.statics[id]) st.statics[id] = { id: id, name: m.name, mesh: m.id };
      return id;
    }
    var counts = {};
    var mlist = src.meshes.filter(function (m) { return !o.meshes || o.meshes.indexOf(m.id) >= 0; });
    // first pass: the owner of every face (who moves it most), to know which twins really mirror
    var recs = [];
    mlist.forEach(function (m) {
      var mr = euler(m.rotation), mo = m.origin, chain = m.chain || [];
      m.faces.forEach(function (f) {
        var vk = f.v, sum = {}, tot = 0, ws = [], bind = [];
        for (var i = 0; i < vk.length; i++) {
          var k = vk[i], v = m.vertices[k]; if (!v) return;
          var w = rig.weightOf[m.pre + ':' + k] || [];
          ws.push(w); for (var j = 0; j < w.length; j++) { sum[w[j][0]] = (sum[w[j][0]] || 0) + w[j][1]; tot += w[j][1]; }
          var p = add(app(mr, vec3(v)), mo);
          for (var c = chain.length - 1; c >= 0; c--) p = add(app(chain[c].R, p), chain[c].t);
          bind.push(p);
        }
        var owner = null, bestW = 0, second = null, secondW = 0;
        Object.keys(sum).forEach(function (id) { if (sum[id] > bestW) { second = owner; secondW = bestW; bestW = sum[id]; owner = id; } else if (sum[id] > secondW) { secondW = sum[id]; second = id; } });
        if (!owner) owner = staticBone(m);
        counts[owner] = (counts[owner] || 0) + 1;
        recs.push({ m: m, f: f, ws: ws, bind: bind, owner: owner, second: second });
      });
    });
    // a pair mirrors only when both sides own about as much (an odd mesh keeps its own triangles on both sides)
    st.mirrored = {};
    if (o.mirror) Object.keys(rig.twin).forEach(function (id) {
      if (rig.side[id] !== 'r') return;
      var l = rig.twin[id], a = counts[l] || 0, b = counts[id] || 0;
      if ((a === 0 && b === 0) || (a > 0 && b <= a * 2 && b >= a * 0.5)) st.mirrored[id] = l;
    });
    var nTri = 0;
    recs.forEach(function (r) {
      if (st.mirrored[r.owner]) return;
      var vk = r.f.v, isStatic = !!st.statics[r.owner];
      var pts = r.bind.map(function (p, i) { return isStatic || !r.ws[i].length ? p : skin(rig, p, r.ws[i], X); });
      var uvs = vk.map(function (k) { var u = r.f.uv && r.f.uv[k]; return u ? [Number(u[0]) || 0, Number(u[1]) || 0] : [0, 0]; });
      var keys = vk.map(function (k) { return r.m.pre + ':' + k; });
      var tris;
      if (vk.length === 3) tris = [[0, 1, 2]];
      else if (vk.length === 4) { var q = quadOrder(pts); tris = [[q[0], q[1], q[2]], [q[0], q[2], q[3]]]; }
      else { tris = []; for (var i = 1; i + 1 < vk.length; i++) tris.push([0, i, i + 1]); }
      var rec = { keys: keys, owner: r.owner, second: r.second, bind: r.bind, ws: r.ws, tris: tris.map(function (t) { return { p: [pts[t[0]], pts[t[1]], pts[t[2]]], uv: [uvs[t[0]], uvs[t[1]], uvs[t[2]]], tex: r.f.tex }; }) };
      nTri += rec.tris.length;
      st.faces.push(rec);
    });
    st.triangles = nTri;
    faceErrors(st);
    // the model's box in the reference pose (every chosen mesh, both sides)
    var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    recs.forEach(function (r) {
      var isStatic = !!st.statics[r.owner];
      r.bind.forEach(function (p, i) { var q = isStatic || !r.ws[i].length ? p : skin(rig, p, r.ws[i], X); for (var a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], q[a]); hi[a] = Math.max(hi[a], q[a]); } });
    });
    st.box = lo[0] === Infinity ? null : { lo: lo, hi: hi };
    // the adjacency of faces (shared vertices), for the seam bands
    var facesOfV = {};
    st.faces.forEach(function (f, i) { f.keys.forEach(function (k) { (facesOfV[k] = facesOfV[k] || []).push(i); }); });
    st.facesOfV = facesOfV;
    return st;
  }
  /** the frame a bone's plane lives in: the bone (reference pose) or a static mesh's world */
  function frameOf(st, id) { return st.statics[id] ? { W: ID, P: [0, 0, 0] } : { W: st.ref.W[id], P: st.ref.P[id] }; }

  // =================================================================== planes: one per bone, split where the surface bends
  /** the plane a set of triangles is flattened onto, in its bone's frame: normal n (towards the model's back), axes, offset, deviation */
  function planeBasis(st, owner, tris) {
    var fr = frameOf(st, owner), Wt = tr(fr.W), head = fr.P;
    var loc = tris.map(function (t) { return t.p.map(function (q) { return app(Wt, sub(q, head)); }); });
    var Ar = 0, cen = [0, 0, 0], cs = [];
    loc.forEach(function (p) { var a = len(cross(sub(p[1], p[0]), sub(p[2], p[0]))) / 2 + 1e-9, m = scl(add(add(p[0], p[1]), p[2]), 1 / 3); cs.push([a, m, p]); Ar += a; cen = add(cen, scl(m, a)); });
    cen = scl(cen, 1 / Ar);
    var C = [0, 0, 0, 0, 0, 0, 0, 0, 0];
    cs.forEach(function (x) { x[2].concat([x[1]]).forEach(function (q) { var dd = sub(q, cen); for (var r = 0; r < 3; r++) for (var k = 0; k < 3; k++) C[r * 3 + k] += x[0] * dd[r] * dd[k]; }); });
    var E = eigenN(C, 3), order = [0, 1, 2].sort(function (x, y) { return E.vals[x] - E.vals[y]; });
    var n = norm(E.vecs[order[0]]); if (app(fr.W, n)[2] < 0) n = scl(n, -1);
    var ay = sub([0, 1, 0], scl(n, n[1])); if (len(ay) < 1e-3) ay = sub([1, 0, 0], scl(n, n[0]));
    ay = norm(ay); var ax = norm(cross(ay, n));
    var off = dot(cen, n), dev = 0;
    loc.forEach(function (p) { p.forEach(function (q) { dev = Math.max(dev, Math.abs(dot(q, n) - off)); }); });
    return { Wt: Wt, head: head, n: n, ax: ax, ay: ay, off: off, dev: dev, area: Ar, cen: cen, spread: E.vecs[order[2]] };
  }
  /** two halves of a cluster: along its longest side, or by which way its triangles face (whichever flattens better) */
  function splitOf(st, c) {
    var tris = c.faces.map(function (fi) { return { fi: fi, t: st.faces[fi].tris }; });
    if (tris.length < 2) return null;
    var b = c.basis, fr = frameOf(st, c.owner), Wt = tr(fr.W), head = fr.P;
    function centre(f) { var s = [0, 0, 0], n = 0; f.t.forEach(function (t) { t.p.forEach(function (q) { s = add(s, app(Wt, sub(q, head))); n++; }); }); return scl(s, 1 / n); }
    function normalOf(f) { var s = [0, 0, 0]; f.t.forEach(function (t) { var p = t.p.map(function (q) { return app(Wt, sub(q, head)); }); s = add(s, cross(sub(p[1], p[0]), sub(p[2], p[0]))); }); return s; }
    var cands = [];
    // by place: the median along the direction it spreads most
    var ds = tris.map(function (f) { return dot(centre(f), b.spread); }), sorted = ds.slice().sort(function (x, y) { return x - y; }), med = sorted[sorted.length >> 1];
    var a1 = [], b1 = []; tris.forEach(function (f, i) { (ds[i] < med ? a1 : b1).push(f.fi); });
    if (a1.length && b1.length) cands.push([a1, b1]);
    // by facing: two groups of normals (seeded by the two most different ones)
    var ns = tris.map(function (f) { return norm(normalOf(f)); }), s0 = 0, s1 = 0, worst = 2;
    for (var i = 0; i < ns.length; i++) { var d0 = dot(ns[i], ns[0]); if (d0 < worst) { worst = d0; s1 = i; } }
    var c0 = ns[s0], c1 = ns[s1];
    if (worst < 0.95) {
      var ga, gb;
      for (var it = 0; it < 6; it++) {
        ga = []; gb = []; var sa = [0, 0, 0], sb = [0, 0, 0];
        ns.forEach(function (nv, k) { if (dot(nv, c0) >= dot(nv, c1)) { ga.push(tris[k].fi); sa = add(sa, nv); } else { gb.push(tris[k].fi); sb = add(sb, nv); } });
        if (!ga.length || !gb.length) break;
        c0 = norm(sa); c1 = norm(sb);
      }
      if (ga && gb && ga.length && gb.length) cands.push([ga, gb]);
    }
    var best = null;
    cands.forEach(function (cd) {
      var halves = cd.map(function (fs) { var tt = []; fs.forEach(function (fi) { st.faces[fi].tris.forEach(function (t) { tt.push(t); }); }); return { faces: fs, basis: planeBasis(st, c.owner, tt) }; });
      var e = Math.max(halves[0].basis.dev, halves[1].basis.dev);
      if (!best || e < best.e) best = { e: e, halves: halves };
    });
    return best && best.e < c.basis.dev * 0.95 ? best.halves : null;
  }
  var PIECE = 12;
  /**
   * the faces that stray from their bone's plane while it moves (over tol), as pieces: the patches they make (faces of
   * the same bone sharing a vertex), cut into pieces of at most size faces along their longest side -> {face: key}
   */
  function piecesOf(st, tol, size) {
    var cand = {}, out = {};
    st.faces.forEach(function (f, i) { if (f.err > tol && !st.statics[f.owner]) cand[i] = 1; });
    var parent = {};
    function find(x) { while (parent[x] !== undefined && parent[x] !== x) { parent[x] = parent[parent[x]] === undefined ? parent[x] : parent[parent[x]]; x = parent[x]; } return x; }
    Object.keys(cand).forEach(function (i) { parent[i] = i; });
    Object.keys(cand).forEach(function (i) {
      var f = st.faces[i];
      f.keys.forEach(function (k) { (st.facesOfV[k] || []).forEach(function (g) { if (cand[g] && st.faces[g].owner === f.owner) { var a = find(String(i)), b = find(String(g)); if (a !== b) parent[a] = b; } }); });
    });
    var comps = {};
    Object.keys(cand).forEach(function (i) { var r = find(i); (comps[r] = comps[r] || []).push(Number(i)); });
    var n = 0;
    Object.keys(comps).forEach(function (r) {
      (function cut(list) {
        if (list.length <= size) { var key = st.faces[list[0]].owner + '|' + (n++); list.forEach(function (i) { out[i] = key; }); return; }
        // the middle of each face (reference pose), cut at the median of the longest side
        var cs = list.map(function (i) { var t = st.faces[i].tris[0].p; return [(t[0][0] + t[1][0] + t[2][0]) / 3, (t[0][1] + t[1][1] + t[2][1]) / 3, (t[0][2] + t[1][2] + t[2][2]) / 3]; });
        var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
        cs.forEach(function (c) { for (var a = 0; a < 3; a++) { lo[a] = Math.min(lo[a], c[a]); hi[a] = Math.max(hi[a], c[a]); } });
        var ax = 0; for (var a2 = 1; a2 < 3; a2++) if (hi[a2] - lo[a2] > hi[ax] - lo[ax]) ax = a2;
        var idx = list.map(function (i, k) { return k; }).sort(function (p, q) { return cs[p][ax] - cs[q][ax]; }), half = idx.length >> 1;
        cut(idx.slice(0, half).map(function (k) { return list[k]; })); cut(idx.slice(half).map(function (k) { return list[k]; }));
      })(comps[r]);
    });
    return out;
  }  /**
   * Level B: the clusters (planes) - one per bone that owns triangles, split while the flattening error (source units)
   * is over tol and there are fewer than budget planes (each plane of a mirrored bone counts twice). -> {clusters}
   */
  function clustersOf(st, o) {
    o = o || {};
    var tol = o.tol > 0 ? o.tol : Infinity, budget = o.budget > 0 ? o.budget : Infinity, animTol = o.animTol > 0 ? o.animTol : Infinity, byOwner = {}, order = [];
    // faces that stray from their bone's plane while it moves (pulled by a second bone) become a piece of their own, its
    // motion fitted to the real mesh (a few faces stay with the bone)
    var pieceOf = piecesOf(st, animTol, o.pieceSize || PIECE);
    st.faces.forEach(function (f, i) { var k = pieceOf[i] ? f.owner + '#' + pieceOf[i] : f.owner; if (!byOwner[k]) { byOwner[k] = []; order.push(k); } byOwner[k].push(i); });
    var mirroredL = {}; Object.keys(st.mirrored).forEach(function (r) { mirroredL[st.mirrored[r]] = 1; });
    function weight(owner) { return mirroredL[owner] ? 2 : 1; }
    var list = order.map(function (k) {
      var owner = k.split('#')[0], piece = k.indexOf('#') > 0 ? k.slice(k.indexOf('#') + 1) : null, tt = [];
      byOwner[k].forEach(function (fi) { st.faces[fi].tris.forEach(function (t) { tt.push(t); }); });
      return { owner: owner, piece: piece, faces: byOwner[k], basis: planeBasis(st, owner, tt) };
    });
    var planes = 0; list.forEach(function (c) { planes += weight(c.owner); });
    var guard = 0;
    while (planes < budget && guard++ < 4000) {
      var worst = null;
      list.forEach(function (c) { if (!c.done && c.basis.dev > tol && (!worst || c.basis.dev > worst.basis.dev)) worst = c; });
      if (!worst) break;
      if (planes + weight(worst.owner) > budget) break;
      var h = splitOf(st, worst);
      if (!h) { worst.done = true; continue; }
      var at = list.indexOf(worst);
      list.splice(at, 1, { owner: worst.owner, piece: worst.piece, faces: h[0].faces, basis: h[0].basis }, { owner: worst.owner, piece: worst.piece, faces: h[1].faces, basis: h[1].basis });
      planes += weight(worst.owner);
    }
    list.forEach(function (c, i) { c.idx = i; c.faces.forEach(function (fi) { st.faces[fi].cluster = i; }); });
    // the seam band: every plane also paints its neighbours' triangles up to BAND rings out (they share a vertex), behind its own
    var BAND = o.band === undefined ? 2 : o.band;
    list.forEach(function (c) {
      c.cover = [];
      if (!(BAND > 0)) return;
      var seen = {}, front = c.faces.slice();
      c.faces.forEach(function (fi) { seen[fi] = 1; });
      for (var ring = 0; ring < BAND; ring++) {
        var next = [];
        front.forEach(function (fi) { st.faces[fi].keys.forEach(function (k) { (st.facesOfV[k] || []).forEach(function (g) { if (!seen[g]) { seen[g] = 1; next.push(g); } }); }); });
        next.forEach(function (g) { c.cover.push(g); }); front = next;
      }
    });
    // planes that overlap along a seam sit a hair apart: different layers for neighbours (greedy colouring)
    var nb = list.map(function () { return {}; });
    list.forEach(function (c) { c.cover.forEach(function (g) { var o2 = st.faces[g].cluster; if (o2 !== undefined && o2 !== c.idx) { nb[c.idx][o2] = 1; nb[o2][c.idx] = 1; } }); });
    var LEVELS = [0, 1, -1, 2, -2, 3, -3];
    list.forEach(function (c) {
      var used = {}; Object.keys(nb[c.idx]).forEach(function (k) { var x = list[k]; if (x.layer !== undefined) used[x.layer] = 1; });
      c.layer = 0; for (var i = 0; i < LEVELS.length; i++) if (!used[LEVELS[i]]) { c.layer = LEVELS[i]; break; }
    });
    var maxDev = 0; list.forEach(function (c) { maxDev = Math.max(maxDev, c.basis.dev); });
    // the pieces: {key, owner, faces} (each fitted on its own; a mirrored bone's piece also on the other side)
    var pieces = [], pk = {};
    list.forEach(function (c) { if (!c.piece) return; if (!pk[c.piece]) { pk[c.piece] = { key: c.piece, owner: c.owner, faces: [] }; pieces.push(pk[c.piece]); } c.faces.forEach(function (fi) { pk[c.piece].faces.push(fi); }); });
    return { clusters: list, planes: planes, maxDev: maxDev, pieces: pieces };
  }

  // =================================================================== sizes, then the pictures (rasterised, 4 x 4 samples a pixel)
  var SS = 4, DILATE = 2, LAYER = 0.045;
  /** a cluster's plane laid out for scale sc and d pixels per unit: its triangles in plane coordinates, the picture size */
  function layoutOf(st, c, sc, d) {
    var b = c.basis, tris = [];
    c.faces.forEach(function (fi) { st.faces[fi].tris.forEach(function (t) { tris.push({ t: t, cover: false }); }); });
    c.cover.forEach(function (fi) { st.faces[fi].tris.forEach(function (t) { tris.push({ t: t, cover: true }); }); });
    var lo = [1e9, 1e9], hi = [-1e9, -1e9], back = 0, front = 0;
    var loc = tris.map(function (x) {
      var p = x.t.p.map(function (q) { return app(b.Wt, sub(q, b.head)); });
      var facing = dot(cross(sub(p[1], p[0]), sub(p[2], p[0])), b.n);
      if (!x.cover) { if (facing >= 0) back += facing; else front -= facing; }
      // the seam band reaches past the plane's own triangles: that is where it fills the gap when the bones bend apart
      p.forEach(function (q) { var u = dot(q, b.ax), v = dot(q, b.ay); lo = [Math.min(lo[0], u), Math.min(lo[1], v)]; hi = [Math.max(hi[0], u), Math.max(hi[1], v)]; });
      return { p: p, uv: x.t.uv, tex: x.t.tex, cover: x.cover, back: facing >= 0 };
    });
    var pad = (DILATE + 0.5) / (d * sc);
    lo = [lo[0] - pad, lo[1] - pad]; hi = [hi[0] + pad, hi[1] + pad];
    var W = Math.max(1, Math.round((hi[0] - lo[0]) * sc * d)), H = Math.max(1, Math.round((hi[1] - lo[1]) * sc * d));
    var wM = W / (sc * d), hM = H / (sc * d);
    // two skins when both facings carry real area (each at least a quarter of the other)
    var two = back > 0 && front > 0 && Math.min(back, front) * 4 >= Math.max(back, front);
    return { loc: loc, lo: lo, wM: wM, hM: hM, W: W, H: H, two: two, sc: sc, d: d };
  }
  function texel(Tx, u, v, out) {
    var x = Math.floor(u * Tx.w / Tx.uvW), y = Math.floor(v * Tx.h / Tx.uvH);
    if (x < 0) x = 0; else if (x >= Tx.w) x = Tx.w - 1;
    if (y < 0) y = 0; else if (y >= Tx.h) y = Tx.h - 1;
    var o = (y * Tx.w + x) * 4; out[0] = Tx.d[o]; out[1] = Tx.d[o + 1]; out[2] = Tx.d[o + 2]; out[3] = Tx.d[o + 3];
  }
  function b64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return typeof btoa === 'function' ? btoa(s) : Buffer.from(s, 'binary').toString('base64'); }
  function now() { return typeof performance !== 'undefined' && performance.now ? performance.now() : Date.now(); }
  /**
   * one skin of a plane, painted in small steps (step(until) -> true when done): the nearest triangle to its viewer wins
   * (dir 1 = seen from the back, -1 = from the front); 4 x 4 samples a pixel (fewer on a huge picture)
   */
  function skinTask(st, lay, list, dir, b) {
    var Wpx = lay.W, Hpx = lay.H, ss = Wpx * Hpx * SS * SS > 6e6 ? (Wpx * Hpx * 4 > 6e6 ? 1 : 2) : SS;
    var SW = Wpx * ss, SH = Hpx * ss, col = new Uint8Array(SW * SH * 3), hit = new Uint8Array(SW * SH), zb = new Float32Array(SW * SH), cc = [0, 0, 0, 0];
    var texs = st.src.textures, fallback = null, i = 0, row = 0, phase = 0, px = null, out = null;
    for (var ti = 0; ti < texs.length && !fallback; ti++) if (texs[ti] && texs[ti].d) fallback = texs[ti];
    function tri(t) {
      var Tx = (typeof t.tex === 'number' && texs[t.tex] && texs[t.tex].d) ? texs[t.tex] : fallback; if (!Tx) return;
      var q = t.p.map(function (pp) { return [(dot(pp, b.ax) - lay.lo[0]) / lay.wM * SW, (1 - (dot(pp, b.ay) - lay.lo[1]) / lay.hM) * SH, dir * dot(pp, b.n) - (t.cover ? 1e4 : 0)]; });
      var x0 = Math.max(0, Math.floor(Math.min(q[0][0], q[1][0], q[2][0]))), x1 = Math.min(SW - 1, Math.ceil(Math.max(q[0][0], q[1][0], q[2][0])));
      var y0 = Math.max(0, Math.floor(Math.min(q[0][1], q[1][1], q[2][1]))), y1 = Math.min(SH - 1, Math.ceil(Math.max(q[0][1], q[1][1], q[2][1])));
      var area = (q[1][0] - q[0][0]) * (q[2][1] - q[0][1]) - (q[2][0] - q[0][0]) * (q[1][1] - q[0][1]); if (Math.abs(area) < 1e-12) return;
      for (var y = y0; y <= y1; y++) for (var x = x0; x <= x1; x++) {
        var cx = x + 0.5, cy = y + 0.5;
        var w0 = ((q[1][0] - cx) * (q[2][1] - cy) - (q[2][0] - cx) * (q[1][1] - cy)) / area, w1 = ((q[2][0] - cx) * (q[0][1] - cy) - (q[0][0] - cx) * (q[2][1] - cy)) / area, w2 = 1 - w0 - w1;
        if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue;
        var z = w0 * q[0][2] + w1 * q[1][2] + w2 * q[2][2], k = y * SW + x; if (hit[k] && z < zb[k]) continue;
        texel(Tx, w0 * t.uv[0][0] + w1 * t.uv[1][0] + w2 * t.uv[2][0], w0 * t.uv[0][1] + w1 * t.uv[1][1] + w2 * t.uv[2][1], cc);
        if (cc[3] < 128) continue;
        zb[k] = z; hit[k] = 1; col[k * 3] = cc[0]; col[k * 3 + 1] = cc[1]; col[k * 3 + 2] = cc[2];
      }
    }
    // down to the picture: the mean of the covered samples, solid where half or more are covered
    function down(y2) {
      for (var x2 = 0; x2 < Wpx; x2++) {
        var r = 0, g = 0, bl = 0, k = 0;
        for (var sy = 0; sy < ss; sy++) for (var sx = 0; sx < ss; sx++) { var j = (y2 * ss + sy) * SW + x2 * ss + sx; if (hit[j]) { r += col[j * 3]; g += col[j * 3 + 1]; bl += col[j * 3 + 2]; k++; } }
        if (k * 2 >= ss * ss) { var o = (y2 * Wpx + x2) * 4; px[o] = r / k; px[o + 1] = g / k; px[o + 2] = bl / k; px[o + 3] = 255; }
      }
    }
    // a little past the edges (neighbouring pieces meet without a hairline): DILATE rings of the neighbours' colour
    function dilate() {
      for (var pass = 0; pass < DILATE; pass++) {
        var was = new Uint8ClampedArray(px);
        for (var y3 = 0; y3 < Hpx; y3++) for (var x3 = 0; x3 < Wpx; x3++) {
          var o3 = (y3 * Wpx + x3) * 4; if (was[o3 + 3]) continue;
          var rr = 0, gg = 0, bb = 0, kk = 0;
          for (var dd = 0; dd < 4; dd++) {
            var X2 = x3 + (dd === 0 ? 1 : dd === 1 ? -1 : 0), Y2 = y3 + (dd === 2 ? 1 : dd === 3 ? -1 : 0);
            if (X2 < 0 || Y2 < 0 || X2 >= Wpx || Y2 >= Hpx) continue;
            var p4 = (Y2 * Wpx + X2) * 4; if (was[p4 + 3]) { rr += was[p4]; gg += was[p4 + 1]; bb += was[p4 + 2]; kk++; }
          }
          if (kk) { px[o3] = rr / kk; px[o3 + 1] = gg / kk; px[o3 + 2] = bb / kk; px[o3 + 3] = 255; }
        }
      }
    }
    return {
      step: function (until) {
        for (;;) {
          if (phase === 0) {
            for (var n = 0; n < 48 && i < list.length; n++) tri(list[i++]);
            if (i >= list.length) { phase = 1; px = new Uint8ClampedArray(Wpx * Hpx * 4); }
          } else if (phase === 1) {
            for (var n2 = 0; n2 < 8 && row < Hpx; n2++) down(row++);
            if (row >= Hpx) { phase = 2; col = hit = zb = null; }
          } else {
            dilate();
            var solid = 0; for (var i5 = 3; i5 < px.length; i5 += 4) if (px[i5]) solid++;
            out = { solid: solid, pic: { w: Wpx, h: Hpx, px: b64(new Uint8Array(px.buffer)) } };
            return true;
          }
          if (now() >= until) return false;
        }
      },
      result: function () { return out; }
    };
  }
  /** one cluster's plane, painted in steps -> result {centre (bone frame), R, w, h (source units), south, north, same} */
  function rasterTask(st, c, lay) {
    var b = c.basis, tasks = [skinTask(st, lay, lay.loc.filter(function (t) { return t.back; }), 1, b), skinTask(st, lay, lay.loc.filter(function (t) { return !t.back; }), -1, b)], k = 0, out = null;
    return {
      step: function (until) {
        while (k < 2) { if (!tasks[k].step(until)) return false; k++; }
        var south = tasks[0].result(), north = tasks[1].result();
        // one skin only (or a side the bone barely has): both faces show the fuller picture
        var pick = function (x, y) { return x.solid * 4 < y.solid ? y : x; };
        var hi = [lay.lo[0] + lay.wM, lay.lo[1] + lay.hM], off = b.off + (c.layer || 0) * LAYER / lay.sc;
        var cu = (lay.lo[0] + hi[0]) / 2, cv = (lay.lo[1] + hi[1]) / 2, centre = add(add(scl(b.ax, cu), scl(b.ay, cv)), scl(b.n, off));
        var B = [b.ax[0], b.ay[0], b.n[0], b.ax[1], b.ay[1], b.n[1], b.ax[2], b.ay[2], b.n[2]];
        var sp = pick(south, north).pic, np = pick(north, south).pic;
        out = { centre: centre, R: B, w: lay.wM, h: lay.hM, south: sp, north: np, same: sp === np };
        return true;
      },
      result: function () { return out; }
    };
  }
  function rasterOf(st, c, lay) { var t = rasterTask(st, c, lay); t.step(Infinity); return t.result(); }

  // =================================================================== fitted pieces: where one rigid plane per bone cannot follow the mesh
  /** the turn R (and centres) that best takes points A onto points B (Horn's quaternion fit) */
  function fitRigid(Ap, Bp) {
    var n = Ap.length, ca = [0, 0, 0], cb = [0, 0, 0], k;
    for (k = 0; k < n; k++) { ca = add(ca, Ap[k]); cb = add(cb, Bp[k]); }
    ca = scl(ca, 1 / n); cb = scl(cb, 1 / n);
    var S3 = [0, 0, 0, 0, 0, 0, 0, 0, 0];
    for (k = 0; k < n; k++) { var a = sub(Ap[k], ca), b = sub(Bp[k], cb); for (var i = 0; i < 3; i++) for (var j = 0; j < 3; j++) S3[i * 3 + j] += a[i] * b[j]; }
    var xx = S3[0], xy = S3[1], xz = S3[2], yx = S3[3], yy = S3[4], yz = S3[5], zx = S3[6], zy = S3[7], zz = S3[8];
    var N = [xx + yy + zz, yz - zy, zx - xz, xy - yx, yz - zy, xx - yy - zz, xy + yx, zx + xz, zx - xz, xy + yx, -xx + yy - zz, yz + zy, xy - yx, zx + xz, yz + zy, -xx - yy + zz];
    var E = eigenN(N, 4), bi = 0; for (var q = 1; q < 4; q++) if (E.vals[q] > E.vals[bi]) bi = q;
    var w = E.vecs[bi][0], x = E.vecs[bi][1], y = E.vecs[bi][2], z = E.vecs[bi][3];
    return { R: [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y), 2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x), 2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)], ca: ca, cb: cb };
  }
  /** the channels of an animation as the part will play them (loops closed): bone id -> {rotation, position} key lists */
  function easedChannels(A, closeLoop) {
    var out = {}, dummy = { seams: 0 };
    Object.keys(A.chan).forEach(function (id) { var c = A.chan[id]; out[id] = { rotation: channelKeys(c.rotation, A, 'rotation', closeLoop, dummy), position: channelKeys(c.position, A, 'position', closeLoop, dummy) }; });
    return out;
  }
  function poseOfAnim(rig, chan, t) { return poseAt(rig, function (id) { var c = chan[id]; return c ? { r: valAt(c.rotation, t), p: valAt(c.position, t) } : null; }); }
  /**
   * how far each face strays from its bone's plane during the reference animation (source units, the worst of its
   * vertices over 16 moments): a vertex pulled by other bones moves away from the rigid plane of the face's bone
   */
  function faceErrors(st) {
    var rig = st.rig, A = st.refAnim, cache = {};
    st.faces.forEach(function (f) { f.err = 0; });
    if (!A) return;
    var poses = []; for (var s = 0; s < 16; s++) poses.push(poseOfAnim(rig, A.chan, A.length * s / 16));
    st.faces.forEach(function (f) {
      var o = f.owner; if (st.statics[o]) return;
      var W0t = tr(st.ref.W[o]), P0 = st.ref.P[o], worst = 0;
      for (var i = 0; i < f.keys.length; i++) {
        var ws = f.ws[i], ck = f.keys[i] + '|' + o, e = cache[ck];
        if (e === undefined) {
          e = 0;
          if (ws.length && !(ws.length === 1 && ws[0][0] === o)) {
            var loc = app(W0t, sub(skin(rig, f.bind[i], ws, st.ref), P0));
            for (var s2 = 0; s2 < poses.length; s2++) { var X = poses[s2], p = add(X.P[o], app(X.W[o], loc)); e = Math.max(e, len(sub(p, skin(rig, f.bind[i], ws, X)))); }
          }
          cache[ck] = e;
        }
        if (e > worst) worst = e;
      }
      f.err = worst;
    });
  }
  /**
   * a piece's motion in one animation: its vertices moved by the real skinning, fitted as one rigid piece frame by
   * frame, relative to its bone -> {d (its pivot in the bone's frame), rot: [[t, euler]], pos: [[t, xyz]]}. right = the
   * mirrored twin (its own bones, the vertices mirrored)
   */
  function fitPiece(st, piece, A, chan, right) {
    var rig = st.rig, tw = function (id) { return right ? (rig.twin[id] || id) : id; }, seen = {}, verts = [];
    piece.faces.forEach(function (fi) {
      var f = st.faces[fi];
      f.keys.forEach(function (k, i) { if (seen[k] || !f.ws[i].length) return; seen[k] = 1; verts.push({ bind: right ? mir(f.bind[i]) : f.bind[i], ws: f.ws[i].map(function (q) { return [tw(q[0]), q[1]]; }) }); });
    });
    var step = Math.max(1, Math.floor(verts.length / 160)); verts = verts.filter(function (_, i) { return i % step === 0; });
    var owner = tw(piece.owner), ref = verts.map(function (v) { return skin(rig, v.bind, v.ws, st.ref); });
    var cref = [0, 0, 0]; ref.forEach(function (p) { cref = add(cref, p); }); cref = scl(cref, 1 / Math.max(1, ref.length));
    var W0 = st.ref.W[owner], d = app(tr(W0), sub(cref, st.ref.P[owner])), out = { d: d, rot: [], pos: [] };
    if (!A || verts.length < 3) return out;
    var fps = Math.max(1, Math.min(30, A.snapping || 20)), prev = null;
    for (var f2 = 0; f2 / fps <= A.length + 1e-6; f2++) {
      var t = Math.min(A.length, f2 / fps), X = poseOfAnim(rig, chan, t), cur = verts.map(function (v) { return skin(rig, v.bind, v.ws, X); }), F = fitRigid(ref, cur);
      var Wt = tr(X.W[owner]), Rr = mul(Wt, mul(F.R, W0)), p = sub(app(Wt, sub(F.cb, X.P[owner])), d), e = eulerOf(Rr);
      if (prev) e = e.map(function (x, i) { while (x - prev[i] > 180) x -= 360; while (x - prev[i] < -180) x += 360; return x; });
      prev = e;
      out.rot.push([Math.round(t * 1000) / 1000, e]); out.pos.push([Math.round(t * 1000) / 1000, p]);
    }
    return out;
  }

  // =================================================================== the CPM part (library item) and its animations
  /** fewer keys: drop every key the straight line between its neighbours already gives (within tol) */
  function reduceKeys(keys, tol) {
    if (keys.length <= 2) return keys;
    var keep = keys.map(function () { return false; }); keep[0] = keep[keys.length - 1] = true;
    var stack = [[0, keys.length - 1]];
    while (stack.length) {
      var s = stack.pop(), a = s[0], b = s[1], worst = -1, at = -1;
      for (var i = a + 1; i < b; i++) {
        var f = (keys[i][0] - keys[a][0]) / ((keys[b][0] - keys[a][0]) || 1), e = 0;
        for (var k = 0; k < 3; k++) e = Math.max(e, Math.abs(keys[i][1][k] - (keys[a][1][k] + (keys[b][1][k] - keys[a][1][k]) * f)));
        if (e > worst) { worst = e; at = i; }
      }
      if (worst > tol) { keep[at] = true; stack.push([a, at]); stack.push([at, b]); }
    }
    return keys.filter(function (_, i) { return keep[i]; });
  }
  /**
   * a channel as linear keys, the loop closed: keys other than linear are sampled at the animation's frame rate; a loop
   * that does not come back to its first pose eases (smoothstep) into it over its last second
   */
  function channelKeys(list, A, ch, closeLoop, stats) {
    if (!list || !list.length) return [];
    var fps = Math.max(1, Math.min(60, A.snapping || 20)), out;
    var linear = list.every(function (k) { return k[2] === 'linear' || k[2] === 'bezier'; });
    if (linear) out = list.map(function (k) { return [k[0], k[1].slice()]; });
    else { out = []; for (var f = 0; f / fps <= A.length + 1e-6; f++) { var t = Math.min(A.length, f / fps); out.push([Math.round(t * 1000) / 1000, valAt(list, t)]); } }
    if (!closeLoop || A.loop !== 'loop') return out;
    var v0 = valAt(list, 0), d = sub(v0, valAt(list, A.length));
    if (Math.max(Math.abs(d[0]), Math.abs(d[1]), Math.abs(d[2])) < (ch === 'rotation' ? 0.05 : 0.002)) return out;
    stats.seams++;
    var BLEND = Math.min(1, A.length / 4), from = A.length - BLEND;
    out = out.filter(function (k) { return k[0] < from - 1e-6; });
    for (var g = 0; ; g++) {
      var t2 = from + g / fps; if (t2 > A.length + 1e-6) break; t2 = Math.min(t2, A.length);
      var s = (t2 - from) / BLEND, w = s * s * (3 - 2 * s);
      out.push([Math.round(t2 * 1000) / 1000, add(valAt(list, t2), scl(d, w))]);
    }
    return out;
  }
  /** a bone name as a part name (letters, numbers, '_' and '.') */
  function partName(s) { return String(s || 'bone').replace(/[^A-Za-z0-9_.\-]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 48) || 'bone'; }
  /**
   * the item: groups = bones (their rest + the reference pose), a painted plane per cluster (the right twins get the
   * left planes mirrored), the kept animations. o = {sc, d, anchorOff, root, anchor, name, anims: [{i, type}], closeLoop, planes
   * (rasterOf results by cluster index, or null = no pictures: a quick shape for the 3D preview)}
   */
  function assemble(st, B, o) {
    var rig = st.rig, sc = o.sc, anchorOff = o.anchorOff || [0, 0, 0], planes = o.planes;
    // the bones rest in the reference pose (the edit view looks like the animation, not like the bind pose) only when that
    // animation always plays ('global'): its keys lose the same amount, the others keep adding to the real rest - exact in
    // the game for any mix of animations. Otherwise they keep their own rest (every animation exact, alone or mixed).
    var refA = st.refAnim, refGlobal = !!(refA && (o.anims || []).some(function (x) { return st.src.anims[x.i] === refA && x.type === 'global'; }));
    var mean = refGlobal ? st.mean : {};
    function S(v) { return [r3(v[0] * sc), r3(v[1] * sc), r3(v[2] * sc)]; }
    // the unrotated pivots a Blockbench group needs, for the reference pose
    var U = {};
    function u(id) { if (U[id]) return U[id]; var b = rig.by[id], m = mean[id], q = m ? add(b.origin, m.p) : b.origin; return (U[id] = b.parent ? add(u(b.parent), q) : q); }
    var byOwner = {}; B.clusters.forEach(function (c) { (byOwner[c.owner] = byOwner[c.owner] || []).push(c); });
    var srcOf = function (id) { return st.mirrored[id] || id; };
    // only bones that carry a plane (or have one further down) become groups
    var needed = {};
    rig.bones.forEach(function (b) { if (byOwner[srcOf(b.id)]) for (var p = b.id; p; p = rig.by[p].parent) needed[p] = 1; });
    var stats = { cubes: 0, groups: 0, seams: 0, keys: 0, srcKeys: 0 }, name = o.keyBase || 'cv';
    function planeCube(c, ownerId, mirror) {
      var pl = planes ? planes[c.idx] : null, cen, Rm, w, h;
      if (pl) { cen = pl.centre; Rm = pl.R; w = pl.w; h = pl.h; }
      else { var lay = o.layouts[c.idx], b = c.basis, hi = [lay.lo[0] + lay.wM, lay.lo[1] + lay.hM]; cen = add(add(scl(b.ax, (lay.lo[0] + hi[0]) / 2), scl(b.ay, (lay.lo[1] + hi[1]) / 2)), scl(b.n, b.off)); Rm = [b.ax[0], b.ay[0], b.n[0], b.ax[1], b.ay[1], b.n[1], b.ax[2], b.ay[2], b.n[2]]; w = lay.wM; h = lay.hM; }
      var bone = ownerId;
      if (mirror) {
        // the other side: the left plane mirrored in the world (bind pose, where the mesh is symmetric), then put in this
        // bone's own frame (its turns need not mirror the first side's: only the shape does)
        var Lid = c.owner, WLb = rig.bind.W[Lid], PLb = rig.bind.P[Lid], WRb = rig.bind.W[bone], PRb = rig.bind.P[bone];
        // the left plane in its own frame: reference pose coordinates are bone-local, the same in the bind pose
        var cw = mir(add(PLb, app(WLb, cen))), Bw = mul(SX, mul(mul(WLb, Rm), SX));
        cen = app(tr(WRb), sub(cw, PRb)); Rm = mul(tr(WRb), Bw);
      }
      var base = st.statics[bone] ? [0, 0, 0] : u(bone), cc = add(add(S(base), anchorOff), S(cen)), hw = w * sc / 2, hh = h * sc / 2;
      var cube = { c: 1, n: partName((st.statics[bone] ? st.statics[bone].name : rig.by[bone].name) + (byOwner[c.owner].length > 1 ? '_' + (byOwner[c.owner].indexOf(c) + 1) : '')),
        f: [r3(cc[0] - hw), r3(cc[1] - hh), r3(cc[2] - 0.004)], t: [r3(cc[0] + hw), r3(cc[1] + hh), r3(cc[2] + 0.004)], o: cc.map(r3), r: eulerOf(Rm).map(r3) };
      if (pl) {
        var key = name + c.idx;
        var face = function (nm, flip) { var pic = nm === 'south' ? pl.south : pl.north; return { face: nm, rel: [0, 0, r3(w * sc), r3(h * sc)], w: pic.w, h: pic.h, px: pic.px, flip: [mirror ? !flip : flip, false], rot: 0, hd: true, key: pl.same ? key : key + nm.charAt(0) }; };
        cube.tex = { box: false, off: [0, 0], faces: [face('south', false), face('north', true)], fs: { east: -1, west: -1, up: -1, down: -1 } };
      } else cube.col = o.previewColour || 0x4fc3f7;
      stats.cubes++;
      return cube;
    }
    function group(id) {
      var b = rig.by[id], m = mean[id], gn = partName(b.name);
      // a bone named like a body part (head, body...) would read as one: it keeps a suffix
      if (ROOTS.indexOf(gn.toLowerCase()) >= 0) gn += '_bone';
      var g = { g: 1, n: gn, o: add(S(u(id)), anchorOff).map(r3), kids: [], aid: id };
      var r = m ? add(b.rotation, m.r) : b.rotation; if (r.some(function (x) { return Math.abs(x) > 1e-4; })) g.r = r.map(r3);
      var mirror = !!st.mirrored[id];
      (byOwner[srcOf(id)] || []).forEach(function (c) {
        if (!c.piece) { g.kids.push(planeCube(c, id, mirror)); return; }
        // a fitted piece: its own group inside the bone's (pivot at its middle), moved by its fitted keys
        var side = mirror ? 'r' : 'l', pid = 'pc:' + c.piece + ':' + side, pg = pieceGroups[pid];
        if (!pg) {
          var fit = o.fits && o.fits[c.piece + ':' + side], d = fit ? fit.d : c.basis.cen;
          pieceFits[pid] = fit; pieceBone[pid] = id;
          pg = pieceGroups[pid] = { g: 1, n: gn + '_m' + Object.keys(pieceBone).filter(function (k) { return pieceBone[k] === id; }).length, o: add(S(add(u(id), d)), anchorOff).map(r3), kids: [], aid: pid };
          g.kids.push(pg); stats.groups++;
        }
        pg.kids.push(planeCube(c, id, mirror));
      });
      (rig.kids[id] || []).forEach(function (k) { if (needed[k]) g.kids.push(group(k)); });
      stats.groups++;
      return g;
    }
    var pieceGroups = {}, pieceFits = {}, pieceBone = {};
    var kids = rig.roots.filter(function (id) { return needed[id]; }).map(group);
    // static meshes: their planes sit right in the top group
    Object.keys(st.statics).forEach(function (id) { (byOwner[id] || []).forEach(function (c) { kids.push(planeCube(c, id, false)); }); });
    var top = { g: 1, n: partName(o.name || 'modelo'), o: anchorOff.map(r3), kids: kids };
    stats.pieces = Object.keys(pieceGroups).length;
    // ---- the animations: the same motion; the reference pose (the bones' new rest, see above) taken out of its own keys
    var anims = [];
    (o.anims || []).forEach(function (x) {
      var A = st.src.anims[x.i]; if (!A) return;
      var shifted = refGlobal && A === refA, tracks = [];
      rig.bones.forEach(function (bn) {
        var id = bn.id; if (!needed[id]) return;
        var c = A.chan[id], m = shifted ? mean[id] : null, keys = [];
        ['rotation', 'position'].forEach(function (ch) {
          var off = m ? (ch === 'rotation' ? m.r : m.p) : null, list = c ? channelKeys(c[ch], A, ch, o.closeLoop !== false, stats) : [];
          if (c) stats.srcKeys += c[ch].length;
          var ks = list.map(function (k) { var v = off ? sub(k[1], off) : k[1]; return [k[0], ch === 'position' ? v.map(function (z) { return r3(z * sc); }) : v.map(r2)]; });
          // a channel without keys holds the rest: a bone rested in the reference pose needs a key giving that back
          if (!ks.length && off && off.some(function (z) { return Math.abs(z) > 1e-4; })) ks = [[0, ch === 'position' ? off.map(function (z) { return r3(-z * sc); }) : off.map(function (z) { return r2(-z); })]];
          ks = reduceKeys(ks, ch === 'rotation' ? (o.rotTol || 0.25) : (o.posTol || 0.015));
          ks.forEach(function (k) { keys.push({ ch: ch, t: k[0], i: 'linear', v: k[1] }); });
        });
        if (keys.some(function (k) { return k.v.some(function (z) { return Math.abs(z) > 0.02; }); })) { tracks.push({ aid: id, keys: keys }); stats.keys += keys.length; }
      });
      // the fitted pieces: their keys for this animation (made by the job, relative to their bone)
      Object.keys(pieceGroups).forEach(function (pid) {
        var fit = pieceFits[pid], f = fit && fit.anims && fit.anims[x.i]; if (!f) return;
        var rk = reduceKeys(f.rot.map(function (k) { return [k[0], k[1].map(r2)]; }), o.rotTol || 0.25), pk = reduceKeys(f.pos.map(function (k) { return [k[0], k[1].map(function (z) { return r3(z * sc); })]; }), o.posTol || 0.015);
        var keys = rk.map(function (k) { return { ch: 'rotation', t: k[0], i: 'linear', v: k[1] }; }).concat(pk.map(function (k) { return { ch: 'position', t: k[0], i: 'linear', v: k[1] }; }));
        if (keys.some(function (k) { return k.v.some(function (z) { return Math.abs(z) > 0.02; }); })) { tracks.push({ aid: pid, keys: keys }); stats.keys += keys.length; }
      });
      if (tracks.length) anims.push({ name: A.name, type: x.type || 'global', additive: true, loop: A.loop, length: A.length, snapping: Math.max(1, Math.min(60, Math.round(A.snapping) || 20)), tracks: tracks });
    });
    var item = { v: 1, id: o.id || 'cv' + Date.now().toString(36), user: true, name: String(o.name || 'modelo').slice(0, 64), cat: /wing|asa|fly/i.test(String(o.name || '')) ? 'body' : 'other',
      sets: [{ root: o.root || '', anchor: (o.anchor || [0, 0, 0]).slice(), parts: [top] }], created: Date.now() };
    if (planes) item.hd = o.d;
    if (anims.length) item.anims = anims;
    item.stats = stats;
    return item;
  }

  // =================================================================== the whole conversion, in small steps
  /**
   * a job: o = {meshes, mirror, ref, tol, budget, band, sc, d, anchorOff, root, anchor, name, anims, closeLoop}; cache =
   * {st, B} already worked out for the same choices (the window has them). J.step(until) -> true when J.item is ready;
   * J.progress (0..1), J.done / J.total (planes painted)
   */
  function job(src, o, cache) {
    var J = { item: null, planes: [], progress: 0, done: 0, total: 0 }, st = cache && cache.st, B = cache && cache.B, lays = null, i = 0, task = null;
    var fits = {}, fitList = null, fi = 0, eased = {};
    J.step = function (until) {
      for (;;) {
        if (!st) st = analyse(src, o);
        else if (!B) B = clustersOf(st, o);
        else if (!lays) {
          lays = B.clusters.map(function (c) { return layoutOf(st, c, o.sc, o.d); });
          // the fitted pieces: each side, each kept animation
          var mirroredL = {}; Object.keys(st.mirrored).forEach(function (r) { mirroredL[st.mirrored[r]] = 1; });
          fitList = [];
          (B.pieces || []).forEach(function (pc) {
            (mirroredL[pc.owner] ? ['l', 'r'] : ['l']).forEach(function (side) {
              fitList.push({ pc: pc, side: side, ai: -1 });
              (o.anims || []).forEach(function (x) { fitList.push({ pc: pc, side: side, ai: x.i }); });
            });
          });
          J.total = lays.length + fitList.length;
        } else if (i < lays.length) {
          if (!task) task = rasterTask(st, B.clusters[i], lays[i]);
          if (task.step(until)) { J.planes[i] = task.result(); task = null; i++; J.done = i; }
          J.progress = 0.05 + 0.9 * ((i + fi) / Math.max(1, J.total));
        } else if (fi < fitList.length) {
          var it = fitList[fi++], key = it.pc.key + ':' + it.side, A = it.ai >= 0 ? src.anims[it.ai] : null;
          if (A && !eased[it.ai]) eased[it.ai] = easedChannels(A, o.closeLoop !== false);
          var f = fitPiece(st, it.pc, A, A ? eased[it.ai] : null, it.side === 'r');
          if (!fits[key]) fits[key] = { d: f.d, anims: {} };
          if (A) fits[key].anims[it.ai] = { rot: f.rot, pos: f.pos };
          J.done = i + fi; J.progress = 0.05 + 0.9 * ((i + fi) / Math.max(1, J.total));
        } else { J.item = assemble(st, B, Object.assign({}, o, { planes: J.planes, layouts: lays, fits: fits })); J.progress = 1; return true; }
        if (now() >= until) return false;
      }
    };
    return J;
  }
  /** runs a job to the end at once (tests, small models) */
  function runJob(J) { while (!J.step(Infinity)) { /* runs to the end */ } return J.item; }

  // =================================================================== where it goes on the player
  // lang: lib.root.{head|body|right_arm|left_arm|right_leg|left_leg}
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  var PIVOT = { head: [0, 24, 0], body: [0, 24, 0], right_arm: [5, 22, 0], left_arm: [-5, 22, 0], right_leg: [1.9, 12, 0], left_leg: [-1.9, 12, 0] };
  // a model that was not made around a player hangs from: the upper back, the top of the head, the middle of a limb
  var MOUNT = { head: [0, 8, 0], body: [0, -3, 2.3], right_arm: [1, -4, 0], left_arm: [-1, -4, 0], right_leg: [0.1, -6, 0], left_leg: [-0.1, -6, 0] };
  var HINTS = [[/wing|asa|tail|cauda|rabo|cape|capa|back|costa|jetpack|mochila|backpack/i, 'body'], [/ear|orelha|horn|chifre|hat\b|chapeu|halo|crown|coroa|hair|cabel|head|cabeca|antenn|antena|mask|mascara/i, 'head']];
  /** the bones that carry triangles (and the ones above them) */
  function usedBones(st) {
    var used = {}, rig = st.rig;
    st.faces.forEach(function (f) { for (var p = f.owner; p && rig.by[p]; p = rig.by[p].parent) used[p] = 1; });
    Object.keys(st.mirrored).forEach(function (r) { if (used[st.mirrored[r]]) for (var p = r; p && rig.by[p]; p = rig.by[p].parent) used[p] = 1; });
    return used;
  }
  /** {built (made around a player: it stays where it is), maxDim, attach (where it hangs from: the top bones' heads)} */
  function placementOf(st) {
    var b = st.box; if (!b) return { built: true, maxDim: 1, attach: [0, 0, 0] };
    var maxDim = Math.max(b.hi[0] - b.lo[0], b.hi[1] - b.lo[1], b.hi[2] - b.lo[2]) || 1;
    var near = b.hi[0] > -12 && b.lo[0] < 12 && b.hi[1] > 0 && b.lo[1] < 40 && b.hi[2] > -12 && b.lo[2] < 12;
    // a glTF model knows nothing of the player: never 'made around it'
    var built = near && maxDim <= 96 && b.lo[1] > -16 && !st.src.gltf;
    var used = usedBones(st), roots = st.rig.roots.filter(function (id) { return used[id]; }), a = [0, 0, 0];
    if (roots.length) { roots.forEach(function (id) { a = add(a, st.ref.P[id]); }); a = scl(a, 1 / roots.length); }
    else a = [(b.lo[0] + b.hi[0]) / 2, (b.lo[1] + b.hi[1]) / 2, (b.lo[2] + b.hi[2]) / 2];
    return { built: built, maxDim: maxDim, attach: a };
  }
  function guessRoot(st, words, built) {
    for (var i = 0; i < HINTS.length; i++) if (HINTS[i][0].test(words)) return HINTS[i][1];
    var b = st.box; if (!built || !b) return 'body';
    var c = [(b.lo[0] + b.hi[0]) / 2, (b.lo[1] + b.hi[1]) / 2, (b.lo[2] + b.hi[2]) / 2];
    if (c[1] >= 24 && Math.abs(c[0]) < 8 && Math.abs(c[2]) < 8) return 'head';
    if (c[1] < 12) return c[0] >= 0 ? 'right_leg' : 'left_leg';
    if (Math.abs(c[0]) > 6) return c[0] > 0 ? 'right_arm' : 'left_arm';
    return 'body';
  }
  function anchorOffOf(pl, root, sc, extra) {
    var e = extra || [0, 0, 0];
    // made around a player: it stays where it is (a new size grows / shrinks it around where it hangs from)
    if (pl.built) { var p = PIVOT[root] || [0, 0, 0], a = pl.attach || [0, 0, 0]; return [e[0] - p[0] + a[0] * (1 - sc), e[1] - p[1] + a[1] * (1 - sc), e[2] - p[2] + a[2] * (1 - sc)]; }
    var m = MOUNT[root] || [0, 0, 0];
    return [m[0] - sc * pl.attach[0] + e[0], m[1] - sc * pl.attach[1] + e[1], m[2] - sc * pl.attach[2] + e[2]];
  }

  // =================================================================== putting the part in the model: one undo step for everything
  // The texture may need to grow (more space) and get more pixels (HD) for the pictures: model_texture does both as
  // steps of their own, so here they happen inside this step, the texture kept whole before / after with it (its canvas
  // does not come back by itself on a CPM skin) and put back on Ctrl+Z / Ctrl+Y.
  function copyCanvas(c0) { var c = document.createElement('canvas'); c.width = c0.width; c.height = c0.height; c.getContext('2d').drawImage(c0, 0, 0); return c; }
  function surfaces(tex) {
    if (tex.layers_enabled && tex.layers && tex.layers.length) return tex.layers.filter(function (l) { return l && l.ctx && (!l.type || l.type === 'pixel_layer'); }).map(function (l) { return { ctx: l.ctx, layer: l }; });
    var cv = tex.canvas; return cv ? [{ ctx: tex.ctx || cv.getContext('2d'), layer: null }] : [];
  }
  function snapshot(tex) { return { w: tex.width, h: tex.height, uvw: tex.uv_width, uvh: tex.uv_height, s: surfaces(tex).map(function (s) { return { c: copyCanvas(s.ctx.canvas), off: s.layer && s.layer.offset ? s.layer.offset.slice() : null }; }) }; }
  function refreshTexture(tex) {
    try { if (typeof tex.updateChangesAfterEdit === 'function') tex.updateChangesAfterEdit(); } catch (e) { }
    try { if (G.UVEditor && G.UVEditor.vue && typeof G.UVEditor.vue.updateTexture === 'function') G.UVEditor.vue.updateTexture(); } catch (e) { }
    try { if (G.Canvas && typeof G.Canvas.updateAllFaces === 'function') G.Canvas.updateAllFaces(tex); } catch (e) { }
    try { if (G.Canvas && typeof G.Canvas.updateAllUVs === 'function') G.Canvas.updateAllUVs(); } catch (e) { }
  }
  function restore(tex, snap) {
    var run = function () {
      surfaces(tex).forEach(function (s, i) {
        var b = snap.s[i]; if (!b) return;
        s.ctx.canvas.width = b.c.width; s.ctx.canvas.height = b.c.height; s.ctx.imageSmoothingEnabled = false; s.ctx.clearRect(0, 0, b.c.width, b.c.height); s.ctx.drawImage(b.c, 0, 0);
        if (s.layer && b.off) s.layer.offset = b.off.slice();
      });
      tex.width = snap.w; tex.height = snap.h;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    if (snap.uvw) { tex.uv_width = snap.uvw; tex.uv_height = snap.uvh; }
    refreshTexture(tex);
  }
  /** the texture's area k times wider and taller, every pixel kept where it is (no undo step of its own) */
  function growHere(tex, k) {
    var I = X().uvInfo(tex);
    var run = function () {
      surfaces(tex).forEach(function (s) { var c = copyCanvas(s.ctx.canvas); s.ctx.canvas.width = c.width * k; s.ctx.canvas.height = c.height * k; s.ctx.imageSmoothingEnabled = false; s.ctx.clearRect(0, 0, s.ctx.canvas.width, s.ctx.canvas.height); s.ctx.drawImage(c, 0, 0); });
      tex.width = Math.round(I.w * k); tex.height = Math.round(I.h * k); tex.keep_size = true;
    };
    if (typeof tex.edit === 'function') tex.edit(run, { no_undo: true }); else run();
    tex.uv_width = I.uvW * k; tex.uv_height = I.uvH * k;
    refreshTexture(tex);
  }
  /** k texture pixels per UV unit (every pixel becomes a block, nearest; no undo step of its own) */
  function hdHere(tex, k) {
    var f = k / X().density(tex); if (!(f > 1.0001)) return;
    var W = Math.round(tex.width * f), H = Math.round(tex.height * f);
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
    refreshTexture(tex);
  }
  var texSteps = new Map();
  function onUndo(d) { var s = d && d.entry && texSteps.get(d.entry); if (s) try { restore(s.tex, s.before); } catch (e) { L.warn('could not put the texture back', e); } }
  function onRedo(d) { var s = d && d.entry && texSteps.get(d.entry); if (s) try { restore(s.tex, s.after); } catch (e) { L.warn('could not redo the texture', e); } }
  function eachCube(list, fn) { (list || []).forEach(function (n) { if (n.c) fn(n); else if (n.g) eachCube(n.kids, fn); }); }
  /**
   * the item in the model, one undo step (parts, pixels, the texture's size, animations): o = {hd (pixels per unit the
   * pictures want), where (body part)} -> {groups (top), cubes, all, anims, grew, hd}
   */
  function insertItem(item, o) {
    o = o || {};
    var lib = Lb(), tx = X(), set = item.sets[0], parts = lib.prepare(set.parts);
    var parent = (o.where && lib.rootGroup(o.where)) || lib.rootGroup(set.root) || 'root';
    var anchor = parent !== 'root' && parent.origin ? parent.origin.slice() : (set.anchor || [0, 0, 0]).slice();
    var painted = []; eachCube(parts, function (n) { if (n.tex) painted.push(n.tex); });
    var tex = painted.length ? tx.mainTexture() : null;
    if (painted.length && !tex) throw new Error(T('texmap.none'));
    var before = tex ? snapshot(tex) : null, out = { cubes: [], groups: [], tex: [], byAid: {}, colours: false }, anims = [], tops = [], grew = 0, hd = false;
    var withAnims = !!(item.anims && item.anims.length && G.Animation);
    function aspects(cubes, groups, an) {
      var a = Object.assign({ outliner: true, elements: cubes, groups: groups }, tex ? tx.pixelAspects(tex) : {});
      if (withAnims) a.animations = an;
      return a;
    }
    G.Undo.initEdit(aspects([], [], []));
    try {
      var plans = [];
      // no room on the skin: its area doubles (64 -> 128 -> 256 -> 512, pixels kept) until the pictures fit
      for (var tries = 0; painted.length; tries++) {
        try { plans = lib.planPaint(painted, tex); break; }
        catch (e) { if (tries >= 3 || (e && e.message) !== T('lib.noroom')) throw e; growHere(tex, 2); grew++; }
      }
      // HD pictures: the texture first gets the pixels they were drawn with (UV units stay, the plan stays right)
      if (painted.length && o.hd && tx.density(tex) < o.hd - 1e-6) { hdHere(tex, o.hd); hd = true; }
      var n0 = out.groups.length;
      lib.build(parts, parent, anchor, out);
      for (var gi = n0; gi < out.groups.length; gi++) if (out.groups[gi].parent === parent || (parent === 'root' && out.groups[gi].parent === 'root')) tops.push(out.groups[gi]);
      if (out.tex.length) lib.paint(out.tex, plans, tex);
      if (withAnims) anims = lib.makeAnims(item, out.byAid, false);
    } catch (e) {
      try { G.Undo.cancelEdit(); } catch (x) { }
      if (before) try { restore(tex, before); } catch (x) { }
      throw e;
    }
    G.Undo.finishEdit(T('convert'), aspects(out.cubes, out.groups, anims));
    var H = G.Undo.history, entry = H && H[H.length - 1];
    if (tex && entry) texSteps.set(entry, { tex: tex, before: before, after: snapshot(tex) });
    M.refresh();
    if (tex) refreshTexture(tex);
    try { if (tex && typeof tex.updateLayerChanges === 'function') tex.updateLayerChanges(true); } catch (e) { }
    M.selectNodes(tops);
    return { groups: tops, cubes: out.cubes, all: out.groups, anims: anims, grew: grew, hd: hd };
  }

  // =================================================================== where the 3D model comes from
  function hasMeshes(p) { return (p && p.elements || []).some(function (e) { return e && e.type === 'mesh'; }); }
  /** the other open tabs with meshes (a model opened with File > Open, an OBJ imported into a Generic model...) */
  function sourceTabs() { return (G.ModelProject && G.ModelProject.all ? G.ModelProject.all : []).filter(function (p) { return p && p !== G.Project && hasMeshes(p); }); }
  function tabName(p) { return String(p.name || p.geometry_name || T('convert.src.noname')); }
  /** src of a tab or a parsed file + its textures loaded -> Promise of info {src, kind, label, project} */
  function ready(info) {
    return loadTextures(info.src, usedTextures(info.src, null)).then(function () { return info; });
  }
  function fromTab(p) { return ready({ src: fromProject(p), kind: 'tab', project: p, label: tabName(p) }); }
  /** a .bbmodel text -> info (Promise); a clear message for a file that is not one */
  function fromText(text, fileName) {
    var j = null; try { j = JSON.parse(text); } catch (e) { j = null; }
    var name = String(fileName || 'modelo').split(/[\\/]/).pop().replace(/\.[^.]+$/, '') || 'modelo';
    if (!j || !Array.isArray(j.elements)) return Promise.reject(new Error(T('convert.bad')));
    var src = fromJSON(j, name);
    if (!src.meshes.length) return Promise.reject(new Error(T('convert.nomesh')));
    return ready({ src: src, kind: 'file', label: name });
  }
  /** a picked file -> info (Promise): a .glb / .gltf through the glTF reader, a .bbmodel as JSON */
  function fromFile(f) {
    var name = String((f && f.name) || 'modelo').split(/[\\/]/).pop(), GL = L.core.modules.model_gltf;
    if (/\.(glb|gltf)$/i.test(name) && GL) {
      var src;
      try { src = GL.read(typeof f.content === 'string' ? new TextEncoder().encode(f.content) : f.content, name); } catch (e) { return Promise.reject(e); }
      return ready({ src: src, kind: 'file', label: src.name });
    }
    var text = typeof f.content === 'string' ? f.content : (typeof TextDecoder !== 'undefined' ? new TextDecoder().decode(f.content) : '');
    return fromText(text, name);
  }
  function pickFile() {
    G.Blockbench.import({ resource_id: 'cpmplus_convert', extensions: ['bbmodel', 'glb', 'gltf'], type: T('convert.file'), readtype: 'binary' }, function (files) {
      var f = files && files[0]; if (!f) return;
      fromFile(f).then(open, function (e) { lastError = e && e.message; G.Blockbench.showMessageBox({ title: T('convert'), message: e && e.message ? e.message : String(e) }); });
    });
  }

  // =================================================================== the window
  // lang: convert.type.{off|global|standing|walking|running|sneaking|jumping|falling|swimming|flying|creative_flying|riding|sleeping|gesture|custom_pose}
  var TYPES = ['off', 'global', 'standing', 'walking', 'running', 'sneaking', 'jumping', 'falling', 'swimming', 'flying', 'creative_flying', 'riding', 'sleeping', 'gesture', 'custom_pose'];
  // lang: convert.detail.{normal|high|vhigh|max}
  // [flattening, motion] tolerances (px of the part): a plane splits where the surface bends more, a fitted piece forms where
  // the mesh strays more from its bone's plane while it moves
  var DETAIL = { normal: [2, 3], high: [1, 1.5], vhigh: [0.5, 0.75], max: [0.25, 0.4] };
  // lang: convert.density.{hd8|hd4|skin}
  var MAX_ANIMS = 12, MAX_PREVIEW = 3000;
  var dlg = null, cur = null, lastError = null, running = null, lastResult = null;
  function typeOptions() { var o = {}; TYPES.forEach(function (t) { o[t] = T('convert.type.' + t); }); return o; }
  function defaultTypes(src) {
    var g = src.anims.map(function (a) { var t = Lb() && Lb().guessAnimType ? Lb().guessAnimType(a.name) : 'gesture'; return TYPES.indexOf(t) >= 0 ? t : 'gesture'; });
    if (g.length && g.indexOf('global') < 0) g[0] = 'global';
    return g;
  }
  function whereOptions(guess) {
    var o = { auto: T('convert.where.auto', T('lib.root.' + guess)) };
    ROOTS.forEach(function (r) { if (Lb().rootGroup(r)) o[r] = T('lib.root.' + r); });
    return o;
  }
  /** the options of the window, resolved: {job (for job()), keyA, keyB, root, density} */
  function resolve(c, f) {
    var src = c.src, kept = [];
    src.anims.forEach(function (a, i) { if (i >= MAX_ANIMS) return; var t = f['anim_' + i]; if (t && t !== 'off') kept.push({ i: i, type: t }); });
    var ref = -1;
    if (f.ref !== 'bind' && kept.length) { var g = kept.filter(function (k) { return k.type === 'global'; })[0]; ref = (g || kept[0]).i; }
    var meshes = f.meshes === 'all' || !f.meshes ? null : f.meshes === 'sel' ? src.meshes.filter(function (m) { return m.selected; }).map(function (m) { return m.id; }) : [f.meshes];
    return { meshes: meshes, mirror: f.mirror !== false && c.pairs > 0, ref: ref, anims: kept, closeLoop: f.loop !== false, budget: Math.max(0, Math.round(Number(f.budget) || 0)), detail: DETAIL[f.detail] || DETAIL.normal, density: f.density || 'hd8',
      size: Math.max(1, Number(f.size) || 1), where: f.where || 'auto', off: [Number(f.offx) || 0, Number(f.offy) || 0, Number(f.offz) || 0] };
  }
  /** the analysis for the window's choices (cached by what each level depends on) -> plan {st, B, lays, job options, stats} */
  function planFor(c, f) {
    var r = resolve(c, f), keyA = JSON.stringify([r.meshes, r.mirror, r.ref]);
    if (c.keyA !== keyA) { c.st = analyse(c.src, { meshes: r.meshes, mirror: r.mirror, ref: r.ref }); c.keyA = keyA; c.keyB = null; c.pl = placementOf(c.st); }
    // the size left as it came: the scale it was worked out with (a model made around a player keeps exactly 1)
    var st = c.st, pl = c.pl, sc = Math.abs(r.size - c.size0) < 1e-9 ? c.sc0 : r.size / pl.maxDim, tol = r.detail[0] / sc, animTol = r.detail[1] / sc, keyB = JSON.stringify([keyA, tol, animTol, r.budget]);
    if (c.keyB !== keyB) { c.B = clustersOf(st, { tol: tol, animTol: animTol, budget: r.budget }); c.keyB = keyB; }
    var words = [c.label, c.src.name].concat(c.src.meshes.map(function (m) { return m.name; })).join(' ');
    var guess = guessRoot(st, words, pl.built), root = r.where !== 'auto' && ROOTS.indexOf(r.where) >= 0 ? r.where : guess;
    var tex = X().mainTexture(), dens = r.density === 'hd4' ? 4 : r.density === 'skin' ? Math.max(1, Math.round(tex ? X().density(tex) : 1)) : 8;
    var lays = c.B.clusters.map(function (cl) { return layoutOf(st, cl, sc, dens); });
    var o = { meshes: r.meshes, mirror: r.mirror, ref: r.ref, tol: tol, animTol: animTol, budget: r.budget, sc: sc, d: dens, anchorOff: anchorOffOf(pl, root, sc, r.off), root: root, anchor: (PIVOT[root] || [0, 0, 0]).slice(),
      name: c.label, anims: r.anims, closeLoop: r.closeLoop };
    // the skin space the pictures need (UV units; a mirrored twin shares its side's pictures)
    var need = 0; c.B.clusters.forEach(function (cl, i) { var l = lays[i]; need += Math.ceil(l.wM * sc - 1e-6) * Math.ceil(l.hM * sc - 1e-6) * (l.two ? 2 : 1); });
    return { st: st, B: c.B, lays: lays, o: o, guess: guess, root: root, need: need, planes: c.B.planes, sc: sc };
  }
  /** how the pictures fit on the skin: '' (they fit) | 'grow:N' (the texture grows to N x N) | 'no' (not even at 512) */
  function roomFor(need) {
    var tex = X().mainTexture(); if (!tex) return 'none';
    var I = X().uvInfo(tex), map = X().occupancy(tex), free = 0;
    for (var i = 0; i < map.occ.length; i++) if (!map.occ[i]) free++;
    var w = I.uvW, h = I.uvH;
    if (need <= free * 0.85) return '';
    for (var k = 0; k < 3; k++) { free += w * h * 3; w *= 2; h *= 2; if (need <= free * 0.85) return 'grow:' + Math.round(w) + 'x' + Math.round(h); }
    return 'no';
  }
  function update(f) {
    var c = cur; if (!dlg || !c) return;
    var p;
    try { p = planFor(c, f); } catch (e) { L.warn('convert plan failed', e); M.clearPreview(); M.dialogNote(dlg, (e && e.message) || String(e), M.allCubes().length, true); return; }
    c.plan = p;
    var room = roomFor(p.need), roomText = room === '' ? T('convert.room.ok') : room === 'none' ? T('texmap.none') : room === 'no' ? T('convert.room.no') : T('convert.room.grow', room.slice(5).replace('x', ' \u00d7 '));
    // the shape (no pictures yet): how many groups, and the 3D preview of every plane where it will be (the rest pose)
    var shape = assemble(p.st, p.B, Object.assign({}, p.o, { planes: null, layouts: p.lays, previewColour: 0x4fc3f7 }));
    c.groups = shape.stats.groups + 1;
    M.dialogNote(dlg, T('convert.count', p.planes, c.groups, p.need, roomText), M.allCubes().length + p.planes, room === 'no' || room === 'none');
    try {
      var boxes = Lb().previewBoxes(shape, { where: p.root });
      if (boxes.length <= MAX_PREVIEW) M.preview(boxes.map(function (b) { return { from: b.from, to: b.to, matrix: b.matrix || undefined, col: b.col }; }), 0x4fc3f7, { opacity: 0.6 });
    } catch (e) { L.warn('convert preview failed', e); }
    var info = dlg.object && dlg.object.querySelector('.cmp_cv_where');
    if (info) info.textContent = p.st.box ? T('convert.size', M.num(p.st.box.hi[0] - p.st.box.lo[0], 1), M.num(p.st.box.hi[1] - p.st.box.lo[1], 1), M.num(p.st.box.hi[2] - p.st.box.lo[2], 1), M.num(p.sc, 3)) : '';
  }
  function sourceOptions(c) {
    var o = {};
    sourceTabs().forEach(function (p) { o['tab:' + p.uuid] = T('convert.src.tab', tabName(p)); });
    if (c.kind === 'file') o.file = T('convert.src.file', c.label);
    o.pick = T('convert.src.pick');
    return o;
  }
  /** the window for a model (info from fromTab / fromText) */
  function open(info) {
    if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; }
    M.clearPreview();
    var src = info.src, c = { src: src, kind: info.kind, label: info.label, project: info.project };
    var rig = rigOf(src); c.pairs = Object.keys(rig.twin).length / 2;
    var tris = 0; src.meshes.forEach(function (m) { tris += m.faces.length; });
    var sel = src.meshes.filter(function (m) { return m.selected; }).length;
    var meshOpts = { all: T('convert.meshes.all', src.meshes.length) };
    if (sel && sel < src.meshes.length) meshOpts.sel = T('convert.meshes.sel', sel);
    if (src.meshes.length <= 16) src.meshes.forEach(function (m) { meshOpts[m.id] = m.name; });
    var types = defaultTypes(src), anims = src.anims.slice(0, MAX_ANIMS);
    // the first look, for the defaults: the model as it will be made (size, body part); kept for the window's first plan
    var ref0 = types.indexOf('global'); if (ref0 < 0 || ref0 >= anims.length) ref0 = anims.length ? 0 : -1;
    var st0 = analyse(src, { meshes: null, mirror: c.pairs > 0, ref: ref0 }), pl0 = placementOf(st0);
    c.st = st0; c.pl = pl0; c.keyA = JSON.stringify([null, c.pairs > 0, ref0]);
    var size0 = pl0.built ? Math.round(pl0.maxDim * 10) / 10 : Math.max(1, Math.round(Math.min(pl0.maxDim, 60)));
    c.size0 = size0; c.sc0 = pl0.built ? 1 : size0 / pl0.maxDim;
    var guess0 = guessRoot(st0, [c.label, src.name].concat(src.meshes.map(function (m) { return m.name; })).join(' '), pl0.built);
    var form = {
      source: { label: T('convert.f.source'), type: 'select', options: sourceOptions(c), value: c.kind === 'tab' ? 'tab:' + c.project.uuid : 'file' },
      meshes: { label: T('convert.f.meshes'), type: 'select', options: meshOpts, value: 'all', condition: function () { return src.meshes.length > 1; } },
      where: { label: T('convert.f.where'), type: 'select', options: whereOptions(guess0), value: 'auto' },
      size: { label: T('convert.f.size'), type: 'number', value: size0, min: 1, max: 512, step: 1 },
      detail: { label: T('convert.f.detail'), type: 'select', options: { normal: T('convert.detail.normal'), high: T('convert.detail.high'), vhigh: T('convert.detail.vhigh'), max: T('convert.detail.max') }, value: 'normal' },
      density: { label: T('convert.f.density'), type: 'select', options: { hd8: T('convert.density.hd8'), hd4: T('convert.density.hd4'), skin: T('convert.density.skin') }, value: 'hd8' },
      mirror: { label: T('convert.f.mirror'), type: 'checkbox', value: true, condition: function () { return c.pairs > 0; } }
    };
    var topts = typeOptions();
    anims.forEach(function (a, i) { form['anim_' + i] = { label: T('convert.f.anim', a.name), type: 'select', options: topts, value: types[i] }; });
    form.more = { label: T('convert.f.more'), type: 'checkbox', value: false };
    function more(f) { return !!(f && f.more); }
    form.offx = { label: T('convert.f.offx'), type: 'number', value: 0, step: 0.5, min: -256, max: 256, condition: more };
    form.offy = { label: T('convert.f.offy'), type: 'number', value: 0, step: 0.5, min: -256, max: 256, condition: more };
    form.offz = { label: T('convert.f.offz'), type: 'number', value: 0, step: 0.5, min: -256, max: 256, condition: more };
    form.ref = { label: T('convert.f.ref'), type: 'select', options: { mean: T('convert.ref.mean'), bind: T('convert.ref.bind') }, value: 'mean', condition: function (f) { return more(f) && anims.length > 0; } };
    form.budget = { label: T('convert.f.budget'), type: 'number', value: 0, min: 0, max: 20000, step: 1, condition: more };
    form.loop = { label: T('convert.f.loop'), type: 'checkbox', value: true, condition: function (f) { return more(f) && anims.length > 0; } };
    var infoLine = T('convert.info', c.label, src.meshes.length, tris, src.bones.length, src.anims.length);
    var notes = [];
    if (src.skipped.molang) notes.push(T('convert.molang', src.skipped.molang));
    if (src.skipped.scale) notes.push(T('convert.scale', src.skipped.scale));
    if (src.anims.length > MAX_ANIMS) notes.push(T('convert.manyanims', MAX_ANIMS));
    cur = c;
    dlg = new G.Dialog({
      id: 'cpmplus_model_convert', title: T('convert'), width: 560, buttons: [T('convert.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('convert.tip')) + '</p>', '<p class="cmp_cv_info">' + L.esc(infoLine) + '</p>', '<p class="cmp_cv_where"></p>'].concat(notes.map(function (n) { return '<p class="cmp_cv_note">' + L.esc(n) + '</p>'; })),
      form: form,
      onFormChange: function (f) {
        if (f.source && f.source !== form.source.value) {
          var pick = f.source;
          setTimeout(function () {
            if (pick === 'pick') { close(); pickFile(); return; }
            var p = sourceTabs().filter(function (x) { return 'tab:' + x.uuid === pick; })[0];
            if (p) { close(); fromTab(p).then(open); }
          }, 0);
          return;
        }
        update(f);
      },
      onConfirm: function (f) {
        var p = null; try { p = planFor(c, f); } catch (e) { G.Blockbench.showQuickMessage((e && e.message) || String(e), 4000); return false; }
        M.clearPreview(); dlg = null; cur = null;
        start(c, p);
      },
      onCancel: function () { M.clearPreview(); dlg = null; cur = null; }
    });
    function close() { if (dlg) { try { dlg.close(); } catch (e) { } } M.clearPreview(); dlg = null; cur = null; }
    dlg.show();
    try { update(dlg.getFormResult()); } catch (e) { L.warn('convert window failed', e); }
    return dlg;
  }

  // =================================================================== converting: chunks, a progress bar, Cancel
  var RUN_ID = 'cpmplus_model_convert_run';
  /** runs the plan in small steps (Blockbench stays responsive); a small model is done before any window shows -> Promise of the insert result */
  function start(c, p) {
    var J = job(c.src, p.o, { st: p.st, B: p.B }), project = G.Project, pd = null, cancelled = false, t0 = now();
    lastError = null;
    return new Promise(function (resolveP) {
      function finish(err, res) {
        running = null;
        if (pd) { var d = pd; pd = null; try { d.close(); } catch (e) { } }
        if (err === 'cancel') { G.Blockbench.showQuickMessage(T('convert.cancelled'), 2500); resolveP(null); return; }
        if (err) { lastError = err && err.message ? err.message : String(err); G.Blockbench.showMessageBox({ title: T('convert'), message: T('error') + '\n' + lastError }); resolveP(null); return; }
        lastResult = res;
        var na = res.anims.length;
        G.Blockbench.showQuickMessage(T('convert.done', res.cubes.length, na === 1 ? T('lib.anims.one') : T('lib.anims', na)), 3000);
        resolveP(res);
      }
      function bar() {
        if (!pd || !pd.object) return;
        var b = pd.object.querySelector('.cmp_cv_bar > div'), l = pd.object.querySelector('.cmp_cv_label');
        if (b) b.style.width = Math.round(J.progress * 100) + '%';
        if (l) l.textContent = J.total ? T('convert.p.paint', J.done, J.total) : T('convert.p.analyse');
      }
      function tick() {
        if (cancelled) { finish('cancel'); return; }
        var done;
        try { done = J.step(now() + 30); } catch (e) { finish(e); return; }
        if (!done) {
          // a window with the progress once it takes a while
          if (!pd && now() - t0 > 150) {
            pd = new G.Dialog({ id: RUN_ID, title: T('convert'), width: 420, buttons: ['dialog.cancel'], confirmIndex: 0, cancelIndex: 0,
              lines: ['<div class="cmp_cv_run"><p class="cmp_cv_label">' + L.esc(T('convert.p.analyse')) + '</p><div class="cmp_cv_bar"><div></div></div></div>'],
              onConfirm: function () { cancelled = true; }, onCancel: function () { cancelled = true; } });
            pd.show();
          }
          bar();
          setTimeout(tick, 0);
          return;
        }
        if (G.Project !== project) { finish(new Error(T('convert.moved'))); return; }
        try { var res = insertItem(J.item, { hd: p.o.d, where: p.root }); res.item = J.item; finish(null, res); } catch (e) { finish(e); }
      }
      running = { cancel: function () { cancelled = true; }, job: J };
      tick();
    });
  }

  // =================================================================== the action
  function run() {
    if (!M.isCpm()) {
      // clicked in the 3D model's own tab: back to the CPM project, with this tab as the model
      var cpmTab = (G.ModelProject && G.ModelProject.all || []).filter(function (p) { return p && p.format && p.format.id === 'cpm'; })[0], here = G.Project;
      if (cpmTab && hasMeshes(here)) { cpmTab.select(); setTimeout(function () { fromTab(here).then(open); }, 50); return; }
      G.Blockbench.showMessageBox({ title: T('convert'), message: T('convert.cpmonly') }); lastError = 'cpm'; return;
    }
    if (running) { G.Blockbench.showQuickMessage(T('convert.busy'), 2500); return; }
    var tabs = sourceTabs();
    // the model opened last (the one just brought in)
    if (tabs.length) fromTab(tabs[tabs.length - 1]).then(open, function (e) { G.Blockbench.showMessageBox({ title: T('convert'), message: (e && e.message) || String(e) }); });
    else pickFile();
  }
  /** File > Import: right after 'Importar .vox' (or 'Import OBJ') */
  function placeInImport(action) {
    var file = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.file, imp = null;
    if (!file || !Array.isArray(file.structure)) return;
    file.structure.forEach(function (x) { if (x && x.id === 'import') imp = x; });
    if (!imp || !Array.isArray(imp.children)) return;
    var kids = imp.children;
    for (var i = kids.length - 1; i >= 0; i--) if (kids[i] && kids[i] !== action && kids[i].id === action.id) kids.splice(i, 1);
    if (kids.indexOf(action) >= 0) return;
    var at = -1; kids.forEach(function (x, k) { var id = typeof x === 'string' ? x : x && x.id; if (id === 'cpmplus_model_vox' || (at < 0 && id === 'import_obj')) at = k; });
    kids.splice(at >= 0 ? at + 1 : kids.length, 0, action);
    L.onUninstall(function () { var k = kids.indexOf(action); if (k >= 0) kids.splice(k, 1); });
  }
  var STYLE = 'cmp_convert_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = ['.cmp_cv_info{font-weight:600;margin:4px 0 2px}', '.cmp_cv_where{opacity:.8;margin:0 0 8px}', '.cmp_cv_note{opacity:.8;margin:0 0 4px}',
        '.cmp_cv_run{padding:6px 2px}', '.cmp_cv_label{margin:0 0 8px}', '.cmp_cv_bar{height:10px;border-radius:5px;background:var(--color-back);border:1px solid var(--color-border);overflow:hidden}',
        '.cmp_cv_bar>div{height:100%;width:0;background:var(--color-accent);transition:width .15s}'].join('\n');
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var action = M.register({ id: 'cpmplus_model_convert', sec: 'create', icon: 'transform', key: 'convert', spots: [], run: function () { run(); } });
    placeInImport(action);
    try {
      G.Blockbench.on('undo', onUndo); G.Blockbench.on('redo', onRedo);
      L.onUninstall(function () { G.Blockbench.removeListener('undo', onUndo); G.Blockbench.removeListener('redo', onRedo); });
    } catch (e) { }
  };
  api.uninstall = function () {
    if (running) running.cancel();
    if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; }
    M.clearPreview(); texSteps.clear();
  };

  api.engine = { fromJSON: fromJSON, fromProject: fromProject, usedTextures: usedTextures, loadTextures: loadTextures, rigOf: rigOf, analyse: analyse, clustersOf: clustersOf, layoutOf: layoutOf, rasterOf: rasterOf, assemble: assemble, job: job, runJob: runJob, twinNames: twinNames, valAt: valAt, reduceKeys: reduceKeys, euler: euler, eulerOf: eulerOf, placementOf: placementOf, guessRoot: guessRoot, anchorOffOf: anchorOffOf, poseAt: poseAt, skin: skin, meanOf: meanOf, fitPiece: fitPiece, easedChannels: easedChannels, poseOfAnim: poseOfAnim };
  api.run = run; api.open = open; api.fromTab = fromTab; api.fromText = fromText; api.fromFile = fromFile; api.pickFile = pickFile; api.sourceTabs = sourceTabs; api.start = start; api.insertItem = insertItem; api.planFor = planFor;
  api.dialog = function () { return dlg; }; api.current = function () { return cur; }; api.running = function () { return running; }; api.lastError = function () { return lastError; }; api.lastResult = function () { return lastResult; };
  // the texture steps of insertItem, for tools that put parts in inside their own undo step (model_combine)
  api.texSteps = { snapshot: snapshot, restore: restore, grow: growHere, hd: hdHere, refresh: refreshTexture };
  return api;
})
