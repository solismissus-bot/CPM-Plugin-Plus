package com.tom.cpm.blockbench.tools.bend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Canvas;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Modes;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.PlusUvUtil;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.web.client.util.I18n;

import jsinterop.base.Js;

/**
 * CPM+ (G42) 'Membros dobráveis': splits the vanilla cube of an arm / leg / the body into an upper and a lower cube
 * so the lower one can bend at the elbow / knee / waist, without changing what the skin looks like at 0 degrees.
 * <ul>
 * <li>The limb cube is found by GEOMETRY (the vanilla box of the part at the root's pivot, Steve or Alex width), never
 * by name; a rotated cube, a mesh, several candidates or an already bent limb are refused with a message (or the user
 * picks the cube).</li>
 * <li>Box UV is converted to per-face UV by Blockbench itself ({@link PlusUvUtil#toPerFace}); side faces are split on
 * whole texel rows (mirror and rotation kept), the top stays on the upper cube, the bottom on the lower one; the
 * hidden inner faces at the cut show the cut row of the skin.</li>
 * <li>2nd layer (sleeve / trouser leg): the inflate is kept and the two DRAWN pieces meet exactly (no overlap, no
 * gap), with every piece size on the 0.1 px grid the CPM export writes (so the game draws exactly that); the seam is at
 * most 0.05 px from the texel row. The layer's inner faces are empty. When the root has no layer cube but its cube is
 * the imported vanilla part (the game drew the sleeve), the sleeve is built from the 2nd layer UV.</li>
 * <li>New child group '&lt;root&gt;_cotovelo / _joelho / _cintura' with its pivot on the cut plane, at the outer edge
 * (the side that must stay closed when it bends). The root itself is untouched (pivot, rotation, keys, flags).</li>
 * <li>Item slot of the hand (found by its item renderer) and the user's parts below the cut move into the new group
 * keeping their world position; a missing hand slot is created inside the same undo entry.</li>
 * <li>Marker GroupData extra 'bend' on the new group (survives the .bbmodel; lost in a .cpmproject round trip):
 * 'Desfazer membro dobrável' rebuilds the original cube(s) from it.</li>
 * <li>Every change is ONE undo entry; every new node gets fresh CPM ids (IdUtil.resetIds).</li>
 * </ul>
 */
public final class BendLimb {
	/** GroupData extra key of the joint group */
	public static final String MARK = "bend";
	public static final PlayerModelParts[] PARTS = {PlayerModelParts.LEFT_ARM, PlayerModelParts.RIGHT_ARM, PlayerModelParts.LEFT_LEG, PlayerModelParts.RIGHT_LEG, PlayerModelParts.BODY};
	private static final float EPS = 1e-3f;

	private BendLimb() {
	}

	/** user options for one part */
	public static final class Opts {
		public String root;
		/** cut in model px counted from the top of the limb; -1 = middle */
		public int cut = -1;
		/** 'front' / 'back' (where the lower part swings); null = default of the part */
		public String dir;
		public boolean layer = true;
		public boolean moveItem = true;
		public boolean moveBelow = true;
		/** 'none' / 'joint' */
		public String gap = "none";
		/** explicit base cube (user selection) */
		public Cube cube;
	}

	/** the dry run: everything checked, nothing changed */
	public static final class Plan {
		public Opts opts;
		public Group root;
		public PlayerModelParts part;
		public Cube base, layer;
		public boolean layerFromVanilla, slim;
		public int h;
		public List<Integer> cuts = new ArrayList<>();
		public int cut;
		public float cutY;
		public String dir, jointName;
		public float[] pivot;
		public ItemSlot slot;
		public Group slotGroup;
		public boolean slotMovable = true;
		public List<OutlinerNode> below = new ArrayList<>();
		public Map<String, float[]> baseUv, layerUv;
		public float layerInflate;
		/** refusal: lang key + args (null = ok) */
		public String error;
		public Object[] errorArgs = new Object[0];
		public List<String> warnings = new ArrayList<>();

		Plan fail(String key, Object... args) {
			error = key;
			errorArgs = args;
			return this;
		}

		public String errorText() {
			return error == null ? null : I18n.format(error, errorArgs);
		}
	}

	/** result of an apply */
	public static final class Result {
		public final List<Group> joints = new ArrayList<>();
		public final List<Cube> cubes = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();
	}

	// ====================================================================== detection

	public static boolean isLimb(Group g) {
		return g != null && g.parent == Group.ROOT && part(g.name) != null;
	}

	public static PlayerModelParts part(String rootName) {
		if (rootName == null)return null;
		for (PlayerModelParts p : PARTS)if (p.getName().equals(rootName))return p;
		return null;
	}

	public static String jointSuffix(PlayerModelParts p) {
		switch (p) {
		case LEFT_ARM:
		case RIGHT_ARM: return "_cotovelo";
		case LEFT_LEG:
		case RIGHT_LEG: return "_joelho";
		default: return "_cintura";
		}
	}

	public static String defaultDir(PlayerModelParts p) {
		return p == PlayerModelParts.LEFT_LEG || p == PlayerModelParts.RIGHT_LEG ? "back" : "front";
	}

	/** the joint group (child of the root) carrying the 'bend' marker, or null */
	public static Group findJoint(Group root) {
		if (root == null || root.children == null)return null;
		for (int i = 0; i < root.children.length; i++) {
			OutlinerNode n = root.children.getAt(i);
			if (n instanceof Group && hasMark((Group) n))return (Group) n;
		}
		return null;
	}

	public static boolean hasMark(Group g) {
		try {
			return g != null && g.getData().hasExtra(MARK);
		} catch (Throwable e) {
			return false;
		}
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < EPS;
	}

	private static boolean sameBox(Cube c, JsVec3 from, JsVec3 to) {
		float fx = Math.min(c.from.x, c.to.x), tx = Math.max(c.from.x, c.to.x);
		float fy = Math.min(c.from.y, c.to.y), ty = Math.max(c.from.y, c.to.y);
		float fz = Math.min(c.from.z, c.to.z), tz = Math.max(c.from.z, c.to.z);
		return near(fx, from.x) && near(fy, from.y) && near(fz, from.z) && near(tx, to.x) && near(ty, to.y) && near(tz, to.z);
	}

	static boolean rotated(Cube c) {
		return c.rotation != null && (Math.abs(c.rotation.x) > 1e-4 || Math.abs(c.rotation.y) > 1e-4 || Math.abs(c.rotation.z) > 1e-4);
	}

	static native boolean isMesh(Object n) /*-{
		return !!n && n.type === 'mesh';
	}-*/;

	/** Blockbench Animate mode active */
	public static boolean animateMode() {
		try {
			return com.tom.cpm.blockbench.tools.anim.AnimToolSupport.animateMode();
		} catch (Throwable e) {
			return false;
		}
	}

	/** the dry run for one part (never changes anything) */
	public static Plan plan(Opts o) {
		Plan p = new Plan();
		p.opts = o;
		if (animateMode())return p.fail("cpmplus.w7rig.bend.err.animate");
		p.part = part(o.root);
		if (p.part == null)return p.fail("cpmplus.w7rig.bend.err.notLimb", String.valueOf(o.root));
		p.root = RootUtil.findRootGroup(o.root);
		if (p.root == null)return p.fail("cpmplus.w7rig.bend.err.noRoot", partName(p.part));
		if (findJoint(p.root) != null)return p.fail("cpmplus.w7rig.bend.err.already", partName(p.part));

		// ---- the limb cube, by geometry
		List<Cube> cand = new ArrayList<>();
		List<Cube> layerCand = new ArrayList<>();
		boolean anyMesh = false, anyRotated = false;
		boolean arm = p.part == PlayerModelParts.LEFT_ARM || p.part == PlayerModelParts.RIGHT_ARM;
		SkinType[] types = arm ? new SkinType[] {SkinType.DEFAULT, SkinType.SLIM} : new SkinType[] {SkinType.DEFAULT};
		for (int i = 0; i < p.root.children.length; i++) {
			OutlinerNode n = p.root.children.getAt(i);
			if (isMesh(n))anyMesh = true;
			if (!(n instanceof Cube))continue;
			Cube c = (Cube) n;
			for (SkinType st : types) {
				RootUtil.CubeSpec spec = RootUtil.vanillaCubeSpec(p.part, st, p.root.origin);
				if (!sameBox(c, spec.from, spec.to))continue;
				if (c.inflate > 0.01f) {
					if (!layerCand.contains(c))layerCand.add(c);
				} else if (Math.abs(c.inflate) <= 0.01f) {
					if (rotated(c))anyRotated = true;
					else if (!cand.contains(c)) {
						cand.add(c);
						if (st == SkinType.SLIM && !near(spec.size.x, RootUtil.vanillaCubeSpec(p.part, SkinType.DEFAULT, p.root.origin).size.x))p.slim = true;
					}
				}
			}
		}
		if (o.cube != null) {
			Cube c = o.cube;
			if (c.parent != p.root)return p.fail("cpmplus.w7rig.bend.err.selectedOutside", partName(p.part));
			if (rotated(c))return p.fail("cpmplus.w7rig.bend.err.rotated", c.name);
			cand.clear();
			cand.add(c);
			layerCand.clear();
			for (int i = 0; i < p.root.children.length; i++) {
				OutlinerNode n = p.root.children.getAt(i);
				if (n instanceof Cube && n != c && ((Cube) n).inflate > 0.01f && sameBox((Cube) n, c.from, c.to))layerCand.add((Cube) n);
			}
		}
		if (cand.isEmpty()) {
			if (anyRotated)return p.fail("cpmplus.w7rig.bend.err.rotated", partName(p.part));
			if (anyMesh)return p.fail("cpmplus.w7rig.bend.err.mesh", partName(p.part));
			return p.fail("cpmplus.w7rig.bend.err.noCube", partName(p.part));
		}
		if (cand.size() > 1)return p.fail("cpmplus.w7rig.bend.err.ambiguous", partName(p.part), cand.size());
		p.base = cand.get(0);
		if (rotated(p.base))return p.fail("cpmplus.w7rig.bend.err.rotated", p.base.name);
		if (p.base.extrude || p.base.getData().getExtrude() != null)return p.fail("cpmplus.w7rig.bend.err.extrude", p.base.name);
		if (layerCand.size() > 1 && o.layer)return p.fail("cpmplus.w7rig.bend.err.ambiguousLayer", partName(p.part));
		if (o.layer) {
			if (layerCand.size() == 1)p.layer = layerCand.get(0);
			else if (layerCand.isEmpty() && isVanillaCube(p.base))p.layerFromVanilla = true;
		}
		if (p.layer != null && rotated(p.layer))return p.fail("cpmplus.w7rig.bend.err.rotated", p.layer.name);
		if (p.layer != null && (p.layer.extrude || p.layer.getData().getExtrude() != null))return p.fail("cpmplus.w7rig.bend.err.extrude", p.layer.name);
		p.layerInflate = p.layer != null ? p.layer.inflate : p.layerFromVanilla ? 0.25f : 0;
		if ((p.layer != null || p.layerFromVanilla) && PlusUvUtil.drawnBoundaries(12, p.layerInflate, new double[] {0, 0.5, 1}) == null)
			return p.fail("cpmplus.w7rig.bend.err.layerInflate", p.layer != null ? p.layer.name : partName(p.part), fmt(p.layerInflate));

		// ---- geometry and UVs
		float fy = Math.min(p.base.from.y, p.base.to.y), ty = Math.max(p.base.from.y, p.base.to.y);
		double hd = ty - fy;
		p.h = (int) Math.round(hd);
		if (Math.abs(hd - p.h) > EPS || p.h < 2)return p.fail("cpmplus.w7rig.bend.err.height", p.base.name);
		p.baseUv = PlusUvUtil.currentUvs(p.base);
		if (p.layer != null)p.layerUv = PlusUvUtil.currentUvs(p.layer);
		for (String f : PlusUvUtil.SIDES) {
			if (!p.base.box_uv && !PlusUvUtil.disabled(p.base, f) && PlusUvUtil.rot(p.base, f) % 180 != 0)return p.fail("cpmplus.w7rig.bend.err.faceRot", p.base.name);
			if (p.layer != null && !p.layer.box_uv && !PlusUvUtil.disabled(p.layer, f) && PlusUvUtil.rot(p.layer, f) % 180 != 0)return p.fail("cpmplus.w7rig.bend.err.faceRot", p.layer.name);
		}
		double qb = texelScale(p.base), ql = p.layer != null ? texelScale(p.layer) : qb;
		for (int k = 1; k < p.h; k++) {
			if (cutOk(p.base, p.baseUv, k, p.h, qb) && (p.layer == null || cutOk(p.layer, p.layerUv, k, p.h, ql)))p.cuts.add(k);
		}
		if (p.cuts.isEmpty())return p.fail("cpmplus.w7rig.bend.err.noGridCut", p.base.name);
		int want = o.cut > 0 ? o.cut : p.h / 2;
		int best = p.cuts.get(0);
		for (int k : p.cuts)if (Math.abs(k - want) < Math.abs(best - want))best = k;
		if (o.cut > 0 && best != o.cut)p.warnings.add(I18n.format("cpmplus.w7rig.bend.warn.cutMoved", o.cut, best));
		p.cut = best;
		p.cutY = ty - p.cut;
		p.dir = "back".equals(o.dir) || "front".equals(o.dir) ? o.dir : defaultDir(p.part);
		float fx = Math.min(p.base.from.x, p.base.to.x), tx = Math.max(p.base.from.x, p.base.to.x);
		float fz = Math.min(p.base.from.z, p.base.to.z), tz = Math.max(p.base.from.z, p.base.to.z);
		// front (Blockbench north, -Z) bends forward: the outer side is the back (+Z), which must stay closed
		p.pivot = new float[] {(fx + tx) / 2f, p.cutY, "front".equals(p.dir) ? tz : fz};
		p.jointName = uniqueGroupName(p.root.name + jointSuffix(p.part));

		// ---- hand item slot
		if (p.part == PlayerModelParts.RIGHT_ARM)p.slot = ItemSlot.RIGHT_HAND;
		else if (p.part == PlayerModelParts.LEFT_ARM)p.slot = ItemSlot.LEFT_HAND;
		if (p.slot != null) {
			p.slotGroup = findSlot(p.root, p.slot);
			if (p.slotGroup != null && !straightChain(p.slotGroup, p.root)) {
				p.slotMovable = false;
				if (o.moveItem)p.warnings.add(I18n.get("cpmplus.w7rig.bend.warn.slotRotated"));
			}
		}
		// ---- other parts below the cut (direct children of the root)
		for (int i = 0; i < p.root.children.length; i++) {
			OutlinerNode n = p.root.children.getAt(i);
			if (n == p.base || n == p.layer || n == p.slotGroup)continue;
			if (p.slotGroup != null && n instanceof Group && isAncestor((Group) n, p.slotGroup))continue;
			if (n instanceof Cube) {
				Cube c = (Cube) n;
				if (layerCand.contains(c))continue;
				if ((c.from.y + c.to.y) / 2f < p.cutY - EPS)p.below.add(c);
			} else if (n instanceof Group) {
				Group g = (Group) n;
				if (g.origin != null && g.origin.y < p.cutY - EPS)p.below.add(g);
			}
		}
		return p;
	}

	private static boolean isAncestor(Group anc, Group node) {
		Group g = node.parent;
		int guard = 0;
		while (g != null && g != Group.ROOT && guard++ < 1000) {
			if (g == anc)return true;
			g = g.parent;
		}
		return false;
	}

	static String fmt(double v) {
		double r = Math.round(v * 100) / 100.0;
		String s = String.valueOf(r);
		if (s.endsWith(".0"))s = s.substring(0, s.length() - 2);
		return s.replace('.', ',');
	}

	private static boolean cutOk(Cube c, Map<String, float[]> uv, int k, int h, double q) {
		for (String f : PlusUvUtil.SIDES) {
			float[] u = uv.get(f);
			if (u == null || PlusUvUtil.disabled(c, f))continue;
			float[][] s = PlusUvUtil.splitSide(u, c.box_uv ? 0 : PlusUvUtil.rot(c, f), k, h);
			if (s == null)return false;
			for (float[] r : s)for (int i = 0; i < 4; i++)if (!PlusUvUtil.isGrid(r[i] * q, 1))return false;
		}
		return true;
	}

	/** texels per UV unit of the cube's texture (1 when unknown) */
	static native double texelScale(Cube c) /*-{
		try {
			var tex = null;
			for (var f in c.faces) {
				var fc = c.faces[f];
				if (!fc || fc.texture === null || fc.texture === false) continue;
				if (typeof fc.getTexture === 'function') tex = fc.getTexture();
				if (tex) break;
			}
			if (!tex || !tex.width) return 1;
			var uw = (typeof tex.getUVWidth === 'function' ? tex.getUVWidth() : 0) || ($wnd.Project && $wnd.Project.texture_width) || tex.width;
			var q = tex.width / uw;
			return q > 0 ? q : 1;
		} catch (e) { return 1; }
	}-*/;

	private static boolean isVanillaCube(Cube c) {
		try {
			return c.getData().isVanilla();
		} catch (Throwable e) {
			return false;
		}
	}

	/** the item slot group (by its item renderer) below root, or null */
	public static Group findSlot(Group root, ItemSlot slot) {
		Group[] out = new Group[1];
		String n = slot.name();
		if (root.children == null)return null;
		forEachGroup(root, g -> {
			if (out[0] == null && n.equalsIgnoreCase(g.getData().getItemRenderer()))out[0] = g;
		});
		return out[0];
	}

	private interface GroupVisitor {
		void visit(Group g);
	}

	private static void forEachGroup(Group parent, GroupVisitor v) {
		if (parent.children == null)return;
		for (int i = 0; i < parent.children.length; i++) {
			OutlinerNode n = parent.children.getAt(i);
			if (n instanceof Group) {
				v.visit((Group) n);
				forEachGroup((Group) n, v);
			}
		}
	}

	/** true when every group between node (exclusive) and ancestor (exclusive) has no rotation */
	private static boolean straightChain(Group node, Group ancestor) {
		Group p = node.parent;
		int guard = 0;
		while (p != null && p != ancestor && p != Group.ROOT && guard++ < 1000) {
			if (p.rotation != null && (p.rotation.x != 0 || p.rotation.y != 0 || p.rotation.z != 0))return false;
			p = p.parent;
		}
		return p == ancestor;
	}

	public static String uniqueGroupName(String base) {
		String n = base;
		int i = 2;
		while (groupNamed(n) != null)n = base + i++;
		return n;
	}

	static Group groupNamed(String name) {
		for (Group g : Group.all)if (name.equals(g.name))return g;
		return null;
	}

	public static String partName(PlayerModelParts p) {
		return I18n.get("cpmplus.w7rig.part." + p.getName());
	}

	// ====================================================================== apply

	/** applies every plan (all must be ok) as ONE undo entry */
	public static Result apply(List<Plan> plans) {
		for (Plan p : plans)if (p.error != null)throw new IllegalStateException(p.errorText());
		Result res = new Result();
		List<Group> beforeGroups = new ArrayList<>();
		List<OutlinerElement> beforeElems = new ArrayList<>();
		for (Plan p : plans) {
			beforeGroups.add(p.root);
			if (p.slotGroup != null)beforeGroups.add(p.slotGroup);
			beforeElems.add(p.base);
			if (p.layer != null)beforeElems.add(p.layer);
			for (OutlinerNode n : p.below) {
				if (n instanceof Group)beforeGroups.add((Group) n);
				else if (n instanceof Cube)beforeElems.add((Cube) n);
			}
		}
		UndoData before = new UndoData();
		before.outliner = true;
		before.groups = beforeGroups.toArray(new Group[0]);
		before.elements = beforeElems.toArray(new OutlinerElement[0]);
		List<Group> afterGroups = new ArrayList<>();
		List<OutlinerElement> afterElems = new ArrayList<>();
		String label = plans.size() == 1 ? I18n.format("cpmplus.w7rig.bend.undo", partName(plans.get(0).part)) : I18n.get("cpmplus.w7rig.bend.undoMany");
		PlusUndo.run(label, before, () -> {
			UndoData a = new UndoData();
			a.outliner = true;
			a.groups = afterGroups.toArray(new Group[0]);
			a.elements = afterElems.toArray(new OutlinerElement[0]);
			return a;
		}, () -> {
			for (Plan p : plans)applyOne(p, res, afterGroups, afterElems);
			ProjectGenerator.updateAll();
			Canvas.updateAll();
		});
		return res;
	}

	private static void applyOne(Plan p, Result res, List<Group> afterGroups, List<OutlinerElement> afterElems) {
		Opts o = p.opts;
		afterGroups.add(p.root);
		// ---- joint group (new: fresh ids before anything moves into it)
		GroupProperties gp = new GroupProperties();
		gp.name = p.jointName;
		gp.origin = JsVec3.make(p.pivot[0], p.pivot[1], p.pivot[2]);
		gp.rotation = JsVec3.make(0, 0, 0);
		Group joint = new Group(gp).init();
		joint.addTo(p.root, -1);
		joint.isOpen = true;
		IdUtil.resetIds(joint);
		afterGroups.add(joint);
		res.joints.add(joint);

		Map<String, Object> mark = new LinkedHashMap<>();
		mark.put("v", 1.0);
		mark.put("root", p.root.name);
		mark.put("cut", (double) p.cut);
		mark.put("h", (double) p.h);
		mark.put("dir", p.dir);

		// ---- base cube
		Cube[] b = split(p, p.base, p.baseUv, joint, false, 0);
		mark.put("base", cubeRecord(p.base, b));
		afterElems.add(b[0]);
		afterElems.add(b[1]);
		res.cubes.add(b[0]);
		res.cubes.add(b[1]);

		// ---- 2nd layer
		Cube layer = p.layer;
		if (layer == null && p.layerFromVanilla)layer = makeVanillaLayer(p);
		if (layer != null) {
			Map<String, float[]> luv = p.layerUv != null ? p.layerUv : PlusUvUtil.currentUvs(layer);
			Cube[] l = split(p, layer, luv, joint, true, layer.inflate);
			Map<String, Object> lr = cubeRecord(layer, l);
			if (p.layer == null)lr.put("fromVanilla", true);
			mark.put("layer", lr);
			afterElems.add(l[0]);
			afterElems.add(l[1]);
			res.cubes.add(l[0]);
			res.cubes.add(l[1]);
			if (p.layer == null)layer.remove(false);
		}

		// ---- optional joint block (inside the upper part, hidden at 0 degrees)
		if ("joint".equals(o.gap)) {
			Cube jc = jointCube(p, b[0]);
			afterElems.add(jc);
			res.cubes.add(jc);
			mark.put("jointCube", jc.uuid);
		}

		// ---- hand item slot
		if (p.slot != null && o.moveItem && p.slotMovable) {
			Group slot = p.slotGroup;
			if (slot == null) {
				List<Group> made = new ArrayList<>();
				BBActions.addSlotsNoUndo(new ItemSlot[] {p.slot}, made);
				if (!made.isEmpty()) {
					slot = made.get(0);
					mark.put("slotCreated", true);
				}
			}
			if (slot != null) {
				slot.addTo(joint, -1);
				afterGroups.add(slot);
				mark.put("slot", slot.uuid);
			}
		}
		// ---- the user's parts below the cut
		if (o.moveBelow) {
			List<Object> moved = new ArrayList<>();
			for (OutlinerNode n : p.below) {
				if (n instanceof Group) {
					((Group) n).addTo(joint, -1);
					afterGroups.add((Group) n);
				} else if (n instanceof Cube) {
					((Cube) n).addTo(joint, -1);
					afterElems.add((Cube) n);
				}
				moved.add(n.uuid);
			}
			if (!moved.isEmpty())mark.put("moved", moved);
		}

		// ---- the original cubes go away (Ctrl+Z / 'Desfazer membro dobrável' bring them back)
		p.base.remove(false);
		if (p.layer != null)p.layer.remove(false);

		joint.getData().putExtra(MARK, mark).flush();
		res.warnings.addAll(p.warnings);
	}

	private static Map<String, Object> cubeRecord(Cube orig, Cube[] pieces) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("name", orig.name);
		r.put("from", vec(orig.from));
		r.put("to", vec(orig.to));
		r.put("box", orig.box_uv);
		if (orig.uv_offset != null) {
			List<Object> uo = new ArrayList<>();
			uo.add((double) orig.uv_offset.x);
			uo.add((double) orig.uv_offset.y);
			r.put("uvOffset", uo);
		}
		r.put("mirror", orig.mirror_uv);
		r.put("inflate", (double) orig.inflate);
		if (isVanillaCube(orig))r.put("vanilla", true);
		try {
			long sid = orig.getData().getStoreID();
			if (sid != 0)r.put("sid", String.valueOf(sid));
		} catch (Throwable e) {
		}
		if (!orig.box_uv)r.put("faces", faceRecord(orig));
		r.put("up", pieces[0].uuid);
		r.put("low", pieces[1].uuid);
		return r;
	}

	private static Map<String, Object> faceRecord(Cube c) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (String f : PlusUvUtil.FACES) {
			CubeFace cf = PlusUvUtil.face(c, f);
			if (cf == null || cf.uv == null)continue;
			Map<String, Object> fr = new LinkedHashMap<>();
			List<Object> uv = new ArrayList<>();
			uv.add((double) cf.uv.sx);
			uv.add((double) cf.uv.sy);
			uv.add((double) cf.uv.ex);
			uv.add((double) cf.uv.ey);
			fr.put("uv", uv);
			fr.put("rot", (double) cf.rotation);
			if (cf.isDisabled())fr.put("off", true);
			m.put(f, fr);
		}
		return m;
	}

	private static List<Object> vec(JsVec3 v) {
		List<Object> l = new ArrayList<>();
		l.add((double) v.x);
		l.add((double) v.y);
		l.add((double) v.z);
		return l;
	}

	/**
	 * Two per-face copies of c: [0] the upper piece (stays in the root), [1] the lower piece (into the joint group).
	 * layer=true: inflate kept, drawn pieces meet exactly with sizes on the export's 0.1 grid, inner faces empty.
	 */
	private static Cube[] split(Plan p, Cube c, Map<String, float[]> uv, Group joint, boolean layer, float inflate) {
		float fy = Math.min(c.from.y, c.to.y), ty = Math.max(c.from.y, c.to.y);
		float fx = Math.min(c.from.x, c.to.x), tx = Math.max(c.from.x, c.to.x);
		float fz = Math.min(c.from.z, c.to.z), tz = Math.max(c.from.z, c.to.z);
		int h = p.h, k = p.cut;
		float upFrom, lowTo;
		if (layer) {
			// drawn box = [fy - i, ty + i]; boundary from the top on the 0.1 grid; each piece keeps the inflate
			double[] bd = PlusUvUtil.drawnBoundaries(ty - fy, inflate, new double[] {0, (double) k / h, 1});
			if (bd == null)throw new IllegalStateException("2nd layer of '" + c.name + "' cannot be split on the export grid");
			double seam = ty + inflate - bd[1];
			upFrom = (float) (seam + inflate);
			lowTo = (float) (seam - inflate);
		} else {
			upFrom = p.cutY;
			lowTo = p.cutY;
		}
		Cube up = c.duplicate();
		Cube low = c.duplicate();
		Map<String, Integer> rots = new LinkedHashMap<>();
		for (String f : PlusUvUtil.SIDES)rots.put(f, c.box_uv ? 0 : PlusUvUtil.rot(c, f));
		PlusUvUtil.toPerFace(up);
		PlusUvUtil.toPerFace(low);
		setVec(up.from, fx, upFrom, fz);
		setVec(up.to, tx, ty, tz);
		setVec(low.from, fx, fy, fz);
		setVec(low.to, tx, lowTo, tz);
		up.name = c.name + "_cima";
		low.name = c.name + "_baixo";
		String stripFace = null;
		for (String f : PlusUvUtil.SIDES) {
			float[] u = uv.get(f);
			if (u == null || PlusUvUtil.disabled(c, f))continue;
			float[][] s = PlusUvUtil.splitSide(u, rots.get(f), k, h);
			if (s == null)throw new IllegalStateException("face " + f + " of " + c.name + " is rotated 90/270");
			PlusUvUtil.setUv(up, f, s[0]);
			PlusUvUtil.setUv(low, f, s[1]);
			if (stripFace == null)stripFace = f;
		}
		if (layer || stripFace == null) {
			// the layer's inner faces are empty: nothing to see there, and no line of sleeve inside a transparent sleeve
			CubeFace d = PlusUvUtil.face(up, "down");
			if (d != null)d.disable();
			CubeFace t = PlusUvUtil.face(low, "up");
			if (t != null)t.disable();
		} else {
			float[] su = uv.get(stripFace);
			PlusUvUtil.setUv(up, "down", PlusUvUtil.stripAt(su, rots.get(stripFace), k, h, true));
			PlusUvUtil.setUv(low, "up", PlusUvUtil.stripAt(su, rots.get(stripFace), k, h, false));
			CubeFace d = PlusUvUtil.face(up, "down");
			if (d != null)d.rotation = 0;
			CubeFace t = PlusUvUtil.face(low, "up");
			if (t != null)t.rotation = 0;
		}
		setVec(low.origin, p.pivot[0], p.pivot[1], p.pivot[2]);
		low.addTo(joint, -1);
		IdUtil.resetIds(up);
		IdUtil.resetIds(low);
		refresh(up);
		refresh(low);
		return new Cube[] {up, low};
	}

	/** writes into an existing Blockbench vector (never shares arrays between nodes) */
	static void setVec(JsVec3 v, float x, float y, float z) {
		v.x = x;
		v.y = y;
		v.z = z;
	}

	static native void refresh(Cube c) /*-{
		try {
			if (c.preview_controller) {
				c.preview_controller.updateGeometry(c);
				c.preview_controller.updateFaces(c);
				c.preview_controller.updateUV(c);
			}
		} catch (e) {}
	}-*/;

	/** a temporary 2nd layer cube built from the part's 2nd layer UV (the game drew the sleeve of a vanilla cube) */
	private static Cube makeVanillaLayer(Plan p) {
		SkinType st = p.slim ? SkinType.SLIM : SkinType.DEFAULT;
		RootUtil.CubeSpec spec = RootUtil.secondLayerCubeSpec(p.part, st, p.root.origin);
		Cube.CubeProperties cp = new Cube.CubeProperties();
		cp.name = RootUtil.secondLayerName(p.part, st);
		cp.origin = RootUtil.copyVec(p.root.origin);
		cp.from = spec.from;
		cp.to = spec.to;
		Cube cube = new Cube(cp);
		cube.inflate = spec.inflate;
		cube.box_uv = true;
		cube.uv_offset = spec.uvOffset;
		cube.addTo(p.root).init();
		copyTextures(p.base, cube);
		return cube;
	}

	/** every textured face of 'to' gets the texture of 'from' (first textured face) */
	static native void copyTextures(Cube from, Cube to) /*-{
		var tex = null;
		for (var f in from.faces) if (from.faces[f] && from.faces[f].texture) { tex = from.faces[f].texture; break; }
		if (tex == null) return;
		for (var g in to.faces) if (to.faces[g] && to.faces[g].texture !== null) to.faces[g].texture = tex;
		try { if (to.preview_controller) { to.preview_controller.updateFaces(to); to.preview_controller.updateUV(to); } } catch (e) {}
	}-*/;

	/** a block just above the cut inside the upper piece (0.1 px inside every side: invisible at 0 degrees) */
	private static Cube jointCube(Plan p, Cube up) {
		float fx = Math.min(up.from.x, up.to.x), tx = Math.max(up.from.x, up.to.x);
		float fz = Math.min(up.from.z, up.to.z), tz = Math.max(up.from.z, up.to.z);
		Cube jc = up.duplicate();
		jc.name = p.root.name + "_junta";
		// 0.1 px inside: sizes stay on the export's 0.1 grid
		float d = 0.1f;
		float hgt = Math.min(1f, p.cut);
		setVec(jc.from, fx + d, p.cutY - hgt + d, fz + d);
		setVec(jc.to, tx - d, p.cutY - d, tz - d);
		// every face shows the row right above the cut of the matching side face
		for (String f : PlusUvUtil.SIDES) {
			float[] u = p.baseUv.get(f);
			if (u == null)continue;
			PlusUvUtil.setUv(jc, f, PlusUvUtil.stripAt(u, p.base.box_uv ? 0 : PlusUvUtil.rot(p.base, f), p.cut, p.h, true));
		}
		float[] n = p.baseUv.get("north");
		if (n != null) {
			float[] s = PlusUvUtil.stripAt(n, p.base.box_uv ? 0 : PlusUvUtil.rot(p.base, "north"), p.cut, p.h, true);
			PlusUvUtil.setUv(jc, "up", s);
			PlusUvUtil.setUv(jc, "down", s);
		}
		IdUtil.resetIds(jc);
		refresh(jc);
		return jc;
	}

	// ====================================================================== undo the bend

	/** names of the animations that move the joint group (they would lose that movement) */
	public static List<String> animationsUsing(Group joint) {
		List<String> out = new ArrayList<>();
		if (Animation.all == null)return out;
		for (Animation a : Animation.all) {
			if (a.animators == null)continue;
			Object an = a.animators.get(joint.uuid);
			if (an == null)continue;
			BoneAnimator ba = Js.uncheckedCast(an);
			if (ba.hasTransform())out.add(a.name);
		}
		return out;
	}

	/** rebuilds the original cube(s) from the marker and removes the joint group (ONE undo entry) */
	@SuppressWarnings("unchecked")
	public static void unbend(Group joint) {
		Object m0 = joint.getData().getExtra(MARK);
		if (!(m0 instanceof Map))throw new IllegalStateException(I18n.get("cpmplus.w7rig.unbend.err.noMark"));
		Map<String, Object> m = (Map<String, Object>) m0;
		Group root = joint.parent;
		if (root == null || root == Group.ROOT || root.parent != Group.ROOT || !String.valueOf(m.get("root")).equals(root.name))
			throw new IllegalStateException(I18n.get("cpmplus.w7rig.unbend.err.moved"));
		List<Group> beforeGroups = new ArrayList<>();
		beforeGroups.add(root);
		beforeGroups.add(joint);
		List<OutlinerElement> beforeElems = new ArrayList<>();
		List<Cube> pieces = new ArrayList<>();
		for (String k : new String[] {"base", "layer"}) {
			Object r = m.get(k);
			if (!(r instanceof Map))continue;
			for (String pk : new String[] {"up", "low"}) {
				Cube c = cubeByUuid(String.valueOf(((Map<String, Object>) r).get(pk)));
				if (c != null)pieces.add(c);
			}
		}
		Cube jc = m.get("jointCube") != null ? cubeByUuid(String.valueOf(m.get("jointCube"))) : null;
		if (jc != null)pieces.add(jc);
		beforeElems.addAll(pieces);
		List<OutlinerNode> keep = new ArrayList<>();
		for (int i = 0; i < joint.children.length; i++) {
			OutlinerNode n = joint.children.getAt(i);
			if (pieces.contains(n))continue;
			keep.add(n);
			if (n instanceof Group)beforeGroups.add((Group) n);
			else if (n instanceof Cube)beforeElems.add((Cube) n);
		}
		UndoData before = new UndoData();
		before.outliner = true;
		before.groups = beforeGroups.toArray(new Group[0]);
		before.elements = beforeElems.toArray(new OutlinerElement[0]);
		List<OutlinerElement> afterElems = new ArrayList<>();
		List<Group> afterGroups = new ArrayList<>();
		PlusUndo.run(I18n.get("cpmplus.w7rig.unbend.undo"), before, () -> {
			UndoData a = new UndoData();
			a.outliner = true;
			a.groups = afterGroups.toArray(new Group[0]);
			a.elements = afterElems.toArray(new OutlinerElement[0]);
			return a;
		}, () -> {
			afterGroups.add(root);
			Cube texSrc = null;
			for (Cube c : pieces)if (texSrc == null)texSrc = c;
			for (String k : new String[] {"base", "layer"}) {
				Object r = m.get(k);
				if (!(r instanceof Map))continue;
				Map<String, Object> rec = (Map<String, Object>) r;
				if (Boolean.TRUE.equals(rec.get("fromVanilla")))continue;// the game draws that sleeve itself again
				Cube src = cubeByUuid(String.valueOf(rec.get("up")));
				Cube c = rebuild(rec, root, src != null ? src : texSrc);
				afterElems.add(c);
			}
			for (OutlinerNode n : keep) {
				if (n instanceof Group) {
					((Group) n).addTo(root, -1);
					afterGroups.add((Group) n);
				} else if (n instanceof Cube) {
					((Cube) n).addTo(root, -1);
					afterElems.add((Cube) n);
				}
			}
			for (Cube c : pieces)c.remove(false);
			joint.remove(false);
			ProjectGenerator.updateAll();
			Canvas.updateAll();
		});
	}

	@SuppressWarnings("unchecked")
	private static Cube rebuild(Map<String, Object> rec, Group root, Cube texSrc) {
		Cube.CubeProperties cp = new Cube.CubeProperties();
		cp.name = String.valueOf(rec.get("name"));
		cp.origin = RootUtil.copyVec(root.origin);
		cp.from = jsVec((List<Object>) rec.get("from"));
		cp.to = jsVec((List<Object>) rec.get("to"));
		Cube c = new Cube(cp);
		c.inflate = num(rec.get("inflate"));
		c.mirror_uv = Boolean.TRUE.equals(rec.get("mirror"));
		boolean box = !Boolean.FALSE.equals(rec.get("box"));
		Object uo = rec.get("uvOffset");
		if (uo instanceof List)c.uv_offset = JsVec2.make(num(((List<Object>) uo).get(0)), num(((List<Object>) uo).get(1)));
		c.box_uv = box;
		c.addTo(root).init();
		if (texSrc != null)copyTextures(texSrc, c);
		if (!box && rec.get("faces") instanceof Map) {
			Map<String, Object> faces = (Map<String, Object>) rec.get("faces");
			for (Map.Entry<String, Object> e : faces.entrySet()) {
				Map<String, Object> fr = (Map<String, Object>) e.getValue();
				List<Object> uv = (List<Object>) fr.get("uv");
				PlusUvUtil.setUv(c, e.getKey(), new float[] {num(uv.get(0)), num(uv.get(1)), num(uv.get(2)), num(uv.get(3))});
				CubeFace cf = PlusUvUtil.face(c, e.getKey());
				if (cf != null) {
					cf.rotation = (int) num(fr.get("rot"));
					if (Boolean.TRUE.equals(fr.get("off")))cf.disable();
				}
			}
		}
		IdUtil.resetIds(c);
		CubeData cd = c.getData();
		if (Boolean.TRUE.equals(rec.get("vanilla")))cd.setVanilla(true);
		Object sid = rec.get("sid");
		if (sid != null) {
			long id = GroupData.parseId(String.valueOf(sid));
			if (id != 0 && !storeIdUsed(id))cd.setStoreID(id);
		}
		cd.flush();
		refresh(c);
		return c;
	}

	private static boolean storeIdUsed(long id) {
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++) {
			try {
				if (cubesAll.getAt(i).getData().getStoreID() == id)return true;
			} catch (Throwable e) {
			}
		}
		return false;
	}

	private static float num(Object o) {
		if (o instanceof Number)return ((Number) o).floatValue();
		try {
			return Float.parseFloat(String.valueOf(o));
		} catch (Exception e) {
			return 0;
		}
	}

	private static JsVec3 jsVec(List<Object> l) {
		return JsVec3.make(num(l.get(0)), num(l.get(1)), num(l.get(2)));
	}

	static Cube cubeByUuid(String uuid) {
		if (uuid == null)return null;
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			if (uuid.equals(c.uuid))return c;
		}
		return null;
	}

	// ====================================================================== 'Testar dobra' (preview only)

	/**
	 * Swings the joint group 0 -&gt; 90 -&gt; 0 degrees in the viewport for about 1.6 s: only the three.js object is
	 * rotated (no project data, no undo entry); the real pose is restored at the end (Canvas.updateAllBones).
	 */
	public static void testBend(Group joint) {
		Object m0 = joint.getData().getExtra(MARK);
		String dir = m0 instanceof Map ? String.valueOf(((Map<?, ?>) m0).get("dir")) : "front";
		nativeSwing(joint, "back".equals(dir) ? -1 : 1);
	}

	private static native void nativeSwing(Group g, int sign) /*-{
		var mesh = g.mesh;
		if (!mesh) return;
		var start = Date.now(), dur = 1600, base = mesh.rotation.x;
		var C = $wnd.Canvas;
		var step = function() {
			var t = (Date.now() - start) / dur;
			if (t >= 1 || !g.mesh) {
				try { if (C && C.updateAllBones) C.updateAllBones(); } catch (e) {}
				return;
			}
			var a = Math.sin(Math.PI * t) * Math.PI / 2;
			mesh.rotation.x = base + sign * a;
			$wnd.requestAnimationFrame(step);
		};
		$wnd.requestAnimationFrame(step);
	}-*/;
}
