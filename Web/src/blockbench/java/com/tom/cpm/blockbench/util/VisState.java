package com.tom.cpm.blockbench.util;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;

import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (NEW-VIS-EXPORT, W0C-EXPORTCORE): the PERSISTENT visibility of a group / cube (the eye icon of the outliner),
 * independent of the editor mode and the timeline frame.
 * <p>
 * In Animate mode the CPM preview (CPMVisuals) shows cpm_hidden groups and visibility keyframes by writing the
 * runtime state into Blockbench's own {@code visibility} field; it saves the user's value first in
 * {@code cpm_default_visible} and sets {@code cpm_animator_init}, and restores it when leaving Animate mode. Blockbench
 * itself and the CPM export read {@code visibility} as persistent data, so everything that saves or exports reads
 * {@link #persistentVisible} instead:
 * <ul>
 * <li>BlockbenchExport (the 'showInEditor' / config.json "show" of every cube),</li>
 * <li>the .bbmodel save (Codecs.project compile: save, save as, autosave, backups) through {@link #onCompile}, which
 * puts the persistent value back into the compiled model (the open project is not changed).</li>
 * </ul>
 * W1-PREVIEW's Animate-mode preview keeps writing the runtime state the same way (cpm_animator_init /
 * cpm_default_visible) and calls this helper; nothing here depends on CPMVisuals.
 */
public final class VisState {

	private VisState() {
	}

	/** the user's visibility of the cube (the Animate-mode preview state is ignored) */
	public static boolean persistentVisible(Cube c) {
		if(c == null)return true;
		return c.animatorInit ? c.defaultVisible : c.visibility;
	}

	/** the user's visibility of the group (the Animate-mode preview state is ignored) */
	public static boolean persistentVisible(Group g) {
		if(g == null)return true;
		return g.animatorInit ? g.defaultVisible : g.visibility;
	}

	/** same for any outliner node (Group, Cube or another element with 'visibility'); true for null */
	public static boolean persistentVisible(Object node) {
		if(node instanceof Cube)return persistentVisible((Cube) node);
		if(node instanceof Group)return persistentVisible((Group) node);
		if(node == null || !"object".equals(Js.typeof(node)))return true;
		JsPropertyMap<Object> m = Js.asPropertyMap(node);
		if(Js.isTruthy(m.get("cpm_animator_init")))return Js.isTruthy(m.get("cpm_default_visible"));
		Object v = m.get("visibility");
		return v == null || Js.isTruthy(v);
	}

	/** true while a node shows the Animate-mode runtime state instead of its persistent visibility */
	public static boolean isRuntimeState(Object node) {
		if(node == null || !"object".equals(Js.typeof(node)))return false;
		return Js.isTruthy(Js.asPropertyMap(node).get("cpm_animator_init"));
	}

	/**
	 * Codecs.project 'compile' listener (installed by W0cExportcoreInstall): in the compiled model (model.elements,
	 * model.groups and group objects inside model.outliner) every node that currently shows the Animate-mode runtime
	 * state gets its persistent visibility. Only the compiled copy changes.
	 */
	public static void onCompile(Object event) {
		try {
			if(event == null)return;
			Object model = Js.asPropertyMap(event).get("model");
			if(model == null || !"object".equals(Js.typeof(model)))return;
			fixModel(model);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not restore the saved visibility of the .bbmodel", e);
		}
	}

	private static native void fixModel(Object model) /*-{
		var byUuid = {};
		var add = function(list) { if (list) for (var i = 0; i < list.length; i++) { var n = list[i]; if (n && n.uuid && n.cpm_animator_init) byUuid[n.uuid] = n; } };
		add($wnd.Group && $wnd.Group.all);
		add($wnd.Cube && $wnd.Cube.all);
		var any = false;
		for (var k in byUuid) { any = true; break; }
		if (!any) return;
		// exactly what Blockbench writes for the persistent value: a cube's save copy has 'visibility: false' only
		// when hidden (Cube.getSaveCopy), a group's copy always has the boolean (Group.getSaveCopy)
		var fixElement = function(o) {
			if (!o || typeof o !== 'object' || !o.uuid) return;
			var n = byUuid[o.uuid];
			if (!n) return;
			if (n.cpm_default_visible) delete o.visibility; else o.visibility = false;
		};
		var fixGroup = function(o) {
			if (!o || typeof o !== 'object' || !o.uuid) return;
			var n = byUuid[o.uuid];
			if (n) o.visibility = !!n.cpm_default_visible;
		};
		if (model.elements instanceof Array) for (var i = 0; i < model.elements.length; i++) fixElement(model.elements[i]);
		if (model.groups instanceof Array) for (var j = 0; j < model.groups.length; j++) fixGroup(model.groups[j]);
		var walk = function(l) {
			if (!(l instanceof Array)) return;
			for (var k = 0; k < l.length; k++) { var o = l[k]; if (o && typeof o === 'object') { if ('visibility' in o) fixGroup(o); walk(o.children); } }
		};
		walk(model.outliner);
	}-*/;
}
