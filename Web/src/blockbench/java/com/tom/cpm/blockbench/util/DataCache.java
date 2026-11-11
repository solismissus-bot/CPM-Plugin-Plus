package com.tom.cpm.blockbench.util;

import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Project;

import elemental2.dom.DomGlobal;
import jsinterop.base.Js;

/**
 * CPM+ (G16): the parsed ProjectData / GroupData / CubeData objects are cached on the Blockbench object as
 * 'cpm_dataCache'.
 *
 * <ul>
 * <li>The cache checks itself: each data object remembers the exact cpm_data string it was parsed from or last
 * flushed to, and Group/Cube/Project.getData() parses again when the live string is different (Ctrl+Z / redo
 * of a Blockbench edit, a restored snapshot, a remote edit, a tool that rewrites the string).</li>
 * <li>Changes that were never flushed are not in any string: Blockbench's undo can't see them. So
 * {@link PlusUndo} flushes the group / cube data before it closes the undo entry ({@link #flushNodes()}), and
 * undo / redo / cancelled edits drop the group and cube caches ({@link #invalidateNodes()}, installed by
 * W0bInfraInstall).</li>
 * </ul>
 * Dropping a cache loses its unflushed changes by design: flush first.
 */
public final class DataCache {
	public static final String CACHE_KEY = "cpm_dataCache";

	private DataCache() {
	}

	/** Drops the cached data object of a group, cube or project (null / non-objects are ignored). */
	public static void invalidate(Object node) {
		if (node == null || !"object".equals(Js.typeof(node)))return;
		try {
			Js.asPropertyMap(node).delete(CACHE_KEY);
		} catch (Throwable e) {
		}
	}

	/** Drops the cached ProjectData of the open project. */
	public static void invalidateProject() {
		Object p = Project.getProject();
		if (Js.isTruthy(p))invalidate(p);
	}

	/** Drops every cached data object of the open project (project, groups, cubes). */
	public static void invalidateAll() {
		invalidateProject();
		invalidateNodes();
	}

	/**
	 * Drops the cached GroupData / CubeData of every group and cube (they are parsed again from their cpm_data
	 * string on the next getData(); the project data, which Blockbench's undo does not cover, is kept).
	 */
	public static void invalidateNodes() {
		try {
			if (Group.all != null)for (Group g : Group.all)invalidate(g);
			var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
			if (cubesAll != null)for (int i = 0; i < cubesAll.length; i++)invalidate(cubesAll.getAt(i));
		} catch (Throwable e) {
		}
	}

	/**
	 * Writes the unflushed changes of every cached GroupData / CubeData into its node's cpm_data string (no-op for
	 * unchanged data; nodes without a cache are not touched). Used by PlusUndo before the undo entry is closed.
	 */
	public static void flushNodes() {
		if (Group.all != null) {
			for (Group g : Group.all) {
				Object c = cached(g);
				if (c instanceof GroupData)((GroupData) c).flush();
			}
		}
		if (Cube.all != null) {
			var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
			for (int i = 0; i < cubesAll.length; i++) {
				Object c = cached(cubesAll.getAt(i));
				if (c instanceof CubeData)((CubeData) c).flush();
			}
		}
	}

	private static Object cached(Object node) {
		if (node == null || !"object".equals(Js.typeof(node)))return null;
		return Js.asPropertyMap(node).get(CACHE_KEY);
	}

	/** Blockbench undo / redo / cancelled edit: drops the group and cube caches (see the class comment) */
	public static void onUndoLoaded(Object event) {
		try {
			invalidateNodes();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not refresh the CPM data after undo", e);
		}
	}

	/** true when the cached text and the live cpm_data string are the same (null, undefined and "" are equal) */
	public static boolean sameSource(String cached, String live) {
		boolean ec = cached == null || cached.isEmpty();
		boolean el = live == null || live.isEmpty();
		if (ec || el)return ec == el;
		return cached.equals(live);
	}
}
