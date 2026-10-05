package com.tradepro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Suivi de la position d'un instrument, sans dépendance à Bookmap pour pouvoir être testé seul.
 *
 * Deux sources :
 *  - le statut du fournisseur (StatusInfo) : dès qu'il a annoncé une position non nulle, il fait foi,
 *    y compris pour un retour à zéro, et les exécutions ne changent plus la taille ;
 *  - les exécutions, tant qu'aucun statut n'a annoncé de position (tous les fournisseurs n'en envoient pas).
 *
 * Tous les prix sont des prix réels.
 */
final class PositionTracker {

    private int position;                       // > 0 long, < 0 short
    private double averagePrice = Double.NaN;
    private double lastExecutionPrice = Double.NaN;
    private boolean statusDriven;

    /** Sens de chaque ordre vu, pour signer les exécutions (qui ne portent que l'orderId). */
    private final Map<String, Boolean> sides = new HashMap<>();
    /** Exécutions déjà comptées : Bookmap peut les rejouer, par exemple à un changement de compte. */
    private final Set<String> seen = new HashSet<>();
    /** Exécutions arrivées avant la mise à jour de leur ordre : {prix, taille}, rejouées quand le sens est connu. */
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

    synchronized int position() { return position; }

    /** Prix moyen d'entrée (prix réel), NaN à plat ou s'il est inconnu. */
    synchronized double averagePrice() { return position == 0 ? Double.NaN : averagePrice; }

    /** Enregistre le sens d'un ordre et compte les exécutions qui l'attendaient. */
    synchronized void onOrder(String orderId, boolean buy) {
        sides.put(orderId, buy);
        List<double[]> late = waiting.remove(orderId);
        if (late == null) return;
        for (double[] e : late) apply(buy, e[0], (int) e[1]);
    }

    /**
     * @param executionKey identifiant unique de l'exécution, ou null s'il est inconnu (elle est alors toujours comptée)
     */
    synchronized void onExecution(String orderId, String executionKey, double price, int size) {
        if (size <= 0) return;
        if (executionKey != null && !seen.add(executionKey)) return;        // déjà comptée
        Boolean buy = sides.get(orderId);
        if (buy == null) {
            waiting.computeIfAbsent(orderId, k -> new ArrayList<>()).add(new double[] { price, size });
            return;
        }
        apply(buy, price, size);
    }

    /**
     * Statut du fournisseur.
     * @param price prix moyen réel, NaN ou &lt;= 0 s'il n'est pas fourni
     */
    synchronized void onStatus(int newPosition, double price) {
        if (newPosition != 0) statusDriven = true;
        if (!statusDriven) return;              // jamais de position annoncée : ce fournisseur ne la suit peut-être pas
        int before = position;
        boolean known = !Double.isNaN(price) && !Double.isInfinite(price) && price > 0;
        if (newPosition == 0) {
            averagePrice = Double.NaN;
        } else if (known) {
            averagePrice = price;
        } else if (before == 0 || (before > 0) != (newPosition > 0) || Double.isNaN(averagePrice)) {
            averagePrice = lastExecutionPrice;   // prix moyen non fourni : la dernière exécution, faute de mieux
        }
        position = newPosition;
    }

    private void apply(boolean buy, double price, int size) {
        lastExecutionPrice = price;
        if (statusDriven) return;                // la taille vient du statut ; l'exécution ne sert qu'au prix
        int before = position;
        int after = before + (buy ? size : -size);
        if (after == 0) {
            averagePrice = Double.NaN;
        } else if (before == 0 || (before > 0) != (after > 0)) {
            averagePrice = price;                                                           // ouverture ou retournement
        } else if (Math.abs(after) > Math.abs(before)) {
            averagePrice = (averagePrice * Math.abs(before) + price * size) / Math.abs(after);     // renfort
        }
        position = after;
    }
}
