package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Takes': saved versions of an animation, kept in the project's CPM+ data (never exported, CPM does not read
 * them). Save the current keys as a take, try something else, then restore a take or MIX the animation toward it
 * (0..100 %: every rotation / position / size value moves that far toward the take, sampled on the union of both key
 * times and the frame grid). Restore and mix are one undo entry each; the list of takes itself is project data.
 */
public final class Takes {
	private Takes() {
	}

	static final String EXTRA = "cpmplusTakes";

	// ====================================================================== storage

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> list(Animation a) {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			Object v = Project.getData().getExtra(EXTRA);
			if (v instanceof Map) {
				Object l = ((Map<String, Object>) v).get(a.uuid);
				if (l instanceof List)for (Object o : (List<Object>) l)if (o instanceof Map)out.add((Map<String, Object>) o);
			}
		} catch (Throwable e) {
			// no project data yet
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	static void store(Animation a, List<Map<String, Object>> takes) {
		ProjectData pd = Project.getData();
		Object v = pd.getExtra(EXTRA);
		Map<String, Object> all = v instanceof Map ? new LinkedHashMap<>((Map<String, Object>) v) : new LinkedHashMap<>();
		if (takes.isEmpty())all.remove(a.uuid);
		else all.put(a.uuid, new ArrayList<Object>(takes));
		if (all.isEmpty())pd.removeExtra(EXTRA);
		else pd.putExtra(EXTRA, all);
		pd.flush();
		Project.saved = false;
	}

	public static Result save(Animation a, String name) {
		Result r = new Result();
		if (a == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		List<Map<String, Object>> takes = list(a);
		Map<String, Object> tk = new LinkedHashMap<>();
		String n = name != null && !name.trim().isEmpty() ? name.trim() : t("cpmplus.w3animtools.takes.defaultName", takes.size() + 1);
		tk.put("name", n);
		tk.put("data", stringify(a.getUndoCopy(JsPropertyMap.of(), false)));
		takes.add(tk);
		store(a, takes);
		r.changed = true;
		return r.put("count", (double) takes.size()).put("name", n);
	}

	public static Result delete(Animation a, int index) {
		Result r = new Result();
		List<Map<String, Object>> takes = list(a);
		if (index < 0 || index >= takes.size())return r.error("noTake", t("cpmplus.w3animtools.takes.err.noTake"));
		takes.remove(index);
		store(a, takes);
		r.changed = true;
		return r.put("count", (double) takes.size());
	}

	/** a detached animation (not in the project) holding the take's keys, to read them */
	static Animation detached(Map<String, Object> take) {
		Object data = parse(String.valueOf(take.get("data")));
		return newDetached(data);
	}

	private static native Animation newDetached(Object data) /*-{
		return new $wnd.Animation(data);
	}-*/;

	private static native String stringify(Object o) /*-{
		return JSON.stringify(o);
	}-*/;

	private static native Object parse(String s) /*-{
		return JSON.parse(s);
	}-*/;

	// ====================================================================== restore / mix

	public static Result restore(Animation a, int index) {
		return mix(a, index, 100);
	}

	/** moves every value amount % toward the take (100 = the take's keys exactly) */
	public static Result mix(Animation a, int index, double amount) {
		Result r = new Result();
		List<Map<String, Object>> takes = list(a);
		if (a == null || index < 0 || index >= takes.size())return r.error("noTake", t("cpmplus.w3animtools.takes.err.noTake"));
		Animation tk = detached(takes.get(index));
		double w = Math.max(0, Math.min(100, amount)) / 100;
		Map<String, Group> groups = new LinkedHashMap<>();
		Map<String, List<KeyData>> plans = new LinkedHashMap<>();
		boolean exact = w >= 1 - 1e-9;
		int fps = Math.max(10, Math.min(60, AnimEdit.snappingOf(a)));
		List<Group> all = new ArrayList<>();
		for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g != null && !all.contains(g))all.add(g);
		}
		for (BoneAnimator ba : AnimEdit.boneAnimators(tk)) {
			Group g = AnimEdit.groupOf(ba);
			if (g != null && !all.contains(g))all.add(g);
		}
		for (Group g : all) {
			BoneAnimator cur = AnimEdit.animatorOf(a, g), take = AnimEdit.animatorOf(tk, g);
			for (String ch : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE, KeyMath.VIS, KeyMath.COLOR}) {
				List<KeyData> kc = AnimEdit.readKeys(cur, ch), kt = AnimEdit.readKeys(take, ch);
				if (kc.isEmpty() && kt.isEmpty())continue;
				List<KeyData> out;
				boolean motion = KeyMath.ROT.equals(ch) || KeyMath.POS.equals(ch) || KeyMath.SCALE.equals(ch);
				if (exact || !motion) {
					out = exact ? kt : kc;// visibility / colour are not mixed
				} else {
					TreeSet<Double> times = new TreeSet<>();
					for (KeyData k : kc)times.add(Tween.snap(k.time));
					for (KeyData k : kt)times.add(Tween.snap(k.time));
					double end = Math.max(a.length, tk.length);
					int n = (int) Math.round(end * fps);
					for (int i = 0; i <= n; i++)times.add(Tween.snap(i / (double) fps));
					out = new ArrayList<>();
					for (Double tt : times) {
						double[] va = AnimEdit.valueOrRest(cur, ch, tt), vb = AnimEdit.valueOrRest(take, ch, tt);
						KeyData kd = new KeyData();
						kd.time = tt;
						kd.interp = "linear";
						kd.molang = false;
						kd.v = new double[][] {{va[0] + (vb[0] - va[0]) * w, va[1] + (vb[1] - va[1]) * w, va[2] + (vb[2] - va[2]) * w}};
						out.add(kd);
					}
					out = CorrectionLayer.reduce(out, false);
				}
				String id = g.uuid + "|" + ch;
				groups.put(id, g);
				plans.put(id, out);
			}
		}
		double newLen = exact ? tk.length : Math.max(a.length, a.length + (tk.length - a.length) * w);
		List<Animation> l = Collections.singletonList(a);
		PlusUndo.run(t(exact ? "cpmplus.w3animtools.takes.undo.restore" : "cpmplus.w3animtools.takes.undo.mix"), AnimxUtil.track(UndoData.make(a), l), () -> AnimxUtil.track(UndoData.make(a), l), () -> {
			for (Map.Entry<String, List<KeyData>> e : plans.entrySet()) {
				String ch = e.getKey().substring(e.getKey().indexOf('|') + 1);
				BoneAnimator ba = a.getBoneAnimator(groups.get(e.getKey()));
				AnimEdit.writeKeys(ba, ch, e.getValue(), true);
			}
			a.length = (float) newLen;
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r.put("channels", (double) plans.size());
	}

	// ====================================================================== dialog

	/** CPM+ &gt; Animação &gt; 'Takes (versões da animação)...' */
	public static void openDialog() {
		Animation a = Animation.selected;
		if (a == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		List<Map<String, Object>> takes = list(a);
		Dialog[] dlg = new Dialog[1];
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.takes.intro", a.name)));
		rows.add(FormRow.text("name", "cpmplus.w3animtools.takes.name", t("cpmplus.w3animtools.takes.defaultName", takes.size() + 1)));
		rows.add(FormRow.buttons("save", "cpmplus.w3animtools.takes.saveLabel", new String[] {"cpmplus.w3animtools.takes.save"}, i -> {
			Object n = dlg[0] != null ? Js.asPropertyMap(dlg[0].getFormResult()).get("name") : null;
			save(a, n != null ? String.valueOf(n) : null);
			reopen(dlg[0]);
		}));
		if (!takes.isEmpty()) {
			rows.add(FormRow.range("amount", "cpmplus.w3animtools.takes.amount", 50, 5, 100, 5));
			for (int k = 0; k < takes.size(); k++) {
				int idx = k;
				rows.add(FormRow.buttons("take" + k, String.valueOf(takes.get(k).get("name")), new String[] {
						"cpmplus.w3animtools.takes.restore", "cpmplus.w3animtools.takes.mix", "cpmplus.w3animtools.takes.delete"}, b -> {
							if (b == 0)report(restore(a, idx), "cpmplus.w3animtools.takes.restored");
							else if (b == 1) {
								Object amt = dlg[0] != null ? Js.asPropertyMap(dlg[0].getFormResult()).get("amount") : null;
								double w = amt != null ? Double.parseDouble(String.valueOf(amt)) : 50;
								report(mix(a, idx, w), "cpmplus.w3animtools.takes.mixed");
							} else {
								delete(a, idx);
								reopen(dlg[0]);
							}
						}));
			}
		} else rows.add(FormRow.info("none", null, t("cpmplus.w3animtools.takes.none")));
		FormOpts fo = new FormOpts();
		fo.width = 560;
		fo.confirmKey = "cpmplus.w3animtools.trans.report.close";
		dlg[0] = PlusDialogs.form("cpmplus_w3animtools_takes", "cpmplus.w3animtools.takes.title", rows, fr -> {}, fo);
	}

	private static void report(Result r, String okKey) {
		if (!r.ok)PlusDialogs.block(r.errorText());
		else PlusDialogs.quick(t(okKey), 3000);
	}

	private static void reopen(Dialog d) {
		try {
			if (d != null)d.hide();
		} catch (Throwable e) {
			// already closed
		}
		openDialog();
	}

	// ====================================================================== QA hook

	/** takes({op: save | list | restore | mix | delete, anim, name, index, amount}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Animation a = AnimToolSupport.anim(opts);
		String op = AnimToolSupport.str(opts, "op", "list");
		int index = (int) AnimToolSupport.num(opts, "index", 0);
		Result r;
		switch (op) {
		case "save":
			r = save(a, AnimToolSupport.str(opts, "name", null));
			break;
		case "restore":
			r = restore(a, index);
			break;
		case "mix":
			r = mix(a, index, AnimToolSupport.num(opts, "amount", 50));
			break;
		case "delete":
			r = delete(a, index);
			break;
		default:
			r = new Result();
		}
		JsArray<Object> names = new JsArray<>();
		if (a != null)for (Map<String, Object> m : list(a))names.push(String.valueOf(m.get("name")));
		JsPropertyMap<Object> js = r.toJs();
		js.set("takes", names);
		return js;
	}
}
