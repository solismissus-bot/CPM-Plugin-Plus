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
    // foliage tuft: a ragged blob in one flat colour
    foliage: function (u, v) {
      var d = Math.sqrt(u * u + v * v), a = Math.atan2(v, u);
      return d <= 0.66 + 0.12 * Math.sin(5 * a + 0.7) + 0.16 * hash3(Math.floor((a + Math.PI) * 3.2), 17, 5) ? 1 : 0;
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
    foliage: function (s) { return s <= 2.5 ? ['#'] : s <= 4.5 ? ['.#.#', '####', '.##.'] : ['.#.#.', '#####', '#####', '.#.#.']; },
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

  // =================================================================== HD brush tips (beta32): drawn here with Canvas2D, original designs
  // A tip is a 128x128 image: alpha = coverage, red = tone (128 = the brush colour, lighter above, darker below),
  // green = how much of colour 2 (flower centre, scar stitches). Mipmapped, sampled bilinearly at any size / angle.
  // 'Follow' tips are drawn pointing right (+x = along the stroke); upright tips have their top at y = 0.
  var TIP_N = 128;
  function tf(tn, a, c2) { return 'rgba(' + Math.round(clamp(128 + tn * 127, 0, 255)) + ',' + (c2 ? 255 : 0) + ',0,' + (a === undefined ? 1 : a) + ')'; }
  function seeded(seed) { var s = (seed >>> 0) || 1; return function () { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296; }; }
  function circle(g, x, y, r) { g.beginPath(); g.arc(x, y, Math.max(0.1, r), 0, Math.PI * 2); g.fill(); }
  /** a filled stroke along a polyline whose width goes from w0 to w1 (tapered strands, blades) */
  function taper(g, pts, w0, w1) {
    var L = [], R = [], n = pts.length;
    for (var i = 0; i < n; i++) {
      var a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)], dx = b[0] - a[0], dy = b[1] - a[1], l = Math.sqrt(dx * dx + dy * dy) || 1;
      var w = (w0 + (w1 - w0) * i / (n - 1)) / 2, nx = -dy / l * w, ny = dx / l * w;
      L.push([pts[i][0] + nx, pts[i][1] + ny]); R.push([pts[i][0] - nx, pts[i][1] - ny]);
    }
    g.beginPath(); g.moveTo(L[0][0], L[0][1]);
    for (var j = 1; j < n; j++) g.lineTo(L[j][0], L[j][1]);
    for (var k = n - 1; k >= 0; k--) g.lineTo(R[k][0], R[k][1]);
    g.closePath(); g.fill();
  }
  function quad(p0, p1, p2, n) { var out = []; for (var i = 0; i <= n; i++) { var s = i / n, a = (1 - s) * (1 - s), b = 2 * (1 - s) * s, c = s * s; out.push([a * p0[0] + b * p1[0] + c * p2[0], a * p0[1] + b * p1[1] + c * p2[1]]); } return out; }
  function lin(g, x0, y0, x1, y1, stops) { var gr = g.createLinearGradient(x0, y0, x1, y1); stops.forEach(function (s) { gr.addColorStop(s[0], s[1]); }); return gr; }
  function rad(g, x0, y0, r0, x1, y1, r1, stops) { var gr = g.createRadialGradient(x0, y0, r0, x1, y1, r1); stops.forEach(function (s) { gr.addColorStop(s[0], s[1]); }); return gr; }
  var TIPS = {
    round: function (g, N) { g.fillStyle = tf(0); circle(g, N / 2, N / 2, N / 2 - 1.5); },
    soft: function (g, N) { g.fillStyle = rad(g, N / 2, N / 2, 0, N / 2, N / 2, N / 2, [[0, tf(0, 1)], [0.35, tf(0, 0.8)], [0.7, tf(0, 0.3)], [1, tf(0, 0)]]); g.fillRect(0, 0, N, N); },
    // graphite: solid in the middle, thinning out toward the edge (the paper grain shows there first)
    pencil: function (g, N) { g.fillStyle = rad(g, N / 2, N / 2, 0, N / 2, N / 2, N / 2 - 2, [[0, tf(0, 1)], [0.5, tf(0, 0.92)], [0.8, tf(0, 0.5)], [1, tf(0, 0)]]); g.fillRect(0, 0, N, N); },
    // render / gouache: covers in the middle, a short soft edge
    render: function (g, N) { g.fillStyle = rad(g, N / 2, N / 2, 0, N / 2, N / 2, N / 2 - 1, [[0, tf(0, 1)], [0.62, tf(0, 1)], [0.85, tf(0, 0.55)], [1, tf(0, 0)]]); g.fillRect(0, 0, N, N); },
    // watercolour: thin in the middle, more pigment at the rim
    water: function (g, N) { g.fillStyle = rad(g, N / 2, N / 2, 0, N / 2, N / 2, N / 2 - 1, [[0, tf(0, 0.32)], [0.72, tf(0, 0.36)], [0.9, tf(-0.25, 0.8)], [0.97, tf(-0.3, 0.55)], [1, tf(0, 0)]]); g.fillRect(0, 0, N, N); },
    // a flat brush loaded with paint (oil / gouache): a solid body with bristle streaks along the stroke, ragged ends
    bristle: function (g, N, R) {
      g.fillStyle = tf(0, 0.75); g.fillRect(N / 2 - 12, 12, 24, N - 24);
      for (var i = 0; i < 34; i++) {
        var y = 9 + i * (N - 18) / 33 + (R() - 0.5) * 2, edge = Math.min(y - 9, N - 9 - y) / (N / 2 - 9);
        g.fillStyle = tf((R() - 0.5) * 1.3, edge < 0.12 ? 0.45 + R() * 0.3 : 0.8 + R() * 0.2);
        g.beginPath(); g.ellipse(N / 2 + (R() - 0.5) * 6, y, 10 + R() * 12, 1.6 + R() * 1.6, 0, 0, Math.PI * 2); g.fill();
      }
    },
    // hair: strands side by side across the stroke, light and dark
    hair: function (g, N) { var T = [-0.6, 0.1, 0.5, -0.1, 0.35, -0.4, 0.2, -0.6]; for (var i = 0; i < T.length; i++) { g.fillStyle = tf(T[i], 0.95); circle(g, N / 2, 10 + i * (N - 20) / (T.length - 1), 5.2); } },
    hairshine: function (g, N) { [44, 64, 84].forEach(function (y, i) { g.fillStyle = tf(0, i === 1 ? 1 : 0.75); circle(g, N / 2, y, i === 1 ? 6 : 4.5); }); },
    curl: function (g, N) {
      for (var s = 0; s <= 1.0001; s += 0.006) {
        var a = -Math.PI * 0.3 + s * Math.PI * 2.1, r = 50 - s * 30, x = N / 2 + Math.cos(a) * r, y = N / 2 + Math.sin(a) * r * 0.85;
        g.fillStyle = tf(Math.sin(a) < 0 ? 0.45 : -0.35, 1); circle(g, x, y, 2.5 + (1 - Math.abs(s - 0.35) * 1.4) * 6);
      }
    },
    // a lock of fur: many fine hairs side by side (traced along the stroke), the outer ones thinner and see-through
    fur: function (g, N, R) {
      var K = 32;
      for (var i = 0; i < K; i++) {
        var q = (i + 0.5) / K * 2 - 1, y = N / 2 + q * (N / 2 - 8) + (R() - 0.5) * 3, e = 1 - Math.abs(q);
        g.fillStyle = tf((R() - 0.5) * 1.0 + 0.2 * e, 0.2 + 0.7 * Math.sqrt(e) * (0.6 + 0.4 * R()));
        circle(g, N / 2 + (R() - 0.5) * 10, y, 1.1 + 1.6 * e * R() + 0.6);
      }
    },
    // feather pointing forward: a light shaft, fine barbs angled toward the tip, a narrower front vane, splits and down
    feather: function (g, N, R) {
      function shaftY(x) { return 64 + 6 * Math.sin((x / N) * Math.PI) - 3; }
      function vane(x, side) { var s = (x - 6) / (N - 10); return (side < 0 ? 28 : 38) * Math.sin(Math.PI * Math.pow(clamp(s, 0, 1), 0.75)) * (s > 0.8 ? (1 - s) / 0.2 * 0.6 + 0.4 : 1); }
      var splits = [0.34 + R() * 0.1, 0.6 + R() * 0.12];
      g.lineCap = 'round';
      for (var x = 6; x < N - 16; x += 1.5) {
        var s = (x - 6) / (N - 10), y0 = shaftY(x);
        [-1, 1].forEach(function (side) {
          if (splits.some(function (sp) { return Math.abs(s - sp) < 0.012 + (side > 0 ? 0.01 : 0); }) && R() < 0.85) return;
          var h = vane(x, side) * (0.9 + 0.12 * R()), fw = h * (0.5 + 0.1 * R()), down = s < 0.16;
          g.strokeStyle = lin(g, x, y0, x + fw, y0 + side * h, [[0, tf(0.25, down ? 0.5 : 0.95)], [0.7, tf(-0.05, down ? 0.4 : 0.9)], [1, tf(-0.45, down ? 0.15 : 0.6)]]);
          g.lineWidth = down ? 1.1 : 1.5;
          g.beginPath(); g.moveTo(x, y0); g.quadraticCurveTo(x + fw * 0.3, y0 + side * h * 0.6, x + fw, y0 + side * h); g.stroke();
        });
      }
      g.fillStyle = tf(0.85, 1); var pts = []; for (var x2 = 2; x2 <= N - 6; x2 += 4) pts.push([x2, shaftY(x2)]); taper(g, pts, 4.2, 0.8);
    },
    // grass tuft: blades rising from the bottom, dark at the root, light at the tips
    grass: function (g, N, R) {
      for (var i = 0; i < 7; i++) {
        var bx = 24 + i * 13 + (R() - 0.5) * 6, h = 64 + R() * 58, lean = (R() - 0.5) * 46, w = 7 + R() * 4;
        var p0 = [bx, N - 2], p2 = [bx + lean, N - 2 - h], p1 = [bx + lean * 0.15, N - 2 - h * 0.55];
        g.fillStyle = lin(g, p0[0], p0[1], p2[0], p2[1], [[0, tf(-0.75)], [0.45, tf(-0.05)], [1, tf(0.65)]]);
        taper(g, quad(p0, p1, p2, 18), w, 0.3);
      }
    },
    // foliage tuft (the community's favourite grass / bush brush): ONE flat colour, a ragged leafy outline of little
    // points and lobes, a slightly soft edge and a lightly see-through, mottled inside, so tufts painted over each
    // other show a little variation; volume comes from painting a lighter colour on top (4 variants)
    foliage: function (g, N, R) {
      function blob(x, y, r, n, inner) {
        g.beginPath();
        for (var i = 0; i < n; i++) {
          var a = i / n * Math.PI * 2, rr = r * (i % 2 ? inner + (1 - inner) * 0.3 * R() : 0.85 + 0.15 * R());
          g[i ? 'lineTo' : 'moveTo'](x + Math.cos(a) * rr, y + Math.sin(a) * rr);
        }
        g.closePath(); g.fill();
      }
      // a serrated clump: k sharp points of uneven length (leaf tips) between short notches
      function spiky(x, y, r, k, sy) {
        g.beginPath();
        for (var i = 0; i < k; i++) {
          var a = i / k * Math.PI * 2, rr = r * (0.78 + (i % 2 ? 0.24 + 0.46 * R() * R() : -0.08 * R()));
          g[i ? 'lineTo' : 'moveTo'](x + Math.cos(a) * rr, y + Math.sin(a) * rr * sy);
        }
        g.closePath(); g.fill();
      }
      // the silhouette of a little bush: a wide clump at the base and a few smaller ones on it, peaks mostly on top
      g.filter = 'blur(0.4px)';
      g.fillStyle = tf(0, 1);
      spiky(64, 76, 28 + R() * 5, 40, 0.74);
      for (var j = 0, n = 6 + Math.floor(R() * 4); j < n; j++) {
        var ax = 64 + (R() - 0.5) * 60, ay = 60 + (R() - 0.72) * 40 * (1 - Math.abs(ax - 64) / 52);
        spiky(ax, ay, 9 + R() * 8, 20 + Math.floor(R() * 10), 0.95);
      }
      // a lightly mottled inside (it stays almost opaque, like the original)
      g.globalCompositeOperation = 'destination-out';
      for (var m = 0; m < 7; m++) { g.fillStyle = 'rgba(0,0,0,' + (0.05 + 0.07 * R()).toFixed(2) + ')'; blob(64 + (R() - 0.5) * 60, 66 + (R() - 0.5) * 44, 7 + R() * 10, 12, 0.6); }
      g.globalCompositeOperation = 'source-over';
      g.filter = 'none';
    },
    // leaf pointing forward: blade with light upper half, darker lower half, midrib and veins
    leaf: function (g, N) {
      g.fillStyle = lin(g, 0, 22, 0, 106, [[0, tf(0.45)], [0.5, tf(0.05)], [1, tf(-0.45)]]);
      g.beginPath(); g.moveTo(5, 64); g.bezierCurveTo(28, 14, 96, 16, 124, 64); g.bezierCurveTo(96, 112, 28, 114, 5, 64); g.fill();
      g.strokeStyle = tf(-0.6, 0.9); g.lineWidth = 3; g.beginPath(); g.moveTo(8, 64); g.quadraticCurveTo(64, 60, 120, 64); g.stroke();
      g.lineWidth = 1.6; g.strokeStyle = tf(-0.35, 0.8);
      [30, 50, 70, 90].forEach(function (x) { g.beginPath(); g.moveTo(x, 63); g.lineTo(x + 16, 63 - 26 * Math.sin(Math.PI * x / 128)); g.moveTo(x, 65); g.lineTo(x + 16, 65 + 26 * Math.sin(Math.PI * x / 128)); g.stroke(); });
    },
    // five petals, colour 2 in the middle
    flower: function (g, N) {
      g.save(); g.translate(N / 2, N / 2);
      for (var k = 0; k < 5; k++) {
        g.save(); g.rotate(k * Math.PI * 2 / 5);
        g.fillStyle = lin(g, 0, -10, 0, -60, [[0, tf(-0.35)], [0.6, tf(0.15)], [1, tf(0.5)]]);
        g.beginPath(); g.ellipse(0, -34, 20, 28, 0, 0, Math.PI * 2); g.fill();
        g.restore();
      }
      g.fillStyle = rad(g, -4, -4, 2, 0, 0, 17, [[0, tf(0.5, 1, true)], [1, tf(-0.4, 1, true)]]); circle(g, 0, 0, 16);
      g.restore();
    },
    // painted cloud: a lumpy 'cauliflower' outline made of many small bumps, flat colour lit from the top, a little
    // darker toward the flat base, a few lighter bumps on top (4 variants)
    cloud: function (g, N, R) {
      var B = [], cx = 64, by = 98;
      B.push([cx, 80, 30 + R() * 4]);
      for (var i = 0; i < 15; i++) {
        var a = Math.PI * (1.04 + 0.92 * i / 14) + (R() - 0.5) * 0.12, rx = 42 + R() * 6, ry = 34 + R() * 10;
        B.push([cx + Math.cos(a) * rx, 78 + Math.sin(a) * ry, 9 + R() * 11]);
      }
      for (var j = 0; j < 7; j++) B.push([22 + j * 14 + (R() - 0.5) * 4, by - 4 + R() * 3, 8 + R() * 5]);
      g.fillStyle = lin(g, 0, 18, 0, 112, [[0, tf(0.5)], [0.55, tf(0.12)], [1, tf(-0.42)]]);
      B.forEach(function (c) { circle(g, c[0], Math.min(c[1], by - c[2] * 0.4), c[2]); });
      g.globalCompositeOperation = 'source-atop';
      for (var k = 1; k < B.length; k += 2) { var c = B[k]; if (c[1] > 70) continue; g.fillStyle = rad(g, c[0] - c[2] * 0.3, c[1] - c[2] * 0.35, 0, c[0], c[1], c[2], [[0, tf(0.8, 0.55)], [1, tf(0.5, 0)]]); circle(g, c[0], c[1], c[2]); }
      g.fillStyle = tf(-0.5, 0.7); g.fillRect(0, by + 2, N, N);
      g.globalCompositeOperation = 'source-over';
    },
    // flame ribbon (the fire brush): a hot core across the stroke, white-yellow in the middle, orange at the edge
    // (the 'warm' tones turn the colour into yellow-white above, deep red below)
    // (the tones change only across the stroke, so the dabs on top of each other keep a white-hot line in the middle)
    flamecore: function (g, N) {
      g.fillStyle = lin(g, 0, 2, 0, N - 2, [[0, tf(-0.5, 0)], [0.16, tf(-0.3, 0.45)], [0.34, tf(0.25, 0.9)], [0.5, tf(1, 1)], [0.66, tf(0.25, 0.9)], [0.84, tf(-0.3, 0.45)], [1, tf(-0.5, 0)]]);
      g.fillRect(0, 0, N, N);
      g.globalCompositeOperation = 'destination-in';
      g.fillStyle = rad(g, N / 2, N / 2, 0, N / 2, N / 2, N / 2 - 1, [[0, 'rgba(0,0,0,1)'], [0.6, 'rgba(0,0,0,1)'], [1, 'rgba(0,0,0,0)']]); g.fillRect(0, 0, N, N);
      g.globalCompositeOperation = 'source-over';
    },
    // a thin tongue of flame licking away from its root (left) to a curled, see-through tip (right): yellow root,
    // red tip (3 variants)
    tongue: function (g, N, R) {
      var bend = (R() - 0.25) * 60, pts = quad([6, 64], [60, 64 + bend], [122, 64 + bend * 0.2 + (R() - 0.5) * 24], 24);
      g.filter = 'blur(2.5px)';
      g.fillStyle = lin(g, 6, 64, 122, 64, [[0, tf(0.6, 0.85)], [0.4, tf(0, 0.75)], [1, tf(-0.9, 0.2)]]);
      taper(g, pts, 18 + R() * 8, 1);
      g.filter = 'none';
    },
    // splatter: an uneven blob with droplets around (4 variants)
    splat: function (g, N, R) {
      g.fillStyle = tf(-0.1);
      g.beginPath();
      var n = 26, base = 22 + R() * 6;
      for (var i = 0; i <= n; i++) { var a = i / n * Math.PI * 2, r = base + (R() - 0.3) * 10 + (i % 5 === 0 ? R() * 12 : 0); g[i ? 'lineTo' : 'moveTo'](64 + Math.cos(a) * r, 64 + Math.sin(a) * r); }
      g.fill();
      for (var k = 0; k < 9; k++) { var a2 = R() * Math.PI * 2, d = 34 + R() * 26, rr = 2 + R() * 6; g.fillStyle = tf(0); circle(g, 64 + Math.cos(a2) * d, 64 + Math.sin(a2) * d, rr); }
      for (var m = 0; m < 3; m++) { var a3 = R() * Math.PI * 2; g.save(); g.translate(64, 64); g.rotate(a3); g.fillStyle = tf(0); g.beginPath(); g.ellipse(36, 0, 18, 3, 0, 0, Math.PI * 2); g.fill(); g.restore(); }
      g.fillStyle = tf(-0.4, 0.5); circle(g, 60, 60, base * 0.5);
    },
    bubble: function (g, N) {
      g.fillStyle = tf(0.3, 0.14); circle(g, 64, 64, 58);
      g.strokeStyle = tf(0.15, 0.95); g.lineWidth = 6; g.beginPath(); g.arc(64, 64, 57, 0, Math.PI * 2); g.stroke();
      g.strokeStyle = tf(1, 0.95); g.lineWidth = 7; g.lineCap = 'round'; g.beginPath(); g.arc(64, 64, 42, Math.PI * 1.1, Math.PI * 1.45); g.stroke();
      g.fillStyle = tf(1, 1); circle(g, 92, 88, 5);
    },
    // four-point sparkle with a soft glow
    sparkle: function (g, N) {
      g.fillStyle = rad(g, 64, 64, 0, 64, 64, 46, [[0, tf(0.7, 0.75)], [1, tf(0.3, 0)]]); g.fillRect(0, 0, N, N);
      g.fillStyle = tf(0.85);
      [[0, 1], [1, 0]].forEach(function (d) { g.beginPath(); g.moveTo(64 - d[0] * 62, 64 - d[1] * 62); g.lineTo(64 + d[1] * 7, 64 + d[0] * 7); g.lineTo(64 + d[0] * 62, 64 + d[1] * 62); g.lineTo(64 - d[1] * 7, 64 - d[0] * 7); g.closePath(); g.fill(); });
      g.fillStyle = tf(1); circle(g, 64, 64, 9);
    },
    star: function (g, N) {
      g.fillStyle = rad(g, 64, 60, 4, 64, 64, 62, [[0, tf(0.55)], [1, tf(-0.25)]]);
      g.beginPath(); for (var i = 0; i < 10; i++) { var a = -Math.PI / 2 + i * Math.PI / 5, r = i % 2 ? 26 : 62; g[i ? 'lineTo' : 'moveTo'](64 + Math.cos(a) * r, 66 + Math.sin(a) * r); } g.closePath(); g.fill();
    },
    heart: function (g, N) {
      g.fillStyle = lin(g, 0, 14, 0, 120, [[0, tf(0.35)], [1, tf(-0.4)]]);
      g.beginPath(); g.moveTo(64, 118); g.bezierCurveTo(10, 80, 2, 40, 30, 22); g.bezierCurveTo(48, 10, 62, 22, 64, 36); g.bezierCurveTo(66, 22, 80, 10, 98, 22); g.bezierCurveTo(126, 40, 118, 80, 64, 118); g.fill();
      g.fillStyle = tf(1, 0.85); g.beginPath(); g.ellipse(38, 40, 9, 6, -0.6, 0, Math.PI * 2); g.fill();
    },
    // iris: dark rim, lighter ring, pupil, white shine, shade of the eyelid
    iris: function (g, N) {
      g.fillStyle = rad(g, 64, 64, 0, 64, 64, 60, [[0, tf(-0.3)], [0.45, tf(0.35)], [0.82, tf(-0.2)], [1, tf(-0.9)]]); circle(g, 64, 64, 59);
      g.fillStyle = tf(-1); circle(g, 64, 66, 22);
      g.globalCompositeOperation = 'source-atop'; g.fillStyle = tf(-0.8, 0.6); g.beginPath(); g.ellipse(64, 6, 66, 34, 0, 0, Math.PI * 2); g.fill(); g.globalCompositeOperation = 'source-over';
      g.fillStyle = tf(1); circle(g, 84, 44, 11); circle(g, 46, 86, 4.5);
    },
    // tiles laid end to end along the stroke (the tile is as long as the brush is wide)
    lace: function (g, N) {
      g.fillStyle = tf(0.2); g.fillRect(0, 10, N, 14);
      g.globalCompositeOperation = 'destination-out'; [32, 96].forEach(function (x) { circle(g, x, 17, 3.5); }); g.globalCompositeOperation = 'source-over';
      g.strokeStyle = tf(0.1); g.lineWidth = 7; g.beginPath(); g.arc(64, 22, 56, 0.08, Math.PI - 0.08); g.stroke();
      g.lineWidth = 4.5; g.beginPath(); g.arc(64, 22, 34, 0.12, Math.PI - 0.12); g.stroke();
      g.lineWidth = 4; g.beginPath(); g.arc(64, 58, 8, 0, Math.PI * 2); g.stroke();
      g.fillStyle = tf(0.1); [[20, 50], [108, 50], [64, 88]].forEach(function (p) { circle(g, p[0], p[1], 4); });
    },
    pearl: function (g, N) {
      g.fillStyle = rad(g, 46, 42, 2, 64, 64, 58, [[0, tf(1)], [0.25, tf(0.5)], [0.75, tf(-0.05)], [1, tf(-0.65)]]); circle(g, 64, 64, 57);
      g.fillStyle = tf(1); circle(g, 44, 40, 8);
    },
    chain: function (g, N) {
      g.strokeStyle = lin(g, 0, 36, 0, 92, [[0, tf(0.6)], [0.5, tf(0)], [1, tf(-0.6)]]); g.lineWidth = 10;
      g.beginPath(); g.ellipse(38, 64, 30, 22, 0, 0, Math.PI * 2); g.stroke();
      g.fillStyle = lin(g, 0, 58, 0, 70, [[0, tf(0.6)], [1, tf(-0.4)]]); g.beginPath(); g.moveTo(66, 58); g.lineTo(128, 58); g.lineTo(128, 70); g.lineTo(66, 70); g.fill();
    },
    stitch: function (g, N) {
      g.strokeStyle = tf(-0.1); g.lineCap = 'round'; g.lineWidth = 16; g.beginPath(); g.moveTo(14, 64); g.lineTo(82, 64); g.stroke();
      g.strokeStyle = tf(0.5); g.lineWidth = 5; g.beginPath(); g.moveTo(18, 61); g.lineTo(76, 61); g.stroke();
    },
    scar: function (g, N) {
      g.fillStyle = lin(g, 0, 56, 0, 72, [[0, tf(-0.4)], [0.5, tf(-0.9)], [1, tf(-0.4)]]); g.fillRect(0, 57, N, 14);
      g.strokeStyle = tf(0.1, 1, true); g.lineCap = 'round'; g.lineWidth = 8; g.beginPath(); g.moveTo(58, 18); g.lineTo(70, 110); g.stroke();
    },
    dot: function (g, N) { g.filter = 'blur(2px)'; g.fillStyle = tf(0); circle(g, N / 2, N / 2, N / 2 - 6); g.filter = 'none'; }
  };
  var tipCache = {};
  function hashStr(s) { var h = 2166136261; for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); } return h >>> 0; }
  /** the tip image and its smaller copies (premultiplied: alpha, tone x alpha, colour 2 x alpha) */
  function tipOf(name, variant) {
    var key = name + '#' + (variant || 0);
    if (tipCache[key]) return tipCache[key];
    var cv = document.createElement('canvas'); cv.width = cv.height = TIP_N;
    var g = cv.getContext('2d');
    (TIPS[name] || TIPS.round)(g, TIP_N, seeded(hashStr(key)));
    var d = g.getImageData(0, 0, TIP_N, TIP_N).data, S = TIP_N, n = S * S;
    var A = new Float32Array(n), TT = new Float32Array(n), CC = new Float32Array(n);
    for (var i = 0; i < n; i++) { var a = d[i * 4 + 3] / 255; A[i] = a; TT[i] = (d[i * 4] - 128) / 127 * a; CC[i] = d[i * 4 + 1] / 255 * a; }
    var levels = [{ S: S, a: A, t: TT, c: CC }];
    while (S > 2) {
      var S2 = S >> 1, lv = levels[levels.length - 1], A2 = new Float32Array(S2 * S2), T2 = new Float32Array(S2 * S2), C2 = new Float32Array(S2 * S2);
      for (var y = 0; y < S2; y++) for (var x = 0; x < S2; x++) {
        var j = y * 2 * S + x * 2, k = y * S2 + x;
        A2[k] = (lv.a[j] + lv.a[j + 1] + lv.a[j + S] + lv.a[j + S + 1]) / 4;
        T2[k] = (lv.t[j] + lv.t[j + 1] + lv.t[j + S] + lv.t[j + S + 1]) / 4;
        C2[k] = (lv.c[j] + lv.c[j + 1] + lv.c[j + S] + lv.c[j + S + 1]) / 4;
      }
      S = S2; levels.push({ S: S, a: A2, t: T2, c: C2 });
    }
    return (tipCache[key] = { levels: levels });
  }
  var SMP = { a: 0, t: 0, c: 0 };
  /** bilinear sample of a tip level at texel coordinates (outside the image = nothing) */
  function sampleTip(lv, fx, fy) {
    var S = lv.S, x0 = Math.floor(fx), y0 = Math.floor(fy), wx = fx - x0, wy = fy - y0, a = 0, tt = 0, cc = 0;
    for (var q = 0; q < 4; q++) {
      var xx = x0 + (q & 1), yy = y0 + (q >> 1);
      if (xx < 0 || yy < 0 || xx >= S || yy >= S) continue;
      var w = ((q & 1) ? wx : 1 - wx) * ((q >> 1) ? wy : 1 - wy), j = yy * S + xx;
      a += lv.a[j] * w; tt += lv.t[j] * w; cc += lv.c[j] * w;
    }
    SMP.a = a; SMP.t = tt; SMP.c = cc;
  }

  // =================================================================== the brushes (family + parameters)
  // o = the pixel art version (64x64 skins: the families further down); sm = the HD version (smooth families, tips).
  // HD keys: tip, variants, rot (follow | up | random | none), angleJ (radians), spacing / scatter (x brush size),
  // sizeJ, flow, flowJ, scale, count, toneRange (tone steps of the tip's light / dark), tones (per stamp), toneFn
  // (warm = fire), blend (screen | toneUp), fadeIn / fadeOut (x brush size), sizeFade (taper the size, not the
  // opacity), grain (paper texture fixed to the texture), mixUnder, flip (h | v), tile (laid end to end), color
  function P(id, group, fam, o, sm) { o = o || {}; o.id = id; o.group = group; o.fam = fam; if (sm) { sm.id = id; o.sm = sm; } return o; }
  var PRESETS = [
    P('lapis', 'draw', 'grain', {}, { fam: 'img', tip: 'pencil', spacing: 0.05, flow: 0.4, flowJ: 0.15, grain: 0.7, grainMode: 'height', fadeIn: 1.2, fadeOut: 1.6, sizeFade: true }),
    P('lineart', 'draw', 'pen', { taper: 2.5 }, { fam: 'img', tip: 'round', spacing: 0.05, flow: 1, fadeIn: 3, fadeOut: 3, sizeFade: true }),
    P('contorno', 'draw', 'outline', { auto: true }, { fam: 'outlineS', auto: true }),
    P('contorno2', 'draw', 'outline', { auto: false }, { fam: 'outlineS', auto: false }),
    P('render', 'draw', 'mix', { strength: 0.35, pickup: 0.3, amount: 0.55 },
      { fam: 'img', tip: 'render', spacing: 0.05, flow: 0.28, pickup: 0.12, mix: 0.25, fadeIn: 0.8, fadeOut: 1.4, sizeFade: true }),
    P('renderpx', 'draw', 'softdither', {}, { fam: 'img', tip: 'soft', spacing: 0.08, flow: 0.1, pickup: 0.2, mix: 0.55 }),
    P('aquarela', 'draw', 'water', {}, { fam: 'waterS', flow: 0.95, grain: 0.45, mixUnder: 0.2 }),
    P('oleo', 'draw', 'strands', { density: 1.1, minK: 3, maxK: 16, width: 1, tones: [-1, 0, 0, 1], blend: 'paint' },
      { fam: 'img', tip: 'bristle', rot: 'follow', angleJ: 0.03, spacing: 0.04, flow: 0.9, toneRange: 1.3, pickup: 0.08, mix: 0.3, fadeIn: 0.4, fadeOut: 1.2, sizeFade: true }),
    P('arcoiris', 'draw', 'pen', { color: 'rainbow' }, { fam: 'img', tip: 'round', spacing: 0.05, flow: 1, color: 'rainbow' }),
    P('pixel', 'draw', 'native'),
    P('cabelo', 'hair', 'strands', { density: 0.7, minK: 2, maxK: 10, width: 1, tones: [-1, 0, 1, 0, -1], taper: 1.5 },
      { fam: 'img', tip: 'hair', rot: 'follow', spacing: 0.03, flow: 1, toneRange: 2.2, fadeIn: 2, fadeOut: 2.5, sizeFade: true }),
    P('cachos', 'hair', 'stamp', { mask: 'curl', spacing: 1, rot: 'follow', scale: 1, sizeJ: 0.25, tones: [-1, 0, 0, 1] },
      { fam: 'img', tip: 'curl', rot: 'follow', angleJ: 0.25, spacing: 0.45, sizeJ: 0.25, toneRange: 2, tones: [-1, 0, 0, 1] }),
    P('brilhocabelo', 'hair', 'strands', { density: 0.3, minK: 1, maxK: 4, width: 0.7, tones: [2, 3], blend: 'toneUp', taper: 2, onPaint: true },
      { fam: 'img', tip: 'hairshine', rot: 'follow', spacing: 0.03, flow: 0.8, blend: 'toneUp', toneUp: 2.5, fadeIn: 2.5, fadeOut: 2.5, sizeFade: true }),
    P('pelo', 'hair', 'tufts', { spacing: 0.35, count: 3, tones: [-1, 0, 1] },
      { fam: 'img', tip: 'fur', rot: 'follow', angleJ: 0.08, spacing: 0.04, flow: 0.85, toneRange: 1.4, fadeIn: 1.5, fadeOut: 3, sizeFade: true }),
    P('penas', 'hair', 'stamp', { mask: 'chevron', spacing: 0.55, rot: 'follow', scale: 1, sizeJ: 0.15, tones: [0, 0, 1] },
      { fam: 'img', tip: 'feather', variants: 3, rot: 'follow', angleJ: 0.1, spacing: 0.38, sizeJ: 0.15, scale: 1.5, toneRange: 2, flip: 'v' }),
    P('escamas', 'hair', 'pattern', { pattern: 'scales', cellDiv: 6 }),
    P('grama', 'nature', 'blades', { spacing: 0.3, count: 3 },
      { fam: 'img', tip: 'grass', variants: 3, rot: 'up', angleJ: 0.12, spacing: 0.26, scatter: 0.18, sizeJ: 0.35, toneRange: 2.5, flip: 'h' }),
    P('folhagem', 'nature', 'stamp', { mask: 'foliage', spacing: 0.35, scatter: 0.15, rot: 'random', scale: 1, sizeJ: 0.3 },
      { fam: 'img', tip: 'foliage', variants: 4, rot: 'up', angleJ: 0.2, flip: 'h', spacing: 0.16, scatter: 0.1, sizeJ: 0.3, flow: 1, flowJ: 0.15 }),
    P('folhas', 'nature', 'stamp', { mask: 'leaf', spacing: 0.6, scatter: 0.8, rot: 'random', scale: 0.9, sizeJ: 0.4, tones: [-1, 0, 0, 1] },
      { fam: 'img', tip: 'leaf', rot: 'random', spacing: 0.42, scatter: 0.7, sizeJ: 0.4, scale: 0.9, toneRange: 2, tones: [-1, 0, 0, 1], flip: 'v' }),
    P('flores', 'nature', 'stamp', { mask: 'flower', spacing: 1.1, scatter: 1, rot: 'random', scale: 0.8, sizeJ: 0.4 },
      { fam: 'img', tip: 'flower', rot: 'random', spacing: 0.9, scatter: 0.9, sizeJ: 0.45, scale: 0.8, toneRange: 2 }),
    P('nuvens', 'nature', 'stamp', { mask: 'cloud', spacing: 0.55, scatter: 0.25, rot: 'none', scale: 1.4, sizeJ: 0.35 },
      { fam: 'img', tip: 'cloud', variants: 4, rot: 'up', angleJ: 0.05, spacing: 0.3, scatter: 0.22, sizeJ: 0.4, scale: 1.4, toneRange: 2, flip: 'h' }),
    P('fogo', 'nature', 'stamp', { mask: 'flame', spacing: 0.5, scatter: 0.35, rot: 'none', scale: 1.3, sizeJ: 0.45 },
      { fam: 'flameS', fadeIn: 1, fadeOut: 2.5, sizeFade: true }),
    P('respingos', 'nature', 'stamp', { mask: 'splat', spacing: 1.2, scatter: 1.2, rot: 'random', scale: 0.8, sizeJ: 0.7, count: 2 },
      { fam: 'img', tip: 'splat', variants: 4, rot: 'random', spacing: 1.1, scatter: 1.1, sizeJ: 0.7, count: 2, toneRange: 1.5 }),
    P('bolhas', 'nature', 'stamp', { mask: 'bubble', spacing: 1.1, scatter: 1, rot: 'none', scale: 0.9, sizeJ: 0.6 },
      { fam: 'img', tip: 'bubble', rot: 'none', spacing: 0.9, scatter: 1, sizeJ: 0.6, toneRange: 3 }),
    P('glitter', 'fx', 'spray', { spacing: 0.5, count: 4, scatter: 1, tones: [-1, 0, 1, 2] },
      { fam: 'sprayS', spacing: 0.45, count: 5, scatter: 1, tones: [-1, 0, 1, 2] }),
    P('brilhos', 'fx', 'stamp', { mask: 'sparkle', spacing: 1.3, scatter: 1.1, rot: 'none', scale: 1.1, sizeJ: 0.6, blend: 'lighter', color: 'light' },
      { fam: 'img', tip: 'sparkle', rot: 'none', spacing: 0.8, scatter: 1.1, sizeJ: 0.6, scale: 1.3, blend: 'screen', toneRange: 3, color: 'light' }),
    P('estrelas', 'fx', 'stamp', { mask: 'star', spacing: 1.8, scatter: 1.2, rot: 'none', scale: 1.1, sizeJ: 0.5, tones: [0, 1] },
      { fam: 'img', tip: 'star', rot: 'random', spacing: 1.3, scatter: 1.1, sizeJ: 0.5, toneRange: 2, tones: [0, 1] }),
    P('neon', 'fx', 'glow', {}, { fam: 'glowS' }),
    P('blush', 'fx', 'blush', { spacing: 1.6 }, { fam: 'blushS', spacing: 1.6 }),
    P('olhos', 'fx', 'stamp', { mask: 'iris', spacing: 3, rot: 'none', scale: 1 }, { fam: 'img', tip: 'iris', rot: 'none', spacing: 3, toneRange: 4 }),
    P('coracoes', 'deco', 'stamp', { mask: 'heart', spacing: 1.6, scatter: 0.6, rot: 'none', scale: 1, sizeJ: 0.4 },
      { fam: 'img', tip: 'heart', rot: 'none', spacing: 1.3, scatter: 0.5, sizeJ: 0.4, toneRange: 2 }),
    P('renda', 'deco', 'stamp', { mask: 'lace', tile: true, rot: 'follow', scale: 1 }, { fam: 'img', tip: 'lace', tile: 1, rot: 'follow', toneRange: 1.5 }),
    P('perolas', 'deco', 'stamp', { mask: 'pearl', tile: true, rot: 'none', scale: 1 }, { fam: 'img', tip: 'pearl', tile: 1, rot: 'none', toneRange: 3 }),
    P('corrente', 'deco', 'stamp', { mask: 'chain', tile: true, rot: 'follow', scale: 1.2 }, { fam: 'img', tip: 'chain', tile: 1, rot: 'follow', scale: 1.2, toneRange: 2 }),
    P('costura', 'deco', 'motif', { motif: 'stitch', period: 0.75 }, { fam: 'img', tip: 'stitch', tile: 1, rot: 'follow', scale: 0.8, toneRange: 1.5 }),
    P('cicatriz', 'deco', 'motif', { motif: 'scar', period: 0.8 }, { fam: 'img', tip: 'scar', tile: 1, rot: 'follow', toneRange: 1.5 }),
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
    var tg = { tex: tex, src: src, ctx: src.ctx, w: c.width, h: c.height, off: [off[0], off[1]], orig: new Uint8ClampedArray(data.data), img: data, rec: new Map(), dirty: null, sides: [], area: null, virtual: false, pixel: pixelFor(tex, c.width) };
    S.targets.push(tg);
    return tg;
  }
  function virtualTarget(w, h, fill, pixel) {
    var img = new ImageData(w, h);
    for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) { var c = fill(x, y), i = (y * w + x) * 4; img.data[i] = c[0]; img.data[i + 1] = c[1]; img.data[i + 2] = c[2]; img.data[i + 3] = c.length > 3 ? c[3] : 255; }
    return { tex: null, src: null, ctx: null, w: w, h: h, off: [0, 0], orig: new Uint8ClampedArray(img.data), img: img, rec: new Map(), dirty: null, sides: [], area: null, virtual: true, pixel: pixel !== false };
  }
  /** a texture layer can grow while painting (Blockbench expands it under the brush): move the buffers along */
  function syncTarget(tg) {
    if (tg.virtual) return;
    var c = tg.src.canvas, off = tg.src.offset || [0, 0];
    if (c.width === tg.w && c.height === tg.h && off[0] === tg.off[0] && off[1] === tg.off[1]) return;
    var nw = c.width, nh = c.height, dx = tg.off[0] - off[0], dy = tg.off[1] - off[1];
    var cur = tg.ctx.getImageData(0, 0, nw, nh), orig = new Uint8ClampedArray(nw * nh * 4);
    var NL = tg.L ? tg.L.map(function (ly) { return { A: new Float32Array(nw * nh), C: new Float32Array(nw * nh * 3), blend: ly.blend }; }) : null;
    for (var y = 0; y < tg.h; y++) {
      var ny = y + dy; if (ny < 0 || ny >= nh) continue;
      for (var x = 0; x < tg.w; x++) {
        var nx = x + dx; if (nx < 0 || nx >= nw) continue;
        var s1 = y * tg.w + x, d1 = ny * nw + nx, si = s1 * 4, di = d1 * 4;
        orig[di] = tg.orig[si]; orig[di + 1] = tg.orig[si + 1]; orig[di + 2] = tg.orig[si + 2]; orig[di + 3] = tg.orig[si + 3];
        if (NL) for (var k = 0; k < NL.length; k++) { NL[k].A[d1] = tg.L[k].A[s1]; NL[k].C[d1 * 3] = tg.L[k].C[s1 * 3]; NL[k].C[d1 * 3 + 1] = tg.L[k].C[s1 * 3 + 1]; NL[k].C[d1 * 3 + 2] = tg.L[k].C[s1 * 3 + 2]; }
      }
    }
    tg.orig = orig; tg.img = cur; tg.w = nw; tg.h = nh; tg.off = [off[0], off[1]]; tg.dirty = null;
    if (NL) tg.L = NL;
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

  // =================================================================== HD painting (beta32): stroke layers + tip dabs
  /** pixel art or smooth: 'auto' = pixel art on textures up to 64 px wide (skins), smooth (anti-aliased) above */
  function pixelFor(tex, w) {
    var st = L.opt('br.style', 'auto');
    if (st === 'pixel') return true;
    if (st === 'smooth') return false;
    return (tex && tex.width ? tex.width : w) <= 64;
  }
  /**
   * A stroke paints into its own layers (premultiplied colour + coverage, 'over' between the dabs, like a painting
   * app), then each changed pixel is the original texture with the layers on top. Layer 0 is under layer 1.
   */
  function layers(count) {
    if (!T.L) { T.L = []; T.box = null; }
    while (T.L.length < count) { var n = T.w * T.h; T.L.push({ A: new Float32Array(n), C: new Float32Array(n * 3), blend: 'normal' }); }
    return T.L;
  }
  function boxAdd(x0, y0, x1, y1) {
    var b = T.box;
    if (!b) T.box = { x0: x0, y0: y0, x1: x1, y1: y1 };
    else { if (x0 < b.x0) b.x0 = x0; if (y0 < b.y0) b.y0 = y0; if (x1 > b.x1) b.x1 = x1; if (y1 > b.y1) b.y1 = y1; }
  }
  function smoothstep(e0, e1, x) { var tt = clamp((x - e0) / (e1 - e0), 0, 1); return tt * tt * (3 - 2 * tt); }
  function compositeAt(i) {
    var i4 = i * 4, o = T.orig, d = T.img.data, r = o[i4], g = o[i4 + 1], b = o[i4 + 2], al = o[i4 + 3] / 255, lock = lockAlpha();
    for (var k = 0; k < T.L.length; k++) {
      var ly = T.L[k], A = ly.A[i]; if (A <= 0.0015) continue;
      var a, i3 = i * 3, cr, cg, cb;
      if (ly.wash) {
        // watercolour: A = how close the stroke's middle passed (0 edge .. 1 centre): more pigment at the edge
        a = (0.3 + 0.5 * (1 - smoothstep(0, 0.3, A))) * smoothstep(0, 0.07, A) * ly.wash;
        cr = ly.C[i3]; cg = ly.C[i3 + 1]; cb = ly.C[i3 + 2];
      } else {
        a = Math.min(1, A); cr = ly.C[i3] / A; cg = ly.C[i3 + 1] / A; cb = ly.C[i3 + 2] / A;
      }
      // paper texture: applied to the whole stroke (dabs on top of each other never fill the holes of the paper);
      // 'height' (pencil): the paper's peaks take the graphite first, so a full stroke is solid with a fine grain
      // and only its thin edge breaks up
      if (ly.grain) {
        var gx = i % T.w + T.off[0], gy = Math.floor(i / T.w) + T.off[1];
        a = ly.grainMode === 'height' ? clamp(a * (1 + 0.5 * ly.grain) - (1 - paperAt(gx, gy)) * ly.grain, 0, 1) : a * grainAt(gx, gy, ly.grain);
      }
      a *= S.opacity;
      if (ly.blend === 'screen' && al > 0) { cr = 255 - (255 - r) * (255 - cr) / 255; cg = 255 - (255 - g) * (255 - cg) / 255; cb = 255 - (255 - b) * (255 - cb) / 255; }
      if (lock) { if (al <= 0) continue; r += (cr - r) * a; g += (cg - g) * a; b += (cb - b) * a; continue; }
      var na = a + al * (1 - a);
      if (na <= 0) continue;
      r = (cr * a + r * al * (1 - a)) / na; g = (cg * a + g * al * (1 - a)) / na; b = (cb * a + b * al * (1 - a)) / na; al = na;
    }
    d[i4] = byte(r); d[i4 + 1] = byte(g); d[i4 + 2] = byte(b); d[i4 + 3] = Math.round(clamp(al, 0, 1) * 255);
  }
  /** re-writes the texture pixels of a box (texture coordinates) from the original + the stroke layers */
  function compositeBox(x0, y0, x1, y1) {
    if (!T.L) return;
    var cx0 = Math.max(x0, T.off[0]), cy0 = Math.max(y0, T.off[1]), cx1 = Math.min(x1, T.off[0] + T.w - 1), cy1 = Math.min(y1, T.off[1] + T.h - 1);
    if (cx1 < cx0 || cy1 < cy0) return;
    for (var y = cy0; y <= cy1; y++) for (var x = cx0; x <= cx1; x++) compositeAt(idx(x, y));
    mark(cx0, cy0); mark(cx1, cy1);
  }
  var lutCache = new Map();
  /** colour of the tip's tones for a base colour: tn -1..1 -> rgb (tone steps x toneRange, 'warm' for fire) */
  function lutFor(base, p) {
    var range = p.toneRange || 0, fn = p.toneFn || 'shade';
    if (!range) return function () { return base; };
    var key = Math.round(base[0]) + ',' + Math.round(base[1]) + ',' + Math.round(base[2]) + '|' + range + fn, tab = lutCache.get(key);
    if (!tab) {
      tab = [];
      for (var i = 0; i <= 32; i++) { var k = (i / 16 - 1) * range; tab.push(fn === 'warm' ? toneWarm(base, k) : tone(base, k)); }
      if (lutCache.size > 600) lutCache.clear();
      lutCache.set(key, tab);
    }
    return function (tn) { return tab[Math.round((clamp(tn, -1, 1) + 1) * 16)]; };
  }
  /** fire tones: lighter toward yellow-white, darker toward deep red (instead of the cool shadow of the shade) */
  function toneWarm(rgb, k) {
    if (!k) return [rgb[0], rgb[1], rgb[2]];
    var c = L.rgbToHsv(rgb[0], rgb[1], rgb[2]), h = c.h, s = c.s, v = c.v, f = clamp(Math.abs(k) / 3, 0, 1);
    if (k > 0) { h = h + (((55 - h + 540) % 360) - 180) * f; s = s * (1 - 0.6 * f); v = v + (1 - v) * 0.85 * f; }
    else { h = h + (((358 - h + 540) % 360) - 180) * f * 0.8; s = Math.min(1, s + (1 - s) * 0.4 * f); v = v * (1 - 0.6 * f); }
    return L.hsvToRgb(h, s, v);
  }
  var upCache = new Map();
  function toneOf(r, g, b, k) {
    var key = ((r << 16) | (g << 8) | b) + '|' + k.toFixed(2), c = upCache.get(key);
    if (!c) { c = tone([r, g, b], k); if (upCache.size > 4000) upCache.clear(); upCache.set(key, c); }
    return c;
  }
  /** paper texture fixed to the texture: holes where the paper is low (pencil, watercolour) */
  function grainAt(px, py, amount) { var gr = 0.6 * valueNoise(px, py, 2.3, 919) + 0.4 * hash3(px, py, 77); return clamp(1 - amount + amount * 1.7 * gr, 0, 1); }
  /** height of the paper, 0 (valley) .. 1 (peak) */
  function paperAt(px, py) { return 0.55 * valueNoise(px, py, 2.3, 919) + 0.45 * hash3(px, py, 77); }
  /**
   * One tip dab into stroke layer k: centre (cx, cy) in texture pixels, diameter D, angle (radians), flips fx / fy
   * (+1 / -1), flow (opacity of this dab), base colour; p gives toneRange / toneFn / blend / grain / mixUnder.
   */
  function dabRaw(k, tipName, variant, cx, cy, D, ang, fx, fy, flow, base, p) {
    var tip = tipOf(tipName, variant), lv = tip.levels, R = D / 2;
    if (R < 0.3 || flow <= 0.002) return null;
    var li = 0; while (li < lv.length - 1 && lv[li + 1].S >= D) li++;
    var l = lv[li], Sz = l.S, ca = Math.cos(ang), sa = Math.sin(ang), ext = R * (Math.abs(ca) + Math.abs(sa)) + 1;
    var x0 = Math.floor(cx - ext), x1 = Math.ceil(cx + ext), y0 = Math.floor(cy - ext), y1 = Math.ceil(cy + ext);
    var ly = layers(k + 1)[k], lut = lutFor(base, p), mixU = p.mixUnder || 0, o = T.orig;
    if (p.blend === 'screen') ly.blend = 'screen';
    if (p.grain) { ly.grain = p.grain; ly.grainMode = p.grainMode; }
    for (var py = y0; py <= y1; py++) for (var px = x0; px <= x1; px++) {
      if (!allowed(px, py)) continue;
      var dx = px + 0.5 - cx, dy = py + 0.5 - cy, u = (dx * ca + dy * sa) / R * fx, v = (-dx * sa + dy * ca) / R * fy;
      if (u <= -1 || u >= 1 || v <= -1 || v >= 1) continue;
      sampleTip(l, (u + 1) * 0.5 * Sz - 0.5, (v + 1) * 0.5 * Sz - 0.5);
      var s0 = SMP.a; if (s0 < 0.004) continue;
      var a = s0 * flow;
      if (a < 0.003) continue;
      var i = idx(px, py), i4 = i * 4, tn = SMP.t / s0, c2w = SMP.c / s0, col;
      if (p.blend === 'toneUp') {
        if (o[i4 + 3] === 0) continue;
        col = toneOf(o[i4], o[i4 + 1], o[i4 + 2], (p.toneUp || 2) * (0.6 + 0.4 * tn));
      } else {
        col = lut(tn);
        if (c2w > 0.01) col = lerp3(col, S.c2, Math.min(1, c2w));
        if (mixU && o[i4 + 3]) col = lerp3(col, [o[i4], o[i4 + 1], o[i4 + 2]], mixU);
      }
      a = Math.min(1, a);
      var A0 = ly.A[i], i3 = i * 3, ia = 1 - a;
      ly.C[i3] = ly.C[i3] * ia + col[0] * a; ly.C[i3 + 1] = ly.C[i3 + 1] * ia + col[1] * a; ly.C[i3 + 2] = ly.C[i3 + 2] * ia + col[2] * a;
      ly.A[i] = A0 + a * (1 - A0);
    }
    boxAdd(x0, y0, x1, y1);
    return [x0, y0, x1, y1];
  }
  function dab(k, tipName, variant, cx, cy, D, ang, fx, fy, flow, base, p) {
    var b = dabRaw(k, tipName, variant, cx, cy, D, ang, fx, fy, flow, base, p);
    if (b) compositeBox(b[0], b[1], b[2], b[3]);
  }
  /** points along the path where dabs are due, every sp px of arc (interpolated between the calls), own counter per key */
  function duePoints(side, sp, key) {
    key = key || 'a';
    var nk = 'next_' + key, out = [], guard = 0;
    if (side[nk] === undefined) side[nk] = 0;
    while (side.arc >= side[nk] && guard++ < 512) {
      var seg = side.arc - side.prevArc, f = seg > 1e-6 ? clamp((side[nk] - side.prevArc) / seg, 0, 1) : 1;
      out.push([side.prev[0] + (side.last[0] - side.prev[0]) * f, side.prev[1] + (side.last[1] - side.prev[1]) * f, side.n, side[nk]]);
      side[nk] += sp; side.n++;
    }
    return out;
  }
  function rndN(side, n, ch) { return hash3(n * 131 + ch * 7, side.idx * 7919 + ch * 31, S.seed); }
  /**
   * One dab of an HD brush: d = {k layer, tip, variant, x, y, D, D0 (brush size), ang, fx, fy, flow, base, arc, pp
   * (tone / blend settings of this part, else the brush's)}. It thins in at the start (fadeIn), is kept for the
   * fade out at the end (fadeOut) and is painted now.
   */
  function put(side, p, d) {
    var fade = p.fadeIn ? clamp(d.arc / Math.max(1, p.fadeIn * d.D0), 0, 1) : 1;
    if (p.sizeFade) d.D *= 0.25 + 0.75 * fade; else d.flow *= fade;
    if (p.fadeOut) (side.dabs || (side.dabs = [])).push(d);
    dab(d.k, d.tip, d.variant || 0, d.x, d.y, d.D, d.ang, d.fx, d.fy, d.flow, d.base, d.pp || p);
  }
  /**
   * 'Wet' brushes (render, blend, oil; ibisPaint's Water type): the colour under the brush (the texture as it was
   * when the stroke started) is picked up and carried along the stroke, so it drags from one colour into the next;
   * pickup = how fast the carried colour changes, mix = how much of it is in the paint.
   */
  function wetColour(side, p, base, cx, cy, D) {
    var r = Math.max(1, D * 0.3), sum = [0, 0, 0], n = 0;
    for (var j = 0; j < 5; j++) {
      var c = original(Math.floor(cx + (j === 1 ? r : j === 2 ? -r : 0)), Math.floor(cy + (j === 3 ? r : j === 4 ? -r : 0)));
      if (c) { sum[0] += c[0]; sum[1] += c[1]; sum[2] += c[2]; n++; }
    }
    if (n) { var under = [sum[0] / n, sum[1] / n, sum[2] / n]; side.carry = side.carry ? lerp3(side.carry, under, p.pickup) : under; }
    return side.carry ? lerp3(base, side.carry, p.mix || 0.3) : base;
  }
  /** the stroke ends: brushes that thin out at the end are painted again from their recorded dabs */
  function refade(p) {
    if (!p || !p.fadeOut || !T.L) return;
    var any = T.sides.some(function (s) { return s.dabs && s.dabs.length; });
    if (!any) return;
    T.L.forEach(function (ly) { ly.A.fill(0); ly.C.fill(0); });
    T.sides.forEach(function (s) {
      (s.dabs || []).forEach(function (d) {
        var f = clamp((s.arc - d.arc) / Math.max(1, p.fadeOut * d.D0), 0, 1);
        dabRaw(d.k, d.tip, d.variant || 0, d.x, d.y, p.sizeFade ? d.D * (0.25 + 0.75 * f) : d.D, d.ang, d.fx, d.fy, p.sizeFade ? d.flow : d.flow * f, d.base, d.pp || p);
      });
    });
    if (T.box) compositeBox(T.box.x0, T.box.y0, T.box.x1, T.box.y1);
  }

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
    // off: integer positions are pixel indexes (their centre is +0.5), fractional ones (soft brush) are exact
    var s = { idx: T.sides.length + (S.targets.indexOf(T) + 1) * 1000, last: [x, y], prev: [x, y], arc: 0, prevArc: 0, next: 0, n: 0, hist: [[x, y]], dir: [1, 0], first: true,
      off: (x % 1 === 0 && y % 1 === 0) ? 0.5 : 0 };
    T.sides.push(s);
    return s;
  }
  function advance(side, x, y) {
    if (side.first) { side.first = false; return; }
    var dx = x - side.last[0], dy = y - side.last[1];
    side.prev = side.last; side.prevArc = side.arc;
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
    },

    // ----------------------------------------------------------------- HD families (smooth, tips drawn above)
    /** image tips along the path: spacing, scatter, rotation, size / opacity / tone jitter, fade in / out, tiles */
    img: function (p, side, x, y, size) {
      var vary = Math.min(1, varyMul()), sc = scatterMul(), D0 = Math.max(1, size * (p.scale || 1));
      var sp = p.tile ? D0 * p.tile : Math.max(0.35, (p.spacing || 0.1) * D0);
      duePoints(side, sp).forEach(function (pt) {
        var n = pt[2];
        for (var q = 0; q < (p.count || 1); q++) {
          var ch = q * 17, r1 = rndN(side, n, ch + 1), r2 = rndN(side, n, ch + 2), r3 = rndN(side, n, ch + 3), r4 = rndN(side, n, ch + 4), r5 = rndN(side, n, ch + 5), r6 = rndN(side, n, ch + 6);
          var D = p.tile ? D0 : D0 * (1 - (p.sizeJ || 0) * vary * r1);
          var flow = (p.flow === undefined ? 1 : p.flow) * (1 - (p.flowJ || 0) * r6);
          var rad = (p.scatter || 0) * sc * D0, a = 2 * Math.PI * r2, dd = rad * Math.sqrt(r3), dirA = Math.atan2(side.dir[1], side.dir[0]);
          var ang = p.rot === 'follow' ? dirA + (r4 - 0.5) * 2 * (p.angleJ || 0) : p.rot === 'random' ? r4 * Math.PI * 2 : (r4 - 0.5) * 2 * (p.angleJ || 0);
          var fx = p.flip === 'h' && r5 < 0.5 ? -1 : 1, fy = p.flip === 'v' && r5 < 0.5 ? -1 : 1;
          var cx = pt[0] + side.off + Math.cos(a) * dd, cy = pt[1] + side.off + Math.sin(a) * dd;
          var variant = p.variants ? Math.floor(r6 * p.variants) % p.variants : 0, base = colourFor(p, side, r5);
          if (p.pickup) base = wetColour(side, p, base, cx, cy, D0);
          put(side, p, { k: 0, tip: p.tip, variant: variant, x: cx, y: cy, D: D, D0: D0, ang: ang, fx: fx, fy: fy, flow: flow, base: base, arc: pt[3] });
        }
      });
    },
    /**
     * fire (like the community flame brushes): a soft warm glow (screen), tongues of flame licking back and out from
     * both edges, and a hot white-yellow core along the path; thin at both ends. Colour 1 = the flame's colour.
     */
    flameS: function (p, side, x, y, size) {
      var D0 = Math.max(2, size), dirA = Math.atan2(side.dir[1], side.dir[0]), nx = -side.dir[1], ny = side.dir[0], vary = Math.min(1, varyMul());
      var warm = { toneRange: 3, toneFn: 'warm' }, glow = toneWarm(S.c1, -0.6);
      duePoints(side, Math.max(0.5, 0.2 * D0), 'g').forEach(function (pt) {
        put(side, p, { k: 0, tip: 'soft', x: pt[0] + side.off, y: pt[1] + side.off, D: D0 * 2.6, D0: D0, ang: 0, fx: 1, fy: 1, flow: 0.06, base: glow, arc: pt[3], pp: { blend: 'screen' } });
      });
      duePoints(side, Math.max(0.5, 0.28 * D0), 't').forEach(function (pt) {
        var n = pt[2], s = rndN(side, n, 1) < 0.5 ? -1 : 1, off = (0.05 + 0.25 * rndN(side, n, 2)) * D0 * s;
        var ang = dirA + Math.PI - s * (0.2 + 0.55 * rndN(side, n, 3)), D = D0 * (0.9 + 0.9 * rndN(side, n, 4) * vary);
        var rx = pt[0] + side.off + nx * off, ry = pt[1] + side.off + ny * off;
        put(side, p, { k: 1, tip: 'tongue', variant: Math.floor(rndN(side, n, 5) * 3), x: rx + Math.cos(ang) * D * 0.45, y: ry + Math.sin(ang) * D * 0.45, D: D, D0: D0, ang: ang, fx: 1, fy: s, flow: 0.85, base: S.c1, arc: pt[3], pp: warm });
      });
      duePoints(side, Math.max(0.35, 0.06 * D0), 'b').forEach(function (pt) {
        var wob = 0.8 + 0.4 * valueNoise(pt[3], side.idx * 13, Math.max(2, D0 * 0.9), 31);
        put(side, p, { k: 2, tip: 'flamecore', x: pt[0] + side.off, y: pt[1] + side.off, D: D0 * 0.75 * wob, D0: D0, ang: dirA, fx: 1, fy: 1, flow: 0.5, base: S.c1, arc: pt[3], pp: warm });
      });
    },
    /**
     * watercolour: the stroke keeps, per pixel, how close its middle passed; the composite puts more pigment near the
     * edge of the whole stroke (the 'wet edge'), a little of the colour underneath mixed in, and paper grain
     */
    waterS: function (p, side, x, y, size) {
      var D = Math.max(2, size), R = D / 2, ly = layers(1)[0];
      ly.wash = p.flow || 1; if (p.grain) ly.grain = p.grain;
      duePoints(side, Math.max(0.35, 0.08 * D)).forEach(function (pt) {
        var cx = pt[0] + side.off, cy = pt[1] + side.off, x0 = Math.floor(cx - R - 1), x1 = Math.ceil(cx + R + 1), y0 = Math.floor(cy - R - 1), y1 = Math.ceil(cy + R + 1);
        for (var py = y0; py <= y1; py++) for (var px = x0; px <= x1; px++) {
          if (!allowed(px, py)) continue;
          var dx = px + 0.5 - cx, dy = py + 0.5 - cy, c = 1 - Math.sqrt(dx * dx + dy * dy) / R;
          if (c <= 0) continue;
          var i = idx(px, py);
          if (c <= ly.A[i]) continue;
          if (ly.A[i] <= 0) {
            var i4 = i * 4, col = S.c1;
            if (p.mixUnder && T.orig[i4 + 3]) col = lerp3(S.c1, [T.orig[i4], T.orig[i4 + 1], T.orig[i4 + 2]], p.mixUnder);
            ly.C[i * 3] = col[0]; ly.C[i * 3 + 1] = col[1]; ly.C[i * 3 + 2] = col[2];
          }
          ly.A[i] = c;
        }
        boxAdd(x0, y0, x1, y1);
        compositeBox(x0, y0, x1, y1);
      });
    },
    /** colour with an automatic outline: the outline under, the colour on top (Double) */
    outlineS: function (p, side, x, y, size) {
      var D = Math.max(1, size), ow = Math.max(1, D / 6), oc = p.auto ? tone(S.c1, -3) : S.c2;
      duePoints(side, Math.max(0.35, 0.08 * D)).forEach(function (pt) {
        var cx = pt[0] + side.off, cy = pt[1] + side.off;
        dab(0, 'round', 0, cx, cy, D + 2 * ow, 0, 1, 1, 1, oc, {});
        dab(1, 'round', 0, cx, cy, D, 0, 1, 1, 1, S.c1, {});
      });
    },
    /** neon: a wide soft glow (screen) under a thin almost white core */
    glowS: function (p, side, x, y, size) {
      var D = Math.max(1, size), core = lerp3(S.c1, WHITE, 0.75);
      duePoints(side, Math.max(0.35, 0.06 * D)).forEach(function (pt) {
        var cx = pt[0] + side.off, cy = pt[1] + side.off;
        dab(0, 'soft', 0, cx, cy, D * 1.7, 0, 1, 1, 0.3, S.c1, { blend: 'screen' });
        dab(1, 'round', 0, cx, cy, Math.max(1, D * 0.35), 0, 1, 1, 1, core, {});
      });
    },
    /** blush: a very light airbrush, with a few tiny sparkles */
    blushS: function (p, side, x, y, size) {
      var D = Math.max(2, size);
      duePoints(side, Math.max(0.35, 0.1 * D)).forEach(function (pt) { dab(0, 'soft', 0, pt[0] + side.off, pt[1] + side.off, D, 0, 1, 1, 0.07, S.c1, {}); });
      duePoints(side, Math.max(2, (p.spacing || 1.6) * D), 's').forEach(function (pt) {
        if (rndN(side, pt[2], 1) >= 0.5) return;
        var a = 2 * Math.PI * rndN(side, pt[2], 2), dd = D * 0.4 * Math.sqrt(rndN(side, pt[2], 3));
        dab(1, 'sparkle', 0, pt[0] + side.off + Math.cos(a) * dd, pt[1] + side.off + Math.sin(a) * dd, Math.max(3, D * 0.3), 0, 1, 1, 1, WHITE, { blend: 'screen' });
      });
    },
    /** glitter: little dots in several tones of colour 1, now and then a sparkle */
    sprayS: function (p, side, x, y, size) {
      var D = Math.max(2, size), sc = scatterMul(), vary = varyMul();
      duePoints(side, Math.max(0.5, (p.spacing || 0.45) * D)).forEach(function (pt) {
        var n = pt[2], count = Math.max(2, Math.round((p.count || 5) * Math.max(1, D / 10)));
        for (var q = 0; q < count; q++) {
          var ch = q * 23, a = 2 * Math.PI * rndN(side, n, ch + 1), dd = (p.scatter || 1) * sc * D * 0.6 * Math.sqrt(rndN(side, n, ch + 2));
          var cx = pt[0] + side.off + Math.cos(a) * dd, cy = pt[1] + side.off + Math.sin(a) * dd;
          if (rndN(side, n, ch + 3) < 0.1) dab(1, 'sparkle', 0, cx, cy, Math.max(3, D * 0.45), 0, 1, 1, 1, lerp3(S.c1, WHITE, 0.6), { blend: 'screen' });
          else dab(0, 'dot', 0, cx, cy, Math.max(1, D * (0.08 + 0.1 * rndN(side, n, ch + 5))), 0, 1, 1, 1, tone(S.c1, pickTone(p, rndN(side, n, ch + 4)) * vary), {});
        }
      });
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
  /** the version of a brush for the current target: HD (smooth tips) or pixel art */
  function modeOf(p) { return !T.pixel && p.sm ? p.sm : p; }
  function draw(o) {
    if (!S || !o || !o.texture) return;
    var tg = targetFor(o.texture); if (!tg) return;
    T = tg;
    var p = preset(), q = modeOf(p), size = Math.max(1, o.size || 1), x = o.x, y = o.y;
    if (T.tex.layers_enabled && T.src && typeof T.src.expandTo === 'function') {
      // scattered stamps can land farther than the brush size: let the layer grow there too
      var reach = Math.ceil(size * (1.8 + (q.scale || 1) + (q.scatter || 0) * scatterMul())) + 3;
      try { T.src.expandTo([x - reach, y - reach], [x + reach, y + reach]); } catch (e) { }
    }
    syncTarget(T);
    T.area = faceArea();
    var side = sideFor(x, y);
    advance(side, x, y);
    try { FAM[q.fam](q, side, x, y, size); } catch (e) { L.warn('brush ' + p.id + ' failed', e); }
    flush();
  }
  function end() {
    if (S) {
      var p = preset();
      S.targets.forEach(function (tg) {
        T = tg; syncTarget(tg);
        var q = modeOf(p);
        try { if (q === p) endStroke(p); else refade(q); } catch (e) { L.warn('brush end failed', e); }
        flush();
      });
    }
    S = null; T = null;
  }

  // =================================================================== virtual strokes (library previews, QA)
  var PREVIEW_SIZE = { pen: 3, grain: 2, outline: 2, mix: 5, softdither: 5, water: 5, strands: 4, stamp: 4, spray: 4, blades: 4, tufts: 4, motif: 4, pattern: 8, glow: 3, blush: 5 };
  // HD previews (128 x 48): the brush size per kind
  var PREVIEW_SIZE_HD = { img: 16, outlineS: 7, glowS: 8, blushS: 16, sprayS: 12, mix: 12, pattern: 16 };
  var PREVIEW_HD_BY_ID = { lineart: 7, arcoiris: 7, lapis: 6, cabelo: 14, brilhocabelo: 12, oleo: 14, renda: 14, perolas: 12, corrente: 14, costura: 12, cicatriz: 14, olhos: 22, nuvens: 14, fogo: 14 };
  /** runs a brush on a virtual w x h image along pts; returns the ImageData (pixel: the 64x64 version or HD) */
  function virtualStroke(p, w, h, size, pts, c1, c2, fill, seed, pixel) {
    var savedS = S, savedT = T;
    try {
      S = { seed: seed, c1: c1, c2: c2, opacity: 1, targets: [] };
      T = virtualTarget(w, h, fill, pixel);
      S.targets.push(T);
      var q = modeOf(p);
      pts.forEach(function (pt) { var side = sideFor(pt[0], pt[1]); advance(side, pt[0], pt[1]); FAM[q.fam](q, side, pt[0], pt[1], size); });
      if (q === p) endStroke(p); else refade(q);
      return T.img;
    } finally { S = savedS; T = savedT; }
  }
  function previewPath(w, h, pad) {
    var pts = [], m = pad || 5, amp = h / 2 - (pad ? pad : 6);
    for (var x = m; x <= w - m; x++) pts.push([x, Math.round(h / 2 + Math.sin((x - m) / (w - 2 * m) * Math.PI * 2) * amp)]);
    return pts;
  }
  function previewFill(c1, p, h) {
    var half = (h || 24) / 2;
    // brushes that work over paint (shine, grain, paper): a band of colour 1 to work on
    if (p && p.onPaint) { var mid = tone(c1, -1), lo = tone(c1, -2); return function (x, y) { return y < half ? mid : lo; }; }
    // dark background for light colours and the other way round; two tones so blending brushes show something
    var dark = luminance(c1) > 0.42, top = dark ? [78, 84, 98] : [214, 218, 224], bottom = dark ? [52, 56, 66] : [178, 184, 194];
    return function (x, y) { return y < half ? top : bottom; };
  }
  /** previews in the look the brush will have on the texture being painted (HD or 64x64 pixel art) */
  function previewPixel() {
    var tex = G.Texture && (G.Texture.selected || (G.Texture.all && G.Texture.all[0]));
    return pixelFor(tex, 64);
  }
  var previewCache = {};
  function renderPreview(id, c1, c2, pixel) {
    if (pixel === undefined) pixel = previewPixel();
    var p = BY_ID[id], w = pixel ? 64 : 128, h = pixel ? 24 : 48, key = id + '|' + c1.join(',') + '|' + c2.join(',') + '|' + (pixel ? 'px' : 'hd');
    if (previewCache[key]) return previewCache[key];
    var canvas = document.createElement('canvas'); canvas.width = w; canvas.height = h;
    var ctx = canvas.getContext('2d'), fill = previewFill(c1, p, h);
    try {
      var img;
      if (p.fam === 'native') img = virtualStroke({ fam: 'pen' }, w, h, pixel ? 1 : 2, previewPath(w, h), c1, c2, fill, 4242, true);
      else if (pixel || !p.sm) img = virtualStroke(p, w, h, pixel ? (PREVIEW_SIZE[p.fam] || 4) : (PREVIEW_SIZE_HD[p.fam] || 12), previewPath(w, h, pixel ? 0 : 14), c1, c2, fill, 4242, pixel);
      else img = virtualStroke(p, w, h, PREVIEW_HD_BY_ID[id] || PREVIEW_SIZE_HD[p.sm.fam] || 14, previewPath(w, h, 14), c1, c2, fill, 4242, false);
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
  /** beta31: the brush library is the 'Pincéis' part of the CPM+ panel (no window of its own any more) */
  function openLibrary() { return L.core.showPanel('brushes'); }
  api.install = function () {
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
    ui.scatter = L.slider('cpmplus_art_br_scatter', 'cpmplus.art.br.scatter', 0, 200, 100, 'br.scatter', function () { return on() && !!USES_SCATTER[preset().fam]; });
    ui.vary = L.slider('cpmplus_art_br_vary', 'cpmplus.art.br.vary', 0, 200, 100, 'br.vary', function () { return on() && !!USES_VARY[preset().fam]; });
    // HD (smooth tips) or pixel art: automatic by the size of the texture
    ui.style = L.select('cpmplus_art_br_style', 'cpmplus.art.br.style', ['auto', 'smooth', 'pixel'], 'auto', 'br.style', on, function () { previewCache = {}; });
  };
  api.uninstall = function () { S = null; T = null; previewCache = {}; };
  api.openLibrary = openLibrary;
  /** the library for the CPM+ panel: groups in order, each brush with its name, tip and family */
  api.catalog = function () {
    return GROUPS.map(function (g) {
      return {
        id: g, name: t('cpmplus.art.br.g.' + g),
        items: PRESETS.filter(function (p) { return p.group === g; }).map(function (p) {
          return { id: p.id, name: t('cpmplus.art.br.p.' + p.id), desc: t('cpmplus.art.br.p.' + p.id + '.desc'), fam: p.fam };
        })
      };
    });
  };
  /** the brush the Pincéis tool paints with */
  api.current = function () { return preset().id; };
  /** true when the options Espalhar / Variação do something for this brush */
  api.uses = function (id) { var p = BY_ID[id] || preset(); return { scatter: !!USES_SCATTER[p.fam], vary: !!USES_VARY[p.fam] }; };
  api.pick = pick;
  api.setPreset = setPreset;
  api.groupOf = function (id) { return BY_ID[id] ? BY_ID[id].group : null; };
  api.familyOf = function (id) { return BY_ID[id] ? BY_ID[id].fam : null; };
  /** QA: fixed random seed for the next strokes (null = random again) */
  api.setSeed = function (n) { fixedSeed = n; };
  api.preview = function (id, pixel) { return renderPreview(id, L.primary(), L.secondary(), pixel); };
  /** HD previews or pixel art previews right now (the texture being painted) */
  api.previewPixel = previewPixel;
  /** QA: a brush on a virtual w x h image along pts -> RGBA array (o: {c1, c2, base [r,g,b,a], seed, hd}) */
  api.virtualStroke = function (id, w, h, size, pts, o) {
    var p = BY_ID[id]; if (!p || p.fam === 'native') return null;
    o = o || {};
    var b = o.base || [70, 76, 88, 255], u = o.under;// under: RGBA bytes (w x h) to paint over, so strokes can be layered
    var fill = u ? function (x, y) { var i = (y * w + x) * 4; return [u[i], u[i + 1], u[i + 2], u[i + 3]]; } : function () { return b; };
    var img = virtualStroke(p, w, h, size, pts, o.c1 || L.primary(), o.c2 || L.secondary(), fill, o.seed !== undefined ? o.seed : (fixedSeed !== null ? fixedSeed : 4242), !o.hd);
    return Array.from(img.data);
  };
  return api;
})
