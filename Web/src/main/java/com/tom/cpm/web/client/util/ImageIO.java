package com.tom.cpm.web.client.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import com.tom.cpl.math.Vec2i;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ImageIO.IImageIO;
import com.tom.cpm.shared.editor.project.ProjectFile.BAIS;
import com.tom.cpm.web.client.java.Java;
import com.tom.cpm.web.client.java.Java.ResourceInputStream;
import com.tom.cpm.web.client.render.RenderSystem;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import elemental2.core.Uint32Array;
import elemental2.dom.Blob;
import elemental2.dom.CanvasRenderingContext2D;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLCanvasElement;
import elemental2.dom.ImageBitmap;
import elemental2.dom.ImageBitmapOptions;
import elemental2.promise.Promise;
import jsinterop.base.Js;

public class ImageIO implements IImageIO {
	public static final Cache<String, Image> loadedImages = CacheBuilder.newBuilder().expireAfterAccess(30, TimeUnit.SECONDS).build();

	@Override
	public Image read(InputStream f) throws IOException {
		if(f instanceof ResourceInputStream) {
			Image j = RenderSystem.preloadedAssets.get(((ResourceInputStream)f).path);
			if(j == null)throw new IOException("Resource not found: " + ((ResourceInputStream)f).path);
			return new Image(j);
		} else if (f instanceof BAIS) {
			BAIS b = (BAIS) f;
			if(b.getPath() != null) {
				// CPM+ (load A4): a PNG of an opened project file: pngj first (exactly the pixels v0.7 read), the
				// browser's decode of the same file only for what pngj can't read (palette / grayscale / 16-bit)
				try {
					return load(f, b.getPath());
				} catch (IOException e) {
					Image d = b.getDecoded();
					if(d != null)return new Image(d);
					throw e;
				}
			}
			Image j = loadedImages.size() > 0 ? loadedImages.getIfPresent(Base64.getEncoder().encodeToString(b.getBuf())) : null;
			if(j != null)return new Image(j);
			return load(f);// CPM+ (load A4): was 'Image not found'
		} else {
			return load(f);
		}
	}

	@Override
	public void write(Image img, File f) throws IOException {
		try(FileOutputStream fo = new FileOutputStream(f)) {
			write(img, fo);
		}
	}

	@Override
	public void write(Image img, OutputStream f) throws IOException {
		save(img, f);
	}

	@Override
	public Vec2i getSize(InputStream din) throws IOException {
		return new Vec2i();
	}

	@Override
	public CompletableFuture<Image> readF(File file) {
		return com.tom.cpm.web.client.java.io.FileInputStream.openFile(file).thenCompose(fin -> {
			try (InputStream fi = fin) {
				try {
					return CompletableFuture.completedFuture(read(fi));
				} catch (Exception e) {
					CompletableFuture<Image> f = new CompletableFuture<>();
					Java.promiseToCf(loadImage(fin.getB64(), true, false), f);
					return f;
				}
			} catch (Exception e) {
				CompletableFuture<Image> f = new CompletableFuture<>();
				f.completeExceptionally(e);
				return f;
			}
		});
	}

	public static CompletableFuture<Image> loadImage(byte[] f) {
		String b64 = Base64.getEncoder().encodeToString(f);
		Image j = loadedImages.getIfPresent(b64);
		if(j == null) {
			CompletableFuture<Image> cf = new CompletableFuture<>();
			Java.promiseToCf(loadImage(b64, true, true), cf);
			return cf;
		} else {
			return CompletableFuture.completedFuture(new Image(j));
		}
	}

	public static Promise<Image> loadImage(String data, boolean b64, boolean cache) {
		ImageBitmapOptions opt = ImageBitmapOptions.create();
		opt.setColorSpaceConversion("none");
		opt.setPremultiplyAlpha("none");
		return DomGlobal.fetch(b64 ? "data:image/png;base64," + data : data).then(r -> r.blob()).
				then(b -> DomGlobal.createImageBitmap(b, opt)).then(img -> {
					HTMLCanvasElement canvas = Js.uncheckedCast(DomGlobal.document.createElement("canvas"));
					canvas.width = img.getWidth();
					canvas.height = img.getHeight();
					CanvasRenderingContext2D ctx = Js.uncheckedCast(canvas.getContext("2d"));
					ctx.globalCompositeOperation = "copy";
					ctx.drawImage(img, 0, 0);
					Image image = new Image(new Uint32Array(ctx.getImageData(0, 0, img.getWidth(), img.getHeight()).data.buffer), img.getWidth(), img.getHeight());
					if(cache)loadedImages.put(data, image);
					return Promise.resolve(image);
				});
	}

	/**
	 * CPM+ (load A4/A6): decodes an image Blob with the browser (createImageBitmap, no premultiply, no colour space
	 * conversion): the same pixels as {@link #loadImage(String, boolean, boolean)} without the base64 data-URL round
	 * trip. Palette, grayscale and 16-bit PNGs work.
	 */
	public static Promise<Image> loadImage(Blob blob) {
		ImageBitmapOptions opt = ImageBitmapOptions.create();
		opt.setColorSpaceConversion("none");
		opt.setPremultiplyAlpha("none");
		return DomGlobal.createImageBitmap(blob, opt).then(img -> Promise.resolve(fromBitmap(img)));
	}

	/** CPM+ (load A4): PNG bytes (Uint8Array) -> Blob for {@link #loadImage(Blob)} */
	public static native Blob pngBlob(Object uint8Array)/*-{
		return new Blob([uint8Array], {type: 'image/png'});
	}-*/;

	/** the drawing code of {@link #loadImage(String, boolean, boolean)} */
	private static Image fromBitmap(ImageBitmap img) {
		int w = img.getWidth();
		int h = img.getHeight();
		HTMLCanvasElement canvas = Js.uncheckedCast(DomGlobal.document.createElement("canvas"));
		canvas.width = w;
		canvas.height = h;
		CanvasRenderingContext2D ctx = Js.uncheckedCast(canvas.getContext("2d"));
		ctx.globalCompositeOperation = "copy";
		ctx.drawImage(img, 0, 0);
		Image image = new Image(new Uint32Array(ctx.getImageData(0, 0, w, h).data.buffer), w, h);
		closeBitmap(img);
		canvas.width = 0;
		canvas.height = 0;
		return image;
	}

	private static native void closeBitmap(Object b)/*-{
		try {
			if (b && typeof b.close === 'function') b.close();
		} catch (e) {}
	}-*/;

	public static Image load(InputStream din) throws IOException {
		return load(din, null);
	}

	/**
	 * pngj decode (RGB8 / RGBA8). CPM+ (load A4): path != null (a PNG of a project file): a clear error naming the
	 * file for PNG formats pngj can't read and for damaged files; RGB8 (no alpha channel) gets alpha 255 (it read
	 * the next pixel's red as alpha).
	 */
	public static Image load(InputStream din, String path) throws IOException {
		try {
			PngReader pngr = new PngReader(din);
			int channels = pngr.imgInfo.channels;
			if (channels < 3 || pngr.imgInfo.bitDepth != 8) {
				if(path != null)throw new UnsupportedPngException(path);
				throw new RuntimeException("This method is for RGB8/RGBA8 images");
			}

			Image img = new Image(pngr.imgInfo.cols, pngr.imgInfo.rows);
			for (int row = 0; row < pngr.imgInfo.rows; row++) {
				ImageLineInt l1 = (ImageLineInt) pngr.readRow();
				int[] scanline = l1.getScanline();
				for (int j = 0; j < pngr.imgInfo.cols; j++) {
					int offset = j * channels;
					int a = channels > 3 ? scanline[offset + 3] : 0xff;// CPM+ (load A4): RGB8 = opaque
					img.loadRGB(j, row, (a << 24) | (scanline[offset + 2] << 16) | (scanline[offset + 1] << 8)
							| (scanline[offset]));
				}
			}
			pngr.end();
			return img;
		} catch (UnsupportedPngException e) {
			throw e;
		} catch (Throwable e) {
			if(path == null)throw new IOException(e);
			throw new UnreadablePngException(path, e);
		}
	}

	public static void save(Image img, OutputStream os) throws IOException {
		try {
			ImageInfo imi = new ImageInfo(img.getWidth(), img.getHeight(), 8, true);
			PngWriter pngw = new PngWriter(os, imi);
			for(int y = 0;y<img.getHeight();y++) {
				int[] d = new int[img.getWidth() * 4];
				for(int x = 0;x<img.getWidth();x++) {
					int c = img.storeRGB(x, y);
					int off = x * 4;
					d[off] = c & 0xff;
					d[off + 1] = ((c & 0xff00) >> 8);
					d[off + 2] = ((c & 0xff0000) >> 16);
					d[off + 3] = ((c & 0xff000000) >> 24);
				}
				pngw.writeRowInt(d);
			}
			pngw.end();
		} catch (Throwable e) {
			throw new IOException(e);
		}
	}

	@Override
	public Image read(File f) throws IOException {
		throw new UnsupportedOperationException();
	}

	/** CPM+ (load A4): a PNG of a project file nobody could read */
	public static class PngException extends IOException {
		private static final long serialVersionUID = 1L;
		private final String path;

		public PngException(String path, String message, Throwable cause) {
			super(message, cause);
			this.path = path;
		}

		public String getPath() {
			return path;
		}
	}

	/** palette / grayscale / 16-bit PNG that the browser could not decode either */
	public static class UnsupportedPngException extends PngException {
		private static final long serialVersionUID = 1L;

		public UnsupportedPngException(String path) {
			super(path, "PNG " + path + " uses an unsupported format (palette/grayscale/16-bit). Re-save it as RGBA 8-bit.", null);
		}
	}

	/** damaged PNG (or not a PNG at all) */
	public static class UnreadablePngException extends PngException {
		private static final long serialVersionUID = 1L;

		public UnreadablePngException(String path, Throwable cause) {
			super(path, "PNG " + path + " is damaged or is not a PNG. Re-save it as RGBA 8-bit.", cause);
		}
	}
}
