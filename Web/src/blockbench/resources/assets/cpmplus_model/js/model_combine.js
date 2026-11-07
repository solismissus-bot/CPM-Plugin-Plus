/*
 * CPM Plugin+ beta34 "CPM+ Modelar" - Juntar outro modelo (CPM issue #321): parts of another model come into this CPM
 * project. Tick them in the other model's tree (an open Blockbench tab, or a .cpmproject / .bbmodel file) and they
 * arrive with everything they had:
 * - their painting, copied pixel by pixel into free skin space (box-UV templates kept when they fit, faces that shared
 *   one area still share one; HD pictures ask for 'Textura em HD'; 'Mais espaco na textura' when the skin is full);
 * - their colour cubes, glow and the other CPM effects (extrude, hidden, no vanilla animation, copy transform remapped
 *   to the copies, CPM data without the ids), pivots and turns;
 * - their animations: only the tracks of the parts brought, named after the other model, every CPM setting kept.
 * Each part lands on the body part of the same name (as it sat on the other model's one), or every part on one chosen
 * body part, staying where it was. The other tab is read without switching to it; a file opens in a temporary tab
 * (Blockbench's / the plugin's own loaders), is read and closed again. One Ctrl+Z takes everything back: parts, pixels,
 * the texture's size, animations. Copies get fresh CPM ids (resetIds).
 * Loaded after the parts library (model_library: the insert steps) and the 3D converter (model_convert: texture steps).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  var SHEETS = ['cape', 'elytra', 'armor1', 'armor2'];
  // the texture may double up to 3 times (64 -> 512) when the pictures need room; no picture for huge selections
  var MAX_GROW = 3, MAX_PICTURE = 1500, MAX_PREVIEW = 3000;
  var DOT = ' ' + String.fromCharCode(183) + ' ';
  function Lb() { return L.core.modules.model_library; }
  function X() { return L.core.modules.model_texture; }
  function TS() { var c = L.core.modules.model_convert; return c && c.texSteps; }

  // =================================================================== small helpers
  function vec(v) { return Array.isArray(v) && v.length >= 3 ? [Number(v[0]) || 0, Number(v[1]) || 0, Number(v[2]) || 0] : [0, 0, 0]; }
  function turned(r) { return !!(r && (r[0] || r[1] || r[2])); }
  function sub(a, b) { return [M.snap(a[0] - b[0]), M.snap(a[1] - b[1]), M.snap(a[2] - b[2])]; }
  function add(a, b) { return [M.snap(a[0] + b[0]), M.snap(a[1] + b[1]), M.snap(a[2] + b[2])]; }
  function b64(u8) { var s = ''; for (var i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000)); return btoa(s); }
  /** a w x h block of an ImageData (outside = see-through) */
  function crop(img, x, y, w, h) {
    var out = new Uint8Array(w * h * 4), s = img.data;
    for (var yy = 0; yy < h; yy++) for (var xx = 0; xx < w; xx++) {
      var sx = x + xx, sy = y + yy; if (sx < 0 || sy < 0 || sx >= img.width || sy >= img.height) continue;
      var i = (yy * w + xx) * 4, j = (sy * img.width + sx) * 4; out[i] = s[j]; out[i + 1] = s[j + 1]; out[i + 2] = s[j + 2]; out[i + 3] = s[j + 3];
    }
    return out;
  }
  function avgOf(px) {
    var r = 0, g = 0, b = 0, n = 0;
    for (var i = 0; i < px.length; i += 4) if (px[i + 3] > 8) { r += px[i]; g += px[i + 1]; b += px[i + 2]; n++; }
    return n ? ((Math.round(r / n) << 16) | (Math.round(g / n) << 8) | Math.round(b / n)) : null;
  }
  function data(s) { try { var d = s ? JSON.parse(s) : null; return d && typeof d === 'object' && !Array.isArray(d) ? d : null; } catch (e) { return null; } }
  function cubesText(n) { return n === 1 ? T('lib.cubes.one') : T('lib.cubes', n); }
  function groupsText(n) { return n === 1 ? T('lib.groups.one') : T('lib.groups', n); }
  function animsText(n) { return n === 1 ? T('lib.anims.one') : T('lib.anims', n); }
  function eachNode(list, fn) { (list || []).forEach(function (n) { fn(n); if (n.g) eachNode(n.kids, fn); }); }
  function toBuffer(c) {
    if (!c) return null;
    if (c instanceof ArrayBuffer || Object.prototype.toString.call(c) === '[object ArrayBuffer]') return c;
    if (ArrayBuffer.isView(c)) return c.buffer.slice(c.byteOffset, c.byteOffset + c.byteLength);
    if (typeof c === 'string') return new TextEncoder().encode(c).buffer;
    return null;
  }

  // =================================================================== this model: body parts and texture sheets
  function topGroups() { return G.Group ? G.Group.all.filter(function (g) { return g.parent === 'root'; }) : []; }
  function targetRoot(name) { if (!name) return null; var t = topGroups(); for (var i = 0; i < t.length; i++) if (t[i].name === name) return t[i]; return null; }
  /** the body part of this model with that name ('left_leg_dup' falls back to 'left_leg') */
  function sameRoot(name) { return targetRoot(name) || (/_dup$/.test(name || '') ? targetRoot(String(name).slice(0, -4)) : null); }
  function fallbackRoot() { return targetRoot('body') || topGroups()[0] || 'root'; }
  // lang: lib.root.{head|body|right_arm|left_arm|right_leg|left_leg|none}
  function rootLabel(name) { return ROOTS.indexOf(name) >= 0 ? T('lib.root.' + name) : name ? String(name) : T('lib.root.none'); }
  /** the CPM texture sheet the parts of a body part use (BlockbenchExport / RootGroups): cape, elytra, armour, skin */
  function sheetOf(root) {
    var n = String(root || '').toLowerCase().replace(/_dup$/, '');
    if (n === 'cape') return 'cape';
    if (/^elytra/.test(n)) return 'elytra';
    if (/^armor_(left_leg|right_leg|leggings_body)$/.test(n)) return 'armor2';
    if (/^armor_/.test(n)) return 'armor1';
    return 'skin';
  }
  function texName(t) { return String((t && t.name) || '').toLowerCase().replace(/\.png$/, ''); }
  /** the texture the export uses for a sheet (RootUtil.findSkinTexture / sheetOfName) */
  function sheetTexture(sheet) {
    var all = (G.Texture && G.Texture.all) || [], i;
    if (sheet !== 'skin') { for (i = 0; i < all.length; i++) if (texName(all[i]) === sheet) return all[i]; return null; }
    for (i = 0; i < all.length; i++) if (texName(all[i]) === 'skin') return all[i];
    if (all.length === 1) return all[0];
    for (i = 0; i < all.length; i++) if (SHEETS.indexOf(texName(all[i])) < 0) return all[i];
    return null;
  }
  function defaultTexture() { return (G.Texture && (G.Texture.getDefault ? G.Texture.getDefault() : (G.Texture.selected || G.Texture.all[0]))) || null; }

  // =================================================================== reading the other model (live objects, never switched to)
  /** its textures as pixels: [{uuid, name, img (ImageData | null), sx, sy (pixels per UV unit)}] */
  function texsOf(p) {
    var list = (p.textures || []).map(function (t) {
      var uvW = Number(t.uv_width) || Number(p.texture_width) || t.width || 16, uvH = Number(t.uv_height) || Number(p.texture_height) || t.height || 16, img = null;
      try { var cv = t.canvas; if (cv && cv.width && cv.height) img = cv.getContext('2d').getImageData(0, 0, cv.width, cv.height); } catch (e) { img = null; }
      var h = img ? (Number(t.display_height) || img.height) : 0;
      return { uuid: String(t.uuid || ''), name: String(t.name || ''), img: img, sx: img ? img.width / uvW : 1, sy: img ? h / uvH : 1, isDef: !!t.use_as_default };
    });
    list.single = !!(p.format && p.format.single_texture);
    list.def = list.filter(function (t) { return t.isDef; })[0] || list[0] || null;
    return list;
  }
  function texOf(texs, ref) {
    if (ref === null || ref === undefined) return null;
    // a single-texture format (a skin, a Bedrock entity): every face that is not switched off shows its one texture
    if (texs.single) return texs.def;
    if (ref === false) return null;
    if (ref && typeof ref === 'object' && ref.uuid) ref = ref.uuid;
    if (typeof ref === 'string') { for (var i = 0; i < texs.length; i++) if (texs[i].uuid === ref) return texs[i]; return null; }
    if (typeof ref === 'number') return texs[ref] || null;
    return texs[0] || null;
  }
  function rel(v, h) { return sub(vec(v), h.anchor); }
  function rot(r) { return turned(r) ? [M.snap(Number(r[0]) || 0), M.snap(Number(r[1]) || 0), M.snap(Number(r[2]) || 0)] : undefined; }
  /**
   * the painting of a cube as a library 'tex' (every face's pixels, its place in the box template or its own rect, flips,
   * turn; 'key' = the source area, so faces that shared one area share one again) -> {tex, avg, bk} or null (no picture)
   */
  function paintOf(c, texs) {
    var box = !!c.box_uv, faces = [], fs = {}, bu = Infinity, bv = Infinity, first = null, one = true, big = null;
    FACES.forEach(function (k) {
      var f = c.faces && c.faces[k];
      if (!f || f.texture === null || f.texture === undefined) { fs[k] = -1; return; }
      var t = texOf(texs, f.texture);
      if (!t || !t.img || !Array.isArray(f.uv)) { fs[k] = 0; return; }
      var x1 = Math.min(f.uv[0], f.uv[2]), x2 = Math.max(f.uv[0], f.uv[2]), y1 = Math.min(f.uv[1], f.uv[3]), y2 = Math.max(f.uv[1], f.uv[3]);
      fs[k] = 1;
      if (x2 - x1 <= 1e-6 || y2 - y1 <= 1e-6) {
        if (box) return;// (the box template keeps it)
        // a face with no UV area shows the one pixel under its corner: that colour (see-through = the face is off)
        var px = crop(t.img, Math.min(t.img.width - 1, Math.max(0, Math.floor(x1 * t.sx))), Math.min(t.img.height - 1, Math.max(0, Math.floor(y1 * t.sy))), 1, 1);
        if (px[3] < 8) { fs[k] = -1; return; }
        faces.push({ face: k, rel: [0, 0, 1, 1], flip: [false, false], rot: 0, w: 1, h: 1, px: b64(px), key: 'z:' + t.uuid + ':' + Array.prototype.join.call(px, ',') });
        return;
      }
      var X0 = Math.round(x1 * t.sx), Y0 = Math.round(y1 * t.sy), W = Math.max(1, Math.round((x2 - x1) * t.sx)), H = Math.max(1, Math.round((y2 - y1) * t.sy)), block = crop(t.img, X0, Y0, W, H);
      if (!big || W * H > big.length / 4) big = block;
      if (!first) first = t; else if (t !== first) one = false;
      bu = Math.min(bu, x1); bv = Math.min(bv, y1);
      faces.push({ face: k, r: [x1, y1, x2, y2], flip: [f.uv[0] > f.uv[2], f.uv[1] > f.uv[3]], rot: box ? 0 : ([90, 180, 270].indexOf(Number(f.rotation)) >= 0 ? Number(f.rotation) : 0), w: W, h: H, px: b64(block),
        key: t.uuid + ':' + [x1, y1, x2, y2].join(','), hd: t.sx > 1.0001 || t.sy > 1.0001, t: t });
    });
    if (!faces.some(function (f) { return f.r; })) return faces.length ? { tex: { box: false, off: [0, 0], fs: fs, scale: [1, 1], faces: faces }, avg: null, bk: null, dens: 1 } : null;
    // a box template is one area of one texture
    if (box && !one) box = false;
    if (box && Array.isArray(c.uv_offset)) { bu = Math.min(bu, c.uv_offset[0]); bv = Math.min(bv, c.uv_offset[1]); }
    var dens = 1;
    var out = faces.map(function (f) {
      if (!f.r) return f;
      dens = Math.max(dens, f.t.sx, f.t.sy);
      var o = { face: f.face, rel: box ? [f.r[0] - bu, f.r[1] - bv, f.r[2] - bu, f.r[3] - bv] : [0, 0, f.r[2] - f.r[0], f.r[3] - f.r[1]], flip: f.flip, rot: f.rot, w: f.w, h: f.h, px: f.px, key: f.key };
      if (f.hd) o.hd = true;
      return o;
    });
    var off = box && Array.isArray(c.uv_offset) ? [c.uv_offset[0] - bu, c.uv_offset[1] - bv] : [0, 0];
    // box-UV cubes that used the very same template (same texture, place, size, mirror) share one template again
    var bk = box ? [first.uuid, c.uv_offset && c.uv_offset.join(','), sub(vec(c.to), vec(c.from)).join(','), c.mirror_uv ? 1 : 0, c.inflate || 0, JSON.stringify(fs)].join('|') : null;
    return { tex: { box: box, off: off, fs: fs, scale: [first.sx, first.sy], faces: out }, avg: big ? avgOf(big) : null, bk: bk, dens: dens };
  }
  function cubeSpec(c, h, texs) {
    var s = { c: 1, n: String(c.name || 'cube'), f: rel(c.from, h), t: rel(c.to, h), o: rel(c.origin || c.from, h) };
    var r = rot(c.rotation); if (r) s.r = r;
    if (c.inflate) s.inf = Number(c.inflate) || 0;
    if (c.visibility === false) s.vis = false;
    if (c.mirror_uv) s.mirror_uv = true;
    if (c.cpm_glow) s.glow = true;
    if (c.cpm_extrude) s.extrude = true;
    var d = data(c.cpm_data); if (d) { delete d.sid; delete d.vanilla; if (Object.keys(d).length) s.cpm = d; }
    var rc = typeof c.cpm_recolor === 'number' && c.cpm_recolor !== -1 ? c.cpm_recolor & 0xFFFFFF : null, p = paintOf(c, texs);
    if (p) {
      s.tex = p.tex; if (p.bk) s._bk = p.bk; s._dens = p.dens;
      if (p.avg !== null) s.alt = p.avg;
      if (rc !== null) s.recol = rc;
    } else s.col = rc !== null ? rc : 0x9e9e9e;
    if (c.export === false) s._noexp = true;
    return s;
  }
  function groupSpec(g, h, kids) {
    var s = { g: 1, n: String(g.name || 'group'), o: rel(g.origin, h), aid: String(g.uuid || ''), kids: kids };
    var r = rot(g.rotation); if (r) s.r = r;
    if (g.visibility === false) s.vis = false;
    if (g.cpm_hidden) s.hidden = true;
    var x = {};
    if (g.cpm_dva) x.dva = true;
    if (typeof g.cpm_copy_transform === 'string' && g.cpm_copy_transform) x.ct = g.cpm_copy_transform;
    // CPM data a copy may keep: never the ids, the name of a renamed body part or a linked-copy mark of that model
    var d = data(g.cpm_data); if (d) { delete d.sid; delete d.rootName; delete d.linked; if (Object.keys(d).length) x.data = d; }
    if (g.export === false) x.noexp = true;
    if (Object.keys(x).length) s._x = x;
    return s;
  }
  /** its animations: [{name, type, additive, loop, length, snapping, layerDefault, tracks: [{aid, keys, skip}], props}] */
  function animsOf(p, cpm) {
    var keysOf = Lb().keysOf;
    return (p.animations || []).map(function (a) {
      var tracks = [];
      Object.keys(a.animators || {}).forEach(function (u) {
        var an = a.animators[u]; if (!an || !Array.isArray(an.keyframes) || !an.keyframes.length) return;
        var keys = keysOf(an.keyframes);
        if (keys.length) tracks.push({ aid: String(u), keys: keys, skip: an.keyframes.length - keys.length });
      });
      var nm = String(a.name || 'anim').slice(0, 64), props = {};
      // every CPM setting of the animation (a CPM+ reaction / form marker points at that model's parts: left out)
      if (cpm) Object.keys(a).forEach(function (k) {
        if (!/^cpm_/.test(k) || k === 'cpm_plus_rule') return;
        var v = a[k]; if (v === null || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') props[k] = v;
      });
      return { name: nm, type: cpm && typeof a.cpm_type === 'string' && a.cpm_type ? a.cpm_type : Lb().guessAnimType(nm), additive: cpm ? a.cpm_additive !== false : true,
        loop: a.loop === 'once' || a.loop === 'hold' ? a.loop : 'loop', length: Number(a.length) || 1, snapping: Number(a.snapping) || 20,
        layerDefault: cpm && typeof a.cpm_layerDefault === 'number' ? a.cpm_layerDefault : undefined, tracks: tracks, props: props };
    });
  }
  /**
   * another project (a tab, or the temporary tab of a file) as plain data: {key, kind, label, cpm, rows (the tree, in
   * outliner order: {id, kind 'root' | 'g' | 'c', name, depth, parent, kids, home, spec, base, cubes, anim}), anims,
   * rootNames, skipped}. A row's spec is in library form, relative to its home's anchor: the pivot of the body part it
   * sits on (CPM: the top groups are body parts; other formats: a top group named like one), or the model's origin.
   */
  function readProject(p, label, kind) {
    var cpm = !!(p.format && p.format.id === 'cpm'), texs = texsOf(p), animated = {};
    var src = { key: kind === 'tab' ? 'tab:' + p.uuid : 'file', kind: kind, project: kind === 'tab' ? p : null, label: String(label || 'modelo'), cpm: cpm, rows: [], byId: {}, anims: animsOf(p, cpm),
      rootNames: {}, skipped: { meshes: 0 }, painted: 0, cubes: 0, dens: 1 };
    src.anims.forEach(function (a) { a.tracks.forEach(function (t) { animated[t.aid] = 1; }); });
    function row(n, kindOf, depth, parent, h) {
      // (a body part's own keys never come along: it is not marked as animated)
      var r = { id: String(n.uuid), kind: kindOf, name: String(n.name || (kindOf === 'c' ? 'cube' : 'group')), depth: depth, parent: parent, kids: [], home: h, spec: null, base: false, cubes: 0, anim: kindOf === 'g' && !!animated[n.uuid] };
      src.rows.push(r); src.byId[r.id] = r;
      if (parent) parent.kids.push(r);
      return r;
    }
    function walk(list, depth, parent, h) {
      var specs = [];
      (list || []).forEach(function (n) {
        if (M.isGroup(n)) {
          var r = row(n, 'g', depth, parent, h);
          r.spec = groupSpec(n, h, walk(n.children, depth + 1, r, h));
          specs.push(r.spec);
        } else if (M.isCube(n)) {
          var c = row(n, 'c', depth, parent, h);
          // the player's own base cube of a body part never comes along (this model has its own)
          if (cpm && M.isBase(n)) { c.base = true; return; }
          c.spec = cubeSpec(n, h, texs); specs.push(c.spec);
          src.cubes++; if (c.spec.tex) { src.painted++; src.dens = Math.max(src.dens, c.spec._dens || 1); }
          for (var q = parent; q; q = q.parent) q.cubes++;
        } else if (n && n.type === 'mesh') src.skipped.meshes++;
      });
      return specs;
    }
    (p.outliner || []).forEach(function (n) {
      if (M.isGroup(n)) src.rootNames[String(n.uuid)] = String(n.name || '');
      if (cpm && M.isGroup(n)) {
        // a body part of a CPM model: what was added to it can come along, never the body part itself
        var h = { key: 'r:' + n.uuid, root: String(n.name || ''), anchor: vec(n.origin), rot: turned(n.rotation) ? vec(n.rotation) : null, name: String(n.name || '') };
        var r = row(n, 'root', 0, null, h);
        walk(n.children, 1, r, h);
        return;
      }
      var mapped = M.isGroup(n) ? Lb().rootFromName(n.name) : null;
      var h2 = mapped ? { key: 'g:' + n.uuid, root: mapped, anchor: vec(n.origin), rot: null, name: String(n.name || '') } : { key: '', root: '', anchor: [0, 0, 0], rot: null, name: '' };
      walk([n], 0, null, h2);
    });
    return src;
  }
  function tabName(p) { return String(p.name || p.geometry_name || T('combine.noname')); }
  function hasParts(p) { return (p && p.elements || []).some(function (e) { return M.isCube(e); }); }
  /** the other open tabs with cubes */
  function sourceTabs() { return (G.ModelProject && G.ModelProject.all ? G.ModelProject.all : []).filter(function (p) { return p && p !== G.Project && hasParts(p); }); }
  function fromTab(p) { return readProject(p, tabName(p), 'tab'); }

  // =================================================================== reading a file: a temporary tab, read, closed again
  /** waits for the tab a loader opens (the CPM importer asks whether to convert the animations: yes, they are needed) */
  function waitTab(before, timeout) {
    return new Promise(function (resolve, reject) {
      var t0 = Date.now(), last = null, stable = 0;
      (function poll() {
        var d = G.Dialog && G.Dialog.open;
        if (d && d.id === 'ui_confirm' && /import animations/i.test(String(d.title || ''))) { try { d.confirm(); } catch (e) { } }
        if (d && d.id === 'cpm_error') { reject(new Error(T('combine.bad'))); return; }
        var all = (G.ModelProject && G.ModelProject.all) || [], p = null;
        for (var i = 0; i < all.length; i++) if (before.indexOf(all[i]) < 0) { p = all[i]; break; }
        if (p) {
          // ready: every texture has its pixels and the tab stopped changing for two looks
          var ready = (p.textures || []).every(function (t) { return t.error || (t.canvas && t.canvas.width > 0); });
          var sig = ready ? (p.elements || []).length + ':' + (p.groups || []).length + ':' + (p.animations || []).length : null;
          if (sig !== null && sig === last) stable++; else { stable = 0; last = sig; }
          if (stable >= 2) { resolve({ project: p }); return; }
        }
        if (Date.now() - t0 > timeout) { reject(new Error(T('combine.bad'))); return; }
        setTimeout(poll, 100);
      })();
    });
  }
  function backTo(target) {
    if (target && G.ModelProject && G.ModelProject.all.indexOf(target) >= 0 && G.Project !== target) { try { target.select(); } catch (e) { L.warn('could not go back to the model', e); } }
  }
  /** a picked file ({name, path, content}) -> Promise of a source */
  function fromFile(f) {
    var name = String((f && (f.name || f.path)) || 'modelo').split(/[\\/]/).pop(), label = name.replace(/\.[^.]+$/, '') || 'modelo';
    var target = G.Project, before = ((G.ModelProject && G.ModelProject.all) || []).slice(), path = (f && f.path) || name, cpmFile = /\.cpmproject$/i.test(name);
    try {
      if (cpmFile) {
        var ab = toBuffer(f && f.content);
        if (!ab || !G.Codecs || !G.Codecs.cpmproject) throw new Error(T('combine.bad'));
        G.Codecs.cpmproject.load(ab, { name: name, path: path });
      } else {
        var c = f && f.content, text = typeof c === 'string' ? c : new TextDecoder().decode(toBuffer(c) || new ArrayBuffer(0)), j = null;
        try { j = JSON.parse(text); } catch (e) { j = null; }
        if (!j || !j.meta || typeof j.meta !== 'object' || !Array.isArray(j.elements) || !Array.isArray(j.outliner) || !G.Codecs || !G.Codecs.project) throw new Error(T('combine.bad'));
        // Blockbench's own .bbmodel loader, without a path: no 'recent models' entry for a file only read here
        G.Codecs.project.load(j, { name: name, path: '' });
      }
    } catch (e) { backTo(target); return Promise.reject(e); }
    try { G.Blockbench.showQuickMessage(T('combine.loading', name), 1500); } catch (e) { }
    return waitTab(before, 180000).then(function (r) {
      var src = null, err = null;
      try { src = readProject(r.project, label, 'file'); } catch (e) { err = e; }
      // the temporary tab goes away again (closed without asking: nothing in it was changed)
      var closing = null;
      try { closing = r.project.close(true); } catch (e) { closing = null; }
      return Promise.resolve(closing).catch(function () { }).then(function () {
        backTo(target);
        if (err) throw err;
        if (G.Project !== target) throw new Error(T('combine.moved'));
        return src;
      });
    }, function (e) { backTo(target); throw e; });
  }

  // =================================================================== what goes where (the plan for the ticked rows)
  /** the ticked rows that come along (a ticked group brings what is inside it; a body part brings its own parts) */
  function pickedRows(c) {
    var out = [];
    c.src.rows.forEach(function (r) {
      if (!c.ticked[r.id] || r.base) return;
      for (var q = r.parent; q; q = q.parent) if (c.ticked[q.id]) return;
      if (r.kind === 'root') r.kids.forEach(function (k) { if (!k.base) out.push(k); });
      else out.push(r);
    });
    return out;
  }
  function shift(list, d) {
    return (list || []).map(function (n) {
      var o = Object.assign({}, n);
      if (n.c) { o.f = add(n.f, d); o.t = add(n.t, d); o.o = add(n.o || n.f, d); }
      else if (n.g) { o.o = add(n.o || [0, 0, 0], d); o.kids = shift(n.kids, d); }
      return o;
    });
  }
  /** painted cubes with the very same box template share one: [unique specs] + the unique index of each spec */
  function dedupe(specs) {
    var list = [], index = [], seen = {};
    specs.forEach(function (n) {
      var k = n.tex && n.tex.box && n._bk ? n._bk : null;
      if (k !== null && seen[k] !== undefined) { index.push(seen[k]); return; }
      if (k !== null) seen[k] = list.length;
      index.push(list.length); list.push(n);
    });
    return { list: list, index: index };
  }
  /** the skin area (UV units) the pictures take (shared areas once) */
  function areaOf(specs) {
    var d = dedupe(specs), keys = {}, a = 0;
    d.list.forEach(function (n) {
      n.tex.faces.forEach(function (f) {
        var w = f.rel[2] - f.rel[0], h = f.rel[3] - f.rel[1];
        if (!n.tex.box && f.key) { var k = f.key + '|' + w + 'x' + h; if (keys[k]) return; keys[k] = 1; }
        a += Math.ceil(w - 1e-6) * Math.ceil(h - 1e-6);
      });
    });
    return a;
  }
  /**
   * the plan for the ticked rows and the window's choices f = {where, anims, paint, hd} -> {sets: [{home, parts (built
   * as they are), parent, anchor, tex (this model's texture for its pictures), painted}], item (name + animations for the
   * library's makeAnims, sets for the picture), cubes, groups, area, hd (pixels per UV unit the pictures were drawn
   * with), wantsHD, notes}
   */
  function planFor(c, f) {
    f = f || {};
    var src = c.src, rows = pickedRows(c), where = f.where && f.where !== 'auto' ? f.where : null, chosen = where ? targetRoot(where) : null;
    var byKey = {}, sets = [], aids = {}, cubes = 0, groups = 0, notes = [];
    rows.forEach(function (r) {
      var k = r.home.key;
      if (!byKey[k]) { byKey[k] = { home: r.home, parts: [] }; sets.push(byKey[k]); }
      byKey[k].parts.push(r.spec);
    });
    var hd = 1, missing = {};
    sets.forEach(function (s) {
      var h = s.home, same = h.root ? sameRoot(h.root) : null, parent = chosen || same || fallbackRoot();
      // each part stays where it sat on the other model's body part of that name (no such part here: where it was)
      s.anchor = same ? vec(same.origin) : h.anchor.slice();
      s.parent = parent;
      var build = s.parts;
      // a turned body part (cape, elytra) that is not this one: a group with its turn keeps the parts as they were
      if (h.rot && parent !== same) build = [{ g: 1, n: h.name || 'part', o: [0, 0, 0], r: h.rot.slice(), kids: build }];
      s.build = build;
      if (!chosen && !same && h.root) missing[rootLabel(h.root)] = parent !== 'root' ? rootLabel(parent.name) : rootLabel('');
      var destRoot = parent !== 'root' ? parent.name : '';
      s.tex = sheetTexture(sheetOf(destRoot));
      s.painted = [];
      eachNode(build, function (n) {
        if (n.c) { cubes++; if (n.tex) { s.painted.push(n); hd = Math.max(hd, n._dens || 1); } }
        else if (n.g) { groups++; if (n.aid) aids[n.aid] = 1; }
      });
    });
    Object.keys(missing).forEach(function (k) { notes.push(T('combine.goes.missing', k, missing[k])); });
    // the animations of the parts brought (only their tracks)
    var anims = [], skipKeys = 0;
    if (f.anims !== false) src.anims.forEach(function (a) {
      var tracks = a.tracks.filter(function (t) { return aids[t.aid]; });
      if (!tracks.length) return;
      tracks.forEach(function (t) { skipKeys += t.skip; });
      anims.push({ name: a.name, type: a.type, additive: a.additive, loop: a.loop, length: a.length, snapping: a.snapping, layerDefault: a.layerDefault, tracks: tracks, props: a.props });
    });
    var colours = f.paint === false, byTex = new Map(), area = 0, noTex = false;
    sets.forEach(function (s) {
      if (!s.painted.length || colours) return;
      if (!s.tex) { noTex = true; return; }
      var e = byTex.get(s.tex); if (!e) { e = { tex: s.tex, specs: [] }; byTex.set(s.tex, e); }
      s.painted.forEach(function (n) { e.specs.push(n); });
    });
    byTex.forEach(function (e) { area += areaOf(e.specs); });
    hd = Math.round(hd);
    var wantsHD = false;
    byTex.forEach(function (e) { if (hd > 1 && X().density(e.tex) < hd - 1e-6) wantsHD = true; });
    var item = { v: 1, id: 'combine', user: true, name: src.label, cat: 'other', sets: sets.map(function (s) { return { root: s.home.root || '', anchor: s.home.anchor.slice(), parts: s.parts }; }), anims: anims };
    return { sets: sets, item: item, rows: rows.length, cubes: cubes, groups: groups, area: area, hd: hd, wantsHD: wantsHD, byTex: byTex, noTex: noTex, colours: colours, chosen: chosen, skipKeys: skipKeys, notes: notes };
  }
  /** whether the pictures fit on each texture as it is: '' | 'no' (a dry run of the library's planner) */
  function roomOf(p) {
    var res = '';
    p.byTex.forEach(function (e) {
      if (res) return;
      try { Lb().planPaint(dedupe(e.specs).list.map(function (n) { return n.tex; }), e.tex); }
      catch (x) { res = (x && x.message) === T('lib.noroom') ? 'no' : 'err'; }
    });
    return res;
  }

  // =================================================================== putting it in: ONE undo step for everything
  var steps = new Map(), lastResult = null, lastError = null;
  /** pairs the specs with the nodes the library's build made (same walk: a group before what is inside it) */
  function pairUp(list, out, uuids, pairs) {
    var gi = 0, ci = 0;
    (function walk(l) {
      l.forEach(function (n) {
        if (n.g) { var g = out.groups[gi++]; pairs.push([n, g]); if (n.aid && g) uuids[n.aid] = g; walk(n.kids || []); }
        else if (n.c) pairs.push([n, out.cubes[ci++]]);
      });
    })(list);
  }
  /** the CPM settings the library's build does not set: no vanilla animation, CPM data of groups, not exported */
  function extras(pairs, cpm) {
    pairs.forEach(function (pr) {
      var n = pr[0], node = pr[1]; if (!node) return;
      if (n.g) {
        var x = n._x || {};
        if (cpm && x.dva) node.cpm_dva = true;
        if (cpm && x.data) node.cpm_data = JSON.stringify(x.data);
        if (x.noexp) node.export = false;
      } else if (n._noexp) node.export = false;
    });
  }
  /** copy transforms point at the copies (or at this model's body part of that name; nothing to copy from: left out) */
  function remapCopies(pairs, uuids, src) {
    pairs.forEach(function (pr) {
      var n = pr[0], g = pr[1]; if (!n.g || !g || !n._x || !n._x.ct) return;
      var j = data(n._x.ct); if (!j) return;
      var u = String(j.uuid || ''), to = uuids[u] ? uuids[u].uuid : null;
      if (!to && src.rootNames[u]) { var r = sameRoot(src.rootNames[u]); if (r) to = r.uuid; }
      if (!to) return;
      j.uuid = to;
      g.cpm_copy_transform = JSON.stringify(j);
    });
  }
  /** every CPM setting of the source animation on the new one (makeAnims already set type, additive, layer default) */
  function copyAnimProps(list, made) {
    if (made.length !== list.length) return;
    made.forEach(function (a, i) {
      var props = list[i].props || {};
      Object.keys(props).forEach(function (k) { if (k === 'cpm_type' || k === 'cpm_additive' || k === 'cpm_layerDefault') return; try { a[k] = props[k]; } catch (e) { } });
    });
  }
  /**
   * puts the plan in the model: o = {grow (no room: the texture doubles, up to 3 times), colours (painted cubes as colour
   * cubes), hd (the texture gets the pixels the pictures were drawn with), anims} -> {tops, cubes, groups, anims, grew, hd}
   */
  function apply(c, p, o) {
    o = o || {};
    var lib = Lb(), tx = X(), ts = TS(), cpm = M.isCpm(), src = c.src;
    if (!ts) throw new Error('model_convert texture steps missing');
    var texList = o.colours ? [] : Array.from(p.byTex.values()).map(function (e) { return { tex: e.tex, specs: e.specs }; });
    var withAnims = o.anims !== false && p.item.anims.length > 0 && !!G.Animation;
    var made = { cubes: [], groups: [], tops: [] }, anims = [], grew = 0, hdDone = false;
    function aspects(cubes, groups, an) {
      var a = { outliner: true, elements: cubes, groups: groups };
      texList.forEach(function (e) {
        var pa = tx.pixelAspects(e.tex);
        Object.keys(pa).forEach(function (k) { if (Array.isArray(pa[k])) a[k] = (a[k] || []).concat(pa[k]); else a[k] = pa[k]; });
      });
      if (withAnims) a.animations = an;
      return a;
    }
    G.Undo.initEdit(aspects([], [], []));
    try {
      texList.forEach(function (e) {
        var u = dedupe(e.specs), list = u.list.map(function (n) { return n.tex; }), up = null;
        for (var tries = 0; ; tries++) {
          try { up = lib.planPaint(list, e.tex); break; }
          catch (x) {
            if (!o.grow || tries >= MAX_GROW || (x && x.message) !== T('lib.noroom')) { if (o.grow && (x && x.message) === T('lib.noroom')) throw new Error(T('combine.noroom.max')); throw x; }
            if (!e.snap) e.snap = ts.snapshot(e.tex);
            ts.grow(e.tex, 2); grew++;
          }
        }
        e.plans = e.specs.map(function (n, i) { return up[u.index[i]]; });
        // HD pictures: the texture first gets the pixels they were drawn with (UV units stay: the plan stays right)
        if (o.hd && p.hd > 1 && tx.density(e.tex) < p.hd - 1e-6) { if (!e.snap) e.snap = ts.snapshot(e.tex); ts.hd(e.tex, p.hd); hdDone = true; }
      });
      var byAid = {}, uuids = {}, pairs = [], def = defaultTexture();
      p.sets.forEach(function (s) {
        var out = { cubes: [], groups: [], tex: [], byAid: {}, colours: !!o.colours || !s.tex };
        lib.build(s.build, s.parent, s.anchor, out);
        pairUp(s.build, out, uuids, pairs);
        out.groups.forEach(function (g) { if (g.parent === s.parent) made.tops.push(g); });
        out.cubes.forEach(function (k) { if (k.parent === s.parent) made.tops.push(k); });
        made.cubes = made.cubes.concat(out.cubes); made.groups = made.groups.concat(out.groups);
        Object.keys(out.byAid).forEach(function (k) { byAid[k] = out.byAid[k]; });
        // painted cubes show this body part's texture sheet
        if (s.tex && s.tex !== def) out.tex.forEach(function (t) { try { t.cube.applyTexture(s.tex, true); } catch (x) { } });
        s.out = out;
      });
      extras(pairs, cpm);
      remapCopies(pairs, uuids, src);
      made.tops.forEach(function (n) { M.forgetIds(n); });
      texList.forEach(function (e) {
        var entries = [];
        p.sets.forEach(function (s) { if (s.tex === e.tex && s.out && !s.out.colours) s.out.tex.forEach(function (t) { entries.push(t); }); });
        e.px = lib.paint(entries, e.plans, e.tex);
      });
      if (withAnims) { anims = lib.makeAnims(p.item, byAid, false); copyAnimProps(p.item.anims, anims); }
    } catch (x) {
      try { G.Undo.cancelEdit(); } catch (y) { }
      texList.forEach(function (e) {
        try { if (e.snap) ts.restore(e.tex, e.snap); else if (e.px && e.px.before.length) tx.putBlocks(e.tex, e.px.before); } catch (y) { }
      });
      throw x;
    }
    G.Undo.finishEdit(T('combine'), aspects(made.cubes, made.groups, anims));
    // the texture with this step: its whole canvas when its size changed, else the blocks written (Ctrl+Z / Ctrl+Y)
    var H = G.Undo.history, entry = H && H[H.length - 1], rec = [];
    texList.forEach(function (e) {
      if (e.snap) rec.push({ tex: e.tex, whole: true, before: e.snap, after: ts.snapshot(e.tex) });
      else if (e.px && e.px.before.length) rec.push({ tex: e.tex, whole: false, before: e.px.before, after: e.px.after });
    });
    if (entry && rec.length) steps.set(entry, rec);
    M.refresh();
    texList.forEach(function (e) {
      if (e.snap) { try { ts.refresh(e.tex); } catch (x) { } }
      try { if (typeof e.tex.updateLayerChanges === 'function') e.tex.updateLayerChanges(true); } catch (x) { }
    });
    M.selectNodes(made.tops);
    lastResult = { tops: made.tops, cubes: made.cubes, groups: made.groups, anims: anims, grew: grew, hd: hdDone, colours: !!o.colours };
    return lastResult;
  }
  function onUndo(d) {
    var s = d && d.entry && steps.get(d.entry); if (!s) return;
    s.slice().reverse().forEach(function (x) { try { if (x.whole) TS().restore(x.tex, x.before); else X().putBlocks(x.tex, x.before); } catch (e) { L.warn('could not put the texture back', e); } });
  }
  function onRedo(d) {
    var s = d && d.entry && steps.get(d.entry); if (!s) return;
    s.forEach(function (x) { try { if (x.whole) TS().restore(x.tex, x.after); else X().putBlocks(x.tex, x.after); } catch (e) { L.warn('could not redo the texture', e); } });
  }

  // =================================================================== the window
  var dlg = null, cur = null, busy = false, timer = null;
  function whereOptions() {
    var o = { auto: T('combine.where.auto') }, seen = {};
    ROOTS.forEach(function (r) { if (targetRoot(r)) { o[r] = rootLabel(r); seen[r] = 1; } });
    topGroups().forEach(function (g) { if (!seen[g.name] && g.name !== 'auto') { o[g.name] = String(g.name); seen[g.name] = 1; } });
    return o;
  }
  function sourceOptions(src) {
    var o = {};
    sourceTabs().forEach(function (p) { o['tab:' + p.uuid] = T('combine.src.tab', tabName(p)); });
    if (src.kind === 'file') o.file = T('combine.src.file', src.label);
    o.pick = T('combine.src.pick');
    return o;
  }
  function rowInfo(r) {
    if (r.kind === 'root' || r.kind === 'g') return r.cubes ? cubesText(r.cubes) : '';
    if (r.base) return T('combine.base');
    var s = r.spec || {};
    // lang: combine.kind.{colour|painted}
    return T('combine.kind.' + (s.tex ? 'painted' : 'colour')) + (s.glow ? DOT + T('combine.glow') : '');
  }
  function treeHtml(src) {
    var rows = src.rows.filter(function (r) { return r.kind !== 'root' || r.kids.some(function (k) { return !k.base; }); });
    if (!rows.length) return '<div class="cmp_cb_empty">' + L.esc(T('combine.empty')) + '</div>';
    return rows.map(function (r) {
      var icon = r.kind === 'root' ? 'accessibility' : r.kind === 'g' ? 'folder' : r.spec && r.spec.tex ? 'image' : 'crop_square';
      var name = r.kind === 'root' ? rootLabel(r.name) : r.name;
      return '<label class="cmp_cb_row' + (r.kind === 'root' ? ' root' : '') + (r.base ? ' base' : '') + '" style="padding-left:' + (6 + r.depth * 16) + 'px" data-id="' + L.esc(r.id) + '">' +
        '<input type="checkbox" data-id="' + L.esc(r.id) + '"' + (r.base ? ' disabled' : '') + '>' +
        '<i class="material-icons">' + icon + '</i><span class="nm">' + L.esc(name) + '</span>' +
        (r.anim ? '<i class="material-icons cmp_cb_anim" title="' + L.esc(T('combine.anim')) + '">animation</i>' : '') +
        '<span class="ct">' + L.esc(rowInfo(r)) + '</span></label>';
    }).join('');
  }
  function boxHtml(src) {
    return '<div class="cmp_cb_box"><div class="cmp_cb_main"><div class="cmp_cb_bar"><button type="button" data-act="all">' + L.esc(T('combine.all')) + '</button>' +
      '<button type="button" data-act="none">' + L.esc(T('combine.none')) + '</button></div>' +
      '<div class="cmp_cb_list">' + treeHtml(src) + '</div></div>' +
      '<div class="cmp_cb_side"><img class="cmp_cb_pic" alt="" draggable="false"><div class="cmp_cb_sum"></div></div></div>' +
      '<p class="cmp_cb_where"></p><p class="cmp_cb_room"></p>';
  }
  /** the checkboxes from c.ticked: what is inside a ticked group shows ticked and greyed (it comes along) */
  function syncTree(c) {
    var root = dlg && dlg.object; if (!root) return;
    root.querySelectorAll('.cmp_cb_list input[data-id]').forEach(function (b) {
      var r = c.src.byId[b.getAttribute('data-id')]; if (!r) return;
      var up = false; for (var q = r.parent; q; q = q.parent) if (c.ticked[q.id]) { up = true; break; }
      b.checked = !r.base && (up || !!c.ticked[r.id]);
      b.disabled = r.base || up;
      var row = b.closest('.cmp_cb_row'); if (row) row.classList.toggle('with', up && !r.base);
    });
  }
  function tick(c, id, on) {
    var r = c.src.byId[id]; if (!r || r.base) return;
    if (on) {
      c.ticked[id] = true;
      // its inside comes along: no separate ticks there
      (function clear(k) { k.kids.forEach(function (x) { delete c.ticked[x.id]; clear(x); }); })(r);
    } else delete c.ticked[id];
  }
  /** ticked at the start: what is selected in the other tab */
  function preTicked(src) {
    var p = src.project, out = {}; if (!p) return out;
    var sel = [];
    (p.selected_groups || []).forEach(function (g) { if (g && g.uuid) sel.push(String(g.uuid)); });
    (p.selected_elements || []).forEach(function (e) { if (e && e.uuid) sel.push(String(e.uuid)); });
    sel.forEach(function (id) { var r = src.byId[id]; if (r && !r.base) out[id] = true; });
    var c = { src: src, ticked: {} };
    Object.keys(out).forEach(function (id) { tick(c, id, true); });
    // a row inside another ticked one is already covered
    Object.keys(c.ticked).forEach(function (id) { for (var q = src.byId[id].parent; q; q = q.parent) if (c.ticked[q.id]) { delete c.ticked[id]; return; } });
    return c.ticked;
  }
  function picture(p) {
    var img = dlg && dlg.object && dlg.object.querySelector('.cmp_cb_pic'); if (!img) return;
    if (!p.cubes || p.cubes > MAX_PICTURE) { img.style.visibility = 'hidden'; return; }
    try { img.src = Lb().thumbnail(p.item); img.style.visibility = ''; } catch (e) { img.style.visibility = 'hidden'; L.warn('combine picture failed', e); }
  }
  function update(f) {
    var c = cur; if (!dlg || !c) return;
    var p;
    try { p = planFor(c, f); } catch (e) { L.warn('combine plan failed', e); M.clearPreview(); M.dialogNote(dlg, (e && e.message) || String(e), M.allCubes().length, true); return; }
    c.plan = p;
    var root = dlg.object;
    // 'Textura em HD' only when the pictures have more pixels than this skin
    if (!!c.wantsHD !== !!p.wantsHD) {
      c.wantsHD = p.wantsHD; busy = true;
      try { if (dlg.form && typeof dlg.form.update === 'function') dlg.form.update(); else if (typeof dlg.updateFormValues === 'function') dlg.updateFormValues(); } catch (e) { }
      busy = false;
    }
    var na = f.anims !== false ? p.item.anims.length : 0;
    var parts = [cubesText(p.cubes)];
    if (p.groups) parts.push(groupsText(p.groups));
    if (p.area && !p.colours) parts.push(T('combine.area', p.area));
    if (na) parts.push(animsText(na));
    M.dialogNote(dlg, p.rows ? parts.join(DOT) : T('combine.nothing'), M.allCubes().length + p.cubes, false);
    var sum = root.querySelector('.cmp_cb_sum'); if (sum) sum.textContent = c.src.label;
    var wh = root.querySelector('.cmp_cb_where');
    if (wh) {
      var lines = [];
      if (p.rows) {
        if (p.chosen) lines.push(T('combine.goes.one', rootLabel(p.chosen.name)));
        else lines.push(T('combine.goes', p.sets.map(function (s) { return s.parent !== 'root' ? rootLabel(s.parent.name) : rootLabel(''); }).filter(function (v, i, a) { return a.indexOf(v) === i; }).join(', ')));
        p.notes.forEach(function (n) { lines.push(n); });
        if (p.skipKeys && na) lines.push(T('combine.molang', p.skipKeys));
      }
      if (c.src.skipped.meshes) lines.push(T('combine.meshes', c.src.skipped.meshes));
      wh.textContent = lines.join(' ');
    }
    var room = root.querySelector('.cmp_cb_room');
    if (room) {
      var r = p.rows && !p.colours ? (p.noTex ? 'notex' : p.byTex.size ? roomOf(p) || 'ok' : '') : '';
      // lang: combine.room.{ok|no|notex|err}
      room.textContent = r ? T('combine.room.' + r) : '';
      room.classList.toggle('warn', r === 'no' || r === 'notex' || r === 'err');
    }
    try {
      var pv = { sets: p.sets.map(function (s) {
        var par = s.parent;
        return par !== 'root' ? { root: par.name, anchor: s.anchor, parts: shift(s.build, sub(s.anchor, vec(par.origin))) } : { root: '', anchor: s.anchor, parts: s.build };
      }) };
      var boxes = Lb().previewBoxes(pv, {});
      if (boxes.length && boxes.length <= MAX_PREVIEW) M.preview(boxes.map(function (b) { return { from: b.from, to: b.to, matrix: b.matrix || undefined, col: b.col }; }), 0x4fc3f7, { opacity: 0.75 });
      else M.clearPreview();
    } catch (e) { L.warn('combine preview failed', e); M.clearPreview(); }
    if (timer) clearTimeout(timer);
    timer = setTimeout(function () { timer = null; if (cur === c && c.plan === p) picture(p); }, 120);
  }
  function close() {
    if (timer) { clearTimeout(timer); timer = null; }
    var d = dlg; dlg = null; cur = null; M.clearPreview();
    if (d) { try { d.close(); } catch (e) { try { d.hide(); } catch (x) { } } }
  }
  function done(c, r) {
    var na = r.anims.length;
    G.Blockbench.showQuickMessage(T('combine.done', c.src.label, cubesText(r.cubes.length) + (na ? ' + ' + animsText(na) : '')), 2600);
  }
  function confirm(c, f) {
    var p = null;
    try { p = planFor(c, f); } catch (e) { G.Blockbench.showQuickMessage((e && e.message) || String(e), 4000); return false; }
    if (!p.rows) { G.Blockbench.showQuickMessage(T('combine.pickone'), 2200); return false; }
    var opts = { colours: f.paint === false, hd: f.hd !== false, anims: f.anims !== false };
    try { done(c, apply(c, p, opts)); }
    catch (e) {
      var full = (e && e.message) === T('lib.noroom');
      lastError = (e && e.message) || String(e);
      M.clearPreview();
      // no free skin space: more texture space (the area doubles, nothing moves) or the parts as colour cubes
      G.Blockbench.showMessageBox({ title: T('combine'), icon: full ? 'photo_size_select_large' : 'error', message: full ? T('combine.noroom.ask') : T('error') + '\n' + lastError,
        buttons: full ? [T('lib.grow'), T('lib.colours'), 'dialog.cancel'] : ['dialog.ok'], confirm: 0, cancel: full ? 2 : 0 }, function (b) {
        if (!full || b === 2 || b === undefined || cur !== c) { if (cur === c) update(dlg.getFormResult()); return; }
        try { done(c, apply(c, planFor(c, f), Object.assign({}, opts, b === 0 ? { grow: true } : { colours: true }))); }
        catch (x) { lastError = (x && x.message) || String(x); G.Blockbench.showMessageBox({ title: T('combine'), icon: 'error', message: lastError }); return; }
        close();
      });
      return false;
    }
    if (timer) { clearTimeout(timer); timer = null; }
    M.clearPreview(); dlg = null; cur = null;
  }
  /** the window for a source (from fromTab / fromFile) */
  function open(src) {
    close();
    lastError = null;
    var c = { src: src, ticked: preTicked(src), wantsHD: false };
    cur = c;
    var hasAnims = src.anims.some(function (a) { return a.tracks.length; });
    var form = {
      source: { label: T('combine.f.source'), type: 'select', options: sourceOptions(src), value: src.key },
      where: { label: T('combine.f.where'), type: 'select', options: whereOptions(), value: 'auto' },
      anims: { label: T('combine.f.anims'), type: 'checkbox', value: true, condition: function () { return hasAnims; } },
      paint: { label: T('combine.f.paint'), type: 'checkbox', value: true, condition: function () { return src.painted > 0; } },
      hd: { label: T('combine.f.hd'), description: T('combine.f.hd.desc'), type: 'checkbox', value: true, condition: function (f) { return !!(cur && cur.wantsHD) && (!f || f.paint !== false); } }
    };
    var d = new G.Dialog({
      id: 'cpmplus_model_combine', title: T('combine'), width: 660, buttons: [T('combine.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('combine.tip')) + '</p>', boxHtml(src)],
      form: form,
      onFormChange: function (f) {
        if (busy || cur !== c) return;
        if (f.source && f.source !== src.key) {
          var pick = f.source;
          setTimeout(function () {
            if (pick === 'pick') { close(); pickFile(); return; }
            var t = sourceTabs().filter(function (x) { return 'tab:' + x.uuid === pick; })[0];
            if (t) { var s2 = null; try { s2 = fromTab(t); } catch (e) { L.warn('combine: could not read the tab', e); } if (s2) open(s2); }
          }, 0);
          return;
        }
        update(f);
      },
      onConfirm: function (f) { return confirm(c, f); },
      onCancel: function () { if (timer) { clearTimeout(timer); timer = null; } M.clearPreview(); dlg = null; cur = null; }
    });
    dlg = d;
    d.show();
    try { wire(c); } catch (e) { L.warn('combine window', e); }
    try { update(d.getFormResult()); } catch (e) { L.warn('combine window failed', e); }
    return d;
  }
  function wire(c) {
    var root = dlg.object, box = root.querySelector('.cmp_cb_box'), bar = root.querySelector('.form_bar_source');
    // the tree right under 'De onde', above where and how
    if (box && bar && bar.parentNode) {
      bar.parentNode.insertBefore(box, bar.nextSibling);
      var wh = root.querySelector('.cmp_cb_where'), rm = root.querySelector('.cmp_cb_room');
      if (wh) box.parentNode.insertBefore(wh, box.nextSibling);
      if (rm && wh) wh.parentNode.insertBefore(rm, wh.nextSibling);
    }
    syncTree(c);
    var list = root.querySelector('.cmp_cb_list');
    if (list) list.addEventListener('change', function (e) {
      var b = e.target; if (!b || !b.getAttribute || !b.getAttribute('data-id')) return;
      tick(c, b.getAttribute('data-id'), b.checked); syncTree(c); update(dlg.getFormResult());
    });
    var tb = root.querySelector('.cmp_cb_bar');
    if (tb) tb.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-act]'); if (!b) return;
      if (b.getAttribute('data-act') === 'all') c.src.rows.forEach(function (r) { if (!r.parent && !r.base && (r.kind !== 'root' || r.kids.some(function (k) { return !k.base; }))) tick(c, r.id, true); });
      else c.ticked = {};
      syncTree(c); update(dlg.getFormResult());
    });
  }
  function pickFile() {
    G.Blockbench.import({ resource_id: 'cpmplus_combine', extensions: ['cpmproject', 'bbmodel'], type: T('combine.file'), readtype: 'binary' }, function (files) {
      var f = files && files[0]; if (!f) return;
      fromFile(f).then(open, function (e) { lastError = (e && e.message) || String(e); G.Blockbench.showMessageBox({ title: T('combine'), icon: 'error', message: lastError }); });
    });
  }

  // =================================================================== the action
  function run() {
    if (!M.isCpm()) {
      // clicked in the other model's own tab: back to the CPM project, with this tab as the source
      var cpmTab = ((G.ModelProject && G.ModelProject.all) || []).filter(function (p) { return p && p.format && p.format.id === 'cpm'; })[0], here = G.Project;
      if (cpmTab && hasParts(here)) { cpmTab.select(); setTimeout(function () { try { open(fromTab(here)); } catch (e) { L.warn('combine', e); } }, 50); return; }
      lastError = 'cpm';
      G.Blockbench.showMessageBox({ title: T('combine'), message: T('combine.cpmonly') });
      return;
    }
    var tabs = sourceTabs();
    // the model opened last (the one just brought in)
    if (tabs.length) open(fromTab(tabs[tabs.length - 1]));
    else pickFile();
  }
  /** File > Import: right after the other CPM+ model imports ('Importar .vox', 'Converter modelo 3D', its mesh-to-cubes twin) */
  function placeInImport(action) {
    var file = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.file, imp = null;
    if (!file || !Array.isArray(file.structure)) return;
    file.structure.forEach(function (x) { if (x && x.id === 'import') imp = x; });
    if (!imp || !Array.isArray(imp.children)) return;
    var kids = imp.children;
    for (var i = kids.length - 1; i >= 0; i--) if (kids[i] && kids[i] !== action && kids[i].id === action.id) kids.splice(i, 1);
    if (kids.indexOf(action) >= 0) return;
    var at = -1, rank = 0, RANK = { cpmplus_model_mesh2cube: 4, cpmplus_model_convert: 3, cpmplus_model_vox: 2, import_obj: 1 };
    kids.forEach(function (x, k) {
      var id = typeof x === 'string' ? x : x && x.id, r = RANK[id] || 0;
      if (r > rank) { rank = r; at = k; }
    });
    kids.splice(at >= 0 ? at + 1 : kids.length, 0, action);
    L.onUninstall(function () { var k = kids.indexOf(action); if (k >= 0) kids.splice(k, 1); });
  }
  var STYLE = 'cmp_combine_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = [
        '.cmp_cb_box{display:flex;gap:12px;align-items:flex-start;margin:2px 0 6px}',
        '.cmp_cb_main{flex:1;min-width:0}',
        '.cmp_cb_bar{display:flex;gap:6px;margin:0 0 4px}',
        '.cmp_cb_bar button{padding:2px 10px;cursor:pointer}',
        '.cmp_cb_list{max-height:34vh;min-height:90px;overflow-y:auto;border:1px solid var(--color-border);border-radius:4px;padding:4px 0}',
        '.cmp_cb_row{display:flex;align-items:center;gap:5px;padding:2px 8px;cursor:pointer;white-space:nowrap}',
        '.cmp_cb_row:hover{background:var(--color-selected)}',
        '.cmp_cb_row .material-icons{font-size:16px;opacity:.7}',
        '.cmp_cb_row .nm{overflow:hidden;text-overflow:ellipsis}',
        '.cmp_cb_row .ct{opacity:.6;font-size:.85em;margin-left:4px}',
        '.cmp_cb_row .cmp_cb_anim{color:var(--color-accent);opacity:.9}',
        '.cmp_cb_row.root .nm{font-weight:600}',
        '.cmp_cb_row.with .nm{opacity:.6}',
        '.cmp_cb_row.base{opacity:.45;cursor:default}',
        '.cmp_cb_side{flex:0 0 128px;text-align:center}',
        '.cmp_cb_pic{display:block;width:128px;height:128px;border-radius:6px;background:var(--color-back)}',
        '.cmp_cb_sum{font-size:.85em;opacity:.75;margin-top:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
        '.cmp_cb_empty{padding:14px 10px;text-align:center;opacity:.8}',
        '.cmp_cb_where,.cmp_cb_room{opacity:.85;margin:4px 0 0}',
        '.cmp_cb_room.warn{color:var(--color-warning, #e8a33d);opacity:1}'
      ].join('\n');
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var action = M.register({ id: 'cpmplus_model_combine', sec: 'create', icon: 'merge_type', key: 'combine', spots: [], run: function () { run(); } });
    placeInImport(action);
    try {
      G.Blockbench.on('undo', onUndo); G.Blockbench.on('redo', onRedo);
      L.onUninstall(function () { G.Blockbench.removeListener('undo', onUndo); G.Blockbench.removeListener('redo', onRedo); });
    } catch (e) { }
  };
  api.uninstall = function () { close(); steps.clear(); };

  // for QA and the other modules
  api.run = run; api.open = open; api.pickFile = pickFile; api.fromTab = fromTab; api.fromFile = fromFile; api.readProject = readProject; api.sourceTabs = sourceTabs;
  api.planFor = planFor; api.apply = apply; api.roomOf = roomOf; api.sheetOf = sheetOf; api.sheetTexture = sheetTexture;
  api.dialog = function () { return dlg; }; api.current = function () { return cur; };
  api.lastError = function () { return lastError; }; api.lastResult = function () { return lastResult; };
  /** ticks rows by id (as clicks would) and updates the window */
  api.tick = function (ids, on) {
    var c = cur; if (!c) return false;
    (ids || []).forEach(function (id) { tick(c, String(id), on !== false); });
    syncTree(c); if (dlg) update(dlg.getFormResult());
    return true;
  };
  return api;
})
