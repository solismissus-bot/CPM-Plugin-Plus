package com.tom.cpm.blockbench.tools.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.ext.W5EffectsNatives;
import com.tom.cpm.blockbench.proxy.ext.W5EffectsNatives.Fn;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W5-EFFECTS, G14) 'Mostrar ao pintar': paint UNDER the skin's 2nd layer. In Paint mode (CPM projects) a bar in
 * the viewport offers Tudo / Pele de baixo (hides the 2nd-layer shells) / Só a 2ª camada (hides the base cubes that
 * have a shell) / Só o selecionado (hides everything else).
 * <ul>
 * <li>DISPLAY ONLY: only element.mesh.visible is set (Blockbench's paint raycast and paint mirroring skip invisible
 * meshes). element.visibility, textures and the project are never written: no undo entry, nothing saved or
 * exported.</li>
 * <li>Shells: cubes marked by 'Adicionar 2ª camada' (CubeData extra 'l2', set inside that undo entry), cubes with the
 * exact 2nd-layer geometry of their main part (box, inflate &gt; 0, the part's 2nd-layer UV), and the geometry rule:
 * a cube whose inflated box contains a sibling with a smaller inflate (tolerance 0.01). Never by name.</li>
 * <li>Re-applied whenever Blockbench resets mesh.visible (preview controller setup / update_visibility / update_all,
 * mode change, selection, undo / redo, edits) and restored to the CURRENT element.visibility when leaving Paint mode,
 * switching / closing the project and unloading the plugin. Save / export never read mesh.visible, so they are the
 * same with any mode.</li>
 * <li>A badge says what is hidden ('Mostrando: pele de baixo (X peças escondidas só na tela) · Voltar a mostrar
 * tudo'); the modes are Blockbench actions (rebindable keys, none required); a one-time-per-session hint when a paint
 * click lands on a transparent shell pixel.</li>
 * </ul>
 */
public final class PaintView {
	public enum Mode {
		ALL, UNDER, LAYER2, SELECTED
	}

	public static final String L2_KEY = "l2";
	private static final String L = "cpmplus.w5effects.paint.";
	private static final String BAR_ID = "cpmplus_w5fx_paint_bar";

	private static Mode mode = Mode.ALL;
	/** uuids this tool made invisible (mesh.visible = false) */
	private static final Set<String> hidden = new HashSet<>();
	/** uuids that must be invisible in the current mode */
	private static Set<String> target = new HashSet<>();
	private static int shellCount;
	private static boolean installed, hintShown;
	private static double timer = -1;
	private static Object bar, downListener;
	private static final List<Runnable> offs = new ArrayList<>();
	private static final java.util.function.Consumer<List<OutlinerElement>> MARK = PaintView::markShells;

	private PaintView() {
	}

	// ====================================================================== install

	public static void install() {
		installed = true;
		hintShown = false;
		mode = Mode.ALL;
		hidden.clear();
		target = new HashSet<>();
		PluginStart.addEventListener("select_mode", e -> {
			String id = W5EffectsNatives.modeIdOf(e);
			if (!"paint".equals(id))setMode(Mode.ALL);
			else schedule();
		});
		PluginStart.addEventListener("unselect_mode", e -> {
			if ("paint".equals(W5EffectsNatives.modeIdOf(e)))setMode(Mode.ALL);
		});
		for (String ev : new String[] {"select_project", "unselect_project", "close_project"}) {
			PluginStart.addEventListener(ev, e -> {
				restoreAll();
				mode = Mode.ALL;
				schedule();
			});
		}
		for (String ev : new String[] {"update_selection", "undo", "redo", "load_undo_save", "finished_edit"}) {
			PluginStart.addEventListener(ev, e -> schedule());
		}
		// Blockbench resets mesh.visible from element.visibility here: enforce the mode again for that element
		Fn enforce = PaintView::enforce;
		for (String cls : new String[] {"Cube", "Mesh"}) {
			for (String ev : new String[] {"setup", "update_visibility", "update_all"}) {
				if (W5EffectsNatives.onPreviewController(cls, ev, enforce))offs.add(() -> W5EffectsNatives.offPreviewController(cls, ev, enforce));
			}
		}
		// 'Adicionar 2ª camada' marks its cubes (inside its own undo entry)
		BBActions.secondLayerListeners.add(MARK);
		offs.add(() -> BBActions.secondLayerListeners.remove(MARK));
		// rebindable actions (CPM+ > Prévia); no default keys, nothing needs a modifier
		String[][] acts = {{"all", "cpmplus_w5fx_paint_all", "layers"}, {"under", "cpmplus_w5fx_paint_under", "layers_clear"},
				{"layer2", "cpmplus_w5fx_paint_layer2", "layers"}, {"selected", "cpmplus_w5fx_paint_selected", "filter_center_focus"}};
		for (String[] a : acts) {
			Mode m = parse(a[0]);
			Action act = PlusMenus.action(a[1], L + "action." + a[0], a[2], Cond.CPM_ONLY, () -> {
				if (!W5EffectsNatives.isPaintMode())W5EffectsNatives.selectPaintMode();
				setMode(m);
			});
			PlusMenus.add(PlusMenus.SUB_VIEW, act);
		}
		Action toggle = PlusMenus.action("cpmplus_w5fx_paint_toggle", L + "action.toggle", "layers_clear", Cond.CPM_ONLY, () -> {
			if (!W5EffectsNatives.isPaintMode())W5EffectsNatives.selectPaintMode();
			setMode(mode == Mode.UNDER ? Mode.ALL : Mode.UNDER);
		});
		PlusMenus.add(PlusMenus.SUB_VIEW, toggle);
		bar = W5EffectsNatives.ensureBar(BAR_ID, (n, v) -> setMode(parse(String.valueOf(v))));
		PluginStart.cleanup.add(() -> W5EffectsNatives.removeById(BAR_ID));
		downListener = W5EffectsNatives.addViewportDown(PaintView::onViewportDown);
		final Object dl = downListener;
		PluginStart.cleanup.add(() -> W5EffectsNatives.removeViewportDown(dl));
		schedule();
	}

	public static void uninstall() {
		restoreAll();
		installed = false;
		mode = Mode.ALL;
		for (Runnable r : offs) {
			try {
				r.run();
			} catch (Throwable e) {
			}
		}
		offs.clear();
		if (timer >= 0)W5EffectsNatives.cancelLater(timer);
		timer = -1;
		W5EffectsNatives.removeById(BAR_ID);
		if (downListener != null)W5EffectsNatives.removeViewportDown(downListener);
		downListener = null;
		bar = null;
	}

	public static Mode parse(String s) {
		if ("under".equals(s))return Mode.UNDER;
		if ("layer2".equals(s))return Mode.LAYER2;
		if ("selected".equals(s))return Mode.SELECTED;
		return Mode.ALL;
	}

	public static Mode getMode() {
		return mode;
	}

	// ====================================================================== mode

	/** sets the mode and applies it now (ALL restores every part) */
	public static void setMode(Mode m) {
		mode = m == null ? Mode.ALL : m;
		apply();
	}

	private static boolean active() {
		return installed && mode != Mode.ALL && CPMCodec.isCPM() && W5EffectsNatives.isPaintMode();
	}

	static void schedule() {
		if (!installed || timer >= 0)return;
		timer = W5EffectsNatives.later(__ -> {
			timer = -1;
			apply();
		}, 30);
	}

	/** recomputes which parts are hidden and applies it (also updates the bar) */
	public static void apply() {
		if (!installed)return;
		boolean paint = CPMCodec.isCPM() && W5EffectsNatives.isPaintMode();
		if (!paint && hidden.isEmpty()) {
			// nothing hidden and not painting: only the bar (cheap, also with thousands of parts)
			target = new HashSet<>();
			updateBar(false);
			return;
		}
		Set<String> next = new HashSet<>();
		Map<String, Object> byUuid = new HashMap<>();
		JsArray<Object> els = W5EffectsNatives.allElements();
		for (int i = 0; i < els.length; i++) {
			Object o = els.getAt(i);
			String u = W5EffectsNatives.uuidOf(o);
			if (u != null)byUuid.put(u, o);
		}
		boolean cpm = CPMCodec.isCPM();
		Shells sh = paint ? findShells() : new Shells();
		shellCount = sh.shells.size();
		if (active()) {
			switch (mode) {
			case UNDER:
				next.addAll(sh.shells);
				break;
			case LAYER2:
				next.addAll(sh.bases);
				break;
			case SELECTED: {
				JsArray<Object> sel = W5EffectsNatives.selectedElements();
				Set<String> s = new HashSet<>();
				for (int i = 0; i < sel.length; i++)s.add(W5EffectsNatives.uuidOf(sel.getAt(i)));
				if (!s.isEmpty())for (String u : byUuid.keySet())if (!s.contains(u))next.add(u);
				break;
			}
			default:
				break;
			}
		}
		// restore what is no longer hidden (to the CURRENT eye-icon state), hide the new ones
		for (String u : new ArrayList<>(hidden)) {
			if (next.contains(u))continue;
			Object el = byUuid.get(u);
			if (el != null)W5EffectsNatives.setMeshVisible(el, W5EffectsNatives.visibilityOf(el));
			hidden.remove(u);
		}
		for (String u : next) {
			Object el = byUuid.get(u);
			if (el == null)continue;
			W5EffectsNatives.setMeshVisible(el, false);
			hidden.add(u);
		}
		target = next;
		updateBar(cpm);
	}

	/** preview controller event: Blockbench just set mesh.visible from element.visibility */
	private static void enforce(Object evt) {
		if (!installed || target.isEmpty())return;
		Object el = W5EffectsNatives.elementOf(evt);
		String u = W5EffectsNatives.uuidOf(el);
		if (u != null && target.contains(u)) {
			W5EffectsNatives.setMeshVisible(el, false);
			hidden.add(u);
		}
	}

	/** every part back to its eye-icon state (mode unchanged) */
	public static void restoreAll() {
		if (hidden.isEmpty()) {
			target = new HashSet<>();
			return;
		}
		JsArray<Object> els = W5EffectsNatives.allElements();
		for (int i = 0; i < els.length; i++) {
			Object o = els.getAt(i);
			String u = W5EffectsNatives.uuidOf(o);
			if (u != null && hidden.contains(u))W5EffectsNatives.setMeshVisible(o, W5EffectsNatives.visibilityOf(o));
		}
		hidden.clear();
		target = new HashSet<>();
	}

	// ====================================================================== shells

	static final class Shells {
		final Set<String> shells = new HashSet<>();
		/** base cubes that have a shell */
		final Set<String> bases = new HashSet<>();
	}

	private static boolean near(double a, double b) {
		return Math.abs(a - b) <= 0.01;
	}

	private static SkinType skinType() {
		try {
			String st = Project.getData().skinType;
			if (st != null) {
				SkinType t = SkinType.get(st);
				if (t != null && t != SkinType.UNKNOWN)return t;
			}
		} catch (Throwable e) {
		}
		return SkinType.DEFAULT;
	}

	/** the 2nd-layer geometry of a main part (same math as 'Adicionar 2ª camada') */
	private static boolean isSecondLayerSpec(Cube c, Group root, SkinType st) {
		if (!c.box_uv || c.inflate <= 0 || c.uv_offset == null)return false;
		VanillaModelPart part = root.getRootType();
		if (!(part instanceof PlayerModelParts) || part == PlayerModelParts.CUSTOM_PART)return false;
		RootUtil.CubeSpec sp = RootUtil.secondLayerCubeSpec(part, st, root.origin);
		if (sp == null)return false;
		return near(c.from.x, sp.from.x) && near(c.from.y, sp.from.y) && near(c.from.z, sp.from.z)
				&& near(c.to.x, sp.to.x) && near(c.to.y, sp.to.y) && near(c.to.z, sp.to.z)
				&& near(c.uv_offset.x, sp.uvOffset.x) && near(c.uv_offset.y, sp.uvOffset.y);
	}

	/** inflated box of a cube contains the other's inflated box (tolerance 0.01) */
	private static boolean contains(Cube outer, Cube inner) {
		double io = outer.inflate, ii = inner.inflate;
		return Math.min(outer.from.x, outer.to.x) - io <= Math.min(inner.from.x, inner.to.x) - ii + 0.01
				&& Math.min(outer.from.y, outer.to.y) - io <= Math.min(inner.from.y, inner.to.y) - ii + 0.01
				&& Math.min(outer.from.z, outer.to.z) - io <= Math.min(inner.from.z, inner.to.z) - ii + 0.01
				&& Math.max(outer.from.x, outer.to.x) + io >= Math.max(inner.from.x, inner.to.x) + ii - 0.01
				&& Math.max(outer.from.y, outer.to.y) + io >= Math.max(inner.from.y, inner.to.y) + ii - 0.01
				&& Math.max(outer.from.z, outer.to.z) + io >= Math.max(inner.from.z, inner.to.z) + ii - 0.01;
	}

	/** finds the 2nd-layer shells and the base cubes they cover (never by name) */
	static Shells findShells() {
		Shells s = new Shells();
		if (Cube.all == null)return s;
		SkinType st = skinType();
		Map<Object, List<Cube>> byParent = new HashMap<>();
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			byParent.computeIfAbsent(c.parent, k -> new ArrayList<>()).add(c);
		}
		for (Map.Entry<Object, List<Cube>> e : byParent.entrySet()) {
			List<Cube> sib = e.getValue();
			Group parent = e.getKey() instanceof Group ? (Group) e.getKey() : null;
			boolean root = parent != null && parent.parent == Group.ROOT;
			for (Cube c : sib) {
				boolean shell = isMarked(c) || (root && isSecondLayerSpec(c, parent, st));
				for (Cube o : sib) {
					if (o == c || c.inflate <= o.inflate + 0.001 || !contains(c, o))continue;
					shell = true;
					s.bases.add(o.uuid);
				}
				if (shell)s.shells.add(c.uuid);
			}
		}
		// a shell is never also hidden as a base (nested shells)
		s.bases.removeAll(s.shells);
		return s;
	}

	private static boolean isMarked(Cube c) {
		String pd = c.pluginData;
		if (pd == null || !pd.contains(L2_KEY))return false;
		try {
			return Js.isTruthy(c.getData().getExtra(L2_KEY));
		} catch (Throwable e) {
			return false;
		}
	}

	/** BBActions 'Adicionar 2ª camada' listener: marks the new cubes as shells (runs inside its undo entry) */
	private static void markShells(List<OutlinerElement> created) {
		for (OutlinerElement e : created) {
			if (!(e instanceof Cube))continue;
			CubeData cd = ((Cube) e).getData();
			cd.putExtra(L2_KEY, true);
			cd.flush();
		}
	}

	// ====================================================================== bar + hint

	private static void updateBar(boolean cpm) {
		if (bar == null)bar = W5EffectsNatives.ensureBar(BAR_ID, (n, v) -> setMode(parse(String.valueOf(v))));
		boolean show = installed && cpm && W5EffectsNatives.isPaintMode();
		if (!show) {
			W5EffectsNatives.setBarHtml(bar, "", false);
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("<span style='opacity:.8;margin-right:4px;'>").append(PlusDialogs.esc(I18n.get(L + "bar.label"))).append("</span>");
		String[][] b = {{"all", L + "mode.all"}, {"under", L + "mode.under"}, {"layer2", L + "mode.layer2"}, {"selected", L + "mode.selected"}};
		for (String[] x : b) {
			Mode m = parse(x[0]);
			boolean sel = m == mode;
			boolean dis = shellCount == 0 && (m == Mode.UNDER || m == Mode.LAYER2);
			sb.append("<button data-pv='").append(x[0]).append("'").append(dis ? " disabled" : "")
			.append(" style='min-width:0;height:22px;padding:0 6px;margin:0 2px;").append(sel ? "background:var(--color-accent,#3e90ff);color:var(--color-accent_text,#fff);" : "").append("'>")
			.append(PlusDialogs.esc(I18n.get(x[1]))).append("</button>");
		}
		if (shellCount == 0)sb.append("<div class='cpmplus_pv_note' style='font-size:11px;opacity:.75;'>").append(PlusDialogs.esc(I18n.get(L + "noShells"))).append("</div>");
		else sb.append("<div class='cpmplus_pv_note' style='font-size:11px;opacity:.75;'>").append(PlusDialogs.esc(I18n.get(L + "bar.tip"))).append("</div>");
		if (mode != Mode.ALL) {
			String what = I18n.get(L + "showing." + mode.name().toLowerCase());
			sb.append("<div class='cpmplus_pv_badge' style='margin-top:3px;color:var(--color-warning,#e6a23c);'>")
			.append(PlusDialogs.esc(I18n.format(L + "badge", what, hidden.size())))
			.append(" · <a href='#' data-pv='all' style='color:inherit;text-decoration:underline;'>").append(PlusDialogs.esc(I18n.get(L + "showAll"))).append("</a></div>");
		}
		W5EffectsNatives.setBarHtml(bar, sb.toString(), true);
	}

	/** one-time-per-session hint: a paint click hit a transparent pixel of a shell */
	private static void onViewportDown(Object ev) {
		if (hintShown || mode != Mode.ALL || !installed || !CPMCodec.isCPM() || !W5EffectsNatives.isPaintMode())return;
		Object hit = W5EffectsNatives.raycastPixel(ev);
		if (hit == null)return;
		JsPropertyMap<Object> h = Js.uncheckedCast(hit);
		double alpha = Js.asDouble(h.get("alpha"));
		if (alpha < 0 || alpha >= 0.004)return;
		String uuid = String.valueOf(h.get("uuid"));
		if (!findShells().shells.contains(uuid))return;
		hintShown = true;
		PlusDialogs.toast(I18n.get(L + "hint"), "layers_clear", 15000, () -> setMode(Mode.UNDER));
	}

	// ====================================================================== test hooks

	public static JsPropertyMap<Object> testState() {
		JsPropertyMap<Object> m = JsPropertyMap.of();
		Shells s = findShells();
		m.set("ok", true);
		m.set("mode", mode.name());
		m.set("active", active());
		m.set("paint", W5EffectsNatives.isPaintMode());
		JsArray<Object> a = new JsArray<>();
		for (String u : s.shells)a.push(u);
		m.set("shells", a);
		JsArray<Object> b = new JsArray<>();
		for (String u : s.bases)b.push(u);
		m.set("bases", b);
		JsArray<Object> h = new JsArray<>();
		for (String u : hidden)h.push(u);
		m.set("hidden", h);
		return m;
	}
}
