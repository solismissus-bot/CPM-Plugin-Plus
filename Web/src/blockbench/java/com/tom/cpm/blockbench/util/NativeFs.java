package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Global;

import elemental2.core.ArrayBuffer;
import elemental2.core.Int8Array;
import elemental2.core.JsArray;
import elemental2.core.Uint8Array;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W3-MODELEXPORT, G23 infra): Node's file system in the INSTALLED Blockbench, for the few writes that must be
 * atomic and verified (player_models). Blockbench 5 removed window.require: a plugin's code runs as
 * {@code new Function('requireNativeModule', 'require', code)}, so the GWT bundle can reach
 * {@code requireNativeModule} lexically (desktop only; undefined on the website). requireNativeModule('fs', {scope})
 * asks the user for that folder once and returns an fs limited to it.
 *
 * <p>Never used in test mode (PlusTestHooks.fsRoot() set): callers then go through PlusDialogs.writeFile, which the QA
 * harness records inside its scratch folder. Every method is defensive: a missing API or a refused permission is
 * {@code null} / {@code false} / an error text, never an exception.</p>
 */
public final class NativeFs {
	private NativeFs() {
	}

	/** true in the installed app when Node's fs can be requested (never in test mode) */
	public static boolean available() {
		return PlusTestHooks.fsRoot() == null && Global.isApp() && hasRequire();
	}

	private static native boolean hasRequire() /*-{
		try {
			return typeof requireNativeModule === 'function';
		} catch (e) {
			return false;
		}
	}-*/;

	/**
	 * Node fs scoped to a folder (Blockbench asks the user once per scope); null when unavailable or refused.
	 * @param message shown by Blockbench in its permission prompt
	 */
	public static Object fs(String scope, String message) {
		if (!available())return null;
		return fs0(scope, message);
	}

	private static native Object fs0(String scope, String message) /*-{
		try {
			if (typeof requireNativeModule !== 'function') return null;
			var opts = {};
			if (scope) opts.scope = scope;
			if (message) opts.message = message;
			var f = requireNativeModule('fs', opts);
			return f || null;
		} catch (e) {
			return null;
		}
	}-*/;

	/** environment variable (APPDATA, USERPROFILE, HOME) of the desktop app; null when unknown */
	public static native String env(String name) /*-{
		try {
			if (typeof process !== 'undefined' && process && process.env && process.env[name]) return '' + process.env[name];
		} catch (e) {
		}
		try {
			if (typeof requireNativeModule === 'function') {
				var p = requireNativeModule('process');
				if (p && p.env && p.env[name]) return '' + p.env[name];
			}
		} catch (e) {
		}
		return null;
	}-*/;

	/** user's home folder (os.homedir) or USERPROFILE / HOME; null when unknown */
	public static String home() {
		String h = env("USERPROFILE");
		if (h == null)h = env("HOME");
		if (h == null)h = homedir0();
		return h;
	}

	private static native String homedir0() /*-{
		try {
			if (typeof requireNativeModule === 'function') {
				var os = requireNativeModule('os');
				if (os && typeof os.homedir === 'function') return '' + os.homedir();
			}
		} catch (e) {
		}
		return null;
	}-*/;

	public static native boolean exists(Object fs, String path) /*-{
		try {
			return !!fs && fs.existsSync(path);
		} catch (e) {
			return false;
		}
	}-*/;

	public static native boolean isDir(Object fs, String path) /*-{
		try {
			return !!fs && fs.existsSync(path) && fs.statSync(path).isDirectory();
		} catch (e) {
			return false;
		}
	}-*/;

	/** names inside a folder ([] when missing / refused) */
	public static List<String> list(Object fs, String path) {
		List<String> out = new ArrayList<>();
		JsArray<String> a = list0(fs, path);
		if (a != null)for (int i = 0; i < a.length; i++)out.add(a.getAt(i));
		return out;
	}

	private static native JsArray<String> list0(Object fs, String path) /*-{
		try {
			if (!fs) return null;
			var r = fs.readdirSync(path);
			var o = [];
			for (var i = 0; i < r.length; i++) o.push('' + r[i]);
			return o;
		} catch (e) {
			return null;
		}
	}-*/;

	public static native boolean mkdirs(Object fs, String path) /*-{
		try {
			if (!fs) return false;
			if (!fs.existsSync(path)) fs.mkdirSync(path, {recursive: true});
			return true;
		} catch (e) {
			return false;
		}
	}-*/;

	/** file bytes, null when missing / unreadable */
	public static byte[] read(Object fs, String path) {
		ArrayBuffer ab = read0(fs, path);
		return ab != null ? toBytes(ab) : null;
	}

	private static native ArrayBuffer read0(Object fs, String path) /*-{
		try {
			if (!fs || !fs.existsSync(path)) return null;
			var b = fs.readFileSync(path);
			return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
		} catch (e) {
			return null;
		}
	}-*/;

	/** writes the bytes; null on success, else the error ('CODE: message') */
	public static String writeSync(Object fs, String path, byte[] bytes) {
		return write0(fs, path, toUint8(bytes));
	}

	private static native String write0(Object fs, String path, Uint8Array data) /*-{
		try {
			if (!fs) return 'NOFS: file system not available';
			fs.writeFileSync(path, data);
			return null;
		} catch (e) {
			return (e && e.code ? e.code + ': ' : '') + (e && e.message ? e.message : ('' + e));
		}
	}-*/;

	private static native String rename0(Object fs, String from, String to) /*-{
		try {
			fs.renameSync(from, to);
			return null;
		} catch (e) {
			return (e && e.code ? e.code + ': ' : '') + (e && e.message ? e.message : ('' + e));
		}
	}-*/;

	private static native void unlink0(Object fs, String path) /*-{
		try {
			if (fs && fs.existsSync(path)) fs.unlinkSync(path);
		} catch (e) {
		}
	}-*/;

	/**
	 * Atomic write: '&lt;path&gt;.tmp' first, then rename over the target; a Windows lock (EPERM / EBUSY, e.g. the game's
	 * model list has the file open) is retried 3 times 100 ms apart. Resolves with the path, rejects with the real
	 * error text (the .tmp file is removed).
	 */
	public static Promise<String> writeAtomic(Object fs, String path, byte[] bytes) {
		return new Promise<>((res, rej) -> {
			String tmp = path + ".tmp";
			String err = writeSync(fs, tmp, bytes);
			if (err != null) {
				unlink0(fs, tmp);
				rej.onInvoke(err);
				return;
			}
			tryRename(fs, tmp, path, 0, res, rej);
		});
	}

	private static void tryRename(Object fs, String tmp, String path, int attempt, Promise.PromiseExecutorCallbackFn.ResolveCallbackFn<String> res,
			Promise.PromiseExecutorCallbackFn.RejectCallbackFn rej) {
		String err = rename0(fs, tmp, path);
		if (err == null) {
			res.onInvoke(path);
			return;
		}
		boolean locked = err.startsWith("EPERM") || err.startsWith("EBUSY") || err.startsWith("EACCES");
		if (locked && attempt < 3) {
			DomGlobal.setTimeout(a -> tryRename(fs, tmp, path, attempt + 1, res, rej), 100);
			return;
		}
		unlink0(fs, tmp);
		rej.onInvoke(err);
	}

	/** folder + name with the separator the folder already uses */
	public static String join(String dir, String name) {
		if (dir == null || dir.isEmpty())return name;
		String sep = dir.indexOf('\\') >= 0 ? "\\" : "/";
		if (dir.endsWith("/") || dir.endsWith("\\"))return dir + name;
		return dir + sep + name;
	}

	/** last path component */
	public static String fileName(String path) {
		if (path == null)return "";
		int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return i >= 0 ? path.substring(i + 1) : path;
	}

	// ------------------------------------------------------------------ byte helpers

	public static Uint8Array toUint8(byte[] b) {
		Uint8Array u = new Uint8Array(b.length);
		for (int i = 0; i < b.length; i++)u.setAt(i, (double) (b[i] & 0xff));
		return u;
	}

	public static ArrayBuffer toArrayBuffer(byte[] b) {
		return toUint8(b).buffer;
	}

	public static byte[] toBytes(ArrayBuffer ab) {
		Int8Array a = new Int8Array(ab);
		byte[] b = new byte[a.length];
		for (int i = 0; i < b.length; i++)b[i] = (byte) (double) a.getAt(i);
		return b;
	}

	/** Blob of the bytes (for Blockbench.export) */
	public static native elemental2.dom.Blob toBlob(Uint8Array data, String type) /*-{
		return new Blob([data], {type: type || 'application/octet-stream'});
	}-*/;

	public static elemental2.dom.Blob blob(byte[] b, String type) {
		return toBlob(toUint8(b), type);
	}

	/** JS value of a typeof check: Js helper kept here so callers need no JSNI */
	static boolean isFunction(Object o) {
		return o != null && "function".equals(Js.typeof(o));
	}
}
