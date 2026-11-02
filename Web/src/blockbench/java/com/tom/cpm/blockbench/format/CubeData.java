package com.tom.cpm.blockbench.format;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.util.DataCache;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;

/**
 * CPM data of one Blockbench cube (cube.cpm_data JSON string).
 *
 * CPM+ (G16, preserving flush): the parsed JSON is kept; flush() (only after a change, as before) writes a copy of
 * it with the known keys set or explicitly removed, so keys written by a newer CPM+ survive. Plugin-only data of
 * later features: getExtra/putExtra/removeExtra.
 */
public class CubeData {
	/** CPM+ (G16): keys this class writes itself (u/v/ts = legacy flat extrude keys); extras may not use them */
	public static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"extrude", "sid", "vanilla", "u", "v", "ts")));

	private final Cube cube;
	private boolean changed;
	private Extrude extrude;
	// CPM+ : stable storeID and 'this cube is the vanilla part' marker (see BlockbenchImport)
	private String storeID;
	private boolean vanilla;
	/** CPM+ (G16): the parsed cpm_data as read (key order kept), extras live here too */
	private final Map<String, Object> raw;
	/** CPM+ (G16): the cpm_data string this object was parsed from or last flushed to (cache check) */
	private String source;

	public CubeData(Cube cube) {
		this.cube = cube;
		this.source = cube.pluginData;
		Map<String, Object> parsed = null;
		if(cube.pluginData != null && !cube.pluginData.isEmpty()) {
			JsonMap s0 = JsonUtil.fromJson(cube.pluginData);
			parsed = s0.asMap();
			JsonMap s = new JsonMapImpl(JsonUtil.copyMap(parsed));
			if(s.containsKey("extrude")) {
				this.extrude = new Extrude();
				// CPM+ : flush() writes u/v/ts INSIDE the 'extrude' map, the old code read them from the
				// top level (always 0 after reopening the .bbmodel). Old top-level keys still accepted.
				JsonMap ex = s.get("extrude") instanceof Map ? s.getMap("extrude") : null;
				JsonMap src = ex != null && ex.containsKey("ts") ? ex : s;
				this.extrude.u = src.getInt("u", 0);
				this.extrude.v = src.getInt("v", 0);
				this.extrude.ts = src.getInt("ts", 0);
			}
			if(s.containsKey("sid"))storeID = String.valueOf(s.get("sid"));
			vanilla = s.getBoolean("vanilla", false);
		}
		raw = parsed != null ? parsed : new LinkedHashMap<>();
	}

	private void markDirty() {
		changed = true;
	}

	public void flush() {
		if(changed) {
			// CPM+ (G16): start from the data as read, set or REMOVE every known key
			Map<String, Object> pluginDt = JsonUtil.copyMap(raw);
			// legacy flat extrude keys (read above, now written inside 'extrude')
			pluginDt.remove("u");
			pluginDt.remove("v");
			pluginDt.remove("ts");
			if(extrude != null) {
				Map<String, Object> ex = new LinkedHashMap<>();
				ex.put("u", extrude.u);
				ex.put("v", extrude.v);
				ex.put("ts", extrude.ts);
				pluginDt.put("extrude", JsonUtil.keepUnknownKeys(raw.get("extrude"), ex));
			} else {
				pluginDt.remove("extrude");
			}
			ProjectData.setOrRemove(pluginDt, "sid", storeID);
			ProjectData.setOrRemove(pluginDt, "vanilla", vanilla ? Boolean.TRUE : null);
			if(!pluginDt.isEmpty())cube.pluginData = JsonUtil.toJson(pluginDt);
			// CPM+ (G16): "" (the property's default), never null: Blockbench's undo (Property.merge) skips null
			// values, so a null written here could not be put back by Ctrl+Z / a cancelled edit later
			else cube.pluginData = "";
			source = cube.pluginData;
			changed = false;
		}
	}

	/**
	 * CPM+ (G16): true while this object still matches the cube's cpm_data string (Cube.getData() parses again
	 * when it does not, e.g. after Ctrl+Z restored an older string).
	 */
	public boolean isFor(String liveCpmData) {
		return DataCache.sameSource(source, liveCpmData);
	}

	// ------------------------------------------------------------------ G16 extras

	/** CPM+ (G16): plugin-only value of this cube (JSON shapes). Copy; null when absent. */
	public Object getExtra(String key) {
		checkExtraKey(key);
		return JsonUtil.deepCopy(raw.get(key));
	}

	public boolean hasExtra(String key) {
		checkExtraKey(key);
		return raw.containsKey(key);
	}

	/**
	 * CPM+ (G16): sets a plugin-only value (marks the data changed; written by flush()). JSON shapes without null
	 * (older CPM plugins cannot read a null): a nested null is refused, value == null removes the extra.
	 */
	public CubeData putExtra(String key, Object value) {
		checkExtraKey(key);
		if(value == null)return removeExtra(key);
		JsonUtil.checkExtraValue(key, value);
		raw.put(key, JsonUtil.deepCopy(value));
		markDirty();
		return this;
	}

	/** CPM+ (G16): removes a plugin-only value (marks the data changed; gone after flush()) */
	public CubeData removeExtra(String key) {
		checkExtraKey(key);
		if(raw.containsKey(key)) {
			raw.remove(key);
			markDirty();
		}
		return this;
	}

	private static void checkExtraKey(String key) {
		if(key == null || key.isEmpty())throw new IllegalArgumentException("empty extra key");
		if(KNOWN_KEYS.contains(key))throw new IllegalArgumentException("'" + key + "' is a key CubeData writes itself");
	}

	public Extrude getExtrude() {
		return extrude;
	}

	public Extrude setExtrude(boolean en) {
		if(en) {
			if(extrude == null)extrude = new Extrude();
		} else {
			extrude = null;
		}
		markDirty();
		return extrude;
	}

	public CubeData setStoreID(long id) {
		this.storeID = id == 0 ? null : Long.toString(id);
		markDirty();
		return this;
	}

	public long getStoreID() {
		return GroupData.parseId(storeID);
	}

	public CubeData setVanilla(boolean vanilla) {
		this.vanilla = vanilla;
		markDirty();
		return this;
	}

	public boolean isVanilla() {
		return vanilla;
	}

	public class Extrude {
		private int u, v, ts;

		public int getU() {
			return u;
		}

		public Extrude setU(int u) {
			this.u = u;
			markDirty();
			return this;
		}

		public int getV() {
			return v;
		}

		public Extrude setV(int v) {
			this.v = v;
			markDirty();
			return this;
		}

		public int getTs() {
			return ts;
		}

		public Extrude setTs(int ts) {
			this.ts = ts;
			markDirty();
			return this;
		}

		public CubeData up() {
			return CubeData.this;
		}
	}
}
