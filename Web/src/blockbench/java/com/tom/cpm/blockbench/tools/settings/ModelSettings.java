package com.tom.cpm.blockbench.tools.settings;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.ext.W3SettingsNatives;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.blockbench.util.RootUtil.CubeSpec;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;

/**
 * CPM+ (W3-SETTINGS, F11): 'Ajustes do modelo' - the hub of the model-level settings.
 * <ul>
 * <li>Tipo de braço Steve / Alex (ProjectData skinType, customSkinType = true when the user changes it), optionally
 * adjusting the arm cubes to the new width ({@link #adjustArms}: root pivots from DirectParts via RootUtil, vanilla and
 * 2nd-layer cubes via RootUtil.vanillaCubeSpec / secondLayerCubeSpec, ONE Blockbench undo entry).</li>
 * <li>Esconder a cabeça com crânio, tirar deslocamento da armadura / da cama, brilho com invisibilidade.</li>
 * <li>Buttons to the other settings dialogs, registered with {@link #addSection} (Nome/descrição/ícone, Tamanho e
 * atributos, Camadas da skin).</li>
 * </ul>
 * Project-level data is not part of Blockbench's undo: the 'Feito' toast offers 'clique para desfazer'
 * (ProjectDataUndo); the cube change is a normal Ctrl+Z entry.
 */
public final class ModelSettings {
	public static final String DIALOG_ID = "cpmplus_w3settings_hub";

	private ModelSettings() {
	}

	// ------------------------------------------------------------------ section registry

	/** a button of the hub that opens another settings dialog */
	public static final class Section {
		public final String id, labelKey;
		public final int order;
		public final Runnable open;

		Section(String id, String labelKey, int order, Runnable open) {
			this.id = id;
			this.labelKey = labelKey;
			this.order = order;
			this.open = open;
		}
	}

	private static final List<Section> SECTIONS = new ArrayList<>();

	/**
	 * Adds a button to 'Ajustes do modelo' (sorted by order, then registration). Removed on unload or with the returned
	 * Runnable. A section with the same id replaces the old one.
	 */
	public static Runnable addSection(String id, String labelKey, int order, Runnable open) {
		SECTIONS.removeIf(s -> s.id.equals(id));
		Section s = new Section(id, labelKey, order, open);
		int i = 0;
		while(i < SECTIONS.size() && SECTIONS.get(i).order <= order)i++;
		SECTIONS.add(i, s);
		Runnable rm = () -> SECTIONS.remove(s);
		PluginStart.cleanup.add(rm);
		return rm;
	}

	public static List<Section> sections() {
		return new ArrayList<>(SECTIONS);
	}

	// ------------------------------------------------------------------ values

	/** the values of the hub dialog */
	public static final class HubValues {
		public String skinType;
		public boolean adjustArms = true;
		public boolean hideHeadIfSkull, removeArmorOffset, removeBedOffset, invisGlow;

		public static HubValues current() {
			HubValues v = new HubValues();
			ProjectData pd = Project.getData();
			v.skinType = RootUtil.projectSkinType() == SkinType.SLIM ? "slim" : "default";
			v.hideHeadIfSkull = Project.hideHeadIfSkull;
			v.removeArmorOffset = pd.removeArmorOffset;
			v.removeBedOffset = Project.removeBedOffset;
			v.invisGlow = Project.invisGlow;
			return v;
		}
	}

	/** what {@link #apply} did */
	public static final class ApplyResult {
		public boolean changed, skinTypeChanged, armsAdjusted;
		public final List<String> adjusted = new ArrayList<>(), skipped = new ArrayList<>();
		public int undoEntries;
	}

	// ------------------------------------------------------------------ dialog

	public static void open() {
		HubValues cur = HubValues.current();
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.radio("skinType", "cpmplus.w3settings.hub.armType",
				PlusDialogs.options("default", "label.cpm.skin_type.default", "slim", "label.cpm.skin_type.slim"), cur.skinType)
				.description("cpmplus.w3settings.hub.armType.desc"));
		rows.add(FormRow.checkbox("adjustArms", "cpmplus.w3settings.hub.adjustArms", true)
				.description("cpmplus.w3settings.hub.adjustArms.desc")
				.when(r -> !cur.skinType.equals(r.getString("skinType"))));
		rows.add(FormRow.info("noAdjustWarn", null, "cpmplus.w3settings.hub.noAdjustWarn")
				.when(r -> !cur.skinType.equals(r.getString("skinType")) && !r.getBool("adjustArms")));
		rows.add(FormRow.separator());
		rows.add(FormRow.checkbox("hideHeadIfSkull", "cpmplus.w3settings.hub.hideHead", cur.hideHeadIfSkull)
				.description(coreTooltip("tooltip.cpm.effect.hideHeadIfSkull")));
		rows.add(FormRow.checkbox("removeArmorOffset", "cpmplus.w3settings.hub.armorOffset", cur.removeArmorOffset)
				.description("cpmplus.w3settings.hub.armorOffset.desc"));
		rows.add(FormRow.checkbox("removeBedOffset", "cpmplus.w3settings.hub.bedOffset", cur.removeBedOffset)
				.description(coreTooltip("tooltip.cpm.effect.removeBedOffset")));
		rows.add(FormRow.checkbox("invisGlow", "cpmplus.w3settings.hub.invisGlow", cur.invisGlow)
				.description(coreTooltip("tooltip.cpm.effect.enableInvisGlow")));
		List<Section> secs = sections();
		if(!secs.isEmpty()) {
			rows.add(FormRow.separator());
			String[] labels = new String[secs.size()];
			for (int i = 0; i < labels.length; i++)labels[i] = secs.get(i).labelKey;
			rows.add(FormRow.buttons("sections", "cpmplus.w3settings.hub.more", labels, i -> {
				if(i >= 0 && i < secs.size())secs.get(i).open.run();
			}));
		}
		FormOpts o = new FormOpts();
		o.width = 520;
		PlusDialogs.form(DIALOG_ID, "cpmplus.w3settings.hub.title", rows, r -> {
			HubValues v = new HubValues();
			v.skinType = "slim".equals(r.getString("skinType")) ? "slim" : "default";
			v.adjustArms = r.raw("adjustArms") == null || r.getBool("adjustArms");
			v.hideHeadIfSkull = r.getBool("hideHeadIfSkull");
			v.removeArmorOffset = r.getBool("removeArmorOffset");
			v.removeBedOffset = r.getBool("removeBedOffset");
			v.invisGlow = r.getBool("invisGlow");
			apply(v, true);
		}, o);
	}

	/** CPM's own tooltip text with its '\' line breaks turned into new lines */
	private static String coreTooltip(String key) {
		String t = PlusDialogs.tr(key);
		return t.replace("\\", "\n");
	}

	/**
	 * Applies the hub values to the open project. Cube changes (arm width) are ONE Blockbench undo entry; project data
	 * and flags get the 'Desfazer' toast (when toast is true). Nothing changes (and Project.saved stays) when the values
	 * equal the current ones.
	 */
	public static ApplyResult apply(HubValues v, boolean toast) {
		ApplyResult res = new ApplyResult();
		HubValues cur = HubValues.current();
		SkinType from = "slim".equals(cur.skinType) ? SkinType.SLIM : SkinType.DEFAULT;
		SkinType to = "slim".equals(v.skinType) ? SkinType.SLIM : SkinType.DEFAULT;
		res.skinTypeChanged = from != to;
		boolean flagsChanged = cur.hideHeadIfSkull != v.hideHeadIfSkull || cur.removeArmorOffset != v.removeArmorOffset
				|| cur.removeBedOffset != v.removeBedOffset || cur.invisGlow != v.invisGlow;
		if(!res.skinTypeChanged && !flagsChanged)return res;
		res.changed = true;
		ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
		int histBefore = Undo.index;// position, not length: after a Ctrl+Z the new entry replaces the redo one
		if(res.skinTypeChanged && v.adjustArms) {
			adjustArms(from, to, res);
		}
		res.undoEntries = Undo.index - histBefore;
		ProjectData pd = Project.getData();
		if(res.skinTypeChanged) {
			pd.skinType = to.getName();
			pd.customSkinType = true;
		}
		pd.removeArmorOffset = v.removeArmorOffset;
		Project.hideHeadIfSkull = v.hideHeadIfSkull;
		Project.removeBedOffset = v.removeBedOffset;
		Project.invisGlow = v.invisGlow;
		pd.flush();
		Project.saved = false;
		if(toast)offerUndo(before, res, histBefore, to);
		return res;
	}

	private static void offerUndo(ProjectDataUndo.Snapshot before, ApplyResult res, int histBefore, SkinType to) {
		String what = res.skinTypeChanged ? PlusDialogs.tr(to == SkinType.SLIM ? "cpmplus.w3settings.hub.done.slim" : "cpmplus.w3settings.hub.done.default")
				: PlusDialogs.tr("cpmplus.w3settings.hub.done.flags");
		boolean cubes = res.undoEntries > 0;
		String text = PlusDialogs.trf(cubes ? "cpmplus.w3settings.hub.done.undoAll" : "cpmplus.w3settings.hub.done.undo", what);
		PlusDialogs.toast(text, "undo", 15000, () -> {
			if(!ProjectDataUndo.restore(before)) {
				PlusDialogs.quick(PlusDialogs.tr("cpmplus.w3settings.msg.undoOtherProject"), 4000);
				return;
			}
			// the cube change is undone too while it is still the newest Blockbench entry
			if(cubes && Undo.index == histBefore + res.undoEntries && Undo.index == Undo.historyLength())Undo.undo();
			else if(cubes)PlusDialogs.quick(PlusDialogs.tr("cpmplus.w3settings.hub.undoCubesManually"), 5000);
			PlusDialogs.quick(PlusDialogs.tr("cpmplus.w3settings.msg.undone"), 2000);
		});
		if(res.armsAdjusted)PlusDialogs.toast(PlusDialogs.tr("cpmplus.w3settings.hub.textureWarn"), "warning", 12000, null);
		if(!res.skipped.isEmpty())PlusDialogs.toast(PlusDialogs.trf("cpmplus.w3settings.hub.skipped", String.join(", ", res.skipped)), "warning", 12000, null);
	}

	// ------------------------------------------------------------------ arm adjust core

	/** one cube change planned by {@link #adjustArms} */
	private static final class CubePlan {
		final Cube cube;
		final CubeSpec spec;
		final JsVec3 origin;

		CubePlan(Cube cube, CubeSpec spec, JsVec3 origin) {
			this.cube = cube;
			this.spec = spec;
			this.origin = origin;
		}
	}

	/**
	 * Moves the root pivots of the parts whose vanilla geometry differs between the two skin types (the arms) and
	 * refits their unchanged vanilla cube and 2nd skin layer cube to the new type, in ONE undo entry. A root that was
	 * moved keeps its offset; custom cubes and sub-groups keep their absolute position; an edited vanilla / layer cube
	 * is left alone (listed in res.skipped). The texture is not repainted.
	 */
	public static void adjustArms(SkinType from, SkinType to, ApplyResult res) {
		List<Group> groups = new ArrayList<>();
		List<JsVec3> newOrigins = new ArrayList<>();
		List<CubePlan> plans = new ArrayList<>();
		for(PlayerModelParts part : PlayerModelParts.VALUES) {
			if(part == PlayerModelParts.CUSTOM_PART)continue;
			Group g = RootUtil.findPart(part);
			if(g == null || g.origin == null)continue;
			JsVec3 op = RootUtil.rootOrigin(part, from), np = RootUtil.rootOrigin(part, to);
			JsVec3 no = JsVec3.make(g.origin.x + (np.x - op.x), g.origin.y + (np.y - op.y), g.origin.z + (np.z - op.z));
			CubeSpec ov = RootUtil.vanillaCubeSpec(part, from, g.origin);
			CubeSpec nv = RootUtil.vanillaCubeSpec(part, to, no);
			CubeSpec ol = RootUtil.secondLayerCubeSpec(part, from, g.origin);
			CubeSpec nl = RootUtil.secondLayerCubeSpec(part, to, no);
			boolean pivotSame = near(op.x, np.x) && near(op.y, np.y) && near(op.z, np.z);
			if(pivotSame && sameSpec(ov, RootUtil.vanillaCubeSpec(part, to, g.origin)) && sameSpec(ol, RootUtil.secondLayerCubeSpec(part, to, g.origin)))continue;
			String layerName = RootUtil.secondLayerName(part, from);
			boolean any = false;
			for (int i = 0; i < g.children.length; i++) {
				OutlinerNode n = g.children.getAt(i);
				if(!(n instanceof Cube))continue;
				Cube c = (Cube) n;
				boolean vanillaCand = isVanillaFlag(c) || part.getName().equals(c.name);
				boolean layerCand = layerName != null && layerName.equals(c.name);
				if(vanillaCand) {
					if(matches(c, ov)) {
						plans.add(new CubePlan(c, nv, no));
						any = true;
					} else res.skipped.add(c.name);
				} else if(layerCand && ol != null && nl != null) {
					if(matches(c, ol)) {
						plans.add(new CubePlan(c, nl, no));
						any = true;
					} else res.skipped.add(c.name);
				}
			}
			if(!pivotSame || any) {
				groups.add(g);
				newOrigins.add(no);
				res.adjusted.add(g.name);
			}
		}
		if(groups.isEmpty() && plans.isEmpty())return;
		Group[] ga = groups.toArray(new Group[0]);
		OutlinerElement[] ca = new OutlinerElement[plans.size()];
		for (int i = 0; i < ca.length; i++)ca[i] = plans.get(i).cube;
		UndoData before = new UndoData();
		before.elements = ca;
		before.groups = ga;
		PlusUndo.run(PlusDialogs.tr(to == SkinType.SLIM ? "cpmplus.w3settings.hub.undo.slim" : "cpmplus.w3settings.hub.undo.default"), before, () -> {
			UndoData a = new UndoData();
			a.elements = ca;
			a.groups = ga;
			return a;
		}, () -> {
			for (int i = 0; i < ga.length; i++) {
				JsVec3 o = newOrigins.get(i);
				ga[i].origin = JsVec3.make(o.x, o.y, o.z);
			}
			for(CubePlan p : plans) {
				Cube c = p.cube;
				c.from = RootUtil.copyVec(p.spec.from);
				c.to = RootUtil.copyVec(p.spec.to);
				c.origin = RootUtil.copyVec(p.origin);
				c.uv_offset = com.tom.cpm.blockbench.proxy.Vectors.JsVec2.make(p.spec.uvOffset.x, p.spec.uvOffset.y);
				c.inflate = p.spec.inflate;
				c.mirror_uv = p.spec.mirror;
				c.box_uv = true;
			}
		});
		res.armsAdjusted = true;
		W3SettingsNatives.refreshCubes(ca);
	}

	private static boolean isVanillaFlag(Cube c) {
		try {
			return c.getData().isVanilla();
		} catch (Throwable e) {
			return false;
		}
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 0.002f;
	}

	private static boolean sameSpec(CubeSpec a, CubeSpec b) {
		if(a == null || b == null)return a == b;
		return near(a.from.x, b.from.x) && near(a.from.y, b.from.y) && near(a.from.z, b.from.z) &&
				near(a.to.x, b.to.x) && near(a.to.y, b.to.y) && near(a.to.z, b.to.z) &&
				near(a.uvOffset.x, b.uvOffset.x) && near(a.uvOffset.y, b.uvOffset.y) &&
				near(a.inflate, b.inflate) && a.mirror == b.mirror;
	}

	/** the cube still has exactly the vanilla geometry of spec (same rule as the export's 'unchanged vanilla' test) */
	private static boolean matches(Cube c, CubeSpec s) {
		if(c.from == null || c.to == null || s == null)return false;
		if(!near(c.from.x, s.from.x) || !near(c.from.y, s.from.y) || !near(c.from.z, s.from.z))return false;
		if(!near(c.to.x, s.to.x) || !near(c.to.y, s.to.y) || !near(c.to.z, s.to.z))return false;
		if(!c.box_uv || c.uv_offset == null)return false;
		if(!near(c.uv_offset.x, s.uvOffset.x) || !near(c.uv_offset.y, s.uvOffset.y))return false;
		if(!near(c.inflate, s.inflate))return false;
		return c.mirror_uv == s.mirror;
	}
}
