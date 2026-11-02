package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.editor.anim.EditorAnim;

import elemental2.promise.Promise;

/**
 * CPM+ (W3-ANIMGEN, G24): speed guard of continuous spins. CPM turns a part in a straight line between two frames
 * and always the short way: a step close to 180 degrees can turn backwards in game (the angle is stored as a signed
 * short and truncated). A rotation channel is a spin when its value at L differs from t=0 by +-360*k (within 0.5
 * degree); when two exported frames of a spin are more than {@link MotionPresets#SPIN_MAX_STEP} degrees apart,
 * the dialog of 'Girar sem parar' and the export (this ExportCheck, also for spins made by hand) warn, and
 * 'Corrigir' raises the animation's snapping (one undo entry) to the smallest value that AnimEdit allows (export
 * &lt;= 255 frames) and that brings the step to 120 degrees or less; the duration never changes.
 */
public final class SpinGuard {
	public static final String WARN_KEY = "spinTooFast";

	private SpinGuard() {
	}

	/** one spinning part that turns too fast */
	public static final class Finding {
		public final Animation animation;
		public final Group group;
		public final String partName;
		public final int turns;
		public final double step;
		/** snapping 'Corrigir' sets, -1 = none fits */
		public final int fix;

		Finding(Animation animation, Group group, String partName, int turns, double step, int fix) {
			this.animation = animation;
			this.group = group;
			this.partName = partName;
			this.turns = turns;
			this.step = step;
			this.fix = fix;
		}
	}

	/** animation length the export uses (the last key when it is past the set length) */
	static double exportLength(Animation a) {
		double len = a.length > 0 ? a.length : 0;
		for (BoneAnimator ba : AnimGenBB.boneAnimators(a)) {
			for (String ch : new String[] {"position", "rotation", "scale", "cpm_color", "cpm_visibility"}) {
				Object arr = ba.channel(ch);
				if (arr == null)continue;
				elemental2.core.JsArray<com.tom.cpm.blockbench.proxy.Keyframe> kf = jsinterop.base.Js.uncheckedCast(arr);
				for (int i = 0; i < kf.length; i++)len = Math.max(len, kf.getAt(i).time);
			}
		}
		return len;
	}

	private static double axis(JsVec3 v, int a) {
		if (v == null)return Double.NaN;
		return a == 0 ? v.x : a == 1 ? v.y : v.z;
	}

	/**
	 * the spins of one exported animation that turn more than 120 degrees per exported frame: frames n and the CPM
	 * interpolator of the export (frame k at k*D/(n-1) for LINEAR_SINGLE / TRIG_SINGLE, k*D/n otherwise)
	 */
	public static List<Finding> check(Animation a, int n, InterpolatorType it, int durationMs) {
		List<Finding> out = new ArrayList<>();
		if (a == null || a.animators == null || n < 2)return out;
		double len = exportLength(a);
		if (!(len > 0))return out;
		boolean remap = it == InterpolatorType.LINEAR_SINGLE || it == InterpolatorType.TRIG_SINGLE;
		double h = (durationMs / 1000.0) / (remap ? n - 1 : n);
		for (BoneAnimator ba : AnimGenBB.boneAnimators(a)) {
			if (ba.rotation == null || ba.rotation.length < 2 || ba.isMuted("rotation"))continue;
			JsVec3 v0 = Animation.interpolate(ba.rotation, 0), vl = Animation.interpolate(ba.rotation, (float) len);
			int turns = 0;
			boolean[] spinAxis = new boolean[3];
			for (int ax = 0; ax < 3; ax++) {
				int k = MotionPresets.spinTurns(axis(vl, ax) - axis(v0, ax));
				if (k > 0) {
					spinAxis[ax] = true;
					turns = Math.max(turns, k);
				}
			}
			if (turns == 0)continue;
			double max = 0;
			JsVec3 prev = v0;
			for (int k = 1; k < n; k++) {
				double t = Math.min(len, k * h);
				JsVec3 cur = Animation.interpolate(ba.rotation, (float) t);
				for (int ax = 0; ax < 3; ax++)if (spinAxis[ax])max = Math.max(max, Math.abs(axis(cur, ax) - axis(prev, ax)));
				prev = cur;
			}
			if (max > MotionPresets.SPIN_MAX_STEP + 1e-6) {
				Group g = ba.getGroup();
				String nm = g != null ? g.name : ba.rawName;
				out.add(new Finding(a, g, nm, turns, max, fixFor(a, len, turns)));
			}
		}
		return out;
	}

	/** snapping 'Corrigir' sets for this animation (AnimEdit's allowed values, step &lt;= 120, frames &lt;= 255), -1 = none */
	public static int fixFor(Animation a, double len, int turns) {
		List<Integer> allowed = AnimGenBB.allowedSnappings(a);
		List<Integer> ok = new ArrayList<>();
		for (Integer s : allowed)if (s != null && AnimGenBB.framesOk(a, s, len))ok.add(s);
		return MotionPresets.pickSnapping(len, turns, a.snapping > 0 ? a.snapping : 20, ok);
	}

	/** 'Corrigir': snapping of the animation = s (one undo entry; the duration does not change) */
	public static boolean applyFix(Animation a, int s) {
		if (a == null || s < 1)return false;
		List<Animation> l = new ArrayList<>();
		l.add(a);
		PlusUndo.editAnimations(l, AnimGenBB.tr("cpmplus.w3animgen.spin.fix.undo"), () -> a.snapping = s);
		return true;
	}

	/** ExportCheck: warns about every too fast spin of the exported animations */
	public static void check(ExportContext ctx) {
		if (Animation.all == null)return;
		for (Animation a : Animation.all) {
			if (a == null)continue;
			EditorAnim ea = ctx.export.getExportedAnim(a.uuid);
			if (ea == null || ea.getFrames() == null)continue;
			List<Finding> f = check(a, ea.getFrames().size(), ea.intType, ea.duration);
			for (Finding x : f) {
				WarnEntry w;
				if (x.fix > 0) {
					w = WarnFactory.of(Severity.DIFFERS, WARN_KEY, "cpmplus.w3animgen.spin.tooFast", x.partName, a.name, AnimGenBB.num(x.step), String.valueOf(x.fix));
					final int s = x.fix;
					w.setFix(() -> new Promise<>((res, rej) -> {
						try {
							res.onInvoke(applyFix(a, s));
						} catch (Throwable e) {
							rej.onInvoke(e);
						}
					}), FixKind.DETERMINISTIC);
					w.setFixPreview(AnimGenBB.trf("cpmplus.w3animgen.spin.fix.preview", String.valueOf(s)));
				} else {
					w = WarnFactory.of(Severity.DIFFERS, WARN_KEY, "cpmplus.w3animgen.spin.tooFastNoFix", x.partName, a.name, AnimGenBB.num(x.step));
				}
				w.setConsequence("cpmplus.w3animgen.spin.consequence");
				if (x.group != null)w.addNode(x.group.uuid, x.partName);
				ctx.add(w);
			}
		}
	}

	/** dialog-side text of a too fast spin ('A peça X gira rápido demais...'), null when the speed is fine */
	public static String dialogWarning(String part, double step, int fix) {
		if (step <= MotionPresets.SPIN_MAX_STEP + 1e-9)return null;
		return fix > 0 ? AnimGenBB.trf("cpmplus.w3animgen.spin.tooFastDialog", part, AnimGenBB.num(step), String.valueOf(fix))
				: AnimGenBB.trf("cpmplus.w3animgen.spin.tooFastDialogNoFix", part, AnimGenBB.num(step));
	}

	static String esc(String s) {
		return PlusDialogs.esc(s);
	}
}
