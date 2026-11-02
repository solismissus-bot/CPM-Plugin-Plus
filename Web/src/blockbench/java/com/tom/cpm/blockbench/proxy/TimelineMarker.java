package com.tom.cpm.blockbench.proxy;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): a timeline marker of an animation (Animation.markers) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_TimelineMarker_$$")
public class TimelineMarker {
	/** data {time, color, name} */
	public TimelineMarker(Object data) {}

	public String uuid;
	public double time;
	public int color;
	public Object name;

	public native TimelineMarker extend(Object data);
	public native Object getUndoCopy();
	public native TimelineMarker callPlayhead();
}