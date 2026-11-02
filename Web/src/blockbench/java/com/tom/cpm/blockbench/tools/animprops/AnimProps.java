package com.tom.cpm.blockbench.tools.animprops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.KeyframeDataPoint;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.JsArrayE;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (lite unit animprops: F15 + F18): the logic behind the CPM animation dialog's advanced options and quality.
 * <ul>
 * <li>F15: priority as Normal (0) / Acima das outras (10) / Personalizado, byte limits (-128..127) for priority and
 * order (CPM writes both as a byte), slider steps 1..255 (CPM writes maxValue as an unsigned byte).</li>
 * <li>F18: quality presets 10/20/30/60 + Personalizado, 'Aplicar a todas' (one undo; animations with a stored CPM
 * grid only after asking), 'Calcular exato' (the real conversion, no file written) and the short-visibility check.</li>
 * </ul>
 * The quality is Blockbench's 'snapping': v0.7+ exports keyframes directly; the rate only matters when the export has
 * to recalculate the curve (bezier, formulas, keys off one uniform grid).
 */
public final class AnimProps {
	public static final String Q_CUSTOM = "custom";
	public static final String[] Q_PRESETS = {"10", "20", "30", "60"};
	public static final String P_NORMAL = "normal", P_ABOVE = "above", P_CUSTOM = "custom";
	public static final int PRIO_ABOVE = 10;
	/** CPM writes the frame count of an animation as one byte */
	public static final int MAX_FRAMES = 255;
	private static final String VISIBILITY = CPMCodec.VISIBILITY;
	/** the channels the export reads (BlockbenchExport KEY_CHANNELS + visibility) */
	private static final String[] CHANNELS = {"position", "rotation", "scale", CPMCodec.COLOR, CPMCodec.VISIBILITY};

	private AnimProps() {
	}

	// ------------------------------------------------------------------ F15

	public static boolean inByte(int v) {
		return v >= -128 && v <= 127;
	}

	public static int clampByte(int v) {
		return Math.max(-128, Math.min(127, v));
	}

	/** slider steps: CPM keeps 1..255 (unsigned byte, 0 would mean 255) */
	public static int clampSteps(int v) {
		return Math.max(1, Math.min(255, v));
	}

	public static String priorityOption(int p) {
		if(p == 0)return P_NORMAL;
		if(p == PRIO_ABOVE)return P_ABOVE;
		return P_CUSTOM;
	}

	public static int priorityValue(String sel, int custom) {
		if(P_NORMAL.equals(sel))return 0;
		if(P_ABOVE.equals(sel))return PRIO_ABOVE;
		return custom;
	}

	/** true when one of the options behind 'Mostrar opções avançadas' is not at its default (the section opens by itself) */
	public static boolean hasAdvancedValues(Animation a) {
		if(a == null)return false;
		InterpolatorType it = a.getCpmIntType();
		boolean trig = it == InterpolatorType.TRIG_LOOP || it == InterpolatorType.TRIG_SINGLE;
		return a.getPriority() != 0 || a.isMustFinish() || a.isAnimHidden() || a.getMaxValue() != 100 || !a.isInterpolateVal() || trig;
	}

	/** first number of a form value (Blockbench vector rows give an array, number rows a number) */
	public static int num0(Object v, int def) {
		if(v == null)return def;
		Object x = v;
		if(JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			if(a.length == 0)return def;
			x = a.getAt(0);
		}
		if(x == null)return def;
		double d;
		if("number".equals(Js.typeof(x)))d = Js.asDouble(x);
		else {
			try {
				d = Double.parseDouble(String.valueOf(x).trim());
			} catch (Exception e) {
				return def;
			}
		}
		if(Double.isNaN(d) || Double.isInfinite(d))return def;
		return (int) Math.round(d);
	}

	// ------------------------------------------------------------------ F18

	/** the select value that shows this snapping (an imported 24 shows 'Personalizado') */
	public static String qualityOption(int snapping) {
		String s = Integer.toString(snapping);
		for (String p : Q_PRESETS)if(p.equals(s))return p;
		return Q_CUSTOM;
	}

	/** quality (frames per second) chosen in the dialog: the preset, or the custom number (1..500) */
	public static int quality(String sel, int custom) {
		int v;
		if(sel == null || Q_CUSTOM.equals(sel))v = custom;
		else {
			try {
				v = Integer.parseInt(sel);
			} catch (NumberFormatException e) {
				v = custom;
			}
		}
		return Math.max(1, Math.min(500, v));
	}

	private static String familyOf(String interp) {
		if(interp == null)return "linear";
		switch (interp) {
		case "linear":
			return "linear";
		case "catmullrom":
			return "catmullrom";
		case "step":
			return "step";
		default:
			return null;// bezier and anything new: resampled
		}
	}

	private static boolean onGrid(Set<Double> times, double h, double len) {
		if(!(h > 0))return false;
		double eps = Math.min(1.5e-3, h * 0.05);
		for (double t : times) {
			if(t > len + eps)return false;
			double q = t / h;
			if(Math.abs(q - Math.round(q)) * h > eps)return false;
		}
		return true;
	}

	private static boolean isFormula(KeyframeDataPoint dp) {
		for (String ax : new String[] {"x", "y", "z"}) {
			Object v = dp.getRaw(ax);
			if(v != null && "string".equals(Js.typeof(v))) {
				String s = ((String) v).trim();
				if(s.isEmpty())continue;
				try {
					Double.parseDouble(s);
				} catch (NumberFormatException e) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Same decision as the export (BlockbenchExport.exportFrames): bezier / mixed interpolation / formula keys, or key
	 * times off every uniform grid k*L/M (M up to max(ceil(L*fps), 2*keys)) and off the stored CPM grid -> the export
	 * recalculates the curve at this quality.
	 */
	public static boolean willResample(Animation a, int fps) {
		if(a == null || a.animators == null)return false;
		Set<Double> times = new HashSet<>();
		Map<String, String> fam = new HashMap<>();
		Map<String, Integer> count = new HashMap<>();
		boolean[] special = {false};
		a.animators.forEach(k -> {
			GeneralAnimator ga = a.animators.get(k);
			if(!(ga instanceof BoneAnimator))return;
			BoneAnimator ba = (BoneAnimator) ga;
			for (String ch : CHANNELS) {
				JsArrayE<Keyframe> arr = ba.channel(ch);
				if(arr == null || arr.length == 0 || ba.isMuted(ch))continue;
				boolean visCh = VISIBILITY.equals(ch);
				for (Keyframe kf : arr.array()) {
					times.add((double) kf.time);
					if(visCh)continue;
					if(kf.data_points != null)for (KeyframeDataPoint dp : kf.data_points.array())if(isFormula(dp))special[0] = true;
					String key = k + "/" + ch;
					count.merge(key, 1, Integer::sum);
					String f = familyOf(kf.interpolation);
					if(f == null) {
						fam.put(key + "#bezier", "bezier");
						continue;
					}
					fam.putIfAbsent(key, f);
					if(!f.equals(fam.get(key)))fam.put(key + "#mixed", "mixed");
				}
			}
		});
		if(special[0])return true;
		// interpolation only matters on channels with 2+ keys; every channel must share one family
		String family = null;
		for (Map.Entry<String, String> e : fam.entrySet()) {
			String key = e.getKey();
			int hash = key.indexOf('#');
			String base = hash >= 0 ? key.substring(0, hash) : key;
			Integer c = count.get(base);
			if(c == null || c < 2)continue;
			if(hash >= 0)return true;
			if(family == null)family = e.getValue();
			else if(!family.equals(e.getValue()))return true;
		}
		double len = a.length;
		for (double t : times)len = Math.max(len, t);
		if(len < 1e-4 || times.size() < 2)return false;
		InterpolatorType stored = a.getCpmIntType();
		int sN = a.getCpmFrames();
		if(stored != null && sN >= 1) {
			if(onGrid(times, len / sN, len))return false;
			if(sN > 1 && onGrid(times, len / (sN - 1), len))return false;
		}
		int maxM = Math.min(1000, Math.max((int) Math.ceil(len * fps - 1e-3), 2 * times.size()));
		for (int m = 1; m <= maxM; m++)if(onGrid(times, len / m, len))return false;
		return true;
	}

	/** frames the export makes of a recalculated animation at fps (upper bound), or -1 when it keeps the keyframes */
	public static int predictFrames(Animation a, int fps) {
		if(a == null || !willResample(a, fps))return -1;
		double len = Math.max(0, a.length);
		return Math.max(1, (int) Math.ceil(len * fps - 1e-3)) + 1;
	}

	/**
	 * true when the export recalculates this animation AND a show/hide state lasts less than one frame at fps (it may
	 * vanish in game)
	 */
	public static boolean shortVisibility(Animation a, int fps) {
		if(a == null || a.animators == null || fps <= 0)return false;
		if(!willResample(a, fps))return false;
		double frame = 1.0 / fps;
		boolean[] shortSeg = {false};
		a.animators.forEach(k -> {
			GeneralAnimator ga = a.animators.get(k);
			if(!(ga instanceof BoneAnimator))return;
			BoneAnimator ba = (BoneAnimator) ga;
			if(!ba.hasVisible())return;
			List<Keyframe> keys = new ArrayList<>();
			for (Keyframe kf : ba.visible.array())keys.add(kf);
			keys.sort((x, y) -> Float.compare(x.time, y.time));
			for (int i = 0; i + 1 < keys.size(); i++) {
				boolean v = vis(keys.get(i));
				// a state starts at key i (the first key, or a change) and lasts until the next key with another state
				if(i > 0 && vis(keys.get(i - 1)) == v)continue;
				int j = i + 1;
				while(j < keys.size() && vis(keys.get(j)) == v)j++;
				if(j < keys.size() && keys.get(j).time - keys.get(i).time < frame - 1e-6)shortSeg[0] = true;
			}
		});
		return shortSeg[0];
	}

	private static boolean vis(Keyframe k) {
		return k.data_points != null && k.data_points.length > 0 && k.data_points.getAt(0).getVisible();
	}

	/** 'Aplicar a todas': the quality on every animation without a stored CPM grid; asks before touching imported ones */
	public static void applyQualityToAll(int q) {
		List<Animation> own = new ArrayList<>(), imported = new ArrayList<>();
		if(Animation.all != null)for (Animation a : Animation.all) {
			if(a == null)continue;
			if(a.getCpmIntType() == null)own.add(a);
			else imported.add(a);
		}
		if(imported.isEmpty()) {
			setQuality(own, q);
			return;
		}
		PlusDialogs.messageBox("cpmplus.animprops.applyAll.title", I18n.format("cpmplus.animprops.applyAll.ask", String.valueOf(imported.size()), String.valueOf(q)),
				new String[] {"cpmplus.animprops.applyAll.onlyMine", "cpmplus.animprops.applyAll.all", "cpmplus.animprops.btn.cancel"}, i -> {
					if(i == 0)setQuality(own, q);
					else if(i == 1) {
						List<Animation> l = new ArrayList<>(own);
						l.addAll(imported);
						setQuality(l, q);
					}
				});
	}

	/** one undo entry; animations that would pass 255 frames at that quality are skipped and listed */
	public static int setQuality(List<Animation> list, int q) {
		List<Animation> ok = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		for (Animation a : list) {
			if(a.snapping == q)continue;
			if(predictFrames(a, q) > MAX_FRAMES)skipped.add(a.name);
			else ok.add(a);
		}
		if(!ok.isEmpty())PlusUndo.editAnimations(ok, I18n.get("cpmplus.animprops.applyAll.undo"), () -> {
			for (Animation a : ok)a.snapping = q;
		});
		String msg = ok.isEmpty() && skipped.isEmpty() ? I18n.format("cpmplus.animprops.applyAll.none", String.valueOf(q)) :
			I18n.format("cpmplus.animprops.applyAll.done", String.valueOf(ok.size()), String.valueOf(q));
		if(!skipped.isEmpty())msg += " " + I18n.format("cpmplus.animprops.applyAll.skipped", String.join(", ", skipped));
		if(!ok.isEmpty())PlusDialogs.done(msg);
		else PlusDialogs.quick(msg, 5000);
		JsPropertyMap<Object> res = JsPropertyMap.of();
		res.set("changed", (double) ok.size());
		res.set("skipped", (double) skipped.size());
		res.set("quality", (double) q);
		PlusTestHooks.setLast("animprops.applyAll", res);
		return ok.size();
	}

	/**
	 * 'Calcular exato': the real conversion at quality q (no file written, the animation's snapping is put back), what
	 * the export makes of this animation: frames, CPM interpolator, parts and size (8 + 20 x parts bytes per frame, the
	 * same formula as the export's size estimate).
	 */
	public static void estimate(Animation a, int q) {
		if(a == null) {
			PlusDialogs.block(I18n.get("cpmplus.animprops.estimate.saveFirst"));
			return;
		}
		int old = a.snapping;
		a.snapping = q;
		try {
			ProjectConvert.convertHeadless().then(h -> {
				a.snapping = old;
				List<EditorAnim> eas = h.exp.getExportedAnims(a.uuid);
				List<String> lines = new ArrayList<>();
				JsArray<Object> json = new JsArray<>();
				if(eas.isEmpty())lines.add(I18n.get("cpmplus.animprops.estimate.none"));
				for (EditorAnim ea : eas) {
					int n = ea.getFrames().size();
					Set<ModelElement> comps = new HashSet<>();
					long bytes = 0;
					for (AnimFrame f : ea.getFrames()) {
						comps.addAll(f.getComponents().keySet());
						bytes += 8 + 20L * f.getComponents().size();
					}
					int m = comps.size();
					String it = ea.intType != null ? ea.intType.name().toLowerCase() : "?";
					String kb = String.valueOf(Math.round(bytes / 102.4) / 10.0);
					lines.add(I18n.format("cpmplus.animprops.estimate.line", ea.displayName, String.valueOf(n), it, String.valueOf(m), kb));
					JsPropertyMap<Object> o = JsPropertyMap.of();
					o.set("name", ea.displayName);
					o.set("frames", (double) n);
					o.set("intType", it);
					o.set("components", (double) m);
					o.set("bytes", (double) bytes);
					o.set("duration", (double) ea.duration);
					json.push(o);
				}
				lines.add(I18n.get("cpmplus.animprops.estimate.note"));
				JsPropertyMap<Object> last = JsPropertyMap.of();
				last.set("quality", (double) q);
				last.set("anims", json);
				PlusTestHooks.setLast("animprops.estimate", last);
				PlusDialogs.info("cpmplus_animprops_estimate", "cpmplus.animprops.estimate.title", lines);
				return null;
			}, e -> {
				a.snapping = old;
				PopupDialogs.displayError(PlusDialogs.esc(I18n.get("cpmplus.animprops.estimate.title")), e);
				return null;
			});
		} catch (Throwable e) {
			a.snapping = old;
			throw e;
		}
	}
}
