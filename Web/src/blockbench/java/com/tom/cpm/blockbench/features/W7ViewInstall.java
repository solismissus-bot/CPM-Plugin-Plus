package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.tools.model.SymmetryCheck;

/**
 * Feature installer of package W7-VIEW (CPM+ v0.8). Lite unit 'paint': G58 'Conferir simetria (pintura espelhada)'.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Everything is registered through the shared helpers
 * (PlusMenus, PluginStart.addEventListener ...) so it is removed on unload.
 */
public final class W7ViewInstall {
	private W7ViewInstall() {
	}

	public static void install() {
		SymmetryCheck.install();
	}

	public static void uninstall() {
	}
}
