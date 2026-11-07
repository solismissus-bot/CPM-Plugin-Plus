package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.List;

/**
 * CPM+ (W3-ANIMGEN, F8 'Gerar movimento pronto' + G24 'Girar sem parar'): the preset tables, the key generators and
 * the continuous-spin rules. Pure Java (no Blockbench types, javac-testable in qa/unit/w3animgen).
 * <p>
 * Wave presets write at least {@link #KEYS_PER_CYCLE} catmullrom keys per cycle, a whole number of cycles per loop
 * and a key at L equal to the key at 0 (seamless loop), at most {@link #MAX_KEYS} keys per part. 'Tremer' writes
 * seeded noise on linear keys. 'Girar sem parar' writes exactly two linear keys (0 at t=0, +-360*k at t=L); the
 * export samples the spin at the animation's snapping ({@link #spinGrid}) as LINEAR_SINGLE with the end pose, never
 * LINEAR_LOOP (a loop would turn backwards over the seam), and still subdivides any step above 170 degrees.
 */
public final class MotionPresets {
	public static final int KEYS_PER_CYCLE = 8;
	public static final int MAX_KEYS = 64;
	/** 8 keys per cycle + the closing key: 7 cycles = 57 keys, 8 would be 65 */
	public static final int MAX_CYCLES = 7;
	public static final int MAX_TURNS = 8;
	/** the speed guard: a spin step above this many degrees between two exported frames is 'too fast' */
	public static final double SPIN_MAX_STEP = 120;
	/** the export subdivides any rotation step above this (BlockbenchExport.emitGrid) */
	public static final double EXPORT_REFINE_STEP = 170;
	/** = BlockbenchExport.ROT_SPLIT (kept here: the W3-ANIMGEN javac unit compiles this class on its own) */
	public static final double EXPORT_ROT_SPLIT = 179.999;
	public static final int MAX_FRAMES = 255;

	public static final String POS = "position", ROT = "rotation", SCALE = "scale";

	private MotionPresets() {
	}

	/** the presets of the gallery (ids are stable: dialogs, prefs and test hooks use them) */
	public enum Preset {
		RESPIRAR("respirar", SCALE, 'y', new double[] {0.03, 0.06, 0.1}),
		FLUTUAR("flutuar", POS, 'y', new double[] {0.5, 1, 2}),
		BATER_ASAS("bater_asas", ROT, 'z', new double[] {15, 30, 45}),
		BALANCAR_CAUDA("balancar_cauda", ROT, 'y', new double[] {10, 20, 35}),
		PENDULO("pendulo", ROT, 'x', new double[] {10, 20, 35}),
		TREMER("tremer", POS, 'x', new double[] {0.1, 0.25, 0.5}),
		GIRAR("girar", ROT, 'y', new double[] {1, 1, 1});

		public final String id, channel;
		public final char axis;
		private final double[] amp;

		Preset(String id, String channel, char axis, double[] amp) {
			this.id = id;
			this.channel = channel;
			this.axis = axis;
			this.amp = amp;
		}

		/** amplitude for 'S' (pequena), 'M' (média), 'L' (grande): px, degrees or scale fraction */
		public double amplitude(char intensity) {
			switch (Character.toUpperCase(intensity)) {
			case 'S': return amp[0];
			case 'L': return amp[2];
			default: return amp[1];
			}
		}

		public boolean usesScale() {
			return SCALE.equals(channel);
		}

		public boolean isSpin() {
			return this == GIRAR;
		}

		public boolean isWave() {
			return this != GIRAR && this != TREMER;
		}

		public static Preset byId(String id) {
			if (id == null)return null;
			for (Preset p : values())if (p.id.equalsIgnoreCase(id) || p.name().equalsIgnoreCase(id))return p;
			return null;
		}
	}

	/** one generated key (time in seconds, value x/y/z, Blockbench interpolation) */
	public static final class Key {
		public final double t, x, y, z;
		public final String interp;

		public Key(double t, double x, double y, double z, String interp) {
			this.t = t;
			this.x = x;
			this.y = y;
			this.z = z;
			this.interp = interp;
		}

		public double get(char axis) {
			return axis == 'x' ? x : axis == 'y' ? y : z;
		}

		@Override
		public String toString() {
			return t + ":" + x + "," + y + "," + z + "/" + interp;
		}
	}

	/** settings of one generation */
	public static final class Spec {
		public Preset preset;
		/** loop length (seconds) */
		public double length = 2;
		/** whole cycles per loop (waves) / turns per loop (spin) */
		public int cycles = 1;
		public double amplitude;
		/** start phase 0..1 of a cycle */
		public double phase;
		/** rotation / position axis ('x', 'y', 'z'): pendulum x or z, spin y/x/z */
		public char axis;
		/** spin: +1 = anti-horário (positive angle), -1 = horário */
		public int direction = 1;
		/** snapping of the animation (tremer: one key per frame, at most MAX_KEYS) */
		public int fps = 20;
		public long seed = 1234567;

		public Spec(Preset p) {
			this.preset = p;
			this.axis = p.axis;
			this.amplitude = p.amplitude('M');
		}
	}

	/** why a spec can't be generated ({@code null} = fine) */
	public static String problem(Spec s) {
		if (s.preset == null)return "preset";
		if (!(s.length > 0) || s.length > 32.767 + 1e-9)return "length";
		if (s.preset.isSpin()) {
			if (s.cycles < 1 || s.cycles > MAX_TURNS)return "turns";
		} else if (s.preset.isWave() && (s.cycles < 1 || s.cycles > MAX_CYCLES))return "cycles";
		if (s.fps < 1)return "fps";
		return null;
	}

	/** the keys of one part (see the class comment) */
	public static List<Key> generate(Spec s) {
		String p = problem(s);
		if (p != null)throw new IllegalArgumentException(p);
		List<Key> out = new ArrayList<>();
		double L = s.length;
		switch (s.preset) {
		case GIRAR: {
			double end = 360.0 * s.cycles * (s.direction < 0 ? -1 : 1);
			out.add(new Key(0, 0, 0, 0, "linear"));
			out.add(new Key(L, s.axis == 'x' ? end : 0, s.axis == 'y' ? end : 0, s.axis == 'z' ? end : 0, "linear"));
			return out;
		}
		case TREMER: {
			int seg = Math.max(1, Math.min(MAX_KEYS - 1, (int) Math.round(L * s.fps)));
			BlinkGenerator.Rng rng = new BlinkGenerator.Rng(s.seed);
			double[] first = null;
			for (int i = 0; i <= seg; i++) {
				double t = i == seg ? L : i * L / seg;
				double[] v;
				if (i == seg)v = first;
				else v = new double[] {noise(rng, s.amplitude), noise(rng, s.amplitude), noise(rng, s.amplitude)};
				if (first == null)first = v;
				out.add(new Key(t, v[0], v[1], v[2], "linear"));
			}
			return out;
		}
		default: {
			int n = KEYS_PER_CYCLE * s.cycles;
			Key k0 = null;
			for (int i = 0; i <= n; i++) {
				if (i == n) {
					out.add(new Key(L, k0.x, k0.y, k0.z, k0.interp));
					break;
				}
				double t = i * L / n;
				double w = Math.sin(2 * Math.PI * ((double) i / KEYS_PER_CYCLE + s.phase));
				if (Math.abs(w) < 1e-12)w = 0;
				Key k = wave(s, t, w);
				if (k0 == null)k0 = k;
				out.add(k);
			}
			return out;
		}
		}
	}

	private static double noise(BlinkGenerator.Rng rng, double a) {
		double v = (rng.nextDouble() * 2 - 1) * a;
		return Math.round(v * 1000) / 1000.0;
	}

	private static double r4(double v) {
		return Math.round(v * 10000) / 10000.0;
	}

	private static Key wave(Spec s, double t, double w) {
		double a = s.amplitude * w;
		switch (s.preset) {
		case RESPIRAR:
			return new Key(t, r4(1 + a / 2), r4(1 + a), 1, "catmullrom");
		case FLUTUAR:
			return new Key(t, 0, r4(a), 0, "catmullrom");
		default: {
			double v = r4(a);
			return new Key(t, s.axis == 'x' ? v : 0, s.axis == 'y' ? v : 0, s.axis == 'z' ? v : 0, "catmullrom");
		}
		}
	}

	/**
	 * the same keys for the part on the other side, mirrored across the model's X axis (as F1 'Espelhar movimento'):
	 * position x and rotation y / z change sign, scale stays
	 */
	public static List<Key> mirror(String channel, List<Key> keys) {
		List<Key> out = new ArrayList<>();
		for (Key k : keys) {
			if (POS.equals(channel))out.add(new Key(k.t, neg(k.x), k.y, k.z, k.interp));
			else if (ROT.equals(channel))out.add(new Key(k.t, k.x, neg(k.y), neg(k.z), k.interp));
			else out.add(k);
		}
		return out;
	}

	private static double neg(double v) {
		return v == 0 ? 0 : -v;
	}

	// ====================================================================== continuous spin (G24)

	/** whole turns k when delta (value at L minus value at 0, degrees) is within 0.5 degree of +-360*k (k &gt;= 1), else 0 */
	public static int spinTurns(double delta) {
		double a = Math.abs(delta);
		if (a < 359.5)return 0;
		long k = Math.round(a / 360.0);
		return Math.abs(a - 360.0 * k) <= 0.5 ? (int) Math.min(Integer.MAX_VALUE, k) : 0;
	}

	/**
	 * Largest spin (turns) over the export's grid samples: first / last = the samples at t=0 and t=L, stride values
	 * per part with the rotation x/y/z at rotOffset..rotOffset+2 (BlockbenchExport.sig layout).
	 */
	public static int maxSpinTurns(double[] first, double[] last, int stride, int rotOffset) {
		int max = 0;
		if (first == null || last == null)return 0;
		for (int i = 0; i + stride <= first.length && i + stride <= last.length; i += stride) {
			for (int a = 0; a < 3; a++) {
				double x = first[i + rotOffset + a], y = last[i + rotOffset + a];
				if (Double.isNaN(x) || Double.isNaN(y))continue;
				max = Math.max(max, spinTurns(y - x));
			}
		}
		return max;
	}

	/** smallest multiple of m that is &gt;= target (capped at 1000, never below m): BlockbenchExport.refineGrid */
	public static int refine(int m, int target) {
		if (target <= m)return m;
		int f = (target + m - 1) / m;
		int r = m * f;
		if (r > 1000)r = Math.max(m, (1000 / m) * m);
		return r;
	}

	/**
	 * Export rule for animations with a continuous spin (BlockbenchExport.emitGrid, before its 170-degree refine): the
	 * grid of m intervals over len seconds becomes the snapping grid round(len * fps) (a multiple of m, so every key
	 * stays on a frame) as long as the LINEAR_SINGLE frames (grid + 1) fit in 255; otherwise m is kept.
	 * @param turns the largest spin of the animation ({@link #maxSpinTurns}); 0 = no spin, m unchanged
	 */
	public static int spinGrid(int m, double len, int fps, int turns) {
		if (turns <= 0 || !(len > 0) || fps < 1 || m < 1)return m;
		int target = (int) Math.round(len * fps);
		if (target <= m)return m;
		int nm = refine(m, target);
		if (nm + 1 > MAX_FRAMES)return m;
		return nm;
	}

	/** grid (intervals) the export uses for a lone spin of 2 keys: the snapping grid, then the 170-degree refine */
	public static int spinExportIntervals(double len, int fps, int turns) {
		int m = spinGrid(1, len, fps, turns);
		double step = 360.0 * turns / m;
		if (step > EXPORT_ROT_SPLIT) {
			int nm = refine(m, m * (int) Math.ceil(step / EXPORT_REFINE_STEP));
			if (nm > m)m = nm;
		}
		return m;
	}

	/** degrees between two exported frames of a lone spin (2 keys) */
	public static double spinExportStep(double len, int fps, int turns) {
		return 360.0 * turns / spinExportIntervals(len, fps, turns);
	}

	/** CPM frames a lone spin exports with (LINEAR_SINGLE: intervals + 1, end pose included) */
	public static int spinExportFrames(double len, int fps, int turns) {
		return spinExportIntervals(len, fps, turns) + 1;
	}

	/**
	 * The snapping 'Corrigir' sets: the smallest of the allowed values (sorted or not) above current whose export step
	 * is at most {@link #SPIN_MAX_STEP} and whose frames fit in 255. -1 when none qualifies (fewer turns / a longer
	 * animation are the only way then).
	 */
	public static int pickSnapping(double len, int turns, int current, Iterable<Integer> allowed) {
		int best = -1;
		if (allowed == null)return -1;
		for (Integer s : allowed) {
			if (s == null || s <= current || s < 1)continue;
			if (spinExportFrames(len, s, turns) > MAX_FRAMES)continue;
			if (spinExportStep(len, s, turns) > SPIN_MAX_STEP + 1e-9)continue;
			if (best == -1 || s < best)best = s;
		}
		return best;
	}

	/** the snapping values 1..500 whose lone-spin export fits in 255 frames */
	public static List<Integer> spinSnappings(double len, int turns) {
		List<Integer> out = new ArrayList<>();
		for (int s = 1; s <= 500; s++)if (spinExportFrames(len, s, turns) <= MAX_FRAMES)out.add(s);
		return out;
	}
}
