package com.tom.cpm.blockbench.tools.particles;

import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.tools.particles.ParticleSprites.Sprite;

import elemental2.promise.Promise;

/**
 * CPM+ (W6-PARTICLES): puts the particle image into the skin atlas. Normal parts can only read skin.png (the cape /
 * elytra / armor sheets only work under their own roots), so the sprite is packed into a FULLY TRANSPARENT place of
 * the skin that no UV (box, per-face, extrude), no vanilla part of either layer and no animated-texture region (C6
 * 'start' / 'from') uses. Occupancy and growth come from ParticleTexPacker. Only the sprite's own pixels are
 * written: every other pixel of the skin stays identical; existing UVs are never moved.
 */
public final class ParticleAtlas {
	private ParticleAtlas() {
	}

	/** where the sprite goes (pixels of the image, and uv units for the faces) */
	public static final class Plan {
		public int x, y, sizePx;
		public double u, v, sizeUv;
		/** the image must grow (height x2 per step) first */
		public int growH = 1;
		public int oldW, oldH, newW, newH;
		/** null = ok, else a pt-BR reason key */
		public String errorKey;
	}

	/**
	 * Finds a place for a sprite of spritePx x spritePx image pixels (rounded up to a whole number of uv units).
	 * Grows the height x2 (up to 4 times, never past 8192 px) only when the current image is full: plan.growH.
	 */
	public static Plan plan(Texture skin, int spritePx) {
		Plan p = new Plan();
		ParticleTexPacker.Occupancy occ = ParticleTexPacker.occupancy(skin);
		p.oldW = occ.w;
		p.oldH = occ.h;
		p.newW = occ.w;
		p.newH = occ.h;
		if (occ.w <= 0 || occ.h <= 0) {
			p.errorKey = "cpmplus.w6particles.err.noSkin";
			return p;
		}
		double dx = occ.densX, dy = occ.densY;
		if (Math.abs(dx - Math.round(dx)) > 1e-6 || Math.abs(dy - Math.round(dy)) > 1e-6 || Math.round(dx) != Math.round(dy) || dx < 1) {
			p.errorKey = "cpmplus.w6particles.err.density";
			return p;
		}
		int d = (int) Math.round(dx);
		int uvUnits = Math.max(1, (int) Math.ceil(spritePx / (double) d));
		int px = uvUnits * d;
		p.sizePx = px;
		p.sizeUv = uvUnits;
		int[] at = ParticleTexPacker.findFree(occ, px, px, d);
		int grow = 1;
		while (at == null && grow < 16 && occ.h * grow * 2 <= 8192) {
			grow *= 2;
			// a grown image: the new rows are free
			ParticleTexPacker.Occupancy g = new ParticleTexPacker.Occupancy(occ.w, occ.h * grow, occ.densX, occ.densY);
			for (int y = 0; y < occ.h; y++)for (int x = 0; x < occ.w; x++)if (occ.isUsed(x, y))g.markPx(x, y, x + 1, y + 1);
			at = ParticleTexPacker.findFree(g, px, px, d);
		}
		if (at == null) {
			p.errorKey = "cpmplus.w6particles.err.noRoom";
			return p;
		}
		p.growH = grow;
		p.newH = occ.h * grow;
		p.x = at[0];
		p.y = at[1];
		p.u = at[0] / (double) d;
		p.v = at[1] / (double) d;
		return p;
	}

	/** grows (when planned) and paints the sprite (scaled to plan.sizePx, nearest neighbour). Inside the undo edit. */
	public static void apply(Texture skin, Plan p, Sprite sprite) {
		if (p.growH > 1 && !ParticleTexPacker.growCanvas(skin, 1, p.growH))throw new IllegalStateException("could not grow the skin texture");
		Sprite s = sprite.scaled(p.sizePx);
		paint(skin, p.x, p.y, s.w, s.h, s.rgba, true);
	}

	/** clears a rectangle to transparent (old sprite slot of a regenerated system) */
	public static void clear(Texture skin, int x, int y, int w, int h) {
		if (w <= 0 || h <= 0)return;
		paint(skin, x, y, w, h, new int[w * h * 4], false);
	}

	private static native void paint(Texture tex, int x, int y, int w, int h, int[] rgba, boolean onlyVisible) /*-{
		var c = tex.canvas;
		if (!c) throw new Error('texture has no canvas');
		var ctx = tex.ctx || c.getContext('2d');
		var img = ctx.getImageData(x, y, w, h);
		for (var p = 0; p < w * h; p++) {
			if (onlyVisible && !rgba[p * 4 + 3]) continue;// only the sprite's own pixels change
			for (var i = 0; i < 4; i++) img.data[p * 4 + i] = rgba[p * 4 + i];
		}
		ctx.putImageData(img, x, y);
		if (typeof tex.updateChangesAfterEdit === 'function') tex.updateChangesAfterEdit();
		else if (typeof tex.updateSource === 'function') tex.updateSource(c.toDataURL());
	}-*/;

	/** RGBA of the image pixels in [x, x+w) x [y, y+h) */
	public static native int[] read(Texture tex, int x, int y, int w, int h) /*-{
		var c = tex.canvas;
		if (!c) return [];
		var d = (tex.ctx || c.getContext('2d')).getImageData(x, y, w, h).data;
		var out = new Array(w * h * 4);
		for (var i = 0; i < out.length; i++) out[i] = d[i];
		return out;
	}-*/;

	/** decodes a PNG data URL (the user's image) into RGBA */
	public static Promise<Sprite> decode(String dataUrl) {
		return new Promise<>((res, rej) -> decode0(dataUrl, (w, h, rgba) -> {
			if (w <= 0 || h <= 0) {
				rej.onInvoke("bad image");
				return;
			}
			Sprite s = new Sprite(w, h);
			for (int i = 0; i < s.rgba.length && i < rgba.length; i++)s.rgba[i] = rgba[i];
			res.onInvoke(s);
		}));
	}

	@jsinterop.annotations.JsFunction
	interface Decoded {
		void done(int w, int h, int[] rgba);
	}

	private static native void decode0(String url, Decoded cb) /*-{
		var img = new $wnd.Image();
		img.onload = function() {
			try {
				var c = $wnd.document.createElement('canvas');
				c.width = img.naturalWidth; c.height = img.naturalHeight;
				var ctx = c.getContext('2d');
				ctx.drawImage(img, 0, 0);
				var d = ctx.getImageData(0, 0, c.width, c.height).data;
				var out = new Array(d.length);
				for (var i = 0; i < d.length; i++) out[i] = d[i];
				cb(c.width, c.height, out);
			} catch (e) {
				cb(0, 0, []);
			}
		};
		img.onerror = function() { cb(0, 0, []); };
		img.src = url;
	}-*/;

	/** a PNG data URL of a sprite (saved in the parameters of a custom image) */
	public static native String toDataUrl(int w, int h, int[] rgba) /*-{
		var c = $wnd.document.createElement('canvas');
		c.width = w; c.height = h;
		var ctx = c.getContext('2d');
		var img = ctx.createImageData(w, h);
		for (var i = 0; i < w * h * 4; i++) img.data[i] = rgba[i];
		ctx.putImageData(img, 0, 0);
		return c.toDataURL('image/png');
	}-*/;
}
