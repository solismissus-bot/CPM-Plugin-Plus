package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;

/**
 * CPM+ (W4-RIG): rest-pose geometry of the Blockbench outliner, computed from the project data (never from the
 * animated preview meshes). Blockbench stores from / to / origin of every node in absolute coordinates as if no
 * parent were rotated; the shown position of a point is obtained by rotating it about the node's own origin, then
 * about each ancestor group's origin (innermost first). Rotations are Euler degrees in Blockbench's (THREE) 'ZYX'
 * order: M = Rz * Ry * Rx.
 */
public final class RigGeom {

	private RigGeom() {
	}

	/** rotation matrix (row major, 9 values) of Blockbench Euler degrees (order ZYX: M = Rz * Ry * Rx) */
	public static double[] rot(double rxDeg, double ryDeg, double rzDeg) {
		double x = Math.toRadians(rxDeg), y = Math.toRadians(ryDeg), z = Math.toRadians(rzDeg);
		double cx = Math.cos(x), sx = Math.sin(x), cy = Math.cos(y), sy = Math.sin(y), cz = Math.cos(z), sz = Math.sin(z);
		return new double[] {
				cy * cz, sx * sy * cz - cx * sz, cx * sy * cz + sx * sz,
				cy * sz, sx * sy * sz + cx * cz, cx * sy * sz - sx * cz,
				-sy, sx * cy, cx * cy
		};
	}

	public static double[] rot(JsVec3 r) {
		return r == null ? rot(0, 0, 0) : rot(r.x, r.y, r.z);
	}

	/** a * b */
	public static double[] mul(double[] a, double[] b) {
		double[] r = new double[9];
		for (int i = 0; i < 3; i++)
			for (int j = 0; j < 3; j++)
				r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
		return r;
	}

	public static double[] apply(double[] m, double[] v) {
		return new double[] {
				m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
				m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
				m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
		};
	}

	/** Euler degrees (Blockbench order ZYX) of a rotation matrix */
	public static double[] euler(double[] m) {
		double sy = -m[6];
		if (sy > 1)sy = 1;
		if (sy < -1)sy = -1;
		double y = Math.asin(sy);
		double x, z;
		if (Math.abs(m[6]) < 0.9999999) {
			x = Math.atan2(m[7], m[8]);
			z = Math.atan2(m[3], m[0]);
		} else {
			x = 0;
			z = Math.atan2(-m[1], m[4]);
		}
		return new double[] {Math.toDegrees(x), Math.toDegrees(y), Math.toDegrees(z)};
	}

	public static double[] vec(JsVec3 v) {
		return v == null ? new double[3] : new double[] {v.x, v.y, v.z};
	}

	public static boolean isZero(JsVec3 r) {
		return r == null || (r.x == 0 && r.y == 0 && r.z == 0);
	}

	/** p rotated by the Euler rotation r about origin o */
	public static double[] rotateAbout(double[] p, JsVec3 r, JsVec3 o) {
		if (isZero(r))return p;
		double[] m = rot(r);
		double[] d = {p[0] - o.x, p[1] - o.y, p[2] - o.z};
		double[] q = apply(m, d);
		return new double[] {q[0] + o.x, q[1] + o.y, q[2] + o.z};
	}

	/** the parent group of a node, or null at the outliner root */
	public static Group parentOf(Object node) {
		Group p = node instanceof Group ? ((Group) node).parent : node instanceof Cube ? ((Cube) node).parent : null;
		return p == null || p == Group.ROOT || !(p instanceof Group) ? null : p;
	}

	/**
	 * Shown rest-pose position of point p that is stored in the coordinates of node 'from' (a cube: its own
	 * rotation is applied first; a group: its rotation too), then every ancestor up to but NOT including stopAt
	 * (null = up to the outliner root).
	 */
	public static double[] toSpace(double[] p, Object from, Group stopAt) {
		double[] q = p;
		if (from instanceof Cube) {
			Cube c = (Cube) from;
			q = rotateAbout(q, c.rotation, c.origin);
		} else if (from instanceof Group) {
			if (from == stopAt)return q;
			Group g = (Group) from;
			q = rotateAbout(q, g.rotation, g.origin);
		}
		Group g = parentOf(from);
		int guard = 0;
		while (g != null && g != stopAt && guard++ < 10000) {
			q = rotateAbout(q, g.rotation, g.origin);
			g = parentOf(g);
		}
		return q;
	}

	/** the 8 corners of a cube's box (absolute Blockbench coordinates, before any rotation); inflate optional */
	public static List<double[]> corners(Cube c, boolean inflate) {
		double inf = inflate ? c.inflate : 0;
		double x0 = Math.min(c.from.x, c.to.x) - inf, x1 = Math.max(c.from.x, c.to.x) + inf;
		double y0 = Math.min(c.from.y, c.to.y) - inf, y1 = Math.max(c.from.y, c.to.y) + inf;
		double z0 = Math.min(c.from.z, c.to.z) - inf, z1 = Math.max(c.from.z, c.to.z) + inf;
		List<double[]> l = new ArrayList<>(8);
		for (int i = 0; i < 8; i++) {
			l.add(new double[] {(i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1});
		}
		return l;
	}

	/** corners of the cube as shown, in the space of stopAt (null = world) */
	public static List<double[]> shownCorners(Cube c, boolean inflate, Group stopAt) {
		List<double[]> l = corners(c, inflate);
		List<double[]> out = new ArrayList<>(8);
		for (double[] p : l)out.add(toSpace(p, c, stopAt));
		return out;
	}

	/** axis aligned box */
	public static final class Box {
		public final double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
		public final double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};

		public Box() {
		}

		public Box(Box o) {
			for (int i = 0; i < 3; i++) {
				min[i] = o.min[i];
				max[i] = o.max[i];
			}
		}

		public Box add(double[] p) {
			for (int i = 0; i < 3; i++) {
				if (p[i] < min[i])min[i] = p[i];
				if (p[i] > max[i])max[i] = p[i];
			}
			return this;
		}

		public Box add(Box b) {
			if (b.isEmpty())return this;
			add(b.min);
			add(b.max);
			return this;
		}

		public Box addAll(List<double[]> pts) {
			for (double[] p : pts)add(p);
			return this;
		}

		public boolean isEmpty() {
			return min[0] > max[0];
		}

		public double size(int a) {
			return isEmpty() ? 0 : max[a] - min[a];
		}

		public double center(int a) {
			return (min[a] + max[a]) / 2;
		}

		public double[] center() {
			return new double[] {center(0), center(1), center(2)};
		}

		/** volume with every side at least minSide (flat planes still weigh something) */
		public double weight(double minSide) {
			return Math.max(size(0), minSide) * Math.max(size(1), minSide) * Math.max(size(2), minSide);
		}

		/** true when the boxes overlap or are closer than gap on every axis */
		public boolean touches(Box o, double gap) {
			for (int i = 0; i < 3; i++) {
				if (o.min[i] > max[i] + gap || o.max[i] < min[i] - gap)return false;
			}
			return true;
		}

		public Box grow(double[] by) {
			Box b = new Box(this);
			for (int i = 0; i < 3; i++) {
				b.min[i] -= by[i];
				b.max[i] += by[i];
			}
			return b;
		}

		public boolean contains(double[] p) {
			for (int i = 0; i < 3; i++)if (p[i] < min[i] - 1e-9 || p[i] > max[i] + 1e-9)return false;
			return true;
		}

		/** the point of this box closest to p */
		public double[] clamp(double[] p) {
			double[] r = new double[3];
			for (int i = 0; i < 3; i++)r[i] = Math.max(min[i], Math.min(max[i], p[i]));
			return r;
		}

		/** smallest distance between the boxes (0 when they touch or overlap) */
		public double distance(Box o) {
			double s = 0;
			for (int i = 0; i < 3; i++) {
				double d = Math.max(0, Math.max(o.min[i] - max[i], min[i] - o.max[i]));
				s += d * d;
			}
			return Math.sqrt(s);
		}

		public double distance(double[] p) {
			double[] c = clamp(p);
			double s = 0;
			for (int i = 0; i < 3; i++)s += (c[i] - p[i]) * (c[i] - p[i]);
			return Math.sqrt(s);
		}
	}

	/** Blockbench-friendly rounding of a joint / pivot value: quarter pixels when it already is one, else 0.05 */
	public static double snap(double v) {
		double q = v * 4;
		if (Math.abs(q - Math.round(q)) < 0.01)return clean(Math.round(q) / 4.0);
		return clean(Math.round(v * 20) / 20.0);
	}

	/** no -0, no float noise */
	public static double clean(double v) {
		double r = Math.round(v * 10000.0) / 10000.0;
		return r == 0 ? 0 : r;
	}

	/** short number text: 12, 1.5, -0.25 */
	public static String fmt(double v) {
		double r = clean(v);
		if (r == Math.rint(r) && Math.abs(r) < 1e15)return Long.toString((long) r);
		String s = Double.toString(r);
		if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0)return s;
		return s;
	}

	public static String fmt(double[] v) {
		return fmt(v[0]) + ", " + fmt(v[1]) + ", " + fmt(v[2]);
	}

	public static double dist(double[] a, double[] b) {
		double s = 0;
		for (int i = 0; i < 3; i++)s += (a[i] - b[i]) * (a[i] - b[i]);
		return Math.sqrt(s);
	}
}
