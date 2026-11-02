package com.tom.cpm.blockbench.proxy.ext;

import com.tom.cpm.blockbench.proxy.Menu;

import elemental2.core.JsArray;

/**
 * CPM+ (W4-RIG): Blockbench natives used by the rig tools (part weight, vanilla parts, joint check, build chain).
 * Every optional Blockbench API is feature-detected (typeof) and never throws.
 */
public final class W4RigNatives {

	private W4RigNatives() {
	}

	/** true when any animation has a keyframe (any channel, muted channels too) on the animator of this node uuid */
	public static native boolean hasAnyKeys(String uuid) /*-{
		try {
			var list = ($wnd.Animator && $wnd.Animator.animations) || ($wnd.Animation && $wnd.Animation.all) || [];
			for (var i = 0; i < list.length; i++) {
				var a = list[i];
				var an = a && a.animators ? a.animators[uuid] : null;
				if (!an) continue;
				if (an.keyframes && an.keyframes.length) return true;
				if (an.channels) {
					for (var ch in an.channels) {
						var k = an[ch];
						if (k && k.length) return true;
					}
				}
			}
		} catch (e) {
			return true;// unsure: treat as animated (never collapse / move something animated)
		}
		return false;
	}-*/;

	/** names of the animations that have keyframes of one of these channels on this node uuid */
	public static native JsArray<String> animsWithKeys(String uuid, String[] channels) /*-{
		var out = [];
		try {
			var list = ($wnd.Animator && $wnd.Animator.animations) || ($wnd.Animation && $wnd.Animation.all) || [];
			for (var i = 0; i < list.length; i++) {
				var a = list[i];
				var an = a && a.animators ? a.animators[uuid] : null;
				if (!an) continue;
				var hit = false;
				for (var c = 0; c < channels.length && !hit; c++) {
					var k = an[channels[c]];
					if (k && k.length) hit = true;
				}
				if (!hit && an.keyframes) {
					for (var j = 0; j < an.keyframes.length && !hit; j++) {
						if (channels.indexOf(an.keyframes[j].channel) >= 0) hit = true;
					}
				}
				if (hit) out.push(String(a.name));
			}
		} catch (e) {
		}
		return out;
	}-*/;

	/** true when any animation has visibility (cpm_visibility) keyframes on this node uuid (unsure: true) */
	public static native boolean hasVisibilityKeys(String uuid) /*-{
		try {
			var list = ($wnd.Animator && $wnd.Animator.animations) || ($wnd.Animation && $wnd.Animation.all) || [];
			for (var i = 0; i < list.length; i++) {
				var a = list[i];
				var an = a && a.animators ? a.animators[uuid] : null;
				if (an && an.cpm_visibility && an.cpm_visibility.length) return true;
			}
		} catch (e) {
			return true;
		}
		return false;
	}-*/;

	/** Group.prototype.menu (the outliner context menu of groups), or null */
	public static native Menu groupMenu() /*-{
		try {
			return ($wnd.Group && $wnd.Group.prototype && $wnd.Group.prototype.menu) || null;
		} catch (e) {
			return null;
		}
	}-*/;

	/** Cube.prototype.menu (the outliner context menu of cubes), or null */
	public static native Menu cubeMenu() /*-{
		try {
			return ($wnd.Cube && $wnd.Cube.prototype && $wnd.Cube.prototype.menu) || null;
		} catch (e) {
			return null;
		}
	}-*/;

	/** the selected outliner elements, in selection order (copy) */
	public static native JsArray<Object> selectedElements() /*-{
		var out = [];
		try {
			var s = ($wnd.Outliner && $wnd.Outliner.selected) || $wnd.selected || [];
			for (var i = 0; i < s.length; i++) out.push(s[i]);
		} catch (e) {
		}
		return out;
	}-*/;

	/** the selected groups, in selection order (copy) */
	public static native JsArray<Object> selectedGroups() /*-{
		var out = [];
		try {
			var s = ($wnd.Group && $wnd.Group.multi_selected) || ($wnd.Project && $wnd.Project.selected_groups) || [];
			for (var i = 0; i < s.length; i++) out.push(s[i]);
			if (!out.length && $wnd.Group && $wnd.Group.first_selected) out.push($wnd.Group.first_selected);
			if (!out.length && $wnd.Group && $wnd.Group.selected) out.push($wnd.Group.selected);
		} catch (e) {
		}
		return out;
	}-*/;

	/** selects these nodes (groups / cubes) in the outliner, opens their parents and scrolls to the first */
	public static native void selectNodes(JsArray<Object> nodes) /*-{
		try {
			if (typeof $wnd.unselectAllElements === 'function') $wnd.unselectAllElements();
			else if ($wnd.Outliner && $wnd.Outliner.selected) {
				var sel = $wnd.Outliner.selected.slice();
				for (var k = 0; k < sel.length; k++) if (sel[k] && sel[k].unselect) sel[k].unselect();
			}
			for (var i = 0; i < nodes.length; i++) {
				var n = nodes[i];
				if (!n || typeof n.select !== 'function') continue;
				if (i === 0) n.select();
				else n.select({shiftKey: false, ctrlOrCmd: true, ctrlKey: true});
				if (typeof n.showInOutliner === 'function') {
					try { if (i === 0) n.showInOutliner(); } catch (e2) {}
				} else if (n.parent && typeof n.parent.openUp === 'function') {
					try { n.parent.openUp(); } catch (e3) {}
				}
			}
			if (typeof $wnd.updateSelection === 'function') $wnd.updateSelection();
		} catch (e) {
			console.warn('CPM+: could not select', e);
		}
	}-*/;

	/** a temporary sphere helper in the viewport (model space = Blockbench coordinates); null when THREE is missing */
	public static native Object addSphere(double x, double y, double z, double radius, int color) /*-{
		try {
			var T = $wnd.THREE;
			if (!T) return null;
			var parent = ($wnd.Project && $wnd.Project.model_3d) || ($wnd.Canvas && $wnd.Canvas.scene) || $wnd.scene;
			if (!parent) return null;
			var m = new T.Mesh(new T.SphereGeometry(radius, 16, 12), new T.MeshBasicMaterial({color: color, depthTest: false, transparent: true, opacity: 0.9}));
			m.renderOrder = 999;
			m.position.set(x, y, z);
			m.name = 'cpmplus_w4rig_marker';
			m.no_export = true;
			parent.add(m);
			return m;
		} catch (e) {
			console.warn('CPM+: could not add a marker', e);
			return null;
		}
	}-*/;

	/** a temporary dashed line helper between two points; null when THREE is missing */
	public static native Object addLine(double x1, double y1, double z1, double x2, double y2, double z2, int color) /*-{
		try {
			var T = $wnd.THREE;
			if (!T) return null;
			var parent = ($wnd.Project && $wnd.Project.model_3d) || ($wnd.Canvas && $wnd.Canvas.scene) || $wnd.scene;
			if (!parent) return null;
			var g = new T.BufferGeometry().setFromPoints([new T.Vector3(x1, y1, z1), new T.Vector3(x2, y2, z2)]);
			var mat = T.LineDashedMaterial ? new T.LineDashedMaterial({color: color, dashSize: 0.5, gapSize: 0.35, depthTest: false}) : new T.LineBasicMaterial({color: color, depthTest: false});
			var l = new T.Line(g, mat);
			if (typeof l.computeLineDistances === 'function') l.computeLineDistances();
			l.renderOrder = 999;
			l.name = 'cpmplus_w4rig_marker';
			parent.add(l);
			return l;
		} catch (e) {
			console.warn('CPM+: could not add a marker line', e);
			return null;
		}
	}-*/;

	/** removes a helper made by addSphere / addLine and frees its GPU data */
	public static native void removeObject(Object o) /*-{
		try {
			if (!o) return;
			if (o.parent) o.parent.remove(o);
			if (o.geometry && o.geometry.dispose) o.geometry.dispose();
			if (o.material && o.material.dispose) o.material.dispose();
		} catch (e) {
		}
	}-*/;

	/** number of marker helpers currently in the scene (QA) */
	public static native int markerCount() /*-{
		var n = 0;
		try {
			var root = ($wnd.Canvas && $wnd.Canvas.scene) || $wnd.scene;
			if (root && root.traverse) root.traverse(function(o) { if (o && o.name === 'cpmplus_w4rig_marker') n++; });
		} catch (e) {
		}
		return n;
	}-*/;
}
