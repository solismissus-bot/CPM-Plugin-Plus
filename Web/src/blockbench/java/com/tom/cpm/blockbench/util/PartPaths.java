package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CPM+ (W2-ANIMCORE, D6): full outliner paths and the left/right name table, shared by every tool that pairs parts
 * (mirror tools, batch rename, texture mirror...).
 * <p>
 * Pure Java (javac-testable, qa/unit/w2animcore): the outliner is reached through a {@link Tree}. Blockbench's own
 * outliner tree is {@link AnimEdit#OUTLINER} (with shortcuts {@link AnimEdit#pathOf(Object)} /
 * {@link AnimEdit#findByPath(String)}).
 * <p>
 * Path = node names from the root group down to the node joined by '/'; a segment gets '[k]' (1-based, outliner
 * order) only when siblings share that exact name: {@code body/asa_esq/pena[2]}. Names ending in '_dup' (duplicated
 * vanilla roots) are kept as they are.
 */
public final class PartPaths {
	private PartPaths() {
	}

	/** the outliner seen as a tree (parent == null for top-level nodes) */
	public interface Tree<T> {
		String name(T node);

		T parent(T node);

		List<T> children(T node);

		/** top-level nodes in outliner order */
		List<T> roots();
	}

	private static <T> List<T> siblings(Tree<T> tree, T node) {
		T p = tree.parent(node);
		return p == null ? tree.roots() : tree.children(p);
	}

	/** 1-based index among siblings with exactly the same name (outliner order), 0 when the name is unique there */
	public static <T> int siblingIndex(Tree<T> tree, T node) {
		String n = tree.name(node);
		int idx = 0, count = 0;
		for (T s : siblings(tree, node)) {
			if (!n.equals(tree.name(s)))continue;
			count++;
			if (s == node)idx = count;
		}
		return count > 1 ? idx : 0;
	}

	/** path segment of a node: its name, plus '[k]' when a sibling has the same name */
	public static <T> String segment(Tree<T> tree, T node) {
		int k = siblingIndex(tree, node);
		String n = tree.name(node);
		return k > 0 ? n + "[" + k + "]" : n;
	}

	/** full path from the top-level group: 'body/asa_esq/pena[2]' */
	public static <T> String pathOf(Tree<T> tree, T node) {
		List<String> segs = new ArrayList<>();
		T n = node;
		int guard = 0;
		while (n != null && guard++ < 1000) {
			segs.add(0, segment(tree, n));
			n = tree.parent(n);
		}
		return String.join("/", segs);
	}

	/** splits a path into segments (a '/' inside a name is not supported by paths) */
	public static List<String> split(String path) {
		List<String> out = new ArrayList<>();
		if (path == null || path.isEmpty())return out;
		for (String s : path.split("/"))out.add(s);
		return out;
	}

	/** name part and index of a segment: 'pena[2]' -&gt; ('pena', 2); 'pena' -&gt; ('pena', 0) */
	static String segName(String seg) {
		int i = seg.lastIndexOf('[');
		if (i > 0 && seg.endsWith("]")) {
			String num = seg.substring(i + 1, seg.length() - 1);
			if (!num.isEmpty() && allDigits(num))return seg.substring(0, i);
		}
		return seg;
	}

	private static boolean allDigits(String s) {
		for (int i = 0; i < s.length(); i++)if (!Character.isDigit(s.charAt(i)))return false;
		return true;
	}

	static int segIndex(String seg) {
		String n = segName(seg);
		if (n.length() == seg.length())return 0;
		try {
			return Integer.parseInt(seg.substring(n.length() + 1, seg.length() - 1));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/** node at a full path, or null (also when the path is ambiguous) */
	public static <T> T findByPath(Tree<T> tree, String path) {
		List<String> segs = split(path);
		if (segs.isEmpty())return null;
		List<T> level = tree.roots();
		T found = null;
		for (String seg : segs) {
			String name = segName(seg);
			int idx = segIndex(seg);
			List<T> same = new ArrayList<>();
			for (T c : level) {
				if (name.equals(tree.name(c)))same.add(c);
			}
			if (idx == 0) {
				if (same.size() != 1)return null;// missing or ambiguous
				found = same.get(0);
			} else if (same.size() >= 2 && idx <= same.size()) {
				found = same.get(idx - 1);
			} else {
				// the name itself may end in '[k]'
				List<T> lit = new ArrayList<>();
				for (T c : level)if (seg.equals(tree.name(c)))lit.add(c);
				if (lit.size() != 1)return null;
				found = lit.get(0);
			}
			level = tree.children(found);
		}
		return found;
	}

	/**
	 * Every node a path can mean when segments without '[k]' are read loosely (all siblings with that name): used to
	 * tell "missing" (empty) from "ambiguous" (2+ nodes).
	 */
	public static <T> List<T> findAllByPath(Tree<T> tree, String path) {
		List<String> segs = split(path);
		List<T> cur = new ArrayList<>();
		if (segs.isEmpty())return cur;
		List<T> level = new ArrayList<>(tree.roots());
		for (int i = 0; i < segs.size(); i++) {
			String seg = segs.get(i);
			String name = segName(seg);
			int idx = segIndex(seg);
			List<T> next = new ArrayList<>();
			// level holds the candidates' children grouped per parent: index '[k]' counts inside one parent
			List<List<T>> groups = new ArrayList<>();
			if (i == 0)groups.add(level);
			else for (T p : cur)groups.add(tree.children(p));
			for (List<T> g : groups) {
				List<T> same = new ArrayList<>();
				for (T c : g)if (name.equals(tree.name(c)))same.add(c);
				if (idx == 0)next.addAll(same);
				else if (idx <= same.size())next.add(same.get(idx - 1));
				else for (T c : g)if (seg.equals(tree.name(c)))next.add(c);
			}
			cur = next;
			if (cur.isEmpty())break;
		}
		return cur;
	}

	// ====================================================================== left / right names

	/** the token pairs, in the order they are tested (whole tokens, compared ignoring case) */
	private static final String[][] PAIRS = {
		{"left", "right"},
		{"esquerda", "direita"},
		{"esquerdo", "direito"},
		{"esq", "dir"},
		{"l", "r"},
		{"e", "d"},
	};
	/** long words that are also swapped inside a longer token ('asaesquerda', 'leftwing') */
	private static final String[][] SUBSTRING_PAIRS = {
		{"esquerda", "direita"},
		{"esquerdo", "direito"},
	};

	private static boolean isSep(char c) {
		return c == '_' || c == '.' || c == '-' || c == ' ';
	}

	/** splits a name into tokens: separators [_ . - space] and camelCase boundaries; separators kept as own tokens */
	static List<String> tokens(String name) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (isSep(c)) {
				if (cur.length() > 0)out.add(cur.toString());
				cur.setLength(0);
				out.add(String.valueOf(c));
				continue;
			}
			if (cur.length() > 0) {
				char p = cur.charAt(cur.length() - 1);
				boolean boundary = (Character.isLowerCase(p) || Character.isDigit(p)) && Character.isUpperCase(c);
				// 'LArm' -> 'L' 'Arm': upper followed by upper+lower
				if (!boundary && Character.isUpperCase(p) && Character.isUpperCase(c) && i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1)))boundary = true;
				// 'arm2' / '2arm': digits are their own token
				if (!boundary && Character.isDigit(p) != Character.isDigit(c))boundary = true;
				if (boundary) {
					out.add(cur.toString());
					cur.setLength(0);
				}
			}
			cur.append(c);
		}
		if (cur.length() > 0)out.add(cur.toString());
		return out;
	}

	/** replacement with the case of the original: 'LEFT' -&gt; 'RIGHT', 'Left' -&gt; 'Right', 'left' -&gt; 'right' */
	static String withCase(String original, String repl) {
		if (original.length() > 0 && original.equals(original.toUpperCase(Locale.ROOT)) && !original.equals(original.toLowerCase(Locale.ROOT)))
			return repl.toUpperCase(Locale.ROOT);
		if (original.length() > 0 && Character.isUpperCase(original.charAt(0)))
			return Character.toUpperCase(repl.charAt(0)) + repl.substring(1);
		return repl;
	}

	private static String swapToken(String tok) {
		String low = tok.toLowerCase(Locale.ROOT);
		for (String[] p : PAIRS) {
			if (low.equals(p[0]))return withCase(tok, p[1]);
			if (low.equals(p[1]))return withCase(tok, p[0]);
		}
		// 'leftwing' / 'Rightarm': left/right as the start of a longer token
		for (String[] p : new String[][] {PAIRS[0]}) {
			for (int s = 0; s < 2; s++) {
				String a = p[s], b = p[1 - s];
				if (low.startsWith(a) && low.length() > a.length() && Character.isLetter(low.charAt(a.length())))
					return withCase(tok.substring(0, a.length()), b) + tok.substring(a.length());
			}
		}
		for (String[] p : SUBSTRING_PAIRS) {
			for (int s = 0; s < 2; s++) {
				String a = p[s], b = p[1 - s];
				int i = low.indexOf(a);
				if (i >= 0)return tok.substring(0, i) + withCase(tok.substring(i, i + a.length()), b) + tok.substring(i + a.length());
			}
		}
		return null;
	}

	/**
	 * The name of the other side, or null when the name has no left/right token. Every token is swapped (whole
	 * tokens: left/right, esquerda/direita, esquerdo/direito, esq/dir, l/r, e/d; left/right also at the start of a
	 * longer token, esquerda/direita/esquerdo/direito also inside one); case is preserved. Numbers (1/2) are never
	 * treated as sides: 'asa1' has no opposite here (see PartPairing for the suggestion).
	 */
	public static String oppositeName(String name) {
		if (name == null || name.isEmpty())return null;
		List<String> toks = tokens(name);
		StringBuilder sb = new StringBuilder();
		boolean any = false;
		for (String t : toks) {
			String r = t.length() == 1 && isSep(t.charAt(0)) ? null : swapToken(t);
			if (r != null) {
				any = true;
				sb.append(r);
			} else sb.append(t);
		}
		return any ? sb.toString() : null;
	}

	/** oppositeName, or the name itself when it has no side ('Trocar esquerda/direita' in batch rename) */
	public static String swapSides(String name) {
		String o = oppositeName(name);
		return o != null ? o : name;
	}

	/** every segment's name swapped (indexes '[k]' kept); equals the path when no segment has a side */
	public static String oppositePath(String path) {
		List<String> segs = split(path);
		List<String> out = new ArrayList<>();
		for (String s : segs) {
			String n = segName(s);
			String suffix = s.substring(n.length());
			out.add(swapSides(n) + suffix);
		}
		return String.join("/", out);
	}
}
