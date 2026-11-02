package com.tom.cpm.blockbench.tools.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Modes;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.ext.W6HelpNatives;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.render.VanillaModelPart;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W6-HELP, G50): 'Ver esqueleto' - bones, joints and CPM roots drawn over the model (Edit and Animate mode).
 * One THREE.Group 'cpmplus_skeleton' in Canvas.scene, registered in Canvas.gizmos (so Canvas.withoutGizmos hides it
 * in screenshots, GIFs and thumbnails). Only a view: no undo, nothing in the project, no effect on the export. Per user
 * on/off and options in PlusPrefs; no default key (Blockbench keybinds can assign one). Topology is rebuilt on
 * selection / edit / undo / redo / project switch; positions follow mesh.matrixWorld every frame; everything is
 * disposed when switched off, on a non-CPM project and on unload.
 */
public final class Skeleton {
	public static final String PREF_ON = "cpmplus.help.skeleton";
	public static final String PREF_LABELS = "cpmplus.help.skeletonLabels";
	public static final String PREF_SELECTED = "cpmplus.help.skeletonSelectedOnly";
	public static final String PREF_AXES = "cpmplus.help.skeletonAxes";

	private static Object state;
	private static boolean on, animHintShown;
	private static Toggle toggle;
	private static double timer = -1;
	private static int rebuilds;

	private Skeleton() {
	}

	public static void install() {
		on = PlusPrefs.getBool(PREF_ON, false);
		toggle = PlusMenus.toggle("w6help_skeleton", "cpmplus.w6help.skel.menu", "account_tree", Cond.CPM_ONLY, on, Skeleton::setOn);
		PlusMenus.add(PlusMenus.SUB_VIEW, toggle);
		PlusMenus.add(PlusMenus.SUB_VIEW, PlusMenus.action("w6help_skeleton_options", "cpmplus.w6help.skel.options", "tune", Cond.CPM_ONLY, Skeleton::options));
		for (String ev : new String[] {"update_selection", "finished_edit", "undo", "redo", "select_project", "load_undo_save", "add_cube", "add_group", "group_elements"}) {
			PluginStart.addEventListener(ev, e -> schedule());
		}
		PluginStart.addEventListener("unselect_project", e -> dispose());
		PluginStart.addEventListener("select_mode", e -> {
			if (on && state != null)animHint();
		});
		if (on)schedule();
	}

	public static void uninstall() {
		if (timer >= 0)W6HelpNatives.cancel(timer);
		timer = -1;
		dispose();
		toggle = null;
	}

	/** the toggle changed (menu, action search or a keybind) */
	public static void setOn(boolean v) {
		on = v;
		PlusPrefs.set(PREF_ON, v);
		if (toggle != null && toggle.value != v) {
			// called by the test hook / options: keep the menu check mark in sync
			toggle.value = v;
			try {
				toggle.updateEnabledState();
			} catch (Throwable e) {
			}
		}
		if (!v) {
			dispose();
			return;
		}
		if (!CPMCodec.isCPM()) {
			PlusDialogs.quick(PlusDialogs.tr("cpmplus.w6help.skel.notCpm"), 3000);
			return;
		}
		rebuild();
		animHint();
	}

	public static boolean isOn() {
		return on;
	}

	private static void animHint() {
		try {
			if (!animHintShown && com.tom.cpm.blockbench.tools.anim.AnimToolSupport.animateMode()) {
				animHintShown = true;
				PlusDialogs.toast(PlusDialogs.tr("cpmplus.w6help.skel.animHint"), "info", 8000, null);
			}
		} catch (Throwable e) {
		}
	}

	private static void schedule() {
		if (!on)return;
		if (timer >= 0)W6HelpNatives.cancel(timer);
		timer = W6HelpNatives.later(40, () -> {
			timer = -1;
			rebuild();
		});
	}

	/** rebuilds the topology now (also the test hook) */
	public static void rebuild() {
		if (!on || !CPMCodec.isCPM()) {
			dispose();
			return;
		}
		if (state == null)state = W6HelpNatives.skelCreate((uuid, x) -> selectGroup(uuid));
		if (state == null)return;
		W6HelpNatives.skelSet(state, buildNodes(), PlusPrefs.getBool(PREF_LABELS, true), PlusPrefs.getBool(PREF_AXES, false));
		rebuilds++;
	}

	public static void dispose() {
		if (state != null)W6HelpNatives.skelDispose(state);
		state = null;
	}

	/** clicking a label selects the group and opens it in the outliner (clicking lines does nothing in v1) */
	private static void selectGroup(String uuid) {
		if (Group.all == null)return;
		for (Group g : Group.all) {
			if (uuid.equals(g.uuid)) {
				try {
					g.select();
					g.openUp();
				} catch (Throwable e) {
				}
				return;
			}
		}
	}

	private static void options() {
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.checkbox("labels", "cpmplus.w6help.skel.opt.labels", PlusPrefs.getBool(PREF_LABELS, true)));
		rows.add(FormRow.checkbox("selectedOnly", "cpmplus.w6help.skel.opt.selectedOnly", PlusPrefs.getBool(PREF_SELECTED, false)));
		rows.add(FormRow.checkbox("axes", "cpmplus.w6help.skel.opt.axes", PlusPrefs.getBool(PREF_AXES, false)));
		rows.add(FormRow.info("legend", null, "cpmplus.w6help.skel.legend"));
		PlusDialogs.form("cpmplus_w6help_skeleton_options", "cpmplus.w6help.skel.options", rows, r -> {
			PlusPrefs.set(PREF_LABELS, r.getBool("labels"));
			PlusPrefs.set(PREF_SELECTED, r.getBool("selectedOnly"));
			PlusPrefs.set(PREF_AXES, r.getBool("axes"));
			if (on)rebuild();
		});
	}

	// ====================================================================== topology + classification

	private static final class Cat {
		String color, label;
		boolean dashed, bad;
		int prio;
	}

	private static Group parentGroup(Object node) {
		Object p = Js.asPropertyMap(node).get("parent");
		if (p == null || "string".equals(Js.typeof(p)) || !(p instanceof Group))return null;
		return (Group) p;
	}

	private static boolean isVisible(Group g) {
		Object v = Js.asPropertyMap(g).get("visibility");
		return v == null || "undefined".equals(Js.typeof(v)) || Js.isTruthy(v);
	}

	/** the groups drawn: outliner order, hidden groups (and what is inside them) skipped */
	private static void collect(Object node, List<Group> out, boolean onlySubtree, Group sel, boolean inSel) {
		if (!(node instanceof Group))return;
		Group g = (Group) node;
		if (!isVisible(g))return;
		boolean in = inSel || !onlySubtree || g == sel;
		if (in)out.add(g);
		if (g.children == null)return;
		for (int i = 0; i < g.children.length; i++)collect(g.children.getAt(i), out, onlySubtree, sel, in);
	}

	private static Object buildNodes() {
		boolean selOnly = PlusPrefs.getBool(PREF_SELECTED, false);
		Group sel = null;
		try {
			sel = Group.first_selected;
		} catch (Throwable e) {
		}
		boolean onlySubtree = selOnly && sel != null;
		List<Group> groups = new ArrayList<>();
		if (Group.all != null) {
			for (Group g : Group.all) {
				if (g.parent == Group.ROOT)collect(g, groups, onlySubtree, sel, false);
			}
		}
		Map<String, Integer> index = new HashMap<>();
		for (int i = 0; i < groups.size(); i++)index.put(groups.get(i).uuid, i);
		String cRoot = W6HelpNatives.themeColor("--color-accent", "#3e90ff");
		String cEquip = W6HelpNatives.themeColor("--color-subtle_text", "#9a9a9a");
		String cItem = W6HelpNatives.themeColor("--color-confirm", "#4caf50");
		String cOwn = W6HelpNatives.themeColor("--color-text", "#d0d0d0");
		String cBad = W6HelpNatives.themeColor("--color-close", "#f44336");
		JsArray<Object> nodes = new JsArray<>();
		Map<String, Cat> cats = new HashMap<>();
		for (Group g : groups) {
			Group p = parentGroup(g);
			Cat parent = p != null ? cats.get(p.uuid) : null;
			Cat c = classify(g, p, parent, sel, cRoot, cEquip, cItem, cOwn, cBad);
			cats.put(g.uuid, c);
			JsPropertyMap<Object> n = JsPropertyMap.of();
			n.set("g", g);
			Integer pi = p != null ? index.get(p.uuid) : null;
			n.set("parent", pi != null ? (double) pi : -1.0);
			n.set("color", c.color);
			n.set("dashed", c.dashed);
			n.set("label", c.label);
			n.set("prio", (double) c.prio);
			n.set("stub", stubOf(g, index));
			nodes.push(n);
		}
		return nodes;
	}

	/** leaf group with cubes: offset from the pivot to the centre of its cubes (group's unrotated frame), else null */
	private static Object stubOf(Group g, Map<String, Integer> drawn) {
		if (g.children == null)return null;
		double x = 0, y = 0, z = 0;
		int n = 0;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode ch = g.children.getAt(i);
			if (ch instanceof Group && drawn.containsKey(((Group) ch).uuid))return null;
			if (ch instanceof Cube) {
				Cube c = (Cube) ch;
				if (c.from == null || c.to == null)continue;
				x += (c.from.x + c.to.x) / 2.0;
				y += (c.from.y + c.to.y) / 2.0;
				z += (c.from.z + c.to.z) / 2.0;
				n++;
			}
		}
		if (n == 0 || g.origin == null)return null;
		JsArray<Double> a = new JsArray<>();
		a.push(x / n - g.origin.x);
		a.push(y / n - g.origin.y);
		a.push(z / n - g.origin.z);
		return a;
	}

	private static String partLabel(VanillaModelPart rt) {
		String k = "cpmplus.w6help.part." + rt.getName().toLowerCase(Locale.ROOT);
		String t = PlusDialogs.tr(k);
		return t.equals(k) ? rt.getName() : t;
	}

	private static Cat classify(Group g, Group parent, Cat pc, Group sel, String cRoot, String cEquip, String cItem, String cOwn, String cBad) {
		Cat c = new Cat();
		boolean top = parent == null;
		VanillaModelPart rt = null;
		if (top) {
			try {
				rt = g.getRootType();
			} catch (Throwable e) {
			}
		}
		String itemSlot = null;
		try {
			itemSlot = g.getData().getItemRenderer();
		} catch (Throwable e) {
		}
		c.dashed = pc != null && pc.dashed;
		c.bad = pc != null && pc.bad;
		c.color = pc != null && pc.bad ? cBad : cOwn;
		if (!g.export) {
			c.bad = true;
			c.color = cBad;
			c.label = g.name + " \u2014 " + PlusDialogs.tr("cpmplus.w6help.skel.noExport");
			c.prio = 8;
		} else if (top && rt == null) {
			c.bad = true;
			c.color = cBad;
			c.label = g.name + " \u2014 " + PlusDialogs.tr("cpmplus.w6help.skel.unknownRoot");
			c.prio = 9;
		} else if (top && rt instanceof PlayerModelParts) {
			boolean dup = g.name.toLowerCase(Locale.ROOT).endsWith("_dup");
			c.color = cRoot;
			c.dashed = dup;
			c.label = dup ? PlusDialogs.trf("cpmplus.w6help.skel.copyOf", partLabel(rt)) : partLabel(rt);
			c.prio = 10;
		} else if (top && rt instanceof RootModelType) {
			c.color = cEquip;
			c.label = g.name + " (" + PlusDialogs.tr("cpmplus.w6help.skel.equipOnly") + ")";
			c.prio = 7;
		} else if (itemSlot != null) {
			c.color = cItem;
			c.label = "\u270b " + itemSlot;
			c.prio = 6;
		} else if (c.bad) {
			c.color = cBad;
		}
		if (g == sel || isSelected(g)) {
			if (c.label == null)c.label = g.name;
			c.prio = 20;
		}
		return c;
	}

	private static boolean isSelected(Group g) {
		try {
			Group[] ms = Group.multi_selected;
			if (ms == null)return false;
			for (Group s : ms)if (s == g)return true;
		} catch (Throwable e) {
		}
		return false;
	}

	// ====================================================================== test hooks

	/** {segments, labels, inGizmos, inScene, nodes, on, labelDom} */
	public static Object state() {
		return W6HelpNatives.skelState(state);
	}

	public static int rebuilds() {
		return rebuilds;
	}

	public static int labelDomCount() {
		return W6HelpNatives.skelLabelDomCount();
	}
}
