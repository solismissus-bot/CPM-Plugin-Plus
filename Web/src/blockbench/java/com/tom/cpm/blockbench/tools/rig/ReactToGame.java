package com.tom.cpm.blockbench.tools.rig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.tools.anim.AnimxUtil;
import com.tom.cpm.blockbench.tools.budget.ModelBudget;
import com.tom.cpm.blockbench.tools.model.CopyTransformEditor;
import com.tom.cpm.blockbench.tools.model.RigGeom;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.AnimEdit.PriorityPlan;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ beta34 'Reagir ao jogo…' (pose driven by a game value): the selected parts move by themselves with a value of
 * the game - where the head looks (yaw / pitch), health, hunger, air, light, voice, punch, bow, crossbow - or with one
 * of the model's sliders (value layers). What it writes is CPM's own value-driven animation: additive, loop, 2-3
 * linear keys (value 0 = first key, value 1 = last key), never a Copy Transform from the head (CPM #941: a head copy
 * with a multiplier sometimes turns the head backwards).
 * <ul>
 * <li>Presets: neck / spine split over N bones that follows the look (each bone a share of the yaw / pitch), hair and
 * ears that keep hanging when the head looks up or down (exact counter-rotation of the head pitch, CPM #191), wings
 * that droop at low health (rotated towards the ground about their own pivot), glow in the dark (light level drives
 * the visibility, optionally CPM's glow effect), or 'Minha pose' (the user's poses as minimum / middle / maximum).</li>
 * <li>Rotation steps between two keys stay below 170 degrees (CPM wraps rotations into 0..360 and a 180 degree step
 * plays the long way round in game - checked with the in-game validator).</li>
 * <li>CPM #548: an always-playing animation forces the visibility of every part it lists, so a part that is hidden by
 * default (or shown / hidden by another animation) would always show. Such parts move through a new outer group
 * instead ('Mover por um grupo de fora', on by default), which keeps their visibility working.</li>
 * <li>Priority above every non-additive animation of the parts (CPM applies animations by priority: a gesture that
 * sets the same part would otherwise erase the reaction while it plays).</li>
 * <li>Preview: 'Simular' moves the game value in the viewport and turns the vanilla head for the look drivers
 * (RigPreview); the head is back at rest when the dialog closes.</li>
 * <li>One undo entry for the whole creation (outer groups + glow flags + every animation); each 'Usar a pose atual
 * como …' on an existing reaction is one undo entry.</li>
 * </ul>
 */
public final class ReactToGame {
	public static final String DIALOG_ID = "cpmplus_rigpro_react";
	public static final String EDIT_DIALOG_ID = "cpmplus_rigpro_react_edit";
	static final String PREF = "rigpro.react.";
	static final String[] PRESETS = {"neck", "hang", "droop", "glow", "pose"};
	static final String[] SLOTS = {"min", "mid", "max"};
	static final double LEN = 1;
	static final int SNAP = 20;
	/** largest rotation step between two keys (CPM plays 180 degrees the long way round) */
	static final double MAX_STEP = 170;

	private ReactToGame() {
	}

	static String t(String key, Object... args) {
		return args.length == 0 ? PlusDialogs.tr(key) : PlusDialogs.trf(key, args);
	}

	// ====================================================================== drivers

	/** what moves the parts */
	public static final class Driver {
		public final String id, type, slider;
		public final boolean centered, always;
		/** 'x' = head up / down, 'y' = head left / right, 0 = none */
		public final char head;

		Driver(String id, String type, boolean centered, boolean always, char head, String slider) {
			this.id = id;
			this.type = type;
			this.centered = centered;
			this.always = always;
			this.head = head;
			this.slider = slider;
		}

		boolean isSlider() {
			return slider != null || "newSlider".equals(id);
		}

		String langId() {
			return isSlider() ? "slider" : id;
		}

		public String label() {
			if (slider != null)return t("cpmplus.rigpro.drv.slider", slider);
			return t("cpmplus.rigpro.drv." + id);
		}

		public String minText() {
			return t("cpmplus.rigpro.drv." + langId() + ".min");
		}

		public String maxText() {
			return t("cpmplus.rigpro.drv." + langId() + ".max");
		}

		/** Blockbench degrees the vanilla head turns at value v (yaw: + = to the left; pitch: + = up), as in game */
		static double headTurn(double v) {
			return 90 - 180 * v;
		}
	}

	/** {id, cpm type, centered, always playing, head axis} */
	static final String[][] BUILTIN = {
			{"yaw", "head_rotation_yaw", "c", "a", "y"},
			{"pitch", "head_rotation_pitch", "c", "a", "x"},
			{"health", "health", "", "a", ""},
			{"hunger", "hunger", "", "a", ""},
			{"air", "air", "", "a", ""},
			{"light", "light", "", "a", ""},
			{"voice", "speaking", "", "", ""},
			{"punchR", "punch_right", "", "", ""},
			{"punchL", "punch_left", "", "", ""},
			{"bowR", "bow_right", "", "", ""},
			{"bowL", "bow_left", "", "", ""},
			{"xbowR", "crossbow_ch_right", "", "", ""},
			{"xbowL", "crossbow_ch_left", "", "", ""},
	};

	static Driver builtin(String[] d) {
		return new Driver(d[0], d[1], "c".equals(d[2]), "a".equals(d[3]), d[4].isEmpty() ? 0 : d[4].charAt(0), null);
	}

	/** every slider (value layer) id of the project, in animation order */
	public static List<String> sliders() {
		Set<String> s = new LinkedHashSet<>();
		for (Animation a : AnimEdit.all()) {
			Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
			if (tp != null && tp.getKey() == AnimationType.VALUE_LAYER && a.name != null)s.add(idOf(a.name));
		}
		return new ArrayList<>(s);
	}

	static String idOf(String name) {
		int i = name.indexOf('#');
		return i < 0 ? name : name.substring(0, i);
	}

	public static List<Driver> drivers() {
		List<Driver> l = new ArrayList<>();
		for (String[] d : BUILTIN)l.add(builtin(d));
		for (String s : sliders())l.add(new Driver("slider:" + s, "value_layer", false, true, (char) 0, s));
		l.add(new Driver("newSlider", "value_layer", false, true, (char) 0, null));
		return l;
	}

	public static Driver driver(String id) {
		if (id == null)return null;
		for (Driver d : drivers())if (d.id.equals(id))return d;
		return null;
	}

	/** the driver of an existing value-driven animation (head look, health ... or a slider), else null */
	public static Driver driverOf(Animation a) {
		Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
		if (tp == null)return null;
		if (tp.getKey() == AnimationType.VALUE_LAYER)return new Driver("slider:" + idOf(a.name), "value_layer", false, true, (char) 0, idOf(a.name));
		if (tp.getKey() != AnimationType.POSE || tp.getValue() == null || !tp.getValue().hasStateGetter())return null;
		for (String[] d : BUILTIN)if (d[1].equalsIgnoreCase(a.type))return builtin(d);
		return null;
	}

	// ====================================================================== options and plan

	public static final class Options {
		public String preset = "neck";
		public String driver = "yaw";
		public String newSlider;
		public final List<Group> parts = new ArrayList<>();
		public boolean side = true, updown = true, tipMore;
		public double amount = 100;
		public double droop = 45, below = 50;
		public int light = 7;
		public boolean glowFlag = true;
		public boolean wrap = true;
		public String name;
		/** 'pose': slot (min / mid / max) -> part -> pose (Blockbench key values of the part itself) */
		public final Map<String, Map<Group, RigPreview.Pose>> slots = new LinkedHashMap<>();
	}

	/** one animation the tool will create */
	public static final class PlannedAnim {
		public Driver driver;
		public String name;
		public final Map<Group, Map<String, List<KeyData>>> keys = new LinkedHashMap<>();

		void put(Group g, String ch, List<KeyData> ks) {
			keys.computeIfAbsent(g, k -> new LinkedHashMap<>()).put(ch, ks);
		}

		/** pose of every part at value v (keys are linear; visibility steps) */
		Map<Group, RigPreview.Pose> at(double v) {
			Map<Group, RigPreview.Pose> m = new LinkedHashMap<>();
			double t = v * LEN;
			for (Map.Entry<Group, Map<String, List<KeyData>>> e : keys.entrySet()) {
				RigPreview.Pose p = new RigPreview.Pose();
				for (Map.Entry<String, List<KeyData>> c : e.getValue().entrySet()) {
					List<KeyData> ks = c.getValue();
					if (ks.isEmpty())continue;
					if (KeyMath.VIS.equals(c.getKey()))p.vis = KeyMath.visAt(ks, t, true);
					else {
						double[] val = KeyMath.interpolate(ks, t);
						if (KeyMath.ROT.equals(c.getKey()))p.rot = val;
						else if (KeyMath.POS.equals(c.getKey()))p.pos = val;
						else if (KeyMath.SCALE.equals(c.getKey()))p.scl = val;
					}
				}
				m.put(e.getKey(), p);
			}
			return m;
		}
	}

	public static final class Plan {
		public final List<PlannedAnim> anims = new ArrayList<>();
		/** parts that move through a new outer group (CPM #548) */
		public final List<Group> wrap = new ArrayList<>();
		/** parts that get CPM's glow effect on their cubes */
		public final List<Cube> glowCubes = new ArrayList<>();
		public final Result r = new Result();
		public PriorityPlan priority;
		public Driver driver;
		public boolean newSlider;
		public String sliderName;
	}

	static RigPreview.Pose pose(double[] rot) {
		RigPreview.Pose p = new RigPreview.Pose();
		p.rot = rot;
		return p;
	}

	static KeyData key(double t, double[] v) {
		return KeyData.of(RigMath.snap(t, SNAP), "linear", RigMath.clean(v[0]), RigMath.clean(v[1]), RigMath.clean(v[2]));
	}

	static List<KeyData> keys3(double[] a, double[] mid, double[] b) {
		List<KeyData> l = new ArrayList<>();
		l.add(key(0, a));
		if (mid != null)l.add(key(LEN / 2, mid));
		l.add(key(LEN, b));
		return RigMath.splitWide(l, MAX_STEP, SNAP);
	}

	static final double[] ZERO = {0, 0, 0};

	static boolean isZero(double[] v, double def) {
		if (v == null)return true;
		for (double d : v)if (Math.abs(d - def) > 1e-4)return false;
		return true;
	}

	/** true when another animation keys the visibility of g */
	static boolean visKeyedElsewhere(Group g) {
		for (Animation a : AnimEdit.all()) {
			BoneAnimator ba = AnimEdit.animatorOf(a, g);
			if (ba != null && AnimEdit.count(ba, KeyMath.VIS) > 0)return true;
		}
		return false;
	}

	/** parts whose visibility CPM would override (hidden by default / shown or hidden by another animation) */
	static boolean visRisk(Group g) {
		if (g == null || g.parent == Group.ROOT)return false;// roots can't be shown / hidden by animations
		return g.hidden || visKeyedElsewhere(g);
	}

	public static Group headRoot() {
		for (Group g : AnimxUtil.vanillaRoots())if (AnimxUtil.isHead(g))return g;
		return null;
	}

	static String baseName(List<Group> parts) {
		if (parts.isEmpty())return "?";
		String n = parts.get(0).name;
		return parts.size() == 1 ? n : n + " +" + (parts.size() - 1);
	}

	static String uniq(String n) {
		return AnimEdit.byName(n) != null ? AnimEdit.uniqueAnimName(n, "") : n;
	}

	/** 'Asa#cauda' -> 'Asa#cauda 2' ...: the slider id before '#' never changes */
	static String uniqCompanion(String n) {
		if (AnimEdit.byName(n) == null)return n;
		int k = 2;
		while (AnimEdit.byName(n + " " + k) != null && k < 1000)k++;
		return n + " " + k;
	}

	/** every planned key, never applied: the dialog preview and the creation use the same plan */
	public static Plan plan(Options o) {
		Plan p = new Plan();
		Result r = p.r;
		List<Group> parts = new ArrayList<>();
		for (Group g : o.parts)if (g != null && !parts.contains(g))parts.add(g);
		if (parts.isEmpty())return failed(p, "noParts");
		Group head = headRoot();
		String preset = o.preset != null ? o.preset : "neck";
		Driver d;
		switch (preset) {
		case "neck":
			d = driver(o.side || !o.updown ? "yaw" : "pitch");
			break;
		case "hang":
			d = driver("pitch");
			break;
		case "droop":
			d = driver("health");
			break;
		case "glow":
			d = driver("light");
			break;
		default:
			d = driver(o.driver);
		}
		if (d == null)return failed(p, "noDriver");
		p.driver = d;
		if ("newSlider".equals(d.id)) {
			String nm = o.newSlider != null ? o.newSlider.trim() : "";
			if (nm.isEmpty())nm = t("cpmplus.rigpro.react.newSliderDefault");
			if (nm.indexOf('#') >= 0 || nm.indexOf(':') >= 0)return failed(p, "sliderName");
			p.newSlider = true;
			p.sliderName = sliders().contains(nm) ? null : nm;
			if (p.sliderName == null)return failed(p, "sliderTaken");
		} else if (d.slider != null)p.sliderName = d.slider;
		for (Group g : parts) {
			if (("neck".equals(preset) || "hang".equals(preset)) && g == head)return failed(p, "headItself");
		}
		// CPM #548: parts whose visibility the always-playing animation would override move through an outer group
		boolean keysVis = "glow".equals(preset);
		for (Group g : parts)if (!keysVis && visRisk(g)) {
			if (o.wrap)p.wrap.add(g);
			else r.warn("visRisk", t("cpmplus.rigpro.react.warn.visRiskOff", g.name));
		}
		if (!p.wrap.isEmpty())r.warn("wrap", t("cpmplus.rigpro.react.warn.wrap", names(p.wrap)));
		double a = Math.max(0, Math.min(100, o.amount)) / 100.0;
		String base = baseName(parts);
		switch (preset) {
		case "neck": {
			if (!o.side && !o.updown)return failed(p, "neckNothing");
			List<Group> chain = RigMath.chainOrder(parts);
			int n = chain.size();
			double sum = 0;
			for (int i = 1; i <= n; i++)sum += o.tipMore ? i : 1;
			List<Group> inHead = new ArrayList<>();
			for (Group g : chain)if (head != null && RigMath.inside(g, head))inHead.add(g);
			if (!inHead.isEmpty())r.warn("insideHead", t("cpmplus.rigpro.react.warn.insideHead", names(inHead)));
			if (o.side) {
				PlannedAnim pa = new PlannedAnim();
				pa.driver = driver("yaw");
				pa.name = o.name != null && !o.name.trim().isEmpty() ? o.name.trim() : t("cpmplus.rigpro.react.name.neckYaw", base);
				for (int i = 0; i < n; i++) {
					double w = (o.tipMore ? i + 1 : 1) / sum;
					double deg = 90 * a * w;
					pa.put(chain.get(i), KeyMath.ROT, keys3(new double[] {0, deg, 0}, ZERO, new double[] {0, -deg, 0}));
				}
				p.anims.add(pa);
			}
			if (o.updown) {
				PlannedAnim pa = new PlannedAnim();
				pa.driver = driver("pitch");
				String nm = t("cpmplus.rigpro.react.name.neckPitch", base);
				if (!o.side && o.name != null && !o.name.trim().isEmpty())nm = o.name.trim();
				pa.name = nm;
				for (int i = 0; i < n; i++) {
					double w = (o.tipMore ? i + 1 : 1) / sum;
					double deg = 90 * a * w;
					pa.put(chain.get(i), KeyMath.ROT, keys3(new double[] {deg, 0, 0}, ZERO, new double[] {-deg, 0, 0}));
				}
				p.anims.add(pa);
			}
			break;
		}
		case "hang": {
			PlannedAnim pa = new PlannedAnim();
			pa.driver = d;
			pa.name = name(o, t("cpmplus.rigpro.react.name.hang", base));
			List<Group> outside = new ArrayList<>();
			double[] hw = head != null ? RigMath.worldRot(head) : RigMath.ID;
			for (Group g : parts) {
				if (head == null || !RigMath.inside(g, head))outside.add(g);
				boolean wrapped = p.wrap.contains(g);
				double[] rest = wrapped ? ZERO : RigGeom.vec(g.rotation);
				double[] mRest = RigGeom.rot(rest[0], rest[1], rest[2]);
				// the parent frame seen from the head (Q), so the counter turn is about the HEAD's own X axis
				double[] q = RigGeom.mul(RigMath.transpose(hw), RigMath.worldRot(RigMath.parent(g)));
				double[][] ks = new double[3][];
				double[] vals = {90 * a, 0, -90 * a};
				for (int s = 0; s < 3; s++) {
					double[] counter = RigGeom.mul(RigGeom.mul(RigMath.transpose(q), RigMath.rotX(-vals[s])), q);
					ks[s] = RigMath.keyFor(rest, RigGeom.mul(counter, mRest));
				}
				pa.put(g, KeyMath.ROT, keys3(ks[0], ks[1], ks[2]));
			}
			if (!outside.isEmpty())r.warn("notInHead", t("cpmplus.rigpro.react.warn.notInHead", names(outside)));
			p.anims.add(pa);
			break;
		}
		case "droop": {
			PlannedAnim pa = new PlannedAnim();
			pa.driver = d;
			pa.name = name(o, t("cpmplus.rigpro.react.name.droop", base));
			double ang = Math.max(1, Math.min(90, o.droop));
			double below = Math.max(5, Math.min(100, o.below)) / 100.0;
			List<Group> noTip = new ArrayList<>(), hanging = new ArrayList<>();
			for (Group g : parts) {
				double[] dir = RigMath.tipDir(g);
				if (dir == null) {
					noTip.add(g);
					continue;
				}
				double[] pw = RigMath.worldRot(RigMath.parent(g));
				double[] down = RigGeom.apply(RigMath.transpose(pw), new double[] {0, -1, 0});
				double[] axis = RigMath.norm(RigMath.cross(dir, down));
				double[] dn = RigMath.norm(dir);
				if (axis == null || dn == null || (dn[0] * down[0] + dn[1] * down[1] + dn[2] * down[2]) > 0.95) {
					hanging.add(g);
					continue;
				}
				boolean wrapped = p.wrap.contains(g);
				double[] rest = wrapped ? ZERO : RigGeom.vec(g.rotation);
				double[] mRest = RigGeom.rot(rest[0], rest[1], rest[2]);
				double[] k = RigMath.keyFor(rest, RigGeom.mul(RigMath.axisAngle(axis, ang), mRest));
				List<KeyData> ks = new ArrayList<>();
				ks.add(key(0, k));
				if (below < 0.999)ks.add(key(Math.max(0.05, RigMath.snap(below * LEN, SNAP)), ZERO));
				ks.add(key(LEN, ZERO));
				pa.put(g, KeyMath.ROT, RigMath.splitWide(ks, MAX_STEP, SNAP));
			}
			if (!noTip.isEmpty())r.warn("noTip", t("cpmplus.rigpro.react.warn.noTip", names(noTip)));
			if (!hanging.isEmpty())r.warn("hanging", t("cpmplus.rigpro.react.warn.hanging", names(hanging)));
			if (pa.keys.isEmpty())return failed(p, "droopNothing");
			p.anims.add(pa);
			break;
		}
		case "glow": {
			PlannedAnim pa = new PlannedAnim();
			pa.driver = d;
			pa.name = name(o, t("cpmplus.rigpro.react.name.glow", base));
			int lv = Math.max(0, Math.min(14, o.light));
			double hideAt = RigMath.snap((lv + 0.5) / 15.0 * LEN, SNAP);
			for (Group g : parts) {
				List<KeyData> ks = new ArrayList<>();
				ks.add(KeyData.visKey(0, true));
				ks.add(KeyData.visKey(hideAt, false));
				pa.put(g, KeyMath.VIS, ks);
				if (o.glowFlag)for (Cube c : RigMath.cubesOf(g))if (!c.glow && !p.glowCubes.contains(c))p.glowCubes.add(c);
			}
			p.anims.add(pa);
			break;
		}
		default: {
			PlannedAnim pa = new PlannedAnim();
			pa.driver = d;
			String dflt = d.isSlider() ? (p.newSlider ? p.sliderName : p.sliderName + "#" + base) : t("cpmplus.rigpro.react.name.pose", base, d.label());
			pa.name = name(o, dflt);
			// a slider's animations share its button by the text before '#' (CPM's exporter): keep that id
			if (p.newSlider)pa.name = p.sliderName;
			else if (d.isSlider() && !idOf(pa.name).equals(p.sliderName))pa.name = p.sliderName + "#" + pa.name;
			for (Group g : parts) {
				boolean wrapped = p.wrap.contains(g);
				RigPreview.Pose mn = slotPose(o, "min", g), md = slotPose(o, "mid", g), mx = slotPose(o, "max", g);
				boolean anyMid = md != null || d.centered;
				for (String ch : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE}) {
					double def = KeyMath.SCALE.equals(ch) ? 1 : 0;
					double[] vMin = val(mn, ch), vMid = val(md, ch), vMax = val(mx, ch);
					boolean used = !isZero(vMin, def) || !isZero(vMid, def) || !isZero(vMax, def);
					if (!used && !KeyMath.ROT.equals(ch))continue;
					double[] rest = {def, def, def};
					double[] a0 = vMin != null ? vMin : rest, a2 = vMax != null ? vMax : rest;
					double[] a1 = vMid != null ? vMid : anyMid ? (d.centered ? rest : null) : null;
					if (wrapped && KeyMath.ROT.equals(ch)) {
						a0 = toWrapper(g, a0);
						a2 = toWrapper(g, a2);
						if (a1 != null)a1 = toWrapper(g, a1);
					}
					pa.put(g, ch, keys3(a0, a1, a2));
				}
			}
			p.anims.add(pa);
		}
		}
		for (PlannedAnim pa : p.anims)pa.name = pa.driver.isSlider() && pa.name.indexOf('#') > 0 ? uniqCompanion(pa.name) : uniq(pa.name);
		// above every non-additive animation of the parts (CPM applies animations by priority)
		List<Animation> touching = new ArrayList<>();
		for (Animation an : AnimEdit.all()) {
			if (an.additive)continue;
			for (Group g : parts) {
				BoneAnimator ba = AnimEdit.animatorOf(an, g);
				if (ba != null && ba.hasTransform()) {
					touching.add(an);
					break;
				}
			}
		}
		p.priority = AnimEdit.nextPriority(touching);
		if (!p.priority.ok)return failed(p, "priority");
		return p;
	}

	static RigPreview.Pose slotPose(Options o, String slot, Group g) {
		Map<Group, RigPreview.Pose> m = o.slots.get(slot);
		return m != null ? m.get(g) : null;
	}

	static double[] val(RigPreview.Pose p, String ch) {
		if (p == null)return null;
		return KeyMath.ROT.equals(ch) ? p.rot : KeyMath.POS.equals(ch) ? p.pos : p.scl;
	}

	/** the outer group's key that shows the same turn as key k on the part itself: W = R(rest + k) * R(rest)^T */
	static double[] toWrapper(Group g, double[] k) {
		double[] rest = RigGeom.vec(g.rotation);
		double[] w = RigGeom.mul(RigGeom.rot(rest[0] + k[0], rest[1] + k[1], rest[2] + k[2]), RigMath.transpose(RigGeom.rot(rest[0], rest[1], rest[2])));
		return RigMath.keyFor(ZERO, w);
	}

	static String name(Options o, String dflt) {
		return o.name != null && !o.name.trim().isEmpty() ? o.name.trim() : dflt;
	}

	static Plan failed(Plan p, String code) {
		p.r.error(code, t("cpmplus.rigpro.react.err." + code));
		return p;
	}

	static String names(Collection<Group> gs) {
		List<String> l = new ArrayList<>();
		for (Group g : gs) {
			if (l.size() >= 5) {
				l.add("+" + (gs.size() - 5));
				break;
			}
			l.add(g.name);
		}
		return String.join(", ", l);
	}

	// ====================================================================== create (one undo entry)

	static String uniqueGroupName(String base) {
		Set<String> used = new HashSet<>();
		for (Group g : Group.all)if (g.name != null)used.add(g.name.toLowerCase());
		String n = base;
		int k = 2;
		while (used.contains(n.toLowerCase()) && k < 10000)n = base + k++;
		return n;
	}

	static native int indexIn(Object parent, Object node) /*-{
		var list = parent === 'root' ? ($wnd.Outliner && $wnd.Outliner.root) : (parent && parent.children);
		return list ? list.indexOf(node) : -1;
	}-*/;

	static native void addToAt(Object node, Object parent, int index) /*-{
		node.addTo(parent, index);
	}-*/;

	/** a new outer group around g (same pivot, no rotation), placed where g was; g moves into it */
	static Group wrapper(Group g) {
		Object parent = g.parent;
		int idx = indexIn(parent, g);
		GroupProperties gp = new GroupProperties();
		gp.name = uniqueGroupName(g.name + "_" + t("cpmplus.rigpro.react.wrapSuffix"));
		gp.origin = JsVec3.make(g.origin.x, g.origin.y, g.origin.z);
		gp.rotation = JsVec3.make(0, 0, 0);
		Group w = new Group(gp).init();
		addToAt(w, parent, idx < 0 ? -1 : idx);
		addToAt(g, w, -1);
		return w;
	}

	static native void copyCpmProps(Object src, Object dst) /*-{
		var props = $wnd.Animation && $wnd.Animation.properties;
		if (!props) return;
		for (var k in props) {
			if (k.indexOf('cpm_') !== 0) continue;
			var v = src[k];
			dst[k] = (v && typeof v === 'object') ? JSON.parse(JSON.stringify(v)) : v;
		}
	}-*/;

	static native void addMarker(Animation a, double time, String name, int color) /*-{
		try {
			if (!a.markers || !$wnd.TimelineMarker) return;
			a.markers.push(new $wnd.TimelineMarker({time: time, color: color, name: name}));
		} catch (e) {}
	}-*/;

	static Animation sliderSource(String id) {
		for (Animation a : AnimEdit.all()) {
			Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
			if (tp != null && tp.getKey() == AnimationType.VALUE_LAYER && a.name != null && idOf(a.name).equals(id))return a;
		}
		return null;
	}

	public static Result create(Options o) {
		Plan p = plan(o);
		Result r = p.r;
		if (!r.ok)return r;
		List<Group> groups = new ArrayList<>();
		for (Group g : p.wrap) {
			if (!groups.contains(g))groups.add(g);
			if (g.parent instanceof Group && g.parent != Group.ROOT && !groups.contains(g.parent))groups.add(g.parent);
		}
		boolean struct = !p.wrap.isEmpty() || !p.glowCubes.isEmpty();
		List<Animation> renumbered = new ArrayList<>(p.priority.renumber.keySet());
		List<Animation> made = new ArrayList<>();
		List<Group> madeGroups = new ArrayList<>();
		UndoData before = struct ? PlusUndo.structAfter(groups, p.glowCubes, null) : new UndoData();
		before.animations = renumbered.toArray(new Animation[0]);
		Animation slider = p.sliderName != null && !p.newSlider ? sliderSource(p.sliderName) : null;
		PlusUndo.run(t("cpmplus.rigpro.react.undo"), before, () -> {
			List<Group> gs = new ArrayList<>(groups);
			gs.addAll(madeGroups);
			UndoData d = struct ? PlusUndo.structAfter(gs, p.glowCubes, null) : new UndoData();
			List<Animation> an = new ArrayList<>(renumbered);
			an.addAll(made);
			d.animations = an.toArray(new Animation[0]);
			return d;
		}, () -> {
			p.priority.applyRenumber();
			Map<Group, Group> target = new LinkedHashMap<>();
			for (Group g : p.wrap) {
				Group w = wrapper(g);
				madeGroups.add(w);
				target.put(g, w);
			}
			for (Cube c : p.glowCubes)c.glow = true;
			for (PlannedAnim pa : p.anims) {
				Animation a = AnimxUtil.newAnimation(pa.name, pa.driver.type, "loop", LEN, SNAP);
				if (slider != null)copyCpmProps(slider, a);
				a.type = pa.driver.type;
				a.additive = true;
				a.setPriority(p.priority.value);
				a.setCpmIntType(null);
				a.setCpmFrames(0);
				a.setCpmDuration(0);
				if (p.newSlider) {
					a.setMaxValue(100);
					a.setInterpolateVal(true);
					a.setLayerDefault(0);
				}
				a.loop = "loop";
				a.length = (float) LEN;
				for (Map.Entry<Group, Map<String, List<KeyData>>> e : pa.keys.entrySet()) {
					Group g = target.getOrDefault(e.getKey(), e.getKey());
					BoneAnimator ba = a.getBoneAnimator(g);
					for (Map.Entry<String, List<KeyData>> c : e.getValue().entrySet())AnimEdit.writeKeys(ba, c.getKey(), c.getValue(), true);
				}
				addMarker(a, 0, pa.driver.minText(), 3);
				if (pa.driver.centered)addMarker(a, LEN / 2, t("cpmplus.rigpro.slot.mid"), 1);
				addMarker(a, LEN, pa.driver.maxText(), 2);
				made.add(a);
			}
		});
		r.changed = true;
		List<Object> names = new ArrayList<>();
		for (Animation a : made)names.add(a.name);
		List<Object> wraps = new ArrayList<>();
		for (Group g : madeGroups)wraps.add(g.name);
		r.put("names", names).put("wrappers", wraps).put("priority", (double) p.priority.value).put("glowCubes", (double) p.glowCubes.size());
		if (!made.isEmpty()) {
			try {
				made.get(0).select();
				if ("pose".equals(o.preset))Timeline.setTime(LEN);
			} catch (Throwable e) {
			}
		}
		AnimToolSupport.refreshPreview();
		return r;
	}

	// ====================================================================== capture into an existing reaction

	static double slotTime(String slot) {
		return "min".equals(slot) ? 0 : "mid".equals(slot) ? LEN / 2 : LEN;
	}

	/** parts the edit view works on: the selection, else every part with transform keys in a */
	static List<Group> editParts(Animation a) {
		List<Group> l = new ArrayList<>();
		for (Group g : AnimToolSupport.selectedGroups())if (!l.contains(g))l.add(g);
		if (!l.isEmpty())return l;
		for (BoneAnimator ba : AnimxUtil.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g != null && ba.hasTransform() && !l.contains(g))l.add(g);
		}
		return l;
	}

	/** pose of g in animation a at time t (Blockbench key values; rest when the channel has no keys) */
	static RigPreview.Pose poseAt(Animation a, Group g, double t) {
		RigPreview.Pose p = new RigPreview.Pose();
		BoneAnimator ba = a != null ? AnimEdit.animatorOf(a, g) : null;
		p.rot = AnimEdit.valueOrRest(ba, KeyMath.ROT, t);
		p.pos = AnimEdit.valueOrRest(ba, KeyMath.POS, t);
		p.scl = AnimEdit.valueOrRest(ba, KeyMath.SCALE, t);
		return p;
	}

	/**
	 * 'Usar a pose atual como mínimo / meio / máximo' on an existing reaction: the pose the parts have at time 'at'
	 * becomes the key of the slot; the key the user made at 'at' (not a slot) is removed, so the reaction stays a clean
	 * 2-3 key mapping. One undo entry.
	 */
	public static Result capture(Animation a, String slot, List<Group> parts, double at) {
		Result r = new Result();
		if (a == null || driverOf(a) == null)return r.error("notReaction", t("cpmplus.rigpro.react.err.notReaction"));
		if (parts.isEmpty())return r.error("noParts", t("cpmplus.rigpro.react.err.noParts"));
		double st = slotTime(slot) * (a.length > 0 ? a.length / LEN : 1);
		Map<Group, RigPreview.Pose> poses = new LinkedHashMap<>();
		for (Group g : parts)poses.put(g, poseAt(a, g, at));
		boolean nearSlot = false;
		for (String s : SLOTS)if (Math.abs(at - slotTime(s) * (a.length > 0 ? a.length / LEN : 1)) < KeyMath.BB_EPS)nearSlot = true;
		final boolean removeAt = !nearSlot;
		List<Animation> one = new ArrayList<>();
		one.add(a);
		int[] n = {0};
		PlusUndo.run(t("cpmplus.rigpro.react.undoCapture"), AnimxUtil.track(UndoData.make(a), one), () -> AnimxUtil.track(UndoData.make(a), one), () -> {
			for (Map.Entry<Group, RigPreview.Pose> e : poses.entrySet()) {
				Group g = e.getKey();
				RigPreview.Pose p = e.getValue();
				BoneAnimator ba = a.getBoneAnimator(g);
				for (String ch : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE}) {
					double def = KeyMath.SCALE.equals(ch) ? 1 : 0;
					double[] v = val(p, ch);
					boolean has = AnimEdit.count(ba, ch) > 0;
					if (!has && isZero(v, def))continue;
					if (removeAt)AnimEdit.removeNear(ba, ch, java.util.Collections.singletonList(at), KeyMath.BB_EPS);
					List<KeyData> ks = new ArrayList<>();
					ks.add(KeyData.of(st, "linear", RigMath.clean(v[0]), RigMath.clean(v[1]), RigMath.clean(v[2])));
					n[0] += AnimEdit.writeKeys(ba, ch, ks, false);
					if (KeyMath.ROT.equals(ch))splitChannel(ba);
				}
			}
		});
		r.changed = n[0] > 0;
		AnimToolSupport.refreshPreview();
		return r.put("keys", (double) n[0]).put("slotTime", st);
	}

	/** rotation channel of linear keys: midpoints where a step reaches MAX_STEP */
	static void splitChannel(BoneAnimator ba) {
		List<KeyData> ks = AnimEdit.readKeys(ba, KeyMath.ROT);
		if (ks.size() < 2)return;
		for (KeyData k : ks)if (!"linear".equals(k.interp) || k.molang || k.v.length != 1)return;
		ks.sort((x, y) -> Double.compare(x.time, y.time));
		List<KeyData> sp = RigMath.splitWide(ks, MAX_STEP, SNAP);
		if (sp.size() != ks.size())AnimEdit.writeKeys(ba, KeyMath.ROT, sp, true);
	}

	// ====================================================================== budget

	/** distinct pose / gesture / layer / slider ids of the project (the 253 cap) */
	public static int usedIds() {
		Set<String> ids = new HashSet<>();
		for (Animation a : AnimEdit.all()) {
			Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
			if (tp == null || a.name == null)continue;
			AnimationType k = tp.getKey();
			if (k == AnimationType.CUSTOM_POSE || k == AnimationType.GESTURE || k.isLayer())ids.add(idOf(a.name));
		}
		return ids.size();
	}

	static String budgetText(Plan p) {
		int used = usedIds();
		String what = p != null && p.newSlider ? t("cpmplus.rigpro.react.budget.one") : t("cpmplus.rigpro.react.budget.none");
		return t("cpmplus.rigpro.react.budget", used, ModelBudget.MAX_ANIM_IDS, what);
	}

	/** copy transforms that copy the vanilla head with a multiplier (CPM #941) */
	public static List<Group> headCopiesWithMultiplier() {
		List<Group> l = new ArrayList<>();
		Group head = headRoot();
		if (head == null || Group.all == null)return l;
		for (Group g : Group.all) {
			CopyTransformEditor.Ct c = CopyTransformEditor.read(g);
			if (c == null || !head.uuid.equals(c.uuid))continue;
			if (Math.abs(c.multiply - 1) > 0.01 && (c.get("rx") || c.get("ry") || c.get("rz")))l.add(g);
		}
		return l;
	}

	// ====================================================================== dialog

	private static final String SUMMARY_ID = "cpmplus_rigpro_react_summary";
	private static final String SLOTS_ID = "cpmplus_rigpro_react_slots";

	static Options fromForm(FormResult fr, List<Group> parts, Options keep) {
		Options o = new Options();
		o.parts.addAll(parts);
		o.preset = fr.getString("preset") != null ? fr.getString("preset") : "neck";
		o.driver = fr.getString("driver") != null ? fr.getString("driver") : "yaw";
		o.newSlider = fr.getString("newSlider");
		o.side = fr.raw("side") == null || fr.getBool("side");
		o.updown = fr.raw("updown") == null || fr.getBool("updown");
		o.amount = fr.getDouble("amount", 100);
		o.tipMore = fr.getBool("tipMore");
		o.droop = fr.getDouble("droop", 45);
		o.below = fr.getDouble("below", 50);
		o.light = fr.getInt("light", 7);
		o.glowFlag = fr.raw("glowFlag") == null || fr.getBool("glowFlag");
		o.wrap = fr.raw("wrap") == null || fr.getBool("wrap");
		o.name = fr.getBool("more") ? fr.getString("name") : null;
		if (keep != null)o.slots.putAll(keep.slots);
		return o;
	}

	/** the pose the parts show in the animation selected when the dialog opened (null when there is none) */
	static Map<Group, RigPreview.Pose> poseSource(List<Group> parts) {
		Animation a = Animation.selected;
		if (a == null || driverOf(a) != null)return null;
		double t = Timeline.time;
		Map<Group, RigPreview.Pose> m = new LinkedHashMap<>();
		boolean any = false;
		for (Group g : parts) {
			RigPreview.Pose p = poseAt(a, g, t);
			m.put(g, p);
			if (!isZero(p.rot, 0) || !isZero(p.pos, 0) || !isZero(p.scl, 1))any = true;
		}
		return any ? m : null;
	}

	static native void enterAnimateMode() /*-{
		try {
			var M = $wnd.Modes;
			if (M && M.options && M.options.animate && M.animate !== true) M.options.animate.select();
		} catch (e) {}
	}-*/;

	/** CPM+ &gt; Animação &gt; 'Reagir ao jogo…' (also the group right-click menu) */
	public static void open() {
		String prevMode = FollowPart.modeId();
		enterAnimateMode();
		Animation sel = Animation.selected;
		if (sel != null && driverOf(sel) != null) {
			openEdit(sel);
			return;
		}
		openCreate(prevMode);
	}

	/** @param prevMode the mode to go back to when the dialog is cancelled (null = stay) */
	static void openCreate(String prevMode) {
		List<Group> parts = new ArrayList<>(AnimToolSupport.selectedGroups());
		if (parts.isEmpty()) {
			PlusDialogs.block(t("cpmplus.rigpro.react.err.noParts"));
			return;
		}
		Map<Group, RigPreview.Pose> source = poseSource(parts);
		Options state = new Options();
		Map<String, String> presetOpts = new LinkedHashMap<>();
		for (String p : PRESETS)presetOpts.put(p, "cpmplus.rigpro.react.preset." + p);
		Map<String, String> drvOpts = new LinkedHashMap<>();
		for (Driver d : drivers())drvOpts.put(d.id, d.label());
		String preset = PlusPrefs.get(PREF + "preset", "neck");
		boolean presetOk = false;
		for (String x : PRESETS)if (x.equals(preset))presetOk = true;
		if (!presetOk)preset = "neck";
		String drv = PlusPrefs.get(PREF + "driver", "health");
		if (!drvOpts.containsKey(drv))drv = "health";

		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.rigpro.react.intro")));
		rows.add(FormRow.info("parts", null, t("cpmplus.rigpro.react.parts", parts.size(), names(parts))));
		rows.add(FormRow.radio("preset", "cpmplus.rigpro.react.what", presetOpts, preset));
		for (String p : PRESETS)rows.add(FormRow.info("help_" + p, null, t("cpmplus.rigpro.react.preset." + p + ".help")).when(fr -> p.equals(fr.getString("preset"))));
		rows.add(FormRow.select("driver", "cpmplus.rigpro.react.driver", drvOpts, drv).when(fr -> "pose".equals(fr.getString("preset"))));
		rows.add(FormRow.text("newSlider", "cpmplus.rigpro.react.newSlider", t("cpmplus.rigpro.react.newSliderDefault"))
				.when(fr -> "pose".equals(fr.getString("preset")) && "newSlider".equals(fr.getString("driver"))));
		rows.add(FormRow.info("notAlways", null, t("cpmplus.rigpro.react.notAlways")).when(fr -> {
			if (!"pose".equals(fr.getString("preset")))return false;
			Driver x = driver(fr.getString("driver"));
			return x != null && !x.always;
		}));
		rows.add(FormRow.checkbox("side", "cpmplus.rigpro.react.side", true).when(fr -> "neck".equals(fr.getString("preset"))));
		rows.add(FormRow.checkbox("updown", "cpmplus.rigpro.react.updown", true).when(fr -> "neck".equals(fr.getString("preset"))));
		rows.add(FormRow.range("amount", "cpmplus.rigpro.react.amount", 100, 0, 100, 5).description("cpmplus.rigpro.react.amount.desc")
				.when(fr -> "neck".equals(fr.getString("preset")) || "hang".equals(fr.getString("preset"))));
		rows.add(FormRow.range("droop", "cpmplus.rigpro.react.droop", 45, 5, 90, 5).when(fr -> "droop".equals(fr.getString("preset"))));
		rows.add(FormRow.range("below", "cpmplus.rigpro.react.below", 50, 10, 100, 5).description("cpmplus.rigpro.react.below.desc")
				.when(fr -> "droop".equals(fr.getString("preset"))));
		rows.add(FormRow.range("light", "cpmplus.rigpro.react.light", 7, 0, 14, 1).description("cpmplus.rigpro.react.light.desc")
				.when(fr -> "glow".equals(fr.getString("preset"))));
		rows.add(FormRow.checkbox("glowFlag", "cpmplus.rigpro.react.glowFlag", true).description("cpmplus.rigpro.react.glowFlag.desc")
				.when(fr -> "glow".equals(fr.getString("preset"))));
		if (source != null) {
			rows.add(FormRow.buttons("capture", "cpmplus.rigpro.react.capture", new String[] {"cpmplus.rigpro.react.useMin", "cpmplus.rigpro.react.useMid", "cpmplus.rigpro.react.useMax"}, i -> {
				state.slots.put(SLOTS[i], source);
				refreshCreate(parts, state);
			}).when(fr -> "pose".equals(fr.getString("preset"))));
		} else {
			rows.add(FormRow.info("captureHow", null, t("cpmplus.rigpro.react.captureHow")).when(fr -> "pose".equals(fr.getString("preset"))));
		}
		rows.add(FormRow.htmlInfo("slots", null, "<span id='" + SLOTS_ID + "'></span>").when(fr -> "pose".equals(fr.getString("preset"))));
		rows.add(FormRow.range("sim", "cpmplus.rigpro.react.sim", 50, 0, 100, 1).description("cpmplus.rigpro.react.sim.desc"));
		rows.add(FormRow.range("sim2", "cpmplus.rigpro.react.sim2", 50, 0, 100, 1).description("cpmplus.rigpro.react.sim.desc")
				.when(fr -> "neck".equals(fr.getString("preset")) && fr.getBool("side") && fr.getBool("updown")));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		rows.add(FormRow.checkbox("more", "cpmplus.rigpro.more", false));
		rows.add(FormRow.text("name", "cpmplus.rigpro.react.name", "").when(fr -> fr.getBool("more")));
		rows.add(FormRow.checkbox("tipMore", "cpmplus.rigpro.react.tipMore", false).when(fr -> fr.getBool("more") && "neck".equals(fr.getString("preset"))));
		rows.add(FormRow.checkbox("wrap", "cpmplus.rigpro.react.wrap", true).description("cpmplus.rigpro.react.wrap.desc").when(fr -> fr.getBool("more")));

		RigPreview pv = RigPreview.start(null);
		FormOpts fo = new FormOpts();
		fo.width = 560;
		fo.confirmKey = "cpmplus.rigpro.react.ok";
		fo.onCancel = () -> {
			pv.stop();
			FollowPart.restoreMode(prevMode);
		};
		fo.onChange = fr -> {
			Options o = fromForm(fr, parts, state);
			state.preset = o.preset;
			update(o, fr, pv);
		};
		Dialog[] dlg = new Dialog[1];
		dlg[0] = PlusDialogs.form(DIALOG_ID, "cpmplus.rigpro.react.title", rows, fr -> {
			pv.stop();
			Options o = fromForm(fr, parts, state);
			PlusPrefs.set(PREF + "preset", o.preset);
			if ("pose".equals(o.preset))PlusPrefs.set(PREF + "driver", o.driver);
			Result r = create(o);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				return;
			}
			@SuppressWarnings("unchecked")
			List<Object> nm = (List<Object>) r.extra.get("names");
			PlusDialogs.done(t("cpmplus.rigpro.react.done", String.valueOf(nm != null && !nm.isEmpty() ? nm.get(0) : "")));
			if ("pose".equals(o.preset))PlusDialogs.toast(t("cpmplus.rigpro.react.poseNext"), "info", 15000, null);
			if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 10000, null);
		}, fo);
		createDialog = dlg[0];
		createParts = parts;
		createState = state;
		createPreview = pv;
		dockLeft(DIALOG_ID);
		// first preview / summary right away
		DomGlobal.setTimeout(x -> {
			try {
				if (dlg[0] != null)update(fromForm(new FormResult(dlg[0].getFormResult()), parts, state), new FormResult(dlg[0].getFormResult()), pv);
			} catch (Throwable e) {
				DomGlobal.console.warn("CPM+: react preview failed", e);
			}
		}, 0);
	}

	private static Dialog createDialog;
	private static List<Group> createParts;
	private static Options createState;
	private static RigPreview createPreview;

	static void refreshCreate(List<Group> parts, Options state) {
		Dialog d = createDialog;
		if (d == null || createPreview == null)return;
		FormResult fr = new FormResult(d.getFormResult());
		update(fromForm(fr, parts, state), fr, createPreview);
	}

	/** re-plans, refreshes the preview and the summary (every form change) */
	static void update(Options o, FormResult fr, RigPreview pv) {
		Plan p = plan(o);
		StringBuilder sb = new StringBuilder();
		if (!p.r.ok)sb.append("<b style='color:var(--color-close,#f44336)'>").append(PlusDialogs.esc(p.r.errorText())).append("</b><br>");
		else {
			List<String> an = new ArrayList<>(), dl = new ArrayList<>();
			for (PlannedAnim pa : p.anims) {
				an.add("'" + pa.name + "'");
				if (!dl.contains(pa.driver.label()))dl.add(pa.driver.label());
			}
			sb.append(PlusDialogs.esc(t("cpmplus.rigpro.react.willCreate", String.join(", ", an), String.join(" + ", dl)))).append("<br>");
		}
		for (String w : p.r.warnings)sb.append("<span style='color:var(--color-warning,#e6a23c)'>").append(PlusDialogs.esc(w)).append("</span><br>");
		List<Group> hc = headCopiesWithMultiplier();
		if (!hc.isEmpty())sb.append("<span style='color:var(--color-warning,#e6a23c)'>").append(PlusDialogs.esc(t("cpmplus.rigpro.react.warn.headCopy", names(hc)))).append("</span><br>");
		double v1 = fr.getDouble("sim", 50) / 100.0, v2 = fr.getDouble("sim2", 50) / 100.0;
		if (p.r.ok && p.driver != null)sb.append(PlusDialogs.esc(simText(p, v1, v2))).append("<br>");
		sb.append("<span style='opacity:0.8'>").append(PlusDialogs.esc(budgetText(p.r.ok ? p : null))).append("</span>");
		setHtml(SUMMARY_ID, sb.toString());
		if ("pose".equals(o.preset)) {
			StringBuilder s = new StringBuilder();
			for (String slot : SLOTS) {
				boolean got = o.slots.get(slot) != null;
				s.append(PlusDialogs.esc(t("cpmplus.rigpro.slot." + slot))).append(": ").append(got ? "✓ " + PlusDialogs.esc(t("cpmplus.rigpro.react.slotSet")) : PlusDialogs.esc(t("cpmplus.rigpro.react.slotRest"))).append("&nbsp;&nbsp; ");
			}
			setHtml(SLOTS_ID, s.toString());
		}
		if (!p.r.ok) {
			pv.show(new LinkedHashMap<>(), Double.NaN, Double.NaN);
			return;
		}
		previewPlan(p, v1, v2, pv);
	}

	static String simText(Plan p, double v1, double v2) {
		List<String> parts = new ArrayList<>();
		for (PlannedAnim pa : p.anims) {
			double v = pa.driver.id.equals("pitch") && p.anims.size() > 1 ? v2 : v1;
			parts.add(valueText(pa.driver, v));
		}
		return t("cpmplus.rigpro.react.simulating", String.join(" + ", parts));
	}

	/** the game situation of value v ('cabeça 45° para a direita', 'vida 30%' ...) */
	static String valueText(Driver d, double v) {
		if (d.head == 'y') {
			int deg = (int) Math.round(Math.abs(180 * v - 90));
			return deg == 0 ? t("cpmplus.rigpro.val.yaw0") : t(v > 0.5 ? "cpmplus.rigpro.val.yawR" : "cpmplus.rigpro.val.yawL", deg);
		}
		if (d.head == 'x') {
			int deg = (int) Math.round(Math.abs(180 * v - 90));
			return deg == 0 ? t("cpmplus.rigpro.val.pitch0") : t(v > 0.5 ? "cpmplus.rigpro.val.pitchD" : "cpmplus.rigpro.val.pitchU", deg);
		}
		if ("light".equals(d.id))return t("cpmplus.rigpro.val.light", (int) Math.round(v * 15));
		return t("cpmplus.rigpro.val.pct", d.label(), Math.round(v * 100) + "%");
	}

	/** shows the planned poses at the simulated values (+ the head turn) */
	static void previewPlan(Plan p, double v1, double v2, RigPreview pv) {
		Map<Group, RigPreview.Pose> sum = new LinkedHashMap<>();
		double hx = Double.NaN, hy = Double.NaN;
		for (PlannedAnim pa : p.anims) {
			double v = pa.driver.id.equals("pitch") && p.anims.size() > 1 ? v2 : v1;
			if (pa.driver.head == 'y')hy = Driver.headTurn(v);
			if (pa.driver.head == 'x')hx = Driver.headTurn(v);
			for (Map.Entry<Group, RigPreview.Pose> e : pa.at(v).entrySet()) {
				RigPreview.Pose s = sum.computeIfAbsent(e.getKey(), k -> new RigPreview.Pose());
				RigPreview.Pose x = e.getValue();
				if (x.rot != null)s.rot = s.rot == null ? x.rot : new double[] {s.rot[0] + x.rot[0], s.rot[1] + x.rot[1], s.rot[2] + x.rot[2]};
				if (x.pos != null)s.pos = s.pos == null ? x.pos : new double[] {s.pos[0] + x.pos[0], s.pos[1] + x.pos[1], s.pos[2] + x.pos[2]};
				if (x.scl != null)s.scl = s.scl == null ? x.scl : new double[] {s.scl[0] * x.scl[0], s.scl[1] * x.scl[1], s.scl[2] * x.scl[2]};
				if (x.vis != null)s.vis = x.vis;
			}
		}
		pv.show(sum, hx, hy);
	}

	static native void setHtml(String id, String html) /*-{
		var el = $wnd.document.getElementById(id);
		if (el) el.innerHTML = html;
	}-*/;

	/** moves an open dialog to the left edge so the model in the middle of the viewport stays visible (live preview) */
	public static native void dockLeft(String id) /*-{
		try {
			var st = $wnd.Dialog && $wnd.Dialog.stack;
			for (var i = st ? st.length - 1 : -1; i >= 0; i--) {
				var d = st[i];
				if (d && d.id === id && d.object) { d.object.style.left = '12px'; break; }
			}
		} catch (e) {}
	}-*/;

	/** the edit view of an existing reaction: capture buttons, 'Simular', 'Criar outra reação' */
	static void openEdit(Animation a) {
		Driver d = driverOf(a);
		List<Group> parts = editParts(a);
		double at = Timeline.time;
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.rigpro.react.edit.intro", a.name, d.label())));
		rows.add(FormRow.info("range", null, t("cpmplus.rigpro.react.edit.range", d.minText(), d.maxText())));
		rows.add(FormRow.info("parts", null, parts.isEmpty() ? t("cpmplus.rigpro.react.err.noParts") : t("cpmplus.rigpro.react.parts", parts.size(), names(parts))));
		rows.add(FormRow.buttons("capture", "cpmplus.rigpro.react.capture", new String[] {"cpmplus.rigpro.react.useMin", "cpmplus.rigpro.react.useMid", "cpmplus.rigpro.react.useMax"}, i -> {
			Result r = capture(a, SLOTS[i], parts, at);
			if (!r.ok)PlusDialogs.quick(r.errorText(), 5000);
			else {
				editDone.add(SLOTS[i]);
				setHtml(SLOTS_ID, editSlotsText());
				PlusDialogs.quick(t("cpmplus.rigpro.react.captured", t("cpmplus.rigpro.slot." + SLOTS[i])), 3000);
			}
			if (editPreview != null && editDialog != null)editSim(a, d, new FormResult(editDialog.getFormResult()), editPreview);
		}));
		rows.add(FormRow.htmlInfo("slots", null, "<span id='" + SLOTS_ID + "'></span>"));
		double frac = Math.max(0, Math.min(1, at / Math.max(0.05, a.length)));
		rows.add(FormRow.range("sim", "cpmplus.rigpro.react.sim", Math.round(frac * 100), 0, 100, 1).description("cpmplus.rigpro.react.sim.desc"));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		rows.add(FormRow.buttons("other", null, new String[] {"cpmplus.rigpro.react.another"}, i -> {
			closeTop(EDIT_DIALOG_ID);
			DomGlobal.setTimeout(x -> {
				try {
					unselectAnimation();
					openCreate(null);
				} catch (Throwable e) {
					DomGlobal.console.warn(e);
				}
			}, 50);
		}));
		editDone.clear();
		RigPreview pv = RigPreview.start(a);
		editPreview = pv;
		FormOpts fo = new FormOpts();
		fo.width = 540;
		fo.confirmKey = "cpmplus.rigpro.react.edit.ok";
		fo.onCancel = pv::stop;
		fo.onChange = fr -> editSim(a, d, fr, pv);
		Dialog dlg = PlusDialogs.form(EDIT_DIALOG_ID, "cpmplus.rigpro.react.edit.title", rows, fr -> pv.stop(), fo);
		editDialog = dlg;
		dockLeft(EDIT_DIALOG_ID);
		DomGlobal.setTimeout(x -> {
			try {
				setHtml(SLOTS_ID, editSlotsText());
				editSim(a, d, new FormResult(dlg.getFormResult()), pv);
			} catch (Throwable e) {
			}
		}, 0);
	}

	private static final List<String> editDone = new ArrayList<>();
	private static RigPreview editPreview;
	private static Dialog editDialog;

	static String editSlotsText() {
		StringBuilder s = new StringBuilder();
		for (String slot : SLOTS) {
			if (!editDone.contains(slot))continue;
			if (s.length() > 0)s.append(", ");
			s.append(PlusDialogs.esc(t("cpmplus.rigpro.slot." + slot)));
		}
		return s.length() == 0 ? "" : "✓ " + PlusDialogs.esc(t("cpmplus.rigpro.react.edit.saved")) + " " + s;
	}

	static void editSim(Animation a, Driver d, FormResult fr, RigPreview pv) {
		double v = fr.getDouble("sim", 50) / 100.0;
		double t = v * (a.length > 0 ? a.length : LEN);
		Map<Group, RigPreview.Pose> m = new LinkedHashMap<>();
		for (BoneAnimator ba : AnimxUtil.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null)continue;
			RigPreview.Pose p = poseAt(a, g, t);
			if (AnimEdit.count(ba, KeyMath.VIS) > 0)p.vis = AnimEdit.visAt(ba, t, !g.hidden);
			m.put(g, p);
		}
		pv.show(m, d.head == 'x' ? Driver.headTurn(v) : Double.NaN, d.head == 'y' ? Driver.headTurn(v) : Double.NaN);
		setHtml(SUMMARY_ID, PlusDialogs.esc(t("cpmplus.rigpro.react.simulating", valueText(d, v))));
	}

	static native void closeTop(String id) /*-{
		try {
			var st = $wnd.Dialog && $wnd.Dialog.stack;
			var d = st && st.length ? st[st.length - 1] : $wnd.Dialog.open;
			if (d && d.id === id) d.cancel();
		} catch (e) {}
	}-*/;

	static native void unselectAnimation() /*-{
		try {
			var a = $wnd.Animation && $wnd.Animation.selected;
			if (a && typeof a.unselect === 'function') a.unselect();
		} catch (e) {}
	}-*/;

	// ====================================================================== QA hook

	static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		if (v == null)return def;
		if ("number".equals(Js.typeof(v)))return Js.asDouble(v);
		try {
			return Double.parseDouble(String.valueOf(v));
		} catch (NumberFormatException e) {
			return def;
		}
	}

	static boolean bool(JsPropertyMap<Object> o, String k, boolean def) {
		Object v = o.get(k);
		return v == null ? def : Js.isTruthy(v);
	}

	static List<Group> groups(JsPropertyMap<Object> o, String k) {
		List<Group> l = new ArrayList<>();
		Object v = o.get(k);
		if (v == null)return l;
		List<String> names = new ArrayList<>();
		if (JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)names.add(String.valueOf(a.getAt(i)));
		} else names.add(String.valueOf(v));
		for (String n : names) {
			Group g = AnimEdit.groupByPath(n);
			if (g == null)for (Group x : Group.all)if (n.equals(x.name)) {
				g = x;
				break;
			}
			if (g != null)l.add(g);
		}
		return l;
	}

	static Options optsFromHook(JsPropertyMap<Object> o) {
		Options op = new Options();
		op.parts.addAll(groups(o, "parts"));
		op.preset = str(o, "preset", "neck");
		op.driver = str(o, "driver", "yaw");
		op.newSlider = str(o, "newSlider", null);
		op.side = bool(o, "side", true);
		op.updown = bool(o, "updown", true);
		op.amount = num(o, "amount", 100);
		op.tipMore = bool(o, "tipMore", false);
		op.droop = num(o, "droop", 45);
		op.below = num(o, "below", 50);
		op.light = (int) num(o, "light", 7);
		op.glowFlag = bool(o, "glowFlag", true);
		op.wrap = bool(o, "wrap", true);
		op.name = str(o, "name", null);
		// 'pose' slots from the animation / time given ({captureFrom: anim name, captureAt: t, slots: ['max']})
		String from = str(o, "captureFrom", null);
		if (from != null) {
			Animation src = AnimEdit.byName(from);
			double at = num(o, "captureAt", 0);
			Object sl = o.get("slots");
			List<String> slots = new ArrayList<>();
			if (sl != null && JsArray.isArray(sl)) {
				JsArray<Object> a = Js.uncheckedCast(sl);
				for (int i = 0; i < a.length; i++)slots.add(String.valueOf(a.getAt(i)));
			}
			Map<Group, RigPreview.Pose> m = new LinkedHashMap<>();
			for (Group g : op.parts)m.put(g, poseAt(src, g, at));
			for (String s : slots)op.slots.put(s, m);
		}
		return op;
	}

	static Map<String, Object> planJson(Plan p) {
		Map<String, Object> m = new LinkedHashMap<>();
		List<Object> anims = new ArrayList<>();
		for (PlannedAnim pa : p.anims) {
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("name", pa.name);
			a.put("type", pa.driver.type);
			Map<String, Object> parts = new LinkedHashMap<>();
			for (Map.Entry<Group, Map<String, List<KeyData>>> e : pa.keys.entrySet()) {
				Map<String, Object> chs = new LinkedHashMap<>();
				for (Map.Entry<String, List<KeyData>> c : e.getValue().entrySet()) {
					List<Object> ks = new ArrayList<>();
					for (KeyData k : c.getValue()) {
						List<Object> kv = new ArrayList<>();
						kv.add(k.time);
						if (KeyMath.VIS.equals(c.getKey()))kv.add(k.vis);
						else {
							kv.add(k.get(0));
							kv.add(k.get(1));
							kv.add(k.get(2));
						}
						ks.add(kv);
					}
					chs.put(c.getKey(), ks);
				}
				parts.put(e.getKey().name, chs);
			}
			a.put("parts", parts);
			anims.add(a);
		}
		m.put("anims", anims);
		List<Object> w = new ArrayList<>();
		for (Group g : p.wrap)w.add(g.name);
		m.put("wrap", w);
		m.put("glowCubes", (double) p.glowCubes.size());
		m.put("priority", p.priority != null ? (double) p.priority.value : 0.0);
		m.put("driver", p.driver != null ? p.driver.id : null);
		return m;
	}

	/**
	 * react({op: plan | create | capture | drivers | budget | preview | open | state, ...options}); capture: {anim, slot,
	 * parts, at}; preview: options + {sim, sim2} -> previewed head turn + temp animation, then stopped unless keep.
	 */
	public static Object hook(JsPropertyMap<Object> o) {
		String op = str(o, "op", "plan");
		switch (op) {
		case "drivers": {
			Result r = new Result();
			List<Object> l = new ArrayList<>();
			for (Driver d : drivers()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", d.id);
				m.put("type", d.type);
				m.put("label", d.label());
				m.put("min", d.minText());
				m.put("max", d.maxText());
				l.add(m);
			}
			return r.put("drivers", l).toJs();
		}
		case "budget":
			return new Result().put("used", (double) usedIds()).put("text", budgetText(null)).toJs();
		case "create":
			return create(optsFromHook(o)).toJs();
		case "capture": {
			Animation a = AnimEdit.byName(str(o, "anim", ""));
			List<Group> parts = groups(o, "parts");
			if (parts.isEmpty() && a != null)parts = editParts(a);
			return capture(a, str(o, "slot", "max"), parts, num(o, "at", Timeline.time)).toJs();
		}
		case "preview": {
			Options op2 = optsFromHook(o);
			Plan p = plan(op2);
			RigPreview pv = RigPreview.start(null);
			previewPlan(p, num(o, "sim", 50) / 100.0, num(o, "sim2", 50) / 100.0, pv);
			Result r = new Result();
			Group head = headRoot();
			r.put("headRot", head != null ? meshRot(head.mesh) : null);
			Map<String, Object> rots = new LinkedHashMap<>();
			for (Group g : op2.parts)rots.put(g.name, meshRot(g.mesh));
			r.put("partRot", rots);
			r.put("state", RigPreview.state());
			r.put("tempInList", pv.temp() != null && AnimEdit.all().contains(pv.temp()));
			if (!bool(o, "keep", false)) {
				Animation tmp = pv.temp();
				pv.stop();
				r.put("stoppedTempGone", tmp == null || !AnimEdit.all().contains(tmp));
				r.put("headRotAfter", head != null ? meshRot(head.mesh) : null);
			}
			return r.toJs();
		}
		case "stopPreview":
			RigPreview.stopActive();
			return new Result().put("state", RigPreview.state()).toJs();
		case "state":
			return new Result().put("state", RigPreview.state()).toJs();
		case "open":
			open();
			return new Result().toJs();
		default: {
			Plan p = plan(optsFromHook(o));
			Result r = p.r;
			r.put("plan", planJson(p));
			return r.toJs();
		}
		}
	}

	static List<Object> meshRot(Object mesh) {
		if (!hasRotation(mesh))return null;
		List<Object> l = new ArrayList<>();
		for (int i = 0; i < 3; i++)l.add(Math.round(Math.toDegrees(meshAxis(mesh, i)) * 1000) / 1000.0);
		return l;
	}

	private static native boolean hasRotation(Object mesh) /*-{
		return !!(mesh && mesh.rotation);
	}-*/;

	private static native double meshAxis(Object mesh, int i) /*-{
		return i == 0 ? mesh.rotation.x : i == 1 ? mesh.rotation.y : mesh.rotation.z;
	}-*/;

	static Element el(String id) {
		return DomGlobal.document.getElementById(id);
	}

	/** keys of a channel in an animation for a part (QA) */
	static List<Keyframe> keysOf(Animation a, Group g, String ch) {
		return AnimxUtil.sorted(AnimxUtil.keys(AnimEdit.animatorOf(a, g), ch));
	}
}
