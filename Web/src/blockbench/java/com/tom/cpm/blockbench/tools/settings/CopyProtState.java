package com.tom.cpm.blockbench.tools.settings;

import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.OfflineUuid;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;

/**
 * CPM+ (W3-SETTINGS, G29 + G4 Part B): 'Quem pode copiar' of the open project, and the ONE way to change it.
 * <ul>
 * <li>{@link #of(ProjectData)}: NORMAL / CLONEABLE / UUID_LOCK (no description = NORMAL).</li>
 * <li>{@link #setCopyProt(ProjectData, CopyProtection)}: sets the value, resets the 'cloneable on purpose' mark
 * (cloneAck) when it changes, drops the client lock when it is no longer UUID_LOCK, flushes, Project.saved = false.
 * Never called automatically (import only informs).</li>
 * <li>cloneAck (desc extra, written only when true): the user confirmed CLONEABLE is wanted (no more warnings).</li>
 * <li>client lock (desc extras lockUuid / lockKind / lockNick): the account a UUID_LOCK model is locked to. Canonical
 * lower-case hyphenated UUID; kind premium | offline | manual; nick only for display.</li>
 * </ul>
 * The texts never say 'protegido': NORMAL only hides CPM's 'Clonar' button.
 */
public final class CopyProtState {
	public static final String CLONE_ACK = "cloneAck";
	public static final String LOCK_UUID = "lockUuid";
	public static final String LOCK_KIND = "lockKind";
	public static final String LOCK_NICK = "lockNick";
	public static final String KIND_PREMIUM = "premium", KIND_OFFLINE = "offline", KIND_MANUAL = "manual";

	private CopyProtState() {
	}

	/** copy protection of the project; no description = NORMAL */
	public static CopyProtection of(ProjectData pd) {
		if(pd == null || pd.description == null || pd.description.copyProtection == null)return CopyProtection.NORMAL;
		return pd.description.copyProtection;
	}

	/** true when the user said CLONEABLE is on purpose */
	public static boolean cloneAck(ProjectData pd) {
		if(pd == null || pd.description == null)return false;
		try {
			return Boolean.TRUE.equals(pd.getDescExtra(CLONE_ACK));
		} catch (Throwable e) {
			return false;
		}
	}

	/** the client UUID the model is locked to (canonical), or null */
	public static String lockUuid(ProjectData pd) {
		String s = descString(pd, LOCK_UUID);
		return s != null ? OfflineUuid.normalize(s) : null;
	}

	public static String lockKind(ProjectData pd) {
		String k = descString(pd, LOCK_KIND);
		return KIND_PREMIUM.equals(k) || KIND_OFFLINE.equals(k) ? k : KIND_MANUAL;
	}

	public static String lockNick(ProjectData pd) {
		String n = descString(pd, LOCK_NICK);
		return n != null && !n.isEmpty() ? n : null;
	}

	private static String descString(ProjectData pd, String key) {
		if(pd == null || pd.description == null)return null;
		try {
			Object v = pd.getDescExtra(key);
			return v instanceof String ? (String) v : null;
		} catch (Throwable e) {
			return null;
		}
	}

	/** creates the description when the project has none (name from nothing: empty, the export fills nothing else) */
	public static ModelDescription ensureDescription(ProjectData pd) {
		if(pd.description == null) {
			pd.description = new ModelDescription();
			clearOwnExtras(pd);// stale extras of a removed description must not come back
		}
		return pd.description;
	}

	/** removes cloneAck and the client lock from the stored description data */
	public static void clearOwnExtras(ProjectData pd) {
		pd.removeDescExtra(CLONE_ACK);
		clearLock(pd);
	}

	public static void clearLock(ProjectData pd) {
		pd.removeDescExtra(LOCK_UUID);
		pd.removeDescExtra(LOCK_KIND);
		pd.removeDescExtra(LOCK_NICK);
	}

	/** sets the client lock fields (no flush) */
	public static void setLock(ProjectData pd, String canonicalUuid, String kind, String nick) {
		ensureDescription(pd);
		pd.putDescExtra(LOCK_UUID, canonicalUuid);
		pd.putDescExtra(LOCK_KIND, kind != null ? kind : KIND_MANUAL);
		if(nick != null && !nick.trim().isEmpty())pd.putDescExtra(LOCK_NICK, nick.trim());
		else pd.removeDescExtra(LOCK_NICK);
	}

	/**
	 * Applies a copy protection without flushing: any change resets cloneAck; anything but UUID_LOCK drops the client
	 * lock. NORMAL on a project without description changes nothing. Returns true when something changed.
	 */
	public static boolean applyCopyProt(ProjectData pd, CopyProtection v) {
		CopyProtection nv = v != null ? v : CopyProtection.NORMAL;
		if(of(pd) == nv)return false;
		if(pd.description == null && nv == CopyProtection.NORMAL)return false;
		ensureDescription(pd).copyProtection = nv;
		pd.removeDescExtra(CLONE_ACK);
		if(nv != CopyProtection.UUID_LOCK)clearLock(pd);
		return true;
	}

	/** THE setter: value, cloneAck reset, lock dropped when not UUID_LOCK, flush, Project.saved = false */
	public static boolean setCopyProt(ProjectData pd, CopyProtection v) {
		boolean ch = applyCopyProt(pd, v);
		if(ch) {
			pd.flush();
			Project.saved = false;
		}
		return ch;
	}

	/** marks CLONEABLE as wanted (true) or removes the mark; flush + Project.saved = false */
	public static void setCloneAck(ProjectData pd, boolean ack) {
		if(cloneAck(pd) == ack)return;
		if(ack) {
			ensureDescription(pd);
			pd.putDescExtra(CLONE_ACK, Boolean.TRUE);
		} else pd.removeDescExtra(CLONE_ACK);
		pd.flush();
		Project.saved = false;
	}

	/** first 8 hex digits + '…' (display only) */
	public static String shortUuid(String uuid) {
		if(uuid == null)return "";
		return uuid.length() > 8 ? uuid.substring(0, 8) + "…" : uuid;
	}
}
