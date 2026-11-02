package com.tom.cpm.blockbench.convert;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Timeline;

import elemental2.dom.DomGlobal;

/**
 * CPM+ (G9): the one way Blockbench animations are sampled outside the preview (the export's frames and the
 * 'Transformar fórmulas em keyframes' tool use the same steps, so a converted channel equals what the export writes).
 * <ul>
 * <li>Blockbench evaluates a Molang formula with {@code query.anim_time = this.animator.animation.time}, a getter
 * computed from {@link Timeline#time} and the animation's loop mode (loop / hold / once). So the sample time is put
 * on the timeline directly ({@link #setTime}; no redraw, unlike Timeline.setTime) and the formula sees exactly what
 * the preview would show at that playhead.</li>
 * <li>{@code this} in a formula reads {@code Animator._last_values}: cleared before every sample, so nothing leaks
 * between frames, parts or animations (two exports in a row are identical).</li>
 * <li>Molang variables are reset once per animation ({@link #beginAnimation}), like Blockbench's Animation.select.</li>
 * <li>{@link #finish} after a batch: 'this' values cleared again and, in Animate mode, the preview redrawn at the
 * restored playhead. Sampling never moves a mesh, so Edit mode needs nothing.</li>
 * </ul>
 * Numeric keyframes are not affected by any of this (the cleared values only feed {@code this} and variables).
 */
public final class AnimSampler {

	private AnimSampler() {
	}

	/** before the samples of one animation: Molang variables reset (like Blockbench's Animation.select) */
	public static void beginAnimation() {
		resetVariables();
	}

	/** evaluation time = t (Timeline.time, no redraw) and every channel's 'this' value cleared */
	public static void setTime(float t) {
		Timeline.time = t;
		resetLastValues();
	}

	/**
	 * After a batch of samples (the caller already restored Timeline.time): 'this' values cleared and, in Animate
	 * mode with an animation selected, the preview shown again at the restored playhead.
	 */
	public static void finish() {
		resetLastValues();
		refreshPreview();
	}

	/**
	 * Runs body with anim as the sampled animation; the playhead and the selected animation are restored afterwards
	 * (also when body throws) and {@link #finish} runs.
	 */
	public static void sampling(Animation anim, Runnable body) {
		float saveTime = Timeline.time;
		Animation saveSel = Animation.selected;
		try {
			Animation.selected = anim;
			beginAnimation();
			body.run();
		} finally {
			Timeline.time = saveTime;
			Animation.selected = saveSel;
			finish();
		}
	}

	private static native void resetLastValues() /*-{
		try {
			var A = $wnd.Animator;
			if (A && typeof A.resetLastValues == 'function') A.resetLastValues();
		} catch (e) {}
	}-*/;

	private static native void resetVariables() /*-{
		try {
			var A = $wnd.Animator;
			if (A && A.MolangParser && typeof A.MolangParser.resetVariables == 'function') A.MolangParser.resetVariables();
		} catch (e) {}
	}-*/;

	private static void refreshPreview() {
		try {
			refreshPreview0();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not refresh the animation preview", e);
		}
	}

	private static native void refreshPreview0() /*-{
		var M = $wnd.Modes, A = $wnd.Animator, An = $wnd.Animation;
		if (M && M.animate && An && An.selected && A && typeof A.preview == 'function') A.preview();
	}-*/;
}
