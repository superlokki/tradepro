package com.tradepro;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;

import velox.api.layer1.annotations.*;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.*;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.CanvasMouseEvent.CoordinateRequestType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.messages.indicators.AliasFilter;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.*;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;

/**
 * Couche d'affichage et de drag par-dessus le trading de Bookmap :
 *  - ligne et étiquette de la position, avec son PnL
 *  - poignées TP / SL à tirer pour poser un ordre de sortie
 *  - drag & drop des ordres actifs ; clic droit pendant le drag = abandon
 *  - crosshair avec étiquette de prix
 * Les TP / SL des poignées suivent la taille de la position ; l'add-on ne les annule jamais.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Trade Pro")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@Layer1TradingStrategy
@UnrestrictedData   // requis par Bookmap ; aucune donnée ne sort de Bookmap
public class TradePro implements CustomModule, BboListener, OrdersListener, PositionListener,
        CustomSettingsPanelProvider, ScreenSpacePainterFactory {

    // Priorité maximale : sur nos cibles, le clic nous revient avant Bookmap
    private static final int HIGH_SCORE = MouseModuleScore.MAX.score;
    private static final int GRAB_PX = 6;           // tolérance pour attraper une ligne sur la heatmap
    private static final int LABEL_GRAB_PX = 11;    // à droite de la timeline : hauteur de l'étiquette
    private static final int DRAG_MIN_PX = 4;       // en dessous, c'est un clic, pas un déplacement

    private static final Color BUY = new Color(8, 153, 129);       // #089981
    private static final Color SELL = new Color(242, 54, 69);       // #F23645
    private static final Color CROSS = new Color(149, 152, 161);    // #9598A1
    private static final Color POS = BUY;
    private static final Color POS_SHORT = SELL;
    private static final Color REFUSED = new Color(150, 40, 40);    // ordre refusé
    // Étiquettes au style de Bookmap : fond ardoise, texte blanc
    private static final Color LABEL_BG = new Color(57, 73, 82);
    private static final Color LABEL_BG_HOT = new Color(12, 113, 160);    // survol ou drag

    /** Réglages enregistrés avec le workspace, par instrument. */
    @StrategySettingsVersion(currentVersion = 1, compatibleVersions = {})
    public static class Settings {
        public int posColor = POS.getRGB();             // position LONG
        public int shortColor = POS_SHORT.getRGB();     // position SHORT
        public int tpColor = Handle.TP.color.getRGB();
        public int slColor = Handle.SL.color.getRGB();
        public int labelColor = LABEL_BG.getRGB();          // fond des étiquettes
        public int labelHoverColor = LABEL_BG_HOT.getRGB(); // fond au survol ou pendant un drag
        public boolean posDashed = false, tpDashed = false, slDashed = false;
        public int posWidth = 1, tpWidth = 1, slWidth = 1;                      // en pixels
        public int posOpacity = 100, tpOpacity = 100, slOpacity = 100;          // fond des étiquettes, en %
        public boolean posTicks = false, exitTicks = false;                     // ticks plutôt qu'argent
        public int tagPos = 50;     // position des étiquettes, en % (0 = contre la timeline, 100 = bord droit)
    }

    private volatile Color posColor = POS, shortColor = POS_SHORT, tpColor = Handle.TP.color, slColor = Handle.SL.color;
    private volatile Color labelBg = LABEL_BG, labelHot = LABEL_BG_HOT;

    private Color colorOf(Handle h) { return h == Handle.TP ? tpColor : slColor; }

    private volatile boolean posDashed, tpDashed, slDashed;

    private boolean dashedOf(Handle h) { return h == Handle.TP ? tpDashed : slDashed; }

    private static final int WIDTH_MAX = 6;
    private volatile int posWidth = 1, tpWidth = 1, slWidth = 1;    // en pixels

    private int widthOf(Handle h) { return h == Handle.TP ? tpWidth : slWidth; }

    private static int clampWidth(int w) { return Math.max(1, Math.min(WIDTH_MAX, w)); }

    /** Position horizontale des étiquettes, en %. */
    private volatile int tagPos = 50;

    /** Opacité du fond des étiquettes au repos, en %. */
    private volatile int posOpacity = 100, tpOpacity = 100, slOpacity = 100;

    /** Gain / perte en ticks plutôt qu'en argent. */
    private volatile boolean posTicks, exitTicks;

    private int opacityOf(Handle h) { return h == Handle.TP ? tpOpacity : slOpacity; }

    private static int clampPercent(int v) { return Math.max(0, Math.min(100, v)); }

    /** Fond d'étiquette au repos, à l'opacité donnée. */
    private Color idleFill(int percent) {
        Color c = labelBg;
        return percent >= 100 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * percent / 100f));
    }
    private static final int HANDLE_SPACE_PX = 4;   // entre les poignées et l'étiquette de la position
    private static final int HANDLE_GRAB_PX = 13;   // demi-hauteur de la poignée, plus 1 px
    private static final int NOTICE_MS = 4_000;     // durée d'un message de refus
    private static final long SENT_HIDE_MS = 3_000; // poignée masquée le temps que son ordre apparaisse

    /** Take profit (limite) ou stop loss (stop) : poignée, et rôle d'un ordre de sortie. */
    enum Handle {
        TP(BUY), SL(SELL);
        final Color color;
        Handle(Color c) { color = c; }
        /** Début du clientId des ordres de cette poignée. */
        String prefix() { return "tradepro-" + this + "-"; }
    }

    enum Mode {
        BUY_LMT("BUY LMT", true, false), SELL_LMT("SELL LMT", false, false),
        BUY_STP("BUY STP", true, true), SELL_STP("SELL STP", false, true);
        final String label; final boolean buy, stop;
        Mode(String l, boolean b, boolean s) { label = l; buy = b; stop = s; }
        static Mode of(boolean buy, boolean stop) {
            return stop ? (buy ? BUY_STP : SELL_STP) : (buy ? BUY_LMT : SELL_LMT);
        }
    }

    /** Message affiché quelques secondes sur le chart (ordre refusé). */
    private record Notice(String text, double level, long until) { }

    /** Image d'étiquette prête à dessiner, avec sa taille. */
    private record Img(PreparedImage image, int w, int h) { }

    /** Bords gauche et droit d'une poignée à l'écran, en pixels. */
    private record Span(int left, int right) { }

    // ---------- État ----------
    private String alias;
    private double pips;
    private PriceGrid grid;
    private double multiplier = Double.NaN;     // valeur d'un point pour 1 contrat, NaN si inconnue
    private Api api;
    private volatile boolean running;           // faux après stop() : plus aucun ordre envoyé

    private volatile int bestBid = Integer.MIN_VALUE, bestAsk = Integer.MAX_VALUE;
    private volatile long lastPnlRedraw;
    private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();

    /** Position : statut de Bookmap s'il existe, sinon exécutions. */
    private final PositionTracker tracker = new PositionTracker();
    /** Dernière position traitée, pour repérer un scale in / out. */
    private int lastPosition;
    /** Ordres posés par les poignées : les seuls que l'add-on redimensionne. */
    private final Map<String, Boolean> ownOrders = new ConcurrentHashMap<>();

    private volatile Notice notice;
    /** Dernier envoi de chaque poignée, par Handle.ordinal(). */
    private final long[] sentAt = new long[Handle.values().length];

    /** Une vue par painter : Bookmap en recrée un à chaque rechargement du chart. */
    private final List<View> views = new CopyOnWriteArrayList<>();

    // ======================================================================
    //  Cycle de vie
    // ======================================================================
    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState state) {
        this.alias = alias;
        this.pips = info.pips;
        this.grid = new PriceGrid(info.pips);
        this.api = api;
        orders.clear();
        tracker.reset();
        lastPosition = 0;
        ownOrders.clear();
        bestBid = Integer.MIN_VALUE;
        bestAsk = Integer.MAX_VALUE;
        notice = null;
        Settings st = api.getSettings(Settings.class);
        posColor = new Color(st.posColor);
        shortColor = new Color(st.shortColor);
        boolean known = !Double.isNaN(info.multiplier) && !Double.isInfinite(info.multiplier) && info.multiplier > 0;
        multiplier = known ? info.multiplier : Double.NaN;
        tpColor = new Color(st.tpColor);
        slColor = new Color(st.slColor);
        labelBg = new Color(st.labelColor);
        labelHot = new Color(st.labelHoverColor);
        posDashed = st.posDashed;
        tpDashed = st.tpDashed;
        slDashed = st.slDashed;
        posWidth = clampWidth(st.posWidth);
        tpWidth = clampWidth(st.tpWidth);
        slWidth = clampWidth(st.slWidth);
        tagPos = Math.max(0, Math.min(100, st.tagPos));
        posOpacity = clampPercent(st.posOpacity);
        tpOpacity = clampPercent(st.tpOpacity);
        slOpacity = clampPercent(st.slOpacity);
        posTicks = st.posTicks;
        exitTicks = st.exitTicks;
        running = true;
        api.sendUserMessage(painterMessage(true));
    }

    @Override
    public void stop() {
        running = false;
        api.sendUserMessage(painterMessage(false));
    }

    /**
     * Ajout ou retrait du painter de cet instrument. Une instance par instrument : sans nom propre à l'instrument,
     * la dernière activée remplaçait le painter des autres, qui ne dessinaient plus rien.
     */
    private Layer1ApiUserMessageModifyScreenSpacePainter painterMessage(boolean add) {
        String own = alias;
        Layer1ApiUserMessageModifyScreenSpacePainter.Builder b = Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(TradePro.class, "Trade Pro")
                .setAliasFilter(new AliasFilter() {
                    @Override public boolean isDisplayedForAlias(String a) { return own.equals(a); }
                })
                .setIsAdd(add);
        if (add) b.setScreenSpacePainterFactory(this);
        Layer1ApiUserMessageModifyScreenSpacePainter msg = b.build();
        msg.applyNameModifier(own);
        return msg;
    }

    @Override
    public ScreenSpacePainter createScreenSpacePainter(String name, String indicatorAlias,
                                                      ScreenSpaceCanvasFactory factory) {
        if (!alias.equals(indicatorAlias)) {
            return new ScreenSpacePainterAdapter() { };
        }
        View v = new View(factory);
        views.add(v);
        return v.painter;
    }

    private void redrawAll() { views.forEach(View::redraw); }

    /** Message temporaire sur le chart, recopié dans le log. */
    private void showNotice(String text, double level) {
        Log.warn("Trade Pro: " + text + " @ " + fmt(level));
        notice = new Notice(text, level, System.currentTimeMillis() + NOTICE_MS);
        redrawAll();
        javax.swing.Timer t = new javax.swing.Timer(NOTICE_MS + 50, e -> redrawAll());
        t.setRepeats(false);
        t.start();
    }

    // ======================================================================
    //  Données reçues de Bookmap : carnet, ordres, position
    // ======================================================================
    @Override
    public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
        boolean moved = bidPrice != bestBid || askPrice != bestAsk;
        bestBid = bidPrice;   // en niveaux de prix
        bestAsk = askPrice;
        // PnL de la position : 5 redessins par seconde au plus
        long now = System.currentTimeMillis();
        if (moved && position() != 0 && now - lastPnlRedraw >= 200) {
            lastPnlRedraw = now;
            redrawAll();
        }
    }

    @Override
    public void onOrderUpdated(OrderInfoUpdate u) {
        if (!alias.equals(u.instrumentAlias)) return;
        if (u.status != null && u.status.isActive()) {
            orders.put(u.orderId, u);
            if (isHandleClientId(u.clientId)) ownOrders.put(u.orderId, Boolean.TRUE);
        } else {
            orders.remove(u.orderId);
            ownOrders.remove(u.orderId);
        }
        tracker.onOrder(u.orderId, u.isBuy);      // le sens de l'ordre signe ses exécutions
        positionMayHaveChanged();
        redrawAll();
    }

    @Override
    public void onOrderExecuted(ExecutionInfo ex) {
        // Sans executionId, une clé maison évite de recompter une exécution rejouée
        String key = ex.executionId != null && !ex.executionId.isEmpty() ? ex.executionId
                : ex.orderId + "|" + ex.time + "|" + ex.price + "|" + ex.size;
        tracker.onExecution(ex.orderId, key, toRealPrice(ex.price), ex.size);
        positionMayHaveChanged();
        redrawAll();
    }

    @Override
    public void onPositionUpdate(StatusInfo st) {
        if (!alias.equals(st.instrumentAlias)) return;
        double avg = st.averagePrice > 0 ? toRealPrice(st.averagePrice) : Double.NaN;
        tracker.onStatus(st.position, avg);
        positionMayHaveChanged();
        redrawAll();
    }

    private static boolean isHandleClientId(String clientId) {
        if (clientId == null) return false;
        for (Handle h : Handle.values()) if (clientId.startsWith(h.prefix())) return true;
        return false;
    }

    /** Scale in / out : les TP / SL des poignées prennent la nouvelle taille. Rien à la fermeture ni au retournement. */
    private synchronized void positionMayHaveChanged() {
        int now = position(), before = lastPosition;
        if (now == before) return;
        lastPosition = now;
        if (!running || now == 0 || before == 0 || (now > 0) != (before > 0)) return;
        int size = Math.abs(now);
        for (String orderId : ownOrders.keySet()) {
            OrderInfo o = orders.get(orderId);
            if (o == null || roleOf(o, now) == null || o.unfilled == size) continue;
            api.updateOrder(new OrderResizeParameters(orderId, size));
            Log.info("Trade Pro: " + roleOf(o, now) + " " + orderId + " resized " + o.unfilled + " -> " + size);
        }
    }

    /** Prix reçu en prix réel ou en niveaux selon le fournisseur : on garde le plus proche du marché. */
    private double toRealPrice(double p) {
        if (!bboKnown()) return p;
        double bid = bestBid * pips;
        return Math.abs(p * pips - bid) < Math.abs(p - bid) ? p * pips : p;
    }

    private int position() { return tracker.position(); }

    /** Niveau (prix / pips) de la position, NaN si à plat ou si le prix d'entrée est inconnu. */
    private double positionLevel() {
        double avg = tracker.averagePrice();
        return Double.isNaN(avg) ? Double.NaN : grid.level(avg);
    }

    /** Ordre de sortie d'une poignée : vente pour un long, achat pour un short. */
    private static Mode exitMode(Handle h, int position) {
        return Mode.of(position < 0, h == Handle.SL);
    }

    /** TP pour une limite de sortie, SL pour un stop de sortie, null sinon. */
    private static Handle roleOf(OrderInfo o, int position) {
        if (position == 0 || o.isBuy != (position < 0)) return null;
        if (o.type == OrderType.LMT) return Handle.TP;
        if (o.type == OrderType.STP || o.type == OrderType.STP_LMT) return Handle.SL;
        return null;
    }

    /** Contrats que cette poignée peut encore couvrir ; 0 = poignée masquée. */
    private int remaining(Handle h, int position) {
        if (System.currentTimeMillis() - sentAt[h.ordinal()] < SENT_HIDE_MS) return 0;   // ordre envoyé, pas encore affiché
        int covered = 0;
        for (OrderInfo o : orders.values()) if (roleOf(o, position) == h) covered += o.unfilled;
        return Math.max(0, Math.abs(position) - covered);
    }

    // ======================================================================
    //  Vue : canvas + formes + état souris d'un painter
    // ======================================================================
    private final class View {
    private final ScreenSpaceCanvas heat, ladder;
    private final List<CanvasShape> heatShapes = new ArrayList<>();
    private final List<CanvasShape> ladderShapes = new ArrayList<>();
    private final List<CanvasShape> tags = new ArrayList<>();       // dessinées en dernier, au-dessus des lignes
    /** Étiquettes placées : {niveau, x gauche, x droite, hauteur}, pour éviter les chevauchements. */
    private final List<double[]> placed = new ArrayList<>();
    /** Cache d'images : la souris redessine à chaque mouvement. */
    private final Map<String, PreparedImage> lineCache = new HashMap<>();
    private final Map<String, Img> labelCache = new HashMap<>();
    private boolean disposed;

    private volatile int heatW, heatH, ladderW;
    private volatile long priceH = 1;

    private volatile boolean mouseIn;
    private volatile int mouseX;
    private volatile double mouseLevel;
    private volatile boolean mouseOnLadder;
    /** Bords des poignées au dernier dessin ; table figée, remplacée d'un bloc. */
    private volatile Map<Handle, Span> handleSpans = Map.of();

    private volatile Handle dragHandle;
    private volatile String dragId;
    private volatile double dragLevel;
    private volatile double pressLevel;
    private volatile int pressSign;             // sens de la position au clic : +1 long, -1 short
    private volatile boolean dragMoved;         // vrai après DRAG_MIN_PX de mouvement

    final ScreenSpacePainter painter = new ScreenSpacePainterAdapter() {
        // Changements reçus par lot : un seul redessin dans onMoveEnd
        private boolean dirty;

        @Override public void onHeatmapFullPixelsWidth(int w) { heatW = w; dirty = true; }
        @Override public void onHeatmapPixelsHeight(int h) { heatH = h; dirty = true; }
        @Override public void onHeatmapPriceHeight(long p) { priceH = Math.max(1, p); dirty = true; }
        @Override public void onRightOfTimelineWidth(int w) { ladderW = w; dirty = true; }
        @Override public void onMoveEnd() {
            if (!dirty) return;
            dirty = false;
            redraw();
        }
        @Override public void dispose() { View.this.dispose(); }
    };

    View(ScreenSpaceCanvasFactory factory) {
        heat = factory.createCanvas(ScreenSpaceCanvasType.HEATMAP);
        ladder = factory.createCanvas(ScreenSpaceCanvasType.RIGHT_OF_TIMELINE);
        heat.addMouseListener(new Mouse(false));
        ladder.addMouseListener(new Mouse(true));
    }

    private synchronized void dispose() {
        if (disposed) return;
        disposed = true;
        cancelDrag();
        views.remove(this);
        heatShapes.clear();
        ladderShapes.clear();
        lineCache.clear();
        labelCache.clear();
        heat.dispose();
        ladder.dispose();
    }

    private void cancelDrag() {
        dragHandle = null;
        dragId = null;
        dragMoved = false;
    }

    // ======================================================================
    //  Souris
    // ======================================================================
    private final class Mouse implements CanvasMouseListener {
        /** Vrai pour la zone à droite de la timeline. */
        private final boolean onLadder;
        Mouse(boolean onLadder) { this.onLadder = onLadder; }

        private void track(CanvasMouseEvent e) {
            mouseOnLadder = onLadder;
            View.this.track(e);
        }

        private Handle handleHere() { return onLadder ? handleNear(mouseLevel) : null; }

        @Override
        public int getEventScore(CanvasMouseEvent e) {
            MouseEvent src = e.sourceEvent;
            int id = src.getID();
            if (id == MouseEvent.MOUSE_WHEEL) return MouseModuleScore.NONE.score;   // le zoom reste à Bookmap
            if (dragId != null || dragHandle != null) return HIGH_SCORE;
            if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_ENTERED
                    || id == MouseEvent.MOUSE_EXITED) {
                track(e);
                return MouseModuleScore.MIN.score;       // observer sans prendre la main
            }
            // Le clic droit reste à Bookmap : on ne prend que le clic gauche sur une cible
            if (!SwingUtilities.isLeftMouseButton(src)) return MouseModuleScore.NONE.score;
            track(e);
            return (handleHere() != null || orderNear(mouseLevel) != null)
                    ? HIGH_SCORE : MouseModuleScore.NONE.score;
        }

        @Override public void mouseMoved(CanvasMouseEvent e) {
            track(e);
            Component c = e.sourceEvent.getComponent();
            if (c != null) {
                boolean grab = handleHere() != null || orderNear(mouseLevel) != null;
                c.setCursor(Cursor.getPredefinedCursor(grab ? Cursor.N_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
            }
            redraw();
        }

        @Override public void mouseEntered(CanvasMouseEvent e) { mouseIn = true; track(e); redraw(); }

        @Override public void mouseExited(CanvasMouseEvent e) {
            mouseIn = false;
            Component c = e.sourceEvent.getComponent();
            if (c != null && dragId == null && dragHandle == null) c.setCursor(Cursor.getDefaultCursor());
            redraw();
        }

        @Override public void mousePressed(CanvasMouseEvent e) {
            track(e);
            MouseEvent src = e.sourceEvent;
            if (dragId != null || dragHandle != null) {
                // autre bouton pendant un drag : abandon
                if (!SwingUtilities.isLeftMouseButton(src)) { cancelDrag(); redraw(); }
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(src)) return;
            pressLevel = mouseLevel;
            dragLevel = mouseLevel;
            dragMoved = false;
            pressSign = Integer.signum(position());
            // Un ordre existant passe avant une poignée
            OrderInfo o = orderNear(mouseLevel);
            if (o != null) {
                dragId = o.orderId;
            } else {
                dragHandle = handleHere();
            }
            redraw();
        }

        @Override public void mouseDragged(CanvasMouseEvent e) {
            track(e);
            if (dragId == null && dragHandle == null) return;
            dragLevel = mouseLevel;
            if (Math.abs(mouseLevel - pressLevel) * pxPerLevel() >= DRAG_MIN_PX) dragMoved = true;
            redraw();
        }

        @Override public void mouseReleased(CanvasMouseEvent e) {
            track(e);
            Handle hd = dragHandle;
            String id = dragId;
            boolean moved = dragMoved;
            cancelDrag();
            if (!SwingUtilities.isLeftMouseButton(e.sourceEvent) || !moved || disposed || !running) {
                redraw();       // clic simple, drag abandonné ou add-on arrêté
                return;
            }
            int target = (int) Math.round(mouseLevel);      // prix du relâchement
            if (hd != null) placeExit(hd, target, pressSign);
            OrderInfo o = id == null ? null : orders.get(id);
            if (o != null && target != Math.round(orderLevel(o))) {     // même niveau : rien à envoyer
                String refusal = refusal(modeOf(o), target);
                if (refusal == null) moveOrder(o, target);
                else showNotice("Move rejected: " + refusal, target);
            }
            redraw();
        }
    }

    private void track(CanvasMouseEvent e) {
        mouseIn = true;
        mouseX = e.getX(new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, 0, 0),
                CoordinateRequestType.PIXELS).compose().pixelsX;
        mouseLevel = e.getY(new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, 0),
                CoordinateRequestType.DATA).compose().dataY;
    }

    private double pxPerLevel() { return heatH / (double) priceH; }

    /** Ordre déplaçable le plus proche. À droite de la timeline, la tolérance couvre l'étiquette. */
    private OrderInfo orderNear(double level) {
        OrderInfo best = null;
        double bestDist = mouseOnLadder ? LABEL_GRAB_PX : GRAB_PX;
        for (OrderInfo o : orders.values()) {
            if (modeOf(o) == null) continue;
            double d = Math.abs(orderLevel(o) - level) * pxPerLevel();
            if (d <= bestDist) { bestDist = d; best = o; }
        }
        return best;
    }

    /** Centre d'une étiquette de largeur w, gardée dans la zone à droite de la timeline. */
    private int tagCenter(int w) {
        int c = ladderW * tagPos / 100;
        return Math.max(w / 2, Math.min(ladderW - w / 2, c));
    }

    /** Poignée sous le pointeur ; la marge couvre l'espace entre deux poignées. */
    private Handle handleNear(double level) {
        double pos = positionLevel();
        if (Double.isNaN(pos) || Math.abs(pos - level) * pxPerLevel() > HANDLE_GRAB_PX) return null;
        Map<Handle, Span> spans = handleSpans;
        int x = mouseX, slack = HANDLE_SPACE_PX / 2;
        for (Handle h : Handle.values()) {
            if (remaining(h, position()) == 0) continue;     // déjà couverte
            Span span = spans.get(h);
            if (span != null && x >= span.left() - slack && x < span.right() + slack) return h;
        }
        return null;
    }

    // ======================================================================
    //  Dessin
    // ======================================================================
    private synchronized void redraw() {
        ScreenSpaceCanvas h = heat, l = ladder;
        if (disposed || heatW <= 0 || heatH <= 0) return;

        heatShapes.forEach(h::removeShape);
        ladderShapes.forEach(l::removeShape);
        heatShapes.clear();
        ladderShapes.clear();
        tags.clear();
        placed.clear();

        // 0) Position ouverte + poignées TP / SL
        int position = position();
        double pos = positionLevel();
        boolean moving = dragMoved;
        if (Double.isNaN(pos)) handleSpans = Map.of();
        if (!Double.isNaN(pos)) {
            int size = Math.abs(position);
            Color pc = position > 0 ? posColor : shortColor;
            int pw = posWidth;
            hLine(pos, pc, posDashed, pw);
            if (ladderW > 0) {
                add(ladderShapes, l, icon(line(ladderW, pw, pc, false), px(0), dataY(pos, -pw / 2 - (pw % 2)),
                        px(ladderW), dataY(pos, pw / 2)));
            }
            // PnL au prix de sortie : bid pour un long, ask pour un short
            String pnl = bboKnown() ? "  " + pnl(position > 0 ? bestBid : bestAsk, pos, size, position, posTicks) : "";
            Img posImg = labelImg(size + (position > 0 ? " LONG" : " SHORT") + "  " + fmt(pos) + pnl,
                    idleFill(posOpacity), false);
            // Poignées à gauche de l'étiquette de la position, décalée à droite si la place manque
            Map<Handle, Span> spans = new EnumMap<>(Handle.class);
            int handlesW = 0;
            for (Handle hd : Handle.values()) {
                if (remaining(hd, position) > 0) handlesW += labelImg(hd.toString(), labelBg, true).w() + HANDLE_SPACE_PX;
            }
            int posX = Math.max(handlesW, tagCenter(posImg.w()) - posImg.w() / 2);
            int nextX = posX - handlesW;
            for (Handle hd : Handle.values()) {
                if (remaining(hd, position) == 0) continue;
                int w = labelImg(hd.toString(), labelBg, true).w();
                spans.put(hd, new Span(nextX, nextX + w));
                nextX += w + HANDLE_SPACE_PX;
            }
            handleSpans = Map.copyOf(spans);
            place(pos, posImg, posX);
            // Poignée survolée : fond opaque
            Handle hover = dragHandle != null ? dragHandle
                    : mouseIn && mouseOnLadder && dragId == null && orderNear(mouseLevel) == null
                            ? handleNear(mouseLevel) : null;
            for (Handle hd : Handle.values()) {
                int left = remaining(hd, position);
                if (left == 0) continue;        // position déjà couverte
                if (hd == dragHandle && moving) {
                    int lvl = (int) Math.round(dragLevel);
                    Mode em = exitMode(hd, position);
                    String refusal = refusal(em, lvl);
                    if (refusal != null && bboKnown()) {
                        // Mauvais côté : limite au meilleur prix (insideLevel)
                        int inside = insideLevel(hd, em.buy);
                        hLine(lvl, colorOf(hd), true, Math.max(2, widthOf(hd)));
                        tag(lvl, left + " " + insideName(hd, em.buy) + "  " + fmt(inside) + "  "
                                + distance(inside, pos, left, position), false, labelHot);
                    } else {
                        Color c = refusal == null ? colorOf(hd) : REFUSED;
                        hLine(lvl, c, true, Math.max(2, widthOf(hd)));
                        tag(lvl, left + " " + hd + "  " + fmt(lvl) + "  " + distance(lvl, pos, left, position)
                                + (refusal == null ? "" : "  — rejected: " + refusal), false,
                                refusal == null ? labelHot : REFUSED);
                    }
                } else {
                    Color fill = labelHot;
                    if (hd != hover) {      // au repos : opacité du rôle
                        fill = idleFill(opacityOf(hd));
                    }
                    // couverte entre-temps : rien à afficher
                    Span span = spans.get(hd);
                    if (span != null) place(pos, labelImg(hd.toString(), fill, true), span.left());
                }
            }
        }

        // 1) Ordres actifs : une sortie s'affiche en TP ou SL ; l'ordre survolé est mis en avant
        OrderInfo hoverOrder = mouseIn && dragId == null && dragHandle == null ? orderNear(mouseLevel) : null;
        for (OrderInfo o : orders.values()) {
            if (Double.isNaN(orderLevel(o))) continue;      // ordre au marché : pas de ligne
            boolean dragging = moving && o.orderId.equals(dragId);
            boolean hot = o.orderId.equals(dragId) || (hoverOrder != null && o.orderId.equals(hoverOrder.orderId));
            double lvl = dragging ? Math.round(dragLevel) : orderLevel(o);
            boolean stop = o.type == OrderType.STP || o.type == OrderType.STP_LMT;
            String refusal = dragging ? refusal(modeOf(o), (int) lvl) : null;
            Handle role = Double.isNaN(pos) ? null : roleOf(o, position);
            Color c = refusal != null ? REFUSED : role != null ? colorOf(role) : o.isBuy ? BUY : SELL;
            String name = role != null ? role.toString() : (o.isBuy ? "BUY " : "SELL ") + o.type;
            int w = role != null ? widthOf(role) : 1;
            hLine(lvl, c, role != null ? dashedOf(role) : stop, hot ? w + 1 : w);
            String dist = role != null ? "  " + distance(lvl, pos, o.unfilled, position) : "";
            tag(lvl, o.unfilled + " " + name + "  " + fmt(lvl) + dist
                    + (refusal == null ? "" : "  — rejected: " + refusal), false,
                    refusal != null ? REFUSED : hot ? labelHot : role != null ? idleFill(opacityOf(role)) : labelBg);
        }

        // 2) Message de refus encore affiché
        Notice n = notice;
        if (n != null && System.currentTimeMillis() < n.until()) tag(n.level(), n.text(), false, REFUSED);

        // 3) Crosshair
        if (mouseIn && dragId == null && dragHandle == null) {
            hLine(mouseLevel, CROSS, true, 1);
            if (!mouseOnLadder) {
                add(heatShapes, h, icon(line(1, heatH, CROSS, false),
                        px(mouseX), pxY(0), px(mouseX + 1), pxY(heatH)));
            }
            priceTag(Math.round(mouseLevel), fmt(Math.round(mouseLevel)), new Color(70, 70, 70));
        }

        for (CanvasShape t : tags) add(ladderShapes, l, t);
    }

    private void hLine(double level, Color c, boolean dashed, int thick) {
        add(heatShapes, heat, icon(line(heatW, thick, c, dashed), px(0), dataY(level, -thick / 2 - (thick % 2)),
                px(heatW), dataY(level, thick / 2)));
    }

    /** Étiquette de prix du crosshair, calée contre la timeline. */
    private void priceTag(double level, String text, Color bg) {
        Img img = labelImg(text, bg, false);
        add(ladderShapes, ladder, icon(img.image(), px(0), dataY(level, -img.h() / 2),
                px(img.w()), dataY(level, img.h() - img.h() / 2)));
    }

    /**
     * Étiquette à droite de la timeline, décalée à droite si elle en chevauche une autre.
     * @param big  plus large, pour les poignées
     * @param fill fond de l'étiquette
     */
    private void tag(double level, String text, boolean big, Color fill) {
        Img img = labelImg(text, fill, big);
        int x = tagCenter(img.w()) - img.w() / 2;
        boolean shifted = true;
        while (shifted) {
            shifted = false;
            for (double[] p : placed) {
                boolean sameRow = Math.abs(p[0] - level) * pxPerLevel() < Math.max(p[3], img.h());
                if (sameRow && x < p[2] && x + img.w() > p[1]) {
                    x = (int) p[2] + 4;
                    shifted = true;
                }
            }
        }
        place(level, img, x);
    }

    /** Pose une étiquette à cet endroit et la note dans placed. */
    private void place(double level, Img img, int x) {
        placed.add(new double[] { level, x, x + img.w(), img.h() });
        tags.add(icon(img.image(), px(x), dataY(level, -img.h() / 2),
                px(x + img.w()), dataY(level, img.h() - img.h() / 2)));
    }

    private PreparedImage line(int w, int h, Color c, boolean dashed) {
        if (lineCache.size() > 64) lineCache.clear();
        return lineCache.computeIfAbsent(w + "|" + h + "|" + c.getRGB() + "|" + dashed,
                k -> new PreparedImage(dashed ? dashed(w, h, c) : solid(w, h, c)));
    }

    private Img labelImg(String text, Color fill, boolean big) {
        if (labelCache.size() > 256) labelCache.clear();
        String key = fill.getRGB() + "|" + big + "|" + text;
        return labelCache.computeIfAbsent(key, k -> {
            BufferedImage img = label(text, fill, big);
            return new Img(new PreparedImage(img), img.getWidth(), img.getHeight());
        });
    }

    } // fin View

    // ======================================================================
    //  Ordres
    // ======================================================================
    /**
     * Pose l'ordre de sortie d'une poignée pour la partie non couverte de la position.
     * Du mauvais côté du marché, c'est une limite au meilleur prix.
     * @param pressSign sens de la position au clic ; s'il a changé, rien n'est envoyé
     */
    private void placeExit(Handle h, int level, int pressSign) {
        if (!running) return;
        int pos = position();       // lu une fois : taille et sens cohérents
        if (pos == 0 || Integer.signum(pos) != pressSign) {
            showNotice(h + " cancelled: position changed during the drag", level);
            return;
        }
        Mode m = exitMode(h, pos);
        if (!bboKnown()) {
            showNotice(h + " rejected: no market data", level);
            return;
        }
        // Mauvais côté du marché : limite au meilleur prix (insideLevel)
        boolean inside = refusal(m, level) != null;
        if (inside) {
            m = Mode.of(m.buy, false);
            level = insideLevel(h, m.buy);
        }
        int size = remaining(h, pos);
        if (size == 0) {
            showNotice(h + " already covers the whole position", level);
            return;
        }
        double price = grid.price(level);
        SimpleOrderSendParametersBuilder b = new SimpleOrderSendParametersBuilder(alias, m.buy, size);
        if (m.stop) b.setStopPrice(price); else b.setLimitPrice(price);
        b.setClientId(h.prefix() + UUID.randomUUID());      // pour reconnaître l'ordre ensuite
        sentAt[h.ordinal()] = System.currentTimeMillis();
        api.sendOrder(b.build());
        String kind = inside ? insideName(h, m.buy) : m.label;
        Log.info("Trade Pro: " + h + " " + kind + " x" + size + " @ " + fmt(level) + " sent");
    }

    /**
     * Prix de la limite posée du mauvais côté du marché :
     *  - TP passif : SELL ASK pour un long, BUY BID pour un short
     *  - SL agressif : SELL BID pour un long, BUY ASK pour un short
     */
    private int insideLevel(Handle h, boolean buy) {
        boolean atBid = (h == Handle.TP) == buy;
        return atBid ? bestBid : bestAsk;
    }

    private static String insideName(Handle h, boolean buy) {
        boolean atBid = (h == Handle.TP) == buy;
        return (buy ? "BUY " : "SELL ") + (atBid ? "BID" : "ASK");
    }

    /** Vrai si le carnet est connu (un 0 / 0 d'attente ne compte pas). */
    private boolean bboKnown() {
        int bid = bestBid, ask = bestAsk;
        return bid != Integer.MIN_VALUE && ask != Integer.MAX_VALUE && ask > bid;
    }

    /** Raison de refuser un ordre à ce niveau (exécution immédiate, carnet inconnu), null sinon. */
    private String refusal(Mode m, int level) {
        if (m == null) return "order cannot be moved";
        if (!bboKnown()) return "no market data";
        boolean rests = m.stop
                ? (m.buy ? level > bestAsk : level < bestBid)
                : (m.buy ? level < bestAsk : level > bestBid);
        return rests ? null : "wrong side of the market";
    }

    /** Sens et type d'un ordre existant, null s'il n'est pas déplaçable (ni limite, ni stop). */
    private static Mode modeOf(OrderInfo o) {
        if (o.type == OrderType.LMT) return Mode.of(o.isBuy, false);
        if (o.type == OrderType.STP || o.type == OrderType.STP_LMT) return Mode.of(o.isBuy, true);
        return null;
    }

    private void moveOrder(OrderInfo o, int level) {
        if (!running) return;
        double p = grid.price(level);
        if (o.type == null) return;
        switch (o.type) {
            case STP_LMT -> {
                double shift = p - o.stopPrice;         // la limite garde son écart au stop
                api.updateOrder(new OrderMoveParameters(o.orderId, p, o.limitPrice + shift));
            }
            case STP -> api.updateOrder(new OrderMoveParameters(o.orderId, p, Double.NaN));
            case LMT -> api.updateOrder(new OrderMoveParameters(o.orderId, Double.NaN, p));
            default -> { return; }
        }
        Log.info("Trade Pro: " + o.orderId + " moved " + fmt(orderLevel(o)) + " -> " + fmt(level));
    }

    private double orderLevel(OrderInfo o) {
        boolean stop = o.type == OrderType.STP || o.type == OrderType.STP_LMT;
        return grid.level(stop ? o.stopPrice : o.limitPrice);
    }

    /** Gain / perte d'un TP / SL, dans l'unité réglée pour les TP / SL. */
    private String distance(double level, double posLevel, int size, int position) {
        return pnl(level, posLevel, size, position, exitTicks);
    }

    /** Gain / perte si la position sort à ce niveau, en argent ou en ticks (ticks si la valeur du point est inconnue). */
    private String pnl(double level, double posLevel, int size, int position, boolean inTicks) {
        double ticks = (level - posLevel) * (position > 0 ? 1 : -1);
        if (inTicks || Double.isNaN(multiplier)) return String.format("%+.0f t", ticks);
        double pnl = ticks * pips * multiplier * size;
        return (pnl < 0 ? "-$" : "+$") + String.format("%,.2f", Math.abs(pnl));
    }

    private String fmt(double level) { return grid.format(level); }

    private static void add(List<CanvasShape> list, ScreenSpaceCanvas canvas, CanvasShape s) {
        canvas.addShape(s);
        list.add(s);
    }

    private static CanvasIcon icon(PreparedImage img, HorizontalCoordinate x1, VerticalCoordinate y1,
                                   HorizontalCoordinate x2, VerticalCoordinate y2) {
        return new CanvasIcon(img, x1, y1, x2, y2);
    }

    private static CompositeHorizontalCoordinate px(int x) {
        return new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, x, 0);
    }

    private static CompositeVerticalCoordinate pxY(int y) {
        return new CompositeVerticalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, y, 0);
    }

    private static CompositeVerticalCoordinate dataY(double level, int pixelOffset) {
        return new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, pixelOffset, level);
    }

    // ---------- Images ----------
    private static BufferedImage solid(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.dispose();
        return img;
    }

    private static BufferedImage dashed(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        for (int x = 0; x < w; x += 10) g.fillRect(x, 0, 6, h);
        g.dispose();
        return img;
    }

    /** Version de l'add-on, écrite par le build dans version.txt. */
    private static final String VERSION = readVersion();

    private static String readVersion() {
        try (InputStream in = TradePro.class.getResourceAsStream("version.txt")) {
            if (in != null) return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            // version inconnue
        }
        return "?";
    }

    private static final Font FONT = uiFont(12f);

    /** Police de Bookmap (Roboto), sinon police système. */
    private static Font uiFont(float size) {
        try (InputStream in = Log.class.getResourceAsStream("/resources/fonts/roboto/RobotoBm-Medium.ttf")) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(size);
        } catch (Exception e) {
            // police système
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, (int) size);
    }

    /** Étiquette au style de Bookmap : rectangle arrondi, texte blanc. */
    private static BufferedImage label(String text, Color fill, boolean big) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm = pg.getFontMetrics(FONT);
        pg.dispose();
        // Poignées à la même hauteur : elles sont sur la ligne de la position
        int padX = big ? 12 : 7, padY = 2;
        int w = fm.stringWidth(text) + 2 * padX, h = fm.getHeight() + 2 * padY;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(fill);
        g.fillRoundRect(0, 0, w, h, 6, 6);
        // fond translucide : texte atténué aussi
        int ink = fill.getAlpha() < 255 ? Math.min(255, fill.getAlpha() + 80) : 255;
        g.setColor(new Color(255, 255, 255, ink));
        g.setFont(FONT);
        g.drawString(text, padX, padY + fm.getAscent());
        g.dispose();
        return img;
    }

    // ======================================================================
    //  Panneau de réglages
    // ======================================================================
    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        JPanel colors = verticalBox();
        colors.add(colorRow(posColor, POS, "Long", c -> { posColor = c; settingsChanged(); }));
        colors.add(gap());
        colors.add(colorRow(shortColor, POS_SHORT, "Short", c -> { shortColor = c; settingsChanged(); }));
        colors.add(gap());
        colors.add(colorRow(tpColor, Handle.TP.color, "Take profit", c -> { tpColor = c; settingsChanged(); }));
        colors.add(gap());
        colors.add(colorRow(slColor, Handle.SL.color, "Stop loss", c -> { slColor = c; settingsChanged(); }));
        colors.add(gap());
        colors.add(colorRow(labelBg, LABEL_BG, "Label", c -> { labelBg = c; settingsChanged(); }));
        colors.add(gap());
        colors.add(colorRow(labelHot, LABEL_BG_HOT, "Label hover", c -> { labelHot = c; settingsChanged(); }));

        JPanel lines = new JPanel(new GridBagLayout());
        lines.setOpaque(false);
        lineHeader(lines);
        lineRow(lines, 1, "Position", styleBox(posDashed, d -> posDashed = d),
                widthBox(posWidth, w -> posWidth = w), opacityBox(posOpacity, v -> posOpacity = v));
        lineRow(lines, 2, "Take profit", styleBox(tpDashed, d -> tpDashed = d),
                widthBox(tpWidth, w -> tpWidth = w), opacityBox(tpOpacity, v -> tpOpacity = v));
        lineRow(lines, 3, "Stop loss", styleBox(slDashed, d -> slDashed = d),
                widthBox(slWidth, w -> slWidth = w), opacityBox(slOpacity, v -> slOpacity = v));

        String caption = "Left to right: ";
        JLabel value = new JLabel(caption + tagPos);
        JSlider slider = new JSlider(0, 100, tagPos);
        slider.setToolTipText(String.valueOf(tagPos));
        slider.addChangeListener(e -> {
            tagPos = slider.getValue();
            value.setText(caption + tagPos);                    // visible pendant le glissement
            slider.setToolTipText(String.valueOf(tagPos));
            if (slider.getValueIsAdjusting()) redrawAll();      // aperçu ; enregistré au relâchement
            else settingsChanged();
        });
        JPanel tagsPanel = pair(value, slider);

        JPanel pnlPanel = verticalBox();
        pnlPanel.add(pair(new JLabel("Position"), unitBox(posTicks, t -> posTicks = t)));
        pnlPanel.add(gap());
        pnlPanel.add(pair(new JLabel("Take profit / Stop loss"), unitBox(exitTicks, t -> exitTicks = t)));

        // L'API ne donne pas la position déjà ouverte à l'activation
        JPanel warning = verticalBox();
        warning.add(new FittingLabel("Enable Trade Pro only when you are flat.", true));
        warning.add(gap());
        warning.add(new FittingLabel(
                "It cannot see a position that was opened before it was enabled.", false));

        JPanel about = verticalBox();
        about.add(new FittingLabel("Trade Pro " + VERSION, false));
        return new StrategyPanel[] {
            section("Important", warning, 8, TEXT_INDENT), section("Colors", colors, 4, 0),
            section("Lines and labels", lines, 4, TEXT_INDENT),
            section("Profit and loss value", pnlPanel, 4, TEXT_INDENT),
            section("Label position", tagsPanel, 4, TEXT_INDENT),
            section("About", about, 4, TEXT_INDENT) };
    }

    /** Retrait du libellé des couleurs de Bookmap, repris par les autres sections. */
    private static final int TEXT_INDENT = 10;

    private static JPanel verticalBox() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    /** Espace vertical aligné à gauche, pour que BoxLayout n'étire pas la ligne. */
    private static Component gap() {
        JComponent gap = (JComponent) Box.createVerticalStrut(6);
        gap.setAlignmentX(Component.LEFT_ALIGNMENT);
        return gap;
    }

    /** Libellé à gauche, contrôle à droite à sa largeur. */
    private static JPanel pair(JComponent label, JComponent control) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.weightx = 1;
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.insets = new Insets(0, 0, 0, 8);
        row.add(label, g);
        g = new GridBagConstraints();
        g.gridx = 1;
        g.gridy = 0;
        g.anchor = GridBagConstraints.LINE_END;
        row.add(control, g);
        return row;
    }

    private static void lineHeader(JPanel lines) {
        String[] heads = { "", "Line style", "Line width", "Label opacity %" };
        for (int col = 0; col < heads.length; col++) lines.add(new JLabel(heads[col]), lineCell(0, col));
    }

    private static void lineRow(JPanel lines, int row, String name, JComponent style, JComponent width, JComponent opacity) {
        lines.add(new JLabel(name), lineCell(row, 0));
        lines.add(style, lineCell(row, 1));
        lines.add(width, lineCell(row, 2));
        lines.add(opacity, lineCell(row, 3));
    }

    /** Colonne du nom extensible, contrôles à leur largeur : une grille égale rognait « Label opacity % ». */
    private static GridBagConstraints lineCell(int row, int col) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = col;
        g.gridy = row;
        g.weightx = col == 0 ? 1 : 0;
        g.anchor = GridBagConstraints.LINE_START;
        g.fill = col == 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        g.insets = new Insets(row == 0 ? 0 : 6, col == 0 ? 0 : 8, 0, 0);
        return g;
    }

    /** Section du panneau de réglages, avec une marge intérieure. */
    private static StrategyPanel section(String title, JPanel body, int top, int left) {
        body.setBorder(BorderFactory.createEmptyBorder(top, left, 6, 4));
        body.setOpaque(false);
        StrategyPanel panel = new Section(title);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Section adaptée au dialogue de Bookmap, sans défilement horizontal. Minimale nulle : Bookmap la recopie
     * sur l'ascenseur, une minimale pleine rognait le bas. Second passage de mise en page à l'affichage.
     */
    private static final class Section extends StrategyPanel {
        Section(String title) { super(title, new BorderLayout()); }

        /**
         * Largeur demandée plafonnée à 372 px, comme AVWAP : au-delà, le GridBagLayout de Bookmap passe toutes
         * les sections à leur taille minimale et le panneau s'ouvre vide.
         */
        @Override public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            return new Dimension(Math.min(preferred.width, MAX_ASKED_WIDTH), preferred.height);
        }

        private static final int MAX_ASKED_WIDTH = 372;

        @Override public Dimension getMinimumSize() { return new Dimension(0, 0); }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(() -> {
                revalidate();
                if (getParent() != null) getParent().revalidate();
            });
        }
    }

    /** Ligne de couleur de Bookmap, sans le libellé verrouillé à 250 px qui rognait les boutons. */
    private static final class ColorRow extends ColorsConfigItem {
        ColorRow(Color current, Color fallback, String name, java.util.function.Consumer<Color> onPick) {
            super(current, fallback, name, onPick);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel label = getLabel();
            if (label != null) label.setPreferredSize(null);
        }

        @Override public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            Dimension minimum = super.getMinimumSize();
            return new Dimension(preferred.width, Math.max(preferred.height, minimum.height));
        }
    }

    private ColorRow colorRow(Color current, Color fallback, String name, java.util.function.Consumer<Color> onPick) {
        return new ColorRow(current, fallback, name, onPick);
    }

    /** Texte qui revient à la ligne quand le panneau rétrécit, et ajuste sa hauteur. */
    private static final class FittingLabel extends JLabel {
        FittingLabel(String text, boolean bold) {
            super("<html>" + text + "</html>");
            setAlignmentX(Component.LEFT_ALIGNMENT);
            if (bold) setFont(getFont().deriveFont(Font.BOLD));
        }

        @Override public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            int width = getWidth();
            if (width <= 0 && getParent() != null) width = getParent().getWidth() - parentInsets();
            int inner = width - getInsets().left - getInsets().right;
            if (inner <= 0 || inner >= preferred.width) return preferred;
            javax.swing.text.View html = (javax.swing.text.View) getClientProperty(BasicHTML.propertyKey);
            if (html == null) return preferred;
            html.setSize(inner, Integer.MAX_VALUE);
            int height = (int) Math.ceil(html.getPreferredSpan(javax.swing.text.View.Y_AXIS))
                    + getInsets().top + getInsets().bottom;
            return new Dimension(inner, Math.max(preferred.height, height));
        }

        private int parentInsets() {
            if (!(getParent() instanceof JComponent parent)) return 0;
            Insets insets = parent.getInsets();
            return insets.left + insets.right;
        }
    }

    /** Liste « Solid / Dashed » pour une ligne. */
    private JComboBox<String> styleBox(boolean dashed, java.util.function.Consumer<Boolean> setter) {
        JComboBox<String> box = new JComboBox<>(new String[] { "Solid", "Dashed" });
        box.setSelectedIndex(dashed ? 1 : 0);
        box.addActionListener(e -> { setter.accept(box.getSelectedIndex() == 1); settingsChanged(); });
        return box;
    }

    /** Liste « Money / Ticks » pour l'unité d'un gain / perte. */
    private JComboBox<String> unitBox(boolean ticks, java.util.function.Consumer<Boolean> setter) {
        JComboBox<String> box = new JComboBox<>(new String[] { "Money", "Ticks" });
        box.setSelectedIndex(ticks ? 1 : 0);
        box.addActionListener(e -> { setter.accept(box.getSelectedIndex() == 1); settingsChanged(); });
        return box;
    }

    /** Opacité du fond d'une étiquette, de 0 à 100 %, par pas de 5. */
    private JSpinner opacityBox(int percent, java.util.function.IntConsumer setter) {
        JSpinner box = new JSpinner(new SpinnerNumberModel(percent, 0, 100, 5));
        box.addChangeListener(e -> { setter.accept((Integer) box.getValue()); settingsChanged(); });
        return box;
    }

    /** Épaisseur d'une ligne, de 1 à WIDTH_MAX pixels. */
    private JSpinner widthBox(int width, java.util.function.IntConsumer setter) {
        JSpinner box = new JSpinner(new SpinnerNumberModel(width, 1, WIDTH_MAX, 1));
        box.addChangeListener(e -> { setter.accept((Integer) box.getValue()); settingsChanged(); });
        return box;
    }

    private void settingsChanged() {
        saveSettings();
        redrawAll();
    }

    private void saveSettings() {
        if (api == null) return;        // panneau ouvert avant l'activation
        Settings st = new Settings();
        st.posColor = posColor.getRGB();
        st.shortColor = shortColor.getRGB();
        st.tpColor = tpColor.getRGB();
        st.slColor = slColor.getRGB();
        st.labelColor = labelBg.getRGB();
        st.labelHoverColor = labelHot.getRGB();
        st.posDashed = posDashed;
        st.tpDashed = tpDashed;
        st.slDashed = slDashed;
        st.posWidth = posWidth;
        st.tpWidth = tpWidth;
        st.slWidth = slWidth;
        st.tagPos = tagPos;
        st.posOpacity = posOpacity;
        st.tpOpacity = tpOpacity;
        st.slOpacity = slOpacity;
        st.posTicks = posTicks;
        st.exitTicks = exitTicks;
        api.setSettings(st);
    }
}
