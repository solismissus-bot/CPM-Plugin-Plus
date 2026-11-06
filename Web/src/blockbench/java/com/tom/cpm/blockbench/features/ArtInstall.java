package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.tools.art.ArtCore;

import elemental2.dom.DomGlobal;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of the CPM+ painting tools (beta29 "Arte e cores", beta30 brushes, beta31 "CPM+ Pintar" panel).
 * <ul>
 * <li>The CPM+ Pintar panel (Paint mode, a tab next to the Outliner under the colour picker): the tools as named
 * buttons - Sombrear, Esfumar (blends the tones already in the texture), Esfumar pixel (a checker of the two colours),
 * Pontilhar, Variação, Degradê pixel - the 44 brushes with previews, Criar tons, Trocar cor, Sombra no modelo, Ruído
 * na textura, and the options of the chosen tool ('Mais opções' for the rest). Nothing on Blockbench's top bars.</li>
 * <li>Inside Blockbench's own UI: Color panel (Criar tons / Trocar cor), Textures panel and the texture right-click
 * menu (Ruído, Sombra no modelo, Trocar cor), the brush menu next to the Brush (the CPM+ brushes).</li>
 * </ul>
 * Everything is also in CPM+ &gt; Textura.
 * The JavaScript core (tools/art/ArtCore + the brush and panel modules) owns the tools, the panel and the dialogs; this
 * class owns the CPM+ menu entries. Every tool works in any project with a texture (Cond.ANY_PROJECT).
 */
public final class ArtInstall {
	public static final String A_RAMP = "cpmplus_art_ramp";
	public static final String A_NOISE = "cpmplus_art_noise_tex";
	public static final String A_SWAP = "cpmplus_art_swap";
	public static final String A_AUTOSHADE = "cpmplus_art_autoshade";
	/** beta30: the brush library (Pincéis) */
	public static final String A_BRUSHES = "cpmplus_art_brush_library_menu";

	private ArtInstall() {
	}

	public static void install() {
		Action ramp = PlusMenus.action(A_RAMP, "cpmplus.art.ramp.menu", "color_lens", Cond.ANY_PROJECT, () -> ArtCore.open("ramp"));
		Action shade = PlusMenus.action("cpmplus_art_pick_shade", "cpmplus.art.shade.menu", "tonality", Cond.ANY_PROJECT, () -> ArtCore.selectTool("cpmplus_art_shade"));
		Action smudge = PlusMenus.action("cpmplus_art_pick_smudge", "cpmplus.art.smudge.menu", "blur_on", Cond.ANY_PROJECT, () -> ArtCore.selectTool("cpmplus_art_smudge"));
		Action dither = PlusMenus.action("cpmplus_art_pick_dither", "cpmplus.art.dither.menu", "apps", Cond.ANY_PROJECT, () -> ArtCore.selectTool("cpmplus_art_dither"));
		Action grad = PlusMenus.action("cpmplus_art_pick_gradient", "cpmplus.art.grad.menu", "gradient", Cond.ANY_PROJECT, ArtCore::pixelGradient);
		Action noise = PlusMenus.action("cpmplus_art_pick_noise", "cpmplus.art.noise.menu", "grain", Cond.ANY_PROJECT, () -> ArtCore.selectTool("cpmplus_art_noise"));
		Action tnoise = PlusMenus.action(A_NOISE, "cpmplus.art.tnoise.menu", "grain", Cond.ANY_PROJECT, () -> ArtCore.open("noise"));
		Action swap = PlusMenus.action(A_SWAP, "cpmplus.art.swap.menu", "find_replace", Cond.ANY_PROJECT, () -> ArtCore.open("swap"));
		Action auto = PlusMenus.action(A_AUTOSHADE, "cpmplus.art.as.menu", "wb_shade", Cond.ANY_PROJECT, () -> ArtCore.open("autoshade"));
		Action again = PlusMenus.action("cpmplus_art_show_again", "cpmplus.art.showAgain", "palette", Cond.ANY_PROJECT, ArtCore::showToolsAgain);
		Action brushes = PlusMenus.action(A_BRUSHES, "cpmplus.art.br.menu", "brush", Cond.ANY_PROJECT, () -> ArtCore.showPanel("brushes"));
		Action smudgePx = PlusMenus.action("cpmplus_art_pick_smudge_px", "cpmplus.art.smudgepx.menu", "grid_on", Cond.ANY_PROJECT, () -> ArtCore.selectTool("cpmplus_art_smudge_px"));
		for (Action a : new Action[] {again, brushes, shade, smudge, smudgePx, dither, noise, grad, ramp, swap, tnoise, auto})PlusMenus.add(PlusMenus.SUB_TEX, a);
		JsPropertyMap<Object> ids = JsPropertyMap.of();
		ids.set("ramp", A_RAMP);
		ids.set("swap", A_SWAP);
		ids.set("noise", A_NOISE);
		ids.set("autoshade", A_AUTOSHADE);
		try {
			ArtCore.start(ids, PlusTestHooks.active());
		} catch (Throwable e) {
			DomGlobal.console.error("CPM+: the art tools could not start", e);
		}
	}

	public static void uninstall() {
		ArtCore.unload();
	}
}
