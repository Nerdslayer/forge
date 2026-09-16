package forge.ai.effect;

import java.util.Set;

import forge.card.CardType;

/**
 * Small shared parser for static effects whose affected-object scope is explicit in the script.
 * Live relationship analysis and intrinsic definition analysis use the same scope vocabulary, but
 * apply different recipient populations and value arithmetic after parsing it.
 */
enum StaticAbilityScope {
    CONTROLLER,
    OPPONENT,
    BOTH,
    SELF,
    ATTACHED;

    static StaticAbilityScope parse(final String affected) {
        if (affected == null) {
            return null;
        }
        return switch (affected.trim()) {
        case "Card.Self", "Creature.Self" -> SELF;
        case "Creature.EnchantedBy", "Creature.EquippedBy" -> ATTACHED;
        case "Creature.YouCtrl", "Creature.YouCtrl+Other" -> CONTROLLER;
        case "Creature.OppCtrl", "Creature.OppCtrl+Other" -> OPPONENT;
        case "Creature", "Creature.Other" -> BOTH;
        default -> {
            final StaticAbilityScope tribalScope = simpleTribalScope(affected);
            yield tribalScope == null ? simpleObjectScope(affected) : tribalScope;
        }
        };
    }

    private static StaticAbilityScope simpleTribalScope(final String affected) {
        if (!affected.startsWith("Creature.")) {
            return null;
        }
        final String[] pieces = affected.substring("Creature.".length()).split("\\+", -1);
        StaticAbilityScope scope = BOTH;
        boolean controller = false;
        boolean tribe = false;
        boolean other = false;
        for (final String piece : pieces) {
            if ("YouCtrl".equals(piece) || "OppCtrl".equals(piece)) {
                if (controller) {
                    return null;
                }
                controller = true;
                scope = "YouCtrl".equals(piece) ? CONTROLLER : OPPONENT;
            } else if ("Other".equals(piece)) {
                if (other) {
                    return null;
                }
                other = true;
            } else if (CardType.isACreatureType(piece)) {
                if (tribe) {
                    return null;
                }
                tribe = true;
            } else {
                return null;
            }
        }
        return scope;
    }

    private static StaticAbilityScope simpleObjectScope(final String affected) {
        final String trimmed = affected.trim();
        final int dot = trimmed.indexOf('.');
        final int plus = trimmed.indexOf('+');
        final int separator = dot < 0 ? plus : plus < 0 ? dot : Math.min(dot, plus);
        final String objectType = separator < 0 ? trimmed : trimmed.substring(0, separator);
        if (!isSupportedObjectType(objectType)) {
            return null;
        }

        final String qualifiers = separator < 0 ? ""
                : trimmed.substring(separator + 1).replace('.', '+');
        StaticAbilityScope scope = BOTH;
        boolean controller = false;
        boolean other = false;
        for (final String qualifier : qualifiers.split("\\+", -1)) {
            if (qualifier.isEmpty() || "Basic".equals(qualifier)
                    || "nonBasic".equals(qualifier) || "NonBasic".equals(qualifier)
                    || "nonLand".equals(qualifier) || "NonLand".equals(qualifier)) {
                continue;
            }
            if ("YouCtrl".equals(qualifier) || "OppCtrl".equals(qualifier)) {
                if (controller) {
                    return null;
                }
                controller = true;
                scope = "YouCtrl".equals(qualifier) ? CONTROLLER : OPPONENT;
            } else if ("Other".equals(qualifier)) {
                if (other) {
                    return null;
                }
                other = true;
            } else {
                return null;
            }
        }
        return scope;
    }

    private static boolean isSupportedObjectType(final String value) {
        return Set.of("Card", "Permanent", "NonLand", "nonLand", "Token").contains(value)
                || CardType.isACardType(value);
    }

    boolean isCreatureScope(final String affected) {
        if (affected == null) {
            return false;
        }
        final String trimmed = affected.trim();
        return "Creature".equals(trimmed) || trimmed.startsWith("Creature.");
    }

    boolean includesController() {
        return this == CONTROLLER || this == BOTH;
    }

    boolean includesOpponent() {
        return this == OPPONENT || this == BOTH;
    }

    boolean isTribal(final String affected) {
        if (affected == null || !affected.startsWith("Creature.")) {
            return false;
        }
        for (final String piece : affected.substring("Creature.".length()).split("\\+", -1)) {
            if (CardType.isACreatureType(piece)) {
                return true;
            }
        }
        return false;
    }

    String description() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
