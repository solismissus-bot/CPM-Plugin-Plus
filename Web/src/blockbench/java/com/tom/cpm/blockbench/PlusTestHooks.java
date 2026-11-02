package com.tom.cpm.blockbench;

import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.ugwt.client.ExceptionUtil;

import elemental2.core.JsArray;
import elemental2.core.JsError;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.annotations.JsFunction;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W0B-INFRA / INFRA7): test hooks for the QA harness. Everything here is inert unless the page set
 * {@code window.__cpmplus_test_enable = true} before the plugin loaded (the harness does, users never do):
 *
 * <pre>
 * window.__cpmplus = {
 *   counters: {texDecodes, jsonParses, materialsCreated, materialsDisposed, ...},  // count(name)
 *   last: {...},          // setLast(key, json): last result of a tool / analysis
 *   tools: {name: fn(opts) -> Promise<json>},   // register(name, fn); tool cores return {ok, changed, warnings[], errors[]}
 *   installs: {W1IoInstall: 1, ...},            // feature installers that ran in this plugin instance
 *   inFlight: 0,          // long async plugin flows in progress (begin/end/track); the harness waits for 0
 *   info: {version, formatInt, pluginId}
 * }
 * </pre>
 *
 * {@link #fsRoot()} is the scratch folder the harness supplies ({@code window.__cpmplus_test_fsRoot}); every
 * filesystem write/detection must go there in test mode. The object is removed on unload. Each plugin instance
 * creates its own (Blockbench web starts a URL-loaded plugin twice; the first instance is unloaded).
 * {@code window.__cpmplus_test_log} (test mode only) keeps install/uninstall events across instances.
 */
public final class PlusTestHooks {
	private static JsPropertyMap<Object> root;

	private PlusTestHooks() {
	}

	@JsFunction
	public interface Tool {
		/** @return a JSON-like JS value (JsPropertyMap.of(), arrays, strings ...) or a Promise of one */
		Object run(JsPropertyMap<Object> opts);
	}

	/** true when the page enabled the test hooks before the plugin loaded */
	public static boolean enabled() {
		try {
			return Js.isTruthy(Js.asPropertyMap(DomGlobal.window).get("__cpmplus_test_enable"));
		} catch (Throwable e) {
			return false;
		}
	}

	/** Called first in PluginStart.onLoad: creates window.__cpmplus (test mode only). */
	public static void install() {
		if (!enabled())return;
		JsPropertyMap<Object> r = JsPropertyMap.of();
		JsPropertyMap<Object> counters = JsPropertyMap.of();
		for (String c : new String[] {"texDecodes", "jsonParses", "materialsCreated", "materialsDisposed"})counters.set(c, 0.0);
		r.set("counters", counters);
		r.set("last", JsPropertyMap.of());
		r.set("tools", JsPropertyMap.of());
		r.set("installs", JsPropertyMap.of());
		r.set("inFlight", 0.0);
		JsPropertyMap<Object> info = JsPropertyMap.of();
		info.set("version", System.getProperty("cpm.version"));
		info.set("pluginId", System.getProperty("cpm.pluginId"));
		info.set("formatInt", (double) PlusInfo.FORMAT_INT);
		info.set("minReader", (double) PlusInfo.MIN_READER);
		r.set("info", info);
		root = r;
		Js.asPropertyMap(DomGlobal.window).set("__cpmplus", r);
		log("hooks installed");
	}

	/** Called last in PluginStart.onUnload: removes window.__cpmplus if it still belongs to this instance. */
	public static void uninstall() {
		if (root == null)return;
		try {
			JsPropertyMap<Object> w = Js.asPropertyMap(DomGlobal.window);
			if (w.get("__cpmplus") == root)w.delete("__cpmplus");
		} catch (Throwable e) {
		}
		root = null;
		log("hooks removed");
	}

	public static boolean active() {
		return root != null;
	}

	/** counters[name] += 1 (no-op outside test mode) */
	public static void count(String name) {
		count(name, 1);
	}

	public static void count(String name, int n) {
		if (root == null)return;
		JsPropertyMap<Object> c = Js.uncheckedCast(root.get("counters"));
		Object v = c.get(name);
		double d = v != null && "number".equals(Js.typeof(v)) ? Js.asDouble(v) : 0;
		c.set(name, d + n);
	}

	/** last[key] = value (a JS value or a Java JSON value: Map/List/Double/String/Boolean) */
	public static void setLast(String key, Object value) {
		if (root == null)return;
		JsPropertyMap<Object> l = Js.uncheckedCast(root.get("last"));
		l.set(key, toJsSafe(value));
	}

	/** records that a feature installer ran in this instance (installs[name] += 1) and logs it */
	static void installed(String name) {
		if (root != null) {
			JsPropertyMap<Object> m = Js.uncheckedCast(root.get("installs"));
			Object v = m.get(name);
			m.set(name, (v != null ? Js.asDouble(v) : 0) + 1);
		}
		log("install " + name);
	}

	static void uninstalled(String name) {
		log("uninstall " + name);
	}

	/**
	 * Registers a test tool window.__cpmplus.tools[name](opts) -> Promise (test mode only). Exceptions become a
	 * rejected Promise with the Java stack trace in the message.
	 */
	public static void register(String name, Tool tool) {
		if (root == null)return;
		JsPropertyMap<Object> tools = Js.uncheckedCast(root.get("tools"));
		Tool wrapped = opts -> {
			try {
				Object r = tool.run(opts != null ? opts : JsPropertyMap.of());
				if (r instanceof Promise)return r;
				return Promise.resolve(r);
			} catch (Throwable e) {
				return Promise.reject(new JsError(name + ": " + ExceptionUtil.getStackTrace(e)));
			}
		};
		tools.set(name, wrapped);
	}

	/** Scratch folder supplied by the harness in test mode (every file write/detection goes there), else null. */
	public static String fsRoot() {
		if (!enabled())return null;
		try {
			Object v = Js.asPropertyMap(DomGlobal.window).get("__cpmplus_test_fsRoot");
			return v != null && "string".equals(Js.typeof(v)) && !((String) v).isEmpty() ? (String) v : null;
		} catch (Throwable e) {
			return null;
		}
	}

	/** Test mode only: {@code window.__cpmplus_test_failInstall} = installer name that must fail (QA of the load error message) */
	public static String failInstall() {
		if (!enabled())return null;
		try {
			Object v = Js.asPropertyMap(DomGlobal.window).get("__cpmplus_test_failInstall");
			return v != null && "string".equals(Js.typeof(v)) ? (String) v : null;
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * Test mode only: {@code window.__cpmplus_test_desktop = true} makes PlusDialogs treat the web app as the
	 * desktop app for its file APIs (writeFile / pickDirectory go to the harness' spies, confined to
	 * {@link #fsRoot()}), so a scenario can drive desktop-only flows; without it the web behaviour is tested.
	 */
	public static boolean simulateDesktop() {
		if (!enabled() || fsRoot() == null)return false;
		try {
			return Js.isTruthy(Js.asPropertyMap(DomGlobal.window).get("__cpmplus_test_desktop"));
		} catch (Throwable e) {
			return false;
		}
	}

	/** A long async flow started (export without a codec, analysis...): the harness waits until inFlight is 0. */
	public static void begin() {
		if (root == null)return;
		root.set("inFlight", Js.asDouble(root.get("inFlight")) + 1);
	}

	/** The flow ended (also on error). Never goes below 0. */
	public static void end() {
		if (root == null)return;
		double v = Js.asDouble(root.get("inFlight")) - 1;
		root.set("inFlight", v < 0 ? 0.0 : v);
	}

	/** begin() now, end() when the promise settles (resolved or rejected) */
	public static <T> Promise<T> track(Promise<T> p) {
		if (root == null || p == null)return p;
		begin();
		boolean[] done = new boolean[1];
		Runnable fin = () -> {
			if (!done[0]) {
				done[0] = true;
				end();
			}
		};
		p.then(v -> {
			fin.run();
			return null;
		}, e -> {
			fin.run();
			return null;
		});
		return p;
	}

	/** console.time('cpmplus:' + name) when the harness asked for timings (window.__cpmplus_timing) */
	public static void time(String name) {
		if (timing())DomGlobal.console.time("cpmplus:" + name);
	}

	public static void timeEnd(String name) {
		if (timing())DomGlobal.console.timeEnd("cpmplus:" + name);
	}

	private static boolean timing() {
		try {
			return Js.isTruthy(Js.asPropertyMap(DomGlobal.window).get("__cpmplus_timing"));
		} catch (Throwable e) {
			return false;
		}
	}

	/** test mode only: window.__cpmplus_test_log (survives plugin reloads) */
	public static void log(String event) {
		if (!enabled())return;
		try {
			JsPropertyMap<Object> w = Js.asPropertyMap(DomGlobal.window);
			Object l = w.get("__cpmplus_test_log");
			if (l == null || !JsArray.isArray(l)) {
				l = new JsArray<>();
				w.set("__cpmplus_test_log", l);
			}
			Js.<JsArray<Object>>uncheckedCast(l).push(event);
		} catch (Throwable e) {
		}
	}

	private static Object toJsSafe(Object v) {
		if (v == null)return null;
		if (v instanceof java.util.Map || v instanceof java.util.List)return JsonUtil.toJs(v);
		return v;
	}
}
