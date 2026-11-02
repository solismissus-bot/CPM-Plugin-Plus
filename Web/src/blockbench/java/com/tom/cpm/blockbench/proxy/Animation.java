package com.tom.cpm.blockbench.proxy;

import java.util.Locale;
import java.util.function.Function;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.IPose;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import elemental2.core.JsObject;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Animation_$$")
public class Animation {
	public static Animation[] all;
	public String name, uuid, loop;
	public JsPropertyMap<GeneralAnimator> animators;
	public float length;
	public boolean playing;
	public int snapping;

	@JsProperty(name = "cpm_type")
	public String type;

	@JsProperty(name = "cpm_additive")
	public boolean additive;

	@JsProperty(name = "cpm_layerCtrl")
	public boolean layerCtrl;

	@JsProperty(name = "cpm_commandCtrl")
	public boolean commandCtrl;

	@JsProperty(name = "cpm_priority")
	private int priority;

	@JsProperty(name = "cpm_priority")
	private JsObject priority_;

	@JsOverlay
	public final int getPriority() {
		return Js.typeof(priority_) == "undefined" ? 0 : Js.typeof(priority_) == "object" ? Js.cast(priority_) : priority;
	}

	@JsOverlay
	public final void setPriority(int priority) {
		this.priority = priority;
	}

	@JsProperty(name = "cpm_order")
	private int order;

	@JsProperty(name = "cpm_order")
	private JsObject order_;

	@JsOverlay
	public final int getOrder() {
		return Js.typeof(order_) == "undefined" ? 0 : Js.typeof(order_) == "object" ? Js.cast(order_) : order;
	}

	@JsOverlay
	public final void setOrder(int order) {
		this.order = order;
	}

	@JsProperty(name = "cpm_isProperty")
	public boolean isProperty;

	@JsProperty(name = "cpm_group")
	public String group;

	@JsProperty(name = "cpm_layerDefault")
	private float layerDefault;

	@JsProperty(name = "cpm_layerDefault")
	private JsObject layerDefault_;

	@JsOverlay
	public final float getLayerDefault() {
		if(Js.typeof(layerDefault_) == "undefined")return 0;
		if(Js.typeof(layerDefault_) == "array") {
			float[] a = Js.cast(layerDefault_);
			return a[0];
		}
		if(Js.typeof(layerDefault_) == "object")return Js.cast(layerDefault_);
		return layerDefault;
	}

	@JsOverlay
	public final void setLayerDefault(float layerDefault) {
		this.layerDefault = layerDefault;
	}

	@JsProperty(name = "prototype.menu")
	public static Menu menu;

	// CPM+ : raw prototype access, used to hook propertiesDialog (double click / context menu)
	@JsProperty(name = "prototype")
	public static JsPropertyMap<Object> prototype;

	public static Animation selected;

	// CPM+ : Blockbench Animation#select (selects the animation in the timeline)
	public native void select();

	// ------------------------------------------------------------------
	// CPM+ (W0B-INFRA): Blockbench 5 natives used by the animation tools
	// ------------------------------------------------------------------
	public boolean saved;
	public String path;
	public JsArrayE<TimelineMarker> markers;
	/** registered animation properties (name -> Property) */
	public static JsPropertyMap<Property> properties;

	/** the animator of a group / element (created when missing; null when it can't be animated) */
	public native BoneAnimator getBoneAnimator(Object node);
	public native Animation removeAnimator(String uuid);
	/** sets the length (clamped to the last keyframe); no argument = recalculate */
	public native void setLength(double length);
	public native void setLength();
	public native double getMaxLength();
	/** playhead time inside this animation (loop aware), seconds */
	@JsProperty(name = "time")
	public native double getTime();
	public native Animation remove(boolean undo, boolean removeFromFile);
	public native Animation extend(Object data);
	public native Object getUndoCopy(Object options, boolean save);
	public native Animation togglePlayingState(boolean state);
	public native int calculateSnappingFromKeyframes();
	public native void createUniqueName(Object others);

	// ------------------------------------------------------------------
	// CPM+ : CPM-only animation data persisted on the Blockbench animation
	// (registered as Animation properties in CPMCodec, so they are saved in the
	// .bbmodel). Old projects simply do not have them and fall back to defaults.
	// ------------------------------------------------------------------
	@JsOverlay public static final String P_INT_TYPE = "cpm_intType";
	@JsOverlay public static final String P_FRAMES = "cpm_frames";
	@JsOverlay public static final String P_DURATION = "cpm_duration";
	@JsOverlay public static final String P_MUST_FINISH = "cpm_mustFinish";
	@JsOverlay public static final String P_HIDDEN = "cpm_animHidden";
	@JsOverlay public static final String P_MAX_VALUE = "cpm_maxValue";
	@JsOverlay public static final String P_INTERPOLATE_VAL = "cpm_interpolateVal";

	@JsOverlay
	private static double num(Object o, double def) {
		if (o == null) return def;
		String t = Js.typeof(o);
		if ("number".equals(t)) {
			double d = Js.asDouble(o);
			return Double.isNaN(d) ? def : d;
		}
		if ("boolean".equals(t)) return Js.isTruthy(o) ? 1 : 0;
		if ("string".equals(t)) {
			try {
				return Double.parseDouble(((String) o).trim());
			} catch (NumberFormatException e) {
				return def;
			}
		}
		return def;
	}

	@JsOverlay
	private static boolean bool(Object o, boolean def) {
		if (o == null) return def;
		String t = Js.typeof(o);
		if ("boolean".equals(t)) return Js.isTruthy(o);
		if ("number".equals(t)) return Js.asDouble(o) != 0;
		if ("string".equals(t)) return "true".equalsIgnoreCase((String) o) || "1".equals(o);
		return def;
	}

	@JsOverlay
	public final JsPropertyMap<Object> props() {
		return Js.asPropertyMap(this);
	}

	/** CPM+ : stored CPM interpolator, or null when unknown (animation authored in Blockbench / old project). */
	@JsOverlay
	public final com.tom.cpm.shared.animation.interpolator.InterpolatorType getCpmIntType() {
		Object o = props().get(P_INT_TYPE);
		if (o == null || !"string".equals(Js.typeof(o))) return null;
		String s = (String) o;
		for (com.tom.cpm.shared.animation.interpolator.InterpolatorType t : com.tom.cpm.shared.animation.interpolator.InterpolatorType.VALUES) {
			if (t.name().equalsIgnoreCase(s)) return t;
		}
		return null;
	}

	@JsOverlay
	public final void setCpmIntType(com.tom.cpm.shared.animation.interpolator.InterpolatorType t) {
		props().set(P_INT_TYPE, t == null ? "" : t.name().toLowerCase(Locale.ROOT));
	}

	@JsOverlay
	public final int getCpmFrames() {
		return (int) Math.round(num(props().get(P_FRAMES), 0));
	}

	@JsOverlay
	public final void setCpmFrames(int v) {
		props().set(P_FRAMES, (double) v);
	}

	@JsOverlay
	public final int getCpmDuration() {
		return (int) Math.round(num(props().get(P_DURATION), 0));
	}

	@JsOverlay
	public final void setCpmDuration(int v) {
		props().set(P_DURATION, (double) v);
	}

	@JsOverlay
	public final boolean isMustFinish() {
		return bool(props().get(P_MUST_FINISH), false);
	}

	@JsOverlay
	public final void setMustFinish(boolean v) {
		props().set(P_MUST_FINISH, v);
	}

	@JsOverlay
	public final boolean isAnimHidden() {
		return bool(props().get(P_HIDDEN), false);
	}

	@JsOverlay
	public final void setAnimHidden(boolean v) {
		props().set(P_HIDDEN, v);
	}

	@JsOverlay
	public final int getMaxValue() {
		int v = (int) Math.round(num(props().get(P_MAX_VALUE), 100));
		return v <= 0 ? 100 : v;
	}

	@JsOverlay
	public final void setMaxValue(int v) {
		props().set(P_MAX_VALUE, (double) v);
	}

	@JsOverlay
	public final boolean isInterpolateVal() {
		return bool(props().get(P_INTERPOLATE_VAL), true);
	}

	@JsOverlay
	public final void setInterpolateVal(boolean v) {
		props().set(P_INTERPOLATE_VAL, v);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_GeneralAnimator_$$")
	public static class GeneralAnimator {
		// CPM+ (W0B)
		public boolean selected;

		/** Blockbench's createKeyframe(value, time, channel, undo, select): fills the value from the channel */
		public native Keyframe createKeyframe(Object value, float time, String channel, boolean undo, boolean select);

		/** CPM+ (W0B): BB 5 addKeyframe(data, uuid): a keyframe from data ({channel, time, data_points, interpolation...}) without side effects */
		public native Keyframe addKeyframe(Object data, String uuid);
		/** CPM+ (W0B): every keyframe of every channel of this animator */
		@JsProperty(name = "keyframes")
		public native JsArrayE<Keyframe> getKeyframes();
		public native GeneralAnimator select();
		public native GeneralAnimator toggleMuted(String channel);
		public native GeneralAnimator addToTimeline();
		public native Object getUndoCopy();

		@JsOverlay
		public final Keyframe createKeyframe(float time, String channel) {
			return createKeyframe(null, time, channel, false, false);
		}

		/**
		 * CPM+ : batched keyframe creation for import (Blockbench #3287: loading a .cpmproject with many
		 * animated parts froze Blockbench). GeneralAnimator.createKeyframe runs, for every single key,
		 * fillValues (interpolates the whole channel at that time), replaceOthers (scans the channel),
		 * Timeline.snapTime and Animation.selected.setLength() (scans every keyframe of the animation and
		 * updates the timeline Vue + length slider), i.e. O(keys^2) work plus thousands of UI updates.
		 * The importer sets every value itself, uses unique times and sets the animation length, so the
		 * keyframe is constructed directly and pushed onto the channel. Falls back to createKeyframe if
		 * the Blockbench internals differ.
		 */
		@JsOverlay
		public final Keyframe addKeyframeFast(float time, String channel, String interpolation) {
			try {
				JsPropertyMap<Object> self = Js.asPropertyMap(this);
				Object arr = self.get(channel);
				if (arr != null && Js.typeof(arr) == "object") {
					JsPropertyMap<Object> data = JsPropertyMap.of();
					data.set("channel", channel);
					data.set("time", (double) time);
					if (interpolation != null)data.set("interpolation", interpolation);
					Keyframe kf = new Keyframe(data, null, this);
					kf.channel = channel;
					kf.time = time;
					if (interpolation != null)kf.interpolation = interpolation;
					if (kf.data_points != null && kf.data_points.length > 0) {
						Js.asPropertyMap(kf).set("animator", this);
						Js.<JsArray<Keyframe>>uncheckedCast(arr).push(kf);
						return kf;
					}
				}
			} catch (Throwable e) {
				// fall through to Blockbench's own (slow) path
			}
			Keyframe kf = createKeyframe(time, channel);
			if (kf != null) {
				kf.time = time;
				if (interpolation != null)kf.interpolation = interpolation;
			}
			return kf;
		}

	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class AnimatorChannel {
		public String name;
		public boolean mutable, transform;
		public int max_data_points;
	}

	public native Animation add(boolean undo);

	@JsOverlay
	public static Pair<AnimationType, VanillaPose> parseType(String typeIn) {
		AnimationType type = null;
		for(AnimationType t : AnimationType.VALUES) {
			if(typeIn.equalsIgnoreCase(t.name())) {
				type = t;
				break;
			}
		}
		VanillaPose pose = VanillaPose.GLOBAL;
		if(type == null) {
			type = AnimationType.POSE;
			for (VanillaPose p : VanillaPose.VALUES) {
				if(typeIn.equalsIgnoreCase(p.name())) {
					pose = p;
					break;
				}
			}
		}
		return Pair.of(type, pose);
	}

	@JsOverlay
	public static String makeType(AnimationType type, IPose pose) {
		if(type == AnimationType.POSE)return ((VanillaPose) pose).name().toLowerCase(Locale.ROOT);
		return type.name().toLowerCase(Locale.ROOT);
	}

	//Ported from timeline_animators.js
	@JsOverlay
	private static JsVec3 mapAxes(Function<String, Float> cb) {
		JsVec3 v = new JsVec3();
		v.x = cb.apply("x");
		v.y = cb.apply("y");
		v.z = cb.apply("z");
		return v;
	}

	// CPM+ : visibility lookup fixed.
	// Old code used a strict 'keyframe.time < time', so a key exactly at the sample time
	// (e.g. the only key of a 1-frame toggle at t=0) was ignored and every visibility
	// change landed one CPM frame late. Now a key at t <= time (+epsilon) counts, and
	// before the first key the first key's value is held (same as Blockbench does for
	// step channels and as CPM does: frame 0 is shown from t=0).
	@JsOverlay
	public static boolean interpolateVisible(BoneAnimator anim, float time, boolean def) {
		if(anim == null || !anim.hasVisible())return def;
		if(anim.isMuted(CPM_VISIBILITY))return def;
		Keyframe before = null;
		Keyframe first = null;
		float epsilon = 1/1200f;

		for (Keyframe keyframe : anim.visible.array()) {
			if (first == null || keyframe.time < first.time) first = keyframe;
			if (keyframe.time <= time + epsilon) {
				if (before == null || keyframe.time > before.time) {
					before = keyframe;
				}
			}
		}

		// CPM+ : before the first key the element's default visibility applies (as in the original
		// plugin): 'one hide key at time X' must not hide the part from t=0. Imports always write a key at t=0.
		if(before == null)return def;
		if(before.data_points == null || before.data_points.length == 0)return def;
		return before.data_points.getAt(0).getVisible();
	}

	@JsOverlay
	private static final String CPM_VISIBILITY = "cpm_visibility";

	@JsOverlay
	public static JsVec3 interpolate(JsArrayE<Keyframe> keyframes, float time) {
		if(keyframes == null || keyframes.length == 0)return null;
		Keyframe before = null;
		Keyframe after = null;
		Keyframe result = null;
		float epsilon = 1/1200f;

		for (Keyframe keyframe : keyframes.array()) {
			if (keyframe.time < time) {
				if (before == null || keyframe.time > before.time) {
					before = keyframe;
				}
			} else  {
				if (after == null || keyframe.time < after.time) {
					after = keyframe;
				}
			}
		}
		if (before != null && epsilon(before.time, time, epsilon)) {
			result = before;
		} else if (after != null && epsilon(after.time, time, epsilon)) {
			result = after;
		} else if (before != null && before.interpolation.equals("step")) {
			result = before;
		} else if (before != null && after == null) {
			result = before;
		} else if (after != null && before == null) {
			result = after;
		} else if (before == null && after == null) {
			//
		} else {
			boolean no_interpolations = Blockbench.hasFlag("no_interpolations");
			float alpha = getLerp(before.time, after.time, time);

			if (no_interpolations || (
					before.interpolation.equals("linear") &&
					(after.interpolation.equals("linear") || after.interpolation.equals("step"))
					)) {
				if (no_interpolations) {
					alpha = Math.round(alpha);
				}

				final Keyframe fbefore = before;
				final Keyframe fafter = after;
				final float falpha = alpha;
				return mapAxes(axis -> fbefore.getLerp(fafter, axis, falpha, false));

			} else if (before.interpolation.equals("catmullrom") || after.interpolation.equals("catmullrom")) {
				JsArray<Keyframe> sorted = keyframes.slice().sort((kf1, kf2) -> (kf1.time - kf2.time));
				int before_index = sorted.indexOf(before);
				Keyframe before_plus = sorted.getAt(before_index-1);
				Keyframe after_plus = sorted.getAt(before_index+2);

				final Keyframe fbefore = before;
				final Keyframe fafter = after;
				final float falpha = alpha;
				return mapAxes(axis -> fbefore.getCatmullromLerp(before_plus, fbefore, fafter, after_plus, axis, falpha));

			} else if (before.interpolation.equals("bezier") || after.interpolation.equals("bezier")) {
				// Bezier
				final Keyframe fbefore = before;
				final Keyframe fafter = after;
				final float falpha = alpha;
				return mapAxes(axis -> fbefore.getBezierLerp(fbefore, fafter, axis, falpha));
			}
		}
		if (result != null && result instanceof Keyframe) {
			Keyframe keyframe = result;
			int dp_index = (keyframe.time > time || epsilon(keyframe.time, time, epsilon)) ? 0 : keyframe.data_points.length-1;

			return mapAxes(axis -> keyframe.calc(axis, dp_index));
		}
		return null;
	}

	@JsOverlay
	private static float getLerp(float a, float b, float m) {
		return (m-a) / (b-a);
	}

	@JsOverlay
	private static boolean epsilon(float a, float b, float epsilon) {
		return Math.abs(b - a) < epsilon;
	}
}
