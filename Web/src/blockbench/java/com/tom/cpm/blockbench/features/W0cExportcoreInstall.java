package com.tom.cpm.blockbench.features;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ImportHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Codecs;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.blockbench.util.VisState;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.util.GetFreeSkinSlots;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.core.JsError;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W0C-EXPORTCORE (CPM+ v0.8): the export / import extension points (ExportHooks,
 * WarnFixes, ImportHooks, headless conversion), the shared root / id / layer utilities and the persistent
 * visibility of the .bbmodel save. Installs:
 * <ul>
 * <li>built-in export check 'storeIdCollision' (INFO): elements that would end up with ids that are the same number
 * in game (after StoreIDGen); the export renumbers them itself before writing (IdUtil.repairCollisions, the same new
 * ids on every export), and the fix 'Renumerar IDs' clears such ids stored in the project so the row goes away;</li>
 * <li>built-in export check 'layerCapacity' (BREAKS): more 'Skin Layer Triggered' animations than the free skin layers
 * of the project can encode (CPM's in-game export would throw); only when the project has free layers set (without
 * them CPM's editor asks which layers to use);</li>
 * <li>Codecs.project 'compile' listener: the saved .bbmodel keeps the user's visibility, never the Animate-mode
 * preview state (VisState).</li>
 * </ul>
 * In test mode only: the 'w0cexportcore.*' tools of the QA scenarios.
 */
public final class W0cExportcoreInstall {
	private static final List<Runnable> qaRemovers = new ArrayList<>();
	private static final Map<String, Integer> qaCounts = new LinkedHashMap<>();
	private static final List<Object> qaAfterWrite = new ArrayList<>();

	private W0cExportcoreInstall() {
	}

	public static void install() {
		ExportHooks.register(W0cExportcoreInstall::checkStoreIds);
		WarnFixes.register(IdUtil.WARN_KEY, "cpmplus.w0cexportcore.fix.renumber", "cpmplus.w0cexportcore.fix.renumber.preview", 10, w -> {
			Set<Long> ids = renumberedIds(w);
			if(storedIdNodes(ids).isEmpty())return null;// nothing stored to clear (not from the project's ids)
			return () -> renumberStoredIds(ids);
		});
		ExportHooks.register(W0cExportcoreInstall::checkLayerCapacity);
		PluginStart.addEventListener(Codecs.project, "compile", VisState::onCompile);
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
		qaRemovers.clear();
		qaCounts.clear();
		qaAfterWrite.clear();
	}

	// ====================================================================== built-in export checks

	/**
	 * Predicts the ids after StoreIDGen (the first element with a non-zero id keeps it; later duplicates and zeros get
	 * new random ids) and reports kept ids that are different numbers but the same double (= the same id in game).
	 * INFO: the export renumbers them itself (IdUtil.repairCollisions, deterministic), the file works in game. Row
	 * args: (names, ids the export renumbers as a comma separated list). When those ids are stored in the project
	 * (imported / copied parts), the fix 'Renumerar IDs' clears them in the project (one undo entry): the export then
	 * derives new stable ids from the uuids and the row goes away.
	 */
	static void checkStoreIds(ExportHooks.ExportContext ctx) {
		Set<Long> kept = new HashSet<>();
		Map<Double, List<ModelElement>> byD = new LinkedHashMap<>();
		Editor.walkElements(ctx.editor.elements, e -> {
			if(!IdUtil.hasStoreId(e) || e.storeID == 0)return;
			if(!kept.add(e.storeID))return;// a duplicate: StoreIDGen gives it a new id
			byD.computeIfAbsent((double) e.storeID, k -> new ArrayList<>()).add(e);
		});
		List<String> names = new ArrayList<>();
		Set<Long> renumbered = new HashSet<>();
		List<String> ids = new ArrayList<>();
		for (List<ModelElement> l : byD.values()) {
			if(l.size() < 2)continue;
			for (int i = 0; i < l.size(); i++) {
				ModelElement e = l.get(i);
				names.add(e.name != null ? e.name : "?");
				if(i > 0 && renumbered.add(e.storeID))ids.add(Long.toString(e.storeID));// the first one keeps its id
			}
		}
		if(names.isEmpty())return;
		boolean stored = !storedIdNodes(renumbered).isEmpty();
		String n = String.join(", ", names);
		ctx.add(WarnFactory.of(Severity.INFO, IdUtil.WARN_KEY, "cpmplus.w0cexportcore.warn.storeId", n)
				.setConsequence(stored ? "cpmplus.w0cexportcore.warn.storeId.stored" : "cpmplus.w0cexportcore.warn.storeId.plugin")
				.setKey(IdUtil.WARN_KEY, n, String.join(",", ids)));
	}

	/** the ids a storeIdCollision row says the export renumbers (its 2nd arg) */
	private static Set<Long> renumberedIds(WarnEntry w) {
		Set<Long> out = new HashSet<>();
		Object[] a = w.getArgs();
		if(a == null || a.length < 2 || a[1] == null)return out;
		for (String s : String.valueOf(a[1]).split(",")) {
			try {
				if(!s.trim().isEmpty())out.add(Long.parseLong(s.trim()));
			} catch (NumberFormatException e) {
			}
		}
		return out;
	}

	/** Blockbench groups / cubes whose STORED id (G16 'sid') is one of ids */
	private static List<Object> storedIdNodes(Set<Long> ids) {
		List<Object> out = new ArrayList<>();
		if(ids.isEmpty())return out;
		if(Group.all != null)for (Group g : Group.all)if(ids.contains(g.getData().getStoreID()))out.add(g);
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		if(cubesAll != null)for (int i = 0; i < cubesAll.length; i++) {
			Cube c = cubesAll.getAt(i);
			if(ids.contains(c.getData().getStoreID()))out.add(c);
		}
		return out;
	}

	/**
	 * Fix 'Renumerar IDs' of storeIdCollision: clears the stored ids of the parts the export would renumber (one undo
	 * entry); the export then gives them stable ids from their uuids. Resolves true (the dialog re-checks).
	 */
	static Promise<Boolean> renumberStoredIds(Set<Long> ids) {
		List<Group> gs = new ArrayList<>();
		List<Cube> cs = new ArrayList<>();
		for (Object o : storedIdNodes(ids)) {
			if(o instanceof Group)gs.add((Group) o);
			else cs.add((Cube) o);
		}
		if(gs.isEmpty() && cs.isEmpty())return Promise.resolve(false);
		String label = I18n.get("cpmplus.w0cexportcore.fix.renumber");
		PlusUndo.run(label, PlusUndo.structAfter(gs, cs, null), () -> PlusUndo.structAfter(gs, cs, null), () -> {
			for (Group g : gs)g.getData().setStoreID(0).flush();
			for (Cube c : cs)c.getData().setStoreID(0).flush();
		});
		PlusDialogs.done(label);
		return Promise.resolve(true);
	}

	/** 'Skin Layer Triggered' animations vs the free skin layers set in the project (CPM's resolveEncID rule) */
	static void checkLayerCapacity(ExportHooks.ExportContext ctx) {
		if(ctx.editor.animEnc == null)return;// no layers chosen: CPM's editor asks in game (Exporter.check)
		LayerCapacity.Result r = LayerCapacity.check(ctx.editor);
		if(r.ok())return;
		String key = LayerCapacity.ERR_NO_LAYERS.equals(r.errorKey) ? "cpmplus.w0cexportcore.warn.layerCapacity.noLayers" : "cpmplus.w0cexportcore.warn.layerCapacity.tooMany";
		ctx.add(WarnFactory.of(Severity.BREAKS, LayerCapacity.WARN_KEY, key, r.count, r.freeLayers, r.capacity)
				.setConsequence("cpmplus.w0cexportcore.warn.layerCapacity.consequence"));
	}

	// ====================================================================== test tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static boolean bool(JsPropertyMap<Object> o, String k) {
		return Js.isTruthy(o.get(k));
	}

	private static JsPropertyMap<Object> ok() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}

	private static JsArray<Object> vec(JsVec3 v) {
		JsArray<Object> a = new JsArray<>();
		if(v == null)return a;
		a.push((double) v.x, (double) v.y, (double) v.z);
		return a;
	}

	private static JsArray<Object> vec2(JsVec2 v) {
		JsArray<Object> a = new JsArray<>();
		if(v == null)return a;
		a.push((double) v.x, (double) v.y);
		return a;
	}

	private static Group group(String name) {
		for (Group g : Group.all)if (name.equals(g.name))return g;
		return null;
	}

	private static void count(String k) {
		qaCounts.merge(k, 1, Integer::sum);
	}

	private static JsPropertyMap<Object> rows(List<WarnEntry> ws) {
		JsArray<Object> arr = new JsArray<>();
		for (WarnEntry w : ws) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("key", w.getKey());
			m.set("severity", w.getSeverity().name());
			m.set("message", w.getMessage());
			m.set("fixKind", w.getFixKind().name());
			m.set("fixes", (double) WarnFixes.choicesFor(w).size());
			arr.push(m);
		}
		JsPropertyMap<Object> r = ok();
		r.set("rows", arr);
		return r;
	}

	/** element paths like the QA comparator: root id (+ '_dup'), children by name with '#n' for repeated names */
	private static JsArray<Object> paths(Editor e) {
		JsArray<Object> out = new JsArray<>();
		Map<String, Integer> rootSeen = new HashMap<>();
		for (ModelElement r : e.elements) {
			String id = ((VanillaModelPart) r.typeData).getName() + (r.duplicated ? "_dup" : "");
			int n = rootSeen.merge(id, 1, Integer::sum);
			String p = id + (n > 1 ? "#" + n : "");
			out.push(p);
			walk(r.children, p, out);
		}
		return out;
	}

	private static void walk(List<ModelElement> list, String parent, JsArray<Object> out) {
		Map<String, Integer> seen = new HashMap<>();
		for (ModelElement c : list) {
			String nm = c.name != null ? c.name : "";
			int n = seen.merge(nm, 1, Integer::sum);
			String p = parent + "/" + nm + (n > 1 ? "#" + n : "");
			out.push(p);
			walk(c.children, p, out);
		}
	}

	private static void registerTestTools() {
		// ---------------------------------------------------------------- INFRA2: RootUtil
		PlusTestHooks.register("w0cexportcore.root", o -> {
			String op = str(o, "op", "makeRoot");
			JsPropertyMap<Object> r = ok();
			SkinType st = SkinType.get(str(o, "skinType", "default"));
			if(st == null || st == SkinType.UNKNOWN)st = SkinType.DEFAULT;
			switch (op) {
			case "makeRoot": {
				// creates the group like the 'unknown root' fix does, reads it and removes it again (no undo entry)
				Group g = RootUtil.makeRoot(str(o, "name", "body"), st);
				r.set("name", g.name);
				r.set("origin", vec(g.origin));
				r.set("rotation", vec(g.rotation));
				r.set("parentIsRoot", g.parent == Group.ROOT);
				g.remove(false);
				break;
			}
			case "cubeSpec": {
				VanillaModelPart p = RootUtil.part(str(o, "part", "left_arm"));
				RootUtil.CubeSpec v = RootUtil.vanillaCubeSpec(p, st);
				JsPropertyMap<Object> vm = JsPropertyMap.of();
				vm.set("from", vec(v.from));
				vm.set("to", vec(v.to));
				vm.set("uv", vec2(v.uvOffset));
				vm.set("inflate", (double) v.inflate);
				vm.set("mirror", v.mirror);
				r.set("vanilla", vm);
				RootUtil.CubeSpec l = RootUtil.secondLayerCubeSpec(p, st);
				if(l != null) {
					JsPropertyMap<Object> lm = JsPropertyMap.of();
					lm.set("from", vec(l.from));
					lm.set("to", vec(l.to));
					lm.set("uv", vec2(l.uvOffset));
					lm.set("inflate", (double) l.inflate);
					lm.set("mirror", l.mirror);
					lm.set("name", RootUtil.secondLayerName(p, st));
					r.set("layer", lm);
				}
				r.set("rootOrigin", vec(RootUtil.rootOrigin(p, st)));
				r.set("rootRotation", vec(RootUtil.rootRotation(p, st)));
				break;
			}
			case "tree": {
				// findRootGroup / rootOf / isDescendant / copyVec on a group of the open project
				Group g = group(str(o, "group", "body"));
				Group root = RootUtil.findRootGroup(str(o, "root", "body"));
				r.set("found", root != null);
				r.set("rootOf", g != null && RootUtil.rootOf(g) != null ? RootUtil.rootOf(g).name : null);
				r.set("isDescendant", g != null && root != null && RootUtil.isDescendant(g, root));
				r.set("selfDescendant", root != null && RootUtil.isDescendant(root, root));
				if(root != null) {
					JsVec3 c = RootUtil.copyVec(root.origin);
					r.set("copySame", c == root.origin);
					r.set("copyEqual", c.x == root.origin.x && c.y == root.origin.y && c.z == root.origin.z);
				}
				r.set("skinType", RootUtil.projectSkinType().getName());
				r.set("skinTexture", RootUtil.findSkinTexture() != null ? RootUtil.findSkinTexture().name : null);
				break;
			}
			default:
				r.set("ok", false);
			}
			return r;
		});

		// ---------------------------------------------------------------- INFRA2: IdUtil
		PlusTestHooks.register("w0cexportcore.ids", o -> {
			String op = str(o, "op", "newId");
			JsPropertyMap<Object> r = ok();
			switch (op) {
			case "newId": {
				Set<Long> used = new HashSet<>();
				Object u = o.get("used");
				if(u != null && JsArray.isArray(u)) {
					JsArray<Object> ua = Js.uncheckedCast(u);
					for (int i = 0; i < ua.length; i++)used.add(Long.parseLong(String.valueOf(ua.getAt(i))));
				}
				int n = o.get("n") != null ? (int) Js.asDouble(o.get("n")) : 1000;
				JsArray<Object> ids = new JsArray<>();
				int bad = 0;
				Set<Double> usedD = new HashSet<>();
				for (Long l : used)usedD.add((double) l.longValue());
				for (int i = 0; i < n; i++) {
					long id = IdUtil.newId(used);
					if(id < IdUtil.MIN_ID || id >= IdUtil.MAX_EXCLUSIVE || used.contains(id) || usedD.contains((double) id))bad++;
					used.add(id);
					usedD.add((double) id);
					if(i < 20)ids.push(Long.toString(id));
				}
				r.set("sample", ids);
				r.set("bad", (double) bad);
				break;
			}
			case "setSid": {
				// G16 storeID of a group (exact decimal string, e.g. 64 bit ids that are the same double), one undo entry
				Group g = group(str(o, "group", ""));
				String sid = str(o, "sid", "0");
				if(g == null) {
					r.set("ok", false);
					break;
				}
				PlusUndo.run("CPM+ QA set id", PlusUndo.structBefore(g), () -> PlusUndo.structAfter(new Group[] {g}, null, null),
						() -> g.getData().setStoreID(Long.parseLong(sid)));
				r.set("data", g.pluginData);
				r.set("changed", true);
				break;
			}
			case "resetIds": {
				Group g = group(str(o, "group", ""));
				if(g == null) {
					r.set("ok", false);
					break;
				}
				List<String> before = new ArrayList<>();
				g.forEachChild(n -> before.add(String.valueOf(Js.asPropertyMap(n).get("cpm_data"))), null, true);
				PlusUndo.run("CPM+ QA reset ids", PlusUndo.structBefore(g), () -> PlusUndo.structAfter(new Group[] {g}, null, null), () -> IdUtil.resetIds(g));
				List<String> after = new ArrayList<>();
				g.forEachChild(n -> after.add(String.valueOf(Js.asPropertyMap(n).get("cpm_data"))), null, true);
				r.set("before", JsonUtil.toJs(new ArrayList<Object>(before)));
				r.set("after", JsonUtil.toJs(new ArrayList<Object>(after)));
				r.set("changed", true);
				break;
			}
			default:
				r.set("ok", false);
			}
			return r;
		});

		// ---------------------------------------------------------------- INFRA2: LayerCapacity
		PlusTestHooks.register("w0cexportcore.layers", o -> {
			String op = str(o, "op", "project");
			JsPropertyMap<Object> r = ok();
			if("rule".equals(op)) {
				LayerCapacity.Result lr = LayerCapacity.check((int) Js.asDouble(o.get("n")), (int) Js.asDouble(o.get("f")));
				r.set("count", (double) lr.count);
				r.set("free", (double) lr.freeLayers);
				r.set("capacity", (double) lr.capacity);
				r.set("error", lr.errorKey);
				return r;
			}
			if("setFreeLayers".equals(op)) {
				// the project's 'skin layers used by animations' (what CPM's anim_enc.json holds), test only
				ProjectData pd = Project.getData();
				List<Object> free = new ArrayList<>();
				Map<String, Object> def = new LinkedHashMap<>();
				Object l = o.get("layers");
				if(l != null && JsArray.isArray(l)) {
					JsArray<Object> la = Js.uncheckedCast(l);
					for (int i = 0; i < la.length; i++) {
						free.add(String.valueOf(la.getAt(i)));
						def.put(String.valueOf(la.getAt(i)), false);
					}
				}
				if(!(pd.animations.get("anims") instanceof Map))pd.animations.put("anims", new LinkedHashMap<String, Object>());
				pd.animations.put("free", free);
				pd.animations.put("def", def);
				pd.flush();
				r.set("changed", true);
			}
			LayerCapacity.Result p = LayerCapacity.checkProject();
			r.set("projectCount", (double) p.count);
			r.set("projectFree", (double) p.freeLayers);
			r.set("projectError", p.errorKey);
			return ProjectConvert.buildEditor(false).then(e -> {
				LayerCapacity.Result er = LayerCapacity.check(e);
				r.set("editorCount", (double) er.count);
				r.set("editorFree", (double) er.freeLayers);
				r.set("editorError", er.errorKey);
				r.set("hasAnimEnc", e.animEnc != null);
				return Promise.resolve(r);
			});
		});

		// ---------------------------------------------------------------- INFRA5: buildEditor / convertHeadless
		PlusTestHooks.register("w0cexportcore.headless", o -> {
			String op = str(o, "op", "build");
			if("fix".equals(op)) {
				// runs fix 'choice' of the first row with this key (what its button does), without the dialog
				String key = str(o, "key", "");
				int choice = o.get("choice") != null ? (int) Js.asDouble(o.get("choice")) : 0;
				return ProjectConvert.convertHeadless().then(h -> {
					JsPropertyMap<Object> r = ok();
					WarnEntry w = h.warnings.stream().filter(x -> key.equals(x.getKey())).findFirst().orElse(null);
					List<WarnFixes.Choice> cs = w != null ? WarnFixes.choicesFor(w) : new ArrayList<>();
					JsArray<Object> labels = new JsArray<>();
					for (WarnFixes.Choice c : cs)labels.push(c.label);
					r.set("labels", labels);
					if(w == null || choice >= cs.size()) {
						r.set("ok", false);
						return Promise.resolve(r);
					}
					return w.runFix(cs.get(choice).task).then(applied -> {
						r.set("applied", applied != null && applied);
						r.set("changed", applied != null && applied);
						return Promise.resolve(r);
					});
				});
			}
			if("convert".equals(op)) {
				return ProjectConvert.convertHeadless().then(h -> {
					JsPropertyMap<Object> r = rows(h.warnings);
					r.set("paths", paths(h.editor));
					r.set("parts", (double) BlockbenchExport.countExportedParts(h.editor));
					r.set("inGameCubes", (double) BlockbenchExport.countInGameCubes(h.editor));
					r.set("animations", (double) h.editor.animations.size());
					r.set("animMap", (double) h.exp.getAnimMap().size());
					r.set("uuidLookup", (double) h.exp.getUuidLookup().size());
					JsPropertyMap<Object> frames = JsPropertyMap.of();
					for (Map.Entry<String, EditorAnim> en : h.exp.getAnimMap().entrySet())frames.set(en.getKey(), (double) en.getValue().getFrames().size());
					r.set("framesByBbUuid", frames);
					JsPropertyMap<Object> colour = JsPropertyMap.of();
					for (Group g : Group.all)colour.set(g.name, BlockbenchExport.isColourTarget(g));
					r.set("colourTargets", colour);
					return Promise.resolve(r);
				});
			}
			boolean warn = bool(o, "warnings");
			return ProjectConvert.buildEditor(warn).then(e -> {
				JsPropertyMap<Object> r = ok();
				r.set("paths", paths(e));
				r.set("count", (double) countAll(e));
				r.set("animations", (double) e.animations.size());
				return Promise.resolve(r);
			}, err -> {
				JsPropertyMap<Object> r = ok();
				r.set("ok", false);
				r.set("cancelled", err instanceof PopupDialogs.CancelException);
				r.set("error", String.valueOf(err));
				return Promise.resolve(r);
			});
		});

		// ---------------------------------------------------------------- INFRA6: Exporter.exportModelTo (.cpmmodel bytes)
		PlusTestHooks.register("w0cexportcore.cpmmodel", o -> ProjectConvert.buildEditor(false).then(e -> new Promise<JsPropertyMap<Object>>((res, rej) -> {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			boolean[] closed = new boolean[1];
			boolean[] done = new boolean[1];
			int[] errors = new int[1];
			int[] closes = new int[1];
			JsPropertyMap<Object> r = ok();
			// fail: 'write' = the sink throws while the file is written into it (CPM+ QA of the never-truncated rule)
			String fail = str(o, "fail", "");
			java.io.OutputStream sink = new java.io.OutputStream() {
				@Override
				public void write(int b) throws java.io.IOException {
					if("write".equals(fail))throw new java.io.IOException("CPM+ QA: sink write failed");
					bout.write(b);
				}

				@Override
				public void write(byte[] b, int off, int len) throws java.io.IOException {
					if("write".equals(fail))throw new java.io.IOException("CPM+ QA: sink write failed");
					bout.write(b, off, len);
				}

				@Override
				public void close() {
					closes[0]++;
					r.set("closes", (double) closes[0]);
					if(closed[0])return;
					closed[0] = true;
					byte[] b = bout.toByteArray();
					r.set("b64", Base64.getEncoder().encodeToString(b));
					r.set("size", (double) b.length);
					if(!done[0]) {
						done[0] = true;
						res.onInvoke(r);
					}
				}
			};
			r.set("closes", 0d);
			r.set("errors", 0d);
			// no skin layers chosen for the custom animations: the in-game editor asks (Exporter.check) and preselects
			// the layers the skin leaves free; do the same here
			if(e.animEnc == null && e.animations.stream().anyMatch(EditorAnim::isCustom) && e.vanillaSkin != null) {
				e.animEnc = GetFreeSkinSlots.getDefault(e);
				r.set("defaultLayers", (double) e.animEnc.freeLayers.size());
			}
			ModelDescription d = new ModelDescription();
			d.name = str(o, "name", "CPM+ QA");
			d.desc = str(o, "desc", "");
			String link = str(o, "link", "local:cpmplusqa");
			boolean okExp = Exporter.exportModelTo(e, new BBUI(), d, () -> sink, (data, lc) -> {
				r.set("overflowBytes", (double) data.length);
				lc.accept(new Link(link));
			}, err -> {
				errors[0]++;
				r.set("errors", (double) errors[0]);
				r.set("error", String.valueOf(err));
			});
			r.set("returned", okExp);
			r.set("closed", closed[0]);
			if(!done[0] && (!okExp || errors[0] > 0)) {
				done[0] = true;
				res.onInvoke(r);
			}
		})));

		// ---------------------------------------------------------------- ExportHooks / WarnFixes / ImportHooks
		PlusTestHooks.register("w0cexportcore.hooks", o -> {
			String op = str(o, "op", "counts");
			JsPropertyMap<Object> r = ok();
			switch (op) {
			case "addCheck": {
				String key = str(o, "key", "qaCheck");
				String msg = str(o, "message", "CPM+ QA check");
				Severity sev = Severity.valueOf(str(o, "severity", "DIFFERS"));
				boolean builtIn = bool(o, "builtInFix");
				String fixPreview = str(o, "fixPreview", null);
				qaRemovers.add(ExportHooks.register(ctx -> {
					count("check");
					WarnEntry w = WarnFactory.ofHtml(sev, key, Dialog.esc(msg));
					if(builtIn)w.setFix(() -> {
						count("builtInFix");
						return Promise.resolve(false);
					}, WarnEntry.FixKind.ASKS);
					if(fixPreview != null)w.setFixPreview(fixPreview);
					ctx.add(w);
				}));
				break;
			}
			case "addFix": {
				String key = str(o, "key", "qaCheck");
				String label = str(o, "label", "cpmplus.w0cexportcore.qa.fixA");
				String preview = str(o, "preview", null);
				// throwMsg: the fix throws right away (no promise); rejectMsg: its promise rejects with that text
				String thr = str(o, "throwMsg", null);
				String rejMsg = str(o, "rejectMsg", null);
				int order = o.get("order") != null ? (int) Js.asDouble(o.get("order")) : 10;
				qaRemovers.add(WarnFixes.register(key, label, preview, order, w -> () -> {
					count("fix:" + label);
					if(thr != null)throw new IllegalStateException(thr);
					if(rejMsg != null)return Promise.reject(rejMsg);
					return Promise.resolve(false);
				}));
				break;
			}
			case "addImportHooks":
				qaRemovers.add(ImportHooks.beforeAnims(e -> count("beforeAnims")));
				qaRemovers.add(ImportHooks.onElement((me, n) -> count(n instanceof Group ? "elementGroup" : n instanceof Cube ? "elementCube" : "elementOther")));
				qaRemovers.add(ImportHooks.afterImport((e, imp) -> {
					count("afterImport");
					if(imp.getElementGroups().isEmpty())count("afterImportNoGroups");
				}));
				break;
			case "addTranslator": {
				String match = str(o, "match", "CPM+ QA");
				String text = str(o, "text", "QA");
				qaRemovers.add(ExportHooks.translateException(t -> t != null && String.valueOf(t.getMessage()).contains(match) ? text : null));
				break;
			}
			case "addBeforeFinish": {
				String thr = str(o, "throwMsg", null);
				qaRemovers.add(ExportHooks.beforeFinish(e -> {
					count("beforeFinish");
					if(thr != null)throw new IllegalStateException(thr);
				}));
				break;
			}
			case "addAfterWrite":
				qaRemovers.add(ExportHooks.afterWrite(res -> {
					count("afterWrite");
					JsPropertyMap<Object> m = JsPropertyMap.of();
					m.set("path", res.path);
					m.set("bytes", (double) (res.bytes != null ? res.bytes.byteLength : -1));
					m.set("editor", res.editor != null);
					qaAfterWrite.add(m);
				}));
				break;
			case "clear":
				for (Runnable rr : qaRemovers)rr.run();
				qaRemovers.clear();
				qaCounts.clear();
				qaAfterWrite.clear();
				break;
			case "counts":
				break;
			default:
				r.set("ok", false);
			}
			JsPropertyMap<Object> c = JsPropertyMap.of();
			for (Map.Entry<String, Integer> en : qaCounts.entrySet())c.set(en.getKey(), (double) en.getValue());
			r.set("counts", c);
			JsArray<Object> aw = new JsArray<>();
			for (Object x : qaAfterWrite)aw.push(x);
			r.set("afterWrite", aw);
			return r;
		});

		// ---------------------------------------------------------------- B5: error dialog
		PlusTestHooks.register("w0cexportcore.error", o -> {
			String kind = str(o, "kind", "jsError");
			String text = str(o, "text", "CPM+ QA");
			Object err;
			switch (kind) {
			case "null":
				err = null;
				break;
			case "throwable":
				err = new IllegalStateException(text);
				break;
			case "string":
				err = text;
				break;
			default:
				err = new JsError(text);
			}
			PopupDialogs.displayError(str(o, "msg", I18n.get("bb-label.error.export")), err);
			return ok();
		});
	}

	private static int countAll(Editor e) {
		int[] c = new int[1];
		Editor.walkElements(e.elements, el -> c[0]++);
		return c[0];
	}
}
