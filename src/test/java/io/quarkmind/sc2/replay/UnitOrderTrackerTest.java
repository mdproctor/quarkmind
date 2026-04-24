package io.quarkmind.sc2.replay;

import io.quarkmind.domain.Point2d;
import io.quarkmind.domain.UnitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UnitOrderTrackerTest {

    UnitOrderTracker tracker;

    @BeforeEach
    void setUp() { tracker = new UnitOrderTracker(); }

    @Test
    void unitMovesTowardTargetEachTick() {
        tracker.loadOrders(List.of(
            new UnitOrder("r-1-1", 0L, new Point2d(20, 20), null)
        ));
        Map<String, Point2d> positions = new HashMap<>(Map.of("r-1-1", new Point2d(10, 10)));
        Map<String, UnitType> types    = Map.of("r-1-1", UnitType.PROBE);

        tracker.advance(22L, positions, types);

        Point2d pos = positions.get("r-1-1");
        assertThat(pos.x()).isGreaterThan(10f);
        assertThat(pos.y()).isGreaterThan(10f);
        assertThat(pos.x()).isLessThan(20f);
    }

    @Test
    void unitStopsWithinHalfTileOfTarget() {
        tracker.loadOrders(List.of(
            new UnitOrder("r-1-1", 0L, new Point2d(10.1f, 10.1f), null)
        ));
        Map<String, Point2d> positions = new HashMap<>(Map.of("r-1-1", new Point2d(10, 10)));
        Map<String, UnitType> types    = Map.of("r-1-1", UnitType.PROBE);

        tracker.advance(22L, positions, types);

        Point2d pos = positions.get("r-1-1");
        float dx = pos.x() - 10.1f, dy = pos.y() - 10.1f;
        assertThat(Math.sqrt(dx*dx + dy*dy)).isLessThan(0.5);
    }

    @Test
    void ordersAfterCurrentLoopAreNotApplied() {
        tracker.loadOrders(List.of(
            new UnitOrder("r-1-1", 100L, new Point2d(20, 20), null)
        ));
        Map<String, Point2d> positions = new HashMap<>(Map.of("r-1-1", new Point2d(10, 10)));
        Map<String, UnitType> types    = Map.of("r-1-1", UnitType.PROBE);

        tracker.advance(22L, positions, types);

        assertThat(positions.get("r-1-1").x()).isEqualTo(10f);
    }

    @Test
    void followOrderMovesUnitTowardTarget() {
        tracker.loadOrders(List.of(
            new UnitOrder("r-1-1", 0L, null, "r-2-1")
        ));
        Map<String, Point2d> positions = new HashMap<>(Map.of(
            "r-1-1", new Point2d(10, 10),
            "r-2-1", new Point2d(30, 30)
        ));
        Map<String, UnitType> types = Map.of("r-1-1", UnitType.ZEALOT, "r-2-1", UnitType.PROBE);

        tracker.advance(22L, positions, types);

        Point2d pos = positions.get("r-1-1");
        assertThat(pos.x()).isGreaterThan(10f);
        assertThat(pos.y()).isGreaterThan(10f);
    }

    @Test
    void unknownUnitTypeUsesDefaultSpeed() {
        tracker.loadOrders(List.of(
            new UnitOrder("r-1-1", 0L, new Point2d(100, 100), null)
        ));
        Map<String, Point2d> positions = new HashMap<>(Map.of("r-1-1", new Point2d(10, 10)));
        Map<String, UnitType> types    = Map.of("r-1-1", UnitType.UNKNOWN);

        tracker.advance(22L, positions, types);

        assertThat(positions.get("r-1-1").x()).isGreaterThan(10f);
    }

    @Test
    void newOrderOverridesPreviousOrder() {
        tracker.loadOrders(List.of(
            new UnitOrder("r-1-1",  0L, new Point2d(100, 100), null),
            new UnitOrder("r-1-1", 10L, new Point2d(5, 5),     null)
        ));
        Map<String, Point2d> positions = new HashMap<>(Map.of("r-1-1", new Point2d(10, 10)));
        Map<String, UnitType> types    = Map.of("r-1-1", UnitType.PROBE);

        tracker.advance(22L, positions, types);

        Point2d pos = positions.get("r-1-1");
        assertThat(pos.x()).isLessThan(10f);
        assertThat(pos.y()).isLessThan(10f);
    }
}
