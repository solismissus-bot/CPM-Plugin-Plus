package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Property.Clazz;
import com.tom.cpm.blockbench.proxy.Property.Type;
import com.tom.cpm.blockbench.proxy.ext.W5VisNatives;
import com.tom.cpm.blockbench.tools.vis.FormMotion;
import com.tom.cpm.blockbench.tools.vis.PlusVisibility;
import com.tom.cpm.blockbench.tools.vis.Reactions;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of the visibility tools (CPM+ lite unit 'vis'):
 * <ul>
 * <li>G37 'Mostrar/esconder nesta animação…' (group context menu in Animate mode, CPM+ &gt; Animação) and 'Reações
 * automáticas…' (CPM+ &gt; Animação); hidden animation property {@value PlusVisibility#RULE_PROP}; export check
 * 'reação pode ser sobrescrita' with the fix.</li>
 * <li>G28 'Criar forma com movimento próprio…' (group context menu, CPM+ &gt; Animação), export checks.</li>
 * </ul>
 * The visibility controller (G33 rule), the on/off layer (F3 core) and the priority logic are internal helpers of
 * these two tools ({@link PlusVisibility}). Test hooks (test mode only): w5vis.preview, w5vis.showHide, w5vis.rule,
 * w5vis.g28, w5vis.checks.
 */
public final class W5VisInstall {
	private W5VisInstall() {
	}

	public static void install() {
		CPMCodec.registerProperty(Clazz.ANIMATION, Type.STRING, PlusVisibility.RULE_PROP, "CPM+ rule", "", true, true, true);

		Action reactions = PlusMenus.action("cpmplus_w5vis_reactions", "cpmplus.w5vis.menu.reactions", "bolt", Cond.CPM_ONLY, null, Reactions::openRules);
		Action showHide = PlusMenus.action("cpmplus_w5vis_showhide", "cpmplus.w5vis.menu.showHide", "visibility", Cond.CPM_ONLY,
				() -> W5VisNatives.isAnimateMode() && W5VisNatives.selectedAnimation() != null && !PlusVisibility.selectedGroups().isEmpty(), Reactions::openShowHide);
		Action form = PlusMenus.action("cpmplus_w5vis_form", "cpmplus.w5vis.menu.form", "content_copy", Cond.CPM_ONLY,
				() -> anyNonRootSelected(), FormMotion::open);

		PlusMenus.add(PlusMenus.SUB_ANIM, reactions);
		PlusMenus.add(PlusMenus.SUB_ANIM, showHide);
		PlusMenus.add(PlusMenus.SUB_ANIM, form);

		PlusMenus.addToContext(W5VisNatives.groupMenu(), showHide, "");
		PlusMenus.addToContext(W5VisNatives.groupMenu(), form, "");

		ExportHooks.register(Reactions::check);
		ExportHooks.register(FormMotion::check);

		// session-only previews ('Testar'): keep them over Blockbench's frame redraws, drop them when the mode changes
		PluginStart.addEventListener("display_animation_frame", __ -> {
			if (PlusVisibility.previewActive())PlusVisibility.reapplyPreview();
		});
		PluginStart.addEventListener("select_mode", __ -> PlusVisibility.endPreview());
		PluginStart.cleanup.add(PlusVisibility::endPreview);
		// Ctrl+Z / redo of our entries: Blockbench rebuilds the animations with another snapping and string values
		PluginStart.addEventListener("undo", __ -> W5VisNatives.fixAfterUndo(true));
		PluginStart.addEventListener("redo", __ -> W5VisNatives.fixAfterUndo(false));

		installHooks();
	}

	public static void uninstall() {
	}

	private static boolean anyNonRootSelected() {
		for (Group g : PlusVisibility.selectedGroups())if (!PlusVisibility.isRoot(g))return true;
		return false;
	}

	// ------------------------------------------------------------------ test hooks

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v == null ? def : String.valueOf(v);
	}

	private static boolean bool(JsPropertyMap<Object> o, String k, boolean def) {
		Object v = o.get(k);
		return v == null ? def : Js.isTruthy(v);
	}

	private static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		return v == null ? def : Js.asDouble(v);
	}

	private static List<String> strs(JsPropertyMap<Object> o, String k) {
		List<String> l = new ArrayList<>();
		Object v = o.get(k);
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)l.add(String.valueOf(a.getAt(i)));
		}
		return l;
	}

	/** a group by uuid, name or path ('body/asas': last segment) */
	private static Group group(String s) {
		Group g = PlusVisibility.byUuid(s);
		if (g != null)return g;
		String n = s.contains("/") ? s.substring(s.lastIndexOf('/') + 1) : s;
		for (Group x : Group.all)if (n.equals(x.name))return x;
		return null;
	}

	private static List<Group> groups(JsPropertyMap<Object> o, String k) {
		List<Group> l = new ArrayList<>();
		for (String s : strs(o, k)) {
			Group g = group(s);
			if (g == null)throw new IllegalArgumentException("unknown group " + s);
			l.add(g);
		}
		return l;
	}

	private static Animation anim(String s) {
		Animation a = PlusVisibility.animByUuid(s);
		if (a != null)return a;
		for (Animation x : PlusVisibility.anims())if (s.equals(x.name))return x;
		return null;
	}

	private static JsArray<Object> arr(List<String> l) {
		JsArray<Object> a = new JsArray<>();
		for (String s : l)a.push(s);
		return a;
	}

	private static JsPropertyMap<Object> animJs(Animation a) {
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("uuid", a.uuid);
		m.set("name", a.name);
		m.set("type", a.type);
		m.set("priority", (double) a.getPriority());
		m.set("additive", a.additive);
		m.set("loop", a.loop);
		m.set("length", (double) a.length);
		m.set("rule", Js.asPropertyMap(a).get(PlusVisibility.RULE_PROP));
		return m;
	}

	private static JsPropertyMap<Object> out(Reactions.Out o) {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", o.ok);
		r.set("errorKey", o.errorKey);
		r.set("error", o.error);
		r.set("ruleId", o.ruleId);
		JsArray<Object> as = new JsArray<>();
		for (Animation a : o.anims)as.push(animJs(a));
		r.set("anims", as);
		r.set("targets", arr(o.targets));
		r.set("impact", arr(o.impact));
		r.set("warnings", arr(o.warnings));
		r.set("priority", (double) o.priority);
		r.set("priorityOld", (double) o.priorityOld);
		r.set("t0", o.t0);
		r.set("t1", o.t1);
		r.set("keys", (double) o.keys);
		return r;
	}

	private static Reactions.RuleParams ruleParams(JsPropertyMap<Object> o) {
		Reactions.RuleParams p = new Reactions.RuleParams();
		p.when = str(o, "when", null);
		p.action = str(o, "action", "hide");
		p.parts.addAll(groups(o, "groups"));
		p.partsHide.addAll(groups(o, "groupsHide"));
		p.sneakWalk = bool(o, "sneakWalk", false);
		p.separate = bool(o, "separate", true);
		p.confirmImpact = bool(o, "confirmImpact", false);
		p.restoreHidden = bool(o, "restoreHidden", false);
		return p;
	}

	private static void installHooks() {
		PlusTestHooks.register("w5vis.preview", o -> {
			String op = str(o, "op", "state");
			List<Group> gs = groups(o, "groups");
			if ("stop".equals(op))PlusVisibility.endPreview();
			else if ("testRule".equals(op)) {
				Reactions.Rule rule = Reactions.rule(str(o, "id", ""));
				if (rule != null)Reactions.test(rule);
			}
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("active", PlusVisibility.previewActive());
			JsPropertyMap<Object> st = JsPropertyMap.of();
			for (Group g : gs)st.set(g.name, W5VisNatives.meshStates(g));
			r.set("meshes", st);
			return r;
		});
		PlusTestHooks.register("w5vis.showHide", o -> {
			Reactions.ShowHide s = new Reactions.ShowHide();
			s.anim = anim(str(o, "anim", ""));
			s.groups.addAll(groups(o, "groups"));
			s.show = bool(o, "show", true);
			s.range = str(o, "range", "all");
			s.t0 = num(o, "t0", 0);
			s.t1 = num(o, "t1", 0);
			s.hideOutside = bool(o, "hideOutside", false);
			s.confirmImpact = bool(o, "confirmImpact", false);
			s.separate = bool(o, "separate", true);
			s.replace = bool(o, "replace", true);
			return out(Reactions.showHideInAnim(s));
		});
		PlusTestHooks.register("w5vis.rule", o -> {
			String op = str(o, "op", "create");
			switch (op) {
			case "create":
				return out(Reactions.createRule(ruleParams(o), null));
			case "edit":
				return out(Reactions.createRule(ruleParams(o), str(o, "id", "")));
			case "delete":
				return out(Reactions.deleteRule(str(o, "id", ""), bool(o, "restoreHidden", false)));
			case "raise":
				return out(Reactions.raisePriority(str(o, "id", "")));
			case "conflicts": {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("conflicts", arr(Reactions.conflicts()));
				return r;
			}
			default: {
				JsArray<Object> list = new JsArray<>();
				for (Reactions.Rule ru : Reactions.rules()) {
					JsPropertyMap<Object> m = JsPropertyMap.of();
					m.set("id", ru.id);
					m.set("when", ru.when);
					m.set("action", ru.action);
					m.set("uuids", arr(ru.uuids));
					m.set("uuidsHide", arr(ru.uuidsHide));
					m.set("priority", (double) ru.priority());
					JsArray<Object> as = new JsArray<>();
					for (Animation a : ru.anims)as.push(animJs(a));
					m.set("anims", as);
					list.push(m);
				}
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("rules", list);
				return r;
			}
			}
		});
		PlusTestHooks.register("w5vis.g28", o -> {
			FormMotion.Params p = new FormMotion.Params();
			p.name = str(o, "name", null);
			p.parts.addAll(groups(o, "parts"));
			p.situations.addAll(strs(o, "situations"));
			p.startOn = bool(o, "startOn", false);
			p.createMissing = bool(o, "createMissing", false);
			FormMotion.Out f = FormMotion.create(p);
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("ok", f.ok);
			r.set("errorKey", f.errorKey);
			r.set("error", f.error);
			r.set("priority", (double) f.priority);
			r.set("copiedKeys", (double) f.copiedKeys);
			r.set("dupNodes", (double) f.dupNodes);
			if (f.layer != null)r.set("layer", animJs(f.layer));
			JsArray<Object> c = new JsArray<>();
			for (int i = 0; i < f.copies.size(); i++) {
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("original", f.originals.get(i).uuid);
				m.set("copy", f.copies.get(i).uuid);
				m.set("copyName", f.copies.get(i).name);
				m.set("origCtrl", f.origCtrl.get(i).uuid);
				m.set("copyCtrl", f.copyCtrl.get(i).uuid);
				c.push(m);
			}
			r.set("forms", c);
			r.set("layerWarning", f.layerWarning);
			return r;
		});
		// the rows the export would show for this package's checks; fix=<key> runs the first such row's built-in fix
		PlusTestHooks.register("w5vis.checks", o -> {
			String fix = str(o, "fix", null);
			return ProjectConvert.convertHeadless().then(h -> {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("rows", rows(h.warnings));
				if (fix == null)return Promise.resolve(r);
				WarnEntry target = null;
				for (WarnEntry w : h.warnings)if (fix.equals(w.getKey()) && w.canQuickFix()) {
					target = w;
					break;
				}
				if (target == null) {
					r.set("fixed", false);
					return Promise.resolve(r);
				}
				return target.runQuickFix().then(ok -> ProjectConvert.convertHeadless().then(h2 -> {
					r.set("fixed", ok);
					r.set("after", rows(h2.warnings));
					return Promise.resolve(r);
				}));
			});
		});
	}

	private static JsArray<Object> rows(List<WarnEntry> ws) {
		JsArray<Object> l = new JsArray<>();
		for (WarnEntry w : ws) {
			String k = w.getKey();
			if (k == null || !k.startsWith("w5vis"))continue;
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("key", k);
			m.set("message", w.getMessage());
			m.set("severity", w.getSeverity().name());
			m.set("canFix", w.canQuickFix());
			m.set("fixPreview", w.getFixPreview());
			m.set("nodes", arr(new ArrayList<>(w.getNames())));
			l.push(m);
		}
		return l;
	}
}
