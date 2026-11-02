package com.tom.cpm.blockbench.tools.particles;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.convert.BlockbenchImport;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;
import com.tom.cpm.blockbench.proxy.Cube.FaceUV;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.tools.particles.ParticleMath.FramePlan;
import com.tom.cpm.blockbench.tools.particles.ParticleMath.Sample;
import com.tom.cpm.blockbench.tools.particles.ParticleMath.Timing;
import com.tom.cpm.blockbench.tools.particles.ParticleSprites.Sprite;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.model.PlayerModelParts;

import jsinterop.base.Js;

/**
 * CPM+ (W6-PARTICLES): creates (or regenerates) one fake-particle system in the open project, as ONE undo entry:
 * <ul>
 * <li>a group 'Partículas – &lt;nome&gt;' under the emitter part (collapsed; it is one more CPM element = +1 part),
 * holding the generator parameters (G16 GroupData extra, for 'Editar partículas');</li>
 * <li>N groups 'particula_NN' with one 2x2x0 plane each (per-face UV on the sprite slot, glow / recolor), hidden at
 * rest when 'esconder em primeira pessoa' is on: CPM's first-person hand view plays no pose / gesture animation, so
 * hidden-at-rest parts never flash in front of the camera and never show as a frozen clump when the trigger is
 * off;</li>
 * <li>the sprite pixels in a free place of the skin (ParticleAtlas);</li>
 * <li>the animation: a new one for the chosen trigger, or the keys merged into an existing animation on ITS frame
 * grid (interpolation family, frame count, duration kept).</li>
 * </ul>
 */
public final class ParticleBuilder {
	public static final String GROUP_PREFIX = "Partículas – ";

	private ParticleBuilder() {
	}

	/** what the generator did (for the toast, the scenario hooks and 'Editar partículas') */
	public static final class Result {
		public Group container;
		public List<Group> parts = new ArrayList<>();
		public List<Cube> cubes = new ArrayList<>();
		public Animation anim;
		public boolean animCreated;
		public int frames, durationMs, fps;
		public boolean fpsAdjusted;
		public String intType;
		public ParticleAtlas.Plan plan;
		public List<String> notes = new ArrayList<>();
		public boolean layerCtrl;
	}

	/** the emitter: parent group + anchor offset (BB units, relative to the parent's origin) */
	public static final class Emitter {
		public Group parent;
		public double ax, ay, az;
		public String errorKey;
	}

	public static Emitter emitter(ParticleParams p) {
		Emitter e = new Emitter();
		Group g = null;
		String where = p.where != null ? p.where : "body";
		switch (where) {
		case "head":
			g = RootUtil.findRootGroup(PlayerModelParts.HEAD.getName());
			break;
		case "right_hand":
			g = RootUtil.findRootGroup(PlayerModelParts.RIGHT_ARM.getName());
			break;
		case "left_hand":
			g = RootUtil.findRootGroup(PlayerModelParts.LEFT_ARM.getName());
			break;
		case "selected":
			g = byUuid(p.parentUuid);
			break;
		case "body":
		case "feet":
		default:
			g = RootUtil.findRootGroup(PlayerModelParts.BODY.getName());
			break;
		}
		if (g == null) {
			e.errorKey = "selected".equals(where) ? "cpmplus.w6particles.err.noSelected" : "cpmplus.w6particles.err.noRoot";
			return e;
		}
		e.parent = g;
		Cube c = firstCube(g);
		double ox = g.origin.x, oy = g.origin.y, oz = g.origin.z;
		if (c != null) {
			double cx = (c.from.x + c.to.x) / 2, cz = (c.from.z + c.to.z) / 2;
			double minY = Math.min(c.from.y, c.to.y), maxY = Math.max(c.from.y, c.to.y);
			switch (where) {
			case "head":
				e.ax = cx - ox; e.ay = maxY + 2 - oy; e.az = cz - oz;
				break;
			case "right_hand":
			case "left_hand":
				e.ax = cx - ox; e.ay = minY - oy; e.az = cz - oz;
				break;
			case "feet":
				e.ax = cx - ox; e.ay = 0.5 - oy; e.az = cz - oz;
				break;
			default:
				e.ax = cx - ox; e.ay = (minY + maxY) / 2 - oy; e.az = cz - oz;
			}
		} else if ("feet".equals(where)) {
			e.ay = 0.5 - oy;
		}
		return e;
	}

	static Cube firstCube(Group g) {
		if (g == null || g.children == null)return null;
		for (int i = 0; i < g.children.length; i++) {
			OutlinerNode n = g.children.getAt(i);
			if (n instanceof Cube)return (Cube) n;
		}
		return null;
	}

	public static Group byUuid(String uuid) {
		if (uuid == null || uuid.isEmpty() || Group.all == null)return null;
		for (Group g : Group.all)if (g != null && uuid.equals(g.uuid))return g;
		return null;
	}

	public static Animation animByUuid(String uuid) {
		if (uuid == null || uuid.isEmpty() || Animation.all == null)return null;
		for (Animation a : Animation.all)if (a != null && uuid.equals(a.uuid))return a;
		return null;
	}

	/** the particle parameters stored on a group made by the generator, or null */
	public static ParticleParams paramsOf(Group g) {
		if (g == null)return null;
		try {
			Object o = g.getData().getExtra(ParticleParams.EXTRA_KEY);
			return o != null ? ParticleParams.fromMap(o) : null;
		} catch (Throwable e) {
			return null;
		}
	}

	/** the particle system group that contains node (itself included), or null */
	public static Group systemOf(Object node) {
		Object n = node;
		for (int i = 0; i < 64 && n instanceof Group; i++) {
			Group g = (Group) n;
			if (paramsOf(g) != null)return g;
			n = g.parent;
		}
		if (node instanceof Cube)return systemOf(((Cube) node).parent);
		return null;
	}

	// ------------------------------------------------------------------ create / regenerate

	/**
	 * Creates the system (old != null: replaces that system, same undo entry). Throws on failure (nothing is left
	 * half applied: PlusUndo cancels the edit).
	 */
	public static Result build(ParticleParams p, Sprite sprite, Group old) {
		Texture skin = RootUtil.findSkinTexture();
		if (skin == null)throw new UserError("cpmplus.w6particles.err.noSkin");
		if (Js.isTruthy(skin.layers_enabled))throw new UserError("cpmplus.w6particles.err.layers");
		ParticleParams oldP = old != null ? paramsOf(old) : null;
		Animation oldAnim = oldP != null ? animByUuid(oldP.animUuid) : null;
		Animation with = "with_anim".equals(p.trigger) ? animByUuid(p.withAnim) : null;
		if ("with_anim".equals(p.trigger) && with == null)throw new UserError("cpmplus.w6particles.err.noAnim");

		Emitter em = emitter(p);
		if (em.errorKey != null)throw new UserError(em.errorKey);
		if (old != null && (em.parent == old || RootUtil.isDescendant(em.parent, old)))throw new UserError("cpmplus.w6particles.err.insideSelf");

		// frame grid (FORMAT limits only)
		Result r = new Result();
		Timing timing = p.isOnce() ? Timing.ONCE : Timing.LOOP;
		String interp = "linear";
		InterpolatorType it = timing == Timing.ONCE ? InterpolatorType.LINEAR_SINGLE : InterpolatorType.LINEAR_LOOP;
		int frames;
		float len;
		if (with != null) {
			len = with.length > 0 ? with.length : 1;
			ParticleFrames.FramePrediction fp = ParticleFrames.predictFrames(with, with.snapping, len);
			frames = Math.max(2, Math.min(ParticleMath.MAX_FRAMES, fp.frames));
			InterpolatorType st = with.getCpmIntType();
			String fam = familyOf(with);
			interp = fam != null ? fam : st != null ? BlockbenchImport.interpolationFor(st) : "linear";
			boolean loopAnim = "loop".equals(with.loop);
			timing = loopAnim ? Timing.LOOP : Timing.ONCE;
			it = st != null ? st : loopAnim ? InterpolatorType.LINEAR_LOOP : InterpolatorType.LINEAR_SINGLE;
			int existing = animatedParts(with, oldP != null && !oldP.animCreated ? old : null);
			if (existing + p.count > ParticleMath.MAX_ANIMATED_PARTS)
				throw new UserError(PlusDialogs.trf("cpmplus.w6particles.err.tooManyParts", existing, p.count, ParticleMath.MAX_ANIMATED_PARTS));
			r.fps = (int) Math.round(frames / Math.max(0.001, len));
		} else {
			FramePlan fp = ParticleMath.frames(p.life, p.fps);
			frames = fp.frames;
			len = fp.durationMs / 1000f;
			r.fps = fp.fps;
			r.fpsAdjusted = fp.adjusted;
			if (p.count > ParticleMath.MAX_ANIMATED_PARTS)
				throw new UserError(PlusDialogs.trf("cpmplus.w6particles.err.tooManyParts", 0, p.count, ParticleMath.MAX_ANIMATED_PARTS));
		}
		r.frames = frames;
		r.durationMs = Math.round(len * 1000);
		r.intType = it.name();
		boolean singleGrid = it == InterpolatorType.LINEAR_SINGLE || it == InterpolatorType.TRIG_SINGLE;
		boolean closeLoop = it == InterpolatorType.POLY_LOOP || it == InterpolatorType.LINEAR_LOOP || it == InterpolatorType.TRIG_LOOP;
		boolean closeHold = it == InterpolatorType.POLY_SINGLE;
		float step = singleGrid ? len / (frames - 1) : len / frames;
		ParticleMath.Settings st = p.settings();
		if ("catmullrom".equals(interp))st.hideTail = 1;// the spline may undershoot the 0.01 end: hide that segment too
		Sample[][] samples = ParticleMath.generate(st, frames, timing);
		boolean color = p.usesColor();

		// layer capacity (gestures / toggles): layer-controlled only when the free skin layers can encode it
		boolean layerCtrl = true;
		if (with == null && ("gesture_once".equals(p.trigger) || "gesture_loop".equals(p.trigger) || "toggle".equals(p.trigger))) {
			LayerCapacity.Result lc = LayerCapacity.checkProject();
			boolean addsGesture = !"toggle".equals(p.trigger);
			String err = LayerCapacity.error(lc.count + (addsGesture ? 1 : 0), lc.freeLayers);
			if (err != null || lc.freeLayers < 2) {
				layerCtrl = false;
				r.notes.add(PlusDialogs.tr("cpmplus.w6particles.note.noLayers"));
			}
		}
		r.layerCtrl = layerCtrl;

		// the texture place is decided before the edit (a full skin aborts without touching anything)
		ParticleAtlas.Plan plan0 = ParticleAtlas.plan(skin, Math.max(sprite.w, sprite.h));
		if (plan0.errorKey != null && old == null)throw new UserError(plan0.errorKey);

		List<Group> beforeGroups = new ArrayList<>();
		beforeGroups.add(em.parent);
		List<OutlinerElement> beforeElements = new ArrayList<>();
		if (old != null) {
			beforeGroups.add(old);
			old.forEachChild(n -> {
				if (n instanceof Group)beforeGroups.add((Group) n);
				else if (n instanceof Cube)beforeElements.add((Cube) n);
			}, null, false);
		}
		List<Animation> beforeAnims = new ArrayList<>();
		if (oldAnim != null)beforeAnims.add(oldAnim);
		if (with != null && !beforeAnims.contains(with))beforeAnims.add(with);

		UndoData before = new UndoData();
		before.outliner = true;
		before.groups = beforeGroups.toArray(new Group[0]);
		before.elements = beforeElements.toArray(new OutlinerElement[0]);
		before.textures = new Texture[] {skin};
		before.bitmap = true;
		before.uv_mode = true;
		before.animations = beforeAnims.toArray(new Animation[0]);

		final float flen = len;
		final int fframes = frames;
		final String finterp = interp;
		final InterpolatorType fit = it;
		final boolean fLayerCtrl = layerCtrl;
		final Animation fWith = with;
		PlusUndo.run(PlusDialogs.tr("cpmplus.w6particles.undo"), before, () -> {
			UndoData a = new UndoData();
			a.outliner = true;
			List<Group> gs = new ArrayList<>();
			gs.add(em.parent);
			if (r.container != null)gs.add(r.container);
			gs.addAll(r.parts);
			a.groups = gs.toArray(new Group[0]);
			a.elements = r.cubes.toArray(new OutlinerElement[0]);
			a.textures = new Texture[] {skin};
			a.bitmap = true;
			a.uv_mode = true;
			List<Animation> as = new ArrayList<>();
			if (r.anim != null)as.add(r.anim);
			if (fWith != null && !as.contains(fWith))as.add(fWith);
			if (oldAnim != null && !as.contains(oldAnim) && Animation.all != null && contains(Animation.all, oldAnim))as.add(oldAnim);
			a.animations = as.toArray(new Animation[0]);
			return a;
		}, () -> {
			// 1. remove the old system (regenerate)
			if (old != null) {
				if (oldAnim != null) {
					old.forEachChild(n -> {
						if (n instanceof Group)removeAnimator(oldAnim, ((Group) n).uuid);
					}, null, true);
				}
				if (oldP != null && oldP.slotW > 0 && oldP.slotX >= 0)ParticleAtlas.clear(skin, (int) oldP.slotX, (int) oldP.slotY, (int) oldP.slotW, (int) oldP.slotH);
				old.remove(false);
			}
			// 2. texture slot (planned again after removing the old system: its slot is free now)
			ParticleAtlas.Plan plan = old != null ? ParticleAtlas.plan(skin, Math.max(sprite.w, sprite.h)) : plan0;
			if (plan.errorKey != null)throw new UserError(plan.errorKey);
			ParticleAtlas.apply(skin, plan, sprite);
			r.plan = plan;

			// 3. groups and planes
			double cx = em.parent.origin.x + em.ax + p.offX, cy = em.parent.origin.y + em.ay + p.offY, cz = em.parent.origin.z + em.az + p.offZ;
			GroupProperties gp = new GroupProperties();
			gp.name = uniqueName(GROUP_PREFIX + (p.name == null || p.name.trim().isEmpty() ? PlusDialogs.tr("cpmplus.w6particles.defName") : p.name.trim()));
			gp.origin = JsVec3.make((float) cx, (float) cy, (float) cz);
			gp.rotation = JsVec3.make(0, 0, 0);
			Group cont = new Group(gp).init();
			cont.addTo(em.parent);
			cont.isOpen = false;
			r.container = cont;
			int n = samples.length;
			for (int k = 0; k < n; k++) {
				String nm = "particula_" + (k + 1 < 10 ? "0" : "") + (k + 1);
				GroupProperties pp = new GroupProperties();
				pp.name = nm;
				pp.origin = JsVec3.make((float) cx, (float) cy, (float) cz);
				pp.rotation = JsVec3.make(0, 0, 0);
				Group pg = new Group(pp).init();
				pg.addTo(cont);
				pg.isOpen = false;
				pg.hidden = p.hideFirstPerson;
				r.parts.add(pg);
				r.cubes.add(plane(pg, nm, cx, cy, cz, plan, skin, p.glow, color));
			}

			// 4. animation
			Animation an;
			if (fWith != null) {
				an = fWith;
				r.animCreated = false;
			} else if (oldAnim != null && oldP != null && oldP.animCreated) {
				an = oldAnim;
				r.animCreated = true;
			} else {
				an = new Animation().add(false);
				r.animCreated = true;
			}
			if (r.animCreated)setupAnim(an, p, flen, fframes, fit, step, fLayerCtrl);
			r.anim = an;
			double[] times = new double[fframes];
			for (int f = 0; f < fframes; f++)times[f] = f * step;
			boolean close = closeLoop || closeHold;
			for (int k = 0; k < n; k++)writeKeys(an, r.parts.get(k), samples[k], times, close ? flen : -1, closeHold, finterp, color);
			// an old generated animation that no longer has particles (the trigger became 'junto com a animação')
			if (oldAnim != null && oldP != null && oldP.animCreated && an != oldAnim)oldAnim.remove(false, false);

			// 5. parameters (Editar partículas) + hash of the generated keys (manual-edit detection)
			p.animUuid = an.uuid;
			p.animCreated = r.animCreated;
			p.slotX = plan.x;
			p.slotY = plan.y;
			p.slotW = plan.sizePx;
			p.slotH = plan.sizePx;
			p.parentUuid = em.parent.uuid;
			p.hash = keyHash(an, r.parts);
			GroupData gd = cont.getData();
			gd.putExtra(ParticleParams.EXTRA_KEY, p.toMap());
			gd.flush();
		});
		try {
			ProjectGenerator.updateAll();
		} catch (Throwable e) {
		}
		if (r.fpsAdjusted)r.notes.add(PlusDialogs.trf("cpmplus.w6particles.note.fps", r.fps));
		if (r.plan != null && r.plan.growH > 1)r.notes.add(PlusDialogs.trf("cpmplus.w6particles.note.grown", r.plan.oldW, r.plan.oldH, r.plan.newW, r.plan.newH));
		return r;
	}

	private static boolean contains(Animation[] all, Animation a) {
		for (Animation x : all)if (x == a)return true;
		return false;
	}

	private static void setupAnim(Animation an, ParticleParams p, float len, int frames, InterpolatorType it, float step, boolean layerCtrl) {
		String base = GROUP_PREFIX + (p.name == null || p.name.trim().isEmpty() ? PlusDialogs.tr("cpmplus.w6particles.defName") : p.name.trim());
		an.name = uniqueAnimName(base, an);
		switch (p.trigger) {
		case "idle":
			an.type = Animation.makeType(AnimationType.POSE, VanillaPose.STANDING);
			an.loop = "loop";
			break;
		case "walk":
			an.type = Animation.makeType(AnimationType.POSE, VanillaPose.WALKING);
			an.loop = "loop";
			break;
		case "gesture_once":
			an.type = Animation.makeType(AnimationType.GESTURE, null);
			an.loop = "once";
			break;
		case "gesture_loop":
			an.type = Animation.makeType(AnimationType.GESTURE, null);
			an.loop = "loop";
			break;
		case "toggle":
			an.type = Animation.makeType(AnimationType.LAYER, null);
			an.loop = "loop";
			break;
		case "always":
		default:
			an.type = Animation.makeType(AnimationType.POSE, VanillaPose.GLOBAL);
			an.loop = "loop";
			break;
		}
		an.additive = false;
		an.layerCtrl = layerCtrl;
		an.commandCtrl = false;
		an.length = len;
		an.snapping = BlockbenchImport.pickSnapping(step);
		an.setCpmIntType(it);
		an.setCpmFrames(frames);
		an.setCpmDuration(Math.round(len * 1000));
		an.setMustFinish(false);
		an.setAnimHidden(false);
		// existing animators of the reused animation are replaced
	}

	private static String uniqueName(String base) {
		String n = base;
		int i = 2;
		while (nameUsed(n))n = base + " " + (i++);
		return n;
	}

	private static boolean nameUsed(String n) {
		if (Group.all == null)return false;
		for (Group g : Group.all)if (g != null && n.equals(g.name))return true;
		return false;
	}

	private static String uniqueAnimName(String base, Animation self) {
		String n = base;
		int i = 2;
		while (animNameUsed(n, self))n = base + " " + (i++);
		return n;
	}

	private static boolean animNameUsed(String n, Animation self) {
		if (Animation.all == null)return false;
		for (Animation a : Animation.all)if (a != null && a != self && n.equals(a.name))return true;
		return false;
	}

	/** one 2x2x0 plane, centred on (cx, cy, cz), facing north / south, sampling the sprite slot */
	private static Cube plane(Group g, String name, double cx, double cy, double cz, ParticleAtlas.Plan plan, Texture skin, boolean glow, boolean color) {
		Cube.CubeProperties c = new Cube.CubeProperties();
		c.name = name;
		c.origin = JsVec3.make((float) cx, (float) cy, (float) cz);
		c.from = JsVec3.make((float) cx - 1, (float) cy - 1, (float) cz);
		c.rotation = JsVec3.make(0, 0, 0);
		Cube cube = new Cube(c);
		c = new Cube.CubeProperties();
		c.to = JsVec3.make((float) cx + 1, (float) cy + 1, (float) cz);
		cube.extend(c);
		cube.box_uv = false;
		cube.autouv = 0;
		cube.glow = glow;
		if (color)cube.setRecolor(0xFFFFFF);
		float u0 = (float) plan.u, v0 = (float) plan.v, u1 = (float) (plan.u + plan.sizeUv), v1 = (float) (plan.v + plan.sizeUv);
		CubeFace[] all = {cube.faces.up, cube.faces.down, cube.faces.east, cube.faces.west};
		for (CubeFace f : all)f.uv = FaceUV.make(0, 0, 0, 0);
		cube.faces.north.uv = FaceUV.make(u0, v0, u1, v1);
		cube.faces.south.uv = FaceUV.make(u1, v0, u0, v1);
		cube.addTo(g).init();
		cube.applyTexture(skin, true);
		cube.faces.forEach(f -> {
			if (f.uv == null || f.uv.isEmpty())f.disable();
		});
		return cube;
	}

	private static void writeKeys(Animation an, Group g, Sample[] s, double[] times, double closeAt, boolean hold, String interp, boolean color) {
		removeAnimator(an, g.uuid);
		BoneAnimator be = new BoneAnimator(g.uuid, an);
		an.animators.set(g.uuid, be);
		int n = s.length;
		int keys = n + (closeAt > 0 ? 1 : 0);
		for (int k = 0; k < keys; k++) {
			boolean closing = k >= n;
			Sample v = closing ? (hold ? s[n - 1] : s[0]) : s[k];
			float t = (float) (closing ? closeAt : times[k]);
			Keyframe kp = be.addKeyframeFast(t, "position", interp);
			if (kp != null)kp.data_points.getAt(0).set(new Vec3f(-v.px, -v.py, v.pz));
			Keyframe kr = be.addKeyframeFast(t, "rotation", interp);
			if (kr != null)kr.data_points.getAt(0).set(new Vec3f(-v.rx, -v.ry, v.rz));
			Keyframe ks = be.addKeyframeFast(t, "scale", interp);
			if (ks != null)ks.data_points.getAt(0).set(new Vec3f(v.scale, v.scale, v.scale));
			if (color) {
				Keyframe kc = be.addKeyframeFast(t, "cpm_color", interp);
				if (kc != null) {
					kc.data_points.getAt(0).x = v.r;
					kc.data_points.getAt(0).y = v.g;
					kc.data_points.getAt(0).z = v.b;
				}
			}
		}
		Boolean prev = null;
		for (int k = 0; k < n; k++) {
			if (prev == null || prev.booleanValue() != s[k].show) {
				Keyframe kv = be.addKeyframeFast((float) times[k], "cpm_visibility", "step");
				if (kv != null)kv.data_points.getAt(0).setVisible(s[k].show);
			}
			prev = s[k].show;
		}
		if (closeAt > 0) {
			// closing visibility = frame 0 (loop) / last frame (hold): keeps the export's seam check exact
			boolean v = hold ? s[n - 1].show : s[0].show;
			Keyframe kv = be.addKeyframeFast((float) closeAt, "cpm_visibility", "step");
			if (kv != null)kv.data_points.getAt(0).setVisible(v);
		}
	}

	static void removeAnimator(Animation an, String uuid) {
		if (an == null || an.animators == null || uuid == null)return;
		if (an.animators.has(uuid))an.animators.delete(uuid);
	}

	/** animated parts of an animation (animators with keys), without those under skip */
	static int animatedParts(Animation an, Group skip) {
		int[] c = new int[1];
		an.animators.forEach(k -> {
			Object o = an.animators.get(k);
			if (o instanceof BoneAnimator && ((BoneAnimator) o).hasTransform()) {
				Group g = byUuid(k);
				if (skip != null && g != null && (g == skip || RootUtil.isDescendant(g, skip)))return;
				c[0]++;
			}
		});
		return c[0];
	}

	/** interpolation family of the existing keys of an animation (linear / catmullrom / step), null when none */
	static String familyOf(Animation an) {
		String[] fam = new String[1];
		an.animators.forEach(k -> {
			Object o = an.animators.get(k);
			if (fam[0] != null || !(o instanceof BoneAnimator))return;
			BoneAnimator ba = (BoneAnimator) o;
			for (String ch : new String[] {"position", "rotation", "scale", "cpm_color"}) {
				com.tom.ugwt.client.JsArrayE<Keyframe> arr = ba.channel(ch);
				if (arr == null || arr.length < 2)continue;
				String i = arr.getAt(0).interpolation;
				if (i == null || i.isEmpty() || "linear".equals(i))fam[0] = "linear";
				else if ("catmullrom".equals(i) || "step".equals(i))fam[0] = i;
				else fam[0] = "linear";
				return;
			}
		});
		return fam[0];
	}

	/** hash of every generated key value (manual edits change it) */
	public static String keyHash(Animation an, List<Group> parts) {
		List<Double> v = new ArrayList<>();
		if (an != null) {
			for (Group g : parts) {
				Object o = an.animators.get(g.uuid);
				if (!(o instanceof BoneAnimator)) {
					v.add(-1.0);
					continue;
				}
				BoneAnimator ba = (BoneAnimator) o;
				for (String ch : new String[] {"position", "rotation", "scale", "cpm_color", "cpm_visibility"}) {
					com.tom.ugwt.client.JsArrayE<Keyframe> arr = ba.channel(ch);
					v.add(arr == null ? 0.0 : (double) arr.length);
					if (arr == null)continue;
					for (int i = 0; i < arr.length; i++) {
						Keyframe kf = arr.getAt(i);
						v.add((double) kf.time);
						if ("cpm_visibility".equals(ch))v.add(kf.data_points.getAt(0).getVisible() ? 1.0 : 0.0);
						else {
							v.add(num(kf.data_points.getAt(0).getRaw("x")));
							v.add(num(kf.data_points.getAt(0).getRaw("y")));
							v.add(num(kf.data_points.getAt(0).getRaw("z")));
						}
					}
				}
			}
		}
		double[] d = new double[v.size()];
		for (int i = 0; i < d.length; i++)d[i] = v.get(i);
		return ParticleMath.hash(d);
	}

	private static double num(Object o) {
		if (o == null)return 0;
		if ("number".equals(Js.typeof(o)))return Js.asDouble(o);
		try {
			return Double.parseDouble(String.valueOf(o));
		} catch (Exception e) {
			return Double.NaN;
		}
	}

	/** parts of a system (its particle groups, in order) */
	public static List<Group> partsOf(Group system) {
		List<Group> out = new ArrayList<>();
		if (system == null || system.children == null)return out;
		for (int i = 0; i < system.children.length; i++) {
			OutlinerNode n = system.children.getAt(i);
			if (n instanceof Group)out.add((Group) n);
		}
		return out;
	}

	/** true when the keys of a system were edited by hand since the generator wrote them */
	public static boolean editedByHand(Group system) {
		ParticleParams p = paramsOf(system);
		if (p == null || p.hash == null || p.hash.isEmpty())return false;
		Animation an = animByUuid(p.animUuid);
		return !p.hash.equals(keyHash(an, partsOf(system)));
	}

	/** an error to show the user as is (pt-BR text or lang key) */
	public static final class UserError extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public UserError(String keyOrText) {
			super(PlusDialogs.tr(keyOrText));
		}
	}
}
