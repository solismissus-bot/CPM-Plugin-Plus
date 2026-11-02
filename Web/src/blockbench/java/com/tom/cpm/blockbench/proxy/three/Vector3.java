package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.Vector3 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.Vector3")
public class Vector3 {
	public double x, y, z;

	public Vector3() {}
	public Vector3(double x, double y, double z) {}

	public native Vector3 set(double x, double y, double z);
	public native Vector3 copy(Object v);
	@JsMethod(name = "clone")
	public native Vector3 copyOf();
	public native Vector3 add(Object v);
	public native Vector3 sub(Object v);
	public native Vector3 multiplyScalar(double s);
	public native Vector3 applyQuaternion(Quaternion q);
	public native Vector3 applyMatrix4(Matrix4 m);
	public native Vector3 applyEuler(Euler e);
	public native Vector3 normalize();
	public native double length();
	public native double distanceTo(Object v);
	public native Vector3 project(Object camera);
	public native Vector3 fromArray(double[] array);
	public native double[] toArray();
}