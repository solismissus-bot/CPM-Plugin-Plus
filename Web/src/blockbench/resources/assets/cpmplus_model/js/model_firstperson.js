/*
 * CPM Plugin+ "CPM+ Modelar" - Previa em 1a pessoa (View menu toggle + panel button): the viewport camera goes where
 * the player's eyes are (1.62 blocks = 25.92 px above the feet, the middle of the head), looking forward (the player's
 * front, -Z) with Minecraft's default field of view (70 degrees); the head, body and legs are hidden like in first
 * person, and the arm is drawn where Minecraft draws the empty main hand (the vanilla first-person arm transform of
 * ItemInHandRenderer.renderPlayerArm + the idle bob, the arm part at its pivot; everything inside the arm group comes
 * along). A box over the viewport switches between the right arm and both arms and leaves (Esc works too). Leaving
 * puts the previous camera back exactly (position, orientation, target, field of view, projection, zoom, controls)
 * and every hidden part / moved arm as it was. Only the 3D view changes: no model change, no undo step.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var EYE = 1.62 * 16, FOV = 70;
  var cur = null;

  function preview() { return G.Preview && G.Preview.selected; }
  function arms() { var o = {}; (G.Group ? G.Group.all : []).forEach(function (g) { if (g.parent === 'root' && (g.name === 'right_arm' || g.name === 'left_arm')) o[g.name] = g; }); return o; }
  /** the first-person arm transform (Minecraft, camera space in blocks), then the arm part at its pivot, in px */
  function armMatrix(g, right, camWorld) {
    var T3 = G.THREE, f = right ? 1 : -1, D = Math.PI / 180, H = new T3.Matrix4();
    function m(x) { H.multiply(x); }
    m(new T3.Matrix4().makeTranslation(f * 0.64000005, -0.6, -0.71999997));
    m(new T3.Matrix4().makeRotationY(f * 45 * D));
    m(new T3.Matrix4().makeTranslation(f * -1.0, 3.6, 3.5));
    m(new T3.Matrix4().makeRotationZ(f * 120 * D));
    m(new T3.Matrix4().makeRotationX(200 * D));
    m(new T3.Matrix4().makeRotationY(f * -135 * D));
    m(new T3.Matrix4().makeTranslation(f * 5.6, 0, 0));
    // the arm part: its pivot in Minecraft model units (x mirrored, y down from the neck), the idle bob (zRot +-0.1)
    m(new T3.Matrix4().makeTranslation(-g.origin[0] / 16, (24 - g.origin[1]) / 16, g.origin[2] / 16));
    m(new T3.Matrix4().makeRotationZ(f * 0.1));
    // Blockbench px with Y up and +X = the player's right -> Minecraft model units
    m(new T3.Matrix4().makeScale(-1 / 16, -1 / 16, 1 / 16));
    return new T3.Matrix4().copy(camWorld).multiply(new T3.Matrix4().makeScale(16, 16, 16)).multiply(H);
  }
  function placeArms(both) {
    var r = cur; if (!r) return;
    var a = arms(), cam = r.p.camera;
    cam.updateMatrixWorld(true);
    ['right_arm', 'left_arm'].forEach(function (name) {
      var g = a[name]; if (!g || !g.mesh) return;
      var show = name === 'right_arm' || both;
      g.mesh.visible = show;
      if (!show) return;
      var parent = g.mesh.parent; if (parent) parent.updateMatrixWorld(true);
      var W = armMatrix(g, name === 'right_arm', cam.matrixWorld);
      var local = parent ? new G.THREE.Matrix4().copy(parent.matrixWorld).invert().multiply(W) : W;
      g.mesh.matrixAutoUpdate = false; g.mesh.matrix.copy(local); g.mesh.updateMatrixWorld(true);
    });
    r.both = !!both;
    render(); hud();
  }
  function render() { try { if (G.Preview && G.Preview.all) G.Preview.all.forEach(function (p) { if (p.render) p.render(); }); } catch (e) { } }

  // =================================================================== on / off
  function start(both) {
    if (cur) return cur;
    var p = preview(); if (!p || !p.camera || !p.controls) return null;
    var a = arms();
    if (!a.right_arm) { G.Blockbench.showQuickMessage(T('fpview.noarm'), 3000); return null; }
    var cam = p.camera, saved = {
      ortho: !!p.isOrtho, pos: cam.position.clone(), quat: cam.quaternion.clone(), up: cam.up.clone(), zoom: cam.zoom,
      fov: p.camPers ? p.camPers.fov : cam.fov, target: p.controls.target.clone(), enabled: p.controls.enabled, vis: [], arms: []
    };
    cur = { p: p, saved: saved, both: false };
    // hide everything at the top of the outliner but the arms (first person draws only the hand)
    (G.Outliner && G.Outliner.root ? G.Outliner.root : []).forEach(function (n) {
      var mesh = n.mesh; if (!mesh) return;
      saved.vis.push({ mesh: mesh, visible: mesh.visible });
      if (n !== a.right_arm && n !== a.left_arm) mesh.visible = false;
    });
    ['right_arm', 'left_arm'].forEach(function (k) { var g = a[k]; if (g && g.mesh) saved.arms.push({ mesh: g.mesh, auto: g.mesh.matrixAutoUpdate, matrix: g.mesh.matrix.clone(), visible: g.mesh.visible }); });
    if (p.isOrtho && typeof p.setProjectionMode === 'function') p.setProjectionMode(false);
    if (typeof p.setFOV === 'function') p.setFOV(FOV); else { p.camera.fov = FOV; p.camera.updateProjectionMatrix(); }
    var c2 = p.camera;
    c2.position.set(0, EYE, 0); c2.up.set(0, 1, 0);
    p.controls.target.set(0, EYE, -16);
    try { p.controls.update(); } catch (e) { }
    c2.lookAt(0, EYE, -16);
    p.controls.enabled = false;
    document.addEventListener('keydown', onKey, true);
    placeArms(both);
    return cur;
  }
  function stop(why) {
    var r = cur; if (!r) return null;
    cur = null;
    document.removeEventListener('keydown', onKey, true);
    var p = r.p, s = r.saved;
    try {
      s.arms.forEach(function (x) { x.mesh.matrixAutoUpdate = x.auto; x.mesh.matrix.copy(x.matrix); if (x.auto) x.mesh.updateMatrix(); x.mesh.visible = x.visible; });
      s.vis.forEach(function (x) { x.mesh.visible = x.visible; });
      if (typeof p.setFOV === 'function') p.setFOV(s.fov);
      if (s.ortho && typeof p.setProjectionMode === 'function') p.setProjectionMode(true);
      var cam = p.camera;
      cam.position.copy(s.pos); cam.up.copy(s.up); cam.zoom = s.zoom; cam.updateProjectionMatrix();
      p.controls.target.copy(s.target);
      try { p.controls.update(); } catch (e) { }
      cam.position.copy(s.pos); cam.quaternion.copy(s.quat); cam.updateMatrixWorld(true);
      p.controls.enabled = s.enabled;
    } catch (e) { L.warn('could not put the camera back', e); }
    removeHud();
    render();
    return { why: why };
  }
  function toggle() { return cur ? stop('toggle') : start(false); }
  function onKey(e) { if (cur && e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); stop('esc'); } }

  // =================================================================== the box over the viewport
  function hostNode() { var p = cur ? cur.p : preview(); return p ? p.node || (p.canvas && p.canvas.parentElement) : null; }
  function hud() {
    var host = hostNode(); if (!host || !cur) return;
    var el = host.querySelector('.cmp_fp_hud');
    if (!el) {
      el = document.createElement('div'); el.className = 'cmp_fp_hud';
      el.innerHTML = '<span class="t"></span><button type="button" data-a="right"></button><button type="button" data-a="both"></button><button type="button" data-a="exit"></button>';
      el.addEventListener('click', function (e) {
        var a = e.target && e.target.getAttribute && e.target.getAttribute('data-a'); if (!a) return;
        e.stopPropagation();
        if (a === 'exit') stop('button'); else placeArms(a === 'both');
      });
      host.appendChild(el);
    }
    el.querySelector('.t').textContent = T('fpview.hud');
    var bs = el.querySelectorAll('button');
    bs[0].textContent = T('fpview.right'); bs[1].textContent = T('fpview.both'); bs[2].textContent = T('fpview.exit');
    bs[0].classList.toggle('on', !cur.both); bs[1].classList.toggle('on', !!cur.both);
  }
  function removeHud() { var p = preview(), host = p ? p.node || (p.canvas && p.canvas.parentElement) : null, el = host && host.querySelector('.cmp_fp_hud'); if (el) el.remove(); document.querySelectorAll('.cmp_fp_hud').forEach(function (x) { x.remove(); }); }

  var STYLE = 'cmp_fp_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_fp_hud{position:absolute;left:50%;top:10px;transform:translateX(-50%);z-index:5;display:flex;gap:6px;align-items:center;background:var(--color-ui);border:1px solid var(--color-accent);border-radius:4px;padding:6px 8px;box-shadow:0 2px 8px rgba(0,0,0,.35)}\n' +
        '.cmp_fp_hud .t{font-weight:600;margin-right:4px}\n.cmp_fp_hud button.on{border-color:var(--color-accent);background:var(--color-accent);color:var(--color-accent_text, #fff)}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var off = function () { if (cur) stop('mode'); }, offP = function () { if (cur) stop('project'); };
    try {
      G.Blockbench.on('select_mode', off); G.Blockbench.on('select_project', offP); G.Blockbench.on('unselect_project', offP);
      L.onUninstall(function () { G.Blockbench.removeListener('select_mode', off); G.Blockbench.removeListener('select_project', offP); G.Blockbench.removeListener('unselect_project', offP); });
    } catch (e) { }
    M.register({ id: 'cpmplus_model_fpview', sec: 'check', icon: 'visibility', key: 'fpview', cpm: false, spots: ['view'], run: function () { toggle(); } });
  };
  api.uninstall = function () { if (cur) stop('uninstall'); removeHud(); };
  api.start = start; api.stop = stop; api.toggle = toggle; api.active = function () { return !!cur; }; api.state = function () { return cur; };
  api.armMatrix = armMatrix; api.EYE = EYE; api.FOV = FOV;
  return api;
})
