package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.LineBasicMaterial (dispose() it when the helper is removed) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.LineBasicMaterial")
public class LineBasicMaterial {
	/** THREE.Color */
	public Object color;
	public double linewidth, opacity;
	public boolean transparent, depthTest, depthWrite, vertexColors;

	/** params {color: 0xRRGGBB, transparent, opacity, depthTest, linewidth, vertexColors} */
	public LineBasicMaterial(Object params) {}

	public native void dispose();
}