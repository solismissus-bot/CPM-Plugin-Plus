package com.tom.cpm.blockbench.tools.particles;

import com.tom.cpm.blockbench.proxy.Animation;

/**
 * CPM+ (particles): the frame grid the export will write for an existing animation, so generated keys can be put on
 * that grid ('junto com a animação'). Private to the particle generator (the shared animation core may have its own
 * helper; this one never conflicts with it).
 */
public final class ParticleFrames {
	private ParticleFrames() {
	}

	/** frames / duration the export will write for an animation */
	public static final class FramePrediction {
		public final int frames, durationMs;
		public final boolean ok;

		public FramePrediction(int frames, int durationMs) {
			this.frames = frames;
			this.durationMs = durationMs;
			this.ok = frames <= ParticleMath.MAX_FRAMES && durationMs <= ParticleMath.MAX_DURATION_MS;
		}
	}

	/**
	 * Frames the export writes for anim with this snapping and length: the stored CPM grid (cpm_frames) when the
	 * animation came from CPM and the length still matches, else round(length * snapping) (at least 1).
	 */
	public static FramePrediction predictFrames(Animation anim, int snapping, double length) {
		int stored = anim != null ? anim.getCpmFrames() : 0;
		int sd = anim != null ? anim.getCpmDuration() : 0;
		int durMs = (int) Math.round(Math.max(0, length) * 1000);
		if (stored >= 1 && anim.getCpmIntType() != null && (sd <= 0 || Math.abs(sd - durMs) < 2)) {
			return new FramePrediction(stored, sd > 0 ? sd : durMs);
		}
		int s = snapping > 0 ? snapping : 20;
		return new FramePrediction(Math.max(1, (int) Math.round(length * s)), durMs);
	}
}
