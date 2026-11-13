# Lumen semantic dialog audit and consistency pass

**Date:** 2026-11-13  
**Scope:** all page `dlg({...})` sites in `ui.html`, all native `AppPal.dlg()` sites and explicit native positive/negative/neutral button declarations.  
**Result:** semantic consistency changes implemented; action callbacks and return values preserved.

## Audit coverage

- Page dialog call sites: **58**.
- Native Lumen dialog entry points: **77** (`AppPal.dlg()`); the only remaining `AlertDialog.Builder(...)` constructor is the central subclass in `AppPal.kt`.
- Native explicit button declarations: **90**.
- Choice-list dialogs without action rows were included in the scan; their list-item callbacks remain unchanged.
- Existing Lumen token, contrast, parity, and lint checks were rerun after the changes.

## Findings and exact corrections

| Location | Before | Correction |
|---|---|---|
| `app/src/main/java/com/lanshare/app/Lm.kt` (dialog `restyle`, `isDismissAction`) | Every visible positive button received a filled primary style unless the dialog was destructive. Secondary destructive choices were styled like ordinary text. | Classify acknowledgement/cancel labels as quiet actions in notice dialogs; retain filled primary styling for real operations; destructive alternatives receive error-coloured text. Explicit destructive primary actions retain the error-container fill. |
| `app/src/main/assets/ui.html` (`dlg`, around line 1796) | `dng` was true when either `danger` **or** `altDanger` was true, so a safe primary action such as “Move to bin” inherited destructive styling when “Delete permanently” was only the alternative. | Primary danger now depends only on `o.danger`; `altDanger` styles and gives error feedback only to the alternative button. |
| `app/src/main/java/com/lanshare/app/VaultActivity.kt` (formerly lines 192-489) | Vault dialogs used raw `AlertDialog.Builder`, bypassing Lumen theme, button hierarchy, sizing and semantic styling. | All Vault dialogs now use `AppPal.dlg`. Permanent Delete/Remove confirmations are explicitly `kind("danger")`; biometric enable prompt is `kind("perm")`. Existing callbacks and dialog flows are retained. |
| `app/src/main/java/com/lanshare/app/TextEditorActivity.kt` (`failLoad`, now around line 832) | “Close” was the positive/primary action while “Retry” was secondary. | “Retry” is now primary; “Close” is secondary. Both original callbacks are preserved. |
| `app/src/main/java/com/lanshare/app/UsActivity.kt` (Search index notice, now around line 501) | “OK” occupied the primary slot while the actionable “Rescan now” was neutral. | “Rescan now” is primary; “Close” is secondary. Same callbacks. |
| `app/src/main/java/com/lanshare/app/StorageActivity.kt` (System / other notice, now around line 1229) | “Allow access” was neutral while “OK” was primary. | When access is needed, “Allow access” is primary and “Close” secondary; otherwise the sole “Close” action is rendered as a quiet dismissal. |
| `app/src/main/java/com/lanshare/app/VaultActivity.kt` (biometric offer, now around line 284) | “Not now” and “Don’t ask again” occupied the negative and neutral slots in reverse semantic order. | “Not now” is the ordinary secondary action; “Don’t ask again” is the tertiary persistent choice. Both callbacks unchanged. |

## Shared semantic rules now enforced

- **Primary filled action:** an operation that proceeds (Save, Apply, Connect, Rescan, Retry, Delete, etc.).
- **Quiet acknowledgement/dismissal:** Close, Dismiss, Got it, Cancel, Back, Not now; OK/Tamam/Done are quiet in `info`, `warn`, `perm` and `ok` notice dialogs, but remain primary in ordinary form dialogs where they submit data.
- **Destructive primary:** error-container fill for destructive confirmations.
- **Destructive secondary/tertiary:** error-coloured text, without competing with the primary action.
- **HTML alternatives:** `altDanger` affects only the alternative button, never the primary button.
- Button callbacks, promise results (`true` / input text / `null` / `alt`), cancellation and dismissal mechanics are unchanged.

## Full page-dialog inventory

| ui.html line | Dialog title | Primary label | Alternative | Classification |
|---:|---|---|---|---|
| 1908 | Add another LANShare device | Connect | - | input |
| 1987 | Google Drive | Reconnect | - | kind=info |
| 2344 | New album | Create | - | input |
| 2437 | Copy or move if new | Move | Copy | kind=info |
| 2449 | Move  | Move to bin | Delete permanently | alternative destructive, kind=warn |
| 2575 | Rescan all pictures? | Rescan | - | kind=warn |
| 2764 | Pictures folder of  | Save | - | input |
| 3177 | Delete logs? | Delete | - | primary destructive |
| 3290 | Turn on root access? | Turn on | - | primary destructive, kind=perm |
| 3324 | Delete the search model? | Delete | - | primary destructive |
| 3346 | Exit LANShare? | Exit | - | primary destructive, kind=warn |
| 3363 | Import settings | Import | - | primary destructive, kind=warn |
| 3445 | Clear all PDF caches? | Clear | - | kind=warn |
| 3469 | Rescan the whole phone? | Rescan | - | kind=info |
| 3475 | Read text again? | Read again | - | kind=info |
| 3655 | Eject  | Open | - | kind=info |
| 3700 | Restore settings | Restore | - | primary destructive, kind=warn |
| 4123 | What the printer reports | Close | - | kind=info |
| 4491 | Stored PDF not found | Edit | - | kind=warn |
| 4536 | Remove  | Remove | - | primary destructive |
| 4542 | Clear recent prints? | Clear | - | primary destructive, kind=warn |
| 4632 | Merge  | Merge | Skip look-alikes | kind=warn |
| 4639 | Find duplicates | Similar images | Exact copies | kind=info |
| 4682 | Delete  | Delete | - | standard |
| 4690 | Delete  | Delete | - | primary destructive |
| 4692 | (dynamic title) | OK | - | alternative destructive |
| 4767 | Copies of  | Set | - | input |
| 4773 | Remove  | Remove | - | primary destructive |
| 4790 | Remove  | Remove | - | primary destructive |
| 4804 | Remove all pictures from Print space? | Remove all | - | primary destructive |
| 4882 | Pictures per sheet | Apply | - | input |
| 4885 | Auto-fill all sheets? | Auto-fill | - | kind=warn |
| 4890 | Save this layout | Save | - | input |
| 4893 | Replace \u201c | Replace | - | kind=warn |
| 4899 | Use \u201c | Use layout | - | kind=warn |
| 4912 | Remove \u201c | Remove | - | primary destructive |
| 4969 | Remove sheet  | Remove | - | primary destructive |
| 5044 | Remove  | Remove | - | primary destructive |
| 5113 | (dynamic title) | Print | - | kind=warn |
| 5117 | Two-sided sheets | Yes, both sides | No, one side at a time | kind=info |
| 5124 | Now the backs | Print the backs | Last sheet first | kind=info |
| 5195 | Rotation and size | Apply | - | input |
| 5272 | Keep pictures apart | Spread them | Keep as they are | kind=warn |
| 5504 | Delete  | Delete | - | kind=warn |
| 5528 | Empty the Recycle bin? | Empty | - | primary destructive, kind=warn |
| 5568 | Clean junk files? | Clean | - | kind=warn |
| 5621 | Empty the Recycle bin? | Empty | - | primary destructive, kind=warn |
| 5635 | Delete  | Delete | - | primary destructive |
| 5641 | (dynamic title) | Details | - | kind=warn |
| 5783 | Rename | Rename | - | input |
| 5786 | New folder | Create | - | input |
| 6343 | New PIN? | New PIN | - | kind=warn |
| 6658 | Switch to  | Switch | - | kind=warn |
| 6689 | Stop this program? | Stop | - | kind=warn |
| 6778 | Rename shortcut | Save | - | input |
| 6790 | Reset shortcuts? | Reset | - | kind=warn |
| 6906 | " | Replace | Keep both | kind=warn |
| 7006 | Device name | Save | - | input |

## Full native action-button inventory

| Kotlin file | Line | Slot | Label |
|---|---:|---|---|
| `com/lanshare/app/ImageEditActivity.kt` | 261 | negative | Cancel |
| `com/lanshare/app/ImageEditActivity.kt` | 262 | positive | OK |
| `com/lanshare/app/ImageEditActivity.kt` | 279 | negative | Cancel |
| `com/lanshare/app/ImageViewerActivity.kt` | 336 | positive | Close |
| `com/lanshare/app/ImageViewerActivity.kt` | 608 | negative | Cancel |
| `com/lanshare/app/ImageViewerActivity.kt` | 609 | positive | Delete |
| `com/lanshare/app/InboxActivity.kt` | 201 | positive | Clear |
| `com/lanshare/app/ModelLinks.kt` | 89 | positive | Copy details |
| `com/lanshare/app/ModelLinks.kt` | 93 | negative | Dismiss |
| `com/lanshare/app/OfficeViewerActivity.kt` | 379 | negative | Cancel |
| `com/lanshare/app/OfficeViewerActivity.kt` | 380 | positive | OK |
| `com/lanshare/app/OfficeViewerActivity.kt` | 446 | negative | Cancel |
| `com/lanshare/app/OfficeViewerActivity.kt` | 456 | negative | Close |
| `com/lanshare/app/OfficeViewerActivity.kt` | 473 | negative | Close |
| `com/lanshare/app/OfficeViewerActivity.kt` | 504 | positive | OK |
| `com/lanshare/app/OfficeViewerActivity.kt` | 518 | positive | OK |
| `com/lanshare/app/PdfViewerActivity.kt` | 812 | positive | Go |
| `com/lanshare/app/PdfViewerActivity.kt` | 813 | negative | Cancel |
| `com/lanshare/app/PdfViewerActivity.kt` | 1258 | negative | Close |
| `com/lanshare/app/PdfViewerActivity.kt` | 1260 | positive | Copy |
| `com/lanshare/app/PdfViewerActivity.kt` | 1264 | neutral | Share |
| `com/lanshare/app/PhonePrint.kt` | 143 | positive | Print |
| `com/lanshare/app/PhonePrint.kt` | 144 | negative | Cancel |
| `com/lanshare/app/PlayerActivity.kt` | 950 | positive | OK |
| `com/lanshare/app/PlayerActivity.kt` | 971 | negative | Cancel |
| `com/lanshare/app/PlayerActivity.kt` | 1017 | negative | Cancel |
| `com/lanshare/app/PlayerActivity.kt` | 1018 | positive | Delete |
| `com/lanshare/app/PrintLogActivity.kt` | 324 | negative | Cancel |
| `com/lanshare/app/PrintLogActivity.kt` | 363 | positive | Clear |
| `com/lanshare/app/PrintLogActivity.kt` | 364 | negative | Cancel |
| `com/lanshare/app/StorageActivity.kt` | 930 | positive | Close |
| `com/lanshare/app/StorageActivity.kt` | 960 | positive | OK |
| `com/lanshare/app/StorageActivity.kt` | 972 | positive | Delete |
| `com/lanshare/app/StorageActivity.kt` | 973 | negative | Cancel |
| `com/lanshare/app/StorageActivity.kt` | 1076 | positive | Delete |
| `com/lanshare/app/StorageActivity.kt` | 1077 | negative | Cancel |
| `com/lanshare/app/StorageActivity.kt` | 1229 | positive | Allow access |
| `com/lanshare/app/StorageActivity.kt` | 1230 | positive | Close |
| `com/lanshare/app/StorageWiz.kt` | 763 | negative | Close |
| `com/lanshare/app/StorageWiz.kt` | 1354 | positive | App settings |
| `com/lanshare/app/TextEditorActivity.kt` | 832 | positive | Retry |
| `com/lanshare/app/TextEditorActivity.kt` | 833 | negative | Close |
| `com/lanshare/app/TextEditorActivity.kt` | 929 | positive | Overwrite |
| `com/lanshare/app/TextEditorActivity.kt` | 930 | negative | Cancel |
| `com/lanshare/app/TextEditorActivity.kt` | 931 | neutral | Discard mine |
| `com/lanshare/app/TextEditorActivity.kt` | 972 | positive | Retry |
| `com/lanshare/app/TextEditorActivity.kt` | 973 | negative | Stay |
| `com/lanshare/app/TextEditorActivity.kt` | 974 | neutral | Discard |
| `com/lanshare/app/TextEditorActivity.kt` | 976 | positive | OK |
| `com/lanshare/app/TextEditorActivity.kt` | 1001 | positive | Reload |
| `com/lanshare/app/TextEditorActivity.kt` | 1041 | positive | Go |
| `com/lanshare/app/TextEditorActivity.kt` | 1042 | negative | Cancel |
| `com/lanshare/app/TextEditorActivity.kt` | 1116 | positive | OK |
| `com/lanshare/app/UpdateCheck.kt` | 73 | positive | Kapat |
| `com/lanshare/app/UpdateCheck.kt` | 87 | positive | İndir |
| `com/lanshare/app/UpdateCheck.kt` | 90 | negative | Sonra |
| `com/lanshare/app/UpdateCheck.kt` | 91 | neutral | Bu sürümü atla |
| `com/lanshare/app/UpdateCheck.kt` | 200 | negative | İptal |
| `com/lanshare/app/UpdateCheck.kt` | 248 | positive | Tarayıcıda aç |
| `com/lanshare/app/UpdateCheck.kt` | 249 | negative | Kapat |
| `com/lanshare/app/UpdateCheck.kt` | 258 | positive | Kur |
| `com/lanshare/app/UpdateCheck.kt` | 259 | negative | Kapat |
| `com/lanshare/app/UsActivity.kt` | 420 | positive | Save |
| `com/lanshare/app/UsActivity.kt` | 421 | negative | Cancel |
| `com/lanshare/app/UsActivity.kt` | 501 | positive | Rescan now |
| `com/lanshare/app/UsActivity.kt` | 733 | negative | Cancel |
| `com/lanshare/app/UsActivity.kt` | 935 | positive | OK |
| `com/lanshare/app/UsActivity.kt` | 938 | negative | Cancel |
| `com/lanshare/app/UsActivity.kt` | 990 | positive | OK |
| `com/lanshare/app/UsActivity.kt` | 1010 | positive | Delete |
| `com/lanshare/app/UsActivity.kt` | 1011 | negative | Cancel |
| `com/lanshare/app/UsActivity.kt` | 1034 | negative | Cancel |
| `com/lanshare/app/UsActivity.kt` | 1067 | positive | OK |
| `com/lanshare/app/UsIndexActivity.kt` | 276 | positive | Rescan |
| `com/lanshare/app/VaultActivity.kt` | 202 | positive | Delete |
| `com/lanshare/app/VaultActivity.kt` | 209 | positive | OK |
| `com/lanshare/app/VaultActivity.kt` | 284 | positive | Turn on |
| `com/lanshare/app/VaultActivity.kt` | 285 | negative | Not now |
| `com/lanshare/app/VaultActivity.kt` | 286 | neutral | Don |
| `com/lanshare/app/VaultActivity.kt` | 308 | negative | Cancel |
| `com/lanshare/app/VaultActivity.kt` | 326 | positive | Unlock |
| `com/lanshare/app/VaultActivity.kt` | 329 | negative | Cancel |
| `com/lanshare/app/VaultActivity.kt` | 358 | positive | Unlock |
| `com/lanshare/app/VaultActivity.kt` | 363 | negative | Cancel |
| `com/lanshare/app/VaultActivity.kt` | 395 | positive | I saved all 3 |
| `com/lanshare/app/VaultActivity.kt` | 405 | positive | OK |
| `com/lanshare/app/VaultActivity.kt` | 447 | positive | Remove |
| `com/lanshare/app/VaultActivity.kt` | 457 | positive | Remove |
| `com/lanshare/app/VaultActivity.kt` | 465 | positive | I wrote it down |
| `com/lanshare/app/VaultActivity.kt` | 476 | positive | Check |

## Validation

- `python3 tools/lumen/gen.py --check`: pass (generated Lumen artifacts are unchanged).
- `python3 tools/lumen/parity.py`: pass, 624 custom inputs + 42 presets, 0 differences.
- `python3 tools/lumen/contrast.py`: pass, 0 failing contrast pairs across theme, custom, category, gradient and high-contrast checks.
- `python3 tools/lumen/lint.py`: pass; 58/58 page dialogs have the required semantic hero/input path; 0 native notice dialogs missing `kind()`; 0 unnamed icon-only buttons; new A9 semantic-dialog assertions pass.
- Inline JavaScript syntax: pass (`node --check` on all 9 inline scripts).
- Android/Kotlin build: not run because this source archive has no Gradle wrapper and Gradle is not installed in the execution environment. Device-level visual verification remains outstanding.

## Deliberately unchanged

- Choice-list rows, item selection callbacks and menu contents.
- Text-entry confirmation callbacks and validation.
- The native `AlertDialog` positive/negative/neutral callback bodies.
- Existing dialog sizing, animations, hero artwork, theme tokens and contrast values.
