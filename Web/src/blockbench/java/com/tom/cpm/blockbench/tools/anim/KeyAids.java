package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ key aids:
 * <ul>
 * <li>Tangentes que não passam do ponto: bezier keys whose handles never overshoot (a key that is a peak or a valley
 * gets flat handles; the others follow the neighbours' slope, clamped so the curve stays between them).</li>
 * <li>Esticar / achatar (volume): a size key at the playhead, one axis s and the two others 1 / sqrt(s) (the volume
 * stays the same).</li>
 * <li>Pivô temporário: turns the parts around another part's pivot without moving any pivot: rotation and position keys
 * at the playhead that put the part where the turn around that point would.</li>
 * </ul>
 * Each is one undo entry; the dialogs preview live and restore before OK / Cancel.
 */
public final class KeyAids {
	private KeyAids() {
	}

	// ====================================================================== clamped tangents

	static final class Target {
		final BoneAnimator ba;
		final String ch;
		final Set<Keyframe> only;

		Target(BoneAnimator ba, String ch, Set<Keyframe> only) {
			this.ba = ba;
			this.ch = ch;
			this.only = only;
		}
	}

	/** the selected keys of the timeline, else every key of the selected parts (else of every part) */
	static List<Target> targets(Animation a, boolean useSelection) {
		List<Target> out = new ArrayList<>();
		Map<String, Target> byChannel = new LinkedHashMap<>();
		if (useSelection && Timeline.selected != null && Timeline.selected.length > 0) {
			for (int i = 0; i < Timeline.selected.length; i++) {
				Keyframe kf = Timeline.selected.getAt(i);
				Object an = Js.asPropertyMap(kf).get("animator");
				Object ch = Js.asPropertyMap(kf).get("channel");
				if (!(an instanceof BoneAnimator) || ch == null)continue;
				String c = String.valueOf(ch);
				if (!KeyMath.ROT.equals(c) && !KeyMath.POS.equals(c) && !KeyMath.SCALE.equals(c))continue;
				BoneAnimator ba = (BoneAnimator) an;
				String k = ba.uuid + "|" + c;
				Target t = byChannel.get(k);
				if (t == null)byChannel.put(k, t = new Target(ba, c, new LinkedHashSet<>()));
				t.only.add(kf);
			}
			out.addAll(byChannel.values());
			if (!out.isEmpty())return out;
		}
		List<Group> sel = AnimToolSupport.selectedGroups();
		for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null || !sel.isEmpty() && !sel.contains(g))continue;
			for (String c : new String[] {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE})if (AnimEdit.count(ba, c) >= 2)out.add(new Target(ba, c, null));
		}
		return out;
	}

	public static Result clampTangents(Animation a, boolean useSelection, boolean apply) {
		Result r = new Result();
		if (a == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		List<Target> ts = targets(a, useSelection);
		int n = 0;
		for (Target t : ts) {
			JsArrayE<Keyframe> arr = t.ba.channel(t.ch);
			if (arr == null)continue;
			for (int i = 0; i < arr.length; i++)if ((t.only == null || t.only.contains(arr.getAt(i))) && !AnimEdit.hasMolang(arr.getAt(i)))n++;
		}
		r.put("keys", (double) n);
		if (!apply || n == 0)return r;
		PlusUndo.editAnimations(Collections.singletonList(a), t("cpmplus.w3animtools.aids.tangents.undo"), () -> {
			for (Target t : ts)clamp(t);
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	static void clamp(Target t) {
		JsArrayE<Keyframe> arr = t.ba.channel(t.ch);
		List<Keyframe> keys = new ArrayList<>();
		for (int i = 0; i < arr.length; i++)keys.add(arr.getAt(i));
		Collections.sort(keys, (x, y) -> Float.compare(x.time, y.time));
		for (int i = 0; i < keys.size(); i++) {
			Keyframe k = keys.get(i);
			if (t.only != null && !t.only.contains(k) || AnimEdit.hasMolang(k))continue;
			Keyframe p = i > 0 ? keys.get(i - 1) : null, nx = i + 1 < keys.size() ? keys.get(i + 1) : null;
			double[] lt = new double[3], lv = new double[3], rt = new double[3], rv = new double[3];
			for (int a = 0; a < 3; a++) {
				double v = AnimxUtil.value(k, 0, a);
				double vp = p != null ? AnimxUtil.value(p, Math.max(0, AnimxUtil.dataPoints(p) - 1), a) : Double.NaN;
				double vn = nx != null ? AnimxUtil.value(nx, 0, a) : Double.NaN;
				double dl = p != null ? k.time - p.time : 0.3, dr = nx != null ? nx.time - k.time : 0.3;
				double m = 0;
				if (p != null && nx != null && !Double.isNaN(vp) && !Double.isNaN(vn)) {
					boolean peak = v >= Math.max(vp, vn) - 1e-9 || v <= Math.min(vp, vn) + 1e-9;
					m = peak ? 0 : (vn - vp) / (nx.time - p.time);
				}
				double l = -m * dl / 3, rr = m * dr / 3;
				if (p != null && !Double.isNaN(vp))l = clampTo(l, vp - v);
				if (nx != null && !Double.isNaN(vn))rr = clampTo(rr, vn - v);
				lt[a] = -dl / 3;
				lv[a] = l;
				rt[a] = dr / 3;
				rv[a] = rr;
			}
			setBezier(k, lt, lv, rt, rv);
		}
	}

	/** x limited to the interval between 0 and limit (same sign, not beyond) */
	static double clampTo(double x, double limit) {
		if (limit >= 0)return Math.max(0, Math.min(limit, x));
		return Math.min(0, Math.max(limit, x));
	}

	private static native void setBezier(Keyframe k, double[] lt, double[] lv, double[] rt, double[] rv) /*-{
		k.interpolation = 'bezier';
		k.bezier_linked = false;
		k.bezier_left_time = [lt[0], lt[1], lt[2]];
		k.bezier_left_value = [lv[0], lv[1], lv[2]];
		k.bezier_right_time = [rt[0], rt[1], rt[2]];
		k.bezier_right_value = [rv[0], rv[1], rv[2]];
	}-*/;

	// ====================================================================== squash & stretch

	/** the size key value: axis s, the two others 1 / sqrt(s) */
	static double[] volumeScale(int axis, double s) {
		double o = 1 / Math.sqrt(Math.max(1e-3, s));
		double[] v = {o, o, o};
		v[axis] = s;
		return v;
	}

	static Result squash(Animation a, List<Group> parts, int axis, double percent, double time, Tween.Live live) {
		Result r = new Result();
		if (a == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (parts.isEmpty())return r.error("noParts", t("cpmplus.w3animtools.err.selectPart"));
		double[] v = volumeScale(axis, percent / 100);
		double tm = Tween.snap(time);
		Runnable body = () -> {
			for (Group g : parts)writeAt(a, g, KeyMath.SCALE, tm, v, live);
		};
		if (live != null)body.run();
		else {
			PlusUndo.run(t("cpmplus.w3animtools.aids.squash.undo"), AnimxUtil.track(com.tom.cpm.blockbench.proxy.Undo.UndoData.make(a), Collections.singletonList(a)),
					() -> AnimxUtil.track(com.tom.cpm.blockbench.proxy.Undo.UndoData.make(a), Collections.singletonList(a)), body);
			r.changed = true;
		}
		AnimToolSupport.refreshPreview();
		r.put("x", v[0]).put("y", v[1]).put("z", v[2]).put("parts", (double) parts.size());
		return r;
	}

	/** a key at time t (adjusted in place, or a new one taking the interpolation of its neighbour) */
	static void writeAt(Animation a, Group g, String ch, double tm, double[] v, Tween.Live live) {
		BoneAnimator ba = a.getBoneAnimator(g);
		JsArrayE<Keyframe> arr = ba.channel(ch);
		Keyframe at = null;
		if (arr != null)for (int i = 0; i < arr.length; i++)if (Math.abs(arr.getAt(i).time - tm) < KeyMath.EPS)at = arr.getAt(i);
		if (at != null) {
			if (live != null)live.remember(at);
			int n = Math.max(1, AnimxUtil.dataPoints(at));
			KeyData k = new KeyData();
			k.v = new double[n][];
			for (int i = 0; i < n; i++)k.v[i] = KeyMath.dup(v);
			Exaggerate.write(at, k);
		} else {
			KeyData nk = Tween.newKey(AnimEdit.readKeys(ba, ch), tm, v);
			AnimEdit.writeKeys(ba, ch, Collections.singletonList(nk), false);
			if (live != null)live.created(ba, ch, tm);
		}
	}

	// ====================================================================== temporary pivot

	/**
	 * [rotation to add (degrees x, y, z), position to add (px)] for a part shown at the playhead, so it turns by (rx, ry,
	 * rz) (ZYX, in its parent's frame) around the pivot of 'pivot' instead of its own pivot.
	 */
	private static native double[] pivotDelta(Object part, Object pivot, double rx, double ry, double rz) /*-{
		var T = $wnd.THREE, m = part.mesh, pm = m && m.parent, pv = pivot.mesh;
		if (!m || !pm || !pv) return [0, 0, 0, 0, 0, 0];
		m.updateWorldMatrix(true, false); pv.updateWorldMatrix(true, false);
		var Q = pm.worldToLocal(pv.getWorldPosition(new T.Vector3()));
		var dq = new T.Quaternion().setFromEuler(new T.Euler(rx * Math.PI / 180, ry * Math.PI / 180, rz * Math.PI / 180, 'ZYX'));
		var q = m.quaternion.clone(), q2 = dq.clone().multiply(q);
		var e0 = m.rotation, e1 = new T.Euler().setFromQuaternion(q2, 'ZYX');
		var wrap = function(a) { a = a % (2 * Math.PI); if (a > Math.PI) a -= 2 * Math.PI; if (a < -Math.PI) a += 2 * Math.PI; return a; };
		var p = m.position.clone(), p2 = p.clone().sub(Q).applyQuaternion(dq).add(Q);
		return [wrap(e1.x - e0.x) * 180 / Math.PI, wrap(e1.y - e0.y) * 180 / Math.PI, wrap(e1.z - e0.z) * 180 / Math.PI, p2.x - p.x, p2.y - p.y, p2.z - p.z];
	}-*/;

	static Result tempPivot(Animation a, List<Group> parts, Group pivot, double rx, double ry, double rz, double time, Tween.Live live) {
		Result r = new Result();
		if (a == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		if (parts.isEmpty())return r.error("noParts", t("cpmplus.w3animtools.err.selectPart"));
		if (pivot == null)return r.error("noPivot", t("cpmplus.w3animtools.aids.pivot.err.noPivot"));
		double tm = Tween.snap(time);
		// the pose at the playhead on the meshes (this animation and the ones playing with it)
		float save = Timeline.time;
		Timeline.time = (float) tm;
		AnimToolSupport.refreshPreview();
		List<double[]> deltas = new ArrayList<>();
		for (Group g : parts) {
			// a plain JS array: copy it into a Java array before it goes into a List (a cast would throw)
			double[] raw = pivotDelta(g, pivot, rx, ry, rz);
			double[] d = new double[6];
			for (int i = 0; i < 6; i++)d[i] = raw[i];
			deltas.add(d);
		}
		Timeline.time = save;
		Runnable body = () -> {
			for (int i = 0; i < parts.size(); i++) {
				Group g = parts.get(i);
				double[] d = deltas.get(i);
				BoneAnimator ba = AnimEdit.animatorOf(a, g);
				double[] rot = AnimEdit.valueOrRest(ba, KeyMath.ROT, tm), pos = AnimEdit.valueOrRest(ba, KeyMath.POS, tm);
				writeAt(a, g, KeyMath.ROT, tm, new double[] {rot[0] + d[0], rot[1] + d[1], rot[2] + d[2]}, live);
				writeAt(a, g, KeyMath.POS, tm, new double[] {pos[0] + d[3], pos[1] + d[4], pos[2] + d[5]}, live);
			}
		};
		if (live != null)body.run();
		else {
			PlusUndo.run(t("cpmplus.w3animtools.aids.pivot.undo"), AnimxUtil.track(com.tom.cpm.blockbench.proxy.Undo.UndoData.make(a), Collections.singletonList(a)),
					() -> AnimxUtil.track(com.tom.cpm.blockbench.proxy.Undo.UndoData.make(a), Collections.singletonList(a)), body);
			r.changed = true;
		}
		AnimToolSupport.refreshPreview();
		double[] d0 = deltas.get(0);
		r.put("dRot", JsonList.of(d0[0], d0[1], d0[2])).put("dPos", JsonList.of(d0[3], d0[4], d0[5]));
		return r;
	}

	/** tiny helper: a List of doubles for Result.put (JsonUtil turns it into an array) */
	static final class JsonList {
		static List<Object> of(double... v) {
			List<Object> l = new ArrayList<>();
			for (double d : v)l.add(Math.round(d * 1e4) / 1e4);
			return l;
		}
	}

	// ====================================================================== dialogs

	/** CPM+ &gt; Animação &gt; 'Tangentes que não passam do ponto' */
	public static void tangentsAction() {
		Result r = clampTangents(Animation.selected, true, true);
		if (!r.ok)PlusDialogs.block(r.errorText());
		else if (!r.changed)PlusDialogs.quick(t("cpmplus.w3animtools.aids.tangents.nothing"), 4000);
		else PlusDialogs.done(t("cpmplus.w3animtools.aids.tangents.done", ((Double) r.extra.get("keys")).intValue()));
	}

	/** CPM+ &gt; Animação &gt; 'Esticar / achatar (mantém o volume)...' */
	public static void squashDialog() {
		Animation a = Animation.selected;
		List<Group> sel = AnimToolSupport.selectedGroups();
		if (a == null || sel.isEmpty()) {
			PlusDialogs.block(t(a == null ? "cpmplus.w3animtools.err.noAnimation" : "cpmplus.w3animtools.err.selectPart"));
			return;
		}
		double time = Tween.snap(Timeline.time);
		Tween.Live live = new Tween.Live();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.aids.squash.intro", PoseColumn.fmt(time))));
		rows.add(FormRow.radio("axis", "cpmplus.w3animtools.aids.squash.axis", PlusDialogs.options("1", "Y", "0", "X", "2", "Z"), "1"));
		rows.add(FormRow.range("amount", "cpmplus.w3animtools.aids.squash.amount", 120, 30, 250, 5).description("cpmplus.w3animtools.aids.squash.amount.desc"));
		FormOpts fo = new FormOpts();
		fo.onCancel = live::restore;
		fo.onChange = fr -> {
			live.restore();
			squash(a, sel, axis(fr), fr.getDouble("amount", 100), time, live);
		};
		PlusDialogs.form("cpmplus_w3animtools_squash", "cpmplus.w3animtools.aids.squash.title", rows, fr -> {
			live.restore();
			Result r = squash(a, sel, axis(fr), fr.getDouble("amount", 100), time, null);
			if (!r.ok)PlusDialogs.block(r.errorText());
		}, fo);
	}

	private static int axis(FormResult fr) {
		String s = fr.getString("axis");
		return "0".equals(s) ? 0 : "2".equals(s) ? 2 : 1;
	}

	/** CPM+ &gt; Animação &gt; 'Girar em volta de outro ponto (pivô temporário)...' */
	public static void pivotDialog() {
		Animation a = Animation.selected;
		List<Group> sel = AnimToolSupport.selectedGroups();
		if (a == null || sel.isEmpty()) {
			PlusDialogs.block(t(a == null ? "cpmplus.w3animtools.err.noAnimation" : "cpmplus.w3animtools.err.selectPart"));
			return;
		}
		double time = Tween.snap(Timeline.time);
		Map<String, String> groups = new LinkedHashMap<>();
		for (Group g : Group.all)if (!sel.contains(g))groups.put(g.uuid, AnimEdit.pathOf(g));
		Tween.Live live = new Tween.Live();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.aids.pivot.intro", PoseColumn.fmt(time))));
		rows.add(FormRow.select("pivot", "cpmplus.w3animtools.aids.pivot.pivot", groups, groups.isEmpty() ? "" : groups.keySet().iterator().next()));
		rows.add(FormRow.decimal("rx", "cpmplus.w3animtools.reladj.rx", 0, -360, 360, 1));
		rows.add(FormRow.decimal("ry", "cpmplus.w3animtools.reladj.ry", 0, -360, 360, 1));
		rows.add(FormRow.decimal("rz", "cpmplus.w3animtools.reladj.rz", 0, -360, 360, 1));
		FormOpts fo = new FormOpts();
		fo.onCancel = live::restore;
		fo.onChange = fr -> {
			live.restore();
			Group pv = BatchKeys.byUuid(fr.getString("pivot"));
			if (pv != null)tempPivot(a, sel, pv, fr.getDouble("rx", 0), fr.getDouble("ry", 0), fr.getDouble("rz", 0), time, live);
		};
		PlusDialogs.form("cpmplus_w3animtools_pivot", "cpmplus.w3animtools.aids.pivot.title", rows, fr -> {
			live.restore();
			Result r = tempPivot(a, sel, BatchKeys.byUuid(fr.getString("pivot")), fr.getDouble("rx", 0), fr.getDouble("ry", 0), fr.getDouble("rz", 0), time, null);
			if (!r.ok)PlusDialogs.block(r.errorText());
		}, fo);
	}

	// ====================================================================== QA hook

	/** keyAids({op: tangents | squash | pivot, anim, parts, axis, amount, pivot, rot, time, selection}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		String op = AnimToolSupport.str(opts, "op", "tangents");
		Animation a = AnimToolSupport.anim(opts);
		List<Group> parts = new ArrayList<>();
		for (String s : AnimToolSupport.list(opts, "parts")) {
			Group g = AnimToolSupport.group(s);
			if (g != null)parts.add(g);
		}
		double time = AnimToolSupport.num(opts, "time", Timeline.time);
		switch (op) {
		case "squash":
			return squash(a, parts, (int) AnimToolSupport.num(opts, "axis", 1), AnimToolSupport.num(opts, "amount", 100), time, null).toJs();
		case "pivot": {
			List<String> rot = AnimToolSupport.list(opts, "rot");
			double[] rv = new double[3];
			for (int i = 0; i < 3 && i < rot.size(); i++)rv[i] = Double.parseDouble(rot.get(i));
			String pv = AnimToolSupport.str(opts, "pivot", "");
			return tempPivot(a, parts, AnimToolSupport.group(pv), rv[0], rv[1], rv[2], time, null).toJs();
		}
		default:
			return clampTangents(a, AnimToolSupport.bool(opts, "selection", false), AnimToolSupport.bool(opts, "apply", true)).toJs();
		}
	}
}
