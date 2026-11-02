package com.tom.cpm.blockbench.proxy;

import com.tom.ugwt.client.JsArrayE;

import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

/** CPM+ : Blockbench's global Animator (refresh the animation preview after keyframe edits) */
@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Animator_$$")
public class Animator {
	public static native void preview();

	// CPM+ (W0B)
	public static JsArrayE<Animation> animations;
	/** true while the Animate mode is open */
	public static boolean open;
	/** clears the per-channel last values used by the preview (call before sampling another animation) */
	public static native void resetLastValues();
	public static native void showDefaultPose(boolean noMatrixUpdate);
	/** deprecated in BB 5 (logs a warning, forwards to AnimationCodec.codecs.bedrock): feature-detect first */
	public static native Object loadFile(Object file, Object animationNames);
	public static native Object importFile(Object file, Object options);
	@JsProperty(name = "MolangParser")
	public static MolangParser molang;

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class MolangParser {
		public JsPropertyMap<Object> variables, global_variables;
		public native void resetVariables();
		public native double parse(Object expression, Object variables);
	}
}
