package com.tom.cpm.blockbench.tools.budget;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.util.GetFreeSkinSlots;
import com.tom.cpm.shared.editor.util.SafetyLevel;
import com.tom.cpm.shared.io.ChecksumOutputStream;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.BoxRender;
import com.tom.cpm.shared.parts.ModelPartEnd;
import com.tom.cpm.shared.parts.ModelPartSkinType;
import com.tom.cpm.shared.parts.PartCollection;
import com.tom.cpl.util.Image;

import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W2-TOOLS, C1): 'Quem pode ver meu modelo?' - READ-ONLY.
 * <p>
 * Your own model is exempt from CPM's safety limits: you always see it, while players with other safety settings may
 * get it blocked. This panel measures the model like the game does and shows, per audience, whether it loads:
 * <ul>
 * <li>cubes: every custom part the game builds (ModelDefinition.resolveAll: exported parts, plus the fake part of a
 * duplicated / extra root) + the voxels of extruded cubes (BoxRender.getExtrudeSize);</li>
 * <li>texture sheets: width and height of every sheet in the model (TextureProvider checks both);</li>
 * <li>size: the model file (.cpmmodel) keeps up to 30 KB inline; anything bigger goes to a link (gist / paste) whose
 * download is base64 text: KB = floor(4 * ceil(bytes / 3) / 1024), compared with each profile's maxLink;</li>
 * <li>animated textures: off for strangers on the default profile (the model loads, the texture stays still).</li>
 * </ul>
 * Columns: MEDIUM (what strangers use by default: still textures, 256 px, 100 KB link, 256 cubes), LOW (512, 1024 KB,
 * 1024), FRIENDS (the friends default: 512, 1024 KB, 32767), OFF (safety off: 8192 px, no other limit) and YOU (your
 * own model: no checks). These limits only depend on OTHER players' settings: they are information, never errors
 * (Convention 13). Real format limits (layer-controlled animations vs free skin layers, 253 poses / gestures / layers,
 * 255 frames / parts per animation) are listed apart. Each section is measured inside its own try/catch: a failure
 * shows 'desconhecido' in that row, never an error dialog. Nothing is changed or written.
 * <p>
 * Shared helper for other packages: {@link #measure()} / {@link #measure(Editor, BlockbenchExport)},
 * {@link Report#verdict(Audience)} and {@link #summaryLine(Report)}.
 */
public final class ModelBudget {
	public static final String DIALOG_ID = "cpmplus_budget";
	/** inline data block of a .cpmmodel (Exporter.ModelWriter buffer) */
	public static final int INLINE_MAX = 30 * 1024;
	/** CPM format: poses / gestures / layers per model (Generators.collectAnimationDetails) */
	public static final int MAX_ANIM_IDS = 253;
	/** CPM format: frames / parts per animation are written as one byte */
	public static final int MAX_PER_ANIM = 255;
	private static final int TOP_ANIMS = 15;
	private static boolean running;

	private ModelBudget() {
	}

	/** who looks at the model, with CPM's limits for them (BuiltInSafetyProfiles / ConfigKeys friend defaults) */
	public enum Audience {
		MEDIUM("medium", true, false, 256, 100, 256),
		LOW("low", true, true, 512, 1024, 1024),
		FRIENDS("friends", true, true, 512, 1024, Short.MAX_VALUE),
		OFF("off", true, true, 8192, 256 * 1024, Integer.MAX_VALUE),
		YOU("you", false, true, 8192, Integer.MAX_VALUE, Integer.MAX_VALUE);

		public final String id;
		/** false = your own model: CPM checks nothing */
		public final boolean checked;
		public final boolean animatedTex;
		public final int maxTex, maxLinkKB, maxCubes;

		Audience(String id, boolean checked, boolean animatedTex, int maxTex, int maxLinkKB, int maxCubes) {
			this.id = id;
			this.checked = checked;
			this.animatedTex = animatedTex;
			this.maxTex = maxTex;
			this.maxLinkKB = maxLinkKB;
			this.maxCubes = maxCubes;
		}

		public String label() {
			return PlusDialogs.tr("cpmplus.w2tools.budget.col." + id);
		}
	}

	public enum State {
		OK, PARTIAL, FAIL, UNKNOWN;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** verdict of one audience: state + reasons (translated text) */
	public static final class Verdict {
		public State state = State.OK;
		public final List<String> reasons = new ArrayList<>();
	}

	/** approximate size of one animation in the model */
	public static final class AnimSize {
		public String name;
		public int frames, parts, bytes;
	}

	/** what was measured; -1 / null = unknown (see {@link #unknown}) */
	public static final class Report {
		public int cubes = -1, extrudeVoxels = -1, exportedParts = -1;
		public int maxSheet = -1;
		public final List<String> sheets = new ArrayList<>();
		public Boolean animatedTex;
		/** model package bytes (= the link content), the inline data block of a .cpmmodel */
		public int bytes = -1, inlineBytes = -1;
		public Boolean overflow;
		/** download size of the link (base64) and raw KB */
		public int linkKB = -1, rawKB = -1;
		/** CPM's own editor verdict (SafetyLevel.getLevel with exportSize set): 'medium' / 'low' / 'none' */
		public String cpmLevel;
		public LayerCapacity.Result layers;
		/** true: the project has no free skin layers set, the ones the skin leaves free were assumed */
		public boolean defaultLayers;
		public int poses = -1, gestures = -1, layerAnims = -1;
		public final List<AnimSize> anims = new ArrayList<>();
		public final List<AnimSize> overFormat = new ArrayList<>();
		public int animCount = -1;
		/** section -&gt; why it could not be measured */
		public final Map<String, String> unknown = new LinkedHashMap<>();

		public int totalCubes() {
			return cubes < 0 || extrudeVoxels < 0 ? -1 : cubes + extrudeVoxels;
		}

		public Verdict verdict(Audience a) {
			Verdict v = new Verdict();
			if (!a.checked) {
				if (maxSheet > a.maxTex) {
					v.state = State.FAIL;
					v.reasons.add(PlusDialogs.trf("cpmplus.w2tools.budget.why.tex", maxSheet, a.maxTex));
				}
				return v;
			}
			boolean fail = false, partial = false, unk = false;
			int tc = totalCubes();
			if (tc < 0)unk = true;
			else if (tc > a.maxCubes) {
				fail = true;
				v.reasons.add(PlusDialogs.trf("cpmplus.w2tools.budget.why.cubes", tc, a.maxCubes));
			}
			if (maxSheet < 0)unk = true;
			else if (maxSheet > a.maxTex) {
				fail = true;
				v.reasons.add(PlusDialogs.trf("cpmplus.w2tools.budget.why.tex", maxSheet, a.maxTex));
			}
			if (overflow == null)unk = true;
			else if (overflow && linkKB > a.maxLinkKB) {
				fail = true;
				v.reasons.add(PlusDialogs.trf("cpmplus.w2tools.budget.why.link", linkKB, a.maxLinkKB));
			}
			if (animatedTex == null)unk = true;
			else if (animatedTex && !a.animatedTex) {
				partial = true;
				v.reasons.add(PlusDialogs.tr("cpmplus.w2tools.budget.why.animTex"));
			}
			v.state = fail ? State.FAIL : unk ? State.UNKNOWN : partial ? State.PARTIAL : State.OK;
			return v;
		}
	}

	// ====================================================================== measuring

	/** test mode only: window.__cpmplus_test_budgetThrow = section name ('cubes', 'sheet', 'size', 'layers', 'anims', 'cpm', 'convert') */
	private static void injected(String section) {
		if (!PlusTestHooks.enabled())return;
		Object v = Js.asPropertyMap(DomGlobal.window).get("__cpmplus_test_budgetThrow");
		if (v != null && (section.equals(v) || "all".equals(v)))throw new IllegalStateException("CPM+ QA: injected failure in " + section);
	}

	private static String err(Throwable e) {
		String m = e.getMessage();
		return e.getClass().getSimpleName() + (m != null ? ": " + m : "");
	}

	/** measures the open project (converted headless like the export, nothing changed); never rejects */
	public static Promise<Report> measure() {
		try {
			injected("convert");
			return ProjectConvert.convertHeadless().then(h -> Promise.resolve(measure(h.editor, h.exp)), e -> {
				Report r = new Report();
				r.unknown.put("convert", String.valueOf(e));
				return Promise.resolve(r);
			});
		} catch (Throwable e) {
			Report r = new Report();
			r.unknown.put("convert", err(e));
			return Promise.resolve(r);
		}
	}

	/** measures a converted editor (a throwaway: its animEnc / exportSize may be set); exp may be null */
	public static Report measure(Editor e, BlockbenchExport exp) {
		Report r = new Report();
		try {
			injected("cubes");
			measureCubes(e, r);
		} catch (Throwable t) {
			r.cubes = r.extrudeVoxels = -1;
			r.unknown.put("cubes", err(t));
		}
		try {
			injected("sheet");
			measureSheets(e, r);
		} catch (Throwable t) {
			r.maxSheet = -1;
			r.animatedTex = null;
			r.unknown.put("sheet", err(t));
		}
		try {
			injected("layers");
			measureLayers(e, r);
		} catch (Throwable t) {
			r.layers = null;
			r.unknown.put("layers", err(t));
		}
		try {
			injected("size");
			measureSize(e, r);
		} catch (Throwable t) {
			r.bytes = r.inlineBytes = r.linkKB = r.rawKB = -1;
			r.overflow = null;
			r.unknown.put("size", err(t));
		}
		try {
			injected("cpm");
			if (r.bytes >= 0) {
				e.exportSize = (r.bytes + 1023) / 1024;// never assigned upstream: CPM's own size check needs it
				r.cpmLevel = SafetyLevel.getLevel(e).getLvl();
			}
		} catch (Throwable t) {
			r.cpmLevel = null;
			r.unknown.put("cpm", err(t));
		}
		try {
			injected("anims");
			measureAnims(e, exp, r);
		} catch (Throwable t) {
			r.anims.clear();
			r.animCount = -1;
			r.unknown.put("anims", err(t));
		}
		return r;
	}

	/** ModelDefinition.resolveAll: every rendered custom cube + the voxels of extruded ones */
	static void measureCubes(Editor e, Report r) {
		int[] c = new int[2];
		countCubes(e.elements, c);
		r.cubes = c[0];
		r.extrudeVoxels = c[1];
		r.exportedParts = BlockbenchExport.countExportedParts(e);
	}

	private static void countCubes(List<ModelElement> list, int[] c) {
		for (ModelElement el : list) {
			if (el.templateElement)continue;// ExportHelper.flattenElements skips the whole subtree
			if (el.type == ElementType.NORMAL) {
				c[0]++;
				if (el.extrude && el.size != null)c[1] += BoxRender.getExtrudeSize(el.size, el.texture ? el.textureSize : 0);
			} else if (el.type == ElementType.ROOT_PART && (el.duplicated || el.typeData instanceof RootModelType)) {
				c[0]++;// written as a fake cube
			}
			countCubes(el.children, c);
		}
	}

	/** texture sheets in the model (skin when edited, every other editable sheet) */
	static void measureSheets(Editor e, Report r) {
		int max = 0;
		boolean anim = false;
		for (Map.Entry<TextureSheetType, ETextures> en : e.textures.entrySet()) {
			TextureSheetType t = en.getKey();
			ETextures tex = en.getValue();
			if (tex == null)continue;
			boolean inModel = t == TextureSheetType.SKIN ? tex.isEdited() : t.editable;
			if (!inModel)continue;
			Image img = tex.getImage();
			if (img == null)continue;
			r.sheets.add(t.name().toLowerCase(Locale.ROOT) + " " + img.getWidth() + "x" + img.getHeight());
			max = Math.max(max, Math.max(img.getWidth(), img.getHeight()));
			if (tex.animatedTexs != null && !tex.animatedTexs.isEmpty())anim = true;
		}
		r.maxSheet = max == 0 ? 64 : max;
		r.animatedTex = anim;
	}

	/** format limits: layer-controlled animations vs free layers, poses / gestures / layers vs 253 */
	static void measureLayers(Editor e, Report r) {
		Set<String> layers = new HashSet<>(), poses = new HashSet<>(), gestures = new HashSet<>();
		for (EditorAnim a : e.animations) {
			if (a.type == null)continue;
			if (a.type.isLayer())layers.add(a.getId());
			if (a.type == AnimationType.CUSTOM_POSE)poses.add(a.getId());
			if (a.type == AnimationType.GESTURE)gestures.add(a.getId());
		}
		r.poses = poses.size();
		r.gestures = gestures.size();
		r.layerAnims = layers.size();
		if (e.animEnc == null && e.vanillaSkin != null && e.animations.stream().anyMatch(EditorAnim::isCustom)) {
			// no skin layers chosen: CPM's in-game export asks and preselects the layers the skin leaves free
			e.animEnc = GetFreeSkinSlots.getDefault(e);
			r.defaultLayers = true;
		}
		r.layers = LayerCapacity.check(e);
	}

	/** the model package (= link content) and the inline .cpmmodel block, exactly as Exporter writes them */
	static void measureSize(Editor e, Report r) throws Exception {
		if (e.animEnc == null && e.vanillaSkin != null && e.animations.stream().anyMatch(EditorAnim::isCustom)) {
			e.animEnc = GetFreeSkinSlots.getDefault(e);
			r.defaultLayers = true;
		}
		PartCollection def = Exporter.prepareExport(e);
		ByteArrayOutputStream pkg = new ByteArrayOutputStream();
		pkg.write(ModelDefinitionLoader.HEADER);
		ChecksumOutputStream cos = new ChecksumOutputStream(pkg);
		def.writePackage(new IOHelper(cos));
		cos.close();
		ByteArrayOutputStream inl = new ByteArrayOutputStream();
		inl.write(ModelDefinitionLoader.HEADER);
		ChecksumOutputStream cos2 = new ChecksumOutputStream(inl);
		try (IOHelper dout = new IOHelper(cos2)) {
			dout.writeObjectBlock(new ModelPartSkinType(e.skinType));
			def.writeBlocks(dout);
			dout.writeObjectBlock(ModelPartEnd.END);
		}
		r.bytes = pkg.size();
		r.inlineBytes = inl.size();
		r.overflow = r.inlineBytes > INLINE_MAX;
		r.rawKB = (r.bytes + 1023) / 1024;
		r.linkKB = linkKB(r.bytes);
	}

	/** KB CPM counts for a link: the base64 download, floor(4 * ceil(raw / 3) / 1024) */
	public static int linkKB(long rawBytes) {
		return (int) (4L * ((rawBytes + 2) / 3) / 1024);
	}

	/** approximate bytes per animation (frames x changed channels), top 15 */
	static void measureAnims(Editor e, BlockbenchExport exp, Report r) {
		List<AnimSize> all = new ArrayList<>();
		if (exp != null && Animation.all != null) {
			for (Animation a : Animation.all) {
				if (a == null)continue;
				List<EditorAnim> eas = exp.getExportedAnims(a.uuid);
				if (eas.isEmpty())continue;
				AnimSize s = new AnimSize();
				s.name = a.name;
				for (EditorAnim ea : eas)add(s, ea);
				all.add(s);
			}
		} else {
			for (EditorAnim ea : e.animations) {
				AnimSize s = new AnimSize();
				s.name = ea.displayName != null ? ea.displayName : ea.filename;
				add(s, ea);
				all.add(s);
			}
		}
		r.animCount = all.size();
		for (AnimSize s : all)if (s.frames > MAX_PER_ANIM || s.parts > MAX_PER_ANIM)r.overFormat.add(s);
		all.sort((x, y) -> Integer.compare(y.bytes, x.bytes));
		for (int i = 0; i < all.size() && i < TOP_ANIMS; i++)r.anims.add(all.get(i));
	}

	private static void add(AnimSize s, EditorAnim ea) {
		List<AnimFrame> fr = ea.getFrames();
		List<ModelElement> comps = ea.getComponentsFiltered();
		int f = fr.size();
		s.frames = Math.max(s.frames, f);
		s.parts = Math.max(s.parts, comps.size());
		int b = 12 + (s.name != null ? s.name.length() : 0) + 2 * comps.size();
		for (ModelElement el : comps) {
			boolean pos = false, rot = false, col = false, vis = false, scl = false;
			for (AnimFrame x : fr) {
				pos |= x.hasPosChanges(el);
				rot |= x.hasRotChanges(el);
				col |= x.hasColorChanges(el);
				vis |= x.hasVisChanges(el);
				scl |= x.hasScaleChanges(el);
			}
			if (pos)b += 5 + 6 * f;
			if (rot)b += 5 + 6 * f;
			if (col)b += 5 + 3 * f;
			if (vis)b += 5 + (f + 7) / 8;
			if (scl)b += 5 + 6 * f;
		}
		s.bytes += b;
	}

	/**
	 * One line per audience (Convention 13): 'Você: ✓ · Amigos: ✓ · Segurança desligada: ✓ · Baixa: ✗ · Padrão (médio):
	 * ✗ não carrega'
	 */
	public static String summaryLine(Report r) {
		Audience[] order = {Audience.YOU, Audience.FRIENDS, Audience.OFF, Audience.LOW, Audience.MEDIUM};
		List<String> parts = new ArrayList<>();
		for (Audience a : order)parts.add(a.label() + ": " + mark(r.verdict(a).state));
		return String.join(" · ", parts);
	}

	private static String mark(State s) {
		switch (s) {
		case OK:
			return "✓";
		case PARTIAL:
			return "✓ " + PlusDialogs.tr("cpmplus.w2tools.budget.state.partialShort");
		case FAIL:
			return "✗ " + PlusDialogs.tr("cpmplus.w2tools.budget.state.failShort");
		default:
			return "?";
		}
	}

	// ====================================================================== user flow

	/** CPM+ menu action: progress message, measure, show the panel (never automatic) */
	public static void open() {
		if (running) {
			PlusDialogs.quick(PlusDialogs.tr("cpmplus.w2tools.budget.busy"), 2000);
			return;
		}
		running = true;
		PlusDialogs.quick(PlusDialogs.tr("cpmplus.w2tools.budget.progress"), 2500);
		PlusDialogs.status(PlusDialogs.tr("cpmplus.w2tools.budget.progress"));
		PlusTestHooks.track(measure().then(r -> {
			running = false;
			PlusDialogs.status(null);
			if (PlusTestHooks.active())PlusTestHooks.setLast("budget", toJson(r));
			show(r);
			return Promise.resolve(r);
		}, e -> {
			running = false;
			PlusDialogs.status(null);
			Report r = new Report();
			r.unknown.put("convert", String.valueOf(e));
			show(r);
			return Promise.resolve(r);
		}));
	}

	private static final String OK_COLOR = "var(--color-confirm, #3fae5a)";
	private static final String BAD_COLOR = "var(--color-close, #f44336)";
	private static final String WARN_COLOR = "var(--color-warning, #e6a23c)";

	private static String esc(Object o) {
		return Dialog.esc(o);
	}

	private static String cell(State s, String text, String title) {
		String color = s == State.OK ? OK_COLOR : s == State.FAIL ? BAD_COLOR : s == State.PARTIAL ? WARN_COLOR : "inherit";
		String sym = s == State.OK ? "✓ " : s == State.FAIL ? "✗ " : s == State.PARTIAL ? "~ " : "? ";
		return "<td style='padding:3px 6px; color:" + color + ";'" + (title != null ? " title='" + esc(title) + "'" : "") + ">" + sym + esc(text) + "</td>";
	}

	private static String unknownCell(Report r, String section) {
		return cell(State.UNKNOWN, PlusDialogs.tr("cpmplus.w2tools.budget.unknown"), r.unknown.get(section));
	}

	/** the panel (dialog 'cpmplus_budget'): table per audience + format limits + heaviest animations */
	static void show(Report r) {
		Audience[] cols = {Audience.MEDIUM, Audience.LOW, Audience.FRIENDS, Audience.OFF, Audience.YOU};
		StringBuilder h = new StringBuilder();
		h.append("<p>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.intro"))).append("</p>");
		h.append("<div style='overflow-x:auto; max-width:100%;'><table class='cpmplus_budget_table' style='border-collapse:collapse; width:100%; font-size:0.95em;'>");
		h.append("<tr><th style='text-align:left; padding:3px 6px;'></th>");
		for (Audience a : cols)h.append("<th style='text-align:left; padding:3px 6px;'>").append(esc(a.label())).append("</th>");
		h.append("</tr>");
		// cubes
		int tc = r.totalCubes();
		h.append("<tr><td style='padding:3px 6px;'>").append(esc(tc < 0 ? PlusDialogs.tr("cpmplus.w2tools.budget.row.cubesUnknown")
				: r.extrudeVoxels > 0 ? PlusDialogs.trf("cpmplus.w2tools.budget.row.cubesExtrude", tc, r.cubes, r.extrudeVoxels) : PlusDialogs.trf("cpmplus.w2tools.budget.row.cubes", tc))).append("</td>");
		for (Audience a : cols) {
			if (tc < 0)h.append(unknownCell(r, "cubes"));
			else if (!a.checked || a.maxCubes == Integer.MAX_VALUE)h.append(cell(State.OK, PlusDialogs.tr("cpmplus.w2tools.budget.noLimit"), null));
			else h.append(cell(tc <= a.maxCubes ? State.OK : State.FAIL, PlusDialogs.trf("cpmplus.w2tools.budget.max", a.maxCubes), null));
		}
		h.append("</tr>");
		// texture
		h.append("<tr><td style='padding:3px 6px;'>").append(esc(r.maxSheet < 0 ? PlusDialogs.tr("cpmplus.w2tools.budget.row.texUnknown") : PlusDialogs.trf("cpmplus.w2tools.budget.row.tex", r.maxSheet))).append("</td>");
		for (Audience a : cols) {
			if (r.maxSheet < 0)h.append(unknownCell(r, "sheet"));
			else h.append(cell(r.maxSheet <= a.maxTex ? State.OK : State.FAIL, PlusDialogs.trf("cpmplus.w2tools.budget.maxPx", a.maxTex), null));
		}
		h.append("</tr>");
		// size
		String sizeText;
		if (r.overflow == null)sizeText = PlusDialogs.tr("cpmplus.w2tools.budget.row.sizeUnknown");
		else if (r.overflow)sizeText = PlusDialogs.trf("cpmplus.w2tools.budget.row.sizeLink", r.rawKB, r.linkKB);
		else sizeText = PlusDialogs.trf("cpmplus.w2tools.budget.row.sizeInline", r.rawKB);
		h.append("<tr><td style='padding:3px 6px;'>").append(esc(sizeText)).append("</td>");
		for (Audience a : cols) {
			if (r.overflow == null)h.append(unknownCell(r, "size"));
			else if (!r.overflow)h.append(cell(State.OK, PlusDialogs.tr("cpmplus.w2tools.budget.inFile"), null));
			else if (!a.checked || a.maxLinkKB == Integer.MAX_VALUE)h.append(cell(State.OK, PlusDialogs.tr("cpmplus.w2tools.budget.noLimit"), null));
			else h.append(cell(r.linkKB <= a.maxLinkKB ? State.OK : State.FAIL, PlusDialogs.trf("cpmplus.w2tools.budget.maxKB", a.maxLinkKB), null));
		}
		h.append("</tr>");
		// animated textures
		h.append("<tr><td style='padding:3px 6px;'>").append(esc(r.animatedTex == null ? PlusDialogs.tr("cpmplus.w2tools.budget.row.animTexUnknown")
				: PlusDialogs.tr(r.animatedTex ? "cpmplus.w2tools.budget.row.animTexYes" : "cpmplus.w2tools.budget.row.animTexNo"))).append("</td>");
		for (Audience a : cols) {
			if (r.animatedTex == null)h.append(unknownCell(r, "sheet"));
			else if (!r.animatedTex || a.animatedTex)h.append(cell(State.OK, PlusDialogs.tr("cpmplus.w2tools.budget.okShort"), null));
			else h.append(cell(State.PARTIAL, PlusDialogs.tr("cpmplus.w2tools.budget.still"), null));
		}
		h.append("</tr>");
		// result
		h.append("<tr style='border-top:1px solid var(--color-border, #444);'><td style='padding:3px 6px;'><b>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.row.result"))).append("</b></td>");
		for (Audience a : cols) {
			Verdict v = r.verdict(a);
			String t = PlusDialogs.tr("cpmplus.w2tools.budget.state." + v.state.id());
			h.append(cell(v.state, t, v.reasons.isEmpty() ? null : String.join("\n", v.reasons)));
		}
		h.append("</tr></table></div>");
		h.append("<p style='opacity:0.85;'>").append(esc(summaryLine(r))).append("</p>");
		if (r.overflow != null && r.overflow)h.append("<p>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.linkNote", r.linkKB))).append("</p>");
		else if (r.overflow != null)h.append("<p>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.skinNote", r.linkKB))).append("</p>");
		if (tc > 256)h.append("<p style='color:" + WARN_COLOR + ";'>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.epicFight", tc))).append("</p>");
		if (r.cpmLevel != null)h.append("<p style='opacity:0.85;'>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.cpmLevel", PlusDialogs.tr("cpmplus.w2tools.budget.level." + r.cpmLevel)))).append("</p>");
		// format limits
		h.append("<h3 style='margin:10px 0 4px 0;'>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.formatTitle"))).append("</h3><ul style='margin-top:0;'>");
		if (r.layers == null) {
			h.append("<li>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.unknownSection"))).append(r.unknown.containsKey("layers") ? " (" + esc(r.unknown.get("layers")) + ")" : "").append("</li>");
		} else {
			boolean bad = !r.layers.ok();
			String txt = r.layers.count == 0 ? PlusDialogs.tr("cpmplus.w2tools.budget.layersNone")
					: PlusDialogs.trf(bad ? "cpmplus.w2tools.budget.layersBad" : "cpmplus.w2tools.budget.layersOk", r.layers.count, r.layers.freeLayers, r.layers.capacity);
			h.append("<li style='color:").append(bad ? BAD_COLOR : "inherit").append(";'>").append(esc(txt));
			if (r.defaultLayers && r.layers.count > 0)h.append(" ").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.layersDefault")));
			h.append("</li>");
			h.append("<li>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.counts", r.poses, r.gestures, r.layerAnims, MAX_ANIM_IDS))).append("</li>");
		}
		for (AnimSize s : r.overFormat)h.append("<li style='color:").append(BAD_COLOR).append(";'>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.animTooBig", s.name, s.frames, s.parts, MAX_PER_ANIM))).append("</li>");
		h.append("</ul>");
		// heaviest animations
		h.append("<h3 style='margin:10px 0 4px 0;'>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.animsTitle"))).append("</h3>");
		if (r.animCount < 0) {
			h.append("<p>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.unknownSection"))).append("</p>");
		} else if (r.anims.isEmpty()) {
			h.append("<p>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.animsNone"))).append("</p>");
		} else {
			h.append("<ol style='margin-top:0;'>");
			for (AnimSize s : r.anims)h.append("<li>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.animRow", s.name, s.frames, s.parts, kb(s.bytes)))).append("</li>");
			h.append("</ol>");
			if (r.animCount > r.anims.size())h.append("<p style='opacity:0.85;'>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.animsMore", r.animCount - r.anims.size()))).append("</p>");
		}
		if (r.unknown.containsKey("convert"))h.append("<p style='color:" + BAD_COLOR + ";'>").append(esc(PlusDialogs.trf("cpmplus.w2tools.budget.convertFailed", r.unknown.get("convert")))).append("</p>");
		h.append("<p style='opacity:0.85;'>").append(esc(PlusDialogs.tr("cpmplus.w2tools.budget.footer"))).append("</p>");
		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = DIALOG_ID;
		d.title = PlusDialogs.tr("cpmplus.w2tools.budget.title");
		d.width = 780;
		d.singleButton = true;
		d.setHtml(h.toString());
		new Dialog(d).show();
	}

	private static String kb(int bytes) {
		double k = bytes / 1024.0;
		return k < 10 ? String.valueOf(Math.round(k * 10) / 10.0) : String.valueOf(Math.round(k));
	}

	// ====================================================================== JSON (test hooks)

	public static Map<String, Object> toJson(Report r) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("cubes", (double) r.cubes);
		m.put("extrudeVoxels", (double) r.extrudeVoxels);
		m.put("totalCubes", (double) r.totalCubes());
		m.put("exportedParts", (double) r.exportedParts);
		m.put("maxSheet", (double) r.maxSheet);
		m.put("sheets", new ArrayList<Object>(r.sheets));
		m.put("animatedTex", r.animatedTex);
		m.put("bytes", (double) r.bytes);
		m.put("inlineBytes", (double) r.inlineBytes);
		m.put("overflow", r.overflow);
		m.put("linkKB", (double) r.linkKB);
		m.put("rawKB", (double) r.rawKB);
		m.put("cpmLevel", r.cpmLevel);
		Map<String, Object> verdicts = new LinkedHashMap<>(), reasons = new LinkedHashMap<>();
		for (Audience a : Audience.values()) {
			Verdict v = r.verdict(a);
			verdicts.put(a.id, v.state.id());
			reasons.put(a.id, new ArrayList<Object>(v.reasons));
		}
		m.put("verdicts", verdicts);
		m.put("reasons", reasons);
		if (r.layers != null) {
			Map<String, Object> l = new LinkedHashMap<>();
			l.put("count", (double) r.layers.count);
			l.put("free", (double) r.layers.freeLayers);
			l.put("capacity", (double) r.layers.capacity);
			l.put("error", r.layers.errorKey);
			l.put("defaultLayers", r.defaultLayers);
			m.put("layers", l);
		} else m.put("layers", null);
		m.put("poses", (double) r.poses);
		m.put("gestures", (double) r.gestures);
		m.put("layerAnims", (double) r.layerAnims);
		List<Object> an = new ArrayList<>();
		for (AnimSize s : r.anims) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("name", s.name);
			x.put("frames", (double) s.frames);
			x.put("parts", (double) s.parts);
			x.put("bytes", (double) s.bytes);
			an.add(x);
		}
		m.put("anims", an);
		m.put("animCount", (double) r.animCount);
		m.put("unknown", new LinkedHashMap<String, Object>(r.unknown));
		m.put("summary", summaryLine(r));
		return m;
	}
}
