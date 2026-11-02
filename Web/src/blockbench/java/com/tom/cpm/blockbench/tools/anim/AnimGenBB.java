package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.PartPairing;
import com.tom.cpm.blockbench.util.PartPaths;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-ANIMGEN): Blockbench-side helpers shared by the generators (F4 blink, F8 motion presets, F22 colour
 * slider): part lists and paths, new animations, keyframe writing, and the adapters to the W2-ANIMCORE engine
 * (PartPairing, AnimEdit) so their exact signatures are used in ONE place.
 */
public final class AnimGenBB {
	private AnimGenBB() {
	}

	// ------------------------------------------------------------------ parts

	public static boolean isRoot(Group g) {
		return g != null && g.parent == Group.ROOT;
	}

	/** every group in outliner order (depth first) */
	public static List<Group> groups() {
		List<Group> out = new ArrayList<>();
		Object root = rootList();
		if (root != null)collect(Js.uncheckedCast(root), out);
		return out;
	}

	private static native Object rootList() /*-{
		return $wnd.Outliner && $wnd.Outliner.root ? $wnd.Outliner.root : null;
	}-*/;

	private static void collect(JsArray<Object> list, List<Group> out) {
		for (int i = 0; i < list.length; i++) {
			Object n = list.getAt(i);
			if (n instanceof Group) {
				Group g = (Group) n;
				out.add(g);
				if (g.children != null)collect(Js.uncheckedCast(g.children), out);
			}
		}
	}

	/** the groups a generator may animate (every group below a root part) */
	public static List<Group> childGroups() {
		List<Group> out = new ArrayList<>();
		for (Group g : groups())if (!isRoot(g))out.add(g);
		return out;
	}

	/** 'head/olho_aberto' (names from the root part down) */
	public static String path(Group g) {
		List<String> segs = new ArrayList<>();
		Group n = g;
		int guard = 0;
		while (n != null && guard++ < 1000) {
			segs.add(0, n.name);
			n = parentGroup(n);
		}
		return String.join("/", segs);
	}

	/**
	 * the parent group, null for a root part (its parent is Blockbench's 'root' marker, which must never be read as a
	 * group: under GWT an instanceof test on a native type does not tell them apart)
	 */
	public static Group parentGroup(Group g) {
		if (g == null || isRoot(g))return null;
		Object p = g.parent;
		if (p == null || !"object".equals(Js.typeof(p)))return null;
		return (Group) p;
	}

	/** group at 'head/olho_aberto' (first match per level), null when missing */
	public static Group findByPath(String path) {
		if (path == null || path.isEmpty())return null;
		String[] segs = path.split("/");
		List<Group> level = new ArrayList<>();
		for (Group g : groups())if (isRoot(g))level.add(g);
		Group cur = null;
		for (String s : segs) {
			cur = null;
			for (Group g : level)if (s.equals(g.name)) {
				cur = g;
				break;
			}
			if (cur == null)return null;
			level = new ArrayList<>();
			if (cur.children != null)for (int i = 0; i < cur.children.length; i++) {
				OutlinerNode c = cur.children.getAt(i);
				if (c instanceof Group)level.add((Group) c);
			}
		}
		return cur;
	}

	public static Group byUuid(String uuid) {
		if (uuid == null || uuid.isEmpty())return null;
		for (Group g : groups())if (uuid.equals(g.uuid))return g;
		return null;
	}

	/** 'head > olho_aberto' for lists */
	public static String label(Group g) {
		return path(g).replace("/", " > ");
	}

	/** select options uuid -> 'head > olho' of the child groups; noneKey first when not null */
	public static Map<String, String> groupOptions(String noneKey) {
		Map<String, String> m = new LinkedHashMap<>();
		if (noneKey != null)m.put("", noneKey);
		for (Group g : childGroups())m.put(g.uuid, label(g));
		return m;
	}

	/** the selected group (first), or null */
	public static Group selectedGroup() {
		try {
			Group g = Group.first_selected;
			if (g != null && Js.isTruthy(g))return g;
		} catch (Throwable e) {
		}
		return null;
	}

	public static List<Group> selectedGroups() {
		List<Group> out = new ArrayList<>();
		try {
			if (Group.multi_selected != null)for (Group g : Group.multi_selected)if (g != null)out.add(g);
		} catch (Throwable e) {
		}
		if (out.isEmpty()) {
			Group g = selectedGroup();
			if (g != null)out.add(g);
		}
		return out;
	}

	/** cubes directly inside a group */
	public static List<Cube> directCubes(Group g) {
		List<Cube> out = new ArrayList<>();
		if (g == null || g.children == null)return out;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode c = g.children.getAt(i);
			if (c instanceof Cube)out.add((Cube) c);
		}
		return out;
	}

	/** every cube inside a group (any depth) */
	public static List<Cube> allCubes(Group g) {
		List<Cube> out = new ArrayList<>();
		if (g == null || g.children == null)return out;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode c = g.children.getAt(i);
			if (c instanceof Cube)out.add((Cube) c);
			else if (c instanceof Group)out.addAll(allCubes((Group) c));
		}
		return out;
	}

	/** true when a is b or inside b */
	public static boolean isSameOrInside(Group a, Group b) {
		Group n = a;
		int guard = 0;
		while (n != null && guard++ < 1000) {
			if (n == b)return true;
			n = parentGroup(n);
		}
		return false;
	}

	// ------------------------------------------------------------------ W2-ANIMCORE adapters

	/** groups-only outliner tree for PartPaths / PartPairing */
	public static final PartPaths.Tree<Group> GROUP_TREE = new PartPaths.Tree<Group>() {
		@Override
		public String name(Group node) {
			return node.name;
		}

		@Override
		public Group parent(Group node) {
			return parentGroup(node);
		}

		@Override
		public List<Group> children(Group node) {
			List<Group> out = new ArrayList<>();
			if (node.children != null)for (int i = 0; i < node.children.length; i++) {
				OutlinerNode c = node.children.getAt(i);
				if (c instanceof Group)out.add((Group) c);
			}
			return out;
		}

		@Override
		public List<Group> roots() {
			List<Group> out = new ArrayList<>();
			for (Group g : groups())if (isRoot(g))out.add(g);
			return out;
		}
	};

	/** the part on the other side (F1 pairing: PAIRED or a SUGGESTION), null when there is none */
	public static Group pairOf(Group g) {
		if (g == null)return null;
		try {
			PartPairing.Pair<Group> p = PartPairing.pair(GROUP_TREE, g, null);
			if (p == null || p.target == null || p.target == g)return null;
			if (PartPairing.sure(p.kind) || p.kind == PartPairing.Kind.SUGGESTION)return p.target;
		} catch (Throwable e) {
		}
		return null;
	}

	/** true when the pair is only a guess (the dialog shows it as a suggestion) */
	public static boolean pairIsGuess(Group g) {
		try {
			PartPairing.Pair<Group> p = PartPairing.pair(GROUP_TREE, g, null);
			return p != null && p.kind == PartPairing.Kind.SUGGESTION;
		} catch (Throwable e) {
			return false;
		}
	}

	/** snapping values of an EXISTING animation whose export stays within 255 frames (AnimEdit) */
	public static List<Integer> allowedSnappings(Animation a) {
		List<Integer> out = new ArrayList<>();
		try {
			List<Integer> l = AnimEdit.allowedSnappings(a);
			if (l != null)out.addAll(l);
		} catch (Throwable e) {
		}
		return out;
	}

	/** AnimEdit.predictFrames(anim, snapping, length).ok (frames &lt;= 255, &lt;= 32.767 s) */
	public static boolean framesOk(Animation a, int snapping, double length) {
		try {
			AnimEdit.Frames p = AnimEdit.predictFrames(a, snapping, length);
			return p == null || p.ok;
		} catch (Throwable e) {
			return true;
		}
	}

	// ------------------------------------------------------------------ animations

	public static String uniqueAnimName(String base) {
		String b = base == null || base.trim().isEmpty() ? "animation" : base.trim();
		String n = b;
		int i = 2;
		while (animNamed(n) != null) {
			n = b + " " + i;
			i++;
		}
		return n;
	}

	public static Animation animNamed(String n) {
		if (Animation.all == null)return null;
		for (Animation a : Animation.all)if (a != null && n.equals(a.name))return a;
		return null;
	}

	/**
	 * a new looping animation (call inside the undo edit): name made unique, CPM type, additive, length, snapping;
	 * the other CPM properties keep their defaults
	 */
	public static Animation newAnimation(String name, String type, boolean additive, double length, int snapping) {
		String n = uniqueAnimName(name);
		Animation a = new Animation();
		a.name = n;
		a = a.add(false);
		a.name = n;
		a.loop = "loop";
		a.type = type;
		a.additive = additive;
		a.length = (float) length;
		a.snapping = Math.max(1, Math.min(500, snapping));
		return a;
	}

	/** AnimationWizard's type choices (custom types without the staged ones, then every vanilla pose) */
	public static Map<String, String> typeOptions() {
		Map<String, String> m = new LinkedHashMap<>();
		for (VanillaPose p : VanillaPose.VALUES) {
			if (p != VanillaPose.GLOBAL)continue;
			m.put(p.name().toLowerCase(Locale.ROOT), I18n.get("label.cpm.pose." + p.name().toLowerCase(Locale.ROOT)));
		}
		for (AnimationType t : AnimationType.VALUES) {
			if (!t.isCustom() || t.isStaged())continue;
			String id = t.name().toLowerCase(Locale.ROOT);
			m.put(id, I18n.get("label.cpm.new_anim_" + id));
		}
		for (VanillaPose p : VanillaPose.VALUES) {
			if (p == VanillaPose.CUSTOM || p == VanillaPose.GLOBAL)continue;
			String id = p.name().toLowerCase(Locale.ROOT);
			m.put(id, I18n.get("label.cpm.pose." + id));
		}
		return m;
	}

	/** true for animation types whose 'layer controlled' animations use the free skin layers (custom pose, gesture) */
	public static boolean usesLayers(Animation a) {
		if (a == null || a.type == null || !a.layerCtrl)return false;
		AnimationType t = Animation.parseType(a.type).getKey();
		return t == AnimationType.CUSTOM_POSE || t == AnimationType.GESTURE;
	}

	/** every bone animator of an animation (Object.values(anim.animators)) */
	public static List<BoneAnimator> boneAnimators(Animation a) {
		List<BoneAnimator> out = new ArrayList<>();
		if (a == null || a.animators == null)return out;
		JsPropertyMap<Object> m = Js.uncheckedCast(a.animators);
		m.forEach(k -> {
			Object o = m.get(k);
			if (o instanceof BoneAnimator)out.add((BoneAnimator) o);
		});
		return out;
	}

	/** the animator of a group in an animation (created when missing) */
	public static BoneAnimator animator(Animation a, Group g) {
		return a.getBoneAnimator(g);
	}

	/** the animator only when it exists already (no new animator) */
	public static BoneAnimator existingAnimator(Animation a, Group g) {
		if (a == null || a.animators == null || g == null)return null;
		Object o = a.animators.get(g.uuid);
		return o instanceof BoneAnimator ? (BoneAnimator) o : null;
	}

	public static boolean hasKeys(Animation a, Group g, String channel) {
		BoneAnimator ba = existingAnimator(a, g);
		if (ba == null)return false;
		JsArray<Keyframe> k = Js.uncheckedCast(ba.channel(channel));
		return k != null && k.length > 0;
	}

	/** removes every key of one channel of a group (inside the undo edit) */
	public static void clearChannel(Animation a, Group g, String channel) {
		BoneAnimator ba = existingAnimator(a, g);
		if (ba == null)return;
		Object arr = ba.channel(channel);
		if (arr == null)return;
		JsArray<Keyframe> k = Js.uncheckedCast(arr);
		List<Keyframe> copy = new ArrayList<>();
		for (int i = 0; i < k.length; i++)copy.add(k.getAt(i));
		for (Keyframe kf : copy)removeKey(kf, k);
	}

	private static native void removeKey(Keyframe kf, JsArray<Keyframe> arr) /*-{
		if (typeof kf.remove === 'function') kf.remove();
		var i = arr.indexOf(kf);
		if (i >= 0) arr.splice(i, 1);
	}-*/;

	/** data point {x, y, z} */
	public static JsPropertyMap<Object> xyz(double x, double y, double z) {
		JsPropertyMap<Object> d = JsPropertyMap.of();
		d.set("x", x);
		d.set("y", y);
		d.set("z", z);
		return d;
	}

	/** data point of a visibility key */
	public static JsPropertyMap<Object> vis(boolean visible) {
		JsPropertyMap<Object> d = JsPropertyMap.of();
		d.set("cpm_visible", visible);
		return d;
	}

	/** data point of a colour key (R/G/B 0..255, colour picker place off) */
	public static JsPropertyMap<Object> color(int[] rgb) {
		JsPropertyMap<Object> d = xyz(rgb[0], rgb[1], rgb[2]);
		d.set("cpm_color_picker_place", false);
		return d;
	}

	/**
	 * adds one key (inside the undo edit): Blockbench 5 addKeyframe (no side effects: no snapping, no length change),
	 * then the data point is set again field by field so every Blockbench version stores exactly these values
	 */
	public static Keyframe addKey(BoneAnimator ba, String channel, double time, String interp, JsPropertyMap<Object> dp) {
		Keyframe kf = addKeyNative(ba, channel, time, interp, dp);
		if (kf == null)throw new IllegalStateException("could not create a keyframe on channel " + channel);
		return kf;
	}

	private static native Keyframe addKeyNative(BoneAnimator ba, String channel, double time, String interp, Object dp) /*-{
		var kf = null;
		var data = {channel: channel, time: time, interpolation: interp, data_points: [dp]};
		if (typeof ba.addKeyframe === 'function') {
			kf = ba.addKeyframe(data);
		} else {
			kf = ba.createKeyframe(null, time, channel, false, false);
		}
		if (!kf) return null;
		kf.time = time;
		kf.interpolation = interp;
		if (kf.data_points && kf.data_points.length) {
			var p = kf.data_points[0];
			for (var k in dp) if (Object.prototype.hasOwnProperty.call(dp, k)) p[k] = dp[k];
		}
		return kf;
	}-*/;

	/** after an edit: show the animation in the timeline and refresh the preview (feature-detected) */
	public static native void showAnimation(Animation a) /*-{
		try {
			if (a && typeof a.select === 'function') a.select();
			if ($wnd.Animator && typeof $wnd.Animator.preview === 'function') $wnd.Animator.preview();
		} catch (e) {
			console.warn('CPM+: could not refresh the timeline', e);
		}
	}-*/;

	/** the Blockbench edit mode is Animate (the generators also work in Edit mode; the timeline shows in Animate) */
	public static boolean isCpm() {
		return CPMCodec.isCPM();
	}

	public static String tr(String key) {
		return PlusDialogs.tr(key);
	}

	public static String trf(String key, Object... args) {
		return PlusDialogs.trf(key, args);
	}

	/** number text with at most 2 decimals and a comma for pt-BR ('2,85') */
	public static String num(double v) {
		double r = Math.round(v * 100) / 100.0;
		String s = r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
		if (I18n.locale != null && I18n.locale.toLowerCase(Locale.ROOT).startsWith("pt"))s = s.replace('.', ',');
		return s;
	}
}
