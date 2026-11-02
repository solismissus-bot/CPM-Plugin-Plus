package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.ext.W5StickNatives;
import com.tom.cpm.blockbench.tools.model.StickToRoot;
import com.tom.cpm.blockbench.util.RootSuggest;
import com.tom.cpm.blockbench.util.RootSuggest.Suggestion;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.blockbench.util.StickMath.Placement;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W5-STICK (CPM+ v0.8):
 * <ul>
 * <li>G36 'Grudar na parte do corpo...' (CPM+ &gt; Modelagem, and the outliner context menu of groups and cubes):
 * Edit mode and CPM projects only.</li>
 * <li>G6 'Resolver...' on the loose-cube export warning (WarnFixes key 'looseCubes'): opens the 'Peças soltas'
 * dialog.</li>
 * </ul>
 * In test mode only: the 'w5stick.*' tools of the QA scenarios.
 */
public final class W5StickInstall {
	/** WarnEntry key of the loose-cube export row (BlockbenchExport.convertLooseCubes) */
	public static final String LOOSE_KEY = "looseCubes";

	private W5StickInstall() {
	}

	public static void install() {
		Action stick = PlusMenus.action(StickToRoot.ACTION_ID, "cpmplus.w5stick.action.stick", "push_pin", Cond.CPM_ONLY,
				W5StickNatives::isEditMode, StickToRoot::openForSelection);
		PlusMenus.add(PlusMenus.SUB_MODEL, stick);
		PlusMenus.addToContext(W5StickNatives.groupMenu(), stick, "");
		PlusMenus.addToContext(W5StickNatives.cubeMenu(), stick, "");
		WarnFixes.register(LOOSE_KEY, "cpmplus.w5stick.fix.resolve", 10, w -> () -> StickToRoot.resolveLoose(w.getUuids()));
		if (PlusTestHooks.enabled())installTestTools();
	}

	public static void uninstall() {
		// everything above is removed by PluginStart.cleanup (PlusMenus / WarnFixes register their removers)
	}

	// ------------------------------------------------------------------ QA tools (test mode only)

	private static void installTestTools() {
		PlusTestHooks.register("w5stick.suggest", o -> {
			Object n = node(str(o, "name"));
			JsPropertyMap<Object> r = JsPropertyMap.of();
			if (n == null) {
				r.set("ok", false);
				return r;
			}
			Suggestion s = RootSuggest.suggest(n);
			r.set("ok", true);
			r.set("part", s.part);
			r.set("kind", s.kind.name());
			r.set("second", s.second);
			r.set("dist", s.dist);
			r.set("secondDist", s.secondDist);
			r.set("suggestRoot", RootSuggest.suggestRoot(n));
			return r;
		});
		PlusTestHooks.register("w5stick.boxes", o -> {
			SkinType st = SkinType.get(str(o, "skinType") != null ? str(o, "skinType") : "default");
			if (st == null || st == SkinType.UNKNOWN)st = SkinType.DEFAULT;
			JsPropertyMap<Object> r = JsPropertyMap.of();
			for (PlayerModelParts p : RootSuggest.PARTS) {
				double[][] b = RootSuggest.localBox(p, st);
				JsPropertyMap<Object> e = JsPropertyMap.of();
				e.set("min", arr(b[0]));
				e.set("max", arr(b[1]));
				r.set(p.getName(), e);
			}
			return r;
		});
		PlusTestHooks.register("w5stick.targets", o -> {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			StickToRoot.targets().forEach(r::set);
			return r;
		});
		// apply / check without the dialogs: names of pieces + target values ('head', 'new:right_arm', 'g:<uuid>')
		PlusTestHooks.register("w5stick.apply", o -> {
			List<Object> nodes = new ArrayList<>();
			for (String n : list(o, "nodes"))nodes.add(node(n));
			List<String> targets = list(o, "targets");
			JsPropertyMap<Object> r = JsPropertyMap.of();
			StickToRoot.Result chk = StickToRoot.check(nodes, targets);
			r.set("checkErrors", strs(chk.errors));
			r.set("checkWarnings", strs(chk.warnings));
			if (Js.isTruthy(o.get("checkOnly")) || (!chk.errors.isEmpty() && !Js.isTruthy(o.get("force")))) {
				r.set("applied", false);
				return r;
			}
			StickToRoot.Result res = StickToRoot.apply(nodes, targets, Js.isTruthy(o.get("folder")));
			r.set("applied", true);
			r.set("changed", res.changed);
			r.set("moved", res.moved);
			r.set("created", strs(res.created));
			JsArray<Object> pl = new JsArray<>();
			for (Placement p : res.placements) {
				JsPropertyMap<Object> e = JsPropertyMap.of();
				e.set("plain", p.plain);
				e.set("rounded", p.rounded);
				e.set("residual", p.residual);
				e.set("origin", arr(p.origin));
				e.set("rotation", arr(p.rotation));
				pl.push(e);
			}
			r.set("placements", pl);
			return r;
		});
		PlusTestHooks.register("w5stick.moveToBody", o -> {
			List<Cube> cubes = new ArrayList<>();
			for (String n : list(o, "nodes")) {
				Object c = node(n);
				if (c instanceof Cube)cubes.add((Cube) c);
			}
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("changed", StickToRoot.moveToBody(cubes));
			return r;
		});
		PlusTestHooks.register("w5stick.state", o -> {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("editMode", W5StickNatives.isEditMode());
			r.set("looseAck", StickToRoot.isLooseAck());
			r.set("skinType", RootUtil.projectSkinType().getName());
			return r;
		});
	}

	/** a group (first) or cube with this name */
	private static Object node(String name) {
		if (name == null)return null;
		for (Group g : Group.all)if (name.equals(g.name))return g;
		for (Cube c : Cube.all.asList())if (name.equals(c.name))return c;
		return null;
	}

	private static String str(JsPropertyMap<Object> o, String k) {
		Object v = o.get(k);
		return v == null ? null : String.valueOf(v);
	}

	private static List<String> list(JsPropertyMap<Object> o, String k) {
		List<String> out = new ArrayList<>();
		Object v = o.get(k);
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
		}
		return out;
	}

	private static JsArray<Object> arr(double[] v) {
		JsArray<Object> a = new JsArray<>();
		for (double d : v)a.push(d);
		return a;
	}

	private static JsArray<Object> strs(List<String> l) {
		JsArray<Object> a = new JsArray<>();
		for (String s : l)a.push(s);
		return a;
	}
}
