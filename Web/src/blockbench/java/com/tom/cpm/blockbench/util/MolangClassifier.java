package com.tom.cpm.blockbench.util;

import java.util.Locale;

/**
 * CPM+ (G9): sorts the text of one keyframe data point (x / y / z of any channel).
 * <ul>
 * <li>{@link Kind#NUMBER}: a plain number (or empty text, which Blockbench reads as 0).</li>
 * <li>{@link Kind#FORMULA}: a Molang formula the export reproduces exactly: only numbers, operators, parentheses,
 * {@code math.*} (except random / die_roll), {@code query.anim_time} / {@code q.anim_time},
 * {@code query.life_time} / {@code q.life_time} (playhead seconds) / {@code query.time_stamp} / {@code q.time_stamp}
 * (playhead in ticks; Blockbench 5.2.2 derives both from Timeline.time, checked by the QA scenario), {@code this},
 * {@code true} / {@code false}. The export bakes it frame by frame, the same
 * values the preview shows at those times.</li>
 * <li>{@link Kind#RED}: something CPM can't reproduce in game: {@code math.random*}, {@code math.die_roll*},
 * {@code variable.*} / {@code v.*}, {@code temp.*} / {@code t.*}, {@code context.*} / {@code c.*}, any other
 * {@code query.*} / {@code q.*}, any other name, or text that can't be read. {@link Result#token} names the first
 * such token ('?' = unreadable text).</li>
 * </ul>
 * Pure Java (no Blockbench objects): unit-tested with javac.
 */
public final class MolangClassifier {

	private MolangClassifier() {
	}

	public static enum Kind {
		NUMBER, FORMULA, RED
	}

	public static final class Result {
		public final Kind kind;
		/** RED: the first token the game can't reproduce ('?' = text that can't be read); otherwise null */
		public final String token;

		private Result(Kind kind, String token) {
			this.kind = kind;
			this.token = token;
		}

		/** FORMULA or RED: the export samples it frame by frame */
		public boolean isFormula() {
			return kind != Kind.NUMBER;
		}

		@Override
		public String toString() {
			return token == null ? kind.name() : kind.name() + "(" + token + ")";
		}
	}

	public static final Result NUMBER = new Result(Kind.NUMBER, null);
	private static final Result FORMULA = new Result(Kind.FORMULA, null);

	/** true when the trimmed text is a plain finite number, or empty / null */
	public static boolean isNumber(String s) {
		if (s == null)return true;
		String t = s.trim();
		if (t.isEmpty())return true;
		int i = 0, n = t.length();
		if (t.charAt(0) == '+' || t.charAt(0) == '-')i++;
		int digits = 0;
		boolean dot = false;
		for (; i < n; i++) {
			char c = t.charAt(i);
			if (c >= '0' && c <= '9')digits++;
			else if (c == '.' && !dot)dot = true;
			else break;
		}
		if (digits == 0)return false;
		if (i < n && (t.charAt(i) == 'e' || t.charAt(i) == 'E')) {
			i++;
			if (i < n && (t.charAt(i) == '+' || t.charAt(i) == '-'))i++;
			int ed = 0;
			for (; i < n && t.charAt(i) >= '0' && t.charAt(i) <= '9'; i++)ed++;
			if (ed == 0)return false;
		}
		return i == n;
	}

	/** classifies one data point text (null / empty = NUMBER) */
	public static Result classify(String text) {
		if (isNumber(text))return NUMBER;
		String s = text.trim();
		int n = s.length();
		int depth = 0;
		int i = 0;
		while (i < n) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if ((c >= '0' && c <= '9') || (c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)))) {
				while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.'))i++;
				continue;
			}
			if (Character.isLetter(c) || c == '_') {
				int st = i;
				while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.'))i++;
				String bad = redToken(s.substring(st, i));
				if (bad != null)return new Result(Kind.RED, bad);
				continue;
			}
			if (c == '\'') {
				int end = s.indexOf('\'', i + 1);
				if (end < 0)return new Result(Kind.RED, "?");
				i = end + 1;
				continue;
			}
			if (c == '(' || c == '[' || c == '{') {
				depth++;
				i++;
				continue;
			}
			if (c == ')' || c == ']' || c == '}') {
				depth--;
				if (depth < 0)return new Result(Kind.RED, "?");
				i++;
				continue;
			}
			if ("+-*/%<>=!&|?:,;".indexOf(c) >= 0) {
				i++;
				continue;
			}
			return new Result(Kind.RED, "?");
		}
		if (depth != 0)return new Result(Kind.RED, "?");
		return FORMULA;
	}

	/** null when the name is reproducible, else the name as written (lower case) */
	static String redToken(String id) {
		String l = id.toLowerCase(Locale.ROOT);
		while (l.endsWith("."))l = l.substring(0, l.length() - 1);
		switch (l) {
		case "this":
		case "true":
		case "false":
		case "return":
		case "query.anim_time":
		case "q.anim_time":
		case "query.life_time":
		case "q.life_time":
		case "query.time_stamp":
		case "q.time_stamp":
			return null;
		default:
			break;
		}
		if (l.startsWith("math.")) {
			String f = l.substring(5);
			if (f.isEmpty() || f.startsWith("random") || f.startsWith("die_roll"))return l;
			return null;
		}
		return l;
	}
}
