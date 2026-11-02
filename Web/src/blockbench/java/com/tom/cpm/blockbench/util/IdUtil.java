package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.model.RootModelType;

import elemental2.dom.DomGlobal;

/**
 * CPM+ (INFRA2, W0C-EXPORTCORE; frozen after W0C): CPM element ids (storeID).
 * <p>
 * In game every element that shares a storeID gets the animations and copy-transforms of the others
 * (AnimationsLoaderV1.findPartRef / CopyTransformEffect.load match ALL elements with that id), and JSON numbers are
 * doubles, so two different 64 bit ids that round to the same double are the same id in game. Rules:
 * <ul>
 * <li>Every tool that clones or creates nodes from existing ones (duplicate, copy, slice, mirror, import subtree)
 * calls {@link #resetIds(Object)} on every new node inside its undo entry (Convention 9): the export then derives a
 * fresh stable id from the new uuid.</li>
 * <li>{@link #newId(Set)} hands out ids in [1000, 2^53): exact as JS numbers, never equal to a used id as long or as
 * double.</li>
 * <li>The export repairs a collision after StoreIDGen, right before writing ({@link #findCollisions},
 * {@link #repairCollisions}, deterministic). A collision comes from ids stored in the project (an imported file,
 * parts copied without resetIds); the export dialog shows it as an INFO row 'storeIdCollision' with the fix
 * 'Renumerar IDs'.</li>
 * </ul>
 */
public final class IdUtil {
	/** WarnEntry key of the export check 'two elements have the same id' */
	public static final String WARN_KEY = "storeIdCollision";
	/** smallest id handed out (CPM uses small numbers for the vanilla parts) */
	public static final long MIN_ID = 1000L;
	/** first value that is no longer exact as a JS number (2^53) */
	public static final long MAX_EXCLUSIVE = 1L << 53;

	private IdUtil() {
	}

	/**
	 * Clears the CPM ids of node and everything inside it (Group or Cube): GroupData / CubeData storeID = 0, the
	 * cube's 'vanilla part' mark off (a copy is a custom cube), cached data dropped and flushed. Call it inside the
	 * undo entry of the tool that created the node (PlusUndo flushes the data before the entry is closed).
	 */
	public static void resetIds(Object node) {
		if(node instanceof Group) {
			Group g = (Group) node;
			DataCache.invalidate(g);
			GroupData gd = g.getData();
			if(gd.getStoreID() != 0) {
				gd.setStoreID(0);
				gd.flush();
			}
			if(g.children != null) {
				for (int i = 0; i < g.children.length; i++) {
					OutlinerNode c = g.children.getAt(i);
					resetIds(c);
				}
			}
		} else if(node instanceof Cube) {
			Cube c = (Cube) node;
			DataCache.invalidate(c);
			CubeData cd = c.getData();
			boolean ch = false;
			if(cd.getStoreID() != 0) {
				cd.setStoreID(0);
				ch = true;
			}
			if(cd.isVanilla()) {
				cd.setVanilla(false);
				ch = true;
			}
			if(ch)cd.flush();
		}
	}

	/**
	 * a new id in [1000, 2^53) that is neither in used nor equal (as a double) to any id in used. Every value below
	 * 2^53 is exact as a double, and a used id of 2^53 or more is 2^53 or more as a double too, so for these candidates
	 * 'same double' and 'same long' are the same test (O(1) per try).
	 */
	public static long newId(Set<Long> used) {
		for (int tries = 0; tries < 10000; tries++) {
			long v = MIN_ID + (long) Math.floor(Math.random() * (double) (MAX_EXCLUSIVE - MIN_ID));
			if(v < MIN_ID || v >= MAX_EXCLUSIVE)continue;
			if(used != null && used.contains(v))continue;
			return v;
		}
		// practically unreachable: the first free value from MIN_ID up
		long v = MIN_ID;
		while(used != null && used.contains(v))v++;
		return v;
	}

	/**
	 * A DETERMINISTIC id in [1000, 2^53) derived from seed that is not in used (same seed + same used = same id; like
	 * {@link #newId}, no used id is the same long or the same double). For repairs that must give the same file on
	 * every export.
	 */
	public static long derivedId(long seed, Set<Long> used) {
		long x = seed;
		for (int tries = 0; tries < 10000; tries++) {
			x += 0x9E3779B97F4A7C15L;// splitmix64
			long z = x;
			z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
			z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
			z = z ^ (z >>> 31);
			long v = MIN_ID + (z >>> 11) % (MAX_EXCLUSIVE - MIN_ID);
			if(used != null && used.contains(v))continue;
			return v;
		}
		long v = MIN_ID;
		while(used != null && used.contains(v))v++;
		return v;
	}

	/** true for the elements StoreIDGen gives an id (every element except the non-duplicated vanilla player roots) */
	public static boolean hasStoreId(ModelElement e) {
		return e.type != ElementType.ROOT_PART || e.duplicated || e.typeData instanceof RootModelType;
	}

	/**
	 * Groups of elements whose ids collide in game: the same long, or different longs that are the same double
	 * (each inner list has 2+ elements, in walk order). Empty when every id is unique.
	 */
	public static List<List<ModelElement>> findCollisions(Editor editor) {
		Map<Double, List<ModelElement>> byD = new HashMap<>();
		List<Double> order = new ArrayList<>();
		Editor.walkElements(editor.elements, e -> {
			if(!hasStoreId(e) || e.storeID == 0)return;
			double d = e.storeID;
			List<ModelElement> l = byD.get(d);
			if(l == null) {
				l = new ArrayList<>();
				byD.put(d, l);
				order.add(d);
			}
			l.add(e);
		});
		List<List<ModelElement>> out = new ArrayList<>();
		for (Double d : order) {
			List<ModelElement> l = byD.get(d);
			if(l.size() > 1)out.add(l);
		}
		return out;
	}

	/**
	 * Safety net after StoreIDGen, right before the project is written: gives every element of a collision except
	 * the first a new unique id ({@link #derivedId}, from its old id: the same project gives the same file on every
	 * export). Animations and copy-transforms point at element objects and get their ids when the project is
	 * written, so they follow. @return number of ids changed (0 = nothing to do)
	 */
	public static int repairCollisions(Editor editor) {
		List<List<ModelElement>> col = findCollisions(editor);
		if(col.isEmpty())return 0;
		Set<Long> used = new HashSet<>();
		Editor.walkElements(editor.elements, e -> {
			if(hasStoreId(e) && e.storeID != 0)used.add(e.storeID);
		});
		int n = 0;
		for (List<ModelElement> l : col) {
			for (int i = 1; i < l.size(); i++) {
				long id = derivedId(l.get(i).storeID * 31 + i, used);
				used.add(id);
				l.get(i).storeID = id;
				n++;
			}
		}
		DomGlobal.console.warn("CPM+: " + n + " element id(s) collided in game (same number) and were renumbered before saving");
		return n;
	}
}
