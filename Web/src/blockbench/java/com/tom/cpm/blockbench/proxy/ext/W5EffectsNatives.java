package com.tom.cpm.blockbench.proxy.ext;

import elemental2.core.JsArray;
import jsinterop.annotations.JsFunction;

/**
 * CPM+ (W5-EFFECTS): Blockbench 5 natives used by the paint view modes (G14) and 'Virar cor sólida' (G55). Every
 * optional Blockbench API is feature-detected (typeof): a missing one makes the call a no-op, never an exception in
 * the user's face.
 */
public final class W5EffectsNatives {
	private W5EffectsNatives() {
	}

	/** a click inside the paint view bar: name of the action + its argument (string / number / null) */
	@JsFunction
	public interface Act {
		void act(String name, Object arg);
	}

	/** an event callback of the paint view (no argument) */
	@JsFunction
	public interface Fn {
		void run(Object arg);
	}

	/** next macrotask (debounce helper); returns the timer id */
	public static native double later(Fn fn, int ms) /*-{
		return $wnd.setTimeout(function() { try { fn(null); } catch (e) { console.error('CPM+', e); } }, ms);
	}-*/;

	public static native void cancelLater(double id) /*-{
		$wnd.clearTimeout(id);
	}-*/;

	// ------------------------------------------------------------------ selection

	/** the selected outliner elements (Outliner.selected), never null */
	public static native JsArray<Object> selectedElements() /*-{
		var s = $wnd.Outliner && $wnd.Outliner.selected;
		return s ? s.slice() : [];
	}-*/;

	/** true for a Blockbench Cube (GWT's instanceof on the native proxy does not check the JS class) */
	public static native boolean isCube(Object o) /*-{
		return !!o && typeof $wnd.Cube === 'function' && o instanceof $wnd.Cube;
	}-*/;

	/** every outliner element (cubes, meshes ...) */
	public static native JsArray<Object> allElements() /*-{
		var s = $wnd.Outliner && $wnd.Outliner.elements;
		return s ? s.slice() : [];
	}-*/;

	/** selects exactly these elements and groups (nothing else), then updates Blockbench's selection */
	public static native void selectOnly(JsArray<Object> elements, JsArray<Object> groups) /*-{
		try { if (typeof $wnd.unselectAllElements === 'function') $wnd.unselectAllElements(); } catch (e) {}
		var G = $wnd.Group;
		try {
			if (G && G.all) for (var i = 0; i < G.all.length; i++) G.all[i].selected = false;
			if (G && G.multi_selected) G.multi_selected.splice(0, G.multi_selected.length);
		} catch (e) {}
		var sel = $wnd.Outliner && $wnd.Outliner.selected;
		for (var j = 0; j < elements.length; j++) {
			var el = elements[j];
			try {
				if (typeof el.markAsSelected === 'function') el.markAsSelected();
				else { el.selected = true; if (sel && sel.indexOf(el) < 0) sel.push(el); }
			} catch (e) {}
		}
		for (var k = 0; k < groups.length; k++) {
			var g = groups[k];
			try {
				g.selected = true;
				if (G && G.multi_selected && G.multi_selected.indexOf(g) < 0) G.multi_selected.push(g);
			} catch (e) {}
		}
		try { if (typeof $wnd.updateSelection === 'function') $wnd.updateSelection(); } catch (e) {}
	}-*/;

	// ------------------------------------------------------------------ preview refresh

	/** Canvas.updateView for these elements (geometry + faces + uv), falls back to the cube preview controller */
	public static native void updateElements(JsArray<Object> elements) /*-{
		if (!elements || !elements.length) return;
		try {
			if ($wnd.Canvas && typeof $wnd.Canvas.updateView === 'function') {
				$wnd.Canvas.updateView({elements: elements, element_aspects: {geometry: true, faces: true, uv: true}});
				return;
			}
		} catch (e) { console.warn('CPM+: Canvas.updateView failed', e); }
		for (var i = 0; i < elements.length; i++) {
			try { elements[i].preview_controller.updateGeometry(elements[i]); } catch (e) {}
		}
	}-*/;

	// ------------------------------------------------------------------ paint view (G14)

	/** true while Blockbench is in Paint mode */
	public static native boolean isPaintMode() /*-{
		var M = $wnd.Modes;
		if (!M) return false;
		return M.paint === true || !!(M.selected && M.selected.id === 'paint');
	}-*/;

	/** switches Blockbench to Paint mode (no-op when unavailable) */
	public static native void selectPaintMode() /*-{
		try { var M = $wnd.Modes; if (M && M.options && M.options.paint && typeof M.options.paint.select === 'function') M.options.paint.select(); } catch (e) {}
	}-*/;

	/** id of a mode in a select_mode / unselect_mode event, or null */
	public static native String modeIdOf(Object evt) /*-{
		try { return evt && evt.mode && evt.mode.id ? String(evt.mode.id) : null; } catch (e) { return null; }
	}-*/;

	/** the element of a preview controller event, or null */
	public static native Object elementOf(Object evt) /*-{
		try { return evt && evt.element ? evt.element : null; } catch (e) { return null; }
	}-*/;

	public static native String uuidOf(Object node) /*-{
		return node && node.uuid ? String(node.uuid) : null;
	}-*/;

	/** element.mesh.visible = v (display only; element.visibility is never written) */
	public static native void setMeshVisible(Object el, boolean v) /*-{
		try { if (el && el.mesh) el.mesh.visible = v; } catch (e) {}
	}-*/;

	/** element.visibility (the saved eye-icon state) */
	public static native boolean visibilityOf(Object el) /*-{
		return !!(el && el.visibility !== false);
	}-*/;

	/** adds a listener to a preview controller (Cube / Mesh ...) by global class name; returns false when missing */
	public static native boolean onPreviewController(String className, String event, Fn fn) /*-{
		try {
			var C = $wnd[className];
			var pc = C && C.preview_controller;
			if (!pc || typeof pc.on !== 'function') return false;
			pc.on(event, fn);
			return true;
		} catch (e) { return false; }
	}-*/;

	public static native void offPreviewController(String className, String event, Fn fn) /*-{
		try {
			var C = $wnd[className];
			var pc = C && C.preview_controller;
			if (pc && typeof pc.removeListener === 'function') pc.removeListener(event, fn);
		} catch (e) {}
	}-*/;

	/**
	 * Creates (once) the paint-view bar inside the 3D viewport (#preview, else body). Returns the element. Its content
	 * is set with setBarHtml (trusted HTML built from escaped strings) and clicks on [data-pv] call act(value).
	 */
	public static native Object ensureBar(String id, Act act) /*-{
		var doc = $wnd.document;
		var el = doc.getElementById(id);
		if (el) return el;
		el = doc.createElement('div');
		el.id = id;
		el.setAttribute('style', 'position:absolute;top:6px;left:50%;transform:translateX(-50%);z-index:5;'
			+ 'background:var(--color-back, #21252b);color:var(--color-text, #ddd);border:1px solid var(--color-border, #444);'
			+ 'border-radius:4px;padding:4px 8px;font-size:12px;max-width:92%;box-shadow:0 2px 6px rgba(0,0,0,0.35);display:none;');
		el.addEventListener('click', function(ev) {
			var t = ev.target;
			while (t && t !== el && !(t.getAttribute && t.getAttribute('data-pv'))) t = t.parentNode;
			if (t && t !== el && t.getAttribute) {
				ev.preventDefault();
				ev.stopPropagation();
				try { act('mode', t.getAttribute('data-pv')); } catch (e) { console.error('CPM+ paint view', e); }
			}
		});
		var host = doc.getElementById('preview') || doc.body;
		if (host === doc.body) el.style.position = 'fixed';
		host.appendChild(el);
		return el;
	}-*/;

	public static native void setBarHtml(Object el, String html, boolean shown) /*-{
		if (!el) return;
		if (el.__cpmHtml !== html) { el.innerHTML = html; el.__cpmHtml = html; }
		el.style.display = shown ? 'block' : 'none';
	}-*/;

	public static native void removeById(String id) /*-{
		var el = $wnd.document.getElementById(id);
		if (el && el.parentNode) el.parentNode.removeChild(el);
	}-*/;

	/**
	 * Installs a capture-phase mousedown listener on the document that calls fn(evt) for clicks on a canvas inside
	 * the 3D viewport. Returns the listener (pass it to removeViewportDown).
	 */
	public static native Object addViewportDown(Fn fn) /*-{
		var l = function(ev) {
			try {
				var t = ev.target;
				if (!t || t.tagName !== 'CANVAS') return;
				var host = $wnd.document.getElementById('preview');
				if (host && !host.contains(t)) return;
				fn(ev);
			} catch (e) {}
		};
		$wnd.document.addEventListener('mousedown', l, true);
		return l;
	}-*/;

	public static native void removeViewportDown(Object l) /*-{
		if (l) $wnd.document.removeEventListener('mousedown', l, true);
	}-*/;

	/**
	 * Raycasts a viewport click like Blockbench's paint tools and returns {uuid, alpha} of the first element hit
	 * (alpha 0..1 of the texture pixel under the cursor, -1 when unknown), or null.
	 */
	public static native Object raycastPixel(Object ev) /*-{
		try {
			var P = $wnd.Preview, pv = P && P.selected;
			if (!pv || typeof pv.raycast !== 'function') return null;
			var d = pv.raycast(ev);
			if (!d || d.type !== 'element' || !d.element) return null;
			var res = {uuid: d.element.uuid, alpha: -1};
			var f = d.element.faces && d.face ? d.element.faces[d.face] : null;
			var tex = f && typeof f.getTexture === 'function' ? f.getTexture() : null;
			var Pa = $wnd.Painter;
			if (tex && Pa && typeof Pa.getCanvasToolPixelCoords === 'function' && d.intersects && d.intersects[0]) {
				var xy = Pa.getCanvasToolPixelCoords(d.intersects[0].uv, tex);
				var cv = typeof Pa.getCanvas === 'function' ? Pa.getCanvas(tex) : tex.canvas;
				var px = cv.getContext('2d').getImageData(Math.floor(xy[0]), Math.floor(xy[1]), 1, 1).data;
				res.alpha = px[3] / 255;
			}
			return res;
		} catch (e) { return null; }
	}-*/;

	// ------------------------------------------------------------------ textures (G55)

	/**
	 * RGBA pixels of a texture as {w, h, data} (one getImageData of the texture's canvas, else its image), or null
	 * when the texture is not loaded (error, no size).
	 */
	public static native Object readTexture(Object tex) /*-{
		try {
			if (!tex || tex.error) return null;
			var src = null, w = 0, h = 0;
			if (tex.canvas && tex.canvas.width > 0 && tex.canvas.height > 0) { src = tex.canvas; w = src.width; h = src.height; }
			else if (tex.img && tex.img.naturalWidth > 0) { src = tex.img; w = src.naturalWidth; h = src.naturalHeight; }
			if (!src || w <= 0 || h <= 0) return null;
			var c = $wnd.document.createElement('canvas');
			c.width = w; c.height = h;
			var ctx = c.getContext('2d');
			ctx.drawImage(src, 0, 0);
			var id = ctx.getImageData(0, 0, w, h);
			return {w: w, h: h, data: id.data};
		} catch (e) { return null; }
	}-*/;

	/** texture uv size [w, h] as Blockbench uses it for face UVs (per-texture uv size in CPM projects) */
	public static native double uvWidth(Object tex) /*-{
		try { if (typeof tex.getUVWidth === 'function') return tex.getUVWidth(); } catch (e) {}
		return tex.uv_width || ($wnd.Project && $wnd.Project.texture_width) || 64;
	}-*/;

	public static native double uvHeight(Object tex) /*-{
		try { if (typeof tex.getUVHeight === 'function') return tex.getUVHeight(); } catch (e) {}
		return tex.uv_height || ($wnd.Project && $wnd.Project.texture_height) || 64;
	}-*/;

	/** number of animation frames of a texture (1 when not animated) */
	public static native int frames(Object tex) /*-{
		try { var f = tex.frameCount; return f && f > 1 ? f : 1; } catch (e) { return 1; }
	}-*/;

	/** raw face.texture: a texture uuid string, or null / false / undefined */
	public static native Object faceTexture(Object face) /*-{
		return face ? face.texture : null;
	}-*/;

	/** Cube.prototype.menu (the cube context menu) or null */
	public static native Object cubeMenu() /*-{
		try { return $wnd.Cube && $wnd.Cube.prototype && $wnd.Cube.prototype.menu ? $wnd.Cube.prototype.menu : null; } catch (e) { return null; }
	}-*/;

}
