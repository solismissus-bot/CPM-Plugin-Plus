package com.tom.cpm.blockbench.features;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import com.tom.cpl.util.Image;
import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.ext.W3ModelexportNatives;
import com.tom.cpm.blockbench.tools.export.ModelExport;
import com.tom.cpm.blockbench.tools.export.SkinExport;
import com.tom.cpm.blockbench.tools.export.TakeToGame;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.ImageIO;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W3-MODELEXPORT (CPM+ v0.8): getting the model into the game.
 * <ul>
 * <li>CPM+ &gt; Levar pro jogo\u2026 (3 cards: skin with the model inside / .cpmmodel / .cpmproject) - TakeToGame;</li>
 * <li>CPM+ &gt; Exportar modelo (.cpmmodel), also in File &gt; Export (C4 + G4 Part A) - ModelExport;</li>
 * <li>CPM+ &gt; Testar no jogo agora, Ctrl+Alt+T by default (G23) - TakeToGame;</li>
 * <li>CPM+ &gt; Conferir uma skin\u2026 (G45) - SkinExport.</li>
 * </ul>
 * BBUI.displayPopup (CPM's own popups) is never silent any more (ModelExport.unsupportedPopup). In test mode only:
 * the 'w3modelexport.*' tools of the QA scenarios.
 */
public final class W3ModelexportInstall {
	private W3ModelexportInstall() {
	}

	public static void install() {
		Action take = PlusMenus.action("cpmplus_w3modelexport_take", "cpmplus.w3modelexport.menu.take", "sports_esports", Cond.CPM_ONLY, TakeToGame::open);
		PlusMenus.add(PlusMenus.TOP, take);
		Action exp = PlusMenus.action("cpmplus_export_cpmmodel", "cpmplus.w3modelexport.menu.cpmmodel", "file_download", Cond.CPM_ONLY, ModelExport::exportAction);
		PlusMenus.add(PlusMenus.TOP, exp);
		try {
			PlusMenus.addToMenuBar(exp, "file.export");
		} catch (Throwable e) {
			elemental2.dom.DomGlobal.console.warn("CPM+: File > Export entry not added", e);
		}
		PlusMenus.add(PlusMenus.TOP, testAction());
		Action check = PlusMenus.action("cpmplus_w3modelexport_checkskin", "cpmplus.w3modelexport.menu.checkSkin", "fact_check", Cond.ALWAYS, SkinExport::checkSkinAction);
		PlusMenus.add(PlusMenus.TOP, check);
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	/** 'Testar no jogo agora' with the default shortcut Ctrl+Alt+T (rebindable in Blockbench's keybinds) */
	private static Action testAction() {
		String name = I18n.get("cpmplus.w3modelexport.test.action");
		Action.ActionProperties a = new Action.ActionProperties();
		a.name = name;
		a.description = I18n.get("cpmplus.w3modelexport.test.action.desc");
		a.icon = "play_circle";
		a.category = "tools";
		Object kb = W3ModelexportNatives.keybind("t", true, true, false);
		if (kb != null)a.keybind = kb;
		a.condition = Cond.CPM_ONLY.toCondition(null);
		a.click = e -> {
			if (!Cond.CPM_ONLY.met())return;
			try {
				TakeToGame.testInGame();
			} catch (Throwable ex) {
				PopupDialogs.displayError(I18n.format("cpmplus.w0binfra.error.actionFailed", PlusDialogs.esc(name)), ex);
			}
		};
		Action act = new Action(PlusMenus.prefixed("cpmplus_w3modelexport_test"), a);
		PluginStart.cleanup.add(act::delete);
		return act;
	}

	// ====================================================================== QA tools (test mode only)

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static JsPropertyMap<Object> ok() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w3modelexport.slug", o -> {
			JsPropertyMap<Object> r = ok();
			r.set("slug", ModelExport.slug(str(o, "name", null), str(o, "fallback", "model")));
			r.set("testFile", TakeToGame.testFileName());
			r.set("command", TakeToGame.command(TakeToGame.testFileName()));
			return r;
		});
		PlusTestHooks.register("w3modelexport.state", o -> {
			JsPropertyMap<Object> r = ok();
			ProjectData pd = Project.getData();
			r.set("copyProt", pd.description != null && pd.description.copyProtection != null ? pd.description.copyProtection.name() : null);
			r.set("descName", pd.description != null ? pd.description.name : null);
			r.set("descDesc", pd.description != null ? pd.description.desc : null);
			Object free = pd.animations.get("free");
			r.set("animFree", free instanceof List ? Js.<Object>uncheckedCast(com.tom.cpm.blockbench.util.JsonUtil.toJs(free)) : null);
			r.set("hasDef", pd.animations.get("def") instanceof Map);
			r.set("savedLink", SkinExport.savedLink());
			r.set("savedHash", SkinExport.savedHash());
			JsPropertyMap<Object> c = ModelExport.qaCounters();
			r.set("counters", c);
			return r;
		});
		PlusTestHooks.register("w3modelexport.popup", o -> {
			new BBUI().displayPopup(f -> null);
			JsPropertyMap<Object> r = ok();
			r.set("popupClass", ModelExport.qaLastPopupClass());
			return r;
		});
		PlusTestHooks.register("w3modelexport.verify", o -> {
			byte[] b = Base64.getDecoder().decode(str(o, "b64", ""));
			ModelExport.Verify v = ModelExport.verifyModelFile(b);
			JsPropertyMap<Object> r = ok();
			r.set("ok", v.ok);
			r.set("error", v.error);
			r.set("name", v.name);
			r.set("desc", v.desc);
			r.set("link", v.link);
			r.set("dataBytes", (double) v.dataBytes);
			r.set("overflowBytes", (double) v.overflowBytes);
			return r;
		});
		PlusTestHooks.register("w3modelexport.skin", o -> {
			String op = str(o, "op", "measure");
			JsPropertyMap<Object> r = ok();
			switch (op) {
			case "baseError": {
				int w = (int) Js.asDouble(o.get("w")), h = (int) Js.asDouble(o.get("h"));
				r.set("error", SkinExport.baseError(new Image(w, h)));
				return r;
			}
			case "ui":
				SkinExport.open();
				return r;
			case "compare":
				r.set("error", SkinExport.GistWizard.compare(str(o, "text", null), str(o, "b64", "")));
				return r;
			case "parse":
				try {
					r.set("link", SkinExport.GistWizard.parse(str(o, "url", "")).toString());
				} catch (Throwable e) {
					r.set("ok", false);
					r.set("error", e.getMessage());
				}
				return r;
			case "verify":
				return SkinExport.GistWizard.verify(str(o, "url", ""), str(o, "b64", "")).then(l -> {
					r.set("link", l.toString());
					return Promise.resolve(r);
				}, err -> {
					r.set("ok", false);
					r.set("error", err instanceof RuntimeException ? ((RuntimeException) err).getMessage() : String.valueOf(err));
					return Promise.resolve(r);
				});
			case "inspect":
				try {
					Image img = ImageIO.load(new ByteArrayInputStream(Base64.getDecoder().decode(str(o, "png", ""))));
					r.set("text", SkinExport.describe(img));
					SkinExport.Inspect in = img.getWidth() == 64 && img.getHeight() == 64 ? SkinExport.inspect(img) : new SkinExport.Inspect();
					r.set("hasData", in.hasData);
					r.set("checksumOk", in.checksumOk);
					r.set("skinType", in.skinType);
					r.set("link", in.link);
					r.set("bytes", (double) in.bytes);
				} catch (Throwable e) {
					r.set("ok", false);
					r.set("error", String.valueOf(e));
				}
				return r;
			default:
				String l = str(o, "link", null);
				return SkinExport.qaTool(op, str(o, "base", "default"), l != null ? new Link(l) : null, r);
			}
		});
	}
}
