package com.tom.cpm.blockbench.tools.settings;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.OfflineUuid;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.blockbench.util.ServerCommands;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.project.ProjectFile;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;

import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;

/**
 * CPM+ (W3-SETTINGS): what the settings do around export / import.
 * <ul>
 * <li>Export rows (ExportHooks): CLONEABLE not confirmed (yellow, fixes 'Manter clonável' / 'Tornar normal');
 * CLONEABLE confirmed (quiet info, only next to other rows); UUID_LOCK without a client ('a trava é aplicada quando você
 * exporta pelo editor do CPM no jogo', never blocks the .cpmproject); 'Travado para: …' summary; scaling options a
 * server blocks by default (info, 'Ver comandos pro staff').</li>
 * <li>beforeFinish: a client lock is copied into editor.description.uuid (a copy of the description) and written as
 * the extra key 'uuid' of description.json (CPM's loader ignores unknown keys).</li>
 * <li>Import: 'uuid' of description.json is read back as the client lock; once per opened project a toast explains a
 * CLONEABLE mark nobody confirmed or a UUID lock without a client (never changes anything by itself).</li>
 * </ul>
 */
public final class SettingsHooks {
	public static final String KEY_CLONEABLE = "cpmplus_w3settings_cloneable";
	public static final String KEY_CLONEABLE_ACK = "cpmplus_w3settings_cloneableAck";
	public static final String KEY_UUID_LOCK = "cpmplus_w3settings_uuidLock";
	public static final String KEY_LOCKED_TO = "cpmplus_w3settings_lockedTo";
	public static final String KEY_SCALING = "cpmplus_w3settings_scalingServer";
	public static final String DESC_JSON = "description.json";

	private static final Set<String> TOASTED = new HashSet<>();

	private SettingsHooks() {
	}

	// ------------------------------------------------------------------ export rows

	public static void exportCheck(ExportContext ctx) {
		ProjectData pd = Project.getData();
		ModelDescription d = ctx.editor.description;
		CopyProtection cp = d != null && d.copyProtection != null ? d.copyProtection : CopyProtection.NORMAL;
		if(cp == CopyProtection.CLONEABLE) {
			if(!CopyProtState.cloneAck(pd)) {
				ctx.add(WarnFactory.of(Severity.DIFFERS, KEY_CLONEABLE, "cpmplus.w3settings.warn.cloneable")
						.setConsequence("cpmplus.w3settings.warn.cloneable.ifIgnored").setFixKind(FixKind.ASKS));
			} else if(!ctx.getWarnings().isEmpty()) {
				ctx.add(WarnFactory.of(Severity.INFO, KEY_CLONEABLE_ACK, "cpmplus.w3settings.warn.cloneableAck"));
			}
		} else if(cp == CopyProtection.UUID_LOCK) {
			if(CopyProtState.lockUuid(pd) != null) {
				ctx.add(WarnFactory.ofHtml(Severity.INFO, KEY_LOCKED_TO, PlusDialogs.esc(DescriptionDialog.lockSummary(pd))));
			} else {
				ctx.add(WarnFactory.of(Severity.INFO, KEY_UUID_LOCK, "cpmplus.w3settings.warn.uuidLockInGame"));
			}
		}
		if(ctx.editor.scalingElem != null && ctx.editor.scalingElem.enabled) {
			int n = ServerCommands.countBlocked(ctx.editor.scalingElem.scaling);
			if(n > 0)ctx.add(WarnFactory.of(Severity.INFO, KEY_SCALING, "cpmplus.w3settings.warn.scalingServer", String.valueOf(n)).setFixKind(FixKind.VIEW_ONLY));
		}
	}

	/** 'Manter clonável': marks CLONEABLE as wanted */
	public static Promise<Boolean> keepCloneable() {
		ProjectDataUndo.Snapshot s = ProjectDataUndo.snapshot();
		CopyProtState.setCloneAck(Project.getData(), true);
		ProjectDataUndo.offerUndo(s, PlusDialogs.tr("cpmplus.w3settings.fix.keepCloneable.done"));
		return Promise.resolve(true);
	}

	/** 'Tornar normal': copy protection NORMAL (the 'Clonar' button disappears) */
	public static Promise<Boolean> makeNormal() {
		ProjectDataUndo.Snapshot s = ProjectDataUndo.snapshot();
		boolean ch = CopyProtState.setCopyProt(Project.getData(), CopyProtection.NORMAL);
		if(ch)ProjectDataUndo.offerUndo(s, PlusDialogs.tr("cpmplus.w3settings.fix.makeNormal.done"));
		return Promise.resolve(ch);
	}

	// ------------------------------------------------------------------ before finish: client lock

	/** client lock -> editor.description.uuid (on a copy) + 'uuid' key in description.json */
	public static void beforeFinish(Editor editor) {
		if(editor.description == null || editor.description.copyProtection != CopyProtection.UUID_LOCK)return;
		String u = CopyProtState.lockUuid(Project.getData());
		if(u == null)return;
		ModelDescription src = editor.description;
		ModelDescription c = new ModelDescription();
		c.name = src.name;
		c.desc = src.desc;
		c.icon = src.icon;
		c.camera = src.camera;
		c.copyProtection = src.copyProtection;
		c.uuid = UUID.fromString(u);
		editor.description = c;
		editor.project = new UuidProjectFile(u);
	}

	/** the .cpmproject being written: adds "uuid" to description.json (written by CPM's DescriptionLoaderV1) */
	static final class UuidProjectFile extends ProjectFile {
		private final String uuid;

		UuidProjectFile(String uuid) {
			this.uuid = uuid;
		}

		@Override
		public boolean setEntry(String path, byte[] data) {
			if(DESC_JSON.equals(path) && data != null && data.length > 0) {
				try {
					Map<String, Object> m = JsonUtil.asMap(JsonUtil.parse(new String(data, StandardCharsets.UTF_8)));
					if(m != null) {
						Map<String, Object> o = new LinkedHashMap<>(m);
						o.put("uuid", uuid);
						data = JsonUtil.toJson(o).getBytes(StandardCharsets.UTF_8);
					}
				} catch (Throwable e) {
					DomGlobal.console.error("CPM+: could not add the client uuid to description.json", e);
				}
			}
			return super.setEntry(path, data);
		}
	}

	// ------------------------------------------------------------------ import / open

	/** 'uuid' of an imported description.json -> client lock (when the project has none yet) */
	public static void readUuid(Editor editor) {
		if(editor == null || editor.project == null)return;
		byte[] b;
		try {
			b = editor.project.getEntry(DESC_JSON);
		} catch (Throwable e) {
			return;
		}
		if(b == null)return;
		ProjectData pd = Project.getData();
		if(pd.description == null || CopyProtState.lockUuid(pd) != null)return;
		try {
			Map<String, Object> m = JsonUtil.asMap(JsonUtil.parse(new String(b, StandardCharsets.UTF_8)));
			Object u = m != null ? m.get("uuid") : null;
			String c = u instanceof String ? OfflineUuid.normalize((String) u) : null;
			if(c == null)return;
			CopyProtState.setLock(pd, c, CopyProtState.KIND_MANUAL, null);
			pd.flush();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not read the client uuid of description.json", e);
		}
	}

	/** once per opened project: explain a CLONEABLE mark nobody confirmed / a UUID lock without a client */
	public static void onProjectOpened() {
		try {
			if(!CPMCodec.isCPM())return;
			String id = Project.getUuid();
			if(id == null || TOASTED.contains(id))return;
			ProjectData pd = Project.getData();
			CopyProtection cp = CopyProtState.of(pd);
			String kind = null;
			if(cp == CopyProtection.CLONEABLE && !CopyProtState.cloneAck(pd))kind = "cloneable";
			else if(cp == CopyProtection.UUID_LOCK && CopyProtState.lockUuid(pd) == null)kind = "uuidLock";
			if(kind == null)return;
			TOASTED.add(id);
			PlusTestHooks.setLast("w3settings.openNotice", kind);
			PlusDialogs.toast(PlusDialogs.tr("cpmplus.w3settings.open." + kind), "info", 20000, DescriptionDialog::open);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: copy protection notice failed", e);
		}
	}

	static void clearToasted() {
		TOASTED.clear();
	}
}
