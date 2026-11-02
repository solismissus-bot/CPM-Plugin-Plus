package com.tom.cpm.blockbench.util;

import java.util.LinkedHashMap;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;

/**
 * CPM+ (G42 / G57, shared): UV and size helpers for splitting cubes without changing what the skin looks like.
 * <ul>
 * <li>{@link #toPerFace(Cube)}: Blockbench's OWN box UV -&gt; per-face conversion (its preview controller writes the
 * box layout into the face UVs, then {@code setUVMode(false)} keeps them). The result is checked against
 * {@link #boxLayout} (a port of the same Blockbench layout) and refused when they differ - UVs are never guessed.</li>
 * <li>{@link #splitSide}: proportional V split of a side face keeping its U range, mirror and rotation (0 / 180).</li>
 * <li>{@link #grid01}: the default CPM export writes cube SIZES as tenths of a pixel (truncated) and positions as
 * 1/682 px; every size a splitting tool writes is put on the 0.1 grid so the game draws exactly that.</li>
 * </ul>
 * Face order of every float[4] here: {u1, v1, u2, v2} exactly like Blockbench's face.uv.
 */
public final class PlusUvUtil {
	public static final String[] FACES = {"north", "south", "east", "west", "up", "down"};
	public static final String[] SIDES = {"north", "south", "east", "west"};
	private static final double EPS = 1e-4;

	private PlusUvUtil() {
	}

	/**
	 * Port of Blockbench's preview_controller.updateUV for box UV cubes (cube.js): floored size (x, y, z), faces from
	 * the uv offset, mirror swaps east / west and flips every U range.
	 */
	public static Map<String, float[]> boxLayout(float sx, float sy, float sz, float uo, float vo, boolean mirror) {
		float x = (float) Math.floor(sx + 1e-6), y = (float) Math.floor(sy + 1e-6), z = (float) Math.floor(sz + 1e-6);
		// {face, fromU, fromV, sizeU, sizeV}
		float[][] l = {
				{0f, z, z, y},
				{z + x, z, z, y},
				{z + x, z, -x, -z},
				{z + 2 * x, 0f, -x, z},
				{2 * z + x, z, x, y},
				{z, z, x, y},
		};
		String[] names = {"east", "west", "up", "down", "south", "north"};
		if (mirror) {
			for (float[] f : l) {
				f[0] = f[0] + f[2];
				f[2] = -f[2];
			}
			float[] e = l[0];
			l[0] = l[1];
			l[1] = e;
		}
		Map<String, float[]> out = new LinkedHashMap<>();
		for (int i = 0; i < l.length; i++) {
			float[] f = l[i];
			out.put(names[i], new float[] {f[0] + uo, f[1] + vo, f[0] + f[2] + uo, f[1] + f[3] + vo});
		}
		return out;
	}

	/** the face uvs a cube shows now: the box layout for box UV cubes, else a copy of its per-face UVs */
	public static Map<String, float[]> currentUvs(Cube c) {
		if (c.box_uv) {
			return boxLayout(c.to.x - c.from.x, c.to.y - c.from.y, c.to.z - c.from.z,
					c.uv_offset != null ? c.uv_offset.x : 0, c.uv_offset != null ? c.uv_offset.y : 0, c.mirror_uv);
		}
		Map<String, float[]> out = new LinkedHashMap<>();
		for (String f : FACES) {
			CubeFace cf = face(c, f);
			if (cf == null || cf.uv == null)out.put(f, null);
			else out.put(f, new float[] {cf.uv.sx, cf.uv.sy, cf.uv.ex, cf.uv.ey});
		}
		return out;
	}

	/** Blockbench face by name (north / south / east / west / up / down) */
	public static CubeFace face(Cube c, String name) {
		if (c == null || c.faces == null)return null;
		switch (name) {
		case "north": return c.faces.north;
		case "south": return c.faces.south;
		case "east": return c.faces.east;
		case "west": return c.faces.west;
		case "up": return c.faces.up;
		case "down": return c.faces.down;
		default: return null;
		}
	}

	/** face rotation 0 / 90 / 180 / 270 */
	public static int rot(Cube c, String f) {
		CubeFace cf = face(c, f);
		return cf == null ? 0 : ((cf.rotation % 360) + 360) % 360;
	}

	/** hidden (texture null) or missing face */
	public static boolean disabled(Cube c, String f) {
		CubeFace cf = face(c, f);
		return cf == null || cf.isDisabled();
	}

	/**
	 * Converts a box UV cube to per-face UV with Blockbench's own code (face UVs = the box layout, then
	 * setUVMode(false)); verifies the result against {@link #boxLayout}. No-op for a per-face cube.
	 * @throws IllegalStateException when Blockbench produced other UVs than the box layout (never guessed)
	 */
	public static void toPerFace(Cube c) {
		if (!c.box_uv)return;
		Map<String, float[]> expect = currentUvs(c);
		nativeToPerFace(c);
		if (c.box_uv)throw new IllegalStateException("Blockbench did not switch the cube to per-face UV");
		for (String f : FACES) {
			CubeFace cf = face(c, f);
			float[] e = expect.get(f);
			if (cf == null || e == null)throw new IllegalStateException("face " + f + " has no UV");
			if (cf.isDisabled())continue;
			if (cf.uv == null || Math.abs(cf.uv.sx - e[0]) > EPS || Math.abs(cf.uv.sy - e[1]) > EPS || Math.abs(cf.uv.ex - e[2]) > EPS || Math.abs(cf.uv.ey - e[3]) > EPS)
				throw new IllegalStateException("box UV conversion of face " + f + " of '" + c.name + "' differs from the box layout"
						+ (cf.uv != null ? ": " + cf.uv.sx + "," + cf.uv.sy + "," + cf.uv.ex + "," + cf.uv.ey : "")
						+ " vs " + e[0] + "," + e[1] + "," + e[2] + "," + e[3]);
		}
	}

	private static native void nativeToPerFace(Cube c) /*-{
		// Blockbench writes the box layout into faces[*].uv when it updates the UV of a box UV cube
		if (c.preview_controller && c.mesh && c.mesh.geometry) c.preview_controller.updateUV(c);
		if (typeof c.setUVMode === 'function') c.setUVMode(false);
		else c.box_uv = false;
	}-*/;

	/** sets a face's UV (a new array) */
	public static void setUv(Cube c, String face, float[] uv) {
		CubeFace cf = face(c, face);
		if (cf == null)return;
		cf.uv = Cube.FaceUV.make(uv[0], uv[1], uv[2], uv[3]);
	}

	/**
	 * Splits a side face's UV at k of h rows counted from the TOP of the face (world +Y).
	 * @return {upper, lower} UV rects, or null when the face rotation is 90 / 270 (the split would cut along U)
	 */
	public static float[][] splitSide(float[] uv, int rotation, double k, double h) {
		int r = ((rotation % 360) + 360) % 360;
		if (r != 0 && r != 180)return null;
		double v1 = uv[1], v2 = uv[3];
		double t = k / h;
		if (r == 0) {
			// rotation 0: the top edge of the face shows v1
			float vc = (float) (v1 + (v2 - v1) * t);
			return new float[][] {{uv[0], uv[1], uv[2], vc}, {uv[0], vc, uv[2], uv[3]}};
		} else {
			// rotation 180: the top edge shows v2
			float vc = (float) (v2 - (v2 - v1) * t);
			return new float[][] {{uv[0], vc, uv[2], uv[3]}, {uv[0], uv[1], uv[2], vc}};
		}
	}

	/**
	 * The one-row strip of a side face right above (above = true) or right below the split (in the face's
	 * top-to-bottom direction): used for the inner faces at a cut, so a bent joint shows the skin's colour at the
	 * cut instead of a hole.
	 */
	public static float[] stripAt(float[] uv, int rotation, double k, double h, boolean above) {
		int r = ((rotation % 360) + 360) % 360;
		double v1 = uv[1], v2 = uv[3];
		double dir = Math.signum(v2 - v1);
		if (dir == 0)dir = 1;
		if (r == 180)dir = -dir;
		double vc = r == 180 ? v2 - (v2 - v1) * (k / h) : v1 + (v2 - v1) * (k / h);
		double other = above ? vc - dir : vc + dir;
		float a = (float) Math.min(vc, other), b = (float) Math.max(vc, other);
		return new float[] {uv[0], a, uv[2], b};
	}

	/** true when v is on the grid with this step (1 = whole UV units) */
	public static boolean isGrid(double v, double step) {
		double q = v / step;
		return Math.abs(q - Math.round(q)) < EPS;
	}

	/** v rounded to the 0.1 px grid of the CPM export (sizes are written as tenths of a pixel) */
	public static double grid01(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	/** true when v is (within float noise) a multiple of 0.1 */
	public static boolean onGrid01(double v) {
		return Math.abs(v * 10.0 - Math.round(v * 10.0)) < 1e-3;
	}

	/**
	 * Splits a box of length len (along one axis, inflate infl on every piece) into pieces at the given fractions of
	 * the DRAWN length (len + 2 infl; fractions[0] = 0, last = 1), so that the drawn pieces meet without overlap and
	 * every piece size lands on the 0.1 grid of the export. Returns the drawn boundaries (offsets from the drawn start)
	 * or null when it cannot be done (len + 2 infl not on the 0.1 grid, or a piece would be empty).
	 * The boundaries move at most 0.05 px from the exact texel boundaries (a stretch far below one texel).
	 */
	public static double[] drawnBoundaries(double len, double infl, double[] fractions) {
		double drawn = len + 2 * infl;
		if (!onGrid01(drawn) || !onGrid01(2 * infl))return null;
		double[] b = new double[fractions.length];
		for (int i = 0; i < fractions.length; i++)b[i] = grid01(fractions[i] * drawn);
		b[0] = 0;
		b[b.length - 1] = grid01(drawn);
		for (int i = 1; i < b.length; i++) {
			double size = b[i] - b[i - 1] - 2 * infl;
			if (size < 0.1 - 1e-6)return null;
		}
		return b;
	}

	/** area of a UV rect (absolute) */
	public static double area(float[] uv) {
		return uv == null ? 0 : Math.abs((uv[2] - uv[0]) * (uv[3] - uv[1]));
	}

	/** true when the rect lies inside [0, w] x [0, h] */
	public static boolean inside(float[] uv, double w, double h) {
		if (uv == null)return true;
		double minU = Math.min(uv[0], uv[2]), maxU = Math.max(uv[0], uv[2]);
		double minV = Math.min(uv[1], uv[3]), maxV = Math.max(uv[1], uv[3]);
		return minU >= -EPS && minV >= -EPS && maxU <= w + EPS && maxV <= h + EPS;
	}
}
