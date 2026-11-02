package com.tom.cpm.blockbench.tools.vis;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.tom.cpm.blockbench.proxy.Animation;

/**
 * CPM+ (W5-VIS): priority helper of the visibility tools (Convention 10: byte range -128..127, refuse instead of
 * wrapping). A new reaction / form / show-hide must win over every animation that touches its parts, so it gets
 * max + 1 (at least atLeast). When that would be above 127 it is refused (no tie, no silent renumbering of the user's
 * animations).
 */
public final class VisPriority {
	private VisPriority() {
	}

	/** -128..127 (CPM stores the priority as a byte) */
	public static int clampByte(int v) {
		return v < -128 ? -128 : v > 127 ? 127 : v;
	}

	/** result of {@link #nextPriority}: the value to use (only when ok) */
	public static final class PriorityPlan {
		public final boolean ok;
		public final int value;
		/** kept for callers that put renumbered animations in their undo scope: always empty (no renumbering) */
		public final Map<Animation, Integer> renumber = new LinkedHashMap<>();

		PriorityPlan(boolean ok, int value) {
			this.ok = ok;
			this.value = value;
		}

		public void applyRenumber() {
		}
	}

	public static PriorityPlan nextPriority(Collection<Animation> touching) {
		return nextPriority(touching, Integer.MIN_VALUE);
	}

	/**
	 * A priority higher than every animation in touching (max + 1, at least atLeast). !ok when it would be above 127
	 * (another animation on these parts already uses 127: a tie would let it win in game).
	 */
	public static PriorityPlan nextPriority(Collection<Animation> touching, int atLeast) {
		int max = Integer.MIN_VALUE;
		if (touching != null)for (Animation a : touching)max = Math.max(max, a.getPriority());
		int want;
		if (max == Integer.MIN_VALUE)want = atLeast == Integer.MIN_VALUE ? 0 : atLeast;
		else want = atLeast == Integer.MIN_VALUE ? max + 1 : Math.max(max + 1, atLeast);
		if (want < -128)want = -128;
		if (want > 127)return new PriorityPlan(false, 127);
		return new PriorityPlan(true, want);
	}
}
