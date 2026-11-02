package com.tom.cpm.blockbench.tools.bend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/** CPM+ (G42 / G57): the dialogs of 'Membros dobráveis' and 'Curvar peça', pt-BR first. */
public final class BendDialogs {

	private BendDialogs() {
	}

	private static String t(String k) {
		return I18n.get(k);
	}

	// ====================================================================== G42

	/** 'Membros dobráveis…' (root = null: every part) */
	public static void openBend(Group root) {
		if (BendLimb.animateMode()) {
			PlusDialogs.block(t("cpmplus.w7rig.bend.err.animate"));
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w7rig.bend.intro"));
		Map<PlayerModelParts, BendLimb.Plan> plans = new LinkedHashMap<>();
		boolean anyArm = false, anyLayer = false, anySlim = false;
		List<String> belowNames = new ArrayList<>();
		for (PlayerModelParts p : BendLimb.PARTS) {
			BendLimb.Opts o = new BendLimb.Opts();
			o.root = p.getName();
			BendLimb.Plan pl = BendLimb.plan(o);
			plans.put(p, pl);
			if (pl.error == null) {
				boolean def = root != null ? root.name.equals(p.getName()) : p != PlayerModelParts.BODY;
				rows.add(FormRow.checkbox("part_" + p.getName(), BendLimb.partName(p) + " (" + jointWord(p) + ")", def));
				if (p == PlayerModelParts.LEFT_ARM || p == PlayerModelParts.RIGHT_ARM)anyArm = true;
				if (pl.layer != null || pl.layerFromVanilla)anyLayer = true;
				if (pl.slim)anySlim = true;
				for (OutlinerNode n : pl.below)belowNames.add(nameOf(n));
			} else {
				rows.add(FormRow.info("na_" + p.getName(), BendLimb.partName(p), pl.errorText()));
			}
		}
		boolean anyOk = plans.values().stream().anyMatch(pl -> pl.error == null);
		if (!anyOk) {
			BendLimb.Plan only = root != null ? plans.get(BendLimb.part(root.name)) : null;
			PlusDialogs.block(only != null ? only.errorText() : t("cpmplus.w7rig.bend.err.none"));
			return;
		}
		rows.add(FormRow.separator());
		rows.add(FormRow.range("cut", "cpmplus.w7rig.bend.cut", 6, 1, 11, 1).description("cpmplus.w7rig.bend.cut.desc"));
		rows.add(FormRow.select("dir", "cpmplus.w7rig.bend.dir", PlusDialogs.options("auto", "cpmplus.w7rig.bend.dir.auto", "front", "cpmplus.w7rig.bend.dir.front", "back", "cpmplus.w7rig.bend.dir.back"), "auto")
				.description("cpmplus.w7rig.bend.dir.desc"));
		if (anyLayer)rows.add(FormRow.checkbox("layer", "cpmplus.w7rig.bend.layer", true).description("cpmplus.w7rig.bend.layer.desc"));
		if (anyArm)rows.add(FormRow.checkbox("item", "cpmplus.w7rig.bend.item", true).description("cpmplus.w7rig.bend.item.desc"));
		if (!belowNames.isEmpty())rows.add(FormRow.checkbox("below", I18n.format("cpmplus.w7rig.bend.below", String.join(", ", limit(belowNames, 6))), true));
		rows.add(FormRow.select("gap", "cpmplus.w7rig.bend.gap", PlusDialogs.options("none", "cpmplus.w7rig.bend.gap.none", "joint", "cpmplus.w7rig.bend.gap.joint"), "none"));
		if (anySlim)rows.add(FormRow.info("slim", null, "cpmplus.w7rig.bend.slim"));
		rows.add(FormRow.info("limits", null, "cpmplus.w7rig.bend.limits"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w7rig.bend.ok";
		PlusDialogs.form("cpmplus_w7rig_bend", "cpmplus.w7rig.bend.title", rows, r -> {
			List<BendLimb.Plan> todo = new ArrayList<>();
			for (PlayerModelParts p : BendLimb.PARTS) {
				if (plans.get(p).error != null || !r.getBool("part_" + p.getName()))continue;
				BendLimb.Opts o = new BendLimb.Opts();
				o.root = p.getName();
				o.cut = r.getInt("cut", 6);
				String d = r.getString("dir");
				o.dir = "front".equals(d) || "back".equals(d) ? d : null;
				o.layer = r.raw("layer") == null || r.getBool("layer");
				o.moveItem = r.raw("item") == null || r.getBool("item");
				o.moveBelow = r.raw("below") == null || r.getBool("below");
				o.gap = "joint".equals(r.getString("gap")) ? "joint" : "none";
				BendLimb.Plan pl = BendLimb.plan(o);
				if (pl.error != null) {
					PlusDialogs.block(pl.errorText());
					r.keepOpen();
					return;
				}
				todo.add(pl);
			}
			if (todo.isEmpty()) {
				PlusDialogs.quick(t("cpmplus.w7rig.bend.err.nothing"), 3000);
				r.keepOpen();
				return;
			}
			BendLimb.Result res = BendLimb.apply(todo);
			List<String> names = new ArrayList<>();
			for (BendLimb.Plan pl : todo)names.add(BendLimb.partName(pl.part) + " (" + pl.cut + " px)");
			PlusDialogs.done(I18n.format("cpmplus.w7rig.bend.done", String.join(", ", names)));
			for (String w : res.warnings)PlusDialogs.toast(w, "warning", 8000, null);
			if (!res.joints.isEmpty()) {
				Group j = res.joints.get(0);
				PlusDialogs.toast(t("cpmplus.w7rig.bend.testToast"), "play_arrow", 10000, () -> BendLimb.testBend(j));
			}
		}, fo);
	}

	private static String jointWord(PlayerModelParts p) {
		return t("cpmplus.w7rig.joint" + BendLimb.jointSuffix(p));
	}

	private static List<String> limit(List<String> l, int n) {
		if (l.size() <= n)return l;
		List<String> o = new ArrayList<>(l.subList(0, n));
		o.add("+" + (l.size() - n));
		return o;
	}

	static String nameOf(OutlinerNode n) {
		Object v = Js.asPropertyMap(n).get("name");
		return v == null ? "?" : String.valueOf(v);
	}

	/** 'Desfazer membro dobrável' */
	public static void openUnbend(Group joint) {
		if (joint == null || !BendLimb.hasMark(joint)) {
			PlusDialogs.block(t("cpmplus.w7rig.unbend.err.noMark"));
			return;
		}
		if (BendLimb.animateMode()) {
			PlusDialogs.block(t("cpmplus.w7rig.bend.err.animate"));
			return;
		}
		List<String> anims = BendLimb.animationsUsing(joint);
		String msg = anims.isEmpty() ? I18n.format("cpmplus.w7rig.unbend.confirm", joint.name)
				: I18n.format("cpmplus.w7rig.unbend.confirmAnims", joint.name, String.join(", ", anims));
		PlusDialogs.confirm("cpmplus_w7rig_unbend", "cpmplus.w7rig.unbend.title", msg, () -> {
			BendLimb.unbend(joint);
			PlusDialogs.done(t("cpmplus.w7rig.unbend.done"));
		}, null);
	}

	// ====================================================================== G57

	/** 'Curvar peça…' on the selected cube */
	public static void openCurve() {
		openCurve(BendCube.selectedCube());
	}

	public static void openCurve(Cube cube) {
		if (BendLimb.animateMode()) {
			PlusDialogs.block(t("cpmplus.w7rig.bend.err.animate"));
			return;
		}
		if (cube == null) {
			PlusDialogs.block(t("cpmplus.w7rig.curve.oneCube"));
			return;
		}
		String why = BendCube.refuse(cube);
		if (why != null) {
			PlusDialogs.block(t(why));
			return;
		}
		BendCube.Opts def = new BendCube.Opts();
		JsPropertyMap<Object> p0 = BendCube.plan(cube, def);
		String dir0 = String.valueOf(p0.get("dir"));
		def.dir = dir0;
		JsArray<Double> valid0 = Js.uncheckedCast(p0.get("valid"));
		def.slices = pickDefaultN(valid0);
		if (valid0 == null || valid0.length == 0) {
			JsArray<String> errs = Js.uncheckedCast(p0.get("errors"));
			PlusDialogs.block(errs != null && errs.length > 0 ? t("cpmplus.w7rig.curve.err." + errs.getAt(0)) : t("cpmplus.w7rig.curve.err.invalidN"));
			return;
		}
		JsPropertyMap<Object> pd = BendCube.plan(cube, def);

		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w7rig.curve.intro"));
		Map<String, String> axes = new LinkedHashMap<>();
		axes.put("auto", "cpmplus.w7rig.curve.axis.auto");
		axes.put("0", "cpmplus.w7rig.curve.axis.x");
		axes.put("1", "cpmplus.w7rig.curve.axis.y");
		axes.put("2", "cpmplus.w7rig.curve.axis.z");
		rows.add(FormRow.select("axis", "cpmplus.w7rig.curve.axis", axes, "auto"));
		Map<String, String> dirs = new LinkedHashMap<>();
		for (String d : new String[] {"-z", "+z", "+x", "-x", "+y", "-y"})dirs.put(d, "cpmplus.w7rig.curve.dir." + (d.charAt(0) == '-' ? "neg" : "pos") + d.charAt(1));
		rows.add(FormRow.select("dir", "cpmplus.w7rig.curve.dir", dirs, dir0).description("cpmplus.w7rig.curve.dir.desc"));
		rows.add(FormRow.range("angle", "cpmplus.w7rig.curve.angle", 90, -180, 180, 1));
		Map<String, String> ns = new LinkedHashMap<>();
		for (int i = 1; i <= BendCube.MAX_SLICES; i++)ns.put(String.valueOf(i), String.valueOf(i));
		rows.add(FormRow.select("slices", "cpmplus.w7rig.curve.slices", ns, String.valueOf(def.slices)).description("cpmplus.w7rig.curve.slices.desc"));
		Map<String, String> piv = new LinkedHashMap<>();
		piv.put("inner", "cpmplus.w7rig.curve.pivot.inner");
		piv.put("center", "cpmplus.w7rig.curve.pivot.center");
		rows.add(FormRow.radio("pivot", "cpmplus.w7rig.curve.pivot", piv, "inner"));
		rows.add(FormRow.checkbox("close", "cpmplus.w7rig.curve.close", false).description("cpmplus.w7rig.curve.close.desc"));
		rows.add(FormRow.checkbox("flip", "cpmplus.w7rig.curve.flip", false));
		rows.add(FormRow.checkbox("live", "cpmplus.w7rig.curve.live", true));
		rows.add(FormRow.textarea("status", "cpmplus.w7rig.curve.status", BendCube.statusText(pd, def)).readonly());
		Dialog[] dlg = new Dialog[1];
		BendCube.Preview pv = new BendCube.Preview(cube);
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w7rig.curve.confirm";
		fo.onCancel = pv::drop;
		fo.onChange = r -> {
			BendCube.Opts o = from(r);
			if (pv.isShown(o) && r.getBool("live"))return;
			pv.drop();// the original cube is back (same uuid) before planning again
			JsPropertyMap<Object> p = BendCube.plan(pv.source(), o);
			String st = BendCube.statusText(p, o);
			if (dlg[0] != null && !st.equals(r.getString("status")))setStatus(dlg[0], st);
			if (r.getBool("live") && Js.isTruthy(p.get("ok")))pv.show(o);
		};
		dlg[0] = PlusDialogs.form("cpmplus_w7rig_curve", "cpmplus.w7rig.curve.title", rows, r -> {
			BendCube.Opts o = from(r);
			BendCube.Result res = pv.commit(o);
			if (!res.changed) {
				r.keepOpen();
				PlusDialogs.block(String.join("\n", res.errors));
				return;
			}
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w7rig.curve.done", o.slices, BendCube.fmt(res.gap)));
		}, fo);
	}

	private static int pickDefaultN(JsArray<Double> valid) {
		int best = 1;
		if (valid == null)return best;
		for (int i = 0; i < valid.length; i++) {
			int n = (int) (double) valid.getAt(i);
			if (n <= 6 && n > best)best = n;
		}
		return best;
	}

	private static native void setStatus(Object dialog, String txt) /*-{
		try { dialog.setFormValues({status: txt}, false); } catch (e) {}
	}-*/;

	static BendCube.Opts from(FormResult r) {
		BendCube.Opts o = new BendCube.Opts();
		o.axis = r.getString("axis") != null ? r.getString("axis") : "auto";
		o.dir = r.getString("dir") != null ? r.getString("dir") : "";
		o.angle = r.getDouble("angle", 90);
		o.slices = r.getInt("slices", 4);
		o.pivot = "center".equals(r.getString("pivot")) ? "center" : "inner";
		o.close = r.getBool("close");
		o.flip = r.getBool("flip");
		return o;
	}
}
