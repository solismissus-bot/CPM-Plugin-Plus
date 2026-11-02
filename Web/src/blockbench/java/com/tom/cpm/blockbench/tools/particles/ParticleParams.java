package com.tom.cpm.blockbench.tools.particles;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPM+ (W6-PARTICLES): every choice of the 'Partículas com sua imagem' dialog. Saved on the particle group (G16
 * GroupData extra {@link #EXTRA_KEY}) so 'Editar partículas' can regenerate with other values. JSON shapes only
 * (String / Double / Boolean), never null.
 */
public final class ParticleParams {
	public static final String EXTRA_KEY = "cpmplus_particles";
	public static final int VERSION = 1;

	public String name = "Brilho";
	/** sparkle, heart, smoke, spark, petal, portal, custom */
	public String image = "sparkle";
	/** custom image: PNG data URL (small sprite) */
	public String customPng = "";
	/** ParticleMath.Pattern name (burst, orbit, rain, sparkle, smoke, behind) */
	public String style = "burst";
	/** body, head, feet, right_hand, left_hand, selected */
	public String where = "body";
	/** group uuid when where == selected (or the group the system was created under) */
	public String parentUuid = "";
	public double offX, offY, offZ;
	public int count = 12;
	public double life = 1.2;
	public int fps = 20;
	public double distance = 1;
	public double size = 1;
	/** shrink, blink, darken (experimental) */
	public String fade = "shrink";
	/** keep, gradient */
	public String color = "keep";
	public String colorA = "#ffffff";
	public String colorB = "#9966ff";
	public boolean glow = true;
	public double spin = 1;
	public long seed = 1337;
	/** always, idle, walk, gesture_once, gesture_loop, toggle, with_anim */
	public String trigger = "always";
	/** with_anim: uuid of the animation the particles were merged into */
	public String withAnim = "";
	public boolean hideFirstPerson = true;

	// written by the generator
	public String animUuid = "";
	public boolean animCreated;
	public String hash = "";
	public double slotX = -1, slotY = -1, slotW, slotH;

	public ParticleParams copy() {
		return fromMap(toMap());
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("v", (double) VERSION);
		m.put("name", name);
		m.put("image", image);
		m.put("customPng", customPng != null ? customPng : "");
		m.put("style", style);
		m.put("where", where);
		m.put("parentUuid", parentUuid != null ? parentUuid : "");
		m.put("offX", offX);
		m.put("offY", offY);
		m.put("offZ", offZ);
		m.put("count", (double) count);
		m.put("life", life);
		m.put("fps", (double) fps);
		m.put("distance", distance);
		m.put("size", size);
		m.put("fade", fade);
		m.put("color", color);
		m.put("colorA", colorA);
		m.put("colorB", colorB);
		m.put("glow", glow);
		m.put("spin", spin);
		m.put("seed", (double) seed);
		m.put("trigger", trigger);
		m.put("withAnim", withAnim != null ? withAnim : "");
		m.put("hideFirstPerson", hideFirstPerson);
		m.put("animUuid", animUuid != null ? animUuid : "");
		m.put("animCreated", animCreated);
		m.put("hash", hash != null ? hash : "");
		m.put("slotX", slotX);
		m.put("slotY", slotY);
		m.put("slotW", slotW);
		m.put("slotH", slotH);
		return m;
	}

	public static ParticleParams fromMap(Object o) {
		ParticleParams p = new ParticleParams();
		if (!(o instanceof Map))return p;
		Map<?, ?> m = (Map<?, ?>) o;
		p.name = str(m, "name", p.name);
		p.image = str(m, "image", p.image);
		p.customPng = str(m, "customPng", "");
		p.style = str(m, "style", p.style);
		p.where = str(m, "where", p.where);
		p.parentUuid = str(m, "parentUuid", "");
		p.offX = num(m, "offX", 0);
		p.offY = num(m, "offY", 0);
		p.offZ = num(m, "offZ", 0);
		p.count = (int) Math.round(num(m, "count", p.count));
		p.life = num(m, "life", p.life);
		p.fps = (int) Math.round(num(m, "fps", p.fps));
		p.distance = num(m, "distance", p.distance);
		p.size = num(m, "size", p.size);
		p.fade = str(m, "fade", p.fade);
		p.color = str(m, "color", p.color);
		p.colorA = str(m, "colorA", p.colorA);
		p.colorB = str(m, "colorB", p.colorB);
		p.glow = bool(m, "glow", p.glow);
		p.spin = num(m, "spin", p.spin);
		p.seed = Math.round(num(m, "seed", p.seed));
		p.trigger = str(m, "trigger", p.trigger);
		p.withAnim = str(m, "withAnim", "");
		p.hideFirstPerson = bool(m, "hideFirstPerson", true);
		p.animUuid = str(m, "animUuid", "");
		p.animCreated = bool(m, "animCreated", false);
		p.hash = str(m, "hash", "");
		p.slotX = num(m, "slotX", -1);
		p.slotY = num(m, "slotY", -1);
		p.slotW = num(m, "slotW", 0);
		p.slotH = num(m, "slotH", 0);
		return p;
	}

	private static String str(Map<?, ?> m, String k, String def) {
		Object v = m.get(k);
		return v instanceof String ? (String) v : def;
	}

	private static double num(Map<?, ?> m, String k, double def) {
		Object v = m.get(k);
		if (v instanceof Number) {
			double d = ((Number) v).doubleValue();
			return Double.isNaN(d) ? def : d;
		}
		return def;
	}

	private static boolean bool(Map<?, ?> m, String k, boolean def) {
		Object v = m.get(k);
		return v instanceof Boolean ? (Boolean) v : def;
	}

	/** ParticleMath settings for these params */
	public ParticleMath.Settings settings() {
		ParticleMath.Settings s = new ParticleMath.Settings();
		s.pattern = ParticleMath.Pattern.of(style);
		s.fade = ParticleMath.Fade.of(fade);
		s.count = Math.max(1, count);
		s.distance = (float) Math.max(0.05, Math.min(4, distance));
		s.size = (float) Math.max(0.1, Math.min(8, size));
		s.spin = (float) Math.max(0, Math.min(1, spin));
		s.seed = seed;
		if (usesColor()) {
			s.colorA = "gradient".equals(color) ? rgb(colorA) : new int[] {255, 255, 255};
			s.colorB = "gradient".equals(color) ? rgb(colorB) : new int[] {255, 255, 255};
		}
		return s;
	}

	/** true when the planes need the recolor channel (colour gradient or the 'darken' fade) */
	public boolean usesColor() {
		return "gradient".equals(color) || "darken".equalsIgnoreCase(fade);
	}

	/** '#rrggbb' -> {r, g, b} (white when invalid) */
	public static int[] rgb(String hex) {
		try {
			String h = hex.trim();
			if (h.startsWith("#"))h = h.substring(1);
			if (h.length() == 3)h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
			int v = Integer.parseInt(h.substring(0, 6), 16);
			return new int[] {(v >> 16) & 255, (v >> 8) & 255, v & 255};
		} catch (Exception e) {
			return new int[] {255, 255, 255};
		}
	}

	public boolean isOnce() {
		return "gesture_once".equals(trigger);
	}
}
