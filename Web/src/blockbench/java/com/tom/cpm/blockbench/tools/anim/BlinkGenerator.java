package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CPM+ (W3-ANIMGEN, F4 'Gerar piscada'): the blink schedule. Pure Java (no Blockbench types, javac-testable in
 * qa/unit/w3animgen).
 * <p>
 * Every time is a WHOLE number of frames of 1/fps (fps = the snapping of the new animation): Blockbench and the CPM
 * export then agree exactly. The export writes visibility-only animations as NO_INTERPOLATE buckets k*L/N (CPM shows
 * frame (int) step), and Animation.interpolateVisible counts a key at t &lt;= sample, so a key placed exactly on the
 * grid switches exactly at its frame. The loop starts and ends with the eye open (key at 0 and at L, same state), so
 * the loop is seamless. CPM has no randomness at run time: the 'random' mode writes a long loop (8-12 s) whose blink
 * positions come from a seeded generator (the same seed always gives the same loop).
 */
public final class BlinkGenerator {
	/** most frames a generated blink loop may use (CPM's limit is 255 per animation; 5 frames margin) */
	public static final int MAX_FRAMES = 250;
	/** CPM's longest animation (ms are written as a signed short) */
	public static final double MAX_SECONDS = 32.767;
	public static final double RANDOM_MIN_S = 8, RANDOM_MAX_S = 12;

	private BlinkGenerator() {
	}

	/** one blink: the eye closes at frame 'close' and opens again at frame 'open' (open &gt; close) */
	public static final class Blink {
		public final int close, open;

		Blink(int close, int open) {
			this.close = close;
			this.open = open;
		}

		@Override
		public String toString() {
			return close + "-" + open;
		}
	}

	/** a whole loop */
	public static final class Schedule {
		public final int fps, lengthFrames, durFrames;
		public final List<Blink> blinks;
		public final boolean random;
		public final long seed;
		/** the requested blink was shorter than one frame: one frame was used */
		public final boolean durRaised;
		/** random mode: the 8 s minimum did not fit in {@link #MAX_FRAMES} at this fps (the loop is shorter) */
		public final boolean shortLoop;

		Schedule(int fps, int lengthFrames, int durFrames, List<Blink> blinks, boolean random, long seed, boolean durRaised, boolean shortLoop) {
			this.fps = fps;
			this.lengthFrames = lengthFrames;
			this.durFrames = durFrames;
			this.blinks = Collections.unmodifiableList(blinks);
			this.random = random;
			this.seed = seed;
			this.durRaised = durRaised;
			this.shortLoop = shortLoop;
		}

		/** loop length in seconds */
		public double length() {
			return lengthFrames / (double) fps;
		}

		/** seconds of a frame number */
		public double time(int frame) {
			return frame / (double) fps;
		}

		/**
		 * visibility keys [frame, 1 = visible / 0 = hidden] of the OPEN eye (closedEye = false) or of the CLOSED eye
		 * (closedEye = true): a key at 0, one at every close / open, and the last one at L (same state as at 0)
		 */
		public List<int[]> keys(boolean closedEye) {
			List<int[]> out = new ArrayList<>();
			int open = closedEye ? 0 : 1, shut = closedEye ? 1 : 0;
			out.add(new int[] {0, open});
			for (Blink b : blinks) {
				out.add(new int[] {b.close, shut});
				if (b.open < lengthFrames)out.add(new int[] {b.open, open});
			}
			out.add(new int[] {lengthFrames, open});
			return out;
		}

		/** true when the eye is closed at time t seconds (same rule as the export: key at frame &lt;= t counts) */
		public boolean closedAt(double t) {
			double f = t * fps;
			for (Blink b : blinks) {
				if (f + 1e-6 >= b.close && f + 1e-6 < b.open)return true;
			}
			return false;
		}

		/**
		 * frames the CPM export writes for this loop: the keys sit on the uniform grid of the greatest common divisor of
		 * their frame numbers, and a looping visibility animation is written as N = L / grid buckets
		 */
		public int exportFrames() {
			int g = lengthFrames;
			for (Blink b : blinks) {
				g = gcd(g, b.close);
				g = gcd(g, b.open);
			}
			return g <= 0 ? lengthFrames : lengthFrames / g;
		}

		@Override
		public String toString() {
			return "Schedule[fps=" + fps + ", L=" + lengthFrames + ", dur=" + durFrames + ", blinks=" + blinks + (random ? ", seed=" + seed : "") + "]";
		}
	}

	static int gcd(int a, int b) {
		a = Math.abs(a);
		b = Math.abs(b);
		while (b != 0) {
			int t = a % b;
			a = b;
			b = t;
		}
		return a;
	}

	/** frames a blink of dur seconds lasts at this fps (0 when shorter than half a frame) */
	public static int durFrames(double dur, int fps) {
		return (int) Math.round(dur * fps);
	}

	/** dur * fps &lt; 1: the blink would be shorter than one frame of the animation */
	public static boolean tooShort(double dur, int fps) {
		return dur * fps < 1 - 1e-9;
	}

	/** the smallest fps that makes a blink of dur seconds last at least one frame */
	public static int minFpsFor(double dur) {
		if (!(dur > 0))return 1;
		return Math.max(1, (int) Math.ceil(1 / dur - 1e-9));
	}

	/** frames between the two blinks of a double blink (at least 2) */
	public static int doubleGap(int fps) {
		return Math.max(2, (int) Math.round(0.12 * fps));
	}

	/**
	 * The loop for these settings.
	 * @param every  seconds between blinks (regular: also the loop length)
	 * @param dur    seconds the eye stays closed (at least one frame is always used)
	 * @param fps    frames per second of the new animation (its snapping)
	 * @param random seeded random positions in a long loop (8-12 s)
	 * @param dbl    double blinks: always in the regular mode, sometimes (seeded) in the random mode
	 * @throws IllegalArgumentException with a short reason when the settings can't make a loop
	 */
	public static Schedule schedule(double every, double dur, int fps, boolean random, boolean dbl, long seed) {
		if (fps < 1)throw new IllegalArgumentException("fps");
		if (!(every > 0) || !(dur > 0))throw new IllegalArgumentException("every/dur");
		int d = durFrames(dur, fps);
		// shorter than one frame: exactly one frame is used (never less), and the caller tells the user
		boolean raised = tooShort(dur, fps);
		if (raised || d < 1)d = 1;
		int gap = doubleGap(fps);
		if (!random) {
			int lf = (int) Math.round(every * fps);
			List<Blink> bl = new ArrayList<>();
			if (dbl) {
				// the second blink ends the loop, the first one is 'gap' frames before it
				int c2 = lf - d, o1 = c2 - gap, c1 = o1 - d;
				if (c1 < 1)throw new IllegalArgumentException("every too short");
				bl.add(new Blink(c1, o1));
				bl.add(new Blink(c2, lf));
			} else {
				int c = lf - d;
				if (c < 1)throw new IllegalArgumentException("every too short");
				bl.add(new Blink(c, lf));
			}
			return new Schedule(fps, lf, d, bl, false, seed, raised, false);
		}
		Rng rng = new Rng(seed);
		int minLf = (int) Math.ceil(RANDOM_MIN_S * fps - 1e-9), maxLf = Math.min((int) Math.floor(RANDOM_MAX_S * fps + 1e-9), MAX_FRAMES);
		boolean shortLoop = false;
		int lf;
		if (minLf > maxLf) {
			lf = maxLf;
			shortLoop = true;
		} else {
			lf = minLf + rng.nextInt(maxLf - minLf + 1);
		}
		int e = Math.max(d + 2, (int) Math.round(every * fps));
		List<Blink> bl = new ArrayList<>();
		int pos = Math.max(1, (int) Math.round(e * (0.4 + 0.6 * rng.nextDouble())));
		while (pos + d <= lf) {
			Blink b = new Blink(pos, pos + d);
			bl.add(b);
			int last = b.open;
			if (dbl && rng.nextDouble() < 0.35) {
				int c2 = b.open + gap;
				if (c2 + d <= lf) {
					bl.add(new Blink(c2, c2 + d));
					last = c2 + d;
				}
			}
			pos = last + Math.max(d + 2, (int) Math.round(e * (0.6 + 0.8 * rng.nextDouble())));
		}
		if (bl.isEmpty()) {
			if (lf - d < 1)throw new IllegalArgumentException("loop too short");
			bl.add(new Blink(lf - d, lf));
		}
		return new Schedule(fps, lf, d, bl, true, seed, raised, shortLoop);
	}

	/** problem of a schedule for CPM ({@code null} = fine): 'frames' (more than 255 export frames) or 'length' */
	public static String limitProblem(Schedule s) {
		if (s.length() > MAX_SECONDS + 1e-9)return "length";
		if (s.exportFrames() > 255)return "frames";
		return null;
	}

	/** small deterministic generator (same numbers in Java and in the compiled plugin: 64-bit LCG, long math only) */
	public static final class Rng {
		private long state;

		public Rng(long seed) {
			state = seed ^ 0x5DEECE66DL;
			next();
		}

		private long next() {
			state = state * 6364136223846793005L + 1442695040888963407L;
			return state;
		}

		/** 0 &lt;= value &lt; n */
		public int nextInt(int n) {
			if (n <= 1)return 0;
			long v = (next() >>> 33);
			return (int) (v % n);
		}

		/** 0 &lt;= value &lt; 1 */
		public double nextDouble() {
			return (next() >>> 11) * (1.0 / (1L << 53));
		}
	}
}
