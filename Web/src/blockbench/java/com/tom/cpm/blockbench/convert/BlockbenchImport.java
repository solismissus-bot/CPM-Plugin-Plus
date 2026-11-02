package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleUnaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Direction;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;
import com.tom.cpm.blockbench.proxy.Cube.FaceUV;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.KeyframeDataPoint;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec2;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.shared.animation.InterpolatorChannel;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.editor.project.JsonList;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;
import com.tom.cpm.shared.editor.project.loaders.AnimationsLoaderV1;
import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.DirectPartValues;
import com.tom.cpm.shared.model.render.DirectParts;
import com.tom.cpm.shared.model.render.PerFaceUV.Face;
import com.tom.cpm.shared.model.render.PerFaceUV.Rot;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.core.Uint8ClampedArray;
import elemental2.dom.CanvasRenderingContext2D;
import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLCanvasElement;
import elemental2.dom.ImageData;
import elemental2.promise.Promise;
import jsinterop.base.Js;

public class BlockbenchImport {
	private Map<TextureSheetType, Texture> textures = new HashMap<>();
	private Map<ModelElement, Group> me2group = new HashMap<>();
	private List<Runnable> postConvert = new ArrayList<>();
	private final Editor editor;
	private Function<List<WarnEntry>, Promise<Void>> openWarn;
	private List<WarnEntry> warnings = new ArrayList<>();
	private boolean copyTrWarn, importAnims, rscaleWarn;
	private Map<String, Integer> groupNames = new HashMap<>();
	private String wearerSkinHash;// CPM+
	private Map<TextureSheetType, String> sheetUrls;// CPM+ (load A8): PNG data URL of every sheet, encoded before newProject

	public BlockbenchImport(Editor editor, Function<List<WarnEntry>, Promise<Void>> openWarn) {
		this.editor = editor;
		this.openWarn = openWarn;
	}

	/** CPM+ (W0C): the imported CPM editor */
	public Editor getEditor() {
		return editor;
	}

	/** CPM+ (W0C): CPM element -> the Blockbench group created for it (for ImportHooks.afterImport) */
	public Map<ModelElement, Group> getElementGroups() {
		return java.util.Collections.unmodifiableMap(me2group);
	}

	/** CPM+ (W0C): true when the CPM animations were converted into Blockbench animations ('Import'), false = kept in CPM format */
	public boolean animationsImported() {
		return importAnims;
	}

	public Promise<Void> doImport() {
		for (ModelElement me : editor.elements) {
			// CPM+ : visible vanilla parts are no longer converted into custom cubes on export: the cube
			// created for them is marked as the vanilla part and exported as 'vanilla part shown'
			// (keeps the 2nd skin layer, skin type and vanilla behaviour). No warning needed.
			checkCompat(me.children);
		}
		if(rscaleWarn)
			warnings.add(new WarnEntry("CPM+: Some elements use a render scale (CPM 'Scale' effect). It is preserved on export but not previewed in Blockbench.").setInfo());

		Promise<Void> warns = !warnings.isEmpty() ? openWarn.apply(warnings) : Promise.resolve((Void) null);

		if(!editor.animations.isEmpty()) {
			warns = warns.then(__ -> new Promise<>((res, rej) -> {
				editor.ui.displayConfirm("Import Animations", "Convert CPM animations into Blockbench animations or keep CPM animations\\The animation importer is in Beta and may not import every animation correctly.\\Using the keep option will store the animations separately in the CPM format inside the bbmodel.", () -> {
					importAnims = true;
					res.onInvoke((Void) null);
				}, () -> res.onInvoke((Void) null), "Import", "Keep");
			}));
		}

		// CPM+ (load A8/E3): the texture sheets are encoded in the background first (status text), then the browser
		// paints, then newProject + doImport0 run in one synchronous step as before (with the encoded PNGs)
		return warns.then(__ -> {
			ImportProgress.phase(ImportProgress.TEXTURES);
			return RenderSystem.withContext(() -> {
				return encodeSheets();
			});
		}).then(urls -> {
			sheetUrls = urls;
			ImportProgress.phase(ImportProgress.BUILD);
			return ImportProgress.yieldFrame();
		}).then(__ -> RenderSystem.withContext(() -> {
			Global.newProject(CPMCodec.format);
			doImport0();
			return Promise.resolve((Void) null);
		}));
	}

	/**
	 * CPM+ (load A8): every texture sheet as a PNG data URL, encoded with canvas.toBlob (no long freeze), in
	 * editor.textures order; the canvas is drawn exactly like v0.7's import. A sheet whose async encode fails gets the
	 * old synchronous canvas.toDataURL() (same pixels either way: the same canvas, the same PNG encoder).
	 */
	private Promise<Map<TextureSheetType, String>> encodeSheets() {
		List<TextureSheetType> keys = new ArrayList<>();
		List<Promise<String>> enc = new ArrayList<>();
		for (Map.Entry<TextureSheetType, ETextures> e : editor.textures.entrySet()) {
			// an unedited skin (the wearer's own skin) keeps v0.7's synchronous toDataURL(): its data URL is hashed
			// (wearerSkinHash) and Blockbench may re-encode it with toDataURL later (.bbmodel save / reopen); toBlob
			// writes other PNG bytes (same pixels). It is the editor's small default skin.
			if(e.getKey() == TextureSheetType.SKIN && !e.getValue().isEdited())continue;
			keys.add(e.getKey());
			enc.add(encodePng(sheetCanvas(e.getValue().provider.getImage())));
		}
		Promise<Object[]> all = Promise.all(Js.cast(enc.toArray(new Promise[0])));
		return all.then(r -> {
			JsArray<Object> urls = Js.uncheckedCast(r);// a plain JS array
			Map<TextureSheetType, String> m = new HashMap<>();
			for (int i = 0; i < keys.size() && i < urls.length; i++) {
				Object u = urls.getAt(i);
				if(u != null)m.put(keys.get(i), Js.asString(u));
			}
			return Promise.resolve(m);
		});
	}

	/** the canvas of v0.7's import (putImageData of the CPM image) */
	private static HTMLCanvasElement sheetCanvas(Image img) {
		HTMLCanvasElement canvas = Js.uncheckedCast(DomGlobal.document.createElement("canvas"));
		canvas.width = img.getWidth();
		canvas.height = img.getHeight();
		CanvasRenderingContext2D ctx = Js.uncheckedCast(canvas.getContext("2d"));
		Uint8ClampedArray i = new Uint8ClampedArray(img.getData().buffer);
		ImageData im = new ImageData(i, img.getWidth(), img.getHeight());
		ctx.putImageData(im, 0, 0);
		return canvas;
	}

	/** data URL of the canvas: toBlob('image/png') + FileReader; canvas.toDataURL() when that is not possible */
	private static native Promise<String> encodePng(HTMLCanvasElement c)/*-{
		return new $wnd.Promise(function(res) {
			var done = false;
			var finish = function(u) {
				if (done) return;
				done = true;
				res(u);
			};
			var sync = function() {
				try {
					finish(c.toDataURL());
				} catch (e) {
					finish(null);
				}
			};
			try {
				if (typeof c.toBlob !== 'function' || typeof $wnd.FileReader !== 'function') {
					sync();
					return;
				}
				c.toBlob(function(b) {
					if (!b) {
						sync();
						return;
					}
					try {
						var fr = new $wnd.FileReader();
						fr.onload = function() {
							var r = fr.result;
							if (typeof r === 'string' && r.indexOf('data:image/png;base64,') === 0) finish(r);
							else sync();
						};
						fr.onerror = sync;
						fr.onabort = sync;
						fr.readAsDataURL(b);
					} catch (e) {
						sync();
					}
				}, 'image/png');
			} catch (e) {
				sync();
			}
		});
	}-*/;

	private void checkCompat(List<ModelElement> children) {
		for (ModelElement me : children) {

			if(me.copyTransform != null && !copyTrWarn) {
				warnings.add(new WarnEntry(I18n.format("bb-label.warn.copyTransformNotVis")));
				copyTrWarn = true;
			}
			if(me.scale != null && (me.scale.x != 1 || me.scale.y != 1 || me.scale.z != 1))rscaleWarn = true;// CPM+

			checkCompat(me.children);
		}
	}

	private void doImport0() {
		Vec2i tex = editor.textures.get(TextureSheetType.SKIN).provider.size;
		Project.texture_width = tex.x;
		Project.texture_height = tex.y;
		Project.box_uv = true;

		editor.textures.entrySet().forEach(e -> {
			Texture.TextureProperties c = new Texture.TextureProperties();
			c.mode = "bitmap";
			c.name = e.getKey().name().toLowerCase();
			Texture t = new Texture(c);
			// CPM+ (load A8): encoded before newProject (encodeSheets); the old synchronous encode stays the fallback
			String dataUrl = sheetUrls != null ? sheetUrls.get(e.getKey()) : null;
			if(dataUrl == null)dataUrl = sheetCanvas(e.getValue().provider.getImage()).toDataURL();
			t.fromDataURL(dataUrl).add(false);
			textures.put(e.getKey(), t);
			t.uv_width = e.getValue().provider.size.x;
			t.uv_height = e.getValue().provider.size.y;
			// CPM+ : an unedited skin is the wearer's own skin (the image is only the editor's default
			// skin). Remember it so export doesn't bake it into the model unless it was painted on.
			if(e.getKey() == TextureSheetType.SKIN && !e.getValue().isEdited())
				wearerSkinHash = ProjectData.hash(dataUrl);
		});

		for (ModelElement me : editor.elements) {
			VanillaModelPart part = (VanillaModelPart) me.typeData;
			PartValues pv = DirectParts.getPartOverrides(part, editor.skinType);
			Vec3f pos = pv.getPos().add(me.pos);
			Vec3f rot = me.rotation;
			if(part.getCopyFrom() != null) {
				ModelElement c = findElementById(editor, part.getCopyFrom());
				if(c != null) {
					pos = pos.add(c.pos);
					rot = rot.add(c.rotation);
				}
			} else if(pv instanceof DirectPartValues) {
				rot = rot.add(((DirectPartValues)pv).getRotation());
			}
			String name = part.getName();
			if(me.duplicated) {
				name += "_dup";
			}
			GroupProperties gp = new GroupProperties();
			gp.name = name;
			gp.origin = JsVec3.make(0, 24, 0);
			gp.rotation = JsVec3.make(-rot.x, -rot.y, rot.z);
			Group gr = new Group(gp).init();
			gr.disableVanillaAnim = me.disableVanillaAnim;
			gp = new GroupProperties();
			gp.origin = JsVec3.make(-pos.x, 24 - pos.y, pos.z);
			gr.extend(gp);
			me2group.put(me, gr);
			// CPM+ : root data the official plugin dropped (renamed roots, storeID of duplicated /
			// custom roots, locked, name colour)
			{
				GroupData gd = gr.getData();
				if(me.name != null && !me.name.isEmpty() && !me.name.equals(part.getName()))gd.setRootName(me.name);
				if(me.duplicated || part instanceof RootModelType)gd.setStoreID(me.storeID);
				if(me.locked)gd.setLocked(true);
				if(me.partNameColor != 0)gd.setNameColor(me.partNameColor);
				gd.flush();
			}
			ImportHooks.fireElement(me, gr);// CPM+ (W0C)

			TextureSheetType tst = part instanceof RootModelType ? RootGroups.getGroup((RootModelType) part).getTexSheet((RootModelType) part) : TextureSheetType.SKIN;

			if(!me.hidden) {
				Cube.CubeProperties c = new Cube.CubeProperties();
				c.name = part.getName();
				c.origin = gr.origin;
				Vec3f o = pv.getOffset();
				Vec3f s = pv.getSize();
				c.from = JsVec3.make(gr.origin.x - o.x - s.x, gr.origin.y - o.y - s.y, gr.origin.z + o.z);
				c.rotation = JsVec3.make(0, 0, 0);
				c.uv_offset = JsVec2.make(pv.getUV());
				// CPM+ : armor / elytra parts are mirrored and inflated (same as BBActions.addRoot)
				c.mirror_uv = pv.isMirror();
				c.inflate = pv.getMCScale();
				Cube cube = new Cube(c);
				c = new Cube.CubeProperties();
				c.to = JsVec3.make(cube.from.x + s.x, cube.from.y + s.y, cube.from.z + s.z);
				cube.extend(c);
				cube.mirror_uv = pv.isMirror();
				cube.inflate = pv.getMCScale();
				cube.box_uv = true;
				cube.addTo(gr).init();

				cube.applyTexture(textures.get(tst), true);
				// CPM+ : this cube IS the vanilla part. Export keeps the vanilla part visible instead of
				// replacing it with a custom cube (which lost the 2nd layer) as long as it's unchanged.
				cube.getData().setVanilla(true).flush();
				ImportHooks.fireElement(me, cube);// CPM+ (W0C)
			}

			importChildren(me.children, gr, tst);
		}

		ProjectData pd = Project.getData();

		ImportHooks.fireBeforeAnims(editor);// CPM+ (W0C): once per import, before the animations are converted / kept
		if(!editor.animations.isEmpty()) {
			if(importAnims)editor.animations.forEach(this::convertAnim);
			else new AnimEnc().write(pd.animations);
		}
		// CPM+ (F24): the skin layers (anim_enc.json) were dropped when the animations were imported (or absent)
		if(importAnims || editor.animations.isEmpty())new AnimEnc().writeLayers(pd.animations);

		pd.scalingOpt.putAll(editor.scalingElem.scaling);
		pd.renderPos = editor.scalingElem.pos;
		pd.renderRot = editor.scalingElem.rotation;
		pd.renderScl = editor.scalingElem.scale;
		// CPM+ : the 'Scaling' checkbox was never carried, export always disabled scaling (#445)
		pd.scalingEnabled = editor.scalingElem.enabled;
		// CPM+ : skin type (slim/default) + whether the model forces it
		if(editor.skinType != null) {
			pd.skinType = editor.skinType.getName();
			pd.customSkinType = editor.customSkinType;
		}
		pd.wearerSkinHash = wearerSkinHash;
		for(TextureSheetType tx : TextureSheetType.VALUES) {
			ETextures eTex = editor.textures.get(tx);
			if(eTex != null && eTex.isEditable())pd.customGrid.put(tx, eTex.customGridSize);
		}
		// CPM+ : keep what the plugin can't edit (model icon, tags, templates) byte for byte
		storeRaw(pd, "desc_icon.png");
		storeRaw(pd, "templates.json");
		storeRaw(pd, "template_settings.json");
		try {
			List<String> tags = editor.project.listEntires("tags");
			if(tags != null)tags.forEach(t -> storeRaw(pd, "tags/" + t));
		} catch (Throwable e) {
		}

		pd.leftHandPos = editor.leftHandPos;
		pd.rightHandPos = editor.rightHandPos;
		pd.modelId = editor.modelId;
		pd.removeArmorOffset = editor.removeArmorOffset;
		Project.hideHeadIfSkull = editor.hideHeadIfSkull;
		Project.removeBedOffset = editor.removeBedOffset;
		Project.invisGlow = editor.enableInvisGlow;

		for(TextureSheetType tx : TextureSheetType.VALUES) {
			ETextures eTex = editor.textures.get(tx);
			if(eTex != null) {
				if(eTex.isEditable()) {
					Map<String, Object> t = new HashMap<>();
					JsonMap map = new JsonMapImpl(t);
					JsonList l = map.putList("anim");
					eTex.animatedTexs.forEach(v -> v.save(l.addMap()));
					if(l.size() != 0) {
						pd.animTex.put(tx, t);
					}
				}
			}
		}

		pd.description = editor.description;

		pd.flush();

		postConvert.forEach(Runnable::run);

		ProjectGenerator.updateAll();
		ImportHooks.fireAfterImport(editor, this);// CPM+ (W0C): once per import, at the very end
	}

	private class AnimEnc extends AnimationsLoaderV1 {

		public void write(Map<String, Object> an) {
			Map<String, Object> l = new HashMap<>();
			for (EditorAnim e : editor.animations) {
				Map<String, Object> d = new HashMap<>();
				l.put(e.filename, d);
				JsonMap data = new JsonMapImpl(d);
				writeAnimation(e, data);
			}
			an.put("anims", l);
			writeLayers(an);
		}

		/** CPM+ (F24): anim_enc.json (free layers + default visibility) into the project data */
		public void writeLayers(Map<String, Object> an) {
			if(editor.animEnc != null) {
				// CPM+ (F24): unknown layer names in the file (null) are skipped instead of failing the import
				an.put("free", editor.animEnc.freeLayers.stream().filter(v -> v != null).map(v -> v.getLowerName()).collect(Collectors.toList()));
				an.put("def", editor.animEnc.defaultLayerValue.entrySet().stream().filter(e -> e.getKey() != null && e.getValue() != null).map(e -> Pair.of(e.getKey().getLowerName(), e.getValue())).collect(Collectors.toMap(Pair::getKey, Pair::getValue)));
			}
		}

		@Override
		protected void writePartRef(Map<String, Object> map, ModelElement me) {
			map.put("uuid", me2group.get(me).uuid);
		}
	}

	private Animation convertAnim(EditorAnim ea) {
		Animation a = new Animation().add(false);
		Animation.selected = a;
		a.name = ea.displayName;
		if (ea.type.isStaged()) {
			a.loop = "once";
		} else if (ea.type.isLayer() || ea.pose != null)a.loop = "loop";
		else a.loop = ea.loop ? "loop" : "once";
		a.type = Animation.makeType(ea.type, ea.pose);
		a.additive = ea.add;
		a.commandCtrl = ea.command;
		a.layerCtrl = ea.layerControlled;
		a.setPriority(ea.priority);
		a.setOrder(ea.order);
		a.group = ea.group;
		a.setLayerDefault(ea.layerDefault);
		a.isProperty = ea.isProperty;

		// ------------------------------------------------------------------
		// CPM+ : rewritten keyframe import.
		//  * CPM frames are placed on CPM's real time grid:
		//      LINEAR_SINGLE / TRIG_SINGLE : t_k = k*D/(N-1)   (last frame exactly at D)
		//      everything else             : t_k = k*D/N
		//    *_LOOP types get a closing key at t=D equal to frame 0 (rotation unwrapped like the
		//    CPM runtime) so Blockbench loops seamlessly; POLY_SINGLE gets a closing key at D equal
		//    to the last frame (CPM's padded spline holds it). N==1 -> one key at t=0.
		//  * Keys are only created for channel groups the element actually animates (same test as
		//    CPM's AnimationExporter). No scale/visibility/colour keys on vanilla root parts, no colour
		//    keys on textured non-recolor cubes, visibility keys only where the value changes.
		//  * Colour is already 0..255 in CPM (old code multiplied by 255 again).
		//  * Keyframe interpolation comes from the CPM interpolator (linear / catmullrom / step).
		//  * Exact key times are written (not snapped) and the original CPM metadata is stored on the
		//    animation, so export can rebuild exactly the same N frames (no resampling).
		// ------------------------------------------------------------------
		List<AnimFrame> fr = ea.getFrames();
		int n = fr.size();
		int durMs = ea.duration > 0 ? ea.duration : 1000;
		float dur = durMs / 1000f;
		a.length = dur;
		InterpolatorType it = ea.intType != null ? ea.intType : InterpolatorType.POLY_LOOP;
		a.setCpmIntType(it);
		a.setCpmFrames(n);
		a.setCpmDuration(durMs);
		a.setMustFinish(ea.mustFinish);
		a.setAnimHidden(ea.hidden);
		a.setMaxValue(ea.maxValue);
		a.setInterpolateVal(ea.interpolateValue);

		if(n == 0) {
			a.snapping = 20;
			Animation.selected = null;
			return a;
		}

		boolean singleGrid = n > 1 && (it == InterpolatorType.LINEAR_SINGLE || it == InterpolatorType.TRIG_SINGLE);
		boolean closeLoop = n > 1 && (it == InterpolatorType.POLY_LOOP || it == InterpolatorType.LINEAR_LOOP || it == InterpolatorType.TRIG_LOOP);
		boolean closeHold = n > 1 && it == InterpolatorType.POLY_SINGLE;
		float step = singleGrid ? dur / (n - 1) : dur / n;
		int nKeys = n + (closeLoop || closeHold ? 1 : 0);
		float[] times = new float[nKeys];
		for (int k = 0; k < n; k++)times[k] = k * step;
		if(nKeys > n)times[n] = dur;
		a.snapping = pickSnapping(step);
		String interp = interpolationFor(it);

		for (ModelElement me : ea.getComponentsFiltered()) {
			Group g = me2group.get(me);
			if(g == null)continue;
			boolean root = me.type == ElementType.ROOT_PART;
			boolean anyPos = fr.stream().anyMatch(f -> f.hasPosChanges(me));
			boolean anyRot = fr.stream().anyMatch(f -> f.hasRotChanges(me));
			boolean anyScl = !root && fr.stream().anyMatch(f -> f.hasScaleChanges(me));
			boolean anyCol = !root && (me.recolor || !me.texture) && fr.stream().anyMatch(f -> f.hasColorChanges(me));
			boolean anyVis = !root && fr.stream().anyMatch(f -> f.hasVisChanges(me));
			if(!anyPos && !anyRot && !anyScl && !anyCol && !anyVis)continue;

			BoneAnimator be = new BoneAnimator(g.uuid, a, g.name);// CPM+ (G7): the part name travels with the keys (BB 5 '_name')
			a.animators.set(g.uuid, be);

			float[][] v = new float[InterpolatorChannel.VALUES.length][];
			for (InterpolatorChannel channel : InterpolatorChannel.VALUES) {
				float[] raw = AnimFrame.toArray(ea, me, channel);
				// NO_INTERPOLATE does not unwrap rotations in CPM either
				DoubleUnaryOperator setup = it == InterpolatorType.NO_INTERPOLATE ? (d -> d) : channel.createInterpolatorSetup();
				float[] out = new float[nKeys];
				for (int k = 0; k < n; k++)out[k] = (float) setup.applyAsDouble(raw[k]);
				if(closeLoop)out[n] = (float) setup.applyAsDouble(raw[0]);
				else if(closeHold)out[n] = out[n - 1];
				v[channel.ordinal()] = out;
			}

			Vec3f addPos = me.pos;
			Vec3f addRot = me.rotation;
			if(!ea.add && root) {
				VanillaModelPart part = (VanillaModelPart) me.typeData;
				PartValues pv = DirectParts.getPartOverrides(part, editor.skinType);
				if(pv instanceof DirectPartValues) {
					addRot = addRot.add(((DirectPartValues)pv).getRotation());
				}
				addPos = addPos.add(pv.getPos());
			}

			for (int k = 0; k < nKeys; k++) {
				float t = times[k];
				if(anyPos) {
					Vec3f pos = new Vec3f(v[InterpolatorChannel.POS_X.ordinal()][k], v[InterpolatorChannel.POS_Y.ordinal()][k], v[InterpolatorChannel.POS_Z.ordinal()][k]);
					if(!ea.add)pos = pos.sub(addPos);
					pos.x *= -1;
					pos.y *= -1;
					Keyframe kf = makeKey(be, t, "position", interp);
					if(kf != null)kf.data_points.getAt(0).set(pos);
				}
				if(anyRot) {
					Vec3f rot = new Vec3f(
							(float) Math.toDegrees(v[InterpolatorChannel.ROT_X.ordinal()][k]),
							(float) Math.toDegrees(v[InterpolatorChannel.ROT_Y.ordinal()][k]),
							(float) Math.toDegrees(v[InterpolatorChannel.ROT_Z.ordinal()][k])
							);
					if(!ea.add)rot = rot.sub(addRot);
					rot.x *= -1;
					rot.y *= -1;
					Keyframe kf = makeKey(be, t, "rotation", interp);
					if(kf != null)kf.data_points.getAt(0).set(rot);
				}
				if(anyScl) {
					Vec3f scl = new Vec3f(v[InterpolatorChannel.SCALE_X.ordinal()][k], v[InterpolatorChannel.SCALE_Y.ordinal()][k], v[InterpolatorChannel.SCALE_Z.ordinal()][k]);
					Keyframe kf = makeKey(be, t, "scale", interp);
					if(kf != null)kf.data_points.getAt(0).set(scl);
				}
				if(anyCol) {
					Keyframe kf = makeKey(be, t, CPMCodec.COLOR, interp);
					if(kf != null) {
						KeyframeDataPoint dc = kf.data_points.getAt(0);
						dc.x = clampColor(v[InterpolatorChannel.COLOR_R.ordinal()][k]);
						dc.y = clampColor(v[InterpolatorChannel.COLOR_G.ordinal()][k]);
						dc.z = clampColor(v[InterpolatorChannel.COLOR_B.ordinal()][k]);
					}
				}
			}

			if(anyVis) {
				Boolean prev = null;
				for (int k = 0; k < n; k++) {
					FrameData dt = fr.get(k).getComponents().get(me);
					// deterministic fallback (old code read the live render state)
					boolean vis = dt == null ? !me.hidden : dt.isVisible();
					if(prev == null || prev.booleanValue() != vis) {
						Keyframe kf = makeKey(be, times[k], CPMCodec.VISIBILITY, "step");
						if(kf != null)kf.data_points.getAt(0).setVisible(vis);
					}
					prev = vis;
				}
			}
		}

		Animation.selected = null;
		return a;
	}

	/**
	 * CPM+ (D4): converts one animation of another (headless) CPM editor onto groups of the open project, with the same
	 * code as the import. map: the other file's elements -> the open project's groups (unmapped parts are skipped).
	 */
	public static Animation importAnim(Editor other, EditorAnim ea, Map<ModelElement, Group> map) {
		BlockbenchImport bi = new BlockbenchImport(other, null);
		bi.me2group.putAll(map);
		return bi.convertAnim(ea);
	}

	// CPM+ : create a keyframe at an exact (unsnapped) time with the given interpolation
	private static Keyframe makeKey(BoneAnimator be, float time, String channel, String interp) {
		// CPM+ : batched creation without Blockbench's per-key side effects (freeze on import, BB #3287)
		return be.addKeyframeFast(time, channel, interp);
	}

	private static int clampColor(float v) {
		int c = Math.round(v);
		return c < 0 ? 0 : c > 255 ? 255 : c;
	}

	/**
	 * CPM+ : Blockbench keyframe interpolation for a CPM interpolator.
	 * POLY (natural cubic spline) and TRIG (sine ease-out) have no exact Blockbench equivalent;
	 * 'catmullrom' (Blockbench "smooth") is the closest. The original type is stored on the
	 * animation (cpm_intType) so export restores it as long as the keys are still smooth.
	 */
	public static String interpolationFor(InterpolatorType it) {
		switch (it) {
		case LINEAR_LOOP:
		case LINEAR_SINGLE:
			return "linear";
		case NO_INTERPOLATE:
			return "step";
		case POLY_LOOP:
		case POLY_SINGLE:
		case TRIG_LOOP:
		case TRIG_SINGLE:
		default:
			return "catmullrom";
		}
	}

	/** CPM+ : smallest snapping (fps, 1..500) whose grid contains every multiple of 'step' seconds. */
	public static int pickSnapping(float step) {
		if(!(step > 0))return 20;
		double fps = 1.0 / step;
		int start = Math.max(1, (int) Math.floor(fps + 1e-6));
		for (int s = start; s <= 500; s++) {
			double v = s * (double) step;
			if(Math.abs(v - Math.round(v)) < 1e-3)return s;
		}
		long r = Math.round(fps);
		return (int) Math.max(1, Math.min(500, r));
	}

	private ModelElement findElementById(Editor editor, VanillaModelPart copyFrom) {
		for(ModelElement me : editor.elements) {
			if(me.typeData == copyFrom && !me.duplicated)return me;
		}
		return null;
	}

	private void importChildren(List<ModelElement> children, Group parent, TextureSheetType tst) {
		for (ModelElement me : children) {
			Group gr;
			Cube cube;

			GroupProperties gp = new GroupProperties();
			int d = groupNames.merge(me.name, 1, Integer::sum) - 1;
			gp.name = me.name + (d > 0 ? d : "");
			gp.origin = JsVec3.make(0, 24, 0);
			gr = new Group(gp).init();
			gp = new GroupProperties();
			gp.origin = JsVec3.make(-me.pos.x, 24 - me.pos.y, me.pos.z);
			gr.extend(gp);
			gr.origin.V3_add(parent.origin);
			gr.origin.y -= 24;
			gr.addTo(parent);
			gp = new GroupProperties();
			gp.rotation = JsVec3.make(-me.rotation.x, -me.rotation.y, me.rotation.z);
			gr.extend(gp);
			gr.visibility = me.showInEditor;
			gr.hidden = me.hidden;
			me2group.put(me, gr);
			if(me.copyTransform != null) {
				postConvert.add(() -> {
					Map<String, Object> s = me.copyTransform.toMap();
					// CPM+ (B1): 'copy colour' under both keys (the game reads 'r', CPM's editor writes 'color')
					s.put("color", me.copyTransform.copyColor);
					s.put("r", me.copyTransform.copyColor);
					Group g = me2group.get(me.copyTransform.from);
					if(g != null) {
						s.remove("storeID");
						s.put("uuid", g.uuid);
						gr.copyTransform = JsonUtil.toJson(s);
					}
				});
			}
			GroupData gd = gr.getData();
			if(me.itemRenderer != null) {
				gd.setItemRenderer(me.itemRenderer.slot.name().toLowerCase());
				// CPM+ : for item renderers meshScale is the item scale (not baked into a cube size)
				gd.setItemScale(me.meshScale);
			}
			// CPM+ : lossless round trip of fields without a Blockbench counterpart
			gd.setStoreID(me.storeID);
			gd.setRenderScale(me.scale);
			if(me.locked)gd.setLocked(true);
			if(me.partNameColor != 0)gd.setNameColor(me.partNameColor);
			gd.flush();
			ImportHooks.fireElement(me, gr);// CPM+ (W0C)

			Cube.CubeProperties c = new Cube.CubeProperties();
			c.name = me.name;
			Vec3f o = me.offset;
			Vec3f s = new Vec3f(me.size.x * me.meshScale.x, me.size.y * me.meshScale.y, me.size.z * me.meshScale.z);
			c.from = JsVec3.make(gr.origin.x - o.x - s.x, gr.origin.y - o.y - s.y, gr.origin.z + o.z);
			c.inflate = me.mcScale;
			c.mirror_uv = me.mirror;
			c.uv_offset = JsVec2.make(me.u, me.v);
			c.visibility = me.showInEditor;
			cube = new Cube(c);
			cube.glow = me.glow;
			cube.extrude = me.extrude;
			cube.setRecolor(!me.texture || me.recolor ? me.rgb : -1);
			c = new Cube.CubeProperties();
			c.to = JsVec3.make(cube.from.x + s.x, cube.from.y + s.y, cube.from.z + s.z);
			cube.extend(c);

			// CPM+ : CPM's box layout uses ceil(size); Blockbench's box UV truncates fractional sizes,
			// so any fractional size (not only < 1) is converted to the equivalent per-face UV.
			importTextureUV(me, cube, s.x < 1 || s.y < 1 || s.z < 1 || isFrac(me.size.x) || isFrac(me.size.y) || isFrac(me.size.z));

			cube.addTo(gr).init();
			if(me.texture) {
				cube.applyTexture(textures.get(tst), true);
				// CPM+ : faces that don't exist in CPM (missing per-face UV / unused extrude faces) are
				// hidden in Blockbench too instead of showing a stretched pixel
				if(!cube.box_uv) {
					cube.faces.forEach(f -> {
						if(f.uv == null || f.uv.isEmpty())f.disable();
					});
				}
			} else {
				// CPM+ : colour-only element: no texture at all, so Blockbench treats it as a colour
				// cube (isColorCube) and export writes texture=false again (was textured + tinted)
				cube.box_uv = true;
				cube.faces.forEach(Cube.CubeFace::setBlank);
			}

			CubeData cd = cube.getData();

			if(me.extrude) {
				cd.setExtrude(true).setU(me.u).setV(me.v).setTs(me.textureSize);
			}
			cd.flush();
			ImportHooks.fireElement(me, cube);// CPM+ (W0C)

			importChildren(me.children, gr, tst);
		}
	}

	private void importTextureUV(ModelElement me, Cube cube, boolean forcePF) {
		if(!me.texture)return;
		if(me.faceUV != null || forcePF || me.textureSize > 1 || me.singleTex || me.extrude || me.meshScale.x != 1 || me.meshScale.y != 1 || me.meshScale.z != 1) {
			cube.box_uv = false;
			cube.autouv = 0;
			if(me.faceUV != null) {
				for (Direction d : Direction.VALUES) {
					Face f = me.faceUV.faces.get(d);
					CubeFace cf = cube.faces.getFace(d);
					if(f != null) {
						cf.uv = makeFaceUV(f.sx, f.sy, f.ex, f.ey);
						if(d == Direction.UP || d == Direction.DOWN)
							cf.rotation = (rotToInt(f.rotation) + 180) % 360;
						else
							cf.rotation = rotToInt(f.rotation);
					} else {
						cf.uv = makeFaceUV(0, 0, 0, 0);
					}
				}
			} else if(me.singleTex || me.extrude) {
				if ((me.mcScale == 0 || me.extrude) && (me.size.x == 0 || me.size.y == 0 || me.size.z == 0)) {
					float texU = me.u * me.textureSize;
					float texV = me.v * me.textureSize;
					if (me.size.x == 0) {
						float tu = texU + ProjectConvert.ceil(me.size.z * me.textureSize);
						float tv = texV + ProjectConvert.ceil(me.size.y * me.textureSize);
						for (Direction d : Direction.VALUES) {
							CubeFace face = cube.faces.getFace(d);
							if (d == Direction.WEST) {
								face.uv = makeFaceUV(texU, texV, tu, tv);
							} else if (d == Direction.EAST) {
								face.uv = makeFaceUV(tu, texV, texU, tv);
							} else {
								face.uv = makeFaceUV(0, 0, 0, 0);
							}
						}
					} else if (me.size.y == 0) {
						float tu = texU + ProjectConvert.ceil(me.size.x * me.textureSize);
						float tv = texV + ProjectConvert.ceil(me.size.z * me.textureSize);
						for (Direction d : Direction.VALUES) {
							CubeFace face = cube.faces.getFace(d);
							if (d == Direction.UP) {
								face.uv = makeFaceUV(tu, texV, texU, tv);
							} else if (d == Direction.DOWN) {
								face.uv = makeFaceUV(texU, texV, tu, tv);
							} else {
								face.uv = makeFaceUV(0, 0, 0, 0);
							}
						}
					} else if (me.size.z == 0) {
						float tu = texU + ProjectConvert.ceil(me.size.x * me.textureSize);
						float tv = texV + ProjectConvert.ceil(me.size.y * me.textureSize);
						for (Direction d : Direction.VALUES) {
							CubeFace face = cube.faces.getFace(d);
							if (d == Direction.NORTH) {
								face.uv = makeFaceUV(texU, texV, tu, tv);
							} else if (d == Direction.SOUTH) {
								face.uv = makeFaceUV(tu, texV, texU, tv);
							} else {
								face.uv = makeFaceUV(0, 0, 0, 0);
							}
						}
					}
				} else {
					float size = Math.max(me.size.x, Math.max(me.size.y, me.size.z));
					for (Direction d : Direction.VALUES) {
						CubeFace face = cube.faces.getFace(d);
						face.uv.sx = me.u * me.textureSize;
						face.uv.sy = me.v * me.textureSize;
						face.uv.ex = (me.u + size) * me.textureSize;
						face.uv.ey = (me.v + size) * me.textureSize;
					}
				}
				if (me.mirror) {
					for (Direction d : Direction.VALUES) {
						CubeFace face = cube.faces.getFace(d);
						float t = face.uv.sx;
						face.uv.sx = face.uv.ex;
						face.uv.ex = t;
					}
					FaceUV uv = cube.faces.east.uv;
					cube.faces.east.uv = cube.faces.west.uv;
					cube.faces.west.uv = uv;
				}
			} else {
				boxToPFUV(cube, me.textureSize, me.meshScale.x, me.meshScale.y, me.meshScale.z);
			}
		} else {
			cube.box_uv = true;
		}
	}

	// CPM+
	private static boolean isFrac(float v) {
		return Math.abs(v - Math.round(v)) > 1e-4f;
	}

	// CPM+ : copy a raw .cpmproject entry into the project data (base64)
	private void storeRaw(ProjectData pd, String path) {
		try {
			byte[] b = editor.project.getEntry(path);
			if(b != null)pd.rawFiles.put(path, Base64.getEncoder().encodeToString(b));
		} catch (Throwable e) {
		}
	}

	private static FaceUV makeFaceUV(float sx, float sy, float ex, float ey) {
		return FaceUV.make(sx, sy, ex, ey);
	}

	private static int rotToInt(Rot r) {
		switch (r) {
		case ROT_0: return 0;
		case ROT_180: return 180;
		case ROT_270: return 270;
		case ROT_90: return 90;
		default: throw new RuntimeException();
		}
	}

	public static void boxToPFUV(Cube cube, int texSc, float scX, float scY, float scZ) {
		Vec3f d = cube.to.toVecF().sub(cube.from.toVecF()).mul(texSc);
		d.mul(scX == 0 ? 1 : 1f / scX, scY == 0 ? 1 : 1f / scY, scZ == 0 ? 1 : 1f / scZ);
		float dx = ProjectConvert.ceil(d.x);
		float dy = ProjectConvert.ceil(d.y);
		float dz = ProjectConvert.ceil(d.z);
		float u = ProjectConvert.floor(cube.uv_offset.x * texSc);
		float v = ProjectConvert.floor(cube.uv_offset.y * texSc);
		float f4 = u;
		float f5 = u + dz;
		float f6 = u + dz + dx;
		float f7 = u + dz + dx + dx;
		float f8 = u + dz + dx + dz;
		float f9 = u + dz + dx + dz + dx;
		float f10 = v;
		float f11 = v + dz;
		float f12 = v + dz + dy;
		cube.faces.up.uv = makeFaceUV(f6, f11, f5, f10);
		cube.faces.down.uv = makeFaceUV(f7, f10, f6, f11);
		cube.faces.east.uv = makeFaceUV(f4, f11, f5, f12);
		cube.faces.north.uv = makeFaceUV(f5, f11, f6, f12);
		cube.faces.west.uv = makeFaceUV(f6, f11, f8, f12);
		cube.faces.south.uv = makeFaceUV(f8, f11, f9, f12);
		if(cube.mirror_uv) {
			cube.faces.forEach(face -> {
				float f = face.uv.sx;
				face.uv.sx = face.uv.ex;
				face.uv.ex = f;
			});
			FaceUV uv = cube.faces.east.uv;
			cube.faces.east.uv = cube.faces.west.uv;
			cube.faces.west.uv = uv;
		}
		cube.autouv = 0;
	}
}
