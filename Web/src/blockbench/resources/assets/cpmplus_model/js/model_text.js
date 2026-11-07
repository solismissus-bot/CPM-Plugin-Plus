/*
 * CPM Plugin+ "CPM+ Modelar" - Texto em blocos: a text written with a pixel font (3 x 5 small capitals or 5 x 7 with
 * lowercase, digits, punctuation and the Portuguese accents), made of cubes: each letter pixel is a little box, the
 * boxes are joined greedily (few cubes), in a new group on the chosen body part, facing front / back / a side / up.
 * In CPM projects they are colour cubes (no skin space). Live preview while typing; one Ctrl+Z.
 * Loaded after the modelling engine (L.core.modules.model).
 */
(function (L) {
  'use strict';
  var G = L.G, M = L.core.modules.model, T = M.T;
  var api = {};
  var MAX_CHARS = 400;

  // =================================================================== fonts ('#' = ink; rows top to bottom; row 0 = top of a capital)
  // small: 3 x 5 capitals (lowercase is drawn with the capitals), wider M / N / W so they read well
  var SMALL = {
    h: 5, space: 2, lower: false, glyphs: {
      A: '.#.|#.#|###|#.#|#.#', B: '##.|#.#|##.|#.#|##.', C: '.##|#..|#..|#..|.##', D: '##.|#.#|#.#|#.#|##.',
      E: '###|#..|##.|#..|###', F: '###|#..|##.|#..|#..', G: '.##|#..|#.#|#.#|.##', H: '#.#|#.#|###|#.#|#.#',
      I: '###|.#.|.#.|.#.|###', J: '..#|..#|..#|#.#|.#.', K: '#.#|#.#|##.|#.#|#.#', L: '#..|#..|#..|#..|###',
      M: '#...#|##.##|#.#.#|#...#|#...#', N: '#..#|##.#|#.##|#..#|#..#', O: '.#.|#.#|#.#|#.#|.#.', P: '##.|#.#|##.|#..|#..',
      Q: '.#.|#.#|#.#|##.|.##', R: '##.|#.#|##.|#.#|#.#', S: '.##|#..|.#.|..#|##.', T: '###|.#.|.#.|.#.|.#.',
      U: '#.#|#.#|#.#|#.#|###', V: '#.#|#.#|#.#|.#.|.#.', W: '#...#|#...#|#.#.#|##.##|#...#', X: '#.#|#.#|.#.|#.#|#.#',
      Y: '#.#|#.#|.#.|.#.|.#.', Z: '###|..#|.#.|#..|###',
      '0': '###|#.#|#.#|#.#|###', '1': '.#.|##.|.#.|.#.|###', '2': '##.|..#|.#.|#..|###', '3': '##.|..#|.#.|..#|##.',
      '4': '#.#|#.#|###|..#|..#', '5': '###|#..|##.|..#|##.', '6': '.##|#..|###|#.#|###', '7': '###|..#|.#.|.#.|.#.',
      '8': '###|#.#|###|#.#|###', '9': '###|#.#|###|..#|##.',
      '.': '.|.|.|.|#', ',': '..|..|..|..|.#|#.', '!': '#|#|#|.|#', '?': '##.|..#|.#.|...|.#.', ':': '.|#|.|#|.',
      ';': '..|.#|..|.#|#.', '-': '...|...|###|...|...', '+': '...|.#.|###|.#.|...', '=': '...|###|...|###|...',
      '/': '..#|..#|.#.|#..|#..', '(': '.#|#.|#.|#.|.#', ')': '#.|.#|.#|.#|#.', "'": '#|#|.|.|.', '"': '#.#|#.#|...|...|...',
      '*': '...|#.#|.#.|#.#|...', '#': '#.#|###|#.#|###|#.#', '%': '#.#|..#|.#.|#..|#.#', '&': '.#.|#.#|.#.|#.#|.##',
      '_': '...|...|...|...|###', '<': '..#|.#.|#..|.#.|..#', '>': '#..|.#.|..#|.#.|#..', '@': '###|#.#|###|#..|.##',
      '$': '.##|##.|.#.|.##|##.', '[': '##|#.|#.|#.|##', ']': '##|.#|.#|.#|##',
      '\u00ba': '.#.|#.#|.#.|...|###', '\u00aa': '.##|#.#|.##|...|###'
    }
  };
  // normal: 5 x 7 with lowercase (x-height 5, descenders 2 rows below the line)
  var NORMAL = {
    h: 7, space: 3, lower: true, glyphs: {
      A: '.###.|#...#|#...#|#####|#...#|#...#|#...#', B: '####.|#...#|#...#|####.|#...#|#...#|####.', C: '.###.|#...#|#....|#....|#....|#...#|.###.',
      D: '###..|#..#.|#...#|#...#|#...#|#..#.|###..', E: '#####|#....|#....|####.|#....|#....|#####', F: '#####|#....|#....|####.|#....|#....|#....',
      G: '.###.|#...#|#....|#.###|#...#|#...#|.####', H: '#...#|#...#|#...#|#####|#...#|#...#|#...#', I: '###|.#.|.#.|.#.|.#.|.#.|###',
      J: '..###|...#.|...#.|...#.|...#.|#..#.|.##..', K: '#...#|#..#.|#.#..|##...|#.#..|#..#.|#...#', L: '#....|#....|#....|#....|#....|#....|#####',
      M: '#...#|##.##|#.#.#|#.#.#|#...#|#...#|#...#', N: '#...#|#...#|##..#|#.#.#|#..##|#...#|#...#', O: '.###.|#...#|#...#|#...#|#...#|#...#|.###.',
      P: '####.|#...#|#...#|####.|#....|#....|#....', Q: '.###.|#...#|#...#|#...#|#.#.#|#..#.|.##.#', R: '####.|#...#|#...#|####.|#.#..|#..#.|#...#',
      S: '.####|#....|#....|.###.|....#|....#|####.', T: '#####|..#..|..#..|..#..|..#..|..#..|..#..', U: '#...#|#...#|#...#|#...#|#...#|#...#|.###.',
      V: '#...#|#...#|#...#|#...#|#...#|.#.#.|..#..', W: '#...#|#...#|#...#|#.#.#|#.#.#|#.#.#|.#.#.', X: '#...#|#...#|.#.#.|..#..|.#.#.|#...#|#...#',
      Y: '#...#|#...#|#...#|.#.#.|..#..|..#..|..#..', Z: '#####|....#|...#.|..#..|.#...|#....|#####',
      a: '.....|.....|.###.|....#|.####|#...#|.####', b: '#....|#....|#.##.|##..#|#...#|#...#|####.', c: '.....|.....|.###.|#....|#....|#...#|.###.',
      d: '....#|....#|.##.#|#..##|#...#|#...#|.####', e: '.....|.....|.###.|#...#|#####|#....|.###.', f: '..##|.#..|.#..|###.|.#..|.#..|.#..',
      g: '.....|.....|.####|#...#|#...#|#...#|.####|....#|.###.', h: '#....|#....|#.##.|##..#|#...#|#...#|#...#', i: '.#.|...|##.|.#.|.#.|.#.|###',
      j: '...#|....|..##|...#|...#|...#|...#|#..#|.##.', k: '#...|#...|#..#|#.#.|##..|#.#.|#..#', l: '##.|.#.|.#.|.#.|.#.|.#.|.##',
      m: '.....|.....|##.#.|#.#.#|#.#.#|#...#|#...#', n: '.....|.....|#.##.|##..#|#...#|#...#|#...#', o: '.....|.....|.###.|#...#|#...#|#...#|.###.',
      p: '.....|.....|####.|#...#|#...#|#...#|####.|#....|#....', q: '.....|.....|.####|#...#|#...#|#...#|.####|....#|....#',
      r: '.....|.....|#.##.|##..#|#....|#....|#....', s: '.....|.....|.####|#....|.###.|....#|####.', t: '.#..|.#..|####|.#..|.#..|.#..|..##',
      u: '.....|.....|#...#|#...#|#...#|#..##|.##.#', v: '.....|.....|#...#|#...#|#...#|.#.#.|..#..', w: '.....|.....|#...#|#...#|#.#.#|#.#.#|.#.#.',
      x: '.....|.....|#...#|.#.#.|..#..|.#.#.|#...#', y: '.....|.....|#...#|#...#|#...#|#...#|.####|....#|.###.', z: '.....|.....|#####|...#.|..#..|.#...|#####',
      '\u0131': '...|...|##.|.#.|.#.|.#.|###',
      '0': '.###.|#...#|#..##|#.#.#|##..#|#...#|.###.', '1': '.#.|##.|.#.|.#.|.#.|.#.|###', '2': '.###.|#...#|....#|...#.|..#..|.#...|#####',
      '3': '#####|...#.|..#..|...#.|....#|#...#|.###.', '4': '...#.|..##.|.#.#.|#..#.|#####|...#.|...#.', '5': '#####|#....|####.|....#|....#|#...#|.###.',
      '6': '..##.|.#...|#....|####.|#...#|#...#|.###.', '7': '#####|....#|...#.|..#..|.#...|.#...|.#...', '8': '.###.|#...#|#...#|.###.|#...#|#...#|.###.',
      '9': '.###.|#...#|#...#|.####|....#|...#.|.##..',
      '.': '..|..|..|..|..|##|##', ',': '..|..|..|..|..|##|.#|#.', '!': '#|#|#|#|#|.|#', '?': '.###.|#...#|....#|...#.|..#..|.....|..#..',
      ':': '..|##|##|..|##|##|..', ';': '..|##|##|..|##|##|.#|#.', '-': '....|....|....|####|....|....|....', '+': '.....|..#..|..#..|#####|..#..|..#..|.....',
      '=': '.....|.....|#####|.....|#####|.....|.....', '/': '....#|....#|...#.|..#..|.#...|#....|#....', '(': '..#|.#.|#..|#..|#..|.#.|..#',
      ')': '#..|.#.|..#|..#|..#|.#.|#..', "'": '#|#|.|.|.|.|.', '"': '#.#|#.#|...|...|...|...|...', '*': '.....|..#..|#.#.#|.###.|#.#.#|..#..|.....',
      '#': '.#.#.|.#.#.|#####|.#.#.|#####|.#.#.|.#.#.', '%': '##...|##..#|...#.|..#..|.#...|#..##|...##', '&': '.##..|#..#.|#.#..|.#...|#.#.#|#..#.|.##.#',
      '_': '.....|.....|.....|.....|.....|.....|#####', '<': '...#|..#.|.#..|#...|.#..|..#.|...#', '>': '#...|.#..|..#.|...#|..#.|.#..|#...',
      '@': '.###.|#...#|....#|.##.#|#.#.#|#.#.#|.###.', '$': '..#..|.####|#.#..|.###.|..#.#|####.|..#..', '[': '###|#..|#..|#..|#..|#..|###',
      ']': '###|..#|..#|..#|..#|..#|###', '\u00ba': '.#.|#.#|.#.|...|###|...|...', '\u00aa': '.##|#.#|.##|...|###|...|...'
    }
  };
  var FONTS = { f3x5: SMALL, f5x7: NORMAL };
  // lang: text.font.{f3x5|f5x7}
  var FONT_IDS = ['f3x5', 'f5x7'];
  // accents: 2 rows above the letter (1 empty row between), the cedilla right under it
  var MARKS = {
    acute: ['..#', '.#.'], grave: ['#..', '.#.'], circ: ['.#.', '#.#'], uml: ['#.#'],
    tilde: ['.#.#', '#.#.'], tildeW: ['.##.#', '#..#.'], ced: ['.#.', '##.'], cedW: ['..#..', '.##..']
  };
  // [mark, base letters, their accented lowercase char codes] (capitals = code - 0x20)
  var COMPOSE_TABLE = [
    ['acute', 'aeiou', [0xe1, 0xe9, 0xed, 0xf3, 0xfa]], ['grave', 'aeiou', [0xe0, 0xe8, 0xec, 0xf2, 0xf9]],
    ['circ', 'aeiou', [0xe2, 0xea, 0xee, 0xf4, 0xfb]], ['uml', 'aeiou', [0xe4, 0xeb, 0xef, 0xf6, 0xfc]],
    ['tilde', 'aon', [0xe3, 0xf5, 0xf1]], ['ced', 'c', [0xe7]]
  ];
  var COMPOSE = {};
  COMPOSE_TABLE.forEach(function (row) {
    for (var i = 0; i < row[1].length; i++) {
      COMPOSE[String.fromCharCode(row[2][i])] = [row[1][i], row[0]];
      COMPOSE[String.fromCharCode(row[2][i] - 0x20)] = [row[1][i].toUpperCase(), row[0]];
    }
  });
  /** a glyph as {w, cells: [[x, y]...]} (y can be < 0: accents above a capital), or null when the font has no drawing */
  var glyphCache = {};
  function plainGlyph(font, ch) {
    var s = font.glyphs[ch]; if (!s) return null;
    var rows = s.split('|'), w = 0, cells = [];
    rows.forEach(function (r, y) { w = Math.max(w, r.length); for (var x = 0; x < r.length; x++) if (r[x] === '#') cells.push([x, y]); });
    return { w: w, cells: cells };
  }
  function glyph(fid, ch) {
    var k = fid + '|' + ch;
    if (glyphCache.hasOwnProperty(k)) return glyphCache[k];
    var font = FONTS[fid], g = null;
    if (!font.lower && ch !== ch.toUpperCase() && ch.toUpperCase().length === 1) ch = ch.toUpperCase();
    g = plainGlyph(font, ch);
    if (!g && COMPOSE[ch]) {
      var base = COMPOSE[ch][0], mark = COMPOSE[ch][1];
      if (!font.lower) base = base.toUpperCase();
      if (font.lower && base === 'i') base = '\u0131';
      var b = plainGlyph(font, base);
      if (b) {
        var wide = b.w >= 5, rows = MARKS[mark === 'tilde' && wide ? 'tildeW' : mark === 'ced' && wide ? 'cedW' : mark];
        var top = Infinity, bottom = -Infinity; b.cells.forEach(function (c) { top = Math.min(top, c[1]); bottom = Math.max(bottom, c[1]); });
        var aw = rows[0].length, off = Math.max(0, Math.floor((b.w - aw) / 2)), cells = b.cells.slice();
        var y0 = mark === 'ced' ? bottom + 1 : top - 1 - rows.length;
        rows.forEach(function (r, i) { for (var x = 0; x < r.length; x++) if (r[x] === '#') cells.push([off + x, y0 + i]); });
        g = { w: b.w, cells: cells };
      }
    }
    glyphCache[k] = g;
    return g;
  }

  // =================================================================== text -> pixel grid
  /**
   * o = {text, font, spacing, lineGap, align ('left' | 'center' | 'right'), bold} -> {w, h, ink (Uint8Array w*h, row 0 =
   * top), missing: [chars without drawing], chars (drawn)}
   */
  function raster(o) {
    var fid = FONTS[o.font] ? o.font : 'f5x7', font = FONTS[fid], sp = Math.max(0, Math.round(Number(o.spacing) || 0)), bold = !!o.bold;
    var text = String(o.text || '').replace(/\r/g, '').slice(0, MAX_CHARS), lines = text.split('\n'), missing = [], drawn = 0;
    // per line: the pixels relative to the line's top-left (y relative to the capital's top)
    var laid = lines.map(function (line) {
      var x = 0, px = [], first = true;
      for (var i = 0; i < line.length; i++) {
        var ch = line[i];
        if (!first) x += sp;
        first = false;
        if (ch === ' ' || ch === '\t') { x += font.space; continue; }
        var g = glyph(fid, ch);
        if (!g) { if (missing.indexOf(ch) < 0) missing.push(ch); x += font.space; continue; }
        drawn++;
        g.cells.forEach(function (c) { px.push([x + c[0], c[1]]); if (bold) px.push([x + c[0] + 1, c[1]]); });
        x += g.w + (bold ? 1 : 0);
      }
      var w = 0; px.forEach(function (p) { w = Math.max(w, p[0] + 1); });
      return { px: px, w: Math.max(w, x) };
    });
    var top = 0, bottom = font.h - 1;
    laid.forEach(function (l) { l.px.forEach(function (p) { top = Math.min(top, p[1]); bottom = Math.max(bottom, p[1]); }); });
    var lineH = bottom - top + 1, pitch = lineH + Math.max(0, Math.round(Number(o.lineGap) || 0));
    var W = 0; laid.forEach(function (l) { W = Math.max(W, l.w); });
    // trailing empty lines do not count
    var n = laid.length; while (n > 1 && !laid[n - 1].px.length) n--;
    var H = (n - 1) * pitch + lineH;
    if (!W || !drawn) return { w: 0, h: 0, ink: new Uint8Array(0), missing: missing, chars: 0 };
    var ink = new Uint8Array(W * H);
    for (var li = 0; li < n; li++) {
      var l = laid[li], dx = o.align === 'left' ? 0 : o.align === 'right' ? W - l.w : Math.floor((W - l.w) / 2);
      l.px.forEach(function (p) { var x = p[0] + dx, y = li * pitch + (p[1] - top); if (x >= 0 && x < W && y >= 0 && y < H) ink[y * W + x] = 1; });
    }
    return { w: W, h: H, ink: ink, missing: missing, chars: drawn };
  }

  // =================================================================== where it goes
  // lang: text.face.{front|back|right|left|up}
  var FACES = ['front', 'back', 'right', 'left', 'up'];
  // R = the reading direction, D = down the text, N = out of the surface (CPM: the player looks to -Z, his right is +X)
  var DIRS = {
    front: { R: [-1, 0, 0], D: [0, -1, 0], N: [0, 0, -1] }, back: { R: [1, 0, 0], D: [0, -1, 0], N: [0, 0, 1] },
    right: { R: [0, 0, -1], D: [0, -1, 0], N: [1, 0, 0] }, left: { R: [0, 0, 1], D: [0, -1, 0], N: [-1, 0, 0] },
    up: { R: [-1, 0, 0], D: [0, 0, -1], N: [0, 1, 0] }
  };
  var ROOTS = ['head', 'body', 'right_arm', 'left_arm', 'right_leg', 'left_leg'];
  function rootGroup(name) {
    if (!G.Group) return null;
    for (var i = 0; i < G.Group.all.length; i++) { var g = G.Group.all[i]; if (g.parent === 'root' && g.name === name) return g; }
    return null;
  }
  /** the choices of 'Onde': the selection, the body parts that exist, other top groups, loose (no group) */
  function whereOptions() {
    var o = {};
    if (M.selNodes().length) o.sel = T('text.where.sel');
    ROOTS.forEach(function (r) { if (rootGroup(r)) o['root:' + r] = T('lib.root.' + r); });
    (G.Group ? G.Group.all : []).forEach(function (g) { if (g.parent === 'root' && ROOTS.indexOf(g.name) < 0 && !o['root:' + g.name]) o['root:' + g.name] = String(g.name); });
    o.none = T('text.where.none');
    return o;
  }
  function defaultWhere() {
    if (M.selNodes().length) return 'sel';
    return rootGroup('body') ? 'root:body' : 'none';
  }
  /** data box (the parent's coordinates, inflate included) of some cubes, or null */
  function dataBox(cubes) {
    var lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    cubes.forEach(function (c) { var f = c.inflate || 0; for (var i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], Math.min(c.from[i], c.to[i]) - f); hi[i] = Math.max(hi[i], Math.max(c.from[i], c.to[i]) + f); } });
    return lo[0] === Infinity ? null : { lo: lo, hi: hi };
  }
  /**
   * the surface the text stands on: {parent, box (data coordinates of the parent chain), origin (fallback point)}.
   * where: 'sel' | 'root:<name>' | 'none'
   */
  function anchor(where) {
    if (where === 'sel') {
      var n = M.selNodes()[0];
      if (n) {
        if (M.isCube(n)) return { parent: n.parent && n.parent !== 'root' ? n.parent : 'root', box: dataBox([n]) };
        var bx = dataBox(M.cubesOf([n]));
        return { parent: n, box: bx, origin: n.origin.slice() };
      }
    }
    if (where && where.indexOf('root:') === 0) {
      var g = rootGroup(where.slice(5));
      if (g) {
        var base = M.cubesOf([g]).filter(function (c) { return c.parent === g && M.isBase(c); });
        return { parent: g, box: dataBox(base.length ? base : M.cubesOf([g])), origin: g.origin.slice() };
      }
    }
    return { parent: 'root', box: null, origin: [0, 0, 0] };
  }
  function add(a, b, k) { return [a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k]; }
  /**
   * the boxes of a text (data coordinates of the parent): o = raster options + {size, depth, face, where} ->
   * {boxes: [{from, to}], centre, parent, grid}
   */
  function plan(o) {
    var grid = raster(o), s = Math.max(0.0625, Number(o.size) || 1), depth = Math.max(0.0625, Number(o.depth) || 1);
    var d = DIRS[o.face] || DIRS.front, an = anchor(o.where), P;
    // the centre of the text block, on the surface on that side (the box's middle, pushed out to its face)
    if (an.box) {
      var c = [(an.box.lo[0] + an.box.hi[0]) / 2, (an.box.lo[1] + an.box.hi[1]) / 2, (an.box.lo[2] + an.box.hi[2]) / 2];
      for (var i = 0; i < 3; i++) if (d.N[i]) c[i] = d.N[i] > 0 ? an.box.hi[i] : an.box.lo[i];
      P = c;
    } else if (o.where === 'none') {
      // loose: standing on the ground at the middle of the world (lying text: on the ground)
      P = o.face === 'up' ? [0, 0, 0] : [0, grid.h * s / 2, 0];
    } else P = an.origin.slice();
    var tl = add(add(P, d.R, -grid.w * s / 2), d.D, -grid.h * s / 2), boxes = [];
    var rects = grid.w ? M.greedyBoxes(grid.w, grid.h, 1, function (x, y) { return grid.ink[y * grid.w + x]; }) : [];
    rects.forEach(function (r) {
      var a = add(add(tl, d.R, r.x * s), d.D, r.y * s), b = add(add(a, d.R, r.w * s), d.D, r.h * s), e = add(a, d.N, depth);
      var from = [], to = [];
      for (var k = 0; k < 3; k++) {
        var vals = [a[k], b[k], e[k], b[k] + d.N[k] * depth];
        from.push(M.snap(Math.min.apply(null, vals))); to.push(M.snap(Math.max.apply(null, vals)));
      }
      boxes.push({ from: from, to: to });
    });
    return { boxes: boxes, centre: [M.snap(P[0]), M.snap(P[1]), M.snap(P[2])], parent: an.parent, grid: grid };
  }
  function cleanName(s) {
    var t = String(s || '').normalize ? String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '') : String(s || '');
    t = t.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 24);
    return t;
  }
  function groupName(o) {
    var n = o.name && String(o.name).trim();
    if (n) return n;
    var t = cleanName(String(o.text || '').split('\n')[0]);
    return T('text.group') + (t ? '_' + t : '');
  }
  function currentColour() {
    try { var c = G.ColorPanel && G.ColorPanel.get ? G.ColorPanel.get() : null; if (c) return c; } catch (e) { }
    return '#ffffff';
  }

  // =================================================================== making it (one undo entry)
  function create(o) {
    o = Object.assign({}, DEF, o || {});
    var p = plan(o);
    if (!p.boxes.length) throw new Error(T('text.empty'));
    var colour = M.rgbOf(o.colour || currentColour()), name = groupName(o), group = null;
    var made = M.edit(T('text'), { outliner: true, elements: [], groups: [] }, function () {
      group = M.newGroup(name, p.centre, p.parent);
      return p.boxes.map(function (b, i) { return M.newCube({ name: name + '_' + (i + 1), from: b.from, to: b.to, origin: p.centre, colour: colour }, group); });
    }, function (cubes) { return { outliner: true, elements: cubes, groups: [group] }; });
    M.refresh();
    try { if (typeof G.unselectAllElements === 'function') G.unselectAllElements(); group.select(); } catch (e) { }
    return { group: group, cubes: made, grid: p.grid };
  }

  // =================================================================== the window (live preview)
  var DEF = { text: 'CPM', font: 'f5x7', size: 1, depth: 1, spacing: 1, lineGap: 2, align: 'center', bold: false, face: 'front', where: 'none' };
  var dlg = null;
  /** model-space matrix of a group chain (null when nothing is turned) - the preview is drawn in model space */
  function chainMatrix(parent) {
    var m = null, T3 = G.THREE, chain = [];
    for (var q = parent; q && q !== 'root'; q = q.parent) chain.unshift(q);
    chain.forEach(function (n) {
      var r = n.rotation, o = n.origin;
      if (!r || (!r[0] && !r[1] && !r[2])) return;
      var t = new T3.Matrix4().makeTranslation(o[0], o[1], o[2]).multiply(new T3.Matrix4().makeRotationFromEuler(new T3.Euler(r[0] * M.DEG, r[1] * M.DEG, r[2] * M.DEG, 'ZYX'))).multiply(new T3.Matrix4().makeTranslation(-o[0], -o[1], -o[2]));
      m = m ? m.multiply(t) : t;
    });
    return m;
  }
  function open() {
    var saved = {}; try { saved = JSON.parse(L.opt('model.text', '{}') || '{}') || {}; } catch (e) { saved = {}; }
    var last = Object.assign({}, DEF, saved), cpm = M.isCpm(), wheres = whereOptions(), fonts = {}, faces = {};
    FONT_IDS.forEach(function (f) { fonts[f] = T('text.font.' + f); });
    FACES.forEach(function (f) { faces[f] = T('text.face.' + f); });
    var where = defaultWhere();
    var more = function (f) { return !!f.more; };
    var form = {
      text: { label: T('text.f.text'), type: 'textarea', value: last.text, height: 70 },
      font: { label: T('text.f.font'), type: 'select', options: fonts, value: FONTS[last.font] ? last.font : DEF.font },
      size: { label: T('text.f.size'), type: 'number', value: last.size, min: 0.25, max: 8, step: 0.25 },
      depth: { label: T('text.f.depth'), type: 'number', value: last.depth, min: 0.25, max: 16, step: 0.25 },
      spacing: { label: T('text.f.spacing'), type: 'number', value: last.spacing, min: 0, max: 6, step: 1 },
      where: { label: T('text.f.where'), type: 'select', options: wheres, value: where },
      face: { label: T('text.f.face'), type: 'select', options: faces, value: FACES.indexOf(last.face) >= 0 ? last.face : 'front' }
    };
    if (cpm) form.colour = { label: T('text.f.colour'), type: 'color', value: last.colour || currentColour() };
    form.more = { label: T('text.f.more'), type: 'checkbox', value: false };
    form.lineGap = { label: T('text.f.linegap'), type: 'number', value: last.lineGap, min: 0, max: 8, step: 1, condition: more };
    form.align = { label: T('text.f.align'), type: 'select', options: { left: T('text.align.left'), center: T('text.align.center'), right: T('text.align.right') }, value: last.align, condition: more };
    form.bold = { label: T('text.f.bold'), type: 'checkbox', value: !!last.bold, condition: more };
    form.name = { label: T('text.f.name'), type: 'text', value: '', placeholder: T('text.f.name.ph'), condition: more };
    // lang: text.tip.{cpm}
    dlg = new G.Dialog({
      id: 'cpmplus_model_text', title: T('text'), width: 480, buttons: [T('text.go'), 'dialog.cancel'],
      lines: ['<p class="cmp_dlg_tip">' + L.esc(T('text.tip' + (cpm ? '.cpm' : ''))) + '</p>'],
      form: form,
      onFormChange: function (f) { update(f); },
      onConfirm: function (f) {
        M.clearPreview();
        var keep = { text: f.text, font: f.font, size: f.size, depth: f.depth, spacing: f.spacing, lineGap: f.lineGap, align: f.align, bold: f.bold, face: f.face };
        if (f.colour) keep.colour = M.hexOf(M.rgbOf(f.colour));
        L.setOpt('model.text', JSON.stringify(keep));
        try { create(f); } catch (e) { G.Blockbench.showQuickMessage(e.message || String(e), 3000); return false; }
        dlg = null;
      },
      onCancel: function () { M.clearPreview(); dlg = null; }
    });
    dlg.show();
    function update(f) {
      var o = Object.assign({}, DEF, f), p = null;
      try { p = plan(o); } catch (e) { p = null; L.warn('text preview failed', e); }
      if (!p) return;
      var m = chainMatrix(p.parent);
      M.preview(p.boxes.map(function (b) { return m ? { from: b.from, to: b.to, matrix: m } : b; }), M.rgbOf(o.colour || '#4fc3f7'), { opacity: 0.85 });
      var note = p.boxes.length ? T('text.count', p.boxes.length, M.num(p.grid.w * o.size), M.num(p.grid.h * o.size)) : T('text.empty');
      if (p.grid.missing.length) note += ' \u00b7 ' + T('text.missing', p.grid.missing.join(' '));
      M.dialogNote(dlg, note, M.allCubes().length + p.boxes.length, !p.boxes.length || p.grid.missing.length > 0);
    }
    try { update(dlg.getFormResult()); } catch (e) { }
    setTimeout(function () { if (dlg) { try { update(dlg.getFormResult()); } catch (e) { } } }, 0);
    return dlg;
  }

  // =================================================================== registration
  api.install = function () {
    M.register({ id: 'cpmplus_model_text', sec: 'create', icon: 'text_fields', key: 'text', mode: 'edit', spots: [], run: function () { open(); } });
  };
  api.uninstall = function () { if (dlg) { try { dlg.close(); } catch (e) { } dlg = null; } M.clearPreview(); };
  api.raster = raster; api.plan = function (o) { return plan(Object.assign({}, DEF, o || {})); }; api.create = create; api.open = open;
  api.dialog = function () { return dlg; }; api.glyph = glyph; api.fonts = FONT_IDS.slice(); api.compose = COMPOSE;
  return api;
})
