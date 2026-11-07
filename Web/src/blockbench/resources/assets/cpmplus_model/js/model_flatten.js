/*
 * CPM Plugin+ "CPM+ Modelar" - Achatar detalhes na textura: small thin cubes lying flat on a bigger painted cube's face
 * (decals, eyes, buttons, thin plates) are painted into that face's pixels - colour cubes with their colour, painted
 * cubes with the pixels of their outer face (transparent pixels let the face show through) - and removed: the model
 * looks the same from the front with fewer cubes. The face mapping (which texture pixel is where on the face) is read
 * from the cube's own mesh geometry, so it is exactly what Blockbench shows (face rotation, flipped UVs, HD textures).
 * What cannot be done is left as it is, with the reason: not lying flat on a face, the face below is a colour cube,
 * passes the edge, the face's pixels are used by another part too, turned, glowing / tinted, another texture.
 * Live preview (green = becomes paint, orange = stays); one Ctrl+Z puts the cubes AND the pixels back (explicit pixel
 * undo, see model_texture: the canvas of a CPM skin does not come back by itself).
 * Loaded after the modelling engine and model_texture.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T, MT = L.core.modules.model_texture;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var DIR = { east: [0, 1], west: [0, -1], up: [1, 1], down: [1, -1], south: [2, 1], north: [2, -1] };
  var TOL = 0.011;

  function turnedChain(n) { for (var p = n; p && p !== 'root'; p = p.parent) { var r = p.rotation; if (r && (r[0] || r[1] || r[2])) return true; } return false; }
  function recolour(c) { return typeof c.cpm_recolor === 'number' && c.cpm_recolor !== -1 ? c.cpm_recolor & 0xFFFFFF : null; }
  function painted(c) { return FACES.some(function (k) { var f = c.faces && c.faces[k]; return f && f.texture !== false && f.texture !== null && f.texture !== undefined; }); }
  function isColourCube(c) { return !painted(c) && recolour(c) !== null; }
  function vbox(c) { var f = c.inflate || 0, lo = [], hi = []; for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i]) - f); hi.push(Math.max(c.from[i], c.to[i]) + f); } return { lo: lo, hi: hi }; }
  function faceTex(c, k) { var f = c.faces && c.faces[k]; if (!f || f.texture === false || f.texture === null || f.texture === undefined) return null; try { return f.getTexture() || null; } catch (e) { return null; } }

  // =================================================================== face geometry: data point on the face <-> texture pixel
  /**
   * the mapping of one face of an unturned cube, read from its mesh: {axis, side, b1, b2, tex, W, H, uv(p1, p2) ->
   * [x, y] texture px, pt(x, y) -> [p1, p2] face plane, rect: [x0, y0, x1, y1] px} or null
   */
  function faceMap(c, k) {
    var tex = faceTex(c, k); if (!tex || !c.mesh || !c.mesh.geometry) return null;
    try { G.Cube.preview_controller.updateGeometry(c); } catch (e) { }
    try { if (G.Canvas.updateUV) G.Canvas.updateUV(c); } catch (e) { }
    var geo = c.mesh.geometry, pos = geo.attributes.position, uv = geo.attributes.uv, nor = geo.attributes.normal;
    if (!pos || !uv) return null;
    var a = DIR[k][0], s = DIR[k][1], b1 = (a + 1) % 3, b2 = (a + 2) % 3, cv = MT.canvasOf(tex);
    var W = (cv && cv.width) || tex.width, H = (cv && cv.height) || tex.height, quad = null;
    for (var i = 0; i + 3 < pos.count && !quad; i += 4) {
      var ok = true;
      for (var j = 0; j < 4 && ok; j++) {
        if (nor) { var nv = [nor.getX(i + j), nor.getY(i + j), nor.getZ(i + j)]; if (Math.abs(nv[a] - s) > 0.01) ok = false; }
        else ok = false;
      }
      if (ok) quad = i;
    }
    if (quad === null) return null;
    var P = [], U = [];
    for (var q = 0; q < 4; q++) {
      var v = [pos.getX(quad + q) + c.origin[0], pos.getY(quad + q) + c.origin[1], pos.getZ(quad + q) + c.origin[2]];
      P.push([v[b1], v[b2]]); U.push([uv.getX(quad + q) * W, (1 - uv.getY(quad + q)) * H]);
    }
    // affine map from the plane to the texture: 3 corners (origin, along b1, along b2)
    var lo1 = Math.min(P[0][0], P[1][0], P[2][0], P[3][0]), hi1 = Math.max(P[0][0], P[1][0], P[2][0], P[3][0]);
    var lo2 = Math.min(P[0][1], P[1][1], P[2][1], P[3][1]), hi2 = Math.max(P[0][1], P[1][1], P[2][1], P[3][1]);
    if (hi1 - lo1 < 1e-6 || hi2 - lo2 < 1e-6) return null;
    function at(x, y) { for (var r = 0; r < 4; r++) if (Math.abs(P[r][0] - x) < 1e-4 && Math.abs(P[r][1] - y) < 1e-4) return U[r]; return null; }
    var O = at(lo1, lo2), A1 = at(hi1, lo2), A2 = at(lo1, hi2);
    if (!O || !A1 || !A2) return null;
    var du1 = (A1[0] - O[0]) / (hi1 - lo1), dv1 = (A1[1] - O[1]) / (hi1 - lo1), du2 = (A2[0] - O[0]) / (hi2 - lo2), dv2 = (A2[1] - O[1]) / (hi2 - lo2);
    var det = du1 * dv2 - du2 * dv1; if (Math.abs(det) < 1e-9) return null;
    var xs = U.map(function (u) { return u[0]; }), ys = U.map(function (u) { return u[1]; });
    return {
      axis: a, side: s, b1: b1, b2: b2, tex: tex, W: W, H: H,
      lo: [lo1, lo2], hi: [hi1, hi2],
      uv: function (p1, p2) { var x = p1 - lo1, y = p2 - lo2; return [O[0] + du1 * x + du2 * y, O[1] + dv1 * x + dv2 * y]; },
      pt: function (x, y) { var dx = x - O[0], dy = y - O[1]; return [lo1 + (dx * dv2 - dy * du2) / det, lo2 + (du1 * dy - dv1 * dx) / det]; },
      rect: [Math.round(Math.min.apply(null, xs)), Math.round(Math.min.apply(null, ys)), Math.round(Math.max.apply(null, xs)), Math.round(Math.max.apply(null, ys))]
    };
  }

  // =================================================================== the plan
  // lang: flatten.stay.{notflat|colourbase|overhang|shared|turned|glow|tinted|texture|thick|base}
  var REASONS = ['notflat', 'colourbase', 'overhang', 'shared', 'turned', 'glow', 'tinted', 'texture', 'thick', 'base'];
  var RANK = { notflat: 0, overhang: 1, colourbase: 2, thick: 3, tinted: 4 };
  /**
   * o = {cubes, thick (max px), quiet (cubes that are not thin at all are not listed)} -> {jobs: [{cube, target, face,
   * map}], stay: {reason: [cubes]}, texture, saved}
   */
  function plan(o) {
    var maxT = Math.max(0.0625, Number(o.thick) || 1), cubes = (o.cubes || []).filter(function (c) { return M.isCube(c) && c.export !== false; });
    var going = cubes.slice(), stay = {}, jobs = [], texture = null;
    REASONS.forEach(function (r) { stay[r] = []; });
    function keep(c, why) { stay[why].push(c); var i = going.indexOf(c); if (i >= 0) going.splice(i, 1); }
    cubes.slice().forEach(function (c) {
      if (M.isBase(c)) return keep(c, 'base');
      if (turnedChain(c) || (c.rotation && (c.rotation[0] || c.rotation[1] || c.rotation[2]))) return keep(c, 'turned');
      if (c.cpm_glow) return keep(c, 'glow');
      if (painted(c) && recolour(c) !== null) return keep(c, 'tinted');
      if (!painted(c) && recolour(c) === null) return keep(c, 'notflat');
      var b = vbox(c), dims = [b.hi[0] - b.lo[0], b.hi[1] - b.lo[1], b.hi[2] - b.lo[2]];
      // not thin at all: not a detail (the whole model: not even listed)
      if (Math.min(dims[0], dims[1], dims[2]) > maxT + 1e-6) { if (o.quiet) { going.splice(going.indexOf(c), 1); return; } return keep(c, 'thick'); }
    });
    // the targets: unturned cubes that are not going away
    var targets = M.allCubes().filter(function (t) { return going.indexOf(t) < 0 && t.visibility !== false && !turnedChain(t) && !(t.rotation && (t.rotation[0] || t.rotation[1] || t.rotation[2])); });
    going.slice().forEach(function (c) {
      var b = vbox(c), best = null, why = 'notflat';
      // the most telling reason wins when no face takes the detail
      var why2 = function (w) { if (RANK[w] > RANK[why]) why = w; };
      targets.forEach(function (t) {
        var tb = vbox(t);
        FACES.forEach(function (k) {
          var a = DIR[k][0], s = DIR[k][1], b1 = (a + 1) % 3, b2 = (a + 2) % 3, plane = s > 0 ? tb.hi[a] : tb.lo[a];
          var thick = b.hi[a] - b.lo[a];
          // lies on the face: touching it from outside or sunk into it by at most its own thickness, sticking out
          var near = s > 0 ? b.lo[a] : b.hi[a], far = s > 0 ? b.hi[a] : b.lo[a];
          if (s > 0 ? (near < plane - thick - TOL || near > plane + TOL || far < plane - TOL) : (near > plane + thick + TOL || near < plane - TOL || far > plane + TOL)) return;
          // and over it (not past its edges)
          var over = b.lo[b1] >= tb.lo[b1] - TOL && b.hi[b1] <= tb.hi[b1] + TOL && b.lo[b2] >= tb.lo[b2] - TOL && b.hi[b2] <= tb.hi[b2] + TOL;
          var touch = b.hi[b1] > tb.lo[b1] + TOL && b.lo[b1] < tb.hi[b1] - TOL && b.hi[b2] > tb.lo[b2] + TOL && b.lo[b2] < tb.hi[b2] - TOL;
          if (!touch) return;
          if (!over) return why2('overhang');
          if (thick > maxT + 1e-6) return why2('thick');
          if (!faceTex(t, k)) return why2(isColourCube(t) ? 'colourbase' : 'notflat');
          if (recolour(t) !== null) return why2('tinted');
          var area = (tb.hi[b1] - tb.lo[b1]) * (tb.hi[b2] - tb.lo[b2]);
          if (!best || area > best.area) best = { target: t, face: k, area: area, out: s > 0 ? far - plane : plane - far };
        });
      });
      if (!best) return keep(c, why);
      var map = faceMap(best.target, best.face);
      // the mesh must agree with the face's UV data (never paint somewhere else)
      var pr = map && pxRect(best.target, best.face);
      if (!map || !pr || pr.join() !== map.rect.join()) return keep(c, 'notflat');
      if (texture && map.tex !== texture) return keep(c, 'texture');
      texture = map.tex;
      // the decal's own look: its colour, or its outer face's pixels
      var src = null;
      if (painted(c)) { src = faceMap(c, best.face); if (!src) return keep(c, 'notflat'); }
      jobs.push({ cube: c, target: best.target, face: best.face, map: map, src: src, colour: recolour(c), out: best.out });
    });
    // the face's pixels must belong to that face only (other parts sharing them would change too)
    if (texture) {
      var mine = jobs.map(function (j) { return j.cube; }), owners = [];
      M.allCubes().forEach(function (c) { if (mine.indexOf(c) >= 0) return; FACES.forEach(function (k) { if (faceTex(c, k) !== texture) return; var r = pxRect(c, k); if (r) owners.push({ c: c, k: k, r: r }); }); });
      jobs.slice().forEach(function (j) {
        var r = footRect(j), shared = owners.some(function (w) { return !(w.c === j.target && w.k === j.face) && w.r[0] < r[2] && w.r[2] > r[0] && w.r[1] < r[3] && w.r[3] > r[1]; });
        if (shared) { jobs.splice(jobs.indexOf(j), 1); stay.shared.push(j.cube); }
      });
    }
    // the outer details paint last (a sticker on a sticker)
    jobs.sort(function (x, y) { return x.out - y.out; });
    var stays = 0; REASONS.forEach(function (r) { stays += stay[r].length; });
    return { jobs: jobs, stay: stay, stays: stays, texture: jobs.length ? texture : null, saved: jobs.length };
  }
  /** texture px rect of a face from its data (UV units -> px) */
  function pxRect(c, k) {
    var f = c.faces[k]; if (!f || !f.uv) return null;
    var tex = faceTex(c, k), I = MT.uvInfo(tex);
    var x0 = Math.min(f.uv[0], f.uv[2]) * I.sx, x1 = Math.max(f.uv[0], f.uv[2]) * I.sx, y0 = Math.min(f.uv[1], f.uv[3]) * I.sy, y1 = Math.max(f.uv[1], f.uv[3]) * I.sy;
    return x1 - x0 > 1e-6 && y1 - y0 > 1e-6 ? [Math.floor(x0 + 1e-6), Math.floor(y0 + 1e-6), Math.ceil(x1 - 1e-6), Math.ceil(y1 - 1e-6)] : null;
  }
  /** texture px rect (on the target face) covered by a job's decal */
  function footRect(j) {
    var b = vbox(j.cube), m = j.map, cs = [m.uv(b.lo[m.b1], b.lo[m.b2]), m.uv(b.hi[m.b1], b.lo[m.b2]), m.uv(b.lo[m.b1], b.hi[m.b2]), m.uv(b.hi[m.b1], b.hi[m.b2])];
    var xs = cs.map(function (p) { return p[0]; }), ys = cs.map(function (p) { return p[1]; });
    return [Math.floor(Math.min.apply(null, xs) + 1e-6), Math.floor(Math.min.apply(null, ys) + 1e-6), Math.ceil(Math.max.apply(null, xs) - 1e-6), Math.ceil(Math.max.apply(null, ys) - 1e-6)];
  }

  // =================================================================== painting (one undo step with the pixels)
  function readAll(tex) {
    var cv = MT.canvasOf(tex); if (!cv) return null;
    try { return cv.getContext('2d').getImageData(0, 0, cv.width, cv.height); } catch (e) { return null; }
  }
  /**
   * the new pixels: one block per target face (the footprints of its details), the details painted in order (outer
   * ones last) -> {before, after} blocks for model_texture.putBlocks
   */
  function paintBlocks(p) {
    var groups = [], byKey = {};
    p.jobs.forEach(function (j) {
      var key = j.target.uuid + '|' + j.face, r = footRect(j), g = byKey[key];
      if (!g) { g = byKey[key] = { r: r.slice(), jobs: [] }; groups.push(g); }
      g.r = [Math.min(g.r[0], r[0]), Math.min(g.r[1], r[1]), Math.max(g.r[2], r[2]), Math.max(g.r[3], r[3])];
      g.jobs.push(j);
    });
    var before = MT.grabBlocks(p.texture, groups.map(function (g) { return [g.r[0], g.r[1], Math.max(1, g.r[2] - g.r[0]), Math.max(1, g.r[3] - g.r[1])]; }));
    var after = before.map(function (b) { return { x: b.x, y: b.y, w: b.w, h: b.h, img: new ImageData(new Uint8ClampedArray(b.img.data), b.w, b.h) }; });
    var srcCache = {};
    groups.forEach(function (g, gi) {
      var blk = after[gi], d = blk.img.data;
      g.jobs.forEach(function (j) {
        var b = vbox(j.cube), m = j.map, src = null;
        if (j.src) { var key = j.src.tex.uuid; src = srcCache[key] = srcCache[key] || readAll(j.src.tex); if (!src) return; }
        for (var y = blk.y; y < blk.y + blk.h; y++) for (var x = blk.x; x < blk.x + blk.w; x++) {
          var q = m.pt(x + 0.5, y + 0.5);
          if (q[0] < b.lo[m.b1] - 1e-6 || q[0] > b.hi[m.b1] + 1e-6 || q[1] < b.lo[m.b2] - 1e-6 || q[1] > b.hi[m.b2] + 1e-6) continue;
          var rgba;
          if (src) {
            var su = j.src.uv(q[0], q[1]), sx = Math.min(src.width - 1, Math.max(0, Math.floor(su[0]))), sy = Math.min(src.height - 1, Math.max(0, Math.floor(su[1]))), k = (sy * src.width + sx) * 4;
            rgba = [src.data[k], src.data[k + 1], src.data[k + 2], src.data[k + 3]];
          } else rgba = [(j.colour >> 16) & 255, (j.colour >> 8) & 255, j.colour & 255, 255];
          if (!rgba[3]) continue;
          var o = ((y - blk.y) * blk.w + (x - blk.x)) * 4, al = rgba[3] / 255;
          for (var c = 0; c < 3; c++) d[o + c] = Math.round(rgba[c] * al + d[o + c] * (1 - al));
          d[o + 3] = Math.max(d[o + 3], rgba[3]);
        }
      });
    });
    return { before: before, after: after };
  }
  /** flattens a plan (one undo step: cubes + pixels) -> the removed cubes */
  function apply(p) {
    if (!p.jobs.length) return [];
    var tex = p.texture, gone = p.jobs.map(function (j) { return j.cube; });
    // every block is read and painted before anything is written
    var px = paintBlocks(p), aspects = MT.pixelAspects(tex);
    M.edit(T('flatten'), Object.assign({ outliner: true, elements: gone.slice() }, aspects), function () {
      MT.putBlocks(tex, px.after);
      gone.forEach(function (c) { c.remove(); });
    }, function () { return Object.assign({ outliner: true, elements: [] }, aspects); });
    MT.bindPixels(tex, px.before, px.after);
    try { if (typeof tex.updateLayerChanges === 'function') tex.updateLayerChanges(true); } catch (e) { }
    M.refresh();
    return gone;
  }
  // =================================================================== the window
  var dlg = null;
  function targetCubes(what) { return what === 'all' ? M.allCubes() : M.cubesOf(M.selNodes()); }
  function previewPlan(p) {
    var boxes = [];
    p.jobs.forEach(function (j) { var b = vbox(j.cube); boxes.push({ from: b.lo, to: b.hi, col: 0x4caf50 }); });
    REASONS.forEach(function (r) { if (r === 'base') return; p.stay[r].forEach(function (c) { var b = vbox(c); boxes.push({ from: b.lo, to: b.hi, col: 0xff9800 }); }); });
    M.preview(boxes, 0x4caf50, { opacity: 0.7 });
  }
  function open() {
    var whole = !M.selNodes().length;
    dlg = new G.Dialog({
      id: 'cpmplus_model_flatten', title: T('flatten'), width: 500, buttons: [T('flatten.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('flatten.tip')) + '</p>', '<div class="cmp_flat_sum"></div>'],
      form: {
        what: { label: T('flatten.f.what'), type: 'select', options: { sel: T('flatten.f.sel'), all: T('flatten.f.all') }, value: whole ? 'all' : 'sel' },
        thick: { label: T('flatten.f.thick'), type: 'select', options: { '0.5': '0.5 px', '1': '1 px', '2': '2 px' }, value: '1' }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview(); dlg = null;
        var p = plan({ cubes: targetCubes(f.what), thick: f.thick, quiet: f.what === 'all' });
        if (!p.jobs.length) { G.Blockbench.showQuickMessage(T('flatten.none'), 2500); return; }
        apply(p);
        G.Blockbench.showQuickMessage(T('flatten.done', p.jobs.length), 2500);
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var p = plan({ cubes: targetCubes(f.what), thick: f.thick, quiet: f.what === 'all' }), box = dlg.object.querySelector('.cmp_flat_sum'), lines = [];
      lines.push('<p class="cmp_flat_big">' + L.esc(p.jobs.length ? T('flatten.sum', p.jobs.length) : T('flatten.none')) + '</p>');
      REASONS.forEach(function (r) { if (r !== 'base' && p.stay[r].length) lines.push('<p class="cmp_flat_stay">' + L.esc(T('flatten.stay.' + r, p.stay[r].length)) + '</p>'); });
      if (box) box.innerHTML = lines.join('');
      previewPlan(p);
      M.dialogNote(dlg, T('flatten.after'), M.allCubes().length - p.saved, false);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('flatten preview failed', e); }
    return dlg;
  }
  var STYLE = 'cmp_flat_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_flat_big{font-size:1.1em;font-weight:600;margin:6px 0}\n.cmp_flat_stay{opacity:.8;margin:2px 0}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_flatten', sec: 'texture', icon: 'layers_clear', key: 'flatten', mode: 'edit', spots: ['cube'], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.plan = plan; api.apply = apply; api.faceMap = faceMap; api.open = open; api.dialog = function () { return dlg; }; api.targetCubes = targetCubes;
  return api;
})
