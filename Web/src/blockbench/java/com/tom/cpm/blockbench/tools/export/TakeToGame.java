package com.tom.cpm.blockbench.tools.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.NativeFs;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsDate;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W3-MODELEXPORT): "Levar pro jogo\u2026" (3 cards: skin with the model inside / .cpmmodel / .cpmproject, each
 * saying who sees the model) and G23 "Testar no jogo agora" (Ctrl+Alt+T): a test copy written to the game's
 * player_models as 'cpmplus_&lt;slug&gt;.cpmmodel' (no lock, local overflow link, description 'CPM+ teste \u00b7 date') and
 * the command '/cpmclient set_model cpmplus_&lt;slug&gt;.cpmmodel' copied - never typed into the game.
 */
public final class TakeToGame {
	/** PlusPrefs: game folder per project (key = project file path or name) */
	static final String PREF_GAME_DIRS = "cpmplus.testGameDirs";
	/** description prefix that marks a test copy written by CPM+ (replaced without asking) */
	static final String TEST_DESC = "CPM+ teste";

	private TakeToGame() {
	}

	// ====================================================================== hub

	public static void open() {
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("skin", "cpmplus.w3modelexport.take.skin", "cpmplus.w3modelexport.take.skin.who"));
		rows.add(FormRow.buttons("skinBtn", "", new String[] {"cpmplus.w3modelexport.take.skin.btn"}, i -> later(SkinExport::open)));
		rows.add(FormRow.separator());
		rows.add(FormRow.info("model", "cpmplus.w3modelexport.take.model", "cpmplus.w3modelexport.take.model.who"));
		rows.add(FormRow.buttons("modelBtn", "", new String[] {"cpmplus.w3modelexport.take.model.btn", "cpmplus.w3modelexport.test.action"}, i -> later(i == 0 ? ModelExport::exportAction : TakeToGame::testInGame)));
		rows.add(FormRow.separator());
		rows.add(FormRow.info("project", "cpmplus.w3modelexport.take.project", "cpmplus.w3modelexport.take.project.who"));
		rows.add(FormRow.buttons("projectBtn", "", new String[] {"cpmplus.w3modelexport.take.project.btn"}, i -> later(ProjectConvert::export)));
		PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
		fo.width = 600;
		fo.confirmKey = "cpmplus.w3modelexport.btn.close";
		fo.cancelKey = "cpmplus.w3modelexport.btn.cancel";
		PlusDialogs.form("cpmplus_w3modelexport_take", "cpmplus.w3modelexport.take.title", rows, fr -> {
		}, fo);
	}

	/** closes the hub, then runs the card's action */
	private static void later(Runnable r) {
		try {
			Object d = com.tom.cpm.blockbench.proxy.Global.getGlobal("Dialog");
			Object open = d != null ? jsinterop.base.Js.asPropertyMap(d).get("open") : null;
			if (open != null && com.tom.cpm.blockbench.proxy.Global.hasFunction(open, "hide"))jsinterop.base.Js.<Dialog>uncheckedCast(open).hide();
		} catch (Throwable e) {
		}
		DomGlobal.setTimeout(a -> {
			try {
				r.run();
			} catch (Throwable e) {
				ModelExport.report(e);
			}
		}, 50);
	}

	// ====================================================================== G23

	/** 'cpmplus_&lt;slug&gt;.cpmmodel' (slug fallback 'modelo') */
	public static String testFileName() {
		return "cpmplus_" + ModelExport.slug(ModelExport.projectName(), "modelo") + ".cpmmodel";
	}

	public static String command(String fileName) {
		return "/cpmclient set_model " + fileName;
	}

	/** the test copy's description: no lock, 'CPM+ teste \u00b7 &lt;date&gt;', file labelled '(teste, sem trava)' */
	static ModelDescription testDescription(Editor e) {
		ModelDescription d = ModelExport.unlocked(ModelExport.descriptionFor(e));
		d.name = ModelExport.projectName() + " " + I18n.get("cpmplus.w3modelexport.test.label");
		d.desc = TEST_DESC + " \u00b7 " + dateText();
		return d;
	}

	static String dateText() {
		JsDate d = new JsDate();
		return (int) d.getFullYear() + "-" + two(d.getMonth() + 1) + "-" + two(d.getDate()) + " " + two(d.getHours()) + ":" + two(d.getMinutes());
	}

	private static String two(double v) {
		int i = (int) v;
		return i < 10 ? "0" + i : String.valueOf(i);
	}

	/** CPM+ &gt; Testar no jogo agora (Ctrl+Alt+T) */
	public static void testInGame() {
		PlusTestHooks.track(testFlow().then(r -> null, err -> {
			ModelExport.report(err);
			return null;
		}));
	}

	static Promise<String> testFlow() {
		String file = testFileName();
		Editor[] ed = new Editor[1];
		// validators first: the export warning dialog (cancel = nothing written)
		return ModelExport.prepareEditor(true, false).then(e -> {
			ed[0] = e;
			String le = ModelExport.layerError(e);
			if (le != null)return Promise.reject(new ModelExport.UserError(I18n.get(le)));
			return ModelExport.buildModelBytes(e, testDescription(e), ModelExport.OverflowMode.LOCAL);
		}).then(bytes -> {
			if (!PlusDialogs.isDesktop()) {
				return ModelExport.download(file.substring(0, file.length() - ".cpmmodel".length()), bytes).then(n -> {
					done(n, null);
					return Promise.resolve(n);
				});
			}
			return gameDir().then(dir -> {
				String models = NativeFs.join(dir, "player_models");
				String path = NativeFs.join(models, file);
				boolean ours = ModelExport.writtenByUs(path) || ModelExport.existingDesc(path).startsWith(TEST_DESC);
				return ModelExport.confirmOverwrite(path, ours).then(go -> {
					if (!Boolean.TRUE.equals(go))return Promise.<String>reject(new ModelExport.Cancelled());
					return ModelExport.writeDesktop(path, bytes).then(p -> {
						done(file, p);
						return Promise.resolve(p);
					});
				});
			});
		});
	}

	static void done(String fileName, String path) {
		String cmd = command(fileName);
		PlusDialogs.copyText(cmd);
		PlusDialogs.toast(I18n.get("cpmplus.w3modelexport.test.toast"), "sports_esports", 12000, null);
		if (PlusTestHooks.active()) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("file", fileName);
			m.set("path", path);
			m.set("command", cmd);
			PlusTestHooks.setLast("w3modelexport.test", m);
		}
		Dialog.DialogProperties p = new Dialog.DialogProperties();
		p.id = "cpmplus_w3modelexport_test_done";
		p.title = I18n.get("cpmplus.w3modelexport.test.doneTitle");
		String badge = "<span style='background: #2e7d32; color: #fff; border-radius: 4px; padding: 1px 8px; font-weight: bold;'>"
				+ PlusDialogs.esc(I18n.get("cpmplus.w3modelexport.test.badge")) + "</span> ";
		String where = path != null ? I18n.format("cpmplus.w3modelexport.done.saved", path) : I18n.format("cpmplus.w3modelexport.test.downloaded", fileName);
		p.setHtml("<p>" + badge + PlusDialogs.esc(fileName) + "</p>",
				"<p>" + PlusDialogs.escLines(where) + "</p>",
				"<p><b>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.test.toast")) + "</b></p>",
				"<p><code>" + PlusDialogs.esc(cmd) + "</code></p>",
				"<p style='opacity: 0.8;'>" + PlusDialogs.escLines(I18n.get("cpmplus.w3modelexport.test.limits")) + "</p>");
		p.buttons = new String[] {I18n.get("cpmplus.w3modelexport.btn.copyAgain"), I18n.get("cpmplus.w3modelexport.btn.copyReset"), I18n.get("cpmplus.w3modelexport.btn.close")};
		p.confirmIndex = 2;
		p.cancelIndex = 2;
		p.onButton = i -> {
			if (i == 0)PlusDialogs.copyText(cmd);
			else if (i == 1)PlusDialogs.copyText("/cpmclient reset_model");
			return i == 2;
		};
		new Dialog(p).show();
	}

	// ---------------------------------------------------------------------- game folder

	private static String projectKey() {
		String k = Project.save_path != null && !Project.save_path.isEmpty() ? Project.save_path : Project.name;
		return k != null ? k : "";
	}

	/** remembered folder of this project, else detection + confirmation (always asked the first time) */
	static Promise<String> gameDir() {
		String fsRoot = PlusTestHooks.fsRoot();
		if (fsRoot != null)return Promise.resolve(fsRoot);// test mode: only the harness folder, nothing detected
		Object m = PlusPrefs.getJson(PREF_GAME_DIRS);
		if (m instanceof Map) {
			Object v = ((Map<?, ?>) m).get(projectKey());
			if (v instanceof String && !((String) v).isEmpty())return Promise.resolve((String) v);
		}
		return askGameDir();
	}

	static void remember(String dir) {
		Object m = PlusPrefs.getJson(PREF_GAME_DIRS);
		Map<String, Object> map = new LinkedHashMap<>();
		if (m instanceof Map)((Map<?, ?>) m).forEach((k, v) -> map.put(String.valueOf(k), v));
		map.put(projectKey(), dir);
		PlusPrefs.set(PREF_GAME_DIRS, map);
	}

	/** forget the folder of this project (next test asks again) */
	public static void forgetGameDir() {
		Object m = PlusPrefs.getJson(PREF_GAME_DIRS);
		if (!(m instanceof Map))return;
		Map<String, Object> map = new LinkedHashMap<>();
		((Map<?, ?>) m).forEach((k, v) -> map.put(String.valueOf(k), v));
		map.remove(projectKey());
		PlusPrefs.set(PREF_GAME_DIRS, map);
	}

	/** candidate game folders that have CPM (a mods/ jar named like the mod, or a player_models folder) */
	static List<String> detect() {
		List<String> out = new ArrayList<>();
		if (!NativeFs.available())return out;
		String appData = NativeFs.env("APPDATA");
		String home = NativeFs.home();
		List<String> cands = new ArrayList<>();
		if (appData != null) {
			cands.add(NativeFs.join(appData, ".minecraft"));
			addChildren(cands, NativeFs.join(appData, "PrismLauncher" + sep(appData) + "instances"), new String[] {".minecraft", "minecraft"});
			addChildren(cands, NativeFs.join(appData, "ModrinthApp" + sep(appData) + "profiles"), new String[] {""});
		}
		if (home != null) {
			addChildren(cands, NativeFs.join(home, "curseforge" + sep(home) + "minecraft" + sep(home) + "Instances"), new String[] {""});
			if (appData == null)cands.add(NativeFs.join(home, ".minecraft"));
		}
		for (String c : cands)if (valid(c))out.add(c);
		return out;
	}

	private static String sep(String p) {
		return p.indexOf('\\') >= 0 ? "\\" : "/";
	}

	private static void addChildren(List<String> out, String dir, String[] subs) {
		Object fs = NativeFs.fs(dir, I18n.get("cpmplus.w3modelexport.fs.detect"));
		if (fs == null || !NativeFs.isDir(fs, dir))return;
		for (String n : NativeFs.list(fs, dir)) {
			for (String s : subs) {
				String p = s.isEmpty() ? NativeFs.join(dir, n) : NativeFs.join(NativeFs.join(dir, n), s);
				if (NativeFs.isDir(fs, p))out.add(p);
			}
		}
	}

	/** a game folder with CPM: mods/ has a jar like 'customizable player models', or player_models exists */
	static boolean valid(String dir) {
		Object fs = NativeFs.fs(dir, I18n.get("cpmplus.w3modelexport.fs.detect"));
		if (fs == null || !NativeFs.isDir(fs, dir))return false;
		if (NativeFs.isDir(fs, NativeFs.join(dir, "player_models")))return true;
		String mods = NativeFs.join(dir, "mods");
		for (String n : NativeFs.list(fs, mods)) {
			String l = n.toLowerCase();
			if (l.endsWith(".jar") && l.matches(".*customizable.?player.?models.*"))return true;
		}
		return false;
	}

	/** dialog: detected folders + 'Procurar\u2026'; always confirmed; remembered for this project */
	static Promise<String> askGameDir() {
		return new Promise<>((res, rej) -> {
			List<String> found = detect();
			Map<String, String> opts = new LinkedHashMap<>();
			for (int i = 0; i < found.size(); i++)opts.put(String.valueOf(i), found.get(i));
			List<FormRow> rows = new ArrayList<>();
			rows.add(FormRow.info("why", "", "cpmplus.w3modelexport.gamedir.text"));
			if (!opts.isEmpty())rows.add(FormRow.radio("dir", "cpmplus.w3modelexport.gamedir.found", opts, "0"));
			else rows.add(FormRow.info("none", "", "cpmplus.w3modelexport.gamedir.none"));
			String[] chosen = new String[1];
			boolean[] closed = new boolean[1];
			rows.add(FormRow.buttons("browse", "", new String[] {"cpmplus.w3modelexport.gamedir.browse"}, i -> {
				String d = PlusDialogs.pickDirectory("cpmplus.w3modelexport.gamedir.title", null);
				if (d == null)return;
				if (!valid(d)) {
					PlusDialogs.block(I18n.get("cpmplus.w3modelexport.gamedir.invalid"));
					return;
				}
				chosen[0] = d;
				PlusDialogs.quick(I18n.format("cpmplus.w3modelexport.gamedir.picked", d), 3000);
			}));
			PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
			fo.confirmKey = "cpmplus.w3modelexport.gamedir.use";
			fo.cancelKey = "cpmplus.w3modelexport.btn.cancel";
			fo.width = 560;
			fo.onCancel = () -> {
				if (!closed[0]) {
					closed[0] = true;
					rej.onInvoke(new ModelExport.Cancelled());
				}
			};
			PlusDialogs.form("cpmplus_w3modelexport_gamedir", "cpmplus.w3modelexport.gamedir.title", rows, fr -> {
				String d = chosen[0];
				if (d == null) {
					String idx = fr.getString("dir");
					if (idx != null && opts.containsKey(idx))d = opts.get(idx);
				}
				if (d == null) {
					fr.keepOpen();
					PlusDialogs.quick(I18n.get("cpmplus.w3modelexport.gamedir.pickFirst"), 3000);
					return;
				}
				closed[0] = true;
				remember(d);
				res.onInvoke(d);
			}, fo);
		});
	}
}
