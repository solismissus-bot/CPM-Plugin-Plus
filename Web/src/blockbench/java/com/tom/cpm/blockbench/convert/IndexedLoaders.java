package com.tom.cpm.blockbench.convert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.tom.cpl.math.Vec3f;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.elements.ElementType;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.project.IProject;
import com.tom.cpm.shared.editor.project.JsonList;
import com.tom.cpm.shared.editor.project.JsonMap;
import com.tom.cpm.shared.editor.project.ProjectIO;
import com.tom.cpm.shared.editor.project.ProjectPartLoader;
import com.tom.cpm.shared.editor.project.loaders.AnimationsLoaderV1;
import com.tom.cpm.shared.editor.project.loaders.ElementsLoaderV1;
import com.tom.cpm.shared.editor.project.loaders.TemplateLoaderV1;
import com.tom.cpm.shared.editor.template.EditorTemplate;
import com.tom.cpm.shared.editor.template.TemplateArgHandler;
import com.tom.cpm.shared.editor.template.TemplateArgType;
import com.tom.cpm.shared.editor.template.TemplateSettings;
import com.tom.cpm.shared.model.PlayerModelParts;
import com.tom.cpm.shared.model.RootModelType;
import com.tom.cpm.shared.model.render.VanillaModelPart;

import elemental2.dom.DomGlobal;

/**
 * CPM+ (load A3): the project loaders 'elements', 'anims' and 'template' with an index of the elements by storeID,
 * instead of walking the whole model for every animation component / copy transform / template part
 * (AngelArmor: ~70% of the whole import was this lookup).
 * <p>
 * The load bodies are verbatim copies of CPM's loaders (diff them against cpmsrc), only the lookups change, with
 * exactly the same winners: every element with a storeID, in {@link Editor#walkElements} order (animations and
 * template parts get ALL matches in that order; a copy transform keeps the LAST match, or none). Keys are the longs
 * of JsonMap.getLong, as before. save() is inherited unchanged.
 * <p>
 * {@link #install()} swaps the loaders in ProjectIO's static tables (also used by the Embedded Editor) and keeps the
 * originals for {@link #uninstall()}; any error leaves the originals in place. PlusPrefs
 * 'cpmplus.noIndexedLoaders' = true skips it (W1IoInstall).
 */
public final class IndexedLoaders {
	private IndexedLoaders() {
	}

	/** loader map -> (id -> original loader) of every map that was changed */
	private static final Map<Map<String, ProjectPartLoader>, Map<String, ProjectPartLoader>> replaced = new IdentityHashMap<>();
	private static boolean installed;

	public static boolean isInstalled() {
		return installed;
	}

	public static void install() {
		if(installed)return;
		Map<Map<String, ProjectPartLoader>, Map<String, ProjectPartLoader>> done = new IdentityHashMap<>();
		try {
			Map<ProjectIO, Boolean> ios = new IdentityHashMap<>();
			for (ProjectIO io : ProjectIO.loaders.values())ios.put(io, Boolean.TRUE);
			for (ProjectIO io : ios.keySet()) {
				Map<String, ProjectPartLoader> orig = new HashMap<>();
				done.put(io.partLoaders, orig);
				replace(io.partLoaders, "elements", ElementsLoaderV1.class, new PlusElementsLoaderV1(), orig);
				replace(io.partLoaders, "anims", AnimationsLoaderV1.class, new PlusAnimationsLoaderV1(), orig);
				replace(io.partLoaders, "template", TemplateLoaderV1.class, new PlusTemplateLoaderV1(), orig);
			}
			replaced.putAll(done);
			installed = true;
		} catch (Throwable e) {
			// leave CPM's own loaders in place
			restore(done);
			DomGlobal.console.warn("CPM+: indexed project loaders not installed, using CPM's own", e);
		}
	}

	public static void uninstall() {
		restore(replaced);
		replaced.clear();
		installed = false;
	}

	private static void restore(Map<Map<String, ProjectPartLoader>, Map<String, ProjectPartLoader>> m) {
		m.forEach((loaders, orig) -> orig.forEach((id, l) -> {
			try {
				loaders.put(id, l);
			} catch (Throwable e) {
			}
		}));
	}

	/** replaces id only when it is still CPM's own loader class (never a loader someone else installed) */
	private static void replace(Map<String, ProjectPartLoader> loaders, String id, Class<?> cpmClass, ProjectPartLoader plus, Map<String, ProjectPartLoader> orig) {
		ProjectPartLoader l = loaders.get(id);
		if(l == null || l.getClass() != cpmClass)return;
		if(plus.getVersion() != l.getVersion() || plus.getLoadOrder() != l.getLoadOrder() || !plus.getId().equals(id))return;
		orig.put(id, l);
		loaders.put(id, plus);
	}

	/** every element by storeID, in walk order (several elements may share one id: all of them, in that order) */
	static Map<Long, List<ModelElement>> index(Editor editor) {
		Map<Long, List<ModelElement>> idx = new HashMap<>();
		Editor.walkElements(editor.elements, e -> idx.computeIfAbsent(e.storeID, k -> new ArrayList<>(1)).add(e));
		return idx;
	}

	// ------------------------------------------------------------------ elements (copy transform source)

	public static class PlusElementsLoaderV1 extends ElementsLoaderV1 {

		@Override
		public void load(Editor editor, IProject project) throws IOException {
			// --- verbatim copy of ElementsLoaderV1.load up to the final walk
			JsonMap data = project.getJson("config.json");
			JsonList lst = data.getList("elements");
			lst.forEachMap(map -> {
				String key = map.getString("id");
				ModelElement elem = null;
				if (map.getBoolean("customPart", false)) {
					for (RootModelType rmt : RootModelType.VALUES) {
						if(rmt.getName().equalsIgnoreCase(key)) {
							elem = new ModelElement(editor, ElementType.ROOT_PART, rmt);
							break;
						}
					}
					editor.elements.add(elem);
					elem.storeID = map.getLong("storeID", 0);
					elem.duplicated = map.getBoolean("dup", false);
				} else if (map.getBoolean("dup", false)) {
					for (ModelElement e : editor.elements) {
						if(((VanillaModelPart) e.typeData).getName().equalsIgnoreCase(key)) {
							elem = new ModelElement(editor, ElementType.ROOT_PART, e.typeData);
							elem.duplicated = true;
							elem.storeID = map.getLong("storeID", 0);
							if(e.typeData instanceof PlayerModelParts && elem.storeID == ((PlayerModelParts)e.typeData).ordinal()) {
								//Fix duplicated parts broken storeID
								elem.storeID = Math.abs(new Random().nextLong());
							}
							editor.elements.add(elem);
							break;
						}
					}
				} else {
					for (ModelElement e : editor.elements) {
						if(((VanillaModelPart) e.typeData).getName().equalsIgnoreCase(key)) {
							elem = e;
							break;
						}
					}
				}
				if(elem != null) {
					elem.hidden = !map.getBoolean("show");
					elem.showInEditor = map.getBoolean("showInEditor", true);
					elem.locked = map.getBoolean("locked", false);
					if(map.containsKey("children")) {
						loadChildren(map.getList("children"), elem, editor);
					}
					elem.pos = new Vec3f(map.getMap("pos"), new Vec3f(0, 0, 0));
					elem.rotation = new Vec3f(map.getMap("rotation"), new Vec3f(0, 0, 0));
					elem.disableVanillaAnim = map.getBoolean("disableVanillaAnim", false);
					elem.name = map.getString("name", "");
					elem.partNameColor = map.getInt("nameColor", 0);
				}
			});
			// --- CPM+ : CopyTransformEffect.load(editor) walked the whole model per copy transform and kept the
			// LAST element whose storeID matched (none: 'from' unchanged). Same winner from the index.
			Map<Long, List<ModelElement>> idx = index(editor);
			Editor.walkElements(editor.elements, e -> {
				if(e.copyTransform != null) {
					List<ModelElement> l = idx.get(e.copyTransform.storeID);
					if(l != null && !l.isEmpty())e.copyTransform.from = l.get(l.size() - 1);
				}
			});
		}
	}

	// ------------------------------------------------------------------ animations

	public static class PlusAnimationsLoaderV1 extends AnimationsLoaderV1 {
		private Map<Long, List<ModelElement>> idx;
		private Editor idxEditor;

		@Override
		public void load(Editor editor, IProject project) throws IOException {
			idx = index(editor);
			idxEditor = editor;
			try {
				super.load(editor, project);
			} finally {
				idx = null;
				idxEditor = null;
			}
		}

		@Override
		protected void findPartRef(AnimFrame frm, JsonMap map, Consumer<ModelElement> me) {
			Map<Long, List<ModelElement>> ix = idx;
			if(ix == null || frm.getAnim().editor != idxEditor) {
				super.findPartRef(frm, map, me);
				return;
			}
			long sid = map.getLong("storeID");
			List<ModelElement> l = ix.get(sid);
			if(l != null) {
				for (ModelElement elem : l) {
					me.accept(elem);
				}
			}
		}
	}

	// ------------------------------------------------------------------ templates

	public static class PlusTemplateLoaderV1 extends TemplateLoaderV1 {

		@Override
		public void load(Editor editor, IProject project) throws IOException {
			// --- verbatim copy of TemplateLoaderV1.load; the two walks use the index / a HashSet
			project.jsonIfExists("templates.json", data -> {
				JsonList lst = data.getList("templates");
				lst.forEachMap(map -> {
					editor.templates.add(EditorTemplate.load(editor, map));
				});
			});
			project.jsonIfExists("template_settings.json", data -> {
				if(editor.templateSettings == null)editor.templateSettings = new TemplateSettings(editor);
				editor.templateSettings.hasTex = data.getBoolean("texture");
				Map<Long, List<ModelElement>> idx = index(editor);
				JsonList lst = data.getList("args");
				lst.forEachMap(map -> {
					TemplateArgType type = TemplateArgType.lookup(map.getString("type"));
					TemplateArgHandler arg = new TemplateArgHandler(editor, map.getString("name"), map.getString("desc"), type);
					editor.templateSettings.templateArgs.add(arg);
					arg.handler.loadProject(map.getMap("data").asMap());
					if(arg.handler.requiresParts() && arg.effectedElems != null) {
						JsonList partList = map.getList("parts");
						partList.<Number>forEach(e -> {
							List<ModelElement> l = idx.get(e.longValue());
							if(l != null)arg.effectedElems.addAll(l);
						});
					}
				});
				Set<Long> templElems = new HashSet<>(data.getList("displayElems").<Number>stream().map(Number::longValue).collect(Collectors.toList()));
				Editor.walkElements(editor.elements, e -> {
					if(templElems.contains(e.storeID)) {
						e.templateElement = true;
					}
				});
			});
		}
	}
}
