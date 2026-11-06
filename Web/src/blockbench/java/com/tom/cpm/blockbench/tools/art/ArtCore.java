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
		init(core(), bridge(test, actions));
		if (test)expose(core());
	}

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
