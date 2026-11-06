package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
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

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ starter kits for beginners (each creates ready animations in ONE undo entry):
 * <ul>
 * <li>Nova animação pelo que você quer: recipes that set the CPM type, loop, additive and length for the goal (always
 * moving, walk / run style, emote, toggle, custom pose, follow the look) and say when it plays.</li>
 * <li>Gerar andar (2 ou 4 patas): a walk / run cycle on CPM's clock (non-additive, so Minecraft's swing does not fight
 * it): legs opposite, arms opposite to the legs (2 legs) or trot pairs (4 legs), knees / elbows from 'Dobrar membro'
 * bend while the leg comes forward, a small body bob.</li>
 * <li>Kit de rosto: expressions as toggles (layers: one face shown, the others hidden), a mouth that opens with the
 * voice ('speaking' value pose) and eyes / pupils that follow where the head looks (head yaw / pitch value poses).</li>
 * </ul>
 */
public final class SimpleKits {
	private SimpleKits() {
	}

	// ====================================================================== recipes

	static final String[] RECIPES = {"always", "walkStyle", "walkReplace", "runStyle", "gesture", "toggle", "pose", "look"};

	/** {type, loop, additive, length} per recipe */
	static Object[] recipe(String id) {
		switch (id) {
		case "always":
			return new Object[] {"global", "loop", true, 2.0};
		case "walkStyle":
			return new Object[] {"walking", "loop", true, 1.0};
		case "walkReplace":
			return new Object[] {"walking", "loop", false, 0.6};
		case "runStyle":
			return new Object[] {"running", "loop", true, 0.5};
		case "gesture":
			return new Object[] {"gesture", "once", false, 2.0};
		case "toggle":
			return new Object[] {"layer", "loop", true, 1.0};
		case "pose":
			return new Object[] {"custom_pose", "loop", false, 1.0};
		case "look":
			return new Object[] {"head_rotation_yaw", "loop", true, 1.0};
		default:
			return null;
		}
	}

	public static Result createRecipe(String id, String name, List<Group> parts) {
		Result r = new Result();
		Object[] rc = recipe(id);
		if (rc == null)return r.error("recipe", t("cpmplus.w3animtools.recipe.err.unknown"));
		String nm = name != null && !name.trim().isEmpty() ? name.trim() : t("cpmplus.w3animtools.recipe." + id + ".name");
		if (AnimEdit.byName(nm) != null)nm = AnimEdit.uniqueAnimName(nm, "");
		String fn = nm;
		Animation[] made = new Animation[1];
		PlusUndo.run(t("cpmplus.w3animtools.recipe.undo"), UndoData.make(new Animation[0]), () -> UndoData.make(made[0] != null ? new Animation[] {made[0]} : new Animation[0]), () -> {
			Animation a = AnimxUtil.newAnimation(fn, (String) rc[0], (String) rc[1], (Double) rc[3], 20);
			a.additive = (Boolean) rc[2];
			// follow the look: the chosen parts turn from -45 (head to one side) to +45 degrees (to the other)
			if ("look".equals(id))for (Group g : parts)writeTwo(a, g, KeyMath.ROT, new double[] {0, 45, 0}, new double[] {0, -45, 0}, a.length);
			made[0] = a;
		});
		r.changed = true;
		made[0].select();
		AnimToolSupport.refreshPreview();
		return r.put("name", fn).put("type", (String) rc[0]).put("loop", (String) rc[1]).put("additive", (Boolean) rc[2]);
	}

	static void writeTwo(Animation a, Group g, String ch, double[] v0, double[] v1, double len) {
		List<KeyData> keys = new ArrayList<>();
		keys.add(key(0, v0));
		keys.add(key(len, v1));
		AnimEdit.writeKeys(a.getBoneAnimator(g), ch, keys, true);
	}

	static KeyData key(double time, double[] v) {
		KeyData k = new KeyData();
		k.time = Tween.snap(time);
		k.interp = "linear";
		k.molang = false;
		k.v = new double[][] {{v[0], v[1], v[2]}};
		return k;
	}

	/** CPM+ &gt; Animação &gt; 'Nova animação pelo que você quer...' */
	public static void recipeDialog() {
		Map<String, String> opts = new LinkedHashMap<>();
		for (String id : RECIPES)opts.put(id, "cpmplus.w3animtools.recipe." + id);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.recipe.intro")));
		rows.add(FormRow.radio("recipe", "cpmplus.w3animtools.recipe.what", opts, "always"));
		rows.add(FormRow.text("name", "cpmplus.w3animtools.recipe.name", ""));
		for (String id : RECIPES)rows.add(FormRow.info("when_" + id, null, t("cpmplus.w3animtools.recipe." + id + ".when")).when(fr -> id.equals(fr.getString("recipe"))));
		FormOpts fo = new FormOpts();
		fo.width = 620;
		fo.confirmKey = "cpmplus.w3animtools.recipe.ok";
		PlusDialogs.form("cpmplus_w3animtools_recipe", "cpmplus.w3animtools.recipe.title", rows, fr -> {
			String id = fr.getString("recipe");
			List<Group> parts = AnimToolSupport.selectedGroups();
			if ("look".equals(id) && parts.isEmpty()) {
				PlusDialogs.block(t("cpmplus.w3animtools.recipe.err.lookParts"));
				return;
			}
			Result r = createRecipe(id, fr.getString("name"), parts);
			if (!r.ok)PlusDialogs.block(r.errorText());
			else PlusDialogs.done(t("cpmplus.w3animtools.recipe.done", String.valueOf(r.extra.get("name"))));
		}, fo);
	}

	// ====================================================================== walk / run generator

	public static final class Gait {
		public boolean four;
		public boolean run;
		public double cycle = 0.6, legs = 30, arms = 25, knees = 25, bob = 0.5;
		/** 4 legs: front left, front right, back left, back right (null = guess) */
		public Group fl, fr, bl, br;
	}

	static Group root(String n) {
		for (Group g : AnimxUtil.vanillaRoots())if (n.equals(g.name))return g;
		return null;
	}

	static double wave(double ph) {
		return Math.sin(ph * 2 * Math.PI);
	}

	public static Result gait(Gait o, boolean apply) {
		Result r = new Result();
		List<Group> legs = new ArrayList<>();
		List<Double> phases = new ArrayList<>();
		List<Double> amps = new ArrayList<>();
		if (o.four) {
			Group[] g = {o.fl != null ? o.fl : root("right_arm"), o.fr != null ? o.fr : root("left_arm"), o.bl != null ? o.bl : root("right_leg"), o.br != null ? o.br : root("left_leg")};
			double[] ph = {0, 0.5, 0.5, 0};// trot: diagonal pairs together
			for (int i = 0; i < 4; i++)if (g[i] != null) {
				legs.add(g[i]);
				phases.add(ph[i]);
				amps.add(o.legs);
			}
		} else {
			Object[][] parts = {{"right_leg", 0.0, o.legs}, {"left_leg", 0.5, o.legs}, {"right_arm", 0.5, o.arms}, {"left_arm", 0.0, o.arms}};
			for (Object[] p : parts) {
				Group g = root((String) p[0]);
				if (g != null) {
					legs.add(g);
					phases.add((Double) p[1]);
					amps.add((Double) p[2]);
				}
			}
		}
		if (legs.isEmpty())return r.error("noLegs", t("cpmplus.w3animtools.gait.err.noLegs"));
		double cycle = Math.max(0.2, Math.min(4, o.cycle));
		int n = 8;
		Map<Group, List<KeyData>> rot = new LinkedHashMap<>();
		Map<Group, List<KeyData>> joints = new LinkedHashMap<>();
		for (int i = 0; i < legs.size(); i++) {
			Group g = legs.get(i);
			double ph = phases.get(i), amp = amps.get(i);
			List<KeyData> ks = new ArrayList<>();
			for (int k = 0; k <= n; k++)ks.add(smooth(key(k * cycle / n, new double[] {amp * wave(k / (double) n + ph), 0, 0})));
			rot.put(g, ks);
			Group j = EmoteImport.joint(g);
			if (j != null && o.knees > 0) {
				boolean arm = g.name != null && g.name.contains("arm");
				List<KeyData> jk = new ArrayList<>();
				for (int k = 0; k <= n; k++) {
					// the joint bends most while the limb swings forward (knee: lower leg back; elbow: forearm forward)
					double b = Math.max(0, wave(k / (double) n + ph + 0.25)) * o.knees;
					jk.add(smooth(key(k * cycle / n, new double[] {arm ? b : -b, 0, 0})));
				}
				joints.put(j, jk);
			}
		}
		Group body = root("body");
		List<KeyData> bob = new ArrayList<>();
		if (body != null && o.bob > 0)for (int k = 0; k <= n; k++)bob.add(smooth(key(k * cycle / n, new double[] {0, -o.bob * Math.abs(wave(k / (double) n)), 0})));
		r.put("legs", (double) legs.size()).put("joints", (double) joints.size()).put("cycle", cycle);
		if (!apply)return r;
		String name = t(o.run ? "cpmplus.w3animtools.gait.nameRun" : "cpmplus.w3animtools.gait.nameWalk");
		if (AnimEdit.byName(name) != null)name = AnimEdit.uniqueAnimName(name, "");
		String fn = name;
		Animation[] made = new Animation[1];
		PlusUndo.run(t("cpmplus.w3animtools.gait.undo"), UndoData.make(new Animation[0]), () -> UndoData.make(made[0] != null ? new Animation[] {made[0]} : new Animation[0]), () -> {
			Animation a = AnimxUtil.newAnimation(fn, o.run ? "running" : "walking", "loop", cycle, 20);
			a.additive = false;
			for (Map.Entry<Group, List<KeyData>> e : rot.entrySet())AnimEdit.writeKeys(a.getBoneAnimator(e.getKey()), KeyMath.ROT, e.getValue(), true);
			for (Map.Entry<Group, List<KeyData>> e : joints.entrySet())AnimEdit.writeKeys(a.getBoneAnimator(e.getKey()), KeyMath.ROT, e.getValue(), true);
			if (!bob.isEmpty())AnimEdit.writeKeys(a.getBoneAnimator(body), KeyMath.POS, bob, true);
			made[0] = a;
		});
		r.changed = true;
		made[0].select();
		AnimToolSupport.refreshPreview();
		return r.put("name", fn);
	}

	private static KeyData smooth(KeyData k) {
		k.interp = "catmullrom";
		return k;
	}

	/** CPM+ &gt; Animação &gt; 'Gerar andar / correr (2 ou 4 patas)...' */
	public static void gaitDialog() {
		Map<String, String> groups = new LinkedHashMap<>();
		groups.put("", t("cpmplus.w3animtools.gait.guess"));
		for (Group g : Group.all)groups.put(g.uuid, AnimEdit.pathOf(g));
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.gait.intro")));
		rows.add(FormRow.radio("legs", "cpmplus.w3animtools.gait.legs", PlusDialogs.options("2", "cpmplus.w3animtools.gait.legs2", "4", "cpmplus.w3animtools.gait.legs4"), "2"));
		rows.add(FormRow.radio("kind", "cpmplus.w3animtools.gait.kind", PlusDialogs.options("walk", "cpmplus.w3animtools.gait.walk", "run", "cpmplus.w3animtools.gait.run"), "walk"));
		rows.add(FormRow.decimal("cycle", "cpmplus.w3animtools.gait.cycle", 0.6, 0.2, 4, 0.05).description("cpmplus.w3animtools.gait.cycle.desc"));
		rows.add(FormRow.range("legAmp", "cpmplus.w3animtools.gait.legAmp", 30, 5, 80, 1));
		rows.add(FormRow.range("armAmp", "cpmplus.w3animtools.gait.armAmp", 25, 0, 80, 1).when(fr -> "2".equals(fr.getString("legs"))));
		rows.add(FormRow.range("knees", "cpmplus.w3animtools.gait.knees", 25, 0, 90, 1).description("cpmplus.w3animtools.gait.knees.desc"));
		rows.add(FormRow.decimal("bob", "cpmplus.w3animtools.gait.bob", 0.5, 0, 4, 0.1));
		for (String k : new String[] {"fl", "fr", "bl", "br"})rows.add(FormRow.select(k, "cpmplus.w3animtools.gait." + k, groups, "").when(fr -> "4".equals(fr.getString("legs"))));
		rows.add(FormRow.info("note", null, t("cpmplus.w3animtools.gait.note")));
		FormOpts fo = new FormOpts();
		fo.width = 620;
		fo.confirmKey = "cpmplus.w3animtools.gait.ok";
		PlusDialogs.form("cpmplus_w3animtools_gait", "cpmplus.w3animtools.gait.title", rows, fr -> {
			Gait o = fromForm(fr);
			Result r = gait(o, true);
			if (!r.ok)PlusDialogs.block(r.errorText());
			else PlusDialogs.done(t("cpmplus.w3animtools.gait.done", String.valueOf(r.extra.get("name"))));
		}, fo);
	}

	static Gait fromForm(FormResult fr) {
		Gait o = new Gait();
		o.four = "4".equals(fr.getString("legs"));
		o.run = "run".equals(fr.getString("kind"));
		o.cycle = fr.getDouble("cycle", 0.6);
		o.legs = fr.getDouble("legAmp", 30);
		o.arms = fr.getDouble("armAmp", 25);
		o.knees = fr.getDouble("knees", 25);
		o.bob = fr.getDouble("bob", 0.5);
		o.fl = BatchKeys.byUuid(fr.getString("fl"));
		o.fr = BatchKeys.byUuid(fr.getString("fr"));
		o.bl = BatchKeys.byUuid(fr.getString("bl"));
		o.br = BatchKeys.byUuid(fr.getString("br"));
		return o;
	}

	// ====================================================================== face kit

	public static final class Face {
		/** expression groups: one toggle each (that face shown, the others hidden) */
		public List<Group> expressions = new ArrayList<>();
		/** shown while the player speaks (voice chat level) */
		public Group mouth;
		/** pupils / eyes that follow where the head looks */
		public List<Group> eyes = new ArrayList<>();
		public double eyeRange = 1;
	}

	public static Result face(Face f, boolean apply) {
		Result r = new Result();
		if (f.expressions.isEmpty() && f.mouth == null && f.eyes.isEmpty())return r.error("nothing", t("cpmplus.w3animtools.face.err.nothing"));
		int count = f.expressions.size() + (f.mouth != null ? 1 : 0) + (f.eyes.isEmpty() ? 0 : 2);
		r.put("animations", (double) count);
		if (!apply)return r;
		List<Animation> made = new ArrayList<>();
		PlusUndo.run(t("cpmplus.w3animtools.face.undo"), UndoData.make(new Animation[0]), () -> UndoData.make(made.toArray(new Animation[0])), () -> {
			for (Group e : f.expressions) {
				Animation a = AnimxUtil.newAnimation(uniq(t("cpmplus.w3animtools.face.exprName", e.name)), "layer", "loop", 0.05, 20);
				a.additive = true;
				for (Group o : f.expressions) {
					BoneAnimator ba = a.getBoneAnimator(o);
					AnimxUtil.addKey(ba, AnimxUtil.visKeyData(0, o == e));
				}
				made.add(a);
			}
			if (f.mouth != null) {
				Animation a = AnimxUtil.newAnimation(uniq(t("cpmplus.w3animtools.face.mouthName")), "speaking", "loop", 1, 20);
				a.additive = true;
				BoneAnimator ba = a.getBoneAnimator(f.mouth);
				AnimxUtil.addKey(ba, AnimxUtil.visKeyData(0, false));
				AnimxUtil.addKey(ba, AnimxUtil.visKeyData(0.25, true));
				made.add(a);
			}
			if (!f.eyes.isEmpty()) {
				double d = f.eyeRange;
				Animation yaw = AnimxUtil.newAnimation(uniq(t("cpmplus.w3animtools.face.yawName")), "head_rotation_yaw", "loop", 1, 20);
				Animation pitch = AnimxUtil.newAnimation(uniq(t("cpmplus.w3animtools.face.pitchName")), "head_rotation_pitch", "loop", 1, 20);
				yaw.additive = true;
				pitch.additive = true;
				for (Group g : f.eyes) {
					writeTwo(yaw, g, KeyMath.POS, new double[] {d, 0, 0}, new double[] {-d, 0, 0}, 1);
					writeTwo(pitch, g, KeyMath.POS, new double[] {0, d, 0}, new double[] {0, -d, 0}, 1);
				}
				made.add(yaw);
				made.add(pitch);
			}
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		List<Object> names = new ArrayList<>();
		for (Animation a : made)names.add(a.name);
		return r.put("names", names);
	}

	private static String uniq(String n) {
		return AnimEdit.byName(n) != null ? AnimEdit.uniqueAnimName(n, "") : n;
	}

	/** CPM+ &gt; Animação &gt; 'Kit de rosto (expressões, boca, olhos)...' */
	public static void faceDialog() {
		Map<String, String> groups = new LinkedHashMap<>();
		for (Group g : Group.all)groups.put(g.uuid, AnimEdit.pathOf(g));
		Map<String, String> one = new LinkedHashMap<>();
		one.put("", t("cpmplus.w3animtools.face.none"));
		one.putAll(groups);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.face.intro")));
		rows.add(FormRow.multiSelect("expr", "cpmplus.w3animtools.face.expr", groups, Collections.emptyList()).description("cpmplus.w3animtools.face.expr.desc"));
		rows.add(FormRow.select("mouth", "cpmplus.w3animtools.face.mouth", one, "").description("cpmplus.w3animtools.face.mouth.desc"));
		rows.add(FormRow.multiSelect("eyes", "cpmplus.w3animtools.face.eyes", groups, Collections.emptyList()).description("cpmplus.w3animtools.face.eyes.desc"));
		rows.add(FormRow.decimal("range", "cpmplus.w3animtools.face.range", 1, 0.25, 4, 0.25));
		rows.add(FormRow.buttons("blink", "cpmplus.w3animtools.face.blinkLabel", new String[] {"cpmplus.w3animtools.face.blink"}, i -> BlinkTool.open(null)));
		FormOpts fo = new FormOpts();
		fo.width = 640;
		fo.confirmKey = "cpmplus.w3animtools.face.ok";
		PlusDialogs.form("cpmplus_w3animtools_face", "cpmplus.w3animtools.face.title", rows, fr -> {
			Face f = new Face();
			for (String u : fr.getList("expr")) {
				Group g = BatchKeys.byUuid(u);
				if (g != null)f.expressions.add(g);
			}
			f.mouth = BatchKeys.byUuid(fr.getString("mouth"));
			for (String u : fr.getList("eyes")) {
				Group g = BatchKeys.byUuid(u);
				if (g != null)f.eyes.add(g);
			}
			f.eyeRange = fr.getDouble("range", 1);
			Result r = face(f, true);
			if (!r.ok)PlusDialogs.block(r.errorText());
			else PlusDialogs.done(t("cpmplus.w3animtools.face.done", ((Double) r.extra.get("animations")).intValue()));
		}, fo);
	}

	// ====================================================================== QA hook

	/** kits({op: recipe | gait | face, ...}) */
	public static Object hook(JsPropertyMap<Object> opts) {
		String op = AnimToolSupport.str(opts, "op", "recipe");
		switch (op) {
		case "gait": {
			Gait o = new Gait();
			o.four = AnimToolSupport.bool(opts, "four", false);
			o.run = AnimToolSupport.bool(opts, "run", false);
			o.cycle = AnimToolSupport.num(opts, "cycle", 0.6);
			o.legs = AnimToolSupport.num(opts, "legs", 30);
			o.arms = AnimToolSupport.num(opts, "arms", 25);
			o.knees = AnimToolSupport.num(opts, "knees", 25);
			o.bob = AnimToolSupport.num(opts, "bob", 0.5);
			String[] keys = {"fl", "fr", "bl", "br"};
			Group[] gs = new Group[4];
			for (int i = 0; i < 4; i++) {
				String s = AnimToolSupport.str(opts, keys[i], null);
				gs[i] = s != null ? AnimToolSupport.group(s) : null;
			}
			o.fl = gs[0];
			o.fr = gs[1];
			o.bl = gs[2];
			o.br = gs[3];
			return gait(o, AnimToolSupport.bool(opts, "apply", true)).toJs();
		}
		case "face": {
			Face f = new Face();
			for (String s : AnimToolSupport.list(opts, "expr")) {
				Group g = AnimToolSupport.group(s);
				if (g != null)f.expressions.add(g);
			}
			String m = AnimToolSupport.str(opts, "mouth", null);
			f.mouth = m != null ? AnimToolSupport.group(m) : null;
			for (String s : AnimToolSupport.list(opts, "eyes")) {
				Group g = AnimToolSupport.group(s);
				if (g != null)f.eyes.add(g);
			}
			f.eyeRange = AnimToolSupport.num(opts, "range", 1);
			return face(f, AnimToolSupport.bool(opts, "apply", true)).toJs();
		}
		default: {
			List<Group> parts = new ArrayList<>();
			for (String s : AnimToolSupport.list(opts, "parts")) {
				Group g = AnimToolSupport.group(s);
				if (g != null)parts.add(g);
			}
			return createRecipe(AnimToolSupport.str(opts, "recipe", "always"), AnimToolSupport.str(opts, "name", null), parts).toJs();
		}
		}
	}

	static JsArray<Object> none() {
		return new JsArray<>();
	}
}
