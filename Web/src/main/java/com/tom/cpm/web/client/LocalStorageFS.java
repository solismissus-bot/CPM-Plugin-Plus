package com.tom.cpm.web.client;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.IGui;
import com.tom.cpl.gui.elements.FileChooserPopup;
import com.tom.cpl.gui.elements.FileChooserPopup.FileFilter;
import com.tom.cpl.gui.elements.InputPopup;
import com.tom.cpl.gui.elements.MessagePopup;
import com.tom.cpm.web.client.FS.IFS;
import com.tom.cpm.web.client.java.Java;
import com.tom.cpm.web.client.java.io.FileNotFoundException;
import com.tom.cpm.web.client.render.GuiImpl;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.Global;
import elemental2.core.JsArray;
import elemental2.core.JsObject;
import elemental2.dom.Blob;
import elemental2.dom.DomGlobal;
import elemental2.dom.FileReader;
import elemental2.dom.HTMLInputElement;
import elemental2.dom.HTMLLinkElement;
import elemental2.dom.URL;
import elemental2.dom.Window;
import elemental2.promise.Promise;
import elemental2.webstorage.Storage;
import elemental2.webstorage.WebStorageWindow;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

public class LocalStorageFS implements IFS {
	private boolean enable;
	private Storage local, session;

	public LocalStorageFS(Window window) {
		try {
			local = WebStorageWindow.of(window).localStorage;
			session = WebStorageWindow.of(window).sessionStorage;
			local.setItem("m:/", Global.JSON.stringify(Meta.make(true)));
			enable = true;
		} catch (Throwable e) {
			DomGlobal.console.warn(e);
			enable = false;
		}
		if (session == null)session = new MemorySession();
	}

	public static LocalStorageFS getInstance() {
		return (LocalStorageFS) FS.getImpl();
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "Object")
	public static class Meta {
		public boolean folder;

		@JsOverlay
		public static LocalStorageFS.Meta make(boolean folder) {
			LocalStorageFS.Meta m = new Meta();
			m.folder = folder;
			return m;
		}

		@JsOverlay
		public static LocalStorageFS.Meta parse(String in) {
			return Js.cast(Global.JSON.parse(in));
		}
	}

	public String[] list(String path) {
		if("/mnt".equals(path))return JsObject.keys(session).map((v, __) -> "/mnt/" + v).asArray(new String[0]);
		if(!this.enable) {
			if("/".equals(path))return new String[] {"/mnt"};
			return null;
		}
		var pf = "m:" + path;
		String i = local.getItem(pf);
		if(i != null) {
			if(Meta.parse(i).folder) {
				if(!path.endsWith("/"))pf += "/";
				final String fpf = pf;
				JsArray<String> l = new JsArray<>();
				JsObject.keys(local).forEach((name, __) -> {
					if(name.startsWith(fpf) && name.indexOf("/", fpf.length()) == -1 && name != "m:/") {
						l.push(name.substring(2));
					}
					return null;
				});
				if(pf.equals("m:/")) {
					l.push("/mnt");
				}
				return l.asArray(new String[0]);
			} else
				return null;
		} else
			return null;
	}

	public boolean exists(String path) {
		if("/mnt".equals(path))return true;
		String[] sp = path.split("/");
		if("mnt".equals(sp[1]))return Js.isTruthy(session.getItem(sp[2]));
		if(!this.enable)return false;
		return Js.isTruthy(local.getItem("m:" + path));
	}

	public boolean isDir(String path) {
		if("/mnt".equals(path))return true;
		if(path.startsWith("/mnt"))return false;
		if(!this.enable)return false;
		String i = local.getItem("m:" + path);
		if(i != null)
			return Meta.parse(i).folder;
		return false;
	}

	public void mkdir(String path) {
		if(path.startsWith("/mnt"))return;
		if(!this.enable)return;
		String i = local.getItem("m:" + path);
		if(i == null) {
			// CPM+ (B6): on a full storage the raw QuotaExceededError escaped Editor.tick (autosaves.mkdirs()) as a fatal
			// GUI crash; the file write that follows does not need the folder meta and rejects with the friendly text
			String err = trySetItem(local, "m:" + path, Global.JSON.stringify(Meta.make(true)));
			if(err != null)DomGlobal.console.warn("CPM: could not create the folder " + path + " (" + err + ")");
		}
	}

	@Override
	public String getContentSync(String path) throws FileNotFoundException {
		if(path.startsWith("/mnt/")) {
			String[] sp = path.split("/");
			if(sp[1] == "mnt") {
				String f = session.getItem(sp[2]);
				if(f == null)throw new FileNotFoundException();
				return f;
			}
			throw new FileNotFoundException();
		}
		if(!this.enable)throw new FileNotFoundException("File system not enabled");
		String c = local.getItem("f:" + path);
		if(c == null)throw new FileNotFoundException();
		return c;
	}

	@Override
	public Promise<Void> setContent(String path, String cont) {
		return new Promise<>((res, rej) -> {
			if(path.startsWith("/mnt")) {
				// CPM+ (B6): '/mnt/<name>' is a download. The missing returns let it fall through, so every export of the
				// Embedded Editor also left a full copy under 'm:/mnt/..' / 'f:/mnt/..' in localStorage that nothing
				// could list or delete (they fill the storage; the Clear CPM storage action removes old ones).
				if(path.startsWith("/mnt/")) {
					String[] sp = path.split("/");
					DomGlobal.fetch("data:application/octet-binary;base64," + cont).
					then(v -> v.blob()).then(b -> {
						saveAs(b, sp[sp.length - 1]);
						return null;
					}).catch_(e -> {
						DomGlobal.console.error("CPM: download of " + sp[sp.length - 1] + " failed", e);
						return null;
					});
					res.onInvoke((Void) null);
					return;
				}
				rej.onInvoke(new FileNotFoundException());
				return;
			}
			try {
				writeSync(path, cont);
			} catch (IOException e) {
				rej.onInvoke(e);
				return;
			}
			res.onInvoke((Void) null);
		});
	}

	// ------------------------------------------------------------------ CPM+ (B6 / E4): storage limits and cleanup

	/** autosaves kept per project (same file name after the time stamp); older ones are deleted by each new autosave */
	public static final int AUTOSAVES_KEPT = 3;
	/** extra room (chars) the free-space check asks for, on top of what the write needs (per-item overhead) */
	private static final int SPACE_MARGIN = 4096;
	private static final String SPACE_PROBE_KEY = "f:/.cpmplus-space-check";
	private boolean fullNotified;

	/**
	 * Writes one file of this storage now (localStorage is synchronous), all or nothing: what setContent does for any
	 * path outside '/mnt', but the error is thrown here, so a stream (FileOutputStream.flush, e.g. the Embedded Editor's
	 * 'Export &gt; Model' to '/player_models') reports it instead of showing 'export success' for a file never written.
	 * <p>
	 * Autosaves ('.../autosaves/autosave-YYYYMMDD-HHMMSS-&lt;name&gt;', Editor.tick) keep at most {@value #AUTOSAVES_KEPT}
	 * per project: the older ones beyond {@value #AUTOSAVES_KEPT}-1 are deleted BEFORE the write (a heavy project
	 * could not hold 4 copies at once, so the write failed and nothing was ever pruned). When the storage is still full,
	 * the oldest remaining autosaves of the SAME project are deleted one at a time (newest kept as long as possible)
	 * until the new one fits - but only when a free-space check shows that deleting them is enough, so they are never
	 * thrown away for a write that fails anyway. A crash copy ('recovered-*', Editor.saveRecovered) may also take the
	 * place of that project's autosaves. Never deletes a 'recovered-*' file or another project's files.
	 * @throws FileNotFoundException storage disabled, a '/mnt' path, or a folder with that name
	 * @throws IOException storage full (the 'CPM storage full' text) or another storage error
	 */
	public void writeSync(String path, String cont) throws IOException {
		if(path.startsWith("/mnt"))throw new FileNotFoundException("Not a stored path: " + path);
		if(!this.enable)throw new FileNotFoundException("File system not enabled");
		String i = local.getItem("m:" + path);
		if(i != null && isFolderMeta(i))throw new FileNotFoundException("Directory exists with the same name");
		String auto = autosaveSuffix(path), rec = auto == null ? recoveredSuffix(path) : null;
		// older autosaves of the same project, newest first (the one being written is never in it, even on clock skew)
		List<String> older = new ArrayList<>();
		try {
			if(auto != null || rec != null)older = sameProjectAutosaves(path, auto != null ? auto : rec);
			if(auto != null) {
				while(older.size() > AUTOSAVES_KEPT - 1)deleteStoredFile(older.remove(older.size() - 1));
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM: could not remove old autosaves", e);
		}
		// CPM+ (B6): a full localStorage made setItem throw half way (meta written, content not); now the write
		// is all or nothing and the caller gets a clear 'storage full' error instead of a bare QuotaExceededError
		String err = put(path, cont);
		try {
			if("quota".equals(err) && !older.isEmpty() && fitsAfterFreeing(path, cont, older)) {
				while("quota".equals(err) && !older.isEmpty()) {
					deleteStoredFile(older.remove(older.size() - 1));
					err = put(path, cont);
				}
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM: could not make room for " + path, e);
		}
		if(err != null)throw storageError(err);
	}

	/** one all-or-nothing write: a meta written for it is rolled back when the content does not fit */
	private String put(String path, String cont) {
		String err = null;
		boolean newMeta = false;
		if(local.getItem("m:" + path) == null) {
			err = trySetItem(local, "m:" + path, Global.JSON.stringify(Meta.make(false)));
			newMeta = err == null;
		}
		if(err == null)err = trySetItem(local, "f:" + path, cont);
		if(err != null && newMeta) {
			try {
				local.removeItem("m:" + path);
			} catch (Throwable e) {
			}
		}
		return err;
	}

	/** stored autosaves ('autosave-*' only) of the project 'suffix' in the folder of path, path itself excluded; newest first */
	private List<String> sameProjectAutosaves(String path, String suffix) {
		String dir = path.substring(0, path.lastIndexOf('/') + 1);
		List<String> same = new ArrayList<>();
		for (String p : storedPaths()) {
			if(!p.equals(path) && p.startsWith(dir) && p.indexOf('/', dir.length()) < 0 && suffix.equals(autosaveSuffix(p)))same.add(p);
		}
		// same prefix and suffix, so the time stamp sorts as text
		same.sort(Comparator.reverseOrder());
		return same;
	}

	/**
	 * After a 'quota' failure: true when deleting all of 'older' frees enough room for the write. Needed - freed (+ a
	 * margin) is tried as a temporary probe key (removed at once); its own 'quota' failure means deleting them would not help.
	 */
	private boolean fitsAfterFreeing(String path, String cont, List<String> older) {
		double freed = 0;
		for (String p : older)freed += storedBytes(p) / 2;
		String f = local.getItem("f:" + path);
		double needed = ("f:" + path).length() + cont.length() - (f != null ? ("f:" + path).length() + f.length() : 0);
		if(local.getItem("m:" + path) == null)needed += ("m:" + path).length() + Global.JSON.stringify(Meta.make(false)).length();
		double probe = needed - freed;
		if(probe <= 0)return true;
		return probeFits(local, SPACE_PROBE_KEY, (int) Math.max(0, probe + SPACE_MARGIN - SPACE_PROBE_KEY.length()));
	}

	/** true when 'len' more chars fit in the storage now (written under key and removed again) */
	private static native boolean probeFits(Storage s, String key, int len) /*-{
		var ok = false;
		try {
			s.setItem(key, len > 0 ? '0'.repeat(len) : '');
			ok = true;
		} catch (e) {
			ok = false;
		}
		try {
			s.removeItem(key);
		} catch (e) {
		}
		return ok;
	}-*/;

	/** storage.setItem without throwing: null when written, 'quota' when the storage is full, else the error text */
	private static native String trySetItem(Storage s, String key, String value) /*-{
		try {
			s.setItem(key, value);
			return null;
		} catch (e) {
			var n = e && e.name ? String(e.name) : '';
			var c = e && typeof e.code === 'number' ? e.code : 0;
			if (n === 'QuotaExceededError' || n === 'NS_ERROR_DOM_QUOTA_REACHED' || c === 22 || c === 1014) return 'quota';
			return (n ? n + ': ' : '') + (e && e.message ? e.message : String(e));
		}
	}-*/;

	private IOException storageError(String err) {
		if("quota".equals(err)) {
			long kb = (long) Math.ceil(usageBytes() / 1024);
			String text = msg("cpmplus.w1storage.storageFull", "CPM storage full (%s KB). In Blockbench, use CPM+ > Help > Clear CPM storage.", kb);
			notifyFull(text);
			return new IOException(text);
		}
		return new IOException(msg("cpmplus.w1storage.storageError", "Could not write to the CPM storage: %s", err));
	}

	/**
	 * The first 'storage full' of this window also opens a popup in the CPM editor GUI (when this window has one, i.e.
	 * the Embedded Editor): a failed autosave is otherwise only a 3 second tooltip that is easy to miss. Blockbench's
	 * own window has no CPM GUI, so nothing opens there.
	 */
	private void notifyFull(String text) {
		if(fullNotified)return;
		try {
			WebMC mc = WebMC.getInstance();
			GuiImpl gui = mc != null ? mc.getGui() : null;
			if(gui == null || gui.getFrame() == null)return;
			fullNotified = true;
			String title = msg("cpmplus.w1storage.storageFull.title", "Could not save");
			mc.executeOnGameThread(() -> {
				try {
					Frame frm = gui.getFrame();
					if(frm != null)frm.openPopup(new MessagePopup(frm, title, text));
				} catch (Throwable e) {
					DomGlobal.console.warn("CPM: could not show the storage full message", e);
				}
			});
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM: could not show the storage full message", e);
		}
	}

	/**
	 * Translated text with an English fallback: the CPM+ lang fragments are part of the plugin script (also inside the
	 * Embedded Editor window), the standalone web editor does not have them.
	 */
	private static String msg(String key, String fallback, Object... args) {
		String f = fallback;
		try {
			String t = I18n.get(key);
			if(t != null && !t.equals(key))f = t;
		} catch (Throwable e) {
		}
		try {
			return String.format(f, args);
		} catch (Throwable e) {
			return f;
		}
	}

	private static boolean isFolderMeta(String meta) {
		try {
			return Meta.parse(meta).folder;
		} catch (Throwable e) {
			return false;
		}
	}

	/** true for the keys of this file system: 'm:' + path (meta) and 'f:' + path (base64 content) */
	public static boolean isFsKey(String key) {
		return key != null && (key.startsWith("m:") || key.startsWith("f:"));
	}

	/**
	 * Project name of an autosave written by Editor.tick ('&lt;dir&gt;/autosaves/autosave-YYYYMMDD-HHMMSS-&lt;name&gt;',
	 * i.e. '^.*&#47;autosaves/autosave-\d{8}-\d{6}-(.+)$'), or null for any other path (also 'recovered-*').
	 */
	public static String autosaveSuffix(String path) {
		return stampedSuffix(path, "autosave-");
	}

	/** project name of a crash copy written by Editor.saveRecovered ('.../autosaves/recovered-YYYYMMDD-HHMMSS-&lt;name&gt;'), else null */
	public static String recoveredSuffix(String path) {
		return stampedSuffix(path, "recovered-");
	}

	/** 'YYYYMMDDHHMMSS' of an autosave / recovered file name, or null */
	public static String stamp(String path) {
		String name = fileName(path);
		for (String pre : new String[] {"autosave-", "recovered-"}) {
			if(stampedSuffix(path, pre) != null) {
				int p = pre.length();
				return name.substring(p, p + 8) + name.substring(p + 9, p + 15);
			}
		}
		return null;
	}

	private static String fileName(String path) {
		int s = path.lastIndexOf('/');
		return s >= 0 ? path.substring(s + 1) : path;
	}

	private static String stampedSuffix(String path, String prefix) {
		if(path == null)return null;
		int s = path.lastIndexOf('/');
		if(s < 0 || !path.substring(0, s).endsWith("/autosaves"))return null;
		String name = path.substring(s + 1);
		int p = prefix.length();
		if(!name.startsWith(prefix) || name.length() < p + 17)return null;
		for (int i = 0; i < 15; i++) {
			char c = name.charAt(p + i);
			if(i == 8) {
				if(c != '-')return null;
			} else if(c < '0' || c > '9')return null;
		}
		if(name.charAt(p + 15) != '-')return null;
		return name.substring(p + 16);
	}

	/** true when localStorage works here (otherwise nothing is stored) */
	public boolean isEnabled() {
		return enable;
	}

	/** every localStorage key (snapshot, safe to modify the storage while iterating the result) */
	private List<String> localKeys() {
		List<String> keys = new ArrayList<>();
		if(!enable)return keys;
		for (int n = 0; n < local.getLength(); n++) {
			String k = local.key(n);
			if(k != null)keys.add(k);
		}
		return keys;
	}

	/** paths of every stored file ('f:' content or a non-folder 'm:' meta; also leftover '/mnt/..' copies) */
	public List<String> storedPaths() {
		List<String> out = new ArrayList<>();
		if(!enable)return out;
		Set<String> seen = new HashSet<>();
		for (String k : localKeys()) {
			if(!isFsKey(k) || "m:/".equals(k))continue;
			String p = k.substring(2);
			if(k.startsWith("m:")) {
				String m = local.getItem(k);
				if(m == null || isFolderMeta(m))continue;
			}
			if(seen.add(p))out.add(p);
		}
		return out;
	}

	/** bytes one stored file takes: ('m:' + path + meta, 'f:' + path + content) lengths * 2 (UTF-16) */
	public double storedBytes(String path) {
		if(!enable || path == null)return 0;
		double b = 0;
		for (String k : new String[] {"m:" + path, "f:" + path}) {
			String v = local.getItem(k);
			if(v != null)b += (k.length() + v.length()) * 2.0;
		}
		return b;
	}

	/** bytes the CPM editor files take in localStorage: (key + value length) * 2 over every 'm:' / 'f:' key */
	public double usageBytes() {
		double sum = 0;
		for (String k : localKeys()) {
			if(!isFsKey(k))continue;
			String v = local.getItem(k);
			sum += (k.length() + (v != null ? v.length() : 0)) * 2.0;
		}
		return sum;
	}

	/** bytes of every localStorage key of this site (Blockbench, CPM and anything else; information only) */
	public double totalLocalBytes() {
		double sum = 0;
		for (String k : localKeys()) {
			String v = local.getItem(k);
			sum += (k.length() + (v != null ? v.length() : 0)) * 2.0;
		}
		return sum;
	}

	/**
	 * Deletes one stored FILE: {@link #deleteFile} plus the keys it skips (an 'f:' without its 'm:' left by a failed
	 * write, or a leftover '/mnt/..' copy). Never a folder, never any key outside 'm:' / 'f:'.
	 * @return true when something was removed
	 */
	public boolean deleteStoredFile(String path) {
		if(!enable || path == null || path.isEmpty() || "/".equals(path))return false;
		String m = local.getItem("m:" + path);
		if(m != null && isFolderMeta(m))return false;
		boolean had = m != null || local.getItem("f:" + path) != null;
		try {
			deleteFile(path);
		} catch (Throwable e) {
			// unreadable meta: the two keys below are removed anyway
		}
		local.removeItem("m:" + path);
		local.removeItem("f:" + path);
		return had;
	}

	/**
	 * Deletes every CPM editor file and folder: every 'm:' / 'f:' key except the root 'm:/' (kept / re-created). Never
	 * touches any other key (Blockbench settings, CPM config, CPM+ prefs) and never sessionStorage: Blockbench's window
	 * mounts nothing there (the Embedded Editor is its own window with its own sessionStorage), so every sessionStorage
	 * key of Blockbench's window belongs to someone else (other plugins share this origin).
	 * @return number of keys removed
	 */
	public int clearAll() {
		int n = 0;
		if(enable) {
			for (String k : localKeys()) {
				if(isFsKey(k) && !"m:/".equals(k)) {
					local.removeItem(k);
					n++;
				}
			}
			if(local.getItem("m:/") == null)trySetItem(local, "m:/", Global.JSON.stringify(Meta.make(true)));
		}
		return n;
	}

	public void deleteFile(String path) {
		if(path.startsWith("/mnt"))return;
		if(!this.enable)return;
		String i = local.getItem("m:" + path);
		if(i != null) {
			if(Meta.parse(i).folder) {
				for(String c : this.list(path)) {
					this.deleteFile(c);
				}
				local.removeItem("m:" + path);
			} else {
				local.removeItem("m:" + path);
				local.removeItem("f:" + path);
			}
		}
	}

	@Override
	public Promise<File> mount(elemental2.dom.File file) {
		return new Promise<>((res, rej) -> {
			FileReader reader = new FileReader();
			reader.readAsDataURL(file);
			reader.onload = __ -> {
				String b64 = reader.result.asString().substring(reader.result.asString().indexOf(',') + 1);
				// CPM+ (B6): a full sessionStorage threw out of onload, so the promise never settled (the editor waited
				// forever); now it rejects with a message
				try {
					mount(b64, file.name);
				} catch (Throwable e) {
					long kb = (long) Math.ceil(b64.length() * 2 / 1024.0);
					rej.onInvoke(new IOException(msg("cpmplus.w1storage.mountFailed", "Could not open \"%s\" in the CPM editor: the browser's temporary storage is full (file of %s KB).", file.name, kb)));
					return null;
				}
				res.onInvoke(new File("/mnt/" + file.name));
				return null;
			};
			reader.onerror = err -> {
				rej.onInvoke(err);
				return null;
			};
		});
	}

	@Override
	public boolean needFileManager() {
		return true;
	}

	@Override
	public void mount(String b64, String name) {
		session.setItem(name, b64);
	}

	public static void saveAs(Blob blob, String name) {
		HTMLLinkElementEx a = Js.uncheckedCast(DomGlobal.document.createElement("a"));
		String url = URL.createObjectURL(blob);
		a.href = url;
		a.download = name;
		DomGlobal.document.body.appendChild(a);
		a.click();
		DomGlobal.setTimeout(__ -> {
			DomGlobal.document.body.removeChild(a);
			URL.revokeObjectURL(url);
		}, 1);
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	private static class HTMLLinkElementEx extends HTMLLinkElement {
		public String download;
	}

	private class FileImpl implements IFile {
		private String[] path;

		public FileImpl(FileImpl file, String name) {
			List<String> l = new ArrayList<>();
			l.addAll(Arrays.asList(file.path));
			l.addAll(Arrays.asList(name.split("/")));
			l.removeIf(f -> f.isEmpty() || f.equals("."));
			path = l.toArray(new String[0]);
		}

		public FileImpl(String name) {
			if(name.startsWith("/"))name = name.substring(1);
			path = name.split("/");
		}

		private FileImpl(String[] path) {
			this.path = path;
		}

		@Override
		public String getName() {
			return path.length == 0 ? "" : path[path.length - 1];
		}

		@Override
		public boolean exists() {
			return LocalStorageFS.this.exists(getAbsolutePath());
		}

		@Override
		public FileImpl getParentFile() {
			return path.length > 1 ? new FileImpl(Arrays.copyOf(path, path.length - 1)) : new FileImpl("/");
		}

		@Override
		public void mkdirs() {
			if(path.length < 2)return;
			for (int i = 1; i <= path.length; i++) {
				LocalStorageFS.this.mkdir("/" + Arrays.stream(path, 0, i).collect(Collectors.joining("/")));
			}
		}

		@Override
		public boolean isDirectory() {
			return LocalStorageFS.this.isDir(getAbsolutePath());
		}

		@Override
		public String getAbsolutePath() {
			List<String> l = new ArrayList<>();
			for(String pe : path) {
				if(pe.equals("..")) {
					if(!l.isEmpty())
						l.remove(l.size() - 1);
				} else {
					l.add(pe);
				}
			}
			return "/" + l.stream().collect(Collectors.joining("/"));
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + Arrays.hashCode(path);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			FileImpl other = (FileImpl) obj;
			if (!Arrays.equals(path, other.path)) return false;
			return true;
		}

		@Override
		public boolean isHidden() {
			return false;
		}

		@Override
		public String getCanonicalPath() throws IOException {
			return getAbsolutePath();
		}

		@Override
		public boolean isFile() {
			return exists() && !isDirectory();
		}

		@Override
		public String[] list() {
			return LocalStorageFS.this.list(getAbsolutePath());
		}

		@Override
		public boolean isRoot() {
			return getAbsolutePath().equals("/");
		}
	}

	@Override
	public IFile getImpl(IFile parent, String path) {
		if(parent != null)
			return new FileImpl((FileImpl) parent, path);
		else
			return new FileImpl(path);
	}

	@Override
	public String getWorkDir() {
		return "/";
	}

	@Override
	public CompletableFuture<File> openFileChooser(FileChooserPopup fc) {
		CompletableFuture<File> f = new CompletableFuture<>();
		if(fc.isSaveDialog()) {
			IGui gui = fc.getGui();
			fc.getFrame().openPopup(new InputPopup(fc.getFrame(), gui.i18nFormat("web-label.fileName"), gui.i18nFormat("web-label.enterFileName"), v -> f.complete(new File("/mnt/" + v)), null));
		} else {
			HTMLInputElement input = Js.uncheckedCast(RenderSystem.getDocument().createElement("input"));
			input.style.display = "none";
			input.type = "file";
			if(fc.getFilter() instanceof FileFilter) {
				FileFilter ff = (FileFilter) fc.getFilter();
				if(ff.getExt() != null) {
					input.accept = "." + ff.getExt();
				}
			}
			RenderSystem.getDocument().body.appendChild(input);
			input.onchange = e -> {
				RenderSystem.withContext(() -> {
					if(input.files.getLength() > 0) {
						Java.promiseToCf(FS.mount(input.files.getAt(0)), f);
					} else {
						f.complete(null);
					}
				});
				return null;
			};
			input.click();
		}
		return f;
	}

	private static class MemorySession implements Storage {
		List<String> items = new ArrayList<>();
		Map<String, String> content = new HashMap<>();

		@Override
		public void clear() {
			items.clear();
			content.clear();
		}

		@Override
		public String getItem(String key) {
			return content.get(key);
		}

		@Override
		public int getLength() {
			return items.size();
		}

		@Override
		public String key(int index) {
			return items.get(index);
		}

		@Override
		public void removeItem(String key) {
			content.remove(key);
			items.remove(key);
		}

		@Override
		public void setItem(String key, String data) {
			if (!items.contains(key))
				items.add(key);
			content.put(key, data);
		}
	}
}