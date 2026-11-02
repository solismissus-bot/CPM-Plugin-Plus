package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.tom.cpm.web.client.util.I18n;

import elemental2.promise.Promise;

/**
 * One row of the CPM warning dialog (export / import / 'Conferir modelo').
 * <p>
 * CPM+ (W0C-EXPORTCORE) structured fields, so later packages can attach fixes ({@link WarnFixes}) and render rows
 * consistently:
 * <ul>
 * <li>{@link Severity}: BREAKS (it will not work in game / CPM refuses it), DIFFERS (works, but not like the
 * Blockbench preview), INFO. The old constructors give DIFFERS, or INFO after {@link #setInfo()}.</li>
 * <li>key (stable id of the kind of warning, e.g. 'unknownTexture'; fixes are registered per key) + args.</li>
 * <li>consequenceKey: lang key of one line that says what happens in game.</li>
 * <li>uuids + names of the affected nodes.</li>
 * <li>{@link FixKind} and fixPreview (what the built-in fix will do, shown on its button).</li>
 * </ul>
 * The message (and tooltip) is trusted HTML: producers escape user strings (names) themselves.
 */
public class WarnEntry implements Comparable<WarnEntry> {
	/** how bad it is for the model in game */
	public static enum Severity {
		/** it will not work in game (CPM refuses the model / mixes parts up) */
		BREAKS,
		/** works, but looks / behaves differently from the Blockbench preview */
		DIFFERS,
		/** information only */
		INFO
	}

	/** what the built-in fix does */
	public static enum FixKind {
		/** no fix */
		NONE,
		/** changes the project in one known way (one undo entry) */
		DETERMINISTIC,
		/** opens a dialog / asks first */
		ASKS,
		/** only shows something (a list, a selection), changes nothing */
		VIEW_ONLY
	}

	private int priority;
	private String message, tooltip;
	private FutureTask autoFix;
	private boolean fixed;
	private Severity severity = Severity.DIFFERS;
	private String key;
	private Object[] args = new Object[0];
	private String consequenceKey;
	private List<String> uuids = new ArrayList<>();
	private List<String> names = new ArrayList<>();
	private FixKind fixKind = FixKind.NONE;
	private String fixPreview;

	public static interface FutureTask {
		Promise<Boolean> runTask();
	}

	public WarnEntry(String message) {
		this.message = message;
	}

	public WarnEntry(String message, FutureTask autoFix) {
		this.message = message;
		this.autoFix = autoFix;
		if(autoFix != null)this.fixKind = FixKind.ASKS;
	}

	public Promise<Boolean> runQuickFix() {
		return runFix(autoFix);
	}

	/**
	 * runs a fix of this row (the built-in one or one from {@link WarnFixes}); the row counts as fixed when it resolves
	 * true. A fix that throws right away (or returns no promise) gives a rejected promise, never an uncaught error.
	 */
	public Promise<Boolean> runFix(FutureTask task) {
		if(task == null)return Promise.resolve(false);
		Promise<Boolean> p;
		try {
			p = task.runTask();
		} catch (Throwable e) {
			return Promise.reject(e);
		}
		if(p == null)return Promise.reject(new IllegalStateException("CPM+: the fix returned no result"));
		return p.then(s -> {
			fixed = s != null && s;
			return Promise.resolve(s);
		});
	}

	public WarnEntry setTooltip(String tooltip) {
		this.tooltip = tooltip;
		return this;
	}

	public WarnEntry setPriority(int priority) {
		this.priority = priority;
		return this;
	}

	/** severity INFO */
	public WarnEntry setInfo() {
		this.severity = Severity.INFO;
		return this;
	}

	public WarnEntry setSeverity(Severity severity) {
		this.severity = severity != null ? severity : Severity.DIFFERS;
		return this;
	}

	/** stable key of this kind of warning (fixes registered in {@link WarnFixes} under it apply) + its arguments */
	public WarnEntry setKey(String key, Object... args) {
		this.key = key;
		this.args = args != null ? args : new Object[0];
		return this;
	}

	/** lang key of one line that says what happens in game (shown under the message) */
	public WarnEntry setConsequence(String consequenceKey) {
		this.consequenceKey = consequenceKey;
		return this;
	}

	/** the affected nodes (uuid + display name, same order) */
	public WarnEntry setNodes(List<String> uuids, List<String> names) {
		this.uuids = uuids != null ? new ArrayList<>(uuids) : new ArrayList<>();
		this.names = names != null ? new ArrayList<>(names) : new ArrayList<>();
		return this;
	}

	public WarnEntry addNode(String uuid, String name) {
		uuids.add(uuid);
		names.add(name);
		return this;
	}

	/** sets (or replaces) the built-in fix */
	public WarnEntry setFix(FutureTask fix, FixKind kind) {
		this.autoFix = fix;
		this.fixKind = fix == null ? FixKind.NONE : kind != null ? kind : FixKind.ASKS;
		return this;
	}

	public WarnEntry setFixKind(FixKind kind) {
		this.fixKind = kind != null ? kind : FixKind.NONE;
		return this;
	}

	/** plain text: what the built-in fix will do (shown as the button's hint) */
	public WarnEntry setFixPreview(String fixPreview) {
		this.fixPreview = fixPreview;
		return this;
	}

	public boolean isFixed() {
		return fixed;
	}

	public String getMessage() {
		return message;
	}

	public boolean isInfo() {
		return severity == Severity.INFO;
	}

	public Severity getSeverity() {
		return severity;
	}

	public String getKey() {
		return key;
	}

	public Object[] getArgs() {
		return args;
	}

	public String getConsequenceKey() {
		return consequenceKey;
	}

	public List<String> getUuids() {
		return Collections.unmodifiableList(uuids);
	}

	public List<String> getNames() {
		return Collections.unmodifiableList(names);
	}

	public FixKind getFixKind() {
		return fixKind;
	}

	public String getFixPreview() {
		return fixPreview;
	}

	public FutureTask getBuiltInFix() {
		return autoFix;
	}

	/** BREAKS rows first, then by priority (higher first); otherwise the order rows were added in */
	@Override
	public int compareTo(WarnEntry o) {
		boolean b1 = severity == Severity.BREAKS, b2 = o.severity == Severity.BREAKS;
		if(b1 != b2)return b1 ? -1 : 1;
		return -Integer.compare(priority, o.priority);
	}

	public boolean canQuickFix() {
		return autoFix != null;
	}

	public String getTooltip() {
		return tooltip;
	}

	public static class MultiWarnEntry extends WarnEntry {
		private int count = 1;

		private MultiWarnEntry(String message) {
			super(message);
		}

		public static WarnEntry addOrIncEntry(List<WarnEntry> ent, String msg) {
			WarnEntry e = ent.stream().filter(w -> w instanceof MultiWarnEntry && ((MultiWarnEntry)w).getMessage0().equals(msg)).findFirst().orElse(null);
			if(e != null) {
				((MultiWarnEntry)e).count++;
			} else {
				e = new MultiWarnEntry(msg);
				ent.add(e);
			}
			return e;
		}

		public String getMessage0() {
			return super.getMessage();
		}

		@Override
		public String getMessage() {
			return I18n.formatBr("bb-label.warn.multiple", super.getMessage(), count);
		}
	}

	/**
	 * CPM+ (W0C): builds structured rows. The message comes from a lang key; every argument is HTML-escaped (names
	 * typed by the user are safe in the dialog).
	 */
	public static final class WarnFactory {
		private WarnFactory() {
		}

		/** row with severity, stable key and the message of msgKey formatted with the (escaped) args */
		public static WarnEntry of(Severity severity, String key, String msgKey, Object... args) {
			Object[] esc = new Object[args != null ? args.length : 0];
			for (int i = 0; i < esc.length; i++)esc[i] = args[i] instanceof Number ? args[i] : escape(args[i]);
			WarnEntry w = new WarnEntry(I18n.formatBr(msgKey, esc));
			w.setSeverity(severity);
			w.setKey(key, args);
			return w;
		}

		/** row with severity, stable key and an already formatted (trusted HTML) message */
		public static WarnEntry ofHtml(Severity severity, String key, String trustedHtml) {
			WarnEntry w = new WarnEntry(trustedHtml);
			w.setSeverity(severity);
			w.setKey(key);
			return w;
		}

		static String escape(Object o) {
			if(o == null)return "";
			return String.valueOf(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
		}
	}
}
