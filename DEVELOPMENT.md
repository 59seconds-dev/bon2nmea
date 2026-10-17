# BON4 workflow modernization

Development branch: `develop/bon4-workflow`. The upstream fork's `main` remains unchanged.

## Goals
- Pick BON4 files with a configurable source folder and newest-first sorting.
- Identify circuit from GPS trajectories (including TC1000 vs TC2000), allow correction, and remember verified choices.
- Generate stable output names from GPS date/time and confirmed circuit, with collision handling.
- Choose an output folder once (Android Storage Access Framework tree URI) and save subsequent conversions automatically.
- Preserve NMEA export and add VBO export **after** validating the VBO schema and RaceChrono import behavior with real BON4 samples.
- Do not add session-name/notes metadata unless RaceChrono is verified to import it.

## Existing implementation risks
- `ACTION_OPEN_DOCUMENT` ordering is controlled by the document provider, not the app.
- `getDefaultUri()` uses an app-specific file URI instead of a persisted SAF tree URI.
- The converter uses `InputStream.read(chunk)` without checking that 26 bytes were filled.
- `GlobalScope.launch` performs progress-bar/Snackbar updates outside the main thread.
- BON4 elevation is parsed but omitted from the existing `GPRMC` output.
- BON4 elevation units and conversion factor remain unverified.
- Verify timestamps, GPS coordinate scale, speed scale, and conversion parity against real logs before changing exporters.

## Delivery stages
1. Stabilize the current converter and add deterministic tests using an anonymized/sample BON4 file.
2. Persist input/output folders; implement source-file list and sort order.
3. Circuit recognition, manual override, learned track fingerprints, and safe output naming.
4. NMEA/VBO exporters and RaceChrono import verification.
5. Build and test APK on supported Android versions.

This file is a development plan, **not** a claim that any feature has already been implemented or tested.
