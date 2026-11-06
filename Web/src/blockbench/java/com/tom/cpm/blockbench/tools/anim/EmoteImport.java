package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Importar emote (Emotecraft / PlayerAnimator)': an emote .json becomes a CPM gesture, with PlayerAnimator's own
 * playback rules (KeyframeAnimationPlayer): every axis of every part is its own timeline of absolute values (radians,
 * or degrees when 'degrees' is true or missing; 'turn' adds full turns), the segment INTO a keyframe uses that
 * keyframe's easing, before the first keyframe the value comes from the default, after the last one it goes back to
 * the default at the end (non-looping: stopTick) or wraps to returnTick (looping).
 * <ul>
 * <li>Parts: head, torso (the body part; in files before version 3 'torso' is the whole player), rightArm, leftArm,
 * rightLeg, leftLeg -&gt; CPM's head / body / right_arm / left_arm / right_leg / left_leg. Minecraft part space is
 * converted to Blockbench's (x and y of rotation and position negated, positions relative to the vanilla pivot).</li>
 * <li>'body' (the whole player: PlayerAnimator rotates the matrix around 0.7 block above the feet, order Z Y X, in the
 * space before the model flip) is flattened onto every CPM part (CPM parts are siblings, not children).</li>
 * <li>Bends (bendy-lib): a limb bend goes to the '&lt;part&gt;_cotovelo' / '_joelho' joint made by 'Dobrar membro';
 * the torso bend rotates the head and the arms around the waist (and the '_cintura' joint when there is one). Without
 * the joint a bend is left out (warning). Bend directions follow bendy-lib's axis (cos, 0, sin); approximate.</li>
 * <li>Timing: one key per tick (20 per second, linear, redundant keys dropped). Not looping: one 'once' gesture until
 * stopTick. Looping: a gesture loop from returnTick to endTick + 1, and when returnTick &gt; 0 the start becomes CPM's
 * entry ('g:&lt;name&gt;', plays once before the loop).</li>
 * <li>Non-additive (an emote poses the parts it moves); one undo entry.</li>
 * </ul>
 */
public final class EmoteImport {
	private EmoteImport() {
	}

	static final String[] PARTS = {"head", "torso", "rightArm", "leftArm", "rightLeg", "leftLeg"};
	static final String[] ROOTS = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
	/** PlayerAnimator / vanilla pivots (Minecraft part space, px) */
	static final double[][] DEF_POS = {{0, 0, 0}, {0, 0, 0}, {-5, 2, 0}, {5, 2, 0}, {-1.9, 12, 0.1}, {1.9, 12, 0.1}};
	static final double[][] CPM_POS = {{0, 0, 0}, {0, 0, 0}, {-5, 2, 0}, {5, 2, 0}, {-1.9, 12, 0}, {1.9, 12, 0}};
	static final String[] AXES = {"x", "y", "z", "pitch", "yaw", "roll", "axis", "bend"};
	static final double WAIST_Y = 6, BODY_PIVOT_Y = 24 - 0.7 * 16;

	// ====================================================================== the file

	static final class Key {
		int tick;
		double value;
		String ease;
	}

	/** keyframes of one axis of one part, sorted by tick */
	static final class Axis {
		final List<Key> keys = new ArrayList<>();
	}

	static final class Emote {
		String name;
		int version = 1;
		int beginTick, endTick, stopTick, returnTick;
		boolean loop;
		/** part -&gt; axis name -&gt; keys */
		final Map<String, Map<String, Axis>> parts = new LinkedHashMap<>();
	}

	static Emote parse(String fileName, Object json) {
		JsPropertyMap<Object> root = Js.asPropertyMap(json);
		Object em = root.get("emote");
		if (em == null)throw new IllegalArgumentException("cpmplus.w3animtools.emote.err.notEmote");
		Emote e = new Emote();
		e.version = root.has("version") ? (int) num(root.get("version"), 1) : 1;
		Object nm = root.get("name");
		e.name = nm != null ? nameText(nm) : baseName(fileName);
		JsPropertyMap<Object> n = Js.asPropertyMap(em);
		e.beginTick = (int) num(n.get("beginTick"), 0);
		e.endTick = (int) num(n.get("endTick"), 0);
		if (e.endTick <= 0)throw new IllegalArgumentException("cpmplus.w3animtools.emote.err.endTick");
		e.stopTick = n.has("stopTick") ? (int) num(n.get("stopTick"), e.endTick) : e.endTick;
		if (n.has("isLoop") && n.has("returnTick")) {
			e.loop = bool(n.get("isLoop"));
			e.returnTick = Math.max(0, Math.min(e.endTick, (int) num(n.get("returnTick"), 0)));
		}
		boolean degrees = !n.has("degrees") || bool(n.get("degrees"));
		Object mv = n.get("moves");
		if (mv == null || !JsArray.isArray(mv))throw new IllegalArgumentException("cpmplus.w3animtools.emote.err.noMoves");
		JsArray<Object> moves = Js.uncheckedCast(mv);
		for (int i = 0; i < moves.length; i++) {
			JsPropertyMap<Object> m = Js.asPropertyMap(moves.getAt(i));
			int tick = (int) num(m.get("tick"), 0);
			String ease = m.has("easing") ? String.valueOf(m.get("easing")) : "linear";
			int turn = (int) num(m.get("turn"), 0);
			for (String part : keys(m)) {
				if ("tick".equals(part) || "comment".equals(part) || "easing".equals(part) || "turn".equals(part))continue;
				String p = part;
				if (e.version < 3 && "torso".equals(p))p = "body";
				Object pv = m.get(part);
				if (pv == null || !"object".equals(Js.typeof(pv)))continue;
				JsPropertyMap<Object> pn = Js.asPropertyMap(pv);
				for (String ax : AXES) {
					if (!pn.has(ax))continue;
					double v = num(pn.get(ax), 0);
					boolean angle = "pitch".equals(ax) || "yaw".equals(ax) || "roll".equals(ax) || "axis".equals(ax) || "bend".equals(ax);
					if (degrees && angle)v = Math.toRadians(v);
					if (angle && turn != 0 && !"axis".equals(ax) && !"bend".equals(ax))v += Math.PI * 2 * turn;
					Key k = new Key();
					k.tick = tick;
					k.value = v;
					k.ease = ease;
					e.parts.computeIfAbsent(p, x -> new LinkedHashMap<>()).computeIfAbsent(ax, x -> new Axis()).keys.add(k);
				}
			}
		}
		for (Map<String, Axis> m : e.parts.values())for (Axis a : m.values())Collections.sort(a.keys, (x, y) -> Integer.compare(x.tick, y.tick));
		return e;
	}

	private static native String[] keys(Object o) /*-{
		return Object.keys(o);
	}-*/;

	private static String nameText(Object o) {
		if ("string".equals(Js.typeof(o)))return (String) o;
		// translated name objects {"text": ...} or JSON text
		JsPropertyMap<Object> m = Js.asPropertyMap(o);
		Object t = m.get("text");
		return t != null ? String.valueOf(t) : String.valueOf(o);
	}

	static String baseName(String f) {
		if (f == null)return "emote";
		String s = f.replace('\\', '/');
		s = s.substring(s.lastIndexOf('/') + 1);
		int d = s.lastIndexOf('.');
		return d > 0 ? s.substring(0, d) : s;
	}

	private static double num(Object v, double def) {
		if (v == null)return def;
		if ("number".equals(Js.typeof(v)))return Js.asDouble(v);
		try {
			return Double.parseDouble(String.valueOf(v));
		} catch (NumberFormatException ex) {
			return def;
		}
	}

	private static boolean bool(Object v) {
		if (v == null)return false;
		if ("boolean".equals(Js.typeof(v)))return Js.isTruthy(v);
		return "true".equalsIgnoreCase(String.valueOf(v));
	}

	// ====================================================================== playback (KeyframeAnimationPlayer)

	/** value of an axis at a tick; def = the part's default for that axis */
	static double value(Emote e, Axis a, double tick, double def) {
		if (a == null || a.keys.isEmpty())return def;
		List<Key> ks = a.keys;
		Key before = null, after = null;
		for (Key k : ks) {
			if (k.tick <= tick)before = k;
			else if (after == null)after = k;
		}
		double t0, v0, t1, v1;
		String ease;
		if (before == null) {
			t0 = 0;
			v0 = def;
			t1 = after.tick;
			v1 = after.value;
			ease = after.ease;
			if (t1 <= t0)return v1;
		} else if (after == null) {
			if (e.loop) {
				// wraps to the first key at or after returnTick, one tick after endTick
				Key wrap = null;
				for (Key k : ks)if (k.tick >= e.returnTick) {
					wrap = k;
					break;
				}
				t1 = e.endTick + 1;
				v1 = wrap != null ? wrap.value : def;
				ease = wrap != null ? wrap.ease : "linear";
			} else {
				t1 = Math.max(e.stopTick, before.tick);
				v1 = def;
				ease = "linear";
			}
			t0 = before.tick;
			v0 = before.value;
			if (t1 <= t0)return v0;
		} else {
			t0 = before.tick;
			v0 = before.value;
			t1 = after.tick;
			v1 = after.value;
			ease = after.ease;
		}
		double f = Math.max(0, Math.min(1, (tick - t0) / (t1 - t0)));
		return v0 + (v1 - v0) * ease(ease, f);
	}

	/** easings.net functions, names like Emotecraft's ('EASEINOUTSINE', 'INOUTSINE', 'LINEAR', 'CONSTANT') */
	static double ease(String name, double x) {
		String n = name == null ? "LINEAR" : name.toUpperCase(Locale.ROOT).replace("_", "");
		if (n.startsWith("EASE"))n = n.substring(4);
		if (n.equals("LINEAR") || n.isEmpty())return x;
		if (n.equals("CONSTANT") || n.equals("STEP"))return x >= 1 ? 1 : 0;
		String mode, type;
		if (n.startsWith("INOUT")) {
			mode = "INOUT";
			type = n.substring(5);
		} else if (n.startsWith("IN")) {
			mode = "IN";
			type = n.substring(2);
		} else if (n.startsWith("OUT")) {
			mode = "OUT";
			type = n.substring(3);
		} else return x;
		switch (mode) {
		case "IN":
			return in(type, x);
		case "OUT":
			return 1 - in(type, 1 - x);
		default:
			return x < 0.5 ? in(type, 2 * x) / 2 : 1 - in(type, 2 - 2 * x) / 2;
		}
	}

	private static double in(String type, double x) {
		switch (type) {
		case "SINE":
			return 1 - Math.cos(x * Math.PI / 2);
		case "QUAD":
			return x * x;
		case "CUBIC":
			return x * x * x;
		case "QUART":
			return x * x * x * x;
		case "QUINT":
			return x * x * x * x * x;
		case "EXPO":
			return x == 0 ? 0 : Math.pow(2, 10 * x - 10);
		case "CIRC":
			return 1 - Math.sqrt(Math.max(0, 1 - x * x));
		case "BACK":
			return 2.70158 * x * x * x - 1.70158 * x * x;
		case "ELASTIC":
			return x == 0 || x == 1 ? x : -Math.pow(2, 10 * x - 10) * Math.sin((x * 10 - 10.75) * (2 * Math.PI) / 3);
		case "BOUNCE":
			return 1 - bounceOut(1 - x);
		default:
			return x;
		}
	}

	private static double bounceOut(double x) {
		double n1 = 7.5625, d1 = 2.75;
		if (x < 1 / d1)return n1 * x * x;
		if (x < 2 / d1)return n1 * (x -= 1.5 / d1) * x + 0.75;
		if (x < 2.5 / d1)return n1 * (x -= 2.25 / d1) * x + 0.9375;
		return n1 * (x -= 2.625 / d1) * x + 0.984375;
	}

	// ====================================================================== the pose of the CPM parts at a tick

	/** final pose of one CPM part (Minecraft part space): rotation (quaternion) + pivot position (px) */
	static final class Pose {
		double[] q = {0, 0, 0, 1};
		double[] p = new double[3];
		boolean rotKeyed, posKeyed;
	}

	static double[] quatMC(double pitch, double yaw, double roll) {
		// Minecraft ModelPart: rotate Z, then Y, then X  ->  R = Rz * Ry * Rx (THREE 'ZYX')
		return PhysicsSim.quatZYX(pitch, yaw, roll);
	}

	/** rotation by angle around the horizontal axis (cos a, 0, sin a) (bendy-lib) */
	static double[] bendQuat(double axis, double bend) {
		double[] ax = {Math.cos(axis), 0, Math.sin(axis)};
		double s = Math.sin(bend / 2);
		return new double[] {ax[0] * s, ax[1] * s, ax[2] * s, Math.cos(bend / 2)};
	}

	static final class Frame {
		final Pose[] roots = new Pose[6];
		/** joint rotation (bend) per root, null when the part does not bend */
		final double[][] joint = new double[6][];
	}

	static Frame frame(Emote e, double tick, boolean waistJoint) {
		Frame f = new Frame();
		for (int i = 0; i < 6; i++) {
			Map<String, Axis> m = e.parts.get(PARTS[i]);
			// files before version 3: the torso bend sits on 'body' (renamed from 'torso')
			if (i == 1 && e.version < 3 && e.parts.get("body") != null && e.parts.get("body").get("bend") != null) {
				Map<String, Axis> mb = e.parts.get("body");
				f.joint[1] = bendQuat(value(e, ax(mb, "axis"), tick, 0), value(e, ax(mb, "bend"), tick, 0));
			}
			Pose ps = new Pose();
			double pitch = value(e, ax(m, "pitch"), tick, 0), yaw = value(e, ax(m, "yaw"), tick, 0), roll = value(e, ax(m, "roll"), tick, 0);
			ps.q = quatMC(pitch, yaw, roll);
			ps.p = new double[] {value(e, ax(m, "x"), tick, DEF_POS[i][0]), value(e, ax(m, "y"), tick, DEF_POS[i][1]), value(e, ax(m, "z"), tick, DEF_POS[i][2])};
			ps.rotKeyed = ax(m, "pitch") != null || ax(m, "yaw") != null || ax(m, "roll") != null;
			ps.posKeyed = ax(m, "x") != null || ax(m, "y") != null || ax(m, "z") != null;
			if (ax(m, "bend") != null)f.joint[i] = bendQuat(value(e, ax(m, "axis"), tick, 0), value(e, ax(m, "bend"), tick, 0));
			f.roots[i] = ps;
		}
		// torso bend: the upper body (head, arms) turns around the waist with it; without a '_cintura' joint the
		// whole torso part turns there instead (approximation)
		if (f.joint[1] != null) {
			double[] q = f.joint[1];
			double[] pivot = {0, WAIST_Y, 0};
			for (int i : new int[] {0, 2, 3})apply(f.roots[i], q, pivot, new double[3]);
			if (!waistJoint)apply(f.roots[1], q, pivot, new double[3]);
		}
		// the whole player ('body'): render space before the flip (x, y negated in part space), pivot 0.7 block up
		Map<String, Axis> b = e.parts.get("body");
		if (b != null) {
			double vx = value(e, ax(b, "pitch"), tick, 0), vy = value(e, ax(b, "yaw"), tick, 0), vz = value(e, ax(b, "roll"), tick, 0);
			double[] q = quatMC(-vx, -vy, vz);
			double[] t = {-16 * value(e, ax(b, "x"), tick, 0), -16 * value(e, ax(b, "y"), tick, 0), 16 * value(e, ax(b, "z"), tick, 0)};
			for (int i = 0; i < 6; i++)apply(f.roots[i], q, new double[] {0, BODY_PIVOT_Y, 0}, t);
		}
		return f;
	}

	private static Axis ax(Map<String, Axis> m, String a) {
		return m != null ? m.get(a) : null;
	}

	/** turns a part with q around pivot, then moves it by t */
	static void apply(Pose ps, double[] q, double[] pivot, double[] t) {
		ps.q = PhysicsSim.mul(q, ps.q);
		double[] r = PhysicsSim.rot(q, PhysicsSim.sub(ps.p, pivot));
		ps.p = PhysicsSim.add(PhysicsSim.add(r, pivot), t);
		ps.rotKeyed = true;
		ps.posKeyed = true;
	}

	/** Minecraft part rotation -&gt; Blockbench animation values (degrees; x and y negated) */
	static double[] bbRot(double[] q) {
		double[] e = PhysicsSim.eulerZYX(q);
		return new double[] {-Math.toDegrees(e[0]), -Math.toDegrees(e[1]), Math.toDegrees(e[2])};
	}

	/** Minecraft part pivot -&gt; Blockbench position offset (from the vanilla pivot; x and y negated) */
	static double[] bbPos(double[] p, int root) {
		return new double[] {-(p[0] - CPM_POS[root][0]), -(p[1] - CPM_POS[root][1]), p[2] - CPM_POS[root][2]};
	}

	// ====================================================================== build the animations

	static final class Plan {
		Emote emote;
		String name;
		/** tick ranges: setup [0, mainStart), main [mainStart, mainEnd] */
		int mainStart, mainEnd;
		boolean setup;
		final Map<String, List<KeyData>> mainKeys = new LinkedHashMap<>(), setupKeys = new LinkedHashMap<>();
		final Map<String, Group> groups = new LinkedHashMap<>();
		int skippedBends;
	}

	static Group root(String n) {
		for (Group g : AnimxUtil.vanillaRoots())if (n.equals(g.name))return g;
		return null;
	}

	static Group joint(Group root) {
		if (root == null || root.children == null)return null;
		for (int i = 0; i < root.children.length; i++) {
			Object c = root.children.getAt(i);
			if (c instanceof Group) {
				String n = ((Group) c).name;
				if (n != null && (n.endsWith("_cotovelo") || n.endsWith("_joelho") || n.endsWith("_cintura")))return (Group) c;
			}
		}
		return null;
	}

	static Plan plan(Emote e, Result r) {
		Plan p = new Plan();
		p.emote = e;
		p.name = e.name.replace('#', ' ').trim();
		if (p.name.isEmpty())p.name = "emote";
		if (e.loop) {
			p.mainStart = e.returnTick;
			p.mainEnd = e.endTick + 1;
			p.setup = e.returnTick > 0;
		} else {
			p.mainStart = 0;
			p.mainEnd = Math.max(e.stopTick, 1);
		}
		boolean[] rotUsed = new boolean[6], posUsed = new boolean[6], jointUsed = new boolean[6];
		Group[] roots = new Group[6], joints = new Group[6];
		for (int i = 0; i < 6; i++) {
			roots[i] = root(ROOTS[i]);
			joints[i] = joint(roots[i]);
		}
		int last = p.mainEnd;
		List<Frame> frames = new ArrayList<>();
		for (int tk = 0; tk <= last; tk++)frames.add(frame(e, tk, joints[1] != null));
		for (Frame f : frames)for (int i = 0; i < 6; i++) {
			if (f.roots[i].rotKeyed)rotUsed[i] = true;
			if (f.roots[i].posKeyed) {
				double[] o = bbPos(f.roots[i].p, i);
				if (Math.abs(o[0]) > 0.01 || Math.abs(o[1]) > 0.01 || Math.abs(o[2]) > 0.01)posUsed[i] = true;
			}
			if (f.joint[i] != null)jointUsed[i] = true;
		}
		for (int i = 0; i < 6; i++) {
			if (roots[i] == null)continue;
			if (jointUsed[i] && joints[i] == null && i != 1)p.skippedBends++;
			if (rotUsed[i])addTrack(p, frames, roots[i], i, KeyMath.ROT, false);
			if (posUsed[i])addTrack(p, frames, roots[i], i, KeyMath.POS, false);
			if (jointUsed[i] && joints[i] != null)addTrack(p, frames, joints[i], i, KeyMath.ROT, true);
		}
		if (p.skippedBends > 0)r.warn("bends", t("cpmplus.w3animtools.emote.warn.bends", p.skippedBends));
		if (e.parts.containsKey("body"))r.warn("body", t("cpmplus.w3animtools.emote.warn.body"));
		r.put("name", p.name).put("loop", e.loop).put("setup", p.setup).put("tracks", (double) p.groups.size())
		.put("seconds", (p.mainEnd - p.mainStart) / 20.0).put("ticks", (double) last);
		return p;
	}

	private static void addTrack(Plan p, List<Frame> frames, Group g, int i, String ch, boolean joint) {
		List<KeyData> setup = new ArrayList<>(), main = new ArrayList<>();
		for (int tk = 0; tk < frames.size(); tk++) {
			Frame f = frames.get(tk);
			double[] v;
			if (joint)v = bbRot(f.joint[i] != null ? f.joint[i] : new double[] {0, 0, 0, 1});
			else v = KeyMath.ROT.equals(ch) ? bbRot(f.roots[i].q) : bbPos(f.roots[i].p, i);
			if (tk <= p.mainStart && p.setup)setup.add(key(tk / 20.0, v));
			if (tk >= p.mainStart && tk <= p.mainEnd)main.add(key((tk - p.mainStart) / 20.0, v));
		}
		unwrap(main, KeyMath.ROT.equals(ch));
		unwrap(setup, KeyMath.ROT.equals(ch));
		String k = g.uuid + "|" + ch;
		p.groups.put(k, g);
		p.mainKeys.put(k, CorrectionLayer.reduce(main, false));
		if (p.setup)p.setupKeys.put(k, CorrectionLayer.reduce(setup, false));
	}

	/** consecutive rotation keys never jump by more than 180 degrees per axis (no spin through the long way) */
	static void unwrap(List<KeyData> keys, boolean rot) {
		if (!rot)return;
		for (int i = 1; i < keys.size(); i++) {
			double[] a = keys.get(i - 1).v[0], b = keys.get(i).v[0];
			for (int x = 0; x < 3; x++) {
				while (b[x] - a[x] > 180)b[x] -= 360;
				while (b[x] - a[x] < -180)b[x] += 360;
			}
		}
	}

	private static KeyData key(double time, double[] v) {
		KeyData k = new KeyData();
		k.time = Math.round(time * 1e6) / 1e6;
		k.interp = "linear";
		k.molang = false;
		k.v = new double[][] {{v[0], v[1], v[2]}};
		return k;
	}

	public static Result apply(Plan p, Result r) {
		if (p.mainKeys.isEmpty())return r.error("nothing", t("cpmplus.w3animtools.emote.err.nothing"));
		String name = p.name;
		if (AnimEdit.byName(name) != null)name = AnimEdit.uniqueAnimName(name, "");
		String fn = name;
		List<Animation> made = new ArrayList<>();
		PlusUndo.run(t("cpmplus.w3animtools.emote.undo"), UndoData.make(new Animation[0]), () -> UndoData.make(made.toArray(new Animation[0])), () -> {
			Animation main = AnimxUtil.newAnimation(fn, "gesture", p.emote.loop ? "loop" : "once", (p.mainEnd - p.mainStart) / 20.0, 20);
			main.additive = false;
			for (Map.Entry<String, List<KeyData>> en : p.mainKeys.entrySet()) {
				String ch = en.getKey().substring(en.getKey().indexOf('|') + 1);
				AnimEdit.writeKeys(main.getBoneAnimator(p.groups.get(en.getKey())), ch, en.getValue(), true);
			}
			made.add(main);
			if (p.setup) {
				Animation s = AnimxUtil.newAnimation("g:" + fn, "setup", "once", p.mainStart / 20.0, 20);
				s.additive = false;
				for (Map.Entry<String, List<KeyData>> en : p.setupKeys.entrySet()) {
					String ch = en.getKey().substring(en.getKey().indexOf('|') + 1);
					AnimEdit.writeKeys(s.getBoneAnimator(p.groups.get(en.getKey())), ch, en.getValue(), true);
				}
				made.add(s);
			}
		});
		r.changed = true;
		r.put("created", (double) made.size()).put("animName", fn);
		if (!made.isEmpty())made.get(0).select();
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** CPM+ &gt; Animação &gt; 'Importar emote (Emotecraft / PlayerAnimator)...' */
	public static void open() {
		Blockbench.ImportProperties ip = new Blockbench.ImportProperties();
		ip.extensions = new String[] {"json"};
		ip.type = t("cpmplus.w3animtools.emote.fileType");
		ip.readtype = "text";
		ip.resource_id = "cpmplus_emote";
		boolean ok = PlusDialogs.importFiles(ip, files -> {
			if (files == null || files.length == 0)return;
			Object content = Js.asPropertyMap(files[0]).get("content");
			String name = files[0].name != null ? files[0].name : files[0].path;
			Result r = importText(name, content != null ? String.valueOf(content) : "", true);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				return;
			}
			PlusDialogs.done(t("cpmplus.w3animtools.emote.done", String.valueOf(r.extra.get("animName"))));
			if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 10000, null);
		});
		if (!ok)PlusDialogs.block("cpmplus.w2tools.bedrock.err.noImport");
	}

	static Result importText(String fileName, String text, boolean apply) {
		Result r = new Result();
		Object json;
		try {
			json = parseJson(text);
		} catch (Throwable ex) {
			return r.error("json", t("cpmplus.w3animtools.emote.err.json"));
		}
		Emote e;
		try {
			e = parse(fileName, json);
		} catch (IllegalArgumentException ex) {
			return r.error("format", t(ex.getMessage()));
		}
		Plan p = plan(e, r);
		if (!apply)return r;
		return apply(p, r);
	}

	private static native Object parseJson(String s) /*-{
		return JSON.parse(s);
	}-*/;

	/** emoteImport({name, text, apply}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		return importText(AnimToolSupport.str(opts, "name", "emote.json"), AnimToolSupport.str(opts, "text", "{}"), AnimToolSupport.bool(opts, "apply", true)).toJs();
	}
}
