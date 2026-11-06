package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.ugwt.client.JsArrayE;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Ajuste relativo': adds the SAME change to every key of the chosen parts (rotation and position add, size
 * multiplies), so the whole pose shifts while the motion stays the same ("lower the tail 10 degrees in the whole
 * walk"). Scope: the whole animation, after / before the playhead or a time range, with a soft edge (falloff, smooth
 * ramp) outside it. Works on the current animation or on every animation that animates those parts, in ONE undo
 * entry. Keys with formulas are skipped; channels without keys are left alone (a constant offset there would change
 * what a non-additive animation does). The dialog previews live and restores everything before OK / Cancel.
 */
public final class RelativeAdjust {
	private RelativeAdjust() {
	}

	public enum Scope {ALL, AFTER, BEFORE, RANGE}

	static final String PREF = "cpmplus.reladj.";

	public static final class Options {
		public List<Animation> anims = new ArrayList<>();
		/** null = every part animated in the animations */
		public Collection<Group> parts;
		public boolean children = true;
		public double[] rot = new double[3], pos = new double[3];
		/** uniform size, percent (100 = unchanged) */
		public double scale = 100;
		public Scope scope = Scope.ALL;
		public double from, to;
		/** seconds of smooth fade outside the range */
		public double falloff;

		boolean hasRot() {
			return rot[0] != 0 || rot[1] != 0 || rot[2] != 0;
		}

		boolean hasPos() {
			return pos[0] != 0 || pos[1] != 0 || pos[2] != 0;
		}

		boolean hasScale() {
			return Math.abs(scale - 100) > 1e-9;
		}
	}

	/** how much of the change a key at time t gets: 1 inside the scope, a smooth ramp over the falloff, 0 beyond */
	static double weight(Options o, double t) {
		double a, b;
		switch (o.scope) {
		case AFTER:
			a = o.from;
			b = Double.MAX_VALUE;
			break;
		case BEFORE:
			a = -Double.MAX_VALUE;
			b = o.to;
			break;
		case RANGE:
			a = Math.min(o.from, o.to);
			b = Math.max(o.from, o.to);
			break;
		default:
			return 1;
		}
		if (t >= a - 1e-6 && t <= b + 1e-6)return 1;
		double d = t < a ? a - t : t - b;
		if (o.falloff <= 1e-6 || d >= o.falloff)return 0;
		double u = 1 - d / o.falloff;
		return u * u * (3 - 2 * u);
	}

	static final class Plan {
		final Keyframe kf;
		final String ch;
		final double w;

		Plan(Keyframe kf, String ch, double w) {
			this.kf = kf;
			this.ch = ch;
			this.w = w;
		}
	}

	static Set<Group> scope(Options o, Animation anim) {
		Set<Group> out = new LinkedHashSet<>();
		if (o.parts == null) {
			for (BoneAnimator ba : AnimEdit.boneAnimators(anim)) {
				Group g = AnimEdit.groupOf(ba);
				if (g != null)out.add(g);
			}
		} else for (Group g : o.parts) {
			out.add(g);
			if (o.children)out.addAll(AnimxUtil.descendants(g));
		}
		return out;
	}

	static List<Plan> plan(Options o, Result r, Set<Animation> touchedOut) {
		List<Plan> out = new ArrayList<>();
		int noKeys = 0, formulas = 0;
		Set<Group> parts = new LinkedHashSet<>();
		for (Animation anim : o.anims) {
			for (Group g : scope(o, anim)) {
				BoneAnimator ba = AnimEdit.animatorOf(anim, g);
				for (String ch : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE}) {
					boolean want = KeyMath.ROT.equals(ch) ? o.hasRot() : KeyMath.POS.equals(ch) ? o.hasPos() : o.hasScale();
					if (!want)continue;
					JsArrayE<Keyframe> arr = ba != null ? ba.channel(ch) : null;
					if (arr == null || arr.length == 0) {
						if (o.parts != null)noKeys++;
						continue;
					}
					for (int i = 0; i < arr.length; i++) {
						Keyframe kf = arr.getAt(i);
						if (AnimEdit.hasMolang(kf)) {
							formulas++;
							continue;
						}
						double w = weight(o, kf.time);
						if (w <= 1e-9)continue;
						out.add(new Plan(kf, ch, w));
						parts.add(g);
						if (touchedOut != null)touchedOut.add(anim);
					}
				}
			}
		}
		if (noKeys > 0)r.warn("noKeys", t("cpmplus.w3animtools.reladj.warn.noKeys", noKeys));
		if (formulas > 0)r.warn("molang", t("cpmplus.w3animtools.warn.formulaSkipped", formulas));
		r.put("keys", (double) out.size()).put("parts", (double) parts.size());
		return out;
	}

	static void applyPlans(List<Plan> plans, Options o, Tween.Live live) {
		for (Plan p : plans) {
			if (live != null)live.remember(p.kf);
			int n = Math.max(1, AnimxUtil.dataPoints(p.kf));
			KeyData k = new KeyData();
			k.v = new double[n][3];
			for (int d = 0; d < n; d++) {
				for (int a = 0; a < 3; a++) {
					double v = AnimxUtil.value(p.kf, d, a);
					if (Double.isNaN(v))v = KeyMath.SCALE.equals(p.ch) ? 1 : 0;
					if (KeyMath.ROT.equals(p.ch))v += o.rot[a] * p.w;
					else if (KeyMath.POS.equals(p.ch))v += o.pos[a] * p.w;
					else v *= 1 + (o.scale / 100 - 1) * p.w;
					k.v[d][a] = v;
				}
			}
			Exaggerate.write(p.kf, k);
		}
	}

	public static Result run(Options o, boolean apply) {
		Result r = new Result();
		if (o.anims.isEmpty())return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (o.parts != null && o.parts.isEmpty())return r.error("noParts", t("cpmplus.w3animtools.err.selectPart"));
		if (!o.hasRot() && !o.hasPos() && !o.hasScale())return r.error("noChange", t("cpmplus.w3animtools.reladj.err.noChange"));
		Set<Animation> touched = new LinkedHashSet<>();
		List<Plan> plans = plan(o, r, touched);
		r.put("anims", (double) touched.size());
		if (!apply || plans.isEmpty())return r;
		PlusUndo.editAnimations(new ArrayList<>(touched), t("cpmplus.w3animtools.reladj.undo"), () -> applyPlans(plans, o, null));
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** animations (besides the current one) that have keys on one of these parts */
	static List<Animation> animsWith(Collection<Group> parts, boolean children) {
		List<Animation> out = new ArrayList<>();
		Set<Group> all = new LinkedHashSet<>();
		for (Group g : parts) {
			all.add(g);
			if (children)all.addAll(AnimxUtil.descendants(g));
		}
		for (Animation a : AnimEdit.all()) {
			for (Group g : all) {
				BoneAnimator ba = AnimEdit.animatorOf(a, g);
				if (ba != null && ba.hasTransform()) {
					out.add(a);
					break;
				}
			}
		}
		return out;
	}

	// ====================================================================== dialog

	private static final String SUMMARY_ID = "cpmplus_w3animtools_reladj_summary";

	private static Options fromForm(Animation anim, FormResult fr, List<Group> sel) {
		Options o = new Options();
		o.parts = sel.isEmpty() ? null : new ArrayList<>(sel);
		o.children = fr.getBool("children");
		o.rot = new double[] {fr.getDouble("rx", 0), fr.getDouble("ry", 0), fr.getDouble("rz", 0)};
		o.pos = new double[] {fr.getDouble("px", 0), fr.getDouble("py", 0), fr.getDouble("pz", 0)};
		o.scale = fr.getDouble("scale", 100);
		String sc = fr.getString("scope");
		o.scope = "after".equals(sc) ? Scope.AFTER : "before".equals(sc) ? Scope.BEFORE : "range".equals(sc) ? Scope.RANGE : Scope.ALL;
		double now = Tween.snap(Timeline.time);
		o.from = o.scope == Scope.RANGE ? fr.getDouble("from", 0) : now;
		o.to = o.scope == Scope.RANGE ? fr.getDouble("to", anim.length) : now;
		o.falloff = Math.max(0, fr.getDouble("falloff", 0));
		if ("all".equals(fr.getString("anims")) && !sel.isEmpty())o.anims.addAll(animsWith(sel, o.children));
		if (!o.anims.contains(anim))o.anims.add(0, anim);
		return o;
	}

	/** CPM+ &gt; Animação &gt; 'Ajuste relativo (somar em todas as keys)...' (Ctrl+Alt+Y) */
	public static void openDialog() {
		Animation anim = Animation.selected;
		if (anim == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		List<Group> sel = AnimToolSupport.selectedGroups();
		Tween.Live live = new Tween.Live();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.reladj.intro")));
		rows.add(FormRow.info("who", null, sel.isEmpty() ? t("cpmplus.w3animtools.reladj.parts.all", anim.name) : t("cpmplus.w3animtools.reladj.parts.sel", sel.size())));
		if (!sel.isEmpty())rows.add(FormRow.checkbox("children", "cpmplus.w3animtools.reladj.children", PlusPrefs.getBool(PREF + "children", true)));
		rows.add(FormRow.decimal("rx", "cpmplus.w3animtools.reladj.rx", 0, -360, 360, 0.5));
		rows.add(FormRow.decimal("ry", "cpmplus.w3animtools.reladj.ry", 0, -360, 360, 0.5));
		rows.add(FormRow.decimal("rz", "cpmplus.w3animtools.reladj.rz", 0, -360, 360, 0.5));
		rows.add(FormRow.decimal("px", "cpmplus.w3animtools.reladj.px", 0, -48, 48, 0.25));
		rows.add(FormRow.decimal("py", "cpmplus.w3animtools.reladj.py", 0, -48, 48, 0.25));
		rows.add(FormRow.decimal("pz", "cpmplus.w3animtools.reladj.pz", 0, -48, 48, 0.25));
		rows.add(FormRow.decimal("scale", "cpmplus.w3animtools.reladj.scale", 100, 1, 1000, 1));
		rows.add(FormRow.radio("scope", "cpmplus.w3animtools.reladj.scope", PlusDialogs.options(
				"all", "cpmplus.w3animtools.reladj.scope.all",
				"after", "cpmplus.w3animtools.reladj.scope.after",
				"before", "cpmplus.w3animtools.reladj.scope.before",
				"range", "cpmplus.w3animtools.reladj.scope.range"), PlusPrefs.get(PREF + "scope", "all")));
		rows.add(FormRow.decimal("from", "cpmplus.w3animtools.reladj.from", 0, 0, 1000, 0.05).when(fr -> "range".equals(fr.getString("scope"))));
		rows.add(FormRow.decimal("to", "cpmplus.w3animtools.reladj.to", anim.length, 0, 1000, 0.05).when(fr -> "range".equals(fr.getString("scope"))));
		rows.add(FormRow.decimal("falloff", "cpmplus.w3animtools.reladj.falloff", PlusPrefs.getNum(PREF + "falloff", 0), 0, 10, 0.05)
				.description("cpmplus.w3animtools.reladj.falloff.desc").when(fr -> !"all".equals(fr.getString("scope"))));
		if (!sel.isEmpty()) {
			int others = animsWith(sel, true).size();
			rows.add(FormRow.radio("anims", "cpmplus.w3animtools.reladj.anims", PlusDialogs.options(
					"this", t("cpmplus.w3animtools.reladj.anims.this", anim.name),
					"all", t("cpmplus.w3animtools.reladj.anims.all", others)), "this"));
		}
		rows.add(FormRow.checkbox("preview", "cpmplus.w3animtools.reladj.preview", true));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		FormOpts fo = new FormOpts();
		fo.width = 560;
		fo.confirmKey = "cpmplus.w3animtools.reladj.ok";
		fo.onCancel = live::restore;
		fo.onChange = fr -> {
			live.restore();
			Options o = fromForm(anim, fr, sel);
			Result r = new Result();
			List<Plan> plans = (o.hasRot() || o.hasPos() || o.hasScale()) ? plan(o, r, null) : new ArrayList<>();
			if (fr.getBool("preview") && !plans.isEmpty()) {
				applyPlans(plans, o, live);
				AnimToolSupport.refreshPreview();
			}
			setSummary(r, plans.size());
		};
		PlusDialogs.form("cpmplus_w3animtools_reladj", "cpmplus.w3animtools.reladj.title", rows, fr -> {
			live.restore();
			Options o = fromForm(anim, fr, sel);
			if (!sel.isEmpty())PlusPrefs.set(PREF + "children", o.children);
			PlusPrefs.set(PREF + "scope", fr.getString("scope"));
			PlusPrefs.set(PREF + "falloff", o.falloff);
			Result res = run(o, true);
			if (!res.ok) {
				PlusDialogs.block(res.errorText());
				return;
			}
			if (!res.changed)PlusDialogs.quick(t("cpmplus.w3animtools.reladj.nothing"), 4000);
			else PlusDialogs.done(t("cpmplus.w3animtools.reladj.done", ((Double) res.extra.get("keys")).intValue(), ((Double) res.extra.get("anims")).intValue()));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	private static void setSummary(Result r, int n) {
		Element el = DomGlobal.document.getElementById(SUMMARY_ID);
		if (el == null)return;
		StringBuilder sb = new StringBuilder(t("cpmplus.w3animtools.reladj.summary", n));
		for (String w : r.warnings)sb.append(" ").append(w);
		el.textContent = sb.toString();
	}

	// ====================================================================== QA hook

	/**
	 * relAdjust({anim, anims: 'current' | 'all' | [names], parts: 'all' | [paths], children, rot: [x,y,z], pos: [x,y,z],
	 * scale, scope: all | after | before | range, from, to, falloff, apply, previewTest})
	 */
	public static Object hook(JsPropertyMap<Object> opts) {
		Options o = new Options();
		Animation cur = AnimToolSupport.anim(opts);
		o.children = AnimToolSupport.bool(opts, "children", true);
		Object p = opts.get("parts");
		if (p != null && !"all".equals(p)) {
			List<Group> l = new ArrayList<>();
			for (String s : AnimToolSupport.list(opts, "parts")) {
				Group g = AnimToolSupport.group(s);
				if (g != null)l.add(g);
			}
			o.parts = l;
		}
		Object an = opts.get("anims");
		if ("all".equals(an) && o.parts != null)o.anims.addAll(animsWith(o.parts, o.children));
		else if (an != null && !"current".equals(an)) {
			for (String s : AnimToolSupport.list(opts, "anims")) {
				Animation a = AnimEdit.byName(s);
				if (a != null)o.anims.add(a);
			}
		}
		if (o.anims.isEmpty() && cur != null)o.anims.add(cur);
		o.rot = vec3(opts, "rot");
		o.pos = vec3(opts, "pos");
		o.scale = AnimToolSupport.num(opts, "scale", 100);
		String sc = AnimToolSupport.str(opts, "scope", "all");
		o.scope = "after".equals(sc) ? Scope.AFTER : "before".equals(sc) ? Scope.BEFORE : "range".equals(sc) ? Scope.RANGE : Scope.ALL;
		o.from = AnimToolSupport.num(opts, "from", Timeline.time);
		o.to = AnimToolSupport.num(opts, "to", Timeline.time);
		o.falloff = AnimToolSupport.num(opts, "falloff", 0);
		if (AnimToolSupport.bool(opts, "previewTest", false)) {
			Result r = new Result();
			Tween.Live live = new Tween.Live();
			applyPlans(plan(o, r, null), o, live);
			r.put("previewActive", live.active());
			live.restore();
			return r.toJs();
		}
		return run(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
	}

	private static double[] vec3(JsPropertyMap<Object> opts, String key) {
		List<String> l = AnimToolSupport.list(opts, key);
		double[] v = new double[3];
		for (int i = 0; i < 3 && i < l.size(); i++) {
			try {
				v[i] = Double.parseDouble(l.get(i));
			} catch (NumberFormatException e) {
				v[i] = 0;
			}
		}
		return v;
	}
}
