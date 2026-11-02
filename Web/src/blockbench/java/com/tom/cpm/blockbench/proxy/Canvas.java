package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.three.Object3D;
import com.tom.ugwt.client.JsArrayE;
import com.tom.ugwt.client.JsRunnable;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Canvas_$$")
public class Canvas {
	public static native void updateVisibility();
	public static native void updateAllBones();

	// CPM+ (W0B): scene access for preview tools
	public static Object3D scene;
	/** helper objects hidden in screenshots (add own helpers here, remove them on unload) */
	public static JsArrayE<Object3D> gizmos;
	public static Object3D outlines;
	/** runs cb with gizmos / grids / outlines hidden (screenshots) */
	public static native void withoutGizmos(JsRunnable cb);
	public static native void updateAllPositions();
	public static native void updateAll();
	/** options {elements, element_aspects, groups, group_aspects, selection} */
	public static native void updateView(Object options);
}