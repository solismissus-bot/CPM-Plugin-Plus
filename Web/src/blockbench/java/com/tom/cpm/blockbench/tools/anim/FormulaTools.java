package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.AnimSampler;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.KeyframeDataPoint;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.MolangClassifier;
import com.tom.cpm.blockbench.util.MolangClassifier.Kind;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLElement;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (G9): Molang formulas in keyframes.
 * <ul>
 * <li>The export samples formulas at every frame time ({@link AnimSampler}), so deterministic formulas come out like
 * the preview. This class tells the user: one row 'X animações usam fórmulas…' with 'Ver quais' (animation › part ›
 * channel; a click selects the key) and one red row per channel whose formula uses something the game can't
 * reproduce ({@link MolangClassifier}). Never blocking.</li>
 * <li>'Transformar fórmulas em keyframes': the formula stretches of the chosen channels (between two keys where either
 * end is a formula; before the first / after the last key when that key is a formula, because Blockbench evaluates a
 * formula at the playhead) are sampled on the snapping grid with the export's procedure, both ends included, and
 * replaced by linear keys. Plain number keys stay. Red channels start unchecked. ONE undo entry.</li>
 * </ul>
 */
public final class FormulaTools {
	/** channels whose data points can hold formulas (visibility is a boolean) */
	public static final String[] CHANNELS = {"position", "rotation", "scale", CPMCodec.COLOR};
	private static final String[] AXES = {"x", "y", "z"};
	public static final String WARN_KEY = "molangFormulas";
	public static final String WARN_KEY_RED = "molangUnsupported";

	private FormulaTools() {
	}

	// ------------------------------------------------------------------ install

	public static void install() {
		ExportHooks.register(FormulaTools::exportCheck);
		WarnFixes.register(WARN_KEY, "cpmplus.w4molang.formula.viewWhich", 0, w -> () -> showList(scan(allAnims(), null)));
		WarnFixes.register(WARN_KEY_RED, "cpmplus.w4molang.formula.viewWhich", 0, w -> () -> {
			List<Target> l = new ArrayList<>();
			for (Target t : scan(allAnims(), null))if (t.redToken != null && w.getUuids().contains(t.ba.uuid))l.add(t);
			return showList(l);
		});
		Action convert = PlusMenus.addAction(PlusMenus.SUB_ANIM, "w4molang_formulas", "cpmplus.w4molang.menu.formulas", "functions", Cond.CPM_ONLY, FormulaTools::openDialog);
		try {
			if (Animation.menu != null)PlusMenus.addToContext(Animation.menu, convert, "");
			Menu kfMenu = keyframeMenu();
			if (kfMenu != null)PlusMenus.addToContext(kfMenu, convert, "");
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not add 'Transformar fórmulas em keyframes' to the context menus", e);
		}
		PlusTestHooks.register("w4molang.classify", o -> {
			Object t = Js.asPropertyMap(o).get("text");
			MolangClassifier.Result r = MolangClassifier.classify(t != null ? String.valueOf(t) : null);
			return JsPropertyMap.of("kind", r.kind.name(), "token", r.token);
		});
		PlusTestHooks.register("w4molang.formulas", o -> scanJson());
		PlusTestHooks.register("w4molang.convertFormulas", FormulaTools::convertJson);
		PlusTestHooks.register("w4molang.openDialog", o -> {
			openDialog();
			return JsPropertyMap.of("ok", true);
		});
		PlusTestHooks.register("w4molang.viewWhich", o -> {
			showList(scan(allAnims(), null));// the 'Ver quais' list (not awaited: it stays open)
			return JsPropertyMap.of("ok", true);
		});
	}

	public static void uninstall() {
		// menus, context menu entries, export checks and fixes are removed by PluginStart.cleanup
	}

	private static native Menu keyframeMenu() /*-{
		try {
			var K = $wnd.Keyframe;
			return K && K.prototype && K.prototype.menu ? K.prototype.menu : null;
		} catch (e) {
			return null;
		}
	}-*/;

	// ------------------------------------------------------------------ scan

	/** one channel of one part in one animation that holds formula keys */
	public static final class Target {
		public final Animation anim;
		public final BoneAnimator ba;
		public final String partName, channel;
		public int formulaKeys;
		/** first unreproducible token, or null */
		public String redToken;
		/** a formula key of this channel (for 'Ver quais') */
		public Keyframe firstKey;

		Target(Animation anim, BoneAnimator ba, String partName, String channel) {
			this.anim = anim;
			this.ba = ba;
			this.partName = partName;
			this.channel = channel;
		}

		public String label() {
			return anim.name + " › " + partName + " › " + channelLabel(channel);
		}
	}

	public static String channelLabel(String ch) {
		switch (ch) {
		case "position":
			return I18n.get("cpmplus.w4molang.channel.position");
		case "rotation":
			return I18n.get("cpmplus.w4molang.channel.rotation");
		case "scale":
			return I18n.get("cpmplus.w4molang.channel.scale");
		default:
			return I18n.get("cpmplus.w4molang.channel.color");
		}
	}

	/** classification of one keyframe: NUMBER, FORMULA or RED (first red token in result.token) */
	public static MolangClassifier.Result classify(Keyframe kf) {
		MolangClassifier.Result worst = MolangClassifier.NUMBER;
		if (kf == null || kf.data_points == null)return worst;
		for (int i = 0; i < kf.data_points.length; i++) {
			KeyframeDataPoint dp = kf.data_points.getAt(i);
			for (String ax : AXES) {
				Object o = dp.getRaw(ax);
				if (o == null || !"string".equals(Js.typeof(o)))continue;
				MolangClassifier.Result r = MolangClassifier.classify((String) o);
				if (r.kind == Kind.RED)return r;
				if (r.kind == Kind.FORMULA)worst = r;
			}
		}
		return worst;
	}

	static boolean isFormula(Keyframe kf) {
		return classify(kf).isFormula();
	}

	/** uuid -> group, built once per scan */
	private static Map<String, Group> groupsByUuid() {
		Map<String, Group> m = new java.util.HashMap<>();
		if (Group.all != null)for (Group g : Group.all)if (g != null && g.uuid != null)m.put(g.uuid, g);
		return m;
	}

	/**
	 * every channel with formula keys in these animations; exported = the export's part filter (null: animators of
	 * groups that exist). Muted channels are skipped (the export ignores them).
	 */
	public static List<Target> scan(Collection<Animation> anims, Predicate<BoneAnimator> exported) {
		List<Target> out = new ArrayList<>();
		Map<String, Group> groups = groupsByUuid();
		for (Animation a : anims) {
			if (a == null || a.animators == null)continue;
			a.animators.forEach(k -> {
				GeneralAnimator ga = a.animators.get(k);
				if (!(ga instanceof BoneAnimator))return;
				BoneAnimator ba = (BoneAnimator) ga;
				Group g = ba.uuid != null ? groups.get(ba.uuid) : null;
				if (exported != null ? !exported.test(ba) : g == null)return;
				String name = g != null ? g.name : ba.rawName != null ? ba.rawName : "?";
				for (String ch : CHANNELS) {
					JsArrayE<Keyframe> arr = ba.channel(ch);
					if (arr == null || arr.length == 0 || ba.isMuted(ch))continue;
					Target t = null;
					for (int i = 0; i < arr.length; i++) {
						Keyframe kf = arr.getAt(i);
						MolangClassifier.Result r = classify(kf);
						if (!r.isFormula())continue;
						if (t == null) {
							t = new Target(a, ba, name, ch);
							t.firstKey = kf;
						}
						t.formulaKeys++;
						if (r.kind == Kind.RED && t.redToken == null)t.redToken = r.token;
					}
					if (t != null)out.add(t);
				}
			});
		}
		return out;
	}

	static List<Animation> allAnims() {
		List<Animation> l = new ArrayList<>();
		if (Project.animations != null)for (Animation a : Project.animations.array())l.add(a);
		return l;
	}

	// ------------------------------------------------------------------ export rows

	/** ExportCheck: the 'X animações usam fórmulas' row + one red row per channel with unreproducible tokens */
	public static void exportCheck(ExportContext ctx) {
		Map<String, ?> lookup = ctx.export != null ? ctx.export.getUuidLookup() : null;
		List<Target> all = scan(allAnims(), lookup != null ? ba -> lookup.containsKey(ba.uuid) : null);
		if (all.isEmpty())return;
		Set<Animation> anims = new LinkedHashSet<>();
		for (Target t : all)anims.add(t.anim);
		WarnEntry w = WarnFactory.of(Severity.INFO, WARN_KEY, "cpmplus.w4molang.formula.warn", anims.size());
		w.setConsequence("cpmplus.w4molang.formula.ifIgnored");
		w.setTooltip(PlusDialogs.esc(I18n.get("cpmplus.w4molang.formula.tooltip")));
		ctx.add(w);
		for (Target t : all) {
			if (t.redToken == null)continue;
			WarnEntry r = WarnFactory.of(Severity.BREAKS, WARN_KEY_RED, "cpmplus.w4molang.formula.red", t.partName + "/" + channelLabel(t.channel), t.redToken, t.anim.name);
			r.setConsequence("cpmplus.w4molang.formula.redIfIgnored");
			r.addNode(t.ba.uuid, t.partName);
			ctx.add(r);
		}
	}

	/** 'Ver quais': animation › part › channel; a click selects the animation and the keyframe */
	public static Promise<Boolean> showList(List<Target> list) {
		return new Promise<>((res, rej) -> {
			HTMLElement root = (HTMLElement) DomGlobal.document.createElement("div");
			root.style.setProperty("max-height", "60vh");
			root.style.setProperty("overflow", "auto");
			for (Target t : list) {
				HTMLElement line = (HTMLElement) DomGlobal.document.createElement("div");
				line.className = "cpmplus_formula_line";
				line.style.setProperty("cursor", "pointer");
				line.style.setProperty("padding", "2px 4px");
				String txt = t.label() + " (" + I18n.format("cpmplus.w4molang.formula.keys", t.formulaKeys) + ")";
				if (t.redToken != null)txt += " — " + I18n.format("cpmplus.w4molang.formula.redShort", t.redToken);
				line.textContent = txt;// plain text: names are never parsed as HTML
				if (t.redToken != null)line.style.setProperty("color", "var(--color-error, #e14f4f)");
				line.addEventListener("click", e -> selectKey(t));
				root.appendChild(line);
			}
			Dialog.DialogProperties d = new Dialog.DialogProperties();
			d.id = "cpmplus_w4molang_formulas_list";
			d.title = I18n.get("cpmplus.w4molang.formula.listTitle");
			d.width = 640;
			d.singleButton = true;
			d.lineNodes = new Object[] {root};
			d.onConfirm = x -> {
				res.onInvoke(false);
				return true;
			};
			d.onCancel = () -> {
				res.onInvoke(false);
				return true;
			};
			new Dialog(d).show();
		});
	}

	private static void selectKey(Target t) {
		try {
			enterAnimateMode();
			if (Animation.selected != t.anim)t.anim.select();
			if (t.firstKey != null) {
				t.firstKey.select();
				Timeline.setTime(t.firstKey.time);
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not select the keyframe", e);
		}
	}

	private static native void enterAnimateMode() /*-{
		var M = $wnd.Modes;
		if (M && !M.animate && M.options && M.options.animate && typeof M.options.animate.select == 'function') M.options.animate.select();
	}-*/;

	// ------------------------------------------------------------------ converter

	public static final class ConvertResult {
		public int channels, removedKeys, addedKeys;
	}

	/** sample times + values of one target, computed before anything changes */
	private static final class Bake {
		final Target t;
		final List<double[]> intervals = new ArrayList<>();
		final List<Float> times = new ArrayList<>();
		final List<double[]> values = new ArrayList<>();

		Bake(Target t) {
			this.t = t;
		}
	}

	private static List<Keyframe> sorted(JsArrayE<Keyframe> arr) {
		List<Keyframe> l = new ArrayList<>();
		for (int i = 0; i < arr.length; i++)l.add(arr.getAt(i));
		l.sort((a, b) -> Float.compare(a.time, b.time));
		return l;
	}

	private static double animLength(Animation a, List<Keyframe> keys) {
		double len = a.length;
		for (Keyframe k : keys)len = Math.max(len, k.time);
		return len;
	}

	/** the formula stretches of one channel, merged (seconds) */
	private static List<double[]> intervals(Target t) {
		List<Keyframe> keys = sorted(t.ba.channel(t.channel));
		List<double[]> iv = new ArrayList<>();
		int n = keys.size();
		if (n == 0)return iv;
		double len = animLength(t.anim, keys);
		boolean[] f = new boolean[n];
		for (int i = 0; i < n; i++)f[i] = isFormula(keys.get(i));
		for (int i = 0; i + 1 < n; i++)if (f[i] || f[i + 1])iv.add(new double[] {keys.get(i).time, keys.get(i + 1).time});
		// before the first / after the last key Blockbench shows that key, a formula there keeps moving
		if (f[0] && keys.get(0).time > 1e-6)iv.add(new double[] {0, keys.get(0).time});
		if (f[n - 1] && len > keys.get(n - 1).time + 1e-6)iv.add(new double[] {keys.get(n - 1).time, len});
		if (n == 1 && f[0] && iv.isEmpty())iv.add(new double[] {keys.get(0).time, keys.get(0).time});
		iv.sort((a, b) -> Double.compare(a[0], b[0]));
		List<double[]> merged = new ArrayList<>();
		for (double[] i : iv) {
			if (!merged.isEmpty() && i[0] <= merged.get(merged.size() - 1)[1] + 1e-6) {
				double[] last = merged.get(merged.size() - 1);
				last[1] = Math.max(last[1], i[1]);
			} else merged.add(new double[] {i[0], i[1]});
		}
		return merged;
	}

	/** the animation's snapping (frames per second; the export uses the same rate for formulas) */
	private static int snapping(Animation a) {
		Object o = Js.asPropertyMap(a).get("snapping");
		double d = o != null && "number".equals(Js.typeof(o)) ? Js.asDouble(o) : 20;
		if (Double.isNaN(d) || d < 1)d = 20;
		return (int) Math.min(500, Math.round(d));
	}

	/** sample times of one stretch: both ends, the snapping grid inside, and the times of the keys inside */
	private static List<Float> sampleTimes(double a, double b, double h, List<Keyframe> keys) {
		TreeMap<Long, Float> m = new TreeMap<>();
		double eps = 1e-4;
		put(m, a);
		put(m, b);
		long k0 = (long) Math.ceil((a + eps) / h);
		for (long k = k0; k * h < b - eps; k++)put(m, k * h);
		for (Keyframe kf : keys)if (kf.time > a + eps && kf.time < b - eps)put(m, kf.time);
		return new ArrayList<>(m.values());
	}

	private static void put(Map<Long, Float> m, double t) {
		m.put(Math.round(t * 10000.0), (float) t);
	}

	/** number of plain keys the channel will have in its formula stretches after converting (for the confirmation) */
	public static int estimate(Target t) {
		int n = 0;
		double h = 1.0 / snapping(t.anim);
		List<Keyframe> keys = sorted(t.ba.channel(t.channel));
		for (double[] i : intervals(t))n += sampleTimes(i[0], i[1], h, keys).size();
		return n;
	}

	/** converts the formula stretches of these channels into linear keys: ONE undo entry */
	public static ConvertResult convert(List<Target> targets) {
		ConvertResult r = new ConvertResult();
		if (targets.isEmpty())return r;
		// 1. sample everything first (the export's procedure), nothing is changed yet
		List<Bake> bakes = new ArrayList<>();
		for (Target t : targets) {
			Bake b = new Bake(t);
			b.intervals.addAll(intervals(t));
			if (b.intervals.isEmpty())continue;
			double h = 1.0 / snapping(t.anim);
			List<Keyframe> keys = sorted(t.ba.channel(t.channel));
			for (double[] iv : b.intervals)b.times.addAll(sampleTimes(iv[0], iv[1], h, keys));
			bakes.add(b);
		}
		for (Bake b : bakes) {
			AnimSampler.sampling(b.t.anim, () -> {
				for (Float time : b.times) {
					AnimSampler.setTime(time);
					JsVec3 v = Animation.interpolate(b.t.ba.channel(b.t.channel), time);
					b.values.add(v == null ? null : new double[] {v.x, v.y, v.z});
				}
			});
		}
		Set<Animation> anims = new LinkedHashSet<>();
		for (Bake b : bakes)anims.add(b.t.anim);
		if (bakes.isEmpty())return r;
		// 2. replace the keys
		PlusUndo.editAnimations(anims, I18n.get("cpmplus.w4molang.undo.formulas"), () -> {
			for (Bake b : bakes) {
				JsArrayE<Keyframe> arr = b.t.ba.channel(b.t.channel);
				List<Keyframe> keys = sorted(arr);
				List<Float> keepTimes = new ArrayList<>();
				for (Keyframe kf : keys) {
					boolean in = false, endpoint = false;
					for (double[] iv : b.intervals) {
						if (kf.time >= iv[0] - 1e-4 && kf.time <= iv[1] + 1e-4) {
							in = true;
							if (Math.abs(kf.time - iv[0]) < 1e-4 || Math.abs(kf.time - iv[1]) < 1e-4)endpoint = true;
						}
					}
					if (!in)continue;
					if (endpoint && !isFormula(kf)) {
						keepTimes.add(kf.time);// plain end keys stay (the stretch on their other side keeps its shape)
						continue;
					}
					kf.remove();
					r.removedKeys++;
				}
				for (int i = 0; i < b.times.size(); i++) {
					float time = b.times.get(i);
					double[] v = b.values.get(i);
					if (v == null)continue;
					boolean kept = false;
					for (Float kt : keepTimes)if (Math.abs(kt - time) < 1e-4)kept = true;
					if (kept)continue;
					Keyframe kf = b.t.ba.addKeyframeFast(time, b.t.channel, "linear");
					if (kf == null || kf.data_points == null || kf.data_points.length == 0)continue;
					setValues(kf, v[0], v[1], v[2]);
					r.addedKeys++;
				}
				r.channels++;
			}
		});
		refresh();
		PlusTestHooks.count("w4molangFormulaConvert");
		return r;
	}

	private static native void setValues(Keyframe kf, double x, double y, double z) /*-{
		kf.uniform = false;
		var dp = kf.data_points[0];
		dp.x = x;
		dp.y = y;
		dp.z = z;
		if (kf.data_points.length > 1) kf.data_points.splice(1, kf.data_points.length - 1);
	}-*/;

	private static native void refresh() /*-{
		try {
			var T = $wnd.Timeline;
			if (T && T.vue && typeof T.vue.$forceUpdate == 'function') T.vue.$forceUpdate();
			var A = $wnd.Animator;
			if ($wnd.Modes && $wnd.Modes.animate && $wnd.Animation && $wnd.Animation.selected && A && typeof A.preview == 'function') A.preview();
		} catch (e) {}
	}-*/;

	// ------------------------------------------------------------------ dialog

	/** animator uuid | channel of the selected keyframes */
	private static Set<String> selectedChannels() {
		Set<String> s = new LinkedHashSet<>();
		try {
			JsArrayE<Keyframe> sel = Timeline.selected;
			if (sel == null)return s;
			for (int i = 0; i < sel.length; i++) {
				Keyframe kf = sel.getAt(i);
				if (kf.animator != null)s.add(kf.animator.uuid + "|" + kf.channel);
			}
		} catch (Throwable e) {
			// no timeline selection
		}
		return s;
	}

	/** 'Transformar fórmulas em keyframes' */
	public static void openDialog() {
		List<Target> all = scan(allAnims(), null);
		if (all.isEmpty()) {
			PlusDialogs.info("cpmplus_w4molang_formulas_none", "cpmplus.w4molang.formula.toolTitle", Collections.singletonList("cpmplus.w4molang.formula.none"));
			return;
		}
		Set<String> selCh = selectedChannels();
		Animation cur = Animation.selected;
		Map<String, String> scopes = new LinkedHashMap<>();
		boolean anySel = false, anyCur = false;
		for (Target t : all) {
			if (t.anim == cur && selCh.contains(t.ba.uuid + "|" + t.channel))anySel = true;
			if (t.anim == cur)anyCur = true;
		}
		if (anySel)scopes.put("keys", "cpmplus.w4molang.formula.scopeKeys");
		if (anyCur)scopes.put("anim", "cpmplus.w4molang.formula.scopeAnim");
		scopes.put("all", "cpmplus.w4molang.formula.scopeAll");
		String def = scopes.keySet().iterator().next();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.radio("scope", "cpmplus.w4molang.formula.scope", scopes, def));
		for (String sc : scopes.keySet()) {
			int f = 0, est = 0;
			for (Target t : all) {
				if (!inScope(t, sc, cur, selCh))continue;
				f += t.formulaKeys;
				est += estimate(t);
			}
			rows.add(FormRow.info("count_" + sc, null, I18n.format("cpmplus.w4molang.formula.count", f, est)).when(r -> sc.equals(r.getString("scope"))));
		}
		for (int i = 0; i < all.size(); i++) {
			Target t = all.get(i);
			String label = t.label() + " (" + I18n.format("cpmplus.w4molang.formula.keys", t.formulaKeys) + ")";
			FormRow cb = FormRow.checkbox("c" + i, null, t.redToken == null).option("label", label);
			if (t.redToken != null)cb.description(I18n.format("cpmplus.w4molang.formula.redShort", t.redToken));
			cb.when(r -> inScope(t, r.getString("scope"), cur, selCh));
			rows.add(cb);
		}
		rows.add(FormRow.info("hint", null, I18n.get("cpmplus.w4molang.formula.hint")));
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.width = 640;
		o.confirmKey = "cpmplus.w4molang.formula.convert";
		PlusDialogs.form("cpmplus_w4molang_formulas", "cpmplus.w4molang.formula.toolTitle", rows, res -> {
			String sc = res.getString("scope");
			List<Target> chosen = new ArrayList<>();
			for (int i = 0; i < all.size(); i++) {
				Target t = all.get(i);
				if (inScope(t, sc, cur, selCh) && res.getBool("c" + i))chosen.add(t);
			}
			if (chosen.isEmpty()) {
				PlusDialogs.toast(I18n.get("cpmplus.w4molang.formula.nothingChosen"), "info", 4000, null);
				return;
			}
			ConvertResult cr = convert(chosen);
			PlusDialogs.done(I18n.format("cpmplus.w4molang.formula.done", cr.channels, cr.addedKeys));
		}, o);
	}

	private static boolean inScope(Target t, String scope, Animation cur, Set<String> selCh) {
		if ("all".equals(scope))return true;
		if ("anim".equals(scope))return t.anim == cur;
		return t.anim == cur && selCh.contains(t.ba.uuid + "|" + t.channel);
	}

	// ------------------------------------------------------------------ test hooks

	/** test tool: {anim?: name, includeRed?: bool} -> converts (no dialog) */
	public static Object convertJson(Object opts) {
		JsPropertyMap<Object> o = Js.asPropertyMap(opts);
		Object an = o.get("anim");
		boolean red = Js.isTruthy(o.get("includeRed"));
		List<Animation> anims = new ArrayList<>();
		for (Animation a : allAnims())if (an == null || String.valueOf(an).equals(a.name))anims.add(a);
		List<Target> ts = new ArrayList<>();
		for (Target t : scan(anims, null))if (red || t.redToken == null)ts.add(t);
		ConvertResult r = convert(ts);
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("ok", true);
		m.set("channels", (double) r.channels);
		m.set("addedKeys", (double) r.addedKeys);
		m.set("removedKeys", (double) r.removedKeys);
		return m;
	}

	/** test tool: the formula channels [{anim, part, channel, keys, red, estimate}] */
	public static Object scanJson() {
		JsArray<Object> out = new JsArray<>();
		for (Target t : scan(allAnims(), null)) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("anim", t.anim.name);
			m.set("part", t.partName);
			m.set("channel", t.channel);
			m.set("keys", (double) t.formulaKeys);
			m.set("red", t.redToken);
			m.set("estimate", (double) estimate(t));
			out.push(m);
		}
		return out;
	}
}
