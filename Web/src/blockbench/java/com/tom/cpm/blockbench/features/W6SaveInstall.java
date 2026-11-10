package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.tools.export.FormatLimitsCheck;

/**
 * Feature installer of package W6-SAVE (CPM+ v0.8). Taken so far: G10 'Conferir limites do jogo' (export rows, never
 * blocking: cube > 25.5 px, position / offset / animation > 48 px, with the value the game receives).
 * PluginStart calls install() once per plugin start (inside its own try/catch) and uninstall() when the plugin unloads.
 */
public final class W6SaveInstall {
	private W6SaveInstall() {
	}

	public static void install() {
		ExportHooks.register(FormatLimitsCheck::check);
		// pivot more than 48 px from the folder above: one click moves it, nothing changes place
		com.tom.cpm.blockbench.tools.export.FarPivotFix.install();
		// G2: a body part the game shows although the project has no exported root group for it
		ExportHooks.register(com.tom.cpm.blockbench.tools.model.GhostParts::check);
		com.tom.cpm.blockbench.tools.model.GhostParts.install();
	}

	public static void uninstall() {
	}
}
