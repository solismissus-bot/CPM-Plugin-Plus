package com.tom.cpm.blockbench.tools.particles;

import java.util.List;
import java.util.Map;

import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;

import jsinterop.base.Js;

/**
 * CPM+ (particles): the small texture packer the particle generator needs: {@link #occupancy(Texture)} (alpha &gt; 0 +
 * every cube face on the texture + vanilla parts of both layers + animated-texture regions), {@link #findFree} and
 * {@link #growCanvas}. Pixel coordinates of the texture image. Private to the particle generator.
 */
public final class ParticleTexPacker {
	private ParticleTexPacker() {
	}

	/** occupied pixels of one texture image */
	public static final class Occupancy {
		public final int w, h;
		/** image pixels per uv unit (x, y) */
		public final double densX, densY;
		final boolean[] used;
		private int[] sat;

		public Occupancy(int w, int h, double densX, double densY) {
			this.w = w;
			this.h = h;
			this.densX = densX;
			this.densY = densY;
			this.used = new boolean[Math.max(0, w * h)];
		}

		/** marks a rectangle given in UV units (clipped to the image) */
		public void markUv(double u, double v, double uw, double vh) {
			markPx((int) Math.floor(Math.min(u, u + uw) * densX), (int) Math.floor(Math.min(v, v + vh) * densY),
					(int) Math.ceil(Math.max(u, u + uw) * densX), (int) Math.ceil(Math.max(v, v + vh) * densY));
		}

		/** marks the pixels [x0, x1) x [y0, y1) */
		public void markPx(int x0, int y0, int x1, int y1) {
			x0 = Math.max(0, x0);
			y0 = Math.max(0, y0);
			x1 = Math.min(w, x1);
			y1 = Math.min(h, y1);
			for (int y = y0; y < y1; y++)for (int x = x0; x < x1; x++)used[y * w + x] = true;
			sat = null;
		}

		public boolean isUsed(int x, int y) {
			return used[y * w + x];
		}

		/** true when no pixel of [x, x+rw) x [y, y+rh) is used (and the rectangle is inside the image) */
		public boolean isFree(int x, int y, int rw, int rh) {
			if (x < 0 || y < 0 || x + rw > w || y + rh > h)return false;
			if (sat == null)buildSat();
			int W = w + 1;
			int s = sat[(y + rh) * W + (x + rw)] - sat[y * W + (x + rw)] - sat[(y + rh) * W + x] + sat[y * W + x];
			return s == 0;
		}

		private void buildSat() {
			int W = w + 1;
			sat = new int[W * (h + 1)];
			for (int y = 0; y < h; y++) {
				int row = 0;
				for (int x = 0; x < w; x++) {
					if (used[y * w + x])row++;
					sat[(y + 1) * W + (x + 1)] = sat[y * W + (x + 1)] + row;
				}
			}
		}
	}

	/**
	 * Occupancy of tex: alpha &gt; 0, every face of every cube using tex (box UV with CPM's ceil sizes, per-face UV
	 * rectangles), the vanilla skin parts of both layers (skin sheet only) and the C6 animated-texture regions
	 * ('start' area and the 'from' strip) of ProjectData.animTex.
	 */
	public static Occupancy occupancy(Texture tex) {
		int w = imgWidth(tex), h = imgHeight(tex);
		int uvW = tex.uv_width > 0 ? tex.uv_width : Project.texture_width;
		int uvH = tex.uv_height > 0 ? tex.uv_height : Project.texture_height;
		Occupancy o = new Occupancy(w, h, uvW > 0 ? w / (double) uvW : 1, uvH > 0 ? h / (double) uvH : 1);
		int[] alpha = alpha(tex.canvas, w, h);
		if (alpha != null) {
			for (int i = 0; i < alpha.length && i < o.used.length; i++)if (alpha[i] != 0)o.used[i] = true;
		}
		// cube faces
		if (Cube.all != null) {
			for (Cube c : Cube.all.array()) {
				if (c == null || c.faces == null)continue;
				boolean onTex = false;
				CubeFace[] fs = {c.faces.north, c.faces.south, c.faces.east, c.faces.west, c.faces.up, c.faces.down};
				for (CubeFace f : fs)if (f != null && tex.uuid != null && tex.uuid.equals(Js.asPropertyMap(f).get("texture")))onTex = true;
				if (!onTex && !(Texture.all != null && Texture.all.length == 1))continue;
				if (c.box_uv) {
					double dx = Math.ceil(Math.abs(c.to.x - c.from.x) - 1e-4), dy = Math.ceil(Math.abs(c.to.y - c.from.y) - 1e-4), dz = Math.ceil(Math.abs(c.to.z - c.from.z) - 1e-4);
					double u = c.uv_offset != null ? c.uv_offset.x : 0, v = c.uv_offset != null ? c.uv_offset.y : 0;
					o.markUv(u, v, 2 * (dx + dz), dz + dy);
				} else {
					for (CubeFace f : fs) {
						if (f == null || f.uv == null || f.isDisabled() || f.uv.isEmpty())continue;
						o.markUv(f.uv.sx, f.uv.sy, f.uv.ex - f.uv.sx, f.uv.ey - f.uv.sy);
					}
				}
			}
		}
		// vanilla parts, both layers (the skin sheet only)
		if (tex.name == null || tex.name.equalsIgnoreCase("skin") || tex.name.equalsIgnoreCase("skin.png") || Texture.all.length == 1) {
			SkinType st = RootUtil.projectSkinType();
			for (PlayerModelParts p : PlayerModelParts.VALUES) {
				if (p == PlayerModelParts.CUSTOM_PART)continue;
				markSpec(o, safeSpec(p, st, false));
				markSpec(o, safeSpec(p, st, true));
			}
		}
		// C6 animated-texture regions
		try {
			ProjectData pd = Project.getData();
			Map<String, Object> sheet = pd.animTex.get(TextureSheetType.SKIN);
			Object list = sheet != null ? sheet.get("anim") : null;
			if (list instanceof List) {
				for (Object e : (List<?>) list) {
					if (!(e instanceof Map))continue;
					Map<?, ?> m = (Map<?, ?>) e;
					double sx = vx(m.get("start"), "x"), sy = vx(m.get("start"), "y");
					double zw = vx(m.get("size"), "x"), zh = vx(m.get("size"), "y");
					double fx = vx(m.get("from"), "x"), fy = vx(m.get("from"), "y");
					double fc = m.get("frameCount") instanceof Number ? ((Number) m.get("frameCount")).doubleValue() : 1;
					boolean anX = Boolean.TRUE.equals(m.get("anX"));
					// AnimatedTex copies IMAGE pixels (AnimatedTexture.copyTexture), not uv units
					o.markPx((int) sx, (int) sy, (int) (sx + zw), (int) (sy + zh));
					o.markPx((int) fx, (int) fy, (int) (fx + (anX ? zw * fc : zw)), (int) (fy + (anX ? zh : zh * fc)));
				}
			}
		} catch (Throwable e) {
		}
		return o;
	}

	private static double vx(Object m, String k) {
		if (m instanceof Map) {
			Object v = ((Map<?, ?>) m).get(k);
			if (v instanceof Number)return ((Number) v).doubleValue();
		}
		return 0;
	}

	private static RootUtil.CubeSpec safeSpec(PlayerModelParts p, SkinType st, boolean second) {
		try {
			return second ? RootUtil.secondLayerCubeSpec(p, st) : RootUtil.vanillaCubeSpec(p, st);
		} catch (Throwable e) {
			return null;
		}
	}

	private static void markSpec(Occupancy o, RootUtil.CubeSpec s) {
		if (s == null || s.uvOffset == null || s.size == null)return;
		double dx = Math.ceil(s.size.x - 1e-4), dy = Math.ceil(s.size.y - 1e-4), dz = Math.ceil(s.size.z - 1e-4);
		o.markUv(s.uvOffset.x, s.uvOffset.y, 2 * (dx + dz), dz + dy);
	}

	/** first free w x h place (pixels) with x, y multiples of align, scanning rows top to bottom; null when full */
	public static int[] findFree(Occupancy o, int w, int h, int align) {
		int a = Math.max(1, align);
		for (int y = 0; y + h <= o.h; y += a) {
			for (int x = 0; x + w <= o.w; x += a) {
				if (o.isFree(x, y, w, h))return new int[] {x, y};
			}
		}
		return null;
	}

	/**
	 * Grows the texture image by integer factors (new area transparent, existing pixels unchanged) and scales the
	 * texture's uv size and Project.texture_* with it (same density: existing UVs stay valid). Call inside an undo
	 * edit with {textures, bitmap, uv_mode}.
	 */
	public static boolean growCanvas(Texture tex, int factorW, int factorH) {
		if (factorW < 1 || factorH < 1 || (factorW == 1 && factorH == 1))return false;
		int oldUvW = tex.uv_width > 0 ? tex.uv_width : Project.texture_width;
		int oldUvH = tex.uv_height > 0 ? tex.uv_height : Project.texture_height;
		if (!grow0(tex, factorW, factorH))return false;
		tex.uv_width = oldUvW * factorW;
		tex.uv_height = oldUvH * factorH;
		if (Project.texture_width == oldUvW)Project.texture_width = oldUvW * factorW;
		if (Project.texture_height == oldUvH)Project.texture_height = oldUvH * factorH;
		return true;
	}

	private static native boolean grow0(Texture tex, int fw, int fh) /*-{
		var c = tex.canvas;
		if (!c) return false;
		var tmp = $wnd.document.createElement('canvas');
		tmp.width = c.width; tmp.height = c.height;
		tmp.getContext('2d').drawImage(c, 0, 0);
		c.width = c.width * fw; c.height = c.height * fh;
		var ctx = tex.ctx || c.getContext('2d');
		ctx.clearRect(0, 0, c.width, c.height);
		ctx.drawImage(tmp, 0, 0);
		try { tex.width = c.width; tex.height = c.height; } catch (e) {}
		if (typeof tex.updateChangesAfterEdit === 'function') tex.updateChangesAfterEdit();
		else if (typeof tex.updateSource === 'function') tex.updateSource(c.toDataURL());
		return true;
	}-*/;

	public static native int imgWidth(Texture tex) /*-{
		if (tex.canvas && tex.canvas.width) return tex.canvas.width;
		if (tex.width) return tex.width;
		return tex.img && tex.img.naturalWidth ? tex.img.naturalWidth : 0;
	}-*/;

	public static native int imgHeight(Texture tex) /*-{
		if (tex.canvas && tex.canvas.height) return tex.canvas.height;
		if (tex.height) return tex.height;
		return tex.img && tex.img.naturalHeight ? tex.img.naturalHeight : 0;
	}-*/;

	/** alpha channel of the canvas (w * h), or null */
	private static native int[] alpha(Object canvas, int w, int h) /*-{
		try {
			if (!canvas || !w || !h) return null;
			var d = canvas.getContext('2d').getImageData(0, 0, w, h).data;
			var out = new Array(w * h);
			for (var i = 0; i < w * h; i++) out[i] = d[i * 4 + 3];
			return out;
		} catch (e) {
			return null;
		}
	}-*/;
}
