package com.tom.cpm.blockbench.proxy;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Modes_$$")
public class Modes {
	public static Modes animate;

	// CPM+ : Mode#selected (true while this mode is active)
	public boolean selected;
}
