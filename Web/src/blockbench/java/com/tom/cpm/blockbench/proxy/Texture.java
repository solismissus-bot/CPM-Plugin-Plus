package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.three.ThreeMaterial;
import com.tom.ugwt.client.JsArrayE;

import elemental2.dom.CanvasRenderingContext2D;
import elemental2.dom.HTMLCanvasElement;
import elemental2.dom.HTMLImageElement;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Texture_$$")
public class Texture {
	public static JsArrayE<Texture> all;
	public String name, source, uuid;
	public int uv_width, uv_height;
	// CPM+ : image size in pixels (pre-export validation)
	public int width, height;

	// ------------------------------------------------------------------
	// CPM+ (W0B-INFRA): pixel editing (Blockbench 5)
	// ------------------------------------------------------------------
	/** the editable canvas and its 2D context (BB 5 keeps them on every texture) */
	public HTMLCanvasElement canvas;
	public CanvasRenderingContext2D ctx;
	public HTMLImageElement img;
	/** texture layers (only when layers_enabled) and the selected one */
	public JsArrayE<Object> layers;
	public Object selected_layer;
	public boolean layers_enabled, saved, internal;
	public String mode, path;
	/** number of animation frames of a flipbook texture (getter) */
	@JsProperty(name = "frameCount")
	public int frameCount;
	/** registered texture properties */
	public static jsinterop.base.JsPropertyMap<Property> properties;

	public Texture() {}
	public Texture(TextureProperties ctr) {}

	public native Texture fromDataURL(String url);
	public native Texture fromPath(String path);// CPM+
	public native void add(boolean undo);

	public native ThreeMaterial getMaterial();

	// CPM+ (W0B)
	/** Blockbench's paint helper: cb(canvas) draws into the texture, options {no_undo, edit_name, use_cache} */
	public native void edit(EditCallback cb, Object options);
	/** after drawing into canvas/ctx: refreshes material, source and layers */
	public native void updateChangesAfterEdit();
	/** replaces the image source (data URL) */
	public native Texture updateSource(String dataUrl);
	public native String getDataURL();
	public native String getBase64();
	public native Texture select();
	public native int getUVWidth();
	public native int getUVHeight();
	public native void updateLayerChanges(boolean updateHistory);
	public native void convertToInternal(String dataUrl);
	public native Object getUndoCopy(boolean bitmap);

	/** CPM+ (W0B): frame count (BB 5 'frameCount' getter; the brief's 'frame_count') */
	@JsOverlay
	public final int getFrameCount() {
		return frameCount;
	}

	@JsFunction
	public interface EditCallback {
		/** draws into the canvas; return it (or a new canvas) */
		Object edit(HTMLCanvasElement canvas);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class TextureProperties {
		public String mode, name;
	}
}
