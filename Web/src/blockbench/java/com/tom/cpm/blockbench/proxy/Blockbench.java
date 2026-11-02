package com.tom.cpm.blockbench.proxy;

import elemental2.core.ArrayBuffer;
import elemental2.core.JsObject;
import elemental2.dom.Blob;
import elemental2.dom.DomGlobal;
import jsinterop.annotations.JsFunction;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

/**
 * Blockbench's global 'Blockbench' object.
 *
 * CPM+ (W0B-INFRA): natives for messages, toasts, status bar, progress, message boxes and the directory picker.
 * Some of them are missing or are stubs on some platforms (pickDirectory only warns in the web app): call them
 * through the guarded wrappers in util/PlusDialogs, or check {@link #has(String)} first.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Blockbench_$$")
public class Blockbench {

	public static String version;
	// CPM+ : platform flags (BB 5)
	public static boolean isWeb, isMobile, isTouch;

	@JsMethod(name = "import")
	public static native void import_(ImportProperties pr, CallbackImport cb);

	public static native void export(ExportProperties pr, CallbackExport cb);
	// CPM+ : Blockbench.read(paths, options, callback): Blockbench's own (desktop) file reader
	public static native void read(String[] paths, ReadProperties pr, CallbackImport cb);

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class ReadProperties {
		public String readtype;
		public boolean errorbox;
	}
	public static native void writeFile(String path, WriteProperties pr, CallbackExport cb);

	public static native void on(String id, CallbackEvent cb);
	public static native void removeListener(String id, CallbackEvent cb);
	// CPM+ (W0B)
	public static native void dispatchEvent(String id, Object data);

	// ------------------------------------------------------------------ CPM+ (W0B-INFRA) user feedback natives

	/** small text in the middle of the screen for ms milliseconds (text only, no HTML) */
	public static native void showQuickMessage(String message, int ms);

	/** toast at the bottom (text only, no HTML); returns the toast (delete() removes it) */
	public static native Toast showToastNotification(ToastOptions options);

	/** text of the status bar (file name slot); no argument restores the default */
	public static native void setStatusBarText(String text);
	public static native void setStatusBarText();

	/** progress bar 0..1 (0 hides it) */
	public static native void setProgress(double progress);
	public static native void setProgress(double progress, double time, String bar);

	/** desktop app only (the web app only logs a warning and returns undefined) */
	public static native String pickDirectory(PickDirOptions options);

	/** message box (its 'message' is rendered as HTML / markdown: escape user text) */
	public static native void showMessageBox(MessageBoxOptions options, MessageBoxCallback cb);

	public static native void notification(String title, String text);
	public static native void addFlag(String flag);
	public static native void removeFlag(String flag);
	public static native Object getIconNode(String icon, String color);
	public static native void openLink(String url);

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class ToastOptions {
		public String id, text, icon, color;
		public int expire;
		public ToastClick click;
		public ToastClose onClose;
	}

	@JsFunction
	public interface ToastClick {
		/** @return true to remove the toast */
		boolean onClick(Object event);
	}

	@JsFunction
	public interface ToastClose {
		boolean onClose(Object event);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class Toast {
		public native void delete();
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class PickDirOptions {
		public String title, resource_id, startpath;
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class MessageBoxOptions {
		public String title, message, icon, translateKey;
		public String[] buttons;
		public int confirm, cancel, width;
		public boolean cancel_on_click_outside;
	}

	@JsFunction
	public interface MessageBoxCallback {
		void onButton(int button);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class ImportProperties {
		public String[] extensions;
		public String type, readtype, resource_id;
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class ExportProperties {
		public String[] extensions;
		public String type, name, resource_id, startpath;

		@JsProperty(name = "content")
		public String textContent;

		@JsProperty(name = "content")
		public Blob binaryContent;

		public CallbackWriter custom_writer;
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class WriteProperties {
		public JsObject content;
		public String savetype;
	}

	@JsFunction
	public interface CallbackImport {
		void doImport(BBFile[] files);
	}

	@JsFunction
	public interface CallbackExport {
		void doExport(String path);
	}

	@JsFunction
	public interface CallbackWriter {
		void doWrite(JsObject content, String path);
	}

	@JsFunction
	public interface CallbackEvent {
		void onEvent(Object data);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class BBFile {
		public String name, path;

		@JsProperty(name = "content")
		public String textContent;

		@JsProperty(name = "content")
		public ArrayBuffer binaryContent;
	}

	@JsOverlay
	public static void focus() {
		DomGlobal.window.focus();
	}

	public static native boolean hasFlag(String flag);

	/** CPM+ (W0B): true when Blockbench.&lt;name&gt; exists and is a function (optional / platform-specific APIs) */
	@JsOverlay
	public static boolean has(String name) {
		return Global.hasFunction(Global.getBlockbench(), name);
	}

	/** CPM+ (W0B): Blockbench.isWeb with a fallback for builds without the flag */
	@JsOverlay
	public static boolean isWebApp() {
		Object v = Js.asPropertyMap(Global.getBlockbench()).get("isWeb");
		if (v != null && "boolean".equals(Js.typeof(v)))return Js.isTruthy(v);
		return !Global.isApp();
	}
}
