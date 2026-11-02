package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Keyframe_$$")
public class Keyframe {
	public static JsArrayE<Keyframe> selected;

	public BoneAnimator animator;
	public String uuid, interpolation, channel;
	public boolean transform, bezier_linked;
	public float time;
	public JsVec3 bezier_left_time, bezier_left_value, bezier_right_time, bezier_right_value;
	public int color;
	public JsArrayE<KeyframeDataPoint> data_points;
	// CPM+ (W0B)
	public boolean uniform;

	// CPM+ : Blockbench's constructor new Keyframe(data, uuid, animator); used for batched import
	public Keyframe(Object data, String uuid, Object animator) {}

	public native float getLerp(Keyframe after, String axis, float alpha, boolean allow_exp);
	public native float getBezierLerp(Keyframe before, Keyframe after, String axis, float alpha);
	public native float getCatmullromLerp(Keyframe before_plus, Keyframe before, Keyframe after, Keyframe after_plus, String axis, float alpha);
	public native float calc(String axis, int dp_index);

	// CPM+ (W0B): editing natives (Blockbench 5.2)
	/** copy of the keyframe data (save=true keeps the uuid and bezier handles) */
	public native Object getUndoCopy(boolean save, Object options);
	public native Object getUndoCopy();
	/** mirrors the values on an axis (0 = x) the way Blockbench's 'flip' does */
	public native Keyframe flip(int axis);
	/** removes the keyframe from its animator channel and the timeline selection (no undo by itself) */
	public native void remove();
	public native Keyframe extend(Object data);
	public native Keyframe select(Object event);
	public native Keyframe select();
	/** raw value of an axis ('x', 'y', 'z') of a data point (number or molang string) */
	public native Object get(String axis, int dataPoint);
	public native Keyframe set(String axis, Object value, int dataPoint);
	public native Keyframe offset(String axis, double amount, int dataPoint);
	public native Keyframe getPreviousKeyframe();
	public native Object getArray(int dataPoint);
}