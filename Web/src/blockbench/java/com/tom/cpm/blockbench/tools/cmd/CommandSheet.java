package com.tom.cpm.blockbench.tools.cmd;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.util.ButtonIdCheck;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Anim;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Button;
import com.tom.cpm.blockbench.util.ButtonIdCheck.ButtonType;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Finding;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Kind;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Severity;
import com.tom.cpm.blockbench.util.CommandArg;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.web.client.util.I18n;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.Event;
import elemental2.dom.HTMLElement;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W5-CMDS, E2 + G61): 'Comandos e roda de gestos' - every name a command can use, the exact lines to copy
 * ('/cpmclient animate', '/cpm animate' for command-controlled animations, '/cpm detect'), value hints, names that
 * need quotes / can't be used, names that mix up in game (shared with G31, {@link ButtonIdCheck}) and a preview of the
 * gesture wheel in order. Read-only: nothing in the project changes (no undo entry, the project stays saved).
 * <p>
 * The names come from the export itself ({@link ProjectConvert#convertHeadless()}: the CPM animations the file will
 * have), never from a second copy of the naming rules. Other packages add sections with
 * {@link #addSection(String, String, Supplier)} (e.g. 'Comandos pro staff').
 */
public final class CommandSheet {
	public static final String DIALOG_ID = "cpmplus_w5cmds_sheet";
	/** the player placeholder in the lines (the user replaces it with a nick, or @p in a command block) */
	public static final String PLAYER_KEY = "cpmplus.w5cmds.sheet.player";

	private CommandSheet() {
	}

	// ------------------------------------------------------------------ section registry

	private static final class Section {
		final String id, titleKey;
		final Supplier<String> html;

		Section(String id, String titleKey, Supplier<String> html) {
			this.id = id;
			this.titleKey = titleKey;
			this.html = html;
		}
	}

	private static final List<Section> SECTIONS = new ArrayList<>();

	/**
	 * Adds a section at the end of the sheet: titleKey = lang key of its heading, html = TRUSTED html (escape user
	 * strings) or null to skip it this time. Removed on unload (or with the returned Runnable).
	 */
	public static Runnable addSection(String id, String titleKey, Supplier<String> html) {
		Section s = new Section(id, titleKey, html);
		SECTIONS.add(s);
		Runnable rm = () -> SECTIONS.remove(s);
		PluginStart.cleanup.add(rm);
		return rm;
	}

	// ------------------------------------------------------------------ model

	/** one command name (a button, a layer group or a dropdown option) */
	public static final class Row {
		public final Button button;
		/** null for the button itself, else the option name */
		public final String option;
		public final String name;
		public final CommandArg.Kind kind;
		public final List<String> lines = new ArrayList<>();
		/** lines for Minecraft 1.12- / Bukkit fallback, only when they differ */
		public final List<String> legacyLines = new ArrayList<>();
		public String valueHint;

		Row(Button button, String option) {
			this.button = button;
			this.option = option;
			this.name = option != null ? option : button.name;
			this.kind = CommandArg.classify(name);
		}
	}

	public static final class Model {
		public final List<Anim> anims;
		public final ButtonIdCheck.Result result;
		public final List<Row> rows = new ArrayList<>();
		/** staged (start / end) and vanilla pose animations: no button, no command */
		public final List<Anim> noButton = new ArrayList<>();
		public int needQuotes, unusable;

		Model(List<Anim> anims) {
			this.anims = anims;
			this.result = ButtonIdCheck.check(anims);
		}

		/** the findings that matter for commands (two different things answer to one name, or the export fails) */
		public List<Finding> collisions() {
			List<Finding> l = new ArrayList<>();
			for (Finding f : result.findings)if (f.severity != Severity.GREY)l.add(f);
			return l;
		}
	}

	/** EditorAnim -> ButtonIdCheck.Anim; ref = the Blockbench animation uuid (null for generated animations) */
	public static List<Anim> animsOf(Editor e, BlockbenchExport exp) {
		Map<EditorAnim, String> uuids = new IdentityHashMap<>();
		if (exp != null && Animation.all != null) {
			for (Animation a : Animation.all) {
				if (a == null || a.uuid == null)continue;
				for (EditorAnim ea : exp.getExportedAnims(a.uuid))uuids.put(ea, a.uuid);
			}
		}
		List<Anim> out = new ArrayList<>();
		for (EditorAnim ea : e.animations) {
			Anim a = new Anim(kindOf(ea), ea.displayName, ea.type == AnimationType.LAYER ? ea.group : null);
			a.command = ea.command;
			a.isProperty = ea.isProperty;
			a.hidden = ea.hidden;
			a.layerCtrl = ea.layerControlled;
			a.order = ea.order;
			a.maxValue = ea.maxValue;
			a.layerDefault = ea.layerDefault;
			a.ref = uuids.get(ea);
			out.add(a);
		}
		return out;
	}

	public static Kind kindOf(EditorAnim ea) {
		if (ea.pose instanceof VanillaPose)return Kind.VANILLA_POSE;
		return kindOf(ea.type);
	}

	static Kind kindOf(AnimationType t) {
		if (t == null)return Kind.VANILLA_POSE;
		switch (t) {
		case CUSTOM_POSE: return Kind.CUSTOM_POSE;
		case GESTURE: return Kind.GESTURE;
		case LAYER: return Kind.LAYER;
		case VALUE_LAYER: return Kind.VALUE_LAYER;
		case SETUP: return Kind.SETUP;
		case FINISH: return Kind.FINISH;
		default: return Kind.VANILLA_POSE;
		}
	}

	/**
	 * The Blockbench animations as CPM will see them, without converting the model (for live checks in dialogs): the
	 * button-making animations carry the same name, type and group as in the export; 'once' / 'hold' also make a
	 * start stage, which never has a button. ref = the Blockbench animation. 'replace' (optional) stands for 'self'
	 * with edited values.
	 */
	public static List<Anim> animsOfProject(Animation self, Anim replace) {
		List<Anim> out = new ArrayList<>();
		if (Animation.all == null)return out;
		boolean replaced = false;
		for (Animation an : Animation.all) {
			if (an == null)continue;
			if (self != null && an == self && replace != null) {
				out.add(replace);
				replaced = true;
				continue;
			}
			Anim a = ofBB(an);
			if (a != null)out.add(a);
		}
		if (replace != null && !replaced)out.add(replace);
		return out;
	}

	/** one Blockbench animation as a ButtonIdCheck.Anim (null when its type is not a string) */
	public static Anim ofBB(Animation an) {
		if (an.type == null || !"string".equals(Js.typeof(an.type)))return null;
		Pair<AnimationType, VanillaPose> t = Animation.parseType(an.type);
		Kind k = t.getKey() == AnimationType.POSE ? Kind.VANILLA_POSE : kindOf(t.getKey());
		Anim a = new Anim(k, an.name, k == Kind.LAYER ? an.group : null);
		a.command = an.commandCtrl;
		a.isProperty = an.isProperty;
		a.hidden = an.isAnimHidden();
		a.layerCtrl = an.layerCtrl;
		a.order = an.getOrder();
		a.maxValue = an.getMaxValue();
		a.layerDefault = an.getLayerDefault();
		a.ref = an;
		return a;
	}

	public static Model build(List<Anim> anims) {
		Model m = new Model(anims);
		String player = PlusDialogs.tr(PLAYER_KEY);
		for (Anim a : anims)if (!a.makesButton())m.noButton.add(a);
		for (Button b : m.result.sorted()) {
			Row r = new Row(b, null);
			fill(r, player);
			m.rows.add(r);
			if (b.type == ButtonType.DROPDOWN) {
				for (String o : b.options) {
					Row ro = new Row(b, o);
					fill(ro, player);
					m.rows.add(ro);
				}
			}
		}
		for (Row r : m.rows) {
			if (r.kind == CommandArg.Kind.NEEDS_QUOTES)m.needQuotes++;
			else if (r.kind == CommandArg.Kind.UNUSABLE)m.unusable++;
		}
		return m;
	}

	/** the lines and the value hint of a row */
	private static void fill(Row r, String player) {
		Button b = r.button;
		String value = null;
		if (r.option == null) {
			switch (b.type) {
			case TOGGLE:
				r.valueHint = PlusDialogs.tr("cpmplus.w5cmds.sheet.value.toggle");
				break;
			case VALUE:
				value = String.valueOf(b.maxValue);
				r.valueHint = PlusDialogs.trf("cpmplus.w5cmds.sheet.value.slider", b.maxValue);
				break;
			case DROPDOWN: {
				value = b.options.isEmpty() ? "0" : "1";
				StringBuilder sb = new StringBuilder(PlusDialogs.tr("cpmplus.w5cmds.sheet.value.none"));
				for (int i = 0; i < b.options.size(); i++)sb.append(", ").append(i + 1).append(" = ").append(b.options.get(i));
				r.valueHint = PlusDialogs.trf("cpmplus.w5cmds.sheet.value.dropdown", sb.toString());
				break;
			}
			default:
				r.valueHint = PlusDialogs.tr("cpmplus.w5cmds.sheet.value.none.pose");
			}
		} else r.valueHint = PlusDialogs.tr("cpmplus.w5cmds.sheet.value.option");
		if (r.kind == CommandArg.Kind.UNUSABLE)return;
		if (b.command) {
			r.lines.add(CommandArg.serverLine(player, r.name, value));
		} else {
			r.lines.add(CommandArg.clientLine(r.name, value));
		}
		r.lines.add(CommandArg.detectLine(player, r.name, null));
		if (value != null && CommandArg.legacyDiffers(r.name)) {
			r.legacyLines.add(b.command ? CommandArg.legacyServerLine(player, r.name, value) : CommandArg.legacyClientLine(r.name, value));
		}
	}

	/** the model of the open project, from a headless export (never changes the project) */
	public static Promise<Model> compute() {
		return ProjectConvert.convertHeadless().then(h -> Promise.resolve(build(animsOf(h.editor, h.exp))));
	}

	// ------------------------------------------------------------------ dialog

	/** CPM+ > Anima\u00e7\u00e3o > 'Comandos e roda de gestos...' */
	public static void open() {
		PlusTestHooks.track(compute().then(m -> {
			show(m);
			return Promise.resolve(m);
		}, err -> {
			PopupDialogs.displayError(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.title")), err);
			return Promise.resolve((Model) null);
		}));
	}

	private static String typeLabel(Button b) {
		return PlusDialogs.tr("cpmplus.w5cmds.type." + b.type.name().toLowerCase(Locale.ROOT));
	}

	private static String badge(CommandArg.Kind k, String name) {
		switch (k) {
		case OK:
			return "<span class='cpmplus_w5cmds_badge' data-kind='ok' style='color: var(--color-confirm, #4caf50);'>" + PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.badge.ok")) + "</span>";
		case NEEDS_QUOTES:
			return "<span class='cpmplus_w5cmds_badge' data-kind='quotes' style='color: var(--color-warning, #e6a23c);'>" + PlusDialogs.esc(PlusDialogs.trf("cpmplus.w5cmds.badge.quotes", CommandArg.commandArg(name))) + "</span>";
		default:
			return "<span class='cpmplus_w5cmds_badge' data-kind='unusable' style='color: var(--color-close, #f44336);'>" + PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.badge.unusable")) + "</span>";
		}
	}

	/** the dialog; copy buttons carry an index into 'copies', select buttons into 'selects' */
	public static void show(Model m) {
		List<String> copies = new ArrayList<>();
		List<String> selects = new ArrayList<>();
		StringBuilder h = new StringBuilder();
		h.append("<div class='cpmplus_w5cmds_sheet' style='max-height: 65vh; overflow-y: auto; user-select: text;'>");
		h.append("<p>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.intro"))).append("</p>");

		// collisions first (red)
		List<Finding> col = m.collisions();
		if (!col.isEmpty()) {
			h.append("<h3 style='color: var(--color-close, #f44336);'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.collisions"))).append("</h3>");
			for (Finding f : col) {
				h.append("<div class='cpmplus_w5cmds_collision' style='color: var(--color-close, #f44336); margin-bottom: 6px;'>");
				h.append(PlusDialogs.esc(PlusDialogs.trf(f.code.equals("GROUP_CONFLICT") ? "cpmplus.w5cmds.sheet.collision.group" : "cpmplus.w5cmds.sheet.collision", f.name)));
				for (Anim a : f.anims) {
					String uuid = uuidOf(a);
					h.append(" <button class='cpmplus_w5cmds_select' data-i='").append(selects.size()).append("'").append(uuid == null ? " disabled" : "").append(">")
							.append(PlusDialogs.esc(PlusDialogs.trf("cpmplus.w5cmds.btn.select", a.name))).append("</button>");
					selects.add(uuid);
				}
				h.append("</div>");
			}
			h.append("<p style='opacity: .8;'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.order"))).append("</p>");
		}

		// command rows
		h.append("<h3>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.commands"))).append("</h3>");
		if (m.rows.isEmpty())h.append("<p>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.empty"))).append("</p>");
		for (Row r : m.rows) {
			boolean opt = r.option != null;
			h.append("<div class='cpmplus_w5cmds_row' data-name='").append(PlusDialogs.esc(r.name)).append("' style='margin: 6px 0 6px ").append(opt ? "24px" : "0").append(";'>");
			h.append("<b>").append(PlusDialogs.esc(r.name)).append("</b> <span style='opacity: .7;'>(")
					.append(PlusDialogs.esc(opt ? PlusDialogs.trf("cpmplus.w5cmds.type.option", r.button.name) : typeLabel(r.button))).append(")</span> ");
			h.append(badge(r.kind, r.name));
			if (r.kind != CommandArg.Kind.OK) {
				h.append(" <button class='cpmplus_w5cmds_copy' data-i='").append(copies.size()).append("' data-safe='1'>")
						.append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.btn.safeName"))).append("</button>");
				copies.add(CommandArg.safeNameSuggestion(r.name));
			}
			if (r.kind == CommandArg.Kind.UNUSABLE)
				h.append("<div style='color: var(--color-close, #f44336);'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.badge.unusable.hint"))).append("</div>");
			for (String l : r.lines) {
				h.append("<div><code class='cpmplus_w5cmds_line'>").append(PlusDialogs.esc(l)).append("</code> <button class='cpmplus_w5cmds_copy' data-i='")
						.append(copies.size()).append("'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.btn.copy"))).append("</button></div>");
				copies.add(l);
			}
			for (String l : r.legacyLines) {
				h.append("<div style='opacity: .8;'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.legacy"))).append(" <code class='cpmplus_w5cmds_line_legacy'>")
						.append(PlusDialogs.esc(l)).append("</code> <button class='cpmplus_w5cmds_copy' data-i='").append(copies.size()).append("'>")
						.append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.btn.copy"))).append("</button></div>");
				copies.add(l);
			}
			if (!opt && !r.button.command && r.kind != CommandArg.Kind.UNUSABLE)
				h.append("<div style='opacity: .75;'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.serverNote"))).append("</div>");
			if (!opt && r.button.command)
				h.append("<div style='opacity: .75;'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.commandNote"))).append("</div>");
			if (r.valueHint != null && !r.valueHint.isEmpty())
				h.append("<div style='opacity: .75;'>").append(PlusDialogs.esc(r.valueHint)).append("</div>");
			h.append("</div>");
		}
		h.append("<p style='opacity: .8;'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.playerHint"))).append("</p>");

		// wheel preview
		h.append("<h3>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.sheet.wheel"))).append("</h3><ol class='cpmplus_w5cmds_wheel'>");
		for (Button b : m.result.sorted()) {
			boolean shown = !b.command && !b.hidden;
			h.append("<li data-name='").append(PlusDialogs.esc(b.name)).append("' data-shown='").append(shown).append("'").append(shown ? "" : " style='opacity: .45;'").append(">")
					.append(PlusDialogs.esc(b.name)).append(" <span style='opacity: .7;'>(").append(PlusDialogs.esc(typeLabel(b))).append(")</span>");
			if (b.command)h.append(" ").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.wheel.command")));
			else if (b.hidden)h.append(" ").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5cmds.wheel.hidden")));
			h.append("</li>");
		}
		h.append("</ol>");
		List<String> staged = new ArrayList<>();
		for (Anim a : m.noButton)if (a.kind == Kind.SETUP || a.kind == Kind.FINISH)staged.add(a.name);
		if (!staged.isEmpty()) {
			h.append("<p style='opacity: .6;' class='cpmplus_w5cmds_staged'>").append(PlusDialogs.esc(PlusDialogs.trf("cpmplus.w5cmds.wheel.staged", String.join(", ", staged)))).append("</p>");
		}

		// sections of other packages
		for (Section s : new ArrayList<>(SECTIONS)) {
			String html;
			try {
				html = s.html.get();
			} catch (Throwable e) {
				DomGlobal.console.error("CPM+: command sheet section '" + s.id + "' failed", e);
				continue;
			}
			if (html == null || html.isEmpty())continue;
			h.append("<h3 class='cpmplus_w5cmds_section' data-id='").append(PlusDialogs.esc(s.id)).append("'>").append(PlusDialogs.esc(PlusDialogs.tr(s.titleKey))).append("</h3>").append(html);
		}
		h.append("</div>");

		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = DIALOG_ID;
		d.title = PlusDialogs.tr("cpmplus.w5cmds.sheet.title");
		d.width = 780;
		d.singleButton = true;
		d.setHtml(h.toString());
		d.onOpen = () -> bind(copies, selects);
		new Dialog(d).show();
	}

	private static String uuidOf(Anim a) {
		if (a.ref instanceof String)return (String) a.ref;
		if (a.ref instanceof Animation)return ((Animation) a.ref).uuid;
		return null;
	}

	private static void bind(List<String> copies, List<String> selects) {
		Element root = DomGlobal.document.querySelector(".cpmplus_w5cmds_sheet");
		if (root == null)return;
		root.addEventListener("click", ev -> onClick(ev, copies, selects));
	}

	private static void onClick(Event ev, List<String> copies, List<String> selects) {
		Element t = Js.uncheckedCast(ev.target);
		if (t == null)return;
		HTMLElement el = Js.uncheckedCast(t);
		String cls = el.className != null ? el.className : "";
		String direct = el.getAttribute("data-copy");
		if (direct != null && cls.contains("cpmplus_w5cmds_copy")) {
			copySafe(direct, false);
			return;
		}
		String idx = el.getAttribute("data-i");
		if (idx == null)return;
		int i;
		try {
			i = Integer.parseInt(idx);
		} catch (NumberFormatException e) {
			return;
		}
		if (cls.contains("cpmplus_w5cmds_copy") && i >= 0 && i < copies.size()) {
			boolean safe = "1".equals(el.getAttribute("data-safe"));
			copySafe(copies.get(i), safe);
		} else if (cls.contains("cpmplus_w5cmds_select") && i >= 0 && i < selects.size()) {
			selectAnimation(selects.get(i));
		}
	}

	/** copies a line, or a safe-name suggestion with the 'renaming resets saved choices' warning */
	public static void copySafe(String text, boolean safeName) {
		PlusDialogs.copyText(text);
		if (safeName)PlusDialogs.toast(PlusDialogs.tr("cpmplus.w5cmds.safeName.warn"), "warning", 9000, null);
	}

	/** selects the Blockbench animation with that uuid in the animation panel (not an edit: no undo entry) */
	public static boolean selectAnimation(String uuid) {
		if (uuid == null || Animation.all == null)return false;
		for (Animation a : Animation.all) {
			if (a != null && uuid.equals(a.uuid)) {
				try {
					a.select();
					return true;
				} catch (Throwable e) {
					return false;
				}
			}
		}
		return false;
	}

	/** lang text of a finding for the export dialog / rename dialog (plain text; names inserted as is) */
	public static String describe(Finding f) {
		List<String> parts = new ArrayList<>();
		for (Anim a : f.anims)parts.add("'" + a.name + "' (" + kindLabel(a) + ")");
		String list = String.join(", ", parts);
		switch (f.code) {
		case "GROUP_CONFLICT":
			return f.throwsNow ? I18n.format("cpmplus.w5cmds.warn.groupConflict.now", f.name, list) : I18n.format("cpmplus.w5cmds.warn.groupConflict", f.name, list);
		case "GROUP_VS_OPTION":
			return I18n.format("cpmplus.w5cmds.warn.groupVsOption", f.name, list);
		case "EMPTY_ID":
			return I18n.format("cpmplus.w5cmds.warn.emptyId", list);
		case "SAME_BUTTON":
			return I18n.format("cpmplus.w5cmds.warn.sameButton", f.name, list);
		default:
			return I18n.format("cpmplus.w5cmds.warn.mix", f.name, list, f.anims.isEmpty() ? "" : f.anims.get(0).name);
		}
	}

	public static String kindLabel(Anim a) {
		switch (a.kind) {
		case CUSTOM_POSE: return I18n.get("cpmplus.w5cmds.type.pose");
		case GESTURE: return I18n.get("cpmplus.w5cmds.type.gesture");
		case VALUE_LAYER: return I18n.get("cpmplus.w5cmds.type.value");
		case LAYER: return a.group != null ? I18n.format("cpmplus.w5cmds.type.option", a.group) : I18n.get("cpmplus.w5cmds.type.toggle");
		default: return I18n.get("cpmplus.w5cmds.type.other");
		}
	}

	/** rows as plain data (test hooks): name, type, kind, lines, legacy, hint, options */
	public static List<Map<String, Object>> rowsJson(Model m) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Row r : m.rows) {
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("name", r.name);
			o.put("button", r.button.name);
			o.put("type", r.option != null ? "OPTION" : r.button.type.name());
			o.put("kind", r.kind.name());
			o.put("command", r.button.command);
			o.put("lines", new ArrayList<Object>(r.lines));
			o.put("legacy", new ArrayList<Object>(r.legacyLines));
			o.put("hint", r.valueHint);
			if (r.option == null && r.button.type == ButtonType.DROPDOWN)o.put("options", new ArrayList<Object>(r.button.options));
			out.add(o);
		}
		return out;
	}
}
