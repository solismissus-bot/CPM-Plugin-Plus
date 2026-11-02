package com.tom.cpm.blockbench.convert;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs.UserException;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.project.ProjectFile;
import com.tom.cpm.shared.editor.project.ProjectIO;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.ImageIO;

import elemental2.promise.Promise;

/**
 * CPM+ (load E3): what the user sees while a .cpmproject opens - status bar text + progress bar per phase (read the
 * zip, load the model, prepare the textures, build it in Blockbench), a yield so the text paints before each long
 * synchronous step, everything reset when the last running import ends (also on error). Two imports never mix:
 * each one builds its Blockbench project in one synchronous step (Global.newProject + BlockbenchImport.doImport0).
 * Also the 'cpmplus:*' timers of the QA harness and the plain-words error for a PNG nobody can read.
 */
public final class ImportProgress {
	/** imports running now */
	private static int active;

	public static final String ZIP = "zip", PNG = "png", LOAD = "load", TEXTURES = "textures", BUILD = "build";

	private ImportProgress() {
	}

	public static boolean isBusy() {
		return active > 0;
	}

	/** starts an import; every begin() is followed by exactly one end() (see {@link #finish}) */
	public static void begin() {
		active++;
		PlusTestHooks.begin();
		phase(ZIP);
	}

	/** status text + progress of one phase (only while an import runs) */
	public static void phase(String name) {
		if(active <= 0)return;
		double p;
		switch (name) {
		case ZIP:
			p = 0.05;
			break;
		case PNG:
			p = 0.15;
			break;
		case LOAD:
			p = 0.25;
			break;
		case TEXTURES:
			p = 0.6;
			break;
		case BUILD:
			p = 0.75;
			break;
		default:
			return;
		}
		PlusDialogs.status(I18n.get("cpmplus.w1io.progress." + name));
		PlusDialogs.progress(p);
	}

	/** resolves after the browser had a chance to paint (next animation frame, at most ~100 ms later) */
	public static native Promise<Void> yieldFrame()/*-{
		return new $wnd.Promise(function(res) {
			var done = false;
			var go = function() {
				if (done) return;
				done = true;
				res(null);
			};
			try {
				if (typeof $wnd.requestAnimationFrame === 'function') $wnd.requestAnimationFrame(function() {
					$wnd.setTimeout(go, 0);
				});
			} catch (e) {
			}
			$wnd.setTimeout(go, 100);
		});
	}-*/;

	/** ProjectIO.loadProject with the 'cpmplus:loadProject' timer; config.json parsed once for all loaders */
	public static void loadProject(Editor editor) throws Exception {
		PlusTestHooks.time("loadProject");
		ProjectFile pf = editor.project;
		pf.setLoadCache(true);
		try {
			ProjectIO.loadProject(editor, pf);
		} catch (Exception e) {
			throw translate(e);
		} finally {
			pf.setLoadCache(false);
			PlusTestHooks.timeEnd("loadProject");
		}
	}

	/** a PNG nobody could read -> plain words (pt-BR first) instead of a stack trace */
	public static Exception translate(Exception e) {
		Throwable t = e;
		for (int i = 0; t != null && i < 8; i++, t = t.getCause()) {
			if(t instanceof ImageIO.UnsupportedPngException)
				return new UserException(I18n.format("cpmplus.w1io.error.pngUnsupported", String.valueOf(((ImageIO.PngException) t).getPath())));
			if(t instanceof ImageIO.PngException)
				return new UserException(I18n.format("cpmplus.w1io.error.pngDamaged", String.valueOf(((ImageIO.PngException) t).getPath())));
		}
		return e;
	}

	/** ends the import when p settles (resolved or rejected): progress hidden, status reset, next import allowed */
	public static <T> Promise<T> finish(Promise<T> p) {
		boolean[] done = new boolean[1];
		Runnable fin = () -> {
			if(done[0])return;
			done[0] = true;
			end();
		};
		p.then(v -> {
			fin.run();
			return null;
		}, err -> {
			fin.run();
			return null;
		});
		return p;
	}

	public static void end() {
		if(active <= 0)return;
		active--;
		PlusTestHooks.end();
		if(active == 0) {
			PlusDialogs.progress(0);
			PlusDialogs.status(null);
		}
	}

	/** plugin unload: nothing is running any more */
	public static void reset() {
		while (active > 0)end();
	}

	/** instrumentation of ProjectFile (W1IoInstall): QA timers / counters + the phases above */
	public static ProjectFile.Instrumentation instrumentation() {
		return new ProjectFile.Instrumentation() {

			@Override
			public void time(String name) {
				PlusTestHooks.time(name);
				phase(name);
			}

			@Override
			public void timeEnd(String name) {
				PlusTestHooks.timeEnd(name);
			}

			@Override
			public void count(String name) {
				PlusTestHooks.count(name);
			}
		};
	}
}
