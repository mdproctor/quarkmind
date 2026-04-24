package io.quarkmind.sc2.replay;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameEventStreamTest {

    static final Path REPLAY =
        Path.of("replays/aiarena_protoss/Nothing_4720936.SC2Replay");

    @Test
    void parsesMoreThanThousandOrders() {
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        assertThat(orders).hasSizeGreaterThan(1000);
    }

    @Test
    void allTargetCoordinatesWithinMapBounds() {
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        for (UnitOrder o : orders) {
            if (o.targetPos() != null) {
                assertThat(o.targetPos().x()).as("x for loop %d", o.loop())
                    .isBetween(0f, 256f);
                assertThat(o.targetPos().y()).as("y for loop %d", o.loop())
                    .isBetween(0f, 256f);
            }
        }
    }

    @Test
    void containsMoveOrders() {
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        assertThat(orders).anyMatch(UnitOrder::isMove);
    }

    @Test
    void allOrdersHaveExactlyOneTarget() {
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        // Every order must have either a position target or a unit target, not both
        assertThat(orders).allMatch(o -> (o.targetPos() != null) != (o.targetUnitTag() != null));
    }

    @Test
    void ordersAreSortedByLoop() {
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        for (int i = 1; i < orders.size(); i++) {
            assertThat(orders.get(i).loop())
                .isGreaterThanOrEqualTo(orders.get(i - 1).loop());
        }
    }

    @Test
    void unitTagsMatchTrackerEventFormat() {
        List<UnitOrder> orders = GameEventStream.parse(REPLAY);
        assertThat(orders).allMatch(o -> o.unitTag().startsWith("r-"));
    }

    @Test
    void throwsForMissingFile() {
        assertThatThrownBy(() -> GameEventStream.parse(Path.of("nonexistent.SC2Replay")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
