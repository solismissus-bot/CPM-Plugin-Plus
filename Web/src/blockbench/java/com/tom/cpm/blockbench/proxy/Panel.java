package com.tom.cpm.blockbench.proxy;

import elemental2.dom.HTMLElement;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): a Blockbench interface panel. Panels created by the plugin must be deleted on unload. */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Panel_$$")
public class Panel {
	public Panel(String id, PanelOptions options) {}

	public String id, name, icon;
	public HTMLElement node;
	public boolean folded;

	public native void delete();
	public native void fold(boolean state);
	public native void update();
	public native void moveTo(String slot);

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class PanelOptions {
		public String name, icon, id;
		public Object condition, display_condition, menu, default_position, toolbars;
		public VueComponent component;
		public boolean growable, resizable, optional;
		public int min_height;
	}
}