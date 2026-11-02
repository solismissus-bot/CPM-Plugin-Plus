package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W2-ANIMCORE): Blockbench side of the animation editing core.
 * <ul>
 * <li>Keyframe &lt;-&gt; {@link KeyData} adapters ({@link #readKeys}, {@link #writeKeys} replace | merge), Blockbench
 * interpolation ({@link #sample}, {@link #sampler}).</li>
 * <li>Format limits (hard, Convention 10/13): {@link #predictFrames} (the exporter's grid rule: &lt;= 255 frames,
 * &lt;= 32.767 s), {@link #allowedSnappings}, {@link #nextPriority} (never &gt; 127), {@link #clampByte}.</li>
 * <li>The outliner as a {@link PartPaths.Tree} ({@link #OUTLINER}, {@link #pathOf}, {@link #findByPath},
 * {@link #pairOf}).</li>
 * <li>The 'flip_node_name' listener: Blockbench's own mirror tools also understand direita/esquerda (CPM projects,
 * X axis only).</li>
 * </ul>
 * Tools never rely on Animation.selected implicitly and restore Timeline.time after sampling.
 */
public final class AnimEdit {
	private AnimEdit() {
	}

	// ====================================================================== outliner tree

	/** Blockbench's outliner: nodes are Group / Cube (any outliner node with a name) */
	public static final PartPaths.Tree<Object> OUTLINER = new PartPaths.Tree<Object>() {
		@Override
		public String name(Object node) {
			Object n = Js.asPropertyMap(node).get("name");
			return n != null ? String.valueOf(n) : "";
		}

		@Override
		public Object parent(Object node) {
			Object p = Js.asPropertyMap(node).get("parent");
			if (p == null || "string".equals(Js.typeof(p)) || !Js.isTruthy(p))return null;
			return p;
		}

		@Override
		public List<Object> children(Object node) {
			Object c = Js.asPropertyMap(node).get("children");
			List<Object> out = new ArrayList<>();
			if (c != null && JsArray.isArray(c)) {
				JsArray<Object> a = Js.uncheckedCast(c);
				for (int i = 0; i < a.length; i++)out.add(a.getAt(i));
			}
			return out;
		}

		@Override
		public List<Object> roots() {
			List<Object> out = new ArrayList<>();
			Object r = outlinerRoot();
			if (r != null && JsArray.isArray(r)) {
				JsArray<Object> a = Js.uncheckedCast(r);
				for (int i = 0; i < a.length; i++)out.add(a.getAt(i));
			}
			return out;
		}
	};

	private static native Object outlinerRoot() /*-{
		return $wnd.Outliner ? $wnd.Outliner.root : null;
	}-*/;

	/** full outliner path of a group / cube: 'body/asa_esq/pena[2]' */
	public static String pathOf(Object node) {
		return PartPaths.pathOf(OUTLINER, node);
	}

	/** group / cube at a full path, or null */
	public static Object findByPath(String path) {
		return PartPaths.findByPath(OUTLINER, path);
	}

	/** group at a full path (null when missing or not a group) */
	public static Group groupByPath(String path) {
		Object o = findByPath(path);
		return o instanceof Group ? (Group) o : null;
	}

	/** partner group on the other side (groups only) */
	public static PartPairing.Pair<Object> pairOf(Group g) {
		return PartPairing.pair(OUTLINER, g, n -> n instanceof Group);
	}

	public static boolean isGroup(Object o) {
		return o instanceof Group;
	}

	/** vanilla root part (head, body, left_arm ... and their '_dup' copies) */
	public static boolean isRoot(Group g) {
		return g != null && g.parent == Group.ROOT;
	}

	/** true when a is an ancestor of b or b of a */
	public static boolean related(Group a, Group b) {
		if (a == null || b == null)return false;
		return isAncestor(a, b) || isAncestor(b, a);
	}

	private static boolean isAncestor(Group anc, Group node) {
		Object p = OUTLINER.parent(node);
		int guard = 0;
		while (p != null && guard++ < 1000) {
			if (p == anc)return true;
			p = OUTLINER.parent(p);
		}
		return false;
	}

	// ====================================================================== animators

	/** every bone animator of an animation (Object.values(anim.animators): also the ones not shown in the timeline) */
	public static List<BoneAnimator> boneAnimators(Animation anim) {
		List<BoneAnimator> out = new ArrayList<>();
		if (anim == null || anim.animators == null)return out;
		anim.animators.forEach(k -> {
			GeneralAnimator ga = anim.animators.get(k);
			if (ga instanceof BoneAnimator)out.add((BoneAnimator) ga);
		});
		return out;
	}

	/** the animator of a group, or null when the animation has none (never creates one) */
	public static BoneAnimator animatorOf(Animation anim, Group g) {
		if (anim == null || g == null || anim.animators == null)return null;
		GeneralAnimator ga = anim.animators.get(g.uuid);
		return ga instanceof BoneAnimator ? (BoneAnimator) ga : null;
	}

	/** the group an animator drives (by uuid), or null */
	public static Group groupOf(BoneAnimator a) {
		if (a == null || a.uuid == null || Group.uuids == null)return null;
		Object g = Group.uuids.get(a.uuid);
		return g instanceof Group ? (Group) g : null;
	}

	/** number of keys of one channel */
	public static int count(BoneAnimator a, String channel) {
		JsArrayE<Keyframe> k = a != null ? a.channel(channel) : null;
		return k != null ? k.length : 0;
	}

	public static boolean hasKeys(BoneAnimator a, String channel) {
		return count(a, channel) > 0;
	}

	public static Animation byName(String name) {
		if (Animation.all == null)return null;
		for (Animation a : Animation.all)if (name.equals(a.name))return a;
		return null;
	}

	public static List<Animation> all() {
		List<Animation> out = new ArrayList<>();
		if (Animation.all != null)Collections.addAll(out, Animation.all);
		return out;
	}

	// ====================================================================== keyframe <-> KeyData

	/**
	 * Keys of one channel as KeyData (sorted by time). Numeric strings are numbers; any other string is Molang
	 * (molang = true, values NaN, never rewritten). payload = the keyframe's undo copy (interpolation, bezier,
	 * cpm_visible, colour picker data... are copied from it when written).
	 */
	public static List<KeyData> readKeys(BoneAnimator a, String channel) {
		List<KeyData> out = new ArrayList<>();
		JsArrayE<Keyframe> arr = a != null ? a.channel(channel) : null;
		if (arr == null)return out;
		for (int i = 0; i < arr.length; i++) {
			Keyframe kf = arr.getAt(i);
			out.add(toKeyData(kf, channel));
		}
		Collections.sort(out, (x, y) -> Double.compare(x.time, y.time));
		return out;
	}

	private static KeyData toKeyData(Keyframe kf, String channel) {
		KeyData k = new KeyData();
		k.time = kf.time;
		k.interp = kf.interpolation != null ? kf.interpolation : "linear";
		k.payload = kf.getUndoCopy(true, JsPropertyMap.of());
		int n = kf.data_points != null ? Math.max(1, kf.data_points.length) : 1;
		k.v = new double[n][3];
		double def = KeyMath.SCALE.equals(channel) ? 1 : 0;
		String[] ax = {"x", "y", "z"};
		for (int d = 0; d < n; d++) {
			JsPropertyMap<Object> dp = kf.data_points != null && d < kf.data_points.length ? Js.asPropertyMap(kf.data_points.getAt(d)) : null;
			for (int i = 0; i < 3; i++) {
				Object v = dp != null ? dp.get(ax[i]) : null;
				double x = num(v, def);
				if (Double.isNaN(x))k.molang = true;
				k.v[d][i] = x;
			}
			if (d == 0 && dp != null && KeyMath.VIS.equals(channel)) {
				Object vis = dp.get("cpm_visible");
				k.vis = vis == null ? true : "string".equals(Js.typeof(vis)) ? "true".equals(vis) : Js.isTruthy(vis);
			}
		}
		if ("bezier".equals(k.interp)) {
			k.bLT = vec(kf.bezier_left_time);
			k.bLV = vec(kf.bezier_left_value);
			k.bRT = vec(kf.bezier_right_time);
			k.bRV = vec(kf.bezier_right_value);
		}
		return k;
	}

	private static double[] vec(JsVec3 v) {
		if (v == null)return null;
		Object o = v;
		if (JsArray.isArray(o)) {
			JsArray<Object> a = Js.uncheckedCast(o);
			return new double[] {num(a.getAt(0), 0), num(a.getAt(1), 0), num(a.getAt(2), 0)};
		}
		return new double[] {v.x, v.y, v.z};
	}

	/** number (also numeric strings); NaN for Molang; def for empty / missing */
	static double num(Object v, double def) {
		if (v == null)return def;
		String t = Js.typeof(v);
		if ("number".equals(t)) {
			double d = Js.asDouble(v);
			return Double.isNaN(d) ? def : d;
		}
		if ("boolean".equals(t))return Js.isTruthy(v) ? 1 : 0;
		if ("string".equals(t)) {
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

	/** removes every key of a channel (no undo of its own: call inside PlusUndo) */
	public static int clearChannel(BoneAnimator a, String channel) {
		JsArrayE<Keyframe> arr = a != null ? a.channel(channel) : null;
		if (arr == null)return 0;
		List<Keyframe> l = new ArrayList<>();
		for (int i = 0; i < arr.length; i++)l.add(arr.getAt(i));
		for (Keyframe k : l)k.remove();
		return l.size();
	}

	/** removes the keys of a channel within eps of one of the times */
	public static int removeNear(BoneAnimator a, String channel, Collection<Double> times, double eps) {
		JsArrayE<Keyframe> arr = a != null ? a.channel(channel) : null;
		if (arr == null)return 0;
		List<Keyframe> l = new ArrayList<>();
		for (int i = 0; i < arr.length; i++) {
			Keyframe k = arr.getAt(i);
			for (Double t : times)if (Math.abs(k.time - t) < eps) {
				l.add(k);
				break;
			}
		}
		for (Keyframe k : l)k.remove();
		return l.size();
	}

	/**
	 * Writes keys into a channel. replace = remove every existing key of the channel first; merge = remove only the
	 * keys within 1e-5 of a new key time. Each key is created from its payload (or a fresh key) with time, values
	 * (unless Molang), interpolation, bezier handles and visibility from the KeyData; new uuid. No undo of its own.
	 * @return keys written
	 */
	public static int writeKeys(BoneAnimator dst, String channel, List<KeyData> keys, boolean replace) {
		if (dst == null)return 0;
		ensureChannel(dst, channel);
		if (replace)clearChannel(dst, channel);
		else {
			List<Double> times = new ArrayList<>();
			for (KeyData k : keys)times.add(k.time);
			removeNear(dst, channel, times, KeyMath.EPS);
		}
		int n = 0;
		for (KeyData k : keys) {
			JsPropertyMap<Object> data = keyObject(k, channel);
			Keyframe kf = dst.addKeyframe(data, null);
			if (kf != null)n++;
		}
		return n;
	}

	private static native void ensureChannel(BoneAnimator a, String channel) /*-{
		if (!a[channel]) a[channel] = [];
	}-*/;

	private static JsPropertyMap<Object> keyObject(KeyData k, String channel) {
		JsPropertyMap<Object> data = clone(k.payload);
		data.delete("uuid");
		data.delete("animator");
		data.set("channel", channel);
		data.set("time", k.time);
		data.set("interpolation", k.interp != null ? k.interp : "linear");
		JsArray<Object> dps;
		Object old = data.get("data_points");
		if (old != null && JsArray.isArray(old))dps = Js.uncheckedCast(old);
		else {
			dps = new JsArray<>();
			data.set("data_points", dps);
		}
		if (!k.molang) {
			String[] ax = {"x", "y", "z"};
			for (int d = 0; d < k.v.length; d++) {
				JsPropertyMap<Object> dp;
				if (d < dps.length)dp = Js.uncheckedCast(dps.getAt(d));
				else {
					dp = JsPropertyMap.of();
					dps.push(dp);
				}
				if (KeyMath.isTransform(channel) || KeyMath.COLOR.equals(channel)) {
					for (int i = 0; i < 3; i++)dp.set(ax[i], clean(k.v[d][i]));
				}
			}
			if (dps.length > k.v.length)dps.length = k.v.length;
		}
		if (KeyMath.VIS.equals(channel)) {
			if (dps.length == 0)dps.push(JsPropertyMap.of());
			JsPropertyMap<Object> dp = Js.uncheckedCast(dps.getAt(0));
			dp.set("cpm_visible", k.vis);
		}
		if ("bezier".equals(k.interp)) {
			if (k.bLT != null)data.set("bezier_left_time", arr(k.bLT));
			if (k.bLV != null)data.set("bezier_left_value", arr(k.bLV));
			if (k.bRT != null)data.set("bezier_right_time", arr(k.bRT));
			if (k.bRV != null)data.set("bezier_right_value", arr(k.bRV));
		}
		return data;
	}

	/** no -0, no float noise below 1e-9 */
	private static double clean(double v) {
		if (Math.abs(v) < 1e-9)return 0;
		double r = Math.round(v * 1e6) / 1e6;
		return Math.abs(r - v) < 1e-9 ? r : v;
	}

	private static JsArray<Object> arr(double[] v) {
		JsArray<Object> a = new JsArray<>();
		for (double d : v)a.push(clean(d));
		return a;
	}

	private static native JsPropertyMap<Object> clone(Object o) /*-{
		if (!o) return {};
		return JSON.parse(JSON.stringify(o));
	}-*/;

	// ====================================================================== Blockbench interpolation

	/** channel value at t with Blockbench's own interpolation (Molang evaluated); null when the channel is empty */
	public static double[] sample(BoneAnimator a, String channel, double t) {
		JsArrayE<Keyframe> arr = a != null ? a.channel(channel) : null;
		if (arr == null || arr.length == 0)return null;
		float save = Timeline.time;
		try {
			Timeline.time = (float) t;
			JsVec3 v = Animation.interpolate(arr, (float) t);
			return v == null ? null : new double[] {v.x, v.y, v.z};
		} finally {
			Timeline.time = save;
		}
	}

	/** sampler over the CURRENT keys of a channel (call it before the channel is rewritten) */
	public static KeyMath.Sampler sampler(BoneAnimator a, String channel) {
		double def = KeyMath.SCALE.equals(channel) ? 1 : 0;
		return t -> {
			double[] v = sample(a, channel, t);
			return v != null ? v : new double[] {def, def, def};
		};
	}

	/** visibility at t (the part's own visibility before the first key) */
	public static boolean visAt(BoneAnimator a, double t, boolean def) {
		return Animation.interpolateVisible(a, (float) t, def);
	}

	/** value of a channel at t from a Blockbench channel, or the rest value (0 / scale 1) when the channel is empty */
	public static double[] valueOrRest(BoneAnimator a, String channel, double t) {
		double[] v = a != null ? sample(a, channel, t) : null;
		if (v != null)return v;
		double d = KeyMath.SCALE.equals(channel) ? 1 : 0;
		return new double[] {d, d, d};
	}

	// ====================================================================== format limits

	/** the CPM frame limit per animation (byte) and the duration limit (short, ms) */
	public static final int MAX_FRAMES = 255;
	public static final int MAX_DURATION_MS = 32767;

	/** result of {@link #predictFrames} */
	public static final class Frames {
		public final int frames;
		public final int durationMs;
		public final boolean ok;
		/** how the exporter would write it: 'stored' (original CPM grid), 'grid', 'resample', 'empty' */
		public final String path;

		Frames(int frames, int durationMs, String path) {
			this.frames = frames;
			this.durationMs = durationMs;
			this.path = path;
			this.ok = frames <= MAX_FRAMES && durationMs <= MAX_DURATION_MS && frames >= 1;
		}

		@Override
		public String toString() {
			return frames + " frames, " + durationMs + " ms (" + path + ")";
		}
	}

	/**
	 * How many frames the exporter (v0.7 keyframe-native rule, BlockbenchExport.exportFrames / emitGrid) writes for
	 * this animation when its snapping is {@code snapping} and its length {@code length} (pass the animation's own
	 * values to predict the current export). Staged ('p:'/'c:'/'g:'), 'once' and 'hold' main animations are predicted
	 * like the frame list that carries the keys. Restores Timeline.time / Animation.selected.
	 */
	public static Frames predictFrames(Animation anim, int snapping, double length) {
		List<BoneAnimator> anims = new ArrayList<>();
		for (BoneAnimator ba : boneAnimators(anim)) {
			if (!ba.hasTransform())continue;
			Object node = nodeOf(ba.uuid);
			if (node == null)continue;
			if (Js.asPropertyMap(node).get("export") != null && !Js.isTruthy(Js.asPropertyMap(node).get("export")))continue;
			anims.add(ba);
		}
		float len = (float) Math.max(0, length);
		InterpolatorType stored = anim.getCpmIntType();
		int sN = anim.getCpmFrames();
		int sD = anim.getCpmDuration();
		String[] chans = {"position", "rotation", "scale", CPMCodec.COLOR};
		List<Float> times = new ArrayList<>();
		String family = null;
		boolean mixed = false, molang = false, hasVis = false;
		for (BoneAnimator ba : anims) {
			boolean root = isRootNode(nodeOf(ba.uuid));
			for (String ch : chans) {
				JsArrayE<Keyframe> arr = ba.channel(ch);
				if (arr == null || arr.length == 0 || ba.isMuted(ch))continue;
				for (int i = 0; i < arr.length; i++) {
					Keyframe kf = arr.getAt(i);
					times.add(kf.time);
					if (hasMolang(kf))molang = true;
					if (arr.length < 2)continue;
					String fam = familyOf(kf.interpolation);
					if (fam == null)mixed = true;
					else if (family == null)family = fam;
					else if (!family.equals(fam))mixed = true;
				}
			}
			if (ba.hasVisible() && !ba.isMuted(CPMCodec.VISIBILITY) && !root) {
				for (int i = 0; i < ba.visible.length; i++) {
					times.add(ba.visible.getAt(i).time);
					hasVis = true;
				}
			}
		}
		float maxT = 0;
		for (Float t : times)maxT = Math.max(maxT, t);
		if (maxT > len + 1e-4f)len = maxT;
		int durMs = (int) Math.round(len * 1000.0);
		if (sD > 0 && Math.abs(len * 1000.0 - sD) < 1.5)durMs = sD;
		Pair<AnimationType, VanillaPose> type = anim.type != null ? Animation.parseType(anim.type) : Pair.of(AnimationType.CUSTOM_POSE, null);
		boolean staged = isStaged(anim);
		int defDur = sD > 0 ? sD : staged ? 50 : 1000;
		if (times.isEmpty())return new Frames(1, durMs > 0 ? durMs : defDur, "empty");
		if (len < 1e-4f)return new Frames(1, defDur, "empty");
		AnimationType tk = type.getKey();
		boolean loop = "loop".equals(anim.loop) && tk.canLoop();
		boolean runtimeLoop = tk == AnimationType.CUSTOM_POSE || tk == AnimationType.LAYER ||
				(tk == AnimationType.POSE && type.getValue() != null && !type.getValue().hasStateGetter()) ||
				(tk == AnimationType.GESTURE && loop);
		boolean dynamic = tk == AnimationType.VALUE_LAYER || (tk == AnimationType.POSE && type.getValue() != null && type.getValue().hasStateGetter());
		// staged animations, 'hold' and 'once' on a type that can't loop are exported as a SETUP stage
		if (staged || "hold".equals(anim.loop) || ("once".equals(anim.loop) && !tk.canLoop())) {
			runtimeLoop = false;
			dynamic = false;
		}
		if (family == null)family = stored != null ? familyOf(stored) : "step";
		int fps = (int) Math.min(500, Math.max(1, snapping));
		float saveTime = Timeline.time;
		Animation saveSel = Animation.selected;
		try {
			Animation.selected = anim;
			if (!mixed && !molang) {
				if (stored != null && sN >= 1 && family.equals(familyOf(stored))) {
					boolean remap = sN > 1 && (stored == InterpolatorType.LINEAR_SINGLE || stored == InterpolatorType.TRIG_SINGLE);
					int m0 = remap ? sN - 1 : sN;
					float h0 = len / m0;
					if (onGrid(times, h0, len)) {
						boolean ok;
						if (sN == 1 || isLoopType(stored))ok = same(sig(anims, 0), sig(anims, len), true);
						else if (stored == InterpolatorType.POLY_SINGLE)ok = same(sig(anims, (m0 - 1) * h0), sig(anims, len), true);
						else if (stored == InterpolatorType.NO_INTERPOLATE)ok = runtimeLoop || same(sig(anims, (m0 - 1) * h0), sig(anims, len), true);
						else ok = true;
						if (ok)return new Frames(sN, Math.max(1, durMs), "stored");
					}
				}
				List<Float> distinct = new ArrayList<>();
				for (Float t : times) {
					boolean found = false;
					for (Float d : distinct)if (Math.abs(d - t) < 1e-4f) {
						found = true;
						break;
					}
					if (!found)distinct.add(t);
				}
				int maxM = Math.min(1000, Math.max((int) Math.ceil(len * fps - 1e-3f), 2 * distinct.size()));
				int m = -1;
				for (int mm = 1; mm <= maxM; mm++)if (onGrid(distinct, len / mm, len)) {
					m = mm;
					break;
				}
				if (m > 0)return grid(anims, len, m, family, stored, runtimeLoop, dynamic, hasVis, fps, "grid");
			}
			int m = Math.max(1, Math.round(len * fps));
			return grid(anims, len, m, "linear", null, runtimeLoop, dynamic, hasVis, fps, "resample");
		} finally {
			Timeline.time = saveTime;
			Animation.selected = saveSel;
		}
	}

	/** the export prediction with the animation's own snapping and length */
	public static Frames predictFrames(Animation anim) {
		return predictFrames(anim, snappingOf(anim), anim.length);
	}

	public static int snappingOf(Animation anim) {
		Object o = Js.asPropertyMap(anim).get("snapping");
		double d = o != null && "number".equals(Js.typeof(o)) ? Js.asDouble(o) : 20;
		if (Double.isNaN(d) || d < 1)d = 20;
		return (int) Math.min(500, Math.round(d));
	}

	/** snapping values (Blockbench allows 10..500) whose export stays within the format limits, ascending */
	public static List<Integer> allowedSnappings(Animation anim) {
		List<Integer> out = new ArrayList<>();
		for (int s : new int[] {10, 12, 15, 20, 24, 25, 30, 40, 48, 50, 60, 100, 120, 200, 240, 500}) {
			if (predictFrames(anim, s, anim.length).ok)out.add(s);
		}
		return out;
	}

	private static Frames grid(List<BoneAnimator> anims, float len, int m, String family, InterpolatorType stored,
			boolean runtimeLoop, boolean dynamic, boolean hasVis, int fps, String path) {
		List<double[]> s = sampleGrid(anims, len, m);
		boolean stepFam = "step".equals(family);
		if (!stepFam) {
			double step = maxRotStep(s);
			if (step > 180.001) {
				int nm = refineGrid(m, m * (int) Math.ceil(step / 170.0));
				if (nm > m) {
					m = nm;
					s = sampleGrid(anims, len, m);
				}
			}
		}
		float h = len / m;
		boolean allEq = true;
		for (int k = 1; k <= m && allEq; k++)allEq = same(s.get(0), s.get(k), false);
		boolean seamless = same(s.get(m), s.get(0), false);
		boolean endHold = same(s.get(m), s.get(m - 1), false);
		double durS = len;
		int n;
		boolean trig = stored == InterpolatorType.TRIG_LOOP || stored == InterpolatorType.TRIG_SINGLE;
		if (allEq) {
			n = 1;
		} else if ("linear".equals(family) || ("catmullrom".equals(family) && trig)) {
			if (runtimeLoop && seamless)n = m;
			else {
				if (hasVis) {
					m = refineGrid(m, (int) Math.ceil(len * fps - 1e-3f) - 1);
					h = len / m;
				}
				n = m + 1;
			}
		} else if ("catmullrom".equals(family)) {
			if (runtimeLoop && seamless)n = m;
			else if (endHold)n = m;
			else if (dynamic) {
				m = refineGrid(m, (int) Math.ceil(len * fps - 1e-3f));
				n = m + 1;
			} else {
				n = m + 1;
				durS = len + h;
			}
		} else {
			if (runtimeLoop || endHold || dynamic)n = m;
			else {
				n = m + 1;
				durS = len + h;
			}
		}
		return new Frames(n, Math.max(1, (int) Math.round(durS * 1000.0)), path);
	}

	private static Object nodeOf(String uuid) {
		if (uuid == null)return null;
		if (Group.uuids != null) {
			Object g = Group.uuids.get(uuid);
			if (g != null)return g;
		}
		if (Cube.all != null)for (int i = 0; i < Cube.all.length; i++)if (uuid.equals(Cube.all.getAt(i).uuid))return Cube.all.getAt(i);
		return null;
	}

	private static boolean isRootNode(Object node) {
		if (!(node instanceof Group))return false;
		Group g = (Group) node;
		return g.parent == Group.ROOT && g.getRootType() != null;
	}

	private static final int SIG = 13;

	private static double[] sig(List<BoneAnimator> anims, float t) {
		Timeline.time = t;
		double[] r = new double[anims.size() * SIG];
		for (int i = 0; i < anims.size(); i++) {
			BoneAnimator a = anims.get(i);
			int o = i * SIG;
			put(r, o, a.isMuted("position") ? null : Animation.interpolate(a.position, t));
			put(r, o + 3, a.isMuted("rotation") ? null : Animation.interpolate(a.rotation, t));
			put(r, o + 6, a.isMuted("scale") ? null : Animation.interpolate(a.scale, t));
			put(r, o + 9, a.isMuted(CPMCodec.COLOR) ? null : Animation.interpolate(a.color, t));
			Object node = nodeOf(a.uuid);
			boolean root = node == null || isRootNode(node);
			boolean hidden = node != null && Js.isTruthy(Js.asPropertyMap(node).get("cpm_hidden"));
			r[o + 12] = root ? 0 : (Animation.interpolateVisible(a, t, !hidden) ? 1 : 0);
		}
		return r;
	}

	private static void put(double[] r, int o, JsVec3 v) {
		if (v == null)r[o] = r[o + 1] = r[o + 2] = Double.NaN;
		else {
			r[o] = v.x;
			r[o + 1] = v.y;
			r[o + 2] = v.z;
		}
	}

	private static boolean same(double[] a, double[] b, boolean modRot) {
		for (int i = 0; i < a.length; i++) {
			double x = a[i], y = b[i];
			boolean nx = Double.isNaN(x), ny = Double.isNaN(y);
			if (nx || ny) {
				if (nx != ny)return false;
				continue;
			}
			double d = x - y;
			int c = i % SIG;
			if (modRot && c >= 3 && c < 6) {
				d = d % 360.0;
				if (d > 180)d -= 360;
				if (d < -180)d += 360;
			}
			if (Math.abs(d) > 1e-3)return false;
		}
		return true;
	}

	private static List<double[]> sampleGrid(List<BoneAnimator> anims, float len, int m) {
		float h = len / m;
		List<double[]> s = new ArrayList<>();
		for (int k = 0; k <= m; k++)s.add(sig(anims, k * h));
		return s;
	}

	private static double maxRotStep(List<double[]> s) {
		double max = 0;
		for (int k = 1; k < s.size(); k++) {
			double[] a = s.get(k - 1), b = s.get(k);
			for (int i = 0; i < a.length; i++) {
				int c = i % SIG;
				if (c >= 3 && c < 6 && !Double.isNaN(a[i]) && !Double.isNaN(b[i]))max = Math.max(max, Math.abs(a[i] - b[i]));
			}
		}
		return max;
	}

	private static int refineGrid(int m, int target) {
		if (target <= m)return m;
		int f = (target + m - 1) / m;
		int r = m * f;
		if (r > 1000)r = Math.max(m, (1000 / m) * m);
		return r;
	}

	private static boolean onGrid(List<Float> times, float h, float len) {
		if (!(h > 0))return false;
		double eps = Math.min(1.5e-3, h * 0.05);
		for (Float t : times) {
			if (t > len + eps)return false;
			double q = t / (double) h;
			if (Math.abs(q - Math.round(q)) * h > eps)return false;
		}
		return true;
	}

	private static String familyOf(String interp) {
		if (interp == null || interp.isEmpty() || interp.equals("linear"))return "linear";
		if (interp.equals("catmullrom"))return "catmullrom";
		if (interp.equals("step"))return "step";
		return null;
	}

	private static String familyOf(InterpolatorType it) {
		switch (it) {
		case LINEAR_LOOP:
		case LINEAR_SINGLE:
			return "linear";
		case NO_INTERPOLATE:
			return "step";
		default:
			return "catmullrom";
		}
	}

	private static boolean isLoopType(InterpolatorType it) {
		return it == InterpolatorType.POLY_LOOP || it == InterpolatorType.LINEAR_LOOP || it == InterpolatorType.TRIG_LOOP;
	}

	/** true when a key holds a Molang expression (a string that is not a number) */
	public static boolean hasMolang(Keyframe kf) {
		if (kf.data_points == null)return false;
		for (int i = 0; i < kf.data_points.length; i++) {
			JsPropertyMap<Object> dp = Js.asPropertyMap(kf.data_points.getAt(i));
			for (String ax : new String[] {"x", "y", "z"}) {
				if (Double.isNaN(num(dp.get(ax), 0)))return true;
			}
		}
		return false;
	}

	/**
	 * Staged animation (exported as a stage of another one): name 'p:...' / 'c:...' / 'g:...' with type 'staged',
	 * or any 'p:' / 'c:' / 'g:' prefixed name.
	 */
	public static boolean isStaged(Animation anim) {
		String n = anim.name != null ? anim.name : "";
		if (n.length() > 2 && n.charAt(1) == ':' && "pcgPCG".indexOf(n.charAt(0)) >= 0)return true;
		String t = anim.type != null ? anim.type.toLowerCase(Locale.ROOT) : "";
		return t.equals("staged") || t.startsWith("setup") || t.startsWith("finish");
	}

	// ====================================================================== priority (byte range)

	/** -128..127 (CPM stores the priority as a byte) */
	public static int clampByte(int v) {
		return v < -128 ? -128 : v > 127 ? 127 : v;
	}

	/** result of {@link #nextPriority}: the value to use, and other animations to renumber first (compression) */
	public static final class PriorityPlan {
		public final boolean ok;
		public final int value;
		/** existing animations whose priority must change first (same relative order); empty when not needed */
		public final Map<Animation, Integer> renumber;
		/** lang key of the reason when !ok */
		public final String messageKey;

		PriorityPlan(boolean ok, int value, Map<Animation, Integer> renumber, String messageKey) {
			this.ok = ok;
			this.value = value;
			this.renumber = renumber;
			this.messageKey = messageKey;
		}

		/** applies the renumbering (inside the caller's undo: include those animations in its undo aspects) */
		public void applyRenumber() {
			for (Map.Entry<Animation, Integer> e : renumber.entrySet())e.getKey().setPriority(e.getValue());
		}

		@Override
		public String toString() {
			return ok ? value + (renumber.isEmpty() ? "" : " (renumber " + renumber.size() + ")") : "refuse";
		}
	}

	/** next priority above every touching animation (see {@link #nextPriority(Collection, int)}) */
	public static PriorityPlan nextPriority(Collection<Animation> touching) {
		return nextPriority(touching, Integer.MIN_VALUE);
	}

	/**
	 * A priority higher than every animation in touching (max + 1, at least atLeast). When that is above 127 (the
	 * byte limit) the existing priorities are COMPRESSED (renumbered downwards keeping their order and ties) so the
	 * new one fits at 127; when 255 distinct values are already in use the plan refuses. Never returns &gt; 127.
	 */
	public static PriorityPlan nextPriority(Collection<Animation> touching, int atLeast) {
		int max = Integer.MIN_VALUE;
		TreeSet<Integer> distinct = new TreeSet<>();
		if (touching != null)for (Animation a : touching) {
			int p = a.getPriority();
			distinct.add(p);
			max = Math.max(max, p);
		}
		int want = max == Integer.MIN_VALUE ? (atLeast == Integer.MIN_VALUE ? 0 : atLeast) : Math.max(max + 1, atLeast == Integer.MIN_VALUE ? max + 1 : atLeast);
		if (want < -128)want = -128;
		if (want <= 127)return new PriorityPlan(true, want, new LinkedHashMap<>(), null);
		// compress: distinct values v_0 < ... < v_{n-1} -> min(v_i, 126 - (n - 1 - i)), the new one gets 127
		int n = distinct.size();
		if (n > 255)return new PriorityPlan(false, 127, new LinkedHashMap<>(), "cpmplus.w2animcore.priority.full");
		Map<Integer, Integer> map = new LinkedHashMap<>();
		int i = 0;
		for (Integer v : distinct) {
			int nv = Math.min(v, 126 - (n - 1 - i));
			if (nv < -128)return new PriorityPlan(false, 127, new LinkedHashMap<>(), "cpmplus.w2animcore.priority.full");
			map.put(v, nv);
			i++;
		}
		Map<Animation, Integer> ren = new LinkedHashMap<>();
		for (Animation a : touching) {
			int p = a.getPriority();
			int nv = map.get(p);
			if (nv != p)ren.put(a, nv);
		}
		return new PriorityPlan(true, 127, ren, null);
	}

	// ====================================================================== names

	/**
	 * '&lt;base&gt; (espelhada)' style names: suffix inserted before the first '#' (gesture ids 'wave#2' -&gt;
	 * 'wave (espelhada)#2'); made unique with 2, 3... before the '#' part.
	 */
	public static String uniqueAnimName(String name, String suffix) {
		String head = name, tail = "";
		int h = name.indexOf('#');
		if (h >= 0) {
			head = name.substring(0, h);
			tail = name.substring(h);
		}
		String base = head + suffix;
		String cand = base + tail;
		int k = 2;
		while (byName(cand) != null && k < 1000)cand = base + (k++) + tail;
		return cand;
	}

	// ====================================================================== undo keeps CPM snapping

	/**
	 * Blockbench's undo / redo / cancelled edit restore an animation through Animation.extend, which clamps snapping
	 * to 10..500; animations imported from CPM keep the CPM grid as snapping (e.g. 4 = 4 frames per second), so a
	 * Ctrl+Z of ANY edit of such an animation silently changed its snapping (and with it the resampling rate of the
	 * export). After every undo save is loaded, the saved snapping is put back when Blockbench clamped it.
	 */
	public static void installSnappingUndoFix() {
		PluginStart.addEventListener("load_undo_save", AnimEdit::restoreClampedSnapping);
	}

	private static native void restoreClampedSnapping(Object data) /*-{
		try {
			var save = data && data.save;
			if (!save || !save.animations || !$wnd.Animator || !$wnd.Animator.animations) return;
			for (var uuid in save.animations) {
				var d = save.animations[uuid];
				if (!d || typeof d.snapping !== 'number' || !(d.snapping >= 1) || d.snapping >= 10) continue;
				var list = $wnd.Animator.animations;
				for (var i = 0; i < list.length; i++) {
					if (list[i] && list[i].uuid === uuid && list[i].snapping !== d.snapping) list[i].snapping = d.snapping;
				}
			}
		} catch (e) {
			console.warn('CPM+: could not restore the animation snapping after undo', e);
		}
	}-*/;

	/** copies the snapping exactly (Animation.extend clamps it to 10..500, CPM grids can be lower) */
	public static void copySnapping(Animation from, Animation to) {
		Object s = Js.asPropertyMap(from).get("snapping");
		if (s != null && "number".equals(Js.typeof(s)))Js.asPropertyMap(to).set("snapping", s);
	}

	// ====================================================================== flip_node_name listener

	private static final String[][] FLIP_PAIRS = {
		{"direita", "esquerda"}, {"Direita", "Esquerda"}, {"DIREITA", "ESQUERDA"},
		{"direito", "esquerdo"}, {"Direito", "Esquerdo"}, {"DIREITO", "ESQUERDO"},
	};

	/**
	 * Blockbench's native mirror tools rename 'right'/'left' through flipNameOnAxis, which dispatches 'flip_node_name'
	 * with its live pairs table. In CPM projects (X axis only) the full pt-BR words are added. Not esq/dir/e/d:
	 * Blockbench matches multi-letter keys as substrings ('direcao' would break). Removed on unload.
	 */
	public static void installFlipNameListener() {
		PluginStart.addEventListener("flip_node_name", AnimEdit::onFlipName);
	}

	private static void onFlipName(Object data) {
		try {
			if (data == null || !CPMCodec.isCPM())return;
			JsPropertyMap<Object> d = Js.asPropertyMap(data);
			Object axis = d.get("axis");
			if (axis == null || num(axis, -1) != 0)return;
			Object pairs = d.get("pairs");
			if (pairs == null || !"object".equals(Js.typeof(pairs)))return;
			JsPropertyMap<Object> p = Js.asPropertyMap(pairs);
			for (String[] fp : FLIP_PAIRS)if (!p.has(fp[0]))p.set(fp[0], fp[1]);
		} catch (Throwable e) {
			// never break Blockbench's mirror tool
		}
	}
}
