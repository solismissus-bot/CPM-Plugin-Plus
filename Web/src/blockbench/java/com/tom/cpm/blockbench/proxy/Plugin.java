package com.tom.cpm.blockbench.proxy;

import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Plugin_$$")
public class Plugin {
	public String id;
	// CPM+ : used to detect the official CPM plugin running next to this fork
	public boolean installed, disabled;
	// CPM+ (W0B): metadata of a registered plugin ('source' = store | url | file)
	public String title, version, source, path;

	public static native void register(String id, PluginProperties pr);

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class PluginProperties {
		public String name, author, description, icon, version, variant, min_version;
		// CPM+ : Blockbench reads 'title' (not 'name') plus these metadata fields
		public String title, about, website, repository, bug_tracker;
		public boolean await_loading;
		public String[] tags;
		public Callback onload, onunload;
	}

	@JsFunction
	public interface Callback {
		void run();
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Plugins_$$")
	public static class Plugins {

		public static Plugin[] all;
		/** CPM+ (W0B): plugins installed by the user (persisted list: id, version, source, path, disabled) */
		public static InstalledPlugin[] installed;
		/** CPM+ (W0B): id of the plugin whose file is being evaluated right now */
		public static String currently_loading;
	}

	/** CPM+ (W0B): entry of Plugins.installed */
	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class InstalledPlugin {
		public String id, version, source, path;
		public boolean disabled;
	}

	public native void reload();
	// CPM+ (W0B): runs the plugin's onunload (Blockbench keeps the plugin listed)
	public native Plugin unload();
}
