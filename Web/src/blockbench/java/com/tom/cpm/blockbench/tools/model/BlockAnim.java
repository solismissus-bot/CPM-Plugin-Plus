package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.CPMVisuals;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.RootUtil;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.HTMLStyleElement;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W4-RIG): 'Animar bloco por bloco' and 'Lista só com grupos'.
 * <ul>
 * <li>Bloco por bloco (opt-in, remembered per user): in Animate mode, clicking a cube that shares its group with other
 * pieces gives that cube its own bone right away ('Fazer osso desta peça' with the joint where it touches the rest),
 * then selects the new bone so it can be keyed. A cube that already is alone in its group just selects that group, so
 * clicking again never makes another bone. One undo entry per bone; nothing moves (BuildChain's self-test).</li>
 * <li>Só grupos (opt-in, remembered per user): in Animate mode of a CPM project the outliner hides the cube rows and
 * keeps only the groups (bones). Edit mode always shows everything.</li>
 * </ul>
 */
public final class BlockAnim {
	public static final String PREF_BLOCKS = "cpmplus.w4rig.blockAnim";// lang_check:not-a-key (PlusPrefs key, kept: users' saved choice)
	public static final String PREF_GROUPS_ONLY = "cpmplus.w4rig.groupsOnly";// lang_check:not-a-key (PlusPrefs key)
	private static final String BODY_CLASS = "cpmplus_groups_only";
	private static final String STYLE_ID = "cpmplus_groups_only_style";

	private static boolean blocks, groupsOnly, busy;

	private BlockAnim() {
	}

	public static void load() {
		blocks = PlusPrefs.getBool(PREF_BLOCKS, false);
		groupsOnly = PlusPrefs.getBool(PREF_GROUPS_ONLY, false);
		ensureStyle();
		try {
			installSelectPatch();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: block by block (outliner click) not available", e);
		}
		refreshOutliner();
	}

	// ------------------------------------------------------------------ outliner click on a block (Animate mode)

	/**
	 * Blockbench refuses Cube.select in Animate mode ('group required to animate'), which is what a click on a block in
	 * the outliner calls. With the option ON (CPM project, Animate mode) the click is handled here instead: the block gets
	 * its bone (or its existing bone is selected). Everything else goes to Blockbench unchanged.
	 */
	private static native void installSelectPatch() /*-{
		var C = $wnd.Cube;
		if (!C || !C.prototype || C.prototype.__cpmplusBlockAnimOrig) return;
		var orig = C.prototype.select;
		C.prototype.__cpmplusBlockAnimOrig = orig;
		C.prototype.select = function() {
			try {
				if (@com.tom.cpm.blockbench.tools.model.BlockAnim::outlinerSelect(Ljava/lang/Object;)(this)) return this;
			} catch (e) {
				$wnd.console.warn('CPM+: block by block', e);
			}
			return orig.apply(this, arguments);
		};
	}-*/;

	private static native void uninstallSelectPatch() /*-{
		var C = $wnd.Cube;
		if (!C || !C.prototype || !C.prototype.__cpmplusBlockAnimOrig) return;
		C.prototype.select = C.prototype.__cpmplusBlockAnimOrig;
		delete C.prototype.__cpmplusBlockAnimOrig;
	}-*/;

	/** true when the select was handled (option ON, CPM, Animate mode, a block inside a group) */
	static boolean outlinerSelect(Object el) {
		if (!blocks || busy || !(el instanceof Cube))return false;
		if (!CPMCodec.isCPM() || !CPMVisuals.isAnimateMode())return false;
		Cube c = (Cube) el;
		if (!(c.parent instanceof Group))return false;
		if (needsBone(c)) {
			if (makeBone(c) == null)RigParts.select(c.parent);
		} else {
			RigParts.select(c.parent);
		}
		return true;
	}

	/**
	 * Blockbench 5 hides block rows in Animate mode (Mode.hidden_node_types). With 'Animar bloco por bloco' ON (and the
	 * groups-only list OFF) the block rows are listed in CPM projects so they can be clicked; the original list is
	 * restored otherwise.
	 */
	private static native void setBlockRows(boolean show) /*-{
		var M = $wnd.Modes;
		var m = M && M.options && M.options.animate;
		if (!m) return;
		if (!m.__cpmplusHiddenOrig) m.__cpmplusHiddenOrig = (m.hidden_node_types || []).slice();
		var base = m.__cpmplusHiddenOrig;
		var want = show ? base.filter(function(t) { return t !== 'cube'; }) : base.slice();
		var cur = m.hidden_node_types || [];
		if (cur.length === want.length && cur.every(function(t, i) { return t === want[i]; })) return;
		m.hidden_node_types = want;
		var O = $wnd.Outliner;
		if (O && typeof O.updateNodeDisplayRules === 'function') O.updateNodeDisplayRules();
	}-*/;

	private static native void restoreBlockRows() /*-{
		var M = $wnd.Modes;
		var m = M && M.options && M.options.animate;
		if (!m || !m.__cpmplusHiddenOrig) return;
		m.hidden_node_types = m.__cpmplusHiddenOrig.slice();
		delete m.__cpmplusHiddenOrig;
		var O = $wnd.Outliner;
		if (O && typeof O.updateNodeDisplayRules === 'function') O.updateNodeDisplayRules();
	}-*/;

	public static boolean isBlocks() {
		return blocks;
	}

	public static boolean isGroupsOnly() {
		return groupsOnly;
	}

	public static void setBlocks(boolean on) {
		blocks = on;
		PlusPrefs.set(PREF_BLOCKS, on);
		refreshOutliner();
		PlusDialogs.quick(PlusDialogs.tr(on ? "cpmplus.w4rig.blockanim.on" : "cpmplus.w4rig.blockanim.off"), on ? 9000 : 4000);
	}

	public static void setGroupsOnly(boolean on) {
		groupsOnly = on;
		PlusPrefs.set(PREF_GROUPS_ONLY, on);
		refreshOutliner();
		PlusDialogs.quick(PlusDialogs.tr(on ? "cpmplus.w4rig.groupsonly.on" : "cpmplus.w4rig.groupsonly.off"), 5000);
	}

	// ------------------------------------------------------------------ outliner: only groups

	private static void ensureStyle() {
		if (DomGlobal.document.getElementById(STYLE_ID) != null)return;
		HTMLStyleElement st = (HTMLStyleElement) DomGlobal.document.createElement("style");
		st.id = STYLE_ID;
		st.textContent = "body." + BODY_CLASS + " #panel_outliner li.outliner_node:has(> .outliner_object[element_type=\"cube\"]){display:none !important;}";
		DomGlobal.document.head.appendChild(st);
	}

	/** body class on/off: option ON + CPM project + Animate mode (called on mode / project switches too) */
	public static void refreshOutliner() {
		try {
			Element body = DomGlobal.document.body;
			if (body == null)return;
			boolean cpm = CPMCodec.isCPM();
			boolean on = groupsOnly && cpm && CPMVisuals.isAnimateMode();
			if (on)body.classList.add(BODY_CLASS);
			else body.classList.remove(BODY_CLASS);
			setBlockRows(blocks && !groupsOnly && cpm);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: groups-only outliner", e);
		}
	}

	public static void uninstall() {
		try {
			uninstallSelectPatch();
			restoreBlockRows();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: block by block cleanup", e);
		}
		try {
			if (DomGlobal.document.body != null)DomGlobal.document.body.classList.remove(BODY_CLASS);
			Element st = DomGlobal.document.getElementById(STYLE_ID);
			if (st != null)st.remove();
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: groups-only cleanup", e);
		}
	}

	// ------------------------------------------------------------------ block by block

	/** Blockbench 'canvas_select' (fired before the click changes the selection): data.element = the clicked piece */
	public static void onCanvasSelect(Object data) {
		if (!blocks || busy || data == null)return;
		try {
			if (!CPMCodec.isCPM() || !CPMVisuals.isAnimateMode())return;
			Object el = Js.asPropertyMap(data).get("element");
			if (!(el instanceof Cube))return;
			Cube c = (Cube) el;
			if (!needsBone(c))return;
			// after Blockbench finished its own click (selection undo entry closed), so the edit is a clean undo step
			DomGlobal.setTimeout(__ -> makeBone(c), 0);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: block by block", e);
		}
	}

	/** true when the cube shares its group with other pieces (else the group already is its bone) */
	static boolean needsBone(Cube c) {
		if (!(c.parent instanceof Group))return false;
		Group p = c.parent;
		if (p.children == null)return false;
		for (int i = 0; i < p.children.length; i++) {
			OutlinerNode n = p.children.getAt(i);
			if (n != c)return true;
		}
		return false;
	}

	/** gives the cube its own bone and selects it; returns the bone or null (refused: the reason is shown) */
	public static Group makeBone(Cube c) {
		if (busy || c == null || !needsBone(c))return null;
		busy = true;
		try {
			List<Object> sel = new ArrayList<>();
			sel.add(c);
			String prefix = freePrefix(BuildChain.defaultPrefix(sel));
			BuildChain.Plan p = BuildChain.plan(sel, BuildChain.Order.CLICK, BuildChain.JointMode.SEAM, prefix, true);
			if (p.error != null) {
				PlusDialogs.quick(PlusDialogs.trf("cpmplus.w4rig.blockanim.refused", c.name, p.error), 7000);
				return null;
			}
			List<Group> links = BuildChain.apply(p, p.joints);
			if (links.isEmpty())return null;
			Group bone = links.get(0);
			RigParts.select(bone);
			PlusDialogs.quick(PlusDialogs.trf("cpmplus.w4rig.blockanim.made", bone.name), 3500);
			return bone;
		} catch (Throwable e) {
			DomGlobal.console.error("CPM+: block by block failed", e);
			PlusDialogs.quick(PlusDialogs.trf("cpmplus.w4rig.blockanim.refused", c.name, String.valueOf(e.getMessage())), 7000);
			return null;
		} finally {
			busy = false;
		}
	}

	/** a prefix whose first name (prefix + '1') is free and not a reserved body-part name */
	static String freePrefix(String base) {
		Set<String> used = new HashSet<>();
		if (Group.all != null)for (Group g : Group.all)if (g != null && g.name != null)used.add(g.name.toLowerCase());
		for (int k = 0; k < 1000; k++) {
			String pre = k == 0 ? base : base + "_" + k;
			String n = pre + "1";
			if (!used.contains(n.toLowerCase()) && RootUtil.part(n) == null && RootUtil.part(pre) == null)return pre;
		}
		return base + "_" + System.currentTimeMillis();
	}

	/** test hook state */
	public static JsPropertyMap<Object> state() {
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("blocks", blocks);
		m.set("groupsOnly", groupsOnly);
		m.set("bodyClass", DomGlobal.document.body != null && DomGlobal.document.body.classList.contains(BODY_CLASS));
		return m;
	}
}
