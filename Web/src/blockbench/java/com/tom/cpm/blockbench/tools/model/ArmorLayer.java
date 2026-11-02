package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeProperties;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Outliner;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Texture.TextureProperties;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.tools.model.CopyTransformEditor.Ct;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.resources.Resources;
import com.tom.cpm.web.client.util.I18n;

/**
 * CPM+ (W3-COPYXF, C3): 'Fazer camada de armadura' - port of CPM's Generators.makeArmorLayer to Blockbench terms.
 * <p>
 * For every selected cube: the chain of its parent groups up to (not including) its vanilla root is mirrored under
 * the chosen armor root, one group per level that copies EVERY channel of the original group (copy transform, all
 * flags, additive off, multiply 1); a level reuses a child whose copy-transform source already is that group. The
 * cube is copied into the last level with the same from / to / origin / rotation, inflate = the armor root's default
 * MC scale, box UV at 0,0 on the armor sheet (armor1, or armor2 for leggings). The armor root (and the armor1/armor2
 * textures) are created when missing ('Adicionar partes > Armadura'). Everything is ONE undo entry. New nodes get
 * fresh CPM ids (IdUtil.resetIds). Names never end in '_dup' (that suffix makes a top-level group a body copy).
 */
public final class ArmorLayer {
	public static final String ACTION_ID = "cpmplus_make_armor_layer";
	public static final String DIALOG_ID = "cpmplus_w3copyxf_armor";
	public static final String SUFFIX = "_armor";

	private ArmorLayer() {
	}

	/** the armor roots a cube of this player part can be worn on (first = default) */
	public static List<RootModelType> armorRootsFor(PlayerModelParts p) {
		List<RootModelType> l = new ArrayList<>();
		if (p == null)return l;
		switch (p) {
		case HEAD:
			l.add(RootModelType.ARMOR_HELMET);
			break;
		case BODY:
			l.add(RootModelType.ARMOR_BODY);
			l.add(RootModelType.ARMOR_LEGGINGS_BODY);
			break;
		case LEFT_ARM:
			l.add(RootModelType.ARMOR_LEFT_ARM);
			break;
		case RIGHT_ARM:
			l.add(RootModelType.ARMOR_RIGHT_ARM);
			break;
		case LEFT_LEG:
			l.add(RootModelType.ARMOR_LEFT_LEG);
			l.add(RootModelType.ARMOR_LEFT_FOOT);
			break;
		case RIGHT_LEG:
			l.add(RootModelType.ARMOR_RIGHT_LEG);
			l.add(RootModelType.ARMOR_RIGHT_FOOT);
			break;
		default:
			break;
		}
		return l;
	}

	/** inflate of a cube on this armor root (CPM: rmt.getDefaultSize(skinType).getMCScale()) */
	public static float inflateFor(RootModelType rmt) {
		return rmt.getDefaultSize(RootUtil.projectSkinType()).getMCScale();
	}

	/** texture name of the armor sheet of this root ('armor1' / 'armor2') */
	public static String sheetName(RootModelType rmt) {
		return RootGroups.ARMOR.getTexSheet(rmt).name().toLowerCase(Locale.ROOT);
	}

	/** selected cubes (Outliner.selected) */
	static List<Cube> selectedCubes() {
		List<Cube> out = new ArrayList<>();
		if (Outliner.selected == null)return out;
		for (OutlinerElement e : Outliner.selected)if (e instanceof Cube)out.add((Cube) e);
		return out;
	}

	/** the player part of the root that holds the cube, or null with a user message key in why[0] */
	static PlayerModelParts partOf(Cube c, String[] why) {
		Group root = RootUtil.rootOf(c);
		if (root == null) {
			why[0] = PlusDialogs.trf("cpmplus.w3copyxf.armor.loose", c.name);
			return null;
		}
		VanillaModelPart rt = root.getRootType();
		if (rt instanceof RootModelType) {
			why[0] = PlusDialogs.trf("cpmplus.w3copyxf.armor.underArmor", c.name, root.name);
			return null;
		}
		if (!(rt instanceof PlayerModelParts) || rt == PlayerModelParts.CUSTOM_PART) {
			why[0] = PlusDialogs.trf("cpmplus.w3copyxf.armor.unknownRoot", c.name, root.name);
			return null;
		}
		return (PlayerModelParts) rt;
	}

	public static void open() {
		List<Cube> cubes = selectedCubes();
		if (cubes.isEmpty()) {
			PlusDialogs.block("cpmplus.w3copyxf.armor.selectCubes");
			return;
		}
		PlayerModelParts part = null;
		for (Cube c : cubes) {
			String[] why = new String[1];
			PlayerModelParts p = partOf(c, why);
			if (p == null) {
				PlusDialogs.block(why[0]);
				return;
			}
			if (part != null && part != p) {
				PlusDialogs.block("cpmplus.w3copyxf.armor.mixedParts");
				return;
			}
			part = p;
		}
		List<RootModelType> roots = armorRootsFor(part);
		Map<String, String> opts = new LinkedHashMap<>();
		for (RootModelType r : roots)opts.put(r.getName(), "cpmplus.w3copyxf.armorRoot." + r.getName());
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf("cpmplus.w3copyxf.armor.intro", cubes.size())));
		rows.add(FormRow.select("root", "cpmplus.w3copyxf.armor.root", opts, roots.get(0).getName()));
		rows.add(FormRow.info("note", null, "cpmplus.w3copyxf.armor.note"));
		PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
		fo.confirmKey = "cpmplus.w3copyxf.btn.create";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form(DIALOG_ID, "cpmplus.w3copyxf.armor.title", rows, r -> {
			RootModelType rmt = null;
			String sel = r.getString("root");
			for (RootModelType t : roots)if (t.getName().equals(sel))rmt = t;
			if (rmt == null)rmt = roots.get(0);
			Result res = make(cubes, rmt);
			String msg = PlusDialogs.trf("cpmplus.w3copyxf.armor.done", res.cubes.size(), I18n.get("cpmplus.w3copyxf.armorRoot." + rmt.getName()));
			if (res.skipped > 0)msg += " " + PlusDialogs.trf("cpmplus.w3copyxf.armor.skipped", res.skipped);
			PlusDialogs.done(msg);
		}, fo);
	}

	/** what make() did */
	public static final class Result {
		public final List<Group> groups = new ArrayList<>();
		public final List<Cube> cubes = new ArrayList<>();
		public final List<Group> reused = new ArrayList<>();
		public final List<Texture> textures = new ArrayList<>();
		public int skipped;
	}

	/** the whole tool as ONE undo entry (also used by the QA hook) */
	public static Result make(List<Cube> cubes, RootModelType rmt) {
		Result res = new Result();
		List<OutlinerElement> elements = new ArrayList<>();
		UndoData before = PlusUndo.structBefore();
		before.textures = new Texture[0];
		PlusUndo.run(I18n.get("cpmplus.w3copyxf.armor.undo"), before, () -> {
			List<Group> g = new ArrayList<>(res.groups);
			UndoData d = PlusUndo.structAfter(g, elements, res.textures);
			d.textures = res.textures.toArray(new Texture[0]);
			return d;
		}, () -> {
			Group root = RootUtil.findRootGroup(rmt.getName());
			if (root == null) {
				List<OutlinerElement> np = new ArrayList<>();
				List<Group> ng = new ArrayList<>();
				BBActions.addRootNoUndo(RootGroups.ARMOR, np, res.textures, ng);
				elements.addAll(np);
				res.groups.addAll(ng);
				root = RootUtil.findRootGroup(rmt.getName());
				if (root == null)throw new IllegalStateException("armor root " + rmt.getName() + " could not be created");
			}
			Texture tex = sheetTexture(rmt, res.textures);
			float inflate = inflateFor(rmt);
			for (Cube c : cubes) {
				Group target = mirrorChain(c, root, res);
				String name = c.name + SUFFIX;
				if (hasSameCube(target, name, c)) {
					res.skipped++;
					continue;
				}
				CubeProperties cp = new CubeProperties();
				cp.name = name;
				cp.from = RootUtil.copyVec(c.from);
				cp.to = RootUtil.copyVec(c.to);
				cp.origin = RootUtil.copyVec(c.origin);
				cp.rotation = RootUtil.copyVec(c.rotation);
				Cube nc = new Cube(cp);
				nc.inflate = inflate;
				nc.box_uv = true;
				nc.mirror_uv = false;
				nc.uv_offset = JsVec2.make(0, 0);
				nc.addTo(target).init();
				if (tex != null)nc.applyTexture(tex, true);
				IdUtil.resetIds(nc);
				res.cubes.add(nc);
				elements.add(nc);
			}
		});
		return res;
	}

	/** mirrors the parent chain of cube (below its vanilla root) under armorRoot; returns the group the cube goes into */
	static Group mirrorChain(Cube cube, Group armorRoot, Result res) {
		List<Group> chain = new ArrayList<>();
		Object p = cube.parent;
		int guard = 0;
		while (p instanceof Group && ((Group) p).parent != Group.ROOT && guard++ < 10000) {
			chain.add(0, (Group) p);
			p = ((Group) p).parent;
		}
		Group cur = armorRoot;
		for (Group src : chain) {
			Group found = null;
			for (int i = 0; i < cur.children.length; i++) {
				OutlinerNode n = cur.children.getAt(i);
				if (!(n instanceof Group))continue;
				Ct ct = CopyTransformEditor.read((Group) n);
				if (ct != null && src.uuid.equals(ct.uuid)) {
					found = (Group) n;
					break;
				}
			}
			if (found != null) {
				if (!res.reused.contains(found) && !res.groups.contains(found))res.reused.add(found);
				cur = found;
				continue;
			}
			GroupProperties gp = new GroupProperties();
			gp.name = safeName(src.name + SUFFIX);
			gp.origin = RootUtil.copyVec(src.origin);
			gp.rotation = RootUtil.copyVec(src.rotation);
			Group ng = new Group(gp).init();
			ng.isOpen = true;
			ng.copyTransform = CopyTransformEditor.toJson(Ct.all(src.uuid));
			ng.addTo(cur);
			IdUtil.resetIds(ng);
			res.groups.add(ng);
			cur = ng;
		}
		return cur;
	}

	/** never a name ending in '_dup' (a top-level '_dup' group is a body copy) */
	static String safeName(String n) {
		return n.toLowerCase(Locale.ROOT).endsWith("_dup") ? n + "_" : n;
	}

	static boolean hasSameCube(Group g, String name, Cube src) {
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (!(n instanceof Cube))continue;
			Cube c = (Cube) n;
			if (name.equals(c.name) && c.from.equals(src.from) && c.to.equals(src.to))return true;
		}
		return false;
	}

	/** the armor1 / armor2 texture (created from CPM's template when missing; recorded in created) */
	static Texture sheetTexture(RootModelType rmt, List<Texture> created) {
		TextureSheetType tst = RootGroups.ARMOR.getTexSheet(rmt);
		String n = tst.name().toLowerCase(Locale.ROOT);
		for (Texture t : Texture.all.array()) {
			if (t.name != null && (t.name.equalsIgnoreCase(n) || t.name.equalsIgnoreCase(n + ".png")))return t;
		}
		String data = Resources.getResource("assets/cpm/textures/template/" + n + ".png");
		if (data == null)return null;
		TextureProperties txp = new TextureProperties();
		txp.mode = "bitmap";
		txp.name = n;
		Texture tex = new Texture(txp);
		tex.fromDataURL("data:image/png;base64," + data);
		tex.add(false);
		tex.uv_width = tst.getDefSize().x;
		tex.uv_height = tst.getDefSize().y;
		created.add(tex);
		return tex;
	}
}
