package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W6-ANIMX, G39): movement that stops when the situation changes (jump, fall, sneak...).
 * <p>
 * The game plays ONE main situation at a time ({@code AnimationState.getMainPose}) plus GLOBAL; a part the active
 * situation does not animate goes back to its rest position (resetAnimationPos every frame) - it does not freeze.
 * {@link #mainPose} is a line-by-line copy of getMainPose, including the existence rule: JUMPING, CRAWLING,
 * RETRO_SWIMMING, CLIMBING_ON_LADDER, ON_LADDER and SNEAK_WALK only happen when the model has an animation for them
 * (registry.hasPoseAnimations), otherwise the game falls through to the next situation and there is no gap.
 * <p>
 * Read-only analysis; only the action buttons edit (each one undo entry). Parts marked 'É de propósito' are kept in
 * the project data (extra 'sitIgnore', G16) and never reported.
 */
public final class SituationCheck {
	public static final String IGNORE_KEY = "sitIgnore";

	/** getMainPose order */
	public static final List<VanillaPose> CHAIN = Collections.unmodifiableList(Arrays.asList(VanillaPose.SLEEPING, VanillaPose.DYING,
			VanillaPose.FLYING, VanillaPose.TRIDENT_SPIN, VanillaPose.FALLING, VanillaPose.RIDING, VanillaPose.CREATIVE_FLYING,
			VanillaPose.CRAWLING, VanillaPose.SWIMMING, VanillaPose.RETRO_SWIMMING, VanillaPose.CLIMBING_ON_LADDER, VanillaPose.ON_LADDER,
			VanillaPose.JUMPING, VanillaPose.SNEAK_WALK, VanillaPose.SNEAKING, VanillaPose.RUNNING, VanillaPose.WALKING, VanillaPose.STANDING));
	/** only when the model has an animation of that pose */
	public static final Set<VanillaPose> CONDITIONAL = Collections.unmodifiableSet(EnumSet.of(VanillaPose.CRAWLING, VanillaPose.RETRO_SWIMMING,
			VanillaPose.CLIMBING_ON_LADDER, VanillaPose.ON_LADDER, VanillaPose.JUMPING, VanillaPose.SNEAK_WALK));
	public static final List<VanillaPose> LOCOMOTION = Collections.unmodifiableList(Arrays.asList(VanillaPose.STANDING, VanillaPose.WALKING,
			VanillaPose.RUNNING, VanillaPose.SNEAKING, VanillaPose.SNEAK_WALK));
	public static final List<VanillaPose> COMMON = Collections.unmodifiableList(Arrays.asList(VanillaPose.JUMPING, VanillaPose.FALLING,
			VanillaPose.SNEAKING, VanillaPose.SNEAK_WALK));

	private SituationCheck() {
	}

	// ------------------------------------------------------------------ pure truth table

	/** the inputs of AnimationState.getMainPose */
	public static final class State {
		public boolean sleeping, dying, elytraFlying, tridentSpin, riding, creativeFlying, crawling, swimming, retroSwimming, onLadder, jumping, sneaking, sprinting;
		public int falling;
		public float moveX, moveY, moveZ;
	}

	/** copy of AnimationState.getMainPose (jumping = 'jumped less than 500 ms ago') */
	public static VanillaPose mainPose(State s, Set<VanillaPose> withAnims) {
		if (s.sleeping)return VanillaPose.SLEEPING;
		else if (s.dying)return VanillaPose.DYING;
		else if (s.elytraFlying)return VanillaPose.FLYING;
		else if (s.tridentSpin)return VanillaPose.TRIDENT_SPIN;
		else if (s.falling > 4)return VanillaPose.FALLING;
		else if (s.riding)return VanillaPose.RIDING;
		else if (s.creativeFlying)return VanillaPose.CREATIVE_FLYING;
		else if (s.crawling && withAnims.contains(VanillaPose.CRAWLING))return VanillaPose.CRAWLING;
		else if (s.swimming)return VanillaPose.SWIMMING;
		else if (s.retroSwimming && withAnims.contains(VanillaPose.RETRO_SWIMMING))return VanillaPose.RETRO_SWIMMING;
		else if (s.onLadder && Math.abs(s.moveY) > 0.05F && withAnims.contains(VanillaPose.CLIMBING_ON_LADDER))return VanillaPose.CLIMBING_ON_LADDER;
		else if (s.onLadder && withAnims.contains(VanillaPose.ON_LADDER))return VanillaPose.ON_LADDER;
		else if (s.jumping && withAnims.contains(VanillaPose.JUMPING))return VanillaPose.JUMPING;
		else if (s.sneaking)return (Math.abs(s.moveX) > 0 || Math.abs(s.moveZ) > 0) && withAnims.contains(VanillaPose.SNEAK_WALK) ? VanillaPose.SNEAK_WALK : VanillaPose.SNEAKING;
		else if (s.sprinting)return VanillaPose.RUNNING;
		else if (Math.abs(s.moveX) > 0 || Math.abs(s.moveZ) > 0)return VanillaPose.WALKING;
		else return VanillaPose.STANDING;
	}

	/** the situations that can happen with this model, in getMainPose order */
	public static List<VanillaPose> situationTable(Set<VanillaPose> withAnims) {
		List<VanillaPose> l = new ArrayList<>();
		for (VanillaPose p : CHAIN)if (exists(p, withAnims))l.add(p);
		return l;
	}

	public static boolean exists(VanillaPose p, Set<VanillaPose> withAnims) {
		return !CONDITIONAL.contains(p) || withAnims.contains(p);
	}

	public static boolean isRare(VanillaPose p) {
		return !LOCOMOTION.contains(p) && !COMMON.contains(p);
	}

	public static String label(VanillaPose p) {
		return PlusDialogs.tr("cpmplus.w6animx.sit." + p.name().toLowerCase(Locale.ROOT));
	}

	public static String when(VanillaPose p) {
		return PlusDialogs.tr("cpmplus.w6animx.sit.when." + p.name().toLowerCase(Locale.ROOT));
	}

	// ------------------------------------------------------------------ project analysis

	/** non-staged animations per situation pose */
	public static Map<VanillaPose, List<Animation>> situationAnims() {
		Map<VanillaPose, List<Animation>> m = new LinkedHashMap<>();
		for (Animation a : AnimxUtil.animations()) {
			Pair<AnimationType, VanillaPose> t = AnimxUtil.typeOf(a);
			if (t == null || t.getKey() != AnimationType.POSE || !CHAIN.contains(t.getValue()))continue;
			m.computeIfAbsent(t.getValue(), k -> new ArrayList<>()).add(a);
		}
		return m;
	}

	private static double eps(String ch) {
		if (AnimxUtil.ROT.equals(ch))return 0.01;
		if (AnimxUtil.COL.equals(ch))return 0.5;
		if (AnimxUtil.SCL.equals(ch))return 1e-4;
		return 1e-3;
	}

	/**
	 * true when the channel really moves in this animation: the amplitude of the curve is above epsilon. The curve
	 * passes through every key value, so different key values already prove it; equal key values only move with
	 * bezier handles or formulas, which are sampled (the export's own interpolation) on the animation's grid.
	 */
	public static boolean moves(Animation a, BoneAnimator ba, String ch) {
		if (!AnimxUtil.hasKeys(ba, ch))return false;
		JsArrayE<Keyframe> keys = AnimxUtil.keys(ba, ch);
		if (AnimxUtil.VIS.equals(ch)) {
			Boolean first = null;
			for (int i = 0; i < keys.length; i++) {
				boolean v = AnimxUtil.visibleValue(keys.getAt(i));
				if (first == null)first = v;
				else if (first != v)return true;
			}
			return false;
		}
		double e = eps(ch);
		double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		boolean sample = false;
		for (int i = 0; i < keys.length; i++) {
			Keyframe kf = keys.getAt(i);
			if (!AnimxUtil.isNumeric(kf)) {
				sample = true;
				continue;
			}
			if ("bezier".equals(kf.interpolation))sample = true;
			int dps = AnimxUtil.dataPoints(kf);
			for (int dp = 0; dp < dps; dp++) {
				for (int ax = 0; ax < 3; ax++) {
					double v = AnimxUtil.value(kf, dp, ax);
					if (Double.isNaN(v))continue;
					min[ax] = Math.min(min[ax], v);
					max[ax] = Math.max(max[ax], v);
				}
			}
		}
		for (int ax = 0; ax < 3; ax++)if (max[ax] - min[ax] > e)return true;
		if (!sample || keys.length < 1)return false;
		double len = a.length > 0 ? a.length : 1;
		int n = Math.max(8, Math.min(240, (int) Math.ceil(len * AnimxUtil.snapping(a))));
		double[] lo = null, hi = null;
		for (int k = 0; k <= n; k++) {
			double[] v = AnimxUtil.sample(keys, len * k / n);
			if (v == null)continue;
			if (lo == null) {
				lo = AnimxUtil.cp(v);
				hi = AnimxUtil.cp(v);
			}
			for (int ax = 0; ax < 3; ax++) {
				lo[ax] = Math.min(lo[ax], v[ax]);
				hi[ax] = Math.max(hi[ax], v[ax]);
			}
		}
		if (lo == null)return false;
		for (int ax = 0; ax < 3; ax++)if (hi[ax] - lo[ax] > e)return true;
		return false;
	}

	public static final class PartReport {
		public Group group;
		public final Set<String> channels = new LinkedHashSet<>();
		public final Set<VanillaPose> moving = new LinkedHashSet<>();
		public final List<VanillaPose> missing = new ArrayList<>();
		public final List<VanillaPose> missingRare = new ArrayList<>();
		/** situation animations with keys of this part (moving or not) */
		public final List<Animation> keyed = new ArrayList<>();
		public boolean canMoveToGlobal;
	}

	public static final class Report {
		public final List<PartReport> parts = new ArrayList<>();
		public final List<VanillaPose> table = new ArrayList<>();
		public final Set<VanillaPose> withAnims = EnumSet.noneOf(VanillaPose.class);
		public boolean customPoses;
		public int ignored;
		public double ms;
	}

	public static Report check() {
		double t0 = now();
		Report r = new Report();
		Map<VanillaPose, List<Animation>> sit = situationAnims();
		r.withAnims.addAll(sit.keySet());
		r.table.addAll(situationTable(r.withAnims));
		for (Animation a : AnimxUtil.animations()) {
			Pair<AnimationType, VanillaPose> t = AnimxUtil.typeOf(a);
			if (t != null && (t.getKey() == AnimationType.CUSTOM_POSE || t.getKey() == AnimationType.GESTURE))r.customPoses = true;
		}
		Set<String> ignore = ignored();
		// group uuid -> report
		Map<String, PartReport> parts = new LinkedHashMap<>();
		for (Map.Entry<VanillaPose, List<Animation>> e : sit.entrySet()) {
			VanillaPose p = e.getKey();
			for (Animation a : e.getValue()) {
				boolean loop = "loop".equals(a.loop);
				for (BoneAnimator ba : AnimxUtil.boneAnimators(a)) {
					Group g = ba.getGroup();
					if (g == null || !Js.isTruthy(g))continue;
					PartReport pr = null;
					for (String ch : AnimxUtil.ALL) {
						if (!AnimxUtil.hasKeys(ba, ch))continue;
						if (pr == null)pr = parts.computeIfAbsent(g.uuid, k -> {
							PartReport x = new PartReport();
							x.group = g;
							return x;
						});
						if (!pr.keyed.contains(a))pr.keyed.add(a);
						if (loop && moves(a, ba, ch)) {
							pr.moving.add(p);
							pr.channels.add(ch);
						}
					}
				}
			}
		}
		for (PartReport pr : parts.values()) {
			if (ignore.contains(pr.group.uuid)) {
				if (!pr.moving.isEmpty())r.ignored++;
				continue;
			}
			int loco = 0;
			for (VanillaPose p : LOCOMOTION)if (exists(p, r.withAnims) && pr.moving.contains(p))loco++;
			if (loco < 2)continue;
			for (VanillaPose p : COMMON)if (exists(p, r.withAnims) && !pr.moving.contains(p))pr.missing.add(p);
			if (pr.missing.isEmpty())continue;
			for (VanillaPose p : r.table)if (!COMMON.contains(p) && !pr.moving.contains(p))pr.missingRare.add(p);
			pr.canMoveToGlobal = tracksEqual(pr.group, pr.keyed);
			r.parts.add(pr);
		}
		r.ms = now() - t0;
		return r;
	}

	private static native double now() /*-{
		return $wnd.performance && $wnd.performance.now ? $wnd.performance.now() : Date.now();
	}-*/;

	/**
	 * 'Mover pra Global' is only offered when the part's track is the same in every situation animation that keys it:
	 * same length, all looping, same additive flag, same key count / times / interpolation per channel and the same
	 * sampled values (1e-3).
	 */
	public static boolean tracksEqual(Group g, List<Animation> keyed) {
		if (keyed.isEmpty())return false;
		Animation r = keyed.get(0);
		BoneAnimator rb = AnimxUtil.animatorOf(r, g);
		for (Animation a : keyed) {
			if (!"loop".equals(a.loop) || a.additive != r.additive || Math.abs(a.length - r.length) > 1e-4)return false;
			if (a == r)continue;
			BoneAnimator b = AnimxUtil.animatorOf(a, g);
			for (String ch : AnimxUtil.ALL) {
				List<Keyframe> k1 = AnimxUtil.sorted(AnimxUtil.keys(rb, ch)), k2 = AnimxUtil.sorted(AnimxUtil.keys(b, ch));
				if (k1.size() != k2.size())return false;
				for (int i = 0; i < k1.size(); i++) {
					if (Math.abs(k1.get(i).time - k2.get(i).time) > 1e-4)return false;
					if (!String.valueOf(k1.get(i).interpolation).equals(String.valueOf(k2.get(i).interpolation)))return false;
				}
				if (k1.isEmpty())continue;
				if (AnimxUtil.VIS.equals(ch)) {
					for (int i = 0; i < k1.size(); i++)if (AnimxUtil.visibleValue(k1.get(i)) != AnimxUtil.visibleValue(k2.get(i)))return false;
					continue;
				}
				double len = r.length > 0 ? r.length : 1;
				for (int s = 0; s <= 60; s++) {
					double t = len * s / 60;
					double[] v1 = AnimxUtil.sample(AnimxUtil.keys(rb, ch), t), v2 = AnimxUtil.sample(AnimxUtil.keys(b, ch), t);
					if (v1 == null || v2 == null)return false;
					for (int ax = 0; ax < 3; ax++)if (Math.abs(v1[ax] - v2[ax]) > 1e-3)return false;
				}
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ ignore list (project data, G16)

	public static Set<String> ignored() {
		Set<String> s = new LinkedHashSet<>();
		try {
			Object v = Project.getData().getExtra(IGNORE_KEY);
			if (v instanceof List) {
				for (Object o : (List<?>) v)if (o != null)s.add(String.valueOf(o));
			} else if (v != null && JsArray.isArray(v)) {
				JsArray<Object> a = Js.uncheckedCast(v);
				for (int i = 0; i < a.length; i++)s.add(String.valueOf(a.getAt(i)));
			}
		} catch (Throwable e) {
		}
		return s;
	}

	/** marks / unmarks a part as 'É de propósito' (project data, undo through the 'Desfazer' toast) */
	public static void setIgnored(Group g, boolean ignore, boolean toast) {
		Set<String> s = ignored();
		if (ignore ? !s.add(g.uuid) : !s.remove(g.uuid))return;
		ProjectDataUndo.Snapshot snap = ProjectDataUndo.snapshot();
		ProjectData pd = Project.getData();
		if (s.isEmpty())pd.removeExtra(IGNORE_KEY);
		else pd.putExtra(IGNORE_KEY, new ArrayList<>(s));
		pd.flush();
		Project.saved = false;
		if (toast)ProjectDataUndo.offerUndo(snap, PlusDialogs.trf(ignore ? "cpmplus.w6animx.sit.ignore.done" : "cpmplus.w6animx.sit.unignore.done", g.name));
	}

	// ------------------------------------------------------------------ Mover pra Global

	public static final class MoveResult {
		public boolean ok;
		public String error;
		public Animation global;
		public boolean created;
		public int movedKeys;
	}

	/** the situations where the part starts moving after 'Mover pra Global' (+ gestures) */
	public static List<VanillaPose> newlyMoving(PartReport pr, Report r) {
		List<VanillaPose> l = new ArrayList<>();
		for (VanillaPose p : r.table)if (!pr.moving.contains(p))l.add(p);
		return l;
	}

	/** animations that are not situation animations of this part and still key it (priority / additive shown) */
	public static List<Animation> conflicts(PartReport pr) {
		List<Animation> l = new ArrayList<>();
		for (Animation a : AnimxUtil.animations()) {
			if (pr.keyed.contains(a) || AnimxUtil.isStaged(a))continue;
			BoneAnimator ba = AnimxUtil.animatorOf(a, pr.group);
			for (String ch : AnimxUtil.ALL) {
				if (AnimxUtil.hasKeys(ba, ch)) {
					l.add(a);
					break;
				}
			}
		}
		return l;
	}

	private static boolean isGlobal(Animation a) {
		Pair<AnimationType, VanillaPose> t = AnimxUtil.typeOf(a);
		return t != null && t.getKey() == AnimationType.POSE && t.getValue() == VanillaPose.GLOBAL;
	}

	public static MoveResult moveToGlobal(PartReport pr) {
		MoveResult res = new MoveResult();
		if (pr.keyed.isEmpty() || !tracksEqual(pr.group, pr.keyed)) {
			res.error = "cpmplus.w6animx.sit.move.notEqual";
			return res;
		}
		Animation src = pr.keyed.get(0);
		Group g = pr.group;
		Animation target = null;
		for (Animation a : AnimxUtil.animations()) {
			if (!isGlobal(a) || !"loop".equals(a.loop) || a.additive != src.additive || Math.abs(a.length - src.length) > 1e-4)continue;
			BoneAnimator ba = AnimxUtil.animatorOf(a, g);
			boolean busy = false;
			for (String ch : AnimxUtil.ALL)if (AnimxUtil.count(ba, ch) > 0)busy = true;
			if (busy) {
				res.error = "cpmplus.w6animx.sit.move.globalBusy";
				return res;
			}
			target = a;
			break;
		}
		List<Animation> anims = new ArrayList<>(pr.keyed);
		if (target != null)anims.add(target);
		Animation existing = target;
		Animation[] made = new Animation[1];
		UndoData before = AnimxUtil.track(UndoData.make(anims.toArray(new Animation[0])), anims);
		PlusUndo.run(PlusDialogs.trf("cpmplus.w6animx.undo.moveGlobal", g.name), before, () -> {
			List<Animation> l = new ArrayList<>(anims);
			if (made[0] != null)l.add(made[0]);
			return AnimxUtil.track(UndoData.make(l.toArray(new Animation[0])), l);
		}, () -> {
			Animation glob = existing;
			if (glob == null) {
				glob = AnimxUtil.newAnimation(AnimxUtil.uniqueAnimName(PlusDialogs.tr("cpmplus.w6animx.sit.globalName")), "global", "loop", src.length, AnimxUtil.snapping(src));
				AnimxUtil.copyFlags(src, glob);
				made[0] = glob;
				res.created = true;
			}
			BoneAnimator dst = glob.getBoneAnimator(g);
			BoneAnimator sb = AnimxUtil.animatorOf(src, g);
			for (String ch : AnimxUtil.ALL) {
				for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(sb, ch))) {
					AnimxUtil.addKey(dst, AnimxUtil.keyCopy(kf));
					res.movedKeys++;
				}
			}
			for (Animation a : pr.keyed) {
				BoneAnimator ba = AnimxUtil.animatorOf(a, g);
				for (String ch : AnimxUtil.ALL)for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(ba, ch)))kf.remove();
			}
			res.global = glob;
		});
		res.ok = true;
		AnimxUtil.preview();
		return res;
	}

	// ------------------------------------------------------------------ Copiar pras que faltam

	public static final class CopyResult {
		public boolean ok;
		public String error;
		public final List<Animation> created = new ArrayList<>();
		public final List<Animation> filled = new ArrayList<>();
		/** target animations whose length differs from the source ('pode dar um salto na troca') */
		public final List<Animation> lengthDiffers = new ArrayList<>();
		public Animation source;
		public int copiedKeys;
	}

	/** the most similar source: Parado, then Andando, Correndo, Agachado, else the first moving situation */
	public static Animation sourceFor(PartReport pr) {
		Map<VanillaPose, List<Animation>> sit = situationAnims();
		for (VanillaPose p : LOCOMOTION) {
			if (!pr.moving.contains(p))continue;
			for (Animation a : sit.getOrDefault(p, Collections.emptyList())) {
				BoneAnimator ba = AnimxUtil.animatorOf(a, pr.group);
				for (String ch : AnimxUtil.ALL)if ("loop".equals(a.loop) && moves(a, ba, ch))return a;
			}
		}
		return pr.keyed.isEmpty() ? null : pr.keyed.get(0);
	}

	/**
	 * Copies the part's keys from the most similar situation into the target situations: into their existing looping
	 * animation, or a new animation with the right type and loop. adjustTime scales the key times to each target's
	 * length. One undo entry.
	 */
	public static CopyResult copyToMissing(PartReport pr, List<VanillaPose> targets, boolean adjustTime) {
		CopyResult res = new CopyResult();
		Animation src = sourceFor(pr);
		if (src == null || targets.isEmpty()) {
			res.error = "cpmplus.w6animx.sit.copy.nothing";
			return res;
		}
		res.source = src;
		Group g = pr.group;
		Map<VanillaPose, List<Animation>> sit = situationAnims();
		Map<VanillaPose, Animation> dest = new LinkedHashMap<>();
		List<Animation> existing = new ArrayList<>();
		for (VanillaPose p : targets) {
			Animation t = null;
			for (Animation a : sit.getOrDefault(p, Collections.emptyList()))if ("loop".equals(a.loop)) {
				t = a;
				break;
			}
			dest.put(p, t);
			if (t != null) {
				existing.add(t);
				if (Math.abs(t.length - src.length) > 1e-4)res.lengthDiffers.add(t);
			}
		}
		BoneAnimator sb = AnimxUtil.animatorOf(src, g);
		UndoData before = AnimxUtil.track(UndoData.make(existing.toArray(new Animation[0])), existing);
		PlusUndo.run(PlusDialogs.trf("cpmplus.w6animx.undo.copyMissing", g.name), before, () -> {
			List<Animation> l = new ArrayList<>(existing);
			l.addAll(res.created);
			return AnimxUtil.track(UndoData.make(l.toArray(new Animation[0])), l);
		}, () -> {
			for (Map.Entry<VanillaPose, Animation> e : dest.entrySet()) {
				Animation t = e.getValue();
				if (t == null) {
					t = AnimxUtil.newAnimation(AnimxUtil.uniqueAnimName(label(e.getKey())), e.getKey().name().toLowerCase(Locale.ROOT), "loop", src.length, AnimxUtil.snapping(src));
					AnimxUtil.copyFlags(src, t);
					res.created.add(t);
				} else res.filled.add(t);
				double scale = adjustTime && src.length > 1e-4 && t.length > 1e-4 ? t.length / src.length : 1;
				BoneAnimator dst = t.getBoneAnimator(g);
				for (String ch : AnimxUtil.ALL) {
					List<Keyframe> sk = AnimxUtil.sorted(AnimxUtil.keys(sb, ch));
					if (sk.isEmpty())continue;
					for (Keyframe old : AnimxUtil.sorted(AnimxUtil.keys(dst, ch)))old.remove();
					for (Keyframe kf : sk) {
						Object d = AnimxUtil.keyCopy(kf);
						if (scale != 1)AnimxUtil.setTime(d, kf.time * scale);
						AnimxUtil.addKey(dst, d);
						res.copiedKeys++;
					}
				}
			}
		});
		res.ok = true;
		AnimxUtil.preview();
		return res;
	}

	// ------------------------------------------------------------------ JSON (tests)

	public static JsPropertyMap<Object> toJson(Report r) {
		JsPropertyMap<Object> o = AnimxUtil.map();
		JsArray<Object> table = new JsArray<>();
		for (VanillaPose p : r.table)table.push(p.name());
		o.set("table", table);
		JsArray<Object> parts = new JsArray<>();
		for (PartReport pr : r.parts)parts.push(partJson(pr));
		o.set("parts", parts);
		o.set("ignored", r.ignored);
		o.set("customPoses", r.customPoses);
		o.set("ms", r.ms);
		return o;
	}

	static JsPropertyMap<Object> partJson(PartReport pr) {
		JsPropertyMap<Object> m = AnimxUtil.map();
		m.set("group", pr.group.name);
		m.set("uuid", pr.group.uuid);
		m.set("channels", AnimxUtil.arr(new ArrayList<Object>(pr.channels)));
		List<Object> mv = new ArrayList<>(), ms = new ArrayList<>(), mr = new ArrayList<>();
		for (VanillaPose p : pr.moving)mv.add(p.name());
		for (VanillaPose p : pr.missing)ms.add(p.name());
		for (VanillaPose p : pr.missingRare)mr.add(p.name());
		m.set("moving", AnimxUtil.arr(mv));
		m.set("missing", AnimxUtil.arr(ms));
		m.set("missingRare", AnimxUtil.arr(mr));
		m.set("canMoveToGlobal", pr.canMoveToGlobal);
		m.set("text", sentence(pr));
		return m;
	}

	public static PartReport find(Report r, Group g) {
		for (PartReport pr : r.parts)if (pr.group == g)return pr;
		return null;
	}

	static String join(List<VanillaPose> l) {
		List<String> s = new ArrayList<>();
		for (VanillaPose p : l)s.add(label(p));
		return String.join(", ", s);
	}

	/** "'Rabo' se mexe em Parado, Andando, mas não em Pulando, Caindo: ele para e volta à posição normal ao pular." */
	public static String sentence(PartReport pr) {
		List<VanillaPose> mv = new ArrayList<>();
		for (VanillaPose p : CHAIN)if (pr.moving.contains(p))mv.add(p);
		return PlusDialogs.trf("cpmplus.w6animx.sit.sentence", pr.group.name, join(mv), join(pr.missing), when(pr.missing.get(0)));
	}

	// ------------------------------------------------------------------ dialog

	public static void open() {
		Report r = check();
		StringBuilder html = new StringBuilder();
		html.append("<p>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.sit.intro"))).append("</p>");
		if (r.parts.isEmpty())html.append("<p><b>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.sit.none"))).append("</b></p>");
		for (PartReport pr : r.parts) {
			html.append("<div style='margin:6px 0;padding:4px 6px;border-left:3px solid #e6a23c'>");
			html.append("<div>").append(PlusDialogs.esc(sentence(pr))).append("</div><div>");
			for (VanillaPose p : r.table) {
				if (isRare(p))continue;
				boolean on = pr.moving.contains(p);
				html.append(chip(label(p), on));
			}
			html.append("</div>");
			if (!pr.missingRare.isEmpty()) {
				html.append("<details><summary>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.sit.rare"))).append("</summary>");
				for (VanillaPose p : r.table)if (isRare(p))html.append(chip(label(p), pr.moving.contains(p)));
				html.append("</details>");
			}
			html.append("</div>");
		}
		if (r.customPoses)html.append("<p>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.sit.note.custom"))).append("</p>");
		html.append("<p style='opacity:.8'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.sit.note.server"))).append("</p>");
		if (r.ignored > 0)html.append("<p style='opacity:.8'>").append(PlusDialogs.esc(PlusDialogs.trf("cpmplus.w6animx.sit.ignoredCount", r.ignored))).append("</p>");

		List<FormRow> rows = new ArrayList<>();
		for (PartReport pr : r.parts) {
			List<String> btns = new ArrayList<>();
			if (pr.canMoveToGlobal)btns.add("cpmplus.w6animx.sit.btn.global");
			btns.add("cpmplus.w6animx.sit.btn.copy");
			btns.add("cpmplus.w6animx.sit.btn.ignore");
			btns.add("cpmplus.w6animx.sit.btn.show");
			rows.add(FormRow.buttons("p_" + pr.group.uuid.replace("-", ""), pr.group.name, btns.toArray(new String[0]), i -> {
				String b = btns.get(i);
				if (b.endsWith(".global"))moveDialog(pr, r);
				else if (b.endsWith(".copy"))copyDialog(pr, r);
				else if (b.endsWith(".ignore")) {
					setIgnored(pr.group, true, true);
					closeAndReopen();
				} else show(pr);
			}));
		}
		if (!ignored().isEmpty())rows.add(FormRow.buttons("ign", null, new String[] {"cpmplus.w6animx.sit.btn.showIgnored"}, i -> ignoredDialog()));
		FormOpts o = new FormOpts();
		o.htmlLines = new String[] {html.toString()};
		o.width = 640;
		PlusDialogs.form("cpmplus_w6animx_situations", "cpmplus.w6animx.sit.title", rows, x -> {
		}, o);
	}

	private static String chip(String text, boolean on) {
		return "<span style='display:inline-block;margin:2px 4px 2px 0;padding:1px 7px;border-radius:8px;color:#fff;background:" + (on ? "#2e9e4f" : "#7a7a7a") + "'>"
				+ (on ? "✓ " : "✗ ") + PlusDialogs.esc(text) + "</span>";
	}

	private static native void closeDialog(String id) /*-{
		var d = $wnd.Dialog && $wnd.Dialog.open;
		if (d && d.id === id && typeof d.hide === 'function') d.hide();
	}-*/;

	private static void closeAndReopen() {
		closeDialog("cpmplus_w6animx_situations");
		open();
	}

	private static void show(PartReport pr) {
		Animation a = pr.keyed.isEmpty() ? null : pr.keyed.get(0);
		closeDialog("cpmplus_w6animx_situations");
		if (a != null)a.select();
		pr.group.select();
	}

	static void moveDialog(PartReport pr, Report r) {
		List<String> lines = new ArrayList<>();
		List<VanillaPose> nm = newlyMoving(pr, r);
		lines.add(PlusDialogs.trf("cpmplus.w6animx.sit.move.preview", pr.group.name, join(nm)));
		List<Animation> conf = conflicts(pr);
		if (!conf.isEmpty()) {
			lines.add(PlusDialogs.tr("cpmplus.w6animx.sit.move.conflicts"));
			for (Animation a : conf)lines.add(" - " + PlusDialogs.trf("cpmplus.w6animx.sit.move.conflictLine", a.name, a.getPriority(), PlusDialogs.tr(a.additive ? "cpmplus.w6animx.additive" : "cpmplus.w6animx.replaces")));
		}
		PlusDialogs.messageBox("cpmplus.w6animx.sit.btn.global", String.join("\n", lines), new String[] {"cpmplus.w6animx.sit.move.ok", "cpmplus.w0binfra.btn.cancel"}, i -> {
			if (i != 0)return;
			MoveResult m = moveToGlobal(pr);
			if (!m.ok) {
				PlusDialogs.block(m.error);
				return;
			}
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w6animx.sit.move.done", pr.group.name, m.global.name));
			closeAndReopen();
		});
	}

	static void copyDialog(PartReport pr, Report r) {
		Animation src = sourceFor(pr);
		Map<String, String> opts = new LinkedHashMap<>();
		List<String> sel = new ArrayList<>();
		for (VanillaPose p : pr.missing) {
			opts.put(p.name(), label(p));
			sel.add(p.name());
		}
		for (VanillaPose p : pr.missingRare)opts.put(p.name(), label(p));
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("src", null, PlusDialogs.trf("cpmplus.w6animx.sit.copy.from", pr.group.name, src != null ? src.name : "-")));
		rows.add(FormRow.multiSelect("targets", "cpmplus.w6animx.sit.copy.targets", opts, sel));
		rows.add(FormRow.checkbox("adjust", "cpmplus.w6animx.sit.copy.adjust", false).description("cpmplus.w6animx.sit.copy.jump"));
		PlusDialogs.form("cpmplus_w6animx_sit_copy", "cpmplus.w6animx.sit.btn.copy", rows, fr -> {
			List<VanillaPose> t = new ArrayList<>();
			for (String s : fr.getList("targets"))t.add(VanillaPose.valueOf(s));
			CopyResult c = copyToMissing(pr, t, fr.getBool("adjust"));
			if (!c.ok) {
				PlusDialogs.block(c.error);
				return;
			}
			String msg = PlusDialogs.trf("cpmplus.w6animx.sit.copy.done", pr.group.name, c.filled.size() + c.created.size());
			if (!c.lengthDiffers.isEmpty() && !fr.getBool("adjust"))msg += " " + PlusDialogs.tr("cpmplus.w6animx.sit.copy.jump");
			PlusDialogs.done(msg);
			closeAndReopen();
		});
	}

	static void ignoredDialog() {
		Set<String> ig = ignored();
		Map<String, String> opts = new LinkedHashMap<>();
		for (String u : ig) {
			Group g = AnimxUtil.groupByUuidOrName(u);
			opts.put(u, g != null ? g.name : u);
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.multiSelect("keep", "cpmplus.w6animx.sit.ignoredList", opts, new ArrayList<>(ig)));
		PlusDialogs.form("cpmplus_w6animx_sit_ignored", "cpmplus.w6animx.sit.btn.showIgnored", rows, fr -> {
			List<String> keep = fr.getList("keep");
			for (String u : ig) {
				if (keep.contains(u))continue;
				Group g = AnimxUtil.groupByUuidOrName(u);
				if (g != null)setIgnored(g, false, true);
			}
			closeAndReopen();
		});
	}
}
