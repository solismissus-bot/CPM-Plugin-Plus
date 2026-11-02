package com.tom.cpm.blockbench.util;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.PlayerPartValues;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.DirectPartValues;
import com.tom.cpm.shared.model.render.DirectParts;
import com.tom.cpm.shared.model.render.VanillaModelPart;

import jsinterop.base.Js;

/**
 * CPM+ (INFRA2, W0C-EXPORTCORE; frozen after W0C): root groups of a CPM project and the math of the vanilla part
 * cubes, shared by export, the 'Add parts' actions, new projects and every later tool. The formulas are the exact
 * ones the plugin always used (BlockbenchExport.makeRoot, ProjectGenerator.addPart, BBActions.addRoot /
 * add2ndLayer), which now delegate here: never copy this math into a tool.
 * <ul>
 * <li>Root group of a part: pivot (-pos.x, 24 - pos.y, pos.z), rotation (-rot.x, -rot.y, rot.z) of
 * {@code DirectParts.getPartOverrides(part, skinType)} (rotation only for cape / elytra).</li>
 * <li>Vanilla cube of a part inside a group with pivot o: from = (o.x - off.x - size.x, o.y - off.y - size.y,
 * o.z + off.z), to = from + size, box UV at the part's UV, inflate / mirror of the part values.</li>
 * <li>2nd skin layer cube: same box with inflate 0.25 and the 2nd layer UV (PlayerPartValues u2 / v2).</li>
 * </ul>
 * Vectors handed to Blockbench are always NEW arrays ({@link #copyVec}): never share a group's origin array with
 * another node (bug B3: moving one moved the other).
 */
public final class RootUtil {

	private RootUtil() {
	}

	// ------------------------------------------------------------------ parts and roots

	/** the CPM part with this name (player parts first, then cape / elytra / armor), case-insensitive; null when unknown */
	public static VanillaModelPart part(String name) {
		if(name == null)return null;
		for(PlayerModelParts pmp : PlayerModelParts.VALUES) {
			if(pmp.getName().equalsIgnoreCase(name))return pmp;
		}
		for(RootModelType pmp : RootModelType.VALUES) {
			if(pmp.getName().equalsIgnoreCase(name))return pmp;
		}
		return null;
	}

	/**
	 * A new (initialised, not yet placed in the outliner) root group named name for the part of that name, with the
	 * exact pivot / rotation of a vanilla root (old BlockbenchExport.makeRoot). The caller adds it (addTo) inside its
	 * undo entry.
	 */
	public static Group makeRoot(String name, SkinType skinType) {
		VanillaModelPart part = part(name);
		if(part == null)throw new IllegalArgumentException("CPM+: '" + name + "' is not a CPM root part");
		PartValues pv = DirectParts.getPartOverrides(part, skinType);
		Vec3f rot = new Vec3f();
		if(pv instanceof DirectPartValues) {
			rot = rot.add(((DirectPartValues)pv).getRotation());
		}
		Vec3f pos = pv.getPos();
		GroupProperties gp = new GroupProperties();
		gp.name = name;
		gp.origin = JsVec3.make(0, 24, 0);
		gp.rotation = JsVec3.make(-rot.x, -rot.y, rot.z);
		Group gr = new Group(gp).init();
		gp = new GroupProperties();
		gp.origin = JsVec3.make(-pos.x, 24 - pos.y, pos.z);
		gr.extend(gp);
		return gr;
	}

	/** the pivot a root group of this part gets (-pos.x, 24 - pos.y, pos.z) */
	public static JsVec3 rootOrigin(VanillaModelPart part, SkinType skinType) {
		Vec3f p = DirectParts.getPartOverrides(part, skinType).getPos();
		return JsVec3.make(-p.x, 24 - p.y, p.z);
	}

	/** the rotation a root group of this part gets (cape / elytra only; zero for the others) */
	public static JsVec3 rootRotation(VanillaModelPart part, SkinType skinType) {
		PartValues pv = DirectParts.getPartOverrides(part, skinType);
		if(pv instanceof DirectPartValues) {
			Vec3f r = ((DirectPartValues) pv).getRotation();
			return JsVec3.make(-r.x, -r.y, r.z);
		}
		return JsVec3.make(0, 0, 0);
	}

	/** the root group (direct child of the outliner root) whose name is exactly name, or null */
	public static Group findRootGroup(String name) {
		if(name == null || Group.all == null)return null;
		for(Group g : Group.all) {
			if(g.parent == Group.ROOT && name.equals(g.name))return g;
		}
		return null;
	}

	/** the root group of a vanilla part (exact name, as BBActions always matched it), or null */
	public static Group findPart(VanillaModelPart p) {
		return p == null ? null : findRootGroup(p.getName());
	}

	/** true when node (a Group or Cube) is inside ancestor at any depth (false for the node itself) */
	public static boolean isDescendant(Object node, Group ancestor) {
		if(node == null || ancestor == null)return false;
		Object p = parentOf(node);
		int guard = 0;
		while(p != null && p != Group.ROOT && guard++ < 10000) {
			if(p == ancestor)return true;
			p = parentOf(p);
		}
		return false;
	}

	/** the root group (direct child of the outliner root) that contains node, the node itself when it is one, or null */
	public static Group rootOf(Object node) {
		Object n = node;
		int guard = 0;
		while(n != null && n != Group.ROOT && guard++ < 10000) {
			Object p = parentOf(n);
			if(p == Group.ROOT)return n instanceof Group ? (Group) n : null;
			n = p;
		}
		return null;
	}

	private static Object parentOf(Object node) {
		if(node instanceof Group)return ((Group) node).parent;
		if(node instanceof Cube)return ((Cube) node).parent;
		if(node == null || !"object".equals(Js.typeof(node)))return null;
		return Js.asPropertyMap(node).get("parent");
	}

	/** a NEW array [x, y, z] with the values of v (never share Blockbench vector arrays between nodes) */
	public static JsVec3 copyVec(JsVec3 v) {
		if(v == null)return JsVec3.make(0, 0, 0);
		return JsVec3.make(v.x, v.y, v.z);
	}

	/** the project's skin type (slim arms etc.), DEFAULT when unknown */
	public static SkinType projectSkinType() {
		try {
			String st = Project.getData().skinType;
			if(st != null) {
				SkinType t = SkinType.get(st);
				if(t != null && t != SkinType.UNKNOWN)return t;
			}
		} catch (Throwable e) {
		}
		return SkinType.DEFAULT;
	}

	/**
	 * The texture the export uses as the skin sheet (BlockbenchExport uses this method, so both always agree): the
	 * first texture named 'skin' / 'skin.png' (any case); else the only texture; else the first texture whose name is
	 * not a sheet name ({@link #sheetOfName}: cape, elytra, armor1 ...); null when there is none (no texture, or only
	 * other sheets).
	 */
	public static Texture findSkinTexture() {
		Texture[] all = Texture.all.array();
		for(Texture t : all) {
			if(sheetOfName(t.name) == TextureSheetType.SKIN)return t;
		}
		if(all.length == 1)return all[0];
		for(Texture t : all) {
			if(sheetOfName(t.name) == null)return t;
		}
		return null;
	}

	/**
	 * The CPM texture sheet a Blockbench texture name stands for, as the export maps it: the sheet's name with or
	 * without '.png', any case ('skin', 'Cape.png', 'armor1' ...); null for any other name.
	 */
	public static TextureSheetType sheetOfName(String texName) {
		if(texName == null)return null;
		for(TextureSheetType s : TextureSheetType.VALUES) {
			if(texName.equalsIgnoreCase(s.name()) || texName.equalsIgnoreCase(s.name() + ".png"))return s;
		}
		return null;
	}

	// ------------------------------------------------------------------ cube math

	/** Geometry of a vanilla-part cube in Blockbench coordinates (all vectors are new arrays). */
	public static final class CubeSpec {
		public final JsVec3 from, to;
		public final JsVec2 uvOffset;
		public final float inflate;
		public final boolean mirror;
		/** the box size (to - from) */
		public final Vec3f size;

		CubeSpec(JsVec3 from, Vec3f size, JsVec2 uvOffset, float inflate, boolean mirror) {
			this.from = from;
			this.size = size;
			this.to = JsVec3.make(from.x + size.x, from.y + size.y, from.z + size.z);
			this.uvOffset = uvOffset;
			this.inflate = inflate;
			this.mirror = mirror;
		}
	}

	/** vanilla cube of part inside a root group at its default pivot ({@link #rootOrigin}) */
	public static CubeSpec vanillaCubeSpec(VanillaModelPart part, SkinType skinType) {
		return vanillaCubeSpec(part, skinType, rootOrigin(part, skinType));
	}

	/**
	 * Vanilla cube of part inside a group whose pivot is origin (exact ProjectGenerator.addPart / BBActions.addRoot /
	 * BlockbenchImport math): uv_offset = part UV, inflate = part MC scale (armor 0.5 / 1.0 ...), mirror as the part.
	 */
	public static CubeSpec vanillaCubeSpec(VanillaModelPart part, SkinType skinType, JsVec3 origin) {
		PartValues pv = DirectParts.getPartOverrides(part, skinType);
		Vec3f o = pv.getOffset();
		Vec3f s = pv.getSize();
		JsVec3 from = JsVec3.make(origin.x - o.x - s.x, origin.y - o.y - s.y, origin.z + o.z);
		return new CubeSpec(from, s, JsVec2.make(pv.getUV()), pv.getMCScale(), pv.isMirror());
	}

	/** 2nd skin layer cube of a player part inside a root group at its default pivot; null for non-player parts */
	public static CubeSpec secondLayerCubeSpec(VanillaModelPart part, SkinType skinType) {
		return part instanceof PlayerModelParts ? secondLayerCubeSpec(part, skinType, rootOrigin(part, skinType)) : null;
	}

	/**
	 * 2nd skin layer cube (hat / jacket / sleeves / pants) of a player part inside a group whose pivot is origin
	 * (exact BBActions.add2ndLayer math): the part's box, inflate 0.25, box UV at the 2nd layer UV. null for parts that
	 * have no 2nd layer (cape, elytra, armor).
	 */
	public static CubeSpec secondLayerCubeSpec(VanillaModelPart part, SkinType skinType, JsVec3 origin) {
		if(!(part instanceof PlayerModelParts))return null;
		PlayerPartValues pv = PlayerPartValues.getFor((PlayerModelParts) part, skinType);
		if(pv == null)return null;
		Vec3f o = pv.getOffset();
		Vec3f s = pv.getSize();
		JsVec3 from = JsVec3.make(origin.x - o.x - s.x, origin.y - o.y - s.y, origin.z + o.z);
		return new CubeSpec(from, s, JsVec2.make(pv.u2, pv.v2), 0.25F, false);
	}

	/** name of the 2nd layer cube of a player part ('label.cpm.layer_...' in the user's language), or null */
	public static String secondLayerName(VanillaModelPart part, SkinType skinType) {
		if(!(part instanceof PlayerModelParts))return null;
		PlayerPartValues pv = PlayerPartValues.getFor((PlayerModelParts) part, skinType);
		return pv == null ? null : com.tom.cpm.web.client.util.I18n.format("label.cpm.layer_" + pv.layer.getLowerName());
	}
}
