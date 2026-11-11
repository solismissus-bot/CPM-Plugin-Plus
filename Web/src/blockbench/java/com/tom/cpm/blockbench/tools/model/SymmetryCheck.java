package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Painter;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W7-VIEW, G58) 'Conferir simetria (pintura espelhada)'. Blockbench's mirror painting only paints a cube's twin
 * when Painter.getMirrorElement finds one (same inflate / size, mirrored bounds within 0.01, compatible rotation);
 * otherwise it paints nothing and says nothing. This tool asks Blockbench itself (Painter.getMirrorElement(cube, [X])
 * - never a copy of its rule), lists the cubes without a twin, finds near twins (bounds / size within 1 px, inflate
 * within 0.5, rotation within 2 deg, side names preferred, no cube used twice) and offers:
 * <ul>
 * <li>'Alinhar' (the user picks which side stays): the other cube becomes the exact mirror (from/to, inflate,
 * rotation with Y/Z negated, its own origin mirrored); never UV, texture, parent group origins or keyframes; a
 * confirmation when anything moves more than 0.25 px or the size changes; afterwards Blockbench is asked again and
 * the edit is cancelled (nothing changed) when it still does not pair;</li>
 * <li>'Centralizar' for a cube almost centred on x = 0;</li>
 * <li>'É assimétrica de propósito' (CubeData extra 'mirrorIgnore', saved in the .bbmodel, never in config.json).</li>
 * </ul>
 * X axis only (CPM skins mirror left / right). Each action is one undo entry. In Paint mode with mirror painting on, a
 * toast says how many cubes have no twin (ignored ones are not counted).
 */
public final class SymmetryCheck {
	public static final String IGNORE_KEY = "mirrorIgnore";
	private static double lastBadge;

	private SymmetryCheck() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_TEX, "cpmplus_w7view_symmetry", "cpmplus.w7view.sym.menu", "flip", Cond.CPM_ONLY, SymmetryCheck::open);
		PluginStart.addEventListener("select_mode", e -> badgeLater());
		if (PlusTestHooks.active()) {
			PlusTestHooks.register("w7view.symmetryReport", o -> toJs(report()));
			PlusTestHooks.register("w7view.alignPair", o -> {
				JsPropertyMap<Object> r = result();
				Cube t = find(String.valueOf(o.get("target"))), ref = find(String.valueOf(o.get("reference")));
				r.set("ok", t != null && ref != null && align(t, ref, true));
				r.set("mirrorOfTarget", mirrorName(t));
				return r;
			});
			PlusTestHooks.register("w7view.centerCube", o -> {
				JsPropertyMap<Object> r = result();
				Cube t = find(String.valueOf(o.get("cube")));
				r.set("ok", t != null && center(t, true));
				r.set("mirrorOfTarget", mirrorName(t));
				return r;
			});
			PlusTestHooks.register("w7view.ignoreCube", o -> {
				JsPropertyMap<Object> r = result();
				Cube t = find(String.valueOf(o.get("cube")));
				r.set("ok", t != null && setIgnored(t, !Js.isTruthy(o.get("clear"))));
				return r;
			});
			PlusTestHooks.register("w7view.badge", o -> {
				JsPropertyMap<Object> r = result();
				r.set("count", (double) unpairedCount());
				return r;
			});
		}
	}

	private static JsPropertyMap<Object> result() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}

	static Cube find(String nameOrUuid) {
		if (Cube.all == null || nameOrUuid == null)return null;
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			if (nameOrUuid.equals(c.uuid) || nameOrUuid.equals(c.name))return c;
		}
		return null;
	}

	private static String mirrorName(Cube c) {
		if (c == null)return null;
		Object m = mirrorOf(c);
		if (m == null)return null;
		return Js.asPropertyMap(m).get("name") + "";
	}

	/** Blockbench's own answer: the twin, the cube itself (centred), or null */
	public static Object mirrorOf(Cube c) {
		try {
			if (!Painter.available())return null;
			Object m = Painter.getMirrorElement(c, new boolean[] {true, false, false});
			return Js.isTruthy(m) ? m : null;
		} catch (Throwable e) {
			return null;
		}
	}

	static boolean isIgnored(Cube c) {
		try {
			return Js.isTruthy(c.getData().getExtra(IGNORE_KEY));
		} catch (Throwable e) {
			return false;
		}
	}

	/** cubes that count: exported, not vanilla, not ignored, not meshes */
	static boolean counts(Cube c) {
		return c != null && c.export && !c.getData().isVanilla() && !isIgnored(c);
	}

	// ------------------------------------------------------------------ report

	/** one row of the report */
	public static final class Issue {
		public Cube cube, other;
		/** 'pair' (near twin), 'center' (almost centred), 'none' */
		public String kind;
		public String diff;
		public double maxMove;
	}

	public static List<Issue> report() {
		List<Cube> unpaired = new ArrayList<>();
		if (Cube.all == null || !Painter.available())return new ArrayList<>();
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			if (!counts(c))continue;
			if (mirrorOf(c) == null)unpaired.add(c);
		}
		List<Issue> out = new ArrayList<>();
		boolean[] used = new boolean[unpaired.size()];
		// greedy smallest-difference matching (no cube used twice)
		List<double[]> cand = new ArrayList<>();
		for (int i = 0; i < unpaired.size(); i++) {
			for (int j = i + 1; j < unpaired.size(); j++) {
				double s = nearScore(unpaired.get(i), unpaired.get(j));
				if (s >= 0)cand.add(new double[] {s, i, j});
			}
		}
		cand.sort((a, b) -> Double.compare(a[0], b[0]));
		for (double[] c : cand) {
			int i = (int) c[1], j = (int) c[2];
			if (used[i] || used[j])continue;
			used[i] = used[j] = true;
			Issue is = new Issue();
			Cube a = unpaired.get(i), b = unpaired.get(j);
			// the 'right' side (x < 0 in CPM's Blockbench space is the model's left) first, for a stable order
			if (centerX(a) > centerX(b)) {
				Cube t = a;
				a = b;
				b = t;
			}
			is.cube = a;
			is.other = b;
			is.kind = "pair";
			is.diff = describe(a, b);
			is.maxMove = maxMove(a, b);
			out.add(is);
		}
		for (int i = 0; i < unpaired.size(); i++) {
			if (used[i])continue;
			Cube c = unpaired.get(i);
			Issue is = new Issue();
			is.cube = c;
			if (Math.abs(centerX(c)) <= 1) {
				is.kind = "center";
				is.diff = PlusDialogs.trf("cpmplus.w7view.sym.diff.center", fmt(centerX(c)));
			} else {
				is.kind = "none";
				is.diff = PlusDialogs.tr("cpmplus.w7view.sym.diff.none");
			}
			out.add(is);
		}
		return out;
	}

	public static int unpairedCount() {
		if (Cube.all == null || !Painter.available())return 0;
		int n = 0;
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			if (counts(c) && mirrorOf(c) == null)n++;
		}
		return n;
	}

	private static native double centerX(Cube c) /*-{
		return (c.from[0] + c.to[0]) / 2;
	}-*/;

	/**
	 * near twin score (sum of differences) or -1: mirrored bounds within 1 px per coordinate, size within 1 px,
	 * inflate within 0.5, rotation within 2 deg (X equal, Y / Z negated); side tokens in the names lower the score
	 */
	private static native double nearScore(Cube a, Cube b) /*-{
		var s = 0, d;
		for (var k = 0; k < 3; k++) {
			var af = a.from[k], at = a.to[k], bf = b.from[k], bt = b.to[k];
			if (k === 0) { d = Math.max(Math.abs(af + bt), Math.abs(at + bf)); }
			else { d = Math.max(Math.abs(af - bf), Math.abs(at - bt)); }
			if (d > 1 + 1e-6) return -1;
			s += d;
			var ds = Math.abs(Math.abs(at - af) - Math.abs(bt - bf));
			if (ds > 1 + 1e-6) return -1;
		}
		var di = Math.abs((a.inflate || 0) - (b.inflate || 0));
		if (di > 0.5 + 1e-6) return -1;
		s += di;
		var ra = a.rotation || [0, 0, 0], rb = b.rotation || [0, 0, 0];
		var dr = Math.max(Math.abs(ra[0] - rb[0]), Math.abs(ra[1] + rb[1]), Math.abs(ra[2] + rb[2]));
		if (dr > 2 + 1e-6) return -1;
		s += dr / 10;
		// both on the same side of x = 0 are not twins
		var ca = (a.from[0] + a.to[0]) / 2, cb = (b.from[0] + b.to[0]) / 2;
		if (ca * cb > 0) return -1;
		var side = function(n) {
			n = String(n || '').toLowerCase();
			if (/(^|[_\-\s.])(l|left|esq|esquerd[oa]|e)($|[_\-\s.\d])/.test(n)) return 'l';
			if (/(^|[_\-\s.])(r|right|dir|direit[oa]|d)($|[_\-\s.\d])/.test(n)) return 'r';
			return '';
		};
		var sa = side(a.name), sb = side(b.name);
		if (sa && sb && sa !== sb) s -= 0.5;
		return Math.max(0, s);
	}-*/;

	private static native double maxMove(Cube a, Cube b) /*-{
		var m = 0;
		for (var k = 0; k < 3; k++) {
			if (k === 0) m = Math.max(m, Math.abs(a.from[0] + b.to[0]), Math.abs(a.to[0] + b.from[0]));
			else m = Math.max(m, Math.abs(a.from[k] - b.from[k]), Math.abs(a.to[k] - b.to[k]));
		}
		return m;
	}-*/;

	private static String describe(Cube a, Cube b) {
		String d = describe0(a, b);
		if (d == null || d.isEmpty())return PlusDialogs.tr("cpmplus.w7view.sym.diff.tiny");
		String[] p = d.split("\\|");
		switch (p[0]) {
		case "axis":
			return PlusDialogs.trf("cpmplus.w7view.sym.diff.axis", p[1], p[2]);
		case "inflate":
			return PlusDialogs.trf("cpmplus.w7view.sym.diff.inflate", p[1], p[2]);
		case "rot":
			return PlusDialogs.trf("cpmplus.w7view.sym.diff.rot", p[1]);
		default:
			return PlusDialogs.tr("cpmplus.w7view.sym.diff.tiny");
		}
	}

	private static native String describe0(Cube a, Cube b) /*-{
		var f = function(v) { return (Math.round(v * 1000) / 1000).toString().replace('.', ','); };
		var di = Math.abs((a.inflate || 0) - (b.inflate || 0));
		if (di > 0.001) return 'inflate|' + f(a.inflate || 0) + '|' + f(b.inflate || 0);
		var ra = a.rotation || [0, 0, 0], rb = b.rotation || [0, 0, 0];
		var dr = Math.max(Math.abs(ra[0] - rb[0]), Math.abs(ra[1] + rb[1]), Math.abs(ra[2] + rb[2]));
		var best = -1, bd = 0, names = ['X', 'Y', 'Z'];
		for (var k = 0; k < 3; k++) {
			var d = k === 0 ? Math.max(Math.abs(a.from[0] + b.to[0]), Math.abs(a.to[0] + b.from[0])) : Math.max(Math.abs(a.from[k] - b.from[k]), Math.abs(a.to[k] - b.to[k]));
			if (d > bd) { bd = d; best = k; }
		}
		if (best >= 0 && bd > 0.005) return 'axis|' + names[best] + '|' + f(bd);
		if (dr > 0.01) return 'rot|' + f(dr);
		return '';
	}-*/;

	static String fmt(double v) {
		double r = Math.round(v * 1000) / 1000.0;
		return String.valueOf(r).replace('.', ',');
	}

	// ------------------------------------------------------------------ actions

	/**
	 * makes 'target' the exact mirror of 'reference' (X axis) in ONE undo entry; asks first when something moves more
	 * than 0.25 px or the size changes (skipped when confirmed=true). Cancelled (nothing changed) when Blockbench still
	 * does not pair them afterwards.
	 * @return true when applied
	 */
	public static boolean align(Cube target, Cube reference, boolean confirmed) {
		if (target == null || reference == null || target == reference)return false;
		if (!confirmed && needsConfirm(target, reference)) {
			PlusDialogs.confirm("cpmplus_w7view_sym_confirm", "cpmplus.w7view.sym.title", "cpmplus.w7view.sym.confirmShape", () -> {
				if (align(target, reference, true)) {
					PlusDialogs.done(PlusDialogs.trf("cpmplus.w7view.sym.done.align", target.name));
					reopenLater();
				}
			}, null);
			return false;
		}
		boolean[] ok = new boolean[1];
		try {
			PlusUndo.run(PlusDialogs.tr("cpmplus.w7view.sym.undo.align"), UndoData.make(target), () -> UndoData.make(target), () -> {
				mirrorFrom(target, reference);
				Object m = mirrorOf(target);
				if (m != reference)throw new NotPaired();
				ok[0] = true;
			});
		} catch (NotPaired e) {
			PlusDialogs.block("cpmplus.w7view.sym.notPaired");
			return false;
		}
		updateView(target);
		return ok[0];
	}

	static final class NotPaired extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private static native boolean needsConfirm(Cube t, Cube r) /*-{
		for (var k = 0; k < 3; k++) {
			var nf = k === 0 ? -r.to[0] : r.from[k], nt = k === 0 ? -r.from[0] : r.to[k];
			if (Math.abs(nf - t.from[k]) > 0.25 || Math.abs(nt - t.to[k]) > 0.25) return true;
			if (Math.abs(Math.abs(nt - nf) - Math.abs(t.to[k] - t.from[k])) > 1e-6) return true;
		}
		return false;
	}-*/;

	/** from/to/inflate/rotation/origin of target = mirror of reference in x = 0 (UV, texture, parents, keys untouched) */
	private static native void mirrorFrom(Cube t, Cube r) /*-{
		var nf = [-r.to[0], r.from[1], r.from[2]], nt = [-r.from[0], r.to[1], r.to[2]];
		t.from[0] = nf[0]; t.from[1] = nf[1]; t.from[2] = nf[2];
		t.to[0] = nt[0]; t.to[1] = nt[1]; t.to[2] = nt[2];
		t.inflate = r.inflate || 0;
		if (t.rotation && r.rotation) { t.rotation[0] = r.rotation[0]; t.rotation[1] = -r.rotation[1]; t.rotation[2] = -r.rotation[2]; }
		if (t.origin && r.origin) { t.origin[0] = -r.origin[0]; t.origin[1] = r.origin[1]; t.origin[2] = r.origin[2]; }
	}-*/;

	/** moves a nearly centred cube so it is centred on x = 0 (one undo entry; cancelled when Blockbench still disagrees) */
	public static boolean center(Cube c, boolean confirmed) {
		if (c == null)return false;
		if (!confirmed && Math.abs(centerX(c)) > 0.25) {
			PlusDialogs.confirm("cpmplus_w7view_sym_confirm", "cpmplus.w7view.sym.title", "cpmplus.w7view.sym.confirmShape", () -> {
				if (center(c, true)) {
					PlusDialogs.done(PlusDialogs.trf("cpmplus.w7view.sym.done.center", c.name));
					reopenLater();
				}
			}, null);
			return false;
		}
		try {
			PlusUndo.run(PlusDialogs.tr("cpmplus.w7view.sym.undo.center"), UndoData.make(c), () -> UndoData.make(c), () -> {
				shiftX(c, -centerX(c));
				if (mirrorOf(c) != c)throw new NotPaired();
			});
		} catch (NotPaired e) {
			PlusDialogs.block("cpmplus.w7view.sym.notPaired");
			return false;
		}
		updateView(c);
		return true;
	}

	private static native void shiftX(Cube c, double dx) /*-{
		c.from[0] += dx;
		c.to[0] += dx;
		// a cube rotated around its own origin: its pivot moves with it
		if (c.origin && (c.rotation && (c.rotation[0] || c.rotation[1] || c.rotation[2]))) c.origin[0] += dx;
	}-*/;

	public static boolean setIgnored(Cube c, boolean ignored) {
		if (c == null)return false;
		return PlusUndo.runOrReport(PlusDialogs.tr("cpmplus.w7view.sym.undo.ignore"), UndoData.make(c), () -> UndoData.make(c), () -> {
			if (ignored)c.getData().putExtra(IGNORE_KEY, Boolean.TRUE);
			else c.getData().removeExtra(IGNORE_KEY);
		});
	}

	private static native void updateView(Cube c) /*-{
		try {
			if ($wnd.Canvas && $wnd.Canvas.updateView) $wnd.Canvas.updateView({elements: [c], element_aspects: {geometry: true, transform: true}});
		} catch (e) {}
	}-*/;

	/** aligns every listed pair whose difference is below 0.1 px, keeping the chosen side ('r' = the cube with x &lt; 0 ... see report order) */
	public static int alignAllTiny(boolean keepFirst) {
		List<Issue> l = report();
		List<Cube[]> todo = new ArrayList<>();
		for (Issue i : l) {
			if (!"pair".equals(i.kind) || i.maxMove >= 0.1 || needsConfirm(keepFirst ? i.other : i.cube, keepFirst ? i.cube : i.other))continue;
			todo.add(keepFirst ? new Cube[] {i.other, i.cube} : new Cube[] {i.cube, i.other});
		}
		if (todo.isEmpty())return 0;
		Cube[] targets = new Cube[todo.size()];
		for (int i = 0; i < targets.length; i++)targets[i] = todo.get(i)[0];
		int[] done = new int[1];
		try {
			PlusUndo.run(PlusDialogs.tr("cpmplus.w7view.sym.undo.align"), UndoData.make(targets), () -> UndoData.make(targets), () -> {
				for (Cube[] p : todo)mirrorFrom(p[0], p[1]);
				for (Cube[] p : todo)if (mirrorOf(p[0]) != p[1])throw new NotPaired();
				done[0] = todo.size();
			});
		} catch (NotPaired e) {
			PlusDialogs.block("cpmplus.w7view.sym.notPaired");
			return 0;
		}
		for (Cube c : targets)updateView(c);
		return done[0];
	}

	// ------------------------------------------------------------------ dialog

	private static void reopenLater() {
		DomGlobal.setTimeout(a -> open(), 50);
	}

	public static void open() {
		if (!Painter.available()) {
			PlusDialogs.block("cpmplus.w7view.sym.noPainter");
			return;
		}
		List<Issue> issues = report();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("help", null, "cpmplus.w7view.sym.help"));
		if (issues.isEmpty()) {
			rows.add(FormRow.info("ok", null, "cpmplus.w7view.sym.allPaired"));
		} else {
			rows.add(FormRow.info("count", null, PlusDialogs.trf("cpmplus.w7view.sym.count", issues.size())));
			int tiny = 0;
			for (Issue i : issues)if ("pair".equals(i.kind) && i.maxMove < 0.1)tiny++;
			if (tiny > 0) {
				rows.add(FormRow.buttons("tiny", PlusDialogs.trf("cpmplus.w7view.sym.tiny", tiny), new String[] {"cpmplus.w7view.sym.btn.keepFirst", "cpmplus.w7view.sym.btn.keepSecond"}, idx -> {
					closeDialog();
					int cnt = alignAllTiny(idx == 0);
					if (cnt > 0)PlusDialogs.done(PlusDialogs.trf("cpmplus.w7view.sym.done.tiny", cnt));
					reopenLater();
				}));
			}
			int n = 0;
			for (Issue is : issues) {
				if (n++ >= 40) {
					rows.add(FormRow.info("more", null, PlusDialogs.trf("cpmplus.w7view.sym.more", issues.size() - 40)));
					break;
				}
				final Issue fi = is;
				String key = "row" + n;
				if ("pair".equals(is.kind)) {
					String label = PlusDialogs.trf("cpmplus.w7view.sym.row.pair", is.cube.name, is.other.name, is.diff);
					rows.add(FormRow.buttons(key, label, new String[] {
							PlusDialogs.trf("cpmplus.w7view.sym.btn.useFirst", is.cube.name),
							PlusDialogs.trf("cpmplus.w7view.sym.btn.useSecond", is.other.name),
							"cpmplus.w7view.sym.btn.ignore", "cpmplus.w7view.sym.btn.select"}, idx -> {
								if (idx == 0)act(() -> align(fi.other, fi.cube, false), "cpmplus.w7view.sym.done.align", fi.other.name);
								else if (idx == 1)act(() -> align(fi.cube, fi.other, false), "cpmplus.w7view.sym.done.align", fi.cube.name);
								else if (idx == 2)act(() -> setIgnored(fi.cube, true) && setIgnored(fi.other, true), "cpmplus.w7view.sym.done.ignore", fi.cube.name);
								else select(fi.cube, fi.other);
							}));
				} else if ("center".equals(is.kind)) {
					String label = PlusDialogs.trf("cpmplus.w7view.sym.row.center", is.cube.name, is.diff);
					rows.add(FormRow.buttons(key, label, new String[] {"cpmplus.w7view.sym.btn.center", "cpmplus.w7view.sym.btn.ignore", "cpmplus.w7view.sym.btn.select"}, idx -> {
						if (idx == 0)act(() -> center(fi.cube, false), "cpmplus.w7view.sym.done.center", fi.cube.name);
						else if (idx == 1)act(() -> setIgnored(fi.cube, true), "cpmplus.w7view.sym.done.ignore", fi.cube.name);
						else select(fi.cube, null);
					}));
				} else {
					String label = PlusDialogs.trf("cpmplus.w7view.sym.row.none", is.cube.name, is.diff);
					rows.add(FormRow.buttons(key, label, new String[] {"cpmplus.w7view.sym.btn.ignore", "cpmplus.w7view.sym.btn.select"}, idx -> {
						if (idx == 0)act(() -> setIgnored(fi.cube, true), "cpmplus.w7view.sym.done.ignore", fi.cube.name);
						else select(fi.cube, null);
					}));
				}
			}
		}
		rows.add(FormRow.info("note", null, "cpmplus.w7view.sym.note"));
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.width = 640;
		o.confirmKey = "cpmplus.w7view.btn.close";
		PlusDialogs.form("cpmplus_w7view_symmetry", "cpmplus.w7view.sym.title", rows, r -> {
		}, o);
	}

	private interface Act {
		boolean run();
	}

	private static void act(Act a, String doneKey, String name) {
		closeDialog();
		if (a.run()) {
			PlusDialogs.done(PlusDialogs.trf(doneKey, name));
			reopenLater();
		}
	}

	private static native void closeDialog() /*-{
		try {
			var d = $wnd.Dialog && $wnd.Dialog.open;
			if (d && d.id === 'cpmplus_w7view_symmetry') d.close(0);
		} catch (e) {}
	}-*/;

	private static native void select(Cube a, Cube b) /*-{
		try {
			$wnd.unselectAllElements && $wnd.unselectAllElements();
			a.select();
			if (b) b.selectLow ? b.selectLow() : b.select({shiftKey: true});
			$wnd.updateSelection && $wnd.updateSelection();
			if ($wnd.Preview && $wnd.Preview.selected && $wnd.BarItems && $wnd.BarItems.focus_on_selection) $wnd.BarItems.focus_on_selection.click();
		} catch (e) {}
	}-*/;

	// ------------------------------------------------------------------ paint mode badge

	private static void badgeLater() {
		DomGlobal.setTimeout(a -> {
			try {
				badge();
			} catch (Throwable e) {
			}
		}, 300);
	}

	private static void badge() {
		if (!CPMCodec.isCPM() || !paintMirrorOn())return;
		double now = System.currentTimeMillis();
		if (now - lastBadge < 5000)return;
		lastBadge = now;
		int n = unpairedCount();
		if (n <= 0)return;
		PlusDialogs.toast(PlusDialogs.trf("cpmplus.w7view.sym.badge", n), "flip", 10000, SymmetryCheck::open);
	}

	private static native boolean paintMirrorOn() /*-{
		try {
			var m = $wnd.Modes && $wnd.Modes.selected;
			if (!m || m.id !== 'paint') return false;
			var b = $wnd.BarItems && $wnd.BarItems.mirror_painting;
			if (b && b.value) return true;
			return !!($wnd.Painter && $wnd.Painter.mirror_painting);
		} catch (e) { return false; }
	}-*/;

	// ------------------------------------------------------------------ test JSON

	private static Object toJs(List<Issue> l) {
		JsPropertyMap<Object> r = result();
		JsArray<Object> a = new JsArray<>();
		for (Issue i : l) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("cube", i.cube.name);
			m.set("other", i.other != null ? i.other.name : null);
			m.set("kind", i.kind);
			m.set("diff", i.diff);
			m.set("maxMove", i.maxMove);
			a.push(m);
		}
		r.set("issues", a);
		r.set("unpaired", (double) unpairedCount());
		return r;
	}

	static String esc(String s) {
		return Dialog.esc(s);
	}
}
