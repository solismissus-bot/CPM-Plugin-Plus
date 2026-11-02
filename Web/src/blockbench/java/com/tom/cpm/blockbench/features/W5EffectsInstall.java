package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.tools.effects.SolidColor;
import com.tom.cpm.blockbench.tools.view.PaintView;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W5-EFFECTS (CPM+ v0.8). Lite unit 'paint': G14 'Mostrar ao pintar' (paint under the
 * 2nd layer) and G55 'Virar cor sólida'. Each part installs on its own (one failing part never stops the other). In
 * test mode the 'w5effects.*' tools drive the features from the QA scenarios.
 */
public final class W5EffectsInstall {
	private W5EffectsInstall() {
	}

	public static void install() {
		Throwable first = null;
		for (Runnable r : new Runnable[] {SolidColor::install, PaintView::install}) {
			try {
				r.run();
			} catch (Throwable e) {
				if (first == null)first = e;
			}
		}
		if (PlusTestHooks.active())registerTestTools();
		if (first != null) {
			if (first instanceof RuntimeException)throw (RuntimeException) first;
			throw new RuntimeException(first);
		}
	}

	public static void uninstall() {
		for (Runnable r : new Runnable[] {PaintView::uninstall, SolidColor::uninstall}) {
			try {
				r.run();
			} catch (Throwable e) {
			}
		}
	}

	// ====================================================================== test tools (test mode only)

	private static List<String> strings(Object v) {
		List<String> out = new ArrayList<>();
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)out.add(String.valueOf(a.getAt(i)));
		}
		return out;
	}

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null ? String.valueOf(v) : def;
	}

	private static void registerTestTools() {
		// G14: paint view mode / state
		PlusTestHooks.register("w5effects.paint", o -> {
			String op = str(o, "op", "state");
			if ("mode".equals(op))PaintView.setMode(PaintView.parse(str(o, "mode", "all")));
			else if ("apply".equals(op))PaintView.apply();
			return PaintView.testState();
		});
		// G55: findSolidColor() / toSolidColor(uuids, color?) / dominant colour / export-warning fix
		PlusTestHooks.register("w5effects.solid", o -> {
			String op = str(o, "op", "find");
			if ("convert".equals(op)) {
				Object c = o.get("color");
				Integer color = c != null && "number".equals(Js.typeof(c)) ? (int) Js.asDouble(c) : null;
				return SolidColor.testConvert(strings(o.get("uuids")), color);
			}
			if ("fixChoices".equals(op)) {
				// the fixes a row 'invalidTexture' offers (built-in first); run=true runs ours
				WarnEntry w = new WarnEntry("qa").setKey("invalidTexture");
				List<WarnFixes.Choice> ch = WarnFixes.choicesFor(w);
				JsPropertyMap<Object> m = JsPropertyMap.of();
				JsArray<Object> labels = new JsArray<>();
				WarnEntry.FutureTask mine = null;
				for (WarnFixes.Choice c : ch) {
					labels.push(c.label);
					if (!c.builtIn)mine = c.task;
				}
				m.set("ok", true);
				m.set("labels", labels);
				if (!Js.isTruthy(o.get("run")) || mine == null)return m;
				return w.runFix(mine).then(ok -> {
					m.set("fixed", ok != null && ok);
					return Promise.resolve(m);
				});
			}
			if ("dominant".equals(op)) {
				JsPropertyMap<Object> m = JsPropertyMap.of();
				String uuid = str(o, "uuid", "");
				for (int i = 0; i < Cube.all.length; i++) {
					Cube cb = Cube.all.getAt(i);
					if (uuid.equals(cb.uuid))m.set("rgb", (double) SolidColor.dominantColorOr(cb, SolidColor.FALLBACK));
				}
				m.set("ok", true);
				return m;
			}
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("ok", true);
			m.set("cubes", SolidColor.testFind());
			return m;
		});
	}
}
