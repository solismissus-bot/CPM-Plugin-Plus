package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.ext.W5StickNatives;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.blockbench.util.Reparent;
import com.tom.cpm.blockbench.util.RootSuggest;
import com.tom.cpm.blockbench.util.RootSuggest.Suggestion;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.blockbench.util.StickMath;
import com.tom.cpm.blockbench.util.StickMath.Kind;
import com.tom.cpm.blockbench.util.StickMath.Placement;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.util.I18n;

import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W5-STICK): 'Grudar na parte do corpo...' (G36) and the 'Peças soltas' dialog of the loose-cube export warning
 * (G6).
 * <ul>
 * <li>One piece: target list (existing roots by pt-BR name, '_dup' as '(cópia)', missing player roots '(vai ser
 * criada)'), suggestion badge (never applied on its own), optional folder for a loose cube.</li>
 * <li>Several pieces: one row per piece with its own suggestion, 'Grudar todas onde estão'.</li>
 * <li>Loose cubes (export warning 'Resolver...'): 'Grudar onde estão' (primary), 'Mover para o Corpo', 'Cancelar',
 * 'Não avisar neste projeto'.</li>
 * </ul>
 * Every change is ONE undo entry ('Grudar na parte do corpo') that also removes the roots / folder it created. Blocked:
 * Animate mode, a piece that already is a body part, a target inside the piece, animated pieces that would need a
 * rotation compensation. Warnings with 'Continuar mesmo assim': shifted roots, copy-transform pointing at the target,
 * item slots.
 */
public final class StickToRoot {
	public static final String ACTION_ID = "cpmplus_w5stick_stick";
	public static final String DLG_SINGLE = "cpmplus_w5stick_single";
	public static final String DLG_BATCH = "cpmplus_w5stick_batch";
	public static final String DLG_LOOSE = "cpmplus_w5stick_loose";
	/** ProjectData extra: true = the user turned the loose-cube export warning off for this project */
	public static final String LOOSE_ACK = "looseAck";
	static final String SKIP = "skip";
	private static final String K = "cpmplus.w5stick.";

	private StickToRoot() {
	}

	// ------------------------------------------------------------------ entry points

	/** the action: the selected groups / cubes */
	public static void openForSelection() {
		open(Arrays.asList(W5StickNatives.selectedNodes()));
	}

	/** opens the single or the batch dialog for these pieces (after the checks) */
	public static void open(List<Object> picked) {
		if (!W5StickNatives.isEditMode()) {
			PlusDialogs.block(K + "block.animate");
			return;
		}
		List<Object> nodes = normalize(picked);
		if (nodes.isEmpty()) {
			PlusDialogs.block(K + "block.empty");
			return;
		}
		List<String> roots = new ArrayList<>();
		List<Object> movable = new ArrayList<>();
		for (Object n : nodes) {
			if (isBodyPart(n))roots.add(name(n));
			else movable.add(n);
		}
		if (movable.isEmpty()) {
			PlusDialogs.block(I18n.format(K + "block.root", roots.get(0)));
			return;
		}
		if (movable.size() == 1 && roots.isEmpty())openSingle(movable.get(0));
		else openBatch(movable, roots, false, null);
	}

	/** G6 'Resolver...': the loose cubes of the export warning (by uuid); resolves true only after a change */
	public static Promise<Boolean> resolveLoose(List<String> uuids) {
		return new Promise<>((res, rej) -> {
			try {
				if (!W5StickNatives.isEditMode()) {
					PlusDialogs.block(K + "block.animate");
					res.onInvoke(false);
					return;
				}
				List<Object> cubes = new ArrayList<>();
				for (Cube c : Cube.all.asList()) {
					if (c.parent == Group.ROOT && (uuids == null || uuids.contains(c.uuid)))cubes.add(c);
				}
				if (cubes.isEmpty()) {
					res.onInvoke(false);
					return;
				}
				openBatch(cubes, new ArrayList<>(), true, v -> res.onInvoke(v));
			} catch (Throwable e) {
				PopupDialogs.displayError(PlusDialogs.esc(I18n.get(K + "loose.title")), e);
				res.onInvoke(false);
			}
		});
	}

	// ------------------------------------------------------------------ dialogs

	private static void openSingle(Object node) {
		SkinType st = RootUtil.projectSkinType();
		Map<String, String> targets = targets();
		Suggestion s = RootSuggest.suggest(node, st);
		String pre = preselect(s.part, targets);
		boolean loose = node instanceof Cube && ((Cube) node).parent == Group.ROOT;
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, I18n.format(K + "single.intro", name(node))));
		rows.add(FormRow.info("suggestion", null, suggestionText(s)));
		rows.add(FormRow.select("target", K + "field.target", targets, pre));
		if (loose)rows.add(FormRow.checkbox("folder", K + "field.folder", false).description(K + "field.folder.desc"));
		rows.add(FormRow.info("limits", null, I18n.get(K + "single.limits")));
		FormOpts o = new FormOpts();
		o.confirmKey = K + "btn.stick";
		o.cancelKey = K + "btn.cancel";
		PlusDialogs.form(DLG_SINGLE, K + "single.title", rows, r -> {
			List<Object> nodes = new ArrayList<>();
			nodes.add(node);
			List<String> tv = new ArrayList<>();
			tv.add(r.getString("target"));
			checkThenApply(nodes, tv, loose && r.getBool("folder"), null);
		}, o);
	}

	/**
	 * Batch dialog (also the 'Peças soltas' dialog of the export warning when looseMode). done (may be null) gets
	 * true when the project changed, false otherwise (exactly once).
	 */
	private static void openBatch(List<Object> nodes, List<String> skippedRoots, boolean looseMode, java.util.function.Consumer<Boolean> done) {
		SkinType st = RootUtil.projectSkinType();
		Map<String, String> targets = targets();
		Map<String, String> withSkip = new LinkedHashMap<>(targets);
		withSkip.put(SKIP, K + "opt.skip");
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, I18n.get(K + (looseMode ? "loose.intro" : "batch.intro"))));
		if (!skippedRoots.isEmpty())rows.add(FormRow.info("roots", null, I18n.format(K + "batch.skippedRoots", String.join(", ", skippedRoots))));
		for (int i = 0; i < nodes.size(); i++) {
			Suggestion s = RootSuggest.suggest(nodes.get(i), st);
			String mark = s.kind == Kind.GREEN ? " \u2714" : s.kind == Kind.ORANGE ? " \u26A0" : "";
			rows.add(FormRow.select("n" + i, name(nodes.get(i)) + mark, withSkip, preselect(s.part, targets)).description(suggestionText(s)));
		}
		if (looseMode)rows.add(FormRow.checkbox("ack", K + "loose.ack", false).description(K + "loose.ack.desc"));
		boolean[] finished = new boolean[1];
		java.util.function.Consumer<Boolean> once = v -> {
			if (finished[0])return;
			finished[0] = true;
			if (done != null)done.accept(v);
		};
		if (!looseMode) {
			FormOpts o = new FormOpts();
			o.confirmKey = K + "btn.stickAll";
			o.cancelKey = K + "btn.cancel";
			PlusDialogs.form(DLG_BATCH, K + "batch.title", rows, r -> checkThenApply(nodes, values(r, nodes.size()), false, null), o);
			return;
		}
		Dialog.DialogProperties d = new Dialog.DialogProperties();
		d.id = DLG_LOOSE;
		d.title = I18n.get(K + "loose.title");
		d.form = PlusDialogs.formObject(rows);
		d.buttons = new String[] {I18n.get(K + "btn.stickHere"), I18n.get(K + "btn.moveBody"), I18n.get(K + "btn.cancel")};
		d.confirmIndex = 0;
		d.cancelIndex = 2;
		Dialog[] ref = new Dialog[1];
		d.onButton = i -> {
			if (finished[0])return true;
			try {
				FormResult r = new FormResult(ref[0] != null ? ref[0].getFormResult() : null);
				boolean ack = r.getBool("ack");
				if (i == 0) {
					checkThenApply(nodes, values(r, nodes.size()), false, changed -> {
						if (ack)setAck();
						once.accept(changed || ack);
					});
				} else if (i == 1) {
					List<Cube> cubes = new ArrayList<>();
					for (Object n : nodes)cubes.add((Cube) n);
					boolean changed = moveToBody(cubes);
					if (ack)setAck();
					once.accept(changed || ack);
				} else {
					if (ack)setAck();
					once.accept(ack);
				}
			} catch (Throwable e) {
				once.accept(false);
				PopupDialogs.displayError(PlusDialogs.esc(d.title), e);
			}
			return true;
		};
		ref[0] = new Dialog(d);
		ref[0].show();
	}

	private static List<String> values(FormResult r, int n) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++)out.add(r.getString("n" + i));
		return out;
	}

	/** 'Não avisar neste projeto': project data (not part of Blockbench's undo: toast 'Desfazer') */
	private static void setAck() {
		ProjectDataUndo.Snapshot s = ProjectDataUndo.snapshot();
		ProjectData pd = Project.getData();
		pd.putExtra(LOOSE_ACK, true);
		pd.flush();
		Project.saved = false;
		ProjectDataUndo.offerUndo(s, I18n.get(K + "msg.ackDone"));
	}

	/** true when the user turned the loose-cube warning off in this project */
	public static boolean isLooseAck() {
		try {
			Object v = Project.getData().getExtra(LOOSE_ACK);
			return v != null && (Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v)));
		} catch (Throwable e) {
			return false;
		}
	}

	// ------------------------------------------------------------------ checks + apply

	/** result of {@link #apply} */
	public static final class Result {
		public boolean ok = true, changed;
		public List<String> errors = new ArrayList<>(), warnings = new ArrayList<>(), created = new ArrayList<>();
		public List<Placement> placements = new ArrayList<>();
		public int moved;
	}

	/**
	 * Checks first (red block = nothing changes; yellow warnings = 'Continuar mesmo assim'), then applies.
	 * done (may be null) gets true when the project changed.
	 */
	static void checkThenApply(List<Object> nodes, List<String> targetValues, boolean folder, java.util.function.Consumer<Boolean> done) {
		Result chk = check(nodes, targetValues);
		if (!chk.errors.isEmpty()) {
			PlusDialogs.block(String.join("\n\n", chk.errors));
			if (done != null)done.accept(false);
			return;
		}
		Runnable go = () -> {
			Result r = apply(nodes, targetValues, folder);
			report(r, nodes, targetValues);
			if (done != null)done.accept(r.changed);
		};
		if (chk.warnings.isEmpty()) {
			go.run();
			return;
		}
		PlusDialogs.messageBox("cpmplus.w0binfra.dlg.warn.title", String.join("\n\n", chk.warnings),
				new String[] {"cpmplus.w0binfra.btn.continueAnyway", "cpmplus.w0binfra.btn.cancel"}, i -> {
					if (i == 0)go.run();
					else if (done != null)done.accept(false);
				});
	}

	private static void report(Result r, List<Object> nodes, List<String> targetValues) {
		if (!r.ok) {
			PlusDialogs.block(String.join("\n\n", r.errors));
			return;
		}
		if (!r.changed) {
			PlusDialogs.quick(I18n.get(K + "msg.nothing"), 3000);
			return;
		}
		if (r.moved == 1) {
			int i = 0;
			while (i < targetValues.size() && SKIP.equals(targetValues.get(i)))i++;
			String target = i < targetValues.size() ? targetLabel(targetValues.get(i)) : "";
			PlusDialogs.done(I18n.format(K + "msg.doneOne", name(nodes.get(Math.min(i, nodes.size() - 1))), target));
		} else {
			PlusDialogs.done(I18n.format(K + "msg.doneMany", r.moved));
		}
	}

	/** what would block / warn (no change) */
	public static Result check(List<Object> nodes, List<String> targetValues) {
		Result r = new Result();
		if (!W5StickNatives.isEditMode()) {
			r.ok = false;
			r.errors.add(I18n.get(K + "block.animate"));
			return r;
		}
		SkinType st = RootUtil.projectSkinType();
		Set<String> warned = new LinkedHashSet<>();
		for (int i = 0; i < nodes.size(); i++) {
			Object n = nodes.get(i);
			String tv = i < targetValues.size() ? targetValues.get(i) : SKIP;
			if (tv == null || SKIP.equals(tv))continue;
			if (isBodyPart(n)) {
				r.errors.add(I18n.format(K + "block.root", name(n)));
				continue;
			}
			Group tg = targetGroup(tv);
			VanillaModelPart part = targetPart(tv);
			if (tg == null && part == null) {
				r.errors.add(I18n.format(K + "block.noTarget", name(n)));
				continue;
			}
			if (tg != null && (tg == n || (n instanceof Group && RootUtil.isDescendant(tg, (Group) n)))) {
				r.errors.add(I18n.format(K + "block.self", name(n)));
				continue;
			}
			if (tg != null && Reparent.parentOf(n) == tg)continue;// already there
			// would it need a rotation compensation? then animated position / rotation keys would change meaning
			boolean plain = !Reparent.chainHasRotation(Reparent.parentOf(n)) && (tg != null ? !Reparent.chainHasRotation(tg) : StickMath.isZeroRot(vec(RootUtil.rootRotation(part, st))));
			if (!plain && W5StickNatives.hasTransformKeys(uuid(n))) {
				r.errors.add(I18n.format(K + "block.anim", name(n)));
				continue;
			}
			if (tg != null) {
				VanillaModelPart tp = tg.getRootType();
				if (tp != null && !tg.name.endsWith("_dup")) {
					double[] def = vec(RootUtil.rootOrigin(tp, st)), defR = vec(RootUtil.rootRotation(tp, st));
					double[] o = Reparent.origin(tg), rr = Reparent.rotation(tg);
					if (dist(def, o) > 0.01 || dist(defR, rr) > 0.01) {
						String w = I18n.format(K + "warn.shifted", label(tp), fmt(o[0]) + ", " + fmt(o[1]) + ", " + fmt(o[2]));
						if (warned.add(w))r.warnings.add(w);
					}
				}
				for (Group g : Reparent.groupsOf(n)) {
					String ct = g.copyTransform;
					if (ct != null && !ct.isEmpty()) {
						try {
							JsonMap m = JsonUtil.fromJson(ct);
							Object src = m.get("uuid");
							if (src != null && tg.uuid.equals(String.valueOf(src))) {
								String w = I18n.format(K + "warn.copy", g.name, targetLabel(tv));
								if (warned.add(w))r.warnings.add(w);
							}
						} catch (Throwable e) {
						}
					}
				}
			}
			for (Group g : Reparent.groupsOf(n)) {
				GroupData gd = g.getData();
				if (gd.getItemRenderer() != null) {
					String w = I18n.format(K + "warn.slot", g.name, targetLabel(tv));
					if (warned.add(w))r.warnings.add(w);
				}
			}
		}
		r.ok = r.errors.isEmpty();
		return r;
	}

	/**
	 * Moves every piece to its target in ONE undo entry ('Grudar na parte do corpo'): missing roots are created (and
	 * removed again by Ctrl+Z), parents before children, pieces whose ancestor is also moved are skipped. folder: a
	 * loose cube gets its own group with the pivot at the joint. No dialogs (callers check first).
	 */
	public static Result apply(List<Object> picked, List<String> targetValues, boolean folder) {
		Result r = new Result();
		SkinType st = RootUtil.projectSkinType();
		List<Object> nodes = new ArrayList<>();
		List<String> tvs = new ArrayList<>();
		for (int i = 0; i < picked.size(); i++) {
			String tv = i < targetValues.size() ? targetValues.get(i) : SKIP;
			Object n = picked.get(i);
			if (tv == null || SKIP.equals(tv) || isBodyPart(n))continue;
			Group tg = targetGroup(tv);
			if (tg != null && Reparent.parentOf(n) == tg && !folder)continue;// already there
			nodes.add(n);
			tvs.add(tv);
		}
		// parents before children; a piece inside another moved piece goes along with it
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < nodes.size(); i++) {
			boolean inner = false;
			for (Object o : nodes)if (o != nodes.get(i) && o instanceof Group && RootUtil.isDescendant(nodes.get(i), (Group) o))inner = true;
			if (!inner)order.add(i);
		}
		order.sort(Comparator.comparingInt(i -> depth(nodes.get(i))));
		if (order.isEmpty())return r;

		List<Cube> cubes = new ArrayList<>();
		List<Group> groups = new ArrayList<>();
		for (int i : order) {
			for (Cube c : Reparent.cubesOf(nodes.get(i)))if (!cubes.contains(c))cubes.add(c);
			for (Group g : Reparent.groupsOf(nodes.get(i)))if (!groups.contains(g))groups.add(g);
			Group tg = targetGroup(tvs.get(i));
			if (tg != null && !groups.contains(tg))groups.add(tg);
		}
		List<Group> created = new ArrayList<>();
		List<Cube> createdCubes = new ArrayList<>();
		UndoData before = new UndoData();
		before.outliner = true;
		before.elements = cubes.toArray(new OutlinerElement[0]);
		before.groups = groups.toArray(new Group[0]);
		List<Object> movedNodes = new ArrayList<>();
		PlusUndo.run(I18n.get(K + "undo.stick"), before, () -> {
			UndoData a = new UndoData();
			a.outliner = true;
			List<Cube> el = new ArrayList<>(cubes);
			el.addAll(createdCubes);
			a.elements = el.toArray(new OutlinerElement[0]);
			List<Group> all = new ArrayList<>(groups);
			for (Group g : created)if (!all.contains(g))all.add(g);
			a.groups = all.toArray(new Group[0]);
			return a;
		}, () -> {
			for (int i : order) {
				Object n = nodes.get(i);
				String tv = tvs.get(i);
				Group tg = targetGroup(tv);
				if (tg == null) {
					VanillaModelPart part = targetPart(tv);
					tg = RootUtil.findPart(part);
					if (tg == null) {
						tg = createRoot(part, st, created, createdCubes);
						r.created.add(part.getName());
					}
				}
				Group dest = tg;
				if (folder && n instanceof Cube && ((Cube) n).parent == Group.ROOT) {
					double[] o = Reparent.origin(tg);
					Group f = Js.uncheckedCast(W5StickNatives.newGroup(((Cube) n).name, o[0], o[1], o[2]));
					f.addTo(tg);
					created.add(f);
					r.created.add(f.name);
					dest = f;
				}
				r.placements.add(Reparent.toParent(n, dest));
				movedNodes.add(n);
				r.moved++;
			}
		});
		r.changed = r.moved > 0;
		refresh(movedNodes);
		return r;
	}

	/**
	 * G6 'Mover para o Corpo': the cubes go into the top-level 'body' group (created when missing). Refused (nothing
	 * changes, no undo entry) when that group is rotated: Blockbench cube coordinates are absolute, so the cubes would
	 * turn with it. One undo entry, which also removes a body it created.
	 * @return true when something changed
	 */
	public static boolean moveToBody(List<Cube> cubes) {
		if (!W5StickNatives.isEditMode()) {
			PlusDialogs.block(K + "block.animate");
			return false;
		}
		Group body = findBody();
		if (body != null && !StickMath.isZeroRot(Reparent.rotation(body))) {
			PlusDialogs.block(K + "block.bodyRotated");
			return false;
		}
		List<Cube> todo = new ArrayList<>();
		for (Cube c : cubes)if ((body == null || c.parent != body) && !todo.contains(c))todo.add(c);
		if (todo.isEmpty())return false;
		UndoData before = new UndoData();
		before.outliner = true;
		before.elements = todo.toArray(new OutlinerElement[0]);
		before.groups = body != null ? new Group[] {body} : new Group[0];
		List<Group> made = new ArrayList<>();
		List<Cube> madeCubes = new ArrayList<>();
		PlusUndo.run(I18n.get(K + "undo.body"), before, () -> {
			UndoData a = new UndoData();
			a.outliner = true;
			List<Cube> el = new ArrayList<>(todo);
			el.addAll(madeCubes);
			a.elements = el.toArray(new OutlinerElement[0]);
			List<Group> gs = new ArrayList<>();
			if (body != null)gs.add(body);
			gs.addAll(made);
			a.groups = gs.toArray(new Group[0]);
			return a;
		}, () -> {
			Group b = body;
			if (b == null)b = createRoot(PlayerModelParts.BODY, RootUtil.projectSkinType(), made, madeCubes);
			for (Cube c : todo)Reparent.toParent(c, b);
		});
		refresh(new ArrayList<>(todo));
		PlusDialogs.done(I18n.format(K + "msg.bodyDone", todo.size()));
		return true;
	}

	/**
	 * A missing player root, created WITH its vanilla cube marked vanilla (as the import makes it): without a root group
	 * the export shows CPM's vanilla part, and an unchanged vanilla cube keeps exactly that (an empty root group would
	 * hide the vanilla part in game). Both go into the caller's undo entry (groups / cubes lists).
	 */
	static Group createRoot(VanillaModelPart part, SkinType st, List<Group> groups, List<Cube> cubes) {
		Group g = RootUtil.makeRoot(part.getName(), st);
		g.isOpen = true;
		groups.add(g);
		if (part instanceof PlayerModelParts) {
			RootUtil.CubeSpec spec = RootUtil.vanillaCubeSpec(part, st, g.origin);
			Cube.CubeProperties c = new Cube.CubeProperties();
			c.name = part.getName();
			c.origin = RootUtil.copyVec(g.origin);
			c.from = spec.from;
			c.rotation = JsVec3.make(0, 0, 0);
			c.uv_offset = spec.uvOffset;
			c.mirror_uv = spec.mirror;
			c.inflate = spec.inflate;
			Cube cube = new Cube(c);
			Cube.CubeProperties c2 = new Cube.CubeProperties();
			c2.to = spec.to;
			cube.extend(c2);
			cube.mirror_uv = spec.mirror;
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

	/** the top-level body group (name 'body', any case, not a '_dup'), or null */
	public static Group findBody() {
		for (Group g : Group.all) {
			if (g.parent == Group.ROOT && g.name != null && g.name.equalsIgnoreCase(PlayerModelParts.BODY.getName()))return g;
		}
		return null;
	}

	private static void refresh(List<Object> moved) {
		W5StickNatives.refreshCanvas();
		try {
			ProjectGenerator.updateAll();
		} catch (Throwable e) {
		}
		if (!moved.isEmpty())W5StickNatives.select(moved.toArray());
	}

	// ------------------------------------------------------------------ targets

	/** 'g:&lt;uuid&gt;' -> label for existing roots, 'new:&lt;part&gt;' -> '... (vai ser criada)' for missing player roots */
	public static Map<String, String> targets() {
		Map<String, String> m = new LinkedHashMap<>();
		for (PlayerModelParts p : RootSuggest.PARTS) {
			Group g = RootUtil.findPart(p);
			if (g != null)m.put("g:" + g.uuid, label(p));
			else m.put("new:" + p.getName(), I18n.format(K + "opt.created", label(p)));
		}
		for (Group g : Group.all) {
			if (g.parent != Group.ROOT || m.containsKey("g:" + g.uuid))continue;
			VanillaModelPart p = g.getRootType();
			if (p == null)continue;
			String l = label(p);
			if (g.name.endsWith("_dup"))l = I18n.format(K + "opt.copy", l);
			m.put("g:" + g.uuid, l);
		}
		return m;
	}

	static String preselect(String part, Map<String, String> targets) {
		VanillaModelPart p = RootUtil.part(part);
		Group g = p != null ? RootUtil.findPart(p) : null;
		if (g != null && targets.containsKey("g:" + g.uuid))return "g:" + g.uuid;
		if (targets.containsKey("new:" + part))return "new:" + part;
		return targets.keySet().iterator().next();
	}

	/** the group of a target value ('g:uuid'; also a part name when its root exists), or null */
	static Group targetGroup(String tv) {
		if (tv == null)return null;
		if (tv.startsWith("g:")) {
			String u = tv.substring(2);
			for (Group g : Group.all)if (u.equals(g.uuid))return g;
			return null;
		}
		if (tv.startsWith("new:"))return RootUtil.findPart(RootUtil.part(tv.substring(4)));
		return RootUtil.findPart(RootUtil.part(tv));
	}

	static VanillaModelPart targetPart(String tv) {
		if (tv == null)return null;
		if (tv.startsWith("new:"))return RootUtil.part(tv.substring(4));
		if (tv.startsWith("g:")) {
			Group g = targetGroup(tv);
			return g != null ? g.getRootType() : null;
		}
		return RootUtil.part(tv);
	}

	static String targetLabel(String tv) {
		Group g = targetGroup(tv);
		VanillaModelPart p = targetPart(tv);
		if (p == null)return g != null ? g.name : String.valueOf(tv);
		return g != null && g.name.endsWith("_dup") ? I18n.format(K + "opt.copy", label(p)) : label(p);
	}

	static String label(VanillaModelPart p) {
		return I18n.get("label.cpm.elem." + p.getName());
	}

	static String suggestionText(Suggestion s) {
		String l = label(RootUtil.part(s.part));
		switch (s.kind) {
		case GREEN:
			return I18n.format(K + "sug.green", l);
		case ORANGE:
			return I18n.format(K + "sug.orange", l, s.second != null ? label(RootUtil.part(s.second)) : "?");
		case NEAREST:
			return I18n.format(K + "sug.nearest", l);
		default:
			return I18n.format(K + "sug.none", l);
		}
	}

	// ------------------------------------------------------------------ helpers

	/** groups / cubes only, no duplicates, without pieces whose ancestor is also picked */
	static List<Object> normalize(List<Object> picked) {
		List<Object> nodes = new ArrayList<>();
		for (Object o : picked)if (Reparent.isNode(o) && !nodes.contains(o))nodes.add(o);
		List<Object> out = new ArrayList<>();
		for (Object n : nodes) {
			boolean inner = false;
			for (Object o : nodes)if (o != n && o instanceof Group && RootUtil.isDescendant(n, (Group) o))inner = true;
			if (!inner)out.add(n);
		}
		return out;
	}

	/** a top-level group that is a body part (vanilla root or '_dup') */
	public static boolean isBodyPart(Object n) {
		return n instanceof Group && ((Group) n).parent == Group.ROOT && ((Group) n).getRootType() != null;
	}

	static String name(Object n) {
		return n instanceof Group ? ((Group) n).name : n instanceof Cube ? ((Cube) n).name : "?";
	}

	static String uuid(Object n) {
		return n instanceof Group ? ((Group) n).uuid : n instanceof Cube ? ((Cube) n).uuid : null;
	}

	static int depth(Object n) {
		int d = 0;
		Group p = Reparent.parentOf(n);
		while (p != null && d < 10000) {
			d++;
			p = Reparent.parentOf(p);
		}
		return d;
	}

	static double[] vec(JsVec3 v) {
		return v == null ? new double[3] : new double[] {v.x, v.y, v.z};
	}

	static double dist(double[] a, double[] b) {
		double s = 0;
		for (int i = 0; i < 3; i++)s += (a[i] - b[i]) * (a[i] - b[i]);
		return Math.sqrt(s);
	}

	static String fmt(double v) {
		double r = Math.round(v * 100) / 100.0;
		String s = String.valueOf(r);
		if (s.endsWith(".0"))s = s.substring(0, s.length() - 2);
		return s;
	}
}
