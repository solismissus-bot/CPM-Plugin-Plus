/*
 * CPM Plugin+ "CPM+ Modelar" - Testar junta: the selected group swings around its pivot in the viewport for a few
 * seconds (X, then Y, then Z, twice; click again to stop) so you see whether the pivot is in the right place and
 * whether the pieces come apart at the joint. The pivot is marked (yellow ball + axes) and the faces where the part
 * touches its neighbours get a red patch that only shows when a gap opens there. A small box over the viewport says
 * how much the middle of the joint comes loose (the pivot is off when it does) and how much the corners open; when the
 * pivot looks off it suggests where it should be. Nothing in the model changes: only the 3D view moves, no undo step.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var AMP = 30, SEG = 1.2, ROUNDS = 2, EPS = 0.02, OK_GAP = 0.25;
  var cur = null, last = null;

  // =================================================================== where the part touches the rest of the model
  /**
   * contacts between the cubes inside g and every other cube (model space, rest pose): [{region (Box3), other (Box3),
   * thin (axis index or -1 for an overlap), patch (Box3 drawn red)}]
   */
  function contacts(g) {
    var mine = M.cubesOf([g]), others = M.allCubes().filter(function (c) { return mine.indexOf(c) < 0 && c.visibility !== false; });
    var A = mine.filter(function (c) { return c.visibility !== false; }).map(function (c) { return M.cubeBox(c); });
    var B = others.map(function (c) { return M.cubeBox(c); }), out = [];
    A.forEach(function (a) {
      var ae = a.clone().expandByScalar(EPS);
      B.forEach(function (b) {
        var be = b.clone().expandByScalar(EPS);
        if (!ae.intersectsBox(be)) return;
        var r = ae.clone().intersect(be), s = r.getSize(M.V3(0, 0, 0)), dims = [s.x, s.y, s.z];
        if (Math.min(dims[0], dims[1], dims[2]) <= 0) return;
        // only faces that touch (one thin axis) or parts that overlap; an edge / a corner touching is not a joint
        var flat = dims.map(function (x) { return x <= 2 * EPS + 0.06; }), nflat = flat.filter(Boolean).length;
        if (nflat >= 2) return;
        var thin = flat.indexOf(true), area = thin >= 0 ? dims[(thin + 1) % 3] * dims[(thin + 2) % 3] : dims[0] * dims[1] * dims[2] / Math.min(dims[0], dims[1], dims[2]);
        var patch = r.clone(), ax = ['x', 'y', 'z'];
        // in the plane: a bit smaller than the touching area, so at rest it hides inside the pieces
        for (var i = 0; i < 3; i++) {
          if (i === thin) { var mid = (r.min[ax[i]] + r.max[ax[i]]) / 2; patch.min[ax[i]] = mid - 0.05; patch.max[ax[i]] = mid + 0.05; }
          else { patch.min[ax[i]] += 0.03; patch.max[ax[i]] -= 0.03; }
        }
        if (patch.isEmpty()) return;
        out.push({ region: r, other: b, thin: thin, patch: patch, area: area });
      });
    });
    return out;
  }
  /** the joint itself: the biggest touching / overlapping area */
  function mainContact(cs) { var m = null; cs.forEach(function (c) { if (!m || c.area > m.area) m = c; }); return m; }
  /** the middle of the joint (a good pivot), or null */
  function jointCentre(cs) {
    var m = mainContact(cs); if (!m) return null;
    var p = m.region.getCenter(M.V3(0, 0, 0));
    return [M.snap(Math.round(p.x * 16) / 16), M.snap(Math.round(p.y * 16) / 16), M.snap(Math.round(p.z * 16) / 16)];
  }
  /**
   * how far (px) the middle of the joint (the biggest contact) and the corners of every contact come away from the
   * neighbour for a model-space turn R about P
   */
  function gaps(cs, P, R) {
    var mid = 0, edge = 0, v = M.V3(0, 0, 0), main = mainContact(cs);
    cs.forEach(function (c) {
      var r = c.region, pts = [r.getCenter(M.V3(0, 0, 0))];
      for (var k = 0; k < 8; k++) pts.push(M.V3(k & 1 ? r.max.x : r.min.x, k & 2 ? r.max.y : r.min.y, k & 4 ? r.max.z : r.min.z));
      pts.forEach(function (p, i) {
        v.copy(p).sub(P).applyQuaternion(R).add(P);
        var d = c.other.distanceToPoint(v);
        if (i === 0) { if (c === main) mid = Math.max(mid, d); } else edge = Math.max(edge, d);
      });
    });
    return { mid: mid, edge: edge };
  }

  // =================================================================== the test
  function overlay(g, cs, P) {
    var T3 = G.THREE, grp = new T3.Group(); grp.name = 'cpmplus_model_joint';
    var own = grp.userData.own = [];
    function mat(m) { own.push(m); return m; }
    var red = mat(new T3.MeshBasicMaterial({ color: 0xff2a2a, polygonOffset: true, polygonOffsetFactor: -1, polygonOffsetUnits: -1 }));
    cs.forEach(function (c) {
      if (c.thin < 0) return;
      var s = c.patch.getSize(M.V3(0, 0, 0)), m = new T3.Mesh(new T3.BoxGeometry(s.x, s.y, s.z), red);
      c.patch.getCenter(m.position); m.name = 'cpmplus_joint_gap'; grp.add(m);
    });
    var ball = new T3.Mesh(new T3.SphereGeometry(0.45, 12, 8), mat(new T3.MeshBasicMaterial({ color: 0xffcc00, depthTest: false, transparent: true })));
    ball.position.copy(P); ball.renderOrder = 999; ball.name = 'cpmplus_joint_pivot'; grp.add(ball);
    var pos = [], col = [], L3 = 2.5;
    [[1, 0, 0, 0xff4040], [0, 1, 0, 0x40ff40], [0, 0, 1, 0x4080ff]].forEach(function (a) {
      pos.push(P.x - a[0] * L3, P.y - a[1] * L3, P.z - a[2] * L3, P.x + a[0] * L3, P.y + a[1] * L3, P.z + a[2] * L3);
      var cc = new T3.Color(a[3]); col.push(cc.r, cc.g, cc.b, cc.r, cc.g, cc.b);
    });
    var geo = new T3.BufferGeometry(); geo.setAttribute('position', new T3.Float32BufferAttribute(pos, 3)); geo.setAttribute('color', new T3.Float32BufferAttribute(col, 3));
    var lines = new T3.LineSegments(geo, mat(new T3.LineBasicMaterial({ vertexColors: true, depthTest: false, transparent: true })));
    lines.renderOrder = 999; grp.add(lines);
    var root = (G.Project && G.Project.model_3d) || null; if (root) root.add(grp);
    return grp;
  }
  function dropOverlay(grp) {
    if (!grp) return;
    try { if (grp.parent) grp.parent.remove(grp); } catch (e) { }
    grp.traverse(function (o) { try { if (o.geometry) o.geometry.dispose(); } catch (e) { } });
    (grp.userData.own || []).forEach(function (m) { try { m.dispose(); } catch (e) { } });
  }
  function render() { try { if (G.Preview && G.Preview.all) G.Preview.all.forEach(function (p) { if (p.render) p.render(); }); } catch (e) { } }
  function hostNode() { var p = G.Preview && G.Preview.selected; return p ? p.node || (p.canvas && p.canvas.parentElement) : null; }
  function hud(text, done) {
    var host = hostNode(); if (!host) return;
    var el = host.querySelector('.cmp_joint_hud');
    if (!el) {
      el = document.createElement('div'); el.className = 'cmp_joint_hud';
      el.innerHTML = '<div class="t"></div><button type="button"></button>';
      el.querySelector('button').addEventListener('click', function (e) { e.stopPropagation(); if (cur) stop('button'); else removeHud(); });
      host.appendChild(el);
    }
    el.querySelector('.t').textContent = text;
    el.querySelector('button').textContent = done ? T('joint.close') : T('joint.stop');
  }
  function removeHud() { var host = hostNode(), el = host && host.querySelector('.cmp_joint_hud'); if (el) el.remove(); }
  function axisName(k) { return ['X', 'Y', 'Z'][k]; }
  function start(g) {
    if (cur) stop('restart');
    if (!g || !g.mesh) { G.Blockbench.showQuickMessage(T('need.groups'), 2500); return null; }
    var cs = contacts(g), P = M.pivotOf(g), PQ = M.parentQuat(g);
    cur = {
      g: g, q0: g.mesh.quaternion.clone(), cs: cs, P: P, PQ: PQ, PQi: PQ.clone().invert(), t0: null, raf: 0,
      mid: 0, edge: 0, grp: overlay(g, cs, P), centre: jointCentre(cs), axis: 0
    };
    hud(T('joint.running', g.name, axisName(0)));
    cur.raf = requestAnimationFrame(frame);
    return cur;
  }
  function frame(now) {
    var r = cur; if (!r) return;
    // the clock starts at the first frame (a frame's time stamp can be older than the click)
    if (r.t0 === null) r.t0 = now;
    var t = Math.max(0, (now - r.t0) / 1000);
    if (t >= SEG * 3 * ROUNDS) { stop('done'); return; }
    var k = Math.floor(t / SEG) % 3, u = (t % SEG) / SEG, ang = Math.sin(u * Math.PI * 2) * AMP;
    var axis = [M.V3(1, 0, 0), M.V3(0, 1, 0), M.V3(0, 0, 1)][k], dq = new G.THREE.Quaternion().setFromAxisAngle(axis, ang * M.DEG);
    try {
      r.g.mesh.quaternion.copy(dq).multiply(r.q0);
      // the same turn in model space (the axes are the parent's): R = PQ * dq * PQ^-1
      var R = r.PQ.clone().multiply(dq).multiply(r.PQi), gp = gaps(r.cs, r.P, R);
      r.mid = Math.max(r.mid, gp.mid); r.edge = Math.max(r.edge, gp.edge);
    } catch (e) { L.warn('joint test frame failed', e); stop('error'); return; }
    if (k !== r.axis) { r.axis = k; hud(T('joint.running', r.g.name, axisName(k))); }
    render();
    r.raf = requestAnimationFrame(frame);
  }
  /** ends the test: the part goes back exactly where it was; the box says what was seen */
  function stop(why) {
    var r = cur; if (!r) return null;
    cur = null;
    try { cancelAnimationFrame(r.raf); } catch (e) { }
    try { r.g.mesh.quaternion.copy(r.q0); r.g.mesh.updateMatrixWorld(true); } catch (e) { }
    dropOverlay(r.grp);
    render();
    var res = { group: r.g.name, contacts: r.cs.length, mid: M.snap(Math.round(r.mid * 100) / 100), edge: M.snap(Math.round(r.edge * 100) / 100), pivot: r.g.origin.slice(), suggest: null, why: why };
    var p = M.pivotOf(r.g);
    if (r.centre && r.mid > OK_GAP && (Math.abs(p.x - r.centre[0]) + Math.abs(p.y - r.centre[1]) + Math.abs(p.z - r.centre[2]) > 0.25)) {
      // in the numbers of the group's own pivot field (its parents' frame)
      var sp = M.toParentFrame(M.V3(r.centre[0], r.centre[1], r.centre[2]), r.g);
      res.suggest = [M.snap(sp.x), M.snap(sp.y), M.snap(sp.z)];
    }
    last = res;
    if (why === 'mode' || why === 'project' || why === 'uninstall') { removeHud(); return res; }
    var text = !res.contacts ? T('joint.alone', res.group) : res.mid <= OK_GAP ? T('joint.ok', res.group, M.num(res.edge), AMP) : T('joint.loose', res.group, M.num(res.mid), AMP);
    if (res.suggest) text += ' ' + T('joint.suggest', res.suggest.join(', '));
    hud(text, true);
    return res;
  }
  function toggle() {
    if (cur) return stop('button');
    removeHud();
    return start(M.selGroups()[0]);
  }
  var STYLE = 'cmp_joint_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_joint_hud{position:absolute;left:10px;top:10px;z-index:5;max-width:min(440px,70%);background:var(--color-ui);border:1px solid var(--color-accent);border-radius:4px;padding:8px 10px;font-size:.95em;box-shadow:0 2px 8px rgba(0,0,0,.35)}\n' +
        '.cmp_joint_hud .t{white-space:pre-line;margin-bottom:6px}\n.cmp_joint_hud button{min-width:80px}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var onMode = function () { if (cur) stop('mode'); }, onProject = function () { if (cur) stop('project'); removeHud(); }, onUndo = function () { if (cur) stop('undo'); };
    try {
      G.Blockbench.on('select_mode', onMode); G.Blockbench.on('select_project', onProject); G.Blockbench.on('undo', onUndo); G.Blockbench.on('redo', onUndo);
      L.onUninstall(function () { G.Blockbench.removeListener('select_mode', onMode); G.Blockbench.removeListener('select_project', onProject); G.Blockbench.removeListener('undo', onUndo); G.Blockbench.removeListener('redo', onUndo); });
    } catch (e) { }
    M.register({ id: 'cpmplus_model_joint', sec: 'check', icon: 'sync', key: 'joint', mode: 'edit', need: 'groups', spots: ['group'], run: function () { toggle(); } });
  };
  api.uninstall = function () { if (cur) stop('uninstall'); removeHud(); };
  api.start = start; api.stop = stop; api.toggle = toggle; api.contacts = contacts; api.gaps = gaps; api.jointCentre = jointCentre;
  api.running = function () { return cur; }; api.last = function () { return last; };
  return api;
})
