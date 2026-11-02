package com.tom.cpm.blockbench.util;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;

import elemental2.dom.DomGlobal;

/**
 * CPM+ (INFRA3): every change a CPM+ tool makes to the Blockbench project is exactly ONE undo entry.
 *
 * <pre>
 * PlusUndo.run("Espelhar asa", PlusUndo.structBefore(g), () -&gt; PlusUndo.structAfter(groups, cubes, null), () -&gt; { ...edit... });
 * </pre>
 *
 * When the body throws, the edit is cancelled with Blockbench's Undo.cancelEdit(true) (restores the state saved
 * by initEdit, no history entry; probed with typeof, fallback finishEdit + Undo.undo()) and the exception is
 * rethrown, so the caller shows it (PopupDialogs.displayError) - nothing is left half applied.
 * <p>
 * G16 group / cube data (GroupData / CubeData: putExtra, known setters) edited in the body is flushed into the
 * nodes' cpm_data strings before the entry is closed, so Ctrl+Z / redo restore it like any other property; put
 * every group / cube whose data the body changes into before / after. Undo, redo and a cancelled edit drop the
 * cached data objects ({@link DataCache}), so getData() / getExtra() / exports always match the restored strings.
 * A recorded group / cube whose cpm_data is null or undefined gets "" before the entry opens (Blockbench's undo
 * cannot put a missing value back; GroupData / CubeData.flush() write "" for empty data for the same reason).
 * Project-level pluginData is NOT part of Blockbench's undo: use {@link ProjectDataUndo}.
 */
public final class PlusUndo {

	private PlusUndo() {
	}

	/**
	 * Runs body inside one undo entry. before = what will change (the aspects saved before), after = the same
	 * aspects after the body (new elements / groups / animations included). Exceptions cancel the edit and are
	 * rethrown (RuntimeException as is, others wrapped).
	 */
	public static void run(String label, UndoData before, Supplier<UndoData> after, Runnable body) {
		if (Undo.isEditing())DomGlobal.console.warn("CPM+: an undo edit was still open when '" + label + "' started (Blockbench replaces it)");
		Object known = snapshotUuids();
		emptyMissingData(before);
		Undo.initEdit(before);
		try {
			body.run();
			// G16: group / cube data changed in the body (putExtra, known setters) must be IN the cpm_data
			// strings when Blockbench records the entry, or Ctrl+Z / redo could not see them
			DataCache.flushNodes();
		} catch (Throwable e) {
			trackCreated(known);
			cancel(label);
			if (e instanceof RuntimeException)throw (RuntimeException) e;
			if (e instanceof Error)throw (Error) e;
			throw new RuntimeException(e);
		}
		UndoData a;
		try {
			a = after != null ? after.get() : before;
		} catch (Throwable e) {
			trackCreated(known);
			cancel(label);
			if (e instanceof RuntimeException)throw (RuntimeException) e;
			throw new RuntimeException(e);
		}
		Undo.finishEdit(label, a);
	}

	/**
	 * G16: Blockbench's undo cannot put a MISSING cpm_data back (Property.merge skips null / undefined values), so a
	 * group / cube whose cpm_data was null or undefined before the edit (a file saved by an older build, a project
	 * converted from another format) would keep the edited string after Ctrl+Z or a cancelled edit. Such values are
	 * set to "" first (the property's default; same meaning: no CPM data) on every group / cube the entry records:
	 * before.elements, before.group(s), and every group when the outliner is part of the entry.
	 */
	private static native void emptyMissingData(UndoData before) /*-{
		try {
			if (!before) return;
			var G = $wnd.Group, C = $wnd.Cube;
			var fix = function(n) {
				if (n && (n.cpm_data === null || n.cpm_data === undefined) && ((G && n instanceof G) || (C && n instanceof C))) n.cpm_data = '';
			};
			var each = function(list) { if (list) for (var i = 0; i < list.length; i++) fix(list[i]); };
			each(before.elements);
			each(before.groups);
			fix(before.group);
			if (before.outliner) each(G && G.all);
		} catch (e) {
			console.warn('CPM+: could not prepare the CPM data for undo', e);
		}
	}-*/;

	/** uuids of every group, element, texture and animation that exist before the body runs */
	private static native Object snapshotUuids() /*-{
		var s = {};
		try {
			var add = function(list) { if (list) for (var i = 0; i < list.length; i++) if (list[i] && list[i].uuid) s[list[i].uuid] = true; };
			add($wnd.Group && $wnd.Group.all);
			add($wnd.Outliner && $wnd.Outliner.elements);
			add($wnd.Texture && $wnd.Texture.all);
			add($wnd.Animator && $wnd.Animator.animations);
		} catch (e) {}
		return s;
	}-*/;

	/**
	 * After a failed body: Blockbench's cancelEdit(true) restores the saved state and removes what is in the
	 * reference save but not in the saved one; the reference is built from the SAME aspects object initEdit got.
	 * So every group / element / texture / animation the body created is added to those aspects (and the saved
	 * state gets an empty entry for an aspect it did not record), then cancelEdit removes them too: nothing is
	 * left half applied, and no history entry is added.
	 */
	private static native void trackCreated(Object known) /*-{
		try {
			var undo = $wnd.Undo;
			var cs = undo && undo.current_save;
			if (!cs || !cs.aspects || !known) return;
			var asp = cs.aspects;
			var fresh = function(list) { var out = []; if (list) for (var i = 0; i < list.length; i++) { var n = list[i]; if (n && n.uuid && !known[n.uuid]) out.push(n); } return out; };
			var groups = fresh($wnd.Group && $wnd.Group.all);
			var elements = fresh($wnd.Outliner && $wnd.Outliner.elements);
			var textures = fresh($wnd.Texture && $wnd.Texture.all);
			var animations = fresh($wnd.Animator && $wnd.Animator.animations);
			if (groups.length) {
				if (!(asp.groups instanceof Array)) asp.groups = [];
				for (var g = 0; g < groups.length; g++) if (asp.groups.indexOf(groups[g]) < 0) asp.groups.push(groups[g]);
				if (!cs.groups) cs.groups = [];
			}
			if (elements.length) {
				if (!(asp.elements instanceof Array)) asp.elements = [];
				for (var e = 0; e < elements.length; e++) if (asp.elements.indexOf(elements[e]) < 0) asp.elements.push(elements[e]);
				if (!cs.elements) cs.elements = {};
			}
			if (textures.length) {
				if (!(asp.textures instanceof Array)) asp.textures = [];
				for (var t = 0; t < textures.length; t++) if (asp.textures.indexOf(textures[t]) < 0) asp.textures.push(textures[t]);
				if (!cs.textures) cs.textures = {};
			}
			if (animations.length) {
				if (!(asp.animations instanceof Array)) asp.animations = [];
				for (var a = 0; a < animations.length; a++) if (asp.animations.indexOf(animations[a]) < 0) asp.animations.push(animations[a]);
				if (!cs.animations) cs.animations = {};
			}
		} catch (ex) {
			console.warn('CPM+: could not track the nodes created by a failed edit', ex);
		}
	}-*/;

	/**
	 * Same as {@link #run} but shows the error to the user (CPM error dialog) instead of rethrowing.
	 * @return true when the edit was applied
	 */
	public static boolean runOrReport(String label, UndoData before, Supplier<UndoData> after, Runnable body) {
		try {
			run(label, before, after, body);
			return true;
		} catch (Throwable e) {
			PopupDialogs.displayError(PlusDialogs.esc(label), e);
			return false;
		}
	}

	/**
	 * drops the open edit and restores what initEdit saved (no history entry); then the cached group / cube data
	 * are dropped, so changes the body made to them (flushed or not) are gone too
	 */
	private static void cancel(String label) {
		try {
			if (Undo.has("cancelEdit")) {
				Undo.cancelEdit(true);
			} else {
				// very old Blockbench: record, then undo it (leaves one redo-able entry)
				Undo.finishEdit(label);
				Undo.undo();
			}
		} catch (Throwable e2) {
			DomGlobal.console.error("CPM+: could not cancel the undo edit of '" + label + "'", e2);
		}
		DataCache.invalidateNodes();
	}

	/** one undo entry for changes of existing animations (properties, keyframes) */
	public static void editAnimations(Collection<Animation> animations, String label, Runnable body) {
		Animation[] arr = animations.toArray(new Animation[0]);
		run(label, UndoData.make(arr), () -> UndoData.make(arr), body);
	}

	/** one undo entry that creates an animation (the supplier creates and adds it) */
	public static Animation createAnimation(String label, Supplier<Animation> create) {
		Animation[] out = new Animation[1];
		run(label, UndoData.make(new Animation[0]), () -> UndoData.make(out[0] != null ? new Animation[] {out[0]} : new Animation[0]), () -> out[0] = create.get());
		return out[0];
	}

	/**
	 * Undo aspects before a structural change (new / moved / removed groups and cubes): outliner + the given
	 * groups (always an array, may be empty) + no elements.
	 */
	public static UndoData structBefore(Group... groups) {
		UndoData d = new UndoData();
		d.outliner = true;
		d.groups = groups != null ? groups : new Group[0];
		d.elements = new OutlinerElement[0];
		return d;
	}

	/** Undo aspects after a structural change: outliner + groups + elements (+ textures when not empty). */
	public static UndoData structAfter(Group[] groups, OutlinerElement[] elements, Texture[] textures) {
		UndoData d = new UndoData();
		d.outliner = true;
		d.groups = groups != null ? groups : new Group[0];
		d.elements = elements != null ? elements : new OutlinerElement[0];
		if (textures != null && textures.length > 0)d.textures = textures;
		return d;
	}

	public static UndoData structAfter(List<Group> groups, List<? extends OutlinerElement> elements, List<Texture> textures) {
		return structAfter(groups != null ? groups.toArray(new Group[0]) : null,
				elements != null ? elements.toArray(new OutlinerElement[0]) : null,
				textures != null ? textures.toArray(new Texture[0]) : null);
	}
}
