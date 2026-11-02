package com.tom.cpm.blockbench.format;

import com.tom.cpm.blockbench.util.PlusPrefs;

/**
 * CPM+ (NEW-GIZMO): opt-in "rotate one axis at a time" mode for the Rotate gizmo in CPM projects.
 * <p>
 * Blockbench stores rotations as ZYX Euler angles. Dragging a gizmo ring turns the part exactly around that ring and
 * Blockbench rewrites all three angles so the result matches (js/modeling/transform.js rotateOnAxis,
 * js/animations/animation_transform.js onMove). That is why "+15 on X" can also write about -5 on Y in Global /
 * Parent space. It is upstream behaviour (Blockbench #946, #720, #3316, #3388), the same in every format, and the
 * exported model looks in game exactly like the Blockbench preview.
 * <p>
 * With this mode on (CPM projects only, default OFF, per user in PlusPrefs {@value #PREF_KEY}) a drag on the X / Y /
 * Z ring changes only that one Euler value, exactly like typing in the Transform panel / keyframe fields:
 * <ul>
 * <li>Edit mode: Blockbench's own slider path, {@code rotateOnAxis(n => n + diff, axis, true)} (multi-selection:
 * every rotated object gets +diff on that axis, like the slider).</li>
 * <li>Animate mode: Blockbench's own single-value branch ({@code keyframe.offset(axis, diff)}). The module only takes
 * it when {@code BarItems.rotation_space.value} is not 'global' / 'local', and its keyframe list is private, so the
 * hook sets the value to 'parent' for the duration of that ONE synchronous onMove call and puts it back in a finally
 * block (no BarSelect.set: no UI update, no onChange).</li>
 * </ul>
 * The free (white) ring, the other tools, other formats, onStart / onEnd (Undo), snapping and the tooltip stay
 * Blockbench's. Export is not touched: the values are plain ZYX Euler angles, exported as (-x, -y, z).
 * <p>
 * Trade-off: changing one ZYX value turns about the part's own X, about Y after Z and about the parent's Z. In Local
 * space the X ring matches the motion exactly, the Y / Z rings can drift when the other angles are not 0. So turning
 * the mode on switches the gizmo to Local space (the toast says so); the user may switch back.
 * <p>
 * Unhook is identity-checked: a module's onMove is put back only while it still is our wrapper; when another plugin
 * wrapped it later, its wrapper stays and ours becomes a plain pass-through.
 */
public final class GimbalRotate {
	public static final String PREF_KEY = "cpmplus.gimbalRotate";
	private static boolean enabled;
	private static Object hookHandle;

	private GimbalRotate() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/** turns the mode on/off for this user; @return false when the choice could not be stored (still applied now) */
	public static boolean setEnabled(boolean v) {
		enabled = v;
		return PlusPrefs.set(PREF_KEY, v);
	}

	/** the Blockbench gizmo modules could be hooked (Blockbench 5 TransformerModule); otherwise no toggle is shown */
	public static boolean isAvailable() {
		return hookHandle != null;
	}

	/** called by the hook on every gizmo move */
	public static boolean isActive() {
		return enabled && CPMCodec.isCPM();
	}

	public static void install() {
		enabled = PlusPrefs.getBool(PREF_KEY, false);
		if (hookHandle == null)hookHandle = hook();
	}

	public static void uninstall() {
		if (hookHandle != null)unhook(hookHandle);
		hookHandle = null;
	}

	/** Local rotation space for the Rotate gizmo (where the per-axis rings match the motion); true when changed */
	public static native boolean useLocalSpace() /*-{
		try {
			var rs = $wnd.BarItems && $wnd.BarItems.rotation_space;
			if (!rs || rs.value === 'local' || typeof rs.set !== 'function') return false;
			rs.set('local');
			if (typeof rs.onChange === 'function') rs.onChange();
			else if (typeof $wnd.updateSelection === 'function') $wnd.updateSelection();
			return rs.value === 'local';
		} catch (e) { return false; }
	}-*/;

	private static native Object hook() /*-{
		var TM = $wnd.TransformerModule;
		if (!TM || !TM.modules) return null;
		var active = function(context) {
			try {
				return !!context && typeof context.axis_number === 'number' &&
					@com.tom.cpm.blockbench.format.GimbalRotate::isActive()() &&
					!!$wnd.Toolbox && !!$wnd.Toolbox.selected && $wnd.Toolbox.selected.id === 'rotate_tool' &&
					!!$wnd.Transformer && $wnd.Transformer.axis !== 'E';
			} catch (e) { return false; }
		};
		var entries = [];
		var add = function(module, make) {
			var orig = module.onMove;
			var entry = {module: module, orig: orig, off: false};
			var w = make(orig, entry);
			w.__cpmplus_gimbal = true;
			w.__cpmplus_orig = orig;
			entry.wrapper = w;
			module.onMove = w;
			entries.push(entry);
		};
		var edit = TM.modules.edit, anim = TM.modules.animation;
		if (edit && typeof edit.onMove === 'function' && typeof $wnd.rotateOnAxis === 'function') {
			add(edit, function(orig, entry) {
				return function(context) {
					if (!entry.off && active(context)) {
						// Blockbench's slider path: changes only obj.rotation[axis] of every rotated object
						var difference = context.value - (this.previous_value || 0);
						$wnd.rotateOnAxis(function(n) { return n + difference; }, context.axis_number, true);
						$wnd.Canvas.updatePositions(true);
						$wnd.Transformer.updateSelection();
						try {
							if ($wnd.Blockbench.setCursorTooltip && $wnd.trimFloatNumber)
								$wnd.Blockbench.setCursorTooltip($wnd.trimFloatNumber(context.value - this.initial_value));
						} catch (e) {}
						return;
					}
					return orig.apply(this, arguments);
				};
			});
		}
		if (anim && typeof anim.onMove === 'function') {
			add(anim, function(orig, entry) {
				return function(context) {
					if (!entry.off && active(context)) {
						var rs = $wnd.BarItems && $wnd.BarItems.rotation_space;
						if (rs && rs.value !== 'parent') {
							// Animate module: any space but global/local takes its single-value branch
							// transform_keyframes[0].offset(axis, difference); restored right after this call
							var old = rs.value;
							rs.value = 'parent';
							try { return orig.apply(this, arguments); } finally { rs.value = old; }
						}
					}
					return orig.apply(this, arguments);
				};
			});
		}
		return entries.length ? entries : null;
	}-*/;

	private static native void unhook(Object entries) /*-{
		for (var i = 0; i < entries.length; i++) {
			var e = entries[i];
			e.off = true;
			try { if (e.module.onMove === e.wrapper) e.module.onMove = e.orig; } catch (x) {}
		}
	}-*/;
}
