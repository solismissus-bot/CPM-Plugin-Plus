package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.ext.W5StickNatives;
import com.tom.cpm.blockbench.util.StickMath.Placement;

/**
 * CPM+ (W5-STICK, G36): moves a group or cube into another group WITHOUT moving it in the world.
 * <p>
 * Blockbench's CPM format is a bone_rig format: origins / from / to are absolute coordinates and every node sits at
 * T(origin - parent.origin) * R(rotation) (Euler 'ZYX', degrees) inside its parent. So:
 * <ul>
 * <li>When neither the old nor the new parent chain has any rotation (the 6 player roots, a plain body), a move is a
 * plain addTo: nothing else changes (exact).</li>
 * <li>Otherwise (cape (6,-180,0), elytra +-15 degrees, a tilted group) the node gets the transform
 * M = inverse(W_target) * W_oldParent * L_node (Blockbench's own 'keep position' math): new origin = target.origin +
 * translation(M), new rotation = Euler ZYX of M (scale discarded: rigid), and the whole subtree (origins, cube
 * from / to) shifts by the same delta, so every child keeps its place relative to the node. Values are rounded to
 * 0.01 px / 0.1 degree only when no corner moves more than {@link StickMath#ROUND_RESIDUAL} px; otherwise 4
 * decimals are kept.</li>
 * </ul>
 * The math is done on the outliner data (not on Blockbench's meshes or internal functions), so it works the same in
 * the web and desktop app, needs no Blockbench-internal fallback, and only in Edit mode (in Animate mode the
 * preview pose is not the rest pose: callers refuse). Call inside ONE PlusUndo edit (outliner + every moved cube /
 * group in the aspects).
 */
public final class Reparent {

	private Reparent() {
	}

	// ------------------------------------------------------------------ node access

	public static boolean isNode(Object n) {
		return n instanceof Group || n instanceof Cube;
	}

	/** parent group, or null for the outliner root */
	public static Group parentOf(Object n) {
		Group p = n instanceof Group ? ((Group) n).parent : n instanceof Cube ? ((Cube) n).parent : null;
		return p == null || p == Group.ROOT || !(((Object) p) instanceof Group) ? null : p;
	}

	public static double[] vec(Object n, String prop) {
		return new double[] {W5StickNatives.comp(n, prop, 0), W5StickNatives.comp(n, prop, 1), W5StickNatives.comp(n, prop, 2)};
	}

	public static double[] origin(Object n) {
		return n == null ? new double[3] : vec(n, "origin");
	}

	public static double[] rotation(Object n) {
		return n == null ? new double[3] : vec(n, "rotation");
	}

	/** world matrix of a group / cube (identity for null = the outliner root) */
	public static double[] world(Object node) {
		if (!isNode(node))return StickMath.identity();
		Group p = parentOf(node);
		double[] pw = world(p);
		return StickMath.mul(pw, StickMath.local(StickMath.sub(origin(node), origin(p)), rotation(node)));
	}

	/** true when the node or any group above it has a rotation */
	public static boolean chainHasRotation(Object node) {
		Object n = node;
		int guard = 0;
		while (isNode(n) && guard++ < 10000) {
			if (!StickMath.isZeroRot(rotation(n)))return true;
			n = parentOf(n);
		}
		return false;
	}

	/** every cube of the piece: the cube itself, or all cubes inside the group (any depth) */
	public static List<Cube> cubesOf(Object node) {
		List<Cube> out = new ArrayList<>();
		collect(node, out, null);
		return out;
	}

	/** the group itself (when it is one) and every group inside it */
	public static List<Group> groupsOf(Object node) {
		List<Group> out = new ArrayList<>();
		collect(node, null, out);
		return out;
	}

	private static void collect(Object n, List<Cube> cubes, List<Group> groups) {
		if (n instanceof Cube) {
			if (cubes != null)cubes.add((Cube) n);
		} else if (n instanceof Group) {
			Group g = (Group) n;
			if (groups != null)groups.add(g);
			if (g.children != null) {
				for (int i = 0; i < g.children.length; i++) {
					OutlinerNode c = g.children.getAt(i);
					collect(c, cubes, groups);
				}
			}
		}
	}

	/** the 8 world corners of a cube (inflate included) */
	public static double[][] worldCorners(Cube c) {
		double inf = W5StickNatives.inflate(c);
		double[] o = origin(c), f = vec(c, "from"), t = vec(c, "to");
		double[] min = new double[3], max = new double[3];
		for (int i = 0; i < 3; i++) {
			min[i] = Math.min(f[i], t[i]) - inf - o[i];
			max[i] = Math.max(f[i], t[i]) + inf - o[i];
		}
		double[] w = world(c);
		double[][] cl = StickMath.corners(min, max);
		for (int i = 0; i < cl.length; i++)cl[i] = StickMath.apply(w, cl[i]);
		return cl;
	}

	// ------------------------------------------------------------------ moving

	/** what moving node into target would do (no change) */
	public static Placement plan(Object node, Group target) {
		Group oldParent = parentOf(node);
		boolean plain = !chainHasRotation(oldParent) && !chainHasRotation(target);
		List<double[]> pts = new ArrayList<>();
		if (!plain)for (Cube c : cubesOf(node))for (double[] p : worldCorners(c))pts.add(p);
		return StickMath.reparent(world(oldParent), origin(oldParent), origin(node), rotation(node), world(target), origin(target), plain,
				pts.toArray(new double[0][]));
	}

	/**
	 * Moves node into target keeping its world position (inside the caller's undo edit). Edit mode only.
	 * @return the placement that was applied
	 */
	public static Placement toParent(Object node, Group target) {
		if (!W5StickNatives.isEditMode())throw new IllegalStateException("Reparent: only in Edit mode");
		if (!isNode(node) || target == null)throw new IllegalArgumentException("Reparent: node and target are required");
		if (node == target || (node instanceof Group && RootUtil.isDescendant(target, (Group) node)))
			throw new IllegalArgumentException("Reparent: the target is the piece itself or inside it");
		Placement p = plan(node, target);
		if (node instanceof Group)((Group) node).addTo(target);
		else ((Cube) node).addTo(target);
		if (!p.plain) {
			double[] d = p.delta;
			W5StickNatives.setVec(node, "rotation", p.rotation[0], p.rotation[1], p.rotation[2]);
			shift(node, d);
		}
		return p;
	}

	/** adds d to the origin (and from / to of cubes) of node and everything inside it */
	private static void shift(Object n, double[] d) {
		if (d[0] == 0 && d[1] == 0 && d[2] == 0)return;
		add(n, "origin", d);
		if (n instanceof Cube) {
			add(n, "from", d);
			add(n, "to", d);
		} else if (n instanceof Group) {
			Group g = (Group) n;
			if (g.children != null)for (int i = 0; i < g.children.length; i++)shift(g.children.getAt(i), d);
		}
	}

	private static void add(Object n, String prop, double[] d) {
		double[] v = vec(n, prop);
		W5StickNatives.setVec(n, prop, StickMath.snap(v[0] + d[0], 1e-6), StickMath.snap(v[1] + d[1], 1e-6), StickMath.snap(v[2] + d[2], 1e-6));
	}
}
