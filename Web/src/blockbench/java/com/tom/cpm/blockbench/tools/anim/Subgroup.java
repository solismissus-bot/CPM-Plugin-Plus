package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W6-ANIMX, G41 C): 'Criar sub-grupo de animação…' - a child group '&lt;root&gt;_anim' of a vanilla root that
 * takes the root's children and the chosen animation channels, so scale (dropped on roots: rootScaling) works in game
 * and additive rotation / position can live below the vanilla swing.
 * <ul>
 * <li>origin = the root's origin, rotation 0 (the root keeps its own rest rotation), so every cube stays exactly where
 * it was (Blockbench coordinates are absolute; the export computes the offsets).</li>
 * <li>Not moved: the subgroup itself, item / parrot slot groups (GroupData item renderer), '_dup' copies and - unless
 * asked - the unchanged vanilla part cube (moving it turns it into a custom cube and the skin's 2nd layer of that part
 * is no longer drawn).</li>
 * <li>Keys move with every field (time, data points, interpolation, bezier handles, bezier_linked, uniform) through
 * Blockbench's own key copy, onto anim.getBoneAnimator(subgroup).</li>
 * <li>Default channels: additive animations = every chosen channel; non-additive = scale only (moving their rotation
 * / position would let Minecraft's swing / look come back underneath).</li>
 * <li>One undo entry (outliner + every touched animation); an existing '&lt;root&gt;_anim' child is reused.</li>
 * </ul>
 */
public final class Subgroup {

	public static final class Opts {
		public Group root;
		public String name;
		public boolean scale = true, rot, pos;
		/** also move rotation / position keys of NON-additive animations */
		public boolean nonAdditiveRotPos;
		/** animations whose keys move (null = every animation keying the root) */
		public List<Animation> anims;
		/** also move the unchanged vanilla part cube */
		public boolean moveVanillaCube;
		/** cpm_copy_transform sources pointing at the root now point at the subgroup */
		public boolean retargetCopy;
	}

	public static final class Result {
		public boolean ok;
		public String error;
		public Group group;
		public boolean reused;
		public int movedNodes, movedKeys, animsTouched, retargeted;
		public List<String> skipped = new ArrayList<>();
	}

	private Subgroup() {
	}

	public static String defaultName(Group root) {
		return root.name + "_anim";
	}

	/** an existing direct child group with that name (reused instead of nesting again) */
	public static Group existing(Group root, String name) {
		if (root.children == null)return null;
		for (int i = 0; i < root.children.length; i++) {
			Object c = root.children.getAt(i);
			if (c instanceof Group && name.equals(((Group) c).name))return (Group) c;
		}
		return null;
	}

	/** lang key of the problem with this name, or null when it can be used */
	public static String nameProblem(Group root, String name) {
		if (name == null || name.trim().isEmpty())return "cpmplus.w6animx.sub.err.empty";
		if (name.endsWith("_dup"))return "cpmplus.w6animx.sub.err.dup";
		Group probe = Js.uncheckedCast(JsPropertyMap.of());
		probe.name = name;
		if (probe.getRootType() != null)return "cpmplus.w6animx.sub.err.reserved";
		Group ex = existing(root, name);
		for (Group g : Group.all) {
			if (g != ex && name.equalsIgnoreCase(g.name))return "cpmplus.w6animx.sub.err.taken";
		}
		return null;
	}

	/** animations with any key on the root */
	public static List<Animation> animsTouching(Group root) {
		List<Animation> l = new ArrayList<>();
		for (Animation a : AnimxUtil.animations()) {
			BoneAnimator ba = AnimxUtil.animatorOf(a, root);
			if (ba == null)continue;
			for (String ch : AnimxUtil.ALL) {
				if (AnimxUtil.count(ba, ch) > 0) {
					l.add(a);
					break;
				}
			}
		}
		return l;
	}

	/** groups whose cpm_copy_transform points at the root */
	public static List<Group> copySources(Group root) {
		List<Group> l = new ArrayList<>();
		for (Group g : Group.all) {
			String ct = g.copyTransform;
			if (ct != null && "string".equals(Js.typeof(ct)) && ct.contains(root.uuid))l.add(g);
		}
		return l;
	}

	private static boolean keepInRoot(Object node, Group sub, boolean moveVanilla, Result res) {
		if (node == sub)return true;
		if (node instanceof Group) {
			Group g = (Group) node;
			String n = g.name != null ? g.name.toLowerCase(Locale.ROOT) : "";
			String item = null;
			try {
				item = g.getData().getItemRenderer();
			} catch (Throwable e) {
			}
			if (item != null || n.contains("parrot") || n.endsWith("_dup")) {
				res.skipped.add(g.name);
				return true;
			}
			return false;
		}
		if (node instanceof Cube) {
			Cube c = (Cube) node;
			boolean vanilla = false;
			try {
				vanilla = c.getData().isVanilla();
			} catch (Throwable e) {
			}
			if (vanilla && !moveVanilla) {
				res.skipped.add(c.name);
				return true;
			}
		}
		return false;
	}

	private static boolean moves(Animation a, String ch, Opts o) {
		if (AnimxUtil.SCL.equals(ch))return o.scale;
		boolean want = AnimxUtil.ROT.equals(ch) ? o.rot : o.pos;
		return want && (a.additive || o.nonAdditiveRotPos);
	}

	/** creates / reuses the subgroup and moves children and keys; one undo entry. Never a partial edit. */
	public static Result make(Opts o) {
		Result res = new Result();
		Group root = o.root;
		if (root == null || root.children == null || root.children.length == 0) {
			res.error = "cpmplus.w6animx.sub.err.noChildren";
			return res;
		}
		String name = o.name != null ? o.name.trim() : defaultName(root);
		String prob = nameProblem(root, name);
		if (prob != null) {
			res.error = prob;
			return res;
		}
		Group ex = existing(root, name);
		List<Animation> anims = o.anims != null ? o.anims : animsTouching(root);
		List<Animation> touched = new ArrayList<>();
		for (Animation a : anims) {
			BoneAnimator ba = AnimxUtil.animatorOf(a, root);
			for (String ch : AnimxUtil.TRANSFORM)if (moves(a, ch, o) && AnimxUtil.count(ba, ch) > 0 && !touched.contains(a))touched.add(a);
		}
		// plan the moves first (the same nodes are recorded before and after: moved, never created / removed)
		List<Object> plan = new ArrayList<>();
		for (int i = 0; i < root.children.length; i++) {
			Object k = root.children.getAt(i);
			if (!keepInRoot(k, ex, o.moveVanillaCube, res))plan.add(k);
		}
		if (plan.isEmpty() && touched.isEmpty() && ex != null) {
			res.error = "cpmplus.w6animx.sub.err.nothing";
			return res;
		}
		if (plan.isEmpty() && ex == null) {
			// only the vanilla part cube (or slots / copies) inside: an empty sub-group would scale nothing
			res.error = "cpmplus.w6animx.sub.err.onlyVanilla";
			return res;
		}
		List<Group> copySrc = o.retargetCopy ? copySources(root) : new ArrayList<>();
		List<Group> keptGroups = new ArrayList<>();
		keptGroups.add(root);
		if (ex != null)keptGroups.add(ex);
		List<OutlinerElement> movedEls = new ArrayList<>();
		for (Object k : plan) {
			if (k instanceof Group)keptGroups.add((Group) k);
			else if (k instanceof OutlinerElement)movedEls.add((OutlinerElement) k);
		}
		keptGroups.addAll(copySrc);

		UndoData before = PlusUndo.structAfter(keptGroups, movedEls, null);
		before.animations = touched.toArray(new Animation[0]);
		AnimxUtil.track(before, touched);
		Group[] made = new Group[1];
		PlusUndo.run(PlusDialogs.trf("cpmplus.w6animx.undo.subgroup", name), before, () -> {
			List<Group> gs = new ArrayList<>(keptGroups);
			if (made[0] != null && !gs.contains(made[0]))gs.add(made[0]);
			UndoData d = PlusUndo.structAfter(gs, movedEls, null);
			d.animations = touched.toArray(new Animation[0]);
			return AnimxUtil.track(d, touched);
		}, () -> {
			Group sub = ex;
			if (sub == null) {
				GroupProperties gp = new GroupProperties();
				gp.name = name;
				gp.origin = JsVec3.make(root.origin.x, root.origin.y, root.origin.z);
				gp.rotation = JsVec3.make(0, 0, 0);
				sub = new Group(gp).init();
				sub.addTo(root, 0);
				res.reused = false;
			} else res.reused = true;
			made[0] = sub;
			// children, outliner order kept
			for (Object k : plan) {
				addTo(k, sub);
				res.movedNodes++;
			}
			// keys
			for (Animation a : touched) {
				BoneAnimator src = AnimxUtil.animatorOf(a, root);
				BoneAnimator dst = a.getBoneAnimator(sub);
				for (String ch : AnimxUtil.TRANSFORM) {
					if (!moves(a, ch, o))continue;
					for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(src, ch))) {
						AnimxUtil.addKey(dst, AnimxUtil.keyCopy(kf));
						kf.remove();
						res.movedKeys++;
					}
				}
				res.animsTouched++;
			}
			for (Group g : copySrc) {
				g.copyTransform = g.copyTransform.replace(root.uuid, sub.uuid);
				res.retargeted++;
			}
			res.group = sub;
		});
		res.ok = true;
		AnimxUtil.preview();
		return res;
	}

	private static native void addTo(Object node, Object parent) /*-{
		node.addTo(parent);
	}-*/;

	public static JsPropertyMap<Object> toJson(Result r) {
		JsPropertyMap<Object> m = AnimxUtil.map();
		m.set("ok", r.ok);
		m.set("error", r.error);
		m.set("group", r.group != null ? r.group.uuid : null);
		m.set("name", r.group != null ? r.group.name : null);
		m.set("reused", r.reused);
		m.set("movedNodes", r.movedNodes);
		m.set("movedKeys", r.movedKeys);
		m.set("animsTouched", r.animsTouched);
		m.set("retargeted", r.retargeted);
		JsArray<Object> s = new JsArray<>();
		for (String x : r.skipped)s.push(x);
		m.set("skipped", s);
		return m;
	}

	// ------------------------------------------------------------------ dialog

	/**
	 * @param preselect animations to pre-check (null = all touching the root)
	 * @param rotPos 'Devolver -&gt; mover para sub-grupo': rotation / position pre-checked (also for non-additive)
	 */
	public static void openDialog(Group root, List<Animation> preselect, boolean rotPos, java.util.function.Consumer<Boolean> done) {
		java.util.function.Consumer<Boolean> cb = done != null ? done : x -> {
		};
		if (root == null || !AnimxUtil.isVanillaRoot(root)) {
			PlusDialogs.block("cpmplus.w6animx.sub.err.notRoot");
			cb.accept(false);
			return;
		}
		if (root.children == null || root.children.length == 0) {
			PlusDialogs.block("cpmplus.w6animx.sub.err.noChildren");
			cb.accept(false);
			return;
		}
		List<Animation> touching = animsTouching(root);
		String name = defaultName(root);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf("cpmplus.w6animx.sub.intro", AnimXray.partName(root))));
		Group ex = existing(root, name);
		if (ex != null)rows.add(FormRow.info("reuse", null, PlusDialogs.trf("cpmplus.w6animx.sub.reuse", name)));
		rows.add(FormRow.text("name", "cpmplus.w6animx.sub.name", name));
		boolean hasVanilla = false;
		for (int i = 0; i < root.children.length; i++) {
			Object c = root.children.getAt(i);
			if (c instanceof Cube) {
				try {
					if (((Cube) c).getData().isVanilla())hasVanilla = true;
				} catch (Throwable e) {
				}
			}
		}
		boolean anyAdditive = false;
		for (Animation a : touching)if (a.additive)anyAdditive = true;
		// additive animations move every channel by default, unless Minecraft's cube stays in the root (it would no
		// longer turn / move with them)
		boolean rpDefault = rotPos || (anyAdditive && !hasVanilla);
		rows.add(FormRow.checkbox("scale", "cpmplus.w6animx.ch.scale", true));
		rows.add(FormRow.checkbox("rot", "cpmplus.w6animx.ch.rotation", rpDefault));
		rows.add(FormRow.checkbox("pos", "cpmplus.w6animx.ch.position", rpDefault));
		rows.add(FormRow.checkbox("nonAdd", "cpmplus.w6animx.sub.nonAdditive", rotPos).description("cpmplus.w6animx.sub.nonAdditive.desc")
				.when(r -> r.getBool("rot") || r.getBool("pos")));
		boolean multiAxis = false;
		Map<String, String> opts = new LinkedHashMap<>();
		List<String> sel = new ArrayList<>();
		for (Animation a : touching) {
			opts.put(a.uuid, a.name + (a.additive ? " (" + PlusDialogs.tr("cpmplus.w6animx.additive") + ")" : ""));
			if (preselect == null || preselect.contains(a))sel.add(a.uuid);
			if (a.additive && rotAxes(AnimxUtil.animatorOf(a, root)) > 1)multiAxis = true;
		}
		if (!touching.isEmpty())rows.add(FormRow.multiSelect("anims", "cpmplus.w6animx.sub.anims", opts, sel));
		else rows.add(FormRow.info("noanims", null, "cpmplus.w6animx.sub.noAnims"));
		if (multiAxis)rows.add(FormRow.info("multi", null, "cpmplus.w6animx.sub.multiAxis"));
		if (hasVanilla)rows.add(FormRow.checkbox("vanilla", "cpmplus.w6animx.sub.moveVanilla", false).description("cpmplus.w6animx.sub.moveVanilla.desc"));
		if (hasVanilla)rows.add(FormRow.info("vanStays", null, "cpmplus.w6animx.sub.vanillaStays").when(r -> (r.getBool("rot") || r.getBool("pos")) && !r.getBool("vanilla")));

		List<Group> cs = copySources(root);
		if (!cs.isEmpty())rows.add(FormRow.checkbox("retarget", "cpmplus.w6animx.sub.retarget", false).description("cpmplus.w6animx.sub.retarget.desc"));
		rows.add(FormRow.info("note", null, "cpmplus.w6animx.xray.note.preview"));
		PlusDialogs.FormOpts fo = new PlusDialogs.FormOpts();
		fo.onCancel = () -> cb.accept(false);
		PlusDialogs.form("cpmplus_w6animx_subgroup", "cpmplus.w6animx.sub.title", rows, fr -> {
			Opts o = new Opts();
			o.root = root;
			o.name = fr.getString("name");
			o.scale = fr.getBool("scale");
			o.rot = fr.getBool("rot");
			o.pos = fr.getBool("pos");
			o.nonAdditiveRotPos = fr.getBool("nonAdd");
			o.moveVanillaCube = fr.getBool("vanilla");
			o.retargetCopy = fr.getBool("retarget");
			o.anims = new ArrayList<>();
			for (String u : fr.getList("anims")) {
				Animation a = AnimxUtil.find(u);
				if (a != null)o.anims.add(a);
			}
			String prob = nameProblem(root, o.name != null ? o.name.trim() : "");
			if (prob != null) {
				PlusDialogs.quick(PlusDialogs.tr(prob), 4000);
				fr.keepOpen();
				return;
			}
			Result r = make(o);
			if (!r.ok) {
				PlusDialogs.block(r.error);
				cb.accept(false);
				return;
			}
			cb.accept(true);
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w6animx.sub.done", r.group.name, r.animsTouched));
			if (Animation.selected != null)AnimXray.open(Animation.selected);
		}, fo);
	}

	private static int rotAxes(BoneAnimator ba) {
		int n = 0;
		if (ba == null)return 0;
		for (int ax = 0; ax < 3; ax++) {
			for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(ba, AnimxUtil.ROT))) {
				if (Math.abs(AnimxUtil.value(kf, 0, ax)) > 1e-6) {
					n++;
					break;
				}
			}
		}
		return n;
	}

}
