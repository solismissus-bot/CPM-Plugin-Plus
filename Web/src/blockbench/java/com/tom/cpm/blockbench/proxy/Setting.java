package com.tom.cpm.blockbench.proxy;

import elemental2.core.JsObject;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W0B-INFRA): a Blockbench setting (File > Settings). Settings created by the plugin must be deleted on
 * unload (delete()). Also gives access to the global 'settings' map and Blockbench's 'Language'.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Setting_$$")
public class Setting {
	public Setting(String id, SettingOptions options) {}

	public String id, type, name, description, category;
	/** current value (getter/setter; profiles aware) */
	public Object value;

	public native void delete();
	public native void set(Object value);

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class SettingOptions {
		public String name, description, category, type, icon;
		/** 'toggle' | 'number' | 'text' | 'password' | 'select' | 'click' */
		public Object value;
		public double min, max, step;
		public JsObject options;
		public boolean requires_restart;
		public Object condition;
		public SettingChange onChange;
	}

	@JsFunction
	public interface SettingChange {
		void onChange(Object value);
	}

	//UGWT won't replace unless in separate class
	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	private static class G {
		@JsProperty(name = "$$ugwt_m_settings_$$")
		public static JsPropertyMap<Setting> settings;

		@JsProperty(name = "$$ugwt_m_Language_$$")
		public static Language language;
	}

	/** the global 'settings' map (id -> Setting) */
	@JsOverlay
	public static JsPropertyMap<Setting> all() {
		return G.settings;
	}

	@JsOverlay
	public static Setting get(String id) {
		JsPropertyMap<Setting> s = G.settings;
		return s != null ? s.get(id) : null;
	}

	/** Blockbench's interface language setting value (e.g. 'en', 'pt'), or null */
	@JsOverlay
	public static String languageSetting() {
		Setting s = get("language");
		Object v = s != null ? s.value : null;
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : null;
	}

	/** Blockbench's Language object (Language.code = active interface language, e.g. 'pt'), or null */
	@JsOverlay
	public static Language getLanguage() {
		return Global.getGlobal("Language") != null ? G.language : null;
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class Language {
		public String code;
		public JsPropertyMap<String> data;
		public native void addTranslations(String language, JsPropertyMap<String> strings);
	}
}