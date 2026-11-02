package com.tom.cpm.blockbench.tools.anim;

/**
 * CPM+ (W3-ANIMGEN, F22 'Criar seletor de cor'): the math of a colour slider. Pure Java (javac-testable).
 * <p>
 * The slider is a looping VALUE_LAYER animation of length {@link #LENGTH} with one cpm_color key per swatch on EVERY
 * chosen part (a part without a key at some step would turn black there). Steps: key i at i*L/k with 'step'
 * interpolation (export: NO_INTERPOLATE, N = k frames); smooth: key i at i*L/(k-1), linear (export: LINEAR_SINGLE,
 * N = k frames incl. the end). maxValue = k-1, so the in-game slider has exactly one position per swatch; the
 * default value index/(k-1) selects the start colour. In game the slider position j travels as a byte (j*255/(k-1)),
 * CPM plays the animation at time byte/256*1000 ms ({@link #cpmMillis}) and shows frame floor(t/1000*N): swatch j for
 * every j and every k &lt;= 16 ({@link #cpmFrame}).
 */
public final class ColorSlider {
	public static final int MIN = 2, MAX = 16;
	public static final double LENGTH = 1;

	private ColorSlider() {
	}

	/** key times (seconds) of k swatches */
	public static double[] keyTimes(int k, boolean smooth) {
		check(k);
		double[] t = new double[k];
		for (int i = 0; i < k; i++)t[i] = smooth ? i * LENGTH / (k - 1) : i * LENGTH / k;
		return t;
	}

	public static int maxValue(int k) {
		check(k);
		return k - 1;
	}

	/** layerDefault (0..1) that selects swatch index */
	public static double layerDefault(int index, int k) {
		check(k);
		if (index < 0)index = 0;
		if (index > k - 1)index = k - 1;
		return index / (double) (k - 1);
	}

	public static void check(int k) {
		if (k < MIN || k > MAX)throw new IllegalArgumentException("swatches must be " + MIN + ".." + MAX);
	}

	/** '#rgb', '#rrggbb' or 'rrggbb' -&gt; {r, g, b} 0..255, null when not a colour */
	public static int[] parseHex(String s) {
		if (s == null)return null;
		String h = s.trim();
		if (h.startsWith("#"))h = h.substring(1);
		if (h.length() == 3)h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
		if (h.length() != 6)return null;
		int[] c = new int[3];
		for (int i = 0; i < 3; i++) {
			int a = hexDigit(h.charAt(i * 2)), b = hexDigit(h.charAt(i * 2 + 1));
			if (a < 0 || b < 0)return null;
			c[i] = a * 16 + b;
		}
		return c;
	}

	private static int hexDigit(char c) {
		if (c >= '0' && c <= '9')return c - '0';
		if (c >= 'a' && c <= 'f')return c - 'a' + 10;
		if (c >= 'A' && c <= 'F')return c - 'A' + 10;
		return -1;
	}

	public static String hex(int r, int g, int b) {
		return "#" + two(r) + two(g) + two(b);
	}

	private static String two(int v) {
		String s = Integer.toHexString(Math.max(0, Math.min(255, v)));
		return s.length() < 2 ? "0" + s : s;
	}

	/**
	 * time (ms) CPM plays a value layer at for slider position j of k (AnimationTrigger.ValueTrigger: the value travels
	 * as a byte, val = byte / 256, time = (long) (val * 1000); position j = byte round(j * 255 / (k-1)))
	 */
	public static long cpmMillis(int j, int k) {
		int b = (int) Math.round(j * 255.0 / (k - 1));
		return (long) (b / 256f * 1000);
	}

	/** frame CPM shows for slider position j of k with n frames (the animation's duration is 1000 ms: frame = floor(t / 1000 * n)) */
	public static int cpmFrame(int j, int k, int n) {
		long millis = cpmMillis(j, k);
		int f = (int) Math.floor((millis % 1000) / 1000.0 * n);
		return Math.max(0, Math.min(n - 1, f));
	}

	/** default swatches: red, green, blue, yellow, ... (k colours) */
	public static String defaultSwatch(int i) {
		String[] d = {"#e53935", "#43a047", "#1e88e5", "#fdd835", "#8e24aa", "#fb8c00", "#00acc1", "#6d4c41",
				"#ffffff", "#212121", "#d81b60", "#7cb342", "#3949ab", "#c0ca33", "#5e35b1", "#f4511e"};
		return d[Math.max(0, i) % d.length];
	}
}
