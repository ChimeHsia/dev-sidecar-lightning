# Browser styles and theme integration

Accepted on 2026-11-02 after isolated source review, local tests and targeted
device verification. UI changes originate from `128cca9`, `d9231f4` and
`ba03219`; the tested combined source, including the theme repair, is
`befef499589d20cb1fd6aa7fe293d009524b7240`.

## Integrated behavior

- Desktop, Side Drawer and Bottom Drawer styles, outlined controls and vector
  launcher icon.
- Settings and Exit incognito menu entries in incognito mode.
- Material alert, confirm, prompt and before-unload implementation.
- Existing duplicate-tab snapshots, lazy WebView creation, background lifecycle,
  activity recreation and wide-color preference changes remain present.
- Complete Material theme dependencies at startup and after preference changes.

The first combined build `640e937` crashed on confirm/prompt because its
retained AppCompat runtime theme lacked `colorSurface`. The repair adds only
the required Material theme chain and 60 regression cases. The earlier
UI-only build's successful dialogs did not establish combined-build safety.

## Evidence and result

- Browser JVM/Robolectric: **196 passed**, zero failures or skips, including
  actual dialog/input display, button results, Back cancellation and themes.
- Release APK SHA-256:
  `36f5de3379e14f488793a8bb83d26ee2087b8548e153252d7238d6a98810ded8`.
- Payload marker 8; project zip SHA-256:
  `d7a1c8d256272831698a03192997e9e15b1823a688d136f345f51986aacba981`.
  Assets/JNI, signer and CA match the earlier accepted payload 8.
- HONOR PPG-AN00 / Android 16: normal and incognito confirm/prompt/alert
  positive, negative and Back results passed; no corresponding new crash.
- Incognito cookie was independently read back before exit, then empty after
  re-entry at the same test origin. Settings round-trip and exit passed.
- Three tab styles, lazy duplication and Google HK HTTPS passed targeted checks.
  System theme, Bottom Drawer and wide color enabled were restored.

Before application, all 414 eligible original browser source/config file
hashes matched the integration snapshot. The accepted 53-path style/theme
delta was applied with before/after guards and a local backup, preserving the
other browser changes. No APK, Node payload, CA key or raw device log is
committed.

## Limits and device incident

Before-unload runtime behavior and pending-dialog exactly-once callbacks during
Activity/WebView destruction or process restart remain unverified. The light
theme received a confirm smoke check; the complete device matrix used the
original system theme. Long-duration stability and actual panel color accuracy
are outside this acceptance.

The test worker initially installed the UI branch's wrong application ID,
updating existing `top.panming.dsbrowser` before badging validation. Data was
retained, but its prior APK was not backed up and has not been restored.
This was disclosed to the user. Subsequent installations target only
`com.dsha.browser` after package/signature/hash checks and APK backup.

The two earlier intentional crash reproductions may have affected volatile
tab state; complete original-tab-state preservation is not claimed. Test
services and forwards were removed; local test URLs can remain in history.
The fixed combined APK remains installed, and further device actions are
coordinated by the same owner.

Local reports are retained under the chat's visualization directory:
`integration-640e937-device/REPORT-befef49.md` and its fixture result file.
Payload 9 and native Kotlin modules require separate acceptance.
