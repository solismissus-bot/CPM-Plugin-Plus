package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Project_$$")
public class Project {
	public static int texture_width, texture_height;
	public static String name, geometry_name;
	public static boolean box_uv;
	public static JsArrayE<Animation> animations;
	public static ModelFormat format;
	public static JsArrayE<Group> selected_groups;
	// CPM+ (W0B): open project state. save_path / export_path are local absolute paths: never store them in files
	public static boolean saved;
	public static String uuid, save_path, export_path;

	@JsProperty(name = "cpm_data")
	public static String pluginData;

	@JsProperty(name = "cpm_hideHeadIfSkull")
	public static boolean hideHeadIfSkull;

	@JsProperty(name = "cpm_removeBedOffset")
	public static boolean removeBedOffset;

	@JsProperty(name = "cpm_invisGlow")
	public static boolean invisGlow;

	//UGWT won't replace unless in separate class
	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	private static class G {

		@JsProperty(name = "$$ugwt_m_Project_$$")
		public static Object project;
	}

	@JsOverlay
	public static Object getProject() {
		return G.project;
	}

	/** CPM+ (W0B): uuid of the open project tab, null on the start screen */
	@JsOverlay
	public static String getUuid() {
		Object p = G.project;
		if (p == null || !Js.isTruthy(p) || !"object".equals(Js.typeof(p)))return null;
		Object u = Js.asPropertyMap(p).get("uuid");
		return u != null && "string".equals(Js.typeof(u)) ? (String) u : null;
	}

	@JsProperty(name = "cpm_dataCache")
	private static ProjectData data;

	@JsOverlay
	public static ProjectData getData() {
		// CPM+ (G16): parse again when cpm_data was replaced since (restored snapshot, remote edit)
		if(data == null || !data.isFor(pluginData))data = new ProjectData(true);
		return data;
	}

	@JsOverlay
	public static void setData(ProjectData data) {
		Project.data = data;
	}
}
