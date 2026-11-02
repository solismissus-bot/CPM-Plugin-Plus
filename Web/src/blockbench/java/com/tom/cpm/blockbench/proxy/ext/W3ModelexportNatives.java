package com.tom.cpm.blockbench.proxy.ext;

/**
 * CPM+ (W3-MODELEXPORT): Blockbench natives this package needs beyond the frozen proxies (typeof-guarded).
 */
public final class W3ModelexportNatives {
	private W3ModelexportNatives() {
	}

	/**
	 * Blockbench keybind object (new Keybind({key, ctrl, alt, shift})) for an Action's default shortcut (the user can
	 * rebind it in Blockbench's keybinds); null when Blockbench has no Keybind class.
	 */
	public static native Object keybind(String key, boolean ctrl, boolean alt, boolean shift) /*-{
		try {
			var K = (typeof Keybind === 'function') ? Keybind : ($wnd.Keybind || null);
			if (!K) return null;
			return new K({key: key, ctrl: ctrl, alt: alt, shift: shift});
		} catch (e) {
			return null;
		}
	}-*/;
}
