/*
 * CPM Plugin+ beta33 "CPM+ Modelar" - organising parts:
 * - Selecao esperta: clicking again on the same spot selects the part BEHIND (and so on, in a loop) - Blockbench's own
 *   click is redirected inside its 'canvas_select' event, so the rest of the click works as always; the name of the
 *   part under the mouse (with its groups) next to the cursor; 'Selecionar parecidos': same size, same colour, same
 *   group, the twin on the other side, same name without the number.
 * - Renomear em lote: name + number, prefix / suffix, replace text, swap left <-> right, clean names (no accents or
 *   spaces), with the list 'before -> after' live, one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};

  // =================================================================== click again = the part behind
  var last = null, cycleItem = null, hoverItem = null;
  function elementsUnder(data) {
    var out = [];
    (data.intersects || []).forEach(function (it) {
      var o = it.object; if (!o || !o.isElement) return;
      var el = G.OutlinerNode && G.OutlinerNode.uuids ? G.OutlinerNode.uuids[o.name] : null;
      if (el && out.indexOf(el) < 0 && el.visibility !== false) out.push(el);
    });
    return out;
  }
  /** Blockbench's 'canvas_select' (before it selects data.element): a second click on the same spot picks the next part under the mouse */
  function onCanvasSelect(data) {
    try {
      if (!data || data.type !== 'element' || !cycleItem || !cycleItem.value) return;
      var ev = data.event; if (!ev || ev.clientX === undefined) return;
      var mods = ev.shiftKey || ev.ctrlKey || ev.metaKey || ev.altKey, now = Date.now();
      var els = elementsUnder(data), pick = data.element;
      var same = last && !mods && Math.abs(ev.clientX - last.x) <= 4 && Math.abs(ev.clientY - last.y) <= 4 && now - last.t >= 250 && now - last.t < 15000;
      if (same && els.length > 1) {
        var i = els.indexOf(last.el);
        if (i >= 0) pick = els[(i + 1) % els.length];
      }
      if (pick && pick !== data.element) { data.element = pick; data.face = undefined; }
      last = { x: ev.clientX, y: ev.clientY, el: pick, t: now };
    } catch (e) { L.warn('click cycle failed', e); }
  }

  // =================================================================== the name of the part under the mouse
  var tip = null, hoverTimer = 0, hoverCanvas = null, onHover = null, onLeave = null;
  function pathOf(el) {
    var names = [el.name];
    for (var p = el.parent; p && p !== 'root'; p = p.parent) names.unshift(p.name);
    return names.join(' \u203A ');
  }
  function hoverAt(ev) {
    var pv = G.Preview && G.Preview.selected, THREE = G.THREE; if (!pv || !pv.canvas || !M.inEdit()) return null;
    var r = pv.canvas.getBoundingClientRect(); if (!r.width) return null;
    var mouse = new THREE.Vector2((ev.clientX - r.left) / r.width * 2 - 1, -((ev.clientY - r.top) / r.height) * 2 + 1);
    var rc = new THREE.Raycaster(); rc.setFromCamera(mouse, pv.camera);
    var els = (G.Outliner && G.Outliner.elements ? G.Outliner.elements : []).filter(function (e) { return e.visibility !== false && e.mesh; });
    var hits = rc.intersectObjects(els.map(function (e) { return e.mesh; }), false);
    if (!hits.length) return null;
    var hit = els[els.map(function (e) { return e.mesh; }).indexOf(hits[0].object)];
    return hit || null;
  }
  function showTip(ev, el) {
    if (!tip) { tip = document.createElement('div'); tip.className = 'cmp_hover'; document.body.appendChild(tip); }
    tip.textContent = pathOf(el);
    tip.style.left = (ev.clientX + 16) + 'px'; tip.style.top = (ev.clientY + 14) + 'px'; tip.style.display = '';
  }
  function hideTip() { if (tip) tip.style.display = 'none'; }
  function installHover() {
    onHover = function (ev) {
      if (!hoverItem || !hoverItem.value || ev.buttons) { hideTip(); return; }
      if (hoverTimer) return;
      hoverTimer = setTimeout(function () {
        hoverTimer = 0;
        var el = null; try { el = hoverAt(ev); } catch (e) { el = null; }
        if (el) showTip(ev, el); else hideTip();
      }, 60);
    };
    onLeave = function () { hideTip(); };
    function attach() {
      var pv = G.Preview && G.Preview.selected, cv = pv && pv.canvas;
      if (!cv || cv === hoverCanvas) return;
      if (hoverCanvas) { hoverCanvas.removeEventListener('mousemove', onHover); hoverCanvas.removeEventListener('mouseleave', onLeave); }
      hoverCanvas = cv; cv.addEventListener('mousemove', onHover); cv.addEventListener('mouseleave', onLeave);
    }
    attach();
    var again = setInterval(attach, 2000);
    L.onUninstall(function () {
      clearInterval(again);
      if (hoverCanvas) { hoverCanvas.removeEventListener('mousemove', onHover); hoverCanvas.removeEventListener('mouseleave', onLeave); }
      hoverCanvas = null; if (tip) tip.remove(); tip = null;
    });
    if (!document.getElementById('cmp_select_style')) {
      var st = document.createElement('style'); st.id = 'cmp_select_style';
      st.textContent = '.cmp_hover{position:fixed;z-index:40;background:rgba(20,20,24,.9);color:#fff;padding:2px 7px;border-radius:4px;font-size:12px;pointer-events:none;white-space:nowrap;border:1px solid var(--color-accent, #3e90ff)}' +
        '.cmp_rename_list{max-height:220px;overflow:auto;font-family:monospace;font-size:12px;margin:8px 0 0;padding:6px 8px;background:var(--color-back);border:1px solid var(--color-border);border-radius:4px}' +
        '.cmp_rename_list div{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.cmp_rename_list .same{opacity:.5}';
      document.head.appendChild(st);
      L.onUninstall(function () { var e = document.getElementById('cmp_select_style'); if (e) e.remove(); });
    }
  }

  // =================================================================== Selecionar parecidos
  // lang: similar.{size|colour|group|twin|name}
  var SIMILAR = ['size', 'colour', 'group', 'twin', 'name'];
  function sizeOf(c) { return [0, 1, 2].map(function (i) { return M.snap(Math.abs(c.to[i] - c.from[i])); }).join(','); }
  function baseName(n) { return String(n || '').replace(/[_\-. ]?\d+$/, '').toLowerCase(); }
  function colourKey(c) { if (M.isCpm() && c.cpm_recolor !== undefined && c.cpm_recolor !== null && c.cpm_recolor !== -1) return 'r' + c.cpm_recolor; return 'm' + (c.color === undefined ? '' : c.color); }
  /** the parts that are 'like' the selection; how = size | colour | group | twin | name */
  function similar(how) {
    var sel = M.selCubes(), all = M.allCubes(), out = sel.slice();
    if (!sel.length) return [];
    if (how === 'size') { var ks = sel.map(sizeOf); all.forEach(function (c) { if (ks.indexOf(sizeOf(c)) >= 0 && out.indexOf(c) < 0) out.push(c); }); }
    else if (how === 'colour') { var kc = sel.map(colourKey); all.forEach(function (c) { if (kc.indexOf(colourKey(c)) >= 0 && out.indexOf(c) < 0) out.push(c); }); }
    else if (how === 'group') { sel.forEach(function (s) { var p = s.parent; all.forEach(function (c) { if (c.parent === p && out.indexOf(c) < 0) out.push(c); }); }); }
    else if (how === 'name') { var kn = sel.map(function (c) { return baseName(c.name); }); all.forEach(function (c) { if (kn.indexOf(baseName(c.name)) >= 0 && out.indexOf(c) < 0) out.push(c); }); }
    else if (how === 'twin') {
      sel.forEach(function (s) {
        var b = M.cubeBox(s);
        all.forEach(function (c) {
          if (c === s || out.indexOf(c) >= 0) return;
          var o = M.cubeBox(c), e = 0.01;
          if (Math.abs(o.min.x + b.max.x) < e && Math.abs(o.max.x + b.min.x) < e && Math.abs(o.min.y - b.min.y) < e && Math.abs(o.max.y - b.max.y) < e && Math.abs(o.min.z - b.min.z) < e && Math.abs(o.max.z - b.max.z) < e) out.push(c);
        });
      });
    }
    return out;
  }
  function selectSimilar(how) {
    var list = similar(how);
    if (!list.length) { G.Blockbench.showQuickMessage(T('need.cubes'), 2500); return 0; }
    M.selectNodes(list);
    G.Blockbench.showQuickMessage(T('similar.done', list.length), 1800);
    return list.length;
  }
  function similarChildren() {
    return SIMILAR.map(function (k) { return { id: 'cpmplus_model_similar_' + k, name: T('similar.' + k), icon: 'select_all', click: function () { selectSimilar(k); } }; });
  }

  // =================================================================== Renomear em lote
  // lang: rename.m.{number|affix|replace|swap|clean}
  var RMODES = ['number', 'affix', 'replace', 'swap', 'clean'];
  var SWAP = [['left', 'right'], ['esquerda', 'direita'], ['esquerdo', 'direito'], ['esq', 'dir'], ['l', 'r'], ['e', 'd']];
  function keepCase(src, word) {
    if (src === src.toUpperCase() && src.length > 1) return word.toUpperCase();
    if (src[0] === src[0].toUpperCase() && src[0] !== src[0].toLowerCase()) return word[0].toUpperCase() + word.slice(1);
    return word;
  }
  /**
   * swaps left <-> right words (whole words only); a single letter (l / r / e / d) only when it stands alone between
   * separators (arm_l, L_arm, perna.e) - never inside 'R2D2'
   */
  function swapSides(name) {
    var s = String(name);
    return s.replace(/[A-Za-z\u00C0-\u00FF]+/g, function (w, at) {
      var lw = w.toLowerCase();
      if (w.length === 1) {
        var before = at > 0 ? s[at - 1] : '', after = s[at + 1] || '';
        if ((before && !/[_.\- ]/.test(before)) || (after && !/[_.\- ]/.test(after))) return w;
      }
      var one = swapWord(w); if (one !== null) return one;
      // camelCase: LeftArm -> RightArm (only whole words of 2+ letters)
      var parts = w.split(/(?=[A-Z\u00C0-\u00DE])/);
      if (parts.length > 1) return parts.map(function (p) { var x = p.length > 1 ? swapWord(p) : null; return x === null ? p : x; }).join('');
      return w;
    });
  }
  function swapWord(w) {
    var lw = w.toLowerCase();
    for (var i = 0; i < SWAP.length; i++) {
      var a = SWAP[i][0], b = SWAP[i][1];
      if (lw === a) return keepCase(w, b);
      if (lw === b) return keepCase(w, a);
    }
    return null;
  }
  function clean(name) {
    return String(name).normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^A-Za-z0-9_.\-]+/g, '_').replace(/_+/g, '_').replace(/^_|_$/g, '') || 'parte';
  }
  function newNames(o, nodes) {
    var start = Math.round(Number(o.start)), pad = String(nodes.length - 1 + start).length;
    return nodes.map(function (n, i) {
      var old = n.name;
      switch (o.mode) {
        case 'number': { var k = String(start + i); if (o.pad) while (k.length < Math.max(2, pad)) k = '0' + k; return (o.base || 'parte') + (o.sep === undefined ? '_' : o.sep) + k; }
        case 'affix': return (o.prefix || '') + old + (o.suffix || '');
        case 'replace': {
          if (!o.find) return old;
          var re = new RegExp(String(o.find).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), o.matchCase ? 'g' : 'gi');
          return old.replace(re, o.repl || '');
        }
        case 'swap': return swapSides(old);
        case 'clean': return clean(old);
      }
      return old;
    });
  }
  function targets(deep) {
    var nodes = M.selNodes();
    if (!deep) return nodes;
    var out = [];
    nodes.forEach(function walk(n) { if (out.indexOf(n) < 0) out.push(n); if (M.isGroup(n)) (n.children || []).forEach(function (k) { if (M.isGroup(k) || M.isCube(k)) walk(k); }); });
    return out;
  }
  function rename(o, nodes) {
    nodes = nodes || targets(o.deep);
    if (!nodes.length) throw new Error(T('need.sel'));
    var names = newNames(o, nodes), changed = nodes.filter(function (n, i) { return names[i] && names[i] !== n.name; });
    if (!changed.length) return 0;
    M.edit(T('rename'), { elements: nodes.filter(M.isCube), groups: nodes.filter(M.isGroup), outliner: true }, function () {
      nodes.forEach(function (n, i) { if (names[i] && names[i] !== n.name) n.name = names[i]; });
    });
    M.refresh();
    return changed.length;
  }
  var ndlg = null;
  function openRename() {
    if (!M.selNodes().length) { G.Blockbench.showQuickMessage(T('need.sel'), 2500); return null; }
    var modes = {}; RMODES.forEach(function (m) { modes[m] = T('rename.m.' + m); });
    var is = function (m) { return function (f) { return f.mode === m; }; };
    ndlg = new G.Dialog({
      id: 'cpmplus_model_rename', title: T('rename'), width: 500,
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('rename.tip')) + '</p>'],
      form: {
        mode: { label: T('rename.f.mode'), type: 'select', options: modes, value: 'number' },
        base: { label: T('rename.f.base'), type: 'text', value: (M.selNodes()[0] && M.selNodes()[0].name.replace(/[_\-. ]?\d+$/, '')) || 'parte', condition: is('number') },
        start: { label: T('rename.f.start'), type: 'number', value: 1, step: 1, condition: is('number') },
        pad: { label: T('rename.f.pad'), type: 'checkbox', value: false, condition: is('number') },
        prefix: { label: T('rename.f.prefix'), type: 'text', value: '', condition: is('affix') },
        suffix: { label: T('rename.f.suffix'), type: 'text', value: '', condition: is('affix') },
        find: { label: T('rename.f.find'), type: 'text', value: '', condition: is('replace') },
        repl: { label: T('rename.f.repl'), type: 'text', value: '', condition: is('replace') },
        matchCase: { label: T('rename.f.case'), type: 'checkbox', value: false, condition: is('replace') },
        deep: { label: T('rename.f.deep'), type: 'checkbox', value: false }
      },
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        try { var n = rename(f); G.Blockbench.showQuickMessage(T('rename.done', n), 1800); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3000); return false; }
        ndlg = null;
      },
      onCancel: function () { ndlg = null; }
    });
    ndlg.show();
    function update(f) {
      var root = ndlg && ndlg.object; if (!root) return;
      var box = root.querySelector('.cmp_rename_list');
      if (!box) { box = document.createElement('div'); box.className = 'cmp_rename_list'; (root.querySelector('.dialog_content') || root).appendChild(box); }
      var nodes = targets(f.deep), names = newNames(f, nodes), html = [];
      nodes.slice(0, 40).forEach(function (n, i) { html.push('<div class="' + (names[i] === n.name ? 'same' : '') + '">' + L.esc(n.name) + ' \u2192 ' + L.esc(names[i]) + '</div>'); });
      if (nodes.length > 40) html.push('<div>' + L.esc(T('rename.more', nodes.length - 40)) + '</div>');
      box.innerHTML = html.join('');
    }
    try { update(ndlg.getFormResult()); } catch (e) { }
    return ndlg;
  }

  // =================================================================== registration
  api.install = function () {
    cycleItem = L.mk('Toggle', 'cpmplus_model_click_cycle', { name: T('cycle'), description: T('cycle.desc'), icon: 'flip_to_back', category: 'view', default: L.opt('model.cycle', true), onChange: function (v) { L.setOpt('model.cycle', !!v); } });
    hoverItem = L.mk('Toggle', 'cpmplus_model_hover_names', { name: T('hover'), description: T('hover.desc'), icon: 'label', category: 'view', default: L.opt('model.hover', true), onChange: function (v) { L.setOpt('model.hover', !!v); if (!v) hideTip(); } });
    try { G.Blockbench.on('canvas_select', onCanvasSelect); L.onUninstall(function () { G.Blockbench.removeListener('canvas_select', onCanvasSelect); }); } catch (e) { }
    installHover();
    ['view'].forEach(function (m) { var mm = G.MenuBar && G.MenuBar.menus && G.MenuBar.menus[m]; if (mm && Array.isArray(mm.structure)) { [cycleItem, hoverItem].forEach(function (it) { if (mm.structure.indexOf(it) < 0) { mm.structure.push(it); L.onUninstall(function () { var k = mm.structure.indexOf(it); if (k >= 0) mm.structure.splice(k, 1); }); } }); } });
    M.register({ id: 'cpmplus_model_similar', sec: 'organize', icon: 'select_all', key: 'similar', mode: 'edit', need: 'cubes', spots: ['edit', 'cube'], submenu: similarChildren, run: function (e) { var m = new G.Menu(similarChildren()); m.open(e && e.target ? e.target : e); } });
    M.register({ id: 'cpmplus_model_rename', sec: 'organize', icon: 'drive_file_rename_outline', key: 'rename', mode: 'edit', need: 'sel', spots: ['edit', 'cube', 'group'], run: function () { openRename(); } });
  };
  api.uninstall = function () { if (ndlg) { try { ndlg.close(); } catch (e) { } ndlg = null; } hideTip(); };
  // QA
  api.similar = similar; api.selectSimilar = selectSimilar; api.swapSides = swapSides; api.clean = clean;
  api.rename = function (o, nodes) { return rename(o, nodes); }; api.newNames = newNames; api.openRename = openRename;
  api.cycle = { onCanvasSelect: onCanvasSelect, reset: function () { last = null; } };
  api.hover = { at: hoverAt, path: pathOf };
  return api;
})
