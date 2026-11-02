package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport;
import com.tom.cpm.blockbench.tools.anim.MirrorAnimation;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.KeyMath;
import com.tom.cpm.blockbench.util.KeyMath.KeyData;
import com.tom.cpm.blockbench.util.PartPairing;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of the animation editing core (CPM+ v0.8, lite build): KeyMath, PartPaths, PartPairing, AnimEdit
 * and F2 'Criar cópia espelhada' (D3: a whole animation mirrored left/right into a new copy), plus the
 * 'flip_node_name' listener (Blockbench's own mirror tools - also 'Espelhar enquanto animo' - understand
 * direita/esquerda in CPM projects) and the undo fix that keeps a CPM snapping below 10. Everything is registered
 * through PlusMenus / PluginStart and removed on unload.
 */
public final class W2AnimcoreInstall {
	private W2AnimcoreInstall() {
	}

	public static void install() {
		AnimEdit.installFlipNameListener();
		AnimEdit.installSnappingUndoFix();

		// CPM+ > Animação > 'Criar cópia espelhada...' and the animation's right-click menu
		Action f2 = PlusMenus.action("cpmplus_w2animcore_f2", "cpmplus.w2animcore.menu.f2", "flip_to_back", Cond.CPM_ONLY, () -> AnimToolSupport.animateMode() && Animation.selected != null, MirrorAnimation::openDialog);
		PlusMenus.add(PlusMenus.SUB_ANIM, f2);
		PlusMenus.addToContext(Animation.menu, f2, "");

		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
	}

	// ====================================================================== QA hooks (test mode only)

	private static void registerTestTools() {
		PlusTestHooks.register("w2animcore.f2", MirrorAnimation::hook);
		PlusTestHooks.register("w2animcore.open", o -> {
			// opens the dialog the way the menu does (QA of the pt-BR labels / dialog id)
			if ("f2".equals(str(o, "tool", "")))MirrorAnimation.openDialog();
			return ok();
		});
		// key dump of a part for assertions: {anim, part} -> channels {ch: [{time, interp, v: [[x,y,z]..], vis, bLV, bRV, bLT, bRT, molang}]}
		PlusTestHooks.register("w2animcore.keys", o -> {
			Animation a = AnimEdit.byName(str(o, "anim", ""));
			Group g = AnimEdit.groupByPath(str(o, "part", ""));
			JsPropertyMap<Object> r = ok();
			if (a == null || g == null) {
				r.set("ok", false);
				return r;
			}
			BoneAnimator ba = AnimEdit.animatorOf(a, g);
			JsPropertyMap<Object> chans = JsPropertyMap.of();
			for (String ch : KeyMath.CHANNELS) {
				JsArray<Object> arr = new JsArray<>();
				if (ba != null)for (KeyData k : AnimEdit.readKeys(ba, ch))arr.push(keyJs(k));
				chans.set(ch, arr);
			}
			r.set("channels", chans);
			r.set("hasAnimator", ba != null);
			r.set("inTimeline", ba != null && inTimeline(ba));
			return r;
		});
		// Blockbench interpolation at times: {anim, part, channel, times: [..]} -> values [[x,y,z]..] (visibility: booleans)
		PlusTestHooks.register("w2animcore.sample", o -> {
			Animation a = AnimEdit.byName(str(o, "anim", ""));
			Group g = AnimEdit.groupByPath(str(o, "part", ""));
			String ch = str(o, "channel", KeyMath.ROT);
			JsPropertyMap<Object> r = ok();
			BoneAnimator ba = a != null && g != null ? AnimEdit.animatorOf(a, g) : null;
			JsArray<Object> vals = new JsArray<>();
			Object ts = o.get("times");
			if (ts != null && JsArray.isArray(ts)) {
				JsArray<Object> ta = Js.uncheckedCast(ts);
				for (int i = 0; i < ta.length; i++) {
					double t = Js.asDouble(ta.getAt(i));
					if (KeyMath.VIS.equals(ch))vals.push(AnimEdit.visAt(ba, t, g == null || !g.hidden));
					else vals.push(JsonUtil.toJs(toList(AnimEdit.valueOrRest(ba, ch, t))));
				}
			}
			r.set("values", vals);
			return r;
		});
		PlusTestHooks.register("w2animcore.pair", o -> {
			JsPropertyMap<Object> r = ok();
			Group g = AnimEdit.groupByPath(str(o, "part", ""));
			if (g == null) {
				r.set("ok", false);
				return r;
			}
			PartPairing.Pair<Object> p = AnimEdit.pairOf(g);
			r.set("kind", p.kind.name());
			r.set("target", p.target != null ? AnimEdit.pathOf(p.target) : null);
			return r;
		});
		PlusTestHooks.register("w2animcore.copyName", o -> {
			JsPropertyMap<Object> r = ok();
			r.set("name", MirrorAnimation.copyName(str(o, "name", "")));
			r.set("type", MirrorAnimation.mirrorType(str(o, "type", "")));
			return r;
		});
	}

	private static native boolean inTimeline(Object animator) /*-{
		return !!($wnd.Timeline && $wnd.Timeline.animators && $wnd.Timeline.animators.indexOf(animator) >= 0);
	}-*/;

	private static JsPropertyMap<Object> keyJs(KeyData k) {
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("time", k.time);
		m.set("interp", k.interp);
		JsArray<Object> v = new JsArray<>();
		for (double[] d : k.v)v.push(JsonUtil.toJs(toList(d)));
		m.set("v", v);
		m.set("vis", k.vis);
		m.set("molang", k.molang);
		if (k.bLV != null)m.set("bLV", JsonUtil.toJs(toList(k.bLV)));
		if (k.bRV != null)m.set("bRV", JsonUtil.toJs(toList(k.bRV)));
		if (k.bLT != null)m.set("bLT", JsonUtil.toJs(toList(k.bLT)));
		if (k.bRT != null)m.set("bRT", JsonUtil.toJs(toList(k.bRT)));
		Object payload = k.payload;
		if (payload != null) {
			JsPropertyMap<Object> pm = Js.asPropertyMap(payload);
			Object dps = pm.get("data_points");
			if (dps != null && JsArray.isArray(dps) && Js.<JsArray<Object>>uncheckedCast(dps).length > 0) {
				Object cp = Js.asPropertyMap(Js.<JsArray<Object>>uncheckedCast(dps).getAt(0)).get("cpm_color_picker_place");
				if (cp != null)m.set("colorPicker", cp);
			}
		}
		return m;
	}

	private static List<Object> toList(double[] d) {
		List<Object> l = new ArrayList<>();
		if (d != null)for (double x : d)l.add(x);
		return l;
	}

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static JsPropertyMap<Object> ok() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", true);
		r.set("changed", false);
		r.set("warnings", new JsArray<>());
		r.set("errors", new JsArray<>());
		return r;
	}
}
