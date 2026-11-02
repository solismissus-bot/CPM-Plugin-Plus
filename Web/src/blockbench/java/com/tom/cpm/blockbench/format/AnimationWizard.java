package com.tom.cpm.blockbench.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.animprops.AnimProps;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.web.client.java.JsBuilder;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

public class AnimationWizard {
	// CPM+ : interpolation override choices (only matters for 'smooth' keys: POLY vs TRIG)
	private static final String INTERP_AUTO = "auto";
	private static final String INTERP_POLY = "poly";
	private static final String INTERP_TRIG = "trig";

	public static void open(Animation anIn, Consumer<Boolean> close) {
		Dialog.DialogProperties dctr = new Dialog.DialogProperties();
		dctr.id = "cpm_new_animation";
		// CPM+ : edit and create have different titles
		dctr.title = anIn != null ? I18n.get("cpmplus.animprops.title.edit") : I18n.get("bb-label.newAnimation");
		Dialog.FormTextboxElement name = Dialog.FormTextboxElement.make("generic.name");
		if(anIn != null)name.value = anIn.name;
		else name.value = uniqueName("animation", null);// CPM+

		Dialog.FormSelectElement loop = Dialog.FormSelectElement.make("menu.animation.loop");
		loop.options = new JsBuilder<>().
				put("once", "menu.animation.loop.once").
				put("hold", "menu.animation.loop.hold").
				put("loop", "menu.animation.loop.loop").
				build();
		if(anIn != null)loop.value = anIn.loop;
		else loop.value = "loop";// CPM+ : was 'once' (first option) which needs a staged setup for poses

		Dialog.FormSelectElement type = Dialog.FormSelectElement.make(I18n.get("bb-label.animationType"));
		JsBuilder<String> typeB = new JsBuilder<>();
		for(AnimationType t : AnimationType.VALUES) {
			if(t.isCustom()) {
				String id = t.name().toLowerCase(Locale.ROOT);
				typeB.put(id, I18n.get("label.cpm.new_anim_" + id));
			}
		}
		for (VanillaPose p : VanillaPose.VALUES) {
			if(p == VanillaPose.CUSTOM)continue;
			String id = p.name().toLowerCase(Locale.ROOT);
			typeB.put(id, I18n.get("label.cpm.pose." + id));
		}
		type.options = typeB.build();
		if(anIn != null)type.value = anIn.type;

		Dialog.FormCheckboxElement add = Dialog.FormCheckboxElement.make(I18n.get("label.cpm.anim_additive"));
		if(anIn != null)add.value = anIn.additive;
		else add.value = true;// CPM+ : CPM default

		Dialog.FormCheckboxElement lc = Dialog.FormCheckboxElement.make(I18n.get("label.cpm.anim_layerCtrl"), anIn != null ? anIn.layerCtrl : true);
		lc.description = I18n.formatNl("tooltip.cpm.anim_layerCtrl");

		Dialog.FormCheckboxElement cc = Dialog.FormCheckboxElement.make(I18n.get("label.cpm.anim_command"));
		if(anIn != null)cc.value = anIn.commandCtrl;
		cc.description = I18n.formatNl("tooltip.cpm.anim_command");

		// CPM+ (F15): 'Mostrar opções avançadas' (opens by itself when one of those options is not at its default)
		Dialog.FormCheckboxElement advanced = Dialog.FormCheckboxElement.make(I18n.get("cpmplus.animprops.advanced"), AnimProps.hasAdvancedValues(anIn), I18n.get("cpmplus.animprops.advanced.desc"));

		// CPM+ (F15): priority as Normal (0) / Acima das outras (10) / Personalizado
		int prio0 = anIn != null ? anIn.getPriority() : 0;
		Dialog.FormSelectElement prioritySel = Dialog.FormSelectElement.make(I18n.get("label.cpm.anim_priority"));
		prioritySel.options = new JsBuilder<>().
				put(AnimProps.P_NORMAL, I18n.get("cpmplus.animprops.prio.normal")).
				put(AnimProps.P_ABOVE, I18n.get("cpmplus.animprops.prio.above")).
				put(AnimProps.P_CUSTOM, I18n.get("cpmplus.animprops.prio.custom")).
				build();
		prioritySel.value = AnimProps.priorityOption(prio0);
		prioritySel.description = I18n.get("cpmplus.animprops.prio.desc");

		Dialog.FormVectorElement priority = Dialog.FormVectorElement.make(I18n.get("cpmplus.animprops.prio.value"), 1);
		priority.value[0] = prio0;
		priority.description = I18n.formatNl("tooltip.cpm.anim_priority");

		Dialog.FormVectorElement order = Dialog.FormVectorElement.make(I18n.get("label.cpm.anim_order"), 1);
		if(anIn != null)order.value[0] = anIn.getOrder();
		order.description = I18n.formatNl("tooltip.cpm.anim_order");

		// CPM+ (F15): yellow notes (shown by update())
		Dialog.FormInfoElement byteWarn = Dialog.FormInfoElement.make(null, warnHtml(I18n.get("cpmplus.animprops.byteWarn")), null);
		Dialog.FormInfoElement unreachWarn = Dialog.FormInfoElement.make(null, warnHtml(I18n.get("cpmplus.animprops.unreachWarn")), null);

		Dialog.FormCheckboxElement prop = Dialog.FormCheckboxElement.make(I18n.get("label.cpm.anim_is_property"));
		if(anIn != null)prop.value = anIn.isProperty;
		prop.description = I18n.formatNl("tooltip.cpm.anim_is_property");

		Dialog.FormTextboxElement group = Dialog.FormTextboxElement.make(I18n.get("label.cpm.layerGroup"));
		if(anIn != null)group.value = anIn.group;
		group.description = I18n.formatNl("tooltip.cpm.layerGroup");

		Dialog.FormVectorElement layerDefaultF = Dialog.FormVectorElement.make(I18n.get("label.cpm.defLayerSettings.value"), 1);
		if(anIn != null)layerDefaultF.value[0] = anIn.getLayerDefault();

		Dialog.FormCheckboxElement layerDefaultB = Dialog.FormCheckboxElement.make(I18n.get("label.cpm.defLayerSettings.toggle"));
		if(anIn != null)layerDefaultB.value = anIn.getLayerDefault() > 0.5f;

		// CPM+ (F18): quality = Blockbench's snapping, presets 10/20/30/60 + Personalizado (an imported 24 stays 24)
		int snap0 = anIn != null && anIn.snapping > 0 ? anIn.snapping : 20;
		Dialog.FormSelectElement quality = Dialog.FormSelectElement.make(I18n.get("cpmplus.animprops.quality"));
		quality.options = new JsBuilder<>().
				put("10", I18n.get("cpmplus.animprops.q.10")).
				put("20", I18n.get("cpmplus.animprops.q.20")).
				put("30", I18n.get("cpmplus.animprops.q.30")).
				put("60", I18n.get("cpmplus.animprops.q.60")).
				put(AnimProps.Q_CUSTOM, I18n.get("cpmplus.animprops.q.custom")).
				build();
		quality.value = AnimProps.qualityOption(snap0);
		quality.description = I18n.get("cpmplus.animprops.quality.desc");

		// CPM+ : min 1 (was 10, which forced >=10 frames/s on export after opening the dialog)
		Dialog.FormNumberElement snapping = Dialog.FormNumberElement.make(I18n.get("cpmplus.animprops.qualityCustom"), 1, 1, 500);
		snapping.value = snap0;
		Dialog.FormInfoElement qualityHint = Dialog.FormInfoElement.make(null, PlusDialogs.escMdLines(I18n.get("cpmplus.animprops.quality.hint")), null);
		Dialog.FormInfoElement visWarn = Dialog.FormInfoElement.make(null, warnHtml(I18n.get("cpmplus.animprops.visWarn")), null);
		Dialog.FormInfoElement frameWarn = Dialog.FormInfoElement.make(null, warnHtml(I18n.get("cpmplus.animprops.frameWarn")), null);
		Dialog[] dlg = new Dialog[1];
		JsPropertyMap<Object> qualityTools = JsPropertyMap.of();
		qualityTools.set("type", "buttons");
		JsArray<String> qtb = new JsArray<>();
		qtb.push(I18n.get("cpmplus.animprops.applyAll"));
		qtb.push(I18n.get("cpmplus.animprops.estimate"));
		qualityTools.set("buttons", qtb);
		PlusDialogs.ButtonsClick qtClick = (i, ev) -> {
			try {
				FormResult cur = new FormResult(dlg[0] != null ? dlg[0].getFormResult() : null);
				int q = AnimProps.quality(cur.getString("quality"), cur.getInt("snapping", snap0));
				if(i == 0)AnimProps.applyQualityToAll(q);
				else AnimProps.estimate(anIn, q);
			} catch (Throwable e) {
				PopupDialogs.displayError(PlusDialogs.esc(I18n.get("cpmplus.animprops.quality")), e);
			}
		};
		qualityTools.set("click", qtClick);

		// CPM+ : length is editable (new animations default to 1s instead of 0 -> 0 exported frames)
		Dialog.FormVectorElement length = Dialog.FormVectorElement.make(I18n.get("cpmplus.animprops.length"), 1);
		length.value[0] = anIn != null ? anIn.length : 1;
		length.min = 0;

		// CPM+ : CPM-only properties (#923 / #944)
		Dialog.FormSelectElement interp = Dialog.FormSelectElement.make(I18n.get("cpmplus.animprops.interp"));
		interp.options = new JsBuilder<>().
				put(INTERP_AUTO, I18n.get("cpmplus.animprops.interp.auto")).
				put(INTERP_POLY, I18n.get("cpmplus.animprops.interp.poly")).
				put(INTERP_TRIG, I18n.get("cpmplus.animprops.interp.trig")).
				build();
		InterpolatorType curIt = anIn != null ? anIn.getCpmIntType() : null;
		interp.value = curIt == null ? INTERP_AUTO : (curIt == InterpolatorType.TRIG_LOOP || curIt == InterpolatorType.TRIG_SINGLE) ? INTERP_TRIG : INTERP_POLY;
		interp.description = I18n.get("cpmplus.animprops.interp.desc");

		// CPM+ (F15): pt-BR labels + one-line tips; stored property names unchanged (old .bbmodel files)
		Dialog.FormCheckboxElement mustFinish = Dialog.FormCheckboxElement.make(I18n.get("cpmplus.animprops.mustFinish"), anIn != null && anIn.isMustFinish(), I18n.get("cpmplus.animprops.mustFinish.desc"));
		Dialog.FormCheckboxElement hidden = Dialog.FormCheckboxElement.make(I18n.get("cpmplus.animprops.hidden"), anIn != null && anIn.isAnimHidden(), I18n.get("cpmplus.animprops.hidden.desc"));
		Dialog.FormNumberElement maxValue = Dialog.FormNumberElement.make(I18n.get("cpmplus.animprops.maxValue"), 1, 1, 255);
		maxValue.value = AnimProps.clampSteps(anIn != null ? anIn.getMaxValue() : 100);
		maxValue.description = I18n.get("cpmplus.animprops.maxValue.desc");
		Dialog.FormCheckboxElement interpVal = Dialog.FormCheckboxElement.make(I18n.get("cpmplus.animprops.interpVal"), anIn != null ? anIn.isInterpolateVal() : true, I18n.get("cpmplus.animprops.interpVal.desc"));

		dctr.form = new JsBuilder<>().
				put("name", name).
				put("loop", loop).
				put("type", type).
				put("add", add).
				put("layerCtrl", lc).
				put("commandCtrl", cc).
				put("order", order).
				put("prop", prop).
				put("group", group).
				put("layerDefaultF", layerDefaultF).
				put("layerDefaultB", layerDefaultB).
				put("length", length).
				put("quality", quality).
				put("snapping", snapping).
				put("qualityHint", qualityHint).
				put("visWarn", visWarn).
				put("frameWarn", frameWarn).
				put("qualityTools", qualityTools).
				put("advanced", advanced).
				put("prioritySel", prioritySel).
				put("priority", priority).
				put("byteWarn", byteWarn).
				put("interp", interp).
				put("mustFinish", mustFinish).
				put("hidden", hidden).
				put("unreachWarn", unreachWarn).
				put("maxValue", maxValue).
				put("interpVal", interpVal).
				build();
		Dialog.link(dctr.form);

		// CPM+ (F15 / F18): which rows show depends on the type, 'Mostrar opções avançadas' and the values
		Consumer<FormResult> update = r -> {
			String t = r.getString("type");
			if(t == null)t = AnimationType.CUSTOM_POSE.name().toLowerCase(Locale.ROOT);
			boolean isLayer = t.equals(AnimationType.LAYER.name().toLowerCase(Locale.ROOT));
			boolean isValue = t.equals(AnimationType.VALUE_LAYER.name().toLowerCase(Locale.ROOT));
			boolean isPose = t.equals(AnimationType.CUSTOM_POSE.name().toLowerCase(Locale.ROOT));
			boolean isGesture = t.equals(AnimationType.GESTURE.name().toLowerCase(Locale.ROOT));
			boolean noFinish = t.equals(AnimationType.SETUP.name().toLowerCase(Locale.ROOT)) || t.equals(AnimationType.FINISH.name().toLowerCase(Locale.ROOT));
			boolean wheel = isLayer || isValue || isPose || isGesture;// command / order / hidden apply
			boolean adv = r.getBool("advanced");
			prop.getBar().toggle(isLayer || isValue);
			group.getBar().toggle(isLayer);
			layerDefaultF.getBar().toggle(isValue);
			layerDefaultB.getBar().toggle(isLayer);
			lc.getBar().toggle(isPose || isGesture);
			cc.getBar().toggle(wheel);
			order.getBar().toggle(wheel);
			prioritySel.getBar().toggle(adv);
			boolean customPrio = AnimProps.P_CUSTOM.equals(r.getString("prioritySel"));
			priority.getBar().toggle(adv && customPrio);
			interp.getBar().toggle(adv);
			mustFinish.getBar().toggle(adv && !noFinish);
			hidden.getBar().toggle(adv && wheel);
			maxValue.getBar().toggle(adv && isValue);
			interpVal.getBar().toggle(adv && isValue);
			unreachWarn.getBar().toggle(wheel && r.getBool("hidden") && !r.getBool("commandCtrl"));
			int pv = AnimProps.num0(r.raw("priority"), 0);
			int ov = AnimProps.num0(r.raw("order"), 0);
			byteWarn.getBar().toggle((adv && customPrio && !AnimProps.inByte(pv)) || (wheel && !AnimProps.inByte(ov)));
			int q = AnimProps.quality(r.getString("quality"), r.getInt("snapping", snap0));
			snapping.getBar().toggle(AnimProps.Q_CUSTOM.equals(r.getString("quality")));
			visWarn.getBar().toggle(anIn != null && AnimProps.shortVisibility(anIn, q));
			frameWarn.getBar().toggle(anIn != null && q != anIn.snapping && AnimProps.predictFrames(anIn, q) > AnimProps.MAX_FRAMES);
		};

		// CPM+ (F15): hiding from the wheel turns on 'controlled by command' (only a command can reach it then)
		boolean[] prevHidden = {anIn != null && anIn.isAnimHidden()};
		dctr.onFormChange = rIn -> {
			FormResult r = new FormResult(rIn);
			boolean h = r.getBool("hidden");
			if(h && !prevHidden[0] && !r.getBool("commandCtrl") && dlg[0] != null) {
				prevHidden[0] = true;
				JsPropertyMap<Object> v = JsPropertyMap.of();
				v.set("commandCtrl", true);
				try {
					dlg[0].setFormValues(v, false);
					r = new FormResult(dlg[0].getFormResult());
				} catch (Throwable e) {
					DomGlobal.console.warn("CPM+: could not tick 'controlled by command'", e);
				}
			}
			prevHidden[0] = h;
			update.accept(r);
		};

		dctr.onOpen = () -> {
			JsPropertyMap<Object> init = JsPropertyMap.of();
			init.set("type", anIn != null ? anIn.type : AnimationType.CUSTOM_POSE.name().toLowerCase(Locale.ROOT));
			init.set("advanced", advanced.value);
			init.set("prioritySel", prioritySel.value);
			init.set("priority", (double) prio0);
			init.set("order", (double) (anIn != null ? anIn.getOrder() : 0));
			init.set("hidden", hidden.value);
			init.set("commandCtrl", cc.value);
			init.set("quality", quality.value);
			init.set("snapping", (double) snap0);
			update.accept(new FormResult(init));
		};

		dctr.onConfirm = rIn -> {
			Animation a = anIn;
			DialogResult r = Js.uncheckedCast(rIn);
			FormResult fr = new FormResult(rIn);
			// CPM+ (F18): never change the quality to one that makes more than 255 frames (CPM writes the count as a byte)
			int q = AnimProps.quality(fr.getString("quality"), fr.getInt("snapping", snap0));
			if(anIn != null && q != anIn.snapping) {
				int fr0 = AnimProps.predictFrames(anIn, q);
				if(fr0 > AnimProps.MAX_FRAMES) {
					PlusDialogs.block(I18n.format("cpmplus.animprops.tooManyFrames", String.valueOf(q), anIn.name, String.valueOf(fr0)));
					return false;
				}
			}
			List<String> notes = new ArrayList<>();
			boolean edit = false;
			if(a != null) {
				Undo.initEdit(UndoData.make(a));
				edit = true;
			} else {
				// CPM+ : proper undo for 'Add animation' (initEdit was missing)
				Undo.initEdit(UndoData.make(new Animation[0]));
				a = new Animation();
				a.name = uniqueName(r.name, null);
				a = a.add(false);
			}
			// CPM+ : unique animation names ('#' grouping still works, only exact duplicates are renamed)
			a.name = uniqueName(r.name == null || r.name.trim().isEmpty() ? "animation" : r.name.trim(), a);
			a.loop = r.loop;
			a.type = r.type;
			a.additive = r.add;
			a.layerCtrl = r.layerCtrl;
			a.commandCtrl = r.commandCtrl;
			// CPM+ (F15): Normal 0 / Acima das outras 10 / Personalizado; priority and order are bytes in CPM (-128..127)
			int prio = AnimProps.priorityValue(fr.getString("prioritySel"), AnimProps.num0(fr.raw("priority"), prio0));
			if(!AnimProps.inByte(prio))notes.add(I18n.format("cpmplus.animprops.clampedPrio", String.valueOf(prio), String.valueOf(AnimProps.clampByte(prio))));
			a.setPriority(AnimProps.clampByte(prio));
			a.group = r.group;
			a.isProperty = r.prop;
			int ord = AnimProps.num0(fr.raw("order"), 0);
			if(!AnimProps.inByte(ord))notes.add(I18n.format("cpmplus.animprops.clampedOrder", String.valueOf(ord), String.valueOf(AnimProps.clampByte(ord))));
			a.setOrder(AnimProps.clampByte(ord));
			a.snapping = q;// CPM+ (F18)
			if(r.length != null && r.length.length > 0 && !Float.isNaN(r.length[0]))
				a.length = Math.max(0, r.length[0]);
			if(a.type.equals(AnimationType.LAYER.name().toLowerCase(Locale.ROOT)))
				a.setLayerDefault(r.layerDefaultB ? 1 : 0);
			else
				a.setLayerDefault(r.layerDefaultF[0]);
			a.setMustFinish(r.mustFinish);
			a.setAnimHidden(r.hidden);
			a.setMaxValue(AnimProps.clampSteps(fr.getInt("maxValue", anIn != null ? anIn.getMaxValue() : 100)));// CPM+ (F15): 1..255
			a.setInterpolateVal(r.interpVal);
			applyInterp(a, r.interp);

			if(edit)Undo.finishEdit("Edit animation properties");
			else {
				Undo.finishEdit("Add animation", UndoData.make(a));
				try {
					a.select();
				} catch (Throwable e) {
					// older/newer Blockbench without Animation#select
				}
			}
			if(!notes.isEmpty())PlusDialogs.toast(String.join(" ", notes), "warning", 10000, null);
			if(close != null)close.accept(true);
			return true;
		};

		dctr.onCancel = () -> {
			if(close != null)close.accept(false);
			return true;
		};

		com.tom.cpm.blockbench.plus.PlusGestureParts.decorateWizard(dctr, anIn);// CPM+ (G54): 'Nome no jogo' + 'Parte'
		dlg[0] = new Dialog(dctr);
		dlg[0].show();
	}

	/** CPM+ (F15): yellow note for an info row (constant lang text, markdown + HTML escaped) */
	private static String warnHtml(String text) {
		return "<span style='color: var(--color-warning, #e6a23c);'>" + PlusDialogs.escMdLines(text) + "</span>";
	}

	/** CPM+ : apply the smooth-interpolation choice to the stored CPM interpolator. */
	private static void applyInterp(Animation a, String choice) {
		if(choice == null)return;
		InterpolatorType cur = a.getCpmIntType();
		boolean curTrig = cur == InterpolatorType.TRIG_LOOP || cur == InterpolatorType.TRIG_SINGLE;
		if(INTERP_AUTO.equals(choice)) {
			if(cur != null && curTrig) {
				a.setCpmIntType(null);
				a.setCpmFrames(0);
			}
		} else if(INTERP_TRIG.equals(choice)) {
			if(!curTrig) {
				// keep loop/single variant if known; frames=0 -> grid re-detected on export
				a.setCpmIntType(cur != null ? (cur == InterpolatorType.POLY_SINGLE || cur == InterpolatorType.LINEAR_SINGLE ? InterpolatorType.TRIG_SINGLE : InterpolatorType.TRIG_LOOP) : InterpolatorType.TRIG_LOOP);
				a.setCpmFrames(0);
			}
		} else if(INTERP_POLY.equals(choice)) {
			if(cur == null || curTrig) {
				a.setCpmIntType(cur == InterpolatorType.TRIG_SINGLE ? InterpolatorType.POLY_SINGLE : InterpolatorType.POLY_LOOP);
				a.setCpmFrames(0);
			}
		}
	}

	/** CPM+ : returns 'base' or 'base2', 'base3'... so no other animation has exactly that name. */
	private static String uniqueName(String base, Animation self) {
		if(base == null)base = "animation";
		String n = base;
		int i = 2;
		while(nameTaken(n, self)) {
			n = base + i;
			i++;
		}
		return n;
	}

	private static boolean nameTaken(String n, Animation self) {
		if(Animation.all == null)return false;
		for (Animation an : Animation.all) {
			if(an != self && n.equals(an.name))return true;
		}
		return false;
	}

	/**
	 * CPM+ : route Blockbench's own animation properties dialog (double click on an animation,
	 * 'Properties' in its context menu, BB 5.x) to the CPM dialog when a CPM project is open,
	 * so CPM animation type and settings can be edited after creation (GitHub #923, #944).
	 */
	public static void installPropertiesHook() {
		try {
			Object proto = Animation.prototype;
			if(proto == null)return;
			Object orig = hookPropertiesDialog(proto);
			if(orig != null)PluginStart.cleanup.add(() -> unhookPropertiesDialog(proto, orig));
		} catch (Throwable e) {
			// never break plugin loading over this
		}
	}

	public static boolean handlePropertiesDialog(Animation a) {
		if(!CPMCodec.isCPM())return false;// CPM+ : null/false-safe format check
		open(a, null);
		return true;
	}

	private static native Object hookPropertiesDialog(Object proto)/*-{
		var orig = proto.propertiesDialog;
		if (typeof orig !== 'function') return null;
		proto.propertiesDialog = function() {
			if (@com.tom.cpm.blockbench.format.AnimationWizard::handlePropertiesDialog(Lcom/tom/cpm/blockbench/proxy/Animation;)(this)) return;
			return orig.apply(this, arguments);
		};
		return orig;
	}-*/;

	private static native void unhookPropertiesDialog(Object proto, Object orig)/*-{
		proto.propertiesDialog = orig;
	}-*/;

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	private static class DialogResult {
		public String name, loop, type, group, interp;
		public boolean add, layerCtrl, commandCtrl, prop, layerDefaultB, mustFinish, hidden, interpVal;
		public float priority, order;
		public float[] layerDefaultF, length;
		public int snapping;
	}
}
