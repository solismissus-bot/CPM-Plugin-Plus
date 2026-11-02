package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PartPairing;
import com.tom.cpm.blockbench.util.PartPaths;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Copiar / Colar pose' for many parts at once (CPM's Copy / Paste / Mirror Part Pose works on one part).
 * <ul>
 * <li>Copiar pose: the pose at the playhead of the selected parts AND everything inside them (nothing selected =
 * the whole model): rotation / position / scale / colour value (Blockbench's own interpolation) and visibility.</li>
 * <li>Colar pose: one key per channel at the playhead (any animation, any time). Same parts; or, when ONE part
 * tree was copied and ONE other part is selected, onto that part and its children (matched by name).</li>
 * <li>Colar espelhado: the same, on the parts of the other side (left &lt;-&gt; right, by full path), mirrored
 * (position x, rotation y / z negated); parts without a side are mirrored in place.</li>
 * <li>Espelhar pose: the selected pose mirrored now: a part and its partner both selected swap sides, a part alone
 * is mirrored in place (like CPM's Mirror Part Pose).</li>
 * </ul>
 * A channel gets a key when the copied part moves in it or the target already has keys in it (then the rest value);
 * channels nobody animates stay empty. One undo entry; new animators are cleared by undo (AnimxUtil tracking).
 */
public final class PoseClipboard {
	private PoseClipboard() {
	}

	private static final String[] VALUE_CH = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE, KeyMath.COLOR};

	/** one copied part */
	static final class Part {
		String uuid, path, name;
		/** path below the copied root ('' = the root itself); null when several trees were copied */
		String rel;
		/** value at the copied time of every channel that has keys in the source */
		final Map<String, double[]> val = new LinkedHashMap<>();
		/** visibility at the copied time (only when the source has visibility keys) */
		Boolean vis;

		boolean moves() {
			return !val.isEmpty() || vis != null;
		}
	}

	private static List<Part> clip;
	private static String clipRoot, clipAnim;
	private static double clipTime;

	// ====================================================================== parts

	/** the selected groups and every group inside them; nothing selected = every group of the model */
	static List<Group> partsOf(List<Group> sel) {
		Set<Group> out = new LinkedHashSet<>();
		if (sel == null || sel.isEmpty()) {
			for (Group g : Group.all)out.add(g);
		} else {
			for (Group g : sel) {
				out.add(g);
				out.addAll(AnimxUtil.descendants(g));
			}
		}
		return new ArrayList<>(out);
	}

	/** selected groups that are not inside another selected group */
	static List<Group> roots(List<Group> sel) {
		List<Group> out = new ArrayList<>();
		for (Group g : sel) {
			boolean inner = false;
			for (Group o : sel)if (o != g && AnimxUtil.descendants(o).contains(g)) {
				inner = true;
				break;
			}
			if (!inner && !out.contains(g))out.add(g);
		}
		return out;
	}

	static List<Part> capture(Animation anim, double t, List<Group> parts, Group root) {
		String rp = root != null ? AnimEdit.pathOf(root) : null;
		List<Part> out = new ArrayList<>();
		for (Group g : parts) {
			Part p = new Part();
			p.uuid = g.uuid;
			p.name = g.name;
			p.path = AnimEdit.pathOf(g);
			if (rp != null)p.rel = g == root ? "" : p.path.startsWith(rp + "/") ? p.path.substring(rp.length() + 1) : null;
			BoneAnimator ba = AnimEdit.animatorOf(anim, g);
			if (ba != null) {
				for (String ch : VALUE_CH) {
					if (!AnimxUtil.hasKeys(ba, ch))continue;
					double[] v = AnimEdit.sample(ba, ch, t);
					if (v != null && !Double.isNaN(v[0]) && !Double.isNaN(v[1]) && !Double.isNaN(v[2]))p.val.put(ch, v);
				}
				if (AnimxUtil.hasKeys(ba, KeyMath.VIS))p.vis = AnimEdit.visAt(ba, t, true);
			}
			out.add(p);
		}
		return out;
	}

	static double[] mirror(String ch, double[] v) {
		if (KeyMath.POS.equals(ch))return new double[] {-v[0], v[1], v[2]};
		if (KeyMath.ROT.equals(ch))return new double[] {v[0], -v[1], -v[2]};
		return KeyMath.dup(v);
	}

	private static double snapTime(double time) {
		return Math.round(Math.max(0, time) * 1e6) / 1e6;
	}

	// ====================================================================== copy

	public static Result copy(Animation anim, double time, List<Group> sel) {
		Result r = new Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		double tt = snapTime(time);
		List<Group> rs = sel == null ? new ArrayList<>() : roots(sel);
		List<Part> c = capture(anim, tt, partsOf(sel), rs.size() == 1 ? rs.get(0) : null);
		int moving = 0;
		for (Part p : c)if (p.moves())moving++;
		clip = c;
		clipRoot = rs.size() == 1 ? rs.get(0).uuid : null;
		clipAnim = anim.name;
		clipTime = tt;
		r.put("parts", (double) c.size()).put("moving", (double) moving).put("time", tt).put("root", rs.size() == 1 ? rs.get(0).name : null);
		return r;
	}

	public static boolean hasClip() {
		return clip != null && !clip.isEmpty();
	}

	// ====================================================================== paste

	/** where each copied part goes; null target = no place for it */
	private static Map<Part, Group> targets(List<Part> src, List<Group> sel, boolean mirrored, Result r, boolean[] mirrorValues) {
		Map<Part, Group> out = new LinkedHashMap<>();
		Group other = sel != null && sel.size() == 1 ? sel.get(0) : null;
		boolean retarget = clipRoot != null && other != null && !other.uuid.equals(clipRoot);
		mirrorValues[0] = mirrored;
		int missing = 0;
		if (retarget) {
			String rp = AnimEdit.pathOf(other);
			for (Part p : src) {
				if (p.rel == null) {
					missing++;
					continue;
				}
				Group g = p.rel.isEmpty() ? other : AnimEdit.groupByPath(rp + "/" + p.rel);
				if (g == null && !p.rel.isEmpty())g = AnimEdit.groupByPath(rp + "/" + PartPaths.oppositePath(p.rel));
				if (g == null)missing++;
				else out.put(p, g);
			}
			r.put("retarget", other.name);
		} else {
			int guesses = 0;
			for (Part p : src) {
				Object o = Group.uuids != null ? Group.uuids.get(p.uuid) : null;
				Group g = o instanceof Group ? (Group) o : AnimEdit.groupByPath(p.path);
				if (g == null) {
					missing++;
					continue;
				}
				if (mirrored) {
					PartPairing.Pair<Object> pr = AnimEdit.pairOf(g);
					if (pr.kind == PartPairing.Kind.NONE || !(pr.target instanceof Group)) {
						missing++;
						continue;
					}
					if (pr.kind == PartPairing.Kind.SUGGESTION)guesses++;
					g = (Group) pr.target;
				}
				out.put(p, g);
			}
			if (guesses > 0)r.warn("guess", t("cpmplus.w3animtools.pose.warn.guess", guesses));
		}
		// two copied parts on one target: the first one wins
		Set<Group> used = new HashSet<>();
		int dup = 0;
		for (Map.Entry<Part, Group> e : new ArrayList<>(out.entrySet())) {
			if (!used.add(e.getValue())) {
				out.remove(e.getKey());
				dup++;
			}
		}
		if (missing + dup > 0)r.warn("missing", t("cpmplus.w3animtools.pose.warn.missing", missing + dup));
		return out;
	}

	private static final class Plan {
		final Group g;
		final String ch;
		final double[] v;
		final Boolean vis;

		Plan(Group g, String ch, double[] v, Boolean vis) {
			this.g = g;
			this.ch = ch;
			this.v = v;
			this.vis = vis;
		}
	}

	private static List<Plan> plan(Animation anim, Map<Part, Group> map, boolean mirror) {
		List<Plan> plans = new ArrayList<>();
		for (Map.Entry<Part, Group> e : map.entrySet()) {
			Part p = e.getKey();
			Group g = e.getValue();
			BoneAnimator have = AnimEdit.animatorOf(anim, g);
			for (String ch : VALUE_CH) {
				boolean src = p.val.containsKey(ch);
				boolean dst = have != null && AnimxUtil.hasKeys(have, ch);
				if (!src && (!dst || KeyMath.COLOR.equals(ch)))continue;
				double[] v = src ? p.val.get(ch) : AnimxUtil.rest(ch);
				plans.add(new Plan(g, ch, mirror ? mirror(ch, v) : KeyMath.dup(v), null));
			}
			if (p.vis != null)plans.add(new Plan(g, KeyMath.VIS, null, p.vis));
		}
		return plans;
	}

	/** writes the plans at t: one undo entry */
	private static void write(Animation anim, double t, List<Plan> plans, String label) {
		List<Animation> l = Collections.singletonList(anim);
		Animation[] arr = {anim};
		UndoData before = AnimxUtil.track(UndoData.make(arr), l);
		PlusUndo.run(label, before, () -> AnimxUtil.track(UndoData.make(arr), l), () -> {
			for (Plan p : plans) {
				BoneAnimator ba = anim.getBoneAnimator(p.g);
				if (ba == null)continue;
				if (p.vis != null) {
					AnimEdit.removeNear(ba, KeyMath.VIS, Collections.singletonList(t), KeyMath.EPS);
					AnimxUtil.addKey(ba, AnimxUtil.visKeyData(t, p.vis));
					continue;
				}
				AnimEdit.writeKeys(ba, p.ch, Collections.singletonList(key(ba, p.ch, t, p.v)), false);
			}
		});
		AnimToolSupport.refreshPreview();
	}

	/** the new key: interpolation of the key already there, else of the neighbour before (after), else linear */
	private static KeyData key(BoneAnimator ba, String ch, double t, double[] v) {
		KeyData before = null, after = null, at = null;
		for (KeyData k : AnimEdit.readKeys(ba, ch)) {
			if (Math.abs(k.time - t) < KeyMath.EPS)at = k;
			else if (k.time < t)before = k;
			else if (after == null)after = k;
		}
		KeyData like = at != null ? at : before != null ? before : after;
		KeyData nk = new KeyData();
		nk.interp = like != null && like.interp != null ? like.interp : "linear";
		if ("step".equals(nk.interp) && at == null && before == null)nk.interp = "linear";
		nk.time = t;
		nk.molang = false;
		nk.v = new double[][] {KeyMath.dup(v)};
		if ("bezier".equals(nk.interp)) {
			nk.bLT = new double[] {-0.1, -0.1, -0.1};
			nk.bLV = new double[] {0, 0, 0};
			nk.bRT = new double[] {0.1, 0.1, 0.1};
			nk.bRV = new double[] {0, 0, 0};
		}
		return nk;
	}

	public static Result paste(Animation anim, double time, List<Group> sel, boolean mirrored, boolean apply) {
		Result r = new Result();
		if (!hasClip())return r.error("empty", t("cpmplus.w3animtools.pose.err.empty"));
		if (anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		double tt = snapTime(time);
		boolean[] mv = new boolean[1];
		Map<Part, Group> map = targets(clip, sel, mirrored, r, mv);
		List<Plan> plans = plan(anim, map, mv[0]);
		Set<String> parts = new LinkedHashSet<>();
		for (Plan p : plans)parts.add(p.g.name);
		r.put("keys", (double) plans.size()).put("parts", (double) parts.size()).put("time", tt).put("from", clipAnim).put("fromTime", clipTime);
		List<String> names = new ArrayList<>();
		for (Map.Entry<Part, Group> e : map.entrySet())names.add(e.getKey().path + " -> " + AnimEdit.pathOf(e.getValue()));
		r.put("map", names);
		if (!apply || plans.isEmpty())return r;
		write(anim, tt, plans, t(mirrored ? "cpmplus.w3animtools.pose.undo.pasteMirror" : "cpmplus.w3animtools.pose.undo.paste"));
		r.changed = true;
		return r;
	}

	// ====================================================================== mirror in place

	public static Result mirrorPose(Animation anim, double time, List<Group> sel, boolean apply) {
		Result r = new Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		double tt = snapTime(time);
		List<Group> parts = partsOf(sel);
		Set<Group> set = new HashSet<>(parts);
		List<Part> now = capture(anim, tt, parts, null);
		Map<Part, Group> map = new LinkedHashMap<>();
		int swapped = 0;
		for (int i = 0; i < parts.size(); i++) {
			Group g = parts.get(i);
			Group dst = g;
			PartPairing.Pair<Object> pr = AnimEdit.pairOf(g);
			if ((PartPairing.sure(pr.kind) || pr.kind == PartPairing.Kind.SUGGESTION) && pr.target instanceof Group && set.contains(pr.target)) {
				dst = (Group) pr.target;
				swapped++;
			}
			map.put(now.get(i), dst);
		}
		List<Plan> plans = plan(anim, map, true);
		r.put("keys", (double) plans.size()).put("swapped", (double) swapped).put("inPlace", (double) (parts.size() - swapped)).put("time", tt);
		if (!apply || plans.isEmpty())return r;
		write(anim, tt, plans, t("cpmplus.w3animtools.pose.undo.mirror"));
		r.changed = true;
		return r;
	}

	// ====================================================================== menu actions

	private static String fmt(double t) {
		return PoseColumn.fmt(t);
	}

	public static void copyAction() {
		Result res = copy(Animation.selected, Timeline.time, AnimToolSupport.selectedGroups());
		if (!res.ok) {
			PlusDialogs.block(res.errorText());
			return;
		}
		int n = ((Double) res.extra.get("parts")).intValue();
		PlusDialogs.quick(t("cpmplus.w3animtools.pose.copied", n, fmt(clipTime)), 3000);
	}

	public static void pasteAction(boolean mirrored) {
		Result res = paste(Animation.selected, Timeline.time, AnimToolSupport.selectedGroups(), mirrored, true);
		if (!res.ok) {
			PlusDialogs.block(res.errorText());
			return;
		}
		if (!res.changed)PlusDialogs.quick(t("cpmplus.w3animtools.pose.nothing"), 3000);
		else PlusDialogs.quick(t(mirrored ? "cpmplus.w3animtools.pose.pastedMirror" : "cpmplus.w3animtools.pose.pasted", ((Double) res.extra.get("parts")).intValue(), fmt(Timeline.time)), 3000);
		if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
	}

	public static void mirrorAction() {
		Result res = mirrorPose(Animation.selected, Timeline.time, AnimToolSupport.selectedGroups(), true);
		if (!res.ok) {
			PlusDialogs.block(res.errorText());
			return;
		}
		if (!res.changed)PlusDialogs.quick(t("cpmplus.w3animtools.pose.nothing"), 3000);
		else PlusDialogs.quick(t("cpmplus.w3animtools.pose.mirrored", ((Double) res.extra.get("swapped")).intValue(), ((Double) res.extra.get("inPlace")).intValue()), 3000);
	}

	// ====================================================================== QA hook

	/** pose({op: copy | paste | pasteMirror | mirror, anim, time, parts: [path...], apply}) */
	public static Object hook(JsPropertyMap<Object> o) {
		String op = AnimToolSupport.str(o, "op", "copy");
		double time = AnimToolSupport.num(o, "time", Timeline.time);
		List<Group> sel = new ArrayList<>();
		for (String s : AnimToolSupport.list(o, "parts")) {
			Group g = AnimToolSupport.group(s);
			if (g != null)sel.add(g);
		}
		Animation a = AnimToolSupport.anim(o);
		boolean apply = AnimToolSupport.bool(o, "apply", true);
		switch (op) {
		case "paste":
			return paste(a, time, sel, false, apply).toJs();
		case "pasteMirror":
			return paste(a, time, sel, true, apply).toJs();
		case "mirror":
			return mirrorPose(a, time, sel, apply).toJs();
		case "clear":
			clip = null;
			clipRoot = null;
			return o;
		default:
			return copy(a, time, sel).toJs();
		}
	}
}
