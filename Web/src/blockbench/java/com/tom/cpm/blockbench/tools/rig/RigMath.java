package com.tom.cpm.blockbench.tools.rig;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.tools.model.RigGeom;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;

/**
 * CPM+ beta34 rig tools: rest-pose math shared by 'Reagir ao jogo' and 'Acompanhar parte do corpo'. Rotation matrices
 * are row major, Blockbench Euler degrees in (THREE) ZYX order, M = Rz * Ry * Rx (same as CPM: the export only flips
 * the X and Y signs, which conjugates every matrix the same way).
 */
public final class RigMath {
	private RigMath() {
	}

	public static final double[] ID = {1, 0, 0, 0, 1, 0, 0, 0, 1};

	public static double[] transpose(double[] m) {
		return new double[] {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]};
	}

	/** rotation of deg degrees about the (normalised) axis k (right hand rule) */
	public static double[] axisAngle(double[] k, double deg) {
		double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a), t = 1 - c;
		double x = k[0], y = k[1], z = k[2];
		return new double[] {
				t * x * x + c, t * x * y - s * z, t * x * z + s * y,
				t * x * y + s * z, t * y * y + c, t * y * z - s * x,
				t * x * z - s * y, t * y * z + s * x, t * z * z + c
		};
	}

	public static double[] rotX(double deg) {
		return RigGeom.rot(deg, 0, 0);
	}

	public static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	public static double len(double[] v) {
		return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
	}

	public static double[] norm(double[] v) {
		double l = len(v);
		return l < 1e-9 ? null : new double[] {v[0] / l, v[1] / l, v[2] / l};
	}

	/** rest rotation matrix of a group (its own Euler rotation) */
	public static double[] restRot(Group g) {
		return g == null ? ID : RigGeom.rot(g.rotation);
	}

	/** shown rest orientation of g in world space: every ancestor (outermost first) then g itself; identity for null */
	public static double[] worldRot(Group g) {
		List<Group> chain = new ArrayList<>();
		Group c = g;
		int guard = 0;
		while (c != null && guard++ < 10000) {
			chain.add(0, c);
			c = RigGeom.parentOf(c);
		}
		double[] m = ID;
		for (Group x : chain)m = RigGeom.mul(m, restRot(x));
		return m;
	}

	/** the parent group of g (null at the outliner root) */
	public static Group parent(Group g) {
		return RigGeom.parentOf(g);
	}

	/** each component of e moved by whole turns to within 180 degrees of ref (keys never take the long way) */
	public static double[] unwrapNear(double[] e, double[] ref) {
		double[] r = new double[3];
		for (int i = 0; i < 3; i++) {
			double d = e[i] - ref[i];
			d -= 360 * Math.rint(d / 360);
			r[i] = ref[i] + d;
		}
		return r;
	}

	/** Euler key (degrees, added to the rest Euler like CPM / Blockbench do) that turns rest into final */
	public static double[] keyFor(double[] restEuler, double[] finalMatrix) {
		double[] e = RigGeom.euler(finalMatrix);
		double[] u = unwrapNear(e, restEuler);
		return new double[] {clean(u[0] - restEuler[0]), clean(u[1] - restEuler[1]), clean(u[2] - restEuler[2])};
	}

	public static double clean(double v) {
		double r = Math.round(v * 10000) / 10000.0;
		return Math.abs(r) < 1e-9 ? 0 : r;
	}

	/** every cube inside g (any depth) */
	public static List<Cube> cubesOf(Group g) {
		List<Cube> out = new ArrayList<>();
		collect(g, out, 0);
		return out;
	}

	private static void collect(Group g, List<Cube> out, int depth) {
		if (g == null || g.children == null || depth > 200)return;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (n instanceof Cube)out.add((Cube) n);
			else if (n instanceof Group)collect((Group) n, out, depth + 1);
		}
	}

	/**
	 * Direction from the pivot of g to the middle of everything inside it, in the space of g's PARENT (the frame its
	 * own rotation lives in), at rest. null when g has no cubes or they sit on the pivot.
	 */
	public static double[] tipDir(Group g) {
		List<Cube> cubes = cubesOf(g);
		if (cubes.isEmpty())return null;
		Group stop = parent(g);
		double[] sum = new double[3];
		int n = 0;
		for (Cube c : cubes) {
			for (double[] p : RigGeom.shownCorners(c, false, stop)) {
				sum[0] += p[0];
				sum[1] += p[1];
				sum[2] += p[2];
				n++;
			}
		}
		if (n == 0)return null;
		double[] d = {sum[0] / n - g.origin.x, sum[1] / n - g.origin.y, sum[2] / n - g.origin.z};
		return len(d) < 0.05 ? null : d;
	}

	/** depth of g in the outliner (roots = 0) */
	public static int depth(Group g) {
		int d = 0;
		Group p = parent(g);
		while (p != null && d < 10000) {
			d++;
			p = parent(p);
		}
		return d;
	}

	/** index of g in Group.all (outliner order) */
	public static int order(Group g) {
		if (Group.all == null)return 0;
		for (int i = 0; i < Group.all.length; i++)if (Group.all[i] == g)return i;
		return 0;
	}

	/** parts from the base of a chain to its tip: ancestors before descendants, then outliner order */
	public static List<Group> chainOrder(List<Group> parts) {
		List<Group> l = new ArrayList<>(parts);
		l.sort((a, b) -> {
			int d = Integer.compare(depth(a), depth(b));
			return d != 0 ? d : Integer.compare(order(a), order(b));
		});
		return l;
	}

	/** true when node is inside ancestor (any depth) */
	public static boolean inside(Group node, Group ancestor) {
		Group p = parent(node);
		int guard = 0;
		while (p != null && guard++ < 10000) {
			if (p == ancestor)return true;
			p = parent(p);
		}
		return false;
	}

	/**
	 * Linear keys where no rotation step between two keys reaches maxStep degrees: CPM bakes the keys onto a frame
	 * grid and wraps rotations into 0..360, so a single step of 180 degrees or more plays the long way round in game.
	 * Midpoints are inserted (exact on the straight line) until every step is below maxStep.
	 */
	public static List<KeyData> splitWide(List<KeyData> keys, double maxStep, int snapping) {
		List<KeyData> out = new ArrayList<>();
		if (keys.isEmpty())return out;
		out.add(keys.get(0));
		for (int i = 1; i < keys.size(); i++) {
			KeyData a = keys.get(i - 1), b = keys.get(i);
			double step = 0;
			for (int ax = 0; ax < 3; ax++)step = Math.max(step, Math.abs(b.get(ax) - a.get(ax)));
			int parts = (int) Math.ceil(step / maxStep);
			if (step >= maxStep && parts < 2)parts = 2;
			for (int k = 1; k < parts; k++) {
				double f = k / (double) parts;
				double t = snap(a.time + (b.time - a.time) * f, snapping);
				if (t <= a.time + KeyMath.EPS || t >= b.time - KeyMath.EPS)continue;
				double ff = (t - a.time) / (b.time - a.time);
				KeyData m = KeyData.of(t, "linear", a.get(0) + (b.get(0) - a.get(0)) * ff, a.get(1) + (b.get(1) - a.get(1)) * ff, a.get(2) + (b.get(2) - a.get(2)) * ff);
				out.add(m);
			}
			out.add(b);
		}
		return out;
	}

	public static double snap(double t, int snapping) {
		int s = snapping > 0 ? snapping : 20;
		return Math.round(t * s) / (double) s;
	}
}
