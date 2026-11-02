package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.web.client.util.I18n;

import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W3-COPYXF, C2): 'Copiar movimento de...' - the Copy Transform editor of a group.
 * <ul>
 * <li>Target: exactly one selected group that is not a root (CPM only loads a copy transform on children; roots
 * ignore it).</li>
 * <li>Sources: groups only (the export resolves the uuid through its group lookup), excluding the target itself, its
 * descendants, generated rotation subgroups, groups that are not exported, unknown top-level groups and every group
 * whose own copy-transform chain leads back to the target (a cycle).</li>
 * <li>The group property cpm_copy_transform gets EXACTLY the keys CopyTransformEffect.load reads plus 'uuid' and both
 * colour keys: uuid, px, py, pz, rx, ry, rz, sx, sy, sz, cv, color, r, additive, multiply (no storeID: the export
 * resolves the uuid). Every change is ONE undo entry; Remove writes "" (never undefined: Blockbench's undo cannot put
 * a missing value back).</li>
 * <li>Export checks: a copy transform whose source is gone / not exported (dangling uuid) is a warning with the fix
 * 'Remover'; one on a root group is an info row (CPM ignores it there).</li>
 * </ul>
 */
public final class CopyTransformEditor {
	public static final String ACTION_ID = "cpmplus_copy_transform";
	public static final String DIALOG_ID = "cpmplus_w3copyxf_ct";
	/** WarnEntry key: the source group of a copy transform does not exist (or is not exported) */
	public static final String WARN_MISSING = "w3copyxf.ctMissing";
	/** WarnEntry key: a copy transform on a root group (ignored by CPM) */
	public static final String WARN_ROOT = "w3copyxf.ctOnRoot";
	/** the boolean flags of the dialog, in dialog order ('color' is written as 'color' and 'r') */
	public static final String[] FLAGS = {"px", "py", "pz", "rx", "ry", "rz", "sx", "sy", "sz", "cv", "color", "additive"};

	private CopyTransformEditor() {
	}

	// ====================================================================== data

	/** one copy transform (the JSON of cpm_copy_transform) */
	public static final class Ct {
		public String uuid;
		public final Map<String, Boolean> flags = new LinkedHashMap<>();
		public double multiply = 1;

		public Ct() {
			for (String f : FLAGS)flags.put(f, false);
		}

		public boolean get(String flag) {
			Boolean b = flags.get(flag);
			return b != null && b;
		}

		public Ct set(String flag, boolean v) {
			flags.put(flag, v);
			return this;
		}

		/** every channel copied (position, rotation, scale, visibility, colour), additive off, multiply 1 */
		public static Ct all(String uuid) {
			Ct c = new Ct();
			c.uuid = uuid;
			for (String f : FLAGS)c.flags.put(f, !"additive".equals(f));
			c.multiply = 1;
			return c;
		}
	}

	/** the JSON written into cpm_copy_transform (exact key set and order) */
	public static String toJson(Ct ct) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("uuid", ct.uuid);
		for (String f : new String[] {"px", "py", "pz", "rx", "ry", "rz", "sx", "sy", "sz", "cv"})m.put(f, ct.get(f));
		m.put("color", ct.get("color"));
		m.put("r", ct.get("color"));// the key the game reads (B1)
		m.put("additive", ct.get("additive"));
		m.put("multiply", ct.multiply);
		return JsonUtil.toJson(m);
	}

	/** the copy transform of a group, or null when it has none / it can't be read */
	public static Ct read(Group g) {
		String s = g != null ? g.copyTransform : null;
		if (s == null || s.isEmpty() || !"string".equals(Js.typeof(s)))return null;
		try {
			JsonMap m = JsonUtil.fromJson(s);
			Ct c = new Ct();
			Object u = m.get("uuid");
			c.uuid = u != null ? String.valueOf(u) : null;
			for (String f : FLAGS) {
				if ("color".equals(f))continue;
				c.flags.put(f, m.getBoolean(f, false));
			}
			// same rule as the export (B1): 'r' is what the game reads, CPM's editor writes 'color'
			boolean color = m.containsKey("r") ? m.getBoolean("r", false) || m.getBoolean("color", false) : m.getBoolean("color", false);
			c.flags.put("color", color);
			c.multiply = m.getFloat("multiply", 1f);
			return c;
		} catch (Throwable e) {
			return null;
		}
	}

	/** true when the group carries a (non-empty) copy transform string */
	public static boolean has(Group g) {
		String s = g != null ? g.copyTransform : null;
		return s != null && "string".equals(Js.typeof(s)) && !s.isEmpty();
	}

	// ====================================================================== selection rules

	public static boolean isRoot(Group g) {
		return g != null && g.parent == Group.ROOT;
	}

	/** group exported: its own export flag and every ancestor's */
	public static boolean isExported(Group g) {
		Object n = g;
		int guard = 0;
		while (n instanceof Group && guard++ < 10000) {
			Group x = (Group) n;
			if (!x.export)return false;// same test as the export
			if (x.parent == Group.ROOT)return x.getRootType() != null;
			n = x.parent;
		}
		return n == Group.ROOT;
	}

	public static Group byUuid(String uuid) {
		if (uuid == null || Group.all == null)return null;
		for (Group g : Group.all)if (uuid.equals(g.uuid))return g;
		return null;
	}

	/** true when following source's copy-transform chain reaches target (choosing source would make a cycle) */
	public static boolean leadsTo(Group source, Group target) {
		Set<String> seen = new HashSet<>();
		Group cur = source;
		while (cur != null && seen.add(cur.uuid)) {
			if (cur == target)return true;
			Ct c = read(cur);
			if (c == null || c.uuid == null)return false;
			cur = byUuid(c.uuid);
		}
		return false;
	}

	/** groups the target may copy from (see the class comment) */
	public static List<Group> sources(Group target) {
		List<Group> out = new ArrayList<>();
		if (Group.all == null)return out;
		for (Group g : Group.all) {
			if (g == target)continue;
			if (g.is_rotation_subgroup)continue;
			if (target != null && isDescendant(g, target))continue;
			if (!isExported(g))continue;
			if (target != null && leadsTo(g, target))continue;
			out.add(g);
		}
		return out;
	}

	static boolean isDescendant(Group node, Group ancestor) {
		Object p = node.parent;
		int guard = 0;
		while (p instanceof Group && guard++ < 10000) {
			if (p == ancestor)return true;
			p = ((Group) p).parent;
		}
		return false;
	}

	/** 'body › asa › pena' */
	public static String pathName(Group g) {
		List<String> parts = new ArrayList<>();
		Object n = g;
		int guard = 0;
		while (n instanceof Group && guard++ < 64) {
			parts.add(0, ((Group) n).name);
			n = ((Group) n).parent;
		}
		return String.join(" › ", parts);
	}

	// ====================================================================== edits (one undo entry each)

	/** cpm_copy_transform is registered with an undefined default: make it "" so Ctrl+Z can restore 'none' */
	static native void prepareProp(Group g) /*-{
		if (g && (g.cpm_copy_transform === undefined || g.cpm_copy_transform === null)) g.cpm_copy_transform = '';
	}-*/;

	static UndoData groupsData(Group... g) {
		UndoData d = new UndoData();
		d.groups = g;
		d.elements = new OutlinerElement[0];
		return d;
	}

	/** writes ct into target (one undo entry 'Copiar movimento') */
	public static void apply(Group target, Ct ct) {
		prepareProp(target);
		String json = toJson(ct);
		PlusUndo.run(I18n.get("cpmplus.w3copyxf.ct.undo"), groupsData(target), () -> groupsData(target), () -> target.copyTransform = json);
	}

	/** removes the copy transform of target (one undo entry) */
	public static void remove(Group target) {
		prepareProp(target);
		PlusUndo.run(I18n.get("cpmplus.w3copyxf.ct.undoRemove"), groupsData(target), () -> groupsData(target), () -> target.copyTransform = "");
	}

	// ====================================================================== dialog

	/** the action: one selected non-root group */
	public static void open() {
		List<Group> sel = GroupEffects.selectedGroups();
		if (sel.size() != 1) {
			PlusDialogs.block("cpmplus.w3copyxf.ct.selectOne");
			return;
		}
		Group target = sel.get(0);
		if (isRoot(target)) {
			PlusDialogs.block(PlusDialogs.trf("cpmplus.w3copyxf.ct.rootTarget", target.name));
			return;
		}
		open(target);
	}

	public static void open(Group target) {
		List<Group> src = sources(target);
		Ct cur = read(target);
		boolean existing = has(target);
		if (src.isEmpty() && !existing) {
			PlusDialogs.block("cpmplus.w3copyxf.ct.noSources");
			return;
		}
		Map<String, String> opts = new LinkedHashMap<>();
		for (Group g : src)opts.put(g.uuid, pathName(g) + (isRoot(g) ? " " + I18n.get("cpmplus.w3copyxf.ct.rootMark") : ""));
		String value = cur != null && cur.uuid != null && opts.containsKey(cur.uuid) ? cur.uuid : null;
		Ct v = cur != null ? cur : new Ct();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf("cpmplus.w3copyxf.ct.intro", target.name)));
		if (cur != null && cur.uuid != null && value == null)
			rows.add(FormRow.info("lost", null, PlusDialogs.trf("cpmplus.w3copyxf.ct.lostSource", target.name)));
		if (!opts.isEmpty())rows.add(FormRow.select("source", "cpmplus.w3copyxf.ct.source", opts, value != null ? value : opts.keySet().iterator().next()));
		rows.add(FormRow.checkbox("px", "cpmplus.w3copyxf.ct.px", v.get("px")));
		rows.add(FormRow.checkbox("py", "cpmplus.w3copyxf.ct.py", v.get("py")));
		rows.add(FormRow.checkbox("pz", "cpmplus.w3copyxf.ct.pz", v.get("pz")));
		rows.add(FormRow.checkbox("rx", "cpmplus.w3copyxf.ct.rx", v.get("rx")));
		rows.add(FormRow.checkbox("ry", "cpmplus.w3copyxf.ct.ry", v.get("ry")));
		rows.add(FormRow.checkbox("rz", "cpmplus.w3copyxf.ct.rz", v.get("rz")));
		rows.add(FormRow.checkbox("sx", "cpmplus.w3copyxf.ct.sx", v.get("sx")));
		rows.add(FormRow.checkbox("sy", "cpmplus.w3copyxf.ct.sy", v.get("sy")));
		rows.add(FormRow.checkbox("sz", "cpmplus.w3copyxf.ct.sz", v.get("sz")));
		rows.add(FormRow.checkbox("cv", "cpmplus.w3copyxf.ct.cv", v.get("cv")));
		rows.add(FormRow.checkbox("color", "cpmplus.w3copyxf.ct.color", v.get("color")));
		rows.add(FormRow.checkbox("additive", "cpmplus.w3copyxf.ct.additive", v.get("additive")).description("cpmplus.w3copyxf.ct.additive.desc"));
		rows.add(FormRow.decimal("multiply", "cpmplus.w3copyxf.ct.multiply", v.multiply, -100, 100, 0.05).description("cpmplus.w3copyxf.ct.multiply.desc"));
		rows.add(FormRow.info("note", null, "cpmplus.w3copyxf.ct.notInPreview"));

		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = DIALOG_ID;
		d.title = I18n.get("cpmplus.w3copyxf.ct.title");
		d.form = PlusDialogs.formObject(rows);
		d.width = 520;
		if (existing) {
			d.buttons = new String[] {I18n.get("cpmplus.w3copyxf.btn.apply"), I18n.get("cpmplus.w3copyxf.ct.btnRemove"), I18n.get("cpmplus.w0binfra.btn.cancel")};
			d.confirmIndex = 0;
			d.cancelIndex = 2;
		} else {
			d.buttons = new String[] {I18n.get("cpmplus.w3copyxf.btn.apply"), I18n.get("cpmplus.w0binfra.btn.cancel")};
			d.confirmIndex = 0;
			d.cancelIndex = 1;
		}
		d.onConfirm = res -> {
			try {
				return confirm(target, new FormResult(res));
			} catch (Throwable e) {
				com.tom.cpm.blockbench.util.PopupDialogs.displayError(PlusDialogs.esc(d.title), e);
				return true;
			}
		};
		d.onButton = i -> {
			if (existing && i == 1) {
				try {
					remove(target);
					PlusDialogs.done(PlusDialogs.trf("cpmplus.w3copyxf.ct.doneRemove", target.name));
				} catch (Throwable e) {
					com.tom.cpm.blockbench.util.PopupDialogs.displayError(PlusDialogs.esc(d.title), e);
				}
			}
			return true;
		};
		new Dialog(d).show();
	}

	/** @return false to keep the dialog open */
	private static boolean confirm(Group target, FormResult r) {
		String uuid = r.getString("source");
		Group src = byUuid(uuid);
		if (src == null || !sources(target).contains(src)) {
			PlusDialogs.block("cpmplus.w3copyxf.ct.badSource");
			return false;
		}
		Ct ct = new Ct();
		ct.uuid = src.uuid;
		for (String f : FLAGS)ct.set(f, r.getBool(f));
		double m = r.getDouble("multiply", 1);
		if (Double.isNaN(m) || Double.isInfinite(m))m = 1;
		ct.multiply = m;
		apply(target, ct);
		boolean any = false;
		for (String f : FLAGS)if (!"additive".equals(f) && ct.get(f))any = true;
		PlusDialogs.done(PlusDialogs.trf("cpmplus.w3copyxf.ct.done", target.name, src.name));
		if (!any)PlusDialogs.toast(I18n.get("cpmplus.w3copyxf.ct.nothingTicked"), "info", 8000, null);
		return true;
	}

	// ====================================================================== export checks + fixes

	public static void installChecks() {
		ExportHooks.register(ctx -> {
			if (Group.all == null)return;
			Map<String, ?> lookup = ctx.export.getUuidLookup();
			for (Group g : Group.all) {
				if (!has(g) || g.is_rotation_subgroup)continue;
				if (isRoot(g)) {
					if (g.getRootType() == null || !isExported(g))continue;
					WarnEntry w = WarnFactory.of(Severity.INFO, WARN_ROOT, "cpmplus.w3copyxf.warn.ctOnRoot", g.name);
					w.addNode(g.uuid, g.name);
					ctx.add(w);
					continue;
				}
				if (!lookup.containsKey(g.uuid))continue;// the target itself is not exported
				Ct c = read(g);
				if (c != null && c.uuid != null && lookup.containsKey(c.uuid))continue;
				WarnEntry w = WarnFactory.of(Severity.DIFFERS, WARN_MISSING, "cpmplus.w3copyxf.warn.ctMissing", g.name);
				w.setConsequence("cpmplus.w3copyxf.warn.ctMissing.consequence");
				w.addNode(g.uuid, g.name);
				ctx.add(w);
			}
		});
		WarnFixes.FixFactory removeFix = entry -> {
			List<String> uuids = entry.getUuids();
			if (uuids.isEmpty())return null;
			String uuid = uuids.get(0);
			return () -> new Promise<>((res, rej) -> {
				try {
					Group g = byUuid(uuid);
					if (g == null || !has(g)) {
						res.onInvoke(false);
						return;
					}
					remove(g);
					PlusDialogs.done(PlusDialogs.trf("cpmplus.w3copyxf.ct.doneRemove", g.name));
					res.onInvoke(true);
				} catch (Throwable e) {
					rej.onInvoke(e);
				}
			});
		};
		WarnFixes.register(WARN_MISSING, "cpmplus.w3copyxf.fix.removeCt", 10, removeFix);
		WarnFixes.register(WARN_ROOT, "cpmplus.w3copyxf.fix.removeCt", 10, removeFix);
	}
}
