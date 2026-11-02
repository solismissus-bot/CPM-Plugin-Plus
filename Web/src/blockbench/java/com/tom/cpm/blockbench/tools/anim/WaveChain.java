package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ F9 'Fazer onda' (W4-ANIM2): a whip / wave along a bone chain (tail, hair, wing).
 * <ul>
 * <li>Chain = the first bone, then at each level its only child group (plugin rotation subgroups '_rN' and item
 * slots are skipped); where it splits the user picks the branch (never guessed).</li>
 * <li>'Onda nova': bone i gets A_i * sin(2 pi (w t / L - i d)) on one rotation axis, catmullrom keys on a uniform grid
 * (at least 8 per wave, at most 64 per bone, a key at L equal to the key at 0), whole waves only so the loop closes;
 * the grid follows the animation snapping when it can, so the keys sit on the timeline ticks. A_i grows to 2 x A at
 * the tip with 'Ponta mexe mais'. The exporter writes these keys as a seamless POLY_LOOP.</li>
 * <li>'Seguir a 1ª peça': the rotation keys of the first bone copied to the next bones with delay i * d * L (rounded to
 * the snapping) and strength f_i - the F10 'copy to parts' math (scale around rest, wrap around the loop with the
 * value at the cut, full-turn spins kept, molang keys skipped and counted).</li>
 * <li>All values are computed before anything is written; one undo entry; the frames the export will write are
 * checked with AnimEdit.predictFrames (255 frames / 32.767 s, Convention 10) and the edit is cancelled when over.</li>
 * </ul>
 */
public final class WaveChain {
	public static final String MODE_NEW = "new";
	public static final String MODE_FOLLOW = "follow";
	public static final int MAX_BONES = 32;
	public static final int MIN_KEYS_PER_WAVE = 8;
	public static final int MAX_KEYS_PER_BONE = 64;
	/** 7 waves x 8 keys + the closing key = 57 keys per bone (8 waves would need 65) */
	public static final int MAX_WAVES = (MAX_KEYS_PER_BONE - 1) / MIN_KEYS_PER_WAVE;
	private static final String K = "cpmplus.w4anim2.wave.";
	private static final String ROT = "rotation";
	/** CPM format limit: an animation lasts at most 32.767 s (duration in ms is a short) */
	private static final int MAX_DURATION_MS = 32767;

	private WaveChain() {
	}

	/** what the user chose */
	public static final class Opts {
		public String mode = MODE_NEW;
		/** rotation axis 'x' / 'y' / 'z' */
		public String axis = "y";
		/** amplitude in degrees */
		public double size = 15;
		public int waves = 1;
		/** delay between two bones, fraction of a wave (0 .. 0.5) */
		public double delay = 0.15;
		public boolean tipMore = true;
		/** length (s) used only when the animation is still empty (length 0) */
		public double length = 1;
		/** uuids of chain bones left out */
		public Set<String> skip = new HashSet<>();
	}

	/** the chain from a first bone; branchAt != null when it splits there and no choice was given */
	public static final class Chain {
		public final List<Group> bones = new ArrayList<>();
		public Group branchAt;
		public final List<Group> branches = new ArrayList<>();
		public boolean truncated;
	}

	/** result of {@link #apply}: blocked != null = refused (nothing changed) */
	public static final class Result {
		public boolean ok, changed;
		public String blocked;
		public int keys, bones, frames;
		public final List<String> warnings = new ArrayList<>();
	}

	/** refusal inside the undo body (cancels the edit) */
	private static final class Refused extends RuntimeException {
		private static final long serialVersionUID = 1L;

		Refused(String msg) {
			super(msg);
		}
	}

	// ====================================================================== chain

	/** child groups that can continue a chain (no plugin rotation subgroup '_rN', no item slot) */
	public static List<Group> chainChildren(Group g) {
		List<Group> out = new ArrayList<>();
		if (g == null || g.children == null)return out;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (!(n instanceof Group))continue;
			Group c = (Group) n;
			if (c.is_rotation_subgroup && c.name != null && c.name.matches(".*_r\\d+$"))continue;
			if (c.getData().getItemRenderer() != null)continue;
			out.add(c);
		}
		return out;
	}

	/**
	 * first bone, then its only chain child at each level. choices: parent uuid -> chosen child uuid, or "" to stop
	 * there. A split without a choice ends the walk with branchAt set.
	 */
	public static Chain buildChain(Group first, Map<String, String> choices) {
		Chain c = new Chain();
		Group cur = first;
		while (cur != null) {
			c.bones.add(cur);
			if (c.bones.size() >= MAX_BONES) {
				c.truncated = !chainChildren(cur).isEmpty();
				break;
			}
			List<Group> ch = chainChildren(cur);
			if (ch.isEmpty())break;
			if (ch.size() == 1) {
				cur = ch.get(0);
				continue;
			}
			String pick = choices != null ? choices.get(cur.uuid) : null;
			if (pick == null) {
				c.branchAt = cur;
				c.branches.addAll(ch);
				break;
			}
			Group next = null;
			for (Group g : ch)if (g.uuid.equals(pick))next = g;
			cur = next;// "" (stop) or unknown -> end
		}
		return c;
	}

	/** index of the main direction of the chain (0 x, 1 y, 2 z): first bone pivot -> last bone pivot (or its cubes) */
	public static int mainDirection(List<Group> bones) {
		if (bones.isEmpty())return 2;
		JsVec3 a = bones.get(0).origin;
		double dx = 0, dy = 0, dz = 0;
		if (bones.size() > 1) {
			JsVec3 b = bones.get(bones.size() - 1).origin;
			dx = b.x - a.x;
			dy = b.y - a.y;
			dz = b.z - a.z;
		}
		if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < 1e-6) {
			// one bone (or all pivots equal): the centre of the last bone's cubes
			Group last = bones.get(bones.size() - 1);
			int n = 0;
			for (int i = 0; last.children != null && i < last.children.length; i++) {
				OutlinerNode o = last.children.getAt(i);
				if (!(o instanceof Cube))continue;
				Cube cu = (Cube) o;
				dx += (cu.from.x + cu.to.x) / 2 - a.x;
				dy += (cu.from.y + cu.to.y) / 2 - a.y;
				dz += (cu.from.z + cu.to.z) / 2 - a.z;
				n++;
			}
			if (n == 0)return 2;
		}
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ax >= ay && ax >= az)return 0;
		if (ay >= az)return 1;
		return 2;
	}

	/** rotation axis for 'side to side' (default), 'up/down' (or front/back) and 'twist' for a main direction */
	static String[] axisRoles(int dir) {
		switch (dir) {
		case 0: return new String[] {"z", "y", "x"};// wing: flap up/down (z), forward/back (y), twist (x)
		case 1: return new String[] {"z", "x", "y"};// hanging hair: sideways (z), front/back (x), twist (y)
		default: return new String[] {"y", "x", "z"};// tail: sideways (y), up/down (x), twist (z)
		}
	}

	// ====================================================================== UI

	/** CPM+ > Animação > 'Fazer onda…' and the group context menu (Animate mode) */
	public static void open() {
		Animation anim = Animation.selected;
		if (!Animator.open || anim == null) {
			PlusDialogs.block(K + "needAnimate");
			return;
		}
		Group first = selectedGroup();
		if (first == null) {
			PlusDialogs.block(K + "needGroup");
			return;
		}
		askBranches(first, new LinkedHashMap<>(), anim);
	}

	private static void askBranches(Group first, Map<String, String> choices, Animation anim) {
		Chain c = buildChain(first, choices);
		if (c.branchAt == null) {
			showDialog(anim, c);
			return;
		}
		// option values must be valid HTML ids (Blockbench builds "input#<value>"): never empty, never a uuid that may
		// start with a digit -> "b<index>" / "stop", mapped back to the uuid below
		Map<String, String> opts = new LinkedHashMap<>();
		for (int i = 0; i < c.branches.size(); i++)opts.put("b" + i, c.branches.get(i).name);
		opts.put("stop", K + "branch.stop");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("text", null, PlusDialogs.trf(K + "branch.text", c.branchAt.name)));
		rows.add(FormRow.radio("branch", K + "branch.label", opts, "b0"));
		PlusDialogs.form("cpmplus_w4anim2_wave_branch", K + "branch.title", rows, r -> {
			String v = r.getString("branch");
			String uuid = "";
			if (v != null && v.startsWith("b")) {
				try {
					uuid = c.branches.get(Integer.parseInt(v.substring(1))).uuid;
				} catch (RuntimeException e) {
					uuid = "";
				}
			}
			choices.put(c.branchAt.uuid, uuid);
			askBranches(first, choices, anim);
		});
	}

	private static void showDialog(Animation anim, Chain c) {
		List<Group> bones = c.bones;
		int dir = mainDirection(bones);
		String[] roles = axisRoles(dir);
		String[] roleKeys = {K + "axis.side", dir == 1 ? K + "axis.frontback" : dir == 0 ? K + "axis.frontback" : K + "axis.updown", K + "axis.twist"};
		if (dir == 0)roleKeys[0] = K + "axis.updown";
		Map<String, String> axes = new LinkedHashMap<>();
		for (int i = 0; i < 3; i++)axes.put(roles[i], PlusDialogs.trf(roleKeys[i], roles[i].toUpperCase()));
		boolean empty = anim.length < 1e-4;
		BoneAnimator src = animatorOf(anim, bones.get(0));
		boolean srcHasRot = src != null && src.rotation != null && src.rotation.length > 0;

		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, K + "intro"));
		StringBuilder names = new StringBuilder();
		for (Group g : bones)names.append(names.length() > 0 ? " > " : "").append(g.name);
		rows.add(FormRow.info("chain", null, PlusDialogs.trf(K + "chain", names.toString())));
		if (c.truncated)rows.add(FormRow.info("trunc", null, PlusDialogs.trf(K + "tooManyBones", MAX_BONES, MAX_BONES)));
		rows.add(FormRow.info("anim", null, PlusDialogs.trf(K + "anim", anim.name, fmt(anim.length))));
		for (int i = 0; i < bones.size(); i++)rows.add(FormRow.checkbox("b" + i, bones.get(i).name, true));
		rows.add(FormRow.radio("mode", K + "mode", PlusDialogs.options(MODE_NEW, K + "mode.new", MODE_FOLLOW, K + "mode.follow"), MODE_NEW));
		rows.add(FormRow.info("followSrc", null, PlusDialogs.trf(K + "followSrc", bones.get(0).name)).when(r -> MODE_FOLLOW.equals(r.getString("mode"))));
		if (!srcHasRot)rows.add(FormRow.info("noSrc", null, PlusDialogs.trf(K + "err.noSrcKeys", bones.get(0).name)).when(r -> MODE_FOLLOW.equals(r.getString("mode"))));
		rows.add(FormRow.select("axis", K + "axis", axes, roles[0]).when(r -> !MODE_FOLLOW.equals(r.getString("mode"))));
		rows.add(FormRow.number("size", K + "size", 15, 1, 180).when(r -> !MODE_FOLLOW.equals(r.getString("mode"))));
		rows.add(FormRow.number("waves", K + "waves", 1, 1, MAX_WAVES).description(K + "waves.desc").when(r -> !MODE_FOLLOW.equals(r.getString("mode"))));
		rows.add(FormRow.number("delay", K + "delay", 15, 0, 50));
		rows.add(FormRow.checkbox("tipMore", K + "tipMore", true).description(K + "tipMore.desc"));
		if (empty)rows.add(FormRow.decimal("length", K + "length", 1, 0.05, 32.7, 0.05));
		List<String> keyed = new ArrayList<>();
		for (int i = 0; i < bones.size(); i++) {
			BoneAnimator ba = animatorOf(anim, bones.get(i));
			if (ba != null && ba.rotation != null && ba.rotation.length > 0)keyed.add(bones.get(i).name);
		}
		if (!keyed.isEmpty())rows.add(FormRow.info("replace", null, PlusDialogs.trf(K + "replace", String.join(", ", keyed))));
		if (!"loop".equals(anim.loop))rows.add(FormRow.info("notLoop", null, K + "notLoop"));

		FormOpts fo = new FormOpts();
		fo.width = 520;
		PlusDialogs.form("cpmplus_w4anim2_wave", K + "title", rows, r -> {
			Opts o = readOpts(r, bones);
			Result res = apply(anim, bones, o);
			if (res.blocked != null) {
				r.keepOpen();
				PlusDialogs.block(res.blocked);
				return;
			}
			done(anim, res);
		}, fo);
	}

	private static Opts readOpts(FormResult r, List<Group> bones) {
		Opts o = new Opts();
		String m = r.getString("mode");
		o.mode = MODE_FOLLOW.equals(m) ? MODE_FOLLOW : MODE_NEW;
		String a = r.getString("axis");
		if (a != null && a.matches("[xyz]"))o.axis = a;
		o.size = r.getDouble("size", 15);
		o.waves = r.getInt("waves", 1);
		o.delay = r.getDouble("delay", 15) / 100.0;
		o.tipMore = r.getBool("tipMore");
		o.length = r.getDouble("length", 1);
		for (int i = 0; i < bones.size(); i++)if (!r.getBool("b" + i))o.skip.add(bones.get(i).uuid);
		return o;
	}

	/** after a successful apply: show it playing + toast */
	public static void done(Animation anim, Result res) {
		try {
			anim.select();
			Animator.preview();
		} catch (Throwable e) {
		}
		for (String w : res.warnings)PlusDialogs.toast(w, "warning", 8000, null);
		PlusDialogs.done(PlusDialogs.trf(K + "done", res.bones, res.keys));
	}

	// ====================================================================== core

	/**
	 * Writes the wave (one undo entry). Nothing is changed when the result is blocked. Throws only on unexpected
	 * errors (the edit is cancelled then too).
	 */
	public static Result apply(Animation anim, List<Group> chain, Opts o) {
		Result r = new Result();
		if (anim == null) {
			r.blocked = PlusDialogs.tr(K + "needAnimate");
			return r;
		}
		int n = chain.size();
		List<Integer> idx = new ArrayList<>();
		boolean follow = MODE_FOLLOW.equals(o.mode);
		for (int i = 0; i < n; i++) {
			if (o.skip.contains(chain.get(i).uuid))continue;
			if (follow && i == 0)continue;// the source keeps its keys
			idx.add(i);
		}
		if (idx.isEmpty()) {
			r.blocked = PlusDialogs.tr(K + "err.noBones");
			return r;
		}
		double len = anim.length > 1e-4 ? anim.length : o.length;
		if (!(len >= 0.05) || len > MAX_DURATION_MS / 1000.0) {
			r.blocked = PlusDialogs.tr(K + "err.length");
			return r;
		}
		double d = Math.max(0, Math.min(0.5, o.delay));
		int snapping = anim.snapping;
		final double L = len;

		// every key of every bone, computed before anything is written
		Map<Group, List<JsPropertyMap<Object>>> out = new LinkedHashMap<>();
		if (!follow) {
			if (o.waves < 1 || o.waves > MAX_WAVES) {
				r.blocked = PlusDialogs.trf(K + "err.waves", MAX_WAVES);
				return r;
			}
			int ax = "x".equals(o.axis) ? 0 : "z".equals(o.axis) ? 2 : 1;
			int m = gridSize(o.waves, L, snapping);
			for (int i : idx) {
				double amp = o.size * tipFactor(i, n, o.tipMore);
				List<JsPropertyMap<Object>> keys = new ArrayList<>();
				for (int k = 0; k <= m; k++) {
					double t = k * L / m;
					double v = amp * Math.sin(2 * Math.PI * ((double) o.waves * k / m - i * d));
					if (Math.abs(v) < 1e-9)v = 0;
					double[] vec = new double[3];
					vec[ax] = v;
					keys.add(key(t, vec, "catmullrom"));
				}
				out.put(chain.get(i), keys);
			}
		} else {
			BoneAnimator src = animatorOf(anim, chain.get(0));
			if (src == null || src.rotation == null || src.rotation.length == 0 || src.isMuted(ROT)) {
				r.blocked = PlusDialogs.trf(K + "err.noSrcKeys", chain.get(0).name);
				return r;
			}
			boolean loop = "loop".equals(anim.loop);
			int[] skipped = new int[2];// molang, dropped
			for (int i : idx) {
				double dt = i * d * L;
				if (snapping >= 1)dt = Math.round(dt * snapping) / (double) snapping;
				if (loop)dt = dt % L;
				out.put(chain.get(i), follow(src.rotation, L, dt, tipFactor(i, n, o.tipMore), loop, skipped));
			}
			if (skipped[0] > 0)r.warnings.add(PlusDialogs.trf(K + "warn.molang", skipped[0] / Math.max(1, idx.size())));
			if (skipped[1] > 0)r.warnings.add(PlusDialogs.trf(K + "warn.dropped", skipped[1]));
		}

		// format limits are checked BEFORE anything is written: Blockbench's cancelEdit does not remove keys added to
		// bone animators that did not exist when the edit started, so a refusal inside the edit could leave keys behind
		{
			var pre = AnimEdit.predictFrames(anim, anim.snapping, Math.max(anim.length, L));
			if (pre.durationMs > MAX_DURATION_MS) {
				r.blocked = PlusDialogs.trf(K + "err.duration", fmt(pre.durationMs / 1000.0));
				return r;
			}
			if (!pre.ok) {
				r.blocked = PlusDialogs.trf(K + "err.frames", pre.frames);
				return r;
			}
		}
		int[] count = new int[1];
		int[] frames = new int[1];
		try {
			PlusUndo.editAnimations(Collections.singletonList(anim), PlusDialogs.tr(K + "undo"), () -> {
				for (Map.Entry<Group, List<JsPropertyMap<Object>>> e : out.entrySet()) {
					BoneAnimator ba = anim.getBoneAnimator(e.getKey());
					if (ba == null)continue;
					clearChannel(ba, ROT);
					for (JsPropertyMap<Object> k : e.getValue()) {
						if (ba.addKeyframe(k, null) != null)count[0]++;
					}
				}
				if (anim.length < L - 1e-6)anim.setLength(L);
				var p = AnimEdit.predictFrames(anim, anim.snapping, anim.length);
				frames[0] = p.frames;
				if (p.durationMs > MAX_DURATION_MS)throw new Refused(PlusDialogs.trf(K + "err.duration", fmt(p.durationMs / 1000.0)));
				if (!p.ok)throw new Refused(PlusDialogs.trf(K + "err.frames", p.frames));
			});
		} catch (Refused e) {
			r.blocked = e.getMessage();
			r.warnings.clear();
			return r;
		}
		r.ok = true;
		r.changed = count[0] > 0;
		r.keys = count[0];
		r.bones = out.size();
		r.frames = frames[0];
		return r;
	}

	/** 1 at the first bone, up to 2 at the tip ('Ponta mexe mais'), else 1 */
	public static double tipFactor(int i, int n, boolean tipMore) {
		if (!tipMore || n < 2)return 1;
		return 1 + (double) i / (n - 1);
	}

	/**
	 * keys per animation for a wave: on the snapping grid when L * snapping is whole and has a divisor m with
	 * m % waves == 0, m / waves >= 8 and m + 1 <= 64 (the largest such m); else waves * min(16, 63 / waves).
	 */
	public static int gridSize(int waves, double len, int snapping) {
		if (snapping >= 1) {
			double s = len * snapping;
			long si = Math.round(s);
			if (si > 0 && Math.abs(s - si) < 1e-6) {
				for (int m = MAX_KEYS_PER_BONE - 1; m >= MIN_KEYS_PER_WAVE * waves; m--) {
					if (m % waves == 0 && si % m == 0)return m;
				}
			}
		}
		return waves * Math.min(16, (MAX_KEYS_PER_BONE - 1) / waves);
	}

	/**
	 * F10 'copy to parts' math for one bone: the source rotation keys scaled by k around rest and shifted by dt;
	 * loop: wrapped around L with keys at 0 and L holding the value at the cut, full-turn spins kept; once: keys
	 * past L dropped (counted), the start pose held until dt.
	 */
	static List<JsPropertyMap<Object>> follow(JsArrayE<Keyframe> srcKeys, double len, double dt, double k, boolean loop, int[] skipped) {
		List<Keyframe> keys = new ArrayList<>();
		for (int i = 0; i < srcKeys.length; i++)keys.add(srcKeys.getAt(i));
		keys.sort((a, b) -> Float.compare(a.time, b.time));
		double[] spin = new double[3];
		if (loop) {
			JsVec3 v0 = Animation.interpolate(srcKeys, 0), vl = Animation.interpolate(srcKeys, (float) len);
			if (v0 != null && vl != null) {
				double[] a = {v0.x, v0.y, v0.z}, b = {vl.x, vl.y, vl.z};
				for (int c = 0; c < 3; c++) {
					double dlt = b[c] - a[c];
					long turns = Math.round(dlt / 360.0);
					if (turns != 0 && Math.abs(dlt - 360.0 * turns) < 0.5)spin[c] = 360.0 * turns;
				}
			}
		}
		List<JsPropertyMap<Object>> out = new ArrayList<>();
		List<Double> times = new ArrayList<>();
		for (Keyframe kf : keys) {
			JsPropertyMap<Object> copy = Js.uncheckedCast(kf.getUndoCopy());
			if (!scaleData(copy, k)) {
				skipped[0]++;
				continue;
			}
			double t = kf.time + dt;
			boolean wrapped = false;
			if (t > len + 1e-5) {
				if (!loop) {
					skipped[1]++;
					continue;
				}
				t -= len;
				wrapped = true;
			}
			if (wrapped && (spin[0] != 0 || spin[1] != 0 || spin[2] != 0))offset(copy, -spin[0] * k, -spin[1] * k, -spin[2] * k);
			if (containsTime(times, t))continue;// the copy of the key at L lands on the one at 0 + dt
			copy.delete("uuid");
			copy.delete("animator");
			copy.set("time", t);
			copy.set("channel", ROT);
			times.add(t);
			out.add(copy);
		}
		if (dt > 1e-5 && dt < len - 1e-5) {
			if (loop) {
				// value at the cut, at both ends
				JsVec3 cut = Animation.interpolate(srcKeys, (float) (len - dt));
				String interp = interpBefore(keys, len - dt);
				if (cut != null) {
					double[] v = {cut.x * k, cut.y * k, cut.z * k};
					if (!containsTime(times, len))out.add(key(len, v, interp));
					if (!containsTime(times, 0))out.add(key(0, new double[] {v[0] - spin[0] * k, v[1] - spin[1] * k, v[2] - spin[2] * k}, interp));
				}
			} else {
				JsVec3 start = Animation.interpolate(srcKeys, 0);
				if (start != null && !containsTime(times, 0))out.add(key(0, new double[] {start.x * k, start.y * k, start.z * k}, interpBefore(keys, 0)));
				if (skipped[1] > 0 && !containsTime(times, len)) {
					JsVec3 end = Animation.interpolate(srcKeys, (float) (len - dt));
					if (end != null)out.add(key(len, new double[] {end.x * k, end.y * k, end.z * k}, interpBefore(keys, len - dt)));
				}
			}
		}
		return out;
	}

	private static boolean containsTime(List<Double> times, double t) {
		for (Double x : times)if (Math.abs(x - t) < 1e-5)return true;
		return false;
	}

	private static String interpBefore(List<Keyframe> sorted, double t) {
		String it = sorted.isEmpty() ? "linear" : sorted.get(0).interpolation;
		for (Keyframe kf : sorted)if (kf.time <= t + 1e-5 && kf.interpolation != null)it = kf.interpolation;
		return it != null && !it.isEmpty() ? it : "linear";
	}

	/** scales x / y / z of every data point and the bezier handle values by k; false when a value is a formula */
	private static boolean scaleData(JsPropertyMap<Object> copy, double k) {
		Object dps = copy.get("data_points");
		if (dps == null || !JsArray.isArray(dps))return false;
		JsArray<Object> arr = Js.uncheckedCast(dps);
		for (int i = 0; i < arr.length; i++) {
			JsPropertyMap<Object> dp = Js.uncheckedCast(arr.getAt(i));
			for (String a : new String[] {"x", "y", "z"}) {
				Double v = num(dp.get(a));
				if (v == null)return false;
				dp.set(a, v * k);
			}
		}
		for (String h : new String[] {"bezier_left_value", "bezier_right_value"}) {
			Object hv = copy.get(h);
			if (hv == null || !JsArray.isArray(hv))continue;
			JsArray<Object> a = Js.uncheckedCast(hv);
			JsArray<Object> na = new JsArray<>();
			for (int i = 0; i < a.length; i++) {
				Double v = num(a.getAt(i));
				na.push(v != null ? (Object) (v * k) : a.getAt(i));
			}
			copy.set(h, na);
		}
		return true;
	}

	private static void offset(JsPropertyMap<Object> copy, double ox, double oy, double oz) {
		JsArray<Object> arr = Js.uncheckedCast(copy.get("data_points"));
		for (int i = 0; i < arr.length; i++) {
			JsPropertyMap<Object> dp = Js.uncheckedCast(arr.getAt(i));
			dp.set("x", num(dp.get("x")) + ox);
			dp.set("y", num(dp.get("y")) + oy);
			dp.set("z", num(dp.get("z")) + oz);
		}
	}

	/** a number (or numeric text), null for a formula; empty / missing = 0 */
	static Double num(Object o) {
		if (o == null)return 0.0;
		String t = Js.typeof(o);
		if ("number".equals(t))return Js.asDouble(o);
		if ("string".equals(t)) {
			String s = ((String) o).trim();
			if (s.isEmpty())return 0.0;
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** keyframe data for GeneralAnimator.addKeyframe: one rotation data point */
	static JsPropertyMap<Object> key(double t, double[] v, String interp) {
		JsPropertyMap<Object> data = JsPropertyMap.of();
		data.set("channel", ROT);
		data.set("time", t);
		data.set("interpolation", interp != null ? interp : "linear");
		JsPropertyMap<Object> dp = JsPropertyMap.of();
		dp.set("x", v[0]);
		dp.set("y", v[1]);
		dp.set("z", v[2]);
		JsArray<Object> dps = new JsArray<>();
		dps.push(dp);
		data.set("data_points", dps);
		return data;
	}

	private static void clearChannel(BoneAnimator ba, String ch) {
		JsArrayE<Keyframe> arr = ba.channel(ch);
		if (arr == null)return;
		List<Keyframe> old = new ArrayList<>();
		for (int i = 0; i < arr.length; i++)old.add(arr.getAt(i));
		for (Keyframe kf : old)kf.remove();
	}

	/** the existing animator of a group in anim (never creates one), or null */
	public static BoneAnimator animatorOf(Animation anim, Group g) {
		if (anim == null || anim.animators == null || g == null)return null;
		Object a = anim.animators.get(g.uuid);
		return a instanceof BoneAnimator ? (BoneAnimator) a : null;
	}

	/** the selected group (first one), or null */
	public static Group selectedGroup() {
		Object g = selectedGroupNative();
		return g instanceof Group ? (Group) g : null;
	}

	private static native Object selectedGroupNative() /*-{
		var G = $wnd.Group;
		if (!G) return null;
		if (G.first_selected) return G.first_selected;
		if (G.multi_selected && G.multi_selected.length) return G.multi_selected[0];
		if (G.selected && G.selected instanceof G) return G.selected;
		if (G.selected && G.selected.length) return G.selected[0];
		return null;
	}-*/;

	static String fmt(double v) {
		double r = Math.round(v * 100) / 100.0;
		String s = String.valueOf(r);
		if (s.endsWith(".0"))s = s.substring(0, s.length() - 2);
		return s;
	}
}
