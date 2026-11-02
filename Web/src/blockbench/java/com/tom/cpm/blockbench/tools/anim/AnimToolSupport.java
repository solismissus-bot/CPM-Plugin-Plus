package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Modes;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.PlusDialogs;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W2-ANIMCORE): shared pieces of the animation tools (F1 / F2 / F6 / F7 / D6): the result every tool core
 * returns ({ok, changed, warnings[], errors[]} + codes), option parsing for the QA hooks, the "is the rest pose
 * mirrored?" check, and the current-context helpers (clicked group, current animation, Animate mode).
 */
public final class AnimToolSupport {
	private AnimToolSupport() {
	}

	/** result of a tool core (also what the QA hook returns) */
	public static final class Result {
		public boolean ok = true, changed;
		public final List<String> warnings = new ArrayList<>(), errors = new ArrayList<>();
		public final List<String> warningCodes = new ArrayList<>(), errorCodes = new ArrayList<>();
		public final Map<String, Object> extra = new LinkedHashMap<>();

		public Result warn(String code, String text) {
			if (!warningCodes.contains(code) || !warnings.contains(text)) {
				warningCodes.add(code);
				warnings.add(text);
			}
			return this;
		}

		public Result error(String code, String text) {
			ok = false;
			errorCodes.add(code);
			errors.add(text);
			return this;
		}

		public boolean hasWarning(String code) {
			return warningCodes.contains(code);
		}

		public Result put(String k, Object v) {
			extra.put(k, v);
			return this;
		}

		public JsPropertyMap<Object> toJs() {
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("ok", ok);
			r.set("changed", changed);
			r.set("warnings", strs(warnings));
			r.set("errors", strs(errors));
			r.set("warningCodes", strs(warningCodes));
			r.set("errorCodes", strs(errorCodes));
			for (Map.Entry<String, Object> e : extra.entrySet())r.set(e.getKey(), JsonUtil.toJs(e.getValue()));
			return r;
		}

		/** warnings as one text (dialogs) */
		public String warningText() {
			return String.join("\n", warnings);
		}

		public String errorText() {
			return String.join("\n", errors);
		}
	}

	static JsArray<Object> strs(List<String> l) {
		JsArray<Object> a = new JsArray<>();
		for (String s : l)a.push(s);
		return a;
	}

	// ====================================================================== hook option parsing

	static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	static boolean bool(JsPropertyMap<Object> o, String k, boolean def) {
		Object v = o.get(k);
		return v == null ? def : Js.isTruthy(v);
	}

	static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		if (v == null)return def;
		if ("number".equals(Js.typeof(v)))return Js.asDouble(v);
		try {
			return Double.parseDouble(String.valueOf(v));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	static List<String> list(JsPropertyMap<Object> o, String k) {
		List<String> out = new ArrayList<>();
		Object v = o.get(k);
		if (v == null)return out;
		if (JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
		} else out.add(String.valueOf(v));
		return out;
	}

	/** 'all' or a list of channel names / short names (rot, pos, scale, vis, color) */
	static Set<String> channels(JsPropertyMap<Object> o, String k) {
		Set<String> s = new LinkedHashSet<>();
		Object v = o.get(k);
		if (v == null || "all".equals(v)) {
			for (String c : KeyMath.CHANNELS)s.add(c);
			return s;
		}
		for (String c : list(o, k))s.add(channelName(c));
		return s;
	}

	static String channelName(String c) {
		switch (c) {
		case "rot":
		case "rotation":
			return KeyMath.ROT;
		case "pos":
		case "position":
			return KeyMath.POS;
		case "scale":
			return KeyMath.SCALE;
		case "vis":
		case "visibility":
		case "cpm_visibility":
			return KeyMath.VIS;
		case "color":
		case "colour":
		case "cpm_color":
			return KeyMath.COLOR;
		default:
			return c;
		}
	}

	/** animation by name, or the selected one */
	static Animation anim(JsPropertyMap<Object> o) {
		String n = str(o, "anim", null);
		if (n == null)return Animation.selected;
		return AnimEdit.byName(n);
	}

	/** group by full path (or plain name when unique) */
	static Group group(String pathOrName) {
		if (pathOrName == null)return null;
		Group g = AnimEdit.groupByPath(pathOrName);
		if (g != null)return g;
		Group found = null;
		for (Group x : Group.all)if (pathOrName.equals(x.name)) {
			if (found != null)return null;
			found = x;
		}
		return found;
	}

	// ====================================================================== context

	/** true while the Animate mode is open */
	public static boolean animateMode() {
		try {
			return Modes.animate != null && Modes.animate.selected;
		} catch (Throwable e) {
			return false;
		}
	}

	/** the group the user clicked (outliner / timeline / viewport): first selected group, else the timeline's animator */
	public static Group clickedGroup() {
		Group g = Group.first_selected;
		if (g != null)return g;
		Object sel = selectedAnimator();
		if (sel instanceof BoneAnimator)return AnimEdit.groupOf((BoneAnimator) sel);
		return null;
	}

	/** every selected group (outliner order of selection) */
	public static List<Group> selectedGroups() {
		List<Group> out = new ArrayList<>();
		if (Group.multi_selected != null)for (Group g : Group.multi_selected)if (g != null)out.add(g);
		if (out.isEmpty()) {
			Group g = clickedGroup();
			if (g != null)out.add(g);
		}
		return out;
	}

	private static native Object selectedAnimator() /*-{
		return $wnd.Timeline ? $wnd.Timeline.selected_animator : null;
	}-*/;

	/** selects the animation, plays it, refreshes the preview (after a tool changed it) */
	public static void showAndPlay(Animation a) {
		try {
			if (a != null && Animation.selected != a)a.select();
			Animator.preview();
			if (!Timeline.playing)Timeline.start();
		} catch (Throwable e) {
			// preview only
		}
	}

	public static void refreshPreview() {
		try {
			if (animateMode())Animator.preview();
		} catch (Throwable e) {
		}
	}

	// ====================================================================== rest symmetry

	/**
	 * true when b is the mirror image of a at rest (origins mirrored on X within 0.01, rotation (rx, -ry, -rz)
	 * within 0.1 degree), checked up the parent chain until both reach the same parent. Vanilla arms / legs pass.
	 */
	public static boolean restMirrored(Group a, Group b) {
		Group x = a, y = b;
		int guard = 0;
		while (x != null && y != null && x != y && guard++ < 200) {
			if (!pairMirrored(x, y))return false;
			Group px = parentGroup(x), py = parentGroup(y);
			if (px == py)return true;
			x = px;
			y = py;
		}
		return x == y;
	}

	private static boolean pairMirrored(Group a, Group b) {
		if (a.origin == null || b.origin == null)return true;
		if (Math.abs(a.origin.x + b.origin.x) >= 0.01 || Math.abs(a.origin.y - b.origin.y) >= 0.01 || Math.abs(a.origin.z - b.origin.z) >= 0.01)return false;
		float[] ra = rot(a), rb = rot(b);
		return Math.abs(ra[0] - rb[0]) < 0.1 && Math.abs(ra[1] + rb[1]) < 0.1 && Math.abs(ra[2] + rb[2]) < 0.1;
	}

	private static float[] rot(Group g) {
		if (g.rotation == null)return new float[3];
		return new float[] {g.rotation.x, g.rotation.y, g.rotation.z};
	}

	static Group parentGroup(Group g) {
		return g.parent instanceof Group && g.parent != Group.ROOT ? g.parent : null;
	}

	/** visibility of a part when no key says otherwise */
	static boolean defaultVisible(Group g) {
		return g == null || !g.hidden;
	}

	/** enables / disables the confirm button of the open CPM+ dialog with this id (OK disabled while input is invalid) */
	public static native void setConfirmEnabled(String id, boolean on) /*-{
		try {
			var d = $wnd.Dialog && $wnd.Dialog.stack ? $wnd.Dialog.stack[$wnd.Dialog.stack.length - 1] : null;
			if (!d || d.id !== id || !d.object) return;
			var bar = d.object.querySelectorAll('.dialog_bar button');
			var b = bar.length ? bar[d.confirmIndex || 0] : null;
			if (b) { b.disabled = !on; b.style.opacity = on ? '' : '0.5'; }
		} catch (e) {}
	}-*/;

	/** pt-BR / en text of a lang key */
	static String t(String key, Object... args) {
		return args.length == 0 ? PlusDialogs.tr(key) : PlusDialogs.trf(key, args);
	}
}
