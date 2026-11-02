package com.tom.cpm.blockbench.proxy.ext;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;

import elemental2.core.JsArray;

/**
 * CPM+ (W5-VIS): Blockbench natives used by the visibility tools (separate visibility, show/hide in an animation,
 * reactions, on/off button, form with its own motion). Every optional Blockbench API is probed with typeof; nothing
 * here opens an undo entry (callers wrap their edits in PlusUndo).
 */
public final class W5VisNatives {
	private W5VisNatives() {
	}

	/** true while Blockbench is in Animate mode (BB 5: Modes.animate is a boolean flag; older builds: a Mode object) */
	public static native boolean isAnimateMode() /*-{
		try {
			var M = $wnd.Modes;
			if (!M) return false;
			if (M.animate === true) return true;
			if (M.animate && typeof M.animate === 'object' && M.animate.selected) return true;
			return !!(M.selected && M.selected.id === 'animate');
		} catch (e) {
			return false;
		}
	}-*/;

	/** Group.prototype.menu (the outliner context menu of groups), or null */
	public static native Menu groupMenu() /*-{
		return ($wnd.Group && $wnd.Group.prototype && $wnd.Group.prototype.menu) || null;
	}-*/;

	/** Cube.prototype.menu (the outliner context menu of cubes), or null */
	public static native Menu cubeMenu() /*-{
		return ($wnd.Cube && $wnd.Cube.prototype && $wnd.Cube.prototype.menu) || null;
	}-*/;

	/** the selected animation (Animation.selected / Animator.selected), or null */
	public static native Animation selectedAnimation() /*-{
		var a = ($wnd.Animation && $wnd.Animation.selected) || ($wnd.Animator && $wnd.Animator.selected) || null;
		return a || null;
	}-*/;

	/** the playhead time in seconds */
	public static native double timelineTime() /*-{
		return ($wnd.Timeline && typeof $wnd.Timeline.time === 'number') ? $wnd.Timeline.time : 0;
	}-*/;

	// ------------------------------------------------------------------ keyframes

	/** number of keyframes of node's animator in a on channel (0 when there is no animator) */
	public static native int keyCount(Animation a, Object node, String channel) /*-{
		var ba = (a && a.animators && node) ? a.animators[node.uuid] : null;
		var arr = ba ? ba[channel] : null;
		return (arr && arr.length) ? arr.length : 0;
	}-*/;

	/**
	 * true when node's animator in a has a keyframe on a channel other than except (except = null: any channel). Only
	 * the bone channels count (position, rotation, scale, cpm_visibility, cpm_color).
	 */
	public static native boolean hasKeysExcept(Animation a, Object node, String except) /*-{
		var ba = (a && a.animators && node) ? a.animators[node.uuid] : null;
		if (!ba) return false;
		var chans = ['position', 'rotation', 'scale', 'cpm_visibility', 'cpm_color'];
		for (var i = 0; i < chans.length; i++) {
			if (chans[i] === except) continue;
			var arr = ba[chans[i]];
			if (arr && arr.length) return true;
		}
		return false;
	}-*/;

	/** visibility keys of node in a, sorted by time, as [t0, v0, t1, v1, ...] (v: 1 visible, 0 hidden) */
	public static native JsArray<Object> visKeys(Animation a, Object node) /*-{
		var out = [];
		var ba = (a && a.animators && node) ? a.animators[node.uuid] : null;
		var arr = ba ? ba.cpm_visibility : null;
		if (!arr) return out;
		var l = arr.slice().sort(function(x, y) { return x.time - y.time; });
		for (var i = 0; i < l.length; i++) {
			var dp = l[i].data_points && l[i].data_points[0];
			var v = dp ? dp.cpm_visible : true;
			out.push(l[i].time, (v === true || v === 'true') ? 1 : 0);
		}
		return out;
	}-*/;

	/** true when the visibility channel of node's animator in a is muted */
	public static native boolean visMuted(Animation a, Object node) /*-{
		var ba = (a && a.animators && node) ? a.animators[node.uuid] : null;
		return !!(ba && ba.muted && ba.muted.cpm_visibility);
	}-*/;

	/**
	 * Replaces every visibility key of node in a by step keys (times[i], vis[i]); creates the animator when missing.
	 * @return the number of keys written, -1 when the node can't be animated
	 */
	public static native int setVisKeys(Animation a, Object node, double[] times, boolean[] vis) /*-{
		var ba = a.getBoneAnimator(node);
		if (!ba) return -1;
		if (!ba.cpm_visibility) ba.cpm_visibility = [];
		var old = ba.cpm_visibility.slice();
		var sel = $wnd.Timeline && $wnd.Timeline.selected;
		for (var i = 0; i < old.length; i++) {
			if (sel && sel.indexOf) { var k = sel.indexOf(old[i]); if (k >= 0) sel.splice(k, 1); }
		}
		ba.cpm_visibility.splice(0, ba.cpm_visibility.length);
		for (var j = 0; j < times.length; j++) {
			var v = !!vis[j];
			var data = {channel: 'cpm_visibility', time: times[j], interpolation: 'step', data_points: [{cpm_visible: v}]};
			var kf = null;
			if (typeof ba.addKeyframe === 'function') kf = ba.addKeyframe(data);
			if (!kf) {
				kf = new $wnd.Keyframe(data, null, ba);
				ba.cpm_visibility.push(kf);
				kf.animator = ba;
			}
			if (kf.data_points && kf.data_points[0]) kf.data_points[0].cpm_visible = v;
			kf.interpolation = 'step';
			kf.time = times[j];
		}
		return times.length;
	}-*/;

	/** removes every visibility key of node in a (no animator: nothing). @return keys removed */
	public static native int clearVisKeys(Animation a, Object node) /*-{
		var ba = (a && a.animators && node) ? a.animators[node.uuid] : null;
		if (!ba || !ba.cpm_visibility || !ba.cpm_visibility.length) return 0;
		var sel = $wnd.Timeline && $wnd.Timeline.selected;
		var old = ba.cpm_visibility.splice(0, ba.cpm_visibility.length);
		for (var i = 0; i < old.length; i++) {
			if (sel && sel.indexOf) { var k = sel.indexOf(old[i]); if (k >= 0) sel.splice(k, 1); }
		}
		return old.length;
	}-*/;

	/**
	 * Moves every visibility keyframe (time, value, interpolation and uuid kept) of from's animator in a to to's
	 * animator (created when missing), and the channel's mute flag. @return keys moved
	 */
	public static native int moveVisKeys(Animation a, Object from, Object to) /*-{
		var bf = (a && a.animators) ? a.animators[from.uuid] : null;
		var muted = !!(bf && bf.muted && bf.muted.cpm_visibility);
		if (!bf || !bf.cpm_visibility || !bf.cpm_visibility.length) return 0;
		var bt = a.getBoneAnimator(to);
		if (!bt) return 0;
		if (!bt.cpm_visibility) bt.cpm_visibility = [];
		var keys = bf.cpm_visibility.splice(0, bf.cpm_visibility.length);
		for (var i = 0; i < keys.length; i++) {
			keys[i].animator = bt;
			bt.cpm_visibility.push(keys[i]);
		}
		if (muted) {
			if (!bt.muted) bt.muted = {};
			bt.muted.cpm_visibility = true;
			bf.muted.cpm_visibility = false;
		}
		return keys.length;
	}-*/;

	/**
	 * Replaces the keyframes of to's animator in a, channel by channel, with copies of from's (new keyframe uuids, all
	 * data kept: time, values, interpolation, bezier handles), plus mute flags and 'rotation_global'.
	 * @return keys copied
	 */
	public static native int copyAllKeys(Animation a, Object from, Object to) /*-{
		var bf = (a && a.animators) ? a.animators[from.uuid] : null;
		if (!bf) return 0;
		var chans = ['position', 'rotation', 'scale', 'cpm_visibility', 'cpm_color'];
		var n = 0, bt = null;
		for (var ci = 0; ci < chans.length; ci++) {
			var c = chans[ci];
			var arr = bf[c];
			if (!arr || !arr.length) continue;
			if (!bt) bt = a.getBoneAnimator(to);
			if (!bt) return n;
			if (!bt[c]) bt[c] = [];
			// replace: the destination channel ends up exactly like the source (also when Blockbench's duplicate already copied keys)
			bt[c].splice(0, bt[c].length);
			for (var i = 0; i < arr.length; i++) {
				var d = (typeof arr[i].getUndoCopy === 'function') ? arr[i].getUndoCopy(true) : null;
				if (!d) continue;
				delete d.uuid;
				delete d.animator;
				d.channel = c;
				var kf = (typeof bt.addKeyframe === 'function') ? bt.addKeyframe(d) : null;
				if (!kf) {
					kf = new $wnd.Keyframe(d, null, bt);
					bt[c].push(kf);
					kf.animator = bt;
				}
				// exact values: the undo copy turns numbers into strings ('-15' for -14.999999999999998)
				var sp = arr[i].data_points, dp = kf.data_points;
				if (sp && dp) {
					for (var p = 0; p < sp.length && p < dp.length; p++) {
						for (var key in sp[p]) {
							if (!Object.prototype.hasOwnProperty.call(sp[p], key)) continue;
							var v = sp[p][key], t = typeof v;
							if (t === 'number' || t === 'string' || t === 'boolean') dp[p][key] = v;
						}
					}
				}
				n++;
			}
		}
		if (bt) {
			if (bf.muted) {
				if (!bt.muted) bt.muted = {};
				for (var m in bf.muted) bt.muted[m] = bf.muted[m];
			}
			if (bf.rotation_global) bt.rotation_global = true;
		}
		return n;
	}-*/;

	// ------------------------------------------------------------------ exact undo of existing animations

	/**
	 * Blockbench's own undo of an animation rebuilds it from its undo copy, which changes the snapping (4 came back
	 * as 10, so a resampled animation would export at another rate) and turns every key value into a string. This
	 * records what has to come back exactly: {uuid: {snapping, kf: {keyframe uuid: [{key: primitive}]}}}.
	 */
	public static native Object snapshotAnims(Object anims) /*-{
		var out = {};
		try {
			if (!anims) return out;
			for (var i = 0; i < anims.length; i++) {
				var a = anims[i];
				if (!a || !a.uuid) continue;
				var rec = {snapping: a.snapping, kf: {}};
				var ans = a.animators || {};
				for (var u in ans) {
					var ba = ans[u];
					var kfs = (ba && ba.keyframes) || [];
					for (var k = 0; k < kfs.length; k++) {
						var kf = kfs[k];
						if (!kf || !kf.uuid || !kf.data_points) continue;
						var dps = [];
						for (var p = 0; p < kf.data_points.length; p++) {
							var src = kf.data_points[p], o = {};
							for (var key in src) {
								if (!Object.prototype.hasOwnProperty.call(src, key)) continue;
								var v = src[key], t = typeof v;
								if (t === 'number' || t === 'string' || t === 'boolean') o[key] = v;
							}
							dps.push(o);
						}
						// keyframe flags too ('uniform' scale came back false, bezier handles, interpolation)
						var props = {};
						for (var pk in kf) {
							if (!Object.prototype.hasOwnProperty.call(kf, pk) || pk === 'selected' || pk === 'uuid' || pk === 'data_points') continue;
							var pv = kf[pk], pt = typeof pv;
							if (pt === 'number' || pt === 'string' || pt === 'boolean') props[pk] = pv;
							else if (Array.isArray(pv) && pv.every(function(x) { var tx = typeof x; return tx === 'number' || tx === 'string' || tx === 'boolean'; })) props[pk] = pv.slice();
						}
						dps.props = props;
						rec.kf[kf.uuid] = dps;
					}
				}
				out[a.uuid] = rec;
			}
		} catch (e) {}
		return out;
	}-*/;

	/** puts the before / after snapshots on the undo entry just recorded (the newest one) */
	public static native void attachToLastEntry(Object before, Object after) /*-{
		try {
			var U = $wnd.Undo;
			if (!U || !U.history || U.index < 1) return;
			var entry = U.history[U.index - 1];
			if (entry) entry.cpmplus_vis_anims = {before: before, after: after};
		} catch (e) {}
	}-*/;

	/** after Ctrl+Z (undo=true) / redo of one of our entries: snapping and key values back exactly */
	public static native void fixAfterUndo(boolean undo) /*-{
		try {
			var U = $wnd.Undo;
			if (!U || !U.history) return;
			var entry = undo ? U.history[U.index] : U.history[U.index - 1];
			var snap = entry && entry.cpmplus_vis_anims && (undo ? entry.cpmplus_vis_anims.before : entry.cpmplus_vis_anims.after);
			if (!snap) return;
			var all = ($wnd.Animator && $wnd.Animator.animations) || [];
			for (var i = 0; i < all.length; i++) {
				var a = all[i];
				var rec = a && snap[a.uuid];
				if (!rec) continue;
				if (typeof rec.snapping === 'number') a.snapping = rec.snapping;
				var ans = a.animators || {};
				for (var u in ans) {
					var kfs = (ans[u] && ans[u].keyframes) || [];
					for (var k = 0; k < kfs.length; k++) {
						var kf = kfs[k];
						var dps = kf && rec.kf[kf.uuid];
						if (!dps || !kf.data_points) continue;
						for (var p = 0; p < dps.length && p < kf.data_points.length; p++) {
							for (var key in dps[p]) kf.data_points[p][key] = dps[p][key];
						}
						if (dps.props) for (var pk in dps.props) {
							var pv = dps.props[pk];
							kf[pk] = Array.isArray(pv) ? pv.slice() : pv;
						}
					}
				}
			}
		} catch (e) {
			console.warn('CPM+: could not restore the animation values after undo', e);
		}
	}-*/;

	// ------------------------------------------------------------------ outliner

	/** a new empty group (rotation 0) named name with the pivot (ox, oy, oz), placed in parent ('root' or a Group) at index */
	public static native Group newGroupAt(String name, double ox, double oy, double oz, Object parent, int index) /*-{
		var g = new $wnd.Group({name: name, origin: [ox, oy, oz], rotation: [0, 0, 0]}).init();
		g.addTo(parent, index);
		return g;
	}-*/;

	/** index of node inside its parent's children (or the outliner root) */
	public static native int indexInParent(Object node) /*-{
		var p = node.parent;
		var list = (p && p !== 'root' && p.children) ? p.children : $wnd.Outliner.root;
		return list.indexOf(node);
	}-*/;

	/** moves node (group or cube) into target (a Group), at the end, or at index when index &gt;= 0 */
	public static native void moveInto(Object node, Object target, int index) /*-{
		if (index >= 0) node.addTo(target, index); else node.addTo(target);
	}-*/;

	/** Blockbench's Group.duplicate() (copy with new uuids placed right after the original), or null */
	public static native Group duplicateGroup(Group g) /*-{
		if (typeof g.duplicate !== 'function') return null;
		return g.duplicate();
	}-*/;

	/** every node inside g (groups and cubes, depth first, children order), g first */
	public static native JsArray<Object> subtree(Object g) /*-{
		var out = [];
		var walk = function(n) {
			out.push(n);
			if (n && n.children) for (var i = 0; i < n.children.length; i++) walk(n.children[i]);
		};
		walk(g);
		return out;
	}-*/;

	/** true when node is a Cube (an outliner element with a mesh, not a group) */
	public static native boolean isCube(Object node) /*-{
		return !!($wnd.Cube && node instanceof $wnd.Cube);
	}-*/;

	/** the selected cubes (Outliner.selected filtered to cubes) */
	public static native JsArray<Object> selectedCubes() /*-{
		var out = [];
		var s = ($wnd.Outliner && $wnd.Outliner.selected) || [];
		for (var i = 0; i < s.length; i++) if ($wnd.Cube && s[i] instanceof $wnd.Cube) out.push(s[i]);
		return out;
	}-*/;

	/** the selected groups (Project.selected_groups / Group.multi_selected / Group.selected) */
	public static native JsArray<Object> selectedGroups() /*-{
		var out = [];
		var P = $wnd.Project;
		var s = (P && P.selected_groups) || ($wnd.Group && $wnd.Group.multi_selected) || [];
		for (var i = 0; i < s.length; i++) if (s[i] && out.indexOf(s[i]) < 0) out.push(s[i]);
		if (!out.length && $wnd.Group && $wnd.Group.first_selected) out.push($wnd.Group.first_selected);
		return out;
	}-*/;

	// ------------------------------------------------------------------ session-only viewport preview ('Testar')

	/** shows / hides the meshes of every cube inside the groups (viewport only: no project data changes) */
	public static native void previewMeshes(Object[] groups, boolean visible) /*-{
		for (var i = 0; i < groups.length; i++) {
			var g = groups[i];
			if (!g) continue;
			var walk = function(n) {
				if (n && n.mesh && $wnd.Cube && n instanceof $wnd.Cube) n.mesh.visible = visible;
				if (n && n.children) for (var j = 0; j < n.children.length; j++) walk(n.children[j]);
			};
			walk(g);
		}
	}-*/;

	/** puts every mesh back to the element's own visibility (Canvas.updateVisibility) */
	public static native void restoreMeshes() /*-{
		try {
			if ($wnd.Canvas && typeof $wnd.Canvas.updateVisibility === 'function') $wnd.Canvas.updateVisibility();
		} catch (e) {}
	}-*/;

	/** the viewport visibility (mesh.visible) of every cube inside g, as [name, 1|0, ...] (test hooks) */
	public static native JsArray<Object> meshStates(Object g) /*-{
		var out = [];
		var walk = function(n) {
			if (n && n.mesh && $wnd.Cube && n instanceof $wnd.Cube) out.push(n.name, n.mesh.visible ? 1 : 0);
			if (n && n.children) for (var j = 0; j < n.children.length; j++) walk(n.children[j]);
		};
		walk(g);
		return out;
	}-*/;

	// ------------------------------------------------------------------ animations

	/** a new animation (added to the project without an undo entry of its own) */
	public static native Animation newAnimation(String name, String loop, double length, int snapping) /*-{
		var a = new $wnd.Animation({name: name, loop: loop, length: length, snapping: snapping});
		a.add(false);
		return a;
	}-*/;

	/** removes an animation from the project (no undo entry of its own) */
	public static native void removeAnimation(Animation a) /*-{
		if (typeof a.remove === 'function') a.remove(false, false);
	}-*/;

	/** shows a menu node greyed out (or normal) with a tooltip; used for 'select the parts first' */
	public static native void setActionHint(Object action, boolean enabled, String tooltip) /*-{
		try {
			var nodes = [];
			if (action.menu_node) nodes.push(action.menu_node);
			if (action.nodes) for (var i = 0; i < action.nodes.length; i++) nodes.push(action.nodes[i]);
			for (var j = 0; j < nodes.length; j++) {
				var n = nodes[j];
				if (!n || !n.style) continue;
				n.style.opacity = enabled ? '' : '0.5';
				if (tooltip) n.setAttribute('title', tooltip); else n.removeAttribute('title');
			}
		} catch (e) {}
	}-*/;
}
