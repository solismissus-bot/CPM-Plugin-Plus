package com.tom.cpm.blockbench;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.format.AnimationWizard;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.web.client.java.JsBuilder;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

/**
 * CPM+ : the 'CPM+' menu (quality-of-life tools). Everything here only reads/changes Blockbench
 * data through public Blockbench APIs and is wrapped so a failure never breaks the plugin.
 */
public class PlusTools {
	public static final String MENU_ID = "cpm_plus_tools";
	private static final String[] KEY_CHANNELS = {"position", "rotation", "scale", CPMCodec.COLOR};

	/**
	 * CPM+ (W0B): the CPM+ menu itself is built by PlusMenus (submenus Ajustes do modelo / Animação / Modelagem /
	 * Textura / Otimizar / Prévia / Ajuda); the existing tools keep their action ids (keybinds survive).
	 */
	public static void load() {
		// every CPM+ tool is CPM-only (they are also reachable through action search / keybinds,
		// not just the CPM+ menu, so the menu's condition is not enough)
		PlusMenus.add(PlusMenus.TOP, PlusMenus.action(BBActions.ID + "validate", "cpmplus.w0binfra.tool.validate", "fact_check",
				Cond.CPM_ONLY, null, () -> validate()));

		if (BBActions.reexportAction != null)PlusMenus.add(PlusMenus.TOP, BBActions.reexportAction);

		PlusMenus.add(PlusMenus.SUB_ANIM, PlusMenus.action(BBActions.ID + "anim_properties", "cpmplus.w0binfra.tool.animProps", "list",
				Cond.CPM_ONLY, () -> Animation.selected != null, () -> {
					if (Animation.selected != null)AnimationWizard.open(Animation.selected, null);
				}));

		PlusMenus.add(PlusMenus.SUB_ANIM, PlusMenus.action(BBActions.ID + "fix_interp", "cpmplus.w0binfra.tool.fixInterp", "timeline",
				Cond.CPM_ONLY, () -> Animation.all != null && Animation.all.length > 0, () -> fixInterpolationDialog()));

		// CPM+ : one-shot, undoable action (was a global toggle whose state didn't follow the selected
		// animation, stayed on after a failed enable and changed snapping without undo). Ctrl+Z restores.
		PlusMenus.add(PlusMenus.SUB_ANIM, PlusMenus.action(BBActions.ID + "cpm_frame_grid", "cpmplus.w0binfra.tool.cpmGrid", "grid_on",
				Cond.CPM_ONLY, () -> Animation.selected != null, () -> setCpmGrid()));

		PlusMenus.add(PlusMenus.SUB_HELP, PlusMenus.action(BBActions.ID + "about", "cpmplus.w0binfra.tool.about", "info",
				Cond.CPM_ONLY, null, () -> showAbout()));
	}

	// ---------------------------------------------------------------- validate

	/** Runs the same conversion + warning pass as export, but writes nothing. */
	private static void validate() {
		Editor editor = new Editor();
		editor.setUI(new BBUI());
		editor.reinit();
		boolean[] shown = new boolean[1];
		// CPM+ (W0B): async flow without a codec export: the QA harness waits while it runs
		PlusTestHooks.track(PopupDialogs.runTaskWithWarning(w -> ProjectConvert.prepExport(editor, w), () -> shown[0] = true)).then(__ -> {
			if (!shown[0])PopupDialogs.displayText("Validation passed: no problems found. The model can be exported.");
			return null;
		}).catch_(err -> {
			PopupDialogs.displayError(I18n.get("bb-label.error.export"), err);
			return null;
		});
	}

	// ---------------------------------------------------------------- interpolation tool

	private static void fixInterpolationDialog() {
		Dialog.DialogProperties dctr = new Dialog.DialogProperties();
		dctr.id = "cpmplus_fix_interp";
		dctr.title = "Fix animation interpolation";
		Dialog.FormSelectElement mode = Dialog.FormSelectElement.make("Interpolation");
		mode.options = new JsBuilder<>().
				put("catmullrom", "Smooth (CPM Polynomial)").
				put("linear", "Linear (CPM Linear)").
				put("step", "Step (CPM No Interpolation)").
				build();
		mode.value = "catmullrom";
		Dialog.FormSelectElement scope = Dialog.FormSelectElement.make("Apply to");
		scope.options = new JsBuilder<>().
				put("selected", "Selected animation").
				put("all", "All animations").
				build();
		scope.value = Animation.selected != null ? "selected" : "all";
		Dialog.FormInfoElement info = Dialog.FormInfoElement.make("", "Changes position, rotation, scale and CPM colour keyframes (bezier keys too). Visibility keys always stay 'step'. CPM interpolates a whole animation with one type, so mixing types makes the export resample the animation.", null);
		dctr.form = new JsBuilder<>().
				put("mode", mode).
				put("scope", scope).
				put("info", info).
				build();
		dctr.onConfirm = rIn -> {
			InterpResult r = Js.uncheckedCast(rIn);
			int n = applyInterpolation(r.mode, "all".equals(r.scope));
			PopupDialogs.displayText("Changed " + n + " keyframe(s) to '" + r.mode + "'.");
			return true;
		};
		new Dialog(dctr).show();
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	private static class InterpResult {
		public String mode, scope;
	}

	public static int applyInterpolation(String interp, boolean all) {
		List<Animation> anims = new ArrayList<>();
		if (all) {
			for (Animation a : Animation.all)anims.add(a);
		} else if (Animation.selected != null) {
			anims.add(Animation.selected);
		}
		// CPM+ : only animations that actually change take part in the undo entry
		List<Animation> touched = new ArrayList<>();
		List<Keyframe> kfs = new ArrayList<>();
		for (Animation a : anims) {
			List<Keyframe> ak = new ArrayList<>();
			collectKeys(a, ak);
			boolean any = false;
			for (Keyframe kf : ak)if (!interp.equals(kf.interpolation)) { any = true; break; }
			if (any) {
				touched.add(a);
				kfs.addAll(ak);
			}
		}
		if (kfs.isEmpty())return 0;
		// CPM+ : undo through the 'animations' aspect (whole-animation snapshots incl. keyframes).
		// The 'keyframes' aspect is bound to Animation.selected and the timeline's animators, so with
		// 'All animations' it pasted other animations' keys into the selected one on Ctrl+Z, and with
		// no selected animation it recorded nothing.
		Animation[] arr = touched.toArray(new Animation[0]);
		Undo.initEdit(UndoData.make(arr));
		int changed = 0;
		for (Keyframe kf : kfs) {
			if (!interp.equals(kf.interpolation)) {
				kf.interpolation = interp;
				changed++;
			}
		}
		Undo.finishEdit("Set keyframe interpolation", UndoData.make(arr));
		try {
			Animator.preview();
		} catch (Throwable e) {
		}
		return changed;
	}

	private static void collectKeys(Animation a, List<Keyframe> out) {
		if (a == null || a.animators == null)return;
		a.animators.forEach(k -> {
			GeneralAnimator ga = a.animators.get(k);
			if (!(ga instanceof BoneAnimator))return;
			for (String ch : KEY_CHANNELS) {
				Object arr = Js.asPropertyMap(ga).get(ch);
				if (arr == null || !"object".equals(Js.typeof(arr)))continue;
				JsArrayE<Keyframe> keys = Js.uncheckedCast(arr);
				for (int i = 0; i < keys.length; i++)out.add(keys.getAt(i));
			}
		});
	}

	// ---------------------------------------------------------------- CPM frame grid

	private static void setCpmGrid() {
		Animation a = Animation.selected;
		if (a == null)return;
		int fps = cpmFrameRate(a);
		if (fps <= 0) {
			PopupDialogs.displayText("Could not determine a CPM frame grid for '" + a.name + "' (no CPM frame data and no keyframes).");
			return;
		}
		int old = a.snapping;
		if (old == fps) {
			PopupDialogs.displayText("Timeline snapping of '" + a.name + "' is already " + fps + " steps/s (one step = one CPM frame).");
			return;
		}
		Undo.initEdit(UndoData.make(a));
		a.snapping = fps;
		Undo.finishEdit("Snap timeline to CPM frame grid", UndoData.make(a));
		PopupDialogs.displayText("Timeline snapping of '" + a.name + "' set to " + fps + " steps/s (one step = one CPM frame, was " + old + "). Undo restores it.");
	}

	/** Frame rate of the CPM frame grid: stored CPM data first, otherwise the keyframe times. */
	public static int cpmFrameRate(Animation a) {
		int n = a.getCpmFrames();
		int d = a.getCpmDuration();
		InterpolatorType it = a.getCpmIntType();
		if (n > 0 && d > 0) {
			boolean single = n > 1 && (it == InterpolatorType.LINEAR_SINGLE || it == InterpolatorType.TRIG_SINGLE);
			float step = single ? (d / 1000f) / (n - 1) : (d / 1000f) / n;
			return com.tom.cpm.blockbench.convert.BlockbenchImport.pickSnapping(step);
		}
		List<Keyframe> kfs = new ArrayList<>();
		collectKeys(a, kfs);
		TreeSet<Float> times = new TreeSet<>();
		for (Keyframe k : kfs)times.add(k.time);
		if (times.size() < 2)return times.isEmpty() ? 0 : 20;
		for (int s = 1; s <= 500; s++) {
			boolean ok = true;
			for (Float t : times) {
				double v = t * (double) s;
				if (Math.abs(v - Math.round(v)) > 1e-3) {
					ok = false;
					break;
				}
			}
			if (ok)return s;
		}
		return 0;
	}

	// ---------------------------------------------------------------- about

	/** About dialog (also the 'Atualizar' button of the newer-file notice) */
	public static void showAbout() {
		Dialog.DialogProperties dctr = new Dialog.DialogProperties();
		dctr.id = "cpmplus_about";
		dctr.title = "About " + PlusInfo.SHORT_NAME;
		dctr.width = 700;
		dctr.setHtml(PlusInfo.aboutHtml());
		dctr.singleButton = true;
		new Dialog(dctr).show();
	}
}
