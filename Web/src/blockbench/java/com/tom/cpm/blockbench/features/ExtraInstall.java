package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.ExtraTools;

/** CPM+ beta2 small tools: F5, F6, G63, F4 (Animação), E2+G61 and G3 (Animação, always available in CPM projects). */
public final class ExtraInstall {
	private ExtraInstall() {
	}

	public static void install() {
		Action f5 = PlusMenus.action("cpmplus_extra_f5", "cpmplus.extra.f5.menu", "loop", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, ExtraTools::openCloseLoop);
		Action f6 = PlusMenus.action("cpmplus_extra_f6", "cpmplus.extra.f6.menu", "update", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, ExtraTools::openShift);
		Action g63 = PlusMenus.action("cpmplus_extra_g63", "cpmplus.extra.g63.menu", "show_chart", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, ExtraTools::openEase);
		Action f4 = PlusMenus.action("cpmplus_extra_f4", "cpmplus.extra.f4.menu", "visibility_off", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, ExtraTools::openBlink);
		PlusMenus.add(PlusMenus.SUB_ANIM, f5);
		PlusMenus.add(PlusMenus.SUB_ANIM, f6);
		PlusMenus.add(PlusMenus.SUB_ANIM, g63);
		// F4 blink: the W3-ANIMGEN 'Gerar piscada' (regular or random) is the menu entry; this one stays for the hooks
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_extra_e2", "cpmplus.extra.e2.menu", "terminal", Cond.CPM_ONLY, ExtraTools::openCommands);
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_extra_g3", "cpmplus.extra.g3.menu", "format_color_reset", Cond.CPM_ONLY, ExtraTools::openGhostColours);
		// D1: animation optimizer (fewer frames within a tolerance), in Animação and Otimizar
		Action optim = PlusMenus.action("cpmplus_optim", "cpmplus.optim.menu", "compress", Cond.CPM_ONLY,
				() -> !com.tom.cpm.blockbench.tools.anim.AnimxUtil.animations().isEmpty(), com.tom.cpm.blockbench.tools.anim.AnimOptimizer::open);
		PlusMenus.add(PlusMenus.SUB_ANIM, optim);
		PlusMenus.add(PlusMenus.SUB_OPT, optim);
		com.tom.cpm.blockbench.PlusTestHooks.register("optim.run", com.tom.cpm.blockbench.tools.anim.AnimOptimizer::toolJson);
		// D4: import animations from another .cpmproject
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_animimport", "cpmplus.animimport.menu", "move_to_inbox", Cond.CPM_ONLY, com.tom.cpm.blockbench.tools.anim.AnimImport::open);
		com.tom.cpm.blockbench.PlusTestHooks.register("animimport.run", com.tom.cpm.blockbench.tools.anim.AnimImport::toolJson);
		ExtraTools.registerHooks();
	}

	public static void uninstall() {
	}
}
