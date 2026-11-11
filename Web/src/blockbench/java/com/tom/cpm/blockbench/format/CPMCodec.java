package com.tom.cpm.blockbench.format;

import java.util.ArrayList;
import java.util.List;

import com.tom.cpm.blockbench.BBActions;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.convert.OldPluginConvert;
import com.tom.cpm.blockbench.convert.ProjectConvert;
import com.tom.cpm.blockbench.ee.EmbeddedEditorHandler;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Action.Condition;
import com.tom.cpm.blockbench.proxy.Action.ConditionMethod;
import com.tom.cpm.blockbench.proxy.Animation.AnimatorChannel;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Blockbench.WriteProperties;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Codec;
import com.tom.cpm.blockbench.proxy.Codecs;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.KeyframeDataPoint;
import com.tom.cpm.blockbench.proxy.ModelFormat;
import com.tom.cpm.blockbench.proxy.ModelFormat.FormatPage;
import com.tom.cpm.blockbench.proxy.Outliner;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Property;
import com.tom.cpm.blockbench.proxy.Property.Clazz;
import com.tom.cpm.blockbench.proxy.Property.Type;
import com.tom.cpm.blockbench.proxy.VueComponent;
import com.tom.cpm.shared.MinecraftObjectHolder;
import com.tom.cpm.web.client.WebMC;
import com.tom.cpm.web.client.util.I18n;

import elemental2.dom.DomGlobal;
import javaemul.internal.annotations.DoNotAutobox;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

public class CPMCodec {
	public static final String FORMAT_ID = "cpm";
	public static final String VISIBILITY = "cpm_visibility";
	public static final String COLOR = "cpm_color";
	/** CPM+ : JS property set on the ModelFormat created by this fork */
	public static final String PLUS_TAG = "cpmplus";

	public static Codec codec;
	public static ModelFormat format;
	private static boolean addedAnimators;

	public static void init() {
		Codec.CodecProperties prop = new Codec.CodecProperties();
		prop.name = "Customizable Player Models Project";
		prop.extension = "cpmproject";
		prop.remember = true;
		prop.load_filter = new Codec.LoadFilter();
		prop.load_filter.type = "text";
		prop.load_filter.extensions = new String[] {"cpmproject", "cpmmodel"};// CPM+ : .cpmmodel opens view-only
		prop.export = ProjectConvert::export;
		// CPM+ : .cpmproject is a zip. Blockbench's generic loader reads codec files as text and the
		// codec had no load/parse callback, so Open / drag & drop / Recent did nothing. load() re-reads
		// the file as binary (desktop) or falls back to the binary import dialog (web).
		prop.load = (model, file, args) -> ProjectConvert.loadFromCodec(model, file);
		prop.write = (content, path) -> {
			if (content == Js.uncheckedCast(""))return;
			WriteProperties pr = new WriteProperties();
			pr.content = content;
			pr.savetype = "zip";
			ProjectConvert.rememberExportPath(path);// CPM+ : quick re-export target
			Blockbench.writeFile(path, pr, codec::afterSave);
		};
		codec = new Codec("cpmproject", prop);
		ModelFormat.FormatProperties ctr = new ModelFormat.FormatProperties();
		ctr.id = FORMAT_ID;
		ctr.icon = "icon-player";
		ctr.name = "Customizable Player Models model (CPM+)";// CPM+ : display only; the format id stays "cpm"
		ctr.description = I18n.get("bb-label.cpmCodecDesc");
		ctr.target = "Minecraft: Java Edition with Customizable Player Models mod";
		ctr.bone_rig = true;
		ctr.box_uv = true;
		ctr.optional_box_uv = true;
		ctr.centered_grid = true;
		ctr.single_texture = false;
		ctr.rotate_cubes = true;
		ctr.uv_rotation = true;
		ctr.animation_mode = true;
		ctr.animation_files = true;
		ctr.per_texture_uv_size = true;
		ctr.render_sides = "double";
		if(MinecraftObjectHolder.DEBUGGING) {
			//ctr.animation_controllers = true;
			//ctr.bone_binding_expression = true;
		}
		ctr.codec = codec;
		ctr.category = "minecraft";
		ctr.format_page = new FormatPage();
		VueComponent cm = VueComponent.create("format_desc");
		cm.setMethod("create", ProjectGenerator::newProject);
		cm.setMethod("openembed", EmbeddedEditorHandler::open);
		cm.setMethod("open", ProjectConvert::open);
		cm.setMethod("openmodel", ProjectConvert::openModel);// CPM+
		cm.data.set("platform", WebMC.platform);
		ctr.format_page.component = cm;

		AnimatorChannel visCh = new AnimatorChannel();
		visCh.name = I18n.get("label.cpm.visible");
		visCh.mutable = true;
		visCh.transform = false;
		visCh.max_data_points = 1;

		AnimatorChannel colorCh = new AnimatorChannel();
		colorCh.name = I18n.get("label.cpm.recolor");
		colorCh.mutable = true;
		colorCh.transform = true;
		colorCh.max_data_points = 1;

		ctr.onActivation = () -> {
			if(addedAnimators)return;
			addedAnimators = true;
			BoneAnimator.prototype.channels.set(VISIBILITY, visCh);
			BoneAnimator.prototype.channels.set(COLOR, colorCh);
		};
		ctr.onDeactivation = () -> {
			if(!addedAnimators)return;
			addedAnimators = false;
			BoneAnimator.prototype.channels.delete(VISIBILITY);
			BoneAnimator.prototype.channels.delete(COLOR);
		};

		format = new ModelFormat(ctr);
		codec.format = format;
		// CPM+ : tag our format so the official-plugin detection never mistakes it for the official one
		try {
			Js.asPropertyMap(format).set(PLUS_TAG, true);
		} catch (Throwable e) {
		}
		// CPM+ : unregister through Blockbench's delete() APIs, and only if the registered format/codec
		// is still ours (if the official plugin is loaded too, don't delete its registration).
		final ModelFormat myFormat = format;
		final Codec myCodec = codec;
		PluginStart.cleanup.add(() -> {
			if (Global.getFormats().get(FORMAT_ID) == myFormat) {
				try {
					myFormat.delete();
				} catch (Throwable e) {
					Global.getFormats().delete(FORMAT_ID);
				}
			}
		});
		PluginStart.cleanup.add(() -> {
			if (Codecs.cpmproject == myCodec) {
				try {
					myCodec.delete();
				} catch (Throwable e) {
				}
			}
		});

		createProperty(Clazz.CUBE, Type.BOOLEAN, "cpm_glow", "CPM Glow Effect", false, true);
		createProperty(Clazz.CUBE, Type.NUMBER, "cpm_recolor", "CPM Recolor Effect", -1, true);
		createProperty(Clazz.CUBE, Type.BOOLEAN, "cpm_extrude", "CPM Extrude Effect", false, true);

		createProperty(Clazz.GROUP, Type.BOOLEAN, "cpm_hidden", "CPM Hidden Effect", false, true);
		createProperty(Clazz.GROUP, Type.BOOLEAN, "cpm_dva", "CPM Disable Vanilla Animations Effect", false, true);
		createProperty(Clazz.GROUP, Type.STRING, "cpm_copy_transform", "CPM Copy Transform Effect", Js.undefined(), true);

		createProperty(Clazz.PROJECT, Type.BOOLEAN, "cpm_hideHeadIfSkull", "CPM Hide Head with Skull", true, false);
		createProperty(Clazz.PROJECT, Type.BOOLEAN, "cpm_removeBedOffset", "CPM Remove Bed Offset", false, false);
		createProperty(Clazz.PROJECT, Type.BOOLEAN, "cpm_invisGlow", "CPM Invisible Glow", false, false);

		createProperty(Clazz.ANIMATION, Type.STRING, "cpm_type", "CPM Animation Type", "custom_pose", true);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, "cpm_additive", "CPM Additive", true, false);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, "cpm_layerCtrl", "CPM Layer Controlled", true, false);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, "cpm_commandCtrl", "CPM Command Controlled", false, false);
		createProperty(Clazz.ANIMATION, Type.NUMBER, "cpm_priority", "CPM Animation Priority", 0, false);
		createProperty(Clazz.ANIMATION, Type.NUMBER, "cpm_order", "CPM Animation Button Order", 0, false);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, "cpm_isProperty", "CPM Is Property", false, false);
		createProperty(Clazz.ANIMATION, Type.STRING, "cpm_group", "CPM Animation Type", "", false);
		createProperty(Clazz.ANIMATION, Type.NUMBER, "cpm_layerDefault", "CPM Layer Default Value", 0, false);
		// CPM+ : CPM-only animation data, persisted so CPM -> BB -> CPM round trips are lossless.
		// Missing in old .bbmodel files -> defaults (no stored grid, export infers from keyframes).
		createProperty(Clazz.ANIMATION, Type.STRING, com.tom.cpm.blockbench.proxy.Animation.P_INT_TYPE, "CPM Interpolator", "", true);
		createProperty(Clazz.ANIMATION, Type.NUMBER, com.tom.cpm.blockbench.proxy.Animation.P_FRAMES, "CPM Original Frame Count", 0, true);
		createProperty(Clazz.ANIMATION, Type.NUMBER, com.tom.cpm.blockbench.proxy.Animation.P_DURATION, "CPM Original Duration (ms)", 0, true);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, com.tom.cpm.blockbench.proxy.Animation.P_MUST_FINISH, "CPM Must Finish", false, false);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, com.tom.cpm.blockbench.proxy.Animation.P_HIDDEN, "CPM Hidden Animation", false, false);
		createProperty(Clazz.ANIMATION, Type.NUMBER, com.tom.cpm.blockbench.proxy.Animation.P_MAX_VALUE, "CPM Max Value", 100, false);
		createProperty(Clazz.ANIMATION, Type.BOOLEAN, com.tom.cpm.blockbench.proxy.Animation.P_INTERPOLATE_VAL, "CPM Interpolate Value", true, false);

		createProperty(Clazz.GROUP, Type.STRING, "cpm_data", "CPM Data", Js.undefined(), true);
		createProperty(Clazz.CUBE, Type.STRING, "cpm_data", "CPM Data", Js.undefined(), true);
		createProperty(Clazz.PROJECT, Type.STRING, "cpm_data", "CPM Data", Js.undefined(), true);

		PluginStart.cleanup.add(() -> BoneAnimator.prototype.channels.delete(VISIBILITY));
		addTranslatedEntry("timeline." + VISIBILITY, "CPM " + I18n.get("label.cpm.visible"));

		PluginStart.cleanup.add(() -> BoneAnimator.prototype.channels.delete(COLOR));
		addTranslatedEntry("timeline." + COLOR, "CPM " + I18n.get("label.cpm.recolor"));

		if (Project.format != null && Project.format.id == FORMAT_ID) {
			addedAnimators = true;
			BoneAnimator.prototype.channels.set(VISIBILITY, visCh);
			BoneAnimator.prototype.channels.set(COLOR, colorCh);
		}

		createProperty(Clazz.KEYFRAME_DATA, Type.BOOLEAN, "cpm_visible", I18n.get("label.cpm.visible"), true, false).condition.method = a -> {
			KeyframeDataPoint point = Js.uncheckedCast(a);
			return point.keyframe.channel == VISIBILITY;
		};

		createProperty(Clazz.KEYFRAME_DATA, Type.BOOLEAN, "cpm_color_picker_place", "Color Picker", false, false).condition.method = a -> {
			KeyframeDataPoint point = Js.uncheckedCast(a);
			return point.keyframe.channel == COLOR;
		};

		PluginStart.addEventListener("update_selection", dt -> {
			if(Global.getFormat() == CPMCodec.format) {
				if(Outliner.selected.length == 1 && Outliner.selected[0] instanceof Cube) {
					BBActions.glowButton.value = ((Cube)Outliner.selected[0]).glow;
					BBActions.recolorButton.value = ((Cube)Outliner.selected[0]).getRecolor() != -1;
					BBActions.glowButton.updateEnabledState();
					BBActions.recolorButton.updateEnabledState();
				}
				if(Project.selected_groups != null && Project.selected_groups.length > 0) {
					BBActions.hiddenButton.value = Project.selected_groups.getAt(0).hidden;
					BBActions.hiddenButton.updateEnabledState();
				}
				// CPM+ (W0B): selection listeners of the CPM+ tools (each isolated)
				for (Runnable r : new ArrayList<>(selectionListeners)) {
					try {
						r.run();
					} catch (Throwable e) {
						DomGlobal.console.error("CPM+: selection listener failed", e);
					}
				}
			}
		});
		PluginStart.cleanup.add(selectionListeners::clear);
		PluginStart.addEventListener(Codecs.project, "parsed", __ -> OldPluginConvert.convert());
		// CPM+ (W0B): properties registered with exported=false are never saved into the .bbmodel (Blockbench
		// itself honours 'export: false' only for project properties; element/group/animation copies keep them)
		PluginStart.addEventListener(Codecs.project, "compile", CPMCodec::stripNotExported);
	}

	private static void stripNotExported(Object evt) {
		if (notExported.isEmpty() || evt == null)return;
		try {
			Object model = Js.asPropertyMap(evt).get("model");
			if (model == null)return;
			for (String[] p : notExported)stripNotExported0(model, p[0], p[1]);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not drop non-exported properties", e);
		}
	}

	private static native void stripNotExported0(Object model, String where, String name) /*-{
		var del = function(list) { if (list instanceof Array) for (var i = 0; i < list.length; i++) if (list[i] && typeof list[i] === 'object') delete list[i][name]; };
		if (where === 'PROJECT') delete model[name];
		else if (where === 'GROUP') { del(model.groups); }
		else if (where === 'ANIMATION') del(model.animations);
		else if (where === 'TEXTURE') del(model.textures);
		else if (where === 'KEYFRAME_DATA') {
			var anims = model.animations || [];
			for (var a = 0; a < anims.length; a++) {
				var an = anims[a] && anims[a].animators;
				for (var k in an || {}) { var kfs = an[k] && an[k].keyframes || []; for (var f = 0; f < kfs.length; f++) del(kfs[f] && kfs[f].data_points); }
			}
		} else del(model.elements);
	}-*/;

	public static Property createProperty(Clazz clz, Type type, String id, String label, @DoNotAutobox Object def, boolean hidden) {
		Property p = Property.createProperty(clz, type, id, label, def, formatCPM(), hidden);
		track(p, clz, id);
		return p;
	}

	// ------------------------------------------------------------------ CPM+ (W0B-INFRA) property registry

	private static final List<Property> registered = new ArrayList<>();
	/** {clazz name, property name} of properties registered with exported=false */
	private static final List<String[]> notExported = new ArrayList<>();
	private static final List<Runnable> selectionListeners = new ArrayList<>();

	/**
	 * CPM+ (W0B): registers a Blockbench Property for a CPM+ feature (also after init, from a feature installer).
	 * exported=false keeps it out of the saved .bbmodel; hidden=true hides it from Blockbench's property panels;
	 * cpmFormatOnly=true applies it only to CPM projects (false: every format, e.g. a dismiss flag of a banner
	 * shown in other formats). The property is deleted when the plugin unloads. New CPM+-only names should start
	 * with 'cpm_plus_' (listed in the file's version stamp).
	 */
	public static Property registerProperty(Clazz clz, Type type, String name, String label, @DoNotAutobox Object def, boolean exported, boolean hidden, boolean cpmFormatOnly) {
		Property.PropertyInit init = new Property.PropertyInit();
		init.label = label;
		init.def = def;
		init.exposed = !hidden;
		if(cpmFormatOnly)init.condition = formatCPM();
		if(!exported)init.exportFlag = false;
		Property p = Property.create(clz, type, name, init);
		track(p, clz, name);
		if(!exported) {
			String[] rec = new String[] {clz.name(), name};
			notExported.add(rec);
			PluginStart.cleanup.add(() -> notExported.remove(rec));
		}
		return p;
	}

	private static void track(Property p, Clazz clz, String name) {
		registered.add(p);
		PluginStart.cleanup.add(() -> {
			registered.remove(p);
			// only delete it if the registration is still ours (another plugin may have replaced it)
			JsPropertyMap<Property> props = clz.properties();
			if(props == null || props.get(name) == p)p.delete();
		});
	}

	/** CPM+ (W0B): names of the properties registered by this plugin instance (in registration order) */
	public static List<String> registeredPropertyNames() {
		List<String> l = new ArrayList<>();
		for (Property p : registered)l.add(p.name);
		return l;
	}

	/** CPM+ (W0B): like {@link #registeredPropertyNames()} without the ones registered with exported=false (never in a file) */
	public static List<String> exportedPropertyNames() {
		List<String> l = new ArrayList<>();
		for (Property p : registered) {
			boolean exported = true;
			for (String[] ne : notExported)if (ne[1].equals(p.name))exported = false;
			if (exported && !l.contains(p.name))l.add(p.name);
		}
		return l;
	}

	/**
	 * CPM+ (W0B): runs r after every selection change in a CPM project (Blockbench 'update_selection'). Errors are
	 * isolated. @return a Runnable that removes the listener (all are removed on unload).
	 */
	public static Runnable onSelectionChange(Runnable r) {
		selectionListeners.add(r);
		return () -> selectionListeners.remove(r);
	}

	public static void addTranslatedEntry(String key, String value) {
		Global.getLang().set(key, value);
		PluginStart.cleanup.add(() -> Global.getLang().delete(key));
	}

	public static Condition formatCPM() {
		Condition condition = new Action.Condition();
		condition.formats = new String[] {FORMAT_ID};
		return condition;
	}

	public static ConditionMethod notCPM() {
		// CPM+ : Format is false/undefined on the start screen; a throw inside a BB condition breaks toolbars
		return c -> !isCPM();
	}

	/** CPM+ : null-safe 'is a CPM project open' check, for event hooks and conditions */
	public static boolean isCPM() {
		try {
			ModelFormat f = Global.getFormat();
			return f != null && Js.isTruthy(f) && FORMAT_ID.equals(f.id);
		} catch (Throwable e) {
			return false;
		}
	}
}
