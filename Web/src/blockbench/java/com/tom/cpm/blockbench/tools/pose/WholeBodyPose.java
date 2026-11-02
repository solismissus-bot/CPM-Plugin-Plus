package com.tom.cpm.blockbench.tools.pose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.PivotMath;
import com.tom.cpm.blockbench.util.PivotMath.BodyTransform;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W7-POSE, G59) "Girar/mover o corpo inteiro…": turns / moves the selected root parts of the open animation
 * around ONE common pivot, so head, arms and legs follow the body (in CPM the vanilla parts are sibling roots and can't
 * be grouped).
 * <p>
 * Math (PivotMath, Blockbench scene space = CPM space mirrored, see there): every root is T(origin + keyPos) *
 * R(groupRotation + keyRotation) (Euler ZYX), the whole-body transform is M' = T(c + offset) * R * T(-c) * M, the new
 * Euler is the solution closest to the previous key (no 360 spins, no flips). All 6 channels of every selected root
 * are written. Linear per-channel interpolation would move the pivots along the chord instead of the arc, so smoothing
 * keys are inserted at 1/snapping and pruned again where the neighbours reproduce them (0.01 px / 0.05 degree).
 * Positions are checked against the CPM file limit (+-48 px) BEFORE anything is written. One undo entry.
 */
public final class WholeBodyPose {
	private WholeBodyPose() {
	}

	private static final double EPS_T = 1e-4;
	private static final double POS_TOL = 0.01, ROT_TOL = 0.05;
	private static final double MAX_POS = 48;
	/** CPM animation format limits: duration (s) and frame count */
	private static final double MAX_LEN = 32.767;
	private static final int MAX_FRAMES = 255;
	/** largest turn (degrees, per axis) between two consecutive written keys */
	private static final double MAX_KEY_TURN = 170;

	/** what to do */
	public static final class Params {
		public double[] rot = new double[3];
		public double[] off = new double[3];
		/** hip | neck | feet | part */
		public String pivot = "hip";
		public String pivotPart;
		/** names of the root groups that follow (null = all) */
		public List<String> roots;
		/** frame | all | ramp */
		public String range = "frame";
		public double time;
		public double from, to;
		public boolean convertNonAdditive;
	}

	/** outcome (also the test hook result) */
	public static final class Result {
		public boolean ok, changed;
		public int rootsChanged, keysWritten, smoothKeys, bezierConverted, molangReplaced;
		public List<String> skipped = new ArrayList<>();
		public List<String> warnings = new ArrayList<>();
		public List<String> errors = new ArrayList<>();
		/** first value over the +-48 px limit: 'root @ time' */
		public String violation;

		JsPropertyMap<Object> toJs() {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", ok);
			m.set("changed", changed);
			m.set("rootsChanged", (double) rootsChanged);
			m.set("keysWritten", (double) keysWritten);
			m.set("smoothKeys", (double) smoothKeys);
			m.set("bezierConverted", (double) bezierConverted);
			m.set("molangReplaced", (double) molangReplaced);
			m.set("skipped", arr(skipped));
			m.set("warnings", arr(warnings));
			m.set("errors", arr(errors));
			m.set("violation", violation);
			return m;
		}
	}

	private static JsArray<String> arr(List<String> l) {
		JsArray<String> a = new JsArray<>();
		for (String s : l)a.push(s);
		return a;
	}

	// ------------------------------------------------------------------ project access

	/** every root group a whole-body move can take: vanilla roots (incl. '_dup', cape, elytra, armour) */
	public static List<Group> rootGroups() {
		List<Group> l = new ArrayList<>();
		if (Group.all == null)return l;
		for (Group g : Group.all) {
			if (g.parent == Group.ROOT && g.getRootType() != null && g.export)l.add(g);
		}
		return l;
	}

	static BoneAnimator animatorOf(Animation a, Group g) {
		if (a == null || a.animators == null)return null;
		GeneralAnimator ga = a.animators.get(g.uuid);
		return ga instanceof BoneAnimator ? (BoneAnimator) ga : null;
	}

	private static double[] vec(JsVec3 v) {
		return v == null ? new double[3] : new double[] {v.x, v.y, v.z};
	}

	/** {worldPivot[3], euler[3]} of a root at time t (Blockbench space) */
	static double[] pose(Animation a, Group g, double t) {
		BoneAnimator ba = animatorOf(a, g);
		double[] p = ba != null && !ba.isMuted("position") ? vec(Animation.interpolate(ba.position, (float) t)) : new double[3];
		double[] r = ba != null && !ba.isMuted("rotation") ? vec(Animation.interpolate(ba.rotation, (float) t)) : new double[3];
		return new double[] {g.origin.x + p[0], g.origin.y + p[1], g.origin.z + p[2], g.rotation.x + r[0], g.rotation.y + r[1], g.rotation.z + r[2]};
	}

	private static List<Double> keyTimes(BoneAnimator ba) {
		List<Double> l = new ArrayList<>();
		if (ba == null)return l;
		for (String ch : new String[] {"position", "rotation"}) {
			JsArrayE<Keyframe> arr = ba.channel(ch);
			if (arr == null)continue;
			for (Keyframe k : arr.array())l.add((double) k.time);
		}
		return l;
	}

	private static void addTime(List<Double> l, double t) {
		for (Double d : l)if (Math.abs(d - t) < EPS_T)return;
		l.add(t);
	}

	private static int snapping(Animation a) {
		return a.snapping > 0 ? a.snapping : 20;
	}

	private static double snap(Animation a, double t) {
		int s = snapping(a);
		return Math.round(t * s) / (double) s;
	}

	/** the pivot of the whole-body move (Blockbench space) */
	static double[] pivot(Animation a, Params p, double t, List<Group> roots) {
		switch (p.pivot) {
		case "feet":
			return new double[] {0, 0, 0};
		case "neck":
			for (Group g : rootGroups())if ("body".equalsIgnoreCase(g.name)) {
				double[] w = pose(a, g, t);
				return new double[] {w[0], w[1], w[2]};
			}
			return new double[] {0, 24, 0};
		case "part":
			for (Group g : rootGroups())if (g.name.equals(p.pivotPart)) {
				double[] w = pose(a, g, t);
				return new double[] {w[0], w[1], w[2]};
			}
			return new double[] {0, 12, 0};
		default:
			return new double[] {0, 12, 0};
		}
	}

	// ------------------------------------------------------------------ plan

	/** one written key time of one root: new world pivot + Euler, and whether it may be pruned */
	private static final class Sample {
		final double t;
		final double s;
		final boolean anchor;
		double[] v;// {px, py, pz, rx, ry, rz} new KEY values (relative to origin / group rotation)
		boolean keep = true;

		Sample(double t, double s, boolean anchor) {
			this.t = t;
			this.s = s;
			this.anchor = anchor;
		}
	}

	private static final class Plan {
		final Map<Group, List<Sample>> perRoot = new LinkedHashMap<>();
		double removeFrom = Double.NaN, removeTo = Double.NaN;
		double newLength = -1;
	}

	private static List<Sample> samples(Animation a, Params p, List<Group> roots, Result res) {
		List<Sample> out = new ArrayList<>();
		int fps = snapping(a);
		double step = 1.0 / fps;
		List<Double> union = new ArrayList<>();
		for (Group g : roots)for (double t : keyTimes(animatorOf(a, g)))addTime(union, t);
		union.sort(Double::compare);
		switch (p.range) {
		case "all": {
			if (union.isEmpty())union.add(snap(a, p.time));
			for (int i = 0; i < union.size(); i++) {
				double t = union.get(i);
				out.add(new Sample(t, 1, true));
				if (i + 1 < union.size()) {
					double nt = union.get(i + 1);
					for (double g = Math.floor(t * fps + 1e-6) + 1; g / fps < nt - EPS_T; g++) {
						if (g / fps > t + EPS_T)out.add(new Sample(g / fps, 1, false));
					}
				}
			}
			break;
		}
		case "ramp": {
			double A = snap(a, Math.min(p.from, p.to)), B = snap(a, Math.max(p.from, p.to));
			if (B - A < step - 1e-9) {
				res.errors.add(I18n.get("cpmplus.w7pose.body.err.ramp"));
				return out;
			}
			int n = (int) Math.round((B - A) * fps);
			for (int k = 0; k <= n; k++) {
				double t = A + k / (double) fps;
				out.add(new Sample(t, k / (double) n, k == 0 || k == n));
			}
			break;
		}
		default: {
			double t0 = snap(a, p.time);
			Double prev = null, next = null;
			for (double u : union) {
				if (u < t0 - EPS_T)prev = u;
				if (u > t0 + EPS_T && next == null)next = u;
			}
			if (prev != null) {
				out.add(new Sample(prev, 0, true));
				for (double g = Math.floor(prev * fps + 1e-6) + 1; g / fps < t0 - EPS_T; g++) {
					if (g / fps > prev + EPS_T)out.add(new Sample(g / fps, (g / fps - prev) / (t0 - prev), false));
				}
			}
			out.add(new Sample(t0, 1, true));
			if (next != null) {
				for (double g = Math.floor(t0 * fps + 1e-6) + 1; g / fps < next - EPS_T; g++) {
					if (g / fps > t0 + EPS_T)out.add(new Sample(g / fps, (next - g / fps) / (next - t0), false));
				}
				out.add(new Sample(next, 0, true));
			}
			break;
		}
		}
		return out;
	}

	private static Plan plan(Animation a, Params p, List<Group> roots, Result res) {
		Plan plan = new Plan();
		List<Sample> base = samples(a, p, roots, res);
		if (base.isEmpty())return plan;
		BodyTransform full = BodyTransform.of(p.rot, pivot(a, p, snap(a, p.time), roots), p.off);
		if ("ramp".equals(p.range)) {
			plan.removeFrom = base.get(0).t;
			plan.removeTo = base.get(base.size() - 1).t;
		}
		double last = base.get(base.size() - 1).t;
		if (last > a.length + 1e-6)plan.newLength = last;
		for (Group g : roots) {
			List<Sample> l = new ArrayList<>();
			double[] prevE = null;
			for (Sample b : base) {
				Sample s = new Sample(b.t, b.s, b.anchor);
				double[] o = pose(a, g, s.t);
				double[] w = new double[] {o[0], o[1], o[2]};
				double[] e = new double[] {o[3], o[4], o[5]};
				double[] n = full.fraction(Math.max(0, Math.min(1, s.s))).applyPart(w, e, prevE != null ? prevE : e);
				prevE = new double[] {n[3], n[4], n[5]};
				s.v = new double[] {n[0] - g.origin.x, n[1] - g.origin.y, n[2] - g.origin.z, n[3] - g.rotation.x, n[4] - g.rotation.y, n[5] - g.rotation.z};
				l.add(s);
			}
			prune(l);
			plan.perRoot.put(g, l);
		}
		return plan;
	}

	/** removes smoothing samples that the kept neighbours reproduce by linear interpolation (all within tolerance) */
	private static void prune(List<Sample> l) {
		int i = 0;
		while (i < l.size() - 1) {
			int j = i + 1;
			// extend the segment i..j as far as every sample in between stays on the line i..j
			while (j + 1 < l.size() && !l.get(j).anchor) {
				int cand = j + 1;
				// two kept keys never turn 180 degrees or more apart (ambiguous direction: it would read as a spin)
				boolean ok = true;
				for (int c = 3; c < 6 && ok; c++)ok = Math.abs(l.get(cand).v[c] - l.get(i).v[c]) < MAX_KEY_TURN;
				for (int k = i + 1; k < cand && ok; k++) {
					ok = PivotMath.collinear(l.get(i).t, l.get(i).v, l.get(k).t, l.get(k).v, l.get(cand).t, l.get(cand).v, POS_TOL, ROT_TOL);
				}
				if (!ok)break;
				j = cand;
			}
			for (int k = i + 1; k < j; k++)l.get(k).keep = false;
			i = j;
		}
	}

	/** the value CPM stores for this key (additive: the key; otherwise the absolute root position) */
	private static double[] exportPos(Group g, double[] v, boolean additive) {
		if (additive)return new double[] {-v[0], -v[1], v[2]};
		double wx = g.origin.x + v[0], wy = g.origin.y + v[1], wz = g.origin.z + v[2];
		return new double[] {-wx, 24 - wy, wz};
	}

	// ------------------------------------------------------------------ apply

	/** computes, checks and (write=true) applies in one undo entry */
	public static Result run(Animation a, Params p, boolean write) {
		Result res = new Result();
		if (a == null) {
			res.errors.add(I18n.get("cpmplus.w7pose.body.err.noAnim"));
			return res;
		}
		List<Group> roots = new ArrayList<>();
		for (Group g : rootGroups()) {
			if (p.roots != null && !p.roots.contains(g.name))continue;
			BoneAnimator ba = animatorOf(a, g);
			if (ba != null && ba.rotation_global) {
				res.skipped.add(g.name);
				continue;
			}
			if (ba != null && ba.scale != null && ba.scale.length > 0)res.warnings.add(I18n.format("cpmplus.w7pose.body.warn.scale", g.name));
			roots.add(g);
		}
		if (!res.skipped.isEmpty())res.warnings.add(I18n.format("cpmplus.w7pose.body.warn.global", String.join(", ", res.skipped)));
		if (roots.isEmpty()) {
			res.errors.add(I18n.get("cpmplus.w7pose.body.err.noRoots"));
			return res;
		}
		if (a.additive && !p.convertNonAdditive)res.warnings.add(I18n.get("cpmplus.w7pose.body.warn.additive"));
		Plan plan = plan(a, p, roots, res);
		if (!res.errors.isEmpty())return res;
		boolean additive = a.additive && !p.convertNonAdditive;
		// format limits of a CPM animation: at most 32.767 s and 255 frames (checked before anything is written)
		double len = Math.max(a.length, plan.newLength);
		if (len > MAX_LEN + 1e-6) {
			res.errors.add(I18n.format("cpmplus.w7pose.body.err.length", fmt(len)));
			return res;
		}
		List<Double> times = new ArrayList<>();
		for (Group g : rootGroups())for (double t : keyTimes(animatorOf(a, g)))addTime(times, t);
		for (List<Sample> l : plan.perRoot.values())for (Sample s : l)if (s.keep)addTime(times, s.t);
		if (times.size() > MAX_FRAMES) {
			res.errors.add(I18n.format("cpmplus.w7pose.body.err.frames", times.size(), MAX_FRAMES));
			return res;
		}
		// format limit: +-48 px for every exported position (checked before anything is written)
		for (Map.Entry<Group, List<Sample>> e : plan.perRoot.entrySet()) {
			for (Sample s : e.getValue()) {
				if (!s.keep)continue;
				double[] ep = exportPos(e.getKey(), s.v, additive);
				for (double c : ep) {
					if (Math.abs(c) > MAX_POS + 1e-6) {
						res.violation = I18n.format("cpmplus.w7pose.body.err.limit", e.getKey().name, fmt(s.t), fmt(Math.abs(c)));
						res.errors.add(res.violation);
						return res;
					}
				}
			}
		}
		for (List<Sample> l : plan.perRoot.values())for (Sample s : l)if (s.keep) {
			res.keysWritten += 2;
			if (!s.anchor)res.smoothKeys += 2;
		}
		res.rootsChanged = plan.perRoot.size();
		res.ok = true;
		if (!write)return res;
		int[] counts = new int[2];
		PlusUndo.editAnimations(Arrays.asList(a), I18n.get("cpmplus.w7pose.body.undo"), () -> {
			if (p.convertNonAdditive)a.additive = false;
			if (plan.newLength > 0)a.length = (float) plan.newLength;
			for (Map.Entry<Group, List<Sample>> e : plan.perRoot.entrySet()) {
				Group g = e.getKey();
				if (!Double.isNaN(plan.removeFrom))removeInside(a, g, plan.removeFrom, plan.removeTo);
				for (Sample s : e.getValue()) {
					if (!s.keep)continue;
					counts[0] += writeKey(a, g, "position", s.t, s.v[0], s.v[1], s.v[2]);
					counts[0] += writeKey(a, g, "rotation", s.t, s.v[3], s.v[4], s.v[5]);
				}
			}
		});
		res.bezierConverted = counts[0] & 0xffff;
		res.molangReplaced = counts[0] >>> 16;
		if (res.bezierConverted > 0)res.warnings.add(I18n.format("cpmplus.w7pose.body.warn.bezier", res.bezierConverted));
		if (res.molangReplaced > 0)res.warnings.add(I18n.format("cpmplus.w7pose.body.warn.molang", res.molangReplaced));
		res.changed = true;
		try {
			Animator.preview();
		} catch (Throwable ex) {
		}
		return res;
	}

	private static String fmt(double v) {
		return String.valueOf(Math.round(v * 100) / 100.0);
	}

	/** removes the position / rotation keys of g strictly inside (from, to) (the ramp re-samples them) */
	private static native void removeInside(Animation a, Group g, double from, double to) /*-{
		var ba = a.getBoneAnimator(g);
		if (!ba) return;
		['position', 'rotation'].forEach(function(ch) {
			var arr = ba[ch];
			if (!arr) return;
			arr.filter(function(k) { return k.time > from + 1e-4 && k.time < to - 1e-4; }).forEach(function(k) {
				var i = arr.indexOf(k);
				if (i >= 0) arr.splice(i, 1);
			});
		});
	}-*/;

	/**
	 * Writes one key (replacing the value of a key at the same time, keeping its interpolation unless it is bezier).
	 * @return 1 when a bezier key was made linear, + 65536 when a formula was replaced by a number
	 */
	private static native int writeKey(Animation a, Group g, String ch, double t, double x, double y, double z) /*-{
		var ba = a.getBoneAnimator(g);
		if (!ba) return 0;
		var r = function(v) { return Math.round(v * 1e5) / 1e5; };
		var arr = ba[ch] || [];
		var kf = null;
		for (var i = 0; i < arr.length; i++) if (Math.abs(arr[i].time - t) < 1e-4) { kf = arr[i]; break; }
		var out = 0;
		if (kf) {
			var dp = kf.data_points[0];
			var isNum = function(v) { return typeof v == 'number' || (typeof v == 'string' && v.trim() !== '' && !isNaN(Number(v))); };
			if (!isNum(dp.x) || !isNum(dp.y) || !isNum(dp.z)) out += 65536;
			if (kf.data_points.length > 1) kf.data_points.splice(1, kf.data_points.length - 1);
			dp.x = r(x); dp.y = r(y); dp.z = r(z);
			if (kf.interpolation == 'bezier') { kf.interpolation = 'linear'; out += 1; }
		} else {
			ba.addKeyframe({channel: ch, time: t, interpolation: 'linear', data_points: [{x: r(x), y: r(y), z: r(z)}]});
		}
		return out;
	}-*/;

	// ------------------------------------------------------------------ ghost preview (display only)

	/** shows the result at the playhead on the meshes (Animator.preview() first, nothing written) */
	static void ghost(Animation a, Params p) {
		try {
			Animator.preview();
		} catch (Throwable e) {
		}
		Result res = new Result();
		List<Group> roots = new ArrayList<>();
		for (Group g : rootGroups())if (p.roots == null || p.roots.contains(g.name))roots.add(g);
		double t = Timeline.time;
		double s;
		switch (p.range) {
		case "all":
			s = 1;
			break;
		case "ramp": {
			double A = Math.min(p.from, p.to), B = Math.max(p.from, p.to);
			s = B - A < 1e-6 ? 0 : Math.max(0, Math.min(1, (t - A) / (B - A)));
			if (t > B)s = 0;
			break;
		}
		default:
			s = Math.abs(t - snap(a, p.time)) < EPS_T ? 1 : 0;
			break;
		}
		if (s <= 0 || res == null)return;
		BodyTransform bt = BodyTransform.of(p.rot, pivot(a, p, snap(a, p.time), roots), p.off).fraction(s);
		for (Group g : roots) {
			double[] o = pose(a, g, t);
			double[] n = bt.applyPart(new double[] {o[0], o[1], o[2]}, new double[] {o[3], o[4], o[5]}, null);
			setMesh(g.mesh, n[0], n[1], n[2], n[3] * PivotMath.DEG, n[4] * PivotMath.DEG, n[5] * PivotMath.DEG);
		}
	}

	private static native void setMesh(Object m, double x, double y, double z, double rx, double ry, double rz) /*-{
		if (!m) return;
		m.position.set(x, y, z);
		m.rotation.set(rx, ry, rz);
	}-*/;

	// ------------------------------------------------------------------ dialog

	public static void open() {
		Animation a = Animation.selected;
		if (a == null || !CPMCodec.isCPM()) {
			PlusDialogs.toast(I18n.get("cpmplus.w7pose.body.err.noAnim"), "info", 4000, null);
			return;
		}
		List<Group> roots = rootGroups();
		if (roots.isEmpty()) {
			PlusDialogs.block(I18n.get("cpmplus.w7pose.body.err.noRoots"));
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, I18n.get("cpmplus.w7pose.body.intro")));
		rows.add(FormRow.decimal("rx", "cpmplus.w7pose.body.rx", 0, -360, 360, 1).description("cpmplus.w7pose.body.rot.desc"));
		rows.add(FormRow.decimal("ry", "cpmplus.w7pose.body.ry", 0, -360, 360, 1));
		rows.add(FormRow.decimal("rz", "cpmplus.w7pose.body.rz", 0, -360, 360, 1));
		rows.add(FormRow.decimal("ox", "cpmplus.w7pose.body.ox", 0, -48, 48, 0.5).description("cpmplus.w7pose.body.off.desc"));
		rows.add(FormRow.decimal("oy", "cpmplus.w7pose.body.oy", 0, -48, 48, 0.5));
		rows.add(FormRow.decimal("oz", "cpmplus.w7pose.body.oz", 0, -48, 48, 0.5));
		Map<String, String> piv = new LinkedHashMap<>();
		piv.put("hip", "cpmplus.w7pose.body.pivot.hip");
		piv.put("neck", "cpmplus.w7pose.body.pivot.neck");
		piv.put("feet", "cpmplus.w7pose.body.pivot.feet");
		piv.put("part", "cpmplus.w7pose.body.pivot.part");
		rows.add(FormRow.select("pivot", "cpmplus.w7pose.body.pivot", piv, "hip"));
		Map<String, String> parts = new LinkedHashMap<>();
		for (Group g : roots)parts.put(g.name, g.name);
		rows.add(FormRow.select("pivotPart", "cpmplus.w7pose.body.pivotPart", parts, roots.get(0).name).when(r -> "part".equals(r.getString("pivot"))));
		rows.add(FormRow.info("rootsInfo", "cpmplus.w7pose.body.roots", I18n.get("cpmplus.w7pose.body.roots.desc")));
		for (Group g : roots) {
			FormRow cb = FormRow.checkbox("root_" + g.uuid, null, true).option("label", g.name);
			if ("head".equalsIgnoreCase(g.name))cb.description("cpmplus.w7pose.body.head.desc");
			rows.add(cb);
		}
		Map<String, String> ranges = new LinkedHashMap<>();
		ranges.put("frame", "cpmplus.w7pose.body.range.frame");
		ranges.put("all", "cpmplus.w7pose.body.range.all");
		ranges.put("ramp", "cpmplus.w7pose.body.range.ramp");
		rows.add(FormRow.select("range", "cpmplus.w7pose.body.range", ranges, "frame"));
		rows.add(FormRow.decimal("from", "cpmplus.w7pose.body.from", 0, 0, 3600, 0.05).when(r -> "ramp".equals(r.getString("range"))));
		rows.add(FormRow.decimal("to", "cpmplus.w7pose.body.to", Math.max(a.length, 1.0 / Math.max(1, a.snapping)), 0, 3600, 0.05).when(r -> "ramp".equals(r.getString("range"))));
		if (a.additive) {
			rows.add(FormRow.info("addWarn", null, I18n.get("cpmplus.w7pose.body.warn.additive")));
			rows.add(FormRow.checkbox("convert", "cpmplus.w7pose.body.convert", false).description("cpmplus.w7pose.body.convert.desc"));
		} else {
			rows.add(FormRow.info("lockWarn", null, I18n.get("cpmplus.w7pose.body.warn.lock")));
		}
		double time = Timeline.time;
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.width = 520;
		o.confirmKey = "cpmplus.w7pose.body.apply";
		o.onChange = r -> {
			try {
				ghost(a, params(r, roots, time));
			} catch (Throwable e) {
			}
		};
		o.onCancel = () -> {
			try {
				Animator.preview();
			} catch (Throwable e) {
			}
		};
		PlusDialogs.form("cpmplus_w7pose_body", "cpmplus.w7pose.body.title", rows, r -> {
			Params p = params(r, roots, time);
			Result res = run(a, p, false);
			if (!res.ok) {
				r.keepOpen();
				PlusDialogs.block(String.join("\n", res.errors));
				return;
			}
			res = run(a, p, true);
			String msg = I18n.format("cpmplus.w7pose.body.done", res.rootsChanged, res.smoothKeys);
			PlusDialogs.done(msg);
			if (!res.warnings.isEmpty())PlusDialogs.toast(String.join("\n", res.warnings), "warning", 9000, null);
		}, o);
	}

	static Params params(FormResult r, List<Group> roots, double time) {
		Params p = new Params();
		p.rot = new double[] {r.getDouble("rx", 0), r.getDouble("ry", 0), r.getDouble("rz", 0)};
		p.off = new double[] {r.getDouble("ox", 0), r.getDouble("oy", 0), r.getDouble("oz", 0)};
		p.pivot = r.getString("pivot") != null ? r.getString("pivot") : "hip";
		p.pivotPart = r.getString("pivotPart");
		p.roots = new ArrayList<>();
		for (Group g : roots)if (r.getBool("root_" + g.uuid))p.roots.add(g.name);
		p.range = r.getString("range") != null ? r.getString("range") : "frame";
		p.from = r.getDouble("from", 0);
		p.to = r.getDouble("to", 0);
		p.time = time;
		p.convertNonAdditive = r.getBool("convert");
		return p;
	}

	// ------------------------------------------------------------------ test hook

	public static void registerTestHooks() {
		PlusTestHooks.register("w7pose.wholeBody", o -> {
			JsPropertyMap<Object> opts = Js.uncheckedCast(o);
			Params p = new Params();
			p.rot = new double[] {num(opts, "rx"), num(opts, "ry"), num(opts, "rz")};
			p.off = new double[] {num(opts, "ox"), num(opts, "oy"), num(opts, "oz")};
			if (opts.get("pivot") != null)p.pivot = String.valueOf(opts.get("pivot"));
			if (opts.get("pivotPart") != null)p.pivotPart = String.valueOf(opts.get("pivotPart"));
			if (opts.get("range") != null)p.range = String.valueOf(opts.get("range")).toLowerCase(Locale.ROOT);
			p.time = opts.get("time") != null ? num(opts, "time") : Timeline.time;
			p.from = num(opts, "from");
			p.to = num(opts, "to");
			p.convertNonAdditive = Js.isTruthy(opts.get("convert"));
			Object rl = opts.get("roots");
			if (rl != null && JsArray.isArray(rl)) {
				JsArray<Object> ra = Js.uncheckedCast(rl);
				p.roots = new ArrayList<>();
				for (int i = 0; i < ra.length; i++)p.roots.add(String.valueOf(ra.getAt(i)));
			}
			Animation a = Animation.selected;
			if (opts.get("anim") != null) {
				for (int i = 0; i < Animation.all.length; i++)if (Animation.all[i].name.equals(String.valueOf(opts.get("anim"))))a = Animation.all[i];
			}
			if (Js.isTruthy(opts.get("ghost"))) {
				ghost(a, p);
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("ok", true);
				m.set("meshes", meshState());
				return m;
			}
			return run(a, p, !Js.isTruthy(opts.get("dryRun"))).toJs();
		});
	}

	/** {rootName: {px, py, pz, rx, ry, rz}} of the root meshes (test hook) */
	private static native Object meshState() /*-{
		var out = {};
		try {
			($wnd.Group.all || []).forEach(function(g) {
				if (g.parent !== 'root' || !g.mesh) return;
				var m = g.mesh;
				out[g.name] = {px: m.position.x, py: m.position.y, pz: m.position.z, rx: m.rotation.x, ry: m.rotation.y, rz: m.rotation.z};
			});
		} catch (e) {}
		return out;
	}-*/;

	private static double num(JsPropertyMap<Object> o, String k) {
		Object v = o.get(k);
		if (v == null)return 0;
		try {
			return Double.parseDouble(String.valueOf(v));
		} catch (Exception e) {
			return 0;
		}
	}
}
