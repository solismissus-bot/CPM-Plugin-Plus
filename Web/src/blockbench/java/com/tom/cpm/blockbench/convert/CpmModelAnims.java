package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.CustomPose;
import com.tom.cpm.shared.animation.IPose;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.project.loaders.AnimationsLoaderV1;
import com.tom.cpm.shared.parts.anim.AnimLoaderState;
import com.tom.cpm.shared.parts.anim.AnimatorChannel;
import com.tom.cpm.shared.parts.anim.AnimatorChannel.CubeColorDriver;
import com.tom.cpm.shared.parts.anim.AnimatorChannel.CubeFloatDriver;
import com.tom.cpm.shared.parts.anim.AnimatorChannel.CubePosDriver;
import com.tom.cpm.shared.parts.anim.AnimatorChannel.CubeRotDriver;
import com.tom.cpm.shared.parts.anim.AnimatorChannel.CubeScaleDriver;
import com.tom.cpm.shared.parts.anim.AnimatorChannel.CubeVisDriver;
import com.tom.cpm.shared.parts.anim.ConstantTimeBool;
import com.tom.cpm.shared.parts.anim.ConstantTimeFloat;
import com.tom.cpm.shared.parts.anim.Float3Driver;
import com.tom.cpm.shared.parts.anim.Float3Driver.Float3Consumer;
import com.tom.cpm.shared.parts.anim.ParameterDetails;
import com.tom.cpm.shared.parts.anim.SerializedAnimation;
import com.tom.cpm.shared.parts.anim.SerializedTrigger;
import com.tom.cpm.shared.parts.anim.StageType;
import com.tom.cpm.shared.parts.anim.menu.AbstractGestureButtonData;
import com.tom.cpm.shared.parts.anim.menu.AbstractGestureButtonData.AbstractCommandTriggerableData;
import com.tom.cpm.shared.parts.anim.menu.BoolParameterToggleButtonData;
import com.tom.cpm.shared.parts.anim.menu.CustomPoseGestureButtonData;
import com.tom.cpm.shared.parts.anim.menu.DropdownButtonData;
import com.tom.cpm.shared.parts.anim.menu.LegacyDropdownButtonData;
import com.tom.cpm.shared.parts.anim.menu.ValueParameterButtonData;

/**
 * CPM+ : the editor animations of a .cpmmodel written in CPM's current animation format (the reverse of CPM's
 * AnimationExporter): the trigger and its button give the type, name, group, order, default and options; the
 * channels give the frames (13 per cube: position, rotation, colour and scale x/y/z, then visibility; rotation in
 * radians). Several animations of one button get 'name#2', 'name#3'... like CPM's 'name#part'.
 */
final class CpmModelAnims {
	private CpmModelAnims() {
	}

	private static final class Info {
		AnimationType type;
		IPose pose;
		String name, group;
		int order;
		float def;
		int maxValue = 100;
		boolean layerCtrl, command, isProperty, hidden;
	}

	static void toEditor(Editor e, AnimLoaderState st, IntFunction<ModelElement> byId) {
		Map<Integer, SerializedTrigger> triggers = st.getTriggers();
		List<Integer> ids = new ArrayList<>(st.getAnims().keySet());
		Collections.sort(ids);
		Map<String, Integer> seen = new HashMap<>();
		for (int id : ids) {
			SerializedAnimation sa = st.getAnims().get(id);
			SerializedTrigger tr = triggers.get(sa.triggerID);
			if (tr == null)continue;
			SerializedTrigger main = tr;
			AnimationType stage = null;
			if (tr.stage != null) {// setup / finish / the main animations of a staged gesture
				List<Integer> staged = st.getStagedList();
				main = tr.stagingID >= 0 && tr.stagingID < staged.size() ? triggers.get(staged.get(tr.stagingID)) : null;
				if (main == null)continue;
				if (tr.stage == StageType.SETUP)stage = AnimationType.SETUP;
				else if (tr.stage == StageType.FINISH)stage = AnimationType.FINISH;
			}
			Info info = describe(main, st.getGestureButtons(), st.getParameters());
			if (info == null)continue;
			AnimationType type = stage != null ? stage : info.type;
			String key = type + ":" + info.name;
			int n = seen.merge(key, 1, Integer::sum);
			String name = n > 1 ? info.name + "#" + n : info.name;
			IPose pose = stage != null ? null : info.pose;

			EditorAnim a = new EditorAnim(e, AnimationsLoaderV1.getFileName(pose, name), type, false);
			a.displayName = name;
			a.pose = pose;
			a.loop = type == AnimationType.GESTURE && main.looping;
			a.mustFinish = tr.mustFinish;
			a.priority = sa.priority;
			a.duration = sa.duration == VanillaPose.DYNAMIC_DURATION_DIV ? 1000 : sa.duration;
			a.layerControlled = info.layerCtrl;
			a.command = info.command;
			a.isProperty = info.isProperty;
			a.hidden = info.hidden;
			a.order = info.order;
			a.group = info.group;
			a.layerDefault = info.def;
			a.maxValue = info.maxValue;
			a.interpolateValue = main.parameterInterpolate;
			frames(a, sa, byId);
			e.animations.add(a);
		}
	}

	/** type, name and button settings of a (non staged) trigger */
	private static Info describe(SerializedTrigger t, List<AbstractGestureButtonData> buttons, ParameterDetails params) {
		Info i = new Info();
		if (t.pose != null) {
			i.type = AnimationType.POSE;
			i.pose = t.pose;
			i.name = t.pose.name().toLowerCase(Locale.ROOT);
			return i;
		}
		if (t.anim == null)return null;
		i.type = t.anim == AnimationType.POSE ? AnimationType.CUSTOM_POSE : t.anim;
		for (int k = 0; k < buttons.size() && i.name == null; k++) {
			AbstractGestureButtonData b = buttons.get(k);
			switch (i.type) {
			case GESTURE:
			case CUSTOM_POSE:
				if (b instanceof CustomPoseGestureButtonData && ((CustomPoseGestureButtonData) b).isPose() == (i.type == AnimationType.CUSTOM_POSE)
						&& ((CustomPoseGestureButtonData) b).id == t.value)i.name = b.getName();
				break;
			case LAYER:
				if (t.bitMask) {
					if (b instanceof BoolParameterToggleButtonData && ((BoolParameterToggleButtonData) b).parameter == t.parameter
							&& ((BoolParameterToggleButtonData) b).mask == t.value) {
						i.name = b.getName();
						i.def = (params.getDefaultParam(t.parameter) & t.value) != 0 ? 1 : 0;
					}
				} else if (b instanceof DropdownButtonData && ((DropdownButtonData) b).parameter == t.parameter) {
					List<String> opts = ((DropdownButtonData) b).opts;
					i.name = t.value >= 0 && t.value < opts.size() ? opts.get(t.value) : b.getName() + " " + t.value;
					i.group = b.getName();
					i.def = Byte.toUnsignedInt(params.getDefaultParam(t.parameter)) == t.value ? 1 : 0;
				} else if (b instanceof LegacyDropdownButtonData) {
					for (Map.Entry<String, Integer> o : ((LegacyDropdownButtonData) b).parameters.entrySet()) {
						if (o.getValue() != null && o.getValue() == t.parameter && !o.getKey().isEmpty()) {
							i.name = o.getKey();
							i.group = b.getName();
							i.def = params.getDefaultParam(t.parameter) != 0 ? 1 : 0;
						}
					}
				}
				break;
			case VALUE_LAYER:
				if (b instanceof ValueParameterButtonData && ((ValueParameterButtonData) b).parameter == t.parameter) {
					i.name = b.getName();
					i.maxValue = ((ValueParameterButtonData) b).maxValue;
					i.def = Byte.toUnsignedInt(params.getDefaultParam(t.parameter)) / 255f;
				}
				break;
			default:
				break;
			}
			if (i.name != null) {
				i.order = k;
				if (b instanceof AbstractCommandTriggerableData) {
					AbstractCommandTriggerableData c = (AbstractCommandTriggerableData) b;
					i.layerCtrl = c.layerCtrl;
					i.command = c.command;
					i.isProperty = c.isProperty;
					i.hidden = c.hidden;
				}
			}
		}
		if (i.name == null)i.name = i.type.name().toLowerCase(Locale.ROOT) + " " + t.value;
		if (i.type == AnimationType.CUSTOM_POSE)i.pose = new CustomPose(i.name, i.order);
		return i;
	}

	/** the frames of one animation, from its channels */
	private static void frames(EditorAnim a, SerializedAnimation sa, IntFunction<ModelElement> byId) {
		Map<Integer, float[][]> vals = new LinkedHashMap<>();// cube id -> 12 channels (pos, rot, colour, scale x/y/z)
		Map<Integer, boolean[]> vis = new LinkedHashMap<>();
		Boolean add = null;
		InterpolatorType it = null;
		int count = 0, axis = 0;
		Float3Consumer prev = null;
		for (int c = 0; c < sa.animatorChannels.size(); c++) {
			AnimatorChannel ac = sa.animatorChannels.get(c);
			if (ac == null)continue;
			Float3Consumer f3 = Float3Driver.getFromPart(ac.part);
			if (f3 instanceof CubeFloatDriver) {
				axis = f3 == prev ? axis + 1 : 0;// a Float3Driver registers x, y, z in this order
				prev = f3;
				CubeFloatDriver d = (CubeFloatDriver) f3;
				if (add == null)add = d.additive;
				int g = f3 instanceof CubePosDriver ? 0 : f3 instanceof CubeRotDriver ? 1 : f3 instanceof CubeColorDriver ? 2 : f3 instanceof CubeScaleDriver ? 3 : -1;
				if (g < 0 || axis > 2 || !(ac.frameData instanceof ConstantTimeFloat))continue;
				ConstantTimeFloat ctf = (ConstantTimeFloat) ac.frameData;
				vals.computeIfAbsent(d.cubeId, k -> new float[12][])[g * 3 + axis] = ctf.getFrames();
				if (it == null)it = ctf.getIntType();
				count = Math.max(count, ctf.getFrames().length);
			} else {
				prev = null;
				if (ac.part instanceof CubeVisDriver && ac.frameData instanceof ConstantTimeBool) {
					boolean[] b = ((ConstantTimeBool) ac.frameData).getFrames();
					vis.put(((CubeVisDriver) ac.part).getCubeId(), b);
					count = Math.max(count, b.length);
				}
			}
		}
		a.add = add == null || add;// before makeData: a frame's untouched values depend on it
		a.intType = it != null ? it : InterpolatorType.POLY_LOOP;
		int n = Math.max(count, 1);
		List<AnimFrame> fr = a.getFrames();
		for (int f = 0; f < n; f++)fr.add(new AnimFrame(a));
		Set<Integer> cubes = new LinkedHashSet<>(vals.keySet());
		cubes.addAll(vis.keySet());
		for (int id : cubes) {
			ModelElement me = byId.apply(id);
			if (me == null)continue;
			float[][] v = vals.get(id);
			boolean[] s = vis.get(id);
			for (int f = 0; f < n; f++) {
				FrameData d = fr.get(f).makeData(me);
				if (v != null) {
					if (has(v, 0))d.setPos(vec(v, 0, f, d.getPosition(), false));
					if (has(v, 1))d.setRot(vec(v, 1, f, d.getRotation(), true));
					if (has(v, 2))d.setColor(vec(v, 2, f, d.getColor(), false));
					if (has(v, 3))d.setScale(vec(v, 3, f, d.getScale(), false));
				}
				if (s != null && f < s.length)d.setShow(s[f]);
			}
		}
		a.setSelectedFrame(fr.get(0));
	}

	private static boolean has(float[][] v, int g) {
		return v[g * 3] != null || v[g * 3 + 1] != null || v[g * 3 + 2] != null;
	}

	/** x/y/z of group g at frame f; an axis without data keeps the frame's own value; rotation radians -> degrees */
	private static Vec3f vec(float[][] v, int g, int f, Vec3f def, boolean rot) {
		return new Vec3f(axis(v[g * 3], f, def.x, rot), axis(v[g * 3 + 1], f, def.y, rot), axis(v[g * 3 + 2], f, def.z, rot));
	}

	private static float axis(float[] a, int f, float def, boolean rot) {
		if (a == null || f >= a.length)return def;
		return rot ? (float) Math.toDegrees(a[f]) : a[f];
	}
}
