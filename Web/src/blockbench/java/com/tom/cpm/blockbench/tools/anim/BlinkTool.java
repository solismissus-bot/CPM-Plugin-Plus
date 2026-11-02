package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.BlinkGenerator.Schedule;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;

/**
 * CPM+ (W3-ANIMGEN, F4 'Gerar piscada…'): makes an eye blink by itself in game. The open-eye group is shown and the
 * closed-eye group hidden at rest (closed group cpm_hidden = true); a new looping, additive 'global' animation
 * ('Piscar') switches their visibility at the blink frames of {@link BlinkGenerator}. One of the two groups may be
 * left empty. Root parts can't be used (CPM can't hide a root part).
 */
public final class BlinkTool {
	private BlinkTool() {
	}

	/** what the core made */
	public static final class Result {
		public Animation animation;
		public Schedule schedule;
		public final List<String> warnings = new ArrayList<>();
	}

	/** user-facing reason (translated) why these groups can't blink, or null */
	public static String problem(Group open, Group closed) {
		if (open == null && closed == null)return AnimGenBB.tr("cpmplus.w3animgen.blink.err.none");
		if (open != null && open == closed)return AnimGenBB.tr("cpmplus.w3animgen.blink.err.same");
		for (Group g : new Group[] {open, closed}) {
			if (g != null && AnimGenBB.isRoot(g))return AnimGenBB.trf("cpmplus.w3animgen.blink.err.root", g.name);
		}
		if (open != null && closed != null && (AnimGenBB.isSameOrInside(open, closed) || AnimGenBB.isSameOrInside(closed, open)))
			return AnimGenBB.tr("cpmplus.w3animgen.blink.err.inside");
		return null;
	}

	/**
	 * Core (dialog and test hook): ONE undo entry creates the animation, writes the keys on the grid and hides the
	 * closed-eye group at rest. Throws IllegalArgumentException with a user message when the groups can't blink.
	 */
	public static Result apply(Group open, Group closed, Schedule s, String name) {
		String p = problem(open, closed);
		if (p != null)throw new IllegalArgumentException(p);
		String lp = BlinkGenerator.limitProblem(s);
		if (lp != null)throw new IllegalArgumentException(AnimGenBB.tr("cpmplus.w3animgen.blink.err." + ("length".equals(lp) ? "length" : "frames")));
		Result r = new Result();
		r.schedule = s;
		Group[] touched = closed != null ? new Group[] {closed} : new Group[0];
		UndoData before = new UndoData();
		before.animations = new Animation[0];
		before.groups = touched;
		String label = AnimGenBB.tr("cpmplus.w3animgen.blink.undo");
		PlusUndo.run(label, before, () -> {
			UndoData a = new UndoData();
			a.animations = r.animation != null ? new Animation[] {r.animation} : new Animation[0];
			a.groups = touched;
			return a;
		}, () -> {
			Animation an = AnimGenBB.newAnimation(name != null && !name.trim().isEmpty() ? name.trim() : AnimGenBB.tr("cpmplus.w3animgen.blink.animName"),
					"global", true, s.length(), s.fps);
			r.animation = an;
			if (open != null)write(an, open, s.keys(false), s);
			if (closed != null) {
				write(an, closed, s.keys(true), s);
				closed.hidden = true;
			}
		});
		if (s.durRaised)r.warnings.add(AnimGenBB.tr("cpmplus.w3animgen.blink.warn.oneFrame"));
		if (s.shortLoop)r.warnings.add(AnimGenBB.trf("cpmplus.w3animgen.blink.warn.shortLoop", AnimGenBB.num(s.length())));
		return r;
	}

	private static void write(Animation an, Group g, List<int[]> keys, Schedule s) {
		BoneAnimator ba = AnimGenBB.animator(an, g);
		for (int[] k : keys)AnimGenBB.addKey(ba, "cpm_visibility", s.time(k[0]), "step", AnimGenBB.vis(k[1] == 1));
	}

	// ------------------------------------------------------------------ dialog

	/** the dialog; from = the group the user right-clicked (or the selected one), may be null */
	public static void open(Group from) {
		Group open = null, closed = null;
		if (from != null && !AnimGenBB.isRoot(from)) {
			if (looksClosed(from.name)) {
				closed = from;
				open = sibling(from, false);
			} else {
				open = from;
				closed = sibling(from, true);
			}
		}
		long[] seed = {System.currentTimeMillis() & 0xFFFFFFL};
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w3animgen.blink.intro"));
		rows.add(FormRow.select("open", "cpmplus.w3animgen.blink.open", AnimGenBB.groupOptions("cpmplus.w3animgen.common.none"), open != null ? open.uuid : ""));
		rows.add(FormRow.select("closed", "cpmplus.w3animgen.blink.closed", AnimGenBB.groupOptions("cpmplus.w3animgen.common.none"), closed != null ? closed.uuid : ""));
		rows.add(FormRow.decimal("every", "cpmplus.w3animgen.blink.every", PlusPrefs.getNum("cpmplus.blinkEvery", 3), 0.5, 30, 0.05));
		rows.add(FormRow.decimal("dur", "cpmplus.w3animgen.blink.dur", PlusPrefs.getNum("cpmplus.blinkDur", 0.15), 0.01, 2, 0.01));
		rows.add(FormRow.checkbox("random", "cpmplus.w3animgen.blink.random", PlusPrefs.getBool("cpmplus.blinkRandom", false))
				.description("cpmplus.w3animgen.blink.random.desc"));
		rows.add(FormRow.buttons("reroll", null, new String[] {"cpmplus.w3animgen.blink.reroll"}, i -> {
			seed[0] = (seed[0] * 31 + 17 + (System.currentTimeMillis() & 0xFFFF)) & 0xFFFFFFL;
			PlusDialogs.quick(AnimGenBB.trf("cpmplus.w3animgen.blink.rerolled", String.valueOf(seed[0])), 1500);
		}).when(r -> r.getBool("random")));
		rows.add(FormRow.checkbox("dbl", "cpmplus.w3animgen.blink.double", PlusPrefs.getBool("cpmplus.blinkDouble", false))
				.description("cpmplus.w3animgen.blink.double.desc"));
		rows.add(FormRow.number("fps", "cpmplus.w3animgen.common.fps", 20, 1, 120).description("cpmplus.w3animgen.common.fps.desc"));
		FormOpts o = new FormOpts();
		o.confirmKey = "cpmplus.w3animgen.blink.create";
		PlusDialogs.form("cpmplus_w3animgen_blink", "cpmplus.w3animgen.blink.title", rows, r -> {
			Group go = AnimGenBB.byUuid(r.getString("open")), gc = AnimGenBB.byUuid(r.getString("closed"));
			String p = problem(go, gc);
			if (p != null) {
				r.keepOpen();
				PlusDialogs.block(p);
				return;
			}
			double every = r.getDouble("every", 3), dur = r.getDouble("dur", 0.15);
			int fps = Math.max(1, Math.min(120, r.getInt("fps", 20)));
			boolean random = r.getBool("random"), dbl = r.getBool("dbl");
			PlusPrefs.set("cpmplus.blinkEvery", every);
			PlusPrefs.set("cpmplus.blinkDur", dur);
			PlusPrefs.set("cpmplus.blinkRandom", random);
			PlusPrefs.set("cpmplus.blinkDouble", dbl);
			if (BlinkGenerator.tooShort(dur, fps)) {
				int better = betterFps(every, dur, random, dbl, seed[0]);
				String msg = AnimGenBB.trf("cpmplus.w3animgen.blink.short", AnimGenBB.num(dur), fps);
				String[] btn = better > 0 ? new String[] {AnimGenBB.trf("cpmplus.w3animgen.blink.raiseFps", better), "cpmplus.w3animgen.blink.useOneFrame", "cpmplus.w0binfra.btn.cancel"}
						: new String[] {"cpmplus.w3animgen.blink.useOneFrame", "cpmplus.w0binfra.btn.cancel"};
				PlusDialogs.messageBox("cpmplus.w3animgen.blink.title", msg, btn, b -> {
					if (better > 0 && b == 0)run(go, gc, every, dur, better, random, dbl, seed[0]);
					else if ((better > 0 && b == 1) || (better <= 0 && b == 0))run(go, gc, every, dur, fps, random, dbl, seed[0]);
				});
				return;
			}
			run(go, gc, every, dur, fps, random, dbl, seed[0]);
		}, o);
	}

	/** the smallest fps (from the frames limit's point of view) at which the blink lasts one frame, or -1 */
	public static int betterFps(double every, double dur, boolean random, boolean dbl, long seed) {
		for (int f = BlinkGenerator.minFpsFor(dur); f <= 120; f++) {
			try {
				Schedule s = BlinkGenerator.schedule(every, dur, f, random, dbl, seed);
				if (BlinkGenerator.limitProblem(s) == null && !s.durRaised)return f;
			} catch (IllegalArgumentException e) {
			}
		}
		return -1;
	}

	private static void run(Group open, Group closed, double every, double dur, int fps, boolean random, boolean dbl, long seed) {
		Schedule s;
		try {
			s = BlinkGenerator.schedule(every, dur, fps, random, dbl, seed);
		} catch (IllegalArgumentException e) {
			PlusDialogs.block(AnimGenBB.tr("cpmplus.w3animgen.blink.err.every"));
			return;
		}
		Result r;
		try {
			r = apply(open, closed, s, null);
		} catch (IllegalArgumentException e) {
			PlusDialogs.block(e.getMessage());
			return;
		}
		AnimGenBB.showAnimation(r.animation);
		PlusDialogs.done(AnimGenBB.trf("cpmplus.w3animgen.blink.done", r.animation.name, r.schedule.blinks.size(), AnimGenBB.num(r.schedule.length())));
		if (!r.warnings.isEmpty())PlusDialogs.info("cpmplus_w3animgen_blink_note", "cpmplus.w3animgen.blink.title", r.warnings);
	}

	private static boolean looksClosed(String n) {
		String s = n == null ? "" : n.toLowerCase(Locale.ROOT);
		return s.contains("fechad") || s.contains("closed") || s.contains("shut") || s.contains("piscand");
	}

	/** the other eye group next to g (aberto &lt;-&gt; fechado, open &lt;-&gt; closed), or null */
	private static Group sibling(Group g, boolean wantClosed) {
		Group p = AnimGenBB.parentGroup(g);
		if (p == null)return null;
		for (int i = 0; i < p.children.length; i++) {
			OutlinerNode c = p.children.getAt(i);
			if (c instanceof Group && c != g) {
				String n = ((Group) c).name.toLowerCase(Locale.ROOT);
				boolean cl = looksClosed(n);
				boolean op = n.contains("abert") || n.contains("open");
				if (wantClosed ? cl : (op && !cl))return (Group) c;
			}
		}
		return null;
	}
}
