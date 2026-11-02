package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.BBUI;
import com.tom.cpm.blockbench.convert.BlockbenchImport;
import com.tom.cpm.blockbench.convert.ImportProgress;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Blockbench;
import com.tom.cpm.blockbench.proxy.Blockbench.ImportProperties;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.elements.ModelElement;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.shared.model.render.VanillaModelPart;
import com.tom.cpm.web.client.render.RenderSystem;
import com.tom.cpm.web.client.util.JSZip;

import elemental2.core.ArrayBuffer;
import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (D4): imports animations from another .cpmproject into the open project. The file is loaded headless (a CPM
 * editor that never touches the Blockbench project), its parts are matched to the open project's groups by CPM store
 * id, then by full path (root part / child / ... with an index for same-named siblings, never by bare name: models
 * repeat names a lot), and each chosen animation is converted with the import's own code
 * ({@link BlockbenchImport#importAnim}). Parts without a match are skipped and listed. One undo entry.
 */
public final class AnimImport {
	private AnimImport() {
	}

	public static final class Result {
		public final List<Animation> created = new ArrayList<>();
		/** names (paths) of animated parts of the file that have no group in the open project */
		public final Set<String> unmatched = new LinkedHashSet<>();
		public int matchedBySid, matchedByPath;
	}

	// ------------------------------------------------------------------ headless load

	public static Promise<Editor> load(ArrayBuffer ab) {
		return RenderSystem.withContext(() -> {
			Editor editor = new Editor();
			editor.setUI(new BBUI());
			editor.reinit();
			return new JSZip().loadAsync(ab).then(editor.project::load).then(__ -> RenderSystem.withContext(() -> {
				try {
					ImportProgress.loadProject(editor);
				} catch (Exception e) {
					return Promise.reject(e);
				}
				return Promise.resolve(editor);
			}));
		});
	}

	// ------------------------------------------------------------------ part matching

	private static native String groupPath(Group g) /*-{
		var p = [];
		var n = g;
		while (n && n instanceof $wnd.Group) {
			p.unshift(String(n.name));
			n = n.parent;
		}
		return p.join('/');
	}-*/;

	private static native JsArray<Group> childGroups(Object parent) /*-{
		var out = [];
		var list = parent === 'root' || !parent ? $wnd.Outliner.root : parent.children;
		if (!list) return out;
		for (var i = 0; i < list.length; i++) if (list[i] instanceof $wnd.Group) out.push(list[i]);
		return out;
	}-*/;

	/** path segment with an index for the 2nd, 3rd ... sibling of the same name ('wing', 'wing#2') */
	private static String seg(String name, Map<String, Integer> seen) {
		int n = seen.merge(name, 1, Integer::sum);
		return n == 1 ? name : name + "#" + n;
	}

	private static void groupPaths(Object parent, String prefix, Map<String, Group> out) {
		JsArray<Group> l = childGroups(parent);
		Map<String, Integer> seen = new HashMap<>();
		for (int i = 0; i < l.length; i++) {
			Group g = l.getAt(i);
			String p = prefix + seg(String.valueOf(g.name), seen);
			out.put(p, g);
			groupPaths(g, p + "/", out);
		}
	}

	private static String rootName(ModelElement me) {
		String n = me.typeData instanceof VanillaModelPart ? ((VanillaModelPart) me.typeData).getName() : me.name;
		return me.duplicated ? n + "_dup" : n;
	}

	private static void elementPaths(List<ModelElement> list, String prefix, boolean roots, Map<ModelElement, String> out) {
		Map<String, Integer> seen = new HashMap<>();
		for (ModelElement me : list) {
			String p = prefix + seg(roots ? rootName(me) : String.valueOf(me.name), seen);
			out.put(me, p);
			if (me.children != null)elementPaths(me.children, p + "/", false, out);
		}
	}

	/** the other file's elements -> the open project's groups */
	public static Map<ModelElement, Group> mapParts(Editor other, Result res) {
		Map<String, Group> byPath = new LinkedHashMap<>();
		groupPaths("root", "", byPath);
		Map<Long, Group> bySid = new HashMap<>();
		Set<Long> dupSid = new HashSet<>();
		for (Group g : byPath.values()) {
			long sid = 0;
			try {
				sid = g.getData().getStoreID();
			} catch (Throwable e) {
			}
			if (sid == 0)continue;
			if (bySid.put(sid, g) != null)dupSid.add(sid);
		}
		for (Long s : dupSid)bySid.remove(s);
		Map<ModelElement, String> paths = new IdentityHashMap<>();
		elementPaths(other.elements, "", true, paths);
		Map<ModelElement, Group> map = new IdentityHashMap<>();
		Set<Group> used = new HashSet<>();
		for (Map.Entry<ModelElement, String> e : paths.entrySet()) {
			ModelElement me = e.getKey();
			Group g = me.storeID != 0 ? bySid.get(me.storeID) : null;
			if (g != null && used.add(g)) {
				map.put(me, g);
				res.matchedBySid++;
			}
		}
		for (Map.Entry<ModelElement, String> e : paths.entrySet()) {
			if (map.containsKey(e.getKey()))continue;
			Group g = byPath.get(e.getValue());
			if (g != null && used.add(g)) {
				map.put(e.getKey(), g);
				res.matchedByPath++;
			}
		}
		return map;
	}

	// ------------------------------------------------------------------ import (one undo entry)

	public static Result importAnims(Editor other, List<EditorAnim> chosen) {
		Result res = new Result();
		Map<ModelElement, Group> map = mapParts(other, res);
		Map<ModelElement, String> paths = new IdentityHashMap<>();
		elementPaths(other.elements, "", true, paths);
		Set<String> taken = new HashSet<>();
		for (Animation a : AnimxUtil.animations())taken.add(a.name);
		UndoData before = AnimxUtil.track(UndoData.make(new Animation[0]), new ArrayList<>());
		PlusUndo.run(PlusDialogs.tr("cpmplus.animimport.undo"), before, () -> AnimxUtil.track(UndoData.make(res.created.toArray(new Animation[0])), res.created), () -> {
			for (EditorAnim ea : chosen) {
				for (ModelElement me : ea.getComponentsFiltered())if (!map.containsKey(me))res.unmatched.add(paths.getOrDefault(me, String.valueOf(me.name)));
				Animation a = BlockbenchImport.importAnim(other, ea, map);
				if (a == null)continue;
				if (taken.contains(a.name))a.name = AnimxUtil.uniqueAnimName(a.name);
				taken.add(a.name);
				res.created.add(a);
			}
		});
		AnimxUtil.preview();
		return res;
	}

	// ------------------------------------------------------------------ UI

	public static void open() {
		ImportProperties pr = new ImportProperties();
		pr.extensions = new String[] {"cpmproject"};
		pr.type = "Customizable Player Models Project";
		pr.readtype = "binary";
		pr.resource_id = "cpmproject_files";
		Blockbench.import_(pr, files -> {
			String fileName = files[0].name;
			load(files[0].binaryContent).then(ed -> {
				choose(ed, fileName);
				return null;
			}).catch_(e -> {
				DomGlobal.console.error("CPM+: could not read the .cpmproject", e);
				PlusDialogs.messageBox("cpmplus.animimport.title", PlusDialogs.tr("cpmplus.animimport.readError"), new String[] {"cpmplus.w0binfra.btn.ok"}, i -> {
				});
				return null;
			});
		});
	}

	private static void choose(Editor ed, String fileName) {
		if (ed.animations.isEmpty()) {
			PlusDialogs.messageBox("cpmplus.animimport.title", PlusDialogs.trf("cpmplus.animimport.none", fileName), new String[] {"cpmplus.w0binfra.btn.ok"}, i -> {
			});
			return;
		}
		Result probe = new Result();
		Map<ModelElement, Group> map = mapParts(ed, probe);
		Set<ModelElement> animated = new HashSet<>();
		for (EditorAnim ea : ed.animations)animated.addAll(ea.getComponentsFiltered());
		int found = 0;
		for (ModelElement me : animated)if (map.containsKey(me))found++;
		Map<String, String> opts = new LinkedHashMap<>();
		List<String> all = new ArrayList<>();
		for (int i = 0; i < ed.animations.size(); i++) {
			EditorAnim ea = ed.animations.get(i);
			String k = "a" + i;
			opts.put(k, String.valueOf(ea.displayName));
			all.add(k);
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, PlusDialogs.trf("cpmplus.animimport.intro", fileName, found, animated.size())));
		rows.add(FormRow.multiSelect("anims", "cpmplus.animimport.anims", opts, all));
		PlusDialogs.form("cpmplus_animimport", "cpmplus.animimport.title", rows, fr -> {
			List<EditorAnim> chosen = new ArrayList<>();
			String sel = fr.getString("anims");
			for (int i = 0; i < ed.animations.size(); i++)if (isChosen(fr, "anims", "a" + i, sel))chosen.add(ed.animations.get(i));
			if (chosen.isEmpty())return;
			Result r = importAnims(ed, chosen);
			String msg = PlusDialogs.trf("cpmplus.animimport.done", r.created.size());
			if (!r.unmatched.isEmpty()) {
				PlusDialogs.messageBox("cpmplus.animimport.title", msg + "\n\n" + PlusDialogs.trf("cpmplus.animimport.unmatched", String.join(", ", r.unmatched)), new String[] {"cpmplus.w0binfra.btn.ok"}, i -> {
				});
			} else PlusDialogs.quick(msg, 5000);
		});
	}

	private static boolean isChosen(PlusDialogs.FormResult fr, String key, String value, String raw) {
		Object v = fr.raw(key);
		if (v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)if (value.equals(String.valueOf(a.getAt(i))))return true;
			return false;
		}
		if (v != null && "object".equals(Js.typeof(v)))return Js.isTruthy(Js.asPropertyMap(v).get(value));
		return raw != null && raw.contains(value);
	}

	// ------------------------------------------------------------------ test hook: {base64, anims?: [names]}

	public static Object toolJson(Object o) {
		JsPropertyMap<Object> m = Js.asPropertyMap(o);
		ArrayBuffer ab = decode(String.valueOf(m.get("base64")));
		Object names = m.get("anims");
		return load(ab).then(ed -> {
			List<EditorAnim> chosen = new ArrayList<>();
			Set<String> want = null;
			if (names != null && JsArray.isArray(names)) {
				want = new HashSet<>();
				JsArray<Object> a = Js.uncheckedCast(names);
				for (int i = 0; i < a.length; i++)want.add(String.valueOf(a.getAt(i)));
			}
			for (EditorAnim ea : ed.animations)if (want == null || want.contains(String.valueOf(ea.displayName)))chosen.add(ea);
			Result r = importAnims(ed, chosen);
			JsPropertyMap<Object> out = AnimxUtil.map();
			List<Object> cr = new ArrayList<>();
			for (Animation a : r.created)cr.add(a.name);
			out.set("created", AnimxUtil.arr(cr));
			out.set("unmatched", AnimxUtil.arr(new ArrayList<Object>(r.unmatched)));
			out.set("bySid", r.matchedBySid);
			out.set("byPath", r.matchedByPath);
			out.set("inFile", ed.animations.size());
			return Promise.resolve((Object) out);
		});
	}

	private static native ArrayBuffer decode(String b64) /*-{
		var s = $wnd.atob(b64);
		var u = new Uint8Array(s.length);
		for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i);
		return u.buffer;
	}-*/;
}
