package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.List;

import forge.ai.combat.CombatEventBatch;
import forge.ai.combat.CombatOutcomeResolution;
import forge.ai.combat.CombatPlayerResources;
import forge.ai.combat.PublicCombatSnapshot;

/** Pure backend for prepared combat outcomes; shares parsing and resource arithmetic, not live targets. */
public final class CombatEventOutcomeResolver {
    private CombatEventOutcomeResolver() { }

    public static CombatOutcomeResolution resolve(final PublicCombatSnapshot snapshot, final List<CombatEventBatch> batches) {
        return continueWith(snapshot, batches, new CombatOutcomeResolution(true, 0, snapshot.resources(), java.util.Map.of(), List.of()));
    }

    /** Resolves only new batches; earlier declarations/strike steps must never be replayed. */
    public static CombatOutcomeResolution continueWith(final PublicCombatSnapshot snapshot,
            final List<CombatEventBatch> batches, final CombatOutcomeResolution previous) {
        if (!previous.supported()) { return previous; }
        final var resources = new LinkedHashMap<>(previous.resourcesAfter());
        final var resolutions = new LinkedHashMap<>(previous.resolutions());
        int utility = previous.utility();
        for (final CombatEventBatch batch : batches) {
            for (final CombatTriggerDescription trigger : snapshot.triggers()) {
                final int source = trigger.ability().sourceId();
                if (!batch.battlefieldBefore().containsKey(source)) { continue; }
                for (final CombatEventBatch.Event event : batch.events()) {
                    final boolean matches = switch (trigger.kind()) {
                        case SELF_ATTACK -> event instanceof CombatEventBatch.Attacks attack && attack.cardId() == source
                                && !snapshot.observedAttackers().contains(source);
                        case SELF_BLOCK -> event instanceof CombatEventBatch.Blocks block && block.blockerId() == source
                                && !snapshot.observedBlockers().contains(source);
                        case SELF_COMBAT_PLAYER_DAMAGE -> event instanceof CombatEventBatch.Damage hit && hit.sourceId() == source
                                && hit.recipient() == CombatEventBatch.Recipient.PLAYER && hit.amount() > 0
                                && snapshot.players().containsKey(hit.recipientId())
                                && (batch.stage() == CombatEventBatch.Stage.FIRST_STRIKE_DAMAGE
                                        || batch.stage() == CombatEventBatch.Stage.REGULAR_DAMAGE)
                                && ("Player".equals(trigger.parameters().get("ValidTarget"))
                                        || hit.recipientId() != batch.battlefieldBefore().get(source).characteristics().controllerId());
                        case SELF_DIES -> event instanceof CombatEventBatch.Dies death && death.cardId() == source
                                && (batch.stage() == CombatEventBatch.Stage.FIRST_STRIKE_DEATHS
                                        || batch.stage() == CombatEventBatch.Stage.REGULAR_DEATHS);
                    };
                    if (!matches) { continue; }
                    final var description = trigger.drawOutcome();
                    if (description.isEmpty()) { return unsupported("Unprepared combat outcome: " + trigger.ability()); }
                    final var draw = description.orElseThrow();
                    final int controller = batch.battlefieldBefore().get(source).characteristics().controllerId();
                    final int recipient = draw.controller() ? controller
                            : snapshot.players().keySet().stream().filter(id -> id != controller).findFirst().orElse(-1);
                    final CombatPlayerResources before = resources.get(recipient);
                    if (before == null) { return unsupported("Missing public draw resources for player " + recipient); }
                    // The snapshot audit excludes all unprojected draw restrictions/replacements.
                    final var result = DrawOutcomeDescription.evaluateResources(before.handSize(), before.librarySize(),
                            draw.amount(), Integer.MAX_VALUE);
                    if (result.overdraw()) {
                        // TODO: Project draw-loss rules and their timing before damage; do not clip
                        // to the available library and falsely certify a combat win.
                        return unsupported("Combat-triggered draw could exhaust the library for player " + recipient);
                    }
                    resources.put(recipient, new CombatPlayerResources(result.handAfter(), result.libraryAfter()));
                    utility = EffectMath.add(utility, recipient == snapshot.observingPlayerId()
                            ? result.value() : EffectMath.negate(result.value()));
                    resolutions.merge(trigger.ability(), 1, Integer::sum);
                }
            }
        }
        // TODO: General shared event matching, broader damage/death outcomes and sequences.
        // Fixed self draw has no new battlefield events or stack-order-dependent characteristics.
        return new CombatOutcomeResolution(true, utility, resources, resolutions, List.of());
    }

    private static CombatOutcomeResolution unsupported(final String reason) {
        return new CombatOutcomeResolution(false, 0, java.util.Map.of(), java.util.Map.of(), List.of(reason));
    }
}
