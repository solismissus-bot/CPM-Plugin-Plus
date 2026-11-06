package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
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

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Editar keys em várias animações': one operation on the keys of many animations at once, ONE undo entry.
 * <ul>
 * <li>Velocidade: key times (and bezier handle times) and the length times f (50 % = twice as fast).</li>
 * <li>Intensidade: rotation / position values times f around rest, size 1 + (s - 1) * f, for the chosen parts.</li>
 * <li>Trocar a peça: every key of part A moves to part B (B's keys of those channels are replaced).</li>
 * <li>Copiar a trilha: the keys of a part in the current animation are copied into the others (optionally stretched
 * to each animation's length).</li>
 * <li>Apagar a trilha: every key of a part.</li>
 * </ul>
 * Formula keys are skipped by the value operations; staged and companion layers are listed like any animation.
 */
public final class BatchKeys {
	private BatchKeys() {
	}

	public enum Op {SPEED, INTENSITY, RETARGET, COPY, DELETE}

	public static final class Options {
		public Op op = Op.SPEED;
		public List<Animation> anims = new ArrayList<>();
		/** the part (null = every part, only for SPEED / INTENSITY) */
		public Group part;
		/** RETARGET destination */
		public Group to;
		public boolean children = true;
		/** percent */
		public double factor = 100;
		/** COPY: the animation to copy from */
		public Animation source;
		/** COPY: stretch the track to each animation's length */
		public boolean stretch = true;
	}

	static final String[] ALL = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE, KeyMath.VIS, KeyMath.COLOR};
	static final String[] MOTION = {KeyMath.ROT, KeyMath.POS, KeyMath.SCALE};

	static Set<Group> parts(Options o) {
		Set<Group> s = new LinkedHashSet<>();
		if (o.part == null)return s;
		s.add(o.part);
		if (o.children)s.addAll(AnimxUtil.descendants(o.part));
		return s;
	}

	public static Result run(Options o, boolean apply) {
		Result r = new Result();
		if (o.anims.isEmpty())return r.error("noAnims", t("cpmplus.w3animtools.batch.err.noAnims"));
		if ((o.op == Op.RETARGET || o.op == Op.COPY || o.op == Op.DELETE) && o.part == null)return r.error("noPart", t("cpmplus.w3animtools.err.selectPart"));
		if (o.op == Op.RETARGET && (o.to == null || o.to == o.part))return r.error("noTarget", t("cpmplus.w3animtools.batch.err.noTarget"));
		if ((o.op == Op.SPEED || o.op == Op.INTENSITY) && Math.abs(o.factor - 100) < 1e-9)return r.error("noChange", t("cpmplus.w3animtools.batch.err.noChange"));
		if (o.op == Op.SPEED && o.factor < 1)return r.error("noChange", t("cpmplus.w3animtools.batch.err.noChange"));
		if (o.op == Op.COPY && o.source == null)return r.error("noSource", t("cpmplus.w3animtools.err.noAnimation"));
		List<Animation> targets = new ArrayList<>(o.anims);
		if (o.op == Op.COPY)targets.remove(o.source);
		int[] keys = new int[1];
		int[] formulas = new int[1];
		int[] tooLong = new int[1];
		Set<Animation> touched = new LinkedHashSet<>();
		Runnable body = () -> {
			for (Animation a : targets) {
				int n = 0;
				switch (o.op) {
				case SPEED:
					n = speed(a, o.factor / 100, apply);
					if (a.length * o.factor / 100 * 1000 > AnimEdit.MAX_DURATION_MS)tooLong[0]++;
					break;
				case INTENSITY:
					n = intensity(a, o, formulas, apply);
					break;
				case RETARGET:
					n = retarget(a, o.part, o.to, o.children, apply);
					break;
				case COPY:
					n = copy(o.source, a, parts(o), o.stretch, apply);
					break;
				case DELETE:
					n = delete(a, parts(o), apply);
					break;
				default:
				}
				if (n > 0) {
					keys[0] += n;
					touched.add(a);
				}
			}
		};
		if (!apply) {
			body.run();
		} else {
			List<Animation> all = new ArrayList<>(targets);
			// count first: nothing to do = no undo entry
			Options dry = o;
			int before = 0;
			for (Animation a : targets)before += count(a, dry);
			if (before == 0 && o.op != Op.COPY)return r.put("keys", 0.0).put("anims", 0.0);
			PlusUndo.run(t("cpmplus.w3animtools.batch.undo." + o.op.name().toLowerCase()), AnimxUtil.track(UndoData.make(all.toArray(new Animation[0])), all),
					() -> AnimxUtil.track(UndoData.make(all.toArray(new Animation[0])), all), body);
			r.changed = keys[0] > 0;
			AnimToolSupport.refreshPreview();
		}
		if (formulas[0] > 0)r.warn("molang", t("cpmplus.w3animtools.warn.formulaSkipped", formulas[0]));
		if (tooLong[0] > 0)r.warn("tooLong", t("cpmplus.w3animtools.batch.warn.tooLong", tooLong[0]));
		r.put("keys", (double) keys[0]).put("anims", (double) touched.size());
		return r;
	}

	/** keys the operation would touch in one animation (dry run) */
	static int count(Animation a, Options o) {
		switch (o.op) {
		case SPEED:
			return speed(a, o.factor / 100, false);
		case INTENSITY:
			return intensity(a, o, new int[1], false);
		case RETARGET:
			return retarget(a, o.part, o.to, o.children, false);
		case DELETE:
			return delete(a, parts(o), false);
		default:
			return 1;
		}
	}

	// ====================================================================== operations

	static int speed(Animation a, double f, boolean apply) {
		int n = 0;
		for (BoneAnimator ba : AnimEdit.boneAnimators(a))for (String ch : ALL) {
			JsArrayE<Keyframe> arr = ba.channel(ch);
			if (arr == null)continue;
			for (int i = 0; i < arr.length; i++) {
				Keyframe kf = arr.getAt(i);
				if (apply) {
					kf.time = (float) (Math.round(kf.time * f * 1e6) / 1e6);
					scaleHandles(kf, f);
				}
				n++;
			}
		}
		if (apply && n >= 0)a.length = (float) (Math.round(a.length * f * 1e6) / 1e6);
		return n;
	}

	private static native void scaleHandles(Object kf, double f) /*-{
		var fs = ['bezier_left_time', 'bezier_right_time'];
		for (var i = 0; i < fs.length; i++) { var v = kf[fs[i]]; if (v && v.length >= 3) { v[0] *= f; v[1] *= f; v[2] *= f; } }
	}-*/;

	static int intensity(Animation a, Options o, int[] formulas, boolean apply) {
		int n = 0;
		double f = o.factor / 100;
		Set<Group> only = parts(o);
		for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			Group g = AnimEdit.groupOf(ba);
			if (g == null || o.part != null && !only.contains(g))continue;
			for (String ch : MOTION) {
				JsArrayE<Keyframe> arr = ba.channel(ch);
				if (arr == null)continue;
				for (int i = 0; i < arr.length; i++) {
					Keyframe kf = arr.getAt(i);
					if (AnimEdit.hasMolang(kf)) {
						formulas[0]++;
						continue;
					}
					n++;
					if (!apply)continue;
					int dps = Math.max(1, AnimxUtil.dataPoints(kf));
					KeyData k = new KeyData();
					k.v = new double[dps][3];
					for (int d = 0; d < dps; d++)for (int x = 0; x < 3; x++) {
						double v = AnimxUtil.value(kf, d, x);
						if (Double.isNaN(v))v = KeyMath.SCALE.equals(ch) ? 1 : 0;
						k.v[d][x] = KeyMath.SCALE.equals(ch) ? 1 + (v - 1) * f : v * f;
					}
					Exaggerate.write(kf, k);
				}
			}
		}
		return n;
	}

	/** A (and, with children, the groups inside it matched by position in the tree) -&gt; B */
	static int retarget(Animation a, Group from, Group to, boolean children, boolean apply) {
		Map<Group, Group> map = new LinkedHashMap<>();
		map.put(from, to);
		if (children)pairChildren(from, to, map);
		int n = 0;
		for (Map.Entry<Group, Group> e : map.entrySet()) {
			BoneAnimator src = AnimEdit.animatorOf(a, e.getKey());
			if (src == null)continue;
			for (String ch : ALL) {
				List<KeyData> keys = AnimEdit.readKeys(src, ch);
				if (keys.isEmpty())continue;
				n += keys.size();
				if (!apply)continue;
				AnimEdit.writeKeys(a.getBoneAnimator(e.getValue()), ch, keys, true);
				AnimEdit.clearChannel(src, ch);
			}
		}
		return n;
	}

	/** children of a and b paired by name, then by order among the unmatched */
	static void pairChildren(Group a, Group b, Map<Group, Group> out) {
		List<Group> ca = childGroups(a), cb = childGroups(b);
		List<Group> left = new ArrayList<>(cb);
		List<Group> unmatched = new ArrayList<>();
		for (Group g : ca) {
			Group m = null;
			for (Group h : left)if (h.name != null && h.name.equals(g.name)) {
				m = h;
				break;
			}
			if (m != null) {
				left.remove(m);
				out.put(g, m);
				pairChildren(g, m, out);
			} else unmatched.add(g);
		}
		for (int i = 0; i < unmatched.size() && i < left.size(); i++) {
			out.put(unmatched.get(i), left.get(i));
			pairChildren(unmatched.get(i), left.get(i), out);
		}
	}

	static List<Group> childGroups(Group g) {
		List<Group> l = new ArrayList<>();
		if (g.children != null)for (int i = 0; i < g.children.length; i++) {
			Object c = g.children.getAt(i);
			if (c instanceof Group)l.add((Group) c);
		}
		return l;
	}

	static int copy(Animation src, Animation dst, Set<Group> parts, boolean stretch, boolean apply) {
		int n = 0;
		double f = stretch && src.length > 1e-6 && dst.length > 1e-6 ? dst.length / src.length : 1;
		for (Group g : parts) {
			BoneAnimator sb = AnimEdit.animatorOf(src, g);
			if (sb == null)continue;
			for (String ch : ALL) {
				List<KeyData> keys = AnimEdit.readKeys(sb, ch);
				if (keys.isEmpty())continue;
				n += keys.size();
				if (!apply)continue;
				for (KeyData k : keys) {
					k.time = Math.round(k.time * f * 1e6) / 1e6;
					if (k.bLT != null)for (int i = 0; i < 3; i++)k.bLT[i] *= f;
					if (k.bRT != null)for (int i = 0; i < 3; i++)k.bRT[i] *= f;
				}
				AnimEdit.writeKeys(dst.getBoneAnimator(g), ch, keys, true);
			}
		}
		return n;
	}

	static int delete(Animation a, Set<Group> parts, boolean apply) {
		int n = 0;
		for (Group g : parts) {
			BoneAnimator ba = AnimEdit.animatorOf(a, g);
			if (ba == null)continue;
			for (String ch : ALL) {
				int c = AnimEdit.count(ba, ch);
				n += c;
				if (apply && c > 0)AnimEdit.clearChannel(ba, ch);
			}
		}
		return n;
	}

	// ====================================================================== dialog

	/** CPM+ &gt; Animação &gt; 'Editar keys em várias animações...' */
	public static void openDialog() {
		Animation cur = Animation.selected;
		List<Group> sel = AnimToolSupport.selectedGroups();
		Map<String, String> animOpts = new LinkedHashMap<>();
		for (Animation a : AnimEdit.all())animOpts.put(a.uuid, a.name);
		if (animOpts.isEmpty()) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		Map<String, String> groups = new LinkedHashMap<>();
		groups.put("", t("cpmplus.w3animtools.batch.allParts"));
		for (Group g : com.tom.cpm.blockbench.proxy.Group.all)groups.put(g.uuid, AnimEdit.pathOf(g));
		String part0 = sel.isEmpty() ? "" : sel.get(0).uuid;
		List<String> pre = new ArrayList<>();
		if (cur != null)pre.add(cur.uuid);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.batch.intro")));
		rows.add(FormRow.radio("op", "cpmplus.w3animtools.batch.op", PlusDialogs.options(
				"speed", "cpmplus.w3animtools.batch.op.speed",
				"intensity", "cpmplus.w3animtools.batch.op.intensity",
				"retarget", "cpmplus.w3animtools.batch.op.retarget",
				"copy", "cpmplus.w3animtools.batch.op.copy",
				"delete", "cpmplus.w3animtools.batch.op.delete"), "speed"));
		rows.add(FormRow.multiSelect("anims", "cpmplus.w3animtools.batch.anims", animOpts, pre));
		rows.add(FormRow.range("factor", "cpmplus.w3animtools.batch.factor", 100, 10, 300, 5).description("cpmplus.w3animtools.batch.factor.desc")
				.when(fr -> "speed".equals(fr.getString("op")) || "intensity".equals(fr.getString("op"))));
		rows.add(FormRow.select("part", "cpmplus.w3animtools.batch.part", groups, part0).when(fr -> !"speed".equals(fr.getString("op"))));
		rows.add(FormRow.select("to", "cpmplus.w3animtools.batch.to", groups, "").when(fr -> "retarget".equals(fr.getString("op"))));
		rows.add(FormRow.checkbox("children", "cpmplus.w3animtools.batch.children", true).when(fr -> !"speed".equals(fr.getString("op"))));
		rows.add(FormRow.checkbox("stretch", "cpmplus.w3animtools.batch.stretch", true).description("cpmplus.w3animtools.batch.stretch.desc")
				.when(fr -> "copy".equals(fr.getString("op"))));
		if (cur != null)rows.add(FormRow.info("copyFrom", null, t("cpmplus.w3animtools.batch.copyFrom", cur.name)).when(fr -> "copy".equals(fr.getString("op"))));
		FormOpts fo = new FormOpts();
		fo.width = 620;
		fo.confirmKey = "cpmplus.w3animtools.batch.ok";
		PlusDialogs.form("cpmplus_w3animtools_batch", "cpmplus.w3animtools.batch.title", rows, fr -> {
			Options o = fromForm(fr, cur);
			Result r = run(o, true);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				fr.keepOpen();
				return;
			}
			if (!r.changed)PlusDialogs.quick(t("cpmplus.w3animtools.batch.nothing"), 4000);
			else PlusDialogs.done(t("cpmplus.w3animtools.batch.done", ((Double) r.extra.get("keys")).intValue(), ((Double) r.extra.get("anims")).intValue()));
			if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 8000, null);
		}, fo);
	}

	static Options fromForm(FormResult fr, Animation cur) {
		Options o = new Options();
		String op = fr.getString("op");
		o.op = "intensity".equals(op) ? Op.INTENSITY : "retarget".equals(op) ? Op.RETARGET : "copy".equals(op) ? Op.COPY : "delete".equals(op) ? Op.DELETE : Op.SPEED;
		for (String u : fr.getList("anims"))for (Animation a : AnimEdit.all())if (u.equals(a.uuid))o.anims.add(a);
		o.factor = fr.getDouble("factor", 100);
		o.part = byUuid(fr.getString("part"));
		o.to = byUuid(fr.getString("to"));
		o.children = fr.getBool("children");
		o.stretch = fr.getBool("stretch");
		o.source = cur;
		return o;
	}

	static Group byUuid(String uuid) {
		if (uuid == null || uuid.isEmpty() || Group.uuids == null)return null;
		Object g = Group.uuids.get(uuid);
		return g instanceof Group ? (Group) g : null;
	}

	// ====================================================================== QA hook

	/** batchKeys({op, anims: [names] | 'all', part, to, children, factor, source, stretch, apply}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		Options o = new Options();
		String op = AnimToolSupport.str(opts, "op", "speed");
		o.op = Op.valueOf(op.toUpperCase());
		Object an = opts.get("anims");
		if ("all".equals(an))o.anims.addAll(AnimEdit.all());
		else for (String s : AnimToolSupport.list(opts, "anims")) {
			Animation a = AnimEdit.byName(s);
			if (a != null)o.anims.add(a);
		}
		String p = AnimToolSupport.str(opts, "part", null), to = AnimToolSupport.str(opts, "to", null);
		o.part = p != null ? AnimToolSupport.group(p) : null;
		o.to = to != null ? AnimToolSupport.group(to) : null;
		o.children = AnimToolSupport.bool(opts, "children", true);
		o.factor = AnimToolSupport.num(opts, "factor", 100);
		String src = AnimToolSupport.str(opts, "source", null);
		o.source = src != null ? AnimEdit.byName(src) : Animation.selected;
		o.stretch = AnimToolSupport.bool(opts, "stretch", true);
		return run(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
	}
}
