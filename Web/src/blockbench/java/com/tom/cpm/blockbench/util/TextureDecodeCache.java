package com.tom.cpm.blockbench.util;

import java.util.HashMap;
import java.util.Map;

import com.tom.cpl.util.Image;
import com.tom.cpm.blockbench.PlusTestHooks;

/**
 * CPM+ (load A7): decoded Blockbench textures kept while one export / validation runs (the warning dialog's quick
 * fixes re-run the whole export: the textures are decoded once). An entry is used only when the texture's source is
 * the SAME string as when it was decoded (any paint / edit changes it), and every caller gets its own copy of the
 * image (CPM's texture code may change it). Cleared when the warning dialog closes, when the export / validation
 * finishes, when another project is selected or closed, and when the plugin unloads (big sheets are ~10 MB each).
 */
public final class TextureDecodeCache {
	private static final Map<String, CacheEntry> cache = new HashMap<>();

	private TextureDecodeCache() {
	}

	private static final class CacheEntry {
		final String source;
		final Image image;
		final long bytes;

		CacheEntry(String source, Image image, long bytes) {
			this.source = source;
			this.image = image;
			this.bytes = bytes;
		}
	}

	/** a decoded texture: a copy of the image + the size of its PNG (for the size estimate) */
	public static final class Hit {
		public final Image image;
		public final long bytes;

		Hit(Image image, long bytes) {
			this.image = image;
			this.bytes = bytes;
		}
	}

	/** the cached decode of texture uuid when its source is exactly the same, else null */
	public static Hit get(String uuid, String source) {
		if(uuid == null || source == null)return null;
		CacheEntry e = cache.get(uuid);
		if(e == null || !e.source.equals(source))return null;
		PlusTestHooks.count("texCacheHits");
		return new Hit(new Image(e.image), e.bytes);
	}

	/** stores a fresh decode (counted as one texture decode for the QA counters) */
	public static void put(String uuid, String source, Image image, long bytes) {
		PlusTestHooks.count("texDecodes");
		if(uuid == null || source == null || image == null)return;
		cache.put(uuid, new CacheEntry(source, new Image(image), bytes));
	}

	public static void clear() {
		cache.clear();
	}

	public static int size() {
		return cache.size();
	}
}
