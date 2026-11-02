package com.tom.cpm.blockbench.proxy;

import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_ModelProject_$$")
public class ModelProject {
	public static JsArrayE<Object> all;
	// CPM+ (W0B): registered project properties (Property instances by name)
	public static JsPropertyMap<Property> properties;

	// CPM+ (W0B): one project tab
	public String uuid, name;
	public boolean saved;
	public ModelFormat format;

	public native boolean select();
	/** async in BB 5 (returns a Promise); force=true closes without asking to save */
	public native Object close(boolean force);
}
