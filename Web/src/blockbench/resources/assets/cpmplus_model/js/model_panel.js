/*
 * CPM Plugin+ beta33: the "CPM+ Modelar" panel - a real Blockbench panel in Edit mode. Every modelling tool is a button
 * with its name, in sections (Criar, Ajustar, Organizar, Textura, Conferir, Corpo e rig), the CPM+ body / rig tools
 * that already existed are there too, and the bottom of the panel always shows the selection (parts, size in pixels,
 * the gap between two parts) and how heavy the model is for other players. A button that needs a selection first is
 * greyed out with the reason in its tooltip.
 * Loaded last (after every modelling module registered its tools); L = the art core helpers.
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var PANEL_ID = 'cpmplus_model_panel';
  var panel = null, vm = null;
  // the CPM+ body / rig tools that already existed (Java actions), shown in the 'rig' section when present
  var RIG = ['cpmplus_w4anim2_mirror', 'cpmplus_w5stick_stick', 'cpmplus_copy_transform', 'cpmplus_w4rig_joints', 'cpmplus_w4rig_chain', 'cpmplus_w4rig_bone',
    'cpmplus_w7rig_bend', 'cpmplus_w7rig_curve', 'cpmplus_w6help_skeleton'];
  var TEXTURE_EXTRA = ['cpmplus_w5fx_solid_convert'];

  // =================================================================== model
  function condOk(it) { try { return !it.condition || typeof G.Condition !== 'function' || !!G.Condition(it.condition); } catch (e) { return false; } }
  function sections() {
    var out = [];
    M.sections.forEach(function (s) {
      var items = M.defs().filter(function (d) { return d.sec === s; }).map(function (d) {
        var ok = M.ready(d), inMode = !d.mode || d.mode !== 'edit' || M.inEdit();
        return { id: d.id, icon: d.icon, name: T(d.key), desc: T(d.key + '.desc') + (ok ? '' : '\n' + T('need.' + (typeof d.need === 'string' ? d.need : 'sel'))), ready: ok && inMode && (!d.cpm || M.isCpm()), native: false };
      });
      var extra = s === 'rig' ? RIG : s === 'texture' ? TEXTURE_EXTRA : [];
      extra.forEach(function (id) {
        var it = G.BarItems && G.BarItems[id]; if (!it || !M.isCpm()) return;
        items.push({ id: id, icon: typeof it.icon === 'string' ? it.icon : 'build', name: it.name, desc: it.description || '', ready: condOk(it), native: true });
      });
      if (items.length) out.push({ id: s, name: T('panel.sec.' + s), items: items });
    });
    return out;
  }
  function infoText() {
    var i = M.info(), lines = [];
    if (i.nodes) {
      lines.push(T('panel.sel', i.cubes));
      if (i.size) lines.push(T('panel.size', M.num(i.size[0]), M.num(i.size[1]), M.num(i.size[2])));
      if (i.gap) lines.push(T('panel.gap', M.num(i.gap[0]), M.num(i.gap[1]), M.num(i.gap[2]), M.num(i.dist)));
    } else lines.push(T('panel.nosel'));
    return { lines: lines, model: T('weight.model', i.total, T('weight.' + i.weight)), weight: i.weight, tip: weightTip() };
  }
  /** the weight line's tip: what the number means + how many parts each body part has */
  // lang: lib.root.{head|body|right_arm|left_arm|right_leg|left_leg}
  var KNOWN_ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  function weightTip() {
    var parts = [];
    try {
      (G.Group && G.Group.all ? G.Group.all : []).forEach(function (g) {
        if (g.parent !== 'root') return;
        var n = M.cubesOf([g]).length;
        if (n) parts.push((KNOWN_ROOTS.indexOf(g.name) >= 0 ? T('lib.root.' + g.name) : g.name) + ': ' + n);
      });
    } catch (e) { }
    return T('weight.tip') + (parts.length ? '\n' + parts.join(' · ') : '');
  }
  var pending = false;
  function refresh() {
    if (!vm || pending) return;
    pending = true;
    setTimeout(function () {
      pending = false; if (!vm) return;
      try { vm.sections = sections(); var inf = infoText(); vm.info = inf.lines; vm.model = inf.model; vm.weight = inf.weight; vm.weightTip = inf.tip; } catch (e) { L.warn('model panel refresh failed', e); }
    }, 0);
  }

  // =================================================================== the panel
  var TEMPLATE = [
    '<div class="cmp">',
    '<p class="cmp_hint">{{hint}}</p>',
    '<template v-for="s in sections"><h3 class="cmp_h" :key="\'h\'+s.id" :data-section="s.id">{{s.name}}</h3>',
    '<div class="cmp_grid" :key="\'g\'+s.id">',
    '<div v-for="b in s.items" :key="b.id" class="cmp_btn" :class="{off: !b.ready}" :data-id="b.id" :title="b.desc" @click="run(b)"><i class="material-icons">{{b.icon}}</i><span>{{b.name}}</span></div>',
    '</div></template>',
    '<div class="cmp_info" data-section="info"><div v-for="(l, i) in info" :key="i">{{l}}</div><div class="cmp_weight" :class="weight" :title="weightTip">{{model}}</div></div>',
    '</div>'
  ].join('');
  function component() {
    return {
      data: function () { return { hint: T('panel.hint'), sections: [], info: [], model: '', weight: 'light', weightTip: T('weight.tip') }; },
      mounted: function () { vm = this; refresh(); },
      methods: {
        run: function (b) {
          if (b.native) { var it = G.BarItems[b.id]; if (it) { try { it.trigger ? it.trigger({}) : it.click({}); } catch (e) { L.warn('could not run ' + b.id, e); } } }
          else M.run(b.id, {});
          refresh();
        }
      },
      template: TEMPLATE
    };
  }
  var STYLE = 'cpmplus_model_panel_style';
  function installStyle() {
    if (document.getElementById(STYLE)) return;
    var st = document.createElement('style'); st.id = STYLE;
    st.textContent = [
      '#panel_' + PANEL_ID + ' .cmp{padding:2px 8px 0}',
      '.cmp_hint{font-size:.9em;opacity:.75;margin:2px 0 4px}',
      '.cmp_h{font-size:.92em;font-weight:600;margin:8px 0 4px;text-transform:uppercase;letter-spacing:.04em;opacity:.8}',
      '.cmp_grid{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:6px}',
      '.cmp_btn{display:flex;align-items:center;gap:6px;padding:5px 8px;border:1px solid var(--color-border);border-radius:4px;cursor:pointer;background:var(--color-back);min-height:32px;box-sizing:border-box;min-width:0}',
      '.cmp_btn i{font-size:20px;flex:0 0 auto}',
      '.cmp_btn span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.cmp_btn:hover{border-color:var(--color-accent)}',
      '.cmp_btn.off{opacity:.5}',
      '.cmp_info{position:sticky;bottom:0;background:var(--color-ui);border-top:1px solid var(--color-border);padding:6px 8px 8px;margin:12px -8px 0;z-index:2;font-size:.92em}',
      // the model's weight is information, not a warning (many models are made for animations / videos)
      '.cmp_weight{margin-top:2px;font-weight:600;opacity:.85;cursor:help}',
      '@keyframes cmpflash{0%{box-shadow:inset 0 0 0 2px var(--color-accent)}100%{box-shadow:inset 0 0 0 0 transparent}}',
      '.cmp_flash{animation:cmpflash 1.4s ease-out}'
    ].join('\n');
    document.head.appendChild(st);
    L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
  }
  /**
   * Edit mode: a tab next to the Textures panel in the left sidebar (the Outliner and the Transform panel on the right
   * stay as they are, they are used all the time while modelling). The user can move it; Blockbench remembers.
   */
  var EDIT_POS = { slot: 'left_bar', attached_to: 'textures', attached_index: 1, folded: false };
  function createPanel() {
    if (G.Panels && G.Panels[PANEL_ID]) { try { G.Panels[PANEL_ID].delete(); } catch (e) { } }
    panel = new G.Panel(PANEL_ID, {
      name: T('panel'), icon: 'view_in_ar', condition: { modes: ['edit'] },
      default_position: { slot: 'left_bar', float_position: [0, 0], float_size: [330, 620], height: 420, folded: false },
      mode_positions: { edit: EDIT_POS },
      growable: true, resizable: true, min_height: 180, expand_button: true,
      component: component()
    });
    L.onUninstall(function () { try { if (panel) panel.delete(); } catch (e) { } panel = null; vm = null; });
    try { if (typeof G.updateInterfacePanels === 'function') G.updateInterfacePanels(); } catch (e) { }
  }
  function hostPanel() { try { return panel && typeof panel.getHostPanel === 'function' ? panel.getHostPanel() || null : null; } catch (e) { return null; } }
  function bringForward() {
    if (!panel) return;
    var host = hostPanel();
    if (host) {
      if (typeof host.selectTab === 'function') host.selectTab(panel);
      if (host.folded && typeof host.fold === 'function') host.fold(false);
    } else {
      if (panel.slot === 'hidden' && typeof panel.moveTo === 'function') panel.moveTo('left_bar');
      if (panel.folded && typeof panel.fold === 'function') panel.fold(false);
    }
  }
  /** the first time Edit mode opens with this beta: the CPM+ Modelar tab is the open one */
  function firstEditTab() {
    if (!M.inEdit() || !panel) return;
    if (!L.opt('model.panel.tab33', false)) { L.setOpt('model.panel.tab33', true); bringForward(); }
    giveRoom(0, false);
  }
  /**
   * The left sidebar is shared by the UV editor (big) and the Textures tab group: once, the group with this panel gets
   * the larger part (~55%; the UV editor keeps the rest), the way Blockbench does it when the border is dragged (a fixed
   * height, then update). The user can drag it back; Blockbench remembers. Waits until the sidebar is laid out.
   */
  function giveRoom(tries, force) {
    if (!force && L.opt('model.panel.room33', false)) return false;
    var host = hostPanel() || panel; if (!host || !host.container) return false;
    var bar = host.container.parentElement, H = bar ? bar.clientHeight : 0;
    if (!H || !host.container.clientHeight) { if (tries < 15) setTimeout(function () { giveRoom(tries + 1, force); }, 200); return false; }
    L.setOpt('model.panel.room33', true);
    var want = Math.round(H * 0.55);
    if (host.container.clientHeight >= want) return true;
    // the sidebar is a flex column: the UV editor gets a fixed height (~42%) and the tab group grows into the rest
    try {
      var uv = G.Panels && G.Panels.uv, upd = uv && uv.position_data;
      if (upd && upd.slot === (host.position_data && host.position_data.slot)) {
        upd.height = Math.round(H * 0.42); upd.fixed_height = true; uv.update();
      }
      var pd = host.position_data; if (pd) { pd.fixed_height = false; host.update(); }
      if (typeof G.updateInterfacePanels === 'function') G.updateInterfacePanels();
    } catch (e) { L.warn('could not give the model panel more room', e); }
    return true;
  }
  function installListeners() {
    var on = function () { refresh(); };
    var onMode = function () { setTimeout(function () { firstEditTab(); refresh(); }, 0); };
    var evs = ['update_selection', 'finished_edit', 'undo', 'redo', 'select_project', 'add_cube', 'add_group'];
    try {
      evs.forEach(function (e) { G.Blockbench.on(e, on); });
      G.Blockbench.on('select_mode', onMode);
      L.onUninstall(function () { evs.forEach(function (e) { G.Blockbench.removeListener(e, on); }); G.Blockbench.removeListener('select_mode', onMode); });
    } catch (e) { }
    firstEditTab();
  }

  // =================================================================== API
  api.install = function () {
    installStyle();
    createPanel();
    installListeners();
    M.register({ id: 'cpmplus_model_show_panel', key: 'panel.show', icon: 'view_in_ar', spots: [], run: function () { api.show(); } });
  };
  api.uninstall = function () { };
  /** shows the panel (Edit mode, its tab open) scrolled to a section: create | adjust | organize | texture | check | rig | info */
  api.show = function (section) {
    try {
      if (G.Modes && G.Modes.options && G.Modes.options.edit && !M.inEdit()) G.Modes.options.edit.select();
      if (!panel) return false;
      bringForward(); refresh();
      setTimeout(function () {
        if (!panel) return;
        var el = panel.node.querySelector('[data-section="' + (section || 'create') + '"]');
        if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
        var box = (hostPanel() || panel).container;
        if (box) { box.classList.remove('cmp_flash'); void box.offsetWidth; box.classList.add('cmp_flash'); }
      }, 30);
      return true;
    } catch (e) { L.warn('could not show the model panel', e); return false; }
  };
  api.panel = function () { return panel; };
  /** QA: the one-time 'more room' step again (force = ignore that it already ran) */
  api.giveRoom = function (force) { return giveRoom(0, !!force); };
  api.refresh = refresh;
  api.model = function () { return vm; };
  return api;
})
