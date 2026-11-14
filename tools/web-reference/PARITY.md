# Making a native screen identical to its web screen

The Android UI is a 1:1 port of the web app's look. `ui/Common.kt` holds the web theme;
every screen is laid out from the Tailwind classes of its web source
(`naqi-ice-tamimi/src/routes/...`, `src/components/app/...`). Follow this loop per screen.

## Loop

1. Read the web TSX and copy its structure class by class (see the mapping below).
2. Give the screen data: add rows to a `fixture.<area>.json` overlay (same shape a query's
   `select` returns, embedded objects included). Both sides read the same fixture.
3. Web reference: `ROUTES='[["/route","name"]]' node shoot.js web-<area> <role>`
   (role: distributor | supervisor | admin). More states of a route (open dialog, tab,
   filled form): a `steps.<area>.js` hook.
4. Native: a test class extending `ScreenHarness` — `show(Screen.X, role)`, `open(Screen.Y,
   "name")`, `shot("name")`, `shotDialog("name")` — writing the same names.
5. `WEB=web-<area> python3 rows.py name…` lists every block's top and height on both sides.
   Done means: same number of blocks, every Δtop and Δh within ±1.5 dp.
6. `WEB=web-<area> python3 pair.py name…` then look at `pair-<name>.png` (web left, native
   right): colours, icons, weights, alignment, borders, shadows, text.

Only ever change the native side.

## Tailwind → Compose

- 1 Tailwind unit = 4 dp (`p-3` = 12dp, `gap-2` = 8dp, `h-12` = 48dp, `size-5` = 20dp).
- Text: use the package's own `Text` (never import `androidx.compose.material3.Text`) with
  `style = T.xs | sm | base | lg | xl | xl2 | xl3`, plus `.medium .semibold .bold .muted
  .color(c) .ltr`. Default is `T.base` (16/24). Never pass `fontSize`/`fontWeight` separately.
- `space-y-N` → `Column(verticalArrangement = Arrangement.spacedBy((N*4).dp))`, but (Tailwind
  v4 puts the gap as margin-bottom on each child):
  - a child with its own `mb-*` uses that instead of the parent's gap (`PageTitle` takes the
    parent's `spacing` for this); `mt-*` adds to it;
  - vertical margins do nothing on inline elements (`<label>`, `<span>`): no gap there;
  - inline text sitting directly in a block gets at least the block's line height: 24dp in a
    default block, 20dp inside a `text-sm` block (`Labeled` already does this).
- Borders take space, as in CSS: `Modifier.box(radius, background, border, borderWidth,
  shadow)` insets content by the border; fixed heights (`h-*`) include it.
- Radii: `rounded-md|lg|xl|2xl|3xl` = `Radius.md|lg|xl|xl2|xl3`; `rounded-full` = CircleShape.
- Shadows: `shadow-soft` = `softShadow(radius)`; `shadow`/`shadow-sm` = `smallShadow(radius)`.
- Colours: `Ice.*` (`text-muted-foreground` = `Ice.mutedText`, `bg-secondary` = `Ice.secondary`,
  `bg-warning/20` = `Ice.warning.copy(alpha = 0.2f)` …).
- `Button` → `Btn(text|content, variant = Default|Destructive|Outline|Secondary|Ghost|Link,
  height = <h-*>, default 36dp)`; `size="sm"` = `height = 32.dp, style = T.xs.medium`;
  icons are 16dp (`icon =` or inside the content lambda). Disabled = half opacity.
- `Input` → `Input(height = <h-*>, default 36dp, placeholder =)`; forms that use `h-12`
  inputs with placeholders can use `Field`. `Label` + control → `Labeled`.
  Native `<select>` → `Select`; `Switch` → `Toggle`; `Dialog` → `WebDialog`.
  Browser `confirm()` / `prompt()` → `AlertDialog`.
- Cards: `rounded-2xl bg-card p-4 shadow-soft` = `IceCard`; `rounded-2xl border bg-card p-3
  shadow-soft` stat = `Stat`/`StatGrid`; `divide-y rounded-2xl border bg-card` = `ListCard`
  with `Divider()`; dashed empty state = `Empty`; tinted notice = `Banner`.
- Links and tappable rows have no ripple: `Modifier.tap {}`.
- Icons: `Lucide.*` (the web's `lucide-react` names). Missing one? `python3 gen_icons.py`.
- `formatMoney / formatNum / formatDate / adenTime` already print exactly what the web's
  `Intl` formatters print; a plain `${n}` in the web source is Latin digits.
- In RTL, Tailwind `left-*`/`right-*`/`text-left` are physical: `text-left` = `TextAlign.Left`,
  physical right = `Alignment.*Start`.
