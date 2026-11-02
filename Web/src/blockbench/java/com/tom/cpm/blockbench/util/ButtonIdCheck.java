package com.tom.cpm.blockbench.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPM+ (W5-CMDS, G31 + E2): what CPM's AnimationExporter will make of the animation names. Pure Java (javac-testable).
 * <p>
 * Every gesture-wheel button, layer group (dropdown) and dropdown option lives in ONE name space in CPM
 * ({@code AnimationExporter.allButtons}, keyed by {@code EditorAnim.getId()} = the name before the first '#', or the
 * group name). {@link #check(List)} replays {@code processAnimation} / {@code makeButtonInfo} step by step on the
 * animations in export order and reports:
 * <ul>
 * <li>{@link Result#conflictGroup}: the group name for which the real exporter throws
 * {@code RuntimeException("Animation name conflict " + group)} in this order (the export fails);</li>
 * <li>RED: a layer group named like a button that is not a dropdown (pose, gesture, toggle, slider) - depending on the
 * order the export fails or the button silently drives the dropdown;</li>
 * <li>YELLOW: two different things share one name (an option in two groups, an option named like a button or another
 * group, a pose and a gesture, a slider and a toggle...): only one of them exists in game and it also triggers the
 * other one's animations; a name that starts with '#' (empty id);</li>
 * <li>GREY: the same type (and group) with the same id ('Asas#1', 'Asas#2', or the exact same name): one button that
 * plays both animations, intended.</li>
 * </ul>
 * The in-game lookup is case-sensitive: 'Feliz' and 'feliz' are different names and never reported. The result
 * depends on the order of the animation list.
 */
public final class ButtonIdCheck {

	/** kind of a CPM animation (EditorAnim type / pose) */
	public static enum Kind {
		VANILLA_POSE, CUSTOM_POSE, GESTURE, LAYER, VALUE_LAYER, SETUP, FINISH
	}

	/** button types CPM creates */
	public static enum ButtonType {
		POSE, GESTURE, TOGGLE, VALUE, DROPDOWN
	}

	public static enum Severity {
		RED, YELLOW, GREY
	}

	/** one CPM animation (as converted for export), in export order */
	public static final class Anim {
		public final Kind kind;
		/** the exported display name (EditorAnim.displayName) */
		public final String name;
		/** layer group (LAYER only); null / empty = no group (CPM does not save an empty group) */
		public final String group;
		public boolean command, isProperty, hidden, layerCtrl = true;
		public int order, maxValue = 100;
		public float layerDefault;
		/** caller data (e.g. the Blockbench animation uuid) */
		public Object ref;
		int index;

		public Anim(Kind kind, String name, String group) {
			this.kind = kind;
			this.name = name != null ? name : "";
			this.group = group == null || group.isEmpty() ? null : group;
		}

		/** EditorAnim.getId(): the name before the first '#' ("" when it starts with '#') */
		public String getId() {
			return idOf(name);
		}

		public boolean makesButton() {
			return kind == Kind.CUSTOM_POSE || kind == Kind.GESTURE || kind == Kind.LAYER || kind == Kind.VALUE_LAYER;
		}

		public int getIndex() {
			return index;
		}

		@Override
		public String toString() {
			return kind + ":" + name + (group != null ? "@" + group : "");
		}
	}

	/** one button of the compiled model */
	public static final class Button {
		public final ButtonType type;
		public final String name;
		/** dropdown options in id order, WITHOUT the injected 'none' option */
		public final List<String> options = new ArrayList<>();
		/** dropdown: the animation that created each option (same order as options) */
		public final List<Anim> optionAnims = new ArrayList<>();
		public int order;
		public boolean command, isProperty, hidden, layerCtrl;
		public int maxValue;
		/** the animation that created the button */
		public final Anim creator;
		/** every animation this button plays (or, for a dropdown, every option animation) */
		public final List<Anim> anims = new ArrayList<>();
		final int seq;

		Button(ButtonType type, String name, Anim creator, int seq) {
			this.type = type;
			this.name = name;
			this.creator = creator;
			this.seq = seq;
		}

		/** CPM's keybind / saved-choice key prefix: p, g, l, d (a slider has no keybind: v) */
		public String keyPrefix() {
			switch (type) {
			case POSE: return "p";
			case GESTURE: return "g";
			case TOGGLE: return "l";
			case DROPDOWN: return "d";
			default: return "v";
			}
		}
	}

	/** ParameterInfo: a button, and the option index for a dropdown option */
	static final class Param {
		final Button button;
		final int value;
		/** what created this entry: kind + group + id (equal key = the same button on purpose) */
		final String creatorKey;
		final Anim creator;

		Param(Button button, int value, String creatorKey, Anim creator) {
			this.button = button;
			this.value = value;
			this.creatorKey = creatorKey;
			this.creator = creator;
		}
	}

	public static final class Finding {
		public final Severity severity;
		/** GROUP_CONFLICT, GROUP_VS_OPTION, NAME_MIX, EMPTY_ID, SAME_BUTTON */
		public final String code;
		/** the shared name */
		public final String name;
		/** the animations involved (first = the one that owns the name in this order) */
		public final List<Anim> anims = new ArrayList<>();
		/** true when the real exporter throws for this finding in this order */
		public boolean throwsNow;

		Finding(Severity severity, String code, String name) {
			this.severity = severity;
			this.code = code;
			this.name = name;
		}

		void add(Anim a) {
			if (a != null && !anims.contains(a))anims.add(a);
		}
	}

	public static final class Result {
		/** buttons in creation order */
		public final List<Button> buttons = new ArrayList<>();
		/** the group whose dropdown makes the real exporter throw 'Animation name conflict', null when it does not */
		public String conflictGroup;
		/** the animation being processed when it throws */
		public Anim conflictAnim;
		public final List<Finding> findings = new ArrayList<>();
		/** every animation -> the button it triggers (null: vanilla pose / staged / not reached) */
		public final Map<Anim, Button> target = new LinkedHashMap<>();
		/** every animation -> the dropdown option index it triggers (dropdown options only, 1-based like CPM: 0 = none) */
		public final Map<Anim, Integer> optionValue = new HashMap<>();

		/** buttons in gesture-wheel order: AnimationExporter.sortButtons (stable sort by order) */
		public List<Button> sorted() {
			List<Button> l = new ArrayList<>(buttons);
			Collections.sort(l, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order) : Integer.compare(a.seq, b.seq));
			return l;
		}

		public int count(Severity s) {
			int n = 0;
			for (Finding f : findings)if (f.severity == s)n++;
			return n;
		}

		/** the findings an animation is part of */
		public List<Finding> findingsOf(Anim a) {
			List<Finding> l = new ArrayList<>();
			for (Finding f : findings)if (f.anims.contains(a))l.add(f);
			return l;
		}
	}

	private ButtonIdCheck() {
	}

	public static String idOf(String name) {
		if (name == null)return "";
		int i = name.indexOf('#');
		if (i == -1)return name;
		if (i == 0)return "";
		return name.substring(0, i);
	}

	private static String naturalKey(Anim a) {
		switch (a.kind) {
		case CUSTOM_POSE: return "P\u0000" + a.getId();
		case GESTURE: return "G\u0000" + a.getId();
		case VALUE_LAYER: return "V\u0000" + a.getId();
		case LAYER: return a.group == null ? "T\u0000" + a.getId() : "O\u0000" + a.group + "\u0000" + a.getId();
		default: return null;
		}
	}

	/** replays AnimationExporter on the animations (export order) */
	public static Result check(List<Anim> anims) {
		Result r = new Result();
		Map<String, Param> all = new HashMap<>();
		int[] seq = {0};
		for (int i = 0; i < anims.size(); i++)anims.get(i).index = i;
		for (Anim a : anims) {
			if (!a.makesButton())continue;
			String id = a.getId();
			if (!all.containsKey(id)) {
				Param p;
				try {
					p = makeButtonInfo(a, all, r, seq);
				} catch (ConflictException e) {
					r.conflictGroup = e.group;
					r.conflictAnim = a;
					break;// the real export stops here
				}
				if (p != null) {
					// processAnimation: these flags are (re)applied on the button of the new entry
					if (a.kind != Kind.LAYER)p.button.layerCtrl = a.layerCtrl;
					p.button.command = a.command;
					p.button.isProperty = a.isProperty;
					target(r, a, p);
				}
			} else {
				Param p = all.get(id);
				target(r, a, p);
				String nk = naturalKey(a);
				if (nk.equals(p.creatorKey)) {
					Finding f = find(r, Severity.GREY, "SAME_BUTTON", id);
					f.add(p.creator);
					f.add(a);
				} else if (p.creatorKey.startsWith("D\u0000")) {
					// named like a dropdown created before (group key): a button drives the dropdown (the other order
					// throws), an option lands on 'none' of that dropdown
					if (a.kind == Kind.LAYER && a.group != null) {
						Finding f = find(r, Severity.YELLOW, "GROUP_VS_OPTION", p.button.name);
						f.add(a);
						f.add(p.creator);
					} else groupFinding(r, p.button.name, p.creator, a, false);
				} else {
					Finding f = find(r, Severity.YELLOW, "NAME_MIX", id);
					f.add(p.creator);
					f.add(a);
				}
			}
		}
		// order independent: a group named like a pose / gesture / toggle / slider fails in the other order
		for (Anim g : anims) {
			if (g.kind != Kind.LAYER || g.group == null)continue;
			for (Anim b : anims) {
				if (b == g || !b.makesButton())continue;
				boolean nonDropdown = b.kind != Kind.LAYER || b.group == null;
				if (nonDropdown && b.getId().equals(g.group))groupFinding(r, g.group, b, g, false);
				else if (!nonDropdown && b.getId().equals(g.group)) {
					// an option named like a group (its own: two commands with one name; another: options mixed up)
					Finding f = find(r, Severity.YELLOW, "GROUP_VS_OPTION", g.group);
					f.add(b);
					f.add(g);
				}
			}
		}
		if (r.conflictGroup != null) {
			Finding f = groupFinding(r, r.conflictGroup, null, r.conflictAnim, true);
			// the button that holds the name
			for (Anim b : anims)if (b.makesButton() && (b.kind != Kind.LAYER || b.group == null) && b.getId().equals(r.conflictGroup))f.add(b);
		}
		for (Anim a : anims) {
			if (a.makesButton() && a.name.startsWith("#")) {
				Finding f = find(r, Severity.YELLOW, "EMPTY_ID", "");
				f.add(a);
			}
		}
		// a yellow / red finding makes the grey one about the same name redundant
		r.findings.removeIf(f -> f.severity == Severity.GREY && r.findings.stream().anyMatch(o -> o != f && o.severity != Severity.GREY && o.name.equals(f.name)));
		Collections.sort(r.findings, (x, y) -> x.severity.ordinal() - y.severity.ordinal());
		return r;
	}

	private static Finding groupFinding(Result r, String group, Anim button, Anim layer, boolean throwsNow) {
		Finding f = find(r, Severity.RED, "GROUP_CONFLICT", group);
		f.add(button);
		f.add(layer);
		if (throwsNow)f.throwsNow = true;
		return f;
	}

	private static Finding find(Result r, Severity s, String code, String name) {
		for (Finding f : r.findings)if (f.code.equals(code) && f.name.equals(name))return f;
		Finding f = new Finding(s, code, name);
		r.findings.add(f);
		return f;
	}

	private static void target(Result r, Anim a, Param p) {
		r.target.put(a, p.button);
		if (p.button.type == ButtonType.DROPDOWN && p.value > 0)r.optionValue.put(a, p.value);
		if (!p.button.anims.contains(a))p.button.anims.add(a);
	}

	private static final class ConflictException extends Exception {
		private static final long serialVersionUID = 1L;
		final String group;

		ConflictException(String group) {
			super("Animation name conflict " + group);
			this.group = group;
		}
	}

	private static Param makeButtonInfo(Anim a, Map<String, Param> all, Result r, int[] seq) throws ConflictException {
		String id = a.getId();
		switch (a.kind) {
		case CUSTOM_POSE:
		case GESTURE:
		{
			Button b = new Button(a.kind == Kind.CUSTOM_POSE ? ButtonType.POSE : ButtonType.GESTURE, id, a, seq[0]++);
			b.layerCtrl = a.layerCtrl;
			b.hidden = a.hidden;
			b.order = a.order;
			r.buttons.add(b);
			Param p = new Param(b, 0, naturalKey(a), a);
			all.put(id, p);
			return p;
		}
		case LAYER:
			if (a.group != null) {
				if (!all.containsKey(a.group)) {
					Button dd = new Button(ButtonType.DROPDOWN, a.group, a, seq[0]++);
					dd.order = a.order;
					r.buttons.add(dd);
					all.put(a.group, new Param(dd, 0, "D\u0000" + a.group, a));
				}
				Param info = all.get(a.group);
				if (info.button.type != ButtonType.DROPDOWN)throw new ConflictException(a.group);
				Button dd = info.button;
				dd.options.add(id);
				dd.optionAnims.add(a);
				Param pr = new Param(dd, dd.options.size(), naturalKey(a), a);// option ids start at 1 ('none' is 0)
				all.put(id, pr);
				return pr;
			} else {
				Button b = new Button(ButtonType.TOGGLE, id, a, seq[0]++);
				b.hidden = a.hidden;
				b.order = a.order;
				r.buttons.add(b);
				Param p = new Param(b, 0, naturalKey(a), a);
				all.put(id, p);
				return p;
			}
		case VALUE_LAYER:
		{
			Button b = new Button(ButtonType.VALUE, id, a, seq[0]++);
			b.maxValue = a.maxValue;
			b.hidden = a.hidden;
			b.order = a.order;
			r.buttons.add(b);
			Param p = new Param(b, 0, naturalKey(a), a);
			all.put(id, p);
			return p;
		}
		default:
			return null;
		}
	}

	/**
	 * Every name a command can address in game (CommandAction names), in button order: each button's name and each
	 * dropdown option. Exact (case-sensitive) names.
	 */
	public static List<String> commandNames(Result r) {
		List<String> l = new ArrayList<>();
		for (Button b : r.sorted()) {
			l.add(b.name);
			if (b.type == ButtonType.DROPDOWN)l.addAll(b.options);
		}
		return l;
	}
}
