package com.tom.cpm.blockbench.tools.model;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.util.DataCache;

import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W4-EXPORTFIX, G1): the single CPM refresh after Blockbench's undo / redo (events load_undo_save, undo, redo).
 * <ul>
 * <li>Every cached CPM data object is dropped (project, groups incl. name colour / item slot / render scale, cubes):
 * the next getData() parses the restored cpm_data string. (W0B-INFRA drops the group / cube caches too; dropping twice
 * is harmless.)</li>
 * <li>When the undone / redone entry touched the model (elements, groups, outliner or textures), the CPM view is
 * refreshed ONCE per undo (ProjectGenerator.updateAll, debounced: Blockbench fires several events per undo), never
 * once per event or per node.</li>
 * </ul>
 */
public final class UndoRefresh {
	private static boolean pending, structural;

	private UndoRefresh() {
	}

	public static void install() {
		PluginStart.addEventListener("load_undo_save", UndoRefresh::onEvent);
		PluginStart.addEventListener("undo", UndoRefresh::onEvent);
		PluginStart.addEventListener("redo", UndoRefresh::onEvent);
	}

	private static void onEvent(Object event) {
		try {
			if (!CPMCodec.isCPM())return;
			DataCache.invalidateAll();
			if (touchesModel(event))structural = true;
			if (pending)return;
			pending = true;
			DomGlobal.setTimeout(__ -> refresh(), 0);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: undo refresh failed", e);
		}
	}

	/** the save restored by this event changes the model (or the event does not say: assume it does) */
	private static boolean touchesModel(Object event) {
		if (event == null || !"object".equals(Js.typeof(event)))return true;
		JsPropertyMap<Object> e = Js.asPropertyMap(event);
		Object save = e.get("save");
		if (save == null || !"object".equals(Js.typeof(save))) {
			// 'undo' / 'redo' events carry the entry: look at its before / post saves
			Object entry = e.get("entry");
			if (entry == null || !"object".equals(Js.typeof(entry)))return false;
			JsPropertyMap<Object> en = Js.asPropertyMap(entry);
			return touches(en.get("before")) || touches(en.get("post"));
		}
		return touches(save);
	}

	private static boolean touches(Object save) {
		if (save == null || !"object".equals(Js.typeof(save)))return false;
		JsPropertyMap<Object> s = Js.asPropertyMap(save);
		return s.has("elements") || s.has("group") || s.has("groups") || s.has("outliner") || s.has("textures");
	}

	private static void refresh() {
		pending = false;
		boolean s = structural;
		structural = false;
		if (!s || !CPMCodec.isCPM())return;
		try {
			ProjectGenerator.updateAll();
			PlusTestHooks.count("w4exportfixUndoRefresh");
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not refresh the view after undo", e);
		}
	}
}
