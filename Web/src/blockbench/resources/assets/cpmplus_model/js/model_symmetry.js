/*
 * CPM Plugin+ "CPM+ Modelar" - Conferir simetria do modelo: finds the left / right counterparts (names with left /
 * right, esquerdo / direito, esq / dir, _l / _r ... in any case or separator; unnamed cubes by their mirrored place
 * across X = 0) and lists what is not symmetric: a missing counterpart, size, position, rotation, pivot, colour, a
 * painting that is not mirrored, and differences inside a pair of groups. Each row has 'Espelhar deste lado' on both
 * sides: the other side becomes the exact mirror of this one (geometry, rotation, pivot, colour, the texture mirrored
 * the way Blockbench mirrors it: box UV mirror / per-face UVs flipped, east <-> west), a missing counterpart is made
 * as a mirrored copy (fresh CPM ids, names with the side swapped). One undo step per fix; the list refreshes.
 * The player's own base cubes and the body parts' fixed pivots are never changed.
 * Loaded after the modelling engine (and model_flatten, whose face mapping it uses for the painting check).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var EPS = 0.001, NEAR = 0.51;
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];

  // =================================================================== names with a side
  var SIDE = { left: 'L', right: 'R', esquerdo: 'L', esquerda: 'L', esq: 'L', direito: 'R', direita: 'R', dir: 'R', l: 'L', r: 'R' };
  var SWAP = { left: 'right', right: 'left', esquerdo: 'direito', esquerda: 'direita', direito: 'esquerdo', direita: 'esquerda', esq: 'dir', dir: 'esq', l: 'r', r: 'l' };
  function tokens(name) {
    var out = [], re = /[A-Z]?[a-z]+|[A-Z]+(?![a-z])|[0-9]+/g, m, s = String(name || '');
    while ((m = re.exec(s))) out.push({ t: m[0], lo: m[0].toLowerCase(), at: m.index });
    return out;
  }
  /** {side: 'L' | 'R', key (the name without its side word), tok} or null (no side word, or two of them) */
  function sideOf(name) {
    var tk = tokens(name), hits = [];
    tk.forEach(function (x, i) { if (SIDE[x.lo] && (x.lo.length > 1 || i === 0 || i === tk.length - 1)) hits.push(i); });
    if (hits.length !== 1) return null;
    var i0 = hits[0];
    return { side: SIDE[tk[i0].lo], key: tk.map(function (x, i) { return i === i0 ? '#' : x.lo; }).join('_'), tok: tk[i0] };
  }
  /** the name with its side word swapped (same letter case) */
  function swapName(name) {
    var s = sideOf(name); if (!s) return name;
    var w = SWAP[s.tok.lo], t = s.tok.t;
    var out = t === t.toUpperCase() ? w.toUpperCase() : t[0] === t[0].toUpperCase() ? w[0].toUpperCase() + w.slice(1) : w;
    return name.slice(0, s.tok.at) + out + name.slice(s.tok.at + t.length);
  }

  // =================================================================== geometry of the mirror (x -> -x)
  function isRoot(g) { return M.isGroup(g) && g.parent === 'root' && ROOTS.indexOf(g.name) >= 0 && M.isCpm(); }
  function box(c) { var lo = [], hi = []; for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i])); hi.push(Math.max(c.from[i], c.to[i])); } return { lo: lo, hi: hi }; }
  function centre(n) { if (M.isCube(n)) { var b = box(n); return [(b.lo[0] + b.hi[0]) / 2, (b.lo[1] + b.hi[1]) / 2, (b.lo[2] + b.hi[2]) / 2]; } var bb = M.cubesOf([n]).map(box); if (!bb.length) return n.origin.slice(); var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity]; bb.forEach(function (b) { for (var i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], b.lo[i]); hi[i] = Math.max(hi[i], b.hi[i]); } }); return [(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2]; }
  function mirRot(r) { return [r[0], r[1] ? -r[1] : 0, r[2] ? -r[2] : 0]; }
  function same3(a, b, e) { return Math.abs(a[0] - b[0]) <= (e || EPS) && Math.abs(a[1] - b[1]) <= (e || EPS) && Math.abs(a[2] - b[2]) <= (e || EPS); }
  function turned(n) { var r = n.rotation || [0, 0, 0]; return !!(r[0] || r[1] || r[2]); }
  function mirFace(k) { return k === 'east' ? 'west' : k === 'west' ? 'east' : k; }
  function recolour(c) { return typeof c.cpm_recolor === 'number' && c.cpm_recolor !== -1 ? c.cpm_recolor & 0xFFFFFF : null; }
  function paintedFaces(c) { return FACES.filter(function (k) { var f = c.faces && c.faces[k]; return f && f.texture !== false && f.texture !== null && f.texture !== undefined; }); }

  // =================================================================== comparing a pair (B should be the mirror of A)
  // a face as you look at it from outside: the axis going right and the axis going down ([axis, direction]); the
  // mirror across X turns A's face picture into B's mirrored face picture flipped left-right
  var RIGHT = { north: [0, -1], south: [0, 1], east: [2, -1], west: [2, 1], up: [0, 1], down: [0, 1] };
  var DOWN = { north: [1, -1], south: [1, -1], east: [1, -1], west: [1, -1], up: [2, 1], down: [2, -1] };
  /** the picture a face shows (sampled at the texture's resolution) -> {w, h, px: [...]} or null */
  function picture(c, k) {
    var F = L.core.modules.model_flatten, m = F && F.faceMap ? F.faceMap(c, k) : null; if (!m) return null;
    var img = canvasData(m.tex); if (!img) return null;
    var I = L.core.modules.model_texture.uvInfo(m.tex), f = c.inflate || 0, b = box(c), R = RIGHT[k], D = DOWN[k], out = [];
    var lo = [b.lo[0] - f, b.lo[1] - f, b.lo[2] - f], hi = [b.hi[0] + f, b.hi[1] + f, b.hi[2] + f];
    var lr = hi[R[0]] - lo[R[0]], ld = hi[D[0]] - lo[D[0]], nr = Math.max(1, Math.round(lr * I.sx)), nd = Math.max(1, Math.round(ld * I.sy));
    for (var j = 0; j < nd; j++) for (var i = 0; i < nr; i++) {
      var p = [0, 0, 0];
      p[R[0]] = R[1] > 0 ? lo[R[0]] + (i + 0.5) / nr * lr : hi[R[0]] - (i + 0.5) / nr * lr;
      p[D[0]] = D[1] > 0 ? lo[D[0]] + (j + 0.5) / nd * ld : hi[D[0]] - (j + 0.5) / nd * ld;
      out.push(pix(img, m.uv(p[m.b1], p[m.b2])));
    }
    return { w: nr, h: nd, px: out };
  }
  /** the painting of A's faces seen on B: 'ok' (mirrored) | 'copied' (the same picture, not mirrored) | 'diff' */
  function paintCheck(a, b) {
    var res = 'ok';
    paintedFaces(a).some(function (k) {
      var pa = picture(a, k), pb = picture(b, mirFace(k));
      if (!pa || !pb || pa.w !== pb.w || pa.h !== pb.h) { res = 'diff'; return true; }
      var mirrored = true, plain = true;
      for (var j = 0; j < pa.h; j++) for (var i = 0; i < pa.w; i++) {
        var v = pb.px[j * pa.w + i];
        if (v !== pa.px[j * pa.w + (pa.w - 1 - i)]) mirrored = false;
        if (v !== pa.px[j * pa.w + i]) plain = false;
      }
      if (!mirrored) { res = plain ? 'copied' : 'diff'; return true; }
      return false;
    });
    return res;
  }
  var dataCache = null;
  function canvasData(tex) {
    dataCache = dataCache || {};
    if (dataCache[tex.uuid]) return dataCache[tex.uuid];
    var cv = L.core.modules.model_texture.canvasOf(tex); if (!cv) return null;
    try { return (dataCache[tex.uuid] = cv.getContext('2d').getImageData(0, 0, cv.width, cv.height)); } catch (e) { return null; }
  }
  function pix(img, x, y) {
    if (Array.isArray(x)) { y = x[1]; x = x[0]; }
    var xi = Math.min(img.width - 1, Math.max(0, Math.floor(x))), yi = Math.min(img.height - 1, Math.max(0, Math.floor(y))), i = (yi * img.width + xi) * 4;
    return img.data[i + 3] ? (img.data[i] << 24 | img.data[i + 1] << 16 | img.data[i + 2] << 8 | img.data[i + 3]) >>> 0 : 0;
  }
  /** differences between A and B (B should be A mirrored): {kinds: [...], n} */
  function diff(a, b) {
    var kinds = [], n = 0;
    function add(k, c) { if (kinds.indexOf(k) < 0) kinds.push(k); n += c || 1; }
    if (M.isCube(a) !== M.isCube(b)) { add('kind'); return { kinds: kinds, n: n }; }
    if (M.isCube(a)) {
      var A = box(a), B = box(b), sa = [A.hi[0] - A.lo[0], A.hi[1] - A.lo[1], A.hi[2] - A.lo[2]], sb = [B.hi[0] - B.lo[0], B.hi[1] - B.lo[1], B.hi[2] - B.lo[2]];
      if (!same3(sa, sb) || Math.abs((a.inflate || 0) - (b.inflate || 0)) > EPS) add('size');
      else if (!same3([-A.hi[0], A.lo[1], A.lo[2]], [B.lo[0], B.lo[1], B.lo[2]])) add('pos');
      if (!same3(mirRot(a.rotation), b.rotation)) add('rot');
      else if ((turned(a) || turned(b)) && !same3([-a.origin[0], a.origin[1], a.origin[2]], b.origin)) add('pivot');
      var pa = paintedFaces(a).length > 0, pb = paintedFaces(b).length > 0;
      if (pa !== pb || (!pa && recolour(a) !== recolour(b)) || !!a.cpm_glow !== !!b.cpm_glow) add('colour');
      else if (pa) { var pc = paintCheck(a, b); if (pc === 'copied') add('texture'); else if (pc === 'diff') add('paint'); }
      return { kinds: kinds, n: n };
    }
    if (!isRoot(a) && !isRoot(b)) {
      if (!same3(mirRot(a.rotation), b.rotation)) add('rot');
      if (!same3([-a.origin[0], a.origin[1], a.origin[2]], b.origin)) add('pivot');
    }
    var m = matchKids(a, b);
    if (m.onlyA.length || m.onlyB.length) add('inside', m.onlyA.length + m.onlyB.length);
    m.pairs.forEach(function (p) { var d = diff(p[0], p[1]); if (d.n) add('inside', d.n); });
    return { kinds: kinds, n: n };
  }
  /** the children of two groups paired: by side names, same name, then mirrored place -> {pairs, onlyA, onlyB} */
  function matchKids(a, b) {
    var ka = (a.children || []).filter(function (x) { return !M.isBase(x); }), kb = (b.children || []).filter(function (x) { return !M.isBase(x); }), pairs = [], used = [];
    var left = ka.filter(function (x) {
      var want = swapName(x.name), hit = null;
      kb.forEach(function (y) { if (!hit && used.indexOf(y) < 0 && (y.name === want || (want === x.name && y.name === x.name))) hit = y; });
      if (hit) { pairs.push([x, hit]); used.push(hit); return false; }
      return true;
    });
    var onlyA = left.filter(function (x) {
      var c = centre(x), hit = null;
      kb.forEach(function (y) { if (!hit && used.indexOf(y) < 0 && M.isCube(x) === M.isCube(y) && same3([-c[0], c[1], c[2]], centre(y), NEAR)) hit = y; });
      if (hit) { pairs.push([x, hit]); used.push(hit); return false; }
      return true;
    });
    return { pairs: pairs, onlyA: onlyA, onlyB: kb.filter(function (y) { return used.indexOf(y) < 0; }) };
  }

  // =================================================================== the check
  function depth(n) { var d = 0; for (var p = n.parent; p && p !== 'root'; p = p.parent) d++; return d; }
  function under(n, nodes) { for (var p = n; p && p !== 'root'; p = p.parent) if (nodes.indexOf(p) >= 0) return true; return false; }
  /** the counterpart group a node's copy goes into: the paired group of its parent, or its own parent */
  function sideIndex(all) {
    var idx = {};
    all.forEach(function (x) { var t = sideOf(x.name); if (t) (idx[t.key] = idx[t.key] || []).push({ n: x, side: t.side }); });
    return idx;
  }
  function partnerOf(n, idx) {
    var s = sideOf(n.name); if (!s) return null;
    var list = idx[s.key] || [];
    for (var i = 0; i < list.length; i++) { var x = list[i]; if (x.n !== n && x.side !== s.side && M.isCube(x.n) === M.isCube(n)) return x.n; }
    return null;
  }
  /** -> rows: [{a, b (null = missing), kinds, n}] (a = left when known) */
  function check() {
    dataCache = null;
    var nodes = (G.Group ? G.Group.all.slice() : []).concat(M.allCubes()).filter(function (n) { return !M.isBase(n); });
    nodes.sort(function (x, y) { return depth(x) - depth(y); });
    var rows = [], covered = [], idx = sideIndex(nodes);
    nodes.forEach(function (n) {
      // the body parts themselves (left_arm / right_arm ...) are fixed by CPM: their insides are compared piece by piece
      if (isRoot(n) || under(n, covered)) return;
      var s = sideOf(n.name); if (!s) return;
      var p = partnerOf(n, idx);
      if (p && under(p, covered)) return;
      if (!p) { rows.push({ a: n, b: null, kinds: ['missing'], n: 1 }); covered.push(n); return; }
      if (s.side === 'R') return;// the pair is handled from its left side
      var d = diff(n, p);
      covered.push(n, p);
      if (d.n) rows.push({ a: n, b: p, kinds: d.kinds, n: d.n });
    });
    // unnamed cubes: their mirrored place across X = 0 (in the same group, or in the partner groups: left_arm / right_arm)
    var free = M.allCubes().filter(function (c) { return !M.isBase(c) && !sideOf(c.name) && !under(c, covered); }), done = [];
    var gidx = sideIndex(G.Group ? G.Group.all : []), partnerParent = function (c) { return M.isGroup(c.parent) ? partnerOf(c.parent, gidx) : null; };
    free.forEach(function (c) {
      if (done.indexOf(c) >= 0) return;
      var cc = centre(c); if (Math.abs(cc[0]) < 0.26) return;
      var hit = null, pp = partnerParent(c);
      free.forEach(function (o) { if (!hit && o !== c && done.indexOf(o) < 0 && (o.parent === c.parent || (pp && o.parent === pp)) && same3([-cc[0], cc[1], cc[2]], centre(o), NEAR)) hit = o; });
      if (!hit) return;
      done.push(c, hit);
      var a = cc[0] < 0 ? c : hit, b = a === c ? hit : c, d = diff(a, b);
      if (d.n) rows.push({ a: a, b: b, kinds: d.kinds, n: d.n });
    });
    dataCache = null;
    return rows;
  }

  // =================================================================== fixes (one undo step each)
  function mirrorUV(a, b) {
    b.box_uv = a.box_uv;
    if (a.box_uv) {
      b.uv_offset = [a.uv_offset[0], a.uv_offset[1]]; b.mirror_uv = !a.mirror_uv;
      FACES.forEach(function (k) { if (b.faces[k] && a.faces[k]) b.faces[k].texture = a.faces[k].texture; });
    } else {
      var copy = {};
      FACES.forEach(function (k) { var f = a.faces[k]; copy[k] = f ? { uv: f.uv.slice(), rot: f.rotation || 0, tex: f.texture } : null; });
      FACES.forEach(function (k) {
        var src = copy[k], f = b.faces[mirFace(k)]; if (!src || !f) return;
        // the face shows its picture flipped left-right: u swapped, the turn the other way
        f.uv = [src.uv[2], src.uv[1], src.uv[0], src.uv[3]]; f.rotation = (360 - src.rot) % 360; f.texture = src.tex;
      });
    }
    try { if (b.box_uv && typeof b.updateUV === 'function') b.updateUV(); } catch (e) { }
  }
  /** b becomes a mirrored a (cubes) */
  function mirrorCube(a, b) {
    var A = box(a);
    for (var i = 0; i < 3; i++) { b.from[i] = i === 0 ? -A.hi[0] : A.lo[i]; b.to[i] = i === 0 ? -A.lo[0] : A.hi[i]; }
    b.origin[0] = -a.origin[0]; b.origin[1] = a.origin[1]; b.origin[2] = a.origin[2];
    var r = mirRot(a.rotation); for (var j = 0; j < 3; j++) b.rotation[j] = r[j];
    b.inflate = a.inflate || 0; b.cpm_glow = !!a.cpm_glow; b.cpm_recolor = a.cpm_recolor;
    if (!paintedFaces(a).length) FACES.forEach(function (k) { if (b.faces[k]) b.faces[k].texture = false; });
    else mirrorUV(a, b);
  }
  /** a node flipped across X = 0 in place (its own copy), names with the side swapped */
  function flipInPlace(n) {
    if (n.name) n.name = swapName(n.name);
    if (M.isCube(n)) {
      var c = { from: n.from.slice(), to: n.to.slice(), origin: n.origin.slice(), rotation: n.rotation.slice(), inflate: n.inflate, cpm_glow: n.cpm_glow, cpm_recolor: n.cpm_recolor, box_uv: n.box_uv, uv_offset: (n.uv_offset || [0, 0]).slice(), mirror_uv: n.mirror_uv, faces: n.faces };
      var faces = {}; FACES.forEach(function (k) { var f = n.faces[k]; faces[k] = f ? { uv: f.uv.slice(), rotation: f.rotation || 0, texture: f.texture } : null; });
      c.faces = faces;
      mirrorCube(c, n);
      return;
    }
    if (!isRoot(n)) { n.origin[0] = -n.origin[0]; var r = mirRot(n.rotation); for (var j = 0; j < 3; j++) n.rotation[j] = r[j]; }
    (n.children || []).forEach(flipInPlace);
  }
  /** b (and everything inside) becomes the mirror of a */
  function mirrorInto(a, b) {
    if (M.isCube(a)) { mirrorCube(a, b); return; }
    if (!isRoot(a) && !isRoot(b)) {
      b.origin[0] = -a.origin[0]; b.origin[1] = a.origin[1]; b.origin[2] = a.origin[2];
      var r = mirRot(a.rotation); for (var j = 0; j < 3; j++) b.rotation[j] = r[j];
    }
    var m = matchKids(a, b);
    m.pairs.forEach(function (p) {
      if (M.isCube(p[0]) === M.isCube(p[1])) mirrorInto(p[0], p[1]);
      else { p[1].remove(); copyInto(p[0], b); }
    });
    m.onlyB.forEach(function (y) { y.remove(); });
    m.onlyA.forEach(function (x) { copyInto(x, b); });
  }
  function copyInto(x, parent) {
    var c = M.duplicateNode(x);
    // Blockbench numbers a copy's name ('asa_left2'): the mirrored copy takes the original names, side swapped below
    (function names(s, d) { d.name = s.name; (s.children || []).forEach(function (k, i) { if (d.children && d.children[i]) names(k, d.children[i]); }); })(x, c);
    c.addTo(parent === 'root' ? undefined : parent);
    flipInPlace(c);
    return c;
  }
  /** fixes a row: from = 'a' | 'b' (the side that is kept and mirrored onto the other) -> true when something changed */
  function fix(row, from) {
    var src = from === 'b' ? row.b : row.a, dst = from === 'b' ? row.a : row.b;
    if (!src) return false;
    var touched = dst ? [dst] : [], made = null;
    M.edit(T('sym.fix'), { outliner: true, elements: M.cubesOf(touched), groups: M.groupsOf(touched) }, function () {
      if (dst) mirrorInto(src, dst);
      else {
        // a missing counterpart: a mirrored copy, in the partner of the original's group
        var p = src.parent && src.parent !== 'root' ? src.parent : 'root', pp = M.isGroup(p) ? partnerOf(p, sideIndex(G.Group.all)) : null;
        made = copyInto(src, pp || p);
      }
    }, function () {
      var n = dst || made;
      return { outliner: true, elements: M.cubesOf([n]), groups: M.groupsOf([n]) };
    });
    M.refresh();
    return true;
  }

  // =================================================================== the window
  var dlg = null, rows = [];
  // lang: sym.k.{missing|size|pos|rot|pivot|colour|texture|paint|inside|kind}
  // lang: sym.fix.{a|make}
  function rowText(r) {
    if (!r.b) return T('sym.missing', r.a.name, swapName(r.a.name));
    return r.kinds.map(function (k) { return T('sym.k.' + k); }).join(', ');
  }
  function render() {
    var root = dlg && dlg.object; if (!root) return;
    var list = root.querySelector('.cmp_sym_list'), sum = root.querySelector('.cmp_sym_sum');
    sum.textContent = rows.length ? T('sym.sum', rows.length) : T('sym.ok');
    list.innerHTML = rows.map(function (r, i) {
      var a = '<button type="button" class="cmp_sym_fix" data-i="' + i + '" data-from="a" title="' + L.esc(T('sym.fix.tip', r.a.name)) + '">' + L.esc(T(r.b ? 'sym.fix.a' : 'sym.fix.make')) + '</button>';
      var b = r.b ? '<button type="button" class="cmp_sym_fix" data-i="' + i + '" data-from="b" title="' + L.esc(T('sym.fix.tip', r.b.name)) + '">' + L.esc(T('sym.fix.b')) + '</button>' : '';
      return '<div class="cmp_sym_row" data-i="' + i + '"><div class="nm"><a data-i="' + i + '">' + L.esc(r.a.name) + (r.b ? ' \u2194 ' + L.esc(r.b.name) : '') + '</a></div><div class="what">' + L.esc(rowText(r)) + '</div><div class="bt">' + a + b + '</div></div>';
    }).join('');
  }
  function onClick(e) {
    var t = e.target, i = t && t.getAttribute && t.getAttribute('data-i');
    if (i === null || i === undefined) return;
    var r = rows[Number(i)]; if (!r) return;
    if (t.classList.contains('cmp_sym_fix')) {
      try { fix(r, t.getAttribute('data-from')); } catch (x) { L.warn('symmetry fix failed', x); G.Blockbench.showQuickMessage(x.message || String(x), 3000); }
      rows = check(); render();
    } else if (t.tagName === 'A') M.selectNodes([r.a].concat(r.b ? [r.b] : []));
  }
  function open() {
    rows = check();
    dlg = new G.Dialog({
      id: 'cpmplus_model_symmetry', title: T('symmetry'), width: 620, buttons: ['dialog.close'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('symmetry.tip')) + '</p><p class="cmp_sym_sum"></p><div class="cmp_sym_list"></div>'],
      onConfirm: function () { dlg = null; }, onCancel: function () { dlg = null; }
    });
    dlg.show();
    render();
    dlg.object.addEventListener('click', onClick);
    return dlg;
  }
  var STYLE = 'cmp_sym_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = ['.cmp_sym_sum{font-weight:600;margin:4px 0 6px}', '.cmp_sym_list{max-height:46vh;overflow:auto}',
        '.cmp_sym_row{display:grid;grid-template-columns:1fr auto;gap:2px 10px;padding:6px 4px;border-bottom:1px solid var(--color-border)}',
        '.cmp_sym_row .nm a{cursor:pointer;font-weight:600}', '.cmp_sym_row .what{grid-column:1;opacity:.85}',
        '.cmp_sym_row .bt{grid-column:2;grid-row:1 / span 2;display:flex;gap:6px;align-items:center}', '.cmp_sym_fix{white-space:nowrap}'].join('\n');
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_symmetry', sec: 'check', icon: 'flip', key: 'symmetry', mode: 'edit', spots: [], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } };
  api.check = check; api.fix = fix; api.sideOf = sideOf; api.swapName = swapName; api.open = open; api.dialog = function () { return dlg; }; api.rows = function () { return rows; };
  return api;
})
