package com.tom.cpm.blockbench.tools.export;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.shared.animation.InterpolatorChannel;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.util.ExportHelper;

/**
 * CPM+ (G10): the game's format limits, checked on the CONVERTED model (values relative to the parent, exactly what
 * CPM writes), never blocking. The normal export of the mod (Exporter V1) writes
 * <ul>
 * <li>cube size with IOHelper.writeVec3ub: clamp((int) (v * 10), 0, 255) / 10 -> at most 25.5 px, negative -> 0;</li>
 * <li>cube / pivot position and offset, root offsets, animation position and scale with writeVec6b / writeFloat2:
 * clamp((int) (v * DIV), -32768, 32767) / DIV with DIV = 32767 / 48 -> about +-48 px.</li>
 * </ul>
 * Blockbench shows the full value, the game silently cuts it. Each row says which part, the value and what the game
 * receives. Rotation is written with a wrap (no limit).
 */
public final class FormatLimitsCheck {
	public static final int DIV = Short.MAX_VALUE / 48;
	public static final float MAX_SIZE = 25.5f;
	private static final int MAX_ROWS = 25;

	private FormatLimitsCheck() {
	}

	/** what writeVec3ub stores for a size */
	public static float gameSize(float v) {
		return Math.max(0, Math.min(255, (int) (v * 10))) / 10f;
	}

	/** what writeFloat2 stores for a position / offset / animation value */
	public static float gamePos(float v) {
		return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (v * DIV))) / (float) DIV;
	}

	/** true when writeFloat2 cuts the value (more than rounding) */
	public static boolean posCut(float v) {
		return Math.abs(gamePos(v) - v) > 0.01f;
	}

	/** the CPM editor's own input limit for size and mesh scale (FormatLimits.getSizeLimit()) */
	private static final int EDITOR_LIMIT = 25;

	/**
	 * A cube longer than {@link #MAX_SIZE} on an axis goes to the game as size x mesh scale (the CPM editor's
	 * 'Scale'): size n/10 <= 25 and scale m/DIV, values the game stores without cutting, with size * scale within
	 * 0.002 px of the real length. BoxRender draws offset .. offset + size * meshScale and per-face UVs do not depend
	 * on the size, so the cube looks the same in game; its pivot, offset and animations do not change. Box UV,
	 * single texture and extruded cubes take their UV from the size: the caller exports them per face / skips them.
	 * @return true when the size / mesh scale of me changed
	 */
	public static boolean fitSize(ModelElement me) {
		float[] s = {me.size.x, me.size.y, me.size.z};
		float[] c = {me.meshScale.x, me.meshScale.y, me.meshScale.z};
		boolean any = false;
		for (int i = 0; i < 3; i++) {
			if (s[i] <= MAX_SIZE)continue;
			double[] f = fit(s[i] * (double) c[i]);
			if (f == null)continue;
			s[i] = (float) f[0];
			c[i] = (float) f[1];
			any = true;
		}
		if (any) {
			me.size = new Vec3f(s[0], s[1], s[2]);
			me.meshScale = new Vec3f(c[0], c[1], c[2]);
		}
		return any;
	}

	/** close enough for size * scale: 50 times under the 0.1 px CPM rounds every cube size to */
	private static final double FIT_TOLERANCE = 0.002;

	/**
	 * {size, scale} for a length (see {@link #fitSize}): the biggest size within {@link #FIT_TOLERANCE} (values that
	 * still read naturally in the CPM editor, scale about 1.1 - 2.2), else the closest; null when longer than 25 x 25 px
	 */
	static double[] fit(double len) {
		double[] best = null;
		double bestErr = Double.MAX_VALUE;
		for (int n = EDITOR_LIMIT * 10; n >= 10; n--) {
			if (!exact(n, 10))continue;
			int m0 = (int) (len * 10 * DIV / n);
			for (int m = m0; m <= m0 + 1; m++) {
				if (m <= DIV || m > EDITOR_LIMIT * DIV || !exact(m, DIV))continue;
				double s = n / 10.0, c = m / (double) DIV, err = Math.abs(s * c - len);
				if (err < bestErr) {
					best = new double[] {s, c};
					bestErr = err;
				}
			}
			if (bestErr < FIT_TOLERANCE)break;
		}
		return best;
	}

	/**
	 * k / div is read back as k by the game's (int) (v * div): in float on the JVM (CPM editor exporting the
	 * .cpmproject) and in double here (GWT exporting the .cpmmodel). About half of all m / 682 fail one of them.
	 */
	private static boolean exact(int k, int div) {
		double v = k / (double) div, f = f32(v);
		return (int) (v * div) == k && (int) f32(f * div) == k && (int) (f * div) == k;
	}

	/** a JVM float (GWT keeps floats as doubles) */
	private static native double f32(double v) /*-{
		return Math.fround(v);
	}-*/;

	static String fmt(double v) {
		double r = Math.round(v * 100) / 100.0;
		String s = String.valueOf(r);
		if (s.endsWith(".0"))s = s.substring(0, s.length() - 2);
		return s.replace(".", com.tom.cpm.web.client.util.I18n.get("cpmplus.w6save.limits.decimal"));// ',' in pt-BR, '.' in English
	}

	private static final String[] AXES = {"X", "Y", "Z"};

	private static float axis(Vec3f v, int i) {
		return i == 0 ? v.x : i == 1 ? v.y : v.z;
	}

	public static void check(ExportContext ctx) {
		Editor ed = ctx.editor;
		if (ed == null)return;
		Map<ModelElement, String> uuidOf = new HashMap<>();
		try {
			for (Map.Entry<String, ModelElement> e : ctx.export.getUuidLookup().entrySet())uuidOf.putIfAbsent(e.getValue(), e.getKey());
		} catch (Throwable e) {
		}
		int[] rows = {0, 0};
		// folders the 'Consertar' button can fix go into ONE row with that button, the rest keep a row each
		Set<String> fixable = FarPivotFix.fixable();
		List<String> fixU = new ArrayList<>(), fixN = new ArrayList<>();
		ExportHelper.walkElements(ed.elements, me -> {
			if (me.templateElement)return;
			String name = me.name != null ? me.name : "?";
			if (me.type == ElementType.NORMAL && me.size != null) {
				for (int i = 0; i < 3; i++) {
					float s = axis(me.size, i);
					if (s > MAX_SIZE + 1e-4f)add(ctx, rows, uuidOf.get(me), name, "limitSize", "cpmplus.w6save.limits.size", name, fmt(s), AXES[i], fmt(gameSize(s)));
					else if (s < -1e-4f)add(ctx, rows, uuidOf.get(me), name, "limitSize", "cpmplus.w6save.limits.negSize", name, AXES[i]);
				}
			}
			if ((me.type == ElementType.NORMAL || me.type == ElementType.ROOT_PART) && me.pos != null) {
				for (int i = 0; i < 3; i++) {
					float p = axis(me.pos, i);
					if (posCut(p)) {
						String u = uuidOf.get(me);
						if (u != null && fixable.contains(u)) {
							fixU.add(u);
							fixN.add(name);
						} else add(ctx, rows, u, name, "limitPos", "cpmplus.w6save.limits.pos", name, fmt(p), AXES[i], fmt(gamePos(p)));
						break;
					}
				}
			}
			if (me.type == ElementType.NORMAL && me.offset != null) {
				for (int i = 0; i < 3; i++) {
					float p = axis(me.offset, i);
					if (posCut(p)) {
						add(ctx, rows, uuidOf.get(me), name, "limitOffset", "cpmplus.w6save.limits.offset", name, fmt(p), AXES[i], fmt(gamePos(p)));
						break;
					}
				}
			}
		});
		if (ed.animations != null) {
			InterpolatorChannel[] pos = {InterpolatorChannel.POS_X, InterpolatorChannel.POS_Y, InterpolatorChannel.POS_Z};
			InterpolatorChannel[] scl = {InterpolatorChannel.SCALE_X, InterpolatorChannel.SCALE_Y, InterpolatorChannel.SCALE_Z};
			for (EditorAnim ea : ed.animations) {
				List<AnimFrame> fr = ea.getFrames();
				if (fr == null || fr.isEmpty())continue;
				for (ModelElement me : ea.getComponentsFiltered()) {
					float mp = worst(ea, me, pos), ms = worst(ea, me, scl);
					String an = ea.displayName != null ? ea.displayName : "?";
					String name = me.name != null ? me.name : "?";
					if (posCut(mp))add(ctx, rows, uuidOf.get(me), name, "limitAnim", "cpmplus.w6save.limits.anim", an, name, fmt(mp), "cpmplus.w6save.limits.what.pos", fmt(gamePos(mp)));
					if (posCut(ms))add(ctx, rows, uuidOf.get(me), name, "limitAnim", "cpmplus.w6save.limits.anim", an, name, fmt(ms), "cpmplus.w6save.limits.what.scale", fmt(gamePos(ms)));
				}
			}
		}
		if (!fixU.isEmpty())// first in the list: the one-click fix must not hide under the other rows
			ctx.add(WarnFactory.of(Severity.DIFFERS, FarPivotFix.KEY, "cpmplus.w6save.limits.posFix", fixU.size()).setNodes(fixU, fixN).setPriority(1));
		checkAnimSizes(ctx);
		if (rows[1] > 0)ctx.add(WarnFactory.of(Severity.DIFFERS, "limitMore", "cpmplus.w6save.limits.more", rows[1]));
	}

	// ------------------------------------------------------------------ G5: frames / duration / parts per animation

	/**
	 * ModelPartAnimation writes the part count and the frame count with write() (1 byte: 300 frames -> 44, the 256th
	 * part's motion lands on part 0) and the duration with writeShort (more than 32 767 ms turns negative). Value
	 * driven animations get their duration replaced by the mod (DYNAMIC_DURATION_DIV): no duration row for them.
	 */
	static void checkAnimSizes(ExportContext ctx) {
		Editor ed = ctx.editor;
		if (ed.animations == null)return;
		Map<EditorAnim, String> bbOf = new java.util.IdentityHashMap<>();
		try {
			for (Map.Entry<String, EditorAnim> e : ctx.export.getAnimMap().entrySet())bbOf.put(e.getValue(), e.getKey());
		} catch (Throwable e) {
		}
		for (EditorAnim ea : ed.animations) {
			String an = ea.displayName != null ? ea.displayName : "?";
			String bb = bbOf.get(ea);
			int frames = ea.getFrames() != null ? ea.getFrames().size() : 0;
			if (frames > 255) {
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, "limitFrames", "cpmplus.w6save.limits.frames", an, frames, frames & 255);
				w.setConsequence("cpmplus.w6save.limits.consequence");
				if (bb != null) {
					w.setFix(() -> fixFrames(bb), com.tom.cpm.blockbench.convert.WarnEntry.FixKind.ASKS);
					w.setFixPreview(com.tom.cpm.web.client.util.I18n.get("cpmplus.w6save.limits.fixFrames"));
				}
				ctx.add(w);
			}
			boolean dynamic = ea.type == com.tom.cpm.shared.animation.AnimationType.VALUE_LAYER ||
					(ea.pose instanceof com.tom.cpm.shared.animation.VanillaPose && ((com.tom.cpm.shared.animation.VanillaPose) ea.pose).hasStateGetter());
			if (!dynamic && ea.duration > Short.MAX_VALUE) {
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, "limitDuration", "cpmplus.w6save.limits.duration", an, fmt(ea.duration / 1000.0));
				w.setConsequence("cpmplus.w6save.limits.consequence");
				ctx.add(w);
			}
			int parts = ea.getComponentsFiltered().size();
			if (parts > 255) {
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, "limitParts", "cpmplus.w6save.limits.parts", an, parts);
				w.setConsequence("cpmplus.w6save.limits.consequence");
				ctx.add(w);
			}
		}
	}

	/**
	 * 'Ajustar': an animation re-sampled at its fps (curves, formulas, mixed interpolation) gets the highest fps that
	 * stays within 255 frames (one undo entry); an animation whose keys sit on a grid opens 'Otimizar animação' on it
	 * (the frames come from the keys there, not from the fps).
	 */
	public static elemental2.promise.Promise<Boolean> fixFrames(String bbUuid) {
		com.tom.cpm.blockbench.proxy.Animation a = com.tom.cpm.blockbench.tools.anim.AnimxUtil.find(bbUuid);
		if (a == null)return elemental2.promise.Promise.resolve(false);
		com.tom.cpm.blockbench.util.AnimEdit.Frames f = com.tom.cpm.blockbench.util.AnimEdit.predictFrames(a);
		if ("resample".equals(f.path)) {
			int cur = com.tom.cpm.blockbench.util.AnimEdit.snappingOf(a);
			int s = cur - 1;
			while (s >= 1 && com.tom.cpm.blockbench.util.AnimEdit.predictFrames(a, s, a.length).frames > 255)s--;
			if (s >= 1) {
				int fs = s;
				com.tom.cpm.blockbench.util.PlusUndo.editAnimations(java.util.Collections.singletonList(a), com.tom.cpm.web.client.util.I18n.get("cpmplus.w6save.limits.undoFps"), () -> a.snapping = fs);
				int nf = com.tom.cpm.blockbench.util.AnimEdit.predictFrames(a).frames;
				com.tom.cpm.blockbench.util.PlusDialogs.quick(com.tom.cpm.blockbench.util.PlusDialogs.trf("cpmplus.w6save.limits.fpsDone", a.name, cur, fs, nf), 6000);
				return elemental2.promise.Promise.resolve(true);
			}
		}
		try {
			a.select();
		} catch (Throwable e) {
		}
		com.tom.cpm.blockbench.tools.anim.AnimOptimizer.open();
		return elemental2.promise.Promise.resolve(false);
	}

	/** the value with the largest magnitude over every frame of the channels */
	private static float worst(EditorAnim ea, ModelElement me, InterpolatorChannel[] chs) {
		float w = 0;
		for (InterpolatorChannel ch : chs) {
			float[] a;
			try {
				a = AnimFrame.toArray(ea, me, ch);
			} catch (Throwable e) {
				continue;
			}
			if (a == null)continue;
			for (float v : a)if (Math.abs(v) > Math.abs(w))w = v;
		}
		return w;
	}

	private static void add(ExportContext ctx, int[] rows, String uuid, String name, String key, String msgKey, Object... args) {
		if (rows[0] >= MAX_ROWS) {
			rows[1]++;
			return;
		}
		rows[0]++;
		// 'cpmplus.limits.what.*' arguments are lang keys (position / scale)
		for (int i = 0; i < args.length; i++)if (args[i] instanceof String && ((String) args[i]).startsWith("cpmplus.w6save.limits.what."))args[i] = com.tom.cpm.web.client.util.I18n.get((String) args[i]);
		WarnEntry w = WarnFactory.of(Severity.DIFFERS, key, msgKey, args);
		w.setConsequence("cpmplus.w6save.limits.consequence");
		if (uuid != null)w.addNode(uuid, name);
		ctx.add(w);
	}
}
