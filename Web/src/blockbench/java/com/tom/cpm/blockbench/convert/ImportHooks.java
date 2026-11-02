package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.ugwt.client.ExceptionUtil;

import elemental2.dom.DomGlobal;

/**
 * CPM+ (W0C-EXPORTCORE): extension points of the .cpmproject import (BlockbenchImport). Each hook runs inside its
 * own try/catch (a failing hook is logged and skipped; the import goes on). Registrations are removed when the plugin
 * unloads (or with the returned Runnable).
 * <ul>
 * <li>{@link #beforeAnims}: once per import, after the model was built in Blockbench and before the CPM animations
 * are converted (or kept in CPM format): may adjust editor.animations.</li>
 * <li>{@link #onElement}: for every Blockbench node created from a CPM element - the Group of every element (roots
 * too) and the Cube of every non-root element and of every shown vanilla root.</li>
 * <li>{@link #afterImport}: once per import, at the very end (project data stored, everything converted).</li>
 * </ul>
 */
public final class ImportHooks {
	private static final List<EditorHook> BEFORE_ANIMS = new ArrayList<>();
	private static final List<ElementHook> ON_ELEMENT = new ArrayList<>();
	private static final List<AfterImportHook> AFTER_IMPORT = new ArrayList<>();

	private ImportHooks() {
	}

	public static interface EditorHook {
		void accept(Editor editor);
	}

	/** node is the Blockbench Group or Cube created for element */
	public static interface ElementHook {
		void onElement(ModelElement element, OutlinerNode node);
	}

	public static interface AfterImportHook {
		void afterImport(Editor editor, BlockbenchImport importer);
	}

	private static <T> Runnable add(List<T> list, T item) {
		list.add(item);
		Runnable remove = () -> list.remove(item);
		PluginStart.cleanup.add(remove);
		return remove;
	}

	public static Runnable beforeAnims(EditorHook hook) {
		return add(BEFORE_ANIMS, hook);
	}

	public static Runnable onElement(ElementHook hook) {
		return add(ON_ELEMENT, hook);
	}

	public static Runnable afterImport(AfterImportHook hook) {
		return add(AFTER_IMPORT, hook);
	}

	// ------------------------------------------------------------------ called by BlockbenchImport

	static void fireBeforeAnims(Editor editor) {
		for (EditorHook h : new ArrayList<>(BEFORE_ANIMS)) {
			try {
				h.accept(editor);
			} catch (Throwable e) {
				log("beforeAnims", e);
			}
		}
	}

	static void fireElement(ModelElement me, OutlinerNode node) {
		if(ON_ELEMENT.isEmpty() || node == null)return;
		for (ElementHook h : new ArrayList<>(ON_ELEMENT)) {
			try {
				h.onElement(me, node);
			} catch (Throwable e) {
				log("onElement", e);
			}
		}
	}

	static void fireAfterImport(Editor editor, BlockbenchImport importer) {
		for (AfterImportHook h : new ArrayList<>(AFTER_IMPORT)) {
			try {
				h.afterImport(editor, importer);
			} catch (Throwable e) {
				log("afterImport", e);
			}
		}
	}

	private static void log(String what, Throwable e) {
		DomGlobal.console.error("CPM+: import hook " + what + " failed and was skipped:\n" + ExceptionUtil.getStackTrace(e));
	}
}
