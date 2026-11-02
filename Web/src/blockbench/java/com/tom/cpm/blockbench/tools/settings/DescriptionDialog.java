package com.tom.cpm.blockbench.tools.settings;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.ext.W3SettingsNatives;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.OfflineUuid;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;

import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-SETTINGS, F19 + G29 + G4 Part B): 'Nome, descrição e ícone' of the model.
 * <ul>
 * <li>name / description; empty name + description + no icon + 'Ninguém' = no description at all (nothing is
 * written into the export).</li>
 * <li>icon: 'Escolher PNG…' or 'Tirar foto da tela', stored as the raw project entry desc_icon.png, ALWAYS exactly
 * 256x256 (CPM drops any other size): a 256x256 PNG is kept byte for byte. An icon with an empty name still creates
 * the description (the export only writes the icon with a description).</li>
 * <li>'Quem pode copiar': Ninguém (normal) / Outros podem clonar / Travar na conta de quem exportar no jogo / Só o
 * cliente (UUID lock to the client's account: premium lookup on the desktop app, offline UUID from the nick, or a
 * pasted UUID).</li>
 * </ul>
 * Project data is not part of Blockbench's undo: the 'Feito' toast offers 'clique para desfazer'.
 */
public final class DescriptionDialog {
	public static final String DIALOG_ID = "cpmplus_w3settings_desc";
	public static final String ICON = "desc_icon.png";
	public static final String PROT_NORMAL = "normal", PROT_CLONE = "cloneable", PROT_LOCK = "uuid_lock", PROT_CLIENT = "client";

	private DescriptionDialog() {
	}

	/** the values of the dialog */
	public static final class DescValues {
		public String name = "", desc = "";
		/** new icon (base64 PNG, exactly 256x256) or null = keep the current one */
		public String iconB64;
		public boolean removeIcon;
		/** normal | cloneable | uuid_lock | client */
		public String prot = PROT_NORMAL;
		public String lockKind = CopyProtState.KIND_PREMIUM, lockNick = "", lockUuid = "";

		public static DescValues current() {
			ProjectData pd = Project.getData();
			DescValues v = new DescValues();
			if(pd.description != null) {
				v.name = pd.description.name != null ? pd.description.name : "";
				v.desc = pd.description.desc != null ? pd.description.desc : "";
			}
			CopyProtection cp = CopyProtState.of(pd);
			String lock = CopyProtState.lockUuid(pd);
			if(cp == CopyProtection.CLONEABLE)v.prot = PROT_CLONE;
			else if(cp == CopyProtection.UUID_LOCK)v.prot = lock != null ? PROT_CLIENT : PROT_LOCK;
			if(lock != null) {
				v.lockUuid = lock;
				v.lockKind = CopyProtState.lockKind(pd);
				String n = CopyProtState.lockNick(pd);
				v.lockNick = n != null ? n : "";
			}
			return v;
		}
	}

	/** state line text ('Quem pode copiar agora: …') */
	public static String stateText(ProjectData pd) {
		CopyProtection cp = CopyProtState.of(pd);
		String s;
		if(cp == CopyProtection.CLONEABLE)s = PlusDialogs.tr(CopyProtState.cloneAck(pd) ? "cpmplus.w3settings.prot.state.cloneableAck" : "cpmplus.w3settings.prot.cloneable");
		else if(cp == CopyProtection.UUID_LOCK) {
			String u = CopyProtState.lockUuid(pd);
			s = u != null ? lockSummary(pd) : PlusDialogs.tr("cpmplus.w3settings.prot.uuidLock");
		} else s = PlusDialogs.tr("cpmplus.w3settings.prot.normal");
		return PlusDialogs.trf("cpmplus.w3settings.desc.state", s);
	}

	/** 'Travado para: <nick> (<tipo>) <uuid curto>' */
	public static String lockSummary(ProjectData pd) {
		String u = CopyProtState.lockUuid(pd);
		String nick = CopyProtState.lockNick(pd);
		String kind = PlusDialogs.tr("cpmplus.w3settings.lock.kind." + CopyProtState.lockKind(pd));
		return PlusDialogs.trf("cpmplus.w3settings.lock.summary", nick != null ? nick : "?", kind, CopyProtState.shortUuid(u));
	}

	// ------------------------------------------------------------------ dialog

	public static void open() {
		ProjectData pd = Project.getData();
		DescValues cur = DescValues.current();
		String curIcon = pd.rawFiles.get(ICON);
		String[] pendingIcon = new String[1];
		boolean[] removeIcon = new boolean[1];
		String[] resolvedNick = new String[1];
		Dialog[] dlg = new Dialog[1];
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("state", null, stateText(pd)));
		rows.add(FormRow.text("name", "cpmplus.w3settings.desc.name", cur.name));
		rows.add(FormRow.textarea("desc", "cpmplus.w3settings.desc.desc", cur.desc));
		rows.add(FormRow.text("iconStatus", "cpmplus.w3settings.desc.icon", PlusDialogs.tr(curIcon != null ? "cpmplus.w3settings.desc.icon.set" : "cpmplus.w3settings.desc.icon.none")).readonly());
		rows.add(FormRow.buttons("iconButtons", null, new String[] {"cpmplus.w3settings.desc.icon.pick", "cpmplus.w3settings.desc.icon.shot", "cpmplus.w3settings.desc.icon.remove"}, i -> {
			if(i == 0)pickIcon(b64 -> {
				pendingIcon[0] = b64;
				removeIcon[0] = false;
				setStatus(dlg[0], "cpmplus.w3settings.desc.icon.new");
			});
			else if(i == 1)screenshotIcon(b64 -> {
				pendingIcon[0] = b64;
				removeIcon[0] = false;
				setStatus(dlg[0], "cpmplus.w3settings.desc.icon.new");
			});
			else {
				pendingIcon[0] = null;
				removeIcon[0] = true;
				setStatus(dlg[0], "cpmplus.w3settings.desc.icon.removed");
			}
		}));
		rows.add(FormRow.info("iconNote", null, "cpmplus.w3settings.desc.icon.note"));
		rows.add(FormRow.separator());
		rows.add(FormRow.radio("prot", "cpmplus.w3settings.prot.title", PlusDialogs.options(
				PROT_NORMAL, "cpmplus.w3settings.prot.normal",
				PROT_CLONE, "cpmplus.w3settings.prot.cloneable",
				PROT_LOCK, "cpmplus.w3settings.prot.uuidLock",
				PROT_CLIENT, "cpmplus.w3settings.prot.client"), cur.prot));
		rows.add(FormRow.info("protNormal", null, "cpmplus.w3settings.prot.normal.info").when(r -> PROT_NORMAL.equals(r.getString("prot"))));
		rows.add(FormRow.info("protClone", null, "cpmplus.w3settings.prot.cloneable.info").when(r -> PROT_CLONE.equals(r.getString("prot"))));
		rows.add(FormRow.info("protLock", null, "cpmplus.w3settings.prot.uuidLock.info").when(r -> PROT_LOCK.equals(r.getString("prot"))));
		rows.add(FormRow.select("lockKind", "cpmplus.w3settings.lock.kind", PlusDialogs.options(
				CopyProtState.KIND_PREMIUM, "cpmplus.w3settings.lock.kind.premium.long",
				CopyProtState.KIND_OFFLINE, "cpmplus.w3settings.lock.kind.offline.long",
				CopyProtState.KIND_MANUAL, "cpmplus.w3settings.lock.kind.manual.long"), cur.lockKind)
				.description("cpmplus.w3settings.lock.kind.desc")
				.when(r -> PROT_CLIENT.equals(r.getString("prot"))));
		rows.add(FormRow.text("lockNick", "cpmplus.w3settings.lock.nick", cur.lockNick)
				.when(r -> PROT_CLIENT.equals(r.getString("prot")) && !CopyProtState.KIND_MANUAL.equals(r.getString("lockKind"))));
		rows.add(FormRow.buttons("lockLookup", null, new String[] {"cpmplus.w3settings.lock.lookup"}, i -> {
			FormResult r = new FormResult(dlg[0] != null ? dlg[0].getFormResult() : null);
			String kind = r.getString("lockKind");
			String nick = r.getString("lockNick");
			if(nick == null || nick.trim().isEmpty()) {
				PlusDialogs.block("cpmplus.w3settings.lock.err.noNick");
				return;
			}
			if(CopyProtState.KIND_OFFLINE.equals(kind)) {
				String u = OfflineUuid.fromName(nick.trim());
				setValue(dlg[0], "lockUuid", u);
				PlusDialogs.quick(PlusDialogs.trf("cpmplus.w3settings.lock.offlineDone", nick.trim()), 4000);
			} else {
				lookupPremium(nick.trim(), (u, name) -> {
					resolvedNick[0] = name;
					setValue(dlg[0], "lockUuid", u);
				});
			}
		}).when(r -> PROT_CLIENT.equals(r.getString("prot")) && !CopyProtState.KIND_MANUAL.equals(r.getString("lockKind"))));
		rows.add(FormRow.text("lockUuid", "cpmplus.w3settings.lock.uuid", cur.lockUuid)
				.placeholder("cpmplus.w3settings.lock.uuid.placeholder")
				.when(r -> PROT_CLIENT.equals(r.getString("prot"))));
		rows.add(FormRow.info("lockWebNote", null, "cpmplus.w3settings.lock.webNote")
				.when(r -> PROT_CLIENT.equals(r.getString("prot")) && CopyProtState.KIND_PREMIUM.equals(r.getString("lockKind")) && !W3SettingsNatives.canLookupPremium(PlusTestHooks.enabled())));
		rows.add(FormRow.info("lockOffline", null, "cpmplus.w3settings.lock.offlineWarn")
				.when(r -> PROT_CLIENT.equals(r.getString("prot")) && CopyProtState.KIND_OFFLINE.equals(r.getString("lockKind"))));
		rows.add(FormRow.info("lockCheck", null, "cpmplus.w3settings.lock.checkLog")
				.when(r -> PROT_CLIENT.equals(r.getString("prot"))));
		rows.add(FormRow.htmlInfo("lockHonest", null, "<span style='opacity: 0.7;'>" + PlusDialogs.escMdLines(PlusDialogs.tr("cpmplus.w3settings.lock.honest")) + "</span>")
				.when(r -> PROT_CLIENT.equals(r.getString("prot"))));
		FormOpts o = new FormOpts();
		o.width = 560;
		dlg[0] = PlusDialogs.form(DIALOG_ID, "cpmplus.w3settings.desc.title", rows, r -> {
			DescValues v = new DescValues();
			v.name = str(r.getString("name"));
			v.desc = str(r.getString("desc"));
			v.iconB64 = pendingIcon[0];
			v.removeIcon = removeIcon[0];
			v.prot = r.getString("prot") != null ? r.getString("prot") : PROT_NORMAL;
			v.lockKind = r.getString("lockKind") != null ? r.getString("lockKind") : CopyProtState.KIND_MANUAL;
			v.lockNick = str(r.getString("lockNick"));
			if(resolvedNick[0] != null && resolvedNick[0].equalsIgnoreCase(v.lockNick))v.lockNick = resolvedNick[0];
			v.lockUuid = str(r.getString("lockUuid"));
			String err = validate(v);
			if(err != null) {
				PlusDialogs.block(err);
				r.keepOpen();
				return;
			}
			apply(v, true);
		}, o);
	}

	private static String str(String s) {
		return s != null ? s : "";
	}

	private static void setStatus(Dialog d, String key) {
		setValue(d, "iconStatus", PlusDialogs.tr(key));
	}

	private static void setValue(Dialog d, String key, String value) {
		if(d == null)return;
		try {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set(key, value);
			d.setFormValues(m, true);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not update the dialog", e);
		}
	}

	// ------------------------------------------------------------------ icon

	/** 'Escolher PNG…': PNG file picker -> 256x256 base64 (red message for anything that is not a readable PNG) */
	public static void pickIcon(java.util.function.Consumer<String> done) {
		Blockbench.ImportProperties p = new Blockbench.ImportProperties();
		p.extensions = new String[] {"png"};
		p.type = "PNG";
		p.readtype = "image";
		p.resource_id = "cpmplus_icon";
		boolean ok = PlusDialogs.importFiles(p, files -> {
			if(files == null || files.length == 0)return;
			String url = files[0].textContent;
			iconFromDataUrl(url).then(b64 -> {
				done.accept(b64);
				return null;
			}).catch_(e -> {
				PlusDialogs.block(iconError(e));
				return null;
			});
		});
		if(!ok)PlusDialogs.block("cpmplus.w3settings.desc.icon.err.noImport");
	}

	/** 'Tirar foto da tela': viewport screenshot, centre-cropped to a square, scaled to 256x256 */
	public static void screenshotIcon(java.util.function.Consumer<String> done) {
		PlusTestHooks.track(W3SettingsNatives.viewportScreenshot().then(url -> iconFromDataUrl(url)).then(b64 -> {
			done.accept(b64);
			return null;
		})).catch_(e -> {
			PlusDialogs.block("cpmplus.w3settings.desc.icon.err.shot");
			return null;
		});
	}

	/** data URL -> base64 PNG of exactly 256x256 (a 256x256 PNG byte for byte); rejects with a lang key */
	public static Promise<String> iconFromDataUrl(String url) {
		return W3SettingsNatives.iconFromDataUrl(url).then(r -> Promise.resolve((String) r.get("b64")));
	}

	private static String iconError(Object e) {
		String m = e != null ? String.valueOf(Js.asPropertyMap(e).get("message")) : "";
		return "notpng".equals(m) ? "cpmplus.w3settings.desc.icon.err.notPng" : "cpmplus.w3settings.desc.icon.err.decode";
	}

	// ------------------------------------------------------------------ client lock

	@FunctionalInterface
	public interface LookupDone {
		void found(String canonicalUuid, String name);
	}

	/** premium (Mojang) lookup; desktop only. Explains instead of failing silently. */
	public static void lookupPremium(String nick, LookupDone done) {
		boolean test = PlusTestHooks.enabled();
		if(!W3SettingsNatives.canLookupPremium(test)) {
			PlusDialogs.block("cpmplus.w3settings.lock.err.webLookup");
			return;
		}
		PlusTestHooks.track(W3SettingsNatives.lookupPremium(nick, test)).then(text -> {
			if(text == null || text.trim().isEmpty()) {
				PlusDialogs.block(PlusDialogs.trf("cpmplus.w3settings.lock.err.notFound", nick));
				return null;
			}
			Object parsed = JsonUtil.parse(text);
			java.util.Map<String, Object> m = JsonUtil.asMap(parsed);
			String id = m != null && m.get("id") instanceof String ? (String) m.get("id") : null;
			String name = m != null && m.get("name") instanceof String ? (String) m.get("name") : nick;
			String u = OfflineUuid.normalize(id);
			if(u == null) {
				PlusDialogs.block(PlusDialogs.trf("cpmplus.w3settings.lock.err.notFound", nick));
				return null;
			}
			done.found(u, name);
			PlusDialogs.quick(PlusDialogs.trf("cpmplus.w3settings.lock.found", name, u), 5000);
			return null;
		}).catch_(e -> {
			PlusDialogs.block("cpmplus.w3settings.lock.err.lookupFailed");
			return null;
		});
	}

	/** the random UUID of Blockbench's own (fake) player profile, or null */
	public static String webMcUuid() {
		try {
			return String.valueOf(MinecraftClientAccess.get().getClientPlayer().getUUID()).toLowerCase();
		} catch (Throwable e) {
			return null;
		}
	}

	/** null when the values can be applied, else the message (lang key or text) */
	public static String validate(DescValues v) {
		if(!PROT_CLIENT.equals(v.prot))return null;
		String u;
		if(CopyProtState.KIND_OFFLINE.equals(v.lockKind)) {
			if(v.lockNick.trim().isEmpty())return "cpmplus.w3settings.lock.err.noNick";
			u = OfflineUuid.fromName(v.lockNick.trim());
		} else {
			if(v.lockUuid.trim().isEmpty())return CopyProtState.KIND_PREMIUM.equals(v.lockKind) ? "cpmplus.w3settings.lock.err.noUuidPremium" : "cpmplus.w3settings.lock.err.noUuid";
			u = OfflineUuid.normalize(v.lockUuid);
			if(u == null)return "cpmplus.w3settings.lock.err.badUuid";
		}
		if(u == null)return "cpmplus.w3settings.lock.err.badUuid";
		String web = webMcUuid();
		if(web != null && web.equals(u))return "cpmplus.w3settings.lock.err.webMc";
		v.lockUuid = u;
		return null;
	}

	// ------------------------------------------------------------------ apply

	/** what {@link #apply} did */
	public static final class Result {
		public boolean changed, descriptionRemoved;
		public String error;
	}

	/**
	 * Applies the values (validate first). Unchanged values change nothing (no flush, Project.saved kept). Empty
	 * name + description + no icon + normal = no description (and no stored icon).
	 */
	public static Result apply(DescValues v, boolean toast) {
		Result res = new Result();
		String err = validate(v);
		if(err != null) {
			res.error = err;
			return res;
		}
		ProjectData pd = Project.getData();
		DescValues cur = DescValues.current();
		String curIcon = pd.rawFiles.get(ICON);
		String newIcon = v.iconB64 != null ? v.iconB64 : (v.removeIcon ? null : curIcon);
		boolean client = PROT_CLIENT.equals(v.prot);
		boolean same = cur.name.equals(v.name) && cur.desc.equals(v.desc) && same(curIcon, newIcon) && cur.prot.equals(v.prot)
				&& (!client || (cur.lockUuid.equals(v.lockUuid) && cur.lockKind.equals(v.lockKind) && cur.lockNick.equals(v.lockNick.trim())));
		if(same)return res;
		res.changed = true;
		ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
		pd = Project.getData();
		CopyProtection cp = PROT_CLONE.equals(v.prot) ? CopyProtection.CLONEABLE : (PROT_LOCK.equals(v.prot) || client) ? CopyProtection.UUID_LOCK : CopyProtection.NORMAL;
		boolean empty = v.name.isEmpty() && v.desc.isEmpty() && newIcon == null && cp == CopyProtection.NORMAL;
		if(empty) {
			if(pd.description != null)CopyProtState.clearOwnExtras(pd);
			pd.description = null;
			pd.rawFiles.remove(ICON);
			res.descriptionRemoved = true;
		} else {
			ModelDescription d = CopyProtState.ensureDescription(pd);
			d.name = v.name;
			d.desc = v.desc;
			CopyProtState.applyCopyProt(pd, cp);
			if(client)CopyProtState.setLock(pd, v.lockUuid, v.lockKind, v.lockNick);
			else CopyProtState.clearLock(pd);
			if(newIcon != null)pd.rawFiles.put(ICON, newIcon);
			else pd.rawFiles.remove(ICON);
		}
		pd.flush();
		Project.saved = false;
		if(toast)ProjectDataUndo.offerUndo(before, PlusDialogs.tr("cpmplus.w3settings.desc.done"));
		return res;
	}

	private static boolean same(String a, String b) {
		return a == null ? b == null : a.equals(b);
	}
}
