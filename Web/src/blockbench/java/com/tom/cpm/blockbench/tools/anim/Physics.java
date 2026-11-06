package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
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
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
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

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Física / seguir o movimento': bakes follow-through (tails, ears, hair, capes...) for one animation into a
 * separate ADDITIVE layer that plays together with it in game.
 * <ul>
 * <li>The layer is named '&lt;name&gt;#física' (or '&lt;name&gt; física' when the name already has a '#'): CPM's
 * exporter keys every pose / gesture / layer button by the text before the first '#', so in game both animations
 * are ONE button and start together; vanilla poses start together anyway. Same type and loop mode as the original,
 * every CPM setting copied, additive on, priority above every animation that moves those parts (CPM applies
 * animations in ascending priority, so the additive layer always lands on top).</li>
 * <li>Its duration is the original's exported duration and its keys sit on a uniform grid (linear, closing key =
 * first key), so both have the same cycle in game and stay in phase forever.</li>
 * <li>The driving motion is sampled through Blockbench's own preview pipeline (Animator.stackAnimations of the
 * original alone), so the offsets match what the export writes. Looping animations run until two cycles agree
 * (&lt; 0.1 degree, at most 20 cycles); one-shot animations (gestures 'once', 'hold') start at rest and fade back to
 * rest over the last 0.2 s.</li>
 * <li>Optional 'game forces': the wind of running / falling / flying / swimming and the jump impulse, approximations
 * of the player's movement that the animation itself does not contain. Vanilla arm / leg swing is NOT included.</li>
 * <li>Value-driven animations (value layers, poses driven by a value) and staged ones are refused.</li>
 * </ul>
 * One undo entry. The dialog previews the layer live (temporary animation playing with the original, removed on
 * OK / Cancel) and can play the timeline while the sliders move. Running it again updates the existing layer.
 */
public final class Physics {
	private Physics() {
	}

	static final String PREF = "cpmplus.physics.";
	/** layer name suffixes recognised in any language (the new layer gets the translated one) */
	static final String[] SUFFIXES = {"física", "fisica", "physics"};
	static final String[] PRESETS = {"tail", "ear", "hair", "cape", "antenna", "feather", "custom"};
	/** floppy, bounce, weight, maxAngle, tipLoose */
	static final double[][] PRESET_VALUES = {
			{55, 35, 10, 45, 50},
			{35, 45, 0, 25, 20},
			{60, 20, 30, 35, 40},
			{70, 10, 40, 50, 30},
			{30, 75, 0, 30, 60},
			{45, 55, 5, 30, 40},
	};
	static final int MAX_CYCLES = 20;
	static final double CONVERGE_DEG = 0.1;

	/** what the user sets (0..100 sliders, degrees, percent) */
	public static final class Settings {
		public String preset = "tail";
		public double floppy = 55, bounce = 35, weight = 10, maxAngle = 45, tipLoose = 50, mix = 100;
		public boolean gameForces = true;
		public boolean children = true;

		void preset(String id) {
			preset = id;
			for (int i = 0; i < PRESET_VALUES.length; i++)if (PRESETS[i].equals(id)) {
				floppy = PRESET_VALUES[i][0];
				bounce = PRESET_VALUES[i][1];
				weight = PRESET_VALUES[i][2];
				maxAngle = PRESET_VALUES[i][3];
				tipLoose = PRESET_VALUES[i][4];
			}
		}
	}

	// ====================================================================== the original animation

	/** extra movement of the player in this pose (model space: front = -Z, up = +Y) */
	static final class Force {
		final String key;
		final double[] wind;
		final double impulseY, impulseTime;

		Force(String key, double wx, double wy, double wz, double impulseY, double impulseTime) {
			this.key = key;
			this.wind = new double[] {wx, wy, wz};
			this.impulseY = impulseY;
			this.impulseTime = impulseTime;
		}
	}

	static Force forceOf(VanillaPose pose) {
		if (pose == null)return null;
		switch (pose) {
		case WALKING:
			return new Force("walk", 0, 0, 60, 0, 0);
		case SNEAK_WALK:
			return new Force("sneakWalk", 0, 0, 35, 0, 0);
		case RUNNING:
			return new Force("run", 0, 0, 160, 0, 0);
		case CREATIVE_FLYING:
			return new Force("creativeFly", 0, 0, 120, 0, 0);
		case RETRO_SWIMMING:
			return new Force("retroSwim", 0, 0, 80, 0, 0);
		case RIDING:
			return new Force("ride", 0, 0, 50, 0, 0);
		case FALLING:
			return new Force("fall", 0, 220, 0, 0, 0);
		case FLYING:
			return new Force("elytra", 0, -220, 0, 0, 0);
		case SWIMMING:
			return new Force("swim", 0, -140, 0, 0, 0);
		case CRAWLING:
			return new Force("crawl", 0, -60, 0, 0, 0);
		case JUMPING:
			return new Force("jump", 0, 0, 0, -400, 0.12);
		default:
			return null;
		}
	}

	static final class Source {
		Animation anim;
		AnimationType type;
		VanillaPose pose;
		boolean loop;
		/** Blockbench length (s) */
		double len;
		/** the exported duration (s): one cycle in game */
		double period;
		Force force;
		/** lang key when this animation cannot get physics */
		String errorKey;
	}

	static Source source(Animation a) {
		Source s = new Source();
		s.anim = a;
		if (a == null) {
			s.errorKey = "cpmplus.w3animtools.err.noAnimation";
			return s;
		}
		Pair<AnimationType, VanillaPose> tp;
		try {
			tp = a.type != null ? Animation.parseType(a.type) : Pair.of(AnimationType.CUSTOM_POSE, null);
		} catch (Throwable e) {
			tp = Pair.of(AnimationType.CUSTOM_POSE, null);
		}
		s.type = tp.getKey();
		s.pose = s.type == AnimationType.POSE ? tp.getValue() : null;
		if (isLayerName(a.name))s.errorKey = "cpmplus.w3animtools.physics.err.isLayer";
		else if (AnimEdit.isStaged(a) || s.type.isStaged())s.errorKey = "cpmplus.w3animtools.physics.err.staged";
		else if (s.type == AnimationType.VALUE_LAYER || s.pose != null && s.pose.hasStateGetter())s.errorKey = "cpmplus.w3animtools.physics.err.value";
		String lm = a.loop != null ? a.loop : "loop";
		s.loop = !("once".equals(lm) || "hold".equals(lm));
		s.len = Math.max(0, a.length);
		AnimEdit.Frames fr = AnimEdit.predictFrames(a);
		s.period = Math.max(0.05, fr.durationMs / 1000.0);
		s.force = forceOf(s.pose);
		return s;
	}

	// ====================================================================== names

	/** '&lt;name&gt;#física' (same button in game), or '&lt;name&gt; física' when the name already has a part */
	static String layerName(String src) {
		return LayerSupport.nameFor(src, t("cpmplus.w3animtools.physics.suffix"));
	}

	/** a companion layer of any kind (physics, correction): never the original of a physics layer */
	static boolean isLayerName(String name) {
		return LayerSupport.sourceNameOf(name, SUFFIXES) != null || LayerSupport.sourceNameOf(name, CorrectionLayer.SUFFIXES) != null;
	}

	/** the existing physics layer of an animation (any language suffix), or null */
	static Animation layerOf(Animation src) {
		return LayerSupport.companionOf(src, SUFFIXES);
	}

	/** the animation the tool works on: the selected one, or the original when its physics layer is selected */
	static Animation originalOf(Animation a) {
		return LayerSupport.originalOf(a, SUFFIXES);
	}

	// ====================================================================== the simulated parts

	static final class Links {
		final List<Group> groups = new ArrayList<>();
		int[] parent, depth;
		double[] tipLocal;
		int roots, empty;
	}

	private static int depthOf(Group g) {
		int d = 0;
		Object p = AnimEdit.OUTLINER.parent(g);
		while (p != null && d < 1000) {
			d++;
			p = AnimEdit.OUTLINER.parent(p);
		}
		return d;
	}

	private static boolean under(Group g, Set<Group> set) {
		Object p = AnimEdit.OUTLINER.parent(g);
		int guard = 0;
		while (p != null && guard++ < 1000) {
			if (p instanceof Group && set.contains(p))return true;
			p = AnimEdit.OUTLINER.parent(p);
		}
		return false;
	}

	/** the parts to simulate: the picked ones (never a vanilla root) plus, with children, every group inside them */
	static Links links(List<Group> picked, boolean children) {
		Links l = new Links();
		Set<Group> pick = new LinkedHashSet<>();
		for (Group g : picked) {
			if (g == null)continue;
			if (AnimEdit.isRoot(g)) {
				l.roots++;
				continue;
			}
			pick.add(g);
		}
		Set<Group> all = new LinkedHashSet<>();
		for (Group g : pick) {
			if (children && under(g, pick))continue;
			all.add(g);
			if (children)all.addAll(AnimxUtil.descendants(g));
		}
		List<Group> list = new ArrayList<>(all);
		Map<Group, Integer> depth = new LinkedHashMap<>();
		for (Group g : list)depth.put(g, depthOf(g));
		Collections.sort(list, (a, b) -> Integer.compare(depth.get(a), depth.get(b)));// stable: parents first
		l.groups.addAll(list);
		int n = list.size();
		l.parent = new int[n];
		l.depth = new int[n];
		l.tipLocal = new double[n * 3];
		for (int i = 0; i < n; i++) {
			Group g = list.get(i);
			l.parent[i] = -1;
			Object p = AnimEdit.OUTLINER.parent(g);
			int guard = 0;
			while (p != null && guard++ < 1000) {
				int idx = list.indexOf(p);
				if (idx >= 0) {
					l.parent[i] = idx;
					break;
				}
				p = AnimEdit.OUTLINER.parent(p);
			}
			l.depth[i] = l.parent[i] >= 0 ? l.depth[l.parent[i]] + 1 : 0;
			double[] tip = tipOf(g, list);
			System.arraycopy(tip, 0, l.tipLocal, i * 3, 3);
			if (PhysicsSim.len(tip) < 0.05)l.empty++;
		}
		return l;
	}

	/**
	 * Where the part ends, in its own frame (relative to its pivot, Blockbench's local axes): the middle of the
	 * pivots of its simulated children; else the far end of its blocks (and of the blocks of groups inside it that
	 * are not simulated) along the direction pivot -&gt; middle of the blocks.
	 */
	static double[] tipOf(Group g, List<Group> links) {
		double[] o = {g.origin.x, g.origin.y, g.origin.z};
		double[] sum = new double[3];
		int nc = 0;
		if (g.children != null)for (int i = 0; i < g.children.length; i++) {
			Object c = g.children.getAt(i);
			if (c instanceof Group && links.contains(c)) {
				Group cg = (Group) c;
				sum[0] += cg.origin.x - o[0];
				sum[1] += cg.origin.y - o[1];
				sum[2] += cg.origin.z - o[2];
				nc++;
			}
		}
		if (nc > 0) {
			double[] avg = PhysicsSim.scale(sum, 1.0 / nc);
			if (PhysicsSim.len(avg) >= 0.05)return avg;
		}
		List<double[]> corners = new ArrayList<>();
		double[] cen = new double[3];
		int cubes = collectCorners(g, links, o, corners, cen, true);
		if (cubes == 0)return new double[3];
		cen = PhysicsSim.scale(cen, 1.0 / cubes);
		double[] dir;
		if (PhysicsSim.len(cen) >= 0.05)dir = PhysicsSim.scale(cen, 1 / PhysicsSim.len(cen));
		else {
			double[] far = null;
			double fl = 0;
			for (double[] c : corners) {
				double cl = PhysicsSim.len(c);
				if (cl > fl) {
					fl = cl;
					far = c;
				}
			}
			if (far == null || fl < 0.05)return new double[3];
			dir = PhysicsSim.scale(far, 1 / fl);
		}
		double len = 0;
		for (double[] c : corners)len = Math.max(len, PhysicsSim.dot(c, dir));
		return PhysicsSim.scale(dir, len);
	}

	private static int collectCorners(Group g, List<Group> links, double[] o, List<double[]> corners, double[] cen, boolean top) {
		int n = 0;
		if (g.children == null)return 0;
		for (int i = 0; i < g.children.length; i++) {
			Object c = g.children.getAt(i);
			if (c instanceof Cube) {
				Cube cb = (Cube) c;
				if (cb.from == null || cb.to == null)continue;
				double[] f = {cb.from.x - o[0], cb.from.y - o[1], cb.from.z - o[2]};
				double[] t = {cb.to.x - o[0], cb.to.y - o[1], cb.to.z - o[2]};
				for (int k = 0; k < 8; k++)corners.add(new double[] {(k & 1) == 0 ? f[0] : t[0], (k & 2) == 0 ? f[1] : t[1], (k & 4) == 0 ? f[2] : t[2]});
				cen[0] += (f[0] + t[0]) / 2;
				cen[1] += (f[1] + t[1]) / 2;
				cen[2] += (f[2] + t[2]) / 2;
				n++;
			} else if (c instanceof Group && !links.contains(c)) {
				n += collectCorners((Group) c, links, o, corners, cen, false);
			}
		}
		return n;
	}

	// ====================================================================== sampling (Blockbench's own pipeline)

	static final class Prepared {
		Source src;
		Links links;
		/** key intervals of the layer (keys 0..frames) */
		int frames;
		/** simulation samples per key interval */
		int perKey;
		PhysicsSim.Frame[] samples;
		/** the samples as numbers (debug / QA) */
		int sampleCount;
		double sampleMs;
	}

	/** frames of the layer: the original's snapping (20..60 per second), at most 254 intervals */
	static int layerFrames(Source s) {
		int fps = Math.max(20, Math.min(60, AnimEdit.snappingOf(s.anim)));
		return Math.max(4, Math.min(254, (int) Math.round(s.period * fps)));
	}

	static Prepared prepare(Source src, Links links) {
		Prepared p = new Prepared();
		p.src = src;
		p.links = links;
		p.frames = layerFrames(src);
		double fs = src.period <= 4 ? 120 : 60;
		int m = Math.max(1, (int) Math.ceil(fs * src.period / p.frames));
		while (m > 1 && p.frames * m > 3000)m--;
		p.perKey = m;
		int S = src.loop ? p.frames * m : p.frames * m + 1;
		double[] taus = new double[S];
		for (int s = 0; s < S; s++)taus[s] = src.loop ? s * src.period / S : s * src.period / (S - 1);
		int n = links.groups.size();
		double len = src.len;
		// Blockbench times: the sample time when the original is still running, else its end; one more at 0
		double[] times = new double[S + 1];
		for (int s = 0; s < S; s++)times[s] = Math.min(taus[s], len);
		times[S] = 0;
		Object[] groups = links.groups.toArray();
		double t0 = DomGlobal.performance.now();
		double[] raw = sampleNative(src.anim, groups, links.tipLocal, times);
		p.sampleMs = DomGlobal.performance.now() - t0;
		AnimToolSupport.refreshPreview();
		int stride = n * PhysicsSim.STRIDE;
		PhysicsSim.Frame[] fr = new PhysicsSim.Frame[S];
		PhysicsSim.Frame atZero = frameAt(raw, S, stride);
		PhysicsSim.Frame atEnd = null;
		for (int s = 0; s < S; s++) {
			PhysicsSim.Frame f = frameAt(raw, s, stride);
			if (taus[s] > len + 1e-6 && src.loop && src.period - len > 1e-6) {
				// CPM interpolates from the last frame back to the first one before the cycle restarts
				if (atEnd == null)atEnd = f;
				f = PhysicsSim.Frame.lerp(atEnd, atZero, (taus[s] - len) / (src.period - len), n);
			}
			fr[s] = f;
		}
		p.samples = fr;
		p.sampleCount = S;
		return p;
	}

	private static PhysicsSim.Frame frameAt(double[] raw, int s, int stride) {
		// raw is a plain JS array (no Java array type): System.arraycopy would throw ArrayStoreException
		double[] d = new double[stride];
		int o = s * stride;
		for (int k = 0; k < stride; k++)d[k] = raw[o + k];
		return new PhysicsSim.Frame(d);
	}

	/**
	 * For each time: the animation alone on the model (Animator.showDefaultPose + stackAnimations, exactly the preview)
	 * and, per group: pivot world position, tip world position, world quaternion, parent world quaternion, local Euler.
	 */
	private static native double[] sampleNative(Animation anim, Object[] groups, double[] tipLocal, double[] times) /*-{
		var A = $wnd.Animator, TL = $wnd.Timeline, T = $wnd.THREE;
		var out = [];
		var save = TL.time;
		var p = new T.Vector3(), v = new T.Vector3(), q = new T.Quaternion();
		try {
			$wnd.__cpmplusNoStepped = true;// the 'stepped preview' must not change what is sampled
			for (var k = 0; k < times.length; k++) {
				TL.time = times[k];
				A.showDefaultPose(true);
				A.stackAnimations([anim], false);
				for (var i = 0; i < groups.length; i++) {
					var m = groups[i].mesh;
					if (!m) { for (var z = 0; z < 17; z++) out.push(z == 9 || z == 13 ? 1 : 0); continue; }
					m.updateWorldMatrix(true, false);
					m.getWorldPosition(p);
					out.push(p.x, p.y, p.z);
					v.set(tipLocal[3 * i], tipLocal[3 * i + 1], tipLocal[3 * i + 2]).applyMatrix4(m.matrixWorld);
					out.push(v.x, v.y, v.z);
					m.getWorldQuaternion(q);
					out.push(q.x, q.y, q.z, q.w);
					if (m.parent) m.parent.getWorldQuaternion(q); else q.set(0, 0, 0, 1);
					out.push(q.x, q.y, q.z, q.w);
					out.push(m.rotation.x, m.rotation.y, m.rotation.z);
				}
			}
		} finally {
			$wnd.__cpmplusNoStepped = false;
			TL.time = save;
			if (A.resetLastValues) A.resetLastValues();
		}
		return out;
	}-*/;

	// ====================================================================== bake

	static final class Baked {
		Settings settings;
		PhysicsSim.Out out;
		/** keys per simulated part that actually moves */
		final Map<Group, List<KeyData>> keys = new LinkedHashMap<>();
		double maxDeg;
	}

	static PhysicsSim.Params params(Settings s, Source src) {
		PhysicsSim.Params p = new PhysicsSim.Params();
		p.omega = 40 * Math.pow(6 / 40.0, clamp(s.floppy, 0, 100) / 100);
		p.zeta = Math.pow(0.08, clamp(s.bounce, 0, 100) / 100);
		p.tipLoose = clamp(s.tipLoose, 0, 100) / 100;
		p.gravity = clamp(s.weight, 0, 100) * 3;
		p.maxAngle = Math.toRadians(clamp(s.maxAngle, 1, 90));
		if (s.gameForces && src.force != null) {
			p.wind = new double[] {src.force.wind[0], src.force.wind[1], src.force.wind[2]};
			p.impulseY = src.force.impulseY;
			p.impulseTime = src.force.impulseTime;
		}
		return p;
	}

	static Baked bake(Prepared pr, Settings s) {
		Baked b = new Baked();
		b.settings = s;
		Source src = pr.src;
		b.out = PhysicsSim.run(pr.links.parent, pr.links.depth, pr.samples, src.loop, src.period, params(s, src), MAX_CYCLES, CONVERGE_DEG);
		int n = pr.links.groups.size(), F = pr.frames, m = pr.perKey, S = pr.samples.length;
		double mix = clamp(s.mix, 0, 150) / 100;
		double tail = Math.min(0.2, 0.3 * src.period);
		for (int i = 0; i < n; i++) {
			List<KeyData> keys = new ArrayList<>();
			double max = 0;
			for (int k = 0; k <= F; k++) {
				int idx = src.loop ? (k * m) % S : Math.min(S - 1, k * m);
				double time = k * src.period / F;
				double f = mix;
				if (!src.loop && time > src.period - tail) {
					double u = Math.max(0, Math.min(1, (src.period - time) / tail));
					f *= u * u * (3 - 2 * u);
				}
				double[] v = {b.out.deltas[idx][i * 3] * f, b.out.deltas[idx][i * 3 + 1] * f, b.out.deltas[idx][i * 3 + 2] * f};
				for (double d : v)max = Math.max(max, Math.abs(d));
				KeyData kd = new KeyData();
				kd.time = Math.round(time * 1e6) / 1e6;
				kd.interp = "linear";
				kd.molang = false;
				kd.v = new double[][] {v};
				keys.add(kd);
			}
			if (max >= 0.01)b.keys.put(pr.links.groups.get(i), keys);
			b.maxDeg = Math.max(b.maxDeg, max);
		}
		return b;
	}

	// ====================================================================== write the layer (one undo entry)

	static void writeKeys(Animation a, Baked b, boolean clearFirst) {
		if (clearFirst)for (BoneAnimator ba : AnimEdit.boneAnimators(a))AnimEdit.clearChannel(ba, KeyMath.ROT);
		for (Map.Entry<Group, List<KeyData>> e : b.keys.entrySet()) {
			BoneAnimator ba = a.getBoneAnimator(e.getKey());
			AnimEdit.writeKeys(ba, KeyMath.ROT, e.getValue(), true);
		}
	}

	/** the original's settings on the layer: every cpm_* property, then additive, length, loop, grid */
	static void setupLayer(Animation layer, Prepared pr, int priority) {
		LayerSupport.setup(layer, pr.src.anim, priority, pr.src.period);
		int fps = (int) Math.round(pr.frames / pr.src.period);
		Js.asPropertyMap(layer).set("snapping", (double) Math.max(1, Math.min(500, fps)));
	}

	/**
	 * The original + every NON-additive animation that moves one of the simulated parts: they must play below the
	 * layer (a non-additive animation applied later would replace the offsets). Additive ones add up in any order.
	 */
	static Set<Animation> touching(Prepared pr, Animation exclude) {
		return LayerSupport.nonAdditiveTouching(pr.src.anim, pr.links.groups, exclude);
	}

	static Result apply(Prepared pr, Baked b) {
		Result r = new Result();
		Source src = pr.src;
		if (b.keys.isEmpty())return r.error("nothing", t("cpmplus.w3animtools.physics.err.nothing"));
		Animation existing = layerOf(src.anim);
		Set<Animation> touch = touching(pr, existing);
		int maxP = Integer.MIN_VALUE;
		for (Animation a : touch)maxP = Math.max(maxP, a.getPriority());
		AnimEdit.PriorityPlan plan;
		if (existing != null && existing.getPriority() > maxP)plan = null;// already on top
		else {
			plan = AnimEdit.nextPriority(touch);
			if (!plan.ok)return r.error("priority", t(plan.messageKey));
		}
		AnimEdit.PriorityPlan fp = plan;
		List<Animation> changed = new ArrayList<>();
		if (existing != null)changed.add(existing);
		if (plan != null)changed.addAll(plan.renumber.keySet());
		int prio = plan != null ? plan.value : existing.getPriority();
		String label = t("cpmplus.w3animtools.physics.undo");
		Animation[] before = changed.toArray(new Animation[0]);
		Animation[] created = new Animation[1];
		PlusUndo.run(label, UndoData.make(before), () -> {
			List<Animation> after = new ArrayList<>(changed);
			if (created[0] != null)after.add(created[0]);
			return UndoData.make(after.toArray(new Animation[0]));
		}, () -> {
			if (fp != null)fp.applyRenumber();
			if (existing != null) {
				setupLayer(existing, pr, prio);
				writeKeys(existing, b, true);
			} else {
				Animation a = new Animation();
				a.extend(copyData(src.anim, layerName(src.anim.name)));
				setupLayer(a, pr, prio);
				// keys first, then into the project (once listed, every added key costs more)
				writeKeys(a, b, false);
				created[0] = a.add(false);
			}
		});
		Animation layer = existing != null ? existing : created[0];
		r.changed = true;
		r.put("name", layer.name).put("uuid", layer.uuid).put("created", existing == null).put("priority", (double) layer.getPriority())
		.put("renumbered", (double) (plan != null ? plan.renumber.size() : 0));
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** stats of a bake (dialog summary, hook) */
	static void stats(Prepared pr, Baked b, Result r) {
		r.put("links", (double) pr.links.groups.size()).put("moving", (double) b.keys.size()).put("frames", (double) pr.frames)
		.put("period", pr.src.period).put("loop", pr.src.loop).put("cycles", (double) b.out.cycles).put("converged", b.out.converged)
		.put("maxDeg", Math.round(b.maxDeg * 100) / 100.0).put("lastDiff", Math.round(b.out.lastDiff * 1000) / 1000.0)
		.put("samples", (double) pr.sampleCount).put("sampleMs", Math.round(pr.sampleMs * 10) / 10.0).put("layerName", layerName(pr.src.anim.name));
		if (pr.links.roots > 0)r.warn("roots", t("cpmplus.w3animtools.physics.warn.roots", pr.links.roots));
		if (pr.links.empty > 0)r.warn("empty", t("cpmplus.w3animtools.physics.warn.empty", pr.links.empty));
		if (pr.src.loop && !b.out.converged)r.warn("notSettled", t("cpmplus.w3animtools.physics.warn.notSettled", PoseColumn.fmt(b.out.lastDiff)));
	}

	private static Object copyData(Animation src, String name) {
		return LayerSupport.copyData(src, name);
	}

	// ====================================================================== live preview (a temporary animation)

	static final class Preview {
		Animation temp;
		Animation hidden;
		Object hiddenPlaying;
		boolean startedTimeline;

		void show(Prepared pr, Baked b, boolean on) {
			if (temp == null) {
				Animation existing = layerOf(pr.src.anim);
				if (existing != null) {
					hidden = existing;
					hiddenPlaying = Js.asPropertyMap(existing).get("playing");
					Js.asPropertyMap(existing).set("playing", false);
				}
				Animation a = new Animation();
				a.extend(copyData(pr.src.anim, layerName(pr.src.anim.name) + " " + t("cpmplus.w3animtools.physics.previewSuffix")));
				setupLayer(a, pr, pr.src.anim.getPriority() + 1);
				temp = a.add(false);
			}
			writeKeys(temp, b, true);
			Js.asPropertyMap(temp).set("playing", on);
			AnimToolSupport.refreshPreview();
		}

		void play(boolean on) {
			if (on && !timelinePlaying()) {
				timelinePlay(true);
				startedTimeline = true;
			} else if (!on && startedTimeline) {
				timelinePlay(false);
				startedTimeline = false;
			}
		}

		void dispose() {
			if (startedTimeline)timelinePlay(false);
			startedTimeline = false;
			if (temp != null) {
				try {
					temp.remove(false, false);
				} catch (Throwable e) {
					DomGlobal.console.warn("CPM+: could not remove the physics preview", e);
				}
				temp = null;
			}
			if (hidden != null) {
				Js.asPropertyMap(hidden).set("playing", hiddenPlaying);
				hidden = null;
			}
			AnimToolSupport.refreshPreview();
		}
	}

	private static native boolean timelinePlaying() /*-{
		return !!($wnd.Timeline && $wnd.Timeline.playing);
	}-*/;

	private static native void timelinePlay(boolean on) /*-{
		var T = $wnd.Timeline;
		if (!T) return;
		if (on) { if (!T.playing && typeof T.start === 'function') T.start(); }
		else if (T.playing && typeof T.pause === 'function') T.pause();
	}-*/;

	// ====================================================================== dialog

	private static final String SUMMARY_ID = "cpmplus_w3animtools_physics_summary";

	static Settings fromForm(FormResult fr, Settings base) {
		Settings s = new Settings();
		s.preset = fr.getString("preset") != null ? fr.getString("preset") : base.preset;
		s.floppy = fr.getDouble("floppy", base.floppy);
		s.bounce = fr.getDouble("bounce", base.bounce);
		s.weight = fr.getDouble("weight", base.weight);
		s.maxAngle = fr.getDouble("maxAngle", base.maxAngle);
		s.tipLoose = fr.getDouble("tipLoose", base.tipLoose);
		s.mix = fr.getDouble("mix", base.mix);
		s.gameForces = fr.raw("gameForces") != null ? fr.getBool("gameForces") : base.gameForces;
		s.children = fr.raw("children") != null ? fr.getBool("children") : base.children;
		return s;
	}

	static Settings savedSettings() {
		Settings s = new Settings();
		s.preset(PlusPrefs.get(PREF + "preset", "tail"));
		s.floppy = PlusPrefs.getNum(PREF + "floppy", s.floppy);
		s.bounce = PlusPrefs.getNum(PREF + "bounce", s.bounce);
		s.weight = PlusPrefs.getNum(PREF + "weight", s.weight);
		s.maxAngle = PlusPrefs.getNum(PREF + "maxAngle", s.maxAngle);
		s.tipLoose = PlusPrefs.getNum(PREF + "tipLoose", s.tipLoose);
		s.mix = PlusPrefs.getNum(PREF + "mix", s.mix);
		s.gameForces = PlusPrefs.getBool(PREF + "gameForces", true);
		s.children = PlusPrefs.getBool(PREF + "children", true);
		return s;
	}

	static void saveSettings(Settings s) {
		PlusPrefs.set(PREF + "preset", s.preset);
		PlusPrefs.set(PREF + "floppy", s.floppy);
		PlusPrefs.set(PREF + "bounce", s.bounce);
		PlusPrefs.set(PREF + "weight", s.weight);
		PlusPrefs.set(PREF + "maxAngle", s.maxAngle);
		PlusPrefs.set(PREF + "tipLoose", s.tipLoose);
		PlusPrefs.set(PREF + "mix", s.mix);
		PlusPrefs.set(PREF + "gameForces", s.gameForces);
		PlusPrefs.set(PREF + "children", s.children);
	}

	/** the parts of an existing layer (re-bake without a selection) */
	static List<Group> layerParts(Animation layer) {
		List<Group> l = new ArrayList<>();
		if (layer == null)return l;
		for (BoneAnimator ba : AnimEdit.boneAnimators(layer)) {
			if (!AnimxUtil.hasKeys(ba, KeyMath.ROT))continue;
			Group g = AnimEdit.groupOf(ba);
			if (g != null)l.add(g);
		}
		return l;
	}

	/** CPM+ &gt; Animação &gt; 'Física: seguir o movimento...' (Ctrl+Alt+U) */
	public static void openDialog() {
		Animation sel = Animation.selected;
		Animation anim = originalOf(sel);
		Source src = source(anim);
		if (src.errorKey != null) {
			PlusDialogs.block(t(src.errorKey, anim != null ? anim.name : ""));
			return;
		}
		Animation existing = layerOf(anim);
		List<Group> picked = AnimToolSupport.selectedGroups();
		boolean fromLayer = false;
		if (picked.isEmpty() && existing != null) {
			picked = layerParts(existing);
			fromLayer = true;
		}
		if (picked.isEmpty()) {
			PlusDialogs.block(t("cpmplus.w3animtools.physics.err.select"));
			return;
		}
		if (anim != sel)anim.select();
		Settings saved = savedSettings();
		List<Group> fpicked = picked;
		Links lk = links(picked, saved.children);
		if (lk.groups.isEmpty()) {
			PlusDialogs.block(t("cpmplus.w3animtools.physics.err.onlyRoots"));
			return;
		}
		Prepared[] prep = {prepare(src, lk)};
		Preview pv = new Preview();
		String layerName = existing != null ? existing.name : layerName(anim.name);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.physics.intro", layerName, anim.name)));
		rows.add(FormRow.info("who", null, (existing != null ? t("cpmplus.w3animtools.physics.update") + " " : "")
				+ t(fromLayer ? "cpmplus.w3animtools.physics.partsFromLayer" : "cpmplus.w3animtools.physics.parts", names(picked))));
		Map<String, String> presets = new LinkedHashMap<>();
		for (String p : PRESETS)presets.put(p, "cpmplus.w3animtools.physics.preset." + p);
		rows.add(FormRow.select("preset", "cpmplus.w3animtools.physics.preset", presets, saved.preset));
		rows.add(FormRow.range("floppy", "cpmplus.w3animtools.physics.floppy", saved.floppy, 0, 100, 1).description("cpmplus.w3animtools.physics.floppy.desc"));
		rows.add(FormRow.range("bounce", "cpmplus.w3animtools.physics.bounce", saved.bounce, 0, 100, 1).description("cpmplus.w3animtools.physics.bounce.desc"));
		rows.add(FormRow.range("weight", "cpmplus.w3animtools.physics.weight", saved.weight, 0, 100, 1).description("cpmplus.w3animtools.physics.weight.desc"));
		rows.add(FormRow.range("maxAngle", "cpmplus.w3animtools.physics.maxAngle", saved.maxAngle, 5, 90, 1).description("cpmplus.w3animtools.physics.maxAngle.desc"));
		rows.add(FormRow.range("tipLoose", "cpmplus.w3animtools.physics.tipLoose", saved.tipLoose, 0, 100, 1).description("cpmplus.w3animtools.physics.tipLoose.desc"));
		rows.add(FormRow.range("mix", "cpmplus.w3animtools.physics.mix", saved.mix, 0, 150, 5).description("cpmplus.w3animtools.physics.mix.desc"));
		if (src.force != null)rows.add(FormRow.checkbox("gameForces", "cpmplus.w3animtools.physics.gameForces", saved.gameForces)
				.description(t("cpmplus.w3animtools.physics.force." + src.force.key)));
		rows.add(FormRow.checkbox("children", "cpmplus.w3animtools.physics.children", saved.children).description("cpmplus.w3animtools.physics.children.desc"));
		rows.add(FormRow.checkbox("preview", "cpmplus.w3animtools.physics.preview", true).description("cpmplus.w3animtools.physics.preview.desc"));
		rows.add(FormRow.checkbox("play", "cpmplus.w3animtools.physics.play", true));
		rows.add(FormRow.info("vanilla", null, t("cpmplus.w3animtools.physics.vanillaNote")));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		FormOpts fo = new FormOpts();
		fo.width = 640;
		fo.labelWidth = 230;
		fo.confirmKey = existing != null ? "cpmplus.w3animtools.physics.okUpdate" : "cpmplus.w3animtools.physics.ok";
		DialogState ds = new DialogState();
		ds.lastPreset = saved.preset;
		ds.lastChildren = saved.children;
		fo.onCancel = () -> {
			ds.cancelTimer();
			pv.dispose();
		};
		fo.onChange = fr -> {
			String preset = fr.getString("preset");
			if (preset != null && !preset.equals(ds.lastPreset)) {
				ds.lastPreset = preset;
				if (!"custom".equals(preset) && ds.dialog != null) {
					Settings ps = new Settings();
					ps.preset(preset);
					JsPropertyMap<Object> v = JsPropertyMap.of();
					v.set("floppy", ps.floppy);
					v.set("bounce", ps.bounce);
					v.set("weight", ps.weight);
					v.set("maxAngle", ps.maxAngle);
					v.set("tipLoose", ps.tipLoose);
					setValues(ds.dialog, v);
					return;
				}
			}
			ds.schedule(() -> {
				Settings s = fromForm(fr, saved);
				if (s.children != ds.lastChildren) {
					ds.lastChildren = s.children;
					Links nl = links(fpicked, s.children);
					if (!nl.groups.isEmpty())prep[0] = prepare(src, nl);
				}
				Baked b = bake(prep[0], s);
				if (fr.getBool("preview"))pv.show(prep[0], b, true);
				else if (pv.temp != null)pv.show(prep[0], b, false);
				pv.play(fr.getBool("play"));
				Result r = new Result();
				stats(prep[0], b, r);
				setSummary(r, b);
			});
		};
		ds.dialog = PlusDialogs.form("cpmplus_w3animtools_physics", "cpmplus.w3animtools.physics.title", rows, fr -> {
			ds.cancelTimer();
			pv.dispose();
			Settings s = fromForm(fr, saved);
			saveSettings(s);
			if (s.children != ds.lastChildren)prep[0] = prepare(src, links(fpicked, s.children));
			Baked b = bake(prep[0], s);
			Result res = apply(prep[0], b);
			if (!res.ok) {
				PlusDialogs.block(res.errorText());
				return;
			}
			Result st = new Result();
			stats(prep[0], b, st);
			PlusDialogs.done(t("cpmplus.w3animtools.physics.done", String.valueOf(res.extra.get("name")), b.keys.size(), prep[0].frames));
			if (!st.warnings.isEmpty())PlusDialogs.toast(st.warningText(), "warning", 8000, null);
		}, fo);
		// first preview right away
		ds.schedule(() -> {
			Baked b = bake(prep[0], saved);
			pv.show(prep[0], b, true);
			pv.play(true);
			Result r = new Result();
			stats(prep[0], b, r);
			setSummary(r, b);
		});
	}

	static final class DialogState {
		Dialog dialog;
		String lastPreset;
		boolean lastChildren;
		double timer = -1;

		void schedule(Runnable r) {
			cancelTimer();
			timer = DomGlobal.setTimeout(a -> {
				timer = -1;
				try {
					r.run();
				} catch (Throwable e) {
					DomGlobal.console.error("CPM+: physics preview failed", e);
				}
			}, 120);
		}

		void cancelTimer() {
			if (timer >= 0)DomGlobal.clearTimeout(timer);
			timer = -1;
		}
	}

	private static native void setValues(Object dialog, Object values) /*-{
		try { dialog.setFormValues(values, true); } catch (e) { console.warn('CPM+: could not set the physics preset', e); }
	}-*/;

	private static String names(List<Group> l) {
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (Group g : l) {
			if (i == 4) {
				sb.append(", +").append(l.size() - 4);
				break;
			}
			if (i > 0)sb.append(", ");
			sb.append(g.name);
			i++;
		}
		return sb.toString();
	}

	private static void setSummary(Result r, Baked b) {
		Element el = DomGlobal.document.getElementById(SUMMARY_ID);
		if (el == null)return;
		StringBuilder sb = new StringBuilder();
		if (b.keys.isEmpty())sb.append(t("cpmplus.w3animtools.physics.err.nothing"));
		else {
			Object cyc = r.extra.get("cycles");
			sb.append(t("cpmplus.w3animtools.physics.summary", ((Double) r.extra.get("moving")).intValue(), ((Double) r.extra.get("frames")).intValue(),
					PoseColumn.fmt(b.maxDeg), cyc != null ? ((Double) cyc).intValue() : 1));
		}
		for (String w : r.warnings)sb.append(" ").append(w);
		el.textContent = sb.toString();
	}

	static double clamp(double v, double lo, double hi) {
		return Double.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
	}

	// ====================================================================== QA hook

	/**
	 * physics({anim, parts: [paths] | 'selected' | 'layer', children, preset, floppy, bounce, weight, maxAngle, tipLoose,
	 * mix, gameForces, apply, verify, previewTest})
	 */
	public static Object hook(JsPropertyMap<Object> opts) {
		Animation anim = originalOf(AnimToolSupport.anim(opts));
		Source src = source(anim);
		Result r = new Result();
		if (src.errorKey != null) {
			r.error("source", t(src.errorKey, anim != null ? anim.name : ""));
			r.put("errorKey", src.errorKey);
			return r.toJs();
		}
		Settings s = new Settings();
		s.preset(AnimToolSupport.str(opts, "preset", "tail"));
		s.floppy = AnimToolSupport.num(opts, "floppy", s.floppy);
		s.bounce = AnimToolSupport.num(opts, "bounce", s.bounce);
		s.weight = AnimToolSupport.num(opts, "weight", s.weight);
		s.maxAngle = AnimToolSupport.num(opts, "maxAngle", s.maxAngle);
		s.tipLoose = AnimToolSupport.num(opts, "tipLoose", s.tipLoose);
		s.mix = AnimToolSupport.num(opts, "mix", s.mix);
		s.gameForces = AnimToolSupport.bool(opts, "gameForces", true);
		s.children = AnimToolSupport.bool(opts, "children", true);
		List<Group> picked = new ArrayList<>();
		Object p = opts.get("parts");
		if ("selected".equals(p))picked = AnimToolSupport.selectedGroups();
		else if ("layer".equals(p) || p == null)picked = layerParts(layerOf(anim));
		else for (String n : AnimToolSupport.list(opts, "parts")) {
			Group g = AnimToolSupport.group(n);
			if (g != null)picked.add(g);
		}
		Links lk = links(picked, s.children);
		if (lk.groups.isEmpty()) {
			r.error("noParts", t(lk.roots > 0 ? "cpmplus.w3animtools.physics.err.onlyRoots" : "cpmplus.w3animtools.physics.err.select"));
			return r.toJs();
		}
		Prepared pr = prepare(src, lk);
		Baked b = bake(pr, s);
		stats(pr, b, r);
		if (AnimToolSupport.bool(opts, "previewTest", false)) {
			Preview pv = new Preview();
			pv.show(pr, b, true);
			r.put("previewName", pv.temp != null ? pv.temp.name : "");
			r.put("previewInList", pv.temp != null && AnimEdit.all().contains(pv.temp));
			r.put("previewPlaying", pv.temp != null && Js.isTruthy(Js.asPropertyMap(pv.temp).get("playing")));
			Animation tmp = pv.temp;
			pv.dispose();
			r.put("previewRemoved", tmp != null && !AnimEdit.all().contains(tmp));
			return r.toJs();
		}
		if (AnimToolSupport.bool(opts, "apply", true)) {
			Result a = apply(pr, b);
			if (!a.ok) {
				for (int i = 0; i < a.errors.size(); i++)r.error(a.errorCodes.get(i), a.errors.get(i));
				return r.toJs();
			}
			r.changed = true;
			r.extra.putAll(a.extra);
			if (AnimToolSupport.bool(opts, "verify", false)) {
				Animation layer = layerOf(anim);
				r.put("verifyErr", verify(pr, b, layer));
			}
		}
		return r.toJs();
	}

	/**
	 * Self-check: the original + the written layer shown together (Blockbench's pipeline) at every key time must put
	 * each part's tip where the simulation had it (mix 100 %, outside the one-shot fade). Largest distance in pixels.
	 */
	static double verify(Prepared pr, Baked b, Animation layer) {
		if (layer == null)return -1;
		int F = pr.frames, m = pr.perKey, S = pr.samples.length;
		double tail = Math.min(0.2, 0.3 * pr.src.period);
		List<Double> times = new ArrayList<>();
		List<Integer> idx = new ArrayList<>();
		for (int k = 0; k < F; k++) {
			double time = k * pr.src.period / F;
			if (!pr.src.loop && time > pr.src.period - tail - 1e-9)break;
			if (time > pr.src.len + 1e-6)break;
			times.add(time);
			idx.add(pr.src.loop ? (k * m) % S : k * m);
		}
		double[] t = new double[times.size()];
		for (int i = 0; i < t.length; i++)t[i] = times.get(i);
		Object[] groups = pr.links.groups.toArray();
		double[] raw = sampleBoth(pr.src.anim, layer, groups, pr.links.tipLocal, t);
		AnimToolSupport.refreshPreview();
		double err = 0;
		int n = groups.length;
		for (int i = 0; i < t.length; i++) {
			double[] sim = b.out.tips[idx.get(i)];
			for (int l = 0; l < n; l++) {
				if (PhysicsSim.len(PhysicsSim.vec(pr.links.tipLocal, l * 3)) < 0.05)continue;
				double dx = raw[(i * n + l) * 3] - sim[l * 3], dy = raw[(i * n + l) * 3 + 1] - sim[l * 3 + 1], dz = raw[(i * n + l) * 3 + 2] - sim[l * 3 + 2];
				err = Math.max(err, Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
		}
		return Math.round(err * 1e5) / 1e5;
	}

	private static native double[] sampleBoth(Animation a, Animation b, Object[] groups, double[] tipLocal, double[] times) /*-{
		var A = $wnd.Animator, TL = $wnd.Timeline, T = $wnd.THREE;
		var out = [], save = TL.time, v = new T.Vector3();
		try {
			$wnd.__cpmplusNoStepped = true;// the 'stepped preview' must not change what is sampled
			for (var k = 0; k < times.length; k++) {
				TL.time = times[k];
				A.showDefaultPose(true);
				A.stackAnimations([a, b], false);
				for (var i = 0; i < groups.length; i++) {
					var m = groups[i].mesh;
					m.updateWorldMatrix(true, false);
					v.set(tipLocal[3 * i], tipLocal[3 * i + 1], tipLocal[3 * i + 2]).applyMatrix4(m.matrixWorld);
					out.push(v.x, v.y, v.z);
				}
			}
		} finally {
			$wnd.__cpmplusNoStepped = false;
			TL.time = save;
			if (A.resetLastValues) A.resetLastValues();
		}
		return out;
	}-*/;
}
