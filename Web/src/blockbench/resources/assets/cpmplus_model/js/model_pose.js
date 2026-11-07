/*
 * CPM Plugin+ "CPM+ Modelar" - Aplicar pose como repouso (Animate mode): the pose an animation shows at the playhead
 * becomes the model's rest pose for the selected bones (or every bone that is out of its rest): the group's rotation
 * gets the animation's rotation, the group (with everything inside) moves by the animation's position. Then every
 * animation's keys of those bones are shifted by the same amount, so the animations look exactly as before (Blockbench
 * shows rest + key for additive and non-additive CPM animations alike; the exporter adds the rest only for the
 * non-additive ones, so their exported values do not change at all). The animation the pose comes from is always
 * shifted (its pose becomes zero). Under 'Mais opcoes' the additive animations can instead keep their keys and move
 * from the new rest. Bones that an animation does not key show the new rest there. One undo step (outliner +
 * animations). Molang keys get '- value' added. Scale keys are not touched (a group has no rest scale).
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var AX = ['x', 'y', 'z'], TINY = 1e-6;

  function anims() { return G.Animation && G.Animation.all ? G.Animation.all.slice() : []; }
  function inAnimate() { return !!(G.Modes && G.Modes.animate); }
  function animByUuid(id) { var a = anims(); for (var i = 0; i < a.length; i++) if (a[i].uuid === id) return a[i]; return null; }
  function round(v) { return Math.round(v * 10000) / 10000; }
  /** the value an animation shows for a bone channel at its current time ([x, y, z], zeros when it has no keys) */
  function shown(anim, g, channel) {
    var ba = anim && anim.animators && anim.animators[g.uuid];
    if (!ba || !ba[channel] || !ba[channel].length) return [0, 0, 0];
    var v = null;
    try { v = ba.interpolate(channel, true); } catch (e) { v = null; }
    if (!Array.isArray(v)) return [0, 0, 0];
    return v.map(function (x) { x = Number(x); return isFinite(x) ? round(x) : 0; });
  }
  function nonZero(v) { return Math.abs(v[0]) > TINY || Math.abs(v[1]) > TINY || Math.abs(v[2]) > TINY; }
  /** shows animation a at time t (the same view the user gets in Animate mode) */
  function showAt(a, t) {
    try { if (a && G.Animation.selected !== a) a.select(); } catch (e) { }
    try { G.Timeline.setTime(Math.max(0, Number(t) || 0)); } catch (e) { }
    try { G.Animator.preview(); } catch (e) { }
  }
  /**
   * what would change: o = {anim, time, bones: 'sel' | 'all' | [groups]} -> [{group, rot, pos}] (only bones whose pose
   * differs from their rest). Shows the animation at that time first (interpolation reads the playhead).
   */
  function plan(o) {
    var a = o.anim && typeof o.anim === 'object' ? o.anim : animByUuid(o.anim);
    if (!a) return [];
    showAt(a, o.time);
    var list = Array.isArray(o.bones) ? o.bones : o.bones === 'sel' ? M.selGroups() : (G.Group ? G.Group.all.slice() : []);
    var out = [];
    list.forEach(function (g) {
      if (!M.isGroup(g)) return;
      var rot = shown(a, g, 'rotation'), pos = shown(a, g, 'position');
      if (nonZero(rot) || nonZero(pos)) out.push({ group: g, rot: rot, pos: pos });
    });
    return out;
  }
  /** shifts every key of a channel by -v (each data point; Molang keys get '- v' through Keyframe.offset) */
  function shiftKeys(ba, channel, v) {
    var n = 0;
    (ba && ba[channel] ? ba[channel] : []).forEach(function (kf) {
      var dps = kf.data_points && kf.data_points.length ? kf.data_points.length : 1;
      for (var d = 0; d < dps; d++) for (var i = 0; i < 3; i++) if (Math.abs(v[i]) > TINY) kf.offset(AX[i], -v[i], d);
      n++;
    });
    return n;
  }
  /** a channel the animation does not key: one key at 0 s that keeps the old rest (-v) */
  function hold(ba, channel, v) {
    var dp = { x: M.snap(-v[0]) || 0, y: M.snap(-v[1]) || 0, z: M.snap(-v[2]) || 0 };
    ba.addKeyframe({ channel: channel, time: 0, data_points: [dp] });
    return 1;
  }
  /**
   * applies a plan (one undo step) -> {bones, animations (changed), keys}. o.additiveFollow: additive animations other
   * than the source keep their keys (they move from the new rest).
   */
  function apply(changes, o) {
    o = o || {};
    if (!changes.length) return { bones: 0, animations: 0, keys: 0 };
    var source = o.anim && typeof o.anim === 'object' ? o.anim : animByUuid(o.anim), all = anims(), roots = changes.map(function (c) { return c.group; });
    var groups = M.groupsOf(roots), cubes = M.cubesOf(roots), touched = {}, keys = 0;
    var aspects = { outliner: true, elements: cubes, groups: groups, animations: all };
    M.edit(T('restpose'), aspects, function () {
      changes.forEach(function (c) {
        var g = c.group;
        for (var i = 0; i < 3; i++) g.rotation[i] = M.snap(g.rotation[i] + c.rot[i]);
        if (nonZero(c.pos)) M.shiftGroup(g, [M.snap(c.pos[0]), M.snap(c.pos[1]), M.snap(c.pos[2])]);
        all.forEach(function (a) {
          if (o.additiveFollow && a !== source && a.cpm_additive !== false) return;
          // only animations that animate this bone; a channel they do not key gets one key holding the old rest
          var ba = a.animators && a.animators[g.uuid], has = function (ch) { return !!(ba && ba[ch] && ba[ch].length); };
          if (!has('rotation') && !has('position')) return;
          var k = 0;
          if (nonZero(c.rot)) k += has('rotation') ? shiftKeys(ba, 'rotation', c.rot) : hold(ba, 'rotation', c.rot);
          if (nonZero(c.pos)) k += has('position') ? shiftKeys(ba, 'position', c.pos) : hold(ba, 'position', c.pos);
          if (k) { touched[a.uuid] = 1; keys += k; }
        });
      });
    }, aspects);
    try { G.Canvas.updateAll(); } catch (e) { }
    try { G.Animator.preview(); } catch (e) { }
    try { if (G.Timeline && G.Timeline.update) G.Timeline.update(); } catch (e) { }
    return { bones: changes.length, animations: Object.keys(touched).length, keys: keys };
  }

  // =================================================================== the window (the viewport shows the pose live)
  var dlg = null;
  function open() {
    var list = anims();
    if (!list.length) { G.Blockbench.showMessageBox({ title: T('restpose'), message: T('restpose.noanim') }); return null; }
    if (!inAnimate() && G.Modes && G.Modes.options && G.Modes.options.animate) G.Modes.options.animate.select();
    var prev = { anim: G.Animation.selected || null, time: G.Timeline ? G.Timeline.time : 0 };
    var cur = prev.anim && list.indexOf(prev.anim) >= 0 ? prev.anim : list[0], opts = {};
    list.forEach(function (a) { opts[a.uuid] = String(a.name) + (a.cpm_additive === false ? ' (' + T('restpose.abs') + ')' : ''); });
    var nsel = M.selGroups().length, bones = { all: T('restpose.b.all') };
    if (nsel) bones = { sel: T('restpose.b.sel', nsel), all: T('restpose.b.all') };
    var form = {
      anim: { label: T('restpose.f.anim'), type: 'select', options: opts, value: cur.uuid },
      time: { label: T('restpose.f.time'), type: 'number', value: M.snap(prev.anim === cur ? prev.time : 0), min: 0, step: 0.05 },
      bones: { label: T('restpose.f.bones'), type: 'select', options: bones, value: nsel ? 'sel' : 'all' },
      more: { label: T('restpose.f.more'), type: 'checkbox', value: false },
      follow: { label: T('restpose.f.follow'), description: T('restpose.f.follow.desc'), type: 'checkbox', value: false, condition: function (f) { return !!f.more; } }
    };
    var done = false;
    dlg = new G.Dialog({
      id: 'cpmplus_model_restpose', title: T('restpose'), width: 520, buttons: [T('restpose.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('restpose.tip')) + '</p>', '<div class="cmp_pose_list"></div>'],
      form: form,
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        var a = animByUuid(f.anim), ch = plan({ anim: a, time: f.time, bones: f.bones });
        if (!ch.length) { G.Blockbench.showQuickMessage(T('restpose.none'), 2500); return false; }
        var r = apply(ch, { anim: a, additiveFollow: !!f.follow });
        done = true; dlg = null;
        G.Blockbench.showQuickMessage(T('restpose.done', r.bones, r.animations), 3000);
      },
      onCancel: function () {
        dlg = null;
        if (!done) { if (prev.anim) showAt(prev.anim, prev.time); else { try { G.Timeline.setTime(prev.time); G.Animator.preview(); } catch (e) { } } }
      }
    });
    dlg.show();
    function update(f) {
      var a = animByUuid(f.anim), ch = plan({ anim: a, time: f.time, bones: f.bones }), box = dlg && dlg.object && dlg.object.querySelector('.cmp_pose_list');
      if (!box) return;
      var lines = ch.slice(0, 12).map(function (c) {
        var parts = [];
        if (nonZero(c.rot)) parts.push(T('restpose.rot', c.rot.map(function (v) { return M.num(v); }).join(', ')));
        if (nonZero(c.pos)) parts.push(T('restpose.pos', c.pos.map(function (v) { return M.num(v); }).join(', ')));
        return '<div>' + L.esc(c.group.name + ': ' + parts.join(' \u00b7 ')) + '</div>';
      });
      if (ch.length > 12) lines.push('<div>' + L.esc(T('restpose.more', ch.length - 12)) + '</div>');
      if (!ch.length) lines.push('<div>' + L.esc(T('restpose.none')) + '</div>');
      // how many other animations get their keys shifted
      var n = 0;
      anims().forEach(function (x) {
        if (f.follow && x !== a && x.cpm_additive !== false) return;
        if (ch.some(function (c) { var ba = x.animators && x.animators[c.group.uuid]; return ba && ((ba.rotation && ba.rotation.length) || (ba.position && ba.position.length)); })) n++;
      });
      box.innerHTML = lines.join('');
      M.dialogNote(dlg, T('restpose.note', ch.length, n), M.allCubes().length, !ch.length);
    }
    try { update(dlg.getFormResult()); } catch (e) { L.warn('rest pose preview failed', e); }
    return dlg;
  }

  // =================================================================== Animation menu (Animate mode)
  function placeInAnimationMenu(action) {
    var m = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus.animation;
    if (!m || !Array.isArray(m.structure)) return;
    var s = m.structure;
    for (var i = s.length - 1; i >= 0; i--) if (s[i] && s[i] !== action && s[i].id === action.id) s.splice(i, 1);
    if (s.indexOf(action) >= 0) return;
    var sep = typeof G.MenuSeparator === 'function' ? new G.MenuSeparator('cpmplus_model_anim', T('menu.section')) : '_';
    s.push(sep, action);
    L.onUninstall(function () { [action, sep].forEach(function (x) { var k = s.indexOf(x); if (k >= 0) s.splice(k, 1); }); });
  }
  var STYLE = 'cmp_pose_style';
  api.install = function () {
    if (!document.getElementById(STYLE)) {
      var st = document.createElement('style'); st.id = STYLE;
      st.textContent = '.cmp_pose_list{max-height:180px;overflow:auto;margin:4px 0 6px;font-family:var(--font-code, monospace);font-size:.9em}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
    }
    var action = M.register({ id: 'cpmplus_model_restpose', sec: 'rig', icon: 'accessibility_new', key: 'restpose', mode: 'any', spots: [], run: function () { open(); } });
    placeInAnimationMenu(action);
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } };
  api.plan = plan; api.apply = apply; api.open = open; api.dialog = function () { return dlg; }; api.shown = shown;
  return api;
})
