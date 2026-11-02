package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.Matrix4 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.Matrix4")
public class Matrix4 {
	public double[] elements;

	public Matrix4() {}

	public native Matrix4 identity();
	public native Matrix4 copy(Matrix4 m);
	@JsMethod(name = "clone")
	public native Matrix4 copyOf();
	public native Matrix4 multiply(Matrix4 m);
	public native Matrix4 premultiply(Matrix4 m);
	public native Matrix4 multiplyMatrices(Matrix4 a, Matrix4 b);
	public native Matrix4 invert();
	public native Matrix4 makeRotationFromEuler(Euler e);
	public native Matrix4 makeRotationFromQuaternion(Quaternion q);
	public native Matrix4 makeTranslation(double x, double y, double z);
	public native Matrix4 compose(Vector3 position, Quaternion quaternion, Vector3 scale);
	public native void decompose(Vector3 position, Quaternion quaternion, Vector3 scale);
	public native Matrix4 setPosition(double x, double y, double z);
}