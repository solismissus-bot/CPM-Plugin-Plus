package com.tom.cpm.blockbench.tools.bend;

import java.util.ArrayList;
import java.util.List;

import com.google.gwt.core.client.JavaScriptObject;
import com.tom.cpm.blockbench.proxy.Cube;
import com.tom.cpm.blockbench.proxy.Group;
import com.tom.cpm.blockbench.proxy.OutlinerElement;
import com.tom.cpm.blockbench.proxy.Undo;
import com.tom.cpm.blockbench.proxy.Undo.UndoData;
import com.tom.cpm.blockbench.util.IdUtil;
import com.tom.cpm.blockbench.util.PlusDialogs;
import com.tom.cpm.blockbench.util.PlusUndo;

import elemental2.core.JsArray;
import jsinterop.base.Js;
import jsinterop.base.JsPropertyMap;

/**
 * CPM+ (G57) 'Curvar…': turns one cube into a chain of N slices. Slice k is cube 'nome_k' inside group 'nome_k'
 * (child of group k-1), each group turned by angle/N around the joint line, cubes without own rotation (so the export
 * never creates '_r1' groups). Side faces get the matching strip of the original UV (whole texels only, so only some N
 * are offered); the original end faces stay on the first / last slice; the faces between slices show the skin row at
 * the joint (so an open joint never shows a hole). Every slice size lands on the 0.1 px grid the CPM export writes,
 * and inflated slices are shortened so the DRAWN slices meet exactly. 'Fechar frestas' extends both sides of every
 * joint by t*tan(phi/2) (rounded up to 0.1: a mitre, the outer corners meet) and shrinks odd slices 0.05 px per side
 * on the joint axis against z-fighting. One undo (or Cancel) gives the original cube back with its uuid and name.
 */
public final class BendCube {
	public static final int MAX_SLICES = 12;

	private BendCube() {
	}

	public static final class Opts {
		public String axis = "auto", dir = "", pivot = "inner";
		public double angle = 90;
		public int slices = 4;
		public boolean close, flip;

		JavaScriptObject js() {
			JsPropertyMap<Object> o = JsPropertyMap.of();
			o.set("axis", axis);
			o.set("dir", dir);
			o.set("pivot", pivot);
			o.set("angle", angle);
			o.set("slices", (double) slices);
			o.set("close", close);
			o.set("flip", flip);
			o.set("maxN", (double) MAX_SLICES);
			return Js.uncheckedCast(o);
		}

		boolean same(Opts b) {
			return axis.equals(b.axis) && dir.equals(b.dir) && pivot.equals(b.pivot) && angle == b.angle && slices == b.slices && close == b.close && flip == b.flip;
		}
	}

	public static final class Result {
		public boolean ok, changed;
		public final List<String> errors = new ArrayList<>();
		public final List<Group> groups = new ArrayList<>();
		public final List<Cube> cubes = new ArrayList<>();
		public double gap, realAngle, extend;
		public JsPropertyMap<Object> plan;
	}

	/** dry run (never changes anything) */
	public static JsPropertyMap<Object> plan(Cube cube, Opts o) {
		return Js.uncheckedCast(nativePlan(cube, o.js()));
	}

	/** checks that do not depend on the options (null = ok, else the reason key) */
	public static String refuse(Cube cube) {
		if (cube == null || BendLimb.isMesh(cube))return "cpmplus.w7rig.curve.err.notCube";
		if (cube.extrude || cube.getData().getExtrude() != null)return "cpmplus.w7rig.curve.err.extrude";
		if (BendLimb.rotated(cube))return "cpmplus.w7rig.curve.err.rotated";
		return null;
	}

	/** applies a bend in one undo entry */
	public static Result apply(Cube cube, Opts o) {
		Result res = new Result();
		String why = refuse(cube);
		if (why != null) {
			res.errors.add(PlusDialogs.tr(why));
			return res;
		}
		JsPropertyMap<Object> plan = plan(cube, o);
		res.plan = plan;
		JsArray<String> errs = Js.uncheckedCast(plan.get("errors"));
		for (int i = 0; i < errs.length; i++)res.errors.add(PlusDialogs.tr("cpmplus.w7rig.curve.err." + errs.getAt(i)));
		if (!Js.isTruthy(plan.get("ok")))return res;
		res.gap = Js.asDouble(plan.get("gap"));
		res.realAngle = Js.asDouble(plan.get("realAngle"));
		res.extend = Js.asDouble(plan.get("extend"));
		String base = cube.name;
		Object parentObj = Js.asPropertyMap(cube).get("parent");
		Group parent = parentObj != null && "object".equals(Js.typeof(parentObj)) ? Js.uncheckedCast(parentObj) : null;
		UndoData before = new UndoData();
		before.outliner = true;
		before.selection = true;
		before.elements = new OutlinerElement[] {cube};
		before.groups = parent != null ? new Group[] {parent} : new Group[0];
		PlusUndo.run(PlusDialogs.trf("cpmplus.w7rig.curve.undo", base), before, () -> {
			UndoData a = new UndoData();
			a.outliner = true;
			a.selection = true;
			a.elements = res.cubes.toArray(new OutlinerElement[0]);
			List<Group> gs = new ArrayList<>(res.groups);
			if (parent != null)gs.add(parent);
			a.groups = gs.toArray(new Group[0]);
			return a;
		}, () -> {
			JsPropertyMap<Object> out = Js.uncheckedCast(nativeApply(cube, Js.uncheckedCast(plan), base));
			JsArray<Group> gs = Js.uncheckedCast(out.get("groups"));
			JsArray<Cube> cs = Js.uncheckedCast(out.get("cubes"));
			for (int i = 0; i < gs.length; i++) {
				IdUtil.resetIds(gs.getAt(i));
				res.groups.add(gs.getAt(i));
			}
			for (int i = 0; i < cs.length; i++) {
				IdUtil.resetIds(cs.getAt(i));
				res.cubes.add(cs.getAt(i));
			}
		});
		res.ok = true;
		res.changed = true;
		return res;
	}

	static String fmt(double v) {
		return BendLimb.fmt(v);
	}

	/** live status: valid N, measured outer gap per joint, real angle, warnings */
	public static String statusText(JsPropertyMap<Object> p, Opts o) {
		StringBuilder sb = new StringBuilder();
		JsArray<Double> valid = Js.uncheckedCast(p.get("valid"));
		List<String> vs = new ArrayList<>();
		if (valid != null)for (int i = 0; i < valid.length; i++)vs.add(String.valueOf((int) (double) valid.getAt(i)));
		sb.append(PlusDialogs.trf("cpmplus.w7rig.curve.validN", vs.isEmpty() ? "-" : String.join(", ", vs)));
		JsArray<String> errs = Js.uncheckedCast(p.get("errors"));
		if (errs != null)for (int i = 0; i < errs.length; i++)sb.append("\n").append(PlusDialogs.tr("cpmplus.w7rig.curve.err." + errs.getAt(i)));
		if (p.get("gap") != null && Js.isTruthy(p.get("ok"))) {
			double gap = Js.asDouble(p.get("gap"));
			boolean close = Js.isTruthy(p.get("close"));
			if (close)sb.append("\n").append(PlusDialogs.trf("cpmplus.w7rig.curve.closed", fmt(Js.asDouble(p.get("extend")))));
			else if (gap > 0.005) {
				sb.append("\n").append(PlusDialogs.trf("cpmplus.w7rig.curve.gap", fmt(gap)));
				int better = suggestN(p, 0.25);
				if (better > 0)sb.append(" ").append(PlusDialogs.trf("cpmplus.w7rig.curve.gapHint", better));
			}
			if ("center".equals(o.pivot) && Math.abs(o.angle) > 0)sb.append("\n").append(PlusDialogs.tr("cpmplus.w7rig.curve.centerWarn"));
			double real = Js.asDouble(p.get("realAngle"));
			if (Math.abs(real - o.angle) >= 0.05)sb.append("\n").append(PlusDialogs.trf("cpmplus.w7rig.curve.realAngle", fmt(real)));
		}
		if (Js.isTruthy(p.get("colourOnly")))sb.append("\n").append(PlusDialogs.tr("cpmplus.w7rig.curve.colourOnly"));
		sb.append("\n").append(PlusDialogs.tr("cpmplus.w7rig.curve.honest"));
		return sb.toString();
	}

	/** the smallest valid N above the current one whose outer gap is at most maxGap (0 = none) */
	private static int suggestN(JsPropertyMap<Object> p, double maxGap) {
		JsArray<Double> valid = Js.uncheckedCast(p.get("valid"));
		double t = Js.asDouble(p.get("thickness"));
		double theta = Math.abs(Js.asDouble(p.get("theta")));
		int cur = (int) Js.asDouble(p.get("N"));
		if (valid == null)return 0;
		for (int i = 0; i < valid.length; i++) {
			int n = (int) (double) valid.getAt(i);
			if (n <= cur)continue;
			double g = 2 * t * Math.sin(Math.toRadians(theta / n) / 2);
			if (g <= maxGap)return n;
		}
		return 0;
	}

	/**
	 * Live preview inside the dialog: every change applies the bend as one undo entry after undoing the previous
	 * preview (and dropping its redo entry), Cancel undoes it, OK keeps exactly one entry.
	 */
	static final class Preview {
		private final Cube original;
		private final String uuid;
		private Opts shown;
		private Result shownResult;
		private int baseHistory = -1;

		Preview(Cube c) {
			this.original = c;
			this.uuid = c.uuid;
		}

		/** the cube the plan works on: the original (undo restores it with the same uuid) */
		Cube source() {
			Cube c = BendLimb.cubeByUuid(uuid);
			return c != null ? c : original;
		}

		boolean isShown(Opts o) {
			return shown != null && shown.same(o);
		}

		void show(Opts o) {
			drop();
			// the position in the history (not its length: a redo branch is dropped by the preview entry)
			int h = undoIndex();
			try {
				Result r = apply(source(), o);
				if (r.changed) {
					shown = o;
					shownResult = r;
					baseHistory = h;
				}
			} catch (Throwable e) {
				shown = null;
				shownResult = null;
			}
		}

		void drop() {
			if (shown == null)return;
			Undo.undo();
			truncateRedo(baseHistory);
			shown = null;
			shownResult = null;
			baseHistory = -1;
		}

		/** OK: keeps the preview when it matches, else applies once */
		Result commit(Opts o) {
			if (shown != null && shown.same(o)) {
				Result r = shownResult;
				shown = null;
				shownResult = null;
				return r;
			}
			drop();
			return apply(source(), o);
		}
	}

	/** Undo.index of the open project (-1 when unknown) */
	public static native int undoIndex() /*-{
		try { var U = $wnd.Undo; return U && typeof U.index === 'number' ? U.index : -1; } catch (e) { return -1; }
	}-*/;

	private static native void truncateRedo(int len) /*-{
		try {
			var U = $wnd.Undo;
			if (len >= 0 && U && U.history && U.history.length > len && U.index === len) U.history.length = len;
		} catch (e) {}
	}-*/;

	/** the one selected cube, or null */
	public static native Cube selectedCube() /*-{
		var s = ($wnd.Cube && $wnd.Cube.selected) || [];
		var out = [];
		for (var i = 0; i < s.length; i++) if (s[i] && s[i] instanceof $wnd.Cube) out.push(s[i]);
		return out.length === 1 ? out[0] : null;
	}-*/;

	// ======================================================================== natives

	/**
	 * Plans a bend of one cube. o = {axis:'auto'|0|1|2, dir:'+x'|'-x'|'+y'|'-y'|'+z'|'-z', angle (deg), slices,
	 * pivot:'inner'|'center', close, flip, maxN}. Returns {ok, errors[], valid[], slices[{from,to,pivot,rotation,faces}],
	 * gap, extend, realAngle, thickness, theta, N, axis, dir, colourOnly}.
	 */
	private static native JavaScriptObject nativePlan(Object cube, JavaScriptObject o) /*-{
		var W = $wnd;
		var res = {ok: false, errors: [], warnings: [], valid: []};
		if (!cube || !(cube instanceof W.Cube)) { res.errors.push('notCube'); return res; }
		var r = cube.rotation || [0, 0, 0];
		if (Math.abs(r[0]) > 1e-6 || Math.abs(r[1]) > 1e-6 || Math.abs(r[2]) > 1e-6) { res.errors.push('rotated'); return res; }
		var FACES = ['east', 'west', 'up', 'down', 'south', 'north'];
		var NORMAL = {east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0], south: [0, 0, 1], north: [0, 0, -1]};
		var dot = function(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; };
		var cross = function(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; };
		var on01 = function(v) { return Math.abs(v * 10 - Math.round(v * 10)) < 1e-3; };
		var g01 = function(v) { return Math.round(v * 10) / 10; };
		var disabled = function(fc) { return !fc || fc.texture === null; };
		var from = [Math.min(cube.from[0], cube.to[0]), Math.min(cube.from[1], cube.to[1]), Math.min(cube.from[2], cube.to[2])];
		var to = [Math.max(cube.from[0], cube.to[0]), Math.max(cube.from[1], cube.to[1]), Math.max(cube.from[2], cube.to[2])];
		var size = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
		var infl = +cube.inflate || 0;
		var axis = o.axis;
		if (axis === 'auto' || axis === undefined || axis === null || axis === '') {
			axis = 0;
			for (var i = 1; i < 3; i++) if (size[i] > size[axis] + 1e-9) axis = i;
			if (size[1] >= size[axis] - 1e-9) axis = 1;
		}
		axis = +axis;
		res.axis = axis;
		var L0 = size[axis];
		if (L0 <= 1e-6) { res.errors.push('flat'); return res; }
		var dir = '' + (o.dir || '');
		var dAxis = {x: 0, y: 1, z: 2}[dir.charAt(1)];
		var dSign = dir.charAt(0) === '-' ? -1 : 1;
		if (dAxis === undefined || dAxis === axis) {
			// default: hanging parts (length Y) curl backwards (+z), the others droop down (-y)
			dAxis = axis === 1 ? 2 : 1; dSign = dAxis === 1 ? -1 : 1;
			res.dirDefaulted = true;
		}
		res.dir = (dSign < 0 ? '-' : '+') + 'xyz'.charAt(dAxis);
		var cAxis = 3 - axis - dAxis;
		var theta = +o.angle || 0;
		var N = Math.max(1, Math.round(+o.slices || 1));
		var maxN = Math.max(1, +o.maxN || 12);
		var bendSign = theta < 0 ? -dSign : dSign;
		var phi = Math.abs(theta) / N;
		res.theta = theta; res.N = N; res.phi = phi;
		// base end: nearest to the cube's pivot along the axis (flip = other end)
		var org = cube.origin || [0, 0, 0];
		var baseMin = Math.abs(org[axis] - from[axis]) <= Math.abs(org[axis] - to[axis]) + 1e-9;
		if (o.flip) baseMin = !baseMin;
		var sgnL = baseMin ? 1 : -1;
		res.baseMin = baseMin;
		var T = size[dAxis] + 2 * infl;// drawn thickness
		res.thickness = T;
		var inner = (o.pivot || 'inner') !== 'center';
		var pivA = inner ? (bendSign > 0 ? to[dAxis] + infl : from[dAxis] - infl) : (from[dAxis] + to[dAxis]) / 2;
		var dOut = inner ? T : T / 2;
		var phiR = phi * Math.PI / 180;
		res.gap = phi > 0 ? 2 * dOut * Math.sin(phiR / 2) : 0;
		var close = !!o.close && phi > 0;
		res.close = close;
		var ext = close ? Math.ceil(dOut * Math.tan(phiR / 2) * 10 - 1e-6) / 10 : 0;
		res.extend = ext;
		// rotation sign: turning the tip direction around +C by +phi must move it toward bendSign*D
		var eL = [0, 0, 0]; eL[axis] = sgnL;
		var eC = [0, 0, 0]; eC[cAxis] = 1;
		var eD = [0, 0, 0]; eD[dAxis] = bendSign;
		var rotSign = dot(cross(eC, eL), eD) >= 0 ? 1 : -1;
		var rotVal = rotSign * phi;
		// the game stores angles as 1/65535 of a turn (truncated): the real total angle in game (magnitude)
		res.realAngle = (theta < 0 ? -1 : 1) * Math.floor(phi / 360 * 65535 + 1e-9) * 360 / 65535 * N;

		// ---- face UVs as Blockbench shows them (box UV: the box layout Blockbench uses, floored size)
		var uvs = {};
		if (cube.box_uv) {
			var s = [Math.floor(size[0] + 1e-6), Math.floor(size[1] + 1e-6), Math.floor(size[2] + 1e-6)];
			var lay = [{face: 'east', from: [0, s[2]], size: [s[2], s[1]]}, {face: 'west', from: [s[2] + s[0], s[2]], size: [s[2], s[1]]},
				{face: 'up', from: [s[2] + s[0], s[2]], size: [-s[0], -s[2]]}, {face: 'down', from: [s[2] + s[0] * 2, 0], size: [-s[0], s[2]]},
				{face: 'south', from: [s[2] * 2 + s[0], s[2]], size: [s[0], s[1]]}, {face: 'north', from: [s[2], s[2]], size: [s[0], s[1]]}];
			if (cube.mirror_uv) {
				lay.forEach(function(f) { f.from[0] += f.size[0]; f.size[0] *= -1; });
				var tmp = {from: lay[0].from.slice(), size: lay[0].size.slice()};
				lay[0].from = lay[1].from.slice(); lay[0].size = lay[1].size.slice();
				lay[1].from = tmp.from; lay[1].size = tmp.size;
			}
			var uo = cube.uv_offset || [0, 0];
			lay.forEach(function(f) { uvs[f.face] = [f.from[0] + uo[0], f.from[1] + uo[1], f.from[0] + f.size[0] + uo[0], f.from[1] + f.size[1] + uo[1]]; });
		} else {
			FACES.forEach(function(f) { var fc = cube.faces[f]; uvs[f] = fc && fc.uv ? [fc.uv[0], fc.uv[1], fc.uv[2], fc.uv[3]] : [0, 0, 0, 0]; });
		}
		// texels per UV unit
		var texOf = function(fc) {
			if (!fc || fc.texture === null || fc.texture === false) return null;
			try { if (typeof fc.getTexture === 'function') return fc.getTexture(); } catch (e) {}
			return null;
		};
		var qOf = function(fc, comp) {
			var tex = texOf(fc);
			if (!tex || !tex.width) return 1;
			var uw = comp === 0 ? ((typeof tex.getUVWidth === 'function' ? tex.getUVWidth() : 0) || W.Project.texture_width || tex.width)
				: ((typeof tex.getUVHeight === 'function' ? tex.getUVHeight() : 0) || W.Project.texture_height || tex.height);
			var px = comp === 0 ? tex.width : (tex.height || tex.width);
			return px / uw;
		};
		// which UV component of a side face runs along the length axis, and which end of it is at the base: read from
		// the real preview mesh (rotation / mirror / inverted rects are whatever Blockbench draws)
		var faceDir = function(f) {
			var mesh = cube.mesh;
			if (!mesh || !mesh.geometry || !mesh.geometry.attributes) return null;
			var pos = mesh.geometry.attributes.position, uva = mesh.geometry.attributes.uv;
			var order = (W.Canvas && W.Canvas.face_order && W.Canvas.face_order.length == 6) ? W.Canvas.face_order : FACES;
			var fi = order.indexOf(f);
			if (!pos || !uva || fi < 0 || pos.count < (fi + 1) * 4) return null;
			// the two corners of one edge that runs along the axis (same position on the other in-face axis)
			var nAx = Math.abs(NORMAL[f][0]) > 0.5 ? 0 : Math.abs(NORMAL[f][1]) > 0.5 ? 1 : 2;
			var oAx = 3 - axis - nAx;
			var vs = [];
			for (var i = 0; i < 4; i++) {
				var vi = fi * 4 + i;
				var P = [pos.getX(vi), pos.getY(vi), pos.getZ(vi)];
				vs.push({p: P[axis], o: P[oAx], uv: [uva.getX(vi), 1 - uva.getY(vi)]});
			}
			var minO = Math.min(vs[0].o, vs[1].o, vs[2].o, vs[3].o);
			var edge = vs.filter(function(v) { return Math.abs(v.o - minO) < 1e-6; });
			if (edge.length !== 2) return null;
			var lo = edge[0].p <= edge[1].p ? edge[0] : edge[1], hi = edge[0].p <= edge[1].p ? edge[1] : edge[0];
			if (Math.abs(hi.p - lo.p) < 1e-9) return null;
			var du = Math.abs(hi.uv[0] - lo.uv[0]), dv = Math.abs(hi.uv[1] - lo.uv[1]);
			var comp = du >= dv ? 0 : 1;
			// does the component grow from the min end to the max end of the axis?
			var grows = hi.uv[comp] > lo.uv[comp];
			return {comp: comp, growsWithAxis: grows};
		};
		var sides = [];
		var isColour = true;
		for (var fi = 0; fi < FACES.length; fi++) {
			var f = FACES[fi];
			var fc = cube.faces[f];
			if (disabled(fc)) continue;
			if (fc.texture !== false) isColour = false;
			var n = NORMAL[f];
			if (Math.abs(n[axis]) > 0.5) continue;// end face
			if (fc.texture === false) continue;// blank face: no UV to keep
			var uv = uvs[f];
			if (Math.abs(uv[2] - uv[0]) < 1e-9 && Math.abs(uv[3] - uv[1]) < 1e-9) continue;
			var fd = faceDir(f);
			if (!fd) { res.errors.push('noMesh'); return res; }
			var i1 = fd.comp === 0 ? 0 : 1, i2 = fd.comp === 0 ? 2 : 3;
			var cMin = Math.min(uv[i1], uv[i2]), cMax = Math.max(uv[i1], uv[i2]);
			// UV component at the min / max end of the axis
			var atMin = fd.growsWithAxis ? cMin : cMax, atMax = fd.growsWithAxis ? cMax : cMin;
			var cStart = baseMin ? atMin : atMax, cEnd = baseMin ? atMax : atMin;
			sides.push({face: f, comp: fd.comp, i1: i1, i2: i2, cStart: cStart, cEnd: cEnd, q: qOf(fc, fd.comp), uv: uv.slice(), normal: n});
		}
		res.colourOnly = isColour;
		// drawn boundaries of N slices on the 0.1 grid (offsets from the drawn base end), null = impossible
		var drawn = L0 + 2 * infl;
		var bounds = function(nn) {
			if (!on01(drawn) || !on01(2 * infl)) return null;
			var b = [];
			for (var k = 0; k <= nn; k++) b.push(g01(drawn * k / nn));
			b[0] = 0; b[nn] = g01(drawn);
			for (var k2 = 1; k2 <= nn; k2++) if (b[k2] - b[k2 - 1] - 2 * infl < 0.1 - 1e-6) return null;
			if (infl === 0) for (var k3 = 1; k3 <= nn; k3++) if (Math.abs(b[k3] - drawn * k3 / nn) > 1e-6) return null;
			return b;
		};
		var onGrid = function(x, qq) { var y = x * qq; return Math.abs(y - Math.round(y)) < 1e-4; };
		for (var nn = 1; nn <= maxN; nn++) {
			var ok = bounds(nn) !== null;
			for (var si = 0; si < sides.length && ok; si++) {
				var sd = sides[si];
				for (var k = 0; k <= nn && ok; k++) if (!onGrid(sd.cStart + (sd.cEnd - sd.cStart) * k / nn, sd.q)) ok = false;
			}
			if (ok) res.valid.push(nn);
		}
		if (!on01(drawn) || !on01(2 * infl)) res.errors.push('sizeGrid');
		else if (res.valid.indexOf(N) < 0) res.errors.push(N > maxN ? 'tooMany' : 'invalidN');
		if (res.errors.length) return res;
		var B = bounds(N);
		// the outer side face: its skin row is shown on the faces between slices
		var outer = null;
		for (var so = 0; so < sides.length; so++) if (Math.abs(sides[so].normal[dAxis]) > 0.5 && sides[so].normal[dAxis] * bendSign < 0) outer = sides[so];
		if (!outer && sides.length) outer = sides[0];
		var stripAt = function(sd, c, towardTip) {
			// one texel of the side face at boundary c, on the tip side (towardTip) or the base side of it
			var sg = sd.cEnd >= sd.cStart ? 1 : -1;
			var o2 = towardTip ? c + sg / sd.q : c - sg / sd.q;
			var uv = sd.uv.slice();
			uv[sd.i1] = Math.min(c, o2); uv[sd.i2] = Math.max(c, o2);
			return uv;
		};
		// ---- slices (rest coordinates)
		var drawnStart = baseMin ? from[axis] - infl : to[axis] + infl;
		var shrink = ext > 0 && N > 1 ? 0.05 : 0;
		var slices = [];
		for (var k4 = 0; k4 < N; k4++) {
			var d0 = drawnStart + sgnL * B[k4], d1 = drawnStart + sgnL * B[k4 + 1];// drawn ends (base side, tip side)
			var a0 = d0 + sgnL * infl, a1 = d1 - sgnL * infl;// box ends
			var e0 = (close && k4 > 0) ? ext : 0, e1 = (close && k4 < N - 1) ? ext : 0;
			var b0 = a0 - sgnL * e0, b1 = a1 + sgnL * e1;
			var sf = from.slice(), st = to.slice();
			sf[axis] = Math.min(b0, b1); st[axis] = Math.max(b0, b1);
			if (shrink > 0 && (k4 % 2) === 1) { sf[cAxis] += shrink; st[cAxis] -= shrink; }
			var piv = [0, 0, 0];
			piv[axis] = d0; piv[dAxis] = pivA; piv[cAxis] = (from[cAxis] + to[cAxis]) / 2;
			var rot = [0, 0, 0]; rot[cAxis] = rotVal;
			var faces = {};
			for (var fj = 0; fj < FACES.length; fj++) {
				var ff = FACES[fj];
				var src = cube.faces[ff];
				var nrm = NORMAL[ff];
				var entry = {uv: uvs[ff].slice(), rotation: cube.box_uv ? 0 : (src.rotation || 0), texture: src.texture === undefined ? null : src.texture};
				if (Math.abs(nrm[axis]) > 0.5) {
					// end face: the original only on the first (base side) / last (tip side) slice
					var towardBase = (nrm[axis] > 0) !== (sgnL > 0);
					var isOuter = towardBase ? k4 === 0 : k4 === N - 1;
					if (!isOuter) {
						if (close || !outer) { entry.texture = null; entry.uv = [0, 0, 0, 0]; }
						else {
							// between two slices: the skin row at the joint (an open joint shows the colour, never a hole)
							var cB = outer.cStart + (outer.cEnd - outer.cStart) * (towardBase ? k4 : k4 + 1) / N;
							entry.uv = stripAt(outer, cB, towardBase);
							entry.rotation = 0;
							entry.texture = cube.faces[outer.face].texture;
						}
					}
				} else {
					for (var s2 = 0; s2 < sides.length; s2++) {
						var sd2 = sides[s2];
						if (sd2.face !== ff) continue;
						var cA = sd2.cStart + (sd2.cEnd - sd2.cStart) * k4 / N, cB2 = sd2.cStart + (sd2.cEnd - sd2.cStart) * (k4 + 1) / N;
						// keep the rect's orientation: the component that pointed to the base keeps pointing to the base side
						var firstIsBase = Math.abs(sd2.uv[sd2.i1] - sd2.cStart) <= Math.abs(sd2.uv[sd2.i1] - sd2.cEnd);
						entry.uv[sd2.i1] = Math.round((firstIsBase ? cA : cB2) * 1e6) / 1e6;
						entry.uv[sd2.i2] = Math.round((firstIsBase ? cB2 : cA) * 1e6) / 1e6;
					}
				}
				faces[ff] = entry;
			}
			slices.push({from: sf, to: st, pivot: piv, rotation: rot, faces: faces});
		}
		res.slices = slices;
		res.ok = true;
		return res;
	}-*/;

	/** applies a plan (inside an undo entry): returns {groups:[], cubes:[]}; the original cube is removed */
	private static native JavaScriptObject nativeApply(Object cube, JavaScriptObject plan, String baseName) /*-{
		var W = $wnd;
		var out = {groups: [], cubes: []};
		var parent = cube.parent;
		var arr = (parent && typeof parent === 'object' && parent.children) ? parent.children : ((W.Outliner && W.Outliner.root) || []);
		var index = arr.indexOf(cube);
		var save = cube.getSaveCopy ? cube.getSaveCopy() : {};
		// a colour cube (no texture on any face) stays box UV: the export only treats box UV cubes as colour cubes
		var keepBox = !!plan.colourOnly && !!cube.box_uv;
		var prev = null;
		for (var k = 0; k < plan.slices.length; k++) {
			var sl = plan.slices[k];
			var nm = baseName + '_' + (k + 1);
			var g = new W.Group({name: nm, origin: sl.pivot.slice(), rotation: sl.rotation.slice()}).init();
			if (prev) g.addTo(prev);
			else g.addTo(parent, index);
			g.isOpen = true;
			var data = JSON.parse(JSON.stringify(save));
			delete data.uuid;
			data.name = nm;
			data.from = sl.from.slice(); data.to = sl.to.slice();
			data.origin = sl.pivot.slice();
			data.rotation = [0, 0, 0];
			data.box_uv = keepBox;
			data.faces = {};
			for (var f in sl.faces) data.faces[f] = {uv: sl.faces[f].uv.slice(), rotation: sl.faces[f].rotation || 0, texture: sl.faces[f].texture};
			var c = new W.Cube(data);
			c.addTo(g).init();
			c.box_uv = keepBox;
			if (!keepBox) for (var f2 in sl.faces) {
				if (!c.faces[f2]) continue;
				c.faces[f2].uv = sl.faces[f2].uv.slice();
				c.faces[f2].rotation = sl.faces[f2].rotation || 0;
				c.faces[f2].texture = sl.faces[f2].texture;
			}
			try { if (c.preview_controller) { c.preview_controller.updateGeometry(c); c.preview_controller.updateFaces(c); c.preview_controller.updateUV(c); } } catch (e) {}
			out.groups.push(g);
			out.cubes.push(c);
			prev = g;
		}
		cube.remove();
		try { if (W.Canvas && W.Canvas.updateAll) W.Canvas.updateAll(); } catch (e) {}
		return out;
	}-*/;
}
