package com.tom.cpm.blockbench.tools.animprops;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.LayerCapacity;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormResult;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.shared.editor.util.PlayerSkinLayer;
import com.tom.cpm.web.client.util.I18n;

import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.HTMLButtonElement;
import elemental2.dom.HTMLInputElement;
import elemental2.dom.NodeList;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ (lite unit animprops, F24): 'Camadas da skin pras animações'. CPM syncs custom poses and gestures that are
 * 'layer controlled' to other players through the skin's 2nd-layer parts (hat, jacket, sleeves, pants): each layer
 * chosen as an animation switch carries one bit. Same data as CPM's own AnimEncConfigPopup (anim_enc.json:
 * freeLayers + defaultValues), without its 'Limpar' button (that one erases skin pixels).
 * <ul>
 * <li>Per PlayerSkinLayer: 'usar como interruptor de animação' (free) and 'mostrar essa camada' (default visibility,
 * disabled while the layer is a switch: CPM then owns it).</li>
 * <li>OK is disabled, with the reason, while layer-controlled animations exist and the switches can't encode them
 * (CPM's export would fail: {@link LayerCapacity}, a format limit).</li>
 * <li>Writes pluginData 'anims.free' / 'anims.def' of the open project; undo through the 'Desfazer' toast (Ctrl+Z
 * does not cover project data). A project where the dialog was never confirmed keeps no anim_enc data.</li>
 * </ul>
 */
public final class SkinLayersDialog {
	public static final String DIALOG_ID = "cpmplus_animprops_skin_layers";

	private SkinLayersDialog() {
	}

	/** the values of the dialog: switches + default visibility of every layer */
	static final class State {
		final Set<PlayerSkinLayer> free = new LinkedHashSet<>();
		final Map<PlayerSkinLayer, Boolean> def = new EnumMap<>(PlayerSkinLayer.class);
	}

	/** edits the free/def layers of the open Blockbench project; resolves true when OK was pressed */
	public static Promise<Boolean> open() {
		ProjectData pd = Project.getData();
		State st = fromProject(pd);
		int count = LayerCapacity.checkProject().count;
		return show(st, count, res -> {
			ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
			ProjectData p = Project.getData();
			writeProject(p, res);
			p.flush();
			Project.saved = false;
			ProjectDataUndo.offerUndo(before, I18n.get("cpmplus.animprops.layers.doneWhat"));
		});
	}

	@SuppressWarnings("unchecked")
	static State fromProject(ProjectData pd) {
		State st = new State();
		Object fl = pd.animations.get("free");
		if(fl instanceof List) {
			for (Object o : (List<Object>) fl) {
				PlayerSkinLayer l = o != null ? PlayerSkinLayer.getLayer(String.valueOf(o)) : null;
				if(l != null)st.free.add(l);
			}
		}
		Object df = pd.animations.get("def");
		Map<String, Object> dm = df instanceof Map ? (Map<String, Object>) df : null;
		for (PlayerSkinLayer l : PlayerSkinLayer.VALUES) {
			Object v = dm != null ? dm.get(l.getLowerName()) : null;
			st.def.put(l, v instanceof Boolean ? (Boolean) v : !st.free.contains(l));
		}
		return st;
	}

	/** pd.animations 'free' (lower names, enum order) + 'def' (every layer; a switch layer is always false) */
	static void writeProject(ProjectData pd, State st) {
		List<Object> free = new ArrayList<>();
		for (PlayerSkinLayer l : PlayerSkinLayer.VALUES)if(st.free.contains(l))free.add(l.getLowerName());
		Map<String, Object> def = new LinkedHashMap<>();
		for (PlayerSkinLayer l : PlayerSkinLayer.VALUES) {
			Boolean v = st.def.get(l);
			def.put(l.getLowerName(), st.free.contains(l) ? Boolean.FALSE : (v != null ? v : Boolean.TRUE));
		}
		pd.animations.put("free", free);
		pd.animations.put("def", def);
	}

	private static String freeKey(PlayerSkinLayer l) {
		return "free_" + l.getLowerName();
	}

	private static String defKey(PlayerSkinLayer l) {
		return "def_" + l.getLowerName();
	}

	/** null when the choice works with count layer-controlled animations, else the reason (plain text) */
	static String problem(int count, int free) {
		String err = LayerCapacity.error(count, free);
		if(err == null)return null;
		if(LayerCapacity.ERR_NO_LAYERS.equals(err))return I18n.format("cpmplus.animprops.layers.needTwo", String.valueOf(count));
		return I18n.format("cpmplus.animprops.layers.tooMany", String.valueOf(count), String.valueOf(free), String.valueOf(LayerCapacity.capacity(free)));
	}

	static State read(FormResult r) {
		State st = new State();
		for (PlayerSkinLayer l : PlayerSkinLayer.VALUES) {
			if(r.getBool(freeKey(l)))st.free.add(l);
			st.def.put(l, r.getBool(defKey(l)));
		}
		return st;
	}

	private static Promise<Boolean> show(State st, int count, Consumer<State> apply) {
		return new Promise<>((res, rej) -> {
			boolean[] done = new boolean[1];
			List<FormRow> rows = new ArrayList<>();
			rows.add(FormRow.info("hint", null, I18n.get("cpmplus.animprops.layers.hint")));
			if(count > 0)rows.add(FormRow.info("count", null, I18n.format("cpmplus.animprops.layers.count", String.valueOf(count))));
			for (PlayerSkinLayer l : PlayerSkinLayer.VALUES) {
				String name = I18n.get("label.cpm.layer_" + l.getLowerName());
				rows.add(FormRow.checkbox(freeKey(l), I18n.format("cpmplus.animprops.layers.free", name), st.free.contains(l)));
				Boolean dv = st.def.get(l);
				rows.add(FormRow.checkbox(defKey(l), I18n.format("cpmplus.animprops.layers.def", name), dv != null ? dv : !st.free.contains(l)));
			}
			rows.add(FormRow.info("problem", null, ""));
			PlusDialogs.FormOpts opts = new PlusDialogs.FormOpts();
			opts.width = 540;
			Dialog[] dlg = new Dialog[1];
			opts.onChange = r -> refresh(dlg[0], read(r), count);
			opts.onCancel = () -> {
				if(!done[0]) {
					done[0] = true;
					res.onInvoke(false);
				}
			};
			dlg[0] = PlusDialogs.form(DIALOG_ID, "cpmplus.animprops.layers.title", rows, r -> {
				State ns = read(r);
				String p = problem(count, ns.free.size());
				if(p != null) {
					// never written: CPM could not export the model with this choice
					r.keepOpen();
					refresh(dlg[0], ns, count);
					return;
				}
				done[0] = true;
				apply.accept(ns);
				PlusTestHooks.setLast("animprops.skinLayers", describe(ns));
				res.onInvoke(true);
			}, opts);
			refresh(dlg[0], st, count);
		});
	}

	/** JSON-like description for the test hooks */
	static Map<String, Object> describe(State st) {
		Map<String, Object> m = new LinkedHashMap<>();
		List<Object> free = new ArrayList<>();
		for (PlayerSkinLayer l : PlayerSkinLayer.VALUES)if(st.free.contains(l))free.add(l.getLowerName());
		m.put("free", free);
		Map<String, Object> def = new LinkedHashMap<>();
		st.def.forEach((k, v) -> def.put(k.getLowerName(), v));
		m.put("def", def);
		return m;
	}

	/** 'mostrar essa camada' disabled for switch layers; OK disabled + the reason while the choice can't export */
	private static void refresh(Dialog d, State st, int count) {
		if(d == null)return;
		try {
			Element root = Js.uncheckedCast(Js.asPropertyMap(d).get("object"));
			if(root == null)return;
			for (PlayerSkinLayer l : PlayerSkinLayer.VALUES) {
				Element in = root.querySelector("input#" + defKey(l));
				if(in != null)((HTMLInputElement) Js.uncheckedCast(in)).disabled = st.free.contains(l);
			}
			String p = problem(count, st.free.size());
			Element info = root.querySelector(".form_bar_problem");
			if(info != null) {
				info.textContent = p != null ? p : "";
				Js.asPropertyMap(Js.asPropertyMap(info).get("style")).set("color", "var(--color-error, #e14f4f)");
			}
			NodeList<Element> btns = root.querySelectorAll(".button_bar button");
			if(btns.length == 0)btns = root.querySelectorAll("footer button, .dialog_bar button");
			if(btns.length > 0) {
				HTMLButtonElement ok = Js.uncheckedCast(btns.item(0));
				ok.disabled = p != null;
				if(p != null)ok.title = p;
				else ok.removeAttribute("title");
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: skin layer dialog refresh failed", e);
		}
	}
}
