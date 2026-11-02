package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeProperties;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PartPaths;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ F12 'Espelhar pro outro lado (CPM)' (W4-ANIM2): copies a part (group or cube) to the other side of the body,
 * mirrored, attached to the right root, in ONE undo entry.
 * <ul>
 * <li>Root pairs: left/right arm and leg, elytra, armor arm / leg / foot and their '_dup' copies; head, body, cape,
 * armor helmet / body / leggings mirror in place. A missing target root is created (player part: root + its vanilla
 * part cube, so the game keeps showing the vanilla part as before; '_dup': empty root; elytra / armor: 'Add parts');
 * the toast says so. A root itself is refused (that is 'Criar cópia da parte do corpo').</li>
 * <li>Copy: BB duplicate, original names kept, IdUtil.resetIds on every new node (fresh stable storeIDs, never the
 * original's). Geometry mirrored relative to the two roots: x' = sx + dx - x (= -x for the vanilla rig, i.e.
 * Cube.flip(0, 0, false): east/west faces and per-face UVs swapped, box UV mirror flag toggled, rotation y / z
 * negated), y / z moved by the root offset; groups: pivot mirrored, rotation y / z negated. Placed under the mirrored
 * parent when it exists, else directly under the target root (warning when a rotated parent is left behind).</li>
 * <li>Names: side tokens swapped in every name (pt-BR too, PartPaths.oppositeName); group names kept unique.</li>
 * <li>Links: cpm_copy_transform pointing inside the subtree -> the clone (port of CPM's Generators.afterDuplicate),
 * outside -> the paired part (else kept + warning; every other JSON key unchanged); item slots left_hand / right_hand
 * and left_shoulder / right_shoulder swapped.</li>
 * <li>Animations (optional): every key of the original subtree mirrored onto the clone (position x, rotation y / z
 * negated incl. bezier handles; formulas wrapped in -( ); scale / visibility / colour copied).</li>
 * </ul>
 */
public final class MirrorToOtherSide {
	private static final String K = "cpmplus.w4anim2.mirror.";
	/** roots that swap sides (each with its pair); the '_dup' copies follow the same table */
	public static final String[][] ROOT_PAIRS = {
			{"left_arm", "right_arm"},
			{"left_leg", "right_leg"},
			{"elytra_left", "elytra_right"},
			{"armor_left_arm", "armor_right_arm"},
			{"armor_left_leg", "armor_right_leg"},
			{"armor_left_foot", "armor_right_foot"},
	};
	/** roots on the middle of the body: the part is mirrored in place */
	public static final String[] IN_PLACE = {"head", "body", "cape", "armor_helmet", "armor_body", "armor_leggings_body"};
	private static final String[] CHANNELS = {"position", "rotation", "scale", CPMCodec.VISIBILITY, CPMCodec.COLOR};
	private static final String ROOT_CHOICE = "root";

	private MirrorToOtherSide() {
	}

	/** what the user chose */
	public static final class Opts {
		/** name of the mirrored part (null = automatic) */
		public String name;
		public boolean copy = true, renameSides = true, fixLinks = true, mirrorAnims = false;
		/** uuid of the parent to attach to, or null / "root" = the target root (auto: the mirrored parent) */
		public String parent;
	}

	/** everything known before changing anything (dialog + core) */
	public static final class Plan {
		public Object node;
		public String nodeName;
		public Group srcRoot;
		public String dstRootName;
		/** null = will be created */
		public Group dstRoot;
		/** existing mirrored parent (null = directly under the target root) */
		public Group mirroredParent;
		public String defaultName;
		public String blocked;
		public int extruded;
		/** a rotated parent is left behind when the part goes directly under the target root */
		public String rotParentWarn;
		public final List<String> warnings = new ArrayList<>();
		public final List<Animation> anims = new ArrayList<>();
		public final List<Group> groups = new ArrayList<>();
		public final List<Cube> cubes = new ArrayList<>();
	}

	public static final class Result {
		public boolean ok, changed;
		public String blocked;
		public Object clone;
		public final List<String> created = new ArrayList<>();
		public final List<String> warnings = new ArrayList<>();
	}

	// ====================================================================== roots and paths

	/** the root a part on root goes to: its pair, itself for middle roots, null for unknown roots */
	public static String pairRoot(String root) {
		if (root == null)return null;
		boolean dup = root.endsWith("_dup");
		String base = dup ? root.substring(0, root.length() - 4) : root;
		String r = null;
		for (String[] p : ROOT_PAIRS) {
			if (p[0].equals(base))r = p[1];
			else if (p[1].equals(base))r = p[0];
		}
		for (String s : IN_PLACE)if (s.equals(base))r = base;
		if (r == null)return null;
		return dup ? r + "_dup" : r;
	}

	private static Object parentOf(Object node) {
		if (node instanceof Group)return ((Group) node).parent;
		if (node instanceof Cube)return ((Cube) node).parent;
		return null;
	}

	private static String nameOf(Object node) {
		if (node instanceof Group)return ((Group) node).name;
		if (node instanceof Cube)return ((Cube) node).name;
		return "";
	}

	/** names from the root down to node */
	public static List<String> path(Object node) {
		List<String> out = new ArrayList<>();
		Object n = node;
		int guard = 0;
		while (n instanceof Group || n instanceof Cube) {
			out.add(0, nameOf(n));
			n = parentOf(n);
			if (guard++ > 10000)break;
		}
		return out;
	}

	/** the path on the other side: root paired, every other name side-swapped (unchanged when it has no side) */
	public static List<String> mirroredPath(List<String> path) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < path.size(); i++) {
			String s = path.get(i);
			if (i == 0) {
				String r = pairRoot(s);
				out.add(r != null ? r : s);
			} else {
				String o = PartPaths.oppositeName(s);
				out.add(o != null ? o : s);
			}
		}
		return out;
	}

	/** the group at path (root group name, then child group names; first match), or null */
	public static Group findByPath(List<String> path) {
		if (path.isEmpty())return null;
		Group g = RootUtil.findRootGroup(path.get(0));
		for (int i = 1; i < path.size() && g != null; i++) {
			Group next = null;
			for (int c = 0; g.children != null && c < g.children.length; c++) {
				OutlinerNode n = g.children.getAt(c);
				if (n instanceof Group && path.get(i).equals(((Group) n).name)) {
					next = (Group) n;
					break;
				}
			}
			g = next;
		}
		return g;
	}

	/** the part on the other side of g (a root: its pair; else the group at the mirrored path), or null */
	public static Group pairGroup(Group g) {
		if (g == null)return null;
		if (g.parent == Group.ROOT) {
			String pr = pairRoot(g.name);
			return pr != null ? RootUtil.findRootGroup(pr) : null;
		}
		return findByPath(mirroredPath(path(g)));
	}

	/** node and everything inside, pre-order (same order as BB duplicate builds the copy) */
	private static void walk(Object node, List<Object> out) {
		out.add(node);
		if (node instanceof Group) {
			Group g = (Group) node;
			for (int i = 0; g.children != null && i < g.children.length; i++)walk(g.children.getAt(i), out);
		}
	}

	private static Group findGroup(String uuid) {
		if (uuid == null || Group.all == null)return null;
		for (Group g : Group.all)if (uuid.equals(g.uuid))return g;
		return null;
	}

	private static String swapSlot(String slot) {
		if (slot == null)return null;
		switch (slot) {
		case "left_hand": return "right_hand";
		case "right_hand": return "left_hand";
		case "left_shoulder": return "right_shoulder";
		case "right_shoulder": return "left_shoulder";
		default: return slot;
		}
	}

	// ====================================================================== plan

	/** the part selected in the outliner: one group, else one cube; null with blocked message in out[0] */
	public static Object selectedNode(String[] why) {
		Object[] groups = Js.uncheckedCast(selectedGroups());
		if (groups.length > 1) {
			why[0] = PlusDialogs.tr(K + "block.many");
			return null;
		}
		if (groups.length == 1)return groups[0];
		Object[] els = Js.uncheckedCast(selectedElements());
		if (els.length > 1) {
			why[0] = PlusDialogs.tr(K + "block.many");
			return null;
		}
		if (els.length == 1 && els[0] instanceof Cube)return els[0];
		why[0] = PlusDialogs.tr(K + "block.none");
		return null;
	}

	public static Plan plan(Object node) {
		Plan p = new Plan();
		p.node = node;
		if (!(node instanceof Group) && !(node instanceof Cube)) {
			p.blocked = PlusDialogs.tr(K + "block.none");
			return p;
		}
		p.nodeName = nameOf(node);
		if (node instanceof Group && ((Group) node).parent == Group.ROOT) {
			p.blocked = PlusDialogs.trf(K + "block.root", p.nodeName);
			return p;
		}
		p.srcRoot = RootUtil.rootOf(node);
		if (p.srcRoot == null) {
			p.blocked = PlusDialogs.trf(K + "block.noRoot", p.nodeName);
			return p;
		}
		p.dstRootName = pairRoot(p.srcRoot.name);
		if (p.dstRootName == null) {
			p.blocked = PlusDialogs.trf(K + "block.unknownRoot", p.srcRoot.name);
			return p;
		}
		p.dstRoot = RootUtil.findRootGroup(p.dstRootName);
		Object parent = parentOf(node);
		if (parent instanceof Group && parent != p.srcRoot) {
			Group mp = findByPath(mirroredPath(path(parent)));
			if (mp != null && mp != node && !RootUtil.isDescendant(mp, node instanceof Group ? (Group) node : null))p.mirroredParent = mp;
			else {
				for (Object a = parent; a instanceof Group && a != p.srcRoot; a = ((Group) a).parent) {
					JsVec3 r = ((Group) a).rotation;
					if (r != null && (r.x != 0 || r.y != 0 || r.z != 0)) {
						p.rotParentWarn = PlusDialogs.trf(K + "warn.rotParent", ((Group) a).name, p.dstRootName);
						p.warnings.add(p.rotParentWarn);
						break;
					}
				}
			}
		}
		String opp = PartPaths.oppositeName(p.nodeName);
		p.defaultName = opp != null ? opp : p.nodeName;
		List<Object> all = new ArrayList<>();
		walk(node, all);
		Set<String> uuids = new HashSet<>();
		for (Object o : all) {
			if (o instanceof Group) {
				p.groups.add((Group) o);
				uuids.add(((Group) o).uuid);
			} else if (o instanceof Cube) {
				Cube c = (Cube) o;
				p.cubes.add(c);
				if (c.extrude || c.getData().getExtrude() != null)p.extruded++;
			}
		}
		if (p.extruded > 0)p.warnings.add(PlusDialogs.trf(K + "warn.extrude", p.extruded));
		if (Animation.all != null) {
			for (Animation a : Animation.all) {
				for (Group g : p.groups) {
					BoneAnimator ba = animatorOf(a, g);
					if (ba != null && ba.hasTransform()) {
						p.anims.add(a);
						break;
					}
				}
			}
		}
		// links that will not find a pair, slots that already exist on the other side (preview only)
		for (Group g : p.groups) {
			String ct = g.copyTransform;
			if (ct != null && !ct.isEmpty()) {
				Object u = ctMap(ct).get("uuid");
				if (u instanceof String && !uuids.contains(u)) {
					Group t = findGroup((String) u);
					if (t != null && pairGroup(t) == null)p.warnings.add(PlusDialogs.trf(K + "warn.link", g.name, t.name, t.name));
				}
			}
			String slot = g.getData().getItemRenderer();
			String sw = swapSlot(slot);
			if (slot != null && !sw.equals(slot) && slotExists(sw, uuids))p.warnings.add(PlusDialogs.trf(K + "warn.slot", sw));
		}
		return p;
	}

	/** the copy-transform JSON as a map (empty when it is not valid JSON: such a link is left alone) */
	private static Map<String, Object> ctMap(String ct) {
		try {
			Map<String, Object> m = JsonUtil.fromJson(ct).asMap();
			return m != null ? JsonUtil.copyMap(m) : new HashMap<>();
		} catch (Throwable e) {
			return new HashMap<>();
		}
	}

	private static boolean slotExists(String slot, Set<String> exclude) {
		for (Group g : Group.all) {
			if (exclude.contains(g.uuid))continue;
			if (slot.equalsIgnoreCase(g.getData().getItemRenderer()))return true;
		}
		return false;
	}

	// ====================================================================== UI

	/** CPM+ > Modelagem > 'Espelhar pro outro lado (CPM)…' and the outliner context menus */
	public static void open() {
		if (Animator.open) {
			PlusDialogs.block(K + "block.animate");
			return;
		}
		String[] why = new String[1];
		Object node = selectedNode(why);
		if (node == null) {
			PlusDialogs.block(why[0]);
			return;
		}
		Plan p = plan(node);
		if (p.blocked != null) {
			PlusDialogs.block(p.blocked);
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf(K + "intro", p.nodeName)));
		rows.add(FormRow.text("name", K + "name", p.defaultName));
		Map<String, String> parents = new LinkedHashMap<>();
		if (p.mirroredParent != null && p.mirroredParent != p.dstRoot)parents.put(p.mirroredParent.uuid, String.join("/", path(p.mirroredParent)));
		parents.put(ROOT_CHOICE, p.dstRoot != null ? p.dstRootName : PlusDialogs.trf(K + "target.create", p.dstRootName));
		rows.add(FormRow.select("parent", K + "target", parents, parents.keySet().iterator().next()).description(K + "target.desc"));
		rows.add(FormRow.info("targetNote", null, K + "target.desc"));
		rows.add(FormRow.checkbox("copy", K + "copy", true).description(K + "copy.desc"));
		rows.add(FormRow.checkbox("rename", K + "rename", true));
		rows.add(FormRow.checkbox("links", K + "links", true));
		rows.add(FormRow.checkbox("anims", K + "anims", false));
		if (!p.anims.isEmpty())rows.add(FormRow.info("animCount", null, PlusDialogs.trf(K + "anims.count", p.anims.size())));
		rows.add(FormRow.info("tex", null, K + "texNote"));
		for (int i = 0; i < p.warnings.size(); i++)rows.add(FormRow.info("warn" + i, null, "⚠ " + p.warnings.get(i)));
		FormOpts fo = new FormOpts();
		fo.width = 520;
		PlusDialogs.form("cpmplus_w4anim2_mirror", K + "title", rows, r -> {
			Opts o = readOpts(r);
			Result res = apply(p, o);
			if (res.blocked != null) {
				r.keepOpen();
				PlusDialogs.block(res.blocked);
				return;
			}
			done(res);
		}, fo);
	}

	private static Opts readOpts(FormResult r) {
		Opts o = new Opts();
		o.name = r.getString("name");
		o.parent = r.getString("parent");
		o.copy = r.getBool("copy");
		o.renameSides = r.getBool("rename");
		o.fixLinks = r.getBool("links");
		o.mirrorAnims = r.getBool("anims");
		return o;
	}

	public static void done(Result res) {
		for (String c : res.created)PlusDialogs.toast(PlusDialogs.trf(K + "created", c), "add_box", 8000, null);
		for (String w : res.warnings)PlusDialogs.toast(w, "warning", 10000, null);
		PlusDialogs.done(PlusDialogs.trf(K + "done", nameOf(res.clone), String.join("/", path(parentOf(res.clone)))));
	}

	// ====================================================================== core

	/** mirrors the planned part (ONE undo entry); blocked = nothing changed */
	public static Result apply(Plan p, Opts o) {
		Result r = new Result();
		if (p.blocked != null) {
			r.blocked = p.blocked;
			return r;
		}
		String topName = o.name != null ? o.name.trim() : null;
		if (o.name != null && topName.isEmpty()) {
			r.blocked = PlusDialogs.tr(K + "block.emptyName");
			return r;
		}
		if (topName == null)topName = o.renameSides ? p.defaultName : p.nodeName;
		final String fTop = topName;
		Group chosenParent = null;
		if (o.parent != null && !ROOT_CHOICE.equals(o.parent)) {
			chosenParent = findGroup(o.parent);
			if (chosenParent == null || chosenParent == p.node || (p.node instanceof Group && RootUtil.isDescendant(chosenParent, (Group) p.node)))chosenParent = null;
		} else if (o.parent == null) {
			chosenParent = p.mirroredParent;
		}
		final Group fParent = chosenParent;

		List<Group> createdGroups = new ArrayList<>();
		List<OutlinerElement> createdCubes = new ArrayList<>();
		List<Texture> createdTex = new ArrayList<>();
		List<Group> newGroups = new ArrayList<>();
		List<Cube> newCubes = new ArrayList<>();

		UndoData before = PlusUndo.structBefore(o.copy ? new Group[0] : p.groups.toArray(new Group[0]));
		before.elements = o.copy ? new OutlinerElement[0] : p.cubes.toArray(new OutlinerElement[0]);
		before.textures = new Texture[0];
		if (o.mirrorAnims && !p.anims.isEmpty()) {
			before.animations = p.anims.toArray(new Animation[0]);
			// the clone's animators are new: Blockbench's undo would leave their keys behind (AnimxUtil tracking clears them)
			com.tom.cpm.blockbench.tools.anim.AnimxUtil.track(before, p.anims);
		}

		PlusUndo.run(PlusDialogs.tr(K + "undo"), before, () -> {
			List<Group> g = new ArrayList<>(newGroups);
			g.addAll(createdGroups);
			List<OutlinerElement> e = new ArrayList<>(newCubes);
			e.addAll(createdCubes);
			UndoData a = PlusUndo.structAfter(g, e, null);
			a.textures = createdTex.toArray(new Texture[0]);
			if (o.mirrorAnims && !p.anims.isEmpty()) {
				a.animations = p.anims.toArray(new Animation[0]);
				com.tom.cpm.blockbench.tools.anim.AnimxUtil.track(a, p.anims);
			}
			return a;
		}, () -> {
			// 1. target root
			Group dstRoot = p.dstRoot != null ? p.dstRoot : createRoot(p.dstRootName, createdGroups, createdCubes, createdTex, r.created);
			if (dstRoot == null)throw new IllegalStateException("could not create the root '" + p.dstRootName + "'");
			// 2. copy (or the part itself)
			List<Object> orig = new ArrayList<>();
			walk(p.node, orig);
			Object clone;
			if (o.copy)clone = p.node instanceof Group ? ((Group) p.node).duplicate() : ((Cube) p.node).duplicate();
			else clone = p.node;
			List<Object> cl = new ArrayList<>();
			walk(clone, cl);
			if (cl.size() != orig.size())throw new IllegalStateException("the copy has " + cl.size() + " parts, the original " + orig.size());
			Map<String, String> uuidMap = new HashMap<>();
			Set<String> subtree = new HashSet<>();
			for (int i = 0; i < orig.size(); i++) {
				Object on = orig.get(i), cn = cl.get(i);
				if (on instanceof Group) {
					uuidMap.put(((Group) on).uuid, ((Group) cn).uuid);
					subtree.add(((Group) cn).uuid);
					newGroups.add((Group) cn);
					if (o.copy)((Group) cn).name = ((Group) on).name;// BB numbered the copies
				} else if (on instanceof Cube) {
					newCubes.add((Cube) cn);
					if (o.copy)((Cube) cn).name = ((Cube) on).name;
				}
			}
			if (o.copy)IdUtil.resetIds(clone);
			// 3. geometry: mirror relative to the two roots
			JsVec3 so = p.srcRoot.origin, dso = dstRoot.origin;
			double c = (so.x + dso.x) / 2.0;
			float dy = dso.y - so.y, dz = dso.z - so.z;
			for (Object n : cl) {
				if (n instanceof Group) {
					Group gr = (Group) n;
					gr.origin = JsVec3.make((float) (2 * c - gr.origin.x), gr.origin.y + dy, gr.origin.z + dz);
					gr.rotation = JsVec3.make(gr.rotation.x, neg(gr.rotation.y), neg(gr.rotation.z));
				} else if (n instanceof Cube) {
					Cube cu = (Cube) n;
					String nm = cu.name;
					// own arrays first (an old project may share a vector with another node)
					cu.from = RootUtil.copyVec(cu.from);
					cu.to = RootUtil.copyVec(cu.to);
					cu.origin = RootUtil.copyVec(cu.origin);
					cu.flip(0, c, false);
					cu.name = nm;// flip renames left/right in English; names are handled below
					if (dy != 0 || dz != 0) {
						cu.from = JsVec3.make(cu.from.x, cu.from.y + dy, cu.from.z + dz);
						cu.to = JsVec3.make(cu.to.x, cu.to.y + dy, cu.to.z + dz);
						cu.origin = JsVec3.make(cu.origin.x, cu.origin.y + dy, cu.origin.z + dz);
					}
				}
			}
			// 4. attach
			Group target = fParent != null ? fParent : dstRoot;
			if (parentOf(clone) != target) {
				if (clone instanceof Group)((Group) clone).addTo(target, -1);
				else ((Cube) clone).addTo(target, -1);
			}
			// 5. names
			for (int i = 0; i < cl.size(); i++) {
				Object n = cl.get(i);
				String nm = i == 0 ? fTop : nameOf(n);
				if (i > 0 && o.renameSides) {
					String opp = PartPaths.oppositeName(nm);
					if (opp != null)nm = opp;
				}
				if (n instanceof Group)((Group) n).name = nm;
				else ((Cube) n).name = nm;
			}
			for (Object n : cl)if (n instanceof Group)uniqueName((Group) n);
			// 6. 'seguir peça' and item slots
			if (o.fixLinks) {
				for (Object n : cl) {
					if (!(n instanceof Group))continue;
					Group gr = (Group) n;
					String ct = gr.copyTransform;
					if (ct != null && !ct.isEmpty()) {
						Map<String, Object> m = ctMap(ct);
						Object u = m.get("uuid");
						if (u instanceof String) {
							String us = (String) u;
							String nu = null;
							if (o.copy && uuidMap.containsKey(us))nu = uuidMap.get(us);
							else if (!o.copy && subtree.contains(us))nu = us;
							else {
								Group t = findGroup(us);
								Group pr = pairGroup(t);
								if (pr != null)nu = pr.uuid;
								else if (t != null)r.warnings.add(PlusDialogs.trf(K + "warn.link", gr.name, t.name, t.name));
							}
							if (nu != null && !nu.equals(us)) {
								m.put("uuid", nu);
								gr.copyTransform = JsonUtil.toJson(m);
							}
						}
					}
					GroupData gd = gr.getData();
					String slot = gd.getItemRenderer();
					String sw = swapSlot(slot);
					if (slot != null && !sw.equals(slot)) {
						gd.setItemRenderer(sw);
						gd.flush();
						if (slotExists(sw, subtree))r.warnings.add(PlusDialogs.trf(K + "warn.slot", sw));
					}
				}
			}
			// 7. animations
			if (o.mirrorAnims) {
				for (Animation an : p.anims) {
					for (int i = 0; i < orig.size(); i++) {
						if (!(orig.get(i) instanceof Group))continue;
						BoneAnimator oba = animatorOf(an, (Group) orig.get(i));
						if (oba == null)continue;
						if (o.copy) {
							BoneAnimator cba = null;
							for (String ch : CHANNELS) {
								JsArrayE<Keyframe> arr = oba.channel(ch);
								if (arr == null || arr.length == 0)continue;
								if (cba == null)cba = an.getBoneAnimator(cl.get(i));
								if (cba == null)break;
								for (int k = 0; k < arr.length; k++) {
									JsPropertyMap<Object> data = Js.uncheckedCast(arr.getAt(k).getUndoCopy());
									data.delete("uuid");
									data.delete("animator");
									mirrorKeyData(data, ch);
									cba.addKeyframe(data, null);
								}
							}
						} else {
							for (String ch : new String[] {"position", "rotation"}) {
								JsArrayE<Keyframe> arr = oba.channel(ch);
								for (int k = 0; arr != null && k < arr.length; k++)mirrorKeyInPlace(arr.getAt(k), ch);
							}
						}
					}
				}
			}
			r.clone = clone;
		});
		if (p.extruded > 0)r.warnings.add(0, PlusDialogs.trf(K + "warn.extrude", p.extruded));
		if (fParent == null && p.rotParentWarn != null)r.warnings.add(p.rotParentWarn);
		refresh(r.clone);
		r.ok = true;
		r.changed = true;
		return r;
	}

	private static float neg(float v) {
		return v == 0 ? 0 : -v;
	}

	/** keeps a group name unique among all groups (Blockbench bone rig): 'name', 'name2', 'name3' ... */
	private static void uniqueName(Group g) {
		String base = g.name;
		if (!nameTaken(base, g))return;
		for (int i = 2; i < 10000; i++) {
			String n = base + i;
			if (!nameTaken(n, g)) {
				g.name = n;
				return;
			}
		}
	}

	private static boolean nameTaken(String name, Group self) {
		for (Group o : Group.all)if (o != self && name.equals(o.name))return true;
		return false;
	}

	/**
	 * a missing target root: player part = root + vanilla part cube (marked vanilla: the game keeps the vanilla part
	 * as before), '_dup' = empty root copy, elytra / armor = Blockbench 'Add parts' for that root group
	 */
	static Group createRoot(String name, List<Group> groups, List<OutlinerElement> cubes, List<Texture> tex, List<String> created) {
		boolean dup = name.endsWith("_dup");
		String base = dup ? name.substring(0, name.length() - 4) : name;
		VanillaModelPart part = RootUtil.part(base);
		if (part == null)return null;
		SkinType st = RootUtil.projectSkinType();
		if (!dup && part instanceof RootModelType) {
			RootGroups rg = RootGroups.getGroup((RootModelType) part);
			List<Group> made = new ArrayList<>();
			BBActions.addRootNoUndo(rg, cubes, tex, made);
			groups.addAll(made);
			for (Group g : made)created.add(g.name);
			return RootUtil.findRootGroup(name);
		}
		Group g = RootUtil.makeRoot(base, st);
		g.name = name;
		g.isOpen = true;
		groups.add(g);
		created.add(name);
		if (!dup && part instanceof PlayerModelParts) {
			// same steps as BBActions.addRootNoUndo / ProjectGenerator.addPart (math: RootUtil.vanillaCubeSpec)
			RootUtil.CubeSpec spec = RootUtil.vanillaCubeSpec(part, st, g.origin);
			CubeProperties cp = new CubeProperties();
			cp.name = base;
			cp.origin = RootUtil.copyVec(g.origin);
			cp.from = spec.from;
			cp.rotation = JsVec3.make(0, 0, 0);
			cp.uv_offset = spec.uvOffset;
			Cube cube = new Cube(cp);
			cube.mirror_uv = spec.mirror;
			cp = new CubeProperties();
			cp.to = spec.to;
			cube.extend(cp);
			cube.inflate = spec.inflate;
			cube.box_uv = true;
			cube.addTo(g).init();
			Texture skin = RootUtil.findSkinTexture();
			if (skin != null)cube.applyTexture(skin, true);
			cube.getData().setVanilla(true).flush();
			cubes.add(cube);
		}
		return g;
	}

	/** mirrored keyframe data (copy): position x, rotation y / z and their bezier handles negated */
	static void mirrorKeyData(JsPropertyMap<Object> data, String channel) {
		String[] axes = axesOf(channel);
		if (axes.length == 0)return;
		Object dps = data.get("data_points");
		if (dps != null && JsArray.isArray(dps)) {
			JsArray<Object> arr = Js.uncheckedCast(dps);
			for (int i = 0; i < arr.length; i++) {
				JsPropertyMap<Object> dp = Js.uncheckedCast(arr.getAt(i));
				for (String a : axes)dp.set(a, negate(dp.get(a)));
			}
		}
		for (String h : new String[] {"bezier_left_value", "bezier_right_value"}) {
			Object hv = data.get(h);
			if (hv == null || !JsArray.isArray(hv))continue;
			JsArray<Object> src = Js.uncheckedCast(hv);
			JsArray<Object> na = new JsArray<>();
			for (int i = 0; i < src.length; i++)na.push(contains(axes, "xyz".substring(i, Math.min(3, i + 1))) ? negate(src.getAt(i)) : src.getAt(i));
			data.set(h, na);
		}
	}

	private static void mirrorKeyInPlace(Keyframe kf, String channel) {
		String[] axes = axesOf(channel);
		for (int i = 0; kf.data_points != null && i < kf.data_points.length; i++) {
			JsPropertyMap<Object> dp = Js.asPropertyMap(kf.data_points.getAt(i));
			for (String a : axes)dp.set(a, negate(dp.get(a)));
		}
		JsPropertyMap<Object> m = Js.asPropertyMap(kf);
		for (String h : new String[] {"bezier_left_value", "bezier_right_value"}) {
			Object hv = m.get(h);
			if (hv == null || !JsArray.isArray(hv))continue;
			JsArray<Object> arr = Js.uncheckedCast(hv);
			for (int i = 0; i < arr.length && i < 3; i++)if (contains(axes, "xyz".substring(i, i + 1)))arr.setAt(i, negate(arr.getAt(i)));
		}
	}

	private static String[] axesOf(String channel) {
		if ("position".equals(channel))return new String[] {"x"};
		if ("rotation".equals(channel))return new String[] {"y", "z"};
		return new String[0];
	}

	private static boolean contains(String[] a, String s) {
		for (String x : a)if (x.equals(s))return true;
		return false;
	}

	/** -v for numbers and numeric text; a formula becomes -(formula) (exact mirror, the export bakes it) */
	static Object negate(Object v) {
		if (v == null)return v;
		String t = Js.typeof(v);
		if ("number".equals(t)) {
			double d = Js.asDouble(v);
			return d == 0 ? (Object) 0.0 : (Object) (-d);
		}
		if ("string".equals(t)) {
			String s = ((String) v).trim();
			if (s.isEmpty())return v;
			try {
				double d = Double.parseDouble(s);
				return d == 0 ? (Object) 0.0 : (Object) (-d);
			} catch (NumberFormatException e) {
				return "-(" + s + ")";
			}
		}
		return v;
	}

	public static BoneAnimator animatorOf(Animation anim, Group g) {
		if (anim == null || anim.animators == null || g == null)return null;
		Object a = anim.animators.get(g.uuid);
		return a instanceof BoneAnimator ? (BoneAnimator) a : null;
	}

	private static void refresh(Object clone) {
		try {
			ProjectGenerator.updateAll();
		} catch (Throwable e) {
		}
		refreshNative(clone);
	}

	private static native void refreshNative(Object clone) /*-{
		try {
			if ($wnd.Canvas && typeof $wnd.Canvas.updateAll === 'function') $wnd.Canvas.updateAll();
			if (typeof $wnd.unselectAllElements === 'function') $wnd.unselectAllElements();
			if (clone && typeof clone.select === 'function') clone.select();
			if (clone && typeof clone.showInOutliner === 'function') clone.showInOutliner();
		} catch (e) {
			console.warn('CPM+: refresh after mirror', e);
		}
	}-*/;

	private static native Object selectedGroups() /*-{
		var G = $wnd.Group;
		if (!G) return [];
		if (G.multi_selected && G.multi_selected.length) return G.multi_selected.slice();
		if (G.first_selected) return [G.first_selected];
		if (G.selected && G.selected instanceof G) return [G.selected];
		if (G.selected && G.selected.length) return G.selected.slice();
		return [];
	}-*/;

	private static native Object selectedElements() /*-{
		var O = $wnd.Outliner;
		return O && O.selected ? O.selected.slice() : [];
	}-*/;

}
