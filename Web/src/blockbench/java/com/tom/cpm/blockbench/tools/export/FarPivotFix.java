package com.tom.cpm.blockbench.tools.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Canvas;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.ext.W4RigNatives;
import com.tom.cpm.blockbench.tools.model.RigGeom;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;

/**
 * CPM+ : 'Consertar sem mudar o visual' on the export rows 'limitPos' (a folder's pivot more than 48 px from the
 * pivot of the folder above: the game cuts it, FormatLimitsCheck). A folder that holds only folders (and cubes that
 * draw nothing) and has no rotation, no rotation / scale keys, no render scale, no item slot and no Copy Transform
 * gets its pivot moved onto its parent's pivot. Without rotation T(a) * T(b) = T(a + b): every child keeps its place and its own pivot (where
 * its keys turn), position keys only translate, and the export adds the new rest position to non-additive keys, so
 * nothing moves in Blockbench, in game or in any animation. The children's distance becomes the sum, e.g. the
 * -182 / +180 pairs of imported armatures become 0 / -2. A folder is moved only when all its children end within the
 * limit. CPM animations kept raw on import ('Keep') store absolute values: a folder they rotate / scale, or that they
 * or its children are in without being additive, is left alone. One click fixes every such folder, one undo entry.
 */
public final class FarPivotFix {
	private FarPivotFix() {
	}

	public static void install() {
		WarnFixes.register("limitPos", "cpmplus.w6save.limits.fixPivot", "cpmplus.w6save.limits.fixPivot.preview", 0, w -> {
			List<String> u = w.getUuids();
			return !u.isEmpty() && plan().fixed.contains(u.get(0)) ? FarPivotFix::run : null;
		});
	}

	private static final class Plan {
		/** folder -> its new pivot (the parent's) */
		final Map<Group, double[]> moves = new LinkedHashMap<>();
		/** folders whose distance to the pivot above ends within the limit */
		final Set<String> fixed = new HashSet<>();
	}

	private static Plan plan() {
		Plan p = new Plan();
		Kept kept = kept();
		List<Group> gs = new ArrayList<>(Arrays.asList(Group.all));
		gs.sort(Comparator.comparingInt(FarPivotFix::depth));// parents first
		Map<Group, double[]> org = new HashMap<>();
		for (Group g : gs)org.put(g, RigGeom.vec(g.origin));
		for (Group g : gs) {
			if (!movable(g, kept))continue;
			double[] po = org.get(g.parent);
			List<Group> kids = childGroups(g);
			boolean far = far(org.get(g), po);
			for (Group k : kids)far |= far(org.get(k), org.get(g));
			if (!far)continue;
			boolean ok = true;
			for (Group k : kids)ok &= !far(org.get(k), po) && !copies(k) && !kept.absolute.contains(sid(k));
			if (!ok)continue;
			org.put(g, Arrays.copyOf(po, 3));// (GWT has no array clone())
			p.moves.put(g, Arrays.copyOf(po, 3));
			p.fixed.add(g.uuid);
			for (Group k : kids)p.fixed.add(k.uuid);
		}
		return p;
	}

	private static Promise<Boolean> run() {
		Plan p = plan();
		if (p.moves.isEmpty())return Promise.resolve(false);
		Group[] gs = p.moves.keySet().toArray(new Group[0]);
		List<Cube> blank = new ArrayList<>();
		for (Group g : gs)for (OutlinerNode n : g.children.asList())if (n instanceof Cube && ((Cube) n).export)blank.add((Cube) n);
		Cube[] cs = blank.toArray(new Cube[0]);
		UndoData before = PlusUndo.structBefore(gs);
		before.elements = cs;
		PlusUndo.run(PlusDialogs.tr("cpmplus.w6save.limits.undoPivot"), before, () -> PlusUndo.structAfter(gs, cs, null), () -> {
			p.moves.forEach((g, o) -> {
				// the empty cube the import makes for the folder keeps its offset (it draws nothing either way)
				for (OutlinerNode n : g.children.asList()) {
					if (n instanceof Cube && ((Cube) n).export) {
						Cube c = (Cube) n;
						for (JsVec3 v : new JsVec3[] {c.from, c.to, c.origin}) {
							v.x += (float) (o[0] - g.origin.x);
							v.y += (float) (o[1] - g.origin.y);
							v.z += (float) (o[2] - g.origin.z);
						}
					}
				}
				g.origin.x = (float) o[0];
				g.origin.y = (float) o[1];
				g.origin.z = (float) o[2];
			});
		});
		try {
			Canvas.updateAllPositions();
			ProjectGenerator.updateAll();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: refresh failed", e);
		}
		PlusDialogs.quick(PlusDialogs.trf("cpmplus.w6save.limits.pivotDone", gs.length), 6000);
		return Promise.resolve(true);
	}

	/**
	 * moving its pivot changes nothing: only folders and cubes that draw nothing inside it (the import makes a 0 x 0 x 0
	 * cube for every folder; CPM draws no mesh for size 0 without inflate), no rotation (rest, keys), scale or copied
	 * transform
	 */
	private static boolean movable(Group g, Kept kept) {
		if (g.parent == null || g.parent == Group.ROOT || !g.export || g.is_rotation_subgroup || g.is_catch_bone)return false;
		if (!RigGeom.isZero(g.rotation))return false;
		for (OutlinerNode n : g.children.asList()) {
			if (n instanceof Group)continue;
			if (!(n instanceof Cube))return false;
			Cube c = (Cube) n;
			if (c.export && (c.inflate != 0 || c.from.x != c.to.x || c.from.y != c.to.y || c.from.z != c.to.z))return false;
		}
		GroupData gd = g.getData();
		Vec3f rs = gd.getRenderScale();
		if (gd.getItemRenderer() != null || (rs != null && (rs.x != 1 || rs.y != 1 || rs.z != 1)) || copies(g))return false;
		if (kept.turned.contains(sid(g)) || kept.absolute.contains(sid(g)))return false;
		return W4RigNatives.animsWithKeys(g.uuid, new String[] {"rotation", "scale"}).length == 0;
	}

	/** g copies another part's transform, or another part copies g's */
	private static boolean copies(Group g) {
		if (g.copyTransform != null && !g.copyTransform.isEmpty())return true;
		for (Group o : Group.all)if (o.copyTransform != null && o.copyTransform.contains(g.uuid))return true;
		return false;
	}

	private static long sid(Group g) {
		return g.getData().getStoreID();
	}

	private static final class Kept {
		/** rotated or scaled by a kept animation */
		final Set<Long> turned = new HashSet<>();
		/** in a kept animation that is not additive (absolute positions) */
		final Set<Long> absolute = new HashSet<>();
	}

	/** the storeIDs the CPM animations kept raw on import touch (ProjectData 'anims', the .cpmproject animation JSON) */
	private static Kept kept() {
		Kept k = new Kept();
		Map<String, Object> an = JsonUtil.asMap(Project.getData().animations.get("anims"));
		if (an == null)return k;
		for (Object o : an.values()) {
			Map<String, Object> a = JsonUtil.asMap(o);
			List<Object> frames = a != null ? JsonUtil.asList(a.get("frames")) : null;
			if (frames == null)continue;
			boolean add = Boolean.TRUE.equals(a.get("additive"));
			for (Object f : frames) {
				Map<String, Object> fm = JsonUtil.asMap(f);
				List<Object> cs = fm != null ? JsonUtil.asList(fm.get("components")) : null;
				if (cs == null)continue;
				for (Object c : cs) {
					Map<String, Object> cm = JsonUtil.asMap(c);
					Object id = cm != null ? cm.get("storeID") : null;
					if (id == null)continue;
					long sid = id instanceof Number ? ((Number) id).longValue() : GroupData.parseId(String.valueOf(id));
					if (!add)k.absolute.add(sid);
					if (moves(cm.get("rotation"), 0) || moves(cm.get("scale"), 1))k.turned.add(sid);
				}
			}
		}
		return k;
	}

	private static boolean moves(Object v, double rest) {
		Map<String, Object> m = JsonUtil.asMap(v);
		if (m == null)return false;
		for (String a : new String[] {"x", "y", "z"}) {
			Object n = m.get(a);
			if (n instanceof Number && Math.abs(((Number) n).doubleValue() - rest) > 1e-6)return true;
		}
		return false;
	}

	private static List<Group> childGroups(Group g) {
		List<Group> out = new ArrayList<>();
		for (OutlinerNode n : g.children.asList())if (n instanceof Group && ((Group) n).export)out.add((Group) n);
		return out;
	}

	/** the folder's position as CPM gets it (BlockbenchExport: pivot - parent pivot, x and y flipped) is cut by the game */
	private static boolean far(double[] o, double[] parent) {
		return FormatLimitsCheck.posCut((float) -(o[0] - parent[0])) || FormatLimitsCheck.posCut((float) -(o[1] - parent[1])) ||
				FormatLimitsCheck.posCut((float) (o[2] - parent[2]));
	}

	private static int depth(Group g) {
		int d = 0;
		for (Group p = g.parent; p != null && p != Group.ROOT; p = p.parent)d++;
		return d;
	}
}
