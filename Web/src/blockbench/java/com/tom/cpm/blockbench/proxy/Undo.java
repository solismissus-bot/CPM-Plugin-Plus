package com.tom.cpm.blockbench.proxy;

import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

/**
 * Blockbench's 'Undo' (the UndoSystem of the open project).
 * CPM+ (W0B): cancelEdit / undo / redo / history natives; use util/PlusUndo for edits made by CPM+ tools.
 */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Undo_$$")
public class Undo {
	public static native void initEdit(UndoData dt);
	public static native void finishEdit(String action);
	public static native void finishEdit(String action, UndoData dt);

	// CPM+ (W0B)
	/** drops the edit started by initEdit without a history entry; revert=true restores the saved state */
	public static native void cancelEdit(boolean revert);
	public static native void undo();
	public static native void redo();
	public static JsArrayE<Object> history;
	public static int index;
	/** the edit in progress (between initEdit and finishEdit/cancelEdit), or undefined */
	@JsProperty(name = "current_save")
	public static Object currentSave;

	/** CPM+ (W0B): true when Undo.&lt;name&gt; is a function (cancelEdit is probed before use) */
	@JsOverlay
	public static boolean has(String name) {
		return Global.hasFunction(Global.getGlobal("Undo"), name);
	}

	/** CPM+ (W0B): number of entries in the undo history (-1 when there is no open project) */
	@JsOverlay
	public static int historyLength() {
		try {
			Object u = Global.getGlobal("Undo");
			if (u == null)return -1;
			Object h = Js.asPropertyMap(u).get("history");
			return h != null ? Js.<JsArrayE<Object>>uncheckedCast(h).length : -1;
		} catch (Throwable e) {
			return -1;
		}
	}

	@JsOverlay
	public static boolean isEditing() {
		Object u = Global.getGlobal("Undo");
		return u != null && Js.isTruthy(Js.asPropertyMap(u).get("current_save"));
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class UndoData {
		public OutlinerElement[] elements;
		public boolean selection, outliner, uv_mode, uv_only;
		public Group group;
		public Group[] groups;
		public Texture[] textures;
		public Animation[] animations;
		// CPM+ : keyframe edits (interpolation tool)
		public Keyframe[] keyframes;
		// CPM+ (W0B): texture pixels (painting tools)
		public boolean bitmap;

		@JsOverlay
		public static UndoData make(OutlinerElement... elem) {
			UndoData d = new UndoData();
			d.elements = elem;
			return d;
		}

		@JsOverlay
		public static UndoData make(Group elem) {
			UndoData d = new UndoData();
			d.group = elem;
			return d;
		}

		@JsOverlay
		public static UndoData make(Animation... an) {
			UndoData d = new UndoData();
			d.animations = an;
			return d;
		}

		@JsOverlay
		public static UndoData make(Group... elem) {
			UndoData d = new UndoData();
			d.groups = elem;
			return d;
		}
	}
}
