package com.tom.cpm.blockbench.tools.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpl.util.Image;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.NativeFs;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.io.ChecksumInputStream;
import com.tom.cpm.shared.io.ChecksumOutputStream;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.SkinDataInputStream;
import com.tom.cpm.shared.io.SkinDataOutputStream;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.parts.ModelPartEnd;
import com.tom.cpm.shared.parts.ModelPartSkinType;
import com.tom.cpm.shared.parts.ModelPartType;
import com.tom.cpm.shared.parts.PartCollection;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.ImageIO;

import elemental2.core.ArrayBuffer;
import elemental2.core.JsRegExp;
import elemental2.core.RegExpResult;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLElement;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-MODELEXPORT, G45): "Skin com o modelo dentro (servidor n\u00e3o precisa do mod)". The model goes into the
 * template area of a 64x64 skin PNG (CPM's SkinDataOutputStream, the channel of the project's arm type); when it does
 * not fit, the PNG carries only a link block and the rest is published in a gist / pastebin, checked by downloading
 * it and comparing byte by byte before the skin is made. Every PNG is read back with SkinDataInputStream (header,
 * checksum, same bytes) before 'Pronto'. Exporting never changes the project; only the published link + hash are
 * remembered (G16 extra, 'Desfazer' toast).
 */
public final class SkinExport {
	/** G16 extra (top level of the project data): {skinLink, skinHash} */
	public static final String EXTRA = "cpmplus_w3modelexport";

	private SkinExport() {
	}

	// ====================================================================== capacity / bytes (pure)

	/** CPM's free-space template (null while it is not loaded) */
	static Image template() {
		try {
			return MinecraftClientAccess.get().getDefinitionLoader().getTemplate();
		} catch (Throwable e) {
			return null;
		}
	}

	/** bytes the template area can carry in this channel (alpha-locked pixels carry 3 bytes, others 4) */
	public static int capacity(Image template, int channel) {
		int shift = 8 * (2 - channel);
		int total = 0;
		for (int y = 0; y < template.getHeight(); y++) {
			for (int x = 0; x < template.getWidth(); x++) {
				int t = template.getRGB(x, y);
				if (((t >>> shift) & 0xff) == 0xff)total += (t & 0xff) == 0xff ? 3 : 4;
			}
		}
		return total;
	}

	/** the byte stream a skin carries: header + checksum(skin type, model blocks or the link block, end) */
	static byte[] skinStream(SkinType type, PartCollection def, Link link) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(ModelDefinitionLoader.HEADER);
		ChecksumOutputStream cos = new ChecksumOutputStream(baos);
		try (IOHelper dout = new IOHelper(cos)) {
			dout.writeObjectBlock(new ModelPartSkinType(type));
			if (link == null)def.writeBlocks(dout);
			else dout.writeObjectBlock(def.toLink(link));
			dout.writeObjectBlock(ModelPartEnd.END);
		}
		return baos.toByteArray();
	}

	/** what goes into the gist when the model does not fit (= CPM's overflow upload: header + checksum(package)) */
	static byte[] overflowData(PartCollection def) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		baos.write(ModelDefinitionLoader.HEADER);
		ChecksumOutputStream cos = new ChecksumOutputStream(baos);
		def.writePackage(new IOHelper(cos));
		cos.close();
		return baos.toByteArray();
	}

	/** the meter: what the model needs vs what the skin holds */
	public static final class Meter {
		public int needed, capacity, overflowBytes;
		public boolean fits;
		public String b64;
		public SkinType type;
		PartCollection def;
	}

	/** measures on the converted editor (no image is touched: the base image is copied only when the skin is written) */
	static Meter measure(Editor e, ModelDescription d) throws IOException {
		Image tpl = template();
		if (tpl == null)throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.skin.noTemplate"));
		ModelDescription old = e.description;
		PartCollection def;
		try {
			e.description = d;
			def = Exporter.prepareExport(e);
		} finally {
			e.description = old;
		}
		Meter m = new Meter();
		m.def = def;
		m.type = e.skinType != null && e.skinType != SkinType.UNKNOWN ? e.skinType : SkinType.DEFAULT;
		m.capacity = capacity(tpl, m.type.getChannel());
		m.needed = skinStream(m.type, def, null).length;
		m.fits = m.needed <= m.capacity;
		if (!m.fits) {
			byte[] ovf = overflowData(def);
			m.overflowBytes = ovf.length;
			m.b64 = Base64.getEncoder().encodeToString(ovf);
		}
		return m;
	}

	/** writes the stream into a COPY of the base; the base itself is never changed */
	static Image writeSkin(Image base, int channel, byte[] stream) throws IOException {
		Image img = new Image(base);
		try (SkinDataOutputStream out = new SkinDataOutputStream(img, template(), channel)) {
			out.write(stream);
		}
		return img;
	}

	// ====================================================================== reading a skin back

	/** what a skin PNG carries */
	public static final class Inspect {
		public boolean hasData, checksumOk;
		public String skinType, link, error;
		public int bytes;
		public byte[] stream;
	}

	private static final class CountingIn extends InputStream {
		private final InputStream in;
		final ByteArrayOutputStream copy = new ByteArrayOutputStream();

		CountingIn(InputStream in) {
			this.in = in;
		}

		@Override
		public int read() throws IOException {
			int b = in.read();
			if (b >= 0)copy.write(b);
			return b;
		}
	}

	/** reads the model data of a skin like CPM (header in the classic or slim channel, blocks, checksum) */
	public static Inspect inspect(Image img) {
		Inspect r = new Inspect();
		Image tpl = template();
		if (tpl == null || img == null || img.getWidth() != 64 || img.getHeight() != 64) {
			r.error = img == null ? "no image" : "size " + img.getWidth() + "x" + img.getHeight();
			return r;
		}
		for (SkinType st : new SkinType[] {SkinType.DEFAULT, SkinType.SLIM}) {
			SkinDataInputStream sin = new SkinDataInputStream(img, tpl, st.getChannel());
			CountingIn in = new CountingIn(sin);
			try {
				if (in.read() != ModelDefinitionLoader.HEADER)continue;
				r.hasData = true;
				r.skinType = st == SkinType.SLIM ? "slim" : "classic";
				ChecksumInputStream cis = new ChecksumInputStream(in);
				IOHelper din = new IOHelper(cis);
				for (int guard = 0; guard < 100000; guard++) {
					Object[] blk = din.readObjectBlock(ModelPartType.VALUES, (t, b) -> new Object[] {t, b});
					if (blk == null)continue;
					ModelPartType t = (ModelPartType) blk[0];
					IOHelper b = (IOHelper) blk[1];
					if (t == ModelPartType.SKIN_TYPE) {
						int v = b.read();
						SkinType ty = v >= 0 && v < SkinType.VALUES.length ? SkinType.VALUES[v] : SkinType.UNKNOWN;
						if (ty != SkinType.UNKNOWN) {
							r.skinType = ty == SkinType.SLIM ? "slim" : "classic";
							if (ty.getChannel() != sin.getChannel())sin.setChannel(ty.getChannel());
						}
					} else if (t == ModelPartType.PACKAGE_LINK || t == ModelPartType.DEFINITION_LINK || t == ModelPartType.SKIN_LINK) {
						r.link = new Link(b).toString();
					} else if (t == ModelPartType.END) {
						cis.checkSum();
						r.checksumOk = true;
						break;
					}
				}
				r.stream = in.copy.toByteArray();
				r.bytes = r.stream.length;
				return r;
			} catch (Throwable e) {
				r.error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
				r.stream = in.copy.toByteArray();
				r.bytes = r.stream.length;
				return r;
			}
		}
		return r;
	}

	/** PNG bytes -&gt; Image without any colour change (pngj; canvas without premultiply as a fallback) */
	static Promise<Image> decodePng(byte[] png) {
		try {
			return Promise.resolve(ImageIO.load(new ByteArrayInputStream(png)));
		} catch (Throwable e) {
			return ImageIO.loadImage(Base64.getEncoder().encodeToString(png), true, false);
		}
	}

	static byte[] encodePng(Image img) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		ImageIO.save(img, b);
		return b.toByteArray();
	}

	// ====================================================================== the full export

	/** result of {@link #generate} */
	public static final class Result {
		public byte[] png;
		public Meter meter;
		public Link link;
		public Inspect check;
	}

	/**
	 * Writes the skin: the model inline when it fits, otherwise the link block (link required). The PNG is encoded,
	 * decoded again and read back with SkinDataInputStream: header, checksum and the same bytes, or it rejects.
	 */
	static Result generate(Meter m, Image base, Link link) throws IOException {
		if (base.getWidth() != 64 || base.getHeight() != 64)throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.skin.size"));
		if (!m.fits && link == null)throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.skin.needsLink"));
		byte[] stream = skinStream(m.type, m.def, m.fits ? null : link);
		Image img = writeSkin(base, m.type.getChannel(), stream);
		Result r = new Result();
		r.meter = m;
		r.link = m.fits ? null : link;
		r.png = encodePng(img);
		Image back = ImageIO.load(new ByteArrayInputStream(r.png));
		Inspect ins = inspect(back);
		r.check = ins;
		boolean same = ins.stream != null && ins.stream.length >= stream.length;
		for (int i = 0; same && i < stream.length; i++)same = ins.stream[i] == stream[i];
		if (!ins.hasData || !ins.checksumOk || !same)
			throw new ModelExport.UserError(I18n.format("cpmplus.w3modelexport.skin.verifyFailed", ins.error != null ? ins.error : "bytes"));
		return r;
	}

	// ====================================================================== bases

	/** project skin texture as an image when it is exactly 64x64, else null */
	static Image projectTexture(Editor e) {
		try {
			Image i = e.textures.get(TextureSheetType.SKIN).getImage();
			return i != null && i.getWidth() == 64 && i.getHeight() == 64 ? new Image(i) : null;
		} catch (Throwable ex) {
			return null;
		}
	}

	/** Steve / Alex of the project's arm type */
	static Image defaultSkin(Editor e) {
		SkinType t = e.skinType != null && e.skinType != SkinType.UNKNOWN ? e.skinType : SkinType.DEFAULT;
		return new Image(t.getSkinTexture());
	}

	/** heuristic Steve/Alex check of a base: classic arms are 4 px wide (column 54-55 of the right arm used) */
	static String armHint(Image base, SkinType type) {
		int opaque = 0;
		for (int x = 54; x < 56; x++)for (int y = 20; y < 32; y++)if ((base.getRGB(x, y) >>> 24) != 0)opaque++;
		boolean looksClassic = opaque > 0;
		if (type == SkinType.SLIM && looksClassic)return I18n.get("cpmplus.w3modelexport.skin.armHintClassic");
		if (type != SkinType.SLIM && !looksClassic)return I18n.get("cpmplus.w3modelexport.skin.armHintSlim");
		return null;
	}

	// ====================================================================== link + hash in the project

	public static String savedLink() {
		Map<String, Object> m = extra();
		Object v = m != null ? m.get("skinLink") : null;
		return v instanceof String ? (String) v : null;
	}

	public static String savedHash() {
		Map<String, Object> m = extra();
		Object v = m != null ? m.get("skinHash") : null;
		return v instanceof String ? (String) v : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> extra() {
		try {
			Object v = Project.getData().getExtra(EXTRA);
			return v instanceof Map ? (Map<String, Object>) v : null;
		} catch (Throwable e) {
			return null;
		}
	}

	/** remembers the published link + hash of its data (G16 extra; survives .bbmodel save; 'Desfazer' toast) */
	static void storeLink(Link link, String b64) {
		String hash = ProjectData.hash(b64);
		if (link.toString().equals(savedLink()) && hash.equals(savedHash()))return;
		ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
		Map<String, Object> m = extra();
		Map<String, Object> n = new LinkedHashMap<>();
		if (m != null)n.putAll(m);
		n.put("skinLink", link.toString());
		n.put("skinHash", hash);
		ProjectData pd = Project.getData();
		pd.putExtra(EXTRA, n);
		pd.flush();
		Project.saved = false;
		ProjectDataUndo.offerUndo(before, I18n.get("cpmplus.w3modelexport.skin.linkStored"));
	}

	// ====================================================================== UI

	static final String BASE_PROJECT = "project", BASE_FILE = "file", BASE_DEFAULT = "default";

	/** CPM+ &gt; Levar pro jogo\u2026 &gt; Skin com o modelo dentro */
	public static void open() {
		Editor[] ed = new Editor[1];
		PlusTestHooks.track(ModelExport.prepareEditor(true, true).then(e -> {
			ed[0] = e;
			String le = ModelExport.layerError(e);
			if (le != null)return Promise.reject(new ModelExport.UserError(I18n.get(le)));
			return ModelExport.resolveLock(ModelExport.descriptionFor(e));
		}).then(d -> chooseBase(ed[0], d)).then(r -> null, err -> {
			ModelExport.report(err);
			return null;
		}));
	}

	/** dialog: base image + meter; then generate (inline) or the gist wizard (link) */
	static Promise<Void> chooseBase(Editor e, ModelDescription d) {
		return new Promise<>((res, rej) -> {
			Meter m;
			try {
				m = measure(e, d);
			} catch (Throwable t) {
				rej.onInvoke(t);
				return;
			}
			Image proj = projectTexture(e);
			Image[] picked = new Image[1];
			Map<String, String> opts = new LinkedHashMap<>();
			if (proj != null)opts.put(BASE_PROJECT, "cpmplus.w3modelexport.skin.base.project");
			opts.put(BASE_FILE, "cpmplus.w3modelexport.skin.base.file");
			opts.put(BASE_DEFAULT, e.skinType == SkinType.SLIM ? "cpmplus.w3modelexport.skin.base.alex" : "cpmplus.w3modelexport.skin.base.steve");
			List<FormRow> rows = new ArrayList<>();
			rows.add(FormRow.info("who", "cpmplus.w3modelexport.skin.whoLabel", "cpmplus.w3modelexport.skin.who"));
			rows.add(FormRow.info("meter", "cpmplus.w3modelexport.skin.meterLabel", meterText(m)));
			rows.add(FormRow.radio("base", "cpmplus.w3modelexport.skin.base", opts, proj != null ? BASE_PROJECT : BASE_DEFAULT));
			rows.add(FormRow.buttons("pick", "", new String[] {"cpmplus.w3modelexport.skin.pickPng"}, i -> pickBase(img -> {
				picked[0] = img;
				String hint = armHint(img, m.type);
				PlusDialogs.quick(I18n.get("cpmplus.w3modelexport.skin.pickedOk") + (hint != null ? " " + hint : ""), 4000);
			})).when(r -> BASE_FILE.equals(r.getString("base"))));
			rows.add(FormRow.info("final", "", "cpmplus.w3modelexport.skin.finalNote"));
			PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
			fo.confirmKey = m.fits ? "cpmplus.w3modelexport.skin.make" : "cpmplus.w3modelexport.skin.next";
			fo.cancelKey = "cpmplus.w3modelexport.btn.cancel";
			fo.width = 560;
			fo.onCancel = () -> rej.onInvoke(new ModelExport.Cancelled());
			PlusDialogs.form("cpmplus_w3modelexport_skin", "cpmplus.w3modelexport.skin.title", rows, fr -> {
				String b = fr.getString("base");
				Image base = BASE_PROJECT.equals(b) ? proj : BASE_FILE.equals(b) ? picked[0] : defaultSkin(e);
				if (base == null) {
					fr.keepOpen();
					PlusDialogs.quick(I18n.get("cpmplus.w3modelexport.skin.pickFirst"), 3000);
					return;
				}
				Promise<Link> lp = m.fits ? Promise.resolve((Link) null) : later(50).then(x -> GistWizard.open(m.b64, savedLink()));
				PlusTestHooks.track(lp).then(link -> {
					Result r;
					try {
						r = generate(m, base, link);
					} catch (Throwable t) {
						return Promise.<String>reject(t);
					}
					if (link != null)storeLink(link, m.b64);
					return save(r).then(n -> {
						showDone(r, n);
						return Promise.resolve(n);
					});
				}).then(x -> {
					res.onInvoke((Void) null);
					return null;
				}, err -> {
					rej.onInvoke(err);
					return null;
				});
			}, fo);
		});
	}

	static String meterText(Meter m) {
		if (m.fits)return I18n.format("cpmplus.w3modelexport.skin.fits", ModelExport.kb(m.needed), ModelExport.kb(m.capacity));
		return I18n.format("cpmplus.w3modelexport.skin.noFit", ModelExport.kb(m.needed - m.capacity));
	}

	/** asks for a PNG; 64x64 only (64x32 = old format: 'Trocar skin base'; anything else refused) */
	static void pickBase(java.util.function.Consumer<Image> out) {
		Blockbench.ImportProperties ip = new Blockbench.ImportProperties();
		ip.extensions = new String[] {"png"};
		ip.type = "PNG";
		ip.readtype = "binary";
		ip.resource_id = "cpmplus_skin_base";
		PlusDialogs.importFiles(ip, files -> {
			if (files == null || files.length == 0)return;
			Object c = Js.asPropertyMap(files[0]).get("content");
			if (!(c instanceof ArrayBuffer)) {
				PlusDialogs.block(I18n.get("cpmplus.w3modelexport.skin.readFailed"));
				return;
			}
			decodePng(NativeFs.toBytes((ArrayBuffer) c)).then(img -> {
				String err = baseError(img);
				if (err != null)PlusDialogs.block(err);
				else out.accept(img);
				return null;
			}, e -> {
				PlusDialogs.block(I18n.get("cpmplus.w3modelexport.skin.readFailed"));
				return null;
			});
		});
	}

	/** null when the image can be a base, else the reason (never rescaled) */
	public static String baseError(Image img) {
		if (img.getWidth() == 64 && img.getHeight() == 64)return null;
		if (img.getWidth() == 64 && img.getHeight() == 32)return I18n.get("cpmplus.w3modelexport.skin.legacy");
		return I18n.format("cpmplus.w3modelexport.skin.badSize", img.getWidth(), img.getHeight());
	}

	/** download / save dialog of the PNG ('&lt;slug&gt;_skin.png') */
	static Promise<String> save(Result r) {
		return new Promise<>((res, rej) -> {
			String name = ModelExport.slug(ModelExport.projectName(), "model") + "_skin";
			Blockbench.ExportProperties pr = new Blockbench.ExportProperties();
			pr.type = "PNG";
			pr.extensions = new String[] {"png"};
			pr.name = name;
			pr.resource_id = "cpmplus_skin";
			pr.binaryContent = NativeFs.blob(r.png, "image/png");
			if (!PlusDialogs.export(pr, p -> {
			})) {
				rej.onInvoke(new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.error.noWrite")));
				return;
			}
			res.onInvoke(name + ".png");
		});
	}

	static void showDone(Result r, String fileName) {
		if (PlusTestHooks.active()) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("file", fileName);
			m.set("fits", r.meter.fits);
			m.set("needed", (double) r.meter.needed);
			m.set("capacity", (double) r.meter.capacity);
			m.set("link", r.link != null ? r.link.toString() : null);
			m.set("skinType", r.check.skinType);
			m.set("png", Base64.getEncoder().encodeToString(r.png));
			PlusTestHooks.setLast("w3modelexport.skin", m);
		}
		DomGlobal.console.log("CPM+: skin written " + fileName + " (" + r.check.bytes + " bytes of model data, " + r.check.skinType
				+ (r.link != null ? ", link " + r.link : "") + ")");
		Dialog.DialogProperties p = new Dialog.DialogProperties();
		p.id = "cpmplus_w3modelexport_skin_done";
		p.title = I18n.get("cpmplus.w3modelexport.skin.doneTitle");
		String img = "<img alt='' style='image-rendering: pixelated; width: 128px; height: 128px; border: 1px solid var(--color-border, #444);' src='data:image/png;base64,"
				+ Base64.getEncoder().encodeToString(r.png) + "'>";
		p.setHtml("<p>" + PlusDialogs.escLines(I18n.format("cpmplus.w3modelexport.skin.done", fileName)) + "</p>",
				"<p>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.skin.who")) + "</p>",
				"<div style='display: flex; gap: 12px; align-items: center;'>" + img + "<span>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.skin.previewNote")) + "</span></div>",
				"<p>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.skin.finalNote")) + "</p>");
		p.singleButton = true;
		new Dialog(p).show();
	}

	/** resolves after ms (lets a closing dialog finish before the next one opens) */
	static Promise<Object> later(int ms) {
		return new Promise<>((res, rej) -> DomGlobal.setTimeout(a -> res.onInvoke((Object) null), ms));
	}

	// ====================================================================== 'Conferir uma skin'

	/** CPM+ &gt; Conferir uma skin\u2026: what a PNG carries (model / link / nothing) */
	public static void checkSkinAction() {
		Blockbench.ImportProperties ip = new Blockbench.ImportProperties();
		ip.extensions = new String[] {"png"};
		ip.type = "PNG";
		ip.readtype = "binary";
		ip.resource_id = "cpmplus_skin_check";
		PlusDialogs.importFiles(ip, files -> {
			if (files == null || files.length == 0)return;
			Object c = Js.asPropertyMap(files[0]).get("content");
			String name = files[0].name != null ? files[0].name : "skin.png";
			if (!(c instanceof ArrayBuffer)) {
				PlusDialogs.block(I18n.get("cpmplus.w3modelexport.skin.readFailed"));
				return;
			}
			PlusTestHooks.track(decodePng(NativeFs.toBytes((ArrayBuffer) c)).then(img -> {
				showInspect(name, img);
				return null;
			}, e -> {
				PlusDialogs.block(I18n.get("cpmplus.w3modelexport.skin.readFailed"));
				return null;
			}));
		});
	}

	/** the answer text for one PNG (also used by the test tool) */
	public static String describe(Image img) {
		if (img.getWidth() != 64 || img.getHeight() != 64)return I18n.format("cpmplus.w3modelexport.check.notSkin", img.getWidth(), img.getHeight());
		Inspect r = inspect(img);
		if (!r.hasData)return I18n.get("cpmplus.w3modelexport.check.noData");
		if (!r.checksumOk)return I18n.get("cpmplus.w3modelexport.check.broken");
		boolean slim = "slim".equals(r.skinType);
		String type = I18n.get(slim ? "cpmplus.w3modelexport.check.slim" : "cpmplus.w3modelexport.check.classic");
		if (r.link != null)return I18n.format("cpmplus.w3modelexport.check.link", r.link, type);
		return I18n.format("cpmplus.w3modelexport.check.model", ModelExport.kb(r.bytes), type);
	}

	static void showInspect(String name, Image img) {
		String text = describe(img);
		if (PlusTestHooks.active()) {
			Inspect r = img.getWidth() == 64 && img.getHeight() == 64 ? inspect(img) : new Inspect();
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("text", text);
			m.set("hasData", r.hasData);
			m.set("checksumOk", r.checksumOk);
			m.set("skinType", r.skinType);
			m.set("link", r.link);
			m.set("bytes", (double) r.bytes);
			PlusTestHooks.setLast("w3modelexport.check", m);
		}
		List<String> lines = new ArrayList<>();
		lines.add(name);
		lines.add(text);
		PlusDialogs.info("cpmplus_w3modelexport_check", "cpmplus.w3modelexport.check.title", lines);
	}

	// ====================================================================== gist wizard (also used by the .cpmmodel export)

	/**
	 * 3 steps: copy the data, publish a PUBLIC gist with one file, paste its address. The address is validated with
	 * CPM's own link rules (git / pb only; 'p' needs a Minecraft login and is not offered), the raw content is
	 * downloaded and must be EXACTLY the data ('Gist confere') before the link can be used. Specific errors: not
	 * found / private, old content, a newline or spaces around the text, extra text, wrong file.
	 */
	public static final class GistWizard {
		private GistWizard() {
		}

		/** resolves with the verified link; rejects with ModelExport.Cancelled when closed */
		public static Promise<Link> open(String b64, String savedLink) {
			return new Promise<>((res, rej) -> {
				Link[] ok = new Link[1];
				Dialog[] dref = new Dialog[1];
				boolean[] closed = new boolean[1];
				String saved = savedLink != null ? pageUrl(savedLink) : "";
				List<FormRow> rows = new ArrayList<>();
				rows.add(FormRow.info("s1", "cpmplus.w3modelexport.gist.step1", I18n.format("cpmplus.w3modelexport.gist.step1.text", ModelExport.kb(b64.length()))));
				rows.add(FormRow.buttons("copy", "", new String[] {"cpmplus.w3modelexport.gist.copy"}, i -> PlusDialogs.copyText(b64)));
				rows.add(FormRow.info("s2", "cpmplus.w3modelexport.gist.step2", "cpmplus.w3modelexport.gist.step2.text"));
				rows.add(FormRow.buttons("open", "", new String[] {"cpmplus.w3modelexport.gist.openSite"}, i -> openUrl("https://gist.github.com/")));
				rows.add(FormRow.text("url", "cpmplus.w3modelexport.gist.step3", saved).placeholder("https://gist.github.com/<nome>/<id>"));
				rows.add(FormRow.buttons("check", "", new String[] {"cpmplus.w3modelexport.gist.check"}, i -> {
					PlusDialogs.FormResult fr = new PlusDialogs.FormResult(dref[0] != null ? dref[0].getFormResult() : null);
					ok[0] = null;
					status(I18n.get("cpmplus.w3modelexport.gist.checking"), null);
					PlusTestHooks.track(verify(fr.getString("url"), b64).then(l -> {
						ok[0] = l;
						status(I18n.get("cpmplus.w3modelexport.gist.ok"), "#4caf50");
						return null;
					}, err -> {
						status(err instanceof ModelExport.UserError ? ((ModelExport.UserError) err).getMessage() : String.valueOf(err), "#f44336");
						return null;
					}));
				}));
				PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
				fo.confirmKey = "cpmplus.w3modelexport.gist.use";
				fo.cancelKey = "cpmplus.w3modelexport.btn.cancel";
				fo.width = 560;
				String outdated = savedLink != null && savedHash() != null && !savedHash().equals(ProjectData.hash(b64))
						? "<p style='color: var(--color-warning, #e6a23c);'>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.gist.outdated")) + "</p>" : "";
				fo.htmlLines = new String[] {"<p>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.gist.intro")) + "</p>" + outdated,
						"<p id='cpmplus_w3me_gist_status' style='font-weight: bold;'></p>"};
				fo.onCancel = () -> {
					if (!closed[0]) {
						closed[0] = true;
						rej.onInvoke(new ModelExport.Cancelled());
					}
				};
				dref[0] = PlusDialogs.form("cpmplus_w3modelexport_gist", "cpmplus.w3modelexport.gist.title", rows, fr -> {
					if (ok[0] == null) {
						fr.keepOpen();
						status(I18n.get("cpmplus.w3modelexport.gist.checkFirst"), "#f44336");
						return;
					}
					closed[0] = true;
					res.onInvoke(ok[0]);
				}, fo);
			});
		}

		private static void status(String text, String color) {
			HTMLElement el = (HTMLElement) DomGlobal.document.getElementById("cpmplus_w3me_gist_status");
			if (el == null)return;
			el.textContent = text;
			el.style.color = color != null ? color : "";
		}

		private static native void openUrl(String url) /*-{
			try {
				if ($wnd.Blockbench && typeof $wnd.Blockbench.openLink === 'function') $wnd.Blockbench.openLink(url);
				else $wnd.open(url, '_blank');
			} catch (e) {
			}
		}-*/;

		/** the page address of a stored link ('git:a/b' -&gt; https://gist.github.com/a/b) */
		static String pageUrl(String link) {
			if (link.startsWith("git:"))return "https://gist.github.com/" + link.substring(4);
			if (link.startsWith("pb:"))return "https://pastebin.com/" + link.substring(3);
			return link;
		}

		/** validates the address with CPM's link rules; git and pb only */
		public static Link parse(String url) {
			String u = url != null ? url.trim() : "";
			while (u.length() > 1 && (u.startsWith("\"") || u.startsWith("'")) && (u.endsWith("\"") || u.endsWith("'")))u = u.substring(1, u.length() - 1).trim();
			if (u.isEmpty())throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.gist.empty"));
			// raw address -> page address (what CPM's validator accepts)
			RegExpResult raw = new JsRegExp("^https?://gist\\.githubusercontent\\.com/([A-Za-z0-9_\\-]+)/([0-9A-Za-z]+)(/.*)?$").exec(u);
			if (raw != null)u = "https://gist.github.com/" + raw.getAt(1) + "/" + raw.getAt(2);
			RegExpResult pbRaw = new JsRegExp("^https?://pastebin\\.com/raw/([A-Za-z0-9_\\-]+)/?$").exec(u);
			if (pbRaw != null)u = "https://pastebin.com/" + pbRaw.getAt(1);
			if (u.startsWith("http://"))u = "https://" + u.substring(7);
			while (u.endsWith("/"))u = u.substring(0, u.length() - 1);
			Link l = null;
			try {
				l = ModelDefinitionLoader.parseLink(u);
			} catch (Throwable e) {
				l = null;
			}
			if (l == null) {
				// same rules as CPM's validators (GistResourceLoader / PastebinResourceLoader)
				RegExpResult g = new JsRegExp("^https://gist\\.github\\.com/([a-zA-Z_0-9\\-]+)/(\\w+)#?[a-zA-Z_0-9\\-]*$").exec(u);
				RegExpResult p = new JsRegExp("^https://pastebin\\.com/([a-zA-Z_0-9\\-]+)$").exec(u);
				if (g != null)l = new Link("git", g.getAt(1) + "/" + g.getAt(2));
				else if (p != null)l = new Link("pb", p.getAt(1));
			}
			if (l == null)throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.gist.badUrl"));
			if ("p".equals(l.getLoader()))throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.gist.pasteNeedsLogin"));
			if (!"git".equals(l.getLoader()) && !"pb".equals(l.getLoader()))throw new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.gist.badUrl"));
			return l;
		}

		/** the address CPM downloads for the link (test mode: the harness' local server) */
		static String rawUrl(Link l) {
			String base = null;
			if (PlusTestHooks.enabled()) {
				Object v = Js.asPropertyMap(DomGlobal.window).get("__cpmplus_w3modelexport_gistBase");
				if (v != null && "string".equals(Js.typeof(v)))base = (String) v;
			}
			if ("git".equals(l.getLoader()))return (base != null ? base : "https://gist.githubusercontent.com") + "/" + l.getPath() + "/raw";
			return (base != null ? base + "/pb" : "https://pastebin.com") + "/raw/" + l.getPath();
		}

		/** downloads the published text and compares it with the data; resolves with the link when EXACTLY equal */
		public static Promise<Link> verify(String url, String b64) {
			Link l;
			try {
				l = parse(url);
			} catch (Throwable e) {
				return Promise.reject(e);
			}
			String raw = rawUrl(l);
			return DomGlobal.fetch(raw + (raw.contains("?") ? "&" : "?") + "t=" + (long) elemental2.core.JsDate.now()).then(r -> {
				if (r.status == 404)return Promise.<String>reject(new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.gist.notFound")));
				if (!r.ok)return Promise.<String>reject(new ModelExport.UserError(I18n.format("cpmplus.w3modelexport.gist.httpError", (int) r.status)));
				return r.text();
			}, err -> Promise.<String>reject(new ModelExport.UserError(I18n.get("cpmplus.w3modelexport.gist.network")))).then(text -> {
				String err = compare(text, b64);
				if (err != null)return Promise.<Link>reject(new ModelExport.UserError(err));
				return Promise.resolve(l);
			});
		}

		/** null when the downloaded text is exactly the data, else the specific problem (pt-BR) */
		public static String compare(String text, String b64) {
			if (text == null)return I18n.get("cpmplus.w3modelexport.gist.wrongFile");
			if (text.equals(b64))return null;
			String t = text.trim();
			if (t.equals(b64))return I18n.get("cpmplus.w3modelexport.gist.whitespace");
			String q = t;
			while (q.length() > 1 && (q.startsWith("\"") || q.startsWith("'")) && (q.endsWith("\"") || q.endsWith("'")))q = q.substring(1, q.length() - 1);
			if (q.equals(b64))return I18n.get("cpmplus.w3modelexport.gist.quotes");
			String old = savedHash();
			if (old != null && old.equals(ProjectData.hash(t)))return I18n.get("cpmplus.w3modelexport.gist.oldContent");
			if (t.contains(b64))return I18n.get("cpmplus.w3modelexport.gist.extraText");
			if (t.matches("[A-Za-z0-9+/=\\s]+") && t.startsWith("U"))return I18n.get("cpmplus.w3modelexport.gist.otherModel");
			return I18n.get("cpmplus.w3modelexport.gist.wrongFile");
		}
	}

	// ====================================================================== QA (test mode tools)

	/** 'measure': the meter of the open project; 'generate': + the skin (base 'default' | 'project', link when it overflows), saved through Blockbench.export */
	public static Promise<Object> qaTool(String op, String base, Link link, JsPropertyMap<Object> r) {
		return ModelExport.prepareEditor(false, false).then(e -> {
			ModelDescription d = ModelExport.descriptionFor(e);
			if (ModelExport.lockedWithoutOwner(d))d = ModelExport.unlocked(d);
			Meter m;
			try {
				m = measure(e, d);
			} catch (Throwable t) {
				return Promise.<Object>reject(t);
			}
			r.set("needed", (double) m.needed);
			r.set("capacity", (double) m.capacity);
			r.set("fits", m.fits);
			r.set("overflowBytes", (double) m.overflowBytes);
			r.set("b64", m.b64);
			r.set("skinType", m.type == SkinType.SLIM ? "slim" : "classic");
			r.set("meterText", meterText(m));
			if (!"generate".equals(op))return Promise.<Object>resolve(r);
			Image b = "project".equals(base) ? projectTexture(e) : defaultSkin(e);
			if (b == null)return Promise.<Object>reject(new ModelExport.UserError("no 64x64 base"));
			Result res;
			try {
				res = generate(m, b, link);
			} catch (Throwable t) {
				return Promise.<Object>reject(t);
			}
			if (link != null)storeLink(link, m.b64);
			r.set("png", Base64.getEncoder().encodeToString(res.png));
			r.set("checkBytes", (double) res.check.bytes);
			r.set("checkSkinType", res.check.skinType);
			r.set("checkLink", res.check.link);
			return save(res).then(n -> {
				r.set("file", n);
				return Promise.<Object>resolve(r);
			});
		});
	}
}