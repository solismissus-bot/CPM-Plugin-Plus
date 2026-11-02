package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.Exaggerate;
import com.tom.cpm.blockbench.tools.anim.PoseClipboard;
import com.tom.cpm.blockbench.tools.anim.PoseColumn;
import com.tom.cpm.blockbench.tools.anim.QuickAnim;

/**
 * Feature installer of the quick animation tools (CPM+ v0.8, lite build): F14 submenu 'Animação rápida' (Inverter,
 * Mudar velocidade, Deixar tudo suave / reto, Fantasma, Rastro, Espelhar enquanto animo), F17 'Exagerar ou suavizar
 * movimento' (0..300 %) and F23 'Marcar pose aqui' (also in the timeline row's right-click menu). Everything is
 * registered through PlusMenus and removed on unload.
 */
public final class W3AnimtoolsInstall {
	private W3AnimtoolsInstall() {
	}

	public static void install() {
		QuickAnim.install();
		Action f17 = PlusMenus.action("cpmplus_w3animtools_f17", "cpmplus.w3animtools.f17.menu", "open_in_full", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, Exaggerate::openDialog);
		Action f23 = PlusMenus.action("cpmplus_w3animtools_f23", "cpmplus.w3animtools.f23.menu", "add_road", Cond.CPM_ONLY,
				() -> AnimToolSupport.animateMode() && Animation.selected != null, PoseColumn::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, f17);
		PlusMenus.add(PlusMenus.SUB_ANIM, f23);
		PlusMenus.addToContext(protoMenu("BoneAnimator"), f23, "");

		// copy / paste / mirror the pose of many parts at once (CPM's Copy / Paste / Mirror Part Pose, one part each)
		java.util.function.BooleanSupplier inAnim = () -> AnimToolSupport.animateMode() && Animation.selected != null;
		Action pc = PlusMenus.action("cpmplus_w3animtools_posecopy", "cpmplus.w3animtools.pose.copy", "content_copy", Cond.CPM_ONLY, inAnim, PoseClipboard::copyAction);
		Action pp = PlusMenus.action("cpmplus_w3animtools_posepaste", "cpmplus.w3animtools.pose.paste", "content_paste", Cond.CPM_ONLY, inAnim, () -> PoseClipboard.pasteAction(false));
		Action pm = PlusMenus.action("cpmplus_w3animtools_posepastemirror", "cpmplus.w3animtools.pose.pasteMirror", "flip", Cond.CPM_ONLY, inAnim, () -> PoseClipboard.pasteAction(true));
		Action px = PlusMenus.action("cpmplus_w3animtools_posemirror", "cpmplus.w3animtools.pose.mirror", "compare_arrows", Cond.CPM_ONLY, inAnim, PoseClipboard::mirrorAction);
		for (Action a : new Action[] {pc, pp, pm, px}) {
			PlusMenus.add(PlusMenus.SUB_ANIM, a);
			PlusMenus.addToContext(protoMenu("BoneAnimator"), a, "");
			PlusMenus.addToContext(protoMenu("Group"), a, "");
		}

		if (PlusTestHooks.active()) {
			PlusTestHooks.register("w3animtools.f14reverse", QuickAnim::hookReverse);
			PlusTestHooks.register("w3animtools.f14interp", QuickAnim::hookInterp);
			PlusTestHooks.register("w3animtools.f14items", QuickAnim::hookItems);
			PlusTestHooks.register("w3animtools.f14native", QuickAnim::hookNative);
			PlusTestHooks.register("w3animtools.f17", Exaggerate::hook);
			PlusTestHooks.register("w3animtools.f23", PoseColumn::hook);
			PlusTestHooks.register("w3animtools.pose", PoseClipboard::hook);
			PlusTestHooks.register("w3animtools.open", o -> {
				Object which = o.get("tool");
				if ("f17".equals(which))Exaggerate.openDialog();
				else if ("f23".equals(which))PoseColumn.openDialog();
				return o;
			});
		}
	}

	public static void uninstall() {
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
