package com.tom.cpm.blockbench.tools.rig;

import java.util.LinkedHashMap;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.AnimxUtil;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;

import elemental2.dom.DomGlobal;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ beta34 rig tools: live viewport preview while a rig dialog is open (Animate mode).
 * <ul>
 * <li>The previewed pose lives in a TEMPORARY animation (one key at time 0 per part and channel, playing): Blockbench
 * shows it through its own pipeline (rotation / position / scale / CPM visibility) on top of whatever else plays.
 * It is added without undo and removed when the dialog closes, so it never reaches the history or an export.</li>
 * <li>For the head yaw / pitch drivers the vanilla head is turned like the game turns it (Animator.preview fires
 * 'display_animation_frame' at its end; the turn is added there, so every redraw keeps it). Blockbench resets the
 * meshes from their rest pose on every preview, so closing restores the head by itself.</li>
 * <li>An animation that would show twice (the one being edited) stops playing during the preview and plays again
 * afterwards.</li>
 * </ul>
 */
public final class RigPreview {
	private static RigPreview active;

	private Animation temp;
	private Animation muted;
	private Object mutedPlaying;
	private double headX, headY;
	private boolean headOn;

	private RigPreview() {
	}

	public static RigPreview start(Animation muteWhilePreviewing) {
		stopActive();
		RigPreview p = new RigPreview();
		if (muteWhilePreviewing != null) {
			p.muted = muteWhilePreviewing;
			p.mutedPlaying = Js.asPropertyMap(muteWhilePreviewing).get("playing");
			Js.asPropertyMap(muteWhilePreviewing).set("playing", false);
		}
		active = p;
		return p;
	}

	public static boolean isActive() {
		return active != null;
	}

	public static void stopActive() {
		if (active != null)active.stop();
	}

	/** one part's previewed offsets (Blockbench key values; null channel = untouched) */
	public static final class Pose {
		public double[] rot, pos, scl;
		public Boolean vis;
	}

	/**
	 * Shows the poses and the head turn (Blockbench degrees added to the vanilla head: x = look up / down, y = look
	 * left / right; NaN = no turn).
	 */
	public void show(Map<Group, Pose> poses, double headXDeg, double headYDeg) {
		if (active != this)return;
		try {
			ensureTemp();
			for (BoneAnimator ba : AnimxUtil.boneAnimators(temp))for (String ch : AnimxUtil.ALL)AnimEdit.clearChannel(ba, ch);
			for (Map.Entry<Group, Pose> e : poses.entrySet()) {
				Group g = e.getKey();
				Pose p = e.getValue();
				if (g == null || p == null)continue;
				BoneAnimator ba = temp.getBoneAnimator(g);
				if (ba == null)continue;
				if (p.rot != null)AnimxUtil.addKey(ba, AnimxUtil.keyData(KeyMath.ROT, 0, "linear", p.rot[0], p.rot[1], p.rot[2]));
				if (p.pos != null)AnimxUtil.addKey(ba, AnimxUtil.keyData(KeyMath.POS, 0, "linear", p.pos[0], p.pos[1], p.pos[2]));
				if (p.scl != null)AnimxUtil.addKey(ba, AnimxUtil.keyData(KeyMath.SCALE, 0, "linear", p.scl[0], p.scl[1], p.scl[2]));
				if (p.vis != null)AnimxUtil.addKey(ba, AnimxUtil.visKeyData(0, p.vis));
			}
			Js.asPropertyMap(temp).set("playing", true);
			headOn = !Double.isNaN(headXDeg) || !Double.isNaN(headYDeg);
			headX = Double.isNaN(headXDeg) ? 0 : headXDeg;
			headY = Double.isNaN(headYDeg) ? 0 : headYDeg;
			if (AnimToolSupport.animateMode())Animator.preview();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: rig preview failed", e);
		}
	}

	private void ensureTemp() {
		if (temp != null && AnimEdit.all().contains(temp))return;
		Animation a = new Animation();
		JsPropertyMap<Object> d = JsPropertyMap.of();
		d.set("name", "~ " + com.tom.cpm.blockbench.util.PlusDialogs.tr("cpmplus.rigpro.previewName"));
		d.set("loop", "loop");
		d.set("length", 1.0);
		d.set("snapping", 20.0);
		a.extend(d);
		temp = a.add(false);
		temp.type = "global";
		temp.additive = true;
	}

	/** Blockbench redrew the animated pose (display_animation_frame): turn the vanilla head again */
	public static void onFrame() {
		RigPreview p = active;
		if (p == null || !p.headOn)return;
		try {
			Group head = AnimxUtil.groupByUuidOrName("head");
			if (head == null || head.parent != Group.ROOT)head = rootHead();
			if (head != null)addRot(head.mesh, Math.toRadians(p.headX), Math.toRadians(p.headY), 0);
		} catch (Throwable e) {
		}
	}

	private static Group rootHead() {
		for (Group g : AnimxUtil.vanillaRoots())if (AnimxUtil.isHead(g))return g;
		return null;
	}

	private static native void addRot(Object mesh, double x, double y, double z) /*-{
		if (!mesh || !mesh.rotation) return;
		mesh.rotation.x += x;
		mesh.rotation.y += y;
		mesh.rotation.z += z;
	}-*/;

	/** removes the temporary animation, plays the muted one again, redraws (the head goes back to rest) */
	public void stop() {
		if (active == this)active = null;
		headOn = false;
		try {
			if (temp != null) {
				Animation t = temp;
				temp = null;
				if (AnimEdit.all().contains(t))t.remove(false, false);
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not remove the rig preview", e);
		}
		if (muted != null) {
			Js.asPropertyMap(muted).set("playing", mutedPlaying);
			muted = null;
		}
		AnimToolSupport.refreshPreview();
	}

	/** the temporary animation (QA) */
	public Animation temp() {
		return temp;
	}

	/** test hook data */
	public static Map<String, Object> state() {
		Map<String, Object> m = new LinkedHashMap<>();
		RigPreview p = active;
		m.put("active", p != null);
		m.put("temp", p != null && p.temp != null ? p.temp.name : null);
		m.put("headOn", p != null && p.headOn);
		m.put("headX", p != null ? p.headX : 0.0);
		m.put("headY", p != null ? p.headY : 0.0);
		return m;
	}
}
