package com.tom.cpm.blockbench.tools.anim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.tom.cpm.blockbench.proxy.Animation;
import com.tom.cpm.blockbench.proxy.BoneAnimator;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.Keyframe;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusDialogs.FormOpts;
import com.tom.cpm.blockbench.util.PlusDialogs.FormRow;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (W6-ANIMX, G41 A/B): 'Quem mexe cada parte?' - a read-only X-ray of one animation per vanilla body part, and
 * the per-part buttons 'Travar aqui (parar o balanço)' and 'Devolver pro Minecraft'.
 * <p>
 * What the game does (RootModelElement: posAdd &amp;= add / rotAdd &amp;= add; the export only calls setPos / setRot
 * when that channel has keys): a rotation or position key on a ROOT in a non-additive animation replaces the vanilla
 * pose of that channel (on the head: the look direction too); additive keys add to it; scale keys on a root are
 * dropped (rootScaling); keys on groups inside a root never change the root's flags. So rotation and position are read
 * separately and 'hasTransform' is never used.
 */
public final class AnimXray {

	public enum Badge {
		/** no rotation / position key on the root: Minecraft's swing / look stays */
		MC_MOVES,
		/** non-additive rotation and/or position keys: the animation replaces Minecraft's movement */
		REPLACES,
		/** additive keys on the root: they add to Minecraft's movement */
		ADDS,
		/** cpm_dva: no vanilla animation at all, in every animation */
		NO_VANILLA,
		/** scale keys on the root: the game ignores them */
		ROOT_SCALE
	}

	public static final class Row {
		public final Group root;
		public boolean rot, pos, scl, additive, dva, childKeys;
		/**
		 * CPM's rule (AnimFrame.hasPosChanges compares the non-additive root frame, default position + offset, with the
		 * offset only): rotation keys on an arm / leg in a non-additive animation also fix its POSITION at the standing
		 * one, so while sneaking / swimming the limb sits ~3 px off. Same in CPM's own editor.
		 */
		public boolean posLocked;
		public final List<Badge> badges = new ArrayList<>();

		Row(Group root) {
			this.root = root;
		}

		public boolean replaces() {
			return badges.contains(Badge.REPLACES);
		}

		/** the head stops following the mouse in this animation */
		public boolean breaksLook() {
			return AnimxUtil.isHead(root) && (replaces() || dva);
		}

		/** only parts inside the root are animated in a non-additive animation: the limb keeps swinging */
		public boolean childOnlyNote() {
			return childKeys && !rot && !pos && !additive && !dva;
		}

		public String replaceChannelsKey() {
			if (rot && pos)return "cpmplus.w6animx.xray.ch.both";
			return rot ? "cpmplus.w6animx.xray.ch.rotOnly" : "cpmplus.w6animx.xray.ch.posOnly";
		}
	}

	private AnimXray() {
	}

	// ------------------------------------------------------------------ analysis (read only)

	public static List<Row> analyse(Animation a) {
		List<Row> out = new ArrayList<>();
		if (a == null)return out;
		for (Group g : AnimxUtil.vanillaRoots())out.add(analyse(a, g));
		return out;
	}

	public static Row analyse(Animation a, Group g) {
		Row r = new Row(g);
		BoneAnimator ba = AnimxUtil.animatorOf(a, g);
		r.rot = AnimxUtil.hasKeys(ba, AnimxUtil.ROT);
		r.pos = AnimxUtil.hasKeys(ba, AnimxUtil.POS);
		r.scl = AnimxUtil.hasKeys(ba, AnimxUtil.SCL);
		r.additive = a.additive;
		r.dva = g.disableVanillaAnim;
		r.posLocked = !a.additive && r.rot && !r.pos && isLimb(g);
		for (Group d : AnimxUtil.descendants(g)) {
			BoneAnimator cb = AnimxUtil.animatorOf(a, d);
			if (cb == null)continue;
			for (String ch : AnimxUtil.ALL) {
				if (AnimxUtil.hasKeys(cb, ch)) {
					r.childKeys = true;
					break;
				}
			}
			if (r.childKeys)break;
		}
		if (r.dva)r.badges.add(Badge.NO_VANILLA);
		if (r.rot || r.pos)r.badges.add(r.additive ? Badge.ADDS : Badge.REPLACES);
		else if (!r.dva)r.badges.add(Badge.MC_MOVES);
		if (r.scl)r.badges.add(Badge.ROOT_SCALE);
		return r;
	}

	/** arms and legs: their vanilla position is not 0 (it changes while sneaking / swimming) */
	static boolean isLimb(Group g) {
		Object p = g.getRootType();
		if (!(p instanceof com.tom.cpm.shared.model.PlayerModelParts))return false;
		com.tom.cpm.shared.model.PlayerModelParts pp = (com.tom.cpm.shared.model.PlayerModelParts) p;
		return pp == com.tom.cpm.shared.model.PlayerModelParts.LEFT_ARM || pp == com.tom.cpm.shared.model.PlayerModelParts.RIGHT_ARM
				|| pp == com.tom.cpm.shared.model.PlayerModelParts.LEFT_LEG || pp == com.tom.cpm.shared.model.PlayerModelParts.RIGHT_LEG;
	}

	public static String badgeText(Row r, Badge b) {
		switch (b) {
		case MC_MOVES:
			return PlusDialogs.tr("cpmplus.w6animx.xray.badge.mc");
		case REPLACES:
			return PlusDialogs.trf("cpmplus.w6animx.xray.badge.replace", PlusDialogs.tr(r.replaceChannelsKey()));
		case ADDS:
			return PlusDialogs.tr("cpmplus.w6animx.xray.badge.add");
		case NO_VANILLA:
			return PlusDialogs.tr("cpmplus.w6animx.xray.badge.dva");
		default:
			return PlusDialogs.tr("cpmplus.w6animx.xray.badge.rootScale");
		}
	}

	private static String badgeColor(Badge b) {
		switch (b) {
		case MC_MOVES:
			return "#8a8a8a";
		case REPLACES:
			return "#2f7fd8";
		case ADDS:
			return "#2e9e4f";
		case NO_VANILLA:
			return "#8e44ad";
		default:
			return "#c9a100";
		}
	}

	public static String partName(Group g) {
		String k = AnimxUtil.partKey(g);
		String t = k != null ? PlusDialogs.tr(k) : g.name;
		return t.equals(k) ? g.name : t;
	}

	public static JsPropertyMap<Object> toJson(Animation a, List<Row> rows) {
		JsPropertyMap<Object> o = AnimxUtil.map();
		o.set("animation", a != null ? a.name : null);
		JsArray<Object> arr = new JsArray<>();
		for (Row r : rows) {
			JsPropertyMap<Object> m = AnimxUtil.map();
			m.set("root", r.root.name);
			m.set("rot", r.rot);
			m.set("pos", r.pos);
			m.set("scale", r.scl);
			m.set("additive", r.additive);
			m.set("dva", r.dva);
			JsArray<Object> b = new JsArray<>();
			JsArray<Object> t = new JsArray<>();
			for (Badge x : r.badges) {
				b.push(x.name());
				t.push(badgeText(r, x));
			}
			m.set("badges", b);
			m.set("texts", t);
			m.set("breaksLook", r.breaksLook());
			m.set("childOnlyNote", r.childOnlyNote());
			m.set("posLocked", r.posLocked);
			if (r.replaces())m.set("channels", PlusDialogs.tr(r.replaceChannelsKey()));
			arr.push(m);
		}
		o.set("rows", arr);
		return o;
	}

	// ------------------------------------------------------------------ dialog

	public static void open(Animation a) {
		if (a == null) {
			PlusDialogs.block("cpmplus.w6animx.err.noAnimation");
			return;
		}
		List<Row> rows = analyse(a);
		if (rows.isEmpty()) {
			PlusDialogs.block("cpmplus.w6animx.xray.noRoots");
			return;
		}
		StringBuilder html = new StringBuilder();
		html.append("<p>").append(PlusDialogs.esc(PlusDialogs.trf("cpmplus.w6animx.xray.intro", a.name))).append("</p>");
		html.append("<table style='width:100%;border-collapse:collapse'>");
		for (Row r : rows) {
			html.append("<tr><td style='padding:3px 6px;vertical-align:top;white-space:nowrap'><b>").append(PlusDialogs.esc(partName(r.root))).append("</b></td><td style='padding:3px 6px'>");
			for (Badge b : r.badges) {
				html.append("<span style='display:inline-block;margin:1px 4px 1px 0;padding:1px 6px;border-radius:8px;color:#fff;background:")
				.append(badgeColor(b)).append("'>").append(PlusDialogs.esc(badgeText(r, b))).append("</span>");
			}
			if (r.breaksLook())html.append("<div>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.xray.note.head"))).append("</div>");
			if (r.childOnlyNote())html.append("<div>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.xray.note.childOnly"))).append("</div>");
			if (r.posLocked)html.append("<div>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.xray.note.posLocked"))).append("</div>");
			html.append("</td></tr>");
		}
		html.append("</table>");
		html.append(summaryHtml());
		html.append("<p style='opacity:.8'>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.xray.note.preview"))).append("</p>");

		List<FormRow> form = new ArrayList<>();
		for (Row r : rows) {
			final Group g = r.root;
			form.add(FormRow.buttons("btn_" + g.uuid.replace("-", ""), partName(g), new String[] {
					"cpmplus.w6animx.xray.btn.lock", "cpmplus.w6animx.xray.btn.return", "cpmplus.w6animx.xray.btn.subgroup"}, i -> {
						if (i == 0)lockDialog(a, g);
						else if (i == 1)returnDialog(a, g);
						else Subgroup.openDialog(g, Collections.singletonList(a), false, null);
					}));
		}
		FormOpts o = new FormOpts();
		o.htmlLines = new String[] {html.toString()};
		o.width = 640;
		PlusDialogs.form("cpmplus_w6animx_xray", "cpmplus.w6animx.xray.title", form, r -> {
		}, o);
	}

	/** one line per animation that replaces Minecraft's movement somewhere (which parts, and the look) */
	static String summaryHtml() {
		StringBuilder sb = new StringBuilder();
		List<String> lines = new ArrayList<>();
		for (Animation an : AnimxUtil.animations()) {
			List<String> parts = new ArrayList<>();
			boolean look = false;
			for (Row r : analyse(an)) {
				if (r.replaces())parts.add(partName(r.root));
				if (r.replaces() && r.breaksLook())look = true;
			}
			if (parts.isEmpty())continue;
			String l = PlusDialogs.trf("cpmplus.w6animx.xray.summary.line", an.name, String.join(", ", parts));
			if (look)l += " - " + PlusDialogs.tr("cpmplus.w6animx.xray.summary.look");
			lines.add(l);
		}
		sb.append("<details><summary>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.xray.summary.title"))).append("</summary>");
		if (lines.isEmpty())sb.append("<p>").append(PlusDialogs.esc(PlusDialogs.tr("cpmplus.w6animx.xray.summary.none"))).append("</p>");
		else {
			sb.append("<ul>");
			for (String l : lines)sb.append("<li>").append(PlusDialogs.esc(l)).append("</li>");
			sb.append("</ul>");
		}
		sb.append("</details>");
		return sb.toString();
	}

	// ------------------------------------------------------------------ Travar aqui

	/** result of {@link #lock} */
	public static final class LockResult {
		public boolean needsNonAdditive, needsConfirmHead, nothingToDo;
		public int added;
	}

	/**
	 * Adds linear [0,0,0] keys at t=0 and t=L in the chosen channels that have no keys yet (the export adds the rest
	 * pose of the part in a non-additive animation, so the part stays exactly at rest). One undo entry.
	 * @param makeNonAdditive the animation is additive: switch it to non-additive in the same undo entry (else refuse)
	 */
	public static LockResult lock(Animation a, Group root, boolean rot, boolean pos, boolean confirmHead, boolean makeNonAdditive) {
		LockResult res = new LockResult();
		if (a.additive && !makeNonAdditive) {
			res.needsNonAdditive = true;
			return res;
		}
		if (AnimxUtil.isHead(root) && !confirmHead) {
			res.needsConfirmHead = true;
			return res;
		}
		BoneAnimator ex = AnimxUtil.animatorOf(a, root);
		List<String> chs = new ArrayList<>();
		if (rot && !AnimxUtil.hasKeys(ex, AnimxUtil.ROT))chs.add(AnimxUtil.ROT);
		if (pos && !AnimxUtil.hasKeys(ex, AnimxUtil.POS))chs.add(AnimxUtil.POS);
		if (chs.isEmpty() && !(a.additive && makeNonAdditive)) {
			res.nothingToDo = true;
			return res;
		}
		List<Animation> l = Collections.singletonList(a);
		UndoData before = AnimxUtil.track(UndoData.make(a), l);
		PlusUndo.run(PlusDialogs.trf("cpmplus.w6animx.undo.lock", partName(root)), before, () -> AnimxUtil.track(UndoData.make(a), l), () -> {
			if (makeNonAdditive)a.additive = false;
			BoneAnimator ba = a.getBoneAnimator(root);
			for (String ch : chs) {
				AnimxUtil.addKey(ba, AnimxUtil.keyData(ch, 0, "linear", 0, 0, 0));
				res.added++;
				if (a.length > 1e-4) {
					AnimxUtil.addKey(ba, AnimxUtil.keyData(ch, a.length, "linear", 0, 0, 0));
					res.added++;
				}
			}
		});
		AnimxUtil.preview();
		return res;
	}

	static void lockDialog(Animation a, Group g) {
		Row r = analyse(a, g);
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("i", null, PlusDialogs.trf("cpmplus.w6animx.lock.intro", partName(g), a.name)));
		rows.add(FormRow.checkbox("rot", "cpmplus.w6animx.ch.rotation", !r.rot));
		rows.add(FormRow.checkbox("pos", "cpmplus.w6animx.ch.position", !r.pos));
		if (r.rot || r.pos)rows.add(FormRow.info("has", null, PlusDialogs.trf("cpmplus.w6animx.lock.already", PlusDialogs.tr(r.replaceChannelsKey()))));
		if (a.additive) {
			rows.add(FormRow.info("addInfo", null, PlusDialogs.tr("cpmplus.w6animx.lock.additive")));
			rows.add(FormRow.checkbox("nonAdd", "cpmplus.w6animx.lock.btn.makeReplace", false));
		}
		if (AnimxUtil.isHead(g))rows.add(FormRow.checkbox("head", "cpmplus.w6animx.lock.head", false));
		PlusDialogs.form("cpmplus_w6animx_lock", "cpmplus.w6animx.lock.title", rows, fr -> {
			LockResult res = lock(a, g, fr.getBool("rot"), fr.getBool("pos"), fr.getBool("head"), fr.getBool("nonAdd"));
			if (res.needsNonAdditive) {
				PlusDialogs.block("cpmplus.w6animx.lock.additive");
				return;
			}
			if (res.needsConfirmHead) {
				PlusDialogs.block("cpmplus.w6animx.lock.head");
				return;
			}
			if (res.nothingToDo) {
				PlusDialogs.quick(PlusDialogs.tr("cpmplus.w6animx.lock.nothing"), 3000);
				return;
			}
			PlusDialogs.done(PlusDialogs.trf("cpmplus.w6animx.lock.done", partName(g), res.added));
		});
	}

	// ------------------------------------------------------------------ 'na hora': warn once per animation / part

	private static final java.util.Set<String> warned = new java.util.HashSet<>();

	public static void resetLive() {
		warned.clear();
	}

	/**
	 * After an edit in the Animate mode: the first time the selected animation makes the head stop following the mouse
	 * (non-additive rotation / position keys on the head) or leaves a limb swinging (non-additive, keys only on the
	 * parts inside it), a toast says so; clicking it opens the X-ray. Returns how many toasts were shown.
	 */
	public static int liveCheck() {
		if (!com.tom.cpm.blockbench.format.CPMCodec.isCPM() || !AnimToolSupport.animateMode())return 0;
		Animation a = Animation.selected;
		if (a == null)return 0;
		int n = 0;
		for (Row r : analyse(a)) {
			String key = a.uuid + "|" + r.root.uuid;
			if (r.replaces() && r.breaksLook() && warned.add(key + "|look")) {
				PlusDialogs.toast(PlusDialogs.trf("cpmplus.w6animx.xray.now.look", a.name), "visibility_off", 12000, () -> open(a));
				n++;
			}
			if (r.childOnlyNote() && warned.add(key + "|child")) {
				PlusDialogs.toast(PlusDialogs.trf("cpmplus.w6animx.xray.now.child", partName(r.root), a.name), "sync", 12000, () -> open(a));
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ Devolver pro Minecraft

	/** number of position / rotation keys of the root in this animation */
	public static int rootKeyCount(Animation a, Group root) {
		BoneAnimator ba = AnimxUtil.animatorOf(a, root);
		return AnimxUtil.count(ba, AnimxUtil.ROT) + AnimxUtil.count(ba, AnimxUtil.POS);
	}

	/** deletes the root's position / rotation keys in this animation (one undo entry); returns the number removed */
	public static int returnToMinecraft(Animation a, Group root) {
		BoneAnimator ba = AnimxUtil.animatorOf(a, root);
		int n = rootKeyCount(a, root);
		if (ba == null || n == 0)return 0;
		List<Animation> l = Collections.singletonList(a);
		PlusUndo.run(PlusDialogs.trf("cpmplus.w6animx.undo.return", partName(root)), AnimxUtil.track(UndoData.make(a), l), () -> AnimxUtil.track(UndoData.make(a), l), () -> {
			for (String ch : Arrays.asList(AnimxUtil.ROT, AnimxUtil.POS)) {
				for (Keyframe kf : AnimxUtil.sorted(AnimxUtil.keys(ba, ch)))kf.remove();
			}
		});
		AnimxUtil.preview();
		return n;
	}

	static void returnDialog(Animation a, Group g) {
		int n = rootKeyCount(a, g);
		if (n == 0) {
			PlusDialogs.quick(PlusDialogs.trf("cpmplus.w6animx.return.none", partName(g)), 3000);
			return;
		}
		List<FormRow> rows = new ArrayList<>();
		rows.add(FormRow.info("i", null, PlusDialogs.trf("cpmplus.w6animx.return.intro", n, partName(g), a.name)));
		rows.add(FormRow.radio("mode", "cpmplus.w6animx.return.mode", PlusDialogs.options("subgroup", "cpmplus.w6animx.return.mode.subgroup", "delete", "cpmplus.w6animx.return.mode.delete"), "subgroup"));
		PlusDialogs.form("cpmplus_w6animx_return", "cpmplus.w6animx.return.title", rows, fr -> {
			if ("delete".equals(fr.getString("mode"))) {
				int k = returnToMinecraft(a, g);
				PlusDialogs.done(PlusDialogs.trf("cpmplus.w6animx.return.done", k, partName(g)));
			} else {
				Subgroup.openDialog(g, Collections.singletonList(a), true, null);
			}
		});
	}
}
