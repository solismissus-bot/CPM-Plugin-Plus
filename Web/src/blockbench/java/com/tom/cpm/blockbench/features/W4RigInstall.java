package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.ext.W4RigNatives;
import com.tom.cpm.blockbench.tools.model.BuildChain;
import com.tom.cpm.blockbench.tools.model.JointCheck;
import com.tom.cpm.blockbench.tools.model.RigGeom;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of the rig tools (CPM+ v0.8): G18 'Conferir articulações' (export check + panel + fixes) and G19
 * 'Montar corrente…' / 'Fazer osso desta peça' (menu + outliner context menus). Test tools 'w4rig.*' in test mode.
 */
public final class W4RigInstall {
	private W4RigInstall() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_MODEL, "cpmplus_w4rig_joints", "cpmplus.w4rig.menu.joints", "accessibility_new", Cond.CPM_ONLY, () -> JointCheck.openPanel(false));
		Action chain = PlusMenus.addAction(PlusMenus.SUB_MODEL, "cpmplus_w4rig_chain", "cpmplus.w4rig.menu.chain", "link", Cond.CPM_ONLY, () -> BuildChain.open(false));
		Action bone = PlusMenus.addAction(PlusMenus.SUB_MODEL, "cpmplus_w4rig_bone", "cpmplus.w4rig.menu.bone", "polyline", Cond.CPM_ONLY, () -> BuildChain.open(true));
		Menu gm = W4RigNatives.groupMenu();
		Menu cm = W4RigNatives.cubeMenu();
		if (gm != null) {
			PlusMenus.addToContext(gm, chain, "");
			PlusMenus.addToContext(gm, bone, "");
		}
		if (cm != null) {
			PlusMenus.addToContext(cm, chain, "");
			PlusMenus.addToContext(cm, bone, "");
		}
		ExportHooks.register(JointCheck::exportCheck);
		JointCheck.registerFixes();
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
		JointCheck.clearMarkers();
	}

	// ====================================================================== test tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static JsPropertyMap<Object> ok() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}

	private static JsArray<Object> arr(List<?> l) {
		JsArray<Object> a = new JsArray<>();
		for (Object o : l)a.push(o);
		return a;
	}

	private static JsArray<Double> vec(double[] v) {
		JsArray<Double> a = new JsArray<>();
		if (v != null)for (double d : v)a.push(d);
		return a;
	}

	private static Object node(String name) {
		for (Group g : Group.all)if (g != null && name.equals(g.name))return g;
		for (int i = 0; i < Cube.all.length; i++)if (name.equals(Cube.all.getAt(i).name))return Cube.all.getAt(i);
		return null;
	}

	private static JsPropertyMap<Object> joints() {
		JsPropertyMap<Object> o = ok();
		JsPropertyMap<Object> m = JsPropertyMap.of();
		for (JointCheck.Result r : JointCheck.check()) {
			JsPropertyMap<Object> p = JsPropertyMap.of();
			p.set("ok", r.ok);
			p.set("warns", r.warns());
			p.set("strong", r.strong);
			p.set("ignored", r.ignored);
			p.set("rotated", r.rotated);
			p.set("hasBox", r.hasBox);
			p.set("pivot", vec(r.pivot));
			p.set("suggested", vec(r.suggested));
			if (r.box != null) {
				p.set("min", vec(r.box.min));
				p.set("max", vec(r.box.max));
			}
			m.set(r.root.name, p);
		}
		o.set("roots", m);
		o.set("markers", (double) JointCheck.markerCount());
		o.set("sceneMarkers", (double) W4RigNatives.markerCount());
		return o;
	}

	private static void registerTestTools() {
		// G18
		PlusTestHooks.register("w4rig.joints", o -> {
			String op = str(o, "op", "check");
			String root = str(o, "root", null);
			List<JointCheck.Result> sel = new ArrayList<>();
			for (JointCheck.Result r : JointCheck.check())if (root == null || root.equals(r.root.name))sel.add(r);
			int n = 0;
			switch (op) {
			case "fix":
				n = JointCheck.fix(sel);
				break;
			case "ignore":
			case "unignore":
				for (JointCheck.Result r : sel) {
					JointCheck.setIgnored(r.root, "ignore".equals(op));
					n++;
				}
				break;
			case "show":
				if (!sel.isEmpty())JointCheck.show(sel.get(0));
				break;
			case "clear":
				JointCheck.clearMarkers();
				break;
			case "panel":
				JointCheck.openPanel(Js.isTruthy(o.get("showIgnored")));
				break;
			default:
			}
			JsPropertyMap<Object> r = joints();
			r.set("changed", n > 0);
			r.set("count", (double) n);
			return r;
		});
		// G19
		PlusTestHooks.register("w4rig.chain", o -> {
			List<Object> items = new ArrayList<>();
			Object list = o.get("items");
			if (list != null && JsArray.isArray(list)) {
				JsArray<Object> a = Js.uncheckedCast(list);
				for (int i = 0; i < a.length; i++) {
					Object nd = node(String.valueOf(a.getAt(i)));
					if (nd != null)items.add(nd);
				}
			} else items = BuildChain.selection();
			String ord = str(o, "order", "auto");
			BuildChain.Order order = "click".equals(ord) ? BuildChain.Order.CLICK : "outliner".equals(ord) ? BuildChain.Order.OUTLINER : BuildChain.Order.AUTO;
			String jm = str(o, "joint", "seam");
			BuildChain.JointMode mode = "center".equals(jm) ? BuildChain.JointMode.CENTER : "back".equals(jm) ? BuildChain.JointMode.BACK : BuildChain.JointMode.SEAM;
			BuildChain.Plan p = BuildChain.plan(items, order, mode, str(o, "prefix", "cauda"), !"separate".equals(str(o, "mode", "chain")));
			JsPropertyMap<Object> r = ok();
			r.set("error", p.error);
			List<Object> names = new ArrayList<>(), order2 = new ArrayList<>(), joints = new ArrayList<>(), forks = new ArrayList<>(), gaps = new ArrayList<>();
			for (int i = 0; i < p.items.size(); i++) {
				order2.add(p.items.get(i).name);
				joints.add(vec(p.joints.get(i)));
				forks.add(p.fork.get(i));
				gaps.add(p.gap.get(i));
			}
			names.addAll(p.names);
			r.set("names", arr(names));
			r.set("order", arr(order2));
			r.set("joints", arr(joints));
			r.set("fork", arr(forks));
			r.set("gap", arr(gaps));
			r.set("hasFork", p.hasFork());
			if (Js.isTruthy(o.get("apply")) && p.error == null) {
				if (p.hasFork() && !Js.isTruthy(o.get("force"))) {
					r.set("applied", false);
				} else {
					try {
						List<Group> links = BuildChain.apply(p, p.joints);
						List<Object> ln = new ArrayList<>();
						for (Group g : links)ln.add(g.name);
						r.set("links", arr(ln));
						r.set("applied", true);
						r.set("changed", true);
					} catch (Throwable e) {
						r.set("applied", false);
						r.set("applyError", String.valueOf(e.getMessage()));
					}
				}
			}
			if (Js.isTruthy(o.get("ui")))BuildChain.open(Js.isTruthy(o.get("single")));
			return r;
		});
		// export-dialog rows of the joint check (headless conversion, no dialog, no fix)
		PlusTestHooks.register("w4rig.rows", o -> ProjectConvert.convertHeadless().then(h -> {
			JsPropertyMap<Object> r = ok();
			List<Object> rows = new ArrayList<>();
			for (WarnEntry w : h.warnings) {
				if (!JointCheck.WARN_KEY.equals(w.getKey()))continue;
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("key", w.getKey());
				List<Object> args = new ArrayList<>();
				for (Object a : w.getArgs())args.add(String.valueOf(a));
				m.set("args", arr(args));
				m.set("severity", w.getSeverity().name());
				m.set("message", w.getMessage());
				List<Object> fixes = new ArrayList<>();
				for (WarnFixes.Choice c : WarnFixes.choicesFor(w))fixes.add(c.label);
				m.set("fixes", arr(fixes));
				rows.add(m);
			}
			r.set("rows", arr(rows));
			r.set("total", (double) h.warnings.size());
			return Promise.resolve((Object) r);
		}));
		// shown rest-pose corners of every cube (world), for 'nothing moved' checks
		PlusTestHooks.register("w4rig.corners", o -> {
			JsPropertyMap<Object> r = ok();
			JsPropertyMap<Object> m = JsPropertyMap.of();
			for (int i = 0; i < Cube.all.length; i++) {
				Cube c = Cube.all.getAt(i);
				List<Object> cs = new ArrayList<>();
				for (double[] p : RigGeom.shownCorners(c, false, null))cs.add(vec(p));
				m.set(c.uuid, arr(cs));
			}
			r.set("corners", m);
			return r;
		});
	}
}
