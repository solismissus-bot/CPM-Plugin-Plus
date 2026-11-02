package com.tom.cpm.blockbench;

import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Plugin;
import com.tom.cpm.blockbench.proxy.Plugin.Plugins;

import elemental2.dom.DomGlobal;
import jsinterop.base.Js;

/**
 * CPM+ : fork identity, about/changelog text and detection of the official CPM plugin.
 *
 * Compatibility notes:
 *  - Plugin id is cpm_plugin_plus (set by the build, -PbbPluginId), so it installs next to the
 *    official cpm_plugin instead of replacing it.
 *  - The model format id stays "cpm" and the codec id stays "cpmproject": existing CPM .bbmodel
 *    files store the format id, and all cpm_* element/animation properties keep their names, so
 *    projects made with either plugin open in the other one.
 *  - Because the format id is shared, the two plugins cannot be active at the same time (the one
 *    loaded last owns Formats.cpm, and unloading either one used to delete it). Action and menu ids
 *    are prefixed (cpmplus_) so buttons/keybinds don't overwrite each other, and on load the fork
 *    detects the official plugin and asks the user to disable it.
 *  - Settings use their own localStorage key (cpm_plugin_plus_config, copied once from the official
 *    plugin's settings on first start).
 */
public class PlusInfo {
	public static final String DISPLAY_NAME = "CPM Plugin+ (Customizable Player Models)";
	public static final String SHORT_NAME = "CPM Plugin+";
	public static final String AUTHOR = "tom5454 (fork: CPM+)";
	public static final String OFFICIAL_PLUGIN_ID = "cpm_plugin";
	public static final String DESCRIPTION = "Improved fork of the Customizable Player Models plugin: lossless CPM animation round trips, model/texture fixes and quality-of-life tools. Disable the official CPM plugin while using this one.";
	public static final String WEBSITE = "https://github.com/tom5454/CustomPlayerModels";
	public static final String REPOSITORY = "https://github.com/tom5454/CustomPlayerModels";
	public static final String ISSUES = "https://github.com/tom5454/CustomPlayerModels/issues";

	/**
	 * CPM+ (G16): integer version of the CPM+ data written into CPM .bbmodel files (cpm_data.cpmplus.fmt). Raise it
	 * whenever a build writes new plugin data (new cpm_data keys, new cpm_plus_* properties). Files stamped with a
	 * higher number were saved by a newer CPM+ (yellow notice on open). Compare integers only, never version text.
	 */
	public static final int FORMAT_INT = 1;
	/**
	 * CPM+ (G16): oldest FORMAT_INT that can still edit files written by this build safely (cpmplus.minReader).
	 * Raise only for an incompatible change; older builds then show the stronger 'save as copy' notice.
	 */
	public static final int MIN_READER = 1;

	/** Fixes in this fork (shown in the plugin page 'about' and the CPM+ > About dialog) */
	public static final String[] CHANGES = {
			"Animations: visibility keyframes at the exact frame time (single-frame toggles) are no longer lost (#966).",
			"Animations: no extra in-between frames; CPM frames are imported on CPM's real time grid and exported back 1:1 (same frame count, interpolator and duration).",
			"Animations: interpolation is kept (Linear / Smooth = Polynomial or Trigonometric / Step) instead of always Linear.",
			"Animations: 0-length or 1-frame animations export at least one frame; looped animations close seamlessly; durations are rounded instead of truncated.",
			"Animations: colour keyframes are no longer multiplied by 255; staged 'p:' setup names, mustFinish, hidden, maxValue and other CPM-only fields survive the round trip.",
			"Animations: loading a .cpmproject with many animated parts no longer freezes Blockbench (keyframes are created in one batch, BB #3287).",
			"Models: colour-only cubes, scaling settings, wearer skin, quarter-pixel precision (#973), vanilla parts with 2nd layer, animated textures, render scale and skin type (slim) are preserved.",
			"Editor: Add Root / Item slots / Parrots / 2nd Layer can be undone; 2nd layer finds the skin texture, uses slim sizes and doesn't duplicate.",
			"Editor: hidden groups are restored when leaving Animate mode, and the plugin no longer touches other formats' animator UI (BB #3856).",
			"Editor: .cpmproject opens from File > Open, drag & drop and Recent; quick re-export to the last file.",
			"Editor: names are HTML-escaped in dialogs; dialogs use Blockbench 5 element lines; embedded editor reports errors and blocked popups.",
			"New 'CPM+' menu: Validate model, Re-export, Animation properties, Fix animation interpolation, CPM frame grid, About.",
	};

	public static final String ABOUT_MD;

	static {
		StringBuilder sb = new StringBuilder();
		sb.append("Unofficial fork of tom5454's Customizable Player Models Blockbench plugin.\n\n");
		sb.append("**Disable the official CPM plugin while this one is enabled** (both register the 'cpm' format).\n\n");
		sb.append("Project files (.cpmproject and CPM .bbmodel) are compatible with the official plugin.\n\n");
		sb.append("### Changes\n");
		for (String c : CHANGES)sb.append("- ").append(c).append('\n');
		sb.append("\nUpstream issue tracker: ").append(ISSUES).append('\n');
		ABOUT_MD = sb.toString();
	}

	private static boolean officialFormatSeen, warned;

	/** Run before CPMCodec.init(): a 'cpm' format that already exists belongs to another plugin. */
	public static void checkOfficialPluginBeforeInit() {
		try {
			Object f = Global.getFormats() != null ? Global.getFormats().get(CPMCodec.FORMAT_ID) : null;
			// CPM+ : a 'cpm' format registered by another CPM+ instance (Blockbench may start a
			// URL-loaded plugin twice) carries our tag and is not the official plugin.
			officialFormatSeen = f != null && Js.isTruthy(f) &&
					!Js.isTruthy(Js.asPropertyMap(f).get(CPMCodec.PLUS_TAG));
		} catch (Throwable e) {
		}
	}

	/** The official plugin may load after us, so check again a little later. */
	public static void checkOfficialPluginLater() {
		if (officialFormatSeen) {
			warnOfficial();
			return;
		}
		DomGlobal.setTimeout(__ -> {
			if (isOfficialPluginActive())warnOfficial();
		}, 3000);
	}

	public static boolean isOfficialPluginActive() {
		try {
			Plugin[] all = Plugins.all;
			if (all == null)return false;
			for (Plugin p : all) {
				if (p == null || p.id == null)continue;
				if (OFFICIAL_PLUGIN_ID.equals(p.id) && Js.isTruthy(p.installed) && !Js.isTruthy(p.disabled))return true;
			}
		} catch (Throwable e) {
		}
		return false;
	}

	private static void warnOfficial() {
		if (warned)return;
		warned = true;
		DomGlobal.console.warn(SHORT_NAME + ": the official CPM plugin (cpm_plugin) is also enabled");
		try {
			Dialog.DialogProperties dctr = new Dialog.DialogProperties();
			dctr.id = "cpmplus_official_warning";
			dctr.title = SHORT_NAME;
			dctr.setHtml("<p>The official <b>Customizable Player Models</b> plugin is also enabled.</p>" +
					"<p>Both plugins register the same CPM model format, so only one of them can work at a time and " +
					"unloading one can break the other. Please disable or uninstall one of them in " +
					"<i>File &gt; Plugins</i> and restart Blockbench.</p>" +
					"<p>Your projects are compatible with both plugins.</p>");
			dctr.singleButton = true;
			new Dialog(dctr).show();
		} catch (Throwable e) {
		}
	}

	/** About / changelog dialog content (trusted constant HTML) */
	public static String aboutHtml() {
		StringBuilder sb = new StringBuilder();
		sb.append("<div style='max-height: 60vh; overflow-y: auto;'>");
		sb.append("<p><b>").append(Dialog.esc(DISPLAY_NAME)).append("</b> ").append(Dialog.esc(System.getProperty("cpm.version"))).append("<br>");
		sb.append("by ").append(Dialog.esc(AUTHOR)).append("</p>");
		sb.append("<p>Unofficial fork. Disable the official CPM plugin while this one is enabled. Project files are compatible with the official plugin.</p>");
		sb.append("<h3>Fixes and additions</h3><ul>");
		for (String c : CHANGES)sb.append("<li>").append(Dialog.esc(c)).append("</li>");
		sb.append("</ul>");
		sb.append("<p>Known CPM issues and reports: <a href='").append(ISSUES).append("' target='_blank'>").append(ISSUES).append("</a></p>");
		sb.append("</div>");
		return sb.toString();
	}
}
