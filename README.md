# Trade Pro — Bookmap add-on

Trade Pro adds a position line and draggable take-profit / stop-loss handles to the Bookmap chart, in the area to the right of the timeline. It sits on top of Bookmap's own trading and replaces nothing: you still open positions with Bookmap, at the size set in the TCP.

## What it does

- **Shows your position**: a line at the average price with a label such as `5 LONG  31310.85  -$41.00` (size, side, price, unrealized PnL).
- **TP and SL handles**: two handles sit on the position line, to the left of its label. Drag one and release it to place a limit order (TP) or a stop order (SL) for the part of the position that has no exit order yet. While you drag, the label shows the price and the profit or loss at that price.
- **Break Even (BE) button**: a `BE` button sits to the right of the position label. Click it to place a Stop Loss at the position price for the whole position. If a Stop Loss is already working, even one you placed yourself, it is resized and moved there instead: no second order is created, and from then on it follows the position size like a handle's SL. The button hides once a Stop Loss covers the whole position at the position price. If the market is already past the position price, BE exits at once (see below).
- **Move orders with the mouse**: grab any working order line and release it at the new price. Right-click during the drag to cancel.
- **Scale in / scale out**: when the position grows or shrinks, the TP and SL placed with the handles or set by BE take its new size. When the position closes or reverses, they are cancelled.
- **Several instruments at once**: enable it on each instrument you trade. Each chart has its own position, handles and settings.
- **Looks like Bookmap**: labels have the size and font of Bookmap's order labels, and turn the same translucent blue under the pointer.

Profit and loss is shown in money or in ticks, your choice in the settings. Money needs the instrument's point value: when Bookmap does not know it, ticks are shown.

## Install

1. Download the latest jar from the [releases page](https://github.com/superlokki/tradepro/releases).
2. In Bookmap: Settings > Configure add-ons > **Add**, and pick the jar.
3. Select **Trade Pro** and tick the checkbox to enable it on the chart. Repeat for each instrument you want it on.

Trading must be enabled in Bookmap's TCP. Tested on Bookmap 8.0.

## Good to know

- **Enable it only when you are flat.** Bookmap does not tell an add-on about a position that is already open, so Trade Pro would show wrong sizes.
- **TP and SL are cancelled when the position closes or reverses.**
- **A simple click sends nothing**, except on the BE button. On a handle or an order you have to drag at least a few pixels. A right-click on a Trade Pro label does nothing either: it never reaches Bookmap, so it cannot place an order.
- **A TP or SL released on the wrong side of the market exits at once** instead of being rejected: a limit at the best price, SELL BID for a long, BUY ASK for a short. This works for the handles, for TP / SL orders you drag and for the BE button. A stop order is cancelled first; the limit is sent only once Bookmap confirms the cancel, so a stop filled in the meantime never leads to a second exit.
- Unsigned add-ons are blocked on Bookmap Data / dxFeed real-time data. Use delayed data, replay, crypto or a broker connection.

## Settings

The add-on's settings panel lets you change the colours (position, TP, SL, labels), the line style and width, the opacity of the position, TP and SL labels (the BE button uses the SL one), whether profit and loss is shown in money or in ticks (separately for the position and for TP / SL), and where the labels sit in the right-hand area. Settings are saved with the workspace, separately for each instrument. The "About" section shows the Trade Pro version.

## Building from source

See [BUILD.md](BUILD.md).
