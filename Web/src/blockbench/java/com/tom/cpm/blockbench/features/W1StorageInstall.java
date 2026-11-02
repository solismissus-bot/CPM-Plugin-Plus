package com.tom.cpm.blockbench.features;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.tools.storage.StorageCleaner;
import com.tom.cpm.web.client.LocalStorageFS;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W1-STORAGE (CPM+ v0.8, B6 + E4).
 * <ul>
 * <li>B6 lives in LocalStorageFS (web client): '/mnt/..' downloads are no longer also stored, a full storage gives a
 * clear 'storage full' error instead of a half-written file, a full sessionStorage rejects mount(), and only the
 * 3 newest autosaves of each project are kept.</li>
 * <li>E4: 'CPM+ &gt; Ajuda &gt; Limpar armazenamento do CPM...' ({@link StorageCleaner}), available everywhere
 * (also on the start screen, web and desktop), because a full storage blocks the CPM editor whatever is open.</li>
 * </ul>
 */
public final class W1StorageInstall {
	private W1StorageInstall() {
	}

	public static void install() {
		PlusMenus.add(PlusMenus.SUB_HELP, PlusMenus.action(StorageCleaner.ACTION_ID, "cpmplus.w1storage.menu", "delete_sweep", Cond.ALWAYS, null, StorageCleaner::open));
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	// ====================================================================== test tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	/** {state: 'resolved' | 'rejected', value, message} once the promise settled (never rejects itself) */
	private static Promise<Object> settle(Promise<?> p) {
		return new Promise<>((res, rej) -> {
			p.then(v -> {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("state", "resolved");
				r.set("value", v == null ? null : String.valueOf(v));
				res.onInvoke(r);
				return null;
			}, e -> {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("state", "rejected");
				r.set("message", e instanceof Throwable ? ((Throwable) e).getMessage() : String.valueOf(e));
				r.set("javaException", e instanceof Throwable);
				res.onInvoke(r);
				return null;
			});
		});
	}

	private static native elemental2.dom.File makeFile(String b64, String name) /*-{
		var bin = $wnd.atob(b64);
		var u = new Uint8Array(bin.length);
		for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
		return new $wnd.File([u], name);
	}-*/;

	private static void registerTestTools() {
		// LocalStorageFS of Blockbench's window (the one the plugin installed): setContent / mount / usage
		PlusTestHooks.register("w1storage.fs", o -> {
			LocalStorageFS fs = StorageCleaner.fs();
			String op = str(o, "op", "");
			switch (op) {
			case "setContent":
				return settle(fs.setContent(str(o, "path", ""), str(o, "b64", "")));
			case "mount":
				return settle(fs.mount(makeFile(str(o, "b64", ""), str(o, "name", "qa.cpmproject"))));
			case "usage": {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("enabled", fs.isEnabled());
				r.set("usageBytes", fs.usageBytes());
				JsArray<Object> f = new JsArray<>();
				for (String n : fs.storedPaths())f.push(n);
				r.set("stored", f);
				return r;
			}
			case "stream": {
				// what ModelWriter.finish / ImageIO.write do: java.io.FileOutputStream + close (sync result)
				JsPropertyMap<Object> r = JsPropertyMap.of();
				try (OutputStream out = new FileOutputStream(new File(str(o, "path", "")))) {
					out.write(Base64.getDecoder().decode(str(o, "b64", "")));
					r.set("state", "resolved");
				} catch (Throwable e) {
					r.set("state", "rejected");
					r.set("message", e.getMessage());
					r.set("javaException", e instanceof IOException);
				}
				return r;
			}
			case "mkdirs": {
				// what Editor.tick does before an autosave: File.mkdirs (sync; must never throw)
				JsPropertyMap<Object> r = JsPropertyMap.of();
				try {
					new File(str(o, "path", "")).mkdirs();
					r.set("state", "resolved");
				} catch (Throwable e) {
					r.set("state", "threw");
					r.set("message", String.valueOf(e));
				}
				return r;
			}
			default:
				throw new IllegalArgumentException("unknown op " + op);
			}
		});
		// what the dialog would show (read-only)
		PlusTestHooks.register("w1storage.scan", o -> StorageCleaner.toJs(StorageCleaner.scan(StorageCleaner.fs())));
	}
}
