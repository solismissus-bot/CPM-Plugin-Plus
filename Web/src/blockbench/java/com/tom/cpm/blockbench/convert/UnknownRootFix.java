package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootSuggest;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.web.client.util.I18n;

/**
 * CPM+ (W4-EXPORTFIX, G1): the 'unknown root' fix of the export warning as ONE undo entry, in every branch:
 * <ul>
 * <li>rename the group to the root ('head') or to a duplicate root ('head_dup');</li>
 * <li>put the group into the existing root, or into a new root made with the exact vanilla pivot (RootUtil.makeRoot;
 * a duplicate root when asked).</li>
 * </ul>
 * The undo 'before' is taken BEFORE anything changes: outliner + the group + the existing target root; the 'after'
 * also lists the created root, so Ctrl+Z restores names and hierarchy and removes the created root (Outliner.toJSON
 * equal to before the fix), Ctrl+Y brings it back.
 */
public final class UnknownRootFix {

	private UnknownRootFix() {
	}

	/** the root to preselect for group: the suggestion (RootSuggest, W5-STICK makes it geometric) or 'head' */
	public static String preselect(Group group) {
		String s = null;
		try {
			s = RootSuggest.suggestRoot(group);
		} catch (Throwable e) {
		}
		return s != null ? s : PlayerModelParts.VALUES[0].getName();
	}

	/** true when a root group named root exists (the 'rename' box is only allowed when not, or for a duplicate) */
	public static boolean rootExists(String root) {
		return RootUtil.findRootGroup(root) != null;
	}

	/**
	 * Applies the choice of the fix dialog (same branches as the original plugin) as one undo entry.
	 * @return the group that now carries the part (the renamed group or the root it was put into)
	 */
	public static Group apply(Group group, String root, boolean dupRoot, boolean rename, SkinType skinType) {
		Group existing = dupRoot ? null : RootUtil.findRootGroup(root);
		List<Group> beforeGroups = new ArrayList<>();
		beforeGroups.add(group);
		if (existing != null && existing != group)beforeGroups.add(existing);
		UndoData before = PlusUndo.structBefore(beforeGroups.toArray(new Group[0]));
		Group[] target = new Group[1];
		Group[] created = new Group[1];
		PlusUndo.run(I18n.get("cpmplus.w4exportfix.undo.fixRoot"), before, () -> {
			List<Group> after = new ArrayList<>(beforeGroups);
			if (created[0] != null && !after.contains(created[0]))after.add(created[0]);
			return PlusUndo.structAfter(after, null, null);
		}, () -> {
			if (dupRoot) {
				if (rename) {
					group.name = root + "_dup";
					target[0] = group;
					return;
				}
				Group g = RootUtil.makeRoot(root, skinType);
				g.name = root + "_dup";
				created[0] = g;
				group.addTo(g);
				target[0] = g;
			} else if (rename && existing == null) {
				group.name = root;
				target[0] = group;
			} else {
				Group g = existing;
				if (g == null) {
					g = RootUtil.makeRoot(root, skinType);
					created[0] = g;
				}
				group.addTo(g);
				target[0] = g;
			}
		});
		ProjectGenerator.updateAll();
		return target[0];
	}
}
