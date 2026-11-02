package com.tom.cpm.blockbench.tools.settings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.blockbench.util.ServerCommands;
import com.tom.cpm.blockbench.util.ServerCommands.Item;
import com.tom.cpm.blockbench.util.ServerCommands.Kind;
import com.tom.cpm.shared.util.ScalingOptions;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-SETTINGS, F21 + G44): 'Tamanho e atributos' of the character (CPM 'Scaling' effect) and the card 'O que o
 * servidor precisa liberar' with ready '/cpm scaling' commands for the server staff.
 * <ul>
 * <li>Escala geral (slider 0.1..3 + exact value up to the option's maximum), altura dos olhos, hitbox, alcance, each
 * with '↺ padrão' (1 = unset); the other attributes collapsed ('o servidor decide'); posição de exibição (render
 * position / rotation / scale, visual only).</li>
 * <li>Values are clamped to ScalingOptions getMin / getMax. scalingEnabled = any option set (not 0 / 1) or a render
 * transform set. An untouched OK changes nothing.</li>
 * <li>The card only reads the values and copies text (no undo, the project is not marked modified).</li>
 * </ul>
 */
public final class SizeAttributesDialog {
	public static final String DIALOG_ID = "cpmplus_w3settings_size";
	public static final String CARD_ID = "cpmplus_w3settings_server";
	/** the options of the main section (the rest is under 'Atributos') */
	public static final List<ScalingOptions> MAIN = Arrays.asList(ScalingOptions.ENTITY, ScalingOptions.EYE_HEIGHT,
			ScalingOptions.HITBOX_WIDTH, ScalingOptions.HITBOX_HEIGHT, ScalingOptions.REACH);
	public static final float SLIDER_MIN = 0.1f, SLIDER_MAX = 3f;

	private SizeAttributesDialog() {
	}

	/** the values of the dialog: every option (1 = normal) + the render transform (null = untouched) */
	public static final class SizeValues {
		public final Map<ScalingOptions, Float> options = new EnumMap<>(ScalingOptions.class);
		public Vec3f renderPos, renderRot, renderScl;

		public static SizeValues current() {
			ProjectData pd = Project.getData();
			SizeValues v = new SizeValues();
			for(ScalingOptions o : ScalingOptions.VALUES)v.options.put(o, valueOf(pd, o));
			v.renderPos = shown(pd.renderPos, 0);
			v.renderRot = shown(pd.renderRot, 0);
			v.renderScl = shown(pd.renderScl, 1);
			return v;
		}
	}

	/** stored value, 1 when unset (null / 0) */
	public static float valueOf(ProjectData pd, ScalingOptions o) {
		Float f = pd.scalingOpt.get(o);
		return ServerCommands.isUnset(f) ? 1f : f;
	}

	/** what the dialog shows for a render vector: the stored one, or the neutral value (scale: 0 and 1 both mean unset) */
	private static Vec3f shown(Vec3f v, float neutral) {
		if(v == null)return new Vec3f(neutral, neutral, neutral);
		if(neutral == 1)return new Vec3f(v.x == 0 ? 1 : v.x, v.y == 0 ? 1 : v.y, v.z == 0 ? 1 : v.z);
		return new Vec3f(v);
	}

	private static String key(ScalingOptions o) {
		return "opt_" + ServerCommands.id(o);
	}

	private static String label(ScalingOptions o) {
		if(MAIN.contains(o))return "cpmplus.w3settings.size.opt." + ServerCommands.id(o);
		return "label.cpm.tree.scaling." + o.name().toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------ dialog

	public static void open() {
		SizeValues cur = SizeValues.current();
		Dialog[] dlg = new Dialog[1];
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w3settings.size.intro"));
		float ent = cur.options.get(ScalingOptions.ENTITY);
		rows.add(FormRow.range("entity_slider", "cpmplus.w3settings.size.entitySlider", clamp(ent, SLIDER_MIN, SLIDER_MAX), SLIDER_MIN, SLIDER_MAX, 0.05)
				.description("cpmplus.w3settings.size.entitySlider.desc"));
		for(ScalingOptions o : MAIN) {
			rows.add(FormRow.decimal(key(o), label(o), cur.options.get(o), o.getMin(), o.getMax(), 0.01)
					.description(PlusDialogs.trf("cpmplus.w3settings.size.range", ServerCommands.num(o.getMin()), ServerCommands.num(o.getMax()))));
			rows.add(FormRow.buttons("reset_" + ServerCommands.id(o), null, new String[] {"cpmplus.w3settings.size.reset"}, i -> {
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set(key(o), 1.0);
				if(o == ScalingOptions.ENTITY)m.set("entity_slider", 1.0);
				setValues(dlg[0], m);
			}));
		}
		boolean anyAttr = false;
		for(ScalingOptions o : ScalingOptions.VALUES)if(!MAIN.contains(o) && Math.abs(cur.options.get(o) - 1f) > 1e-6f)anyAttr = true;
		rows.add(FormRow.separator());
		rows.add(FormRow.checkbox("show_attrs", "cpmplus.w3settings.size.showAttrs", anyAttr)
				.description("cpmplus.w3settings.size.showAttrs.desc"));
		rows.add(FormRow.htmlInfo("attrs_warn", null, "<span style='color: var(--color-warning, #e6a23c);'>" + PlusDialogs.escMdLines(PlusDialogs.tr("cpmplus.w3settings.size.attrsWarn")) + "</span>")
				.when(r -> r.getBool("show_attrs")));
		for(ScalingOptions o : ScalingOptions.VALUES) {
			if(MAIN.contains(o))continue;
			FormRow row = FormRow.decimal(key(o), label(o), cur.options.get(o), o.getMin(), o.getMax(), 0.01).when(r -> r.getBool("show_attrs"));
			if(o.getDefualtEnabled())row.description("cpmplus.w3settings.size.freeOption");
			rows.add(row);
		}
		boolean anyRender = !isNeutral(cur.renderPos, 0) || !isNeutral(cur.renderRot, 0) || !isNeutral(cur.renderScl, 1);
		rows.add(FormRow.separator());
		rows.add(FormRow.checkbox("show_render", "cpmplus.w3settings.size.showRender", anyRender)
				.description("cpmplus.w3settings.size.showRender.desc"));
		rows.add(vector("render_pos", "cpmplus.w3settings.size.renderPos", cur.renderPos, -Vec3f.MAX_POS, Vec3f.MAX_POS, 0.1));
		rows.add(vector("render_rot", "cpmplus.w3settings.size.renderRot", cur.renderRot, -360, 360, 1));
		rows.add(vector("render_scl", "cpmplus.w3settings.size.renderScl", cur.renderScl, 0, 25, 0.05));
		rows.add(FormRow.separator());
		rows.add(FormRow.buttons("server", "cpmplus.w3settings.size.serverTitle", new String[] {"cpmplus.w3settings.size.serverOpen"}, i -> {
			SizeValues v = dlg[0] != null ? read(new FormResult(dlg[0].getFormResult()), cur) : cur;
			openServerCard(v.options);
		}));
		double[] last = {clamp(ent, SLIDER_MIN, SLIDER_MAX), ent};
		FormOpts opts = new FormOpts();
		opts.width = 560;
		opts.onChange = r -> {
			// slider <-> exact value
			double s = r.getDouble("entity_slider", last[0]);
			double n = r.getDouble(key(ScalingOptions.ENTITY), last[1]);
			JsPropertyMap<Object> m = JsPropertyMap.of();
			if(Math.abs(s - last[0]) > 1e-6) {
				m.set(key(ScalingOptions.ENTITY), s);
				n = s;
			} else if(Math.abs(n - last[1]) > 1e-6) {
				double cl = clamp((float) n, SLIDER_MIN, SLIDER_MAX);
				if(Math.abs(cl - s) > 1e-6)m.set("entity_slider", cl);
				s = cl;
			}
			last[0] = s;
			last[1] = n;
			if(m.has(key(ScalingOptions.ENTITY)) || m.has("entity_slider"))setValues(dlg[0], m);
		};
		dlg[0] = PlusDialogs.form(DIALOG_ID, "cpmplus.w3settings.size.title", rows, r -> apply(read(r, cur), true), opts);
	}

	private static FormRow vector(String key, String label, Vec3f v, double min, double max, double step) {
		JsArray<Double> a = new JsArray<>();
		a.push((double) v.x);
		a.push((double) v.y);
		a.push((double) v.z);
		return FormRow.text(key, label, "").option("type", "vector").option("dimensions", 3.0).option("value", a)
				.option("min", min).option("max", max).option("step", step).when(r -> r.getBool("show_render"));
	}

	private static void setValues(Dialog d, JsPropertyMap<Object> m) {
		if(d == null)return;
		try {
			d.setFormValues(m, false);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not update the dialog", e);
		}
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : v > max ? max : v;
	}

	/** reads the form; fields that are missing keep the current value */
	static SizeValues read(FormResult r, SizeValues cur) {
		SizeValues v = new SizeValues();
		for(ScalingOptions o : ScalingOptions.VALUES) {
			double d = r.getDouble(key(o), cur.options.get(o));
			if(Double.isNaN(d))d = cur.options.get(o);
			v.options.put(o, (float) d);
		}
		v.renderPos = readVec(r, "render_pos", cur.renderPos);
		v.renderRot = readVec(r, "render_rot", cur.renderRot);
		v.renderScl = readVec(r, "render_scl", cur.renderScl);
		return v;
	}

	private static Vec3f readVec(FormResult r, String key, Vec3f def) {
		Object raw = r.raw(key);
		if(raw == null || !JsArray.isArray(raw))return def;
		JsArray<Object> a = Js.uncheckedCast(raw);
		if(a.length < 3)return def;
		float[] f = new float[3];
		float[] d = {def.x, def.y, def.z};
		for (int i = 0; i < 3; i++) {
			Object o = a.getAt(i);
			double x = o != null && "number".equals(Js.typeof(o)) ? Js.asDouble(o) : Double.NaN;
			if(Double.isNaN(x) && o != null) {
				try {
					x = Double.parseDouble(String.valueOf(o));
				} catch (Exception e) {
					x = Double.NaN;
				}
			}
			f[i] = Double.isNaN(x) ? d[i] : (float) x;
		}
		return new Vec3f(f[0], f[1], f[2]);
	}

	// ------------------------------------------------------------------ apply

	/** what {@link #apply} did */
	public static final class Result {
		public boolean changed;
		public final List<String> clamped = new ArrayList<>();
	}

	/**
	 * Applies the values: clamped to each option's range, 1 (or 0) removes the option, a changed render vector is
	 * stored; scalingEnabled follows the rule (any option or render transform set). Unchanged values change nothing.
	 */
	public static Result apply(SizeValues v, boolean toast) {
		Result res = new Result();
		SizeValues cur = SizeValues.current();
		Map<ScalingOptions, Float> next = new EnumMap<>(ScalingOptions.class);
		List<ScalingOptions> changed = new ArrayList<>();
		for(ScalingOptions o : ScalingOptions.VALUES) {
			Float in = v.options.get(o);
			float x = in == null || in.isNaN() ? cur.options.get(o) : in;
			if(Math.abs(x) < 1e-6f)x = 1f;// 0 = unset
			float c = ServerCommands.clamp(o, x);
			if(Math.abs(c - x) > 1e-6f)res.clamped.add(ServerCommands.id(o));
			next.put(o, c);
			if(Math.abs(c - cur.options.get(o)) > 1e-4f)changed.add(o);
		}
		Vec3f rp = clampVec(v.renderPos, -Vec3f.MAX_POS, Vec3f.MAX_POS), rr = clampVec(v.renderRot, -360, 360), rs = clampVec(v.renderScl, 0, 25);
		boolean posCh = rp != null && !sameVec(rp, cur.renderPos);
		boolean rotCh = rr != null && !sameVec(rr, cur.renderRot);
		boolean sclCh = rs != null && !sameVec(rs, cur.renderScl);
		if(changed.isEmpty() && !posCh && !rotCh && !sclCh)return res;
		res.changed = true;
		ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
		ProjectData pd = Project.getData();
		for(ScalingOptions o : changed) {
			float x = next.get(o);
			if(ServerCommands.isUnset(x))pd.scalingOpt.remove(o);
			else pd.scalingOpt.put(o, x);
		}
		if(posCh)pd.renderPos = rp;
		if(rotCh)pd.renderRot = rr;
		if(sclCh)pd.renderScl = rs;
		boolean any = false;
		for(Float f : pd.scalingOpt.values())if(!ServerCommands.isUnset(f))any = true;
		any |= !isNeutral(pd.renderPos, 0) || !isNeutral(pd.renderRot, 0) || !isScaleUnset(pd.renderScl);
		pd.scalingEnabled = any;
		pd.flush();
		Project.saved = false;
		if(toast) {
			ProjectDataUndo.offerUndo(before, PlusDialogs.tr("cpmplus.w3settings.size.done"));
			if(!res.clamped.isEmpty())PlusDialogs.toast(PlusDialogs.trf("cpmplus.w3settings.size.clamped", String.join(", ", res.clamped)), "warning", 10000, null);
			int blocked = ServerCommands.countBlocked(pd.scalingOpt);
			if(blocked > 0)PlusDialogs.toast(PlusDialogs.trf("cpmplus.w3settings.size.serverToast", blocked), "dns", 10000, () -> openServerCard(Project.getData().scalingOpt));
		}
		return res;
	}

	private static Vec3f clampVec(Vec3f v, float min, float max) {
		if(v == null)return null;
		return new Vec3f(clamp(v.x, min, max), clamp(v.y, min, max), clamp(v.z, min, max));
	}

	private static boolean sameVec(Vec3f a, Vec3f b) {
		if(a == null || b == null)return a == b;
		return Math.abs(a.x - b.x) < 1e-4f && Math.abs(a.y - b.y) < 1e-4f && Math.abs(a.z - b.z) < 1e-4f;
	}

	private static boolean isNeutral(Vec3f v, float n) {
		return v == null || (v.x == n && v.y == n && v.z == n);
	}

	/** render scale components 0 and 1 both mean 'unset' (ScalingElement.hasTransform) */
	private static boolean isScaleUnset(Vec3f v) {
		return v == null || ((v.x == 0 || v.x == 1) && (v.y == 0 || v.y == 1) && (v.z == 0 || v.z == 1));
	}

	// ------------------------------------------------------------------ G44: what the server must enable

	/** number for the UI: the language's decimal separator (pt-BR comma); commands always use '.' */
	public static String uiNum(float v) {
		String s = ServerCommands.num(v);
		String sep = PlusDialogs.tr("cpmplus.w3settings.num.decimalSep");
		return ",".equals(sep) ? s.replace('.', ',') : s;
	}

	private static String optName(ScalingOptions o) {
		return PlusDialogs.tr(label(o));
	}

	/** one explanation line per value (grouped), plain text */
	public static List<String> cardLines(List<Item> items) {
		List<String> out = new ArrayList<>();
		for(Kind k : new Kind[] {Kind.SIZE, Kind.ATTRIBUTE, Kind.FREE}) {
			List<Item> g = new ArrayList<>();
			for(Item i : items)if(i.kind == k)g.add(i);
			if(g.isEmpty())continue;
			out.add(PlusDialogs.tr("cpmplus.w3settings.server.group." + k.name().toLowerCase(Locale.ROOT)));
			for(Item i : g) {
				String lk = k == Kind.FREE ? "cpmplus.w3settings.server.line.free" : k == Kind.SIZE ? "cpmplus.w3settings.server.line.size" : "cpmplus.w3settings.server.line.attr";
				out.add("• " + PlusDialogs.trf(lk, optName(i.option), uiNum(i.value)));
			}
		}
		return out;
	}

	/** the text for 'Copiar mensagem pro staff' (pt-BR first) */
	public static String staffMessage(List<Item> items, String nick, boolean useAll, boolean limits) {
		List<String> names = new ArrayList<>();
		for(Item i : ServerCommands.needingServer(items))names.add(optName(i.option) + " " + uiNum(i.value) + "x");
		String cmds = String.join("\n", ServerCommands.commands(items, nick, useAll, limits));
		String who = nick != null && !nick.trim().isEmpty() ? nick.trim() : ServerCommands.NICK_PLACEHOLDER;
		return PlusDialogs.trf("cpmplus.w3settings.server.staffMessage", String.join(", ", names), cmds, who);
	}

	/**
	 * 'Copiar comandos' (message false) / 'Copiar mensagem pro staff' (message true): clipboard, or the selectable text
	 * box when the browser blocks it. Read only (no undo, Project.saved unchanged).
	 */
	public static elemental2.promise.Promise<Boolean> copy(List<Item> items, String nick, boolean useAll, boolean limits, boolean message) {
		String text = message ? staffMessage(items, nick, useAll, limits) : String.join("\n", ServerCommands.commands(items, nick, useAll, limits));
		return PlusDialogs.copyText(text);
	}

	/** the card 'O que o servidor precisa liberar' for these values (read only; copies text) */
	public static void openServerCard(Map<ScalingOptions, Float> values) {
		List<Item> items = ServerCommands.items(values);
		List<Item> need = ServerCommands.needingServer(items);
		boolean allowAll = ServerCommands.allShortcutAllowed(items);
		Dialog[] dlg = new Dialog[1];
		List<FormRow> rows = new ArrayList<>();
		if(need.isEmpty()) {
			rows.add(FormRow.info("nothing", null, items.isEmpty() ? "cpmplus.w3settings.server.nothing" : "cpmplus.w3settings.server.onlyFree"));
		}
		List<String> lines = cardLines(items);
		if(!lines.isEmpty())rows.add(FormRow.info("lines", null, String.join("\n", lines)));
		if(!need.isEmpty()) {
			rows.add(FormRow.separator());
			rows.add(FormRow.radio("target", "cpmplus.w3settings.server.target", PlusDialogs.options(
					"all", "cpmplus.w3settings.server.target.all",
					"nick", "cpmplus.w3settings.server.target.nick"), "all"));
			rows.add(FormRow.text("nick", "cpmplus.w3settings.server.nick", "").placeholder("<nick>")
					.description("cpmplus.w3settings.server.nick.desc").when(r -> "nick".equals(r.getString("target"))));
			if(allowAll)rows.add(FormRow.checkbox("useAll", "cpmplus.w3settings.server.useAll", false).description("cpmplus.w3settings.server.useAll.warn"));
			rows.add(FormRow.checkbox("limits", "cpmplus.w3settings.server.limits", false).description("cpmplus.w3settings.server.limits.desc"));
			rows.add(FormRow.textarea("preview", "cpmplus.w3settings.server.preview", String.join("\n", ServerCommands.commands(items, null, false, false))).readonly());
			rows.add(FormRow.buttons("copy", null, new String[] {"cpmplus.w3settings.server.copyCommands", "cpmplus.w3settings.server.copyMessage"}, i -> {
				FormResult r = new FormResult(dlg[0] != null ? dlg[0].getFormResult() : null);
				String nick = "nick".equals(r.getString("target")) ? r.getString("nick") : null;
				copy(items, nick, r.getBool("useAll"), r.getBool("limits"), i != 0);
			}));
			rows.add(FormRow.info("notes", null, "cpmplus.w3settings.server.notes"));
		}
		FormOpts o = new FormOpts();
		o.width = 600;
		o.onChange = r -> {
			if(need.isEmpty() || dlg[0] == null)return;
			String nick = "nick".equals(r.getString("target")) ? r.getString("nick") : null;
			String t = String.join("\n", ServerCommands.commands(items, nick, r.getBool("useAll"), r.getBool("limits")));
			if(!t.equals(r.getString("preview"))) {
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("preview", t);
				setValues(dlg[0], m);
			}
		};
		dlg[0] = PlusDialogs.form(CARD_ID, "cpmplus.w3settings.server.title", rows, null, o);
	}
}
