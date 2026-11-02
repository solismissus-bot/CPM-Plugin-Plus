package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;

/**
 * CPM+ F14 / F17 math, pure Java (no GWT / jsinterop: javac-testable with KeyMath only).
 * <ul>
 * <li>reverse: a channel played backwards over [0, L] (the WHOLE animation, not the selected range). Smooth keys keep
 * their interpolation (Blockbench decides a segment from both of its keys and that rule is symmetric for linear /
 * catmullrom / bezier), data points swap, bezier handles swap with their times negated (like Blockbench's
 * reverse_keyframes). Step channels (visibility, or every key 'step') are rebuilt from their segments: the state of
 * [t_i, t_i+1) becomes a key at L - t_i+1, plus a key at 0 with the state of the last segment.</li>
 * <li>scale: amplitude around the rest pose (KeyMath.scaleAmp: rotation / position v*k, scale 1+(v-1)*k).</li>
 * </ul>
 */
public final class QuickAnimMath {
	private QuickAnimMath() {
	}

	/** result of {@link #reverse}: the new keys, and whether 'step' keys were mixed with smooth ones */
	public static final class Reversed {
		public final List<KeyData> keys;
		public final boolean mixedStep;

		Reversed(List<KeyData> keys, boolean mixedStep) {
			this.keys = keys;
			this.mixedStep = mixedStep;
		}
	}

	/**
	 * @param channel KeyMath channel name
	 * @param keys the channel's keys (any order; not modified)
	 * @param L animation length (&gt; 0)
	 * @param defVisible visibility of the part before its first key (visibility only)
	 */
	public static Reversed reverse(String channel, List<KeyData> keys, double L, boolean defVisible) {
		List<KeyData> s = new ArrayList<>(keys);
		Collections.sort(s, (a, b) -> Double.compare(a.time, b.time));
		if (s.isEmpty())return new Reversed(new ArrayList<>(), false);
		if (KeyMath.isStep(channel))return new Reversed(reverseVis(s, L, defVisible), false);
		boolean step = false, smooth = false, molang = false;
		for (KeyData k : s) {
			if ("step".equals(k.interp))step = true;
			else smooth = true;
			if (k.molang)molang = true;
		}
		if (step && !smooth && !molang)return new Reversed(reverseStepValues(s, L), false);
		List<KeyData> out = new ArrayList<>();
		for (int i = s.size() - 1; i >= 0; i--)out.add(flipKey(s.get(i), L));
		return new Reversed(KeyMath.sortDedupe(out, KeyMath.EPS), step && smooth);
	}

	/** one key moved to L - t: data points reversed, bezier handles swapped (times negated); interpolation kept */
	static KeyData flipKey(KeyData src, double L) {
		KeyData k = src.copy();
		k.time = clampTime(L - k.time, L);
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
		return k;
	}

	/** visibility: segments of the original, rebuilt backwards (the result always has a key at 0) */
	static List<KeyData> reverseVis(List<KeyData> s, double L, boolean def) {
		List<KeyMath.VisSegment> seg = KeyMath.visSegments(s, L, def);
		List<KeyData> v = new ArrayList<>();
		KeyData template = s.get(0);
		for (int i = seg.size() - 1; i >= 0; i--) {
			KeyMath.VisSegment g = seg.get(i);
			KeyData k = template.copy();
			k.time = clampTime(L - g.t1, L);
			k.vis = g.state;
			k.interp = "step";
			v.add(k);
		}
		return KeyMath.visMinimal(KeyMath.sortDedupe(v, KeyMath.EPS));
	}

	/**
	 * every key 'step': value held from key i until key i+1. Reversed: the value of key i goes to L - t(i+1), the
	 * last key's value to 0. Each new key is a copy of the key whose value it holds.
	 */
	static List<KeyData> reverseStepValues(List<KeyData> s, double L) {
		List<KeyData> out = new ArrayList<>();
		int n = s.size();
		for (int i = n - 1; i >= 0; i--) {
			KeyData k = s.get(i).copy();
			double end = i + 1 < n ? s.get(i + 1).time : L;
			k.time = clampTime(L - end, L);
			double[] held = KeyMath.dup(k.v[k.v.length - 1]);
			k.v = new double[][] {held};
			k.bLT = k.bLV = k.bRT = k.bRV = null;
			out.add(k);
		}
		// the first segment [0, t1) holds key 0's value; when key 0 is after 0, nothing else is needed (the value of
		// key 0 is also what Blockbench shows before it)
		return KeyMath.sortDedupe(out, KeyMath.EPS);
	}

	/** amplitude of one key (KeyMath.scaleAmp), or null for a Molang key */
	public static KeyData scale(String channel, KeyData k, double factor) {
		return KeyMath.scaleAmp(channel, k, factor);
	}

	/** largest |value| of the keys (rotation warning) */
	public static double maxAbs(List<KeyData> keys) {
		double m = 0;
		for (KeyData k : keys) {
			if (k == null || k.molang)continue;
			for (double[] d : k.v)for (double x : d)m = Math.max(m, Math.abs(x));
		}
		return m;
	}

	private static double clampTime(double t, double L) {
		double r = Math.max(0, Math.min(L, t));
		double q = Math.round(r * 1e6) / 1e6;
		return Math.abs(q - r) < 1e-9 ? q : r;
	}

	private static double[] neg(double[] a) {
		if (a == null)return null;
		double[] r = KeyMath.dup(a);
		for (int i = 0; i < r.length; i++)r[i] = -r[i];
		return r;
	}
}
