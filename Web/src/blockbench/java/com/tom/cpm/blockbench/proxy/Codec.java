package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.Blockbench.CallbackEvent;

import elemental2.core.ArrayBuffer;
import elemental2.core.JsObject;
import jsinterop.annotations.JsConstructor;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Codec_$$")
public class Codec {

	@JsConstructor
	public Codec(String id, CodecProperties ctr) {}

	public ModelFormat format;
	public String name, extension;
	public boolean overwrite;

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class CodecProperties {
		public String name, extension;
		public boolean remember;
		public LoadFilter load_filter;
		public CallbackCompile compile;
		public CallbackWrite write;
		public CallbackExport export;
		public CallbackParse parse;
		// CPM+ : Open / drag & drop / Recent support
		public CallbackLoad load;
	}

	@JsFunction
	public interface CallbackLoad {
		void load(Object model, Blockbench.BBFile file, Object args);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class LoadFilter {
		public String type;
		public String[] extensions;
	}

	@JsFunction
	public interface CallbackCompile {
		String compile();
	}

	@JsFunction
	public interface CallbackWrite {
		void write(JsObject content, String path);
	}

	@JsFunction
	public interface CallbackExport {
		void export();
	}

	@JsFunction
	public interface CallbackParse {
		void parse(ArrayBuffer buffer, String path);
	}

	@JsFunction
	public interface CallbackOverwrite {
		void doOverwrite(String path);
	}

	public native void export();
	// CPM+ (W0B): the .bbmodel codec (Codecs.project): compile(options) -> JSON text (or the model with {raw:true});
	// parse(model, path) loads into the open project (fires 'parsed')
	public native Object compile(Object options);
	public native Object compile();
	public native void parse(Object model, String path);
	public native void dispatchEvent(String eventName, JsObject data);
	public native void afterDownload(String path);
	public native void afterSave(String path);
	public native void write(JsObject content, String path);
	public native String fileName();
	public native String startPath();
	public native void overwrite(JsObject content, String path, CallbackOverwrite cb);
	public native void on(String id, CallbackEvent cb);
	public native void removeListener(String id, CallbackEvent cb);
	// CPM+ : Blockbench's own unregister (removes Codecs[id] and the format link)
	public native void delete();
}
