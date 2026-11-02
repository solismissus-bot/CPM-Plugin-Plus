package com.tom.cpm.blockbench.format;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.util.DataCache;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;

/**
 * CPM data of one Blockbench group (group.cpm_data JSON string).
 *
 * CPM+ (G16, preserving flush): the parsed JSON is kept; flush() (only after a change, as before) writes a copy of
 * it with the known keys set or explicitly removed, so keys written by a newer CPM+ survive. Plugin-only data of
 * later features: getExtra/putExtra/removeExtra.
 */
public class GroupData {
	/** CPM+ (G16): keys this class writes itself; extras may not use them */
	public static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"item", "sid", "rscale", "iscale", "locked", "nameColor", "rootName")));

	private final Group group;
	private boolean changed;
	private String itemRenderer;
	// CPM+ : ModelElement fields without a Blockbench counterpart, kept so CPM -> BB -> CPM is lossless.
	// All optional; missing in old files.
	private String storeID;// stored as string: 63 bit ids don't survive a JS double
	private Vec3f renderScale;// ModelElement.scale ('rscale')
	private Vec3f itemScale;// ModelElement.meshScale of item renderer elements
	private boolean locked;
	private int nameColor;
	private String rootName;// ModelElement.name of root parts (renamed roots)
	/** CPM+ (G16): the parsed cpm_data as read (key order kept), extras live here too */
	private final Map<String, Object> raw;
	/** CPM+ (G16): the cpm_data string this object was parsed from or last flushed to (cache check) */
	private String source;

	public GroupData(Group group) {
		this.group = group;
		this.source = group.pluginData;
		Map<String, Object> parsed = null;
		if(group.pluginData != null && !group.pluginData.isEmpty()) {
			JsonMap s0 = JsonUtil.fromJson(group.pluginData);
			parsed = s0.asMap();
			JsonMap s = new JsonMapImpl(JsonUtil.copyMap(parsed));
			if(s.containsKey("item")) {
				itemRenderer = s.getString("item");
			}
			// CPM+
			if(s.containsKey("sid"))storeID = String.valueOf(s.get("sid"));
			if(s.containsKey("rscale"))renderScale = new Vec3f(s.getMap("rscale"), new Vec3f(1, 1, 1));
			if(s.containsKey("iscale"))itemScale = new Vec3f(s.getMap("iscale"), new Vec3f(1, 1, 1));
			locked = s.getBoolean("locked", false);
			nameColor = s.getInt("nameColor", 0);
			if(s.containsKey("rootName"))rootName = s.getString("rootName");
		}
		raw = parsed != null ? parsed : new LinkedHashMap<>();
	}

	public void flush() {
		if(changed) {
			// CPM+ (G16): start from the data as read, set or REMOVE every known key
			Map<String, Object> pluginDt = JsonUtil.copyMap(raw);
			ProjectData.setOrRemove(pluginDt, "item", itemRenderer);
			// CPM+
			ProjectData.setOrRemove(pluginDt, "sid", storeID);
			ProjectData.setOrRemove(pluginDt, "rscale", renderScale != null ? JsonUtil.keepUnknownKeys(raw.get("rscale"), renderScale.toMap()) : null);
			ProjectData.setOrRemove(pluginDt, "iscale", itemScale != null ? JsonUtil.keepUnknownKeys(raw.get("iscale"), itemScale.toMap()) : null);
			ProjectData.setOrRemove(pluginDt, "locked", locked ? Boolean.TRUE : null);
			ProjectData.setOrRemove(pluginDt, "nameColor", nameColor != 0 ? (Object) Integer.valueOf(nameColor) : null);
			ProjectData.setOrRemove(pluginDt, "rootName", rootName != null && !rootName.isEmpty() ? rootName : null);
			if(!pluginDt.isEmpty())group.pluginData = JsonUtil.toJson(pluginDt);
			// CPM+ (G16): "" (the property's default), never null: Blockbench's undo (Property.merge) skips null
			// values, so a null written here could not be put back by Ctrl+Z / a cancelled edit later
			else group.pluginData = "";
			source = group.pluginData;
			changed = false;
		}
	}

	/**
	 * CPM+ (G16): true while this object still matches the group's cpm_data string (Group.getData() parses
	 * again when it does not, e.g. after Ctrl+Z restored an older string).
	 */
	public boolean isFor(String liveCpmData) {
		return DataCache.sameSource(source, liveCpmData);
	}

	// ------------------------------------------------------------------ G16 extras

	/** CPM+ (G16): plugin-only value of this group (JSON shapes). Copy; null when absent. */
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
	public GroupData putExtra(String key, Object value) {
		checkExtraKey(key);
		if(value == null)return removeExtra(key);
		JsonUtil.checkExtraValue(key, value);
		raw.put(key, JsonUtil.deepCopy(value));
		markDirty();
		return this;
	}

	/** CPM+ (G16): removes a plugin-only value (marks the data changed; gone after flush()) */
	public GroupData removeExtra(String key) {
		checkExtraKey(key);
		if(raw.containsKey(key)) {
			raw.remove(key);
			markDirty();
		}
		return this;
	}

	private static void checkExtraKey(String key) {
		if(key == null || key.isEmpty())throw new IllegalArgumentException("empty extra key");
		if(KNOWN_KEYS.contains(key))throw new IllegalArgumentException("'" + key + "' is a key GroupData writes itself");
	}

	public GroupData setItemRenderer(String itemRenderer) {
		this.itemRenderer = itemRenderer;
		markDirty();
		return this;
	}

	public String getItemRenderer() {
		return itemRenderer;
	}

	// CPM+ : accessors for the round-trip fields

	public GroupData setStoreID(long id) {
		this.storeID = id == 0 ? null : Long.toString(id);
		markDirty();
		return this;
	}

	/** @return the stored CPM storeID or 0 */
	public long getStoreID() {
		return parseId(storeID);
	}

	public static long parseId(String s) {
		if(s == null || s.isEmpty())return 0;
		try {
			if(s.indexOf('.') != -1 || s.indexOf('E') != -1 || s.indexOf('e') != -1)
				return (long) Double.parseDouble(s);
			return Long.parseLong(s);
		} catch (Exception e) {
			return 0;
		}
	}

	public GroupData setRenderScale(Vec3f v) {
		this.renderScale = v == null || (v.x == 1 && v.y == 1 && v.z == 1) ? null : new Vec3f(v);
		markDirty();
		return this;
	}

	public Vec3f getRenderScale() {
		return renderScale;
	}

	public GroupData setItemScale(Vec3f v) {
		this.itemScale = v == null || (v.x == 1 && v.y == 1 && v.z == 1) ? null : new Vec3f(v);
		markDirty();
		return this;
	}

	public Vec3f getItemScale() {
		return itemScale;
	}

	public GroupData setLocked(boolean locked) {
		this.locked = locked;
		markDirty();
		return this;
	}

	public boolean isLocked() {
		return locked;
	}

	public GroupData setNameColor(int nameColor) {
		this.nameColor = nameColor;
		markDirty();
		return this;
	}

	public int getNameColor() {
		return nameColor;
	}

	public GroupData setRootName(String rootName) {
		this.rootName = rootName;
		markDirty();
		return this;
	}

	public String getRootName() {
		return rootName;
	}

	private void markDirty() {
		changed = true;
	}
}
