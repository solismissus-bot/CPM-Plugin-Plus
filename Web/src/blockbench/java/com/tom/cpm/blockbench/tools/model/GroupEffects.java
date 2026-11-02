package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpl.util.ItemSlot;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Outliner;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.promise.Promise;

/**
 * CPM+ (W3-COPYXF, C5 + G20): group effects that CPM has but Blockbench had no button for.
 * <ul>
 * <li>'Sem animação do Minecraft' (cpm_dva, CPM's 'Disable vanilla animations' root effect): a toggle that is only
 * enabled while every selected group is a root part (parent = outliner root and a known part name); mixed values
 * show unchecked, a click sets all. One undo entry. Only visible in game.</li>
 * <li>'Escolher slot de item...': makes a non-root group an item hold slot (GroupData item = lower-case ItemSlot
 * name), or a normal group again ('Nenhum'). Warns when the group has content (CPM does not draw it).</li>
 * <li>'Criar cópia da parte do corpo...' (G20): a top-level group named EXACTLY '&lt;part&gt;_dup' (the export only
 * recognises the suffix '_dup'), only for the 6 player parts, 'Sem animação do Minecraft' on by default, optionally
 * moving the selected non-root nodes into it (only when that keeps them where they are). Reuses an existing copy.</li>
 * <li>Export checks: '&lt;part&gt;_dup&lt;N&gt;' top-level groups (Blockbench's unique names) with a rename fix, and
 * cpm_dva on a group that is not a root (ignored by CPM) with a fix that switches it off.</li>
 * </ul>
 */
public final class GroupEffects {
	public static final String DVA_ID = "cpmplus_dva";
	public static final String ITEM_SLOT_ID = "cpmplus_item_slot";
	public static final String DUP_ID = "cpmplus_make_dup_root";
	public static final String ITEM_SLOT_DIALOG = "cpmplus_w3copyxf_itemslot";
	public static final String DUP_DIALOG = "cpmplus_w3copyxf_dup";
	public static final String WARN_DUP_NUMBERED = "w3copyxf.dupNumbered";
	public static final String WARN_DVA_NOT_ROOT = "w3copyxf.dvaNotRoot";
	/** the 6 player parts a '_dup' copy exists for (CPM: ModelPartDupRoot only for PlayerModelParts) */
	public static final PlayerModelParts[] DUP_PARTS = {PlayerModelParts.HEAD, PlayerModelParts.BODY, PlayerModelParts.LEFT_ARM,
			PlayerModelParts.RIGHT_ARM, PlayerModelParts.LEFT_LEG, PlayerModelParts.RIGHT_LEG};
	/** '<part>_dup' for a top-level group named '<part>_dup<digits>' (Blockbench's unique names), else null */
	public static String numberedDupFix(String name) {
		if (name == null)return null;
		String l = name.toLowerCase(Locale.ROOT);
		for (PlayerModelParts p : DUP_PARTS) {
			String pre = p.getName() + "_dup";
			if (!l.startsWith(pre) || l.length() == pre.length())continue;
			boolean digits = true;
			for (int i = pre.length(); i < l.length(); i++)if (l.charAt(i) < '0' || l.charAt(i) > '9')digits = false;
			if (digits)return pre;
		}
		return null;
	}

	static Toggle dvaToggle;

	private GroupEffects() {
	}

	// ====================================================================== selection

	private static native JsArray<Group> selectedGroups0() /*-{
		var out = [];
		try {
			var P = $wnd.Project, G = $wnd.Group;
			if (!G) return out;
			// only real groups (Group.selected / first_selected differ between Blockbench versions: array, group or undefined)
			var add = function(x) { if (x && x instanceof G && out.indexOf(x) < 0) out.push(x); };
			var addAll = function(a) {
				if (!a) return;
				if (a instanceof G) { add(a); return; }
				if (typeof a.length === 'number') for (var i = 0; i < a.length; i++) add(a[i]);
			};
			addAll(P && P.selected_groups);
			if (!out.length) addAll(G.multi_selected);
			if (!out.length) addAll(G.first_selected);
			if (!out.length) addAll(G.selected);
		} catch (e) {}
		return out;
	}-*/;

	/** every selected group (Blockbench 5 multi-selection) */
	public static List<Group> selectedGroups() {
		List<Group> l = new ArrayList<>();
		JsArray<Group> a = selectedGroups0();
		for (int i = 0; i < a.length; i++)l.add(a.getAt(i));
		return l;
	}

	/** a root part: parent = outliner root and a known part name (also '_dup' copies) */
	public static boolean isRootPart(Group g) {
		return g != null && g.parent == Group.ROOT && g.getRootType() != null;
	}

	public static boolean allSelectedRoots() {
		List<Group> s = selectedGroups();
		if (s.isEmpty())return false;
		for (Group g : s)if (!isRootPart(g))return false;
		return true;
	}

	static UndoData groupsData(List<Group> l) {
		UndoData d = new UndoData();
		d.groups = l.toArray(new Group[0]);
		d.elements = new OutlinerElement[0];
		return d;
	}

	// ====================================================================== 'Sem animação do Minecraft'

	/** toggle value for the current selection: on only when every selected root has it (mixed = off) */
	public static boolean dvaValue() {
		List<Group> s = selectedGroups();
		if (s.isEmpty())return false;
		for (Group g : s)if (!g.disableVanillaAnim)return false;
		return true;
	}

	public static void syncDva() {
		if (dvaToggle == null)return;
		try {
			dvaToggle.value = allSelectedRoots() && dvaValue();
			dvaToggle.updateEnabledState();
		} catch (Throwable e) {
		}
	}

	/** sets cpm_dva on every given root (one undo entry) */
	public static void setDva(List<Group> groups, boolean v) {
		PlusUndo.run(I18n.get("cpmplus.w3copyxf.dva.undo"), groupsData(groups), () -> groupsData(groups), () -> {
			for (Group g : groups)g.disableVanillaAnim = v;
		});
	}

	/** the toggle (registered by W3CopyxfInstall); null after unload */
	public static void setToggle(Toggle t) {
		dvaToggle = t;
	}

	/** click on the toggle: v = the new value */
	public static void onDvaToggle(boolean v) {
		List<Group> s = selectedGroups();
		if (s.isEmpty() || !allSelectedRoots()) {
			syncDva();
			PlusDialogs.quick(I18n.get("cpmplus.w3copyxf.dva.onlyRoots"), 3500);
			return;
		}
		setDva(s, v);
		syncDva();
		PlusDialogs.done(PlusDialogs.trf(v ? "cpmplus.w3copyxf.dva.doneOn" : "cpmplus.w3copyxf.dva.doneOff", names(s)));
	}

	static String names(List<Group> l) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < l.size() && i < 6; i++) {
			if (i > 0)sb.append(", ");
			sb.append(l.get(i).name);
		}
		if (l.size() > 6)sb.append(" (+").append(l.size() - 6).append(")");
		return sb.toString();
	}

	// ====================================================================== item slot

	/** 'none' + every ItemSlot (lower-case names, the value GroupData stores) */
	public static Map<String, String> slotOptions() {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("none", "cpmplus.w3copyxf.slot.none");
		for (ItemSlot s : ItemSlot.VALUES) {
			String n = s.name().toLowerCase(Locale.ROOT);
			m.put(n, "cpmplus.w3copyxf.slot." + n);
		}
		return m;
	}

	/** true when CPM would ignore something inside the group if it became an item slot */
	public static boolean hasContent(Group g) {
		if (g.children == null)return false;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (n instanceof Group)return true;
			if (n instanceof Cube) {
				Cube c = (Cube) n;
				boolean zero = Math.abs(c.to.x - c.from.x) < 1e-4f && Math.abs(c.to.y - c.from.y) < 1e-4f && Math.abs(c.to.z - c.from.z) < 1e-4f;
				if (c.export && !zero)return true;
			}
		}
		return false;
	}

	/** slot = lower-case ItemSlot name, or null / 'none' to make it a normal group again (one undo entry) */
	public static void setItemSlot(Group g, String slot) {
		String v = slot == null || "none".equals(slot) ? null : slot.toLowerCase(Locale.ROOT);
		List<Group> l = new ArrayList<>();
		l.add(g);
		PlusUndo.run(I18n.get("cpmplus.w3copyxf.slot.undo"), groupsData(l), () -> groupsData(l), () -> {
			GroupData gd = g.getData();
			gd.setItemRenderer(v);// null removes the 'item' key (flushed by PlusUndo)
		});
	}

	public static void openItemSlot() {
		List<Group> s = selectedGroups();
		if (s.size() != 1) {
			PlusDialogs.block("cpmplus.w3copyxf.slot.selectOne");
			return;
		}
		Group g = s.get(0);
		if (g.parent == Group.ROOT) {
			PlusDialogs.block(PlusDialogs.trf("cpmplus.w3copyxf.slot.rootRefused", g.name));
			return;
		}
		String cur = g.getData().getItemRenderer();
		Map<String, String> opts = slotOptions();
		String value = cur != null && opts.containsKey(cur.toLowerCase(Locale.ROOT)) ? cur.toLowerCase(Locale.ROOT) : "none";
		boolean content = hasContent(g);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf("cpmplus.w3copyxf.slot.intro", g.name)));
		rows.add(FormRow.select("slot", "cpmplus.w3copyxf.slot.label", opts, value));
		if (content)rows.add(FormRow.info("content", null, "cpmplus.w3copyxf.slot.hasContent").when(r -> !"none".equals(r.getString("slot"))));
		PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
		fo.confirmKey = "cpmplus.w3copyxf.btn.apply";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form(ITEM_SLOT_DIALOG, "cpmplus.w3copyxf.slot.title", rows, r -> {
			String slot = r.getString("slot");
			if (slot == null)slot = "none";
			if (slot.equals(value))return;
			final String fs = slot;
			Runnable doIt = () -> {
				setItemSlot(g, fs);
				PlusDialogs.done("none".equals(fs) ? PlusDialogs.trf("cpmplus.w3copyxf.slot.doneNone", g.name) :
					PlusDialogs.trf("cpmplus.w3copyxf.slot.done", g.name, I18n.get("cpmplus.w3copyxf.slot." + fs)));
			};
			if (!"none".equals(slot) && content)PlusDialogs.warnContinue(PlusDialogs.trf("cpmplus.w3copyxf.slot.contentWarn", g.name), doIt);
			else doIt.run();
		}, fo);
	}

	// ====================================================================== 'Criar cópia da parte do corpo' (G20)

	/** what makeDup() did */
	public static final class DupResult {
		public Group group;
		public boolean created, dvaChanged;
		public final List<Object> moved = new ArrayList<>();
		public final List<Object> notMoved = new ArrayList<>();
	}

	/** nodes that may be moved into a copy: selected groups / cubes that are not roots, top-most only */
	public static List<Object> moveCandidates(Group target) {
		List<Object> sel = new ArrayList<>();
		for (Group g : selectedGroups())if (g.parent != Group.ROOT)sel.add(g);
		if (Outliner.selected != null)for (OutlinerElement e : Outliner.selected)if (e instanceof Cube && !sel.contains(e))sel.add(e);
		// a selected ROOT says which body part (Blockbench also selects everything inside it): its contents are not
		// pieces to move, or 'Criar cópia' on the body would move every body cube into the copy
		List<Group> selRoots = new ArrayList<>();
		for (Group g : selectedGroups())if (g.parent == Group.ROOT)selRoots.add(g);
		List<Object> out = new ArrayList<>();
		for (Object n : sel) {
			if (target != null && (n == target || RootUtil.isDescendant(n, target)))continue;
			boolean inSelRoot = false;
			for (Group r : selRoots)if (RootUtil.isDescendant(n, r))inSelRoot = true;
			if (inSelRoot)continue;
			boolean covered = false;
			for (Object o : sel)if (o != n && o instanceof Group && RootUtil.isDescendant(n, (Group) o))covered = true;
			if (!covered)out.add(n);
		}
		return out;
	}

	/** true when moving node into a root without rotation keeps it where it is (no rotated group above it) */
	static boolean keepsWorldPosition(Object node) {
		Object p = node instanceof Group ? ((Group) node).parent : node instanceof Cube ? ((Cube) node).parent : null;
		int guard = 0;
		while (p instanceof Group && guard++ < 10000) {
			Group g = (Group) p;
			if (g.rotation != null && !(g.rotation.x == 0 && g.rotation.y == 0 && g.rotation.z == 0))return false;
			p = g.parent;
		}
		return true;
	}

	private static native int rootIndexOf(Object node) /*-{
		try { var r = $wnd.Outliner && $wnd.Outliner.root; return r ? r.indexOf(node) : -1; } catch (e) { return -1; }
	}-*/;

	/** creates (or reuses) '<part>_dup' with dva, optionally moving nodes into it: ONE undo entry */
	public static DupResult makeDup(PlayerModelParts part, boolean dva, List<Object> move) {
		DupResult res = new DupResult();
		String name = part.getName() + "_dup";
		Group existing = RootUtil.findRootGroup(name);
		List<Group> before = new ArrayList<>();
		if (existing != null)before.add(existing);
		List<OutlinerElement> movedCubes = new ArrayList<>();
		// the pieces that will move (and their current parents) are part of the entry BEFORE the change, so Blockbench
		// records a real move (and Ctrl+Z puts them back) instead of seeing them as new elements
		List<OutlinerElement> movingCubes = new ArrayList<>();
		if (move != null) {
			for (Object n : move) {
				if (n instanceof Cube) {
					movingCubes.add((Cube) n);
					Object par = ((Cube) n).parent;
					if (par instanceof Group && !before.contains(par))before.add((Group) par);
				} else if (n instanceof Group) {
					if (!before.contains(n))before.add((Group) n);
					Object par = ((Group) n).parent;
					if (par instanceof Group && !before.contains(par))before.add((Group) par);
				}
			}
		}
		// every group of the 'before' entry (the old parents too) must be in the 'after' entry: a group Blockbench sees
		// only before the change counts as removed, and redo deleted the body the pieces came from
		List<Group> after = new ArrayList<>(before);
		UndoData b = PlusUndo.structAfter(before, movingCubes, null);
		PlusUndo.run(I18n.get("cpmplus.w3copyxf.dup.undo"), b, () -> PlusUndo.structAfter(after, movedCubes, null), () -> {
			Group g = existing;
			if (g == null) {
				g = RootUtil.makeRoot(part.getName(), RootUtil.projectSkinType());
				g.name = name;// exactly '<part>_dup': never a numeric suffix
				Group orig = RootUtil.findPart(part);
				int idx = orig != null ? rootIndexOf(orig) : -1;
				g.addTo(Group.ROOT, idx >= 0 ? idx + 1 : -1);
				g.isOpen = true;
				IdUtil.resetIds(g);
				res.created = true;
				after.add(g);
			}
			if (g.disableVanillaAnim != dva) {
				g.disableVanillaAnim = dva;
				res.dvaChanged = true;
			}
			if (move != null) {
				for (Object n : move) {
					if (n instanceof Group && ((Group) n).parent == Group.ROOT) {
						res.notMoved.add(n);// never a root inside a root
						continue;
					}
					if (n == g || (n instanceof Group && RootUtil.isDescendant(g, (Group) n))) {
						res.notMoved.add(n);
						continue;
					}
					if (!keepsWorldPosition(n)) {
						res.notMoved.add(n);
						continue;
					}
					if (n instanceof Group) {
						((Group) n).addTo(g, -1);
						if (!after.contains(n))after.add((Group) n);
					} else if (n instanceof Cube) {
						((Cube) n).addTo(g, -1);
						movedCubes.add((Cube) n);
					}
					res.moved.add(n);
				}
			}
			res.group = g;
		});
		return res;
	}

	static String partLabel(PlayerModelParts p) {
		return I18n.get("cpmplus.w3copyxf.part." + p.getName());
	}

	public static void openDup() {
		PlayerModelParts def = PlayerModelParts.BODY;
		for (Group g : selectedGroups()) {
			Group r = RootUtil.rootOf(g);
			VanillaModelPart rt = r != null ? r.getRootType() : null;
			if (rt instanceof PlayerModelParts && rt != PlayerModelParts.CUSTOM_PART) {
				def = (PlayerModelParts) rt;
				break;
			}
		}
		if (def == PlayerModelParts.BODY && Outliner.selected != null) {
			for (OutlinerElement e : Outliner.selected) {
				Group r = RootUtil.rootOf(e);
				VanillaModelPart rt = r != null ? r.getRootType() : null;
				if (rt instanceof PlayerModelParts && rt != PlayerModelParts.CUSTOM_PART) {
					def = (PlayerModelParts) rt;
					break;
				}
			}
		}
		Map<String, String> opts = new LinkedHashMap<>();
		for (PlayerModelParts p : DUP_PARTS)opts.put(p.getName(), "cpmplus.w3copyxf.part." + p.getName());
		List<Object> cand = moveCandidates(null);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, "cpmplus.w3copyxf.dup.intro"));
		rows.add(FormRow.select("part", "cpmplus.w3copyxf.dup.part", opts, def.getName()));
		rows.add(FormRow.checkbox("dva", "cpmplus.w3copyxf.dup.dva", true).description("cpmplus.w3copyxf.dva.desc"));
		if (!cand.isEmpty())rows.add(FormRow.checkbox("move", PlusDialogs.trf("cpmplus.w3copyxf.dup.move", cand.size()), true));
		PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
		fo.confirmKey = "cpmplus.w3copyxf.btn.create";
		fo.cancelKey = "cpmplus.w0binfra.btn.cancel";
		PlusDialogs.form(DUP_DIALOG, "cpmplus.w3copyxf.dup.title", rows, r -> {
			PlayerModelParts part = PlayerModelParts.BODY;
			String sel = r.getString("part");
			for (PlayerModelParts p : DUP_PARTS)if (p.getName().equals(sel))part = p;
			boolean dva = r.getBool("dva");
			boolean move = !cand.isEmpty() && r.getBool("move");
			Group existing = RootUtil.findRootGroup(part.getName() + "_dup");
			List<Object> mv = move ? moveCandidates(existing) : null;
			if (existing != null && existing.disableVanillaAnim == dva && (mv == null || mv.isEmpty())) {
				existing.select();
				PlusDialogs.toast(PlusDialogs.trf("cpmplus.w3copyxf.dup.exists", existing.name), "info", 8000, null);
				return;
			}
			DupResult res = makeDup(part, dva, mv);
			try {
				res.group.select();
			} catch (Throwable e) {
			}
			String msg = res.created ? PlusDialogs.trf("cpmplus.w3copyxf.dup.done", partLabel(part)) : PlusDialogs.trf("cpmplus.w3copyxf.dup.doneReused", res.group.name);
			if (!res.moved.isEmpty())msg += " " + PlusDialogs.trf("cpmplus.w3copyxf.dup.moved", res.moved.size());
			PlusDialogs.done(msg);
			if (!res.notMoved.isEmpty())PlusDialogs.toast(PlusDialogs.trf("cpmplus.w3copyxf.dup.notMoved", res.notMoved.size()), "warning", 12000, null);
		}, fo);
	}

	// ====================================================================== export checks + fixes

	public static void installChecks() {
		ExportHooks.register(ctx -> {
			if (Group.all == null)return;
			for (Group g : Group.all) {
				if (g.parent == Group.ROOT && g.export) {
					String fixed = numberedDupFix(g.name);
					if (fixed != null) {
						WarnEntry w = WarnFactory.of(Severity.BREAKS, WARN_DUP_NUMBERED, "cpmplus.w3copyxf.warn.dupNumbered", g.name, fixed);
						w.setConsequence("cpmplus.w3copyxf.warn.dupNumbered.consequence");
						w.addNode(g.uuid, g.name);
						ctx.add(w);
					}
				} else if (g.parent != Group.ROOT && g.disableVanillaAnim && !g.is_rotation_subgroup) {
					WarnEntry w = WarnFactory.of(Severity.DIFFERS, WARN_DVA_NOT_ROOT, "cpmplus.w3copyxf.warn.dvaNotRoot", g.name);
					w.addNode(g.uuid, g.name);
					ctx.add(w);
				}
			}
		});
		WarnFixes.register(WARN_DUP_NUMBERED, "cpmplus.w3copyxf.fix.renameDup", 10, entry -> {
			if (entry.getUuids().isEmpty())return null;
			String uuid = entry.getUuids().get(0);
			return () -> new Promise<>((res, rej) -> {
				try {
					Group g = CopyTransformEditor.byUuid(uuid);
					String fixed = g != null ? numberedDupFix(g.name) : null;
					if (fixed == null) {
						res.onInvoke(false);
						return;
					}
					List<Group> l = new ArrayList<>();
					l.add(g);
					PlusUndo.run(I18n.get("cpmplus.w3copyxf.dup.undoRename"), groupsData(l), () -> groupsData(l), () -> g.name = fixed);
					PlusDialogs.done(PlusDialogs.trf("cpmplus.w3copyxf.dup.doneRename", fixed));
					res.onInvoke(true);
				} catch (Throwable e) {
					rej.onInvoke(e);
				}
			});
		});
		WarnFixes.register(WARN_DVA_NOT_ROOT, "cpmplus.w3copyxf.fix.dvaOff", 10, entry -> {
			if (entry.getUuids().isEmpty())return null;
			String uuid = entry.getUuids().get(0);
			return () -> new Promise<>((res, rej) -> {
				try {
					Group g = CopyTransformEditor.byUuid(uuid);
					if (g == null || !g.disableVanillaAnim) {
						res.onInvoke(false);
						return;
					}
					List<Group> l = new ArrayList<>();
					l.add(g);
					setDva(l, false);
					res.onInvoke(true);
				} catch (Throwable e) {
					rej.onInvoke(e);
				}
			});
		});
	}

}
