package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Blockbench.MessageBoxOptions;
import com.tom.cpm.blockbench.proxy.Blockbench.PickDirOptions;
import com.tom.cpm.blockbench.proxy.Blockbench.ToastOptions;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.core.JsObject;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLTextAreaElement;
import elemental2.promise.Promise;
import jsinterop.annotations.JsFunction;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (INFRA1): one way to build dialogs, toasts and messages for every CPM+ tool.
 *
 * <ul>
 * <li>Dialog ids always start with {@value #ID_PREFIX} (added when missing): they never collide with Blockbench's
 * or the official plugin's dialogs, and the QA harness answers them by id.</li>
 * <li>Texts are lang keys (I18n, pt-BR first) or already translated text; a string that is not a key is shown as is.</li>
 * <li>User strings (names, file names, errors) are always shown literally: every place where Blockbench renders
 * HTML (radio options, dialog lines) is HTML-escaped here, places it renders as MARKDOWN (info rows, Blockbench's
 * message box) are markdown- and HTML-escaped (no bold / code / clickable link from a name); places Blockbench
 * renders as plain text (labels, titles, buttons, select options, toasts, quick messages) pass unchanged.</li>
 * <li>Optional / desktop-only Blockbench APIs are feature-detected (typeof): the web app never throws. File paths
 * and folders only exist in the installed app: check canWriteFiles() / canPickDirectory() and call
 * desktopOnly(featureKey) on the website instead of failing silently.</li>
 * </ul>
 */
public final class PlusDialogs {
	public static final String ID_PREFIX = "cpmplus_";

	private PlusDialogs() {
	}

	// ------------------------------------------------------------------ text helpers

	/** lang key -> text (en_us / pt_br fragments); anything that is not a key is returned unchanged */
	public static String tr(String keyOrText) {
		if (keyOrText == null)return "";
		try {
			return I18n.get(keyOrText);
		} catch (Throwable e) {
			return keyOrText;
		}
	}

	/** I18n.format(key, args) (String.format placeholders) */
	public static String trf(String key, Object... args) {
		try {
			return I18n.format(key, args);
		} catch (Throwable e) {
			return key;
		}
	}

	/** HTML-escape (user strings inside HTML) */
	public static String esc(Object s) {
		return Dialog.esc(s);
	}

	/** escaped text with line breaks kept (for places Blockbench renders as HTML) */
	public static String escLines(String s) {
		return esc(s).replace("\n", "<br>");
	}

	/**
	 * Literal text for places Blockbench renders as MARKDOWN (form 'info' rows, Blockbench's message box): every
	 * markdown character is backslash-escaped (so '__a__', '*b*', '`c`', '[t](url)', '# x', '1. x' or a bare
	 * 'https://...' stay plain text, never bold / code / a clickable link), then HTML-escaped, line breaks kept.
	 */
	public static String escMdLines(String s) {
		if (s == null)return "";
		StringBuilder sb = new StringBuilder(s.length() + 16);
		boolean lineStart = true;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\n') {
				sb.append(c);
				lineStart = true;
				continue;
			}
			// leading spaces would start an indented code block
			if (c == ' ' && lineStart) {
				sb.append(' ');
				continue;
			}
			lineStart = false;
			if ("\\`*_{}[]()#+-.!|~:=".indexOf(c) >= 0)sb.append('\\');
			sb.append(c);
		}
		return escLines(sb.toString());
	}

	/** 'cpmplus_' + id unless it already starts with it */
	public static String id(String id) {
		if (id == null || id.isEmpty())return ID_PREFIX + "dialog";
		return id.startsWith(ID_PREFIX) ? id : ID_PREFIX + id;
	}

	// ------------------------------------------------------------------ form rows

	/** condition of one row: evaluated by Blockbench with the current form values */
	@JsFunction
	public interface RowCondition {
		boolean test(JsPropertyMap<Object> formResult);
	}

	@JsFunction
	public interface ButtonsClick {
		void click(int index, Object event);
	}

	/**
	 * One form row: FormRow.info / text / textarea / number / decimal (the plan's 'float') / checkbox / select / radio /
	 * inlineSelect / multiSelect / range / color / buttons / separator; chain description() / when() / readonly().
	 */
	public static final class FormRow {
		final String key;
		final JsPropertyMap<Object> el;
		private final boolean separator;

		private FormRow(String key, String type, String labelKey) {
			this.key = key;
			this.separator = false;
			this.el = JsPropertyMap.of();
			el.set("type", type);
			// labels are rendered as text by Blockbench: no escaping
			if (labelKey != null)el.set("label", tr(labelKey));
		}

		private FormRow(String key) {
			this.key = key;
			this.separator = true;
			this.el = null;
		}

		/** tooltip / '?' text (plain text) */
		public FormRow description(String keyOrText) {
			if (el != null)el.set("description", tr(keyOrText));
			return this;
		}

		/** row shown only while the condition holds for the current form values */
		public FormRow when(Predicate<FormResult> condition) {
			if (el != null) {
				RowCondition c = r -> {
					try {
						return condition.test(new FormResult(r));
					} catch (Throwable e) {
						return true;
					}
				};
				el.set("condition", c);
			}
			return this;
		}

		public FormRow readonly() {
			if (el != null)el.set("readonly", true);
			return this;
		}

		public FormRow fullWidth() {
			if (el != null)el.set("full_width", true);
			return this;
		}

		public FormRow placeholder(String keyOrText) {
			if (el != null)el.set("placeholder", tr(keyOrText));
			return this;
		}

		/** any other Blockbench form option (escaped by the caller when Blockbench renders it as HTML) */
		public FormRow option(String name, Object value) {
			if (el != null)el.set(name, value);
			return this;
		}

		public String getKey() {
			return key;
		}

		// ---------------------------------------------------------- row factories

		/** information text (plain text: Blockbench renders info rows as markdown, so markdown + HTML escaped, line breaks kept) */
		public static FormRow info(String key, String labelKey, String text) {
			FormRow r = new FormRow(key, "info", labelKey);
			r.el.set("text", escMdLines(text != null ? tr(text) : ""));
			return r;
		}

		/**
		 * information text from TRUSTED html (constant markup; user strings inside must be escaped with
		 * escMdLines(): Blockbench renders info rows as markdown)
		 */
		public static FormRow htmlInfo(String key, String labelKey, String trustedHtml) {
			FormRow r = new FormRow(key, "info", labelKey);
			r.el.set("text", trustedHtml != null ? trustedHtml : "");
			return r;
		}

		public static FormRow text(String key, String labelKey, String value) {
			FormRow r = new FormRow(key, "text", labelKey);
			r.el.set("value", value != null ? value : "");
			return r;
		}

		public static FormRow textarea(String key, String labelKey, String value) {
			FormRow r = new FormRow(key, "textarea", labelKey);
			r.el.set("value", value != null ? value : "");
			return r;
		}

		/** whole number (step 1, rounded) */
		public static FormRow number(String key, String labelKey, int value, int min, int max) {
			FormRow r = new FormRow(key, "number", labelKey);
			r.el.set("value", (double) value);
			r.el.set("min", (double) min);
			r.el.set("max", (double) max);
			r.el.set("step", 1.0);
			r.el.set("force_step", true);
			return r;
		}

		/** decimal number (the plan's 'float' row) */
		public static FormRow decimal(String key, String labelKey, double value, double min, double max, double step) {
			FormRow r = new FormRow(key, "number", labelKey);
			r.el.set("value", value);
			r.el.set("min", min);
			r.el.set("max", max);
			r.el.set("step", step);
			return r;
		}

		public static FormRow checkbox(String key, String labelKey, boolean value) {
			FormRow r = new FormRow(key, "checkbox", labelKey);
			r.el.set("value", value);
			return r;
		}

		/** options: value -> label key / text (in order) */
		public static FormRow select(String key, String labelKey, Map<String, String> options, String value) {
			FormRow r = new FormRow(key, "select", labelKey);
			r.el.set("options", options(options, false));
			if (value != null)r.el.set("value", value);
			return r;
		}

		/** options rendered as HTML by Blockbench: labels are escaped */
		public static FormRow radio(String key, String labelKey, Map<String, String> options, String value) {
			FormRow r = new FormRow(key, "radio", labelKey);
			r.el.set("options", options(options, true));
			if (value != null)r.el.set("value", value);
			return r;
		}

		public static FormRow inlineSelect(String key, String labelKey, Map<String, String> options, String value) {
			FormRow r = new FormRow(key, "inline_select", labelKey);
			r.el.set("options", options(options, false));
			if (value != null)r.el.set("value", value);
			return r;
		}

		public static FormRow multiSelect(String key, String labelKey, Map<String, String> options, Collection<String> value) {
			FormRow r = new FormRow(key, "multi_select", labelKey);
			r.el.set("options", options(options, false));
			JsArray<String> v = new JsArray<>();
			if (value != null)for (String s : value)v.push(s);
			r.el.set("value", v);
			return r;
		}

		public static FormRow range(String key, String labelKey, double value, double min, double max, double step) {
			FormRow r = new FormRow(key, "range", labelKey);
			r.el.set("value", value);
			r.el.set("min", min);
			r.el.set("max", max);
			r.el.set("step", step);
			return r;
		}

		/** colour picker, value '#rrggbb' */
		public static FormRow color(String key, String labelKey, String hex) {
			FormRow r = new FormRow(key, "color", labelKey);
			if (hex != null)r.el.set("value", hex);
			return r;
		}

		/** a row of buttons (labels: keys / text); onClick(index) */
		public static FormRow buttons(String key, String labelKey, String[] buttonKeys, IntConsumer onClick) {
			FormRow r = new FormRow(key, "buttons", labelKey);
			JsArray<String> b = new JsArray<>();
			for (String s : buttonKeys)b.push(tr(s));
			r.el.set("buttons", b);
			ButtonsClick c = (i, e) -> {
				try {
					onClick.accept(i);
				} catch (Throwable ex) {
					PopupDialogs.displayError(esc(tr(labelKey)), ex);
				}
			};
			r.el.set("click", c);
			return r;
		}

		private static int sepCount;

		/** horizontal line */
		public static FormRow separator() {
			return new FormRow("_sep" + (sepCount++));
		}
	}

	private static JsPropertyMap<Object> options(Map<String, String> opts, boolean escape) {
		JsPropertyMap<Object> o = JsPropertyMap.of();
		if (opts != null)opts.forEach((k, v) -> o.set(k, escape ? esc(tr(v)) : tr(v)));
		return o;
	}

	/** form values (Blockbench getFormResult) with typed getters */
	public static final class FormResult {
		private final JsPropertyMap<Object> r;
		private boolean keepOpen;

		public FormResult(Object result) {
			this.r = result != null ? Js.uncheckedCast(result) : JsPropertyMap.of();
		}

		public Object raw(String key) {
			return r.get(key);
		}

		public String getString(String key) {
			Object v = r.get(key);
			return v == null ? null : "string".equals(Js.typeof(v)) ? (String) v : String.valueOf(v);
		}

		public double getDouble(String key, double def) {
			Object v = r.get(key);
			if (v == null)return def;
			if ("number".equals(Js.typeof(v)))return Js.asDouble(v);
			try {
				return Double.parseDouble(String.valueOf(v));
			} catch (Exception e) {
				return def;
			}
		}

		public int getInt(String key, int def) {
			double d = getDouble(key, Double.NaN);
			return Double.isNaN(d) ? def : (int) Math.round(d);
		}

		public boolean getBool(String key) {
			return Js.isTruthy(r.get(key));
		}

		public List<String> getList(String key) {
			List<String> out = new ArrayList<>();
			Object v = r.get(key);
			if (v != null && JsArray.isArray(v)) {
				JsArray<Object> a = Js.uncheckedCast(v);
				for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
			}
			return out;
		}

		/** colour as '#rrggbb' (Blockbench returns a tinycolor object) */
		public String getColor(String key) {
			Object v = r.get(key);
			if (v == null)return null;
			if ("string".equals(Js.typeof(v)))return (String) v;
			if (Global.hasFunction(v, "toHexString"))return callToHex(v);
			return null;
		}

		private static native String callToHex(Object v) /*-{
			return v.toHexString();
		}-*/;

		/** call from onConfirm to keep the dialog open (e.g. invalid input; show why) */
		public void keepOpen() {
			keepOpen = true;
		}
	}

	/** options of {@link #form(String, String, List, Consumer, FormOpts)} */
	public static final class FormOpts {
		public Integer width;
		public String confirmKey, cancelKey;
		public Runnable onCancel;
		public Consumer<FormResult> onChange;
		/** trusted html lines shown above the form (escape user strings) */
		public String[] htmlLines;
		/** fixed label column (px): longer labels end in '…' (full text on hover), no sideways scrolling */
		public Integer labelWidth;
	}

	private static native void compactLabels(String id, int w) /*-{
		try {
			var doc = $wnd.document, sid = id + '_cpmplus_layout';
			if (!doc.getElementById(sid)) {
				var s = doc.createElement('style');
				s.id = sid;
				var p = 'dialog#' + id;
				s.textContent = p + ', ' + p + ' .form { --max_label_width: ' + w + 'px !important; }\n'
					+ p + ' .dialog_content { overflow-x: hidden; }\n'
					+ p + ' label.name_space_left { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }\n'
					+ p + ' .dialog_content p { white-space: normal; overflow-wrap: anywhere; }\n';
				doc.head.appendChild(s);
			}
			var dl = doc.getElementById(id);
			if (!dl) return;
			var ls = dl.querySelectorAll('label.name_space_left');
			for (var i = 0; i < ls.length; i++) if (!ls[i].title) ls[i].title = ls[i].textContent;
		} catch (e) {}
	}-*/;

	public static Dialog form(String id, String titleKey, List<FormRow> rows, Consumer<FormResult> onConfirm) {
		return form(id, titleKey, rows, onConfirm, null);
	}

	/**
	 * Shows a form dialog. onConfirm(result) runs on OK (errors are shown in the CPM error dialog); call
	 * result.keepOpen() to keep the dialog open.
	 */
	public static Dialog form(String id, String titleKey, List<FormRow> rows, Consumer<FormResult> onConfirm, FormOpts opts) {
		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = id(id);
		d.title = tr(titleKey);
		JsPropertyMap<Object> form = JsPropertyMap.of();
		if (rows != null) {
			for (FormRow r : rows) {
				if (r.separator)form.set(r.key, "_");
				else form.set(r.key, r.el);
			}
		}
		d.form = Js.uncheckedCast(form);
		if (opts != null && opts.htmlLines != null)d.setHtml(opts.htmlLines);
		if (opts != null && opts.width != null)d.width = opts.width;
		if (opts != null && (opts.confirmKey != null || opts.cancelKey != null)) {
			d.buttons = new String[] {tr(opts.confirmKey != null ? opts.confirmKey : "cpmplus.w0binfra.btn.ok"), tr(opts.cancelKey != null ? opts.cancelKey : "cpmplus.w0binfra.btn.cancel")};
			d.confirmIndex = 0;
			d.cancelIndex = 1;
		}
		d.onConfirm = res -> {
			FormResult fr = new FormResult(res);
			try {
				if (onConfirm != null)onConfirm.accept(fr);
			} catch (Throwable e) {
				PopupDialogs.displayError(esc(d.title), e);
			}
			return !fr.keepOpen;
		};
		if (opts != null && opts.onCancel != null) {
			Runnable c = opts.onCancel;
			d.onCancel = () -> {
				try {
					c.run();
				} catch (Throwable e) {
					PopupDialogs.displayError(esc(d.title), e);
				}
				return true;
			};
		}
		if (opts != null && opts.onChange != null) {
			Consumer<FormResult> ch = opts.onChange;
			d.onFormChange = res -> {
				try {
					ch.accept(new FormResult(res));
				} catch (Throwable e) {
					DomGlobal.console.error(e);
				}
			};
		}
		Dialog dlg = new Dialog(d);
		dlg.show();
		// Blockbench marks the default radio option with 'selected' (not 'checked'): set the values explicitly
		if (rows != null) {
			JsPropertyMap<Object> init = JsPropertyMap.of();
			boolean any = false;
			for (FormRow r : rows) {
				if (!r.separator && "radio".equals(r.el.get("type")) && r.el.get("value") != null) {
					init.set(r.key, r.el.get("value"));
					any = true;
				}
			}
			if (any) {
				try {
					dlg.setFormValues(init, true);
				} catch (Throwable e) {
				}
			}
		}
		if (opts != null && opts.labelWidth != null)compactLabels(d.id, opts.labelWidth);
		return dlg;
	}

	// ------------------------------------------------------------------ simple dialogs

	private static Dialog.DialogProperties base(String id, String titleKey, String html) {
		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = id(id);
		d.title = tr(titleKey);
		d.setHtml(html);
		return d;
	}

	/** information dialog; lines are plain text (escaped) */
	public static Dialog info(String id, String titleKey, List<String> lines) {
		StringBuilder sb = new StringBuilder();
		if (lines != null)for (String l : lines)sb.append("<p>").append(escLines(tr(l))).append("</p>");
		Dialog.DialogProperties d = base(id, titleKey, sb.toString());
		d.singleButton = true;
		Dialog dlg = new Dialog(d);
		dlg.show();
		return dlg;
	}

	/** information dialog from TRUSTED html (escape user strings with esc()) */
	public static Dialog infoHtml(String id, String titleKey, String trustedHtml) {
		Dialog.DialogProperties d = base(id, titleKey, trustedHtml);
		d.singleButton = true;
		Dialog dlg = new Dialog(d);
		dlg.show();
		return dlg;
	}

	/** yes/no question; msg = key or text (plain); onYes runs on Confirmar */
	public static Dialog confirm(String msgKey, Runnable onYes) {
		return confirm("cpmplus_confirm", "cpmplus.w0binfra.dlg.confirm.title", msgKey, onYes, null);
	}

	public static Dialog confirm(String id, String titleKey, String msgKey, Runnable onYes, Runnable onNo) {
		Dialog.DialogProperties d = base(id, titleKey, "<p>" + escLines(tr(msgKey)) + "</p>");
		d.buttons = new String[] {tr("cpmplus.w0binfra.btn.confirm"), tr("cpmplus.w0binfra.btn.cancel")};
		d.confirmIndex = 0;
		d.cancelIndex = 1;
		d.onConfirm = r -> {
			safeRun(onYes, d.title);
			return true;
		};
		d.onCancel = () -> {
			safeRun(onNo, d.title);
			return true;
		};
		Dialog dlg = new Dialog(d);
		dlg.show();
		return dlg;
	}

	/** warning with 'Continuar mesmo assim' / 'Cancelar'; msg = translated text (plain) */
	public static Dialog warnContinue(String msg, Runnable onContinue) {
		Dialog.DialogProperties d = base("cpmplus_warn_continue", "cpmplus.w0binfra.dlg.warn.title",
				"<p style='color: var(--color-warning, #e6a23c);'>" + escLines(tr(msg)) + "</p>");
		d.buttons = new String[] {tr("cpmplus.w0binfra.btn.continueAnyway"), tr("cpmplus.w0binfra.btn.cancel")};
		d.confirmIndex = 0;
		d.cancelIndex = 1;
		d.onConfirm = r -> {
			safeRun(onContinue, d.title);
			return true;
		};
		Dialog dlg = new Dialog(d);
		dlg.show();
		return dlg;
	}

	/** the action can't be done: red message, only OK (explain why and what to do instead) */
	public static Dialog block(String msg) {
		Dialog.DialogProperties d = base("cpmplus_block", "cpmplus.w0binfra.dlg.block.title",
				"<p style='color: var(--color-close, #f44336);'>" + escLines(tr(msg)) + "</p>");
		d.singleButton = true;
		Dialog dlg = new Dialog(d);
		dlg.show();
		return dlg;
	}

	private static void safeRun(Runnable r, String title) {
		if (r == null)return;
		try {
			r.run();
		} catch (Throwable e) {
			PopupDialogs.displayError(esc(title), e);
		}
	}

	// ------------------------------------------------------------------ toasts / messages (feature-detected)

	/** 'Feito: ... Ctrl+Z desfaz.' toast after an edit that is ONE Blockbench undo entry */
	public static void done(String what) {
		toast(trf("cpmplus.w0binfra.msg.done", what), "check", 6000, null);
	}

	/** toast at the bottom (plain text); onClick (optional) runs once and closes the toast */
	public static void toast(String text, String icon, int expireMs, Runnable onClick) {
		try {
			if (Blockbench.has("showToastNotification")) {
				ToastOptions o = new ToastOptions();
				o.text = text;
				o.icon = icon != null ? icon : "info";
				if (expireMs > 0)o.expire = expireMs;
				if (onClick != null) {
					boolean[] ran = new boolean[1];
					o.click = e -> {
						if (!ran[0]) {
							ran[0] = true;
							safeRun(onClick, text);
						}
						return true;
					};
				}
				Blockbench.showToastNotification(o);
				return;
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: toast failed", e);
		}
		quick(text, Math.max(expireMs, 2000));
	}

	/** short message in the middle of the screen (plain text) */
	public static void quick(String text, int ms) {
		try {
			if (Blockbench.has("showQuickMessage")) {
				Blockbench.showQuickMessage(text, ms);
				return;
			}
		} catch (Throwable e) {
		}
		DomGlobal.console.log("CPM+: " + text);
	}

	/** status bar text (null restores Blockbench's default) */
	public static void status(String text) {
		try {
			if (!Blockbench.has("setStatusBarText"))return;
			if (text == null)Blockbench.setStatusBarText();
			else Blockbench.setStatusBarText(text);
		} catch (Throwable e) {
		}
	}

	/** progress bar 0..1 (0 hides it) */
	public static void progress(double p) {
		try {
			if (Blockbench.has("setProgress"))Blockbench.setProgress(p);
		} catch (Throwable e) {
		}
	}

	@JsFunction
	public interface MessageBoxResult {
		void onButton(int button);
	}

	/**
	 * Message with several buttons (plain text, escaped); cb(index of the pressed button, or -1 when closed
	 * with X / Esc). Own dialog 'cpmplus_message' (Blockbench's showMessageBox uses a fixed id).
	 */
	public static Dialog messageBox(String titleKey, String message, String[] buttonKeys, MessageBoxResult cb) {
		Dialog.DialogProperties d = base("cpmplus_message", titleKey, "<p>" + escLines(tr(message)) + "</p>");
		String[] keys = buttonKeys != null && buttonKeys.length > 0 ? buttonKeys : new String[] {"cpmplus.w0binfra.btn.ok"};
		String[] b = new String[keys.length];
		for (int i = 0; i < b.length; i++)b[i] = tr(keys[i]);
		d.buttons = b;
		d.confirmIndex = 0;
		d.cancelIndex = b.length - 1;
		boolean[] done = new boolean[1];
		d.onButton = i -> {
			if (!done[0]) {
				done[0] = true;
				if (cb != null) {
					try {
						cb.onButton(i);
					} catch (Throwable e) {
						PopupDialogs.displayError(esc(d.title), e);
					}
				}
			}
			return true;
		};
		Dialog dlg = new Dialog(d);
		dlg.show();
		return dlg;
	}

	/** Blockbench's own message box when available (id 'message_box'); message shown as literal text (markdown + HTML escaped) */
	public static void bbMessageBox(String titleKey, String message, String[] buttonKeys, MessageBoxResult cb) {
		if (!Blockbench.has("showMessageBox")) {
			messageBox(titleKey, message, buttonKeys, cb);
			return;
		}
		MessageBoxOptions o = new MessageBoxOptions();
		o.title = tr(titleKey);
		o.message = escMdLines(tr(message));
		if (buttonKeys != null) {
			String[] b = new String[buttonKeys.length];
			for (int i = 0; i < b.length; i++)b[i] = tr(buttonKeys[i]);
			o.buttons = b;
		}
		Blockbench.showMessageBox(o, i -> {
			if (cb != null)cb.onButton(i);
		});
	}

	// ------------------------------------------------------------------ clipboard

	/**
	 * Copies text: navigator.clipboard first ('Copiado' toast only when it really worked), otherwise a dialog with
	 * the text selected in a read-only box (the user presses Ctrl+C). Never throws.
	 * @return resolves true when copied automatically, false when the manual dialog was shown
	 */
	public static Promise<Boolean> copyText(String text) {
		String t = text != null ? text : "";
		return new Promise<>((res, rej) -> {
			try {
				Object clip = Js.asPropertyMap(DomGlobal.navigator).get("clipboard");
				if (clip != null && Global.hasFunction(clip, "writeText")) {
					Promise<Object> p = writeClipboard(clip, t);
					p.then(v -> {
						quick(tr("cpmplus.w0binfra.msg.copied"), 1500);
						res.onInvoke(true);
						return null;
					}, e -> {
						showCopyDialog(t);
						res.onInvoke(false);
						return null;
					});
					return;
				}
			} catch (Throwable e) {
			}
			showCopyDialog(t);
			res.onInvoke(false);
		});
	}

	private static native Promise<Object> writeClipboard(Object clip, String text) /*-{
		try {
			var p = clip.writeText(text);
			if (p && typeof p.then === 'function') return p;
			return $wnd.Promise.resolve(p);
		} catch (e) {
			return $wnd.Promise.reject(e);
		}
	}-*/;

	private static native boolean execCopy() /*-{
		try {
			return !!$wnd.document.execCommand('copy');
		} catch (e) {
			return false;
		}
	}-*/;

	private static void showCopyDialog(String text) {
		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = "cpmplus_copy_text";
		d.title = tr("cpmplus.w0binfra.dlg.copy.title");
		d.setHtml("<p>" + esc(tr("cpmplus.w0binfra.dlg.copy.hint")) + "</p>",
				"<textarea id='cpmplus_copy_text_area' readonly style='width: 100%; min-height: 160px; font-family: monospace; resize: vertical;'></textarea>");
		d.buttons = new String[] {tr("cpmplus.w0binfra.btn.copy"), tr("cpmplus.w0binfra.btn.close")};
		d.confirmIndex = 0;
		d.cancelIndex = 1;
		d.onConfirm = r -> {
			// second chance: the user clicked, so execCommand('copy') may be allowed now
			HTMLTextAreaElement ta = (HTMLTextAreaElement) DomGlobal.document.getElementById("cpmplus_copy_text_area");
			boolean ok = false;
			if (ta != null) {
				ta.focus();
				ta.select();
				try {
					ok = execCopy();
				} catch (Throwable e) {
				}
			}
			if (ok)quick(tr("cpmplus.w0binfra.msg.copied"), 1500);
			return ok;// keep the dialog open when copying still failed (text stays selected)
		};
		d.onOpen = () -> {
			HTMLTextAreaElement ta = (HTMLTextAreaElement) DomGlobal.document.getElementById("cpmplus_copy_text_area");
			if (ta != null) {
				ta.value = text;// value, never innerHTML
				ta.focus();
				ta.select();
			}
		};
		new Dialog(d).show();
	}

	// ------------------------------------------------------------------ files (feature-detected, test-mode safe)

	/**
	 * true in the installed Blockbench (desktop app), false on the website (web app: no file paths, no folders).
	 * Test mode: the web app counts as desktop while the scenario sets {@code window.__cpmplus_test_desktop}
	 * (PlusTestHooks.simulateDesktop: writes go to the harness' spies, inside h.fsRoot only).
	 */
	public static boolean isDesktop() {
		return Global.isApp() || PlusTestHooks.simulateDesktop();
	}

	/** true when {@link #pickDirectory} can show a folder picker here (false on the web app) */
	public static boolean canPickDirectory() {
		if (!isDesktop())return false;
		return PlusTestHooks.fsRoot() != null || Blockbench.has("pickDirectory");
	}

	/** true when {@link #writeFile} can write a file by path here (false on the web app: use {@link #export}) */
	public static boolean canWriteFiles() {
		return isDesktop() && Blockbench.has("writeFile");
	}

	/**
	 * Tells the user a feature needs the installed Blockbench (red 'Não dá para fazer isso' dialog in pt-BR):
	 * call it when {@link #canWriteFiles()} / {@link #canPickDirectory()} is false instead of failing silently.
	 * @param featureKey lang key (or text) of the feature name
	 */
	public static Dialog desktopOnly(String featureKey) {
		return block(trf("cpmplus.w0binfra.msg.desktopOnly", tr(featureKey)));
	}

	/**
	 * Desktop folder picker. null when cancelled, and also where no folder can be picked (web app, API missing):
	 * check {@link #canPickDirectory()} first and call {@link #desktopOnly} when it is false, so 'not possible
	 * here' is never mistaken for 'cancelled'. In test mode the harness' scratch folder is returned instead (no
	 * real folder is ever touched).
	 */
	public static String pickDirectory(String titleKey, String startPath) {
		if (!canPickDirectory())return null;
		String fsRoot = PlusTestHooks.fsRoot();
		if (fsRoot != null)return fsRoot;
		try {
			PickDirOptions o = new PickDirOptions();
			o.title = tr(titleKey);
			if (startPath != null)o.startpath = startPath;
			o.resource_id = "cpmplus";
			Object r = Blockbench.pickDirectory(o);
			return r != null && "string".equals(Js.typeof(r)) ? (String) r : null;
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: pickDirectory failed", e);
			return null;
		}
	}

	/**
	 * Blockbench.writeFile (desktop app only). In test mode only paths inside the harness' scratch folder are
	 * allowed (anything else throws).
	 * @return false (nothing written, cb never called) on the web app - Blockbench web has a writeFile that does
	 *         nothing - or when the API is missing: check {@link #canWriteFiles()} first and use
	 *         {@link #desktopOnly} / {@link #export} instead, never report success
	 */
	public static boolean writeFile(String path, Blockbench.WriteProperties props, Blockbench.CallbackExport cb) {
		checkTestPath(path);
		if (!canWriteFiles())return false;
		Blockbench.writeFile(path, props, cb);
		return true;
	}

	/** test mode: refuses any path outside PlusTestHooks.fsRoot() (path-component aware) */
	public static void checkTestPath(String path) {
		String root = PlusTestHooks.fsRoot();
		if (root == null)return;
		if (!isInside(root, path))throw new IllegalStateException("CPM+ test mode: refusing to touch '" + path + "' outside " + root);
	}

	static boolean isInside(String root, String path) {
		if (path == null)return false;
		String r = root.replace('\\', '/');
		String p = path.replace('\\', '/');
		while (r.endsWith("/"))r = r.substring(0, r.length() - 1);
		if (p.contains("/../") || p.endsWith("/.."))return false;
		String rl = r.toLowerCase(), pl = p.toLowerCase();
		return pl.equals(rl) || pl.startsWith(rl + "/");
	}

	/** Blockbench.export (download / save dialog) when available */
	public static boolean export(Blockbench.ExportProperties props, Blockbench.CallbackExport cb) {
		if (!Blockbench.has("export"))return false;
		Blockbench.export(props, cb);
		return true;
	}

	/** Blockbench.import (file picker) when available */
	public static boolean importFiles(Blockbench.ImportProperties props, Blockbench.CallbackImport cb) {
		if (!Blockbench.has("import"))return false;
		Blockbench.import_(props, cb);
		return true;
	}

	/** Blockbench.read (desktop: read files by path) when available; test mode: inside fsRoot only */
	public static boolean read(String[] paths, Blockbench.ReadProperties props, Blockbench.CallbackImport cb) {
		if (paths != null)for (String p : paths)checkTestPath(p);
		if (!Global.isApp() || !Blockbench.has("read"))return false;
		Blockbench.read(paths, props, cb);
		return true;
	}

	/** ordered option map helper: options("a", "key.a", "b", "key.b") */
	public static Map<String, String> options(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2)m.put(kv[i], kv[i + 1]);
		return m;
	}

	/** JsObject view of a Java list of rows (for Blockbench APIs that take a form object) */
	public static JsObject formObject(List<FormRow> rows) {
		JsPropertyMap<Object> form = JsPropertyMap.of();
		for (FormRow r : rows)form.set(r.key, r.separator ? "_" : r.el);
		return Js.uncheckedCast(form);
	}
}
