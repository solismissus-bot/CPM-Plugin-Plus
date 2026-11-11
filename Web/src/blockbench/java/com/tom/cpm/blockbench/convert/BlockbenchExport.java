package com.tom.cpm.blockbench.convert;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import com.tom.cpl.math.MathHelper;
import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Direction;
import com.tom.cpl.util.Image;
import com.tom.cpl.util.ItemSlot;
import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.convert.WarnEntry.MultiWarnEntry;
import com.tom.cpm.blockbench.format.AnimationWizard;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Timeline;
import com.tom.cpm.blockbench.format.CubeData;
import com.tom.cpm.blockbench.format.GroupData;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.format.ProjectGenerator;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Cube.CubeFace;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Dialog.DialogProperties;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Outliner;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Group.GroupProperties;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.proxy.Texture;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.proxy.Vectors.JsVec3;
import com.tom.cpm.blockbench.tools.export.FormatLimitsCheck;
import com.tom.cpm.blockbench.util.JsonUtil;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs.UserException;
import com.tom.cpm.blockbench.util.RootUtil;
import com.tom.cpm.blockbench.util.TextureDecodeCache;
import com.tom.cpm.blockbench.util.VisState;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.CustomPose;
import com.tom.cpm.shared.animation.IPose;
import com.tom.cpm.shared.animation.VanillaPose;
import com.tom.cpm.shared.animation.interpolator.InterpolatorType;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.editor.CopyTransformEffect;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.actions.ActionBuilder;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.anim.AnimFrame.FrameData;
import com.tom.cpm.shared.editor.anim.AnimatedTex;
import com.tom.cpm.shared.editor.anim.AnimationEncodingData;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.editor.project.JsonList;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.JsonMapImpl;
import com.tom.cpm.shared.editor.project.loaders.AnimationsLoaderV1;
import com.tom.cpm.shared.editor.util.PlayerSkinLayer;
import com.tom.cpm.shared.model.PartPosition;
import com.tom.cpm.shared.model.PartValues;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.DirectPartValues;
import com.tom.cpm.shared.model.render.DirectParts;
import com.tom.cpm.shared.model.render.ItemRenderer;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.model.render.PerFaceUV.Face;
import com.tom.cpm.shared.model.render.PerFaceUV.Rot;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.java.Java;
import com.tom.cpm.web.client.java.JsBuilder;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.ImageIO;
import com.tom.ugwt.client.JsArrayE;

import elemental2.dom.DomGlobal;
import elemental2.dom.FileReader;
import elemental2.promise.Promise;
import elemental2.promise.Promise.PromiseExecutorCallbackFn.RejectCallbackFn;
import elemental2.promise.Promise.PromiseExecutorCallbackFn.ResolveCallbackFn;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

public class BlockbenchExport {
	private final Editor editor;
	private JsArrayE<Group> all;
	private List<WarnEntry> warnings = new ArrayList<>();
	private Map<String, CPMElem> modelTree = new HashMap<>();
	private Map<TextureSheetType, UVMul> uvMul = new HashMap<>();
	private List<Runnable> postConvert = new ArrayList<>();
	private Map<String, ModelElement> uuidLookup = new HashMap<>();
	private Function<List<WarnEntry>, Promise<Void>> openWarn;
	// CPM+ : animation export state
	private Set<VanillaPose> stagedBasePoses = new HashSet<>();
	private Map<ModelElement, List<ModelElement>> colorTargets = new HashMap<>();
	private List<String> animsBakedMolang = new ArrayList<>();
	private List<String> animsResampled = new ArrayList<>();
	private List<String> animsFastRot = new ArrayList<>();
	private boolean warnRootVis, warnScale0;
	// CPM+ : model / texture export state (validation report)
	private Set<String> unknownTextures = new HashSet<>();
	private List<String> badTextureCubes = new ArrayList<>();
	private List<String> editedVanilla = new ArrayList<>();
	private List<String> negativeSize = new ArrayList<>();
	private List<String> itemGroupContent = new ArrayList<>();
	private List<String> fracBoxUV = new ArrayList<>();
	private long texBytes;
	private boolean wearerSkinKept;
	// CPM+ (W0C): Blockbench animation uuid -> the exported animation with its frames / every animation made from it
	private Map<String, EditorAnim> animMap = new HashMap<>();
	private Map<String, List<EditorAnim>> animAll = new HashMap<>();

	public BlockbenchExport(Editor editor, Function<List<WarnEntry>, Promise<Void>> openWarn) {
		this.editor = editor;
		this.openWarn = openWarn;
	}

	// ======================================================================
	// CPM+ (W0C-EXPORTCORE): read-only getters for tools (headless conversion, export checks)
	// ======================================================================

	/** the converted CPM editor */
	public Editor getEditor() {
		return editor;
	}

	/** Blockbench group / root uuid -> the CPM element it became (multi-cube groups: the group element) */
	public Map<String, ModelElement> getUuidLookup() {
		return Collections.unmodifiableMap(uuidLookup);
	}

	/** the exported CPM animation (with the frames) of a Blockbench animation, or null (not exported / unknown type) */
	public EditorAnim getExportedAnim(String bbAnimUuid) {
		return animMap.get(bbAnimUuid);
	}

	/** Blockbench animation uuid -> its exported CPM animation with the frames (see {@link #getExportedAnim}) */
	public Map<String, EditorAnim> getAnimMap() {
		return Collections.unmodifiableMap(animMap);
	}

	/**
	 * every CPM animation made from one Blockbench animation: for 'once' / 'hold' poses and gestures the held main
	 * animation comes first, then the one with the frames (the setup stage); otherwise only the latter
	 */
	public List<EditorAnim> getExportedAnims(String bbAnimUuid) {
		List<EditorAnim> l = animAll.get(bbAnimUuid);
		return l == null ? Collections.emptyList() : Collections.unmodifiableList(l);
	}

	/**
	 * true when CPM colour keyframes on this group reach a cube in game (same rule as the export): not a root group,
	 * and one of its exported unrotated direct cubes has a CPM colour (recolor, or a colour-only cube). Rotated cubes
	 * become their own '_r1' parts and do not take the group's colour.
	 */
	public static boolean isColourTarget(Group g) {
		if(g == null || g.parent == Group.ROOT || g.children == null)return false;
		for (int i = 0; i < g.children.length; i++) {
			Object n = g.children.getAt(i);
			if(!(n instanceof Cube))continue;
			Cube c = (Cube) n;
			if(!c.export)continue;
			if(!c.rotation.allEqual(0) && !g.is_rotation_subgroup)continue;
			if(c.getRecolor() != -1)return true;
		}
		return false;
	}

	/**
	 * The cube count CPM checks in game against the 'max cube count' safety setting of the players who see the model
	 * (ModelDefinition.resolveAll; 256 on the default MEDIUM profile, 1024 on LOW). Same as
	 * {@link #countInGameCubes(Editor)}. Safety-profile limits are information only, never a reason to refuse
	 * (Convention 13).
	 */
	public static int countExportedParts(Editor e) {
		return countInGameCubes(e);
	}

	/**
	 * The cube count CPM checks in game (ModelDefinition.resolveAll: cubes.size() against MAX_CUBE_COUNT), for the
	 * default CPM export (ExportHelper.flattenElements):
	 * <ul>
	 * <li>every custom element (cubes AND groups, hidden ones too) = 1;</li>
	 * <li>every cape / elytra / armor root and every duplicated root = 1 (written as a fake cube);</li>
	 * <li>the vanilla player roots = 0;</li>
	 * <li>template elements (and what is inside them) are not counted (the plugin never exports templates);</li>
	 * <li>extruded cubes count 1 like any cube: resolveAll would add BoxRender.getExtrudeSize for cubes whose extrude
	 * flag is set, but EffectExtrude sets that flag in apply(), which runs AFTER the check, so the extra pieces are
	 * never counted (proven with the real CPM jar: the QA scenario w0cexportcore_headless exports 256 / 257 counted
	 * cubes with extruded ones and the in-game check loads / blocks them on the default 'medium' profile).</li>
	 * </ul>
	 */
	public static int countInGameCubes(Editor e) {
		int[] c = new int[1];
		countInGameCubes(e.elements, c);
		return c[0];
	}

	private static void countInGameCubes(List<ModelElement> list, int[] c) {
		for (ModelElement me : list) {
			if(me.templateElement)continue;// ExportHelper.flattenElements skips the whole subtree
			if(me.type == ElementType.NORMAL) {
				c[0]++;
			} else if(me.type == ElementType.ROOT_PART && (me.duplicated || me.typeData instanceof RootModelType)) {
				c[0]++;
			}
			countInGameCubes(me.children, c);
		}
	}

	private static void log(String msg) {
		DomGlobal.console.log(msg);
	}

	@SuppressWarnings("unchecked")
	public Promise<Void> doExport() {
		log("Exporting");
		all = Global.getAllGroups();

		// CPM+ : skin type of the project (was the web client's default, slim models exported as default)
		{
			ProjectData pdx = Project.getData();
			if(pdx.skinType != null) {
				SkinType st = SkinType.get(pdx.skinType);
				if(st != null && st != SkinType.UNKNOWN) {
					editor.skinType = st;
					editor.customSkinType = pdx.customSkinType;
				}
			}
		}

		List<Promise<Void>> loadedTextures = new ArrayList<>();
		UVMul skinUV = new UVMul();
		uvMul.put(TextureSheetType.SKIN, skinUV);
		if(Texture.all.length == 1) {
			Texture skin = Texture.all.getAt(0);
			loadedTextures.add(loadTexture(skin, TextureSheetType.SKIN));
			skinUV.texId = skin.uuid;
		} else {
			List<Texture> unknown = new ArrayList<>();
			boolean skinFound = false;
			for (int i = 0;i<Texture.all.length;i++) {
				Texture tex = Texture.all.getAt(i);
				// CPM+ (W0C): the name -> sheet rule is shared with RootUtil.findSkinTexture (the tools use the same skin)
				TextureSheetType tst = RootUtil.sheetOfName(tex.name);
				if(tst == null) {
					unknown.add(tex);
					continue;
				}
				if(tst == TextureSheetType.SKIN)skinFound = true;
				loadedTextures.add(loadTexture(tex, tst));
				if(tst != TextureSheetType.SKIN) {
					UVMul m = new UVMul();
					uvMul.put(tst, m);
				}
				uvMul.get(tst).texId = tex.uuid;
			}
			// CPM+ : best effort for extra/unnamed textures (#1035 / #361 / #298): with no 'skin'
			// texture the first unknown texture is used as the skin sheet instead of exporting without one
			if(!skinFound && !unknown.isEmpty()) {
				// CPM+ (W0C): RootUtil.findSkinTexture = the first texture without a sheet name here
				Texture skin = RootUtil.findSkinTexture();
				if(skin == null || !unknown.remove(skin))skin = unknown.remove(0);
				loadedTextures.add(loadTexture(skin, TextureSheetType.SKIN));
				skinUV.texId = skin.uuid;
				warnings.add(new WarnEntry("CPM+: No texture named 'skin', '" + esc(skin.name) + "' was used as the skin texture.").setInfo().setKey("noSkinTexture"));
			}
			for (Texture tex : unknown) {
				unknownTextures.add(tex.uuid);
				warnings.add(new WarnEntry(I18n.format("bb-label.warn.unknownTexture", esc(tex.name)), () -> fixTexture(tex)).
						setTooltip("CPM+: CPM supports one texture per sheet (skin, cape, elytra, armor1, armor2). Cubes using this texture are still exported but sample the skin texture at the same UV. Merge it into the skin texture, or rename it to a free sheet with the auto fix.").setKey("unknownTexture", tex.name));
			}
		}
		log("Exported textures");

		all.slice().forEach(group -> {
			List<Group> subgroups = new ArrayList<>();
			int[] group_i = new int[] {all.indexOf(group)};
			group.children.forEachReverse(in -> {
				if(!(in instanceof Cube) || !((Cube)in).export)return;
				Cube cube = (Cube) in;
				if(!cube.rotation.allEqual(0)) {
					Group sub = subgroups.stream().filter(s -> {
						if(!s.rotation.equals(cube.rotation))return false;
						if(s.rotation.hasValues(1)) {
							return s.origin.equals(cube.origin);
						} else {
							if(s.rotation.x == 0 && s.origin.x != cube.origin.x)return false;
							if(s.rotation.y == 0 && s.origin.y != cube.origin.y)return false;
							if(s.rotation.z == 0 && s.origin.z != cube.origin.z)return false;
							return true;
						}
					}).findFirst().orElse(null);
					if(sub == null) {
						GroupProperties g = new GroupProperties();
						g.rotation = cube.rotation;
						g.origin = cube.origin;
						g.name = cube.name + "_r1";
						sub = new Group(g);
						sub.parent = group;
						sub.is_rotation_subgroup = true;
						sub.createUniqueName(all);
						subgroups.add(sub);
						group_i[0]++;
						all.splice(group_i[0], 0, sub);
					}
					sub.children.push(cube);
				}
			});
		});

		log("Generated rotation subgroups");

		// CPM+ : two passes, roots that copy another root's transform (armor -> head/body/legs)
		// need the source root converted first (was outliner-order dependent)
		for (int pass = 0; pass < 2; pass++) {
			final int fpass = pass;
			all.forEach(group -> {
				if ((!(group instanceof Group) && !group.is_catch_bone) || !group.export) return;
				if (group.parent != Group.ROOT) return;
				VanillaModelPart rt = group.getRootType();
				boolean second = rt != null && rt.getCopyFrom() != null;
				if(second != (fpass == 1))return;
				convertRoot(group);
			});
		}

		log("Exported roots");

		// CPM+ : loose cubes (directly in the outliner root) go into the body. The old code used the
		// wrong signs (x*=1, y*=1), ignored the body pivot and dropped inflate/glow/recolor/export.
		convertLooseCubes();
		log("Exported loose cubes");

		all.forEach(group -> {
			if ((!(group instanceof Group) && !group.is_catch_bone) || !group.export) return;
			if (group.parent == Group.ROOT) return;
			Vec3f rot = new Vec3f();
			rot.x = -group.rotation.x;
			rot.y = -group.rotation.y;
			rot.z = group.rotation.z;
			Vec3f o = group.origin.toVecF().sub(group.parent.origin.toVecF());
			o.x *= -1;
			o.y *= -1;
			final Vec3f fo = o;
			List<CPMElem> children = new ArrayList<>();
			CPMElem ch = modelTree.get(group.parent.uuid);
			// CPM+ : CPM never renders the cubes or children of an item-hold element, warn instead of
			// dropping them silently
			if(!group.is_rotation_subgroup && group.getData().getItemRenderer() != null) {
				boolean content = group.children.asList().stream().anyMatch(n -> n instanceof Group ||
						(n instanceof Cube && ((Cube) n).export && !isZeroSize((Cube) n)));
				if(content)itemGroupContent.add(group.name);
			}
			group.children.forEach(in -> {
				if(!(in instanceof Cube))return;
				Cube cube = (Cube) in;
				if(!cube.export || (!cube.rotation.allEqual(0) && !group.is_rotation_subgroup))return;
				CPMElem e = new CPMElem(group, cube, rot, fo);
				children.add(e);
			});
			if(children.isEmpty()) {
				CPMElem e = new CPMElem(group, null, rot, fo);
				children.add(e);
			} else if(children.size() > 1) {
				CPMElem e = new CPMElem(group, null, rot, fo);
				children.forEach(CPMElem::resetTransform);
				e.children.addAll(children);
				children.clear();
				children.add(e);
			}
			if(ch == null) {
				MultiWarnEntry.addOrIncEntry(warnings, I18n.format("bb-label.warn.cubeSkipped")).setPriority(-1).setKey("cubeSkipped");
				return;
			}
			ch.children.add(children.get(0));
			modelTree.put(group.uuid, children.get(0));
		});

		log("Exported model parts");

		modelTree.values().stream().filter(e -> e.part != null).forEach(CPMElem::finishConvert);
		postConvert.forEach(Runnable::run);

		log("Constructed model parts hierarchy");

		Promise<?> pr = Promise.all(loadedTextures.stream().toArray(Promise[]::new));
		return pr.then(__ -> {
			log("Textures finished");
			finishExport();
			if(!warnings.isEmpty())
				return openWarn.apply(warnings);
			else
				return Promise.resolve((Void) null);
		});
	}

	// ======================================================================
	// CPM+ : root groups, loose cubes and stable ids
	// ======================================================================

	private void convertRoot(Group group) {
		String groupName = group.name;
		VanillaModelPart part;
		ModelElement me;
		GroupData gd = group.getData();
		if(groupName.endsWith("_dup")) {
			groupName = groupName.substring(0, groupName.length() - 4);
			part = part(groupName);
			if(part == null) {
				rootWarning(group);
				return;
			}
			me = new ModelElement(editor, ElementType.ROOT_PART, part);
			me.duplicated = true;
			// CPM+ : stable storeID (was a new random id on every export)
			long sid = gd.getStoreID();
			me.storeID = sid != 0 ? sid : stableId(group.uuid);
			editor.elements.add(me);
		} else {
			part = part(groupName);
			if(part == null) {
				rootWarning(group);
				return;
			}
			me = getPart(part);
			if(part instanceof RootModelType) {
				long sid = gd.getStoreID();
				me.storeID = sid != 0 ? sid : stableId(group.uuid);
			}
		}
		// CPM+ : renamed roots, locked, name colour
		if(gd.getRootName() != null)me.name = gd.getRootName();
		if(gd.isLocked())me.locked = true;
		if(gd.getNameColor() != 0)me.partNameColor = gd.getNameColor();
		me.hidden = true;
		me.disableVanillaAnim = group.disableVanillaAnim;
		uuidLookup.put(group.uuid, me);
		Vec3f o = group.origin.toVecF();
		o.x *= -1;
		o.y *= -1;
		o.y += 24;
		me.rotation.x = -group.rotation.x;
		me.rotation.y = -group.rotation.y;
		me.rotation.z = group.rotation.z;
		PartValues pv = DirectParts.getPartOverrides(part, editor.skinType);
		o = o.sub(pv.getPos());
		VanillaModelPart p = part.getCopyFrom();
		if(p != null) {
			ModelElement e = getPart(p);
			me.rotation = me.rotation.sub(e.rotation);
			o = o.sub(e.pos);
		} else if(pv instanceof DirectPartValues) {
			me.rotation = me.rotation.sub(((DirectPartValues)pv).getRotation());
		}
		me.pos = o;
		fixDecimals(me);
		modelTree.put(group.uuid, new CPMElem(me));
		final ModelElement fme = me;
		group.children.forEach(in -> {
			if(!(in instanceof Cube))return;
			Cube cube = (Cube) in;
			if(!cube.export || (!cube.rotation.allEqual(0) && !group.is_rotation_subgroup))return;
			// CPM+ : the vanilla part cube created on import: keep the vanilla part (2nd layer, skin
			// type, vanilla behaviour) instead of converting it into a custom cube, unless it was edited
			if(cube.getData().isVanilla()) {
				if(isUnchangedVanilla(group, cube, pv)) {
					fme.hidden = false;
					return;
				}
				editedVanilla.add(cube.name);
			}
			ModelElement e = new ModelElement(editor);
			add(fme, e);
			e.texture = true;
			e.storeID = stableCubeId(cube);
			convert(e, group.origin, cube);
			fixDecimals(e);
		});
	}

	private static boolean near(float a, float b) {
		return Math.abs(a - b) < 0.002f;
	}

	private static boolean isUnchangedVanilla(Group group, Cube cube, PartValues pv) {
		Vec3f o = pv.getOffset();
		Vec3f s = pv.getSize();
		float fx = group.origin.x - o.x - s.x;
		float fy = group.origin.y - o.y - s.y;
		float fz = group.origin.z + o.z;
		if(!near(cube.from.x, fx) || !near(cube.from.y, fy) || !near(cube.from.z, fz))return false;
		if(!near(cube.to.x, fx + s.x) || !near(cube.to.y, fy + s.y) || !near(cube.to.z, fz + s.z))return false;
		if(!cube.box_uv || cube.uv_offset == null)return false;
		if(!near(cube.uv_offset.x, pv.getUV().x) || !near(cube.uv_offset.y, pv.getUV().y))return false;
		if(!near(cube.inflate, pv.getMCScale()))return false;
		if(cube.mirror_uv != pv.isMirror())return false;
		if(cube.getRecolor() != -1 || cube.glow || cube.extrude)return false;
		return true;
	}

	private void convertLooseCubes() {
		List<Cube> loose = new ArrayList<>();
		Cube.all.forEach(c -> {
			if(c.parent == Group.ROOT && c.export)loose.add(c);
		});
		if(loose.isEmpty())return;
		Group bodyG = null;
		for (Group g : Group.all) {
			if(g.parent == Group.ROOT && g.export && g.name.equalsIgnoreCase(PlayerModelParts.BODY.getName())) {
				bodyG = g;
				break;
			}
		}
		ModelElement body = getPart(PlayerModelParts.BODY);
		Vec3f base;
		if(bodyG != null)base = bodyG.origin.toVecF();
		else {
			PartValues pv = DirectParts.getPartOverrides(PlayerModelParts.BODY, editor.skinType);
			Vec3f p = pv.getPos();
			base = new Vec3f(-p.x, 24 - p.y, p.z);
		}
		List<String> names = new ArrayList<>();
		for (Cube cube : loose) {
			ModelElement me = new ModelElement(editor);
			add(body, me);
			me.texture = true;
			Vec3f o = cube.origin.toVecF().sub(base);
			o.x *= -1;
			o.y *= -1;
			me.pos = o;
			Vec3f r = cube.rotation.toVecF();
			me.rotation = new Vec3f(-r.x, -r.y, r.z);
			// CPM+ (NEW-VIS-EXPORT): the user's visibility, never the Animate-mode preview state
			me.showInEditor = VisState.persistentVisible(cube);
			me.storeID = stableCubeId(cube);
			convert(me, cube.origin, cube);
			fixDecimals(me);
			names.add(cube.name);
		}
		// CPM+ (W5-STICK, G6): ONE row in plain words (count; up to 10 names in the tooltip). Its fix 'Resolver...'
		// (WarnFixes 'looseCubes', W5StickInstall) opens the 'Peças soltas' dialog: 'Grudar onde estão' / 'Mover para o
		// Corpo' / 'Não avisar neste projeto'. The export itself is unchanged (old models depend on it).
		if(com.tom.cpm.blockbench.tools.model.StickToRoot.isLooseAck())return;
		WarnEntry lw = WarnEntry.WarnFactory.of(WarnEntry.Severity.DIFFERS, "looseCubes", loose.size() == 1 ? "cpmplus.w5stick.warn.loose.one" : "cpmplus.w5stick.warn.loose.many", loose.size());
		StringBuilder tip = new StringBuilder();
		for (int i = 0; i < names.size() && i < 10; i++)tip.append(i > 0 ? ", " : "").append(names.get(i));
		if(names.size() > 10)tip.append(", ... (+").append(names.size() - 10).append(")");
		String tt = esc(I18n.format("cpmplus.w5stick.warn.loose.names", tip.toString()));
		if(bodyG != null && !bodyG.rotation.allEqual(0))tt += "<br>" + esc(I18n.get("cpmplus.w5stick.warn.loose.rotatedBody"));
		lw.setTooltip(tt);
		for (Cube c : loose)lw.addNode(c.uuid, c.name);
		lw.setFixKind(WarnEntry.FixKind.ASKS);
		warnings.add(lw);
	}

	/** CPM+ : stable, JS-safe (53 bit) id derived from a Blockbench uuid */
	private static long stableId(String s) {
		if(s == null)return 0;
		long h = 1125899906842597L;
		for (int i = 0; i < s.length(); i++) {
			h = 31 * h + s.charAt(i);
		}
		h = h & 0x1FFFFFFFFFFFFFL;
		if(h < 1000)h += 1000;
		return h;
	}

	private static long stableCubeId(Cube cube) {
		long sid = cube.getData().getStoreID();
		return sid != 0 ? sid : stableId(cube.uuid);
	}

	private static boolean isZeroSize(Cube c) {
		return Math.abs(c.to.x - c.from.x) < 1e-4f && Math.abs(c.to.y - c.from.y) < 1e-4f && Math.abs(c.to.z - c.from.z) < 1e-4f;
	}

	/** CPM+ : escape names shown in the (HTML) warning dialog */
	private static String esc(String s) {
		if(s == null)return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
	}

	private static String list(List<String> names) {
		StringBuilder sb = new StringBuilder();
		int n = Math.min(names.size(), 25);
		for (int i = 0; i < n; i++) {
			if(i > 0)sb.append(", ");
			sb.append(esc(names.get(i)));
		}
		if(names.size() > n)sb.append(", ... (+" + (names.size() - n) + ")");
		return sb.toString();
	}

	/**
	 * CPM+ : snap to a 1/10000 grid. Removes float noise (2.9999998 -> 3) but keeps quarter/eighth
	 * pixels and fine rotations (#973). The old code rounded size/rotation to 1 decimal
	 * (0.25 -> 0.3) and pos/offset to 2 decimals. CPM itself stores ~1/682 precision.
	 */
	private void fixDecimals(ModelElement me) {
		me.size = fixDecimals(me.size);
		me.pos = fixDecimals(me.pos);
		me.rotation = fixDecimals(me.rotation);
		me.offset = fixDecimals(me.offset);
	}

	private static Vec3f fixDecimals(Vec3f v) {
		if (v == null)return null;
		return new Vec3f(snap(v.x), snap(v.y), snap(v.z));
	}

	private static float snap(float v) {
		if(Float.isNaN(v) || Float.isInfinite(v))return 0;
		float r = (float) (Math.round(v * 10000.0) / 10000.0);
		return r == 0 ? 0 : r;// no -0
	}

	private void rootWarning(Group group) {
		warnings.add(new WarnEntry(I18n.format("bb-label.warn.unknownRoot", esc(group.name)), () -> {// CPM+ : escaped
			return new Promise<>((res, rej) -> {
				DialogProperties pr = new DialogProperties();
				pr.id = "cpm_fix_roots";
				pr.title = I18n.get("bb-label.selectRoot");
				pr.singleButton = false;
				Dialog.FormSelectElement selPart = Dialog.FormSelectElement.make(I18n.get("bb-label.selectRoot"));
				JsBuilder<String> b = new JsBuilder<>();
				for(PlayerModelParts p : PlayerModelParts.VALUES) {
					if(p == PlayerModelParts.CUSTOM_PART)continue;
					b.put(p.getName(), I18n.get("label.cpm.elem." + p.getName()));
				}
				for(RootModelType p : RootModelType.VALUES) {
					b.put(p.getName(), I18n.get("label.cpm.elem." + p.getName()));
				}
				selPart.value = UnknownRootFix.preselect(group);// CPM+ (W4, G1): suggested root (RootSuggest), never applied by itself
				selPart.options = b.build();
				Dialog.FormCheckboxElement renameBox = Dialog.FormCheckboxElement.make(I18n.get("bb-label.export.renameGroup"), true, I18n.formatNl("bb-tooltip.export.renameGroup"));
				pr.form = new JsBuilder<>().
						put("root", selPart).
						put("dup", Dialog.FormCheckboxElement.make(I18n.get("bb-label.export.markDuplicateRoot"))).
						put("rename", renameBox).
						build();
				Dialog.link(pr.form);
				if(group.children.asList().stream().allMatch(n -> n instanceof Group)) {
					pr.buttons = new String[] {"action.resolve_group", "dialog.confirm", "dialog.cancel"};
					pr.confirmIndex = 1;
					pr.onButton = id -> {
						if(id == 0) {
							group.resolve();
							res.onInvoke(true);
						}
						return true;
					};
				}
				pr.onFormChange = d -> {
					JsPropertyMap<Object> dr = Js.uncheckedCast(d);
					String root = Js.cast(dr.get("root"));
					boolean dupRoot = Js.isTruthy(dr.get("dup"));
					boolean en = dupRoot || Arrays.stream(Group.all).noneMatch(gr -> gr.parent == Group.ROOT && gr.name.equals(root));
					renameBox.getBar().find("input#rename").disabled(!en);
				};
				pr.onOpen = () -> {
					// CPM+ (W4, G1): the preselected root (was hard-coded 'head')
					String pre = Js.cast(selPart.value);
					boolean en = !UnknownRootFix.rootExists(pre);
					renameBox.getBar().find("input#rename").disabled(!en);
				};
				pr.onConfirm = d -> {
					JsPropertyMap<Object> dr = Js.uncheckedCast(d);
					String root = Js.cast(dr.get("root"));
					boolean dupRoot = Js.isTruthy(dr.get("dup"));
					boolean rename = Js.isTruthy(dr.get("rename"));
					// CPM+ (W4, G1): every branch is ONE undo entry that restores names / hierarchy and removes a created root
					UnknownRootFix.apply(group, root, dupRoot, rename, editor.skinType);
					res.onInvoke(true);
					return true;
				};
				pr.onCancel = () -> {
					res.onInvoke(false);
					return true;
				};
				new Dialog(pr).show();
			});
		}).setKey("unknownRoot", group.name).addNode(group.uuid, group.name));
	}

	/** CPM+ (W0C): delegates to RootUtil.makeRoot (same math, shared with the tools) */
	private Group makeRoot(String root) {
		return RootUtil.makeRoot(root, editor.skinType);
	}

	private Promise<Boolean> fixTexture(Texture tex) {
		return new Promise<>((res, rej) -> {
			JsBuilder<String> b = new JsBuilder<>();
			for(TextureSheetType tst : TextureSheetType.VALUES) {
				if(tst == TextureSheetType.LIST_ICON)continue;
				if(Texture.all.asList().stream().anyMatch(t -> t.name.equals(tst.name().toLowerCase())))continue;
				b.put(tst.name().toLowerCase(), I18n.get("label.cpm.texture." + tst.name().toLowerCase()));
			}
			if(b.isEmpty()) {
				PopupDialogs.displayMessage(I18n.formatBr("bb-label.export.mergeTextureSheets"));
				rej.onInvoke("");
				return;
			}

			DialogProperties pr = new DialogProperties();
			pr.id = "cpm_fix_texture";
			pr.title = I18n.get("bb-label.selectTexture.title");
			pr.singleButton = false;
			Dialog.FormSelectElement selPart = Dialog.FormSelectElement.make(I18n.format("bb-label.selectTexture", tex.name));
			selPart.value = b.first();
			selPart.options = b.build();
			selPart.description = I18n.formatNl("bb-label.export.mergeTextureSheets");
			pr.form = new JsBuilder<>().put("root", selPart).build();
			pr.onConfirm = d -> {
				JsPropertyMap<String> dr = Js.uncheckedCast(d);
				String root = dr.get("root");
				UndoData dt = new UndoData();
				dt.textures = new Texture[] {tex};
				Undo.initEdit(dt);
				tex.name = root;
				Undo.finishEdit(I18n.format("bb-label.autoFixApplied", I18n.get("bb-label.autoFix.renameTexture")), dt);
				ProjectGenerator.updateAll();

				res.onInvoke(true);
				return true;
			};
			pr.onCancel = () -> {
				res.onInvoke(false);
				return true;
			};
			new Dialog(pr).show();
		});
	}

	private Promise<Boolean> showDecimalFixed(Set<String> decFixApplied) {
		return new Promise<>((res, rej) -> {
			DialogProperties pr = new DialogProperties();
			pr.id = "cpm_list_decimals";
			pr.title = I18n.get("bb-label.warn.uvDecimalFixer.list");
			pr.singleButton = true;
			// CPM+ : names escaped, HTMLElement lines instead of the deprecated HTML string
			pr.setHtml("<div>" +
					I18n.formatBr("bb-label.warn.uvDecimalFixer.info") +
					decFixApplied.stream().map(BlockbenchExport::esc).collect(Collectors.joining("<br>", "<div style='padding: 8px;'>", "</div></div>"))
			);
			pr.onCancel = () -> {
				res.onInvoke(false);
				return true;
			};
			new Dialog(pr).show();
		});
	}

	private void finishExport() {
		Set<String> decFixApplied = new HashSet<>();
		uvMul.entrySet().forEach(e -> {
			UVMul m = e.getValue();
			TextureSheetType tst = e.getKey();
			if(tst.editable) {
				ETextures tex = editor.textures.get(tst);
				if (tex.provider.size.x * 16 < 16384 && tex.provider.size.y * 16 < 16384) {
					List<DecimalFixOp> reqFix = m.elems.stream().filter(DecimalFixOp::needsFix).collect(Collectors.toList());
					if (!reqFix.isEmpty()) {
						for (DecimalFixOp op : reqFix) {
							decFixApplied.add(op.getDisplayName());
						}
						m.baseMul *= 16;
						tex.provider.size.x *= 16;
						tex.provider.size.y *= 16;
					}
				}
			}
			m.elems.forEach(DecimalFixOp::apply);
		});
		log("Applied UV decimal fixer");
		if (!decFixApplied.isEmpty()) {
			warnings.add(new WarnEntry(I18n.format("bb-label.warn.uvDecimalFixer.applied"), () -> showDecimalFixed(decFixApplied)).setInfo().setTooltip(I18n.format("bb-tooltip.warn.uvDecimalFixerApplied", I18n.get("bb-button.autoFix"))).setFixKind(WarnEntry.FixKind.VIEW_ONLY).setKey("uvDecimalFixer"));
		}

		ProjectData pd = Project.getData();
		if(!pd.animations.isEmpty())new AnimEnc().load(new JsonMapImpl(pd.animations));
		editor.scalingElem.scaling.putAll(pd.scalingOpt);
		if(pd.renderPos != null)editor.scalingElem.pos = pd.renderPos;
		if(pd.renderRot != null)editor.scalingElem.rotation = pd.renderRot;
		if(pd.renderScl != null)editor.scalingElem.scale = pd.renderScl;
		// CPM+ : the 'Scaling' checkbox was never set, PropertiesLoaderV1 then wrote scaling 0 and the
		// exporter skipped every scaling effect (#445). Stored flag, or inferred for old projects.
		if(pd.scalingEnabled != null) {
			editor.scalingElem.enabled = pd.scalingEnabled.booleanValue();
		} else {
			// CPM+ : legacy guess. CPM saves 1.0 for every unset scaling option and (1,1,1)/zeros for the render
			// transform even when scaling is off, so 0 and 1 mean 'unset' (same rule as ScalingElement).
			// PropertiesLoaderV1 encodes 'enabled' as config 'scaling' != 0, which the import stores as the
			// ENTITY option: if it is present, it decides.
			Float ent = pd.scalingOpt.get(com.tom.cpm.shared.util.ScalingOptions.ENTITY);
			boolean any;
			if(ent != null) {
				any = ent != 0;
			} else {
				any = pd.scalingOpt.values().stream().anyMatch(v -> v != null && v != 0 && Math.abs(v - 1) > 1e-4);
				any |= nonZero(pd.renderPos) || nonZero(pd.renderRot) || notUnsetScale(pd.renderScl);
			}
			editor.scalingElem.enabled = any;
		}
		// CPM+ : custom texture grid flag (stored, or grid size differs from the image size)
		for(TextureSheetType tx : TextureSheetType.VALUES) {
			ETextures eTex = editor.textures.get(tx);
			if(eTex == null || !eTex.isEditable())continue;
			boolean cg = Boolean.TRUE.equals(pd.customGrid.get(tx));
			try {
				if(eTex.isEdited() && eTex.getImage() != null) {
					Image img = eTex.getImage();
					cg |= img.getWidth() != eTex.provider.size.x || img.getHeight() != eTex.provider.size.y;
				}
			} catch (Throwable ex) {
			}
			eTex.customGridSize = cg;
		}

		if(pd.leftHandPos != null)editor.leftHandPos = pd.leftHandPos;
		if(pd.rightHandPos != null)editor.rightHandPos = pd.rightHandPos;
		editor.modelId = pd.modelId;
		editor.removeArmorOffset = pd.removeArmorOffset;//TODO test

		if (!pd.animTex.isEmpty()) {
			for(TextureSheetType tx : TextureSheetType.VALUES) {
				ETextures eTex = editor.textures.get(tx);
				if(eTex != null && eTex.isEditable() && pd.animTex.containsKey(tx)) {
					JsonMap map = new JsonMapImpl(pd.animTex.get(tx));
					JsonList list = map.getList("anim");
					list.forEachMap(elem -> eTex.animatedTexs.add(new AnimatedTex(editor, tx, elem)));
				}
			}
		}

		editor.description = pd.description;
		editor.hideHeadIfSkull = Project.hideHeadIfSkull;
		editor.removeBedOffset = Project.removeBedOffset;
		editor.enableInvisGlow = Project.invisGlow;

		try {
			Project.animations.forEach(this::convertAnimation);
		} finally {
			AnimSampler.finish();// CPM+ (G9): 'this' values cleared, Animate-mode preview redrawn at the restored playhead
		}
		addStagedBaseAnims();// CPM+
		addAnimWarnings();// CPM+
		addModelWarnings();// CPM+ : pre-export validation report
		ExportHooks.runChecks(editor, this, warnings);// CPM+ (W0C): checks registered by the tools (ExportHooks)

		resetEditorState(editor);
	}

	private static boolean nonZero(Vec3f v) {
		return v != null && (v.x != 0 || v.y != 0 || v.z != 0);
	}

	/** CPM+ : render scale components 0 and 1 both mean 'unset' (ScalingElement.hasTransform) */
	private static boolean notUnsetScale(Vec3f v) {
		return v != null && ((v.x != 0 && v.x != 1) || (v.y != 0 && v.y != 1) || (v.z != 0 && v.z != 1));
	}

	/**
	 * CPM+ : pre-export validation report. Everything here is a non-blocking warning/info entry in
	 * the export warning dialog (the export continues on confirm).
	 */
	private void addModelWarnings() {
		if(!badTextureCubes.isEmpty())
			warnings.add(new WarnEntry(I18n.format("bb-label.warn.invalidTexture", list(badTextureCubes))).setKey("invalidTexture"));
		if(!itemGroupContent.isEmpty())
			warnings.add(new WarnEntry("CPM+: CPM doesn't render cubes or sub-groups inside item-hold groups, they are skipped: " + list(itemGroupContent)).setKey("itemGroupContent"));
		if(!editedVanilla.isEmpty())
			warnings.add(new WarnEntry("CPM+: These vanilla part cubes were edited and are exported as custom cubes (the vanilla part and its 2nd layer are hidden): " + list(editedVanilla)).setInfo().setKey("editedVanilla"));
		if(!negativeSize.isEmpty())
			warnings.add(new WarnEntry("CPM+: Cubes with negative size were normalized (#493): " + list(negativeSize)).setInfo().setKey("negativeSize"));
		if(!fracBoxUV.isEmpty())
			warnings.add(new WarnEntry("CPM+: Box UV cubes with a non-integer UV offset were exported with per-face UV: " + list(fracBoxUV)).setInfo().setKey("fracBoxUV"));
		if(wearerSkinKept)
			warnings.add(new WarnEntry("CPM+: The skin texture was not edited, the model keeps using each player's own skin (it is not baked into the model). Paint on the skin to include it.").setInfo().setKey("wearerSkinKept"));

		// unsupported outliner nodes (#947 / #657)
		try {
			OutlinerElement[] els = Outliner.elements;
			if(els != null) {
				List<String> unsupported = new ArrayList<>();
				for (OutlinerElement e : els) {
					if(e == null || e instanceof Cube)continue;
					JsPropertyMap<Object> m = Js.asPropertyMap(e);
					Object nm = m.get("name");
					Object tp = m.get("type");
					unsupported.add((nm != null ? String.valueOf(nm) : "?") + " (" + (tp != null ? String.valueOf(tp) : "unknown") + ")");
				}
				if(!unsupported.isEmpty())
					warnings.add(new WarnEntry("CPM+: CPM only supports cubes. These elements (meshes, locators, null objects, ...) are skipped: " + list(unsupported)).setKey("unsupportedElements"));
			}
		} catch (Throwable ex) {
		}

		// element count / size limits
		int[] count = new int[1];
		List<String> big = new ArrayList<>();
		List<String> far = new ArrayList<>();
		int sizeLimit = 25;
		Editor.walkElements(editor.elements, e -> {
			if(e.type == ElementType.ROOT_PART)return;
			count[0]++;
			if(e.size != null && (e.size.x > sizeLimit || e.size.y > sizeLimit || e.size.z > sizeLimit))big.add(e.name);
			if(outOfRange(e.pos) || outOfRange(e.offset))far.add(e.name);
		});
		// CPM+ (W0C): the number CPM checks in game (also the fake cubes of cape / elytra / armor / duplicated roots),
		// not just the elements
		int inGameCubes = countInGameCubes(editor);
		if(inGameCubes > 256)
			warnings.add(new WarnEntry("CPM+: The model counts as " + inGameCubes + " cubes in game (cubes + groups + cape / elytra / armor roots). The default safety settings other players use allow 256, they may see the vanilla skin instead.").setInfo().setKey("elementCount", inGameCubes));
		if(!big.isEmpty())
			// CPM+ : the size/position limits are only enforced by the CPM editor's input fields (FormatLimits),
			// the model file itself stores these values unclamped
			warnings.add(new WarnEntry("CPM+: Cubes larger than " + sizeLimit + " pixels are outside the range the CPM editor lets you set; they export fine, but editing them in the CPM editor may clamp them (#269 / #338): " + list(big)).setInfo().setKey("bigCubes"));
		if(!far.isEmpty())
			warnings.add(new WarnEntry("CPM+: Position/offset beyond +-" + Vec3f.MAX_POS + " pixels is outside the range the CPM editor lets you set; it exports fine, but editing it in the CPM editor may clamp it: " + list(far)).setInfo().setKey("farPositions"));

		// texture sizes
		List<String> bigTex = new ArrayList<>();
		for (int i = 0; i < Texture.all.length; i++) {
			Texture t = Texture.all.getAt(i);
			int w = Math.max(t.width, t.uv_width);
			int h = Math.max(t.height, t.uv_height);
			if(w > 256 || h > 256)bigTex.add(t.name + " (" + w + "x" + h + ")");
		}
		if(!bigTex.isEmpty())
			warnings.add(new WarnEntry("CPM+: Textures larger than 256x256 are blocked by the default safety settings other players use: " + list(bigTex)).setInfo().setKey("bigTextures"));

		// estimated size (textures + elements + animation frames)
		long est = texBytes + count[0] * 48L;
		for (EditorAnim ea : editor.animations) {
			try {
				for (AnimFrame f : ea.getFrames())est += 8 + f.getComponents().size() * 20L;
			} catch (Throwable ex) {
			}
		}
		if(est > 100 * 1024)
			warnings.add(new WarnEntry("CPM+: Estimated model size is about " + (est / 1024) + " KB. Models over 100 KB are blocked by the default safety settings other players use and may not fit on the skin/paste site.").setInfo().setKey("estimatedSize", est));
	}

	private static boolean outOfRange(Vec3f v) {
		float m = Vec3f.MAX_POS;
		return v != null && (Math.abs(v.x) > m || Math.abs(v.y) > m || Math.abs(v.z) > m);
	}

	private static native void resetEditorState(Editor e)/*-{
		e.@com.tom.cpm.shared.editor.Editor::undoQueue.@java.util.Stack::clear()();
		e.@com.tom.cpm.shared.editor.Editor::redoQueue.@java.util.Stack::clear()();
	}-*/;

	private void convertAnimation(Animation anim) {
		if(Js.typeof(anim.type) != "string") {
			warnings.add(new WarnEntry(I18n.formatBr("bb-label.warn.unknownAnimation", esc(anim.name)), () -> new Promise<>((res, rej) -> {
				AnimationWizard.open(anim, res::onInvoke);
			})).setKey("unknownAnimation", anim.name));
			return;
		}

		AnimSampler.beginAnimation();// CPM+ (G9): Molang variables reset once per animation (like Animation.select)
		boolean[] error = new boolean[] {false, false};
		List<BoneAnimator> anims = new ArrayList<>();
		anim.animators.forEach(k -> {
			GeneralAnimator ga = anim.animators.get(k);
			if(ga instanceof BoneAnimator) {
				BoneAnimator ba = (BoneAnimator) ga;
				if(!ba.hasTransform())return;
				// CPM+ : animators of groups that are not exported (export=false / skipped) are ignored
				if(!uuidLookup.containsKey(ba.uuid))return;
				anims.add(ba);
			}
		});

		Pair<AnimationType, VanillaPose> typeA = Animation.parseType(anim.type);
		Pair<AnimationType, VanillaPose> type = typeA;

		String setupName;
		if(typeA.getKey() == AnimationType.POSE) {
			// CPM+ : was typeA.getKey() -> "p:pose", which never links to the vanilla pose
			setupName = "p:" + typeA.getValue().name().toLowerCase(Locale.ROOT);
		} else if(typeA.getKey() == AnimationType.CUSTOM_POSE) {
			setupName = "c:" + anim.name;
		} else if(typeA.getKey() == AnimationType.GESTURE || typeA.getKey() == AnimationType.LAYER) {
			// CPM+ : CPM links 'g:' by button id (text before '#')
			setupName = "g:" + idOf(anim.name);
		} else
			setupName = null;

		boolean loop = false;
		String name = anim.name;

		if(type.getKey().isStaged()) {
			if(!anim.loop.equals("once")) {
				warnings.add(new WarnEntry(I18n.formatBr("bb-label.warn.stagedAnimLooping", esc(anim.name)), () -> new Promise<>((res, rej) -> {
					Undo.initEdit(UndoData.make(anim));
					anim.loop = "once";
					Undo.finishEdit("Edit animation properties");
					res.onInvoke(true);
				})).setFixKind(WarnEntry.FixKind.DETERMINISTIC).setKey("stagedAnimLooping", anim.name));
				return;
			}
			String[] sp = anim.name.split(":", 2);
			boolean err = false;
			if(sp.length < 2)err = true;
			else if(sp[0].equalsIgnoreCase("p")) {
				VanillaPose pose = null;
				for (VanillaPose p : VanillaPose.VALUES) {
					if(sp[1].equalsIgnoreCase(p.name())) {
						pose = p;
						break;
					}
				}
				if(pose == null)err = true;
				else {
					// CPM+ : CPM links staged anims case-sensitively ('p:<pose lowercase>'), normalize.
					// The '<pose> Base' anim is now created once per pose and only if no animation
					// for that pose is exported (see addStagedBaseAnims).
					name = "p:" + pose.name().toLowerCase(Locale.ROOT);
					stagedBasePoses.add(pose);
				}
			} else if(sp[0].equalsIgnoreCase("c")) {
				name = "c:" + sp[1];// CPM+ : normalize prefix case
			} else if(sp[0].equalsIgnoreCase("g")) {
				name = "g:" + sp[1];// CPM+ : normalize prefix case
			} else {
				err = true;
			}
			if(err) {
				warnings.add(new WarnEntry(I18n.formatBr("bb-label.warn.unknownStagedAnimation", esc(anim.name)), () -> fixStaged(anim)).setKey("unknownStagedAnimation", anim.name));
				return;
			}
		} else {
			if (!anim.loop.equals("loop")) {
				if (type.getKey() == AnimationType.VALUE_LAYER || (type.getKey() == AnimationType.POSE && type.getValue().hasStateGetter())) {
					warnings.add(new WarnEntry(I18n.formatBr("bb-label.warn.valueAnimNotLooping", esc(anim.name)), () -> new Promise<>((res, rej) -> {
						Undo.initEdit(UndoData.make(anim));
						anim.loop = "loop";
						Undo.finishEdit("Edit animation properties");
						res.onInvoke(true);
					})).setFixKind(WarnEntry.FixKind.DETERMINISTIC).setKey("valueAnimNotLooping", anim.name));
					return;
				}
			}
			if(anim.loop.equals("loop")) {
				if(type.getKey().canLoop())loop = true;
			} else if(anim.loop.equals("once")) {
				if(!type.getKey().canLoop()) {
					// CPM+ : 'once' on a pose/custom pose/layer becomes a SETUP stage. CPM needs a main
					// animation to link it to (AnimationExporter throws stagingMissingMain otherwise),
					// so add an empty (rest pose) main animation carrying the button properties.
					EditorAnim main = makeMainAnim(anim, type, name);
					main.addFrame();
					editor.animations.add(main);
					recordAnim(anim, main);// CPM+ (W0C)
					type = Pair.of(AnimationType.SETUP, null);
					name = setupName;
				}
			} else if(anim.loop.equals("hold")) {
				// CPM+ : the main (held last pose) animation now keeps priority/command/layerCtrl/
				// order/group/isProperty/layerDefault and the CPM-only fields
				EditorAnim ea = makeMainAnim(anim, type, name);
				float holdT = anim.length > 0 ? anim.length : 0;
				float saveTime = Timeline.time;
				Animation saveSel = Animation.selected;
				try {
					Animation.selected = anim;
					generateFrame(anims, ea, holdT, error);
				} finally {
					Timeline.time = saveTime;
					Animation.selected = saveSel;
				}
				editor.animations.add(ea);
				recordAnim(anim, ea);// CPM+ (W0C)

				type = Pair.of(AnimationType.SETUP, null);
				name = setupName;
			} else return;//Error
		}

		IPose pose = poseFor(type, anim.name);

		String fn = AnimationsLoaderV1.getFileName(pose, name);
		EditorAnim ea = new EditorAnim(editor, fn, type.getKey(), false);
		applyProps(ea, anim);
		ea.loop = loop;
		ea.displayName = name;
		ea.pose = pose;

		// CPM+ : keyframe-native export (see exportFrames)
		AnimationType tk = type.getKey();
		boolean runtimeLoop = tk == AnimationType.CUSTOM_POSE || tk == AnimationType.LAYER ||
				(tk == AnimationType.POSE && type.getValue() != null && !type.getValue().hasStateGetter()) ||
				(tk == AnimationType.GESTURE && loop);
		// CPM+ : value-driven types: CPM replaces the duration (AnimationExporter: DYNAMIC_DURATION_DIV)
		boolean dynamic = tk == AnimationType.VALUE_LAYER ||
				(tk == AnimationType.POSE && type.getValue() != null && type.getValue().hasStateGetter());
		exportFrames(anim, anims, ea, runtimeLoop, dynamic, tk.isStaged(), error);

		editor.animations.add(ea);
		recordAnim(anim, ea);// CPM+ (W0C)
		animMap.put(anim.uuid, ea);
		if(error[0])
			MultiWarnEntry.addOrIncEntry(warnings, I18n.format("bb-label.warn.rootScaling")).setPriority(-1).setKey("rootScaling");
		if(error[1])
			MultiWarnEntry.addOrIncEntry(warnings, I18n.format("bb-label.warn.globalRotation")).setPriority(-1).setKey("globalRotation");
	}

	/** CPM+ (W0C): every CPM animation made from a Blockbench animation, in creation order */
	private void recordAnim(Animation anim, EditorAnim ea) {
		if(anim.uuid != null)animAll.computeIfAbsent(anim.uuid, k -> new ArrayList<>()).add(ea);
	}

	// ======================================================================
	// CPM+ : animation export helpers
	// ======================================================================

	private static String idOf(String name) {
		if(name == null)return "";
		int i = name.indexOf('#');
		return i == -1 ? name : name.substring(0, i);
	}

	private static IPose poseFor(Pair<AnimationType, VanillaPose> type, String animName) {
		if(type.getKey() == AnimationType.CUSTOM_POSE)
			return new CustomPose(animName, 0);
		else if(type.getKey() == AnimationType.POSE)
			return type.getValue();
		return null;
	}

	/** CPM+ : copy every CPM animation property from the Blockbench animation. */
	private static void applyProps(EditorAnim ea, Animation anim) {
		ea.add = anim.additive;
		ea.command = anim.commandCtrl;
		ea.layerControlled = anim.layerCtrl;
		ea.priority = anim.getPriority();
		ea.isProperty = anim.isProperty;
		ea.order = anim.getOrder();
		ea.group = anim.group;
		ea.layerDefault = anim.getLayerDefault();
		ea.mustFinish = anim.isMustFinish();
		ea.hidden = anim.isAnimHidden();
		ea.maxValue = anim.getMaxValue();
		ea.interpolateValue = anim.isInterpolateVal();
	}

	/** CPM+ : main animation for 'once'/'hold' staged conversion (loops a single frame). */
	private EditorAnim makeMainAnim(Animation anim, Pair<AnimationType, VanillaPose> type, String name) {
		IPose pose = poseFor(type, anim.name);
		EditorAnim ea = new EditorAnim(editor, AnimationsLoaderV1.getFileName(pose, name), type.getKey(), false);
		applyProps(ea, anim);
		ea.intType = InterpolatorType.LINEAR_LOOP;
		ea.loop = true;
		ea.displayName = name;
		ea.pose = pose;
		return ea;
	}

	/** CPM+ : '<pose> Base' anims for staged 'p:' animations, once per pose, only when nothing else animates that pose. */
	private void addStagedBaseAnims() {
		for (VanillaPose pose : stagedBasePoses) {
			if(editor.animations.stream().anyMatch(e -> e.pose == pose && !e.type.isStaged()))continue;
			String nm = pose.name().toLowerCase(Locale.ROOT) + " Base";
			String fn = AnimationsLoaderV1.getFileName(pose, nm);
			EditorAnim ea = new EditorAnim(editor, fn, AnimationType.POSE, true);
			ea.intType = InterpolatorType.LINEAR_LOOP;
			ea.add = true;
			ea.displayName = nm;
			ea.pose = pose;
			editor.animations.add(ea);
		}
	}

	private void addAnimWarnings() {
		// CPM+ (G9): the formula rows ('X animações usam fórmulas', red rows per channel) come from FormulaTools
		if(!animsResampled.isEmpty())
			warnings.add(new WarnEntry("CPM+: These animations use bezier/mixed interpolation or keys off a uniform grid, they were resampled at the snapping rate with linear interpolation: " + list(animsResampled)).setInfo().setKey("resampled"));
		if(!animsFastRot.isEmpty())
			warnings.add(new WarnEntry("CPM+: Rotation changes by more than 180 degrees between two frames; CPM always takes the shortest path, add keyframes in between: " + list(animsFastRot)).setKey("fastRotation"));
		if(warnRootVis)
			warnings.add(new WarnEntry("CPM+: Visibility keyframes on vanilla root parts are ignored (CPM can't animate root part visibility). Animate a child group instead.").setInfo().setKey("rootVisibility"));
		if(warnScale0)
			warnings.add(new WarnEntry("CPM+: Scale 0 keyframes have no effect in CPM (that axis keeps its normal size). Use a small value like 0.01 to collapse a part, or hide it with a visibility keyframe.").setInfo().setKey("scale0"));
	}

	private static final String[] KEY_CHANNELS = {"position", "rotation", "scale", CPMCodec.COLOR};

	private static JsArrayE<Keyframe> channel(BoneAnimator ba, String ch) {
		switch (ch) {
		case "position": return ba.position;
		case "rotation": return ba.rotation;
		case "scale": return ba.scale;
		default: return ba.color;
		}
	}

	/** CPM+ : 'linear' / 'catmullrom' / 'step', or null for bezier/unknown. */
	private static String familyOf(String interp) {
		if(interp == null || interp.isEmpty() || interp.equals("linear"))return "linear";
		if(interp.equals("catmullrom"))return "catmullrom";
		if(interp.equals("step"))return "step";
		return null;
	}

	private static String familyOf(InterpolatorType it) {
		switch (it) {
		case LINEAR_LOOP:
		case LINEAR_SINGLE:
			return "linear";
		case NO_INTERPOLATE:
			return "step";
		default:
			return "catmullrom";
		}
	}

	private static boolean isLoopType(InterpolatorType it) {
		return it == InterpolatorType.POLY_LOOP || it == InterpolatorType.LINEAR_LOOP || it == InterpolatorType.TRIG_LOOP;
	}

	/** LINEAR_SINGLE / TRIG_SINGLE put frame k at k*D/(N-1). */
	private static boolean isRemapType(InterpolatorType it) {
		return it == InterpolatorType.LINEAR_SINGLE || it == InterpolatorType.TRIG_SINGLE;
	}

	private static boolean isTrig(InterpolatorType it) {
		return it == InterpolatorType.TRIG_LOOP || it == InterpolatorType.TRIG_SINGLE;
	}

	private static boolean hasMolang(Keyframe kf) {
		if(kf.data_points == null)return false;
		for (int i = 0; i < kf.data_points.length; i++) {
			JsPropertyMap<Object> dp = Js.asPropertyMap(kf.data_points.getAt(i));
			for (String ax : new String[] {"x", "y", "z"}) {
				Object o = dp.get(ax);
				if(o != null && "string".equals(Js.typeof(o))) {
					String s = ((String) o).trim();
					if(s.isEmpty())continue;
					try {
						Double.parseDouble(s);
					} catch (NumberFormatException e) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean hasHiddenKey(BoneAnimator ba) {
		if(!ba.hasVisible())return false;
		for (Keyframe kf : ba.visible.array()) {
			if(kf.data_points != null && kf.data_points.length > 0 && !kf.data_points.getAt(0).getVisible())return true;
		}
		return false;
	}

	private static int snapFps(Animation anim) {
		Object o = Js.asPropertyMap(anim).get("snapping");
		double d = o != null && "number".equals(Js.typeof(o)) ? Js.asDouble(o) : 20;
		if(Double.isNaN(d) || d < 1)d = 20;// CPM+ : snapping 0 used to export 0 frames
		return (int) Math.min(500, Math.round(d));
	}

	private static boolean onGrid(List<Float> times, float h, float len) {
		if(!(h > 0))return false;
		double eps = Math.min(1.5e-3, h * 0.05);
		for (Float t : times) {
			if(t > len + eps)return false;
			double q = t / (double) h;
			if(Math.abs(q - Math.round(q)) * h > eps)return false;
		}
		return true;
	}

	private static int findGrid(List<Float> times, float len, int maxM) {
		for (int m = 1; m <= maxM; m++) {
			if(onGrid(times, len / m, len))return m;
		}
		return -1;
	}

	private static final int SIG_STRIDE = 13;// pos3 rot3 scale3 color3 vis1

	/** CPM+ : sample every exported channel at time t (for grid/seam comparisons). */
	private double[] sig(List<BoneAnimator> anims, float t) {
		AnimSampler.setTime(t);// CPM+ (G9): Timeline.time + 'this' values cleared (shared sampler)
		double[] r = new double[anims.size() * SIG_STRIDE];
		for (int i = 0; i < anims.size(); i++) {
			BoneAnimator a = anims.get(i);
			ModelElement me = uuidLookup.get(a.uuid);
			int o = i * SIG_STRIDE;
			putVec(r, o, a.isMuted("position") ? null : Animation.interpolate(a.position, t));
			putVec(r, o + 3, a.isMuted("rotation") ? null : Animation.interpolate(a.rotation, t));
			putVec(r, o + 6, a.isMuted("scale") ? null : Animation.interpolate(a.scale, t));
			putVec(r, o + 9, a.isMuted(CPMCodec.COLOR) ? null : Animation.interpolate(a.color, t));
			boolean root = me == null || me.type == ElementType.ROOT_PART;
			r[o + 12] = root ? 0 : (Animation.interpolateVisible(a, t, me == null || !me.hidden) ? 1 : 0);
		}
		return r;
	}

	private static void putVec(double[] r, int o, JsVec3 v) {
		if(v == null) {
			r[o] = r[o + 1] = r[o + 2] = Double.NaN;
		} else {
			r[o] = v.x;
			r[o + 1] = v.y;
			r[o + 2] = v.z;
		}
	}

	/**
	 * CPM+ : loop seam check (end sample vs frame 0). Visibility is per bucket (frame k shows from k*D/N) and goes
	 * back to frame 0 by itself when the loop wraps, so it is not compared at the end: a looping layer that shows a
	 * part in its last frame was exported as POLY_SINGLE with an extra frame and a longer duration (4000 -> 5333 ms).
	 */
	private static boolean sameSeam(double[] end, double[] start, boolean modRot) {
		double[] e = java.util.Arrays.copyOf(end, end.length);
		for (int i = SIG_STRIDE - 1; i < e.length; i += SIG_STRIDE)e[i] = start[i];
		return same(e, start, modRot);
	}

	private static boolean same(double[] a, double[] b) {
		return same(a, b, true);
	}

	/** @param modRot compare rotations modulo 360 (only valid when checking an imported CPM grid) */
	private static boolean same(double[] a, double[] b, boolean modRot) {
		for (int i = 0; i < a.length; i++) {
			double x = a[i], y = b[i];
			boolean nx = Double.isNaN(x), ny = Double.isNaN(y);
			if(nx || ny) {
				if(nx != ny)return false;
				continue;
			}
			double d = x - y;
			int c = i % SIG_STRIDE;
			if(modRot && c >= 3 && c < 6) {// rotation: compare modulo 360
				d = d % 360.0;
				if(d > 180)d -= 360;
				if(d < -180)d += 360;
			}
			if(Math.abs(d) > 1e-3)return false;
		}
		return true;
	}

	private static boolean fastRotation(double[] a, double[] b) {
		for (int i = 0; i < a.length; i++) {
			int c = i % SIG_STRIDE;
			if(c >= 3 && c < 6 && !Double.isNaN(a[i]) && !Double.isNaN(b[i]) && Math.abs(a[i] - b[i]) > 180.001)return true;
		}
		return false;
	}

	/**
	 * CPM+ : keyframe-native animation export (replaces resampling at 'snapping' fps).
	 *
	 * 1. Collect the union of keyframe times (non-muted channels of exported groups) and the
	 *    interpolation family of the keys (linear / catmullrom / step).
	 * 2. If the animation came from CPM (cpm_intType/cpm_frames stored) and the keys still sit on
	 *    that grid with matching interpolation, export exactly the original N frames with the original
	 *    interpolator and duration -> lossless round trip, no extra frames.
	 * 3. Otherwise, if all keys lie on a uniform grid k*L/M (smallest M), export those grid times:
	 *    linear -> LINEAR_LOOP (looping trigger + seamless) / LINEAR_SINGLE incl. the end pose,
	 *    catmullrom -> POLY_* (or TRIG_* if stored), step -> NO_INTERPOLATE.
	 * 4. Bezier, mixed interpolation, molang or off-grid keys -> resample at snapping fps (>=1) with
	 *    LINEAR, including the final pose for non-looping animations.
	 * Molang is evaluated at each sample time (Timeline.time is moved, then restored).
	 * Always at least one frame, duration never 0.
	 */
	private void exportFrames(Animation anim, List<BoneAnimator> anims, EditorAnim ea, boolean runtimeLoop, boolean dynamic, boolean staged, boolean[] error) {
		float len = anim.length > 0 ? anim.length : 0;
		InterpolatorType stored = anim.getCpmIntType();
		int sN = anim.getCpmFrames();
		int sD = anim.getCpmDuration();

		List<Float> times = new ArrayList<>();
		String family = null;
		boolean mixed = false, molang = false, hasVis = false;
		for (BoneAnimator ba : anims) {
			ModelElement me = uuidLookup.get(ba.uuid);
			boolean root = me == null || me.type == ElementType.ROOT_PART;
			for (String ch : KEY_CHANNELS) {
				JsArrayE<Keyframe> arr = channel(ba, ch);
				if(arr == null || arr.length == 0 || ba.isMuted(ch))continue;
				for (Keyframe kf : arr.array()) {
					times.add(kf.time);
					if(hasMolang(kf))molang = true;
					if(arr.length < 2)continue;// interpolation of a lone key is irrelevant
					String fam = familyOf(kf.interpolation);
					if(fam == null)mixed = true;
					else if(family == null)family = fam;
					else if(!family.equals(fam))mixed = true;
				}
			}
			if(ba.hasVisible() && !ba.isMuted(CPMCodec.VISIBILITY)) {
				if(root) {
					if(hasHiddenKey(ba))warnRootVis = true;
				} else {
					for (Keyframe kf : ba.visible.array()) {
						times.add(kf.time);
						hasVis = true;
					}
				}
			}
		}
		float maxT = 0;
		for (Float t : times)maxT = Math.max(maxT, t);
		if(maxT > len + 1e-4f)len = maxT;
		int durMs = (int) Math.round(len * 1000.0);
		if(sD > 0 && Math.abs(len * 1000.0 - sD) < 1.5)durMs = sD;
		int defDur = sD > 0 ? sD : staged ? 50 : 1000;

		if(times.isEmpty()) {
			// CPM+ : still export one frame so the animation (button / toggle) exists
			ea.addFrame();
			ea.duration = durMs > 0 ? durMs : defDur;
			ea.intType = stored != null ? stored : InterpolatorType.LINEAR_LOOP;
			return;
		}

		float saveTime = Timeline.time;
		Animation saveSel = Animation.selected;
		try {
			Animation.selected = anim;

			if(len < 1e-4f) {
				// CPM+ : zero-length animation (e.g. a single toggle key at t=0): 1 frame with that state
				generateFrame(anims, ea, 0, error);
				ea.duration = defDur;
				ea.intType = stored != null ? stored : InterpolatorType.LINEAR_LOOP;
				return;
			}

			// CPM+ : no interpolated transform track (only visibility / lone constant keys): step is exact
			// (NO_INTERPOLATE frames and visibility buckets are both k*D/N), linear would shift visibility
			if(family == null)family = stored != null ? familyOf(stored) : "step";
			int fps = snapFps(anim);

			if(!mixed && !molang) {
				// 2. stored CPM grid
				if(stored != null && sN >= 1 && family.equals(familyOf(stored))) {
					boolean remap = sN > 1 && isRemapType(stored);
					int m0 = remap ? sN - 1 : sN;
					float h0 = len / m0;
					if(onGrid(times, h0, len)) {
						boolean ok;
						if(sN == 1)ok = same(sig(anims, 0), sig(anims, len));
						else if(isLoopType(stored))ok = sameSeam(sig(anims, len), sig(anims, 0), true);
						else if(stored == InterpolatorType.POLY_SINGLE)ok = same(sig(anims, (m0 - 1) * h0), sig(anims, len));
						else if(stored == InterpolatorType.NO_INTERPOLATE)ok = runtimeLoop || same(sig(anims, (m0 - 1) * h0), sig(anims, len));
						else ok = true;
						if(ok) {
							double[] prev = null;
							for (int k = 0; k < sN; k++) {
								float t = k * h0;
								double[] s = sig(anims, t);
								if(prev != null && fastRotation(prev, s))addOnce(animsFastRot, anim.name);
								prev = s;
								generateFrame(anims, ea, t, error);
							}
							ea.intType = stored;
							ea.duration = Math.max(1, durMs);
							return;
						}
					}
				}

				// 3. uniform grid
				// grid no finer than the snapping grid (or 2x the number of distinct key times),
				// so irregular keys are resampled instead of producing hundreds of frames
				List<Float> distinct = new ArrayList<>();
				for (Float t : times) {
					boolean found = false;
					for (Float d : distinct)if(Math.abs(d - t) < 1e-4f) { found = true; break; }
					if(!found)distinct.add(t);
				}
				int maxM = Math.min(1000, Math.max(MathHelper.ceil(len * fps - 1e-3f), 2 * distinct.size()));
				int m = findGrid(distinct, len, maxM);
				if(m > 0) {
					emitGrid(anim, anims, ea, len, m, family, stored, runtimeLoop, dynamic, hasVis, fps, error);
					return;
				}
			}

			// 4. fallback: resample
			if(molang)addOnce(animsBakedMolang, anim.name);
			else addOnce(animsResampled, anim.name);
			int m = Math.max(1, Math.round(len * fps));
			emitGrid(anim, anims, ea, len, m, "linear", null, runtimeLoop, dynamic, hasVis, fps, error);
		} finally {
			Timeline.time = saveTime;
			Animation.selected = saveSel;
		}
	}

	private static void addOnce(List<String> l, String n) {
		if(!l.contains(n))l.add(n);
	}

	/** CPM+ : a rotation step between two exported frames must stay BELOW this (degrees): 180 itself is ambiguous in CPM */
	public static final double ROT_SPLIT = 179.999;

	/** CPM+ : largest rotation change (degrees, raw values) between consecutive samples */
	private static double maxRotStep(List<double[]> s) {
		double max = 0;
		for (int k = 1; k < s.size(); k++) {
			double[] a = s.get(k - 1), b = s.get(k);
			for (int i = 0; i < a.length; i++) {
				int c = i % SIG_STRIDE;
				if(c >= 3 && c < 6 && !Double.isNaN(a[i]) && !Double.isNaN(b[i]))max = Math.max(max, Math.abs(a[i] - b[i]));
			}
		}
		return max;
	}

	/** CPM+ : smallest multiple of m that is >= target (capped at 1000, never below m) */
	private static int refineGrid(int m, int target) {
		if(target <= m)return m;
		int f = (target + m - 1) / m;
		int r = m * f;
		if(r > 1000)r = Math.max(m, (1000 / m) * m);
		return r;
	}

	private List<double[]> sampleGrid(List<BoneAnimator> anims, float len, int m) {
		float h = len / m;
		List<double[]> s = new ArrayList<>();
		for (int k = 0; k <= m; k++)s.add(sig(anims, k * h));
		return s;
	}

	/**
	 * CPM+ : emit frames on the uniform grid k*len/m.
	 * @param dynamic VALUE_LAYER / state-getter pose: CPM ignores the duration and maps the value 0..1
	 *                onto the frame list, so the 'extra frame + longer duration' trick can't be used.
	 * @param hasVis  non-root visibility keys exist (visibility buckets are k*D/N in every CPM interpolator)
	 */
	private void emitGrid(Animation anim, List<BoneAnimator> anims, EditorAnim ea, float len, int m, String family, InterpolatorType stored,
			boolean runtimeLoop, boolean dynamic, boolean hasVis, int fps, boolean[] error) {
		List<double[]> s = sampleGrid(anims, len, m);
		boolean stepFam = family.equals("step");
		// CPM+ (W3-ANIMGEN, G24): a continuous spin (a rotation turning +-360*k over the animation) is sampled at the
		// snapping rate (LINEAR_SINGLE with the end pose, below), so 'Corrigir' of the spin speed guard works
		if(!stepFam) {
			int sm = com.tom.cpm.blockbench.tools.anim.MotionPresets.spinGrid(m, len, fps,
					com.tom.cpm.blockbench.tools.anim.MotionPresets.maxSpinTurns(s.get(0), s.get(m), SIG_STRIDE, 3));
			if(sm != m) {
				m = sm;
				s = sampleGrid(anims, len, m);
			}
		}
		// CPM+ : CPM always interpolates rotation the short way: subdivide the grid (keys stay on it,
		// linear segments are reproduced exactly) until every step is below 180 degrees, so spins authored
		// in Blockbench don't play backwards / not at all. A step of EXACTLY 180 is split too: the short
		// way is ambiguous there and CPM took the other side (+90 -> -90 played through 180; real CPM
		// classes, beta34 rig probe).
		if(!stepFam) {
			double step = maxRotStep(s);
			if(step > ROT_SPLIT) {
				int nm = refineGrid(m, m * (int) Math.ceil(step / 170.0));
				if(nm > m) {
					m = nm;
					s = sampleGrid(anims, len, m);
				}
				if(maxRotStep(s) > ROT_SPLIT)addOnce(animsFastRot, anim.name);
			}
		}
		float h = len / m;
		// CPM+ : exact comparison (rotation NOT modulo 360): 0 and 360 degrees are a full turn apart
		// for the interpolation, treating them as equal turned spins into loops that run backwards
		boolean allEq = true;
		for (int k = 1; k <= m && allEq; k++)allEq = same(s.get(0), s.get(k), false);
		boolean seamless = sameSeam(s.get(m), s.get(0), false);
		boolean endHold = same(s.get(m), s.get(m - 1), false);
		double durS = len;
		int n;
		InterpolatorType type;
		boolean trig = stored != null && isTrig(stored);
		if(allEq) {
			n = 1;
			type = stepFam ? InterpolatorType.NO_INTERPOLATE : family.equals("catmullrom") ? (trig ? InterpolatorType.TRIG_LOOP : InterpolatorType.POLY_LOOP) : InterpolatorType.LINEAR_LOOP;
		} else if(family.equals("linear") || (family.equals("catmullrom") && trig)) {
			boolean lin = family.equals("linear");
			if(runtimeLoop && seamless) {
				type = lin ? InterpolatorType.LINEAR_LOOP : InterpolatorType.TRIG_LOOP;
				n = m;
			} else {
				type = lin ? InterpolatorType.LINEAR_SINGLE : InterpolatorType.TRIG_SINGLE;
				// CPM+ : *_SINGLE puts transform frame k at k*D/(N-1) but visibility buckets at k*D/N;
				// refine the grid so a visibility switch is at most one snapping frame early
				if(hasVis) {
					m = refineGrid(m, MathHelper.ceil(len * fps - 1e-3f) - 1);
					h = len / m;
				}
				n = m + 1;// frame k at k*D/(N-1) = k*h, end pose included
			}
		} else if(family.equals("catmullrom")) {
			if(runtimeLoop && seamless) {
				type = InterpolatorType.POLY_LOOP;
				n = m;
			} else if(endHold) {
				type = InterpolatorType.POLY_SINGLE;
				n = m;
			} else if(dynamic) {
				// CPM+ : value-driven: no duration trick possible, resample onto a remapping LINEAR_SINGLE grid
				addOnce(animsResampled, anim.name);
				type = InterpolatorType.LINEAR_SINGLE;
				m = refineGrid(m, MathHelper.ceil(len * fps - 1e-3f));
				h = len / m;
				n = m + 1;
			} else {
				type = InterpolatorType.POLY_SINGLE;
				n = m + 1;
				durS = len + h;// POLY_SINGLE frames are at k*D/N
			}
		} else {
			type = InterpolatorType.NO_INTERPOLATE;
			if(runtimeLoop || endHold || dynamic) {
				// dynamic: value v shows frame floor(v*N) = BB time v*len (only v == 1.0 exactly differs)
				n = m;
			} else {
				n = m + 1;
				durS = len + h;
			}
		}
		for (int k = 0; k < n; k++) {
			generateFrame(anims, ea, k * h, error);
		}
		ea.intType = type;
		ea.duration = Math.max(1, (int) Math.round(durS * 1000.0));
	}

	private void generateFrame(List<BoneAnimator> anims, EditorAnim ea, float ftime, boolean[] error) {
		AnimSampler.setTime(ftime);// CPM+ (G9): molang (query.anim_time etc.) is evaluated at the sample time, 'this' never leaks
		ea.addFrame();
		AnimFrame f = ea.getSelectedFrame();

		anims.forEach(a -> {
			ModelElement me = uuidLookup.get(a.uuid);
			if(me != null) {
				Vec3f addPos = me.pos;
				Vec3f addRot = me.rotation;
				boolean root = me.type == ElementType.ROOT_PART;
				if(!ea.add && root) {
					VanillaModelPart part = (VanillaModelPart) me.typeData;
					PartValues pv = DirectParts.getPartOverrides(part, editor.skinType);
					if(pv instanceof DirectPartValues) {
						addRot = addRot.add(((DirectPartValues)pv).getRotation());
					}
					addPos = addPos.add(pv.getPos());
				}

				// CPM+ : muted channels are not exported
				JsVec3 pos = a.isMuted("position") ? null : Animation.interpolate(a.position, ftime);
				JsVec3 rot = a.isMuted("rotation") ? null : Animation.interpolate(a.rotation, ftime);
				JsVec3 scl = a.isMuted("scale") ? null : Animation.interpolate(a.scale, ftime);
				// CPM+ : colour for recolor AND untextured (colour) cubes, or the cubes of a multi-cube group
				List<ModelElement> colTargets = new ArrayList<>();
				if(!root) {
					if(me.recolor || !me.texture)colTargets.add(me);
					else {
						List<ModelElement> ch = colorTargets.get(me);
						if(ch != null)ch.stream().filter(c -> c.recolor || !c.texture).forEach(colTargets::add);
					}
				}
				JsVec3 color = !colTargets.isEmpty() && !a.isMuted(CPMCodec.COLOR) ? Animation.interpolate(a.color, ftime) : null;

				FrameData dt = f.getComponents().get(me);
				if(dt == null) {
					dt = f.new FrameData(me);
					f.getComponents().put(me, dt);
				}

				if(pos != null) {
					Vec3f v = pos.toVecF();
					v.x *= -1;
					v.y *= -1;
					if(!ea.add)v = v.add(addPos);
					dt.setPos(v);
				}
				if(rot != null) {
					Vec3f v = rot.toVecF();
					v.x *= -1;
					v.y *= -1;
					if(!ea.add)v = v.add(addRot);
					ActionBuilder.limitVec(v, 0, 360, true);
					dt.setRot(v);
					if(a.rotation_global)error[1] = true;
				}
				if(scl != null) {
					if(root)
						error[0] = true;
					else {
						Vec3f s = scl.toVecF();
						// CPM+ : CPM ignores a 0 scale component (RenderedCube.setRenderScale). Keep the value as-is
						// (rewriting it would flatten the cube in game and break lossless round trips); only
						// tell the user, and only for animations authored in Blockbench (not imported from CPM).
						if((s.x == 0 || s.y == 0 || s.z == 0) && Animation.selected != null && Animation.selected.getCpmIntType() == null)
							warnScale0 = true;
						dt.setScale(s);
					}
				}
				// CPM+ : root parts can't be shown/hidden by CPM animations and their exported element is
				// always hidden (the vanilla box becomes a cube), so a root visibility key (old imports
				// wrote visible=true on every root) must not set show=true -> vanilla box drawn on top.
				if(!root)
					dt.setShow(Animation.interpolateVisible(a, ftime, !me.hidden));
				if(color != null) {
					// CPM+ : CPM colours are 0..255
					Vec3f c = color.toVecF();
					c.x = Math.max(0, Math.min(255, Math.round(c.x)));
					c.y = Math.max(0, Math.min(255, Math.round(c.y)));
					c.z = Math.max(0, Math.min(255, Math.round(c.z)));
					for (ModelElement target : colTargets) {
						FrameData td = f.getComponents().get(target);
						if(td == null) {
							td = f.new FrameData(target);
							f.getComponents().put(target, td);
						}
						td.setColor(new Vec3f(c));
					}
				}
			}
		});
	}

	private Promise<Boolean> fixStaged(Animation a) {
		return new Promise<>((res, rej) -> {
			JsBuilder<String> b = new JsBuilder<>();
			for (VanillaPose p : VanillaPose.VALUES) {
				if(p == VanillaPose.CUSTOM)continue;
				String id = p.name().toLowerCase(Locale.ROOT);
				b.put("p:" + id, I18n.get("label.cpm.pose." + id));
			}
			Project.animations.forEach(an -> {
				Pair<AnimationType, VanillaPose> type = Animation.parseType(an.type);
				if(type.getKey() == AnimationType.CUSTOM_POSE) {
					b.put("c:" + an.name, an.name);
				} else if(type.getKey() == AnimationType.GESTURE || type.getKey() == AnimationType.LAYER) {
					b.put("g:" + idOf(an.name), idOf(an.name));// CPM+ (G54): one entry per button ('Dance4#corpo' -> 'g:Dance4')
				}
			});

			DialogProperties pr = new DialogProperties();
			pr.id = "cpm_fix_staged";
			pr.title = I18n.get("bb-label.fixStagedAnim.title");
			pr.singleButton = false;
			Dialog.FormSelectElement selPart = Dialog.FormSelectElement.make(I18n.format("bb-label.fixStagedAnim.selectBase"));
			selPart.value = b.first();
			selPart.options = b.build();
			pr.form = new JsBuilder<>().put("root", selPart).build();
			pr.onConfirm = d -> {
				JsPropertyMap<String> dr = Js.uncheckedCast(d);
				String root = dr.get("root");

				Undo.initEdit(UndoData.make(a));
				a.name = root;
				Undo.finishEdit(I18n.format("bb-label.autoFixApplied", I18n.get("bb-label.autoFix.stagedAnim")));

				res.onInvoke(true);
				return true;
			};
			pr.onCancel = () -> {
				res.onInvoke(false);
				return true;
			};
			new Dialog(pr).show();
		});
	}

	protected static PartPosition loadPartPos(JsonMap fpHand, String name) {
		PartPosition p = new PartPosition();
		JsonMap map = fpHand.getMap(name);
		if(map != null) {
			Vec3f pos = new Vec3f(map.getMap("position"), new Vec3f());
			Vec3f rotation = new Vec3f(map.getMap("rotation"), new Vec3f());
			Vec3f scale = new Vec3f(map.getMap("scale"), new Vec3f());
			p.setRenderScale(pos, new Rotation(rotation, true), scale);
		}
		return p;
	}

	private class AnimEnc extends AnimationsLoaderV1 {

		public void load(JsonMap data) {
			JsonMap an = data.getMap("anims");
			if(an != null)an.asMap().keySet().forEach(a -> loadAnimation(editor, a, an.getMap(a)));// CPM+ (F24): 'anims' may be missing
			// CPM+ (F24): 'free' alone is enough (def defaults to !free.contains(layer), like CPM's AnimEncConfigPopup);
			// unknown layer names are skipped (CPM's saveEnc would throw on them)
			if(data.containsKey("free")) {
				try {
					AnimationEncodingData enc = new AnimationEncodingData();
					data.getList("free").forEach(v -> {
						PlayerSkinLayer l = v instanceof String ? PlayerSkinLayer.getLayer((String) v) : null;
						if(l != null)enc.freeLayers.add(l);
					});
					JsonMap def = data.getMap("def");
					if(def != null)def.forEach((k, v) -> {
						PlayerSkinLayer l = PlayerSkinLayer.getLayer(k);
						if(l != null && v instanceof Boolean)enc.defaultLayerValue.put(l, (Boolean) v);
					});
					else for (PlayerSkinLayer l : PlayerSkinLayer.VALUES)enc.defaultLayerValue.put(l, !enc.freeLayers.contains(l));
					editor.animEnc = enc;
				} catch (Throwable e) {
					DomGlobal.console.warn("CPM+: skin layers for animations (anim_enc) could not be read, skipped", e);
				}
			}
		}

		@Override
		protected void findPartRef(AnimFrame frm, JsonMap map, Consumer<ModelElement> me) {
			ModelElement el = uuidLookup.get(map.getString("uuid"));
			if(el != null)me.accept(el);
		}
	}

	private Promise<Void> loadTexture(Texture t, TextureSheetType tst) {
		ETextures e = editor.textures.get(tst);
		if(e == null) {
			e = new ETextures(editor, tst);
			editor.textures.put(tst, e);
			Image def = new Image(tst.getDefSize().x, tst.getDefSize().y);
			e.provider.size = new Vec2i(tst.getDefSize());
			try(InputStream is = ModelDefinitionLoader.class.getResourceAsStream("/assets/cpm/textures/template/" + tst.name().toLowerCase() + ".png")) {
				def = Image.loadFrom(is);
			} catch (IOException ex) {
			}
			e.setDefaultImg(def);
			e.setImage(new Image(def));
		}
		if(tst.editable) {
			final ETextures fe = e;
			// CPM+ : the skin was the wearer's own skin on import and is unchanged -> don't bake the
			// editor's default skin into the model (keep 'use player skin')
			if(tst == TextureSheetType.SKIN) {
				String wh = Project.getData().wearerSkinHash;
				if(wh != null && t.source != null && wh.equals(ProjectData.hash(t.source))) {
					fe.provider.size = new Vec2i(t.uv_width, t.uv_height);
					wearerSkinKept = true;
					return Promise.resolve((Void) null);
				}
			}
			return loadTextureImage(t).then(img -> {
						fe.provider.size = new Vec2i(t.uv_width, t.uv_height);
						fe.setImage(img);
						fe.setEdited(true);
						return Promise.resolve((Void) null);
					}).catch_(er -> {
						// CPM+ (B5): a null / odd rejection must still give the message (error.toString() threw)
						String cause;
						try {
							cause = er == null ? "null" : String.valueOf(Java.convertRejectObject(er));
						} catch (Throwable ex) {
							cause = String.valueOf(er);
						}
						return Promise.reject(new UserException(I18n.formatNl("bb-label.error.failedToLoadTexture", t.name, cause)));
					});
		}
		return Promise.resolve((Void) null);
	}

	/**
	 * CPM+ (load A6/A7): pixels of a Blockbench texture: the decode of the SAME source is reused while one export /
	 * validation runs (quick fixes re-run the whole export), else fetch(source) -> Blob -> browser decode (no
	 * FileReader / second data-URL fetch; the same createImageBitmap(premultiplyAlpha none) pixels as before).
	 * Encoding stays pngj.
	 */
	private Promise<Image> loadTextureImage(Texture t) {
		final String src = t.source;
		TextureDecodeCache.Hit hit = TextureDecodeCache.get(t.uuid, src);
		if(hit != null) {
			texBytes += hit.bytes;// CPM+ : size estimate
			return Promise.resolve(hit.image);
		}
		return DomGlobal.fetch(src).then(b -> b.blob()).then(blob -> {
			// CPM+ : size estimate, the same number as v0.7's (base64 length * 3 / 4)
			long size = (((long) blob.size + 2) / 3) * 3;
			texBytes += size;
			return ImageIO.loadImage(blob).then(img -> {
				TextureDecodeCache.put(t.uuid, src, img, size);
				return Promise.resolve(img);
			});
		});
	}

	private VanillaModelPart part(String name) {
		return RootUtil.part(name);// CPM+ (W0C): shared
	}

	private void convert(ModelElement elem, Group group, Cube cube) {
		convert(elem, group.origin, cube);
	}

	private void convert(ModelElement elem, JsVec3 origin, Cube cube) {
		elem.name = cube.name;
		elem.mcScale = cube.inflate;
		// CPM+ : negative size cubes (to < from, #493) are normalized
		float fx = Math.min(cube.from.x, cube.to.x), tx = Math.max(cube.from.x, cube.to.x);
		float fy = Math.min(cube.from.y, cube.to.y), ty = Math.max(cube.from.y, cube.to.y);
		float fz = Math.min(cube.from.z, cube.to.z), tz = Math.max(cube.from.z, cube.to.z);
		if(cube.to.x < cube.from.x || cube.to.y < cube.from.y || cube.to.z < cube.from.z)negativeSize.add(cube.name);
		elem.size = new Vec3f(tx - fx, ty - fy, tz - fz);
		elem.offset.x = origin.x - tx;
		elem.offset.y = -fy - elem.size.y + origin.y;
		elem.offset.z = fz - origin.z;
		elem.glow = cube.glow;
		int rc = cube.getRecolor();
		elem.recolor = rc != -1;
		if(rc != -1)elem.rgb = rc;
		elem.extrude = cube.extrude;
		CubeData cd = cube.getData();
		if(cd.getExtrude() != null) {
			// CPM+ : extruded sprite. Its UV is u/v/textureSize, not per-face, so no per-face decimal
			// fixer ops; textureSize follows the sheet upscale instead.
			elem.extrude = true;
			CubeData.Extrude ext = cd.getExtrude();
			int ts = Math.max(1, ext.getTs());
			elem.u = ext.getU();
			elem.v = ext.getV();
			// CPM+ : moving the UV in Blockbench now has an effect (was always the stored u/v)
			float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE;
			for(Direction d : Direction.VALUES) {
				CubeFace cf = cube.faces.getFace(d);
				if(cf == null || cf.uv == null || cf.isDisabled() || cf.uv.isEmpty())continue;
				minU = Math.min(minU, Math.min(cf.uv.sx, cf.uv.ex));
				minV = Math.min(minV, Math.min(cf.uv.sy, cf.uv.ey));
			}
			if(minU != Float.MAX_VALUE) {
				int du = Math.round(minU / ts);
				int dv = Math.round(minV / ts);
				if(du != elem.u || dv != elem.v) {
					elem.u = du;
					elem.v = dv;
				}
			}
			// CPM+ : mirror was lost (extruded cubes are per-face in Blockbench)
			elem.mirror = cube.mirror_uv;
			elem.faceUV = null;
			elem.texture = true;
			elem.textureSize = ts;
			UVMul m = uvMul.get(sheetOf(elem));
			if(m != null)m.elems.add(new DecimalFixOp(cube, null, 1, 4, m, a -> elem.textureSize = ts * a));
			return;
		}
		// CPM+ : longer than the game's 25.5 px: size x mesh scale, same mesh (FormatLimitsCheck.fitSize). CPM lays a
		// box UV out from the size, so a fitted cube takes the per-face UVs Blockbench shows for it.
		boolean fitted = !elem.extrude && FormatLimitsCheck.fitSize(elem);
		convertUV(elem, cube, fitted);
	}

	private TextureSheetType sheetOf(ModelElement elem) {
		VanillaModelPart part = (VanillaModelPart) elem.getRoot().typeData;
		TextureSheetType tst = TextureSheetType.SKIN;
		if(part instanceof RootModelType) {
			tst = RootGroups.getGroup((RootModelType) part).getTexSheet((RootModelType) part);
		}
		return tst;
	}

	private void convertGroup(ModelElement elem, Group group) {
		uuidLookup.put(group.uuid, elem);
		elem.hidden = group.hidden;
		if(group.copyTransform != null && !group.copyTransform.isEmpty()) {
			postConvert.add(() -> {
				JsonMap s = JsonUtil.fromJson(group.copyTransform);
				ModelElement me = uuidLookup.get(s.get("uuid"));
				if(me != null) {
					CopyTransformEffect ct = new CopyTransformEffect(elem);
					ct.load(s);
					// CPM+ (B1): the game reads 'r' (CPM's own editor writes 'color'): accept either
					ct.copyColor = s.containsKey("r") ? s.getBoolean("r", false) || s.getBoolean("color", false) : s.getBoolean("color", false);
					ct.from = me;
					elem.copyTransform = ct;
				}
			});
		}
		GroupData gd = group.getData();
		if(gd.getItemRenderer() != null) {
			String name = gd.getItemRenderer();
			for(ItemSlot slot : ItemSlot.VALUES) {
				if(name.equalsIgnoreCase(slot.name())) {
					elem.itemRenderer = new ItemRenderer(slot, 0);
					elem.size = new Vec3f();
					elem.faceUV = null;
					// CPM+ : item scale (meshScale) was lost
					if(gd.getItemScale() != null)elem.meshScale = new Vec3f(gd.getItemScale());
					break;
				}
			}
		}
		// CPM+ : round-trip fields (stable storeID, render scale, locked, name colour)
		long sid = gd.getStoreID();
		if(sid != 0)elem.storeID = sid;
		else if(group.is_rotation_subgroup) {
			// generated '_r1' groups get a new uuid every export: keep the cube's id, or derive one from the parent
			if(elem.storeID == 0)elem.storeID = stableId((group.parent != null ? group.parent.uuid : "") + "/" + group.name);
		} else elem.storeID = stableId(group.uuid);
		if(gd.getRenderScale() != null)elem.scale = new Vec3f(gd.getRenderScale());
		if(gd.isLocked())elem.locked = true;
		if(gd.getNameColor() != 0)elem.partNameColor = gd.getNameColor();
	}

	private static boolean isDecimal(float v) {
		// CPM+ : was v - floor(v), so float noise below an integer (2.9999998) counted as decimal
		return Math.abs(v - Math.round(v)) > 0.01f;
	}

	private void convertUV(ModelElement elem, Cube cube, boolean perFace) {
		// CPM+ : colour cube (box UV, no texture on any face): no UV at all. The recolor flag is not
		// set on colour elements by the CPM editor (texture=false already means 'use the colour').
		if (cube.isColorCube() && cube.getRecolor() != -1) {
			elem.texture = false;
			elem.recolor = false;
			return;
		}
		TextureSheetType tst = sheetOf(elem);
		UVMul m = uvMul.get(tst);
		if(m == null) {
			badTextureCubes.add(cube.name);// CPM+ : one aggregated warning
			return;
		}

		// CPM+ : a hidden face (texture null) can't be expressed with box UV -> per-face
		boolean anyDisabled = false;
		for(Direction d : Direction.VALUES) {
			CubeFace cf = cube.faces.getFace(d);
			if(cf != null && cf.isDisabled())anyDisabled = true;
		}
		// CPM+ : box UV offsets are integers in CPM; a fractional offset used to be rounded silently
		// (and the sheet still got upscaled). Per-face UV keeps it exact.
		boolean fracOffset = cube.box_uv && cube.uv_offset != null && (isDecimal(cube.uv_offset.x) || isDecimal(cube.uv_offset.y));
		if(fracOffset && !anyDisabled)fracBoxUV.add(cube.name);

		if(cube.box_uv && !perFace && !anyDisabled && !fracOffset && !isDecimal(elem.size.x) && !isDecimal(elem.size.y) && !isDecimal(elem.size.z)) {
			m.elems.add(new DecimalFixOp(cube, null, cube.uv_offset.x, 0, m, a -> elem.u = a));
			m.elems.add(new DecimalFixOp(cube, null, cube.uv_offset.y, 1, m, a -> elem.v = a));
			m.elems.add(new DecimalFixOp(cube, null, 1, 4, m, a -> elem.textureSize = a));
			elem.mirror = cube.mirror_uv;
		} else {
			elem.faceUV = new PerFaceUV();
			for(Direction d : Direction.VALUES) {
				CubeFace cf = cube.faces.getFace(d);
				// CPM+ : hidden faces (texture null) are removed too, not only 0,0,0,0 UVs
				if(cf.uv == null || cf.isDisabled() || cf.uv.isEmpty()) {
					elem.faceUV.faces.remove(d);
					continue;
				}
				Face f = elem.faceUV.faces.computeIfAbsent(d, __ -> new Face());
				m.elems.add(new DecimalFixOp(cube, d, cf.uv.sx, 2, m, a -> f.sx = a));
				m.elems.add(new DecimalFixOp(cube, d, cf.uv.sy, 3, m, a -> f.sy = a));
				m.elems.add(new DecimalFixOp(cube, d, cf.uv.ex, 2, m, a -> f.ex = a));
				m.elems.add(new DecimalFixOp(cube, d, cf.uv.ey, 3, m, a -> f.ey = a));
				if(d == Direction.UP || d == Direction.DOWN)
					f.rotation = intToRot((cf.rotation + 180) % 360);
				else
					f.rotation = intToRot(cf.rotation);
				f.autoUV = Js.isTruthy(cube.autouv) ? true : false;
			}
		}
		{
			boolean badTexture = false;
			for(Direction d : Direction.VALUES) {
				CubeFace cf = cube.faces.getFace(d);
				if(cf.uv == null || cf.isDisabled())continue;// CPM+
				if(!cf.uv.isEmpty() && (m.texId == null || !m.texId.equals(cf.texture))) {
					badTexture = true;
					break;
				}
			}
			if(badTexture)badTextureCubes.add(cube.name);// CPM+ : one aggregated warning
		}
	}

	private static class DecimalFixOp {
		private float val;
		private int mode;
		private UVMul mul;
		private IntConsumer set;
		private Cube cube;
		private Direction face;

		public DecimalFixOp(Cube cube, Direction face, float val, int mode, UVMul mul, IntConsumer set) {
			this.cube = cube;
			this.val = val;
			this.mode = mode;
			this.mul = mul;
			this.set = set;
			this.face = face;
		}

		public String getDisplayName() {
			if (face != null)return I18n.format("bb-label.warn.uvDecimalFixer.face", cube.name, I18n.get("label.cpm.dir." + face.name().toLowerCase(Locale.ROOT)));
			else return cube.name;
		}

		public boolean needsFix() {
			if((mode & 4) != 0)return false;
			float m = val * mul.baseMul * ((mode & 1) != 0 ? mul.y : mul.x);
			// CPM+ : Math.abs, 3.6 (-0.4) was not detected and silently rounded
			return Math.abs(m - Math.round(m)) > 0.05f;
		}

		public void apply() {
			if((mode & 4) != 0) {
				set.accept(mul.baseMul);
			} else if((mode & 2) != 0) {
				set.accept(Math.round(val * mul.baseMul * ((mode & 1) != 0 ? mul.y : mul.x)));
			} else
				set.accept(Math.round(val * ((mode & 1) != 0 ? mul.y : mul.x)));
		}
	}

	private static Rot intToRot(int r) {
		switch (r) {
		case 0: return Rot.ROT_0;
		case 180: return Rot.ROT_180;
		case 270: return Rot.ROT_270;
		case 90: return Rot.ROT_90;
		default: throw new RuntimeException();
		}
	}

	private ModelElement getPart(VanillaModelPart pmp) {
		for(ModelElement e : editor.elements) {
			if(e.typeData == pmp)return e;
		}
		ModelElement e = new ModelElement(editor, ElementType.ROOT_PART, pmp);
		editor.elements.add(e);
		if(pmp instanceof RootModelType) {
			RootGroups rg = RootGroups.getGroup((RootModelType) pmp);
			if(rg != null) {
				//TODO
			}
		}
		return e;
	}

	private void add(ModelElement parent, ModelElement me) {
		parent.children.add(me);
		me.parent = parent;
	}

	private static class UVMul {
		private int baseMul = 1;
		private float x = 1, y = 1;
		private Set<DecimalFixOp> elems = new HashSet<>();
		private String texId;
	}

	private class CPMElem {
		private Group group;
		private Cube cube;
		private List<CPMElem> children = new ArrayList<>();
		private ModelElement part;
		private Vec3f rotation;
		private Vec3f pos;
		private boolean reGrouped;

		public CPMElem(Group group, Cube cube, Vec3f rotation, Vec3f pos) {
			this.group = group;
			this.cube = cube;
			this.rotation = rotation;
			this.pos = pos;
		}

		public CPMElem(ModelElement part) {
			this.part = part;
		}

		public void finishConvert() {
			children.forEach(c -> {
				c.makePart(part);
				c.finishConvert();
			});
		}

		private void makePart(ModelElement parent) {
			if(part == null) {
				part = new ModelElement(editor);
				add(parent, part);
				part.rotation = rotation;
				part.pos = pos;
				part.texture = true;
				if(cube != null) {
					// CPM+ (NEW-VIS-EXPORT): the user's visibility, never the Animate-mode preview state
					part.showInEditor = VisState.persistentVisible(cube);
					part.storeID = stableCubeId(cube);// CPM+ : stable id (merged groups override it in convertGroup)
					convert(part, group, cube);
					if(!reGrouped)convertGroup(part, group);
					else colorTargets.computeIfAbsent(parent, k -> new ArrayList<>()).add(part);// CPM+ : colour keys of a multi-cube group go to its cubes
				} else {
					part.name = group.name;
					part.size = new Vec3f();
					convertGroup(part, group);
				}
				fixDecimals(part);
			}
		}

		public void resetTransform() {
			rotation = new Vec3f();
			pos = new Vec3f();
			reGrouped = true;
		}
	}
}
