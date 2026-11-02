package com.tom.cpm.blockbench.proxy.three;

import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.Object3D")
public class Object3D {
	public String name;
	public JsArrayE<Object3D> children;

	public ThreeVec3 position, scale, fix_position;
	public ThreeRot rotation, fix_rotation;
	public boolean visible;

	public native void add(Object3D b);

	// CPM+ (W0B-INFRA)
	public Object3D parent;
	public String uuid;
	/** THREE.Quaternion / THREE.Matrix4 of this object (typed access: proxy.three.Quaternion / Matrix4) */
	public Object quaternion, matrixWorld, userData;
	public int renderOrder;
	public native void remove(Object3D b);
	public native void updateMatrixWorld(boolean force);
	public native Object getWorldPosition(Object target);
	public native Object getWorldQuaternion(Object target);
	public native void traverse(TraverseCallback cb);

	@jsinterop.annotations.JsFunction
	public interface TraverseCallback {
		void visit(Object3D o);
	}
}
