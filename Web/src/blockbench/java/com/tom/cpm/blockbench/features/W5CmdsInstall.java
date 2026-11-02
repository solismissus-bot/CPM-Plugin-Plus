package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.tools.cmd.CommandSheet;
import com.tom.cpm.blockbench.tools.cmd.RenameAnims;
import com.tom.cpm.blockbench.tools.settings.ModelIdSection;
import com.tom.cpm.blockbench.util.ButtonIdCheck;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Anim;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Finding;
import com.tom.cpm.blockbench.util.CommandArg;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.KeybindIds;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.ServerCommands;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.editor.AnimationExporter;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.parts.anim.AnimLoaderState;
import com.tom.cpm.shared.parts.anim.menu.AbstractGestureButtonData;
import com.tom.cpm.shared.parts.anim.menu.DropdownButtonData;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W5-CMDS (CPM+ v0.8): E2 + G61 'Comandos e roda de gestos', G31 names that mix up in
 * game (+ rename), G11 model ID (+ rename warning).
 * <ul>
 * <li>CPM+ > Anima\u00e7\u00e3o > 'Comandos e roda de gestos...' and 'Renomear nomes que se misturam...';</li>
 * <li>'Ajustes do modelo' section 'ID do modelo' (ModelSettings.addSection);</li>
 * <li>export checks: names that need quotes / can't be used in commands (INFO, opens the sheet), names that mix up or
 * break the in-game export (G31, rename fix), 'Salvar entre partidas' without a model ID (fix: create it), renamed
 * buttons since the last export (fixes: back to the old name / it is right);</li>
 * <li>CPM's raw 'Animation name conflict' error becomes a pt-BR message;</li>
 * <li>after every written export the button keys are remembered (project extra 'lastExportedKeys');</li>
 * <li>the animation dialog extension (CmdsWizardHook) and the 'Comandos pro staff' section of the sheet.</li>
 * </ul>
 */
public final class W5CmdsInstall {
	static final String KEY_NAMES = "w5cmdsCommandNames";
	static final String KEY_CONFLICT = "w5cmdsButtonConflict";
	static final String KEY_MIX = "w5cmdsNameMix";
	static final String KEY_SAME = "w5cmdsSameButton";
	static final String KEY_NO_ID = "w5cmdsNoModelId";
	static final String KEY_RENAMED = "w5cmdsRenamed";

	private W5CmdsInstall() {
	}

	/**
	 * Only G11 is taken from the W5-CMDS draft (model ID section, 'no model ID' and 'renamed since the last export'
	 * rows). Its G31 checks predict the old exporter (CPM+ now renames repeated names itself: 'Azul2') and its command
	 * sheet duplicates the beta2 'Cola de comandos', so they stay off; the animation dialog extension needed an
	 * AnimationWizard API that was never written.
	 */
	static final boolean G31_E2_ENABLED = false;

	public static void install() {
		if (G31_E2_ENABLED) {
			PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w5cmds_sheet", "cpmplus.w5cmds.menu.sheet", "terminal", PlusMenus.Cond.CPM_ONLY, CommandSheet::open);
			PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w5cmds_rename", "cpmplus.w5cmds.menu.rename", "drive_file_rename_outline", PlusMenus.Cond.CPM_ONLY, () -> RenameAnims.openForProject(null));
			CommandSheet.addSection("staff", "cpmplus.w5cmds.sheet.staff", W5CmdsInstall::staffSection);
			WarnFixes.register(KEY_CONFLICT, "cpmplus.w5cmds.fix.show", 20, W5CmdsInstall::showFix);
			WarnFixes.register(KEY_MIX, "cpmplus.w5cmds.fix.show", 20, W5CmdsInstall::showFix);
		}
		ModelIdSection.install();

		ExportHooks.register(W5CmdsInstall::checkNames);
		ExportHooks.translateException(W5CmdsInstall::translate);
		ExportHooks.afterWrite(W5CmdsInstall::rememberKeys);

		WarnFixes.register(KEY_RENAMED, "cpmplus.w5cmds.fix.revert", 10, W5CmdsInstall::revertFix);
		WarnFixes.register(KEY_RENAMED, "cpmplus.w5cmds.fix.keep", 20, w -> () -> Promise.resolve(true));

		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	// ====================================================================== export checks

	static void checkNames(ExportHooks.ExportContext ctx) {
		List<Anim> anims = CommandSheet.animsOf(ctx.editor, ctx.export);
		CommandSheet.Model m = CommandSheet.build(anims);
		// E2 / G61: never blocks
		if (G31_E2_ENABLED && m.needQuotes + m.unusable > 0) {
			ctx.add(WarnFactory.of(Severity.INFO, KEY_NAMES, "cpmplus.w5cmds.warn.names", m.needQuotes, m.unusable)
					.setFix(() -> {
						CommandSheet.show(m);
						return Promise.resolve(false);
					}, FixKind.VIEW_ONLY).setFixPreview(PlusDialogs.tr("cpmplus.w5cmds.fix.openSheet")));
		}
		// G31
		List<String> same = new ArrayList<>();
		for (Finding f : G31_E2_ENABLED ? m.result.findings : new ArrayList<Finding>()) {
			if (f.severity == ButtonIdCheck.Severity.GREY) {
				same.add(f.name);
				continue;
			}
			boolean red = f.severity == ButtonIdCheck.Severity.RED;
			String text = CommandSheet.describe(f) + "\n" + PlusDialogs.tr("cpmplus.w5cmds.order");
			WarnEntry w = WarnFactory.ofHtml(red ? Severity.BREAKS : Severity.DIFFERS, red ? KEY_CONFLICT : KEY_MIX, PlusDialogs.escLines(text));
			w.setKey(red ? KEY_CONFLICT : KEY_MIX, f);
			w.setConsequence(red ? "cpmplus.w5cmds.warn.conflict.consequence" : "cpmplus.w5cmds.warn.mix.consequence");
			for (Anim a : f.anims)w.addNode(a.ref instanceof String ? (String) a.ref : null, a.name);
			w.setFix(() -> new Promise<>((res, rej) -> {
				List<Finding> one = new ArrayList<>();
				one.add(f);
				RenameAnims.open(one, res::onInvoke);
			}), FixKind.ASKS).setFixPreview(PlusDialogs.tr("cpmplus.w5cmds.fix.rename"));
			ctx.add(w);
		}
		if (!same.isEmpty())
			ctx.add(WarnFactory.of(Severity.INFO, KEY_SAME, "cpmplus.w5cmds.warn.sameButtons", String.join(", ", same)));
		// G11: saved choices need the model id
		String modelId = Project.getData().modelId;
		int saved = 0;
		for (EditorAnim ea : ctx.editor.animations)if (ea.isProperty && ea.type != null && ea.type.isLayer() && !ea.command)saved++;
		if (saved > 0 && (modelId == null || modelId.trim().isEmpty())) {
			ctx.add(WarnFactory.of(Severity.DIFFERS, KEY_NO_ID, "cpmplus.w5cmds.warn.noModelId", saved).setFix(() -> {
				ModelIdSection.createIfMissing();
				return Promise.resolve(true);
			}, FixKind.DETERMINISTIC).setFixPreview(PlusDialogs.tr("cpmplus.w5cmds.fix.createId")));
		}
		// G11: renamed since the last export
		List<KeybindIds.Entry> before = KeybindIds.fromJson(Project.getData().getExtra(KeybindIds.EXTRA_KEY));
		if (!before.isEmpty() && m.result.conflictGroup == null) {
			List<KeybindIds.Rename> rn = KeybindIds.renames(before, KeybindIds.keysOf(m.result), modelId != null);
			for (KeybindIds.Rename r : rn) {
				String what = I18n.get("cpmplus.w5cmds.keytype." + r.before.type);
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, KEY_RENAMED, "cpmplus.w5cmds.warn.renamed", r.before.name, r.after.name, what);
				w.setKey(KEY_RENAMED, r);
				w.setConsequence("cpmplus.w5cmds.warn.renamed.consequence");
				ctx.add(w);
			}
		}
	}

	static KeybindIds.Rename renameOf(WarnEntry w) {
		Object[] a = w.getArgs();
		return a != null && a.length > 0 && a[0] instanceof KeybindIds.Rename ? (KeybindIds.Rename) a[0] : null;
	}

	static WarnEntry.FutureTask revertFix(WarnEntry w) {
		KeybindIds.Rename r = renameOf(w);
		if (r == null)return null;
		return () -> Promise.resolve(RenameAnims.revert(r));
	}

	static WarnEntry.FutureTask showFix(WarnEntry w) {
		Object[] a = w.getArgs();
		if (a == null || a.length == 0 || !(a[0] instanceof Finding))return null;
		Finding f = (Finding) a[0];
		String uuid = null;
		for (Anim x : f.anims)if (x.ref instanceof String) {
			uuid = (String) x.ref;
			break;
		}
		if (uuid == null)return null;
		String u = uuid;
		return () -> {
			CommandSheet.selectAnimation(u);
			return Promise.resolve(false);
		};
	}

	/** CPM's AnimationExporter: 'Animation name conflict &lt;group&gt;' (not translated by CPM) */
	static String translate(Throwable t) {
		String msg = t != null ? t.getMessage() : null;
		if (msg == null)return null;
		int i = msg.indexOf("Animation name conflict ");
		if (i < 0)return null;
		String group = msg.substring(i + "Animation name conflict ".length());
		return I18n.format("cpmplus.w5cmds.error.conflict", group);
	}

	/** after a written export: the button keys of that file (G11 rename detection on the next export) */
	static void rememberKeys(ExportHooks.ExportResult res) {
		if (res == null || res.editor == null)return;
		ButtonIdCheck.Result r = ButtonIdCheck.check(CommandSheet.animsOf(res.editor, null));
		ProjectData pd = Project.getData();
		pd.putExtra(KeybindIds.EXTRA_KEY, KeybindIds.toJson(KeybindIds.keysOf(r)));
		pd.flush();
	}

	/** 'Comandos pro staff' (G44 scaling lines) */
	static String staffSection() {
		List<String> lines = ServerCommands.staffSection(Project.getData().scalingOpt, PlusDialogs.tr(CommandSheet.PLAYER_KEY));
		if (lines.isEmpty())return null;
		StringBuilder sb = new StringBuilder("<p>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.staff.intro"))).append("</p>");
		for (String l : lines) {
			sb.append("<div><code>").append(PlusDialogs.esc(l)).append("</code> <button class='cpmplus_w5cmds_copy' data-copy='").append(PlusDialogs.esc(l)).append("'>")
					.append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.btn.copy"))).append("</button></div>");
		}
		sb.append("<button class='cpmplus_w5cmds_copy' data-copy='").append(PlusDialogs.esc(String.join("\n", lines))).append("'>")
				.append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.btn.copyAll"))).append("</button>");
		return sb.toString();
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

	private static List<Object> buttonsJson(ButtonIdCheck.Result r) {
		List<Object> l = new ArrayList<>();
		for (ButtonIdCheck.Button b : r.sorted()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", b.name);
			m.put("type", b.type.name());
			if (b.type == ButtonIdCheck.ButtonType.DROPDOWN)m.put("options", new ArrayList<Object>(b.options));
			l.add(m);
		}
		return l;
	}

	private static List<Object> findingsJson(ButtonIdCheck.Result r) {
		List<Object> l = new ArrayList<>();
		for (Finding f : r.findings) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("severity", f.severity.name());
			m.put("code", f.code);
			m.put("name", f.name);
			m.put("throwsNow", f.throwsNow);
			List<Object> an = new ArrayList<>();
			for (Anim a : f.anims)an.add(a.name);
			m.put("anims", an);
			l.add(m);
		}
		return l;
	}

	/** CPM's real AnimationExporter on a converted editor (groups '' -> none, like a saved and reloaded .cpmproject) */
	private static Map<String, Object> realExporter(Editor e) {
		Map<String, Object> out = new LinkedHashMap<>();
		for (EditorAnim ea : e.animations)if (ea.group != null && ea.group.isEmpty())ea.group = null;
		AnimationExporter ex = new AnimationExporter(e, new AnimLoaderState(null));
		ex.processElements();
		String conflict = null;
		try {
			for (EditorAnim ea : e.animations)ex.processAnimation(ea);
		} catch (RuntimeException t) {
			conflict = t.getMessage();
		}
		List<Object> l = new ArrayList<>();
		for (AbstractGestureButtonData b : ex.sortButtons()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", b.getName());
			String t;
			switch (b.getType()) {
			case BOOL_PARAMETER_TOGGLE: t = "TOGGLE"; break;
			case VALUE_PARAMETER_SLIDER: t = "VALUE"; break;
			default: t = b.getType().name();
			}
			m.put("type", t);
			if (b instanceof DropdownButtonData) {
				List<Object> o = new ArrayList<>(((DropdownButtonData) b).opts);
				if (!o.isEmpty() && "".equals(o.get(0)))o.remove(0);// the injected 'none' option
				m.put("options", o);
			}
			l.add(m);
		}
		out.put("buttons", l);
		out.put("conflict", conflict);
		return out;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w5cmds.commandArg", o -> {
			String n = str(o, "name", "");
			JsPropertyMap<Object> r = ok();
			r.set("kind", CommandArg.classify(n).name());
			r.set("arg", CommandArg.commandArg(n));
			r.set("legacy", CommandArg.legacyArg(n));
			r.set("safe", CommandArg.safeNameSuggestion(n));
			return r;
		});
		PlusTestHooks.register("w5cmds.sheet", o -> PlusTestHooks.track(ProjectConvert.convertHeadless().then(h -> {
			List<Anim> anims = CommandSheet.animsOf(h.editor, h.exp);
			CommandSheet.Model m = CommandSheet.build(anims);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("rows", new ArrayList<Object>(CommandSheet.rowsJson(m)));
			out.put("needQuotes", (double) m.needQuotes);
			out.put("unusable", (double) m.unusable);
			out.put("findings", findingsJson(m.result));
			out.put("buttons", buttonsJson(m.result));
			// independent: the ids straight from the exported CPM animations (EditorAnim.getId, groups)
			Set<String> ids = new LinkedHashSet<>();
			for (EditorAnim ea : h.editor.animations) {
				if (ea.pose instanceof com.tom.cpm.shared.animation.VanillaPose || ea.type == null || ea.type.isStaged() || !ea.type.isCustom())continue;
				if (ea.type == AnimationType.LAYER && ea.group != null && !ea.group.isEmpty())ids.add(ea.group);
				ids.add(ea.getId());
			}
			out.put("exportIds", new ArrayList<Object>(ids));
			if (Js.isTruthy(o.get("open")))CommandSheet.show(m);
			return Promise.resolve(JsonUtil.toJs(out));
		})));
		PlusTestHooks.register("w5cmds.buttons", o -> PlusTestHooks.track(ProjectConvert.convertHeadless().then(h -> {
			List<Anim> anims = CommandSheet.animsOf(h.editor, h.exp);
			ButtonIdCheck.Result p = ButtonIdCheck.check(anims);
			Map<String, Object> out = new LinkedHashMap<>();
			Map<String, Object> pred = new LinkedHashMap<>();
			pred.put("buttons", buttonsJson(p));
			pred.put("conflict", p.conflictGroup != null ? "Animation name conflict " + p.conflictGroup : null);
			pred.put("findings", findingsJson(p));
			out.put("predicted", pred);
			out.put("real", realExporter(h.editor));
			List<Object> order = new ArrayList<>();
			for (Anim a : anims)order.add(a.kind.name() + ":" + a.name + (a.group != null ? "@" + a.group : ""));
			out.put("order", order);
			out.put("ok", true);
			return Promise.resolve(JsonUtil.toJs(out));
		})));
		// prediction for an explicit animation list in a given order (e.g. the order the game loaded, from the IGV report)
		PlusTestHooks.register("w5cmds.predict", o -> {
			List<Anim> anims = new ArrayList<>();
			Object l = o.get("anims");
			if (l != null && JsArray.isArray(l)) {
				JsArray<Object> arr = Js.uncheckedCast(l);
				for (int i = 0; i < arr.length; i++) {
					JsPropertyMap<Object> a = Js.uncheckedCast(arr.getAt(i));
					String type = str(a, "type", "GESTURE").toUpperCase(Locale.ROOT);
					ButtonIdCheck.Kind k;
					try {
						k = ButtonIdCheck.Kind.valueOf(type);
					} catch (IllegalArgumentException e) {
						k = ButtonIdCheck.Kind.VANILLA_POSE;
					}
					anims.add(new Anim(k, str(a, "name", ""), str(a, "group", null)));
				}
			}
			ButtonIdCheck.Result p = ButtonIdCheck.check(anims);
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("ok", true);
			out.put("buttons", buttonsJson(p));
			out.put("conflict", p.conflictGroup);
			out.put("findings", findingsJson(p));
			return JsonUtil.toJs(out);
		});
		PlusTestHooks.register("w5cmds.modelId", o -> {
			String op = str(o, "op", "get");
			JsPropertyMap<Object> r = ok();
			switch (op) {
			case "generate":
				r.set("id", ModelIdSection.generate(str(o, "name", Project.name)));
				break;
			case "set":
				r.set("changed", ModelIdSection.set(str(o, "id", null)));
				break;
			case "create":
				r.set("id", ModelIdSection.createIfMissing());
				break;
			case "open":
				ModelIdSection.open();
				break;
			default:
			}
			r.set("current", ModelIdSection.current());
			return r;
		});
		PlusTestHooks.register("w5cmds.rename", o -> {
			String op = str(o, "op", "open");
			JsPropertyMap<Object> r = ok();
			if ("open".equals(op))RenameAnims.openForProject(b -> PlusTestHooks.setLast("w5cmds.rename", b));
			else {
				ButtonIdCheck.Result p = ButtonIdCheck.check(CommandSheet.animsOfProject(null, null));
				r.set("findings", JsonUtil.toJs(findingsJson(p)));
			}
			return r;
		});
		PlusTestHooks.register("w5cmds.keys", o -> {
			JsPropertyMap<Object> r = ok();
			Object stored = Project.getData().getExtra(KeybindIds.EXTRA_KEY);
			r.set("stored", stored != null ? JsonUtil.toJs(stored) : null);
			if (Js.isTruthy(o.get("revert"))) {
				// the first rename the next export would report, reverted like the warning's 'Voltar ao nome antigo'
				ButtonIdCheck.Result now = ButtonIdCheck.check(CommandSheet.animsOfProject(null, null));
				List<KeybindIds.Rename> rn = KeybindIds.renames(KeybindIds.fromJson(stored), KeybindIds.keysOf(now), Project.getData().modelId != null);
				r.set("renames", (double) rn.size());
				r.set("reverted", !rn.isEmpty() && RenameAnims.revert(rn.get(0)));
			}
			if (Js.isTruthy(o.get("clear"))) {
				ProjectData pd = Project.getData();
				pd.removeExtra(KeybindIds.EXTRA_KEY);
				pd.flush();
			}
			return r;
		});
		PlusTestHooks.register("w5cmds.translate", o -> {
			JsPropertyMap<Object> r = ok();
			r.set("text", ExportHooks.translate(new RuntimeException(str(o, "message", ""))));
			return r;
		});
		PlusTestHooks.register("w5cmds.sections", o -> {
			JsPropertyMap<Object> r = ok();
			JsArray<Object> a = new JsArray<>();
			for (com.tom.cpm.blockbench.tools.settings.ModelSettings.Section s : com.tom.cpm.blockbench.tools.settings.ModelSettings.sections())a.push(s.id);
			r.set("modelSettings", a);
			r.set("staff", staffSection());
			return r;
		});
	}
}
