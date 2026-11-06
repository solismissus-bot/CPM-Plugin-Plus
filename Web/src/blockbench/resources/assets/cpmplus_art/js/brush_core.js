/*
 * CPM Plugin+ "Pincéis" (beta30): a library of community-style brushes - the kinds artists really install in the
 * big mobile painting apps (textured pencil, render / blend, lineart, auto outline, hair, fur, grass, clouds, flames,
 * splatter, glitter, neon, blush, lace, pearls, patterns, textures...) - rebuilt procedurally in our own code (no brush
 * file or image of any app) and tuned for 64x64 skins.
 *
 * Loaded by the art core (api.loadModule) with its helpers L. ONE engine paints every brush through Blockbench's Brush
 * API 'draw' hook: arc-length spacing, scatter, rotation along the stroke, size / tone variation, start + end taper,
 * deterministic randomness per stroke, one result per pixel per stroke (no build-up), Blockbench's own brush tip
 * (size / circle / square), face clamp + selection + lock alpha, a separate path per mirror side, strokes that cross
 * into another texture, layers that grow while painting, and a virtual target for the library previews.
 */
(function (L) {
  'use strict';
  var G = L.G, t = L.t, clamp = L.clamp, byte = L.byte, hash3 = L.hash3;
  var api = {};
  var TOOL = 'cpmplus_art_brushes';
  var fixedSeed = null;

  // =================================================================== small helpers
  /** k tone steps of a colour: + lighter (toward warm), - darker (toward cool), like the Shade brush */
  function tone(rgb, k) {
    if (!k) return [rgb[0], rgb[1], rgb[2]];
    return L.hueShade(rgb, k > 0 ? 1 : -1, clamp(0.16 * Math.abs(k), 0, 0.85));
  }
  function lerp3(a, b, w) { return [a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w]; }
  /** u, v turned by -a: into the stamp's own axes (u = forward along the stroke) */
  function unrot(u, v, a) { if (!a) return [u, v]; var c = Math.cos(a), s = Math.sin(a); return [u * c + v * s, -u * s + v * c]; }
  function rot90(x, y, q) { switch (q & 3) { case 1: return [-y, x]; case 2: return [-x, -y]; case 3: return [y, -x]; default: return [x, y]; } }
  function inPoly(x, y, P) {
    var inside = false;
    for (var i = 0, j = P.length - 1; i < P.length; j = i++) {
      var xi = P[i][0], yi = P[i][1], xj = P[j][0], yj = P[j][1];
      if (((yi > y) !== (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) inside = !inside;
    }
    return inside;
  }
  var STAR = (function () { var p = []; for (var i = 0; i < 10; i++) { var a = -Math.PI / 2 + i * Math.PI / 5, r = i % 2 ? 0.45 : 1; p.push([Math.cos(a) * r, Math.sin(a) * r]); } return p; })();
  var WHITE = [255, 255, 255];
  function valueNoise(x, y, cell, seed) {
    var gx = Math.floor(x / cell), gy = Math.floor(y / cell), fx = x / cell - gx, fy = y / cell - gy;
    var a = hash3(gx, gy, seed), b = hash3(gx + 1, gy, seed), c = hash3(gx, gy + 1, seed), d = hash3(gx + 1, gy + 1, seed);
    var sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy), top = a + (b - a) * sx, bottom = c + (d - c) * sx;
    return top + (bottom - top) * sy;
  }
  function luminance(c) { return (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255; }

  // =================================================================== stamp shapes: (u, v) in -1..1 (u forward, v down) -> 0 / 1 / {c, k}
  // k = tone steps (+ lighter, - darker), 'c2' = colour 2, 'w' = almost white
  function flameIn(u, v) {
    if (v >= 0.3) return u * u + (v - 0.3) * (v - 0.3) <= 0.49;
    if (v < -1) return false;
    return Math.abs(u) <= 0.7 * Math.pow((v + 1) / 1.3, 1.3);
  }
  var MASK = {
    disc: function (u, v) { return u * u + v * v <= 1 ? 1 : 0; },
    star: function (u, v) { return inPoly(u, v, STAR) ? 1 : 0; },
    sparkle: function (u, v) {
      var au = Math.abs(u), av = Math.abs(v);
      if (au < 0.16 && av < 0.16) return { c: 1, k: 'w' };
      return (au <= 0.2 * (1 - av)) || (av <= 0.2 * (1 - au)) ? 1 : 0;
    },
    heart: function (u, v) { var x = u * 1.25, y = -v * 1.25 + 0.3, q = x * x + y * y - 1; return q * q * q - x * x * y * y * y <= 0 ? 1 : 0; },
    leaf: function (u, v) {
      if (Math.abs(v) > 0.5 * (1 - u * u)) return 0;
      return Math.abs(v) < 0.08 && Math.abs(u) < 0.85 ? { c: 1, k: -1 } : 1;
    },
    flower: function (u, v) {
      var d = Math.sqrt(u * u + v * v), a = Math.atan2(v, u);
      if (d < 0.28) return { c: 1, k: 'c2' };
      return d <= 0.55 + 0.45 * Math.abs(Math.cos(2.5 * a)) ? 1 : 0;
    },
    cloud: function (u, v) {
      var C = [[-0.48, 0.2, 0.5], [0, -0.1, 0.62], [0.48, 0.2, 0.5], [-0.22, 0.38, 0.45], [0.26, 0.38, 0.45]], hit = false;
      for (var i = 0; i < C.length; i++) { var dx = u - C[i][0], dy = v - C[i][1]; if (dx * dx + dy * dy <= C[i][2] * C[i][2]) { hit = true; break; } }
      if (!hit) return 0;
      return v < -0.3 ? { c: 1, k: 2 } : v > 0.5 ? { c: 1, k: -1 } : 1;
    },
    flame: function (u, v) {
      if (!flameIn(u, v)) return 0;
      if (flameIn(u / 0.55, (v - 0.36) / 0.55)) return { c: 1, k: 3 };
      return v < -0.35 ? { c: 1, k: -1 } : 1;
    },
    bubble: function (u, v) {
      var d = Math.sqrt(u * u + v * v); if (d > 1) return 0;
      var hx = u + 0.38, hy = v + 0.38; if (hx * hx + hy * hy < 0.05) return { c: 1, k: 'w' };
      return d >= 0.7 ? 1 : 0;
    },
    splat: function (u, v) {
      var d = Math.sqrt(u * u + v * v), a = Math.atan2(v, u);
      return d <= 0.62 + 0.22 * Math.sin(5 * a) * Math.cos(3 * a) ? 1 : 0;
    },
    // '>' pointing forward (feathers / herringbone along the stroke)
    chevron: function (u, v) { return Math.abs(v) <= 0.95 && Math.abs(u + 0.55 * Math.abs(v) - 0.1) <= 0.2 ? 1 : 0; },
    curl: function (u, v) {
      var d = Math.sqrt(u * u + v * v), a = Math.atan2(v, u);
      if (d > 1 || d < 0.52 || (a > -0.5 && a < 0.5)) return 0;
      return v > 0.25 ? { c: 1, k: -1 } : v < -0.45 ? { c: 1, k: 1 } : 1;
    },
    iris: function (u, v) {
      var d2 = u * u + v * v; if (d2 > 1) return 0;
      if ((u - 0.32) * (u - 0.32) + (v + 0.32) * (v + 0.32) < 0.05) return { c: 1, k: 'w' };
      if (d2 < 0.16) return { c: 1, k: -4 };
      if (v < -0.5) return { c: 1, k: -2 };
      if (d2 > 0.72) return { c: 1, k: -1 };
      return v > 0.3 ? { c: 1, k: 1 } : 1;
    },
    // tiles laid end to end along the stroke (u = forward, v = the right-hand side of the stroke); R = radius in pixels
    lace: function (u, v, R) {
      var px = 1 / R;
      if (Math.abs(v + 0.75) < 0.6 * px) return 1;// the tape
      if (v > -0.75) {
        var e = Math.sqrt(u * u + ((v + 0.75) / 1.5) * ((v + 0.75) / 1.5));
        if (Math.abs(e - 0.95) < 0.75 * px) return 1;// the scallop
      }
      return R >= 4 && u * u + (v - 0.05) * (v - 0.05) < 0.5 * px * px ? { c: 1, k: 1 } : 0;// the eyelet
    },
    chain: function (u, v, R) {
      var px = 1 / R, x = (u + 0.5) / 0.48, y = v / 0.62, e = Math.sqrt(x * x + y * y);
      if (Math.abs(e - 1) < 0.9 * px / 0.55) return v < -0.25 ? { c: 1, k: 1 } : v > 0.3 ? { c: 1, k: -1 } : 1;// round link
      return u > -0.02 && Math.abs(v) < 0.75 * px ? 1 : 0;// the next link, seen side-on
    },
    pearl: function (u, v) {
      var d2 = u * u + v * v; if (d2 > 0.95) return 0;
      if ((u + 0.35) * (u + 0.35) + (v + 0.35) * (v + 0.35) < 0.08) return { c: 1, k: 'w' };
      return d2 > 0.45 && v > 0.1 ? { c: 1, k: -1 } : 1;
    }
  };
  // hand-pixelled stamps for small sizes: '#' colour, '+' lighter, '-' darker, 'x' much darker, 'o' colour 2, 'w' almost white
  var BITMAP = {
    sparkle: function (s) { return s <= 2.5 ? ['w'] : s <= 4.5 ? ['.#.', '#w#', '.#.'] : ['..#..', '..#..', '##w##', '..#..', '..#..']; },
    star: function (s) { return s <= 2.5 ? ['#'] : s <= 4.5 ? ['.#.', '###', '#.#'] : ['..#..', '..#..', '#####', '.###.', '.#.#.']; },
    heart: function (s) { return s <= 2.5 ? ['#'] : s <= 4.5 ? ['#.#', '###', '.#.'] : ['.#.#.', '#+###', '#####', '.###.', '..#..']; },
    flower: function (s) { return s <= 2.5 ? ['#'] : ['.#.', '#o#', '.#.']; },
    leaf: function (s) { return s <= 2.5 ? ['#'] : ['.+#', '#-#', '##.']; },
    cloud: function (s) { return s <= 2.5 ? ['+'] : ['.++.', '+##+', '-##-']; },
    flame: function (s) { return s <= 2.5 ? ['+'] : ['.#.', '##.', '#+#', '-+-']; },
    bubble: function (s) { return s <= 2.5 ? ['+'] : ['w#.', '#.#', '.#.']; },
    splat: function (s) { return s <= 2.5 ? ['#'] : ['.#.', '###', '.#.']; },
    chevron: function (s) { return s <= 2.5 ? ['#'] : ['#.', '.#', '#.']; },
    curl: function (s) { return s <= 2.5 ? ['#'] : ['.#+', '#..', '.#-']; },
    iris: function (s) { return s <= 2.5 ? ['#w', 'x#'] : s <= 4.5 ? ['---', '#xw', '+#+'] : ['.--.', '-xw-', '#xx#', '.++.']; },
    // tiles (laid end to end: the bitmap width is the spacing), drawn for a stroke going right
    lace: function (s) { return s <= 2.5 ? ['##', '#.'] : s <= 4.5 ? ['###', '#.#', '.#.'] : ['####', '#..#', '.##.']; },
    chain: function (s) { return s <= 2.5 ? ['#.', '.#'] : s <= 4.5 ? ['.##...', '#..###', '.##...'] : ['.###....', '#...####', '.###....']; },
    pearl: function (s) { return s <= 2.5 ? ['#'] : s <= 4.5 ? ['w#', '#-'] : ['.w.', '###', '.-.']; }
  };

  // =================================================================== motifs repeated along the stroke
  // p = 0..1 inside one period, q = -1..1 across the stroke, s = arc length, period and w in texture pixels
  var MOTIF = {
    stitch: function (p, q, s, period, w) { return p < 0.6 && Math.abs(q) * w / 2 < 0.6 ? 1 : 0; },
    scar: function (p, q, s, period, w) {
      if (Math.abs(p - 0.5) * period < 0.6 && Math.abs(q) < 0.9) return { c: 1, k: 'c2' };
      return Math.abs(q) * w / 2 < 0.6 ? { c: 1, k: -2 } : 0;
    }
  };

  // =================================================================== patterns fixed to the texture (x, y texture pixels, c = cell size)
  // 'c2' = colour 2, {k:'o+1' / 'o-1'} = the pixel's own colour one tone lighter / darker (only where something is painted)
  var PATTERN = {
    checker: function (x, y, c) { return (Math.floor(x / c) + Math.floor(y / c)) & 1 ? 'c2' : 1; },
    stripes: function (x, y, c) { return Math.floor((x + y) / c) & 1 ? 'c2' : 1; },
    dots: function (x, y, c) {
      c = Math.max(2, c);
      var g = c * 2, lx = ((x % g) + g) % g - g / 2 + 0.5, ly = ((y % g) + g) % g - g / 2 + 0.5;
      return lx * lx + ly * ly <= c * c * 0.55 ? 1 : 0;
    },
    tartan: function (x, y, c) {
      var h = ((Math.floor(y / c) % 4) + 4) % 4 === 0, v = ((Math.floor(x / c) % 4) + 4) % 4 === 0;
      return h && v ? { c: 1, k: -2 } : (h || v) ? 'c2' : 1;
    },
    denim: function (x, y) { return (((x + 2 * y) % 4) + 4) % 4 === 0 ? { c: 1, k: -1 } : hash3(x, y, 51) < 0.12 ? { c: 1, k: 1 } : 1; },
    knit: function (x, y, c) {
      var X = Math.floor(x / c), Y = Math.floor(y / c), K = [[1, 0, 0, 1], [0, 1, 1, 0], [-1, 0, 0, -1]][((Y % 3) + 3) % 3][((X % 4) + 4) % 4];
      return K === 1 ? { c: 1, k: 1 } : K === -1 ? { c: 1, k: -1 } : 1;
    },
    halftone: function (x, y) { var lx = ((x % 4) + 4) % 4 - 1.5, ly = ((y % 4) + 4) % 4 - 1.5; return lx * lx + ly * ly <= 1.3 ? 1 : 0; },
    grain: function (x, y) { var h = hash3(x, y, 777); return h < 0.18 ? { c: 1, k: 'o-1' } : h > 0.86 ? { c: 1, k: 'o+1' } : 0; },
    paper: function (x, y) { var n = 0.6 * valueNoise(x, y, 4, 313) + 0.4 * hash3(x, y, 919); return n > 0.68 ? { c: 1, k: 'o+1' } : n < 0.3 ? { c: 1, k: 'o-1' } : 0; },
    scales: function (x, y, c) {
      var w = 4 * c, h = 3 * c, row = Math.floor(y / h), ox = row & 1 ? w / 2 : 0;
      var lx = (((x - ox) % w) + w) % w, ly = ((y % h) + h) % h;
      var u = (lx + 0.5) / w * 2 - 1, v = (ly + 0.5) / h, d = Math.sqrt(u * u + v * v * 1.3);
      if (d > 0.82) return { c: 1, k: -1 };
      return v < 0.35 && Math.abs(u) < 0.45 ? { c: 1, k: 1 } : 1;
    }
  };

  // =================================================================== the brushes (family + parameters)
  function P(id, group, fam, o) { o = o || {}; o.id = id; o.group = group; o.fam = fam; return o; }
  var PRESETS = [
    P('lapis', 'draw', 'grain'),
    P('lineart', 'draw', 'pen', { taper: 2.5 }),
    P('contorno', 'draw', 'outline', { auto: true }),
    P('contorno2', 'draw', 'outline', { auto: false }),
    P('render', 'draw', 'mix', { strength: 0.35, pickup: 0.3, amount: 0.55 }),
    P('renderpx', 'draw', 'softdither'),
    P('aquarela', 'draw', 'water'),
    P('oleo', 'draw', 'strands', { density: 1.1, minK: 3, maxK: 16, width: 1, tones: [-1, 0, 0, 1], blend: 'paint' }),
    P('arcoiris', 'draw', 'pen', { color: 'rainbow' }),
    P('pixel', 'draw', 'native'),
    P('cabelo', 'hair', 'strands', { density: 0.7, minK: 2, maxK: 10, width: 1, tones: [-1, 0, 1, 0, -1], taper: 1.5 }),
    P('cachos', 'hair', 'stamp', { mask: 'curl', spacing: 1, rot: 'follow', scale: 1, sizeJ: 0.25, tones: [-1, 0, 0, 1] }),
    P('brilhocabelo', 'hair', 'strands', { density: 0.3, minK: 1, maxK: 4, width: 0.7, tones: [2, 3], blend: 'toneUp', taper: 2, onPaint: true }),
    P('pelo', 'hair', 'tufts', { spacing: 0.35, count: 3, tones: [-1, 0, 1] }),
    P('penas', 'hair', 'stamp', { mask: 'chevron', spacing: 0.55, rot: 'follow', scale: 1, sizeJ: 0.15, tones: [0, 0, 1] }),
    P('escamas', 'hair', 'pattern', { pattern: 'scales', cellDiv: 6 }),
    P('grama', 'nature', 'blades', { spacing: 0.3, count: 3 }),
    P('folhas', 'nature', 'stamp', { mask: 'leaf', spacing: 0.6, scatter: 0.8, rot: 'random', scale: 0.9, sizeJ: 0.4, tones: [-1, 0, 0, 1] }),
    P('flores', 'nature', 'stamp', { mask: 'flower', spacing: 1.1, scatter: 1, rot: 'random', scale: 0.8, sizeJ: 0.4 }),
    P('nuvens', 'nature', 'stamp', { mask: 'cloud', spacing: 0.55, scatter: 0.25, rot: 'none', scale: 1.4, sizeJ: 0.35 }),
    P('fogo', 'nature', 'stamp', { mask: 'flame', spacing: 0.5, scatter: 0.35, rot: 'none', scale: 1.3, sizeJ: 0.45 }),
    P('respingos', 'nature', 'stamp', { mask: 'splat', spacing: 1.2, scatter: 1.2, rot: 'random', scale: 0.8, sizeJ: 0.7, count: 2 }),
    P('bolhas', 'nature', 'stamp', { mask: 'bubble', spacing: 1.1, scatter: 1, rot: 'none', scale: 0.9, sizeJ: 0.6 }),
    P('glitter', 'fx', 'spray', { spacing: 0.5, count: 4, scatter: 1, tones: [-1, 0, 1, 2] }),
    P('brilhos', 'fx', 'stamp', { mask: 'sparkle', spacing: 1.3, scatter: 1.1, rot: 'none', scale: 1.1, sizeJ: 0.6, blend: 'lighter', color: 'light' }),
    P('estrelas', 'fx', 'stamp', { mask: 'star', spacing: 1.8, scatter: 1.2, rot: 'none', scale: 1.1, sizeJ: 0.5, tones: [0, 1] }),
    P('neon', 'fx', 'glow'),
    P('blush', 'fx', 'blush', { spacing: 1.6 }),
    P('olhos', 'fx', 'stamp', { mask: 'iris', spacing: 3, rot: 'none', scale: 1 }),
    P('coracoes', 'deco', 'stamp', { mask: 'heart', spacing: 1.6, scatter: 0.6, rot: 'none', scale: 1, sizeJ: 0.4 }),
    P('renda', 'deco', 'stamp', { mask: 'lace', tile: true, rot: 'follow', scale: 1 }),
    P('perolas', 'deco', 'stamp', { mask: 'pearl', tile: true, rot: 'none', scale: 1 }),
    P('corrente', 'deco', 'stamp', { mask: 'chain', tile: true, rot: 'follow', scale: 1.2 }),
    P('costura', 'deco', 'motif', { motif: 'stitch', period: 0.75 }),
    P('cicatriz', 'deco', 'motif', { motif: 'scar', period: 0.8 }),
    P('xadrez', 'pattern', 'pattern', { pattern: 'checker', cellDiv: 4 }),
    P('listras', 'pattern', 'pattern', { pattern: 'stripes', cellDiv: 4 }),
    P('bolinhas', 'pattern', 'pattern', { pattern: 'dots', cellDiv: 6 }),
    P('escoces', 'pattern', 'pattern', { pattern: 'tartan', cellDiv: 5 }),
    P('jeans', 'pattern', 'pattern', { pattern: 'denim' }),
    P('trico', 'pattern', 'pattern', { pattern: 'knit', cellDiv: 8 }),
    P('papel', 'pattern', 'pattern', { pattern: 'paper', onPaint: true }),
    P('grao', 'pattern', 'pattern', { pattern: 'grain', onPaint: true }),
    P('reticula', 'pattern', 'pattern', { pattern: 'halftone' })
  ];
  var BY_ID = {}; PRESETS.forEach(function (p) { BY_ID[p.id] = p; });
  var GROUPS = ['draw', 'hair', 'nature', 'fx', 'deco', 'pattern'];
  /** the ones this tool paints ('pixel' is a shortcut to Blockbench's own pencil) */
  var PAINTABLE = PRESETS.filter(function (p) { return p.fam !== 'native'; });
  var USES_SCATTER = { stamp: 1, spray: 1, blades: 1, tufts: 1 };
  var USES_VARY = { stamp: 1, spray: 1, strands: 1, tufts: 1, blades: 1, grain: 1 };
  api.presets = PRESETS.map(function (p) { return p.id; });

  // =================================================================== stroke, targets (one per texture touched) and pixels
  var S = null;// the stroke: {seed, c1, c2, opacity, targets}
  var T = null;// the target being painted: {tex, src, ctx, w, h, off, orig, img, rec, dirty, sides, area, virtual}
  function preset() { var p = BY_ID[L.opt('br.preset', 'lapis')]; return p && p.fam !== 'native' ? p : BY_ID.lapis; }
  function scatterMul() { var s = G.BarItems.cpmplus_art_br_scatter; return s ? s.get() / 100 : 1; }
  function varyMul() { var s = G.BarItems.cpmplus_art_br_vary; return s ? s.get() / 100 : 1; }
  function shape() { try { return G.BarItems.brush_shape ? G.BarItems.brush_shape.value : 'circle'; } catch (e) { return 'circle'; } }
  function begin(o) {
    var sec = L.secondaryClick(o && o.event), p1 = L.primary(), p2 = L.secondary();
    var max = (G.settings && G.settings.opacity_range && G.settings.opacity_range.value == '255') ? 255 : 100;
    S = {
      seed: fixedSeed !== null ? fixedSeed : Math.floor(Math.random() * 999983),
      c1: sec ? p2 : p1, c2: sec ? p1 : p2, opacity: clamp(G.BarItems.slider_brush_opacity.get() / max, 0, 1), targets: []
    };
    T = null;
  }
  function targetFor(tex) {
    for (var i = 0; i < S.targets.length; i++) if (S.targets[i].tex === tex) return S.targets[i];
    var src = L.textureCanvasInfo(tex); if (!src || !src.canvas || !src.ctx) return null;
    var c = src.canvas, data = src.ctx.getImageData(0, 0, c.width, c.height), off = src.offset || [0, 0];
    var tg = { tex: tex, src: src, ctx: src.ctx, w: c.width, h: c.height, off: [off[0], off[1]], orig: new Uint8ClampedArray(data.data), img: data, rec: new Map(), dirty: null, sides: [], area: null, virtual: false };
    S.targets.push(tg);
    return tg;
  }
  function virtualTarget(w, h, fill) {
    var img = new ImageData(w, h);
    for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) { var c = fill(x, y), i = (y * w + x) * 4; img.data[i] = c[0]; img.data[i + 1] = c[1]; img.data[i + 2] = c[2]; img.data[i + 3] = c.length > 3 ? c[3] : 255; }
    return { tex: null, src: null, ctx: null, w: w, h: h, off: [0, 0], orig: new Uint8ClampedArray(img.data), img: img, rec: new Map(), dirty: null, sides: [], area: null, virtual: true };
  }
  /** a texture layer can grow while painting (Blockbench expands it under the brush): move the buffers along */
  function syncTarget(tg) {
    if (tg.virtual) return;
    var c = tg.src.canvas, off = tg.src.offset || [0, 0];
    if (c.width === tg.w && c.height === tg.h && off[0] === tg.off[0] && off[1] === tg.off[1]) return;
    var nw = c.width, nh = c.height, dx = tg.off[0] - off[0], dy = tg.off[1] - off[1];
    var cur = tg.ctx.getImageData(0, 0, nw, nh), orig = new Uint8ClampedArray(nw * nh * 4);
    for (var y = 0; y < tg.h; y++) {
      var ny = y + dy; if (ny < 0 || ny >= nh) continue;
      for (var x = 0; x < tg.w; x++) {
        var nx = x + dx; if (nx < 0 || nx >= nw) continue;
        var si = (y * tg.w + x) * 4, di = (ny * nw + nx) * 4;
        orig[di] = tg.orig[si]; orig[di + 1] = tg.orig[si + 1]; orig[di + 2] = tg.orig[si + 2]; orig[di + 3] = tg.orig[si + 3];
      }
    }
    tg.orig = orig; tg.img = cur; tg.w = nw; tg.h = nh; tg.off = [off[0], off[1]]; tg.dirty = null;
  }
  function faceArea() {
    var ea = G.Painter && G.Painter.editing_area, restrict = !(G.settings && G.settings.paint_side_restrict && G.settings.paint_side_restrict.value === false);
    return restrict && ea && typeof ea === 'object' ? [ea[0], ea[1], ea[2], ea[3]] : null;
  }
  function allowed(x, y) {
    var cx = x - T.off[0], cy = y - T.off[1];
    if (cx < 0 || cy < 0 || cx >= T.w || cy >= T.h) return false;
    if (T.virtual) return true;
    var ea = T.area;
    if (ea && (x < Math.floor(ea[0]) || y < Math.floor(ea[1]) || x >= ea[2] || y >= ea[3])) return false;
    var sel = T.tex && T.tex.selection;
    if (sel && typeof sel.allow === 'function' && sel.allow(x, y) == 0) return false;
    return true;
  }
  function idx(x, y) { return (y - T.off[1]) * T.w + (x - T.off[0]); }
  function keyOf(x, y) { return y * 65536 + x; }
  function mark(x, y) {
    var cx = x - T.off[0], cy = y - T.off[1], d = T.dirty;
    if (!d) T.dirty = { x0: cx, y0: cy, x1: cx, y1: cy };
    else { if (cx < d.x0) d.x0 = cx; if (cy < d.y0) d.y0 = cy; if (cx > d.x1) d.x1 = cx; if (cy > d.y1) d.y1 = cy; }
  }
  function flush() {
    var d = T.dirty; T.dirty = null;
    if (!d || T.virtual || !T.ctx) return;
    T.ctx.putImageData(T.img, 0, 0, d.x0, d.y0, d.x1 - d.x0 + 1, d.y1 - d.y0 + 1);
  }
  function lockAlpha() { return !T.virtual && !!(G.Painter && G.Painter.lock_alpha); }
  /**
   * Paints one texture pixel from its ORIGINAL colour (stroke start): one result per pixel per stroke, so going over
   * a pixel again never builds up. pr = priority (an outline never covers the fill of the same stroke); at the same
   * priority the stronger weight wins and a later dab of equal weight replaces an earlier one (later stamps on top).
   * blend: normal | lighter (screen over painted pixels) | toneUp (the pixel's own colour rgb tone steps lighter /
   * darker, only where something is painted) | paint (opaque, 15% of the colour underneath)
   */
  function paint(x, y, alpha, rgb, blend, pr, weight, side) {
    x = Math.round(x); y = Math.round(y);
    if (alpha <= 0.004 || !allowed(x, y)) return;
    var key = keyOf(x, y), r = T.rec.get(key), w = weight === undefined ? alpha : weight;
    pr = pr || 2;
    if (r && (r.pr > pr || (r.pr === pr && w < r.w - 1e-6))) return;
    var i = idx(x, y) * 4, o = T.orig, base = { r: o[i], g: o[i + 1], b: o[i + 2], a: o[i + 3] / 255 }, lock = lockAlpha();
    if (lock && base.a <= 0) return;
    var src;
    if (blend === 'toneUp') {
      if (base.a <= 0) return;
      src = tone([base.r, base.g, base.b], typeof rgb === 'number' ? rgb : 2);
    } else if (blend === 'lighter' && base.a > 0) {
      src = [255 - (255 - base.r) * (255 - rgb[0]) / 255, 255 - (255 - base.g) * (255 - rgb[1]) / 255, 255 - (255 - base.b) * (255 - rgb[2]) / 255];
    } else if (blend === 'paint' && base.a > 0) {
      src = lerp3(rgb, [base.r, base.g, base.b], 0.15);
    } else src = rgb;
    T.rec.set(key, { w: w, pr: pr, side: side === undefined ? -1 : side });
    var out = G.Painter.combineColors(base, { r: byte(src[0]), g: byte(src[1]), b: byte(src[2]), a: 1 }, clamp(alpha, 0, 1)), d = T.img.data;
    d[i] = byte(out.r); d[i + 1] = byte(out.g); d[i + 2] = byte(out.b);
    d[i + 3] = lock ? o[i + 3] : Math.round(clamp(out.a, 0, 1) * 255);
    mark(x, y);
  }
  /** puts a pixel back as it was when the stroke started (pixel-perfect corners, end taper) */
  function restore(x, y) {
    var key = keyOf(x, y); if (!T.rec.has(key)) return;
    T.rec.delete(key);
    var i = idx(x, y) * 4, d = T.img.data, o = T.orig;
    d[i] = o[i]; d[i + 1] = o[i + 1]; d[i + 2] = o[i + 2]; d[i + 3] = o[i + 3];
    mark(x, y);
  }
  /** mixing brushes work on the CURRENT colours (they are meant to build up), only where something is painted */
  function paintMix(x, y, a, rgb) {
    x = Math.round(x); y = Math.round(y);
    if (a <= 0.004 || !allowed(x, y)) return;
    var i = idx(x, y) * 4, d = T.img.data;
    if (d[i + 3] === 0) return;
    d[i] = byte(d[i] + (rgb[0] - d[i]) * a); d[i + 1] = byte(d[i + 1] + (rgb[1] - d[i + 1]) * a); d[i + 2] = byte(d[i + 2] + (rgb[2] - d[i + 2]) * a);
    mark(x, y);
  }
  function current(x, y) { x = Math.round(x); y = Math.round(y); if (!allowed(x, y)) return null; var i = idx(x, y) * 4, d = T.img.data; return d[i + 3] ? [d[i], d[i + 1], d[i + 2]] : null; }
  function original(x, y) { if (!allowed(x, y)) return null; var i = idx(x, y) * 4, o = T.orig; return o[i + 3] ? [o[i], o[i + 1], o[i + 2]] : null; }

  // ---- the brush tip, exactly like Blockbench's own brush (circle / square, even sizes centred on a pixel corner)
  function tip(x, y, size) {
    var smooth = (x % 1 !== 0) || (y % 1 !== 0), s = smooth ? Math.max(1, size) : Math.max(1, Math.round(size));
    var even = !smooth && s % 2 === 0, cx = smooth ? x - 0.5 : even ? x - 0.5 : x, cy = smooth ? y - 0.5 : even ? y - 0.5 : y;
    var rmax = smooth ? s / 2 : Math.round(s + 1) / 2;
    return { cx: cx, cy: cy, s: s, rmax: rmax, k: smooth ? 1 : 1 + 1 / (rmax + 3), sq: shape() === 'square', half: (s - 1) / 2 + 1e-6 };
  }
  /** -1 outside the tip, else 0 (centre) .. ~1 (edge) */
  function tipAt(T0, px, py) {
    var dx = px - T0.cx, dy = py - T0.cy;
    if (T0.sq) { if (Math.abs(dx) > T0.half || Math.abs(dy) > T0.half) return -1; return Math.max(Math.abs(dx), Math.abs(dy)) / Math.max(0.5, T0.s / 2); }
    var d = Math.sqrt(dx * dx + dy * dy);
    return d * T0.k < T0.rmax ? d / T0.rmax : -1;
  }
  function footprint(x, y, size, fn) {
    var T0 = tip(x, y, size), R = Math.ceil(T0.rmax) + 1;
    for (var py = Math.floor(T0.cy) - R; py <= Math.ceil(T0.cy) + R; py++) for (var px = Math.floor(T0.cx) - R; px <= Math.ceil(T0.cx) + R; px++) {
      var dn = tipAt(T0, px, py); if (dn >= 0) fn(px, py, dn);
    }
  }

  // ---- sides: mirror painting calls the brush on the other side too, and a stroke can jump to another face: each
  // path keeps its own arc length, direction and dab counter
  function sideFor(x, y) {
    var best = null, bd = 1e9;
    T.sides.forEach(function (s) { var d = Math.abs(s.last[0] - x) + Math.abs(s.last[1] - y); if (d < bd) { bd = d; best = s; } });
    if (best && bd <= 8) return best;
    var s = { idx: T.sides.length + (S.targets.indexOf(T) + 1) * 1000, last: [x, y], arc: 0, next: 0, n: 0, hist: [[x, y]], dir: [1, 0], first: true };
    T.sides.push(s);
    return s;
  }
  function advance(side, x, y) {
    if (side.first) { side.first = false; return; }
    var dx = x - side.last[0], dy = y - side.last[1];
    side.arc += Math.sqrt(dx * dx + dy * dy);
    side.last = [x, y];
    side.hist.push([x, y]); if (side.hist.length > 6) side.hist.shift();
    var h0 = side.hist[0], ex = x - h0[0], ey = y - h0[1], l = Math.sqrt(ex * ex + ey * ey);
    if (l > 0.75) side.dir = [ex / l, ey / l];
  }
  /** deterministic randomness: same stroke seed + same dab + same channel = same number */
  function rnd(side, ch) { return hash3(side.n * 131 + ch * 7, side.idx * 7919 + ch * 31, S.seed); }
  function rndK(side, k, ch) { return hash3(k * 977 + ch * 13, side.idx * 389 + 11, S.seed); }
  /** how many dabs are due since the last call (spacing along the path, the first one at the start) */
  function due(side, size, p, spacing) {
    var sp = spacing || Math.max(1, (p.spacing || 0.3) * size), n = 0;
    while (side.arc >= side.next && n < 64) { n++; side.next += sp; side.n++; }
    return n;
  }
  function taperStart(side, full, p) { return p.taper ? clamp(0.3 + 0.7 * side.arc / Math.max(1, p.taper * full * 1.2), 0.3, 1) : 1; }
  function pickTone(p, r) { var ts = p.tones || [0]; return ts[Math.floor(r * ts.length) % ts.length]; }
  function colourFor(p, side, r) {
    if (p.color === 'rainbow') {
      var h = L.rgbToHsv(S.c1[0], S.c1[1], S.c1[2]);
      return L.hsvToRgb(h.h + side.arc * 6, h.s < 0.15 ? 0.85 : h.s, h.v < 0.25 ? 0.9 : h.v);
    }
    if (p.color === 'light') return lerp3(S.c1, WHITE, 0.5);
    if (p.tones) return tone(S.c1, pickTone(p, r) * varyMul());
    return S.c1;
  }
  function bitmapFor(p, sz) { return BITMAP[p.mask] && sz <= 5.5 ? BITMAP[p.mask](sz) : null; }
  function charColour(ch, base) {
    switch (ch) {
      case '+': return tone(base, 2);
      case '-': return tone(base, -1);
      case 'x': return tone(base, -4);
      case 'o': return S.c2;
      case 'w': return lerp3(base, WHITE, 0.85);
      default: return base;
    }
  }
  function stamp(p, cx, cy, sz, ang, base, side) {
    var op = S.opacity, blend = p.blend || 'normal', bm = bitmapFor(p, sz);
    if (bm) {
      var hh = bm.length, ww = bm[0].length, q = (p.rot === 'random' || p.rot === 'follow') ? (Math.round(ang / (Math.PI / 2)) & 3) : 0;
      for (var yy = 0; yy < hh; yy++) for (var xx = 0; xx < ww; xx++) {
        var ch = bm[yy][xx]; if (ch === '.') continue;
        var pt = rot90(xx - Math.floor((ww - 1) / 2), yy - Math.floor((hh - 1) / 2), q);
        paint(Math.round(cx) + pt[0], Math.round(cy) + pt[1], op, charColour(ch, base), blend, 2, 1, side.idx);
      }
      return;
    }
    var R = sz / 2, mask = MASK[p.mask] || MASK.disc;
    for (var py = Math.floor(cy - R - 1); py <= Math.ceil(cy + R + 1); py++) for (var px = Math.floor(cx - R - 1); px <= Math.ceil(cx + R + 1); px++) {
      var uv = unrot((px - cx) / R, (py - cy) / R, ang), m = mask(uv[0], uv[1], R);
      var cov = typeof m === 'object' ? m.c : m; if (!cov) continue;
      var col = typeof m === 'object' && m.k ? (m.k === 'c2' ? S.c2 : m.k === 'w' ? lerp3(base, WHITE, 0.85) : tone(base, m.k)) : base;
      paint(px, py, op, col, blend, 2, 1, side.idx);
    }
  }
  function sparklePixel(px, py, size, side) {
    var s = size >= 12 ? 5 : size >= 6 ? 3 : 1;
    stamp({ mask: 'sparkle', rot: 'none' }, px, py, s, 0, WHITE, side);
  }
  /** pixel-perfect 1px line: an L-shaped corner pixel is taken back (unless the stroke had painted it before) */
  function pixelPerfect(side, x, y, col) {
    x = Math.round(x); y = Math.round(y);
    var H = side.pp || (side.pp = []), last = H[H.length - 1];
    if (last && last[0] === x && last[1] === y) return;
    var had = T.rec.has(keyOf(x, y));
    paint(x, y, S.opacity, col, 'normal', 2, 1, side.idx);
    H.push([x, y, had]);
    if (H.length >= 3) {
      var a = H[H.length - 3], b = H[H.length - 2], c = H[H.length - 1];
      if (Math.abs(a[0] - c[0]) === 1 && Math.abs(a[1] - c[1]) === 1 && (b[0] === a[0] || b[1] === a[1]) && (b[0] === c[0] || b[1] === c[1])) {
        if (!b[2]) restore(b[0], b[1]);
        H.splice(H.length - 2, 1);
      }
    }
    if (H.length > 3) H.shift();
  }

  // =================================================================== families
  var FAM = {
    pen: function (p, side, x, y, size) {
      var full = Math.max(1, Math.round(size)), s = Math.max(1, Math.round(full * taperStart(side, full, p))), col = colourFor(p, side, 0);
      side.full = full;
      (side.path || (side.path = [])).push({ x: x, y: y, s: s });
      if (full === 1) { pixelPerfect(side, x, y, col); return; }
      footprint(x, y, s, function (px, py) { paint(px, py, S.opacity, col, 'normal', 2, 1, side.idx); });
    },
    grain: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size)), vary = varyMul();
      footprint(x, y, s, function (px, py, dn) {
        var g = s <= 2 ? hash3(px, py, 77) : 0.55 * hash3(px, py, 77) + 0.45 * valueNoise(px, py, 3, 91);
        if (g > (s <= 2 ? 0.86 : 0.8 - 0.25 * dn * dn)) return;
        var j = hash3(px, py, 133), k = j < 0.14 ? -1 : j > 0.9 ? 1 : 0;
        paint(px, py, S.opacity, tone(S.c1, k * vary), 'normal', 2, 1, side.idx);
      });
    },
    outline: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size)), ow = Math.max(1, Math.round(s / 6)), oc = p.auto ? tone(S.c1, -3) : S.c2;
      if (s === 1) {
        [[1, 0], [-1, 0], [0, 1], [0, -1]].forEach(function (d) { paint(Math.round(x) + d[0], Math.round(y) + d[1], S.opacity, oc, 'normal', 1, 1, side.idx); });
      } else footprint(x, y, s + 2 * ow, function (px, py) { paint(px, py, S.opacity, oc, 'normal', 1, 1, side.idx); });
      footprint(x, y, s, function (px, py) { paint(px, py, S.opacity, S.c1, 'normal', 3, 1, side.idx); });
    },
    mix: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size)), sum = [0, 0, 0], n = 0;
      footprint(x, y, s, function (px, py) { var c = current(px, py); if (c) { sum[0] += c[0]; sum[1] += c[1]; sum[2] += c[2]; n++; } });
      var avg = n ? [sum[0] / n, sum[1] / n, sum[2] / n] : null;
      if (!side.carried) side.carried = avg || S.c1.slice();
      else if (avg) side.carried = lerp3(side.carried, avg, p.pickup);
      var target = lerp3(S.c1, side.carried, p.amount);
      footprint(x, y, s, function (px, py, dn) { var f = 1 - dn * dn; paintMix(px, py, f * f * p.strength * S.opacity, target); });
    },
    softdither: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size));
      footprint(x, y, s, function (px, py, dn) {
        var cov = 1 - dn * dn; cov *= cov;
        if (s <= 2) cov = 1;
        if (L.ditherThreshold('bayer4', px, py, 0) >= cov) return;
        paint(px, py, S.opacity, S.c1, 'normal', 2, cov, side.idx);
      });
    },
    water: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size));
      footprint(x, y, s, function (px, py, dn) {
        var under = original(px, py), col = under ? lerp3(S.c1, under, 0.2) : S.c1;
        var a = (dn > 0.7 ? 0.62 : 0.3) * (0.85 + 0.3 * hash3(px, py, S.seed % 9973 + 5)) * S.opacity;
        paint(px, py, clamp(a, 0, 1), col, 'normal', 2, 1 - dn, side.idx);
      });
    },
    strands: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size)), K = clamp(Math.round(s * (p.density || 0.6)), p.minK || 2, p.maxK || 10), width = s * (p.width || 1);
      if (!side.bristles || side.bristles.length !== K) {
        side.bristles = [];
        for (var k = 0; k < K; k++) {
          side.bristles.push({
            off: K === 1 ? 0 : ((k + 0.5) / K - 0.5) * width + (rndK(side, k, 1) - 0.5) * width / K * 0.8,
            tone: pickTone(p, rndK(side, k, 2))
          });
        }
      }
      var nx = -side.dir[1], ny = side.dir[0], tf = taperStart(side, s, p), vary = varyMul();
      for (var b = 0; b < side.bristles.length; b++) {
        var br = side.bristles[b];
        if (tf < 1 && Math.abs(br.off) > tf * width / 2 + 0.25) continue;
        var bx = x + nx * br.off, by = y + ny * br.off;
        if (p.blend === 'toneUp') paint(bx, by, S.opacity, br.tone, 'toneUp', 2, 1, side.idx);
        else paint(bx, by, S.opacity, tone(S.c1, br.tone * vary), p.blend || 'normal', 2, 1, side.idx);
      }
    },
    blades: function (p, side, x, y, size) {
      var n = due(side, size, p), vary = varyMul(), sc = scatterMul();
      for (var j = 0; j < n; j++) for (var q = 0; q < (p.count || 3); q++) {
        var ch = q * 11 + j * 7, bx = x + (rnd(side, ch + 1) - 0.5) * size * sc, by = y + (rnd(side, ch + 2) - 0.5) * size * 0.3;
        var h = Math.max(1, Math.round(size * (0.35 + 0.65 * rnd(side, ch + 3)))), lean = (rnd(side, ch + 4) - 0.5) * 0.7, tj = rnd(side, ch + 5) < 0.3 ? 1 : 0;
        for (var st = 0; st < h; st++) paint(bx + lean * st, by - st, S.opacity, tone(S.c1, ((st < h * 0.35 ? -1 : st > h * 0.72 ? 1 : 0) + tj) * vary), 'normal', 2, 1, side.idx);
      }
    },
    tufts: function (p, side, x, y, size) {
      var n = due(side, size, p), base = Math.atan2(side.dir[1], side.dir[0]), vary = varyMul(), sc = scatterMul();
      for (var j = 0; j < n; j++) for (var q = 0; q < (p.count || 3); q++) {
        var ch = q * 13 + j * 5, a = base + (rnd(side, ch + 1) - 0.5) * 0.7, len = Math.max(1, size * 0.5 * (0.6 + 0.4 * rnd(side, ch + 2)));
        var sx = x + (rnd(side, ch + 3) - 0.5) * size * 0.6 * sc, sy = y + (rnd(side, ch + 4) - 0.5) * size * 0.6 * sc, tk = pickTone(p, rnd(side, ch + 5));
        for (var st = 0; st < len; st++) paint(sx + Math.cos(a) * st, sy + Math.sin(a) * st, S.opacity, tone(S.c1, (tk + (st > len * 0.66 ? 1 : 0)) * vary), 'normal', 2, 1, side.idx);
      }
    },
    stamp: function (p, side, x, y, size) {
      var vary = Math.min(1, varyMul()), sc = scatterMul(), tileSz = 0, sp = 0;
      if (p.tile) {
        // laid end to end: the next tile starts where this one ends (a hand-pixelled tile: its own width)
        tileSz = Math.max(1, Math.round(size * (p.scale || 1)));
        var bm0 = bitmapFor(p, tileSz);
        sp = bm0 ? bm0[0].length : tileSz;
      }
      var n = due(side, size, p, sp);
      for (var j = 0; j < n; j++) for (var q = 0; q < (p.count || 1); q++) {
        var ch = q * 17 + j * 5, r1 = rnd(side, ch + 1), r2 = rnd(side, ch + 2), r3 = rnd(side, ch + 3), r4 = rnd(side, ch + 4), r5 = rnd(side, ch + 5);
        var sz = tileSz || Math.max(1, size * (p.scale || 1) * (1 - (p.sizeJ || 0) * vary * r1));
        var rad = (p.scatter || 0) * sc * size, a = 2 * Math.PI * r2, dd = rad * Math.sqrt(r3);
        var ang = p.rot === 'follow' ? Math.atan2(side.dir[1], side.dir[0]) : p.rot === 'random' ? r4 * Math.PI * 2 : 0;
        stamp(p, x + Math.cos(a) * dd, y + Math.sin(a) * dd, sz, ang, colourFor(p, side, r5), side);
      }
    },
    spray: function (p, side, x, y, size) {
      var n = due(side, size, p), sc = scatterMul();
      for (var j = 0; j < n; j++) {
        var count = Math.max(1, Math.round((p.count || 4) * Math.max(1, size / 6)));
        for (var q = 0; q < count; q++) {
          var ch = q * 23 + j * 3, a = 2 * Math.PI * rnd(side, ch + 1), dd = (p.scatter || 1) * sc * size * 0.6 * Math.sqrt(rnd(side, ch + 2));
          var px = x + Math.cos(a) * dd, py = y + Math.sin(a) * dd;
          if (rnd(side, ch + 3) < 0.12) sparklePixel(px, py, size, side);
          else paint(px, py, S.opacity, colourFor(p, side, rnd(side, ch + 4)), 'normal', 2, 1, side.idx);
        }
      }
    },
    motif: function (p, side, x, y, size) {
      var w = Math.max(2, size), half = w / 2, period = Math.max(2, (p.period || 1) * w), s = side.arc, ph = (s % period) / period;
      var nx = -side.dir[1], ny = side.dir[0], fn = MOTIF[p.motif];
      for (var l = -half; l <= half + 1e-9; l += 0.5) {
        var m = fn(ph, l / half, s, period, w); if (!m) continue;
        var col = typeof m === 'object' && m.k ? (m.k === 'c2' ? S.c2 : m.k === 'w' ? lerp3(S.c1, WHITE, 0.85) : tone(S.c1, m.k)) : S.c1;
        paint(x + nx * l, y + ny * l, S.opacity, col, 'normal', 2, 1, side.idx);
      }
    },
    pattern: function (p, side, x, y, size) {
      var cell = Math.max(1, Math.round(size / (p.cellDiv || 4))), fn = PATTERN[p.pattern];
      footprint(x, y, size, function (px, py) {
        var m = fn(px, py, cell); if (!m) return;
        if (m === 'c2') { paint(px, py, S.opacity, S.c2, 'normal', 2, 1, side.idx); return; }
        if (typeof m === 'object' && (m.k === 'o+1' || m.k === 'o-1')) { paint(px, py, S.opacity, m.k === 'o+1' ? 1 : -1, 'toneUp', 2, 1, side.idx); return; }
        paint(px, py, S.opacity, typeof m === 'object' && m.k ? tone(S.c1, m.k) : S.c1, 'normal', 2, 1, side.idx);
      });
    },
    glow: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size)), core = lerp3(S.c1, WHITE, 0.75), dither = s <= 10;
      footprint(x, y, s, function (px, py, dn) {
        if (dn <= 0.3) { paint(px, py, S.opacity, core, 'normal', 3, 1, side.idx); return; }
        var cov = dn <= 0.65 ? 1 : clamp((1 - dn) / 0.35, 0, 1);
        if (dither && cov < 1) { if (cov <= L.ditherThreshold('bayer4', px, py, 0)) return; cov = 1; }
        paint(px, py, cov * S.opacity, S.c1, 'lighter', 2, cov, side.idx);
      });
    },
    blush: function (p, side, x, y, size) {
      var s = Math.max(1, Math.round(size));
      footprint(x, y, s, function (px, py, dn) {
        var cov = (1 - dn * dn); cov = cov * cov * 0.45;
        if (s <= 8) { if (cov <= L.ditherThreshold('bayer2', px, py, 0) * 0.45) return; cov = 0.45; }
        paint(px, py, cov * S.opacity, S.c1, 'normal', 1, cov, side.idx);
      });
      var n = due(side, size, p), r = s / 2;
      for (var j = 0; j < n; j++) if (rnd(side, j * 3 + 1) < 0.5) {
        var a = 2 * Math.PI * rnd(side, j * 3 + 2), dd = r * 0.8 * Math.sqrt(rnd(side, j * 3 + 3));
        sparklePixel(x + Math.cos(a) * dd, y + Math.sin(a) * dd, s, side);
      }
    }
  };
  /** end of the stroke: the lineart also thins out at the end (the last dabs are redrawn smaller) */
  function endStroke(p) {
    if (p.fam !== 'pen' || !p.taper) return;
    T.sides.forEach(function (side) {
      var P0 = side.path, full = side.full || 1; if (!P0 || P0.length < 2 || full < 2) return;
      var len = p.taper * full * 1.2, acc = 0, cand = new Map(), changed = false;
      for (var i = P0.length - 1; i >= 0 && acc <= len; i--) {
        var ns = Math.max(1, Math.round(full * clamp(0.3 + 0.7 * acc / len, 0.3, 1)));
        if (ns < P0[i].s) {
          footprint(P0[i].x, P0[i].y, P0[i].s, function (px, py) { cand.set(keyOf(px, py), [px, py]); });
          P0[i].s = ns; changed = true;
        }
        if (i > 0) { var dx = P0[i].x - P0[i - 1].x, dy = P0[i].y - P0[i - 1].y; acc += Math.sqrt(dx * dx + dy * dy); }
      }
      if (!changed) return;
      var tips = P0.map(function (q) { return tip(q.x, q.y, q.s); });
      cand.forEach(function (c, key) {
        var r = T.rec.get(key); if (!r || r.side !== side.idx) return;
        for (var j = 0; j < tips.length; j++) {
          var T0 = tips[j]; if (Math.abs(T0.cx - c[0]) > T0.rmax + 1 || Math.abs(T0.cy - c[1]) > T0.rmax + 1) continue;
          if (tipAt(T0, c[0], c[1]) >= 0) return;
        }
        restore(c[0], c[1]);
      });
    });
  }
  /** one Blockbench Brush API 'draw' call (Blockbench calls it for every pixel along the stroke: interval 1) */
  function draw(o) {
    if (!S || !o || !o.texture) return;
    var tg = targetFor(o.texture); if (!tg) return;
    T = tg;
    var p = preset(), size = Math.max(1, o.size || 1), x = o.x, y = o.y;
    if (T.tex.layers_enabled && T.src && typeof T.src.expandTo === 'function') {
      // scattered stamps can land farther than the brush size: let the layer grow there too
      var reach = Math.ceil(size * (1.5 + (p.scatter || 0) * scatterMul())) + 3;
      try { T.src.expandTo([x - reach, y - reach], [x + reach, y + reach]); } catch (e) { }
    }
    syncTarget(T);
    T.area = faceArea();
    var side = sideFor(x, y);
    advance(side, x, y);
    try { FAM[p.fam](p, side, x, y, size); } catch (e) { L.warn('brush ' + p.id + ' failed', e); }
    flush();
  }
  function end() {
    if (S) {
      var p = preset();
      S.targets.forEach(function (tg) { T = tg; syncTarget(tg); try { endStroke(p); } catch (e) { L.warn('brush end failed', e); } flush(); });
    }
    S = null; T = null;
  }

  // =================================================================== virtual strokes (library previews, QA)
  var PREVIEW_SIZE = { pen: 3, grain: 2, outline: 2, mix: 5, softdither: 5, water: 5, strands: 4, stamp: 4, spray: 4, blades: 4, tufts: 4, motif: 4, pattern: 8, glow: 3, blush: 5 };
  /** runs a brush on a virtual w x h image along pts; returns the ImageData */
  function virtualStroke(p, w, h, size, pts, c1, c2, fill, seed) {
    var savedS = S, savedT = T;
    try {
      S = { seed: seed, c1: c1, c2: c2, opacity: 1, targets: [] };
      T = virtualTarget(w, h, fill);
      S.targets.push(T);
      pts.forEach(function (pt) { var side = sideFor(pt[0], pt[1]); advance(side, pt[0], pt[1]); FAM[p.fam](p, side, pt[0], pt[1], size); });
      endStroke(p);
      return T.img;
    } finally { S = savedS; T = savedT; }
  }
  function previewPath(w, h) {
    var pts = [], amp = h / 2 - 6;
    for (var x = 5; x <= w - 5; x++) pts.push([x, Math.round(h / 2 + Math.sin((x - 5) / (w - 10) * Math.PI * 2) * amp)]);
    return pts;
  }
  function previewFill(c1, p) {
    // brushes that work over paint (shine, grain, paper): a band of colour 1 to work on
    if (p && p.onPaint) { var mid = tone(c1, -1), lo = tone(c1, -2); return function (x, y) { return y < 12 ? mid : lo; }; }
    // dark background for light colours and the other way round; two tones so blending brushes show something
    var dark = luminance(c1) > 0.42, top = dark ? [78, 84, 98] : [214, 218, 224], bottom = dark ? [52, 56, 66] : [178, 184, 194];
    return function (x, y) { return y < 12 ? top : bottom; };
  }
  var previewCache = {};
  function renderPreview(id, c1, c2) {
    var p = BY_ID[id], w = 64, h = 24, key = id + '|' + c1.join(',') + '|' + c2.join(',');
    if (previewCache[key]) return previewCache[key];
    var canvas = document.createElement('canvas'); canvas.width = w; canvas.height = h;
    var ctx = canvas.getContext('2d'), fill = previewFill(c1, p);
    try {
      var img = p.fam === 'native' ? virtualStroke({ fam: 'pen' }, w, h, 1, previewPath(w, h), c1, c2, fill, 4242)
        : virtualStroke(p, w, h, PREVIEW_SIZE[p.fam] || 4, previewPath(w, h), c1, c2, fill, 4242);
      ctx.putImageData(img, 0, 0);
    } catch (e) { L.warn('preview of ' + id + ' failed', e); }
    return (previewCache[key] = canvas.toDataURL());
  }

  // =================================================================== Blockbench UI
  var ui = {};
  function setPreset(id) {
    var p = BY_ID[id]; if (!p || p.fam === 'native') return false;
    L.setOpt('br.preset', id);
    try { if (ui.preset) ui.preset.set(id); } catch (e) { }
    L.refreshBrushBar();
    return true;
  }
  /** 'Pixel 1px': Blockbench's own pencil at 1px with pixel-perfect on */
  function pickPixelPen() {
    L.selectTool('brush_tool');
    try {
      G.BarItems.slider_brush_size.setValue(1);
      G.BarItems.slider_brush_softness.setValue(0);
      if (G.BarItems.pixel_perfect_drawing && !G.BarItems.pixel_perfect_drawing.value) G.BarItems.pixel_perfect_drawing.set(true);
    } catch (e) { L.warn('could not set up the pixel pen', e); }
    return true;
  }
  function pick(id) {
    var p = BY_ID[id]; if (!p) return false;
    if (p.fam === 'native') return pickPixelPen();
    setPreset(id);
    L.selectTool(TOOL);
    L.refreshBrushBar();
    return true;
  }
  var libDialog = null;
  function openLibrary() {
    var c1 = L.primary(), c2 = L.secondary(), cur = L.opt('br.preset', 'lapis');
    var model = {
      sel: L.isTool(TOOL) ? cur : '',
      groups: GROUPS.map(function (g) {
        return {
          id: g, name: t('cpmplus.art.br.g.' + g),
          items: PRESETS.filter(function (p) { return p.group === g; }).map(function (p) {
            return { id: p.id, name: t('cpmplus.art.br.p.' + p.id), desc: t('cpmplus.art.br.p.' + p.id + '.desc'), img: renderPreview(p.id, c1, c2) };
          })
        };
      })
    };
    var dlg = new G.Dialog({
      id: 'cpmplus_art_brush_library', title: t('cpmplus.art.br.lib.title'), width: 900, singleButton: true,
      component: {
        data: function () { return model; },
        methods: { pick: function (id) { pick(id); this.sel = id; dlg.close(0); } },
        template: [
          '<div class="cpmplus_art_brlib">',
          '<p class="cpmplus_art_hint">' + L.esc(t('cpmplus.art.br.lib.intro')) + '</p>',
          '<div class="cpmplus_art_brscroll">',
          '<div v-for="g in groups" :key="g.id"><h4>{{g.name}}</h4><div class="cpmplus_art_brgrid">',
          '<div v-for="b in g.items" :key="b.id" class="cpmplus_art_brcard" :class="{active: b.id===sel}" :title="b.desc" :data-id="b.id" @click="pick(b.id)">',
          '<img :src="b.img" width="128" height="48" alt=""><span>{{b.name}}</span></div>',
          '</div></div></div></div>'
        ].join('')
      }
    });
    dlg.show();
    libDialog = dlg;
    return dlg;
  }
  var STYLE = 'cpmplus_art_brlib_style';
  function installStyle() {
    if (document.getElementById(STYLE)) return;
    var st = document.createElement('style'); st.id = STYLE;
    st.textContent = [
      '.cpmplus_art_brscroll{max-height:calc(100vh - 260px);overflow-y:auto;overflow-x:hidden;padding-right:4px}',
      '.cpmplus_art_brlib h4{margin:10px 0 4px}',
      '.cpmplus_art_brgrid{display:flex;flex-wrap:wrap;gap:6px}',
      '.cpmplus_art_brcard{width:134px;padding:3px;border:1px solid var(--color-border);border-radius:4px;cursor:pointer;display:flex;flex-direction:column;align-items:center;box-sizing:border-box}',
      '.cpmplus_art_brcard:hover{border-color:var(--color-accent)}',
      '.cpmplus_art_brcard.active{border-color:var(--color-accent);background:var(--color-selected)}',
      '.cpmplus_art_brcard img{image-rendering:pixelated;display:block;width:128px;height:48px}',
      '.cpmplus_art_brcard span{font-size:.92em;margin-top:2px;text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:128px}'
    ].join('\n');
    document.head.appendChild(st);
    L.onUninstall(function () { var e = document.getElementById(STYLE); if (e) e.remove(); });
  }
  /**
   * one-time toast for people who already saw the beta29 'new art tools' toast (new users get that one, which now
   * lists the Brushes too); never in the QA browser (it would cover other notices there)
   */
  function installHint() {
    if (L.opt('br.hint', false)) return;
    if (!L.artHintSeen()) { L.setOpt('br.hint', true); return; }
    function onMode(e) {
      try {
        if (L.test() || L.opt('br.hint', false)) return;
        var mode = e && e.mode ? e.mode : null, id = mode && mode.id ? mode.id : (G.Modes && G.Modes.selected && G.Modes.selected.id);
        if (id !== 'paint') return;
        L.setOpt('br.hint', true);
        G.Blockbench.showToastNotification({ text: t('cpmplus.art.br.hint'), icon: 'brush', expire: 20000 });
      } catch (x) { }
    }
    try { G.Blockbench.on('select_mode', onMode); L.onUninstall(function () { G.Blockbench.removeListener('select_mode', onMode); }); } catch (e) { }
  }
  api.install = function () {
    installStyle();
    installHint();
    var b = L.brushBase();
    b.interval = 1;
    b.blend_modes = false;
    b.pixel_perfect = false;// the 1px lineart is pixel-perfect on its own (Blockbench's version writes behind our buffers)
    b.screen_space = false;// painted in texture space: face clamp + mirror painting stay on
    b.onStrokeStart = function (o) { begin(o); };
    b.onStrokeEnd = function () { end(); };
    b.draw = function (o) { draw(o); };
    ui.tool = L.makeTool(TOOL, 'cpmplus.art.br', 'brush', b);
    var on = function () { return L.isTool(TOOL); };
    var opts = {};
    PAINTABLE.forEach(function (p) { opts[p.id] = t('cpmplus.art.br.p.' + p.id); });
    var saved = BY_ID[L.opt('br.preset', 'lapis')], start = saved && saved.fam !== 'native' ? saved.id : 'lapis';
    ui.preset = L.mk('BarSelect', 'cpmplus_art_br_preset', {
      name: t('cpmplus.art.br.preset'), description: t('cpmplus.art.br.preset.desc'), category: 'paint', condition: on, options: opts, value: start,
      onChange: function (sel) { L.setOpt('br.preset', sel.value); L.refreshBrushBar(); }
    });
    try { ui.preset.set(start); } catch (e) { }
    ui.library = L.mk('Action', 'cpmplus_art_br_library', {
      name: t('cpmplus.art.br.lib.menu'), description: t('cpmplus.art.br.lib.menu.desc'), icon: 'auto_awesome_mosaic', category: 'paint', condition: on,
      click: function () { openLibrary(); }
    });
    ui.scatter = L.slider('cpmplus_art_br_scatter', 'cpmplus.art.br.scatter', 0, 200, 100, 'br.scatter', function () { return on() && !!USES_SCATTER[preset().fam]; });
    ui.vary = L.slider('cpmplus_art_br_vary', 'cpmplus.art.br.vary', 0, 200, 100, 'br.vary', function () { return on() && !!USES_VARY[preset().fam]; });
    L.place('tools', ui.tool, 'cpmplus_art_noise');
    var after = 'cpmplus_art_noise_cover';
    [ui.library, ui.preset, ui.scatter, ui.vary].forEach(function (it) { L.place('brush', it, after); after = it.id; });
  };
  api.uninstall = function () { S = null; T = null; previewCache = {}; try { if (libDialog) libDialog.close(); } catch (e) { } libDialog = null; };
  api.openLibrary = openLibrary;
  api.pick = pick;
  api.setPreset = setPreset;
  api.groupOf = function (id) { return BY_ID[id] ? BY_ID[id].group : null; };
  api.familyOf = function (id) { return BY_ID[id] ? BY_ID[id].fam : null; };
  /** QA: fixed random seed for the next strokes (null = random again) */
  api.setSeed = function (n) { fixedSeed = n; };
  api.preview = function (id) { return renderPreview(id, L.primary(), L.secondary()); };
  /** QA: a brush on a virtual w x h image along pts -> RGBA array (o: {c1, c2, base [r,g,b,a], seed}) */
  api.virtualStroke = function (id, w, h, size, pts, o) {
    var p = BY_ID[id]; if (!p || p.fam === 'native') return null;
    o = o || {};
    var b = o.base || [70, 76, 88, 255];
    var img = virtualStroke(p, w, h, size, pts, o.c1 || L.primary(), o.c2 || L.secondary(), function () { return b; }, o.seed !== undefined ? o.seed : (fixedSeed !== null ? fixedSeed : 4242));
    return Array.from(img.data);
  };
  return api;
})
