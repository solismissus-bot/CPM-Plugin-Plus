package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PartPairing;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Conferir movimento': read-only checks of what usually looks wrong in game, with a jump to the moment.
 * <ul>
 * <li>pico: a key far from both neighbours that are close to each other (a 1-frame spike)</li>
 * <li>degrau: a step key left among smooth keys of the same channel</li>
 * <li>emenda: in a loop, the end does not meet the start (jump) or meets it at a very different speed (jolt)</li>
 * <li>repouso: a one-shot gesture that does not start / end at rest (it jumps when it starts / ends)</li>
 * <li>robótico: a channel with only linear keys that turns around (stops dead at every key)</li>
 * <li>gêmeos: left and right parts with the same timing and the same (or mirrored) values (looks mechanical)</li>
 * </ul>
 * Uses the export's own sampling (the proxy interpolation) for speeds.
 */
public final class MotionCheck {
	private MotionCheck() {
	}

	public static final class Issue {
		public String code, anim, part;
		public Group group;
		public Animation animation;
		public double time;
		/** 3 = big, 2 = medium, 1 = small */
		public int level;
		public String text;
	}

	static final String[] CHS = {KeyMath.ROT, KeyMath.POS};

	public static List<Issue> check(List<Animation> anims) {
		List<Issue> out = new ArrayList<>();
		for (Animation a : anims)check(a, out);
		Collections.sort(out, (x, y) -> x.level != y.level ? Integer.compare(y.level, x.level) : Double.compare(x.time, y.time));
		return out;
	}

	static void check(Animation a, List<Issue> out) {
		Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
		boolean value = tp != null && (tp.getKey() == AnimationType.VALUE_LAYER || tp.getKey() == AnimationType.POSE && tp.getValue() != null && tp.getValue().hasStateGetter());
		boolean loop = "loop".equals(a.loop) && a.length > 1e-4 && !value && !AnimEdit.isStaged(a);
		boolean oneShotGesture = tp != null && tp.getKey() == AnimationType.GESTURE && !"loop".equals(a.loop);
		double frame = 1.0 / Math.max(1, AnimEdit.snappingOf(a));
		int robotic = 0;
		Issue firstRobot = null;
		for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null)continue;
			for (String ch : CHS) {
				List<KeyData> keys = AnimEdit.readKeys(ba, ch);
				if (keys.isEmpty())continue;
				boolean rot = KeyMath.ROT.equals(ch);
				double big = rot ? 25 : 2.5, close = rot ? 6 : 0.6;
				// pico
				for (int i = 1; i + 1 < keys.size(); i++) {
					KeyData p = keys.get(i - 1), c = keys.get(i), n = keys.get(i + 1);
					if (p.molang || c.molang || n.molang)continue;
					if (n.time - p.time > 4 * frame + 1e-6)continue;
					double dev = 0, nb = 0;
					for (int x = 0; x < 3; x++) {
						dev = Math.max(dev, Math.abs(c.v[0][x] - (p.v[p.v.length - 1][x] + n.v[0][x]) / 2));
						nb = Math.max(nb, Math.abs(p.v[p.v.length - 1][x] - n.v[0][x]));
					}
					if (dev > big && nb < close)out.add(issue("spike", a, g, c.time, 3, t("cpmplus.w3animtools.check.spike", fmt(dev, rot))));
				}
				// degrau esquecido
				int smooth = 0, steps = 0;
				KeyData stepKey = null;
				for (int i = 0; i + 1 < keys.size(); i++) {
					if ("step".equals(keys.get(i).interp)) {
						steps++;
						if (stepKey == null)stepKey = keys.get(i);
					} else smooth++;
				}
				if (steps > 0 && smooth >= 2 * steps && stepKey != null)out.add(issue("step", a, g, stepKey.time, 2, t("cpmplus.w3animtools.check.step")));
				// robótico: only linear keys and the motion turns around
				if (keys.size() >= 3 && allLinear(keys) && turns(keys)) {
					robotic++;
					if (firstRobot == null)firstRobot = issue("robotic", a, g, keys.get(1).time, 1, "");
				}
				// emenda do loop
				if (loop) {
					double[] v0 = AnimEdit.sample(ba, ch, 0), vL = AnimEdit.sample(ba, ch, a.length);
					double[] v1 = AnimEdit.sample(ba, ch, Math.min(a.length, frame)), vL1 = AnimEdit.sample(ba, ch, Math.max(0, a.length - frame));
					if (v0 != null && vL != null && v1 != null && vL1 != null) {
						double gap = 0, dSpeed = 0;
						for (int x = 0; x < 3; x++) {
							gap = Math.max(gap, Math.abs(vL[x] - v0[x]));
							double s0 = (v1[x] - v0[x]) / frame, s1 = (vL[x] - vL1[x]) / frame;
							dSpeed = Math.max(dSpeed, Math.abs(s0 - s1));
						}
						if (gap > (rot ? 2 : 0.2))out.add(issue("seamJump", a, g, a.length, 3, t("cpmplus.w3animtools.check.seamJump", fmt(gap, rot))));
						else if (dSpeed > (rot ? 300 : 30))out.add(issue("seamJolt", a, g, a.length, 2, t("cpmplus.w3animtools.check.seamJolt")));
					}
				}
				// gesto que não começa / termina no repouso
				if (oneShotGesture) {
					double[] rest = AnimxUtil.rest(ch);
					double[] v0 = AnimEdit.sample(ba, ch, 0), vL = AnimEdit.sample(ba, ch, a.length);
					double lim = rot ? 2 : 0.2;
					if (v0 != null && dist(v0, rest) > lim)out.add(issue("startRest", a, g, 0, 2, t("cpmplus.w3animtools.check.startRest", fmt(dist(v0, rest), rot))));
					if (vL != null && dist(vL, rest) > lim)out.add(issue("endRest", a, g, a.length, 2, t("cpmplus.w3animtools.check.endRest", fmt(dist(vL, rest), rot))));
				}
			}
		}
		if (firstRobot != null) {
			firstRobot.text = t("cpmplus.w3animtools.check.robotic", robotic);
			out.add(firstRobot);
		}
		twins(a, out);
		walkSync(a, tp, out);
	}

	/**
	 * An ADDITIVE walk / run keeping Minecraft's limb swing (distance based) while parts INSIDE the arms / legs move on
	 * CPM's clock (time based): the two never stay in step (the knee bends at the wrong moment, feet slide).
	 */
	static void walkSync(Animation a, Pair<AnimationType, VanillaPose> tp, List<Issue> out) {
		if (tp == null || tp.getKey() != AnimationType.POSE || !a.additive)return;
		VanillaPose p = tp.getValue();
		if (p != VanillaPose.WALKING && p != VanillaPose.RUNNING && p != VanillaPose.SNEAK_WALK)return;
		for (Group root : AnimxUtil.vanillaRoots()) {
			if (!AnimXray.isLimb(root))continue;
			for (Group d : AnimxUtil.descendants(root)) {
				BoneAnimator ba = AnimEdit.animatorOf(a, d);
				if (ba != null && (AnimxUtil.hasKeys(ba, KeyMath.ROT) || AnimxUtil.hasKeys(ba, KeyMath.POS))) {
					out.add(issue("walkSync", a, root, 0, 2, t("cpmplus.w3animtools.check.walkSync", d.name, root.name)));
					break;
				}
			}
		}
	}

	static boolean allLinear(List<KeyData> keys) {
		for (int i = 0; i + 1 < keys.size(); i++)if (!"linear".equals(keys.get(i).interp))return false;
		return true;
	}

	/** some axis goes up then down (or the other way): the motion stops and turns at a key */
	static boolean turns(List<KeyData> keys) {
		for (int x = 0; x < 3; x++) {
			int last = 0;
			for (int i = 1; i < keys.size(); i++) {
				if (keys.get(i).molang || keys.get(i - 1).molang)return false;
				double d = keys.get(i).v[0][x] - keys.get(i - 1).v[keys.get(i - 1).v.length - 1][x];
				int s = Math.abs(d) < 1e-3 ? 0 : d > 0 ? 1 : -1;
				if (s != 0 && last != 0 && s != last)return true;
				if (s != 0)last = s;
			}
		}
		return false;
	}

	/** left / right parts with the same key times and equal or mirrored rotation values */
	static void twins(Animation a, List<Issue> out) {
		Set<Group> seen = new LinkedHashSet<>();
		for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null || seen.contains(g))continue;
			PartPairing.Pair<Object> pr = AnimEdit.pairOf(g);
			if (pr == null || !PartPairing.sure(pr.kind) || !(pr.target instanceof Group) || pr.target == g)continue;
			Group o = (Group) pr.target;
			seen.add(g);
			seen.add(o);
			List<KeyData> ka = AnimEdit.readKeys(ba, KeyMath.ROT), kb = AnimEdit.readKeys(AnimEdit.animatorOf(a, o), KeyMath.ROT);
			if (ka.size() < 3 || ka.size() != kb.size())continue;
			boolean same = true, moves = false;
			for (int i = 0; i < ka.size() && same; i++) {
				KeyData x = ka.get(i), y = kb.get(i);
				if (x.molang || y.molang || Math.abs(x.time - y.time) > 1e-4) {
					same = false;
					break;
				}
				for (int c = 0; c < 3; c++) {
					double p = x.v[0][c], q = y.v[0][c];
					if (Math.abs(Math.abs(p) - Math.abs(q)) > 0.5)same = false;
					if (Math.abs(p) > 5)moves = true;
				}
			}
			if (same && moves)out.add(issue("twins", a, g, ka.get(1).time, 1, t("cpmplus.w3animtools.check.twins", g.name, o.name)));
		}
	}

	static double dist(double[] a, double[] b) {
		double d = 0;
		for (int i = 0; i < 3; i++)d = Math.max(d, Math.abs(a[i] - b[i]));
		return d;
	}

	static String fmt(double v, boolean rot) {
		return PoseColumn.fmt(v) + (rot ? "°" : " px");
	}

	static Issue issue(String code, Animation a, Group g, double time, int level, String text) {
		Issue i = new Issue();
		i.code = code;
		i.animation = a;
		i.anim = a.name;
		i.group = g;
		i.part = g.name;
		i.time = time;
		i.level = level;
		i.text = text;
		return i;
	}

	// ====================================================================== dialog

	/** goes to the moment: selects the animation and the part, moves the playhead */
	static void jump(Issue i) {
		if (Animation.selected != i.animation)i.animation.select();
		Timeline.setTime((float) Math.max(0, Math.min(i.animation.length, i.time)));
		try {
			i.group.select();
		} catch (Throwable e) {
			// selection is a convenience
		}
		AnimToolSupport.refreshPreview();
	}

	/** CPM+ &gt; Animação &gt; 'Conferir movimento...' (the selected animation; none selected = all) */
	public static void openDialog() {
		List<Animation> anims = Animation.selected != null ? Collections.singletonList(Animation.selected) : AnimEdit.all();
		List<Issue> is = check(anims);
		if (is.isEmpty()) {
			PlusDialogs.done(t("cpmplus.w3animtools.check.none"));
			return;
		}
		StringBuilder html = new StringBuilder("<table style='width:100%;border-collapse:collapse'>");
		int n = 0;
		for (Issue i : is) {
			if (n++ >= 30)break;
			String color = i.level == 3 ? "#e05050" : i.level == 2 ? "#d9a400" : "#7a9fd6";
			html.append("<tr><td style='white-space:nowrap;padding:2px 6px'><span style='color:").append(color).append("'>●</span> ")
			.append(PlusDialogs.esc(i.anim + " · " + i.part + " · " + PoseColumn.fmt(i.time) + " s")).append("</td><td style='padding:2px 6px'>")
			.append(PlusDialogs.esc(i.text)).append("</td></tr>");
		}
		html.append("</table>");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.check.intro", is.size())));
		rows.add(FormRow.htmlInfo("table", null, html.toString()));
		int bn = Math.min(6, is.size());
		String[] labels = new String[bn];
		for (int k = 0; k < bn; k++)labels[k] = (k + 1) + ". " + is.get(k).part + " " + PoseColumn.fmt(is.get(k).time) + "s";
		rows.add(FormRow.buttons("go", "cpmplus.w3animtools.check.go", labels, k -> jump(is.get(k))));
		FormOpts fo = new FormOpts();
		fo.width = 760;
		fo.confirmKey = "cpmplus.w3animtools.trans.report.close";
		PlusDialogs.form("cpmplus_w3animtools_check", "cpmplus.w3animtools.check.title", rows, fr -> {}, fo);
	}

	// ====================================================================== QA hook

	/** motionCheck({anim | all: true}) -&gt; {issues: [{code, anim, part, time, level}]} */
	public static Object hook(JsPropertyMap<Object> opts) {
		List<Animation> anims = AnimToolSupport.bool(opts, "all", false) ? AnimEdit.all() : Collections.singletonList(AnimToolSupport.anim(opts));
		JsArray<Object> arr = new JsArray<>();
		for (Issue i : check(anims)) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("code", i.code);
			m.set("anim", i.anim);
			m.set("part", i.part);
			m.set("time", Math.round(i.time * 1e4) / 1e4);
			m.set("level", i.level);
			m.set("text", i.text);
			arr.push(m);
		}
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("issues", arr);
		return r;
	}
}
