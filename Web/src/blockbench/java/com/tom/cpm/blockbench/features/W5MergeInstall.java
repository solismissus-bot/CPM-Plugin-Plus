package com.tom.cpm.blockbench.features;

/**
 * Feature installer of package W5-MERGE (CPM+ v0.8). Created empty by W0B-INFRA; only W5-MERGE fills it.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Register everything through the shared helpers
 * (PlusMenus, CPMCodec.registerProperty, PluginStart.addEventListener ...) so it is removed on unload.
 */
public final class W5MergeInstall {
	private W5MergeInstall() {
	}

	public static void install() {
	}

	public static void uninstall() {
	}
}