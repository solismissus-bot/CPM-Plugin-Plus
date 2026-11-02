package com.tom.cpm.blockbench.tools.export;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import com.tom.cpl.gui.Frame;
import com.tom.cpl.gui.elements.PopupPanel;
import com.tom.cpl.util.Image;
import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.MenuBar.BarItem;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.tools.settings.SkinLayersDialog;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.NativeFs;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Exporter;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.util.GetFreeSkinSlots;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.web.client.WebMC;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.ImageIO;

import elemental2.core.JsDate;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-MODELEXPORT, C4 + G4 Part A): "Exportar modelo (.cpmmodel)" - the model file the game lists in Gestos &gt;
 * Modelos and loads with /cpmclient set_model, written straight from Blockbench.
 *
 * <p>Reusable steps (G23 'Testar no jogo agora' and the G45 skin card use them too):
 * {@link #prepareEditor(boolean, boolean)} (headless conversion + the skin-layer choice for custom animations),
 * {@link #descriptionFor(Editor)} (a COPY of the project's description: name / desc / icon / copy protection / lock
 * uuid are never changed), {@link #resolveLock(ModelDescription)} (a UUID lock without an owner never reaches the
 * exporter), {@link #buildModelBytes(Editor, ModelDescription, OverflowMode)}, {@link #verifyModelFile(byte[])} and
 * {@link #writeDesktop(String, byte[])}.</p>
 */
public final class ModelExport {
	/** PlusPrefs key of the remembered player_models folder (desktop) */
	public static final String PREF_DIR = "playerModelsDir";
	/** PlusPrefs key: files this plugin wrote (path -&gt; true), so they are replaced without asking */
	static final String PREF_WRITTEN = "cpmplus.writtenModels";
	/** CPM's inline limit of a .cpmmodel data block (Exporter.ModelWriter buffer) */
	public static final int INLINE_LIMIT = 30 * 1024;

	/** what to do with the part of the model that does not fit inline */
	public enum OverflowMode {
		/** ask: publish in a gist (checked) or a local test link */
		ASK,
		/** local test link 'local:cpmplus&lt;time&gt;', the data stays inside the file (G23) */
		LOCAL
	}

	/** an export the user stopped on purpose (nothing was written); never shown as an error */
	public static final class Cancelled extends PopupDialogs.CancelException {
		private static final long serialVersionUID = 1L;
	}

	/** an error with a user message (already translated) */
	public static final class UserError extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public UserError(String msg) {
			super(msg);
		}
	}

	// test observations (window.__cpmplus tools)
	static int layerDialogOpens;
	static int exporterCalls;
	static String lastPopupClass;

	private ModelExport() {
	}

	// ====================================================================== slug

	private static final String ACCENTS = "\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u0101\u0103\u0105\u00e7\u0107\u0109\u010b\u010d\u010f\u0111\u00e8\u00e9\u00ea\u00eb\u0113\u0115\u0117\u0119\u011b\u011d\u011f\u0121\u0123\u0125\u0127\u00ec\u00ed\u00ee\u00ef\u0129\u012b\u012d\u012f\u0131\u0135\u0137\u013a\u013c\u013e\u0140\u0142\u00f1\u0144\u0146\u0148\u0149\u00f2\u00f3\u00f4\u00f5\u00f6\u00f8\u014d\u014f\u0151\u0155\u0157\u0159\u015b\u015d\u015f\u0161\u0163\u0165\u0167\u00f9\u00fa\u00fb\u00fc\u0169\u016b\u016d\u016f\u0171\u0173\u0175\u00fd\u00ff\u0177\u017a\u017c\u017e\u00df";
	private static final String PLAIN = "aaaaaaaaacccccddeeeeeeeeegggghhiiiiiiiiijklllllnnnnnooooooooorrrsssstttuuuuuuuuuuwyyyzzzs";

	/**
	 * File-name slug: lower case, accents folded to ASCII, every other character -&gt; '_', runs of '_' collapsed, no
	 * '_' / '-' at the ends; only [a-z0-9_-] (Brigadier's unquoted string accepts it as one argument).
	 * slug("Drag\u00e3o Filhote Roxo!") == "dragao_filhote_roxo". Empty -&gt; fallback.
	 */
	public static String slug(String name, String fallback) {
		if (name == null)return fallback;
		String s = name.toLowerCase();
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int a = ACCENTS.indexOf(c);
			if (a >= 0 && a < PLAIN.length())c = PLAIN.charAt(a);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
			if (!ok)c = '_';
			if (c == '_' && sb.length() > 0 && sb.charAt(sb.length() - 1) == '_')continue;
			sb.append(c);
		}
		int b = 0, e = sb.length();
		while (b < e && (sb.charAt(b) == '_' || sb.charAt(b) == '-'))b++;
		while (e > b && (sb.charAt(e - 1) == '_' || sb.charAt(e - 1) == '-'))e--;
		String r = sb.substring(b, e);
		if (r.length() > 64)r = r.substring(0, 64);
		return r.isEmpty() ? fallback : r;
	}

	static String projectName() {
		String n = Project.name;
		return n != null && !n.trim().isEmpty() ? n.trim() : I18n.get("cpmplus.w3modelexport.defaultName");
	}

	static final boolean ICON_DISABLED = true;

	// ====================================================================== description (G4 Part A)

	/**
	 * A COPY of the model description to export: the project's description (name, desc, camera, copy protection, lock
	 * uuid as set by the project settings) with the name filled from Project.name only when empty, and the project
	 * icon (desc_icon.png) when there is one. The project's own description is never changed.
	 */
	public static ModelDescription descriptionFor(Editor e) {
		ModelDescription src = e != null ? e.description : null;
		if (src == null) {
			try {
				src = Project.getData().description;
			} catch (Throwable ex) {
				src = null;
			}
		}
		ModelDescription d = copy(src);
		if (d.uuid == null)d.uuid = uuidFromProject();
		if (d.name == null || d.name.trim().isEmpty())d.name = projectName();
		if (d.desc == null)d.desc = "";
		if (d.copyProtection == null)d.copyProtection = CopyProtection.NORMAL;
		if (d.icon == null)d.icon = projectIcon();
		if (ICON_DISABLED)d.icon = null;
		return d;
	}

	/** shallow copy (the camera and the icon are shared, never modified) */
	public static ModelDescription copy(ModelDescription src) {
		ModelDescription d = new ModelDescription();
		if (src != null) {
			d.name = src.name;
			d.desc = src.desc;
			d.icon = src.icon;
			if (src.camera != null)d.camera = src.camera;
			d.copyProtection = src.copyProtection;
			d.uuid = src.uuid;
		}
		return d;
	}

	/** the lock owner a project setting may keep in the description extras ('uuid'), null when absent / invalid */
	private static UUID uuidFromProject() {
		try {
			Object v = Project.getData().getDescExtra("uuid");
			if (v instanceof String && ((String) v).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
				UUID u = UUID.fromString((String) v);
				if (u.getMostSignificantBits() != 0 || u.getLeastSignificantBits() != 0)return u;
			}
		} catch (Throwable e) {
		}
		return null;
	}

	/** desc_icon.png of the project (kept from the imported .cpmproject), null when absent / unreadable / too big */
	private static Image projectIcon() {
		// CPM+ beta: the icon is left out of the .cpmmodel for now. Since the faster PNG reader (load A4) the icon is
		// actually decoded, and writing it from the browser broke the file checksum (read back: "Sum error"); the
		// file is only correct without it. The icon is just the thumbnail in the game's model list.
		if (ICON_DISABLED)return null;
		try {
			String b64 = Project.getData().rawFiles.get("desc_icon.png");
			if (b64 == null || b64.isEmpty())return null;
			Image img = ImageIO.load(new ByteArrayInputStream(Base64.getDecoder().decode(b64)));
			if (img.getWidth() > 256 || img.getHeight() > 256 || img.getWidth() < 1)return null;// CPM refuses icons over 256 px
			return img;
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: model icon not used (" + e + ")");
			return null;
		}
	}

	/** UUID lock without an owner: exported from here it would be locked to a throw-away UUID (nobody could use it) */
	public static boolean lockedWithoutOwner(ModelDescription d) {
		return d != null && d.copyProtection == CopyProtection.UUID_LOCK && d.uuid == null;
	}

	/** the description without any lock (test copies, 'Exportar sem trava'); the given one is not changed */
	public static ModelDescription unlocked(ModelDescription d) {
		ModelDescription c = copy(d);
		c.copyProtection = CopyProtection.NORMAL;
		c.uuid = null;
		return c;
	}

	/**
	 * G4 Part A: resolves with the description to export. A UUID lock without an owner shows 'Trava sem dono definido'
	 * [Definir cliente\u2026] [Exportar sem trava] [Cancelar] and the exporter is never reached unless 'Exportar sem trava'
	 * is chosen (then a NORMAL copy). Rejects with {@link Cancelled} otherwise.
	 */
	public static Promise<ModelDescription> resolveLock(ModelDescription d) {
		if (!lockedWithoutOwner(d))return Promise.resolve(d);
		return new Promise<>((res, rej) -> {
			Dialog.DialogProperties p = new Dialog.DialogProperties();
			p.id = "cpmplus_w3modelexport_lock";
			p.title = I18n.get("cpmplus.w3modelexport.lock.title");
			p.setHtml("<p>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.lock.text")) + "</p>",
					"<p style='opacity: 0.75;'>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.lock.note")) + "</p>");
			p.buttons = new String[] {I18n.get("cpmplus.w3modelexport.lock.defineClient"), I18n.get("cpmplus.w3modelexport.lock.exportUnlocked"), I18n.get("cpmplus.w3modelexport.btn.cancel")};
			// Enter / an unanswered dialog = Cancelar (the safe choice)
			p.confirmIndex = 2;
			p.cancelIndex = 2;
			boolean[] done = new boolean[1];
			p.onButton = i -> {
				if (done[0])return true;
				done[0] = true;
				if (i == 1) {
					res.onInvoke(unlocked(d));
				} else {
					rej.onInvoke(new Cancelled());
					if (i == 0)DomGlobal.setTimeout(a -> openClientSettings(), 50);
				}
				return true;
			};
			p.onCancel = () -> {
				if (!done[0]) {
					done[0] = true;
					rej.onInvoke(new Cancelled());
				}
				return true;
			};
			new Dialog(p).show();
		});
	}

	/** 'Definir cliente\u2026': the project settings that hold the lock owner (W3-SETTINGS); a guide when not found */
	static void openClientSettings() {
		try {
			BarItem best = null;
			Map<String, BarItem> items = new LinkedHashMap<>();
			JsPropertyMap<BarItem> all = Global.getBarItems();
			if (all != null)all.forEach(k -> {
				if (k != null && k.startsWith("cpmplus_w3settings"))items.put(k, all.get(k));
			});
			for (Map.Entry<String, BarItem> en : items.entrySet()) {
				String k = en.getKey().toLowerCase();
				if (k.contains("desc") || k.contains("f19") || k.contains("copy") || k.contains("lock") || k.contains("client")) {
					best = en.getValue();
					break;
				}
			}
			if (best != null && Global.hasFunction(best, "trigger")) {
				Js.<com.tom.cpm.blockbench.proxy.Action>uncheckedCast(best).trigger(null);
				return;
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: project settings not found", e);
		}
		PlusDialogs.info("cpmplus_w3modelexport_lock_help", "cpmplus.w3modelexport.lock.title", java.util.Arrays.asList("cpmplus.w3modelexport.lock.howTo"));
	}

	// ====================================================================== editor + skin layers

	/** custom (non-vanilla-pose) animations need skin layers (anim_enc) to be told apart in game */
	public static boolean needsLayers(Editor e) {
		return e.animEnc == null && !e.animations.isEmpty() && e.animations.stream().anyMatch(EditorAnim::isCustom);
	}

	/**
	 * Headless CPM editor of the project (ProjectConvert.buildEditor). showWarnings: the export warning dialog first
	 * (cancel = {@link PopupDialogs.CancelException}). askLayers: when custom animations have no skin layers yet, the
	 * skin-layer dialog (SkinLayersDialog, the only one) is opened ONCE and its choice is stored in the project
	 * (ProjectData.animations free/def), so the next export does not ask again; false: CPM's default for this export
	 * only (GetFreeSkinSlots.getDefault, nothing stored - test copies).
	 */
	public static Promise<Editor> prepareEditor(boolean showWarnings, boolean askLayers) {
		return ProjectConvert.buildEditor(showWarnings).then(e -> {
			if (!needsLayers(e))return Promise.resolve(e);
			if (!askLayers) {
				if (e.vanillaSkin != null)e.animEnc = GetFreeSkinSlots.getDefault(e);
				return Promise.resolve(e);
			}
			layerDialogOpens++;
			return SkinLayersDialog.open(e).then(ok -> {
				if (!Boolean.TRUE.equals(ok))return Promise.<Editor>reject(new Cancelled());
				return ProjectConvert.buildEditor(false).then(e2 -> {
					if (!needsLayers(e2))return Promise.resolve(e2);
					// the dialog closed without storing a choice: store CPM's default once (same as the in-game export)
					storeDefaultLayers(e2);
					return ProjectConvert.buildEditor(false);
				});
			});
		});
	}

	/** CPM's default skin layers for custom animations, stored in the project (one 'Desfazer' toast) */
	static void storeDefaultLayers(Editor e) {
		if (e.vanillaSkin == null)return;
		com.tom.cpm.shared.editor.anim.AnimationEncodingData enc = GetFreeSkinSlots.getDefault(e);
		ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
		ProjectData pd = Project.getData();
		java.util.List<Object> free = new java.util.ArrayList<>();
		Map<String, Object> def = new LinkedHashMap<>();
		for (com.tom.cpm.shared.editor.util.PlayerSkinLayer l : com.tom.cpm.shared.editor.util.PlayerSkinLayer.VALUES) {
			if (enc.freeLayers.contains(l)) {
				free.add(l.getLowerName());
				def.put(l.getLowerName(), false);
			} else {
				def.put(l.getLowerName(), true);
			}
		}
		if (!(pd.animations.get("anims") instanceof Map))pd.animations.put("anims", new LinkedHashMap<String, Object>());
		pd.animations.put("free", free);
		pd.animations.put("def", def);
		pd.flush();
		Project.saved = false;
		ProjectDataUndo.offerUndo(before, I18n.get("cpmplus.w3modelexport.layers.stored"));
	}

	/** the layer rule CPM's exporter enforces (more layer-triggered animations than free layers = export error) */
	static String layerError(Editor e) {
		try {
			LayerCapacity.Result r = LayerCapacity.check(e);
			return r != null ? r.errorKey : null;
		} catch (Throwable ex) {
			return null;
		}
	}

	// ====================================================================== bytes

	/** UI for the exporter: its error popups become a rejected export instead of a hidden CPM window */
	static final class CapturingUI extends BBUI {
		private final Consumer<Object> fail;

		CapturingUI(Consumer<Object> fail) {
			this.fail = fail;
		}

		@Override
		public void displayMessagePopup(String title, String text, String closeBtn) {
			fail.accept(new UserError(text));
		}

		@Override
		public void onGuiException(String msg, Throwable e, boolean fatal) {
			super.onGuiException(msg, e, fatal);
			fail.accept(e != null ? e : new UserError(msg));
		}

		@Override
		public void displayPopup(Function<Frame, PopupPanel> factory) {
			String cls = popupClass(factory);
			DomGlobal.console.warn("CPM+: popup " + cls + " not supported");
			lastPopupClass = cls;
			fail.accept(new UserError(I18n.format("cpmplus.w3modelexport.popup.unsupported", cls)));
		}
	}

	/**
	 * The .cpmmodel bytes (Exporter.exportModelTo into memory). desc = the description to write (name, desc, icon) and
	 * the copy protection the exporter applies (set on the editor only while the exporter runs, restored in finally
	 * even when it throws). The bytes are checked with CPM's own ModelFile reader before the promise resolves.
	 */
	public static Promise<byte[]> buildModelBytes(Editor e, ModelDescription desc, OverflowMode mode) {
		if (lockedWithoutOwner(desc))return Promise.reject(new UserError(I18n.get("cpmplus.w3modelexport.lock.text")));
		return PlusTestHooks.track(new Promise<>((res, rej) -> {
			boolean[] settled = new boolean[1];
			Consumer<Object> fail = err -> {
				if (settled[0])return;
				settled[0] = true;
				rej.onInvoke(err);
			};
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			OutputStream sink = new OutputStream() {
				@Override
				public void write(int b) {
					bout.write(b);
				}

				@Override
				public void write(byte[] b, int off, int len) {
					bout.write(b, off, len);
				}

				@Override
				public void close() {
					if (settled[0])return;
					byte[] bytes = bout.toByteArray();
					Verify v = verifyModelFile(bytes);
					if (!v.ok) {
						fail.accept(new UserError(I18n.format("cpmplus.w3modelexport.error.verify", v.error)));
						return;
					}
					settled[0] = true;
					res.onInvoke(bytes);
				}
			};
			CapturingUI ui = new CapturingUI(fail);
			ModelDescription old = e.description;
			try {
				e.description = desc;
				testThrowHook();
				exporterCalls++;
				boolean ok = Exporter.exportModelTo(e, ui, desc, () -> sink, (data, linkOut) -> {
					overflowLink(data, mode).then(link -> {
						if (settled[0])return null;
						try {
							linkOut.accept(link);
						} catch (Throwable t) {
							fail.accept(t);
						}
						return null;
					}, err -> {
						fail.accept(err);
						return null;
					});
				});
				if (!ok)fail.accept(new UserError(I18n.get("cpmplus.w3modelexport.error.exporter")));
			} catch (Throwable t) {
				fail.accept(t);
			} finally {
				e.description = old;
			}
		}));
	}

	/** test mode only: window.__cpmplus_w3modelexport_throw makes the export throw after the description was swapped */
	private static void testThrowHook() {
		if (!PlusTestHooks.enabled())return;
		Object v = Js.asPropertyMap(DomGlobal.window).get("__cpmplus_w3modelexport_throw");
		if (Js.isTruthy(v)) {
			Js.asPropertyMap(DomGlobal.window).delete("__cpmplus_w3modelexport_throw");
			throw new IllegalStateException("CPM+ QA: exporter failure injected");
		}
	}

	/** where the overflow part goes */
	static Promise<Link> overflowLink(byte[] data, OverflowMode mode) {
		if (mode == OverflowMode.LOCAL)return Promise.resolve(localLink());
		return new Promise<>((res, rej) -> {
			String b64 = Base64.getEncoder().encodeToString(data);
			int raw = data.length;
			int dlKb = downloadKb(raw);
			Dialog.DialogProperties p = new Dialog.DialogProperties();
			p.id = "cpmplus_w3modelexport_overflow";
			p.title = I18n.get("cpmplus.w3modelexport.overflow.title");
			p.setHtml("<p>" + PlusDialogs.escLines(I18n.format("cpmplus.w3modelexport.overflow.text", kb(raw), dlKb)) + "</p>",
					"<p>" + PlusDialogs.escLines(audienceLine(dlKb)) + "</p>",
					"<p style='opacity: 0.75;'>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.overflow.localNote")) + "</p>");
			p.buttons = new String[] {I18n.get("cpmplus.w3modelexport.overflow.gist"), I18n.get("cpmplus.w3modelexport.overflow.local"), I18n.get("cpmplus.w3modelexport.btn.cancel")};
			p.confirmIndex = 0;
			p.cancelIndex = 2;
			boolean[] done = new boolean[1];
			p.onButton = i -> {
				if (done[0])return true;
				done[0] = true;
				if (i == 0) {
					DomGlobal.setTimeout(a -> SkinExport.GistWizard.open(b64, null).then(l -> {
						res.onInvoke(l);
						return null;
					}, err -> {
						rej.onInvoke(err);
						return null;
					}), 30);
				} else if (i == 1) {
					res.onInvoke(localLink());
				} else {
					rej.onInvoke(new Cancelled());
				}
				return true;
			};
			p.onCancel = () -> {
				if (!done[0]) {
					done[0] = true;
					rej.onInvoke(new Cancelled());
				}
				return true;
			};
			new Dialog(p).show();
		});
	}

	public static Link localLink() {
		return new Link("local:cpmplus" + (long) JsDate.now());
	}

	/** the size other players download for an overflow link (base64), as CPM's safety check measures it */
	public static int downloadKb(int rawBytes) {
		long dl = 4L * ((rawBytes + 2) / 3);
		return (int) (dl / 1024);
	}

	static String kb(int bytes) {
		double k = bytes / 1024.0;
		return String.valueOf(Math.round(k * 10) / 10.0).replace('.', ',');
	}

	/** advisory only (Convention 13): who loads a link of that size with each safety profile */
	static String audienceLine(int dlKb) {
		boolean medium = dlKb <= 100;
		return I18n.format("cpmplus.w3modelexport.audience", I18n.get(medium ? "cpmplus.w3modelexport.audience.yes" : "cpmplus.w3modelexport.audience.no"));
	}

	// ====================================================================== verify

	/** result of {@link #verifyModelFile(byte[])} */
	public static final class Verify {
		public boolean ok;
		public String error, name, desc, link;
		public int dataBytes, overflowBytes;
	}

	/** reads the bytes back like the game (ModelFile.load: 'S' header, fields, icon, checksum) */
	public static Verify verifyModelFile(byte[] b) {
		Verify v = new Verify();
		try {
			if (b == null || b.length < 4)throw new IOException("empty file");
			ModelFile mf = ModelFile.load(new ByteArrayInputStream(b));
			v.name = mf.getName();
			v.desc = mf.getDesc();
			v.dataBytes = mf.getDataBlock().length;
			// the overflow link (ModelFile keeps it private): same layout read again
			ByteArrayInputStream in = new ByteArrayInputStream(b);
			in.read();
			IOHelper h = new IOHelper(in);
			h.readUTF();
			h.readUTF();
			h.readByteArray();
			byte[] ovf = h.readByteArray();
			if (ovf.length > 0) {
				v.overflowBytes = ovf.length;
				v.link = new Link(h).toString();
			}
			v.ok = true;
		} catch (Throwable e) {
			v.ok = false;
			v.error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
		}
		return v;
	}

	// ====================================================================== writing

	/** was this path written by CPM+ (no question before replacing it)? */
	static boolean writtenByUs(String path) {
		Object m = PlusPrefs.getJson(PREF_WRITTEN);
		return m instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) m).get(path.toLowerCase()));
	}

	static void markWritten(String path) {
		try {
			Object m = PlusPrefs.getJson(PREF_WRITTEN);
			Map<String, Object> map = new LinkedHashMap<>();
			if (m instanceof Map)((Map<?, ?>) m).forEach((k, v) -> map.put(String.valueOf(k), v));
			map.put(path.toLowerCase(), true);
			PlusPrefs.set(PREF_WRITTEN, map);
		} catch (Throwable e) {
		}
	}

	/**
	 * Desktop write of a .cpmmodel: atomic (.tmp + rename, retried on a Windows lock), then read back and checked with
	 * ModelFile.load. Test mode: only inside the harness folder, through PlusDialogs.writeFile (never the real disk),
	 * checked in memory. Resolves with the path; rejects with a user message.
	 */
	public static Promise<String> writeDesktop(String path, byte[] bytes) {
		lastWrite(path, bytes);
		if (PlusTestHooks.fsRoot() != null) {
			return new Promise<>((res, rej) -> {
				try {
					Blockbench.WriteProperties wp = new Blockbench.WriteProperties();
					wp.content = Js.uncheckedCast(NativeFs.toArrayBuffer(bytes));
					wp.savetype = "buffer";
					boolean w = PlusDialogs.writeFile(path, wp, p -> {
						Verify v = verifyModelFile(bytes);
						if (v.ok)res.onInvoke(path);
						else rej.onInvoke(new UserError(I18n.format("cpmplus.w3modelexport.error.verify", v.error)));
					});
					if (!w)rej.onInvoke(new UserError(I18n.get("cpmplus.w3modelexport.error.noWrite")));
				} catch (Throwable t) {
					rej.onInvoke(t);
				}
			});
		}
		String dir = parent(path);
		if (NativeFs.available()) {
			Object fs = NativeFs.fs(dir, I18n.get("cpmplus.w3modelexport.fs.permission"));
			if (fs != null) {
				NativeFs.mkdirs(fs, dir);
				return NativeFs.writeAtomic(fs, path, bytes).then(p -> {
					byte[] back = NativeFs.read(fs, path);
					Verify v = verifyModelFile(back);
					if (!v.ok || back.length != bytes.length)return Promise.reject(new UserError(I18n.format("cpmplus.w3modelexport.error.verify", v.ok ? "size" : v.error)));
					markWritten(path);
					return Promise.resolve(path);
				}, err -> Promise.reject(new UserError(I18n.format("cpmplus.w3modelexport.error.write", String.valueOf(err)))));
			}
		}
		// installed app without Node fs: Blockbench's own writer, then read back
		return new Promise<>((res, rej) -> {
			Blockbench.WriteProperties wp = new Blockbench.WriteProperties();
			wp.content = Js.uncheckedCast(NativeFs.toArrayBuffer(bytes));
			wp.savetype = "buffer";
			boolean w = PlusDialogs.writeFile(path, wp, p -> {
				Blockbench.ReadProperties rp = new Blockbench.ReadProperties();
				rp.readtype = "binary";
				rp.errorbox = false;
				boolean r = PlusDialogs.read(new String[] {path}, rp, files -> {
					try {
						Object c = files != null && files.length > 0 ? Js.asPropertyMap(files[0]).get("content") : null;
						byte[] back = c instanceof elemental2.core.ArrayBuffer ? NativeFs.toBytes((elemental2.core.ArrayBuffer) c) : null;
						Verify v = verifyModelFile(back);
						if (!v.ok)rej.onInvoke(new UserError(I18n.format("cpmplus.w3modelexport.error.verify", v.error)));
						else {
							markWritten(path);
							res.onInvoke(path);
						}
					} catch (Throwable t) {
						rej.onInvoke(t);
					}
				});
				if (!r) {
					markWritten(path);
					res.onInvoke(path);
				}
			});
			if (!w)rej.onInvoke(new UserError(I18n.get("cpmplus.w3modelexport.error.noWrite")));
		});
	}

	/** test mode: the bytes of the last desktop write (the harness records writeFile calls without their content) */
	static void lastWrite(String path, byte[] bytes) {
		if (!PlusTestHooks.active())return;
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("path", path);
		m.set("b64", Base64.getEncoder().encodeToString(bytes));
		PlusTestHooks.setLast("w3modelexport.write", m);
	}

	static String parent(String path) {
		int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return i > 0 ? path.substring(0, i) : path;
	}

	/** does a file exist there (desktop); in test mode nothing exists on the (spied) disk */
	static boolean fileExists(String path) {
		if (PlusTestHooks.fsRoot() != null || !NativeFs.available())return false;
		Object fs = NativeFs.fs(parent(path), I18n.get("cpmplus.w3modelexport.fs.permission"));
		return fs != null && NativeFs.exists(fs, path);
	}

	/** description of an existing .cpmmodel ("" when unreadable / missing) */
	static String existingDesc(String path) {
		if (PlusTestHooks.fsRoot() != null || !NativeFs.available())return "";
		Object fs = NativeFs.fs(parent(path), I18n.get("cpmplus.w3modelexport.fs.permission"));
		byte[] b = fs != null ? NativeFs.read(fs, path) : null;
		Verify v = verifyModelFile(b);
		return v.ok && v.desc != null ? v.desc : "";
	}

	/** asks before replacing a file CPM+ did not write; resolves true = go on */
	static Promise<Boolean> confirmOverwrite(String path, boolean ours) {
		if (ours || !fileExists(path))return Promise.resolve(true);
		return new Promise<>((res, rej) -> PlusDialogs.confirm("cpmplus_w3modelexport_overwrite", "cpmplus.w3modelexport.overwrite.title",
				I18n.format("cpmplus.w3modelexport.overwrite.text", NativeFs.fileName(path)), () -> res.onInvoke(true), () -> res.onInvoke(false)));
	}

	// ====================================================================== the C4 action

	/** CPM+ &gt; Exportar modelo (.cpmmodel) / File &gt; Export */
	public static void exportAction() {
		PlusTestHooks.track(exportFlow().then(r -> null, err -> {
			report(err);
			return null;
		}));
	}

	static Promise<String> exportFlow() {
		Editor[] ed = new Editor[1];
		return prepareEditor(true, true).then(e -> {
			ed[0] = e;
			String le = layerError(e);
			if (le != null)return Promise.reject(new UserError(I18n.get(le)));
			return resolveLock(descriptionFor(e));
		}).then(d -> buildModelBytes(ed[0], d, OverflowMode.ASK).then(bytes -> output(bytes, d)));
	}

	/** web: download '&lt;slug&gt;.cpmmodel'; desktop: player_models folder (remembered) */
	static Promise<String> output(byte[] bytes, ModelDescription d) {
		String slug = slug(projectName(), "model");
		if (!PlusDialogs.isDesktop()) {
			return download(slug, bytes).then(n -> {
				success(n, null);
				return Promise.resolve(n);
			});
		}
		return chooseDir().then(dir -> {
			if (dir == null)return download(slug, bytes).then(n -> {
				success(n, null);
				return Promise.resolve(n);
			});
			String path = NativeFs.join(dir, slug + ".cpmmodel");
			return confirmOverwrite(path, writtenByUs(path)).then(go -> {
				if (!Boolean.TRUE.equals(go))return Promise.<String>reject(new Cancelled());
				return writeDesktop(path, bytes).then(p -> {
					success(NativeFs.fileName(p), p);
					return Promise.resolve(p);
				});
			});
		});
	}

	/** Blockbench download / save dialog; resolves with the file name */
	static Promise<String> download(String baseName, byte[] bytes) {
		return new Promise<>((res, rej) -> {
			Blockbench.ExportProperties pr = new Blockbench.ExportProperties();
			pr.type = "CPM Model";
			pr.extensions = new String[] {"cpmmodel"};
			pr.name = baseName;
			pr.resource_id = "cpmplus_cpmmodel";
			pr.binaryContent = NativeFs.blob(bytes, "application/octet-stream");
			if (!PlusDialogs.export(pr, p -> {
			})) {
				rej.onInvoke(new UserError(I18n.get("cpmplus.w3modelexport.error.noWrite")));
				return;
			}
			res.onInvoke(baseName + ".cpmmodel");
		});
	}

	/** remembered player_models folder, or ask (first time); null = 'save somewhere else' (save dialog) */
	static Promise<String> chooseDir() {
		String fsRoot = PlusTestHooks.fsRoot();
		String remembered = PlusPrefs.get(PREF_DIR, null);
		if (fsRoot != null) {
			String dir = NativeFs.join(fsRoot, "player_models");
			if (remembered == null || !remembered.toLowerCase().startsWith(fsRoot.toLowerCase()))PlusPrefs.set(PREF_DIR, dir);
			return Promise.resolve(PlusPrefs.get(PREF_DIR, dir));
		}
		if (remembered != null && !remembered.isEmpty())return Promise.resolve(remembered);
		return new Promise<>((res, rej) -> {
			Dialog.DialogProperties p = new Dialog.DialogProperties();
			p.id = "cpmplus_w3modelexport_dir";
			p.title = I18n.get("cpmplus.w3modelexport.dir.title");
			p.setHtml("<p>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.dir.text")) + "</p>");
			p.buttons = new String[] {I18n.get("cpmplus.w3modelexport.dir.pick"), I18n.get("cpmplus.w3modelexport.dir.saveAs"), I18n.get("cpmplus.w3modelexport.btn.cancel")};
			p.confirmIndex = 0;
			p.cancelIndex = 2;
			boolean[] done = new boolean[1];
			p.onButton = i -> {
				if (done[0])return true;
				done[0] = true;
				if (i == 0) {
					String dir = PlusDialogs.pickDirectory("cpmplus.w3modelexport.dir.title", null);
					if (dir == null) {
						rej.onInvoke(new Cancelled());
					} else {
						PlusPrefs.set(PREF_DIR, dir);
						res.onInvoke(dir);
					}
				} else if (i == 1) {
					res.onInvoke((String) null);
				} else {
					rej.onInvoke(new Cancelled());
				}
				return true;
			};
			p.onCancel = () -> {
				if (!done[0]) {
					done[0] = true;
					rej.onInvoke(new Cancelled());
				}
				return true;
			};
			new Dialog(p).show();
		});
	}

	static void success(String fileName, String path) {
		String cmd = "/cpmclient set_model " + fileName;
		Dialog.DialogProperties p = new Dialog.DialogProperties();
		p.id = "cpmplus_w3modelexport_done";
		p.title = I18n.get("cpmplus.w3modelexport.done.title");
		String where = path != null ? I18n.format("cpmplus.w3modelexport.done.saved", path) : I18n.format("cpmplus.w3modelexport.done.downloaded", fileName);
		p.setHtml("<p>" + PlusDialogs.escLines(where) + "</p>",
				"<p>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.done.howTo")) + "</p>",
				"<p><code>" + PlusDialogs.esc(cmd) + "</code></p>");
		p.buttons = new String[] {I18n.get("cpmplus.w3modelexport.btn.copyCommand"), I18n.get("cpmplus.w3modelexport.btn.close")};
		p.confirmIndex = 1;
		p.cancelIndex = 1;
		p.onButton = i -> {
			if (i == 0)PlusDialogs.copyText(cmd);
			return true;
		};
		new Dialog(p).show();
		if (PlusTestHooks.active()) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("file", fileName);
			m.set("path", path);
			m.set("command", cmd);
			PlusTestHooks.setLast("w3modelexport.done", m);
		}
	}

	/** an export error for the user (nothing for a cancel) */
	public static void report(Object err) {
		if (err instanceof PopupDialogs.CancelException) {
			if (PlusTestHooks.active())PlusTestHooks.setLast("w3modelexport.cancelled", true);
			return;
		}
		if (err instanceof UserError) {
			PopupDialogs.displayUserError(I18n.get("cpmplus.w3modelexport.error.title"), ((UserError) err).getMessage());
			return;
		}
		ProjectConvert.reportExportError(err);
	}

	// ====================================================================== BBUI.displayPopup (never silent)

	/** best effort name of the CPM popup a factory builds (only for the message / log) */
	static String popupClass(Function<Frame, PopupPanel> factory) {
		try {
			Frame f = WebMC.getInstance() != null && WebMC.getInstance().getGui() != null ? WebMC.getInstance().getGui().getFrame() : null;
			if (f != null) {
				PopupPanel p = factory.apply(f);
				if (p != null) {
					String n = p.getClass().getName();
					int i = n.lastIndexOf('.');
					return i >= 0 ? n.substring(i + 1) : n;
				}
			}
		} catch (Throwable e) {
		}
		return I18n.get("cpmplus.w3modelexport.popup.unknown");
	}

	/**
	 * BBUI.displayPopup: CPM's own popups (layer config, overflow upload, ...) can't open inside Blockbench. Never
	 * silent: logs 'CPM+: popup &lt;class&gt; not supported' and shows a message with what to use instead.
	 */
	public static void unsupportedPopup(Function<Frame, PopupPanel> factory) {
		String cls = popupClass(factory);
		lastPopupClass = cls;
		DomGlobal.console.warn("CPM+: popup " + cls + " not supported");
		if (PlusTestHooks.active())PlusTestHooks.setLast("w3modelexport.popup", cls);
		PlusDialogs.info("cpmplus_w3modelexport_popup", "cpmplus.w3modelexport.popup.title",
				java.util.Arrays.asList(I18n.format("cpmplus.w3modelexport.popup.unsupported", cls)));
	}

	// ====================================================================== QA (test mode tools)

	/** counters of this session (test tools only) */
	public static JsPropertyMap<Object> qaCounters() {
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("layerDialogOpens", (double) layerDialogOpens);
		m.set("exporterCalls", (double) exporterCalls);
		m.set("lastPopupClass", lastPopupClass);
		return m;
	}

	public static String qaLastPopupClass() {
		return lastPopupClass;
	}
}