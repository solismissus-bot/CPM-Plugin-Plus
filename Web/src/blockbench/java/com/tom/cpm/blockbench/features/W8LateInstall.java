package com.tom.cpm.blockbench.features;

/**
 * Feature installer of package W8-LATE (CPM+ v0.8). Created empty by W0B-INFRA; only W8-LATE fills it.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Register everything through the shared helpers
 * (PlusMenus, CPMCodec.registerProperty, PluginStart.addEventListener ...) so it is removed on unload.
 */
public final class W8LateInstall {
	private W8LateInstall() {
	}

	public static void install() {
	}

	public static void uninstall() {
	}
}