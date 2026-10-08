# Trade Pro — Bookmap add-on

Trade Pro adds a position line and draggable take-profit / stop-loss handles to the Bookmap chart, in the area to the right of the timeline. It sits on top of Bookmap's own trading and replaces nothing: you still open positions with Bookmap, at the size set in the TCP.

## What it does

- **Shows your position**: a line at the average price with a label such as `5 LONG  31310.85  -$41.00` (size, side, price, unrealized PnL).
- **TP and SL handles**: two handles appear next to the position. Drag one and release it to place a limit order (TP) or a stop order (SL) for the part of the position that has no exit order yet. While you drag, the label shows the price, the distance in ticks and the amount.
- **Move orders with the mouse**: grab any working order line and release it at the new price. Right-click during the drag to cancel.
- **Scale in / scale out**: when the position grows or shrinks, the TP and SL placed with the handles take its new size.
- **Looks like Bookmap**: labels use Bookmap's own order-label style, and turn blue under the pointer.

Amounts are in dollars when Bookmap knows the instrument's point value; otherwise only ticks are shown.

## Install

1. Download the latest jar from the [releases page](https://github.com/superlokki/tradepro/releases).
2. In Bookmap: Settings > Configure add-ons > **Add**, and pick the jar.
3. Select **Trade Pro** and tick the checkbox to enable it on the chart.

Trading must be enabled in Bookmap's TCP. Tested on Bookmap 7.9.

## Good to know

- **Enable it only when you are flat.** Bookmap does not tell an add-on about a position that is already open, so Trade Pro would show wrong sizes.
- **It does not cancel your TP or SL when the position closes.** Cancel the remaining order yourself, or use Bookmap's Execution Pro add-on ("Cancel All Orders on Flat"), otherwise it can open a new position.
- **A simple click sends nothing.** You have to drag at least a few pixels.
- **A handle released on the wrong side of the market** places an order at the best price instead of being rejected: a passive one for a TP (SELL ASK / BUY BID), an immediate exit for an SL (SELL BID / BUY ASK).
- Unsigned add-ons are blocked on Bookmap Data / dxFeed real-time data. Use delayed data, replay, crypto or a broker connection.

## Settings

The add-on's settings panel lets you change the colours (position, TP, SL, labels), the line style and width, the opacity of the position, TP and SL labels, and where the labels sit in the right-hand area. Settings are saved with the workspace.

## Building from source

See [BUILD.md](BUILD.md).
