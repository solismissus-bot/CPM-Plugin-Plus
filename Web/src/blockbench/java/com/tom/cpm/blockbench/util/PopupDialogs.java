package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.convert.WarnFixes.Choice;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.web.client.WebMC;
import com.tom.cpm.web.client.util.Clipboard;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.ExceptionUtil;
import com.tom.ugwt.client.GlobalFunc;

import elemental2.core.JsError;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import elemental2.promise.Promise.PromiseExecutorCallbackFn.ResolveCallbackFn;
import jsinterop.annotations.JsFunction;

public class PopupDialogs {
	public static Dialog errorDialog;
	public static Dialog infoDialog;
	public static Dialog warnDialog;
	public static GlobalFunc copyErrorFunc;
	/**
	 * CPM+ (B5): texts of the last error dialogs, by id. The 'copy' button passes only the id (never the text: btoa
	 * threw on any non-Latin-1 character and user text inside an onclick attribute is unsafe).
	 */
	private static final Map<Integer, String> errorTexts = new LinkedHashMap<>();
	private static final int ERROR_TEXTS_KEPT = 20;
	private static int nextErrorId;

	static {
		Dialog.DialogProperties dctr = new Dialog.DialogProperties();
		dctr.id = "cpm_error";
		dctr.title = I18n.get("bb-label.error.title");
		dctr.lines = new String[] {"?"};
		dctr.singleButton = true;
		errorDialog = new Dialog(dctr);

		dctr = new Dialog.DialogProperties();
		dctr.id = "cpm_warn";
		dctr.title = I18n.get("label.cpm.warning");
		dctr.lines = new String[] {"?"};
		warnDialog = new Dialog(dctr);

		dctr = new Dialog.DialogProperties();
		dctr.id = "cpm_info";
		dctr.title = I18n.get("label.cpm.info");
		dctr.lines = new String[] {"?"};
		infoDialog = new Dialog(dctr);

		copyErrorFunc = GlobalFunc.pushGlobalFunc(CopyErrorCallback.class, PopupDialogs::copyError);
		PluginStart.cleanup.add(copyErrorFunc);
	}

	/** CPM+ (B5): writes the exact text of error dialog id to the clipboard */
	private static void copyError(int id) {
		String t = errorTexts.get(id);
		if(t != null)Clipboard.writeText(t);
	}

	/** CPM+ (B5): keeps text for the copy button (the last 20 errors) and returns its id */
	private static int storeErrorText(String text) {
		int id = nextErrorId++;
		errorTexts.put(id, text);
		while(errorTexts.size() > ERROR_TEXTS_KEPT)errorTexts.remove(errorTexts.keySet().iterator().next());
		return id;
	}

	public static void displayError(String msg, Object err) {
		if(err instanceof PopupDialogs.CancelException)return;
		if(err instanceof UserException) {
			UserException ex = (UserException) err;
			errorDialog.setLines("<p>" + msg + "<br>" + Dialog.esc(ex.getMessage()).replace("\n", "<br>") + "</p>");// CPM+ : escaped
			errorDialog.show();
			return;
		}
		String errorMsg;
		try {
			if(err instanceof Throwable) {
				String st = ExceptionUtil.getStackTrace((Throwable) err);
				errorMsg = msg + "\n" + st;
				DomGlobal.console.error(st);
			} else if(err instanceof JsError) {
				String st = ExceptionUtil.getStackTrace((JsError) err);
				errorMsg = msg + "\n" + st;
				DomGlobal.console.error(st);
			} else {
				DomGlobal.console.log(err);
				errorMsg = msg + "\n" + String.valueOf(err);// CPM+ (B5): null error -> 'null'
			}
		} catch (Throwable e) {
			// CPM+ (B5): the error text itself failed (odd JS value): the dialog still opens
			errorMsg = msg + "\n" + safeString(err);
		}
		errorMsg += "\nPlatform: " + WebMC.platform;
		int id = storeErrorText(errorMsg);
		// CPM+ : error text is escaped (it can contain user names / '<'); the copy button passes only the id (B5)
		errorDialog.setLines("<p>" + msg + "<br>" + Dialog.esc(err == null ? "" : safeString(err)) + "<br>" + I18n.get("bb-label.pleaseReport") + "<br>" + "Platform: " + Dialog.esc(WebMC.platform) + "<br>" + "<button onclick='" + copyErrorFunc + "(" + id + ")'>" + I18n.get("bb-button.copyErrorMsg") + "</button></p>");
		errorDialog.show();
	}

	/**
	 * CPM+ (W0C): an error the user can act on, in plain words (e.g. an export error translated by
	 * ExportHooks.translateException): msg (trusted HTML) and the text in red, escaped. No stack trace.
	 */
	public static void displayUserError(String msg, String text) {
		errorDialog.setLines("<p>" + msg + "<br><span style='color: var(--color-error, #e14f4f);'>" + Dialog.esc(text).replace("\n", "<br>") + "</span></p>");
		errorDialog.show();
	}

	private static String safeString(Object err) {
		try {
			return String.valueOf(err);
		} catch (Throwable e) {
			return "?";
		}
	}

	public static void displayMessage(String msg) {
		infoDialog.setLines("<p>" + msg + "</p>");
		infoDialog.show();
	}

	/** CPM+ : info dialog for plain (non-HTML) text, newlines become line breaks */
	public static void displayText(String text) {
		displayMessage(Dialog.esc(text).replace("\n", "<br>"));
	}

	public static class CancelException extends Exception {
	}

	public static class UserException extends Exception {
		public UserException(String message) {
			super(message);
		}
	}

	@JsFunction
	public interface QuickFixCallback {
		/** CPM+ (W0C): fix choice of row id; choice -1 opens the chooser of a row with several fixes */
		void run(int id, int choice);
	}

	@JsFunction
	public interface CopyErrorCallback {
		/** CPM+ (B5): id of the stored error text */
		void run(int id);
	}

	public interface WarnCallback {
		Promise<Void> updateState(Function<List<WarnEntry>, Promise<Void>> openWarn);
	}

	private static class WarnDialogHandler {
		private WarnCallback cb;
		private List<WarnEntry> ent;
		private boolean quickFixed;
		private GlobalFunc quickFixFunc;
		private ResolveCallbackFn<Void> res;

		public WarnDialogHandler(List<WarnEntry> ent, WarnCallback cb) {
			this.ent = ent;
			this.cb = cb;
		}

		public Promise<Void> display() {
			return new Promise<Void>((res, rej) -> {
				this.res = res;
				quickFixFunc = GlobalFunc.pushGlobalFunc(QuickFixCallback.class, this::applyFix);
				warnDialog.onCancel = () -> {
					rej.onInvoke(new CancelException());
					return true;
				};
				warnDialog.onConfirm = dr -> {
					if(quickFixed) {
						res.onInvoke(cb.updateState(w -> Promise.resolve((Void) null)));
					} else {
						res.onInvoke((Void) null);
					}
					return true;
				};
				fillWarnDialog(ent, quickFixFunc);
			}).finally_(() -> {
				if(quickFixFunc != null)quickFixFunc.run();
				TextureDecodeCache.clear();// CPM+ (load A7): the dialog is closed, its re-runs are over
			});
		}

		/** CPM+ (W0C): one fix of a row (built-in or registered in WarnFixes); choice -1 = let the user choose */
		private void applyFix(int id, int choice) {
			if(id < 0 || id >= ent.size())return;
			WarnEntry w = ent.get(id);
			List<Choice> choices = WarnFixes.choicesFor(w);
			if(choices.isEmpty())return;
			if(choice < 0) {
				if(choices.size() == 1)run(w, choices.get(0));
				else openChooser(w, choices, c -> run(w, c));
				return;
			}
			if(choice < choices.size())run(w, choices.get(choice));
		}

		private void run(WarnEntry w, Choice c) {
			w.runFix(c.task).then(f -> {
				if(f != null && f) {
					cb.updateState(nw -> {
						this.ent = nw;
						fillWarnDialog(nw, quickFixFunc);
						return Promise.reject(null);
					}).then(__ -> {
						warnDialog.hide();
						res.onInvoke((Void) null);
						return null;
					}).catch_(e -> null);
				}
				return null;
			}).catch_(e -> {
				reportFixFailure(c, e);
				return null;
			});
			quickFixed = true;
		}
	}

	/**
	 * CPM+ (W0C): a fix that failed is never silent (Convention 11). Silent only for: CancelException (the user
	 * cancelled), and a built-in fix that gave up with a plain value after showing its own message (e.g. 'merge the
	 * texture sheets' rejects with ""). Everything else - an exception thrown by any fix, any rejection of a fix
	 * registered in WarnFixes - shows 'the fix was not applied' with the details.
	 */
	static void reportFixFailure(Choice c, Object e) {
		if(e instanceof CancelException)return;
		boolean error = e instanceof Throwable || e instanceof JsError;
		if(c.builtIn && !error) {
			DomGlobal.console.log("CPM+: fix not applied", e);
			return;
		}
		DomGlobal.console.error("CPM+: fix '" + c.label + "' failed", e);
		// (lang text + a lang label: trusted; not escaped so 'copy' gives plain text)
		displayError(I18n.format("cpmplus.w0cexportcore.fix.failed", c.label), e);
	}

	/**
	 * CPM+ (W0C): 'Corrigir...' of a row with several fixes: one button per fix (the row's own fix first), then
	 * Cancelar. The message of the row is shown on top.
	 */
	private static void openChooser(WarnEntry w, List<Choice> choices, java.util.function.Consumer<Choice> onPick) {
		Dialog.DialogProperties pr = new Dialog.DialogProperties();
		pr.id = "cpmplus_fix_chooser";
		pr.title = I18n.get("cpmplus.w0cexportcore.dlg.fixChooser.title");
		String[] buttons = new String[choices.size() + 1];
		for (int i = 0; i < choices.size(); i++)buttons[i] = choices.get(i).label;
		buttons[choices.size()] = I18n.get("cpmplus.w0cexportcore.btn.cancel");
		pr.buttons = buttons;
		pr.confirmIndex = 0;
		pr.cancelIndex = choices.size();
		pr.singleButton = false;
		// what each fix does, one line per option (the built-in fix: the row's fixPreview / tooltip)
		StringBuilder opts = new StringBuilder("<ul class='cpmplus_fix_chooser_list'>");
		for (Choice c : choices) {
			opts.append("<li><b>").append(Dialog.esc(c.label)).append("</b>");
			if(c.previewHtml != null && !c.previewHtml.isEmpty())opts.append(": ").append(c.previewHtml);
			opts.append("</li>");
		}
		opts.append("</ul>");
		pr.setHtml("<div><p>" + w.getMessage() + "</p><p>" + Dialog.esc(I18n.get("cpmplus.w0cexportcore.dlg.fixChooser.text")) + "</p>" + opts + "</div>");
		boolean[] done = new boolean[1];
		pr.onButton = i -> {
			if(done[0])return true;
			done[0] = true;
			if(i >= 0 && i < choices.size())onPick.accept(choices.get(i));
			return true;
		};
		new Dialog(pr).show();
	}

	public static Promise<Void> runTaskWithWarning(WarnCallback cb) {
		return cb.updateState(w -> displayWarning(cb, w)).finally_(TextureDecodeCache::clear);// CPM+ (load A7)
	}

	public static Promise<Void> runTaskWithWarning(WarnCallback cb, Runnable onWarn) {
		return cb.updateState(w -> {
			onWarn.run();
			return displayWarning(cb, w);
		}).finally_(TextureDecodeCache::clear);// CPM+ (load A7): export / validation finished
	}

	public static Promise<Void> displayWarning(WarnCallback cb, List<WarnEntry> ent) {
		return new WarnDialogHandler(ent, cb).display();
	}

	private static void fillWarnDialog(List<WarnEntry> ent, GlobalFunc quickFix) {
		StringBuilder sb = new StringBuilder();
		sb.append("<div style='display: flex;flex-direction: column;'>");
		List<WarnEntry> rows = new ArrayList<>(ent);
		rows.stream().filter(w -> !w.isFixed()).sorted().forEach(w -> {
			String msg = w.getMessage();
			boolean breaks = w.getSeverity() == Severity.BREAKS;
			sb.append("<div class='tool widget cpm_warn_msg" + (breaks ? " cpmplus_warn_breaks" : "") + "' style='text-align: left; display: flex; flex: 1; justify-content: space-between;" +
					(breaks ? " border-left: 3px solid var(--color-error, #e14f4f); padding-left: 6px;" : "") + "'>");
			if(w.getTooltip() != null) {
				sb.append("<div class='tooltip' style='margin-left: 0px;'>" + w.getTooltip() + "</div>");
			}
			sb.append("<div style='align-self: center;'>");
			if(breaks)sb.append("<b style='color: var(--color-error, #e14f4f);'>" + Dialog.esc(I18n.get("cpmplus.w0cexportcore.sev.breaks")) + "</b> ");
			sb.append(msg);
			if(w.getConsequenceKey() != null)
				sb.append("<div style='opacity: 0.8; font-size: 0.9em;'>" + Dialog.esc(I18n.get(w.getConsequenceKey())) + "</div>");
			sb.append("</div>");
			// CPM+ (W0C): the row's own fix + the fixes other packages registered for its key (WarnFixes)
			List<Choice> choices = WarnFixes.choicesFor(w);
			int idx = ent.indexOf(w);
			if(choices.size() == 1) {
				Choice only = choices.get(0);
				String hint = only.builtIn ? (w.getFixPreview() != null ? " title='" + Dialog.esc(w.getFixPreview()) + "'" : "") :
					only.previewHtml != null ? " title='" + only.previewHtml + "'" : "";// registered: already escaped text
				sb.append("<div><button" + hint + " onclick='" + quickFix + "(" + idx + ",0)'>" + Dialog.esc(choices.get(0).label) + "</button></div>");
			} else if(choices.size() > 1) {
				sb.append("<div><button class='cpmplus_fix_chooser_btn' onclick='" + quickFix + "(" + idx + ",-1)'>" + Dialog.esc(I18n.get("cpmplus.w0cexportcore.btn.fix")) + "</button></div>");
			}
			sb.append("</div>");
		});
		sb.append("</div>");
		warnDialog.setLines(sb.toString());
		warnDialog.show();
	}
}
