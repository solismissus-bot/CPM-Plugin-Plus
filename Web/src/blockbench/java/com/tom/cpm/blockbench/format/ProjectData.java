package com.tom.cpm.blockbench.format;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.DataCache;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;
import com.tom.cpm.shared.editor.util.ModelDescription;
import com.tom.cpm.shared.editor.util.ModelDescription.CopyProtection;
import com.tom.cpm.shared.model.PartPosition;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.util.ScalingOptions;

/**
 * Project level CPM data, stored as the JSON string Project.cpm_data inside the .bbmodel.
 *
 * CPM+ (G16, preserving flush): the parsed JSON is kept ({@link #raw}). flush() starts from a copy of it and only
 * overwrites / explicitly removes the keys this version knows, so keys written by a newer CPM+ survive a save
 * made with this version. Known maps ('desc', 'scaling', 'tex' sheets, 'left_hand'/'right_hand', each stored
 * animation entry in 'anims') keep the unknown keys of the same map. A known key that is unset is REMOVED (never
 * resurrected from the old file). Plugin-only data of later features goes through getExtra/putExtra/removeExtra.
 */
public class ProjectData {
	/** CPM+ (G16): top-level keys this class reads and writes itself; extras may not use them */
	public static final Set<String> KNOWN_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"anims", "scaling", "left_hand", "right_hand", "modelId", "removeArmorOffset", "tex", "desc",
			"skinType", "customSkinType", "raw", "grid", "wearerSkin", "lastExport", "cpmplus")));
	/** CPM+ (G16): keys of 'desc' this class writes itself */
	public static final Set<String> KNOWN_DESC_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"name", "desc", "zoom", "look", "pos", "copyProt")));

	public Map<ScalingOptions, Float> scalingOpt = new HashMap<>();
	public Vec3f renderPos, renderRot, renderScl;
	public PartPosition leftHandPos, rightHandPos;
	public String modelId;
	public boolean removeArmorOffset = true;
	public Map<TextureSheetType, Map<String, Object>> animTex = new HashMap<>();
	public ModelDescription description;
	public Map<String, Object> animations = new HashMap<>();

	// CPM+ : extra project data that the official plugin dropped. All keys are optional, old
	// .bbmodel files simply don't have them (defaults = old behaviour).
	/** CPM+ : 'Scaling' checkbox of the CPM editor. null = unknown (old file) -> inferred on export */
	public Boolean scalingEnabled;
	/** CPM+ : skin type (default/slim) and whether the model forces it (config.json skinType) */
	public String skinType;
	public boolean customSkinType;
	/** CPM+ : raw .cpmproject entries passed through unchanged (desc_icon.png, tags/*.json, templates.json, template_settings.json). path -> base64 */
	public Map<String, String> rawFiles = new HashMap<>();
	/** CPM+ : ETextures.customGridSize per sheet */
	public Map<TextureSheetType, Boolean> customGrid = new HashMap<>();
	/** CPM+ : the skin texture was the wearer's own skin (not edited in CPM): hash of the imported texture source */
	public String wearerSkinHash;
	/** CPM+ : quick re-export target. In memory only (the ProjectData object is cached on the open
	 *  project), never saved into the .bbmodel so shared files don't expose local paths. */
	public String lastExportPath;

	/** CPM+ (G16): the parsed cpm_data as read (key order kept), extras live here too */
	private final Map<String, Object> raw;
	/** CPM+ (G16): the cpm_data string this object was parsed from or last flushed to (cache check) */
	private String source;

	public ProjectData(boolean init) {
		this.source = init ? Project.pluginData : null;
		Map<String, Object> parsed = null;
		if(init && Project.pluginData != null && !Project.pluginData.isEmpty()) {
			JsonMap s = JsonUtil.fromJson(Project.pluginData);
			parsed = s.asMap();
			// CPM+ (G16): the fields below get deep copies, 'raw' keeps the file as read
			s = new JsonMapImpl(JsonUtil.copyMap(parsed));

			if(s.containsKey("anims")) {
				Map<String, Object> an = JsonUtil.asMap(s.get("anims"));
				if(an != null)animations = an;
			}

			if(s.containsKey("scaling")) {
				JsonMap scl = s.getMap("scaling");

				for(ScalingOptions opt : ScalingOptions.VALUES) {
					float v = scl.getFloat(opt.name().toLowerCase(Locale.ROOT), 0);
					if(v != 0)
						scalingOpt.put(opt, v);
				}

				renderPos = new Vec3f(scl.getMap("render_position"), new Vec3f());
				renderRot = new Vec3f(scl.getMap("render_rotation"), new Vec3f());
				renderScl = new Vec3f(scl.getMap("render_scale"), new Vec3f());
				// CPM+
				if(scl.containsKey("enabled"))scalingEnabled = scl.getBoolean("enabled", false);
			}

			leftHandPos = loadPartPos(s, "left_hand");
			rightHandPos = loadPartPos(s, "right_hand");
			if(s.containsKey("modelId")) {
				modelId = s.getString("modelId");
			}
			// CPM+ : was written but never read back (reset to true after reopening the .bbmodel)
			removeArmorOffset = s.getBoolean("removeArmorOffset", true);

			if(s.containsKey("tex")) {
				JsonMap tex = s.getMap("tex");
				for(TextureSheetType tx : TextureSheetType.VALUES) {
					if(tx.editable) {
						String name = tx.name().toLowerCase(Locale.ROOT);
						if (tex.containsKey(name)) {
							animTex.put(tx, tex.getMap(name).asMap());
						}
					}
				}
			}

			if(s.containsKey("desc")) {
				JsonMap desc = s.getMap("desc");
				description = new ModelDescription();
				description.name = desc.getString("name");
				description.desc = desc.getString("desc");
				description.camera.camDist = desc.getFloat("zoom");
				description.camera.look = new Vec3f(desc.getMap("look"), description.camera.look);
				description.camera.position = new Vec3f(desc.getMap("pos"), description.camera.position);
				description.copyProtection = CopyProtection.lookup(desc.getString("copyProt", "normal"));
				if(description.copyProtection == null)description.copyProtection = CopyProtection.NORMAL;
			}

			// CPM+
			if(s.containsKey("skinType")) {
				skinType = s.getString("skinType");
				customSkinType = s.getBoolean("customSkinType", false);
			}
			if(s.containsKey("raw")) {
				JsonMap raw = s.getMap("raw");
				raw.asMap().forEach((k, v) -> {
					if(v instanceof String)rawFiles.put(k, (String) v);
				});
			}
			if(s.containsKey("grid")) {
				JsonMap grid = s.getMap("grid");
				for(TextureSheetType tx : TextureSheetType.VALUES) {
					String name = tx.name().toLowerCase(Locale.ROOT);
					if(grid.containsKey(name))customGrid.put(tx, grid.getBoolean(name, false));
				}
			}
			if(s.containsKey("wearerSkin"))wearerSkinHash = s.getString("wearerSkin");
			// CPM+ : 'lastExport' (absolute local path, written by an earlier CPM+ build) is no longer read
			// from or written to the .bbmodel; the re-export target is session-local (see lastExportPath)
		}
		raw = parsed != null ? parsed : new LinkedHashMap<>();
	}

	public void flush() {
		// CPM+ (G16): start from the file as read, then set or REMOVE every known key
		Map<String, Object> pluginDt = JsonUtil.copyMap(raw);

		if(!animations.isEmpty())pluginDt.put("anims", mergeAnims(raw.get("anims"), animations));
		else pluginDt.remove("anims");
		{
			Map<String, Object> scl = JsonUtil.copyMap(raw.get("scaling"));
			for(ScalingOptions opt : ScalingOptions.VALUES) {
				String k = opt.name().toLowerCase(Locale.ROOT);
				if(scalingOpt.containsKey(opt) && scalingOpt.get(opt) != null)
					scl.put(k, scalingOpt.get(opt));
				// G16: unset in memory = 0 or absent in the file (the reader skips 0): a stored 0 means the
				// same, keep it so an unchanged file is written back byte for byte
				else if(!(scl.get(k) instanceof Number && ((Number) scl.get(k)).doubleValue() == 0))scl.remove(k);
			}
			// (nested maps keep their unknown keys too: JsonUtil.keepUnknownKeys is recursive)
			setOrRemove(scl, "render_position", renderPos != null ? JsonUtil.keepUnknownKeys(scl.get("render_position"), renderPos.toMap()) : null);
			setOrRemove(scl, "render_rotation", renderRot != null ? JsonUtil.keepUnknownKeys(scl.get("render_rotation"), renderRot.toMap()) : null);
			setOrRemove(scl, "render_scale", renderScl != null ? JsonUtil.keepUnknownKeys(scl.get("render_scale"), renderScl.toMap()) : null);
			setOrRemove(scl, "enabled", scalingEnabled);// CPM+

			if(!scl.isEmpty())pluginDt.put("scaling", scl);
			else pluginDt.remove("scaling");
		}
		putPartPos(leftHandPos, pluginDt, "left_hand");
		putPartPos(rightHandPos, pluginDt, "right_hand");
		setOrRemove(pluginDt, "modelId", modelId);
		// CPM+ : always written so the value is unambiguous (old readers only look for 'false')
		pluginDt.put("removeArmorOffset", removeArmorOffset);

		{
			Map<String, Object> ant = JsonUtil.copyMap(raw.get("tex"));
			Map<String, Object> rawTex = JsonUtil.asMap(raw.get("tex"));
			for(TextureSheetType tx : TextureSheetType.VALUES) {
				if(tx.editable) {
					String name = tx.name().toLowerCase(Locale.ROOT);
					if(animTex.containsKey(tx) && animTex.get(tx) != null) {
						ant.put(name, JsonUtil.keepUnknownKeys(rawTex != null ? rawTex.get(name) : null, animTex.get(tx)));
					} else ant.remove(name);
				}
			}
			// CPM+ : 'hasTex' was never set to true, animated textures were never saved to the .bbmodel
			if(!ant.isEmpty())pluginDt.put("tex", ant);
			else pluginDt.remove("tex");
		}

		if(this.description != null) {
			Map<String, Object> desc = JsonUtil.copyMap(raw.get("desc"));
			setOrRemove(desc, "name", this.description.name);
			setOrRemove(desc, "desc", this.description.desc);
			desc.put("zoom", this.description.camera.camDist);
			desc.put("look", JsonUtil.keepUnknownKeys(desc.get("look"), this.description.camera.look.toMap()));
			desc.put("pos", JsonUtil.keepUnknownKeys(desc.get("pos"), this.description.camera.position.toMap()));
			desc.put("copyProt", (this.description.copyProtection != null ? this.description.copyProtection : CopyProtection.NORMAL).name().toLowerCase(Locale.ROOT));
			pluginDt.put("desc", desc);
		} else {
			// (desc extras need a description: they are dropped with it)
			pluginDt.remove("desc");
		}

		// CPM+
		if(skinType != null) {
			pluginDt.put("skinType", skinType);
			pluginDt.put("customSkinType", customSkinType);
		} else {
			pluginDt.remove("skinType");
			pluginDt.remove("customSkinType");
		}
		{
			// stored .cpmproject files: the in-memory map is authoritative (a deleted entry stays deleted);
			// non-string values in the old map are not ours and are kept
			Map<String, Object> rf = new LinkedHashMap<>();
			Map<String, Object> oldRaw = JsonUtil.asMap(raw.get("raw"));
			if(oldRaw != null)oldRaw.forEach((k, v) -> {
				if(!(v instanceof String))rf.put(k, JsonUtil.deepCopy(v));
			});
			rf.putAll(rawFiles);
			if(!rf.isEmpty())pluginDt.put("raw", rf);
			else pluginDt.remove("raw");
		}
		{
			Map<String, Object> grid = JsonUtil.copyMap(raw.get("grid"));
			for(TextureSheetType tx : TextureSheetType.VALUES) {
				String name = tx.name().toLowerCase(Locale.ROOT);
				if(customGrid.containsKey(tx) && customGrid.get(tx) != null)grid.put(name, customGrid.get(tx));
				else grid.remove(name);
			}
			if(!grid.isEmpty())pluginDt.put("grid", grid);
			else pluginDt.remove("grid");
		}
		setOrRemove(pluginDt, "wearerSkin", wearerSkinHash);
		// CPM+ : absolute path written by an early CPM+ build: never kept in the file
		pluginDt.remove("lastExport");

		if(!pluginDt.isEmpty())Project.pluginData = JsonUtil.toJson(pluginDt);
		else Project.pluginData = null;
		source = Project.pluginData;
	}

	/**
	 * CPM+ (G16): true while this object still matches the project's cpm_data string (Project.getData() parses
	 * again when it does not, e.g. after a restored snapshot).
	 */
	public boolean isFor(String liveCpmData) {
		return DataCache.sameSource(source, liveCpmData);
	}

	// ------------------------------------------------------------------ G16 extras

	/**
	 * CPM+ (G16): plugin-only value stored at the top level of the project cpm_data (JSON shapes: Map, List,
	 * Double, String, Boolean - never null). Returns a copy; null when absent. Never a key of {@link #KNOWN_KEYS}.
	 */
	public Object getExtra(String key) {
		checkExtraKey(key, KNOWN_KEYS);
		return JsonUtil.deepCopy(raw.get(key));
	}

	public boolean hasExtra(String key) {
		checkExtraKey(key, KNOWN_KEYS);
		return raw.containsKey(key);
	}

	/**
	 * CPM+ (G16): sets a plugin-only top-level value (written on the next flush). The official plugin and CPM+
	 * 0.7 crash on a JSON null in cpm_data, so a nested null is refused (IllegalArgumentException) and
	 * value == null removes the extra.
	 */
	public ProjectData putExtra(String key, Object value) {
		checkExtraKey(key, KNOWN_KEYS);
		if(value == null)return removeExtra(key);
		JsonUtil.checkExtraValue(key, value);// validates the value shape now instead of at flush time
		raw.put(key, JsonUtil.deepCopy(value));
		return this;
	}

	/** CPM+ (G16): removes a plugin-only top-level value (gone after the next flush) */
	public ProjectData removeExtra(String key) {
		checkExtraKey(key, KNOWN_KEYS);
		raw.remove(key);
		return this;
	}

	/** CPM+ (G16): plugin-only value inside 'desc' (model description). Copy; null when absent. */
	public Object getDescExtra(String key) {
		checkExtraKey(key, KNOWN_DESC_KEYS);
		Map<String, Object> d = JsonUtil.asMap(raw.get("desc"));
		return d != null ? JsonUtil.deepCopy(d.get(key)) : null;
	}

	/**
	 * CPM+ (G16): sets a plugin-only value inside 'desc'. Written only while {@link #description} is not null
	 * (the description block is dropped as a whole when the model has no description): create it first.
	 * No null (see {@link #putExtra}): a nested null is refused, value == null removes the extra.
	 */
	public ProjectData putDescExtra(String key, Object value) {
		checkExtraKey(key, KNOWN_DESC_KEYS);
		if(value == null)return removeDescExtra(key);
		JsonUtil.checkExtraValue(key, value);
		Map<String, Object> d = JsonUtil.asMap(raw.get("desc"));
		if(d == null) {
			d = new LinkedHashMap<>();
			raw.put("desc", d);
		}
		d.put(key, JsonUtil.deepCopy(value));
		return this;
	}

	public ProjectData removeDescExtra(String key) {
		checkExtraKey(key, KNOWN_DESC_KEYS);
		Map<String, Object> d = JsonUtil.asMap(raw.get("desc"));
		if(d != null)d.remove(key);
		return this;
	}

	/** CPM+ (G16): the version stamp of the file as read ('cpmplus' map), null for legacy files */
	public Map<String, Object> getStamp() {
		return JsonUtil.asMap(JsonUtil.deepCopy(raw.get("cpmplus")));
	}

	private static void checkExtraKey(String key, Set<String> known) {
		if(key == null || key.isEmpty())throw new IllegalArgumentException("empty extra key");
		if(known.contains(key))throw new IllegalArgumentException("'" + key + "' is a key ProjectData writes itself");
	}

	static void setOrRemove(Map<String, Object> map, String key, Object value) {
		if(value != null)map.put(key, value);
		else map.remove(key);
	}

	/**
	 * CPM+ (G16): 'anims' (stored, not imported CPM animations + anim_enc data). The in-memory map decides which
	 * keys and entries exist (a deleted entry is never resurrected); each entry of 'anims.anims' that still exists
	 * keeps the keys of the stored entry it does not have (written by a newer CPM+).
	 */
	private static Map<String, Object> mergeAnims(Object rawAnims, Map<String, Object> cur) {
		Map<String, Object> rawMap = JsonUtil.asMap(rawAnims);
		Map<String, Object> out = new LinkedHashMap<>();
		cur.forEach((k, v) -> {
			Map<String, Object> entries = JsonUtil.asMap(v);
			if("anims".equals(k) && entries != null) {
				Map<String, Object> rawEntries = rawMap != null ? JsonUtil.asMap(rawMap.get(k)) : null;
				Map<String, Object> o = new LinkedHashMap<>();
				entries.forEach((name, data) -> {
					Map<String, Object> d = JsonUtil.asMap(data);
					if(d != null)o.put(name, JsonUtil.keepUnknownKeys(rawEntries != null ? rawEntries.get(name) : null, d));
					else o.put(name, JsonUtil.deepCopy(data));
				});
				out.put(k, o);
			} else {
				out.put(k, JsonUtil.deepCopy(v));
			}
		});
		return out;
	}

	/** CPM+ : stable string hash (used to detect an unedited wearer skin) */
	public static String hash(String s) {
		if(s == null)return null;
		long h = 1125899906842597L;
		for (int i = 0; i < s.length(); i++) {
			h = 31 * h + s.charAt(i);
		}
		return Long.toHexString(h) + ":" + s.length();
	}

	private void putPartPos(PartPosition pos, Map<String, Object> fpHand, String name) {
		if(pos == null) {
			fpHand.remove(name);
			return;
		}
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("position", pos.getRPos().toMap());
		map.put("rotation", pos.getRRotation().toMap3());
		map.put("scale", pos.getRScale().toMap());
		fpHand.put(name, JsonUtil.keepUnknownKeys(raw.get(name), map));
	}

	private static PartPosition loadPartPos(JsonMap fpHand, String name) {
		PartPosition p = new PartPosition();
		JsonMap map = fpHand.getMap(name);
		if(map != null) {
			Vec3f pos = new Vec3f(map.getMap("position"), new Vec3f());
			Vec3f rotation = new Vec3f(map.getMap("rotation"), new Vec3f());
			Vec3f scale = new Vec3f(map.getMap("scale"), new Vec3f());
			p.setRenderScale(pos, new Rotation(rotation, true), scale);
		}
		return p;
	}
}
