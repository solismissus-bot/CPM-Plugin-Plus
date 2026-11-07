package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.CurlChain;
import com.tom.cpm.blockbench.tools.anim.FootSlip;
import com.tom.cpm.blockbench.tools.anim.MovingGround;
import com.tom.cpm.blockbench.tools.anim.SplitAnim;

/**
 * CPM+ beta34 tools for complex rigs and animations (package M34-PROA), installed by W3AnimtoolsInstall:
 * <ul>
 * <li>'Chão andando' (moving floor at Minecraft's walk / run / sneak speed) + 'Velocidade do chão...': CPM+ &gt;
 * Animação, CPM+ &gt; Ver and Blockbench's Animation menu (next to the motion trail / onion skin). The 'pé
 * escorregando' row of 'Conferir movimento' uses the same speed (FootSlip, called from MotionCheck).</li>
 * <li>'Dividir animação grande (mais de 255 partes)...': CPM+ &gt; Animação, the animation's right-click menu and a
 * 'Dividir' fix on the export row 'mais de 255 partes' (FormatLimitsCheck key limitParts, through WarnFixes).</li>
 * <li>'Curvar corrente / abrir em leque...': CPM+ &gt; Animação, the group right-click menu and the timeline row
 * menu (Animate mode).</li>
 * </ul>
 * Everything goes through PlusMenus / WarnFixes / PluginStart listeners and is removed on unload.
 */
public final class AnimProTools {
	private AnimProTools() {
	}

	public static void install() {
		// moving floor + foot slip
		MovingGround.install();
		Action.Toggle ground = PlusMenus.toggle("w34proa_ground", "cpmplus.w34proa.ground.menu", "directions_walk", Cond.CPM_ONLY, MovingGround.isOn(), MovingGround::set);
		MovingGround.setToggle(ground);
		Action speed = PlusMenus.action("cpmplus_w34proa_ground_speed", "cpmplus.w34proa.ground.speed.menu", "speed", Cond.CPM_ONLY, null, MovingGround::speedDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, ground);
		PlusMenus.add(PlusMenus.SUB_ANIM, speed);
		PlusMenus.add(PlusMenus.SUB_VIEW, ground);
		PlusMenus.addToMenuBar(ground, "animation");
		PlusMenus.addToMenuBar(speed, "animation");

		// split a big animation (more than 255 moving parts: CPM writes the count as one byte)
		Action split = PlusMenus.action("cpmplus_w34proa_split", "cpmplus.w34proa.split.menu", "call_split", Cond.CPM_ONLY, () -> Animation.selected != null, SplitAnim::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, split);
		PlusMenus.addToContext(protoMenu("Animation"), split, "");
		WarnFixes.register("limitParts", "cpmplus.w34proa.split.fixLabel", "cpmplus.w34proa.split.fixPreview", 10, w -> () -> SplitAnim.fromExportRow(w.getArgs()));

		// curl a chain / fan out several chains (a pose for whole chains, live preview)
		Action curl = PlusMenus.action("cpmplus_w34proa_curl", "cpmplus.w34proa.curl.menu", "rotate_90_degrees_ccw", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, CurlChain::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, curl);
		PlusMenus.addToContext(protoMenu("Group"), curl, "");
		PlusMenus.addToContext(protoMenu("BoneAnimator"), curl, "");
		CurlChain.installUndoLengthFix();

		if (PlusTestHooks.active()) {
			PlusTestHooks.register("w34proa.ground", MovingGround::hook);
			PlusTestHooks.register("w34proa.footSlip", FootSlip::hook);
			PlusTestHooks.register("w34proa.split", SplitAnim::hook);
			PlusTestHooks.register("w34proa.curl", CurlChain::hook);
			PlusTestHooks.register("w34proa.open", o -> {
				Object which = o.get("tool");
				if ("split".equals(which))SplitAnim.openDialog();
				else if ("groundSpeed".equals(which))MovingGround.speedDialog();
				else if ("curl".equals(which))CurlChain.openDialog();
				return o;
			});
		}
	}

	public static void uninstall() {
		MovingGround.uninstall();
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
