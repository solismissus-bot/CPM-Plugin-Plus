package com.tom.cpm.blockbench.proxy;

import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): Blockbench's global Painter (paint tools, mirror modelling helpers) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Painter_$$")
public class Painter {
	/** the element mirrored to this one on the given axes ([x, y, z] booleans), itself when centred, or undefined */
	public static native Object getMirrorElement(Object element, boolean[] symmetryAxes);
	public static native void edit(Texture texture, Texture.EditCallback cb, Object options);

	@JsOverlay
	public static boolean available() {
		return Global.hasFunction(Global.getGlobal("Painter"), "getMirrorElement");
	}
}