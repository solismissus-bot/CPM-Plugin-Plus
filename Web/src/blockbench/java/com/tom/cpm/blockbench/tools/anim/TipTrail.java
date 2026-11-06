package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.Collections;
import java.util.List;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusPrefs;

import elemental2.dom.DomGlobal;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Rastro das pontas': the path the TIP of each selected part draws during the animation (Blockbench's own
 * motion trail only follows the group pivot, which barely moves for a rotating part). One line per part, coloured by
 * speed (blue = slow, red = fast), a dot at every frame the export writes; clicking a dot moves the playhead to that
 * frame (then pose the part as usual: the trail follows). Sampled through Blockbench's own preview (the playing
 * animations, like the viewport), redrawn after edits, selection and animation changes; nothing is saved in the
 * project. Shown in the Animate mode only.
 */
public final class TipTrail {
	private TipTrail() {
	}

	static final String PREF = "cpmplus.tiptrail.on";
	private static boolean on;
	private static String lastKey = "";
	private static boolean queued;

	public static boolean isOn() {
		return on;
	}

	public static void toggle() {
		set(!on);
		PlusDialogs.quick(t(on ? "cpmplus.w3animtools.trail.on" : "cpmplus.w3animtools.trail.off"), 3500);
	}

	public static void set(boolean v) {
		on = v;
		PlusPrefs.set(PREF, v);
		lastKey = "";
		if (on)refresh(true);
		else clear();
	}

	public static void install() {
		on = PlusPrefs.getBool(PREF, false);
		PluginStart.addEventListener("finished_edit", e -> queue(true));
		PluginStart.addEventListener("update_selection", e -> queue(false));
		PluginStart.addEventListener("select_mode", e -> queue(true));
		PluginStart.addEventListener("select_project", e -> queue(true));
		PluginStart.addEventListener("display_animation_frame", e -> {
			// the selected animation changed (no dedicated event): redraw
			if (on && !key().equals(lastKey))queue(false);
		});
		installClick();
	}

	public static void uninstall() {
		clear();
		removeClick();
	}

	private static void queue(boolean force) {
		if (!on || queued)return;
		queued = true;
		DomGlobal.setTimeout(a -> {
			queued = false;
			refresh(force);
		}, 60);
	}

	private static String key() {
		Animation a = Animation.selected;
		StringBuilder sb = new StringBuilder(a != null ? a.uuid + ":" + a.length : "-");
		for (Group g : AnimToolSupport.selectedGroups())sb.append('|').append(g.uuid);
		return sb.toString();
	}

	/** recomputes and draws (force: even when the animation / selection did not change) */
	public static Result refresh(boolean force) {
		Result r = new Result();
		String k = key();
		if (!force && k.equals(lastKey))return r;
		lastKey = k;
		clear();
		if (!on || !CPMCodec.isCPM() || !AnimToolSupport.animateMode())return r;
		Animation a = Animation.selected;
		List<Group> parts = AnimToolSupport.selectedGroups();
		if (a == null || parts.isEmpty() || a.length < 1e-4)return r;
		if (parts.size() > 12)parts = parts.subList(0, 12);
		int samples = Math.max(8, Math.min(400, (int) Math.round(a.length * 60)));
		int fps = Math.max(1, AnimEdit.snappingOf(a));
		int frames = Math.max(1, Math.min(400, (int) Math.round(a.length * fps)));
		double[] times = new double[samples + 1 + frames + 1];
		for (int i = 0; i <= samples; i++)times[i] = a.length * i / samples;
		for (int f = 0; f <= frames; f++)times[samples + 1 + f] = a.length * f / frames;
		Object[] groups = parts.toArray();
		double[] tips = new double[parts.size() * 3];
		for (int i = 0; i < parts.size(); i++) {
			double[] tl = Physics.tipOf(parts.get(i), Collections.emptyList());
			if (PhysicsSim.len(tl) < 0.05)tl = new double[3];
			System.arraycopy(tl, 0, tips, i * 3, 3);
		}
		double[] raw = sample(groups, tips, times);
		AnimToolSupport.refreshPreview();
		draw(raw, parts.size(), samples, frames, times);
		r.put("parts", (double) parts.size()).put("samples", (double) samples).put("frames", (double) frames);
		return r;
	}

	/** world positions of each tip at each time (the playing animations, Blockbench's preview) */
	private static native double[] sample(Object[] groups, double[] tipLocal, double[] times) /*-{
		var A = $wnd.Animator, TL = $wnd.Timeline, T = $wnd.THREE, An = $wnd.Animation;
		var out = [], save = TL.time, v = new T.Vector3();
		var list = An.all.filter(function(a) { return a.playing; });
		if (An.selected && list.indexOf(An.selected) < 0) list.push(An.selected);
		var sp = $wnd.scene ? $wnd.scene.position : new T.Vector3();
		try {
			$wnd.__cpmplusNoStepped = true;// the 'stepped preview' must not change what is sampled
			for (var k = 0; k < times.length; k++) {
				TL.time = times[k];
				A.showDefaultPose(true);
				A.stackAnimations(list, false);
				for (var i = 0; i < groups.length; i++) {
					var m = groups[i].mesh;
					if (!m) { out.push(0, 0, 0); continue; }
					m.updateWorldMatrix(true, false);
					v.set(tipLocal[3 * i], tipLocal[3 * i + 1], tipLocal[3 * i + 2]).applyMatrix4(m.matrixWorld).sub(sp);
					out.push(v.x, v.y, v.z);
				}
			}
		} finally {
			$wnd.__cpmplusNoStepped = false;
			TL.time = save;
			if (A.resetLastValues) A.resetLastValues();
		}
		return out;
	}-*/;

	/** one line per part (vertex colours by speed) + frame dots, in a group added to the scene */
	private static native void draw(double[] raw, int n, int samples, int frames, double[] times) /*-{
		var T = $wnd.THREE, scene = $wnd.scene;
		if (!T || !scene) return;
		var root = new T.Group();
		root.name = 'cpmplus_tiptrail';
		root.renderOrder = 999;
		var dots = [];
		for (var p = 0; p < n; p++) {
			var pos = [], speed = [], maxS = 1e-6;
			for (var s = 0; s <= samples; s++) {
				var o = (s * n + p) * 3;
				pos.push(raw[o], raw[o + 1], raw[o + 2]);
				if (s > 0) {
					var q = ((s - 1) * n + p) * 3;
					var d = Math.sqrt(Math.pow(raw[o] - raw[q], 2) + Math.pow(raw[o + 1] - raw[q + 1], 2) + Math.pow(raw[o + 2] - raw[q + 2], 2));
					var sp = d / Math.max(1e-6, times[s] - times[s - 1]);
					speed.push(sp);
					if (sp > maxS) maxS = sp;
				} else speed.push(0);
			}
			if (speed.length > 1) speed[0] = speed[1];
			var cols = [], c = new T.Color();
			for (var s2 = 0; s2 <= samples; s2++) {
				var f = Math.min(1, speed[s2] / maxS);
				c.setHSL(0.66 * (1 - f), 1, 0.5);
				cols.push(c.r, c.g, c.b);
			}
			var g = new T.BufferGeometry();
			g.setAttribute('position', new T.Float32BufferAttribute(pos, 3));
			g.setAttribute('color', new T.Float32BufferAttribute(cols, 3));
			var line = new T.Line(g, new T.LineBasicMaterial({vertexColors: true, depthTest: false, transparent: true}));
			line.renderOrder = 999;
			root.add(line);
			var dp = [];
			for (var f2 = 0; f2 <= frames; f2++) {
				var o2 = ((samples + 1 + f2) * n + p) * 3;
				dp.push(raw[o2], raw[o2 + 1], raw[o2 + 2]);
				dots.push({x: raw[o2], y: raw[o2 + 1], z: raw[o2 + 2], t: times[samples + 1 + f2]});
			}
			var dg = new T.BufferGeometry();
			dg.setAttribute('position', new T.Float32BufferAttribute(dp, 3));
			var pts = new T.Points(dg, new T.PointsMaterial({color: 0xffffff, size: 6, sizeAttenuation: false, depthTest: false, transparent: true}));
			pts.renderOrder = 1000;
			root.add(pts);
		}
		root.userData.cpmplusDots = dots;
		scene.add(root);
		$wnd.__cpmplusTipTrail = root;
		if ($wnd.Canvas && $wnd.Canvas.updateView) try { $wnd.Canvas.updateView({}); } catch (e) {}
	}-*/;

	static native void clear() /*-{
		var r = $wnd.__cpmplusTipTrail;
		if (!r) return;
		if (r.parent) r.parent.remove(r);
		r.traverse(function(o) { if (o.geometry) o.geometry.dispose(); if (o.material) o.material.dispose(); });
		$wnd.__cpmplusTipTrail = null;
	}-*/;

	/** a click on a frame dot (screen distance &lt; 8 px) moves the playhead there; other clicks reach Blockbench */
	private static native void installClick() /*-{
		if ($wnd.__cpmplusTipTrailClick) return;
		var handler = function(e) {
			try {
				var r = $wnd.__cpmplusTipTrail, P = $wnd.Preview, T = $wnd.THREE;
				if (!r || !r.userData.cpmplusDots || !P || !P.selected || e.button !== 0) return;
				var pv = P.selected, cv = pv.canvas, cam = pv.camera;
				if (!cv || !cam || e.target !== cv) return;
				var rect = cv.getBoundingClientRect(), sp = $wnd.scene ? $wnd.scene.position : new T.Vector3();
				var best = null, bd = 8, v = new T.Vector3();
				var dots = r.userData.cpmplusDots;
				for (var i = 0; i < dots.length; i++) {
					v.set(dots[i].x, dots[i].y, dots[i].z).add(sp).project(cam);
					var x = (v.x + 1) / 2 * rect.width, y = (1 - v.y) / 2 * rect.height;
					var d = Math.hypot(x - (e.clientX - rect.left), y - (e.clientY - rect.top));
					if (d < bd && v.z < 1) { bd = d; best = dots[i]; }
				}
				if (!best) return;
				e.stopPropagation();
				e.preventDefault();
				$wnd.Timeline.setTime(best.t);
				if ($wnd.Animator) $wnd.Animator.preview();
			} catch (ex) {}
		};
		$wnd.document.addEventListener('mousedown', handler, true);
		$wnd.__cpmplusTipTrailClick = handler;
	}-*/;

	static native void removeClick() /*-{
		if ($wnd.__cpmplusTipTrailClick) $wnd.document.removeEventListener('mousedown', $wnd.__cpmplusTipTrailClick, true);
		$wnd.__cpmplusTipTrailClick = null;
	}-*/;

	/** QA: what is drawn ({lines, dots, firstDot, lastDot}) */
	static native JsPropertyMap<Object> drawn() /*-{
		var r = $wnd.__cpmplusTipTrail;
		if (!r) return {lines: 0, dots: 0};
		var lines = 0;
		r.children.forEach(function(c) { if (c.type === 'Line') lines++; });
		var d = r.userData.cpmplusDots || [];
		return {lines: lines, dots: d.length, first: d[0] || null, last: d[d.length - 1] || null, inScene: !!r.parent};
	}-*/;

	/** tipTrail({on, refresh}) -&gt; what is drawn */
	public static Object hook(JsPropertyMap<Object> opts) {
		Object o = opts.get("on");
		if (o != null)set(AnimToolSupport.bool(opts, "on", false));
		if (AnimToolSupport.bool(opts, "refresh", false))refresh(true);
		JsPropertyMap<Object> d = drawn();
		d.set("on", on);
		return d;
	}

}
