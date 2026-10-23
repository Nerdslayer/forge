package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import forge.card.CardType;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.keyword.Protection;
import forge.game.player.Player;
import forge.game.staticability.StaticAbility;
import forge.game.zone.ZoneType;

/** Public, read-only matching preparation; generated Protection traits keep the engine's semantics. */
public final class CombatProtectionPreparation {
    private CombatProtectionPreparation() { }

    /** Shared lifetime admission; matching/quality coverage is checked separately during preparation. */
    public static boolean isPersistentSelfGrant(final StaticAbility ability) {
        final var p = ability.getMapParams();
        return ability.isIntrinsic() && !ability.isSuppressed() && "Continuous".equals(p.get("Mode"))
                && Set.of("Mode", "Affected", "AffectedDefined", "AddKeyword", "Description", "Secondary", "EffectZone").containsAll(p.keySet())
                && "Battlefield".equals(p.getOrDefault("EffectZone", "Battlefield"))
                && ("Self".equals(p.get("AffectedDefined")) || Set.of("Card.Self", "Creature.Self").contains(p.getOrDefault("Affected", "")))
                && java.util.Arrays.stream(p.getOrDefault("AddKeyword", "").split(" & ")).allMatch(s -> s.startsWith("Protection"));
    }

    static boolean persistentKeywordChanges(final Card card) {
        return card.getChangedCardKeywordsByText().isEmpty() && card.getChangedCardKeywords().values().stream().allMatch(change ->
                !change.isRemoveAllKeywords() && change.getRemoveKeywords().isEmpty() && change.getRemovedKeywordInstances().isEmpty()
                        && change.getKeywords().stream().allMatch(keyword -> keyword.getKeyword() == Keyword.PROTECTION
                                && keyword.getStatic() != null && keyword.getStatic().getHostCard() == card && isPersistentSelfGrant(keyword.getStatic())));
    }

    private static boolean hasProtectionRemovals(final Card card) {
        // A remaining instance does not prove that removed instances stay absent after cleanup.
        return card.getChangedCardKeywords().values().stream().anyMatch(change -> change.isRemoveAllKeywords()
                || change.getRemoveKeywords().stream().anyMatch(keyword -> keyword.startsWith("Protection"))
                || change.getRemovedKeywordInstances().stream().anyMatch(keyword -> keyword.getKeyword() == Keyword.PROTECTION));
    }

    static Optional<PreparedCombatProtection> prepare(final Player observer, final BooleanSupplier checkpoint) {
        final List<Card> cards = new ArrayList<>();
        final List<Card> protectedCards = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        for (final Player player : observer.getGame().getPlayers()) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (player.hasKeyword(Keyword.PROTECTION)) { reasons.add("Protected player needs explicit combat projection: " + player.getId()); }
        }
        for (final Card card : observer.getGame().getCardsIn(ZoneType.Battlefield)) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (card.isFaceDown() || card.isPhasedOut()) { continue; }
            if (card.isCreature()) { cards.add(card); }
            final boolean printed = card.getCurrentState().getIntrinsicKeywords().stream().anyMatch(k -> k.getKeyword() == Keyword.PROTECTION);
            if (!card.hasKeyword(Keyword.PROTECTION) && !printed) { continue; }
            if (!card.isCreature()) { reasons.add("Protected noncreature needs explicit combat projection: " + card.getId()); }
            else { protectedCards.add(card); }
        }
        if (protectedCards.isEmpty()) { return Optional.of(new PreparedCombatProtection(Map.of(), Map.of(), reasons)); }
        final Map<Integer, PreparedCombatProtection.Source> sources = new LinkedHashMap<>();
        for (final Card card : cards) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            if (card.hasChangedCardColors() || card.getChangedCardTypes().iterator().hasNext()) {
                reasons.add("Protection source color/type transition needs projection: " + card.getId());
            }
            sources.put(card.getId(), new PreparedCombatProtection.Source(card.getController().getId(), card.getColor().getColor(),
                    card.getType().toString(), card.getCMC()));
        }
        final Map<Integer, PreparedCombatProtection.Recipient> recipients = new LinkedHashMap<>();
        for (final Card card : protectedCards) {
            if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
            final Set<Integer> matches = new LinkedHashSet<>();
            final List<String> qualities = new ArrayList<>();
            if (!card.getChangedCardKeywordsByText().isEmpty() || !card.getChangedTextColorWords().isEmpty()
                    || !card.getChangedTextTypeWords().isEmpty() || !card.hasKeyword(Keyword.PROTECTION) || hasProtectionRemovals(card)) {
                reasons.add("Modified Protection lifetime needs projection: " + card.getId());
            }
            for (final KeywordInterface keyword : card.getKeywords(Keyword.PROTECTION)) {
                if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                final StaticAbility grant = keyword.getStatic();
                if (grant == null ? !keyword.isIntrinsic() : grant.getHostCard() != card || !isPersistentSelfGrant(grant)) {
                    reasons.add("Temporary/external Protection grant needs projection: " + card.getId());
                    continue;
                }
                final String valid;
                try { valid = Protection.getProtectionValid(keyword.getOriginal(), true); }
                catch (final RuntimeException ex) {
                    reasons.add("Unresolved Protection quality on " + card.getId()); continue;
                }
                if (!stableValidity(valid)) {
                    reasons.add("Dynamic Protection quality needs projection on " + card.getId() + ": " + valid); continue;
                }
                qualities.add(keyword.getOriginal());
                final var replacement = keyword.getReplacements().stream().findFirst().orElse(null);
                if (replacement == null || replacement.isSuppressed() || !replacement.zonesCheck(card.getZone())
                        || !"True".equals(replacement.getMapParams().get("Prevent"))
                        || !"Card.Self".equals(replacement.getMapParams().get("ValidTarget"))) {
                    reasons.add("Unresolved Protection replacement on " + card.getId()); continue;
                }
                for (final Card source : cards) {
                    if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
                    if (replacement.matchesValidParam("ValidSource", source)) { matches.add(source.getId()); }
                }
            }
            recipients.put(card.getId(), new PreparedCombatProtection.Recipient(matches, qualities.stream().distinct().sorted().toList()));
            if (card.getCounters(CounterEnumType.SHIELD) > 0 && !shieldOrderingSafe(observer, checkpoint)) {
                reasons.add("Protection/shield ordering consequences need projection: " + card.getId());
            }
        }
        if (!checkpoint.getAsBoolean()) { return Optional.empty(); }
        // TODO: Temporary durations, external/provider-loss grants, dynamic validity and player/walker recipients.
        // These are structural boundaries, not zero damage or a harmless ignored keyword.
        return Optional.of(new PreparedCombatProtection(recipients, sources, reasons));
    }

    private static boolean stableValidity(final String valid) {
        if (valid.isEmpty()) { return true; }
        for (final String alternative : valid.split(",")) {
            final String[] pieces = alternative.split("\\.", 2);
            // Engine validity also accepts bare subtypes (e.g. Protection:Human), not just Creature.Human.
            if (!CardType.isACardType(pieces[0]) && !CardType.isASubType(pieces[0])
                    && !Set.of("Card", "Permanent", "Emblem").contains(pieces[0])) { return false; }
            if (pieces.length == 1) { continue; }
            for (final String property : pieces[1].split("\\+")) {
                if (!Set.of("WhiteSource", "BlueSource", "BlackSource", "RedSource", "GreenSource", "ColorlessSource",
                        "nonColorlessSource", "MonoColorSource", "MultiColorSource", "nonColorless", "MonoColor", "MultiColor",
                        "White", "Blue", "Black", "Red", "Green", "Colorless", "YouCtrl", "OppCtrl").contains(property)
                        && !CardType.isACardType(property) && !CardType.isASupertype(property) && !CardType.isASubType(property)
                        && !property.matches("ControlledBy Player\\.PlayerUID_[0-9]+|CardUID_[0-9]+|cmc(?:EQ|NE|LT|LE|GT|GE)[0-9]{1,3}")) { return false; }
            }
        }
        return true;
    }

    private static boolean shieldOrderingSafe(final Player observer, final BooleanSupplier checkpoint) {
        // Preserve the shield only when its removal/prevention has no extra modeled or opaque payoff.
        // TODO: General affected-controller replacement-order choices, not observer-favorable ordering.
        for (final Card source : observer.getGame().getCardsIn(ZoneType.STATIC_ABILITIES_SOURCE_ZONES)) {
            if (!checkpoint.getAsBoolean()) { return false; }
            if (source.isFaceDown() || source.isPhasedOut()) { continue; }
            if (source.getTriggers().stream().anyMatch(t -> !t.isSuppressed() && t.zonesCheck(source.getZone())
                    && (t.getMode().toString().contains("Counter") || t.getMode().toString().contains("Prevent")))) { return false; }
            if (source.getStaticAbilities().stream().anyMatch(a -> !a.isSuppressed() && a.getKeyword() == null
                    && a.getMapParams().values().stream().anyMatch(v -> v.contains("Shield") || v.contains("SHIELD")))) { return false; }
        }
        return true;
    }
}
