package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTools;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.MenuBar.BarItem;
import com.tom.cpm.blockbench.proxy.MenuBar.SubMenu;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ F14 'Animação rápida' (CPM+ &gt; Animação): Blockbench's scattered animation helpers in one pt-BR submenu, each
 * with a one-line hint, plus our own 'Inverter'.
 * <ul>
 * <li>Inverter: the WHOLE selected animation played backwards over [0, L] (QuickAnimMath.reverse; Blockbench's
 * reverse_keyframes only mirrors between the first and last SELECTED key and breaks visibility steps). One undo.</li>
 * <li>Deixar tudo suave / reto: PlusTools.applyInterpolation (catmullrom / linear; visibility keys stay 'step').</li>
 * <li>Mudar velocidade..., Fantasma (onion skin), Rastro, Travar o rastro, Espelhar enquanto animo: Blockbench's own
 * items, triggered as they are; each entry exists only when that Blockbench item exists.</li>
 * </ul>
 */
public final class QuickAnim {
	private QuickAnim() {
	}

	/** our item -&gt; {Blockbench BarItem id, icon} (an entry is only added when the BarItem exists) */
	static final Map<String, String[]> NATIVE = new LinkedHashMap<>();
	static {
		NATIVE.put("speed", new String[] {"change_animation_speed", "av_timer"});
		NATIVE.put("onion", new String[] {"animation_onion_skin", "blur_on"});
		NATIVE.put("trail", new String[] {"toggle_motion_trails", "timeline"});
		NATIVE.put("trail_lock", new String[] {"lock_motion_trail", "lock"});
		NATIVE.put("mirror", new String[] {"mirror_animating", "flip"});
	}

	/** ids of the entries made by {@link #install()} (QA) */
	static final List<String> ENTRY_IDS = new ArrayList<>();

	// ====================================================================== Inverter

	private static final class Plan {
		final BoneAnimator ba;
		final String ch;
		final List<KeyData> keys;

		Plan(BoneAnimator ba, String ch, List<KeyData> keys) {
			this.ba = ba;
			this.ch = ch;
			this.keys = keys;
		}
	}

	public static Result reverse(Animation anim, boolean apply) {
		Result r = new Result();
		if (anim == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation"));
		double L = anim.length;
		if (!(L > KeyMath.EPS))return r.error("empty", t("cpmplus.w3animtools.f14.reverse.empty", anim.name));
		List<Plan> plans = new ArrayList<>();
		int keys = 0, mixed = 0, molang = 0;
		for (BoneAnimator ba : AnimEdit.boneAnimators(anim)) {
			Group g = AnimEdit.groupOf(ba);
			boolean def = AnimToolSupport.defaultVisible(g);
			for (String ch : KeyMath.CHANNELS) {
				List<KeyData> k = AnimEdit.readKeys(ba, ch);
				if (k.isEmpty())continue;
				for (KeyData x : k)if (x.molang)molang++;
				QuickAnimMath.Reversed rv = QuickAnimMath.reverse(ch, k, L, def);
				if (rv.mixedStep)mixed++;
				plans.add(new Plan(ba, ch, rv.keys));
				keys += rv.keys.size();
			}
		}
		if (molang > 0)r.warn("molang", t("cpmplus.w3animtools.f14.reverse.formula", molang));
		if (mixed > 0)r.warn("mixedStep", t("cpmplus.w3animtools.f14.reverse.mixedStep"));
		r.put("keys", (double) keys).put("channels", (double) plans.size());
		if (plans.isEmpty())return r.error("empty", t("cpmplus.w3animtools.f14.reverse.empty", anim.name));
		if (!apply)return r;
		PlusUndo.editAnimations(Collections.singletonList(anim), t("cpmplus.w3animtools.f14.reverse.undo"), () -> {
			for (Plan p : plans)AnimEdit.writeKeys(p.ba, p.ch, p.keys, true);
		});
		r.changed = true;
		AnimToolSupport.refreshPreview();
		return r;
	}

	static void reverseAction() {
		Animation anim = Animation.selected;
		Result r = reverse(anim, true);
		if (!r.ok) {
			PlusDialogs.block(r.errorText());
			return;
		}
		PlusDialogs.done(t("cpmplus.w3animtools.f14.reverse.done", anim.name));
		if (!r.warnings.isEmpty())PlusDialogs.toast(r.warningText(), "warning", 10000, null);
	}

	// ====================================================================== Suave / Reto

	/** 'catmullrom' (suave) or 'linear' (reto) on every key of anim (one undo); @return keys changed */
	public static int setInterpolation(Animation anim, String interp) {
		if (anim == null)return 0;
		Animation keep = Animation.selected;
		boolean swap = keep != anim;
		try {
			if (swap)Animation.selected = anim;
			return PlusTools.applyInterpolation(interp, false);
		} finally {
			if (swap)Animation.selected = keep;
		}
	}

	static void interpAction(String interp) {
		Animation anim = Animation.selected;
		if (anim == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		int n = setInterpolation(anim, interp);
		boolean smooth = "catmullrom".equals(interp);
		if (n == 0)PlusDialogs.quick(t(smooth ? "cpmplus.w3animtools.f14.smooth.nothing" : "cpmplus.w3animtools.f14.linear.nothing"), 3000);
		else PlusDialogs.done(t(smooth ? "cpmplus.w3animtools.f14.smooth.done" : "cpmplus.w3animtools.f14.linear.done", n));
	}

	// ====================================================================== Blockbench's own items

	static boolean exists(String barItemId) {
		try {
			return Global.getBarItems() != null && Global.getBarItem(barItemId) != null;
		} catch (Throwable e) {
			return false;
		}
	}

	/** Blockbench's own condition of its item (e.g. the trail lock needs a selected part) */
	static boolean ready(String barItemId) {
		try {
			BarItem bi = Global.getBarItem(barItemId);
			return bi != null && bi.conditionMet();
		} catch (Throwable e) {
			return false;
		}
	}

	/** runs a Blockbench item like a click (Blockbench checks its own condition); @return the toggle state after, or null */
	static Boolean trigger(String barItemId) {
		BarItem bi = Global.getBarItem(barItemId);
		if (bi == null)return null;
		Action a = Js.uncheckedCast(bi);
		a.trigger(null);
		Object v = Js.asPropertyMap(bi).get("value");
		return v != null && "boolean".equals(Js.typeof(v)) ? (Boolean) Js.isTruthy(v) : null;
	}

	/** what the menu entry does; @return the Blockbench item's toggle state after, or null */
	static Boolean nativeAction(String item) {
		String id = NATIVE.get(item)[0];
		if (!exists(id)) {
			PlusDialogs.block(t("cpmplus.w3animtools.f14.missing"));
			return null;
		}
		if (!ready(id)) {
			// never a click that silently does nothing
			PlusDialogs.block(t("trail_lock".equals(item) ? "cpmplus.w3animtools.err.selectPart" : "cpmplus.w3animtools.f14.notNow"));
			return null;
		}
		if ("speed".equals(item)) {
			if (Animation.selected == null) {
				PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
				return null;
			}
			// the hint first, then Blockbench's own dialog
			PlusDialogs.toast(t("cpmplus.w3animtools.f14.speed.hint"), "av_timer", 12000, null);
			trigger(id);
			return null;
		}
		Boolean on = trigger(id);
		if (on != null)PlusDialogs.quick(t(on ? "cpmplus.w3animtools.f14.state.on" : "cpmplus.w3animtools.f14.state.off", t("cpmplus.w3animtools.f14." + item)), 2500);
		return on;
	}

	// ====================================================================== menu

	/** the 'Animação rápida' submenu inside CPM+ &gt; Animação */
	public static void install() {
		ENTRY_IDS.clear();
		List<Object> children = new ArrayList<>();
		Action rev = PlusMenus.action("cpmplus_w3animtools_quick_reverse", "cpmplus.w3animtools.f14.reverse", "swap_horizontal_circle", Cond.CPM_ONLY,
				() -> Animation.selected != null, QuickAnim::reverseAction);
		children.add(rev);
		ENTRY_IDS.add(rev.id);
		addNative(children, "speed", () -> Animation.selected != null && AnimToolSupport.animateMode());
		children.add("_");
		Action smooth = PlusMenus.action("cpmplus_w3animtools_quick_smooth", "cpmplus.w3animtools.f14.smooth", "gesture", Cond.CPM_ONLY,
				() -> Animation.selected != null, () -> interpAction("catmullrom"));
		Action linear = PlusMenus.action("cpmplus_w3animtools_quick_linear", "cpmplus.w3animtools.f14.linear", "show_chart", Cond.CPM_ONLY,
				() -> Animation.selected != null, () -> interpAction("linear"));
		children.add(smooth);
		children.add(linear);
		ENTRY_IDS.add(smooth.id);
		ENTRY_IDS.add(linear.id);
		children.add("_");
		addNative(children, "onion", AnimToolSupport::animateMode);
		addNative(children, "trail", AnimToolSupport::animateMode);
		addNative(children, "trail_lock", AnimToolSupport::animateMode);
		addNative(children, "mirror", AnimToolSupport::animateMode);
		SubMenu sm = new SubMenu();
		sm.id = "cpmplus_w3animtools_quick";
		sm.name = t("cpmplus.w3animtools.f14.menu");
		sm.icon = "bolt";
		JsArray<Object> arr = new JsArray<>();
		for (Object o : children)arr.push(o);
		sm.children = Js.uncheckedCast(arr);
		PlusMenus.add(PlusMenus.SUB_ANIM, sm);
	}

	private static void addNative(List<Object> children, String item, java.util.function.BooleanSupplier when) {
		String[] n = NATIVE.get(item);
		if (!exists(n[0]))return;
		Action a = PlusMenus.action("cpmplus_w3animtools_quick_" + item, "cpmplus.w3animtools.f14." + item, n[1], Cond.CPM_ONLY, when, () -> nativeAction(item));
		children.add(a);
		ENTRY_IDS.add(a.id);
	}

	// ====================================================================== QA hooks

	/** f14reverse({anim, apply}) */
	public static Object hookReverse(JsPropertyMap<Object> o) {
		return reverse(AnimToolSupport.anim(o), AnimToolSupport.bool(o, "apply", true)).toJs();
	}

	/** f14interp({anim, interp: 'catmullrom' | 'linear'}) -&gt; {ok, changed, count} */
	public static Object hookInterp(JsPropertyMap<Object> o) {
		Result r = new Result();
		Animation a = AnimToolSupport.anim(o);
		if (a == null)return r.error("noAnim", t("cpmplus.w3animtools.err.noAnimation")).toJs();
		int n = setInterpolation(a, AnimToolSupport.str(o, "interp", "catmullrom"));
		r.changed = n > 0;
		r.put("count", (double) n);
		return r.toJs();
	}

	/** f14items() -&gt; {entries: [action ids in the submenu], natives: {item: exists}} */
	public static Object hookItems(JsPropertyMap<Object> o) {
		Result r = new Result();
		r.put("entries", new ArrayList<Object>(ENTRY_IDS));
		Map<String, Object> nat = new LinkedHashMap<>();
		for (Map.Entry<String, String[]> e : NATIVE.entrySet())nat.put(e.getKey(), exists(e.getValue()[0]));
		r.put("natives", nat);
		return r.toJs();
	}

	/** f14native({item}) -&gt; runs the menu entry; {state: toggle value after or null} */
	public static Object hookNative(JsPropertyMap<Object> o) {
		Result r = new Result();
		String item = AnimToolSupport.str(o, "item", "");
		if (!NATIVE.containsKey(item))return r.error("unknown", item).toJs();
		Boolean s = nativeAction(item);
		r.put("state", s);
		return r.toJs();
	}
}
