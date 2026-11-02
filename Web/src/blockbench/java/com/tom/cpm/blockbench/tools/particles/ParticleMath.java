package com.tom.cpm.blockbench.tools.particles;

/**
 * CPM+ (W6-PARTICLES): the pure math of the fake-particle generator (no Blockbench / GWT types, javac-testable).
 * Port of the validated prototype (gen_particles.js) with the verifiers' corrections:
 * <ul>
 * <li>every particle lives a whole loop, phase-shifted (LOOP), or all are born at t~0 and die at the end (ONCE, for
 * 'play once' gestures: no wrap, a real burst);</li>
 * <li>birth and death frames are hidden (show=false) and use scale 0.01, never 0: CPM ignores a 0 scale component
 * (RenderedCube.setRenderScale), so a 0 would pop the sprite to full size if a visibility step landed a frame off;</li>
 * <li>rotation turns at most 170 degrees over a life so the hidden death -&gt; birth jump stays under 180 degrees
 * (CPM interpolates rotations the short way);</li>
 * <li>positions are offsets from the emitter, clamped to the format limit +-48 px.</li>
 * </ul>
 * Coordinates are CPM model space (pixels, +y DOWN, the front of the player is -z).
 */
public final class ParticleMath {
	/** FORMAT limits of the default (legacy) CPM animation export (ModelPartAnimation: bytes / short) */
	public static final int MAX_FRAMES = 255;
	public static final int MAX_ANIMATED_PARTS = 255;
	public static final int MAX_DURATION_MS = 32767;
	/** keyframe position limit (Vec3f.MAX_POS = 3 blocks); kept a little inside */
	public static final float MAX_OFFSET = 47.5f;
	public static final float MIN_SCALE = 0.01f;
	/** CPM inline model data: above this the model needs an upload link (gist / paste) */
	public static final int INLINE_LIMIT_BYTES = 30 * 1024;
	/** MEDIUM safety profile: link download limit (KB of the base64 download) and cube limit; advisory only */
	public static final int MEDIUM_LINK_KB = 100;
	public static final int MEDIUM_CUBES = 256;
	public static final int MEDIUM_TEXTURE = 256;

	public enum Pattern {
		BURST, ORBIT, RAIN, SPARKLE, SMOKE, BEHIND;

		public static Pattern of(String s) {
			for (Pattern p : values())if (p.name().equalsIgnoreCase(s))return p;
			return BURST;
		}
	}

	public enum Fade {
		SHRINK, BLINK, DARKEN;

		public static Fade of(String s) {
			for (Fade p : values())if (p.name().equalsIgnoreCase(s))return p;
			return SHRINK;
		}
	}

	public enum Timing {
		/** loop, each particle phase-shifted (always / poses / looping gestures / toggles) */
		LOOP,
		/** play once: everyone born at the start (staggered by up to 2 frames), no wrap */
		ONCE
	}

	/** one frame of one particle (CPM space) */
	public static final class Sample {
		public float px, py, pz;
		public float rx, ry, rz;
		public float scale;
		public int r = 255, g = 255, b = 255;
		public boolean show;
	}

	/** generator settings (what the dialog decides) */
	public static final class Settings {
		public Pattern pattern = Pattern.BURST;
		public Fade fade = Fade.SHRINK;
		public int count = 12;
		public float distance = 1f;
		public float size = 1f;
		public float spin = 1f;
		public long seed = 1337;
		/** recolor channel: null = keep the image colour (no colour keys) */
		public int[] colorA, colorB;
		/** extra hidden frames before the end (POLY / catmullrom grids: the spline may undershoot the 0.01 end) */
		public int hideTail = 0;
	}

	private ParticleMath() {
	}

	/** deterministic LCG, same constants as the prototype */
	static final class Rnd {
		private long s;

		Rnd(long seed) {
			s = seed & 0x7fffffffL;
		}

		double next() {
			s = (s * 1103515245L + 12345L) & 0x7fffffffL;
			return s / (double) 0x7fffffffL;
		}
	}

	/** per-particle random constants */
	static final class Particle {
		double ang, r, rise, base, tilt, sizeK, a3, a4;
		int phase;
	}

	static Particle[] particles(Settings st, int frames) {
		Rnd rnd = new Rnd(st.seed);
		int n = Math.max(1, st.count);
		Particle[] out = new Particle[n];
		for (int k = 0; k < n; k++) {
			Particle p = new Particle();
			p.ang = (k / (double) n) * Math.PI * 2 + (rnd.next() - 0.5) * 0.4;
			p.r = (4 + rnd.next() * 4) * st.distance;
			p.rise = (5 + rnd.next() * 6) * st.distance;
			p.a3 = rnd.next();
			p.a4 = rnd.next();
			p.base = p.a3 * 180;
			p.tilt = (p.a4 - 0.5) * 40;
			p.sizeK = st.size * (0.8 + rnd.next() * 0.6);
			p.phase = (int) (Math.round((k * (double) frames) / n) % Math.max(1, frames));
			out[k] = p;
		}
		return out;
	}

	/**
	 * All samples: [particle][frame]. frames = number of CPM frames of the animation grid.
	 * LOOP: life frame lf = (f - phase) mod F, t = lf / (F - 1); lf 0 and F-1 hidden.
	 * ONCE: birth b_k in [0, 2], t = (f - b) / (F - 1 - b); f &lt;= b and f == F-1 hidden.
	 */
	public static Sample[][] generate(Settings st, int frames, Timing timing) {
		int F = Math.max(2, frames);
		Particle[] ps = particles(st, F);
		Sample[][] out = new Sample[ps.length][F];
		int stagger = Math.min(2, Math.max(0, F / 6));
		for (int k = 0; k < ps.length; k++) {
			Particle p = ps[k];
			int birth = timing == Timing.ONCE ? (ps.length > 1 ? Math.round(k * stagger / (float) (ps.length - 1)) : 0) : 0;
			for (int f = 0; f < F; f++) {
				double t;
				boolean show;
				int lf;
				if (timing == Timing.LOOP) {
					lf = ((f - p.phase) % F + F) % F;
					t = lf / (double) (F - 1);
					show = lf != 0 && lf < F - 1 - st.hideTail;
				} else {
					int span = Math.max(1, F - 1 - birth);
					lf = Math.max(0, f - birth);
					t = Math.min(1, lf / (double) span);
					show = f > birth && f < F - 1 - st.hideTail;
				}
				out[k][f] = sample(st, p, t, show, lf);
			}
		}
		return out;
	}

	static Sample sample(Settings st, Particle p, double t, boolean show, int lifeFrame) {
		Sample s = new Sample();
		double x, y, z, curve = 1;
		double d = st.distance;
		switch (st.pattern) {
		case ORBIT: {
			double a = p.ang + t * Math.PI * 2;
			double rr = 5 * d;
			x = Math.cos(a) * rr;
			z = Math.sin(a) * rr;
			y = -t * (2 + 3 * p.a3) * d;
			break;
		}
		case RAIN:
			x = Math.cos(p.ang) * p.r * 0.6;
			z = Math.sin(p.ang) * p.r * 0.6;
			y = (-10 + 14 * t) * d;
			break;
		case SPARKLE:
			x = Math.cos(p.ang) * p.r;
			z = Math.sin(p.ang) * p.r;
			y = (p.a3 - 0.5) * 10 * d - t;
			break;
		case SMOKE:
			x = Math.cos(p.ang) * p.r * 0.35 * t + Math.sin(t * Math.PI * 2 + p.a4 * 6) * 0.8 * d;
			z = Math.sin(p.ang) * p.r * 0.35 * t;
			y = -(6 + 6 * p.a3) * d * t;
			curve = 0.6 + 1.2 * t;
			break;
		case BEHIND:
			x = (p.a4 - 0.5) * 6 * d;
			z = (2 + 8 * t) * d;
			y = (p.a3 - 0.5) * 4 * d + t * 3 * d;
			break;
		case BURST:
		default: {
			double e = 1 - Math.pow(1 - t, 2);
			x = Math.cos(p.ang) * p.r * e;
			z = Math.sin(p.ang) * p.r * e;
			y = -p.rise * t;
			break;
		}
		}
		s.px = clampPos(x);
		s.py = clampPos(y);
		s.pz = clampPos(z);
		double scale;
		switch (st.fade) {
		case BLINK:
			scale = p.sizeK * curve;
			// blink: visible 2 frames, hidden 2 frames (inside the life)
			if (show && ((lifeFrame / 2) % 2) == 1)show = false;
			break;
		case DARKEN:
			scale = p.sizeK * curve * Math.min(1, 5 * t);
			break;
		case SHRINK:
		default:
			scale = p.sizeK * curve * Math.sin(Math.PI * t);
			break;
		}
		if (!(scale > MIN_SCALE) || t <= 0 || t >= 1)scale = MIN_SCALE;
		s.scale = round3((float) Math.min(scale, 40));
		s.show = show;
		double spinAmt = Math.max(0, Math.min(1, st.spin)) * 170;
		s.rx = round3((float) p.tilt);
		s.ry = round3((float) (p.base + spinAmt * (t - 0.5)));
		s.rz = 0;
		if (st.colorA != null) {
			int[] a = st.colorA;
			int[] b = st.colorB != null ? st.colorB : st.colorA;
			double dark = st.fade == Fade.DARKEN ? (1 - t) : 1;
			s.r = clampColor((a[0] + (b[0] - a[0]) * t) * dark);
			s.g = clampColor((a[1] + (b[1] - a[1]) * t) * dark);
			s.b = clampColor((a[2] + (b[2] - a[2]) * t) * dark);
		}
		return s;
	}

	static float clampPos(double v) {
		if (Double.isNaN(v))return 0;
		return round3((float) Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, v)));
	}

	static float round3(float v) {
		return Math.round(v * 1000f) / 1000f;
	}

	static int clampColor(double v) {
		long c = Math.round(v);
		return (int) (c < 0 ? 0 : c > 255 ? 255 : c);
	}

	// ------------------------------------------------------------------ frames (FORMAT limits)

	/** result of {@link #frames(double, int)} */
	public static final class FramePlan {
		public int frames, fps, durationMs;
		/** true when fps was lowered to stay within 255 frames / 32.767 s (format limits) */
		public boolean adjusted;
	}

	/**
	 * Frames for a life of lifeSec at fps: F = round(life * fps) clamped to [2, 255]; when life * fps &gt; 255 the fps
	 * is lowered (format limit, explained to the user). Duration capped at 32 767 ms.
	 */
	public static FramePlan frames(double lifeSec, int fps) {
		FramePlan p = new FramePlan();
		double life = Math.max(0.1, Math.min(MAX_DURATION_MS / 1000.0, lifeSec));
		int f = Math.max(1, fps);
		if (Math.round(life * f) > MAX_FRAMES) {
			f = Math.max(1, (int) Math.floor(MAX_FRAMES / life));
			p.adjusted = true;
		}
		p.fps = f;
		p.frames = (int) Math.max(2, Math.min(MAX_FRAMES, Math.round(life * f)));
		p.durationMs = (int) Math.min(MAX_DURATION_MS, Math.max(1, Math.round(life * 1000)));
		return p;
	}

	// ------------------------------------------------------------------ budget estimate (advisory)

	/** bytes CPM writes per animated part per frame: pos 6 + rot 6 + scale 6 + vis 1/8 (+ colour 3) */
	public static double bytesPerPartFrame(boolean color) {
		return 18.125 + (color ? 3 : 0);
	}

	/**
	 * Estimated growth of the model definition (raw bytes) for n particles x frames (+ the group element): measured
	 * on the real exporter, ~21.1 B per part-frame with colour, ~72 B per particle (element + channel blocks).
	 */
	public static int estimateAddedBytes(int n, int frames, boolean color, int animations) {
		double perPart = color ? 72 : 66;
		return (int) Math.round(n * (double) frames * bytesPerPartFrame(color) * Math.max(1, animations) + n * perPart + 40 + 30 * Math.max(1, animations));
	}

	/** CPM's maxLink check works on the BASE64 download: KB = floor(4 * ceil(raw / 3) / 1024) */
	public static int downloadKB(int rawBytes) {
		if (rawBytes <= 0)return 0;
		long b64 = 4L * ((rawBytes + 2L) / 3L);
		return (int) (b64 / 1024L);
	}

	/** true when the model data no longer fits inline in the file (CPM asks for a gist / paste link) */
	public static boolean needsLink(int rawBytes) {
		return rawBytes > INLINE_LIMIT_BYTES;
	}

	/** MEDIUM (default) safety profile verdict for other players: cubes, link download size (advisory) */
	public static boolean mediumLoads(int cubes, int rawBytes, int maxTextureSide) {
		if (cubes > MEDIUM_CUBES)return false;
		if (maxTextureSide > MEDIUM_TEXTURE)return false;
		return !needsLink(rawBytes) || downloadKB(rawBytes) <= MEDIUM_LINK_KB;
	}

	/** stable hash of the generated keyframe values (detects manual edits before 'Editar partículas' regenerates) */
	public static String hash(double[] values) {
		long h = 1125899906842597L;
		for (double v : values) {
			long r = Math.round(v * 1000);
			h = 31 * h + (r ^ (r >>> 32));
		}
		return Long.toHexString(h) + ":" + values.length;
	}
}
