package com.tom.cpm.blockbench.plus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tom.cpm.blockbench.PlusMenus;
import com.tom.cpm.blockbench.PlusMenus.Cond;
import com.tom.cpm.blockbench.PlusTestHooks;
import com.tom.cpm.blockbench.PluginStart;
import com.tom.cpm.blockbench.convert.ExportHooks;
import com.tom.cpm.blockbench.convert.ImportHooks;
import com.tom.cpm.blockbench.convert.WarnEntry;
import com.tom.cpm.blockbench.convert.WarnEntry.FixKind;
import com.tom.cpm.blockbench.convert.WarnEntry.Severity;
import com.tom.cpm.blockbench.convert.WarnEntry.WarnFactory;
import com.tom.cpm.blockbench.convert.WarnFixes;
import com.tom.cpm.blockbench.format.AnimationWizard;
import com.tom.cpm.blockbench.format.CPMCodec;
import com.tom.cpm.blockbench.format.ProjectData;
import com.tom.cpm.blockbench.proxy.Action;
import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.Dialog;
import com.tom.cpm.blockbench.proxy.Project;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;
import com.tom.cpm.blockbench.util.PopupDialogs;
import com.tom.cpm.blockbench.util.ProjectDataUndo;
import com.tom.cpm.shared.animation.AnimationType;
import com.tom.cpm.shared.editor.Editor;
import com.tom.cpm.shared.editor.anim.EditorAnim;
import com.tom.cpm.web.client.util.I18n;

import elemental2.core.JsArray;
import elemental2.dom.DomGlobal;
import elemental2.promise.Promise;
import jsinterop.annotations.JsFunction;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (G54, lite/gestparts): one gesture / layer made of several animations, with CPM's own 'Name#part' rule.
 * <p>
 * CPM groups animations by the text before the first '#' (EditorAnim.getId(), AnimationExporter keys every button by
 * it): 'Dance4#corpo', 'Dance4#perna' and 'Dance4#cabeca' are ONE button 'Dance4' in game. For Blockbench they are
 * three different names, so Blockbench never renames them (Animation.createUniqueName only renames exact duplicates,
 * after stripping the trailing digits: a second 'Dance4' became 'Dance2', another gesture). No new property: the CPM
 * editor and every plugin read the same names, nothing in the file format changes.
 * <ul>
 * <li>CPM animation dialog (gesture / layer / value layer only): 'Nome no jogo' + 'Parte (opcional)' instead of the
 * name; the name becomes jogo#parte. Part labels never end in a digit. Other types keep the plain name ('#' as is).</li>
 * <li>'Juntar no mesmo gesto...' / 'Separar deste gesto...' (animation context menu, CPM+ &gt; Animacao): one undo.</li>
 * <li>Badge 'parte de: Dance4 (3)' next to the name in the Animations panel.</li>
 * <li>Export checks: same name in game used by different types (BREAKS); parts of one button with different button
 * settings (DIFFERS, one-click fix, one undo); 'Dance2' next to 'Dance4' looks like a Blockbench renumbering (INFO,
 * dismissible, never automatic).</li>
 * <li>Import: legacy files with several gestures / layers of the SAME name and type become 'Name#parte_a',
 * '#parte_b'... (same button in game, Blockbench no longer renumbers them) + a note.</li>
 * <li>CUSTOM_POSE stays out of the grouping UI (the export builds the custom pose from the full name; untested in
 * game). It still takes part in the 'same name, different types' check.</li>
 * </ul>
 */
public final class PlusGestureParts {
	private static final String L = "cpmplus.gestparts.";
	public static final String KEY_TYPES = "gestparts.gameIdTypes";
	public static final String KEY_BUTTON = "gestparts.buttonDiffers";
	public static final String KEY_MAYBE = "gestparts.maybeParts";
	/** ProjectData extra: 'type|stem' suggestions the user dismissed */
	static final String EXTRA_IGNORE = "cpmplusGestPartsIgnore";
	static final String F_GAME = "cpmplus_gp_game", F_PART = "cpmplus_gp_part", F_HINT = "cpmplus_gp_hint";
	private static final String BADGE_CLASS = "cpmplus_gp_badge";

	private static Object observer;
	private static boolean syncQueued;

	private PlusGestureParts() {
	}

	// ------------------------------------------------------------------ name helpers

	/** the button id in game: text before the first '#' (EditorAnim.getId) */
	public static String gameId(String name) {
		if(name == null)return "";
		int i = name.indexOf('#');
		return i == -1 ? name : name.substring(0, i);
	}

	/** the part label: text after the first '#', "" when none */
	public static String partLabel(String name) {
		if(name == null)return "";
		int i = name.indexOf('#');
		return i == -1 ? "" : name.substring(i + 1);
	}

	/** jogo#parte, or jogo when the part is empty */
	public static String compose(String game, String part) {
		String g = game == null ? "" : game.trim();
		String p = part == null ? "" : part.trim();
		return p.isEmpty() ? g : g + "#" + p;
	}

	/** the CPM custom animation type of a Blockbench cpm_type, null for poses / unknown */
	static AnimationType customType(String type) {
		if(type == null)return null;
		String t = type.trim();
		for (AnimationType at : AnimationType.VALUES) {
			if(at.isCustom() && at.name().equalsIgnoreCase(t))return at;
		}
		return null;
	}

	/** gesture / layer / value layer: the types whose parts share one button */
	public static boolean isGroupedType(String type) {
		AnimationType t = customType(type);
		return t == AnimationType.GESTURE || t == AnimationType.LAYER || t == AnimationType.VALUE_LAYER;
	}

	static String typeLabel(AnimationType t) {
		return I18n.get("label.cpm.new_anim_" + t.name().toLowerCase(Locale.ROOT));
	}

	/** null when the part label is fine, else the reason */
	public static String checkPartLabel(String part) {
		if(part == null || part.trim().isEmpty())return null;
		String p = part.trim();
		if(p.indexOf('#') >= 0)return I18n.get(L + "err.hashPart");
		if(Character.isDigit(p.charAt(p.length() - 1)))return I18n.get(L + "err.digit");
		return null;
	}

	/** null when the name in game is fine, else the reason */
	public static String checkGameName(String game) {
		if(game == null || game.trim().isEmpty())return I18n.get(L + "err.emptyGame");
		if(game.indexOf('#') >= 0)return I18n.get(L + "err.hashGame");
		return null;
	}

	static List<Animation> anims() {
		List<Animation> l = new ArrayList<>();
		if(Animation.all == null)return l;
		for (Animation a : Animation.all)if(a != null && a.name != null)l.add(a);
		return l;
	}

	/** grouped-type animations with this name in game and the same type as 'type' */
	public static List<Animation> partsOf(String game, String type) {
		List<Animation> l = new ArrayList<>();
		AnimationType t = customType(type);
		if(!isGroupedType(type))return l;
		for (Animation a : anims()) {
			if(customType(a.type) != t)continue;
			if(gameId(a.name).equals(game))l.add(a);
		}
		return l;
	}

	/** 'parte de: Dance4 (3)' or null when the animation is a single button */
	public static String badge(Animation a) {
		if(a == null || a.name == null || !isGroupedType(a.type))return null;
		String g = gameId(a.name);
		int n = partsOf(g, a.type).size();
		if(n < 2 && a.name.indexOf('#') < 0)return null;
		return I18n.format(L + "badge", g, String.valueOf(n));
	}

	/** staged animations ('g:Name', any case of the prefix) that link to this name in game */
	static List<Animation> stagedLinks(String game) {
		List<Animation> l = new ArrayList<>();
		for (Animation a : anims()) {
			AnimationType t = customType(a.type);
			if(t == null || !t.isStaged())continue;
			String[] sp = a.name.split(":", 2);
			if(sp.length == 2 && sp[0].equalsIgnoreCase("g") && gameId(sp[1]).equals(game))l.add(a);
		}
		return l;
	}

	private static String str(JsPropertyMap<Object> r, String key) {
		Object v = r == null ? null : r.get(key);
		return v == null ? "" : String.valueOf(v);
	}

	// ------------------------------------------------------------------ CPM animation dialog

	@JsFunction
	interface DlgHook {
		void run(Object dialog, Object result);
	}

	@JsFunction
	interface DlgCheck {
		boolean run(Object dialog, Object result);
	}

	private static final class WizState {
		boolean grouped;
		String name = "", game = "", part = "";
	}

	/**
	 * Called by AnimationWizard right before the dialog is shown: adds 'Nome no jogo' + 'Parte (opcional)' (+ a tip)
	 * after the name field, shown instead of the name for gesture / layer / value layer, and composes the name on OK.
	 * Never throws: when anything fails the dialog stays exactly as it was.
	 */
	public static void decorateWizard(Dialog.DialogProperties dctr, Animation anIn) {
		try {
			decorate0(dctr, anIn);
		} catch (Throwable e) {
			DomGlobal.console.error("CPM+: gesture part fields not added to the animation dialog", e);
		}
	}

	private static void decorate0(Dialog.DialogProperties dctr, Animation anIn) {
		if(dctr == null || dctr.form == null)return;
		JsPropertyMap<Object> old = Js.uncheckedCast(dctr.form);
		Object nameEl = old.get("name");
		if(nameEl == null || old.get("type") == null)return;
		String start = anIn != null ? anIn.name : Js.<Dialog.FormTextboxElement>uncheckedCast(nameEl).value;
		Dialog.FormTextboxElement game = Dialog.FormTextboxElement.make(I18n.get(L + "wiz.game"), gameId(start));
		game.description = I18n.get(L + "wiz.game.desc");
		Dialog.FormTextboxElement part = Dialog.FormTextboxElement.make(I18n.get(L + "wiz.part"), partLabel(start));
		part.description = I18n.get(L + "wiz.part.desc");
		String hintText = I18n.get(L + "wiz.hint");
		String b = anIn != null ? badge(anIn) : null;
		if(b != null)hintText = hintText + "\n" + b;
		Dialog.FormInfoElement hint = Dialog.FormInfoElement.make(I18n.get(L + "wiz.hint.label"), PlusDialogs.escMdLines(hintText), null);
		JsPropertyMap<Object> nf = JsPropertyMap.of();
		old.forEach(k -> {
			nf.set(k, old.get(k));
			if("name".equals(k)) {
				nf.set(F_GAME, game);
				nf.set(F_PART, part);
				nf.set(F_HINT, hint);
			}
		});
		dctr.form = Js.uncheckedCast(nf);
		Dialog.link(dctr.form);
		WizState st = new WizState();
		wrapDialog(dctr, (d, r) -> wizOpen(d, st), (d, r) -> wizChange(d, r, st), (d, r) -> wizConfirm(r, anIn));
	}

	private static void snap(WizState st, JsPropertyMap<Object> r) {
		st.name = str(r, "name");
		st.game = str(r, F_GAME);
		st.part = str(r, F_PART);
	}

	private static JsPropertyMap<Object> resultOf(Object dialog) {
		if(dialog == null)return null;
		try {
			return Js.uncheckedCast(Js.<Dialog>uncheckedCast(dialog).getFormResult());
		} catch (Throwable e) {
			return null;
		}
	}

	private static void wizOpen(Object d, WizState st) {
		JsPropertyMap<Object> r = resultOf(d);
		if(r == null)return;
		st.grouped = isGroupedType(str(r, "type"));
		snap(st, r);
		showFields(d, st.grouped);
	}

	private static void wizChange(Object d, Object rIn, WizState st) {
		JsPropertyMap<Object> r = Js.uncheckedCast(rIn);
		if(r == null)return;
		boolean g = isGroupedType(str(r, "type"));
		if(g != st.grouped) {
			// carry what the user typed into the field that is about to show (never overwrite what was typed there)
			JsPropertyMap<Object> set = JsPropertyMap.of();
			boolean any = false;
			if(g) {
				String n = str(r, "name");
				if(!n.equals(st.name)) {
					set.set(F_GAME, gameId(n.trim()));
					set.set(F_PART, partLabel(n.trim()));
					any = true;
				}
			} else {
				String gm = str(r, F_GAME), pt = str(r, F_PART);
				if(!gm.equals(st.game) || !pt.equals(st.part)) {
					set.set("name", compose(gm, pt));
					any = true;
				}
			}
			if(any && d != null)Js.<Dialog>uncheckedCast(d).setFormValues(set, false);
			st.grouped = g;
			JsPropertyMap<Object> now = resultOf(d);
			snap(st, now != null ? now : r);
		}
		showFields(d, g);
	}

	private static boolean wizConfirm(Object rIn, Animation self) {
		JsPropertyMap<Object> r = Js.uncheckedCast(rIn);
		if(r == null || !isGroupedType(str(r, "type")))return true;
		if(self != null && str(r, F_GAME).equals(gameId(self.name)) && str(r, F_PART).equals(partLabel(self.name))) {
			r.set("name", self.name);// CPM+ : fields untouched: the exact name ('AFK ' keeps its space and its 'g:AFK ' link)
			return true;
		}
		String game = str(r, F_GAME).trim(), part = str(r, F_PART).trim();
		String full = compose(game, part);
		if(self != null && full.equals(self.name)) {
			r.set("name", full);
			return true;// name unchanged (e.g. an imported 'Dance4#p1'): never block saving the other settings
		}
		String err = checkGameName(game);
		if(err == null)err = checkPartLabel(part);
		if(err == null) {
			for (Animation o : anims()) {
				if(o != self && full.equals(o.name)) {
					err = I18n.format(L + "err.taken", full);
					break;
				}
			}
		}
		if(err != null) {
			PlusDialogs.block(err);
			return false;
		}
		r.set("name", full);
		return true;
	}

	/** wraps onOpen / onFormChange (after the dialog's own) and onConfirm (before: false keeps the dialog open) */
	private static native void wrapDialog(Dialog.DialogProperties d, DlgHook open, DlgHook change, DlgCheck confirm) /*-{
		var dlgOf = function(t) {
			if (t && typeof t.setFormValues === 'function' && typeof t.getFormResult === 'function') return t;
			var D = $wnd.Dialog;
			return D ? (D.open || (D.stack && D.stack.length ? D.stack[D.stack.length - 1] : null)) : null;
		};
		var o = d.onOpen, c = d.onFormChange, f = d.onConfirm;
		d.onOpen = function() {
			var r = typeof o === 'function' ? o.apply(this, arguments) : undefined;
			try { open(dlgOf(this), null); } catch (e) { console.error('CPM+: gesture parts (open)', e); }
			return r;
		};
		d.onFormChange = function(res) {
			var r = typeof c === 'function' ? c.apply(this, arguments) : undefined;
			try { change(dlgOf(this), res); } catch (e) { console.error('CPM+: gesture parts (change)', e); }
			return r;
		};
		d.onConfirm = function(res) {
			var ok = true;
			try { ok = confirm(dlgOf(this), res); } catch (e) { console.error('CPM+: gesture parts (confirm)', e); ok = true; }
			if (ok === false) return false;
			return typeof f === 'function' ? f.apply(this, arguments) : true;
		};
	}-*/;

	/** grouped: game + part + tip shown, name hidden; otherwise the plain name (scoped to this dialog) */
	private static native void showFields(Object dlg, boolean grouped) /*-{
		var root = dlg && dlg.object;
		if (!root || typeof root.querySelector !== 'function') return;
		var show = function(k, v) { var el = root.querySelector('.form_bar_' + k); if (el) el.style.display = v ? '' : 'none'; };
		show('name', !grouped);
		show('cpmplus_gp_game', grouped);
		show('cpmplus_gp_part', grouped);
		show('cpmplus_gp_hint', grouped);
	}-*/;

	// ------------------------------------------------------------------ join / split

	/** 'Juntar no mesmo gesto...': checklist of gestures / layers, the name in game, one undo */
	public static void openJoin(Animation clicked) {
		openJoin(clicked != null ? Collections.singletonList(clicked) : Collections.emptyList(), null);
	}

	static void openJoin(List<Animation> pre, String gameSuggestion) {
		Map<String, String> opts = new LinkedHashMap<>();
		Map<String, Animation> byUuid = new HashMap<>();
		Set<String> preSel = new LinkedHashSet<>();
		for (Animation a : anims()) {
			AnimationType t = customType(a.type);
			if(!isGroupedType(a.type))continue;
			opts.put(a.uuid, a.name + " (" + typeLabel(t) + ")");
			byUuid.put(a.uuid, a);
		}
		if(opts.size() < 2) {
			PlusDialogs.block(I18n.get(L + "join.needTwo"));
			return;
		}
		for (Animation p : pre) {
			if(p == null || !byUuid.containsKey(p.uuid))continue;
			preSel.add(p.uuid);
			for (Animation o : partsOf(gameId(p.name), p.type))preSel.add(o.uuid);
		}
		String game = gameSuggestion;
		if(game == null) {
			Map<String, Integer> count = new LinkedHashMap<>();
			for (String u : preSel)count.merge(gameId(byUuid.get(u).name), 1, Integer::sum);
			int best = 0;
			for (Map.Entry<String, Integer> e : count.entrySet()) {
				if(e.getValue() > best) {
					best = e.getValue();
					game = e.getKey();
				}
			}
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("hint", null, I18n.get(L + "join.hint")));
		rows.add(FormRow.multiSelect("anims", L + "join.anims", opts, preSel));
		rows.add(FormRow.text("game", L + "join.game", game != null ? game : ""));
		PlusDialogs.form("cpmplus_gestparts_join", L + "join.title", rows, r -> {
			List<Animation> sel = new ArrayList<>();
			for (String u : r.getList("anims")) {
				Animation a = byUuid.get(u);
				if(a != null)sel.add(a);
			}
			String err = join(sel, r.getString("game"));
			if(err != null) {
				r.keepOpen();
				PlusDialogs.block(err);
			}
		});
	}

	/**
	 * Renames the animations to game#part in ONE undo. Part labels come from the current names (kept when the
	 * animation already has one), never end in a digit and are unique. Nothing is deleted.
	 * @return null when done, else the reason nothing was changed
	 */
	public static String join(List<Animation> sel, String gameIn) {
		if(sel == null || sel.size() < 2)return I18n.get(L + "join.needTwo");
		String game = gameIn == null ? "" : gameIn.trim();
		String ge = checkGameName(game);
		if(ge != null)return ge;
		AnimationType t0 = customType(sel.get(0).type);
		for (Animation a : sel) {
			if(!isGroupedType(a.type))return I18n.format(L + "join.notGrouped", a.name);
			if(customType(a.type) != t0)return I18n.get(L + "join.typesDiffer");
		}
		// another type already uses this name in game: that would be one button for two types
		for (Animation o : anims()) {
			if(sel.contains(o))continue;
			AnimationType ot = customType(o.type);
			if(ot != null && !ot.isStaged() && ot != t0 && gameId(o.name).equals(game))return I18n.format(L + "join.otherType", game, o.name);
		}
		// a start / end animation ('g:Old') of a button that disappears would lose its link
		for (Animation a : sel) {
			String old = gameId(a.name);
			if(old.equals(game))continue;
			boolean othersKeep = false;
			for (Animation o : partsOf(old, a.type))if(!sel.contains(o))othersKeep = true;
			if(!othersKeep) {
				List<Animation> st = stagedLinks(old);
				if(!st.isEmpty())return I18n.format(L + "join.staged", a.name, st.get(0).name);
			}
		}
		Set<String> taken = new LinkedHashSet<>();
		for (Animation a : anims())if(!sel.contains(a))taken.add(a.name);
		Map<Animation, String> newNames = new LinkedHashMap<>();
		Set<String> labels = new LinkedHashSet<>();
		int idx = 0;
		for (Animation a : sel) {
			String lbl = proposeLabel(a.name, game, idx++);
			String base = lbl;
			int n = 1;
			while(labels.contains(lbl) || taken.contains(compose(game, lbl)))lbl = base + "_" + letters(n++);
			labels.add(lbl);
			newNames.put(a, compose(game, lbl));
		}
		boolean any = false;
		for (Map.Entry<Animation, String> e : newNames.entrySet())if(!e.getValue().equals(e.getKey().name))any = true;
		if(!any)return I18n.format(L + "join.nothing", game);
		PlusUndo.editAnimations(sel, I18n.get(L + "join.undo"), () -> newNames.forEach((a, n) -> a.name = n));
		scheduleSync();
		PlusDialogs.done(I18n.format(L + "join.done", String.valueOf(sel.size()), game));
		return null;
	}

	/** part label for an animation joining 'game': its own part, else what its name adds to the game name */
	static String proposeLabel(String name, String game, int index) {
		String p = partLabel(name);
		String s = !p.isEmpty() ? p : name;
		if(p.isEmpty() && s.toLowerCase(Locale.ROOT).startsWith(game.toLowerCase(Locale.ROOT)))s = s.substring(game.length());
		s = s.replace('#', '_').trim();
		while(!s.isEmpty() && "_- .".indexOf(s.charAt(0)) >= 0)s = s.substring(1);
		while(!s.isEmpty() && (Character.isDigit(s.charAt(s.length() - 1)) || "_- .".indexOf(s.charAt(s.length() - 1)) >= 0))s = s.substring(0, s.length() - 1);
		if(s.isEmpty())s = "parte_" + letters(index);
		return s;
	}

	/** 0 -&gt; a, 1 -&gt; b ... 25 -&gt; z, 26 -&gt; aa */
	static String letters(int i) {
		StringBuilder sb = new StringBuilder();
		int n = i;
		do {
			sb.insert(0, (char) ('a' + n % 26));
			n = n / 26 - 1;
		} while(n >= 0);
		return sb.toString();
	}

	/** 'Separar deste gesto': asks a new unique name in game for one part */
	public static void openSplit(Animation a) {
		if(a == null || !isGroupedType(a.type)) {
			PlusDialogs.block(I18n.get(L + "split.notPart"));
			return;
		}
		String sug = compose(gameId(a.name), partLabel(a.name)).replace('#', '_');
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("hint", null, I18n.format(L + "split.hint", a.name)));
		rows.add(FormRow.text("game", L + "split.newName", sug));
		PlusDialogs.form("cpmplus_gestparts_split", L + "split.title", rows, r -> {
			String err = split(a, r.getString("game"));
			if(err != null) {
				r.keepOpen();
				PlusDialogs.block(err);
			}
		});
	}

	/** gives one animation its own name in game (one undo); a 'g:' start / end only it used follows it */
	public static String split(Animation a, String newGameIn) {
		if(a == null || !isGroupedType(a.type))return I18n.get(L + "split.notPart");
		String g = newGameIn == null ? "" : newGameIn.trim();
		String ge = checkGameName(g);
		if(ge != null)return ge;
		for (Animation o : anims()) {
			if(o == a)continue;
			if(gameId(o.name).equals(g) || g.equals(o.name) || g.equals(o.group))return I18n.format(L + "split.taken", g);
		}
		String old = gameId(a.name);
		boolean othersKeep = false;
		for (Animation o : partsOf(old, a.type))if(o != a)othersKeep = true;
		List<Animation> touched = new ArrayList<>();
		touched.add(a);
		List<Animation> staged = othersKeep ? Collections.emptyList() : stagedLinks(old);
		touched.addAll(staged);
		PlusUndo.editAnimations(touched, I18n.get(L + "split.undo"), () -> {
			a.name = g;
			for (Animation s : staged) {
				String[] sp = s.name.split(":", 2);
				s.name = sp[0] + ":" + g;
			}
		});
		scheduleSync();
		PlusDialogs.done(I18n.format(L + "split.done", g));
		return null;
	}

	// ------------------------------------------------------------------ export checks

	private static void check(ExportHooks.ExportContext ctx) {
		if(!CPMCodec.isCPM())return;
		List<Animation> list = anims();
		checkTypes(ctx, list);
		checkButtons(ctx, list);
		checkRenamed(ctx, list);
	}

	/** a) one name in game used by several types (or by a layer group): ONE button for different things */
	private static void checkTypes(ExportHooks.ExportContext ctx, List<Animation> list) {
		Map<String, Map<String, Animation>> byId = new LinkedHashMap<>();
		for (Animation a : list) {
			AnimationType t = customType(a.type);
			if(t == null || t.isStaged())continue;
			byId.computeIfAbsent(gameId(a.name), k -> new LinkedHashMap<>()).putIfAbsent(typeLabel(t), a);
		}
		// layer groups are buttons too (a dropdown named after the group)
		for (Animation a : list) {
			if(customType(a.type) != AnimationType.LAYER || a.group == null || a.group.isEmpty())continue;
			Map<String, Animation> m = byId.get(a.group);
			if(m == null)continue;
			boolean other = false;
			for (Animation o : list) {
				AnimationType ot = customType(o.type);
				if(ot == null || ot.isStaged() || !gameId(o.name).equals(a.group))continue;
				if(!(ot == AnimationType.LAYER && a.group.equals(o.group)))other = true;
			}
			if(other)m.putIfAbsent(I18n.get(L + "type.group"), a);
		}
		for (Map.Entry<String, Map<String, Animation>> e : byId.entrySet()) {
			if(e.getValue().size() < 2)continue;
			StringBuilder types = new StringBuilder();
			Animation last = null;
			for (Map.Entry<String, Animation> t : e.getValue().entrySet()) {
				if(types.length() > 0)types.append(", ");
				types.append(t.getKey());
				last = t.getValue();
			}
			WarnEntry w = WarnFactory.of(Severity.BREAKS, KEY_TYPES, L + "warn.types", e.getKey(), types.toString());
			w.setKey(KEY_TYPES, e.getKey(), last);
			w.setConsequence(L + "warn.types.ifIgnored");
			w.setFixKind(FixKind.ASKS);
			for (Animation a : e.getValue().values())w.addNode(a.uuid, a.name);
			ctx.add(w);
		}
	}

	/** b) parts of one button with different button settings (the game keeps ONE value) */
	private static void checkButtons(ExportHooks.ExportContext ctx, List<Animation> list) {
		Map<String, List<Animation>> groups = new LinkedHashMap<>();
		for (Animation a : list) {
			if(!isGroupedType(a.type))continue;
			groups.computeIfAbsent(customType(a.type).name() + "|" + gameId(a.name), k -> new ArrayList<>()).add(a);
		}
		for (List<Animation> parts : groups.values()) {
			if(parts.size() < 2)continue;
			List<String> diff = differingSettings(parts);
			if(diff.isEmpty())continue;
			Animation first = parts.get(0);
			WarnEntry w = WarnFactory.of(Severity.DIFFERS, KEY_BUTTON, L + "warn.button", gameId(first.name), String.join(", ", diff));
			w.setKey(KEY_BUTTON, gameId(first.name), first);
			w.setConsequence(L + "warn.button.ifIgnored");
			w.setFixKind(FixKind.DETERMINISTIC);
			for (Animation a : parts)w.addNode(a.uuid, a.name);
			ctx.add(w);
		}
	}

	/** labels of the button-level settings that differ between the parts (AnimationExporter takes them from one part) */
	static List<String> differingSettings(List<Animation> parts) {
		Set<String> out = new LinkedHashSet<>();
		Animation f = parts.get(0);
		AnimationType t = customType(f.type);
		for (Animation p : parts) {
			if(p == f)continue;
			if(p.commandCtrl != f.commandCtrl)out.add(L + "set.command");
			if(p.isAnimHidden() != f.isAnimHidden())out.add(L + "set.hidden");
			if(p.getOrder() != f.getOrder())out.add(L + "set.order");
			if(p.isProperty != f.isProperty)out.add(L + "set.property");
			if(t == AnimationType.GESTURE) {
				if(!String.valueOf(p.loop).equals(String.valueOf(f.loop)))out.add(L + "set.loop");
				if(p.layerCtrl != f.layerCtrl)out.add(L + "set.layerCtrl");
			} else if(t == AnimationType.LAYER) {
				if((p.getLayerDefault() > 0.5f) != (f.getLayerDefault() > 0.5f))out.add(L + "set.default");
				if(!nz(p.group).equals(nz(f.group)))out.add(L + "set.group");
			} else if(t == AnimationType.VALUE_LAYER) {
				if(Math.abs(p.getLayerDefault() - f.getLayerDefault()) > 1e-4f)out.add(L + "set.default");
				if(p.getMaxValue() != f.getMaxValue())out.add(L + "set.max");
				if(p.isInterpolateVal() != f.isInterpolateVal())out.add(L + "set.interp");
			}
		}
		List<String> l = new ArrayList<>();
		for (String k : out)l.add(I18n.get(k));
		return l;
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	/** every part of first's button gets first's button settings (one undo) */
	static boolean makeButtonSettingsEqual(Animation first) {
		List<Animation> parts = partsOf(gameId(first.name), first.type);
		if(parts.size() < 2 || differingSettings(parts).isEmpty())return false;
		Animation f = parts.get(0);
		AnimationType t = customType(f.type);
		PlusUndo.editAnimations(parts, I18n.get(L + "fix.same.undo"), () -> {
			for (Animation p : parts) {
				if(p == f)continue;
				p.commandCtrl = f.commandCtrl;
				p.setAnimHidden(f.isAnimHidden());
				p.setOrder(f.getOrder());
				p.isProperty = f.isProperty;
				if(t == AnimationType.GESTURE) {
					p.loop = f.loop;
					p.layerCtrl = f.layerCtrl;
				} else if(t == AnimationType.LAYER) {
					p.setLayerDefault(f.getLayerDefault() > 0.5f ? 1 : 0);
					p.group = f.group;
				} else if(t == AnimationType.VALUE_LAYER) {
					p.setLayerDefault(f.getLayerDefault());
					p.setMaxValue(f.getMaxValue());
					p.setInterpolateVal(f.isInterpolateVal());
				}
			}
		});
		PlusDialogs.done(I18n.format(L + "fix.same.done", gameId(f.name), f.name));
		return true;
	}

	/**
	 * c) 'Dance2' next to 'Dance4' (same text, another number at the end): did Blockbench renumber a copy? Blockbench
	 * strips the trailing digits of a duplicate name and appends the first free number from 2, so only gesture groups
	 * that contain 'stem2' are suggested (numbered layers / toggles such as 'Hat1', 'Hat3' are left alone).
	 */
	private static void checkRenamed(ExportHooks.ExportContext ctx, List<Animation> list) {
		Set<String> ignored = ignoredStems();
		Map<String, List<Animation>> byStem = new LinkedHashMap<>();
		Map<String, String> stems = new HashMap<>();
		for (Animation a : list) {
			if(customType(a.type) != AnimationType.GESTURE || a.name.indexOf('#') >= 0)continue;
			String stem = a.name.replaceAll("\\d+$", "");
			if(stem.trim().isEmpty())continue;
			String key = AnimationType.GESTURE.name() + "|" + stem;
			stems.put(key, stem);
			byStem.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
		}
		for (Map.Entry<String, List<Animation>> e : byStem.entrySet()) {
			List<Animation> l = e.getValue();
			if(l.size() < 2 || ignored.contains(e.getKey()))continue;
			String two = stems.get(e.getKey()) + "2";
			boolean renumbered = false;
			for (Animation a : l)if(a.name.equals(two))renumbered = true;
			if(!renumbered)continue;
			StringBuilder names = new StringBuilder();
			for (Animation a : l) {
				if(names.length() > 0)names.append(", ");
				names.append(a.name);
			}
			WarnEntry w = WarnFactory.of(Severity.INFO, KEY_MAYBE, L + "warn.maybe", names.toString());
			w.setKey(KEY_MAYBE, e.getKey(), l.get(0), l);
			w.setFixKind(FixKind.ASKS);
			for (Animation a : l)w.addNode(a.uuid, a.name);
			ctx.add(w);
		}
	}

	@SuppressWarnings("unchecked")
	static Set<String> ignoredStems() {
		Set<String> s = new LinkedHashSet<>();
		try {
			Object v = Project.getData().getExtra(EXTRA_IGNORE);
			if(v instanceof List)for (Object o : (List<Object>) v)s.add(String.valueOf(o));
		} catch (Throwable e) {
			// no project data: nothing ignored
		}
		return s;
	}

	static void ignoreStem(String key) {
		ProjectDataUndo.Snapshot before = ProjectDataUndo.snapshot();
		ProjectData pd = Project.getData();
		List<Object> l = new ArrayList<>(ignoredStems());
		if(!l.contains(key))l.add(key);
		pd.putExtra(EXTRA_IGNORE, l);
		pd.flush();
		Project.saved = false;
		String stem = key.indexOf('|') >= 0 ? key.substring(key.indexOf('|') + 1) : key;
		ProjectDataUndo.offerUndo(before, I18n.format(L + "ignored", stem));
	}

	// ------------------------------------------------------------------ import

	/** legacy files: several gestures / layers with the SAME name and type -&gt; 'Name#parte_a', 'Name#parte_b' ... */
	static void renameLegacyDuplicates(Editor editor) {
		if(editor == null || editor.animations == null)return;
		Map<String, List<EditorAnim>> groups = new LinkedHashMap<>();
		for (EditorAnim ea : editor.animations) {
			if(ea.type == null || ea.displayName == null || ea.pose != null)continue;
			if(ea.type != AnimationType.GESTURE && ea.type != AnimationType.LAYER && ea.type != AnimationType.VALUE_LAYER)continue;
			if(ea.displayName.indexOf('#') >= 0)continue;
			groups.computeIfAbsent(ea.type.name() + "|" + ea.displayName, k -> new ArrayList<>()).add(ea);
		}
		Set<String> names = new LinkedHashSet<>();
		for (EditorAnim ea : editor.animations)if(ea.displayName != null)names.add(ea.displayName);
		List<String> renamed = new ArrayList<>();
		for (List<EditorAnim> g : groups.values()) {
			if(g.size() < 2)continue;
			int i = 0;
			for (EditorAnim ea : g) {
				String n;
				do {
					n = ea.displayName + "#parte_" + letters(i++);
				} while(names.contains(n));
				names.add(n);
				renamed.add(n);
				ea.displayName = n;
			}
		}
		PlusTestHooks.setLast("gestparts.importRenamed", renamed);
		if(!renamed.isEmpty())PlusDialogs.toast(I18n.format(L + "importNote", String.valueOf(renamed.size()), String.join(", ", renamed)), "call_split", 20000, null);
	}

	// ------------------------------------------------------------------ badges in the Animations panel

	/** re-draws the badges soon (once per burst of changes) */
	static void scheduleSync() {
		if(syncQueued)return;
		syncQueued = true;
		DomGlobal.setTimeout(a -> {
			syncQueued = false;
			syncBadges();
		}, 30);
	}

	/** badge text per animation uuid, then updates the panel (only what changed, so the observer settles) */
	static void syncBadges() {
		try {
			if(observer == null)observer = observe(PlusGestureParts::scheduleSync);
			JsPropertyMap<Object> want = JsPropertyMap.of();
			if(CPMCodec.isCPM()) {
				for (Animation a : anims()) {
					String b = badge(a);
					if(b != null && a.uuid != null)want.set(a.uuid, b);
				}
			}
			applyBadges(want, I18n.get(L + "badge.tip"), BADGE_CLASS);
		} catch (Throwable e) {
			DomGlobal.console.warn("CPM+: gesture part badges not updated", e);
		}
	}

	private static native Object observe(Runnable onChange) /*-{
		var I = $wnd.Interface, P = I && I.Panels && I.Panels.animations, node = P && P.node;
		if (!node || typeof $wnd.MutationObserver !== 'function') return null;
		var obs = new $wnd.MutationObserver(function() { onChange.@java.lang.Runnable::run()(); });
		obs.observe(node, {childList: true, subtree: true, characterData: true});
		return obs;
	}-*/;

	private static native void disconnect(Object obs, String cls) /*-{
		try { if (obs) obs.disconnect(); } catch (e) {}
		try {
			var list = $wnd.document.querySelectorAll('.' + cls);
			for (var i = 0; i < list.length; i++) list[i].remove();
		} catch (e) {}
	}-*/;

	private static native void applyBadges(JsPropertyMap<Object> want, String tip, String cls) /*-{
		var I = $wnd.Interface, P = I && I.Panels && I.Panels.animations, node = P && P.node;
		if (!node) return;
		var lis = node.querySelectorAll('li[anim_id]');
		for (var i = 0; i < lis.length; i++) {
			var li = lis[i], id = li.getAttribute('anim_id'), t = want[id], sp = null;
			for (var c = 0; c < li.children.length; c++) if (li.children[c].classList && li.children[c].classList.contains(cls)) { sp = li.children[c]; break; }
			if (t) {
				if (!sp) {
					sp = $wnd.document.createElement('span');
					sp.className = cls;
					sp.style.cssText = 'font-size:0.8em;opacity:0.8;margin:0 4px;padding:0 5px;border-radius:4px;white-space:nowrap;background:var(--color-back);color:var(--color-subtle_text);';
					var lab = null;
					for (var k = 0; k < li.children.length; k++) if (li.children[k].tagName === 'LABEL') { lab = li.children[k]; break; }
					if (lab && lab.nextSibling) li.insertBefore(sp, lab.nextSibling); else li.appendChild(sp);
				}
				if (sp.textContent !== t) sp.textContent = t;
				if (sp.title !== tip) sp.title = tip;
			} else if (sp) {
				sp.remove();
			}
		}
	}-*/;

	/** test hook: the badges the panel shows now (animation name -&gt; text) */
	private static native JsPropertyMap<Object> readBadges(String cls) /*-{
		var out = {};
		var I = $wnd.Interface, P = I && I.Panels && I.Panels.animations, node = P && P.node;
		if (!node) return out;
		var lis = node.querySelectorAll('li[anim_id]');
		var all = $wnd.Animation && $wnd.Animation.all || [];
		for (var i = 0; i < lis.length; i++) {
			var id = lis[i].getAttribute('anim_id'), sp = lis[i].querySelector('.' + cls);
			var a = null;
			for (var j = 0; j < all.length; j++) if (all[j].uuid === id) { a = all[j]; break; }
			if (a && sp) out[a.name] = sp.textContent;
		}
		return out;
	}-*/;

	// ------------------------------------------------------------------ install

	private static Animation asAnim(Object ctx) {
		if(ctx != null && Animation.all != null) {
			for (Animation a : Animation.all)if(a == ctx)return a;
		}
		return Animation.selected;
	}

	private static void safe(String nameKey, Runnable r) {
		try {
			r.run();
		} catch (Throwable e) {
			PopupDialogs.displayError(I18n.format("cpmplus.w0binfra.error.actionFailed", PlusDialogs.esc(I18n.get(nameKey))), e);
		}
	}

	/** the menu entry only shows when there is something to join (2+ gestures / layers / value layers) */
	private static boolean canJoinAny() {
		int n = 0;
		for (Animation a : anims())if(isGroupedType(a.type) && ++n >= 2)return true;
		return false;
	}

	private static boolean canSplit(Animation a) {
		return a != null && isGroupedType(a.type) && (a.name.indexOf('#') >= 0 || partsOf(gameId(a.name), a.type).size() > 1);
	}

	/** context menu entry of the Animations panel (the click gets the right-clicked animation) */
	private static void contextItem(String nameKey, String icon, Action.ConditionMethod when, java.util.function.Consumer<Animation> click) {
		Action.ActionProperties p = new Action.ActionProperties();
		p.name = I18n.get(nameKey);
		p.icon = icon;
		p.category = "animation";
		Action.Condition c = CPMCodec.formatCPM();
		c.method = ctx -> {
			try {
				return CPMCodec.isCPM() && when.check(ctx);
			} catch (Throwable e) {
				return false;
			}
		};
		p.condition = c;
		p.click = e -> safe(nameKey, () -> click.accept(asAnim(e)));
		Animation.menu.structure.push(p);
		PluginStart.cleanup.add(() -> Animation.menu.structure.remove(p));
	}

	/** registers the dialog-independent parts of G54 (menus, export checks + fixes, import renaming, badges, test hooks) */
	public static void install() {
		ExportHooks.register(PlusGestureParts::check);
		WarnFixes.register(KEY_TYPES, L + "fix.rename", 10, w -> {
			Object[] args = w.getArgs();
			if(args.length < 2 || !(args[1] instanceof Animation))return null;
			Animation a = (Animation) args[1];
			return () -> new Promise<>((res, rej) -> AnimationWizard.open(a, res::onInvoke));
		});
		WarnFixes.register(KEY_BUTTON, L + "fix.same", 10, w -> {
			Object[] args = w.getArgs();
			if(args.length < 2 || !(args[1] instanceof Animation))return null;
			Animation first = (Animation) args[1];
			return () -> Promise.resolve(makeButtonSettingsEqual(first));
		});
		WarnFixes.register(KEY_MAYBE, L + "fix.join", 10, w -> {
			Object[] args = w.getArgs();
			if(args.length < 3 || !(args[1] instanceof Animation) || !(args[2] instanceof List))return null;
			@SuppressWarnings("unchecked")
			List<Animation> l = (List<Animation>) args[2];
			Animation first = (Animation) args[1];
			return () -> {
				openJoin(new ArrayList<>(l), gameId(first.name));
				return Promise.resolve(false);
			};
		});
		WarnFixes.register(KEY_MAYBE, L + "fix.ignore", 20, w -> {
			Object[] args = w.getArgs();
			if(args.length < 1)return null;
			String key = String.valueOf(args[0]);
			return () -> {
				ignoreStem(key);
				return Promise.resolve(true);
			};
		});
		ImportHooks.beforeAnims(PlusGestureParts::renameLegacyDuplicates);

		// menus: right click on an animation + CPM+ > Animacao
		contextItem(L + "menu.join", "call_merge", ctx -> {
			Animation a = asAnim(ctx);
			return a != null && isGroupedType(a.type);
		}, PlusGestureParts::openJoin);
		contextItem(L + "menu.split", "call_split", ctx -> canSplit(asAnim(ctx)), PlusGestureParts::openSplit);
		PlusMenus.add(PlusMenus.SUB_ANIM, PlusMenus.action("cpmplus_gestparts_join", L + "menu.join", "call_merge", Cond.CPM_ONLY,
				PlusGestureParts::canJoinAny, () -> openJoin(Animation.selected)));
		PlusMenus.add(PlusMenus.SUB_ANIM, PlusMenus.action("cpmplus_gestparts_split", L + "menu.split", "call_split", Cond.CPM_ONLY,
				() -> canSplit(Animation.selected), () -> openSplit(Animation.selected)));

		// badges: panel changes (names, list) + edits that change parts without touching the list
		for (String ev : new String[] {"finish_edit", "undo", "redo", "select_project", "load_project", "add_animation"}) {
			PluginStart.addEventListener(ev, d -> scheduleSync());
		}
		PluginStart.cleanup.add(PlusGestureParts::uninstall);
		scheduleSync();

		PlusTestHooks.register("gestparts.join", o -> {
			String err = join(byNames(o.get("anims")), String.valueOf(o.get("game")));
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("ok", err == null);
			r.set("error", err);
			return r;
		});
		PlusTestHooks.register("gestparts.split", o -> {
			List<Animation> l = byNames(o.get("anim"));
			String err = l.isEmpty() ? "no animation" : split(l.get(0), String.valueOf(o.get("game")));
			JsPropertyMap<Object> r = JsPropertyMap.of();
			r.set("ok", err == null);
			r.set("error", err);
			return r;
		});
		PlusTestHooks.register("gestparts.badges", o -> {
			syncBadges();
			return readBadges(BADGE_CLASS);
		});
		PlusTestHooks.register("gestparts.openJoin", o -> {
			List<Animation> l = byNames(o.get("anim"));
			openJoin(l.isEmpty() ? null : l.get(0));
			return true;
		});
	}

	public static void uninstall() {
		Object o = observer;
		observer = null;
		disconnect(o, BADGE_CLASS);
	}

	/** test hooks: animations by name or uuid (a string or an array of strings) */
	static List<Animation> byNames(Object v) {
		List<String> names = new ArrayList<>();
		if(v != null && JsArray.isArray(v)) {
			JsArray<Object> a = Js.uncheckedCast(v);
			for (int i = 0; i < a.length; i++)names.add(String.valueOf(a.getAt(i)));
		} else if(v != null)names.add(String.valueOf(v));
		List<Animation> out = new ArrayList<>();
		for (String n : names) {
			for (Animation a : anims()) {
				if(n.equals(a.name) || n.equals(a.uuid)) {
					out.add(a);
					break;
				}
			}
		}
		return out;
	}
}
