package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.shared.network.ServerCaps;
import com.tom.cpm.shared.util.ScalingOptions;

/**
 * CPM+ (W3-SETTINGS, G44): the '/cpm scaling' commands a server admin has to run so the scaling values of a model
 * work on that server. Pure Java (no Blockbench, no I18n): unit-tested with javac against the game's own
 * ScalingOptions.
 * <p>
 * Syntax (CustomPlayerModels shared/CommandCPM.java buildScaling): {@code /cpm scaling <option> enabled [<player>]
 * <true|false>}, {@code /cpm scaling <option> limit [<player>] [<min>] <max>}, {@code /cpm scaling all enabled
 * [<player>] <bool>}, {@code /cpm scaling debug <player>}. The option id is the enum name in lower case
 * ({@code name().toLowerCase(Locale.ROOT)}), never the network key ('scale', 'hitboxW' ...).
 * <ul>
 * <li>values 0 and 1 mean 'unset' (PlayerData.rescale treats 0 as 1): skipped;</li>
 * <li>options enabled by default on every server (getDefualtEnabled: third person distance, view bobbing) never
 * get a command;</li>
 * <li>a global 'limit' is never generated (it would narrow the range for every player on the server): limit lines
 * are always per player, min = min(1, v), max = max(1, v), clamped to the option's own range;</li>
 * <li>numbers always use '.' as the decimal separator (a pt-BR comma breaks the command);</li>
 * <li>no 'method' commands.</li>
 * </ul>
 */
public final class ServerCommands {
	/** placeholder used when no player name was typed */
	public static final String NICK_PLACEHOLDER = "<nick>";
	/** the 'all enabled' shortcut is only offered from this many options on */
	public static final int ALL_SHORTCUT_MIN = 3;

	private ServerCommands() {
	}

	/** what kind of option (how the explanation groups it) */
	public static enum Kind {
		/** size, eye height, hitbox */
		SIZE,
		/** attributes (health, reach, speed ...) */
		ATTRIBUTE,
		/** enabled by default on every server (no command needed) */
		FREE
	}

	/** one scaling value of the model that differs from 'normal' */
	public static final class Item {
		public final ScalingOptions option;
		/** command id: enum name in lower case */
		public final String id;
		/** the value, clamped to the option's range */
		public final float value;
		public final Kind kind;

		Item(ScalingOptions option, float value) {
			this.option = option;
			this.id = id(option);
			this.value = value;
			this.kind = kindOf(option);
		}

		/** true when the server must enable this option (not FREE) */
		public boolean needsServer() {
			return kind != Kind.FREE;
		}
	}

	/** command id of an option: the enum name in lower case (never the network key) */
	public static String id(ScalingOptions o) {
		return o.name().toLowerCase(Locale.ROOT);
	}

	public static Kind kindOf(ScalingOptions o) {
		if(o.getDefualtEnabled())return Kind.FREE;
		ServerCaps c = o.getCaps();
		if(c == ServerCaps.SCALING || c == ServerCaps.EYE_HEIGHT || c == ServerCaps.HITBOX_SCALING)return Kind.SIZE;
		return Kind.ATTRIBUTE;
	}

	/** true for 0 / 1 / null ('unset' in CPM) */
	public static boolean isUnset(Float v) {
		return v == null || v.isNaN() || Math.abs(v) < 1e-6f || Math.abs(v - 1f) < 1e-6f;
	}

	/** v clamped to the option's own range (getMin .. getMax) */
	public static float clamp(ScalingOptions o, float v) {
		if(v < o.getMin())return o.getMin();
		if(v > o.getMax())return o.getMax();
		return v;
	}

	/** the values that differ from normal, in enum order, clamped to each option's range */
	public static List<Item> items(Map<ScalingOptions, Float> values) {
		List<Item> out = new ArrayList<>();
		if(values == null)return out;
		for(ScalingOptions o : ScalingOptions.values()) {
			Float v = values.get(o);
			if(isUnset(v))continue;
			out.add(new Item(o, clamp(o, v)));
		}
		return out;
	}

	/** the items a server must enable (everything that is not enabled by default) */
	public static List<Item> needingServer(List<Item> items) {
		List<Item> out = new ArrayList<>();
		for(Item i : items)if(i.needsServer())out.add(i);
		return out;
	}

	/** how many values of the model are blocked on a server that has not enabled them */
	public static int countBlocked(Map<ScalingOptions, Float> values) {
		return needingServer(items(values)).size();
	}

	/** decimal with '.', at most 3 decimals, no trailing zeros (0.5, 2, 1.25) - never a locale comma */
	public static String num(float v) {
		long scaled = Math.round((double) v * 1000.0);
		boolean neg = scaled < 0;
		if(neg)scaled = -scaled;
		long ip = scaled / 1000;
		long fp = scaled % 1000;
		StringBuilder sb = new StringBuilder();
		if(neg && scaled != 0)sb.append('-');
		sb.append(ip);
		if(fp != 0) {
			String f = String.valueOf(fp);
			while(f.length() < 3)f = "0" + f;
			while(f.endsWith("0"))f = f.substring(0, f.length() - 1);
			sb.append('.').append(f);
		}
		return sb.toString();
	}

	private static String nickOrPlaceholder(String nick) {
		String n = nick != null ? nick.trim() : "";
		return n.isEmpty() ? NICK_PLACEHOLDER : n;
	}

	private static boolean hasNick(String nick) {
		return nick != null && !nick.trim().isEmpty();
	}

	/** '/cpm scaling &lt;id&gt; enabled true' (global) or '/cpm scaling &lt;id&gt; enabled &lt;nick&gt; true' (nick BEFORE the boolean) */
	public static String enableCommand(Item i, String nick) {
		if(hasNick(nick))return "/cpm scaling " + i.id + " enabled " + nick.trim() + " true";
		return "/cpm scaling " + i.id + " enabled true";
	}

	/**
	 * '/cpm scaling &lt;id&gt; limit &lt;nick&gt; &lt;min&gt; &lt;max&gt;' with min = min(1, v), max = max(1, v), clamped to the
	 * option's range. Always for one player (a global limit would narrow the range for everybody): an empty nick
	 * gives the '&lt;nick&gt;' placeholder.
	 */
	public static String limitCommand(Item i, String nick) {
		float min = clamp(i.option, Math.min(1f, i.value));
		float max = clamp(i.option, Math.max(1f, i.value));
		return "/cpm scaling " + i.id + " limit " + nickOrPlaceholder(nick) + " " + num(min) + " " + num(max);
	}

	/** '/cpm scaling all enabled [&lt;nick&gt;] true' */
	public static String allEnabledCommand(String nick) {
		if(hasNick(nick))return "/cpm scaling all enabled " + nick.trim() + " true";
		return "/cpm scaling all enabled true";
	}

	/** '/cpm scaling debug &lt;nick&gt;' (shows enabled / disabled / unavailable per option for that player) */
	public static String debugCommand(String nick) {
		return "/cpm scaling debug " + nickOrPlaceholder(nick);
	}

	/** true when the 'all enabled' shortcut may be offered (3 or more options need the server) */
	public static boolean allShortcutAllowed(List<Item> items) {
		return needingServer(items).size() >= ALL_SHORTCUT_MIN;
	}

	/**
	 * The command lines for the staff: one 'enabled' line per option that needs the server (or the 'all enabled'
	 * shortcut when useAll and allowed), optional per-player 'limit' lines, always ending with the 'debug' line.
	 * nick null / empty = the commands for every player ('enabled' global, limit / debug with '&lt;nick&gt;').
	 * Empty list when nothing needs the server.
	 */
	public static List<String> commands(List<Item> items, String nick, boolean useAll, boolean withLimits) {
		List<Item> need = needingServer(items);
		if(need.isEmpty())return Collections.emptyList();
		List<String> out = new ArrayList<>();
		if(useAll && need.size() >= ALL_SHORTCUT_MIN) {
			out.add(allEnabledCommand(nick));
		} else {
			for(Item i : need)out.add(enableCommand(i, nick));
		}
		if(withLimits) {
			for(Item i : need)out.add(limitCommand(i, nick));
		}
		out.add(debugCommand(nick));
		return out;
	}

	/** commands joined with '\n' */
	public static String commandsText(Map<ScalingOptions, Float> values, String nick, boolean useAll, boolean withLimits) {
		return String.join("\n", commands(items(values), nick, useAll, withLimits));
	}

	/**
	 * The scaling part of a shared 'Comandos pro staff' panel (E2): the exact list for these values, for one player
	 * (nick) or everybody (null). Same as {@link #commands(List, String, boolean, boolean)} without shortcut / limits.
	 */
	public static List<String> staffSection(Map<ScalingOptions, Float> values, String nick) {
		return commands(items(values), nick, false, false);
	}
}
