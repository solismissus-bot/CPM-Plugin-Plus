package com.tom.cpm.blockbench.convert;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import com.tom.cpl.gui.Frame;
import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.ee.EmbeddedEditorHandler;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Blockbench.BBFile;
import com.tom.cpm.blockbench.proxy.Blockbench.ExportProperties;
import com.tom.cpm.blockbench.proxy.Blockbench.ImportProperties;
import com.tom.cpm.blockbench.proxy.Blockbench.ReadProperties;
import com.tom.cpm.blockbench.proxy.Blockbench.WriteProperties;
import com.tom.cpm.blockbench.proxy.Global;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.TextureDecodeCache;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.gui.EditorGui;
import com.tom.cpm.shared.editor.project.ProjectIO;
import com.tom.cpm.shared.editor.util.StoreIDGen;
import com.tom.cpm.web.client.WebMC;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.I18n;
import com.tom.cpm.web.client.util.JSZip;

import elemental2.core.ArrayBuffer;
import elemental2.core.JsObject;
import elemental2.dom.Blob;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

public class ProjectConvert {

	public static Promise<Blob> compile(JsObject options) {
		return compileWithEditor(options).then(c -> Promise.resolve(c.blob));
	}

	/** CPM+ (W0C): the compiled .cpmproject and the CPM editor it was written from (for ExportHooks.afterWrite) */
	private static final class Compiled {
		Blob blob;
		Editor editor;
	}

	private static Promise<Compiled> compileWithEditor(JsObject options) {
		DomGlobal.console.log("Export");
		Editor[] ed = new Editor[1];
		return convertToCPM(ed).then(model -> {
			JsPropertyMap<?> map = JsPropertyMap.of("model", model, "options", options);
			CPMCodec.codec.dispatchEvent("compile", Js.cast(map));
			Compiled c = new Compiled();
			c.blob = Js.cast(map.get("model"));
			c.editor = ed[0];
			return Promise.resolve(c);
		});
	}

	public static Promise<Blob> convertToCPM() {
		return convertToCPM(new Editor[1]);
	}

	private static Promise<Blob> convertToCPM(Editor[] out) {
		Editor editor = newEditor();
		out[0] = editor;
		return exportWithWarnings(editor).then(__ -> {
			// CPM+ (W0C): ExportHooks.beforeFinish, after the warning dialog, before ids and writing
			try {
				ExportHooks.runBeforeFinish(editor);
			} catch (Throwable e) {
				return Promise.reject(e);
			}
			return saveToBlob(editor, true);
		});
	}

	private static Editor newEditor() {
		Editor editor = new Editor();
		editor.setUI(new BBUI());
		editor.reinit();
		return editor;
	}

	// ------------------------------------------------------------------ CPM+ (W0C, INFRA5): headless conversion

	/** CPM+ (W0C): result of {@link #convertHeadless()} */
	public static final class HeadlessExport {
		/** the converted CPM editor (ExportHooks.beforeFinish applied; ids not assigned yet) */
		public final Editor editor;
		/** the converter, for its getters (uuid lookup, exported animations ...) */
		public final BlockbenchExport exp;
		/** every warning row the export dialog would show (no fix was run) */
		public final List<WarnEntry> warnings;

		HeadlessExport(Editor editor, BlockbenchExport exp, List<WarnEntry> warnings) {
			this.editor = editor;
			this.exp = exp;
			this.warnings = Collections.unmodifiableList(warnings);
		}
	}

	/**
	 * CPM+ (W0C, INFRA5): converts the open Blockbench project into a CPM editor exactly like the export, without
	 * writing anything. showWarnings=false: no dialog (rows are dropped); true: the same warning dialog as the export
	 * (its fixes may change the project, like on export) and the promise rejects with CancelException when the user
	 * cancels. The project is not changed by the conversion itself (Undo history, Project.saved and the .bbmodel stay
	 * the same).
	 */
	public static Promise<Editor> buildEditor(boolean showWarnings) {
		Editor editor = newEditor();
		Promise<Void> p = showWarnings ? exportWithWarnings(editor) : releaseTextures(prepExport(editor, w -> Promise.resolve((Void) null)));
		return PlusTestHooks.track(p.then(__ -> {
			ExportHooks.runBeforeFinish(editor);
			return Promise.resolve(editor);
		}));
	}

	/**
	 * CPM+ (W0C, INFRA5): like {@link #buildEditor(boolean) buildEditor(false)}, and also returns the converter and the
	 * warning rows the export would show. Never shows a dialog, never runs a fix.
	 */
	public static Promise<HeadlessExport> convertHeadless() {
		Editor editor = newEditor();
		List<WarnEntry> rows = new ArrayList<>();
		BlockbenchExport[] exp = new BlockbenchExport[1];
		Promise<Void> p = releaseTextures(prepExport(editor, w -> {
			rows.addAll(w);
			return Promise.resolve((Void) null);
		}, exp));
		return PlusTestHooks.track(p.then(__ -> {
			ExportHooks.runBeforeFinish(editor);
			return Promise.resolve(new HeadlessExport(editor, exp[0], rows));
		}));
	}

	private static Promise<Blob> saveToBlob(Editor editor, boolean passthrough) {
		try {
			StoreIDGen storeIDgen = new StoreIDGen();
			Editor.walkElements(editor.elements, storeIDgen::setID);
			// CPM+ (W0C): safety net, never expected: ids that are the same number in game get new unique ids
			IdUtil.repairCollisions(editor);
			ProjectIO.saveProject(editor, editor.project);
			if(passthrough)writeRawFiles(editor);// CPM+
			return editor.project.save();
		} catch (Throwable e) {
			return Promise.reject(e);
		}
	}

	/**
	 * CPM+ : .cpmproject entries the plugin can't edit (model icon, item/block/entity/biome tags,
	 * templates) are kept from the imported project and written back unchanged, unless CPM itself
	 * wrote that entry.
	 */
	private static void writeRawFiles(Editor editor) {
		ProjectData pd = Project.getData();
		pd.rawFiles.forEach((path, b64) -> {
			if(path.equals("desc_icon.png") && editor.description == null)return;
			try {
				if(editor.project.getEntry(path) != null)return;
			} catch (Throwable e) {
			}
			try {
				editor.project.setEntry(path, Base64.getDecoder().decode(b64));
			} catch (Throwable e) {
				DomGlobal.console.warn("CPM+: failed to restore " + path);
			}
		});
	}

	public static Promise<Void> exportWithWarnings(Editor editor) {
		return PopupDialogs.runTaskWithWarning(w -> prepExport(editor, w));
	}

	/** CPM+ (load A7): the decoded textures of a conversion without the warning dialog are dropped when it ends */
	private static <T> Promise<T> releaseTextures(Promise<T> p) {
		return p.finally_(TextureDecodeCache::clear);
	}

	public static Promise<Void> prepExport(Editor editor, Function<List<WarnEntry>, Promise<Void>> openWarn) {
		return prepExport(editor, openWarn, null);
	}

	/** CPM+ (W0C): same, and hands out the converter (out[0], the last one when a fix re-ran the conversion) */
	private static Promise<Void> prepExport(Editor editor, Function<List<WarnEntry>, Promise<Void>> openWarn, BlockbenchExport[] out) {
		return new Promise<>((res, rej) -> {
			RenderSystem.withContext(() -> {
				editor.loadDefaultPlayerModel();
				try {
					BlockbenchExport be = new BlockbenchExport(editor, openWarn);
					if(out != null)out[0] = be;
					res.onInvoke(be.doExport().finally_(() -> {
						editor.dirty = false;
						editor.autoSaveDirty = false;
					}));
				} catch (Throwable e) {
					rej.onInvoke(e);
				}
			});
		});
	}

	public static Promise<Void> parse(ArrayBuffer ab) {
		boolean started = false;
		try {
			DomGlobal.console.log("Parse");
			ImportProgress.begin();// CPM+ (load E3): status text + progress per phase, reset when it ends
			started = true;
			CPMCodec.codec.dispatchEvent("parse", Js.cast(JsPropertyMap.of("arraybuffer", ab)));
			return ImportProgress.finish(RenderSystem.withContext(() -> {
				Editor editor = new Editor();
				editor.setUI(new BBUI());
				editor.reinit();
				return new JSZip().loadAsync(ab).then(editor.project::load).then(__ -> {
					ImportProgress.phase(ImportProgress.LOAD);
					return ImportProgress.yieldFrame();// CPM+ (load E3): the status text paints before the long load
				}).then(__ -> {
					return RenderSystem.withContext(() -> {
						try {
							ImportProgress.loadProject(editor);// CPM+ (load): ProjectIO.loadProject + timer, config.json parsed once
						} catch (Exception e) {
							return Promise.reject(e);
						}
						return Promise.resolve((Void) null);
					});
				}).then(v -> {
					return PopupDialogs.runTaskWithWarning(w -> RenderSystem.withContext(() -> {
						return new BlockbenchImport(editor, w).doImport();
					}));
				});
			}));
		} catch (Throwable e) {
			if(started)ImportProgress.end();
			return Promise.reject(e);
		}
	}

	public static float ceil(float val) {
		return (float) Math.ceil(val - 0.01);
	}

	public static float floor(float val) {
		return (float) Math.floor(val + 0.01);
	}

	private static String projectName(String fileName) {
		if(fileName == null)return "model";
		String n = fileName;
		int i = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
		if(i != -1)n = n.substring(i + 1);
		return n.replace(".cpmproject", "").replaceAll("\\s+", "_").toLowerCase();
	}

	public static void open() {
		ImportProperties pr = new ImportProperties();
		pr.extensions = new String[] {"cpmproject"};
		pr.type = "Customizable Player Models Project";
		pr.readtype = "binary";
		pr.resource_id = "cpmproject_files";
		Blockbench.import_(pr, files -> {
			String csname = projectName(files[0].name);
			parse(files[0].binaryContent).then(__ -> {
				Project.name = csname;
				Project.geometry_name = csname;
				return null;
			}).catch_(e -> {
				PopupDialogs.displayError(I18n.get("bb-label.error.import"), e);
				return null;
			});
		});
	}

	/**
	 * CPM+ : Codec.load, used by File > Open, drag & drop and Recent models.
	 * Blockbench reads codec files as text, which corrupts the zip, so on desktop the file is
	 * re-read as binary from its path. A real ArrayBuffer is used as is.
	 */
	public static void loadFromCodec(Object model, BBFile file) {
		ArrayBuffer ab = null;
		if(model instanceof ArrayBuffer)ab = (ArrayBuffer) model;
		if(ab == null && file != null)ab = toArrayBuffer(Js.asPropertyMap(file).get("content"));
		String csname = projectName(file != null ? (file.name != null ? file.name : file.path) : null);
		if(ab != null) {
			parseNamed(ab, csname);
			return;
		}
		// CPM+ : desktop: re-read the file as binary through Blockbench's own reader. (Blockbench 5
		// removed window.require, so Node's fs is not reachable from plugins.)
		if(file != null && file.path != null && !file.path.isEmpty() && Global.isApp()) {
			try {
				ReadProperties rp = new ReadProperties();
				rp.readtype = "binary";
				rp.errorbox = false;
				boolean[] done = new boolean[1];
				Blockbench.read(new String[] {file.path}, rp, files -> {
					if(done[0])return;
					done[0] = true;
					ArrayBuffer b = files != null && files.length > 0 ? toArrayBuffer(Js.asPropertyMap(files[0]).get("content")) : null;
					if(b != null)parseNamed(b, csname);
					else open();
				});
				return;
			} catch (Throwable e) {
				DomGlobal.console.warn("CPM+: binary read failed, " + e);
			}
		}
		// web: the file was read as text, the zip is lost. Use the binary importer instead.
		open();
	}

	/** CPM+ : ArrayBuffer, or a typed array / Node Buffer view converted to one; null otherwise */
	private static native ArrayBuffer toArrayBuffer(Object c)/*-{
		if (!c || typeof c !== 'object') return null;
		if (c instanceof ArrayBuffer) return c;
		if (Object.prototype.toString.call(c) === '[object ArrayBuffer]') return c;
		if (ArrayBuffer.isView(c)) return c.buffer.slice(c.byteOffset, c.byteOffset + c.byteLength);
		return null;
	}-*/;

	private static void parseNamed(ArrayBuffer ab, String csname) {
		parse(ab).then(__ -> {
			Project.name = csname;
			Project.geometry_name = csname;
			return null;
		}).catch_(e -> {
			PopupDialogs.displayError(I18n.get("bb-label.error.import"), e);
			return null;
		});
	}

	public static void export() {
		try {
			compileWithEditor(null).then(c -> {
				ExportProperties pr = new ExportProperties();
				pr.resource_id = "model";
				pr.type = CPMCodec.codec.name;
				pr.extensions = new String[] {CPMCodec.codec.extension};
				pr.name = CPMCodec.codec.fileName();
				pr.startpath = CPMCodec.codec.startPath();
				pr.binaryContent = c.blob;
				// CPM+ (W0C): same writer as the codec's, plus ExportHooks.afterWrite once the file is on disk
				pr.custom_writer = Global.isApp() ? (content, path) -> writeCpmproject(content, path, c) : null;
				Blockbench.export(pr, path -> {
					CPMCodec.codec.afterDownload(path);
					afterWrite(path, c);
				});
				return null;
			}).catch_(err -> {
				reportExportError(err);
				return null;
			});
		} catch (Exception e) {
			reportExportError(e);
		}
	}

	/**
	 * CPM+ (W0C): shows an export error. A known error (ExportHooks.translateException) becomes a plain message;
	 * anything else is the CPM error dialog with the details. A cancelled warning dialog shows nothing.
	 */
	public static void reportExportError(Object err) {
		if(err instanceof PopupDialogs.CancelException)return;
		String t = ExportHooks.translate(err);
		if(t != null) {
			if(err != null)DomGlobal.console.log("CPM+: export error (shown to the user as: " + t + ")", err);
			PopupDialogs.displayUserError(I18n.get("bb-label.error.export"), t);
			return;
		}
		PopupDialogs.displayError(I18n.get("bb-label.error.export"), err);
	}

	/** CPM+ (W0C): the codec's desktop writer (Codec.write) with ExportHooks.afterWrite after a successful write */
	private static void writeCpmproject(JsObject content, String path, Compiled c) {
		if (content == Js.uncheckedCast(""))return;
		WriteProperties pr = new WriteProperties();
		pr.content = content;
		pr.savetype = "zip";
		rememberExportPath(path);
		Blockbench.writeFile(path, pr, p -> {
			CPMCodec.codec.afterSave(p);
			afterWrite(p != null && !p.isEmpty() ? p : path, c);
		});
	}

	private static void afterWrite(String path, Compiled c) {
		if(!ExportHooks.hasAfterWrite() || c == null || c.blob == null)return;
		try {
			PlusTestHooks.track(c.blob.arrayBuffer().then(ab -> {
				ExportHooks.fireAfterWrite(path, ab, c.editor);
				return null;
			}));
		} catch (Throwable e) {
			DomGlobal.console.error("CPM+: after-export hooks failed", e);
		}
	}

	/** CPM+ : remember the written path for quick re-export */
	public static void rememberExportPath(String path) {
		if(path == null || path.isEmpty())return;
		try {
			ProjectData pd = Project.getData();
			pd.lastExportPath = path;// CPM+ : session-local, not flushed into the .bbmodel
		} catch (Throwable e) {
		}
	}

	/** CPM+ : export again to the last written .cpmproject without a file dialog (desktop only) */
	public static void reexport() {
		String path = null;
		try {
			path = Project.getData().lastExportPath;
		} catch (Throwable e) {
		}
		if(!Global.isApp() || path == null || path.isEmpty()) {
			export();
			return;
		}
		final String fpath = path;
		try {
			compileWithEditor(null).then(c -> {
				writeCpmproject(Js.cast(c.blob), fpath, c);
				return null;
			}).catch_(err -> {
				reportExportError(err);
				return null;
			});
		} catch (Exception e) {
			reportExportError(e);
		}
	}

	public static void viewInBB() {
		Frame frm = WebMC.getInstance().getGui().getFrame();
		if(frm instanceof EditorGui) {
			EditorGui eg = (EditorGui) frm;
			Editor editor = eg.getEditor();
			saveToBlob(editor, false).then(b -> b.arrayBuffer()).then(a -> {
				EmbeddedEditorHandler.openInHost(a);
				return null;
			});
		}
	}
}
