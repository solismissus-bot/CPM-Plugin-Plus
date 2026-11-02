package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CPM+ (W2-ANIMCORE): pure keyframe math shared by every animation tool (mirror, delay, pose, reverse, amplitude...).
 * <p>
 * No Blockbench / GWT types here on purpose: this class compiles and runs with plain javac (QA unit tests in
 * qa/unit/w2animcore). {@link AnimEdit} adapts Blockbench keyframes to {@link KeyData} and back.
 * <p>
 * Math notes (verified against Blockbench 5.2 keyframe.js / mirror_animating.ts and CPM FrameData.mirror):
 * <ul>
 * <li>Mirror left/right (X axis): position (x,y,z) -&gt; (-x,y,z); rotation (rx,ry,rz) -&gt; (rx,-ry,-rz); scale,
 * visibility and colour unchanged; bezier handle VALUES negated on the same components (handle times unchanged).
 * This is exact for any Euler order (a reflection conjugates each axis). Keys in Blockbench are relative to the rest
 * pose and the exporter adds the rest only for non-additive animations, so no "fix additive" step is needed.</li>
 * <li>Delay (phase shift) on a loop: g(t) = f(t - dt). A rotation axis whose end differs from its start by 360*k
 * (within 0.5 degree, k != 0) "spins": the unrolled curve is F(t + L) = F(t) + delta, so keys that wrap from the tail
 * to the head get value - delta, the key at L gets g(0) + delta (G24).</li>
 * </ul>
 */
public final class KeyMath {
	public static final String POS = "position", ROT = "rotation", SCALE = "scale", VIS = "cpm_visibility", COLOR = "cpm_color";
	/** every channel CPM exports, in the order the tools show them */
	public static final String[] CHANNELS = {ROT, POS, SCALE, VIS, COLOR};
	/** two keys closer than this are the same key */
	public static final double EPS = 1e-5;
	/** Blockbench's own "same time" epsilon for interpolation (1/1200 s) */
	public static final double BB_EPS = 1 / 1200.0;

	private KeyMath() {
	}

	/** copy of an array (null stays null); GWT has no array clone() */
	public static double[] dup(double[] a) {
		return a == null ? null : java.util.Arrays.copyOf(a, a.length);
	}

	/** x with d decimals ("0.25"), no Locale / String.format (GWT) */
	public static String fmt(double x, int d) {
		double p = Math.pow(10, d);
		double r = Math.round(Math.abs(x) * p) / p;
		String s = Long.toString((long) Math.floor(r));
		if (d > 0) {
			long frac = Math.round((r - Math.floor(r)) * p);
			String f = Long.toString(frac);
			while (f.length() < d)f = "0" + f;
			s += "." + f;
		}
		return (x < 0 && r != 0 ? "-" : "") + s;
	}

	/** one keyframe of one channel, detached from Blockbench */
	public static final class KeyData {
		public double time;
		/** linear, catmullrom, bezier, step */
		public String interp = "linear";
		/** values per data point (Blockbench keys have 1, sometimes 2 data points) x {x, y, z} */
		public double[][] v = {{0, 0, 0}};
		/** a value is a Molang expression (not a number): never mirrored / scaled, reported as skipped */
		public boolean molang;
		/** bezier handles (x,y,z each); null when the key has none */
		public double[] bLT, bLV, bRT, bRV;
		/** visibility state (cpm_visibility keys) */
		public boolean vis = true;
		/** adapter data (Blockbench: the keyframe's undo copy); copied by reference */
		public Object payload;

		public KeyData() {
		}

		public KeyData(double time, double x, double y, double z) {
			this.time = time;
			this.v = new double[][] {{x, y, z}};
		}

		public static KeyData of(double time, String interp, double x, double y, double z) {
			KeyData k = new KeyData(time, x, y, z);
			k.interp = interp;
			return k;
		}

		public static KeyData visKey(double time, boolean state) {
			KeyData k = new KeyData(time, 0, 0, 0);
			k.interp = "step";
			k.vis = state;
			return k;
		}

		public KeyData copy() {
			KeyData k = new KeyData();
			k.time = time;
			k.interp = interp;
			k.v = new double[v.length][];
			for (int i = 0; i < v.length; i++)k.v[i] = dup(v[i]);
			k.molang = molang;
			k.bLT = dup(bLT);
			k.bLV = dup(bLV);
			k.bRT = dup(bRT);
			k.bRV = dup(bRV);
			k.vis = vis;
			k.payload = payload;
			return k;
		}

		/** value of an axis (0..2) of the first data point */
		public double get(int axis) {
			return v[0][axis];
		}

		/** value of an axis for the data point used AFTER this key (the last one) */
		double out(int axis) {
			return v[v.length - 1][axis];
		}

		/** value of an axis for the data point used BEFORE this key (the first one) */
		double in(int axis) {
			return v[0][axis];
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(time).append(':').append(interp);
			if (molang)sb.append("(molang)");
			for (double[] d : v)sb.append('[').append(d[0]).append(',').append(d[1]).append(',').append(d[2]).append(']');
			sb.append(vis ? "" : "(hidden)");
			return sb.toString();
		}
	}

	/** samples a channel at time t -&gt; {x, y, z} */
	public interface Sampler {
		double[] at(double t);
	}

	public static boolean isTransform(String channel) {
		return POS.equals(channel) || ROT.equals(channel) || SCALE.equals(channel);
	}

	public static boolean isStep(String channel) {
		return VIS.equals(channel);
	}

	// ====================================================================== mirror

	/**
	 * Mirrors one key left/right (X axis). Position: x negated; rotation: y and z negated; bezier handle values
	 * negated on the same components. Scale, visibility and colour are returned unchanged (copy).
	 * @return the mirrored copy, or null (SKIPPED) for a Molang key of position / rotation
	 */
	public static KeyData mirrorX(String channel, KeyData k) {
		KeyData m = k.copy();
		boolean pos = POS.equals(channel), rot = ROT.equals(channel);
		if (!pos && !rot)return m;
		if (k.molang)return null;
		for (double[] d : m.v) {
			if (pos)d[0] = -d[0];
			else {
				d[1] = -d[1];
				d[2] = -d[2];
			}
		}
		for (double[] h : new double[][] {m.bLV, m.bRV}) {
			if (h == null)continue;
			if (pos)h[0] = -h[0];
			else {
				h[1] = -h[1];
				h[2] = -h[2];
			}
		}
		return m;
	}

	/** mirrors every key; skipped[0] += number of Molang keys left out */
	public static List<KeyData> mirrorAll(String channel, List<KeyData> keys, int[] skipped) {
		List<KeyData> out = new ArrayList<>();
		for (KeyData k : keys) {
			KeyData m = mirrorX(channel, k);
			if (m == null) {
				if (skipped != null)skipped[0]++;
				continue;
			}
			out.add(m);
		}
		return out;
	}

	/** mirrors a pose value {x,y,z} of a channel */
	public static double[] mirrorValue(String channel, double[] v) {
		double[] r = dup(v);
		if (POS.equals(channel))r[0] = -r[0];
		else if (ROT.equals(channel)) {
			r[1] = -r[1];
			r[2] = -r[2];
		}
		return r;
	}

	// ====================================================================== interpolation (Blockbench rules)

	private static List<KeyData> sorted(List<KeyData> keys) {
		List<KeyData> s = new ArrayList<>(keys);
		Collections.sort(s, (a, b) -> Double.compare(a.time, b.time));
		return s;
	}

	/**
	 * Value of a transform / colour channel at time t with Blockbench's rules (Animation.interpolate): the key at t
	 * (1/1200 s), step holds the previous key, linear lerp, catmullrom spline over the neighbours, bezier curve.
	 * Before the first / after the last key the nearest key's value is held. Empty -&gt; null.
	 */
	public static double[] interpolate(List<KeyData> keys, double t) {
		if (keys == null || keys.isEmpty())return null;
		KeyData before = null, after = null;
		for (KeyData k : keys) {
			if (k.time < t) {
				if (before == null || k.time > before.time)before = k;
			} else {
				if (after == null || k.time < after.time)after = k;
			}
		}
		KeyData result = null;
		if (before != null && Math.abs(before.time - t) < BB_EPS)result = before;
		else if (after != null && Math.abs(after.time - t) < BB_EPS)result = after;
		else if (before != null && "step".equals(before.interp))result = before;
		else if (before != null && after == null)result = before;
		else if (after != null && before == null)result = after;
		if (result == null && before != null && after != null) {
			double alpha = (t - before.time) / (after.time - before.time);
			double[] r = new double[3];
			boolean bl = "linear".equals(before.interp) || before.interp == null;
			boolean al = "linear".equals(after.interp) || after.interp == null || "step".equals(after.interp);
			if (bl && al) {
				for (int a = 0; a < 3; a++)r[a] = before.out(a) + (after.in(a) - before.out(a)) * alpha;
				return r;
			}
			if ("catmullrom".equals(before.interp) || "catmullrom".equals(after.interp)) {
				List<KeyData> s = sorted(keys);
				int bi = s.indexOf(before);
				KeyData bp = bi - 1 >= 0 ? s.get(bi - 1) : null;
				KeyData ap = bi + 2 < s.size() ? s.get(bi + 2) : null;
				for (int a = 0; a < 3; a++)r[a] = catmull(bp, before, after, ap, a, alpha);
				return r;
			}
			if ("bezier".equals(before.interp) || "bezier".equals(after.interp)) {
				for (int a = 0; a < 3; a++)r[a] = bezier(before, after, a, alpha);
				return r;
			}
			for (int a = 0; a < 3; a++)r[a] = before.out(a) + (after.in(a) - before.out(a)) * alpha;
			return r;
		}
		if (result == null)return null;
		boolean first = result.time > t || Math.abs(result.time - t) < BB_EPS;
		double[] r = new double[3];
		for (int a = 0; a < 3; a++)r[a] = first ? result.in(a) : result.out(a);
		return r;
	}

	/** Blockbench getCatmullromLerp (THREE.SplineCurve over 2..4 points) */
	private static double catmull(KeyData bp, KeyData b, KeyData a, KeyData ap, int axis, double alpha) {
		List<Double> ys = new ArrayList<>();
		if (bp != null && b.v.length == 1)ys.add(bp.out(axis));
		ys.add(b.out(axis));
		ys.add(a.in(axis));
		if (ap != null && a.v.length == 1)ys.add(ap.in(axis));
		double time = (alpha + (bp != null && b.v.length == 1 ? 1 : 0)) / (ys.size() - 1);
		int n = ys.size();
		double point = (n - 1) * time;
		int ip = (int) Math.floor(point);
		double w = point - ip;
		if (ip >= n - 1) {
			ip = n - 2;
			w = 1;
		}
		double p0 = ys.get(ip == 0 ? ip : ip - 1);
		double p1 = ys.get(ip);
		double p2 = ys.get(ip > n - 2 ? n - 1 : ip + 1);
		double p3 = ys.get(ip > n - 3 ? n - 1 : ip + 2);
		double v0 = (p2 - p0) * 0.5, v1 = (p3 - p1) * 0.5;
		double t2 = w * w, t3 = w * t2;
		return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * w + p1;
	}

	/** Blockbench getBezierLerp (cubic bezier sampled at 200 points, nearest two by time, lerp) */
	private static double bezier(KeyData b, KeyData a, int axis, double alpha) {
		double vb = b.out(axis), va = a.in(axis);
		double gap = a.time - b.time;
		double thb = clamp(b.bRT != null ? b.bRT[axis] : 0, 0, gap);
		double tha = clamp(a.bLT != null ? a.bLT[axis] : 0, -gap, 0);
		double[] p0 = {b.time, vb};
		double[] p1 = {b.time + thb, vb + (b.bRV != null ? b.bRV[axis] : 0)};
		double[] p2 = {a.time + tha, va + (a.bLV != null ? a.bLV[axis] : 0)};
		double[] p3 = {a.time, va};
		double time = b.time + gap * alpha;
		double[][] pts = new double[201][];
		for (int i = 0; i <= 200; i++) {
			double s = i / 200.0, u = 1 - s;
			double x = u * u * u * p0[0] + 3 * u * u * s * p1[0] + 3 * u * s * s * p2[0] + s * s * s * p3[0];
			double y = u * u * u * p0[1] + 3 * u * u * s * p1[1] + 3 * u * s * s * p2[1] + s * s * s * p3[1];
			pts[i] = new double[] {x, y};
		}
		double[] c1 = null, c2 = null;
		double d1 = Double.MAX_VALUE;
		for (double[] p : pts) {
			double d = Math.abs(p[0] - time);
			if (d < d1) {
				d1 = d;
				c1 = p;
			}
		}
		double d2 = Double.MAX_VALUE;
		for (double[] p : pts) {
			if (p == c1)continue;
			double d = Math.abs(p[0] - time);
			if (d < d2) {
				d2 = d;
				c2 = p;
			}
		}
		double f = c2[0] == c1[0] ? 0 : clamp((time - c1[0]) / (c2[0] - c1[0]), 0, 1);
		return c1[1] + (c2[1] - c1[1]) * f;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	/**
	 * Visibility at time t (step): the last key at or before t (+1/1200 s); before the first key the default.
	 * Same rule as the plugin's Animation.interpolateVisible.
	 */
	public static boolean visAt(List<KeyData> keys, double t, boolean def) {
		KeyData before = null;
		for (KeyData k : keys) {
			if (k.time <= t + BB_EPS && (before == null || k.time > before.time))before = k;
		}
		return before == null ? def : before.vis;
	}

	/** a Sampler over a list of keys (pure Blockbench rules) */
	public static Sampler sampler(List<KeyData> keys) {
		return t -> interpolate(keys, t);
	}

	// ====================================================================== seam / spin

	public enum Seam {
		/** f(L) == f(0) */
		CLOSED,
		/** a rotation axis turns 360*k over the loop (k != 0) */
		CLOSED_SPIN,
		/** the loop jumps at the seam */
		OPEN
	}

	/** per axis 360*k when f(L) - f(0) is within 0.5 degree of 360*k (k != 0), else 0 */
	public static double[] spinDelta(double[] f0, double[] fL) {
		double[] d = new double[3];
		if (f0 == null || fL == null)return d;
		for (int a = 0; a < 3; a++) {
			double diff = fL[a] - f0[a];
			long k = Math.round(diff / 360.0);
			if (k != 0 && Math.abs(diff - 360.0 * k) < 0.5)d[a] = 360.0 * k;
		}
		return d;
	}

	/** seam of a channel (rotation may spin; other channels never do) */
	public static Seam seam(String channel, double[] f0, double[] fL) {
		if (f0 == null || fL == null)return Seam.CLOSED;
		boolean spin = false, closed = true;
		double[] d = ROT.equals(channel) ? spinDelta(f0, fL) : new double[3];
		for (int a = 0; a < 3; a++) {
			if (d[a] != 0) {
				spin = true;
				continue;
			}
			if (Math.abs(fL[a] - f0[a]) > 1e-4)closed = false;
		}
		if (!closed)return Seam.OPEN;
		return spin ? Seam.CLOSED_SPIN : Seam.CLOSED;
	}

	// ====================================================================== sorting / dedupe

	/**
	 * Sorts by time and merges keys closer than eps: the key that comes LATER in the input wins (callers append the
	 * keys they prefer last). Stable for equal times.
	 */
	public static List<KeyData> sortDedupe(List<KeyData> keys, double eps) {
		List<KeyData> out = new ArrayList<>();
		for (KeyData k : keys) {
			boolean replaced = false;
			for (int i = 0; i < out.size(); i++) {
				if (Math.abs(out.get(i).time - k.time) < eps) {
					out.set(i, k);
					replaced = true;
					break;
				}
			}
			if (!replaced)out.add(k);
		}
		Collections.sort(out, (a, b) -> Double.compare(a.time, b.time));
		return out;
	}

	/** visibility keys: drops every key that repeats the state of the key before it (first key kept) */
	public static List<KeyData> visMinimal(List<KeyData> keys) {
		List<KeyData> s = sorted(keys);
		List<KeyData> out = new ArrayList<>();
		for (KeyData k : s) {
			if (!out.isEmpty() && out.get(out.size() - 1).vis == k.vis)continue;
			out.add(k);
		}
		return out;
	}

	// ====================================================================== delay / phase shift

	/** result of a shift: the new keys and how many keys were dropped (once / hold) */
	public static final class ShiftResult {
		public final List<KeyData> keys;
		public final int dropped;

		ShiftResult(List<KeyData> keys, int dropped) {
			this.keys = keys;
			this.dropped = dropped;
		}
	}

	/** rounds a time offset to the animation's snapping grid (1/snapping s); snapping &lt; 1 = unchanged */
	public static double roundToSnap(double dt, int snapping) {
		if (snapping < 1)return dt;
		return Math.round(dt * snapping) / (double) snapping;
	}

	/**
	 * Delay (dt &gt; 0) or advance (dt &lt; 0) a LOOPING channel: g(t) = f(t - dt), length unchanged. f samples the
	 * ORIGINAL channel (Blockbench interpolation). Keys move to (t + dt) mod L (a key at L counts as the key at 0);
	 * boundary keys are written at 0 and L (spinning rotation axes: G24 +-360k, see class doc); visibility is shifted
	 * as step segments with the minimal number of keys. Bezier handles move with their key.
	 */
	public static ShiftResult shiftWrap(String channel, List<KeyData> keys, double dt, double L, Sampler f) {
		return shiftWrap(channel, keys, dt, L, f, true);
	}

	/** @param visDefault visibility before the first key (the part's own visibility) */
	public static ShiftResult shiftWrap(String channel, List<KeyData> keys, double dt, double L, Sampler f, boolean visDefault) {
		List<KeyData> src = sorted(keys);
		if (src.isEmpty() || !(L > EPS))return new ShiftResult(copyAll(src), 0);
		dt = dt % L;
		if (Math.abs(dt) < EPS || Math.abs(Math.abs(dt) - L) < EPS)return new ShiftResult(copyAll(src), 0);
		boolean step = isStep(channel);
		boolean hasKeyAt0 = false;
		for (KeyData k : src)if (Math.abs(k.time) < EPS)hasKeyAt0 = true;
		double[] delta = new double[3];
		if (!step && ROT.equals(channel))delta = spinDelta(f.at(0), f.at(L));
		double srcT = dt > 0 ? L - dt : -dt;// g(0) = F(-dt)
		List<KeyData> out = new ArrayList<>();
		// boundary keys first: a shifted key landing on the same time wins (keeps its own interpolation)
		KeyData tmpl = activeKey(src, srcT);
		if (step) {
			KeyData b0 = tmpl.copy();
			b0.time = 0;
			b0.vis = visAt(src, srcT, visDefault);
			out.add(b0);
		} else {
			double[] g0 = f.at(srcT);
			if (dt > 0)for (int a = 0; a < 3; a++)g0[a] -= delta[a];
			KeyData b0 = tmpl.copy();
			b0.time = 0;
			b0.molang = false;
			b0.v = new double[][] {dup(g0)};
			out.add(b0);
			KeyData bL = tmpl.copy();
			bL.time = L;
			bL.molang = false;
			double[] gL = dup(g0);
			for (int a = 0; a < 3; a++)gL[a] += delta[a];
			bL.v = new double[][] {gL};
			out.add(bL);
		}
		for (KeyData k : src) {
			KeyData n = k.copy();
			double t = k.time;
			double sign = 0;
			if (t >= L - EPS) {
				if (hasKeyAt0)continue;// the key at L is the key at 0 of the next turn
				t = 0;
				sign = -1;
			}
			double nt = t + dt;
			if (nt >= L - EPS) {
				nt -= L;
				sign -= 1;
			} else if (nt < -EPS) {
				nt += L;
				sign += 1;
			}
			if (nt < 0)nt = 0;
			n.time = nt;
			if (!step && sign != 0) {
				for (double[] d : n.v)for (int a = 0; a < 3; a++)d[a] += sign * delta[a];
			}
			out.add(n);
		}
		List<KeyData> res = sortDedupe(out, EPS);
		if (step)res = visMinimal(res);
		return new ShiftResult(res, 0);
	}

	/**
	 * Delay / advance a NON-looping channel (once / hold): keys move by dt without wrapping; keys that land outside
	 * [0, L] are dropped and counted (the caller warns); keys within 1e-5 of the ends are clamped onto them.
	 */
	public static ShiftResult shiftClamp(String channel, List<KeyData> keys, double dt, double L) {
		List<KeyData> out = new ArrayList<>();
		int dropped = 0;
		for (KeyData k : sorted(keys)) {
			double nt = k.time + dt;
			if (nt < -EPS || nt > L + EPS) {
				dropped++;
				continue;
			}
			KeyData n = k.copy();
			n.time = Math.max(0, Math.min(L, nt));
			out.add(n);
		}
		List<KeyData> res = sortDedupe(out, EPS);
		if (isStep(channel))res = visMinimal(res);
		return new ShiftResult(res, dropped);
	}

	/** the key whose segment contains t (last key at or before t), or the first key */
	private static KeyData activeKey(List<KeyData> sortedKeys, double t) {
		KeyData r = sortedKeys.get(0);
		for (KeyData k : sortedKeys)if (k.time <= t + EPS)r = k;
		return r;
	}

	private static List<KeyData> copyAll(List<KeyData> keys) {
		List<KeyData> out = new ArrayList<>();
		for (KeyData k : keys)out.add(k.copy());
		return out;
	}

	// ====================================================================== amplitude / reverse

	/**
	 * Scales the movement of one key by factor: rotation / position v*k, scale 1+(v-1)*k, bezier handle values *k;
	 * colour and visibility unchanged. Molang keys -&gt; null (skipped).
	 */
	public static KeyData scaleAmp(String channel, KeyData k, double factor) {
		KeyData m = k.copy();
		if (!isTransform(channel))return m;
		if (k.molang)return null;
		boolean sc = SCALE.equals(channel);
		for (double[] d : m.v)for (int a = 0; a < 3; a++)d[a] = sc ? 1 + (d[a] - 1) * factor : d[a] * factor;
		for (double[] h : new double[][] {m.bLV, m.bRV}) {
			if (h == null)continue;
			for (int a = 0; a < 3; a++)h[a] *= factor;
		}
		return m;
	}

	/**
	 * Plays a channel backwards: t -&gt; L - t; data points (pre / post) swap; bezier left / right handles swap with
	 * their times negated; each segment keeps the interpolation it had (the key that starts a reversed segment takes
	 * the interpolation of the key that started it before).
	 */
	public static List<KeyData> reverse(String channel, List<KeyData> keys, double L) {
		List<KeyData> s = sorted(keys);
		List<KeyData> out = new ArrayList<>();
		for (int i = s.size() - 1; i >= 0; i--) {
			KeyData k = s.get(i).copy();
			k.time = Math.max(0, L - k.time);
			if (k.v.length > 1) {
				double[][] r = new double[k.v.length][];
				for (int j = 0; j < k.v.length; j++)r[j] = k.v[k.v.length - 1 - j];
				k.v = r;
			}
			double[] lt = k.bLT, lv = k.bLV;
			k.bLT = neg(k.bRT);
			k.bLV = k.bRV;
			k.bRT = neg(lt);
			k.bRV = lv;
			// segment (s[i-1], s[i]) is now (s[i], s[i-1]): started by the reversed s[i], interpolated like s[i-1]
			if (!isStep(channel) && i - 1 >= 0)k.interp = s.get(i - 1).interp;
			out.add(k);
		}
		if (isStep(channel)) {
			// step: state at t' = state just before L - t'; rebuild from segments
			List<VisSegment> seg = visSegments(s, L, s.isEmpty() || s.get(0).vis);
			List<KeyData> v = new ArrayList<>();
			for (int i = seg.size() - 1; i >= 0; i--) {
				VisSegment g = seg.get(i);
				KeyData k = (s.isEmpty() ? KeyData.visKey(0, g.state) : s.get(0).copy());
				k.time = Math.max(0, L - g.t1);
				k.vis = g.state;
				v.add(k);
			}
			return visMinimal(sortDedupe(v, EPS));
		}
		return sortDedupe(out, EPS);
	}

	private static double[] neg(double[] a) {
		if (a == null)return null;
		double[] r = dup(a);
		for (int i = 0; i < r.length; i++)r[i] = -r[i];
		return r;
	}

	// ====================================================================== visibility segments

	/** [t0, t1) with one visibility state */
	public static final class VisSegment {
		public final double t0, t1;
		public final boolean state;

		public VisSegment(double t0, double t1, boolean state) {
			this.t0 = t0;
			this.t1 = t1;
			this.state = state;
		}

		@Override
		public String toString() {
			return "[" + t0 + "," + t1 + ")=" + state;
		}
	}

	/** visibility keys -&gt; segments covering [0, L] (before the first key: def) */
	public static List<VisSegment> visSegments(List<KeyData> keys, double L, boolean def) {
		List<KeyData> s = sorted(keys);
		List<VisSegment> out = new ArrayList<>();
		double t = 0;
		boolean st = def;
		for (KeyData k : s) {
			double kt = Math.max(0, Math.min(L, k.time));
			if (kt > t + EPS)out.add(new VisSegment(t, kt, st));
			t = Math.max(t, kt);
			st = k.vis;
		}
		if (L > t + EPS || out.isEmpty())out.add(new VisSegment(t, Math.max(t, L), st));
		// merge equal neighbours
		List<VisSegment> m = new ArrayList<>();
		for (VisSegment g : out) {
			if (!m.isEmpty() && m.get(m.size() - 1).state == g.state) {
				VisSegment p = m.remove(m.size() - 1);
				m.add(new VisSegment(p.t0, g.t1, g.state));
			} else m.add(g);
		}
		return m;
	}

	/** segments -&gt; minimal step keys (template = payload / interpolation donor, may be null) */
	public static List<KeyData> visFromSegments(List<VisSegment> segs, KeyData template) {
		List<KeyData> out = new ArrayList<>();
		for (VisSegment g : segs) {
			KeyData k = template != null ? template.copy() : KeyData.visKey(0, true);
			k.time = g.t0;
			k.vis = g.state;
			k.interp = "step";
			out.add(k);
		}
		return visMinimal(out);
	}
}
