package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.tools.anim.WaveChain;
import com.tom.cpm.blockbench.tools.model.MirrorToOtherSide;
import com.tom.cpm.blockbench.util.PartPaths;
import com.tom.cpm.blockbench.util.RootUtil;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W4-ANIM2 (CPM+ v0.8): F9 'Fazer onda' (CPM+ > Animação + group menu in Animate
 * mode) and F12 'Espelhar pro outro lado (CPM)' (CPM+ > Modelagem + group / cube menus in Edit mode). Test mode only:
 * the 'w4anim2.*' tools the QA scenarios drive.
 */
public final class W4Anim2Install {
	private static final String KW = "cpmplus.w4anim2.wave.menu";
	private static final String KM = "cpmplus.w4anim2.mirror.menu";

	static final boolean F12_ENABLED = true;

	private W4Anim2Install() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "w4anim2_wave", KW, "waves", Cond.CPM_ONLY, WaveChain::open);
		// F12 "Espelhar pro outro lado" stays off in the beta: its reuse still fails (clone without storeIDs, geometry
		// changes after moving twice). The tool and its test hooks are kept for the fix.
		if (F12_ENABLED) {
			PlusMenus.addAction(PlusMenus.SUB_MODEL, "w4anim2_mirror", KM, "flip", Cond.CPM_ONLY, MirrorToOtherSide::open);
			// same mirror without the dialog, animations included (Ctrl+Alt+Shift+M)
			PlusMenus.add(PlusMenus.SUB_MODEL, PlusMenus.action("w4anim2_mirror_anims", "cpmplus.w4anim2.mirror.menuAnims", "flip", Cond.CPM_ONLY,
					() -> !Animator.open, MirrorToOtherSide::quickWithAnims));
		}
		// right click on a group (Animate mode: wave; Edit mode: mirror) and on a cube (mirror)
		Menu groupMenu = protoMenu("Group");
		Menu cubeMenu = protoMenu("Cube");
		if (groupMenu != null) {
			Action w = PlusMenus.action("w4anim2_wave_ctx", KW, "waves", Cond.CPM_ONLY, () -> Animator.open, WaveChain::open);
			PlusMenus.addToContext(groupMenu, w, "");
			Action m = PlusMenus.action("w4anim2_mirror_group_ctx", KM, "flip", Cond.CPM_ONLY, () -> F12_ENABLED && !Animator.open, MirrorToOtherSide::open);
			PlusMenus.addToContext(groupMenu, m, "");
		}
		if (cubeMenu != null) {
			Action m = PlusMenus.action("w4anim2_mirror_cube_ctx", KM, "flip", Cond.CPM_ONLY, () -> F12_ENABLED && !Animator.open, MirrorToOtherSide::open);
			PlusMenus.addToContext(cubeMenu, m, "");
		}
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	private static native Menu protoMenu(String cls) /*-{
		var c = $wnd[cls];
		return c && c.prototype && c.prototype.menu ? c.prototype.menu : null;
	}-*/;

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
		return v != null && "boolean".equals(Js.typeof(v)) ? Js.isTruthy(v) : def;
	}

	private static JsArray<Object> arr(List<String> l) {
		JsArray<Object> a = new JsArray<>();
		for (String s : l)a.push(s);
		return a;
	}

	private static List<String> strList(Object v) {
		List<String> out = new ArrayList<>();
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
		}
		return out;
	}

	/** group or cube at 'root/child/.../name' (groups by name along the way, last segment group or cube) */
	private static Object findNode(String path) {
		if (path == null)return null;
		List<String> segs = Arrays.asList(path.split("/"));
		if (segs.size() == 1)return RootUtil.findRootGroup(segs.get(0));
		Group parent = MirrorToOtherSide.findByPath(segs.subList(0, segs.size() - 1));
		if (parent == null)return null;
		String last = segs.get(segs.size() - 1);
		for (int i = 0; parent.children != null && i < parent.children.length; i++) {
			OutlinerNode n = parent.children.getAt(i);
			if (n instanceof Group && last.equals(((Group) n).name))return n;
		}
		for (int i = 0; parent.children != null && i < parent.children.length; i++) {
			OutlinerNode n = parent.children.getAt(i);
			if (n instanceof Cube && last.equals(((Cube) n).name))return n;
		}
		return null;
	}

	private static Animation findAnim(String name) {
		if (name == null || Animation.all == null)return null;
		for (Animation a : Animation.all)if (name.equals(a.name))return a;
		return null;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w4anim2.oppositeName", o -> {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			for (String n : strList(o.get("names"))) {
				String opp = PartPaths.oppositeName(n);
				r.set(n, opp != null ? opp : n);
			}
			return r;
		});

		PlusTestHooks.register("w4anim2.wave", o -> {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("warnings", new JsArray<>());
			Object fn = findNode(str(o, "first", null));
			if (!(fn instanceof Group)) {
				r.set("ok", false);
				r.set("blocked", "first bone not found: " + str(o, "first", null));
				return r;
			}
			Group first = (Group) fn;
			Animation anim = o.has("anim") ? findAnim(str(o, "anim", null)) : Animation.selected;
			// choices: {parentName: childName or ''}
			Map<String, String> choices = new LinkedHashMap<>();
			Object ch = o.get("choices");
			if (ch != null && "object".equals(Js.typeof(ch))) {
				JsPropertyMap<Object> cm = Js.uncheckedCast(ch);
				cm.forEach(k -> {
					String child = String.valueOf(cm.get(k));
					for (Group g : Group.all) {
						if (!k.equals(g.name))continue;
						String pick = "";
						for (Group c : WaveChain.chainChildren(g))if (c.name.equals(child))pick = c.uuid;
						choices.put(g.uuid, pick);
					}
				});
			}
			WaveChain.Chain c = WaveChain.buildChain(first, choices);
			List<String> names = new ArrayList<>();
			for (Group g : c.bones)names.add(g.name);
			r.set("chain", arr(names));
			r.set("direction", (double) WaveChain.mainDirection(c.bones));
			if (c.branchAt != null) {
				JsPropertyMap<Object> nb = JsPropertyMap.of();
				nb.set("at", c.branchAt.name);
				List<String> opts = new ArrayList<>();
				for (Group g : c.branches)opts.add(g.name);
				nb.set("options", arr(opts));
				r.set("needBranch", nb);
				r.set("ok", false);
				r.set("changed", false);
				return r;
			}
			if (bool(o, "dryRun", false)) {
				r.set("ok", true);
				r.set("changed", false);
				return r;
			}
			WaveChain.Opts op = new WaveChain.Opts();
			op.mode = str(o, "mode", WaveChain.MODE_NEW);
			op.axis = str(o, "axis", "y");
			op.size = num(o, "size", 15);
			op.waves = (int) num(o, "waves", 1);
			op.delay = num(o, "delay", 0.15);
			op.tipMore = bool(o, "tipMore", true);
			op.length = num(o, "length", 1);
			List<String> skip = strList(o.get("skip"));
			for (Group g : c.bones)if (skip.contains(g.name))op.skip.add(g.uuid);
			WaveChain.Result res = WaveChain.apply(anim, c.bones, op);
			r.set("ok", res.ok);
			r.set("changed", res.changed);
			if (res.blocked != null)r.set("blocked", res.blocked);
			r.set("keys", (double) res.keys);
			r.set("bones", (double) res.bones);
			r.set("frames", (double) res.frames);
			r.set("warnings", arr(res.warnings));
			r.set("gridSize", (double) WaveChain.gridSize(op.waves, anim != null && anim.length > 1e-4 ? anim.length : op.length, anim != null ? anim.snapping : 20));
			if (res.ok && bool(o, "done", false))WaveChain.done(anim, res);
			return r;
		});

		PlusTestHooks.register("w4anim2.mirror", o -> {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			Object node = findNode(str(o, "part", null));
			MirrorToOtherSide.Plan p = MirrorToOtherSide.plan(node);
			r.set("dstRoot", p.dstRootName);
			r.set("dstRootExists", p.dstRoot != null);
			r.set("defaultName", p.defaultName);
			r.set("mirroredParent", p.mirroredParent != null ? String.join("/", MirrorToOtherSide.path(p.mirroredParent)) : null);
			r.set("planWarnings", arr(p.warnings));
			r.set("anims", (double) p.anims.size());
			r.set("extruded", (double) p.extruded);
			if (p.blocked != null || bool(o, "dryRun", false)) {
				r.set("ok", p.blocked == null);
				r.set("changed", false);
				if (p.blocked != null)r.set("blocked", p.blocked);
				return r;
			}
			MirrorToOtherSide.Opts op = new MirrorToOtherSide.Opts();
			op.name = str(o, "name", null);
			op.copy = bool(o, "copy", true);
			op.renameSides = bool(o, "renameSides", true);
			op.fixLinks = bool(o, "fixLinks", true);
			op.mirrorAnims = bool(o, "mirrorAnims", false);
			String parent = str(o, "parent", null);
			if (parent != null) {
				if ("root".equals(parent))op.parent = "root";
				else {
					Object pn = findNode(parent);
					op.parent = pn instanceof Group ? ((Group) pn).uuid : "root";
				}
			}
			MirrorToOtherSide.Result res = MirrorToOtherSide.apply(p, op);
			r.set("ok", res.ok);
			r.set("changed", res.changed);
			if (res.blocked != null)r.set("blocked", res.blocked);
			r.set("created", arr(res.created));
			r.set("warnings", arr(res.warnings));
			if (res.clone != null)r.set("clone", String.join("/", MirrorToOtherSide.path(res.clone)));
			if (res.clone instanceof Group)r.set("cloneUuid", ((Group) res.clone).uuid);
			if (res.clone instanceof Cube)r.set("cloneUuid", ((Cube) res.clone).uuid);
			if (res.ok && bool(o, "done", false))MirrorToOtherSide.done(res);
			return r;
		});

		// opens the real dialogs (the scenario answers them by id)
		PlusTestHooks.register("w4anim2.openWave", o -> {
			WaveChain.open();
			return JsPropertyMap.of("ok", true);
		});
		PlusTestHooks.register("w4anim2.openMirror", o -> {
			MirrorToOtherSide.open();
			return JsPropertyMap.of("ok", true);
		});
	}
}
