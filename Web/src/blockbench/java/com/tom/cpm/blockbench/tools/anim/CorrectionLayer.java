package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Camada de ajuste' (correction layer, like Blender's animation layers): a companion '&lt;name&gt;#ajuste'
 * (additive, same type / settings / exported duration, priority above every non-additive animation) where fixes are
 * keyed as OFFSETS on top of the original, without touching its keys. In game it plays together with the original
 * (same button / pose); in Blockbench both play while the layer is edited.
 * <ul>
 * <li>'criar / editar': creates the layer the first time (one undo) and selects it with the original still playing;
 * with the layer selected it goes back to the original (the layer keeps playing).</li>
 * <li>'força': scales the layer (0..150 %): rotation / position values times f, size 1 + (s - 1) * f.</li>
 * <li>'fundir': the layer added into the original's keys (rotation / position added, size multiplied; sampled on the
 * union of both key times and the original's frame grid, linear, redundant keys dropped) and the layer removed,
 * ONE undo entry. Visibility / colour keys of the layer are not merged (warning).</li>
 * </ul>
 */
public final class CorrectionLayer {
	private CorrectionLayer() {
	}

	static final String[] SUFFIXES = {"ajuste", "correction"};

	static String layerName(String src) {
		return LayerSupport.nameFor(src, t("cpmplus.w3animtools.corr.suffix"));
	}

	static Animation layerOf(Animation src) {
		return LayerSupport.companionOf(src, SUFFIXES);
	}

	static Animation originalOf(Animation a) {
		return LayerSupport.originalOf(a, SUFFIXES);
	}

	static boolean isLayer(Animation a) {
		return a != null && LayerSupport.sourceNameOf(a.name, SUFFIXES) != null;
	}

	/** the original's exported duration (one cycle in game): the layer's length, so both stay in phase */
	static double period(Animation src) {
		return Math.max(0.05, AnimEdit.predictFrames(src).durationMs / 1000.0);
	}

	// ====================================================================== create / open

	/** creates the layer when missing (one undo entry); r: name, uuid, created */
	static Result ensure(Animation orig) {
		Result r = new Result();
		Animation layer = layerOf(orig);
		if (layer != null) {
			r.put("name", layer.name).put("uuid", layer.uuid).put("created", false);
			return r;
		}
		Set<Animation> touch = LayerSupport.nonAdditiveTouching(orig, null, null);
		AnimEdit.PriorityPlan plan = AnimEdit.nextPriority(touch);
		if (!plan.ok)return r.error("priority", t(plan.messageKey));
		List<Animation> changed = new ArrayList<>(plan.renumber.keySet());
		Animation[] created = new Animation[1];
		double len = period(orig);
		PlusUndo.run(t("cpmplus.w3animtools.corr.undo.create"), UndoData.make(changed.toArray(new Animation[0])), () -> {
			List<Animation> after = new ArrayList<>(changed);
			if (created[0] != null)after.add(created[0]);
			return UndoData.make(after.toArray(new Animation[0]));
		}, () -> {
			plan.applyRenumber();
			Animation a = new Animation();
			a.extend(LayerSupport.copyData(orig, layerName(orig.name)));
			LayerSupport.setup(a, orig, plan.value, len);
			AnimEdit.copySnapping(orig, a);
			created[0] = a.add(false);
		});
		r.changed = true;
		r.put("name", created[0].name).put("uuid", created[0].uuid).put("created", true).put("priority", (double) created[0].getPriority());
		return r;
	}

	/** CPM+ &gt; Animação &gt; 'Camada de ajuste: criar / editar' (Ctrl+Alt+Shift+A) */
	public static void editAction() {
		Animation sel = Animation.selected;
		if (sel == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		Result r = toggle(sel);
		if (!r.ok) {
			PlusDialogs.block(r.errorText());
			return;
		}
		String what = String.valueOf(r.extra.get("now"));
		if ("layer".equals(what))PlusDialogs.quick(t(Boolean.TRUE.equals(r.extra.get("created")) ? "cpmplus.w3animtools.corr.created" : "cpmplus.w3animtools.corr.opened", String.valueOf(r.extra.get("name"))), 7000);
		else PlusDialogs.quick(t("cpmplus.w3animtools.corr.back", String.valueOf(r.extra.get("name"))), 5000);
	}

	/** original selected: create / select its layer (original keeps playing); layer selected: back to the original */
	static Result toggle(Animation sel) {
		if (isLayer(sel)) {
			Animation orig = originalOf(sel);
			Result r = new Result();
			if (orig == sel)return r.error("noOriginal", t("cpmplus.w3animtools.corr.err.noOriginal", sel.name));
			orig.select();
			LayerSupport.setPlaying(sel, true);
			AnimToolSupport.refreshPreview();
			return r.put("now", "original").put("name", orig.name);
		}
		if (Physics.isLayerName(sel.name))return new Result().error("isLayer", t("cpmplus.w3animtools.physics.err.isLayer", sel.name));
		if (AnimEdit.isStaged(sel))return new Result().error("staged", t("cpmplus.w3animtools.physics.err.staged", sel.name));
		Result r = ensure(sel);
		if (!r.ok)return r;
		Animation layer = layerOf(sel);
		layer.select();
		LayerSupport.setPlaying(sel, true);
		AnimToolSupport.refreshPreview();
		return r.put("now", "layer");
	}

	// ====================================================================== strength

	static final class Scaled {
		final Keyframe kf;
		final String ch;

		Scaled(Keyframe kf, String ch) {
			this.kf = kf;
			this.ch = ch;
		}
	}

	static List<Scaled> keysOf(Animation layer) {
		List<Scaled> out = new ArrayList<>();
		for (BoneAnimator ba : AnimEdit.boneAnimators(layer)) {
			for (String ch : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE}) {
				JsArrayE<Keyframe> arr = ba.channel(ch);
				if (arr == null)continue;
				for (int i = 0; i < arr.length; i++)if (!AnimEdit.hasMolang(arr.getAt(i)))out.add(new Scaled(arr.getAt(i), ch));
			}
		}
		return out;
	}

	static void scale(List<Scaled> keys, double f, Tween.Live live) {
		for (Scaled s : keys) {
			if (live != null)live.remember(s.kf);
			int n = Math.max(1, AnimxUtil.dataPoints(s.kf));
			KeyData k = new KeyData();
			k.v = new double[n][3];
			for (int d = 0; d < n; d++)for (int a = 0; a < 3; a++) {
				double v = AnimxUtil.value(s.kf, d, a);
				if (Double.isNaN(v))v = KeyMath.SCALE.equals(s.ch) ? 1 : 0;
				k.v[d][a] = KeyMath.SCALE.equals(s.ch) ? 1 + (v - 1) * f : v * f;
			}
			Exaggerate.write(s.kf, k);
		}
	}

	public static Result strength(Animation layer, double percent, boolean apply) {
		Result r = new Result();
		if (layer == null || !isLayer(layer))return r.error("noLayer", t("cpmplus.w3animtools.corr.err.noLayer", layer != null ? layer.name : ""));
		List<Scaled> keys = keysOf(layer);
		r.put("keys", (double) keys.size());
		if (!apply || keys.isEmpty())return r;
		double f = Math.max(0, percent) / 100;
		PlusUndo.editAnimations(Collections.singletonList(layer), t("cpmplus.w3animtools.corr.undo.strength"), () -> scale(keys, f, null));
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** CPM+ &gt; Animação &gt; 'Camada de ajuste: força...' */
	public static void strengthDialog() {
		Animation sel = Animation.selected;
		Animation layer = isLayer(sel) ? sel : layerOf(sel);
		if (layer == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.corr.err.noLayer", sel != null ? sel.name : ""));
			return;
		}
		Animation orig = originalOf(layer);
		if (orig != null && orig != layer)LayerSupport.setPlaying(orig, true);
		LayerSupport.setPlaying(layer, true);
		List<Scaled> keys = keysOf(layer);
		Tween.Live live = new Tween.Live();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.corr.strength.intro", layer.name)));
		rows.add(FormRow.range("amount", "cpmplus.w3animtools.corr.strength.amount", 100, 0, 150, 5));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w3animtools.corr.strength.ok";
		fo.onCancel = live::restore;
		fo.onChange = fr -> {
			live.restore();
			scale(keys, fr.getDouble("amount", 100) / 100, live);
			AnimToolSupport.refreshPreview();
		};
		PlusDialogs.form("cpmplus_w3animtools_corr_strength", "cpmplus.w3animtools.corr.strength.title", rows, fr -> {
			live.restore();
			Result r = strength(layer, fr.getDouble("amount", 100), true);
			if (!r.ok)PlusDialogs.block(r.errorText());
			else PlusDialogs.done(t("cpmplus.w3animtools.corr.strength.done", PoseColumn.fmt(fr.getDouble("amount", 100)) + "%"));
		}, fo);
	}

	// ====================================================================== merge down

	static final class MergePlan {
		final Group g;
		final String ch;
		final List<KeyData> keys;
		final boolean newChannel;

		MergePlan(Group g, String ch, List<KeyData> keys, boolean newChannel) {
			this.g = g;
			this.ch = ch;
			this.keys = keys;
			this.newChannel = newChannel;
		}
	}

	static List<MergePlan> mergePlan(Animation orig, Animation layer, Result r) {
		List<MergePlan> out = new ArrayList<>();
		int fps = Math.max(10, Math.min(60, AnimEdit.snappingOf(orig)));
		int newParts = 0, ignored = 0, formulas = 0;
		for (BoneAnimator la : AnimEdit.boneAnimators(layer)) {
			Group g = AnimEdit.groupOf(la);
			if (g == null)continue;
			if (la.hasVisible() || la.hasColor())ignored++;
			BoneAnimator ba = AnimEdit.animatorOf(orig, g);
			for (String ch : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE}) {
				if (!AnimxUtil.hasKeys(la, ch))continue;
				List<KeyData> base = AnimEdit.readKeys(ba, ch);
				List<KeyData> lay = AnimEdit.readKeys(la, ch);
				boolean step = !base.isEmpty();
				for (KeyData k : base) {
					if (!"step".equals(k.interp))step = false;
					if (k.molang)formulas++;
				}
				double end = Math.max(orig.length, 0);
				for (KeyData k : lay)end = Math.max(end, k.time);
				TreeSet<Double> times = new TreeSet<>();
				for (KeyData k : base)times.add(Tween.snap(k.time));
				for (KeyData k : lay)times.add(Tween.snap(k.time));
				int n = (int) Math.round(end * fps);
				for (int i = 0; i <= n; i++)times.add(Tween.snap(Math.min(end, i / (double) fps)));
				List<KeyData> keys = new ArrayList<>();
				Double last = null;
				for (Double t : times) {
					if (last != null && t - last < 1e-4)continue;
					last = t;
					double[] b = AnimEdit.valueOrRest(ba, ch, t);
					double[] l = AnimEdit.valueOrRest(la, ch, t);
					double[] v = new double[3];
					for (int a = 0; a < 3; a++)v[a] = KeyMath.SCALE.equals(ch) ? b[a] * l[a] : b[a] + l[a];
					KeyData kd = new KeyData();
					kd.time = t;
					kd.interp = step ? "step" : "linear";
					kd.molang = false;
					kd.v = new double[][] {v};
					keys.add(kd);
				}
				keys = reduce(keys, step);
				boolean fresh = base.isEmpty();
				if (fresh)newParts++;
				out.add(new MergePlan(g, ch, keys, fresh));
			}
		}
		if (newParts > 0)r.warn("newParts", t("cpmplus.w3animtools.corr.merge.warn.newParts", newParts));
		if (ignored > 0)r.warn("ignored", t("cpmplus.w3animtools.corr.merge.warn.ignored", ignored));
		if (formulas > 0)r.warn("molang", t("cpmplus.w3animtools.corr.merge.warn.molang", formulas));
		int total = 0;
		for (MergePlan p : out)total += p.keys.size();
		r.put("channels", (double) out.size()).put("keys", (double) total);
		return out;
	}

	/** drops keys the neighbours already give (linear) / repeat the previous value (step) */
	static List<KeyData> reduce(List<KeyData> keys, boolean step) {
		if (keys.size() <= 2)return keys;
		List<KeyData> out = new ArrayList<>();
		out.add(keys.get(0));
		for (int i = 1; i < keys.size() - 1; i++) {
			KeyData p = out.get(out.size() - 1), c = keys.get(i), nx = keys.get(i + 1);
			boolean drop = true;
			for (int a = 0; a < 3 && drop; a++) {
				double expect = step ? p.v[0][a] : p.v[0][a] + (nx.v[0][a] - p.v[0][a]) * (c.time - p.time) / (nx.time - p.time);
				if (Math.abs(c.v[0][a] - expect) > 1e-4)drop = false;
			}
			if (!drop)out.add(c);
		}
		out.add(keys.get(keys.size() - 1));
		return out;
	}

	public static Result merge(Animation orig, boolean apply) {
		Result r = new Result();
		Animation layer = layerOf(orig);
		if (layer == null)return r.error("noLayer", t("cpmplus.w3animtools.corr.err.noLayer", orig != null ? orig.name : ""));
		List<MergePlan> plans = mergePlan(orig, layer, r);
		r.put("layer", layer.name);
		if (!apply)return r;
		PlusUndo.run(t("cpmplus.w3animtools.corr.undo.merge"), UndoData.make(orig, layer), () -> UndoData.make(orig), () -> {
			for (MergePlan p : plans)AnimEdit.writeKeys(orig.getBoneAnimator(p.g), p.ch, p.keys, true);
			layer.remove(false, false);
		});
		r.changed = true;
		if (Animation.selected != orig)orig.select();
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** CPM+ &gt; Animação &gt; 'Camada de ajuste: fundir na animação' */
	public static void mergeDialog() {
		Animation sel = Animation.selected;
		Animation orig = sel != null && isLayer(sel) ? originalOf(sel) : sel;
		if (orig == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		Result pre = new Result();
		Animation layer = layerOf(orig);
		if (layer == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.corr.err.noLayer", orig.name));
			return;
		}
		mergePlan(orig, layer, pre);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.corr.merge.intro", layer.name, orig.name)));
		if (!pre.warnings.isEmpty())rows.add(FormRow.info("warn", null, pre.warningText()));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w3animtools.corr.merge.ok";
		PlusDialogs.form("cpmplus_w3animtools_corr_merge", "cpmplus.w3animtools.corr.merge.title", rows, fr -> {
			Result r = merge(orig, true);
			if (!r.ok)PlusDialogs.block(r.errorText());
			else PlusDialogs.done(t("cpmplus.w3animtools.corr.merge.done", layer.name, orig.name));
		}, fo);
	}

	// ====================================================================== QA hook

	/** correction({action: toggle | strength | merge, anim, amount, apply}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		String action = AnimToolSupport.str(opts, "action", "toggle");
		Animation a = AnimToolSupport.anim(opts);
		boolean apply = AnimToolSupport.bool(opts, "apply", true);
		Result r;
		switch (action) {
		case "strength":
			r = strength(isLayer(a) ? a : layerOf(a), AnimToolSupport.num(opts, "amount", 100), apply);
			break;
		case "merge":
			r = merge(isLayer(a) ? originalOf(a) : a, apply);
			break;
		default:
			r = a == null ? new Result().error("noAnim", t("cpmplus.w3animtools.err.noAnimation")) : toggle(a);
		}
		if (Animation.selected != null)r.put("selected", Animation.selected.name);
		return r.toJs();
	}
}
