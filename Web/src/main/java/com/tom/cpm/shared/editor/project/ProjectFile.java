package com.tom.cpm.shared.editor.project;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.tom.cpl.util.Image;
import com.tom.cpl.util.ThrowingConsumer;
import com.tom.cpl.util.ThrowingFunction;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.web.client.FS;
import com.tom.cpm.web.client.java.Java;
import com.tom.cpm.web.client.util.ByteArrays;
import com.tom.cpm.web.client.util.ImageIO;
import com.tom.cpm.web.client.util.JSZip;
import com.tom.cpm.web.client.util.JSZip.ZipEntry;
import com.tom.cpm.web.client.util.JSZip.ZipFileProperties;
import com.tom.cpm.web.client.util.JSZip.ZipWriteProperties;

import elemental2.core.JsObject;
import elemental2.dom.Blob;
import elemental2.dom.DomGlobal;
import elemental2.dom.FileReader;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * Web replacement of CPM's ProjectFile (the zip contents of a .cpmproject).
 * <p>
 * CPM+ (load A4/A5): entries are read as Uint8Array (no base64 string; the Java byte[] is made on the first
 * {@link #getEntry}); JSON is decoded with the browser's TextDecoder (strict UTF-8, else Latin-1, BOM removed: the same
 * text as v0.7's InputStreamReader) and parsed with the same Gson as before; PNGs are read by pngj like v0.7, and PNGs
 * pngj can't read (palette / grayscale / 16-bit) are decoded by the browser when the file is opened. Saving writes
 * binary entries (PNG = STORE, the rest DEFLATE level 6, sizes and CRC in the local headers) in v0.7's order.
 * {@link #setLoadCache(boolean)} lets the plugin's import parse config.json once for all loaders.
 */
public class ProjectFile implements IProject {
	private Entry root;
	/** CPM+ (load A4): PNGs pngj can't read, decoded by the browser when this file was opened (this instance only) */
	private Map<String, Image> decoded = new HashMap<>();
	/** CPM+ (load A4): parse config.json once (only while the plugin's import loads the project) */
	private boolean loadCache;
	private Map<String, Object> configMemo;

	public ProjectFile() {
		root = new Entry();
		root.children = new HashMap<>();
	}

	public boolean setEntry(String path, byte[] data) {
		Entry e = root;
		for(String nm : path.split("/")) {
			if(e.isFile())return false;
			if(e.children == null)e.children = new HashMap<>();
			e = e.children.computeIfAbsent(nm, k -> new Entry());
		}
		e.data = data;
		e.raw = null;
		changed(path);
		return true;
	}

	/** CPM+ (load A4): entry read from a zip, kept as Uint8Array until someone asks for its bytes */
	private boolean setRaw(String path, Object uint8Array) {
		Entry e = root;
		for(String nm : path.split("/")) {
			if(e.isFile())return false;
			if(e.children == null)e.children = new HashMap<>();
			e = e.children.computeIfAbsent(nm, k -> new Entry());
		}
		e.data = null;
		e.raw = uint8Array;
		changed(path);
		return true;
	}

	@Override
	public byte[] getEntry(String path) {
		Entry e = find(path);
		return e == null ? null : e.getData();
	}

	private Entry find(String path) {
		Entry e = root;
		for(String nm : path.split("/")) {
			if(e.children == null)return null;
			e = e.children.get(nm);
			if(e == null)return null;
		}
		return e;
	}

	@Override
	public List<String> listEntires(String path) {
		Entry e = root;
		for(String nm : path.split("/")) {
			e = e.children.get(nm);
			if(e == null)return null;
		}
		return e.children == null ? null : new ArrayList<>(e.children.keySet());
	}

	public void delete(String path) {
		changed(path);
		Entry e = root;
		String[] t = path.split("/");
		for (int i = 0; i < t.length-1; i++) {
			e = e.children.get(t[i]);
			if(e == null)return;
		}
		if(e.children != null)
			e.children.remove(t[t.length - 1]);
	}


	public void clearFolder(String path) {
		configMemo = null;
		decoded.clear();
		Entry e = root;
		for(String nm : path.split("/")) {
			e = e.children.get(nm);
			if(e == null)return;
		}
		if(e.children != null)
			e.children.clear();
	}

	public OutputStream setAsStream(String path) throws IOException {
		if(!setEntry(path, new byte[0]))
			throw new FileNotFoundException();
		return new ByteArrayOutputStream() {

			@Override
			public void close() throws IOException {
				if(!setEntry(path, toByteArray()))
					throw new FileNotFoundException();
			}
		};
	}

	public CompletableFuture<Void> load(File file) {
		root = new Entry();
		root.children = new HashMap<>();
		CompletableFuture<Void> cf = new CompletableFuture<>();
		FS.getContentFuture(file.getAbsolutePath()).
		then(b -> b.arrayBuffer()).
		then(b -> new JSZip().loadAsync(b)).
		then(this::load).then(v -> {
			cf.complete(null);
			return null;
		}).catch_(ex -> {
			if(ex instanceof Throwable)cf.completeExceptionally((Throwable) ex);
			else cf.completeExceptionally(new IOException(String.valueOf(ex)));
			return null;
		});
		return cf;
	}

	/**
	 * CPM+ (load A4): every file entry (directory entries skipped) is read as Uint8Array and stored in the order the
	 * reads finish (as v0.7 did with base64); then every PNG that pngj can't read (not 8-bit RGB / RGBA) is decoded by
	 * the browser into this instance's image map (a PNG nobody can read gets a clear error when a loader reads it).
	 */
	public Promise<Object[]> load(JSZip zip) {
		decoded = new HashMap<>();
		configMemo = null;
		time("zip");
		List<Promise<Object>> resolved = new ArrayList<>();
		List<String> pngs = new ArrayList<>();
		for(String f : JsObject.keys(zip.files).asList()) {
			ZipEntry e = zip.file(f);
			if(e != null && !e.dir) {
				if(f.endsWith(".png"))pngs.add(f);
				resolved.add(e.asyncData("uint8array").then(dt -> {
					setRaw(f, dt);
					return null;
				}));
			}
		}
		Promise<Object[]> reads = Promise.all(Js.cast(resolved.toArray(new Promise[0])));
		return reads.then(__ -> {
			timeEnd("zip");
			List<Promise<Object>> dec = new ArrayList<>();
			for (String f : pngs) {
				Entry en = find(f);
				Object u8 = en != null ? en.raw : null;
				if(u8 == null || pngjCanRead(u8))continue;
				dec.add(ImageIO.loadImage(ImageIO.pngBlob(u8)).then(img -> {
					decoded.put(f, img);
					return null;
				}, err -> {
					DomGlobal.console.warn("CPM+: the browser could not decode " + f, err);
					return null;
				}));
			}
			Promise<Object[]> all;
			if(dec.isEmpty()) {
				all = Promise.resolve(new Object[0]);
			} else {
				time("png");
				all = Promise.all(Js.cast(dec.toArray(new Promise[0])));
				all = all.then(r -> {
					timeEnd("png");
					return Promise.resolve(r);
				});
			}
			return all;
		});
	}

	/** 8-bit RGB / RGBA PNG (IHDR bit depth 8, colour type 2 or 6): what pngj (and v0.7) reads */
	private static boolean pngjCanRead(Object u8) {
		int depth = ByteArrays.byteAt(u8, 24);
		int type = ByteArrays.byteAt(u8, 25);
		return depth == 8 && (type == 2 || type == 6);
	}

	public CompletableFuture<Void> save(File file) {
		CompletableFuture<Void> cf = new CompletableFuture<>();
		save().then(blob -> {
			FileReader reader = new FileReader();
			reader.readAsDataURL(blob);
			reader.onloadend = e -> {
				String base64data = reader.result.asString();
				String dt = base64data.substring(base64data.indexOf(',') + 1);
				Java.promiseToCf(FS.setContent(file.getAbsolutePath(), dt), cf);
				return null;
			};
			return null;
		});
		return cf;
	}

	/**
	 * CPM+ (load A5): binary entries (no base64), *.png STORE (already compressed), everything else DEFLATE level 6,
	 * streamFiles off (sizes + CRC in the local headers: Java's ZipInputStream - the game - reads it). Entries are
	 * written in exactly v0.7's order (each folder entry before its files).
	 */
	public Promise<Blob> save() {
		try {
			time("save");
			JSZip zip = new JSZip();
			Deque<ZEntry> queue = new LinkedList<>();
			queue.push(new ZEntry(root, zip));
			ZEntry directory;
			while (!queue.isEmpty()) {
				directory = queue.pop();
				for (java.util.Map.Entry<String, Entry> e : directory.entry.children.entrySet()) {
					if(e.getValue().children != null) {
						queue.push(new ZEntry(e.getValue(), directory.zip.folder(e.getKey())));
					} else {
						directory.zip.fileBinary(e.getKey(), e.getValue().zipData(), ZipFileProperties.binary(e.getKey().endsWith(".png")));
					}
				}
			}
			return zip.generateAsync(ZipWriteProperties.makeFast()).then(b -> {
				timeEnd("save");
				return Promise.resolve(b);
			});
		} catch (Throwable e) {
			return Promise.reject(e);
		}
	}

	@Override
	public InputStream getAsStream(String path) throws IOException {
		byte[] dt = getEntry(path);
		if(dt == null)throw new FileNotFoundException();
		return new BAIS(dt, path, decoded.get(path));
	}

	// ------------------------------------------------------------------ CPM+ (load A4): JSON and images

	@Override
	public JsonMap getJson(String path) throws IOException {
		Map<String, Object> m = parseJson(path);
		if(m == null)throw new FileNotFoundException();
		return new JsonMapImpl(m);
	}

	@Override
	public <E extends Throwable> void jsonIfExists(String path, ThrowingConsumer<JsonMap, E> e) throws IOException, E {
		Map<String, Object> m = parseJson(path);
		if(m != null)e.accept(new JsonMapImpl(m));
	}

	@Override
	public <R, E extends Throwable> void ifExists(String path, ThrowingFunction<InputStream, R, E> loader, Consumer<R> e) throws E {
		byte[] ze = getEntry(path);
		if(ze != null)
			e.accept(loader.apply(new BAIS(ze, path, decoded.get(path))));
	}

	@Override
	public <R, E extends Throwable> R getIfExists(String path, ThrowingFunction<InputStream, R, E> e) throws E {
		byte[] ze = getEntry(path);
		if(ze != null)
			return e.apply(new BAIS(ze, path, decoded.get(path)));
		else
			return null;
	}

	/**
	 * The ONE parse path for every JSON file (the same Gson conversion as v0.7, so storeIDs round identically
	 * everywhere): bytes -> text (strict UTF-8, else Latin-1, BOM removed) -> Gson. null = no such file.
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> parseJson(String path) throws IOException {
		boolean config = loadCache && "config.json".equals(path);
		if(config && configMemo != null)return configMemo;
		Entry en = find(path);
		if(en == null || !en.isFile())return null;
		Object u8 = en.raw != null ? en.raw : ByteArrays.toUint8Array(en.data);
		String text = ByteArrays.decodeText(u8);
		Map<String, Object> m = (Map<String, Object>) MinecraftObjectHolder.gson.fromJson(text, Object.class);
		count("jsonParses");
		count("jsonParses:" + path);
		if(config)configMemo = m;
		return m;
	}

	/**
	 * CPM+ (load A4): true = config.json is parsed once and shared by the loaders (they only read it; used by the
	 * plugin's import around ProjectIO.loadProject); false drops it again (and the decoded fallback images).
	 */
	public void setLoadCache(boolean on) {
		loadCache = on;
		configMemo = null;
		if(!on)decoded = new HashMap<>();
	}

	private void changed(String path) {
		if(configMemo != null && (path == null || path.isEmpty() || path.equals("config.json")))configMemo = null;
		if(path != null)decoded.remove(path);
	}

	// ------------------------------------------------------------------ CPM+ (load): plugin timers / counters

	/** CPM+ (load): timers and counters of the plugin's test hooks (null = off) */
	public interface Instrumentation {
		void time(String name);
		void timeEnd(String name);
		void count(String name);
	}

	private static Instrumentation instrumentation;

	public static void setInstrumentation(Instrumentation i) {
		instrumentation = i;
	}

	private static void time(String n) {
		Instrumentation i = instrumentation;
		if(i != null)try {
			i.time(n);
		} catch (Throwable e) {
		}
	}

	private static void timeEnd(String n) {
		Instrumentation i = instrumentation;
		if(i != null)try {
			i.timeEnd(n);
		} catch (Throwable e) {
		}
	}

	private static void count(String n) {
		Instrumentation i = instrumentation;
		if(i != null)try {
			i.count(n);
		} catch (Throwable e) {
		}
	}

	public static class BAIS extends ByteArrayInputStream {
		private final String path;
		private final Image decoded;

		public BAIS(byte[] buf) {
			this(buf, null, null);
		}

		/** CPM+ (load A4): an entry of a project file: its path and the browser's decode when pngj can't read it */
		public BAIS(byte[] buf, String path, Image decoded) {
			super(buf);
			this.path = path;
			this.decoded = decoded;
		}

		public byte[] getBuf() {
			return buf;
		}

		public String getPath() {
			return path;
		}

		public Image getDecoded() {
			return decoded;
		}
	}

	private static class Entry {
		private Map<String, Entry> children;
		private byte[] data;
		/** CPM+ (load A4): Uint8Array read from the zip, converted to data on the first getData() */
		private Object raw;

		private boolean isFile() {
			return data != null || raw != null;
		}

		private byte[] getData() {
			if(data == null && raw != null) {
				data = ByteArrays.toBytes(raw);
				raw = null;
			}
			return data;
		}

		/** Uint8Array for JSZip (no conversion for entries nobody read as bytes) */
		private Object zipData() {
			if(raw != null)return raw;
			return ByteArrays.toUint8Array(data != null ? data : new byte[0]);
		}

		@Override
		public String toString() {
			return isFile() ? "Data" : children.toString();
		}
	}

	private static class ZEntry {
		private Entry entry;
		private JSZip zip;

		public ZEntry(Entry entry, JSZip zip) {
			this.entry = entry;
			this.zip = zip;
		}
	}
}
