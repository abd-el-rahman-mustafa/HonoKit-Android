// Draws HonoKit's mark (the desktop's build/icon.svg, decisions §72 there; refined 2026-10-04) as
// Android vector drawables: the in-app logo, the adaptive launcher icon's foreground, and the
// themed (monochrome) icon. The mark: a violet PDF page and a shorter photo (3:4; gold sky, violet
// hills, a sun) leaning in close behind a paper flame whose glow lights the two cards (never the
// space around them), mirrored (right to left) as Arabic reads, and scaled to fill its square. Vector drawables have no blur: the
// shadow under the folded corner becomes a faint flat shape, and the SVG's shadows under the page
// and the flame are left out (flat, they showed as grey slivers on the launcher's white).
// Everything else is the same drawing, built with the same groups (rotations, the flame's scale,
// the mirror), so gradients follow their shapes.
// It also writes the launcher's background layer: "Night", deep indigo with a warm glow.
// Run: node tools/make-icons.js   (add --preview <file.svg> to also write an SVG of what it draws)
const fs = require('fs');
const path = require('path');
const res = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'drawable');

// --- the drawing, in the desktop SVG's 64-unit space ---------------------------------------
const lin = (x1, y1, x2, y2, stops) => ({ linear: [x1, y1, x2, y2], stops });
const rad = (cx, cy, r, stops) => ({ radial: [cx, cy, r], stops });
const solid = (color, alpha = 1) => ({ color, alpha });
const p = (d, fill, extra = {}) => ({ path: d, fill, ...extra });
const g = (t, children, clip) => ({ group: t, children, clip });

const photoShape = 'M19,23H30A6,6 0 0 1 36,29V48A6,6 0 0 1 30,54H19A6,6 0 0 1 13,48V29A6,6 0 0 1 19,23Z';
const pageShape = 'M36,12H41L51,22V47A7,7 0 0 1 44,54H35A7,7 0 0 1 28,47V19A7,7 0 0 1 35,12Z';
const foldShape = 'M41,12V17A5,5 0 0 0 46,22H51Z';
const shift = (d, dx, dy) => d.replace(/([MLHVAZ])([^MLHVAZ]*)/g, (all, cmd, args) => {
  const v = args.split(/[ ,]+/).filter(Boolean).map(Number);
  const f = (x, y) => `${+(x + dx).toFixed(2)},${+(y + dy).toFixed(2)}`;
  if (cmd === 'M' || cmd === 'L') return cmd + f(v[0], v[1]);
  if (cmd === 'H') return cmd + +(v[0] + dx).toFixed(2);
  if (cmd === 'V') return cmd + +(v[0] + dy).toFixed(2);
  if (cmd === 'A') return `A${v[0]},${v[1]} ${v[2]} ${v[3]} ${v[4]} ${f(v[5], v[6])}`;
  return cmd;
});
const poly = (pts) => 'M' + pts.map(([x, y]) => `${x},${y}`).join('L') + 'Z';
const box = (pts) => [Math.min(...pts.map((q) => q[0])), Math.min(...pts.map((q) => q[1])), Math.max(...pts.map((q) => q[0])), Math.max(...pts.map((q) => q[1]))];

const folds = [
  [[[29, 27], [36, 4], [32, 40]], '#fff6a0', '#ffc21e'],
  [[[36, 4], [48, 28], [32, 40]], '#ffb21e', '#ff641a'],
  [[[48, 28], [47, 48], [32, 40]], '#ff5a2a', '#e3342c'],
  [[[47, 48], [32, 60], [32, 40]], '#ff7a2a', '#f04a2c'],
  [[[32, 60], [17, 48], [32, 40]], '#ffd24d', '#ff942e'],
  [[[17, 48], [19, 30], [32, 40]], '#ffe48a', '#ffb23a'],
  [[[19, 30], [29, 27], [32, 40]], '#fffbe6', '#ffe066'],
  [[[24, 16], [29, 27], [19, 30]], '#ffc247', '#ff7a2a'],
];
// The flame: translate(cx cy) scale(.84) translate(-32.5 -32), as a group.
const flameAt = (cx, cy, children) => g({ pivot: [32.5, 32], scale: [0.84, 0.84], translate: [cx - 32.5, cy - 32] }, children);

// The photo leans in from the right and the page from the left, close behind the flame ("U3").
const photoAt = (children) => g({ pivot: [28, 58], rotate: -5, translate: [1.5, 0] }, children);
const pageAt = (children) => g({ pivot: [36, 58], rotate: 5, translate: [-1.5, 0] }, children);
// The flame's glow, drawn inside each card's clip so it lights the cards and never the empty
// space around them (no disc behind the mark). The inner groups undo the card's own turn, so the
// glow stays where the flame is.
const flameGlow = () => p('M13.5,39A19,19 0 1 0 51.5,39A19,19 0 1 0 13.5,39Z', rad(32.5, 39, 19, [[0, '#ffd23f', 0.7], [0.6, '#ff9a1c', 0.25], [1, '#ff7a1a', 0]]));
const glowOnPhoto = () => g({ pivot: [28, 58], rotate: 5 }, [g({ translate: [-1.5, 0] }, [flameGlow()])]);
const glowOnPage = () => g({ pivot: [36, 58], rotate: -5 }, [g({ translate: [1.5, 0] }, [flameGlow()])]);

function colour() {
  return [
    // The photo (3:4, shorter than the page): a gold sky, violet hills, a sun.
    photoAt([
      p(photoShape, lin(24.5, 23, 24.5, 54, [[0, '#ffe27a'], [1, '#f5a524']])),
      g({}, [
        p(poly([[13, 54], [13, 45], [18.5, 39], [24, 45.5], [30, 38.5], [36, 44], [36, 54]]), lin(0, 38.5, 0, 54, [[0, '#a99bff'], [1, '#7a62f5']])),
        p(poly([[13, 54], [17, 49], [21.5, 52.5], [26.5, 46], [36, 54]]), lin(0, 46, 0, 54, [[0, '#6247e8'], [1, '#3b23a8']])),
        // The fire's light on the edge nearest to it.
        p('M20,20H38V60H20Z', lin(36, 0, 25, 0, [[0, '#ff7a3a', 0.55], [1, '#ff7a3a', 0]])),
        glowOnPhoto(),
        p(poly([[13, 23], [36, 23], [13, 54]]), solid('#ffffff', 0.14)),
      ], photoShape),
      p('M13.5,30A6,6 0 1 0 25.5,30A6,6 0 1 0 13.5,30Z', rad(19.5, 30, 6, [[0, '#ffffff', 0.6], [1, '#ffffff', 0]])),
      p('M16.5,30A3,3 0 1 0 22.5,30A3,3 0 1 0 16.5,30Z', solid('#ffffff')),
    ]),
    // The PDF page, creased, with its folded corner.
    pageAt([
      p(pageShape, lin(28, 12, 51, 54, [[0, '#6f9bff'], [0.5, '#6d46f5'], [1, '#4a2dc4']])),
      g({}, [
        p(poly([[28, 12], [51, 54], [28, 54]]), solid('#1b0f5c', 0.15)),
        p('M20,0H50V64H20Z', lin(28, 0, 42, 0, [[0, '#ff7a6a', 0.6], [1, '#ff7a6a', 0]])),
        glowOnPage(),
        p('M0,0H64V64H0Z', rad(20, 10, 36, [[0, '#ffffff', 0.32], [1, '#ffffff', 0]])),
        p(shift(foldShape, -1.2, 1.4), solid('#1b0f5c', 0.18)),
      ], pageShape),
      p(foldShape, lin(41, 12, 51, 22, [[0, '#ece7ff'], [1, '#9d8aff']])),
    ]),
    // The paper flame between them (its glow is on the cards, above).
    flameAt(32.5, 35.5, folds.map(([pts, a, b]) => {
      const [x1, y1, x2, y2] = box(pts);
      return p(poly(pts), lin(x1, y1, x2, y2, [[0, a], [1, b]]), { stroke: 0.8 });
    })),
  ];
}

// One colour (themed icons, Android 13+): the system tints it, so only alpha tells parts apart.
function mono() {
  const shade = [1, 0.82, 0.68, 0.78, 0.92, 0.88, 1, 0.84];
  return [
    photoAt([p(photoShape, solid('#ffffff', 0.45))]),
    pageAt([p(pageShape, solid('#ffffff', 0.7)), p(foldShape, solid('#ffffff', 0.95))]),
    flameAt(32.5, 35.5, folds.map(([pts], i) => p(poly(pts), solid('#ffffff', shade[i]), { stroke: 0.8 }))),
  ];
}

// The mark as drawn: mirrored (right to left), scaled 1.22 about (32.1, 35.5) and moved to
// (32, 30.5), as the SVG's translate(32 30.5) scale(-1.22 1.22) translate(-32.1 -35.5).
const mirrored = (children) => g({ pivot: [32.1, 35.5], scale: [-1.22, 1.22], translate: [-0.1, -5] }, children);

// --- writers: VectorDrawable, and an SVG twin for checking --------------------------------
const hex2 = (a) => Math.round(a * 255).toString(16).padStart(2, '0').toUpperCase();
const argb = (c, a = 1) => `#${hex2(a)}${c.replace('#', '').toUpperCase()}`;
let ids = 0;

function gradientXml(f, pad) {
  const items = f.stops.map(([o, c, a = 1]) => `${pad}        <item android:offset="${o}" android:color="${argb(c, a)}" />`).join('\n');
  const head = f.linear
    ? `android:type="linear" android:startX="${f.linear[0]}" android:startY="${f.linear[1]}" android:endX="${f.linear[2]}" android:endY="${f.linear[3]}"`
    : `android:type="radial" android:centerX="${f.radial[0]}" android:centerY="${f.radial[1]}" android:gradientRadius="${f.radial[2]}"`;
  return `${pad}    <gradient ${head}>\n${items}\n${pad}    </gradient>`;
}

function vd(nodes, depth = 1) {
  const pad = '    '.repeat(depth);
  return nodes.map((n) => {
    if (n.group) {
      const t = n.group;
      const a = [];
      if (t.rotate) a.push(`android:rotation="${t.rotate}"`);
      if (t.pivot) a.push(`android:pivotX="${t.pivot[0]}"`, `android:pivotY="${t.pivot[1]}"`);
      if (t.scale) a.push(`android:scaleX="${t.scale[0]}"`, `android:scaleY="${t.scale[1]}"`);
      if (t.translate) a.push(`android:translateX="${t.translate[0]}"`, `android:translateY="${t.translate[1]}"`);
      const clip = n.clip ? `${pad}    <clip-path android:pathData="${n.clip}" />\n` : '';
      return `${pad}<group${a.length ? ' ' + a.join(' ') : ''}>\n${clip}${vd(n.children, depth + 1)}\n${pad}</group>`;
    }
    const f = n.fill;
    const stroke = n.stroke ? ` android:strokeWidth="${n.stroke}" android:strokeLineJoin="round"` : '';
    if (f.color) {
      const sc = n.stroke ? ` android:strokeColor="${argb(f.color, f.alpha)}"` : '';
      return `${pad}<path android:pathData="${n.path}" android:fillColor="${argb(f.color, f.alpha)}"${sc}${stroke} />`;
    }
    const grad = gradientXml(f, pad);
    const fillAttr = `${pad}    <aapt:attr name="android:fillColor">\n${grad}\n${pad}    </aapt:attr>`;
    const strokeAttr = n.stroke ? `\n${pad}    <aapt:attr name="android:strokeColor">\n${grad}\n${pad}    </aapt:attr>` : '';
    return `${pad}<path android:pathData="${n.path}"${stroke}>\n${fillAttr}${strokeAttr}\n${pad}</path>`;
  }).join('\n');
}

function svg(nodes, defs) {
  return nodes.map((n) => {
    if (n.group) {
      const t = n.group;
      const [px, py] = t.pivot || [0, 0];
      const [tx, ty] = t.translate || [0, 0];
      const parts = [`translate(${tx + px} ${ty + py})`];
      if (t.rotate) parts.push(`rotate(${t.rotate})`);
      if (t.scale) parts.push(`scale(${t.scale[0]} ${t.scale[1]})`);
      parts.push(`translate(${-px} ${-py})`);
      let clip = '';
      if (n.clip) {
        const id = `c${ids++}`;
        defs.push(`<clipPath id="${id}"><path d="${n.clip}"/></clipPath>`);
        clip = ` clip-path="url(#${id})"`;
      }
      // The clip belongs to the group's own space, so it sits on an inner <g>.
      return `<g transform="${parts.join(' ')}"><g${clip}>${svg(n.children, defs)}</g></g>`;
    }
    const f = n.fill;
    let fill;
    if (f.color) fill = `fill="${f.color}" fill-opacity="${f.alpha}"`;
    else {
      const id = `g${ids++}`;
      const stops = f.stops.map(([o, c, a = 1]) => `<stop offset="${o}" stop-color="${c}" stop-opacity="${a}"/>`).join('');
      defs.push(f.linear
        ? `<linearGradient id="${id}" gradientUnits="userSpaceOnUse" x1="${f.linear[0]}" y1="${f.linear[1]}" x2="${f.linear[2]}" y2="${f.linear[3]}">${stops}</linearGradient>`
        : `<radialGradient id="${id}" gradientUnits="userSpaceOnUse" cx="${f.radial[0]}" cy="${f.radial[1]}" r="${f.radial[2]}">${stops}</radialGradient>`);
      fill = `fill="url(#${id})"`;
    }
    const stroke = n.stroke ? (f.color ? ` stroke="${f.color}" stroke-opacity="${f.alpha}"` : ` stroke="${fill.slice(6, -1)}"`) + ` stroke-width="${n.stroke}" stroke-linejoin="round"` : '';
    return `<path d="${n.path}" ${fill}${stroke}/>`;
  }).join('');
}

const header = (size, viewport) => `<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/make-icons.js from HonoKit's mark (desktop build/icon.svg). Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="${size}dp"
    android:height="${size}dp"
    android:viewportWidth="${viewport}"
    android:viewportHeight="${viewport}">
`;

// Launcher layers are 108 x 108; the mark is scaled into the 66 dp safe circle, centred.
const launcher = (children) => [g({ pivot: [32, 32], scale: [0.86, 0.86], translate: [22, 22] }, [mirrored(children)])];

// The launcher's background layer (the mark itself stands free; launchers need one): "Night", the
// owner's choice (2026-10-04). Deep indigo, lighter in the middle, with a soft warm glow where the
// flame stands (54, 56), so the fire lights its own icon. Full bleed: the launcher cuts the shape.
const background = [
  p('M0,0H108V108H0Z', rad(54, 60, 72, [[0, '#3a2d92'], [0.55, '#1c1556'], [1, '#0c0a2c']])),
  p('M0,0H108V108H0Z', rad(54, 56, 34, [[0, '#ff9a3c', 0.38], [1, '#ff9a3c', 0]])),
];

const logo = [mirrored(colour())];
const out = {
  'logo.xml': header(64, 64) + vd(logo) + '\n</vector>\n',
  'ic_launcher_foreground.xml': header(108, 108) + vd(launcher(colour())) + '\n</vector>\n',
  'ic_launcher_monochrome.xml': header(108, 108) + vd(launcher(mono())) + '\n</vector>\n',
  'ic_launcher_background.xml': header(108, 108) + vd(background) + '\n</vector>\n',
};
const target = process.argv.includes('--out') ? process.argv[process.argv.indexOf('--out') + 1] : res;
for (const [name, xml] of Object.entries(out)) fs.writeFileSync(path.join(target, name), xml);

const previewAt = process.argv.indexOf('--preview');
if (previewAt > 0) {
  const defs = [];
  const tile = (nodes, x, clip) => {
    const id = `t${ids++}`;
    defs.push(`<clipPath id="${id}">${clip}</clipPath>`);
    return `<svg x="${x}" y="10" width="216" height="216" viewBox="18 18 72 72"><g clip-path="url(#${id})">${svg(background, defs)}${svg(nodes, defs)}</g></svg>`;
  };
  const body = [
    tile(launcher(colour()), 10, '<circle cx="54" cy="54" r="36"/>'),
    tile(launcher(colour()), 250, '<rect x="18" y="18" width="72" height="72" rx="20"/>'),
    `<svg x="490" y="10" width="216" height="216" viewBox="18 18 72 72"><circle cx="54" cy="54" r="36" fill="#d7e3ff"/><g fill="#1b3b8f">${svg(launcher(mono()), defs).replace(/fill="#ffffff"/g, 'fill="#1b3b8f"').replace(/stroke="#ffffff"/g, 'stroke="#1b3b8f"')}</g></svg>`,
    `<svg x="730" y="20" width="196" height="196" viewBox="0 0 64 64">${svg(logo, defs)}</svg>`,
  ].join('\n');
  fs.writeFileSync(process.argv[previewAt + 1], `<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="236" viewBox="0 0 1000 236"><rect width="1000" height="236" fill="#202124"/><defs>${defs.join('')}</defs>${body}</svg>`);
}
console.log('icons written to', target);
