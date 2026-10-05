package com.tradepro;

import java.math.BigDecimal;

/**
 * Conversions entre niveaux de prix (prix / pips, l'unité de la heatmap et du BBO) et prix réels.
 * Sans dépendance à Bookmap, pour pouvoir être testé seul.
 */
final class PriceGrid {

    final double pips;
    /** Nombre de décimales du pas de cotation : 2 pour 0,25, 3 pour 0,125, 0 pour 5. */
    final int decimals;
    private final BigDecimal step;

    PriceGrid(double pips) {
        this.pips = pips;
        this.step = new BigDecimal(Double.toString(pips)).stripTrailingZeros();
        this.decimals = Math.max(0, step.scale());
    }

    /** Prix réel d'un niveau entier, calculé en décimal : 3 x 0,1 donne 0,3 et non 0,30000000000000004. */
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
