package com.tom.cpm.blockbench.tools.effects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.ext.W5EffectsNatives;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W5-EFFECTS, G55) 'Virar cor sólida': a part painted with ONE opaque colour becomes a CPM colour cube (box UV,
 * no texture on any face, the colour in Recolorir). In game CPM draws the colour without reading the texture, exactly
 * like a colour cube made in CPM's own editor.
 * <ul>
 * <li>Detection is pure (nothing changes): every used texel of every face (UV rect scaled to the texture's real
 * size, HD aware, frame 0 of an animated texture) must have the same RGB and alpha 255. Never an average.</li>
 * <li>Exclusions (listed with the reason, never converted): extrude, Recolorir already on (the tint follows the
 * colour: conservative), a hidden face or a face without UV (a colour cube draws all 6 faces), faces with different
 * textures or a texture that is not the part's sheet, texture not loaded, semi-transparent texel, animated texture
 * whose frames differ, several colours (the context-menu action asks for the colour first).</li>
 * <li>Conversion: ONE undo entry for all cubes (box_uv, uv_offset, per-face UVs and textures restored exactly by
 * Ctrl+Z); glow, hidden, name, inflate, uuid and colour keys stay. Post-check per cube (isColorCube and the rgb),
 * a cube that fails is put back and reported.</li>
 * <li>The 'Recolorir' toggle on a textured cube now starts at the cube's dominant colour (fallback #7F7F7F) instead
 * of black (proxy Cube.toggleRecolor).</li>
 * <li>The export warning 'cube missing or using the wrong texture' gets the fix 'Virar cor sólida (escolher cor)'
 * for cubes without any texture (after the built-in fix).</li>
 * </ul>
 * Texture pixels are read once per texture and cached by texture uuid + a change counter (any edit, undo or redo).
 */
public final class SolidColor {
	public static final int FALLBACK = 0x7F7F7F;
	private static final String L = "cpmplus.w5effects.solid.";

	/** detection status of one cube */
	public enum Status {
		/** one opaque colour: converts as is */
		CANDIDATE,
		/** several opaque colours: converts only after the user picked the colour */
		MULTI,
		/** listed with the reason, never converted */
		EXCLUDED,
		/** already a colour cube: not listed */
		ALREADY
	}

	/** result of the analysis of one cube */
	public static final class Analysis {
		public final Cube cube;
		public Status status;
		/** lang key suffix of the reason (EXCLUDED) */
		public String reason;
		/** the colour (CANDIDATE), the dominant colour (MULTI), else -1 */
		public int rgb = -1;
		public int colors, pixels;

		Analysis(Cube cube) {
			this.cube = cube;
		}

		Analysis exclude(String reason) {
			this.status = Status.EXCLUDED;
			this.reason = reason;
			return this;
		}
	}

	private static int changeCounter;
	private static final Map<String, Object[]> texCache = new HashMap<>();

	private SolidColor() {
	}

	// ====================================================================== install

	public static void install() {
		for (String ev : new String[] {"finished_edit", "undo", "redo", "load_undo_save", "select_project", "update_texture_selection"}) {
			PluginStart.addEventListener(ev, __ -> invalidateCache());
		}
		Action find = PlusMenus.action("cpmplus_w5fx_solid_find", L + "menu.find", "palette", Cond.CPM_ONLY, SolidColor::openFinder);
		PlusMenus.add(PlusMenus.SUB_OPT, find);
		Action conv = PlusMenus.action("cpmplus_w5fx_solid_convert", L + "menu.convert", "format_color_fill", Cond.CPM_ONLY,
				() -> !selectedCubes().isEmpty(), SolidColor::convertSelection);
		Object menu = W5EffectsNatives.cubeMenu();
		if (menu != null)PlusMenus.addToContext(Js.<Menu>uncheckedCast(menu), conv, "");
		// export warning 'cube missing or using the wrong texture': cubes without any texture -> colour cubes
		WarnFixes.register("invalidTexture", L + "fix.invalidTexture", 50, w -> untexturedCubes().isEmpty() ? null : () -> fixUntextured());
	}

	public static void uninstall() {
		texCache.clear();
	}

	static void invalidateCache() {
		changeCounter++;
		texCache.clear();
	}

	// ====================================================================== detection

	private static List<Cube> selectedCubes() {
		List<Cube> out = new ArrayList<>();
		JsArray<Object> sel = W5EffectsNatives.selectedElements();
		for (int i = 0; i < sel.length; i++) {
			Object o = sel.getAt(i);
			if (W5EffectsNatives.isCube(o))out.add(Js.uncheckedCast(o));
		}
		return out;
	}

	private static List<Cube> allCubes() {
		List<Cube> out = new ArrayList<>();
		var cubesAll = Cube.all;// CPM+ : read once (Blockbench 5 filters every element on each read)
		if (cubesAll != null)for (int i = 0; i < cubesAll.length; i++)out.add(cubesAll.getAt(i));
		return out;
	}

	/** analyses every cube of the project (nothing changes) */
	public static List<Analysis> analyzeAll() {
		List<Analysis> out = new ArrayList<>();
		for (Cube c : allCubes())out.add(analyze(c));
		return out;
	}

	private static Texture textureByUuid(String uuid) {
		if (uuid == null || Texture.all == null)return null;
		for (int i = 0; i < Texture.all.length; i++) {
			Texture t = Texture.all.getAt(i);
			if (uuid.equals(t.uuid))return t;
		}
		return null;
	}

	/** the root group of a node (parent chain up to the group under the outliner root), or null */
	static Group rootOf(Cube c) {
		return RootUtil.rootOf(c);// the outliner root is the string "root", never walked into
	}

	/** the texture sheet a cube is exported with (same rule as BlockbenchExport.sheetOf) */
	static TextureSheetType sheetOf(Cube c) {
		Group root = rootOf(c);
		if (root == null || root.name == null)return TextureSheetType.SKIN;
		VanillaModelPart part = root.getRootType();
		if (part instanceof RootModelType) {
			RootGroups g = RootGroups.getGroup((RootModelType) part);
			if (g != null)return g.getTexSheet((RootModelType) part);
		}
		return TextureSheetType.SKIN;
	}

	/** uuid of the texture the export uses for a sheet (same mapping as BlockbenchExport.doExport), or null */
	static String sheetTextureUuid(TextureSheetType tst) {
		if (Texture.all == null || Texture.all.length == 0)return null;
		if (Texture.all.length == 1)return tst == TextureSheetType.SKIN ? Texture.all.getAt(0).uuid : null;
		String found = null, firstUnknown = null;
		boolean skinFound = false;
		for (int i = 0; i < Texture.all.length; i++) {
			Texture t = Texture.all.getAt(i);
			TextureSheetType s0 = null;
			for (TextureSheetType s : TextureSheetType.VALUES) {
				if (t.name != null && (t.name.equalsIgnoreCase(s.name()) || t.name.equalsIgnoreCase(s.name() + ".png"))) {
					s0 = s;
					break;
				}
			}
			if (s0 == null) {
				if (firstUnknown == null)firstUnknown = t.uuid;
				continue;
			}
			if (s0 == TextureSheetType.SKIN)skinFound = true;
			if (s0 == tst)found = t.uuid;
		}
		if (tst == TextureSheetType.SKIN && !skinFound)return firstUnknown;
		return found;
	}

	/** {w, h, data} of a texture (cached by uuid + change counter), or null when not loaded */
	private static Object pixels(Texture t) {
		Object[] e = texCache.get(t.uuid);
		if (e != null && ((Integer) e[0]) == changeCounter && e[2] == Js.asPropertyMap(t).get("source"))return e[1];
		Object px = W5EffectsNatives.readTexture(t);
		texCache.put(t.uuid, new Object[] {changeCounter, px, Js.asPropertyMap(t).get("source")});
		return px;
	}

	private static String faceTex(CubeFace f) {
		Object v = W5EffectsNatives.faceTexture(f);
		return v != null && "string".equals(Js.typeof(v)) && !((String) v).isEmpty() ? (String) v : null;
	}

	/** flat [x0, y0, x1, y1, ...] texel rectangles of the cube's faces in texture pixels (frame 0) */
	private static double[] rects(Cube c, Texture t, Object px) {
		JsPropertyMap<Object> p = Js.uncheckedCast(px);
		double w = Js.asDouble(p.get("w")), h = Js.asDouble(p.get("h"));
		int frames = W5EffectsNatives.frames(t);
		double fh = h / frames;
		double sx = w / W5EffectsNatives.uvWidth(t), sy = fh / W5EffectsNatives.uvHeight(t);
		List<Double> r = new ArrayList<>();
		c.faces.forEach(f -> {
			if (f == null || f.uv == null || f.isDisabled())return;
			double x0 = Math.floor(Math.min(f.uv.sx, f.uv.ex) * sx + 1e-4), x1 = Math.ceil(Math.max(f.uv.sx, f.uv.ex) * sx - 1e-4);
			double y0 = Math.floor(Math.min(f.uv.sy, f.uv.ey) * sy + 1e-4), y1 = Math.ceil(Math.max(f.uv.sy, f.uv.ey) * sy - 1e-4);
			x0 = Math.max(0, Math.min(w, x0));
			x1 = Math.max(0, Math.min(w, x1));
			y0 = Math.max(0, Math.min(fh, y0));
			y1 = Math.max(0, Math.min(fh, y1));
			if (x1 > x0 && y1 > y0) {
				r.add(x0);
				r.add(y0);
				r.add(x1);
				r.add(y1);
			}
		});
		double[] a = new double[r.size()];
		for (int i = 0; i < a.length; i++)a[i] = r.get(i);
		return a;
	}

	/**
	 * Scans the texels of the rects: {pixels, transparent (alpha != 255 count), distinct (opaque RGB count, capped),
	 * dominant (most frequent opaque RGB, -1 none), animDiffers}
	 */
	private static native JsPropertyMap<Object> scan(Object px, double[] rects, int frames) /*-{
		var w = px.w, h = px.h, d = px.data, fh = Math.floor(h / frames);
		var counts = {}, distinct = 0, n = 0, transparent = 0, animDiffers = false, best = -1, bestN = 0;
		for (var r = 0; r + 3 < rects.length; r += 4) {
			for (var y = rects[r + 1]; y < rects[r + 3]; y++) {
				for (var x = rects[r]; x < rects[r + 2]; x++) {
					var i = (y * w + x) * 4;
					n++;
					var a = d[i + 3];
					if (a !== 255) transparent++;
					if (a > 0) {
						var rgb = (d[i] << 16) | (d[i + 1] << 8) | d[i + 2];
						var c = counts[rgb];
						if (c === undefined) { counts[rgb] = 1; distinct++; c = 1; } else { c = ++counts[rgb]; }
						if (c > bestN) { bestN = c; best = rgb; }
					}
					if (frames > 1 && !animDiffers) {
						for (var f = 1; f < frames; f++) {
							var j = ((y + f * fh) * w + x) * 4;
							if (d[j] !== d[i] || d[j + 1] !== d[i + 1] || d[j + 2] !== d[i + 2] || d[j + 3] !== d[i + 3]) { animDiffers = true; break; }
						}
					}
				}
			}
		}
		return {pixels: n, transparent: transparent, distinct: distinct, dominant: best, animDiffers: animDiffers};
	}-*/;

	/** pure analysis of one cube (nothing changes) */
	public static Analysis analyze(Cube c) {
		Analysis a = new Analysis(c);
		if (c.isColorCube()) {
			a.status = Status.ALREADY;
			a.rgb = c.getRecolor();
			return a;
		}
		if (c.extrude || hasExtrudeData(c))return a.exclude("extrude");
		if (c.getRecolor() != -1)return a.exclude("recolor");
		Set<String> texs = new LinkedHashSet<>();
		boolean[] hidden = new boolean[1];
		int[] noTex = new int[1];
		c.faces.forEach(f -> {
			if (f == null)return;
			if (f.isDisabled() || f.uv == null || f.uv.isEmpty()) {
				hidden[0] = true;
				return;
			}
			String t = faceTex(f);
			if (t == null)noTex[0]++;
			else texs.add(t);
		});
		if (hidden[0])return a.exclude("hiddenFaces");
		if (texs.isEmpty())return a.exclude("noTexture");
		if (noTex[0] > 0 || texs.size() > 1)return a.exclude("mixedTextures");
		String uuid = texs.iterator().next();
		Texture t = textureByUuid(uuid);
		if (t == null)return a.exclude("notLoaded");
		Object px = pixels(t);
		if (px == null)return a.exclude("notLoaded");
		if (!uuid.equals(sheetTextureUuid(sheetOf(c))))return a.exclude("otherTexture");
		double[] r = rects(c, t, px);
		JsPropertyMap<Object> s = scan(px, r, W5EffectsNatives.frames(t));
		a.pixels = (int) Js.asDouble(s.get("pixels"));
		a.colors = (int) Js.asDouble(s.get("distinct"));
		if (a.pixels == 0)return a.exclude("noPixels");
		if (Js.asDouble(s.get("transparent")) > 0)return a.exclude("transparent");
		if (Js.isTruthy(s.get("animDiffers")))return a.exclude("animated");
		a.rgb = (int) Js.asDouble(s.get("dominant"));
		a.status = a.colors == 1 ? Status.CANDIDATE : Status.MULTI;
		if (a.status == Status.MULTI)a.reason = "multi";
		return a;
	}

	private static boolean hasExtrudeData(Cube c) {
		String pd = c.pluginData;
		if (pd == null || !pd.contains("extrude"))return false;
		return c.getData().getExtrude() != null;
	}

	/**
	 * The most frequent opaque colour of the texels the cube uses (frame 0), or def when nothing can be read. Used as
	 * the start colour of 'Recolorir' on a textured cube (never black by accident).
	 */
	public static int dominantColorOr(Cube c, int def) {
		try {
			String uuid = null;
			Map<String, Integer> n = new HashMap<>();
			c.faces.forEach(f -> {
				if (f == null || f.isDisabled())return;
				String t = faceTex(f);
				if (t != null)n.merge(t, 1, Integer::sum);
			});
			int best = 0;
			for (Map.Entry<String, Integer> e : n.entrySet()) {
				if (e.getValue() > best) {
					best = e.getValue();
					uuid = e.getKey();
				}
			}
			Texture t = textureByUuid(uuid);
			if (t == null)return def;
			Object px = pixels(t);
			if (px == null)return def;
			JsPropertyMap<Object> s = scan(px, rects(c, t, px), 1);
			int rgb = (int) Js.asDouble(s.get("dominant"));
			return rgb < 0 ? def : rgb;
		} catch (Throwable e) {
			return def;
		}
	}

	// ====================================================================== conversion

	/** state of a cube before the conversion (put back when the post-check fails) */
	private static final class Saved {
		boolean boxUv;
		Object recolorRaw;
		Object[] tex = new Object[6];
	}

	private static Saved save(Cube c) {
		Saved s = new Saved();
		s.boxUv = c.box_uv;
		s.recolorRaw = Js.asPropertyMap(c).get("cpm_recolor");
		int[] i = new int[1];
		c.faces.forEach(f -> s.tex[i[0]++] = f == null ? null : Js.asPropertyMap(f).get("texture"));
		return s;
	}

	private static void restore(Cube c, Saved s) {
		c.box_uv = s.boxUv;
		if (s.recolorRaw == null)Js.asPropertyMap(c).delete("cpm_recolor");
		else Js.asPropertyMap(c).set("cpm_recolor", s.recolorRaw);
		int[] i = new int[1];
		c.faces.forEach(f -> {
			Object v = s.tex[i[0]++];
			if (f != null)Js.asPropertyMap(f).set("texture", v);
		});
	}

	/**
	 * Converts cubes into colour cubes in ONE undo entry. rgbOf: uuid -> colour. Returns the names of the cubes whose
	 * post-check failed (put back as they were).
	 */
	public static List<String> convert(List<Cube> cubes, Map<String, Integer> rgbOf, String label) {
		List<String> failed = new ArrayList<>();
		if (cubes.isEmpty())return failed;
		Cube[] arr = cubes.toArray(new Cube[0]);
		UndoData before = UndoData.make((OutlinerElement[]) arr);
		PlusUndo.run(label, before, () -> UndoData.make((OutlinerElement[]) arr), () -> {
			for (Cube c : arr) {
				Integer rgb = rgbOf.get(c.uuid);
				if (rgb == null)continue;
				Saved s = save(c);
				c.box_uv = true;
				c.faces.forEach(f -> {
					if (f != null)f.setBlank();
				});
				c.setRecolor(rgb & 0xFFFFFF);
				if (!(c.isColorCube() && c.getRecolor() == (rgb & 0xFFFFFF))) {
					restore(c, s);
					failed.add(c.name);
				}
			}
		});
		JsArray<Object> els = new JsArray<>();
		for (Cube c : arr)els.push(c);
		W5EffectsNatives.updateElements(els);
		invalidateCache();
		return failed;
	}

	private static String hex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}

	private static String reasonText(String reason) {
		return I18n.get(L + "reason." + reason);
	}

	private static String parentName(Cube c) {
		Group p = c.parent;// "root" (a string) for cubes outside any group
		return p == null || p == Group.ROOT || p.name == null ? "-" : p.name;
	}

	// ====================================================================== UI: finder (CPM+ > Otimizar)

	/** CPM+ > Otimizar > 'Achar peças de cor única': list with reasons, convert the checked ones in one undo */
	public static void openFinder() {
		List<Analysis> all = analyzeAll();
		List<Analysis> cand = new ArrayList<>(), excl = new ArrayList<>();
		for (Analysis a : all) {
			if (a.status == Status.CANDIDATE)cand.add(a);
			else if (a.status == Status.EXCLUDED || a.status == Status.MULTI)excl.add(a);
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, I18n.get(L + "finder.intro")));
		if (cand.isEmpty()) {
			rows.add(FormRow.info("none", null, I18n.get(L + "finder.none")));
		} else {
			int px = 0;
			for (int i = 0; i < cand.size() && i < 400; i++) {
				Analysis a = cand.get(i);
				px += a.pixels;
				rows.add(FormRow.checkbox("c" + i, I18n.format(L + "finder.row", hex(a.rgb), a.cube.name, parentName(a.cube)), true));
			}
			if (cand.size() > 400)rows.add(FormRow.info("more", null, I18n.format(L + "finder.more", cand.size() - 400)));
			rows.add(FormRow.info("savings", null, I18n.format(L + "finder.savings", px)));
		}
		if (!excl.isEmpty()) {
			rows.add(FormRow.separator());
			StringBuilder sb = new StringBuilder();
			int n = 0;
			for (Analysis a : excl) {
				if (n++ >= 60) {
					sb.append(I18n.format(L + "finder.more", excl.size() - 60)).append('\n');
					break;
				}
				sb.append(I18n.format(L + "finder.excludedRow", a.cube.name, a.status == Status.MULTI ? I18n.format(L + "reason.multi", a.colors) : reasonText(a.reason))).append('\n');
			}
			rows.add(FormRow.info("excluded", L + "finder.excluded", sb.toString().trim()));
		}
		if (!cand.isEmpty()) {
			rows.add(FormRow.buttons("sel", null, new String[] {L + "finder.select"}, i -> {
				JsArray<Object> els = new JsArray<>();
				for (Analysis a : cand)els.push(a.cube);
				W5EffectsNatives.selectOnly(els, new JsArray<>());
			}));
		}
		FormOpts o = new FormOpts();
		o.confirmKey = cand.isEmpty() ? "cpmplus.w5effects.btn.close" : L + "finder.convert";
		o.cancelKey = "cpmplus.w5effects.btn.cancel";
		o.width = 560;
		PlusDialogs.form("cpmplus_w5fx_solid_finder", L + "finder.title", rows, r -> {
			if (cand.isEmpty())return;
			List<Cube> cubes = new ArrayList<>();
			Map<String, Integer> rgb = new HashMap<>();
			for (int i = 0; i < cand.size() && i < 400; i++) {
				if (!r.getBool("c" + i))continue;
				cubes.add(cand.get(i).cube);
				rgb.put(cand.get(i).cube.uuid, cand.get(i).rgb);
			}
			if (cubes.isEmpty())return;
			report(cubes.size(), convert(cubes, rgb, I18n.get(L + "undo")));
		}, o);
	}

	private static void report(int n, List<String> failed) {
		if (failed.isEmpty())PlusDialogs.done(I18n.format(L + "doneN", n));
		else PlusDialogs.block(I18n.format(L + "failed", n - failed.size(), String.join(", ", failed)));
	}

	// ====================================================================== UI: cube context menu

	/** cube context menu 'Virar cor sólida…' on the selection */
	public static void convertSelection() {
		List<Cube> sel = selectedCubes();
		if (sel.isEmpty()) {
			PlusDialogs.block(L + "needCubes");
			return;
		}
		List<Analysis> cand = new ArrayList<>(), multi = new ArrayList<>(), excl = new ArrayList<>();
		for (Cube c : sel) {
			Analysis a = analyze(c);
			if (a.status == Status.CANDIDATE)cand.add(a);
			else if (a.status == Status.MULTI)multi.add(a);
			else if (a.status == Status.EXCLUDED)excl.add(a);
		}
		StringBuilder ex = new StringBuilder();
		for (int i = 0; i < excl.size() && i < 20; i++) {
			Analysis a = excl.get(i);
			ex.append(I18n.format(L + "finder.excludedRow", a.cube.name, reasonText(a.reason))).append('\n');
		}
		if (cand.isEmpty() && multi.isEmpty()) {
			PlusDialogs.info("cpmplus_w5fx_solid_none", L + "finder.title",
					java.util.Arrays.asList(I18n.get(L + "selectionNone"), ex.toString().trim()));
			return;
		}
		if (multi.isEmpty()) {
			Map<String, Integer> rgb = new HashMap<>();
			List<Cube> cubes = new ArrayList<>();
			for (Analysis a : cand) {
				cubes.add(a.cube);
				rgb.put(a.cube.uuid, a.rgb);
			}
			report(cubes.size(), convert(cubes, rgb, I18n.get(L + "undo")));
			if (ex.length() > 0)PlusDialogs.toast(I18n.format(L + "skipped", excl.size()), "info", 8000, null);
			return;
		}
		// several colours: nothing changes before the user confirms the colour
		Analysis first = multi.get(0);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("msg", null, multi.size() == 1
				? I18n.format(L + "multi.one", first.cube.name, first.colors, hex(first.rgb))
				: I18n.format(L + "multi.many", multi.size(), hex(first.rgb))));
		rows.add(FormRow.color("color", L + "multi.color", hex(first.rgb)));
		if (!cand.isEmpty())rows.add(FormRow.info("cand", null, I18n.format(L + "multi.cand", cand.size())));
		if (ex.length() > 0)rows.add(FormRow.info("excluded", L + "finder.excluded", ex.toString().trim()));
		FormOpts o = new FormOpts();
		o.confirmKey = L + "multi.convert";
		o.cancelKey = "cpmplus.w5effects.btn.cancel";
		PlusDialogs.form("cpmplus_w5fx_solid_multi", L + "finder.title", rows, r -> {
			int picked = parseHex(r.getColor("color"), first.rgb);
			Map<String, Integer> rgb = new HashMap<>();
			List<Cube> cubes = new ArrayList<>();
			for (Analysis a : cand) {
				cubes.add(a.cube);
				rgb.put(a.cube.uuid, a.rgb);
			}
			for (Analysis a : multi) {
				cubes.add(a.cube);
				rgb.put(a.cube.uuid, picked);
			}
			report(cubes.size(), convert(cubes, rgb, I18n.get(L + "undo")));
		}, o);
	}

	static int parseHex(String hex, int def) {
		if (hex == null)return def;
		String h = hex.trim();
		if (h.startsWith("#"))h = h.substring(1);
		if (h.length() == 8)h = h.substring(0, 6);
		if (h.length() == 3)h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
		try {
			return Integer.parseInt(h.toLowerCase(Locale.ROOT), 16) & 0xFFFFFF;
		} catch (Exception e) {
			return def;
		}
	}

	// ====================================================================== export fix

	/** exported cubes without any texture on any face and without a colour (the export cannot draw them) */
	static List<Cube> untexturedCubes() {
		List<Cube> out = new ArrayList<>();
		for (Cube c : allCubes()) {
			if (!c.export || c.getRecolor() != -1 || c.extrude)continue;
			boolean[] any = new boolean[1];
			c.faces.forEach(f -> {
				// a hidden face would become visible on a colour cube: such cubes are left to the user
				if (f != null && (faceTex(f) != null || f.isDisabled()))any[0] = true;
			});
			if (!any[0])out.add(c);
		}
		return out;
	}

	/** fix of the 'invalidTexture' warning: pick a colour, the untextured cubes become colour cubes (one undo) */
	static Promise<Boolean> fixUntextured() {
		return new Promise<>((res, rej) -> {
			List<Cube> cubes = untexturedCubes();
			if (cubes.isEmpty()) {
				res.onInvoke(false);
				return;
			}
			List<String> names = new ArrayList<>();
			for (int i = 0; i < cubes.size() && i < 10; i++)names.add(cubes.get(i).name);
			List<FormRow> rows = new ArrayList<>();
			rows.add(FormRow.info("msg", null, I18n.format(L + "fix.msg", cubes.size(), String.join(", ", names) + (cubes.size() > 10 ? " (+" + (cubes.size() - 10) + ")" : ""))));
			rows.add(FormRow.color("color", L + "multi.color", hex(FALLBACK)));
			FormOpts o = new FormOpts();
			o.confirmKey = L + "multi.convert";
			o.cancelKey = "cpmplus.w5effects.btn.cancel";
			boolean[] done = new boolean[1];
			o.onCancel = () -> {
				if (!done[0]) {
					done[0] = true;
					res.onInvoke(false);
				}
			};
			PlusDialogs.form("cpmplus_w5fx_solid_fix", L + "fix.title", rows, r -> {
				if (done[0])return;
				done[0] = true;
				int rgb = parseHex(r.getColor("color"), FALLBACK);
				Map<String, Integer> m = new LinkedHashMap<>();
				for (Cube c : cubes)m.put(c.uuid, rgb);
				List<String> failed = convert(cubes, m, I18n.get(L + "undo"));
				report(cubes.size(), failed);
				res.onInvoke(failed.size() < cubes.size());
			}, o);
		});
	}

	// ====================================================================== test hooks

	/** cpmplusTest.findSolidColor(): every cube with status / reason / rgb */
	public static JsArray<Object> testFind() {
		JsArray<Object> out = new JsArray<>();
		for (Analysis a : analyzeAll()) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("uuid", a.cube.uuid);
			m.set("name", a.cube.name);
			m.set("status", a.status.name());
			m.set("reason", a.reason);
			m.set("rgb", (double) a.rgb);
			m.set("colors", (double) a.colors);
			m.set("pixels", (double) a.pixels);
			out.push(m);
		}
		return out;
	}

	/** cpmplusTest.toSolidColor(uuids, color?): converts the candidates (or every given cube with color) */
	public static JsPropertyMap<Object> testConvert(List<String> uuids, Integer color) {
		List<Cube> cubes = new ArrayList<>();
		Map<String, Integer> rgb = new HashMap<>();
		List<String> skipped = new ArrayList<>();
		for (Cube c : allCubes()) {
			if (!uuids.contains(c.uuid))continue;
			if (color != null) {
				cubes.add(c);
				rgb.put(c.uuid, color);
				continue;
			}
			Analysis a = analyze(c);
			if (a.status == Status.CANDIDATE) {
				cubes.add(c);
				rgb.put(c.uuid, a.rgb);
			} else skipped.add(c.name);
		}
		List<String> failed = convert(cubes, rgb, I18n.get(L + "undo"));
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("ok", true);
		m.set("converted", (double) (cubes.size() - failed.size()));
		JsArray<Object> f = new JsArray<>();
		for (String s : failed)f.push(s);
		m.set("failed", f);
		JsArray<Object> sk = new JsArray<>();
		for (String s : skipped)sk.push(s);
		m.set("skipped", sk);
		return m;
	}

}
