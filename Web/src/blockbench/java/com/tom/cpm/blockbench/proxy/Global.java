package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.MenuBar.BarItem;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsObject;
import elemental2.dom.DomGlobal;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_window_$$")
public class Global {
	public static native boolean newProject(ModelFormat format);
	public static native void setProjectTitle();
	public static native void updateSelection();
	// CPM+ (W0B)
	public static native void unselectAllElements();
	public static native String guid();

	@JsMethod(name = "tl")
	public static native String translate(String key);

	public static native JsObject require(String name);

	//UGWT won't replace unless in separate class
	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	private static class G {

		@JsProperty(name = "$$ugwt_m_isApp_$$")
		public static boolean isApp;

		@JsMethod(name = "$$ugwt_m_getAllGroups_$$")
		public static native JsArrayE<Group> getAllGroups();

		@JsProperty(name = "$$ugwt_m_Format_$$")
		public static ModelFormat format;

		@JsProperty(name = "$$ugwt_m_Formats_$$")
		public static JsPropertyMap<ModelFormat> formats;

		@JsProperty(name = "$$ugwt_m_BarItems_$$")
		public static JsPropertyMap<BarItem> barItems;

		@JsProperty(name = "$$ugwt_m_Language.data_$$")
		public static JsPropertyMap<String> lang;

		// CPM+ (W0B)
		@JsProperty(name = "$$ugwt_m_Blockbench_$$")
		public static Object blockbench;

		@JsMethod(name = "$$ugwt_m_Condition_$$")
		public static native boolean condition(Object condition, Object context);
	}

	@JsOverlay
	public static boolean isApp() {
		return G.isApp;
	}

	@JsOverlay
	public static ModelFormat getFormat() {
		return G.format;
	}

	@JsOverlay
	public static JsArrayE<Group> getAllGroups() {
		return G.getAllGroups();
	}

	@JsOverlay
	public static JsPropertyMap<ModelFormat> getFormats() {
		return G.formats;
	}

	@JsOverlay
	public static BarItem getBarItem(String id) {
		return G.barItems.get(id);
	}

	// CPM+ (W0B)
	@JsOverlay
	public static JsPropertyMap<BarItem> getBarItems() {
		return G.barItems;
	}

	@JsOverlay
	public static JsPropertyMap<String> getLang() {
		return G.lang;
	}

	/** CPM+ (W0B): the global Blockbench object (for typeof checks of optional APIs) */
	@JsOverlay
	public static Object getBlockbench() {
		return G.blockbench;
	}

	/**
	 * CPM+ (W0B): Blockbench's Condition(condition, context): true when a BarItem / menu / form condition is met
	 * (undefined = met). Never throws: a broken condition counts as not met.
	 */
	@JsOverlay
	public static boolean conditionMet(Object condition, Object context) {
		try {
			return G.condition(condition, context);
		} catch (Throwable e) {
			return false;
		}
	}

	/** CPM+ (W0B): true when obj.name exists and is a function (feature detection of optional BB APIs) */
	@JsOverlay
	public static boolean hasFunction(Object obj, String name) {
		try {
			if (obj == null)return false;
			String t = Js.typeof(obj);
			if (!"object".equals(t) && !"function".equals(t))return false;
			return "function".equals(Js.typeof(Js.asPropertyMap(obj).get(name)));
		} catch (Throwable e) {
			return false;
		}
	}

	/** CPM+ (W0B): window[name] (every Blockbench global used by the plugin is on window in BB 5), or null */
	@JsOverlay
	public static Object getGlobal(String name) {
		try {
			Object v = Js.asPropertyMap(DomGlobal.window).get(name);
			return v == null || "undefined".equals(Js.typeof(v)) ? null : v;
		} catch (Throwable e) {
			return null;
		}
	}

	/** CPM+ (W0B): typeof window[name] ('undefined' when missing) */
	@JsOverlay
	public static String typeOfGlobal(String name) {
		try {
			return Js.typeof(Js.asPropertyMap(DomGlobal.window).get(name));
		} catch (Throwable e) {
			return "undefined";
		}
	}

	/** CPM+ (W0B): a project tab is open (the global 'Project' is 0 / undefined on the start screen) */
	@JsOverlay
	public static boolean isProjectOpen() {
		try {
			Object p = Project.getProject();
			return p != null && Js.isTruthy(p) && "object".equals(Js.typeof(p));
		} catch (Throwable e) {
			return false;
		}
	}
}
