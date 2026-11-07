/*
 * CPM Plugin+ "CPM+ Modelar" - Conferir modelo: a list of modelling problems, each with 'Mostrar' (selects the parts)
 * and 'Corrigir' (one small change, one Ctrl+Z), and 'Corrigir tudo' (every fix in one Ctrl+Z):
 * - faces in the same place (z-fighting: they flicker in game): the smaller part moves out a hair (inflate +0.02);
 *   two faces of the same colour cube colour do not flicker and are left alone
 * - hairline gaps between parts that touch (a seam line): the smaller part is moved to close it
 * - paintings that mix (texture areas of two parts that cross each other by accident; identical / nested areas are
 *   kept, they are shared on purpose): the smaller part gets its own free area with the same pixels
 * - empty groups (no cube inside; body parts, animated groups, groups with CPM data or a copy-transform link stay)
 * - cubes without size (invisible), the same cube twice in the same place, cubes hidden inside an opaque cube of
 *   the same group: deleted
 * The export warnings (format limits, ghost parts, half-pixel box UV, loose cubes ...) stay where they are.
 * Loaded after the modelling engine and model_texture.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T, X = L.core.modules.model_texture;
  var api = {};
  var FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
  var NORMAL = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
  var ZF_INFLATE = 0.02, GAP_MAX = 0.06, AREA_MIN = 0.01, EPS = 1e-3;
  // lang: check.kind.{zfight|gap|uv|empty|zero|dup|inside}
  var KINDS = ['zero', 'dup', 'inside', 'empty', 'zfight', 'gap', 'uv'];
  var ICONS = { zfight: 'flare', gap: 'vertical_align_center', uv: 'texture', empty: 'folder_off', zero: 'crop_square', dup: 'content_copy', inside: 'visibility_off' };

  // =================================================================== helpers
  function shown(c) { return c.visibility !== false && c.export !== false; }
  function recolour(c) { return typeof c.cpm_recolor === 'number' && c.cpm_recolor !== -1 ? c.cpm_recolor & 0xFFFFFF : null; }
  function textured(fc) { return !!fc && fc.texture !== false && fc.texture !== null && fc.texture !== undefined; }
  function colourCube(c) { return recolour(c) !== null && FACES.every(function (f) { return !textured(c.faces[f]); }); }
  function alive(c) { return M.allCubes().indexOf(c) >= 0; }
  function size(c, i) { return Math.abs(c.to[i] - c.from[i]); }
  function volume(c) { var inf = 2 * (c.inflate || 0); return (size(c, 0) + inf) * (size(c, 1) + inf) * (size(c, 2) + inf); }
  function nm(n) { return String(n && n.name || '?'); }
  /** the turn of a cube's frame (its groups, then itself) */
  function frameQuat(c) { var q = M.parentQuat(c), own = M.nodeQuat(c); if (own) q.multiply(own); return q; }
  /** the faces of a cube in model space when it sits square to the axes (turns of 90 degrees allowed), else null */
  function squareFaces(c) {
    var q = frameQuat(c), map = {};
    for (var i = 0; i < FACES.length; i++) {
      var n = NORMAL[FACES[i]], v = M.V3(n[0], n[1], n[2]).applyQuaternion(q), a = [Math.abs(v.x), Math.abs(v.y), Math.abs(v.z)], k = a.indexOf(Math.max.apply(null, a));
      if (a[k] < 0.9999) return null;
      map[FACES[i]] = { axis: k, dir: [v.x, v.y, v.z][k] > 0 ? 1 : -1 };
    }
    var b = M.cubeBox(c), lo = [b.min.x, b.min.y, b.min.z], hi = [b.max.x, b.max.y, b.max.z], out = [];
    FACES.forEach(function (f) {
      var fc = c.faces[f]; if (fc && fc.texture === null) return;// a hidden face is not drawn
      var m = map[f], o = [0, 1, 2].filter(function (k) { return k !== m.axis; });
      out.push({ cube: c, face: f, axis: m.axis, dir: m.dir, at: m.dir > 0 ? hi[m.axis] : lo[m.axis], r: [lo[o[0]], hi[o[0]], lo[o[1]], hi[o[1]]], look: colourCube(c) ? 'c' + recolour(c) + (c.cpm_glow ? 'g' : '') : 't' });
    });
    return out;
  }
  function overlap(a, b) { var w = Math.min(a[1], b[1]) - Math.max(a[0], b[0]), h = Math.min(a[3], b[3]) - Math.max(a[2], b[2]); return w > 0 && h > 0 ? w * h : 0; }
  function pairKey(a, b) { return a.uuid < b.uuid ? a.uuid + '|' + b.uuid : b.uuid + '|' + a.uuid; }

  // =================================================================== the checks
  function cubesInOrder() {
    var out = [];
    (G.Outliner && G.Outliner.root ? G.Outliner.root : []).forEach(function walk(n) { if (M.isCube(n)) out.push(n); else if (M.isGroup(n)) (n.children || []).forEach(walk); });
    return out;
  }
  function checkZero(cubes, out) {
    cubes.forEach(function (c) {
      if (M.isBase(c)) return;
      var flat = [0, 1, 2].filter(function (i) { return size(c, i) < 1e-4; }).length;
      if (flat >= 2) out.push({ kind: 'zero', nodes: [c], text: T('check.zero', nm(c)), fix: T('check.fix.delete', nm(c)), op: { del: [c] } });
    });
  }
  function look(c) {
    return JSON.stringify([c.box_uv, c.box_uv ? c.uv_offset : 0, c.mirror_uv, recolour(c), !!c.cpm_glow, c.cpm_extrude ? 1 : 0,
      FACES.map(function (f) { var fc = c.faces[f]; return fc ? [fc.texture, c.box_uv ? 0 : fc.uv, fc.rotation || 0] : 0; })]);
  }
  function checkDup(cubes, out, seen) {
    var groups = {};
    cubes.forEach(function (c) {
      if (M.isBase(c) || !shown(c)) return;
      var k = [c.parent && c.parent !== 'root' ? c.parent.uuid : 'root', c.from.join(), c.to.join(), c.origin.join(), c.rotation.join(), c.inflate || 0, look(c)].join('#');
      (groups[k] = groups[k] || []).push(c);
    });
    Object.keys(groups).forEach(function (k) {
      var l = groups[k]; if (l.length < 2) return;
      var extra = l.slice(1);
      extra.forEach(function (x) { seen[pairKey(l[0], x)] = 1; });
      out.push({ kind: 'dup', nodes: l, text: T('check.dup', nm(l[0]), extra.length), fix: T('check.fix.dup', extra.length), op: { del: extra } });
    });
  }
  /** every texel of a cube's painted faces is fully opaque (or it is a colour cube) */
  function opaque(c, px) {
    if (colourCube(c)) return true;
    if (!px) return false;
    var tex = X.textureOf(c); if (!tex || px.tex !== tex) return false;
    var I = X.uvInfo(tex), d = px.data;
    return X.faceRects(c, tex).every(function (f) {
      var x1 = Math.floor(f.r[0] * I.sx), y1 = Math.floor(f.r[1] * I.sy), x2 = Math.ceil(f.r[2] * I.sx), y2 = Math.ceil(f.r[3] * I.sy);
      for (var y = Math.max(0, y1); y < Math.min(d.height, y2); y++) for (var x = Math.max(0, x1); x < Math.min(d.width, x2); x++) if (d.data[(y * d.width + x) * 4 + 3] < 255) return false;
      return true;
    }) && FACES.every(function (f) { return c.faces[f] && c.faces[f].texture !== null; });
  }
  function vb(c) { var inf = c.inflate || 0, lo = [], hi = []; for (var i = 0; i < 3; i++) { lo.push(Math.min(c.from[i], c.to[i]) - inf); hi.push(Math.max(c.from[i], c.to[i]) + inf); } return { lo: lo, hi: hi }; }
  function checkInside(cubes, out, seen, px) {
    var bySib = {};
    cubes.forEach(function (c) { if (shown(c)) { var k = c.parent && c.parent !== 'root' ? c.parent.uuid : 'root'; (bySib[k] = bySib[k] || []).push(c); } });
    var gone = [];
    Object.keys(bySib).forEach(function (k) {
      var l = bySib[k];
      l.forEach(function (a) {
        if (M.isBase(a) || gone.indexOf(a) >= 0) return;
        var A = vb(a);
        for (var i = 0; i < l.length; i++) {
          var b = l[i]; if (b === a || M.isBase(b) || gone.indexOf(b) >= 0 || seen[pairKey(a, b)]) continue;
          // the same frame: both square to their group, or turned exactly alike around the same pivot
          if (b.rotation.join() !== a.rotation.join() || ((a.rotation[0] || a.rotation[1] || a.rotation[2]) && b.origin.join() !== a.origin.join())) continue;
          var B = vb(b), inn = true;
          for (var j = 0; j < 3 && inn; j++) inn = A.lo[j] > B.lo[j] + EPS && A.hi[j] < B.hi[j] - EPS;
          if (!inn || !opaque(b, px)) continue;
          gone.push(a);
          out.push({ kind: 'inside', nodes: [a, b], text: T('check.inside', nm(a), nm(b)), fix: T('check.fix.delete', nm(a)), op: { del: [a] } });
          break;
        }
      });
    });
  }
  function checkFaces(cubes, out, seen) {
    var faces = [];
    cubes.forEach(function (c) { if (shown(c)) { var f = squareFaces(c); if (f) f.forEach(function (x) { faces.push(x); }); } });
    var zf = {}, gp = {};
    // by axis, then by place along it: only faces closer than GAP_MAX are compared
    faces.sort(function (p, q) { return p.axis - q.axis || p.at - q.at; });
    for (var i = 0; i < faces.length; i++) {
      var a = faces[i];
      for (var j = i + 1; j < faces.length && faces[j].axis === a.axis && faces[j].at - a.at <= GAP_MAX; j++) {
        var b = faces[j];
        if (b.cube === a.cube) continue;
        var d = b.at - a.at;
        var area = overlap(a.r, b.r); if (area < AREA_MIN) continue;
        var key = pairKey(a.cube, b.cube);
        if (b.dir === a.dir) {
          // the same plane, looking the same way: they fight (unless both are the same flat colour)
          if (Math.abs(d) > EPS || seen[key] || (a.look === b.look && a.look !== 't')) continue;
          if (!zf[key]) zf[key] = { a: a.cube, b: b.cube, area: 0 };
          zf[key].area += area;
        } else {
          // facing each other with a hair between them: a seam line
          var lo = a.dir > 0 ? a : b, hi = a.dir > 0 ? b : a, gap = hi.at - lo.at;
          if (gap <= 1e-4 || gap > GAP_MAX || seen[key]) continue;
          if (!gp[key] || gp[key].gap > gap) gp[key] = { lo: lo.cube, hi: hi.cube, axis: a.axis, gap: gap };
        }
      }
    }
    Object.keys(zf).forEach(function (k) {
      var z = zf[k], small = pickSmall(z.a, z.b); if (!small) return;
      out.push({ kind: 'zfight', nodes: [z.a, z.b], text: T('check.zfight', nm(z.a), nm(z.b)), fix: T('check.fix.zfight', nm(small)), op: { inflate: small } });
    });
    Object.keys(gp).forEach(function (k) {
      var g = gp[k], small = pickSmall(g.lo, g.hi); if (!small) return;
      var v = [0, 0, 0]; v[g.axis] = small === g.hi ? -g.gap : g.gap;
      out.push({ kind: 'gap', nodes: [g.lo, g.hi], text: T('check.gap', nm(g.lo), nm(g.hi), M.num(g.gap, 3)), fix: T('check.fix.gap', nm(small)), op: { move: small, by: v } });
    });
  }
  /** the part a fix changes: the smaller one, never the player's own base cubes */
  function pickSmall(a, b) {
    if (M.isBase(a) && M.isBase(b)) return null;
    if (M.isBase(a)) return b; if (M.isBase(b)) return a;
    return volume(a) <= volume(b) ? a : b;
  }
  function sameRect(a, b) { return Math.abs(a[0] - b[0]) < 1e-4 && Math.abs(a[1] - b[1]) < 1e-4 && Math.abs(a[2] - b[2]) < 1e-4 && Math.abs(a[3] - b[3]) < 1e-4; }
  function inRect(a, b) { return a[0] >= b[0] - 1e-4 && a[1] >= b[1] - 1e-4 && a[2] <= b[2] + 1e-4 && a[3] <= b[3] + 1e-4; }
  function sameTemplate(a, b) { return a.box_uv && b.box_uv && a.uv_offset && b.uv_offset && a.uv_offset.join() === b.uv_offset.join() && !!a.mirror_uv === !!b.mirror_uv && [0, 1, 2].every(function (i) { return Math.abs(size(a, i) - size(b, i)) < 1e-4; }); }
  /** two parts whose painted areas cross each other (not the same area, not one inside the other) */
  function mixes(a, ra, b, rb) {
    if (a.box_uv && b.box_uv) return !sameTemplate(a, b) && ra.some(function (x) { return rb.some(function (y) { return overlap([x[0], x[2], x[1], x[3]], [y[0], y[2], y[1], y[3]]) > 0; }); });
    return ra.some(function (x) {
      return rb.some(function (y) {
        if (!(overlap([x[0], x[2], x[1], x[3]], [y[0], y[2], y[1], y[3]]) > 0)) return false;
        return !sameRect(x, y) && !inRect(x, y) && !inRect(y, x);
      });
    });
  }
  function checkUV(cubes, out) {
    var tex = X.mainTexture(); if (!tex) return;
    var list = [];
    cubes.forEach(function (c) { if (X.textureOf(c) !== tex) return; var r = X.faceRects(c, tex).map(function (f) { return f.r; }); if (r.length) list.push({ c: c, r: r }); });
    // cells of the UV grid -> parts there (only parts that share a cell are compared)
    var I = X.uvInfo(tex), W = Math.ceil(I.uvW), cells = {}, pairs = {};
    list.forEach(function (e, idx) {
      e.r.forEach(function (r) {
        for (var y = Math.max(0, Math.floor(r[1])); y < Math.ceil(r[3]); y++) for (var x = Math.max(0, Math.floor(r[0])); x < Math.ceil(r[2]); x++) {
          var k = y * W + x, l = cells[k] || (cells[k] = []);
          l.forEach(function (o) { if (o !== idx) pairs[Math.min(o, idx) + ',' + Math.max(o, idx)] = 1; });
          if (l.indexOf(idx) < 0) l.push(idx);
        }
      });
    });
    var fixed = [];
    Object.keys(pairs).forEach(function (k) {
      var ij = k.split(','), A = list[+ij[0]], B = list[+ij[1]];
      if (!mixes(A.c, A.r, B.c, B.r)) return;
      var small = pickSmall(A.c, B.c); if (!small) return;
      if (fixed.indexOf(small) >= 0) return;// one new area per part is enough
      fixed.push(small);
      out.push({ kind: 'uv', nodes: [A.c, B.c], text: T('check.uv', nm(A.c), nm(B.c)), fix: T('check.fix.uv', nm(small)), op: { area: small, tex: tex } });
    });
  }
  function hasContent(g) {
    return (g.children || []).some(function (k) { return M.isGroup(k) ? hasContent(k) : true; });
  }
  function referenced(g) {
    var hit = false;
    (G.Group.all || []).forEach(function (o) { if (o !== g && typeof o.cpm_copy_transform === 'string' && o.cpm_copy_transform.indexOf(g.uuid) >= 0) hit = true; });
    return hit;
  }
  /** a group that must stay even when it is empty: animated, CPM data (item slot, joint...), copy-transform links */
  function keep(g) {
    var anim = false;
    try { (G.Animation && G.Animation.all || []).forEach(function (a) { var an = a.animators && a.animators[g.uuid]; if (an && an.keyframes && an.keyframes.length) anim = true; }); } catch (e) { }
    if (anim || referenced(g)) return true;
    if (typeof g.cpm_copy_transform === 'string' && g.cpm_copy_transform.trim()) return true;
    if (g.cpm_data) { try { var d = JSON.parse(g.cpm_data); if (d && Object.keys(d).some(function (k) { return k !== 'sid'; })) return true; } catch (e) { return true; } }
    return (g.children || []).some(function (k) { return M.isGroup(k) && keep(k); });
  }
  function checkEmpty(out) {
    var found = [];
    (G.Group.all || []).forEach(function (g) {
      if (!g.parent || g.parent === 'root' || hasContent(g) || keep(g)) return;
      for (var p = g.parent; p && p !== 'root'; p = p.parent) if (found.indexOf(p) >= 0) return;
      found.push(g);
    });
    // a parent found after its child: keep only the top ones
    found.filter(function (g) { for (var p = g.parent; p && p !== 'root'; p = p.parent) if (found.indexOf(p) >= 0) return false; return true; }).forEach(function (g) {
      out.push({ kind: 'empty', nodes: [g], text: T('check.empty', nm(g)), fix: T('check.fix.empty', nm(g)), op: { delGroup: g } });
    });
  }
  /** every problem of the model, in the order they are fixed */
  function run() {
    if (!M.hasProject()) return [];
    var cubes = cubesInOrder(), out = [], seen = {}, px = null;
    try { var tex = X.mainTexture(); var d = tex && X.pixelsOf(tex); if (d) px = { tex: tex, data: d }; } catch (e) { }
    checkZero(cubes, out);
    var live = cubes.filter(function (c) { return [0, 1, 2].filter(function (i) { return size(c, i) < 1e-4; }).length < 2; });
    checkDup(live, out, seen);
    checkInside(live, out, seen, px);
    checkEmpty(out);
    checkFaces(live, out, seen);
    checkUV(live, out);
    out.sort(function (a, b) { return KINDS.indexOf(a.kind) - KINDS.indexOf(b.kind); });
    return out;
  }

  // =================================================================== fixes (one undo entry each, or one for all)
  function fix(issues) {
    issues = issues.filter(Boolean);
    if (!issues.length) return 0;
    var cubes = [], groups = [], uvPlans = [], tex = null, done = 0;
    function addC(c) { if (c && cubes.indexOf(c) < 0) cubes.push(c); }
    issues.forEach(function (it) {
      var o = it.op;
      (o.del || []).forEach(addC); addC(o.inflate); addC(o.move); addC(o.area);
      if (o.delGroup) { M.groupsOf([o.delGroup]).forEach(function (g) { if (groups.indexOf(g) < 0) groups.push(g); }); }
    });
    // new texture areas are planned first (no room = that fix is skipped, nothing changes for it)
    var uvIssues = issues.filter(function (it) { return it.op.area; });
    if (uvIssues.length) {
      tex = uvIssues[0].op.tex;
      var map = X.occupancy(tex);
      uvIssues.forEach(function (it) {
        try { var p = X.planAreas([{ cube: it.op.area, src: it.op.area }], tex, map); uvPlans.push({ it: it, plans: p }); }
        catch (e) { it.skipped = true; }
      });
    }
    if (!issues.some(function (it) { return !it.skipped; })) return 0;
    var px = { before: [], after: [] };
    var before = Object.assign({ outliner: true, elements: cubes.slice(), groups: groups.slice() }, uvPlans.length ? X.pixelAspects(tex) : {});
    M.edit(issues.length > 1 ? T('check.fixall') : T('check.fixone'), before, function () {
      issues.forEach(function (it) {
        var o = it.op;
        if (it.skipped) return;
        if (o.del) { o.del.forEach(function (c) { if (alive(c)) c.remove(); }); done++; }
        else if (o.delGroup) { if (o.delGroup.parent) { o.delGroup.remove(); done++; } }
        else if (o.inflate) { if (alive(o.inflate)) { o.inflate.inflate = M.snap((o.inflate.inflate || 0) + ZF_INFLATE); done++; } }
        else if (o.move) {
          if (alive(o.move)) {
            var c = o.move; M.moveBy(c, o.by);
            for (var k = 0; k < 3; k++) { c.from[k] = M.snap(c.from[k]); c.to[k] = M.snap(c.to[k]); c.origin[k] = M.snap(c.origin[k]); }
            done++;
          }
        }
      });
      var live = uvPlans.filter(function (u) { return alive(u.it.op.area); });
      if (live.length) {
        var all = []; live.forEach(function (u) { u.plans.forEach(function (p) { all.push(p); }); });
        px = X.applyAreas(all, tex); done += live.length;
      }
    }, function () { return Object.assign({ outliner: true, elements: cubes.filter(alive), groups: [] }, uvPlans.length ? X.pixelAspects(tex) : {}); });
    if (tex && px.before.length) X.bindPixels(tex, px.before, px.after);
    M.refresh();
    try { if (tex && typeof tex.updateLayerChanges === 'function') tex.updateLayerChanges(true); } catch (e) { }
    return done;
  }

  // =================================================================== the window
  var dlg = null, current = [];
  function render() {
    if (!dlg || !dlg.object) return;
    current = run();
    var box = dlg.object.querySelector('.cmp_chk_list'); if (!box) return;
    if (!current.length) { box.innerHTML = '<p class="cmp_chk_ok"><i class="material-icons">check_circle</i> ' + L.esc(T('check.none')) + '</p>'; }
    else {
      box.innerHTML = current.map(function (it, i) {
        return '<div class="cmp_chk_row" data-kind="' + it.kind + '"><i class="material-icons">' + ICONS[it.kind] + '</i><div class="cmp_chk_txt"><b>' + L.esc(T('check.kind.' + it.kind)) + '</b><span>' + L.esc(it.text) + '</span></div>' +
          '<button class="cmp_chk_show" data-i="' + i + '">' + L.esc(T('check.show')) + '</button><button class="cmp_chk_fix" data-i="' + i + '" title="' + L.esc(it.fix) + '">' + L.esc(T('check.fixbtn')) + '</button></div>';
      }).join('');
    }
    var head = dlg.object.querySelector('.cmp_chk_head');
    if (head) head.textContent = current.length ? T('check.found', current.length) : '';
  }
  function onClick(e) {
    var b = e.target && e.target.closest ? e.target.closest('button[data-i]') : null; if (!b) return;
    var it = current[+b.getAttribute('data-i')]; if (!it) return;
    if (b.classList.contains('cmp_chk_show')) { M.selectNodes(it.nodes.filter(function (n) { return M.isGroup(n) || alive(n); })); return; }
    try { var n = fix([it]); G.Blockbench.showQuickMessage(n ? T('check.fixed') : T('check.noroom'), 1800); }
    catch (x) { L.warn('check fix failed', x); G.Blockbench.showQuickMessage(T('error') + ' ' + (x && x.message || x), 3000); }
    render();
  }
  function open() {
    dlg = new G.Dialog({
      id: 'cpmplus_model_check', title: T('check'), width: 600, buttons: [T('check.fixall'), 'dialog.close'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('check.tip')) + '</p>', '<p class="cmp_chk_head"></p>', '<div class="cmp_chk_list"></div>'],
      onConfirm: function () {
        var list = run(), n = 0;
        if (list.length) { try { n = fix(list); } catch (x) { L.warn('check fix-all failed', x); G.Blockbench.showMessageBox({ title: T('check'), message: T('error') + '\n' + (x && x.message || x) }); } }
        dlg = null;
        G.Blockbench.showQuickMessage(list.length ? T('check.fixedall', n) : T('check.none'), 2200);
      },
      onCancel: function () { dlg = null; }
    });
    dlg.show();
    var box = dlg.object.querySelector('.cmp_chk_list'); if (box) box.addEventListener('click', onClick);
    render();
    return dlg;
  }
  var STYLE = 'cmp_check_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = [
        '.cmp_chk_list{max-height:380px;overflow-y:auto;margin-top:6px}',
        '.cmp_chk_head{font-weight:600;margin:4px 0}',
        '.cmp_chk_row{display:flex;align-items:center;gap:8px;padding:6px 4px;border-bottom:1px solid var(--color-border)}',
        '.cmp_chk_row>i{font-size:20px;opacity:.85}',
        '.cmp_chk_txt{flex:1;min-width:0;display:flex;flex-direction:column}',
        '.cmp_chk_txt span{opacity:.85;font-size:.95em;overflow-wrap:anywhere}',
        '.cmp_chk_row button{min-width:0;padding:2px 10px;height:28px}',
        '.cmp_chk_ok{display:flex;align-items:center;gap:6px;font-weight:600}'
      ].join('\n');
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    M.register({ id: 'cpmplus_model_check', sec: 'check', icon: 'fact_check', key: 'check', mode: 'edit', spots: [], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } };
  api.run = run; api.fix = function (issues) { return fix(issues); }; api.open = open; api.dialog = function () { return dlg; };
  return api;
})
