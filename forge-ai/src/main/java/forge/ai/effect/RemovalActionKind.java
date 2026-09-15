package forge.ai.effect;

import forge.game.ability.ApiType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** The state transition performed by a single-permanent removal action. */
public enum RemovalActionKind {
    /** A destroy or exile effect whose target does not remain available to replay. */
    PERMANENT_REMOVAL,
    DESTROY,
    EXILE,
    BOUNCE;

    /** Infers the bounded action kinds currently relevant to removal-target selection. */
    public static RemovalActionKind from(final SpellAbility ability) {
        // TODO(effect analysis): Classify temporary exile, delayed returns, blink, and
        // multi-target zone changes instead of using the permanent-removal fallback.
        if (ability == null) {
            return PERMANENT_REMOVAL;
        }
        if (ability.getApi() == ApiType.Destroy) {
            return DESTROY;
        }
        if (ability.getApi() != ApiType.ChangeZone
                || !ability.hasParam("Origin") || !ability.hasParam("Destination")) {
            return PERMANENT_REMOVAL;
        }

        try {
            final boolean startsOnBattlefield = ZoneType.listValueOf(ability.getParam("Origin"))
                    .contains(ZoneType.Battlefield);
            final ZoneType destination = ZoneType.smartValueOf(ability.getParam("Destination"));
            if (!startsOnBattlefield) {
                return PERMANENT_REMOVAL;
            }
            if (destination == ZoneType.Hand) {
                return BOUNCE;
            }
            if (destination == ZoneType.Exile) {
                return EXILE;
            }
        } catch (final RuntimeException ignored) {
            // Malformed or dynamic zone parameters retain the permanent-removal fallback.
        }
        return PERMANENT_REMOVAL;
    }
}
