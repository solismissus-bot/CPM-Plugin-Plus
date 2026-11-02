package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.tools.particles.ParticleAtlas;
import com.tom.cpm.blockbench.tools.particles.ParticleBudget;
import com.tom.cpm.blockbench.tools.particles.ParticleBuilder;
import com.tom.cpm.blockbench.tools.particles.ParticleDialog;
import com.tom.cpm.blockbench.tools.particles.ParticleMath;
import com.tom.cpm.blockbench.tools.particles.ParticleParams;
import com.tom.cpm.blockbench.tools.particles.ParticleTexPacker;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.RootUtil;

import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W6-PARTICLES (CPM+ v0.8): 'Partículas com sua imagem…' (CPM+ &gt; Animação and the
 * group context menu 'Soltar partículas daqui'), 'Editar partículas…' (CPM+ &gt; Animação and the context menu of
 * a particle group) and the test hooks 'w6particles.*'.
 */
public final class W6ParticlesInstall {
	private W6ParticlesInstall() {
	}

	public static void install() {
		PlusMenus.addAction(PlusMenus.SUB_ANIM, "cpmplus_w6particles_open", "cpmplus.w6particles.menu.open", "auto_awesome", Cond.CPM_ONLY, () -> ParticleDialog.openNew(null));
		Action edit = PlusMenus.action("cpmplus_w6particles_edit", "cpmplus.w6particles.menu.edit", "auto_fix_high", Cond.CPM_ONLY,
				() -> ParticleBuilder.systemOf(ParticleDialog_selected()) != null, () -> ParticleDialog.openEdit(ParticleBuilder.systemOf(ParticleDialog_selected())));
		PlusMenus.add(PlusMenus.SUB_ANIM, edit);
		Action from = PlusMenus.action("cpmplus_w6particles_from", "cpmplus.w6particles.menu.from", "auto_awesome", Cond.CPM_ONLY,
				() -> ParticleDialog_selected() != null, () -> ParticleDialog.openNew(ParticleDialog_selected()));
		Menu gm = groupMenu();
		if (gm != null) {
			PlusMenus.addToContext(gm, from, "");
			PlusMenus.addToContext(gm, edit, "");
		}
		registerHooks();
	}

	public static void uninstall() {
	}

	static Group ParticleDialog_selected() {
		try {
			Group g = Group.first_selected;
			return g != null && Js.isTruthy(g) ? g : null;
		} catch (Throwable e) {
			return null;
		}
	}

	private static native Menu groupMenu() /*-{
		return ($wnd.Group && $wnd.Group.prototype && $wnd.Group.prototype.menu) || null;
	}-*/;

	// ------------------------------------------------------------------ test hooks (test mode only)

	private static ParticleParams params(JsPropertyMap<Object> o) {
		Object j = JsonUtil.fromJs(o.get("params"));
		ParticleParams p = ParticleParams.fromMap(j);
		return p;
	}

	@SuppressWarnings("unchecked")
	private static void registerHooks() {
		if (!PlusTestHooks.active())return;
		PlusTestHooks.register("w6particles.create", o -> {
			ParticleParams p = params(o);
			Group old = ParticleBuilder.byUuid(str(o, "replace"));
			return ParticleDialog.sprite(p).then(s -> {
				Map<String, Object> r = new LinkedHashMap<>();
				try {
					ParticleBuilder.Result res = ParticleBuilder.build(p, s, old);
					r.put("ok", true);
					r.put("container", res.container.uuid);
					r.put("containerName", res.container.name);
					List<Object> parts = new ArrayList<>();
					for (Group g : res.parts)parts.add(g.uuid);
					r.put("parts", parts);
					r.put("cubes", (double) res.cubes.size());
					r.put("anim", res.anim != null ? res.anim.uuid : "");
					r.put("animName", res.anim != null ? res.anim.name : "");
					r.put("animType", res.anim != null ? res.anim.type : "");
					r.put("animLoop", res.anim != null ? res.anim.loop : "");
					r.put("animCreated", res.animCreated);
					r.put("layerCtrl", res.layerCtrl);
					r.put("frames", (double) res.frames);
					r.put("durationMs", (double) res.durationMs);
					r.put("intType", res.intType);
					r.put("fps", (double) res.fps);
					r.put("slot", slot(res.plan));
					List<Object> notes = new ArrayList<>(res.notes);
					r.put("notes", notes);
				} catch (ParticleBuilder.UserError e) {
					r.put("ok", false);
					r.put("userError", e.getMessage());
				}
				return Promise.resolve(JsonUtil.toJs(r));
			});
		});
		PlusTestHooks.register("w6particles.estimate", o -> {
			ParticleParams p = params(o);
			return ParticleBudget.measure().then(m -> {
				int frames = ParticleMath.frames(p.life, p.fps).frames;
				ParticleBudget.Estimate e = ParticleBudget.estimate(m, p.count, frames, p.usesColor(), 1);
				Map<String, Object> r = new LinkedHashMap<>();
				r.put("ok", true);
				r.put("frames", (double) frames);
				r.put("cubesBefore", (double) e.cubesBefore);
				r.put("cubesAfter", (double) e.cubesAfter);
				r.put("rawBefore", (double) e.rawBefore);
				r.put("rawAfter", (double) e.rawAfter);
				r.put("addedBytes", (double) e.addedBytes);
				r.put("linkAfter", e.linkAfter);
				r.put("downloadKBAfter", (double) ParticleMath.downloadKB(e.rawAfter));
				r.put("mediumAfter", e.mediumAfter);
				r.put("error", m.error != null ? m.error : "");
				r.put("lines", new ArrayList<Object>(ParticleBudget.lines(e, m, p.count)));
				return Promise.resolve(JsonUtil.toJs(r));
			});
		});
		PlusTestHooks.register("w6particles.measure", o -> ParticleBudget.measure().then(m -> {
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("cubes", (double) m.cubes);
			r.put("rawBytes", (double) m.rawBytes);
			r.put("error", m.error != null ? m.error : "");
			return Promise.resolve(JsonUtil.toJs(r));
		}));
		PlusTestHooks.register("w6particles.params", o -> {
			Group g = ParticleBuilder.byUuid(str(o, "uuid"));
			Map<String, Object> r = new LinkedHashMap<>();
			ParticleParams p = ParticleBuilder.paramsOf(g);
			r.put("ok", p != null);
			if (p != null) {
				r.put("params", p.toMap());
				r.put("editedByHand", ParticleBuilder.editedByHand(g));
			}
			return JsonUtil.toJs(r);
		});
		PlusTestHooks.register("w6particles.math", o -> {
			ParticleParams p = params(o);
			int frames = (int) Math.round(num(o, "frames", 24));
			ParticleMath.Sample[][] s = ParticleMath.generate(p.settings(), frames, p.isOnce() ? ParticleMath.Timing.ONCE : ParticleMath.Timing.LOOP);
			List<Object> parts = new ArrayList<>();
			for (ParticleMath.Sample[] ps : s) {
				List<Object> show = new ArrayList<>(), scale = new ArrayList<>(), pos = new ArrayList<>();
				for (ParticleMath.Sample x : ps) {
					show.add(x.show);
					scale.add((double) x.scale);
					List<Object> v = new ArrayList<>();
					v.add((double) x.px);
					v.add((double) x.py);
					v.add((double) x.pz);
					pos.add(v);
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("show", show);
				m.put("scale", scale);
				m.put("pos", pos);
				parts.add(m);
			}
			Map<String, Object> r = new LinkedHashMap<>();
			r.put("parts", parts);
			return JsonUtil.toJs(r);
		});
		PlusTestHooks.register("w6particles.plan", o -> {
			Texture skin = RootUtil.findSkinTexture();
			ParticleAtlas.Plan pl = ParticleAtlas.plan(skin, (int) Math.round(num(o, "size", 8)));
			Map<String, Object> r = slot(pl);
			ParticleTexPacker.Occupancy occ = ParticleTexPacker.occupancy(skin);
			if (o.has("rect")) {
				Object[] rc = Js.uncheckedCast(o.get("rect"));
				r.put("rectFree", occ.isFree(Js.asInt(rc[0]), Js.asInt(rc[1]), Js.asInt(rc[2]), Js.asInt(rc[3])));
			}
			return JsonUtil.toJs(r);
		});
		PlusTestHooks.register("w6particles.dialog", o -> {
			Group g = ParticleBuilder.byUuid(str(o, "edit"));
			if (g != null)ParticleDialog.openEdit(g);
			else ParticleDialog.openNew(null);
			return JsonUtil.toJs(new LinkedHashMap<String, Object>());
		});
	}

	private static Map<String, Object> slot(ParticleAtlas.Plan pl) {
		Map<String, Object> s = new LinkedHashMap<>();
		if (pl == null)return s;
		s.put("x", (double) pl.x);
		s.put("y", (double) pl.y);
		s.put("size", (double) pl.sizePx);
		s.put("u", pl.u);
		s.put("v", pl.v);
		s.put("growH", (double) pl.growH);
		s.put("error", pl.errorKey != null ? pl.errorKey : "");
		return s;
	}

	private static String str(JsPropertyMap<Object> o, String k) {
		Object v = o.get(k);
		return v != null && "string".equals(Js.typeof(v)) ? (String) v : null;
	}

	private static double num(JsPropertyMap<Object> o, String k, double def) {
		Object v = o.get(k);
		return v != null && "number".equals(Js.typeof(v)) ? Js.asDouble(v) : def;
	}
}
