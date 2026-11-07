package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Chão andando': a checkered floor under the player that scrolls backwards at Minecraft's speed while a walk /
 * run / sneak animation plays (or while the playhead is dragged), so the feet can be judged against the ground: a foot
 * on the ground must move with the squares. CPM plays walk / run / sneak-walk animations on its own clock (they have
 * no state getter), so the floor moves with the timeline: distance = speed x time, kept continuous across loops while
 * playing. Speeds (blocks per second, 1 block = 16 px): walking 4.317, sprinting 5.612, sneaking 1.295. 'Automático'
 * takes the speed from the animation's CPM state (walking / running / sneak_walk, anything else stands still); a
 * speed chosen by hand applies to every animation. Blockbench's own grid is hidden while the floor is shown (static
 * lines under a moving floor would fool the eye) and comes back when it is turned off, when leaving the Animate mode
 * and on unload. View only: nothing goes into the project, the export or the undo history. Off by default; the choice
 * is remembered.
 */
public final class MovingGround {
	private MovingGround() {
	}

	public static final double WALK = 4.317, SPRINT = 5.612, SNEAK = 1.295;
	/** pixels per block */
	public static final double PX = 16;
	/** checker period on the floor (two 8 px squares) */
	static final double PERIOD = 16;
	static final String PREF_ON = "cpmplus.ground.on", PREF_MODE = "cpmplus.ground.mode";
	static final String[] MODES = {"auto", "walk", "sprint", "sneak", "stop"};

	private static boolean on;
	private static String mode = "auto";
	/** loop bookkeeping: the animation the distance belongs to, the last playhead time, completed loops while playing */
	private static String animKey = "";
	private static double lastT = -1;
	private static int wraps;
	private static double dist;

	public static boolean isOn() {
		return on;
	}

	public static String mode() {
		return mode;
	}

	public static void install() {
		on = PlusPrefs.getBool(PREF_ON, false);
		mode = validMode(PlusPrefs.get(PREF_MODE, "auto"));
		PluginStart.addEventListener("display_animation_frame", e -> frame());
		PluginStart.addEventListener("select_mode", e -> DomGlobalTimeout.later(MovingGround::refresh));
		PluginStart.addEventListener("select_project", e -> DomGlobalTimeout.later(MovingGround::refresh));
		PluginStart.addEventListener("unselect_project", e -> remove());
	}

	public static void uninstall() {
		remove();
	}

	static String validMode(String m) {
		for (String s : MODES)if (s.equals(m))return s;
		return "auto";
	}

	public static void set(boolean v) {
		on = v;
		PlusPrefs.set(PREF_ON, v);
		lastT = -1;
		refresh();
		if (v && CPMCodec.isCPM())PlusDialogs.quick(t("cpmplus.w34proa.ground.onHint", speedText(Animation.selected)), 4000);
	}

	public static void setMode(String m) {
		mode = validMode(m);
		PlusPrefs.set(PREF_MODE, mode);
		lastT = -1;
		refresh();
	}

	// ====================================================================== speed

	/** blocks per second of a manual mode ('auto' = -1) */
	static double speedOfMode(String m) {
		switch (m) {
		case "walk": return WALK;
		case "sprint": return SPRINT;
		case "sneak": return SNEAK;
		case "stop": return 0;
		default: return -1;
		}
	}

	/** blocks per second of a CPM state (walking / running / sneak_walk), 0 for every other state */
	public static double speedOfState(String cpmType) {
		if (cpmType == null)return 0;
		switch (cpmType.trim().toLowerCase(java.util.Locale.ROOT)) {
		case "walking": return WALK;
		case "running": return SPRINT;
		case "sneak_walk": return SNEAK;
		default: return 0;
		}
	}

	/** the floor speed for an animation (blocks / s): the hand-picked one, else the one of its CPM state */
	public static double speedFor(Animation a) {
		double m = speedOfMode(mode);
		if (m >= 0)return m;
		return a != null ? speedOfState(a.type) : 0;
	}

	/** true when the speed comes from the animation (Automático), false when picked by hand */
	public static boolean autoMode() {
		return "auto".equals(mode);
	}

	static String speedText(Animation a) {
		double s = speedFor(a);
		String what = s == WALK ? "cpmplus.w34proa.ground.mode.walk" : s == SPRINT ? "cpmplus.w34proa.ground.mode.sprint" : s == SNEAK ? "cpmplus.w34proa.ground.mode.sneak" : "cpmplus.w34proa.ground.mode.stop";
		return t(what);
	}

	// ====================================================================== drawing

	/** creates / removes the floor for the current mode and project, then puts it at the playhead */
	public static void refresh() {
		if (!on || !CPMCodec.isCPM() || !AnimToolSupport.animateMode()) {
			remove();
			return;
		}
		frame();
	}

	/** every preview frame (Animator.preview): floor offset = speed x time, continuous over loops while playing */
	static void frame() {
		if (!on)return;
		if (!CPMCodec.isCPM() || !AnimToolSupport.animateMode()) {
			remove();
			return;
		}
		Animation a = Animation.selected;
		String key = a != null ? a.uuid + "|" + mode : "-|" + mode;
		double t = Timeline.time;
		double len = a != null ? a.length : 0;
		if (!key.equals(animKey)) {
			animKey = key;
			wraps = 0;
			lastT = -1;
		}
		if (lastT >= 0 && Timeline.playing && t < lastT - 1e-6 && len > 1e-4 && a != null && "loop".equals(a.loop))wraps++;
		lastT = t;
		double s = speedFor(a) * PX;
		dist = s * (t + wraps * Math.max(0, len));
		show(dist / PERIOD);
	}

	/** the floor mesh (made once) at offset 'periods' (1 = one 16 px checker period towards +Z, i.e. backwards) */
	private static native void show(double periods) /*-{
		var T = $wnd.THREE, scene = $wnd.scene;
		if (!T || !scene) return;
		var m = $wnd.__cpmplusGround;
		if (!m) {
			var c = $wnd.document.createElement('canvas');
			c.width = 2; c.height = 2;
			var g = c.getContext('2d');
			g.fillStyle = '#5b6170'; g.fillRect(0, 0, 2, 2);
			g.fillStyle = '#3a3e48'; g.fillRect(0, 0, 1, 1); g.fillRect(1, 1, 1, 1);
			var tex = new T.CanvasTexture(c);
			tex.magFilter = T.NearestFilter; tex.minFilter = T.NearestFilter;
			tex.wrapS = T.RepeatWrapping; tex.wrapT = T.RepeatWrapping;
			var W = 128;// 8 blocks
			tex.repeat.set(W / 16, W / 16);
			var geo = new T.PlaneGeometry(W, W, 16, 16);
			// a soft edge that stays put while the squares scroll (vertex alpha, not the texture)
			var pos = geo.attributes.position, cols = [];
			for (var i = 0; i < pos.count; i++) {
				var r = Math.sqrt(pos.getX(i) * pos.getX(i) + pos.getY(i) * pos.getY(i)) / (W / 2);
				var a = r < 0.55 ? 1 : Math.max(0, 1 - (r - 0.55) / 0.4);
				cols.push(1, 1, 1, a);
			}
			geo.setAttribute('color', new T.Float32BufferAttribute(cols, 4));
			var mat = new T.MeshBasicMaterial({map: tex, vertexColors: true, transparent: true, depthWrite: false, side: T.DoubleSide});
			m = new T.Mesh(geo, mat);
			m.name = 'cpmplus_moving_ground';
			m.rotation.x = -Math.PI / 2;
			m.position.set(0, -0.02, 0);
			m.renderOrder = -1;
			m.userData.cpmplusTex = tex;
			$wnd.__cpmplusGround = m;
		}
		if (m.parent !== scene) scene.add(m);
		// plane Y (texture v) points to -Z after the rotation: a growing v offset moves the squares towards +Z
		var off = periods - Math.floor(periods);
		m.userData.cpmplusTex.offset.set(0, off);
		m.userData.cpmplusPeriods = periods;
		var grid = $wnd.three_grid;
		if (grid) {
			if (m.userData.cpmplusGridWas === undefined) m.userData.cpmplusGridWas = grid.visible;
			grid.visible = false;
		}
	}-*/;

	/** removes the floor and gives Blockbench's grid back */
	static native void remove() /*-{
		var m = $wnd.__cpmplusGround;
		if (!m) return;
		var grid = $wnd.three_grid;
		if (grid && m.userData.cpmplusGridWas !== undefined) grid.visible = m.userData.cpmplusGridWas;
		if (m.parent) m.parent.remove(m);
		if (m.geometry) m.geometry.dispose();
		if (m.material) { if (m.material.map) m.material.map.dispose(); m.material.dispose(); }
		$wnd.__cpmplusGround = null;
	}-*/;

	/** QA: what is drawn */
	private static native JsPropertyMap<Object> drawn() /*-{
		var m = $wnd.__cpmplusGround, grid = $wnd.three_grid;
		return {inScene: !!(m && m.parent), offset: m ? m.userData.cpmplusTex.offset.y : null, periods: m ? m.userData.cpmplusPeriods : null,
			gridVisible: grid ? grid.visible : null, y: m ? m.position.y : null};
	}-*/;

	// ====================================================================== speed dialog

	/** CPM+ &gt; Animação &gt; 'Velocidade do chão...' */
	public static void speedDialog() {
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w34proa.ground.speed.intro")));
		rows.add(FormRow.radio("mode", "cpmplus.w34proa.ground.speed.label", PlusDialogs.options(
				"auto", "cpmplus.w34proa.ground.mode.auto",
				"walk", "cpmplus.w34proa.ground.mode.walk",
				"sprint", "cpmplus.w34proa.ground.mode.sprint",
				"sneak", "cpmplus.w34proa.ground.mode.sneak",
				"stop", "cpmplus.w34proa.ground.mode.stop"), mode));
		rows.add(FormRow.checkbox("on", "cpmplus.w34proa.ground.menu", true));
		FormOpts fo = new FormOpts();
		fo.width = 520;
		PlusDialogs.form("cpmplus_w34proa_ground_speed", "cpmplus.w34proa.ground.speed.title", rows, fr -> {
			setMode(fr.getString("mode"));
			boolean v = fr.getBool("on");
			if (v != on)switchTo(v);
			else refresh();
		}, fo);
	}

	/** the menu toggle (kept in step when the dialog / a hook switches the floor on / off) */
	static Object toggle;

	public static void setToggle(Object tg) {
		toggle = tg;
	}

	/** on / off through the menu toggle (its check mark follows), or directly when there is none */
	static void switchTo(boolean v) {
		if (toggle != null && toggleValue(toggle) != v)setToggle0(toggle, v);// Toggle.set -> onChange -> set(v)
		if (on != v)set(v);
	}

	private static native boolean toggleValue(Object tg) /*-{
		return !!tg.value;
	}-*/;

	private static native void setToggle0(Object tg, boolean v) /*-{
		try { if (tg.set) tg.set(v); else { tg.value = v; if (tg.updateEnabledState) tg.updateEnabledState(); } } catch (e) {}
	}-*/;

	// ====================================================================== QA hook

	/** ground({on, mode, refresh}) -&gt; {on, mode, speed (blocks/s of the selected animation), inScene, offset, gridVisible, dist} */
	public static Object hook(JsPropertyMap<Object> opts) {
		if (opts.get("mode") != null)setMode(AnimToolSupport.str(opts, "mode", "auto"));
		if (opts.get("on") != null)switchTo(AnimToolSupport.bool(opts, "on", false));
		if (AnimToolSupport.bool(opts, "refresh", false))refresh();
		JsPropertyMap<Object> d = drawn();
		d.set("on", on);
		d.set("mode", mode);
		d.set("speed", speedFor(Animation.selected));
		d.set("dist", dist);
		d.set("pref", PlusPrefs.getBool(PREF_ON, false));
		return d;
	}

	/** setTimeout 0 helper (Blockbench finishes the mode switch first) */
	static final class DomGlobalTimeout {
		static void later(Runnable r) {
			elemental2.dom.DomGlobal.setTimeout(a -> r.run(), 0);
		}
	}
}
