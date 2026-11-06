package com.tom.cpm.blockbench.features;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.tools.art.ArtCore;

import elemental2.dom.DomGlobal;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of "Arte e cores" (CPM+ beta29): pixel-art painting tools in Blockbench's own Paint mode UI.
 * <ul>
 * <li>Brushes (Paint toolbar, right after the Brush): Sombra (hue shift / ramp step / HSL), Esfumar (smooth / drag
 * pixels / jumble / blur), Pontilhado (Bayer, lines, dots), Variação (per-pixel noise); options on the brush bar.</li>
 * <li>Gradient tool: a 'Pixel art' style (steps, ramps, dither between neighbouring tones, linear / radial).</li>
 * <li>Colour panel (under the picker and in the Palette tab): Gerar rampa, Trocar cores (família).</li>
 * <li>Textures panel: Ruído na textura, Sombra automática (light per face, gradient, contact shadow, edges).</li>
 * </ul>
 * Everything is also in CPM+ &gt; Textura.
 * The JavaScript core (tools/art/ArtCore) owns the brushes, toolbar items and dialogs; this class owns the CPM+ menu
 * entries. Every tool works in any project with a texture (Cond.ANY_PROJECT): painting never touches CPM data.
 */
public final class ArtInstall {
	public static final String A_RAMP = "cpmplus_art_ramp";
	public static final String A_NOISE = "cpmplus_art_noise_tex";
	public static final String A_SWAP = "cpmplus_art_swap";
	public static final String A_AUTOSHADE = "cpmplus_art_autoshade";

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
		Action again = PlusMenus.action("cpmplus_art_show_again", "cpmplus.art.showAgain", "build", Cond.ANY_PROJECT, ArtCore::showToolsAgain);
		for (Action a : new Action[] {shade, smudge, dither, noise, grad, ramp, swap, tnoise, auto, again})PlusMenus.add(PlusMenus.SUB_TEX, a);
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
