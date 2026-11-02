package com.tom.cpm.blockbench.util;

import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.web.client.util.I18n;

/**
 * CPM+ (INFRA3): undo for project-level CPM data, which Blockbench's Ctrl+Z does NOT cover (Project.cpm_data and
 * the project flags hideHeadIfSkull / removeBedOffset / invisGlow).
 *
 * <pre>
 * ProjectDataUndo.Snapshot s = ProjectDataUndo.snapshot();
 * ...change Project.getData() fields, flush(), flags...
 * ProjectDataUndo.offerUndo(s, "tipo de braço: Alex");   // toast "Feito: ... Clique aqui para desfazer."
 * </pre>
 *
 * restore() puts the exact cpm_data string and flags back, re-reads ProjectData (keeping the session-only
 * re-export path) and marks the project unsaved. Never tell the user Ctrl+Z undoes these changes.
 */
public final class ProjectDataUndo {

	private ProjectDataUndo() {
	}

	public static final class Snapshot {
		private String pluginData;
		private boolean hideHeadIfSkull, removeBedOffset, invisGlow;
		private String lastExportPath;
		private String projectUuid;

		public String getPluginData() {
			return pluginData;
		}

		public String getProjectUuid() {
			return projectUuid;
		}
	}

	/** flushes the cached ProjectData, then records the cpm_data string, the three flags and the re-export path */
	public static Snapshot snapshot() {
		ProjectData pd = Project.getData();
		pd.flush();
		Snapshot s = new Snapshot();
		s.pluginData = Project.pluginData;
		s.hideHeadIfSkull = Project.hideHeadIfSkull;
		s.removeBedOffset = Project.removeBedOffset;
		s.invisGlow = Project.invisGlow;
		s.lastExportPath = pd.lastExportPath;
		s.projectUuid = Project.getUuid();
		return s;
	}

	/**
	 * Puts the snapshot back (exact string + flags), re-reads ProjectData, Project.saved = false.
	 * @return false (nothing changed) when another project tab is open now
	 */
	public static boolean restore(Snapshot s) {
		if (s == null)return false;
		String uuid = Project.getUuid();
		if (uuid == null || (s.projectUuid != null && !s.projectUuid.equals(uuid)))return false;
		Project.pluginData = s.pluginData;
		Project.hideHeadIfSkull = s.hideHeadIfSkull;
		Project.removeBedOffset = s.removeBedOffset;
		Project.invisGlow = s.invisGlow;
		ProjectData nd = new ProjectData(true);
		nd.lastExportPath = s.lastExportPath;
		Project.setData(nd);
		Project.saved = false;
		return true;
	}

	/**
	 * Shows the 'done' toast with an undo click: "Feito: &lt;what&gt;. Clique aqui para desfazer." Clicking restores
	 * the snapshot (or explains why it can't, e.g. another project tab is open).
	 */
	public static void offerUndo(Snapshot before, String what) {
		PlusDialogs.toast(I18n.format("cpmplus.w0binfra.msg.doneUndoToast", what), "undo", 15000, () -> {
			if (restore(before))PlusDialogs.quick(I18n.get("cpmplus.w0binfra.msg.undone"), 2000);
			else PlusDialogs.quick(I18n.get("cpmplus.w0binfra.msg.undoOtherProject"), 4000);
		});
	}
}
