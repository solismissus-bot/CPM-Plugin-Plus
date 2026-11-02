package com.tom.cpm.blockbench.util;

import com.tom.cpm.blockbench.proxy.three.MeshBasicMaterial;
import com.tom.cpm.blockbench.proxy.three.MeshBasicMaterial.MeshBasicMaterialInit;
import com.tom.cpm.blockbench.proxy.three.ThreeColor;
import com.tom.cpm.blockbench.proxy.three.ThreeMaterial;

import elemental2.webgl.WebGLRenderingContext;
import jsinterop.base.Js;

public class MaterialGroup {
	private ThreeMaterial baseMaterial;
	private MeshBasicMaterial glowMat;
	private MeshBasicMaterial colorMat;
	private ThreeColor color;
	private boolean applyColor, colorOnly;

	public MaterialGroup(ThreeMaterial base) {
		this.baseMaterial = base;
		this.applyColor = false;
	}

	public ThreeMaterial getGlow() {
		if (glowMat == null) {
			MeshBasicMaterialInit mbmi = new MeshBasicMaterialInit();
			mbmi.alphaTest = 0.5f;
			mbmi.side = 2;
			mbmi.transparent = true;
			if (!colorOnly) {
				if (baseMaterial instanceof MeshBasicMaterial b) {
					mbmi.map = b.map;
				} else {
					mbmi.map = Js.uncheckedCast(baseMaterial.uniforms.get("map").value);
				}
			}
			MeshBasicMaterial m = new MeshBasicMaterial(mbmi);
			m.materialGroup = this;
			if (applyColor) {
				m.color = color;
			}
			glowMat = m;
			glowMat.blendSrc = WebGLRenderingContext.ONE;
			glowMat.blendDst = WebGLRenderingContext.ONE;
			glowMat.blending = 2;
		}
		if (!colorOnly) {
			if (baseMaterial instanceof MeshBasicMaterial b) {
				glowMat.map = b.map;
			} else {
				glowMat.map = Js.uncheckedCast(baseMaterial.uniforms.get("map").value);
			}
		}
		return glowMat;
	}

	public MeshBasicMaterial makeRecolor(ThreeColor color, boolean colorOnly) {
		MeshBasicMaterialInit mbmi = new MeshBasicMaterialInit();
		mbmi.alphaTest = 0.5f;
		mbmi.side = 2;
		mbmi.transparent = true;
		if (!colorOnly) {
			if (baseMaterial instanceof MeshBasicMaterial b)
				mbmi.map = b.map;
			else
				mbmi.map = Js.uncheckedCast(baseMaterial.uniforms.get("map").value);
		}
		MeshBasicMaterial m = new MeshBasicMaterial(mbmi);
		m.color = color;
		var mg = new MaterialGroup(baseMaterial);
		mg.applyColor = true;
		mg.color = color;
		mg.colorMat = m;
		mg.colorOnly = colorOnly;
		m.materialGroup = mg;
		return m;
	}

	public boolean isColor() {
		return applyColor;
	}

	public void setColor(ThreeColor color) {
		this.color = color;
		this.colorMat.color = color;
		if (this.glowMat != null)
			this.glowMat.color = color;
	}

	public ThreeMaterial getOriginal() {
		return baseMaterial;
	}

	public ThreeMaterial getNormal() {
		return applyColor ? colorMat : baseMaterial;
	}
}
