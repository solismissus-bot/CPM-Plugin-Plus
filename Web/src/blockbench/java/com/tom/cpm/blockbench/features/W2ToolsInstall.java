package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.tools.anim.BedrockAnimImport;
import com.tom.cpm.blockbench.tools.anim.BedrockAnimImport.FileInfo;
import com.tom.cpm.blockbench.tools.anim.BedrockAnimImport.Target;
import com.tom.cpm.blockbench.tools.budget.ModelBudget;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.OfflineUuid;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W2-TOOLS (CPM+ v0.8):
 * <ul>
 * <li>C1 'Quem pode ver meu modelo?' (CPM+ menu, action 'cpmplus_budget', dialog 'cpmplus_budget'): read-only panel
 * of the model's cubes / texture / size / animated textures per audience (ModelBudget), never automatic;</li>
 * <li>D8 'Importar animação Bedrock/GeckoLib…' (CPM+ &gt; Animação and Blockbench's Animation menu, action
 * 'cpmplus_import_bedrock_anim'): bone mapping dialog + Blockbench's own loader, one undo (BedrockAnimImport);</li>
 * <li>util/OfflineUuid (pure, used by later packages) - no UI here.</li>
 * </ul>
 * In test mode only: the 'w2tools.*' tools of the QA scenarios.
 */
public final class W2ToolsInstall {
	public static final String ACTION_BUDGET = "cpmplus_budget";
	public static final String ACTION_BEDROCK = "cpmplus_import_bedrock_anim";

	private W2ToolsInstall() {
	}

	public static void install() {
		Action budget = PlusMenus.action(ACTION_BUDGET, "cpmplus.w2tools.budget.menu", "groups", Cond.CPM_ONLY, null, ModelBudget::open);
		PlusMenus.add(PlusMenus.TOP, budget);
		Action bedrock = PlusMenus.action(ACTION_BEDROCK, "cpmplus.w2tools.bedrock.menu", "move_to_inbox", Cond.CPM_ONLY, null, BedrockAnimImport::open);
		PlusMenus.add(PlusMenus.SUB_ANIM, bedrock);
		try {
			PlusMenus.addToMenuBar(bedrock, "animation");
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not add the Bedrock import to the Animation menu", e);
		}
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
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

	private static List<String> strings(Object v) {
		List<String> out = new ArrayList<>();
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
		}
		return out;
	}

	/** a mapping value of a scenario: a group uuid, a group path, or a group name (first in outliner order) */
	private static String resolveTarget(String v, List<Target> targets) {
		if (v == null || v.isEmpty() || "none".equals(v))return null;
		for (Target t : targets)if (v.equals(t.group.uuid))return v;
		for (Target t : targets)if (v.equals(t.path))return t.group.uuid;
		for (Target t : targets)if (v.equals(t.group.name))return t.group.uuid;
		return "missing:" + v;
	}

	private static void registerTestTools() {
		// ---------------------------------------------------------------- C1: measure (and optionally show) the budget
		PlusTestHooks.register("w2tools.budget", o -> ModelBudget.measure().then(rep -> {
			Map<String, Object> m = ModelBudget.toJson(rep);
			PlusTestHooks.setLast("budget", m);
			JsPropertyMap<Object> r = ok();
			r.set("report", JsonUtil.toJs(m));
			return Promise.resolve(r);
		}));

		// ---------------------------------------------------------------- D8: prefill / import without the file picker
		PlusTestHooks.register("w2tools.importBedrock", o -> {
			JsPropertyMap<Object> r = ok();
			FileInfo f;
			try {
				f = BedrockAnimImport.parse(str(o, "fileName", "qa.animation.json"), o.get("json"));
			} catch (IllegalArgumentException e) {
				r.set("ok", false);
				r.set("error", e.getMessage());
				return r;
			}
			List<Target> targets = BedrockAnimImport.targets();
			Map<String, String> pre = BedrockAnimImport.prefill(f.bones, targets);
			JsPropertyMap<Object> pm = JsPropertyMap.of();
			for (String b : f.bones) {
				String uuid = pre.get(b);
				JsPropertyMap<Object> x = JsPropertyMap.of();
				x.set("uuid", uuid);
				Group g = null;
				for (Target t : targets)if (t.group.uuid.equals(uuid)) {
					g = t.group;
					x.set("path", t.path);
				}
				x.set("group", g != null ? g.name : null);
				pm.set(b, x);
			}
			r.set("prefill", pm);
			r.set("bones", JsonUtil.toJs(new ArrayList<Object>(f.bones)));
			r.set("animations", JsonUtil.toJs(new ArrayList<Object>(f.animations)));
			JsArray<Object> paths = new JsArray<>();
			for (Target t : targets)paths.push(t.path);
			r.set("targets", paths);
			if ("prefill".equals(str(o, "op", "import"))) {
				if (Js.isTruthy(o.get("openDialog")))BedrockAnimImport.showDialog(f);
				return r;
			}
			Map<String, String> map = new LinkedHashMap<>();
			Object mo = o.get("mapping");
			if (mo != null && "object".equals(Js.typeof(mo))) {
				JsPropertyMap<Object> mm = Js.uncheckedCast(mo);
				mm.forEach(k -> {
					String t = resolveTarget(String.valueOf(mm.get(k)), targets);
					if (t != null)map.put(k, t);
				});
			} else {
				map.putAll(pre);
			}
			List<String> anims = strings(o.get("animations"));
			if (o.get("animations") == null)anims = new ArrayList<>(f.animations);
			r.set("undoBefore", (double) Undo.historyLength());
			try {
				BedrockAnimImport.Result res = BedrockAnimImport.importAnimations(f, map, anims);
				r.set("result", JsonUtil.toJs(BedrockAnimImport.toJson(res)));
				r.set("changed", true);
				if (Js.isTruthy(o.get("report")))BedrockAnimImport.report(res);
			} catch (IllegalArgumentException e) {
				r.set("ok", false);
				r.set("error", e.getMessage());
				r.set("message", BedrockAnimImport.message(e));
			}
			r.set("undoAfter", (double) Undo.historyLength());
			return r;
		});

		// ---------------------------------------------------------------- OfflineUuid as compiled into the plugin
		PlusTestHooks.register("w2tools.offlineUuid", o -> {
			JsPropertyMap<Object> r = ok();
			JsPropertyMap<Object> names = JsPropertyMap.of();
			for (String n : strings(o.get("names")))names.set(n, OfflineUuid.fromName(n));
			r.set("fromName", names);
			JsPropertyMap<Object> norm = JsPropertyMap.of();
			for (String t : strings(o.get("texts")))norm.set(t, OfflineUuid.normalize(t));
			r.set("normalize", norm);
			return r;
		});
	}
}
