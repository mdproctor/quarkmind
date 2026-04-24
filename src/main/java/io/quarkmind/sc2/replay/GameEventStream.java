package io.quarkmind.sc2.replay;

import hu.scelight.sc2.rep.factory.RepContent;
import hu.scelight.sc2.rep.factory.RepParserEngine;
import hu.scelight.sc2.rep.model.Replay;
import hu.scelight.sc2.rep.model.gameevents.cmd.CmdEvent;
import hu.scelight.sc2.rep.model.gameevents.cmd.TargetPoint;
import hu.scelight.sc2.rep.model.gameevents.cmd.TargetUnit;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.Delta;
import hu.scelight.sc2.rep.model.gameevents.selectiondelta.SelectionDeltaEvent;
import hu.scelight.sc2.rep.s2prot.Event;
import io.quarkmind.domain.Point2d;

import java.nio.file.Path;
import java.util.*;

public final class GameEventStream {

    private GameEventStream() {}

    /**
     * Parses GAME_EVENTS from the replay, returns per-unit move/follow orders sorted by loop.
     * Tags use "r-{index}-{recycle}" format matching ReplaySimulatedGame tracker events.
     */
    public static List<UnitOrder> parse(Path replayPath) {
        Replay replay;
        try {
            replay = RepParserEngine.parseReplay(replayPath, EnumSet.of(RepContent.GAME_EVENTS));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse GAME_EVENTS from: " + replayPath, e);
        }
        if (replay == null || replay.gameEvents == null) {
            throw new IllegalArgumentException("No game events in replay: " + replayPath);
        }

        // Per-player selection: userId → selected unit tags
        Map<Integer, List<String>> selections = new HashMap<>();
        List<UnitOrder> orders = new ArrayList<>();

        for (Event raw : replay.gameEvents.getEvents()) {
            int userId = raw.getUserId();

            if (raw instanceof SelectionDeltaEvent sel) {
                Delta delta = sel.getDelta();
                if (delta == null) continue;
                Integer[] addTags = delta.getAddUnitTags();
                if (addTags == null || addTags.length == 0) continue;
                List<String> decoded = new ArrayList<>(addTags.length);
                for (Integer rawTag : addTags) {
                    if (rawTag != null) decoded.add(decodeTag(rawTag));
                }
                selections.put(userId, decoded);

            } else if (raw instanceof CmdEvent cmd) {
                List<String> selected = selections.get(userId);
                if (selected == null || selected.isEmpty()) continue;

                TargetPoint tp = cmd.getTargetPoint();
                TargetUnit  tu = cmd.getTargetUnit();

                if (tp != null) {
                    float x = tp.getXFloat(), y = tp.getYFloat();
                    // Only accept coordinates within a valid SC2 map range
                    if (x >= 0 && x <= 256 && y >= 0 && y <= 256) {
                        Point2d target = new Point2d(x, y);
                        for (String tag : selected) {
                            orders.add(new UnitOrder(tag, raw.getLoop(), target, null));
                        }
                    }
                } else if (tu != null && tu.getTag() != null) {
                    String targetTag = decodeTag(tu.getTag());
                    for (String tag : selected) {
                        orders.add(new UnitOrder(tag, raw.getLoop(), null, targetTag));
                    }
                }
            }
        }

        orders.sort(Comparator.comparingLong(UnitOrder::loop));
        return orders;
    }

    /** Decode raw SC2 unit tag integer to "r-{index}-{recycle}" format. */
    static String decodeTag(int rawTag) {
        return "r-" + (rawTag >> 18) + "-" + (rawTag & 0x3FFFF);
    }
}
