package com.tom.cpm.blockbench.tools.particles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.tools.particles.ParticleSprites.Sprite;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.RootUtil;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (W6-PARTICLES): 'Partículas com sua imagem…' - preset-first dialog in pt-BR: image + style + where from +
 * when, everything else under 'Mostrar opções avançadas'. The honest 'Como isso funciona' text and the per-audience
 * counter are always visible. Also 'Editar partículas…' (same dialog filled with the saved parameters).
 */
public final class ParticleDialog {
	public static final String DIALOG_ID = "cpmplus_w6particles";
	static final String BUDGET_EL = "cpmplus_w6particles_budget";
	static final String NOTES_EL = "cpmplus_w6particles_notes";

	private ParticleDialog() {
	}

	/** open for a new system (fromGroup: 'Soltar partículas daqui' on that group, else null) */
	public static void openNew(Group fromGroup) {
		ParticleParams p = new ParticleParams();
		if (fromGroup != null) {
			p.where = "selected";
			p.parentUuid = fromGroup.uuid;
		}
		open(p, null);
	}

	/** 'Editar partículas…': the saved parameters; warns first when the keys were edited by hand */
	public static void openEdit(Group system) {
		ParticleParams p = ParticleBuilder.paramsOf(system);
		if (p == null) {
			PlusDialogs.block("cpmplus.w6particles.err.notSystem");
			return;
		}
		if (ParticleBuilder.editedByHand(system)) {
			PlusDialogs.warnContinue(PlusDialogs.tr("cpmplus.w6particles.edit.handEdited"), () -> open(p, system));
		} else open(p, system);
	}

	static void open(ParticleParams init, Group old) {
		String[] customPng = {init.customPng != null ? init.customPng : ""};
		ParticleBudget.Measure[] measure = {null};
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("how", "cpmplus.w6particles.how.title", "cpmplus.w6particles.how.text"));
		rows.add(FormRow.select("image", "cpmplus.w6particles.f.image", PlusDialogs.options(
				"sparkle", "cpmplus.w6particles.img.sparkle", "heart", "cpmplus.w6particles.img.heart", "smoke", "cpmplus.w6particles.img.smoke",
				"spark", "cpmplus.w6particles.img.spark", "petal", "cpmplus.w6particles.img.petal", "portal", "cpmplus.w6particles.img.portal",
				"custom", "cpmplus.w6particles.img.custom"), init.image));
		rows.add(FormRow.buttons("pick", "cpmplus.w6particles.f.pick", new String[] {"cpmplus.w6particles.btn.pick"}, i -> pickImage(customPng)).
				when(r -> "custom".equals(r.getString("image"))));
		rows.add(FormRow.select("style", "cpmplus.w6particles.f.style", PlusDialogs.options(
				"burst", "cpmplus.w6particles.style.burst", "orbit", "cpmplus.w6particles.style.orbit", "rain", "cpmplus.w6particles.style.rain",
				"sparkle", "cpmplus.w6particles.style.sparkle", "smoke", "cpmplus.w6particles.style.smoke", "behind", "cpmplus.w6particles.style.behind"), init.style));
		Map<String, String> where = PlusDialogs.options(
				"body", "cpmplus.w6particles.where.body", "head", "cpmplus.w6particles.where.head", "feet", "cpmplus.w6particles.where.feet",
				"right_hand", "cpmplus.w6particles.where.rightHand", "left_hand", "cpmplus.w6particles.where.leftHand");
		Group sel = selectedGroup();
		String selUuid = "selected".equals(init.where) && !init.parentUuid.isEmpty() ? init.parentUuid : sel != null ? sel.uuid : "";
		Group selG = ParticleBuilder.byUuid(selUuid);
		if (selG != null)where.put("selected", PlusDialogs.trf("cpmplus.w6particles.where.selected", selG.name));
		rows.add(FormRow.select("where", "cpmplus.w6particles.f.where", where, init.where));
		Map<String, String> when = PlusDialogs.options(
				"always", "cpmplus.w6particles.when.always", "idle", "cpmplus.w6particles.when.idle", "walk", "cpmplus.w6particles.when.walk",
				"gesture_once", "cpmplus.w6particles.when.gestureOnce", "gesture_loop", "cpmplus.w6particles.when.gestureLoop",
				"toggle", "cpmplus.w6particles.when.toggle");
		Map<String, String> anims = new LinkedHashMap<>();
		if (Animation.all != null) {
			ParticleParams own = init;
			for (Animation a : Animation.all) {
				if (a == null || a.uuid == null)continue;
				if (old != null && own.animCreated && a.uuid.equals(own.animUuid))continue;
				anims.put(a.uuid, a.name);
			}
		}
		if (!anims.isEmpty())when.put("with_anim", "cpmplus.w6particles.when.withAnim");
		rows.add(FormRow.select("trigger", "cpmplus.w6particles.f.when", when, init.trigger));
		if (!anims.isEmpty()) {
			String def = anims.containsKey(init.withAnim) ? init.withAnim : anims.keySet().iterator().next();
			rows.add(FormRow.select("withAnim", "cpmplus.w6particles.f.withAnim", anims, def).when(r -> "with_anim".equals(r.getString("trigger"))));
		}
		rows.add(FormRow.text("name", "cpmplus.w6particles.f.name", init.name));
		rows.add(FormRow.htmlInfo("budget", "cpmplus.w6particles.f.budget", "<div id='" + BUDGET_EL + "'>" + PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6particles.budget.measuring")) + "</div>"));
		rows.add(FormRow.htmlInfo("notes", "cpmplus.w6particles.f.notes", "<div id='" + NOTES_EL + "'></div>"));
		rows.add(FormRow.checkbox("adv", "cpmplus.w6particles.f.advanced", false));
		rows.add(FormRow.number("count", "cpmplus.w6particles.f.count", init.count, 1, ParticleMath.MAX_ANIMATED_PARTS).when(r -> r.getBool("adv")).
				description("cpmplus.w6particles.f.count.desc"));
		rows.add(FormRow.decimal("life", "cpmplus.w6particles.f.life", init.life, 0.2, 32, 0.1).when(r -> r.getBool("adv")).description("cpmplus.w6particles.f.life.desc"));
		rows.add(FormRow.number("fps", "cpmplus.w6particles.f.fps", init.fps, 2, 60).when(r -> r.getBool("adv")).description("cpmplus.w6particles.f.fps.desc"));
		rows.add(FormRow.range("distance", "cpmplus.w6particles.f.distance", init.distance, 0.25, 3, 0.05).when(r -> r.getBool("adv")).description("cpmplus.w6particles.f.distance.desc"));
		rows.add(FormRow.range("size", "cpmplus.w6particles.f.size", init.size, 0.25, 4, 0.05).when(r -> r.getBool("adv")));
		rows.add(FormRow.select("fade", "cpmplus.w6particles.f.fade", PlusDialogs.options(
				"shrink", "cpmplus.w6particles.fade.shrink", "blink", "cpmplus.w6particles.fade.blink", "darken", "cpmplus.w6particles.fade.darken"), init.fade).
				when(r -> r.getBool("adv")));
		rows.add(FormRow.select("color", "cpmplus.w6particles.f.color", PlusDialogs.options(
				"keep", "cpmplus.w6particles.color.keep", "gradient", "cpmplus.w6particles.color.gradient"), init.color).when(r -> r.getBool("adv")));
		rows.add(FormRow.color("colorA", "cpmplus.w6particles.f.colorA", init.colorA).when(r -> r.getBool("adv") && "gradient".equals(r.getString("color"))));
		rows.add(FormRow.color("colorB", "cpmplus.w6particles.f.colorB", init.colorB).when(r -> r.getBool("adv") && "gradient".equals(r.getString("color"))));
		rows.add(FormRow.checkbox("glow", "cpmplus.w6particles.f.glow", init.glow).when(r -> r.getBool("adv")).description("cpmplus.w6particles.f.glow.desc"));
		rows.add(FormRow.range("spin", "cpmplus.w6particles.f.spin", init.spin, 0, 1, 0.05).when(r -> r.getBool("adv")));
		rows.add(FormRow.number("seed", "cpmplus.w6particles.f.seed", (int) init.seed, 0, 999999).when(r -> r.getBool("adv")).description("cpmplus.w6particles.f.seed.desc"));
		rows.add(FormRow.decimal("offX", "cpmplus.w6particles.f.offX", init.offX, -40, 40, 0.5).when(r -> r.getBool("adv")));
		rows.add(FormRow.decimal("offY", "cpmplus.w6particles.f.offY", init.offY, -40, 40, 0.5).when(r -> r.getBool("adv")));
		rows.add(FormRow.decimal("offZ", "cpmplus.w6particles.f.offZ", init.offZ, -40, 40, 0.5).when(r -> r.getBool("adv")));
		rows.add(FormRow.checkbox("hideFirstPerson", "cpmplus.w6particles.f.hideFp", init.hideFirstPerson).when(r -> r.getBool("adv")).description("cpmplus.w6particles.f.hideFp.desc"));
		rows.add(FormRow.info("preview", null, "cpmplus.w6particles.previewNote"));

		FormOpts o = new FormOpts();
		o.width = 620;
		o.confirmKey = old != null ? "cpmplus.w6particles.btn.regenerate" : "cpmplus.w6particles.btn.create";
		o.onChange = r -> refresh(fromForm(r, init, customPng[0], selUuid), measure[0]);
		PlusDialogs.form(DIALOG_ID, old != null ? "cpmplus.w6particles.title.edit" : "cpmplus.w6particles.title", rows, r -> {
			ParticleParams p = fromForm(r, init, customPng[0], selUuid);
			if ("custom".equals(p.image) && (p.customPng == null || p.customPng.isEmpty())) {
				r.keepOpen();
				PlusDialogs.block("cpmplus.w6particles.err.noImage");
				return;
			}
			run(p, old);
		}, o);
		refresh(init, null);
		PlusTestHooks.track(ParticleBudget.measure()).then(m -> {
			measure[0] = m;
			try {
				refresh(currentParams(init, customPng[0], selUuid), m);
			} catch (Throwable e) {
			}
			return null;
		});
	}

	static Group selectedGroup() {
		try {
			Group g = Group.first_selected;
			return g != null && Js.isTruthy(g) ? g : null;
		} catch (Throwable e) {
			return null;
		}
	}

	private static ParticleParams currentParams(ParticleParams init, String customPng, String selUuid) {
		Dialog d = openDialog();
		if (d == null)return init;
		return fromForm(new FormResult(d.getFormResult()), init, customPng, selUuid);
	}

	private static native Dialog openDialog() /*-{
		var d = $wnd.Dialog && $wnd.Dialog.open;
		return d && d.id === 'cpmplus_w6particles' ? d : null;
	}-*/;

	static ParticleParams fromForm(FormResult r, ParticleParams init, String customPng, String selUuid) {
		ParticleParams p = init.copy();
		if (r.getString("image") != null)p.image = r.getString("image");
		p.customPng = customPng != null ? customPng : "";
		if (r.getString("style") != null)p.style = r.getString("style");
		if (r.getString("where") != null)p.where = r.getString("where");
		if ("selected".equals(p.where))p.parentUuid = selUuid;
		if (r.getString("trigger") != null)p.trigger = r.getString("trigger");
		if (r.getString("withAnim") != null)p.withAnim = r.getString("withAnim");
		if (r.getString("name") != null)p.name = r.getString("name");
		p.count = Math.max(1, r.getInt("count", p.count));
		p.life = r.getDouble("life", p.life);
		p.fps = Math.max(1, r.getInt("fps", p.fps));
		p.distance = r.getDouble("distance", p.distance);
		p.size = r.getDouble("size", p.size);
		if (r.getString("fade") != null)p.fade = r.getString("fade");
		if (r.getString("color") != null)p.color = r.getString("color");
		if (r.getColor("colorA") != null)p.colorA = r.getColor("colorA");
		if (r.getColor("colorB") != null)p.colorB = r.getColor("colorB");
		if (r.raw("glow") != null)p.glow = r.getBool("glow");
		p.spin = r.getDouble("spin", p.spin);
		p.seed = r.getInt("seed", (int) p.seed);
		p.offX = r.getDouble("offX", p.offX);
		p.offY = r.getDouble("offY", p.offY);
		p.offZ = r.getDouble("offZ", p.offZ);
		if (r.raw("hideFirstPerson") != null)p.hideFirstPerson = r.getBool("hideFirstPerson");
		return p;
	}

	/** counter + notes of the open dialog */
	static void refresh(ParticleParams p, ParticleBudget.Measure m) {
		int frames = frames(p);
		Texture skin = RootUtil.findSkinTexture();
		ParticleAtlas.Plan plan = null;
		try {
			if (skin != null)plan = ParticleAtlas.plan(skin, spriteSize(p));
		} catch (Throwable e) {
		}
		ParticleBudget.Estimate est = ParticleBudget.estimate(m, p.count, frames, p.usesColor(), plan != null ? plan.growH : 1);
		List<String> lines = m != null ? ParticleBudget.lines(est, m, p.count) : new ArrayList<>();
		if (m == null)lines.add(0, PlusDialogs.tr("cpmplus.w6particles.budget.measuring"));
		setLines(BUDGET_EL, lines);
		setLines(NOTES_EL, notes(p, plan, frames));
	}

	static int frames(ParticleParams p) {
		if ("with_anim".equals(p.trigger)) {
			Animation a = ParticleBuilder.animByUuid(p.withAnim);
			if (a != null)return Math.max(2, Math.min(255, ParticleFrames.predictFrames(a, a.snapping, a.length).frames));
		}
		return ParticleMath.frames(p.life, p.fps).frames;
	}

	static int spriteSize(ParticleParams p) {
		return 8;
	}

	/** the honest notes that depend on the choices */
	static List<String> notes(ParticleParams p, ParticleAtlas.Plan plan, int frames) {
		List<String> n = new ArrayList<>();
		if ("right_hand".equals(p.where) || "left_hand".equals(p.where))n.add(PlusDialogs.tr("cpmplus.w6particles.note.hand"));
		if ("behind".equals(p.style))n.add(PlusDialogs.tr("cpmplus.w6particles.note.behind"));
		if ("darken".equals(p.fade))n.add(PlusDialogs.tr("cpmplus.w6particles.note.darken"));
		if (!p.glow)n.add(PlusDialogs.tr("cpmplus.w6particles.note.noGlow"));
		if (!p.hideFirstPerson)n.add(PlusDialogs.tr("cpmplus.w6particles.note.fpShown"));
		if ("gesture_loop".equals(p.trigger) || "gesture_once".equals(p.trigger))n.add(PlusDialogs.tr("cpmplus.w6particles.note.gesture"));
		if ("with_anim".equals(p.trigger))n.add(PlusDialogs.tr("cpmplus.w6particles.note.withAnim"));
		ParticleMath.FramePlan fp = ParticleMath.frames(p.life, p.fps);
		if (!"with_anim".equals(p.trigger) && fp.adjusted)n.add(PlusDialogs.trf("cpmplus.w6particles.note.fps", fp.fps));
		if (plan != null && plan.errorKey != null)n.add(PlusDialogs.tr(plan.errorKey));
		else if (plan != null && plan.growH > 1)n.add(PlusDialogs.trf("cpmplus.w6particles.note.grow", plan.oldW, plan.oldH, plan.newW, plan.newH));
		if (wearerSkin())n.add(PlusDialogs.tr("cpmplus.w6particles.note.wearerSkin"));
		return n;
	}

	/** the skin is still the wearer's own (not baked): painting the sprite bakes it into the model */
	static boolean wearerSkin() {
		try {
			String wh = Project.getData().wearerSkinHash;
			Texture t = RootUtil.findSkinTexture();
			return wh != null && t != null && t.source != null && wh.equals(com.tom.cpm.blockbench.format.ProjectData.hash(t.source));
		} catch (Throwable e) {
			return false;
		}
	}

	private static void setLines(String id, List<String> lines) {
		Element el = DomGlobal.document.getElementById(id);
		if (el == null)return;
		StringBuilder sb = new StringBuilder();
		for (String l : lines)sb.append("<div>").append(PlusDialogs.esc(l)).append("</div>");
		el.innerHTML = sb.toString();
	}

	private static void pickImage(String[] holder) {
		Blockbench.ImportProperties ip = new Blockbench.ImportProperties();
		ip.extensions = new String[] {"png"};
		ip.type = "PNG";
		ip.readtype = "image";
		ip.resource_id = "cpmplus_particles";
		boolean ok = PlusDialogs.importFiles(ip, files -> {
			Object f = files != null && files.length > 0 ? files[0] : null;
			Object content = f != null ? Js.asPropertyMap(f).get("content") : null;
			if (content == null || !"string".equals(Js.typeof(content))) {
				PlusDialogs.block("cpmplus.w6particles.err.badImage");
				return;
			}
			ParticleAtlas.decode((String) content).then(s -> {
				Sprite sq = squareFrame(s);
				if (sq == null) {
					PlusDialogs.block("cpmplus.w6particles.err.badImage");
					return null;
				}
				holder[0] = ParticleAtlas.toDataUrl(sq.w, sq.h, sq.rgba);
				PlusDialogs.quick(PlusDialogs.trf("cpmplus.w6particles.msg.picked", s.w, s.h, sq.w), 2500);
				return null;
			}).catch_(e -> {
				PlusDialogs.block("cpmplus.w6particles.err.badImage");
				return null;
			});
		});
		if (!ok)PlusDialogs.block("cpmplus.w6particles.err.badImage");
	}

	/**
	 * The square sprite to use: a strip (several frames side by side or stacked) uses its FIRST frame; larger than
	 * 64 px is scaled down to 32 (nearest); odd sizes are padded. null = unusable.
	 */
	static Sprite squareFrame(Sprite s) {
		if (s == null || s.w <= 0 || s.h <= 0)return null;
		int side = Math.min(s.w, s.h);
		Sprite f = new Sprite(side, side);
		for (int y = 0; y < side; y++)for (int x = 0; x < side; x++) {
			int o = (y * s.w + x) * 4, d = (y * side + x) * 4;
			for (int i = 0; i < 4; i++)f.rgba[d + i] = s.rgba[o + i];
		}
		if (side > 64)f = f.scaled(32);
		if (f.w % 2 == 1)f = f.scaled(f.w + 1);
		return f.empty() ? null : f;
	}

	/** creates / regenerates, then tells the user what happened */
	static void run(ParticleParams p, Group old) {
		PlusTestHooks.track(sprite(p).then(s -> {
			try {
				ParticleBuilder.Result r = ParticleBuilder.build(p, s, old);
				StringBuilder msg = new StringBuilder(PlusDialogs.trf("cpmplus.w6particles.msg.done", r.parts.size(), r.anim != null ? r.anim.name : ""));
				PlusDialogs.done(msg.toString());
				if (!r.notes.isEmpty())PlusDialogs.info("cpmplus_w6particles_notes", "cpmplus.w6particles.title", r.notes);
			} catch (ParticleBuilder.UserError e) {
				PlusDialogs.block(e.getMessage());
			} catch (Throwable e) {
				PopupDialogs.displayError(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6particles.title")), e);
			}
			return null;
		}).catch_(e -> {
			PlusDialogs.block("cpmplus.w6particles.err.badImage");
			return null;
		}));
	}

	/** the sprite of the params (built-in preset, or the saved custom PNG) */
	public static Promise<Sprite> sprite(ParticleParams p) {
		if ("custom".equals(p.image) && p.customPng != null && !p.customPng.isEmpty()) {
			return ParticleAtlas.decode(p.customPng).then(s -> {
				Sprite f = squareFrame(s);
				if (f == null)return Promise.reject("empty image");
				return Promise.resolve(f);
			});
		}
		return Promise.resolve(ParticleSprites.get(p.image));
	}
}
