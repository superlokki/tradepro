package com.tradepro;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.*;

import velox.api.layer1.annotations.*;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.*;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.layers.strategies.interfaces.CanvasMouseEvent.CoordinateRequestType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.*;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.*;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;

/**
 * Couche d'affichage et de drag par-dessus le trading de Bookmap, à droite de la timeline :
 *  - ligne + étiquette sur la position ouverte ("LONG 3 @ prix" et PnL) ; la position vient du statut de Bookmap
 *    quand le fournisseur l'envoie, sinon des exécutions (PositionTracker)
 *  - poignées TP / SL à tirer : elles posent un ordre limite (TP) ou stop (SL) ordinaire, de la taille de la position
 *  - drag & drop de n'importe quel ordre actif pour le déplacer ; clic droit pendant le drag = abandonner
 *  - sur chaque ordre de sortie, l'écart en ticks et le montant par rapport au prix moyen de la position
 *  - crosshair avec étiquette de prix tant que la souris est sur le chart
 *
 * Scale in / scale out : les TP / SL posés par les poignées suivent la taille de la position. L'add-on ne les
 * annule pas quand la position se ferme : c'est laissé à Bookmap (add-on Execution Pro, ou à la main).
 * Les ordres d'entrée et leur taille viennent du trading natif de Bookmap (TCP).
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Trade Pro")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@Layer1TradingStrategy
@UnrestrictedData   // demandé par Bookmap ; l'add-on n'écrit ni n'envoie aucune donnée de marché hors de Bookmap
public class TradePro implements CustomModule, BboListener, OrdersListener, PositionListener,
        CustomSettingsPanelProvider, ScreenSpacePainterFactory {

    // Priorité maximale accordée à un add-on : sur une de nos cibles (poignée, ligne d'ordre), le clic doit nous
    // revenir même là où Bookmap dessine ses propres éléments cliquables dans la zone à droite de la timeline.
    private static final int HIGH_SCORE = MouseModuleScore.MAX.score;
    private static final int GRAB_PX = 6;           // tolérance pour attraper une ligne sur la heatmap
    private static final int LABEL_GRAB_PX = 11;    // à droite de la timeline : toute la hauteur de l'étiquette de l'ordre
    private static final int DRAG_MIN_PX = 4;       // en dessous, un clic n'est pas un déplacement : rien n'est envoyé

    private static final Color BUY = new Color(46, 204, 113);
    private static final Color SELL = new Color(231, 76, 60);
    private static final Color CROSS = new Color(200, 200, 200, 160);
    private static final Color POS = new Color(240, 180, 40);
    private static final Color POS_SHORT = new Color(171, 71, 188);
    private static final Color REFUSED = new Color(150, 40, 40);    // aperçu d'un ordre qui serait refusé, messages de refus
    // Étiquettes au style des labels d'ordres de Bookmap ("1 STP") : fond ardoise, texte blanc. La couleur de
    // l'élément (position, TP, SL) n'apparaît que sur sa ligne.
    private static final Color LABEL_BG = new Color(57, 73, 82);
    private static final Color LABEL_BG_HOT = new Color(12, 113, 160);    // bleu de survol de Bookmap : sous le pointeur ou pendant un drag

    /** Réglages enregistrés par Bookmap avec le workspace (un jeu par instrument). */
    @StrategySettingsVersion(currentVersion = 1, compatibleVersions = {})
    public static class Settings {
        public int posColor = POS.getRGB();             // position LONG
        public int shortColor = POS_SHORT.getRGB();     // position SHORT
        public int tpColor = Handle.TP.color.getRGB();
        public int slColor = Handle.SL.color.getRGB();
        public int labelColor = LABEL_BG.getRGB();          // fond des étiquettes
        public int labelHoverColor = LABEL_BG_HOT.getRGB(); // fond sous le pointeur ou pendant un drag
        public boolean posDashed = false, tpDashed = false, slDashed = false;   // style des lignes
        public int posWidth = 1, tpWidth = 1, slWidth = 1;                      // épaisseur des lignes, en pixels
        public int posOpacity = 100, tpOpacity = 100, slOpacity = 100;          // opacité du fond des étiquettes, en %
        public boolean posTicks = false, exitTicks = false;                     // gain / perte en ticks plutôt qu'en argent
        public int tagPos = 50;     // position horizontale des étiquettes, en % de la zone à droite de la timeline (0 = contre la timeline, 100 = bord droit)
    }

    private volatile Color posColor = POS, shortColor = POS_SHORT, tpColor = Handle.TP.color, slColor = Handle.SL.color;
    private volatile Color labelBg = LABEL_BG, labelHot = LABEL_BG_HOT;

    private Color colorOf(Handle h) { return h == Handle.TP ? tpColor : slColor; }

    private volatile boolean posDashed, tpDashed, slDashed;

    private boolean dashedOf(Handle h) { return h == Handle.TP ? tpDashed : slDashed; }

    private static final int WIDTH_MAX = 6;
    private volatile int posWidth = 1, tpWidth = 1, slWidth = 1;    // épaisseur des lignes, en pixels

    private int widthOf(Handle h) { return h == Handle.TP ? tpWidth : slWidth; }

    private static int clampWidth(int w) { return Math.max(1, Math.min(WIDTH_MAX, w)); }

    /** Position horizontale des étiquettes d'ordres, de position et des poignées, en % de la zone à droite de la timeline. */
    private volatile int tagPos = 50;

    /** Opacité du fond des étiquettes au repos, en % (0 = fond invisible, 100 = plein). Sous le pointeur, le fond est toujours plein. */
    private volatile int posOpacity = 100, tpOpacity = 100, slOpacity = 100;

    /** Gain / perte affiché en ticks plutôt qu'en argent : sur la position, et sur les TP / SL. */
    private volatile boolean posTicks, exitTicks;

    private int opacityOf(Handle h) { return h == Handle.TP ? tpOpacity : slOpacity; }

    private static int clampPercent(int v) { return Math.max(0, Math.min(100, v)); }

    /** Fond d'étiquette au repos, à l'opacité donnée. */
    private Color idleFill(int percent) {
        Color c = labelBg;
        return percent >= 100 ? c : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * percent / 100f));
    }
    private static final int HANDLE_SPACE_PX = 4;   // espace entre les poignées TP / SL et l'étiquette de la position
    private static final int HANDLE_GRAB_PX = 13;   // demi-hauteur de la poignée, plus 1 px
    private static final int NOTICE_MS = 4_000;     // durée d'affichage d'un message de refus sur le chart
    private static final long SENT_HIDE_MS = 3_000; // une poignée reste masquée le temps que son ordre apparaisse dans Bookmap

    /** Take-profit (ordre limite de sortie) ou stop-loss (ordre stop de sortie) : les deux poignées, et le rôle d'un ordre de sortie. */
    enum Handle {
        TP(new Color(38, 166, 91)), SL(new Color(230, 126, 34));
        final Color color;
        Handle(Color c) { color = c; }
        /** Début du clientId des ordres posés par cette poignée : c'est ce qui permet de les reconnaître. */
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

    // ---------- État ----------
    private String alias;
    private double pips;
    private PriceGrid grid;
    private double multiplier = Double.NaN;     // valeur d'un mouvement de 1.0 de prix pour 1 contrat, NaN si inconnue
    private Api api;
    private volatile boolean running;           // faux après stop() : plus aucun ordre n'est envoyé

    private volatile int bestBid = Integer.MIN_VALUE, bestAsk = Integer.MAX_VALUE;
    private volatile long lastPnlRedraw;
    private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();

    /** Sens, taille et prix moyen de la position : statut de Bookmap s'il est envoyé, sinon exécutions. */
    private final PositionTracker tracker = new PositionTracker();
    /** Dernière taille de position traitée, pour repérer un scale in / scale out. */
    private int lastPosition;
    /** Ordres actifs posés par les poignées (orderId), reconnus à leur clientId : les seuls que l'add-on redimensionne. */
    private final Map<String, Boolean> ownOrders = new ConcurrentHashMap<>();

    private volatile Notice notice;
    /** Heure du dernier envoi de chaque poignée (indice = Handle.ordinal()). */
    private final long[] sentAt = new long[Handle.values().length];

    /** Une vue par painter : Bookmap en recrée un (avec de nouveaux canvas) à chaque rechargement du chart. */
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
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(TradePro.class, "Trade Pro")
                .setScreenSpacePainterFactory(this)
                .setIsAdd(true)
                .build());
    }

    @Override
    public void stop() {
        running = false;
        api.sendUserMessage(Layer1ApiUserMessageModifyScreenSpacePainter
                .builder(TradePro.class, "Trade Pro")
                .setIsAdd(false)
                .build());
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

    /** Affiche un message sur le chart pendant quelques secondes, et le recopie dans le log Bookmap. */
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
        bestBid = bidPrice;   // en niveaux de prix (prix / pips)
        bestAsk = askPrice;
        // PnL affiché sur la position : on redessine quand le marché bouge, sans dépasser 5 fois par seconde
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
        tracker.onOrder(u.orderId, u.isBuy);      // le sens de l'ordre sert à signer ses exécutions
        positionMayHaveChanged();
        redrawAll();
    }

    @Override
    public void onOrderExecuted(ExecutionInfo ex) {
        // Sans identifiant d'exécution, on en fabrique un pour ne pas recompter une exécution rejouée
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

    /**
     * Scale in / scale out : quand la position grossit ou diminue sans changer de sens, les TP / SL posés par les
     * poignées prennent sa nouvelle taille. À la fermeture ou au retournement, l'add-on ne fait rien.
     */
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

    /**
     * Selon le fournisseur, un prix d'exécution ou un prix moyen arrive en prix réel ou déjà en niveaux.
     * On tranche une seule fois, à la réception, en gardant l'interprétation la plus proche du marché.
     */
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

    /** Ordre que pose une poignée pour sortir de cette position : on sort d'un long en vendant, d'un short en achetant. */
    private static Mode exitMode(Handle h, int position) {
        return Mode.of(position < 0, h == Handle.SL);
    }

    /**
     * Rôle d'un ordre par rapport à la position : TP si c'est une limite de sortie, SL si c'est un stop de sortie,
     * null sinon (ordre d'entrée, ou pas de position). Purement visuel, quelle que soit l'origine de l'ordre.
     */
    private static Handle roleOf(OrderInfo o, int position) {
        if (position == 0 || o.isBuy != (position < 0)) return null;
        if (o.type == OrderType.LMT) return Handle.TP;
        if (o.type == OrderType.STP || o.type == OrderType.STP_LMT) return Handle.SL;
        return null;
    }

    /**
     * Nombre de contrats de la position que cette poignée peut encore couvrir : la taille de la position moins
     * les ordres de sortie du même rôle déjà en place (poignée, TCP ou bracket Bookmap). 0 = poignée masquée.
     */
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
    private final List<CanvasShape> tags = new ArrayList<>();       // étiquettes ajoutées en dernier, au-dessus des lignes
    /** Étiquettes déjà placées dans ce dessin : {niveau, x gauche, x droite, hauteur}, pour décaler celles qui se chevauchent. */
    private final List<double[]> placed = new ArrayList<>();
    /** Images réutilisées d'un dessin à l'autre : la souris redessine à chaque mouvement. */
    private final Map<String, PreparedImage> lineCache = new HashMap<>();
    private final Map<String, Img> labelCache = new HashMap<>();
    private boolean disposed;

    private volatile int heatW, heatH, ladderW;
    private volatile long priceH = 1;

    private volatile boolean mouseIn;
    private volatile int mouseX;
    private volatile double mouseLevel;
    private volatile boolean mouseOnLadder;
    /** Bords gauche et droit de chaque poignée au dernier dessin : {x1, x2} par Handle.ordinal(), vides si absente. */
    private volatile int[] handleX = new int[2 * Handle.values().length];

    private volatile Handle dragHandle;
    private volatile String dragId;
    private volatile double dragLevel;
    private volatile double pressLevel;
    private volatile int pressSign;             // sens de la position au clic sur une poignée : +1 long, -1 short
    private volatile boolean dragMoved;         // faux tant que la souris n'a pas bougé de DRAG_MIN_PX depuis le clic

    final ScreenSpacePainter painter = new ScreenSpacePainterAdapter() {
        // Bookmap envoie les changements par lot : on note qu'il faut redessiner, et on le fait une fois dans onMoveEnd
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
        /** Vrai pour la zone à droite de la timeline, où sont ancrées les poignées TP / SL. */
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
            if (id == MouseEvent.MOUSE_WHEEL) return MouseModuleScore.NONE.score;   // le zoom reste à Bookmap, même pendant un drag
            if (dragId != null || dragHandle != null) return HIGH_SCORE;
            if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_ENTERED
                    || id == MouseEvent.MOUSE_EXITED) {
                track(e);
                return MouseModuleScore.MIN.score;       // on observe sans voler la main
            }
            // Le clic droit reste à Bookmap (menu) : on ne prend que le clic gauche sur une cible
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
                // clic droit (ou tout autre bouton) pendant un drag : on abandonne, rien n'est envoyé
                if (!SwingUtilities.isLeftMouseButton(src)) { cancelDrag(); redraw(); }
                return;
            }
            if (!SwingUtilities.isLeftMouseButton(src)) return;
            pressLevel = mouseLevel;
            dragLevel = mouseLevel;
            dragMoved = false;
            pressSign = Integer.signum(position());
            // Un ordre existant passe avant une poignée qui le chevauche : on déplace ce qui est déjà au marché
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
                redraw();       // simple clic, drag abandonné ou add-on arrêté : aucun ordre
                return;
            }
            int target = (int) Math.round(mouseLevel);      // le prix du relâchement, pas celui du dernier mouvement
            if (hd != null) placeExit(hd, target, pressSign);
            OrderInfo o = id == null ? null : orders.get(id);
            if (o != null && target != Math.round(orderLevel(o))) {     // même niveau : pas de modification inutile
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

    /**
     * Ordre déplaçable le plus proche de ce niveau. À droite de la timeline la tolérance couvre toute la hauteur
     * de l'étiquette : un clic sur le bord de l'étiquette ne doit pas tomber à côté et devenir un ordre Bookmap.
     */
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

    /** X du centre d'une étiquette de largeur w, gardée entière dans la zone à droite de la timeline. */
    private int tagCenter(int w) {
        int c = ladderW * tagPos / 100;
        return Math.max(w / 2, Math.min(ladderW - w / 2, c));
    }

    /**
     * Poignée sous le pointeur. Les poignées sont sur la ligne de la position, à gauche de son étiquette : il faut
     * être à la hauteur de la position et entre les bords de la poignée (élargis de la moitié de l'espace qui les
     * sépare, pour qu'un clic entre deux poignées ne devienne pas un ordre Bookmap).
     */
    private Handle handleNear(double level) {
        double pos = positionLevel();
        if (Double.isNaN(pos) || Math.abs(pos - level) * pxPerLevel() > HANDLE_GRAB_PX) return null;
        int[] xs = handleX;
        int x = mouseX, slack = HANDLE_SPACE_PX / 2;
        for (Handle h : Handle.values()) {
            if (remaining(h, position()) == 0) continue;     // déjà couvert par un ordre en place
            int i = 2 * h.ordinal();
            if (xs[i + 1] > xs[i] && x >= xs[i] - slack && x < xs[i + 1] + slack) return h;
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
        if (Double.isNaN(pos)) handleX = new int[2 * Handle.values().length];
        if (!Double.isNaN(pos)) {
            int size = Math.abs(position);
            Color pc = position > 0 ? posColor : shortColor;
            int pw = posWidth;
            hLine(pos, pc, posDashed, pw);
            if (ladderW > 0) {
                add(ladderShapes, l, icon(line(ladderW, pw, pc, false), px(0), dataY(pos, -pw / 2 - (pw % 2)),
                        px(ladderW), dataY(pos, pw / 2)));
            }
            // PnL latent au prix de sortie immédiat : le bid pour un long, l'ask pour un short
            String pnl = bboKnown() ? "  " + pnl(position > 0 ? bestBid : bestAsk, pos, size, position, posTicks) : "";
            Img posImg = labelImg(size + (position > 0 ? " LONG" : " SHORT") + "  " + fmt(pos) + pnl,
                    idleFill(posOpacity), false);
            // Les poignées TP / SL sont sur la même ligne, à gauche de l'étiquette de la position, qui garde sa place
            // (sauf s'il n'y a pas la place à gauche : elle se pousse alors vers la droite)
            int[] xs = new int[2 * Handle.values().length];
            int handlesW = 0;
            for (Handle hd : Handle.values()) {
                if (remaining(hd, position) > 0) handlesW += labelImg(hd.toString(), labelBg, true).w() + HANDLE_SPACE_PX;
            }
            int posX = Math.max(handlesW, tagCenter(posImg.w()) - posImg.w() / 2);
            int nextX = posX - handlesW;
            for (Handle hd : Handle.values()) {
                if (remaining(hd, position) == 0) continue;
                int w = labelImg(hd.toString(), labelBg, true).w();
                xs[2 * hd.ordinal()] = nextX;
                xs[2 * hd.ordinal() + 1] = nextX + w;
                nextX += w + HANDLE_SPACE_PX;
            }
            handleX = xs;
            place(pos, posImg, posX);
            // Poignée sous le pointeur (celle qu'un clic attraperait) : affichée opaque, comme un bouton survolé
            Handle hover = dragHandle != null ? dragHandle
                    : mouseIn && mouseOnLadder && dragId == null && orderNear(mouseLevel) == null
                            ? handleNear(mouseLevel) : null;
            for (Handle hd : Handle.values()) {
                int left = remaining(hd, position);
                if (left == 0) continue;        // toute la position a déjà son TP (ou SL) : pas de poignée
                if (hd == dragHandle && moving) {
                    int lvl = (int) Math.round(dragLevel);
                    Mode em = exitMode(hd, position);
                    String refusal = refusal(em, lvl);
                    if (refusal != null && bboKnown()) {
                        // Mauvais côté du marché : la poignée posera une limite au meilleur prix (voir insideLevel)
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
                    if (hd != hover) {      // au repos : opacité réglée pour ce rôle, comme le label de position
                        fill = idleFill(opacityOf(hd));
                    }
                    place(pos, labelImg(hd.toString(), fill, true), xs[2 * hd.ordinal()]);
                }
            }
        }

        // 1) Ordres actifs : un ordre de sortie de la position s'affiche comme TP (limite) ou SL (stop)
        // Ordre sous le pointeur (celui qu'un clic attraperait) : étiquette opaque et ligne épaissie
        OrderInfo hoverOrder = mouseIn && dragId == null && dragHandle == null ? orderNear(mouseLevel) : null;
        for (OrderInfo o : orders.values()) {
            if (Double.isNaN(orderLevel(o))) continue;      // ordre au marché : pas de prix, pas de ligne
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
     * Étiquette à droite de la timeline (ordre, position, poignée), centrée sur la position horizontale réglée.
     * Si elle recouvrirait une étiquette déjà placée à un prix voisin, elle est décalée à sa droite.
     */
    /**
     * @param big    étiquette plus large : les poignées, pour être faciles à viser
     * @param fill   fond de l'étiquette (labelBg au repos, labelHot sous le pointeur)
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

    /** Pose une étiquette à cet endroit précis, et la note pour que les suivantes ne la recouvrent pas. */
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
     * Pose l'ordre de sortie d'une poignée : limite (TP) ou stop (SL), pour la partie de la position qui n'a pas
     * encore d'ordre de sortie de ce type. Relâchée du mauvais côté du marché, elle pose une limite au meilleur prix.
     * L'add-on le redimensionne ensuite avec la position (scale in / scale out), mais ne l'annule jamais.
     * @param pressSign sens de la position au début du geste ; si elle a changé depuis, rien n'est envoyé
     */
    private void placeExit(Handle h, int level, int pressSign) {
        if (!running) return;
        int pos = position();       // lu une seule fois : la taille et le sens viennent de la même position
        if (pos == 0 || Integer.signum(pos) != pressSign) {
            showNotice(h + " cancelled: position changed during the drag", level);
            return;
        }
        Mode m = exitMode(h, pos);
        if (!bboKnown()) {
            showNotice(h + " rejected: no market data", level);
            return;
        }
        // Relâché du mauvais côté du marché (l'ordre partirait tout de suite) : on sort par une limite au meilleur
        // prix, comme les boutons de Bookmap (voir insideLevel).
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
        b.setClientId(h.prefix() + UUID.randomUUID());      // pour reconnaître l'ordre et le redimensionner ensuite
        sentAt[h.ordinal()] = System.currentTimeMillis();
        api.sendOrder(b.build());
        String kind = inside ? insideName(h, m.buy) : m.label;
        Log.info("Trade Pro: " + h + " " + kind + " x" + size + " @ " + fmt(level) + " sent");
    }

    /**
     * Prix de la limite posée par une poignée relâchée du mauvais côté du marché :
     *  - TP : passif, on prend le profit au meilleur prix sans payer l'écart (SELL ASK pour un long, BUY BID pour un short) ;
     *  - SL : agressif, on sort tout de suite (SELL BID pour un long, BUY ASK pour un short).
     */
    private int insideLevel(Handle h, boolean buy) {
        boolean atBid = (h == Handle.TP) == buy;
        return atBid ? bestBid : bestAsk;
    }

    private static String insideName(Handle h, boolean buy) {
        boolean atBid = (h == Handle.TP) == buy;
        return (buy ? "BUY " : "SELL ") + (atBid ? "BID" : "ASK");
    }

    /** Vrai si le meilleur bid et le meilleur ask forment un vrai carnet (un 0 / 0 d'attente ne compte pas). */
    private boolean bboKnown() {
        int bid = bestBid, ask = bestAsk;
        return bid != Integer.MIN_VALUE && ask != Integer.MAX_VALUE && ask > bid;
    }

    /**
     * Raison de refuser un ordre à ce niveau, ou null s'il peut partir. Un ordre est refusé s'il s'exécuterait
     * tout de suite (mauvais côté du marché), ou si le carnet n'est pas connu et qu'on ne peut donc pas en juger.
     */
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
                double shift = p - o.stopPrice;         // la limite suit le déclenchement, avec le même écart
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

    /** Gain ou perte d'un TP / SL à ce niveau, dans l'unité réglée pour les TP / SL. */
    private String distance(double level, double posLevel, int size, int position) {
        return pnl(level, posLevel, size, position, exitTicks);
    }

    /**
     * Gain ou perte si la position sort à ce niveau : en argent pour la taille donnée ("+$250.00" / "-$125.00"),
     * ou en ticks par contrat ("+12 t"). Toujours en ticks quand la valeur du point de l'instrument est inconnue,
     * plutôt qu'un montant faux.
     */
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

    private static final Font FONT = uiFont(12f);

    /** Police de l'interface Bookmap (Roboto embarquée) ; police système si elle est introuvable. */
    private static Font uiFont(float size) {
        try (InputStream in = Log.class.getResourceAsStream("/resources/fonts/roboto/RobotoBm-Medium.ttf")) {
            if (in != null) return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(size);
        } catch (Exception e) {
            // on retombe sur la police système
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, (int) size);
    }

    /**
     * Étiquette au style de Bookmap : rectangle arrondi, texte blanc.
     */
    private static BufferedImage label(String text, Color fill, boolean big) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        FontMetrics fm = pg.getFontMetrics(FONT);
        pg.dispose();
        // Les poignées ont la hauteur des autres étiquettes : elles sont sur la même ligne que celle de la position
        int padX = big ? 12 : 7, padY = 2;
        int w = fm.stringWidth(text) + 2 * padX, h = fm.getHeight() + 2 * padY;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(fill);
        g.fillRoundRect(0, 0, w, h, 6, 6);
        // étiquette translucide (poignée au repos) : le texte s'efface avec elle
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
        JPanel colors = new JPanel(new GridLayout(6, 1, 6, 6));
        colors.add(new ColorsConfigItem(posColor, POS, "Long",
                c -> { posColor = c; settingsChanged(); }));
        colors.add(new ColorsConfigItem(shortColor, POS_SHORT, "Short",
                c -> { shortColor = c; settingsChanged(); }));
        colors.add(new ColorsConfigItem(tpColor, Handle.TP.color, "Take profit",
                c -> { tpColor = c; settingsChanged(); }));
        colors.add(new ColorsConfigItem(slColor, Handle.SL.color, "Stop loss",
                c -> { slColor = c; settingsChanged(); }));
        colors.add(new ColorsConfigItem(labelBg, LABEL_BG, "Label",
                c -> { labelBg = c; settingsChanged(); }));
        colors.add(new ColorsConfigItem(labelHot, LABEL_BG_HOT, "Label hover",
                c -> { labelHot = c; settingsChanged(); }));

        JPanel lines = new JPanel(new GridLayout(4, 4, 2, 6));
        lines.add(new JLabel("")); lines.add(new JLabel("Line style")); lines.add(new JLabel("Line width"));
        lines.add(new JLabel("Label opacity %"));
        lines.add(new JLabel("Position")); lines.add(styleBox(posDashed, d -> posDashed = d));
        lines.add(widthBox(posWidth, w -> posWidth = w));
        lines.add(opacityBox(posOpacity, v -> posOpacity = v));
        lines.add(new JLabel("Take profit")); lines.add(styleBox(tpDashed, d -> tpDashed = d));
        lines.add(widthBox(tpWidth, w -> tpWidth = w));
        lines.add(opacityBox(tpOpacity, v -> tpOpacity = v));
        lines.add(new JLabel("Stop loss")); lines.add(styleBox(slDashed, d -> slDashed = d));
        lines.add(widthBox(slWidth, w -> slWidth = w));
        lines.add(opacityBox(slOpacity, v -> slOpacity = v));

        // Libellé et curseur sur une seule ligne : le panneau complet doit tenir dans la fenêtre de réglages de Bookmap
        JPanel tagsPanel = new JPanel(new GridLayout(1, 2, 6, 6));
        String caption = "Left to right: ";
        JLabel value = new JLabel(caption + tagPos);
        JSlider slider = new JSlider(0, 100, tagPos);
        slider.setToolTipText(String.valueOf(tagPos));
        slider.addChangeListener(e -> {
            tagPos = slider.getValue();
            value.setText(caption + tagPos);                    // valeur choisie, visible pendant le glissement
            slider.setToolTipText(String.valueOf(tagPos));
            if (slider.getValueIsAdjusting()) redrawAll();      // l'aperçu suit le curseur, l'enregistrement attend le relâchement
            else settingsChanged();
        });
        tagsPanel.add(value);
        tagsPanel.add(slider);

        // L'API ne donne pas à un add-on la position déjà ouverte : il ne peut que suivre celles ouvertes après son activation
        // Deux lignes courtes : sur une seule, le texte dépassait la largeur du panneau et son haut était rogné
        JPanel pnlPanel = new JPanel(new GridLayout(2, 2, 6, 6));
        pnlPanel.add(new JLabel("Position"));
        pnlPanel.add(unitBox(posTicks, t -> posTicks = t));
        pnlPanel.add(new JLabel("Take profit / Stop loss"));
        pnlPanel.add(unitBox(exitTicks, t -> exitTicks = t));

        JPanel warning = new JPanel(new GridLayout(2, 1, 6, 6));
        JLabel rule = new JLabel("Enable Trade Pro only when you are flat.");
        rule.setFont(rule.getFont().deriveFont(Font.BOLD));
        warning.add(rule);
        warning.add(new JLabel("It cannot see a position that was opened before it was enabled."));
        return new StrategyPanel[] {
            section("Important", warning, 8, TEXT_INDENT), section("Colors", colors, 4, 0),
            section("Lines and labels", lines, 4, TEXT_INDENT),
            section("Profit and loss value", pnlPanel, 4, TEXT_INDENT),
            section("Label position", tagsPanel, 4, TEXT_INDENT) };
    }

    /** Retrait que le sélecteur de couleur de Bookmap donne à son libellé : les autres sections s'alignent dessus. */
    private static final int TEXT_INDENT = 10;

    /** Section du panneau de réglages : le contenu, avec une marge intérieure pour ne pas toucher le cadre. */
    private static StrategyPanel section(String title, JPanel body, int top, int left) {
        body.setBorder(BorderFactory.createEmptyBorder(top, left, 6, 4));
        body.setOpaque(false);
        StrategyPanel panel = new Section(title);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Section qui garde toujours la hauteur que demande son contenu, cadre de Bookmap compris : sa taille minimale
     * est sa taille préférée, donc Bookmap ne peut pas la comprimer (texte rogné, bas du cadre absent), et elle ne
     * s'étire pas non plus en hauteur. Les tailles sont recalculées à chaque demande, pas figées à la création :
     * le cadre et son titre sont posés par Bookmap après coup.
     */
    private static final class Section extends StrategyPanel {
        Section(String title) { super(title, new BorderLayout()); }

        @Override public Dimension getMinimumSize() { return getPreferredSize(); }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
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
        if (api == null) return;        // panneau ouvert avant l'activation sur un instrument
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
