package com.tom.cpm.blockbench.util;

/**
 * CPM+ (W7-POSE, G59/G60): "turn a set of root parts around one common pivot" math, shared by the whole-body pose tool
 * (G59) and the death tilt compensation (G60). Pure Java (no GWT / Blockbench types) so it is unit tested with javac.
 * <p>
 * Conventions (verified against Blockbench 5.2 and the CPM exporter):
 * <ul>
 * <li>Blockbench scene space (px, Y up) maps to CPM model space (px, Y down) with x' = -x, y' = 24 - y, z' = z. Rotations
 * conjugate with diag(-1,-1,1): a Blockbench Euler (x, y, z) is the CPM Euler (-x, -y, z), exactly what the exporter
 * writes. So the math below is valid in either space as long as one space is used consistently.</li>
 * <li>Euler angles are in degrees, order 'ZYX': matrix = Rz(z) * Ry(y) * Rx(x) (three.js 'ZYX', Blockbench groups,
 * Minecraft ModelPart.rotationZYX, CPM roots).</li>
 * <li>A root part's world transform is T(pivot) * R(euler): roots are siblings at the model root, so local = world.</li>
 * </ul>
 * Matrices are double[9], row-major.
 */
public final class PivotMath {
	private PivotMath() {
	}

	public static final double DEG = Math.PI / 180.0;

	// ------------------------------------------------------------------ matrices

	public static double[] identity() {
		return new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1};
	}

	public static double[] mul(double[] a, double[] b) {
		double[] r = new double[9];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
			}
		}
		return r;
	}

	public static double[] apply(double[] m, double[] v) {
		return new double[] {
				m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
				m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
				m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
		};
	}

	public static double[] transpose(double[] m) {
		return new double[] {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
	}

	public static double[] rotX(double deg) {
		double c = Math.cos(deg * DEG), s = Math.sin(deg * DEG);
		return new double[] {1, 0, 0, 0, c, -s, 0, s, c};
	}

	public static double[] rotY(double deg) {
		double c = Math.cos(deg * DEG), s = Math.sin(deg * DEG);
		return new double[] {c, 0, s, 0, 1, 0, -s, 0, c};
	}

	public static double[] rotZ(double deg) {
		double c = Math.cos(deg * DEG), s = Math.sin(deg * DEG);
		return new double[] {c, -s, 0, s, c, 0, 0, 0, 1};
	}

	/** Rz(z) * Ry(y) * Rx(x), degrees */
	public static double[] eulerZYX(double x, double y, double z) {
		return mul(rotZ(z), mul(rotY(y), rotX(x)));
	}

	public static double[] eulerZYX(double[] e) {
		return eulerZYX(e[0], e[1], e[2]);
	}

	/** angle (degrees) of the rotation between a and b (0 = same orientation) */
	public static double angleBetween(double[] a, double[] b) {
		double[] r = mul(transpose(a), b);
		double tr = (r[0] + r[4] + r[8] - 1) / 2;
		if (tr > 1)tr = 1;
		if (tr < -1)tr = -1;
		return Math.acos(tr) / DEG;
	}

	// ------------------------------------------------------------------ Euler extraction with unwrapping

	/**
	 * Euler ZYX (degrees) of rotation matrix r, choosing among the two Euler solutions and every +-360 variant of each
	 * component the one closest to prev (null = no previous key: the principal solution). This is what keeps a 180
	 * degree yaw next to existing keys from turning into a 360 degree spin, and a pitch over 90 from flipping.
	 */
	public static double[] toEulerZYX(double[] r, double[] prev) {
		double sy = -r[6];
		if (sy > 1)sy = 1;
		if (sy < -1)sy = -1;
		double y = Math.asin(sy);
		double x, z;
		if (Math.abs(Math.cos(y)) > 1e-6) {
			x = Math.atan2(r[7], r[8]);
			z = Math.atan2(r[3], r[0]);
		} else {
			// gimbal lock: only x - z (y = +90) or x + z (y = -90) is defined, keep x of the previous key
			x = prev != null ? prev[0] * DEG : 0;
			if (sy > 0) {
				z = x - Math.atan2(r[1], r[4]);
			} else {
				z = Math.atan2(-r[1], r[4]) - x;
			}
		}
		double[] a = new double[] {x / DEG, y / DEG, z / DEG};
		if (prev == null)return a;
		double[] b = new double[] {a[0] + 180, 180 - a[1], a[2] + 180};
		double[] ua = unwrapTo(a, prev), ub = unwrapTo(b, prev);
		return dist(ua, prev) <= dist(ub, prev) + 1e-9 ? ua : ub;
	}

	private static double[] unwrapTo(double[] e, double[] prev) {
		double[] r = new double[3];
		for (int i = 0; i < 3; i++)r[i] = nearestTurn(e[i], prev[i]);
		return r;
	}

	/** v + 360k closest to ref */
	public static double nearestTurn(double v, double ref) {
		double k = Math.round((ref - v) / 360.0);
		return v + 360.0 * k;
	}

	private static double dist(double[] a, double[] b) {
		return Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);
	}

	// ------------------------------------------------------------------ axis-angle (partial rotations for ramps)

	/** {axisX, axisY, axisZ, angleDegrees} of rotation r (angle in [0, 180]) */
	public static double[] axisAngle(double[] r) {
		double tr = (r[0] + r[4] + r[8] - 1) / 2;
		if (tr > 1)tr = 1;
		if (tr < -1)tr = -1;
		double ang = Math.acos(tr);
		if (ang < 1e-9)return new double[] {1, 0, 0, 0};
		double ax, ay, az;
		if (Math.PI - ang > 1e-6) {
			double s = 2 * Math.sin(ang);
			ax = (r[7] - r[5]) / s;
			ay = (r[2] - r[6]) / s;
			az = (r[3] - r[1]) / s;
		} else {
			// 180 degrees: axis from the diagonal
			double xx = Math.sqrt(Math.max(0, (r[0] + 1) / 2));
			double yy = Math.sqrt(Math.max(0, (r[4] + 1) / 2));
			double zz = Math.sqrt(Math.max(0, (r[8] + 1) / 2));
			if (xx >= yy && xx >= zz) {
				ax = xx; ay = r[1] / (2 * xx); az = r[2] / (2 * xx);
			} else if (yy >= zz) {
				ay = yy; ax = r[1] / (2 * yy); az = r[5] / (2 * yy);
			} else {
				az = zz; ax = r[2] / (2 * zz); ay = r[5] / (2 * zz);
			}
		}
		double l = Math.sqrt(ax * ax + ay * ay + az * az);
		return new double[] {ax / l, ay / l, az / l, ang / DEG};
	}

	/** rotation of angleDeg around the unit axis (Rodrigues) */
	public static double[] fromAxisAngle(double ax, double ay, double az, double angleDeg) {
		double a = angleDeg * DEG;
		double c = Math.cos(a), s = Math.sin(a), t = 1 - c;
		return new double[] {
				t * ax * ax + c, t * ax * ay - s * az, t * ax * az + s * ay,
				t * ax * ay + s * az, t * ay * ay + c, t * ay * az - s * ax,
				t * ax * az - s * ay, t * ay * az + s * ax, t * az * az + c
		};
	}

	// ------------------------------------------------------------------ whole-body transform

	/**
	 * A rigid transform of the whole body: rotation r (matrix) around pivot c, then a translation off. The fraction s
	 * (0..1) scales the rotation angle (same axis) and the translation, for ramps.
	 */
	public static final class BodyTransform {
		public final double[] rot;
		public final double[] pivot;
		public final double[] offset;

		public BodyTransform(double[] rot, double[] pivot, double[] offset) {
			this.rot = rot;
			this.pivot = pivot;
			this.offset = offset;
		}

		/** from Euler ZYX degrees (the values typed in the dialog) */
		public static BodyTransform of(double[] eulerDeg, double[] pivot, double[] offset) {
			return new BodyTransform(eulerZYX(eulerDeg), pivot, offset);
		}

		public BodyTransform fraction(double s) {
			if (s >= 1)return this;
			double[] aa = axisAngle(rot);
			return new BodyTransform(fromAxisAngle(aa[0], aa[1], aa[2], aa[3] * s), pivot,
					new double[] {offset[0] * s, offset[1] * s, offset[2] * s});
		}

		/** rotation angle in degrees (0..180) */
		public double angle() {
			return axisAngle(rot)[3];
		}

		/** new world pivot of a part whose pivot is w */
		public double[] applyPoint(double[] w) {
			double[] d = new double[] {w[0] - pivot[0], w[1] - pivot[1], w[2] - pivot[2]};
			double[] r = PivotMath.apply(rot, d);
			return new double[] {r[0] + pivot[0] + offset[0], r[1] + pivot[1] + offset[1], r[2] + pivot[2] + offset[2]};
		}

		/**
		 * The part (world pivot w, Euler e) after this transform: {pivotX, pivotY, pivotZ, eulerX, eulerY, eulerZ}. The
		 * Euler solution is the one closest to prevEuler (or to e when prevEuler is null).
		 */
		public double[] applyPart(double[] w, double[] e, double[] prevEuler) {
			double[] p = applyPoint(w);
			double[] r = mul(rot, eulerZYX(e));
			double[] ne = toEulerZYX(r, prevEuler != null ? prevEuler : e);
			return new double[] {p[0], p[1], p[2], ne[0], ne[1], ne[2]};
		}
	}

	/** world position of a point given in the local space of a part (pivot w, Euler e) */
	public static double[] localToWorld(double[] w, double[] e, double[] local) {
		double[] r = apply(eulerZYX(e), local);
		return new double[] {r[0] + w[0], r[1] + w[1], r[2] + w[2]};
	}

	/**
	 * Is key b redundant between a and c (linear interpolation of the neighbours reproduces it within posTol px and
	 * rotTol degrees)? Values are {px, py, pz, rx, ry, rz}; ta < tb < tc.
	 */
	public static boolean collinear(double ta, double[] a, double tb, double[] b, double tc, double[] c, double posTol, double rotTol) {
		if (tc - ta < 1e-9)return false;
		double f = (tb - ta) / (tc - ta);
		for (int i = 0; i < 6; i++) {
			double v = a[i] + (c[i] - a[i]) * f;
			if (Math.abs(v - b[i]) > (i < 3 ? posTol : rotTol))return false;
		}
		return true;
	}
}
