package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.Animation.AnimatorChannel;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_BoneAnimator_$$")
public class BoneAnimator extends GeneralAnimator {
	public static BoneAnimator.Props prototype;

	public Animation animation;
	public String uuid;
	public Object muted;
	public JsArrayE<Keyframe> position;
	public JsArrayE<Keyframe> rotation;
	public JsArrayE<Keyframe> scale;

	@JsProperty(name = "cpm_visibility")
	public JsArrayE<Keyframe> visible;

	@JsProperty(name = "cpm_color")
	public JsArrayE<Keyframe> color;

	public boolean rotation_global;

	// CPM+ (W0B): name kept for animators whose group does not exist (BB 5 '_name'); 'type' is 'bone'
	@JsProperty(name = "_name")
	public String rawName;
	public String type;
	/** the channels of this animator type (BoneAnimator.prototype.channels, incl. cpm_visibility / cpm_color) */
	public JsPropertyMap<AnimatorChannel> channels;

	public BoneAnimator(String uuid, Animation anim) {
	}

	/** CPM+ (W0B): BB 5 constructor with the bone name (used when the group is not (yet) in the outliner) */
	public BoneAnimator(String uuid, Animation anim, String name) {
	}

	/** CPM+ (W0B): the group this animator drives (looked up by uuid), or undefined */
	public native Group getGroup();

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class Props {
		public JsPropertyMap<AnimatorChannel> channels;
	}

	/** CPM+ : Blockbench stores muted channels as {channel: true}. Muted channels are not exported. */
	@JsOverlay
	public final boolean isMuted(String channel) {
		if (muted == null || !"object".equals(jsinterop.base.Js.typeof(muted))) return false;
		return jsinterop.base.Js.isTruthy(jsinterop.base.Js.asPropertyMap(muted).get(channel));
	}

	/** CPM+ (W0B): keyframes of one channel ('position', 'rotation', 'scale', 'cpm_visibility', 'cpm_color'), or null */
	@JsOverlay
	public final JsArrayE<Keyframe> channel(String channel) {
		Object a = jsinterop.base.Js.asPropertyMap(this).get(channel);
		return a != null && "object".equals(jsinterop.base.Js.typeof(a)) ? jsinterop.base.Js.uncheckedCast(a) : null;
	}

	@JsOverlay
	public final boolean hasVisible() {
		return visible != null && visible.length > 0;
	}

	@JsOverlay
	public final boolean hasColor() {
		return color != null && color.length > 0;
	}

	@JsOverlay
	public final boolean hasTransform() {
		return (position != null && position.length > 0) ||
				(rotation != null && rotation.length > 0) ||
				(scale != null && scale.length > 0) ||
				hasColor() || hasVisible();
	}
}