package com.tom.cpm.blockbench.tools.cmd;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.util.ButtonIdCheck;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Anim;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Finding;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Kind;
import com.tom.cpm.blockbench.util.ButtonIdCheck.Severity;
import com.tom.cpm.blockbench.util.KeybindIds;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpl.util.Pair;

import jsinterop.base.Js;

/**
 * CPM+ (W5-CMDS, G31 + G11): renaming animations whose names mix up in game, and 'Voltar ao nome antigo'. Only the
 * button id (the part before '#') is edited: a '#suffix' and the linked start / end stages ('g:', 'c:' names) follow
 * along. Every change is ONE undo entry ('Renomear anima\u00e7\u00f5es CPM'). Nothing is ever renamed without the user's click.
 */
public final class RenameAnims {
	public static final String DIALOG_ID = "cpmplus_w5cmds_rename";

	private RenameAnims() {
	}

	/** the part after the id ('#a' in 'Azul#a'), "" when none */
	static String suffixOf(String name) {
		if (name == null)return "";
		int i = name.indexOf('#');
		return i < 0 ? "" : name.substring(i);
	}

	static Animation bbOf(Anim a) {
		if (a.ref instanceof Animation)return (Animation) a.ref;
		if (a.ref instanceof String && Animation.all != null) {
			for (Animation an : Animation.all)if (an != null && a.ref.equals(an.uuid))return an;
		}
		return null;
	}

	static boolean isStaged(Animation an) {
		if (an.type == null || !"string".equals(Js.typeof(an.type)))return false;
		Pair<AnimationType, VanillaPose> t = Animation.parseType(an.type);
		return t.getKey().isStaged();
	}

	/** the start / end stages linked to a main animation by name ('g:id' for gestures / layers, 'c:name' for poses) */
	static List<Animation> linked(Animation main) {
		List<Animation> out = new ArrayList<>();
		Anim m = CommandSheet.ofBB(main);
		if (m == null || Animation.all == null)return out;
		String key;
		if (m.kind == Kind.CUSTOM_POSE)key = "c:" + main.name;
		else if (m.kind == Kind.GESTURE || m.kind == Kind.LAYER)key = "g:" + m.getId();
		else return out;
		for (Animation an : Animation.all) {
			if (an == null || an == main || !isStaged(an) || an.name == null)continue;
			String n = an.name;
			if (n.equalsIgnoreCase(key) || (m.kind != Kind.CUSTOM_POSE && n.length() > key.length() && n.substring(0, key.length()).equalsIgnoreCase(key) && n.charAt(key.length()) == '#'))
				out.add(an);
		}
		return out;
	}

	/** renames main (new id, suffix kept) and its linked stages; must run inside an undo edit that records them */
	static void renameNoUndo(Animation main, String newId) {
		Anim m = CommandSheet.ofBB(main);
		List<Animation> link = linked(main);
		String oldName = main.name;
		String newName = newId + suffixOf(oldName);
		main.name = newName;
		for (Animation st : link) {
			String n = st.name;
			if (m != null && m.kind == Kind.CUSTOM_POSE)st.name = "c:" + newName;
			else st.name = "g:" + newId + suffixOf(n.substring(2));
		}
	}

	/** every animation one rename touches (main + linked stages), for the undo entry */
	static List<Animation> touched(List<Animation> mains) {
		Set<Animation> s = new LinkedHashSet<>();
		for (Animation a : mains) {
			s.add(a);
			s.addAll(linked(a));
		}
		return new ArrayList<>(s);
	}

	/** the rename dialog for the findings of the open project (CPM+ > Anima\u00e7\u00e3o, the export warning's fix) */
	public static void openForProject(Consumer<Boolean> done) {
		ButtonIdCheck.Result r = ButtonIdCheck.check(CommandSheet.animsOfProject(null, null));
		List<Finding> fs = new ArrayList<>();
		for (Finding f : r.findings)if (f.severity != Severity.GREY)fs.add(f);
		if (fs.isEmpty()) {
			PlusDialogs.info("cpmplus_w5cmds_rename_none", "cpmplus.w5cmds.rename.title", java.util.Collections.singletonList("cpmplus.w5cmds.rename.none"));
			if (done != null)done.accept(false);
			return;
		}
		open(fs, done);
	}

	/**
	 * Rename dialog for findings: one field per animation involved (its id, without '#suffix'); suggestions keep the
	 * first name and add '(group / type)' to the others. Live check on OK: the new names must be non-empty, without
	 * '#', and must not mix up again.
	 */
	public static void open(List<Finding> findings, Consumer<Boolean> done) {
		List<Animation> anims = new ArrayList<>();
		List<String> suggestions = new ArrayList<>();
		List<String> lines = new ArrayList<>();
		boolean cmdOrProp = false;
		for (Finding f : findings) {
			lines.add(CommandSheet.describe(f));
			boolean first = true;
			for (Anim a : f.anims) {
				Animation bb = bbOf(a);
				if (bb == null || anims.contains(bb))continue;
				boolean keep;
				if ("GROUP_CONFLICT".equals(f.code) || "GROUP_VS_OPTION".equals(f.code))keep = a.kind == Kind.LAYER && a.group != null && !a.getId().equals(f.name);
				else keep = first;
				first = false;
				anims.add(bb);
				String id = a.getId();
				String extra = a.kind == Kind.LAYER && a.group != null ? a.group : CommandSheet.kindLabel(a);
				suggestions.add(keep || id.isEmpty() ? (id.isEmpty() ? PlusDialogs.tr("cpmplus.w5cmds.rename.emptySuggestion") : id) : id + " (" + extra + ")");
				if (a.command || a.isProperty)cmdOrProp = true;
			}
		}
		if (anims.isEmpty()) {
			PlusDialogs.block(PlusDialogs.tr("cpmplus.w5cmds.rename.nothing"));
			if (done != null)done.accept(false);
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("why", null, String.join("\n", lines) + "\n" + PlusDialogs.tr("cpmplus.w5cmds.order")));
		for (int i = 0; i < anims.size(); i++) {
			Animation an = anims.get(i);
			Anim a = CommandSheet.ofBB(an);
			String label = "'" + an.name + "' (" + (a != null ? CommandSheet.kindLabel(a) : "?") + ")";
			rows.add(FormRow.text("n" + i, label, suggestions.get(i)).description("cpmplus.w5cmds.rename.field.desc"));
		}
		if (cmdOrProp)rows.add(FormRow.info("warn", null, "cpmplus.w5cmds.rename.warnCommand"));
		rows.add(FormRow.info("saved", null, "cpmplus.w5cmds.rename.warnSaved"));
		PlusDialogs.FormOpts o = new PlusDialogs.FormOpts();
		o.confirmKey = "cpmplus.w5cmds.rename.apply";
		o.width = 620;
		o.onCancel = () -> {
			if (done != null)done.accept(false);
		};
		PlusDialogs.form(DIALOG_ID, "cpmplus.w5cmds.rename.title", rows, res -> {
			List<String> ids = new ArrayList<>();
			for (int i = 0; i < anims.size(); i++) {
				String v = res.getString("n" + i);
				v = v == null ? "" : v.trim();
				if (v.isEmpty() || v.indexOf('#') >= 0) {
					PlusDialogs.block(PlusDialogs.trf("cpmplus.w5cmds.rename.invalid", anims.get(i).name));
					res.keepOpen();
					return;
				}
				ids.add(v);
			}
			String clash = clashAfter(anims, ids);
			if (clash != null) {
				PlusDialogs.block(PlusDialogs.trf("cpmplus.w5cmds.rename.stillMixed", clash));
				res.keepOpen();
				return;
			}
			apply(anims, ids);
			PlusDialogs.done(PlusDialogs.tr("cpmplus.w5cmds.rename.done"));
			if (done != null)done.accept(true);
		}, o);
	}

	/** a name that would still mix up after renaming, or null */
	static String clashAfter(List<Animation> anims, List<String> ids) {
		List<Anim> all = new ArrayList<>();
		if (Animation.all == null)return null;
		Set<Animation> renamed = new LinkedHashSet<>(anims);
		for (Animation an : Animation.all) {
			if (an == null)continue;
			Anim a = CommandSheet.ofBB(an);
			if (a == null)continue;
			if (renamed.contains(an)) {
				String nn = ids.get(anims.indexOf(an)) + suffixOf(an.name);
				Anim b = new Anim(a.kind, nn, a.group);
				b.ref = an;
				all.add(b);
			} else all.add(a);
		}
		ButtonIdCheck.Result r = ButtonIdCheck.check(all);
		for (Finding f : r.findings) {
			if (f.severity == Severity.GREY)continue;
			for (Anim a : f.anims)if (renamed.contains(a.ref))return f.name;
		}
		return null;
	}

	/** renames the animations (new ids, suffixes kept, linked stages follow) in ONE undo entry */
	public static void apply(List<Animation> anims, List<String> ids) {
		List<Animation> touched = touched(anims);
		PlusUndo.editAnimations(touched, PlusDialogs.tr("cpmplus.w5cmds.rename.undo"), () -> {
			for (int i = 0; i < anims.size(); i++)renameNoUndo(anims.get(i), ids.get(i));
		});
	}

	/**
	 * G11 'Voltar ao nome antigo': gives the animations (or the layer group) that now use 'after' the old name again,
	 * in ONE undo entry. false when nothing matched.
	 */
	public static boolean revert(KeybindIds.Rename r) {
		if (Animation.all == null)return false;
		List<Animation> mains = new ArrayList<>();
		List<Animation> groupMembers = new ArrayList<>();
		for (Animation an : Animation.all) {
			if (an == null)continue;
			Anim a = CommandSheet.ofBB(an);
			if (a == null || !a.makesButton())continue;
			switch (r.after.type) {
			case "p":
				if (a.kind == Kind.CUSTOM_POSE && a.getId().equals(r.after.name))mains.add(an);
				break;
			case "g":
				if (a.kind == Kind.GESTURE && a.getId().equals(r.after.name))mains.add(an);
				break;
			case "l":
				if (a.kind == Kind.LAYER && a.group == null && a.getId().equals(r.after.name))mains.add(an);
				break;
			case "v":
				if (a.kind == Kind.VALUE_LAYER && a.getId().equals(r.after.name))mains.add(an);
				break;
			case "o":
				if (a.kind == Kind.LAYER && r.after.group != null && r.after.group.equals(a.group) && a.getId().equals(r.after.name))mains.add(an);
				break;
			case "d":
				if (a.kind == Kind.LAYER && r.after.name.equals(a.group))groupMembers.add(an);
				break;
			default:
			}
		}
		if (mains.isEmpty() && groupMembers.isEmpty())return false;
		List<Animation> touched = touched(mains);
		for (Animation g : groupMembers)if (!touched.contains(g))touched.add(g);
		PlusUndo.editAnimations(touched, PlusDialogs.tr("cpmplus.w5cmds.rename.undoRevert"), () -> {
			for (Animation m : mains)renameNoUndo(m, r.before.name);
			for (Animation g : groupMembers)g.group = r.before.name;
		});
		return true;
	}
}
