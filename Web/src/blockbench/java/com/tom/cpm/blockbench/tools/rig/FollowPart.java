package com.tom.cpm.blockbench.tools.rig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.tools.model.CopyTransformEditor;
import com.tom.cpm.blockbench.tools.model.RigGeom;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.model.RootModelType;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ beta34 'Acompanhar parte do corpo…': the selected parts copy, LIVE in game, the motion of a vanilla part - a
 * leg, an arm, the head, the body, the cape or an elytra wing - through CPM's Copy Transform (so they keep up with the
 * walking speed: Minecraft's limb swing follows the distance walked, an animation can't).
 * <ul>
 * <li>Each part moves into a new adapter group (same place in the outliner, pivot at the part or at the joint of the
 * body part it follows) that carries the copy: non-additive, rotation only, one multiplier. The part keeps its own
 * rest rotation, animations and copy transform (applied after the adapter's, depth-first export order).</li>
 * <li>A copy always maps an axis onto the same axis; to turn a forward swing into a horizontal sweep (spider leg) or
 * a sideways lift, the adapter gets a rest rotation (Z 90 / Y -90) and a compensating child group undoes it at rest,
 * so nothing moves until the source moves.</li>
 * <li>Presets: skirt panel (leg, 60 %, about the hip), shoulder pad (arm X+Z, 50 %, about the shoulder), extra leg
 * (same-side leg, alternating sign front to back, sweep when the leg points sideways), wing on the elytra (X Y Z),
 * coat / cloak on the cape (X, about the cape pivot), gear (200 %, axle X / Y / Z), custom.</li>
 * <li>Checks: never a head copy with a multiplier (CPM #941: the head sometimes turns backwards - use 'Reagir ao
 * jogo'); a part already inside the source moves twice; cape copies may stutter (CPM #721); elytra copies only move
 * with an elytra on; a copy cannot shift the phase (a spider foot sweeps but cannot lift at the right moment). Export
 * check of every copy transform of the project: loops and wrong order.</li>
 * <li>One undo entry (adapters, moved parts, a missing cape / elytra root created like 'Add parts').</li>
 * </ul>
 */
public final class FollowPart {
	public static final String DIALOG_ID = "cpmplus_rigpro_follow";
	public static final String WARN_ORDER = "rigpro.ctOrder", WARN_LOOP = "rigpro.ctLoop", WARN_HEAD = "rigpro.ctHeadMult";
	static final String PREF = "rigpro.follow.";
	static final String[] PRESETS = {"skirt", "pad", "leg", "wing", "coat", "gear", "custom"};
	static final String[] SOURCES = {"auto", "right_leg", "left_leg", "right_arm", "left_arm", "head", "body", "cape", "elytra_left", "elytra_right"};
	static final String[] DIRS = {"same", "opposite", "sweep", "sideways"};
	/** G16 extra on the groups this tool makes: 'adapter' / 'comp' */
	static final String EXTRA = "rigFollow";

	private FollowPart() {
	}

	static String t(String key, Object... args) {
		return args.length == 0 ? PlusDialogs.tr(key) : PlusDialogs.trf(key, args);
	}

	/** {amount %, axes, direction, pivot} of a preset */
	static String[] defaults(String preset) {
		switch (preset != null ? preset : "") {
		case "skirt":
			return new String[] {"60", "x", "same", "joint"};
		case "pad":
			return new String[] {"50", "xz", "same", "joint"};
		case "leg":
			return new String[] {"100", "x", "auto", "own"};
		case "wing":
			return new String[] {"100", "xyz", "same", "own"};
		case "coat":
			return new String[] {"100", "x", "same", "joint"};
		case "gear":
			return new String[] {"200", "x", "same", "own"};
		default:
			return new String[] {"100", "x", "same", "own"};
		}
	}

	// ====================================================================== options and plan

	public static final class Options {
		public String preset = "skirt", source = "auto", direction, pivot = "auto";
		public double amount = Double.NaN;
		public Boolean rx, ry, rz;
		public boolean alternate = true;
		public final List<Group> parts = new ArrayList<>();
	}

	/** one part and the copy it gets */
	public static final class Link {
		public Group part;
		public String source;
		public double mult;
		public String dir;
		/** adapter rest rotation (Blockbench Euler degrees); the compensating group gets the inverse */
		public double[] rest = {0, 0, 0};
		public double[] pivot;
		public boolean rx, ry, rz;
		/** the part already follows: its adapter / compensating group (updated instead of nesting again) */
		public Group adapter, comp;

		boolean needsComp() {
			return Math.abs(rest[0]) > 1e-6 || Math.abs(rest[1]) > 1e-6 || Math.abs(rest[2]) > 1e-6;
		}
	}

	public static final class Plan {
		public final List<Link> links = new ArrayList<>();
		/** cape / elytra roots the project lacks (created like 'Add parts') */
		public final Set<String> missingRoots = new HashSet<>();
		public final Result r = new Result();
	}

	static boolean isRoot(Group g) {
		return g != null && g.parent == Group.ROOT;
	}

	static native String extraOf(Group g, String key) /*-{
		try {
			var d = g && g.cpm_data;
			if (!d) return null;
			var o = JSON.parse(d);
			var v = o && o[key];
			return v == null ? null : String(v);
		} catch (e) {
			return null;
		}
	}-*/;

	static boolean isAdapter(Group g) {
		return g != null && g.parent != Group.ROOT && "adapter".equals(extraOf(g, EXTRA));
	}

	static boolean isComp(Group g) {
		return g != null && g.parent != Group.ROOT && "comp".equals(extraOf(g, EXTRA));
	}

	/** shown centre of the part (world), its pivot when it has no cubes */
	static double[] centre(Group g) {
		List<Cube> cubes = RigMath.cubesOf(g);
		double[] s = new double[3];
		int n = 0;
		for (Cube c : cubes) {
			for (double[] p : RigGeom.shownCorners(c, false, null)) {
				s[0] += p[0];
				s[1] += p[1];
				s[2] += p[2];
				n++;
			}
		}
		if (n == 0)return RigGeom.toSpace(RigGeom.vec(g.origin), RigMath.parent(g), null);
		return new double[] {s[0] / n, s[1] / n, s[2] / n};
	}

	/** +1 = the player's right (Blockbench +X), -1 = left, 0 = middle */
	static int side(Group g) {
		double x = centre(g)[0];
		return x > 0.25 ? 1 : x < -0.25 ? -1 : 0;
	}

	static String autoSource(String preset, Group g) {
		int s = side(g);
		switch (preset) {
		case "pad":
			return s < 0 ? "left_arm" : "right_arm";
		case "wing":
			return s < 0 ? "elytra_left" : "elytra_right";
		case "coat":
			return "cape";
		case "custom":
			return "right_leg";
		default:
			return s < 0 ? "left_leg" : "right_leg";
		}
	}

	static boolean pointsSideways(Group g) {
		double[] d = RigMath.tipDir(g);
		return d != null && Math.abs(d[0]) > Math.abs(d[1]) && Math.abs(d[0]) > Math.abs(d[2]);
	}

	/** sign of the part's tip along X (+1 when it points to the player's right) */
	static int tipSign(Group g) {
		double[] d = RigMath.tipDir(g);
		if (d != null && Math.abs(d[0]) > 1e-3)return d[0] > 0 ? 1 : -1;
		return side(g) < 0 ? -1 : 1;
	}

	static Group sourceGroup(String name) {
		return RootUtil.findRootGroup(name);
	}

	static double[] sourcePivot(String name) {
		Group g = sourceGroup(name);
		if (g != null)return RigGeom.vec(g.origin);
		com.tom.cpm.shared.model.render.VanillaModelPart vp = RootUtil.part(name);
		return vp != null ? RigGeom.vec(RootUtil.rootOrigin(vp, RootUtil.projectSkinType())) : new double[3];
	}

	public static Plan plan(Options o) {
		Plan p = new Plan();
		Result r = p.r;
		List<Group> parts = new ArrayList<>();
		for (Group g : o.parts)if (g != null && !parts.contains(g))parts.add(g);
		if (parts.isEmpty())return fail(p, "noParts");
		for (Group g : parts)if (isRoot(g))return fail(p, "rootPart");
		String preset = o.preset != null ? o.preset : "skirt";
		String[] d = defaults(preset);
		double a = (Double.isNaN(o.amount) ? Double.parseDouble(d[0]) : o.amount) / 100.0;
		boolean rx = o.rx != null ? o.rx : d[1].contains("x"), ry = o.ry != null ? o.ry : d[1].contains("y"), rz = o.rz != null ? o.rz : d[1].contains("z");
		if (!rx && !ry && !rz)return fail(p, "noAxis");
		String dir = o.direction != null ? o.direction : d[2];
		boolean multi = (rx ? 1 : 0) + (ry ? 1 : 0) + (rz ? 1 : 0) > 1;
		String pivotMode = "auto".equals(o.pivot) || o.pivot == null ? d[3] : o.pivot;
		// alternating extra legs: per side, front (-Z) to back, +, -, +, ...
		Map<Group, Integer> alt = new HashMap<>();
		if ("leg".equals(preset) && o.alternate) {
			for (int s = -1; s <= 1; s++) {
				List<Group> same = new ArrayList<>();
				for (Group g : parts)if (side(g) == s)same.add(g);
				same.sort((x, y) -> Double.compare(centre(x)[2], centre(y)[2]));
				for (int i = 0; i < same.size(); i++)alt.put(same.get(i), i % 2 == 0 ? 1 : -1);
			}
		}
		Set<String> notes = new HashSet<>();
		List<Group> inside = new ArrayList<>();
		for (Group g : parts) {
			Link l = new Link();
			l.part = g;
			l.source = "auto".equals(o.source) || o.source == null ? autoSource(preset, g) : o.source;
			if (RootUtil.part(l.source) == null)return fail(p, "noSource");
			String pd = "auto".equals(dir) ? (pointsSideways(g) ? "sweep" : "same") : dir;
			if (multi && ("sweep".equals(pd) || "sideways".equals(pd))) {
				notes.add("dirMulti");
				pd = "same";
			}
			l.dir = pd;
			l.rx = rx;
			l.ry = ry;
			l.rz = rz;
			double m = a;
			if ("opposite".equals(pd))m = -a;
			if ("sweep".equals(pd)) {
				l.rest = new double[] {0, 0, 90};
				l.rx = true;
				l.ry = false;
				l.rz = false;
				m = a * tipSign(g);
			} else if ("sideways".equals(pd)) {
				l.rest = new double[] {0, -90, 0};
				l.rx = true;
				l.ry = false;
				l.rz = false;
				m = a * tipSign(g);
			}
			if (alt.containsKey(g))m *= alt.get(g);
			l.mult = Math.round(m * 1000) / 1000.0;
			l.pivot = "joint".equals(pivotMode) ? sourcePivot(l.source) : RigGeom.vec(g.origin);
			// re-run on a part that already follows: update its adapter instead of nesting a new one
			Group par = RigMath.parent(g);
			if (isComp(par) && isAdapter(RigMath.parent(par))) {
				l.comp = par;
				l.adapter = RigMath.parent(par);
			} else if (isAdapter(par))l.adapter = par;
			if ("head".equals(l.source) && Math.abs(Math.abs(l.mult) - 1) > 1e-6)return fail(p, "headMult");
			Group src = sourceGroup(l.source);
			if (src == null)p.missingRoots.add(l.source);
			else if (RigMath.inside(g, src))inside.add(g);
			if ("cape".equals(l.source))notes.add("cape");
			if (l.source.startsWith("elytra"))notes.add("elytra");
			if ("head".equals(l.source))notes.add("head");
			p.links.add(l);
		}
		if (!inside.isEmpty())r.warn("insideSource", t("cpmplus.rigpro.follow.warn.insideSource", ReactToGame.names(inside)));
		for (String n : new String[] {"dirMulti", "cape", "elytra", "head"})if (notes.contains(n))r.warn(n, t("cpmplus.rigpro.follow.warn." + n));
		if (!p.missingRoots.isEmpty()) {
			List<String> labels = new ArrayList<>();
			for (String s : p.missingRoots)labels.add(t("cpmplus.rigpro.follow.src." + s));
			r.warn("addRoot", t("cpmplus.rigpro.follow.warn.addRoot", String.join(", ", labels)));
		}
		return p;
	}

	static Plan fail(Plan p, String code) {
		p.r.error(code, t("cpmplus.rigpro.follow.err." + code));
		return p;
	}

	// ====================================================================== create (one undo entry)

	static CopyTransformEditor.Ct ct(Link l, String sourceUuid) {
		CopyTransformEditor.Ct c = new CopyTransformEditor.Ct();
		c.uuid = sourceUuid;
		c.set("rx", l.rx).set("ry", l.ry).set("rz", l.rz).set("additive", false);
		c.multiply = l.mult;
		return c;
	}

	static native void setVec(Group g, String field, double x, double y, double z) /*-{
		var v = g[field];
		if (v && v.length >= 3) { v[0] = x; v[1] = y; v[2] = z; } else g[field] = [x, y, z];
	}-*/;

	static native void putExtra(Group g, String key, String value) /*-{
		var o = {};
		try { if (g.cpm_data) o = JSON.parse(g.cpm_data) || {}; } catch (e) { o = {}; }
		o[key] = value;
		g.cpm_data = JSON.stringify(o);
	}-*/;

	static Group newGroup(String name, double[] origin, double[] rot, String extra) {
		GroupProperties gp = new GroupProperties();
		gp.name = ReactToGame.uniqueGroupName(name);
		gp.origin = JsVec3.make((float) origin[0], (float) origin[1], (float) origin[2]);
		gp.rotation = JsVec3.make((float) rot[0], (float) rot[1], (float) rot[2]);
		Group w = new Group(gp).init();
		w.copyTransform = "";
		putExtra(w, EXTRA, extra);
		return w;
	}

	public static Result create(Options o) {
		Plan p = plan(o);
		Result r = p.r;
		if (!r.ok)return r;
		List<Group> groups = new ArrayList<>();
		for (Link l : p.links) {
			for (Group g : new Group[] {l.part, RigMath.parent(l.part), l.adapter, l.comp, l.adapter != null ? RigMath.parent(l.adapter) : null})
				if (g != null && !groups.contains(g))groups.add(g);
		}
		for (Group g : groups)if (g.copyTransform == null || !"string".equals(Js.typeof(g.copyTransform)))g.copyTransform = "";
		List<Group> made = new ArrayList<>();
		List<OutlinerElement> madeCubes = new ArrayList<>();
		List<Texture> madeTex = new ArrayList<>();
		UndoData before = PlusUndo.structAfter(groups, new ArrayList<OutlinerElement>(), null);
		boolean roots = !p.missingRoots.isEmpty();
		if (roots)before.textures = new Texture[0];// a created sheet texture (cape / elytra template) is removed by Ctrl+Z
		String suffix = t("cpmplus.rigpro.follow.adapterSuffix"), compSuffix = t("cpmplus.rigpro.follow.compSuffix");
		PlusUndo.run(t("cpmplus.rigpro.follow.undo"), before, () -> {
			List<Group> gs = new ArrayList<>(groups);
			for (Group g : made)if (!gs.contains(g))gs.add(g);
			UndoData a = PlusUndo.structAfter(gs, madeCubes, madeTex);
			if (roots && a.textures == null)a.textures = new Texture[0];
			return a;
		}, () -> {
			// a cape / elytra root the project lacks: the same as 'Add parts' (vanilla part cube + template sheet)
			if (p.missingRoots.contains("cape"))BBActions.addRootNoUndo(RootGroups.CAPE, madeCubes, madeTex, made);
			if (p.missingRoots.contains("elytra_left") || p.missingRoots.contains("elytra_right"))BBActions.addRootNoUndo(RootGroups.ELYTRA, madeCubes, madeTex, made);
			for (Link l : p.links) {
				Group src = sourceGroup(l.source);
				if (src == null)throw new IllegalStateException("CPM+: no root group '" + l.source + "'");
				String json = CopyTransformEditor.toJson(ct(l, src.uuid));
				double[] inv = {-l.rest[0], -l.rest[1], -l.rest[2]};
				if (l.adapter != null) {
					Group ad = l.adapter;
					setVec(ad, "origin", l.pivot[0], l.pivot[1], l.pivot[2]);
					setVec(ad, "rotation", l.rest[0], l.rest[1], l.rest[2]);
					ad.copyTransform = json;
					if (l.comp != null) {
						setVec(l.comp, "origin", l.pivot[0], l.pivot[1], l.pivot[2]);
						setVec(l.comp, "rotation", inv[0], inv[1], inv[2]);
					} else if (l.needsComp()) {
						Group c = newGroup(l.part.name + "_" + compSuffix, l.pivot, inv, "comp");
						made.add(c);
						ReactToGame.addToAt(c, ad, -1);
						ReactToGame.addToAt(l.part, c, -1);
					}
					continue;
				}
				Object parent = l.part.parent;
				int idx = ReactToGame.indexIn(parent, l.part);
				Group ad = newGroup(l.part.name + "_" + suffix, l.pivot, l.rest, "adapter");
				ad.copyTransform = json;
				made.add(ad);
				ReactToGame.addToAt(ad, parent, idx < 0 ? -1 : idx);
				Group into = ad;
				if (l.needsComp()) {
					Group c = newGroup(l.part.name + "_" + compSuffix, l.pivot, inv, "comp");
					made.add(c);
					ReactToGame.addToAt(c, ad, -1);
					into = c;
				}
				ReactToGame.addToAt(l.part, into, -1);
			}
		});
		r.changed = true;
		List<Object> names = new ArrayList<>();
		for (Group g : made)names.add(g.name);
		List<Object> links = new ArrayList<>();
		for (Link l : p.links) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("part", l.part.name);
			m.put("source", l.source);
			m.put("mult", l.mult);
			m.put("dir", l.dir);
			links.add(m);
		}
		AnimToolSupport.refreshPreview();
		return r.put("made", names).put("links", links).put("rootsAdded", (double) p.missingRoots.size());
	}

	// ====================================================================== preview (what the copy does at a swing)

	/**
	 * The parts at a source swing of sigma degrees (Blockbench X of the source): the source root turns, every part
	 * turns about its adapter pivot by D = R(rest) * Rx(mult * sigma) * R(rest)^T (one rotation axis; the multi-axis
	 * presets move X only in the preview), shown as keys on the part itself (rotation + the pivot offset).
	 */
	static Map<Group, RigPreview.Pose> previewPoses(Plan p, double sigma) {
		Map<Group, RigPreview.Pose> m = new LinkedHashMap<>();
		Set<String> sources = new HashSet<>();
		for (Link l : p.links) {
			Group target = l.adapter != null ? l.adapter : l.part;
			double[] restA = RigGeom.rot(l.rest[0], l.rest[1], l.rest[2]);
			double[] dm = RigGeom.mul(RigGeom.mul(restA, RigMath.rotX(l.rx ? l.mult * sigma : 0)), RigMath.transpose(restA));
			RigPreview.Pose pose = new RigPreview.Pose();
			double[] rest = RigGeom.vec(target.rotation);
			if (l.adapter != null) {
				// the adapter itself turns: its key is the copied rotation
				pose.rot = new double[] {l.rx ? l.mult * sigma : 0, 0, 0};
			} else {
				pose.rot = RigMath.keyFor(rest, RigGeom.mul(dm, RigGeom.rot(rest[0], rest[1], rest[2])));
				double[] o = RigGeom.vec(target.origin);
				double[] rel = {o[0] - l.pivot[0], o[1] - l.pivot[1], o[2] - l.pivot[2]};
				double[] moved = RigGeom.apply(dm, rel);
				pose.pos = new double[] {moved[0] - rel[0], moved[1] - rel[1], moved[2] - rel[2]};
			}
			m.put(target, pose);
			sources.add(l.source);
		}
		for (String s : sources) {
			Group g = sourceGroup(s);
			if (g == null || "head".equals(s) || "body".equals(s))continue;
			RigPreview.Pose sp = new RigPreview.Pose();
			sp.rot = new double[] {sigma, 0, 0};
			m.put(g, sp);
		}
		return m;
	}

	// ====================================================================== export checks (every copy transform)

	static native JsArray<Object> preorderGroups() /*-{
		var out = [];
		var walk = function(list) {
			if (!list) return;
			for (var i = 0; i < list.length; i++) {
				var n = list[i];
				if ($wnd.Group && n instanceof $wnd.Group) { out.push(n); walk(n.children); }
			}
		};
		walk($wnd.Outliner && $wnd.Outliner.root);
		return out;
	}-*/;

	/** {code, target, source} of every copy transform that loops back or reads a copy that is applied after it */
	public static List<String[]> ctProblems() {
		List<String[]> out = new ArrayList<>();
		if (Group.all == null)return out;
		JsArray<Object> pre = preorderGroups();
		Map<String, Integer> order = new HashMap<>();
		for (int i = 0; i < pre.length; i++)order.put(((Group) Js.uncheckedCast(pre.getAt(i))).uuid, i);
		Group head = ReactToGame.headRoot();
		for (Group g : Group.all) {
			if (isRoot(g) || !CopyTransformEditor.has(g))continue;
			CopyTransformEditor.Ct c = CopyTransformEditor.read(g);
			if (c == null || c.uuid == null)continue;
			Group src = CopyTransformEditor.byUuid(c.uuid);
			if (src == null)continue;
			if (head != null && src == head && Math.abs(c.multiply - 1) > 0.01 && (c.get("rx") || c.get("ry") || c.get("rz")))out.add(new String[] {"head", g.name, src.name, g.uuid});
			if (isRoot(src) || !CopyTransformEditor.has(src))continue;
			// a loop: following the sources comes back to g
			Set<String> seen = new HashSet<>();
			Group cur = src;
			boolean loop = false;
			while (cur != null && !isRoot(cur) && seen.add(cur.uuid)) {
				if (cur == g) {
					loop = true;
					break;
				}
				CopyTransformEditor.Ct cc = CopyTransformEditor.read(cur);
				cur = cc != null && cc.uuid != null ? CopyTransformEditor.byUuid(cc.uuid) : null;
			}
			if (loop) {
				out.add(new String[] {"loop", g.name, src.name, g.uuid});
				continue;
			}
			// CPM applies the copies in export order (outliner, depth first): a source with its own copy must come first
			Integer oi = order.get(g.uuid), os = order.get(src.uuid);
			if (oi != null && os != null && os > oi)out.add(new String[] {"order", g.name, src.name, g.uuid});
		}
		return out;
	}

	public static void installChecks() {
		ExportHooks.register(ctx -> {
			for (String[] pr : ctProblems()) {
				String key = "loop".equals(pr[0]) ? WARN_LOOP : "order".equals(pr[0]) ? WARN_ORDER : WARN_HEAD;
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, key, "cpmplus.rigpro.follow.check." + pr[0], pr[1], pr[2]);
				w.addNode(pr[3], pr[1]);
				ctx.add(w);
			}
		});
	}

	// ====================================================================== dialog

	private static final String SUMMARY_ID = "cpmplus_rigpro_follow_summary";
	private static String lastPreset;

	static Options fromForm(FormResult fr, List<Group> parts) {
		Options o = new Options();
		o.parts.addAll(parts);
		o.preset = fr.getString("preset") != null ? fr.getString("preset") : "skirt";
		o.source = fr.getString("source") != null ? fr.getString("source") : "auto";
		o.amount = fr.getDouble("amount", Double.NaN);
		o.direction = fr.getString("dir");
		if (fr.getBool("more")) {
			o.rx = fr.getBool("ax");
			o.ry = fr.getBool("ay");
			o.rz = fr.getBool("az");
			o.pivot = fr.getString("pivot") != null ? fr.getString("pivot") : "auto";
			o.alternate = fr.raw("alternate") == null || fr.getBool("alternate");
		}
		return o;
	}

	/** CPM+ &gt; Modelo / Animação &gt; 'Acompanhar parte do corpo…' (also the group right-click menu) */
	public static void open() {
		List<Group> parts = new ArrayList<>();
		for (Group g : AnimToolSupport.selectedGroups())if (!isRoot(g))parts.add(g);
		if (parts.isEmpty()) {
			PlusDialogs.block(t("cpmplus.rigpro.follow.err.noParts"));
			return;
		}
		// the preview needs Animate mode; a modelling user goes back to the mode he was in when the dialog closes
		String prevMode = modeId();
		ReactToGame.enterAnimateMode();
		String preset = PlusPrefs.get(PREF + "preset", "skirt");
		boolean ok = false;
		for (String x : PRESETS)if (x.equals(preset))ok = true;
		if (!ok)preset = "skirt";
		String[] d = defaults(preset);
		Map<String, String> presetOpts = new LinkedHashMap<>();
		for (String p : PRESETS)presetOpts.put(p, "cpmplus.rigpro.follow.preset." + p);
		Map<String, String> srcOpts = new LinkedHashMap<>();
		for (String s : SOURCES)srcOpts.put(s, "cpmplus.rigpro.follow.src." + s);
		Map<String, String> dirOpts = new LinkedHashMap<>();
		for (String s : DIRS)dirOpts.put(s, "cpmplus.rigpro.follow.dir." + s);
		Map<String, String> dirOptsLeg = new LinkedHashMap<>();
		dirOptsLeg.put("auto", "cpmplus.rigpro.follow.dir.auto");
		dirOptsLeg.putAll(dirOpts);
		Map<String, String> pivOpts = PlusDialogs.options("auto", "cpmplus.rigpro.follow.pivot.auto", "own", "cpmplus.rigpro.follow.pivot.own", "joint", "cpmplus.rigpro.follow.pivot.joint");

		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.rigpro.follow.intro")));
		rows.add(FormRow.info("parts", null, t("cpmplus.rigpro.react.parts", parts.size(), ReactToGame.names(parts))));
		rows.add(FormRow.radio("preset", "cpmplus.rigpro.follow.what", presetOpts, preset));
		for (String p : PRESETS)rows.add(FormRow.info("help_" + p, null, t("cpmplus.rigpro.follow.preset." + p + ".help")).when(fr -> p.equals(fr.getString("preset"))));
		rows.add(FormRow.select("source", "cpmplus.rigpro.follow.source", srcOpts, "auto"));
		rows.add(FormRow.range("amount", "cpmplus.rigpro.follow.amount", Double.parseDouble(d[0]), -200, 300, 5).description("cpmplus.rigpro.follow.amount.desc"));
		rows.add(FormRow.radio("dir", "cpmplus.rigpro.follow.dir", dirOptsLeg, d[2]).when(fr -> !"wing".equals(fr.getString("preset")) && !"pad".equals(fr.getString("preset"))));
		rows.add(FormRow.range("sim", "cpmplus.rigpro.follow.sim", 0, -60, 60, 1).description("cpmplus.rigpro.follow.sim.desc"));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		rows.add(FormRow.info("limit", null, t("cpmplus.rigpro.follow.limit")));
		rows.add(FormRow.checkbox("more", "cpmplus.rigpro.more", false));
		rows.add(FormRow.checkbox("ax", "cpmplus.rigpro.follow.ax", d[1].contains("x")).when(fr -> fr.getBool("more")));
		rows.add(FormRow.checkbox("ay", "cpmplus.rigpro.follow.ay", d[1].contains("y")).when(fr -> fr.getBool("more")));
		rows.add(FormRow.checkbox("az", "cpmplus.rigpro.follow.az", d[1].contains("z")).when(fr -> fr.getBool("more")));
		rows.add(FormRow.radio("pivot", "cpmplus.rigpro.follow.pivot", pivOpts, "auto").when(fr -> fr.getBool("more")));
		rows.add(FormRow.checkbox("alternate", "cpmplus.rigpro.follow.alternate", true).description("cpmplus.rigpro.follow.alternate.desc")
				.when(fr -> fr.getBool("more") && "leg".equals(fr.getString("preset"))));

		lastPreset = preset;
		RigPreview pv = RigPreview.start(null);
		FormOpts fo = new FormOpts();
		fo.width = 560;
		fo.confirmKey = "cpmplus.rigpro.follow.ok";
		fo.onCancel = () -> {
			pv.stop();
			restoreMode(prevMode);
		};
		Dialog[] dlg = new Dialog[1];
		fo.onChange = fr -> {
			String pr = fr.getString("preset");
			if (pr != null && !pr.equals(lastPreset) && dlg[0] != null) {
				// a new preset: its own defaults (amount, direction, axes)
				lastPreset = pr;
				String[] dd = defaults(pr);
				JsPropertyMap<Object> v = JsPropertyMap.of();
				v.set("amount", Double.parseDouble(dd[0]));
				v.set("dir", dd[2]);
				v.set("ax", dd[1].contains("x"));
				v.set("ay", dd[1].contains("y"));
				v.set("az", dd[1].contains("z"));
				try {
					dlg[0].setFormValues(v, false);
				} catch (Throwable e) {
				}
				fr = new FormResult(dlg[0].getFormResult());
			}
			update(fromForm(fr, parts), fr, pv);
		};
		dlg[0] = PlusDialogs.form(DIALOG_ID, "cpmplus.rigpro.follow.title", rows, fr -> {
			pv.stop();
			restoreMode(prevMode);
			Options o = fromForm(fr, parts);
			PlusPrefs.set(PREF + "preset", o.preset);
			Result r = create(o);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				return;
			}
			PlusDialogs.done(t("cpmplus.rigpro.follow.done", parts.size()));
			if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 10000, null);
		}, fo);
		ReactToGame.dockLeft(DIALOG_ID);
		DomGlobal.setTimeout(x -> {
			try {
				FormResult fr = new FormResult(dlg[0].getFormResult());
				update(fromForm(fr, parts), fr, pv);
			} catch (Throwable e) {
			}
		}, 0);
	}

	static native String modeId() /*-{
		try {
			var M = $wnd.Modes;
			return M && M.selected && M.selected.id ? String(M.selected.id) : null;
		} catch (e) {
			return null;
		}
	}-*/;

	/** back to the mode the user was in (no-op when it is the current one or unknown) */
	static native void restoreMode(String id) /*-{
		try {
			var M = $wnd.Modes;
			if (!id || !M || !M.options || !M.options[id] || (M.selected && M.selected.id === id)) return;
			M.options[id].select();
		} catch (e) {}
	}-*/;

	static void update(Options o, FormResult fr, RigPreview pv) {
		Plan p = plan(o);
		StringBuilder sb = new StringBuilder();
		if (!p.r.ok)sb.append("<b style='color:var(--color-close,#f44336)'>").append(PlusDialogs.esc(p.r.errorText())).append("</b><br>");
		else {
			int n = 0;
			for (Link l : p.links) {
				if (n++ >= 6) {
					sb.append("…<br>");
					break;
				}
				sb.append(PlusDialogs.esc(t("cpmplus.rigpro.follow.line", l.part.name, t("cpmplus.rigpro.follow.src." + l.source),
						Math.round(l.mult * 100) + "%", t("cpmplus.rigpro.follow.dir." + l.dir)))).append("<br>");
			}
		}
		for (String w : p.r.warnings)sb.append("<span style='color:var(--color-warning,#e6a23c)'>").append(PlusDialogs.esc(w)).append("</span><br>");
		ReactToGame.setHtml(SUMMARY_ID, sb.toString());
		double sigma = fr.getDouble("sim", 0);
		if (!p.r.ok || Math.abs(sigma) < 1e-6)pv.show(new LinkedHashMap<>(), Double.NaN, Double.NaN);
		else pv.show(previewPoses(p, sigma), Double.NaN, Double.NaN);
	}

	// ====================================================================== QA hook

	static Options optsFromHook(JsPropertyMap<Object> o) {
		Options op = new Options();
		op.parts.addAll(ReactToGame.groups(o, "parts"));
		op.preset = ReactToGame.str(o, "preset", "skirt");
		op.source = ReactToGame.str(o, "source", "auto");
		op.direction = ReactToGame.str(o, "dir", null);
		op.pivot = ReactToGame.str(o, "pivot", "auto");
		op.amount = ReactToGame.num(o, "amount", Double.NaN);
		if (o.get("rx") != null)op.rx = ReactToGame.bool(o, "rx", false);
		if (o.get("ry") != null)op.ry = ReactToGame.bool(o, "ry", false);
		if (o.get("rz") != null)op.rz = ReactToGame.bool(o, "rz", false);
		op.alternate = ReactToGame.bool(o, "alternate", true);
		return op;
	}

	/** follow({op: plan | create | checks | preview | open, ...options}) */
	public static Object hook(JsPropertyMap<Object> o) {
		String op = ReactToGame.str(o, "op", "plan");
		switch (op) {
		case "create":
			return create(optsFromHook(o)).toJs();
		case "checks": {
			List<Object> l = new ArrayList<>();
			for (String[] p : ctProblems()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("code", p[0]);
				m.put("target", p[1]);
				m.put("source", p[2]);
				l.add(m);
			}
			return new Result().put("problems", l).toJs();
		}
		case "preview": {
			Plan p = plan(optsFromHook(o));
			RigPreview pv = RigPreview.start(null);
			pv.show(previewPoses(p, ReactToGame.num(o, "sim", 30)), Double.NaN, Double.NaN);
			Result r = new Result();
			Map<String, Object> rots = new LinkedHashMap<>();
			Map<String, Object> poss = new LinkedHashMap<>();
			for (Link l : p.links) {
				rots.put(l.part.name, ReactToGame.meshRot(l.part.mesh));
				poss.put(l.part.name, meshPos(l.part.mesh));
			}
			r.put("partRot", rots).put("partPos", poss);
			r.put("tempInList", pv.temp() != null && AnimEdit.all().contains(pv.temp()));
			if (!ReactToGame.bool(o, "keep", false)) {
				com.tom.cpm.blockbench.proxy.Animation tmp = pv.temp();
				pv.stop();
				r.put("stoppedTempGone", tmp == null || !AnimEdit.all().contains(tmp));
			}
			return r.toJs();
		}
		case "open":
			open();
			return new Result().toJs();
		case "rows":
			// the export-dialog rows of the copy transform check (headless conversion, no dialog, no fix)
			return com.tom.cpm.blockbench.convert.ProjectConvert.convertHeadless().then(hd -> {
				List<Object> rows = new ArrayList<>();
				for (WarnEntry w : hd.warnings) {
					String k = w.getKey();
					if (!WARN_LOOP.equals(k) && !WARN_ORDER.equals(k) && !WARN_HEAD.equals(k))continue;
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("key", k);
					m.put("message", w.getMessage());
					m.put("severity", w.getSeverity().name());
					rows.add(m);
				}
				return elemental2.promise.Promise.resolve((Object) new Result().put("rows", rows).toJs());
			});
		default: {
			Plan p = plan(optsFromHook(o));
			List<Object> links = new ArrayList<>();
			for (Link l : p.links) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("part", l.part.name);
				m.put("source", l.source);
				m.put("mult", l.mult);
				m.put("dir", l.dir);
				m.put("rest", list(l.rest));
				m.put("pivot", list(l.pivot));
				m.put("rx", l.rx);
				m.put("ry", l.ry);
				m.put("rz", l.rz);
				m.put("update", l.adapter != null);
				links.add(m);
			}
			return p.r.put("links", links).put("missingRoots", new ArrayList<Object>(p.missingRoots)).toJs();
		}
		}
	}

	static List<Object> list(double[] v) {
		List<Object> l = new ArrayList<>();
		for (double d : v)l.add(RigMath.clean(d));
		return l;
	}

	static List<Object> meshPos(Object mesh) {
		List<Object> l = new ArrayList<>();
		for (int i = 0; i < 3; i++)l.add(Math.round(meshPos0(mesh, i) * 1000) / 1000.0);
		return l;
	}

	private static native double meshPos0(Object mesh, int i) /*-{
		if (!mesh || !mesh.position) return 0;
		return i == 0 ? mesh.position.x : i == 1 ? mesh.position.y : mesh.position.z;
	}-*/;
}
