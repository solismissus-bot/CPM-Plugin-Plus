package com.tom.cpm.blockbench.tools.model;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.web.client.util.I18n;

import elemental2.promise.Promise;

/**
 * CPM+ (G2): 'parte do corpo fantasma'. The export starts from the default player model (all six vanilla parts
 * visible) and only hides a part when a ROOT group with its name is exported; so deleting 'left_leg', turning its
 * export off or putting it inside another group makes the normal Minecraft leg show in game while Blockbench shows
 * nothing there. One row per such part (never blocking), checked on the converted model (the part is really visible):
 * <ul>
 * <li>missing: fix 'Esconder a parte normal' (an empty root group with the vanilla pivot of the project's skin type)
 * or 'Quero a parte normal' (the root group with the vanilla part cube: Blockbench then shows what the game shows);</li>
 * <li>export off: fix 'Ligar a exportação da pasta';</li>
 * <li>inside another group: shown, no automatic fix (moving it out changes its place).</li>
 * </ul>
 * Armor and held items stay on the part's pivot either way (the rows say so).
 */
public final class GhostParts {
	static final PlayerModelParts[] PARTS = {PlayerModelParts.HEAD, PlayerModelParts.BODY, PlayerModelParts.LEFT_ARM,
			PlayerModelParts.RIGHT_ARM, PlayerModelParts.LEFT_LEG, PlayerModelParts.RIGHT_LEG};
	public static final String KEY_MISSING = "ghostPartMissing", KEY_OFF = "ghostPartOff", KEY_NESTED = "ghostPartNested";
	private static final String K = "cpmplus.w6save.ghost.";

	private GhostParts() {
	}

	public static void install() {
		// second choice on the 'missing' row: keep the normal part and show it in Blockbench
		WarnFixes.register(KEY_MISSING, K + "fix.keep", 10, w -> {
			Object[] a = w.getArgs();
			String name = a != null && a.length > 1 ? String.valueOf(a[1]) : null;
			return name == null ? null : () -> createRoot(name, true);
		});
	}

	static Group rootGroup(String name, boolean exportedOnly) {
		if (Group.all == null)return null;
		for (Group g : Group.all) {
			if (g.parent != Group.ROOT || g.name == null || !g.name.equalsIgnoreCase(name))continue;
			if (!exportedOnly || g.export)return g;
		}
		return null;
	}

	static Group nestedGroup(String name) {
		if (Group.all == null)return null;
		for (Group g : Group.all)if (g.parent != Group.ROOT && g.name != null && g.name.equalsIgnoreCase(name))return g;
		return null;
	}

	private static String parentName(Group g) {
		Object p = g.parent;
		return p instanceof Group ? ((Group) p).name : "?";
	}

	public static void check(ExportContext ctx) {
		if (ctx.editor == null)return;
		for (PlayerModelParts p : PARTS) {
			ModelElement me = null;
			for (ModelElement e : ctx.editor.elements)if (e.type == ElementType.ROOT_PART && e.typeData == p && !e.duplicated) {
				me = e;
				break;
			}
			if (me == null || me.hidden)continue;// the user's own part (or hidden): nothing shows by surprise
			String n = p.getName();
			if (rootGroup(n, true) != null)continue;// exported root group with the vanilla cube: Blockbench shows it too
			String label = GroupEffects.partLabel(p);
			WarnEntry w;
			Group off = rootGroup(n, false);
			Group nested = off == null ? nestedGroup(n) : null;
			if (off != null) {
				w = WarnFactory.of(Severity.DIFFERS, KEY_OFF, K + "off", label, off.name);
				w.setFix(() -> enableExport(off), FixKind.ASKS);
				w.setFixPreview(I18n.get(K + "fix.enable"));
				w.addNode(off.uuid, off.name);
			} else if (nested != null) {
				w = WarnFactory.of(Severity.DIFFERS, KEY_NESTED, K + "nested", label, nested.name, parentName(nested));
				w.addNode(nested.uuid, nested.name);
			} else {
				w = WarnFactory.of(Severity.DIFFERS, KEY_MISSING, K + "missing", label, n);
				w.setFix(() -> createRoot(n, false), FixKind.ASKS);
				w.setFixPreview(I18n.get(K + "fix.hide"));
			}
			w.setConsequence(K + "consequence");
			ctx.add(w);
		}
	}

	/** turns the export of the root group back on (one undo entry) */
	static Promise<Boolean> enableExport(Group g) {
		List<Group> l = new ArrayList<>();
		l.add(g);
		PlusUndo.run(I18n.get(K + "undo.enable"), PlusUndo.structBefore(g), () -> PlusUndo.structAfter(l, null, null), () -> g.export = true);
		return Promise.resolve(true);
	}

	/**
	 * the missing root group of the part, one undo entry: empty (the normal part is hidden in game) or with the vanilla
	 * part cube (the normal part stays and Blockbench shows it)
	 */
	public static Promise<Boolean> createRoot(String name, boolean withVanillaCube) {
		if (rootGroup(name, false) != null)return Promise.resolve(false);// never a second group with the same name
		List<Group> groups = new ArrayList<>();
		List<OutlinerElement> cubes = new ArrayList<>();
		List<Texture> tex = new ArrayList<>();
		PlusUndo.run(I18n.get(withVanillaCube ? K + "undo.keep" : K + "undo.hide"), PlusUndo.structBefore(), () -> PlusUndo.structAfter(groups, cubes, tex), () -> {
			Group g;
			if (withVanillaCube) {
				g = MirrorToOtherSide.createRoot(name, groups, cubes, tex, new ArrayList<>());
			} else {
				g = RootUtil.makeRoot(name, RootUtil.projectSkinType());
				g.name = name;
				groups.add(g);
			}
			if (g != null && g.parent != Group.ROOT)g.addTo(Group.ROOT, -1);
		});
		return Promise.resolve(true);
	}
}
