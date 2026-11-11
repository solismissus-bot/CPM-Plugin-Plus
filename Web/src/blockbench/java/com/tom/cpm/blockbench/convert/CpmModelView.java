package com.tom.cpm.blockbench.convert;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.tom.cpl.math.Rotation;
import com.tom.cpl.math.Vec2i;
import com.tom.cpl.math.Vec3f;
import com.tom.cpl.util.Image;
import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.NativeFs;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.shared.MinecraftClientAccess;
import com.tom.cpm.shared.config.ResourceLoader.ResourceEncoding;
import com.tom.cpm.shared.definition.Link;
import com.tom.cpm.shared.definition.ModelDefinition;
import com.tom.cpm.shared.definition.ModelDefinitionLoader;
import com.tom.cpm.shared.editor.ETextures;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.Generators;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.elements.RootGroups;
import com.tom.cpm.shared.io.IOHelper;
import com.tom.cpm.shared.io.ModelFile;
import com.tom.cpm.shared.model.Cube;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RenderedCube;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.SkinType;
import com.tom.cpm.shared.model.TextureSheetType;
import com.tom.cpm.shared.model.render.ItemRenderer;
import com.tom.cpm.shared.model.render.PerFaceUV;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.shared.parts.ModelPartType;
import com.tom.cpm.shared.skin.TextureProvider;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.ArrayBuffer;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;

/**
 * CPM+ : a .cpmmodel (the file the game uses) opened in Blockbench to look at it, never to edit it.
 * <p>
 * The file is read the way the game reads it (ModelDefinitionLoader + resolveAll; the overflow comes from the file
 * itself; a gist link the file doesn't carry, like a skin uploaded on its own, is downloaded like the game does),
 * turned into a CPM editor and imported like a .cpmproject. A .cpmmodel keeps no
 * names and only compiled animations: cubes are called "cubo N" and the animations are not shown.
 * <p>
 * View only: every group and cube is locked, any edit is undone at once (showing/hiding parts is allowed) and the
 * project can't become a .cpmproject (ProjectConvert.convertToCPM). The mark lives in the project's cpm_data, so a
 * .bbmodel saved from it stays view-only.
 */
public final class CpmModelView {
	/** ProjectData extra that marks a view-only project */
	private static final String KEY = "cpmplusViewOnly";

	private CpmModelView() {
	}

	/** opens the bytes of a .cpmmodel in a new view-only project */
	public static Promise<Void> open(ArrayBuffer ab) {
		byte[] file = NativeFs.toBytes(ab);
		ImportProgress.begin();
		try {
			return ImportProgress.finish(build(file, new HashMap<>())).then(__ -> {
				Project.getData().putExtra(KEY, Boolean.TRUE).flush();
				lock(false);
				return Promise.resolve((Void) null);
			});
		} catch (Throwable e) {
			ImportProgress.end();
			return Promise.reject(e);
		}
	}

	/** reads the file into a new editor and imports it; a missing link is downloaded and the file read again */
	private static Promise<Void> build(byte[] file, Map<Link, byte[]> got) {
		return RenderSystem.withContext(() -> {
			Editor editor = new Editor();
			editor.setUI(new BBUI());
			editor.reinit();
			try {
				load(editor, file, got);
			} catch (MissingLink m) {
				if(got.size() >= 8)return Promise.<Void>reject(m);
				return download(m.link).then(b -> {
					got.put(m.link, b);
					return build(file, got);
				});
			} catch (Throwable e) {
				return Promise.<Void>reject(e);
			}
			return PopupDialogs.runTaskWithWarning(w -> RenderSystem.withContext(() -> new BlockbenchImport(editor, w).doImport()));
		});
	}

	/** a link the file doesn't carry: build() downloads it and reads the file again */
	private static final class MissingLink extends IOException {
		final Link link;

		MissingLink(Link link) {
			super("Link not inside the file: " + link);
			this.link = link;
		}
	}

	/** the game's download of a link (base64 text). ponytail: only gist links (CPM's upload target) */
	private static Promise<byte[]> download(Link l) {
		String s = String.valueOf(l);
		if(!s.startsWith("git:"))return Promise.reject(new PopupDialogs.UserException(I18n.format("cpmplus.w1io.viewer.link", s)));
		return DomGlobal.fetch("https://gist.githubusercontent.com/" + s.substring(4) + "/raw").then(r -> {
			if(!r.ok)return Promise.<String>reject(new IOException("HTTP " + r.status));
			return r.text();
		}).then(t -> Promise.resolve(Base64.getDecoder().decode(t.trim())), e -> {
			return Promise.<byte[]>reject(new PopupDialogs.UserException(I18n.format("cpmplus.w1io.viewer.download", s)));
		});
	}

	/** fills a fresh editor (reinit done) with the model of a .cpmmodel file; got: links downloaded before */
	static void load(Editor editor, byte[] file, Map<Link, byte[]> got) throws IOException {
		ModelFile.load(new ByteArrayInputStream(file));// header, fields and checksum, like the game
		// the same layout again for what ModelFile keeps private: model data, overflow and its link
		ByteArrayInputStream in = new ByteArrayInputStream(file);
		in.read();
		IOHelper h = new IOHelper(in);
		h.readUTF();
		h.readUTF();
		byte[] data = h.readByteArray();
		byte[] ovf = h.readByteArray();
		Link link = ovf.length > 0 ? new Link(h) : null;

		ModelDefinitionLoader<Object> loader = new ModelDefinitionLoader<Object>(p -> null, p -> null, p -> null) {
			@Override
			public InputStream load(Link l, ResourceEncoding enc, ModelDefinition def) throws IOException {
				if(link != null && link.equals(l))return new ByteArrayInputStream(ovf);
				byte[] b = got.get(l);
				if(b != null)return new ByteArrayInputStream(b);
				throw new MissingLink(l);
			}
		};
		// the user's own player: no size limits (PlayerSpecificConfigKey skips the checks for the client player)
		ModelDefinition def = loader.loadModel(data, MinecraftClientAccess.get().getClientPlayer());
		if(def == null)throw new IOException("Not a CPM model");
		if(def.getError() != null)throw new IOException(def.getError());
		def.resolveAll();

		editor.skinType = skinType(data);
		for(TextureSheetType t : TextureSheetType.VALUES) {
			TextureProvider tp = t.editable ? def.getTexture(t, false) : null;// SKIN: null when the model has no skin
			if(tp == null || tp.getImage() == null)continue;
			ETextures et = editor.textures.get(t);
			if(et == null) {
				et = new ETextures(editor, t);
				editor.textures.put(t, et);
			}
			Image img = new Image(tp.getImage());
			et.setImage(img);
			et.provider.size = new Vec2i(tp.size);
			et.customGridSize = img.getWidth() != tp.size.x || img.getHeight() != tp.size.y;
			et.setChangedLocally(false);
			et.markDirty();
		}

		Map<VanillaModelPart, ModelElement> mains = new HashMap<>();
		editor.elements.forEach(e -> mains.put((VanillaModelPart) e.typeData, e));
		List<VanillaModelPart> types = new ArrayList<>(Arrays.asList(PlayerModelParts.VALUES));
		types.addAll(Arrays.asList(RootModelType.VALUES));
		Set<RootGroups> groups = new HashSet<>();
		int[] n = {0};
		for(VanillaModelPart t : types) {
			// ponytail: CUSTOM_PART only holds the placeholders of the extra roots (moved out by resolveAll)
			if(t == PlayerModelParts.CUSTOM_PART || !def.hasRoot(t))continue;
			def.getModelElementFor(t).forEach(r -> {// main root first, then the duplicated ones
				ModelElement re = mains.remove(t);
				if(re == null) {
					re = new ModelElement(editor, ElementType.ROOT_PART, t);
					if(t instanceof RootModelType) {
						groups.add(RootGroups.getGroup((RootModelType) t));
						re.duplicated = editor.elements.stream().anyMatch(e -> e.typeData == t);
					} else re.duplicated = true;
					if(re.duplicated)re.storeID = Math.abs(new Random().nextLong());
					editor.elements.add(re);
				}
				re.pos = new Vec3f(r.posN);
				re.rotation = r.rotN.asVec3f(true);
				re.hidden = r.isHidden();
				re.disableVanillaAnim = r.disableVanilla;
				addCubes(editor, re, r.children, n);
			});
		}
		groups.forEach(g -> Generators.loadTextures(editor, g, editor.textures::put));// sheets the file didn't carry
	}

	private static void addCubes(Editor editor, ModelElement parent, List<RenderedCube> list, int[] n) {
		if(list == null)return;
		for(RenderedCube rc : list) {
			Cube c = rc.getCube();// effects (colour, scale, UV, item) are already applied to it by resolveAll
			ModelElement e = new ModelElement(editor);
			e.parent = parent;
			parent.children.add(e);
			e.name = I18n.format("cpmplus.w1io.viewer.cube", ++n[0]);
			e.pos = new Vec3f(c.pos);
			e.offset = new Vec3f(c.offset);
			e.rotation = new Rotation(c.rotation, false).asVec3f(true);
			e.size = new Vec3f(c.size);
			e.scale = new Vec3f(c.scale);
			e.meshScale = new Vec3f(c.meshScale);
			e.mcScale = c.mcScale;
			e.u = c.u;
			e.v = c.v;
			e.texture = c.texSize != 0;
			e.mirror = c.texSize < 0;
			e.textureSize = Math.max(1, Math.abs(c.texSize));
			e.rgb = c.rgb & 0xffffff;
			e.hidden = c.hidden;
			e.showInEditor = !c.hidden;// looks like in game: parts an animation reveals start invisible
			e.glow = rc.glow;
			e.recolor = rc.recolor;
			e.singleTex = rc.singleTex;
			e.extrude = rc.extrude;
			if(rc.faceUVs != null)e.faceUV = new PerFaceUV(rc.faceUVs);
			if(rc.itemRenderer != null)e.itemRenderer = new ItemRenderer(rc.itemRenderer);
			addCubes(editor, e, rc.children, n);
		}
	}

	/** the SKIN_TYPE block of the model data (the exporter writes it first); DEFAULT when missing */
	private static SkinType skinType(byte[] data) {
		try {
			IOHelper d = new IOHelper(new ByteArrayInputStream(data, 1, data.length - 1));
			for(int i = 0;i < 64;i++) {
				ModelPartType t = d.readEnum(ModelPartType.VALUES);
				IOHelper b = d.readNextBlock();
				if(t == ModelPartType.END)break;
				if(t == ModelPartType.SKIN_TYPE) {
					int v = b.read();
					SkinType s = v >= 0 && v < SkinType.VALUES.length ? SkinType.VALUES[v] : SkinType.UNKNOWN;
					return s == SkinType.UNKNOWN ? SkinType.DEFAULT : s;
				}
			}
		} catch (IOException e) {
		}
		return SkinType.DEFAULT;
	}

	/** true while the open project is a .cpmmodel opened to look at */
	public static boolean isViewOnly() {
		try {
			return Cond.CPM_ONLY.met() && Project.getData().hasExtra(KEY);
		} catch (Throwable e) {
			return false;
		}
	}

	/** finished_edit: in a view-only project the edit is undone at once and dropped from the history */
	public static void onEdit(Object ev) {
		if(!isViewOnly())return;
		Object m = ev != null ? Js.asPropertyMap(ev).get("message") : null;
		String msg = m != null ? String.valueOf(m) : "";
		boolean look = msg.startsWith("Toggle") && msg.contains("visibility");// showing/hiding parts is looking, not editing
		lock(!look);
		if(!look)PlusDialogs.quick(I18n.get("cpmplus.w1io.viewer.readOnly"), 1500);
	}

	/**
	 * Locks every group and cube of the open project and keeps it 'saved'. undo: first undoes the edit that just
	 * finished and drops it from the history (an undone 'Unlock everything' doesn't bring the group locks back, so
	 * they are set again here).
	 */
	private static native void lock(boolean undo)/*-{
		var u = $wnd.Undo;
		if (undo && u && u.history && u.index > 0) {
			u.undo();
			u.history.length = u.index;
		}
		$wnd.Outliner.elements.forEach(function(e) { e.locked = true; });
		$wnd.Group.all.forEach(function(g) { g.locked = true; });
		if (typeof $wnd.updateSelection === 'function') $wnd.updateSelection();
		$wnd.Project.saved = true;
	}-*/;
}
