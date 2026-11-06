package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.Locale;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.ee.EmbeddedEditorHandler;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;

import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Testar no editor do CPM': opens the project in CPM's embedded editor (as 'Ver no Editor Embutido CPM' does)
 * and goes straight to Animation &gt; Test with Minecraft's own animations on and the selected animation's pose /
 * gesture chosen, so it plays exactly like in game (walk swing, look, held items...). Layers and value layers are
 * switched on in the Test panel itself (the toast says so).
 */
public final class TestInCpm {
	private TestInCpm() {
	}

	/** {pose (vanilla enum name or custom pose name), gesture (button id)} for an animation; nulls when none */
	static String[] target(Animation a) {
		String[] out = new String[2];
		if (a == null || a.type == null)return out;
		String name = a.name != null ? a.name : "";
		// an entry / exit animation tests the animation it belongs to
		if (name.length() > 2 && name.charAt(1) == ':') {
			String rest = name.substring(2);
			switch (Character.toLowerCase(name.charAt(0))) {
			case 'p':
				out[0] = rest.toUpperCase(Locale.ROOT);
				return out;
			case 'c':
				out[0] = rest;
				return out;
			case 'g':
				out[1] = rest;
				return out;
			default:
			}
		}
		Pair<AnimationType, VanillaPose> tp;
		try {
			tp = Animation.parseType(a.type);
		} catch (Throwable e) {
			return out;
		}
		switch (tp.getKey()) {
		case POSE:
			if (tp.getValue() != null && tp.getValue() != VanillaPose.GLOBAL)out[0] = tp.getValue().name();
			break;
		case CUSTOM_POSE:
			out[0] = name;
			break;
		case GESTURE:
			out[1] = Transitions.idOf(name);
			break;
		default:
		}
		return out;
	}

	/** CPM+ &gt; Animação &gt; 'Testar no editor do CPM (aba Testar)' */
	public static void open() {
		Animation a = Animation.selected;
		String[] tg = target(a);
		ProjectConvert.convertToCPM().then(b -> b.arrayBuffer()).then(dt -> {
			return EmbeddedEditorHandler.open().then(ee -> ee.openProject(dt).then(v -> {
				ee.openTest(tg[0], tg[1]);
				return null;
			}));
		}).catch_(ex -> {
			ProjectConvert.reportExportError(ex);
			return null;
		});
		String what = tg[0] != null ? tg[0] : tg[1] != null ? tg[1] : null;
		PlusDialogs.toast(what != null ? t("cpmplus.w3animtools.testcpm.guide", what) : t("cpmplus.w3animtools.testcpm.guideLayer"), "play_circle", 9000, null);
	}

	/** testInCpm({anim}) -&gt; {pose, gesture} (does not open anything) */
	public static Object hook(JsPropertyMap<Object> opts) {
		String[] tg = target(AnimToolSupport.anim(opts));
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("pose", tg[0]);
		r.set("gesture", tg[1]);
		return r;
	}
}
