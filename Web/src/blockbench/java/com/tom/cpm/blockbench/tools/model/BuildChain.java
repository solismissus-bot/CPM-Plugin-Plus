package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.ext.W4RigNatives;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;

/**
 * CPM+ (W4-RIG, G19): 'Montar corrente…' / 'Fazer osso desta peça'. Selected pieces (cubes, or groups without
 * keyframes) that share one parent become bones: each piece gets its own group whose pivot is the seam with the
 * previous piece, nested one inside the other ('Encadear') or side by side. New groups have rotation 0 and the cubes
 * keep from / to / rotation / origin, so nothing moves (checked: every shown corner before == after within 1e-4,
 * otherwise the edit is cancelled). One undo entry.
 */
public final class BuildChain {
	public static final int MAX_LINKS = 64;

	public enum Order {AUTO, CLICK, OUTLINER}

	public enum JointMode {SEAM, CENTER, BACK}

	private BuildChain() {
	}

	/** one piece of the chain */
	public static final class Item {
		public final Object node;
		public final String name;
		public final RigGeom.Box box;

		Item(Object node, String name, RigGeom.Box box) {
			this.node = node;
			this.name = name;
			this.box = box;
		}
	}

	/** the computed chain (nothing applied yet) */
	public static final class Plan {
		/** refusal (plain text), null when the plan can be applied */
		public String error;
		public Group parent;
		public final List<Item> items = new ArrayList<>();
		public final List<double[]> joints = new ArrayList<>();
		public final List<String> names = new ArrayList<>();
		/** the order could not be decided here (two candidates within 10 %) */
		public final List<Boolean> fork = new ArrayList<>();
		/** this piece does not touch the previous one */
		public final List<Boolean> gap = new ArrayList<>();
		public boolean chained = true;
		public String prefix;

		public boolean hasFork() {
			return fork.contains(Boolean.TRUE);
		}
	}

	// ------------------------------------------------------------------ selection

	/** the selected pieces: top-most selected groups, then selected cubes not inside a selected group (selection order) */
	public static List<Object> selection() {
		List<Object> out = new ArrayList<>();
		JsArray<Object> gs = W4RigNatives.selectedGroups();
		List<Group> groups = new ArrayList<>();
		for (int i = 0; i < gs.length; i++)if (gs.getAt(i) instanceof Group)groups.add((Group) gs.getAt(i));
		for (Group g : groups) {
			boolean inner = false;
			for (Group o : groups)if (o != g && RootUtil.isDescendant(g, o))inner = true;
			if (!inner && !out.contains(g))out.add(g);
		}
		JsArray<Object> es = W4RigNatives.selectedElements();
		for (int i = 0; i < es.length; i++) {
			Object e = es.getAt(i);
			if (!(e instanceof Cube))continue;
			boolean inner = false;
			for (Group o : groups)if (RootUtil.isDescendant(e, o))inner = true;
			if (!inner && !out.contains(e))out.add(e);
		}
		return out;
	}

	// ------------------------------------------------------------------ plan

	public static Plan plan(List<Object> sel, Order order, JointMode mode, String prefix, boolean chained) {
		Plan p = new Plan();
		p.chained = chained;
		p.prefix = prefix != null ? prefix.trim() : "";
		if (sel == null || sel.isEmpty()) {
			p.error = PlusDialogs.tr("cpmplus.w4rig.g19.err.empty");
			return p;
		}
		if (sel.size() > MAX_LINKS) {
			p.error = PlusDialogs.trf("cpmplus.w4rig.g19.err.max", String.valueOf(MAX_LINKS));
			return p;
		}
		Object parent0 = parentRaw(sel.get(0));
		for (Object o : sel) {
			if (parentRaw(o) != parent0) {
				p.error = PlusDialogs.tr("cpmplus.w4rig.g19.err.parents");
				return p;
			}
		}
		for (Object o : sel) {
			if (o instanceof Group && ((Group) o).parent == Group.ROOT) {
				p.error = PlusDialogs.trf("cpmplus.w4rig.g19.err.root", ((Group) o).name);
				return p;
			}
		}
		if (!(parent0 instanceof Group)) {
			p.error = PlusDialogs.tr("cpmplus.w4rig.g19.err.atRoot");
			return p;
		}
		p.parent = (Group) parent0;
		for (Object o : sel) {
			if (o instanceof Group) {
				Group g = (Group) o;
				String keyed = keyedInside(g);
				if (keyed != null) {
					p.error = PlusDialogs.trf("cpmplus.w4rig.g19.err.keyed", keyed);
					return p;
				}
				if (g.getData().getItemRenderer() != null) {
					p.error = PlusDialogs.trf("cpmplus.w4rig.g19.err.slot", g.name);
					return p;
				}
			}
		}
		List<Item> items = new ArrayList<>();
		for (Object o : sel) {
			RigGeom.Box b = boxInParent(o, p.parent);
			String n = o instanceof Group ? ((Group) o).name : ((Cube) o).name;
			if (b.isEmpty()) {
				p.error = PlusDialogs.trf("cpmplus.w4rig.g19.err.noCubes", n);
				return p;
			}
			items.add(new Item(o, n, b));
		}
		double[] pivot = RigGeom.vec(p.parent.origin);
		// ---- order
		List<Item> ordered = new ArrayList<>();
		List<Boolean> fork = new ArrayList<>();
		if (order == Order.AUTO) {
			List<Item> left = new ArrayList<>(items);
			Item cur = null;
			// the piece that touches the parent's own cubes starts the chain (several: decided by distance, fork check)
			List<Item> touching = new ArrayList<>();
			for (Item it : items)if (touchesParentCubes(p.parent, items, it))touching.add(it);
			if (touching.size() == 1) {
				cur = touching.get(0);
				ordered.add(cur);
				fork.add(false);
				left.remove(cur);
			}
			while (!left.isEmpty()) {
				Item best = null, second = null;
				double bd = Double.MAX_VALUE, sd = Double.MAX_VALUE;
				for (Item it : left) {
					double d = cur == null ? it.box.distance(pivot) : it.box.distance(cur.box);
					// equal box distances (touching pieces): the nearer centre wins
					double tie = cur == null ? RigGeom.dist(it.box.center(), pivot) : RigGeom.dist(it.box.center(), cur.box.center());
					double key = d * 1000 + tie * 1e-3;
					if (key < bd) {
						second = best;
						sd = bd;
						best = it;
						bd = key;
					} else if (key < sd) {
						second = it;
						sd = key;
					}
				}
				boolean f = false;
				if (second != null) {
					double d1 = cur == null ? best.box.distance(pivot) : best.box.distance(cur.box);
					double d2 = cur == null ? second.box.distance(pivot) : second.box.distance(cur.box);
					if (Math.abs(d1 - d2) <= 0.1 * Math.max(d1, d2) + 1e-6) {
						// both touch / are equally far: decided by the centres, a fork when those are within 10 % too
						double c1 = cur == null ? RigGeom.dist(best.box.center(), pivot) : RigGeom.dist(best.box.center(), cur.box.center());
						double c2 = cur == null ? RigGeom.dist(second.box.center(), pivot) : RigGeom.dist(second.box.center(), cur.box.center());
						if (Math.abs(c1 - c2) <= 0.1 * Math.max(c1, c2) + 1e-6)f = true;
					}
				}
				ordered.add(best);
				fork.add(f);
				left.remove(best);
				cur = best;
			}
		} else if (order == Order.OUTLINER) {
			List<Item> rest = new ArrayList<>(items);
			for (int i = 0; i < p.parent.children.length; i++) {
				OutlinerNode n = p.parent.children.getAt(i);
				for (Item it : rest)if (it.node == n) {
					ordered.add(it);
					fork.add(false);
				}
			}
			for (Item it : rest)if (!ordered.contains(it)) {
				ordered.add(it);
				fork.add(false);
			}
		} else {
			for (Item it : items) {
				ordered.add(it);
				fork.add(false);
			}
		}
		p.items.addAll(ordered);
		p.fork.addAll(fork);
		// ---- joints
		for (int i = 0; i < ordered.size(); i++) {
			Item it = ordered.get(i);
			double[] j;
			boolean gap = false;
			if (mode == JointMode.CENTER) {
				j = it.box.center();
			} else if (i == 0) {
				j = firstJoint(p.parent, ordered, pivot);
			} else {
				Item prev = ordered.get(i - 1);
				gap = prev.box.distance(it.box) > 0.01;
				if (mode == JointMode.BACK || gap)j = gap && mode == JointMode.SEAM ? gapMid(prev.box, it.box) : backFace(it.box, prev.box);
				else j = seam(prev.box, it.box);
			}
			p.joints.add(new double[] {RigGeom.snap(j[0]), RigGeom.snap(j[1]), RigGeom.snap(j[2])});
			p.gap.add(gap);
		}
		// ---- names
		String err = names(p, ordered.size());
		if (err != null)p.error = err;
		return p;
	}

	/** checks the prefix and fills the names (prefix1..prefixN); error text or null */
	static String names(Plan p, int n) {
		p.names.clear();
		String pre = p.prefix;
		if (pre.isEmpty())return PlusDialogs.tr("cpmplus.w4rig.g19.err.prefixEmpty");
		if (RootUtil.part(pre) != null || pre.toLowerCase().endsWith("_dup") || pre.contains("#"))
			return PlusDialogs.trf("cpmplus.w4rig.g19.err.reserved", pre);
		Set<String> used = new HashSet<>();
		if (Group.all != null)for (Group g : Group.all)if (g != null && g.name != null)used.add(g.name.toLowerCase());
		List<String> clash = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Item it = p.items.get(i);
			String name = pre + (i + 1);
			if (RootUtil.part(name) != null || name.toLowerCase().endsWith("_dup"))return PlusDialogs.trf("cpmplus.w4rig.g19.err.reserved", name);
			if (reusable(it.node)) {
				p.names.add(((Group) it.node).name);
				continue;
			}
			if (used.contains(name.toLowerCase()))clash.add(name);
			p.names.add(name);
		}
		if (!clash.isEmpty())return PlusDialogs.trf("cpmplus.w4rig.g19.err.nameUsed", String.join(", ", clash));
		return null;
	}

	private static Object parentRaw(Object o) {
		if (o instanceof Group)return ((Group) o).parent;
		if (o instanceof Cube)return ((Cube) o).parent;
		return null;
	}

	/** name of the first group (g or inside it) with keyframes, or null */
	static String keyedInside(Group g) {
		if (W4RigNatives.hasAnyKeys(g.uuid))return g.name;
		if (g.children == null)return null;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (n instanceof Group) {
				String s = keyedInside((Group) n);
				if (s != null)return s;
			}
		}
		return null;
	}

	/** an existing group used as the link itself: rotation 0, nothing points at it, no special data */
	static boolean reusable(Object node) {
		if (!(node instanceof Group))return false;
		Group g = (Group) node;
		if (!RigGeom.isZero(g.rotation))return false;
		if (g.copyTransform != null && !g.copyTransform.isEmpty())return false;
		GroupData gd = g.getData();
		if (gd.getItemRenderer() != null || gd.isLocked())return false;
		if (Group.all != null)for (Group o : Group.all) {
			if (o != null && o.copyTransform != null && o.copyTransform.contains(g.uuid))return false;
		}
		return true;
	}

	/** shown box of a piece in the (unrotated) space of the parent, cube rotation + inflate included */
	static RigGeom.Box boxInParent(Object node, Group parent) {
		RigGeom.Box b = new RigGeom.Box();
		if (node instanceof Cube) {
			b.addAll(RigGeom.shownCorners((Cube) node, true, parent));
		} else if (node instanceof Group) {
			List<Cube> cubes = new ArrayList<>();
			List<Group> groups = new ArrayList<>();
			RigParts.collect((Group) node, cubes, groups);
			for (Cube c : cubes)if (c.export)b.addAll(RigGeom.shownCorners(c, true, parent));
		}
		return b;
	}

	/** joint between two consecutive pieces that touch / overlap: centre of the intersection */
	static double[] seam(RigGeom.Box a, RigGeom.Box b) {
		double[] r = new double[3];
		for (int i = 0; i < 3; i++) {
			double lo = Math.max(a.min[i], b.min[i]), hi = Math.min(a.max[i], b.max[i]);
			r[i] = (lo + hi) / 2;
		}
		return r;
	}

	/** pieces with a gap: midpoint of the closest points */
	static double[] gapMid(RigGeom.Box a, RigGeom.Box b) {
		double[] pa = new double[3], pb = new double[3];
		for (int i = 0; i < 3; i++) {
			if (a.max[i] < b.min[i]) {
				pa[i] = a.max[i];
				pb[i] = b.min[i];
			} else if (b.max[i] < a.min[i]) {
				pa[i] = a.min[i];
				pb[i] = b.max[i];
			} else {
				double lo = Math.max(a.min[i], b.min[i]), hi = Math.min(a.max[i], b.max[i]);
				pa[i] = pb[i] = (lo + hi) / 2;
			}
		}
		return new double[] {(pa[0] + pb[0]) / 2, (pa[1] + pb[1]) / 2, (pa[2] + pb[2]) / 2};
	}

	/** centre of the face of box b that looks at 'towards' (the dominant axis of the centre difference) */
	static double[] backFace(RigGeom.Box b, RigGeom.Box towards) {
		double[] c = b.center(), t = towards.center();
		int ax = 0;
		double best = -1;
		for (int i = 0; i < 3; i++) {
			double half = Math.max(b.size(i) / 2, 1e-6);
			double v = Math.abs(t[i] - c[i]) / half;
			if (v > best) {
				best = v;
				ax = i;
			}
		}
		double[] r = java.util.Arrays.copyOf(c, 3);
		r[ax] = t[ax] >= c[ax] ? b.max[ax] : b.min[ax];
		return r;
	}

	/** true when the piece touches (gap <= 0.01) one of the parent's own exported cubes that is not a chain piece */
	static boolean touchesParentCubes(Group parent, List<Item> items, Item it) {
		if (parent.children == null)return false;
		Set<Object> chain = new HashSet<>();
		for (Item x : items)chain.add(x.node);
		for (int i = 0; i < parent.children.length; i++) {
			OutlinerNode n = parent.children.getAt(i);
			if (!(n instanceof Cube) || chain.contains(n) || !((Cube) n).export)continue;
			RigGeom.Box cb = new RigGeom.Box().addAll(RigGeom.shownCorners((Cube) n, true, parent));
			if (cb.distance(it.box) <= 0.01)return true;
		}
		return false;
	}

	/**
	 * the first joint: where the first piece touches the rest of the parent's own cubes; else the end of the first
	 * piece opposite to the second one; else the face of the first piece that looks at the parent pivot
	 */
	static double[] firstJoint(Group parent, List<Item> ordered, double[] pivot) {
		Item first = ordered.get(0);
		Set<Object> chain = new HashSet<>();
		for (Item it : ordered)chain.add(it.node);
		// the biggest of the parent's own cubes that the first piece touches (the body / head the piece hangs from)
		RigGeom.Box contact = null;
		double bestW = -1;
		if (parent.children != null) {
			for (int i = 0; i < parent.children.length; i++) {
				OutlinerNode n = parent.children.getAt(i);
				if (!(n instanceof Cube) || chain.contains(n) || !((Cube) n).export)continue;
				RigGeom.Box cb = new RigGeom.Box().addAll(RigGeom.shownCorners((Cube) n, true, parent));
				if (cb.distance(first.box) <= 0.01 && cb.weight(0.25) > bestW) {
					RigGeom.Box inter = new RigGeom.Box();
					double[] lo = new double[3], hi = new double[3];
					for (int a = 0; a < 3; a++) {
						lo[a] = Math.max(cb.min[a], first.box.min[a]);
						hi[a] = Math.min(cb.max[a], first.box.max[a]);
					}
					inter.add(lo).add(hi);
					contact = inter;
					bestW = cb.weight(0.25);
				}
			}
		}
		if (contact != null)return contact.center();
		if (ordered.size() > 1) {
			RigGeom.Box b = first.box, nb = ordered.get(1).box;
			double[] c = b.center(), t = nb.center();
			int ax = 0;
			double best = -1;
			for (int i = 0; i < 3; i++) {
				double v = Math.abs(t[i] - c[i]);
				if (v > best) {
					best = v;
					ax = i;
				}
			}
			double[] r = java.util.Arrays.copyOf(c, 3);
			r[ax] = t[ax] >= c[ax] ? b.min[ax] : b.max[ax];
			return r;
		}
		RigGeom.Box b = first.box;
		double[] c = b.center();
		int ax = 0;
		double best = -1;
		for (int i = 0; i < 3; i++) {
			double half = Math.max(b.size(i) / 2, 1e-6);
			double v = Math.abs(pivot[i] - c[i]) / half;
			if (v > best) {
				best = v;
				ax = i;
			}
		}
		double[] r = b.clamp(pivot);
		r[ax] = pivot[ax] >= c[ax] ? b.max[ax] : b.min[ax];
		return r;
	}

	// ------------------------------------------------------------------ apply

	/** applies the plan with these joints (one undo entry); returns the link groups (first = outermost) */
	public static List<Group> apply(Plan p, List<double[]> joints) {
		if (p.error != null)throw new IllegalStateException(p.error);
		Group parent = p.parent;
		List<Cube> cubes = new ArrayList<>();
		List<Group> groups = new ArrayList<>();
		RigParts.collect(parent, cubes, groups);
		Map<String, List<double[]>> before = new HashMap<>();
		for (Cube c : cubes)before.put(c.uuid, RigGeom.shownCorners(c, true, null));
		List<OutlinerElement> moved = new ArrayList<>();
		List<Group> touched = new ArrayList<>();
		touched.add(parent);
		for (Item it : p.items) {
			if (it.node instanceof Cube)moved.add((Cube) it.node);
			else touched.add((Group) it.node);
		}
		int firstIdx = Integer.MAX_VALUE;
		for (Item it : p.items) {
			int ix = indexIn(parent, it.node);
			if (ix >= 0 && ix < firstIdx)firstIdx = ix;
		}
		final int idx0 = firstIdx == Integer.MAX_VALUE ? -1 : firstIdx;
		List<Group> links = new ArrayList<>();
		List<Group> created = new ArrayList<>();
		Group[] tb = touched.toArray(new Group[0]);
		OutlinerElement[] me = moved.toArray(new OutlinerElement[0]);
		UndoData b4 = PlusUndo.structBefore(tb);
		b4.elements = me;
		PlusUndo.run(PlusDialogs.tr("cpmplus.w4rig.g19.undo"), b4, () -> {
			List<Group> after = new ArrayList<>(touched);
			after.addAll(created);
			return PlusUndo.structAfter(after.toArray(new Group[0]), me, null);
		}, () -> {
			Group prev = parent;
			int sepIdx = idx0;
			for (int i = 0; i < p.items.size(); i++) {
				Item it = p.items.get(i);
				double[] j = joints.get(i);
				Group target = p.chained ? prev : parent;
				int at = target == parent ? (p.chained ? idx0 : sepIdx) : -1;
				Group link;
				if (reusable(it.node)) {
					link = (Group) it.node;
					link.origin.x = (float) j[0];
					link.origin.y = (float) j[1];
					link.origin.z = (float) j[2];
					if (!(i == 0 && target == parent))link.addTo(target, at);
				} else {
					GroupProperties gp = new GroupProperties();
					gp.name = p.names.get(i);
					gp.origin = JsVec3.make((float) j[0], (float) j[1], (float) j[2]);
					gp.rotation = JsVec3.make(0, 0, 0);
					link = new Group(gp).init();
					created.add(link);
					link.addTo(target, at);
					if (it.node instanceof Cube)((Cube) it.node).addTo(link, -1);
					else ((Group) it.node).addTo(link, -1);
				}
				if (!p.chained && sepIdx >= 0)sepIdx++;
				links.add(link);
				prev = link;
			}
			// self-test: nothing may move
			for (Cube c : cubes) {
				List<double[]> a = before.get(c.uuid), b = RigGeom.shownCorners(c, true, null);
				for (int k = 0; k < a.size(); k++) {
					if (RigGeom.dist(a.get(k), b.get(k)) > 1e-4)throw new IllegalStateException("CPM+: building the chain would move '" + c.name + "'");
				}
			}
		});
		try {
			ProjectGenerator.updateAll();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: refresh failed", e);
		}
		return links;
	}

	private static int indexIn(Group parent, Object node) {
		if (parent.children == null)return -1;
		for (int i = 0; i < parent.children.length; i++)if (parent.children.getAt(i) == node)return i;
		return -1;
	}

	// ------------------------------------------------------------------ dialog

	/** CPM+ > Modelagem > 'Montar corrente…' (single = 'Fazer osso desta peça') */
	public static void open(boolean single) {
		List<Object> sel = selection();
		if (sel.isEmpty()) {
			PlusDialogs.block("cpmplus.w4rig.g19.err.empty");
			return;
		}
		if (single && sel.size() > 1)sel = sel.subList(0, 1);
		final List<Object> fsel = new ArrayList<>(sel);
		String def = defaultPrefix(fsel);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, single ? "cpmplus.w4rig.g19.introSingle" : "cpmplus.w4rig.g19.intro"));
		rows.add(FormRow.info("count", null, PlusDialogs.trf("cpmplus.w4rig.g19.count", String.valueOf(fsel.size()))));
		if (!single)rows.add(FormRow.select("order", "cpmplus.w4rig.g19.order", PlusDialogs.options("auto", "cpmplus.w4rig.g19.order.auto",
				"click", "cpmplus.w4rig.g19.order.click", "outliner", "cpmplus.w4rig.g19.order.outliner"), "auto"));
		rows.add(FormRow.select("joint", "cpmplus.w4rig.g19.joint", PlusDialogs.options("seam", "cpmplus.w4rig.g19.joint.seam",
				"center", "cpmplus.w4rig.g19.joint.center", "back", "cpmplus.w4rig.g19.joint.back"), "seam"));
		rows.add(FormRow.text("prefix", "cpmplus.w4rig.g19.prefix", def));
		if (!single)rows.add(FormRow.select("mode", "cpmplus.w4rig.g19.mode", PlusDialogs.options("chain", "cpmplus.w4rig.g19.mode.chain",
				"separate", "cpmplus.w4rig.g19.mode.separate"), "chain"));
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.confirmKey = "cpmplus.w4rig.g19.next";
		PlusDialogs.form("cpmplus_w4rig_chain", single ? "cpmplus.w4rig.g19.titleSingle" : "cpmplus.w4rig.g19.title", rows, fr -> {
			Order ord = single ? Order.CLICK : "click".equals(fr.getString("order")) ? Order.CLICK : "outliner".equals(fr.getString("order")) ? Order.OUTLINER : Order.AUTO;
			JointMode jm = "center".equals(fr.getString("joint")) ? JointMode.CENTER : "back".equals(fr.getString("joint")) ? JointMode.BACK : JointMode.SEAM;
			boolean chained = single || !"separate".equals(fr.getString("mode"));
			Plan p = plan(fsel, ord, jm, fr.getString("prefix"), chained);
			if (p.error != null) {
				PlusDialogs.block(p.error);
				fr.keepOpen();
				return;
			}
			DomGlobal.setTimeout(__ -> preview(p), 10);
		}, o);
	}

	static String defaultPrefix(List<Object> sel) {
		Object first = sel.get(0);
		String n = first instanceof Group ? ((Group) first).name : ((Cube) first).name;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n.length(); i++) {
			char c = n.charAt(i);
			if (Character.isLetter(c) || c == '_')sb.append(c);
		}
		String s = sb.toString();
		while (s.endsWith("_"))s = s.substring(0, s.length() - 1);
		if (s.isEmpty() || RootUtil.part(s) != null)s = PlusDialogs.tr("cpmplus.w4rig.g19.defaultPrefix");
		return s;
	}

	/** step 2: the pieces in order with their joints (editable); orange rows where the order is unclear */
	static void preview(Plan p) {
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w4rig.g19.preview.intro"));
		if (p.hasFork())rows.add(FormRow.htmlInfo("forkAll", null, "<p style='color: var(--color-warning, #e6a23c);'>" +
				PlusDialogs.escMdLines(PlusDialogs.tr("cpmplus.w4rig.g19.fork")) + "</p>"));
		for (int i = 0; i < p.items.size(); i++) {
			Item it = p.items.get(i);
			String label = (i + 1) + ". " + p.names.get(i) + "  <-  " + it.name;
			if (p.fork.get(i))rows.add(FormRow.htmlInfo("f" + i, null, "<p style='color: var(--color-warning, #e6a23c);'>" +
					PlusDialogs.escMdLines(PlusDialogs.trf("cpmplus.w4rig.g19.forkRow", it.name)) + "</p>"));
			if (p.gap.get(i))rows.add(FormRow.info("g" + i, null, PlusDialogs.trf("cpmplus.w4rig.g19.gapRow", it.name)));
			double[] j = p.joints.get(i);
			JsArray<Double> v = new JsArray<>();
			v.push(j[0]);
			v.push(j[1]);
			v.push(j[2]);
			rows.add(FormRow.text("j" + i, label, "").option("type", "vector").option("dimensions", 3.0).option("value", v).option("step", 0.05));
		}
		rows.add(FormRow.info("budget", null, PlusDialogs.trf("cpmplus.w4rig.g19.budget", String.valueOf(newGroups(p)))));
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.confirmKey = "cpmplus.w4rig.g19.apply";
		o.width = 560;
		PlusDialogs.form("cpmplus_w4rig_chain_preview", "cpmplus.w4rig.g19.title", rows, fr -> {
			if (p.hasFork()) {
				PlusDialogs.block("cpmplus.w4rig.g19.forkBlocked");
				fr.keepOpen();
				return;
			}
			List<double[]> js = readJoints(fr, p);
			applyAndReport(p, js);
		}, o);
	}

	static int newGroups(Plan p) {
		int n = 0;
		for (Item it : p.items)if (!reusable(it.node))n++;
		return n;
	}

	static List<double[]> readJoints(FormResult fr, Plan p) {
		List<double[]> js = new ArrayList<>();
		for (int i = 0; i < p.items.size(); i++) {
			double[] j = java.util.Arrays.copyOf(p.joints.get(i), 3);
			Object v = fr.raw("j" + i);
			if (v != null && JsArray.isArray(v)) {
				JsArray<Object> a = Js.uncheckedCast(v);
				for (int k = 0; k < 3 && k < a.length; k++) {
					Object x = a.getAt(k);
					if (x != null && "number".equals(Js.typeof(x)) && !Double.isNaN(Js.asDouble(x)))j[k] = Js.asDouble(x);
				}
			}
			js.add(j);
		}
		return js;
	}

	/** applies and tells the user; a failed self-test / error leaves the project unchanged and says so */
	public static boolean applyAndReport(Plan p, List<double[]> joints) {
		List<Group> links;
		try {
			links = apply(p, joints);
		} catch (Throwable e) {
			DomGlobal.console.error("CPM+: build chain failed", e);
			PlusDialogs.block(PlusDialogs.trf("cpmplus.w4rig.g19.failed", String.valueOf(e.getMessage())));
			return false;
		}
		if (!links.isEmpty())RigParts.select(links.get(0));
		PlusDialogs.done(PlusDialogs.trf("cpmplus.w4rig.g19.done", String.valueOf(links.size())));
		return true;
	}
}
