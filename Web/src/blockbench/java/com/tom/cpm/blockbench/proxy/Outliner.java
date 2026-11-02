package com.tom.cpm.blockbench.proxy;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Outliner_$$")
public class Outliner {
	public static OutlinerElement[] selected;
	// CPM+ : every outliner element (cubes, meshes, locators, null objects, ...)
	public static OutlinerElement[] elements;
	// CPM+ (W0B): top-level nodes (Groups and elements); serialized structure
	public static Object root;
	public static native Object toJSON();
}
