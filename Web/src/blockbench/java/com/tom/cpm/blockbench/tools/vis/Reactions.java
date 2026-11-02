package com.tom.cpm.blockbench.tools.vis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.ext.W5VisNatives;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.blockbench.util.RootUtil;

import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W5-VIS, G37) visibility inside animations:
 * <ul>
 * <li>Mode A 'Mostrar/esconder nesta animação…' ({@link #showHideInAnim}): the selected parts appear / disappear in
 * the selected animation (whole animation, from the playhead, or a range snapped to the export grid).</li>
 * <li>Mode B 'Reações automáticas…' ({@link #createRule}): 'when [situation] then [hide | show | swap] these parts'.
 * One animation per rule and per pose (OR rules and the crouch-walking twin make two with the same rule id): type =
 * the lower-case VanillaPose, additive, loop, length 1 s, visibility keys only at 0 and 1, priority above every
 * animation touching the parts (min 10, never above 127), marker {@value PlusVisibility#RULE_PROP} = {v, kind:'rule',
 * id, when, pose, action, uuids, uuidsHide}.</li>
 * </ul>
 * 'Mostrar' makes the parts hidden by default ('hidden'), which changes first person, the menu and every other
 * animation: it is only written after the impact list was confirmed. Keys go on visibility controllers when a part is
 * moved by another animation (G33 rule). Every confirmation is ONE undo entry. Export check: a rule that another
 * animation with an equal or higher priority touches 'pode ser sobrescrita' (fix: raise the rule above it).
 */
public final class Reactions {
	public static final String WARN_OVERRIDDEN = "w5visRuleOverridden";
	public static final int MIN_PRIORITY = 10;

	/** situation id -&gt; poses (lower-case VanillaPose names) */
	static final Map<String, String[]> WHEN = new LinkedHashMap<>();
	static {
		WHEN.put("helmet", new String[] {"armor_head"});
		WHEN.put("skull", new String[] {"wearing_skull"});
		WHEN.put("helmet_or_skull", new String[] {"armor_head", "wearing_skull"});
		WHEN.put("chest", new String[] {"armor_body"});
		WHEN.put("elytra", new String[] {"wearing_elytra"});
		WHEN.put("chest_or_elytra", new String[] {"armor_body", "wearing_elytra"});
		WHEN.put("legs", new String[] {"armor_legs"});
		WHEN.put("boots", new String[] {"armor_boots"});
		WHEN.put("hurt", new String[] {"hurt"});
		WHEN.put("fire", new String[] {"on_fire"});
		WHEN.put("freeze", new String[] {"freezing"});
		WHEN.put("speak", new String[] {"speaking"});
		WHEN.put("sneak", new String[] {"sneaking"});
	}

	private Reactions() {
	}

	/** situation id for an id or a VanillaPose name ('ARMOR_HEAD' -&gt; 'helmet'), or null */
	public static String whenId(String s) {
		if (s == null)return null;
		if (WHEN.containsKey(s))return s;
		String l = s.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, String[]> e : WHEN.entrySet())if (e.getValue().length == 1 && e.getValue()[0].equals(l))return e.getKey();
		if ("sneak_walk".equals(l))return "sneak";
		return null;
	}

	// ------------------------------------------------------------------ rules

	/** one rule (all its twin animations) */
	public static final class Rule {
		public String id, when, action;
		public final List<String> uuids = new ArrayList<>(), uuidsHide = new ArrayList<>();
		public final List<Animation> anims = new ArrayList<>();

		public int priority() {
			int p = Integer.MIN_VALUE;
			for (Animation a : anims)p = Math.max(p, a.getPriority());
			return p;
		}

		public boolean hasSneakWalk() {
			for (Animation a : anims)if ("sneak_walk".equals(a.type))return true;
			return false;
		}
	}

	/** the marker of an animation, or null */
	static Map<String, Object> marker(Animation a) {
		Object o = Js.asPropertyMap(a).get(PlusVisibility.RULE_PROP);
		if (o == null || !"string".equals(Js.typeof(o)) || ((String) o).isEmpty())return null;
		try {
			return JsonUtil.asMap(JsonUtil.parse((String) o));
		} catch (Throwable e) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static void strings(Object o, List<String> into) {
		if (o instanceof List)for (Object x : (List<Object>) o)if (x != null)into.add(String.valueOf(x));
	}

	/** every reaction of the project, in animation order */
	public static List<Rule> rules() {
		Map<String, Rule> m = new LinkedHashMap<>();
		for (Animation a : PlusVisibility.anims()) {
			Map<String, Object> k = marker(a);
			if (k == null || !"rule".equals(k.get("kind")))continue;
			String id = String.valueOf(k.get("id"));
			Rule r = m.get(id);
			if (r == null) {
				r = new Rule();
				r.id = id;
				r.when = String.valueOf(k.get("when"));
				r.action = String.valueOf(k.get("action"));
				strings(k.get("uuids"), r.uuids);
				strings(k.get("uuidsHide"), r.uuidsHide);
				m.put(id, r);
			}
			r.anims.add(a);
		}
		return new ArrayList<>(m.values());
	}

	public static Rule rule(String id) {
		for (Rule r : rules())if (r.id.equals(id))return r;
		return null;
	}

	/** what a rule should do */
	public static final class RuleParams {
		public String when, action = "hide";
		public final List<Group> parts = new ArrayList<>(), partsHide = new ArrayList<>();
		public boolean sneakWalk, separate = true, confirmImpact, restoreHidden;
	}

	/** result of the rule / mode A cores (JSON-friendly) */
	public static final class Out {
		public boolean ok;
		public String errorKey, error, ruleId;
		public final List<Animation> anims = new ArrayList<>();
		public final List<String> targets = new ArrayList<>();
		public final List<String> impact = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();
		public int priority, priorityOld, renumbered;
		public double t0, t1;
		public int keys;
	}

	private static Out fail(Out o, String key, String msg) {
		o.ok = false;
		o.errorKey = key;
		o.error = msg;
		return o;
	}

	private static List<String> poses(RuleParams p) {
		List<String> l = new ArrayList<>();
		for (String s : WHEN.get(p.when))l.add(s);
		if ("sneak".equals(p.when)) {
			boolean twin = p.sneakWalk;
			for (Animation a : PlusVisibility.anims())if ("sneak_walk".equals(a.type) && marker(a) == null)twin = true;
			if (twin)l.add("sneak_walk");
		}
		return l;
	}

	/** validates a rule; null when it can be made */
	private static Out validate(RuleParams p, Out o, Collection<Animation> own) {
		p.when = whenId(p.when);
		if (p.when == null)return fail(o, "when", PlusDialogs.tr("cpmplus.w5vis.g37.badWhen"));
		if (!"hide".equals(p.action) && !"show".equals(p.action) && !"swap".equals(p.action))p.action = "hide";
		if (p.parts.isEmpty() || ("swap".equals(p.action) && p.partsHide.isEmpty()))return fail(o, "noParts", PlusDialogs.tr("cpmplus.w5vis.selectFirst"));
		List<Group> all = new ArrayList<>(p.parts);
		all.addAll(p.partsHide);
		for (Group g : all) {
			if (PlusVisibility.isRoot(g)) {
				boolean hidesHead = ("skull".equals(p.when) || "helmet_or_skull".equals(p.when)) && "head".equalsIgnoreCase(g.name)
						&& (("hide".equals(p.action) && p.parts.contains(g)) || p.partsHide.contains(g));
				if (hidesHead)return fail(o, "skullHead", PlusDialogs.tr("cpmplus.w5vis.g37.skullHead"));
				return fail(o, "root", PlusDialogs.trf("cpmplus.w5vis.rootBlocked", g.name));
			}
		}
		List<Group> showing = showParts(p);
		if (!showing.isEmpty()) {
			for (Group g : showing)for (Animation a : PlusVisibility.impactList(g, own))if (!o.impact.contains(a.name))o.impact.add(a.name);
			if (!p.confirmImpact)return fail(o, "confirmImpact", PlusDialogs.trf("cpmplus.w5vis.impact", names(showing), o.impact.size(), String.join(", ", o.impact)));
		}
		return null;
	}

	private static List<Group> showParts(RuleParams p) {
		List<Group> l = new ArrayList<>();
		if (!"hide".equals(p.action))l.addAll(p.parts);
		return l;
	}

	static String names(Collection<Group> gs) {
		List<String> l = new ArrayList<>();
		for (Group g : gs)l.add(g.name);
		return String.join(", ", l);
	}

	/** creates a rule (one undo entry), or replaces rule 'replaceId' (its animations are removed in the same entry) */
	public static Out createRule(RuleParams p, String replaceId) {
		Out o = new Out();
		Rule old = replaceId != null ? rule(replaceId) : null;
		List<Animation> own = old != null ? old.anims : new ArrayList<>();
		Out bad = validate(p, o, own);
		if (bad != null)return bad;
		List<Group> parts = new ArrayList<>(p.parts);
		parts.addAll(p.partsHide);
		Set<Animation> touching = PlusVisibility.touching(parts, own);
		VisPriority.PriorityPlan plan = PlusVisibility.priorityAbove(touching, MIN_PRIORITY);
		if (!plan.ok)return fail(o, "priority", PlusDialogs.tr("cpmplus.w5vis.priorityFull"));
		int prio = plan.value;
		List<String> poses = poses(p);
		String id = old != null ? old.id : "r" + Long.toString((long) (Math.random() * 1e12), 36);
		String whenLabel = PlusDialogs.tr("cpmplus.w5vis.g37.when." + p.when);
		String actLabel = PlusDialogs.tr("cpmplus.w5vis.g37.action." + p.action);
		String baseName = whenLabel + " - " + actLabel + " " + shortNames(p.parts);
		PlusVisibility.Edit e = new PlusVisibility.Edit();
		for (Group g : parts)PlusVisibility.addControllerScope(g, e);
		for (Animation a : own)e.addAnim(a);
		PlusVisibility.addPlanScope(plan, e);
		List<Group> restore = new ArrayList<>();
		if (old != null && p.restoreHidden)restore.addAll(oldShowTargets(old));
		for (Group g : restore)e.addGroup(g);
		PlusVisibility.run(PlusDialogs.tr(old != null ? "cpmplus.w5vis.g37.undoEdit" : "cpmplus.w5vis.g37.undoCreate"), e, ed -> {
			for (Animation a : own) {
				W5VisNatives.removeAnimation(a);
				ed.removed(a);
			}
			for (Group g : restore)if (!dependsOnHidden(g))g.hidden = false;
			plan.applyRenumber();
			List<Group> show = new ArrayList<>(), hide = new ArrayList<>();
			if ("hide".equals(p.action)) {
				for (Group g : p.parts)hide.add(PlusVisibility.keyTarget(g, p.separate, ed));
			} else {
				for (Group g : p.parts)show.add(PlusVisibility.keyTarget(g, p.separate, ed));
				for (Group g : p.partsHide)hide.add(PlusVisibility.keyTarget(g, p.separate, ed));
			}
			Map<String, Object> mk = new LinkedHashMap<>();
			mk.put("v", 1.0);
			mk.put("kind", "rule");
			mk.put("id", id);
			mk.put("when", p.when);
			mk.put("action", p.action);
			List<Object> u = new ArrayList<>();
			for (Group g : p.parts)u.add(g.uuid);
			mk.put("uuids", u);
			List<Object> uh = new ArrayList<>();
			for (Group g : p.partsHide)uh.add(g.uuid);
			mk.put("uuidsHide", uh);
			for (String pose : poses) {
				mk.put("pose", pose.toUpperCase(Locale.ROOT));
				String nm = PlusVisibility.uniqueAnimName(poses.size() > 1 ? baseName + " (" + PlusDialogs.tr("cpmplus.w5vis.g37.pose." + pose) + ")" : baseName);
				Animation a = W5VisNatives.newAnimation(nm, "loop", 1, 20);
				ed.created(a);
				a.type = pose;
				a.additive = true;
				a.setPriority(prio);
				Js.asPropertyMap(a).set(PlusVisibility.RULE_PROP, JsonUtil.toJson(mk));
				for (Group t : show)PlusVisibility.setConstant(a, t, true);
				for (Group t : hide)PlusVisibility.setConstant(a, t, false);
				o.anims.add(a);
			}
			for (Group t : show)t.hidden = true;
			for (Group t : show)o.targets.add(t.name);
			for (Group t : hide)o.targets.add(t.name);
		});
		o.ok = true;
		o.ruleId = id;
		o.priority = prio;
		o.warnings.addAll(e.warnings);
		return o;
	}

	private static String shortNames(List<Group> gs) {
		String s = names(gs);
		return s.length() > 40 ? s.substring(0, 39) + "…" : s;
	}

	private static List<Group> oldShowTargets(Rule r) {
		List<Group> l = new ArrayList<>();
		for (Animation a : r.anims) {
			for (Group g : Group.all) {
				if (l.contains(g))continue;
				elemental2.core.JsArray<Object> k = W5VisNatives.visKeys(a, g);
				if (k.length >= 2 && Js.asDouble(k.getAt(1)) != 0 && g.hidden)l.add(g);
			}
		}
		return l;
	}

	/** true when another animation (not being removed) still shows g with a visibility key */
	private static boolean dependsOnHidden(Group g) {
		for (Animation a : PlusVisibility.anims())if (PlusVisibility.visKeyCount(a, g) > 0)return true;
		return false;
	}

	/** removes a rule and all its twins (one undo entry); restoreHidden: parts it showed become visible by default again */
	public static Out deleteRule(String id, boolean restoreHidden) {
		Out o = new Out();
		Rule r = rule(id);
		if (r == null)return fail(o, "noRule", PlusDialogs.tr("cpmplus.w5vis.g37.noRule"));
		List<Group> restore = restoreHidden ? oldShowTargets(r) : new ArrayList<>();
		PlusVisibility.Edit e = new PlusVisibility.Edit();
		for (Animation a : r.anims)e.addAnim(a);
		for (Group g : restore)e.addGroup(g);
		PlusVisibility.run(PlusDialogs.tr("cpmplus.w5vis.g37.undoDelete"), e, ed -> {
			for (Animation a : r.anims) {
				W5VisNatives.removeAnimation(a);
				ed.removed(a);
			}
			for (Group g : restore)if (!dependsOnHidden(g))g.hidden = false;
		});
		o.ok = true;
		o.ruleId = id;
		return o;
	}

	// ------------------------------------------------------------------ mode A

	/** mode A parameters */
	public static final class ShowHide {
		public Animation anim;
		public final List<Group> groups = new ArrayList<>();
		public boolean show = true, hideOutside, confirmImpact, separate = true, replace = true;
		/** 'all', 'fromPlayhead' or 'range' */
		public String range = "all";
		public double t0, t1;
	}

	/** mode A core: one undo entry, or an error and nothing changed */
	public static Out showHideInAnim(ShowHide s) {
		Out o = new Out();
		if (s.anim == null)return fail(o, "noAnim", PlusDialogs.tr("cpmplus.w5vis.g37.noAnim"));
		if (s.groups.isEmpty())return fail(o, "noParts", PlusDialogs.tr("cpmplus.w5vis.selectFirst"));
		for (Group g : s.groups)if (PlusVisibility.isRoot(g))return fail(o, "root", PlusDialogs.trf("cpmplus.w5vis.rootBlocked", g.name));
		double len = s.anim.length > 0 ? s.anim.length : 1;
		double t0 = 0, t1 = len;
		if ("fromPlayhead".equals(s.range)) {
			t0 = W5VisNatives.timelineTime();
		} else if ("range".equals(s.range)) {
			t0 = s.t0;
			t1 = s.t1;
		}
		PlusVisibility.Range dry = PlusVisibility.setVisibilityRange(s.anim, s.groups.get(0), s.show, t0, t1, s.replace, false);
		o.t0 = dry.t0;
		o.t1 = dry.t1;
		if (dry.error != null)return fail(o, dry.error, PlusDialogs.trf("cpmplus.w5vis.g37.subFrame", fmt(1.0 / PlusVisibility.snapping(s.anim))));
		List<Animation> self = new ArrayList<>();
		self.add(s.anim);
		if (s.show && s.hideOutside) {
			for (Group g : s.groups)for (Animation a : PlusVisibility.impactList(g, self))if (!o.impact.contains(a.name))o.impact.add(a.name);
			if (!s.confirmImpact)return fail(o, "confirmImpact", PlusDialogs.trf("cpmplus.w5vis.impact", names(s.groups), o.impact.size(), String.join(", ", o.impact)));
		}
		// the user's animation keeps its priority (changing it would change how it mixes with the others in game);
		// other animations keyed on the same group are only reported
		int old = s.anim.getPriority();
		o.priorityOld = old;
		o.priority = old;
		PlusVisibility.Edit e = new PlusVisibility.Edit();
		for (Group g : s.groups)PlusVisibility.addControllerScope(g, e);
		e.addAnim(s.anim);
		double ft0 = t0, ft1 = t1;
		List<Group> keyed = new ArrayList<>();
		PlusVisibility.run(PlusDialogs.tr("cpmplus.w5vis.g37.undoShowHide"), e, ed -> {
			for (Group g : PlusVisibility.topmost(s.groups)) {
				Group t = PlusVisibility.keyTarget(g, s.separate, ed);
				PlusVisibility.Range r = PlusVisibility.setVisibilityRange(s.anim, t, s.show, ft0, ft1, s.replace, true);
				o.keys += r.keys;
				if (s.show && s.hideOutside)t.hidden = true;
				o.targets.add(t.name);
				keyed.add(t);
			}
		});
		o.ok = true;
		for (Group t : keyed) {
			for (Animation a : PlusVisibility.anims()) {
				if (a == s.anim || !W5VisNatives.hasKeysExcept(a, t, null) || a.getPriority() < old)continue;
				o.warnings.add(PlusDialogs.trf("cpmplus.w5vis.g37a.otherAnim", a.name, t.name));
			}
		}
		o.warnings.addAll(e.warnings);
		return o;
	}

	// ------------------------------------------------------------------ export check

	/** ExportCheck: a rule touched by a non-rule animation with an equal / higher priority may be overridden */
	public static void check(ExportContext ctx) {
		for (Rule r : rules()) {
			Set<Group> targets = new LinkedHashSet<>();
			for (Animation a : r.anims)for (Group g : Group.all)if (PlusVisibility.visKeyCount(a, g) > 0)targets.add(g);
			if (targets.isEmpty())continue;
			int prio = r.priority();
			List<Animation> over = overriders(targets, prio);
			if (over.isEmpty())continue;
			List<String> on = new ArrayList<>();
			int maxP = Integer.MIN_VALUE;
			for (Animation a : over) {
				on.add(a.name);
				maxP = Math.max(maxP, a.getPriority());
			}
			String rn = r.anims.get(0).name;
			WarnEntry w = WarnFactory.of(Severity.DIFFERS, WARN_OVERRIDDEN, "cpmplus.w5vis.g37.overridden", rn, String.join(", ", on), maxP, prio);
			w.setConsequence("cpmplus.w5vis.g37.overridden.consequence");
			for (Group g : targets)w.addNode(g.uuid, g.name);
			String id = r.id;
			w.setFix(() -> raiseFix(id), FixKind.DETERMINISTIC);
			w.setFixPreview(PlusDialogs.trf("cpmplus.w5vis.g37.raisePreview", rn));
			ctx.add(w);
		}
	}

	/** non-rule animations with keys on the targets or their ancestors and a priority &gt;= prio */
	static List<Animation> overriders(Collection<Group> targets, int prio) {
		List<Animation> out = new ArrayList<>();
		Set<Animation> t = PlusVisibility.touching(targets, null);
		for (Animation a : t) {
			if (marker(a) != null)continue;
			if (a.getPriority() >= prio)out.add(a);
		}
		return out;
	}

	private static Promise<Boolean> raiseFix(String id) {
		try {
			Out o = raisePriority(id);
			if (!o.ok) {
				PlusDialogs.block(o.error);
				return Promise.resolve(false);
			}
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w5vis.g37.raised", o.priorityOld, o.priority));
			return Promise.resolve(true);
		} catch (Throwable e) {
			PopupDialogs.displayError(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5vis.g37.title")), e);
			return Promise.resolve(false);
		}
	}

	/** raises every twin of the rule above the animations that override it (one undo entry) */
	public static Out raisePriority(String id) {
		Out o = new Out();
		Rule r = rule(id);
		if (r == null)return fail(o, "noRule", PlusDialogs.tr("cpmplus.w5vis.g37.noRule"));
		Set<Group> targets = new LinkedHashSet<>();
		for (Animation a : r.anims)for (Group g : Group.all)if (PlusVisibility.visKeyCount(a, g) > 0)targets.add(g);
		int old = r.priority();
		List<Animation> over = overriders(targets, Integer.MIN_VALUE);
		VisPriority.PriorityPlan plan = PlusVisibility.priorityAbove(over, Math.max(MIN_PRIORITY, old));
		if (!plan.ok)return fail(o, "priority", PlusDialogs.tr("cpmplus.w5vis.priorityFull"));
		int np = plan.value;
		List<Animation> scope = new ArrayList<>(r.anims);
		for (Animation a : plan.renumber.keySet())if (!scope.contains(a))scope.add(a);
		PlusVisibility.editAnims(scope, PlusDialogs.tr("cpmplus.w5vis.g37.undoRaise"), () -> {
			plan.applyRenumber();
			for (Animation a : r.anims)a.setPriority(np);
		});
		o.ok = true;
		o.priorityOld = old;
		o.priority = np;
		o.renumbered = plan.renumber.size();
		return o;
	}

	// ------------------------------------------------------------------ conflicts / test

	/** other rules on the same parts (and opposite actions of the exclusive pose pairs) */
	public static List<String> conflicts() {
		List<String> out = new ArrayList<>();
		List<Rule> rs = rules();
		for (int i = 0; i < rs.size(); i++) {
			for (int j = i + 1; j < rs.size(); j++) {
				Rule a = rs.get(i), b = rs.get(j);
				Set<String> common = new LinkedHashSet<>(a.uuids);
				common.addAll(a.uuidsHide);
				Set<String> bs = new LinkedHashSet<>(b.uuids);
				bs.addAll(b.uuidsHide);
				common.retainAll(bs);
				if (common.isEmpty())continue;
				List<Group> gs = new ArrayList<>();
				for (String u : common) {
					Group g = PlusVisibility.byUuid(u);
					if (g != null)gs.add(g);
				}
				out.add(PlusDialogs.trf("cpmplus.w5vis.g37.conflict", a.anims.get(0).name, b.anims.get(0).name, names(gs)));
			}
		}
		return out;
	}

	/** session-only: shows the viewport as it looks while the rule's situation happens (no undo, nothing written) */
	public static void test(Rule r) {
		List<Group> show = new ArrayList<>(), hide = new ArrayList<>();
		for (String u : r.uuids) {
			Group g = PlusVisibility.byUuid(u);
			if (g != null)("hide".equals(r.action) ? hide : show).add(g);
		}
		for (String u : r.uuidsHide) {
			Group g = PlusVisibility.byUuid(u);
			if (g != null)hide.add(g);
		}
		PlusVisibility.preview(show, hide);
		PlusDialogs.quick(PlusDialogs.trf("cpmplus.w5vis.g37.testing", PlusDialogs.tr("cpmplus.w5vis.g37.when." + r.when)), 2500);
	}

	// ------------------------------------------------------------------ UI: mode B

	public static void openRules() {
		List<FormRow> rows = new ArrayList<>();
		List<Rule> rs = rules();
		rows.add(FormRow.info("intro", null, "cpmplus.w5vis.g37.intro"));
		Dialog[] dlg = new Dialog[1];
		if (rs.isEmpty())rows.add(FormRow.info("none", null, "cpmplus.w5vis.g37.none"));
		for (int i = 0; i < rs.size(); i++) {
			Rule r = rs.get(i);
			String label = PlusDialogs.trf("cpmplus.w5vis.g37.ruleLine", PlusDialogs.tr("cpmplus.w5vis.g37.when." + r.when), PlusDialogs.tr("cpmplus.w5vis.g37.action." + r.action),
					names(groupsOf(r.uuids)), r.priority());
			rows.add(FormRow.buttons("rule" + i, label, new String[] {"cpmplus.w5vis.test", "cpmplus.w5vis.g37.edit", "cpmplus.w5vis.g37.delete"}, b -> {
				if (b == 0)test(r);
				else if (b == 1) {
					closeQuietly(dlg[0]);
					openEditor(r);
				} else {
					closeQuietly(dlg[0]);
					confirmDelete(r);
				}
			}));
		}
		rows.add(FormRow.buttons("actions", null, new String[] {"cpmplus.w5vis.g37.new", "cpmplus.w5vis.g37.checkConflicts", "cpmplus.w5vis.test.stop"}, b -> {
			if (b == 0) {
				closeQuietly(dlg[0]);
				openEditor(null);
			} else if (b == 1) {
				List<String> c = conflicts();
				if (c.isEmpty())c.add(PlusDialogs.tr("cpmplus.w5vis.g37.noConflicts"));
				PlusDialogs.info("cpmplus_w5vis_rule_conflicts", "cpmplus.w5vis.g37.checkConflicts", c);
			} else PlusVisibility.endPreview();
		}));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w0binfra.btn.close";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		fo.onCancel = PlusVisibility::endPreview;
		dlg[0] = PlusDialogs.form("cpmplus_w5vis_reactions", "cpmplus.w5vis.g37.title", rows, r -> PlusVisibility.endPreview(), fo);
	}

	private static void closeQuietly(Dialog d) {
		PlusVisibility.endPreview();
		try {
			if (d != null)d.hide();
		} catch (Throwable e) {
		}
	}

	static List<Group> groupsOf(List<String> uuids) {
		List<Group> l = new ArrayList<>();
		for (String u : uuids) {
			Group g = PlusVisibility.byUuid(u);
			if (g != null)l.add(g);
		}
		return l;
	}

	private static void confirmDelete(Rule r) {
		boolean showed = !"hide".equals(r.action) && !oldShowTargets(r).isEmpty();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("what", null, PlusDialogs.trf("cpmplus.w5vis.g37.deleteWhat", r.anims.get(0).name, r.anims.size())));
		if (showed)rows.add(FormRow.checkbox("restore", "cpmplus.w5vis.g37.restoreHidden", true));
		PlusDialogs.form("cpmplus_w5vis_rule_delete", "cpmplus.w5vis.g37.delete", rows, f -> {
			Out o = deleteRule(r.id, showed && f.getBool("restore"));
			if (o.ok)PlusDialogs.done(PlusDialogs.tr("cpmplus.w5vis.g37.deleted"));
			else PlusDialogs.block(o.error);
		});
	}

	/** rule editor (r = null: new rule, prefilled with the selection) */
	public static void openEditor(Rule r) {
		Map<String, String> whenOpts = new LinkedHashMap<>();
		for (String k : WHEN.keySet())whenOpts.put(k, "cpmplus.w5vis.g37.when." + k);
		Map<String, String> parts = new LinkedHashMap<>();
		for (Group g : Group.all)if (!PlusVisibility.isRoot(g) && !g.is_rotation_subgroup)parts.put(g.uuid, g.name);
		List<String> pre = new ArrayList<>();
		if (r != null)pre.addAll(r.uuids);
		else for (Group g : PlusVisibility.selectedGroups())if (parts.containsKey(g.uuid))pre.add(g.uuid);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.select("when", "cpmplus.w5vis.g37.whenLabel", whenOpts, r != null ? r.when : "helmet"));
		rows.add(FormRow.radio("action", "cpmplus.w5vis.g37.actionLabel", PlusDialogs.options("hide", "cpmplus.w5vis.g37.action.hide", "show", "cpmplus.w5vis.g37.action.show", "swap", "cpmplus.w5vis.g37.action.swap"), r != null ? r.action : "hide"));
		rows.add(FormRow.multiSelect("parts", "cpmplus.w5vis.g37.parts", parts, pre));
		rows.add(FormRow.multiSelect("partsHide", "cpmplus.w5vis.g37.partsHide", parts, r != null ? r.uuidsHide : new ArrayList<>()).when(f -> "swap".equals(f.getString("action"))));
		rows.add(FormRow.checkbox("sneakWalk", "cpmplus.w5vis.g37.sneakWalk", r != null && r.hasSneakWalk()).when(f -> "sneak".equals(f.getString("when"))));
		rows.add(FormRow.checkbox("separate", "cpmplus.w5vis.separate", true).description("cpmplus.w5vis.separate.desc"));
		rows.add(FormRow.info("noteHead", null, "cpmplus.w5vis.g37.note.headPair").when(f -> String.valueOf(f.getString("when")).matches("helmet|skull|helmet_or_skull")));
		rows.add(FormRow.info("noteSkull", null, "cpmplus.w5vis.g37.note.skull").when(f -> String.valueOf(f.getString("when")).matches("skull|helmet_or_skull")));
		rows.add(FormRow.info("noteChest", null, "cpmplus.w5vis.g37.note.chestPair").when(f -> String.valueOf(f.getString("when")).matches("chest|elytra")));
		rows.add(FormRow.info("noteMaterial", null, "cpmplus.w5vis.g37.note.material").when(f -> String.valueOf(f.getString("when")).matches("helmet|chest|legs|boots|helmet_or_skull|chest_or_elytra")));
		rows.add(FormRow.info("noteHurt", null, "cpmplus.w5vis.g37.note.hurt").when(f -> "hurt".equals(f.getString("when"))));
		rows.add(FormRow.info("noteSpeak", null, "cpmplus.w5vis.g37.note.speak").when(f -> "speak".equals(f.getString("when"))));
		rows.add(FormRow.info("noteShow", null, "cpmplus.w5vis.g37.note.show").when(f -> !"hide".equals(f.getString("action"))));
		rows.add(FormRow.info("notePriority", null, "cpmplus.w5vis.g37.note.priority"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = r != null ? "cpmplus.w5vis.g37.save" : "cpmplus.w5vis.g37.create";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form("cpmplus_w5vis_rule", r != null ? "cpmplus.w5vis.g37.edit" : "cpmplus.w5vis.g37.new", rows, f -> {
			RuleParams p = new RuleParams();
			p.when = f.getString("when");
			p.action = f.getString("action");
			p.parts.addAll(groupsOf(f.getList("parts")));
			if ("swap".equals(p.action))p.partsHide.addAll(groupsOf(f.getList("partsHide")));
			p.sneakWalk = f.getBool("sneakWalk");
			p.separate = f.getBool("separate");
			submit(p, r, f);
		}, fo);
	}

	private static void submit(RuleParams p, Rule r, FormResult f) {
		Out o = createRule(p, r != null ? r.id : null);
		if (o.ok) {
			afterCreate(o);
			return;
		}
		if ("confirmImpact".equals(o.errorKey)) {
			PlusDialogs.warnContinue(o.error, () -> {
				p.confirmImpact = true;
				Out o2 = createRule(p, r != null ? r.id : null);
				if (o2.ok)afterCreate(o2);
				else PlusDialogs.block(o2.error);
			});
			return;
		}
		if ("skullHead".equals(o.errorKey)) {
			f.keepOpen();
			PlusDialogs.messageBox("cpmplus.w5vis.g37.title", o.error, new String[] {"cpmplus.w5vis.g37.turnOnSkull", "cpmplus.w0binfra.btn.cancel"}, b -> {
				if (b == 0)turnOnHideHeadIfSkull();
			});
			return;
		}
		f.keepOpen();
		PlusDialogs.block(o.error);
	}

	/** the native project option 'Esconder a cabeça quando usar crânio' (cpm_hideHeadIfSkull), with a 'Desfazer' toast */
	public static void turnOnHideHeadIfSkull() {
		if (Project.hideHeadIfSkull) {
			PlusDialogs.quick(PlusDialogs.tr("cpmplus.w5vis.g37.skullAlreadyOn"), 2500);
			return;
		}
		ProjectDataUndo.Snapshot s = ProjectDataUndo.snapshot();
		Project.hideHeadIfSkull = true;
		Project.saved = false;
		ProjectDataUndo.offerUndo(s, PlusDialogs.tr("cpmplus.w5vis.g37.skullOn"));
	}

	private static void afterCreate(Out o) {
		PlusDialogs.done(PlusDialogs.trf("cpmplus.w5vis.g37.created", o.anims.size(), o.priority));
		if (!o.warnings.isEmpty())PlusDialogs.info("cpmplus_w5vis_rule_notes", "cpmplus.w5vis.g37.title", o.warnings);
	}

	// ------------------------------------------------------------------ UI: mode A

	public static void openShowHide() {
		Animation a = W5VisNatives.selectedAnimation();
		if (a == null) {
			PlusDialogs.block(PlusDialogs.tr("cpmplus.w5vis.g37.noAnim"));
			return;
		}
		List<Group> sel = PlusVisibility.selectedGroups();
		if (sel.isEmpty()) {
			PlusDialogs.quick(PlusDialogs.tr("cpmplus.w5vis.selectFirst"), 2500);
			return;
		}
		for (Group g : sel)if (PlusVisibility.isRoot(g)) {
			PlusDialogs.block(PlusDialogs.trf("cpmplus.w5vis.rootBlocked", g.name));
			return;
		}
		int s = PlusVisibility.snapping(a);
		double len = a.length > 0 ? a.length : 1;
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("which", null, PlusDialogs.trf("cpmplus.w5vis.g37a.which", names(sel), a.name)));
		rows.add(FormRow.radio("show", "cpmplus.w5vis.g37a.what", PlusDialogs.options("show", "cpmplus.w5vis.g37a.appear", "hide", "cpmplus.w5vis.g37a.disappear"), "show"));
		rows.add(FormRow.radio("range", "cpmplus.w5vis.g37a.when", PlusDialogs.options("all", "cpmplus.w5vis.g37a.all", "fromPlayhead", "cpmplus.w5vis.g37a.fromPlayhead", "range", "cpmplus.w5vis.g37a.range"), "all"));
		rows.add(FormRow.decimal("t0", "cpmplus.w5vis.g37a.from", PlusVisibility.snap(W5VisNatives.timelineTime(), s), 0, len, 1.0 / s).when(f -> "range".equals(f.getString("range"))));
		rows.add(FormRow.decimal("t1", "cpmplus.w5vis.g37a.to", len, 0, len, 1.0 / s).when(f -> "range".equals(f.getString("range"))));
		rows.add(FormRow.info("grid", null, PlusDialogs.trf("cpmplus.w5vis.g37a.grid", s)).when(f -> !"all".equals(f.getString("range"))));
		rows.add(FormRow.checkbox("hideOutside", "cpmplus.w5vis.g37a.hideOutside", false).when(f -> "show".equals(f.getString("show"))));
		rows.add(FormRow.info("impact", null, "cpmplus.w5vis.g37a.impactNote").when(f -> "show".equals(f.getString("show")) && f.getBool("hideOutside")));
		rows.add(FormRow.checkbox("replace", "cpmplus.w5vis.g37a.replace", true).description("cpmplus.w5vis.g37a.replace.desc"));
		rows.add(FormRow.checkbox("separate", "cpmplus.w5vis.separate", true).description("cpmplus.w5vis.separate.desc"));
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w5vis.g37a.apply";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form("cpmplus_w5vis_showhide", "cpmplus.w5vis.g37a.title", rows, f -> {
			ShowHide sh = new ShowHide();
			sh.anim = a;
			sh.groups.addAll(sel);
			sh.show = "show".equals(f.getString("show"));
			sh.range = f.getString("range");
			sh.t0 = f.getDouble("t0", 0);
			sh.t1 = f.getDouble("t1", len);
			sh.hideOutside = sh.show && f.getBool("hideOutside");
			sh.replace = f.getBool("replace");
			sh.separate = f.getBool("separate");
			applyShowHide(sh, f);
		}, fo);
	}

	private static void applyShowHide(ShowHide sh, FormResult f) {
		Out o = showHideInAnim(sh);
		if (o.ok) {
			String msg = PlusDialogs.trf("cpmplus.w5vis.g37a.done", fmt(o.t0), fmt(o.t1));
			PlusDialogs.done(msg);
			if (!o.warnings.isEmpty())PlusDialogs.info("cpmplus_w5vis_showhide_notes", "cpmplus.w5vis.g37a.title", o.warnings);
			return;
		}
		if ("confirmImpact".equals(o.errorKey)) {
			PlusDialogs.warnContinue(o.error, () -> {
				sh.confirmImpact = true;
				Out o2 = showHideInAnim(sh);
				if (o2.ok)PlusDialogs.done(PlusDialogs.trf("cpmplus.w5vis.g37a.done", fmt(o2.t0), fmt(o2.t1)));
				else PlusDialogs.block(o2.error);
			});
			return;
		}
		if (f != null)f.keepOpen();
		PlusDialogs.block(o.error);
	}

	/** seconds with at most 3 decimals ('0.5', '1', '0.05') */
	static String fmt(double t) {
		double r = Math.round(t * 1000) / 1000.0;
		if (r == Math.floor(r))return String.valueOf((long) r);
		return String.valueOf(r);
	}

	/** roots by name (skull / head checks in tests) */
	static Group rootNamed(String n) {
		return RootUtil.findRootGroup(n);
	}
}
