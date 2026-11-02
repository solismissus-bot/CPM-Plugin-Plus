package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.plus.PlusGestureParts;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ small animation tools (beta2): F5 'Fechar o loop', F6 'Atrasar / adiantar movimento', G63 'Suavizar movimento'
 * (ease presets baked as keys on the CPM frame grid, so the game shows the same curve), F4 'Gerar piscada', E2+G61
 * 'Cola de comandos' and G3 'Limpar cores fantasmas'. Every change is ONE undo entry (PlusUndo.editAnimations).
 */
public final class ExtraTools {
	private ExtraTools() {
	}

	private static final String[] TR = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE, KeyMath.COLOR};

	private static final class Plan {
		final BoneAnimator ba;
		final String ch;
		final List<KeyData> keys;
		final boolean replace;

		Plan(BoneAnimator ba, String ch, List<KeyData> keys, boolean replace) {
			this.ba = ba;
			this.ch = ch;
			this.keys = keys;
			this.replace = replace;
		}
	}

	private static void apply(Animation anim, String undoKey, List<Plan> plans) {
		PlusUndo.editAnimations(Collections.singletonList(anim), t(undoKey), () -> {
			for (Plan p : plans)AnimEdit.writeKeys(p.ba, p.ch, p.keys, p.replace);
		});
		AnimToolSupport.refreshPreview();
	}

	private static boolean hasMolang(List<KeyData> keys) {
		for (KeyData k : keys)if (k.molang)return true;
		return false;
	}

	private static Animation needAnim() {
		Animation a = Animation.selected;
		if (a == null)PlusDialogs.block(t("cpmplus.extra.err.noAnim"));
		else if (!(a.length > KeyMath.EPS)) {
			PlusDialogs.block(t("cpmplus.extra.err.noLength"));
			return null;
		}
		return a;
	}

	private static double snap(Animation a, double tt) {
		int s = AnimEdit.snappingOf(a);
		if (s < 1)return Math.round(tt * 1e6) / 1e6;
		return Math.round(tt * s) / (double) s;
	}

	// ---------------- F5: close the loop ----------------

	/** adds a key at the end of every channel whose end value differs from its start value (end = start) */
	public static AnimToolSupport.Result closeLoop(Animation anim, boolean apply) {
		AnimToolSupport.Result r = new AnimToolSupport.Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.extra.err.noAnim"));
		double L = anim.length;
		List<Plan> plans = new ArrayList<>();
		int formulas = 0;
		for (BoneAnimator ba : AnimEdit.boneAnimators(anim)) {
			for (String ch : TR) {
				List<KeyData> keys = AnimEdit.readKeys(ba, ch);
				if (keys.isEmpty())continue;
				if (hasMolang(keys)) {
					formulas++;
					continue;
				}
				double[] f0 = AnimEdit.sample(ba, ch, 0), fL = AnimEdit.sample(ba, ch, L);
				if (KeyMath.seam(ch, f0, fL) != KeyMath.Seam.OPEN)continue;
				KeyData last = keys.get(keys.size() - 1);
				KeyData nk = last.copy();
				nk.time = L;
				nk.molang = false;
				nk.v = new double[][] {KeyMath.dup(f0)};
				if (!"linear".equals(nk.interp) && !"catmullrom".equals(nk.interp))nk.interp = "linear";
				nk.bLT = nk.bLV = nk.bRT = nk.bRV = null;
				plans.add(new Plan(ba, ch, Collections.singletonList(nk), false));
			}
		}
		if (formulas > 0)r.warn("molang", t("cpmplus.extra.warn.formula", formulas));
		if (!"loop".equals(anim.loop))r.warn("noloop", t("cpmplus.extra.f5.notLoop"));
		r.put("keys", (double) plans.size());
		if (apply && !plans.isEmpty()) {
			apply(anim, "cpmplus.extra.f5.undo", plans);
			r.changed = true;
		}
		return r;
	}

	public static void openCloseLoop() {
		Animation anim = needAnim();
		if (anim == null)return;
		AnimToolSupport.Result res = closeLoop(anim, true);
		if (!res.ok)PlusDialogs.block(res.errorText());
		else if (!res.changed)PlusDialogs.quick(t("cpmplus.extra.f5.nothing"), 3000);
		else PlusDialogs.done(t("cpmplus.extra.f5.done", ((Double) res.extra.get("keys")).intValue()));
		if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
	}

	// ---------------- F6: delay / advance (phase shift with loop wrap) ----------------

	public static AnimToolSupport.Result shift(Animation anim, List<Group> groups, double dt, boolean apply) {
		AnimToolSupport.Result r = new AnimToolSupport.Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.extra.err.noAnim"));
		double L = anim.length;
		double d = KeyMath.roundToSnap(dt, AnimEdit.snappingOf(anim));
		if (Math.abs(d) < KeyMath.EPS)return r.error("zero", t("cpmplus.extra.f6.zero"));
		List<Plan> plans = new ArrayList<>();
		int formulas = 0;
		List<BoneAnimator> bas = new ArrayList<>();
		if (groups == null || groups.isEmpty())bas.addAll(AnimEdit.boneAnimators(anim));
		else for (Group g : groups) {
			BoneAnimator ba = AnimEdit.animatorOf(anim, g);
			if (ba != null)bas.add(ba);
		}
		for (BoneAnimator ba : bas) {
			for (String ch : KeyMath.CHANNELS) {
				List<KeyData> keys = AnimEdit.readKeys(ba, ch);
				if (keys.isEmpty())continue;
				if (hasMolang(keys)) {
					formulas++;
					continue;
				}
				KeyMath.ShiftResult sr = KeyMath.VIS.equals(ch) ? KeyMath.shiftWrap(ch, keys, d, L, AnimEdit.sampler(ba, ch), true) : KeyMath.shiftWrap(ch, keys, d, L, AnimEdit.sampler(ba, ch));
				plans.add(new Plan(ba, ch, sr.keys, true));
			}
		}
		if (formulas > 0)r.warn("molang", t("cpmplus.extra.warn.formula", formulas));
		r.put("channels", (double) plans.size()).put("dt", d);
		if (apply && !plans.isEmpty()) {
			apply(anim, "cpmplus.extra.f6.undo", plans);
			r.changed = true;
		}
		return r;
	}

	public static void openShift() {
		Animation anim = needAnim();
		if (anim == null)return;
		List<Group> groups = AnimToolSupport.selectedGroups();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, groups.isEmpty() ? t("cpmplus.extra.f6.introAll") : t("cpmplus.extra.f6.introSel", groups.size())));
		rows.add(FormRow.decimal("dt", "cpmplus.extra.f6.amount", 0.25, -anim.length, anim.length, 0.05).description("cpmplus.extra.f6.amount.desc"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.extra.apply";
		PlusDialogs.form("cpmplus_extra_shift", "cpmplus.extra.f6.title", rows, fr -> {
			AnimToolSupport.Result res = shift(anim, groups, fr.getDouble("dt", 0), true);
			if (!res.ok)PlusDialogs.block(res.errorText());
			else if (!res.changed)PlusDialogs.quick(t("cpmplus.extra.f6.nothing"), 3000);
			else PlusDialogs.done(t("cpmplus.extra.f6.done", KeyMath.fmt((Double) res.extra.get("dt"), 2).replace('.', ',')));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	// ---------------- G63: ease presets on the selected keys ----------------

	static double ease(String preset, double u) {
		switch (preset) {
		case "in": return u * u;
		case "out": return 1 - (1 - u) * (1 - u);
		case "back": {
			double c1 = 1.70158, c3 = c1 + 1, x = u - 1;
			return 1 + c3 * x * x * x + c1 * x * x;
		}
		default: return u * u * (3 - 2 * u);
		}
	}

	private static native Object[] selectedKeys() /*-{
		var out = [];
		var sel = ($wnd.Timeline && $wnd.Timeline.selected) || [];
		for (var i = 0; i < sel.length; i++) {
			var k = sel[i];
			if (k && k.animator && typeof k.time === 'number' && k.channel)out.push({a: k.animator, ch: k.channel, t: k.time});
		}
		return out;
	}-*/;

	public static AnimToolSupport.Result easeSelected(Animation anim, String preset, boolean apply) {
		AnimToolSupport.Result r = new AnimToolSupport.Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.extra.err.noAnim"));
		List<BoneAnimator> bas = new ArrayList<>();
		List<String> chs = new ArrayList<>();
		List<List<Double>> times = new ArrayList<>();
		for (Object o : selectedKeys()) {
			JsPropertyMap<Object> m = Js.asPropertyMap(o);
			BoneAnimator ba = Js.uncheckedCast(m.get("a"));
			String ch = Js.asString(m.get("ch"));
			if (!KeyMath.isTransform(ch) && !KeyMath.COLOR.equals(ch))continue;
			int idx = -1;
			for (int i = 0; i < bas.size(); i++)if (bas.get(i) == ba && chs.get(i).equals(ch))idx = i;
			if (idx < 0) {
				bas.add(ba);
				chs.add(ch);
				times.add(new ArrayList<>());
				idx = bas.size() - 1;
			}
			times.get(idx).add(Js.asDouble(m.get("t")));
		}
		int snapping = AnimEdit.snappingOf(anim);
		int fps = snapping < 1 ? 20 : snapping;
		List<Plan> plans = new ArrayList<>();
		int pairs = 0, tooClose = 0, formulas = 0;
		for (int i = 0; i < bas.size(); i++) {
			List<Double> ts = times.get(i);
			if (ts.size() < 2)continue;
			Collections.sort(ts);
			BoneAnimator ba = bas.get(i);
			String ch = chs.get(i);
			List<KeyData> keys = AnimEdit.readKeys(ba, ch);
			if (hasMolang(keys)) {
				formulas++;
				continue;
			}
			List<KeyData> out = new ArrayList<>();
			List<Double> done = new ArrayList<>();
			for (int j = 0; j + 1 < ts.size(); j++) {
				double t0 = ts.get(j), t1 = ts.get(j + 1);
				int steps = (int) Math.round((t1 - t0) * fps);
				if (steps < 2) {
					tooClose++;
					continue;
				}
				if (steps > 120)steps = 120;
				double[] v0 = AnimEdit.sample(ba, ch, t0), v1 = AnimEdit.sample(ba, ch, t1);
				for (int s = 1; s < steps; s++) {
					double u = s / (double) steps;
					double e = ease(preset, u);
					double[] v = new double[3];
					for (int a = 0; a < 3; a++)v[a] = v0[a] + (v1[a] - v0[a]) * e;
					KeyData k = new KeyData();
					k.time = snap(anim, t0 + (t1 - t0) * u);
					k.interp = "linear";
					k.v = new double[][] {v};
					out.add(k);
				}
				pairs++;
				done.add(t0);
				done.add(t1);
			}
			// the selected end keys become linear so nothing else bends the baked curve
			for (KeyData k : keys) {
				for (double tt : done)if (Math.abs(k.time - tt) < KeyMath.EPS && !"linear".equals(k.interp)) {
					KeyData c = k.copy();
					c.interp = "linear";
					c.bLT = c.bLV = c.bRT = c.bRV = null;
					out.add(c);
				}
			}
			if (!out.isEmpty())plans.add(new Plan(ba, ch, out, false));
		}
		if (pairs == 0 && tooClose == 0 && formulas == 0)return r.error("noKeys", t("cpmplus.extra.g63.noKeys"));
		if (tooClose > 0)r.warn("close", t("cpmplus.extra.g63.tooClose", tooClose));
		if (formulas > 0)r.warn("molang", t("cpmplus.extra.warn.formula", formulas));
		r.put("pairs", (double) pairs);
		if (apply && !plans.isEmpty()) {
			apply(anim, "cpmplus.extra.g63.undo", plans);
			r.changed = true;
		}
		return r;
	}

	public static void openEase() {
		Animation anim = needAnim();
		if (anim == null)return;
		Map<String, String> opts = new LinkedHashMap<>();
		opts.put("inout", "cpmplus.extra.g63.inout");
		opts.put("in", "cpmplus.extra.g63.in");
		opts.put("out", "cpmplus.extra.g63.out");
		opts.put("back", "cpmplus.extra.g63.back");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.extra.g63.intro")));
		rows.add(FormRow.radio("preset", "cpmplus.extra.g63.preset", opts, "inout"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.extra.apply";
		PlusDialogs.form("cpmplus_extra_ease", "cpmplus.extra.g63.title", rows, fr -> {
			AnimToolSupport.Result res = easeSelected(anim, fr.getString("preset"), true);
			if (!res.ok)PlusDialogs.block(res.errorText());
			else if (!res.changed)PlusDialogs.quick(t("cpmplus.extra.g63.nothing"), 3000);
			else PlusDialogs.done(t("cpmplus.extra.g63.done", ((Double) res.extra.get("pairs")).intValue()));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	// ---------------- F4: blink generator ----------------

	public static AnimToolSupport.Result blink(Animation anim, List<Group> groups, boolean hide, double every, double dur, boolean apply) {
		AnimToolSupport.Result r = new AnimToolSupport.Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.extra.err.noAnim"));
		if (groups == null || groups.isEmpty())return r.error("noParts", t("cpmplus.extra.f4.noParts"));
		double L = anim.length;
		dur = Math.max(dur, 0.05);
		if (dur >= L)return r.error("short", t("cpmplus.extra.f4.tooShort"));
		int count = Math.max(1, (int) Math.floor(L / Math.max(every, dur * 2) + 1e-6));
		List<Plan> plans = new ArrayList<>();
		for (Group g : groups) {
			BoneAnimator ba = AnimEdit.animatorOf(anim, g);
			if (ba == null)continue;
			List<KeyData> out = new ArrayList<>();
			for (int i = 1; i <= count; i++) {
				double end = snap(anim, i * L / count);
				double start = snap(anim, end - dur);
				if (start <= KeyMath.EPS)start = snap(anim, Math.min(dur, L / 2));
				if (hide) {
					out.add(KeyData.visKey(start, false));
					out.add(KeyData.visKey(Math.min(end, L), true));
				} else {
					double mid = snap(anim, (start + end) / 2);
					double[] s0 = AnimEdit.valueOrRest(ba, KeyMath.SCALE, start);
					double[] s1 = AnimEdit.valueOrRest(ba, KeyMath.SCALE, mid);
					double[] s2 = AnimEdit.valueOrRest(ba, KeyMath.SCALE, end);
					out.add(KeyData.of(start, "linear", s0[0], s0[1], s0[2]));
					out.add(KeyData.of(mid, "linear", s1[0], s1[1] * 0.1, s1[2]));
					out.add(KeyData.of(end, "linear", s2[0], s2[1], s2[2]));
				}
			}
			if (hide && AnimEdit.readKeys(ba, KeyMath.VIS).isEmpty())out.add(KeyData.visKey(0, true));
			plans.add(new Plan(ba, hide ? KeyMath.VIS : KeyMath.SCALE, out, false));
		}
		if (plans.isEmpty())return r.error("noParts", t("cpmplus.extra.f4.noParts"));
		if (!"loop".equals(anim.loop))r.warn("noloop", t("cpmplus.extra.f4.notLoop"));
		r.put("blinks", (double) count).put("parts", (double) plans.size());
		if (apply) {
			apply(anim, "cpmplus.extra.f4.undo", plans);
			r.changed = true;
		}
		return r;
	}

	public static void openBlink() {
		Animation anim = needAnim();
		if (anim == null)return;
		List<Group> groups = AnimToolSupport.selectedGroups();
		if (groups.isEmpty()) {
			PlusDialogs.block(t("cpmplus.extra.f4.noParts"));
			return;
		}
		Map<String, String> modes = new LinkedHashMap<>();
		modes.put("close", "cpmplus.extra.f4.close");
		modes.put("hide", "cpmplus.extra.f4.hide");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.extra.f4.intro", groups.size())));
		rows.add(FormRow.radio("mode", "cpmplus.extra.f4.mode", modes, "close"));
		rows.add(FormRow.decimal("every", "cpmplus.extra.f4.every", Math.min(3, anim.length), 0.2, 60, 0.1));
		rows.add(FormRow.decimal("dur", "cpmplus.extra.f4.dur", 0.15, 0.05, 2, 0.05));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.extra.apply";
		PlusDialogs.form("cpmplus_extra_blink", "cpmplus.extra.f4.title", rows, fr -> {
			AnimToolSupport.Result res = blink(anim, groups, "hide".equals(fr.getString("mode")), fr.getDouble("every", 3), fr.getDouble("dur", 0.15), true);
			if (!res.ok)PlusDialogs.block(res.errorText());
			else PlusDialogs.done(t("cpmplus.extra.f4.done", ((Double) res.extra.get("blinks")).intValue()));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	// ---------------- E2 + G61: command cheat sheet ----------------

	private static String prop(Animation a, String key) {
		Object v = Js.asPropertyMap(a).get(key);
		return v == null ? null : String.valueOf(v);
	}

	/** the name as the game command needs it (quoted when it has spaces or symbols) */
	static String cmdName(String id) {
		if (id.matches("[A-Za-z0-9_.+-]+"))return id;
		return "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	public static String commandSheet(List<String> warnings) {
		StringBuilder sb = new StringBuilder();
		List<String> seen = new ArrayList<>();
		for (Animation a : AnimEdit.all()) {
			String type = prop(a, "cpm_type");
			if (!PlusGestureParts.isGroupedType(type))continue;
			String id = PlusGestureParts.gameId(a.name);
			if (id == null || id.trim().isEmpty() || seen.contains(id))continue;
			seen.add(id);
			boolean value = "value_layer".equalsIgnoreCase(type == null ? "" : type.trim());
			boolean server = "true".equals(prop(a, "cpm_commandCtrl"));
			String n = cmdName(id);
			String v = value ? " 128" : "";
			sb.append("# ").append(id).append(value ? t("cpmplus.extra.e2.valueNote") : "").append('\n');
			sb.append("/cpmclient animate ").append(n).append(v).append('\n');
			if (server) {
				sb.append("/cpm animate @p ").append(n).append(v).append('\n');
				sb.append("/cpm detect @p ").append(n).append('\n');
			}
			sb.append('\n');
			if (id.contains("\""))warnings.add(t("cpmplus.extra.g61.quote", id));
			else if (!n.equals(id))warnings.add(t("cpmplus.extra.g61.space", id));
		}
		return sb.toString().trim();
	}

	public static void openCommands() {
		List<String> warnings = new ArrayList<>();
		String text = commandSheet(warnings);
		if (text.isEmpty()) {
			PlusDialogs.block(t("cpmplus.extra.e2.none"));
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.extra.e2.intro")));
		for (int i = 0; i < warnings.size() && i < 8; i++)rows.add(FormRow.info("w" + i, null, warnings.get(i)));
		rows.add(FormRow.textarea("cmds", "cpmplus.extra.e2.list", text));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.extra.e2.copy";
		PlusDialogs.form("cpmplus_extra_commands", "cpmplus.extra.e2.title", rows, fr -> PlusDialogs.copyText(text).then(b -> {
			if (Boolean.TRUE.equals(b))PlusDialogs.quick(t("cpmplus.extra.e2.copied"), 2500);
			return null;
		}), fo);
	}

	// ---------------- test hooks (window.__cpmplus, test mode only) ----------------

	private static List<Group> partsOf(JsPropertyMap<Object> o) {
		List<Group> gs = new ArrayList<>();
		Object p = o.get("parts");
		if (p == null)return gs;
		Object[] arr = Js.uncheckedCast(p);
		for (Object s : arr) {
			Group g = AnimEdit.groupByPath(String.valueOf(s));
			if (g != null)gs.add(g);
		}
		return gs;
	}

	public static void registerHooks() {
		if (!com.tom.cpm.blockbench.PlusTestHooks.active())return;
		com.tom.cpm.blockbench.PlusTestHooks.register("extra.f5", o -> closeLoop(AnimToolSupport.anim(o), AnimToolSupport.bool(o, "apply", true)).toJs());
		com.tom.cpm.blockbench.PlusTestHooks.register("extra.f6", o -> shift(AnimToolSupport.anim(o), partsOf(o), AnimToolSupport.num(o, "dt", 0.25), AnimToolSupport.bool(o, "apply", true)).toJs());
		com.tom.cpm.blockbench.PlusTestHooks.register("extra.g63", o -> easeSelected(AnimToolSupport.anim(o), AnimToolSupport.str(o, "preset", "inout"), AnimToolSupport.bool(o, "apply", true)).toJs());
		com.tom.cpm.blockbench.PlusTestHooks.register("extra.f4", o -> blink(AnimToolSupport.anim(o), partsOf(o), AnimToolSupport.bool(o, "hide", false), AnimToolSupport.num(o, "every", 3), AnimToolSupport.num(o, "dur", 0.15), AnimToolSupport.bool(o, "apply", true)).toJs());
		com.tom.cpm.blockbench.PlusTestHooks.register("extra.e2", o -> {
			List<String> w = new ArrayList<>();
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("text", commandSheet(w));
			r.set("warnings", w.toArray(new Object[0]));
			return r;
		});
		com.tom.cpm.blockbench.PlusTestHooks.register("extra.g3", o -> {
			int[] c = ghostColours(AnimToolSupport.bool(o, "black", false), AnimToolSupport.bool(o, "apply", false));
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("hidden", (double) c[0]);
			r.set("black", (double) c[1]);
			return r;
		});
	}

	// ---------------- G3: clean ghost colours ----------------

	private static boolean allBlack(List<KeyData> keys) {
		for (KeyData k : keys) {
			if (k.molang)return false;
			for (double[] v : k.v)for (double x : v)if (Math.abs(x) > 1e-6)return false;
		}
		return true;
	}

	/** counts[0] = channels on parts whose colour never reaches the game, counts[1] = all-black channels on coloured parts */
	public static int[] ghostColours(boolean includeBlack, boolean apply) {
		int[] counts = new int[2];
		List<Animation> touched = new ArrayList<>();
		List<BoneAnimator> clear = new ArrayList<>();
		for (Animation a : AnimEdit.all()) {
			boolean any = false;
			for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
				List<KeyData> keys = AnimEdit.readKeys(ba, KeyMath.COLOR);
				if (keys.isEmpty())continue;
				Group g = AnimEdit.groupOf(ba);
				if (g != null && !BlockbenchExport.isColourTarget(g)) {
					counts[0]++;
					clear.add(ba);
					any = true;
				} else if (allBlack(keys)) {
					counts[1]++;
					if (includeBlack) {
						clear.add(ba);
						any = true;
					}
				}
			}
			if (any)touched.add(a);
		}
		if (apply && !clear.isEmpty()) {
			PlusUndo.editAnimations(touched, t("cpmplus.extra.g3.undo"), () -> {
				for (BoneAnimator ba : clear)AnimEdit.clearChannel(ba, KeyMath.COLOR);
			});
			AnimToolSupport.refreshPreview();
		}
		return counts;
	}

	public static void openGhostColours() {
		int[] c = ghostColours(false, false);
		if (c[0] == 0 && c[1] == 0) {
			PlusDialogs.quick(t("cpmplus.extra.g3.none"), 3000);
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.extra.g3.intro", c[0])));
		if (c[1] > 0)rows.add(FormRow.checkbox("black", "cpmplus.extra.g3.black", false).description("cpmplus.extra.g3.black.desc"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.extra.g3.ok";
		PlusDialogs.form("cpmplus_extra_ghost", "cpmplus.extra.g3.title", rows, fr -> {
			boolean black = c[1] > 0 && fr.getBool("black");
			int[] done = ghostColours(black, true);
			PlusDialogs.done(t("cpmplus.extra.g3.done", done[0] + (black ? done[1] : 0)));
		}, fo);
	}
}
