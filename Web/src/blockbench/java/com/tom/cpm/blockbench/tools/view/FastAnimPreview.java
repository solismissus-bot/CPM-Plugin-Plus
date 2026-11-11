package com.tom.cpm.blockbench.tools.view;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.util.PlusPrefs;

/**
 * CPM+ : fast Animate mode for CPM projects (Blockbench 5).
 * <p>
 * Blockbench's Animator.preview resets the pose of every node, runs an animator for every node (an empty one is created
 * for each group the first time) and recomputes the matrices of the whole scene; three.js then recomputes them all
 * again before drawing (it composes position, rotation and scale of every object). On a model with thousands of
 * groups that is most of the frame, although an animation moves a few hundred of them.
 * <ul>
 * <li>Preview: the same Blockbench steps in the same order, only for the nodes something can move this frame: the
 * nodes with keyframes in the acting animations, the root groups (CPM+ tools turn the vanilla head on top of the pose)
 * and the selected nodes (a part dragged before it has keys). Every other node keeps its rest pose, which Blockbench
 * restored for all nodes the last time this set was rebuilt: after an edit, undo, project / mode change, or when
 * another animation starts or stops.</li>
 * <li>Matrices, Animate mode: the node meshes nothing moves keep their local matrix and only the subtrees of the
 * listed nodes whose position, rotation or scale changed are recomputed before drawing.</li>
 * <li>Matrices, other modes of a CPM project: the scene's update composes a local matrix only when the object's
 * position, rotation or scale changed (three.js composes every object on every frame); world matrices follow as in
 * three.js. Objects with their own updateMatrixWorld (gizmo, cameras) keep using it.</li>
 * <li>No FPS cap in Animate mode: Blockbench draws at most settings.fps_limit frames a second (144 by default); in
 * Animate mode the limit is raised to the maximum for the session only (Blockbench saves the setting's master value,
 * so the user's own limit is never written over) and put back when leaving.</li>
 * </ul>
 * An edit, undo, project or mode change recomputes every world matrix once. The result is the one three.js computes.
 * Animation controllers, other formats and any error use Blockbench's own preview and three.js' own update. Off
 * switch: CPM+ &gt; Animação &gt; Modo rápido.
 */
public final class FastAnimPreview {
	public static final String PREF = "cpmplus.fastAnimPreview";

	private FastAnimPreview() {
	}

	public static boolean isEnabled() {
		return PlusPrefs.getBool(PREF, true);
	}

	public static void install() {
		install0(CPMCodec.FORMAT_ID, isEnabled());
		// whatever can add or remove keyframes / nodes, reparent them or change the acting animations: rebuild the node
		// list and recompute every world matrix once, on the next frame
		for (String ev : new String[] {"finished_edit", "undo", "redo", "load_undo_save", "select_project", "select_mode", "add_animation"}) {
			PluginStart.addEventListener(ev, __ -> bump());
		}
		// another project / mode: the Animate state (frozen matrices, FPS) ends; every world matrix once
		for (String ev : new String[] {"select_mode", "select_project", "unselect_project"}) {
			PluginStart.addEventListener(ev, __ -> leaveAnim());
		}
		// every drawn frame: the scene of a CPM project uses the change-checked update, any other one three.js' own
		PluginStart.addEventListener("render_frame", __ -> ensureHook());
		PluginStart.cleanup.add(FastAnimPreview::uninstall0);
	}

	public static void setEnabled(boolean on) {
		PlusPrefs.set(PREF, on);
		setOn(on);
	}

	private static native void setOn(boolean on)/*-{
		var S = $wnd.Animator && $wnd.Animator.__cpmplusFast;
		if (S) { S.unhook(); S.on = on; S.key = null; S.failed = false; }
	}-*/;

	/** the next fast frame rebuilds its node list (every node back to its rest pose once) and all world matrices */
	public static native void bump()/*-{
		var S = $wnd.Animator && $wnd.Animator.__cpmplusFast;
		if (S) { S.ver++; S.force = true; }
	}-*/;

	/** three.js updates the scene matrices itself again */
	public static native void unhook()/*-{
		var S = $wnd.Animator && $wnd.Animator.__cpmplusFast;
		if (S) S.unhook();
	}-*/;

	/** hooks the scene of an open CPM project, unhooks it otherwise */
	public static native void ensureHook()/*-{
		var S = $wnd.Animator && $wnd.Animator.__cpmplusFast;
		if (S) S.ensureHook();
	}-*/;

	/** ends the Animate state (frozen matrices, raised FPS limit); every world matrix once on the next frame */
	public static native void leaveAnim()/*-{
		var S = $wnd.Animator && $wnd.Animator.__cpmplusFast;
		if (S) { S.leaveAnim(); S.force = true; }
	}-*/;

	private static native void uninstall0()/*-{
		var A = $wnd.Animator, S = A && A.__cpmplusFast;
		if (!S) return;
		S.unhook();
		if (A.preview === S.wrapper) A.preview = S.orig;
		delete A.__cpmplusFast;
	}-*/;

	private static native void install0(String formatId, boolean enabled)/*-{
		var w = $wnd, A = w.Animator;
		if (!A || typeof A.preview !== 'function' || A.__cpmplusFast) return;
		if (!w.Blockbench || !/^5\./.test(String(w.Blockbench.version))) return;// the steps below are Blockbench 5's
		var S = {orig: A.preview, on: enabled, failed: false, ver: 0, key: null, list: [], set: null, last: [],
			hasIK: false, hasBill: false, hasMesh: false, scene: null, prevOBR: null, force: true, frozen: [], cache: new w.Map(),
			done: new w.Set(), modelT: new w.Float64Array(10), animMode: false, ocache: new w.WeakMap(), fpsUser: null,
			stats: {fast: 0, orig: 0, rebuild: 0, nodes: 0, subtrees: 0, composed: 0}};
		var BASE_UMW = w.THREE.Object3D.prototype.updateMatrixWorld, FPS_MAX = 1024;

		// CPM project in Animate mode (a pose sampled in Edit mode, e.g. by an export, uses Blockbench's own preview)
		function usable() {
			if (!S.on || S.failed) return false;
			var F = w.Format, AC = w.AnimationController, M = w.Modes, am = M && M.animate;
			if (!w.Project || !F || F.id !== formatId) return false;
			if (!(am === true || (am && typeof am === 'object' && am.selected === true))) return false;
			return !(AC && AC.selected && AC.selected.selected_state);
		}

		// ---------------------------------------------------------------- matrices
		// Hooked scene (Animate mode): three.js skips its own update (scene.autoUpdate = false); before drawing, the
		// scene children that are not the model update as usual, and in the model only the subtrees of the listed nodes
		// whose position, rotation or scale changed are recomputed. Node meshes outside the list (and the children of
		// element meshes) keep their local matrix (matrixAutoUpdate = false), so a recomputed subtree only multiplies
		// them. A rebuild (edit, undo, another animation...) makes one full three.js update and freezes again.

		function freeze() {
			var off = S.frozen, Grp = w.Group, nodes = Grp.all.concat(w.Outliner.elements), i, j, n, m, ch;
			for (i = 0; i < nodes.length; i++) {
				n = nodes[i];
				m = n.mesh;
				if (!m || S.set.has(n)) continue;
				if (m.matrixAutoUpdate) {
					m.matrixAutoUpdate = false;
					off.push(m);
				}
				if (n instanceof Grp) continue;
				ch = m.children;
				for (j = 0; j < ch.length; j++) {
					if (ch[j].matrixAutoUpdate) {
						ch[j].matrixAutoUpdate = false;
						off.push(ch[j]);
					}
				}
			}
		}

		function unfreeze() {
			var off = S.frozen;
			for (var i = 0; i < off.length; i++) off[i].matrixAutoUpdate = true;
			off.length = 0;
		}

		function sameT(c, m) {
			var p = m.position, q = m.quaternion, s = m.scale;
			return c[0] === p.x && c[1] === p.y && c[2] === p.z && c[3] === q._x && c[4] === q._y && c[5] === q._z && c[6] === q._w
				&& c[7] === s.x && c[8] === s.y && c[9] === s.z;
		}

		function keepT(c, m) {
			var p = m.position, q = m.quaternion, s = m.scale;
			c[0] = p.x; c[1] = p.y; c[2] = p.z; c[3] = q._x; c[4] = q._y; c[5] = q._z; c[6] = q._w; c[7] = s.x; c[8] = s.y; c[9] = s.z;
		}

		// one full update: everything automatic again, three.js' own update, then the still nodes frozen
		function fullUpdate(sc) {
			unfreeze();
			sc.updateMatrixWorld();
			freeze();
			S.cache.clear();
			var model = w.Project && w.Project.model_3d;
			if (model) keepT(S.modelT, model);
			S.force = false;
		}

		// the subtrees of the listed nodes that moved since the last update, parents first
		function updateMoved(list) {
			var done = S.done, i, n, m, c, p, d;
			done.clear();
			for (i = 0; i < list.length; i++) {
				n = list[i];
				m = n.mesh;
				if (!m || !m.parent) continue;
				c = S.cache.get(n);
				if (c !== undefined && sameT(c, m)) continue;
				if (c === undefined) S.cache.set(n, c = new w.Float64Array(10));
				keepT(c, m);
				if (!m.matrixAutoUpdate) m.matrixAutoUpdate = true;
				for (p = n.parent, d = 0; p && p !== 'root' && d < 512; p = p.parent, d++) if (done.has(p)) break;
				if (p && p !== 'root' && d < 512) {// a moved parent recomputed this subtree already
					done.add(n);
					continue;
				}
				m.updateMatrixWorld(true);
				done.add(n);
				S.stats.subtrees++;
			}
		}

		function updateScene(sc) {
			var model = w.Project && w.Project.model_3d;
			if (S.force || !model || model.parent !== sc || !sameT(S.modelT, model)) {
				fullUpdate(sc);
				return;
			}
			var ch = sc.children;
			for (var i = 0; i < ch.length; i++) if (ch[i] !== model) ch[i].updateMatrixWorld();
			updateMoved(S.last);
		}

		// Object3D.updateMatrixWorld of three.js, except that the local matrix is composed only when position,
		// quaternion or scale changed since it was last composed here (cache outside the objects: a WeakMap)
		function checked(o, f) {
			if (o.updateMatrixWorld !== BASE_UMW) {// gizmo, cameras...: their own update
				o.updateMatrixWorld(f);
				return;
			}
			if (o.matrixAutoUpdate) {
				var c = S.ocache.get(o);
				if (c === undefined || !sameT(c, o)) {
					if (c === undefined) S.ocache.set(o, c = new w.Float64Array(10));
					keepT(c, o);
					o.matrix.compose(o.position, o.quaternion, o.scale);
					o.matrixWorldNeedsUpdate = true;
					S.stats.composed++;
				}
			}
			if (o.matrixWorldNeedsUpdate || f) {
				if (o.parent === null) o.matrixWorld.copy(o.matrix);
				else o.matrixWorld.multiplyMatrices(o.parent.matrixWorld, o.matrix);
				o.matrixWorldNeedsUpdate = false;
				f = true;
			}
			var ch = o.children;
			for (var i = 0; i < ch.length; i++) checked(ch[i], f);
		}

		function checkedUpdate(sc) {
			var f = S.force;
			S.force = false;
			checked(sc, f);
		}

		// before three.js draws the scene (it skips its own update while hooked)
		function beforeRender(renderer, sc) {
			try {
				if (S.scene === sc) {
					if (S.animMode) updateScene(sc);
					else checkedUpdate(sc);
				}
			} catch (e) {
				w.console.warn('CPM+: scene matrix update failed, three.js updates it from now on', e);
				S.failed = true;
				S.unhook();
				sc.updateMatrixWorld();
			}
			if (S.prevOBR) return S.prevOBR.apply(this, arguments);
		}

		function hook() {
			var sc = w.Canvas && w.Canvas.scene;
			if (!sc || S.scene === sc) return;
			S.unhook();
			S.scene = sc;
			S.prevOBR = sc.hasOwnProperty('onBeforeRender') ? sc.onBeforeRender : null;
			S.force = true;
			sc.onBeforeRender = beforeRender;
			sc.autoUpdate = false;
		}
		function allowed() {
			var F = w.Format;
			return S.on && !S.failed && !!w.Project && !!F && F.id === formatId;
		}
		S.ensureHook = function () {
			if (allowed()) hook();
			else S.unhook();
		};

		// Animate state: frozen still nodes (fast preview) and no FPS cap
		function enterAnim() {
			if (S.animMode) return;
			S.animMode = true;
			S.force = true;
			// Blockbench's loop reads settings.fps_limit.value; this instance shadows that accessor while in Animate mode.
			// The class setter writes master_value (what Blockbench saves) and the settings dialog uses ui_value, so the
			// user's own limit is never written over nor shown changed.
			var st = w.settings && w.settings.fps_limit;
			if (st && !S.fpsUser && !Object.prototype.hasOwnProperty.call(st, 'value') && st.value < FPS_MAX) {
				w.Object.defineProperty(st, 'value', {configurable: true, get: function () { return FPS_MAX; },
					set: function (v) { this.master_value = v; }});
				S.fpsUser = st;
			}
		}
		S.leaveAnim = function () {
			unfreeze();
			S.cache.clear();
			if (S.fpsUser) {
				delete S.fpsUser.value;// the class accessor again
				S.fpsUser = null;
			}
			if (!S.animMode) return;
			S.animMode = false;
			S.force = true;
		};

		S.unhook = function () {
			S.leaveAnim();
			var sc = S.scene;
			if (!sc) return;
			S.scene = null;
			if (S.prevOBR) sc.onBeforeRender = S.prevOBR;
			else delete sc.onBeforeRender;
			S.prevOBR = null;
			sc.autoUpdate = true;
		};

		// ---------------------------------------------------------------- preview

		function resetPose(list) {
			for (var i = 0; i < list.length; i++) {
				var e = list[i], t = e.mesh, an = e.constructor.animator;
				if (!t || !an) continue;
				if (t.fix_rotation) t.rotation.copy(t.fix_rotation);
				if (t.fix_position) t.position.copy(t.fix_position);
				if (an.prototype.channels && an.prototype.channels.scale) t.scale.x = t.scale.y = t.scale.z = 1;
			}
		}

		function depth(n) {
			var d = 0;
			while (n && n.parent && n.parent !== 'root' && d < 512) { n = n.parent; d++; }
			return d;
		}

		// the nodes with keyframes in the acting animations + the root groups, parents before children
		function rebuild(anims) {
			var Grp = w.Group, groups = Grp.all, want = {}, i, a, id, an;
			for (i = 0; i < anims.length; i++) {
				a = anims[i];
				var byName = null;
				for (id in a.animators) {
					an = a.animators[id];
					if (!an || id === 'effects' || !an.keyframes || !an.keyframes.length) continue;
					want[id] = true;
					// an animator read by bone name: Blockbench attaches it to the group with that name (getBoneAnimator)
					if (an._name && !an.group && an.type === 'bone') (byName || (byName = {}))[String(an._name).toLowerCase()] = true;
				}
				if (byName) {
					for (var g = 0; g < groups.length; g++) {
						var gr = groups[g];
						if (!a.animators[gr.uuid] && byName[String(gr.name).toLowerCase()]) {
							var got = a.getBoneAnimator(gr);
							if (got && got.keyframes && got.keyframes.length) want[gr.uuid] = true;
						}
					}
				}
			}
			var order = groups.concat(w.Outliner.elements), list = [];
			for (i = 0; i < order.length; i++) {
				var n = order[i];
				if (!n.constructor.animator) continue;
				if (want[n.uuid] || (n instanceof Grp && n.parent === 'root')) list.push({n: n, d: depth(n), i: list.length});
			}
			list.sort(function (x, y) { return x.d - y.d || x.i - y.i; });
			S.list = list.map(function (x) { return x.n; });
			S.set = new w.Set(S.list);
			S.hasIK = !!(w.NullObject && w.NullObject.all.some(function (x) { return x.ik_target; }));
			S.hasBill = !!(w.Billboard && w.Billboard.all.length);
			S.hasMesh = !!(w.Mesh && w.Mesh.all.length);
		}

		// selected nodes the list does not have (a part dragged before it has keys)
		function withSelected(list) {
			var extra = null, sel = w.Group.multi_selected || [], els = w.Outliner.selected || [], i, n;
			for (i = 0; i < sel.length; i++) {
				n = sel[i];
				if (n && n.constructor.animator && !S.set.has(n)) (extra || (extra = [])).push(n);
			}
			for (i = 0; i < els.length; i++) {
				n = els[i];
				if (n && n.constructor.animator && !S.set.has(n) && !(extra && extra.indexOf(n) >= 0)) (extra || (extra = [])).push(n);
			}
			return extra ? list.concat(extra) : list;
		}

		// Animator.preview of Blockbench 5.2 (no animation controller), for the nodes above
		function fast(in_loop) {
			var P = w.Project, Anim = w.Animation, TL = w.Timeline, Bb = w.Blockbench, C = w.Canvas, i, o, a;
			hook();
			enterAnim();
			var anims = Anim.all.filter(function (x) { return x.playing; });
			if (anims.length > 1 && Anim.selected && anims.indexOf(Anim.selected) >= 0) {
				anims = anims.slice();
				anims.splice(anims.indexOf(Anim.selected), 1);
				anims.push(Anim.selected);
			}
			var key = (P.uuid || '') + '|' + S.ver + '|' + w.Group.all.length + '|' + w.Outliner.elements.length;
			for (i = 0; i < anims.length; i++) key += '|' + anims[i].uuid;
			if (key !== S.key) {
				unfreeze();// rebuild() changes the list: freeze again after the next full update
				rebuild(anims);
				S.key = key;
				A.showDefaultPose(true);// every node back to its rest pose, once
				S.last = [];
				S.force = true;
				S.stats.rebuild++;
			} else {
				resetPose(S.last);
				Bb.dispatchEvent('display_default_pose', {reduced_updates: true});
			}
			var frame = withSelected(S.list);
			S.stats.nodes = frame.length;
			for (i = S.list.length; i < frame.length; i++) {
				var em = frame[i].mesh;
				if (em && !em.matrixAutoUpdate) em.matrixAutoUpdate = true;// selected: may move from now on
			}

			// stackAnimations
			var lastIdx = anims.length - 1, weights = [];
			for (o = 0; o < anims.length; o++) {
				a = anims[o];
				weights.push(a.blend_weight ? Math.clamp(A.MolangParser.parse(a.blend_weight), 0, Infinity) : 1);
			}
			for (i = 0; i < frame.length; i++) {
				var n = frame[i];
				A.resetLastValues();
				Bb.dispatchEvent('pre_stack_node_animations', {node: n, animations: anims, in_loop: in_loop, controller_blend_values: 0});
				for (o = 0; o < anims.length; o++) {
					a = anims[o];
					if (a.loop == 'once' && TL.time > a.length && a.length) continue;
					var r = a.animators[n.uuid];
					if (o === lastIdx) {
						var l = n.mesh;
						if (l) {
							if (!l.pre_rotation) l.pre_rotation = new w.THREE.Euler();
							l.pre_rotation.copy(l.rotation);
						}
					}
					if (r && r.displayFrame) r.displayFrame(weights[o]);
				}
			}
			if (S.hasIK) {
				w.NullObject.all.forEach(function (n) {
					if (!n.ik_target) return;
					anims.forEach(function (a) {
						if (a.loop == 'once' && TL.time > a.length && a.length) return;
						var b = a.getBoneAnimator(n);
						if (b) b.displayIK();
					});
				});
			}
			if (S.scene !== C.scene) C.scene.updateMatrixWorld();
			else if (S.force) fullUpdate(C.scene);
			else updateMoved(frame);
			A.resetLastValues();
			A.resetParticles(true);
			anims.forEach(function (x) { if (x.animators.effects) x.animators.effects.displayFrame(in_loop); });
			S.last = frame;

			// the rest of Animator.preview, unchanged
			var sel = Anim.selected, grid = w.three_grid;
			if (C.ground_plane.visible && sel && sel.anim_time_update.includes('modified_distance_moved')) {
				var d = A.MolangParser.parse(sel.anim_time_update, {'query.modified_distance_moved': TL.time});
				d = TL.time / d * TL.time * 3;
				d = d % 64 || 0;
				C.ground_plane.position.z = C.ground_plane.position.x + d;
				grid.position.z = grid.position.x + d;
			} else {
				grid.position.z = grid.position.x;
				C.ground_plane.position.z = C.ground_plane.position.x;
			}
			A.updateOnionSkin();
			if (S.hasMesh) A.displayMeshDeformation();
			if (S.hasBill) w.Billboard.all.forEach(function (b) { w.Billboard.preview_controller.updateFacingCamera(b); });
			var txt = w.Interface.Panels.variable_placeholders.inside_vue.text;
			if (/^\s*preview\.texture\s*=/mi.test(txt)) {
				var v = A.MolangParser.variableHandler('preview.texture'), tx = w.Texture.all[v % w.Texture.all.length];
				if (tx && tx != w.Texture.selected) tx.select();
			}
			if (/^\s*preview\.texture_frame\s*=/mi.test(txt)) {
				var f = A.MolangParser.variableHandler('preview.texture_frame');
				w.Texture.all.forEach(function (t) { t.currentFrame = f % t.frameCount || 0; });
				w.TextureAnimator.update(w.Texture.all.filter(function (t) { return t.frameCount > 1; }));
			}
			P.model_3d.scale.set(1, 1, 1);
			if (/^\s*preview\.scale\s*=/mi.test(txt)) {
				var s1 = A.MolangParser.variableHandler('preview.scale');
				P.model_3d.scale.x = P.model_3d.scale.y = P.model_3d.scale.z = s1;
			}
			if (/^\s*preview\.scalex\s*=/mi.test(txt)) P.model_3d.scale.x = A.MolangParser.variableHandler('preview.scalex');
			if (/^\s*preview\.scaley\s*=/mi.test(txt)) P.model_3d.scale.y = A.MolangParser.variableHandler('preview.scaley');
			if (/^\s*preview\.scalez\s*=/mi.test(txt)) P.model_3d.scale.z = A.MolangParser.variableHandler('preview.scalez');
			if (w.Group.first_selected || (w.Outliner.selected[0] && w.Outliner.selected[0].constructor.animator)) w.Transformer.updateSelection();
			Bb.dispatchEvent('display_animation_frame', {in_loop: in_loop});
		}

		S.wrapper = function (in_loop) {
			if (!usable()) {
				S.leaveAnim();
				S.stats.orig++;
				return S.orig.apply(this, arguments);
			}
			try {
				S.stats.fast++;
				return fast(in_loop);
			} catch (e) {
				S.failed = true;
				S.unhook();
				w.console.warn('CPM+: fast animation preview failed, Blockbench draws it from now on', e);
				return S.orig.apply(this, arguments);
			}
		};
		A.__cpmplusFast = S;
		A.preview = S.wrapper;
	}-*/;
}
