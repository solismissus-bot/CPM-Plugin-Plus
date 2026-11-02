package com.tom.cpm.blockbench.proxy.three;

import elemental2.core.Float32Array;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.BufferAttribute (vertex data of a BufferGeometry) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.BufferAttribute")
public class BufferAttribute {
	public Object array;
	public int itemSize, count;
	public boolean needsUpdate;

	public BufferAttribute(Float32Array array, int itemSize) {}

	public native BufferAttribute setXYZ(int index, double x, double y, double z);
	public native double getX(int index);
	public native double getY(int index);
	public native double getZ(int index);
}