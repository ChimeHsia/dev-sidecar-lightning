# Browser color and tab resource changes

This change covers a user-selectable window color mode, lazy duplicated tabs,
and consistent WebView lifecycle handling. It does not replace the system
WebView or modify its decoded-image, JavaScript-heap, or GPU texture storage.

## Intended behavior

- The display settings expose wide color gamut. Existing users retain the
  previous default of enabled. Android versions below API 26 use the platform
  default because they do not provide the window color-mode API. Android 10+
  updates the window in place. An attached Android 8-9 window is recreated when
  the setting changes; its pages may reload.
- Duplicating a tab leaves the source selected and creates an independent
  background tab. The copy does not allocate another WebView or start page
  requests until selected.
- An unactivated copy shares an immutable application-owned navigation snapshot.
  Identical large encoded snapshots can reuse backing storage. Mutable state
  passed into WebView is detached. This does not clone an active DOM, JavaScript
  heap, canvas, or in-flight request.
- Each activated tab has its own WebView and mutable runtime state. The existing
  browser profile and ordinary WebView cache rules remain in effect; duplication
  does not create a separate cookie/storage profile.
- Background WebViews receive background lifecycle settings when allocated.
  Resuming a foreground tab restores its lifecycle settings. Late callbacks
  must not recreate a frozen or destroyed WebView.
- The process-wide `pauseTimers()` operation is reserved for application
  backgrounding, not for switching a single tab into the background.

## Local validation

On November 2, 2026, `:app:testLightningPlusDebugUnitTest` passed all 136 tests,
with zero failures, errors or skipped tests. This includes 63 newly added cases:

| Suite | Cases |
| --- | ---: |
| TabAdapter lifecycle and duplication integration | 18 |
| Immutable snapshots and content sharing | 16 |
| Window color mode | 6 |
| Display color preference | 4 |
| Recreation handoff | 5 |
| Session lifecycle/persistence ordering | 8 |
| Session visibility/disposal ordering | 6 |

Tests cover a one-entry WebView pool with eviction concurrent with explicit
freeze, cancellation of an obsolete initializer by user navigation, lazy copies
surviving source closure, nested-state isolation, and failed-history fallback.
Recreation tests use a real Robolectric Activity/ViewModel retention cycle.

The test build includes Android resources. Two test-only accommodations are
needed on the Windows host: canonical directory separator normalization for
AndroidX's internal-storage handler, and an offscreen-pre-raster property recorder
because Robolectric's default `RoboWebSettings` stubs that property.

The successful run used JDK 21, Gradle 9.8.2, offline dependencies, one worker, a
1024 MiB build heap and a 384 MiB test heap. The local run disabled configuration
cache/isolated projects and used in-process Kotlin compilation; no permanent
project memory settings were changed. JVM/Robolectric tests establish the state
and ownership contracts, not device rendering or a frame-rate improvement.

The wide-gamut setting uses a dedicated multi-process preference file so the
normal and incognito processes can observe the same selection. Actual
cross-process notification and device color output remain device acceptance
items.

`:app:assembleLightningPlusRelease` also completed successfully. The resulting
APK is arm64-only (`com.dsha.browser`, version 5.1.0 / code 102), and its APK v2
signature verifies. SHA-256:

```text
5084edcb3a71486e0d2880afa7330d1ca2f307daa4b4c5370023acc9465aa9cf
```

The APK uses the workspace's existing embedded Node payload archives; this
change did not regenerate or synchronize that separate payload. Building and
signature verification do not establish proxy health or device rendering.
An independently named APK and local build evidence are stored outside the
repository in this chat's `tab-resource-implementation` artifact directory.

## Device acceptance

Device testing must be coordinated with the device owner. This implementation
does not install, replace, clear, or drive the user's running browser.

1. Open display settings, disable wide color gamut, and return to the existing
   page. Enable it again and return. Verify ordinary and incognito windows;
   check a wide-gamut image as well as an ordinary sRGB page. Navigation and
   page state should survive the in-place setting change on Android 10+.
   On Android 8-9, verify the notified window recreation restores navigation
   without a recreation loop; live JavaScript/form state may be lost.
2. On Android below API 26, open display settings and return to browsing. The
   unsupported setting must not invoke the newer window API.
3. Duplicate an ordinary page. The source remains selected. Before selecting
   the copy, verify there is no second page load. Select it and verify separate
   navigation and back/forward history.
4. Duplicate again from an unactivated copy through a suitable internal test
   harness. Verify immutable snapshot sharing and independent activation.
5. Navigate or close the source before selecting its copy. Verify the copy
   retains the captured navigation state. Save/restart and close/reopen an
   unactivated copy as separate cases.
6. Repeat duplication in incognito mode; verify no transfer to ordinary mode.
   Do not expect unsaved form data, JavaScript objects, canvas state, or active
   network requests to be cloned.
7. Open a link in a background tab, switch to it, switch away, background the
   app, and resume. Verify loading, foreground animation, and media behavior.
8. Exercise WebView-pool eviction and tab closure while page-finished callbacks
   are pending. Verify the old WebView is not reused or recreated by a callback.

These checks have no implied performance target. Any claim of improved FPS,
power, or memory use requires measurement of the same workload.
