/*
 * CPM Plugin+ "CPM+ Modelar" - 2a camada para pecas novas: for the selected cubes (groups: every cube inside) an outer
 * layer like the hat / jacket of a Minecraft skin - a copy a little bigger (inflate +0.25, like 'Adicionar 2a camada'),
 * right after its cube in the same group, with its own free piece of the texture:
 * - 'Copiar a pintura': the layer starts with the same pixels as its cube (a colour cube: its colour);
 * - 'Transparente': the area stays empty, ready to paint (hair, hood, details).
 * The layers follow the plugin's 2nd-layer convention: CubeData extra 'l2' + a sibling whose inflated box holds its
 * cube, so 'So a 2a camada' / 'Pele de baixo' (Paint mode) and the other CPM+ tools treat them as a 2nd layer.
 * Fresh CPM ids, one Ctrl+Z (pixels too); no room on the texture = nothing changes.
 * Loaded after the modelling engine, model_texture and model_slice.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T, X = L.core.modules.model_texture, SL = L.core.modules.model_slice;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var DEF_INFLATE = 0.25;

  function data(c) { try { var d = c.cpm_data ? JSON.parse(c.cpm_data) : {}; return d && typeof d === 'object' ? d : {}; } catch (e) { return {}; } }
  function marked(c) { return !!data(c).l2; }
  function siblings(c) { return (c.parent && c.parent !== 'root' ? c.parent.children : (G.Outliner && G.Outliner.root) || []).filter(function (n) { return M.isCube(n) && n !== c; }); }
  /** the inflated box of outer holds the inflated box of inner (the 2nd-layer rule of Paint mode, 0.01 tolerance) */
  function holds(outer, inner) {
    var io = outer.inflate || 0, ii = inner.inflate || 0;
    for (var i = 0; i < 3; i++) {
      if (Math.min(outer.from[i], outer.to[i]) - io > Math.min(inner.from[i], inner.to[i]) - ii + 0.01) return false;
      if (Math.max(outer.from[i], outer.to[i]) + io < Math.max(inner.from[i], inner.to[i]) + ii - 0.01) return false;
    }
    return true;
  }
  function textured(fc) { return !!fc && fc.texture !== false && fc.texture !== null && fc.texture !== undefined; }
  function painted(c) { return FACES.some(function (f) { return textured(c.faces[f]); }); }
  /** why a cube gets no layer (lang: layer2.why.{base|shell|has|tex}) or null */
  function refuse(c, tex) {
    if (M.isBase(c)) return 'base';
    var sib = siblings(c);
    if (marked(c) || sib.some(function (s) { return (s.inflate || 0) < (c.inflate || 0) && holds(c, s); })) return 'shell';
    if (sib.some(function (s) { return (s.inflate || 0) > (c.inflate || 0) && holds(s, c); })) return 'has';
    if (painted(c) && X.textureOf(c) !== tex) return 'tex';
    return null;
  }
  function size(c, i) { return Math.abs(c.to[i] - c.from[i]); }
  /** the face rect sizes (w, h) of a cube without paint, in whole texture units */
  var FSIZE = { north: [0, 1], south: [0, 1], east: [2, 1], west: [2, 1], up: [0, 2], down: [0, 2] };
  /**
   * the plan: o = {mode: 'copy'|'clear', inflate} -> {items: [{base, kind: 'paint'|'colour', plan (model_texture
   * area plan) | box: [u, v] | faces: {f: [x1, y1, x2, y2]}}], skipped: {why: [names]}, tex} ; throws 'noroom' / 'notex'
   */
  function plan(cubes, o) {
    var tex = X.mainTexture(); if (!tex) throw new Error('notex');
    var items = [], skipped = {}, map = X.occupancy(tex);
    cubes.forEach(function (c) {
      var why = refuse(c, tex);
      if (why) { (skipped[why] = skipped[why] || []).push(c.name); return; }
      if (painted(c)) {
        var p = X.planAreas([{ cube: null, src: c }], tex, map)[0];// throws 'noroom'
        items.push({ base: c, kind: 'paint', plan: p });
        return;
      }
      var s = [0, 1, 2].map(function (i) { return size(c, i); });
      if (s.every(function (v) { return Math.abs(v - Math.round(v)) < 1e-4; })) {
        var at = X.allocShape(map, X.boxRects(0, 0, Math.round(s[0]), Math.round(s[1]), Math.round(s[2])));
        if (!at) throw new Error('noroom');
        items.push({ base: c, kind: 'colour', box: at, rects: X.boxRects(at[0], at[1], Math.round(s[0]), Math.round(s[1]), Math.round(s[2])) });
      } else {
        var faces = {};
        FACES.forEach(function (f) {
          var w = Math.max(1, Math.ceil(s[FSIZE[f][0]] - 1e-4)), h = Math.max(1, Math.ceil(s[FSIZE[f][1]] - 1e-4)), a = X.alloc(map, w, h);
          if (!a) throw new Error('noroom');
          faces[f] = [a[0], a[1], a[0] + w, a[1] + h];
        });
        items.push({ base: c, kind: 'colour', faces: faces, rects: FACES.map(function (f) { return faces[f]; }) });
      }
    });
    return { items: items, skipped: skipped, tex: tex, mode: o.mode === 'clear' ? 'clear' : 'copy', inflate: Math.max(0.01, Number(o.inflate) || DEF_INFLATE) };
  }
  /** points a cube at the areas of a model_texture plan without copying pixels (the area is free: transparent) */
  function pointAreas(p) {
    p.moves.forEach(function (m) {
      if (m.box) { p.cube.uv_offset = [m.offset[0], m.offset[1]]; return; }
      var f = p.cube.faces[m.face], dw = m.from[2] - m.from[0], dh = m.from[3] - m.from[1], flipX = f.uv[0] > f.uv[2], flipY = f.uv[1] > f.uv[3];
      f.uv = [flipX ? m.to[0] + dw : m.to[0], flipY ? m.to[1] + dh : m.to[1], flipX ? m.to[0] : m.to[0] + dw, flipY ? m.to[1] : m.to[1] + dh];
    });
    try { if (p.cube.box_uv && typeof p.cube.updateUV === 'function') p.cube.updateUV(); } catch (e) { }
  }
  function hexOf(rgb) { return '#' + ('000000' + (rgb & 0xFFFFFF).toString(16)).slice(-6); }
  /** makes the layers of a plan (one undo entry) -> the new cubes */
  function apply(P) {
    if (!P.items.length) return [];
    var tex = P.tex, shells = [], copy = P.mode === 'copy', I = X.uvInfo(tex);
    var pxAsp = copy ? X.pixelAspects(tex) : {}, px = { before: [], after: [] };
    M.edit(T('layer2'), Object.assign({ outliner: true, elements: [], groups: [] }, pxAsp), function () {
      var paints = [], fills = [];
      P.items.forEach(function (it) {
        var b = it.base, s = M.duplicateNode(b);
        s.name = T('layer2.name', b.name);
        s.inflate = M.snap((b.inflate || 0) + P.inflate);
        var d = data(s); d.l2 = true; s.cpm_data = JSON.stringify(d);
        if (it.kind === 'paint') { it.plan.cube = s; paints.push(it.plan); }
        else {
          s.cpm_recolor = -1;
          s.applyTexture(tex, true);
          if (it.box) { s.box_uv = true; s.uv_offset = [it.box[0], it.box[1]]; try { if (typeof s.updateUV === 'function') s.updateUV(); } catch (e) { } }
          else { s.box_uv = false; FACES.forEach(function (f) { s.faces[f].uv = it.faces[f].slice(); s.faces[f].rotation = 0; }); }
          if (copy) fills.push({ rects: it.rects, colour: b.cpm_recolor });
        }
        shells.push(s);
      });
      if (paints.length) {
        if (copy) { var r = X.applyAreas(paints, tex); px.before = px.before.concat(r.before); px.after = px.after.concat(r.after); }
        else paints.forEach(pointAreas);
      }
      if (fills.length) {
        // a colour cube's layer starts with its colour (texture pixels of the allocated rects)
        var rects = [];
        fills.forEach(function (f) { f.rects.forEach(function (r) { rects.push([Math.round(r[0] * I.sx), Math.round(r[1] * I.sy), Math.round((r[2] - r[0]) * I.sx), Math.round((r[3] - r[1]) * I.sy)]); }); });
        rects = rects.filter(function (r) { return r[2] > 0 && r[3] > 0; });
        var before = X.grabBlocks(tex, rects), k = 0;
        var run = function (ctx, off) {
          fills.forEach(function (f) {
            ctx.fillStyle = hexOf(f.colour);
            f.rects.forEach(function (r) { var x = Math.round(r[0] * I.sx), y = Math.round(r[1] * I.sy), w = Math.round((r[2] - r[0]) * I.sx), h = Math.round((r[3] - r[1]) * I.sy); if (w > 0 && h > 0) ctx.fillRect(x - off[0], y - off[1], w, h); k++; });
          });
        };
        if (G.Painter && typeof G.Painter.edit === 'function') G.Painter.edit(tex, function () { run(G.Painter.current.ctx, G.Painter.current.offset || [0, 0]); }, { no_undo: true });
        px.before = px.before.concat(before); px.after = px.after.concat(X.grabBlocks(tex, rects));
      }
    }, function () { return Object.assign({ outliner: true, elements: shells, groups: [] }, pxAsp); });
    if (copy && px.before.length) X.bindPixels(tex, px.before, px.after);
    M.refresh();
    try { if (typeof tex.updateLayerChanges === 'function') tex.updateLayerChanges(true); } catch (e) { }
    M.selectNodes(shells);
    return shells;
  }
  function targets() { return M.cubesOf(M.selNodes()); }
  /** makes the layers of cubes (QA / other modules) -> {shells, skipped}; throws with a message when there is no room */
  function make(cubes, o) {
    var P;
    try { P = plan(cubes || targets(), o || {}); }
    catch (e) { if (e.message === 'noroom') throw new Error(T('layer2.noroom')); if (e.message === 'notex') throw new Error(T('layer2.notex')); throw e; }
    return { shells: apply(P), skipped: P.skipped };
  }

  // =================================================================== the window
  function previewOf(cubes, inf) {
    var boxes = [];
    cubes.forEach(function (c) {
      if (refuse(c, X.mainTexture())) return;
      var e = (c.inflate || 0) + inf, lo = [], hi = [];
      for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i]) - e); hi.push(Math.max(c.from[i], c.to[i]) + e); }
      boxes.push({ from: lo, to: hi, matrix: SL.frameOf(c) });
    });
    M.preview(boxes, 0x81d4fa, { opacity: 0.35 });
    return boxes.length;
  }
  // lang: layer2.why.{base|shell|has|tex}
  function whyText(sk) { return Object.keys(sk).map(function (k) { var l = sk[k]; return T('layer2.why.' + k, l.slice(0, 4).join(', ') + (l.length > 4 ? ' (+' + (l.length - 4) + ')' : '')); }); }
  var dlg = null;
  function open() {
    var cubes = targets();
    if (!cubes.length) { G.Blockbench.showQuickMessage(T('need.sel'), 2500); return null; }
    dlg = new G.Dialog({
      id: 'cpmplus_model_layer2', title: T('layer2'), width: 480, buttons: [T('layer2.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('layer2.tip')) + '</p>', '<div class="cmp_l2_sum"></div>'],
      form: {
        mode: { label: T('layer2.f.mode'), type: 'select', options: { copy: T('layer2.mode.copy'), clear: T('layer2.mode.clear') }, value: 'copy' },
        more: { label: T('layer2.f.more'), type: 'checkbox', value: false },
        inflate: { label: T('layer2.f.inflate'), type: 'number', value: DEF_INFLATE, min: 0.05, max: 4, step: 0.05, condition: function (f) { return !!f.more; } }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview(); dlg = null;
        try {
          var r = make(cubes, f);
          G.Blockbench.showQuickMessage(r.shells.length ? T('layer2.done', r.shells.length) : T('layer2.none'), 2200);
        } catch (e) { G.Blockbench.showMessageBox({ title: T('layer2'), message: e.message || String(e) }); }
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var n = previewOf(cubes, Math.max(0.01, Number(f.inflate) || DEF_INFLATE)), lines = [], sk = {};
      cubes.forEach(function (c) { var w = refuse(c, X.mainTexture()); if (w) (sk[w] = sk[w] || []).push(c.name); });
      lines.push('<p class="cmp_l2_big">' + L.esc(n ? T('layer2.count', n) : T('layer2.none')) + '</p>');
      whyText(sk).forEach(function (s) { lines.push('<p class="cmp_l2_note">' + L.esc(s) + '</p>'); });
      var box = dlg && dlg.object && dlg.object.querySelector('.cmp_l2_sum'); if (box) box.innerHTML = lines.join('');
      M.dialogNote(dlg, T('layer2.after'), M.allCubes().length + n, false);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('2nd layer preview failed', e); }
    return dlg;
  }
  var STYLE = 'cmp_l2_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_l2_big{font-size:1.1em;font-weight:600;margin:6px 0}\n.cmp_l2_note{opacity:.8;margin:2px 0}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_layer2', sec: 'create', icon: 'layers', key: 'layer2', mode: 'edit', cpm: true, need: 'sel', spots: ['cube', 'group'], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.plan = plan; api.make = make; api.open = open; api.dialog = function () { return dlg; }; api.isLayer = marked;
  return api;
})
