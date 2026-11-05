package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
 * CPM+ 'Pose intermediária (tween)': at the playhead, for the chosen parts (the selected ones, optionally with
 * everything inside them; nothing selected = every animated part), creates or adjusts one key per channel
 * (rotation / position / scale, never CPM colour or visibility):
 * <ul>
 * <li>Entre a anterior e a próxima: prev + (next - prev) * (0.5 + amount / 200): -100 = the previous key's pose, 0 =
 * halfway, +100 = the next key's pose, beyond +-100 = overshoot. In a looping animation the neighbours wrap around
 * the loop. A channel without a key before or after (non-looping) is left alone.</li>
 * <li>Puxar pro repouso: now + (rest - now) * amount / 100: 0 = the pose now, 100 = the rest pose (0 / scale 1),
 * above 100 = past it, negative = away from rest.</li>
 * </ul>
 * Per-axis linear blend of Euler values, exactly how Blockbench and CPM interpolate. A new key takes the interpolation
 * of the key before it (else after it, else linear). Keys with formulas (Molang) are skipped with a warning. The dialog
 * previews on the model and removes the preview before OK / Cancel, so only the final edit is one undo entry.
 */
public final class Tween {
	private Tween() {
	}

	public enum Mode {TWEEN, REST}

	static final String[] MOTION = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE};
	static final String PREF_MODE = "cpmplus.tween.mode", PREF_TWEEN = "cpmplus.tween.amount", PREF_REST = "cpmplus.tween.rest", PREF_CHILDREN = "cpmplus.tween.children";

	public static final class Options {
		public Animation anim;
		public double time;
		/** null = every part animated in the animation */
		public Collection<Group> parts;
		public boolean children = true;
		public Mode mode = Mode.TWEEN;
		/** TWEEN: -150..150 (0 = halfway); REST: -100..150 (100 = rest) */
		public double amount;
		public Set<String> channels = new LinkedHashSet<>(Arrays.asList(MOTION));
	}

	/** one key to create or adjust */
	static final class Plan {
		final BoneAnimator ba;
		final Group g;
		final String ch;
		final double[] v;
		/** the key already at the time (adjusted in place), or null (a new key) */
		final Keyframe at;
		final KeyData nk;

		Plan(BoneAnimator ba, Group g, String ch, double[] v, Keyframe at, KeyData nk) {
			this.ba = ba;
			this.g = g;
			this.ch = ch;
			this.v = v;
			this.at = at;
			this.nk = nk;
		}
	}

	static double snap(double time) {
		return Math.round(Math.max(0, time) * 1e6) / 1e6;
	}

	static List<Group> scope(Options o) {
		Set<Group> out = new LinkedHashSet<>();
		if (o.parts == null) {
			for (BoneAnimator ba : AnimEdit.boneAnimators(o.anim)) {
				Group g = AnimEdit.groupOf(ba);
				if (g != null)out.add(g);
			}
		} else {
			for (Group g : o.parts) {
				out.add(g);
				if (o.children)out.addAll(AnimxUtil.descendants(g));
			}
		}
		return new ArrayList<>(out);
	}

	private static Keyframe keyAt(BoneAnimator ba, String ch, double t) {
		JsArrayE<Keyframe> arr = ba.channel(ch);
		if (arr == null)return null;
		for (int i = 0; i < arr.length; i++) {
			Keyframe kf = arr.getAt(i);
			if (Math.abs(kf.time - t) < KeyMath.EPS)return kf;
		}
		return null;
	}

	static List<Plan> plan(Options o, Result r) {
		List<Plan> out = new ArrayList<>();
		double t = snap(o.time);
		double len = o.anim.length;
		boolean loop = "loop".equals(o.anim.loop) && len > KeyMath.EPS;
		int noNeighbor = 0, formulas = 0;
		Set<Group> parts = new LinkedHashSet<>();
		for (Group g : scope(o)) {
			BoneAnimator ba = AnimEdit.animatorOf(o.anim, g);
			if (ba == null)continue;
			for (String ch : MOTION) {
				if (!o.channels.contains(ch) || !AnimxUtil.hasKeys(ba, ch))continue;
				List<KeyData> keys = AnimEdit.readKeys(ba, ch);
				KeyData atData = null;
				for (KeyData k : keys)if (Math.abs(k.time - t) < KeyMath.EPS)atData = k;
				if (atData != null && atData.molang) {
					formulas++;
					continue;
				}
				double[] v;
				if (o.mode == Mode.TWEEN) {
					KeyData prev = null, next = null;
					for (KeyData k : keys) {
						if (Math.abs(k.time - t) < KeyMath.EPS)continue;
						if (k.time < t)prev = k;
						else if (next == null)next = k;
					}
					if (loop) {
						// the loop wraps: before the first key comes the last one, after the last key the first one
						if (prev == null)for (KeyData k : keys)if (Math.abs(k.time - t) >= KeyMath.EPS)prev = k;
						if (next == null)for (KeyData k : keys)if (Math.abs(k.time - t) >= KeyMath.EPS) {
							next = k;
							break;
						}
					}
					if (prev == null || next == null || prev == next && !loop) {
						noNeighbor++;
						continue;
					}
					if (prev.molang || next.molang) {
						formulas++;
						continue;
					}
					double f = 0.5 + o.amount / 200.0;
					double[] a = prev.v[prev.v.length - 1], b = next.v[0];
					v = new double[] {a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
				} else {
					double[] now = AnimEdit.sample(ba, ch, t);
					if (now == null || Double.isNaN(now[0]) || Double.isNaN(now[1]) || Double.isNaN(now[2]))continue;
					double[] rest = AnimxUtil.rest(ch);
					double k = o.amount / 100.0;
					v = new double[] {now[0] + (rest[0] - now[0]) * k, now[1] + (rest[1] - now[1]) * k, now[2] + (rest[2] - now[2]) * k};
				}
				Keyframe at = atData != null ? keyAt(ba, ch, t) : null;
				out.add(new Plan(ba, g, ch, v, at, at == null ? newKey(keys, t, v) : null));
				parts.add(g);
			}
		}
		if (noNeighbor > 0)r.warn("noNeighbor", t("cpmplus.w3animtools.tween.noNeighbor", noNeighbor));
		if (formulas > 0)r.warn("molang", t("cpmplus.w3animtools.warn.formulaSkipped", formulas));
		r.put("keys", (double) out.size()).put("parts", (double) parts.size()).put("time", t).put("loop", loop);
		return out;
	}

	/** a new key: interpolation of the key before it (else after it, else linear); bezier gets flat handles */
	static KeyData newKey(List<KeyData> keys, double t, double[] v) {
		KeyData before = null, after = null;
		for (KeyData k : keys) {
			if (k.time < t - KeyMath.EPS)before = k;
			else if (k.time > t + KeyMath.EPS && after == null)after = k;
		}
		KeyData like = before != null ? before : after;
		KeyData nk = new KeyData();
		nk.interp = like != null && like.interp != null ? like.interp : "linear";
		if ("step".equals(nk.interp) && before == null)nk.interp = "linear";
		nk.time = t;
		nk.molang = false;
		nk.v = new double[][] {KeyMath.dup(v)};
		if ("bezier".equals(nk.interp)) {
			nk.bLT = new double[] {-0.1, -0.1, -0.1};
			nk.bLV = new double[] {0, 0, 0};
			nk.bRT = new double[] {0.1, 0.1, 0.1};
			nk.bRV = new double[] {0, 0, 0};
		}
		return nk;
	}

	/** the values of a key that is already there (every data point gets the new pose; bezier handles stay) */
	private static void writeInPlace(Keyframe kf, double[] v) {
		int n = kf.data_points != null ? Math.max(1, kf.data_points.length) : 1;
		KeyData k = new KeyData();
		k.v = new double[n][];
		for (int i = 0; i < n; i++)k.v[i] = KeyMath.dup(v);
		Exaggerate.write(kf, k);
	}

	static void applyPlans(List<Plan> plans, double t, Live live) {
		for (Plan p : plans) {
			if (p.at != null) {
				if (live != null)live.remember(p.at);
				writeInPlace(p.at, p.v);
			} else {
				AnimEdit.writeKeys(p.ba, p.ch, Collections.singletonList(p.nk), false);
				if (live != null)live.created(p.ba, p.ch, t);
			}
		}
	}

	public static Result run(Options o, boolean apply) {
		Result r = new Result();
		if (o.anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (o.parts != null && o.parts.isEmpty())return r.error("noParts", t("cpmplus.w3animtools.err.selectPart"));
		if (o.channels.isEmpty())return r.error("noChannels", t("cpmplus.w3animtools.err.noChannels"));
		List<Plan> plans = plan(o, r);
		if (!apply || plans.isEmpty())return r;
		double t = snap(o.time);
		PlusUndo.editAnimations(Collections.singletonList(o.anim), t("cpmplus.w3animtools.tween.undo"), () -> applyPlans(plans, t, null));
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	// ====================================================================== live preview (removed before OK / Cancel)

	static final class Live {
		private final List<Keyframe> edited = new ArrayList<>();
		private final List<Object> raws = new ArrayList<>();
		private final List<BoneAnimator> newIn = new ArrayList<>();
		private final List<String> newCh = new ArrayList<>();
		private final List<Double> newT = new ArrayList<>();

		void remember(Keyframe kf) {
			edited.add(kf);
			raws.add(rawCopy(kf));
		}

		void created(BoneAnimator ba, String ch, double t) {
			newIn.add(ba);
			newCh.add(ch);
			newT.add(t);
		}

		boolean active() {
			return !edited.isEmpty() || !newIn.isEmpty();
		}

		void restore() {
			if (!active())return;
			for (int i = 0; i < newIn.size(); i++)AnimEdit.removeNear(newIn.get(i), newCh.get(i), Collections.singletonList(newT.get(i)), KeyMath.EPS);
			for (int i = edited.size() - 1; i >= 0; i--)rawRestore(edited.get(i), raws.get(i));
			edited.clear();
			raws.clear();
			newIn.clear();
			newCh.clear();
			newT.clear();
			AnimToolSupport.refreshPreview();
		}
	}

	private static native Object rawCopy(Object kf) /*-{
		var c = {dp: [], b: {}};
		for (var i = 0; i < kf.data_points.length; i++) { var p = kf.data_points[i]; c.dp.push([p.x, p.y, p.z]); }
		var f = ['bezier_left_value', 'bezier_right_value'];
		for (var j = 0; j < f.length; j++) if (kf[f[j]] && kf[f[j]].slice) c.b[f[j]] = kf[f[j]].slice();
		return c;
	}-*/;

	private static native void rawRestore(Object kf, Object c) /*-{
		for (var i = 0; i < kf.data_points.length && i < c.dp.length; i++) { var p = kf.data_points[i]; p.x = c.dp[i][0]; p.y = c.dp[i][1]; p.z = c.dp[i][2]; }
		for (var k in c.b) { var v = kf[k]; if (v && v.length >= 3) { v[0] = c.b[k][0]; v[1] = c.b[k][1]; v[2] = c.b[k][2]; } else kf[k] = c.b[k].slice(); }
	}-*/;

	// ====================================================================== dialog

	private static final String SUMMARY_ID = "cpmplus_w3animtools_tween_summary";

	private static Options fromForm(Animation anim, double time, FormResult fr, List<Group> sel) {
		Options o = new Options();
		o.anim = anim;
		o.time = time;
		o.mode = "rest".equals(fr.getString("mode")) ? Mode.REST : Mode.TWEEN;
		o.amount = o.mode == Mode.REST ? fr.getDouble("rest", 50) : fr.getDouble("amount", 0);
		o.channels.clear();
		if (fr.getBool("ch_rot"))o.channels.add(KeyMath.ROT);
		if (fr.getBool("ch_pos"))o.channels.add(KeyMath.POS);
		if (fr.getBool("ch_scale"))o.channels.add(KeyMath.SCALE);
		o.parts = sel.isEmpty() ? null : new ArrayList<>(sel);
		o.children = sel.isEmpty() || fr.getBool("children");
		return o;
	}

	/** CPM+ &gt; Animação &gt; 'Pose intermediária (tween)...' (Ctrl+Alt+B) */
	public static void openDialog() {
		Animation anim = Animation.selected;
		if (anim == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		double time = snap(Timeline.time);
		List<Group> sel = AnimToolSupport.selectedGroups();
		Live live = new Live();
		String mode = PlusPrefs.get(PREF_MODE, "tween");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.tween.intro", PoseColumn.fmt(time), anim.name)));
		rows.add(FormRow.info("who", null, sel.isEmpty() ? t("cpmplus.w3animtools.tween.parts.all") : t("cpmplus.w3animtools.tween.parts.sel", sel.size())));
		rows.add(FormRow.radio("mode", "cpmplus.w3animtools.tween.mode", PlusDialogs.options(
				"tween", "cpmplus.w3animtools.tween.mode.tween",
				"rest", "cpmplus.w3animtools.tween.mode.rest"), "rest".equals(mode) ? "rest" : "tween"));
		rows.add(FormRow.range("amount", "cpmplus.w3animtools.tween.amount", clamp(PlusPrefs.getNum(PREF_TWEEN, 0), -150, 150), -150, 150, 5)
				.description("cpmplus.w3animtools.tween.amount.desc").when(fr -> !"rest".equals(fr.getString("mode"))));
		rows.add(FormRow.range("rest", "cpmplus.w3animtools.tween.rest", clamp(PlusPrefs.getNum(PREF_REST, 50), -100, 150), -100, 150, 5)
				.description("cpmplus.w3animtools.tween.rest.desc").when(fr -> "rest".equals(fr.getString("mode"))));
		rows.add(FormRow.checkbox("ch_rot", "cpmplus.w3animtools.ch.rotation", true));
		rows.add(FormRow.checkbox("ch_pos", "cpmplus.w3animtools.ch.position", true));
		rows.add(FormRow.checkbox("ch_scale", "cpmplus.w3animtools.ch.scale", true));
		if (!sel.isEmpty())rows.add(FormRow.checkbox("children", "cpmplus.w3animtools.tween.children", PlusPrefs.getBool(PREF_CHILDREN, true))
				.description("cpmplus.w3animtools.tween.children.desc"));
		rows.add(FormRow.checkbox("preview", "cpmplus.w3animtools.tween.preview", true));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w3animtools.tween.ok";
		fo.onCancel = live::restore;
		fo.onChange = fr -> {
			live.restore();
			Options o = fromForm(anim, time, fr, sel);
			Result r = new Result();
			List<Plan> plans = o.channels.isEmpty() ? new ArrayList<>() : plan(o, r);
			if (fr.getBool("preview") && !plans.isEmpty()) {
				applyPlans(plans, snap(time), live);
				AnimToolSupport.refreshPreview();
			}
			setSummary(r, plans.size());
		};
		PlusDialogs.form("cpmplus_w3animtools_tween", "cpmplus.w3animtools.tween.title", rows, fr -> {
			live.restore();// the preview never reaches the undo history
			Options o = fromForm(anim, time, fr, sel);
			PlusPrefs.set(PREF_MODE, o.mode == Mode.REST ? "rest" : "tween");
			PlusPrefs.set(o.mode == Mode.REST ? PREF_REST : PREF_TWEEN, o.amount);
			if (!sel.isEmpty())PlusPrefs.set(PREF_CHILDREN, fr.getBool("children"));
			Result res = run(o, true);
			if (!res.ok) {
				PlusDialogs.block(res.errorText());
				return;
			}
			if (!res.changed)PlusDialogs.quick(t("cpmplus.w3animtools.tween.nothing"), 4000);
			else PlusDialogs.done(t("cpmplus.w3animtools.tween.done", ((Double) res.extra.get("keys")).intValue(), PoseColumn.fmt(time)));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	private static double clamp(double v, double lo, double hi) {
		return Double.isNaN(v) ? 0 : Math.max(lo, Math.min(hi, v));
	}

	private static void setSummary(Result r, int n) {
		Element el = DomGlobal.document.getElementById(SUMMARY_ID);
		if (el == null)return;
		StringBuilder sb = new StringBuilder(t("cpmplus.w3animtools.tween.summary", n));
		for (String w : r.warnings)sb.append(" ").append(w);
		el.textContent = sb.toString();
	}

	// ====================================================================== QA hook

	/** tween({anim, time, mode: tween | rest, amount, parts: 'all' | [paths], children, channels, apply, previewTest}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Options o = new Options();
		o.anim = AnimToolSupport.anim(opts);
		o.time = AnimToolSupport.num(opts, "time", Timeline.time);
		o.mode = "rest".equals(AnimToolSupport.str(opts, "mode", "tween")) ? Mode.REST : Mode.TWEEN;
		o.amount = AnimToolSupport.num(opts, "amount", o.mode == Mode.REST ? 50 : 0);
		o.children = AnimToolSupport.bool(opts, "children", true);
		if (opts.get("channels") != null) {
			o.channels.clear();
			for (String c : AnimToolSupport.list(opts, "channels"))o.channels.add(AnimToolSupport.channelName(c));
			o.channels.retainAll(Arrays.asList(MOTION));
		}
		Object p = opts.get("parts");
		if (p != null && !"all".equals(p)) {
			if ("selected".equals(p))o.parts = AnimToolSupport.selectedGroups();
			else {
				List<Group> l = new ArrayList<>();
				for (String s : AnimToolSupport.list(opts, "parts")) {
					Group g = AnimToolSupport.group(s);
					if (g != null)l.add(g);
				}
				o.parts = l;
			}
		}
		if (AnimToolSupport.bool(opts, "previewTest", false) && o.anim != null) {
			// what the dialog preview does: apply, then remove it again
			Result r = new Result();
			Live live = new Live();
			applyPlans(plan(o, r), snap(o.time), live);
			r.put("previewActive", live.active());
			live.restore();
			return r.toJs();
		}
		return run(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
	}
}
