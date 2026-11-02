package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.web.client.java.Java;
import com.tom.ugwt.client.ExceptionUtil;

import elemental2.core.ArrayBuffer;
import elemental2.dom.DomGlobal;

/**
 * CPM+ (W0C-EXPORTCORE): extension points of the CPM export. Every registration is removed when the plugin unloads (or
 * with the returned Runnable).
 * <ul>
 * <li>{@link #register(ExportCheck)}: checks that add rows to the warning dialog. They run at the end of the
 * conversion, after the built-in model / animation warnings, on every conversion of a Blockbench project
 * (Codecs.cpmproject.export, re-export, 'Conferir modelo', buildEditor / convertHeadless). A check that throws is
 * skipped (console error), the export goes on.</li>
 * <li>{@link #beforeFinish(Consumer)}: last changes to the converted CPM editor after the warning dialog was
 * accepted and right before the ids are assigned and the file is written (also on headless conversions, so they
 * measure what would be written). An exception here stops the export with an error (never a half-made file).</li>
 * <li>{@link #translateException(Function)}: turns an export error into a plain user message (the first translator
 * that returns non-null wins); the error dialog then shows that text instead of a stack trace. Applies where the
 * error goes through ProjectConvert.reportExportError: the export and the re-export. ('Conferir modelo' in
 * PlusTools still shows the raw error dialog until its owner calls ProjectConvert.reportExportError.)</li>
 * <li>{@link #afterWrite(Consumer)}: called after the .cpmproject was written / downloaded, with the path (file name on
 * the web), the exact bytes and the editor they came from.</li>
 * </ul>
 */
public final class ExportHooks {
	private static final List<ExportCheck> CHECKS = new ArrayList<>();
	private static final List<Consumer<Editor>> BEFORE_FINISH = new ArrayList<>();
	private static final List<Function<Throwable, String>> TRANSLATORS = new ArrayList<>();
	private static final List<Consumer<ExportResult>> AFTER_WRITE = new ArrayList<>();

	private ExportHooks() {
	}

	/** adds rows to the warning dialog of a conversion */
	public static interface ExportCheck {
		void check(ExportContext ctx);
	}

	/** what a check sees: the converted editor, the converter (getters) and the rows so far */
	public static final class ExportContext {
		public final Editor editor;
		public final BlockbenchExport export;
		private final List<WarnEntry> warnings;

		ExportContext(Editor editor, BlockbenchExport export, List<WarnEntry> warnings) {
			this.editor = editor;
			this.export = export;
			this.warnings = warnings;
		}

		/** adds a row to the warning dialog */
		public void add(WarnEntry w) {
			if(w != null)warnings.add(w);
		}

		/** the rows added so far (read only) */
		public List<WarnEntry> getWarnings() {
			return Collections.unmodifiableList(warnings);
		}
	}

	/** a written export */
	public static final class ExportResult {
		/** full path on the desktop app, the file name on the web */
		public final String path;
		/** the exact bytes of the .cpmproject */
		public final ArrayBuffer bytes;
		public final Editor editor;

		ExportResult(String path, ArrayBuffer bytes, Editor editor) {
			this.path = path;
			this.bytes = bytes;
			this.editor = editor;
		}
	}

	private static <T> Runnable add(List<T> list, T item) {
		list.add(item);
		Runnable remove = () -> list.remove(item);
		PluginStart.cleanup.add(remove);
		return remove;
	}

	public static Runnable register(ExportCheck check) {
		return add(CHECKS, check);
	}

	public static Runnable beforeFinish(Consumer<Editor> hook) {
		return add(BEFORE_FINISH, hook);
	}

	public static Runnable translateException(Function<Throwable, String> translator) {
		return add(TRANSLATORS, translator);
	}

	public static Runnable afterWrite(Consumer<ExportResult> hook) {
		return add(AFTER_WRITE, hook);
	}

	// ------------------------------------------------------------------ called by the converter

	/** BlockbenchExport: after the built-in model / animation warnings */
	static void runChecks(Editor editor, BlockbenchExport exp, List<WarnEntry> warnings) {
		if(CHECKS.isEmpty())return;
		ExportContext ctx = new ExportContext(editor, exp, warnings);
		for (ExportCheck c : new ArrayList<>(CHECKS)) {
			try {
				c.check(ctx);
			} catch (Throwable e) {
				DomGlobal.console.error("CPM+: an export check failed and was skipped:\n" + ExceptionUtil.getStackTrace(e));
			}
		}
	}

	/** ProjectConvert: after the warning dialog, before ids are assigned and the file is written. Throws on failure. */
	static void runBeforeFinish(Editor editor) {
		for (Consumer<Editor> h : new ArrayList<>(BEFORE_FINISH))h.accept(editor);
	}

	/** a plain user message for this export error, or null (no translator knows it) */
	public static String translate(Object error) {
		if(TRANSLATORS.isEmpty() || error == null)return null;
		Throwable th;
		try {
			th = Java.convertRejectObject(error);
		} catch (Throwable e) {
			return null;
		}
		for (Function<Throwable, String> t : new ArrayList<>(TRANSLATORS)) {
			try {
				String s = t.apply(th);
				if(s != null && !s.isEmpty())return s;
			} catch (Throwable e) {
				DomGlobal.console.error("CPM+: an export error translator failed", e);
			}
		}
		return null;
	}

	static boolean hasAfterWrite() {
		return !AFTER_WRITE.isEmpty();
	}

	/** ProjectConvert: the file was written / downloaded */
	static void fireAfterWrite(String path, ArrayBuffer bytes, Editor editor) {
		ExportResult r = new ExportResult(path, bytes, editor);
		for (Consumer<ExportResult> h : new ArrayList<>(AFTER_WRITE)) {
			try {
				h.accept(r);
			} catch (Throwable e) {
				DomGlobal.console.error("CPM+: an after-export hook failed:\n" + ExceptionUtil.getStackTrace(e));
			}
		}
	}
}
