package com.tom.cpm.web.client.util;

import elemental2.core.ArrayBuffer;
import elemental2.dom.Blob;
import elemental2.promise.Promise;
import jsinterop.annotations.JsMethod;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL)
public class JSZip {

	public native JSZip file(String name, String content);
	public native ZipEntry file(String name);
	public native JSZip folder(String name);
	public native Promise<Blob> generateAsync(ZipWriteProperties arg);
	public native Promise<JSZip> loadAsync(ArrayBuffer content);
	public native void file(String name, String content, ZipFileProperties prop);
	/** CPM+ (A5): binary entry (Uint8Array) with its own options (compression) */
	@JsMethod(name = "file")
	public native void fileBinary(String name, Object uint8Array, ZipFileProperties prop);

	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	public static class ZipEntry {
		public native Promise<String> async(String type);
		/** CPM+ (A4): async('uint8array') -> Uint8Array (no base64 string in between) */
		@JsMethod(name = "async")
		public native Promise<Object> asyncData(String type);
		/** CPM+ (A4): true for directory entries */
		public boolean dir;
	}

	public JsPropertyMap<ZipEntry> files;

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "Object")
	public static class ZipFileProperties {
		public boolean base64;
		/** CPM+ (A5): per-entry compression ('STORE' / 'DEFLATE') */
		public String compression;

		@JsOverlay
		public static ZipFileProperties make() {
			ZipFileProperties v = new ZipFileProperties();
			v.base64 = true;
			return v;
		}

		/** CPM+ (A5): a Uint8Array entry, STORE (already compressed data: PNG) or DEFLATE */
		@JsOverlay
		public static ZipFileProperties binary(boolean store) {
			ZipFileProperties v = new ZipFileProperties();
			v.compression = store ? "STORE" : "DEFLATE";
			return v;
		}
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "Object")
	public static class ZipWriteProperties {
		public String type, compression;
		public ZipCompressionOptions compressionOptions;
		/** CPM+ (A5): must stay false (sizes + CRC in the local headers: Java's ZipInputStream can't read STORED entries with a data descriptor) */
		public boolean streamFiles;

		@JsOverlay
		public static ZipWriteProperties make() {
			ZipWriteProperties v = new ZipWriteProperties();
			v.type = "blob";
			v.compression = "DEFLATE";
			v.compressionOptions = new ZipCompressionOptions();
			v.compressionOptions.level = 9;
			return v;
		}

		/** CPM+ (A5): blob, DEFLATE level 6 (per-entry STORE for PNGs), no data descriptors */
		@JsOverlay
		public static ZipWriteProperties makeFast() {
			ZipWriteProperties v = make();
			v.compressionOptions.level = 6;
			v.streamFiles = false;
			return v;
		}
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "Object")
	public static class ZipCompressionOptions {
		public int level;
	}
}
