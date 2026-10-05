package com.tradepro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PositionTrackerTest {

    private final PositionTracker t = new PositionTracker();

    private void buy(String order, String exec, double price, int size) {
        t.onOrder(order, true);
        t.onExecution(order, exec, price, size);
    }

    private void sell(String order, String exec, double price, int size) {
        t.onOrder(order, false);
        t.onExecution(order, exec, price, size);
    }

    @Test
    void flatAtStart() {
        assertEquals(0, t.position());
        assertTrue(Double.isNaN(t.averagePrice()));
    }

    @Test
    void openThenAddAveragesThePrice() {
        buy("o1", "e1", 100, 1);
        buy("o2", "e2", 104, 1);
        assertEquals(2, t.position());
        assertEquals(102, t.averagePrice(), 1e-9);
    }

    @Test
    void partialExitKeepsTheAveragePrice() {
        buy("o1", "e1", 100, 3);
        sell("o2", "e2", 110, 1);
        assertEquals(2, t.position());
        assertEquals(100, t.averagePrice(), 1e-9);
    }

    @Test
    void closingGoesFlat() {
        buy("o1", "e1", 100, 2);
        sell("o2", "e2", 105, 2);
        assertEquals(0, t.position());
        assertTrue(Double.isNaN(t.averagePrice()));
    }

    @Test
    void flipRestartsAtTheFillPrice() {
        buy("o1", "e1", 100, 1);
        sell("o2", "e2", 90, 3);
        assertEquals(-2, t.position());
        assertEquals(90, t.averagePrice(), 1e-9);
    }

    @Test
    void replayedExecutionIsCountedOnce() {
        buy("o1", "e1", 100, 1);
        t.onExecution("o1", "e1", 100, 1);
        assertEquals(1, t.position());
    }

    @Test
    void fillBeforeItsOrderIsCountedWhenTheSideArrives() {
        t.onExecution("o1", "e1", 100, 2);          // sens inconnu : mise de côté, pas perdue
        assertEquals(0, t.position());
        t.onOrder("o1", false);
        assertEquals(-2, t.position());
        assertEquals(100, t.averagePrice(), 1e-9);
    }

    @Test
    void statusWithoutPositionIsIgnoredUntilOneIsReported() {
        buy("o1", "e1", 100, 1);
        t.onStatus(0, Double.NaN);                  // fournisseur qui ne suit peut-être pas la position
        assertEquals(1, t.position());
    }

    @Test
    void statusTakesOverOnceItReportsAPosition() {
        t.onStatus(3, 50);
        assertEquals(3, t.position());
        assertEquals(50, t.averagePrice(), 1e-9);

        buy("o1", "e1", 50, 3);                     // statut puis replay des mêmes exécutions : la taille ne double pas
        assertEquals(3, t.position());

        t.onStatus(0, Double.NaN);                  // retour à plat annoncé par le statut seul
        assertEquals(0, t.position());
    }

    @Test
    void statusWithoutAveragePriceUsesTheLastFill() {
        t.onOrder("o1", true);
        t.onStatus(2, Double.NaN);                  // position annoncée sans prix moyen, aucune exécution vue
        assertTrue(Double.isNaN(t.averagePrice()));
        t.onExecution("o1", "e1", 100, 2);
        t.onStatus(2, Double.NaN);
        assertEquals(100, t.averagePrice(), 1e-9);
    }

    @Test
    void resetForgetsEverything() {
        buy("o1", "e1", 100, 1);
        t.onStatus(1, 100);
        t.reset();
        assertEquals(0, t.position());
        buy("o1", "e1", 100, 1);                    // même identifiant qu'avant : recompté après remise à zéro
        assertEquals(1, t.position());
    }
}
