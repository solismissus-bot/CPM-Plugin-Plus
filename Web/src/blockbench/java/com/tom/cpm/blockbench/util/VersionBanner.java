package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusInfo;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.PlusTools;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Codecs;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.MenuBar.BarItem;
import com.tom.cpm.blockbench.proxy.Project;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.HTMLButtonElement;
import elemental2.dom.HTMLDivElement;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (G16): version stamp of CPM .bbmodel files and the notice for files saved by a newer CPM+.
 *
 * <ul>
 * <li>Every CPM .bbmodel Blockbench compiles (save, save as, backup) gets
 * {@code cpm_data.cpmplus = {fmt, ver, minReader[, fields][, newest]}} - written into the saved JSON only (the
 * open project is not changed). The stamp is deterministic (no time stamp): compiling an unchanged project twice
 * gives the same bytes. .cpmproject exports are not affected.</li>
 * <li>A file that carries data of a NEWER CPM+ keeps that information when this build saves it: {@code newest}
 * = the newer build's {fmt, ver, minReader, fields} and {@code minReader} = the highest one seen, so the next
 * older build still shows the notice, and the newer build still sees (own fmt &lt; its fmt) that an older build
 * saved the file.</li>
 * <li>On open: newest fmt &gt; {@link PlusInfo#FORMAT_INT} -&gt; a yellow, non-blocking notice for that project tab,
 * shown IN the layout above the 3D view (it covers no toolbar or control), with [Atualizar] (About),
 * [Salvar como cópia] (Blockbench's Save As) and [Entendi] (gone for this file: remembered per user in PlusPrefs,
 * the project is not changed); minReader &gt; FORMAT_INT -&gt; a stronger notice recommending a copy. Unstamped
 * (legacy) and older files: no notice. The text never promises protection from the official plugin or older
 * versions.</li>
 * <li>Files last saved by an OLDER CPM+: the orphan checks of later features run (they drop own keys that point
 * to things that no longer exist, with a console line).</li>
 * </ul>
 */
public final class VersionBanner {
	public static final String BANNER_ID = "cpmplus_w0binfra_banner";
	public static final String STAMP_KEY = "cpmplus";
	/** stamp key: the stamp of the newest CPM+ whose data the file carries (only when newer than the writer) */
	public static final String NEWEST_KEY = "newest";
	/** PlusPrefs key: notices the user dismissed ([Entendi]), newest first ... oldest dropped */
	private static final String PREF_ACKS = "cpmplus.bannerAck";
	private static final int MAX_ACKS = 50;

	private static final Map<String, Info> byProject = new HashMap<>();
	private static final List<IntUnaryOperator> orphanChecks = new ArrayList<>();

	private VersionBanner() {
	}

	private static final class Info {
		String project, ver, ackId;
		int fmt, minReader;
		boolean strong, toasted;
		/** cpm_plus_* fields of the newer build this version does not know */
		int unknownFields;
	}

	public static void install() {
		PluginStart.addEventListener(Codecs.project, "compile", VersionBanner::onCompile);
		PluginStart.addEventListener(Codecs.project, "parsed", VersionBanner::onParsed);
		PluginStart.addEventListener("select_project", e -> render());
		PluginStart.addEventListener("close_project", e -> {
			try {
				Object p = Js.asPropertyMap(e).get("project");
				Object u = p != null ? Js.asPropertyMap(p).get("uuid") : null;
				if (u != null)byProject.remove(String.valueOf(u));
			} catch (Throwable ex) {
			}
			DomGlobal.setTimeout(__ -> render(), 0);
		});
		PluginStart.cleanup.add(() -> {
			byProject.clear();
			orphanChecks.clear();
			removeDom();
		});
	}

	/**
	 * Registers a check run when a file stamped by an OLDER CPM+ opens: gets the file's fmt, drops own data that
	 * no longer matches the model (e.g. a key pointing to a deleted animation) and returns how many keys it
	 * dropped. Removed on unload.
	 */
	public static void addOrphanCheck(IntUnaryOperator check) {
		orphanChecks.add(check);
	}

	// ------------------------------------------------------------------ stamp

	private static int toInt(Object o) {
		if (o instanceof Number)return (int) Math.floor(((Number) o).doubleValue());
		if (o instanceof String) {
			try {
				return Integer.parseInt(((String) o).trim());
			} catch (Exception e) {
			}
		}
		return 0;
	}

	/** the stamp with the highest fmt a file's stamp carries (the stamp itself or its 'newest'); null for null */
	private static Map<String, Object> newestOf(Map<String, Object> st) {
		if (st == null)return null;
		Map<String, Object> n = JsonUtil.asMap(st.get(NEWEST_KEY));
		if (n != null && toInt(n.get("fmt")) > toInt(st.get("fmt")))return n;
		return st;
	}

	/** the stamp this build writes into a new / unstamped file */
	public static Map<String, Object> stamp() {
		return stamp(null);
	}

	/**
	 * The stamp this build writes into a file whose current stamp is {@code existing} (null = none): this build's
	 * fmt / ver / minReader / exported cpm_plus_* fields, plus 'newest' and a raised minReader when the file
	 * carries data of a newer CPM+ (see the class comment). Deterministic: no time stamp.
	 */
	public static Map<String, Object> stamp(Map<String, Object> existing) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("fmt", PlusInfo.FORMAT_INT);
		m.put("ver", String.valueOf(System.getProperty("cpm.version")));
		m.put("minReader", PlusInfo.MIN_READER);
		List<Object> fields = new ArrayList<>();
		for (String n : CPMCodec.exportedPropertyNames())if (n != null && n.startsWith("cpm_plus_") && !fields.contains(n))fields.add(n);
		if (!fields.isEmpty())m.put("fields", fields);
		Map<String, Object> newest = newestOf(existing);
		if (newest != null && toInt(newest.get("fmt")) > PlusInfo.FORMAT_INT) {
			int minReader = Math.max(PlusInfo.MIN_READER, Math.max(toInt(existing.get("minReader")), toInt(newest.get("minReader"))));
			m.put("minReader", minReader);
			Map<String, Object> n = new LinkedHashMap<>();
			n.put("fmt", toInt(newest.get("fmt")));
			if (newest.get("ver") instanceof String)n.put("ver", newest.get("ver"));
			n.put("minReader", toInt(newest.get("minReader")));
			List<Object> nf = JsonUtil.asList(newest.get("fields"));
			if (nf != null) {
				List<Object> strs = new ArrayList<>();
				for (Object f : nf)if (f instanceof String)strs.add(f);
				if (!strs.isEmpty())n.put("fields", strs);
			}
			m.put(NEWEST_KEY, n);
		}
		return m;
	}

	private static void onCompile(Object evt) {
		try {
			if (!CPMCodec.isCPM() || evt == null)return;
			Object model = Js.asPropertyMap(evt).get("model");
			if (model == null || !"object".equals(Js.typeof(model)))return;
			JsPropertyMap<Object> m = Js.uncheckedCast(model);
			Object cur = m.get("cpm_data");
			// native JSON only: cpm_data can hold megabytes of stored animations, converting it to Java maps on
			// every save / backup would cost close to a second on heavy models
			JsPropertyMap<Object> data;
			if (cur != null && "string".equals(Js.typeof(cur)) && !((String) cur).isEmpty()) {
				Object parsed = elemental2.core.Global.JSON.parse((String) cur);
				if (parsed == null || !"object".equals(Js.typeof(parsed)) || elemental2.core.JsArray.isArray(parsed))return;// not an object: leave the file as it is
				data = Js.uncheckedCast(parsed);
			} else {
				data = JsPropertyMap.of();
			}
			Map<String, Object> existing = JsonUtil.asMap(JsonUtil.fromJs(data.get(STAMP_KEY)));
			data.set(STAMP_KEY, JsonUtil.toJs(stamp(existing)));
			m.set("cpm_data", elemental2.core.Global.JSON.stringify(data));
		} catch (Throwable e) {
			// a broken stamp must never break saving
			DomGlobal.console.warn("CPM+: could not write the version stamp", e);
		}
	}

	// ------------------------------------------------------------------ notice

	private static void onParsed(Object evt) {
		try {
			if (!CPMCodec.isCPM())return;
			String uuid = Project.getUuid();
			String txt = Project.pluginData;
			if (uuid == null || txt == null || txt.isEmpty())return;
			// native parse, only the small stamp is converted (heavy files: see onCompile)
			Object parsed = elemental2.core.Global.JSON.parse(txt);
			if (parsed == null || !"object".equals(Js.typeof(parsed)))return;
			Map<String, Object> st = JsonUtil.asMap(JsonUtil.fromJs(Js.asPropertyMap(parsed).get(STAMP_KEY)));
			if (st == null)return;// legacy file: no notice, no change
			int fmt = toInt(st.get("fmt"));
			if (fmt < PlusInfo.FORMAT_INT)runOrphanChecks(fmt);
			Map<String, Object> newest = newestOf(st);
			int effFmt = toInt(newest.get("fmt"));
			int minReader = Math.max(toInt(st.get("minReader")), toInt(newest.get("minReader")));
			if (effFmt <= PlusInfo.FORMAT_INT && minReader <= PlusInfo.FORMAT_INT)return;
			Object ver = newest.get("ver");
			Info info = new Info();
			info.project = uuid;
			info.fmt = effFmt;
			info.minReader = minReader;
			info.ver = ver instanceof String && !((String) ver).isEmpty() ? (String) ver : "fmt " + effFmt;
			info.strong = minReader > PlusInfo.FORMAT_INT;
			List<String> ours = CPMCodec.registeredPropertyNames();
			List<String> unknown = new ArrayList<>();
			for (Map<String, Object> s : Arrays.asList(st, newest)) {
				List<Object> fields = JsonUtil.asList(s.get("fields"));
				if (fields != null)for (Object f : fields)if (f instanceof String && !ours.contains(f) && !unknown.contains(f))unknown.add((String) f);
			}
			Collections.sort(unknown);
			info.unknownFields = unknown.size();
			// what the notice is about (the newer data) + which file (its project name): stable across saves
			info.ackId = effFmt + "|" + info.ver + "|" + minReader + "|" + String.join(",", unknown) + "|" + (Project.name != null ? Project.name : "");
			if (isAcked(info.ackId)) {
				PlusTestHooks.setLast("versionBanner", lastInfo(info, "acknowledged"));
				return;
			}
			byProject.put(uuid, info);
			PlusTestHooks.count("versionBanners");
			PlusTestHooks.setLast("versionBanner", lastInfo(info, "shown"));
			render();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not read the version stamp", e);
		}
	}

	private static Map<String, Object> lastInfo(Info info, String state) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", state);
		m.put("project", info.project);
		m.put("fmt", info.fmt);
		m.put("minReader", info.minReader);
		m.put("ver", info.ver);
		m.put("strong", info.strong);
		m.put("ackId", info.ackId);
		m.put("unknownFields", info.unknownFields);
		return m;
	}

	private static void runOrphanChecks(int fileFmt) {
		if (orphanChecks.isEmpty())return;
		int dropped = 0;
		for (IntUnaryOperator c : new ArrayList<>(orphanChecks)) {
			try {
				dropped += c.applyAsInt(fileFmt);
			} catch (Throwable e) {
				DomGlobal.console.warn("CPM+: orphan check failed", e);
			}
		}
		if (dropped > 0)DomGlobal.console.log("CPM+: file saved by an older CPM+ (data format " + fileFmt + ", this build " + PlusInfo.FORMAT_INT + "): dropped " + dropped + " orphan key(s)");
	}

	private static boolean isAcked(String ackId) {
		List<Object> l = JsonUtil.asList(PlusPrefs.getJson(PREF_ACKS));
		return l != null && l.contains(ackId);
	}

	/** [Entendi]: never show this notice again for this file (per user, PlusPrefs; the project is not changed) */
	private static void acknowledge(Info info) {
		List<Object> l = JsonUtil.asList(PlusPrefs.getJson(PREF_ACKS));
		List<Object> nl = l != null ? new ArrayList<>(l) : new ArrayList<>();
		nl.remove(info.ackId);
		nl.add(info.ackId);
		while (nl.size() > MAX_ACKS)nl.remove(0);
		PlusPrefs.set(PREF_ACKS, nl);
		byProject.remove(info.project);
		PlusTestHooks.setLast("versionBanner", lastInfo(info, "dismissed"));
		render();
	}

	private static void removeDom() {
		Element el = DomGlobal.document.getElementById(BANNER_ID);
		if (el != null) {
			el.remove();
			relayout();
		}
	}

	/** Blockbench re-measures the 3D view after the notice appeared / went away (it sits in the layout) */
	private static native void relayout() /*-{
		try {
			if (typeof $wnd.resizeWindow === 'function') $wnd.resizeWindow();
		} catch (e) {}
	}-*/;

	/** shows the notice of the selected project tab (or nothing) */
	private static void render() {
		try {
			String uuid = Project.getUuid();
			Info info = uuid != null ? byProject.get(uuid) : null;
			Element old = DomGlobal.document.getElementById(BANNER_ID);
			if (info == null) {
				if (old != null)removeDom();
				return;
			}
			if (old != null) {
				if (info.project.equals(old.getAttribute("data-project")))return;
				old.remove();
			}
			// in the layout, above the 3D view (#center is a flex column: the view gets shorter, nothing is covered)
			Element center = DomGlobal.document.getElementById("center");
			if (center == null) {
				// unknown Blockbench layout: a toast instead (never an overlay over the toolbars), once
				if (!info.toasted) {
					info.toasted = true;
					PlusDialogs.toast(text(info), "warning", 0, () -> fallbackDialog(info));
				}
				return;
			}
			Element topSlot = DomGlobal.document.getElementById("top_slot");
			HTMLDivElement box = build(info);
			if (topSlot != null && topSlot.parentElement == center)center.insertBefore(box, topSlot);
			else center.insertBefore(box, center.firstChild);
			relayout();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not show the version notice", e);
		}
	}

	private static String text(Info info) {
		String text = PlusDialogs.trf(info.strong ? "cpmplus.w0binfra.banner.needsNewer" : "cpmplus.w0binfra.banner.newer", info.ver);
		if (info.unknownFields > 0)text += " " + PlusDialogs.trf("cpmplus.w0binfra.banner.newerFields", info.unknownFields);
		return text;
	}

	private static void fallbackDialog(Info info) {
		PlusDialogs.messageBox("cpmplus.w0binfra.dlg.warn.title", text(info),
				new String[] {"cpmplus.w0binfra.banner.update", "cpmplus.w0binfra.banner.saveCopy", "cpmplus.w0binfra.banner.ok"}, i -> {
					if (i == 0)PlusTools.showAbout();
					else if (i == 1)saveAsCopy();
					else if (i == 2)acknowledge(info);
				});
	}

	private static HTMLDivElement build(Info info) {
		HTMLDivElement box = (HTMLDivElement) DomGlobal.document.createElement("div");
		box.id = BANNER_ID;
		box.setAttribute("data-project", info.project);
		box.setAttribute("role", "status");
		box.style.cssText = "position: relative; flex: 0 0 auto; display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px;"
				+ "padding: 6px 10px; font-size: 13px; line-height: 1.35; border-bottom: 1px solid rgba(0,0,0,0.35);"
				+ "color: #1d1d1d; background: " + (info.strong ? "#ff9e80" : "#ffd966") + ";";
		HTMLDivElement msg = (HTMLDivElement) DomGlobal.document.createElement("div");
		msg.className = "cpmplus_banner_text";
		msg.style.cssText = "flex: 1 1 320px; min-width: 0;";
		msg.textContent = text(info);// plain text: the version text comes from the file
		box.appendChild(msg);
		HTMLDivElement bar = (HTMLDivElement) DomGlobal.document.createElement("div");
		bar.style.cssText = "display: flex; gap: 6px; flex-wrap: wrap; margin-left: auto;";
		bar.appendChild(button("update", "cpmplus.w0binfra.banner.update", () -> PlusTools.showAbout()));
		bar.appendChild(button("copy", "cpmplus.w0binfra.banner.saveCopy", VersionBanner::saveAsCopy));
		bar.appendChild(button("ok", "cpmplus.w0binfra.banner.ok", () -> acknowledge(info)));
		box.appendChild(bar);
		return box;
	}

	private static HTMLButtonElement button(String act, String key, Runnable r) {
		HTMLButtonElement b = (HTMLButtonElement) DomGlobal.document.createElement("button");
		b.textContent = PlusDialogs.tr(key);
		b.setAttribute("data-act", act);
		b.style.cssText = "color: #1d1d1d; background: rgba(255,255,255,0.75); border: 1px solid rgba(0,0,0,0.35); border-radius: 4px; padding: 2px 12px; cursor: pointer; min-width: 80px; height: auto;";
		b.onclick = e -> {
			try {
				r.run();
			} catch (Throwable ex) {
				PopupDialogs.displayError(PlusDialogs.esc(b.textContent), ex);
			}
			return null;
		};
		return b;
	}

	/**
	 * Blockbench's own 'Save As' (desktop: new file; web: download), so the original file stays untouched. Never
	 * falls back to a plain Save (that would overwrite the file the notice warns about).
	 */
	private static void saveAsCopy() {
		BarItem bi = Global.getBarItem("save_project_as");
		if (bi == null) {
			PlusDialogs.info("cpmplus_w0binfra_save_copy", "cpmplus.w0binfra.banner.saveCopy", Collections.singletonList("cpmplus.w0binfra.banner.saveCopyManual"));
			return;
		}
		Action a = Js.uncheckedCast(bi);
		a.click();
	}
}
