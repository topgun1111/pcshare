LANShare Lumen native interface consistency pass

This is a changed-files bundle, not a complete Android Studio project.
Copy the included files into the matching paths in your project, replacing App.kt and the documentation files:

- app/src/main/java/com/lanshare/app/LumenNativeTheme.kt (new)
- app/src/main/java/com/lanshare/app/App.kt (updated)
- LUMEN.md (updated)
- CHANGELOG.md (updated)
- HANDOVER_READ_BEFORE_CODE.md (updated)

What changed:
- A shared Activity-lifecycle theme pass applies Lumen system-bar colours and icon contrast.
- Plain root backgrounds use the Lumen canvas.
- Default monochrome text and editable fields use Lumen ink/muted ink/primary colours.
- Standard buttons receive Lumen primary text and a minimum 48 dp touch height.
- Explicitly styled semantic controls and custom-drawn surfaces are intentionally preserved.

Validation:
- Python syntax checks passed.
- Lumen generator check passed.
- Theme parity: 624 custom inputs + 42 presets, 0 differences.
- Contrast checks: 0 failing pairs; P7 and T4 checks passed.
- Android/Kotlin compilation was NOT run because Gradle and Android SDK are not installed in this environment.
- Device and TalkBack testing remain required. See LUMEN.md Part 3.
