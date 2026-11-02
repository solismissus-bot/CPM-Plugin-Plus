package com.tom.cpm.blockbench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.ee.EmbeddedEditorHandler;
import com.tom.cpm.blockbench.format.AnimationWizard;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeProperties;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Interface;
import com.tom.cpm.blockbench.proxy.MenuBar;
import com.tom.cpm.blockbench.proxy.MenuBar.BarItem;
import com.tom.cpm.blockbench.proxy.MenuBar.BarMenu;
import com.tom.cpm.blockbench.proxy.MenuBar.BarMenuInit;
import com.tom.cpm.blockbench.proxy.Outliner;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Plugin;
import com.tom.cpm.blockbench.proxy.Plugin.Plugins;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Texture.TextureProperties;
import com.tom.cpm.blockbench.proxy.Toolbars;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.jq.JQueryNode;
import com.tom.cpm.blockbench.proxy.jq.JQueryNode.SpectrumInit;
import com.tom.cpm.blockbench.util.ConditionUtil;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.tools.model.PartsUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.PlayerPartValues;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.DirectPartValues;
import com.tom.cpm.shared.model.render.DirectParts;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.resources.Resources;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.base.Js;

public class BBActions {
	public static Toggle glowButton, hiddenButton, recolorButton;
	// CPM+ : action / menu ids are prefixed so they don't collide with the official plugin's
	// (BarItems is a global registry; deleting a shared id on unload would remove the other plugin's
	// button). Ids are not stored in .bbmodel files. Old toolbar layouts that referenced the official
	// ids simply don't show the button until it is re-added.
	public static final String ID = "cpmplus_";
	public static final String MENU_ID = "cpm_plus_main";
	public static final String WIZARD_ID = ID + "animation_wizard";
	public static Action reexportAction;
	/**
	 * CPM+ (W5-EFFECTS, G14): called inside the undo entry of 'Adicionar 2ª camada' with the cubes it created (the
	 * paint view marks them as 2nd-layer shells). Listeners are added / removed by the feature installers.
	 */
	public static final List<java.util.function.Consumer<List<OutlinerElement>>> secondLayerListeners = new ArrayList<>();

	public static void load() {
		List<Object> cpmMenu = new ArrayList<>();
		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.name = I18n.get("bb-button.openCPMProject");
			a.description = "";
			a.icon = "icon-player";
			a.category = "file";
			a.click = e -> ProjectConvert.open();
			Action importAct = new Action(ID + "import_cpmproject", a);
			MenuBar.addAction(importAct, "file.5");
			PluginStart.cleanup.add(importAct::delete);
		}

		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.name = I18n.get("bb-button.exportCPMProject");
			a.description = "";
			a.icon = "icon-player";
			a.category = "file";
			a.click = e -> CPMCodec.codec.export();
			Action exportAct = new Action(ID + "export_cpmproject", a);
			MenuBar.addAction(exportAct, "file.export");
			PluginStart.cleanup.add(exportAct::delete);
		}

		Action reexportAct;
		{
			// CPM+ : quick re-export to the last written .cpmproject (no file dialog, desktop app).
			// Falls back to the normal export dialog when there is no previous path (or on web).
			Action.ActionProperties a = new Action.ActionProperties();
			a.name = "Re-export CPM Project (overwrite last export)";
			a.description = "Export the model again to the last exported .cpmproject file";
			a.icon = "icon-player";
			a.category = "file";
			a.condition = CPMCodec.formatCPM();
			a.click = e -> ProjectConvert.reexport();
			reexportAct = new Action(ID + "reexport_cpmproject", a);
			MenuBar.addAction(reexportAct, "file.export");
			PluginStart.cleanup.add(reexportAct::delete);
			reexportAction = reexportAct;
		}

		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.name = I18n.get("bb-button.openEmbeddedEditor");
			a.description = "";
			a.icon = "icon-player";
			a.category = "file";
			a.click = e -> {
				EmbeddedEditorHandler.open().catch_(ex -> {
					PopupDialogs.displayError("Failed to open Embedded Editor:", ex);
					return null;
				});
			};

			Action openCPM = new Action(ID + "open_embedded_editor", a);
			MenuBar.addAction(openCPM, "file");
			PluginStart.cleanup.add(openCPM::delete);
		}

		if(MinecraftObjectHolder.DEBUGGING) {
			Action.ActionProperties a = new Action.ActionProperties();
			a.icon = "refresh";
			a.name = "Reload CPM Plugin";
			a.click = e -> Arrays.stream(Plugins.all).filter(p -> p.id.equals(System.getProperty("cpm.pluginId"))).findFirst().ifPresent(Plugin::reload);
			Action reload = new Action(ID + "reload", a);
			MenuBar.addAction(reload, "file");
			PluginStart.cleanup.add(reload::delete);
		}

		{
			MenuBar.SubMenu m = new MenuBar.SubMenu();
			m.name = I18n.get("button.cpm.edit.parts");
			m.icon = "icon-player";
			m.id = ID + "add_parts";
			List<Object> parts = new ArrayList<>();
			{
				Action.ActionProperties a = new Action.ActionProperties();
				a.name = I18n.get("button.cpm.tools.add_skin_layer2");
				a.icon = "fa-shirt";
				a.click = e -> add2ndLayer();
				Action act = new Action(ID + "add_2nd_layer", a);
				PluginStart.cleanup.add(act::delete);
				parts.add(act);
			}
			RootGroups.forEach(c -> {
				Action.ActionProperties a = new Action.ActionProperties();
				a.name = I18n.get("button.cpm.root_group." + c.name().toLowerCase(Locale.ROOT));
				a.icon = BBGroups.getGroup(c).icon;
				a.click = e -> addRoot(c);
				Action act = new Action(ID + "add_" + c.name().toLowerCase(Locale.ROOT), a);
				PluginStart.cleanup.add(act::delete);
				parts.add(act);
			});
			{
				Action.ActionProperties a = new Action.ActionProperties();
				a.name = I18n.get("button.cpm.root_group.itemHoldPos");
				a.icon = "back_hand";
				a.click = e -> addSlots(ItemSlot.SLOTS);
				Action act = new Action(ID + "add_items", a);
				PluginStart.cleanup.add(act::delete);
				parts.add(act);
			}
			{
				Action.ActionProperties a = new Action.ActionProperties();
				a.name = I18n.get("button.cpm.root_group.parrots");
				a.icon = "fa-dove";
				a.click = e -> addSlots(ItemSlot.PARROTS);
				Action act = new Action(ID + "add_parrots", a);
				PluginStart.cleanup.add(act::delete);
				parts.add(act);
			}
			m.children = parts.toArray();
			cpmMenu.add(m);
		}

		cpmMenu.add("_");

		{
			Action.ToggleProperties a = new Action.ToggleProperties();
			a.name = I18n.get("label.cpm.glow");
			a.description = "";
			a.condition = new Action.Condition();
			a.condition.method = c -> Outliner.selected.length == 1 && Outliner.selected[0] instanceof Cube;
			a.onChange = v -> {
				if(Outliner.selected.length == 1 && Outliner.selected[0] instanceof Cube) {
					Cube cube = (Cube) Outliner.selected[0];
					Undo.initEdit(UndoData.make(cube));
					cube.glow = v;
					Cube.preview_controller.updateGeometry(cube);
					Undo.finishEdit(I18n.format("action.cpm.switch", I18n.get("label.cpm.glow")), UndoData.make(cube));
				}
			};
			glowButton = new Toggle(ID + "glow", a);
			cpmMenu.add(glowButton);
			PluginStart.cleanup.add(glowButton::delete);
		}

		{
			Action.ToggleProperties a = new Action.ToggleProperties();
			a.name = I18n.get("label.cpm.recolor");
			a.description = "";
			a.condition = new Action.Condition();
			a.condition.method = c -> Outliner.selected.length == 1 && Outliner.selected[0] instanceof Cube && !((Cube) Outliner.selected[0]).isColorCube();
			a.onChange = v -> {
				if(Outliner.selected.length == 1 && Outliner.selected[0] instanceof Cube) {
					Cube cube = (Cube) Outliner.selected[0];
					Undo.initEdit(UndoData.make(cube));
					cube.toggleRecolor();
					Cube.preview_controller.updateGeometry(cube);
					Undo.finishEdit(I18n.format("action.cpm.switch", I18n.get("label.cpm.recolor")), UndoData.make(cube));
				}
			};
			recolorButton = new Toggle(ID + "recolor", a);
			cpmMenu.add(recolorButton);
			PluginStart.cleanup.add(recolorButton::delete);
		}

		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.name = I18n.get("bb-button.recolor.set");
			a.icon = "format_paint";
			a.condition = new Action.Condition();
			a.condition.method = c -> Outliner.selected.length == 1 && Outliner.selected[0] instanceof Cube && ((Cube) Outliner.selected[0]).canRecolor();
			a.click = e -> {
				Dialog.DialogProperties dctr = new Dialog.DialogProperties();
				dctr.id = "cpm_pick_color";
				dctr.title = I18n.get("label.cpm.changeColor");
				dctr.setHtml("<p>" + I18n.formatBr("tooltip.cpm.recolor.colorbtn") + "<br><input type='text' id='cpm_recolor_picker' /></p>");// CPM+
				dctr.singleButton = true;
				dctr.buttons = new String[] {I18n.get("button.cpm.ok"), I18n.get("button.cpm.cancel")};
				int[] newColor = new int[] {-1};
				dctr.onConfirm = e_ -> {
					Cube cube = (Cube) Outliner.selected[0];
					Undo.initEdit(UndoData.make(cube));
					cube.setRecolor(newColor[0]);
					Cube.preview_controller.updateGeometry(cube);
					Undo.finishEdit(I18n.format("action.cpm.set", I18n.get("label.cpm.recolor")), UndoData.make(cube));

					return true;
				};
				dctr.onOpen = () -> {
					Cube cube = (Cube) Outliner.selected[0];
					int color = cube.getRecolor();
					newColor[0] = color;

					SpectrumInit si = new SpectrumInit();
					si.preferredFormat = "hex";
					si.showAlpha = false;
					si.showInput = true;
					si.color = String.format("#%06X", color);
					si.change = si.hide = si.move = ci -> {
						String c = ci.toHexString();
						newColor[0] = Integer.valueOf(c.substring(1, 7), 16);
					};

					JQueryNode.jq("#cpm_recolor_picker").spectrum(si);
				};
				new Dialog(dctr).show();
			};
			Action act = new Action(ID + "recolor_set", a);
			PluginStart.cleanup.add(act::delete);
			cpmMenu.add(act);
		}

		{
			Action.ToggleProperties a = new Action.ToggleProperties();
			a.name = I18n.get("label.cpm.hidden_effect");
			a.description = "";
			a.condition = new Action.Condition();
			a.condition.method = c -> Project.selected_groups != null && Project.selected_groups.length > 0;
			a.onChange = v -> {
				if(Project.selected_groups.length > 0) {
					Undo.initEdit(UndoData.make(Project.selected_groups.array()));
					Project.selected_groups.forEach(g -> g.hidden = v);
					Undo.finishEdit(I18n.format("action.cpm.switch", I18n.get("label.cpm.hidden_effect")), UndoData.make(Project.selected_groups.array()));
				}
			};
			hiddenButton = new Toggle(ID + "hidden", a);
			cpmMenu.add(hiddenButton);
			PluginStart.cleanup.add(hiddenButton::delete);
		}

		cpmMenu.add("_");

		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.name = I18n.get("bb-button.viewInEmbeddedEditor");
			a.icon = "launch";
			a.click = e -> {
				ProjectConvert.convertToCPM().then(b -> b.arrayBuffer()).then(dt -> {
					return EmbeddedEditorHandler.open().then(ee -> ee.openProject(dt));
				}).catch_(ex -> {
					ProjectConvert.reportExportError(ex);// CPM+ (W0C): translated export errors
					return null;
				});
			};
			Action act = new Action(ID + "view_in_embedded", a);
			PluginStart.cleanup.add(act::delete);
			cpmMenu.add(act);
		}
		cpmMenu.add(reexportAct);// CPM+

		BarMenuInit bmi = new BarMenuInit();
		bmi.condition = CPMCodec.formatCPM();
		bmi.name = "CPM";
		new BarMenu(MENU_ID, cpmMenu.toArray(), bmi);
		PluginStart.cleanup.add(() -> MenuBar.menus.delete(MENU_ID));

		Action openCPM;
		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.icon = "fa-plus-circle";
			a.name = I18n.get("bb-button.newAnimation");
			a.category = "animation";
			a.condition = CPMCodec.formatCPM();
			a.click = e -> AnimationWizard.open(null, null);
			openCPM = new Action(WIZARD_ID, a);
			PluginStart.cleanup.add(openCPM::delete);
		}

		boolean add = true;
		for (int i = 0; i < Toolbars.animations.children.length; i++) {
			BarItem bi = Toolbars.animations.children[i];
			if (bi == null || bi.id == null)continue;// CPM+ : null-safe
			if (bi.id.equals("add_animation")) {
				ConditionUtil.and(bi.condition, a -> bi.condition = a, CPMCodec.notCPM());
			} else if(bi.id.equals(WIZARD_ID)) {
				add = false;
				Toolbars.animations.children[i] = openCPM;
			}
		}
		if(add)
			Toolbars.animations.add(openCPM, "0");

		PluginStart.cleanup.add(() -> {
			for (int i = 0; i < Toolbars.animations.children.length; i++) {
				BarItem bi = Toolbars.animations.children[i];
				// CPM+ : separators/strings have no id (the NPE was swallowed and the button stayed after unload)
				if(bi == null || bi.id == null)continue;
				if(bi.id.equals(WIZARD_ID)) {
					JsArrayE<BarItem> ar = Js.cast(Toolbars.animations.children);
					ar.splice(i, 1);
					break;
				}
			}
		});

		Animation.menu.structure.forEach(v -> {
			if(!(v instanceof String)) {
				Action ac = Js.uncheckedCast(v);
				if(ac.name != null && ac.name.equals("menu.animation.properties")) {
					ConditionUtil.and(ac.condition, a -> ac.condition = a, CPMCodec.notCPM());
				}
			}
		});
		{
			Action.ActionProperties a = new Action.ActionProperties();
			a.category = "animation";
			a.icon = "list";
			a.name = "CPM Animation Properties";// CPM+ : clearly named (#923 / #944)
			a.click = e -> AnimationWizard.open(Js.uncheckedCast(e), null);
			a.condition = CPMCodec.formatCPM();
			Animation.menu.structure.push(a);
			PluginStart.cleanup.add(() -> {
				Animation.menu.structure.remove(a);
			});
		}
		// CPM+ : Blockbench's own properties dialog (double click / BB 5 menu) opens the CPM dialog
		AnimationWizard.installPropertiesHook();
		// CPM+ : make 'New CPM Animation' discoverable in the Animation menu too (#909 / #860 / #872)
		try {
			MenuBar.addAction(openCPM, "animation");
			PluginStart.cleanup.add(() -> {
				try {
					MenuBar.removeAction("animation." + WIZARD_ID);
				} catch (Throwable e) {
				}
			});
		} catch (Throwable e) {
		}

		Interface.updateInterface();
	}

	/** CPM+ (W0C): delegates to RootUtil (shared with the tools) */
	private static Group findPart(VanillaModelPart p) {
		return RootUtil.findPart(p);
	}

	/** CPM+ : the project's skin type (slim arms etc.), DEFAULT when unknown (RootUtil, W0C) */
	private static SkinType projectSkinType() {
		return RootUtil.projectSkinType();
	}

	/** CPM+ : the texture used as the skin sheet (same mapping as export; RootUtil, W0C) */
	private static Texture findSkinTexture() {
		return RootUtil.findSkinTexture();
	}

	/**
	 * CPM+ (W4, G1): planned first (existing slots skipped, a missing parent root recreated), ONE undo entry with the
	 * created groups in both saves; nothing to add = a toast and no undo entry (PartsUndo)
	 */
	private static void addSlots(ItemSlot[] slots) {
		PartsUndo.addSlots(slots);
	}

	/**
	 * CPM+ (W0C): the body of 'Add parts > Item hold slots / Parrots' without its own undo entry, for tools that run it
	 * inside an outer PlusUndo (put the returned groups into the entry's 'after'). Slots whose root group is missing
	 * or that already exist are skipped. Each slot gets its OWN pivot array with the parent's values (B3: it shared the
	 * parent's array, so moving the slot's pivot moved the arm's).
	 * @param newGroups receives the created slot groups (may be null)
	 */
	public static void addSlotsNoUndo(ItemSlot[] slots, List<Group> newGroups) {
		for (ItemSlot itemSlot : slots) {
			GroupProperties grp = new GroupProperties();
			grp.name = I18n.format("label.cpm.elem.item." + itemSlot.name().toLowerCase(Locale.ROOT));

			Group parent = null;
			switch (itemSlot) {
			case HEAD:
				parent = findPart(PlayerModelParts.HEAD);
				break;
			case LEFT_HAND:
				parent = findPart(PlayerModelParts.LEFT_ARM);
				break;
			case RIGHT_HAND:
				parent = findPart(PlayerModelParts.RIGHT_ARM);
				break;
			case RIGHT_SHOULDER:
			case LEFT_SHOULDER:
				parent = findPart(PlayerModelParts.BODY);
				break;
			default:
				break;
			}
			if(parent == null)continue;// CPM+ : no root group to attach to (the group was silently lost)
			// CPM+ : don't add the same slot twice
			final Group fp = parent;
			final String slotName = itemSlot.name().toLowerCase();
			boolean exists = Arrays.stream(Group.all).anyMatch(g -> g.parent == fp && slotName.equalsIgnoreCase(g.getData().getItemRenderer()));
			if(exists)continue;
			grp.origin = RootUtil.copyVec(parent.origin);// CPM+ (B3): a copy, never the parent's array
			Group gr = new Group(grp);
			gr.getData().setItemRenderer(slotName).flush();
			if(gr.origin == parent.origin)gr.origin = RootUtil.copyVec(parent.origin);// (B3) paranoid: never shared
			gr.init().addTo(parent);
			if(newGroups != null)newGroups.add(gr);
		}
	}

	/**
	 * CPM+ (W0C): 'Add parts > 2nd layer'. The cube math is RootUtil.secondLayerCubeSpec (shared with the tools);
	 * this keeps the action's behaviour (texture, skip existing layer cubes, one undo entry).
	 */
	private static void add2ndLayer() {
		UndoData udt = new UndoData();
		udt.elements = new OutlinerElement[0];
		udt.outliner = true;// CPM+ : new cubes are added into groups, the outliner must be part of the undo
		udt.groups = new Group[0];// CPM+ (W4, G1): groups array in both saves (Blockbench's undo iterates it)
		Undo.initEdit(udt);
		List<OutlinerElement> newParts = new ArrayList<>();
		// CPM+ : 'skin', 'skin.png' or the only texture (was exactly 'skin' -> untextured layers)
		Texture tex = findSkinTexture();
		SkinType skinType = projectSkinType();// CPM+ : slim projects got 4 px wide sleeves
		for(PlayerModelParts p : PlayerModelParts.VALUES) {
			if(p == PlayerModelParts.CUSTOM_PART)continue;
			Group gr = null;
			for(Group g : Group.all) {
				if(g.parent == Group.ROOT && g.name.equals(p.getName())) {
					gr = g;
					break;
				}
			}
			if(gr == null)continue;
			String layerName = RootUtil.secondLayerName(p, skinType);
			// CPM+ : don't add a second copy of an existing layer cube
			boolean exists = false;
			for (int i = 0; i < gr.children.length; i++) {
				Object n = gr.children.getAt(i);
				if(n instanceof Cube && layerName.equals(((Cube) n).name))exists = true;
			}
			if(exists)continue;
			RootUtil.CubeSpec spec = RootUtil.secondLayerCubeSpec(p, skinType, gr.origin);
			CubeProperties cp = new CubeProperties();
			cp.name = layerName;
			cp.origin = gr.origin;
			cp.from = spec.from;
			Cube cube = new Cube(cp);
			cp.to = spec.to;
			cube.extend(cp);
			cube.inflate = spec.inflate;
			cube.box_uv = true;
			cube.uv_offset = spec.uvOffset;
			cube.addTo(gr).init();
			newParts.add(cube);
			if(tex != null)cube.applyTexture(tex, true);
		}
		fireSecondLayer(newParts);// CPM+ (W5-EFFECTS, G14): inside the same undo entry

		udt = new UndoData();
		udt.elements = newParts.toArray(new OutlinerElement[0]);
		udt.outliner = true;// CPM+
		udt.groups = new Group[0];// CPM+ (W4, G1)
		Undo.finishEdit(I18n.format("button.cpm.tools.add_skin_layer2"), udt);
	}

	/** CPM+ (W5-EFFECTS, G14): tells the listeners which cubes 'Adicionar 2ª camada' created (errors isolated) */
	public static void fireSecondLayer(List<OutlinerElement> created) {
		if(created.isEmpty())return;
		for (java.util.function.Consumer<List<OutlinerElement>> l : new ArrayList<>(secondLayerListeners)) {
			try {
				l.accept(created);
			} catch (Throwable e) {
				elemental2.dom.DomGlobal.console.error("CPM+: 2nd layer listener failed", e);
			}
		}
	}

	/**
	 * CPM+ (W4, G1): roots that exist are skipped ('Essas partes j\u00e1 existem' + no undo entry when nothing is left); ONE
	 * undo entry with the created root groups, cubes and sheet textures (PartsUndo)
	 */
	private static void addRoot(RootGroups c) {
		PartsUndo.addRoot(c);
	}

	/**
	 * CPM+ (W0C): the body of 'Add parts > Cape / Elytra / Armor' without its own undo entry, for tools that run it
	 * inside an outer PlusUndo (put the created groups, cubes and textures into the entry's 'after'). Roots that already
	 * exist are skipped; a missing sheet texture (cape, elytra, armor1/2) is created from CPM's template.
	 * @param newParts receives the created cubes; newTex the created textures (both required)
	 */
	public static void addRootNoUndo(RootGroups c, List<OutlinerElement> newParts, List<Texture> newTex) {
		addRootNoUndo(c, newParts, newTex, null);
	}

	/** same, newGroups (may be null) receives the created root groups */
	public static void addRootNoUndo(RootGroups c, List<OutlinerElement> newParts, List<Texture> newTex, List<Group> newGroups) {
		SkinType skinType = projectSkinType();// CPM+
		for(RootModelType r : c.types) {
			if(Arrays.stream(Group.all).anyMatch(g -> g.parent == Group.ROOT && g.name.equals(r.getName())))continue;
			GroupProperties grp = new GroupProperties();
			grp.name = r.getName();
			grp.origin = RootUtil.rootOrigin(r, skinType);
			PartValues pv = DirectParts.getPartOverrides(r, skinType);
			if(pv instanceof DirectPartValues) {
				grp.rotation = RootUtil.rootRotation(r, skinType);
			}
			Group gr = new Group(grp).init();
			gr.isOpen = true;
			RootUtil.CubeSpec spec = RootUtil.vanillaCubeSpec(r, skinType, gr.origin);
			CubeProperties cp = new CubeProperties();
			cp.name = r.getName();
			cp.origin = gr.origin;
			cp.from = spec.from;
			TextureSheetType tst = c.getTexSheet(r);
			Cube cube = new Cube(cp);
			cube.mirror_uv = spec.mirror;
			cp = new CubeProperties();
			cp.to = spec.to;
			cube.extend(cp);
			cube.inflate = spec.inflate;
			Texture tex = null;
			for(Texture t : Texture.all.array()) {
				if(t.name.equals(tst.name().toLowerCase())) {
					tex = t;
					break;
				}
			}
			if(tex == null) {
				TextureProperties txp = new TextureProperties();
				txp.mode = "bitmap";
				txp.name = tst.name().toLowerCase();
				tex = new Texture(txp);
				tex.fromDataURL("data:image/png;base64," + Resources.getResource("assets/cpm/textures/template/" + tst.name().toLowerCase(Locale.ROOT) + ".png"));
				tex.add(false);
				tex.uv_width = tst.getDefSize().x;
				tex.uv_height = tst.getDefSize().y;
				newTex.add(tex);
			}
			cube.uv_offset = spec.uvOffset;
			cube.box_uv = true;
			cube.addTo(gr).init();
			newParts.add(cube);
			if(newGroups != null)newGroups.add(gr);
			cube.applyTexture(tex, true);
		}
	}

	public static enum BBGroups {
		CAPE(RootGroups.CAPE, "icon-player"),
		ELYTRA(RootGroups.ELYTRA, "fa-feather-alt"),
		ARMOR(RootGroups.ARMOR, "icon-armor_stand_small"),
		;
		public static final BBGroups[] VALUES = values();
		public final RootGroups group;
		public final String icon;

		private BBGroups(RootGroups group, String icon) {
			this.group = group;
			this.icon = icon;
		}

		public static BBGroups getGroup(RootGroups group) {
			for (int i = 0; i < VALUES.length; i++) {
				BBGroups g = VALUES[i];
				if(g.group == group)return g;
			}
			return null;
		}
	}
}
