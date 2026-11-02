package com.tom.cpm.blockbench.tools.vis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.BlockbenchExport;
import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.ext.W5VisNatives;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.editor.elements.ModelElement;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W5-VIS, G28) 'Criar forma com movimento próprio…': a part that walks / runs differently while a button is on.
 * CPM can't limit a pose to a layer (layer triggers are global), so the only way is a hidden copy and a visibility
 * swap: the selected part (or its pure visibility controller) is duplicated in the same parent with the same pivot
 * (IdUtil.resetIds on every copied node: fresh CPM ids), EVERY track of the original is copied to the copy in every
 * animation (the user then edits the copy's motion in the situations), and two visibility controllers are made: '..._forma'
 * around the original and one around the copy (hidden by default). The button is a LAYER animation, ADDITIVE (a
 * non-additive layer writes the rest pose every frame and would freeze the copy), with visibility keys only at 0 and
 * at the length (original hidden, copy visible), priority above every animation touching the parts and the chosen
 * situations (never above 127). Roots are refused. One undo entry. Export checks: a controller of the form deleted,
 * or an animation with an equal / higher priority keyed on the form's controllers ('pode piscar').
 */
public final class FormMotion {
	public static final String WARN_BROKEN = "w5visFormBroken", WARN_FLICKER = "w5visFormFlicker";
	/** situations offered (lower-case VanillaPose names) */
	static final String[] SITUATIONS = {"walking", "running", "sneaking", "sneak_walk", "jumping", "swimming", "flying", "creative_flying", "riding", "falling"};

	private FormMotion() {
	}

	public static final class Params {
		public String name;
		public final List<Group> parts = new ArrayList<>();
		public final List<String> situations = new ArrayList<>();
		public boolean startOn, createMissing;
	}

	public static final class Out {
		public boolean ok;
		public String errorKey, error, id;
		public Animation layer;
		public final List<Group> originals = new ArrayList<>(), copies = new ArrayList<>(), origCtrl = new ArrayList<>(), copyCtrl = new ArrayList<>();
		public final List<Animation> created = new ArrayList<>();
		public int priority, copiedKeys, dupNodes, renumbered;
		public String layerWarning;
	}

	private static Out fail(Out o, String k, String m) {
		o.ok = false;
		o.errorKey = k;
		o.error = m;
		return o;
	}

	/** every form marker of the project */
	static List<Map<String, Object>> forms() {
		List<Map<String, Object>> l = new ArrayList<>();
		for (Animation a : PlusVisibility.anims()) {
			Map<String, Object> m = Reactions.marker(a);
			if (m != null && "form".equals(m.get("kind"))) {
				m.put("_anim", a);
				l.add(m);
			}
		}
		return l;
	}

	@SuppressWarnings("unchecked")
	private static List<String> list(Map<String, Object> m, String k) {
		List<String> l = new ArrayList<>();
		Object o = m.get(k);
		if (o instanceof List)for (Object x : (List<Object>) o)l.add(String.valueOf(x));
		return l;
	}

	/** true when g (or one of its ancestors / descendants) is already part of a form */
	static String formOf(Group g) {
		for (Map<String, Object> f : forms()) {
			List<String> ids = list(f, "orig");
			ids.addAll(list(f, "copy"));
			for (String u : ids) {
				Group c = PlusVisibility.byUuid(u);
				if (c == null)continue;
				if (c == g || PlusVisibility.isInside(g, c) || PlusVisibility.isInside(c, g))return ((Animation) f.get("_anim")).name;
			}
		}
		return null;
	}

	/** the node duplicated for p: its pure visibility controller (keeps the visibility logic), else p */
	static Group unitOf(Group p) {
		Group pc = PlusVisibility.pureControllerOf(p);
		return pc != null ? pc : p;
	}

	static List<Object> nodes(Group g) {
		List<Object> l = new ArrayList<>();
		JsArray<Object> a = W5VisNatives.subtree(g);
		for (int i = 0; i < a.length; i++)l.add(a.getAt(i));
		return l;
	}

	/** creates the form (one undo entry) or explains why not (nothing changed) */
	public static Out create(Params p) {
		Out o = new Out();
		if (p.parts.isEmpty())return fail(o, "noParts", PlusDialogs.tr("cpmplus.w5vis.selectFirst"));
		for (Group g : p.parts) {
			if (PlusVisibility.isRoot(g))return fail(o, "root", PlusDialogs.trf("cpmplus.w5vis.g28.root", g.name));
			String f = formOf(g);
			if (f != null)return fail(o, "inForm", PlusDialogs.trf("cpmplus.w5vis.g28.inForm", g.name, f));
		}
		List<Group> parts = PlusVisibility.topmost(p.parts);
		List<Group> units = new ArrayList<>();
		for (Group g : parts)units.add(unitOf(g));
		// priority: above every animation touching the parts (their ancestors and everything inside) and the chosen situations
		Set<Animation> touching = new LinkedHashSet<>(PlusVisibility.touching(units, null));
		Set<Group> inside = new LinkedHashSet<>();
		for (Group u : units)for (Object n : nodes(u))if (n instanceof Group)inside.add((Group) n);
		for (Animation a : PlusVisibility.anims()) {
			for (Group g : inside)if (W5VisNatives.hasKeysExcept(a, g, null))touching.add(a);
			if (p.situations.contains(a.type))touching.add(a);
		}
		VisPriority.PriorityPlan plan = VisPriority.nextPriority(touching);
		if (!plan.ok)return fail(o, "priority", PlusDialogs.tr("cpmplus.w5vis.priorityFull"));
		int prio = plan.value;
		String base = p.name == null || p.name.trim().isEmpty() ? PlusDialogs.tr("cpmplus.w5vis.g28.defaultName") : p.name.trim();
		String name = PlusVisibility.uniqueAnimName(base);
		String id = "f" + Long.toString((long) (Math.random() * 1e12), 36);
		PlusVisibility.Edit e = new PlusVisibility.Edit();
		for (Group u : units) {
			e.addGroup(u);
			if (!PlusVisibility.isRoot(u))e.addGroup(u.parent);
		}
		List<Animation> scope = new ArrayList<>();
		for (Animation a : PlusVisibility.anims()) {
			for (Group g : inside) {
				if (W5VisNatives.hasKeysExcept(a, g, null)) {
					scope.add(a);
					break;
				}
			}
		}
		for (Animation a : scope)e.addAnim(a);
		PlusVisibility.addPlanScope(plan, e);
		List<String> missing = new ArrayList<>();
		if (p.createMissing) {
			for (String s : p.situations) {
				boolean exists = false;
				for (Animation a : PlusVisibility.anims())if (s.equals(a.type))exists = true;
				if (!exists)missing.add(s);
			}
		}
		PlusVisibility.run(PlusDialogs.tr("cpmplus.w5vis.g28.undo"), e, ed -> {
			plan.applyRenumber();
			for (Group u : units) {
				List<Object> orig = nodes(u);
				Group c = W5VisNatives.duplicateGroup(u);
				if (c == null)throw new IllegalStateException("CPM+: Group.duplicate is not available");
				List<Object> copy = nodes(c);
				if (copy.size() != orig.size())throw new IllegalStateException("CPM+: the copy of " + u.name + " differs from the original");
				for (Object n : copy) {
					if (n instanceof Group)ed.created((Group) n);
					else if (n instanceof Cube)ed.addElement((Cube) n);// new cubes: Ctrl+Z removes them too
				}
				IdUtil.resetIds(c);// Convention 9: fresh CPM ids for every copied node
				c.name = PlusVisibility.uniqueGroupName(u.name + "_" + asciiName(name));
				o.dupNodes += copy.size();
				for (Animation a : scope) {
					for (int i = 0; i < orig.size(); i++) {
						if (orig.get(i) instanceof Group)o.copiedKeys += W5VisNatives.copyAllKeys(a, orig.get(i), copy.get(i));
					}
				}
				Group fo = W5VisNatives.newGroupAt(PlusVisibility.uniqueGroupName(u.name + "_forma_vis"), u.origin.x, u.origin.y, u.origin.z, u.parent, W5VisNatives.indexInParent(u));
				W5VisNatives.moveInto(u, fo, -1);
				fo.isOpen = true;
				ed.created(fo);
				Group fc = W5VisNatives.newGroupAt(PlusVisibility.uniqueGroupName(c.name + PlusVisibility.VIS_SUFFIX), c.origin.x, c.origin.y, c.origin.z, c.parent, W5VisNatives.indexInParent(c));
				W5VisNatives.moveInto(c, fc, -1);
				fc.isOpen = true;
				fc.hidden = true;
				ed.created(fc);
				o.originals.add(u);
				o.copies.add(c);
				o.origCtrl.add(fo);
				o.copyCtrl.add(fc);
			}
			Animation layer = W5VisNatives.newAnimation(name, "loop", 1, 20);
			ed.created(layer);
			layer.type = "layer";
			layer.additive = true;// forced: a non-additive layer freezes the copy at rest
			layer.layerCtrl = true;
			layer.commandCtrl = false;
			layer.setLayerDefault(p.startOn ? 1 : 0);
			layer.setPriority(prio);
			Map<String, Object> mk = new LinkedHashMap<>();
			mk.put("v", 1.0);
			mk.put("kind", "form");
			mk.put("id", id);
			List<Object> po = new ArrayList<>(), oc = new ArrayList<>(), cc = new ArrayList<>(), cp = new ArrayList<>();
			for (Group g : parts)po.add(g.uuid);
			for (Group g : o.origCtrl)oc.add(g.uuid);
			for (Group g : o.copyCtrl)cc.add(g.uuid);
			for (Group g : o.copies)cp.add(g.uuid);
			mk.put("parts", po);
			mk.put("orig", oc);
			mk.put("copy", cc);
			mk.put("copies", cp);
			List<Object> sit = new ArrayList<>(p.situations);
			mk.put("situations", sit);
			Js.asPropertyMap(layer).set(PlusVisibility.RULE_PROP, JsonUtil.toJson(mk));
			for (Group g : o.origCtrl)PlusVisibility.setConstant(layer, g, false);
			for (Group g : o.copyCtrl)PlusVisibility.setConstant(layer, g, true);
			o.layer = layer;
			for (String s : missing) {
				Animation a = W5VisNatives.newAnimation(PlusVisibility.uniqueAnimName(PlusDialogs.tr("cpmplus.w5vis.g28.sit." + s)), "loop", 1, 20);
				ed.created(a);
				a.type = s;
				a.additive = true;
				o.created.add(a);
			}
		});
		o.ok = true;
		o.id = id;
		o.priority = prio;
		o.renumbered = plan.renumber.size();
		LayerCapacity.Result lc = LayerCapacity.checkProject();
		if (!lc.ok())o.layerWarning = PlusDialogs.trf("cpmplus.w5vis.layerCapacity", lc.count, lc.freeLayers);
		return o;
	}

	/** ASCII letters / digits of s (group name suffix), 'forma' when nothing is left */
	static String asciiName(String s) {
		StringBuilder sb = new StringBuilder();
		for (char ch : s.toCharArray()) {
			if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9'))sb.append(ch);
			else if (ch == ' ' || ch == '_' || ch == '-')sb.append('_');
		}
		String r = sb.toString();
		return r.isEmpty() ? "forma" : r;
	}

	// ------------------------------------------------------------------ export checks

	public static void check(ExportContext ctx) {
		for (Map<String, Object> f : forms()) {
			Animation layer = (Animation) f.get("_anim");
			List<String> missing = new ArrayList<>();
			List<Group> ctrls = new ArrayList<>();
			for (String k : new String[] {"orig", "copy"}) {
				for (String u : list(f, k)) {
					Group g = PlusVisibility.byUuid(u);
					if (g == null)missing.add(u);
					else ctrls.add(g);
				}
			}
			if (!missing.isEmpty()) {
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, WARN_BROKEN, "cpmplus.w5vis.g28.broken", layer.name);
				ctx.add(w);
			}
			List<Animation> over = new ArrayList<>();
			for (Animation a : PlusVisibility.anims()) {
				if (a == layer || Reactions.marker(a) != null)continue;
				for (Group g : ctrls) {
					if (W5VisNatives.hasKeysExcept(a, g, null) && a.getPriority() >= layer.getPriority()) {
						over.add(a);
						break;
					}
				}
			}
			if (!over.isEmpty()) {
				List<String> n = new ArrayList<>();
				for (Animation a : over)n.add(a.name);
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, WARN_FLICKER, "cpmplus.w5vis.g28.flicker", layer.name, String.join(", ", n));
				w.setFix(() -> raise(layer, over), FixKind.DETERMINISTIC);
				w.setFixPreview(PlusDialogs.trf("cpmplus.w5vis.g28.raisePreview", layer.name));
				ctx.add(w);
			}
		}
	}

	private static Promise<Boolean> raise(Animation layer, List<Animation> over) {
		try {
			VisPriority.PriorityPlan plan = PlusVisibility.priorityAbove(over, layer.getPriority());
			if (!plan.ok) {
				PlusDialogs.block(PlusDialogs.tr("cpmplus.w5vis.priorityFull"));
				return Promise.resolve(false);
			}
			int old = layer.getPriority(), p = plan.value;
			List<Animation> l = new ArrayList<>();
			l.add(layer);
			for (Animation a : plan.renumber.keySet())if (!l.contains(a))l.add(a);
			PlusVisibility.editAnims(l, PlusDialogs.tr("cpmplus.w5vis.g28.undoRaise"), () -> {
				plan.applyRenumber();
				layer.setPriority(p);
			});
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w5vis.g37.raised", String.valueOf(old), String.valueOf(p)));
			return Promise.resolve(true);
		} catch (Throwable e) {
			PopupDialogs.displayError(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w5vis.g28.title")), e);
			return Promise.resolve(false);
		}
	}

	// ------------------------------------------------------------------ UI

	/** element count of g's subtree in a converted model (what the copy adds), -1 when unknown */
	static int exportedCount(BlockbenchExport exp, Group g) {
		ModelElement me = exp.getUuidLookup().get(g.uuid);
		if (me == null)return -1;
		int[] n = new int[1];
		count(me, n);
		return n[0];
	}

	private static void count(ModelElement me, int[] n) {
		n[0]++;
		if (me.children != null)for (ModelElement c : me.children)count(c, n);
	}

	public static void open() {
		List<Group> sel = PlusVisibility.selectedGroups();
		if (sel.isEmpty()) {
			PlusDialogs.quick(PlusDialogs.tr("cpmplus.w5vis.selectFirst"), 2500);
			return;
		}
		for (Group g : sel)if (PlusVisibility.isRoot(g)) {
			PlusDialogs.block(PlusDialogs.trf("cpmplus.w5vis.g28.root", g.name));
			return;
		}
		PlusTestHooks.track(ProjectConvert.convertHeadless().then(h -> {
			int total = BlockbenchExport.countExportedParts(h.editor);
			int dup = 0;
			for (Group g : PlusVisibility.topmost(sel)) {
				int c = exportedCount(h.exp, unitOf(g));
				dup += (c < 0 ? nodes(unitOf(g)).size() : c) + 2;
			}
			openDialog(sel, dup, total);
			return Promise.resolve((Void) null);
		}, err -> {
			openDialog(sel, -1, -1);
			return Promise.resolve((Void) null);
		}));
	}

	private static void openDialog(List<Group> sel, int dup, int total) {
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w5vis.g28.intro"));
		rows.add(FormRow.text("name", "cpmplus.w5vis.g28.name", PlusVisibility.capitalize(sel.get(0).name) + " " + PlusDialogs.tr("cpmplus.w5vis.g28.suffix")));
		Map<String, String> parts = new LinkedHashMap<>();
		for (Group g : Group.all)if (!PlusVisibility.isRoot(g) && !g.is_rotation_subgroup)parts.put(g.uuid, g.name);
		List<String> pre = new ArrayList<>();
		for (Group g : sel)pre.add(g.uuid);
		rows.add(FormRow.multiSelect("parts", "cpmplus.w5vis.g28.parts", parts, pre));
		for (String s : SITUATIONS) {
			boolean exists = false;
			for (Animation a : PlusVisibility.anims())if (s.equals(a.type))exists = true;
			rows.add(FormRow.checkbox("sit_" + s, "cpmplus.w5vis.g28.sit." + s, exists));
		}
		rows.add(FormRow.checkbox("startOn", "cpmplus.w5vis.g28.startOn", false));
		rows.add(FormRow.info("copyAll", null, "cpmplus.w5vis.g28.copyAll"));
		if (dup >= 0 && total >= 0) {
			int after = total + dup;
			String cost = PlusDialogs.trf("cpmplus.w5vis.g28.cost", String.valueOf(dup), String.valueOf(after));
			if (after > 256) {
				cost += "\n" + PlusDialogs.tr("cpmplus.w5vis.g28.costAmber");
				rows.add(FormRow.htmlInfo("cost", null, "<span style='color: var(--color-warning, #e6a23c);'>" + PlusDialogs.escMdLines(cost) + "</span>"));
			} else rows.add(FormRow.info("cost", null, cost));
		}
		FormOpts fo = new FormOpts();
		fo.confirmKey = "cpmplus.w5vis.g28.create";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form("cpmplus_w5vis_form", "cpmplus.w5vis.g28.title", rows, r -> {
			Params p = new Params();
			p.name = r.getString("name");
			p.parts.addAll(Reactions.groupsOf(r.getList("parts")));
			for (String s : SITUATIONS)if (r.getBool("sit_" + s))p.situations.add(s);
			p.startOn = r.getBool("startOn");
			Out o = create(p);
			if (!o.ok) {
				r.keepOpen();
				PlusDialogs.block(o.error);
				return;
			}
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w5vis.g28.done", o.layer.name, String.valueOf(o.priority)));
			List<String> notes = new ArrayList<>();
			if (o.layerWarning != null)notes.add(o.layerWarning);
			if (!notes.isEmpty())PlusDialogs.info("cpmplus_w5vis_form_notes", "cpmplus.w5vis.g28.title", notes);
		}, fo);
	}
}
