package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.shared.model.PlayerModelParts;

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Conferir movimento' row 'pé escorregando': in a walk / run / sneak-walk (or any animation when a floor speed is
 * picked by hand in 'Chão andando'), where a foot that is ON the ground slides against the ground moving backwards at
 * Minecraft's speed.
 * <ul>
 * <li>Leg chains: the vanilla legs (every block inside them) and groups named like a leg / foot / paw / hoof (pt / en,
 * the top-most one of each chain, with everything inside it). Hidden blocks are left out.</li>
 * <li>Sampled through Blockbench's own preview (only this animation, ~60 times per second): the 8 corners of every
 * block of the chain. The floor is the lowest point any leg reaches; a chain is 'on the ground' while its lowest point
 * is within 0.6 px of it (a chain that never gets there is a leg in the air, skipped).</li>
 * <li>Slip = how far the touching point moves against the ground: the corner that is lowest at one sample is followed
 * to the next sample (the same point of the block, so a foot that rolls heel to toe does not count as sliding) and
 * compared with the ground, which moves backwards (+Z, the player faces -Z) at speed x time. Summed over each stretch
 * on the ground; stretches over 0.5 px are reported with their time ranges (a looping animation joins the stretch that
 * runs over the end with the one at the start).</li>
 * <li>Additive animations on the vanilla legs are skipped: Minecraft adds its own leg swing there, which no preview
 * shows.</li>
 * </ul>
 */
public final class FootSlip {
	private FootSlip() {
	}

	/** reported when a stretch on the ground slides more than this (px) */
	public static final double LIMIT = 0.5;
	/** 'on the ground': lowest point within this of the floor (px) */
	static final double TOUCH = 0.6;
	/** a chain whose lowest point never comes this close to the floor is a leg in the air (px) */
	static final double NEVER = 2.0;
	/** the touching point rising / sinking faster than this (px / s) is a foot lifting off or landing, not planted */
	static final double LIFT = 4.0;

	static final Set<String> WORDS = new HashSet<>(Arrays.asList("leg", "legs", "perna", "pernas", "pe", "pes", "foot", "feet", "paw", "paws",
			"pata", "patas", "hoof", "hooves", "casco", "cascos", "coxa", "canela", "thigh", "shin", "calf", "tornozelo", "ankle"));

	/** a leg: the top group and its blocks */
	static final class Chain {
		final Group top;
		final boolean vanilla;
		final List<Cube> cubes = new ArrayList<>();

		Chain(Group top, boolean vanilla) {
			this.top = top;
			this.vanilla = vanilla;
		}
	}

	/** one stretch on the ground that slides */
	public static final class Slide {
		public double from, to, px;
	}

	/** the result for one chain */
	public static final class ChainResult {
		public String part;
		public boolean touches;
		public double worst, total;
		public final List<Slide> slides = new ArrayList<>();
	}

	// ====================================================================== legs

	static boolean legName(String name) {
		if (name == null)return false;
		String n = name.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n.length(); i++)sb.append(fold(n.charAt(i)));
		for (String w : sb.toString().split("[^a-z]+"))if (WORDS.contains(w))return true;
		return false;
	}

	/** accented Portuguese letters without the accent (GWT has no java.text.Normalizer) */
	static char fold(char c) {
		switch (c) {
		case 'á': case 'à': case 'â': case 'ã': case 'ä': return 'a';
		case 'é': case 'è': case 'ê': case 'ë': return 'e';
		case 'í': case 'ì': case 'î': case 'ï': return 'i';
		case 'ó': case 'ò': case 'ô': case 'õ': case 'ö': return 'o';
		case 'ú': case 'ù': case 'û': case 'ü': return 'u';
		case 'ç': return 'c';
		default: return c;
		}
	}

	static boolean vanillaLeg(Group g) {
		if (!AnimxUtil.isVanillaRoot(g))return false;
		Object p = g.getRootType();
		return p == PlayerModelParts.LEFT_LEG || p == PlayerModelParts.RIGHT_LEG;
	}

	static boolean under(Group g, Set<Group> tops) {
		for (Group p = AnimToolSupport.parentGroup(g); p != null; p = AnimToolSupport.parentGroup(p))if (tops.contains(p))return true;
		return false;
	}

	/** the leg chains of the model: vanilla legs first, then the top-most leg-named groups outside them */
	static List<Chain> chains() {
		List<Chain> out = new ArrayList<>();
		Set<Group> tops = new HashSet<>();
		if (Group.all == null)return out;
		for (Group g : Group.all)if (vanillaLeg(g)) {
			out.add(new Chain(g, true));
			tops.add(g);
		}
		for (Group g : Group.all) {
			if (tops.contains(g) || !legName(g.name) || under(g, tops))continue;
			boolean nested = false;
			for (Group p = AnimToolSupport.parentGroup(g); p != null; p = AnimToolSupport.parentGroup(p))if (legName(p.name)) {
				nested = true;
				break;
			}
			if (nested)continue;
			out.add(new Chain(g, false));
			tops.add(g);
		}
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; cubesAll != null && i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			if (c == null || !c.visibility || c.mesh == null)continue;
			for (Chain ch : out) {
				Group p = c.parent instanceof Group ? c.parent : null;
				boolean inside = false;
				for (Group q = p; q != null; q = AnimToolSupport.parentGroup(q))if (q == ch.top) {
					inside = true;
					break;
				}
				if (!inside)continue;
				// the vanilla block of a leg hidden in CPM (root 'hidden') is not drawn in game
				if (p == ch.top && ch.vanilla && ch.top.hidden)break;
				ch.cubes.add(c);
				break;
			}
		}
		out.removeIf(c -> c.cubes.isEmpty());
		return out;
	}

	// ====================================================================== check

	/** an additive animation with keys on a vanilla leg: Minecraft adds its own leg swing there (not checked) */
	static Group additiveVanillaLeg(Animation a) {
		if (a == null || !a.additive)return null;
		for (Chain c : chains()) {
			if (!c.vanilla)continue;
			for (Group g : withDescendants(c.top)) {
				com.tom.cpm.blockbench.proxy.BoneAnimator ba = com.tom.cpm.blockbench.util.AnimEdit.animatorOf(a, g);
				if (ba != null && (AnimxUtil.hasKeys(ba, AnimxUtil.ROT) || AnimxUtil.hasKeys(ba, AnimxUtil.POS)))return c.top;
			}
		}
		return null;
	}

	static List<Group> withDescendants(Group g) {
		List<Group> l = new ArrayList<>();
		l.add(g);
		l.addAll(AnimxUtil.descendants(g));
		return l;
	}

	/** MotionCheck row: issues 'footSlip' for this animation (nothing when it has no floor speed) */
	static void check(Animation a, List<MotionCheck.Issue> out) {
		double speed = MovingGround.speedFor(a);
		if (speed <= 0 || a == null || a.length < 1e-3)return;
		Group skipped = additiveVanillaLeg(a);
		if (skipped != null)out.add(MotionCheck.issue("footSlipAdditive", a, skipped, 0, 1, t("cpmplus.w34proa.slip.additive", a.name)));
		for (ChainResult cr : analyse(a, speed)) {
			if (cr.slides.isEmpty())continue;
			StringBuilder ranges = new StringBuilder();
			int n = 0;
			for (Slide s : cr.slides) {
				if (n++ >= 3) {
					ranges.append(" …");
					break;
				}
				if (ranges.length() > 0)ranges.append("; ");
				ranges.append(num(s.from, 2)).append("–").append(num(s.to, 2)).append(" s");
			}
			Group g = AnimxUtil.groupByUuidOrName(cr.part);
			if (g == null)continue;
			int level = cr.worst >= 6 ? 3 : cr.worst >= 2 ? 2 : 1;
			out.add(MotionCheck.issue("footSlip", a, g, cr.slides.get(0).from, level,
					t("cpmplus.w34proa.slip.row", g.name, num(cr.worst, 1), ranges.toString(), num(speed, 3))));
		}
	}

	/** a number for the texts: d decimals at most, comma in Portuguese */
	static String num(double v, int d) {
		String s = com.tom.cpm.blockbench.util.KeyMath.fmt(v, d);
		String loc = com.tom.cpm.web.client.util.I18n.locale;
		return loc != null && loc.toLowerCase(Locale.ROOT).startsWith("pt") ? s.replace('.', ',') : s;
	}

	/** every leg chain of the model, sampled over one cycle of the animation with the floor at 'speed' blocks / s */
	public static List<ChainResult> analyse(Animation a, double speed) {
		List<ChainResult> res = new ArrayList<>();
		List<Chain> chains = chains();
		if (a.additive)chains.removeIf(c -> c.vanilla);
		if (chains.isEmpty())return res;
		double len = a.length;
		int n = Math.max(48, Math.min(480, (int) Math.round(len * 60)));
		double[] times = new double[n + 1];
		for (int k = 0; k <= n; k++)times[k] = len * k / n;
		List<Object> cubeList = new ArrayList<>();
		int[] first = new int[chains.size() + 1];
		for (int c = 0; c < chains.size(); c++) {
			first[c] = cubeList.size();
			cubeList.addAll(chains.get(c).cubes);
		}
		first[chains.size()] = cubeList.size();
		int cubes = cubeList.size();
		double[] raw = sample(a, cubeList.toArray(), times);
		AnimToolSupport.refreshPreview();
		if (!AnimToolSupport.animateMode())restPose();
		// lowest corner of each chain at each sample
		double[][] low = new double[chains.size()][n + 1];
		int[][] arg = new int[chains.size()][n + 1];
		double floor = Double.MAX_VALUE;
		for (int c = 0; c < chains.size(); c++) {
			for (int k = 0; k <= n; k++) {
				double best = Double.MAX_VALUE;
				int bi = -1;
				for (int q = first[c] * 8; q < first[c + 1] * 8; q++) {
					double y = raw[(k * cubes * 8 + q) * 3 + 1];
					if (y < best) {
						best = y;
						bi = q;
					}
				}
				low[c][k] = best;
				arg[c][k] = bi;
				floor = Math.min(floor, best);
			}
		}
		double g = speed * MovingGround.PX;// px / s backwards (+Z)
		boolean loop = "loop".equals(a.loop);
		for (int c = 0; c < chains.size(); c++) {
			ChainResult cr = new ChainResult();
			cr.part = chains.get(c).top.uuid;
			double minC = Double.MAX_VALUE;
			for (int k = 0; k <= n; k++)minC = Math.min(minC, low[c][k]);
			cr.touches = minC <= floor + NEVER;
			res.add(cr);
			if (!cr.touches)continue;
			// stretches on the ground: steps k -> k+1 with both samples touching
			List<double[]> runs = new ArrayList<>();// {fromK, toK, slide}
			double[] cur = null;
			for (int k = 0; k < n; k++) {
				int q = arg[c][k];
				int o0 = (k * cubes * 8 + q) * 3, o1 = ((k + 1) * cubes * 8 + q) * 3;
				double dt = times[k + 1] - times[k];
				// planted: at the floor at both samples and the touching point neither lifting off nor landing
				boolean touch = low[c][k] <= floor + TOUCH && low[c][k + 1] <= floor + TOUCH && Math.abs(raw[o1 + 1] - raw[o0 + 1]) <= LIFT * dt;
				if (!touch) {
					cur = null;
					continue;
				}
				double dx = raw[o1] - raw[o0], dz = raw[o1 + 2] - raw[o0 + 2] - g * dt;
				double s = Math.sqrt(dx * dx + dz * dz);
				if (cur == null) {
					cur = new double[] {k, k + 1, 0};
					runs.add(cur);
				}
				cur[1] = k + 1;
				cur[2] += s;
			}
			// a loop: the stretch over the end continues at the start
			if (loop && runs.size() > 1) {
				double[] last = runs.get(runs.size() - 1), firstRun = runs.get(0);
				if (last[1] == n && firstRun[0] == 0) {
					firstRun[0] = last[0] - n;
					firstRun[2] += last[2];
					runs.remove(runs.size() - 1);
				}
			}
			for (double[] r : runs) {
				cr.total += r[2];
				if (r[2] <= LIMIT)continue;
				Slide s = new Slide();
				s.from = r[0] < 0 ? times[(int) (r[0] + n)] : times[(int) r[0]];
				s.to = times[(int) r[1]];
				s.px = r[2];
				cr.slides.add(s);
				cr.worst = Math.max(cr.worst, r[2]);
			}
		}
		return res;
	}

	/** world corners (8 per block, minus the scene offset) of every block at every time, only this animation playing */
	private static native double[] sample(Object anim, Object[] cubes, double[] times) /*-{
		var A = $wnd.Animator, TL = $wnd.Timeline, T = $wnd.THREE;
		var out = [], save = TL.time, v = new T.Vector3();
		var sp = $wnd.scene ? $wnd.scene.position : new T.Vector3();
		var boxes = [];
		for (var i = 0; i < cubes.length; i++) {
			var m = cubes[i].mesh, bb = null;
			if (m && m.geometry) {
				if (!m.geometry.boundingBox) m.geometry.computeBoundingBox();
				bb = m.geometry.boundingBox;
			}
			boxes.push(bb);
		}
		try {
			$wnd.__cpmplusNoStepped = true;// the 'stepped preview' must not change what is sampled
			for (var k = 0; k < times.length; k++) {
				TL.time = times[k];
				A.showDefaultPose(true);
				A.stackAnimations([anim], false);
				for (var i2 = 0; i2 < cubes.length; i2++) {
					var m2 = cubes[i2].mesh, b = boxes[i2];
					if (!m2 || !b) { for (var c0 = 0; c0 < 8; c0++) out.push(0, 1e9, 0); continue; }
					m2.updateWorldMatrix(true, false);
					for (var c = 0; c < 8; c++) {
						v.set(c & 1 ? b.max.x : b.min.x, c & 2 ? b.max.y : b.min.y, c & 4 ? b.max.z : b.min.z).applyMatrix4(m2.matrixWorld).sub(sp);
						out.push(v.x, v.y, v.z);
					}
				}
			}
		} finally {
			$wnd.__cpmplusNoStepped = false;
			TL.time = save;
			if (A.resetLastValues) A.resetLastValues();
		}
		return out;
	}-*/;

	private static native void restPose() /*-{
		try { if ($wnd.Animator) $wnd.Animator.showDefaultPose(); } catch (e) {}
	}-*/;

	// ====================================================================== QA hook

	/** footSlip({anim, speed (blocks/s; default = the floor speed)}) -&gt; {speed, chains: [{part, touches, worst, total, slides: [{from, to, px}]}]} */
	public static Object hook(JsPropertyMap<Object> opts) {
		Animation a = AnimToolSupport.anim(opts);
		JsPropertyMap<Object> r = JsPropertyMap.of();
		if (a == null) {
			r.set("error", "no animation");
			return r;
		}
		double speed = AnimToolSupport.num(opts, "speed", MovingGround.speedFor(a));
		r.set("speed", speed);
		Group skipped = additiveVanillaLeg(a);
		r.set("additiveSkipped", skipped != null ? skipped.name : null);
		JsArray<Object> arr = new JsArray<>();
		if (speed > 0)for (ChainResult cr : analyse(a, speed)) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			Group g = AnimxUtil.groupByUuidOrName(cr.part);
			m.set("part", g != null ? g.name : cr.part);
			m.set("touches", cr.touches);
			m.set("worst", Math.round(cr.worst * 1000) / 1000.0);
			m.set("total", Math.round(cr.total * 1000) / 1000.0);
			JsArray<Object> sl = new JsArray<>();
			for (Slide s : cr.slides) {
				JsPropertyMap<Object> x = JsPropertyMap.of();
				x.set("from", Math.round(s.from * 1e4) / 1e4);
				x.set("to", Math.round(s.to * 1e4) / 1e4);
				x.set("px", Math.round(s.px * 1000) / 1000.0);
				sl.push(x);
			}
			m.set("slides", sl);
			arr.push(m);
		}
		r.set("chains", arr);
		return r;
	}
}
