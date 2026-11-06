package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.BatchKeys;
import com.tom.cpm.blockbench.tools.anim.CorrectionLayer;
import com.tom.cpm.blockbench.tools.anim.Exaggerate;
import com.tom.cpm.blockbench.tools.anim.Physics;
import com.tom.cpm.blockbench.tools.anim.PoseClipboard;
import com.tom.cpm.blockbench.tools.anim.PoseColumn;
import com.tom.cpm.blockbench.tools.anim.QuickAnim;
import com.tom.cpm.blockbench.tools.anim.ClipInsert;
import com.tom.cpm.blockbench.tools.anim.EmoteImport;
import com.tom.cpm.blockbench.tools.anim.KeyAids;
import com.tom.cpm.blockbench.tools.anim.MotionCheck;
import com.tom.cpm.blockbench.tools.anim.SimpleKits;
import com.tom.cpm.blockbench.tools.anim.Takes;
import com.tom.cpm.blockbench.tools.anim.TestInCpm;
import com.tom.cpm.blockbench.tools.anim.VideoHelp;
import com.tom.cpm.blockbench.tools.anim.ViewAids;
import com.tom.cpm.blockbench.tools.anim.RelativeAdjust;
import com.tom.cpm.blockbench.tools.anim.TipTrail;
import com.tom.cpm.blockbench.tools.anim.Transitions;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.format.CPMCodec;

import elemental2.promise.Promise;
import com.tom.cpm.blockbench.tools.anim.Tween;

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
		// in-between pose at the playhead (tween / breakdown), many parts at once, loop-aware
		Action tw = PlusMenus.action("cpmplus_w3animtools_tween", "cpmplus.w3animtools.tween.menu", "linear_scale", Cond.CPM_ONLY, inAnim, Tween::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, tw);
		PlusMenus.addToContext(protoMenu("BoneAnimator"), tw, "");
		PlusMenus.addToContext(protoMenu("Group"), tw, "");
		// physics / follow-through baked into an additive layer that plays with the animation in game
		Action ph = PlusMenus.action("cpmplus_w3animtools_physics", "cpmplus.w3animtools.physics.menu", "waves", Cond.CPM_ONLY, inAnim, Physics::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, ph);
		PlusMenus.addToContext(protoMenu("BoneAnimator"), ph, "");
		PlusMenus.addToContext(protoMenu("Group"), ph, "");
		// relative adjust (same change on every key, many animations) + correction layer (create / strength / merge)
		Action ra = PlusMenus.action("cpmplus_w3animtools_reladj", "cpmplus.w3animtools.reladj.menu", "open_with", Cond.CPM_ONLY, inAnim, RelativeAdjust::openDialog);
		Action ce = PlusMenus.action("cpmplus_w3animtools_corr_edit", "cpmplus.w3animtools.corr.menu.edit", "layers", Cond.CPM_ONLY, inAnim, CorrectionLayer::editAction);
		Action cs = PlusMenus.action("cpmplus_w3animtools_corr_strength", "cpmplus.w3animtools.corr.menu.strength", "tune", Cond.CPM_ONLY, inAnim, CorrectionLayer::strengthDialog);
		Action cm = PlusMenus.action("cpmplus_w3animtools_corr_merge", "cpmplus.w3animtools.corr.menu.merge", "merge_type", Cond.CPM_ONLY, inAnim, CorrectionLayer::mergeDialog);
		for (Action a : new Action[] {ra, ce, cs, cm})PlusMenus.add(PlusMenus.SUB_ANIM, a);
		PlusMenus.addToContext(protoMenu("BoneAnimator"), ra, "");
		PlusMenus.addToContext(protoMenu("Group"), ra, "");
		PlusMenus.addToContext(protoMenu("Animation"), ce, "");
		PlusMenus.addToContext(protoMenu("Animation"), cs, "");
		PlusMenus.addToContext(protoMenu("Animation"), cm, "");
		// transitions kit: CPM entry / exit (staged p:/c:/g:) + the cut report between poses
		Action tr = PlusMenus.action("cpmplus_w3animtools_trans", "cpmplus.w3animtools.trans.menu", "login", Cond.CPM_ONLY, inAnim, Transitions::openDialog);
		Action rep = PlusMenus.action("cpmplus_w3animtools_trans_report", "cpmplus.w3animtools.trans.report.menu", "bolt", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), Transitions::openReport);
		PlusMenus.add(PlusMenus.SUB_ANIM, tr);
		PlusMenus.add(PlusMenus.SUB_ANIM, rep);
		PlusMenus.addToContext(protoMenu("Animation"), tr, "");
		Transitions.installNameGuard();
		// key operations on many animations at once (speed, intensity, retarget, copy / delete a track)
		Action bk = PlusMenus.action("cpmplus_w3animtools_batch", "cpmplus.w3animtools.batch.menu", "library_add_check", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), BatchKeys::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, bk);
		// automatic motion check (spikes, leftover step keys, loop seam, gesture not at rest, robotic, twins)
		Action mc = PlusMenus.action("cpmplus_w3animtools_check", "cpmplus.w3animtools.check.menu", "fact_check", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), MotionCheck::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, mc);
		PlusMenus.addToContext(protoMenu("Animation"), mc, "");
		// emote import (Emotecraft / PlayerAnimator) + clip sequencer
		Action em = PlusMenus.action("cpmplus_w3animtools_emote", "cpmplus.w3animtools.emote.menu", "emoji_people", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), EmoteImport::open);
		Action ci = PlusMenus.action("cpmplus_w3animtools_clip", "cpmplus.w3animtools.clip.menu", "playlist_add", Cond.CPM_ONLY, inAnim, ClipInsert::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, em);
		PlusMenus.add(PlusMenus.SUB_ANIM, ci);
		// view aids (stepped preview, silhouette, click again = the part behind) + key aids + takes
		ViewAids.install();
		com.tom.cpm.blockbench.proxy.Action.Toggle stepped = PlusMenus.toggle("w3animtools_stepped", "cpmplus.w3animtools.aids.stepped.menu", "stairs", Cond.CPM_ONLY, ViewAids.isStepped(), ViewAids::setStepped);
		com.tom.cpm.blockbench.proxy.Action.Toggle sil = PlusMenus.toggle("w3animtools_silhouette", "cpmplus.w3animtools.aids.silhouette.menu", "contrast", Cond.CPM_ONLY, ViewAids.isSilhouette(), ViewAids::setSilhouette);
		com.tom.cpm.blockbench.proxy.Action.Toggle cyc = PlusMenus.toggle("w3animtools_clickcycle", "cpmplus.w3animtools.aids.cycle.menu", "flip_to_back", Cond.CPM_ONLY, ViewAids.isCycle(), ViewAids::setCycle);
		for (com.tom.cpm.blockbench.proxy.Action.Toggle tg : new com.tom.cpm.blockbench.proxy.Action.Toggle[] {stepped, sil, cyc}) {
			PlusMenus.add(PlusMenus.SUB_ANIM, tg);
			PlusMenus.add(PlusMenus.SUB_VIEW, tg);
		}
		Action tn = PlusMenus.action("cpmplus_w3animtools_tangents", "cpmplus.w3animtools.aids.tangents.menu", "show_chart", Cond.CPM_ONLY, inAnim, KeyAids::tangentsAction);
		Action sq = PlusMenus.action("cpmplus_w3animtools_squash", "cpmplus.w3animtools.aids.squash.menu", "unfold_less", Cond.CPM_ONLY, inAnim, KeyAids::squashDialog);
		Action pvt = PlusMenus.action("cpmplus_w3animtools_pivot", "cpmplus.w3animtools.aids.pivot.menu", "my_location", Cond.CPM_ONLY, inAnim, KeyAids::pivotDialog);
		Action tk = PlusMenus.action("cpmplus_w3animtools_takes", "cpmplus.w3animtools.takes.menu", "collections_bookmark", Cond.CPM_ONLY, inAnim, Takes::openDialog);
		for (Action a : new Action[] {tn, sq, pvt, tk})PlusMenus.add(PlusMenus.SUB_ANIM, a);
		PlusMenus.addToContext(protoMenu("BoneAnimator"), sq, "");
		PlusMenus.addToContext(protoMenu("Group"), pvt, "");
		PlusMenus.addToContext(protoMenu("Animation"), tk, "");
		// beginner kits: recipes, walk / run generator, face kit, test in CPM's editor, 'saw it in a video' help
		Action rcp = PlusMenus.action("cpmplus_w3animtools_recipe", "cpmplus.w3animtools.recipe.menu", "auto_awesome", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), SimpleKits::recipeDialog);
		Action gt = PlusMenus.action("cpmplus_w3animtools_gait", "cpmplus.w3animtools.gait.menu", "directions_walk", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), SimpleKits::gaitDialog);
		Action fc = PlusMenus.action("cpmplus_w3animtools_face", "cpmplus.w3animtools.face.menu", "face", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), SimpleKits::faceDialog);
		Action tc = PlusMenus.action("cpmplus_w3animtools_testcpm", "cpmplus.w3animtools.testcpm.menu", "play_circle", Cond.CPM_ONLY, inAnim, TestInCpm::open);
		Action vh = PlusMenus.action("cpmplus_w3animtools_video", "cpmplus.w3animtools.video.menu", "ondemand_video", Cond.CPM_ONLY, () -> CPMCodec.isCPM(), VideoHelp::open);
		for (Action a : new Action[] {rcp, gt, fc, tc, vh})PlusMenus.add(PlusMenus.SUB_ANIM, a);
		PlusMenus.addToContext(protoMenu("Animation"), tc, "");
		// trail of the TIP of each selected part (Blockbench's own trail follows the pivot only)
		TipTrail.install();
		com.tom.cpm.blockbench.proxy.Action.Toggle trail = PlusMenus.toggle("w3animtools_tiptrail", "cpmplus.w3animtools.trail.menu", "gesture", Cond.CPM_ONLY, TipTrail.isOn(), TipTrail::set);
		PlusMenus.add(PlusMenus.SUB_ANIM, trail);
		PlusMenus.add(PlusMenus.SUB_VIEW, trail);
		ExportHooks.register(ctx -> {
			java.util.List<Transitions.Jump> js = Transitions.report(45, 6);
			if (js.isEmpty())return;
			Transitions.Jump j = js.get(0);
			WarnEntry w = WarnFactory.of(Severity.INFO, "poseJumps", "cpmplus.w3animtools.trans.warn.export", js.size(), j.g.name,
					com.tom.cpm.blockbench.util.PlusDialogs.tr("label.cpm.pose." + j.from.name().toLowerCase(java.util.Locale.ROOT)),
					com.tom.cpm.blockbench.util.PlusDialogs.tr("label.cpm.pose." + j.to.name().toLowerCase(java.util.Locale.ROOT)),
					Transitions.worstText(j));
			w.setFix(() -> {
				Transitions.openReport();
				return Promise.resolve(false);
			}, FixKind.VIEW_ONLY);
			ctx.add(w);
		});

		if (PlusTestHooks.active()) {
			PlusTestHooks.register("w3animtools.f14reverse", QuickAnim::hookReverse);
			PlusTestHooks.register("w3animtools.f14interp", QuickAnim::hookInterp);
			PlusTestHooks.register("w3animtools.f14items", QuickAnim::hookItems);
			PlusTestHooks.register("w3animtools.f14native", QuickAnim::hookNative);
			PlusTestHooks.register("w3animtools.f17", Exaggerate::hook);
			PlusTestHooks.register("w3animtools.f23", PoseColumn::hook);
			PlusTestHooks.register("w3animtools.pose", PoseClipboard::hook);
			PlusTestHooks.register("w3animtools.tween", Tween::hook);
			PlusTestHooks.register("w3animtools.physics", Physics::hook);
			PlusTestHooks.register("w3animtools.relAdjust", RelativeAdjust::hook);
			PlusTestHooks.register("w3animtools.correction", CorrectionLayer::hook);
			PlusTestHooks.register("w3animtools.transitions", Transitions::hook);
			PlusTestHooks.register("w3animtools.batchKeys", BatchKeys::hook);
			PlusTestHooks.register("w3animtools.tipTrail", TipTrail::hook);
			PlusTestHooks.register("w3animtools.motionCheck", MotionCheck::hook);
			PlusTestHooks.register("w3animtools.emoteImport", EmoteImport::hook);
			PlusTestHooks.register("w3animtools.clipInsert", ClipInsert::hook);
			PlusTestHooks.register("w3animtools.viewAids", ViewAids::hook);
			PlusTestHooks.register("w3animtools.keyAids", KeyAids::hook);
			PlusTestHooks.register("w3animtools.takes", Takes::hook);
			PlusTestHooks.register("w3animtools.kits", SimpleKits::hook);
			PlusTestHooks.register("w3animtools.testInCpm", TestInCpm::hook);
			PlusTestHooks.register("w3animtools.videoHelp", VideoHelp::hook);
			PlusTestHooks.register("w3animtools.open", o -> {
				Object which = o.get("tool");
				if ("f17".equals(which))Exaggerate.openDialog();
				else if ("f23".equals(which))PoseColumn.openDialog();
				else if ("tween".equals(which))Tween.openDialog();
				else if ("physics".equals(which))Physics.openDialog();
				else if ("reladj".equals(which))RelativeAdjust.openDialog();
				else if ("corrStrength".equals(which))CorrectionLayer.strengthDialog();
				else if ("corrMerge".equals(which))CorrectionLayer.mergeDialog();
				else if ("trans".equals(which))Transitions.openDialog();
				else if ("transReport".equals(which))Transitions.openReport();
				else if ("batch".equals(which))BatchKeys.openDialog();
				return o;
			});
		}
	}

	public static void uninstall() {
		TipTrail.uninstall();
		ViewAids.uninstall();
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
