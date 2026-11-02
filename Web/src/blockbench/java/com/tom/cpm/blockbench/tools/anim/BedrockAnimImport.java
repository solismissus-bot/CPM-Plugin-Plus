package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.AnimationWizard;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Outliner;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.ext.W2ToolsNatives;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.dom.DomGlobal;
import jsinterop.base.Js;

/**
 * CPM+ (W2-TOOLS, D8): 'Importar animação Bedrock/GeckoLib…'.
 * <p>
 * Blockbench already reads Bedrock / GeckoLib .animation.json files, but it pairs each bone with the first group of
 * the same name and keeps bones without a group as hidden 'orphan' tracks the CPM export skips without a word. This
 * tool adds the missing step: a mapping dialog (bone of the file -&gt; group of the CPM model, prefilled by name and
 * by synonyms such as rightArm / bipedRightArm -&gt; right_arm, remembered per bone name), then Blockbench's own loader
 * runs on a rewritten copy of the file in which every mapped bone carries a temporary unique name of its target group
 * (so two groups with the same name each get their own track); the names are restored in a finally block. Bones
 * without a pair are left out (no orphan tracks). Everything the import creates is ONE undo entry.
 * <p>
 * Each new animation: CPM type 'custom_pose', additive on, loop from the file ('loop': true -&gt; loop,
 * 'hold_on_last_frame' -&gt; hold, otherwise once). Molang is not evaluated here: Blockbench keeps the expressions and the
 * CPM+ export samples them into frames (export info 'molangBaked'). Coordinates need no conversion (Blockbench's
 * animation convention is Bedrock's, and the export starts from it).
 */
public final class BedrockAnimImport {
	/** PlusPrefs key: last chosen target (group path) per bone name */
	public static final String PREF_KEY = "cpmplus.bedrockMap";
	/** temporary group names used while Blockbench's loader runs (lower case: the loader compares lower case) */
	public static final String TMP_PREFIX = "__cpmplus_tmp_";
	public static final String DIALOG_ID = "cpmplus_bedrock_import";
	static final String NONE = "none";
	private static final int MAX_REMEMBERED = 300;
	private static final double MAX_SECONDS = 32.767;

	/** normalized bone name (lower case, letters and digits only) -&gt; vanilla root group name */
	private static final Map<String, String> SYNONYMS = new HashMap<>();

	static {
		syn("right_arm", "rightarm", "bipedrightarm", "armright", "rarm", "armr", "rightarm0", "bracodireito");
		syn("left_arm", "leftarm", "bipedleftarm", "armleft", "larm", "arml", "leftarm0", "bracoesquerdo");
		syn("right_leg", "rightleg", "bipedrightleg", "legright", "rleg", "legr", "rightleg0", "pernadireita");
		syn("left_leg", "leftleg", "bipedleftleg", "legleft", "lleg", "legl", "leftleg0", "pernaesquerda");
		syn("head", "head", "bipedhead", "cabeca");
		syn("body", "body", "torso", "chest", "bipedbody", "corpo");
	}

	private static void syn(String target, String... names) {
		for (String n : names)SYNONYMS.put(n, target);
	}

	private BedrockAnimImport() {
	}

	/** lower case, letters and digits only ('Right_Arm' / 'rightArm' / 'right-arm' -&gt; 'rightarm') */
	static String norm(String name) {
		if (name == null)return "";
		String s = name.toLowerCase(Locale.ROOT);
		s = s.replace('ç', 'c').replace('ã', 'a').replace('á', 'a').replace('â', 'a').replace('é', 'e').replace('ê', 'e').replace('í', 'i').replace('ó', 'o').replace('ô', 'o').replace('õ', 'o').replace('ú', 'u');
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))sb.append(c);
		}
		return sb.toString();
	}

	// ====================================================================== the file

	/** a parsed .animation.json */
	public static final class FileInfo {
		public final String fileName;
		public final Object json;
		public final List<String> animations = new ArrayList<>();
		/** every bone of every animation, in file order */
		public final List<String> bones = new ArrayList<>();

		FileInfo(String fileName, Object json) {
			this.fileName = fileName;
			this.json = json;
			for (String a : W2ToolsNatives.animationNames(json)) {
				animations.add(a);
				for (String b : W2ToolsNatives.boneNames(json, a))if (!bones.contains(b))bones.add(b);
			}
		}

		/** bones used by the given animations */
		public List<String> bonesOf(List<String> anims) {
			Set<String> s = new LinkedHashSet<>();
			for (String a : anims)for (String b : W2ToolsNatives.boneNames(json, a))s.add(b);
			return new ArrayList<>(s);
		}
	}

	/** file name without folders (never store absolute paths in the project) */
	static String baseName(String name) {
		if (name == null || name.isEmpty())return "animation.json";
		int i = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		return i >= 0 ? name.substring(i + 1) : name;
	}

	/**
	 * Parses the file text. @throws IllegalArgumentException with a lang key when it is not JSON or has no
	 * 'animations'.
	 */
	public static FileInfo parse(String fileName, Object jsonOrText) {
		Object json = jsonOrText;
		if (json == null)throw new ImportError("cpmplus.w2tools.bedrock.err.notJson");
		if ("string".equals(Js.typeof(json)))json = W2ToolsNatives.parseJson((String) json);
		if (json == null)throw new ImportError("cpmplus.w2tools.bedrock.err.notJson");
		FileInfo f = new FileInfo(baseName(fileName), json);
		if (f.animations.isEmpty())throw new ImportError("cpmplus.w2tools.bedrock.err.noAnims");
		return f;
	}

	// ====================================================================== the model's groups

	/** one group of the model with a readable unique path ('body / asa', '#2' for repeated sibling names) */
	public static final class Target {
		public final Group group;
		public final String path;

		Target(Group group, String path) {
			this.group = group;
			this.path = path;
		}
	}

	/** every group of the project in outliner order with its path */
	public static List<Target> targets() {
		List<Target> out = new ArrayList<>();
		Object root = Outliner.root;
		if (root != null && "object".equals(Js.typeof(root))) {
			Object[] arr = Js.uncheckedCast(root);
			walk(arr, "", out);
		}
		// groups the walk could not reach (should not happen) still get a path
		if (Group.all != null) {
			Set<Group> seen = new HashSet<>();
			for (Target t : out)seen.add(t.group);
			for (Group g : Group.all)if (g != null && !seen.contains(g))out.add(new Target(g, g.name + " (" + g.uuid.substring(0, Math.min(8, g.uuid.length())) + ")"));
		}
		return out;
	}

	private static void walk(Object[] nodes, String parent, List<Target> out) {
		Map<String, Integer> seen = new HashMap<>();
		for (Object n : nodes) {
			if (!(n instanceof Group))continue;
			Group g = (Group) n;
			String nm = g.name != null ? g.name : "";
			int c = seen.merge(nm, 1, Integer::sum);
			String p = (parent.isEmpty() ? "" : parent + " / ") + nm + (c > 1 ? " #" + c : "");
			out.add(new Target(g, p));
			if (g.children != null) {
				Object[] ch = Js.uncheckedCast(g.children);
				walk(ch, p, out);
			}
		}
	}

	/**
	 * Prefilled pairs bone -&gt; group uuid (bones without a pair are left out): the pair remembered for that bone name
	 * (when the group path exists here), else a synonym of a vanilla part ('rightArm', 'bipedHead', 'torso' ...)
	 * pointing to that part's root group, else the first group with the same name (any case).
	 */
	public static Map<String, String> prefill(List<String> bones, List<Target> targets) {
		Map<String, String> out = new LinkedHashMap<>();
		Map<String, String> remembered = remembered();
		for (String b : bones) {
			String uuid = null;
			String rp = remembered.get(b);
			if (rp != null)for (Target t : targets)if (t.path.equals(rp)) {
				uuid = t.group.uuid;
				break;
			}
			if (uuid == null) {
				String root = SYNONYMS.get(norm(b));
				if (root != null)for (Target t : targets)if (t.group.parent == Group.ROOT && root.equalsIgnoreCase(t.group.name)) {
					uuid = t.group.uuid;
					break;
				}
			}
			if (uuid == null)for (Target t : targets)if (t.group.name != null && t.group.name.equalsIgnoreCase(b)) {
				uuid = t.group.uuid;
				break;
			}
			if (uuid != null)out.put(b, uuid);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, String> remembered() {
		Map<String, String> m = new LinkedHashMap<>();
		try {
			Object o = PlusPrefs.getJson(PREF_KEY);
			if (o instanceof Map)((Map<String, Object>) o).forEach((k, v) -> {
				if (v instanceof String)m.put(k, (String) v);
			});
		} catch (Throwable e) {
		}
		return m;
	}

	/** remembers the chosen pairs (bone name -&gt; group path); per-user convenience only, never in the project */
	static void remember(Map<String, String> boneToUuid, List<Target> targets) {
		try {
			Map<String, String> m = remembered();
			Map<String, String> pathOf = new HashMap<>();
			for (Target t : targets)pathOf.put(t.group.uuid, t.path);
			for (Map.Entry<String, String> e : boneToUuid.entrySet()) {
				String p = pathOf.get(e.getValue());
				if (p == null)continue;
				m.remove(e.getKey());
				m.put(e.getKey(), p);
			}
			while (m.size() > MAX_REMEMBERED)m.remove(m.keySet().iterator().next());
			PlusPrefs.set(PREF_KEY, new LinkedHashMap<String, Object>(m));
		} catch (Throwable e) {
		}
	}

	// ====================================================================== the import

	/** what an import did */
	public static final class Result {
		public final List<Animation> created = new ArrayList<>();
		/** bones of the chosen animations that got no pair (left out) */
		public final List<String> unmapped = new ArrayList<>();
		/** tracks the loader created without a group (removed) */
		public final List<String> orphansRemoved = new ArrayList<>();
		/** animations whose mapped bones use Molang expressions (the export samples them) */
		public final List<String> molang = new ArrayList<>();
		/** animations whose sound / particle / timeline effects were left out */
		public final List<String> effectsDropped = new ArrayList<>();
		/** animations longer than CPM can store (32.767 s) */
		public final List<String> tooLong = new ArrayList<>();
		/** Blockbench's loader recorded an undo entry of its own (never expected) */
		public boolean loaderRecordedUndo;
		/** the project's free skin layers can not encode every layer-controlled animation any more */
		public LayerCapacity.Result layers;
		public String loader;
	}

	/**
	 * Imports the chosen animations of the file with the given pairs (bone name -&gt; group uuid; bones missing from the
	 * map are left out). One undo entry; the group names are restored in every case. @throws ImportError (lang key +
	 * arguments, see {@link #message}) when the input is not usable (nothing chosen, two bones on one group, a group
	 * that no longer exists, no loader); nothing is changed then.
	 */
	public static Result importAnimations(FileInfo f, Map<String, String> boneToUuid, List<String> anims) {
		if (anims == null || anims.isEmpty())throw new ImportError("cpmplus.w2tools.bedrock.err.noAnimChosen");
		String loader = W2ToolsNatives.bedrockLoader();
		if (loader == null)throw new ImportError("cpmplus.w2tools.bedrock.err.noLoader");
		for (String a : anims)if (!f.animations.contains(a))throw new ImportError("cpmplus.w2tools.bedrock.err.noAnimChosen");
		List<String> usedBones = f.bonesOf(anims);
		Map<String, Group> pairs = new LinkedHashMap<>();
		Map<Group, String> firstBone = new HashMap<>();
		for (String b : usedBones) {
			String uuid = boneToUuid.get(b);
			if (uuid == null || uuid.isEmpty() || NONE.equals(uuid))continue;
			Group g = W2ToolsNatives.groupByUuid(uuid);
			if (g == null)throw new ImportError("cpmplus.w2tools.bedrock.err.groupGone", b);
			String other = firstBone.get(g);
			if (other != null)throw new ImportError("cpmplus.w2tools.bedrock.err.sameGroup", other, b, g.name);
			firstBone.put(g, b);
			pairs.put(b, g);
		}
		if (pairs.isEmpty())throw new ImportError("cpmplus.w2tools.bedrock.err.noPairs");
		Result res = new Result();
		res.loader = loader;
		for (String b : usedBones)if (!pairs.containsKey(b))res.unmapped.add(b);
		for (String a : anims) {
			if (W2ToolsNatives.hasEffects(f.json, a))res.effectsDropped.add(a);
			for (String b : W2ToolsNatives.boneNames(f.json, a)) {
				if (pairs.containsKey(b) && W2ToolsNatives.boneHasMolang(f.json, a, b)) {
					res.molang.add(a);
					break;
				}
			}
		}
		String placeholders = W2ToolsNatives.placeholderText();
		PlusUndo.run(PlusDialogs.tr("cpmplus.w2tools.bedrock.undo"), UndoData.make(new Animation[0]),
				() -> UndoData.make(res.created.toArray(new Animation[0])), () -> {
					Map<Group, String> oldNames = new LinkedHashMap<>();
					try {
						Object map = W2ToolsNatives.newMap();
						int i = 0;
						for (Map.Entry<String, Group> e : pairs.entrySet()) {
							Group g = e.getValue();
							if (!oldNames.containsKey(g)) {
								oldNames.put(g, g.name);
								g.name = TMP_PREFIX + (i++);
							}
							W2ToolsNatives.put(map, e.getKey(), g.name);
						}
						Object rewritten = W2ToolsNatives.rewrite(f.json, anims.toArray(new String[0]), map);
						int h0 = Undo.historyLength();
						Animation[] made = Js.uncheckedCast(W2ToolsNatives.loadBedrock(W2ToolsNatives.bedrockFile(f.fileName, rewritten, W2ToolsNatives.stringify(rewritten)), anims.toArray(new String[0])));
						if (Undo.historyLength() != h0)res.loaderRecordedUndo = true;
						for (Animation a : made)if (a != null)res.created.add(a);
					} finally {
						// the temporary names never stay, whatever happened
						for (Map.Entry<Group, String> e : oldNames.entrySet())e.getKey().name = e.getValue();
					}
					finishAnimations(f, anims, res);
				});
		if (res.loaderRecordedUndo)DomGlobal.console.warn("CPM+: Blockbench's Bedrock loader recorded its own undo entry");
		// Blockbench's loader may add Molang query sliders to the 'variable placeholders' panel (preview only); keep them
		if (placeholders != null && !placeholders.equals(W2ToolsNatives.placeholderText()))DomGlobal.console.log("CPM+: Blockbench added Molang preview variables for the imported animations");
		res.layers = layerCheck();
		return res;
	}

	/** CPM settings, names, tracks of the new animations (inside the undo entry) */
	private static void finishAnimations(FileInfo f, List<String> anims, Result res) {
		Set<String> taken = new HashSet<>();
		if (Animation.all != null)for (Animation a : Animation.all)if (a != null && !res.created.contains(a))taken.add(a.name);
		for (int i = 0; i < res.created.size(); i++) {
			Animation a = res.created.get(i);
			Object sn = a.props().get("saved_name");
			String key = sn != null && "string".equals(Js.typeof(sn)) && f.animations.contains((String) sn) ? (String) sn : (i < anims.size() ? anims.get(i) : a.name);
			a.loop = W2ToolsNatives.fileLoop(f.json, key);
			a.type = "custom_pose";
			a.additive = true;
			String nm = unique(shortName(key), taken);
			taken.add(nm);
			a.name = nm;
			try {
				a.props().set("saved_name", nm);
				a.path = "";
			} catch (Throwable e) {
			}
			for (String k : W2ToolsNatives.animatorKeys(a)) {
				String type = W2ToolsNatives.animatorType(a, k);
				if (!"bone".equals(type) && !type.isEmpty())continue;
				Group g = W2ToolsNatives.groupByUuid(k);
				if (g == null) {
					res.orphansRemoved.add(W2ToolsNatives.animatorName(a, k));
					W2ToolsNatives.removeAnimator(a, k);
				} else {
					W2ToolsNatives.setAnimatorName(a, k, g.name);
				}
			}
			double len = a.length;
			if (len > MAX_SECONDS)res.tooLong.add(nm);
		}
	}

	/** 'animation.dragao.voar' -&gt; 'voar', 'animation.x.walk.start' -&gt; 'walk.start'; anything else unchanged */
	static String shortName(String key) {
		if (key == null || key.trim().isEmpty())return "bedrock";
		String k = key.trim();
		if (k.toLowerCase(Locale.ROOT).startsWith("animation.")) {
			String[] p = k.split("\\.");
			if (p.length >= 3) {
				StringBuilder sb = new StringBuilder();
				for (int i = 2; i < p.length; i++) {
					if (sb.length() > 0)sb.append('.');
					sb.append(p[i]);
				}
				if (sb.length() > 0)return sb.toString();
			} else if (p.length == 2 && !p[1].isEmpty())return p[1];
		}
		return k;
	}

	static String unique(String base, Set<String> taken) {
		if (!taken.contains(base))return base;
		for (int i = 2;; i++) {
			String n = base + " " + i;
			if (!taken.contains(n))return n;
		}
	}

	/** layer-controlled animations vs the free skin layers set in the project; null when it fits or no layers are set */
	@SuppressWarnings("unchecked")
	static LayerCapacity.Result layerCheck() {
		try {
			ProjectData pd = Project.getData();
			if (!(pd.animations.get("free") instanceof List))return null;// no layers chosen: CPM asks on export in game
			LayerCapacity.Result r = LayerCapacity.checkProject();
			return r.ok() ? null : r;
		} catch (Throwable e) {
			return null;
		}
	}

	// ====================================================================== user flow

	/** CPM+ &gt; Animação &gt; 'Importar animação Bedrock/GeckoLib…' */
	public static void open() {
		Blockbench.ImportProperties p = new Blockbench.ImportProperties();
		p.extensions = new String[] {"json"};
		p.type = PlusDialogs.tr("cpmplus.w2tools.bedrock.fileType");
		p.readtype = "text";
		p.resource_id = "cpmplus_bedrock_anim";
		boolean ok = PlusDialogs.importFiles(p, files -> {
			if (files == null || files.length == 0)return;
			Object content = Js.asPropertyMap(files[0]).get("content");
			String name = files[0].name != null ? files[0].name : files[0].path;
			FileInfo f;
			try {
				f = parse(name, content);
			} catch (IllegalArgumentException e) {
				PlusDialogs.block(PlusDialogs.trf(e.getMessage(), baseName(name)));
				return;
			}
			showDialog(f);
		});
		if (!ok)PlusDialogs.block("cpmplus.w2tools.bedrock.err.noImport");
	}

	/** the mapping dialog: animations to import + the pair of every bone */
	public static void showDialog(FileInfo f) {
		if (W2ToolsNatives.bedrockLoader() == null) {
			PlusDialogs.block("cpmplus.w2tools.bedrock.err.noLoader");
			return;
		}
		List<Target> targets = targets();
		Map<String, String> pre = prefill(f.bones, targets);
		Map<String, String> opts = new LinkedHashMap<>();
		opts.put(NONE, PlusDialogs.tr("cpmplus.w2tools.bedrock.none"));
		for (Target t : targets)opts.put(t.group.uuid, t.path);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf("cpmplus.w2tools.bedrock.intro", f.fileName)));
		rows.add(FormRow.info("animsHead", null, "cpmplus.w2tools.bedrock.animsHead"));
		for (int i = 0; i < f.animations.size(); i++) {
			String a = f.animations.get(i);
			FormRow r = FormRow.checkbox("anim_" + i, null, true);
			r.option("label", a + "  →  " + shortName(a));
			rows.add(r);
		}
		rows.add(FormRow.separator());
		rows.add(FormRow.info("bonesHead", null, "cpmplus.w2tools.bedrock.bonesHead"));
		boolean anyMolang = false;
		for (int i = 0; i < f.bones.size(); i++) {
			String b = f.bones.get(i);
			FormRow r = FormRow.select("bone_" + i, null, opts, pre.getOrDefault(b, NONE));
			r.option("label", b);
			rows.add(r);
			for (String a : f.animations)if (W2ToolsNatives.boneHasMolang(f.json, a, b))anyMolang = true;
		}
		rows.add(FormRow.separator());
		if (anyMolang)rows.add(FormRow.info("molang", null, "cpmplus.w2tools.bedrock.molangInfo"));
		rows.add(FormRow.info("pivots", null, "cpmplus.w2tools.bedrock.pivotInfo"));
		FormOpts o = new FormOpts();
		o.width = 560;
		o.confirmKey = "cpmplus.w2tools.bedrock.btn.import";
		PlusDialogs.form(DIALOG_ID, "cpmplus.w2tools.bedrock.title", rows, r -> confirm(f, targets, r), o);
	}

	private static void confirm(FileInfo f, List<Target> targets, FormResult r) {
		List<String> anims = new ArrayList<>();
		for (int i = 0; i < f.animations.size(); i++)if (r.getBool("anim_" + i))anims.add(f.animations.get(i));
		Map<String, String> pairs = new LinkedHashMap<>();
		for (int i = 0; i < f.bones.size(); i++) {
			String v = r.getString("bone_" + i);
			if (v != null && !v.isEmpty() && !NONE.equals(v))pairs.put(f.bones.get(i), v);
		}
		Result res;
		try {
			res = importAnimations(f, pairs, anims);
		} catch (IllegalArgumentException e) {
			r.keepOpen();
			PlusDialogs.quick(message(e), 6000);
			return;
		}
		remember(pairs, targets);
		report(res);
	}

	/** a refused import: lang key + its arguments (getMessage() is the key) */
	public static final class ImportError extends IllegalArgumentException {
		private static final long serialVersionUID = 1L;
		public final String key;
		public final Object[] args;

		public ImportError(String key, Object... args) {
			super(key);
			this.key = key;
			this.args = args;
		}
	}

	/** the translated text of a refused import */
	public static String message(IllegalArgumentException e) {
		if (e instanceof ImportError) {
			ImportError ie = (ImportError) e;
			return ie.args.length == 0 ? PlusDialogs.tr(ie.key) : PlusDialogs.trf(ie.key, ie.args);
		}
		return PlusDialogs.tr(e.getMessage() != null ? e.getMessage() : "");
	}

	/** one 'Feito: ... Ctrl+Z desfaz.' toast with a short summary of what was left out; a click opens the CPM settings */
	public static void report(Result res) {
		List<String> names = new ArrayList<>();
		for (Animation a : res.created)names.add(a.name);
		List<String> lines = new ArrayList<>();
		lines.add(PlusDialogs.trf("cpmplus.w0binfra.msg.done", PlusDialogs.trf("cpmplus.w2tools.bedrock.done", res.created.size(), String.join(", ", names))));
		if (!res.unmapped.isEmpty())lines.add(PlusDialogs.trf("cpmplus.w2tools.bedrock.sum.unmapped", String.join(", ", res.unmapped)));
		if (!res.orphansRemoved.isEmpty())lines.add(PlusDialogs.trf("cpmplus.w2tools.bedrock.sum.orphans", String.join(", ", res.orphansRemoved)));
		if (!res.molang.isEmpty())lines.add(PlusDialogs.trf("cpmplus.w2tools.bedrock.sum.molang", String.join(", ", res.molang)));
		if (!res.effectsDropped.isEmpty())lines.add(PlusDialogs.trf("cpmplus.w2tools.bedrock.sum.effects", String.join(", ", res.effectsDropped)));
		if (!res.tooLong.isEmpty())lines.add(PlusDialogs.trf("cpmplus.w2tools.bedrock.sum.tooLong", String.join(", ", res.tooLong)));
		if (res.layers != null)lines.add(PlusDialogs.trf("cpmplus.w2tools.bedrock.sum.layers", res.layers.count, res.layers.freeLayers, res.layers.capacity));
		lines.add(PlusDialogs.tr("cpmplus.w2tools.bedrock.sum.next"));
		Animation first = res.created.isEmpty() ? null : res.created.get(0);
		PlusDialogs.toast(String.join("\n", lines), "movie", 15000, first == null ? null : () -> openSettings(first));
		if (PlusTestHooks.active())PlusTestHooks.setLast("bedrockImport", toJson(res));
	}

	/** selects the animation and opens its CPM settings (type, loop, ...) */
	static void openSettings(Animation a) {
		if (a == null || W2ToolsNatives.animationByUuid(a.uuid) == null)return;// undone meanwhile
		try {
			a.select();
		} catch (Throwable e) {
		}
		AnimationWizard.open(a, b -> {
		});
	}

	public static Map<String, Object> toJson(Result res) {
		Map<String, Object> m = new LinkedHashMap<>();
		List<Object> c = new ArrayList<>();
		for (Animation a : res.created) {
			Map<String, Object> am = new LinkedHashMap<>();
			am.put("uuid", a.uuid);
			am.put("name", a.name);
			am.put("loop", a.loop);
			am.put("type", a.type);
			am.put("additive", a.additive);
			am.put("length", (double) a.length);
			List<Object> an = new ArrayList<>();
			for (String k : W2ToolsNatives.animatorKeys(a)) {
				Map<String, Object> x = new LinkedHashMap<>();
				x.put("uuid", k);
				x.put("type", W2ToolsNatives.animatorType(a, k));
				Group g = W2ToolsNatives.groupByUuid(k);
				x.put("group", g != null ? g.name : null);
				x.put("name", W2ToolsNatives.animatorName(a, k));
				x.put("keys", (double) W2ToolsNatives.animatorKeyCount(a, k));
				an.add(x);
			}
			am.put("animators", an);
			c.add(am);
		}
		m.put("created", c);
		m.put("unmapped", new ArrayList<Object>(res.unmapped));
		m.put("orphansRemoved", new ArrayList<Object>(res.orphansRemoved));
		m.put("molang", new ArrayList<Object>(res.molang));
		m.put("effectsDropped", new ArrayList<Object>(res.effectsDropped));
		m.put("tooLong", new ArrayList<Object>(res.tooLong));
		m.put("loaderRecordedUndo", res.loaderRecordedUndo);
		m.put("loader", res.loader);
		m.put("layerError", res.layers != null ? res.layers.errorKey : null);
		return m;
	}
}
