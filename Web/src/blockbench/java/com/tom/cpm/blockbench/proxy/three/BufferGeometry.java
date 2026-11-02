package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.BufferGeometry (dispose() it when the helper is removed) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.BufferGeometry")
public class BufferGeometry {
	public BufferGeometry() {}

	public native BufferGeometry setAttribute(String name, BufferAttribute attribute);
	public native BufferAttribute getAttribute(String name);
	public native void setDrawRange(int start, int count);
	public native BufferGeometry setFromPoints(Object[] points);
	public native void computeBoundingSphere();
	public native void computeBoundingBox();
	public native void dispose();
}