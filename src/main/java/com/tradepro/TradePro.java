package com.tradepro;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.annotations.Layer1TradingStrategy;
import velox.api.layer1.annotations.UnrestrictedData;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.OrderCancelParameters;
import velox.api.layer1.data.OrderInfo;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.data.OrderMoveParameters;
import velox.api.layer1.data.OrderResizeParameters;
import velox.api.layer1.data.OrderStatus;
import velox.api.layer1.data.OrderType;
import velox.api.layer1.data.SimpleOrderSendParametersBuilder;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.layers.strategies.interfaces.CanvasMouseEvent;
import velox.api.layer1.layers.strategies.interfaces.CanvasMouseEvent.CoordinateRequestType;
import velox.api.layer1.layers.strategies.interfaces.CanvasMouseListener;
import velox.api.layer1.layers.strategies.interfaces.MouseModuleScore;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasIcon;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CanvasShape;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeCoordinateBase;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeHorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.CompositeVerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.HorizontalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.PreparedImage;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvas.VerticalCoordinate;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpaceCanvasFactory.ScreenSpaceCanvasType;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterAdapter;
import velox.api.layer1.layers.strategies.interfaces.ScreenSpacePainterFactory;
import velox.api.layer1.messages.indicators.AliasFilter;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyScreenSpacePainter;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.BboListener;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.OrdersListener;
import velox.api.layer1.simplified.PositionListener;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;

/**
 * Couche d'affichage et de drag par-dessus le trading de Bookmap :
 *
 * <ul>
 *   <li>ligne et étiquette de la position, avec son PnL
 *   <li>poignées TP / SL à tirer pour poser un ordre de sortie, et bouton BE (Break Even)
 *   <li>drag &amp; drop des ordres actifs ; clic droit pendant le drag = abandon
 *   <li>crosshair avec étiquette de prix
 * </ul>
 *
 * <p>Les TP / SL des poignées suivent la taille de la position ; l'add-on ne les annule jamais.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Trade Pro")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
@Layer1TradingStrategy
@UnrestrictedData // requis par Bookmap ; aucune donnée ne sort de Bookmap
public class TradePro
    implements CustomModule,
        BboListener,
        OrdersListener,
        PositionListener,
        CustomSettingsPanelProvider,
        ScreenSpacePainterFactory {

  // Priorité maximale : sur nos cibles, le clic nous revient avant Bookmap
  private static final int HIGH_SCORE = MouseModuleScore.MAX.score;
  private static final int GRAB_PX = 6; // tolérance pour attraper une ligne sur la heatmap
  private static final int LABEL_GRAB_PX = 11; // à droite de la timeline : hauteur de l'étiquette
  private static final int DRAG_MIN_PX = 4; // en dessous, c'est un clic, pas un déplacement

  private static final Color BUY = new Color(8, 153, 129); // #089981
  private static final Color SELL = new Color(242, 54, 69); // #F23645
  private static final Color CROSS = new Color(149, 152, 161); // #9598A1
  private static final Color CROSS_TAG = new Color(70, 70, 70); // fond du prix du crosshair
  private static final Color POS = BUY;
  private static final Color POS_SHORT = SELL;
  // Étiquettes au style de Bookmap : fond ardoise, texte blanc
  private static final Color LABEL_BG = new Color(57, 73, 82);
  // Survol ou drag : bleu des étiquettes LMT / STP de Bookmap, mesuré à l'écran. Toujours dessiné
  // translucide (HOT_ALPHA), quelle que soit la couleur choisie dans les réglages, comme chez
  // Bookmap.
  private static final Color LABEL_BG_HOT = new Color(0, 127, 241);
  private static final int HOT_ALPHA = 150; // environ 59 %
  // Ancien bleu par défaut, remplacé s'il est encore enregistré
  private static final int OLD_LABEL_BG_HOT = 0xFF0C71A0;

  private static final int WIDTH_MAX = 6;
  private static final int HANDLE_SPACE_PX = 4; // entre les poignées et l'étiquette de la position
  // Écart visé entre les deux niveaux mesurés, pour une échelle précise
  private static final int SCALE_SPAN_PX = 400;
  private static final long SENT_HIDE_MS = 3_000; // poignée masquée le temps que l'ordre apparaisse

  /** Version de l'add-on, écrite par le build dans version.txt. */
  private static final String VERSION = readVersion();

  private static final Font FONT = uiFont(14f); // Roboto Medium 14, comme Bookmap

  /** Retrait du libellé des couleurs de Bookmap, repris par les autres sections. */
  private static final int TEXT_INDENT = 10;

  /** Réglages enregistrés avec le workspace, par instrument. */
  @StrategySettingsVersion(
      currentVersion = 1,
      compatibleVersions = {})
  public static class Settings {
    /** Couleur de la position LONG. */
    public int posColor = POS.getRGB();

    /** Couleur de la position SHORT. */
    public int shortColor = POS_SHORT.getRGB();

    /** Couleur des ordres et de la poignée Take Profit. */
    public int tpColor = Handle.TP.color.getRGB();

    /** Couleur des ordres et de la poignée Stop Loss. */
    public int slColor = Handle.SL.color.getRGB();

    /** Couleur de fond des étiquettes au repos. */
    public int labelColor = LABEL_BG.getRGB();

    /** Couleur de fond des étiquettes au survol ou pendant un drag. */
    public int labelHoverColor = LABEL_BG_HOT.getRGB();

    /** Vrai si la ligne de position est en pointillés. */
    public boolean posDashed = false;

    /** Vrai si la ligne de Take Profit est en pointillés. */
    public boolean tpDashed = false;

    /** Vrai si la ligne de Stop Loss est en pointillés. */
    public boolean slDashed = false;

    /** Épaisseur de la ligne de position, en pixels. */
    public int posWidth = 1;

    /** Épaisseur de la ligne de Take Profit, en pixels. */
    public int tpWidth = 1;

    /** Épaisseur de la ligne de Stop Loss, en pixels. */
    public int slWidth = 1;

    /** Opacité du fond de l'étiquette de position, en %. */
    public int posOpacity = 100;

    /** Opacité du fond de l'étiquette de Take Profit, en %. */
    public int tpOpacity = 100;

    /** Opacité du fond de l'étiquette de Stop Loss, en %. */
    public int slOpacity = 100;

    /** Vrai pour afficher le PnL de la position en ticks plutôt qu'en argent. */
    public boolean posTicks = false;

    /** Vrai pour afficher le PnL des sorties en ticks plutôt qu'en argent. */
    public boolean exitTicks = false;

    /** Position horizontale des étiquettes, en % (0 = contre la timeline, 100 = bord droit). */
    public int tagPos = 50;
  }

  /** Take profit (limite) ou stop loss (stop) : poignée, et rôle d'un ordre de sortie. */
  enum Handle {
    TP(BUY),
    SL(SELL);

    final Color color;

    Handle(Color color) {
      this.color = color;
    }

    /** Début du clientId des ordres de cette poignée. */
    String prefix() {
      return "tradepro-" + this + "-";
    }
  }

  enum Mode {
    BUY_LMT("BUY LMT", true, false),
    SELL_LMT("SELL LMT", false, false),
    BUY_STP("BUY STP", true, true),
    SELL_STP("SELL STP", false, true);

    final String label;
    final boolean buy;
    final boolean stop;

    Mode(String label, boolean buy, boolean stop) {
      this.label = label;
      this.buy = buy;
      this.stop = stop;
    }

    static Mode of(boolean buy, boolean stop) {
      return stop ? (buy ? BUY_STP : SELL_STP) : (buy ? BUY_LMT : SELL_LMT);
    }
  }

  /** Image d'étiquette prête à dessiner, avec sa taille. */
  private record Img(PreparedImage image, int w, int h) {}

  /** Rectangle d'une poignée à l'écran : bords gauche et droit, hauteur, en pixels. */
  private record Span(int left, int right, int height) {}

  // ---------- État ----------
  private volatile Color posColor = POS;
  private volatile Color shortColor = POS_SHORT;
  private volatile Color tpColor = Handle.TP.color;
  private volatile Color slColor = Handle.SL.color;
  private volatile Color labelBg = LABEL_BG;
  private volatile Color labelHot = LABEL_BG_HOT;

  private volatile boolean posDashed;
  private volatile boolean tpDashed;
  private volatile boolean slDashed;

  private volatile int posWidth = 1; // en pixels
  private volatile int tpWidth = 1;
  private volatile int slWidth = 1;

  /** Position horizontale des étiquettes, en %. */
  private volatile int tagPos = 50;

  /** Opacité du fond des étiquettes au repos, en %. */
  private volatile int posOpacity = 100;

  private volatile int tpOpacity = 100;
  private volatile int slOpacity = 100;

  /** Gain / perte en ticks plutôt qu'en argent. */
  private volatile boolean posTicks;

  private volatile boolean exitTicks;

  private String alias;
  private double pips;
  private PriceGrid grid;
  private double multiplier = Double.NaN; // valeur d'un point pour 1 contrat, NaN si inconnue
  private Api api;
  private volatile boolean running; // faux après stop() : plus aucun ordre envoyé

  private volatile int bestBid = Integer.MIN_VALUE;
  private volatile int bestAsk = Integer.MAX_VALUE;
  private volatile long lastPnlRedraw;
  private final Map<String, OrderInfo> orders = new ConcurrentHashMap<>();

  /** Position : statut de Bookmap s'il existe, sinon exécutions. */
  private final PositionTracker tracker = new PositionTracker();

  /** Dernière position traitée, pour repérer un scale in / out. */
  private int lastPosition;

  /** Ordres posés par les poignées : les seuls que l'add-on redimensionne. */
  private final Map<String, Boolean> ownOrders = new ConcurrentHashMap<>();

  /**
   * Stops lâchés du mauvais côté, en cours d'annulation : orderId -> taille de la sortie à envoyer
   * ensuite.
   */
  private final Map<String, Integer> pendingExits = new ConcurrentHashMap<>();

  /** Dernier envoi de chaque poignée, par Handle.ordinal(). */
  private final long[] sentAt = new long[Handle.values().length];

  /** Dernier envoi ou déplacement d'un SL au break-even. */
  private volatile long beSentAt;

  /** Une vue par painter : Bookmap en recrée un à chaque rechargement du chart. */
  private final List<View> views = new CopyOnWriteArrayList<>();

  private Color colorOf(Handle handle) {
    return handle == Handle.TP ? tpColor : slColor;
  }

  private boolean dashedOf(Handle handle) {
    return handle == Handle.TP ? tpDashed : slDashed;
  }

  private int widthOf(Handle handle) {
    return handle == Handle.TP ? tpWidth : slWidth;
  }

  private static int clampWidth(int width) {
    return Math.max(1, Math.min(WIDTH_MAX, width));
  }

  private int opacityOf(Handle handle) {
    return handle == Handle.TP ? tpOpacity : slOpacity;
  }

  private static int clampPercent(int value) {
    return Math.max(0, Math.min(100, value));
  }

  /** Fond d'étiquette survolée ou en drag : la couleur réglée, translucide. */
  private Color hotFill() {
    Color c = labelHot;
    return new Color(c.getRed(), c.getGreen(), c.getBlue(), HOT_ALPHA);
  }

  /** Fond d'étiquette au repos, à l'opacité donnée. */
  private Color idleFill(int percent) {
    Color c = labelBg;
    return percent >= 100
        ? c
        : new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * percent / 100f));
  }

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
    pendingExits.clear();
    beSentAt = 0;
    bestBid = Integer.MIN_VALUE;
    bestAsk = Integer.MAX_VALUE;
    Settings st = api.getSettings(Settings.class);
    posColor = new Color(st.posColor);
    shortColor = new Color(st.shortColor);
    boolean known =
        !Double.isNaN(info.multiplier)
            && !Double.isInfinite(info.multiplier)
            && info.multiplier > 0;
    multiplier = known ? info.multiplier : Double.NaN;
    tpColor = new Color(st.tpColor);
    slColor = new Color(st.slColor);
    labelBg = new Color(st.labelColor);
    // Sans transparence
    labelHot =
        st.labelHoverColor == OLD_LABEL_BG_HOT ? LABEL_BG_HOT : new Color(st.labelHoverColor);
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
   * Ajout ou retrait du painter de cet instrument. Une instance par instrument : sans nom propre à
   * l'instrument, la dernière activée remplaçait le painter des autres, qui ne dessinaient plus
   * rien.
   */
  private Layer1ApiUserMessageModifyScreenSpacePainter painterMessage(boolean add) {
    String own = alias;
    Layer1ApiUserMessageModifyScreenSpacePainter.Builder builder =
        Layer1ApiUserMessageModifyScreenSpacePainter.builder(TradePro.class, "Trade Pro")
            .setAliasFilter(
                new AliasFilter() {
                  @Override
                  public boolean isDisplayedForAlias(String candidateAlias) {
                    return own.equals(candidateAlias);
                  }
                })
            .setIsAdd(add);
    if (add) {
      builder.setScreenSpacePainterFactory(this);
    }
    Layer1ApiUserMessageModifyScreenSpacePainter msg = builder.build();
    msg.applyNameModifier(own);
    return msg;
  }

  @Override
  public ScreenSpacePainter createScreenSpacePainter(
      String name, String indicatorAlias, ScreenSpaceCanvasFactory factory) {
    if (!alias.equals(indicatorAlias)) {
      return new ScreenSpacePainterAdapter() {};
    }
    View view = new View(factory);
    views.add(view);
    return view.painter;
  }

  private void redrawAll() {
    views.forEach(View::redraw);
  }

  // ======================================================================
  //  Données reçues de Bookmap : carnet, ordres, position
  // ======================================================================
  @Override
  public void onBbo(int bidPrice, int bidSize, int askPrice, int askSize) {
    boolean moved = bidPrice != bestBid || askPrice != bestAsk;
    bestBid = bidPrice; // en niveaux de prix
    bestAsk = askPrice;
    // PnL de la position : 5 redessins par seconde au plus
    long now = System.currentTimeMillis();
    if (moved && position() != 0 && now - lastPnlRedraw >= 200) {
      lastPnlRedraw = now;
      redrawAll();
    }
  }

  @Override
  public void onOrderUpdated(OrderInfoUpdate update) {
    if (!alias.equals(update.instrumentAlias)) {
      return;
    }
    if (update.status != null && update.status.isActive()) {
      orders.put(update.orderId, update);
      if (isHandleClientId(update.clientId)) {
        ownOrders.put(update.orderId, Boolean.TRUE);
      }
      for (Handle h : Handle.values()) {
        if (update.clientId != null && update.clientId.startsWith(h.prefix())) {
          sentAt[h.ordinal()] = 0;
        }
      }
    } else {
      orders.remove(update.orderId);
      ownOrders.remove(update.orderId);
      beSentAt = 0;
      for (Handle h : Handle.values()) {
        if (update.clientId != null && update.clientId.startsWith(h.prefix())) {
          sentAt[h.ordinal()] = 0;
        }
      }
      Integer exit = pendingExits.remove(update.orderId);
      // Seule une annulation confirmée lance la sortie : exécuté ou refusé, le stop a fait son
      // office
      if (exit != null && update.status == OrderStatus.CANCELLED) {
        sendPendingExit(update.isBuy, exit);
      }
    }
    tracker.onOrder(update.orderId, update.isBuy); // le sens de l'ordre signe ses exécutions
    positionMayHaveChanged();
    redrawAll();
  }

  @Override
  public void onOrderExecuted(ExecutionInfo execution) {
    // Sans executionId, une clé maison évite de recompter une exécution rejouée
    String key =
        execution.executionId != null && !execution.executionId.isEmpty()
            ? execution.executionId
            : execution.orderId
                + "|"
                + execution.time
                + "|"
                + execution.price
                + "|"
                + execution.size;
    tracker.onExecution(execution.orderId, key, toRealPrice(execution.price), execution.size);
    positionMayHaveChanged();
    redrawAll();
  }

  @Override
  public void onPositionUpdate(StatusInfo status) {
    if (!alias.equals(status.instrumentAlias)) {
      return;
    }
    double avg = status.averagePrice > 0 ? toRealPrice(status.averagePrice) : Double.NaN;
    tracker.onStatus(status.position, avg);
    positionMayHaveChanged();
    redrawAll();
  }

  private static boolean isHandleClientId(String clientId) {
    if (clientId == null) {
      return false;
    }
    for (Handle handle : Handle.values()) {
      if (clientId.startsWith(handle.prefix())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Scale in / out : les TP / SL des poignées prennent la nouvelle taille. Rien à la fermeture ni
   * au retournement.
   */
  private synchronized void positionMayHaveChanged() {
    int now = position();
    int before = lastPosition;
    if (now == before) {
      return;
    }
    lastPosition = now;
    if (!running || now == 0 || before == 0 || (now > 0) != (before > 0)) {
      beSentAt = 0;
      return;
    }
    int size = Math.abs(now);
    double posLvl = positionLevel();
    for (String orderId : ownOrders.keySet()) {
      OrderInfo order = orders.get(orderId);
      if (order == null || roleOf(order, now) == null || order.unfilled == size) {
        continue;
      }
      if (roleOf(order, now) == Handle.SL
          && !Double.isNaN(posLvl)
          && Math.round(orderLevel(order)) == Math.round(posLvl)) {
        beSentAt = System.currentTimeMillis();
      }
      api.updateOrder(new OrderResizeParameters(orderId, size));
      Log.info(
          "Trade Pro: "
              + roleOf(order, now)
              + " "
              + orderId
              + " resized "
              + order.unfilled
              + " -> "
              + size);
    }
  }

  /**
   * Prix reçu en prix réel ou en niveaux selon le fournisseur : on garde le plus proche du marché.
   */
  private double toRealPrice(double price) {
    if (!bboKnown()) {
      return price;
    }
    double bid = bestBid * pips;
    return Math.abs(price * pips - bid) < Math.abs(price - bid) ? price * pips : price;
  }

  private int position() {
    return tracker.position();
  }

  /** Niveau (prix / pips) de la position, NaN si à plat ou si le prix d'entrée est inconnu. */
  private double positionLevel() {
    double avg = tracker.averagePrice();
    return Double.isNaN(avg) ? Double.NaN : grid.level(avg);
  }

  /** Ordre de sortie d'une poignée : vente pour un long, achat pour un short. */
  private static Mode exitMode(Handle handle, int position) {
    return Mode.of(position < 0, handle == Handle.SL);
  }

  /** TP pour une limite de sortie, SL pour un stop de sortie, null sinon. */
  private static Handle roleOf(OrderInfo order, int position) {
    if (position == 0 || order.isBuy != (position < 0)) {
      return null;
    }
    if (order.type == OrderType.LMT) {
      return Handle.TP;
    }
    if (order.type == OrderType.STP || order.type == OrderType.STP_LMT) {
      return Handle.SL;
    }
    return null;
  }

  /** Contrats que cette poignée peut encore couvrir ; 0 = poignée masquée. */
  private int remaining(Handle handle, int position) {
    // Ordre envoyé, pas encore affiché
    if (System.currentTimeMillis() - sentAt[handle.ordinal()] < SENT_HIDE_MS) {
      return 0;
    }
    int covered = 0;
    for (OrderInfo order : orders.values()) {
      if (roleOf(order, position) == handle) {
        covered += order.unfilled;
      }
    }
    return Math.max(0, Math.abs(position) - covered);
  }

  /** Vrai si le bouton BE doit être affiché : aucun SL au prix d'entrée à la taille de la position. */
  private boolean needsBreakEven(int position, double pos) {
    if (position == 0 || Double.isNaN(pos)) {
      return false;
    }
    if (System.currentTimeMillis() - beSentAt < SENT_HIDE_MS) {
      return false;
    }
    long beLevel = Math.round(pos);
    int coveredAtBe = 0;
    for (OrderInfo order : orders.values()) {
      if (roleOf(order, position) == Handle.SL) {
        if (Math.round(orderLevel(order)) != beLevel) {
          return true;
        }
        coveredAtBe += order.unfilled;
      }
    }
    return coveredAtBe != Math.abs(position);
  }

  // ======================================================================
  //  Vue : canvas + formes + état souris d'un painter
  // ======================================================================
  private final class View {
    private final ScreenSpaceCanvas heat;
    private final ScreenSpaceCanvas ladder;
    private final List<CanvasShape> heatShapes = new ArrayList<>();
    private final List<CanvasShape> ladderShapes = new ArrayList<>();
    // Dessinées en dernier, au-dessus des lignes
    private final List<CanvasShape> tags = new ArrayList<>();

    /** Crosshair, redessiné seul quand la souris bouge sans changer de cible. */
    private final List<CanvasShape> crossHeat = new ArrayList<>();

    private final List<CanvasShape> crossLadder = new ArrayList<>();

    /** Cible survolée au dernier dessin complet (ordre, poignée) ; "" si aucune. */
    private String lastHover = "";

    /**
     * Étiquettes placées : {niveau, x gauche, x droite, hauteur}, pour éviter les chevauchements.
     */
    private final List<double[]> placed = new ArrayList<>();

    /** Cache d'images : la souris redessine à chaque mouvement. */
    private final Map<String, PreparedImage> lineCache = new HashMap<>();

    private final Map<String, Img> labelCache = new HashMap<>();
    private boolean disposed;

    private volatile int heatW;
    private volatile int heatH;
    private volatile int ladderW;
    private volatile long priceH = 1;

    private volatile boolean mouseIn;
    private volatile int mouseX;
    private volatile double mouseLevel;
    private volatile boolean mouseOnLadder;
    // NaN si Bookmap n'a pas donné d'écart exploitable
    private volatile double measuredPxPerLevel = Double.NaN;

    /** Bords des poignées au dernier dessin ; table figée, remplacée d'un bloc. */
    private volatile Map<Handle, Span> handleSpans = Map.of();

    /** Bords du bouton BE au dernier dessin, null s'il est masqué. */
    private volatile Span beSpan;

    private volatile Handle dragHandle;
    private volatile String dragId;
    private volatile boolean pressBe;
    private volatile double dragLevel;
    private volatile double pressLevel;
    private volatile int pressSign; // sens de la position au clic : +1 long, -1 short
    private volatile boolean dragMoved; // vrai après DRAG_MIN_PX de mouvement

    final ScreenSpacePainter painter =
        new ScreenSpacePainterAdapter() {
          // Changements reçus par lot : un seul redessin dans onMoveEnd
          private boolean dirty;

          @Override
          public void onHeatmapFullPixelsWidth(int width) {
            heatW = width;
            dirty = true;
          }

          @Override
          public void onHeatmapPixelsHeight(int height) {
            heatH = height;
            dirty = true;
          }

          @Override
          public void onHeatmapPriceHeight(long priceHeight) {
            priceH = Math.max(1, priceHeight);
            dirty = true;
          }

          @Override
          public void onRightOfTimelineWidth(int width) {
            ladderW = width;
            dirty = true;
          }

          @Override
          public void onMoveEnd() {
            if (!dirty) {
              return;
            }
            dirty = false;
            redraw();
          }

          @Override
          public void dispose() {
            View.this.dispose();
          }
        };

    View(ScreenSpaceCanvasFactory factory) {
      heat = factory.createCanvas(ScreenSpaceCanvasType.HEATMAP);
      ladder = factory.createCanvas(ScreenSpaceCanvasType.RIGHT_OF_TIMELINE);
      heat.addMouseListener(new Mouse(false));
      ladder.addMouseListener(new Mouse(true));
    }

    private synchronized void dispose() {
      if (disposed) {
        return;
      }
      disposed = true;
      cancelDrag();
      views.remove(this);
      heatShapes.clear();
      ladderShapes.clear();
      crossHeat.clear();
      crossLadder.clear();
      lineCache.clear();
      labelCache.clear();
      heat.dispose();
      ladder.dispose();
    }

    private void cancelDrag() {
      dragHandle = null;
      dragId = null;
      pressBe = false;
      dragMoved = false;
    }

    // ======================================================================
    //  Souris
    // ======================================================================
    private final class Mouse implements CanvasMouseListener {
      /** Vrai pour la zone à droite de la timeline. */
      private final boolean onLadder;

      Mouse(boolean onLadder) {
        this.onLadder = onLadder;
      }

      private void track(CanvasMouseEvent event) {
        mouseOnLadder = onLadder;
        View.this.track(event);
      }

      private Handle handleHere() {
        return onLadder ? handleNear() : null;
      }

      private boolean beHere() {
        return onLadder && beNear();
      }

      @Override
      public int getEventScore(CanvasMouseEvent event) {
        MouseEvent src = event.sourceEvent;
        int id = src.getID();
        if (id == MouseEvent.MOUSE_WHEEL) {
          return MouseModuleScore.NONE.score; // le zoom reste à Bookmap
        }
        if (dragId != null || dragHandle != null || pressBe) {
          return HIGH_SCORE;
        }
        if (id == MouseEvent.MOUSE_MOVED
            || id == MouseEvent.MOUSE_ENTERED
            || id == MouseEvent.MOUSE_EXITED) {
          hovered(event, id);
          return MouseModuleScore.MIN.score; // observer sans prendre la main
        }
        track(event);
        // Sur une étiquette (poignée, BE ou ordre, à droite de la timeline), aucun bouton n'atteint
        // Bookmap : un clic droit y poserait un ordre au prix cliqué. Sur la heatmap, le clic droit
        // reste à Bookmap.
        boolean target = beHere() || handleHere() != null || orderNear() != null;
        if (onLadder && target) {
          return HIGH_SCORE;
        }
        if (!SwingUtilities.isLeftMouseButton(src)) {
          return MouseModuleScore.NONE.score;
        }
        return target ? HIGH_SCORE : MouseModuleScore.NONE.score;
      }

      /**
       * Survol, traité dès getEventScore : avec un score MIN, Bookmap n'appelle pas toujours
       * mouseMoved, et le changement de couleur attendait alors le dessin suivant (jusqu'à
       * plusieurs secondes).
       */
      private void hovered(CanvasMouseEvent event, int id) {
        Component component = event.sourceEvent.getComponent();
        if (id == MouseEvent.MOUSE_EXITED) {
          mouseIn = false;
          if (component != null) {
            component.setCursor(Cursor.getDefaultCursor());
          }
          redraw();
          return;
        }
        track(event);
        String hover = hoverKey();
        if (component != null) {
          int cursor =
              hover.isEmpty()
                  ? Cursor.DEFAULT_CURSOR
                  : hover.equals("BE") ? Cursor.HAND_CURSOR : Cursor.N_RESIZE_CURSOR;
          component.setCursor(Cursor.getPredefinedCursor(cursor));
        }
        // Même cible qu'au dernier dessin : seul le crosshair bouge
        if (hover.equals(lastHover)) {
          redrawCross();
        } else {
          redraw();
        }
      }

      // Déjà traités dans getEventScore (hovered)
      @Override
      public void mouseMoved(CanvasMouseEvent event) {}

      @Override
      public void mouseEntered(CanvasMouseEvent event) {}

      @Override
      public void mouseExited(CanvasMouseEvent event) {}

      @Override
      public void mousePressed(CanvasMouseEvent event) {
        track(event);
        MouseEvent src = event.sourceEvent;
        if (dragId != null || dragHandle != null || pressBe) {
          // Autre bouton pendant un drag : abandon
          if (!SwingUtilities.isLeftMouseButton(src)) {
            cancelDrag();
            redraw();
          }
          return;
        }
        if (!SwingUtilities.isLeftMouseButton(src)) {
          return;
        }
        pressLevel = mouseLevel;
        dragLevel = mouseLevel;
        dragMoved = false;
        pressSign = Integer.signum(position());
        if (beHere()) {
          pressBe = true;
          redraw();
          return;
        }
        Handle hd = handleHere();
        if (hd != null) {
          dragHandle = hd;
        } else {
          OrderInfo order = orderNear();
          if (order != null) {
            dragId = order.orderId;
          }
        }
        redraw();
      }

      @Override
      public void mouseDragged(CanvasMouseEvent event) {
        track(event);
        if (pressBe) {
          redraw();
          return;
        }
        if (dragId == null && dragHandle == null) {
          return;
        }
        dragLevel = mouseLevel;
        if (Math.abs(mouseLevel - pressLevel) * pxPerLevel() >= DRAG_MIN_PX) {
          dragMoved = true;
        }
        redraw();
      }

      @Override
      public void mouseReleased(CanvasMouseEvent event) {
        track(event);
        boolean be = pressBe;
        Handle hd = dragHandle;
        String id = dragId;
        boolean moved = dragMoved;
        cancelDrag();
        if (be) {
          if (SwingUtilities.isLeftMouseButton(event.sourceEvent) && !disposed && running) {
            placeBreakEven(pressSign);
          }
          redraw();
          return;
        }
        if (!SwingUtilities.isLeftMouseButton(event.sourceEvent)
            || !moved
            || disposed
            || !running) {
          redraw(); // clic simple, drag abandonné ou add-on arrêté
          return;
        }
        int target = (int) Math.round(mouseLevel); // prix du relâchement
        if (hd != null) {
          placeExit(hd, target, pressSign);
        }
        OrderInfo order = id == null ? null : orders.get(id);
        if (order != null && target != Math.round(orderLevel(order))) {
          // Même niveau : rien à envoyer
          if (roleOf(order, position()) != null && wrongSide(modeOf(order), target)) {
            exitNow(order, target); // mauvais côté : sortie immédiate
          } else {
            moveOrder(order, target);
          }
        }
        redraw();
      }
    }

    private void track(CanvasMouseEvent event) {
      mouseIn = true;
      mouseX =
          event
              .getX(
                  new CompositeHorizontalCoordinate(CompositeCoordinateBase.PIXEL_ZERO, 0, 0),
                  CoordinateRequestType.PIXELS)
              .compose()
              .pixelsX;
      double level =
          event
              .getY(
                  new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, 0),
                  CoordinateRequestType.DATA)
              .compose()
              .dataY;
      // Pour la souris, la ligne de prix k va de k à k + 1 ; pour le dessin, k est au centre de la
      // ligne. Sans ce recalage, tout (survol, crosshair, prix du drag) est décalé d'une demi-ligne
      // vers le haut.
      mouseLevel = level - 0.5;
      // Échelle mesurée par Bookmap : écart en pixels entre deux niveaux, au lieu de heatH / priceH
      // (arrondi)
      int n =
          Math.max(1, (int) Math.ceil(SCALE_SPAN_PX / Math.max(1e-3, heatH / (double) priceH)));
      double ref = Math.floor(level);
      int y0 = pixelsFrom(event, ref);
      int y1 = pixelsFrom(event, ref + n);
      boolean ok = y0 != Integer.MAX_VALUE && y1 != Integer.MAX_VALUE && y0 != y1;
      measuredPxPerLevel = ok ? Math.abs(y1 - y0) / (double) n : Double.NaN;
    }

    private static int pixelsFrom(CanvasMouseEvent event, double level) {
      return event.getY(
              new CompositeVerticalCoordinate(CompositeCoordinateBase.DATA_ZERO, 0, level),
              CoordinateRequestType.PIXELS)
          .pixelsY;
    }

    private double pxPerLevel() {
      double measured = measuredPxPerLevel;
      return Double.isNaN(measured) ? heatH / (double) priceH : measured;
    }

    /** Distance verticale en pixels entre la souris et ce niveau. */
    private double pxFromMouse(double level) {
      return Math.abs(level - mouseLevel) * pxPerLevel();
    }

    /**
     * Ordre déplaçable le plus proche. À droite de la timeline, la tolérance couvre l'étiquette.
     */
    private OrderInfo orderNear() {
      OrderInfo best = null;
      double bestDist = mouseOnLadder ? LABEL_GRAB_PX : GRAB_PX;
      for (OrderInfo order : orders.values()) {
        if (modeOf(order) == null) {
          continue;
        }
        double dist = pxFromMouse(orderLevel(order));
        if (dist <= bestDist) {
          bestDist = dist;
          best = order;
        }
      }
      return best;
    }

    /**
     * Cible sous le pointeur hors drag : "BE", "H" + poignée, "O" + orderId, ou "". Même règle que
     * le dessin.
     */
    private String hoverKey() {
      if (!mouseIn) {
        return "";
      }
      if (mouseOnLadder) {
        if (beNear()) {
          return "BE";
        }
        Handle handle = handleNear();
        if (handle != null) {
          return "H" + handle;
        }
      }
      OrderInfo order = orderNear();
      return order == null ? "" : "O" + order.orderId;
    }

    /** Centre d'une étiquette de largeur w, gardée dans la zone à droite de la timeline. */
    private int tagCenter(int w) {
      int center = ladderW * tagPos / 100;
      return Math.max(w / 2, Math.min(ladderW - w / 2, center));
    }

    /**
     * Poignée sous le pointeur : dans son rectangle, la marge couvrant l'espace entre deux
     * poignées.
     */
    private Handle handleNear() {
      double pos = positionLevel();
      if (Double.isNaN(pos)) {
        return null;
      }
      double dy = pxFromMouse(pos);
      Map<Handle, Span> spans = handleSpans;
      int x = mouseX;
      int slack = HANDLE_SPACE_PX / 2;
      for (Handle handle : Handle.values()) {
        if (remaining(handle, position()) == 0) {
          continue; // déjà couverte
        }
        Span span = spans.get(handle);
        if (span != null
            && dy <= span.height() / 2.0 + 1
            && x >= span.left() - slack
            && x < span.right() + slack) {
          return handle;
        }
      }
      return null;
    }

    /** Vrai si le pointeur est sur le bouton BE. */
    private boolean beNear() {
      double pos = positionLevel();
      Span span = beSpan;
      if (Double.isNaN(pos) || span == null || !needsBreakEven(position(), pos)) {
        return false;
      }
      double dy = pxFromMouse(pos);
      int x = mouseX;
      int slack = HANDLE_SPACE_PX / 2;
      return dy <= span.height() / 2.0 + 1
          && x >= span.left() - slack
          && x < span.right() + slack;
    }

    // ======================================================================
    //  Dessin
    // ======================================================================
    private synchronized void redraw() {
      ScreenSpaceCanvas h = heat;
      ScreenSpaceCanvas l = ladder;
      if (disposed || heatW <= 0 || heatH <= 0) {
        return;
      }

      heatShapes.forEach(h::removeShape);
      ladderShapes.forEach(l::removeShape);
      heatShapes.clear();
      ladderShapes.clear();
      tags.clear();
      placed.clear();

      // 0) Position ouverte + poignées TP / SL (à gauche) + bouton BE (à droite)
      int position = position();
      double pos = positionLevel();
      boolean moving = dragMoved;
      boolean idle = dragId == null && dragHandle == null && !pressBe;
      boolean hoverBe = false;
      Handle hoverHandle = null;
      if (Double.isNaN(pos)) {
        handleSpans = Map.of();
        beSpan = null;
      }
      if (!Double.isNaN(pos)) {
        int size = Math.abs(position);
        Color pc = position > 0 ? posColor : shortColor;
        int pw = posWidth;
        hLine(pos, pc, posDashed, pw);
        // PnL au prix de sortie : bid pour un long, ask pour un short
        String pnl =
            bboKnown()
                ? "  " + pnl(position > 0 ? bestBid : bestAsk, pos, size, position, posTicks)
                : "";
        Img posImg =
            labelImg(
                size + (position > 0 ? " LONG" : " SHORT") + "  " + fmt(pos) + pnl,
                idleFill(posOpacity));
        // Poignées TP / SL à gauche de l'étiquette de la position, bouton BE à droite
        Map<Handle, Span> spans = new EnumMap<>(Handle.class);
        int handlesW = 0;
        for (Handle hd : Handle.values()) {
          if (remaining(hd, position) > 0) {
            handlesW += labelImg(hd.toString(), labelBg).w() + HANDLE_SPACE_PX;
          }
        }
        boolean showBe = needsBreakEven(position, pos);
        Img beImg = showBe ? labelImg("BE", labelBg) : null;
        int rightW = showBe ? HANDLE_SPACE_PX + beImg.w() : 0;
        int maxPosX = Math.max(handlesW, ladderW - posImg.w() - rightW);
        int posX = Math.max(handlesW, Math.min(maxPosX, tagCenter(posImg.w()) - posImg.w() / 2));
        int nextX = posX - handlesW;
        for (Handle hd : Handle.values()) {
          if (remaining(hd, position) == 0) {
            continue;
          }
          Img handle = labelImg(hd.toString(), labelBg);
          spans.put(hd, new Span(nextX, nextX + handle.w(), handle.h()));
          nextX += handle.w() + HANDLE_SPACE_PX;
        }
        handleSpans = Map.copyOf(spans);
        int beX = posX + posImg.w() + HANDLE_SPACE_PX;
        beSpan = showBe ? new Span(beX, beX + beImg.w(), beImg.h()) : null;
        place(pos, posImg, posX);
        if (idle && mouseIn && mouseOnLadder) {
          hoverBe = beNear();
          if (!hoverBe) {
            hoverHandle = handleNear();
          }
        }
        if (showBe) {
          Color beFill = (pressBe || hoverBe) ? hotFill() : idleFill(slOpacity);
          place(pos, labelImg("BE", beFill), beX);
        }
        Handle hover = dragHandle != null ? dragHandle : hoverHandle;
        for (Handle hd : Handle.values()) {
          int left = remaining(hd, position);
          if (left == 0) {
            continue; // position déjà couverte
          }
          if (hd == dragHandle && moving) {
            int lvl = (int) Math.round(dragLevel);
            Mode em = exitMode(hd, position);
            if (wrongSide(em, lvl)) {
              // Mauvais côté : sortie immédiate (insideLevel)
              int inside = insideLevel(em.buy);
              hLine(lvl, colorOf(hd), true, Math.max(2, widthOf(hd)));
              tag(
                  lvl,
                  left
                      + " "
                      + insideName(em.buy)
                      + "  "
                      + fmt(inside)
                      + "  "
                      + distance(inside, pos, left, position),
                  hotFill());
            } else {
              hLine(lvl, colorOf(hd), true, Math.max(2, widthOf(hd)));
              tag(
                  lvl,
                  left + " " + hd + "  " + fmt(lvl) + "  " + distance(lvl, pos, left, position),
                  hotFill());
            }
          } else {
            Color fill = hotFill();
            if (hd != hover) {
              // Au repos : opacité du rôle
              fill = idleFill(opacityOf(hd));
            }
            // Couverte entre-temps : rien à afficher
            Span span = spans.get(hd);
            if (span != null) {
              place(pos, labelImg(hd.toString(), fill), span.left());
            }
          }
        }
      }

      OrderInfo hoverOrder = !hoverBe && hoverHandle == null && idle && mouseIn ? orderNear() : null;

      // 1) Ordres actifs : une sortie s'affiche en TP ou SL ; l'ordre survolé est mis en avant
      for (OrderInfo order : orders.values()) {
        if (Double.isNaN(orderLevel(order))) {
          continue; // ordre au marché : pas de ligne
        }
        boolean dragging = moving && order.orderId.equals(dragId);
        boolean hot =
            order.orderId.equals(dragId)
                || (hoverOrder != null && order.orderId.equals(hoverOrder.orderId));
        double lvl = dragging ? Math.round(dragLevel) : orderLevel(order);
        boolean stop = order.type == OrderType.STP || order.type == OrderType.STP_LMT;
        Handle role = Double.isNaN(pos) ? null : roleOf(order, position);
        if (dragging && role != null && wrongSide(modeOf(order), (int) lvl)) {
          // TP / SL lâché du mauvais côté : sortie immédiate au meilleur prix (exitNow)
          int inside = insideLevel(order.isBuy);
          hLine(lvl, colorOf(role), true, Math.max(2, widthOf(role)));
          tag(
              lvl,
              order.unfilled
                  + " "
                  + insideName(order.isBuy)
                  + "  "
                  + fmt(inside)
                  + "  "
                  + distance(inside, pos, order.unfilled, position),
              hotFill());
          continue;
        }
        Color c = role != null ? colorOf(role) : order.isBuy ? BUY : SELL;
        String name =
            role != null ? role.toString() : (order.isBuy ? "BUY " : "SELL ") + order.type;
        int w = role != null ? widthOf(role) : 1;
        hLine(lvl, c, role != null ? dashedOf(role) : stop, hot ? w + 1 : w);
        String dist =
            role != null ? "  " + distance(lvl, pos, order.unfilled, position) : "";
        Color tagColor = hot ? hotFill() : role != null ? idleFill(opacityOf(role)) : labelBg;
        tag(lvl, order.unfilled + " " + name + "  " + fmt(lvl) + dist, tagColor);
      }

      for (CanvasShape t : tags) {
        add(ladderShapes, l, t);
      }

      // 2) Crosshair, par-dessus le reste
      // La cible dessinée, et non une nouvelle lecture de la souris qui a pu bouger pendant le
      // dessin
      lastHover =
          hoverBe
              ? "BE"
              : hoverHandle != null
                  ? "H" + hoverHandle
                  : hoverOrder != null ? "O" + hoverOrder.orderId : "";
      redrawCross();
    }

    /** Crosshair seul : ligne, trait vertical sur la heatmap, prix calé contre la timeline. */
    private synchronized void redrawCross() {
      if (disposed || heatW <= 0 || heatH <= 0) {
        return;
      }
      crossHeat.forEach(heat::removeShape);
      crossLadder.forEach(ladder::removeShape);
      crossHeat.clear();
      crossLadder.clear();
      if (!mouseIn || dragId != null || dragHandle != null || pressBe) {
        return;
      }
      double level = mouseLevel;
      add(crossHeat, heat, hLineShape(heatW, level, CROSS, true, 1));
      if (!mouseOnLadder) {
        add(
            crossHeat,
            heat,
            icon(
                line(1, heatH, CROSS, false),
                px(mouseX),
                pxY(0),
                px(mouseX + 1),
                pxY(heatH)));
      }
      long price = Math.round(level);
      Img img = labelImg(fmt(price), CROSS_TAG);
      add(
          crossLadder,
          ladder,
          icon(
              img.image(),
              px(0),
              dataY(price, -img.h() / 2),
              px(img.w()),
              dataY(price, img.h() - img.h() / 2)));
    }

    private void hLine(double level, Color c, boolean dashed, int thick) {
      add(heatShapes, heat, hLineShape(heatW, level, c, dashed, thick));
      if (ladderW > 0) {
        add(ladderShapes, ladder, hLineShape(ladderW, level, c, dashed, thick));
      }
    }

    private CanvasShape hLineShape(int width, double level, Color c, boolean dashed, int thick) {
      return icon(
          line(width, thick, c, dashed),
          px(0),
          dataY(level, -thick / 2 - (thick % 2)),
          px(width),
          dataY(level, thick / 2));
    }

    /**
     * Étiquette à droite de la timeline, décalée à droite si elle en chevauche une autre.
     *
     * @param fill fond de l'étiquette
     */
    private void tag(double level, String text, Color fill) {
      Img img = labelImg(text, fill);
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
      placed.add(new double[] {level, x, x + img.w(), img.h()});
      tags.add(
          icon(
              img.image(),
              px(x),
              dataY(level, -img.h() / 2),
              px(x + img.w()),
              dataY(level, img.h() - img.h() / 2)));
    }

    private PreparedImage line(int w, int h, Color c, boolean dashed) {
      if (lineCache.size() > 64) {
        lineCache.clear();
      }
      return lineCache.computeIfAbsent(
          w + "|" + h + "|" + c.getRGB() + "|" + dashed,
          k -> new PreparedImage(dashed ? dashed(w, h, c) : solid(w, h, c)));
    }

    private Img labelImg(String text, Color fill) {
      if (labelCache.size() > 256) {
        labelCache.clear();
      }
      String key = fill.getRGB() + "|" + text;
      return labelCache.computeIfAbsent(
          key,
          k -> {
            BufferedImage img = label(text, fill);
            return new Img(new PreparedImage(img), img.getWidth(), img.getHeight());
          });
    }
  } // fin View

  // ======================================================================
  //  Ordres
  // ======================================================================
  /**
   * Pose l'ordre de sortie d'une poignée pour la partie non couverte de la position. Du mauvais
   * côté du marché, c'est une sortie immédiate (insideLevel).
   *
   * @param pressSign sens de la position au clic ; s'il a changé, rien n'est envoyé
   */
  private void placeExit(Handle handle, int level, int pressSign) {
    if (!running) {
      return;
    }
    int pos = position(); // lu une fois : taille et sens cohérents
    if (pos == 0 || Integer.signum(pos) != pressSign || !bboKnown()) {
      return;
    }
    Mode mode = exitMode(handle, pos);
    // Mauvais côté du marché : sortie immédiate (insideLevel)
    boolean inside = wrongSide(mode, level);
    if (inside) {
      mode = Mode.of(mode.buy, false);
      level = insideLevel(mode.buy);
    }
    int size = remaining(handle, pos);
    if (size == 0) {
      return;
    }
    double price = grid.price(level);
    SimpleOrderSendParametersBuilder builder =
        new SimpleOrderSendParametersBuilder(alias, mode.buy, size);
    if (mode.stop) {
      builder.setStopPrice(price);
    } else {
      builder.setLimitPrice(price);
    }
    builder.setClientId(handle.prefix() + UUID.randomUUID()); // pour reconnaître l'ordre ensuite
    sentAt[handle.ordinal()] = System.currentTimeMillis();
    double posLvl = positionLevel();
    if (handle == Handle.SL && !Double.isNaN(posLvl) && level == Math.round(posLvl)) {
      beSentAt = System.currentTimeMillis();
    }
    api.sendOrder(builder.build());
    String kind = inside ? insideName(mode.buy) : mode.label;
    Log.info("Trade Pro: " + handle + " " + kind + " x" + size + " @ " + fmt(level) + " sent");
  }

  /**
   * Place le Stop Loss au prix de la position (Break Even), à la taille de la position, sans créer
   * un second ordre s'il en existe déjà un.
   */
  private void placeBreakEven(int pressSign) {
    if (!running) {
      return;
    }
    int pos = position();
    double posLvl = positionLevel();
    if (pos == 0 || Double.isNaN(posLvl) || Integer.signum(pos) != pressSign || !bboKnown()) {
      return;
    }
    int beLevel = (int) Math.round(posLvl);
    int size = Math.abs(pos);
    OrderInfo existingSl = null;
    for (OrderInfo order : orders.values()) {
      if (roleOf(order, pos) == Handle.SL) {
        existingSl = order;
        break;
      }
    }
    if (existingSl != null) {
      ownOrders.put(existingSl.orderId, Boolean.TRUE);
      if (wrongSide(modeOf(existingSl), beLevel)) {
        pendingExits.put(existingSl.orderId, size);
        api.updateOrder(new OrderCancelParameters(existingSl.orderId));
        Log.info(
            "Trade Pro: "
                + existingSl.orderId
                + " cancelled, "
                + insideName(existingSl.isBuy)
                + " x"
                + size
                + " to follow (BE @ "
                + fmt(beLevel)
                + ")");
      } else {
        if (existingSl.unfilled != size) {
          api.updateOrder(new OrderResizeParameters(existingSl.orderId, size));
          Log.info(
              "Trade Pro: SL "
                  + existingSl.orderId
                  + " resized "
                  + existingSl.unfilled
                  + " -> "
                  + size);
        }
        if (Math.round(orderLevel(existingSl)) != beLevel) {
          moveOrder(existingSl, beLevel);
        }
      }
      beSentAt = System.currentTimeMillis();
      return;
    }
    placeExit(Handle.SL, beLevel, pressSign);
    beSentAt = System.currentTimeMillis();
  }

  /**
   * Prix d'une sortie immédiate, TP ou SL lâché du mauvais côté : SELL BID pour un long, BUY ASK
   * pour un short.
   */
  private int insideLevel(boolean buy) {
    return buy ? bestAsk : bestBid;
  }

  private static String insideName(boolean buy) {
    return buy ? "BUY ASK" : "SELL BID";
  }

  /**
   * TP ou SL déjà posé, lâché du mauvais côté : sortie immédiate au meilleur prix. Une limite est
   * déplacée au BID / ASK ; un stop ne peut pas devenir une limite, il est annulé et la limite part
   * à la confirmation de l'annulation (onOrderUpdated), jamais avant : un stop exécuté entre-temps
   * ne donne pas de seconde sortie.
   */
  private void exitNow(OrderInfo order, int level) {
    if (!running) {
      return;
    }
    if (order.type == OrderType.LMT) {
      int inside = insideLevel(order.isBuy);
      api.updateOrder(new OrderMoveParameters(order.orderId, Double.NaN, grid.price(inside)));
      Log.info(
          "Trade Pro: "
              + order.orderId
              + " moved to "
              + insideName(order.isBuy)
              + " "
              + fmt(inside));
      return;
    }
    pendingExits.put(order.orderId, order.unfilled);
    api.updateOrder(new OrderCancelParameters(order.orderId));
    Log.info(
        "Trade Pro: "
            + order.orderId
            + " cancelled, "
            + insideName(order.isBuy)
            + " x"
            + order.unfilled
            + " to follow (released @ "
            + fmt(level)
            + ")");
  }

  /** Annulation confirmée d'un stop lâché du mauvais côté : envoie la limite au meilleur prix. */
  private void sendPendingExit(boolean buy, int size) {
    int pos = position();
    if (!running || pos == 0 || buy != (pos < 0) || !bboKnown()) {
      return; // plus rien à sortir
    }
    int qty = Math.min(size, Math.abs(pos));
    int level = insideLevel(buy);
    SimpleOrderSendParametersBuilder builder =
        new SimpleOrderSendParametersBuilder(alias, buy, qty);
    builder.setLimitPrice(grid.price(level));
    // Limite de sortie : suivie comme un TP
    builder.setClientId(Handle.TP.prefix() + UUID.randomUUID());
    api.sendOrder(builder.build());
    Log.info("Trade Pro: " + insideName(buy) + " x" + qty + " @ " + fmt(level) + " sent");
  }

  /** Vrai si le carnet est connu (un 0 / 0 d'attente ne compte pas). */
  private boolean bboKnown() {
    int bid = bestBid;
    int ask = bestAsk;
    return bid != Integer.MIN_VALUE && ask != Integer.MAX_VALUE && ask > bid;
  }

  /**
   * Vrai si le carnet est connu et que ce niveau est du mauvais côté du marché (exécution
   * immédiate).
   */
  private boolean wrongSide(Mode mode, int level) {
    if (mode == null || !bboKnown()) {
      return false;
    }
    boolean rests =
        mode.stop
            ? (mode.buy ? level > bestAsk : level < bestBid)
            : (mode.buy ? level < bestAsk : level > bestBid);
    return !rests;
  }

  /** Sens et type d'un ordre existant, null s'il n'est pas déplaçable (ni limite, ni stop). */
  private static Mode modeOf(OrderInfo order) {
    if (order.type == OrderType.LMT) {
      return Mode.of(order.isBuy, false);
    }
    if (order.type == OrderType.STP || order.type == OrderType.STP_LMT) {
      return Mode.of(order.isBuy, true);
    }
    return null;
  }

  private void moveOrder(OrderInfo order, int level) {
    if (!running) {
      return;
    }
    double p = grid.price(level);
    if (order.type == null) {
      return;
    }
    switch (order.type) {
      case STP_LMT -> {
        double shift = p - order.stopPrice; // la limite garde son écart au stop
        api.updateOrder(new OrderMoveParameters(order.orderId, p, order.limitPrice + shift));
      }
      case STP -> api.updateOrder(new OrderMoveParameters(order.orderId, p, Double.NaN));
      case LMT -> api.updateOrder(new OrderMoveParameters(order.orderId, Double.NaN, p));
      default -> {
        return;
      }
    }
    double posLvl = positionLevel();
    if (roleOf(order, position()) == Handle.SL && !Double.isNaN(posLvl)) {
      beSentAt = level == Math.round(posLvl) ? System.currentTimeMillis() : 0;
    }
    Log.info(
        "Trade Pro: " + order.orderId + " moved " + fmt(orderLevel(order)) + " -> " + fmt(level));
  }

  private double orderLevel(OrderInfo order) {
    boolean stop = order.type == OrderType.STP || order.type == OrderType.STP_LMT;
    return grid.level(stop ? order.stopPrice : order.limitPrice);
  }

  /** Gain / perte d'un TP / SL, dans l'unité réglée pour les TP / SL. */
  private String distance(double level, double posLevel, int size, int position) {
    return pnl(level, posLevel, size, position, exitTicks);
  }

  /**
   * Gain / perte si la position sort à ce niveau, en argent ou en ticks (ticks si la valeur du
   * point est inconnue).
   */
  private String pnl(double level, double posLevel, int size, int position, boolean inTicks) {
    double ticks = (level - posLevel) * (position > 0 ? 1 : -1);
    if (inTicks || Double.isNaN(multiplier)) {
      return String.format("%+.0f t", ticks);
    }
    double pnl = ticks * pips * multiplier * size;
    return (pnl < 0 ? "-$" : "+$") + String.format("%,.2f", Math.abs(pnl));
  }

  private String fmt(double level) {
    return grid.format(level);
  }

  private static void add(List<CanvasShape> list, ScreenSpaceCanvas canvas, CanvasShape shape) {
    canvas.addShape(shape);
    list.add(shape);
  }

  private static CanvasIcon icon(
      PreparedImage img,
      HorizontalCoordinate x1,
      VerticalCoordinate y1,
      HorizontalCoordinate x2,
      VerticalCoordinate y2) {
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
    BufferedImage img =
        new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setColor(c);
    g.fillRect(0, 0, img.getWidth(), img.getHeight());
    g.dispose();
    return img;
  }

  private static BufferedImage dashed(int w, int h, Color c) {
    BufferedImage img =
        new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setColor(c);
    for (int x = 0; x < w; x += 10) {
      g.fillRect(x, 0, 6, h);
    }
    g.dispose();
    return img;
  }

  private static String readVersion() {
    try (InputStream in = TradePro.class.getResourceAsStream("version.txt")) {
      if (in != null) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
      }
    } catch (Exception e) {
      // version inconnue
    }
    return "?";
  }

  /** Police de Bookmap (Roboto), sinon police système. */
  private static Font uiFont(float size) {
    try (InputStream in =
        Log.class.getResourceAsStream("/resources/fonts/roboto/RobotoBm-Medium.ttf")) {
      if (in != null) {
        return Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(size);
      }
    } catch (Exception e) {
      // police système
    }
    return new Font(Font.SANS_SERIF, Font.BOLD, (int) size);
  }

  /** Étiquette au style de Bookmap : rectangle arrondi, texte blanc. */
  private static BufferedImage label(String text, Color fill) {
    BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    Graphics2D pg = probe.createGraphics();
    FontMetrics fm = pg.getFontMetrics(FONT);
    pg.dispose();
    // Mêmes dimensions que les étiquettes d'ordres de Bookmap (« 8 LMT » : 52 x 21 px)
    int padX = 6;
    int padY = 2;
    int w = fm.stringWidth(text) + 2 * padX;
    int h = fm.getHeight() + 2 * padY;

    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(fill);
    g.fillRoundRect(0, 0, w, h, 6, 6);
    // Fond translucide : texte atténué aussi
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
    colors.add(
        colorRow(
            posColor,
            POS,
            "Long",
            c -> {
              posColor = c;
              settingsChanged();
            }));
    colors.add(gap());
    colors.add(
        colorRow(
            shortColor,
            POS_SHORT,
            "Short",
            c -> {
              shortColor = c;
              settingsChanged();
            }));
    colors.add(gap());
    colors.add(
        colorRow(
            tpColor,
            Handle.TP.color,
            "Take profit",
            c -> {
              tpColor = c;
              settingsChanged();
            }));
    colors.add(gap());
    colors.add(
        colorRow(
            slColor,
            Handle.SL.color,
            "Stop loss",
            c -> {
              slColor = c;
              settingsChanged();
            }));
    colors.add(gap());
    colors.add(
        colorRow(
            labelBg,
            LABEL_BG,
            "Label",
            c -> {
              labelBg = c;
              settingsChanged();
            }));
    colors.add(gap());
    colors.add(
        colorRow(
            labelHot,
            LABEL_BG_HOT,
            "Label hover",
            c -> {
              labelHot = new Color(c.getRGB());
              settingsChanged();
            }));

    JPanel lines = new JPanel(new GridBagLayout());
    lines.setOpaque(false);
    lineHeader(lines);
    lineRow(
        lines,
        1,
        "Position",
        styleBox(posDashed, d -> posDashed = d),
        widthBox(posWidth, w -> posWidth = w),
        opacityBox(posOpacity, v -> posOpacity = v));
    lineRow(
        lines,
        2,
        "Take profit",
        styleBox(tpDashed, d -> tpDashed = d),
        widthBox(tpWidth, w -> tpWidth = w),
        opacityBox(tpOpacity, v -> tpOpacity = v));
    lineRow(
        lines,
        3,
        "Stop loss",
        styleBox(slDashed, d -> slDashed = d),
        widthBox(slWidth, w -> slWidth = w),
        opacityBox(slOpacity, v -> slOpacity = v));

    String caption = "Left to right: ";
    JLabel value = new JLabel(caption + tagPos);
    JSlider slider = new JSlider(0, 100, tagPos);
    slider.setToolTipText(String.valueOf(tagPos));
    slider.addChangeListener(
        e -> {
          tagPos = slider.getValue();
          value.setText(caption + tagPos); // visible pendant le glissement
          slider.setToolTipText(String.valueOf(tagPos));
          if (slider.getValueIsAdjusting()) {
            redrawAll(); // aperçu ; enregistré au relâchement
          } else {
            settingsChanged();
          }
        });
    JPanel tagsPanel = pair(value, slider);

    JPanel pnlPanel = verticalBox();
    pnlPanel.add(pair(new JLabel("Position"), unitBox(posTicks, t -> posTicks = t)));
    pnlPanel.add(gap());
    pnlPanel.add(
        pair(new JLabel("Take profit / Stop loss"), unitBox(exitTicks, t -> exitTicks = t)));

    // L'API ne donne pas la position déjà ouverte à l'activation
    JPanel warning = verticalBox();
    warning.add(new FittingLabel("Enable Trade Pro only when you are flat.", true));
    warning.add(gap());
    warning.add(
        new FittingLabel(
            "It cannot see a position that was opened before it was enabled.", false));

    JPanel about = verticalBox();
    about.add(new FittingLabel("Trade Pro " + VERSION, false));
    return new StrategyPanel[] {
      section("Important", warning, 8, TEXT_INDENT),
      section("Colors", colors, 4, 0),
      section("Lines and labels", lines, 4, TEXT_INDENT),
      section("Profit and loss value", pnlPanel, 4, TEXT_INDENT),
      section("Label position", tagsPanel, 4, TEXT_INDENT),
      section("About", about, 4, TEXT_INDENT)
    };
  }

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
    String[] heads = {"", "Line style", "Line width", "Label opacity %"};
    for (int col = 0; col < heads.length; col++) {
      lines.add(new JLabel(heads[col]), lineCell(0, col));
    }
  }

  private static void lineRow(
      JPanel lines,
      int row,
      String name,
      JComponent style,
      JComponent width,
      JComponent opacity) {
    lines.add(new JLabel(name), lineCell(row, 0));
    lines.add(style, lineCell(row, 1));
    lines.add(width, lineCell(row, 2));
    lines.add(opacity, lineCell(row, 3));
  }

  /**
   * Colonne du nom extensible, contrôles à leur largeur : une grille égale rognait « Label opacity
   * % ».
   */
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
   * Section adaptée au dialogue de Bookmap, sans défilement horizontal. Minimale nulle : Bookmap la
   * recopie sur l'ascenseur, une minimale pleine rognait le bas. Second passage de mise en page à
   * l'affichage.
   */
  private static final class Section extends StrategyPanel {
    private static final int MAX_ASKED_WIDTH = 372;

    Section(String title) {
      super(title, new BorderLayout());
    }

    /**
     * Largeur demandée plafonnée à 372 px, comme AVWAP : au-delà, le GridBagLayout de Bookmap passe
     * toutes les sections à leur taille minimale et le panneau s'ouvre vide.
     */
    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      return new Dimension(Math.min(preferred.width, MAX_ASKED_WIDTH), preferred.height);
    }

    @Override
    public Dimension getMinimumSize() {
      return new Dimension(0, 0);
    }

    @Override
    public Dimension getMaximumSize() {
      return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public void addNotify() {
      super.addNotify();
      SwingUtilities.invokeLater(
          () -> {
            revalidate();
            if (getParent() != null) {
              getParent().revalidate();
            }
          });
    }
  }

  /** Ligne de couleur de Bookmap, sans le libellé verrouillé à 250 px qui rognait les boutons. */
  private static final class ColorRow extends ColorsConfigItem {
    ColorRow(Color current, Color fallback, String name, Consumer<Color> onPick) {
      super(current, fallback, name, onPick);
      setAlignmentX(Component.LEFT_ALIGNMENT);
      JLabel label = getLabel();
      if (label != null) {
        label.setPreferredSize(null);
      }
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      Dimension minimum = super.getMinimumSize();
      return new Dimension(preferred.width, Math.max(preferred.height, minimum.height));
    }
  }

  private ColorRow colorRow(Color current, Color fallback, String name, Consumer<Color> onPick) {
    return new ColorRow(current, fallback, name, onPick);
  }

  /** Texte qui revient à la ligne quand le panneau rétrécit, et ajuste sa hauteur. */
  private static final class FittingLabel extends JLabel {
    FittingLabel(String text, boolean bold) {
      super("<html>" + text + "</html>");
      setAlignmentX(Component.LEFT_ALIGNMENT);
      if (bold) {
        setFont(getFont().deriveFont(Font.BOLD));
      }
    }

    @Override
    public Dimension getPreferredSize() {
      Dimension preferred = super.getPreferredSize();
      int width = getWidth();
      if (width <= 0 && getParent() != null) {
        width = getParent().getWidth() - parentInsets();
      }
      int inner = width - getInsets().left - getInsets().right;
      if (inner <= 0 || inner >= preferred.width) {
        return preferred;
      }
      javax.swing.text.View html =
          (javax.swing.text.View) getClientProperty(BasicHTML.propertyKey);
      if (html == null) {
        return preferred;
      }
      html.setSize(inner, Integer.MAX_VALUE);
      int height =
          (int) Math.ceil(html.getPreferredSpan(javax.swing.text.View.Y_AXIS))
              + getInsets().top
              + getInsets().bottom;
      return new Dimension(inner, Math.max(preferred.height, height));
    }

    private int parentInsets() {
      if (!(getParent() instanceof JComponent parent)) {
        return 0;
      }
      Insets insets = parent.getInsets();
      return insets.left + insets.right;
    }
  }

  /** Liste « Solid / Dashed » pour une ligne. */
  private JComboBox<String> styleBox(boolean dashed, Consumer<Boolean> setter) {
    JComboBox<String> box = new JComboBox<>(new String[] {"Solid", "Dashed"});
    box.setSelectedIndex(dashed ? 1 : 0);
    box.addActionListener(
        e -> {
          setter.accept(box.getSelectedIndex() == 1);
          settingsChanged();
        });
    return box;
  }

  /** Liste « Money / Ticks » pour l'unité d'un gain / perte. */
  private JComboBox<String> unitBox(boolean ticks, Consumer<Boolean> setter) {
    JComboBox<String> box = new JComboBox<>(new String[] {"Money", "Ticks"});
    box.setSelectedIndex(ticks ? 1 : 0);
    box.addActionListener(
        e -> {
          setter.accept(box.getSelectedIndex() == 1);
          settingsChanged();
        });
    return box;
  }

  /** Opacité du fond d'une étiquette, de 0 à 100 %, par pas de 5. */
  private JSpinner opacityBox(int percent, IntConsumer setter) {
    JSpinner box = new JSpinner(new SpinnerNumberModel(percent, 0, 100, 5));
    box.addChangeListener(
        e -> {
          setter.accept((Integer) box.getValue());
          settingsChanged();
        });
    return box;
  }

  /** Épaisseur d'une ligne, de 1 à WIDTH_MAX pixels. */
  private JSpinner widthBox(int width, IntConsumer setter) {
    JSpinner box = new JSpinner(new SpinnerNumberModel(width, 1, WIDTH_MAX, 1));
    box.addChangeListener(
        e -> {
          setter.accept((Integer) box.getValue());
          settingsChanged();
        });
    return box;
  }

  private void settingsChanged() {
    saveSettings();
    redrawAll();
  }

  private void saveSettings() {
    if (api == null) {
      return; // panneau ouvert avant l'activation
    }
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

  // ======================================================================
  //  Suivi de la position et grille de prix
  // ======================================================================
  /**
   * Position d'un instrument, en prix réels, sans dépendance à Bookmap :
   *
   * <ul>
   *   <li>statut du fournisseur : fait foi dès qu'il annonce une position non nulle
   *   <li>sinon exécutions (tous les fournisseurs n'envoient pas de statut)
   * </ul>
   */
  private static final class PositionTracker {

    private int position; // > 0 long, < 0 short
    private double averagePrice = Double.NaN;
    private double lastExecutionPrice = Double.NaN;
    private boolean statusDriven;

    /** Sens de chaque ordre, pour signer ses exécutions. */
    private final Map<String, Boolean> sides = new HashMap<>();

    /** Exécutions comptées : Bookmap peut les rejouer. */
    private final Set<String> seen = new HashSet<>();

    /** Exécutions arrivées avant leur ordre : {prix, taille}. */
    private final Map<String, List<double[]>> waiting = new HashMap<>();

    synchronized void reset() {
      position = 0;
      averagePrice = Double.NaN;
      lastExecutionPrice = Double.NaN;
      statusDriven = false;
      sides.clear();
      seen.clear();
      waiting.clear();
    }

    synchronized int position() {
      return position;
    }

    /** Prix moyen d'entrée (prix réel), NaN à plat ou s'il est inconnu. */
    synchronized double averagePrice() {
      return position == 0 ? Double.NaN : averagePrice;
    }

    /** Enregistre le sens d'un ordre et compte les exécutions qui l'attendaient. */
    synchronized void onOrder(String orderId, boolean buy) {
      sides.put(orderId, buy);
      List<double[]> late = waiting.remove(orderId);
      if (late == null) {
        return;
      }
      for (double[] execution : late) {
        apply(buy, execution[0], (int) execution[1]);
      }
    }

    /**
     * Enregistre une exécution d'ordre.
     *
     * @param executionKey identifiant unique, ou null (toujours comptée)
     */
    synchronized void onExecution(String orderId, String executionKey, double price, int size) {
      if (size <= 0) {
        return;
      }
      if (executionKey != null && !seen.add(executionKey)) {
        return; // déjà comptée
      }
      Boolean buy = sides.get(orderId);
      if (buy == null) {
        waiting.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new double[] {price, size});
        return;
      }
      apply(buy, price, size);
    }

    /**
     * Statut du fournisseur.
     *
     * @param price prix moyen réel, NaN ou &lt;= 0 s'il n'est pas fourni
     */
    synchronized void onStatus(int newPosition, double price) {
      if (newPosition != 0) {
        statusDriven = true;
      }
      if (!statusDriven) {
        return; // aucune position annoncée : statut ignoré
      }
      int before = position;
      boolean known = !Double.isNaN(price) && !Double.isInfinite(price) && price > 0;
      if (newPosition == 0) {
        averagePrice = Double.NaN;
      } else if (known) {
        averagePrice = price;
      } else if (before == 0 || (before > 0) != (newPosition > 0) || Double.isNaN(averagePrice)) {
        averagePrice = lastExecutionPrice; // prix moyen absent : dernière exécution
      }
      position = newPosition;
    }

    private void apply(boolean buy, double price, int size) {
      lastExecutionPrice = price;
      if (statusDriven) {
        return; // taille donnée par le statut
      }
      int before = position;
      int after = before + (buy ? size : -size);
      if (after == 0) {
        averagePrice = Double.NaN;
      } else if (before == 0 || (before > 0) != (after > 0)) {
        averagePrice = price; // ouverture ou retournement
      } else if (Math.abs(after) > Math.abs(before)) {
        // Renfort
        averagePrice = (averagePrice * Math.abs(before) + price * size) / Math.abs(after);
      }
      position = after;
    }
  }

  /** Conversions entre niveaux de prix (prix / pips) et prix réels, sans dépendance à Bookmap. */
  private static final class PriceGrid {

    final double pips;

    /** Nombre de décimales du pas de cotation : 2 pour 0,25, 3 pour 0,125, 0 pour 5. */
    final int decimals;

    private final BigDecimal step;

    PriceGrid(double pips) {
      this.pips = pips;
      this.step = new BigDecimal(Double.toString(pips)).stripTrailingZeros();
      this.decimals = Math.max(0, step.scale());
    }

    /** Prix réel d'un niveau, en décimal (3 x 0,1 = 0,3). */
    double price(long level) {
      return step.multiply(BigDecimal.valueOf(level)).doubleValue();
    }

    /** Niveau (éventuellement fractionnaire) d'un prix réel. */
    double level(double price) {
      return price / pips;
    }

    /** Prix affiché d'un niveau, avec toutes les décimales du pas de cotation. */
    String format(double level) {
      return String.format("%." + decimals + "f", level * pips);
    }
  }
}
