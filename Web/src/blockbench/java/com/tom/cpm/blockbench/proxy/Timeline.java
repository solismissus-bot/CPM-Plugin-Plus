package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Timeline_$$")
public class Timeline {
	public static JsArrayE<Keyframe> selected;
	// CPM+ : Blockbench's playhead is fractional seconds (was declared int)
	public static float time;

	// CPM+ (W0B): playback and timeline state
	/** animators shown in the timeline */
	public static JsArrayE<GeneralAnimator> animators;
	public static boolean playing;
	/** every keyframe shown in the timeline (getter) */
	public static JsArrayE<Keyframe> keyframes;
	public static Object vue;

	public static native void start();
	public static native void pause();
	/** moves the playhead (seconds); editing=true skips the timecode update (while dragging) */
	public static native void setTime(double seconds, boolean editing);
	public static native void setTime(double seconds);
	public static native double snapTime(double time, Object animation);
	public static native double getStep();
	public static native void revealTime(double time);
}
