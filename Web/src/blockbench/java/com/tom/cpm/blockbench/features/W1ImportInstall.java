package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.convert.CpmModelView;
import com.tom.cpm.blockbench.convert.ProjectConvert;

/**
 * Feature installer of package W1-IMPORT (CPM+ v0.8). Created empty by W0B-INFRA; only W1-IMPORT fills it.
 * PluginStart calls install() once per plugin start (inside its own try/catch: a failure here never stops the
 * plugin from loading) and uninstall() when the plugin unloads. Register everything through the shared helpers
 * (PlusMenus, CPMCodec.registerProperty, PluginStart.addEventListener ...) so it is removed on unload.
 * <p>
 * CPM+ &gt; Abrir .cpmmodel (só visualizar): CpmModelView. File &gt; Open, drag &amp; drop and the format page
 * button reach it too (CPMCodec).
 */
public final class W1ImportInstall {
	private W1ImportInstall() {
	}

	public static void install() {
		PlusMenus.add(PlusMenus.TOP, PlusMenus.action("cpmplus_w1io_viewmodel", "cpmplus.w1io.viewer.open", "visibility", Cond.ALWAYS, ProjectConvert::openModel));
		PluginStart.addEventListener("finished_edit", CpmModelView::onEdit);
	}

	public static void uninstall() {
	}
}
