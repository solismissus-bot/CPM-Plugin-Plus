package com.tom.cpm.blockbench.proxy;

import com.tom.ugwt.client.JsArrayE;

import elemental2.dom.HTMLCanvasElement;
import elemental2.dom.HTMLElement;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

/** CPM+ (W0B-INFRA): a Blockbench 3D viewport ('Preview.selected' is the active one) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Preview_$$")
public class Preview {
	public static Preview selected;
	public static JsArrayE<Preview> all;

	public String id;
	/** THREE camera / OrbitControls / WebGLRenderer of this viewport */
	public Object camera, controls, renderer;
	public HTMLCanvasElement canvas;
	public HTMLElement node;
	public int width, height;

	/** screenshot of this viewport (gizmos hidden); options {crop, width, height}; cb(dataURL); no cb = BB's screenshot dialog */
	public native void screenshot(Object options, ScreenshotCallback cb);
	public native void render();
	public native void loadAnglePreset(Object preset);

	@JsFunction
	public interface ScreenshotCallback {
		void done(String dataUrl);
	}

	@JsOverlay
	public static Preview getSelected() {
		Object p = Global.getGlobal("Preview");
		if (p == null)return null;
		return selected;
	}

	/** Blockbench's global Screencam (screenshots, GIFs) */
	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Screencam_$$")
	public static class Screencam {
		/** with cb: cb(dataUrl); without: shows Blockbench's screenshot dialog */
		public static native void returnScreenshot(String dataUrl, ScreenshotCallback cb, Object blob);
		public static native void cleanCanvas(Object options, ScreenshotCallback cb);
		public static native void screenshotPreview(Preview preview, Object options, ScreenshotCallback cb);

		@JsOverlay
		public static boolean available() {
			return Global.getGlobal("Screencam") != null;
		}
	}
}