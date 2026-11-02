package com.tom.cpm.blockbench.util;

/**
 * CPM+ (W5-CMDS, E2 + G61): how an animation / button name has to be written as the 'animation' argument of
 * '/cpm animate', '/cpmclient animate' and '/cpm detect'. Pure Java (javac-testable, no Blockbench).
 * <p>
 * Rules verified in the CPM code:
 * <ul>
 * <li>Minecraft 1.13+ (Brigadier, {@code StringArgumentType.string()}): an unquoted word may only contain
 * {@code [0-9A-Za-z_.+-]}; anything else needs double quotes. Inside quotes Brigadier un-escapes {@code \"} and
 * {@code \\}.</li>
 * <li>Minecraft 1.12 and older, b1.7.3 and the Bukkit fallback (CPM's {@code StringCommandHandler}): the line is split
 * on single spaces; a word that STARTS with {@code "} is joined with the following words (one space each) until a word
 * ENDS with {@code "}, and the quotes are cut off. A backslash is kept literally, so escapes never work there. A quoted
 * ONE-word name swallows the next word (the value), so names without spaces are written without quotes there.</li>
 * </ul>
 * So: never escape, never quote a name that does not need it, and a name with {@code "} or {@code \}, two spaces in a
 * row or a space at the start / end can't be reached by a command at all (it still works in the gesture wheel). The
 * in-game lookup is a case-SENSITIVE HashMap: 'Feliz' and 'feliz' are different names.
 */
public final class CommandArg {
	/** how a name behaves in a command */
	public static enum Kind {
		/** works as it is */
		OK,
		/** works inside double quotes (Minecraft 1.13+ autocomplete does not add them) */
		NEEDS_QUOTES,
		/** no command can reach it (it still works in the gesture wheel) */
		UNUSABLE
	}

	private CommandArg() {
	}

	/** Brigadier's unquoted string characters */
	public static boolean isUnquotedChar(char c) {
		return c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_' || c == '.' || c == '+' || c == '-';
	}

	public static Kind classify(String name) {
		if (name == null || name.isEmpty())return Kind.UNUSABLE;
		if (name.indexOf('"') >= 0 || name.indexOf('\\') >= 0)return Kind.UNUSABLE;
		if (name.contains("  ") || name.charAt(0) == ' ' || name.charAt(name.length() - 1) == ' ')return Kind.UNUSABLE;
		boolean plain = true;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			// characters chat refuses (control chars, DEL, section sign) and other whitespace can't be typed
			if (c < 0x20 || c == 0x7f || c == 0xa7 || (c != ' ' && Character.isWhitespace(c)) || c == 0xa0)return Kind.UNUSABLE;
			if (!isUnquotedChar(c))plain = false;
		}
		return plain ? Kind.OK : Kind.NEEDS_QUOTES;
	}

	/**
	 * The argument for Minecraft 1.13+ (and, for names with spaces, also for 1.12-): the name as is when it only has
	 * {@code [0-9A-Za-z_.+-]}, otherwise in double quotes. Never a backslash escape. null for an UNUSABLE name.
	 */
	public static String commandArg(String name) {
		Kind k = classify(name);
		if (k == Kind.UNUSABLE)return null;
		return k == Kind.OK ? name : "\"" + name + "\"";
	}

	/**
	 * The argument for Minecraft 1.12 and older / the Bukkit fallback: quotes only when the name has a space (a quoted
	 * one-word name would swallow the value after it). null for an UNUSABLE name.
	 */
	public static String legacyArg(String name) {
		Kind k = classify(name);
		if (k == Kind.UNUSABLE)return null;
		return name.indexOf(' ') >= 0 ? "\"" + name + "\"" : name;
	}

	/** '/cpmclient animate NAME [value]' (Minecraft 1.13+ form; null for an UNUSABLE name) */
	public static String clientLine(String name, String value) {
		return line("/cpmclient animate ", null, commandArg(name), value);
	}

	/** '/cpm animate PLAYER NAME [value]' (server / command block; only for command-controlled animations) */
	public static String serverLine(String player, String name, String value) {
		return line("/cpm animate ", player, commandArg(name), value);
	}

	/** '/cpm detect PLAYER NAME [value]' */
	public static String detectLine(String player, String name, String value) {
		return line("/cpm detect ", player, commandArg(name), value);
	}

	/** the same lines for Minecraft 1.12 and older / Bukkit fallback (differs only when {@link #legacyDiffers}) */
	public static String legacyClientLine(String name, String value) {
		return line("/cpmclient animate ", null, legacyArg(name), value);
	}

	public static String legacyServerLine(String player, String name, String value) {
		return line("/cpm animate ", player, legacyArg(name), value);
	}

	public static String legacyDetectLine(String player, String name, String value) {
		return line("/cpm detect ", player, legacyArg(name), value);
	}

	private static String line(String cmd, String player, String arg, String value) {
		if (arg == null)return null;
		StringBuilder sb = new StringBuilder(cmd);
		if (player != null)sb.append(player).append(' ');
		sb.append(arg);
		if (value != null && !value.isEmpty())sb.append(' ').append(value);
		return sb.toString();
	}

	/** true when the 1.13+ and the 1.12- arguments differ (a one-word name that needs quotes on 1.13+) */
	public static boolean legacyDiffers(String name) {
		String a = commandArg(name), b = legacyArg(name);
		return a != null && !a.equals(b);
	}

	/**
	 * A name every command accepts unquoted: accents removed (decomposed letters keep their base letter), spaces become
	 * '_', every other character outside {@code [0-9A-Za-z_.+-]} is dropped. Only a suggestion: CPM+ never renames.
	 * Returns "nome" when nothing is left.
	 */
	public static String safeNameSuggestion(String name) {
		if (name == null)return "nome";
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == ' ' || c == '\t') {
				if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_')sb.append('_');
				continue;
			}
			if (isUnquotedChar(c)) {
				sb.append(c);
				continue;
			}
			String base = stripAccent(c);
			if (base != null)sb.append(base);
		}
		String s = sb.toString();
		while (s.startsWith("_"))s = s.substring(1);
		while (s.endsWith("_"))s = s.substring(0, s.length() - 1);
		return s.isEmpty() ? "nome" : s;
	}

	// precomposed Latin letters (Latin-1 Supplement + Latin Extended-A) and their base letters (canonical decomposition
	// without the combining mark; ligatures spelled out), so the result matches an NFD strip without java.text.Normalizer
	private static final String ACCENTED = "\u00c0\u00c1\u00c2\u00c3\u00c4\u00c5\u00e0\u00e1\u00e2\u00e3\u00e4\u00e5\u00c7\u00e7\u00c8\u00c9\u00ca\u00cb\u00e8\u00e9\u00ea\u00eb\u00cc\u00cd\u00ce\u00cf\u00ec\u00ed\u00ee\u00ef\u00d1\u00f1\u00d2\u00d3\u00d4\u00d5\u00d6\u00f2\u00f3\u00f4\u00f5\u00f6\u00d9\u00da\u00db\u00dc\u00f9\u00fa\u00fb\u00fc\u00dd\u00fd\u00ff"
			+ "\u0100\u0101\u0102\u0103\u0104\u0105\u0106\u0107\u0108\u0109\u010a\u010b\u010c\u010d\u010e\u010f\u0112\u0113\u0114\u0115\u0116\u0117\u0118\u0119\u011a\u011b\u011c\u011d\u011e\u011f\u0120\u0121\u0122\u0123\u0124\u0125\u0128\u0129\u012a\u012b\u012c\u012d\u012e\u012f\u0130\u0134\u0135\u0136\u0137\u0139\u013a\u013b\u013c\u013d\u013e\u0143\u0144\u0145\u0146\u0147\u0148\u014c\u014d\u014e\u014f\u0150\u0151\u0154\u0155\u0156\u0157\u0158\u0159\u015a\u015b\u015c\u015d\u015e\u015f\u0160\u0161\u0162\u0163\u0164\u0165\u0168\u0169\u016a\u016b\u016c\u016d\u016e\u016f\u0170\u0171\u0172\u0173\u0174\u0175\u0176\u0177\u0178\u0179\u017a\u017b\u017c\u017d\u017e";
	private static final String BASE = "AAAAAAaaaaaaCcEEEEeeeeIIIIiiiiNnOOOOOoooooUUUUuuuuYyy"
			+ "AaAaAaCcCcCcCcDdEeEeEeEeEeGgGgGgGgHhIiIiIiIiIJjKkLlLlLlNnNnNnOoOoOoRrRrRrSsSsSsSsTtTtUuUuUuUuUuUuWwYyYZzZzZz";

	private static String stripAccent(char c) {
		int i = ACCENTED.indexOf(c);
		if (i >= 0)return String.valueOf(BASE.charAt(i));
		switch (c) {
		case '\u00df': return "ss";
		case '\u00c6': return "AE";
		case '\u00e6': return "ae";
		case '\u0152': return "OE";
		case '\u0153': return "oe";
		case '\u00d8': return "O";
		case '\u00f8': return "o";
		case '\u0110': return "D";
		case '\u0111': return "d";
		case '\u0141': return "L";
		case '\u0142': return "l";
		case '\u00de': return "Th";
		case '\u00fe': return "th";
		case '\u00d0': return "D";
		case '\u00f0': return "d";
		default: return null;
		}
	}
}
