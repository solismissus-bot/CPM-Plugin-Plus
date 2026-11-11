package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.tools.bend.BendCube;
import com.tom.cpm.blockbench.tools.bend.BendDialogs;
import com.tom.cpm.blockbench.tools.bend.BendLimb;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W7-RIG (CPM+ v0.8), lite unit 'bend': G42 'Membros dobráveis' (CPM+ &gt; Modelagem,
 * root / joint context menu) and G57 'Curvar peça' (CPM+ &gt; Modelagem, cube context menu). In test mode the
 * 'w7rig.*' tools drive the same cores.
 */
public final class W7RigInstall {
	private W7RigInstall() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_MODEL, "w7rig_bend", "cpmplus.w7rig.menu.bend", "accessibility_new", Cond.CPM_ONLY, () -> BendDialogs.openBend(null));
		PlusMenus.addAction(PlusMenus.SUB_MODEL, "w7rig_curve", "cpmplus.w7rig.menu.curve", "gesture", Cond.CPM_ONLY, BendDialogs::openCurve);

		Menu gm = protoMenu("Group");
		if (gm != null) {
			Action bend = PlusMenus.action("w7rig_ctx_bend", "cpmplus.w7rig.ctx.bend", "accessibility_new", Cond.CPM_ONLY,
					() -> {
						Group g = selectedGroup();
						return BendLimb.isLimb(g) && BendLimb.findJoint(g) == null;
					}, () -> BendDialogs.openBend(selectedGroup()));
			PlusMenus.addToContext(gm, bend, "");
			Action unbend = PlusMenus.action("w7rig_ctx_unbend", "cpmplus.w7rig.ctx.unbend", "undo", Cond.CPM_ONLY,
					() -> jointOf(selectedGroup()) != null, () -> BendDialogs.openUnbend(jointOf(selectedGroup())));
			PlusMenus.addToContext(gm, unbend, "");
			Action test = PlusMenus.action("w7rig_ctx_test", "cpmplus.w7rig.ctx.test", "play_arrow", Cond.CPM_ONLY,
					() -> jointOf(selectedGroup()) != null, () -> BendLimb.testBend(jointOf(selectedGroup())));
			PlusMenus.addToContext(gm, test, "");
		}
		Menu cm = protoMenu("Cube");
		if (cm != null) {
			Action curve = PlusMenus.action("w7rig_ctx_curve", "cpmplus.w7rig.ctx.curve", "gesture", Cond.CPM_ONLY,
					() -> BendCube.selectedCube() != null, BendDialogs::openCurve);
			PlusMenus.addToContext(cm, curve, "");
		}

		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	private static native Menu protoMenu(String type) /*-{
		var T = $wnd[type];
		return (T && T.prototype && T.prototype.menu) || null;
	}-*/;

	private static Group selectedGroup() {
		try {
			Group g = Group.first_selected;
			if (g != null && Js.isTruthy(g))return g;
			if (com.tom.cpm.blockbench.proxy.Project.selected_groups != null && com.tom.cpm.blockbench.proxy.Project.selected_groups.length > 0)
				return com.tom.cpm.blockbench.proxy.Project.selected_groups.getAt(0);
		} catch (Throwable e) {
		}
		return null;
	}

	/** the joint group of g: g itself when marked, or the joint inside a bent root */
	private static Group jointOf(Group g) {
		if (g == null)return null;
		if (BendLimb.hasMark(g))return g;
		if (g.parent == Group.ROOT)return BendLimb.findJoint(g);
		return null;
	}

	// ====================================================================== test tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		return v != null && "number".equals(Js.typeof(v)) ? Js.asDouble(v) : def;
	}

	private static boolean bool(JsPropertyMap<Object> o, String k, boolean def) {
		Object v = o.get(k);
		return v == null ? def : Js.isTruthy(v);
	}

	private static List<String> strs(JsPropertyMap<Object> o, String k) {
		List<String> out = new ArrayList<>();
		Object v = o.get(k);
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
		}
		return out;
	}

	private static JsPropertyMap<Object> result(boolean ok, boolean changed, List<String> warnings, String error) {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", ok);
		r.set("changed", changed);
		JsArray<Object> w = new JsArray<>();
		if (warnings != null)for (String s : warnings)w.push(s);
		r.set("warnings", w);
		JsArray<Object> e = new JsArray<>();
		if (error != null)e.push(error);
		r.set("errors", e);
		return r;
	}

	private static JsArray<Object> arr(List<?> l) {
		JsArray<Object> a = new JsArray<>();
		for (Object o : l)a.push(o);
		return a;
	}

	private static Cube cubeByUuidOrName(String s) {
		if (s == null)return null;
		Cube c = null;
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++)if (s.equals(cubesAll.getAt(i).uuid))return cubesAll.getAt(i);
		for (int i = 0; i < cubesAll.length; i++)if (c == null && s.equals(cubesAll.getAt(i).name))c = cubesAll.getAt(i);
		return c;
	}

	private static BendLimb.Opts bendOpts(JsPropertyMap<Object> o, String root) {
		BendLimb.Opts b = new BendLimb.Opts();
		b.root = root;
		b.cut = (int) num(o, "cut", -1);
		b.dir = str(o, "dir", null);
		b.layer = bool(o, "layer", true);
		b.moveItem = bool(o, "moveItem", true);
		b.moveBelow = bool(o, "moveBelow", true);
		b.gap = str(o, "gap", "none");
		b.cube = cubeByUuidOrName(str(o, "cube", null));
		return b;
	}

	private static Group groupByUuidOrName(String s) {
		if (s == null)return null;
		for (Group g : Group.all)if (s.equals(g.uuid))return g;
		for (Group g : Group.all)if (s.equals(g.name))return g;
		return null;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w7rig.bendPlan", o -> {
			BendLimb.Plan p = BendLimb.plan(bendOpts(o, str(o, "root", "right_arm")));
			JsPropertyMap<Object> r = result(p.error == null, false, p.warnings, p.errorText());
			if (p.error == null) {
				r.set("h", (double) p.h);
				r.set("cut", (double) p.cut);
				List<Object> cuts = new ArrayList<>();
				for (int k : p.cuts)cuts.add((double) k);
				r.set("cuts", arr(cuts));
				r.set("slim", p.slim);
				r.set("hasLayer", p.layer != null);
				r.set("layerFromVanilla", p.layerFromVanilla);
				r.set("jointName", p.jointName);
				r.set("dir", p.dir);
				r.set("pivot", arr(java.util.Arrays.asList((double) p.pivot[0], (double) p.pivot[1], (double) p.pivot[2])));
				List<Object> below = new ArrayList<>();
				for (OutlinerNode n : p.below)below.add(n.uuid);
				r.set("below", arr(below));
				r.set("base", p.base.uuid);
				r.set("slot", p.slotGroup != null ? p.slotGroup.uuid : null);
			}
			return r;
		});
		PlusTestHooks.register("w7rig.bendLimb", o -> {
			List<String> roots = strs(o, "roots");
			if (roots.isEmpty())roots.add(str(o, "root", "right_arm"));
			List<BendLimb.Plan> plans = new ArrayList<>();
			for (String root : roots) {
				BendLimb.Plan p = BendLimb.plan(bendOpts(o, root));
				if (p.error != null)return result(false, false, null, p.errorText());
				plans.add(p);
			}
			int before = BendCube.undoIndex();
			BendLimb.Result res = BendLimb.apply(plans);
			JsPropertyMap<Object> r = result(true, true, res.warnings, null);
			List<Object> j = new ArrayList<>();
			for (Group g : res.joints)j.add(g.uuid);
			List<Object> c = new ArrayList<>();
			for (Cube cu : res.cubes)c.add(cu.uuid);
			r.set("joints", arr(j));
			r.set("cubes", arr(c));
			r.set("undoAdded", (double) (BendCube.undoIndex() - before));
			return r;
		});
		PlusTestHooks.register("w7rig.unbendLimb", o -> {
			Group g = groupByUuidOrName(str(o, "uuid", null));
			if (g == null || !BendLimb.hasMark(g))return result(false, false, null, I18n.get("cpmplus.w7rig.unbend.err.noMark"));
			List<String> anims = BendLimb.animationsUsing(g);
			if (!anims.isEmpty() && !bool(o, "force", false))return result(false, false, anims, "animations: " + String.join(", ", anims));
			BendLimb.unbend(g);
			return result(true, true, null, null);
		});
		PlusTestHooks.register("w7rig.openDialog", o -> {
			String which = str(o, "which", "bend");
			switch (which) {
			case "bend": BendDialogs.openBend(groupByUuidOrName(str(o, "root", null))); break;
			case "unbend": BendDialogs.openUnbend(groupByUuidOrName(str(o, "uuid", null))); break;
			case "curve": BendDialogs.openCurve(cubeByUuidOrName(str(o, "cube", null))); break;
			default: return result(false, false, null, "unknown dialog " + which);
			}
			return result(true, false, null, null);
		});
		PlusTestHooks.register("w7rig.bendCube", o -> {
			Cube cube = cubeByUuidOrName(str(o, "cube", null));
			BendCube.Opts op = new BendCube.Opts();
			if (o.get("axis") != null)op.axis = String.valueOf(o.get("axis"));
			op.dir = str(o, "dir", "");
			op.angle = num(o, "angle", 90);
			op.slices = (int) num(o, "slices", 4);
			op.pivot = str(o, "pivot", "inner");
			op.close = bool(o, "close", false);
			op.flip = bool(o, "flip", false);
			JsPropertyMap<Object> m;
			if (bool(o, "planOnly", false)) {
				String why = BendCube.refuse(cube);
				if (why != null)return result(false, false, null, I18n.get(why));
				JsPropertyMap<Object> p = BendCube.plan(cube, op);
				m = result(Js.isTruthy(p.get("ok")), false, null, null);
				m.set("plan", p);
				m.set("status", BendCube.statusText(p, op));
				m.set("errors", p.get("errors"));
				return m;
			}
			int before = BendCube.undoIndex();
			BendCube.Result r = BendCube.apply(cube, op);
			m = result(r.ok, r.changed, null, null);
			JsArray<Object> g = new JsArray<>(), c = new JsArray<>(), e = new JsArray<>();
			for (Group x : r.groups)g.push(x.uuid);
			for (Cube x : r.cubes)c.push(x.uuid);
			for (String s : r.errors)e.push(s);
			m.set("groups", g);
			m.set("cubes", c);
			m.set("errors", e);
			m.set("gap", r.gap);
			m.set("realAngle", r.realAngle);
			m.set("extend", r.extend);
			m.set("undoAdded", (double) (BendCube.undoIndex() - before));
			if (r.plan != null)m.set("status", BendCube.statusText(r.plan, op));
			return m;
		});
	}
}
