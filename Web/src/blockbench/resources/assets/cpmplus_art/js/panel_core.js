/*
 * CPM Plugin+ beta31: the "CPM+ Pintar" panel - a real Blockbench panel in Paint mode, under the colour picker. Every
 * art tool is a button with its name written, the brush library is right there with a preview of each brush, the
 * colour and automatic tools open from it, and the options of the chosen tool sit at the bottom of the panel (the main
 * ones shown, the rest under "Mais opções"). Nothing of CPM+ goes on Blockbench's top bars any more.
 * Also adds the brushes to Blockbench's own brush menu (the small arrow next to the Brush).
 * Loaded by the art core (api.loadModule) after the brush module; L = the core helpers (L.core = the art core API).
 */
(function (L) {
  'use strict';
  var G = L.G, t = L.t;
  var api = {};
  var PANEL_ID = 'cpmplus_art_panel';
  var BT = 'cpmplus_art_brushes';
  var panel = null, vm = null;

  // =================================================================== the tools and their options in the panel
  // kind: slider | select | seg (buttons side by side) | toggle ; more = under "Mais opções"
  function C(id, kind, more) { return { id: id, kind: kind, more: !!more }; }
  var TOOLS = [
    { id: 'cpmplus_art_shade', icon: 'tonality', key: 'shade', size: true,
      ctrls: [C('cpmplus_art_shade_dir', 'seg'), C('cpmplus_art_shade_strength', 'slider'), C('cpmplus_art_shade_mode', 'select', 1),
        C('cpmplus_art_shade_source', 'select', 1), C('cpmplus_art_hsl_h', 'slider', 1), C('cpmplus_art_hsl_s', 'slider', 1),
        C('cpmplus_art_hsl_l', 'slider', 1), C('cpmplus_art_shade_once', 'toggle', 1)] },
    { id: 'cpmplus_art_smudge', icon: 'blur_on', key: 'smudge', size: true,
      ctrls: [C('cpmplus_art_smudge_strength', 'slider'), C('cpmplus_art_smudge_mode', 'select', 1), C('cpmplus_art_smudge_snap', 'toggle', 1)] },
    { id: 'cpmplus_art_smudge_px', icon: 'grid_on', key: 'smudgepx', size: true, ctrls: [] },
    { id: 'cpmplus_art_dither', icon: 'apps', key: 'dither', size: true,
      ctrls: [C('cpmplus_art_dither_density', 'slider'), C('cpmplus_art_dither_pattern', 'select', 1), C('cpmplus_art_dither_holes', 'select', 1),
        C('cpmplus_art_dither_shift', 'slider', 1)] },
    { id: 'cpmplus_art_noise', icon: 'grain', key: 'noise', size: true,
      ctrls: [C('cpmplus_art_noise_cover', 'slider'), C('cpmplus_art_noise_mode', 'select', 1), C('cpmplus_art_noise_amount', 'slider', 1)] },
    { id: 'gradient_tool', icon: 'gradient', key: 'grad', size: false, pixelGradient: true,
      ctrls: [C('cpmplus_art_grad_steps', 'slider'), C('cpmplus_art_grad_dither', 'select'), C('cpmplus_art_grad_colors', 'select', 1),
        C('cpmplus_art_grad_shape', 'select', 1)] }
  ];
  var BRUSH_TOOL = { id: BT, key: 'brushes', size: true, ctrls: [C('cpmplus_art_br_scatter', 'slider', 1), C('cpmplus_art_br_vary', 'slider', 1)] };
  var COLOURS = [{ id: 'ramp', icon: 'color_lens', key: 'ramp' }, { id: 'swap', icon: 'find_replace', key: 'swap' }];
  var AUTOS = [{ id: 'autoshade', icon: 'wb_shade', key: 'autoshade' }, { id: 'noise', icon: 'texture', key: 'tnoise' }];
  function toolDef(id) {
    if (id === BT) return BRUSH_TOOL;
    for (var i = 0; i < TOOLS.length; i++) if (TOOLS[i].id === id) return TOOLS[i];
    return null;
  }
  function brushes() { return L.core.modules && L.core.modules.brushes; }

  // =================================================================== state shown in the panel
  /** the art tool in use (the Gradient counts only in its pixel art style), or '' */
  function activeTool() {
    var sel = G.Toolbox && G.Toolbox.selected;
    if (!sel) return '';
    if (sel.id === 'gradient_tool') return L.core.gradPixelOn() ? 'gradient_tool' : '';
    return toolDef(sel.id) ? sel.id : '';
  }
  function ctrlModel(c) {
    var it = G.BarItems[c.id];
    if (!it) return null;
    try { if (typeof G.Condition === 'function' && !G.Condition(it.condition)) return null; } catch (e) { return null; }
    var m = { id: c.id, kind: c.kind, label: it.name, value: null, min: 0, max: 100, step: 1, options: [] };
    if (c.kind === 'slider') {
      var s = it.settings || {};
      m.min = s.min !== undefined ? s.min : 0; m.max = s.max !== undefined ? s.max : 100; m.step = s.interval || 1; m.value = it.get();
    } else if (c.kind === 'select' || c.kind === 'seg') {
      m.value = typeof it.get === 'function' ? it.get() : it.value;
      m.options = Object.keys(it.options || {}).map(function (k) { return { id: k, name: typeof it.getNameFor === 'function' ? it.getNameFor(k) : String(it.options[k]) }; });
    } else if (c.kind === 'toggle') m.value = !!it.value;
    return m;
  }
  function sizeSlider() { return G.BarItems.slider_brush_size; }
  /** reads everything the panel shows from Blockbench (tool, brush, option values) */
  function refresh() {
    if (!vm) return;
    var id = activeTool(), def = toolDef(id), B = brushes();
    vm.active = id;
    vm.brush = B ? B.current() : '';
    if (!def) { vm.title = ''; vm.tip = ''; vm.size = null; vm.main = []; vm.more = []; return; }
    if (id === BT && B) {
      var cur = vm.brush, cat = B.catalog(), name = '', desc = '';
      cat.forEach(function (g) { g.items.forEach(function (b) { if (b.id === cur) { name = b.name; desc = b.desc; } }); });
      vm.title = t('cpmplus.art.panel.tool.brushes') + ': ' + name;
      vm.tip = desc;
    } else {
      vm.title = id === 'gradient_tool' ? t('cpmplus.art.panel.tool.grad') : (G.BarItems[id] ? G.BarItems[id].name : id);
      vm.tip = t('cpmplus.art.panel.tip.' + def.key);
    }
    var ss = sizeSlider();
    vm.size = def.size && ss ? ss.get() : null;
    vm.sizeMax = ss && ss.settings && ss.settings.max ? ss.settings.max : 50;
    var main = [], more = [];
    def.ctrls.forEach(function (c) { var m = ctrlModel(c); if (m) (c.more ? more : main).push(m); });
    vm.main = main; vm.more = more;
  }
  var previewTimer = null;
  function refreshPreviews() {
    if (!vm) return;
    var B = brushes(); if (!B) return;
    vm.groups.forEach(function (g) { if (g.id !== vm.openGroup) return; g.items.forEach(function (b) { b.img = B.preview(b.id); }); });
  }
  function laterPreviews() { if (previewTimer) clearTimeout(previewTimer); previewTimer = setTimeout(function () { previewTimer = null; refreshPreviews(); }, 150); }

  // =================================================================== the panel
  var CtrlComp = {
    props: ['c'],
    template: [
      '<div class="cpp_ctrl">',
      '<template v-if="c.kind===\'slider\'"><label>{{c.label}} <b>{{c.value}}</b></label>',
      '<input type="range" :min="c.min" :max="c.max" :step="c.step" :value="c.value" @input="$emit(\'set\', c, $event.target.value)"></template>',
      '<template v-else-if="c.kind===\'select\'"><label>{{c.label}}</label>',
      '<select :value="c.value" @change="$emit(\'set\', c, $event.target.value)"><option v-for="o in c.options" :key="o.id" :value="o.id">{{o.name}}</option></select></template>',
      '<template v-else-if="c.kind===\'seg\'"><label>{{c.label}}</label><div class="cpp_seg">',
      '<div v-for="o in c.options" :key="o.id" class="cpp_segb" :class="{sel: o.id===c.value}" :data-value="o.id" @click="$emit(\'set\', c, o.id)">{{o.name}}</div></div></template>',
      '<template v-else-if="c.kind===\'toggle\'"><label class="cpp_check"><input type="checkbox" :checked="c.value" @change="$emit(\'set\', c, $event.target.checked)"> {{c.label}}</label></template>',
      '</div>'
    ].join('')
  };
  function makeModel() {
    var B = brushes(), cat = B ? B.catalog() : [], cur = B ? B.current() : '';
    // the brush groups start closed (everything fits in view); with the Pincéis tool in use, its group is open
    var open = '';
    if (L.isTool(BT)) cat.forEach(function (g) { g.items.forEach(function (b) { if (b.id === cur) open = g.id; }); });
    return {
      bt: BT, hint: t('cpmplus.art.panel.hint'), openGroup: open, showMore: false,
      groups: cat.map(function (g) { return { id: g.id, name: g.name, items: g.items.map(function (b) { return { id: b.id, name: b.name, desc: b.desc, img: '' }; }) }; }),
      tools: TOOLS.map(function (x) { return { id: x.id, icon: x.icon, name: x.id === 'gradient_tool' ? t('cpmplus.art.panel.tool.grad') : (G.BarItems[x.id] ? G.BarItems[x.id].name : x.id) }; }),
      colours: COLOURS.map(function (x) { return { id: x.id, icon: x.icon, name: t('cpmplus.art.panel.' + x.key) }; }),
      autos: AUTOS.map(function (x) { return { id: x.id, icon: x.icon, name: t('cpmplus.art.panel.' + x.key) }; }),
      active: '', brush: cur, title: '', tip: '', size: null, sizeMax: 50, main: [], more: [],
      labels: {
        brushes: t('cpmplus.art.panel.brushes'), tools: t('cpmplus.art.panel.tools'), colours: t('cpmplus.art.panel.colours'),
        more: t('cpmplus.art.more'), size: t('cpmplus.art.panel.size'), none: t('cpmplus.art.panel.none')
      }
    };
  }
  // order: the tools first (always in view), the brush groups (closed until clicked: one click shows the previews),
  // then the colour + automatic tools; the options of the chosen tool stay pinned to the bottom
  var TEMPLATE = [
    '<div class="cpp">',
    '<p class="cpp_hint" v-if="!active">{{hint}}</p>',
    '<h3 class="cpp_h" data-section="tools">{{labels.tools}}</h3>',
    '<div class="cpp_grid">',
    '<div v-for="x in tools" :key="x.id" class="cpp_btn" :class="{sel: active===x.id}" :data-id="x.id" :title="x.name" @click="pickTool(x.id)"><i class="material-icons">{{x.icon}}</i><span>{{x.name}}</span></div>',
    '</div>',
    '<h3 class="cpp_h" data-section="brushes">{{labels.brushes}}</h3>',
    '<div v-for="g in groups" :key="g.id" class="cpp_group">',
    '<div class="cpp_ghead" :class="{open: openGroup===g.id}" :data-group="g.id" @click="toggleGroup(g.id)">',
    '<i class="material-icons">{{openGroup===g.id ? \'expand_more\' : \'chevron_right\'}}</i><span>{{g.name}}</span><small>{{g.items.length}}</small></div>',
    '<div v-if="openGroup===g.id" class="cpp_bgrid">',
    '<div v-for="b in g.items" :key="b.id" class="cpp_bcard" :class="{sel: active===bt && brush===b.id}" :title="b.desc" :data-id="b.id" @click="pickBrush(b.id)">',
    '<img :src="b.img" alt=""><span>{{b.name}}</span></div>',
    '</div></div>',
    '<h3 class="cpp_h" data-section="colours">{{labels.colours}}</h3>',
    '<div class="cpp_grid">',
    '<div v-for="x in colours" :key="x.id" class="cpp_btn" :data-open="x.id" :title="x.name" @click="openDialog(x.id)"><i class="material-icons">{{x.icon}}</i><span>{{x.name}}</span></div>',
    '<div v-for="x in autos" :key="x.id" class="cpp_btn" :data-open="x.id" :title="x.name" @click="openDialog(x.id)"><i class="material-icons">{{x.icon}}</i><span>{{x.name}}</span></div>',
    '</div>',
    '<div class="cpp_opts" v-if="active" data-section="options">',
    '<div class="cpp_otitle">{{title}}</div>',
    '<div class="cpp_otip" v-if="tip">{{tip}}</div>',
    '<div v-if="size !== null" class="cpp_ctrl" data-ctrl="size"><label>{{labels.size}} <b>{{size}}</b></label>',
    '<input type="range" min="1" :max="sizeMax" :value="size" @input="setSize($event.target.value)"></div>',
    '<cpp-ctrl v-for="c in main" :key="c.id" :c="c" :data-ctrl="c.id" @set="setCtrl"></cpp-ctrl>',
    '<template v-if="more.length">',
    '<div class="cpp_more" @click="showMore=!showMore"><i class="material-icons">{{showMore ? \'expand_more\' : \'chevron_right\'}}</i>{{labels.more}}</div>',
    '<div v-if="showMore"><cpp-ctrl v-for="c in more" :key="c.id" :c="c" :data-ctrl="c.id" @set="setCtrl"></cpp-ctrl></div>',
    '</template>',
    '</div>',
    '<div class="cpp_opts cpp_none" v-else data-section="options">{{labels.none}}</div>',
    '</div>'
  ].join('');
  function component() {
    var model = makeModel();
    return {
      components: { 'cpp-ctrl': CtrlComp },
      data: function () { return model; },
      mounted: function () { vm = this; refreshPreviews(); refresh(); },
      methods: {
        toggleGroup: function (id) { this.openGroup = this.openGroup === id ? '' : id; refreshPreviews(); },
        pickBrush: function (id) { var B = brushes(); if (B) B.pick(id); refresh(); },
        pickTool: function (id) {
          var def = toolDef(id);
          if (def && def.pixelGradient) L.core.pixelGradient(); else L.selectTool(id);
          this.showMore = false;
          refresh();
        },
        openDialog: function (what) { try { L.core.open(what); } catch (e) { L.warn('could not open ' + what, e); } },
        setSize: function (v) { var s = sizeSlider(); if (!s) return; var n = Number(v); s.change(function () { return n; }); try { s.update(); } catch (e) { } refresh(); },
        setCtrl: function (c, v) {
          var it = G.BarItems[c.id]; if (!it) return;
          try {
            if (c.kind === 'slider') { var n = Number(v); it.change(function () { return n; }); try { it.update(); } catch (e) { } }
            else if (c.kind === 'select' || c.kind === 'seg') it.change(String(v));
            else if (c.kind === 'toggle') it.set(!!v);
          } catch (e) { L.warn('could not set ' + c.id, e); }
          refresh();
        }
      },
      template: TEMPLATE
    };
  }
  var STYLE = 'cpmplus_art_panel_style';
  function installStyle() {
    if (document.getElementById(STYLE)) return;
    var st = document.createElement('style'); st.id = STYLE;
    st.textContent = [
      '#panel_' + PANEL_ID + ' .cpp{padding:2px 8px 0}',
      '.cpp_hint{font-size:.9em;opacity:.75;margin:2px 0 4px}',
      '.cpp_h{font-size:.92em;font-weight:600;margin:8px 0 4px;text-transform:uppercase;letter-spacing:.04em;opacity:.8}',
      '.cpp_ghead{display:flex;align-items:center;gap:4px;cursor:pointer;padding:3px 2px;border-radius:4px}',
      '.cpp_ghead:hover{background:var(--color-button)}',
      '.cpp_ghead small{margin-left:auto;opacity:.6;padding-right:4px}',
      '.cpp_bgrid{display:flex;flex-wrap:wrap;gap:6px;margin:4px 0 8px}',
      '.cpp_bcard{width:134px;padding:3px;border:1px solid var(--color-border);border-radius:4px;cursor:pointer;display:flex;flex-direction:column;align-items:center;box-sizing:border-box;background:var(--color-back)}',
      '.cpp_bcard img{image-rendering:pixelated;width:128px;height:48px;display:block}',
      '.cpp_bcard span{font-size:.9em;margin-top:2px;max-width:128px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.cpp_grid{display:grid;grid-template-columns:1fr 1fr;gap:6px}',
      '.cpp_btn{display:flex;align-items:center;gap:6px;padding:5px 8px;border:1px solid var(--color-border);border-radius:4px;cursor:pointer;background:var(--color-back);min-height:32px;box-sizing:border-box}',
      '.cpp_btn i{font-size:20px;flex:0 0 auto}',
      '.cpp_btn span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.cpp_bcard:hover,.cpp_btn:hover{border-color:var(--color-accent)}',
      '.cpp_bcard.sel,.cpp_btn.sel{border-color:var(--color-accent);background:var(--color-selected)}',
      '.cpp_opts{position:sticky;bottom:0;background:var(--color-ui);border-top:1px solid var(--color-border);padding:6px 8px 8px;margin:12px -8px 0;z-index:2}',
      '.cpp_otitle{font-weight:600}',
      '.cpp_otip{font-size:.9em;opacity:.75;margin:2px 0 4px}',
      '.cpp_ctrl{margin:4px 0;display:grid;grid-template-columns:40% 1fr;align-items:center;gap:6px}',
      '.cpp_ctrl label{font-size:.92em;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      '.cpp_ctrl input[type=range],.cpp_ctrl select{width:100%;box-sizing:border-box;margin:0}',
      '.cpp_ctrl .cpp_check{grid-column:1 / span 2}',
      '.cpp_seg{display:flex;gap:4px}',
      '.cpp_segb{flex:1;text-align:center;padding:3px 4px;border:1px solid var(--color-border);border-radius:4px;cursor:pointer}',
      '.cpp_segb.sel{border-color:var(--color-accent);background:var(--color-selected)}',
      '.cpp_more{cursor:pointer;color:var(--color-accent);display:flex;align-items:center;margin:6px 0 2px;user-select:none}',
      '.cpp_check{display:flex !important;align-items:center;gap:6px}',
      '.cpp_none{opacity:.75;font-size:.9em}',
      '@keyframes cppflash{0%{box-shadow:inset 0 0 0 2px var(--color-accent)}100%{box-shadow:inset 0 0 0 0 transparent}}',
      '.cpp_flash{animation:cppflash 1.4s ease-out}'
    ].join('\n');
    document.head.appendChild(st);
    L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
  }
  /**
   * Blockbench keeps one layout per mode. In Paint mode the panel is a tab next to the Outliner ("Organizador", the
   * part list, little used while painting): the big free area of the right sidebar, under the colour picker. The tab
   * is opened the first time Paint mode opens; the user can move it anywhere, Blockbench remembers.
   */
  var PAINT_POS = { slot: 'right_bar', attached_to: 'outliner', attached_index: 1, folded: false };
  function createPanel() {
    if (G.Panels && G.Panels[PANEL_ID]) { try { G.Panels[PANEL_ID].delete(); } catch (e) { } }
    panel = new G.Panel(PANEL_ID, {
      name: t('cpmplus.art.panel'), icon: 'palette', condition: { modes: ['paint'] },
      default_position: { slot: 'right_bar', float_position: [0, 0], float_size: [330, 620], height: 480, folded: false },
      mode_positions: { paint: PAINT_POS, paint_2d: PAINT_POS },
      growable: true, resizable: true, min_height: 180, expand_button: true,
      component: component()
    });
    L.onUninstall(function () { try { if (panel) panel.delete(); } catch (e) { } panel = null; vm = null; });
    try { if (typeof G.updateInterfacePanels === 'function') G.updateInterfacePanels(); } catch (e) { }
  }
  /** the Outliner tab group the panel sits in (Paint mode), or null when the user moved it out */
  function hostPanel() { try { return panel && typeof panel.getHostPanel === 'function' ? panel.getHostPanel() || null : null; } catch (e) { return null; } }
  /** brings the panel to the front: its tab open, nothing folded */
  function bringForward() {
    if (!panel) return;
    var host = hostPanel();
    if (host) {
      if (typeof host.selectTab === 'function') host.selectTab(panel);
      if (host.folded && typeof host.fold === 'function') host.fold(false);
    } else {
      if (panel.slot === 'hidden' && typeof panel.moveTo === 'function') panel.moveTo('right_bar');
      if (panel.folded && typeof panel.fold === 'function') panel.fold(false);
    }
  }
  /** the first time Paint mode opens with this beta: the CPM+ Pintar tab is the open one */
  function firstPaintTab() {
    if (L.opt('panel.tab31', false) || !G.Modes || !G.Modes.paint || !panel) return;
    L.setOpt('panel.tab31', true);
    bringForward();
  }

  // =================================================================== inside Blockbench's own interface
  /** the brushes in Blockbench's brush menu (the small arrow next to the Brush) */
  function installBrushMenu() {
    var bt = G.BarItems.brush_tool, sm = bt && bt.side_menu;
    if (!sm || typeof sm.structure !== 'function') return;
    var orig = sm.structure;
    while (orig && orig.__cpmplus) orig = orig.__orig;// a copy of the plugin loaded before
    var wrapped = function () {
      var list = orig.apply(this, arguments);
      list = Array.isArray(list) ? list.slice() : [];
      try {
        var B = brushes(); if (!B) return list;
        list.push('_');
        list.push({
          name: t('cpmplus.art.panel.brushMenu'), icon: 'auto_awesome_mosaic',
          children: B.catalog().map(function (g) {
            return { name: g.name, icon: 'brush', children: g.items.map(function (b) { return { name: b.name, icon: 'brush', description: b.desc, click: function () { B.pick(b.id); refresh(); } }; }) };
          })
        });
        list.push({ name: t('cpmplus.art.panel.showPanel'), icon: 'palette', click: function () { api.show('brushes'); } });
      } catch (e) { L.warn('could not add the brushes to the brush menu', e); }
      return list;
    };
    wrapped.__cpmplus = true; wrapped.__orig = orig;
    sm.structure = wrapped;
    L.onUninstall(function () { if (sm.structure === wrapped) sm.structure = orig; });
  }
  /**
   * one toast the first time Paint mode opens with beta31 - only for people who already saw the beta29 toast (new
   * users get that one, which now points to this panel); never in the QA browser
   */
  function installHint() {
    if (L.opt('panel.hint', false)) return;
    if (!L.artHintSeen()) { L.setOpt('panel.hint', true); return; }
    function onMode(e) {
      try {
        if (L.test() || L.opt('panel.hint', false)) return;
        var mode = e && e.mode ? e.mode : null, id = mode && mode.id ? mode.id : (G.Modes && G.Modes.selected && G.Modes.selected.id);
        if (id !== 'paint') return;
        L.setOpt('panel.hint', true);
        G.Blockbench.showToastNotification({ text: t('cpmplus.art.panel.toast'), icon: 'palette', expire: 20000, click: function () { api.show(); return true; } });
        setTimeout(function () { api.show(); }, 300);
      } catch (x) { }
    }
    try { G.Blockbench.on('select_mode', onMode); L.onUninstall(function () { G.Blockbench.removeListener('select_mode', onMode); }); } catch (e) { }
  }
  function installListeners() {
    var on = function () { refresh(); };
    var onMode = function () { setTimeout(function () { firstPaintTab(); refresh(); }, 0); };
    try {
      G.Blockbench.on('update_selection', on);
      G.Blockbench.on('select_mode', onMode);
      L.onUninstall(function () { G.Blockbench.removeListener('update_selection', on); G.Blockbench.removeListener('select_mode', onMode); });
    } catch (e) { }
    firstPaintTab();
    // the previews use the chosen colours
    try {
      var cv = G.ColorPanel && G.ColorPanel.panel && G.ColorPanel.panel.vue;
      if (cv && typeof cv.$watch === 'function') {
        var u1 = cv.$watch('main_color', laterPreviews), u2 = cv.$watch('second_color', laterPreviews);
        L.onUninstall(function () { try { u1(); u2(); } catch (e) { } });
      }
    } catch (e) { }
  }

  // =================================================================== API
  api.install = function () {
    installStyle();
    createPanel();
    installBrushMenu();
    installListeners();
    installHint();
  };
  api.uninstall = function () { if (previewTimer) clearTimeout(previewTimer); previewTimer = null; };
  /** shows the panel (Paint mode, unfolded, in its sidebar) scrolled to a section: brushes | tools | colours | auto | options */
  api.show = function (section) {
    try {
      if (G.Modes && G.Modes.options && G.Modes.options.paint && !G.Modes.paint) G.Modes.options.paint.select();
      if (!panel) return false;
      bringForward();
      if (vm && section === 'brushes') { var B = brushes(), cur = B ? B.current() : ''; vm.groups.forEach(function (g) { g.items.forEach(function (b) { if (b.id === cur) vm.openGroup = g.id; }); }); refreshPreviews(); }
      refresh();
      setTimeout(function () {
        if (!panel) return;
        var el = panel.node.querySelector('[data-section="' + (section || 'tools') + '"]');
        if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
        var box = (hostPanel() || panel).container;
        box.classList.remove('cpp_flash'); void box.offsetWidth; box.classList.add('cpp_flash');
      }, 30);
      return true;
    } catch (e) { L.warn('could not show the art panel', e); return false; }
  };
  /** QA */
  api.panel = function () { return panel; };
  api.refresh = refresh;
  api.model = function () { return vm; };
  return api;
})
