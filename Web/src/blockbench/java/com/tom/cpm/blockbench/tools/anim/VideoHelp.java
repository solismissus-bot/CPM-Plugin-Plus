package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Ajuda: vi isso num vídeo': most CPM tutorials are recorded in CPM's own editor. This card says where each thing
 * they show lives in Blockbench + CPM+ (one row per topic, the shortcut when there is one).
 */
public final class VideoHelp {
	private VideoHelp() {
	}

	/** topic ids: lang keys cpmplus.w3animtools.video.&lt;id&gt;.seen / .here */
	static final String[] TOPICS = {"add", "type", "additive", "priority", "frames", "interp", "test", "copy", "hide", "button", "export", "duplicate", "loop", "value"};

	/** CPM+ &gt; Animação &gt; 'Ajuda: vi isso num vídeo...' */
	public static void open() {
		StringBuilder html = new StringBuilder("<table style='width:100%;border-collapse:collapse'><tr><th style='text-align:left;width:40%'>")
				.append(PlusDialogs.esc(t("cpmplus.w3animtools.video.colSeen"))).append("</th><th style='text-align:left'>")
				.append(PlusDialogs.esc(t("cpmplus.w3animtools.video.colHere"))).append("</th></tr>");
		for (String id : TOPICS) {
			html.append("<tr><td style='vertical-align:top;padding:4px 6px;border-top:1px solid var(--color-border)'>")
			.append(PlusDialogs.esc(t("cpmplus.w3animtools.video." + id + ".seen")))
			.append("</td><td style='vertical-align:top;padding:4px 6px;border-top:1px solid var(--color-border)'>")
			.append(PlusDialogs.esc(t("cpmplus.w3animtools.video." + id + ".here"))).append("</td></tr>");
		}
		html.append("</table>");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w3animtools.video.intro")));
		rows.add(FormRow.htmlInfo("table", null, html.toString()));
		FormOpts fo = new FormOpts();
		fo.width = 780;
		fo.confirmKey = "cpmplus.w3animtools.trans.report.close";
		PlusDialogs.form("cpmplus_w3animtools_video", "cpmplus.w3animtools.video.title", rows, fr -> {}, fo);
	}

	/** videoHelp({}) -&gt; the topics with their texts (QA: every key translated) */
	public static Object hook(JsPropertyMap<Object> opts) {
		JsArray<Object> arr = new JsArray<>();
		for (String id : TOPICS) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("id", id);
			m.set("seen", t("cpmplus.w3animtools.video." + id + ".seen"));
			m.set("here", t("cpmplus.w3animtools.video." + id + ".here"));
			arr.push(m);
		}
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("topics", arr);
		return r;
	}
}
