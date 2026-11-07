/*
 * CPM Plugin+ "CPM+ Modelar" - Copias ligadas: 'Criar copia ligada' on a group makes a copy that follows every later
 * change made inside the original (cubes, sizes, turns, pivots, colours, UV / painting areas, cubes added or removed)
 * until 'Desligar copia'. The copy's own folder keeps its place and turn (move / turn it freely).
 * - The follow-up is part of the same undo step as the change: while copies exist, every edit that touches the model
 *   also keeps the copies' before / after state, and the copies are brought up to date right before Blockbench closes
 *   the step (one Ctrl+Z gives both back, nothing is ever changed outside the history).
 * - The link lives in plugin-owned data only: the copy folder's cpm_data extra 'linked' (= the CPM store id of the
 *   original, the id the export writes: its 'sid' or the exporter's stable id of its uuid) and, for the .cpmproject,
 *   a file 'tags/cpmplus_links.json' kept through the project's raw files (CPM reads only tags/items|blocks|entities|
 *   biomes.json, the game never sees it; CPM+ keeps every tags/ file on import and writes it back on export). After
 *   an import the extras are put back from that file.
 * Loaded after the modelling engine.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var RAW = 'tags/cpmplus_links.json', KEY = 'linked';
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];

  // =================================================================== ids (the same numbers the export writes)
  var B = typeof BigInt === 'function' ? BigInt : null;
  /** BlockbenchExport.stableId: 31-hash of the uuid in 64 bits, the low 53 bits, at least 1000 */
  function stableId(s) {
    if (!s || !B) return 0;
    var h = B('1125899906842597'), mask = (B(1) << B(64)) - B(1), k = B(31);
    for (var i = 0; i < s.length; i++) h = (k * h + B(s.charCodeAt(i))) & mask;
    var v = Number(h & ((B(1) << B(53)) - B(1)));
    return v < 1000 ? v + 1000 : v;
  }
  function data(n) { try { var d = n.cpm_data ? JSON.parse(n.cpm_data) : {}; return d && typeof d === 'object' ? d : {}; } catch (e) { return {}; } }
  /** the store id a group gets in the exported file */
  function idOf(g) { var d = data(g), s = d.sid !== undefined && d.sid !== null ? Number(d.sid) : 0; return s ? s : stableId(g.uuid); }
  function linkOf(g) { var v = data(g)[KEY]; return v !== undefined && v !== null && v !== '' ? Number(v) : 0; }
  function setLink(g, id) { var d = data(g); if (id) d[KEY] = id; else delete d[KEY]; g.cpm_data = Object.keys(d).length ? JSON.stringify(d) : ''; }
  function groups() { return (G.Group && G.Group.all ? G.Group.all : []).slice(); }
  function byId() { var m = {}; groups().forEach(function (g) { var k = idOf(g); if (!m[k]) m[k] = g; }); return m; }
  /** the linked copies: [{copy, orig}] (orig null when it is not in the project now) */
  function links() {
    var out = [], map = null;
    groups().forEach(function (g) { var l = linkOf(g); if (!l) return; if (!map) map = byId(); out.push({ copy: g, orig: map[l] && map[l] !== g ? map[l] : null, id: l }); });
    return out;
  }
  function inside(n, top) { for (var p = n; p && p !== 'root'; p = p.parent) if (p === top) return true; return false; }

  // =================================================================== bringing a copy up to date
  function stripData(n) { var d = data(n); delete d.sid; delete d[KEY]; var k = Object.keys(d).sort(), o = {}; k.forEach(function (x) { o[x] = d[x]; }); return JSON.stringify(o); }
  /** what a folder holds, relative to its pivot (the copy must hold the same) */
  function sig(top) {
    var o = top.origin;
    function rel(p) { return [M.snap(p[0] - o[0]), M.snap(p[1] - o[1]), M.snap(p[2] - o[2])]; }
    function walk(n) {
      if (M.isGroup(n)) return ['G', n.name, rel(n.origin), n.rotation.slice(), n.visibility !== false, !!n.cpm_hidden, n.cpm_copy_transform || '', stripData(n), (n.children || []).map(walk)];
      if (M.isCube(n)) return ['C', n.name, rel(n.from), rel(n.to), rel(n.origin), n.rotation.slice(), n.inflate || 0, !!n.box_uv, n.uv_offset ? n.uv_offset.slice() : null, !!n.mirror_uv,
        n.cpm_recolor, !!n.cpm_glow, !!n.cpm_extrude, n.visibility !== false, n.export !== false, n.color, stripData(n), FACES.map(function (f) { var fc = n.faces && n.faces[f]; return fc ? [fc.uv ? fc.uv.slice() : null, fc.rotation || 0, fc.texture === undefined ? null : fc.texture] : null; })];
      return ['X', String(n.name || ''), String(n.type || '')];
    }
    return JSON.stringify((top.children || []).map(walk));
  }
  function kind(n) { return M.isGroup(n) ? 'G' : M.isCube(n) ? 'C' : 'X'; }
  function sameShape(a, b) {
    if (a.length !== b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (kind(a[i]) !== kind(b[i])) return false;
      if (kind(a[i]) === 'G' && !sameShape(a[i].children || [], b[i].children || [])) return false;
    }
    return true;
  }
  function plus(p, d) { return [M.snap(p[0] + d[0]), M.snap(p[1] + d[1]), M.snap(p[2] + d[2])]; }
  function copyData(src, dst) { var s = data(src), d = data(dst); delete s[KEY]; if (d.sid !== undefined) s.sid = d.sid; else delete s.sid; dst.cpm_data = Object.keys(s).length ? JSON.stringify(s) : ''; }
  function copyProps(s, t, d) {
    t.name = s.name;
    t.origin = plus(s.origin, d); t.rotation = s.rotation.slice();
    if (s.visibility !== undefined) t.visibility = s.visibility;
    if (M.isGroup(s)) {
      t.cpm_hidden = s.cpm_hidden; t.cpm_copy_transform = s.cpm_copy_transform;
      copyData(s, t);
      return;
    }
    t.from = plus(s.from, d); t.to = plus(s.to, d);
    t.inflate = s.inflate; t.box_uv = s.box_uv; t.mirror_uv = s.mirror_uv; t.export = s.export;
    if (s.uv_offset) t.uv_offset = s.uv_offset.slice();
    if (s.autouv !== undefined) t.autouv = s.autouv;
    if (s.color !== undefined) t.color = s.color;
    t.cpm_recolor = s.cpm_recolor; t.cpm_glow = s.cpm_glow; t.cpm_extrude = s.cpm_extrude;
    FACES.forEach(function (f) { var a = s.faces && s.faces[f], b = t.faces && t.faces[f]; if (!a || !b) return; b.uv = a.uv ? a.uv.slice() : b.uv; b.rotation = a.rotation || 0; b.texture = a.texture; });
    copyData(s, t);
  }
  /** the copy's content made equal to the original's (properties in place; rebuilt where the shape changed) */
  function syncKids(o, c, d) {
    var ok = o.children || [], ck = c.children || [];
    if (!sameShape(ok, ck)) {
      ck.slice().reverse().forEach(function (k) { try { k.remove(); } catch (e) { } });
      ok.slice().forEach(function (k) {
        var x = k.duplicate(); if (!x) return;
        x.addTo(c);
        if (M.isGroup(x)) M.shiftGroup(x, d); else if (M.isCube(x)) M.shiftCube(x, d); else if (x.origin) for (var i = 0; i < 3; i++) x.origin[i] += d[i];
        M.forgetIds(x);
        if (M.isGroup(x)) M.groupsOf([x]).forEach(function (g) { if (linkOf(g)) setLink(g, 0); });
        try { if (typeof x.init === 'function' && !x.mesh) x.init(); } catch (e) { }
      });
      // Blockbench numbers the names of copies (pena_1 -> pena_2): same shape now, so the names (and the rest) follow
      if (sameShape(ok, c.children || [])) syncKids(o, c, d);
      return;
    }
    for (var i = 0; i < ok.length; i++) {
      if (kind(ok[i]) === 'X') continue;
      copyProps(ok[i], ck[i], d);
      if (kind(ok[i]) === 'G') syncKids(ok[i], ck[i], d);
    }
  }
  /** brings every linked copy up to date -> how many changed */
  function syncAll() {
    var n = 0;
    links().forEach(function (l) {
      if (!l.orig || inside(l.orig, l.copy) || inside(l.copy, l.orig)) return;
      if (sig(l.orig) === sig(l.copy)) return;
      var d = [l.copy.origin[0] - l.orig.origin[0], l.copy.origin[1] - l.orig.origin[1], l.copy.origin[2] - l.orig.origin[2]];
      syncKids(l.orig, l.copy, d);
      n++;
    });
    if (n) M.refresh();
    return n;
  }

  // =================================================================== undo: the copies are part of every model edit
  var origInit = null, origFinish = null, busy = false;
  // what initEdit added to the step: the same parts go into the closing state when they still exist (a part kept in
  // the opening state but missing from the closing one would read as deleted on redo)
  var pending = null;
  function touchesModel(a) { return !!(a && (a.elements || a.outliner || a.groups || a.group)); }
  function aliveC(c) { return M.allCubes().indexOf(c) >= 0; }
  function aliveG(g) { return groups().indexOf(g) >= 0; }
  function widen(a, extra) {
    if (!touchesModel(a)) return a;
    var ls = links(); if (!ls.length && !(extra && (extra.els.length || extra.grs.length))) return a;
    var o = Object.assign({}, a), els = (a.elements || []).slice(), grs = (a.groups || []).slice(), addE = [], addG = [];
    function pushE(c) { if (els.indexOf(c) < 0) { els.push(c); addE.push(c); } }
    function pushG(g) { if (grs.indexOf(g) < 0) { grs.push(g); addG.push(g); } }
    if (extra) { extra.els.filter(aliveC).forEach(pushE); extra.grs.filter(aliveG).forEach(pushG); }
    ls.forEach(function (l) { M.cubesOf([l.copy]).forEach(pushE); M.groupsOf([l.copy]).forEach(pushG); });
    o.elements = els; o.groups = grs; o.outliner = true;
    o.__cpmplusLinked = { els: addE, grs: addG };
    return o;
  }
  var hooked = null;
  function hookUndo() {
    // every project has its own undo system (the global Undo is the open project's): the class is patched
    var U = G.UndoSystem && G.UndoSystem.prototype && typeof G.UndoSystem.prototype.initEdit === 'function' ? G.UndoSystem.prototype : G.Undo;
    if (!U || origInit) return;
    hooked = U;
    origInit = U.initEdit; origFinish = U.finishEdit;
    U.initEdit = function (aspects) {
      var args = Array.prototype.slice.call(arguments);
      pending = null;
      try {
        restore();
        var w = widen(aspects);
        if (w !== aspects) { pending = w.__cpmplusLinked; delete w.__cpmplusLinked; args[0] = w; }
      } catch (e) { L.warn('linked copies: initEdit', e); }
      return origInit.apply(this, args);
    };
    U.finishEdit = function (action, aspects) {
      var args = Array.prototype.slice.call(arguments), extra = pending;
      pending = null;
      // no closing aspects: Blockbench closes with the opening ones (only the parts that still exist)
      var base = aspects;
      if (!base) {
        var cs = this.current_save && this.current_save.aspects;
        if (cs) { base = Object.assign({}, cs); if (cs.elements) base.elements = cs.elements.filter(aliveC); if (cs.groups) base.groups = cs.groups.filter(aliveG); }
      }
      if (!busy && base && (touchesModel(base) || extra)) {
        busy = true;
        try {
          syncAll();
          var w = widen(base, extra);
          if (w !== base) { delete w.__cpmplusLinked; args[1] = w; }
        } catch (e) { L.warn('linked copies: could not follow the original', e); }
        finally { busy = false; }
      }
      var r = origFinish.apply(this, args);
      try { writeMirror(); } catch (e) { L.warn('linked copies: could not save the links', e); }
      return r;
    };
    L.onUninstall(function () { if (hooked) { hooked.initEdit = origInit; hooked.finishEdit = origFinish; } hooked = null; origInit = origFinish = null; });
  }

  // =================================================================== the links in the .cpmproject (raw file)
  function b64(s) { return btoa(unescape(encodeURIComponent(s))); }
  function unb64(s) { try { return decodeURIComponent(escape(atob(s))); } catch (e) { return ''; } }
  function projectData() { if (!G.Project) return null; try { return G.Project.cpm_data ? JSON.parse(G.Project.cpm_data) : {}; } catch (e) { return null; } }
  function readMirror() {
    var pd = projectData(), r = pd && pd.raw && pd.raw[RAW]; if (!r) return null;
    try { var j = JSON.parse(unb64(r)); return j && Array.isArray(j.links) ? j : null; } catch (e) { return null; }
  }
  /** writes the links [[copy id, original id]] into the project's raw files (nothing when unchanged) */
  function writeMirror() {
    if (!M.isCpm()) return;
    // a project opened from a file whose links are not back on the folders yet: never overwrite them
    if (!settled()) return;
    var list = links().map(function (l) { return [idOf(l.copy), l.id]; }).sort(function (a, b) { return a[0] - b[0]; });
    // no link and no saved links: nothing to read (the project data can be big)
    if (!list.length && !rawPresent()) return;
    var pd = projectData(); if (!pd) return;
    var raw = pd.raw && typeof pd.raw === 'object' ? pd.raw : {}, cur = raw[RAW] || null, next = list.length ? b64(JSON.stringify({ v: 1, links: list })) : null;
    if (cur === next) return;
    if (next) raw[RAW] = next; else delete raw[RAW];
    if (Object.keys(raw).length) pd.raw = raw; else delete pd.raw;
    G.Project.cpm_data = JSON.stringify(pd);
    // what this session wrote is what the folders hold
    restored[G.Project.uuid] = true;
  }
  /** after an import the folder extras are gone: they come back from the raw file (once per project) */
  var restored = {};
  function rawPresent() { var raw = G.Project && G.Project.cpm_data; return !!raw && raw.indexOf('cpmplus_links') >= 0; }
  /** true when the project has no saved links or they are back on its folders */
  function settled() { return !G.Project || !rawPresent() || !!restored[G.Project.uuid]; }
  function restore() {
    if (!G.Project || !rawPresent() || restored[G.Project.uuid]) return 0;
    var j = readMirror(); if (!j) { restored[G.Project.uuid] = true; return 0; }
    // only once the project has its parts (an import fills it after it opens)
    if (!groups().length) return 0;
    var map = byId(), n = 0;
    j.links.forEach(function (p) { var g = map[Number(p[0])]; if (g && !linkOf(g)) { setLink(g, Number(p[1])); n++; } });
    restored[G.Project.uuid] = true;
    return n;
  }

  // =================================================================== the actions
  function selGroup() { var g = M.selGroups()[0]; return g && g.parent !== 'root' ? g : null; }
  function link(g) {
    g = g || selGroup();
    if (!g) throw new Error(T('link.need'));
    var src = linkOf(g) ? (byId()[linkOf(g)] || g) : g, id = linkOf(g) || idOf(g);
    var copy = null, cubes = [], grs = [];
    M.edit(T('link'), { outliner: true, elements: [], groups: [] }, function () {
      copy = M.duplicateNode(g);
      copy.name = T('link.name', src.name);
      M.groupsOf([copy]).forEach(function (x) { if (x !== copy && linkOf(x)) setLink(x, 0); });
      setLink(copy, id);
      cubes = M.cubesOf([copy]); grs = M.groupsOf([copy]);
    }, function () { return { outliner: true, elements: cubes, groups: grs }; });
    M.refresh();
    M.selectNodes([copy]);
    return copy;
  }
  function unlink(g) {
    g = g || M.selGroups().filter(function (x) { return linkOf(x); })[0];
    if (!g || !linkOf(g)) throw new Error(T('unlink.need'));
    M.edit(T('unlink'), { groups: [g] }, function () { setLink(g, 0); });
    M.refresh();
    return g;
  }
  function onEvent() { try { restore(); writeMirror(); } catch (e) { } }
  api.install = function () {
    hookUndo();
    try {
      ['undo', 'redo', 'select_project'].forEach(function (ev) { G.Blockbench.on(ev, onEvent); });
      L.onUninstall(function () { ['undo', 'redo', 'select_project'].forEach(function (ev) { G.Blockbench.removeListener(ev, onEvent); }); });
    } catch (e) { }
    M.register({ id: 'cpmplus_model_link', sec: 'create', icon: 'link', key: 'link', mode: 'edit', need: function () { return !!selGroup(); }, spots: ['group'],
      run: function () { try { link(); G.Blockbench.showQuickMessage(T('link.done'), 2500); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 2500); } } });
    M.register({ id: 'cpmplus_model_unlink', sec: 'create', icon: 'link_off', key: 'unlink', mode: 'edit', need: function () { return M.selGroups().some(function (x) { return linkOf(x); }); }, spots: ['group'],
      run: function () { try { unlink(); G.Blockbench.showQuickMessage(T('unlink.done'), 2200); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 2500); } } });
  };
  api.uninstall = function () { };
  api.link = link; api.unlink = unlink; api.links = links; api.syncAll = syncAll; api.restore = function () { if (G.Project) delete restored[G.Project.uuid]; return restore(); };
  api.idOf = idOf; api.stableId = stableId; api.readMirror = readMirror; api.writeMirror = writeMirror; api.linkOf = linkOf; api.sig = sig;
  return api;
})
