package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;

import elemental2.core.Global;
import elemental2.core.JsArray;
import elemental2.core.JsObject;
import jsinterop.base.Js;
import jsinterop.base.JsArrayLike;
import jsinterop.base.JsPropertyMap;

/**
 * JSON helpers for the cpm_data blobs stored in .bbmodel files (project, group and cube 'cpm_data').
 *
 * CPM+ (G16): values are converted between JS and Java by hand instead of through the GWT Gson
 * replacement, so that
 *  - a JSON null already in a file is read and kept (the Gson replacement threw on null, in both directions;
 *    this build never WRITES a new null: the extras API refuses it, see {@link #checkExtraValue}),
 *  - key order is kept (LinkedHashMap), so an unchanged blob is written back byte for byte,
 *  - numbers stay JS numbers (1 stays "1", 0.5 stays "0.5").
 * Maps are Map&lt;String,Object&gt;, arrays List&lt;Object&gt;, numbers Double, plus String, Boolean and null:
 * the same shapes JsonMapImpl / the CPM loaders expect.
 */
public class JsonUtil {

	public static String toJson(Map<String, Object> from) {
		return stringify(from);
	}

	public static JsonMap fromJson(String text) {
		Object o = parse(text);
		if (!(o instanceof Map))throw new IllegalArgumentException("cpm_data is not a JSON object");
		return new JsonMapImpl(asMap(o));
	}

	/** JSON text -> Java value (LinkedHashMap / ArrayList / Double / String / Boolean / null) */
	public static Object parse(String text) {
		PlusTestHooks.count("jsonParses");
		return fromJs(Global.JSON.parse(text));
	}

	/** Java value -> compact JSON text (same output as JSON.stringify) */
	public static String stringify(Object o) {
		return Global.JSON.stringify(toJs(o));
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : null;
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object o) {
		return o instanceof List ? (List<Object>) o : null;
	}

	/** JS value (from JSON.parse or a JS object literal) -> Java value */
	public static Object fromJs(Object obj) {
		if (obj == null)return null;
		String t = Js.typeof(obj);
		if ("undefined".equals(t))return null;
		if (JsArray.isArray(obj)) {
			JsArrayLike<Object> array = Js.asArrayLike(obj);
			int l = array.getLength();
			List<Object> list = new ArrayList<>(l);
			for (int i = 0; i < l; i++)list.add(fromJs(array.getAt(i)));
			return list;
		}
		if ("object".equals(t)) {
			Map<String, Object> map = new LinkedHashMap<>();
			JsPropertyMap<Object> in = Js.uncheckedCast(obj);
			for (String key : JsObject.keys(obj).asList())map.put(key, fromJs(in.get(key)));
			return map;
		}
		if ("number".equals(t))return Double.valueOf(Js.asDouble(obj));
		if ("string".equals(t))return Js.asString(obj);
		if ("boolean".equals(t))return Boolean.valueOf(Js.asBoolean(obj));
		throw new IllegalArgumentException("Unsupported JSON value of type " + t);
	}

	/** Java value -> JS value (plain objects / arrays), null-safe */
	@SuppressWarnings("unchecked")
	public static Object toJs(Object obj) {
		if (obj == null)return null;
		if (obj instanceof Map) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			for (Map.Entry<String, Object> e : ((Map<String, Object>) obj).entrySet())m.set(e.getKey(), toJs(e.getValue()));
			return m;
		}
		if (obj instanceof List) {
			JsArray<Object> a = new JsArray<>();
			List<Object> l = (List<Object>) obj;
			for (int i = 0; i < l.size(); i++)a.setAt(i, toJs(l.get(i)));
			return a;
		}
		if (obj instanceof Number)return Js.asAny(((Number) obj).doubleValue());
		if (obj instanceof String || obj instanceof Boolean)return obj;
		throw new IllegalArgumentException("Unsupported value in cpm_data: " + obj.getClass().getName());
	}

	/** Deep copy of a Java JSON value (maps keep their key order) */
	@SuppressWarnings("unchecked")
	public static Object deepCopy(Object o) {
		if (o instanceof Map) {
			Map<String, Object> m = new LinkedHashMap<>();
			((Map<String, Object>) o).forEach((k, v) -> m.put(k, deepCopy(v)));
			return m;
		}
		if (o instanceof List) {
			List<Object> l = new ArrayList<>();
			for (Object v : (List<Object>) o)l.add(deepCopy(v));
			return l;
		}
		return o;// immutable: String, Number, Boolean, null
	}

	/** Deep copy of a map (null -> new empty map) */
	public static Map<String, Object> copyMap(Object o) {
		Map<String, Object> m = asMap(deepCopy(o));
		return m != null ? m : new LinkedHashMap<>();
	}

	/**
	 * Merge used by the preserving flush (G16): a copy of {@code current}, plus every key of {@code base} (a map)
	 * that {@code current} does not have - recursively: when both sides hold a map under the same key, that map is
	 * merged the same way (e.g. desc.look.w or left_hand.position.w written by a newer CPM+ survive). What the
	 * plugin wrote is authoritative (scalars and lists of {@code current} win), keys it does not know are kept.
	 * Use it only for maps whose known keys this build always writes (a known key left out of {@code current}
	 * would come back from {@code base}). Not a map -> copy of current.
	 */
	public static Map<String, Object> keepUnknownKeys(Object base, Map<String, Object> current) {
		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Object> b = asMap(base);
		if (b != null) {
			// base order first (so an unchanged blob keeps its key order), current values win
			for (Map.Entry<String, Object> e : b.entrySet()) {
				if (current.containsKey(e.getKey())) {
					Object cv = current.get(e.getKey());
					Map<String, Object> cm = asMap(cv);
					if (cm != null && asMap(e.getValue()) != null)out.put(e.getKey(), keepUnknownKeys(e.getValue(), cm));
					else out.put(e.getKey(), deepCopy(cv));
				} else out.put(e.getKey(), deepCopy(e.getValue()));
			}
		}
		for (Map.Entry<String, Object> e : current.entrySet()) {
			if (!out.containsKey(e.getKey()))out.put(e.getKey(), deepCopy(e.getValue()));
		}
		return out;
	}

	/**
	 * Checks a value given to the G16 extras API (putExtra / putDescExtra): JSON shapes only (Map with String
	 * keys, List, Number, String, Boolean) and NO null at any depth - the official CPM plugin and CPM+ 0.7 read
	 * cpm_data with a JSON reader that crashes on null, so one null would make the whole file unreadable there.
	 * @throws IllegalArgumentException naming the path of the bad value
	 */
	public static void checkExtraValue(String key, Object value) {
		checkExtraValue0(key, value);
	}

	@SuppressWarnings("unchecked")
	private static void checkExtraValue0(String path, Object v) {
		if (v == null)throw new IllegalArgumentException("CPM+ extra '" + path + "': null is not allowed (older CPM plugins cannot read null in cpm_data); remove the key instead");
		if (v instanceof Map) {
			for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet())checkExtraValue0(path + "." + e.getKey(), e.getValue());
			return;
		}
		if (v instanceof List) {
			List<Object> l = (List<Object>) v;
			for (int i = 0; i < l.size(); i++)checkExtraValue0(path + "[" + i + "]", l.get(i));
			return;
		}
		if (v instanceof Number || v instanceof String || v instanceof Boolean)return;
		throw new IllegalArgumentException("CPM+ extra '" + path + "': unsupported value " + v.getClass().getName());
	}
}
