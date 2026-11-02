package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.tools.model.ArmorLayer;
import com.tom.cpm.blockbench.tools.model.CopyTransformEditor;
import com.tom.cpm.blockbench.tools.model.CopyTransformEditor.Ct;
import com.tom.cpm.blockbench.tools.model.GroupEffects;
import com.tom.cpm.blockbench.tools.view.HeldItemPreview;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W3-COPYXF (CPM+ v0.8): C2 'Copiar movimento de...' (copy transform editor), C3 'Fazer
 * camada de armadura', C5 'Sem animação do Minecraft' + 'Escolher slot de item...', G20 'Criar cópia da parte do
 * corpo...', G66 'Item na mão (prévia do editor do CPM)'. Menus: CPM+ > Modelagem / Prévia and the group / cube
 * context menus. Export checks: dangling copy transform (fix 'Remover'), copy transform on a root (info),
 * '&lt;part&gt;_dup&lt;N&gt;' (fix 'Renomear'), 'Sem animação do Minecraft' on a non-root group (fix 'Desligar').
 */
public final class W3CopyxfInstall {
	static final boolean GROUP_EFFECTS_ENABLED = true;

	private W3CopyxfInstall() {
	}

	/** Blockbench's context menu of a node class (Group / Cube), or null when this Blockbench has none */
	private static native Menu protoMenu(String cls) /*-{
		try {
			var C = $wnd[cls];
			var m = C && C.prototype && C.prototype.menu;
			return m && typeof m.addAction === 'function' ? m : null;
		} catch (e) {
			return null;
		}
	}-*/;

	private static boolean oneNonRootGroup() {
		java.util.List<Group> s = GroupEffects.selectedGroups();
		return s.size() == 1 && s.get(0).parent != Group.ROOT;
	}

	public static void install() {
		// ---------------------------------------------------------------- C2 copy transform
		Action ct = PlusMenus.action(CopyTransformEditor.ACTION_ID, "cpmplus.w3copyxf.ct.menu", "link", Cond.CPM_ONLY,
				() -> !GroupEffects.selectedGroups().isEmpty(), CopyTransformEditor::open);
		PlusMenus.add(PlusMenus.SUB_MODEL, ct);
		CopyTransformEditor.installChecks();

		// ---------------------------------------------------------------- C3 armor layer
		Action armor = PlusMenus.action(ArmorLayer.ACTION_ID, "cpmplus.w3copyxf.armor.menu", "icon-armor_stand_small", Cond.CPM_ONLY, null, ArmorLayer::open);
		PlusMenus.add(PlusMenus.SUB_MODEL, armor);

		// ---------------------------------------------------------------- C5 + G20 group effects
		// OFF in the beta: the reuse found real bugs (moving pieces into the body copy makes no undo entry; the
		// "Sem animação do Minecraft" flag on a root is lost on re-import; the G66 held item preview changes the
		// animation files). The Copy Transform editor (C2) and the armor layer (C3) above stay on.
		Toggle dva = null;
		Action slot = null, dup = null;
		if (GROUP_EFFECTS_ENABLED) {
		dva = PlusMenus.toggle(GroupEffects.DVA_ID, "cpmplus.w3copyxf.dva.menu", "motion_photos_off", Cond.CPM_ONLY, false, GroupEffects::onDvaToggle);
		dva.condition = Cond.CPM_ONLY.toCondition(GroupEffects::allSelectedRoots);// only when every selected group is a root
		GroupEffects.setToggle(dva);
		PlusMenus.add(PlusMenus.SUB_MODEL, dva);
		CPMCodec.onSelectionChange(GroupEffects::syncDva);

		slot = PlusMenus.action(GroupEffects.ITEM_SLOT_ID, "cpmplus.w3copyxf.slot.menu", "back_hand", Cond.CPM_ONLY,
				W3CopyxfInstall::oneNonRootGroup, GroupEffects::openItemSlot);
		PlusMenus.add(PlusMenus.SUB_MODEL, slot);

		dup = PlusMenus.action(GroupEffects.DUP_ID, "cpmplus.w3copyxf.dup.menu", "content_copy", Cond.CPM_ONLY, null, GroupEffects::openDup);
		PlusMenus.add(PlusMenus.SUB_MODEL, dup);
		GroupEffects.installChecks();
		}

		// ---------------------------------------------------------------- context menus
		Menu gm = protoMenu("Group");
		if (gm != null) {
			PlusMenus.addToContext(gm, ct, "");
			if (dva != null)PlusMenus.addToContext(gm, dva, "");
			if (slot != null)PlusMenus.addToContext(gm, slot, "");
			if (dup != null)PlusMenus.addToContext(gm, dup, "");
		}
		Menu cm = protoMenu("Cube");
		if (cm != null)PlusMenus.addToContext(cm, armor, "");

		// ---------------------------------------------------------------- G66 held item preview
		if (GROUP_EFFECTS_ENABLED)HeldItemPreview.install();

		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
		if (GROUP_EFFECTS_ENABLED)HeldItemPreview.uninstall();
		GroupEffects.setToggle(null);
	}

	// ====================================================================== QA hooks (test mode only)

	private static JsPropertyMap<Object> ok() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}

	private static Group group(Object nameOrUuid) {
		if (nameOrUuid == null || Group.all == null)return null;
		String s = String.valueOf(nameOrUuid);
		for (Group g : Group.all)if (s.equals(g.uuid))return g;
		for (Group g : Group.all)if (s.equals(g.name))return g;
		return null;
	}

	private static void registerTestTools() {
		// C2: the sources offered for a target (names) and the JSON writer
		PlusTestHooks.register("w3copyxf.ct", o -> {
			JsPropertyMap<Object> r = ok();
			String op = o.get("op") != null ? String.valueOf(o.get("op")) : "sources";
			if ("allJson".equals(op)) {
				r.set("json", CopyTransformEditor.toJson(Ct.all(String.valueOf(o.get("uuid")))));
				return r;
			}
			Group target = group(o.get("target"));
			JsArray<Object> a = new JsArray<>();
			for (Group g : CopyTransformEditor.sources(target))a.push(g.name);
			r.set("sources", a);
			return r;
		});
		// C3: what the armor layer uses for a root (inflate = the root's default MC scale for the project's skin type)
		PlusTestHooks.register("w3copyxf.armor", o -> {
			JsPropertyMap<Object> r = ok();
			String root = o.get("root") != null ? String.valueOf(o.get("root")) : "armor_body";
			RootModelType rmt = null;
			for (RootModelType t : RootModelType.VALUES)if (t.getName().equals(root))rmt = t;
			if (rmt == null)throw new IllegalArgumentException("unknown root " + root);
			r.set("inflate", (double) ArmorLayer.inflateFor(rmt));
			r.set("sheet", ArmorLayer.sheetName(rmt));
			JsArray<Object> roots = new JsArray<>();
			for (PlayerModelParts p : PlayerModelParts.VALUES)for (RootModelType t : ArmorLayer.armorRootsFor(p))roots.push(p.getName() + ">" + t.getName());
			r.set("choices", roots);
			return r;
		});
		// C5 / G20: selection state and the '<part>_dup<N>' parser
		PlusTestHooks.register("w3copyxf.groups", o -> {
			JsPropertyMap<Object> r = ok();
			r.set("dupFix", GroupEffects.numberedDupFix(o.get("name") != null ? String.valueOf(o.get("name")) : null));
			JsArray<Object> a = new JsArray<>();
			for (Object n : GroupEffects.moveCandidates(null))a.push(n instanceof Group ? ((Group) n).name : n instanceof Cube ? ((Cube) n).name : "?");
			r.set("moveCandidates", a);
			r.set("allSelectedRoots", GroupEffects.allSelectedRoots());
			r.set("dvaValue", GroupEffects.dvaValue());
			JsArray<Object> sel = new JsArray<>();
			for (Group g : GroupEffects.selectedGroups())sel.push(g.name);
			r.set("selected", sel);
			return r;
		});
	}
}
