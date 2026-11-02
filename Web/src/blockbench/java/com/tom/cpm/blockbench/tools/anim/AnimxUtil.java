package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W6-ANIMX): helpers shared by the animation analysis / editing tools of W6-ANIMX (G39 situations, G41 X-ray
 * and sub-group, G51 entry/exit, G63 ease, G72 rhythm).
 * <ul>
 * <li>Sampling goes through {@link Animation#interpolate} - the exact function the CPM export bakes frames with.</li>
 * <li>Keyframes are copied with Blockbench's own getUndoCopy() / addKeyframe(), so time, data points, interpolation,
 * bezier handles, bezier_linked and 'uniform' travel unchanged.</li>
 * <li>Undo: Blockbench's 'animations' undo aspect restores keyframes of animators that existed before the edit, but it
 * never removes markers and never removes an animator created during the edit. {@link #track} records, on both undo
 * saves, the exact marker list and animator set of the given animations (Blockbench 'create_undo_save'), and
 * 'load_undo_save' (undo, redo, cancelled edit) puts them back exactly.</li>
 * </ul>
 */
public final class AnimxUtil {
	public static final String POS = "position", ROT = "rotation", SCL = "scale";
	public static final String VIS = CPMCodec.VISIBILITY, COL = CPMCodec.COLOR;
	public static final String[] TRANSFORM = {ROT, POS, SCL};
	public static final String[] ALL = {ROT, POS, SCL, VIS, COL};
	private static final String TRACK_KEY = "cpmplus_w6animx_track";
	private static final String SAVE_KEY = "cpmplus_w6animx";

	private AnimxUtil() {
	}

	// ------------------------------------------------------------------ animations

	public static List<Animation> animations() {
		List<Animation> l = new ArrayList<>();
		JsArrayE<Animation> a = Animator.animations;
		if (a == null)return l;
		for (int i = 0; i < a.length; i++)l.add(a.getAt(i));
		return l;
	}

	/** animation by uuid, else by exact name, else null */
	public static Animation find(String uuidOrName) {
		if (uuidOrName == null)return null;
		for (Animation a : animations())if (uuidOrName.equals(a.uuid))return a;
		for (Animation a : animations())if (uuidOrName.equals(a.name))return a;
		return null;
	}

	/** CPM type of the animation (same parser as the export), null when the animation has no type */
	public static Pair<AnimationType, VanillaPose> typeOf(Animation a) {
		if (a == null || !"string".equals(Js.typeof(a.type)))return null;
		return Animation.parseType(a.type);
	}

	public static boolean isStaged(Animation a) {
		Pair<AnimationType, VanillaPose> t = typeOf(a);
		return t != null && t.getKey().isStaged();
	}

	/** the bone animator of g in a, WITHOUT creating one (null when there is none) */
	public static BoneAnimator animatorOf(Animation a, Group g) {
		if (a == null || g == null || a.animators == null)return null;
		GeneralAnimator ga = a.animators.get(g.uuid);
		return ga instanceof BoneAnimator ? (BoneAnimator) ga : null;
	}

	public static List<BoneAnimator> boneAnimators(Animation a) {
		List<BoneAnimator> l = new ArrayList<>();
		if (a == null || a.animators == null)return l;
		a.animators.forEach(k -> {
			GeneralAnimator ga = a.animators.get(k);
			if (ga instanceof BoneAnimator)l.add((BoneAnimator) ga);
		});
		return l;
	}

	/** keys of a channel (null when the animator / channel is missing) */
	public static JsArrayE<Keyframe> keys(BoneAnimator ba, String ch) {
		return ba == null ? null : ba.channel(ch);
	}

	public static int count(BoneAnimator ba, String ch) {
		JsArrayE<Keyframe> k = keys(ba, ch);
		return k == null ? 0 : k.length;
	}

	/** keys of a channel that count for the export (not muted) */
	public static boolean hasKeys(BoneAnimator ba, String ch) {
		return count(ba, ch) > 0 && !ba.isMuted(ch);
	}

	public static List<Keyframe> sorted(JsArrayE<Keyframe> k) {
		List<Keyframe> l = new ArrayList<>();
		if (k == null)return l;
		for (int i = 0; i < k.length; i++)l.add(k.getAt(i));
		l.sort((a, b) -> Float.compare(a.time, b.time));
		return l;
	}

	/** value of a transform / colour channel at t (the export's own interpolation), null without keys */
	public static double[] sample(JsArrayE<Keyframe> keys, double t) {
		if (keys == null || keys.length == 0)return null;
		JsVec3 v = Animation.interpolate(keys, (float) t);
		if (v == null)return null;
		return new double[] {v.x, v.y, v.z};
	}

	/** rest value of a channel: 0 for rotation / position (and any additive channel), 1 for scale */
	public static double[] rest(String ch) {
		if (SCL.equals(ch))return new double[] {1, 1, 1};
		return new double[] {0, 0, 0};
	}

	/** true when every data point value of the key is a number (no molang) */
	public static native boolean isNumeric(Keyframe kf) /*-{
		if (!kf || !kf.data_points) return false;
		var ax = ['x', 'y', 'z'];
		for (var i = 0; i < kf.data_points.length; i++) {
			var dp = kf.data_points[i];
			for (var j = 0; j < 3; j++) {
				var v = dp[ax[j]];
				if (v === undefined || v === null || typeof v === 'number') continue;
				if (typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v))) continue;
				return false;
			}
		}
		return true;
	}-*/;

	/** numeric value of axis (0..2) of data point dp (molang / missing -> NaN) */
	public static native double value(Keyframe kf, int dp, int axis) /*-{
		var p = kf && kf.data_points && kf.data_points[dp];
		if (!p) return NaN;
		var v = p[['x', 'y', 'z'][axis]];
		if (typeof v === 'number') return v;
		if (typeof v === 'string' && v.trim() !== '') { var n = Number(v); return isNaN(n) ? NaN : n; }
		return NaN;
	}-*/;

	public static native int dataPoints(Keyframe kf) /*-{
		return kf && kf.data_points ? kf.data_points.length : 0;
	}-*/;

	public static native boolean visibleValue(Keyframe kf) /*-{
		var p = kf && kf.data_points && kf.data_points[0];
		if (!p) return true;
		var v = p.cpm_visible;
		if (typeof v === 'string') return v === 'true';
		return v === undefined ? true : !!v;
	}-*/;

	/** a full copy of the key (time, data points, interpolation, handles, bezier_linked, uniform ...) without its uuid */
	public static native Object keyCopy(Keyframe kf) /*-{
		var c = kf.getUndoCopy();
		delete c.uuid;
		delete c.animator;
		return c;
	}-*/;

	/** adds a key from data ({channel, time, data_points, interpolation ...}) to the animator (no undo, no selection) */
	public static native Keyframe addKey(Object animator, Object data) /*-{
		var id = typeof $wnd.guid === 'function' ? $wnd.guid() : undefined;
		return animator.addKeyframe(data, id);
	}-*/;

	public static native Object setTime(Object data, double time) /*-{
		data.time = time;
		return data;
	}-*/;

	/** key data for a transform / colour channel with one data point */
	public static native Object keyData(String channel, double time, String interp, double x, double y, double z) /*-{
		return {channel: channel, time: time, interpolation: interp, data_points: [{x: x, y: y, z: z}]};
	}-*/;

	/** key data for the CPM visibility channel */
	public static native Object visKeyData(double time, boolean visible) /*-{
		return {channel: 'cpm_visibility', time: time, interpolation: 'step', data_points: [{cpm_visible: visible}]};
	}-*/;

	/** sets one handle component: side 'left' / 'right', what 'time' / 'value' */
	public static native void setHandle(Keyframe kf, String side, String what, int axis, double v) /*-{
		var f = 'bezier_' + side + '_' + what;
		var a = kf[f];
		if (!a || typeof a.length !== 'number') { a = [0, 0, 0]; kf[f] = a; }
		a[axis] = v;
	}-*/;

	public static native double getHandle(Keyframe kf, String side, String what, int axis) /*-{
		var a = kf['bezier_' + side + '_' + what];
		return a && typeof a[axis] === 'number' ? a[axis] : 0;
	}-*/;

	// ------------------------------------------------------------------ groups

	/** vanilla body part roots present in the project (head, body, arms, legs, cape, elytra), outliner order */
	public static List<Group> vanillaRoots() {
		List<Group> l = new ArrayList<>();
		if (Group.all == null)return l;
		for (Group g : Group.all) {
			if (isVanillaRoot(g))l.add(g);
		}
		return l;
	}

	public static boolean isVanillaRoot(Group g) {
		if (g == null || g.parent != Group.ROOT || g.name == null || g.name.endsWith("_dup"))return false;
		VanillaModelPart p = g.getRootType();
		if (p instanceof PlayerModelParts)return true;
		return p == RootModelType.CAPE || p == RootModelType.ELYTRA_LEFT || p == RootModelType.ELYTRA_RIGHT;
	}

	public static boolean isHead(Group g) {
		return g != null && g.getRootType() == PlayerModelParts.HEAD;
	}

	public static Group groupByUuidOrName(String s) {
		if (s == null || Group.all == null)return null;
		for (Group g : Group.all)if (s.equals(g.uuid))return g;
		for (Group g : Group.all)if (s.equals(g.name))return g;
		return null;
	}

	/** every group below g (any depth) */
	public static List<Group> descendants(Group g) {
		List<Group> l = new ArrayList<>();
		if (g == null || g.children == null)return l;
		for (int i = 0; i < g.children.length; i++) {
			Object c = g.children.getAt(i);
			if (c instanceof Group) {
				l.add((Group) c);
				l.addAll(descendants((Group) c));
			}
		}
		return l;
	}

	/** lang key of a vanilla part name (cpmplus.w6animx.part.&lt;name&gt;) */
	public static String partKey(Group g) {
		VanillaModelPart p = g.getRootType();
		return p != null ? "cpmplus.w6animx.part." + p.getName().toLowerCase(Locale.ROOT) : null;
	}

	// ------------------------------------------------------------------ editing helpers

	public static int snapping(Animation a) {
		return a != null && a.snapping > 0 ? a.snapping : 20;
	}

	public static String uniqueAnimName(String base) {
		String n = base;
		int i = 2;
		while (nameTaken(n))n = base + " " + (i++);
		return n;
	}

	private static boolean nameTaken(String n) {
		for (Animation a : animations())if (n.equals(a.name))return true;
		return false;
	}

	/** a new animation added to the project (call inside an undo edit) */
	public static Animation newAnimation(String name, String type, String loop, double length, int snapping) {
		Animation a = new Animation().add(false);
		a.name = name;
		a.loop = loop;
		a.type = type;
		a.snapping = snapping;
		a.length = (float) length;
		return a;
	}

	/** copies the CPM button / layer / priority flags of src (priority through AnimEdit.clampByte) */
	public static void copyFlags(Animation src, Animation dst) {
		dst.additive = src.additive;
		dst.layerCtrl = src.layerCtrl;
		dst.commandCtrl = src.commandCtrl;
		dst.setPriority(AnimEdit.clampByte(src.getPriority()));
		dst.isProperty = src.isProperty;
		if ("string".equals(Js.typeof(src.group)))dst.group = src.group;
	}

	/** refreshes the animation preview (never throws) */
	public static void preview() {
		try {
			if (Animator.open)Animator.preview();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: preview refresh failed", e);
		}
	}

	// ------------------------------------------------------------------ dependency adapters (AnimEdit)

	/** AnimEdit.predictFrames: frames CPM will get for this animation at snapping s and length L */
	public static int predictFrames(Animation a, int snapping, double length) {
		AnimEdit.Frames p = AnimEdit.predictFrames(a, snapping, length);
		return p != null ? p.frames : (int) Math.ceil(length * snapping - 1e-6);
	}

	// ------------------------------------------------------------------ undo tracking of markers / new animators

	/** records the markers and the animator set of these animations in the undo save made from d */
	public static UndoData track(UndoData d, Collection<Animation> anims) {
		JsArray<Object> arr = new JsArray<>();
		for (Animation a : anims)if (a != null)arr.push(a);
		Js.asPropertyMap(d).set(TRACK_KEY, arr);
		return d;
	}

	/** listeners for {@link #track} (W6AnimxInstall) */
	public static void installUndoTracking() {
		PluginStart.addEventListener("create_undo_save", AnimxUtil::onCreateSave);
		PluginStart.addEventListener("load_undo_save", AnimxUtil::onLoadSave);
	}

	private static void onCreateSave(Object e) {
		try {
			createSave0(e, TRACK_KEY, SAVE_KEY);
		} catch (Throwable ex) {
			DomGlobal.console.warn("CPM+: could not record the animation markers for undo", ex);
		}
	}

	private static void onLoadSave(Object e) {
		try {
			loadSave0(e, SAVE_KEY);
		} catch (Throwable ex) {
			DomGlobal.console.warn("CPM+: could not restore the animation markers", ex);
		}
	}

	private static native void createSave0(Object e, String tk, String sk) /*-{
		if (!e || !e.aspects || !e.save) return;
		var list = e.aspects[tk];
		if (!list || typeof list.length !== 'number') return;
		var rec = {};
		for (var i = 0; i < list.length; i++) {
			var a = list[i];
			if (!a || !a.uuid) continue;
			var m = [];
			if (a.markers) for (var j = 0; j < a.markers.length; j++) { var mk = a.markers[j]; m.push({time: mk.time, color: mk.color, name: mk.name}); }
			rec[a.uuid] = {markers: m, animators: Object.keys(a.animators || {})};
		}
		e.save[sk] = rec;
	}-*/;

	private static native void loadSave0(Object e, String sk) /*-{
		var rec = e && e.save && e.save[sk];
		if (!rec) return;
		var anims = $wnd.Animator && $wnd.Animator.animations || [];
		for (var uuid in rec) {
			var a = null;
			for (var i = 0; i < anims.length; i++) if (anims[i].uuid === uuid) { a = anims[i]; break; }
			if (!a) continue;
			var r = rec[uuid];
			if (a.markers) {
				a.markers.splice(0, a.markers.length);
				for (var j = 0; j < r.markers.length; j++) a.markers.push(new $wnd.TimelineMarker(r.markers[j]));
			}
			if (a.animators) {
				for (var k in a.animators) {
					if (r.animators.indexOf(k) >= 0) continue;
					var ba = a.animators[k];
					delete a.animators[k];
					var tl = $wnd.Timeline && $wnd.Timeline.animators;
					if (tl) { var idx = tl.indexOf(ba); if (idx >= 0) tl.splice(idx, 1); }
				}
			}
		}
	}-*/;

	/** BB 5 web/desktop: true when the undo system dispatches create_undo_save (the tracking above works) */
	public static native boolean undoTrackingSupported() /*-{
		try {
			var s = $wnd.UndoSystem && $wnd.UndoSystem.save;
			if (!s) return false;
			var src = String(s.prototype && s.prototype.fromState || s);
			return src.indexOf('create_undo_save') >= 0;
		} catch (e) {
			return false;
		}
	}-*/;

	// ------------------------------------------------------------------ small JS helpers

	public static JsPropertyMap<Object> map() {
		return JsPropertyMap.of();
	}

	public static <T> JsArray<T> arr(Collection<T> c) {
		JsArray<T> a = new JsArray<>();
		if (c != null)for (T t : c)a.push(t);
		return a;
	}

	/** copy of an array (GWT has no array clone()) */
	public static double[] cp(double[] a) {
		return a == null ? null : java.util.Arrays.copyOf(a, a.length);
	}

	public static String fmt(double v) {
		double r = Math.round(v * 1000) / 1000.0;
		String s = String.valueOf(r);
		if (s.endsWith(".0"))s = s.substring(0, s.length() - 2);
		return s;
	}
}
