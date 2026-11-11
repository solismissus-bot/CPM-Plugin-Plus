package com.tom.cpm.blockbench.format;

import com.tom.cpl.math.MathHelper;
import com.tom.cpl.math.Vec3f;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.Canvas;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Dialog.FormInfoElement;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.TextureGenerator;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.java.JsBuilder;
import com.tom.cpm.web.client.util.I18n;

import elemental2.dom.BaseRenderingContext2D.FillStyleUnionType;
import elemental2.dom.CanvasRenderingContext2D;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLCanvasElement;
import jsinterop.annotations.JsPackage;
import jsinterop.annotations.JsType;
import jsinterop.base.Js;

public class ProjectGenerator {
	public static Dialog dialogNew;

	public static void initDialog() {
		Dialog.DialogProperties dctr = new Dialog.DialogProperties();
		dctr.id = "cpm_new_project";
		dctr.title = I18n.get("bb-label.newCPMProject");
		Dialog.FormVectorElement selRes = Dialog.FormVectorElement.make("dialog.project.texture_size", 64, 64);
		selRes.min = 64;
		selRes.max = 8192;
		Dialog.FormSelectElement selType = Dialog.FormSelectElement.make("dialog.skin.model");
		selType.value = "default";
		JsBuilder<String> skinTypes = new JsBuilder<>();
		for(SkinType st : SkinType.VANILLA_TYPES) {
			skinTypes.put(st.getName(), I18n.get("label.cpm.skin_type." + st.getName()));
		}
		selType.options = skinTypes.build();
		Dialog.FormFileElement file = Dialog.FormFileElement.make("dialog.skin.texture");
		file.extensions = new String[] {"png"};
		file.readtype = "image";
		file.filetype = "PNG";
		dctr.form = new JsBuilder<>().
				put("format", FormInfoElement.make("data.format", CPMCodec.format.name, I18n.get("bb-label.cpmCodecDesc"))).
				put("resolution", selRes).put("texture", file).
				put("type", selType).
				build();
		dctr.onConfirm = rIn -> {
			makeNewProject(Js.uncheckedCast(rIn));
			return true;
		};
		dialogNew = new Dialog(dctr);
		CPMCodec.format.new_ = () -> {
			newProject();
			return true;
		};
	}

	public static void newProject() {
		dialogNew.show();
	}

	public static void makeNewProject(DialogResult result) {
		if(Global.newProject(CPMCodec.format)) {
			Project.texture_width = MathHelper.clamp((int) result.resolution[0], 64, 8192);
			Project.texture_height = MathHelper.clamp((int) result.resolution[1], 64, 8192);
			SkinType type = SkinType.get(result.type);
			if(type == null || type == SkinType.UNKNOWN)type = SkinType.DEFAULT;
			// CPM+ : the chosen skin type is stored and used on export / Add 2nd layer / Add parts
			{
				ProjectData pd = Project.getData();
				pd.skinType = type.getName();
				pd.customSkinType = false;
				pd.flush();
			}
			for(PlayerModelParts part : PlayerModelParts.VALUES) {
				if(part == PlayerModelParts.CUSTOM_PART)continue;
				addPart(part, type);
			}
			Texture tex;
			if(result.texture != null && !result.texture.isEmpty()) {
				// CPM+ : the chosen PNG was never loaded (empty texture)
				Texture.TextureProperties c = new Texture.TextureProperties();
				c.mode = "bitmap";
				c.name = "skin";
				tex = new Texture(c);
				try {
					if(result.texture.startsWith("data:"))tex.fromDataURL(result.texture);
					else if(Global.isApp())tex.fromPath(result.texture);
				} catch (Throwable e) {
				}
				tex.add(false);
				tex.uv_width = Project.texture_width;
				tex.uv_height = Project.texture_height;
			} else {
				Texture.TextureProperties c = new Texture.TextureProperties();
				c.mode = "bitmap";
				c.name = "skin";
				tex = new Texture(c);
				HTMLCanvasElement canvas = Js.uncheckedCast(DomGlobal.document.createElement("canvas"));
				CanvasRenderingContext2D ctx = Js.uncheckedCast(canvas.getContext("2d"));
				canvas.width = Project.texture_width;
				canvas.height = Project.texture_height;
				Cube.all.forEach(cube -> {
					TextureGenerator.paintCubeBoxTemplate(cube, tex, canvas, null, false);
				});
				ctx.fillStyle = FillStyleUnionType.of("#cdefff");
				ctx.fillRect(9, 11, 2, 2);
				ctx.fillRect(13, 11, 2, 2);
				tex.fromDataURL(canvas.toDataURL()).add(false);
			}
			Cube.all.forEach(cube -> cube.applyTexture(tex, true));
			updateAll();
		}
		dialogNew.hide();
	}

	/** New project: root group + vanilla cube of a player part. The cube math is RootUtil.vanillaCubeSpec (W0C, shared). */
	public static Cube addPart(VanillaModelPart part, SkinType type) {
		PartValues pv = part.getDefaultSize(type);
		Group.GroupProperties pr = new Group.GroupProperties();
		pr.name = part.getName();
		pr.origin = JsVec3.make(0, 24, 0);
		Group gr = new Group(pr).init();
		pr = new Group.GroupProperties();
		Vec3f p = pv.getPos();
		pr.origin = JsVec3.make(-p.x, 24 - p.y, p.z);
		gr.extend(pr);
		gr.isOpen = true;
		RootUtil.CubeSpec spec = RootUtil.vanillaCubeSpec(part, type, gr.origin);
		Cube.CubeProperties c = new Cube.CubeProperties();
		c.name = part.getName();
		c.origin = gr.origin;
		c.from = spec.from;
		c.rotation = JsVec3.make(0, 0, 0);
		c.uv_offset = spec.uvOffset;
		Cube cube = new Cube(c);
		c = new Cube.CubeProperties();
		c.to = spec.to;
		cube.extend(c);
		cube.addTo(gr).init();
		return cube;
	}

	public static void updateAll() {
		Canvas.updateAllBones();
		Canvas.updateVisibility();
		// CPM+ : both put every part back to its rest pose / editor visibility. In Animate mode show the animation again,
		// as Blockbench does after its own updateAllBones (else e.g. Ctrl+Z of a tool showed the model at rest)
		if (CPMVisuals.isAnimateMode()) {
			CPMVisuals.invalidate();
			Animator.preview();
		}
		Global.setProjectTitle();
		Global.updateSelection();
	}

	@JsType(isNative = true, namespace = JsPackage.GLOBAL, name = "$$ugwt_m_Object_$$")
	public static class DialogResult {
		public float[] resolution;
		public String texture, type;
	}
}
