package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.Raycaster (picking in the 3D view) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.Raycaster")
public class Raycaster {
	public Object ray, params;
	public double near, far;

	public Raycaster() {}

	/** coords = THREE.Vector2-like {x, y} in normalized device coordinates (-1..1) */
	public native void setFromCamera(Object coords, Object camera);
	public native void set(Vector3 origin, Vector3 direction);
	/** -> array of {distance, point, object, face} sorted by distance */
	public native Object[] intersectObjects(Object[] objects, boolean recursive);
	public native Object[] intersectObject(Object object, boolean recursive);
}