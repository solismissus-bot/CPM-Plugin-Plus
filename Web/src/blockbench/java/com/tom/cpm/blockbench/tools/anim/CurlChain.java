package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
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
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Curvar corrente / abrir em leque': a POSE for whole bone chains at once (Fazer onda makes motion over time,
 * Pose intermediária works part by part).
 * <ul>
 * <li>Chains come from the selection: each selected group is the first bone, then its only child group at each level
 * (WaveChain.buildChain: rotation subgroups / item slots skipped; it stops where the chain splits). A selected group
 * that is already inside another selected chain is not a chain of its own.</li>
 * <li>Curvar / Dobrar pro lado / Torcer are TOTAL angles shared by the bones of a chain; 'Base -&gt; ponta' moves the
 * share towards the tip (+) or the base (-), keeping the total. Onda em S = one full sine along the chain (two opposite
 * curls of that size). Abrir em leque (2+ chains) turns the first bone of each chain sideways, spread evenly from -half
 * to +half in the order the chains sit side by side.</li>
 * <li>The axes come from the chain's rest direction (first pivot to last pivot, or to its blocks): along Z (tail) curl
 * = X, side = Y, twist = Z; along X (wing, arm) curl = Z, side = Y, twist = X; along Y (hair, neck) curl = X, side = Z,
 * twist = Y. Signs follow the direction, so mirrored chains (two wings) curl the same way: + curl lifts a chain that
 * lies flat and brings a hanging / standing one forward; + side goes to +X for front / back chains and to the back for
 * sideways ones.</li>
 * <li>The change is ADDED to the pose at the playhead (rotation only). 'Pose aqui' writes one key per bone at the
 * playhead (adjusted in place when a key is there); 'Animação até a pose' writes the current pose at the playhead and
 * the curled pose N frames later (rotation keys in between on those bones are removed; the animation grows when
 * needed).</li>
 * <li>Live preview while the dialog is open (removed before OK / Cancel); OK = one undo entry, keys on bones that had
 * no animator are tracked so Ctrl+Z / Ctrl+Y restore exactly (AnimxUtil.track). Keys with formulas (Molang) at those
 * times are skipped with a warning.</li>
 * </ul>
 */
public final class CurlChain {
	private CurlChain() {
	}

	static final String PREF = "cpmplus.curl.";

	public static final class Options {
		public Animation anim;
		public double time;
		public final List<List<Group>> chains = new ArrayList<>();
		public double curl, side, twist, swave, weight, fan;
		/** false = pose at the playhead, true = from the pose at the playhead to the curled pose 'frames' later */
		public boolean ab;
		public int frames = 10;
	}

	/** one key to create or adjust */
	static final class Plan {
		final Group g;
		final double t;
		final double[] v;

		Plan(Group g, double t, double[] v) {
			this.g = g;
			this.t = t;
			this.v = v;
		}
	}

	// ====================================================================== chains

	/** the chains of the selection (first bones in selection order, no chain inside another) */
	public static List<List<Group>> chainsOf(List<Group> sel) {
		List<List<Group>> out = new ArrayList<>();
		Set<Group> used = new LinkedHashSet<>();
		List<WaveChain.Chain> all = new ArrayList<>();
		for (Group g : sel)all.add(WaveChain.buildChain(g, Collections.emptyMap()));
		// a selected group that is a later bone of another selected chain belongs to that chain
		for (WaveChain.Chain c : all)for (int i = 1; i < c.bones.size(); i++)used.add(c.bones.get(i));
		for (WaveChain.Chain c : all) {
			Group first = c.bones.get(0);
			if (used.contains(first))continue;
			out.add(new ArrayList<>(c.bones));
			used.addAll(c.bones);
		}
		return out;
	}

	/** rest direction of a chain: first pivot to last pivot (or to the centre of the blocks of a one-bone chain) */
	static double[] direction(List<Group> bones) {
		Group a = bones.get(0);
		double[] d = new double[3];
		if (bones.size() > 1) {
			Group b = bones.get(bones.size() - 1);
			d[0] = b.origin.x - a.origin.x;
			d[1] = b.origin.y - a.origin.y;
			d[2] = b.origin.z - a.origin.z;
		}
		if (Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]) < 1e-6) {
			Group last = bones.get(bones.size() - 1);
			int n = 0;
			double[] c = new double[3];
			for (int i = 0; last.children != null && i < last.children.length; i++) {
				OutlinerNode o = last.children.getAt(i);
				if (!(o instanceof Cube))continue;
				Cube cu = (Cube) o;
				c[0] += (cu.from.x + cu.to.x) / 2;
				c[1] += (cu.from.y + cu.to.y) / 2;
				c[2] += (cu.from.z + cu.to.z) / 2;
				n++;
			}
			if (n > 0) {
				d[0] = c[0] / n - a.origin.x;
				d[1] = c[1] / n - a.origin.y;
				d[2] = c[2] / n - a.origin.z;
			}
		}
		if (Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]) < 1e-6)d[2] = 1;// no hint: like a tail
		return d;
	}

	/**
	 * {curl axis, side axis, twist axis, curl sign, side sign, twist sign} for a rest direction. A CPM project's bone
	 * keys turn the bone by +angle around +X / +Y / +Z (right hand, measured on Blockbench's own world positions): a
	 * chain along s*Z rises with -s around X and goes to +X with +s around Y; along s*X it rises with +s around Z and
	 * goes back (+Z) with -s around Y; along s*Y it comes forward (-Z) with -s around X and goes to +X with -s
	 * around Z.
	 */
	static int[] axes(double[] d) {
		double ax = Math.abs(d[0]), ay = Math.abs(d[1]), az = Math.abs(d[2]);
		if (az >= ax && az >= ay) {
			int s = d[2] >= 0 ? 1 : -1;
			return new int[] {0, 1, 2, -s, s, s};
		}
		if (ax >= ay) {
			int s = d[0] >= 0 ? 1 : -1;
			return new int[] {2, 1, 0, s, -s, s};
		}
		int s = d[1] >= 0 ? 1 : -1;
		return new int[] {0, 2, 1, -s, -s, s};
	}

	/** where a chain sits sideways (for the fan order): its first pivot along the axis the fan spreads on */
	static double sidePos(List<Group> c) {
		int[] ax = axes(direction(c));
		Group g = c.get(0);
		// the fan turns around the side axis: the chains spread along the remaining axis (not the chain's own)
		int spread = 3 - ax[1] - ax[2];
		return spread == 0 ? g.origin.x : spread == 1 ? g.origin.y : g.origin.z;
	}

	/** share of each bone (mean 1): 'weight' -100..100 moves it to the base (-) or the tip (+) */
	static double[] weights(int n, double weight) {
		double[] w = new double[n];
		double k = Math.max(-100, Math.min(100, weight)) / 100.0, sum = 0;
		for (int i = 0; i < n; i++) {
			w[i] = n == 1 ? 1 : Math.max(0, 1 + k * (2.0 * i / (n - 1) - 1));
			sum += w[i];
		}
		for (int i = 0; i < n; i++)w[i] = sum > 0 ? w[i] * n / sum : 1;
		return w;
	}

	/** the rotation change of every bone (degrees, per chain, per bone) */
	static List<List<double[]>> deltas(Options o) {
		List<List<double[]>> out = new ArrayList<>();
		int m = o.chains.size();
		// fan order: side by side
		List<Integer> order = new ArrayList<>();
		for (int j = 0; j < m; j++)order.add(j);
		Collections.sort(order, (x, y) -> Double.compare(sidePos(o.chains.get(x)), sidePos(o.chains.get(y))));
		double[] fanOf = new double[m];
		for (int r = 0; r < m; r++)fanOf[order.get(r)] = m < 2 ? 0 : o.fan * ((double) r / (m - 1) - 0.5);
		for (int j = 0; j < m; j++) {
			List<Group> c = o.chains.get(j);
			int n = c.size();
			int[] ax = axes(direction(c));
			double[] w = weights(n, o.weight);
			List<double[]> l = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				double[] d = new double[3];
				double s = n > 1 ? Math.sin(2 * Math.PI * (i + 0.5) / n) * Math.PI / n : 0;
				d[ax[0]] += ax[3] * (o.curl * w[i] / n + o.swave * s);
				d[ax[1]] += ax[4] * (o.side * w[i] / n + (i == 0 ? fanOf[j] : 0));
				d[ax[2]] += ax[5] * (o.twist * w[i] / n);
				l.add(d);
			}
			out.add(l);
		}
		return out;
	}

	static double snap(double t) {
		return Math.round(Math.max(0, t) * 1e6) / 1e6;
	}

	static double[] now(Animation a, Group g, double t) {
		BoneAnimator ba = AnimEdit.animatorOf(a, g);
		double[] v = ba != null && AnimxUtil.hasKeys(ba, KeyMath.ROT) ? AnimEdit.sample(ba, KeyMath.ROT, t) : null;
		if (v == null || Double.isNaN(v[0]) || Double.isNaN(v[1]) || Double.isNaN(v[2]))return new double[3];
		return new double[] {v[0], v[1], v[2]};
	}

	static boolean molangAt(Animation a, Group g, double t) {
		BoneAnimator ba = AnimEdit.animatorOf(a, g);
		if (ba == null)return false;
		for (KeyData k : AnimEdit.readKeys(ba, KeyMath.ROT))if (k.molang && Math.abs(k.time - t) < KeyMath.EPS)return true;
		return false;
	}

	static List<Plan> plan(Options o, Result r) {
		List<Plan> out = new ArrayList<>();
		double t = snap(o.time);
		double t2 = snap(t + Math.max(1, o.frames) / (double) Math.max(1, AnimEdit.snappingOf(o.anim)));
		List<List<double[]>> ds = deltas(o);
		int formulas = 0, bones = 0;
		for (int j = 0; j < o.chains.size(); j++) {
			List<Group> c = o.chains.get(j);
			for (int i = 0; i < c.size(); i++) {
				Group g = c.get(i);
				bones++;
				if (molangAt(o.anim, g, t) || o.ab && molangAt(o.anim, g, t2)) {
					formulas++;
					continue;
				}
				double[] cur = now(o.anim, g, t), d = ds.get(j).get(i);
				double[] v = {cur[0] + d[0], cur[1] + d[1], cur[2] + d[2]};
				if (o.ab) {
					out.add(new Plan(g, t, cur));
					out.add(new Plan(g, t2, v));
				} else out.add(new Plan(g, t, v));
			}
		}
		if (formulas > 0)r.warn("molang", t("cpmplus.w3animtools.warn.formulaSkipped", formulas));
		r.put("chains", (double) o.chains.size()).put("bones", (double) bones).put("keys", (double) out.size()).put("time", t);
		if (o.ab)r.put("time2", t2);
		return out;
	}

	private static Keyframe keyAt(BoneAnimator ba, double t) {
		JsArrayE<Keyframe> arr = ba != null ? ba.channel(KeyMath.ROT) : null;
		if (arr == null)return null;
		for (int i = 0; i < arr.length; i++)if (Math.abs(arr.getAt(i).time - t) < KeyMath.EPS)return arr.getAt(i);
		return null;
	}

	/** writes the plans (live != null: remembers how to take them back) */
	static void applyPlans(Options o, List<Plan> plans, Tween.Live live, List<Object[]> createdAnimators) {
		double t = snap(o.time), t2 = -1;
		for (Plan p : plans)if (p.t > t + KeyMath.EPS)t2 = p.t;
		if (o.ab && t2 > 0) {
			// a clean A -> B: rotation keys strictly between the two frames go (not in the live preview: it only adds)
			if (live == null)for (Plan p : plans) {
				BoneAnimator ba = AnimEdit.animatorOf(o.anim, p.g);
				if (ba == null)continue;
				List<Double> mid = new ArrayList<>();
				for (KeyData k : AnimEdit.readKeys(ba, KeyMath.ROT))if (k.time > t + KeyMath.EPS && k.time < t2 - KeyMath.EPS)mid.add(k.time);
				if (!mid.isEmpty())AnimEdit.removeNear(ba, KeyMath.ROT, mid, KeyMath.EPS);
			}
			if (t2 > o.anim.length + 1e-6 && live == null)o.anim.setLength(t2);
		}
		for (Plan p : plans) {
			BoneAnimator ba = AnimEdit.animatorOf(o.anim, p.g);
			if (ba == null) {
				ba = o.anim.getBoneAnimator(p.g);
				if (createdAnimators != null)createdAnimators.add(new Object[] {o.anim, p.g.uuid});
			}
			Keyframe at = keyAt(ba, p.t);
			if (at != null) {
				if (live != null)live.remember(at);
				int n = at.data_points != null ? Math.max(1, at.data_points.length) : 1;
				KeyData k = new KeyData();
				k.v = new double[n][];
				for (int i = 0; i < n; i++)k.v[i] = KeyMath.dup(p.v);
				Exaggerate.write(at, k);
			} else {
				AnimEdit.writeKeys(ba, KeyMath.ROT, Collections.singletonList(Tween.newKey(AnimEdit.readKeys(ba, KeyMath.ROT), p.t, p.v)), false);
				if (live != null)live.created(ba, KeyMath.ROT, p.t);
			}
		}
	}

	/**
	 * Ctrl+Z of an 'Animação até a pose' that made the animation longer, on a bone that had no animator yet: Blockbench
	 * puts the saved length back while that bone's new keys are still there (AnimxUtil drops its animator in the same
	 * load_undo_save round, after AnimEdit's length fix), so the length stayed long. Once every listener ran, the saved
	 * length is put back (setLength never goes below the last key).
	 */
	public static void installUndoLengthFix() {
		com.tom.cpm.blockbench.PluginStart.addEventListener("load_undo_save", e -> DomGlobal.setTimeout(a -> restoreLengths(e), 0));
	}

	private static native void restoreLengths(Object data) /*-{
		try {
			var save = data && data.save;
			if (!save || !save.animations || !$wnd.Animator) return;
			var list = $wnd.Animator.animations || [];
			for (var uuid in save.animations) {
				var d = save.animations[uuid];
				if (!d || typeof d.length !== 'number') continue;
				for (var i = 0; i < list.length; i++) {
					var a = list[i];
					if (a.uuid === uuid && Math.abs(a.length - d.length) > 1e-6 && typeof a.setLength === 'function') a.setLength(d.length);
				}
			}
		} catch (e) {
			console.warn('CPM+: could not restore the animation length after undo', e);
		}
	}-*/;

	/** takes animators made by the preview off the animation again (an empty animator would hide later undo) */
	static void dropAnimators(List<Object[]> created) {
		for (Object[] c : created)detach(c[0], (String) c[1]);
		created.clear();
	}

	/** drops an animator WITHOUT keys; true when it had a row in the timeline */
	private static native boolean detach(Object anim, String uuid) /*-{
		var ba = anim.animators && anim.animators[uuid];
		if (!ba) return false;
		if (ba.keyframes && ba.keyframes.length) return false;// keys there: not ours to drop
		delete anim.animators[uuid];
		var tl = $wnd.Timeline && $wnd.Timeline.animators;
		if (tl) { var k = tl.indexOf(ba); if (k >= 0) { tl.splice(k, 1); return true; } }
		return false;
	}-*/;

	public static Result run(Options o, boolean apply) {
		Result r = new Result();
		if (o.anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (o.chains.isEmpty())return r.error("noChain", t("cpmplus.w34proa.curl.err.noChain"));
		List<Plan> plans = plan(o, r);
		if (!apply || plans.isEmpty())return r;
		// Blockbench 5 gives every bone an empty animator when an animation is shown; its undo clears keys added to
		// those (checked), and AnimxUtil.track drops animators the edit had to create
		List<Animation> anims = Collections.singletonList(o.anim);
		PlusUndo.run(t("cpmplus.w34proa.curl.undo"), AnimxUtil.track(UndoData.make(new Animation[] {o.anim}), anims),
				() -> AnimxUtil.track(UndoData.make(new Animation[] {o.anim}), anims), () -> applyPlans(o, plans, null, null));
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	// ====================================================================== dialog

	private static final String SUMMARY_ID = "cpmplus_w34proa_curl_summary";

	static Options fromForm(Animation anim, double time, FormResult fr, List<List<Group>> chains) {
		Options o = new Options();
		o.anim = anim;
		o.time = time;
		o.chains.addAll(chains);
		o.curl = fr.getDouble("curl", 0);
		o.side = fr.getDouble("side", 0);
		o.fan = chains.size() > 1 ? fr.getDouble("fan", 0) : 0;
		boolean more = fr.getBool("more");
		o.twist = more ? fr.getDouble("twist", 0) : 0;
		o.swave = more ? fr.getDouble("swave", 0) : 0;
		o.weight = more ? fr.getDouble("weight", 0) : 0;
		o.ab = "ab".equals(fr.getString("mode"));
		o.frames = (int) Math.round(fr.getDouble("frames", 10));
		return o;
	}

	/** CPM+ &gt; Animação &gt; 'Curvar corrente / abrir em leque...' and the group / timeline row menus */
	public static void openDialog() {
		Animation anim = Animation.selected;
		if (anim == null || !AnimToolSupport.animateMode()) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		List<List<Group>> chains = chainsOf(AnimToolSupport.selectedGroups());
		if (chains.isEmpty()) {
			PlusDialogs.block(t("cpmplus.w34proa.curl.err.noChain"));
			return;
		}
		double time = snap(Timeline.time);
		Tween.Live live = new Tween.Live();
		List<Object[]> made = new ArrayList<>();
		StringBuilder names = new StringBuilder();
		for (int j = 0; j < chains.size() && j < 6; j++)names.append(j > 0 ? ", " : "").append(t("cpmplus.w34proa.curl.chainName", chains.get(j).get(0).name, chains.get(j).size()));
		if (chains.size() > 6)names.append(", …");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w34proa.curl.intro", chains.size(), names.toString(), PoseColumn.fmt(time))));
		rows.add(FormRow.range("curl", "cpmplus.w34proa.curl.curl", 0, -360, 360, 5).description("cpmplus.w34proa.curl.curl.desc"));
		rows.add(FormRow.range("side", "cpmplus.w34proa.curl.side", 0, -180, 180, 5).description("cpmplus.w34proa.curl.side.desc"));
		if (chains.size() > 1)rows.add(FormRow.range("fan", "cpmplus.w34proa.curl.fan", 0, -150, 150, 5).description("cpmplus.w34proa.curl.fan.desc"));
		rows.add(FormRow.radio("mode", "cpmplus.w34proa.curl.mode", PlusDialogs.options(
				"pose", "cpmplus.w34proa.curl.mode.pose",
				"ab", "cpmplus.w34proa.curl.mode.ab"), "ab".equals(PlusPrefs.get(PREF + "mode", "pose")) ? "ab" : "pose"));
		rows.add(FormRow.number("frames", "cpmplus.w34proa.curl.frames", (int) Math.max(1, Math.min(200, PlusPrefs.getNum(PREF + "frames", 10))), 1, 200)
				.description("cpmplus.w34proa.curl.frames.desc").when(fr -> "ab".equals(fr.getString("mode"))));
		rows.add(FormRow.checkbox("more", "cpmplus.w34proa.curl.more", false));
		rows.add(FormRow.range("twist", "cpmplus.w34proa.curl.twist", 0, -180, 180, 5).description("cpmplus.w34proa.curl.twist.desc").when(fr -> fr.getBool("more")));
		rows.add(FormRow.range("swave", "cpmplus.w34proa.curl.swave", 0, -180, 180, 5).description("cpmplus.w34proa.curl.swave.desc").when(fr -> fr.getBool("more")));
		rows.add(FormRow.range("weight", "cpmplus.w34proa.curl.weight", 0, -100, 100, 10).description("cpmplus.w34proa.curl.weight.desc").when(fr -> fr.getBool("more")));
		rows.add(FormRow.checkbox("preview", "cpmplus.w3animtools.tween.preview", true));
		rows.add(FormRow.htmlInfo("summary", null, "<span id='" + SUMMARY_ID + "'></span>"));
		FormOpts fo = new FormOpts();
		fo.width = 560;
		fo.confirmKey = "cpmplus.w34proa.curl.ok";
		fo.onCancel = () -> {
			live.restore();
			dropAnimators(made);
		};
		fo.onChange = fr -> {
			live.restore();
			dropAnimators(made);
			Options o = fromForm(anim, time, fr, chains);
			Result r = new Result();
			List<Plan> plans = plan(o, r);
			boolean any = o.curl != 0 || o.side != 0 || o.fan != 0 || o.twist != 0 || o.swave != 0;
			if (fr.getBool("preview") && any && !plans.isEmpty()) {
				// the preview shows the curled pose at the playhead (A -> B: its end pose)
				Options po = fromForm(anim, time, fr, chains);
				po.ab = false;
				applyPlans(po, plan(po, new Result()), live, made);
				AnimToolSupport.refreshPreview();
			}
			setSummary(r, o);
		};
		PlusDialogs.form("cpmplus_w34proa_curl", "cpmplus.w34proa.curl.title", rows, fr -> {
			live.restore();// the preview never reaches the undo history
			dropAnimators(made);
			Options o = fromForm(anim, time, fr, chains);
			PlusPrefs.set(PREF + "mode", o.ab ? "ab" : "pose");
			PlusPrefs.set(PREF + "frames", (double) o.frames);
			if (o.curl == 0 && o.side == 0 && o.fan == 0 && o.twist == 0 && o.swave == 0) {
				PlusDialogs.quick(t("cpmplus.w34proa.curl.nothing"), 4000);
				return;
			}
			Result res = run(o, true);
			if (!res.ok) {
				PlusDialogs.block(res.errorText());
				return;
			}
			PlusDialogs.done(t("cpmplus.w34proa.curl.done", ((Double) res.extra.get("bones")).intValue(), o.chains.size()));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	private static void setSummary(Result r, Options o) {
		Element el = DomGlobal.document.getElementById(SUMMARY_ID);
		if (el == null)return;
		Object k = r.extra.get("keys"), b = r.extra.get("bones");
		StringBuilder sb = new StringBuilder(t(o.ab ? "cpmplus.w34proa.curl.summaryAb" : "cpmplus.w34proa.curl.summary",
				b != null ? ((Double) b).intValue() : 0, k != null ? ((Double) k).intValue() : 0));
		for (String w : r.warnings)sb.append(" ").append(w);
		el.textContent = sb.toString();
	}

	// ====================================================================== QA hook

	/** curl({anim, time, chains: [first bone paths] | 'selected', curl, side, twist, swave, weight, fan, mode, frames, apply, previewTest}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Options o = new Options();
		o.anim = AnimToolSupport.anim(opts);
		o.time = AnimToolSupport.num(opts, "time", Timeline.time);
		List<Group> firsts = new ArrayList<>();
		if ("selected".equals(opts.get("chains")))firsts.addAll(AnimToolSupport.selectedGroups());
		else for (String s : AnimToolSupport.list(opts, "chains")) {
			Group g = AnimToolSupport.group(s);
			if (g != null)firsts.add(g);
		}
		o.chains.addAll(chainsOf(firsts));
		o.curl = AnimToolSupport.num(opts, "curl", 0);
		o.side = AnimToolSupport.num(opts, "side", 0);
		o.twist = AnimToolSupport.num(opts, "twist", 0);
		o.swave = AnimToolSupport.num(opts, "swave", 0);
		o.weight = AnimToolSupport.num(opts, "weight", 0);
		o.fan = AnimToolSupport.num(opts, "fan", 0);
		o.ab = "ab".equals(AnimToolSupport.str(opts, "mode", "pose"));
		o.frames = (int) AnimToolSupport.num(opts, "frames", 10);
		if (AnimToolSupport.bool(opts, "previewTest", false) && o.anim != null) {
			Result r = new Result();
			Tween.Live live = new Tween.Live();
			List<Object[]> made = new ArrayList<>();
			Options po = o;
			applyPlans(po, plan(po, r), live, made);
			r.put("previewActive", live.active());
			r.put("previewAnimators", (double) made.size());
			live.restore();
			dropAnimators(made);
			return r.toJs();
		}
		Result r = run(o, AnimToolSupport.bool(opts, "apply", true));
		JsArray<Object> cs = new JsArray<>();
		for (List<Group> c : o.chains) {
			JsArray<Object> names = new JsArray<>();
			for (Group g : c)names.push(g.name);
			cs.push(names);
		}
		JsPropertyMap<Object> js = r.toJs();
		js.set("chainBones", cs);
		return js;
	}
}
