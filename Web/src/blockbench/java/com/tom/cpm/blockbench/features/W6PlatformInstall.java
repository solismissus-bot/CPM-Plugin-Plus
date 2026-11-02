package com.tom.cpm.blockbench.features;

/**
 * Feature installer of package W6-PLATFORM (CPM+ v0.8). Created empty by W0B-INFRA; only W6-PLATFORM fills it.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Register everything through the shared helpers
 * (PlusMenus, CPMCodec.registerProperty, PluginStart.addEventListener ...) so it is removed on unload.
 */
public final class W6PlatformInstall {
	private W6PlatformInstall() {
	}

	public static void install() {
	}

	public static void uninstall() {
	}
}