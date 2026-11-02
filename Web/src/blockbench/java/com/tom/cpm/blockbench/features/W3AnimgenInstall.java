package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.tools.anim.AnimGenBB;
import com.tom.cpm.blockbench.tools.anim.BlinkGenerator;
import com.tom.cpm.blockbench.tools.anim.BlinkGenerator.Schedule;
import com.tom.cpm.blockbench.tools.anim.BlinkTool;
import com.tom.cpm.blockbench.tools.anim.ColorSlider;
import com.tom.cpm.blockbench.tools.anim.ColorSliderTool;
import com.tom.cpm.blockbench.tools.anim.MotionPresets;
import com.tom.cpm.blockbench.tools.anim.MotionPresets.Preset;
import com.tom.cpm.blockbench.tools.anim.MotionPresets.Spec;
import com.tom.cpm.blockbench.tools.anim.MotionTool;
import com.tom.cpm.blockbench.tools.anim.SpinGuard;
import com.tom.cpm.shared.editor.anim.EditorAnim;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W3-ANIMGEN (CPM+ v0.8): animation generators.
 * <ul>
 * <li>F4 'Gerar piscada…' (CPM+ &gt; Animação and the group's right-click menu): {@link BlinkTool}.</li>
 * <li>F8 + G24 'Gerar movimento pronto…' (respirar, flutuar, bater asas, balançar cauda, pêndulo, tremer, girar sem
 * parar): {@link MotionTool}; the spin speed guard is also an export check ({@link SpinGuard}).</li>
 * <li>F22 'Criar seletor de cor…' (selected groups): {@link ColorSliderTool}.</li>
 * </ul>
 * Test mode only: tools 'w3animgen.f4' / 'w3animgen.f8' / 'w3animgen.f22' (the same cores as the dialogs, no dialog)
 * and 'w3animgen.spin' (the export's spin findings / Corrigir).
 */
public final class W3AnimgenInstall {
	private W3AnimgenInstall() {
	}

	public static void install() {
		Action blink = PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w3animgen_blink", "cpmplus.w3animgen.menu.blink", "visibility", Cond.CPM_ONLY,
				() -> BlinkTool.open(AnimGenBB.selectedGroup()));
		Action motion = PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w3animgen_motion", "cpmplus.w3animgen.menu.motion", "auto_awesome_motion", Cond.CPM_ONLY,
				() -> MotionTool.open(AnimGenBB.selectedGroup()));
		Action color = PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w3animgen_color", "cpmplus.w3animgen.menu.color", "palette", Cond.CPM_ONLY,
				ColorSliderTool::open);
		Menu gm = groupMenu();
		if (gm != null) {
			PlusMenus.addToContext(gm, blink, "");
			PlusMenus.addToContext(gm, motion, "");
			PlusMenus.addToContext(gm, color, "");
		}
		ExportHooks.register(SpinGuard::check);
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	private static native Menu groupMenu() /*-{
		return $wnd.Group && $wnd.Group.prototype && $wnd.Group.prototype.menu ? $wnd.Group.prototype.menu : null;
	}-*/;

	// ====================================================================== test tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		return v != null && "number".equals(Js.typeof(v)) ? Js.asDouble(v) : def;
	}

	private static boolean bool(JsPropertyMap<Object> o, String k) {
		return Js.isTruthy(o.get(k));
	}

	private static JsPropertyMap<Object> ok() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}

	private static JsPropertyMap<Object> fail(String msg) {
		JsPropertyMap<Object> r = ok();
		r.set("ok", false);
		Js.<JsArray<Object>>uncheckedCast(r.get("errors")).push(msg);
		return r;
	}

	private static Group part(JsPropertyMap<Object> o, String k) {
		String p = str(o, k, null);
		return p == null || p.isEmpty() ? null : AnimGenBB.findByPath(p);
	}

	/** keys of one channel of a group: [[time, x, y, z, interpolation] ...] or [[time, visible]] */
	private static JsArray<Object> keys(Animation a, Group g, String ch) {
		JsArray<Object> out = new JsArray<>();
		BoneAnimator ba = AnimGenBB.existingAnimator(a, g);
		if (ba == null)return out;
		Object arr = ba.channel(ch);
		if (arr == null)return out;
		JsArray<Keyframe> k = Js.uncheckedCast(arr);
		List<Keyframe> l = new ArrayList<>();
		for (int i = 0; i < k.length; i++)l.add(k.getAt(i));
		l.sort((x, y) -> Float.compare(x.time, y.time));
		for (Keyframe kf : l) {
			JsArray<Object> e = new JsArray<>();
			e.push((double) kf.time);
			JsPropertyMap<Object> dp = Js.uncheckedCast(kf.data_points.getAt(0));
			if ("cpm_visibility".equals(ch)) {
				e.push(kf.data_points.getAt(0).getVisible());
			} else {
				e.push(dp.get("x"));
				e.push(dp.get("y"));
				e.push(dp.get("z"));
				if ("cpm_color".equals(ch))e.push(dp.get("cpm_color_picker_place"));
			}
			e.push(kf.interpolation);
			out.push(e);
		}
		return out;
	}

	private static JsPropertyMap<Object> animInfo(Animation a) {
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("name", a.name);
		m.set("uuid", a.uuid);
		m.set("type", a.type);
		m.set("loop", a.loop);
		m.set("additive", a.additive);
		m.set("length", (double) a.length);
		m.set("snapping", (double) a.snapping);
		m.set("maxValue", (double) a.getMaxValue());
		m.set("interpolateVal", a.isInterpolateVal());
		m.set("layerDefault", (double) a.getLayerDefault());
		return m;
	}

	private static void warnings(JsPropertyMap<Object> r, List<String> w) {
		JsArray<Object> a = Js.uncheckedCast(r.get("warnings"));
		for (String s : w)a.push(s);
	}

	private static void registerTestTools() {
		// ---------------------------------------------------------------- F4
		PlusTestHooks.register("w3animgen.f4", o -> {
			Group open = part(o, "open"), closed = part(o, "closed");
			double every = num(o, "every", 3), dur = num(o, "dur", 0.15);
			int fps = (int) num(o, "fps", 20);
			boolean random = bool(o, "random"), dbl = bool(o, "double");
			long seed = (long) num(o, "seed", 1);
			JsPropertyMap<Object> r = ok();
			r.set("tooShort", BlinkGenerator.tooShort(dur, fps));
			r.set("betterFps", (double) betterFps(every, dur, random, dbl, seed));
			if (bool(o, "checkOnly"))return r;
			Schedule s;
			try {
				s = BlinkGenerator.schedule(every, dur, fps, random, dbl, seed);
			} catch (IllegalArgumentException e) {
				return fail(AnimGenBB.tr("cpmplus.w3animgen.blink.err.every"));
			}
			BlinkTool.Result res;
			try {
				res = BlinkTool.apply(open, closed, s, str(o, "name", null));
			} catch (IllegalArgumentException e) {
				return fail(e.getMessage());
			}
			r.set("changed", true);
			r.set("animation", animInfo(res.animation));
			r.set("open", open != null ? keys(res.animation, open, "cpm_visibility") : new JsArray<>());
			r.set("closed", closed != null ? keys(res.animation, closed, "cpm_visibility") : new JsArray<>());
			r.set("closedHidden", closed != null && closed.hidden);
			r.set("lengthFrames", (double) s.lengthFrames);
			r.set("blinks", (double) s.blinks.size());
			r.set("exportFrames", (double) s.exportFrames());
			r.set("schedule", s.toString());
			warnings(r, res.warnings);
			return r;
		});

		// ---------------------------------------------------------------- F8 + G24
		PlusTestHooks.register("w3animgen.f8", o -> {
			Preset pr = Preset.byId(str(o, "preset", "respirar"));
			if (pr == null)return fail("unknown preset");
			Spec s = new Spec(pr);
			MotionTool.Plan p = new MotionTool.Plan();
			p.spec = s;
			p.part = part(o, "part");
			String target = str(o, "target", "new");
			p.target = "new".equals(target) ? null : "selected".equals(target) ? Animation.selected : AnimGenBB.animNamed(target);
			if (!"new".equals(target) && p.target == null)return fail("no animation " + target);
			s.length = p.target != null ? p.target.length : num(o, "length", 2);
			s.fps = p.target != null ? (p.target.snapping > 0 ? p.target.snapping : 20) : (int) num(o, "fps", 20);
			String in = str(o, "intensity", "M");
			s.amplitude = num(o, "amplitude", pr.amplitude(in.isEmpty() ? 'M' : in.charAt(0)));
			s.cycles = (int) num(o, "cycles", 1);
			s.phase = num(o, "phase", 0);
			String ax = str(o, "axis", null);
			if (ax != null && !ax.isEmpty())s.axis = ax.charAt(0);
			if (pr.isSpin()) {
				s.cycles = (int) num(o, "turns", 1);
				s.direction = "ccw".equals(str(o, "dir", "cw")) ? 1 : -1;
			}
			p.newType = str(o, "type", "global");
			p.newName = str(o, "name", null);
			p.replace = bool(o, "replace");
			p.makeLoop = bool(o, "makeLoop");
			p.spinGroup = bool(o, "spinGroup");
			p.snapping = (int) num(o, "snapping", 0);
			if (p.snapping > 0)s.fps = p.snapping;
			if (pr == Preset.BATER_ASAS && p.part != null) {
				String pv = str(o, "pair", "auto");
				p.pair = "auto".equals(pv) ? AnimGenBB.pairOf(p.part) : pv.isEmpty() ? null : AnimGenBB.findByPath(pv);
			}
			JsPropertyMap<Object> r = ok();
			if (pr.isSpin() && p.part != null) {
				double step = MotionPresets.spinExportStep(s.length, s.fps, s.cycles);
				r.set("spinStep", step);
				r.set("spinFrames", (double) MotionPresets.spinExportFrames(s.length, s.fps, s.cycles));
				r.set("spinFix", (double) MotionPresets.pickSnapping(s.length, s.cycles, s.fps, p.target != null ? AnimGenBB.allowedSnappings(p.target) : MotionPresets.spinSnappings(s.length, s.cycles)));
				r.set("pivotOffset", MotionTool.pivotOffset(p.part, s.axis));
				r.set("restTilt", MotionTool.restTilt(p.part, s.axis));
				String dw = SpinGuard.dialogWarning(p.part.name, step, (int) Js.asDouble(r.get("spinFix")));
				if (dw != null)r.set("speedWarning", dw);
			}
			if (p.pair != null)r.set("pair", AnimGenBB.path(p.pair));
			if (bool(o, "checkOnly")) {
				String pb = MotionTool.problem(p);
				if (pb != null)Js.<JsArray<Object>>uncheckedCast(r.get("errors")).push(pb);
				return r;
			}
			MotionTool.Result res;
			try {
				res = MotionTool.apply(p);
			} catch (IllegalArgumentException e) {
				JsPropertyMap<Object> f = fail(e.getMessage());
				f.set("pair", p.pair != null ? AnimGenBB.path(p.pair) : null);
				return f;
			}
			r.set("changed", true);
			r.set("animation", animInfo(res.animation));
			Group g = res.spun != null ? res.spun : p.part;
			r.set("animated", AnimGenBB.path(g));
			r.set("keys", keys(res.animation, g, pr.channel));
			if (p.pair != null)r.set("pairKeys", keys(res.animation, p.pair, pr.channel));
			if (res.spun != null)r.set("spunOrigin", new JsArray<>((double) res.spun.origin.x, (double) res.spun.origin.y, (double) res.spun.origin.z));
			warnings(r, res.warnings);
			return r;
		});

		// ---------------------------------------------------------------- F22
		PlusTestHooks.register("w3animgen.f22", o -> {
			List<Group> parts = new ArrayList<>();
			Object pa = o.get("parts");
			if (pa != null && JsArray.isArray(pa)) {
				JsArray<Object> arr = Js.uncheckedCast(pa);
				for (int i = 0; i < arr.length; i++) {
					Group g = AnimGenBB.findByPath(String.valueOf(arr.getAt(i)));
					if (g == null)return fail("no part " + arr.getAt(i));
					parts.add(g);
				}
			}
			List<int[]> cols = new ArrayList<>();
			Object ca = o.get("colors");
			if (ca != null && JsArray.isArray(ca)) {
				JsArray<Object> arr = Js.uncheckedCast(ca);
				for (int i = 0; i < arr.length; i++) {
					int[] c = ColorSlider.parseHex(String.valueOf(arr.getAt(i)));
					if (c == null)return fail("bad colour " + arr.getAt(i));
					cols.add(c);
				}
			}
			JsPropertyMap<Object> r = ok();
			JsArray<Object> nr = new JsArray<>();
			for (Group g : ColorSliderTool.notRecolorable(parts))nr.push(AnimGenBB.path(g));
			r.set("notRecolorable", nr);
			if (bool(o, "checkOnly")) {
				String pb = ColorSliderTool.problem(parts, cols.size(), bool(o, "enableRecolor"));
				if (pb != null)Js.<JsArray<Object>>uncheckedCast(r.get("errors")).push(pb);
				return r;
			}
			ColorSliderTool.Result res;
			try {
				res = ColorSliderTool.apply(parts, str(o, "name", null), cols, bool(o, "smooth"), (int) num(o, "start", 0), bool(o, "enableRecolor"));
			} catch (IllegalArgumentException e) {
				return fail(e.getMessage());
			}
			r.set("changed", true);
			r.set("animation", animInfo(res.animation));
			JsPropertyMap<Object> k = JsPropertyMap.of();
			for (Group g : parts)k.set(AnimGenBB.path(g), keys(res.animation, g, "cpm_color"));
			r.set("keys", k);
			JsArray<Object> rc = new JsArray<>();
			for (Cube c : res.recolored)rc.push(c.name);
			r.set("recolored", rc);
			warnings(r, res.warnings);
			return r;
		});

		// ---------------------------------------------------------------- G24 export check / Corrigir
		PlusTestHooks.register("w3animgen.spin", o -> ProjectConvert.convertHeadless().then(h -> {
			JsPropertyMap<Object> r = ok();
			JsArray<Object> rows = new JsArray<>();
			WarnEntry first = null;
			for (WarnEntry w : h.warnings) {
				if (!SpinGuard.WARN_KEY.equals(w.getKey()))continue;
				if (first == null)first = w;
				JsPropertyMap<Object> e = JsPropertyMap.of();
				e.set("message", w.getMessage());
				e.set("severity", w.getSeverity().name());
				e.set("canFix", w.canQuickFix());
				e.set("fixPreview", w.getFixPreview());
				e.set("nodes", String.join(",", w.getNames()));
				rows.push(e);
			}
			r.set("rows", rows);
			JsPropertyMap<Object> frames = JsPropertyMap.of();
			for (Animation a : Animation.all) {
				EditorAnim ea = h.exp.getExportedAnim(a.uuid);
				if (ea == null)continue;
				JsPropertyMap<Object> f = JsPropertyMap.of();
				f.set("frames", (double) ea.getFrames().size());
				f.set("intType", ea.intType.name());
				f.set("duration", (double) ea.duration);
				frames.set(a.name, f);
			}
			r.set("exported", frames);
			if (bool(o, "fix") && first != null && first.canQuickFix()) {
				return first.runQuickFix().then(done -> {
					r.set("fixed", done);
					r.set("changed", done);
					return Promise.resolve(r);
				});
			}
			return Promise.resolve(r);
		}));
	}

	private static int betterFps(double every, double dur, boolean random, boolean dbl, long seed) {
		return BlinkTool.betterFps(every, dur, random, dbl, seed);
	}
}
