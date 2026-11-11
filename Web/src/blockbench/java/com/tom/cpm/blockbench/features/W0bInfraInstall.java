package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Property;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.DataCache;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.blockbench.util.VersionBanner;
import com.tom.cpm.shared.util.ScalingOptions;
import com.tom.cpm.web.client.resources.Resources;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W0B-INFRA (CPM+ v0.8 foundation): G16 version stamp / newer-file notice and, in
 * test mode only, the 'w0binfra.*' tools the QA scenarios use to drive the shared infrastructure (dialogs, undo,
 * prefs, G16 flush paths, property registry, menu conditions, natives).
 */
public final class W0bInfraInstall {
	private static int selectionEvents;

	private W0bInfraInstall() {
	}

	public static void install() {
		VersionBanner.install();
		// G16: Ctrl+Z / redo / a cancelled edit restore cpm_data strings: drop the cached group / cube data so
		// getData() / getExtra() / exports read the restored strings (unflushed changes are not in the history)
		PluginStart.addEventListener("undo", DataCache::onUndoLoaded);
		PluginStart.addEventListener("redo", DataCache::onUndoLoaded);
		PluginStart.addEventListener("load_undo_save", DataCache::onUndoLoaded);
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
		selectionEvents = 0;
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

	private static Group rootGroup(String name) {
		for (Group g : Group.all)if (g.parent == Group.ROOT && name.equalsIgnoreCase(g.name))return g;
		return null;
	}

	private static Cube cube(String name) {
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		for (int i = 0; i < cubesAll.length; i++)if (name.equals(cubesAll.getAt(i).name))return cubesAll.getAt(i);
		return null;
	}

	private static void registerTestTools() {
		// ---------------------------------------------------------------- INFRA4: lang fragments merged by I18n
		PlusTestHooks.register("w0binfra.i18n", o -> {
			JsPropertyMap<Object> r = ok();
			r.set("locale", I18n.locale);
			JsArray<Object> frags = new JsArray<>();
			for (String res : Resources.listResources())if (res.startsWith("assets/cpmplus_") && res.contains("/lang/"))frags.push(res);
			r.set("fragments", frags);
			JsPropertyMap<Object> vals = JsPropertyMap.of();
			Object keys = o.get("keys");
			if (keys != null && JsArray.isArray(keys)) {
				JsArray<Object> ka = Js.uncheckedCast(keys);
				for (int i = 0; i < ka.length; i++) {
					String k = String.valueOf(ka.getAt(i));
					vals.set(k, I18n.get(k));
				}
			}
			r.set("values", vals);
			return r;
		});

		// ---------------------------------------------------------------- INFRA1: dialogs, escaping, toasts, clipboard
		PlusTestHooks.register("w0binfra.dialogs", o -> {
			String op = str(o, "op", "form");
			String text = str(o, "text", "<b>x</b>");
			JsPropertyMap<Object> r = ok();
			switch (op) {
			case "form": {
				List<FormRow> rows = new ArrayList<>();
				rows.add(FormRow.info("info", text, text));
				rows.add(FormRow.text("name", text, text));
				rows.add(FormRow.checkbox("cb", text, true));
				rows.add(FormRow.checkbox("dep", "cpmplus.w0binfra.qa.dependent", false).when(fr -> fr.getBool("cb")));
				rows.add(FormRow.select("sel", text, PlusDialogs.options("a", text, "b", "B"), "a"));
				rows.add(FormRow.radio("rad", text, PlusDialogs.options("ra", text, "rb", "RB"), "ra"));
				rows.add(FormRow.inlineSelect("inl", text, PlusDialogs.options("ia", text, "ib", "IB"), "ia"));
				rows.add(FormRow.multiSelect("mul", text, PlusDialogs.options("ma", text, "mb", "MB"), Arrays.asList("ma")));
				rows.add(FormRow.number("num", text, 7, 0, 10));
				rows.add(FormRow.decimal("dec", text, 0.5, 0, 1, 0.1));
				rows.add(FormRow.range("rng", text, 3, 0, 10, 1));
				rows.add(FormRow.color("col", text, "#ff0000"));
				rows.add(FormRow.textarea("ta", text, text));
				rows.add(FormRow.separator());
				rows.add(FormRow.buttons("btn", text, new String[] {text}, i -> PlusTestHooks.setLast("dialogButton", (double) i)));
				PlusDialogs.form(str(o, "id", "w0binfra_qa_form"), text, rows, fr -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("name", fr.getString("name"));
					m.put("cb", fr.getBool("cb"));
					m.put("dep", fr.getBool("dep"));
					m.put("sel", fr.getString("sel"));
					m.put("rad", fr.getString("rad"));
					m.put("inl", fr.getString("inl"));
					m.put("mul", new ArrayList<Object>(fr.getList("mul")));
					m.put("num", fr.getInt("num", -1));
					m.put("dec", fr.getDouble("dec", -1));
					m.put("rng", fr.getDouble("rng", -1));
					m.put("col", fr.getColor("col"));
					m.put("ta", fr.getString("ta"));
					PlusTestHooks.setLast("dialogResult", m);
				});
				break;
			}
			case "info":
				PlusDialogs.info(str(o, "id", "w0binfra_qa_info"), text, Arrays.asList(text, "line 2\nline 3"));
				break;
			case "confirm":
				PlusDialogs.confirm(text, () -> PlusTestHooks.setLast("confirm", "yes"));
				break;
			case "warn":
				PlusDialogs.warnContinue(text, () -> PlusTestHooks.setLast("warn", "continue"));
				break;
			case "block":
				PlusDialogs.block(text);
				break;
			case "done":
				PlusDialogs.done(text);
				break;
			case "toast":
				PlusDialogs.toast(text, "info", 4000, () -> PlusTestHooks.setLast("toastClick", "clicked"));
				break;
			case "message":
				PlusDialogs.messageBox("cpmplus.w0binfra.dlg.info.title", text, new String[] {"cpmplus.w0binfra.btn.ok"}, i -> PlusTestHooks.setLast("messageBox", (double) i));
				break;
			case "bbmessage":
				PlusDialogs.bbMessageBox("cpmplus.w0binfra.dlg.info.title", text, new String[] {"cpmplus.w0binfra.btn.ok"}, i -> PlusTestHooks.setLast("bbMessageBox", (double) i));
				break;
			case "desktopOnly":
				PlusDialogs.desktopOnly("cpmplus.w0binfra.qa.feature");
				break;
			case "copy":
				return PlusDialogs.copyText(text).then(b -> {
					JsPropertyMap<Object> rr = ok();
					rr.set("copied", b);
					return elemental2.promise.Promise.resolve(rr);
				});
			default:
				r.set("ok", false);
			}
			return r;
		});

		// ---------------------------------------------------------------- INFRA1: guarded natives
		PlusTestHooks.register("w0binfra.natives", o -> {
			JsPropertyMap<Object> r = ok();
			JsPropertyMap<Object> has = JsPropertyMap.of();
			for (String n : new String[] {"showQuickMessage", "showToastNotification", "setStatusBarText", "setProgress", "pickDirectory", "showMessageBox", "export", "import", "read", "writeFile"})
				has.set(n, Blockbench.has(n));
			r.set("has", has);
			r.set("isApp", Global.isApp());
			r.set("isWeb", Blockbench.isWebApp());
			r.set("isDesktop", PlusDialogs.isDesktop());
			r.set("canPickDirectory", PlusDialogs.canPickDirectory());
			r.set("canWriteFiles", PlusDialogs.canWriteFiles());
			r.set("undoCancelEdit", Undo.has("cancelEdit"));
			r.set("pickDirectory", PlusDialogs.pickDirectory("cpmplus.w0binfra.qa.pick", null));
			String write = str(o, "write", null);
			if (write != null) {
				Blockbench.WriteProperties wp = new Blockbench.WriteProperties();
				wp.content = Js.uncheckedCast("CPM+ QA");
				wp.savetype = "text";
				r.set("wrote", PlusDialogs.writeFile(write, wp, p -> PlusTestHooks.setLast("writeFileCallback", p)));
			}
			PlusDialogs.status("CPM+ QA");
			PlusDialogs.status(null);
			PlusDialogs.progress(0.5);
			PlusDialogs.progress(0);
			PlusDialogs.quick("CPM+ QA", 500);
			String outside = str(o, "outside", null);
			if (outside != null) {
				try {
					PlusDialogs.checkTestPath(outside);
					r.set("outsideRefused", false);
				} catch (IllegalStateException e) {
					r.set("outsideRefused", true);
				}
			}
			String inside = str(o, "inside", null);
			if (inside != null) {
				try {
					PlusDialogs.checkTestPath(inside);
					r.set("insideAllowed", true);
				} catch (IllegalStateException e) {
					r.set("insideAllowed", false);
				}
			}
			return r;
		});

		// ---------------------------------------------------------------- INFRA3: PlusUndo
		PlusTestHooks.register("w0binfra.undo", o -> {
			String op = str(o, "op", "throw");
			JsPropertyMap<Object> r = ok();
			r.set("historyBefore", (double) Undo.historyLength());
			r.set("groupsBefore", (double) Group.all.length);
			switch (op) {
			case "throw": {
				Group target = rootGroup(str(o, "group", "body"));
				boolean oldDva = target != null && target.disableVanillaAnim;
				try {
					Group[] created = new Group[1];
					PlusUndo.run("CPM+ QA throw", PlusUndo.structBefore(target != null ? new Group[] {target} : new Group[0]),
							() -> PlusUndo.structAfter(created[0] != null ? new Group[] {created[0]} : new Group[0], null, null), () -> {
								GroupProperties gp = new GroupProperties();
								gp.name = "qa_half_applied";
								gp.origin = JsVec3.make(0, 0, 0);
								created[0] = new Group(gp).init();
								created[0].addTo(Group.ROOT);
								if (target != null)target.disableVanillaAnim = !oldDva;
								throw new IllegalStateException("CPM+ QA: deliberate failure");
							});
					r.set("threw", false);
				} catch (IllegalStateException e) {
					r.set("threw", true);
					r.set("message", e.getMessage());
				}
				r.set("dvaRestored", target == null || target.disableVanillaAnim == oldDva);
				r.set("halfAppliedGroupLeft", rootGroup("qa_half_applied") != null);
				break;
			}
			case "groupProp": {
				Group target = rootGroup(str(o, "group", "body"));
				if (target == null) {
					r.set("ok", false);
					break;
				}
				boolean v = !target.disableVanillaAnim;
				PlusUndo.run("CPM+ QA dva", UndoData.make(new Group[] {target}), () -> UndoData.make(new Group[] {target}), () -> target.disableVanillaAnim = v);
				r.set("uuid", target.uuid);
				r.set("value", v);
				r.set("changed", true);
				break;
			}
			case "outliner": {
				Group[] created = new Group[1];
				PlusUndo.run("CPM+ QA add group", PlusUndo.structBefore(), () -> PlusUndo.structAfter(new Group[] {created[0]}, null, null), () -> {
					GroupProperties gp = new GroupProperties();
					gp.name = str(o, "name", "qa_new_group");
					gp.origin = JsVec3.make(0, 0, 0);
					created[0] = new Group(gp).init();
					created[0].addTo(Group.ROOT);
				});
				r.set("uuid", created[0].uuid);
				r.set("changed", true);
				break;
			}
			default:
				r.set("ok", false);
			}
			r.set("historyAfter", (double) Undo.historyLength());
			r.set("groupsAfter", (double) Group.all.length);
			r.set("editing", Undo.isEditing());
			return r;
		});

		// ---------------------------------------------------------------- INFRA8: PlusPrefs
		PlusTestHooks.register("w0binfra.prefs", o -> {
			String op = str(o, "op", "get");
			String key = str(o, "key", "x");
			JsPropertyMap<Object> r = ok();
			switch (op) {
			case "set":
				r.set("stored", PlusPrefs.set(key, o.get("value")));
				break;
			case "get":
				r.set("value", PlusPrefs.get(key, str(o, "def", null)));
				break;
			case "getBool":
				r.set("value", PlusPrefs.getBool(key, bool(o, "def")));
				break;
			case "remove":
				r.set("stored", PlusPrefs.remove(key));
				break;
			default:
				r.set("ok", false);
			}
			r.set("storageKey", PlusPrefs.STORAGE_KEY);
			return r;
		});

		// ---------------------------------------------------------------- G16: flush paths, extras, stamp, undo of pluginData
		PlusTestHooks.register("w0binfra.g16", o -> {
			String op = str(o, "op", "flushProject");
			JsPropertyMap<Object> r = ok();
			ProjectData pd = Project.getData();
			switch (op) {
			case "flushProject":
				pd.flush();
				break;
			case "editScaling":
				pd.scalingOpt.put(ScalingOptions.ENTITY, (float) (o.get("value") != null ? Js.asDouble(o.get("value")) : 1.5));
				pd.flush();
				break;
			case "setModelId":
				pd.modelId = str(o, "value", "qa-model-id");
				pd.flush();
				break;
			case "clearModelId":
				pd.modelId = null;
				pd.flush();
				break;
			case "removeAnimEntry": {
				Map<String, Object> an = JsonUtil.asMap(pd.animations.get("anims"));
				Object removed = an != null ? an.remove(str(o, "name", "")) : null;
				r.set("removed", removed != null);
				pd.flush();
				break;
			}
			case "toggleExtrude": {
				Cube c = cube(str(o, "cube", ""));
				if (c == null) {
					r.set("ok", false);
					break;
				}
				CubeData cd = c.getData();
				if (cd.getExtrude() == null)cd.setExtrude(true).setU(1).setV(2).setTs(1);
				else cd.setExtrude(false);
				cd.flush();
				r.set("data", c.pluginData);
				break;
			}
			case "touchGroup": {
				// flushes a group's data after a change of a known field and back (GroupData path of 'add item slot' etc.)
				Group g = null;
				for (Group x : Group.all)if (x.name.equals(str(o, "group", "")))g = x;
				if (g == null) {
					r.set("ok", false);
					break;
				}
				GroupData gd = g.getData();
				boolean l = gd.isLocked();
				gd.setLocked(!l);
				gd.flush();
				gd.setLocked(l);
				gd.flush();
				r.set("data", g.pluginData);
				break;
			}
			case "extras": {
				Group g = rootGroup("body");
				Cube c = cube(str(o, "cube", ""));
				pd.putExtra("qaExtra", JsonUtil.fromJs(o.get("value")));
				if (pd.description != null)pd.putDescExtra("qaDesc", "d");
				pd.flush();
				r.set("projectExtra", JsonUtil.toJs(Project.getData().getExtra("qaExtra")));
				if (g != null) {
					g.getData().putExtra("qaExtra", "g").flush();
					r.set("groupData", g.pluginData);
					DataCache.invalidate(g);
					r.set("groupExtraAfterReparse", JsonUtil.toJs(g.getData().getExtra("qaExtra")));
					g.getData().removeExtra("qaExtra").flush();
					r.set("groupDataAfterRemove", g.pluginData);
				}
				if (c != null) {
					c.getData().putExtra("qaExtra", "c").flush();
					r.set("cubeData", c.pluginData);
					c.getData().removeExtra("qaExtra").flush();
					r.set("cubeDataAfterRemove", c.pluginData);
				}
				List<String> refused = new ArrayList<>();
				for (String k : new String[] {"modelId", "desc", "cpmplus"}) {
					try {
						pd.putExtra(k, "x");
					} catch (IllegalArgumentException e) {
						refused.add(k);
					}
				}
				r.set("refusedKnownKeys", JsonUtil.toJs(new ArrayList<Object>(refused)));
				pd.removeExtra("qaExtra");
				if (pd.description != null)pd.removeDescExtra("qaDesc");
				pd.flush();
				break;
			}
			case "invalidate":
				DataCache.invalidateAll();
				break;
			case "stamp":
				r.set("stamp", JsonUtil.toJs(VersionBanner.stamp(JsonUtil.asMap(JsonUtil.fromJs(o.get("existing"))))));
				break;
			case "undoData": {
				// G16 data edited inside PlusUndo.run: group extra + lock, cube extra + extrude toggle (one entry)
				Group g = null;
				for (Group x : Group.all)if (x.name.equals(str(o, "group", "")))g = x;
				Cube c = cube(str(o, "cube", ""));
				if (g == null || c == null) {
					r.set("ok", false);
					break;
				}
				Group fg = g;
				String mode = str(o, "mode", "put");// put | throwAfterFlush | throwNoFlush
				boolean flush = bool(o, "flush");
				UndoData asp = new UndoData();
				asp.groups = new Group[] {g};
				asp.elements = new OutlinerElement[] {c};
				r.set("historyBefore", (double) Undo.historyLength());
				try {
					PlusUndo.run("CPM+ QA data", asp, () -> asp, () -> {
						GroupData gd = fg.getData();
						gd.putExtra("qaUndo", JsonUtil.fromJs(o.get("value")));
						gd.setLocked(!gd.isLocked());
						CubeData cd = c.getData();
						cd.putExtra("qaUndo", "c");
						if (cd.getExtrude() == null)cd.setExtrude(true).setU(1).setV(2).setTs(1);
						else cd.setExtrude(false);
						if (flush || "throwAfterFlush".equals(mode)) {
							gd.flush();
							cd.flush();
						}
						if (!"put".equals(mode))throw new IllegalStateException("CPM+ QA: deliberate failure after a data edit");
					});
					r.set("threw", false);
				} catch (IllegalStateException e) {
					r.set("threw", true);
				}
				r.set("historyAfter", (double) Undo.historyLength());
				r.set("editing", Undo.isEditing());
				dataState(r, g, c);
				break;
			}
			case "readData": {
				Group g = null;
				for (Group x : Group.all)if (x.name.equals(str(o, "group", "")))g = x;
				Cube c = cube(str(o, "cube", ""));
				if (g == null || c == null) {
					r.set("ok", false);
					break;
				}
				dataState(r, g, c);
				break;
			}
			case "nulls": {
				// the extras API never writes a new JSON null (older CPM plugins cannot read it)
				List<String> refused = new ArrayList<>();
				Group g = rootGroup("body");
				Cube c = cube(str(o, "cube", ""));
				Map<String, Object> nested = new LinkedHashMap<>();
				nested.put("a", null);
				List<Object> nestedList = new ArrayList<>(Arrays.asList(1, null));
				Map<String, Object> inList = new LinkedHashMap<>();
				inList.put("l", nestedList);
				Runnable[] tries = {
					() -> pd.putExtra("qaNull", nested),
					() -> pd.putExtra("qaNull", inList),
					() -> pd.putDescExtra("qaNull", nested),
					() -> g.getData().putExtra("qaNull", nested),
					() -> c.getData().putExtra("qaNull", inList),
				};
				String[] names = {"project", "projectList", "desc", "group", "cube"};
				for (int i = 0; i < tries.length; i++) {
					try {
						tries[i].run();
					} catch (IllegalArgumentException e) {
						refused.add(names[i]);
						if (i == 0)r.set("message", e.getMessage());
					}
				}
				r.set("refused", JsonUtil.toJs(new ArrayList<Object>(refused)));
				pd.putExtra("qaNullTop", "x");
				pd.putExtra("qaNullTop", null);// top-level null = remove
				r.set("topNullRemoves", !pd.hasExtra("qaNullTop"));
				pd.flush();
				r.set("hasNullInData", Project.pluginData != null && Project.pluginData.contains("qaNull"));
				if (g != null)r.set("groupHasNull", g.pluginData != null && g.pluginData.contains("qaNull"));
				if (c != null)r.set("cubeHasNull", c.pluginData != null && c.pluginData.contains("qaNull"));
				break;
			}
			case "pdUndo": {
				ProjectDataUndo.Snapshot s = ProjectDataUndo.snapshot();
				r.set("before", s.getPluginData());
				r.set("flagsBefore", flags());
				pd.modelId = "qa-undo-" + pd.modelId;
				pd.flush();
				Project.hideHeadIfSkull = !Project.hideHeadIfSkull;
				Project.invisGlow = !Project.invisGlow;
				Project.removeBedOffset = !Project.removeBedOffset;
				r.set("after", Project.pluginData);
				ProjectDataUndo.offerUndo(s, str(o, "what", "QA"));
				break;
			}
			case "flags":
				r.set("flags", flags());
				break;
			default:
				r.set("ok", false);
			}
			r.set("pluginData", Project.pluginData);
			r.set("saved", Project.saved);
			return r;
		});

		// ---------------------------------------------------------------- CPMCodec.registerProperty after init
		PlusTestHooks.register("w0binfra.registerProperty", o -> {
			Property.Clazz clz = Property.Clazz.valueOf(str(o, "clazz", "GROUP"));
			Property.Type type = Property.Type.valueOf(str(o, "type", "STRING"));
			String name = str(o, "name", "cpm_plus_qa_prop");
			CPMCodec.registerProperty(clz, type, name, "CPM+ QA", o.get("def"), !Js.isTruthy(o.get("notExported")), true, !Js.isTruthy(o.get("anyFormat")));
			JsPropertyMap<Object> r = ok();
			r.set("names", JsonUtil.toJs(new ArrayList<Object>(CPMCodec.registeredPropertyNames())));
			r.set("changed", true);
			return r;
		});

		// ---------------------------------------------------------------- PlusMenus conditions
		PlusTestHooks.register("w0binfra.menus", o -> {
			JsPropertyMap<Object> r = ok();
			if (Global.getBarItem("cpmplus_w0binfra_qa_always") == null) {
				PlusMenus.add(PlusMenus.SUB_HELP, PlusMenus.action("w0binfra_qa_always", "cpmplus.w0binfra.qa.always", "info", Cond.ALWAYS, () -> PlusTestHooks.setLast("menuClick", "always")));
				PlusMenus.add(PlusMenus.SUB_HELP, PlusMenus.action("w0binfra_qa_any", "cpmplus.w0binfra.qa.any", "info", Cond.ANY_PROJECT, () -> PlusTestHooks.setLast("menuClick", "any")));
				PlusMenus.add(PlusMenus.SUB_HELP, PlusMenus.action("w0binfra_qa_cpm", "cpmplus.w0binfra.qa.cpm", "info", Cond.CPM_ONLY, () -> PlusTestHooks.setLast("menuClick", "cpm")));
				PlusMenus.add(PlusMenus.SUB_HELP, PlusMenus.action("w0binfra_qa_throw", "cpmplus.w0binfra.qa.throw", "info", Cond.ALWAYS, () -> {
					throw new IllegalStateException("CPM+ QA: deliberate click failure");
				}));
				r.set("changed", true);
			}
			r.set("menuId", PlusMenus.MENU_ID);
			return r;
		});

		// ---------------------------------------------------------------- CPMCodec.onSelectionChange
		PlusTestHooks.register("w0binfra.selection", o -> {
			if (bool(o, "listen"))CPMCodec.onSelectionChange(() -> selectionEvents++);
			JsPropertyMap<Object> r = ok();
			r.set("events", (double) selectionEvents);
			return r;
		});
	}

	/** what getData() / getExtra() report for a group and a cube, next to their cpm_data strings */
	private static void dataState(JsPropertyMap<Object> r, Group g, Cube c) {
		r.set("groupData", g.pluginData);
		r.set("cubeData", c.pluginData);
		GroupData gd = g.getData();
		CubeData cd = c.getData();
		r.set("groupExtra", JsonUtil.toJs(gd.getExtra("qaUndo")));
		r.set("cubeExtra", JsonUtil.toJs(cd.getExtra("qaUndo")));
		r.set("locked", gd.isLocked());
		r.set("extrude", cd.getExtrude() != null);
	}

	private static JsPropertyMap<Object> flags() {
		JsPropertyMap<Object> f = JsPropertyMap.of();
		f.set("hideHeadIfSkull", Project.hideHeadIfSkull);
		f.set("invisGlow", Project.invisGlow);
		f.set("removeBedOffset", Project.removeBedOffset);
		return f;
	}
}
