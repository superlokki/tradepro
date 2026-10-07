# Building Trade Pro

## Requirements

- JDK 17 or later. The jar is compiled for Java 17, like Bookmap's official examples.
- A local Bookmap installation: the build compiles against its API jars, by default in `C:\Program Files\Bookmap\lib`.

## Build

Two ways, giving the same result (the jar at the project root, classes in `build/classes`).

With Gradle (9.8.0, downloaded automatically on first run; works with Java 17 to 27):

    .\gradlew.bat jar

Without Gradle:

    powershell -ExecutionPolicy Bypass -File build.ps1

If Bookmap is installed elsewhere:

    .\gradlew.bat jar -PbookmapLib="D:/Bookmap/lib"
    powershell -ExecutionPolicy Bypass -File build.ps1 -BookmapLib 'D:\Bookmap\lib'

## Jar name and version

Jars are named `<username>_<addonName>_<addonVersion>.jar`, for example `BrunoF_TradePro_1.0.1.jar`, where the username is the Bookmap account name.

- Gradle: the version is `version` in `build.gradle`; the username is `addonUser` (or `-PaddonUser=...`).
- `build.ps1`: use `-Version` and `-User`.

      powershell -ExecutionPolicy Bypass -File build.ps1 -Version 1.0.2

Bookmap's servers recognize the exact file that was uploaded. Do not rebuild a version that has already been uploaded or released: raise the version number instead.

## Fast reload during development

To try a change without loading a new jar each time:

1. In Bookmap > Configure add-ons > **Add**, choose `build\classes\bm-strategy-package-fs-root.jar`. It is an empty file: Bookmap then reads the classes directly from `build\classes`.
2. After each change, rebuild, then reload the add-on (or restart Bookmap).

## Testing

There are no automated tests. The add-on is checked by hand in Bookmap, in replay or simulation: place, move, cancel, reload.

## Source layout

- `src/main/java/com/tradepro/TradePro.java`: the add-on (mouse, drawing, orders, settings).
- `src/main/java/com/tradepro/PositionTracker.java`: position size and average price, from Bookmap's status when the provider sends it, otherwise rebuilt from executions.
- `src/main/java/com/tradepro/PriceGrid.java`: conversions between prices and price levels, and price formatting.
