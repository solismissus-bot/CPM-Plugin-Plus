package com.tom.cpm.blockbench.tools.anim;

import static com.tom.cpm.blockbench.tools.anim.AnimToolSupport.t;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.tom.cpl.util.Pair;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.tools.anim.AnimToolSupport.Result;
import com.tom.cpm.blockbench.util.AnimEdit;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.animation.VanillaPose;

import elemental2.core.JsArray;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ 'Dividir animação grande': CPM writes the number of parts of an animation as ONE byte (ModelPartAnimation:
 * write(components.length)), so the 256th moving part's motion lands on part 0 in game. The animation is split into
 * pieces of at most 255 parts that play together exactly like the original:
 * <ul>
 * <li>the original keeps its name and its first piece; the others are 'Name#b', 'Name#c'... (CPM keys every gesture /
 * layer button by the text before the first '#', and every animation of a vanilla pose plays with the pose), with all
 * the original's settings (type, additive, loop, length, snapping, priority, layer / button settings, CPM grid);</li>
 * <li>an entry / exit (staged 'p:' / 'c:' / 'g:' name) keeps its exact name in every piece: CPM gathers staged
 * animations by name;</li>
 * <li>a custom pose is refused: CPM keys custom poses by the WHOLE name ('Sentar#b' would be a second pose with its own
 * button), so its pieces could not play together;</li>
 * <li>each bone chain stays in one piece: a group whose moving parts fit (&lt;= 255) goes in whole; a bigger one is
 * opened (the joint alone, then its children), the pieces are filled in outliner order to about the same size.</li>
 * </ul>
 * Keys are moved, never changed (every channel, interpolation, handles, mute flags). One undo entry; the pieces play in
 * the viewport with the original. Time is NOT split: in CPM an entry, the main part and an exit all start when the
 * button is pressed and the main part is cut at its own length counted from that press (AnimationHandler /
 * StagedAnimation), and an exit only plays when the gesture is switched off, so a gesture longer than 32.7 s / 255
 * frames cannot be chained in time; the dialog says so and points to 'Ajustar' / 'Otimizar animação'.
 */
public final class SplitAnim {
	private SplitAnim() {
	}

	public static final int MAX_PARTS = 255;

	/** what the split will do */
	public static final class Plan {
		public Animation anim;
		public int parts;
		public boolean staged;
		/** lang key + argument of the refusal, or null */
		public String refuse, refuseArg;
		public final List<List<Group>> pieces = new ArrayList<>();
		public final List<String> names = new ArrayList<>();
		/** the top groups of each piece's chains (for the summary) */
		public final List<List<Group>> heads = new ArrayList<>();
	}

	// ====================================================================== parts and chains

	/** the moving parts of the animation (one CPM part each): groups with keys in any channel that are exported */
	static Set<Group> animated(Animation a) {
		Set<Group> s = new LinkedHashSet<>();
		for (BoneAnimator ba : AnimEdit.boneAnimators(a)) {
			if (!ba.hasTransform())continue;
			Group g = AnimEdit.groupOf(ba);
			if (g == null || Js.asPropertyMap(g).get("export") != null && !Js.isTruthy(Js.asPropertyMap(g).get("export")))continue;
			s.add(g);
		}
		return s;
	}

	static List<Group> childGroups(Group g) {
		List<Group> out = new ArrayList<>();
		if (g == null || g.children == null)return out;
		for (int i = 0; i < g.children.length; i++) {
			Object c = g.children.getAt(i);
			if (c instanceof Group)out.add((Group) c);
		}
		return out;
	}

	private static int size(Group g, Set<Group> anim, Map<Group, Integer> memo) {
		Integer m = memo.get(g);
		if (m != null)return m;
		int s = anim.contains(g) ? 1 : 0;
		for (Group c : childGroups(g))s += size(c, anim, memo);
		memo.put(g, s);
		return s;
	}

	private static void collect(Group g, Set<Group> anim, List<Group> out) {
		if (anim.contains(g))out.add(g);
		for (Group c : childGroups(g))collect(c, anim, out);
	}

	/** chains kept together: a subtree whose moving parts fit goes whole, a bigger one is opened (joint, then children) */
	private static void units(Group g, Set<Group> anim, int cap, Map<Group, Integer> memo, List<List<Group>> out, List<Group> heads) {
		int s = size(g, anim, memo);
		if (s == 0)return;
		if (s <= cap) {
			List<Group> u = new ArrayList<>();
			collect(g, anim, u);
			out.add(u);
			heads.add(g);
			return;
		}
		if (anim.contains(g)) {
			out.add(Collections.singletonList(g));
			heads.add(g);
		}
		for (Group c : childGroups(g))units(c, anim, cap, memo, out, heads);
	}

	/** the top-level groups in outliner order */
	private static native JsArray<Group> rootGroups() /*-{
		var out = [], O = $wnd.Outliner, G = $wnd.Group;
		if (!O || !O.root) return out;
		for (var i = 0; i < O.root.length; i++) if (G && O.root[i] instanceof G) out.push(O.root[i]);
		return out;
	}-*/;

	static String letters(int i) {
		StringBuilder sb = new StringBuilder();
		int n = i;
		do {
			sb.insert(0, (char) ('a' + n % 26));
			n = n / 26 - 1;
		} while (n >= 0);
		return sb.toString();
	}

	/** 'Name#b', 'Name#c'... ('Name#parte_b' style when the name already has a '#'), never an existing name */
	static String pieceName(String base, int index, Set<String> taken) {
		String sep = base.indexOf('#') < 0 ? "#" : "_";
		for (int k = index; k < index + 500; k++) {
			String n = base + sep + letters(k);
			if (!taken.contains(n))return n;
		}
		return base + sep + letters(index) + "_";
	}

	public static Plan plan(Animation a, int cap) {
		Plan p = new Plan();
		p.anim = a;
		if (a == null) {
			p.refuse = "cpmplus.w3animtools.err.noAnimation";
			return p;
		}
		Set<Group> anim = animated(a);
		p.parts = anim.size();
		p.staged = AnimEdit.isStaged(a);
		Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
		if (!p.staged && tp != null && tp.getKey() == AnimationType.CUSTOM_POSE) {
			p.refuse = "cpmplus.w34proa.split.err.customPose";
			p.refuseArg = a.name;
			return p;
		}
		if (p.parts <= cap) {
			p.refuse = "cpmplus.w34proa.split.err.fits";
			p.refuseArg = a.name;
			return p;
		}
		Map<Group, Integer> memo = new HashMap<>();
		List<List<Group>> us = new ArrayList<>();
		List<Group> hs = new ArrayList<>();
		JsArray<Group> roots = rootGroups();
		for (int i = 0; i < roots.length; i++)units(roots.getAt(i), anim, cap, memo, us, hs);
		int total = 0;
		for (List<Group> u : us)total += u.size();
		int want = (total + cap - 1) / cap;
		for (int target = (total + want - 1) / want; ; target++) {
			p.pieces.clear();
			p.heads.clear();
			List<Group> cur = new ArrayList<>(), curH = new ArrayList<>();
			for (int i = 0; i < us.size(); i++) {
				List<Group> u = us.get(i);
				if (!cur.isEmpty() && cur.size() + u.size() > target) {
					p.pieces.add(cur);
					p.heads.add(curH);
					cur = new ArrayList<>();
					curH = new ArrayList<>();
				}
				cur.addAll(u);
				curH.add(hs.get(i));
			}
			if (!cur.isEmpty()) {
				p.pieces.add(cur);
				p.heads.add(curH);
			}
			if (p.pieces.size() <= want || target >= cap)break;
		}
		Set<String> taken = new LinkedHashSet<>();
		for (Animation x : AnimEdit.all())if (x.name != null)taken.add(x.name);
		for (int i = 0; i < p.pieces.size(); i++) {
			String n = i == 0 || p.staged ? a.name : pieceName(a.name, i, taken);
			taken.add(n);
			p.names.add(n);
		}
		return p;
	}

	// ====================================================================== apply

	public static Result apply(Plan p) {
		Result r = new Result();
		if (p.refuse != null)return r.error("refused", t(p.refuse, p.refuseArg != null ? p.refuseArg : ""));
		Animation orig = p.anim;
		List<Animation> made = new ArrayList<>();
		List<Animation> before = Collections.singletonList(orig);
		PlusUndo.run(t("cpmplus.w34proa.split.undo"), AnimxUtil.track(UndoData.make(new Animation[] {orig}), before), () -> {
			List<Animation> after = new ArrayList<>();
			after.add(orig);
			after.addAll(made);
			return AnimxUtil.track(UndoData.make(after.toArray(new Animation[0])), after);
		}, () -> {
			unselectKeys();
			for (int i = 1; i < p.pieces.size(); i++) {
				Animation a = new Animation();
				a.extend(LayerSupport.copyData(orig, p.names.get(i)));
				AnimEdit.copySnapping(orig, a);
				a = a.add(false);
				a.name = p.names.get(i);// after add: Blockbench renames an exact duplicate on add (staged pieces share the name)
				for (Group g : p.pieces.get(i))moveAnimator(orig, a, g);
				made.add(a);
			}
		});
		// the pieces play in the viewport with the original, so the model still moves as before
		LayerSupport.setPlaying(orig, true);
		for (Animation a : made)LayerSupport.setPlaying(a, true);
		AnimToolSupport.refreshPreview();
		r.changed = true;
		List<Object> names = new ArrayList<>(), sizes = new ArrayList<>();
		for (int i = 0; i < p.pieces.size(); i++) {
			names.add(p.names.get(i));
			sizes.add((double) p.pieces.get(i).size());
		}
		r.put("names", names).put("sizes", sizes).put("pieces", (double) p.pieces.size()).put("parts", (double) p.parts);
		return r;
	}

	/** every key of g's animator moved from src to dst (copies with all their data; the src animator is detached) */
	private static native void moveAnimator(Object src, Object dst, Object group) /*-{
		var ba = src.animators[group.uuid];
		if (!ba) return;
		var nb = dst.getBoneAnimator(group);
		var kfs = ba.keyframes ? ba.keyframes.slice() : [];
		for (var i = 0; i < kfs.length; i++) {
			var c = kfs[i].getUndoCopy();
			delete c.uuid;
			delete c.animator;
			nb.addKeyframe(c, typeof $wnd.guid === 'function' ? $wnd.guid() : undefined);
		}
		if (ba.muted && nb.muted) for (var ch in ba.muted) nb.muted[ch] = ba.muted[ch];
		if (ba.rotation_global !== undefined) nb.rotation_global = ba.rotation_global;
		if (ba.quaternion_interpolation !== undefined) nb.quaternion_interpolation = ba.quaternion_interpolation;
		delete src.animators[group.uuid];
		var tl = $wnd.Timeline && $wnd.Timeline.animators;
		if (tl) { var k = tl.indexOf(ba); if (k >= 0) tl.splice(k, 1); }
	}-*/;

	private static native void unselectKeys() /*-{
		try {
			var T = $wnd.Timeline;
			if (T && T.selected) { T.selected.forEach(function(k) { k.selected = false; }); T.selected.splice(0, T.selected.length); }
		} catch (e) {}
	}-*/;

	// ====================================================================== UI

	/**
	 * The export row 'Dividir' of 'more than 255 parts' (FormatLimitsCheck row 'limitParts', arguments: the animation's
	 * name in the export, the part count): splits without a dialog (one undo entry). Entries / exits share a name: the
	 * one over the limit is taken.
	 */
	public static Promise<Boolean> fromExportRow(Object[] args) {
		String name = args != null && args.length > 0 && args[0] != null ? String.valueOf(args[0]) : null;
		Animation a = null;
		for (Animation x : AnimEdit.all()) {
			if (name == null || !name.equals(x.name))continue;
			if (a == null || animated(x).size() > animated(a).size())a = x;
		}
		if (a == null) {
			PlusDialogs.block(t("cpmplus.w34proa.split.err.notFound", name != null ? name : "?"));
			return Promise.resolve(false);
		}
		Plan p = plan(a, MAX_PARTS);
		Result r = apply(p);
		if (!r.ok) {
			PlusDialogs.block(r.errorText());
			return Promise.resolve(false);
		}
		PlusDialogs.done(t("cpmplus.w34proa.split.done", a.name, p.pieces.size()));
		return Promise.resolve(true);
	}

	/** CPM+ &gt; Animação &gt; 'Dividir animação grande...' and the animation's right-click menu */
	public static void openDialog() {
		Animation a = Animation.selected;
		if (a == null) {
			PlusDialogs.block(t("cpmplus.w3animtools.err.noAnimation"));
			return;
		}
		Plan p = plan(a, MAX_PARTS);
		AnimEdit.Frames f = AnimEdit.predictFrames(a);
		boolean tooLong = f.frames > AnimEdit.MAX_FRAMES || f.durationMs > AnimEdit.MAX_DURATION_MS && !dynamic(a);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("intro", null, t("cpmplus.w34proa.split.intro", a.name, p.parts)));
		if (p.refuse != null) {
			rows.add(FormRow.info("why", null, t(p.refuse, p.refuseArg != null ? p.refuseArg : "")));
		} else {
			StringBuilder html = new StringBuilder("<ul style='margin:4px 0 4px 18px;padding:0'>");
			for (int i = 0; i < p.pieces.size(); i++) {
				StringBuilder who = new StringBuilder();
				List<Group> hs = p.heads.get(i);
				for (int k = 0; k < hs.size() && k < 4; k++)who.append(k > 0 ? ", " : "").append(hs.get(k).name);
				if (hs.size() > 4)who.append(", …");
				html.append("<li><b>").append(PlusDialogs.esc(p.names.get(i))).append("</b> — ")
				.append(PlusDialogs.esc(t("cpmplus.w34proa.split.piece", p.pieces.get(i).size(), who.toString()))).append("</li>");
			}
			html.append("</ul>");
			rows.add(FormRow.info("plan", null, t(p.staged ? "cpmplus.w34proa.split.planStaged" : "cpmplus.w34proa.split.plan", p.pieces.size())));
			rows.add(FormRow.htmlInfo("list", null, html.toString()));
		}
		if (tooLong)rows.add(FormRow.info("time", null, t("cpmplus.w34proa.split.time", a.name, f.frames, PoseColumn.fmt(f.durationMs / 1000.0))));
		if (p.refuse != null && !tooLong) {
			PlusDialogs.block(t(p.refuse, p.refuseArg != null ? p.refuseArg : ""));
			return;
		}
		FormOpts fo = new FormOpts();
		fo.width = 600;
		if (p.refuse == null)fo.confirmKey = "cpmplus.w34proa.split.ok";
		PlusDialogs.form("cpmplus_w34proa_split", "cpmplus.w34proa.split.title", rows, fr -> {
			if (p.refuse != null)return;
			Result r = apply(p);
			if (!r.ok) {
				PlusDialogs.block(r.errorText());
				return;
			}
			PlusDialogs.done(t("cpmplus.w34proa.split.done", a.name, p.pieces.size()));
		}, fo);
	}

	static boolean dynamic(Animation a) {
		Pair<AnimationType, VanillaPose> tp = AnimxUtil.typeOf(a);
		return tp != null && (tp.getKey() == AnimationType.VALUE_LAYER || tp.getKey() == AnimationType.POSE && tp.getValue() != null && tp.getValue().hasStateGetter());
	}

	// ====================================================================== QA hook

	/** split({anim, cap, apply}) -&gt; {ok, parts, refused, names[], sizes[], heads[][] (plan) | applied result} */
	public static Object hook(JsPropertyMap<Object> opts) {
		Animation a = AnimToolSupport.anim(opts);
		int cap = (int) AnimToolSupport.num(opts, "cap", MAX_PARTS);
		Plan p = plan(a, Math.max(1, Math.min(MAX_PARTS, cap)));
		if (AnimToolSupport.bool(opts, "apply", false))return apply(p).toJs();
		JsPropertyMap<Object> r = JsPropertyMap.of();
		r.set("ok", p.refuse == null);
		r.set("parts", p.parts);
		r.set("staged", p.staged);
		r.set("refused", p.refuse);
		JsArray<Object> names = new JsArray<>(), sizes = new JsArray<>(), heads = new JsArray<>();
		for (int i = 0; i < p.pieces.size(); i++) {
			names.push(p.names.get(i));
			sizes.push((double) p.pieces.get(i).size());
			JsArray<Object> h = new JsArray<>();
			for (Group g : p.heads.get(i))h.push(g.name);
			heads.push(h);
		}
		r.set("names", names);
		r.set("sizes", sizes);
		r.set("heads", heads);
		return r;
	}
}
