package com.tom.cpm.blockbench.proxy.ext;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Group;

/**
 * CPM+ (W2-TOOLS): Blockbench natives used by the Bedrock / GeckoLib animation import (D8), every optional API
 * feature-detected (typeof), never throwing on a missing one.
 */
public final class W2ToolsNatives {
	private W2ToolsNatives() {
	}

	/**
	 * The loader of Blockbench's own Bedrock animation import: 'codec' (BB 5: AnimationCodec.codecs.bedrock.loadFile),
	 * 'animator' (BB 4: Animator.loadFile, deprecated in BB 5), or null when this Blockbench has neither.
	 */
	public static native String bedrockLoader() /*-{
		try {
			var ac = $wnd.AnimationCodec;
			if (ac && ac.codecs && ac.codecs.bedrock && typeof ac.codecs.bedrock.loadFile === 'function') return 'codec';
			if ($wnd.Animator && typeof $wnd.Animator.loadFile === 'function') return 'animator';
		} catch (e) {}
		return null;
	}-*/;

	/**
	 * Blockbench's Bedrock animation loader without its import dialog and without an undo entry of its own (the caller
	 * records one): loads the animations named in filter from file {path, json, content}. Bones are matched to groups
	 * by name (case-insensitive, the first match). @return the new animations (never null)
	 */
	public static native Animation[] loadBedrock(Object file, String[] filter) /*-{
		var ac = $wnd.AnimationCodec;
		var r;
		if (ac && ac.codecs && ac.codecs.bedrock && typeof ac.codecs.bedrock.loadFile === 'function') r = ac.codecs.bedrock.loadFile(file, filter);
		else if ($wnd.Animator && typeof $wnd.Animator.loadFile === 'function') r = $wnd.Animator.loadFile(file, filter);
		else throw new Error('Blockbench has no Bedrock animation loader');
		return r ? r : [];
	}-*/;

	/** a JS object {path, json, content} for {@link #loadBedrock} */
	public static native Object bedrockFile(String path, Object json, String content) /*-{
		return {path: path, name: path, json: json, content: content};
	}-*/;

	/** JSON.parse that returns null instead of throwing */
	public static native Object parseJson(String text) /*-{
		try {
			var o = JSON.parse(text);
			return (o && typeof o === 'object') ? o : null;
		} catch (e) {
			return null;
		}
	}-*/;

	/** deep copy of a JSON value */
	public static native Object cloneJson(Object o) /*-{
		return JSON.parse(JSON.stringify(o));
	}-*/;

	public static native String stringify(Object o) /*-{
		return JSON.stringify(o);
	}-*/;

	/** the keys of an animation's 'animators' map */
	public static native String[] animatorKeys(Animation a) /*-{
		var out = [];
		if (a && a.animators) for (var k in a.animators) if (Object.prototype.hasOwnProperty.call(a.animators, k)) out.push(k);
		return out;
	}-*/;

	/** type of an animator ('bone', 'effect', ...; '' when unknown) */
	public static native String animatorType(Animation a, String key) /*-{
		var an = a && a.animators ? a.animators[key] : null;
		if (!an) return '';
		return typeof an.type === 'string' ? an.type : '';
	}-*/;

	/** number of keyframes of an animator (0 when missing) */
	public static native int animatorKeyCount(Animation a, String key) /*-{
		var an = a && a.animators ? a.animators[key] : null;
		return an && an.keyframes ? an.keyframes.length : 0;
	}-*/;

	/** the display name Blockbench stored on an animator (BoneAnimator.name), '' when missing */
	public static native String animatorName(Animation a, String key) /*-{
		var an = a && a.animators ? a.animators[key] : null;
		return an && typeof an.name === 'string' ? an.name : '';
	}-*/;

	/** sets the stored display name of an animator (it names the bone in the .bbmodel) */
	public static native void setAnimatorName(Animation a, String key, String name) /*-{
		var an = a && a.animators ? a.animators[key] : null;
		if (an) an.name = name;
	}-*/;

	/** deletes an animator from an animation (Animation.removeAnimator when available) */
	public static native void removeAnimator(Animation a, String key) /*-{
		if (!a || !a.animators || !a.animators[key]) return;
		if (typeof a.removeAnimator === 'function') {
			try {
				a.removeAnimator(key);
				if (!a.animators[key]) return;
			} catch (e) {}
		}
		delete a.animators[key];
	}-*/;

	/** the group with this uuid (Group.all), or null */
	public static native Group groupByUuid(String uuid) /*-{
		var all = $wnd.Group && $wnd.Group.all;
		if (!all) return null;
		for (var i = 0; i < all.length; i++) if (all[i] && all[i].uuid === uuid) return all[i];
		return null;
	}-*/;

	/** the animation (Animation.all) with this uuid, or null */
	public static native Animation animationByUuid(String uuid) /*-{
		var all = $wnd.Animation && $wnd.Animation.all;
		if (!all) return null;
		for (var i = 0; i < all.length; i++) if (all[i] && all[i].uuid === uuid) return all[i];
		return null;
	}-*/;

	/** names of the animations of a Bedrock / GeckoLib file (keys of json.animations, in file order) */
	public static native String[] animationNames(Object json) /*-{
		var out = [];
		var a = json && json.animations;
		if (a && typeof a === 'object') for (var k in a) if (Object.prototype.hasOwnProperty.call(a, k)) out.push(k);
		return out;
	}-*/;

	/** bone names of one animation of the file (in file order) */
	public static native String[] boneNames(Object json, String anim) /*-{
		var out = [];
		var a = json && json.animations && json.animations[anim];
		var b = a && a.bones;
		if (b && typeof b === 'object') for (var k in b) if (Object.prototype.hasOwnProperty.call(b, k)) out.push(k);
		return out;
	}-*/;

	/** true when a bone of the file has a Molang (text) value anywhere in its channels */
	public static native boolean boneHasMolang(Object json, String anim, String bone) /*-{
		var a = json && json.animations && json.animations[anim];
		var b = a && a.bones && a.bones[bone];
		var found = false;
		var walk = function(v) {
			if (found || v === null || v === undefined) return;
			if (typeof v === 'string') {
				if (isNaN(parseFloat(v)) || !isFinite(v)) found = true;
				return;
			}
			if (typeof v === 'object') for (var k in v) if (Object.prototype.hasOwnProperty.call(v, k)) walk(v[k]);
		};
		walk(b);
		return found;
	}-*/;

	/** 'loop' / 'hold' / 'once' of an animation of the file (Bedrock 'loop': true, 'hold_on_last_frame', absent) */
	public static native String fileLoop(Object json, String anim) /*-{
		var a = json && json.animations && json.animations[anim];
		if (!a) return 'once';
		if (a.loop === 'hold_on_last_frame') return 'hold';
		if (a.loop === true || a.loop === 'true') return 'loop';
		return 'once';
	}-*/;

	/** true when an animation of the file has sound / particle / timeline effects (CPM has none of them) */
	public static native boolean hasEffects(Object json, String anim) /*-{
		var a = json && json.animations && json.animations[anim];
		return !!(a && (a.sound_effects || a.particle_effects || a.timeline));
	}-*/;

	/**
	 * Rewrites the file for the loader: keeps only the chosen animations; per animation keeps only the mapped bones,
	 * renamed to their temporary target names (map: bone name -&gt; temporary group name); drops sound / particle /
	 * timeline effects. The input is not changed.
	 */
	public static native Object rewrite(Object json, String[] anims, Object map) /*-{
		var src = json.animations || {};
		var out = {};
		for (var k in json) if (Object.prototype.hasOwnProperty.call(json, k) && k !== 'animations' && k !== 'animation_controllers') out[k] = json[k];
		out.animations = {};
		for (var i = 0; i < anims.length; i++) {
			var name = anims[i];
			var a = src[name];
			if (!a) continue;
			var copy = JSON.parse(JSON.stringify(a));
			delete copy.sound_effects;
			delete copy.particle_effects;
			delete copy.timeline;
			var bones = {};
			if (copy.bones) for (var b in copy.bones) {
				if (!Object.prototype.hasOwnProperty.call(copy.bones, b)) continue;
				var t = map[b];
				if (typeof t === 'string' && t) bones[t] = copy.bones[b];
			}
			copy.bones = bones;
			out.animations[name] = copy;
		}
		return out;
	}-*/;

	/** {key: value} JS map */
	public static native Object newMap() /*-{
		return {};
	}-*/;

	public static native void put(Object map, String key, String value) /*-{
		map[key] = value;
	}-*/;

	/** Interface.Panels.variable_placeholders text (Blockbench adds Molang query sliders on import), or null */
	public static native String placeholderText() /*-{
		try {
			var p = $wnd.Interface && $wnd.Interface.Panels && $wnd.Interface.Panels.variable_placeholders;
			var v = p && p.inside_vue;
			return v && typeof v.text === 'string' ? v.text : null;
		} catch (e) {
			return null;
		}
	}-*/;
}
