package com.tom.cpm.blockbench.util;

import java.util.List;
import java.util.Locale;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.StickMath.Choice;
import com.tom.cpm.blockbench.util.StickMath.Kind;
import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.DirectParts;

/**
 * CPM+ (G36; stub by W4-EXPORTFIX, real body and ownership W5-STICK): which body part a piece should follow.
 * <p>
 * Read-only. The vanilla box of each of the 6 player parts (head, body, arms, legs; never cape / elytra / armor) is
 * taken from CPM's own part values for the project's skin type (slim arms are 3 px) in the LOCAL frame of that part's
 * root group (so shifted / rotated roots like AngelArmor's still work; a missing root uses its default pivot):
 * x in [-(o.x+s.x), -o.x], y in [-(o.y+s.y), -o.y], z in [o.z, o.z+s.z] (o = offset, s = size). The piece's centre is
 * the centre of the world box of all its cubes. GREEN = exactly one box contains the centre (1 px margin), ORANGE =
 * the two best parts are within 1 px (neck, shoulders, a tail between body and legs: the user must confirm), NEAREST
 * = outside every box. Ties go to the body (loose cubes follow it in game). A piece without cubes falls back to its
 * name. The suggestion is never applied on its own.
 */
public final class RootSuggest {
	/** the parts that are ever suggested */
	public static final PlayerModelParts[] PARTS = {PlayerModelParts.HEAD, PlayerModelParts.BODY, PlayerModelParts.LEFT_ARM,
			PlayerModelParts.RIGHT_ARM, PlayerModelParts.LEFT_LEG, PlayerModelParts.RIGHT_LEG};
	private static final int BODY_INDEX = 1;

	private RootSuggest() {
	}

	/** the suggestion for one piece */
	public static final class Suggestion {
		/** part name ('head', 'body', ...): never null */
		public String part;
		public Kind kind;
		/** runner-up part name (ORANGE: 'between part and second'), or null */
		public String second;
		public double dist, secondDist;
		/** centre of the piece in world coordinates (null without cubes) */
		public double[] centre;
	}

	/**
	 * Name of the body part the piece (group or cube) should follow: 'head', 'body', 'left_arm', 'right_arm',
	 * 'left_leg' or 'right_leg'. Never null (unknown -> 'body', where loose cubes go in game).
	 */
	public static String suggestRoot(Object node) {
		try {
			return suggest(node).part;
		} catch (Throwable e) {
			return PlayerModelParts.BODY.getName();
		}
	}

	/** {@link #suggest(Object, SkinType)} with the project's skin type */
	public static Suggestion suggest(Object node) {
		return suggest(node, RootUtil.projectSkinType());
	}

	public static Suggestion suggest(Object node, SkinType skinType) {
		Suggestion s = new Suggestion();
		double[] centre = centre(node);
		if (centre == null) {
			s.kind = Kind.NONE;
			s.part = byName(node);
			return s;
		}
		s.centre = centre;
		double[] dist = new double[PARTS.length];
		for (int i = 0; i < PARTS.length; i++) {
			double[][] box = localBox(PARTS[i], skinType);
			double[] local = StickMath.apply(StickMath.invRigid(rootWorld(PARTS[i], skinType)), centre);
			dist[i] = StickMath.boxDistance(local, box[0], box[1]);
		}
		Choice c = StickMath.classify(dist, BODY_INDEX);
		s.kind = c.kind;
		s.part = PARTS[c.best].getName();
		s.second = c.second >= 0 ? PARTS[c.second].getName() : null;
		s.dist = c.bestDist;
		s.secondDist = c.secondDist;
		return s;
	}

	/** vanilla box of a part in its root group's local frame: {min, max} */
	public static double[][] localBox(PlayerModelParts part, SkinType skinType) {
		PartValues pv = DirectParts.getPartOverrides(part, skinType);
		Vec3f o = pv.getOffset(), sz = pv.getSize();
		return new double[][] {{-(o.x + sz.x), -(o.y + sz.y), o.z}, {-o.x, -o.y, o.z + sz.z}};
	}

	/** world matrix of the part's root group (the real group when it exists, else the default pivot) */
	public static double[] rootWorld(PlayerModelParts part, SkinType skinType) {
		Group g = RootUtil.findPart(part);
		if (g != null)return Reparent.world(g);
		JsVec3 o = RootUtil.rootOrigin(part, skinType);
		JsVec3 r = RootUtil.rootRotation(part, skinType);
		return StickMath.local(new double[] {o.x, o.y, o.z}, new double[] {r.x, r.y, r.z});
	}

	/** centre of the world box of every cube of the piece, null when it has none */
	public static double[] centre(Object node) {
		List<Cube> cubes = Reparent.cubesOf(node);
		if (cubes.isEmpty())return null;
		double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
		double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (Cube c : cubes) {
			for (double[] p : Reparent.worldCorners(c)) {
				for (int i = 0; i < 3; i++) {
					min[i] = Math.min(min[i], p[i]);
					max[i] = Math.max(max[i], p[i]);
				}
			}
		}
		return new double[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
	}

	/** a piece without cubes: guess from its name (pt-BR and English), else the body */
	static String byName(Object node) {
		String n = node instanceof Group ? ((Group) node).name : node instanceof Cube ? ((Cube) node).name : null;
		if (n == null)return PlayerModelParts.BODY.getName();
		n = n.toLowerCase(Locale.ROOT).replace('\u00e7', 'c').replace('\u00e3', 'a').replace('\u00e9', 'e').replace(' ', '_');
		boolean left = n.contains("left") || n.contains("esq");
		boolean right = n.contains("right") || n.contains("dir");
		if (n.contains("head") || n.contains("cabe") || n.contains("hat") || n.contains("chap"))return PlayerModelParts.HEAD.getName();
		if (n.contains("arm") || n.contains("braco") || n.contains("mao") || n.contains("hand")) {
			if (left)return PlayerModelParts.LEFT_ARM.getName();
			if (right)return PlayerModelParts.RIGHT_ARM.getName();
		}
		if (n.contains("leg") || n.contains("perna") || n.contains("pe_") || n.contains("foot")) {
			if (left)return PlayerModelParts.LEFT_LEG.getName();
			if (right)return PlayerModelParts.RIGHT_LEG.getName();
		}
		return PlayerModelParts.BODY.getName();
	}
}
