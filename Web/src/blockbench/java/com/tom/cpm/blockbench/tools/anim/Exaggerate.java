package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ F17 'Exagerar ou suavizar movimento': scales how much the parts move, 0..300 % around the rest pose (rotation /
 * position v*k, scale 1+(v-1)*k, bezier handle values too: KeyMath.scaleAmp); times, visibility and colour are never
 * touched. Optionally only the keys selected in the timeline. A rotation over 360 degrees is only a warning. 100 % =
 * no change and no undo entry. The dialog previews on the model while it is open and puts every value back before OK /
 * Cancel, so the preview never reaches the undo history or the file. Keys are changed in place (same keyframes, same
 * selection).
 */
public final class Exaggerate {
	private Exaggerate() {
	}

	static final String[] MOTION = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE};

	public static final class Options {
		public Animation anim;
		/** null = every animated part */
		public Collection<Group> parts;
		public double k = 1;
		public Set<String> channels = new LinkedHashSet<>();
		public boolean onlySelectedKeys;

		public Options() {
			for (String c : MOTION)channels.add(c);
		}
	}

	/** one live keyframe and its scaled values */
	private static final class Change {
		final Keyframe kf;
		final KeyData scaled;

		Change(Keyframe kf, KeyData scaled) {
			this.kf = kf;
			this.scaled = scaled;
		}
	}

	private static List<Change> plan(Options o, Result r) {
		List<Change> out = new ArrayList<>();
		Set<Keyframe> sel = o.onlySelectedKeys ? selectedKeys() : null;
		int formulas = 0;
		double maxRot = 0;
		for (BoneAnimator ba : AnimEdit.boneAnimators(o.anim)) {
			Group g = AnimEdit.groupOf(ba);
			if (o.parts != null && (g == null || !o.parts.contains(g)))continue;
			for (String ch : MOTION) {
				if (!o.channels.contains(ch))continue;
				JsArrayE<Keyframe> arr = ba.channel(ch);
				if (arr == null)continue;
				for (int i = 0; i < arr.length; i++) {
					Keyframe kf = arr.getAt(i);
					if (sel != null && !sel.contains(kf))continue;
					KeyData k = read(kf, ch);
					if (k.molang) {
						formulas++;
						continue;
					}
					KeyData s = QuickAnimMath.scale(ch, k, o.k);
					if (s == null)continue;
					if (KeyMath.ROT.equals(ch))maxRot = Math.max(maxRot, QuickAnimMath.maxAbs(Collections.singletonList(s)));
					out.add(new Change(kf, s));
				}
			}
		}
		if (formulas > 0)r.warn("molang", t("cpmplus.w3animtools.warn.formulaSkipped", formulas));
		if (maxRot > 360.0001)r.warn("over360", t("cpmplus.w3animtools.f17.over360", (int) Math.round(maxRot)));
		r.put("keys", (double) out.size()).put("maxRotation", maxRot);
		return out;
	}

	public static Result run(Options o, boolean apply) {
		Result r = new Result();
		if (o.anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (o.parts != null && o.parts.isEmpty())return r.error("noParts", t("cpmplus.w3animtools.err.selectPart"));
		if (o.channels.isEmpty())return r.error("noChannels", t("cpmplus.w3animtools.err.noChannels"));
		if (o.onlySelectedKeys && selectedKeys().isEmpty())return r.error("noSelectedKeys", t("cpmplus.w3animtools.f17.noSelectedKeys"));
		o.k = Math.max(0, Math.min(3, o.k));
		List<Change> changes = plan(o, r);
		if (!apply || Math.abs(o.k - 1) < 1e-9 || changes.isEmpty())return r;// 100 %: nothing changes, no undo entry
		PlusUndo.editAnimations(Collections.singletonList(o.anim), t("cpmplus.w3animtools.f17.undo"), () -> {
			for (Change c : changes)write(c.kf, c.scaled);
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	// ====================================================================== keyframe values (in place)

	static Set<Keyframe> selectedKeys() {
		Set<Keyframe> s = new HashSet<>();
		JsArrayE<Keyframe> sel = Timeline.selected;
		if (sel != null)for (int i = 0; i < sel.length; i++)s.add(sel.getAt(i));
		return s;
	}

	/** values + bezier handle values of a live keyframe (numeric strings are numbers; other strings = Molang) */
	static KeyData read(Keyframe kf, String ch) {
		KeyData k = new KeyData();
		k.time = kf.time;
		k.interp = kf.interpolation != null ? kf.interpolation : "linear";
		double def = KeyMath.SCALE.equals(ch) ? 1 : 0;
		int n = kf.data_points != null ? Math.max(1, kf.data_points.length) : 1;
		k.v = new double[n][3];
		String[] ax = {"x", "y", "z"};
		for (int d = 0; d < n; d++) {
			for (int i = 0; i < 3; i++) {
				double x = kf.data_points != null && d < kf.data_points.length ? num(kf.get(ax[i], d), def) : def;
				if (Double.isNaN(x))k.molang = true;
				k.v[d][i] = x;
			}
		}
		k.bLV = vec(kf, "bezier_left_value");
		k.bRV = vec(kf, "bezier_right_value");
		return k;
	}

	private static double num(Object v, double def) {
		if (v == null)return def;
		String ty = Js.typeof(v);
		if ("number".equals(ty)) {
			double d = Js.asDouble(v);
			return Double.isNaN(d) ? def : d;
		}
		if ("string".equals(ty)) {
			String s = ((String) v).trim();
			if (s.isEmpty())return def;
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				return Double.NaN;
			}
		}
		return def;
	}

	private static double[] vec(Keyframe kf, String field) {
		Object o = Js.asPropertyMap(kf).get(field);
		if (o == null || !JsArray.isArray(o))return null;
		JsArray<Object> a = Js.uncheckedCast(o);
		if (a.length < 3)return null;
		return new double[] {num(a.getAt(0), 0), num(a.getAt(1), 0), num(a.getAt(2), 0)};
	}

	private static double clean(double v) {
		if (Math.abs(v) < 1e-9)return 0;
		double r = Math.round(v * 1e6) / 1e6;
		return Math.abs(r - v) < 1e-9 ? r : v;
	}

	/** writes values (and bezier handle values when the key has them) into the live keyframe */
	static void write(Keyframe kf, KeyData k) {
		String[] ax = {"x", "y", "z"};
		for (int d = 0; d < k.v.length && kf.data_points != null && d < kf.data_points.length; d++) {
			JsPropertyMap<Object> dp = Js.asPropertyMap(kf.data_points.getAt(d));
			for (int i = 0; i < 3; i++)dp.set(ax[i], clean(k.v[d][i]));
		}
		if (k.bLV != null)setVec(kf, "bezier_left_value", clean(k.bLV[0]), clean(k.bLV[1]), clean(k.bLV[2]));
		if (k.bRV != null)setVec(kf, "bezier_right_value", clean(k.bRV[0]), clean(k.bRV[1]), clean(k.bRV[2]));
	}

	private static native void setVec(Object kf, String field, double x, double y, double z) /*-{
		var v = kf[field];
		if (v && typeof v.length === 'number' && v.length >= 3) { v[0] = x; v[1] = y; v[2] = z; }
		else kf[field] = [x, y, z];
	}-*/;

	// ====================================================================== live preview (restored before OK / Cancel)

	/** exact raw values of every motion keyframe of the animation (numbers or strings, as stored) */
	private static final class Snapshot {
		final List<Keyframe> kfs = new ArrayList<>();
		final List<Object> copies = new ArrayList<>();

		Snapshot(Animation anim) {
			for (BoneAnimator ba : AnimEdit.boneAnimators(anim)) {
				for (String ch : MOTION) {
					JsArrayE<Keyframe> arr = ba.channel(ch);
					if (arr == null)continue;
					for (int i = 0; i < arr.length; i++) {
						kfs.add(arr.getAt(i));
						copies.add(rawCopy(arr.getAt(i)));
					}
				}
			}
		}

		void restore() {
			for (int i = 0; i < kfs.size(); i++)rawRestore(kfs.get(i), copies.get(i));
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

	private static Options fromForm(Animation anim, FormResult fr, List<Group> selGroups) {
		Options o = new Options();
		o.anim = anim;
		o.k = fr.getDouble("k", 100) / 100.0;
		o.channels.clear();
		if (fr.getBool("ch_rot"))o.channels.add(KeyMath.ROT);
		if (fr.getBool("ch_pos"))o.channels.add(KeyMath.POS);
		if (fr.getBool("ch_scale"))o.channels.add(KeyMath.SCALE);
		o.parts = "selected".equals(fr.getString("parts")) ? new ArrayList<>(selGroups) : null;
		o.onlySelectedKeys = fr.getBool("onlySel");
		return o;
	}

	private static final String SUMMARY_ID = "cpmplus_w3animtools_f17_summary";

	/** CPM+ &gt; Animação &gt; 'Exagerar ou suavizar movimento...' */
	public static void openDialog() {
		Animation anim = Animation.selected;
		if (anim == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		List<Group> selGroups = AnimToolSupport.selectedGroups();
		boolean hasSel = !selGroups.isEmpty();
		boolean hasKeys = !selectedKeys().isEmpty();
		Snapshot snap = new Snapshot(anim);
		boolean[] previewing = new boolean[1];
		Runnable restore = () -> {
			if (previewing[0]) {
				snap.restore();
				previewing[0] = false;
				AnimToolSupport.refreshPreview();
			}
		};
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.f17.intro", anim.name)));
		rows.add(FormRow.info("intro2", null, t("cpmplus.w3animtools.f17.intro2")));
		rows.add(FormRow.range("k", "cpmplus.w3animtools.f17.k", 100, 0, 300, 5).description("cpmplus.w3animtools.f17.k.desc"));
		rows.add(FormRow.checkbox("ch_rot", "cpmplus.w3animtools.ch.rotation", true));
		rows.add(FormRow.checkbox("ch_pos", "cpmplus.w3animtools.ch.position", true));
		rows.add(FormRow.checkbox("ch_scale", "cpmplus.w3animtools.ch.scale", true));
		rows.add(FormRow.radio("parts", "cpmplus.w3animtools.scope", PlusDialogs.options(
				"all", "cpmplus.w3animtools.scope.all",
				"selected", "cpmplus.w3animtools.scope.selected"), hasSel ? "selected" : "all"));
		if (hasKeys)rows.add(FormRow.checkbox("onlySel", "cpmplus.w3animtools.f17.onlySel", false).description("cpmplus.w3animtools.f17.onlySel.desc"));
		rows.add(FormRow.checkbox("preview", "cpmplus.w3animtools.f17.preview", true));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w3animtools.f17.ok";
		fo.onCancel = restore;
		fo.onChange = fr -> {
			restore.run();
			Options o = fromForm(anim, fr, selGroups);
			Result r = new Result();
			boolean valid = !o.channels.isEmpty() && (o.parts == null || !o.parts.isEmpty());
			List<Change> ch = valid ? plan(o, r) : new ArrayList<>();
			if (fr.getBool("preview") && Math.abs(o.k - 1) > 1e-9 && !ch.isEmpty()) {
				previewing[0] = true;
				for (Change c : ch)write(c.kf, c.scaled);
				AnimToolSupport.refreshPreview();
			}
			setSummary(r, ch.size());
		};
		PlusDialogs.form("cpmplus_w3animtools_exaggerate", "cpmplus.w3animtools.f17.title", rows, fr -> {
			restore.run();// the preview never reaches the undo history
			Options o = fromForm(anim, fr, selGroups);
			Result res = run(o, true);
			if (!res.ok) {
				PlusDialogs.block(res.errorText());
				return;
			}
			if (!res.changed)PlusDialogs.quick(t("cpmplus.w3animtools.f17.nothing"), 3000);
			else PlusDialogs.done(t("cpmplus.w3animtools.f17.done", ((Double) res.extra.get("keys")).intValue(), Math.round(o.k * 100) + "%"));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	private static void setSummary(Result r, int n) {
		Element el = DomGlobal.document.getElementById(SUMMARY_ID);
		if (el == null)return;
		StringBuilder sb = new StringBuilder(t("cpmplus.w3animtools.f17.summary", n));
		for (String w : r.warnings)sb.append(" ").append(w);
		el.textContent = sb.toString();
	}

	// ====================================================================== QA hook

	/** f17({anim, parts: 'all' | 'selected' | [paths], k, channels: [..], onlySelected, apply}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Options o = new Options();
		o.anim = AnimToolSupport.anim(opts);
		o.k = AnimToolSupport.num(opts, "k", 1);
		Object ch = opts.get("channels");
		if (ch != null) {
			o.channels.clear();
			for (String c : AnimToolSupport.list(opts, "channels"))o.channels.add(AnimToolSupport.channelName(c));
			o.channels.retainAll(java.util.Arrays.asList(MOTION));
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
		o.onlySelectedKeys = AnimToolSupport.bool(opts, "onlySelected", false);
		return run(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
	}
}
