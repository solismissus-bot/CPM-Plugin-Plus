package com.tom.cpm.blockbench;

import com.tom.cpl.config.ModConfigFile;
import com.tom.cpl.gui.elements.Panel;
import com.tom.cpl.gui.elements.PopupMenu;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.web.client.WebMC;

import elemental2.dom.DomGlobal;
import elemental2.webstorage.Storage;
import elemental2.webstorage.WebStorageWindow;

public class BBInstance extends WebMC {

	// CPM+ : own localStorage key so settings don't clash with the official plugin
	public static final String CONFIG_KEY = "cpm_plugin_plus_config";
	private static final String OFFICIAL_CONFIG_KEY = "cpm_plugin_config";

	public BBInstance() {
		super(new ModConfigFile(DomGlobal.window, true, migrateConfig()), true, true);
	}

	/** CPM+ : first start of the fork: copy the official plugin's settings once (read only, never written back) */
	private static String migrateConfig() {
		try {
			Storage ls = WebStorageWindow.of(DomGlobal.window).localStorage;
			if (ls != null && ls.getItem(CONFIG_KEY) == null) {
				String old = ls.getItem(OFFICIAL_CONFIG_KEY);
				if (old != null)ls.setItem(CONFIG_KEY, old);
			}
		} catch (Throwable e) {
		}
		return CONFIG_KEY;
	}

	@Override
	public String getMCVersion() {
		return "blockbench";
	}

	@Override
	public void populatePlatformSettings(String group, Panel panel) {
		super.populatePlatformSettings(group, panel);
		switch (group) {
		case "filePopup": {
			PopupMenu pp = (PopupMenu) panel;
			pp.addButton(panel.getGui().i18nFormat("bb-button.viewInBB"), ProjectConvert::viewInBB);
		}
		break;
		}
	}

	@Override
	public String getAppID() {
		return "Blockbench CPM Plugin";// CPM+ : kept unchanged, the paste server identifies the client by this id
	}

	@Override
	protected String getLangChangeDesc() {
		return "bb-label.language.reloadRequired.desc";
	}
}
