package com.tom.cpm.blockbench.format;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Canvas;
import com.tom.cpm.blockbench.proxy.Codecs;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.KeyframeDataPoint;
import com.tom.cpm.blockbench.proxy.Modes;
import com.tom.cpm.blockbench.proxy.NodePreviewController.NodePreviewEvent;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.OutlinerNode;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.proxy.jq.JQueryNode;
import com.tom.cpm.blockbench.proxy.jq.JQueryNode.SpectrumInit;
import com.tom.cpm.blockbench.proxy.three.ThreeColor;
import com.tom.cpm.blockbench.util.MaterialGroup;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.dom.Event;
import elemental2.dom.HTMLDivElement;
import elemental2.dom.HTMLInputElement;
import jsinterop.base.Js;

public class CPMVisuals {

	public static void init() {
		PluginStart.addEventListener(Codecs.project, "parsed", __ -> {
			if (Project.format == CPMCodec.format) {
				Cube.all.forEach(CPMVisuals::updateCubeMaterial);
			}
		});

		PluginStart.addEventListener(Cube.preview_controller, "update_geometry update_faces", dt -> {
			if (Project.format == CPMCodec.format) {
				OutlinerElement elem = NodePreviewEvent.getElement(dt);
				if(elem instanceof Cube) {
					Cube c = (Cube) elem;
					updateCubeMaterial(c);
				}
			}
		});

		// CPM+ : only touch the keyframe panel in CPM projects (Blockbench #3856: the plugin must not
		// interfere with other formats' animator UI)
		PluginStart.addEventListener("update_keyframe_selection", __ -> {
			if (CPMCodec.isCPM())updateKeyframeSelection();
		});

		PluginStart.addEventListener("display_animation_frame", __ -> displayAnimation());
		// CPM+ : restore editor visibility/colours when leaving Animate mode
		PluginStart.addEventListener("select_mode", __ -> displayAnimation());
		// CPM+ (NEW-VIS-ANIMATE): a project left in Animate mode (tab switch / close) and the open project when the plugin
		// unloads never keep the Animate preview visibility; a project selected in Animate mode gets the preview again
		PluginStart.addEventListener("unselect_project", CPMVisuals::onUnselectProject);
		PluginStart.addEventListener("select_project", __ -> onSelectProject());
		PluginStart.cleanup.add(CPMVisuals::restoreForUnload);
	}

	private static void updateCubeMaterial(Cube c) {
		// CPM+ : a cube with no material yet (every face textureless, or its texture still loading in some Blockbench
		// versions) used to crash the import with "cannot read properties of undefined (reading 'cpm_materialGroup')"
		if (c.mesh == null || c.mesh.material == null) return;
		MaterialGroup group = c.mesh.material.getMaterialGroup();
		if (c.getRecolor() != -1) {
			if (group.isColor()) {
				group.setColor(ThreeColor.make(c.getRecolor()));
			} else {
				c.mesh.material = group.makeRecolor(ThreeColor.make(c.getRecolor()), c.isColorCube());
			}
		} else if (group.isColor()) {
			c.mesh.material = group.getOriginal();
		}
		group = c.mesh.material.getMaterialGroup();
		c.mesh.material = c.glow ? group.getGlow() : group.getNormal();
	}

	private static void setCubeColor(Cube c, ThreeColor color) {
		if (c.mesh == null || c.mesh.material == null) return;
		MaterialGroup group = c.mesh.material.getMaterialGroup();
		if (group.isColor())
			group.setColor(color);
	}

	private static void displayAnimation() {
		if (Project.format == CPMCodec.format) {
			// CPM+ : was 'Modes.animate != null' (always true), so the restore branch never ran
			// CPM+ (NEW-VIS-ANIMATE): v0.7's 'Modes.animate.selected' was never true (Modes.animate is a boolean flag)
			if (isAnimateMode()) {
				Map<String, BoneAnimator> animsV = new HashMap<>();
				Map<String, BoneAnimator> animsC = new HashMap<>();
				Arrays.stream(Animation.all).
				filter(a -> a.playing && !(a.loop.equals("once") && Timeline.time > a.length && a.length > 0)).
				sorted(Comparator.comparingInt(Animation::getPriority)).forEach(a -> {
					a.animators.forEach(k -> {
						GeneralAnimator ga = a.animators.get(k);
						if(ga instanceof BoneAnimator) {
							BoneAnimator ba = (BoneAnimator) ga;
							if(ba.hasVisible())animsV.put(k, ba);
							if(ba.hasColor())animsC.put(k, ba);
						}
					});
				});

				for (int i = 0; i < Group.all.length; i++) {
					Group g = Group.all[i];
					if (g.parent == Group.ROOT)
						g.currentVisible = true;
					else
						g.currentVisible = Animation.interpolateVisible(animsV.get(g.uuid), Timeline.time, !g.hidden);

					BoneAnimator cAn = animsC.get(g.uuid);
					JsVec3 color = cAn != null && !cAn.isMuted(CPMCodec.COLOR) ? Animation.interpolate(cAn.color, Timeline.time) : null;
					if (color != null) {
						for (OutlinerNode e : g.children.array()) {
							if (e instanceof Cube) {
								Cube c = (Cube) e;
								if (c.canRecolor()) {
									setCubeColor(c, ThreeColor.make(color.x / 255f, color.y / 255f, color.z / 255f));
								}
							}
						}
					}

					/*if (g.copyTransform != null && !g.copyTransform.isEmpty()) {//TODO
						JsonMap s = JsonUtil.fromJson(g.copyTransform);
						Group from = Group.uuids.get((String) s.get("uuid"));
						if (from != null) {
							Vec3f addPos = new Vec3f();
							Vec3f addRot = new Vec3f();
							if(from.parent == Group.ROOT) {
								VanillaModelPart part = from.parent.getRootType();
								if(part != null) {

								}
							}
							if(s.getBoolean("px", false))g.mesh.position.x = from.mesh.position.x + addPos.x;
							if(s.getBoolean("py", false))g.mesh.position.y = from.mesh.position.y - addPos.y;
							if(s.getBoolean("pz", false))g.mesh.position.z = from.mesh.position.z - addPos.z;
							if(s.getBoolean("rx", false))g.mesh.rotation.x = from.mesh.rotation.x + (float) Math.toRadians(addRot.x);
							if(s.getBoolean("ry", false))g.mesh.rotation.y = from.mesh.rotation.y + (float) Math.toRadians(addRot.y);
							if(s.getBoolean("rz", false))g.mesh.rotation.z = from.mesh.rotation.z - (float) Math.toRadians(addRot.z);
							if(s.getBoolean("sx", false))g.mesh.scale.x = from.mesh.scale.x;
							if(s.getBoolean("sy", false))g.mesh.scale.y = from.mesh.scale.y;
							if(s.getBoolean("sz", false))g.mesh.scale.z = from.mesh.scale.z;
							if(s.getBoolean("cv", false))g.currentVisible = from.currentVisible;
						}
					}*/
				}

				for (int i = 0; i < Cube.all.length; i++) {
					Cube c = Cube.all.getAt(i);
					if(!c.animatorInit) {
						c.animatorInit = true;
						c.defaultVisible = c.visibility;
					}
				}
				// CPM+ : groups' visibility is overwritten by applyVisible too, remember it for restore
				for (int i = 0; i < Group.all.length; i++) {
					Group g = Group.all[i];
					if(!g.animatorInit) {
						g.animatorInit = true;
						g.defaultVisible = g.visibility;
					}
				}

				for (int i = 0; i < Group.all.length; i++) {
					Group g = Group.all[i];
					if(g.parent != Group.ROOT)continue;
					g.applyVisible(true);
				}
			} else {
				for (int i = 0; i < Group.all.length; i++) {
					Group g = Group.all[i];
					if(g.animatorInit) {
						g.animatorInit = false;
						g.visibility = g.defaultVisible;
					}
				}

				for (int i = 0; i < Cube.all.length; i++) {
					Cube c = Cube.all.getAt(i);
					if(c.animatorInit) {
						c.animatorInit = false;
						c.visibility = c.defaultVisible;

						if (c.canRecolor()) {
							setCubeColor(c, ThreeColor.make(c.getRecolor()));
						}
					}
				}
			}
			Canvas.updateVisibility();
		}
	}

	/**
	 * CPM+ (NEW-VIS-ANIMATE): true while Blockbench is in Animate mode. In Blockbench 5 {@code Modes.animate} is a
	 * boolean flag (false at start, true in Animate mode, deleted when another mode is selected), not a Mode object.
	 */
	public static native boolean isAnimateMode() /*-{
		var M = $wnd.Modes;
		if (!M) return false;
		var a = M.animate;
		if (a === true) return true;
		return !!a && typeof a === 'object' && a.selected === true;
	}-*/;

	/** CPM+ (NEW-VIS-ANIMATE): projects whose cubes may still show an Animate colour (restored when selected again) */
	private static final Set<String> colorRefresh = new HashSet<>();

	/**
	 * CPM+ (NEW-VIS-ANIMATE): puts the user's visibility back on every node that shows the Animate preview state (the
	 * same as leaving Animate mode); colors = also the cubes' own recolor (only for the open project: Blockbench's
	 * texture lookups need it). @return true when a node was restored
	 */
	private static boolean restoreEditState(JsArray<Object> groups, JsArray<Object> cubes, boolean colors) {
		boolean any = false;
		if (groups != null) {
			for (int i = 0; i < groups.length; i++) {
				Object o = groups.getAt(i);
				if (!(o instanceof Group))continue;
				Group g = (Group) o;
				if(g.animatorInit) {
					g.animatorInit = false;
					g.visibility = g.defaultVisible;
					any = true;
				}
			}
		}
		if (cubes != null) {
			for (int i = 0; i < cubes.length; i++) {
				Object o = cubes.getAt(i);
				if (!(o instanceof Cube))continue;
				Cube c = (Cube) o;
				if(c.animatorInit) {
					c.animatorInit = false;
					c.visibility = c.defaultVisible;
					any = true;
					if (colors)restoreOwnColor(c);
				}
			}
		}
		return any;
	}

	private static void restoreOwnColor(Cube c) {
		try {
			if (c.mesh != null && c.mesh.material != null && c.canRecolor()) {
				setCubeColor(c, ThreeColor.make(c.getRecolor()));
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not restore the colour of a cube", e);
		}
	}

	/** CPM+ (NEW-VIS-ANIMATE): a CPM project selected again outside Animate mode gets its cubes' own colours back */
	private static void onSelectProject() {
		displayAnimation();
		try {
			if (Js.isTruthy(Project.getProject()) && Project.format == CPMCodec.format && !isAnimateMode() && colorRefresh.remove(Project.getUuid())) {
				for (int i = 0; i < Cube.all.length; i++)restoreOwnColor(Cube.all.getAt(i));
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not restore the cube colours", e);
		}
	}

	/** CPM+ (NEW-VIS-ANIMATE): the project of a tab switch / close is not shown any more: restore its nodes */
	private static void onUnselectProject(Object event) {
		try {
			Object p = event != null ? Js.asPropertyMap(event).get("project") : null;
			if (p == null || !"object".equals(Js.typeof(p)))return;
			Object groups = Js.asPropertyMap(p).get("groups");
			Object elements = Js.asPropertyMap(p).get("elements");
			JsArray<Object> ga = JsArray.isArray(groups) ? Js.<JsArray<Object>>uncheckedCast(groups) : null;
			JsArray<Object> ea = JsArray.isArray(elements) ? Js.<JsArray<Object>>uncheckedCast(elements) : null;
			// Blockbench has already reset Project here: visibility only, the colours when the project is selected again
			if (restoreEditState(ga, ea, false)) {
				Object uuid = Js.asPropertyMap(p).get("uuid");
				if (uuid != null && "string".equals(Js.typeof(uuid)))colorRefresh.add((String) uuid);
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not restore the editor visibility of the previous project", e);
		}
	}

	/** CPM+ (NEW-VIS-ANIMATE): plugin unload: the open project never keeps the Animate preview state */
	private static void restoreForUnload() {
		try {
			if (Js.isTruthy(Project.getProject()) && Project.format == CPMCodec.format) {
				if (restoreEditState(Js.<JsArray<Object>>uncheckedCast(Group.all), Js.<JsArray<Object>>uncheckedCast(Cube.all), true))Canvas.updateVisibility();
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not restore the editor visibility on unload", e);
		}
	}

	private static void updateKeyframeSelection() {
		JQueryNode v = JQueryNode.jq("#keyframe_bar_cpm_visible");
		if(v.length != 0) {
			JQueryNode cb = v.find("#keyframe_bar_cpm_visible_cb");
			if(cb.length == 0) {
				HTMLDivElement el = (HTMLDivElement) v.getAt(0);
				HTMLInputElement inputElement = (HTMLInputElement) el.querySelector("input[type=text]");
				HTMLInputElement checkboxElement = (HTMLInputElement) DomGlobal.document.createElement("input");
				checkboxElement.type = "checkbox";
				checkboxElement.checked = "true".equalsIgnoreCase(inputElement.value);
				checkboxElement.id = "keyframe_bar_cpm_visible_cb";
				checkboxElement.addEventListener("change", event -> {
					inputElement.value = Boolean.toString(checkboxElement.checked);
					inputElement.dispatchEvent(new Event("input"));
				});
				inputElement.style.display = "none";
				el.append(checkboxElement);
			} else {
				HTMLInputElement inputElement = (HTMLInputElement) v.find("input[type=text]").getAt(0);
				HTMLInputElement checkboxElement = (HTMLInputElement) cb.getAt(0);
				checkboxElement.checked = "true".equalsIgnoreCase(inputElement.value);
			}
		}
		v = JQueryNode.jq("#keyframe_bar_cpm_color_picker_place");
		if(v.length != 0 && Keyframe.selected.length != 0) {
			KeyframeDataPoint kdp = Keyframe.selected.getAt(0).data_points.getAt(0);
			SpectrumInit si = new SpectrumInit();
			si.preferredFormat = "hex";
			si.showAlpha = false;
			si.showInput = true;
			si.color = String.format("#%02X%02X%02X", (int) kdp.x, (int) kdp.y, (int) kdp.z);
			si.change = si.hide = si.move = ci -> {
				String c = ci.toHexString();
				kdp.x = Integer.valueOf(c.substring(1, 3), 16);
				kdp.y = Integer.valueOf(c.substring(3, 5), 16);
				kdp.z = Integer.valueOf(c.substring(5, 7), 16);
			};
			v.find("input[type=text]").spectrum(si);

			JQueryNode.jq("#keyframe_bar_x > label").text("R");
			JQueryNode.jq("#keyframe_bar_y > label").text("G");
			JQueryNode.jq("#keyframe_bar_z > label").text("B");
		} else {
			JQueryNode.jq("#keyframe_bar_x > label").text("X");
			JQueryNode.jq("#keyframe_bar_y > label").text("Y");
			JQueryNode.jq("#keyframe_bar_z > label").text("Z");
		}
	}
}
