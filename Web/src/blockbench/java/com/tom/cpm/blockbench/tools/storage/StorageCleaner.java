package com.tom.cpm.blockbench.tools.storage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.web.client.FS;
import com.tom.cpm.web.client.LocalStorageFS;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W1-STORAGE, B6 + E4): 'CPM+ &gt; Ajuda &gt; Limpar armazenamento do CPM...'.
 *
 * The plugin and its Embedded Editor keep the CPM editor's files inside Blockbench's own browser storage
 * (LocalStorageFS on Blockbench's window: 'm:' + path = meta, 'f:' + path = base64 content; the editor's
 * autosaves go to '/player_models/autosaves'). When that storage is full the editor can no longer save. This
 * dialog shows what is stored (usage = (key + value length) * 2 over the 'm:' / 'f:' keys, autosaves grouped by
 * project with dates) and deletes, on request only:
 * <ul>
 * <li>Autosaves (default on): the 'autosave-*' files only (never 'recovered-*' crash copies);</li>
 * <li>Leftover export copies (default on, shown only when present): '/mnt/..' copies the old setContent bug left
 * behind; no CPM screen can list, open or delete them;</li>
 * <li>Every CPM editor file (default off, behind a second confirmation): every 'm:' / 'f:' key except the root
 * 'm:/'.</li>
 * </ul>
 * Deleting goes through LocalStorageFS (deleteFile / deleteStoredFile / clearAll), which never touches a key
 * outside 'm:' / 'f:' (Blockbench settings, the CPM config and the CPM+ prefs stay) nor sessionStorage. Files the
 * CPM editor opens ("mounts", sessionStorage) are never part of this: the Embedded Editor is a separate window with
 * its own sessionStorage, and Blockbench's window mounts nothing, so every sessionStorage key here belongs to
 * Blockbench or another plugin. Nothing in the Blockbench project changes (no undo entry). Whether this is what
 * upstream issue #1028 reports is only a hypothesis.
 */
public final class StorageCleaner {
	public static final String ACTION_ID = "cpmplus_w1storage_clear";
	public static final String DIALOG_ID = "cpmplus_w1storage_dialog";
	public static final String CONFIRM_ALL_ID = "cpmplus_w1storage_confirm_all";
	public static final String EMPTY_ID = "cpmplus_w1storage_empty";
	private static final int MAX_PROJECTS = 12, MAX_OTHERS = 10;

	private StorageCleaner() {
	}

	/** one stored file */
	public static final class Entry {
		public final String path;
		public final double bytes;

		Entry(String path, double bytes) {
			this.path = path;
			this.bytes = bytes;
		}
	}

	/** the autosaves of one project (same name after the time stamp) */
	public static final class ProjectAutosaves {
		public final String name;
		public int count;
		public double bytes;
		/** 'YYYYMMDDHHMMSS' of the newest one */
		public String newest = "";

		ProjectAutosaves(String name) {
			this.name = name;
		}
	}

	/** what the CPM editor keeps in this browser (one read-only pass over the storage) */
	public static final class Scan {
		public boolean enabled;
		public final List<Entry> autosaves = new ArrayList<>(), recovered = new ArrayList<>(), leftovers = new ArrayList<>(), others = new ArrayList<>();
		public final Map<String, ProjectAutosaves> projects = new LinkedHashMap<>();
		/** 'm:' / 'f:' keys, every key of this site */
		public double localBytes, totalLocalBytes;

		public int fileCount() {
			return autosaves.size() + recovered.size() + leftovers.size() + others.size();
		}

		/** what the CPM editor uses: the 'm:' / 'f:' keys */
		public double usageBytes() {
			return localBytes;
		}
	}

	/** the file system the plugin installed on Blockbench's window (PluginStart) */
	public static LocalStorageFS fs() {
		FS.IFS impl = FS.getImpl();
		if (impl instanceof LocalStorageFS)return (LocalStorageFS) impl;
		return new LocalStorageFS(DomGlobal.window);
	}

	public static double sum(List<Entry> l) {
		double s = 0;
		for (Entry e : l)s += e.bytes;
		return s;
	}

	public static Scan scan(LocalStorageFS fs) {
		Scan s = new Scan();
		s.enabled = fs.isEnabled();
		s.localBytes = fs.usageBytes();
		s.totalLocalBytes = fs.totalLocalBytes();
		for (String p : fs.storedPaths()) {
			Entry e = new Entry(p, fs.storedBytes(p));
			String auto = LocalStorageFS.autosaveSuffix(p);
			if (p.startsWith("/mnt/")) {
				s.leftovers.add(e);
			} else if (auto != null) {
				s.autosaves.add(e);
				ProjectAutosaves pa = s.projects.computeIfAbsent(auto, ProjectAutosaves::new);
				pa.count++;
				pa.bytes += e.bytes;
				String st = LocalStorageFS.stamp(p);
				if (st != null && st.compareTo(pa.newest) > 0)pa.newest = st;
			} else if (LocalStorageFS.recoveredSuffix(p) != null) {
				s.recovered.add(e);
			} else {
				s.others.add(e);
			}
		}
		return s;
	}

	// ------------------------------------------------------------------ text helpers

	/** '35 KB' / '1,2 MB' (pt-BR) / '1.2 MB' */
	public static String size(double bytes) {
		if (bytes < 1024 * 1024)return I18n.format("cpmplus.w1storage.size.kb", (long) Math.ceil(bytes / 1024));
		long tenths = Math.round(bytes / (1024.0 * 1024.0) * 10);
		return I18n.format("cpmplus.w1storage.size.mb", tenths / 10, tenths % 10);
	}

	/** 'YYYYMMDDHHMMSS' -&gt; '01/11/2026 14:30' (pt-BR) / '2026-11-01 14:30' */
	public static String date(String stamp) {
		if (stamp == null || stamp.length() < 12)return "?";
		return I18n.format("cpmplus.w1storage.date", stamp.substring(0, 4), stamp.substring(4, 6), stamp.substring(6, 8), stamp.substring(8, 10), stamp.substring(10, 12));
	}

	private static String fileName(String path) {
		int s = path.lastIndexOf('/');
		return s >= 0 ? path.substring(s + 1) : path;
	}

	private static String p(String trustedHtml) {
		return "<p style='margin: 4px 0;'>" + trustedHtml + "</p>";
	}

	private static String list(List<String> escapedItems) {
		StringBuilder sb = new StringBuilder("<ul style='margin: 2px 0 6px 18px; padding: 0;'>");
		for (String i : escapedItems)sb.append("<li>").append(i).append("</li>");
		return sb.append("</ul>").toString();
	}

	// ------------------------------------------------------------------ dialog

	/** the menu action: shows usage and what can be deleted */
	public static void open() {
		LocalStorageFS fs = fs();
		Scan s = scan(fs);
		report("w1storageScan", s);
		if (!s.enabled) {
			PlusDialogs.info(EMPTY_ID, "cpmplus.w1storage.title", java.util.Collections.singletonList("cpmplus.w1storage.disabled"));
			return;
		}
		if (s.fileCount() == 0) {
			List<String> l = new ArrayList<>();
			l.add("cpmplus.w1storage.empty");
			l.add(I18n.format("cpmplus.w1storage.total", size(s.totalLocalBytes)));
			PlusDialogs.info(EMPTY_ID, "cpmplus.w1storage.title", l);
			return;
		}
		List<String> html = new ArrayList<>();
		html.add(p(PlusDialogs.esc(I18n.get("cpmplus.w1storage.intro"))));
		html.add(p("<b>" + PlusDialogs.esc(I18n.format("cpmplus.w1storage.usage", size(s.usageBytes()), s.fileCount())) + "</b><br>"
				+ PlusDialogs.esc(I18n.format("cpmplus.w1storage.total", size(s.totalLocalBytes)))));

		// autosaves grouped by project, newest project first
		if (s.autosaves.isEmpty()) {
			html.add(p(PlusDialogs.esc(I18n.get("cpmplus.w1storage.autosaves.none"))));
		} else {
			StringBuilder sb = new StringBuilder(p("<b>" + PlusDialogs.esc(I18n.format("cpmplus.w1storage.autosaves", s.autosaves.size(), size(sum(s.autosaves)))) + "</b>"));
			List<ProjectAutosaves> projects = new ArrayList<>(s.projects.values());
			projects.sort((a, b) -> b.newest.compareTo(a.newest));
			List<String> items = new ArrayList<>();
			for (int i = 0; i < projects.size() && i < MAX_PROJECTS; i++) {
				ProjectAutosaves pa = projects.get(i);
				items.add(PlusDialogs.esc(I18n.format("cpmplus.w1storage.autosaves.project", pa.name, pa.count, size(pa.bytes), date(pa.newest))));
			}
			if (projects.size() > MAX_PROJECTS)items.add(PlusDialogs.esc(I18n.format("cpmplus.w1storage.autosaves.more", projects.size() - MAX_PROJECTS)));
			sb.append(list(items));
			html.add(sb.toString());
		}
		if (!s.recovered.isEmpty())
			html.add(p(PlusDialogs.esc(I18n.format("cpmplus.w1storage.recovered", s.recovered.size(), size(sum(s.recovered))))));
		if (!s.leftovers.isEmpty())
			html.add(p(PlusDialogs.esc(I18n.format("cpmplus.w1storage.leftovers", s.leftovers.size(), size(sum(s.leftovers))))));
		if (!s.others.isEmpty()) {
			List<String> items = new ArrayList<>();
			for (int i = 0; i < s.others.size() && i < MAX_OTHERS; i++)items.add(PlusDialogs.esc(s.others.get(i).path));
			if (s.others.size() > MAX_OTHERS)items.add(PlusDialogs.esc(I18n.format("cpmplus.w1storage.others.more", s.others.size() - MAX_OTHERS)));
			html.add(p(PlusDialogs.esc(I18n.format("cpmplus.w1storage.others", s.others.size(), size(sum(s.others))))) + list(items));
		}
		html.add(p("<i>" + PlusDialogs.esc(I18n.get("cpmplus.w1storage.notUndoable")) + "</i>"));

		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.checkbox("autosaves", I18n.format("cpmplus.w1storage.opt.autosaves", s.autosaves.size(), size(sum(s.autosaves))), true)
				.description("cpmplus.w1storage.opt.autosaves.desc"));
		if (!s.leftovers.isEmpty())
			rows.add(FormRow.checkbox("leftovers", I18n.format("cpmplus.w1storage.opt.leftovers", s.leftovers.size(), size(sum(s.leftovers))), true)
					.description("cpmplus.w1storage.opt.leftovers.desc"));
		rows.add(FormRow.checkbox("all", I18n.format("cpmplus.w1storage.opt.all", s.fileCount(), size(s.usageBytes())), false)
				.description("cpmplus.w1storage.confirmAll"));

		FormOpts o = new FormOpts();
		o.width = 640;
		o.confirmKey = "cpmplus.w1storage.btn.delete";
		o.cancelKey = "cpmplus.w0binfra.btn.cancel";
		o.htmlLines = html.toArray(new String[0]);
		PlusDialogs.form(DIALOG_ID, "cpmplus.w1storage.title", rows, StorageCleaner::onConfirm, o);
	}

	private static void onConfirm(FormResult fr) {
		boolean auto = fr.getBool("autosaves"), left = fr.getBool("leftovers"), all = fr.getBool("all");
		if (all) {
			// second, explicit confirmation (after this dialog has closed)
			DomGlobal.setTimeout(__ -> PlusDialogs.confirm(CONFIRM_ALL_ID, "cpmplus.w1storage.confirmAll.title", "cpmplus.w1storage.confirmAll",
					() -> clear(false, false, true), StorageCleaner::nothingDeleted), 50);
			return;
		}
		if (!auto && !left) {
			nothingDeleted();
			return;
		}
		clear(auto, left, false);
	}

	private static void nothingDeleted() {
		PlusDialogs.toast(I18n.get("cpmplus.w1storage.nothing"), "info", 5000, null);
		PlusTestHooks.setLast("w1storageClear", "nothing");
	}

	/**
	 * Deletes what was chosen (the storage is read again now: whatever matches the chosen kinds at this moment).
	 * @return bytes freed
	 */
	public static double clear(boolean autosaves, boolean leftovers, boolean all) {
		LocalStorageFS fs = fs();
		Scan before = scan(fs);
		int deleted = 0;
		if (all) {
			deleted = before.fileCount();
			fs.clearAll();
		} else {
			if (autosaves) {
				for (Entry e : before.autosaves)if (fs.deleteStoredFile(e.path))deleted++;
			}
			if (leftovers) {
				for (Entry e : before.leftovers)if (fs.deleteStoredFile(e.path))deleted++;
			}
		}
		Scan after = scan(fs);
		double freed = Math.max(0, before.usageBytes() - after.usageBytes());
		if (deleted == 0) {
			nothingDeleted();
		} else {
			PlusDialogs.toast(I18n.format("cpmplus.w1storage.freed", size(freed), deleted), "delete_sweep", 8000, null);
			if (PlusTestHooks.active()) {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("freedBytes", freed);
				r.set("deleted", (double) deleted);
				r.set("usageBefore", before.usageBytes());
				r.set("usageAfter", after.usageBytes());
				PlusTestHooks.setLast("w1storageClear", r);
			}
		}
		return freed;
	}

	// ------------------------------------------------------------------ test mode

	private static JsArray<Object> paths(List<Entry> l) {
		JsArray<Object> a = new JsArray<>();
		for (Entry e : l)a.push(e.path);
		return a;
	}

	/** the scan as a JS object (test mode: window.__cpmplus.last[key] and the w1storage.scan tool) */
	public static JsPropertyMap<Object> toJs(Scan s) {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("enabled", s.enabled);
		r.set("localBytes", s.localBytes);
		r.set("usageBytes", s.usageBytes());
		r.set("totalLocalBytes", s.totalLocalBytes);
		r.set("usageText", size(s.usageBytes()));
		r.set("fileCount", (double) s.fileCount());
		r.set("autosaves", paths(s.autosaves));
		r.set("recovered", paths(s.recovered));
		r.set("leftovers", paths(s.leftovers));
		r.set("others", paths(s.others));
		JsPropertyMap<Object> pr = JsPropertyMap.of();
		for (ProjectAutosaves pa : s.projects.values()) {
			JsPropertyMap<Object> x = JsPropertyMap.of();
			x.set("count", (double) pa.count);
			x.set("bytes", pa.bytes);
			x.set("newest", pa.newest);
			x.set("newestText", date(pa.newest));
			x.set("sizeText", size(pa.bytes));
			pr.set(pa.name, x);
		}
		r.set("projects", pr);
		return r;
	}

	private static void report(String key, Scan s) {
		if (PlusTestHooks.active())PlusTestHooks.setLast(key, toJs(s));
	}
}
