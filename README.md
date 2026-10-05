# Trade Pro — Bookmap add-on (L1 API, tested on 7.9)

*[Version française](README.fr.md)*

A display and drag layer on top of Bookmap's trading: a line on the position, TP / SL handles to drag, and moving orders with the mouse. The add-on replaces nothing: entry orders, their size (TCP) and the position remain Bookmap's own.

## Install
1. Bookmap > Settings > Configure add-ons > **Add** > `tradepro.jar`
2. Select **Trade Pro** and tick the checkbox to enable it on the chart
3. The add-on settings panel has three sections, saved with the workspace: **Colors** (Long, Short, Take profit, Stop loss), **Lines** (Solid or Dashed style and a width of 1 to 6 px, for the position, the TP and the SL) and **Labels** (horizontal position of the labels in the area to the right of the timeline: 0 = against the timeline, 100 = right edge, 50 by default)
4. Trading must be enabled in Bookmap's TCP

Unsigned add-ons are blocked on Bookmap Data / dxFeed real-time data:
use delayed data, replay, crypto or a broker connection.

## Usage
- **Open position**: a line at the average price + a "LONG 3 @ price" label with the unrealized PnL. Side, size and average price come from Bookmap's status when the provider sends it; otherwise they are rebuilt from the executions
- **TP / SL handles** to the right of the timeline, above and below the position: drag and release to place a limit order (TP) or a stop order (SL). A handle only covers the part of the position that has no exit order of that type yet (3 contracts if nothing is placed, 2 if a TP of 1 already exists) and disappears once the whole position is covered. During the gesture, the label shows the size, the price, the distance in ticks and the amount relative to the average price
- **Exit orders**: any order that exits the position is shown as TP (limit) or SL (stop), with its distance in ticks and its amount, whether it comes from a handle, the TCP or a Bookmap bracket
- **Drag & drop**: grab any active order line and release it at the new price. **Right-click during the drag** → cancelled, nothing is sent
- **Crosshair** (H + V lines) with the price on the ladder, while the mouse is over the chart

The amount is in dollars when Bookmap provides the instrument's point value; otherwise only ticks are shown.

### Scale in / scale out
When the position grows or shrinks without changing side, the TP / SL placed with the handles take its new size (3 → 5 contracts: the TP and the SL go to 5). Orders placed from the TCP and Bookmap brackets are not touched.

### What the add-on does not do
- **It does not cancel the TP / SL when the position closes or reverses.** They stay in the market: use Bookmap's Execution Pro add-on ("Cancel All Orders on Flat") or cancel them by hand, otherwise they can open a new position
- It does not place entry orders and has no size setting: everything goes through Bookmap's native trading

### Safeguards
- A plain click sends nothing: you must drag at least 4 pixels, and an order released on its own price is not modified
- A TP / SL handle released on the wrong side of the market (the order would fill immediately) places a limit at the best price instead. TP: passive, SELL ASK for a long and BUY BID for a short. SL: immediate exit, SELL BID for a long and BUY ASK for a short
- An existing order moved to the wrong side of the market is rejected, as is any order while the book is not known. During the drag, the preview turns dark red with the reason ("rejected: wrong side of the market", "rejected: no market data"); on release, the message stays 4 seconds on the chart and in the Bookmap log
- If the position closes or reverses while you are dragging a handle, nothing is sent

## Build
JDK 17 or later required; the jar is compiled for Java 17, like the official examples. Two ways, giving the same result (`tradepro.jar` at the project root, classes in `build/classes`):

    .\gradlew.bat jar

(Gradle 9.8.0, downloaded automatically on first run; works with Java 17 to 27), or without Gradle:

    powershell -ExecutionPolicy Bypass -File build.ps1

Both compile against the API of the local installation (`C:\Program Files\Bookmap\lib`) and set up fast reload. If Bookmap is installed elsewhere: `.\gradlew.bat jar -PbookmapLib="D:/Bookmap/lib"` or `build.ps1 -BookmapLib 'D:\Bookmap\lib'`.

### Testing
The add-on is checked by hand in Bookmap, in replay or simulation: place, move, cancel, reload.

### Fast reload (during development)
To avoid renaming the jar on every try:

1. In Bookmap > Configure add-ons > **Add**, choose `build\classes\bm-strategy-package-fs-root.jar` (an empty file: Bookmap then reads the classes directly from `build\classes`)
2. After each change: rebuild, then reload the add-on (or restart Bookmap)

If Bookmap has `tradepro.jar` loaded (locked file), output the jar under another name:

    powershell -ExecutionPolicy Bypass -File build.ps1 -Name tradepro-v2.jar
