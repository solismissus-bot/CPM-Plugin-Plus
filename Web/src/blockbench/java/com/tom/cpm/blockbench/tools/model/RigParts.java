package com.tom.cpm.blockbench.tools.model;

import java.util.List;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.ext.W4RigNatives;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.shared.model.PlayerModelParts;

import elemental2.core.JsArray;

/** CPM+ (G18 / G19): small shared helpers of the rig tools (part names in pt-BR / en, subtree walk, selection). */
public final class RigParts {
	public static final PlayerModelParts[] PARTS = {PlayerModelParts.HEAD, PlayerModelParts.BODY, PlayerModelParts.LEFT_ARM,
			PlayerModelParts.RIGHT_ARM, PlayerModelParts.LEFT_LEG, PlayerModelParts.RIGHT_LEG};

	private RigParts() {
	}

	/** 'Cabeça', 'Braço esquerdo' ... */
	public static String label(PlayerModelParts p) {
		return PlusDialogs.tr("cpmplus.w4rig.part." + p.getName());
	}

	/** g and every group / cube under it */
	static void collect(Group g, List<Cube> cubes, List<Group> groups) {
		groups.add(g);
		if (g.children == null)return;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (n instanceof Group)collect((Group) n, cubes, groups);
			else if (n instanceof Cube)cubes.add((Cube) n);
		}
	}

	static void select(Group g) {
		JsArray<Object> a = new JsArray<>();
		a.push(g);
		W4RigNatives.selectNodes(a);
	}
}
