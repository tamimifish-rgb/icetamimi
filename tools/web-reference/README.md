# Web reference harness

Keeps the Android screens visually identical to the web app.

- `shoot.js` renders the published web app in headless Chromium with a mocked backend
  (`fixture.json`; no request reaches the real backend, nobody is signed in) and saves one
  screenshot per route at 411×891 dp, 2× — the same size the Android screen tests render.
- Sample data: `fixture.json`, plus optional `fixture.<area>.json` overlays whose table rows
  are appended (rows may carry embedded objects exactly as a query's `select` would return).
- `app/src/test/.../ScreenHarness.kt` and its subclasses render the native screens with the same fixture into
  `app/build/screens/`.
- `steps.<area>.js` (optional) exports `async (page, name, shot) => {}` to capture more states
  of a route (open a dialog, switch a tab) after its first screenshot.
- `summary.py [web-dir…]` prints one line per screen (block counts and worst difference),
  sampling every pixel; it is the regression check after any UI change.
- `rows.py <name…>` prints the vertical position of every block in both screenshots and the
  difference in dp; `pair.py <name…>` writes side-by-side images; `sheet.py` joins images.
- `gen_icons.py` regenerates `ui/Lucide.kt` from the Lucide icons the web source imports.

```sh
npm install                                   # Cairo font files + Lucide SVGs
node shoot.js web                             # distributor routes -> web/*.png
node shoot.js web-admin admin                 # same session as an administrator
ROUTES='[["/trips","trips"]]' node shoot.js web-admin admin
NOSESSION=1 ROUTES='[["/auth","login"]]' node shoot.js web
(cd ../.. && ./gradlew :app:testDebugUnitTest --tests '*ScreensTest*')
python3 rows.py today sale-filled             # numbers
python3 pair.py today && open pair-today.png  # pictures
```

`PLAYWRIGHT=/path/to/node_modules/playwright` selects a Playwright install; `HTTPS_PROXY`
is used when set.
