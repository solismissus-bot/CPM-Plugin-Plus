package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.Action.Condition;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_MenuBar_$$")
public class MenuBar {
	public static native void addAction(Action a, String id);
	// CPM+
	public static native void removeAction(String path);
	// CPM+ (W0B): re-evaluates which bar menus are shown
	public static native void update();

	public static JsPropertyMap<BarMenu> menus;

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_BarItem_$$")
	public static class BarItem {
		public String id;
		public Condition condition;
		// CPM+ (W0B)
		public native boolean conditionMet();
		public native void delete();
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_BarMenu_$$")
	public static class BarMenu extends Menu {
		// CPM+ (W0B)
		public String id, name;
		public Object condition;

		public BarMenu(String id, Object[] content, BarMenuInit options) {
			super(content);
		}
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class BarMenuInit {
		public Condition condition;
		public String name;
		// CPM+ (W0B)
		public String icon;
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class SubMenu {
		public String name, description, icon, id;
		public Object[] children;
		// CPM+ (W0B)
		public Condition condition;
	}
}
