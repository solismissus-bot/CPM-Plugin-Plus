package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.tools.anim.AnimxUtil;
import com.tom.cpm.blockbench.tools.anim.SituationCheck;
import com.tom.cpm.shared.animation.VanillaPose;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W6-ANIMX (CPM+ v0.8). Only G39 is taken (reused from the stopped W6-ANIMX work):
 * 'Movimento que some ao trocar de situação' in CPM+ &gt; Animação + one INFO row in the export
 * (never blocks). G41 / G51 / G63 / G72 of that package are not part of the plugin.
 */
public final class W6AnimxInstall {
	private W6AnimxInstall() {
	}

	public static void install() {
		// undo saves tagged by AnimxUtil.track also clear the keys an edit added to animators that had none before
		AnimxUtil.installUndoTracking();
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w6animx_situations", "cpmplus.w6animx.menu.situations", "directions_run", Cond.CPM_ONLY, SituationCheck::open);
		ExportHooks.register(ctx -> {
			SituationCheck.Report r = SituationCheck.check();
			if (r.parts.isEmpty())return;
			WarnEntry w = WarnFactory.of(Severity.INFO, "situationGaps", "cpmplus.w6animx.sit.warn", r.parts.size(), SituationCheck.sentence(r.parts.get(0)));
			for (SituationCheck.PartReport pr : r.parts)w.addNode(pr.group.uuid, pr.group.name);
			w.setFix(() -> {
				SituationCheck.open();
				return Promise.resolve(false);
			}, FixKind.VIEW_ONLY);
			ctx.add(w);
		});
		registerTestTools();
	}

	public static void uninstall() {
	}

	// ====================================================================== test tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		return v != null && "number".equals(Js.typeof(v)) ? Js.asDouble(v) : def;
	}

	private static boolean bool(JsPropertyMap<Object> o, String k, boolean def) {
		Object v = o.get(k);
		return v == null ? def : Js.isTruthy(v);
	}

	private static List<String> list(JsPropertyMap<Object> o, String k) {
		List<String> l = new ArrayList<>();
		Object v = o.get(k);
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)l.add(String.valueOf(a.getAt(i)));
		}
		return l;
	}

	private static Group group(JsPropertyMap<Object> o, String k) {
		Group g = AnimxUtil.groupByUuidOrName(str(o, k, null));
		if (g == null)throw new IllegalArgumentException("no group " + str(o, k, null));
		return g;
	}

	private static SituationCheck.PartReport part(JsPropertyMap<Object> o) {
		Group g = group(o, "group");
		SituationCheck.Report r = SituationCheck.check();
		SituationCheck.PartReport pr = SituationCheck.find(r, g);
		if (pr == null)throw new IllegalArgumentException("group not reported: " + g.name);
		return pr;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w6animx.situationTable", o -> {
			Set<VanillaPose> present = EnumSet.noneOf(VanillaPose.class);
			for (String s : list(o, "present"))present.add(VanillaPose.valueOf(s));
			JsPropertyMap<Object> r = AnimxUtil.map();
			List<Object> t = new ArrayList<>();
			for (VanillaPose p : SituationCheck.situationTable(present))t.add(p.name());
			r.set("table", AnimxUtil.arr(t));
			Object st = o.get("state");
			if (st != null) {
				JsPropertyMap<Object> s = Js.uncheckedCast(st);
				SituationCheck.State state = new SituationCheck.State();
				state.sleeping = bool(s, "sleeping", false);
				state.dying = bool(s, "dying", false);
				state.elytraFlying = bool(s, "elytraFlying", false);
				state.tridentSpin = bool(s, "tridentSpin", false);
				state.riding = bool(s, "riding", false);
				state.creativeFlying = bool(s, "creativeFlying", false);
				state.crawling = bool(s, "crawling", false);
				state.swimming = bool(s, "swimming", false);
				state.retroSwimming = bool(s, "retroSwimming", false);
				state.onLadder = bool(s, "onLadder", false);
				state.jumping = bool(s, "jumping", false);
				state.sneaking = bool(s, "sneaking", false);
				state.sprinting = bool(s, "sprinting", false);
				state.falling = (int) num(s, "falling", 0);
				state.moveX = (float) num(s, "moveX", 0);
				state.moveY = (float) num(s, "moveY", 0);
				state.moveZ = (float) num(s, "moveZ", 0);
				r.set("main", SituationCheck.mainPose(state, present).name());
			}
			return r;
		});
		PlusTestHooks.register("w6animx.checkSituations", o -> SituationCheck.toJson(SituationCheck.check()));
		PlusTestHooks.register("w6animx.moveToGlobal", o -> {
			SituationCheck.MoveResult m = SituationCheck.moveToGlobal(part(o));
			JsPropertyMap<Object> r = AnimxUtil.map();
			r.set("ok", m.ok);
			r.set("error", m.error);
			r.set("global", m.global != null ? m.global.name : null);
			r.set("created", m.created);
			r.set("movedKeys", m.movedKeys);
			return r;
		});
		PlusTestHooks.register("w6animx.moveGlobalPreview", o -> {
			SituationCheck.Report rep = SituationCheck.check();
			SituationCheck.PartReport pr = SituationCheck.find(rep, group(o, "group"));
			JsPropertyMap<Object> r = AnimxUtil.map();
			List<Object> nm = new ArrayList<>(), cf = new ArrayList<>();
			if (pr != null) {
				for (VanillaPose p : SituationCheck.newlyMoving(pr, rep))nm.add(p.name());
				for (Animation a : SituationCheck.conflicts(pr))cf.add(a.name);
			}
			r.set("newlyMoving", AnimxUtil.arr(nm));
			r.set("conflicts", AnimxUtil.arr(cf));
			return r;
		});
		PlusTestHooks.register("w6animx.copyToMissing", o -> {
			SituationCheck.PartReport pr = part(o);
			List<VanillaPose> t = new ArrayList<>();
			List<String> ts = list(o, "targets");
			if (ts.isEmpty())t.addAll(pr.missing);
			else for (String s : ts)t.add(VanillaPose.valueOf(s));
			SituationCheck.CopyResult c = SituationCheck.copyToMissing(pr, t, bool(o, "adjust", false));
			JsPropertyMap<Object> r = AnimxUtil.map();
			r.set("ok", c.ok);
			r.set("error", c.error);
			r.set("source", c.source != null ? c.source.name : null);
			List<Object> cr = new ArrayList<>(), fi = new ArrayList<>(), ld = new ArrayList<>();
			for (Animation a : c.created)cr.add(a.name + "|" + a.type + "|" + a.loop);
			for (Animation a : c.filled)fi.add(a.name);
			for (Animation a : c.lengthDiffers)ld.add(a.name);
			r.set("created", AnimxUtil.arr(cr));
			r.set("filled", AnimxUtil.arr(fi));
			r.set("lengthDiffers", AnimxUtil.arr(ld));
			r.set("copiedKeys", c.copiedKeys);
			return r;
		});
		PlusTestHooks.register("w6animx.sitIgnore", o -> {
			SituationCheck.setIgnored(group(o, "group"), bool(o, "ignore", true), false);
			JsPropertyMap<Object> r = AnimxUtil.map();
			r.set("ignored", AnimxUtil.arr(new ArrayList<Object>(SituationCheck.ignored())));
			r.set("pluginData", Project.pluginData);
			return r;
		});
	}
}
