## [1.4.7v4-optlab-r11] - 2026-08-29

- Ported the CP4.1 V2 production chunk fast paths into the existing unified ZomDroid agent.
- Added independent Build 42 controls for Foraging, Neighbour Worker/Main, Grid/Load, Vehicles,
  Randomized Buildings, Lua MapObjects and WorldGen biome caching.
- Kept CP2C dirty-clear isolated under Experimental and default OFF.
- Verified bytecode transformation against real 42.20 and 42.20.3 game JARs; other B42 hotfixes
  use per-mechanism structural probes and vanilla fallback.
- Removed the diagnostic profiler's per-call clocks, deep split, periodic logger and broad wrappers
  from the production path; preserved R10 JAssimp Direct and Native ARM64 modules.

## [1.4.7v4-optlab-r10] - 2026-08-29

### CP3 — JAssimp direct FBX compatibility fix

- Replaced the bundled ARM64 Assimp 5.4.3 JAssimp library with the direct PZ-compatibility build
  that restores the pre-5.4 `Cluster::Transform` bone-offset semantics.
- Matched the supplied Assimp53Compat reference agent's demonstrated mesh/bone offset behavior,
  while keeping that agent completely outside `jars.tar` and all runtime `-javaagent` arguments.
- Preserved the exact R9 library as a guarded rollback and retained the reference agent with exact
  SHA-256 for diagnosis and A/B comparison only.
- Added source provenance, clean NDK r27c rebuild/install scripts, an independent rebuild workflow,
  and mandatory Gradle/CI gates for ELF64/AArch64, SONAME, ten JNI exports and the full unchanged
  3,053-symbol ABI surface.
- Added launch proof for the extracted JAssimp path, byte count, SHA-256 and direct/rollback mode.
- Host/static and packaged-payload checks pass. Android compilation and KI5/skinned-FBX gameplay
  regressions remain explicitly pending and are not claimed by this source checkpoint.

## [1.4.7v4-optlab-r9] - 2026-08-29

### OPT-LAB UI tabs and module independence

- Reorganized the existing theme-aware OPT-LAB dialog into six scrollable tabs:
  Overview, Runtime, Display, Build 42, Native and Experimental.
- Kept the existing AlertDialog, Material theme, orange accent and automatic light/dark colors;
  no UI color is hardcoded.
- Scoped the former master switch to general Runtime/Display settings only. Build 42 presets
  and individual options now remain effective and persisted when the general module is OFF.
- Prevented general profiles from rewriting Build 42 state and vice versa.
- Marked Pathfinding and PopMan visibly EXPERIMENTAL; no behavioral fix is claimed.
- Added a host regression for preference independence and made it mandatory in all workflows.
- Kept JAssimp integration and native device/gameplay regression for the next checkpoint.

## [1.4.7v4-optlab-r8] - 2026-08-28

### Native final source QA

- Fixed the second agent gate so Pathfinding-only and PopMan-only configurations install
  their transformers even when every Pacing/Stream/FBO feature is disabled.
- Added a real `premain` Pathfinding-only regression using an Instrumentation proxy.
- Moved the Build 42 section directly below Native Modules in the OPT-LAB dialog.
- Rebuilt the bundled agent as version 8 / proof schema 7 with PopManRuntime included.
- Updated stale Native Modules tests for the real opt-in PopMan toggle and added strict
  PopMan payload/bridge/JAR source gates.
- Re-ran the complete host/static suite, including both real 42.20 and 42.20.3 JARs.
- Kept all Android/device gameplay, save/reload and benchmark validation explicitly pending.

## [1.4.7v4-optlab-r6] - 2026-08-28

### CP1 Final QA + MobileGL PZCompat V1.2

- Replaced the packaged MobileGL default with the exact V1.2 present-fastpath ThinLTO
  ARM64 payload and added SHA/ELF/SONAME/Build-ID/export/security gates.
- Added automatic upgrade from both known older packaged defaults while preserving unknown
  custom renderers and invalid renderer backups.
- Applied V1.2's recommended `PRESENT_FASTPATH=1` and `OPT_SET=002,003`; proof and cache paths
  now resolve inside the current application sandbox for both install flavors.
- Added host compilation/tests for Native Modules preferences, UI wiring, feature registry,
  MobileGL installation, distinct backup preservation and CP1 device-proof validation.
- Aligned CI with NDK r27d and made all CP1/MobileGL gates mandatory before Gradle.

## [1.4.7v4-optlab-r5] - 2026-08-28

### CP1 — Pathfinding Native + Native Modules

- Added a separate Native Modules window with persistent Lighting64, PZClipper and
  Pathfinding switches; PopMan is visible but unavailable until CP2.
- Added the official Build 42 ARM64 Pathfinding payload with exact SHA-256, ELF,
  JNI-export and three-class ABI gates for Project Zomboid 42.20 and 42.20.3.
- Pathfinding remains opt-in and falls open to the original Java PolygonalMap2 route.
- OptLab agent version 6 / proof schema 5 records native Pathfinding EXERCISED and
  runtime FALLBACK evidence.
- Kept CP4's automatic agent rebuild and Build 42 per-feature compatibility delivery fixes.

## [1.4.3] - 2026-07-05

### Added
✅ Build 42.17 support  
Support for the latest Build 42.17.

✅ CPU/GPU detection  
When creating a new game instance, the app now detects your CPU/GPU and suggests a better renderer.

✅ Optimization tools update  
- BetterFPS now supports full mode — users can select a mode instead of uploading a single file  
- Every Texture Optimized helper added — replaces the texture pack automatically (recommended for 4GB devices)  
- ZombieBuddy framework installation added  
- ZB BetterFPS mod installer added (works only with ZINK)

✅ Indonesian language  
Added Indonesian localization.

✅ New menu icons  
Updated UI with new icons.

---

### Changed
🔧 Improved Mod Fix algorithm  
Better handling of problematic mods with path issues.

🔧 Mod Fix merged with Import Mods  
No need to manually decide if a mod needs fixing — the installer handles double-path issues automatically.

🔧 Smart import improved  
Mods can now be wrapped in additional folders — the installer handles it correctly.

🔧 Gamepad connection handling reworked  
Reduced crashes when connecting/disconnecting controllers (needs more feedback).

---

### Fixed / Stability
🛠️ JNI sig cache size restored to 64  
Reduces cache misses on older devices and improves stability during long sessions on Build 41.

🛠️ Mutex added to JNI trampoline generation  
Prevents race conditions during world load on Build 42.

🛠️ Keep screen on  
The screen now stays on while the game is running.

## [1.4.1] - 2026-04-04
### Added
✅ Voice Chat Support (Experimental)
Multiplayer voice chat is now enabled. Note: this feature is experimental — it may cause crashes on some devices when receiving incoming voice from other players. We need more data to investigate and fix these issues.
If you experience problems, we'd greatly appreciate detailed feedback including your device model, chipset, and game build version.

✅ Version Checker
Tap the new "Check for updates" item in the navigation menu to see your current version and check if a newer release is available on GitHub. A direct link to the release is provided if an update is found.

✅ Environment Variables
Added an Environment Variables field in Settings — useful for advanced rendering tweaks and GPU driver flags (e.g. TU_DEBUG, ZINK_DEBUG, LIBGL_*).

✅ Touchpad & Mouse Stick — Tap to Click
Single tap on the Touchpad and Mouse Stick on-screen controls now sends a left mouse click.

✅ Preset Info
When creating a new game instance, selecting a build preset (Build 41 / 42 / 42.12+) now shows a short description — recommended RAM, supported devices, and what to expect.

✅ Localization
More strings have been localized across the app into Russian, Portuguese (Brazil) and Chinese (Simplified).

## [1.4.0] - 2026-03-20
### Added
**✅ Custom Vulkan Driver**
You can now import your own Snapdragon Vulkan driver (.so file) via the new Import/Export Custom Driver menu item.
The driver is loaded alongside the built-in ones — select Custom Driver in Settings after importing.
Export your loaded driver to share or back it up — note that it will be lost on uninstall.

**✅ Game Log Export**
Added Export Game Log menu item — exports console.txt directly from the game folder.
Useful for bug reports and troubleshooting. If the game crashed before creating the Zomboid folder, the export will show a clear message.

**✅ Gamepad Fix (Split Screen)**
Fixed gamepad inputs being blocked when a physical keyboard is connected (bug in v1.3.7).
Split screen co-op with keyboard + gamepad now works correctly.

**✅ Localization**
Added Russian by AI, Portuguese (Brazil) by AI and Chinese (Simplified) by AI + @neighbor-bear translations.
More strings have been extracted and localized across the app.

**✅ UI & Other**
Added Reddit community link to the navigation menu.
App version is now displayed at the bottom of the navigation menu.

---

## [1.2.9.v4] - 2025-12-24
### Added
**🚀 On-Screen buttons: added a high-contrast outline.**

- So they don’t blend into very bright in-game scenes.

**🛠 Fixes** 

- **Multiplayer**: fixed crashes when trying to connect to a server (b41.78).

---

## [1.2.9] - 2025-12-5
### 🆕 What's New
**🚀 Newer Java version.**

- New launcher version is out with one single change — we've upgraded from Java 17 to the newer Java 21.

For better performance.

---


## [1.2.6] - 2025-11-02
### Added
**🚀 Detection of physical keyboard and mouse**
The launcher now detects connected keyboards and automatically enables PC-style layout in-game. For best stability, it's recommended to connect your keyboard and mouse before launching the game. Hotplug support is experimental and may behave inconsistently.

**🚀 Extended functionality for physical gamepads via customizable on-screen buttons**
If you're playing with a physical gamepad but need more control options, you can open the Controls Editor and create new MNK-type buttons mapped to existing keyboard keys. These MNK buttons will remain visible even when a gamepad is connected. For example, you can add Zoom+ / Zoom− buttons mapped to KEY_EQUAL / KEY_MINUS.

---
## [1.2.4.v2] - 2025-10-17
### Added
**🛠 Fixes**
- Fixed the issue with missing on-screen controls on MIUI devices.

**🚀 Native Library Integration**
- Added a new field for uploading native libraries from Project Zomboid developers (PZ build 42.12) when adding a new game instance.
- Multiplayer support (requers 2 native libs: libRakNet64.so, libZNetNoSteam64.so)

---

## [1.2.4] - 2025-10-13
### Added
**🛠 Fixes**
- Integrated some error fixes by @Wakort (v1.2.2), improving overall stability and compatibility.

**🎮 Gamepad Enhancements**
- Integrated extended gamepad support for broader device compatibility (from v1.2.3.v2).
- Integrated mapping configuration now persists after exiting the game (from v1.2.3.v2).

**🚀 Native Library Integration**
- Added native libraries from Project Zomboid developers (PZ build 42.12).
- Significant performance improvements, especially on build 42.
- Faster loading times and smoother gameplay experience.
- Known issue: crash occurs when opening the map on Build 42.8 and above  

---

## [1.2.3.v2] - 2025-09-29
### Added
- **Extended gamepad support for triggers (LT/RT).**  
  The launcher now correctly handles triggers regardless of how the device reports them:
  - as **axes** (`AXIS_LTRIGGER` / `AXIS_RTRIGGER`, or fallbacks like `Z`/`RZ`, `BRAKE`/`GAS`);
  - or as **buttons** (`KEYCODE_BUTTON_L2` / `KEYCODE_BUTTON_R2`).

- **LT/RT mapping in the setup wizard.**  
  The mapping wizard now includes dedicated steps for LT and RT, allowing proper configuration even when a device only sends button events.

- **Persistent custom mapping.**  
  User-defined layouts are saved in `SharedPreferences` and automatically loaded at startup, so mappings no longer reset after exiting the game.

### How it works
- We **normalize trigger input** to the standard GLFW layout:  
  - **Analog triggers** are mapped to axes `a4` (LT) and `a5` (RT).  
  - If a device only reports **buttons (L2/R2)**, we **synthesize axis values**: press → `1.0`, release → `0.0`.  
- This ensures the game always sees the expected axes, regardless of controller quirks.

### Changed
- The mapping wizard now runs through 12 steps (added LT/RT).  
- Saved mapping format expanded with dedicated slots for LT/RT, but remains backward-compatible.

### Notes
- D-Pad logic remains unchanged (future improvements planned separately).  

### Troubleshooting
- If triggers feel unresponsive or behave incorrectly (e.g. bound to the right stick):
  1. Restart the mapping wizard and reassign LT/RT.  
  2. Confirm that custom mappings are auto-loaded at launcher startup.  
  3. If issues persist, please provide your gamepad model and raw axis log output.

### Thanks
- Huge thanks to testers for logs and reports — they made it possible to build a flexible trigger conversion layer.

---

## [1.2.3] – 2025-09-15
### Added
- Fixed GUIDE button stuck in the Mapping section - the GUIDE/HOME button removed from the Mapping process.

---

## [1.2.0] – 2025-09-14
### Added
- Initial release with basic gamepad support by @shimux0.  
- Default mapping for standard Android-compatible controllers.  
