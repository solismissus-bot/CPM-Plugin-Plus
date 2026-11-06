package com.tom.cpm.blockbench.tools.anim;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.util.AnimEdit;

import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ companion layers ('&lt;name&gt;#física', '&lt;name&gt;#ajuste'): animations that play TOGETHER with an original
 * in game. CPM's exporter keys every pose / gesture / layer button by the text before the first '#'
 * (AnimationExporter: allButtons by EditorAnim.getId()), and every animation of a vanilla pose starts with the pose,
 * so a companion with the same type, the same id and the same exported duration starts and loops with the original.
 * The companion is additive and its priority sits above every non-additive animation on its parts (CPM applies
 * animations in ascending priority; additive ones add up in any order).
 */
final class LayerSupport {
	private LayerSupport() {
	}

	/** '&lt;name&gt;#suffix' (same button in game), or '&lt;name&gt; suffix' when the name already has a part after '#' */
	static String nameFor(String src, String suffix) {
		return src.indexOf('#') < 0 ? src + "#" + suffix : src + " " + suffix;
	}

	/** the original's name of a companion name with one of the suffixes, or null */
	static String sourceNameOf(String name, String... suffixes) {
		if (name == null || name.indexOf('#') < 0)return null;
		for (String suf : suffixes) {
			if (name.endsWith("#" + suf) && name.length() > suf.length() + 1)return name.substring(0, name.length() - suf.length() - 1);
			if (name.endsWith(" " + suf) && name.lastIndexOf('#') < name.length() - suf.length() - 1)return name.substring(0, name.length() - suf.length() - 1);
		}
		return null;
	}

	/** the existing companion of src with one of the suffixes, or null */
	static Animation companionOf(Animation src, String... suffixes) {
		if (src == null)return null;
		for (Animation a : AnimEdit.all()) {
			if (a == src)continue;
			String sn = sourceNameOf(a.name, suffixes);
			if (sn != null && sn.equals(src.name))return a;
		}
		return null;
	}

	/** the original of a companion (by name), or the animation itself */
	static Animation originalOf(Animation a, String... suffixes) {
		if (a == null)return null;
		String sn = sourceNameOf(a.name, suffixes);
		if (sn == null)return a;
		Animation o = AnimEdit.byName(sn);
		return o != null ? o : a;
	}

	/** the original + every NON-additive animation that moves one of the parts (null = any part) */
	static Set<Animation> nonAdditiveTouching(Animation src, Collection<Group> parts, Animation exclude) {
		Set<Animation> s = new LinkedHashSet<>();
		if (src != null)s.add(src);
		for (Animation a : AnimEdit.all()) {
			if (a == exclude || a == src || a.additive)continue;
			if (parts == null) {
				s.add(a);
				continue;
			}
			for (Group g : parts) {
				BoneAnimator ba = AnimEdit.animatorOf(a, g);
				if (ba != null && ba.hasTransform()) {
					s.add(a);
					break;
				}
			}
		}
		return s;
	}

	/** every registered cpm_* property copied as is */
	static native void copyCpmProps(Object src, Object dst) /*-{
		var props = $wnd.Animation && $wnd.Animation.properties;
		if (!props) return;
		for (var k in props) {
			if (k.indexOf('cpm_') !== 0) continue;
			var v = src[k];
			dst[k] = (v && typeof v === 'object') ? JSON.parse(JSON.stringify(v)) : v;
		}
	}-*/;

	/** the original's data (loop, length, every registered property) with a new name, no keys / uuid / markers */
	static Object copyData(Animation src, String name) {
		JsPropertyMap<Object> d = Js.uncheckedCast(clone(src.getUndoCopy(JsPropertyMap.of(), false)));
		d.delete("uuid");
		d.delete("animators");
		d.delete("selected");
		d.delete("saved_name");
		d.delete("markers");
		d.set("name", name);
		return d;
	}

	private static native Object clone(Object o) /*-{
		return JSON.parse(JSON.stringify(o));
	}-*/;

	/** the companion's CPM settings: the original's, then additive, priority, no stored CPM grid, loop, length */
	static void setup(Animation layer, Animation src, int priority, double length) {
		copyCpmProps(src, layer);
		layer.additive = true;
		layer.setPriority(AnimEdit.clampByte(priority));
		layer.setCpmIntType(null);
		layer.setCpmFrames(0);
		layer.setCpmDuration(0);
		layer.loop = src.loop != null ? src.loop : "loop";
		layer.length = (float) length;
	}

	static void setPlaying(Animation a, Object playing) {
		if (a != null)Js.asPropertyMap(a).set("playing", playing);
	}

	static Object playing(Animation a) {
		return a != null ? Js.asPropertyMap(a).get("playing") : null;
	}
}
