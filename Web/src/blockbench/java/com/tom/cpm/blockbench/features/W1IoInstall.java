package com.tom.cpm.blockbench.features;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ImportProgress;
import com.tom.cpm.blockbench.convert.IndexedLoaders;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.util.PlusPrefs;
import com.tom.cpm.blockbench.util.TextureDecodeCache;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.AnimFrame;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.project.ProjectFile;
import com.tom.cpm.shared.editor.project.ProjectIO;
import com.tom.cpm.shared.editor.template.TemplateArgHandler;
import com.tom.cpm.shared.editor.util.StoreIDGen;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.ByteArrays;
import com.tom.cpm.web.client.util.JSZip;

import elemental2.core.ArrayBuffer;
import elemental2.core.Global;
import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * Feature installer of package W1-IO (CPM+ v0.8, lite unit "load"): faster and safer project files.
 * <ul>
 * <li>A3: indexed project loaders (skipped with PlusPrefs 'cpmplus.noIndexedLoaders' = true)</li>
 * <li>A4/E3: ProjectFile timers / counters + the import progress phases (ImportProgress)</li>
 * <li>A7: the texture decode cache is emptied when another project is selected / closed and on unload</li>
 * </ul>
 * Test tools (QA harness only): w1io.state, w1io.applyPrefs, w1io.loadBuffer, w1io.saveBuffer, w1io.qaFix.
 */
public final class W1IoInstall {
	/** PlusPrefs flag: true = use CPM's own project loaders (A3 off) */
	public static final String PREF_NO_INDEXED = "cpmplus.noIndexedLoaders";

	private static Editor lastLoaded;
	private static Runnable qaFixRemover;

	private W1IoInstall() {
	}

	public static void install() {
		ProjectFile.setInstrumentation(ImportProgress.instrumentation());
		applyIndexedPref();
		PluginStart.addEventListener("select_project", e -> TextureDecodeCache.clear());
		PluginStart.addEventListener("close_project", e -> TextureDecodeCache.clear());
		registerTestTools();
	}

	public static void uninstall() {
		IndexedLoaders.uninstall();
		ProjectFile.setInstrumentation(null);
		TextureDecodeCache.clear();
		if(qaFixRemover != null)qaFixRemover.run();
		qaFixRemover = null;
		lastLoaded = null;
		ImportProgress.reset();
	}

	/** A3 on, unless the user (or a test) set PlusPrefs 'cpmplus.noIndexedLoaders' = true */
	public static void applyIndexedPref() {
		if(PlusPrefs.getBool(PREF_NO_INDEXED, false))IndexedLoaders.uninstall();
		else IndexedLoaders.install();
	}

	// ------------------------------------------------------------------ QA test tools (test mode only)

	private static void registerTestTools() {
		if(!PlusTestHooks.active())return;
		PlusTestHooks.register("w1io.state", o -> state());
		PlusTestHooks.register("w1io.applyPrefs", o -> {
			applyIndexedPref();
			return state();
		});
		// what the Embedded Editor does with a .cpmproject (ProjectFile.load + ProjectIO.loadProject), headless
		PlusTestHooks.register("w1io.loadBuffer", o -> {
			byte[] b = Base64.getDecoder().decode(Js.asString(o.get("b64")));
			ArrayBuffer ab = bufferOf(ByteArrays.toUint8Array(b));
			return RenderSystem.withContext(() -> {
				Editor editor = new Editor();
				editor.setUI(new BBUI());
				editor.reinit();
				editor.loadDefaultPlayerModel();
				return new JSZip().loadAsync(ab).then(editor.project::load).then(__ -> RenderSystem.withContext(() -> {
					try {
						ProjectIO.loadProject(editor, editor.project);
					} catch (Exception e) {
						return Promise.reject(e);
					}
					lastLoaded = editor;
					return Promise.resolve(summary(editor));
				}));
			});
		});
		// what 'View in Blockbench' of the Embedded Editor writes (saveToBlob): the editor of the last w1io.loadBuffer
		PlusTestHooks.register("w1io.saveBuffer", o -> {
			Editor editor = lastLoaded;
			if(editor == null)return Promise.reject("w1io.loadBuffer first");
			return RenderSystem.withContext(() -> {
				try {
					StoreIDGen storeIDgen = new StoreIDGen();
					Editor.walkElements(editor.elements, storeIDgen::setID);
					ProjectIO.saveProject(editor, editor.project);
				} catch (Exception e) {
					return Promise.reject(e);
				}
				return editor.project.save().then(blob -> blob.arrayBuffer()).then(ab -> {
					byte[] b = ByteArrays.toBytes(u8Of(ab));
					JsPropertyMap<Object> r = JsPropertyMap.of();
					r.set("b64", Base64.getEncoder().encodeToString(b));
					return Promise.resolve(r);
				});
			});
		});
		// A7: an export warning row whose fix 'changes something' (returns true), so the export re-runs while the
		// warning dialog stays open
		PlusTestHooks.register("w1io.qaFix", o -> {
			if(qaFixRemover != null)qaFixRemover.run();
			qaFixRemover = null;
			if(Js.isTruthy(o.get("on"))) {
				qaFixRemover = ExportHooks.register(ctx -> {
					PlusTestHooks.count("w1ioQaCheck");
					WarnEntry w = WarnFactory.ofHtml(Severity.DIFFERS, "w1ioQaFix", "CPM+ QA W1-IO: re-run the export");
					w.setFix(() -> {
						PlusTestHooks.count("w1ioQaFix");
						return Promise.resolve(true);
					}, WarnEntry.FixKind.DETERMINISTIC);
					ctx.add(w);
				});
			}
			return state();
		});
	}

	private static Object state() {
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("indexedLoaders", IndexedLoaders.isInstalled());
		r.set("textureCache", (double) TextureDecodeCache.size());
		r.set("importBusy", ImportProgress.isBusy());
		return r;
	}

	/** what the loaders produced: elements (+ copy transform source), animation frames, template parts */
	private static Object summary(Editor editor) {
		JsArray<Object> elems = new JsArray<>();
		int[] n = new int[1];
		Editor.walkElements(editor.elements, e -> {
			n[0]++;
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("name", nm(e));
			m.set("storeID", String.valueOf(e.storeID));
			m.set("ct", e.copyTransform != null ? (e.copyTransform.from != null ? nm(e.copyTransform.from) + "#" + e.copyTransform.from.storeID : "<none>") : null);
			m.set("templateElement", e.templateElement);
			elems.push(m);
		});
		JsArray<Object> anims = new JsArray<>();
		editor.animations.forEach(a -> {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("name", a.displayName);
			m.set("file", a.filename);
			JsArray<Object> frames = new JsArray<>();
			for (AnimFrame f : a.getFrames()) {
				List<String> comps = new ArrayList<>();
				f.getComponents().forEach((el, fd) -> comps.add(nm(el) + "#" + el.storeID + "@" + fd.getPosition() + "/" + fd.getRotation() + "/" + fd.getScale() + "/" + fd.getColor() + "/" + fd.isVisible()));
				Collections.sort(comps);
				JsArray<Object> c = new JsArray<>();
				comps.forEach(c::push);
				frames.push(c);
			}
			m.set("frames", frames);
			anims.push(m);
		});
		JsPropertyMap<Object> tpl = JsPropertyMap.of();
		if(editor.templateSettings != null) {
			JsArray<Object> args = new JsArray<>();
			for (TemplateArgHandler arg : editor.templateSettings.templateArgs) {
				JsArray<Object> parts = new JsArray<>();
				if(arg.effectedElems != null)arg.effectedElems.forEach(e -> parts.push(nm(e) + "#" + e.storeID));
				JsPropertyMap<Object> m = JsPropertyMap.of();
				m.set("name", arg.name);
				m.set("parts", parts);
				args.push(m);
			}
			tpl.set("args", args);
		}
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("elements", (double) n[0]);
		r.set("elems", elems);
		r.set("animations", (double) editor.animations.size());
		r.set("anims", anims);
		r.set("template", tpl);
		r.set("textures", (double) editor.textures.size());
		return Global.JSON.parse(Global.JSON.stringify(r));
	}

	private static String nm(ModelElement e) {
		if(e.name != null && !e.name.isEmpty())return e.name;
		return String.valueOf(e.typeData);
	}

	private static native ArrayBuffer bufferOf(Object u8)/*-{
		return u8.buffer;
	}-*/;

	private static native Object u8Of(Object ab)/*-{
		return new Uint8Array(ab);
	}-*/;
}
