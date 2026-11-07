package com.tom.cpm.blockbench.tools.art;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.tom.cpm.web.client.resources.Resources;

import elemental2.dom.DomGlobal;

/**
 * CPM+ "Arte e cores" (beta29): loads {@code assets/cpmplus_art/js/art_core.js} (ONE expression, no globals) and
 * talks to it. The core owns the brushes (Blockbench Brush API), the toolbar items and the dialogs; Java owns the
 * CPM+ menu actions, translations, preferences and the QA hooks.
 */
public final class ArtCore {
	public static final String CORE = "assets/cpmplus_art/js/art_core.js";
	/** beta30: the brush library (one engine, many community-style brushes), a module of the core */
	public static final String BRUSHES = "assets/cpmplus_art/js/brush_core.js";
	/** beta31: the "CPM+ Pintar" panel (every art tool with its name, the brushes, the options), a module of the core */
	public static final String PANEL = "assets/cpmplus_art/js/panel_core.js";
	/**
	 * beta33 "CPM+ Modelar": the modelling tools, modules of the same core (name, file). The engine first (registry,
	 * placement, undo / geometry helpers), the tool modules, the panel last (it lists what the others registered).
	 */
	public static final String[][] MODEL_MODULES = {
			{"model", "assets/cpmplus_model/js/model_core.js"},
			{"model_shapes", "assets/cpmplus_model/js/model_shapes.js"},
			{"model_adjust", "assets/cpmplus_model/js/model_adjust.js"},
			{"model_select", "assets/cpmplus_model/js/model_select.js"},
			{"model_texture", "assets/cpmplus_model/js/model_texture.js"},
			{"model_presets", "assets/cpmplus_model/js/model_presets.js"},
			{"model_parts", "assets/cpmplus_model/js/model_parts.js"},
			{"model_library", "assets/cpmplus_model/js/model_library.js"},
			{"model_merge", "assets/cpmplus_model/js/model_merge.js"},
			{"model_slice", "assets/cpmplus_model/js/model_slice.js"},
			{"model_check", "assets/cpmplus_model/js/model_check.js"},
			{"model_layer2", "assets/cpmplus_model/js/model_layer2.js"},
			{"model_bases", "assets/cpmplus_model/js/model_bases.js"},
			{"model_linked", "assets/cpmplus_model/js/model_linked.js"},
			{"model_build", "assets/cpmplus_model/js/model_build.js"},
			{"model_text", "assets/cpmplus_model/js/model_text.js"},
			{"model_vox", "assets/cpmplus_model/js/model_vox.js"},
			{"model_gltf", "assets/cpmplus_model/js/model_gltf.js"},
			{"model_convert", "assets/cpmplus_model/js/model_convert.js"},
			// beta34 (tools C): Juntar outro modelo (after the library and the converter: their insert / texture steps)
			{"model_combine", "assets/cpmplus_model/js/model_combine.js"},
			{"model_mesh2cube", "assets/cpmplus_model/js/model_mesh2cube.js"},
			{"model_joint", "assets/cpmplus_model/js/model_joint.js"},
			{"model_pose", "assets/cpmplus_model/js/model_pose.js"},
			{"model_flatten", "assets/cpmplus_model/js/model_flatten.js"},
			{"model_symmetry", "assets/cpmplus_model/js/model_symmetry.js"},
			{"model_firstperson", "assets/cpmplus_model/js/model_firstperson.js"},
			// beta34 (tools B): Contorno, Juntar texturas, Recortar com um cubo, Destacar cubo sob o mouse
			{"model_outline", "assets/cpmplus_model/js/model_outline.js"},
			{"model_stitch", "assets/cpmplus_model/js/model_stitch.js"},
			{"model_cut", "assets/cpmplus_model/js/model_cut.js"},
			{"model_hover", "assets/cpmplus_model/js/model_hover.js"},
			// beta34 (tools D): Arrumar UV automatico, Encaixe magnetico
			{"model_uvpack", "assets/cpmplus_model/js/model_uvpack.js"},
			{"model_magnet", "assets/cpmplus_model/js/model_magnet.js"},
			{"model_panel", "assets/cpmplus_model/js/model_panel.js"}
	};
	private static Object core;

	private ArtCore() {
	}

	/** the core API object (evaluated once per plugin instance) */
	public static Object core() {
		if (core == null) {
			String src = resource(CORE);
			if (src == null)throw new IllegalStateException("CPM+: " + CORE + " is missing from the plugin");
			core = evalExpression(src);
			if (core == null)throw new IllegalStateException("CPM+: could not start " + CORE);
		}
		return core;
	}

	/** starts the core with the translations / prefs bridge; actions = {ramp, swap, noise, autoshade} action ids */
	public static void start(Object actions, boolean test) {
		Object c = core();
		init(c, bridge(test, actions));
		String brushes = resource(BRUSHES);
		if (brushes == null)DomGlobal.console.warn("CPM+: " + BRUSHES + " is missing from the plugin");
		else loadModule(c, "brushes", brushes);
		String panel = resource(PANEL);
		if (panel == null)DomGlobal.console.warn("CPM+: " + PANEL + " is missing from the plugin");
		else loadModule(c, "panel", panel);
		for (String[] m : MODEL_MODULES) {
			String src = resource(m[1]);
			if (src == null)DomGlobal.console.warn("CPM+: " + m[1] + " is missing from the plugin");
			else loadModule(c, m[0], src);
		}
		if (test)expose(c);
	}

	/** the "CPM+ Pintar" panel, scrolled to a section (brushes, tools, colours, auto) or null for the top */
	public static void showPanel(String section) {
		showPanel(core(), section);
	}

	private static native void loadModule(Object core, String name, String src) /*-{
		core.loadModule(name, src);
	}-*/;

	private static native void showPanel(Object core, String section) /*-{
		if (!core.showPanel(section))throw new Error('the CPM+ art panel did not start');
	}-*/;

	public static void unload() {
		if (core != null) {
			try {
				uninstall(core);
			} catch (Throwable e) {
				DomGlobal.console.warn("CPM+: art tools did not unload cleanly", e);
			}
		}
		core = null;
		unexpose();
	}

	public static void open(String what) {
		open(core(), what);
	}

	public static void selectTool(String id) {
		selectTool(core(), id);
	}

	public static void showToolsAgain() {
		showAgain(core());
	}

	public static void pixelGradient() {
		pixelGradient(core());
	}

	private static native void pixelGradient(Object core) /*-{
		core.pixelGradient();
	}-*/;

	static String resource(String path) {
		try {
			String b64 = Resources.getResource(path);
			if (b64 == null)return null;
			return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: resource " + path + " not readable", e);
			return null;
		}
	}

	private static native Object evalExpression(String src) /*-{
		try {
			return (new $wnd.Function('return (' + src + '\n);'))();
		} catch (e) {
			console.error('CPM+: art tools core failed to start', e);
			return null;
		}
	}-*/;

	private static native Object bridge(boolean test, Object actions) /*-{
		return {
			t: function (k) {
				return @com.tom.cpm.web.client.util.I18n::get(Ljava/lang/String;)(String(k));
			},
			get: function (k) {
				var v = @com.tom.cpm.blockbench.util.PlusPrefs::getRaw(Ljava/lang/String;)(String(k));
				return v == null ? null : v;
			},
			set: function (k, v) {
				@com.tom.cpm.blockbench.util.PlusPrefs::set(Ljava/lang/String;Ljava/lang/Object;)(String(k), v);
			},
			// beta33: a node copied by a modelling tool forgets its CPM ids (IdUtil, inside the tool's undo entry)
			resetIds: function (node) {
				@com.tom.cpm.blockbench.util.IdUtil::resetIds(Ljava/lang/Object;)(node);
			},
			test: test,
			actions: actions
		};
	}-*/;

	private static native void init(Object core, Object bridge) /*-{
		core.init(bridge);
	}-*/;

	private static native void uninstall(Object core) /*-{
		core.uninstall();
	}-*/;

	private static native void open(Object core, String what) /*-{
		core.open(what);
	}-*/;

	private static native void selectTool(Object core, String id) /*-{
		core.selectTool(id);
	}-*/;

	private static native void showAgain(Object core) /*-{
		core.showToolsAgain();
	}-*/;

	/** QA: the core is reachable as window.__cpmplusArt while the test hooks are on */
	private static native void expose(Object core) /*-{
		$wnd.__cpmplusArt = core;
	}-*/;

	private static native void unexpose() /*-{
		try {
			delete $wnd.__cpmplusArt;
		} catch (e) {
		}
	}-*/;
}
