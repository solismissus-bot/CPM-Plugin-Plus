package com.tom.cpm.blockbench.tools.view;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.ItemSlot;
import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.editor.DisplayItem;
import com.tom.cpm.shared.editor.anim.AnimationDisplayData;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.render.PlayerModelSetup.ArmPose;
import com.tom.cpm.web.client.resources.Resources;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-COPYXF, G66): 'Item na mão (prévia do editor do CPM)'. Shows the item CPM's editor shows for the selected
 * animation (sword, bow, shield, spyglass ...) in the hand / head slot, so you see where it is held without going in
 * game. A preview only:
 * <ul>
 * <li>Its meshes hang from an OWN root in Blockbench's scene (never under a group mesh): never in the .cpmproject,
 * .bbmodel, glTF or OBJ, never hit by clicks (raycast is a no-op), nothing in the file or the undo history; the on/off
 * choice is a per-user preference (PlusPrefs), the per-hand choices live in memory only.</li>
 * <li>Automatic (default) = CPM's rule (AnimationDisplayData.getFor(pose)): a vanilla-pose animation shows its item in
 * its hand; custom / gesture animations and Edit mode show nothing unless an item is picked for that hand.</li>
 * <li>Where: the group the user made an item slot for that hand (GroupData item renderer, found by slot, not by name;
 * item scale applied), else the arm / head root ('Posição padrão do Minecraft'). The spyglass always uses the HEAD
 * slot (Minecraft decides that).</li>
 * <li>Bow state = min(floor(progress*3), 2); crossbow (charging pose) = min(floor(progress*3+1), 3); progress =
 * playhead / animation length.</li>
 * <li>Hidden outside Edit / Animate mode and in non-CPM projects; disposed on project switch, format change and unload.</li>
 * </ul>
 */
public final class HeldItemPreview {
	public static final String TOGGLE_ID = "cpmplus_held_item_preview";
	public static final String OPTIONS_ID = "cpmplus_held_item_options";
	public static final String DIALOG_ID = "cpmplus_w3copyxf_helditem";
	/** PlusPrefs key (per user, never in a file) */
	static final String PREF = "cpmplus.heldItemPreview";
	static final String ROOT_NAME = "cpmplus_heldpreview_root";
	static final ItemSlot[] SLOTS = {ItemSlot.RIGHT_HAND, ItemSlot.LEFT_HAND, ItemSlot.HEAD};

	static boolean enabled;
	/** per slot: "auto", "none" or a DisplayItem name (memory only) */
	static final Map<ItemSlot, String> choice = new EnumMap<>(ItemSlot.class);
	/** -1 = follow the playhead, else a fixed bow / crossbow state */
	static int fixedState = -1;
	static Toggle toggle;
	static Object root;
	static final Map<ItemSlot, Object> meshes = new EnumMap<>(ItemSlot.class);
	static final Map<ItemSlot, String> meshKey = new EnumMap<>(ItemSlot.class);
	static final Map<String, Object> materials = new LinkedHashMap<>();
	static boolean scheduled;
	/** last update (QA hook / dialog): what each slot shows */
	static final Map<ItemSlot, Shown> shown = new EnumMap<>(ItemSlot.class);

	static final class Shown {
		DisplayItem item = DisplayItem.NONE;
		int state;
		String frame;
		boolean userSlot;
	}

	private HeldItemPreview() {
	}

	// ====================================================================== rules (pure)

	/** CPM's getItemState: bow min(floor(p*3), maxStates-1); crossbow while charging min(floor(p*3+1), maxStates-1) */
	public static int itemState(DisplayItem item, AnimationDisplayData data, float progress, int maxStates) {
		if (maxStates <= 1)return 0;
		float p = Float.isNaN(progress) ? 0 : Math.max(0, Math.min(1, progress));
		if (item == DisplayItem.CROSSBOW) {
			if (data == AnimationDisplayData.CROSSBOW_CH_LEFT || data == AnimationDisplayData.CROSSBOW_CH_RIGHT)
				return (int) Math.min(Math.floor(p * 3 + 1), maxStates - 1);
		} else if (item == DisplayItem.BOW) {
			return (int) Math.min(Math.floor(p * 3), maxStates - 1);
		}
		return 0;
	}

	/** the CPM pose data of an animation, or null (custom pose, gesture, value layer ...) */
	public static AnimationDisplayData displayData(Animation a) {
		if (a == null || a.type == null || !"string".equals(Js.typeof(a.type)))return null;
		Pair<AnimationType, VanillaPose> t = Animation.parseType(a.type);
		if (t.getKey() != AnimationType.POSE)return null;
		return AnimationDisplayData.getFor(t.getValue());
	}

	/** CPM's automatic item for a slot (ViewportPanelAnim.getHeldItem with the selected animation) */
	public static DisplayItem autoItem(ItemSlot slot, AnimationDisplayData dt) {
		if (dt != null && dt.slot == slot && dt.item != null)return dt.item;
		return DisplayItem.NONE;
	}

	// ====================================================================== state of the open project

	private static native boolean editOrAnimateMode() /*-{
		try {
			var M = $wnd.Modes;
			if (!M) return true;
			var id = typeof M.id === 'string' ? M.id : (M.selected && M.selected.id);
			if (!id) return true;
			return id === 'edit' || id === 'animate';
		} catch (e) { return false; }
	}-*/;

	private static native boolean animateMode() /*-{
		try {
			var M = $wnd.Modes;
			if (!M) return false;
			// Blockbench 5: Modes.animate is a boolean getter; older: a Mode object with 'selected'
			if (typeof M.animate === 'boolean') return M.animate;
			if (M.animate && typeof M.animate === 'object') return !!M.animate.selected;
			return M.id === 'animate' || !!(M.selected && M.selected.id === 'animate');
		} catch (e) { return false; }
	}-*/;

	private static native double playhead() /*-{
		try { var T = $wnd.Timeline; return T && typeof T.time === 'number' ? T.time : 0; } catch (e) { return 0; }
	}-*/;

	static Animation selectedAnimation() {
		try {
			Object a = Js.asPropertyMap(com.tom.cpm.blockbench.proxy.Global.getGlobal("Animation")).get("selected");
			return a != null && Js.isTruthy(a) ? Js.uncheckedCast(a) : null;
		} catch (Throwable e) {
			return null;
		}
	}

	static DisplayItem itemFor(ItemSlot slot, Animation anim, AnimationDisplayData dt) {
		String c = choice.getOrDefault(slot, "auto");
		if ("none".equals(c))return DisplayItem.NONE;
		if (!"auto".equals(c)) {
			for (DisplayItem d : DisplayItem.VALUES)if (d.name().equalsIgnoreCase(c))return d;
			return DisplayItem.NONE;
		}
		if (anim == null)return DisplayItem.NONE;// Edit mode / nothing selected: Automatic = none
		return autoItem(slot, dt);
	}

	/** the group whose frame the item uses: the user's item slot (by GroupData item renderer), else the vanilla root */
	static Group frameGroup(ItemSlot trSlot, boolean[] userSlot) {
		String n = trSlot.name().toLowerCase(Locale.ROOT);
		if (Group.all != null) {
			for (Group g : Group.all) {
				if (g.parent == Group.ROOT || !com.tom.cpm.blockbench.tools.model.CopyTransformEditor.isExported(g))continue;// a slot the export drops does not count
				String ir = g.getData().getItemRenderer();
				if (ir != null && ir.equalsIgnoreCase(n)) {
					userSlot[0] = true;
					return g;
				}
			}
		}
		userSlot[0] = false;
		switch (trSlot) {
		case HEAD:
			return RootUtil.findPart(PlayerModelParts.HEAD);
		case LEFT_HAND:
			return RootUtil.findPart(PlayerModelParts.LEFT_ARM);
		case RIGHT_HAND:
			return RootUtil.findPart(PlayerModelParts.RIGHT_ARM);
		default:
			return null;
		}
	}

	// ====================================================================== three.js (own scene root)

	private static native Object scene() /*-{
		try {
			var C = $wnd.Canvas;
			if (C && C.scene) return C.scene;
			return $wnd.scene || null;
		} catch (e) { return null; }
	}-*/;

	private static native Object newRoot(String name) /*-{
		var T = $wnd.THREE;
		if (!T) return null;
		var g = new T.Group();
		g.name = name;
		g.raycast = function() {};
		return g;
	}-*/;

	private static native boolean attached(Object root, Object scene) /*-{
		return !!(root && scene && root.parent === scene);
	}-*/;

	private static native void attach(Object root, Object scene) /*-{
		if (root.parent && root.parent !== scene) root.parent.remove(root);
		scene.add(root);
	}-*/;

	private static native Object material(String b64) /*-{
		var T = $wnd.THREE;
		var img = new $wnd.Image();
		var tex = new T.Texture(img);
		tex.magFilter = T.NearestFilter;
		tex.minFilter = T.NearestFilter;
		tex.generateMipmaps = false;
		tex.flipY = false;
		img.onload = function() { tex.needsUpdate = true; };
		img.src = 'data:image/png;base64,' + b64;
		var m = new T.MeshLambertMaterial({map: tex, transparent: true, alphaTest: 0.1, opacity: 0.85, side: T.DoubleSide});
		m.name = 'cpmplus_held_item';
		return m;
	}-*/;

	private static native Object newMesh(double[] pos, double[] uv, double[] normal, Object material, String name) /*-{
		var T = $wnd.THREE;
		var g = new T.BufferGeometry();
		g.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
		g.setAttribute('uv', new T.Float32BufferAttribute(uv, 2));
		g.setAttribute('normal', new T.Float32BufferAttribute(normal, 3));
		var m = new T.Mesh(g, material);
		m.name = name;
		m.raycast = function() {};
		m.matrixAutoUpdate = false;
		m.frustumCulled = false;
		m.renderOrder = 10;
		m.userData.cpmplus_preview = true;
		return m;
	}-*/;

	private static native void addTo(Object parent, Object child) /*-{
		parent.add(child);
	}-*/;

	private static native void setVisible(Object o, boolean v) /*-{
		if (o) o.visible = v;
	}-*/;

	/** mesh.matrix = inverse(root world) * frame.mesh.matrixWorld * scale */
	private static native boolean place(Object mesh, Object root, Object groupMesh, double sx, double sy, double sz) /*-{
		var T = $wnd.THREE;
		if (!groupMesh || !groupMesh.matrixWorld) return false;
		try { groupMesh.updateWorldMatrix(true, false); } catch (e) {}
		try { root.updateWorldMatrix(true, false); } catch (e) {}
		var inv = new T.Matrix4().copy(root.matrixWorld);
		if (typeof inv.invert === 'function') inv.invert(); else inv.getInverse(root.matrixWorld);
		var m = new T.Matrix4().multiplyMatrices(inv, groupMesh.matrixWorld);
		m.multiply(new T.Matrix4().makeScale(sx, sy, sz));
		mesh.matrix.copy(m);
		mesh.matrixWorldNeedsUpdate = true;
		return true;
	}-*/;

	private static native void disposeMesh(Object mesh) /*-{
		try {
			if (mesh.parent) mesh.parent.remove(mesh);
			if (mesh.geometry) mesh.geometry.dispose();
		} catch (e) {}
	}-*/;

	private static native void disposeMaterial(Object m) /*-{
		try { if (m.map) m.map.dispose(); m.dispose(); } catch (e) {}
	}-*/;

	private static native void removeRoot(Object root) /*-{
		try { if (root && root.parent) root.parent.remove(root); } catch (e) {}
	}-*/;

	private static native double[] matrixOf(Object o) /*-{
		var a = o && o.matrix ? o.matrix.elements : [];
		var out = [];
		for (var i = 0; i < a.length; i++) out.push(a[i]);
		return out;
	}-*/;

	private static native Object groupMesh(Group g) /*-{
		return g && g.mesh ? g.mesh : null;
	}-*/;

	/** objects of the scene whose name starts with prefix (QA: no leftover meshes / roots) */
	private static native int countInScene(Object scene, String prefix) /*-{
		var n = 0;
		if (!scene || typeof scene.traverse !== 'function') return 0;
		scene.traverse(function(o) { if (o && typeof o.name === 'string' && o.name.indexOf(prefix) === 0) n++; });
		return n;
	}-*/;

	// ====================================================================== update

	public static void markDirty() {
		if (scheduled)return;
		scheduled = true;
		DomGlobal.requestAnimationFrame(t -> {
			scheduled = false;
			try {
				update();
			} catch (Throwable e) {
				DomGlobal.console.warn("CPM+: held item preview update failed", e);
			}
		});
	}

	static void hideAll() {
		for (Object m : meshes.values())setVisible(m, false);
		shown.clear();
	}

	/** recomputes every slot now */
	public static void update() {
		if (!enabled || !CPMCodec.isCPM() || !editOrAnimateMode()) {
			hideAll();
			return;
		}
		Object scene = scene();
		if (scene == null)return;
		if (root == null) {
			root = newRoot(ROOT_NAME);
			if (root == null)return;
		}
		if (!attached(root, scene))attach(root, scene);
		Animation anim = animateMode() ? selectedAnimation() : null;
		AnimationDisplayData dt = displayData(anim);
		float progress = 0;
		if (anim != null && anim.length > 0)progress = (float) (playhead() / anim.length);
		shown.clear();
		for (ItemSlot slot : SLOTS) {
			DisplayItem item = itemFor(slot, anim, dt);
			Object mesh = meshes.get(slot);
			if (item == DisplayItem.NONE || ItemModels.states(item) == 0) {
				setVisible(mesh, false);
				continue;
			}
			ItemSlot trSlot = item.positionOverride != null ? item.positionOverride : slot;
			boolean[] user = new boolean[1];
			Group fg = frameGroup(trSlot, user);
			Object gm = fg != null ? groupMesh(fg) : null;
			if (gm == null) {
				setVisible(mesh, false);// no arm / head root: nothing to hold it, no error
				continue;
			}
			int states = ItemModels.states(item);
			int state = fixedState >= 0 ? Math.min(fixedState, states - 1) : itemState(item, dt, progress, states);
			ItemModels.Geo geo = ItemModels.geometry(item, state, slot);
			if (geo == null) {
				setVisible(mesh, false);
				continue;
			}
			String key = item.name() + "/" + state;
			if (mesh == null || !key.equals(meshKey.get(slot))) {
				Object mat = materialFor(geo.texture);
				if (mat == null) {
					setVisible(mesh, false);
					continue;
				}
				if (mesh != null)disposeMesh(mesh);
				mesh = newMesh(geo.pos, geo.uv, geo.normal, mat, "cpmplus_held_item_" + slot.name().toLowerCase(Locale.ROOT));
				addTo(root, mesh);
				meshes.put(slot, mesh);
				meshKey.put(slot, key);
			}
			Vec3f sc = user[0] ? fg.getData().getItemScale() : null;
			boolean ok = place(mesh, root, gm, sc != null ? sc.x : 1, sc != null ? sc.y : 1, sc != null ? sc.z : 1);
			setVisible(mesh, ok);
			Shown s = new Shown();
			s.item = item;
			s.state = state;
			s.frame = fg.name;
			s.userSlot = user[0];
			shown.put(slot, s);
		}
	}

	static Object materialFor(String texture) {
		Object m = materials.get(texture);
		if (m != null)return m;
		String b64 = Resources.getResource("assets/cpm/textures/gui/" + texture + ".png");
		if (b64 == null)return null;
		m = material(b64);
		materials.put(texture, m);
		return m;
	}

	/** removes every mesh / material / texture and the root (project switch, format change, unload) */
	public static void disposeAll() {
		for (Object m : meshes.values())disposeMesh(m);
		meshes.clear();
		meshKey.clear();
		for (Object m : materials.values())disposeMaterial(m);
		materials.clear();
		removeRoot(root);
		root = null;
		shown.clear();
	}

	// ====================================================================== UI

	static void setEnabled(boolean v) {
		enabled = v;
		PlusPrefs.set(PREF, v);
		if (toggle != null && toggle.value != v) {
			toggle.value = v;
			try {
				toggle.updateEnabledState();
			} catch (Throwable e) {
			}
		}
		if (v)markDirty();
		else hideAll();
	}

	static Map<String, String> itemOptions(ItemSlot slot) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("auto", "cpmplus.w3copyxf.held.auto");
		m.put("none", "cpmplus.w3copyxf.held.none");
		for (DisplayItem d : DisplayItem.VALUES) {
			if (d == DisplayItem.NONE)continue;
			if (slot == ItemSlot.HEAD && !d.canBeOnHead)continue;
			m.put(d.name().toLowerCase(Locale.ROOT), "cpmplus.w3copyxf.held.item." + d.name().toLowerCase(Locale.ROOT));
		}
		return m;
	}

	static String slotInfo(ItemSlot slot) {
		boolean[] user = new boolean[1];
		Group g = frameGroup(slot, user);
		if (g == null)return PlusDialogs.trf("cpmplus.w3copyxf.held.noRoot", I18n.get("cpmplus.w3copyxf.held.slot." + slot.name().toLowerCase(Locale.ROOT)));
		return user[0] ? PlusDialogs.trf("cpmplus.w3copyxf.held.userSlot", g.name) : I18n.get("cpmplus.w3copyxf.held.defaultPos");
	}

	public static void openOptions() {
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w3copyxf.held.intro"));
		for (ItemSlot s : SLOTS) {
			String k = s.name().toLowerCase(Locale.ROOT);
			rows.add(FormRow.select(k, "cpmplus.w3copyxf.held.slot." + k, itemOptions(s), choice.getOrDefault(s, "auto")));
			rows.add(FormRow.info(k + "_info", null, slotInfo(s)));
		}
		Map<String, String> st = new LinkedHashMap<>();
		st.put("-1", "cpmplus.w3copyxf.held.state.follow");
		for (int i = 0; i < 4; i++)st.put(String.valueOf(i), PlusDialogs.trf("cpmplus.w3copyxf.held.state.n", i));
		rows.add(FormRow.select("state", "cpmplus.w3copyxf.held.state", st, String.valueOf(fixedState)));
		rows.add(FormRow.info("spyglass", null, "cpmplus.w3copyxf.held.spyglass"));
		rows.add(FormRow.info("pose", null, "cpmplus.w3copyxf.held.poseHint"));
		rows.add(FormRow.info("fine", null, "cpmplus.w3copyxf.held.fine"));
		PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
		fo.confirmKey = "cpmplus.w3copyxf.btn.apply";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form(DIALOG_ID, "cpmplus.w3copyxf.held.title", rows, r -> {
			for (ItemSlot s : SLOTS) {
				String v = r.getString(s.name().toLowerCase(Locale.ROOT));
				if (v != null)choice.put(s, v);
			}
			fixedState = r.getInt("state", -1);
			if (!enabled)setEnabled(true);
			else markDirty();
		}, fo);
	}

	/** gray hint condition: an item that makes Minecraft raise the arm (the preview shows the arm at rest) */
	static boolean raisesArm(DisplayItem d) {
		return d != null && d.pose != ArmPose.ITEM && d.pose != ArmPose.EMPTY;
	}

	// ====================================================================== install

	public static void install() {
		for (ItemSlot s : SLOTS)choice.put(s, "auto");
		fixedState = -1;
		enabled = PlusPrefs.getBool(PREF, true);
		toggle = PlusMenus.toggle(TOGGLE_ID, "cpmplus.w3copyxf.held.toggle", "back_hand", Cond.CPM_ONLY, enabled, HeldItemPreview::setEnabled);
		toggle.value = enabled;
		PlusMenus.add(PlusMenus.SUB_VIEW, toggle);
		PlusMenus.addAction(PlusMenus.SUB_VIEW, OPTIONS_ID, "cpmplus.w3copyxf.held.options", "pan_tool", Cond.CPM_ONLY, HeldItemPreview::openOptions);
		PluginStart.addEventListener("display_animation_frame", e -> markDirty());
		PluginStart.addEventListener("select_mode", e -> markDirty());
		PluginStart.addEventListener("update_keyframe_selection", e -> markDirty());
		PluginStart.addEventListener("finished_edit", e -> markDirty());
		PluginStart.addEventListener("select_animation", e -> markDirty());
		PluginStart.addEventListener("select_project", e -> {
			disposeAll();
			markDirty();
		});
		PluginStart.addEventListener("unselect_project", e -> disposeAll());
		PluginStart.addEventListener("close_project", e -> disposeAll());
		PluginStart.addEventListener("convert_format", e -> disposeAll());
		CPMCodec.onSelectionChange(HeldItemPreview::markDirty);
		PluginStart.cleanup.add(HeldItemPreview::disposeAll);
		if (PlusTestHooks.active())registerTestTools();
		markDirty();
	}

	public static void uninstall() {
		disposeAll();
		ItemModels.clearCache();
		toggle = null;
		enabled = false;
		scheduled = false;
	}

	// ====================================================================== QA hooks

	private static JsPropertyMap<Object> stateJson() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("enabled", enabled);
		Object sc = scene();
		r.set("rootInScene", root != null && attached(root, sc));
		r.set("meshesInScene", (double) countInScene(sc, "cpmplus_held_item"));
		r.set("rootsInScene", (double) countInScene(sc, ROOT_NAME));
		r.set("meshCount", (double) meshes.size());
		r.set("materialCount", (double) materials.size());
		JsArray<Object> list = new JsArray<>();
		for (ItemSlot s : SLOTS) {
			Shown sh = shown.get(s);
			JsPropertyMap<Object> o = JsPropertyMap.of();
			o.set("slot", s.name().toLowerCase(Locale.ROOT));
			o.set("item", sh != null ? sh.item.name().toLowerCase(Locale.ROOT) : "none");
			o.set("state", sh != null ? (double) sh.state : 0.0);
			o.set("frame", sh != null ? sh.frame : null);
			o.set("userSlot", sh != null && sh.userSlot);
			Object m = meshes.get(s);
			o.set("visible", m != null && Js.isTruthy(Js.asPropertyMap(m).get("visible")));
			if (m != null) {
				JsArray<Object> mm = new JsArray<>();
				for (double d : matrixOf(m))mm.push(d);
				o.set("matrix", mm);
			}
			list.push(o);
		}
		r.set("slots", list);
		return r;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w3copyxf.heldItem", o -> {
			String op = o.get("op") != null ? String.valueOf(o.get("op")) : "state";
			switch (op) {
			case "set": {
				if (o.has("enabled"))setEnabled(Js.isTruthy(o.get("enabled")));
				for (ItemSlot s : SLOTS) {
					String k = s.name().toLowerCase(Locale.ROOT);
					if (o.get(k) != null)choice.put(s, String.valueOf(o.get(k)));
				}
				if (o.get("fixedState") != null)fixedState = (int) Js.asDouble(o.get("fixedState"));
				update();
				return stateJson();
			}
			case "itemState": {
				DisplayItem item = DisplayItem.valueOf(String.valueOf(o.get("item")).toUpperCase(Locale.ROOT));
				AnimationDisplayData d = o.get("data") != null ? AnimationDisplayData.valueOf(String.valueOf(o.get("data")).toUpperCase(Locale.ROOT)) : null;
				double p = Js.asDouble(o.get("progress"));
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("ok", true);
				r.set("state", (double) itemState(item, d, (float) p, ItemModels.states(item)));
				return r;
			}
			case "dispose":
				disposeAll();
				return stateJson();
			default:
				update();
				return stateJson();
			}
		});
	}
}
