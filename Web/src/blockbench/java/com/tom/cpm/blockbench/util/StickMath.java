package com.tom.cpm.blockbench.util;

/**
 * CPM+ (W5-STICK, G36 / G6): pure math of 'Grudar na parte do corpo' (no Blockbench / GWT types, javac-testable).
 * <ul>
 * <li>Rigid transforms as 4x4 matrices (double[16], ROW-major: m[r*4+c]).</li>
 * <li>Blockbench rotations are Euler angles in degrees, order 'ZYX' (three.js): R = Rz(z) * Ry(y) * Rx(x).</li>
 * <li>In Blockbench's bone_rig format a node (group or cube) sits at T(origin - parent.origin) * R(rotation) inside its
 * parent; outliner-root nodes use parent origin 0. Cube corners are (from|to -/+ inflate) - origin in the cube's
 * frame.</li>
 * <li>{@link #classify}: the suggestion rule of RootSuggest (green / orange / nearest).</li>
 * </ul>
 */
public final class StickMath {
	private StickMath() {
	}

	// ------------------------------------------------------------------ matrices

	public static double[] identity() {
		return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	public static double[] translation(double x, double y, double z) {
		double[] m = identity();
		m[3] = x;
		m[7] = y;
		m[11] = z;
		return m;
	}

	/** R = Rz(z) * Ry(y) * Rx(x), angles in degrees (three.js Euler order 'ZYX', Blockbench's group / cube rotation) */
	public static double[] rotationZYX(double xDeg, double yDeg, double zDeg) {
		double x = Math.toRadians(xDeg), y = Math.toRadians(yDeg), z = Math.toRadians(zDeg);
		double a = Math.cos(x), b = Math.sin(x);
		double c = Math.cos(y), d = Math.sin(y);
		double e = Math.cos(z), f = Math.sin(z);
		double ae = a * e, af = a * f, be = b * e, bf = b * f;
		double[] m = identity();
		m[0] = c * e;
		m[1] = be * d - af;
		m[2] = ae * d + bf;
		m[4] = c * f;
		m[5] = bf * d + ae;
		m[6] = af * d - be;
		m[8] = -d;
		m[9] = b * c;
		m[10] = a * c;
		return m;
	}

	/** T(pos) * R(rot) */
	public static double[] local(double[] pos, double[] rotDeg) {
		return mul(translation(pos[0], pos[1], pos[2]), rotationZYX(rotDeg[0], rotDeg[1], rotDeg[2]));
	}

	public static double[] mul(double[] a, double[] b) {
		double[] r = new double[16];
		for (int i = 0; i < 4; i++) {
			for (int j = 0; j < 4; j++) {
				double s = 0;
				for (int k = 0; k < 4; k++)s += a[i * 4 + k] * b[k * 4 + j];
				r[i * 4 + j] = s;
			}
		}
		return r;
	}

	/** inverse of a rigid transform (rotation + translation) */
	public static double[] invRigid(double[] m) {
		double[] r = identity();
		for (int i = 0; i < 3; i++)for (int j = 0; j < 3; j++)r[i * 4 + j] = m[j * 4 + i];
		for (int i = 0; i < 3; i++) {
			r[i * 4 + 3] = -(r[i * 4] * m[3] + r[i * 4 + 1] * m[7] + r[i * 4 + 2] * m[11]);
		}
		return r;
	}

	public static double[] apply(double[] m, double[] p) {
		return new double[] {
				m[0] * p[0] + m[1] * p[1] + m[2] * p[2] + m[3],
				m[4] * p[0] + m[5] * p[1] + m[6] * p[2] + m[7],
				m[8] * p[0] + m[9] * p[1] + m[10] * p[2] + m[11]
		};
	}

	public static double[] translationOf(double[] m) {
		return new double[] {m[3], m[7], m[11]};
	}

	/**
	 * Euler angles (degrees, order 'ZYX') of the rotation part of m: the exact formula of three.js
	 * Euler.setFromRotationMatrix for 'ZYX' (what Blockbench itself decomposes to). Scale is discarded (rigid input).
	 */
	public static double[] eulerZYX(double[] m) {
		double m11 = m[0], m12 = m[1], m21 = m[4], m22 = m[5], m31 = m[8], m32 = m[9], m33 = m[10];
		double y = Math.asin(-clamp(m31, -1, 1));
		double x, z;
		if (Math.abs(m31) < 0.9999999) {
			x = Math.atan2(m32, m33);
			z = Math.atan2(m21, m11);
		} else {
			x = 0;
			z = Math.atan2(-m12, m22);
		}
		return new double[] {noNegZero(Math.toDegrees(x)), noNegZero(Math.toDegrees(y)), noNegZero(Math.toDegrees(z))};
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	public static double noNegZero(double v) {
		return v == 0 ? 0 : v;
	}

	/** v rounded to a grid step (e.g. 0.01), no -0 */
	public static double snap(double v, double step) {
		double r = Math.round(v / step) * step;
		// kill binary noise of the multiplication (0.30000000000000004)
		r = Math.round(r * 1e9) / 1e9;
		return noNegZero(r);
	}

	public static boolean isZeroRot(double[] r) {
		return r == null || (Math.abs(r[0]) < 1e-9 && Math.abs(r[1]) < 1e-9 && Math.abs(r[2]) < 1e-9);
	}

	/** largest distance between m1*p and m2*p over the points */
	public static double maxDiff(double[] m1, double[] m2, double[][] points) {
		double mx = 0;
		for (double[] p : points) {
			double[] a = apply(m1, p), b = apply(m2, p);
			double d = Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
			if (d > mx)mx = d;
		}
		return mx;
	}

	/** the 8 corners of the box [min, max] */
	public static double[][] corners(double[] min, double[] max) {
		double[][] r = new double[8][];
		for (int i = 0; i < 8; i++) {
			r[i] = new double[] {(i & 1) == 0 ? min[0] : max[0], (i & 2) == 0 ? min[1] : max[1], (i & 4) == 0 ? min[2] : max[2]};
		}
		return r;
	}

	// ------------------------------------------------------------------ reparent (keep the world pose)

	/** result of {@link #reparent} */
	public static final class Placement {
		/** new origin (absolute, Blockbench coordinates) and rotation (degrees) of the moved node */
		public double[] origin, rotation;
		/** origin shift that every descendant (origins, cube from / to) gets too */
		public double[] delta;
		/** true when the values were rounded (0.01 px / 0.1 degree) */
		public boolean rounded;
		/** largest movement of a corner caused by the rounding (px) */
		public double residual;
		/** true when no rotation was involved (plain move, nothing changes) */
		public boolean plain;
	}

	/** residual (px) below which the rounded values (0.01 px, 0.1 degree) are used */
	public static final double ROUND_RESIDUAL = 0.002;

	/**
	 * New origin / rotation of a node moved from parent A to target T without moving in the world:
	 * M = inverse(W_T) * W_A * L_node, L_node = T(origin - A.origin) * R(rotation).
	 * @param wOldParent world matrix of the old parent (identity for the outliner root)
	 * @param oldParentOrigin origin of the old parent (0 for the outliner root)
	 * @param wTarget world matrix of the target group
	 * @param targetOrigin origin of the target group
	 * @param plain true when neither the old nor the new parent chain has any rotation (then nothing changes)
	 * @param worldPoints world positions of the corners of every cube that moves (for the rounding residual)
	 */
	public static Placement reparent(double[] wOldParent, double[] oldParentOrigin, double[] origin, double[] rotation,
			double[] wTarget, double[] targetOrigin, boolean plain, double[][] worldPoints) {
		Placement p = new Placement();
		if (plain) {
			p.origin = new double[] {origin[0], origin[1], origin[2]};
			p.rotation = new double[] {rotation[0], rotation[1], rotation[2]};
			p.delta = new double[3];
			p.plain = true;
			return p;
		}
		double[] lNode = local(sub(origin, oldParentOrigin), rotation);
		double[] world = mul(wOldParent, lNode);
		double[] m = mul(invRigid(wTarget), world);
		double[] t = translationOf(m);
		double[] e = eulerZYX(m);
		double[] exactOrigin = add(targetOrigin, t);
		// rounded candidate: what the CPM export keeps anyway, used only when nothing visibly moves
		double[] ro = {snap(exactOrigin[0], 0.01), snap(exactOrigin[1], 0.01), snap(exactOrigin[2], 0.01)};
		double[] rr = {snap(e[0], 0.1), snap(e[1], 0.1), snap(e[2], 0.1)};
		double res = maxDiff(mul(wTarget, local(sub(ro, targetOrigin), rr)), world, toLocal(world, worldPoints));
		if (res <= ROUND_RESIDUAL) {
			p.origin = ro;
			p.rotation = rr;
			p.rounded = true;
			p.residual = res;
		} else {
			double[] fo = {snap(exactOrigin[0], 1e-4), snap(exactOrigin[1], 1e-4), snap(exactOrigin[2], 1e-4)};
			double[] fr = {snap(e[0], 1e-4), snap(e[1], 1e-4), snap(e[2], 1e-4)};
			p.origin = fo;
			p.rotation = fr;
			p.residual = maxDiff(mul(wTarget, local(sub(fo, targetOrigin), fr)), world, toLocal(world, worldPoints));
		}
		p.delta = sub(p.origin, origin);
		return p;
	}

	/** world points converted into the frame of world (so maxDiff(newWorld, world, local) measures the movement) */
	private static double[][] toLocal(double[] world, double[][] worldPoints) {
		double[] inv = invRigid(world);
		double[][] r = new double[worldPoints == null ? 0 : worldPoints.length][];
		for (int i = 0; i < r.length; i++)r[i] = apply(inv, worldPoints[i]);
		return r;
	}

	public static double[] add(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	public static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	// ------------------------------------------------------------------ suggestion

	/** distance from point p to the box [min, max] (0 inside) */
	public static double boxDistance(double[] p, double[] min, double[] max) {
		double s = 0;
		for (int i = 0; i < 3; i++) {
			double d = p[i] < min[i] ? min[i] - p[i] : p[i] > max[i] ? p[i] - max[i] : 0;
			s += d * d;
		}
		return Math.sqrt(s);
	}

	/** how sure the suggestion is */
	public static enum Kind {
		/** exactly one body part box contains the piece's centre (1 px margin) */
		GREEN,
		/** the two best parts are within 1 px of each other: the user must confirm */
		ORANGE,
		/** outside every box: the nearest part */
		NEAREST,
		/** no geometry (empty group): nothing to measure */
		NONE
	}

	/** result of {@link #classify} */
	public static final class Choice {
		public Kind kind;
		/** index of the suggested part, and of the runner-up (-1 when none) */
		public int best = -1, second = -1;
		public double bestDist, secondDist;
	}

	/** margin (px) of the 'inside the box' test and of the 'too close to call' test */
	public static final double MARGIN = 1.0;

	/**
	 * GREEN when exactly one box contains the centre (distance &lt;= 1 px); ORANGE when the two smallest distances are
	 * within 1 px of each other (also when several boxes contain it); NEAREST otherwise. Ties prefer preferIndex (the
	 * body: loose cubes follow it in game).
	 */
	public static Choice classify(double[] dist, int preferIndex) {
		Choice c = new Choice();
		int n = dist.length;
		if (n == 0) {
			c.kind = Kind.NONE;
			return c;
		}
		int best = -1, second = -1;
		for (int i = 0; i < n; i++) {
			if (best < 0 || before(dist, i, best, preferIndex)) {
				second = best;
				best = i;
			} else if (second < 0 || before(dist, i, second, preferIndex)) {
				second = i;
			}
		}
		c.best = best;
		c.second = second;
		c.bestDist = dist[best];
		c.secondDist = second >= 0 ? dist[second] : Double.POSITIVE_INFINITY;
		int inside = 0;
		for (int i = 0; i < n; i++)if (dist[i] <= MARGIN + 1e-9)inside++;
		if (inside == 1)c.kind = Kind.GREEN;
		else if (second >= 0 && c.secondDist - c.bestDist < MARGIN - 1e-9)c.kind = Kind.ORANGE;
		else if (inside > 1)c.kind = Kind.ORANGE;
		else c.kind = Kind.NEAREST;
		return c;
	}

	/** true when part i ranks before part j: smaller distance; equal distances: preferIndex, then the lower index */
	private static boolean before(double[] dist, int i, int j, int preferIndex) {
		if (Math.abs(dist[i] - dist[j]) > 1e-9)return dist[i] < dist[j];
		if (i == preferIndex)return true;
		if (j == preferIndex)return false;
		return i < j;
	}
}
