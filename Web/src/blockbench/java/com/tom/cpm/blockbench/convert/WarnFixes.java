package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.convert.WarnEntry.FutureTask;
import com.tom.cpm.web.client.util.I18n;

import elemental2.dom.DomGlobal;

/**
 * CPM+ (W0C-EXPORTCORE): fixes for warning rows, registered by any package under the row's key
 * ({@link WarnEntry#getKey()}). A MULTI-registry: several packages may register fixes for the same key, none
 * replaces another.
 * <ul>
 * <li>The row's own (built-in) fix always comes first (order 0); registered fixes follow by order, then by
 * registration.</li>
 * <li>One fix in total: the row shows one button. More than one: a 'Corrigir...' button opens a chooser that lists
 * every fix label.</li>
 * <li>A FixFactory returns null when its fix does not apply to that particular row (the row then does not list it).</li>
 * </ul>
 * <pre>
 * WarnFixes.register(LayerCapacity.WARN_KEY, "cpmplus.w2warni18n.fix.skinLayers", 10, w -&gt; () -&gt; openSkinLayersDialog());
 * </pre>
 * Registrations are removed when the plugin unloads (or with the returned Runnable).
 */
public final class WarnFixes {
	/** label lang key of the built-in fix (the label the row always had) */
	public static final String BUILT_IN_LABEL = "bb-button.autoFix";

	private static final Map<String, List<Fix>> REG = new HashMap<>();
	private static int seq;

	private WarnFixes() {
	}

	/** creates the fix task for one row, or null when the fix does not apply to it */
	public static interface FixFactory {
		FutureTask create(WarnEntry entry);
	}

	/** one registered fix */
	public static final class Fix {
		public final String warnKey, labelKey;
		/** lang key of one line saying what the fix does (shown in the 'Corrigir...' chooser), or null */
		public final String previewKey;
		public final int order;
		public final FixFactory factory;
		private final int seqNo;

		Fix(String warnKey, String labelKey, String previewKey, int order, FixFactory factory, int seqNo) {
			this.warnKey = warnKey;
			this.labelKey = labelKey;
			this.previewKey = previewKey;
			this.order = order;
			this.factory = factory;
			this.seqNo = seqNo;
		}
	}

	/** one fix a row offers: label (translated) + task */
	public static final class Choice {
		public final String label;
		public final FutureTask task;
		public final boolean builtIn;
		/** what the fix does, as safe HTML (escaped text or the row's trusted tooltip), or null */
		public final String previewHtml;

		Choice(String label, FutureTask task, boolean builtIn, String previewHtml) {
			this.label = label;
			this.task = task;
			this.builtIn = builtIn;
			this.previewHtml = previewHtml;
		}
	}

	/**
	 * Registers a fix for rows with this key. labelKey = lang key of the button / chooser line (a short verb phrase:
	 * 'Liberar camadas da skin'); order sorts the registered fixes (the built-in fix is always first).
	 * <p>
	 * The fix task resolves true when it changed the project (the dialog then checks again), false when it did
	 * nothing / the user gave up. To give up after showing its own message, resolve false or reject with
	 * PopupDialogs.CancelException: any other rejection (and an exception thrown by the task) is shown to the user as
	 * 'the fix was not applied' with the details.
	 * @return a Runnable that removes the registration (it is also removed when the plugin unloads)
	 */
	public static Runnable register(String warnKey, String labelKey, int order, FixFactory factory) {
		return register(warnKey, labelKey, null, order, factory);
	}

	/**
	 * Same, with previewKey = lang key of one plain line that says what the fix does (listed under its label in the
	 * 'Corrigir...' chooser, so the user can pick between several fixes), or null.
	 */
	public static Runnable register(String warnKey, String labelKey, String previewKey, int order, FixFactory factory) {
		if(warnKey == null || factory == null)throw new IllegalArgumentException("WarnFixes.register: key and factory are required");
		Fix f = new Fix(warnKey, labelKey, previewKey, order, factory, seq++);
		REG.computeIfAbsent(warnKey, k -> new ArrayList<>()).add(f);
		Runnable remove = () -> {
			List<Fix> l = REG.get(warnKey);
			if(l != null) {
				l.remove(f);
				if(l.isEmpty())REG.remove(warnKey);
			}
		};
		PluginStart.cleanup.add(remove);
		return remove;
	}

	/** the fixes registered for key, in the order they are offered (empty list when none) */
	public static List<Fix> get(String warnKey) {
		List<Fix> l = warnKey == null ? null : REG.get(warnKey);
		if(l == null)return Collections.emptyList();
		List<Fix> out = new ArrayList<>(l);
		out.sort((a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : Integer.compare(a.seqNo, b.seqNo));
		return out;
	}

	/**
	 * every fix the row offers: its built-in fix first, then the registered ones that apply to it. Preview of the
	 * built-in fix = the row's fixPreview, else its tooltip; of a registered fix = its previewKey.
	 */
	public static List<Choice> choicesFor(WarnEntry w) {
		List<Choice> out = new ArrayList<>();
		if(w.canQuickFix()) {
			String pv = w.getFixPreview() != null ? WarnEntry.WarnFactory.escape(w.getFixPreview()) : w.getTooltip();
			out.add(new Choice(I18n.get(w.isInfo() ? "label.cpm.info" : BUILT_IN_LABEL), w.getBuiltInFix(), true, pv));
		}
		for (Fix f : get(w.getKey())) {
			try {
				FutureTask t = f.factory.create(w);
				if(t != null)out.add(new Choice(I18n.get(f.labelKey), t, false, f.previewKey != null ? WarnEntry.WarnFactory.escape(I18n.get(f.previewKey)) : null));
			} catch (Throwable e) {
				DomGlobal.console.error("CPM+: fix '" + f.labelKey + "' for '" + f.warnKey + "' failed", e);
			}
		}
		return out;
	}
}
