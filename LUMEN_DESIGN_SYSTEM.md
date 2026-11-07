# LANShare "Lumen" Design System v1.0

One visual language for the whole app: page (`ui.html`), native screens (Kotlin Activities), dialogs, sheets, notifications, launcher, splash. Specification only, no code. Every value here is a **token**; screens may use tokens, never raw values.

**Contents**
1. Audit: what is inconsistent today
2. Principles
3. Layer model (depth)
4. Colour (ramps, slots, light / dark, semantic, category)
5. Gradients
6. Glass
7. Typography
8. Shape
9. Spacing and layout
10. Elevation and shadow
11. Iconography
12. Components (buttons, fields, lists, bars, sheets, dialogs, feedback ...)
13. Motion and haptics
14. Theme catalogue
15. Native and page parity (how both read the same tokens)
16. Screen-by-screen application
17. Accessibility
18. Performance budget and fallbacks
19. Migration plan, acceptance tests, do / don't

---

## 1. Audit: what is inconsistent today

Measured on the current code (`ui.html` + Kotlin):

| Area | Finding | Consequence |
|---|---|---|
| Corner radius | 14 different values in the page CSS: 0, 2, 4, 8, 9, 10, 12, 14, 16, 18, 20, 24, 28, 50% | Neighbouring elements have unrelated curvature |
| Dialogs | 5 families: page confirm card (`dlg`), bottom sheet (`#sheet .card`, 28 top radius), sheet variants (`.vs` radius 0, `.fu` full screen, `.pj` 20), native `AlertDialog` (28 dp window drawable, white / dark), plus per-feature cards | Same question looks different depending on where it is asked |
| Feedback | `Toast.makeText` used 60+ times in native screens, a different `toast()` in the page | Two message styles, no actions, no colour meaning |
| Colour | About 40 theme blocks; each defines a flat set (`--bg --card --cont --ac --sel --fab ...`); native gets only 7 of them (`AppPal`: bg, cont, fg, mut, bd, ac, onac) | Native screens look "near" but not equal to the page |
| Gradients / glass | None. `backdrop-filter` is used 0 times; gradients only in a few tiles | Flat, "app-like" rather than "OS-like" |
| Icons | Page uses inline SVG path set (`ic()`, `HM_P`, `NlIcons.raw`), native uses vector drawables and Material-style paths, folders have their own drawing (`NlClassicFolder`), file badges have another | Different stroke weights, fills and corner treatments |
| Shadows | One `--sh` token used 11 times, many one-off values | Depth is inconsistent |
| Motion | Ad-hoc `transition` values (.15 s, .2 s, .3 s, .4 s, .5 s) | No shared rhythm |
| Type | One embedded family (Figtree LS) but sizes set per element | No scale |

Goal: collapse each row to one rule set.

---

## 2. Principles

1. **One system, many skins.** Structure (shape, spacing, type, motion, icon grid) never changes between themes. Only colour, wallpaper and glass tint change.
2. **Depth through light, not borders.** Surfaces are separated by tone, soft shadow and a 1 dp light edge, not by dark outlines.
3. **Glass is chrome, not content.** Blur is for bars, sheets, dialogs and floating controls. Pictures, lists and text never sit on a blurred background that moves under them.
4. **Colour has meaning.** Accent = primary action and selection. Semantic colours = state. Category colours = file type. Nothing else is coloured.
5. **Calm first, delight second.** Gradients are tonal (one hue family, small drift), motion is short, nothing loops except progress.
6. **Same question, same dialog.** Any confirmation anywhere in the app uses the same component, same layout and same button order.
7. **Degrade gracefully.** Every glass or gradient surface has a defined solid fallback. The app must look intentional on a cheap phone with "Reduce transparency" on.
8. **Touch first.** 48 dp minimum targets, thumb-zone actions at the bottom.

---

## 3. Layer model (depth)

Every pixel belongs to exactly one layer. Layers define surface colour, blur, shadow and corner radius together.

| Layer | Name | What lives here | Surface | Blur | Shadow |
|---|---|---|---|---|---|
| L0 | Wallpaper | App background behind everything | Aurora mesh (section 5.4) or flat Canvas | none | none |
| L1 | Canvas | Page background of file browser, settings, lists | Canvas colour (tinted, slightly translucent over L0 in glass mode) | none | none |
| L2 | Surface | Cards, tiles, list groups, album cards, storage card | Surface colour + 1 dp edge | none | E1 |
| L3 | Chrome | Top app bar, selection bar, bottom action bar, drawer panel | Glass G1 | 24 dp | E2 |
| L4 | Float | Floating action bar, popup menu, snackbar, tooltips, FAB | Glass G2 | 32 dp | E3 |
| L5 | Modal | Dialogs, bottom sheets, pickers | Glass G3 | 40 dp | E4 |
| L6 | Scrim | Dim layer under L5 | Black / Ink at 32 % (light) or 56 % (dark) | none (optional 6 dp blur on L1 behind) | none |
| L7 | Viewer | Full-screen image, video, PDF stage | Pure black stage (or theme "Ink") | none | none |

Rules: a higher layer is always brighter (light mode) or more luminous (dark mode) than the one below. Never put L5 on L5 (a dialog opened from a sheet replaces the sheet's scrim level, it does not stack glass on glass; the sheet dims to 70 % and the dialog becomes the only blurred element).

---

## 4. Colour

### 4.1 Model

Every theme is generated from **three inputs**: accent hue, neutral tint (0 to 8 % of the accent hue mixed into greys), mode (light / dark / amoled). From them the system builds tonal ramps (0 to 100, Material 3 style) and fills the named slots below. Hand-picked per-theme hex lists (today's approach) are replaced by this generation, with the existing themes kept as **presets** of the three inputs plus an optional wallpaper (section 14).

### 4.2 Default theme "Ember" (keeps the app's current orange identity)

Accent ramp (hue about 24):

| Tone | Hex | Use |
|---|---|---|
| T0 | #000000 | |
| T10 | #3A1A00 | on-container text (light) |
| T20 | #5E2900 | |
| T30 | #8F3E00 | |
| T40 | #C25100 | Primary (light) , equals today's accent |
| T50 | #E4690F | |
| T60 | #FF8A3D | |
| T70 | #FFA566 | |
| T80 | #FFB77C | Primary (dark), equals today's dark accent |
| T90 | #FFDCC2 | Primary container (light), equals today's `--sel` |
| T95 | #FFEDE0 | |
| T98 | #FFF8F4 | |
| T100 | #FFFFFF | |

Neutral ramp (warm, hue 28, chroma about 4): N10 #1F1A16, N20 #342E29, N30 #4B443E, N40 #62594F, N50 #7B7168, N60 #968B81, N70 #B1A59A, N80 #CDC1B5, N90 #E9DDD0, N95 #F6EBDF, N98 #FDF3E9, N99 #FFFAF5. Dark canvas uses N6 #17110C and N4 #120D09 (AMOLED variant: #000000).

### 4.3 Slot list (every theme fills ALL of these; native and page read the same names)

| Slot | Role | Ember Light | Ember Dark |
|---|---|---|---|
| `canvas` | L1 page background | #FFF8F2 | #17110C |
| `surface` | L2 cards, tiles | #FFFFFF | #231B14 |
| `surfaceHi` | L2 raised / hovered / selected row base | #FBEFE4 | #2E241B |
| `surfaceLo` | wells, inset fields, skeleton base | #F4E8DB | #120D09 |
| `edge` | 1 dp divider / card edge | #EADFD4 | #3A2E24 |
| `edgeHi` | glass top-left highlight | #FFFFFF at 70 % | #FFFFFF at 16 % |
| `ink` | primary text | #1F1A16 | #EFE5DB |
| `inkMut` | secondary text, icons at rest | #5A5047 | #C9BBAD |
| `inkDim` | disabled, hints (never for needed info) | #8C8176 | #8E8174 |
| `primary` | accent for fills, links, active icons | #C25100 | #FFB77C |
| `onPrimary` | text / icon on primary | #FFFFFF | #4A2800 |
| `primaryCont` | selected state, chips on, tonal buttons | #FFDCC2 | #7A3E00 |
| `onPrimaryCont` | | #3A1A00 | #FFDCC2 |
| `secondary` | quiet accent (hue +40, chroma low) | #8A5A3C | #E3BFA6 |
| `tertiary` | highlight accent (hue +150) for badges, "new" | #3F6B55 | #A5D2B8 |
| `scrim` | L6 | #1A0F05 at 32 % | #000000 at 56 % |
| `glassTint` | base of G1-G3 (section 6) | #FFF8F2 | #1E1610 |
| `focus` | keyboard / TalkBack focus ring | #0B5FFF | #8AB4FF |

### 4.4 Semantic colours (same hue in all themes, tone shifts with mode)

| Meaning | Light fill / on | Dark fill / on | Used for |
|---|---|---|---|
| Success | #1E8E3E / #FFFFFF, container #D7F2DD / #0B3A19 | #7FD99A / #00391A, container #14502A / #CFF5D8 | finished job, connected, saved |
| Warning | #A66300 / #FFFFFF, container #FFE7BF / #3D2300 | #FFC15E / #3D2300, container #5C3A00 / #FFE7BF | low space, slow device, conflicts |
| Error | #B3261E / #FFFFFF, container #F9DEDC / #410E0B | #F2B8B5 / #601410, container #8C1D18 / #F9DEDC | failed job, destructive action |
| Info | #0B6BCB / #FFFFFF, container #D6E8FB / #002B55 | #9CC8FF / #00315F, container #174A80 / #D6E8FB | tips, neutral notices |

Semantic colour is never the only signal: always paired with an icon and a word.

### 4.5 Category colours (file types, replaces the six `--hc1..6` home colours and folder / badge colours)

One hue per category, defined as a 3-stop family: **base** (icon glyph / badge), **tint** (tile background light), **deep** (tile background dark). Gradient tile = tint to base at 135 degrees (section 5.3).

| Category | Hue | Base light / dark | Notes |
|---|---|---|---|
| Folder | accent (theme) | primary / primary | folders use theme accent, plus user "Style" colours (11) which map to these ramps |
| Pictures | 340 magenta-pink | #C2185B / #FF8FB8 | |
| Video | 265 violet | #6A4BC4 / #C6B3FF | |
| Audio | 205 blue | #0B6BCB / #9CC8FF | |
| Documents (Word, text) | 220 deep blue | #1E5FB0 / #A8C7FA | |
| Spreadsheets | 145 green | #1E8E3E / #7FD99A | |
| Slides | 20 orange-red | #C4431A / #FFB59E | |
| PDF | 5 red | #B3261E / #F2B8B5 | |
| Archives | 45 amber | #A66300 / #FFC15E | |
| Code / text | 175 teal | #00796B / #8AE0D0 | |
| APK / apps | 95 lime-green | #5D7A00 / #C5E27F | |
| Other / unknown | neutral | inkMut | |

The same table feeds: list icons, grid badges, Storage Analyzer treemap and pie, search result chips, Drive / FTP / SMB badges.

### 4.6 Opacity ladder (the only alpha values allowed on ink / primary overlays)

| Token | Value | Use |
|---|---|---|
| `st.hover` | 8 % | pointer hover, list row hover |
| `st.focus` | 12 % | keyboard focus fill |
| `st.press` | 14 % | pressed fill (ripple peak) |
| `st.drag` | 18 % | dragging item |
| `st.disabled.fg` | 38 % | disabled text / icon |
| `st.disabled.bg` | 12 % | disabled fill |
| `st.scrim.light` | 32 % | modal scrim light mode |
| `st.scrim.dark` | 56 % | modal scrim dark mode |

Overlays use `ink` in light mode and `primary` tinted white in dark mode (so dark hover looks luminous, not grey).

### 4.7 Contrast rules (hard)

- Body text on any surface: at least 4.5:1. Large text (18 sp+ or 14 sp bold): at least 3:1. Icons that carry meaning and component edges: at least 3:1.
- Text on **glass** is measured against the worst case: tint at its minimum alpha over the brightest and darkest wallpaper area. If it fails, the tint alpha rises until it passes. Glass legibility wins over transparency.
- Text on a **gradient** must pass on both end stops.
- Selected-state colours must differ from rest by at least 1.5:1 luminance ratio, so selection is visible in greyscale.
- Every theme is rejected unless all slot pairs in section 4.3 pass an automated contrast table (acceptance test T1, section 19).

---

## 5. Gradients

Gradients are **tonal**: same hue family, small hue drift, never rainbow (exception: wallpaper aurora, section 5.4).

### 5.1 Rules

- Angle 135 degrees (top-left to bottom-right) for fills; 180 degrees (top to bottom) for bars and headers; radial for glow.
- Two stops for buttons and tiles, three for hero areas.
- Hue drift: start stop is 6 to 10 degrees toward yellow / warm side, end stop 4 to 8 degrees toward the cool side. Lightness difference between stops: 8 to 14 tone points.
- Both stops must pass the contrast rule against the label colour.
- Gradients are rendered once into a cached bitmap / CSS variable per theme; they are not recomputed on scroll.
- No gradient on text, on dividers, or behind body paragraphs.

### 5.2 Named gradients (Ember values; other themes derive from their own ramp)

| Name | Light stops | Dark stops | Used on |
|---|---|---|---|
| `gPrimary` | #D2590A to #A93F00 (135) | #FFB77C to #FF9A4D (135) | primary button, FAB, active tab indicator, progress fill, selected check circle |
| `gPrimarySoft` | T95 to T90 (135) | #5C3000 to #7A3E00 | tonal button, selected chip, selected row |
| `gSurface` | #FFFFFF to #FFF4EA (180) | #2A2118 to #231B14 | cards, storage card, album card footer |
| `gChrome` | glass tint at 78 % to glass tint at 66 % (180) | glass tint at 70 % to 58 % | top bar, bottom bar (adds a subtle top-light feel) |
| `gDanger` | #C93A2F to #9E1C14 (135) | #FFB4AB to #FF8A7F | destructive button |
| `gSuccess` | #2AA553 to #17753A (135) | #8FE3A8 to #5FCB82 | success progress, done badge |
| `gGlow` | primary at 28 % centre to 0 % edge (radial, 60 percent radius) | primary at 22 % to 0 % | focus halo behind FAB, splash logo, empty-state icon |
| `gSkeleton` | surfaceLo to surfaceHi to surfaceLo (90 degrees, moving) | same | loading placeholders (section 12.20) |
| `gScrimTop` / `gScrimBottom` | black 55 % to 0 % (180 / 0) | same | text legibility over pictures (thumbnail captions, video duration, viewer top and bottom bars) |

### 5.3 Category tile gradient

Tile (40 dp rounded square, section 11.6): tint at top-left to base at 15 % darker at bottom-right (135). Glyph is white (light) / deep (dark) with 1 dp soft inner shadow so it sits in the tile.

### 5.4 Wallpaper "Aurora" (L0, optional, on by default in Ember)

- Base: `canvas`.
- Four large soft blobs (radial gradients, 55 to 75 % of screen width, positioned: top-left, top-right-low, mid-left-low, bottom-right) in `primary`, `secondary`, `tertiary` and `primary` shifted +30 degrees, each at 10 to 14 % alpha (light) or 14 to 20 % (dark).
- Blob positions drift very slowly **only on the home menu** (one cycle 60 s, paused when screen is not home, when battery saver is on, or with Reduce motion). Elsewhere the wallpaper is static.
- Grain: 2.5 % monochrome noise tile (128 px) over the wallpaper to prevent colour banding and to make glass look physical.
- AMOLED theme: wallpaper off, pure black, glass tint is #0B0B10.
- Wallpaper never appears behind text-heavy L1 screens at more than 6 % blob alpha (settings, long lists get a flatter variant: same hues, 6 %).

---

## 6. Glass

### 6.1 Recipe (every glass surface is built from the same 7 ingredients)

1. **Backdrop blur** (radius per tier).
2. **Saturation boost** of the blurred backdrop (130 to 150 %) so colours behind glow instead of greying.
3. **Tint** (`glassTint` at a tier alpha).
4. **Vertical tint gradient** (`gChrome`) so the top feels lighter.
5. **Edge highlight**: 1 dp inner stroke, brightest at top-left fading to 0 at bottom-right (`edgeHi`), simulates light catching the rim.
6. **Outer hairline**: 0.5 dp `edge` at 60 % so the shape stays readable on very similar backgrounds.
7. **Shadow** of the tier (section 10), coloured with the theme's shadow tint.
Plus grain at 2 % on G2 and G3.

### 6.2 Tiers

| Tier | Layer | Blur | Tint alpha light / dark | Saturation | Radius | Examples |
|---|---|---|---|---|---|---|
| G0 Frost | on-picture overlays | 12 dp | black 32 % / black 40 % | 120 % | 8 / pill | video duration pill, thumbnail count badge, image viewer info chips |
| G1 Chrome | L3 | 24 dp | 72 % / 62 % | 140 % | 0 (bars) or 28 (floating) | top app bar, selection bar, bottom action bar, drawer panel |
| G2 Float | L4 | 32 dp | 78 % / 70 % | 145 % | 20 to 28 | popup menu, snackbar, FAB container, tooltip, floating paste bar |
| G3 Modal | L5 | 40 dp | 84 % / 76 % | 150 % | 28 to 32 | dialogs, bottom sheets, pickers |

Each tier's tint alpha is a **floor**: the contrast rule (4.7) may push it higher per theme.

### 6.3 Where glass is allowed / forbidden

| Allowed | Forbidden |
|---|---|
| Top app bar when content scrolls beneath it | Rows of a list, grid thumbnails, album covers |
| Selection bar and bottom action bar | The reading area of Word / PDF / text viewers (solid page) |
| Drawer panel (over scrim) | Settings rows |
| Sheets, dialogs, popup menus, snackbar | Anything inside a dialog or sheet except its own header |
| Floating paste bar, floating FAB group | Two glass layers directly stacked |
| On-picture pills (G0) | Progress bars, charts, treemap cells |

### 6.4 Behaviour

- Glass surfaces are **static in tone**: they do not change tint while scrolling, except the top bar which goes from fully transparent (scroll position 0) to G1 over the first 24 dp of scroll (opacity ramp 150 ms).
- Maximum **two** blurred surfaces visible at once (e.g. top bar + bottom bar). A third one (a dialog) turns the first two into their solid fallback while it is open (their blur is not needed behind a 56 % scrim).
- Fast scroll (fling) over a grid: bars may drop to solid for the duration of the fling and return 120 ms after it stops (adaptive; section 18).

### 6.5 Fallback ladder (decided automatically, user can force the lowest)

| Step | Condition | Result |
|---|---|---|
| F0 full glass | blur supported, no power saving, device class normal or high | as specified |
| F1 light glass | blur supported but device class low, or battery saver | blur radius halved, no saturation boost, no grain |
| F2 frosted solid | blur unsupported (older Android System WebView, native API below 31) | tint alpha raised to 94 % / 92 % so it reads as a solid translucent sheet, keeps edge highlight and shadow |
| F3 flat | user setting "Reduce transparency" or high-contrast mode | fully opaque `surface`, no gradient on chrome, edge 1 dp `edge` |

All four steps keep layout, radii, spacing and icons identical; only the material changes.

---

## 7. Typography

One family: **Figtree** (already embedded as "Figtree LS", weights 100 to 900, includes Turkish glyphs İ ı Ş ş Ğ ğ Ç ç Ö ö Ü ü; verify before release). User-selected custom fonts and the system font stay available as an override of the **family only**; sizes, weights and spacing below do not change.

| Style | Size / line height (sp) | Weight | Tracking | Use |
|---|---|---|---|---|
| Display | 36 / 44 | 600 | -0.5 % | splash, empty-state hero number (free space) |
| Headline L | 28 / 36 | 600 | -0.25 % | storage card figure, home greeting |
| Headline M | 24 / 32 | 600 | 0 | screen titles in large top bar |
| Title L | 20 / 28 | 600 | 0 | dialog title, sheet title, day heading in Month mode |
| Title M | 16 / 24 | 600 | +0.1 % | top bar title (small), card title, **day heading** (Day mode) |
| Title S | 14 / 20 | 600 | +0.1 % | list section headers, settings group labels |
| Body L | 16 / 24 | 400 | +0.3 % | paragraphs in dialogs and sheets |
| Body M | 14 / 20 | 400 | +0.2 % | list primary line, descriptions |
| Body S | 12 / 16 | 400 | +0.3 % | list secondary line, helper text |
| Label L | 14 / 20 | 600 | +0.1 % | button text |
| Label M | 12 / 16 | 600 | +0.4 % | chips, tab labels, bottom action bar labels |
| Label S | 11 / 16 | 600 | +0.5 % | badges, counters, overlines (uppercase only if locale-aware) |
| Mono | 13 / 20 | 450 | 0 | paths, IP addresses, code, remote shell (Fnt.kt monospace) |

Rules:
- Numbers in sizes, counts, speeds, times use **tabular figures** so columns align and counters do not jitter.
- Uppercase transformation must be locale-aware (Turkish `i` becomes `İ`, `ı` becomes `I`). Prefer sentence case everywhere; never uppercase user text.
- Paths and file names: single line, middle ellipsis (keep extension visible); never wrap file names in list rows, allow 2 lines in grid captions.
- Font scale: layouts must hold at 130 % and stay usable at 200 % (section 17); line heights scale with size.
- Max line length in reading surfaces (dialogs, Word reading mode, settings descriptions): 60 to 72 characters.
- Contrast of secondary text uses `inkMut`, never lower than 4.5:1.

---

## 8. Shape

### 8.1 Radius scale (replaces the 14 current values)

| Token | dp | Old values folded into it | Use |
|---|---|---|---|
| `r.xs` | 4 | 2, 4 | focus ring corner, tiny badges, scrollbar thumb ends, progress track in dense rows |
| `r.s` | 8 | 8, 9, 10 | chips (rectangular variant), small inputs, tooltips, G0 pills (when not full) |
| `r.m` | 12 | 12 | thumbnails, list-row selected background, text fields, segmented control |
| `r.l` | 16 | 14, 16, 18 | cards, tiles, album covers, popup menu items hover |
| `r.xl` | 24 | 20, 24 | floating bars, popup menus, snackbar, date picker |
| `r.xxl` | 32 | 28 | dialogs, bottom sheets (top corners), large containers |
| `r.full` | 999 | 50% | buttons, chips (pill variant), switches, icon-button backgrounds, avatars, FAB (squircle option below) |

### 8.2 Rules

- **Concentric corners**: inner radius = outer radius minus the padding between them (a 16 dp padded card inside a 32 dp sheet has 16 dp corners; a thumbnail inside that card with 8 dp padding has 8 dp).
- **Thumbnails** (gallery grid cells): `r.m` when grid has 3 columns or fewer; `r.s` at 4 to 5; `r.xs` at 6 to 8; square (0) with 1 dp gaps is allowed only in the densest "Month" overview.
- **Selected thumbnail** shrinks to 88 % and re-rounds to `r.l` (matches existing behaviour, now codified), with the check circle in the top-left.
- **FAB** is a rounded square 56 dp with `r.l` plus 4 dp (20 dp), extended FAB is pill. Do not mix circle and square FABs in one app.
- **Full-screen** surfaces (viewers, full sheet) have radius 0. Sheets that do not fill the screen have `r.xxl` top corners and 0 bottom corners; on tablets / landscape (width over 600 dp) sheets float centered with all four corners `r.xxl`, max width 640 dp.
- Bars attached to the screen edge have radius 0; floating bars (detached by 12 dp margin) are `r.xl`.
- Superellipse is not used (not available natively); plain rounded rectangles only, same on page and native.

---

## 9. Spacing and layout

### 9.1 Grid

4 dp base unit. Allowed spacing tokens: 0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64. Nothing else (no 5, 6, 7, 9, 10, 14, 18 gaps).

| Token | dp | Typical use |
|---|---|---|
| `sp.1` | 4 | icon to text in chips, gap between stacked label and value |
| `sp.2` | 8 | gap between buttons in a row, grid gutter (gallery), list-icon to text distance minus 8 |
| `sp.3` | 12 | card inner padding (compact), sheet inner gap |
| `sp.4` | 16 | **screen side margin (phones)**, card inner padding, dialog inner gap |
| `sp.5` | 20 | dialog side padding on small phones |
| `sp.6` | 24 | dialog padding, section gap |
| `sp.8` | 32 | between major sections |
| `sp.10` | 40 | empty-state spacing |
| `sp.12` | 48 | touch target, top bar content height base |
| `sp.16` | 64 | large hero areas |

### 9.2 Sizes

| Item | Size |
|---|---|
| Minimum touch target | 48 x 48 dp (visual element may be smaller, hit area is not) |
| Top app bar (small) | 64 dp content + status bar inset |
| Top app bar (large, collapsing) | 112 dp expanded, 64 dp collapsed |
| Bottom action bar (selection) | 72 dp + nav bar inset, floating 12 dp from edges |
| List row, 1 line | 56 dp |
| List row, 2 lines | 72 dp |
| List row, 3 lines | 88 dp |
| List leading icon tile | 40 dp, in 16 dp left padding, 16 dp gap to text |
| Dense file row (user "compact" view) | 48 dp |
| Button height | 40 (compact), 48 (default), 56 (large / dialog primary on phones) |
| Icon button | 48 target, 40 visual circle |
| Chip | 32 dp height |
| Text field | 56 dp (outlined or filled-glass) |
| Switch | 52 x 32 dp |
| FAB | 56 dp (small 40, extended 56 high) |
| Dialog width | phone: screen width minus 2 x 24 dp, max 360 dp for alert, 560 dp for rich dialogs; tablet: 560 dp |
| Sheet max width | 640 dp |

### 9.3 Gallery grids

- Gutter 2 dp (columns 5 to 8), 4 dp (columns 3 to 4), 8 dp (columns 2). Day headings: top padding 16 dp, bottom 8 dp, left 16 dp.
- Album-home cards: 2 / 3 / 4 columns as set today, gap 12 dp, cover 4:3, caption 12 dp below cover (title Title S, count Body S `inkMut`).

### 9.4 Safe areas and breakpoints

- Status bar and navigation bar are transparent; content draws edge to edge and adds insets. Glass bars extend under the bars so the blur covers them.
- Breakpoints: compact under 600 dp, medium 600 to 899, expanded 900 and above (matches the current 600 / 900 gallery logic). Medium and expanded switch sheets to floating, show the drawer permanently at 900+, and raise margins to 24 dp (medium) and 32 dp (expanded).
- Foldables: on unfolding, layout re-flows to medium or expanded without losing selection state.

---

## 10. Elevation and shadow

Shadows are two-part (key light from top, ambient all around) and **tinted** with `shadowTint` (theme hue at 35 % chroma, never pure black) so they feel part of the palette.

| Level | Layer | Light mode (key / ambient) | Dark mode |
|---|---|---|---|
| E0 | L0, L1 | none | none |
| E1 | cards, tiles | y 1 blur 3 at 10 % / y 1 blur 2 at 6 % | edge only (shadows invisible); surface lightens by 4 % |
| E2 | G1 chrome | y 2 blur 8 at 12 % / y 1 blur 3 at 8 % | y 2 blur 10 at 40 % |
| E3 | G2 floats, FAB | y 6 blur 18 at 16 % / y 2 blur 6 at 10 % | y 8 blur 20 at 50 % |
| E4 | G3 modals | y 12 blur 32 at 20 % / y 4 blur 10 at 12 % | y 16 blur 36 at 60 % |
| E5 | drag lifted item | y 16 blur 40 at 24 % | y 20 blur 44 at 66 % |

In dark mode, **depth is shown by luminance**: each higher layer is 4 to 6 % lighter, plus the `edgeHi` rim.

---

## 11. Iconography

### 11.1 One family, one grid

Family name **Lumen Icons**. Rounded, friendly, geometric; replaces the mixed Material-path set, native vector drawables, and ad-hoc badges.

| Property | Value |
|---|---|
| Canvas | 24 x 24 dp, live area 20 x 20 (2 dp padding) |
| Style | **Outline by default, filled for "active / selected" state** (same glyph, two states: e.g. heart outline to heart filled) |
| Stroke | 1.75 dp, round caps, round joins |
| Inner corners | 2 dp radius; outer corners 3 dp; never sharp |
| Keylines | circle 20, square 18, portrait rectangle 16 x 20, landscape 20 x 16 |
| Details | no detail smaller than 1.5 dp; no gaps smaller than 2 dp |
| Duotone option | for tiles and onboarding only: outline + 20 % `primary` fill on the main shape |
| Perspective | none, front view only |
| Optical balance | circles drawn 1 dp larger than squares of the same keyline |

### 11.2 Sizes

16 (inline in text, badges), 20 (chips, dense rows), 24 (default: bars, buttons, rows), 32 (tiles in grids), 40 (list leading tiles, glyph inside is 24), 48 (empty states small), 64 (empty states, dialogs hero), 96 (splash, onboarding). Icons scale in whole steps only.

### 11.3 Colour of icons

| State | Colour |
|---|---|
| Rest (bars, rows) | `inkMut` |
| Emphasis / current | `ink` |
| Active / selected | `primary`, glyph switches to filled |
| On primary fill | `onPrimary` |
| Disabled | `ink` at 38 % |
| Destructive | semantic Error |
| On pictures | white with a 1 dp soft shadow (or inside a G0 pill) |

Icons never use a gradient on the glyph itself; gradients are only on tile backgrounds.

### 11.4 Canonical icon inventory (one icon per concept, used everywhere)

| Concept | Glyph description | Notes |
|---|---|---|
| Back | chevron-left (no stem) | top bars; arrow-left with stem only in viewers |
| Close | X | dialogs, selection mode |
| Menu | three lines, middle one shorter | drawer |
| More | three vertical dots | overflow |
| Search | circle + handle | universal search entry |
| Filter / tune | two sliders | size and sort, view options |
| Select all | square with double check | |
| Check | tick | selection circle, success |
| Add | plus | new folder, add to Print space |
| Copy | two overlapping rounded squares | |
| Cut / Move | scissors for cut; arrow into folder for Move (two icons, two meanings, never swapped) | |
| Paste | clipboard with arrow down | |
| Copy / move if new | copy glyph with small sparkle | |
| Share | node-and-two-lines (not arrow-out-of-box) | single share glyph app-wide |
| Send to device | arrow into phone outline | LAN send |
| Download | arrow down to tray | |
| Upload | arrow up from tray | |
| Delete | trash can with lid | Recycle bin glyph = trash with arrow-back |
| Restore | circular arrow | |
| Info / details | circle with i | |
| Print | printer | |
| Rename | pencil over line | |
| Edit | pencil | |
| Star / favourite | star outline to filled | |
| Hide | eye with slash | Show = eye |
| Lock | padlock | |
| Folder | rounded folder with tab | drawing shared with `NlClassicFolder` look |
| Home | house | |
| Devices | phone + monitor | |
| Wi-Fi / network | three arcs | |
| Cloud | cloud | Cloud servers, Drive |
| Server | stacked rounded bars | FTP servers |
| Storage | pie in rounded square | Storage Analyzer |
| Settings | gear with 8 soft teeth | |
| Theme | half-filled circle | |
| Refresh | circular arrow with tip | |
| Sort | two bars + arrow | |
| View grid / list | 2x2 squares / three lines | |
| Play / Pause / Next / Prev | rounded triangle / two rounded bars | player |
| Rotate / Crop / Draw / Text | edit-tool glyphs from the same grid | image editor |
| Scan / OCR | corner brackets with text lines | |
| AI / search by meaning | sparkle (4-point) | AI search, similar |
| Terminal | prompt chevron + cursor | remote shell |

Rule: a new feature picks from this list first; a new glyph is drawn only on the 11.1 grid and added here.

### 11.5 File, folder and category glyphs

- A single **glyph set** (document, sheet, slides, PDF, image, video, audio, archive, code, apk, text, unknown, folder variants) on the 24 grid, outline style.
- **Folder**: rounded body `r.l` proportions, tab 40 % width, drawn as a **two-plate folder** (back plate darker, front plate lighter, thin highlight on the front top edge). Same drawing used in the list, grid, album placeholder, breadcrumbs and picker; user folder Style colour and chosen glyph (existing 20 icons) sit centred on the front plate in `onPrimary`-like contrast.
- **File without thumbnail**: category tile (5.3) with category glyph; extension label in Label S under the glyph on tiles of 64 dp and larger.

### 11.6 Tiles (icon containers)

| Tile | Size | Radius | Fill |
|---|---|---|---|
| List leading | 40 | `r.m` | category gradient (5.3) |
| Grid file | 64 to 96 | `r.l` | category gradient, large glyph 32 to 40 |
| Home menu | 56 | `r.l` | category / feature gradient with glow `gGlow` on press |
| Settings row | 36 | `r.s` | `primaryCont` flat (no gradient in dense lists) |
| Dialog hero | 56 | `r.full` | semantic container tone with glyph in semantic colour |

### 11.7 Badges and overlays

- Count / state badge: pill, 16 dp high, Label S, `primary` gradient fill (`error` for failures), 1.5 dp `surface` ring so it separates from the icon.
- Source badge on thumbnails (Drive, FTP, other phone): 20 dp circle bottom-right, G0 frost with glyph at 14.
- Video duration: G0 pill bottom-right with play glyph 12 dp and Label S text.

### 11.8 System-level icons

- **Launcher**: adaptive icon, foreground = Lumen "L" mark inside two stacked rounded squares (reads as file + glass), background = `gPrimary` Ember gradient, themed (monochrome) layer supplied. The **Lite** variant keeps the shape and uses the secondary gradient so both apps are siblings.
- **Notification small icon**: single-colour glyph on 24 dp grid, same mark simplified.
- **Shortcuts, share-sheet aliases, Print space alias**: same tile style as 11.6 Home menu, 48 dp.
- **Splash**: logo mark 96 dp over `gGlow` on the theme wallpaper, fade-through to Home (no hard white or hard black flash).

---

## 12. Components

Universal state recipe for every interactive component:

| State | Visual |
|---|---|
| Rest | as specified |
| Hover (pointer) | `st.hover` overlay |
| Focus (keyboard / switch access) | 2 dp `focus` ring offset 2 dp, plus `st.focus` overlay |
| Pressed | `st.press` overlay + ripple from touch point (radius reaches the farthest corner in 280 ms) + scale 0.98 for buttons and tiles |
| Selected | `primaryCont` or gradient + check / filled glyph (never colour alone) |
| Disabled | content 38 %, container 12 %, no ripple, no shadow |
| Loading | label replaced by 20 dp circular progress, width fixed (no layout jump) |
| Error | `error` edge / text + error glyph + helper text |

### 12.1 Buttons

| Type | Container | Content | Height | Radius | Use |
|---|---|---|---|---|---|
| Primary | `gPrimary`, E2 on glass screens, E1 otherwise | `onPrimary`, Label L | 48 (56 in dialogs on phones) | full | the one main action of a surface; max one per dialog |
| Tonal | `gPrimarySoft` | `onPrimaryCont` | 48 | full | secondary action |
| Glass | G2 tint + edge | `ink` | 48 | full | actions floating over pictures or wallpaper |
| Outline | transparent, 1.5 dp `edge` ring (`primary` at 50 % when focused) | `primary` | 48 | full | tertiary, "Cancel" when paired with Primary |
| Text | none | `primary` | 40 (48 target) | full (hover shape) | inline, dialog dismiss, links |
| Danger | `gDanger` | white / dark-on tone | 48 | full | permanent delete, format, replace-all |
| Icon | none (hover circle) | 24 glyph, `inkMut` | 48 target / 40 visual | full | bars |
| Icon filled / tonal | `primaryCont` circle or glass circle | glyph | 48 / 40 | full | toggles (selected = filled) |
| FAB | `gPrimary`, E3, `gGlow` halo while idle-attention only | glyph `onPrimary` | 56 | 20 | one per screen (e.g. add) |
| Extended FAB | same | glyph + Label L | 56 | full | |
| Split | Primary + 1 dp divider + chevron half | | 48 | full | "Copy" with destination options |

Rules: label is a verb or verb phrase in sentence case ("Move to bin", not "OK"); icon + label gap 8 dp; inside buttons horizontal padding 24 dp (16 for compact / icon+label); minimum width 64 dp; two buttons side by side share the row equally on phones (dialog) and each is at least 112 dp.

### 12.2 Selection controls

- **Checkbox** 24 dp visual (48 target), 2 dp edge `inkMut`, radius `r.xs`+2 (6), checked = `gPrimary` fill with white tick that draws in over 150 ms. Indeterminate = bar.
- **Radio** 24 dp circle, inner dot 10 dp scales in over 120 ms.
- **Switch** 52 x 32; track off = `surfaceLo` + 2 dp `edge`, thumb 16 dp `inkMut`; on = `gPrimary` track, thumb 24 dp white with E1 and a check glyph; thumb grows to 28 dp while pressed.
- **Selection circle on media** (gallery / lists): 24 dp circle, off = 2 dp white ring with soft shadow over pictures (only shown in selection mode), on = `gPrimary` fill + white tick; pop animation 1.0 to 1.15 to 1.0 in 180 ms; tile shrink to 88 % over 160 ms.
- **Segmented control** (e.g. Day / Month, Photos / Videos): height 40, container `surfaceLo` `r.full`, selected segment = `gPrimarySoft` pill sliding between segments in 200 ms (shared element, not a fade).
- **Slider** (print scale, brightness, zoom): track 4 dp, `primary` fill, thumb 20 dp with E2 and 40 dp halo on press; value bubble = G2 pill above the thumb while dragging.

### 12.3 Chips

Height 32, pill, Label M. Types: **filter** (off: outline; on: `gPrimarySoft` + check glyph 16), **input** (with close glyph), **suggestion** (tonal), **assist** (leading glyph). Used for search scopes ("In /Folder x"), Whole word / Contains, phone selector, Photos / Videos. Chip rows scroll horizontally, 8 dp gap, 16 dp lead-in padding, fading edge 24 dp.

### 12.4 Text fields and search

- **Field**: height 56, `r.m`, filled style: `surfaceLo` container, 1.5 dp bottom edge that becomes 2 dp `primary` on focus; label floats to Label M above value; helper / error text Body S below (8 dp gap); trailing clear glyph appears when non-empty.
- **Search bar** (universal search, in-folder filter): pill, 52 dp, G2 glass on wallpaper screens, `surfaceLo` on plain screens; leading search glyph, trailing mic / filter / clear; scope chips directly below.
- **Password / code field**: mono type, show / hide glyph.
- Keyboard: correct input type and IME action always set (search, done, next).

### 12.5 Lists and rows

- **Row anatomy**: leading tile 40 (or checkbox in selection) / text block (primary Body M `ink`, secondary Body S `inkMut`, optional tertiary Body S) / trailing meta (size, date, or glyph) / trailing action.
- **Dividers**: none inside cards (spacing only); 1 dp `edge` inset 72 dp from the left in flat lists.
- **Selected row**: `gPrimarySoft` background, `r.m`, 4 dp inset from the sides, leading tile swaps to the check circle with a 160 ms flip.
- **Section header**: Title S `inkMut`, 24 dp top, 8 dp bottom, sticky with G1 glass when it sticks.
- **Swipe actions** (if introduced): reveal tonal colours (Delete = error container, Share = primary container) with the same glyphs as 11.4.
- **Hold menu**: press 350 ms (haptic tick), row lifts (E5, scale 1.02), selection starts; the bottom action bar slides up.

### 12.6 Gallery grid, day and month headings

- Day heading (Day mode): Title M `ink`, left 16 dp, top 16 / bottom 8; **count** in Body S `inkMut` after a 8 dp gap ("23 items"); trailing **select circle** (24 dp, only in selection mode) with off / partial / on states (partial = bar glyph) matching 12.2.
- Month heading (Month mode): Title L.
- Sticky heading: when a heading reaches the top it becomes a G1 strip of 40 dp (like Google Photos date scrubbing) and swaps text as the next heading arrives.
- **Fast-scroll scrubber**: 4 dp track at the right edge, appears on scroll, expands to 48 dp touch with a G2 date bubble ("Nov 2026") showing the heading under the thumb.
- Thumbnails: radius by column rule (8.2); loading = `gSkeleton`; failed = category tile with broken-image glyph in `inkDim`.
- Album cards: cover 4:3 `r.l`, bottom `gScrimBottom` over cover only when text overlays it (title below the cover otherwise); count badge as G0 pill.

### 12.7 Top app bars

| Variant | Height | Surface | Content |
|---|---|---|---|
| Small | 64 + inset | transparent at scroll 0, G1 after 24 dp scroll | back / menu glyph, Title M, up to 3 action icons |
| Large (collapsing) | 112 to 64 | same | Headline M title collapses into Title M |
| Selection | same as active bar | `gPrimarySoft` tint over G1, slides over the bar (200 ms) | close glyph, "N selected - size" Title M, select-all glyph |
| Search | 64 | G1 | field fills the bar, back glyph |
| Viewer | overlay | `gScrimTop` + G0 icons | auto-hides after 3 s of inactivity (fade 200 ms) |

Breadcrumb / path bar: pill chips for each segment, current segment `gPrimarySoft`, scrolls to the end, long-press copies the path.

### 12.8 Bottom action bar (selection actions)

Floating G2 bar, 12 dp from edges, `r.xl`, E3, height 72. Up to 5 actions as icon (24) over Label M; more than 5 collapses the last into More (opens a G2 popup). Existing action order is fixed app-wide: **Copy, Move, (context actions: Print / Print space / Share), Details, Delete (rightmost, error tint)**. Appears with slide-up 220 ms + fade; actions stagger 20 ms each.

### 12.9 Navigation drawer and home menu

- **Drawer**: G1 panel 320 dp (or 88 % width max), `r.xxl` on the free side corners, header = storage card (L2) with `gPrimary` ring chart, items 56 dp, selected = `gPrimarySoft` pill, section dividers with Title S labels; scrim 32 %.
- **Home menu**: L0 aurora wallpaper; storage card L2 with ring + used / free (Headline L figure); sections "Browse" and "Network and tools" as grids of 56 dp tiles (11.6) with caption Label M; tile press = scale 0.96 + glow; the same icons and tile gradients as the drawer.
- **Devices bottom sheet**: rows with device tile, name, state dot (success pulse when online, `inkDim` offline), "Add by IP" row as Text button.

### 12.10 Tabs and pagers

Top tabs: 48 dp, Label L, indicator = 3 dp `gPrimary` pill under the label width, slides with spring; no full-width underline. Page transitions between tabs: fade-through 200 ms.

### 12.11 Bottom sheets (one component, five sizes)

| Size | Height | Use |
|---|---|---|
| Peek | content (max 40 %) | quick options, sort |
| Half | up to 60 % | pickers (album, folder), devices |
| Tall | up to 90 % | settings subpages, details |
| Full | 100 % (radius 0, solid `canvas`) | file picker, print layout, editors |
| Modal-center (tablet) | max 640 x 80 % | all of the above on width over 600 dp |

Anatomy: G3 surface, drag handle 32 x 4 dp `inkDim` (8 dp from top), title row (Title L + optional close glyph) 56 dp, content with 16 dp margins, sticky action row at the bottom (G3 with top edge highlight) when the sheet has actions. Dismiss: swipe down, tap scrim, back. Open 280 ms decelerate, close 200 ms accelerate. The same sheet component hosts every picker (album, folder, printer, Drive / FTP servers, view options, Size and sort); no custom per-feature card shapes.

### 12.12 Dialogs (the single dialog component)

One layout for **every** dialog in page and native code. Native `AlertDialog` windows are replaced or restyled to this spec (same 32 dp radius, same glass, same buttons).

**Anatomy (top to bottom)**: optional hero tile 56 (semantic container, glyph 28), 16 dp gap, **Title** (Title L, max 2 lines, centred if hero present, otherwise left), 8 dp gap, **Message** (Body L `inkMut`, max 6 lines then scrolls), optional content (field, list, checkbox "Do this for all"), 24 dp gap, **button row**.

**Surface**: G3 glass, `r.xxl` (32), E4, padding 24 dp (20 on width under 360 dp), width per 9.2, centred, scrim 32 / 56 %.

**Buttons**: right-aligned row on wide dialogs; **stacked full-width on phones when any label exceeds 16 characters or there are 3 or more actions** (primary at the top). Order in a row, left to right: Text (dismiss) , Outline (alternative) , Primary (confirm). The primary is always the safe default except in Danger dialogs, where the Danger button is on the right and the Cancel text button is focused by default.

**Dialog types**

| Type | Hero | Buttons | Example |
|---|---|---|---|
| Info | Info container | one Primary "Got it" | tips, results |
| Confirm | none | Text Cancel + Primary verb | Paste here, Apply to all folders |
| Destructive | Error container + trash glyph | Text Cancel + Danger | Delete permanently; secondary Tonal "Move to bin" above Danger when both exist |
| Input | none | Text Cancel + Primary Save | rename, new folder, IP address (field auto-focused, keyboard up, dialog moves above it) |
| Choice | none | list of 48 dp radio rows, Primary on selection | sort, language |
| Conflict ask (existing "Already exists - replace?") | Warning container | stacked buttons: Replace, Skip, Keep both, Replace if newer, Only if not similar + a switch row "Apply to all remaining" | copy, move, merge |
| Progress | none | Text Cancel (+ Run in background) | long jobs: linear determinate bar, file name Mono Body S, speed and ETA Label M tabular |
| Update | App tile | Primary Download, Text Later | change log renders **before** the button: sections Added (success tone bullet glyph), Removed (error tone), Changed / Fixed (primary tone) |
| Permission | Info container with permission glyph | Text Not now + Primary Open settings | storage, notifications |

**Behaviour**: scale 0.92 to 1.0 + fade in 220 ms decelerate; exit 160 ms accelerate; back key and scrim tap dismiss unless destructive in-progress; text is selectable only for error details; error details are in an expandable "Details" row (Mono, copy glyph).

### 12.13 Menus and popups

G2 surface, `r.xl`, E3, padding 8 dp, items 48 dp (dense 40), item `r.m` hover, leading glyph 24 + 12 dp gap, trailing shortcut or chevron `inkDim`, danger item last, separated by an 8 dp gap and error tint. Appear anchored with 140 ms scale from the anchor point (0.9 to 1) + fade. Cascading menus open to the side with 8 dp overlap (replaces the former Favorite folders popup pattern).

### 12.14 Feedback: snackbar / toast (one component)

Replaces **all** `Toast.makeText` calls and the page `toast()`.

- G2 pill / `r.xl`, E3, max width 560 dp, 12 dp from the bottom (above the bottom action bar or paste bar, never covering them), padding 16 x 12.
- Layout: semantic glyph 20 (only for success / warning / error; neutral has none), message Body M (max 2 lines), optional **Text action** ("Undo", "Open", "Details") in `primary`, no close button (auto-dismiss).
- Duration: 3 s short, 5 s with action, 8 s error; stays while an Undo countdown is active (the 5 s permanent-delete Restore countdown shows a thin `gPrimary` countdown bar along the bottom edge).
- Queue: one at a time, the next replaces the current with a 120 ms cross-fade; identical consecutive messages merge ("Copied 3 items" to "Copied 7 items").
- Native screens call the same component through one shared helper; no screen uses the system toast.

### 12.15 Banners and inline notices

Full-width card (L2), `r.l`, semantic container fill, glyph 24, Title S + Body S, optional Text action. Used for: low storage, device offline, index building, update available. Max one banner per screen; stacking collapses into a "N notices" row.

### 12.16 Progress and job cards

- **Linear**: 4 dp (8 dp in job cards), track `surfaceLo` `r.full`, fill `gPrimary` (`gSuccess` on completion), indeterminate = 40 % wide gradient bar sweeping 1.4 s with accelerate-decelerate easing.
- **Circular**: 24 / 40 / 56 dp, 3 / 4 / 5 dp stroke, round caps.
- **Ring chart** (storage): 12 dp stroke, `gPrimary` arc over `surfaceLo`, centre Headline L figure.
- **Job card** (copy / move / delete / print / cache): L2 card with leading tile, title Title S, file Mono Body S, linear progress, speed + ETA Label M tabular, Cancel Text button, expandable list for multiple jobs. Completion: bar turns `gSuccess`, check glyph pops, auto-collapses after 4 s.

### 12.17 Empty, error and permission states

Centred column: glyph 64 in a `r.full` tile with `gGlow` behind (semantic or neutral container), Title M, Body M `inkMut` (max 2 lines), one Primary or Tonal button. Illustration style: only glyph + glow, no clip-art. Texts are human ("Nothing here yet"), never codes; technical code goes into a "Details" row.

### 12.18 Pull to refresh

Indicator = 40 dp G2 circle with `primary` circular progress that scales in from 0.6, threshold 70 dp (as now), release spring 200 ms; the label "Pull to refresh" is dropped (glyph only) to remove wording differences between languages.

### 12.19 Scrollbars and scrubbers

Thin 3 dp `inkDim` at 40 % while scrolling, fade out 600 ms after stop; touch-draggable scrubber on long lists and galleries (12.6).

### 12.20 Skeleton loading

Placeholders replace spinners for lists and grids: shapes match the final layout (rows 56, thumbs squares), `gSkeleton` shimmer sweep 1.2 s linear, 30 degree angle; shimmer stops with Reduce motion (static `surfaceLo`). A centred spinner is allowed only for blocking, unknown-length operations of under 2 s.

### 12.21 Tooltips and hints

G2 pill, Label M, appears after 500 ms hover / long-press on bar icons, 8 dp from the anchor, auto-hides after 2 s. Coach marks (first run) use the Info dialog style anchored to the element with a `gGlow` ring.

### 12.22 Viewers (image, video, PDF, Office, text, Storage Analyzer)

- Stage is L7 black (image / video / PDF) or `surface` paper (Word / text reading mode with theme-matched paper tint).
- Chrome overlays use `gScrimTop` / `gScrimBottom` + G0 icon buttons; tap toggles, 200 ms fade; double-tap zoom 220 ms decelerate.
- Page indicators (PDF): G0 pill "12 / 340" top-centre, tabular numerals.
- Info panel (image details): G3 half sheet.
- Video controls: G2 floating bar at the bottom with seek slider (12.2), time labels Label M tabular, play button 56 dp `gPrimary` circle.
- Storage Analyzer: treemap cells use category tones (4.5), 2 dp gap, `r.s` cell radius, label inside when cell is larger than 56 x 28 dp; selection raises the cell (E3) and shows details in a peek sheet.

---

## 13. Motion and haptics

### 13.1 Easing (named curves, used everywhere)

| Name | Feel | Use |
|---|---|---|
| `ease.standard` | cubic-bezier (0.2, 0, 0, 1) | most movement within the screen |
| `ease.decel` | (0, 0, 0, 1) | things entering (sheets, dialogs, menus) |
| `ease.accel` | (0.3, 0, 1, 1) | things leaving |
| `ease.emph` | (0.05, 0.7, 0.1, 1) | hero moments (container transform, FAB morph) |
| `ease.spring` | damping 0.8, stiffness 380 | tab indicator, switch thumb, pull release, selection pop |
| `ease.linear` | linear | progress, shimmer, wallpaper drift |

### 13.2 Durations

| Token | ms | Use |
|---|---|---|
| `t.instant` | 90 | ripple start, tick draw, press scale |
| `t.fast` | 140 | menus, tooltips, chip toggle, hover |
| `t.base` | 200 | fades, selection bar swap, tab change |
| `t.med` | 280 | sheets in, drawer, container transform |
| `t.slow` | 400 | page transitions between screens, theme cross-fade (250) |
| `t.hero` | 520 | splash to home, first-run coach |

Exit is about 70 % of the enter time. Nothing exceeds 520 ms except looping progress.

### 13.3 Patterns

| Pattern | Spec |
|---|---|
| Screen push / pop | new screen slides 24 dp from the right + fade (`t.slow`, `ease.emph`); old screen shifts 8 dp left and dims 8 % |
| Open album / viewer from thumbnail | **container transform**: thumbnail rect grows to the full surface (`t.med`, `ease.emph`), image stays continuous, chrome fades in after 120 ms |
| Dialog | scale 0.92 to 1 + fade, scrim fades in parallel (`t.base`) |
| Sheet | slides from the bottom (`t.med`, decel), scrim fades; drag follows finger 1:1 and settles with `ease.spring` |
| Menu | scales from anchor 0.9 to 1 (`t.fast`) |
| Snackbar | slides up 16 dp + fade (`t.base`), exits fade only |
| Selection start | tile shrink to 88 %, circle pop, bar slide (`t.base`, staggered 20 ms per element, max 5) |
| List insert / remove | height collapse + fade (`t.base`), neighbours move with `ease.standard` |
| Theme change | whole app cross-fades colours over 250 ms; nothing jumps |
| Glass appearing | blur radius animates 0 to full while tint alpha rises (`t.med`), so the effect "frosts in" |
| Wallpaper drift | home only, `ease.linear`, 60 s loop, off with Reduce motion / battery saver |
| Skeleton | 1.2 s shimmer loop |
| Success | check draws in 240 ms, container pulses once (scale 1.0 to 1.06 to 1.0) |
| Error | 6 dp horizontal shake, 3 cycles, 240 ms (one time, never looping) |

### 13.4 Reduce motion

Setting or system flag: transforms and parallax replaced by 120 ms fades, no drift, no shimmer movement, no shake (colour flash instead), container transform becomes a cross-fade.

### 13.5 Haptics (map onto the existing `Haptics.kt` events)

| Event | Haptic |
|---|---|
| Hold starts selection | tick (medium) |
| Select / unselect | light tick |
| Switch toggle, chip toggle | light tick |
| Drag crosses snap point, slider end stops | tick |
| Success (job done) | double soft tick |
| Error / destructive confirm | heavy click |
| Pull-to-refresh threshold | tick |
Haptics always respect the system setting and an app toggle; none for scrolling.

### 13.6 Sound

None by default. Optional system sounds only for send / receive complete (single soft chime), off by default.

---

## 14. Theme catalogue

### 14.1 Structure

A theme = **name + mode + accent hue + neutral tint + optional wallpaper recipe + optional OS flavour**. All themes fill the same slots (4.3), pass the same contrast table, and use the same shape, type and motion. Today's ~40 hand-written blocks become presets.

### 14.2 Presets (grouped, one picker with live preview)

| Group | Presets | Notes |
|---|---|---|
| **Lumen** (default family) | Ember (default), Ocean, Forest, Lavender, Sakura, Mint, Sky, Sand | each in Light and Dark automatically (the user picks Auto / Light / Dark separately from the accent) |
| **Contrast** | Graphite (mono accent), AMOLED (pure black, accent violet), High contrast | AMOLED disables wallpaper and uses darker tint; High contrast forces F3 flat, 3 dp focus rings, 7:1 text |
| **Classic** | Nord, Dracula, Solarized, Cyber, Crimson, Sunset | keep their well-known accents; glass and gradient derived by the same rules |
| **OS flavours** | Windows 11 (light, dark), Ubuntu (light, dark) | allowed to change **wallpaper** (Fluent bloom / Yaru aubergine), **accent**, and the **font** (Segoe-like fallback / Ubuntu); radii stay on the scale: Windows flavour maps `r.xxl` to 24 and `r.l` to 12 (Fluent feel); Ubuntu flavour keeps scale. They never alter component structure |
| **Custom** | one hue slider (0 to 360) + one "tint strength" slider + wallpaper on / off | tonal ramps generated live, rejected values snap to the nearest passing tone with a notice |

Suggested trimming: merge Solar / Sand and Mint / Sky variants that differ by under 12 degrees of hue; keep the rest. Fewer presets, higher quality.

### 14.3 Per-theme requirements checklist

1. All 18 slots defined (4.3), plus semantic and category sets generated.
2. `gPrimary`, `gPrimarySoft`, `gDanger`, `gSuccess` generated with the drift rule (5.1).
3. Wallpaper recipe (blob colours) or "off".
4. Contrast table passes at F0 and F3.
5. Preview card renders correctly: top bar, list, selected row, chip, switch, primary button, dialog, snackbar.
6. Status bar and navigation bar icon colours defined (dark icons on light chrome, light icons on dark).

---

## 15. Native and page parity

The page (WebView `ui.html`) and the native screens must read **one token set**.

### 15.1 Single source

- Tokens live in one structured file (design tokens, generated from the three theme inputs). A build step exports two formats: page variables and native colour / dimension resources. Hand-editing either output is forbidden.
- The page already pushes its palette to native (`nlPalette`, `AppPal`). Extend that hand-off from 7 colours to **all slots**, gradients (as stop lists + angle), glass tier parameters, radius scale, spacing scale, type scale, motion tokens, and the active fallback step (F0 to F3). Native persists the last palette (as today) so cold-start native screens (viewer opened from outside) use the right theme before the page loads.
- Native screens that exist outside the page: Image viewer / editor, Video player, PDF viewer, Office viewer, Storage Analyzer, Universal Search screen, Inbox, Print log and live, Clip send, quick-send notifications, system dialogs. Each must (a) take all colours from the palette, (b) use the shared dialog, sheet, snackbar and button components, (c) use Lumen Icons, (d) apply glass per 6.3.

### 15.2 Shared components list (build once, used from both sides)

Dialog (12.12), Bottom sheet (12.11), Snackbar (12.14), Buttons (12.1), Chips (12.3), Progress / job card (12.16), Top and bottom bars (12.7, 12.8), Selection circle (12.2), Skeleton (12.20), Empty state (12.17). A screen that needs something else asks for a new shared component instead of drawing its own.

### 15.3 System integration

| Surface | Spec |
|---|---|
| Status / navigation bars | transparent, edge-to-edge, icon tint follows chrome luminance (not system mode) |
| Window background (before first frame) | theme `canvas` (no white flash in dark themes) |
| Splash | section 11.8 |
| Notifications | accent colour = `primary`, small icon (11.8), large icon = category tile; progress notifications use determinate bar + Cancel action; heads-up text Body M |
| Widgets / shortcuts | tile style 11.6, glass not used (system draws backgrounds) |
| Share sheet entries | app tile + name; "Print space" alias uses the Print tile |
| Android 12+ dynamic colour | optional preset "System colours" (accent from wallpaper) that feeds the same three inputs; off by default so the brand stays consistent |
| Recents thumbnail | uses `canvas` background; secure screens (none) n/a |

---

## 16. Screen-by-screen application

| Screen | Wallpaper | Chrome | Content | Key decisions |
|---|---|---|---|---|
| **Home menu** | Aurora (animated) | transparent top bar, no bottom bar | storage card (L2), tile grids (11.6) | glow on press, devices sheet = sheet component |
| **File browser** | flat variant 6 % | large collapsing top bar (G1 after scroll), breadcrumb chips, selection bar, floating action bar, paste bar (G2) | rows 56 / 72 dp with category tiles, folder tiles with two-plate drawing | folder Style colours map to ramps; pull to refresh 12.18 |
| **Gallery - album home** | flat 6 % | small top bar, chips (This phone / Other phones / PC) | album cards 2-4 columns | covers `r.l`, count pills G0 |
| **Gallery - album grid (Day / Month view)** | none (canvas) | top bar with Size and sort, selection bar, bottom action bar | thumbnails, sticky day heading, scrubber | heading rules 12.6; Group by Day / Month is a segmented control inside the Size and sort sheet |
| **Image viewer / editor** | L7 black | G0 overlay chrome, editor tools in G2 floating bar | stage | container transform from thumbnail |
| **Video player** | L7 black | G2 control bar, `gScrimTop/Bottom` | stage | big play button 56 `gPrimary` |
| **PDF viewer** | L7 dark paper stage | G0 page pill, top bar overlay | pages on `surface` paper with E2 | no glass behind pages |
| **Office / text viewer** | `canvas` | solid top bar (reading), tools in sheet | paper surface tinted warm in Ember | reading mode uses Body L with 1.6 line height |
| **Search (universal)** | flat | search bar G2, scope chips | sections with Title S headers, result rows 72 dp with highlighted match (`primaryCont` background behind matched text) | history rows 48 dp |
| **Storage Analyzer** | flat | small bar | ring + treemap / pie in category tones | cells `r.s` |
| **Print space / print log / live** | flat | top bar + sticky bottom Primary button | paper preview on `surface` E2 | job cards 12.16 |
| **Devices / Drive / FTP / SMB** | sheet | sheet component | rows with source glyph tiles | sign-in errors use Info / Error dialogs with Details row |
| **Settings** | flat | large top bar | groups: L2 cards with Title S headers, rows 56 dp with 36 dp tiles | switches 12.2, descriptions Body S |
| **Dialogs and sheets (everywhere)** | scrim | G3 | per 12.11 / 12.12 | one component |
| **Update dialog** | scrim | G3 | change log first, button after | colour-coded sections |
| **Recycle bin** | flat | small bar with Empty-bin Text action | file browser rows, "Deleted" meta in `inkMut`, restore / delete forever in action bar | read-only inside deleted folders: disabled actions show the reason in a tooltip |
| **Remote shell / terminal** | `surfaceLo` or black | small bar | Mono type, theme-tinted ANSI palette | ANSI colours derived from the category palette, all passing 4.5:1 |

---

## 17. Accessibility

- **Contrast**: 4.7 rules; automated table per theme; High contrast preset at 7:1.
- **Targets**: 48 dp minimum, 8 dp separation; the day-heading select circle has a full-heading-height tap target.
- **Font scaling**: tested at 100, 130, 160, 200 %; bars grow, rows grow (no clipping), dialogs scroll, icon-only bars stay icon-only (no truncation of labels because labels move to tooltips under 12 sp effective).
- **Reduce motion / Reduce transparency / Bold text** honoured (system + in-app).
- **Colour independence**: every state has glyph + text; charts add patterns or labels, not only hue.
- **Screen reader**: every icon button has a content label (verb phrase); selection announces "3 selected"; headings are marked (day headings are real headings so users jump between days); dialogs trap focus and announce their title; progress announces at 10 % steps and on completion.
- **Focus**: visible 2 dp ring, logical order, Escape / back closes top layer only.
- **Touch alternatives**: every long-press action also exists in a menu; drag gestures have button equivalents.
- **Language**: all strings localisable (Turkish and English first); layouts tolerate 40 % longer text; no text inside images; plural forms handled per language.
- **Time / dates**: locale format, 24 h or 12 h by system; relative labels (Today, Yesterday) localised.
- **Photosensitivity**: no flashing above 3 Hz; shimmer and glow low-contrast.

---

## 18. Performance budget and fallbacks

| Topic | Rule |
|---|---|
| Blur surfaces | at most 2 visible; the fallback ladder (6.5) is automatic; thresholds measured on the three reference devices (low, mid, high) |
| Blur over scrolling content | bars may switch to solid during fling (6.4) |
| Backdrop size | blur sampled at 1/4 resolution; radius counted in dp, not px |
| Gradients | pre-rendered once per theme into tiny bitmaps / cached variables; never animated except the progress sweep |
| Shadows | at most 3 shadowed elements visible per screen except cards in lists (E1 only, cached) |
| Wallpaper | single pre-rendered bitmap (screen size, 2.5 % grain baked in); drift animates a translate of that bitmap, not live gradients; drift off in battery saver |
| Lists / grids | no per-row blur or gradient animation; thumbnails decode at display size; skeleton instead of spinners |
| Animation | transform and opacity only; no layout animation inside large lists; frame budget 16 ms at 60 Hz, 8 ms at 120 Hz |
| Memory | glass backdrop buffers released when the surface hides |
| Cold start | window background = `canvas` solid, theme applied before first frame, splash fade-through under 520 ms |
| Page vs native | the page decides F-step from `window` capabilities + a device-class hint from native (RAM, SoC class, battery saver); native sends it with the palette |
| Measure | every release logs (debug) glass step in use, dropped frames in a 5-second scroll test of the gallery and file browser |

---

## 19. Migration plan, acceptance tests, do / don't

### 19.1 Parts (18, one per session; re-split from 10 on 2026-11-07)

| Part | Scope | Visible result |
|---|---|---|
| 1 | Token source `tools/lumen/tokens.json` + generator `gen.py`; generated block in `ui.html` with slot names, radius / spacing / type / state scales; slots alias today's variables | Nothing (same look) (DONE 2026-11-07) |
| 2 | Radius scale applied: all page `border-radius` values moved to `--r-*` | Consistent corners (DONE 2026-11-07) |
| 3 | Spacing and type scale applied: padding / gap / margin and font-size moved to `--sp-*` / `--t-*` | Consistent spacing and text (DONE 2026-11-07) |
| 4 | Flip: existing 15 themes defined in slots (old names become aliases), Ember adopts spec 4.2 values; contrast table T1 script | Warmer, consistent Ember (DONE 2026-11-07) |
| 5 | Palette hand-off to native expanded to all slots (`LumenTokens.kt` generated) | Native has every slot (DONE 2026-11-07) |
| 6 | Native screens recolour from tokens | Native and page match (DONE 2026-11-07) |
| 7 | Page dialog, sheet (5 sizes) and snackbar components | One dialog, one message style (page) (DONE 2026-11-07) |
| 8 | Native dialog / sheet / snackbar helper; replace all `AlertDialog` and `Toast.makeText` | One dialog, one message style (native)(DONE 2026-11-07, sheets `NlSheets` still own look) |
| 9 | Lumen Icons A: inventory 11.4 redrawn for page and native lists | One icon family (DONE 2026-11-07, 46 glyphs; folder / file glyphs wait for Part 10) |
| 10 | Lumen Icons B: folder and category tiles, launcher / notification / splash | Icons complete (DONE 2026-11-07; two-plate folder and gradient main-menu tiles added later the same day; Word / Excel realistic tiles and splash logo screen left) |
| 11 | Buttons, chips, switches, text fields to spec | Controls consistent (DONE 2026-11-07; floating label, clear glyph, search pill, sliding segment left) |
| 12 | Lists, bars, selection visuals, ripple and press states | Lists and bars consistent (PARTLY DONE 2026-11-07: rows, selection, dividers, ripple; bars and drawer left) |
| 13 | Gradients (gPrimary etc.), category tile gradients, shadows | Depth and colour transitions (DONE 2026-11-07 on the page + native dialog button; native tiles / chrome / gSurface / gSuccess left) |
| 14 | Wallpaper Aurora + glass tiers G0-G3 | Glass chrome and sheets (PARTLY DONE 2026-11-07, page only: Aurora on the home menu, G2 / G3 on dialogs, sheets, menus, toast; native chrome / dialogs, G0, grain, drift left) |
| 15 | Glass fallback ladder + settings (Reduce transparency, Glass quality) | Safe on low devices (DONE 2026-11-07 on the page; native has no glass yet) |
| 16 | Motion tokens, container transform, haptic mapping, skeletons | Fluid feel (DONE 2026-11-07 partly: tokens, enter animations, reduce motion, haptic map, skeletons; exits, true container transform, drift left) |
| 17 | Theme presets regenerated from 3 inputs, Custom theme, OS flavours | Final catalogue (PARTLY DONE 2026-11-07, page only: per-preset derived slots, grouped picker, Custom theme, Windows radii, T1 clean; presets keep their hex, Light / Dark pairing, OS fonts, High contrast rings left) |
| 18 | Screen-by-screen pass (section 16), accessibility (17), performance (18) | Release candidate (PARTLY DONE 2026-11-07, page only: focus rings, roles / names / live regions, dialog focus trap + Escape, fling-to-solid, frame test, lint T2 / T3; native a11y, TalkBack, T5 / T7 / T8 / T10 left) |

Each part: update the handover chapter, `CHANGELOG.md`, and send back only changed files in `.tar.xz`.

### 19.1a Status and decisions as built (updated 2026-11-08)

**The current status is the status line in `LUMEN_WORK_PLAN.md` (it also has Parts 4a-4f and the semantic slots of 4g); the audit of what differs from this spec is `LUMEN_AUDIT.md`.** Older text of this section (Parts 1-11 / 17 done, Round 2 A) is history. Numbering: spec Parts 1-18, Round 2 A-C and plan Parts 1-4g are three systems; the plan file maps them. Next: owner compile, device verification (T5 / T7 / T8 / T10, TalkBack).

Tooling: `tools/lumen/tokens.json` is the single source (slots per theme, fresh slots, radius, space, type, font sizes, state opacities). `python3 tools/lumen/gen.py` writes the `/*LUMEN:BEGIN*/ ... /*LUMEN:END*/` block at the top of the `ui.html` `<style>` (`--check` verifies). `python3 tools/lumen/contrast.py` is acceptance test T1.

| Part | What was decided / built |
|---|---|
| 1 | Token names are CSS vars: slots `--canvas --surface --surfaceHi --surfaceLo --edge --edgeHi --ink --inkMut --inkDim --primary --onPrimary --primaryCont --onPrimaryCont --secondary --tertiary --scrim --glassTint --focus`, semantic `--error --errorCont --onErrorCont --success --warning`; scales `--r-*`, `--sp-*`, `--t-<style>-size/lh/w/tr`, `--st-*`. |
| 2 | All page `border-radius` use `--r-*`. Off-scale values fold to the nearest token (2-5 to xs, 6-10 to s, 12-13 to m, 14-19 to l, 20-26 to xl, 28 to xxl, 50% to full). `0` stays. |
| 3 | `padding / margin / gap` of 3px and up use `--sp-*` (halves round down: 6 to 4, 10 to 8, 14 to 12, 18 to 16). 1-2px nudges, `calc / env / var` spacing and `font:` shorthands stay raw. Every `font-size` uses `--fs-11/12/14/16/20/24/28/36` (13 to 12, 15 to 14, 18 to 16, 22 to 20). The Section 7 style tokens `--t-*` exist but are not applied yet (a size alone does not say which style a text is); they are applied per component in Parts 11-12. |
| 4 | All 42 themes live in `tokens.json` as slots; old names (`--bg --card --cont --bd --fg --mut --ac --onac --sel --onsel --err --errc --onerr --ok --warn`) are aliases. Ember light and dark use the 4.2 / 4.3 values. Other themes keep their exact old hex until Part 17. Semantic slots keep today's values (spec 4.4 adopted when components need `onWarning` etc.). Dark themes are detected by canvas luminance and get the dark values of the 8 fresh slots (placeholders). |
| 5 | The page sends every slot to native in the palette JSON as `lm` (ARGB, alpha kept). `gen.py` also writes `LumenTokens.kt` (`class Lumen`, one `Int` per slot, `Lumen.of(ctx)`, Ember light / dark defaults). Old palette keys and `AppPal` stay until Part 6 moves the screens over. |
| 6 | Native screens use `Lumen.of(ctx)` for fallback palette, Storage Analyzer, job overlay, update dialog, print log (semantic success / error). The page palette already reached native via `AppPal` (old keys alias the slots). Not migrated yet: category colours, chrome action-button colours, code highlight, dark viewer chrome, editor highlight palette; they move with Parts 9-10 (icons) and 13 (gradients). Print-log warning keeps its darker amber (slot `warning` is too light as text; revisit in Part 17 with `onWarning`). |
| 7 | The existing page `dlg()` and `toast()` became the Lumen dialog / snackbar (call sites untouched): hero tile, stacked phone buttons, filled primary, tonal danger, 32 px corners (fixes the undefined `--dr` square corners), toast `kind` + `action`, pill look. Sheets: drag handle, scrim slot, `sz-peek/half/tall`, centred modal from 600 px. Hand-written CSS block `/*LUMEN-UI:BEGIN*/` at the end of the page style. |
| 8 | New `Lm.kt`. Snackbar: `Lm.snack(ctx, msg, Toast.LENGTH_x)` (or the 6-arg form with kind `ok / warn / err` and a Text action) draws the 12.14 pill on the activity content view (surfaceHi, edge hairline, 24 dp radius, glyph only for ok / warn / err, 3 s, 5 s with action or LONG, 8 s error, identical message restarts the timer, system toast only when no Activity). All 89 `Toast.makeText` calls converted. Full-screen `Dialog`s register with `Lm.track(d)` so the pill shows above them. Dialog: `AppPal.dlg` calls `Lm.restyle` on show: filled primary / tonal danger (detected from the OK label or a Delete / Clear / Remove / Discard title) buttons, stacked with primary on top on phones for 3+ actions or a label over 16 characters, trash hero tile and Cancel focus for danger, scrim 32 / 56 %, scale-in 220 ms (skipped with animations off), window radius 32 dp (`dlg_bg*.xml` too). Player, image viewer and image editor dialogs moved onto `AppPal.dlg`. `NlToastView` (page toast drawn natively) reads Lumen slots and the pill radius. Open: `NlSheets` look, snackbar lift above bottom bars, hero tiles for other dialog types, Undo countdown bar and number merging (Part 16). |

| 9 | `tools/lumen/icons.json` is the icon source (stroke 1.75, round, 24 grid). Page `ic()` prefers it (class `li`), native chrome gets a `~` prefix, `NlGlyph` / `NlDrawer` draw round strokes. Filled originals stay for keys without a Lumen drawing and for folder-style / file / category glyphs (Part 10). |

| 10 | 85 glyphs in `icons.json`; `NlIcons.draw` is the one native icon drawer; launcher = gradient + stacked-squares-with-L mark, monochrome layer, one-colour notification mark; window background = Ember canvas. |

| 11 | Hand-written block `/*LUMEN-UI2*/` in `ui.html`: `.tbtn` pill (text 40, `.fill` / `.tonal` / `.out` / `.dngf` 48, `.busy`, disabled, focus ring), `#fab` pill, `.chip` 32 pill (`.on` / `.sug` / opt-in `.flt` `.inp`, `.chips` row), `.sw` with thumb check and 28 px press thumb, custom `.cbx`, 24 px radio, `.seg`, `input.lmr` slider, filled `.tf` + `.fh` helper. Native: `Lm.styleButton` sizes. Left: floating label, clear glyph, search pill, sliding segment, value bubble, split button. |

| 12 | Block `/*LUMEN-UI3*/` + ripple script at the end of `ui.html`: 1 px inset row divider, tinted selected row with `r-m` and 4 px inset, Title S date heading, ripple (280 ms, 14 %) on `.tbtn .chip .opt .vrow .seg button`. Native: `NlRowView` highlight inset 4 dp / radius 12 dp, 1 dp inset divider in flat lists. Left: floating bottom bar, top-bar variants, drawer, check-circle flip, scrubber, swipe actions, hold-menu lift, ripple in native views. |

| 13 | Block `/*LUMEN-UI4*/` (inside `@supports(color-mix)`): gradients derived from each theme's slots (`--gPrimary --gPrimarySoft --gDanger --gFab`), applied to filled / tonal buttons, FAB, switch, checkbox, selection circles, progress fill, page-list tiles; elevation `--e1..--e5` tinted by primary, dark themes get 2.8x alpha and edge-only E1 (selector list generated with `is_dark`). Native: `Lm.styleButton` primary gradient. Left: native tiles / chrome, gSurface, gChrome, gSuccess, gGlow, gSkeleton, scrims, real `shadowTint` slot, contrast check of gradient ends. |

| 14 | Block `/*LUMEN-UI5*/` (inside `@supports(backdrop-filter + color-mix)`): tier alphas as vars (light 78-66 / 78 / 84 %, dark 70-58 / 70 / 76 %), `.g1 .g2 .g3` utilities, G3 on `#dlg .dcard` and floating sheets, G2 on `.dmn #toast #jp`; Aurora = four radial blobs x `--aK` on `#home` (amoled 0) + 2.5 % SVG grain, static. Left: native chrome / dialogs / sheets, G0 pills, G2-G3 grain, drift (Part 16), scroll-driven top bar, max-two-blurs rule, per-theme contrast floor. |

| 15 | `html[data-glass]` f0-f3 chosen by `glassPick()` (Reduce transparency or high contrast or no `color-mix` = f3, no blur = f2, Glass quality Light = f1 / Full = f0, Auto = f1 on battery saver or low-end device). UI5 blur / saturation are vars; block `/*LUMEN-UI6*/`: f1 blur 12 / 16 / 20, no saturation, no grain; f2 tint 94 % (92 % dark) no blur; f3 opaque `surface` + 1 px `edge`, no Aurora; `html.dlo` drops sheet / menu blur under an open dialog. Native hint `LumenPrefs.glassHint()`. Settings -> Appearance: Glass quality, Reduce transparency. |

| 16 | Motion tokens `--d-*` / `--ease-*` (generated) + Kotlin `LumenMotion`; block `/*LUMEN-UI7*/`: enter animations of dialog, sheet, full-screen sheet, menu, snackbar, glass frost-in, theme cross-fade, success pulse, error shake, skeleton shimmer; Reduce motion (`html[data-rm]`, switch + system + native); haptic map `HAP` and Haptic feedback switch; native `Lm.kt` dialog / snackbar on tokens; viewer launch grows from the tapped thumbnail (platform scale-up stand-in for the container transform). Left: exits, shared-element transform, drift, selection choreography, list insert / remove, Undo bar, swipe-to-dismiss. |

| 17 | Colour maths in `tools/lumen/derive.py` (HSL + WCAG, snap to the nearest passing tone); `fresh()` derives the 8 unlisted slots per preset, `from_inputs(hue, tint, mode)` is the 4.1 generator used by the Custom theme (`custom.js`, parity-tested against Python). Picker grouped by `tokens.json` `groups`; OS flavours: win11 radii 12 / 24, `freshOverride` for the four Windows / Ubuntu themes (wallpaper blobs). Custom = hue 0-360, tint 0-8, Light / Dark / AMOLED, wallpaper on / off, notice when snapped. Presets keep their hand-picked hex (decision: regenerating 42 well-known palettes would change their identity without a device to judge). |

Known gaps carried forward:
- T1 is clean since Part 17 (the 4 old fails `ubuntu`, `peach`, `garuda`, `solar` were fixed by moving one colour each).
- Native screens still hardcode the old Ember colours, so page and native differ slightly until Parts 5-6.
- Fresh slots (`secondary`, `tertiary`, `glassTint`, `focus`, `scrim`, `inkDim`, `edgeHi`, `surfaceLo`) are derived per preset since Part 17 (`derive.fresh`); `ember` and `dark` keep the spec values.
- Part 8 built `Lm.kt` (snackbar + dialog restyle through `AppPal.dlg`) and converted all `Toast.makeText`; `NlSheets` bottom sheets, snackbar bottom lift above bars, and hero tiles for non-destructive dialog types are still open.
- Sheet swipe-to-dismiss, identical-message merging and the Undo countdown bar wait for Part 16 (motion); glass surfaces for Parts 14-15.

### 19.1b Round 2: eleven follow-up items, in three parts (added 2026-11-07)

After Parts 1-18 the owner asked for these eleven items. Items 1-3, 9, 10 and 11 are named in the plan chat; **items 4-8 are only partly recorded** (the plan text for them was not in the session that built Round 2 Part A) and are written here from the leftovers of Parts 12-16, so check them against the owner's list before starting Part B / C. Whole round is roughly 2,800-4,700 lines; items 1-3 are about 370-580 lines; items 4-6 are the biggest and riskiest because they change native drawing code that has never been compiled or tested on a device.

| # | Item | Part |
|---|---|---|
| 1 | Named brand colours in `tokens.json` and generated code (`--cat-<key>`, `-t`, `-d`; `LumenCategory`) | **A (DONE 2026-11-07)** |
| 2 | Main menu tiles on the 11-category colour table (4.5) | **A (DONE 2026-11-07)** |
| 3 | File list icons as category gradient tiles (`NlFileIcons`) | **A (DONE 2026-11-07, list rows + Universal Search; grid fallback page still uses the realistic icon)** |
| 4 | Floating 72 dp bottom action bar (12.8), top bar variants (12.7), drawer rows (12.9): native `NlChrome` / `NlDrawer` | **B (DONE 2026-11-07 except scroll-driven / large top bars, see Part B notes)** |
| 5 | Native chrome and tiles on gradients / glass (gSurface, gChrome, G0-G3 in native), from Part 13-14 leftovers (to confirm) | **B (PARTLY: gChrome top light on top bar and dock, gPrimary on the primary dock pill, glass stand-in on the dock; tiles, sheets, dialogs left)** |
| 6 | Native drawing leftovers of Parts 12-13 (check-circle flip, native ripple, press states) (to confirm) | **B (PARTLY: native ripple in NlChrome views; check-circle flip, ripple in list / drawer rows left)** |
| 7 | Page leftovers of Parts 12-16: scrubber, swipe actions, hold-menu lift, exits, list insert / remove (to confirm) | C |
| 8 | Page leftovers of Parts 14-17: Light / Dark pairing, OS fonts, High contrast rings, per-theme contrast floor (to confirm) | C |
| 9 | TalkBack labels in native screens, 48 px chip tap area | **C (PARTLY 2026-11-07: native chrome + chips)** |
| 10 | Splash screen | **C (DONE 2026-11-07)** |
| 11 | Remaining form controls (floating label, clear button, search pill, sliding segment, slider bubble) | **C (DONE 2026-11-07, dialog fields only)** |

**Part C as built, partial (2026-11-07).** Done: item 9 (native TalkBack: `NlHitView` exposes each labelled hit rect as a virtual button through `ExploreByTouchHelper`; labels in `labelOf` of `NlBarView`, `NlFabView`, `NlDockView`, `NlToastView`, `NlDlView`; chips get a 48 px hit row by a click redirect on `.chips` padding, no ripple there), item 10 (`values-v31/themes.xml`: splash background = canvas, icon = launcher icon, so Lite stays purple), item 11 (block `/*LUMEN-UI12*/`: search pill 52, `dlg` field floating label + clear, `.seg.sl` sliding pill, `input.lmr` value bubble, optional `data-unit`). **Not done:** item 7 (scrubber, swipe actions, hold-menu lift, exit animations, list insert / remove), item 8 (Light / Dark pairing, OS fonts, per-theme glass contrast floor), `contentDescription` of native list rows / drawer rows / Lm dialogs, floating label for non-dialog fields (`.tf` in sheets keep placeholders), TalkBack run on a device. Kotlin NOT compiled, nothing seen on a device; JS `node --check` ok, CSS braces balanced, `gen.py --check` ok.

**Part B as built (2026-11-07).**
- Page, block `/*LUMEN-UI11*/` (after UI8; the file already had two `UI9` / `UI10` blocks from Parts 18 / owner feedback): `#dock` floats (12 px from the sides, `12px + safe inset` from the bottom, `r-xl`, glass G2 with the same F0-F3 tiers as `.g2`), icon 24 over Label M 12 px, 72 px row, no dividers, `.lm-in` slide-up 220 ms (Reduce motion: 120 ms fade). `--dockh` is now `offsetHeight + 12`. `renderBar()`: more than 5 actions keep the first 3 (4 without a Delete) + **More** (`lmDockMore()`, a `.dsc`/`.dmn` popup above the bar) + Delete (rightmost, error tint). New Lumen glyph `more`. Drawer: panel `min(88vw,320px)` with `r-xxl` on the right corners, 56 px pill rows with 8 px side margin, Title S section labels, hairline separators (native mirrors these computed values).
- Native `NlChrome.kt`: `NlHitView.ripple()` (280 ms decelerate from the touch point, 14 % ink, 160 ms fade, clipped to the target, Reduce motion = instant) used by the top bar buttons and every dock cell / Paste / close; `NlBarView` gChrome light gradient + selection bar slides over the bar (200 ms); `NlDockView` rewritten as the floating card (page sends `bg` + `rx`; fill = page glass tint raised to >= 92 % because a native view cannot blur what is behind it; edge hairline; elevation shadow tinted by `primary` on API 28+; primary pill = gPrimary gradient; delete uses `Lumen.error`; `enter()` slide-up). `.dsc` popups hide the native chrome like a dialog.
- Native `NlDrawer.kt`: `DrPanel.rx` (page `rx`) rounds the right corners via outline + `clipToOutline`, elevation 16 dp.
- **Not done in B:** Large (collapsing) top bar and scroll-driven transparent small bar (needs a page twin that is transparent at scroll 0 and a scroll event to native), Search / Viewer bar variants, drawer storage-card header with ring chart, ripple inside drawer / list rows, check-circle flip, G0-G3 in native dialogs / sheets, 20 ms action stagger. Kotlin NOT compiled, nothing seen on a device (page dock checked in Chromium: 72 px high, 12 px margins, 24 px radius, 5 cells, More popup opens).

**Part A as built.**
- Source: `tokens.json` `category` (11 keys `pic vid aud doc xls ppt pdf zip code apk other`, light and dark base from 4.5, `mono` theme list, tile gradient ends 10 % lighter / 15 % darker). `gen.py` derives per key a **tint** (light: base 85 % to white; dark: base 78 % to black) and a **deep** tone (light: base 45 % to black; dark: base 80 % to black = glyph on a light dark-mode tile).
- Page: `--cat-<key>`, `--cat-<key>-t`, `--cat-<key>-d` in the `LUMEN` block. Light values on `:root`; dark values on every dark theme, `auto` in dark mode and `:root[data-cdark]` (Custom theme in Dark / AMOLED). Graphite and True black are in `mono`: their categories are steps of the theme's own ink towards its canvas (colourless, still distinguishable).
- Main menu: `HC` map of tile to category: Gallery pic, Videos vid, Downloads zip, SMB doc, FTP xls, Cloud aud, Remote shell code, Favorites ppt, Printers pdf, Storage / Devices other. The old per-theme `--hc1..7` are no longer read (rules left in place, safe to delete later). Glyph colour still chosen by `hmInk` (contrast).
- Native: palette JSON key `cat` (`nlPal()`: `[base, tint, deep]` per key) is read by generated `LumenCategory.of(ctx)` (cached per palette string; Ember light / dark defaults when none). `NlFileIcons.draw(cv, name, kind, x, y, size, cat, d)` draws a rounded tile (radius 30 %, gradient base top-left to bottom-right, 1 px rim), the category glyph from `LumenIcons` (white on light tiles, deep tone on dark tiles) and, for tiles of 64 dp and up (`d` > 0), the extension under a smaller glyph. `NlFileIcons.catOf(name, kind)` is the one extension to category table (text and code = `code`, Word / e-books = `doc`, sheets / csv / databases = `xls`, executables = `apk`, disc images = `zip`, playlists = `aud`, the rest `other`). The realistic Word / Excel / PowerPoint icons stay as `drawLegacy` (used inside the white badge of the grid "no thumbnail" page).
- New Lumen glyphs `sheet` and `slides` in `icons.json` (the file category glyphs `file doc img vid aud pdf zip apk code` already existed).
- Tests: `contrast.py` now also runs **T1b**: glyph against both tile gradient ends >= 3:1 for the 11 categories in light and dark and the two mono themes (0 failing). `gen.py --check` in sync; `parity.py` 0 differences; page JS `node --check` OK. Kotlin NOT compiled, nothing seen on a device.
- Left for later (Part B / C): grid and gallery file tiles (`NlGridView`, 11.6 "Grid file" 64-96 dp), page list `.kd` tiles still use the older `--k-*-c/-b` vars (not the `--cat-*` family), Storage Analyzer treemap / pie, search chips and Drive / FTP / SMB badges still use their own colours (4.5 says one table feeds all).

### 19.1c To-do list: everything still missing (added 2026-11-07)

Collected from every "NOT done" note of Parts 5-18 and Round 2 A / B / C. Tick (`[x]`) and date an item when it is built; add new leftovers here, not in the part notes. Order inside a group = suggested order.

**V. Verification debt (do before building more)**
- [x] V1 (2026-11-07, owner compiled; 4f fixed a PixelCopy import after a CI failure) Build the APK (CI) and fix compile errors. Never compiled: `LumenTokens.kt`, `Lm.kt`, `LumenPrefs.kt`, `NlChrome.kt` (dock, ripple, TalkBack `ExploreByTouchHelper`), `NlDrawer.kt`, `NlFileIcons.kt`, `NativeList.kt` changes, `NlClassicFolder`, launcher icon resources, `values-v31/themes.xml`.
- [ ] V2 Device pass: T5 parity screenshots, T6 glass ladder, T7 font scale 130 / 160 / 200 %, T8 gallery scroll frame budget (`lmFrameTest`), T10 native theme-switch flash.
- [ ] V3 TalkBack run on the file browser, dialogs, top bar, dock, drawer, settings.
- [ ] V4 `LumenPrefs.glassHint()` low-end guess (`isLowRamDevice`, `memoryClass <= 128`, <= 4 cores) check on the reference devices.
- [ ] V5 Check the three static dark-theme selector lists (UI4 / UI5 / UI6, with `data-cdark`) after any new dark preset.

**N. Native drawing (never done in native)**
- [x] N1 (all parts 2026-11-07, not device-tested) Native glass for dialogs (`Lm.restyle`), sheets (`NlSheets`), chrome (G1 on top / selection / bottom bar, drawer); API 31+ blur with the F0-F3 ladder and a native F-step. (sheets done 2026-11-07, Part 4a: `LumenGlass.kt`, page reports its step via `LSAndroid.glassStepSet`; chrome / drawer: stand-in done 2026-11-07, Part 4b, `LumenGlass.chromeFill`, tint-raised, NO blur; real in-window blur N1 (c) done 2026-11-07, Part 4f: `NlBackdrop.kt`, F0 / F1 on API 31+, stand-in kept for F2 / F3 and as fallback; not compiled)
- [x] N2 `NlSheets`: Lumen look, motion, drag handle, swipe-to-dismiss. (2026-11-07, Part 4a)
- [x] N3 Category tiles in grid / gallery (`NlGridView`, `NlGalView`, 64-96 dp "Grid file"); realistic Word / Excel / PowerPoint tiles are only `drawLegacy` now. (2026-11-07, 4c: grid no-thumbnail page = category tile; gallery has no file tiles)
- [~] N4 Native chrome FAB / buttons on gradients (`gPrimary`), `gSurface` on cards, `gChrome` everywhere, `gSuccess`, `gGlow`, `gSkeleton`, `gScrimTop / Bottom`, `shadowTint` as a real slot. (2026-11-07, Part 4b: `shadowTint` slot + `LumenGrad.kt`; FAB, dock pill, snackbar, tinted shadows done; `gGlow` / `gSkeleton` / `gScrim*` exist without callers: Parts 4c / 4d)
- [ ] N5 Folder drawing in pickers, album covers and other native places; drawer icons (`NlDrawer`); tiles for Devices / FTP / Cloud sheets. (4c partly: Devices / FTP / Cloud sheet tiles done in page block UI17; no native pickers / album covers exist, drawer icons already Lumen)
- [x] N6 Native radius flavour (win11 `--r-l` 12 / `--r-xxl` 24); native dialogs keep 32 dp. (2026-11-07, Part 4b, `LumenRadius`)
- [ ] N7 (moved to Part 4c) `NlChrome` Search and Viewer bar variants; Large (collapsing) top bar and scroll-driven transparent small bar (needs a scroll event page to native). (4c partly: scroll-driven transparent small bar done; Large collapsing bar and Search / Viewer variants open)
- [x] N8 Drawer storage-card header with ring chart. (2026-11-07, Part 4b, `dStorCard()`, block `LUMEN-UI16`)
- [x] N9 Native ripple inside list rows, grid / gallery views, drawer rows. (done 2026-11-07: rows + grid cards 4d; gallery cells `NlGalView` + drawer rows `DrView` 4e, same `NlRpl`)
- [x] N10 Selection check-circle flip (160 ms), partial / bar state on the day-heading select circle, hold-menu lift (1.02), 20 ms action stagger on the dock. (done 2026-11-07: flip + lift 4d; 4e: dock stagger in `NlDockView` (6 dp rise, 20 ms apart, 160 ms), day / month heading circle in the page gallery already had the dash (`.part`) and check (`.sel`) states, now squeezes open (CSS block `LUMEN-UI18`); native lists have no day headings)
- [x] N11 Sticky heading strip and fast-scroll scrubber in the native list. (done 2026-11-07: scrubber existed (`PdfScrollBar`); 4e: `NlStickyView` pill names the group of the first visible row while scrolling)
- [x] N12 (2026-11-07, Part 4a) Snackbar lift above the bottom bar / dock (`Lm.snack`), system toast only where no Activity exists.
- [x] N13 Splash: logo screen is the system one (Android 12+); older versions still show the window background only. (2026-11-07, 4c: accepted for Android under 12, window background only)

**M. Motion (Part 16 leftovers)**
- [x] M1 Exit animations: dialog, sheet, popup menu close in 200 ms accelerate (they close instantly). (page done 2026-11-07, Part 1; native dialog / sheet / snackbar exits 2026-11-07, Part 4a)
- [~] M2 Screen push of the main file screens with old-screen shift / dim; selection-start choreography. (Part 2 2026-11-07: folder push + 20 ms row tint on selection start; old-screen shift / dim and native bar / dock slide left)
- [x] M3 List insert / remove collapse. (done 2026-11-07, Part 2: render() wrapper collapses removed rows, under 200 rows)
- [x] M4 Aurora wallpaper drift (60 s) and drift off in battery saver. (done 2026-11-07, Part 2: `#home::before` drifts 60 s, off in f2 / f3 / Reduce motion / saver)
- [x] M5 Undo countdown bar and merging of repeated snackbar numbers. (page done 2026-11-07, Part 1: merge + Undo bar; native Undo bar + number merge in `Lm.snack` 2026-11-07, Part 4a)
- [x] M6 Sheet swipe-to-dismiss (page `#sheet`). (done 2026-11-07, Part 1: `/*LUMEN-UI13*/`)
- [x] M7 (2026-11-07: (a) skeleton 4d, (b) container transform 4f snapshot overlay, gallery cells / page-started viewers keep the scale-up) Skeletons in native lists; true shared-element container transform for viewer / player launch (image continuous, chrome fades in after 120 ms).

**P. Page leftovers**
- [x] P1 Swipe actions on rows, fast-scroll scrubber, hold-menu lift (page twin of N10 / N11). (done 2026-11-07, Part 2: swipe rows 96 px, scrubber, hold lift)
- [x] P2 Floating label + clear for fields outside `dlg` (sheets, settings, rename); password show / hide. (done 2026-11-07, Part 2: `.tf` outside dialogs get label + clear, password eye)
- [x] P3 Split button; glass buttons (Part 14); `.seg` has no caller yet (use it in Settings). (done 2026-11-07, Part 2: `.sbtn` + `lmSplit`, `.tbtn.glass`, `.seg` caller = Glass quality)
- [~] P4 G0 on-picture pills; grain on G2 / G3; top bar transparent-to-G1 on scroll; flatter wallpaper variant for list screens. (Part 2 2026-11-07: `.g0` class, grain on f0 dialogs / sheets, top bar glass on scroll, flat list wallpaper; `.g0` not yet applied to viewer counters / badges)
- [x] P5 Grid fallback page still uses the realistic icon; page list `.kd` tiles still use `--k-*-c / -b` instead of `--cat-*`. (done 2026-11-07, Part 1: `.k-*` tiles read `--cat-*`, `.k-office` added)
- [ ] P6 Storage Analyzer treemap / pie (`StorageTreemap.CAT_COL`, `StorageWiz`), search chips, Drive / FTP / SMB badges, `CodeHl`, dark viewer chrome and text-editor palette still use their own colours (4.5: one table). (4c partly: Storage CAT_COL + CodeHl read LumenCategory; StorageWiz, search chips, badges, dark viewer chrome open)
- [x] P7 Contrast check of gradient ends against labels (T1 tests flat slots only) and of glass tint alpha per theme (floor). (2026-11-07, Part 3)
- [~] P8 Use `--t-*` style tokens and `--st-*` in page CSS; 17 `font:` shorthands with px; lint: raw durations 68, hex 201, spacing >= 3 px 26, radius 1. (Part 1 2026-11-07: durations 68 to 13 (rest = infinite spinners), spacing 27 to 25 (negative nudges), radius 0, `font:` shorthands now `--fs-*`, hex 194 left on purpose; `--t-*` / `--st-*` still unused)
- [x] P9 Delete unused `--hc1..7` theme rules and `brand_lite_bg`. (done 2026-11-07, Part 1)
- [x] P10 Selection-circle announce inside the gallery overlay (own count node). (done 2026-11-07, Part 1: `lmLive`, aria-live on the 4 gallery count nodes)

**T. Themes**
- [x] T1 Regenerate the 42 presets from the three inputs (hue, tint, mode); today only the 8 fresh slots are derived. (2026-11-07, Part 3)
- [x] T2 Lumen family as Light / Dark pairs with a separate Auto / Light / Dark choice; Custom follow-system mode. (2026-11-07, Part 3)
- [x] T3 OS font option (Segoe-like / Ubuntu; clashes with the user's own font choice: decide). (2026-11-07, Part 3)
- [x] T4 High contrast: 3 dp focus ring and 7:1 text as a theme, not only the media query. (2026-11-07, Part 3)
- [x] T5 Live preview card in the theme picker; `onWarning` slot; ubuntu / win11 own button gradients and shadows vs Lumen. (2026-11-07, Part 3)

**A. Accessibility (Part 18 / Round 2 C leftovers)**
- [x] A1 (2026-11-07, Part 4a) `contentDescription` / roles for native list rows (`NativeList`), drawer rows (`NlDrawer`), `Lm` dialogs and snackbar.
- [x] A2 (2026-11-07, Part 4a) Chips: real 48 px hit box with ripple (today a click redirect on the `.chips` padding, no ripple there).
- [x] A3 Extend the icon-button name table `LB` whenever a new icon-only button appears. (done 2026-11-07, Part 1: `lint.py` A3 line lists unnamed icon-only buttons; 0 now)

### 19.2 Old to new quick map

| Old | New |
|---|---|
| `--bg` / `--card` / `--cont` | `canvas` / `surface` / `surfaceHi` (or `surfaceLo` for wells) |
| `--ac` / `--onac` | `primary` / `onPrimary` |
| `--sel` / `--onsel` | `primaryCont` / `onPrimaryCont` |
| `--fab` | `gPrimary` |
| `--bd` | `edge` |
| `--mut` | `inkMut` |
| `--err`, `--errc`, `--ok`, `--warn` | semantic set 4.4 |
| `--hc1..6` | category set 4.5 |
| radii 2, 4 / 8, 9, 10 / 12 / 14, 16, 18 / 20, 24 / 28 / 50% | `r.xs` / `r.s` / `r.m` / `r.l` / `r.xl` / `r.xxl` / `r.full` |
| `#sheet .card` variants (`.vs`, `.fu`, `.pj`) | sheet sizes Tall / Full / Modal-center |
| native `AlertDialog`, `dlg_bg*.xml` | dialog component 12.12 |
| `Toast.makeText`, page `toast()` | snackbar 12.14 |

### 19.3 Acceptance tests

| ID | Test | Pass when |
|---|---|---|
| T1 | Contrast table for every theme and glass step | all pairs in 4.3 and 4.7 pass |
| T2 | Token lint | no raw colour, radius, spacing, duration in page CSS or native layouts outside the token file |
| T3 | Dialog audit | every dialog in the app (list generated from code) uses the one component |
| T4 | Icon audit | every glyph is on the 24 grid, 1.75 dp stroke, from the inventory |
| T5 | Parity screenshots | page and native screens of the same theme show identical colours (pixel sample of 20 points) |
| T6 | Fallback | F0 to F3 each render without clipped or unreadable elements; Reduce transparency forces F3 |
| T7 | Font scale | 200 % on file browser, gallery, settings, dialogs: nothing clipped, nothing unreachable |
| T8 | Performance | gallery scroll with 3000 items keeps frame drops under 5 % at F0 on the mid reference device, under 10 % at F1 on the low one |
| T9 | Motion | Reduce motion removes all transforms and loops |
| T10 | Theme switch | switching themes cross-fades with no white flash on any screen, native included |

### 19.4 Do / don't

| Do | Don't |
|---|---|
| Use the next token on the scale | Invent 10 dp, 14 dp, 18 dp, 22 dp |
| Put one Primary action per surface | Show two gradient buttons side by side |
| Let glass float above content | Put blur behind list rows or text columns |
| Pair colour with glyph and word | Use red / green alone for meaning |
| Reuse the dialog, sheet, snackbar | Build a feature-specific popup card |
| Animate transform and opacity | Animate width, height, blur radius inside lists |
| Keep gradient hue drift under 20 degrees | Use rainbow gradients (except Aurora wallpaper) |
| Test every new colour on glass and on gradient | Trust that "it looked fine on my theme" |
| Offer the solid fallback for every effect | Make any feature depend on blur |
| Add new glyphs to the inventory first | Paste a random icon path |

---

*End of Lumen Design System v1.0. Next step suggested: Part 1 of 19.1 (token file and scales), then Part 7 (page dialog and snackbar) because those give the largest consistency gain for the least risk.*
