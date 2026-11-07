package com.tom.cpm.blockbench.tools.rig;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;

/**
 * CPM+ beta34 pro rig tools (installed by W4RigInstall): 'Reagir ao jogo…' (pose driven by a game value or a slider)
 * and 'Acompanhar parte do corpo…' (live copy of a vanilla part through adapter groups + the copy transform export
 * check). Actions live in CPM+ &gt; Animação / Modelo and in Blockbench's own right-click menus (group in the outliner,
 * animation in the animation list, part row of the timeline); everything is removed on unload.
 */
public final class RigPro {
	private RigPro() {
	}

	public static void install() {
		Action react = PlusMenus.action("cpmplus_rigpro_react", "cpmplus.rigpro.react.menu", "sensors", Cond.CPM_ONLY,
				() -> !AnimToolSupport.selectedGroups().isEmpty() || (Animation.selected != null && ReactToGame.driverOf(Animation.selected) != null), ReactToGame::open);
		PlusMenus.add(PlusMenus.SUB_ANIM, react);
		Menu gm = protoMenu("Group");
		if (gm != null)PlusMenus.addToContext(gm, react, "");
		Menu am = protoMenu("Animation");
		if (am != null)PlusMenus.addToContext(am, react, "");
		Menu bm = protoMenu("BoneAnimator");
		if (bm != null)PlusMenus.addToContext(bm, react, "");
		// 'Acompanhar parte do corpo…': live copy of a vanilla part through adapter groups (Copy Transform)
		Action follow = PlusMenus.action("cpmplus_rigpro_follow", "cpmplus.rigpro.follow.menu", "link", Cond.CPM_ONLY,
				() -> {
					for (com.tom.cpm.blockbench.proxy.Group g : AnimToolSupport.selectedGroups())if (g.parent != com.tom.cpm.blockbench.proxy.Group.ROOT)return true;
					return false;
				}, FollowPart::open);
		PlusMenus.add(PlusMenus.SUB_MODEL, follow);
		PlusMenus.add(PlusMenus.SUB_ANIM, follow);
		if (gm != null)PlusMenus.addToContext(gm, follow, "");
		FollowPart.installChecks();
		// the live preview turns the vanilla head again after every redraw of the animated pose
		PluginStart.addEventListener("display_animation_frame", __ -> RigPreview.onFrame());
		PluginStart.cleanup.add(RigPreview::stopActive);
		if (PlusTestHooks.active()) {
			PlusTestHooks.register("rigpro.react", ReactToGame::hook);
			PlusTestHooks.register("rigpro.follow", FollowPart::hook);
		}
	}

	public static void uninstall() {
		RigPreview.stopActive();
	}

	private static native Menu protoMenu(String cls) /*-{
		try {
			var c = $wnd[cls];
			return c && c.prototype && c.prototype.menu ? c.prototype.menu : null;
		} catch (e) {
			return null;
		}
	}-*/;
}
