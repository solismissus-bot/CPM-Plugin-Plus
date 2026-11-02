package com.tom.cpm.blockbench.tools.settings;

import com.tom.cpm.shared.editor.Editor;

import elemental2.promise.Promise;

/**
 * Adapter for W3-MODELEXPORT: the single skin-layer dialog lives in tools.animprops (F24, beta). open() shows it,
 * stores the choice in ProjectData.animations free/def and resolves true on OK, false on cancel.
 */
public final class SkinLayersDialog {
	private SkinLayersDialog() {
	}

	/** opens the dialog for the open project (the editor argument is not needed by the F24 dialog); true = confirmed */
	public static Promise<Boolean> open(Editor editor) {
		return com.tom.cpm.blockbench.tools.animprops.SkinLayersDialog.open();
	}

	/** opens the dialog for the open project; true = confirmed */
	public static Promise<Boolean> open() {
		return com.tom.cpm.blockbench.tools.animprops.SkinLayersDialog.open();
	}
}