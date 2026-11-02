package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.Quaternion */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.Quaternion")
public class Quaternion {
	public double x, y, z, w;

	public Quaternion() {}
	public Quaternion(double x, double y, double z, double w) {}

	public native Quaternion copy(Object q);
	@JsMethod(name = "clone")
	public native Quaternion copyOf();
	public native Quaternion setFromEuler(Euler e);
	public native Quaternion setFromAxisAngle(Vector3 axis, double angle);
	public native Quaternion setFromRotationMatrix(Matrix4 m);
	public native Quaternion multiply(Quaternion q);
	public native Quaternion premultiply(Quaternion q);
	public native Quaternion invert();
	public native Quaternion normalize();
	public native double angleTo(Quaternion q);
}