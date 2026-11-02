package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

/**
 * CPM+ (W3-ANIMGEN, F22 'Criar seletor de cor…'): a slider in the gesture wheel that changes the colour of the chosen
 * parts. One looping VALUE_LAYER animation (length 1 s, maxValue = swatches - 1, default = the start colour) with one
 * cpm_color key per swatch on EVERY chosen part ({@link ColorSlider}). Colour keys only reach cubes that take a colour
 * in game (recolor on, or a colour-only cube): 'Ligar Recolorir' turns recolor on (white, the texture stays as it is)
 * on the direct cubes of the other parts in the same undo entry.
 */
public final class ColorSliderTool {
	/** recolor colour 'Ligar Recolorir' sets (white: the texture is not tinted until the slider moves) */
	public static final int RECOLOR_WHITE = 0xFFFFFF;

	private ColorSliderTool() {
	}

	public static final class Result {
		public Animation animation;
		public final List<Cube> recolored = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();
	}

	/** parts whose colour keys would reach no cube in game */
	public static List<Group> notRecolorable(List<Group> parts) {
		List<Group> out = new ArrayList<>();
		for (Group g : parts)if (!BlockbenchExport.isColourTarget(g))out.add(g);
		return out;
	}

	/** cubes of g that 'Ligar Recolorir' changes: exported, unrotated (or in a rotation subgroup), recolor off */
	public static List<Cube> recolorCandidates(Group g) {
		List<Cube> out = new ArrayList<>();
		for (Cube c : AnimGenBB.directCubes(g)) {
			if (!c.export)continue;
			if (!c.rotation.allEqual(0) && !g.is_rotation_subgroup)continue;
			if (c.getRecolor() == -1)out.add(c);
		}
		return out;
	}

	/** user reason (translated) why the slider can't be made, or null */
	public static String problem(List<Group> parts, int k, boolean enableRecolor) {
		if (parts == null || parts.isEmpty())return AnimGenBB.tr("cpmplus.w3animgen.color.err.noParts");
		if (k < ColorSlider.MIN || k > ColorSlider.MAX)return AnimGenBB.tr("cpmplus.w3animgen.color.err.count");
		for (Group g : parts)if (AnimGenBB.isRoot(g))return AnimGenBB.trf("cpmplus.w3animgen.color.err.root", g.name);
		List<Group> bad = notRecolorable(parts);
		if (!bad.isEmpty()) {
			if (!enableRecolor)return AnimGenBB.trf("cpmplus.w3animgen.color.err.recolor", names(bad));
			List<Group> none = new ArrayList<>();
			for (Group g : bad)if (recolorCandidates(g).isEmpty())none.add(g);
			if (!none.isEmpty())return AnimGenBB.trf("cpmplus.w3animgen.color.err.noCube", names(none));
		}
		return null;
	}

	static String names(List<Group> l) {
		List<String> n = new ArrayList<>();
		for (Group g : l)n.add(g.name);
		return String.join(", ", n);
	}

	/**
	 * Core (dialog and test hook): ONE undo entry creates the slider animation and, when enableRecolor, turns recolor
	 * on for the parts that need it. colors = k {r, g, b} (0..255); start = index of the start colour.
	 */
	public static Result apply(List<Group> parts, String name, List<int[]> colors, boolean smooth, int start, boolean enableRecolor) {
		int k = colors == null ? 0 : colors.size();
		String pr = problem(parts, k, enableRecolor);
		if (pr != null)throw new IllegalArgumentException(pr);
		Result r = new Result();
		for (Group g : notRecolorable(parts))r.recolored.addAll(recolorCandidates(g));
		OutlinerElement[] els = r.recolored.toArray(new OutlinerElement[0]);
		double[] times = ColorSlider.keyTimes(k, smooth);
		UndoData before = new UndoData();
		before.animations = new Animation[0];
		before.elements = els;
		PlusUndo.run(AnimGenBB.tr("cpmplus.w3animgen.color.undo"), before, () -> {
			UndoData a = new UndoData();
			a.animations = r.animation != null ? new Animation[] {r.animation} : new Animation[0];
			a.elements = els;
			return a;
		}, () -> {
			for (Cube c : r.recolored)c.setRecolor(RECOLOR_WHITE);
			String nm = name != null && !name.trim().isEmpty() ? name.trim() : AnimGenBB.tr("cpmplus.w3animgen.color.defaultName");
			Animation an = AnimGenBB.newAnimation(nm, "value_layer", true, ColorSlider.LENGTH, smooth ? k - 1 : k);
			an.setMaxValue(ColorSlider.maxValue(k));
			an.setInterpolateVal(smooth);
			an.setLayerDefault((float) ColorSlider.layerDefault(start, k));
			r.animation = an;
			for (Group g : parts) {
				BoneAnimator ba = AnimGenBB.animator(an, g);
				for (int i = 0; i < k; i++)AnimGenBB.addKey(ba, "cpm_color", times[i], smooth ? "linear" : "step", AnimGenBB.color(colors.get(i)));
			}
		});
		if (AnimGenBB.usesLayers(r.animation)) {
			LayerCapacity.Result lc = LayerCapacity.checkProject();
			if (!lc.ok())r.warnings.add(AnimGenBB.trf("cpmplus.w3animgen.common.layerCapacity", lc.count, lc.freeLayers));
		}
		return r;
	}

	// ------------------------------------------------------------------ dialog

	public static void open() {
		List<Group> parts = new ArrayList<>();
		for (Group g : AnimGenBB.selectedGroups())if (!AnimGenBB.isRoot(g))parts.add(g);
		if (parts.isEmpty()) {
			PlusDialogs.block(AnimGenBB.tr("cpmplus.w3animgen.color.err.noParts"));
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, AnimGenBB.trf("cpmplus.w3animgen.color.intro", names(parts))));
		rows.add(FormRow.text("name", "cpmplus.w3animgen.color.name", AnimGenBB.tr("cpmplus.w3animgen.color.defaultName")));
		rows.add(FormRow.number("count", "cpmplus.w3animgen.color.count", 3, ColorSlider.MIN, ColorSlider.MAX));
		for (int i = 0; i < ColorSlider.MAX; i++) {
			final int idx = i;
			rows.add(FormRow.color("c" + i, AnimGenBB.trf("cpmplus.w3animgen.color.swatch", i + 1), ColorSlider.defaultSwatch(i))
					.when(r -> r.getInt("count", 3) > idx));
		}
		rows.add(FormRow.checkbox("smooth", "cpmplus.w3animgen.color.smooth", false).description("cpmplus.w3animgen.color.smooth.desc"));
		rows.add(FormRow.number("start", "cpmplus.w3animgen.color.start", 1, 1, ColorSlider.MAX));
		rows.add(FormRow.info("hint", null, "cpmplus.w3animgen.color.hint"));
		FormOpts o = new FormOpts();
		o.confirmKey = "cpmplus.w3animgen.color.create";
		PlusDialogs.form("cpmplus_w3animgen_color", "cpmplus.w3animgen.color.title", rows, r -> {
			int k = Math.max(ColorSlider.MIN, Math.min(ColorSlider.MAX, r.getInt("count", 3)));
			List<int[]> cols = new ArrayList<>();
			for (int i = 0; i < k; i++) {
				int[] c = ColorSlider.parseHex(r.getColor("c" + i));
				cols.add(c != null ? c : ColorSlider.parseHex(ColorSlider.defaultSwatch(i)));
			}
			boolean smooth = r.getBool("smooth");
			int start = Math.max(1, Math.min(k, r.getInt("start", 1))) - 1;
			String name = r.getString("name");
			List<Group> bad = notRecolorable(parts);
			if (!bad.isEmpty()) {
				PlusDialogs.messageBox("cpmplus.w3animgen.color.title", AnimGenBB.trf("cpmplus.w3animgen.color.askRecolor", names(bad)),
						new String[] {"cpmplus.w3animgen.color.enableRecolor", "cpmplus.w0binfra.btn.cancel"}, b -> {
							if (b == 0)run(parts, name, cols, smooth, start, true);
						});
				return;
			}
			run(parts, name, cols, smooth, start, false);
		}, o);
	}

	private static void run(List<Group> parts, String name, List<int[]> cols, boolean smooth, int start, boolean recolor) {
		Result r;
		try {
			r = apply(parts, name, cols, smooth, start, recolor);
		} catch (IllegalArgumentException e) {
			PlusDialogs.block(e.getMessage());
			return;
		}
		AnimGenBB.showAnimation(r.animation);
		PlusDialogs.done(AnimGenBB.trf("cpmplus.w3animgen.color.done", r.animation.name, cols.size(), parts.size()));
		if (!r.warnings.isEmpty())PlusDialogs.info("cpmplus_w3animgen_color_note", "cpmplus.w3animgen.color.title", r.warnings);
	}
}
