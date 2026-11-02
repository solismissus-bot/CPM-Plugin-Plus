package com.tom.cpm.blockbench.tools.particles;

/**
 * CPM+ (W6-PARTICLES): the built-in 8x8 sprites (drawn by us in the style of the vanilla particles) as RGBA bytes.
 * Pure Java.
 */
public final class ParticleSprites {
	public static final String[] IDS = {"sparkle", "heart", "smoke", "spark", "petal", "portal"};

	/** an RGBA8 image (row major) */
	public static final class Sprite {
		public final int w, h;
		public final int[] rgba;

		public Sprite(int w, int h) {
			this.w = w;
			this.h = h;
			this.rgba = new int[w * h * 4];
		}

		void put(int x, int y, int r, int g, int b, int a) {
			if (x < 0 || y < 0 || x >= w || y >= h)return;
			int o = (y * w + x) * 4;
			rgba[o] = r;
			rgba[o + 1] = g;
			rgba[o + 2] = b;
			rgba[o + 3] = a;
		}

		public int alpha(int x, int y) {
			return rgba[(y * w + x) * 4 + 3];
		}

		/** nearest-neighbour copy scaled to size x size */
		public Sprite scaled(int size) {
			if (size == w && size == h)return this;
			Sprite s = new Sprite(size, size);
			for (int y = 0; y < size; y++) {
				for (int x = 0; x < size; x++) {
					int sx = Math.min(w - 1, x * w / size), sy = Math.min(h - 1, y * h / size);
					int o = (sy * w + sx) * 4, d = (y * size + x) * 4;
					for (int i = 0; i < 4; i++)s.rgba[d + i] = rgba[o + i];
				}
			}
			return s;
		}

		/** true when no pixel is visible */
		public boolean empty() {
			for (int i = 3; i < rgba.length; i += 4)if (rgba[i] != 0)return false;
			return true;
		}
	}

	private ParticleSprites() {
	}

	public static Sprite get(String id) {
		if (id == null)id = "sparkle";
		switch (id) {
		case "heart": return heart();
		case "smoke": return smoke();
		case "spark": return spark();
		case "petal": return petal();
		case "portal": return portal();
		default: return sparkle();
		}
	}

	/** the prototype's 4-point sparkle (white centre, violet arms) */
	static Sprite sparkle() {
		Sprite s = new Sprite(8, 8);
		int[][] c = {{3, 3}, {4, 3}, {3, 4}, {4, 4}};
		for (int[] p : c)s.put(p[0], p[1], 255, 255, 255, 255);
		int[][] arms = {{3, 2}, {4, 2}, {3, 5}, {4, 5}, {2, 3}, {2, 4}, {5, 3}, {5, 4}};
		for (int[] p : arms)s.put(p[0], p[1], 230, 200, 255, 255);
		int[][] tips = {{3, 1}, {4, 1}, {3, 6}, {4, 6}, {1, 3}, {1, 4}, {6, 3}, {6, 4}};
		for (int[] p : tips)s.put(p[0], p[1], 170, 120, 255, 200);
		int[][] far = {{3, 0}, {4, 7}, {0, 4}, {7, 3}};
		for (int[] p : far)s.put(p[0], p[1], 140, 90, 255, 110);
		int[][] diag = {{2, 2}, {5, 2}, {2, 5}, {5, 5}};
		for (int[] p : diag)s.put(p[0], p[1], 190, 150, 255, 140);
		return s;
	}

	static Sprite heart() {
		String[] rows = {
				"........",
				".XX..XX.",
				"XWXXXXXX",
				"XXXXXXXX",
				"XXXXXXXX",
				".XXXXXX.",
				"..XXXX..",
				"...XX...",
		};
		Sprite s = new Sprite(8, 8);
		for (int y = 0; y < 8; y++)for (int x = 0; x < 8; x++) {
			char ch = rows[y].charAt(x);
			if (ch == 'X')s.put(x, y, 230, 40, 60, 255);
			else if (ch == 'W')s.put(x, y, 255, 200, 210, 255);
		}
		return s;
	}

	static Sprite smoke() {
		Sprite s = new Sprite(8, 8);
		for (int y = 0; y < 8; y++)for (int x = 0; x < 8; x++) {
			double dx = x - 3.5, dy = y - 3.5, d = Math.sqrt(dx * dx + dy * dy);
			if (d > 3.8)continue;
			int a = (int) Math.round(Math.max(60, 230 - d * 45));
			int g = (int) Math.round(200 - d * 18);
			s.put(x, y, g, g, g, a);
		}
		return s;
	}

	static Sprite spark() {
		Sprite s = new Sprite(8, 8);
		for (int y = 0; y < 8; y++)for (int x = 0; x < 8; x++) {
			int d = Math.abs(x * 2 - 7) + Math.abs(y * 2 - 7);
			if (d <= 2)s.put(x, y, 255, 255, 220, 255);
			else if (d <= 5)s.put(x, y, 255, 200, 60, 255);
			else if (d <= 7)s.put(x, y, 255, 120, 20, 200);
		}
		return s;
	}

	static Sprite petal() {
		Sprite s = new Sprite(8, 8);
		for (int y = 0; y < 8; y++)for (int x = 0; x < 8; x++) {
			double u = (x - 3.5) / 3.6, v = (y - 3.5) / 2.2;
			// tilted ellipse
			double ru = u * 0.8 + v * 0.6, rv = -u * 0.6 + v * 0.8;
			double d = ru * ru + rv * rv * 2.2;
			if (d > 1)continue;
			s.put(x, y, 255, d < 0.3 ? 220 : 160, d < 0.3 ? 235 : 200, 255);
		}
		return s;
	}

	/** portal / endermite style: purple specks */
	static Sprite portal() {
		Sprite s = new Sprite(8, 8);
		int[][] px = {{3, 3, 200}, {4, 3, 160}, {3, 4, 170}, {4, 4, 230}, {2, 3, 120}, {5, 4, 120}, {3, 2, 140}, {4, 5, 140},
				{1, 1, 90}, {6, 6, 90}, {6, 1, 80}, {1, 6, 80}};
		for (int[] p : px)s.put(p[0], p[1], Math.min(255, p[2] / 2 + 40), 20, Math.min(255, p[2] + 25), 255);
		return s;
	}
}
