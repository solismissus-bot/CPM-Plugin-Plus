package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.tools.anim.OrphanAnims;

import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W4-EXPORTFIX (CPM+ v0.8). Created empty by W0B-INFRA; only W4-EXPORTFIX fills it.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Register everything through the shared helpers
 * (PlusMenus, CPMCodec.registerProperty, PluginStart.addEventListener ...) so it is removed on unload.
 */
public final class W4ExportfixInstall {
	private W4ExportfixInstall() {
	}

	public static void install() {
		// G9: formula rows in the export + 'Transformar formulas em keyframes'
		com.tom.cpm.blockbench.tools.anim.FormulaTools.install();

		// G1: one refresh per undo / redo (every cached CPM data object dropped); 'Adicionar partes / slots' and the
		// unknown-root fix are one undo entry each (PartsUndo / UnknownRootFix)
		com.tom.cpm.blockbench.tools.model.UndoRefresh.install();
		PlusTestHooks.register("w4exportfix.fixRoot", o -> {
			JsPropertyMap<Object> m = jsinterop.base.Js.asPropertyMap(o);
			com.tom.cpm.blockbench.proxy.Group g = com.tom.cpm.blockbench.util.RootUtil.findRootGroup(String.valueOf(m.get("group")));
			if (g == null)throw new IllegalArgumentException("no root-level group " + m.get("group"));
			if (jsinterop.base.Js.isTruthy(m.get("suggest")))return JsPropertyMap.of("suggest", com.tom.cpm.blockbench.convert.UnknownRootFix.preselect(g));
			com.tom.cpm.blockbench.proxy.Group t = com.tom.cpm.blockbench.convert.UnknownRootFix.apply(g, String.valueOf(m.get("root")), jsinterop.base.Js.isTruthy(m.get("dup")),
					jsinterop.base.Js.isTruthy(m.get("rename")), com.tom.cpm.blockbench.util.RootUtil.projectSkinType());
			return JsPropertyMap.of("ok", true, "target", t != null ? t.name : null);
		});

		// G7: loose ('orphan') animations - remember part names on the animators (finish_edit + mode switch),
		// one export row per old part, menu 'Animacoes soltas...'
		OrphanAnims.installKeepOnDelete();
		PluginStart.addEventListener("finish_edit", OrphanAnims::stampNames);
		PluginStart.addEventListener("select_mode", OrphanAnims::stampNames);
		ExportHooks.register(OrphanAnims::exportCheck);
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "w4exportfix_orphans", "cpmplus.w4exportfix.menu.orphans", "link_off", Cond.CPM_ONLY, OrphanAnims::openMenu);
		PlusTestHooks.register("w4exportfix.orphans", o -> OrphanAnims.listJson());
		PlusTestHooks.register("w4exportfix.reconnect", OrphanAnims::applyJson);
		PlusTestHooks.register("w4exportfix.stampNames", o -> {
			OrphanAnims.stampNames(null);
			return JsPropertyMap.of("ok", true);
		});
		PlusTestHooks.register("w4exportfix.openOrphans", o -> {
			OrphanAnims.openMenu();
			return JsPropertyMap.of("ok", true);
		});
	}

	public static void uninstall() {
		OrphanAnims.uninstallKeepOnDelete();
		com.tom.cpm.blockbench.tools.anim.FormulaTools.uninstall();
	}
}
