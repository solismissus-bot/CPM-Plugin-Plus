package com.tom.cpm.blockbench.tools.animprops;

import java.util.HashMap;
import java.util.Map;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.LayerCapacity;

import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (lite unit animprops: F15, F18, F24): registrations, called from the W4-WIZARD installer. The animation dialog
 * itself (F15 / F18) is Blockbench's animation properties dialog routed to {@code AnimationWizard} (no registration);
 * F24 gets a direct menu entry (CPM+ &gt; Configurações) because the settings hub (F11) is not in this build.
 */
public final class AnimPropsInstall {
	public static final String ACTION_SKIN_LAYERS = "cpmplus_animprops_skin_layers";

	private AnimPropsInstall() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_SETTINGS, ACTION_SKIN_LAYERS, "cpmplus.animprops.layers.menu", "layers", Cond.CPM_ONLY, SkinLayersDialog::open);
		if(PlusTestHooks.active())registerTestTools();
	}

	private static Animation anim(JsPropertyMap<Object> o) {
		Object n = o.get("name");
		if(n == null || Animation.all == null)return null;
		for (Animation a : Animation.all)if(a != null && String.valueOf(n).equals(a.name))return a;
		return null;
	}

	private static void registerTestTools() {
		// F24: the project's pluginData (raw string + parsed), optionally replacing pd.animations first
		PlusTestHooks.register("animprops.pluginData", o -> {
			Object set = o.get("set");
			if(set != null) {
				ProjectData pd = Project.getData();
				Object anims = Js.asPropertyMap(set).get("anims");
				Map<String, Object> m = anims != null ? JsonUtil.asMap(JsonUtil.fromJs(anims)) : null;
				pd.animations = m != null ? new HashMap<>(m) : new HashMap<>();
				pd.flush();
			}
			JsPropertyMap<Object> r = JsPropertyMap.of();
			String raw = Project.pluginData;
			r.set("raw", raw);
			r.set("data", raw != null && !raw.isEmpty() ? JsonUtil.toJs(JsonUtil.parse(raw)) : null);
			return r;
		});
		// F24: opens the dialog; resolves true on OK
		PlusTestHooks.register("animprops.skinLayers", o -> SkinLayersDialog.open());
		PlusTestHooks.register("animprops.capacity", o -> {
			LayerCapacity.Result c = LayerCapacity.checkProject();
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("count", (double) c.count);
			r.set("free", (double) c.freeLayers);
			r.set("ok", c.ok());
			return r;
		});
		// F18: what the dialog decides for an animation at a quality
		PlusTestHooks.register("animprops.predict", o -> {
			Animation a = anim(o);
			Object f = o.get("fps");
			int fps = f != null ? (int) Js.asDouble(f) : 20;
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("found", a != null);
			r.set("resample", AnimProps.willResample(a, fps));
			r.set("shortVisibility", AnimProps.shortVisibility(a, fps));
			r.set("frames", (double) AnimProps.predictFrames(a, fps));
			return r;
		});
	}
}
