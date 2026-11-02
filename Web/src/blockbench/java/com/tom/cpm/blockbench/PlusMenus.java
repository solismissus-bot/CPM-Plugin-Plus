package com.tom.cpm.blockbench;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Action.Condition;
import com.tom.cpm.blockbench.proxy.Action.Toggle;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Menu;
import com.tom.cpm.blockbench.proxy.MenuBar;
import com.tom.cpm.blockbench.proxy.MenuBar.BarMenu;
import com.tom.cpm.blockbench.proxy.MenuBar.BarMenuInit;
import com.tom.cpm.blockbench.proxy.MenuBar.SubMenu;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.web.client.util.I18n;

import jsinterop.base.Js;

/**
 * CPM+ (W0B-INFRA): the 'CPM+' menu bar menu and the one way to register CPM+ actions.
 *
 * <pre>
 * Action a = PlusMenus.action("cpmplus_w3settings_open", "cpmplus.w3settings.menu", "settings", Cond.CPM_ONLY, () -&gt; open());
 * PlusMenus.add(PlusMenus.SUB_SETTINGS, a);
 * </pre>
 *
 * Every action, toggle, menu entry and context-menu entry made here is removed when the plugin unloads. Ids always
 * start with 'cpmplus_'. A click runs only while its condition holds and errors are shown in the CPM error dialog.
 * The CPM+ menu shows itself in CPM projects, and anywhere else only when one of its entries applies there
 * (Cond.ANY_PROJECT / Cond.ALWAYS); empty submenus are hidden by Blockbench.
 */
public final class PlusMenus {
	public static final String MENU_ID = "cpm_plus_tools";
	public static final String ID_PREFIX = "cpmplus_";
	public static final String SUB_SETTINGS = "cpmplus_sub_settings";
	public static final String SUB_ANIM = "cpmplus_sub_anim";
	public static final String SUB_MODEL = "cpmplus_sub_model";
	public static final String SUB_TEX = "cpmplus_sub_tex";
	public static final String SUB_OPT = "cpmplus_sub_opt";
	public static final String SUB_VIEW = "cpmplus_sub_view";
	public static final String SUB_HELP = "cpmplus_sub_help";
	/** top-level items between the submenus and 'Ajuda' (Validar, Re-exportar ...) */
	public static final String TOP = "";

	/** where an action is enabled */
	public enum Cond {
		/** only while a CPM project is open (default) */
		CPM_ONLY,
		/** any open project (also Java Block, Bedrock ...) */
		ANY_PROJECT,
		/** always, also on the start screen */
		ALWAYS;

		public boolean met() {
			switch (this) {
			case CPM_ONLY:
				return CPMCodec.isCPM();
			case ANY_PROJECT:
				return Global.isProjectOpen();
			default:
				return true;
			}
		}

		/** a Blockbench condition for this Cond (+ an optional extra check) */
		public Condition toCondition(BooleanSupplier extra) {
			Condition c = new Condition();
			if (this == CPM_ONLY)c.formats = new String[] {CPMCodec.FORMAT_ID};
			c.method = ctx -> {
				try {
					return met() && (extra == null || extra.getAsBoolean());
				} catch (Throwable e) {
					return false;// a throwing condition breaks Blockbench's toolbars/menus
				}
			};
			return c;
		}
	}

	private static BarMenu menu;
	private static Object[] structure;
	private static final List<Object> topItems = new ArrayList<>();

	private PlusMenus() {
	}

	/** creates the CPM+ bar menu with its submenus (PluginStart, before PlusTools and the feature installers) */
	public static void init() {
		topItems.clear();
		List<Object> s = new ArrayList<>();
		s.add(sub(SUB_SETTINGS, "cpmplus.w0binfra.menu.sub.settings", "settings"));
		s.add(sub(SUB_ANIM, "cpmplus.w0binfra.menu.sub.anim", "movie"));
		s.add(sub(SUB_MODEL, "cpmplus.w0binfra.menu.sub.model", "view_in_ar"));
		s.add(sub(SUB_TEX, "cpmplus.w0binfra.menu.sub.tex", "image"));
		s.add(sub(SUB_OPT, "cpmplus.w0binfra.menu.sub.opt", "speed"));
		s.add(sub(SUB_VIEW, "cpmplus.w0binfra.menu.sub.view", "visibility"));
		s.add("_");
		s.add(sub(SUB_HELP, "cpmplus.w0binfra.menu.sub.help", "help"));
		structure = s.toArray();
		BarMenuInit bmi = new BarMenuInit();
		Condition c = new Condition();
		c.method = ctx -> {
			try {
				return CPMCodec.isCPM() || anyItemApplies();
			} catch (Throwable e) {
				return false;
			}
		};
		bmi.condition = c;
		bmi.name = "CPM+";
		menu = new BarMenu(MENU_ID, structure, bmi);
		final BarMenu my = menu;
		PluginStart.cleanup.add(() -> {
			try {
				if (Global.hasFunction(my, "delete"))my.delete();// removes the menu bar label too
			} catch (Throwable e) {
			}
			if (MenuBar.menus != null && MenuBar.menus.get(MENU_ID) == my)MenuBar.menus.delete(MENU_ID);
			if (menu == my)menu = null;
			topItems.clear();
		});
		updateMenuBar();
	}

	private static SubMenu sub(String id, String nameKey, String icon) {
		SubMenu m = new SubMenu();
		m.id = id;
		m.name = I18n.get(nameKey);
		m.icon = icon;
		m.children = new Object[0];
		return m;
	}

	/** true when an entry of the CPM+ menu applies right now (shows the menu outside CPM projects) */
	private static boolean anyItemApplies() {
		if (menu == null || menu.structure == null)return false;
		for (int i = 0; i < menu.structure.length; i++) {
			if (applies(menu.structure.getAt(i), 0))return true;
		}
		return false;
	}

	private static boolean applies(Object item, int depth) {
		if (item == null || depth > 4 || "string".equals(Js.typeof(item)))return false;
		Object children = Js.asPropertyMap(item).get("children");
		if (children != null && "object".equals(Js.typeof(children))) {
			Object[] arr = Js.uncheckedCast(children);
			for (Object o : arr)if (applies(o, depth + 1))return true;
			return false;
		}
		if (item instanceof Action)return Global.conditionMet(((Action) item).condition, null);
		return false;
	}

	public static void updateMenuBar() {
		try {
			if (Global.hasFunction(Global.getGlobal("MenuBar"), "update"))MenuBar.update();
		} catch (Throwable e) {
		}
	}

	// ------------------------------------------------------------------ actions

	/** CPM-only action (see {@link #action(String, String, String, Cond, BooleanSupplier, Runnable)}) */
	public static Action action(String id, String nameKey, String icon, Runnable click) {
		return action(id, nameKey, icon, Cond.CPM_ONLY, null, click);
	}

	public static Action action(String id, String nameKey, String icon, Cond cond, Runnable click) {
		return action(id, nameKey, icon, cond, null, click);
	}

	/**
	 * Creates a Blockbench Action (also reachable through action search and keybinds). id gets the 'cpmplus_'
	 * prefix when missing; nameKey is a lang key (its '.desc' key, when present, becomes the description); when =
	 * extra enabled check (e.g. 'an animation is selected'). Removed on unload.
	 */
	/**
	 * Default shortcuts of the most used CPM+ tools: Ctrl+Alt+letter. Blockbench 5 itself only uses Ctrl+Alt+S / T;
	 * Alt+Shift switches the Windows keyboard layout and Ctrl+Alt+Q / W / E / C are AltGr characters on ABNT2
	 * keyboards, so neither is used. Every one can be changed in Settings > Keybindings (category Tools).
	 * Values: action id -> key code.
	 */
	private static final java.util.Map<String, Integer> DEFAULT_KEYS = new java.util.HashMap<>();
	static {
		DEFAULT_KEYS.put("cpmplus_w3settings_hub", 65);// A: Ajustes do modelo
		DEFAULT_KEYS.put("cpmplus_extra_f6", 68);// D: Atrasar / adiantar movimento
		DEFAULT_KEYS.put("cpmplus_copy_transform", 70);// F: Copiar movimento de (Copy Transform)
		DEFAULT_KEYS.put("cpmplus_w5stick_stick", 71);// G: Grudar na parte do corpo
		DEFAULT_KEYS.put("cpmplus_w5vis_showhide", 72);// H: Mostrar/esconder nesta animação
		DEFAULT_KEYS.put("cpmplus_animimport", 73);// I: Importar animações de outro .cpmproject
		DEFAULT_KEYS.put("cpmplus_w3modelexport_take", 74);// J: Levar pro jogo
		DEFAULT_KEYS.put("cpmplus_w6help_skeleton", 75);// K: Ver esqueleto
		DEFAULT_KEYS.put("cpmplus_extra_f5", 76);// L: Fechar o loop
		DEFAULT_KEYS.put("cpmplus_w4anim2_mirror", 77);// M: Espelhar pro outro lado
		DEFAULT_KEYS.put("cpmplus_w4anim2_wave", 78);// N: Fazer onda
		DEFAULT_KEYS.put("cpmplus_optim", 79);// O: Otimizar animação
		DEFAULT_KEYS.put("cpmplus_w3animtools_f23", 80);// P: Marcar pose aqui
		DEFAULT_KEYS.put("cpmplus_extra_g63", 86);// V: Suavizar movimento
		DEFAULT_KEYS.put("cpmplus_w3animtools_f17", 88);// X: Exagerar ou suavizar movimento
	}

	private static native Object ctrlAltKey(int keyCode) /*-{
		try {
			return $wnd.Keybind ? new $wnd.Keybind({key: keyCode, ctrl: true, alt: true}) : undefined;
		} catch (e) {
			return undefined;
		}
	}-*/;

	/** the default Keybind of an action id (Ctrl+Alt+letter), or undefined */
	static Object defaultKeybind(String prefixedId) {
		Integer k = DEFAULT_KEYS.get(prefixedId);
		return k == null ? null : ctrlAltKey(k);
	}

	/** id -> 'Ctrl + Alt + X' for the guide / tests */
	public static java.util.Map<String, String> defaultShortcuts() {
		java.util.Map<String, String> m = new java.util.TreeMap<>();
		for (java.util.Map.Entry<String, Integer> e : DEFAULT_KEYS.entrySet())m.put(e.getKey(), "Ctrl + Alt + " + (char) e.getValue().intValue());
		return m;
	}

	public static Action action(String id, String nameKey, String icon, Cond cond, BooleanSupplier when, Runnable click) {
		Cond cnd = cond != null ? cond : Cond.CPM_ONLY;
		String name = I18n.get(nameKey);
		Action.ActionProperties a = new Action.ActionProperties();
		a.name = name;
		String desc = I18n.get(nameKey + ".desc");
		a.description = desc.equals(nameKey + ".desc") ? "" : desc;
		a.icon = icon;
		a.category = "tools";
		Object kb = defaultKeybind(prefixed(id));
		if (kb != null)a.keybind = kb;
		a.condition = cnd.toCondition(when);
		a.click = e -> {
			if (!cnd.met())return;
			try {
				click.run();
			} catch (Throwable ex) {
				PopupDialogs.displayError(I18n.format("cpmplus.w0binfra.error.actionFailed", PlusDialogs.esc(name)), ex);
			}
		};
		Action act = new Action(prefixed(id), a);
		PluginStart.cleanup.add(act::delete);
		return act;
	}

	/** On/off action (e.g. an opt-in preview mode); onChange gets the new value; removed on unload */
	public static Toggle toggle(String id, String nameKey, String icon, Cond cond, boolean def, Consumer<Boolean> onChange) {
		Cond cnd = cond != null ? cond : Cond.CPM_ONLY;
		String name = I18n.get(nameKey);
		Action.ToggleProperties a = new Action.ToggleProperties();
		a.name = name;
		String desc = I18n.get(nameKey + ".desc");
		a.description = desc.equals(nameKey + ".desc") ? "" : desc;
		a.icon = icon;
		a.category = "tools";
		Object kb = defaultKeybind(prefixed(id));
		if (kb != null)a.keybind = kb;
		a.def = def;
		a.condition = cnd.toCondition(null);
		a.onChange = v -> {
			try {
				onChange.accept(v);
			} catch (Throwable ex) {
				PopupDialogs.displayError(I18n.format("cpmplus.w0binfra.error.actionFailed", PlusDialogs.esc(name)), ex);
			}
		};
		Toggle t = new Toggle(prefixed(id), a);
		PluginStart.cleanup.add(t::delete);
		return t;
	}

	public static String prefixed(String id) {
		if (id == null || id.isEmpty())throw new IllegalArgumentException("empty action id");
		return id.startsWith(ID_PREFIX) ? id : ID_PREFIX + id;
	}

	// ------------------------------------------------------------------ placing actions

	/**
	 * Puts an action (or "_" separator) into the CPM+ menu: submenuId = one of the SUB_* constants, or
	 * {@link #TOP} for the top level (before 'Ajuda'). Removed on unload.
	 */
	public static void add(String submenuId, Object item) {
		if (menu == null)throw new IllegalStateException("CPM+ menu not created yet");
		if (submenuId == null || submenuId.isEmpty()) {
			// top level, kept before the separator + 'Ajuda'
			int helpIndex = indexOfId(SUB_HELP);
			int pos = helpIndex > 0 ? helpIndex - 1 : menu.structure.length;
			menu.structure.splice(pos, 0, item);
			topItems.add(item);
			final Object it = item;
			PluginStart.cleanup.add(() -> {
				if (menu == null)return;
				int i = menu.structure.indexOf(it);
				if (i >= 0)menu.structure.splice(i, 1);
			});
			return;
		}
		if (indexOfId(submenuId) < 0)throw new IllegalArgumentException("unknown CPM+ submenu " + submenuId);
		SubMenu sm = Js.uncheckedCast(menu.structure.getAt(indexOfId(submenuId)));
		Object[] ch = sm.children;
		Js.<elemental2.core.JsArray<Object>>uncheckedCast(ch).push(item);
		final Object it = item;
		PluginStart.cleanup.add(() -> {
			elemental2.core.JsArray<Object> a = Js.uncheckedCast(sm.children);
			int i = a.indexOf(it);
			if (i >= 0)a.splice(i, 1);
		});
	}

	private static int indexOfId(String id) {
		if (menu == null || menu.structure == null)return -1;
		for (int i = 0; i < menu.structure.length; i++) {
			Object o = menu.structure.getAt(i);
			if (o != null && "object".equals(Js.typeof(o)) && id.equals(Js.asPropertyMap(o).get("id")))return i;
		}
		return -1;
	}

	/** creates the action and puts it into a submenu in one call */
	public static Action addAction(String submenuId, String id, String nameKey, String icon, Cond cond, Runnable click) {
		Action a = action(id, nameKey, icon, cond, null, click);
		add(submenuId, a);
		return a;
	}

	/**
	 * Adds an action to a Blockbench context menu (e.g. Group.prototype.menu, Cube.prototype.menu, Animation menu)
	 * at path ('' = end, index, or '&lt;submenu id&gt;'). Removed on unload.
	 */
	public static void addToContext(Menu protoMenu, Action action, String path) {
		if (protoMenu == null || action == null)return;
		protoMenu.addAction(action, path != null ? path : "");
		PluginStart.cleanup.add(() -> {
			try {
				protoMenu.removeAction(action);
				if (path != null && !path.isEmpty() && !path.matches("\\d+"))protoMenu.removeAction(path + "." + action.id);
			} catch (Throwable e) {
			}
		});
	}

	/** Adds an action to Blockbench's menu bar at path (e.g. 'file.export', 'animation'). Removed on unload. */
	public static void addToMenuBar(Action action, String path) {
		MenuBar.addAction(action, path);
		PluginStart.cleanup.add(() -> {
			try {
				MenuBar.removeAction(path + "." + action.id);
			} catch (Throwable e) {
			}
		});
	}

	/** the CPM+ bar menu (null before init / after unload) */
	public static BarMenu getMenu() {
		return menu;
	}
}
