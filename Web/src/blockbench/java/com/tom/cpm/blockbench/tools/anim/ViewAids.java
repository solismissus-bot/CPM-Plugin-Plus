package com.tom.cpm.blockbench.tools.anim;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.util.PlusPrefs;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ view aids for animating (nothing is written into the project or the export):
 * <ul>
 * <li>Prévia em degrau (blocking): the viewport holds every key until the next one (Blockbench's interpolate_keyframes
 * event answers t = 0). The export never uses Blockbench's interpolation, and CPM+'s own samplers (physics, tip
 * trails) switch it off while they sample.</li>
 * <li>Silhueta: every block drawn flat dark, to read the pose by its outline; Blockbench's own materials come back when
 * it is turned off (and after edits it is applied again).</li>
 * <li>Clicar de novo alterna: a second click on the same spot of the viewport selects the next part under the mouse
 * (the one behind), and so on (double clicks are left alone).</li>
 * </ul>
 */
public final class ViewAids {
	private ViewAids() {
	}

	static final String PREF_STEP = "cpmplus.viewaids.stepped", PREF_SIL = "cpmplus.viewaids.silhouette", PREF_CYCLE = "cpmplus.viewaids.clickCycle";

	public static void install() {
		installStepped();
		setStepped(PlusPrefs.getBool(PREF_STEP, false));
		setCycle(PlusPrefs.getBool(PREF_CYCLE, true));
		if (PlusPrefs.getBool(PREF_SIL, false))setSilhouette(true);
		PluginStart.addEventListener("finished_edit", e -> reapply());
		PluginStart.addEventListener("select_mode", e -> reapply());
		PluginStart.addEventListener("select_project", e -> reapply());
		PluginStart.addEventListener("update_selection", e -> reapply());
	}

	public static void uninstall() {
		uninstall0();
	}

	// ---------------------------------------------------------------- stepped preview

	public static boolean isStepped() {
		return stepped0();
	}

	public static void setStepped(boolean on) {
		PlusPrefs.set(PREF_STEP, on);
		setStepped0(on);
		AnimToolSupport.refreshPreview();
	}

	private static native void installStepped() /*-{
		var B = $wnd.Blockbench;
		if (!B || $wnd.__cpmplusSteppedFn) return;
		var fn = function(d) {
			if (!$wnd.__cpmplusStepped || $wnd.__cpmplusNoStepped) return;
			return {t: 0};
		};
		B.on('interpolate_keyframes', fn);
		$wnd.__cpmplusSteppedFn = fn;
	}-*/;

	private static native boolean stepped0() /*-{
		return !!$wnd.__cpmplusStepped;
	}-*/;

	private static native void setStepped0(boolean on) /*-{
		$wnd.__cpmplusStepped = on;
	}-*/;

	// ---------------------------------------------------------------- silhouette

	private static boolean silhouette;

	public static boolean isSilhouette() {
		return silhouette;
	}

	public static void setSilhouette(boolean on) {
		silhouette = on;
		PlusPrefs.set(PREF_SIL, on);
		if (on)applySil();
		else restoreSil();
	}

	static void reapply() {
		if (silhouette)applySil();
	}

	private static native void applySil() /*-{
		var T = $wnd.THREE, O = $wnd.Outliner;
		if (!T || !O) return;
		var mat = $wnd.__cpmplusSilMat;
		if (!mat) { mat = new T.MeshBasicMaterial({color: 0x15151a}); $wnd.__cpmplusSilMat = mat; }
		O.elements.forEach(function(e) {
			var m = e.mesh;
			if (!m || !m.isMesh || m.material === mat) return;
			m.__cpmplusMat = m.material;
			m.material = mat;
		});
	}-*/;

	private static native void restoreSil() /*-{
		var O = $wnd.Outliner, mat = $wnd.__cpmplusSilMat;
		if (!O) return;
		O.elements.forEach(function(e) {
			var m = e.mesh;
			if (!m) return;
			if (m.__cpmplusMat && m.material === mat) m.material = m.__cpmplusMat;
			delete m.__cpmplusMat;
		});
		try { if ($wnd.Canvas && $wnd.Canvas.updateAllFaces) $wnd.Canvas.updateAllFaces(); } catch (ex) {}
	}-*/;

	// ---------------------------------------------------------------- click again = the part behind

	public static boolean isCycle() {
		return cycle0();
	}

	public static void setCycle(boolean on) {
		PlusPrefs.set(PREF_CYCLE, on);
		setCycle0(on);
	}

	private static native boolean cycle0() /*-{
		return !!$wnd.__cpmplusClickCycle;
	}-*/;

	private static native void setCycle0(boolean on) /*-{
		if (!on) {
			if ($wnd.__cpmplusClickCycle) $wnd.document.removeEventListener('click', $wnd.__cpmplusClickCycle, true);
			$wnd.__cpmplusClickCycle = null;
			return;
		}
		if ($wnd.__cpmplusClickCycle) return;
		var state = {x: -1, y: -1, at: 0, hits: [], idx: 0};
		var handler = function(e) {
			try {
				var P = $wnd.Preview, T = $wnd.THREE, O = $wnd.Outliner;
				if (!P || !P.selected || !T || !O || e.button !== 0 || e.detail > 1) return;
				var pv = P.selected, cv = pv.canvas;
				if (!cv || e.target !== cv) return;
				var rect = cv.getBoundingClientRect(), x = e.clientX - rect.left, y = e.clientY - rect.top;
				var ray = new T.Raycaster();
				ray.setFromCamera(new T.Vector2(x / rect.width * 2 - 1, -(y / rect.height) * 2 + 1), pv.camera);
				var meshes = [], byMesh = {};
				O.elements.forEach(function(el) { if (el.mesh && el.mesh.isMesh && el.visibility !== false) { meshes.push(el.mesh); byMesh[el.mesh.uuid] = el; } });
				var hits = ray.intersectObjects(meshes, false), els = [];
				hits.forEach(function(h) { var el = byMesh[h.object.uuid]; if (el && els.indexOf(el) < 0) els.push(el); });
				var same = Math.hypot(x - state.x, y - state.y) < 5 && (Date.now() - state.at) < 8000 && els.length > 1
					&& els.length === state.hits.length && els.every(function(el, i) { return el === state.hits[i]; });
				state.x = x; state.y = y; state.at = Date.now(); state.hits = els;
				if (!same) { state.idx = 0; return; }
				state.idx = (state.idx + 1) % els.length;
				var pick = els[state.idx];
				var animate = $wnd.Modes && $wnd.Modes.animate;
				var target = animate && pick.parent && pick.parent !== 'root' && pick.parent.select ? pick.parent : pick;
				if (target.select) target.select();
				if ($wnd.updateSelection) $wnd.updateSelection();
				$wnd.__cpmplusClickCycleLast = target.name;
			} catch (ex) {}
		};
		$wnd.document.addEventListener('click', handler, true);
		$wnd.__cpmplusClickCycle = handler;
	}-*/;

	private static native void uninstall0() /*-{
		var B = $wnd.Blockbench;
		if (B && $wnd.__cpmplusSteppedFn && B.removeListener) B.removeListener('interpolate_keyframes', $wnd.__cpmplusSteppedFn);
		$wnd.__cpmplusSteppedFn = null;
		$wnd.__cpmplusStepped = false;
		if ($wnd.__cpmplusClickCycle) $wnd.document.removeEventListener('click', $wnd.__cpmplusClickCycle, true);
		$wnd.__cpmplusClickCycle = null;
	}-*/;

	/** viewAids({stepped, silhouette, cycle}) -&gt; state */
	public static Object hook(JsPropertyMap<Object> opts) {
		if (opts.get("stepped") != null)setStepped(AnimToolSupport.bool(opts, "stepped", false));
		if (opts.get("silhouette") != null)setSilhouette(AnimToolSupport.bool(opts, "silhouette", false));
		if (opts.get("cycle") != null)setCycle(AnimToolSupport.bool(opts, "cycle", true));
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("stepped", isStepped());
		r.set("silhouette", silhouette);
		r.set("cycle", isCycle());
		return r;
	}
}
