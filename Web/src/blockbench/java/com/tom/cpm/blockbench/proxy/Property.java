package com.tom.cpm.blockbench.proxy;

import com.tom.cpm.blockbench.proxy.Action.Condition;

import javaemul.internal.annotations.DoNotAutobox;
import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Property_$$")
public class Property {

	private Property(Object mp, String type, String id, PropertyInit init) {}

	public Condition condition;
	// CPM+ (W0B)
	public String name, type, label;
	@JsProperty(name = "class")
	public Object owner;
	@JsProperty(name = "export")
	public boolean exportFlag;
	public boolean exposed;

	//UGWT won't replace unless in separate class
	@JsType(isNative = true, namespace = JsPackage.GLOBAL)
	private static class G {

		@JsProperty(name = "$$ugwt_m_ModelProject_$$")
		public static Object modelProject;

		@JsProperty(name = "$$ugwt_m_Group_$$")
		public static Object group;

		@JsProperty(name = "$$ugwt_m_Cube_$$")
		public static Object cube;

		@JsProperty(name = "$$ugwt_m_Locator_$$")
		public static Object locator;

		@JsProperty(name = "$$ugwt_m_Face_$$")
		public static Object face;

		@JsProperty(name = "$$ugwt_m_Texture_$$")
		public static Object texture;

		@JsProperty(name = "$$ugwt_m_Animation_$$")
		public static Object animation;

		@JsProperty(name = "$$ugwt_m_KeyframeDataPoint_$$")
		public static Object keyframedatapoint;
	}

	@JsOverlay
	public static Property createProperty(Clazz clz, Type type, String id, String label, @DoNotAutobox Object def, Condition cond, boolean hidden) {
		PropertyInit init = new PropertyInit();
		init.label = label;
		init.def = def;
		init.exposed = !hidden;
		init.condition = cond;
		return new Property(clz.obj, type.name().toLowerCase(), id, init);
	}

	/** CPM+ (W0B): Property with every option (export=false keeps it out of the saved .bbmodel) */
	@JsOverlay
	public static Property create(Clazz clz, Type type, String id, PropertyInit init) {
		return new Property(clz.obj, type.name().toLowerCase(), id, init);
	}

	public static enum Clazz {
		PROJECT(G.modelProject),
		GROUP(G.group),
		CUBE(G.cube),
		LOCATOR(G.locator),
		FACE(G.face),
		TEXTURE(G.texture),
		ANIMATION(G.animation),
		KEYFRAME_DATA(G.keyframedatapoint),
		;
		private Object obj;
		private Clazz(Object obj) {
			this.obj = obj;
		}

		/** CPM+ (W0B): the registered properties of this Blockbench class (name -> Property) */
		public JsPropertyMap<Property> properties() {
			Object p = jsinterop.base.Js.asPropertyMap(obj).get("properties");
			return p != null ? jsinterop.base.Js.uncheckedCast(p) : null;
		}
	}

	public static enum Type {
		STRING,
		MOLANG,
		NUMBER,
		BOOLEAN,
		ARRAY,
		INSTANCE,
		VECTOR,
		VECTOR2,
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class PropertyInit {
		public String label;

		@JsProperty(name = "default")
		public Object def;

		public Condition condition;
		public boolean exposed;
		// CPM+ (W0B): set to false only for properties that must not be saved into the .bbmodel
		@JsProperty(name = "export")
		public boolean exportFlag;
		public String description;
	}

	public native void delete();
	// CPM+ (W0B)
	public native Object getDefault(Object instance);
	public native void merge(Object instance, Object data);
	public native void copy(Object instance, Object target);
	public native void reset(Object instance);
}