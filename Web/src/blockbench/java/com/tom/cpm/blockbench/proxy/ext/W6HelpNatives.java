package com.tom.cpm.blockbench.proxy.ext;

import jsinterop.annotations.JsFunction;

/**
 * CPM+ (W6-HELP, G50): three.js / DOM natives of the skeleton overlay. Every optional Blockbench API is feature-detected
 * (typeof); nothing here dispatches synthetic keyboard or mouse events.
 */
public final class W6HelpNatives {
	private W6HelpNatives() {
	}

	@JsFunction
	public interface StrCallback {
		void call(String a, String b);
	}

	@JsFunction
	public interface VoidCallback {
		void call();
	}


	/** the label CSS of the skeleton overlay; removed by {@link #removeStyle()} */
	public static native void ensureStyle() /*-{
		try {
			if ($wnd.document.getElementById('cpmplus_w6help_style')) return;
			var s = $wnd.document.createElement('style');
			s.id = 'cpmplus_w6help_style';
			s.textContent = '.cpmplus_skel_label { position: absolute; pointer-events: auto; cursor: pointer; font-size: 11px; line-height: 13px; padding: 0 3px; border-radius: 3px; white-space: nowrap; background: rgba(0,0,0,0.55); color: #fff; transform: translate(-50%, -140%); z-index: 3; user-select: none; }';
			$wnd.document.head.appendChild(s);
		} catch (e) {}
	}-*/;

	public static native void removeStyle() /*-{
		try {
			var s = $wnd.document.getElementById('cpmplus_w6help_style');
			if (s && s.parentNode) s.parentNode.removeChild(s);
		} catch (e) {}
	}-*/;

	/** calls cb after ms (setTimeout); returns the timer id */
	public static native double later(int ms, VoidCallback cb) /*-{
		return $wnd.setTimeout(function() { cb(); }, ms);
	}-*/;

	public static native void cancel(double timer) /*-{
		try { $wnd.clearTimeout(timer); } catch (e) {}
	}-*/;

	// ------------------------------------------------------------------ G50 skeleton overlay

	/**
	 * Creates the overlay: a THREE.Group 'cpmplus_skeleton' in Canvas.scene, registered in Canvas.gizmos (hidden in
	 * screenshots / GIFs / thumbnails by Canvas.withoutGizmos), with one solid + one dashed LineSegments (one buffer each,
	 * drawRange, no per-frame allocation) and screen-sized joint points; per-frame positions come from the groups'
	 * mesh.matrixWorld in onBeforeRender. Labels are HTML over the selected viewport (max 40). Returns the state object.
	 */
	public static native Object skelCreate(StrCallback onLabelClick) /*-{
		var THREE = $wnd.THREE, Canvas = $wnd.Canvas;
		if (!THREE || !Canvas || !Canvas.scene) return null;
		var st = { nodes: [], cap: 0, labelsOn: true, axes: false, segs: 0, labelCount: 0, labelEls: [], onLabel: onLabelClick };
		var root = new THREE.Group();
		root.name = 'cpmplus_skeleton';
		root.renderOrder = 999;
		function mat(dashed) {
			var p = { vertexColors: true, transparent: true, opacity: 0.9, depthTest: false, depthWrite: false };
			if (dashed && THREE.LineDashedMaterial) { p.dashSize = 1.5; p.gapSize = 1; return new THREE.LineDashedMaterial(p); }
			return new THREE.LineBasicMaterial(p);
		}
		function lines(dashed) {
			var g = new THREE.BufferGeometry();
			var l = new THREE.LineSegments(g, mat(dashed));
			l.frustumCulled = false;
			l.renderOrder = 999;
			l.name = dashed ? 'cpmplus_skeleton_dashed' : 'cpmplus_skeleton_solid';
			return l;
		}
		st.solid = lines(false);
		st.dashed = lines(true);
		var pg = new THREE.BufferGeometry();
		var pm = new THREE.PointsMaterial({ size: 6, sizeAttenuation: false, vertexColors: true, transparent: true, opacity: 0.95, depthTest: false, depthWrite: false });
		st.points = new THREE.Points(pg, pm);
		st.points.frustumCulled = false;
		st.points.renderOrder = 1000;
		st.points.name = 'cpmplus_skeleton_joints';
		root.add(st.solid);
		root.add(st.dashed);
		root.add(st.points);
		st.root = root;
		st.tmp = new THREE.Vector3();
		st.tmp2 = new THREE.Vector3();
		st.solid.onBeforeRender = function(renderer, scene, camera) {
			try { @com.tom.cpm.blockbench.proxy.ext.W6HelpNatives::skelFrame(*)(st, camera); } catch (e) {}
		};
		Canvas.scene.add(root);
		if (Canvas.gizmos && Canvas.gizmos.indexOf(root) < 0) Canvas.gizmos.push(root);
		return st;
	}-*/;

	/**
	 * New topology. nodes = [{g: Group, parent: index|-1, stub: [x,y,z] (offset from the pivot in the group's own
	 * unrotated frame) | null, color: '#rrggbb', dashed: bool, label: text|null, prio: number}].
	 */
	public static native void skelSet(Object st, Object nodes, boolean labels, boolean axes) /*-{
		var THREE = $wnd.THREE;
		if (!st || !THREE) return;
		st.nodes = nodes;
		st.labelsOn = labels;
		st.axes = axes;
		var n = nodes.length;
		var segMax = n * 2 + (axes ? n * 3 : 0) + 4;
		if (segMax > st.cap) {
			st.cap = segMax;
			var attr = function(geom, verts) {
				geom.setAttribute('position', new THREE.BufferAttribute(new Float32Array(verts * 3), 3));
				geom.setAttribute('color', new THREE.BufferAttribute(new Float32Array(verts * 3), 3));
			};
			attr(st.solid.geometry, segMax * 2);
			attr(st.dashed.geometry, segMax * 2);
			attr(st.points.geometry, Math.max(n, 1) + 4);
		} else if (st.points.geometry.getAttribute('position').count < n) {
			st.points.geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(n * 3 + 12), 3));
			st.points.geometry.setAttribute('color', new THREE.BufferAttribute(new Float32Array(n * 3 + 12), 3));
		}
		for (var i = 0; i < n; i++) {
			var c = new THREE.Color(nodes[i].color || '#cccccc');
			nodes[i].rgb = [c.r, c.g, c.b];
			nodes[i].wp = [0, 0, 0];
			nodes[i].kids = [];
		}
		for (var j = 0; j < n; j++) if (nodes[j].parent >= 0) nodes[nodes[j].parent].kids.push(j);
		// labels: roots / selection first, max 40
		@com.tom.cpm.blockbench.proxy.ext.W6HelpNatives::skelClearLabels(*)(st);
		if (labels) {
			var idx = [];
			for (var k = 0; k < n; k++) if (nodes[k].label) idx.push(k);
			idx.sort(function(a, b) { return (nodes[b].prio || 0) - (nodes[a].prio || 0) || a - b; });
			idx = idx.slice(0, 40);
			var host = @com.tom.cpm.blockbench.proxy.ext.W6HelpNatives::skelLabelHost()();
			if (host) {
				for (var m = 0; m < idx.length; m++) {
					var nd = nodes[idx[m]];
					var el = $wnd.document.createElement('div');
					el.className = 'cpmplus_skel_label';
					el.textContent = nd.label;
					el.style.borderLeft = '3px solid ' + (nd.color || '#ccc');
					el.style.display = 'none';
					(function(node, element) {
						element.addEventListener('click', function(ev) { ev.stopPropagation(); if (st.onLabel) st.onLabel(node.g ? node.g.uuid : '', ''); });
					})(nd, el);
					host.appendChild(el);
					st.labelEls.push({ el: el, node: idx[m] });
				}
			}
		}
		st.labelCount = st.labelEls.length;
		// segment count (topology): parent -> each child, + one stub per leaf with cubes
		var s = 0;
		for (var q = 0; q < n; q++) { s += nodes[q].kids.length; if (nodes[q].stub && nodes[q].kids.length === 0) s++; }
		st.segs = s;
	}-*/;

	/** element over the selected viewport that holds the labels */
	private static native Object skelLabelHost() /*-{
		try {
			var p = $wnd.Preview && $wnd.Preview.selected;
			if (!p || !p.node) return null;
			var host = p.node.querySelector(':scope > .cpmplus_skel_labels');
			if (!host) {
				host = $wnd.document.createElement('div');
				host.className = 'cpmplus_skel_labels';
				host.style.cssText = 'position:absolute; left:0; top:0; right:0; bottom:0; pointer-events:none; overflow:hidden; z-index:3;';
				if ($wnd.getComputedStyle(p.node).position === 'static') p.node.style.position = 'relative';
				p.node.appendChild(host);
			}
			return host;
		} catch (e) { return null; }
	}-*/;

	private static native void skelClearLabels(Object st) /*-{
		if (!st) return;
		for (var i = 0; i < st.labelEls.length; i++) { var e = st.labelEls[i].el; if (e && e.parentNode) e.parentNode.removeChild(e); }
		st.labelEls = [];
		st.labelCount = 0;
		try {
			var hosts = $wnd.document.querySelectorAll('.cpmplus_skel_labels');
			for (var j = 0; j < hosts.length; j++) if (!hosts[j].childNodes.length && hosts[j].parentNode) hosts[j].parentNode.removeChild(hosts[j]);
		} catch (e) {}
	}-*/;

	/** per frame (onBeforeRender of the solid lines): world positions from mesh.matrixWorld into the buffers */
	private static native void skelFrame(Object st, Object camera) /*-{
		var nodes = st.nodes, n = nodes.length, v = st.tmp, w = st.tmp2;
		for (var i = 0; i < n; i++) {
			var g = nodes[i].g, mesh = g && g.mesh;
			if (!mesh) continue;
			mesh.getWorldPosition(v);
			nodes[i].wp[0] = v.x; nodes[i].wp[1] = v.y; nodes[i].wp[2] = v.z;
		}
		var sp = st.solid.geometry.getAttribute('position'), sc = st.solid.geometry.getAttribute('color');
		var dp = st.dashed.geometry.getAttribute('position'), dc = st.dashed.geometry.getAttribute('color');
		var pp = st.points.geometry.getAttribute('position'), pc = st.points.geometry.getAttribute('color');
		if (!sp || !dp || !pp) return;
		var si = 0, di = 0;
		function put(attrP, attrC, at, p, rgb) {
			attrP.array[at * 3] = p[0]; attrP.array[at * 3 + 1] = p[1]; attrP.array[at * 3 + 2] = p[2];
			attrC.array[at * 3] = rgb[0]; attrC.array[at * 3 + 1] = rgb[1]; attrC.array[at * 3 + 2] = rgb[2];
		}
		var tip = [0, 0, 0];
		for (var a = 0; a < n; a++) {
			var nd = nodes[a];
			var dashed = !!nd.dashed;
			for (var k = 0; k < nd.kids.length; k++) {
				var ch = nodes[nd.kids[k]];
				if (dashed) { put(dp, dc, di++, nd.wp, ch.rgb); put(dp, dc, di++, ch.wp, ch.rgb); }
				else { put(sp, sc, si++, nd.wp, ch.rgb); put(sp, sc, si++, ch.wp, ch.rgb); }
			}
			if (nd.stub && nd.kids.length === 0 && nd.g && nd.g.mesh) {
				w.set(nd.stub[0], nd.stub[1], nd.stub[2]);
				w.applyMatrix4(nd.g.mesh.matrixWorld);
				tip[0] = w.x; tip[1] = w.y; tip[2] = w.z;
				if (dashed) { put(dp, dc, di++, nd.wp, nd.rgb); put(dp, dc, di++, tip, nd.rgb); }
				else { put(sp, sc, si++, nd.wp, nd.rgb); put(sp, sc, si++, tip, nd.rgb); }
			}
			if (st.axes && nd.g && nd.g.mesh) {
				var e = nd.g.mesh.matrixWorld.elements;
				var cols = [[1, 0.25, 0.25], [0.25, 1, 0.25], [0.3, 0.5, 1]];
				for (var ax = 0; ax < 3; ax++) {
					var len = Math.sqrt(e[ax * 4] * e[ax * 4] + e[ax * 4 + 1] * e[ax * 4 + 1] + e[ax * 4 + 2] * e[ax * 4 + 2]) || 1;
					tip[0] = nd.wp[0] + e[ax * 4] / len * 2; tip[1] = nd.wp[1] + e[ax * 4 + 1] / len * 2; tip[2] = nd.wp[2] + e[ax * 4 + 2] / len * 2;
					put(sp, sc, si++, nd.wp, cols[ax]); put(sp, sc, si++, tip, cols[ax]);
				}
			}
			put(pp, pc, a, nd.wp, nd.rgb);
		}
		st.solid.geometry.setDrawRange(0, si);
		st.dashed.geometry.setDrawRange(0, di);
		st.points.geometry.setDrawRange(0, n);
		sp.needsUpdate = true; sc.needsUpdate = true; dp.needsUpdate = true; dc.needsUpdate = true; pp.needsUpdate = true; pc.needsUpdate = true;
		if (di > 0 && typeof st.dashed.computeLineDistances === 'function') { try { st.dashed.computeLineDistances(); } catch (e) {} }
		// labels over the selected viewport only
		var pv = $wnd.Preview && $wnd.Preview.selected;
		if (!st.labelEls.length || !pv || camera !== pv.camera || !pv.canvas) return;
		var cw = pv.canvas.clientWidth || pv.width, chh = pv.canvas.clientHeight || pv.height;
		for (var l = 0; l < st.labelEls.length; l++) {
			var le = st.labelEls[l], p = nodes[le.node].wp;
			v.set(p[0], p[1], p[2]);
			v.project(camera);
			if (v.z > 1 || v.z < -1 || v.x < -1.2 || v.x > 1.2 || v.y < -1.2 || v.y > 1.2) { le.el.style.display = 'none'; continue; }
			le.el.style.display = '';
			le.el.style.left = ((v.x + 1) / 2 * cw).toFixed(1) + 'px';
			le.el.style.top = ((1 - v.y) / 2 * chh).toFixed(1) + 'px';
		}
	}-*/;

	/** removes the overlay: scene, Canvas.gizmos, geometries, materials, label DOM */
	public static native void skelDispose(Object st) /*-{
		if (!st) return;
		@com.tom.cpm.blockbench.proxy.ext.W6HelpNatives::skelClearLabels(*)(st);
		try {
			var Canvas = $wnd.Canvas;
			if (st.root) {
				if (st.root.parent) st.root.parent.remove(st.root);
				if (Canvas && Canvas.gizmos) { var i = Canvas.gizmos.indexOf(st.root); if (i >= 0) Canvas.gizmos.splice(i, 1); }
			}
			[st.solid, st.dashed, st.points].forEach(function(o) {
				if (!o) return;
				o.onBeforeRender = function() {};
				if (o.geometry) o.geometry.dispose();
				if (o.material) o.material.dispose();
			});
		} catch (e) {}
		st.nodes = [];
		st.root = null;
	}-*/;

	/** {segments, labels, inGizmos, inScene, nodes} (test hook / state) */
	public static native Object skelState(Object st) /*-{
		var Canvas = $wnd.Canvas;
		var inScene = !!(Canvas && Canvas.scene && Canvas.scene.getObjectByName('cpmplus_skeleton'));
		if (!st || !st.root) return { segments: 0, labels: 0, inGizmos: false, inScene: inScene, nodes: 0, on: false };
		var pairs = [], labels = [];
		for (var i = 0; i < st.nodes.length; i++) {
			var nd = st.nodes[i];
			pairs.push([nd.g ? nd.g.uuid : null, nd.parent >= 0 && st.nodes[nd.parent].g ? st.nodes[nd.parent].g.uuid : null, !!nd.dashed, nd.color || '']);
		}
		for (var j = 0; j < st.labelEls.length; j++) labels.push(st.labelEls[j].el.textContent);
		return { segments: st.segs, labels: st.labelCount, inGizmos: !!(Canvas.gizmos && Canvas.gizmos.indexOf(st.root) >= 0), inScene: inScene,
			nodes: st.nodes.length, on: true, labelDom: $wnd.document.querySelectorAll('.cpmplus_skel_label').length, pairs: pairs, labelTexts: labels };
	}-*/;

	/** number of label DOM nodes left anywhere (unload check) */
	public static native int skelLabelDomCount() /*-{
		try { return $wnd.document.querySelectorAll('.cpmplus_skel_label').length; } catch (e) { return 0; }
	}-*/;


	/** theme colour token (CSS variable on body) or the fallback */
	public static native String themeColor(String token, String fallback) /*-{
		try {
			var v = $wnd.getComputedStyle($wnd.document.body).getPropertyValue(token);
			v = v ? v.trim() : '';
			if (/^#[0-9a-fA-F]{6}$/.test(v)) return v;
			if (/^#[0-9a-fA-F]{3}$/.test(v)) return '#' + v[1] + v[1] + v[2] + v[2] + v[3] + v[3];
			var m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(v);
			if (m) return '#' + [m[1], m[2], m[3]].map(function(x) { var h = (+x).toString(16); return h.length < 2 ? '0' + h : h; }).join('');
		} catch (e) {}
		return fallback;
	}-*/;
}
