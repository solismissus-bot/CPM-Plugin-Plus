package com.tom.cpm.blockbench.tools.vis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.ext.W5VisNatives;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.PlusDialogs;

import elemental2.core.JsArray;
import jsinterop.base.Js;

/**
 * CPM+ (W5-VIS): the ONE visibility core every visibility tool uses (G33 'Separar visibilidade', G37 show/hide in an
 * animation and reactions, F3 on/off button, G28 form with its own motion; W7-VIEW G65 later).
 * <p>
 * Why controllers: CPM writes a part's show[] only when some frame differs from its default, and fills a missing
 * show[] with 'visible' when loading (ModelPartAnimation ~679). So an animation that only MOVES a part hidden by default
 * shows it in game (#462), and a moving-only animation can force a part visible over another's toggle (#323). The
 * fix is structural: a pure 'controller' parent group ('&lt;part&gt;_vis': rotation 0, no cubes, only visibility keys)
 * carries 'hidden' and every visibility key; the part itself only moves ({@link #ensureController}).
 * <ul>
 * <li>Visibility keys are always 'step', snapped to 1/snapping, with explicit keys at 0 and at the animation length
 * ({@link #setVisibilityRange}); never on vanilla root groups (CPM can't hide them).</li>
 * <li>Every change runs inside ONE undo entry ({@link #run}) that covers the outliner, the touched groups (hidden flag,
 * copy transform) and the touched animations.</li>
 * </ul>
 */
public final class PlusVisibility {
	/** suffix of a visibility controller group */
	public static final String VIS_SUFFIX = "_vis";
	/** hidden animation property: JSON marker of reactions (G37) and forms (G28) */
	public static final String RULE_PROP = "cpm_plus_rule";
	public static final String CH_VIS = CPMCodec.VISIBILITY;
	private static final double EPS = 1e-4;

	private PlusVisibility() {
	}

	// ------------------------------------------------------------------ structure

	/** true for groups CPM can't hide: the vanilla roots (direct children of the outliner root) */
	public static boolean isRoot(Group g) {
		return g == null || g.parent == null || g.parent == Group.ROOT;
	}

	public static boolean hasDirectCubes(Group g) {
		if (g == null || g.children == null)return false;
		for (int i = 0; i < g.children.length; i++) {
			if (W5VisNatives.isCube(g.children.getAt(i)))return true;
		}
		return false;
	}

	/** visibility keys of g in a (0 when none) */
	public static int visKeyCount(Animation a, Group g) {
		return W5VisNatives.keyCount(a, g, CH_VIS);
	}

	/** true when g is hidden by default or some animation has visibility keys on it */
	public static boolean hasVisLogic(Group g) {
		if (g.hidden)return true;
		for (Animation a : anims())if (visKeyCount(a, g) > 0)return true;
		return false;
	}

	/** true when some animation has a position / rotation / scale / colour key on g */
	public static boolean isMoved(Group g) {
		for (Animation a : anims())if (W5VisNatives.hasKeysExcept(a, g, CH_VIS))return true;
		return false;
	}

	/** true when some animation has any key on g */
	public static boolean isAnimated(Group g) {
		for (Animation a : anims())if (W5VisNatives.hasKeysExcept(a, g, null))return true;
		return false;
	}

	/**
	 * A pure visibility controller of child: not a root, rotation 0, no cubes, child is its only child, only
	 * visibility keys in every animation, no copy transform, no item slot.
	 */
	public static boolean isPureController(Group v, Group child) {
		if (v == null || isRoot(v) || child == null)return false;
		if (v.children == null || v.children.length != 1 || v.children.getAt(0) != child)return false;
		if (v.rotation != null && (v.rotation.x != 0 || v.rotation.y != 0 || v.rotation.z != 0))return false;
		if (v.copyTransform != null && !v.copyTransform.isEmpty())return false;
		try {
			if (v.getData().getItemRenderer() != null)return false;
		} catch (Throwable e) {
		}
		for (Animation a : anims())if (W5VisNatives.hasKeysExcept(a, v, CH_VIS))return false;
		return true;
	}

	/** p's parent when it is a pure controller of p, else null */
	public static Group pureControllerOf(Group p) {
		if (p == null || isRoot(p))return null;
		return isPureController(p.parent, p) ? p.parent : null;
	}

	/** every animation of the project */
	public static List<Animation> anims() {
		List<Animation> l = new ArrayList<>();
		if (Animation.all != null)for (Animation a : Animation.all)if (a != null)l.add(a);
		return l;
	}

	/** animations with a visibility key on any of the groups */
	public static List<Animation> animsWithVisKeys(Collection<Group> groups) {
		List<Animation> l = new ArrayList<>();
		for (Animation a : anims()) {
			for (Group g : groups) {
				if (g != null && visKeyCount(a, g) > 0) {
					l.add(a);
					break;
				}
			}
		}
		return l;
	}

	/** groups whose copy transform copies the visibility ('cv') of p */
	public static List<Group> copyVisFollowers(Group p) {
		List<Group> l = new ArrayList<>();
		for (Group x : Group.all) {
			if (x == p || x.copyTransform == null || x.copyTransform.isEmpty())continue;
			Map<String, Object> s = ctMap(x);
			if (s != null && p.uuid.equals(s.get("uuid")) && Boolean.TRUE.equals(s.get("cv")))l.add(x);
		}
		return l;
	}

	/** true when p itself copies the visibility of another part (Copy Transform 'cv') */
	public static boolean copiesVisibility(Group p) {
		Map<String, Object> s = ctMap(p);
		return s != null && Boolean.TRUE.equals(s.get("cv"));
	}

	/** the parsed Copy Transform of x (cpm_copy_transform JSON), or null */
	static Map<String, Object> ctMap(Group x) {
		if (x.copyTransform == null || x.copyTransform.isEmpty())return null;
		try {
			return JsonUtil.asMap(JsonUtil.parse(x.copyTransform));
		} catch (Throwable e) {
			return null;
		}
	}

	private static boolean onlyCopiesVisibility(Group x) {
		Map<String, Object> s = ctMap(x);
		if (s == null)return false;
		for (String k : new String[] {"px", "py", "pz", "rx", "ry", "rz", "sx", "sy", "sz", "color", "r"})
			if (Boolean.TRUE.equals(s.get(k)))return false;
		return true;
	}

	/** what {@link #ensureController} will touch for p: groups (p, parent, copy-transform followers) and animations */
	public static void addControllerScope(Group p, Edit before) {
		before.addGroup(p);
		if (!isRoot(p))before.addGroup(p.parent);
		for (Group x : copyVisFollowers(p))before.addGroup(x);
		List<Group> l = new ArrayList<>();
		l.add(p);
		Group pc = pureControllerOf(p);
		if (pc != null)l.add(pc);
		for (Animation a : animsWithVisKeys(l))before.addAnim(a);
	}

	/**
	 * G33 rule (inside the caller's {@link #run}, with {@link #addControllerScope} in its before-scope): the group
	 * that carries p's visibility from now on. Reuses p's parent when it is already a pure controller (and the move is
	 * exact), otherwise creates '&lt;p&gt;_vis' (rotation 0, no cubes, pivot = p's pivot) at p's place and moves p into
	 * it. p's 'hidden' flag and EVERY visibility key of p (all animations; time, value, interpolation kept) move to the
	 * controller; p keeps its motion. Copy Transforms that copy p's visibility only are redirected to the controller,
	 * others are reported in e.warnings. Roots and parts that copy another part's visibility are never wrapped (p is
	 * returned).
	 */
	public static Group ensureController(Group p, Edit e) {
		if (isRoot(p))return p;
		if (copiesVisibility(p)) {
			e.warn(PlusDialogs.trf("cpmplus.w5vis.warn.copiesVis", p.name));
			return p;
		}
		Group parent = p.parent;
		Group v = null;
		if (isPureController(parent, p)) {
			if (!hasVisLogic(p))return parent;
			if (!hasVisLogic(parent))v = parent;
		}
		if (v == null) {
			int idx = W5VisNatives.indexInParent(p);
			v = W5VisNatives.newGroupAt(uniqueGroupName(p.name + VIS_SUFFIX), p.origin.x, p.origin.y, p.origin.z, parent, idx);
			W5VisNatives.moveInto(p, v, -1);
			v.isOpen = true;
			e.created(v);
		}
		v.hidden = p.hidden;
		p.hidden = false;
		for (Animation a : anims())W5VisNatives.moveVisKeys(a, p, v);
		for (Animation a : anims()) {
			if (visKeyCount(a, p) != 0)throw new IllegalStateException("CPM+: visibility keys left on " + p.name + " in " + a.name);
		}
		for (Group x : copyVisFollowers(p)) {
			if (onlyCopiesVisibility(x)) {
				Map<String, Object> s = ctMap(x);
				s.put("uuid", v.uuid);
				x.copyTransform = JsonUtil.toJson(s);
				e.warn(PlusDialogs.trf("cpmplus.w5vis.warn.copyVisRedirected", x.name, v.name));
			} else {
				e.warn(PlusDialogs.trf("cpmplus.w5vis.warn.copyVisFollower", x.name, p.name, v.name));
			}
		}
		e.controllers.add(v);
		return v;
	}

	/**
	 * Where a tool puts visibility keys for part g: with separate=false g itself; with separate=true g's existing pure
	 * controller, a new controller when some animation moves g (the G33 bug would hit it), else g itself (no extra
	 * element when it is not needed). Must run inside {@link #run} with {@link #addControllerScope}(g) in scope.
	 */
	public static Group keyTarget(Group g, boolean separate, Edit e) {
		if (!separate || isRoot(g))return g;
		Group pc = pureControllerOf(g);
		if (pc != null && !hasVisLogic(g))return pc;
		if (isMoved(g) || pc != null)return ensureController(g, e);
		return g;
	}

	// ------------------------------------------------------------------ keys

	/** snapping of an animation (frames per second of its grid), 20 when unset */
	public static int snapping(Animation a) {
		int s = a.snapping;
		return s > 0 ? s : 20;
	}

	/** t rounded to the animation's grid 1/snapping */
	public static double snap(double t, int snapping) {
		return Math.round(t * snapping) / (double) snapping;
	}

	/** result of {@link #setVisibilityRange} */
	public static final class Range {
		public double t0, t1, length;
		/** null, or 'subFrame' (the range is shorter than one frame of the grid: nothing changed) */
		public String error;
		public int keys;
	}

	/**
	 * Computes (and with apply=true writes) the visibility keys of target in anim so that target has 'state' from t0
	 * to t1 (seconds, snapped to 1/snapping; t1 &gt;= length = until the end) and outside that range !state (replace)
	 * or what it had before (combine). Keys are step keys at the change points plus explicit keys at 0 and at the
	 * length. A range shorter than one frame gives error 'subFrame' and changes nothing.
	 */
	public static Range setVisibilityRange(Animation anim, Group target, boolean state, double t0, double t1, boolean replace, boolean apply) {
		Range r = new Range();
		int s = snapping(anim);
		double len = anim.length > 0 ? anim.length : 0;
		double a0 = Math.max(0, snap(t0, s));
		double a1 = snap(t1, s);
		if (len <= 0)len = Math.max(a1, 1);
		if (a1 > len)a1 = len;
		r.t0 = a0;
		r.t1 = a1;
		r.length = len;
		if (a1 - a0 < 1.0 / s - 1e-6) {
			r.error = "subFrame";
			return r;
		}
		boolean toEnd = a1 >= len - EPS;
		JsArray<Object> old = W5VisNatives.visKeys(anim, target);
		boolean def = !target.hidden;
		List<Double> times = new ArrayList<>();
		addTime(times, 0);
		addTime(times, a0);
		addTime(times, a1);
		addTime(times, len);
		if (!replace)for (int i = 0; i < old.length; i += 2)addTime(times, Js.asDouble(old.getAt(i)));
		times.sort(Double::compare);
		List<double[]> keys = new ArrayList<>();
		for (double t : times) {
			boolean in = t >= a0 - EPS && (toEnd || t < a1 - EPS);
			boolean v = in ? state : replace ? !state : valueAt(old, t, def);
			keys.add(new double[] {t, v ? 1 : 0});
		}
		// keep the keys at 0 and at the length, and the others only where the value changes
		List<double[]> out = new ArrayList<>();
		for (int i = 0; i < keys.size(); i++) {
			double[] k = keys.get(i);
			boolean edge = i == 0 || Math.abs(k[0] - len) < EPS;
			if (!edge && k[1] == out.get(out.size() - 1)[1] && k[0] < len - EPS)continue;
			out.add(k);
		}
		r.keys = out.size();
		if (apply) {
			double[] tt = new double[out.size()];
			boolean[] vv = new boolean[out.size()];
			for (int i = 0; i < tt.length; i++) {
				tt[i] = out.get(i)[0];
				vv[i] = out.get(i)[1] != 0;
			}
			W5VisNatives.setVisKeys(anim, target, tt, vv);
		}
		return r;
	}

	private static void addTime(List<Double> l, double t) {
		for (double d : l)if (Math.abs(d - t) < EPS)return;
		l.add(t);
	}

	/** step value of [t, v, ...] keys at time t (def before the first key), same rule as the export */
	private static boolean valueAt(JsArray<Object> keys, double t, boolean def) {
		boolean v = def;
		boolean found = false;
		for (int i = 0; i < keys.length; i += 2) {
			double kt = Js.asDouble(keys.getAt(i));
			if (kt <= t + 1 / 1200.0) {
				v = Js.asDouble(keys.getAt(i + 1)) != 0;
				found = true;
			}
		}
		return found ? v : def;
	}

	/** constant visibility v over the whole animation: keys at 0 and at L (reactions, forms) */
	public static void setConstant(Animation anim, Group target, boolean v) {
		double len = anim.length > 0 ? anim.length : 1;
		W5VisNatives.setVisKeys(anim, target, new double[] {0, len}, new boolean[] {v, v});
	}

	// ------------------------------------------------------------------ impact / priority

	/**
	 * Making g hidden by default changes it everywhere: first person, the model menu and every animation without its
	 * own visibility key on g. @return those other animations (except 'except')
	 */
	public static List<Animation> impactList(Group g, Collection<Animation> except) {
		List<Animation> l = new ArrayList<>();
		for (Animation a : anims()) {
			if (except != null && except.contains(a))continue;
			if (visKeyCount(a, g) == 0)l.add(a);
		}
		return l;
	}

	/** animations (minus exclude) with any key on the groups or on one of their ancestors */
	public static Set<Animation> touching(Collection<Group> groups, Collection<Animation> exclude) {
		Set<Group> all = new LinkedHashSet<>();
		for (Group g : groups) {
			Group n = g;
			int guard = 0;
			while (n != null && n != Group.ROOT && guard++ < 1000) {
				all.add(n);
				n = n.parent;
			}
		}
		Set<Animation> out = new LinkedHashSet<>();
		for (Animation a : anims()) {
			if (exclude != null && exclude.contains(a))continue;
			for (Group g : all) {
				if (W5VisNatives.hasKeysExcept(a, g, null)) {
					out.add(a);
					break;
				}
			}
		}
		return out;
	}

	/**
	 * priority above every animation in touching and at least min (VisPriority.nextPriority: never above 127; !ok when
	 * it would be - the caller refuses with a message, nothing is renumbered)
	 */
	public static VisPriority.PriorityPlan priorityAbove(Collection<Animation> touching, int min) {
		return VisPriority.nextPriority(touching, min);
	}

	/** puts the animations a priority plan renumbers into the undo scope */
	public static void addPlanScope(VisPriority.PriorityPlan plan, Edit e) {
		if (plan != null)for (Animation a : plan.renumber.keySet())e.addAnim(a);
	}

	// ------------------------------------------------------------------ names

	/** base when no group has that name, else 'base2', 'base3' ... (ASCII, never a vanilla root name) */
	public static String uniqueGroupName(String base) {
		Set<String> used = new HashSet<>();
		for (Group g : Group.all)used.add(g.name.toLowerCase(Locale.ROOT));
		String b = base;
		if (com.tom.cpm.blockbench.util.RootUtil.part(b) != null)b = b + VIS_SUFFIX;
		if (!used.contains(b.toLowerCase(Locale.ROOT)))return b;
		for (int i = 2; ; i++) {
			String n = b + i;
			if (!used.contains(n.toLowerCase(Locale.ROOT)))return n;
		}
	}

	/** base when no animation has that name, else 'base 2', 'base 3' ... */
	public static String uniqueAnimName(String base) {
		Set<String> used = new HashSet<>();
		for (Animation a : anims())used.add(a.name);
		if (!used.contains(base))return base;
		for (int i = 2; ; i++) {
			String n = base + " " + i;
			if (!used.contains(n))return n;
		}
	}

	/** the first letter upper case ('asas' -&gt; 'Asas') */
	public static String capitalize(String s) {
		if (s == null || s.isEmpty())return s;
		return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
	}

	// ------------------------------------------------------------------ undo

	/** the aspects of one undo entry: what exists before and changes, plus what the body creates / removes */
	public static final class Edit {
		final List<Group> groups = new ArrayList<>();
		final List<OutlinerElement> elements = new ArrayList<>();
		final List<Animation> anims = new ArrayList<>();
		final List<Animation> removed = new ArrayList<>();
		/** controllers made or reused by {@link #ensureController} (in creation order) */
		public final List<Group> controllers = new ArrayList<>();
		/** plain-text notes for the user (copy transform redirected / to check ...) */
		public final List<String> warnings = new ArrayList<>();

		public Edit addGroup(Group g) {
			if (g != null && g != Group.ROOT && !groups.contains(g))groups.add(g);
			return this;
		}

		public Edit addAnim(Animation a) {
			if (a != null && !anims.contains(a))anims.add(a);
			return this;
		}

		public Edit addElement(OutlinerElement c) {
			if (c != null && !elements.contains(c))elements.add(c);
			return this;
		}

		/** a group the body created */
		public void created(Group g) {
			addGroup(g);
		}

		/** an animation the body created */
		public void created(Animation a) {
			addAnim(a);
		}

		/** an existing animation (already in the before-scope) the body removes */
		public void removed(Animation a) {
			removed.add(a);
		}

		public void warn(String s) {
			if (s != null && !warnings.contains(s))warnings.add(s);
		}

		UndoData data(boolean after) {
			UndoData d = new UndoData();
			d.outliner = true;
			d.groups = groups.toArray(new Group[0]);
			d.elements = elements.toArray(new OutlinerElement[0]);
			List<Animation> l = new ArrayList<>(anims);
			if (after)l.removeAll(removed);
			d.animations = l.toArray(new Animation[0]);
			return d;
		}
	}

	/**
	 * ONE undo entry (outliner + scope groups + scope animations + what the body creates). The body gets the same
	 * Edit to register what it creates / removes and its notes. Throws (after cancelling the edit) on failure.
	 */
	public static void run(String label, Edit e, Consumer<Edit> body) {
		UndoData before = e.data(false);
		Object snap = W5VisNatives.snapshotAnims(before.animations);
		PlusUndo.run(label, before, () -> e.data(true), () -> body.accept(e));
		W5VisNatives.attachToLastEntry(snap, W5VisNatives.snapshotAnims(e.data(true).animations));
	}

	/** one undo entry for property changes of existing animations (priority), restored exactly by Ctrl+Z */
	public static void editAnims(Collection<Animation> anims, String label, Runnable body) {
		Animation[] arr = anims.toArray(new Animation[0]);
		Object snap = W5VisNatives.snapshotAnims(arr);
		PlusUndo.editAnimations(anims, label, body);
		W5VisNatives.attachToLastEntry(snap, W5VisNatives.snapshotAnims(arr));
	}

	// ------------------------------------------------------------------ session-only viewport preview ('Testar')

	private static final List<Group> previewShown = new ArrayList<>(), previewHidden = new ArrayList<>();

	/** shows (or hides) the given parts in the viewport only: no undo entry, nothing written to the project */
	public static void preview(Collection<Group> visible, Collection<Group> hidden) {
		W5VisNatives.restoreMeshes();
		previewShown.clear();
		previewHidden.clear();
		if (visible != null)previewShown.addAll(visible);
		if (hidden != null)previewHidden.addAll(hidden);
		reapplyPreview();
	}

	/** ends a {@link #preview}: every mesh shows the element's own visibility again */
	public static void endPreview() {
		if (!previewActive())return;
		previewShown.clear();
		previewHidden.clear();
		W5VisNatives.restoreMeshes();
	}

	public static boolean previewActive() {
		return !previewShown.isEmpty() || !previewHidden.isEmpty();
	}

	/** re-applies a running preview (after Blockbench redrew the visibility, e.g. an animation frame) */
	public static void reapplyPreview() {
		if (!previewShown.isEmpty())W5VisNatives.previewMeshes(previewShown.toArray(new Object[0]), true);
		if (!previewHidden.isEmpty())W5VisNatives.previewMeshes(previewHidden.toArray(new Object[0]), false);
	}

	// ------------------------------------------------------------------ misc

	/** the selected groups (outliner) */
	public static List<Group> selectedGroups() {
		List<Group> l = new ArrayList<>();
		JsArray<Object> s = W5VisNatives.selectedGroups();
		for (int i = 0; i < s.length; i++) {
			Object o = s.getAt(i);
			if (o instanceof Group)l.add((Group) o);
		}
		return l;
	}

	/** the selected cubes (outliner) */
	public static List<Cube> selectedCubes() {
		List<Cube> l = new ArrayList<>();
		JsArray<Object> s = W5VisNatives.selectedCubes();
		for (int i = 0; i < s.length; i++) {
			Object o = s.getAt(i);
			if (o instanceof Cube)l.add((Cube) o);
		}
		return l;
	}

	public static Group byUuid(String uuid) {
		if (uuid == null)return null;
		for (Group g : Group.all)if (uuid.equals(g.uuid))return g;
		return null;
	}

	public static Animation animByUuid(String uuid) {
		if (uuid == null)return null;
		for (Animation a : anims())if (uuid.equals(a.uuid))return a;
		return null;
	}

	/** drops groups that are inside another group of the list (the outer one covers them) */
	public static List<Group> topmost(Collection<Group> groups) {
		List<Group> out = new ArrayList<>();
		for (Group g : groups) {
			boolean inner = false;
			for (Group o : groups) {
				if (o != g && isInside(g, o)) {
					inner = true;
					break;
				}
			}
			if (!inner && !out.contains(g))out.add(g);
		}
		return out;
	}

	/** true when node is below ancestor (any depth) */
	public static boolean isInside(Object node, Group ancestor) {
		Object n = node instanceof Group ? ((Group) node).parent : node instanceof Cube ? ((Cube) node).parent : null;
		int guard = 0;
		while (n != null && n != Group.ROOT && guard++ < 10000) {
			if (n == ancestor)return true;
			n = n instanceof Group ? ((Group) n).parent : null;
		}
		return false;
	}

	static List<OutlinerNode> children(Group g) {
		List<OutlinerNode> l = new ArrayList<>();
		if (g.children != null)for (int i = 0; i < g.children.length; i++)l.add(g.children.getAt(i));
		return l;
	}
}
