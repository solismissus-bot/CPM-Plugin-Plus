package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;

import elemental2.dom.DomGlobal;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Kit de transições'. CPM never blends between poses: a pose / gesture switch is a hard cut. Two remedies:
 * <ul>
 * <li>'Entrada e saída suaves': CPM's own staged animations. A SETUP named 'p:&lt;pose&gt;' / 'c:&lt;custom pose&gt;' /
 * 'g:&lt;id before #&gt;' plays once before the loop (the loop waits, StagedAnimation), a FINISH plays once after it.
 * The names use the exact predicate of CPM's EditorAnim.findLinkedAnims. The entry goes from the rest pose (or the
 * first frame of another animation) to the pose the linked animations start with (composed like CPM does: ascending
 * priority, non-additive sets, additive adds); the exit goes from that pose back. The exit only starts from the right
 * pose when the loop finishes its cycle (mustFinish) and the loop is closed, so mustFinish is set (one option).</li>
 * <li>'Relatório de estalos': for poses that cut into each other (parado / andando / correndo / agachado ...), the
 * biggest jump of each part between any moment of one and the first frame of the other.</li>
 * </ul>
 * Blockbench renames an exact duplicate name when an animation is re-added (Ctrl+Z / Ctrl+Y): an entry and an exit
 * share the name, so after every undo / redo a staged name that no longer links but links without its trailing digits
 * is put back.
 */
public final class Transitions {
	private Transitions() {
	}

	// ====================================================================== link names

	/** null when the animation can get an entry / exit, else the lang key of the reason */
	static String ineligible(Animation a) {
		if (a == null)return "cpmplus.w3animtools.err.noAnimation";
		Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
		if (tp == null)return "cpmplus.w3animtools.trans.why.noType";
		AnimationType at = tp.getKey();
		if (at.isStaged() || AnimEdit.isStaged(a))return "cpmplus.w3animtools.trans.why.staged";
		if (at == AnimationType.VALUE_LAYER)return "cpmplus.w3animtools.trans.why.value";
		if (at == AnimationType.POSE) {
			if (tp.getValue().hasStateGetter())return "cpmplus.w3animtools.trans.why.value";
			if (tp.getValue() == VanillaPose.GLOBAL)return "cpmplus.w3animtools.trans.why.global";
		}
		if (at != AnimationType.GESTURE && !"loop".equals(a.loop))return "cpmplus.w3animtools.trans.why.loop";
		return null;
	}

	/** 'p:walking' / 'c:Sentar' / 'g:Dance4' (null when the type has none) */
	static String linkName(Animation a) {
		Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
		if (tp == null)return null;
		switch (tp.getKey()) {
		case POSE:
			return "p:" + tp.getValue().name().toLowerCase(Locale.ROOT);
		case CUSTOM_POSE:
			return "c:" + a.name;
		case GESTURE:
		case LAYER:
			return "g:" + idOf(a.name);
		default:
			return null;
		}
	}

	static String idOf(String name) {
		if (name == null)return "";
		int i = name.indexOf('#');
		return i < 0 ? name : name.substring(0, i);
	}

	/** the non-staged animations a staged name links to (EditorAnim.findLinkedAnims, case-sensitive) */
	static List<Animation> linkedMains(String name) {
		List<Animation> out = new ArrayList<>();
		if (name == null)return out;
		String[] nm = name.split(":", 2);
		if (nm.length != 2)return out;
		for (Animation a : AnimEdit.all()) {
			Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
			if (tp == null || tp.getKey().isStaged() || AnimEdit.isStaged(a))continue;
			boolean hit;
			switch (nm[0]) {
			case "p":
				hit = tp.getKey() == AnimationType.POSE && nm[1].equals(tp.getValue().name().toLowerCase(Locale.ROOT));
				break;
			case "c":
				hit = tp.getKey() == AnimationType.CUSTOM_POSE && nm[1].equals(a.name);
				break;
			case "g":
				hit = (tp.getKey() == AnimationType.GESTURE || tp.getKey() == AnimationType.LAYER || tp.getKey() == AnimationType.VALUE_LAYER) && idOf(a.name).equals(nm[1]);
				break;
			default:
				hit = false;
			}
			if (hit)out.add(a);
		}
		return out;
	}

	/** existing staged animations ('setup' / 'finish') with exactly this name */
	static List<Animation> staged(String name, String type) {
		List<Animation> out = new ArrayList<>();
		for (Animation a : AnimEdit.all())if (name.equals(a.name) && a.type != null && type.equalsIgnoreCase(a.type))out.add(a);
		return out;
	}

	// ====================================================================== the pose the linked animations show

	static final class Track {
		final Group g;
		final String ch;

		Track(Group g, String ch) {
			this.g = g;
			this.ch = ch;
		}
	}

	static final String[] CHS = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE};

	static List<Track> tracks(List<Animation> anims) {
		Map<String, Track> m = new LinkedHashMap<>();
		for (Animation a : anims)for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null)continue;
			for (String ch : CHS)if (AnimxUtil.hasKeys(ba, ch))m.putIfAbsent(g.uuid + "|" + ch, new Track(g, ch));
		}
		return new ArrayList<>(m.values());
	}

	/** the value CPM shows for a track when these animations play together: ascending priority, non-additive sets, additive adds */
	static double[] composite(List<Animation> anims, Track t, double time) {
		List<Animation> sorted = new ArrayList<>(anims);
		Collections.sort(sorted, (a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
		double[] v = AnimxUtil.rest(t.ch);
		v = new double[] {v[0], v[1], v[2]};
		boolean scale = KeyMath.SCALE.equals(t.ch);
		for (Animation a : sorted) {
			BoneAnimator ba = AnimEdit.animatorOf(a, t.g);
			if (!AnimxUtil.hasKeys(ba, t.ch))continue;
			double tt = time;
			if (a.length > 1e-6 && "loop".equals(a.loop))tt = time % a.length;
			double[] s = AnimEdit.sample(ba, t.ch, Math.min(tt, Math.max(0, a.length)));
			if (s == null)continue;
			for (int i = 0; i < 3; i++) {
				if (!a.additive)v[i] = s[i];
				else if (scale)v[i] *= s[i];
				else v[i] += s[i];
			}
		}
		return v;
	}

	/** every linked animation closes its loop (value at 0 == value at the end, 1e-3) */
	static boolean loopsClosed(List<Animation> anims) {
		for (Animation a : anims)for (BoneAnimator ba : AnimEdit.boneAnimators(a))for (String ch : CHS) {
			if (!AnimxUtil.hasKeys(ba, ch))continue;
			double[] v0 = AnimEdit.sample(ba, ch, 0), v1 = AnimEdit.sample(ba, ch, a.length);
			if (v0 == null || v1 == null)continue;
			for (int i = 0; i < 3; i++)if (Math.abs(v0[i] - v1[i]) > 1e-3)return false;
		}
		return true;
	}

	// ====================================================================== entry / exit

	public static final class Opts {
		public Animation main;
		public boolean entry = true, exit = true;
		public double entryDur = 0.25, exitDur = 0.25;
		/** null = rest pose, else the animation whose first frame the entry starts from / the exit goes to */
		public Animation from, to;
		public boolean mustFinish = true;
	}

	public static Result make(Opts o, boolean apply) {
		Result r = new Result();
		String why = ineligible(o.main);
		if (why != null)return r.error("ineligible", t(why, o.main != null ? o.main.name : ""));
		if (!o.entry && !o.exit)return r.error("nothing", t("cpmplus.w3animtools.trans.err.nothing"));
		String name = linkName(o.main);
		List<Animation> mains = linkedMains(name);
		if (name == null || !mains.contains(o.main))return r.error("link", t("cpmplus.w3animtools.trans.err.link", o.main.name));
		double minD = 1.0 / Math.max(1, AnimEdit.snappingOf(o.main));
		if (o.entry && o.entryDur < minD - 1e-9 || o.exit && o.exitDur < minD - 1e-9)return r.error("tooShort", t("cpmplus.w3animtools.trans.err.tooShort", PoseColumn.fmt(minD)));
		List<Track> tracks = tracks(mains);
		boolean closed = loopsClosed(mains);
		boolean gesture = AnimxUtil.typeOf(o.main).getKey() == AnimationType.GESTURE && !"loop".equals(o.main.loop);
		if (o.exit && !closed && !gesture)r.warn("notClosed", t("cpmplus.w3animtools.trans.warn.notClosed"));
		if (o.exit && !o.mustFinish && !gesture)r.warn("noMustFinish", t("cpmplus.w3animtools.trans.warn.noMustFinish"));
		// companions of a custom pose ('Sentar#física') are not linked by 'c:Sentar' (CPM links custom poses by the full name)
		if (name.startsWith("c:")) {
			int comp = 0;
			for (Animation a : AnimEdit.all()) {
				Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
				if (a != o.main && tp != null && tp.getKey() == AnimationType.CUSTOM_POSE && idOf(a.name).equals(o.main.name))comp++;
			}
			if (comp > 0)r.warn("companions", t("cpmplus.w3animtools.trans.warn.companions", comp));
		}
		List<Animation> old = new ArrayList<>();
		if (o.entry)old.addAll(staged(name, "setup"));
		if (o.exit)old.addAll(staged(name, "finish"));
		r.put("name", name).put("mains", (double) mains.size()).put("tracks", (double) tracks.size()).put("closed", closed).put("replaced", (double) old.size());
		if (!apply)return r;
		if (tracks.isEmpty())return r.error("noTracks", t("cpmplus.w3animtools.trans.err.noTracks", o.main.name));
		List<Animation> before = new ArrayList<>(mains);
		before.addAll(old);
		List<Animation> made = new ArrayList<>();
		boolean setMust = o.exit && o.mustFinish && !gesture;
		PlusUndo.run(t("cpmplus.w3animtools.trans.undo", name), AnimxUtil.track(UndoData.make(before.toArray(new Animation[0])), before), () -> {
			List<Animation> l = new ArrayList<>(mains);
			l.addAll(made);
			return AnimxUtil.track(UndoData.make(l.toArray(new Animation[0])), l);
		}, () -> {
			for (Animation a : old)a.remove(false, false);
			if (o.entry) {
				Animation e = newStaged(name, "setup", o.main, o.entryDur);
				for (Track tr : tracks) {
					double[] a = o.from != null ? composite(Collections.singletonList(o.from), tr, 0) : AnimxUtil.rest(tr.ch);
					double[] b = composite(mains, tr, 0);
					writePair(e, tr, a, b, o.entryDur);
				}
				made.add(e);
			}
			if (o.exit) {
				Animation x = newStaged(name, "finish", o.main, o.exitDur);
				for (Track tr : tracks) {
					double[] a = composite(mains, tr, gesture ? o.main.length : 0);
					double[] b = o.to != null ? composite(Collections.singletonList(o.to), tr, 0) : AnimxUtil.rest(tr.ch);
					writePair(x, tr, a, b, o.exitDur);
				}
				made.add(x);
				if (setMust)for (Animation m : mains)m.setMustFinish(true);
			}
		});
		r.changed = true;
		r.put("mustFinish", setMust);
		if (!made.isEmpty())made.get(0).select();
		AnimToolSupport.refreshPreview();
		return r;
	}

	/** a staged animation: added FIRST, named after (Blockbench renames exact duplicates on add) */
	static Animation newStaged(String name, String type, Animation main, double len) {
		Animation a = AnimxUtil.newAnimation(name, type, "once", len, AnimEdit.snappingOf(main));
		AnimEdit.copySnapping(main, a);
		AnimxUtil.copyFlags(main, a);
		return a;
	}

	private static void writePair(Animation a, Track tr, double[] from, double[] to, double d) {
		BoneAnimator ba = a.getBoneAnimator(tr.g);
		AnimxUtil.addKey(ba, AnimxUtil.keyData(tr.ch, 0, "linear", from[0], from[1], from[2]));
		AnimxUtil.addKey(ba, AnimxUtil.keyData(tr.ch, d, "linear", to[0], to[1], to[2]));
	}

	/** CPM+ &gt; Animação &gt; 'Entrada e saída suaves...' */
	public static void openDialog() {
		Animation main = Animation.selected;
		main = LayerSupport.originalOf(main, Physics.SUFFIXES);
		main = LayerSupport.originalOf(main, CorrectionLayer.SUFFIXES);
		String why = ineligible(main);
		if (why != null) {
			PlusDialogs.block(t(why, main != null ? main.name : ""));
			return;
		}
		Animation fm = main;
		String name = linkName(main);
		Map<String, String> others = new LinkedHashMap<>();
		others.put("", t("cpmplus.w3animtools.trans.rest"));
		for (Animation a : AnimEdit.all())if (a != main && ineligible(a) == null)others.put(a.uuid, a.name);
		boolean hasEntry = !staged(name, "setup").isEmpty(), hasExit = !staged(name, "finish").isEmpty();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.trans.intro", name, linkedMains(name).size())));
		rows.add(FormRow.checkbox("entry", "cpmplus.w3animtools.trans.entry", true).description(hasEntry ? "cpmplus.w3animtools.trans.replaces" : "cpmplus.w3animtools.trans.entry.desc"));
		rows.add(FormRow.decimal("entryDur", "cpmplus.w3animtools.trans.dur", 0.25, 0.05, 5, 0.05).when(fr -> fr.getBool("entry")));
		rows.add(FormRow.select("from", "cpmplus.w3animtools.trans.from", others, "").when(fr -> fr.getBool("entry")));
		rows.add(FormRow.checkbox("exit", "cpmplus.w3animtools.trans.exit", true).description(hasExit ? "cpmplus.w3animtools.trans.replaces" : "cpmplus.w3animtools.trans.exit.desc"));
		rows.add(FormRow.decimal("exitDur", "cpmplus.w3animtools.trans.dur", 0.25, 0.05, 5, 0.05).when(fr -> fr.getBool("exit")));
		rows.add(FormRow.select("to", "cpmplus.w3animtools.trans.to", others, "").when(fr -> fr.getBool("exit")));
		rows.add(FormRow.checkbox("mustFinish", "cpmplus.w3animtools.trans.mustFinish", true).description("cpmplus.w3animtools.trans.mustFinish.desc")
				.when(fr -> fr.getBool("exit")));
		FormOpts fo = new FormOpts();
		fo.width = 580;
		fo.confirmKey = "cpmplus.w3animtools.trans.ok";
		PlusDialogs.form("cpmplus_w3animtools_trans", "cpmplus.w3animtools.trans.title", rows, fr -> {
			Opts o = new Opts();
			o.main = fm;
			o.entry = fr.getBool("entry");
			o.exit = fr.getBool("exit");
			o.entryDur = fr.getDouble("entryDur", 0.25);
			o.exitDur = fr.getDouble("exitDur", 0.25);
			o.from = byUuid(fr.getString("from"));
			o.to = byUuid(fr.getString("to"));
			o.mustFinish = fr.getBool("mustFinish");
			Result r = make(o, true);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				return;
			}
			PlusDialogs.done(t("cpmplus.w3animtools.trans.done", name));
			if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 10000, null);
		}, fo);
	}

	static Animation byUuid(String uuid) {
		if (uuid == null || uuid.isEmpty())return null;
		for (Animation a : AnimEdit.all())if (uuid.equals(a.uuid))return a;
		return null;
	}

	// ====================================================================== Blockbench renames duplicates on re-add

	public static void installNameGuard() {
		PluginStart.addEventListener("load_undo_save", e -> DomGlobal.setTimeout(a -> fixNames(), 0));
	}

	/** staged names Blockbench renamed ('p:walking2') put back when only the original name links; returns how many */
	public static int fixNames() {
		int n = 0;
		try {
			for (Animation a : AnimEdit.all()) {
				if (a.type == null || !(a.type.equalsIgnoreCase("setup") || a.type.equalsIgnoreCase("finish")))continue;
				String nm = a.name;
				if (nm == null || nm.length() < 3 || nm.charAt(1) != ':' || !linkedMains(nm).isEmpty())continue;
				String base = nm.replaceAll("\\d+$", "");
				if (base.equals(nm) || linkedMains(base).isEmpty())continue;
				a.name = base;
				n++;
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not check the entry / exit names", e);
		}
		return n;
	}

	// ====================================================================== cut report

	/** poses that cut into each other in game (both directions) */
	static final VanillaPose[][] PAIRS = {
			{VanillaPose.STANDING, VanillaPose.WALKING}, {VanillaPose.WALKING, VanillaPose.RUNNING}, {VanillaPose.STANDING, VanillaPose.RUNNING},
			{VanillaPose.STANDING, VanillaPose.SNEAKING}, {VanillaPose.SNEAKING, VanillaPose.SNEAK_WALK}, {VanillaPose.WALKING, VanillaPose.SNEAK_WALK},
			{VanillaPose.STANDING, VanillaPose.JUMPING}, {VanillaPose.WALKING, VanillaPose.JUMPING}, {VanillaPose.RUNNING, VanillaPose.JUMPING},
			{VanillaPose.STANDING, VanillaPose.FALLING}, {VanillaPose.WALKING, VanillaPose.FALLING}, {VanillaPose.STANDING, VanillaPose.SWIMMING},
			{VanillaPose.STANDING, VanillaPose.CRAWLING}, {VanillaPose.STANDING, VanillaPose.FLYING}, {VanillaPose.STANDING, VanillaPose.RIDING},
			{VanillaPose.STANDING, VanillaPose.CREATIVE_FLYING},
	};

	public static final class Jump {
		public VanillaPose from, to;
		public Group g;
		public String ch;
		/** largest / average distance (degrees or pixels) between any moment of 'from' and the first frame of 'to' */
		public double worst, typical;
	}

	static List<Animation> poseAnims(VanillaPose p) {
		List<Animation> out = new ArrayList<>();
		for (Animation a : AnimEdit.all()) {
			Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
			if (tp != null && tp.getKey() == AnimationType.POSE && tp.getValue() == p && !AnimEdit.isStaged(a))out.add(a);
		}
		return out;
	}

	/** every cut between two animated poses with a jump above the thresholds, biggest first */
	public static List<Jump> report(double degThreshold, double pxThreshold) {
		List<Jump> out = new ArrayList<>();
		for (VanillaPose[] pr : PAIRS)for (int dir = 0; dir < 2; dir++) {
			VanillaPose a = pr[dir], b = pr[1 - dir];
			List<Animation> la = poseAnims(a), lb = poseAnims(b);
			if (la.isEmpty() && lb.isEmpty())continue;
			// the entry of 'b' already takes care of the start when it exists
			if (!staged("p:" + b.name().toLowerCase(Locale.ROOT), "setup").isEmpty())continue;
			List<Animation> both = new ArrayList<>(la);
			both.addAll(lb);
			double lenA = 0;
			for (Animation x : la)lenA = Math.max(lenA, x.length);
			int samples = lenA > 1e-6 ? 24 : 1;
			for (Track tr : tracks(both)) {
				if (KeyMath.SCALE.equals(tr.ch))continue;
				double[] start = composite(lb, tr, 0);
				double worst = 0, sum = 0;
				for (int s = 0; s < samples; s++) {
					double time = samples > 1 ? s * lenA / samples : 0;
					double[] v = composite(la, tr, time);
					double d = 0;
					for (int i = 0; i < 3; i++)d = Math.max(d, Math.abs(v[i] - start[i]));
					worst = Math.max(worst, d);
					sum += d;
				}
				boolean rot = KeyMath.ROT.equals(tr.ch);
				if (worst < (rot ? degThreshold : pxThreshold))continue;
				Jump j = new Jump();
				j.from = a;
				j.to = b;
				j.g = tr.g;
				j.ch = tr.ch;
				j.worst = worst;
				j.typical = sum / samples;
				out.add(j);
			}
		}
		Collections.sort(out, (x, y) -> Double.compare(y.worst, x.worst));
		return out;
	}

	/** '35°' / '3 px' */
	public static String worstText(Jump j) {
		return PoseColumn.fmt(j.worst) + (KeyMath.ROT.equals(j.ch) ? "°" : " px");
	}

	static String poseLabel(VanillaPose p) {
		return PlusDialogs.tr("label.cpm.pose." + p.name().toLowerCase(Locale.ROOT));
	}

	/** CPM+ &gt; Animação &gt; 'Relatório de estalos (trocas de pose)...' */
	public static void openReport() {
		List<Jump> js = report(15, 2);
		if (js.isEmpty()) {
			PlusDialogs.done(t("cpmplus.w3animtools.trans.report.none"));
			return;
		}
		StringBuilder html = new StringBuilder("<table style='width:100%;border-collapse:collapse'><tr><th style='text-align:left'>")
				.append(PlusDialogs.esc(t("cpmplus.w3animtools.trans.report.cut"))).append("</th><th style='text-align:left'>")
				.append(PlusDialogs.esc(t("cpmplus.w3animtools.trans.report.part"))).append("</th><th>")
				.append(PlusDialogs.esc(t("cpmplus.w3animtools.trans.report.worst"))).append("</th><th>")
				.append(PlusDialogs.esc(t("cpmplus.w3animtools.trans.report.typical"))).append("</th></tr>");
		int shown = 0;
		List<VanillaPose[]> fixable = new ArrayList<>();
		for (Jump j : js) {
			if (shown++ >= 14)break;
			String unit = KeyMath.ROT.equals(j.ch) ? "°" : " px";
			html.append("<tr><td>").append(PlusDialogs.esc(poseLabel(j.from) + " → " + poseLabel(j.to))).append("</td><td>")
			.append(PlusDialogs.esc(j.g.name + " (" + t(KeyMath.ROT.equals(j.ch) ? "cpmplus.w3animtools.ch.rotation" : "cpmplus.w3animtools.ch.position") + ")"))
			.append("</td><td style='text-align:center'>").append(PoseColumn.fmt(j.worst)).append(unit)
			.append("</td><td style='text-align:center'>").append(PoseColumn.fmt(j.typical)).append(unit).append("</td></tr>");
			boolean seen = false;
			for (VanillaPose[] f : fixable)if (f[0] == j.from && f[1] == j.to)seen = true;
			if (!seen && !poseAnims(j.to).isEmpty() && fixable.size() < 4)fixable.add(new VanillaPose[] {j.from, j.to});
		}
		html.append("</table>");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.trans.report.intro")));
		rows.add(FormRow.htmlInfo("table", null, html.toString()));
		rows.add(FormRow.info("how", null, t("cpmplus.w3animtools.trans.report.how")));
		if (!fixable.isEmpty()) {
			String[] labels = new String[fixable.size()];
			for (int i = 0; i < labels.length; i++)labels[i] = t("cpmplus.w3animtools.trans.report.fix", poseLabel(fixable.get(i)[0]), poseLabel(fixable.get(i)[1]));
			rows.add(FormRow.buttons("fix", "cpmplus.w3animtools.trans.report.fixLabel", labels, i -> {
				VanillaPose[] f = fixable.get(i);
				Opts o = new Opts();
				o.main = poseAnims(f[1]).get(0);
				o.entry = true;
				o.exit = false;
				o.entryDur = 0.2;
				List<Animation> src = poseAnims(f[0]);
				o.from = src.isEmpty() ? null : src.get(0);
				Result r = make(o, true);
				if (!r.ok)PlusDialogs.block(r.errorText());
				else PlusDialogs.quick(t("cpmplus.w3animtools.trans.done", String.valueOf(r.extra.get("name"))), 5000);
			}));
		}
		FormOpts fo = new FormOpts();
		fo.width = 720;
		fo.confirmKey = "cpmplus.w3animtools.trans.report.close";
		PlusDialogs.form("cpmplus_w3animtools_trans_report", "cpmplus.w3animtools.trans.report.title", rows, fr -> {}, fo);
	}

	// ====================================================================== QA hook

	/** transitions({action: make | report | fixNames, anim, entry, exit, entryDur, exitDur, from, to, mustFinish, apply, deg, px}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		String action = AnimToolSupport.str(opts, "action", "make");
		if ("fixNames".equals(action))return new Result().put("fixed", (double) fixNames()).toJs();
		if ("report".equals(action)) {
			Result r = new Result();
			List<Jump> js = report(AnimToolSupport.num(opts, "deg", 15), AnimToolSupport.num(opts, "px", 2));
			elemental2.core.JsArray<Object> arr = new elemental2.core.JsArray<>();
			for (Jump j : js) {
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("from", j.from.name().toLowerCase(Locale.ROOT));
				m.set("to", j.to.name().toLowerCase(Locale.ROOT));
				m.set("part", j.g.name);
				m.set("ch", j.ch);
				m.set("worst", Math.round(j.worst * 100) / 100.0);
				m.set("typical", Math.round(j.typical * 100) / 100.0);
				arr.push(m);
			}
			JsPropertyMap<Object> js2 = r.toJs();
			js2.set("jumps", arr);
			return js2;
		}
		Opts o = new Opts();
		o.main = AnimToolSupport.anim(opts);
		o.entry = AnimToolSupport.bool(opts, "entry", true);
		o.exit = AnimToolSupport.bool(opts, "exit", true);
		o.entryDur = AnimToolSupport.num(opts, "entryDur", 0.25);
		o.exitDur = AnimToolSupport.num(opts, "exitDur", 0.25);
		String f = AnimToolSupport.str(opts, "from", null), to = AnimToolSupport.str(opts, "to", null);
		o.from = f != null ? AnimEdit.byName(f) : null;
		o.to = to != null ? AnimEdit.byName(to) : null;
		o.mustFinish = AnimToolSupport.bool(opts, "mustFinish", true);
		return make(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
	}
}
