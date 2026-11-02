package com.tom.cpm.blockbench.util;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.CustomPose;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.project.loaders.AnimationsLoaderV1;

import jsinterop.base.Js;

/**
 * CPM+ (INFRA2, W0C-EXPORTCORE; frozen after W0C): how many 'layer controlled' animations (custom poses and
 * gestures synced to other players through the free skin layers) the model can have. Port of the rule in CPM's
 * ModelPartAnimation constructor / resolveEncID (and LayerEncondingHelper, the experimental exporter): every distinct
 * layer-controlled custom pose and gesture gets the next id 1, 2, 3 ... encoded in the f free layers; CPM's export
 * throws
 * <ul>
 * <li>{@code error.cpm.custom_anims_not_supported} when n &gt; 0 and f &lt; 2,</li>
 * <li>{@code error.cpm.too_many_animations} when an id uses every free layer, i.e. n &gt; 2^f - 2.</li>
 * </ul>
 * This is a FORMAT limit (the model cannot be exported in game at all), not a safety-profile one. Tools that add
 * layer-controlled animations call {@link #checkProject()} and warn; the export runs {@link #check(Editor)} as a
 * built-in ExportCheck (severity BREAKS, key {@link #WARN_KEY}).
 */
public final class LayerCapacity {
	/** WarnEntry key of the export check (W2-WARN-I18N registers its fix on it) */
	public static final String WARN_KEY = "layerCapacity";
	/** CPM's own error keys */
	public static final String ERR_NO_LAYERS = "error.cpm.custom_anims_not_supported";
	public static final String ERR_TOO_MANY = "error.cpm.too_many_animations";

	private LayerCapacity() {
	}

	/** n layer-controlled animations, f free layers, the capacity and CPM's error key (null = fits) */
	public static final class Result {
		public final int count, freeLayers, capacity;
		public final String errorKey;

		Result(int count, int freeLayers) {
			this.count = count;
			this.freeLayers = freeLayers;
			this.capacity = capacity(freeLayers);
			this.errorKey = error(count, freeLayers);
		}

		public boolean ok() {
			return errorKey == null;
		}

		@Override
		public String toString() {
			return "LayerCapacity[n=" + count + ", free=" + freeLayers + ", capacity=" + capacity + (errorKey != null ? ", " + errorKey : "") + "]";
		}
	}

	/** how many layer-controlled animations f free layers can encode: 0 for f &lt; 2, else 2^f - 2 */
	public static int capacity(int freeLayers) {
		if(freeLayers < 2)return 0;
		return (1 << Math.min(freeLayers, 30)) - 2;
	}

	/** CPM's export error for n layer-controlled animations and f free layers, or null when the export works */
	public static String error(int count, int freeLayers) {
		if(count <= 0)return null;
		if(freeLayers < 2)return ERR_NO_LAYERS;
		if(count > capacity(freeLayers))return ERR_TOO_MANY;
		return null;
	}

	public static Result check(int count, int freeLayers) {
		return new Result(count, freeLayers);
	}

	/** exact rule on a converted CPM editor (what the export / the in-game editor sees) */
	public static Result check(Editor e) {
		Set<CustomPose> poses = new HashSet<>();
		Set<String> gestures = new HashSet<>();
		for (EditorAnim ea : e.animations) {
			if(ea.pose instanceof VanillaPose)continue;
			if(ea.pose != null) {
				if(ea.layerControlled && ea.pose instanceof CustomPose)poses.add((CustomPose) ea.pose);
			} else if(!ea.type.isStaged() && !ea.type.isLayer() && ea.layerControlled) {
				gestures.add(AnimationsLoaderV1.encodeTypeInName(ea.getId(), ea.type));
			}
		}
		int free = e.animEnc != null ? e.animEnc.freeLayers.size() : 0;
		return new Result(poses.size() + gestures.size(), free);
	}

	/**
	 * Same count on the open Blockbench project, without converting it: the CPM animations of the project
	 * (custom poses / gestures with 'layer controlled' on, the ones the export would write), the CPM animations kept
	 * in CPM format inside the .bbmodel ('Keep' on import), and the free layers stored in the project.
	 */
	@SuppressWarnings("unchecked")
	public static Result checkProject() {
		Set<String> poses = new HashSet<>();
		Set<String> gestures = new HashSet<>();
		if(Animation.all != null) {
			for (Animation a : Animation.all) {
				if(a == null || !"string".equals(Js.typeof(a.type)))continue;
				Pair<AnimationType, VanillaPose> t = Animation.parseType(a.type);
				if(t == null || t.getKey() == null || !a.layerCtrl)continue;
				if(t.getKey() == AnimationType.CUSTOM_POSE)poses.add(a.name);
				else if(t.getKey() == AnimationType.GESTURE)gestures.add(idOf(a.name));
			}
		}
		int free = 0;
		try {
			ProjectData pd = Project.getData();
			Object anims = pd.animations.get("anims");
			if(anims instanceof Map) {
				for (Map.Entry<String, Object> en : ((Map<String, Object>) anims).entrySet()) {
					if(!(en.getValue() instanceof Map))continue;
					Map<String, Object> d = (Map<String, Object>) en.getValue();
					if(Boolean.FALSE.equals(d.get("layerControlled")))continue;
					Object nm = d.get("name");
					String name = nm != null ? String.valueOf(nm) : "Unnamed";
					String file = en.getKey();
					if(file.startsWith("c_"))poses.add(name);
					else if(file.startsWith("g_") && AnimationsLoaderV1.getType(name) == AnimationType.GESTURE)
						gestures.add(idOf(AnimationsLoaderV1.cleanName(name)));
				}
			}
			Object fl = pd.animations.get("free");
			if(fl instanceof List)free = ((List<Object>) fl).size();
		} catch (Throwable e) {
		}
		return new Result(poses.size() + gestures.size(), free);
	}

	/** button id of a gesture (text before '#', CPM's EditorAnim.getId) */
	private static String idOf(String name) {
		if(name == null)return "";
		int i = name.indexOf('#');
		return i == -1 ? name : name.substring(0, i);
	}
}
