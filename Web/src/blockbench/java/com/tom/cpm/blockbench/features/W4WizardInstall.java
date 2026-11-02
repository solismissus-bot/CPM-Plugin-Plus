package com.tom.cpm.blockbench.features;

/**
 * Feature installer of package W4-WIZARD (CPM+ v0.8). Created empty by W0B-INFRA; only W4-WIZARD fills it.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Register everything through the shared helpers
 * (PlusMenus, CPMCodec.registerProperty, PluginStart.addEventListener ...) so it is removed on unload.
 */
public final class W4WizardInstall {
	private W4WizardInstall() {
	}

	public static void install() {
		com.tom.cpm.blockbench.tools.animprops.AnimPropsInstall.install();// CPM+ (lite animprops: F15, F18, F24)
		com.tom.cpm.blockbench.plus.PlusGestureParts.install();// lite/gestparts (G54)
	}

	public static void uninstall() {
		com.tom.cpm.blockbench.plus.PlusGestureParts.uninstall();// lite/gestparts (G54)
	}
}