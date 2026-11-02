package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.convert.ExportHooks.ExportContext;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Animation.GeneralAnimator;
import com.tom.cpm.blockbench.proxy.Animator;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Modes;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.web.client.util.I18n;
import com.tom.ugwt.client.JsArrayE;

import elemental2.dom.DomGlobal;
import elemental2.dom.HTMLElement;
import elemental2.dom.HTMLOptionElement;
import elemental2.dom.HTMLSelectElement;
import elemental2.promise.Promise;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W4-EXPORTFIX, G7): keyframes of deleted groups ('anima\u00e7\u00f5es soltas').
 * <ul>
 * <li>Orphan = a BoneAnimator with keys whose uuid is no longer a node of the outliner (OutlinerNode.uuids, never the
 * animator's cached group). Groups with export=false are NOT orphans.</li>
 * <li>Names: the import passes the group name to the BoneAnimator (BB 5 '_name'); every finished edit and every
 * export stamps '_name' = the group's name on animators whose group exists, and keeps uuid -> name for the session.
 * This also enables Blockbench's own lazy re-match by name (Animation.getBoneAnimator).</li>
 * <li>Export: one DIFFERS row per old part ('A pe\u00e7a X n\u00e3o existe mais: N keyframes em M anima\u00e7\u00f5es n\u00e3o v\u00e3o para o
 * jogo.') with the 'Reconectar\u2026' fix. The export is never blocked.</li>
 * <li>Apply = ONE undo entry: the destination animators are created BEFORE the entry opens (Blockbench's
 * Animation.extend never removes animators that are not in the saved copy), keyframes are MOVED (same objects: time,
 * interpolation, bezier, data points), the source channels are emptied inside the entry (so redo empties them again)
 * and the empty source animators are removed after the entry is closed.</li>
 * </ul>
 */
public final class OrphanAnims {
	/** every channel CPM exports (transform + CPM visibility / colour) */
	public static final String[] CHANNELS = {"position", "rotation", "scale", CPMCodec.VISIBILITY, CPMCodec.COLOR};
	private static final Map<String, String> SESSION_NAMES = new HashMap<>();
	private static String sessionProject;

	private OrphanAnims() {
	}

	// ------------------------------------------------------------------ detection

	public static final class Orphan {
		public final String uuid;
		/** display name ('pe\u00e7a sem nome (id abc123)' when unknown) */
		public String name;
		/** the real name was known (_name or session) */
		public boolean named;
		public final List<Animation> anims = new ArrayList<>();
		public final Map<String, Integer> perChannel = new LinkedHashMap<>();
		public int keys;

		Orphan(String uuid) {
			this.uuid = uuid;
		}

		public int keysIn(Animation a) {
			BoneAnimator ba = animator(a, uuid);
			return ba == null ? 0 : countKeys(ba);
		}
	}

	static BoneAnimator animator(Animation a, String uuid) {
		if (a == null || a.animators == null)return null;
		GeneralAnimator ga = a.animators.get(uuid);
		return ga instanceof BoneAnimator ? (BoneAnimator) ga : null;
	}

	static int countKeys(BoneAnimator ba) {
		int n = 0;
		for (String ch : CHANNELS) {
			JsArrayE<Keyframe> arr = ba.channel(ch);
			if (arr != null)n += arr.length;
		}
		return n;
	}

	/** the outliner node with this uuid (group, cube ...) or null */
	public static native Object liveNode(String uuid) /*-{
		try {
			var O = $wnd.OutlinerNode;
			var n = O && O.uuids ? O.uuids[uuid] : null;
			return n ? n : null;
		} catch (e) {
			return null;
		}
	}-*/;

	private static String projectKey() {
		try {
			return com.tom.cpm.blockbench.proxy.Project.getUuid();
		} catch (Throwable e) {
			return null;
		}
	}

	private static void checkSession() {
		String p = projectKey();
		if (p == null ? sessionProject != null : !p.equals(sessionProject)) {
			SESSION_NAMES.clear();
			sessionProject = p;
		}
	}

	/**
	 * Blockbench 5 drops the keys of a deleted group (Group.remove -> Animation.removeAnimator), so nothing would ever be
	 * 'loose'. In CPM projects the animators WITH keys of a deleted group stay in their animation (named, out of the
	 * timeline): 'Animações soltas' can reconnect them, the export ignores them (no group), Ctrl+Z restores
	 * the group as before (the delete's undo entry already saves those animations).
	 */
	public static native void installKeepOnDelete() /*-{
		var G = $wnd.Group, A = $wnd.Animation, B = $wnd.BoneAnimator;
		if (!G || !A || !B || !G.prototype.remove || !A.prototype.removeAnimator || G.prototype.__cpmplusKeep)return;
		var origRemove = G.prototype.remove, origRA = A.prototype.removeAnimator;
		var depth = 0, undoDepth = 0;
		var chans = ['position', 'rotation', 'scale', 'cpm_visibility', 'cpm_color'];
		// an undo / redo / cancelled edit restores the saved state exactly: groups it removes take their keys along
		var U = $wnd.UndoSystem && $wnd.UndoSystem.prototype && typeof $wnd.UndoSystem.prototype.loadSave === 'function' ? $wnd.UndoSystem.prototype : null;
		var origLoad = U ? U.loadSave : null;
		if (U) {
			U.loadSave = function() {
				undoDepth++;
				try {
					return origLoad.apply(this, arguments);
				} finally {
					undoDepth--;
				}
			};
		}
		G.prototype.remove = function() {
			var keep = false;
			try {
				keep = undoDepth == 0 && @com.tom.cpm.blockbench.format.CPMCodec::isCPM()();
			} catch (e) {
			}
			if (!keep)return origRemove.apply(this, arguments);
			depth++;
			try {
				return origRemove.apply(this, arguments);
			} finally {
				depth--;
			}
		};
		A.prototype.removeAnimator = function(uuid) {
			var ba = depth > 0 && this.animators ? this.animators[uuid] : null;
			var n = 0;
			if (ba instanceof B) {
				for (var i = 0; i < chans.length; i++)if (ba[chans[i]] && ba[chans[i]].length)n += ba[chans[i]].length;
			}
			if (n == 0)return origRA.apply(this, arguments);
			try {
				var O = $wnd.OutlinerNode;
				var node = O && O.uuids ? O.uuids[uuid] : null;
				if (node && node.name)ba._name = node.name;
				var T = $wnd.Timeline;
				if (T) {
					if (T.animators && T.animators.remove)T.animators.remove(ba);
					if (T.selected_animator == ba)T.selected_animator = null;
				}
			} catch (e) {
			}
			return this;
		};
		G.prototype.__cpmplusKeep = {remove: origRemove, ra: origRA, U: U, load: origLoad};
	}-*/;

	public static native void uninstallKeepOnDelete() /*-{
		var G = $wnd.Group, A = $wnd.Animation;
		var k = G ? G.prototype.__cpmplusKeep : null;
		if (!k)return;
		G.prototype.remove = k.remove;
		A.prototype.removeAnimator = k.ra;
		if (k.U && k.load)k.U.loadSave = k.load;
		delete G.prototype.__cpmplusKeep;
	}-*/;

	/** finish_edit / before export: '_name' = group name on every animator whose group exists (+ session map) */
	public static void stampNames(Object ignored) {
		try {
			if (!CPMCodec.isCPM() || Animation.all == null)return;
			checkSession();
			for (Animation a : Animation.all) {
				if (a == null || a.animators == null)continue;
				a.animators.forEach(k -> {
					GeneralAnimator ga = a.animators.get(k);
					if (!(ga instanceof BoneAnimator))return;
					BoneAnimator ba = (BoneAnimator) ga;
					Object n = liveNode(ba.uuid);
					if (n instanceof Group) {
						String name = ((Group) n).name;
						if (name != null && !name.equals(ba.rawName))ba.rawName = name;
						if (name != null)SESSION_NAMES.put(ba.uuid, name);
					}
				});
			}
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: could not remember the animated part names", e);
		}
	}

	/** every orphan of the open project, in the order first seen (animations in project order) */
	public static List<Orphan> find() {
		checkSession();
		Map<String, Orphan> map = new LinkedHashMap<>();
		if (Animation.all == null)return new ArrayList<>();
		for (Animation a : Animation.all) {
			if (a == null || a.animators == null)continue;
			a.animators.forEach(k -> {
				GeneralAnimator ga = a.animators.get(k);
				if (!(ga instanceof BoneAnimator))return;
				BoneAnimator ba = (BoneAnimator) ga;
				if (!"bone".equals(ba.type) && ba.type != null)return;
				if (!ba.hasTransform() || ba.uuid == null)return;
				if (liveNode(ba.uuid) != null)return;
				Orphan o = map.computeIfAbsent(ba.uuid, Orphan::new);
				if (!o.anims.contains(a))o.anims.add(a);
				for (String ch : CHANNELS) {
					JsArrayE<Keyframe> arr = ba.channel(ch);
					if (arr == null || arr.length == 0)continue;
					o.perChannel.merge(ch, arr.length, Integer::sum);
					o.keys += arr.length;
				}
				if (!o.named) {
					String nm = ba.rawName;
					if (nm == null || nm.isEmpty())nm = SESSION_NAMES.get(ba.uuid);
					if (nm != null && !nm.isEmpty()) {
						o.name = nm;
						o.named = true;
					}
				}
			});
		}
		for (Orphan o : map.values()) {
			if (!o.named)o.name = I18n.format("cpmplus.w4exportfix.orphan.unnamed", o.uuid.length() > 6 ? o.uuid.substring(0, 6) : o.uuid);
		}
		return new ArrayList<>(map.values());
	}

	// ------------------------------------------------------------------ targets

	/** live groups whose name equals name ignoring case */
	static List<Group> sameName(String name) {
		List<Group> l = new ArrayList<>();
		if (name == null || Group.all == null)return l;
		for (Group g : Group.all)if (g.name != null && g.name.equalsIgnoreCase(name))l.add(g);
		return l;
	}

	/** the group to preselect: the only live group with the same name (case-insensitive), else null */
	public static Group preselect(Orphan o) {
		if (!o.named)return null;
		List<Group> l = sameName(o.name);
		return l.size() == 1 ? l.get(0) : null;
	}

	// ------------------------------------------------------------------ apply

	public static enum Act {
		RECONNECT, DELETE, LEAVE
	}

	public static final class Plan {
		public final String uuid;
		public final Act act;
		/** RECONNECT: destination group uuid */
		public final String target;
		/** RECONNECT into a destination that already has keys: true = Juntar (keep the destination's key on a time clash), false = Substituir */
		public final boolean merge;

		public Plan(String uuid, Act act, String target, boolean merge) {
			this.uuid = uuid;
			this.act = act;
			this.target = target;
			this.merge = merge;
		}
	}

	public static final class Result {
		public int reconnectedParts, reconnectedKeys, deletedParts, deletedKeys, left, discarded;
		public final List<String> errors = new ArrayList<>();

		public String summary() {
			return I18n.format("cpmplus.w4exportfix.orphan.summary", reconnectedParts, reconnectedKeys, deletedParts, deletedKeys, left);
		}
	}

	/** true when the destination already has keys (in the channels the orphan uses) in one of the orphan's animations */
	public static boolean targetHasKeys(Orphan o, String targetUuid) {
		for (Animation a : o.anims) {
			BoneAnimator src = animator(a, o.uuid);
			BoneAnimator dst = animator(a, targetUuid);
			if (src == null || dst == null)continue;
			for (String ch : CHANNELS) {
				JsArrayE<Keyframe> s = src.channel(ch), d = dst.channel(ch);
				if (s != null && s.length > 0 && d != null && d.length > 0)return true;
			}
		}
		return false;
	}

	/**
	 * Rows that would write two orphans into the same destination in the same animation without Juntar
	 * (returns the uuids of those orphans).
	 */
	public static Set<String> collisions(List<Orphan> orphans, List<Plan> plans) {
		Map<String, Orphan> byUuid = new HashMap<>();
		for (Orphan o : orphans)byUuid.put(o.uuid, o);
		Map<String, List<Plan>> perKey = new HashMap<>();
		for (Plan p : plans) {
			if (p.act != Act.RECONNECT || p.target == null)continue;
			Orphan o = byUuid.get(p.uuid);
			if (o == null)continue;
			for (Animation a : o.anims)perKey.computeIfAbsent(a.uuid + "|" + p.target, k -> new ArrayList<>()).add(p);
		}
		Set<String> bad = new LinkedHashSet<>();
		for (List<Plan> l : perKey.values()) {
			if (l.size() < 2)continue;
			for (Plan p : l)if (!p.merge)bad.add(p.uuid);
		}
		return bad;
	}

	/** applies the plans as ONE undo entry ('Reconectar anima\u00e7\u00f5es soltas'); LEAVE rows change nothing */
	public static Result apply(List<Plan> plans) {
		Result r = new Result();
		List<Orphan> orphans = find();
		Map<String, Orphan> byUuid = new HashMap<>();
		for (Orphan o : orphans)byUuid.put(o.uuid, o);
		Set<String> bad = collisions(orphans, plans);
		if (!bad.isEmpty())throw new IllegalStateException(I18n.get("cpmplus.w4exportfix.orphan.collision"));
		Set<Animation> affected = new LinkedHashSet<>();
		List<Plan> work = new ArrayList<>();
		for (Plan p : plans) {
			Orphan o = byUuid.get(p.uuid);
			if (o == null || p.act == Act.LEAVE) {
				if (o != null)r.left++;
				continue;
			}
			if (p.act == Act.RECONNECT) {
				Object t = p.target != null ? liveNode(p.target) : null;
				if (!(t instanceof Group)) {
					r.errors.add(o.name);
					continue;
				}
			}
			work.add(p);
			affected.addAll(o.anims);
		}
		if (work.isEmpty())return r;
		// (a) destination animators exist BEFORE the undo entry opens (the saved copy then contains them, empty)
		for (Plan p : work) {
			if (p.act != Act.RECONNECT)continue;
			Group dst = (Group) liveNode(p.target);
			for (Animation a : byUuid.get(p.uuid).anims) {
				if (animator(a, dst.uuid) == null)a.animators.set(dst.uuid, new BoneAnimator(dst.uuid, a, dst.name));
			}
		}
		List<Object[]> emptied = new ArrayList<>();
		PlusUndo.editAnimations(affected, I18n.get("cpmplus.w4exportfix.undo.orphans"), () -> {
			for (Plan p : work) {
				Orphan o = byUuid.get(p.uuid);
				int moved = 0;
				for (Animation a : o.anims) {
					BoneAnimator src = animator(a, o.uuid);
					if (src == null)continue;
					if (p.act == Act.DELETE) {
						r.deletedKeys += countKeys(src);
						for (String ch : CHANNELS)clearChannel(src, ch);
					} else {
						BoneAnimator dst = animator(a, p.target);
						double tol = 0.5 / Math.max(1, a.snapping > 0 ? a.snapping : 20);
						boolean rotKeys = src.rotation != null && src.rotation.length > 0;
						for (String ch : CHANNELS) {
							JsArrayE<Keyframe> s = src.channel(ch);
							if (s == null || s.length == 0)continue;
							if (!p.merge)clearChannel(dst, ch);
							JsPropertyMap<Object> res = Js.asPropertyMap(moveKeys(src, dst, ch, p.merge, tol));
							moved += (int) Js.asDouble(res.get("moved"));
							r.discarded += (int) Js.asDouble(res.get("dropped"));
							if (!p.merge && src.isMuted(ch))setMuted(dst, ch);
						}
						if (rotKeys && src.rotation_global)dst.rotation_global = true;
					}
					emptied.add(new Object[] {a, o.uuid});
				}
				if (p.act == Act.DELETE)r.deletedParts++;
				else {
					r.reconnectedParts++;
					r.reconnectedKeys += moved;
				}
			}
		});
		// the empty source animators are dropped after the entry (undo recreates them from the saved copy)
		for (Object[] e : emptied) {
			Animation a = (Animation) e[0];
			String uuid = (String) e[1];
			BoneAnimator ba = animator(a, uuid);
			if (ba != null && countKeys(ba) == 0) {
				try {
					a.removeAnimator(uuid);
				} catch (Throwable ex) {
				}
			}
		}
		refreshTimeline();
		PlusTestHooks.count("w4exportfixOrphanApply");
		return r;
	}

	/** removes every keyframe of a channel (Keyframe.remove also drops it from the timeline selection) */
	private static native void clearChannel(BoneAnimator ba, String ch) /*-{
		var arr = ba[ch];
		if (!arr || !arr.length) return;
		var copy = arr.slice();
		for (var i = 0; i < copy.length; i++) {
			var kf = copy[i];
			if (typeof kf.remove == 'function') kf.remove();
		}
		if (arr.length) arr.splice(0, arr.length);
	}-*/;

	/**
	 * moves every key of src[ch] into dst[ch] (the same Keyframe objects: time, interpolation, bezier, data points
	 * kept); merge: a src key within tol of a dst key is dropped (the destination's key stays). Returns [moved, dropped].
	 */
	private static native Object moveKeys(BoneAnimator src, BoneAnimator dst, String ch, boolean merge, double tol) /*-{
		var s = src[ch];
		if (!dst[ch]) dst[ch] = [];
		var d = dst[ch];
		var moved = 0, dropped = 0;
		var copy = s.slice();
		var existing = d.slice();
		for (var i = 0; i < copy.length; i++) {
			var kf = copy[i];
			var idx = s.indexOf(kf);
			if (idx >= 0) s.splice(idx, 1);
			var clash = false;
			if (merge) {
				for (var j = 0; j < existing.length; j++) {
					if (Math.abs(existing[j].time - kf.time) < tol) { clash = true; break; }
				}
			}
			if (clash) {
				if (kf.selected && $wnd.Timeline && $wnd.Timeline.selected) {
					var si = $wnd.Timeline.selected.indexOf(kf);
					if (si >= 0) $wnd.Timeline.selected.splice(si, 1);
				}
				dropped++;
				continue;
			}
			kf.animator = dst;
			d.push(kf);
			moved++;
		}
		return {moved: moved, dropped: dropped};
	}-*/;

	private static native void setMuted(BoneAnimator ba, String ch) /*-{
		try {
			if (!ba.muted) ba.muted = {};
			if ($wnd.Vue && $wnd.Vue.set) $wnd.Vue.set(ba.muted, ch, true); else ba.muted[ch] = true;
		} catch (e) {}
	}-*/;

	private static void refreshTimeline() {
		try {
			if (Modes.animate != null && Modes.animate.selected && Animation.selected != null)Animator.preview();
		} catch (Throwable e) {
		}
		forceTimelineUpdate();
	}

	private static native void forceTimelineUpdate() /*-{
		try {
			var T = $wnd.Timeline;
			if (T && T.vue && typeof T.vue.$forceUpdate == 'function') T.vue.$forceUpdate();
		} catch (e) {}
	}-*/;

	// ------------------------------------------------------------------ export warning

	/** ExportCheck: one row per old part, never blocking */
	public static void exportCheck(ExportContext ctx) {
		stampNames(null);
		for (Orphan o : find()) {
			WarnEntry w = WarnFactory.of(Severity.DIFFERS, "orphanAnim", "cpmplus.w4exportfix.orphan.warn", o.name, o.keys, o.anims.size());
			w.setConsequence("cpmplus.w4exportfix.orphan.ifIgnored");
			w.setFix(() -> openDialog(o.uuid), FixKind.ASKS);
			w.setFixPreview(I18n.get("cpmplus.w4exportfix.orphan.fixPreview"));
			w.addNode(o.uuid, o.name);
			ctx.add(w);
		}
	}

	// ------------------------------------------------------------------ dialog

	public static void openMenu() {
		openDialog(null);
	}

	/**
	 * 'Anima\u00e7\u00f5es soltas\u2026': one row per old part (Pe\u00e7a antiga | Anima\u00e7\u00f5es (n keys) | Ligar em | A\u00e7\u00e3o). Resolves true
	 * when something was applied. focusUuid (optional) = the row of the export warning, shown first.
	 */
	public static Promise<Boolean> openDialog(String focusUuid) {
		return new Promise<>((res, rej) -> {
			stampNames(null);
			List<Orphan> orphans = find();
			if (orphans.isEmpty()) {
				PlusDialogs.info("cpmplus_w4exportfix_orphans_none", "cpmplus.w4exportfix.orphan.title", java.util.Collections.singletonList("cpmplus.w4exportfix.orphan.none"));
				res.onInvoke(false);
				return;
			}
			if (focusUuid != null) {
				for (int i = 0; i < orphans.size(); i++) {
					if (orphans.get(i).uuid.equals(focusUuid)) {
						orphans.add(0, orphans.remove(i));
						break;
					}
				}
			}
			List<Group> groups = new ArrayList<>();
			if (Group.all != null)for (Group g : Group.all)groups.add(g);
			groups.sort((a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));

			HTMLElement root = el("div", null);
			root.style.setProperty("max-height", "60vh");
			root.style.setProperty("overflow", "auto");
			HTMLElement note = el("p", I18n.get("cpmplus.w4exportfix.orphan.note"));
			note.style.setProperty("opacity", "0.8");
			root.appendChild(note);
			HTMLElement table = el("table", null);
			table.style.setProperty("width", "100%");
			HTMLElement head = el("tr", null);
			for (String k : new String[] {"cpmplus.w4exportfix.orphan.colPart", "cpmplus.w4exportfix.orphan.colAnims", "cpmplus.w4exportfix.orphan.colTarget", "cpmplus.w4exportfix.orphan.colAction"}) {
				HTMLElement th = el("th", I18n.get(k));
				th.style.setProperty("text-align", "left");
				head.appendChild(th);
			}
			table.appendChild(head);
			List<Row> rows = new ArrayList<>();
			for (Orphan o : orphans) {
				Row row = new Row(o);
				rows.add(row);
				HTMLElement tr = el("tr", null);
				row.tr = tr;
				tr.setAttribute("data-orphan", o.uuid);
				HTMLElement c1 = el("td", o.name);
				tr.appendChild(c1);
				tr.appendChild(el("td", I18n.format("cpmplus.w4exportfix.orphan.animsCell", o.anims.size(), o.keys)));
				HTMLElement c3 = el("td", null);
				HTMLSelectElement tgt = (HTMLSelectElement) DomGlobal.document.createElement("select");
				tgt.className = "cpmplus_orphan_target";
				addOption(tgt, "", I18n.get("cpmplus.w4exportfix.orphan.choose"));
				for (Group g : groups)addOption(tgt, g.uuid, g.name);
				Group pre = preselect(o);
				if (pre != null)tgt.value = pre.uuid;
				row.target = tgt;
				c3.appendChild(tgt);
				HTMLElement badge = el("div", "");
				badge.style.setProperty("font-size", "0.85em");
				row.badge = badge;
				c3.appendChild(badge);
				HTMLSelectElement mode = (HTMLSelectElement) DomGlobal.document.createElement("select");
				mode.className = "cpmplus_orphan_mode";
				addOption(mode, "replace", I18n.get("cpmplus.w4exportfix.orphan.modeReplace"));
				addOption(mode, "merge", I18n.get("cpmplus.w4exportfix.orphan.modeMerge"));
				row.mode = mode;
				c3.appendChild(mode);
				tr.appendChild(c3);
				HTMLElement c4 = el("td", null);
				HTMLSelectElement act = (HTMLSelectElement) DomGlobal.document.createElement("select");
				act.className = "cpmplus_orphan_action";
				addOption(act, "reconnect", I18n.get("cpmplus.w4exportfix.orphan.actReconnect"));
				addOption(act, "delete", I18n.get("cpmplus.w4exportfix.orphan.actDelete"));
				addOption(act, "leave", I18n.get("cpmplus.w4exportfix.orphan.actLeave"));
				act.value = pre != null ? "reconnect" : "leave";
				row.action = act;
				c4.appendChild(act);
				tr.appendChild(c4);
				table.appendChild(tr);
			}
			root.appendChild(table);
			HTMLElement footer = el("p", "");
			footer.className = "cpmplus_orphan_footer";
			root.appendChild(footer);
			HTMLElement err = el("p", "");
			err.className = "cpmplus_orphan_error";
			err.style.setProperty("color", "var(--color-close, #f44336)");
			root.appendChild(err);

			Runnable update = () -> updateRows(orphans, rows, footer, err);
			for (Row row : rows) {
				row.target.addEventListener("change", e -> update.run());
				row.action.addEventListener("change", e -> update.run());
				row.mode.addEventListener("change", e -> update.run());
			}
			update.run();

			boolean[] done = new boolean[1];
			Dialog.DialogProperties d = new Dialog.DialogProperties();
			d.id = "cpmplus_w4exportfix_orphans";
			d.title = I18n.get("cpmplus.w4exportfix.orphan.title");
			d.width = 760;
			d.lineNodes = new Object[] {root};
			d.buttons = new String[] {I18n.get("cpmplus.w4exportfix.orphan.apply"), I18n.get("cpmplus.w0binfra.btn.cancel")};
			d.confirmIndex = 0;
			d.cancelIndex = 1;
			d.onConfirm = x -> {
				List<Plan> plans = plansOf(rows);
				Set<String> bad = collisions(orphans, plans);
				if (!bad.isEmpty()) {
					update.run();
					return false;
				}
				for (Plan p : plans) {
					if (p.act == Act.RECONNECT && (p.target == null || p.target.isEmpty())) {
						err.textContent = I18n.get("cpmplus.w4exportfix.orphan.needTarget");
						return false;
					}
				}
				try {
					Result r = apply(plans);
					done[0] = true;
					if (r.reconnectedParts + r.deletedParts > 0) {
						String msg = r.summary();
						if (r.discarded > 0)msg += " " + I18n.format("cpmplus.w4exportfix.orphan.discarded", r.discarded);
						PlusDialogs.toast(msg + " " + I18n.get("cpmplus.w4exportfix.parts.undoHint"), "check", 8000, null);
					}
					res.onInvoke(r.reconnectedParts + r.deletedParts > 0);
				} catch (Throwable e) {
					com.tom.cpm.blockbench.util.PopupDialogs.displayError(PlusDialogs.esc(d.title), e);
					res.onInvoke(false);
				}
				return true;
			};
			d.onCancel = () -> {
				if (!done[0])res.onInvoke(false);
				return true;
			};
			new Dialog(d).show();
		});
	}

	private static final class Row {
		final Orphan o;
		HTMLElement tr, badge;
		HTMLSelectElement target, mode, action;

		Row(Orphan o) {
			this.o = o;
		}
	}

	private static List<Plan> plansOf(List<Row> rows) {
		List<Plan> plans = new ArrayList<>();
		for (Row r : rows) {
			Act a = "reconnect".equals(r.action.value) ? Act.RECONNECT : "delete".equals(r.action.value) ? Act.DELETE : Act.LEAVE;
			String t = r.target.value;
			boolean hasKeys = a == Act.RECONNECT && t != null && !t.isEmpty() && targetHasKeys(r.o, t);
			plans.add(new Plan(r.o.uuid, a, t == null || t.isEmpty() ? null : t, hasKeys && "merge".equals(r.mode.value)));
		}
		return plans;
	}

	private static void updateRows(List<Orphan> orphans, List<Row> rows, HTMLElement footer, HTMLElement err) {
		List<Plan> plans = plansOf(rows);
		Set<String> bad = collisions(orphans, plans);
		int rp = 0, rk = 0, dp = 0, dk = 0, lv = 0;
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			Plan p = plans.get(i);
			String t = r.target.value;
			boolean hasKeys = t != null && !t.isEmpty() && targetHasKeys(r.o, t);
			r.mode.style.setProperty("display", p.act == Act.RECONNECT && hasKeys ? "" : "none");
			String b = "";
			Group pre = preselect(r.o);
			if (pre != null && pre.uuid.equals(t))b = I18n.get("cpmplus.w4exportfix.orphan.sameName");
			else if (r.o.named && sameName(r.o.name).size() > 1)b = I18n.get("cpmplus.w4exportfix.orphan.repeatedName");
			r.badge.textContent = b;
			r.tr.style.setProperty("background", bad.contains(r.o.uuid) ? "rgba(244, 67, 54, 0.25)" : "");
			switch (p.act) {
			case RECONNECT:
				rp++;
				rk += r.o.keys;
				break;
			case DELETE:
				dp++;
				dk += r.o.keys;
				break;
			default:
				lv++;
			}
		}
		footer.textContent = I18n.format("cpmplus.w4exportfix.orphan.footer", rp, rk, dp, dk, lv);
		err.textContent = bad.isEmpty() ? "" : I18n.get("cpmplus.w4exportfix.orphan.collision");
	}

	private static HTMLElement el(String tag, String text) {
		HTMLElement e = (HTMLElement) DomGlobal.document.createElement(tag);
		if (text != null)e.textContent = text;
		return e;
	}

	private static void addOption(HTMLSelectElement sel, String value, String label) {
		HTMLOptionElement o = (HTMLOptionElement) DomGlobal.document.createElement("option");
		o.value = value;
		o.textContent = label;
		sel.appendChild(o);
	}

	// ------------------------------------------------------------------ test hooks

	/** test tool: the orphans as JSON-like data */
	public static Object listJson() {
		elemental2.core.JsArray<Object> out = new elemental2.core.JsArray<>();
		for (Orphan o : find()) {
			JsPropertyMap<Object> m = JsPropertyMap.of();
			m.set("uuid", o.uuid);
			m.set("name", o.name);
			m.set("named", o.named);
			m.set("keys", (double) o.keys);
			m.set("anims", (double) o.anims.size());
			Group pre = preselect(o);
			m.set("preselect", pre != null ? pre.uuid : null);
			JsPropertyMap<Object> ch = JsPropertyMap.of();
			o.perChannel.forEach((k, v) -> ch.set(k, (double) v));
			m.set("channels", ch);
			out.push(m);
		}
		return out;
	}

	/** test tool: apply plans [{uuid, act: reconnect|delete|leave, target, merge}] */
	public static Object applyJson(Object opts) {
		JsPropertyMap<Object> o = Js.asPropertyMap(opts);
		Object pl = o.get("plans");
		List<Plan> plans = new ArrayList<>();
		if (pl != null) {
			elemental2.core.JsArray<Object> arr = Js.uncheckedCast(pl);
			for (int i = 0; i < arr.length; i++) {
				JsPropertyMap<Object> p = Js.asPropertyMap(arr.getAt(i));
				String act = String.valueOf(p.get("act")).toLowerCase(Locale.ROOT);
				Act a = act.startsWith("rec") ? Act.RECONNECT : act.startsWith("del") ? Act.DELETE : Act.LEAVE;
				Object t = p.get("target");
				plans.add(new Plan(String.valueOf(p.get("uuid")), a, t != null ? String.valueOf(t) : null, Js.isTruthy(p.get("merge"))));
			}
		}
		Result r = apply(plans);
		JsPropertyMap<Object> m = JsPropertyMap.of();
		m.set("ok", r.errors.isEmpty());
		m.set("reconnectedParts", (double) r.reconnectedParts);
		m.set("reconnectedKeys", (double) r.reconnectedKeys);
		m.set("deletedParts", (double) r.deletedParts);
		m.set("deletedKeys", (double) r.deletedKeys);
		m.set("left", (double) r.left);
		m.set("discarded", (double) r.discarded);
		m.set("summary", r.summary());
		return m;
	}

	static Collection<String> sessionNames() {
		return SESSION_NAMES.values();
	}
}
