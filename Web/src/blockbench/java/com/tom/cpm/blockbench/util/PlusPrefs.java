package com.tom.cpm.blockbench.util;

import elemental2.core.Global;
import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.webstorage.Storage;
import elemental2.webstorage.WebStorageWindow;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (INFRA8): per-user plugin preferences (remembered choices, last folders, opt-outs).
 *
 * Everything lives in ONE localStorage key, {@value #STORAGE_KEY} (a JSON object), separate from Blockbench's
 * settings and from the official plugin's keys. Every access is wrapped: a blocked / full / throwing storage never
 * breaks a tool - set() then reports false and get() returns the default. Values are read from storage on every
 * get (no cache), so what get() returns is always what is really stored. Per user only: never put prefs (or
 * absolute paths) into .bbmodel / .cpmproject files.
 *
 * Key names: use 'cpmplus.&lt;name&gt;' WITHOUT a w&lt;digits&gt; package stem (e.g. 'cpmplus.bedrockMap'), so lang_check
 * does not take them for translation keys.
 */
public final class PlusPrefs {
	public static final String STORAGE_KEY = "cpm_plugin_plus_prefs";

	private PlusPrefs() {
	}

	private static Storage storage() {
		return WebStorageWindow.of(DomGlobal.window).localStorage;
	}

	/** the whole prefs object as stored (a new empty object when missing / unreadable / not an object) */
	private static JsPropertyMap<Object> read() {
		try {
			Storage s = storage();
			String txt = s != null ? s.getItem(STORAGE_KEY) : null;
			if (txt != null && !txt.isEmpty()) {
				Object o = Global.JSON.parse(txt);
				if (o != null && "object".equals(Js.typeof(o)) && !JsArray.isArray(o))return Js.uncheckedCast(o);
			}
		} catch (Throwable e) {
		}
		return JsPropertyMap.of();
	}

	private static boolean write(JsPropertyMap<Object> m) {
		try {
			Storage s = storage();
			if (s == null)return false;
			s.setItem(STORAGE_KEY, Global.JSON.stringify(m));
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	/** raw JS value of a pref, or null */
	public static Object getRaw(String key) {
		try {
			Object v = read().get(key);
			return v == null || "undefined".equals(Js.typeof(v)) ? null : v;
		} catch (Throwable e) {
			return null;
		}
	}

	public static String get(String key, String def) {
		Object v = getRaw(key);
		return v != null && "string".equals(Js.typeof(v)) ? Js.asString(v) : def;
	}

	public static boolean getBool(String key, boolean def) {
		Object v = getRaw(key);
		return v != null && "boolean".equals(Js.typeof(v)) ? Js.asBoolean(v) : def;
	}

	public static double getNum(String key, double def) {
		Object v = getRaw(key);
		if (v != null && "number".equals(Js.typeof(v))) {
			double d = Js.asDouble(v);
			return Double.isNaN(d) ? def : d;
		}
		return def;
	}

	/** Java JSON value of a pref (Map/List/Double/String/Boolean), or null */
	public static Object getJson(String key) {
		try {
			return JsonUtil.fromJs(getRaw(key));
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * Stores a value: String, Boolean, Number, a Java JSON value (Map/List) or a plain JS value. null removes the
	 * key. @return false when the storage refused (private mode, quota, blocked): the caller may tell the user
	 * the choice will not be remembered; nothing is thrown.
	 */
	public static boolean set(String key, Object value) {
		try {
			JsPropertyMap<Object> m = read();
			if (value == null)m.delete(key);
			else if (value instanceof java.util.Map || value instanceof java.util.List)m.set(key, JsonUtil.toJs(value));
			else if (value instanceof Number)m.set(key, ((Number) value).doubleValue());
			else m.set(key, value);
			return write(m);
		} catch (Throwable e) {
			return false;
		}
	}

	public static boolean remove(String key) {
		return set(key, null);
	}
}
