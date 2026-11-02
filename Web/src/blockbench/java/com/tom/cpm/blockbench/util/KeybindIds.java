package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.util.ButtonIdCheck.Button;
import com.tom.cpm.blockbench.util.ButtonIdCheck.ButtonType;

/**
 * CPM+ (W5-CMDS, G11): the keys under which CPM in game remembers a player's choices for a model, derived from the
 * same replay of the exporter as {@link ButtonIdCheck} (never from a separate copy of the naming rules). Pure Java.
 * <ul>
 * <li>keybinds: {@code getKeybindId()} = 'p' + name (custom pose), 'g' + name (gesture), 'l' + name (toggle),
 * 'd' + name (dropdown / layer group); a slider has no keybind;</li>
 * <li>saved values ('Salvar entre partidas', needs the model ID): toggle / slider / dropdown store their value under
 * the button name; a dropdown stores the NAME of the chosen option, so renaming an option loses it too.</li>
 * </ul>
 * Entries: {@code {k: key, t: type letter (p g l d v o), n: name, g: group (options), prop: isProperty}}; option
 * keys are 'o' + group + '/' + option. Stored after every successful export as the project extra
 * {@value #EXTRA_KEY}; the next export compares.
 */
public final class KeybindIds {
	public static final String EXTRA_KEY = "lastExportedKeys";

	private KeybindIds() {
	}

	public static final class Entry {
		public final String key, type, name, group;
		public final boolean isProperty;

		public Entry(String key, String type, String name, String group, boolean isProperty) {
			this.key = key;
			this.type = type;
			this.name = name;
			this.group = group;
			this.isProperty = isProperty;
		}

		public Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("k", key);
			m.put("t", type);
			m.put("n", name);
			if (group != null)m.put("g", group);
			if (isProperty)m.put("prop", true);
			return m;
		}

		public static Entry fromMap(Object o) {
			if (!(o instanceof Map))return null;
			Map<?, ?> m = (Map<?, ?>) o;
			Object k = m.get("k"), t = m.get("t"), n = m.get("n"), g = m.get("g"), p = m.get("prop");
			if (!(k instanceof String) || !(t instanceof String) || !(n instanceof String))return null;
			return new Entry((String) k, (String) t, (String) n, g instanceof String ? (String) g : null, Boolean.TRUE.equals(p));
		}
	}

	/** one probable rename: a key that disappeared while a new one of the same type appeared */
	public static final class Rename {
		public final Entry before, after;

		Rename(Entry before, Entry after) {
			this.before = before;
			this.after = after;
		}
	}

	/** the keys of the compiled buttons (wheel order) */
	public static List<Entry> keysOf(ButtonIdCheck.Result r) {
		List<Entry> l = new ArrayList<>();
		for (Button b : r.sorted()) {
			l.add(new Entry(b.keyPrefix() + b.name, b.keyPrefix(), b.name, null, b.isProperty));
			if (b.type == ButtonType.DROPDOWN) {
				for (String o : b.options)l.add(new Entry("o" + b.name + "/" + o, "o", o, b.name, b.isProperty));
			}
		}
		return l;
	}

	public static List<Object> toJson(List<Entry> l) {
		List<Object> out = new ArrayList<>();
		for (Entry e : l)out.add(e.toMap());
		return out;
	}

	public static List<Entry> fromJson(Object o) {
		List<Entry> out = new ArrayList<>();
		if (o instanceof List) {
			for (Object x : (List<?>) o) {
				Entry e = Entry.fromMap(x);
				if (e != null)out.add(e);
			}
		}
		return out;
	}

	/**
	 * Keys that disappeared while a new key of the same type (options: in the same group) appeared, paired in order.
	 * Keybind types (p g l d) always count; saved values (v, o) only when the model has an ID and the value is saved
	 * ('Salvar entre partidas'), because only then the game remembers them.
	 */
	public static List<Rename> renames(List<Entry> before, List<Entry> now, boolean hasModelId) {
		List<Rename> out = new ArrayList<>();
		if (before == null || before.isEmpty() || now == null)return out;
		Map<String, Entry> nowKeys = new LinkedHashMap<>();
		for (Entry e : now)nowKeys.put(e.key, e);
		Map<String, Entry> oldKeys = new LinkedHashMap<>();
		for (Entry e : before)oldKeys.put(e.key, e);
		Map<String, List<Entry>> gone = new LinkedHashMap<>(), added = new LinkedHashMap<>();
		for (Entry e : before) {
			if (nowKeys.containsKey(e.key) || !counts(e, hasModelId))continue;
			gone.computeIfAbsent(slot(e), k -> new ArrayList<>()).add(e);
		}
		for (Entry e : now) {
			if (oldKeys.containsKey(e.key))continue;
			added.computeIfAbsent(slot(e), k -> new ArrayList<>()).add(e);
		}
		for (Map.Entry<String, List<Entry>> g : gone.entrySet()) {
			List<Entry> a = added.get(g.getKey());
			if (a == null)continue;
			for (int i = 0; i < g.getValue().size() && i < a.size(); i++)out.add(new Rename(g.getValue().get(i), a.get(i)));
		}
		return out;
	}

	private static boolean counts(Entry e, boolean hasModelId) {
		if ("p".equals(e.type) || "g".equals(e.type) || "l".equals(e.type) || "d".equals(e.type))return true;
		return hasModelId && e.isProperty;
	}

	private static String slot(Entry e) {
		return "o".equals(e.type) ? "o\u0000" + e.group : e.type;
	}
}
