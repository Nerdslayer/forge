package forge.ai.effect;

import java.util.Optional;
import forge.game.trigger.Trigger;

/** Bridges shared scheduled interpretation to intrinsic occurrence estimation. */
public final class IntrinsicScheduledTriggerAdapter {
    private IntrinsicScheduledTriggerAdapter() { }

    public static Optional<IntrinsicScheduledTrigger> describe(final Trigger trigger) {
        return trigger == null ? Optional.empty()
                : ScheduledTriggerParser.parse(trigger.getMapParams()).map(schedule ->
                        new IntrinsicScheduledTrigger(
                                IntrinsicScheduledTrigger.Schedule.valueOf(schedule.timing().name()),
                                IntrinsicScheduledTrigger.PlayerScope.valueOf(schedule.playerScope().name())));
    }
}
