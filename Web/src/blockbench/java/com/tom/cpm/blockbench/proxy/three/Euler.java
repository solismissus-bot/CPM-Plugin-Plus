package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.Euler (radians; order e.g. 'ZYX' as Blockbench uses for bones) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.Euler")
public class Euler {
	public double x, y, z;
	public String order;

	public Euler() {}
	public Euler(double x, double y, double z, String order) {}

	public native Euler set(double x, double y, double z, String order);
	public native Euler copy(Object e);
	@JsMethod(name = "clone")
	public native Euler copyOf();
	public native Euler setFromQuaternion(Quaternion q, String order);
	public native Euler setFromRotationMatrix(Matrix4 m, String order);
	public native Euler reorder(String order);
}