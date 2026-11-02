package com.tom.cpm.blockbench.proxy.ext;

import com.tom.cpm.blockbench.proxy.Menu;

/**
 * CPM+ (W5-STICK): the few Blockbench natives 'Grudar na parte do corpo' needs. Every optional API is
 * feature-detected (typeof); nothing here throws.
 */
public final class W5StickNatives {
	private W5StickNatives() {
	}

	/** component i of the vector property prop (origin / rotation / from / to) of a node, 0 when missing */
	public static native double comp(Object node, String prop, int i) /*-{
		var v = node ? node[prop] : null;
		if (!v) return 0;
		var x = +v[i];
		return isFinite(x) ? x : 0;
	}-*/;

	/** replaces the vector property with a NEW array (never shares an array with another node) */
	public static native void setVec(Object node, String prop, double x, double y, double z) /*-{
		node[prop] = [x, y, z];
	}-*/;

	/** inflate of a cube (0 when missing) */
	public static native double inflate(Object cube) /*-{
		var v = cube ? +cube.inflate : 0;
		return isFinite(v) ? v : 0;
	}-*/;

	/** true in Blockbench's Edit mode (also when Modes cannot be read: never blocks a tool by mistake) */
	public static native boolean isEditMode() /*-{
		try {
			var M = $wnd.Modes;
			if (!M) return true;
			if (M.selected && typeof M.selected === 'object' && M.selected.id) return M.selected.id === 'edit';
			if (typeof M.id === 'string' && M.id) return M.id === 'edit';
			if (M.animate === true) return false;
			return true;
		} catch (e) {
			return true;
		}
	}-*/;

	/** Group.prototype.menu / Cube.prototype.menu (outliner context menus), or null */
	public static native Menu groupMenu() /*-{
		try { return ($wnd.Group && $wnd.Group.prototype && $wnd.Group.prototype.menu) || null; } catch (e) { return null; }
	}-*/;

	public static native Menu cubeMenu() /*-{
		try { return ($wnd.Cube && $wnd.Cube.prototype && $wnd.Cube.prototype.menu) || null; } catch (e) { return null; }
	}-*/;

	/** true when any animation has position or rotation keyframes on the node with this uuid */
	public static native boolean hasTransformKeys(String uuid) /*-{
		try {
			var list = ($wnd.Animation && $wnd.Animation.all) || ($wnd.Animator && $wnd.Animator.animations) || [];
			for (var i = 0; i < list.length; i++) {
				var a = list[i];
				var an = a && a.animators ? a.animators[uuid] : null;
				if (!an) continue;
				if ((an.rotation && an.rotation.length) || (an.position && an.position.length)) return true;
			}
		} catch (e) {}
		return false;
	}-*/;

	/** the selected outliner nodes (groups and cubes) in outliner order, without duplicates */
	public static native Object[] selectedNodes() /*-{
		var out = [];
		try {
			var add = function(n) { if (n && out.indexOf(n) < 0) out.push(n); };
			var gs = ($wnd.Group && $wnd.Group.multi_selected) || [];
			for (var i = 0; i < gs.length; i++) add(gs[i]);
			if (!gs.length && $wnd.Group && $wnd.Group.first_selected) add($wnd.Group.first_selected);
			var sel = ($wnd.Outliner && $wnd.Outliner.selected) || [];
			for (var j = 0; j < sel.length; j++) if ($wnd.Cube && sel[j] instanceof $wnd.Cube) add(sel[j]);
		} catch (e) {}
		return out;
	}-*/;

	/** selects exactly these nodes (keeps the moved pieces selected after the change) */
	public static native void select(Object[] nodes) /*-{
		try {
			if (typeof $wnd.unselectAllElements === 'function') $wnd.unselectAllElements();
			for (var i = 0; i < nodes.length; i++) {
				var n = nodes[i];
				if (n && typeof n.select === 'function') n.select(i > 0 ? {ctrlOrCmd: true, shiftKey: false} : undefined, true);
			}
			if (typeof $wnd.updateSelection === 'function') $wnd.updateSelection();
		} catch (e) {}
	}-*/;

	/** redraws every group / cube after their positions changed */
	public static native void refreshCanvas() /*-{
		try {
			var C = $wnd.Canvas;
			if (C && typeof C.updateAll === 'function') C.updateAll();
			else if (C) {
				if (typeof C.updateAllPositions === 'function') C.updateAllPositions();
				if (typeof C.updateAllBones === 'function') C.updateAllBones();
			}
		} catch (e) {}
	}-*/;

	/** a new group (initialised, at the end of the outliner root), name made unique */
	public static native Object newGroup(String name, double ox, double oy, double oz) /*-{
		var g = new $wnd.Group({name: name, origin: [ox, oy, oz], rotation: [0, 0, 0]}).init();
		try { g.createUniqueName(); } catch (e) {}
		return g;
	}-*/;
}
