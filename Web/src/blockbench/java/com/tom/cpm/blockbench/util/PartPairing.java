package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.tom.cpm.blockbench.util.PartPaths.Tree;

/**
 * CPM+ (W2-ANIMCORE): finds the part on the other side (left &lt;-&gt; right) by FULL outliner path, so models that
 * repeat names many times (AngelArmor: ~360 'pena') still pair correctly.
 * <ul>
 * <li>PAIRED (green): the opposite path exists and points back to this part.</li>
 * <li>SELF: no side in the path (head, body, cauda): mirrored in place ("ela mesma").</li>
 * <li>SUGGESTION (orange): only a guess - numbered twins (asa1 / asa2: numbers are never sides), a partner that does
 * not point back, or two parts that want the same partner. The user must confirm.</li>
 * <li>NONE: the name has a side but the other side does not exist.</li>
 * </ul>
 * Pure Java (javac-testable); the Blockbench outliner is {@link AnimEdit#OUTLINER}.
 */
public final class PartPairing {
	private PartPairing() {
	}

	/**
	 * ORDER: no part answers to the opposite name, but an ancestor IS paired and the part sits at the same place in
	 * the partner's tree (same child index at every level, same child counts): a wing duplicated to the other side
	 * whose cubes Blockbench renumbered (cube114 / cube58). Used like PAIRED.
	 */
	public enum Kind {
		PAIRED, SELF, SUGGESTION, NONE, ORDER
	}

	/** PAIRED or ORDER: a partner the tools may use without asking */
	public static boolean sure(Kind k) {
		return k == Kind.PAIRED || k == Kind.ORDER;
	}

	public static final class Pair<T> {
		public final Kind kind;
		public final T source, target;
		public final String sourcePath, targetPath;

		Pair(Kind kind, T source, T target, String sourcePath, String targetPath) {
			this.kind = kind;
			this.source = source;
			this.target = target;
			this.sourcePath = sourcePath;
			this.targetPath = targetPath;
		}

		Pair<T> as(Kind k) {
			return new Pair<>(k, source, target, sourcePath, targetPath);
		}

		@Override
		public String toString() {
			return sourcePath + " -> " + targetPath + " (" + kind + ")";
		}
	}

	/** pair of one node (any node type) */
	public static <T> Pair<T> pair(Tree<T> tree, T node) {
		return pair(tree, node, null);
	}

	/**
	 * @param candidate which nodes may be a partner (e.g. only groups); null = any
	 */
	public static <T> Pair<T> pair(Tree<T> tree, T node, Predicate<T> candidate) {
		String src = PartPaths.pathOf(tree, node);
		String opp = PartPaths.oppositePath(src);
		if (opp.equals(src)) {
			T twin = numberedTwin(tree, node, candidate);
			if (twin != null)return new Pair<>(Kind.SUGGESTION, node, twin, src, PartPaths.pathOf(tree, twin));
			return new Pair<>(Kind.SELF, node, node, src, src);
		}
		T target = PartPaths.findByPath(tree, opp);
		if (target == null) {
			// two (or more) parts answer to the opposite path: only a suggestion (the first one)
			List<T> all = PartPaths.findAllByPath(tree, opp);
			if (candidate != null)all.removeIf(x -> !candidate.test(x));
			all.remove(node);
			if (all.size() >= 2)return new Pair<>(Kind.SUGGESTION, node, all.get(0), src, PartPaths.pathOf(tree, all.get(0)));
			if (all.size() == 1)target = all.get(0);
		}
		if (target == null || (candidate != null && !candidate.test(target))) {
			T twin = orderTwin(tree, node, candidate);
			if (twin != null)return new Pair<>(Kind.ORDER, node, twin, src, PartPaths.pathOf(tree, twin));
			return new Pair<>(Kind.NONE, node, null, src, opp);
		}
		if (target == node)return new Pair<>(Kind.SELF, node, node, src, src);
		String back = PartPaths.oppositePath(PartPaths.pathOf(tree, target));
		if (!back.equals(src))return new Pair<>(Kind.SUGGESTION, node, target, src, opp);
		return new Pair<>(Kind.PAIRED, node, target, src, opp);
	}

	/**
	 * Pairs several nodes; when two of them want the same partner, both become SUGGESTION (the user decides).
	 */
	public static <T> List<Pair<T>> pairAll(Tree<T> tree, Collection<T> nodes, Predicate<T> candidate) {
		List<Pair<T>> out = new ArrayList<>();
		Map<T, Integer> wanted = new HashMap<>();
		for (T n : nodes) {
			Pair<T> p = pair(tree, n, candidate);
			out.add(p);
			if (p.target != null && p.kind != Kind.SELF)wanted.merge(p.target, 1, Integer::sum);
		}
		for (int i = 0; i < out.size(); i++) {
			Pair<T> p = out.get(i);
			if (p.target != null && p.kind == Kind.PAIRED && wanted.getOrDefault(p.target, 0) > 1)out.set(i, p.as(Kind.SUGGESTION));
		}
		return out;
	}

	/**
	 * The part at the same place under the partner of the nearest ancestor that has one by name: child index (among
	 * candidate children) at every level, and the same number of children at every level. null when none.
	 */
	private static <T> T orderTwin(Tree<T> tree, T node, Predicate<T> candidate) {
		List<Integer> idx = new ArrayList<>(), sizes = new ArrayList<>();
		T cur = node;
		for (int guard = 0; guard < 1000; guard++) {
			T p = tree.parent(cur);
			if (p == null)return null;
			List<T> sib = only(tree.children(p), candidate);
			int i = sib.indexOf(cur);
			if (i < 0)return null;
			idx.add(0, i);
			sizes.add(0, sib.size());
			String pp = PartPaths.pathOf(tree, p);
			String po = PartPaths.oppositePath(pp);
			if (!po.equals(pp)) {
				T twin = PartPaths.findByPath(tree, po);
				if (twin != null && twin != p && PartPaths.oppositePath(PartPaths.pathOf(tree, twin)).equals(pp)) {
					T x = twin;
					for (int k = 0; k < idx.size(); k++) {
						List<T> ch = only(tree.children(x), candidate);
						if (ch.size() != sizes.get(k))return null;
						x = ch.get(idx.get(k));
					}
					return x == node ? null : x;
				}
			}
			cur = p;
		}
		return null;
	}

	private static <T> List<T> only(List<T> l, Predicate<T> candidate) {
		if (candidate == null)return l;
		List<T> out = new ArrayList<>();
		for (T t : l)if (candidate.test(t))out.add(t);
		return out;
	}

	/** 'asa1' -&gt; sibling 'asa2' (and back), also 'asa_1' / 'asa 2'; null when there is none */
	private static <T> T numberedTwin(Tree<T> tree, T node, Predicate<T> candidate) {
		String n = tree.name(node);
		int e = n.length();
		int s = e;
		while (s > 0 && Character.isDigit(n.charAt(s - 1)))s--;
		if (s == e || s == 0)return null;
		String base = n.substring(0, s);
		String num = n.substring(s);
		String other;
		if (num.equals("1"))other = "2";
		else if (num.equals("2"))other = "1";
		else return null;
		T p = tree.parent(node);
		List<T> sib = p == null ? tree.roots() : tree.children(p);
		for (T c : sib) {
			if (c != node && (base + other).equals(tree.name(c)) && (candidate == null || candidate.test(c)))return c;
		}
		return null;
	}
}
