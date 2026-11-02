package com.tom.cpm.blockbench.proxy;

import com.tom.ugwt.client.JsArrayE;

import elemental2.dom.EventTarget;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Menu_$$")
public class Menu {
	public JsArrayE<Object> structure;

	public Menu(Object[] opt) {}

	public native void open(EventTarget target);

	// CPM+ (W0B): Blockbench 5 menu editing
	/** inserts an action (or "_") at path: '' = end, 'n' = index, '<submenu id>[.<index>]' = inside a submenu */
	public native void addAction(Object action, String path);
	/** removes an Action (top level), or the item at '<submenu id>.<item id>' */
	public native void removeAction(Object actionOrPath);
	public native void hide();
	public native void delete();

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_MenuSeparator_$$")
	public static class MenuSeparator {}
}
