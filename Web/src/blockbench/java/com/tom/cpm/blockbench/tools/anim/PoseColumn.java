package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ F23 'Marcar pose aqui': one keyframe column at the playhead for the whole animation, so the pose shown now is
 * kept while other parts change. Rotation / position / scale of every animated part that already moves in that channel
 * ('incluir canais vazios': also the empty ones of animated parts, with the rest value); colour only when asked and
 * only on parts whose colour reaches the game (recolor / colour cubes) and already change colour; visibility never (a
 * key by accident would hide the part). Values = Blockbench's own interpolation at that time, so the pose does not
 * change; a key already there (|dt| &lt; 1e-5) is kept. Every animator of the animation (also the ones the timeline
 * does not show), ONE undo entry (Blockbench's keyframe_column_create only sees the timeline's parts and has no
 * colour).
 */
public final class PoseColumn {
	private PoseColumn() {
	}

	private static final class Plan {
		final BoneAnimator ba;
		final String ch;
		final KeyData key;

		Plan(BoneAnimator ba, String ch, KeyData key) {
			this.ba = ba;
			this.ch = ch;
			this.key = key;
		}
	}

	public static Result run(Animation anim, double time, boolean includeEmpty, boolean color, boolean apply) {
		Result r = new Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		double L = anim.length;
		if (time < -KeyMath.EPS || time > L + KeyMath.EPS)return r.error("outside", t("cpmplus.w3animtools.f23.outside"));
		double tt = Math.max(0, Math.min(L, time));
		double t = Math.round(tt * 1e6) / 1e6;
		List<Plan> plans = new ArrayList<>();
		int kept = 0, formulas = 0;
		List<Object> parts = new ArrayList<>();
		for (BoneAnimator ba : AnimEdit.boneAnimators(anim)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null || !ba.hasTransform())continue;
			List<String> chans = new ArrayList<>();
			Collections.addAll(chans, KeyMath.ROT, KeyMath.POS, KeyMath.SCALE);
			if (color && BlockbenchExport.isColourTarget(g))chans.add(KeyMath.COLOR);
			boolean any = false;
			for (String ch : chans) {
				List<KeyData> keys = AnimEdit.readKeys(ba, ch);
				if (keys.isEmpty() && (KeyMath.COLOR.equals(ch) || !includeEmpty))continue;
				boolean has = false, mol = false;
				KeyData before = null, after = null;
				for (KeyData k : keys) {
					if (Math.abs(k.time - t) < KeyMath.EPS)has = true;
					if (k.molang)mol = true;
					if (k.time <= t)before = k;
					else if (after == null)after = k;
				}
				if (has) {
					kept++;
					continue;
				}
				if (mol) {
					formulas++;
					continue;
				}
				double[] v = AnimEdit.valueOrRest(ba, ch, t);
				KeyData nk;
				KeyData like = before != null ? before : after;
				if (like != null) {
					nk = like.copy();
					if ("step".equals(nk.interp) && before == null)nk.interp = "linear";
				} else {
					nk = new KeyData();
					nk.interp = "linear";
				}
				nk.time = t;
				nk.molang = false;
				nk.v = new double[][] {KeyMath.dup(v)};
				if ("bezier".equals(nk.interp)) {
					// a fresh, flat handle pair (the neighbour's handles belong to its own curve)
					nk.bLT = new double[] {-0.1, -0.1, -0.1};
					nk.bLV = new double[] {0, 0, 0};
					nk.bRT = new double[] {0.1, 0.1, 0.1};
					nk.bRV = new double[] {0, 0, 0};
				} else {
					nk.bLT = nk.bLV = nk.bRT = nk.bRV = null;
				}
				plans.add(new Plan(ba, ch, nk));
				any = true;
			}
			if (any)parts.add(g.name);
		}
		if (formulas > 0)r.warn("molang", t("cpmplus.w3animtools.warn.formulaSkipped", formulas));
		r.put("keys", (double) plans.size()).put("kept", (double) kept).put("parts", parts).put("time", t);
		if (!apply || plans.isEmpty())return r;
		PlusUndo.editAnimations(Collections.singletonList(anim), t("cpmplus.w3animtools.f23.undo"), () -> {
			for (Plan p : plans)AnimEdit.writeKeys(p.ba, p.ch, Collections.singletonList(p.key), false);
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** CPM+ &gt; Animação &gt; 'Marcar pose aqui...' and the timeline row's context menu */
	public static void openDialog() {
		Animation anim = Animation.selected;
		if (anim == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		double time = Timeline.time;
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.f23.intro", fmt(time))));
		rows.add(FormRow.checkbox("empty", "cpmplus.w3animtools.f23.empty", false).description("cpmplus.w3animtools.f23.empty.desc"));
		rows.add(FormRow.checkbox("color", "cpmplus.w3animtools.f23.color", false).description("cpmplus.w3animtools.f23.color.desc"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w3animtools.f23.ok";
		PlusDialogs.form("cpmplus_w3animtools_posecolumn", "cpmplus.w3animtools.f23.title", rows, fr -> {
			Result res = run(anim, time, fr.getBool("empty"), fr.getBool("color"), true);
			if (!res.ok) {
				PlusDialogs.block(res.errorText());
				return;
			}
			if (!res.changed)PlusDialogs.quick(t("cpmplus.w3animtools.f23.nothing"), 3000);
			else PlusDialogs.done(t("cpmplus.w3animtools.f23.done", ((Double) res.extra.get("keys")).intValue(), fmt(time)));
			if (!res.warnings.isEmpty())PlusDialogs.toast(res.warningText(), "warning", 8000, null);
		}, fo);
	}

	static String fmt(double t) {
		return KeyMath.fmt(t, 2).replace('.', ',') + " s";
	}

	/** f23({anim, time (default: the playhead), includeEmpty, color, apply}) */
	public static Object hook(JsPropertyMap<Object> o) {
		double time = AnimToolSupport.num(o, "time", Timeline.time);
		return run(AnimToolSupport.anim(o), time, AnimToolSupport.bool(o, "includeEmpty", false), AnimToolSupport.bool(o, "color", false), AnimToolSupport.bool(o, "apply", true)).toJs();
	}
}
