package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ImportHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Codecs;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.MenuBar.SubMenu;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.ext.W3SettingsNatives;
import com.tom.cpm.blockbench.tools.settings.CopyProtState;
import com.tom.cpm.blockbench.tools.settings.DescriptionDialog;
import com.tom.cpm.blockbench.tools.settings.DescriptionDialog.DescValues;
import com.tom.cpm.blockbench.tools.settings.ModelSettings;
import com.tom.cpm.blockbench.tools.settings.ModelSettings.ApplyResult;
import com.tom.cpm.blockbench.tools.settings.ModelSettings.HubValues;
import com.tom.cpm.blockbench.tools.settings.SettingsHooks;
import com.tom.cpm.blockbench.tools.settings.SizeAttributesDialog;
import com.tom.cpm.blockbench.tools.settings.SizeAttributesDialog.SizeValues;
import com.tom.cpm.blockbench.tools.settings.SkinLayersDialog;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.ServerCommands;
import com.tom.cpm.shared.util.ScalingOptions;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W3-SETTINGS (CPM+ v0.8): 'Ajustes do modelo' (F11) with its sections 'Nome, descrição e
 * ícone' (F19 + G29 + G4 Part B), 'Tamanho e atributos' (F21 + G44) and 'Camadas da skin (avançado)' (W2's
 * SkinLayersDialog, reused), the export rows / import notices of the copy protection and the server scaling commands.
 */
public final class W3SettingsInstall {
	private W3SettingsInstall() {
	}

	public static void install() {
		// ---------------------------------------------------------------- menu: first entry of 'Ajustes do modelo'
		Action hub = PlusMenus.action("cpmplus_w3settings_hub", "cpmplus.w3settings.menu.hub", "settings", Cond.CPM_ONLY, null, ModelSettings::open);
		PlusMenus.add(PlusMenus.SUB_SETTINGS, hub);
		moveFirst(hub);
		Action desc = PlusMenus.action("cpmplus_w3settings_desc", "cpmplus.w3settings.menu.desc", "badge", Cond.CPM_ONLY, null, DescriptionDialog::open);
		PlusMenus.add(PlusMenus.SUB_SETTINGS, desc);
		Action size = PlusMenus.action("cpmplus_w3settings_size", "cpmplus.w3settings.menu.size", "height", Cond.CPM_ONLY, null, SizeAttributesDialog::open);
		PlusMenus.add(PlusMenus.SUB_SETTINGS, size);

		// ---------------------------------------------------------------- hub sections
		ModelSettings.addSection("desc", "cpmplus.w3settings.hub.section.desc", 10, DescriptionDialog::open);
		ModelSettings.addSection("size", "cpmplus.w3settings.hub.section.size", 20, SizeAttributesDialog::open);
		ModelSettings.addSection("skinLayers", "cpmplus.w3settings.hub.section.skinLayers", 30, () -> {
			// W2-WARN-I18N's dialog (the only skin-layer dialog): reused, never a second one
			PlusTestHooks.track(SkinLayersDialog.open());
		});

		// ---------------------------------------------------------------- export / import
		ExportHooks.register(SettingsHooks::exportCheck);
		ExportHooks.beforeFinish(SettingsHooks::beforeFinish);
		WarnFixes.register(SettingsHooks.KEY_CLONEABLE, "cpmplus.w3settings.fix.keepCloneable", 10, w -> SettingsHooks::keepCloneable);
		WarnFixes.register(SettingsHooks.KEY_CLONEABLE, "cpmplus.w3settings.fix.makeNormal", 20, w -> SettingsHooks::makeNormal);
		WarnFixes.register(SettingsHooks.KEY_UUID_LOCK, "cpmplus.w3settings.fix.chooseOwner", 10, w -> () -> {
			DescriptionDialog.open();
			return Promise.resolve(false);
		});
		WarnFixes.register(SettingsHooks.KEY_SCALING, "cpmplus.w3settings.fix.staffCommands", 10, w -> () -> {
			SizeAttributesDialog.openServerCard(Project.getData().scalingOpt);
			return Promise.resolve(false);
		});
		ImportHooks.afterImport((editor, imp) -> {
			SettingsHooks.readUuid(editor);
			SettingsHooks.onProjectOpened();
		});
		if(Codecs.project != null) {
			PluginStart.addEventListener(Codecs.project, "parsed", e -> W3SettingsNatives.later(SettingsHooks::onProjectOpened, 50));
		}

		registerTestTools();
	}

	public static void uninstall() {
	}

	/** puts the hub action first in the 'Ajustes do modelo' submenu */
	private static void moveFirst(Action a) {
		try {
			if(PlusMenus.getMenu() == null || PlusMenus.getMenu().structure == null)return;
			JsArray<Object> st = Js.uncheckedCast(PlusMenus.getMenu().structure);
			for (int i = 0; i < st.length; i++) {
				Object o = st.getAt(i);
				if(o == null || !"object".equals(Js.typeof(o)))continue;
				if(!PlusMenus.SUB_SETTINGS.equals(Js.asPropertyMap(o).get("id")))continue;
				SubMenu sm = Js.uncheckedCast(o);
				JsArray<Object> ch = Js.uncheckedCast(sm.children);
				int idx = ch.indexOf(a);
				if(idx > 0) {
					ch.splice(idx, 1);
					ch.unshift(a);
				}
				return;
			}
		} catch (Throwable e) {
		}
	}

	// ------------------------------------------------------------------ test hooks (test mode only)

	private static void registerTestTools() {
		if(!PlusTestHooks.enabled())return;
		PlusTestHooks.register("w3settings.state", o -> state());
		PlusTestHooks.register("w3settings.hub", o -> {
			HubValues v = HubValues.current();
			if(o.has("skinType"))v.skinType = String.valueOf(o.get("skinType"));
			if(o.has("adjustArms"))v.adjustArms = Js.isTruthy(o.get("adjustArms"));
			if(o.has("hideHeadIfSkull"))v.hideHeadIfSkull = Js.isTruthy(o.get("hideHeadIfSkull"));
			if(o.has("removeArmorOffset"))v.removeArmorOffset = Js.isTruthy(o.get("removeArmorOffset"));
			if(o.has("removeBedOffset"))v.removeBedOffset = Js.isTruthy(o.get("removeBedOffset"));
			if(o.has("invisGlow"))v.invisGlow = Js.isTruthy(o.get("invisGlow"));
			ApplyResult r = ModelSettings.apply(v, Js.isTruthy(o.get("toast")));
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("changed", r.changed);
			m.set("skinTypeChanged", r.skinTypeChanged);
			m.set("armsAdjusted", r.armsAdjusted);
			m.set("undoEntries", (double) r.undoEntries);
			m.set("adjusted", toJs(r.adjusted));
			m.set("skipped", toJs(r.skipped));
			m.set("state", state());
			return m;
		});
		PlusTestHooks.register("w3settings.desc", o -> {
			DescValues v = DescValues.current();
			if(o.has("name"))v.name = String.valueOf(o.get("name"));
			if(o.has("desc"))v.desc = String.valueOf(o.get("desc"));
			if(o.has("prot"))v.prot = String.valueOf(o.get("prot"));
			if(o.has("lockKind"))v.lockKind = String.valueOf(o.get("lockKind"));
			if(o.has("lockNick"))v.lockNick = String.valueOf(o.get("lockNick"));
			if(o.has("lockUuid"))v.lockUuid = String.valueOf(o.get("lockUuid"));
			v.removeIcon = Js.isTruthy(o.get("removeIcon"));
			boolean toast = Js.isTruthy(o.get("toast"));
			Promise<String> icon = o.has("icon") ? DescriptionDialog.iconFromDataUrl(String.valueOf(o.get("icon"))) : Promise.resolve((String) null);
			return icon.then(b64 -> {
				if(b64 != null)v.iconB64 = b64;
				DescriptionDialog.Result r = DescriptionDialog.apply(v, toast);
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("ok", r.error == null);
				m.set("error", r.error != null ? PlusDialogs.tr(r.error) : null);
				m.set("errorKey", r.error);
				m.set("changed", r.changed);
				m.set("descriptionRemoved", r.descriptionRemoved);
				m.set("state", state());
				return Promise.resolve((Object) m);
			}, e -> {
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("ok", false);
				m.set("iconError", String.valueOf(Js.asPropertyMap(e).get("message")));
				m.set("state", state());
				return Promise.resolve((Object) m);
			});
		});
		PlusTestHooks.register("w3settings.size", o -> {
			SizeValues v = SizeValues.current();
			Object opts = o.get("options");
			if(opts != null) {
				JsPropertyMap<Object> om = Js.uncheckedCast(opts);
				for(ScalingOptions so : ScalingOptions.VALUES) {
					String id = ServerCommands.id(so);
					if(om.has(id))v.options.put(so, (float) Js.asDouble(om.get(id)));
				}
			}
			if(o.has("renderPos"))v.renderPos = vec(o.get("renderPos"));
			if(o.has("renderRot"))v.renderRot = vec(o.get("renderRot"));
			if(o.has("renderScl"))v.renderScl = vec(o.get("renderScl"));
			SizeAttributesDialog.Result r = SizeAttributesDialog.apply(v, Js.isTruthy(o.get("toast")));
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("changed", r.changed);
			m.set("clamped", toJs(r.clamped));
			m.set("state", state());
			return m;
		});
		PlusTestHooks.register("w3settings.commands", o -> {
			Map<ScalingOptions, Float> vals = new EnumMap<>(ScalingOptions.class);
			Object opts = o.get("values");
			if(opts != null) {
				JsPropertyMap<Object> om = Js.uncheckedCast(opts);
				for(ScalingOptions so : ScalingOptions.VALUES) {
					String id = ServerCommands.id(so);
					if(om.has(id))vals.put(so, (float) Js.asDouble(om.get(id)));
				}
			} else vals.putAll(Project.getData().scalingOpt);
			String nick = o.has("nick") && o.get("nick") != null ? String.valueOf(o.get("nick")) : null;
			boolean useAll = Js.isTruthy(o.get("useAll")), limits = Js.isTruthy(o.get("limits"));
			List<ServerCommands.Item> items = ServerCommands.items(vals);
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("commands", String.join("\n", ServerCommands.commands(items, nick, useAll, limits)));
			m.set("message", SizeAttributesDialog.staffMessage(items, nick, useAll, limits));
			m.set("lines", toJs(SizeAttributesDialog.cardLines(items)));
			m.set("blocked", (double) ServerCommands.countBlocked(vals));
			return m;
		});
		PlusTestHooks.register("w3settings.validate", o -> {
			DescValues v = new DescValues();
			v.prot = DescriptionDialog.PROT_CLIENT;
			v.lockKind = o.has("lockKind") ? String.valueOf(o.get("lockKind")) : CopyProtState.KIND_MANUAL;
			v.lockNick = o.has("lockNick") ? String.valueOf(o.get("lockNick")) : "";
			v.lockUuid = o.has("lockUuid") ? String.valueOf(o.get("lockUuid")) : "";
			String err = DescriptionDialog.validate(v);
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", err == null);
			m.set("errorKey", err);
			m.set("uuid", err == null ? v.lockUuid : null);
			m.set("webMcUuid", DescriptionDialog.webMcUuid());
			return m;
		});
		PlusTestHooks.register("w3settings.open", o -> {
			String which = String.valueOf(o.get("dialog"));
			if("hub".equals(which))ModelSettings.open();
			else if("desc".equals(which))DescriptionDialog.open();
			else if("size".equals(which))SizeAttributesDialog.open();
			else if("server".equals(which))SizeAttributesDialog.openServerCard(Project.getData().scalingOpt);
			else if("notice".equals(which))SettingsHooks.onProjectOpened();
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			return m;
		});
		PlusTestHooks.register("w3settings.pngSize", o -> W3SettingsNatives.pngSize(String.valueOf(o.get("b64"))));
		// the rows the export dialog would show (headless conversion, nothing written): key, severity, message, fix labels
		PlusTestHooks.register("w3settings.exportRows", o -> ProjectConvert.convertHeadless().then(he -> {
			JsArray<Object> rows = new JsArray<>();
			for(WarnEntry w : he.warnings) {
				JsPropertyMap<Object> r = JsPropertyMap.of();
				r.set("key", w.getKey());
				r.set("severity", w.getSeverity().name());
				r.set("message", w.getMessage());
				JsArray<Object> fixes = new JsArray<>();
				for(WarnFixes.Choice c : WarnFixes.choicesFor(w))fixes.push(c.label);
				r.set("fixes", fixes);
				rows.push(r);
			}
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("rows", rows);
			m.set("uuid", he.editor.description != null && he.editor.description.uuid != null ? he.editor.description.uuid.toString() : null);
			return Promise.resolve((Object) m);
		}));
		// runs one fix of an export row exactly like its button (WarnFixes registry): {key, label (lang key)}
		PlusTestHooks.register("w3settings.fix", o -> ProjectConvert.convertHeadless().then(he -> {
			String key = String.valueOf(o.get("key"));
			String label = PlusDialogs.tr(String.valueOf(o.get("label")));
			for(WarnEntry w : he.warnings) {
				if(!key.equals(w.getKey()))continue;
				for(WarnFixes.Choice c : WarnFixes.choicesFor(w)) {
					if(!label.equals(c.label))continue;
					return w.runFix(c.task).then(done -> {
						JsPropertyMap<Object> m = JsPropertyMap.of();
						m.set("ok", true);
						m.set("fixed", done != null && done);
						m.set("state", state());
						return Promise.resolve((Object) m);
					});
				}
			}
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", false);
			m.set("error", "no row " + key + " with fix " + label);
			return Promise.resolve((Object) m);
		}));
		PlusTestHooks.register("w3settings.shotIcon", o -> W3SettingsNatives.viewportScreenshot().then(url -> DescriptionDialog.iconFromDataUrl(url)).then(b64 -> {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("b64", b64);
			return Promise.resolve((Object) m);
		}));
		PlusTestHooks.register("w3settings.copy", o -> {
			String nick = o.has("nick") && o.get("nick") != null ? String.valueOf(o.get("nick")) : null;
			return SizeAttributesDialog.copy(ServerCommands.items(Project.getData().scalingOpt), nick, Js.isTruthy(o.get("useAll")),
					Js.isTruthy(o.get("limits")), Js.isTruthy(o.get("message"))).then(auto -> {
						JsPropertyMap<Object> m = JsPropertyMap.of();
						m.set("ok", true);
						m.set("copiedAutomatically", auto != null && auto);
						return Promise.resolve((Object) m);
					});
		});
		PlusTestHooks.register("w3settings.sections", o -> {
			List<String> ids = new ArrayList<>();
			for(ModelSettings.Section s : ModelSettings.sections())ids.add(s.id);
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("sections", toJs(ids));
			return m;
		});
	}

	private static Vec3f vec(Object o) {
		JsArray<Object> a = Js.uncheckedCast(o);
		return new Vec3f((float) Js.asDouble(a.getAt(0)), (float) Js.asDouble(a.getAt(1)), (float) Js.asDouble(a.getAt(2)));
	}

	private static JsArray<Object> toJs(List<String> l) {
		JsArray<Object> a = new JsArray<>();
		for(String s : l)a.push(s);
		return a;
	}

	private static JsArray<Object> vecJs(Object v) {
		JsArray<Object> a = new JsArray<>();
		if(v == null)return null;
		JsArray<Object> src = Js.uncheckedCast(v);
		for (int i = 0; i < src.length; i++)a.push(src.getAt(i));
		return a;
	}

	private static JsPropertyMap<Object> state() {
		ProjectData pd = Project.getData();
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("ok", true);
		m.set("skinType", pd.skinType);
		m.set("customSkinType", pd.customSkinType);
		m.set("hideHeadIfSkull", Project.hideHeadIfSkull);
		m.set("removeArmorOffset", pd.removeArmorOffset);
		m.set("removeBedOffset", Project.removeBedOffset);
		m.set("invisGlow", Project.invisGlow);
		m.set("pluginData", Project.pluginData);
		m.set("saved", Project.saved);
		m.set("undoLen", (double) Undo.historyLength());
		m.set("hasDescription", pd.description != null);
		if(pd.description != null) {
			m.set("name", pd.description.name);
			m.set("desc", pd.description.desc);
		}
		m.set("copyProt", CopyProtState.of(pd).name().toLowerCase());
		m.set("cloneAck", CopyProtState.cloneAck(pd));
		m.set("lockUuid", CopyProtState.lockUuid(pd));
		m.set("lockKind", CopyProtState.lockUuid(pd) != null ? CopyProtState.lockKind(pd) : null);
		m.set("lockNick", CopyProtState.lockNick(pd));
		m.set("icon", pd.rawFiles.get(DescriptionDialog.ICON));
		JsPropertyMap<Object> sc = JsPropertyMap.of();
		pd.scalingOpt.forEach((k, v) -> {
			if(v != null)sc.set(ServerCommands.id(k), (double) v);
		});
		m.set("scaling", sc);
		m.set("scalingEnabled", pd.scalingEnabled);
		m.set("webMcUuid", DescriptionDialog.webMcUuid());
		// arm geometry (F11)
		JsPropertyMap<Object> arms = JsPropertyMap.of();
		for(String part : new String[] {"left_arm", "right_arm"}) {
			Group g = null;
			for(Group x : Group.all)if(x.parent == Group.ROOT && part.equals(x.name))g = x;
			if(g == null)continue;
			JsPropertyMap<Object> gm = JsPropertyMap.of();
			gm.set("origin", vecJs(g.origin));
			JsArray<Object> cubes = new JsArray<>();
			for (int i = 0; i < g.children.length; i++) {
				OutlinerNode n = g.children.getAt(i);
				if(!(n instanceof Cube))continue;
				Cube c = (Cube) n;
				JsPropertyMap<Object> cm = JsPropertyMap.of();
				cm.set("name", c.name);
				cm.set("from", vecJs(c.from));
				cm.set("to", vecJs(c.to));
				cm.set("uv", vecJs(c.uv_offset));
				cm.set("inflate", (double) c.inflate);
				cm.set("mirror", c.mirror_uv);
				cubes.push(cm);
			}
			gm.set("cubes", cubes);
			arms.set(part, gm);
		}
		m.set("arms", arms);
		return m;
	}
}
