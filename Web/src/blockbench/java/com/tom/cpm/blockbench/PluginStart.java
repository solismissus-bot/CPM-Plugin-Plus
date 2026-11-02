package com.tom.cpm.blockbench;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.core.client.EntryPoint;

import com.tom.cpm.blockbench.ee.EmbeddedEditorHandler;
import com.tom.cpm.blockbench.features.*;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.CPMVisuals;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Blockbench.CallbackEvent;
import com.tom.cpm.blockbench.proxy.Codec;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.NodePreviewController;
import com.tom.cpm.blockbench.proxy.Plugin;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.web.client.FS;
import com.tom.cpm.web.client.LocalStorageFS;
import com.tom.cpm.web.client.WebMC;
import com.tom.cpm.web.client.java.Java;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.LoggingPrintStream;
import com.tom.ugwt.client.ExceptionUtil;

import elemental2.dom.DomGlobal;

public class PluginStart implements EntryPoint {
	public static List<Runnable> cleanup = new ArrayList<>();

	@Override
	public void onModuleLoad() {
		if (EmbeddedEditorHandler.isRunningInEmbeddedEditor()) {
			EmbeddedEditorHandler.run();
			return;
		}
		System.setOut(new LoggingPrintStream("STDOUT", DomGlobal.console::info));
		System.setErr(new LoggingPrintStream("STDERR", DomGlobal.console::error));
		RenderSystem.preloaded(() -> {
			String initError = null;
			try {
				FS.setImpl(new LocalStorageFS(DomGlobal.window));
				new BBInstance() {

					@Override
					protected String buildPlatformString() {
						return Java.getPlatform() + " BB " + Blockbench.version + (!Global.isApp() ? " (Web)" : "") + " CPM+ " + System.getProperty("cpm.version");
					}
				};
				DomGlobal.console.log(PlusInfo.SHORT_NAME + " loading " + WebMC.platform);
			} catch (Throwable e) {
				StringBuilder sb = new StringBuilder();
				sb.append("Error loading plugin:\n");
				sb.append(ExceptionUtil.getStackTrace(e));
				DomGlobal.console.error(sb.toString());
				initError = sb.toString();
			}

			// CPM+ : fork identity. Blockbench reads 'title' (the old code only set 'name', which BB ignores).
			Plugin.PluginProperties prop = new Plugin.PluginProperties();
			prop.title = PlusInfo.DISPLAY_NAME;
			prop.name = PlusInfo.DISPLAY_NAME;
			prop.author = PlusInfo.AUTHOR;
			prop.variant = "both";
			prop.description = PlusInfo.DESCRIPTION;
			prop.about = PlusInfo.ABOUT_MD;
			prop.website = PlusInfo.WEBSITE;
			prop.repository = PlusInfo.REPOSITORY;
			// bug_tracker intentionally unset: fork bugs should not be filed on the upstream tracker
			prop.await_loading = true;// adds a format: must be ready before projects are opened
			prop.version = System.getProperty("cpm.version");
			prop.tags = new String[] {"Minecraft: Java Edition", "Modded"};
			prop.icon = "icon-player";
			if (initError == null) {
				prop.onload = PluginStart::onLoad;
				prop.onunload = PluginStart::onUnload;
			} else {
				// CPM+ : the core failed to start, don't run onLoad in a broken state, tell the user instead
				final String err = initError;
				prop.onload = () -> DomGlobal.alert(PlusInfo.DISPLAY_NAME + " failed to start. Details are in the console (Help > Developer > Open Dev Tools).\n\n" + (err.length() > 600 ? err.substring(0, 600) + "..." : err));
				prop.onunload = () -> {};
			}
			prop.min_version = "5.0.0";
			Plugin.register(System.getProperty("cpm.pluginId"), prop);
		});
	}

	/** CPM+ : window-global slot holding the unload function of the currently active CPM+ instance */
	/**
	 * CPM+ : Blockbench 5.2 (web, URL-loaded plugins) evaluates the plugin file twice, creating two
	 * independent GWT instances (statics are not shared) that both run onLoad. Unload the previously
	 * started instance first (through a plain JS function kept on window), so actions/listeners/formats
	 * are not registered twice and the second instance does not mistake the first one's 'cpm' format
	 * for the official plugin.
	 */
	private static native void takeOverPreviousInstance() /*-{
		try {
			var key = '__cpm_plugin_plus_active_unload';
			var prev = $wnd[key];
			var mine = @com.tom.cpm.blockbench.PluginStart::myUnloadFn;
			if (!mine) {
				mine = function() { @com.tom.cpm.blockbench.PluginStart::onUnload()(); };
				@com.tom.cpm.blockbench.PluginStart::myUnloadFn = mine;
			}
			if (prev && prev !== mine) {
				console.log('CPM Plugin+: unloading previously started instance');
				try { prev(); } catch (e) {}
			}
			$wnd[key] = mine;
		} catch (e) {}
	}-*/;

	/** CPM+ : clear the window slot if it still points at this instance */
	private static native void releaseInstanceSlot() /*-{
		try {
			var key = '__cpm_plugin_plus_active_unload';
			var mine = @com.tom.cpm.blockbench.PluginStart::myUnloadFn;
			if (mine && $wnd[key] === mine) delete $wnd[key];
		} catch (e) {}
	}-*/;

	private static Object myUnloadFn;

	public static void onLoad() {
		try {
			takeOverPreviousInstance();// CPM+ : see above, must run before anything is registered
			PlusInfo.checkOfficialPluginBeforeInit();// CPM+ : must run before CPMCodec replaces Formats.cpm
			PlusTestHooks.install();// CPM+ (W0B): window.__cpmplus, test mode only
			CPMCodec.init();
			CPMVisuals.init();
			ProjectGenerator.initDialog();
			BBActions.load();
			PlusMenus.init();// CPM+ (W0B): 'CPM+' menu with its submenus
			PlusTools.load();// CPM+ : validate, re-export, interpolation tools, about
			installFeatures();// CPM+ (W0B): every v0.8 feature package, each isolated
			PlusMenus.updateMenuBar();
			PlusInfo.checkOfficialPluginLater();
		} catch (Exception e) {
			PopupDialogs.displayError(I18n.formatNl("bb-label.error.initFailed"), e);
			onUnload();
			cleanup.clear();
		}
	}

	public static void onUnload() {
		uninstallFeatures();// CPM+ (W0B): feature packages first (reverse order), then the core cleanup
		cleanup.forEach(r -> {
			try {
				r.run();
			} catch (Throwable e) {
			}
		});
		cleanup.clear();// CPM+ : don't run the same cleanup twice after a reload
		PlusMenus.updateMenuBar();// CPM+ (W0B): rebuilds the menu bar so no label of a removed menu stays
		PlusTestHooks.uninstall();
		releaseInstanceSlot();
		DomGlobal.console.log("Unloaded " + PlusInfo.SHORT_NAME);
	}

	// ------------------------------------------------------------------ CPM+ (W0B-INFRA) feature installers

	/** one v0.8 feature package: its installer class (features/&lt;Stem&gt;Install) */
	private static final class Feature {
		final String name;
		final Runnable install, uninstall;

		Feature(String name, Runnable install, Runnable uninstall) {
			this.name = name;
			this.install = install;
			this.uninstall = uninstall;
		}
	}

	/**
	 * The installer registry, in plan order. Each package fills ONLY its own install()/uninstall(); a package
	 * never edits this list (every installer of the plan already has its line).
	 */
	private static List<Feature> features() {
		List<Feature> l = new ArrayList<>();
		l.add(new Feature("W0bInfraInstall", W0bInfraInstall::install, W0bInfraInstall::uninstall));
		l.add(new Feature("W0cExportcoreInstall", W0cExportcoreInstall::install, W0cExportcoreInstall::uninstall));
		l.add(new Feature("W1IoInstall", W1IoInstall::install, W1IoInstall::uninstall));
		l.add(new Feature("W1ImportInstall", W1ImportInstall::install, W1ImportInstall::uninstall));
		l.add(new Feature("W1PreviewInstall", W1PreviewInstall::install, W1PreviewInstall::uninstall));
		l.add(new Feature("W1StorageInstall", W1StorageInstall::install, W1StorageInstall::uninstall));
		l.add(new Feature("W2WarnI18nInstall", W2WarnI18nInstall::install, W2WarnI18nInstall::uninstall));
		l.add(new Feature("W2AnimcoreInstall", W2AnimcoreInstall::install, W2AnimcoreInstall::uninstall));
		l.add(new Feature("W2ToolsInstall", W2ToolsInstall::install, W2ToolsInstall::uninstall));
		l.add(new Feature("W3ModelexportInstall", W3ModelexportInstall::install, W3ModelexportInstall::uninstall));
		l.add(new Feature("W3CopyxfInstall", W3CopyxfInstall::install, W3CopyxfInstall::uninstall));
		l.add(new Feature("W3SettingsInstall", W3SettingsInstall::install, W3SettingsInstall::uninstall));
		l.add(new Feature("W3AnimtoolsInstall", W3AnimtoolsInstall::install, W3AnimtoolsInstall::uninstall));
		l.add(new Feature("W3AnimgenInstall", W3AnimgenInstall::install, W3AnimgenInstall::uninstall));
		l.add(new Feature("W4WizardInstall", W4WizardInstall::install, W4WizardInstall::uninstall));
		l.add(new Feature("W4ExportfixInstall", W4ExportfixInstall::install, W4ExportfixInstall::uninstall));
		l.add(new Feature("W4TexInstall", W4TexInstall::install, W4TexInstall::uninstall));
		l.add(new Feature("W4RigInstall", W4RigInstall::install, W4RigInstall::uninstall));
		l.add(new Feature("W4Anim2Install", W4Anim2Install::install, W4Anim2Install::uninstall));
		l.add(new Feature("W5MergeInstall", W5MergeInstall::install, W5MergeInstall::uninstall));
		l.add(new Feature("W5CmdsInstall", W5CmdsInstall::install, W5CmdsInstall::uninstall));
		l.add(new Feature("W5VisInstall", W5VisInstall::install, W5VisInstall::uninstall));
		l.add(new Feature("W5StickInstall", W5StickInstall::install, W5StickInstall::uninstall));
		l.add(new Feature("W5EffectsInstall", W5EffectsInstall::install, W5EffectsInstall::uninstall));
		l.add(new Feature("W6PlatformInstall", W6PlatformInstall::install, W6PlatformInstall::uninstall));
		l.add(new Feature("W6HelpInstall", W6HelpInstall::install, W6HelpInstall::uninstall));
		l.add(new Feature("W6SaveInstall", W6SaveInstall::install, W6SaveInstall::uninstall));
		l.add(new Feature("W6AnimxInstall", W6AnimxInstall::install, W6AnimxInstall::uninstall));
		l.add(new Feature("W6Tex2Install", W6Tex2Install::install, W6Tex2Install::uninstall));
		l.add(new Feature("W6ParticlesInstall", W6ParticlesInstall::install, W6ParticlesInstall::uninstall));
		l.add(new Feature("W7PoseInstall", W7PoseInstall::install, W7PoseInstall::uninstall));
		l.add(new Feature("W7RigInstall", W7RigInstall::install, W7RigInstall::uninstall));
		l.add(new Feature("W7ViewInstall", W7ViewInstall::install, W7ViewInstall::uninstall));
		l.add(new Feature("W7TexartInstall", W7TexartInstall::install, W7TexartInstall::uninstall));
		l.add(new Feature("W8LateInstall", W8LateInstall::install, W8LateInstall::uninstall));
		l.add(new Feature("W10GatedInstall", W10GatedInstall::install, W10GatedInstall::uninstall));
		l.add(new Feature("ExtraInstall", com.tom.cpm.blockbench.features.ExtraInstall::install, com.tom.cpm.blockbench.features.ExtraInstall::uninstall));// CPM+ beta2: F5 F6 G63 F4 E2+G61 G3
		return l;
	}

	/** installers that ran in this instance (uninstalled in reverse order) */
	private static final List<Feature> installed = new ArrayList<>();

	/** runs every installer once, each in its own try/catch: one failing feature never breaks the plugin */
	private static void installFeatures() {
		List<String> failed = new ArrayList<>();
		for (Feature f : features()) {
			try {
				installed.add(f);// also uninstalled when install() failed half way
				if (f.name.equals(PlusTestHooks.failInstall()))throw new IllegalStateException("CPM+ QA: deliberate install failure of " + f.name);
				f.install.run();
				PlusTestHooks.installed(f.name);
			} catch (Throwable e) {
				failed.add(f.name);
				DomGlobal.console.error(PlusInfo.SHORT_NAME + ": feature " + f.name + " failed to install:\n" + ExceptionUtil.getStackTrace(e));
			}
		}
		if (!failed.isEmpty()) {
			PlusTestHooks.setLast("installErrors", String.join(",", failed));
			// user message: how many, and what to do (class names and stack traces only in the console)
			PlusDialogs.toast(I18n.format("cpmplus.w0binfra.error.featuresFailed", failed.size()), "warning", 15000, null);
		}
	}

	private static void uninstallFeatures() {
		for (int i = installed.size() - 1; i >= 0; i--) {
			Feature f = installed.get(i);
			try {
				f.uninstall.run();
			} catch (Throwable e) {
				DomGlobal.console.warn(PlusInfo.SHORT_NAME + ": feature " + f.name + " failed to uninstall", e);
			}
			PlusTestHooks.uninstalled(f.name);
		}
		installed.clear();
	}

	public static void addEventListener(String id, CallbackEvent cb) {
		Blockbench.on(id, cb);
		cleanup.add(() -> Blockbench.removeListener(id, cb));
	}

	public static void addEventListener(NodePreviewController npc, String id, CallbackEvent cb) {
		npc.on(id, cb);
		cleanup.add(() -> npc.removeListener(id, cb));
	}

	public static void addEventListener(Codec c, String id, CallbackEvent cb) {
		c.on(id, cb);
		cleanup.add(() -> c.removeListener(id, cb));
	}
}
