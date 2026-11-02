package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PartPairing;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W2-ANIMCORE, F2 = the interface of D3) 'Criar cópia espelhada': mirrors a WHOLE animation left/right into a
 * new animation '&lt;nome&gt; (espelhada)' (or in place). Parts are paired by full outliner path (PartPairing):
 * paired parts swap their movement (both directions computed from the ORIGINAL keys before anything is written),
 * parts without a side are mirrored in place ("ela mesma"). Every animator is used (Object.values(anim.animators),
 * also the ones the timeline does not show). The copy keeps loop, length, snapping and every cpm_* property; vanilla
 * poses *_LEFT &lt;-&gt; *_RIGHT are swapped (HEAD_ROTATION_YAW keeps its type, with a warning); the priority is copied
 * unchanged. Blockbench's own flip_animation is not used (timeline-only animators, in place, no visibility/colour,
 * no pt-BR names).
 */
public final class MirrorAnimation {
	private MirrorAnimation() {
	}

	public static final String SUFFIX_KEY = "cpmplus.w2animcore.f2.suffix";

	public static final class Opts {
		public Animation anim;
		public boolean asNew = true;
		/** true: paired parts swap; false: every part is mirrored in place */
		public boolean swap = true;
		/** source group uuid -&gt; destination group (null entry = use the automatic pair) */
		public Map<String, Group> pairs = new HashMap<>();
		/** sources the user unticked (not mirrored at all) */
		public List<String> excluded = new ArrayList<>();
		public String name;
	}

	/** one row of the pair table */
	public static final class Row {
		public final Group part;
		public Group dest;
		public final PartPairing.Kind kind;
		public final Group suggestion;
		public boolean include = true;

		Row(Group part, Group dest, PartPairing.Kind kind, Group suggestion) {
			this.part = part;
			this.dest = dest;
			this.kind = kind;
			this.suggestion = suggestion;
		}
	}

	/** every animated part of the animation with its automatic destination (PAIRED partner, else itself) */
	public static List<Row> table(Animation anim) {
		List<Row> rows = new ArrayList<>();
		for (BoneAnimator ba : AnimEdit.boneAnimators(anim)) {
			if (!ba.hasTransform())continue;
			Group g = AnimEdit.groupOf(ba);
			if (g == null)continue;
			PartPairing.Pair<Object> p = AnimEdit.pairOf(g);
			Group dest = p.kind == PartPairing.Kind.PAIRED ? (Group) p.target : g;
			Group sug = p.kind == PartPairing.Kind.SUGGESTION && p.target instanceof Group ? (Group) p.target : null;
			rows.add(new Row(g, dest, p.kind, sug));
		}
		return rows;
	}

	/** 'teste' -&gt; 'teste (espelhada)', 'wave#2' -&gt; 'wave (espelhada)#2' (unique) */
	public static String copyName(String name) {
		return AnimEdit.uniqueAnimName(name, " " + t(SUFFIX_KEY));
	}

	/** the vanilla pose on the other side ('holding_left' -&gt; 'holding_right'), or null when the type has no side */
	public static String mirrorType(String type) {
		if (type == null)return null;
		String up = type.toUpperCase(Locale.ROOT);
		String other = up.endsWith("_LEFT") ? up.substring(0, up.length() - 5) + "_RIGHT" : up.endsWith("_RIGHT") ? up.substring(0, up.length() - 6) + "_LEFT" : null;
		if (other == null)return null;
		for (VanillaPose p : VanillaPose.VALUES)if (p.name().equals(other))return other.toLowerCase(Locale.ROOT);
		return null;
	}

	public static Result check(Opts o) {
		return run(o, false);
	}

	public static Result apply(Opts o) {
		return run(o, true);
	}

	private static Result run(Opts o, boolean apply) {
		Result r = new Result();
		Animation anim = o.anim;
		if (anim == null) {
			r.error("noAnim", t("cpmplus.w2animcore.err.selectAnim"));
			return r;
		}
		if (o.asNew && AnimEdit.isStaged(anim)) {
			r.error("staged", t("cpmplus.w2animcore.f2.err.staged", anim.name));
			return r;
		}
		PlusTestHooks.time("f2.table");
		List<Row> rows = table(anim);
		PlusTestHooks.timeEnd("f2.table");
		Map<Group, Group> dest = new LinkedHashMap<>();
		int pairsN = 0, selfN = 0;
		for (Row row : rows) {
			if (o.excluded.contains(row.part.uuid))continue;
			Group d = o.pairs.containsKey(row.part.uuid) && o.pairs.get(row.part.uuid) != null ? o.pairs.get(row.part.uuid) : row.dest;
			if (!o.swap)d = row.part;
			dest.put(row.part, d);
			if (d == row.part)selfN++;
			else pairsN++;
		}
		// two sources on one destination: nothing is written
		Map<Group, List<String>> byDest = new LinkedHashMap<>();
		for (Map.Entry<Group, Group> e : dest.entrySet())byDest.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey().name);
		List<String> dups = new ArrayList<>();
		for (Map.Entry<Group, List<String>> e : byDest.entrySet())if (e.getValue().size() > 1)dups.add(e.getKey().name + " <- " + String.join(", ", e.getValue()));
		r.put("parts", (double) dest.size()).put("pairs", (double) pairsN).put("self", (double) selfN);
		List<Object> dupList = new ArrayList<>(dups);
		r.put("duplicates", dupList);
		if (!dups.isEmpty()) {
			r.error("duplicateDest", t("cpmplus.w2animcore.f2.err.duplicate", String.join("; ", dups)));
			return r;
		}
		if (dest.isEmpty()) {
			r.error("nothing", t("cpmplus.w2animcore.f2.err.nothing", anim.name));
			return r;
		}
		// compute every output from the ORIGINAL keys first (a swap goes both ways)
		PlusTestHooks.time("f2.read");
		Map<Group, Map<String, List<KeyData>>> out = new LinkedHashMap<>();
		int[] skipped = {0};
		int keys = 0;
		boolean global = false;
		for (Map.Entry<Group, Group> e : dest.entrySet()) {
			BoneAnimator sa = AnimEdit.animatorOf(anim, e.getKey());
			if (sa == null)continue;
			if (sa.rotation_global)global = true;
			Map<String, List<KeyData>> perCh = new LinkedHashMap<>();
			for (String ch : KeyMath.CHANNELS) {
				List<KeyData> k = AnimEdit.readKeys(sa, ch);
				if (k.isEmpty())continue;
				List<KeyData> m = KeyMath.mirrorAll(ch, k, skipped);
				perCh.put(ch, m);
				keys += m.size();
			}
			out.put(e.getValue(), perCh);
		}
		if (o.asNew) {
			// parts the user unticked keep their own movement in the copy, unmirrored (unless a ticked part's
			// mirrored movement goes there)
			for (Row row : rows) {
				if (!o.excluded.contains(row.part.uuid) || out.containsKey(row.part))continue;
				BoneAnimator sa = AnimEdit.animatorOf(anim, row.part);
				if (sa == null)continue;
				Map<String, List<KeyData>> perCh = new LinkedHashMap<>();
				for (String ch : KeyMath.CHANNELS) {
					List<KeyData> k = AnimEdit.readKeys(sa, ch);
					if (!k.isEmpty())perCh.put(ch, k);
				}
				out.put(row.part, perCh);
			}
		}
		PlusTestHooks.timeEnd("f2.read");
		if (skipped[0] > 0)r.warn("molangSkipped", t("cpmplus.w2animcore.warn.molangSkipped", skipped[0]));
		if (global)r.warn("rotationGlobal", t("cpmplus.w2animcore.warn.rotationGlobal"));
		for (Map.Entry<Group, Group> e : dest.entrySet()) {
			if (e.getKey() != e.getValue() && !AnimToolSupport.restMirrored(e.getKey(), e.getValue())) {
				r.warn("restNotMirrored", t("cpmplus.w2animcore.warn.restNotMirrored", e.getKey().name, e.getValue().name));
				break;
			}
		}
		String newType = mirrorType(anim.type);
		if (anim.type != null && anim.type.equalsIgnoreCase("head_rotation_yaw"))r.warn("headYaw", t("cpmplus.w2animcore.f2.warn.headYaw"));
		if (o.asNew && newType == null && anim.type != null) {
			// a vanilla pose (standing, walking...) without a side: the copy keeps the same pose, so in game both play
			try {
				if (Animation.parseType(anim.type).getKey() == AnimationType.POSE)r.warn("samePose", t("cpmplus.w2animcore.f2.warn.samePose"));
			} catch (Throwable e) {
				// unknown type: nothing to say
			}
		}
		String name = o.asNew ? (o.name != null && !o.name.trim().isEmpty() ? o.name.trim() : copyName(anim.name)) : anim.name;
		if (o.asNew && AnimEdit.byName(name) != null)name = AnimEdit.uniqueAnimName(name, "");
		r.put("keys", (double) keys).put("name", name).put("type", newType != null ? newType : anim.type != null ? anim.type : "");
		// Convention 10: one more layer-controlled custom pose / gesture must still fit in the free skin layers
		if (o.asNew && anim.layerCtrl && anim.type != null) {
			try {
				AnimationType at = Animation.parseType(anim.type).getKey();
				if (at == AnimationType.CUSTOM_POSE || at == AnimationType.GESTURE) {
					LayerCapacity.Result cur = LayerCapacity.checkProject();
					LayerCapacity.Result next = LayerCapacity.check(cur.count + 1, cur.freeLayers);
					if (!next.ok())r.warn("layerCapacity", t("cpmplus.w2animcore.f2.warn.layerCapacity", next.count, next.capacity));
				}
			} catch (Throwable e) {
				// the count is advice only; the export check repeats it
			}
		}
		if (!apply)return r;
		String label = t(o.asNew ? "cpmplus.w2animcore.f2.undo" : "cpmplus.w2animcore.f2.undoInPlace");
		if (o.asNew) {
			String fname = name;
			PlusTestHooks.time("f2.create");
			Animation created = PlusUndo.createAnimation(label, () -> {
				Animation a = new Animation();
				a.extend(copyData(anim, fname, newType));
				AnimEdit.copySnapping(anim, a);// extend clamps snapping to 10..500
				copyCpmProps(anim, a, newType != null);// exact values (extend turns a null into the default)
				// keys first, then into the project: once the animation is listed, Blockbench's panels observe it
				// and every added key costs much more (thousands of keys on big models)
				PlusTestHooks.time("f2.write");
				write(a, out, true);
				PlusTestHooks.timeEnd("f2.write");
				return a.add(false);
			});
			PlusTestHooks.timeEnd("f2.create");
			r.put("uuid", created.uuid);
			r.put("name", created.name);
		} else {
			List<Animation> l = new ArrayList<>();
			l.add(anim);
			PlusUndo.editAnimations(l, label, () -> {
				// the parts whose movement moves away are cleared too (their old keys belong to the other side now)
				for (Group src : dest.keySet()) {
					BoneAnimator sa = AnimEdit.animatorOf(anim, src);
					if (sa != null)for (String ch : KeyMath.CHANNELS)AnimEdit.clearChannel(sa, ch);
				}
				write(anim, out, true);
				if (newType != null)anim.type = newType;
			});
		}
		r.changed = true;
		PlusTestHooks.time("f2.preview");
		AnimToolSupport.refreshPreview();
		PlusTestHooks.timeEnd("f2.preview");
		return r;
	}

	private static void write(Animation a, Map<Group, Map<String, List<KeyData>>> out, boolean replace) {
		for (Map.Entry<Group, Map<String, List<KeyData>>> e : out.entrySet()) {
			BoneAnimator da = a.getBoneAnimator(e.getKey());
			for (Map.Entry<String, List<KeyData>> c : e.getValue().entrySet())AnimEdit.writeKeys(da, c.getKey(), c.getValue(), replace);
		}
	}

	/** the original's data (name, loop, length, snapping, every registered property incl. cpm_*), no keys / uuid */
	private static Object copyData(Animation src, String name, String newType) {
		JsPropertyMap<Object> d = Js.uncheckedCast(clone(src.getUndoCopy(JsPropertyMap.of(), false)));
		d.delete("uuid");
		d.delete("animators");
		d.delete("selected");
		d.delete("saved_name");
		d.set("name", name);
		if (newType != null)d.set("cpm_type", newType);
		return d;
	}

	/** every registered cpm_* animation property copied as is (null stays null), except cpm_type when it was remapped */
	private static native void copyCpmProps(Object src, Object dst, boolean skipType) /*-{
		var props = $wnd.Animation && $wnd.Animation.properties;
		if (!props) return;
		for (var k in props) {
			if (k.indexOf('cpm_') !== 0 || (skipType && k === 'cpm_type')) continue;
			var v = src[k];
			dst[k] = (v && typeof v === 'object') ? JSON.parse(JSON.stringify(v)) : v;
		}
	}-*/;

	private static native Object clone(Object o) /*-{
		return JSON.parse(JSON.stringify(o));
	}-*/;

	// ====================================================================== dialog

	private static final String COUNTER_ID = "cpmplus_w2animcore_f2_counter";

	/** 'Criar cópia espelhada...' for the selected animation */
	public static void openDialog() {
		Animation anim = Animation.selected;
		if (anim == null) {
			PlusDialogs.block(t("cpmplus.w2animcore.err.selectAnim"));
			return;
		}
		List<Row> rows = table(anim);
		if (rows.isEmpty()) {
			PlusDialogs.block(t("cpmplus.w2animcore.f2.err.nothing", anim.name));
			return;
		}
		boolean staged = AnimEdit.isStaged(anim);
		Map<String, String> groupOpts = new LinkedHashMap<>();
		groupOpts.put("self", t("cpmplus.w2animcore.f2.self"));
		for (Group g : Group.all)groupOpts.put(g.uuid, shortPath(AnimEdit.pathOf(g)));
		List<FormRow> form = new ArrayList<>();
		form.add(FormRow.info("intro", null, t("cpmplus.w2animcore.f2.intro")));
		if (staged)form.add(FormRow.info("staged", null, t("cpmplus.w2animcore.f2.err.staged", anim.name)));
		form.add(FormRow.checkbox("asNew", "cpmplus.w2animcore.f2.asNew", !staged));
		form.add(FormRow.text("name", "cpmplus.w2animcore.f2.name", copyName(anim.name)).when(fr -> fr.getBool("asNew")));
		form.add(FormRow.checkbox("swap", "cpmplus.w2animcore.f2.swap", true).description("cpmplus.w2animcore.f2.swap.desc"));
		form.add(FormRow.separator());
		int i = 0;
		for (Row row : rows) {
			String full = AnimEdit.pathOf(row.part);
			String desc = t("cpmplus.w2animcore.f2.include") + "\n\n" + full;
			if (row.kind == PartPairing.Kind.SUGGESTION && row.suggestion != null)desc += "\n" + t("cpmplus.w2animcore.f2.suggest", row.suggestion.name);
			form.add(FormRow.checkbox("inc" + i, shortPath(full), true).description(desc));
			form.add(FormRow.select("dst" + i, "cpmplus.w2animcore.f2.goesTo", groupOpts, row.dest == row.part ? "self" : row.dest.uuid).when(fr -> fr.getBool("swap")));
			i++;
		}
		form.add(FormRow.htmlInfo("counter", null, "<span id='" + COUNTER_ID + "'></span>"));
		FormOpts fo = new FormOpts();
		fo.width = 760;
		fo.labelWidth = 330;
		fo.confirmKey = "cpmplus.w2animcore.f2.ok";
		fo.onChange = fr -> updateCounter(optsFrom(anim, rows, fr));
		PlusDialogs.form("cpmplus_w2animcore_f2", "cpmplus.w2animcore.f2.title", form, fr -> {
			Opts o = optsFrom(anim, rows, fr);
			Result chk = check(o);
			if (!chk.ok) {
				PlusDialogs.block(chk.errorText());
				fr.keepOpen();
				return;
			}
			Runnable go = () -> {
				Result res = apply(o);
				if (!res.ok) {
					PlusDialogs.block(res.errorText());
					return;
				}
				Animation shown = o.asNew ? AnimEdit.byName(String.valueOf(res.extra.get("name"))) : anim;
				AnimToolSupport.showAndPlay(shown);
				PlusDialogs.done(t(o.asNew ? "cpmplus.w2animcore.f2.done" : "cpmplus.w2animcore.f2.doneInPlace", String.valueOf(res.extra.get("name"))));
				if (res.hasWarning("headYaw") || res.hasWarning("molangSkipped") || res.hasWarning("layerCapacity") || res.hasWarning("samePose"))PlusDialogs.toast(res.warningText(), "warning", 12000, null);
			};
			if (chk.hasWarning("restNotMirrored"))PlusDialogs.warnContinue(chk.warningText(), go);
			else go.run();
		}, fo);
		DomGlobal.setTimeout(e -> updateCounter(optsFrom(anim, rows, null)), 50);
	}

	/**
	 * 'Right_Wing/…/cube121': the nearest folder that names a side (else the parent) and the part itself,
	 * max ~44 chars; the full path is in the row's '?' help
	 */
	static String shortPath(String path) {
		if (path == null)return "";
		String[] s = path.split("/");
		int n = s.length;
		String r = path;
		if (n > 2) {
			int a = n - 2;
			for (int i = n - 2; i >= 0; i--) {
				if (namesSide(s[i])) {
					a = i;
					break;
				}
			}
			r = s[a] + (a == n - 2 ? "/" : "/…/") + s[n - 1];
		}
		if (r.length() > 44)r = r.substring(0, 20) + "…" + r.substring(r.length() - 23);
		return r;
	}

	private static native boolean namesSide(String n) /*-{
		return /(left|right|esquerd|direit)|(^|[_\-\s.])(l|r|esq|dir)($|[_\-\s.\d])/i.test(n || '');
	}-*/;

	private static Opts optsFrom(Animation anim, List<Row> rows, PlusDialogs.FormResult fr) {
		Opts o = new Opts();
		o.anim = anim;
		o.asNew = !AnimEdit.isStaged(anim);
		if (fr == null)return o;
		o.asNew = fr.getBool("asNew");
		o.swap = fr.getBool("swap");
		o.name = fr.getString("name");
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			if (!fr.getBool("inc" + i)) {
				o.excluded.add(row.part.uuid);
				continue;
			}
			String d = fr.getString("dst" + i);
			o.pairs.put(row.part.uuid, d == null || "self".equals(d) ? row.part : Group.uuids.get(d));
		}
		return o;
	}

	private static void updateCounter(Opts o) {
		Element el = DomGlobal.document.getElementById(COUNTER_ID);
		if (el == null)return;
		Result r = check(o);
		String text = t("cpmplus.w2animcore.f2.counter", num(r, "parts"), num(r, "pairs"), num(r, "self"));
		String color = "var(--color-confirm, #4caf50)";
		if (!r.ok) {
			text += "\n" + r.errorText();
			color = "var(--color-close, #f44336)";
		} else if (!r.warnings.isEmpty()) {
			text += "\n" + r.warningText();
			color = "var(--color-warning, #e6a23c)";
		}
		el.innerHTML = "<span style='color: " + color + ";'>" + PlusDialogs.escLines(text) + "</span>";
		AnimToolSupport.setConfirmEnabled("cpmplus_w2animcore_f2", r.ok);
	}

	private static int num(Result r, String k) {
		Object v = r.extra.get(k);
		return v instanceof Double ? ((Double) v).intValue() : 0;
	}

	// ====================================================================== QA hook

	/** f2({anim, asNew, swap, name, pairs: {srcPath: dstPath | 'self'}, exclude: [paths], apply}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Opts o = new Opts();
		o.anim = AnimToolSupport.anim(opts);
		o.asNew = AnimToolSupport.bool(opts, "asNew", true);
		o.swap = AnimToolSupport.bool(opts, "swap", true);
		o.name = AnimToolSupport.str(opts, "name", null);
		Object p = opts.get("pairs");
		if (p != null && "object".equals(Js.typeof(p))) {
			JsPropertyMap<Object> pm = Js.asPropertyMap(p);
			pm.forEach(k -> {
				Group s = AnimToolSupport.group(k);
				String dv = String.valueOf(pm.get(k));
				Group d = "self".equals(dv) ? s : AnimToolSupport.group(dv);
				if (s != null && d != null)o.pairs.put(s.uuid, d);
			});
		}
		for (String x : AnimToolSupport.list(opts, "exclude")) {
			Group g = AnimToolSupport.group(x);
			if (g != null)o.excluded.add(g.uuid);
		}
		return (AnimToolSupport.bool(opts, "apply", true) ? apply(o) : check(o)).toJs();
	}
}
