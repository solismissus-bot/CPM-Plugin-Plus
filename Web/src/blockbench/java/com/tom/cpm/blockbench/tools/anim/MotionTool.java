package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.tools.anim.MotionPresets.Key;
import com.tom.cpm.blockbench.tools.anim.MotionPresets.Preset;
import com.tom.cpm.blockbench.tools.anim.MotionPresets.Spec;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;

import jsinterop.base.Js;

/**
 * CPM+ (W3-ANIMGEN, F8 'Gerar movimento pronto…' + G24 'Girar sem parar'): ready-made motions written as keys
 * (CPM only exports numbers, never molang). One undo entry per generation: the new animation (or the keys in the
 * selected one), the mirrored keys on the paired wing, the switch to loop and the centred spin group.
 */
public final class MotionTool {
	private MotionTool() {
	}

	/** everything one generation does */
	public static final class Plan {
		public Spec spec;
		public Group part;
		/** bater asas: the wing on the other side (mirrored keys), may be null */
		public Group pair;
		/** null = a new animation */
		public Animation target;
		public String newName, newType = "global";
		public boolean additive = true;
		/** the target animation becomes a loop */
		public boolean makeLoop;
		/** existing keys of the channel are replaced (otherwise they block the generation) */
		public boolean replace;
		/** spin: wrap the part's contents in a new group whose pivot is the visual centre, and spin that group */
		public boolean spinGroup;
		/** snapping set on the animation ('Corrigir' of the speed guard), 0 = keep */
		public int snapping;
	}

	public static final class Result {
		public Animation animation;
		public Group spun;
		public int keys;
		public final List<String> warnings = new ArrayList<>();
	}

	/** reason (translated) why the plan can't be generated, or null */
	public static String problem(Plan p) {
		if (p.part == null)return AnimGenBB.tr("cpmplus.w3animgen.motion.err.noPart");
		String sp = MotionPresets.problem(p.spec);
		if (sp != null)return AnimGenBB.tr("cpmplus.w3animgen.motion.err.spec");
		if (p.spec.preset.usesScale() && AnimGenBB.isRoot(p.part))return AnimGenBB.tr("cpmplus.w3animgen.motion.err.rootScale");
		if (p.pair != null && p.spec.preset.usesScale() && AnimGenBB.isRoot(p.pair))return AnimGenBB.tr("cpmplus.w3animgen.motion.err.rootScale");
		if (p.pair != null && (p.pair == p.part || AnimGenBB.isSameOrInside(p.pair, p.part) || AnimGenBB.isSameOrInside(p.part, p.pair)))
			return AnimGenBB.tr("cpmplus.w3animgen.motion.err.pairInside");
		if (p.target != null && !"loop".equals(p.target.loop) && !p.makeLoop)return AnimGenBB.tr("cpmplus.w3animgen.motion.err.notLoop");
		if (!p.replace && p.target != null) {
			String ch = p.spec.preset.channel;
			if (AnimGenBB.hasKeys(p.target, p.part, ch) || (p.pair != null && AnimGenBB.hasKeys(p.target, p.pair, ch)))
				return AnimGenBB.trf("cpmplus.w3animgen.motion.err.hasKeys", p.part.name);
		}
		return null;
	}

	/** Core (dialog and test hook): ONE undo entry. Throws IllegalArgumentException with a user message. */
	public static Result apply(Plan p) {
		String pr = problem(p);
		if (pr != null)throw new IllegalArgumentException(pr);
		Result r = new Result();
		String ch = p.spec.preset.channel;
		List<Key> keys = MotionPresets.generate(p.spec);
		List<Key> mirrored = p.pair != null ? MotionPresets.mirror(ch, keys) : null;
		UndoData before = p.spinGroup ? PlusUndo.structBefore(p.part) : new UndoData();
		before.animations = p.target != null ? new Animation[] {p.target} : new Animation[0];
		// moved nodes keep their uuids: only the outliner (hierarchy) and the new group go into the entry (listing the
		// moved cubes as 'after' elements would make Ctrl+Z delete them)
		String label = AnimGenBB.trf("cpmplus.w3animgen.motion.undo", AnimGenBB.tr("cpmplus.w3animgen.preset." + p.spec.preset.id));
		PlusUndo.run(label, before, () -> {
			UndoData a = p.spinGroup ? PlusUndo.structAfter(new Group[] {p.part, r.spun}, new OutlinerElement[0], null) : new UndoData();
			a.animations = r.animation != null ? new Animation[] {r.animation} : new Animation[0];
			return a;
		}, () -> {
			Animation an = p.target;
			if (an == null) {
				String nm = p.newName != null && !p.newName.trim().isEmpty() ? p.newName : AnimGenBB.tr("cpmplus.w3animgen.preset." + p.spec.preset.id);
				an = AnimGenBB.newAnimation(nm, p.newType != null ? p.newType : "global", p.additive, p.spec.length, p.spec.fps);
			} else if (p.makeLoop)an.loop = "loop";
			if (p.snapping > 0)an.snapping = p.snapping;
			r.animation = an;
			Group g = p.part;
			if (p.spinGroup) {
				g = makeSpinGroup(p.part);
				r.spun = g;
			}
			if (p.replace) {
				AnimGenBB.clearChannel(an, g, ch);
				if (p.pair != null)AnimGenBB.clearChannel(an, p.pair, ch);
			}
			r.keys += write(an, g, ch, keys);
			if (p.pair != null)r.keys += write(an, p.pair, ch, mirrored);
		});
		if (p.target == null && AnimGenBB.usesLayers(r.animation)) {
			LayerCapacity.Result lc = LayerCapacity.checkProject();
			if (!lc.ok())r.warnings.add(AnimGenBB.trf("cpmplus.w3animgen.common.layerCapacity", lc.count, lc.freeLayers));
		}
		return r;
	}

	private static int write(Animation an, Group g, String ch, List<Key> keys) {
		BoneAnimator ba = AnimGenBB.animator(an, g);
		for (Key k : keys)AnimGenBB.addKey(ba, ch, k.t, k.interp, AnimGenBB.xyz(k.x, k.y, k.z));
		return keys.size();
	}

	// ------------------------------------------------------------------ spin helpers

	/** visual centre (bounding box of every cube inside), or null when the group has no cube */
	public static double[] visualCentre(Group g) {
		List<Cube> cubes = AnimGenBB.allCubes(g);
		if (cubes.isEmpty())return null;
		double[] mn = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, mx = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (Cube c : cubes) {
			double[] f = {c.from.x, c.from.y, c.from.z}, t = {c.to.x, c.to.y, c.to.z};
			for (int i = 0; i < 3; i++) {
				mn[i] = Math.min(mn[i], Math.min(f[i], t[i]));
				mx[i] = Math.max(mx[i], Math.max(f[i], t[i]));
			}
		}
		return new double[] {(mn[0] + mx[0]) / 2, (mn[1] + mx[1]) / 2, (mn[2] + mx[2]) / 2};
	}

	/** distance (px) of the pivot from the visual centre, measured across the spin axis */
	public static double pivotOffset(Group g, char axis) {
		double[] c = visualCentre(g);
		if (c == null)return 0;
		double dx = g.origin.x - c[0], dy = g.origin.y - c[1], dz = g.origin.z - c[2];
		switch (axis) {
		case 'x': return Math.sqrt(dy * dy + dz * dz);
		case 'z': return Math.sqrt(dx * dx + dy * dy);
		default: return Math.sqrt(dx * dx + dz * dz);
		}
	}

	/** the part rests tilted on another axis than the spin axis (spinning it would wobble around the parent's axis) */
	public static boolean restTilt(Group g, char axis) {
		JsVec3 r = g.rotation;
		if (r == null)return false;
		return (axis != 'x' && Math.abs(r.x) > 1e-4) || (axis != 'y' && Math.abs(r.y) > 1e-4) || (axis != 'z' && Math.abs(r.z) > 1e-4);
	}

	/** inside the undo edit: a new child group '<name>_giro' with its pivot on the visual centre holding the part's contents */
	private static Group makeSpinGroup(Group part) {
		double[] c = visualCentre(part);
		if (c == null)c = new double[] {part.origin.x, part.origin.y, part.origin.z};
		GroupProperties gp = new GroupProperties();
		gp.name = part.name + "_giro";
		gp.origin = JsVec3.make((float) c[0], (float) c[1], (float) c[2]);
		gp.rotation = JsVec3.make(0, 0, 0);
		Group ng = new Group(gp).init();
		List<OutlinerNode> kids = new ArrayList<>();
		for (int i = 0; i < part.children.length; i++)kids.add(part.children.getAt(i));
		ng.addTo(part);
		for (OutlinerNode n : kids)moveInto(n, ng);
		ng.createUniqueName(Js.uncheckedCast(Group.all));
		return ng;
	}

	private static native void moveInto(Object node, Group target) /*-{
		node.addTo(target);
	}-*/;

	// ------------------------------------------------------------------ dialog

	private static Map<String, String> presetOptions() {
		Map<String, String> m = new LinkedHashMap<>();
		for (Preset p : Preset.values())m.put(p.id, AnimGenBB.tr("cpmplus.w3animgen.preset." + p.id) + (p.isSpin() ? " " + AnimGenBB.tr("cpmplus.w3animgen.common.experimental") : ""));
		return m;
	}

	public static void open(Group from) {
		Group part = from != null ? from : AnimGenBB.selectedGroup();
		Animation sel = Animation.selected;
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w3animgen.motion.intro"));
		rows.add(FormRow.select("preset", "cpmplus.w3animgen.motion.preset", presetOptions(), PlusPrefs.get("cpmplus.motionPreset", "respirar")));
		rows.add(FormRow.select("part", "cpmplus.w3animgen.motion.part", AnimGenBB.groupOptions(null), part != null && !AnimGenBB.isRoot(part) ? part.uuid : null));
		Map<String, String> pairOpts = new LinkedHashMap<>();
		pairOpts.put("auto", "cpmplus.w3animgen.motion.pair.auto");
		pairOpts.putAll(AnimGenBB.groupOptions("cpmplus.w3animgen.common.none"));
		rows.add(FormRow.select("pair", "cpmplus.w3animgen.motion.pair", pairOpts, "auto").when(r -> "bater_asas".equals(r.getString("preset"))));
		rows.add(FormRow.radio("intensity", "cpmplus.w3animgen.motion.intensity", PlusDialogs.options("S", "cpmplus.w3animgen.motion.intensity.s", "M", "cpmplus.w3animgen.motion.intensity.m", "L", "cpmplus.w3animgen.motion.intensity.l"), "M")
				.when(r -> !"girar".equals(r.getString("preset"))));
		rows.add(FormRow.number("cycles", "cpmplus.w3animgen.motion.cycles", 1, 1, MotionPresets.MAX_CYCLES).description("cpmplus.w3animgen.motion.cycles.desc")
				.when(r -> !"girar".equals(r.getString("preset")) && !"tremer".equals(r.getString("preset"))));
		rows.add(FormRow.number("phase", "cpmplus.w3animgen.motion.phase", 0, 0, 100).when(r -> !"girar".equals(r.getString("preset")) && !"tremer".equals(r.getString("preset"))));
		rows.add(FormRow.select("penduloAxis", "cpmplus.w3animgen.motion.penduloAxis", PlusDialogs.options("x", "cpmplus.w3animgen.motion.axis.frente", "z", "cpmplus.w3animgen.motion.axis.lado"), "x")
				.when(r -> "pendulo".equals(r.getString("preset"))));
		rows.add(FormRow.select("spinAxis", "cpmplus.w3animgen.spin.axis", PlusDialogs.options("y", "cpmplus.w3animgen.spin.axis.y", "x", "cpmplus.w3animgen.spin.axis.x", "z", "cpmplus.w3animgen.spin.axis.z"),
				PlusPrefs.get("cpmplus.spinAxis", "y")).when(r -> "girar".equals(r.getString("preset"))));
		rows.add(FormRow.number("turns", "cpmplus.w3animgen.spin.turns", (int) PlusPrefs.getNum("cpmplus.spinTurns", 1), 1, MotionPresets.MAX_TURNS).when(r -> "girar".equals(r.getString("preset"))));
		rows.add(FormRow.radio("dir", "cpmplus.w3animgen.spin.dir", PlusDialogs.options("cw", "cpmplus.w3animgen.spin.dir.cw", "ccw", "cpmplus.w3animgen.spin.dir.ccw"), PlusPrefs.get("cpmplus.spinDir", "cw"))
				.when(r -> "girar".equals(r.getString("preset"))));
		rows.add(FormRow.checkbox("advanced", "cpmplus.w3animgen.motion.advanced", false).when(r -> !"girar".equals(r.getString("preset"))));
		rows.add(FormRow.decimal("amplitude", "cpmplus.w3animgen.motion.amplitude", 0, 0, 360, 0.01).description("cpmplus.w3animgen.motion.amplitude.desc")
				.when(r -> r.getBool("advanced") && !"girar".equals(r.getString("preset"))));
		Map<String, String> where = new LinkedHashMap<>();
		where.put("new", "cpmplus.w3animgen.motion.target.new");
		if (sel != null)where.put("selected", AnimGenBB.trf("cpmplus.w3animgen.motion.target.selected", sel.name));
		rows.add(FormRow.select("target", "cpmplus.w3animgen.motion.target", where, "new"));
		rows.add(FormRow.select("type", "cpmplus.w3animgen.motion.type", AnimGenBB.typeOptions(), "global").when(r -> !"selected".equals(r.getString("target"))));
		rows.add(FormRow.decimal("length", "cpmplus.w3animgen.motion.length", PlusPrefs.getNum("cpmplus.motionLength", 2), 0.1, 30, 0.05).when(r -> !"selected".equals(r.getString("target"))));
		rows.add(FormRow.number("fps", "cpmplus.w3animgen.common.fps", 20, 1, 120).description("cpmplus.w3animgen.common.fps.desc").when(r -> !"selected".equals(r.getString("target"))));
		FormOpts o = new FormOpts();
		o.confirmKey = "cpmplus.w3animgen.motion.create";
		PlusDialogs.form("cpmplus_w3animgen_motion", "cpmplus.w3animgen.motion.title", rows, r -> {
			Plan p = planFrom(r, sel);
			if (p.part == null) {
				r.keepOpen();
				PlusDialogs.block(AnimGenBB.tr("cpmplus.w3animgen.motion.err.noPart"));
				return;
			}
			PlusPrefs.set("cpmplus.motionPreset", p.spec.preset.id);
			if (p.target == null)PlusPrefs.set("cpmplus.motionLength", p.spec.length);
			if (p.spec.preset.isSpin()) {
				PlusPrefs.set("cpmplus.spinAxis", String.valueOf(p.spec.axis));
				PlusPrefs.set("cpmplus.spinTurns", p.spec.cycles);
				PlusPrefs.set("cpmplus.spinDir", p.spec.direction < 0 ? "cw" : "ccw");
			}
			if (p.spec.preset.usesScale() && AnimGenBB.isRoot(p.part)) {
				r.keepOpen();
				PlusDialogs.block(AnimGenBB.tr("cpmplus.w3animgen.motion.err.rootScale"));
				return;
			}
			askLoop(p);
		}, o);
	}

	static Plan planFrom(FormResult r, Animation sel) {
		Preset pr = Preset.byId(r.getString("preset"));
		if (pr == null)pr = Preset.RESPIRAR;
		Spec s = new Spec(pr);
		Plan p = new Plan();
		p.spec = s;
		p.part = AnimGenBB.byUuid(r.getString("part"));
		boolean useSel = "selected".equals(r.getString("target")) && sel != null;
		p.target = useSel ? sel : null;
		s.length = useSel ? SpinGuard.exportLength(sel) : r.getDouble("length", 2);
		s.fps = useSel ? (sel.snapping > 0 ? sel.snapping : 20) : Math.max(1, Math.min(120, r.getInt("fps", 20)));
		p.newType = r.getString("type") != null && !r.getString("type").isEmpty() ? r.getString("type") : "global";
		String in = r.getString("intensity");
		s.amplitude = pr.amplitude(in != null && !in.isEmpty() ? in.charAt(0) : 'M');
		if (r.getBool("advanced") && r.getDouble("amplitude", 0) > 0)s.amplitude = r.getDouble("amplitude", s.amplitude);
		s.cycles = Math.max(1, Math.min(MotionPresets.MAX_CYCLES, r.getInt("cycles", 1)));
		s.phase = Math.max(0, Math.min(100, r.getInt("phase", 0))) / 100.0;
		if (pr == Preset.PENDULO)s.axis = "z".equals(r.getString("penduloAxis")) ? 'z' : 'x';
		if (pr.isSpin()) {
			String ax = r.getString("spinAxis");
			s.axis = ax != null && !ax.isEmpty() ? ax.charAt(0) : 'y';
			s.cycles = Math.max(1, Math.min(MotionPresets.MAX_TURNS, r.getInt("turns", 1)));
			s.direction = "ccw".equals(r.getString("dir")) ? 1 : -1;
		}
		if (pr == Preset.BATER_ASAS && p.part != null) {
			String pv = r.getString("pair");
			p.pair = pv == null || "auto".equals(pv) ? AnimGenBB.pairOf(p.part) : AnimGenBB.byUuid(pv);
		}
		return p;
	}

	// the questions, one after the other: loop -> existing keys -> spin pivot / tilt -> spin speed -> generate

	private static void askLoop(Plan p) {
		if (p.target != null && !"loop".equals(p.target.loop)) {
			PlusDialogs.messageBox("cpmplus.w3animgen.motion.title", AnimGenBB.trf("cpmplus.w3animgen.motion.askLoop", p.target.name),
					new String[] {"cpmplus.w3animgen.motion.makeLoop", "cpmplus.w0binfra.btn.cancel"}, b -> {
						if (b != 0)return;
						p.makeLoop = true;
						askReplace(p);
					});
			return;
		}
		askReplace(p);
	}

	private static void askReplace(Plan p) {
		String ch = p.spec.preset.channel;
		if (p.target != null && (AnimGenBB.hasKeys(p.target, p.part, ch) || (p.pair != null && AnimGenBB.hasKeys(p.target, p.pair, ch)))) {
			PlusDialogs.messageBox("cpmplus.w3animgen.motion.title", AnimGenBB.trf("cpmplus.w3animgen.motion.askReplace", p.part.name, AnimGenBB.tr("cpmplus.w3animgen.channel." + ch)),
					new String[] {"cpmplus.w3animgen.motion.replace", "cpmplus.w0binfra.btn.cancel"}, b -> {
						if (b != 0)return;
						p.replace = true;
						askPivot(p);
					});
			return;
		}
		askPivot(p);
	}

	private static void askPivot(Plan p) {
		if (p.spec.preset.isSpin()) {
			double off = pivotOffset(p.part, p.spec.axis);
			boolean tilt = restTilt(p.part, p.spec.axis);
			if (off > 0.5 || tilt) {
				String msg = off > 0.5 ? AnimGenBB.trf("cpmplus.w3animgen.spin.askPivot", p.part.name, AnimGenBB.num(off)) : AnimGenBB.trf("cpmplus.w3animgen.spin.askTilt", p.part.name);
				PlusDialogs.messageBox("cpmplus.w3animgen.motion.title", msg,
						new String[] {"cpmplus.w3animgen.spin.makeGroup", "cpmplus.w0binfra.btn.continueAnyway", "cpmplus.w0binfra.btn.cancel"}, b -> {
							if (b == 0)p.spinGroup = true;
							else if (b != 1)return;
							askSpeed(p);
						});
				return;
			}
		}
		askSpeed(p);
	}

	private static void askSpeed(Plan p) {
		if (p.spec.preset.isSpin()) {
			double step = MotionPresets.spinExportStep(p.spec.length, p.spec.fps, p.spec.cycles);
			if (step > MotionPresets.SPIN_MAX_STEP + 1e-9) {
				List<Integer> allowed = p.target != null ? AnimGenBB.allowedSnappings(p.target) : MotionPresets.spinSnappings(p.spec.length, p.spec.cycles);
				List<Integer> ok = new ArrayList<>();
				for (Integer s : allowed)if (s != null && (p.target == null || AnimGenBB.framesOk(p.target, s, p.spec.length)))ok.add(s);
				int fix = MotionPresets.pickSnapping(p.spec.length, p.spec.cycles, p.spec.fps, ok);
				String msg = SpinGuard.dialogWarning(p.part.name, step, fix);
				String[] btn = fix > 0 ? new String[] {AnimGenBB.trf("cpmplus.w3animgen.spin.fixButton", String.valueOf(fix)), "cpmplus.w0binfra.btn.continueAnyway", "cpmplus.w0binfra.btn.cancel"}
						: new String[] {"cpmplus.w0binfra.btn.continueAnyway", "cpmplus.w0binfra.btn.cancel"};
				PlusDialogs.messageBox("cpmplus.w3animgen.motion.title", msg, btn, b -> {
					if (fix > 0 && b == 0) {
						p.snapping = fix;
						p.spec.fps = fix;
					} else if (!((fix > 0 && b == 1) || (fix <= 0 && b == 0)))return;
					run(p);
				});
				return;
			}
		}
		run(p);
	}

	private static void run(Plan p) {
		Result r;
		try {
			r = apply(p);
		} catch (IllegalArgumentException e) {
			PlusDialogs.block(e.getMessage());
			return;
		}
		AnimGenBB.showAnimation(r.animation);
		String what = AnimGenBB.trf("cpmplus.w3animgen.motion.done", AnimGenBB.tr("cpmplus.w3animgen.preset." + p.spec.preset.id), (r.spun != null ? r.spun : p.part).name, r.animation.name);
		if (p.pair != null)what += " " + AnimGenBB.trf("cpmplus.w3animgen.motion.donePair", p.pair.name);
		PlusDialogs.done(what);
		if (!r.warnings.isEmpty())PlusDialogs.info("cpmplus_w3animgen_motion_note", "cpmplus.w3animgen.motion.title", r.warnings);
	}
}
