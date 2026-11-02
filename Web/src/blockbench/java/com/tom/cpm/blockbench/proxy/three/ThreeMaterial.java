package com.tom.cpm.blockbench.proxy.three;

import com.tom.cpm.blockbench.util.MaterialGroup;

import jsinterop.annotations.JsOverlay;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsProperty;
import jsinterop.annotations.JsType;
import jsinterop.base.JsPropertyMap;

@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_THREE.ShaderMaterial_$$")
public class ThreeMaterial {
	public JsPropertyMap<ThreeUniform<?>> uniforms;

	public int blendSrc, blendDst, blendSrcAlpha, blendDstAlpha, blending;

	@JsProperty(name = "cpm_materialGroup")
	public MaterialGroup materialGroup;

	@JsOverlay
	public final MaterialGroup getMaterialGroup() {
		if(materialGroup == null)materialGroup = new MaterialGroup(this);
		return materialGroup;
	}
}
