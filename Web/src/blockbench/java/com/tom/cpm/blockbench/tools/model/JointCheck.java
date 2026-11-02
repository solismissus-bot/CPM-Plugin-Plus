package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.ext.W4RigNatives;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.blockbench.util.VisState;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;

/**
 * CPM+ (W4-RIG, G18): 'Conferir articulações'. The pivot (origin) of a player root group is where the game rotates
 * that body part; the pivot of a cube does not matter. A leg / arm whose root pivot sits in the middle of the limb
 * comes off the body when walking. Checks the 6 player roots and their '_dup' copies against the rest-pose box of
 * their exported, visible cubes (ears / wings / tails that stick out are left out of the box), proportional rules,
 * suggests the joint, and fixes only group.origin (refused when the root itself is rotated). Ignore is stored in the
 * group's CPM data (survives saving) and comes back when the pivot moves more than 1 px.
 */
public final class JointCheck {
	public static final String WARN_KEY = "cpmplusJointPivot";
	/** GroupData extra: [x, y, z] origin when the user chose 'Ignorar' */
	public static final String IGNORE_KEY = "jointIgnore";

	private JointCheck() {
	}

	/** check result of one root */
	public static final class Result {
		public Group root;
		public PlayerModelParts part;
		public boolean hasBox;
		public RigGeom.Box box;
		public double[] pivot, suggested;
		public boolean ok = true, strong, dva, ignored, rotated, vanillaSized;

		public boolean warns() {
			return hasBox && !ok && !ignored;
		}

		public boolean canFix() {
			return warns() && !rotated;
		}
	}

	/** every player root (+ '_dup') with exported visible cubes */
	public static List<Result> check() {
		List<Result> out = new ArrayList<>();
		if (Group.all == null)return out;
		SkinType st = RootUtil.projectSkinType();
		for (Group g : Group.all) {
			if (g == null || g.parent != Group.ROOT || !g.export || g.is_catch_bone || g.name == null)continue;
			Object rt = g.getRootType();
			if (!(rt instanceof PlayerModelParts) || rt == PlayerModelParts.CUSTOM_PART)continue;
			out.add(checkRoot(g, (PlayerModelParts) rt, st));
		}
		return out;
	}

	static Result checkRoot(Group root, PlayerModelParts part, SkinType st) {
		Result r = new Result();
		r.root = root;
		r.part = part;
		r.pivot = RigGeom.vec(root.origin);
		r.dva = root.disableVanillaAnim;
		r.rotated = !RigGeom.isZero(root.rotation);
		List<RigGeom.Box> boxes = new ArrayList<>();
		collectBoxes(root, boxes);
		if (boxes.isEmpty())return r;
		r.hasBox = true;
		RigGeom.Box core = coreBox(boxes, part == PlayerModelParts.BODY || part == PlayerModelParts.HEAD);
		r.box = core;
		double top = core.max[1], bottom = core.min[1], h = Math.max(core.size(1), 1e-6);
		double y = r.pivot[1];
		boolean yOk;
		switch (part) {
		case LEFT_LEG:
		case RIGHT_LEG:
			yOk = y >= top - 0.25 * h - 1e-4 && y <= top + 1 + 1e-4;
			break;
		case LEFT_ARM:
		case RIGHT_ARM:
			yOk = y >= top - 0.35 * h - 1e-4 && y <= top + 1 + 1e-4;
			break;
		case BODY:
			yOk = y >= top - 0.25 * h - 1e-4 && y <= top + 1 + 1e-4;
			break;
		default:// HEAD
			yOk = y >= bottom - 1 - 1e-4 && y <= bottom + 0.25 * h + 1e-4;
			break;
		}
		boolean xzOk = true;
		for (int a : new int[] {0, 2}) {
			double g = Math.max(1, 0.15 * core.size(a));
			if (r.pivot[a] < core.min[a] - g - 1e-4 || r.pivot[a] > core.max[a] + g + 1e-4)xzOk = false;
		}
		r.ok = yOk && xzOk;
		RigGeom.Box strongBox = core.grow(new double[] {core.size(0) * 0.5, core.size(1) * 0.5, core.size(2) * 0.5});
		r.strong = !r.ok && !strongBox.contains(r.pivot);
		r.suggested = suggest(part, core, st);
		Object ig = root.getData().hasExtra(IGNORE_KEY) ? root.getData().getExtra(IGNORE_KEY) : null;
		if (ig instanceof List && ((List<?>) ig).size() == 3) {
			List<?> l = (List<?>) ig;
			double[] at = new double[3];
			boolean num = true;
			for (int i = 0; i < 3; i++) {
				if (l.get(i) instanceof Number)at[i] = ((Number) l.get(i)).doubleValue();
				else num = false;
			}
			r.ignored = num && RigGeom.dist(at, r.pivot) <= 1 + 1e-6;
		}
		return r;
	}

	/** the joint the part should rotate about */
	static double[] suggest(PlayerModelParts part, RigGeom.Box b, SkinType st) {
		// vanilla-sized: the exact vanilla pivot (same as a new project)
		RootUtil.CubeSpec vs = RootUtil.vanillaCubeSpec(part, st);
		boolean vanilla = true;
		double[] vf = {vs.from.x, vs.from.y, vs.from.z}, vt = {vs.to.x, vs.to.y, vs.to.z};
		for (int a = 0; a < 3; a++) {
			if (Math.abs(b.min[a] - Math.min(vf[a], vt[a])) > 0.6 || Math.abs(b.max[a] - Math.max(vf[a], vt[a])) > 0.6)vanilla = false;
		}
		if (vanilla)return RigGeom.vec(RootUtil.rootOrigin(part, st));
		double cx = b.center(0), cz = b.center(2), top = b.max[1], bottom = b.min[1], h = b.size(1);
		double[] s;
		switch (part) {
		case LEFT_ARM:
		case RIGHT_ARM: {
			double vx = RootUtil.rootOrigin(part, st).x;
			double x = vx >= b.min[0] && vx <= b.max[0] ? vx : cx;
			s = new double[] {x, top - 2 * (h / 12), cz};
			break;
		}
		case HEAD:
			s = new double[] {cx, bottom, cz};
			break;
		default:// legs, body
			s = new double[] {cx, top, cz};
			break;
		}
		return new double[] {RigGeom.snap(s[0]), RigGeom.snap(s[1]), RigGeom.snap(s[2])};
	}

	/** rest-pose world boxes of the exported, visible cubes under g (item slots and switched-off groups skipped) */
	static void collectBoxes(Group g, List<RigGeom.Box> out) {
		if (g.children == null)return;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (n instanceof Cube) {
				Cube c = (Cube) n;
				if (!c.export || !VisState.persistentVisible(c))continue;
				out.add(new RigGeom.Box().addAll(RigGeom.shownCorners(c, false, null)));
			} else if (n instanceof Group) {
				Group s = (Group) n;
				if (!s.export || s.is_catch_bone || !VisState.persistentVisible(s))continue;
				GroupData gd = s.getData();
				if (gd.getItemRenderer() != null)continue;
				collectBoxes(s, out);
			}
		}
	}

	/**
	 * the main mass of the part: the biggest cube, plus every cube of at least 10 % of its volume that touches it
	 * (gap 0.5 px), repeatedly. Small decorations far from the core (ears, wings tips, tails) are left out.
	 */
	static RigGeom.Box coreBox(List<RigGeom.Box> boxes, boolean compact) {
		RigGeom.Box main = boxes.get(0);
		for (RigGeom.Box b : boxes)if (b.weight(0.25) > main.weight(0.25))main = b;
		if (compact) {
			// body / head: the main cube plus what is mostly inside it (jacket, hat layer, belt). Wings, tails, long
			// hair and horns that stick out do not move the waist / neck.
			RigGeom.Box core = new RigGeom.Box(main);
			RigGeom.Box grown = main.grow(new double[] {0.5, 0.5, 0.5});
			for (RigGeom.Box b : boxes)if (b != main && fractionInside(b, grown) >= 0.5)core.add(b);
			return core;
		}
		double minW = main.weight(0.25) * 0.1;
		RigGeom.Box core = new RigGeom.Box(main);
		List<RigGeom.Box> rest = new ArrayList<>();
		for (RigGeom.Box b : boxes)if (b != main && b.weight(0.25) >= minW)rest.add(b);
		boolean grew = true;
		while (grew) {
			grew = false;
			for (int i = 0; i < rest.size(); i++) {
				if (core.touches(rest.get(i), 0.5)) {
					core.add(rest.remove(i));
					i--;
					grew = true;
				}
			}
		}
		return core;
	}

	/** share of box b that lies inside box c (flat sides count as inside when their plane is inside) */
	static double fractionInside(RigGeom.Box b, RigGeom.Box c) {
		double f = 1;
		for (int a = 0; a < 3; a++) {
			double s = b.size(a);
			if (s < 1e-6) {
				if (b.min[a] < c.min[a] - 1e-6 || b.min[a] > c.max[a] + 1e-6)return 0;
				continue;
			}
			double o = Math.min(b.max[a], c.max[a]) - Math.max(b.min[a], c.min[a]);
			if (o <= 0)return 0;
			f *= Math.min(1, o / s);
		}
		return f;
	}

	// ------------------------------------------------------------------ fix / ignore (one undo entry each)

	/** moves the pivot of every given (fixable) root to its suggested joint: ONE undo entry */
	public static int fix(List<Result> rs) {
		List<Result> todo = new ArrayList<>();
		for (Result r : rs)if (r.canFix())todo.add(r);
		if (todo.isEmpty())return 0;
		Group[] gs = new Group[todo.size()];
		for (int i = 0; i < gs.length; i++)gs[i] = todo.get(i).root;
		PlusUndo.run(PlusDialogs.tr("cpmplus.w4rig.g18.undo.fix"), PlusUndo.structBefore(gs), () -> PlusUndo.structAfter(gs, null, null), () -> {
			for (Result r : todo) {
				Group g = r.root;
				if (!RigGeom.isZero(g.rotation))throw new IllegalStateException("rotated root " + g.name);
				g.origin.x = (float) r.suggested[0];
				g.origin.y = (float) r.suggested[1];
				g.origin.z = (float) r.suggested[2];
			}
		});
		clearMarkers();
		refresh();
		return todo.size();
	}

	/** asks first when animations rotate / scale the root (they turn about the old point), then fixes */
	public static Promise<Boolean> askAndFix(List<Result> rs) {
		List<String> anims = new ArrayList<>();
		int fixable = 0;
		for (Result r : rs) {
			if (!r.canFix())continue;
			fixable++;
			JsArray<String> a = W4RigNatives.animsWithKeys(r.root.uuid, new String[] {"rotation", "scale"});
			for (int i = 0; i < a.length; i++)if (!anims.contains(a.getAt(i)))anims.add(a.getAt(i));
		}
		if (fixable == 0) {
			for (Result r : rs)if (r.warns() && r.rotated) {
				PlusDialogs.block(PlusDialogs.trf("cpmplus.w4rig.g18.rotated", r.root.name));
				return Promise.resolve(false);
			}
			return Promise.resolve(false);
		}
		if (anims.isEmpty())return Promise.resolve(doFix(rs));
		return new Promise<>((res, rej) -> PlusDialogs.messageBox("cpmplus.w4rig.g18.keyed.title",
				PlusDialogs.trf("cpmplus.w4rig.g18.keyed.text", String.join(", ", anims)),
				new String[] {"cpmplus.w4rig.g18.keyed.move", "cpmplus.w0binfra.btn.cancel"}, i -> {
					try {
						res.onInvoke(i == 0 && doFix(rs));
					} catch (Throwable e) {
						rej.onInvoke(e);
					}
				}));
	}

	private static boolean doFix(List<Result> rs) {
		int n = fix(rs);
		if (n > 0)PlusDialogs.done(PlusDialogs.trf("cpmplus.w4rig.g18.done.fix", String.valueOf(n)));
		return n > 0;
	}

	/** 'Ignorar' (on) / 'Voltar a avisar' (off): stored in the group's CPM data, one undo entry */
	public static void setIgnored(Group g, boolean on) {
		PlusUndo.run(PlusDialogs.tr(on ? "cpmplus.w4rig.g18.undo.ignore" : "cpmplus.w4rig.g18.undo.unignore"), PlusUndo.structBefore(g),
				() -> PlusUndo.structAfter(new Group[] {g}, null, null), () -> {
					GroupData gd = g.getData();
					if (on) {
						List<Object> at = new ArrayList<>();
						at.add((double) g.origin.x);
						at.add((double) g.origin.y);
						at.add((double) g.origin.z);
						gd.putExtra(IGNORE_KEY, at);
					} else gd.removeExtra(IGNORE_KEY);
					gd.flush();
				});
	}

	private static void refresh() {
		try {
			ProjectGenerator.updateAll();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: refresh failed", e);
		}
	}

	// ------------------------------------------------------------------ export check

	public static void exportCheck(ExportContext ctx) {
		for (Result r : check()) {
			if (!r.warns())continue;
			String part = PlusDialogs.esc(RigParts.label(r.part));
			String msg = PlusDialogs.trf(r.strong ? "cpmplus.w4rig.g18.msg.strong" : "cpmplus.w4rig.g18.msg", part, PlusDialogs.esc(r.root.name));
			if (r.dva)msg += " " + PlusDialogs.esc(PlusDialogs.tr("cpmplus.w4rig.g18.msg.dva"));
			WarnEntry w = WarnEntry.WarnFactory.ofHtml(r.dva ? Severity.INFO : Severity.DIFFERS, WARN_KEY, msg);
			w.setKey(WARN_KEY, r.root.uuid, r.root.name);
			w.setTooltip(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w4rig.g18.tooltip")));
			w.setConsequence("cpmplus.w4rig.g18.consequence");
			w.addNode(r.root.uuid, r.root.name);
			w.setFixPreview(PlusDialogs.trf("cpmplus.w4rig.g18.preview", RigGeom.fmt(r.pivot), RigGeom.fmt(r.suggested)));
			ctx.add(w);
		}
	}

	public static void registerFixes() {
		WarnFixes.register(WARN_KEY, "cpmplus.w4rig.g18.fix.move", 10, w -> {
			Result r = fromRow(w);
			if (r == null || !r.warns())return null;
			List<Result> one = new ArrayList<>();
			one.add(r);
			if (r.rotated)return () -> {
				PlusDialogs.block(PlusDialogs.trf("cpmplus.w4rig.g18.rotated", r.root.name));
				return Promise.resolve(false);
			};
			return () -> askAndFix(one);
		});
		WarnFixes.register(WARN_KEY, "cpmplus.w4rig.g18.fix.ignore", 20, w -> {
			Result r = fromRow(w);
			if (r == null || !r.warns())return null;
			return () -> {
				setIgnored(r.root, true);
				PlusDialogs.done(PlusDialogs.trf("cpmplus.w4rig.g18.done.ignore", r.root.name));
				return Promise.resolve(true);
			};
		});
	}

	private static Result fromRow(WarnEntry w) {
		Object[] a = w.getArgs();
		if (a == null || a.length < 1)return null;
		String uuid = String.valueOf(a[0]);
		for (Result r : check())if (r.root.uuid.equals(uuid))return r;
		return null;
	}

	// ------------------------------------------------------------------ viewport markers (temporary, never saved)

	private static final List<Object> markers = new ArrayList<>();
	private static Runnable selectionListener;

	/** red sphere = current pivot, green = suggested, dashed line between; selects the root; gone on close / selection change */
	public static void show(Result r) {
		clearMarkers();
		if (r == null)return;
		RigParts.select(r.root);
		double rad = 0.6;
		if (r.box != null)rad = Math.max(0.35, Math.min(2, Math.max(r.box.size(0), Math.max(r.box.size(1), r.box.size(2))) / 16));
		add(W4RigNatives.addSphere(r.pivot[0], r.pivot[1], r.pivot[2], rad, 0xe14f4f));
		if (r.suggested != null) {
			add(W4RigNatives.addSphere(r.suggested[0], r.suggested[1], r.suggested[2], rad, 0x3fbf5f));
			add(W4RigNatives.addLine(r.pivot[0], r.pivot[1], r.pivot[2], r.suggested[0], r.suggested[1], r.suggested[2], 0xffffff));
		}
		// the selection of the root fires a selection change first: listen after it
		DomGlobal.setTimeout(__ -> {
			if (selectionListener == null)selectionListener = CPMCodec.onSelectionChange(JointCheck::clearMarkers);
		}, 100);
	}

	private static void add(Object o) {
		if (o != null)markers.add(o);
	}

	public static void clearMarkers() {
		for (Object o : markers)W4RigNatives.removeObject(o);
		markers.clear();
		if (selectionListener != null) {
			Runnable r = selectionListener;
			selectionListener = null;
			try {
				r.run();
			} catch (Throwable e) {
			}
		}
	}

	public static int markerCount() {
		return markers.size();
	}

	// ------------------------------------------------------------------ panel

	private static Dialog panel;

	/** CPM+ > Modelagem > 'Conferir articulações' */
	public static void openPanel(boolean showIgnored) {
		List<Result> rs = check();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w4rig.g18.panel.intro"));
		int warned = 0;
		for (Result r : rs) {
			String id = r.root.uuid;
			String part = RigParts.label(r.part);
			rows.add(FormRow.separator());
			if (!r.hasBox) {
				rows.add(FormRow.info("i_" + id, r.root.name, PlusDialogs.trf("cpmplus.w4rig.g18.panel.noCubes", part)));
				continue;
			}
			if (r.ok) {
				rows.add(FormRow.info("i_" + id, r.root.name, PlusDialogs.trf("cpmplus.w4rig.g18.panel.ok", part, RigGeom.fmt(r.pivot))));
				continue;
			}
			if (r.ignored && !showIgnored) {
				rows.add(FormRow.info("i_" + id, r.root.name, PlusDialogs.trf("cpmplus.w4rig.g18.panel.ignored", part)));
				continue;
			}
			warned++;
			String txt = PlusDialogs.trf(r.strong ? "cpmplus.w4rig.g18.panel.warnStrong" : "cpmplus.w4rig.g18.panel.warn", part, RigGeom.fmt(r.pivot), RigGeom.fmt(r.suggested));
			if (r.rotated)txt += "\n" + PlusDialogs.trf("cpmplus.w4rig.g18.rotated", r.root.name);
			if (r.dva)txt += "\n" + PlusDialogs.tr("cpmplus.w4rig.g18.msg.dva");
			if (r.ignored)txt += "\n" + PlusDialogs.tr("cpmplus.w4rig.g18.panel.isIgnored");
			rows.add(FormRow.info("i_" + id, r.root.name, txt));
			if (r.canFix())rows.add(FormRow.checkbox("fix_" + id, "cpmplus.w4rig.g18.panel.fixThis", !r.ignored));
			final Result fr = r;
			rows.add(FormRow.buttons("b_" + id, null, new String[] {"cpmplus.w4rig.g18.btn.show", "cpmplus.w4rig.g18.btn.copy",
					r.ignored ? "cpmplus.w4rig.g18.btn.unignore" : "cpmplus.w4rig.g18.btn.ignore"}, i -> {
						if (i == 0)show(fr);
						else if (i == 1)PlusDialogs.copyText(RigGeom.fmt(fr.suggested));
						else {
							setIgnored(fr.root, !fr.ignored);
							if (panel != null)panel.hide();
							clearMarkers();
							DomGlobal.setTimeout(__ -> openPanel(showIgnored), 50);
						}
					}));
		}
		rows.add(FormRow.separator());
		rows.add(FormRow.info("hint", null, "cpmplus.w4rig.g18.tooltip"));
		rows.add(FormRow.checkbox("showIgnored", "cpmplus.w4rig.g18.panel.showIgnored", showIgnored));
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.width = 620;
		o.confirmKey = warned > 0 ? "cpmplus.w4rig.g18.panel.fixChecked" : "cpmplus.w0binfra.btn.close";
		o.cancelKey = "cpmplus.w0binfra.btn.cancel";
		o.onCancel = JointCheck::clearMarkers;
		boolean[] last = {showIgnored};
		o.onChange = fr -> {
			if (fr.getBool("showIgnored") != last[0]) {
				last[0] = fr.getBool("showIgnored");
				if (panel != null)panel.hide();
				clearMarkers();
				DomGlobal.setTimeout(__ -> openPanel(last[0]), 50);
			}
		};
		panel = PlusDialogs.form("cpmplus_w4rig_joints", "cpmplus.w4rig.g18.panel.title", rows, fr -> {
			clearMarkers();
			List<Result> sel = new ArrayList<>();
			for (Result r : rs)if (r.canFix() && fr.getBool("fix_" + r.root.uuid))sel.add(r);
			if (!sel.isEmpty())askAndFix(sel);
		}, o);
	}
}
