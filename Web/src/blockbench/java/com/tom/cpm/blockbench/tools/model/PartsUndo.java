package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeProperties;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.web.client.util.I18n;

/**
 * CPM+ (W4-EXPORTFIX, G1): 'Adicionar partes' actions (item slots, parrots, cape / elytra / armor) as ONE undo entry
 * each, planned first:
 * <ul>
 * <li>slots / roots that already exist are skipped; when nothing is left to add a toast says so and NO undo entry is
 * made;</li>
 * <li>a missing parent root of a slot (e.g. a deleted right_arm) is recreated with the exact vanilla pivot of the
 * project's skin type (RootUtil.makeRoot) and the vanilla part cube marked vanilla, so the model looks the same in
 * game as before (no root = vanilla part shown; root + unchanged vanilla cube = vanilla part shown);</li>
 * <li>the undo entry records the outliner, the created groups, cubes and textures (groups in both saves: Blockbench
 * removes created groups only when the 'before' has a groups array), so Ctrl+Z removes all of it and Ctrl+Y brings it
 * back with the item slot data.</li>
 * </ul>
 */
public final class PartsUndo {

	private PartsUndo() {
	}

	/** root part a slot hangs from */
	public static PlayerModelParts parentOf(ItemSlot s) {
		switch (s) {
		case HEAD:
			return PlayerModelParts.HEAD;
		case LEFT_HAND:
			return PlayerModelParts.LEFT_ARM;
		case RIGHT_HAND:
			return PlayerModelParts.RIGHT_ARM;
		case RIGHT_SHOULDER:
		case LEFT_SHOULDER:
			return PlayerModelParts.BODY;
		default:
			return null;
		}
	}

	private static boolean slotExists(Group parent, ItemSlot s) {
		if (parent == null)return false;
		String slotName = s.name().toLowerCase(Locale.ROOT);
		return Arrays.stream(Group.all).anyMatch(g -> g.parent == parent && slotName.equalsIgnoreCase(g.getData().getItemRenderer()));
	}

	private static String slotLabel(ItemSlot s) {
		return I18n.format("label.cpm.elem.item." + s.name().toLowerCase(Locale.ROOT));
	}

	private static String partLabel(String partName) {
		return I18n.get("label.cpm.elem." + partName);
	}

	/** 'Adicionar partes > Slots de item / Papagaios' */
	public static void addSlots(ItemSlot[] slots) {
		List<ItemSlot> toAdd = new ArrayList<>();
		List<ItemSlot> existing = new ArrayList<>();
		Set<PlayerModelParts> missingRoots = new LinkedHashSet<>();
		for (ItemSlot s : slots) {
			PlayerModelParts p = parentOf(s);
			if (p == null)continue;
			Group parent = RootUtil.findPart(p);
			if (slotExists(parent, s)) {
				existing.add(s);
				continue;
			}
			toAdd.add(s);
			if (parent == null)missingRoots.add(p);
		}
		if (toAdd.isEmpty()) {
			PlusTestHooks.count("w4exportfixNothingToAdd");
			PlusDialogs.toast(I18n.get("cpmplus.w4exportfix.parts.allSlotsExist"), "info", 5000, null);
			return;
		}
		SkinType skin = RootUtil.projectSkinType();
		List<Group> created = new ArrayList<>();
		List<OutlinerElement> cubes = new ArrayList<>();
		List<String> createdRoots = new ArrayList<>();
		PlusUndo.run(I18n.get("cpmplus.w4exportfix.undo.addSlots"), PlusUndo.structBefore(),
				() -> PlusUndo.structAfter(created, cubes, null), () -> {
					for (PlayerModelParts p : missingRoots) {
						Group root = RootUtil.makeRoot(p.getName(), skin);
						root.isOpen = true;
						created.add(root);
						cubes.add(addVanillaCube(root, p, skin));
						createdRoots.add(partLabel(p.getName()));
					}
					List<Group> slotGroups = new ArrayList<>();
					BBActions.addSlotsNoUndo(toAdd.toArray(new ItemSlot[0]), slotGroups);
					created.addAll(slotGroups);
				});
		StringBuilder sb = new StringBuilder();
		sb.append(I18n.format("cpmplus.w4exportfix.parts.added", join(toAdd)));
		if (!existing.isEmpty())sb.append(' ').append(I18n.format("cpmplus.w4exportfix.parts.existed", join(existing)));
		for (String r : createdRoots)sb.append(' ').append(I18n.format("cpmplus.w4exportfix.parts.rootCreated", r));
		sb.append(' ').append(I18n.get("cpmplus.w4exportfix.parts.undoHint"));
		PlusDialogs.toast(sb.toString(), "check", 8000, null);
	}

	private static String join(List<ItemSlot> l) {
		List<String> s = new ArrayList<>();
		for (ItemSlot i : l)s.add(slotLabel(i));
		return String.join(", ", s);
	}

	/**
	 * the vanilla box of part inside root (same math as a new project / import), marked as the vanilla cube: the
	 * export keeps the vanilla part (2nd layer, skin type) exactly as when the root group did not exist
	 */
	private static Cube addVanillaCube(Group root, PlayerModelParts part, SkinType skin) {
		RootUtil.CubeSpec spec = RootUtil.vanillaCubeSpec(part, skin, root.origin);
		CubeProperties c = new CubeProperties();
		c.name = part.getName();
		c.origin = RootUtil.copyVec(root.origin);
		c.from = spec.from;
		c.rotation = com.tom.cpm.blockbench.proxy.Vectors.JsVec3.make(0, 0, 0);
		c.uv_offset = spec.uvOffset;
		Cube cube = new Cube(c);
		c = new CubeProperties();
		c.to = spec.to;
		cube.extend(c);
		cube.inflate = spec.inflate;
		cube.mirror_uv = spec.mirror;
		cube.box_uv = true;
		cube.addTo(root).init();
		Texture tex = RootUtil.findSkinTexture();
		if (tex != null)cube.applyTexture(tex, true);
		cube.getData().setVanilla(true).flush();
		return cube;
	}

	/** 'Adicionar partes > Capa / Elytra / Armadura' */
	public static void addRoot(RootGroups c) {
		boolean any = false;
		for (RootModelType r : c.types) {
			if (RootUtil.findRootGroup(r.getName()) == null)any = true;
		}
		if (!any) {
			PlusTestHooks.count("w4exportfixNothingToAdd");
			PlusDialogs.toast(I18n.get("cpmplus.w4exportfix.parts.rootsExist"), "info", 5000, null);
			return;
		}
		List<Group> groups = new ArrayList<>();
		List<OutlinerElement> cubes = new ArrayList<>();
		List<Texture> tex = new ArrayList<>();
		UndoData before = PlusUndo.structBefore();
		before.textures = new Texture[0];// created sheet textures are removed by Ctrl+Z
		PlusUndo.run(I18n.get("cpmplus.w4exportfix.undo.addRoot"), before, () -> {
			UndoData a = PlusUndo.structAfter(groups, cubes, tex);
			if (a.textures == null)a.textures = new Texture[0];
			return a;
		}, () -> BBActions.addRootNoUndo(c, cubes, tex, groups));
		List<String> names = new ArrayList<>();
		for (Group g : groups)names.add(partLabel(g.name));
		PlusDialogs.toast(I18n.format("cpmplus.w4exportfix.parts.added", String.join(", ", names)) + " " + I18n.get("cpmplus.w4exportfix.parts.undoHint"), "check", 6000, null);
	}
}
