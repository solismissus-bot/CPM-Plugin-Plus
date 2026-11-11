package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMVisuals;
import com.tom.cpm.blockbench.format.GimbalRotate;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.tools.view.FastAnimPreview;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of the preview unit (CPM+ v0.8, lite/preview):
 * <ul>
 * <li>NEW-GIZMO: the opt-in "Girar um eixo por vez" toggle (GimbalRotate) in CPM+ &gt; Modelagem and CPM+ &gt;
 * Animacao, CPM projects only, default OFF, remembered per user (PlusPrefs).</li>
 * <li>NEW-VIS-ANIMATE lives in CPMVisuals (core): CPM visibility shown in Animate mode, the user's visibility back in
 * Edit mode / on a project switch / on unload; save and export read the persistent value (VisState, W0C).</li>
 * </ul>
 * PluginStart calls install() once per plugin start and uninstall() when the plugin unloads.
 */
public final class W1PreviewInstall {
	public static final String TOGGLE_ID = "cpmplus_w1preview_gimbal_rotate";
	public static final String PREF_ANIM_TIP = "cpmplus.gimbalRotate.animTipShown";

	private W1PreviewInstall() {
	}

	public static void install() {
		// CPM+ : Animate preview only for the nodes an animation moves (big models play at full speed)
		FastAnimPreview.install();
		PlusMenus.add(PlusMenus.SUB_ANIM, PlusMenus.toggle("cpmplus_w1preview_fast_anim", "cpmplus.w1preview.fastAnim", "speed", Cond.CPM_ONLY,
				FastAnimPreview.isEnabled(), FastAnimPreview::setEnabled));
		GimbalRotate.install();
		if (GimbalRotate.isAvailable()) {
			Toggle t = PlusMenus.toggle(TOGGLE_ID, "cpmplus.w1preview.gimbal", "360", Cond.CPM_ONLY, GimbalRotate.isEnabled(), W1PreviewInstall::onGimbalToggle);
			PlusMenus.add(PlusMenus.SUB_MODEL, t);
			PlusMenus.add(PlusMenus.SUB_ANIM, t);
			PluginStart.addEventListener("select_mode", W1PreviewInstall::onSelectMode);
		} else {
			// no Blockbench gizmo modules to hook: no toggle that would silently do nothing
			DomGlobal.console.info("CPM+: per-axis rotation is not available in this Blockbench (no TransformerModule)");
		}
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
		GimbalRotate.uninstall();
	}

	private static void onGimbalToggle(boolean on) {
		boolean stored = GimbalRotate.setEnabled(on);
		if (on) {
			boolean switched = GimbalRotate.useLocalSpace();
			PlusDialogs.toast(I18n.get(switched ? "cpmplus.w1preview.gimbal.onLocal" : "cpmplus.w1preview.gimbal.on"), "360", 10000, null);
		} else {
			PlusDialogs.toast(I18n.get("cpmplus.w1preview.gimbal.off"), "360", 5000, null);
		}
		if (!stored)PlusDialogs.quick(I18n.get("cpmplus.w1preview.gimbal.notStored"), 5000);
	}

	/** the per-axis tip for Animate mode, shown once per user, only while the option is ON in a CPM project */
	private static void onSelectMode(Object event) {
		try {
			if (!GimbalRotate.isActive() || PlusPrefs.getBool(PREF_ANIM_TIP, false))return;
			Object mode = event != null ? Js.asPropertyMap(event).get("mode") : null;
			Object id = mode != null ? Js.asPropertyMap(mode).get("id") : null;
			if (!"animate".equals(id) && !CPMVisuals.isAnimateMode())return;
			PlusPrefs.set(PREF_ANIM_TIP, true);
			PlusDialogs.toast(I18n.get("cpmplus.w1preview.gimbal.animTip"), "360", 15000, null);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: per-axis rotation tip", e);
		}
	}

	// ====================================================================== test tools (test mode only)

	private static void registerTestTools() {
		PlusTestHooks.register("w1preview.state", o -> {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("ok", true);
			r.set("changed", false);
			r.set("warnings", new JsArray<>());
			r.set("errors", new JsArray<>());
			r.set("gimbalAvailable", GimbalRotate.isAvailable());
			r.set("gimbalEnabled", GimbalRotate.isEnabled());
			r.set("gimbalActive", GimbalRotate.isActive());
			r.set("animate", CPMVisuals.isAnimateMode());
			return r;
		});
	}
}
