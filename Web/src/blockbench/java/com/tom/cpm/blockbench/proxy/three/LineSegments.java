package com.tom.cpm.blockbench.proxy.three;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): THREE.LineSegments (pairs of points = line pieces; preview helpers / overlays) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "THREE.LineSegments")
public class LineSegments extends Object3D {
	public BufferGeometry geometry;
	public LineBasicMaterial material;
	public boolean frustumCulled;

	public LineSegments(BufferGeometry geometry, LineBasicMaterial material) {}
}