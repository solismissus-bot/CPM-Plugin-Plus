package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.tools.pose.WholeBodyPose;

/**
 * Feature installer of G59 'Girar/mover o corpo inteiro…' (CPM+ v0.8, CPM+ &gt; Animação). Test tool
 * 'w7pose.wholeBody' in test mode.
 */
public final class W7PoseInstall {
	private W7PoseInstall() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "w7pose_body", "cpmplus.w7pose.menu.body", "accessibility_new", Cond.CPM_ONLY, WholeBodyPose::open);
		if (PlusTestHooks.active())WholeBodyPose.registerTestHooks();
	}

	public static void uninstall() {
	}
}
