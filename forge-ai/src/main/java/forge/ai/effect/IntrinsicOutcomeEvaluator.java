package forge.ai.effect;

import java.util.Locale;

import forge.ai.PlayerResourceValueEvaluator;

/**
 * Pure value calculations for one intrinsic reference situation.
 *
 * <p>This is deliberately smaller than the live {@code OutcomeEvaluator} registry. It does not
 * construct a live Game or Card, choose real targets, or score abilities recursively. It provides
 * the reusable point calculations that the later reference outcome adapter can compose with
 * {@link OutcomePlanner}.</p>
 */
public final class IntrinsicOutcomeEvaluator {
    private final IntrinsicEvaluationSettings settings;

    public IntrinsicOutcomeEvaluator() {
        this(IntrinsicEvaluationSettings.defaults());
    }

    public IntrinsicOutcomeEvaluator(final IntrinsicEvaluationSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Intrinsic evaluation settings are required");
        }
        this.settings = settings;
    }

    public IntrinsicEvaluationSettings settings() {
        return settings;
    }

    /** Values a draw using the hand size from one already-combined reference case. */
    public int evaluateCardDraw(final ReferenceCase referenceCase, final int amount,
            final boolean recipientIsController) {
        return evaluateCardDraw(requiredInt(referenceCase, IntrinsicReferenceModel.HAND_SIZE), amount,
                recipientIsController);
    }

    /** Values drawing unknown cards for the controller of the ability. */
    public int evaluateCardDraw(final int currentHandSize, final int amount,
            final boolean recipientIsController) {
        return orient(PlayerResourceValueEvaluator.evaluateCardDraw(Math.max(0, currentHandSize), amount),
                recipientIsController);
    }

    /** Values random discard of unknown cards for the controller of the ability. */
    public int evaluateRandomDiscard(final int currentHandSize, final int amount,
            final boolean recipientIsController) {
        return orient(-PlayerResourceValueEvaluator.evaluateRandomDiscard(currentHandSize, amount),
                recipientIsController);
    }

    /** Values selected discard, which is less punishing than random discard. */
    public int evaluateChosenDiscard(final int currentHandSize, final int amount,
            final boolean recipientIsController) {
        return orient(-PlayerResourceValueEvaluator.evaluateChosenDiscard(currentHandSize, amount),
                recipientIsController);
    }

    /** Values immediately usable unrestricted mana. */
    public int evaluateMana(final int amount, final boolean recipientIsController) {
        return orient(PlayerResourceValueEvaluator.evaluateMana(amount), recipientIsController);
    }

    /** Values an average card play at a given mana cost without using the current hand size. */
    public int evaluateAverageCardPlay(final int manaCost) {
        return PlayerResourceValueEvaluator.evaluateAverageCardPlay(manaCost);
    }

    /** Values a life change from the affected player's perspective, then applies controller polarity. */
    public int evaluateLifeChange(final int before, final int after,
            final boolean recipientIsController) {
        return orient(PlayerResourceValueEvaluator.evaluateLifeChange(before, after),
                recipientIsController);
    }

    /** Values life gain without allowing an invalid negative-life reference state. */
    public int evaluateLifeGain(final int currentLife, final int amount,
            final boolean recipientIsController) {
        if (amount <= 0) {
            return 0;
        }
        final int before = Math.max(0, currentLife);
        final long after = (long) before + amount;
        final int boundedAfter = after >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) after;
        return evaluateLifeChange(before, boundedAfter, recipientIsController);
    }

    /** Values life gain using the starting life from one reference case. */
    public int evaluateLifeGain(final ReferenceCase referenceCase, final int amount,
            final boolean recipientIsController) {
        return evaluateLifeGain(requiredInt(referenceCase, IntrinsicReferenceModel.LIFE_TOTAL), amount,
                recipientIsController);
    }

    /**
     * Values life loss, including the reduced intrinsic lethal curve rather than the gameplay
     * terminal value. The value is positive when losing life harms the opponent.
     */
    public int evaluateLifeLoss(final int currentLife, final int amount,
            final boolean recipientIsController) {
        if (amount <= 0) {
            return 0;
        }
        final int before = Math.max(0, currentLife);
        final long after = (long) before - amount;
        final int valueToRecipient = after <= 0
                ? -settings.intrinsicLethalValue(before)
                : PlayerResourceValueEvaluator.evaluateLifeChange(before, (int) after);
        return orient(valueToRecipient, recipientIsController);
    }

    /** Values life loss using the starting life from one reference case. */
    public int evaluateLifeLoss(final ReferenceCase referenceCase, final int amount,
            final boolean recipientIsController) {
        return evaluateLifeLoss(requiredInt(referenceCase, IntrinsicReferenceModel.LIFE_TOTAL), amount,
                recipientIsController);
    }

    /** Damage to a player uses the same intrinsic life-loss valuation as a life-loss outcome. */
    public int evaluatePlayerDamage(final int currentLife, final int amount,
            final boolean recipientIsController) {
        return evaluateLifeLoss(currentLife, amount, recipientIsController);
    }

    /** Player damage uses the life total from the same reference case as the other resources. */
    public int evaluatePlayerDamage(final ReferenceCase referenceCase, final int amount,
            final boolean recipientIsController) {
        return evaluateLifeLoss(referenceCase, amount, recipientIsController);
    }

    /**
     * Values the difference between two reference creatures. This intentionally covers only
     * characteristic fields represented by {@link IntrinsicReferenceModel.CreatureProfile}; live
     * CreatureEvaluator also includes mana abilities, spell abilities, triggers, static effects,
     * temporary state, and card-specific bonuses. Those require separate intrinsic adapters and
     * must not be approximated by recursive calls here.
     */
    public int evaluateCreatureDelta(final IntrinsicReferenceModel.CreatureProfile before,
            final IntrinsicReferenceModel.CreatureProfile after, final boolean recipientIsController) {
        if (before == null || after == null) {
            throw new IllegalArgumentException("Reference creatures are required");
        }
        return orient(creatureValue(after) - creatureValue(before), recipientIsController);
    }

    /** Returns the nonrecursive characteristic value used by the reference creature adapter. */
    public int evaluateCreature(final IntrinsicReferenceModel.CreatureProfile creature) {
        if (creature == null) {
            throw new IllegalArgumentException("Reference creature is required");
        }
        return creatureValue(creature);
    }

    private static int creatureValue(final IntrinsicReferenceModel.CreatureProfile creature) {
        if (!creature.present()) {
            return 0;
        }
        int value = 100; // CreatureEvaluator's base plus the non-token card component.
        final int power = creature.power();
        final int toughness = creature.toughness();
        value = addSaturated(value, power * 15);
        value = addSaturated(value, toughness * 10);

        if (hasKeyword(creature, "flying")) {
            value = addSaturated(value, power * 10);
        }
        if (hasKeyword(creature, "deathtouch") && power > 0) {
            value = addSaturated(value, 25);
        }
        if (hasKeyword(creature, "lifelink") && power > 0) {
            value = addSaturated(value, power * 10);
        }
        if (hasKeyword(creature, "trample") && power > 1) {
            value = addSaturated(value, (power - 1) * 5);
        }
        if (hasKeyword(creature, "vigilance")) {
            value = addSaturated(value, power * 5 + toughness * 5);
        }
        if (creature.indestructible()) {
            value = addSaturated(value, 70);
        }
        if (creature.hexproof() || hasKeyword(creature, "hexproof")) {
            value = addSaturated(value, 35);
        } else if (hasKeyword(creature, "shroud")) {
            value = addSaturated(value, 30);
        } else if (hasKeyword(creature, "ward")) {
            value = addSaturated(value, 10);
        }
        return value;
    }

    private static boolean hasKeyword(final IntrinsicReferenceModel.CreatureProfile creature,
            final String keyword) {
        final String expected = keyword.toLowerCase(Locale.ROOT);
        return creature.keywords().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    private static int orient(final int valueToRecipient, final boolean recipientIsController) {
        return recipientIsController ? valueToRecipient : negateSaturated(valueToRecipient);
    }

    private static int addSaturated(final int left, final int right) {
        final long value = (long) left + right;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }

    private static int negateSaturated(final int value) {
        return value == Integer.MIN_VALUE ? Integer.MAX_VALUE : -value;
    }

    private static int requiredInt(final ReferenceCase referenceCase, final String name) {
        if (referenceCase == null) {
            throw new IllegalArgumentException("Reference case is required");
        }
        final Integer value = referenceCase.value(name, Integer.class);
        if (value == null) {
            throw new IllegalArgumentException("Reference case is missing " + name);
        }
        return value;
    }
}
