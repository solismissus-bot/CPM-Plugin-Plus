package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (D1): animation optimizer. The CPM export writes one frame per point of the smallest uniform time grid that
 * holds every keyframe, so 'baked' animations (a key on every frame, e.g. imported from CPM or other tools) carry
 * hundreds of frames for every animated part. This finds the smallest grid M whose linear re-sampling reproduces the
 * motion within a tolerance (rotation degrees, position pixels, scale) and rewrites the keys on it:
 * <ul>
 * <li>Only linear transform tracks are re-sampled (in game LINEAR interpolates linearly between frames, so the check
 * is exact: both curves are piecewise linear, the largest difference is at a key of the original). Smooth, bezier,
 * step or formula tracks keep the animation as it is (reported).</li>
 * <li>Visibility and colour keys are never moved: the new grid must hold each of their times.</li>
 * <li>Channels whose keys are all equal become one key; in additive animations a part whose every channel stays at
 * rest (0 / 0 / 1, no visibility or colour) loses its keys (it changes nothing in game).</li>
 * <li>The stored CPM grid (cpm_frames) follows the new grid, the interpolation type stays. One undo entry.</li>
 * </ul>
 */
public final class AnimOptimizer {
	public static final String[] TRANSFORM = {AnimxUtil.ROT, AnimxUtil.POS, AnimxUtil.SCL};
	private static final double TIME_EPS = 1e-4;

	private AnimOptimizer() {
	}

	public static final class Opts {
		public double rotTol = 0.5, posTol = 0.05, sclTol = 0.005;
	}

	/** one rewritten channel: the new key times / values (null values = keep the keys, only collapse or drop) */
	static final class Track {
		BoneAnimator ba;
		String ch;
		String interp;
		double[] times;
		double[][] values;
	}

	public static final class Plan {
		public Animation anim;
		public int framesBefore, framesAfter, keysBefore, keysAfter;
		/** null = optimized; otherwise a lang key saying why the frames could not be reduced */
		public String note;
		int newGrid;
		double len;
		final List<Track> tracks = new ArrayList<>();
		final List<BoneAnimator> drop = new ArrayList<>();

		public boolean changes() {
			return !tracks.isEmpty() || !drop.isEmpty() || (newGrid > 0 && framesAfter < framesBefore);
		}
	}

	// ------------------------------------------------------------------ analysis

	private static double tol(Opts o, String ch) {
		return AnimxUtil.ROT.equals(ch) ? o.rotTol : AnimxUtil.POS.equals(ch) ? o.posTol : o.sclTol;
	}

	private static double[] val(Keyframe kf) {
		return new double[] {AnimxUtil.value(kf, 0, 0), AnimxUtil.value(kf, 0, 1), AnimxUtil.value(kf, 0, 2)};
	}

	private static boolean same(double[] a, double[] b, double eps) {
		for (int i = 0; i < 3; i++)if (!(Math.abs(a[i] - b[i]) <= eps))return false;
		return true;
	}

	/** linear interpolation of (t[], v[][]) at time x, holding the first / last value outside */
	static double[] lerp(double[] t, double[][] v, double x) {
		int n = t.length;
		if (x <= t[0])return v[0];
		if (x >= t[n - 1])return v[n - 1];
		int lo = 0, hi = n - 1;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (t[mid] <= x)lo = mid;
			else hi = mid;
		}
		double span = t[hi] - t[lo];
		double f = span <= 0 ? 0 : (x - t[lo]) / span;
		double[] r = new double[3];
		for (int i = 0; i < 3; i++)r[i] = v[lo][i] + (v[hi][i] - v[lo][i]) * f;
		return r;
	}

	private static boolean onGrid(double t, double h) {
		double k = t / h;
		return Math.abs(k - Math.rint(k)) * h <= TIME_EPS;
	}

	private static int keyCount(Animation a) {
		int n = 0;
		for (BoneAnimator ba : AnimxUtil.boneAnimators(a))for (String ch : AnimxUtil.ALL)n += AnimxUtil.count(ba, ch);
		return n;
	}

	/** source track of a linear numeric channel: times / values, or null when it cannot be re-sampled */
	private static final class Src {
		double[] t;
		double[][] v;
		String interp;
		double tol;
	}

	public static Plan analyse(Animation a, Opts o) {
		Plan p = new Plan();
		p.anim = a;
		p.keysBefore = keyCount(a);
		AnimEdit.Frames fb = AnimEdit.predictFrames(a);
		p.framesBefore = fb.frames;
		p.framesAfter = fb.frames;
		p.keysAfter = p.keysBefore;

		List<Src> lin = new ArrayList<>();
		Map<Src, BoneAnimator> linBa = new LinkedHashMap<>();
		Map<Src, String> linCh = new LinkedHashMap<>();
		List<Double> fixed = new ArrayList<>();// visibility / colour key times (must stay on the grid)
		double maxT = 0;
		boolean blocked = false;
		int removed = 0;

		for (BoneAnimator ba : AnimxUtil.boneAnimators(a)) {
			boolean anyKey = false, neutral = a.additive;
			for (String ch : new String[] {CPMCodec.VISIBILITY, CPMCodec.COLOR}) {
				for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(ba, ch))) {
					fixed.add((double) kf.time);
					maxT = Math.max(maxT, kf.time);
					anyKey = true;
					neutral = false;
				}
			}
			List<Track> collapses = new ArrayList<>();
			for (String ch : TRANSFORM) {
				List<Keyframe> ks = AnimxUtil.sorted(AnimxUtil.keys(ba, ch));
				if (ks.isEmpty())continue;
				anyKey = true;
				for (Keyframe kf : ks)maxT = Math.max(maxT, kf.time);
				boolean numeric = true;
				for (Keyframe kf : ks)if (!AnimxUtil.isNumeric(kf) || AnimxUtil.dataPoints(kf) != 1)numeric = false;
				if (!numeric) {
					blocked = true;
					neutral = false;
					for (Keyframe kf : ks)fixed.add((double) kf.time);
					continue;
				}
				double[] first = val(ks.get(0));
				boolean constant = true;
				for (Keyframe kf : ks)if (!same(val(kf), first, 1e-9))constant = false;
				if (!(constant && same(first, AnimxUtil.rest(ch), 1e-9)))neutral = false;
				if (constant) {
					if (ks.size() > 1 || !onGrid0(ks.get(0).time)) {
						Track tr = new Track();
						tr.ba = ba;
						tr.ch = ch;
						tr.interp = ks.get(0).interpolation;
						tr.times = new double[] {0};
						tr.values = new double[][] {first};
						collapses.add(tr);
						removed += ks.size() - 1;
					}
					continue;
				}
				boolean linear = true;
				for (int i = 0; i < ks.size() - 1; i++)if (!"linear".equals(ks.get(i).interpolation))linear = false;
				if (!linear) {
					blocked = true;
					for (Keyframe kf : ks)fixed.add((double) kf.time);
					continue;
				}
				Src s = new Src();
				s.t = new double[ks.size()];
				s.v = new double[ks.size()][];
				for (int i = 0; i < ks.size(); i++) {
					s.t[i] = ks.get(i).time;
					s.v[i] = val(ks.get(i));
				}
				s.interp = "linear";
				s.tol = tol(o, ch);
				lin.add(s);
				linBa.put(s, ba);
				linCh.put(s, ch);
			}
			if (anyKey && neutral) {
				p.drop.add(ba);
				continue;
			}
			p.tracks.addAll(collapses);
		}
		for (BoneAnimator ba : p.drop)for (String ch : AnimxUtil.ALL)removed += AnimxUtil.count(ba, ch);

		double len = Math.max(a.length, maxT);
		p.len = len;
		if (lin.isEmpty() || len < 1e-3) {
			p.keysAfter = p.keysBefore - removed;
			if (blocked)p.note = "cpmplus.optim.note.smooth";
			return p;
		}

		// smallest grid M (< the current frame count) that holds the fixed times and reproduces every linear track
		int cur = Math.max(1, p.framesBefore);
		int best = -1;
		List<double[][]> bestVals = null;
		for (int m = 1; m < cur; m++) {
			double h = len / m;
			boolean ok = true;
			for (double ft : fixed)if (!onGrid(ft, h)) {
				ok = false;
				break;
			}
			if (!ok)continue;
			double[] g = new double[m + 1];
			for (int k = 0; k <= m; k++)g[k] = k * h;
			List<double[][]> vals = new ArrayList<>();
			for (Src s : lin) {
				double[][] gv = new double[m + 1][];
				for (int k = 0; k <= m; k++)gv[k] = lerp(s.t, s.v, g[k]);
				for (int i = 0; i < s.t.length && ok; i++)if (!same(lerp(g, gv, s.t[i]), s.v[i], s.tol))ok = false;
				if (!ok)break;
				vals.add(gv);
			}
			if (ok) {
				best = m;
				bestVals = vals;
				break;
			}
		}
		if (best < 0) {
			p.keysAfter = p.keysBefore - removed;
			p.note = blocked ? "cpmplus.optim.note.smooth" : "cpmplus.optim.note.tight";
			return p;
		}
		p.newGrid = best;
		double h = len / best;
		int added = 0, dropped = 0;
		for (int i = 0; i < lin.size(); i++) {
			Src s = lin.get(i);
			double[][] gv = bestVals.get(i);
			// keep only the grid points that are not on the straight line between their neighbours
			List<Double> tt = new ArrayList<>();
			List<double[]> vv = new ArrayList<>();
			for (int k = 0; k <= best; k++) {
				// a point on the straight line from the last kept point to the next grid point adds nothing
				if (k > 0 && k < best && collinear(tt.get(tt.size() - 1), vv.get(vv.size() - 1), k * h, gv[k], (k + 1) * h, gv[k + 1]))continue;
				tt.add(k * h);
				vv.add(gv[k]);
			}
			Track tr = new Track();
			tr.ba = linBa.get(s);
			tr.ch = linCh.get(s);
			tr.interp = s.interp;
			tr.times = new double[tt.size()];
			tr.values = new double[tt.size()][];
			for (int k = 0; k < tt.size(); k++) {
				tr.times[k] = tt.get(k);
				tr.values[k] = vv.get(k);
			}
			p.tracks.add(tr);
			dropped += s.t.length;
			added += tt.size();
		}
		p.keysAfter = p.keysBefore - removed - dropped + added;
		InterpolatorType st = a.getCpmIntType();
		boolean remap = st == InterpolatorType.LINEAR_SINGLE || st == InterpolatorType.TRIG_SINGLE;
		p.framesAfter = Math.min(p.framesBefore, remap || st == null ? best + 1 : best);
		if (blocked)p.note = "cpmplus.optim.note.partial";
		return p;
	}

	private static boolean onGrid0(double t) {
		return Math.abs(t) <= TIME_EPS;
	}

	private static boolean collinear(double t0, double[] v0, double t1, double[] v1, double t2, double[] v2) {
		if (t2 - t0 <= 0)return false;
		double f = (t1 - t0) / (t2 - t0);
		for (int i = 0; i < 3; i++)if (Math.abs(v0[i] + (v2[i] - v0[i]) * f - v1[i]) > 1e-6)return false;
		return true;
	}

	// ------------------------------------------------------------------ apply (one undo entry)

	public static int apply(List<Plan> plans) {
		List<Animation> anims = new ArrayList<>();
		for (Plan p : plans)if (p.changes())anims.add(p.anim);
		if (anims.isEmpty())return 0;
		UndoData before = AnimxUtil.track(UndoData.make(anims.toArray(new Animation[0])), anims);
		PlusUndo.run(PlusDialogs.tr("cpmplus.optim.undo"), before, () -> AnimxUtil.track(UndoData.make(anims.toArray(new Animation[0])), anims), () -> {
			for (Plan p : plans) {
				if (!p.changes())continue;
				for (BoneAnimator ba : p.drop)for (String ch : AnimxUtil.ALL)removeAll(ba, ch);
				for (Track tr : p.tracks) {
					removeAll(tr.ba, tr.ch);
					for (int k = 0; k < tr.times.length; k++) {
						double[] v = tr.values[k];
						AnimxUtil.addKey(tr.ba, AnimxUtil.keyData(tr.ch, tr.times[k], tr.interp != null ? tr.interp : "linear", v[0], v[1], v[2]));
					}
				}
				InterpolatorType st = p.anim.getCpmIntType();
				if (p.newGrid > 0 && st != null && p.anim.getCpmFrames() > 0) {
					boolean remap = st == InterpolatorType.LINEAR_SINGLE || st == InterpolatorType.TRIG_SINGLE;
					p.anim.setCpmFrames(remap ? p.newGrid + 1 : p.newGrid);
				}
			}
		});
		AnimxUtil.preview();
		return anims.size();
	}

	private static void removeAll(BoneAnimator ba, String ch) {
		for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(ba, ch)))kf.remove();
	}

	// ------------------------------------------------------------------ UI

	public static void open() {
		Map<String, String> scope = new LinkedHashMap<>();
		scope.put("sel", PlusDialogs.tr("cpmplus.optim.scope.sel"));
		scope.put("all", PlusDialogs.tr("cpmplus.optim.scope.all"));
		Opts d = new Opts();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.tr("cpmplus.optim.intro")));
		rows.add(FormRow.radio("scope", "cpmplus.optim.scope", scope, Animation.selected != null ? "sel" : "all"));
		rows.add(FormRow.decimal("rot", "cpmplus.optim.rot", d.rotTol, 0, 10, 0.1));
		rows.add(FormRow.decimal("pos", "cpmplus.optim.pos", d.posTol, 0, 2, 0.01));
		rows.add(FormRow.decimal("scl", "cpmplus.optim.scl", d.sclTol, 0, 0.5, 0.001));
		PlusDialogs.form("cpmplus_optim", "cpmplus.optim.title", rows, fr -> {
			Opts o = new Opts();
			o.rotTol = fr.getDouble("rot", d.rotTol);
			o.posTol = fr.getDouble("pos", d.posTol);
			o.sclTol = fr.getDouble("scl", d.sclTol);
			List<Animation> list = new ArrayList<>();
			if ("sel".equals(fr.getString("scope")) && Animation.selected != null)list.add(Animation.selected);
			else list.addAll(AnimxUtil.animations());
			review(list, o);
		});
	}

	private static void review(List<Animation> list, Opts o) {
		List<Plan> plans = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		int changing = 0;
		for (Animation a : list) {
			Plan p = analyse(a, o);
			plans.add(p);
			sb.append(PlusDialogs.trf("cpmplus.optim.row", a.name, p.framesBefore, p.framesAfter, p.keysBefore, p.keysAfter));
			if (p.note != null)sb.append(" — ").append(PlusDialogs.tr(p.note));
			sb.append('\n');
			if (p.changes())changing++;
		}
		if (changing == 0) {
			PlusDialogs.messageBox("cpmplus.optim.title", sb + "\n" + PlusDialogs.tr("cpmplus.optim.nothing"), new String[] {"cpmplus.w0binfra.btn.ok"}, i -> {
			});
			return;
		}
		PlusDialogs.messageBox("cpmplus.optim.title", sb.toString(), new String[] {"cpmplus.optim.apply", "cpmplus.w0binfra.btn.cancel"}, i -> {
			if (i == 0) {
				int n = apply(plans);
				PlusDialogs.quick(PlusDialogs.trf("cpmplus.optim.done", n), 5000);
			}
		});
	}

	// ------------------------------------------------------------------ test hook

	public static Object toolJson(Object opts) {
		JsPropertyMap<Object> m = opts == null ? AnimxUtil.map() : jsinterop.base.Js.asPropertyMap(opts);
		Opts o = new Opts();
		if (m.get("rot") != null)o.rotTol = jsinterop.base.Js.asDouble(m.get("rot"));
		if (m.get("pos") != null)o.posTol = jsinterop.base.Js.asDouble(m.get("pos"));
		if (m.get("scl") != null)o.sclTol = jsinterop.base.Js.asDouble(m.get("scl"));
		List<Animation> list = new ArrayList<>();
		Object an = m.get("anim");
		if (an != null) {
			Animation a = AnimxUtil.find(String.valueOf(an));
			if (a == null)throw new IllegalArgumentException("no animation " + an);
			list.add(a);
		} else list.addAll(AnimxUtil.animations());
		List<Plan> plans = new ArrayList<>();
		JsArray<Object> rows = new JsArray<>();
		for (Animation a : list) {
			Plan p = analyse(a, o);
			plans.add(p);
			JsPropertyMap<Object> row = AnimxUtil.map();
			row.set("anim", a.name);
			row.set("framesBefore", p.framesBefore);
			row.set("framesAfter", p.framesAfter);
			row.set("keysBefore", p.keysBefore);
			row.set("keysAfter", p.keysAfter);
			row.set("note", p.note);
			row.set("dropped", p.drop.size());
			row.set("grid", p.newGrid);
			rows.push(row);
		}
		int applied = jsinterop.base.Js.isTruthy(m.get("apply")) ? apply(plans) : 0;
		JsPropertyMap<Object> r = AnimxUtil.map();
		r.set("rows", rows);
		r.set("applied", applied);
		if (applied > 0) {
			JsArray<Object> after = new JsArray<>();
			for (Animation a : list) {
				JsPropertyMap<Object> x = AnimxUtil.map();
				x.set("anim", a.name);
				x.set("frames", AnimEdit.predictFrames(a).frames);
				x.set("keys", keyCount(a));
				after.push(x);
			}
			r.set("after", after);
		}
		return r;
	}
}
