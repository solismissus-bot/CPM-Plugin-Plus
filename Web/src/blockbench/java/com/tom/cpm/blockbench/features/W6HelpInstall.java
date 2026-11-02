package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.ext.W6HelpNatives;
import com.tom.cpm.blockbench.tools.view.Skeleton;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of G50 'Ver esqueleto' (CPM+ v0.8): toggle + options in CPM+ &gt; Prévia. Only a view: nothing is
 * saved in the project and nothing changes in the export. Test tool 'w6help.skeleton' in test mode.
 */
public final class W6HelpInstall {
	private W6HelpInstall() {
	}

	public static void install() {
		W6HelpNatives.ensureStyle();
		Skeleton.install();
		if (PlusTestHooks.active())registerTestTools();
	}

	public static void uninstall() {
		Skeleton.uninstall();
		W6HelpNatives.removeStyle();
	}

	private static String str(JsPropertyMap<Object> o, String k, String def) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : def;
	}

	private static void registerTestTools() {
		PlusTestHooks.register("w6help.skeleton", o -> {
			String op = str(o, "op", "state");
			switch (op) {
			case "on":
				Skeleton.setOn(true);
				break;
			case "off":
				Skeleton.setOn(false);
				break;
			case "rebuild":
				Skeleton.rebuild();
				break;
			default:
				break;
			}
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("ok", true);
			r.set("changed", false);
			r.set("warnings", new JsArray<>());
			r.set("errors", new JsArray<>());
			r.set("state", Skeleton.state());
			r.set("isOn", Skeleton.isOn());
			r.set("rebuilds", (double) Skeleton.rebuilds());
			r.set("labelDom", (double) Skeleton.labelDomCount());
			return r;
		});
	}
}
