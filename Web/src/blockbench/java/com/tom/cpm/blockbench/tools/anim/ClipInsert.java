package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Inserir clipe (sequenciador)': puts another animation (a clip) into the current one at the playhead, N times,
 * at a speed, optionally reversed. 'Empurrar o resto' (ripple) moves every later key forward by the inserted length;
 * otherwise the clip replaces the keys of its channels in that stretch. Soft edges blend the animation into the clip
 * and back (sampled on the frame grid, smoothstep), so the cut does not pop. One undo entry.
 */
public final class ClipInsert {
	private ClipInsert() {
	}

	public static final class Options {
		public Animation target, clip;
		public double at;
		public int repeats = 1;
		/** percent */
		public double speed = 100;
		public boolean reverse, ripple = true;
		public double blendIn, blendOut;
	}

	static final String[] ALL = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE, KeyMath.VIS, KeyMath.COLOR};
	static final String[] MOTION = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE};

	public static Result run(Options o, boolean apply) {
		Result r = new Result();
		if (o.target == null || o.clip == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (o.target == o.clip)return r.error("same", t("cpmplus.w3animtools.clip.err.same"));
		if (o.clip.length < 1e-4)return r.error("empty", t("cpmplus.w3animtools.clip.err.empty", o.clip.name));
		int reps = Math.max(1, Math.min(50, o.repeats));
		double speed = Math.max(10, Math.min(1000, o.speed)) / 100;
		double clipLen = o.clip.length / speed, total = clipLen * reps;
		double t0 = Tween.snap(o.at);
		double end = t0 + total;
		if (end * 1000 > AnimEdit.MAX_DURATION_MS || o.ripple && (o.target.length + total) * 1000 > AnimEdit.MAX_DURATION_MS)
			r.warn("tooLong", t("cpmplus.w3animtools.batch.warn.tooLong", 1));
		double fps = Math.max(10, Math.min(60, AnimEdit.snappingOf(o.target)));
		double bi = Math.max(0, Math.min(o.blendIn, total / 2)), bo = Math.max(0, Math.min(o.blendOut, total / 2));
		// plan: every channel the clip animates
		Map<String, Group> groups = new LinkedHashMap<>();
		Map<String, List<KeyData>> inserted = new LinkedHashMap<>();
		Map<String, List<KeyData>> blended = new LinkedHashMap<>();
		int keys = 0;
		for (BoneAnimator cb : AnimEdit.boneAnimators(o.clip)) {
			Group g = AnimEdit.groupOf(cb);
			if (g == null)continue;
			BoneAnimator tb = AnimEdit.animatorOf(o.target, g);
			for (String ch : ALL) {
				List<KeyData> src = AnimEdit.readKeys(cb, ch);
				if (src.isEmpty())continue;
				List<KeyData> out = new ArrayList<>();
				for (int rep = 0; rep < reps; rep++) {
					for (KeyData k : src) {
						KeyData n = copy(k);
						double local = o.reverse ? o.clip.length - k.time : k.time;
						n.time = Tween.snap(t0 + rep * clipLen + local / speed);
						if (o.reverse && "bezier".equals(n.interp))n.interp = "linear";
						if (n.bLT != null)for (int i = 0; i < 3; i++)n.bLT[i] /= speed;
						if (n.bRT != null)for (int i = 0; i < 3; i++)n.bRT[i] /= speed;
						out.add(n);
					}
				}
				Collections.sort(out, (a, b) -> Double.compare(a.time, b.time));
				// one key per time: where one repetition ends and the next starts, the start of the next one wins
				for (int i = out.size() - 1; i > 0; i--)if (Math.abs(out.get(i).time - out.get(i - 1).time) < 1e-6)out.remove(i - 1);
				// ripple: the animation's own key at the end (it was at the playhead) continues the motion there
				if (o.ripple && tb != null) {
					boolean later = false;
					for (KeyData k : AnimEdit.readKeys(tb, ch))if (k.time >= t0 - 1e-6)later = true;
					if (later)out.removeIf(k -> k.time >= end - 1e-6);
				}
				// soft edges (motion channels only): orig -> clip at the start, clip -> orig at the end
				boolean motion = KeyMath.ROT.equals(ch) || KeyMath.POS.equals(ch) || KeyMath.SCALE.equals(ch);
				List<KeyData> bl = new ArrayList<>();
				if (motion && (bi > 0 || bo > 0)) {
					if (bi > 0) {
						int n = Math.max(2, (int) Math.ceil(bi * fps));
						for (int i = 0; i <= n; i++) {
							double tt = t0 + bi * i / n;
							double w = smooth(i / (double) n);
							double[] a = origAt(tb, ch, o.ripple ? t0 : tt);
							double[] b = clipAt(cb, ch, o, tt - t0, clipLen, speed);
							bl.add(key(tt, lerp(a, b, w)));
						}
						out.removeIf(k -> k.time > t0 + 1e-6 && k.time < t0 + bi - 1e-6);
					}
					if (bo > 0) {
						int n = Math.max(2, (int) Math.ceil(bo * fps));
						for (int i = 0; i <= n; i++) {
							double tt = end - bo + bo * i / n;
							double w = smooth(i / (double) n);
							double[] a = clipAt(cb, ch, o, tt - t0, clipLen, speed);
							double[] b = origAt(tb, ch, o.ripple ? t0 : tt);
							bl.add(key(tt, lerp(a, b, w)));
						}
						out.removeIf(k -> k.time > end - bo + 1e-6 && k.time < end - 1e-6);
					}
				}
				String id = g.uuid + "|" + ch;
				groups.put(id, g);
				inserted.put(id, out);
				blended.put(id, bl);
				keys += out.size() + bl.size();
			}
		}
		r.put("keys", (double) keys).put("channels", (double) inserted.size()).put("start", t0).put("end", Tween.snap(end)).put("length", (double) clipLen * reps);
		if (inserted.isEmpty())return r.error("noKeys", t("cpmplus.w3animtools.clip.err.empty", o.clip.name));
		if (!apply)return r;
		List<Animation> l = Collections.singletonList(o.target);
		PlusUndo.run(t("cpmplus.w3animtools.clip.undo", o.clip.name), AnimxUtil.track(UndoData.make(o.target), l), () -> AnimxUtil.track(UndoData.make(o.target), l), () -> {
			if (o.ripple) {
				for (BoneAnimator ba : AnimEdit.boneAnimators(o.target))for (String ch : ALL) {
					JsArrayE<Keyframe> arr = ba.channel(ch);
					if (arr == null)continue;
					for (int i = 0; i < arr.length; i++) {
						Keyframe kf = arr.getAt(i);
						if (kf.time >= t0 - 1e-6)kf.time = (float) Tween.snap(kf.time + total);
					}
				}
				o.target.length = (float) Tween.snap(o.target.length + total);
			} else {
				// the clip replaces its channels in [t0, end]
				for (String id : inserted.keySet()) {
					BoneAnimator ba = AnimEdit.animatorOf(o.target, groups.get(id));
					if (ba == null)continue;
					String ch = id.substring(id.indexOf('|') + 1);
					List<Double> drop = new ArrayList<>();
					for (KeyData k : AnimEdit.readKeys(ba, ch))if (k.time >= t0 - 1e-6 && k.time <= end + 1e-6)drop.add(k.time);
					AnimEdit.removeNear(ba, ch, drop, 1e-6);
				}
				if (end > o.target.length)o.target.length = (float) Tween.snap(end);
			}
			for (Map.Entry<String, List<KeyData>> e : inserted.entrySet()) {
				String ch = e.getKey().substring(e.getKey().indexOf('|') + 1);
				BoneAnimator ba = o.target.getBoneAnimator(groups.get(e.getKey()));
				AnimEdit.writeKeys(ba, ch, e.getValue(), false);
				List<KeyData> bl = blended.get(e.getKey());
				if (bl != null && !bl.isEmpty())AnimEdit.writeKeys(ba, ch, bl, false);
			}
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	static double smooth(double u) {
		return u * u * (3 - 2 * u);
	}

	static double[] lerp(double[] a, double[] b, double w) {
		return new double[] {a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w};
	}

	/** the target's value before the insert (rest when it has no keys there) */
	static double[] origAt(BoneAnimator tb, String ch, double t) {
		return AnimEdit.valueOrRest(tb, ch, t);
	}

	/** the clip's value at a time inside the inserted stretch */
	static double[] clipAt(BoneAnimator cb, String ch, Options o, double local, double clipLen, double speed) {
		double inRep = local % clipLen;
		if (local >= clipLen * Math.max(1, o.repeats) - 1e-9)inRep = clipLen;
		double src = inRep * speed;
		if (o.reverse)src = o.clip.length - src;
		return AnimEdit.valueOrRest(cb, ch, Math.max(0, Math.min(o.clip.length, src)));
	}

	static KeyData copy(KeyData k) {
		KeyData n = new KeyData();
		n.time = k.time;
		n.interp = k.interp;
		n.molang = k.molang;
		n.payload = k.payload;
		n.vis = k.vis;
		n.v = new double[k.v.length][];
		for (int i = 0; i < k.v.length; i++)n.v[i] = KeyMath.dup(k.v[i]);
		n.bLT = k.bLT != null ? KeyMath.dup(k.bLT) : null;
		n.bLV = k.bLV != null ? KeyMath.dup(k.bLV) : null;
		n.bRT = k.bRT != null ? KeyMath.dup(k.bRT) : null;
		n.bRV = k.bRV != null ? KeyMath.dup(k.bRV) : null;
		return n;
	}

	static KeyData key(double time, double[] v) {
		KeyData k = new KeyData();
		k.time = Tween.snap(time);
		k.interp = "linear";
		k.molang = false;
		k.v = new double[][] {{v[0], v[1], v[2]}};
		return k;
	}

	// ====================================================================== dialog

	/** CPM+ &gt; Animação &gt; 'Inserir clipe (sequenciador)...' */
	public static void openDialog() {
		Animation cur = Animation.selected;
		if (cur == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		Map<String, String> clips = new LinkedHashMap<>();
		for (Animation a : AnimEdit.all())if (a != cur && a.length > 1e-4)clips.put(a.uuid, a.name);
		if (clips.isEmpty()) {
			PlusDialogs.block(t("cpmplus.w3animtools.clip.err.noClips"));
			return;
		}
		double at = Tween.snap(Timeline.time);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.clip.intro", cur.name, PoseColumn.fmt(at))));
		rows.add(FormRow.select("clip", "cpmplus.w3animtools.clip.clip", clips, clips.keySet().iterator().next()));
		rows.add(FormRow.number("repeats", "cpmplus.w3animtools.clip.repeats", 1, 1, 50));
		rows.add(FormRow.range("speed", "cpmplus.w3animtools.clip.speed", 100, 25, 400, 5));
		rows.add(FormRow.checkbox("reverse", "cpmplus.w3animtools.clip.reverse", false));
		rows.add(FormRow.checkbox("ripple", "cpmplus.w3animtools.clip.ripple", true).description("cpmplus.w3animtools.clip.ripple.desc"));
		rows.add(FormRow.decimal("blendIn", "cpmplus.w3animtools.clip.blendIn", 0.1, 0, 5, 0.05));
		rows.add(FormRow.decimal("blendOut", "cpmplus.w3animtools.clip.blendOut", 0.1, 0, 5, 0.05));
		FormOpts fo = new FormOpts();
		fo.width = 540;
		fo.confirmKey = "cpmplus.w3animtools.clip.ok";
		PlusDialogs.form("cpmplus_w3animtools_clip", "cpmplus.w3animtools.clip.title", rows, fr -> {
			Options o = new Options();
			o.target = cur;
			for (Animation a : AnimEdit.all())if (a.uuid.equals(fr.getString("clip")))o.clip = a;
			o.at = at;
			o.repeats = fr.getInt("repeats", 1);
			o.speed = fr.getDouble("speed", 100);
			o.reverse = fr.getBool("reverse");
			o.ripple = fr.getBool("ripple");
			o.blendIn = fr.getDouble("blendIn", 0);
			o.blendOut = fr.getDouble("blendOut", 0);
			Result r = run(o, true);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				return;
			}
			PlusDialogs.done(t("cpmplus.w3animtools.clip.done", o.clip.name, PoseColumn.fmt((Double) r.extra.get("start")), PoseColumn.fmt((Double) r.extra.get("end"))));
			if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 8000, null);
		}, fo);
	}

	/** clipInsert({anim, clip, at, repeats, speed, reverse, ripple, blendIn, blendOut, apply}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Options o = new Options();
		o.target = AnimToolSupport.anim(opts);
		o.clip = AnimEdit.byName(AnimToolSupport.str(opts, "clip", ""));
		o.at = AnimToolSupport.num(opts, "at", Timeline.time);
		o.repeats = (int) AnimToolSupport.num(opts, "repeats", 1);
		o.speed = AnimToolSupport.num(opts, "speed", 100);
		o.reverse = AnimToolSupport.bool(opts, "reverse", false);
		o.ripple = AnimToolSupport.bool(opts, "ripple", true);
		o.blendIn = AnimToolSupport.num(opts, "blendIn", 0);
		o.blendOut = AnimToolSupport.num(opts, "blendOut", 0);
		return run(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
	}
}
