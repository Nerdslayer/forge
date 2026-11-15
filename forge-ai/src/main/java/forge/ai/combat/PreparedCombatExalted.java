package forge.ai.combat;

import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.staticability.StaticAbilityMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Public declaration inputs and the temporary modifier for one candidate. */
public record PreparedCombatExalted(List<Source> sources, Map<Integer, DamageBasis> damageBases,
        Map<Integer, Integer> resolved, boolean newDeclaration, List<String> limitations, boolean statDependentBlocks, int recipient, int bonus) {
    record Source(int holder, int controller, Integer grantor, boolean uncertain) { }
    record DamageBasis(int signedDamage, boolean toughness, boolean none, boolean inverted) { }

    public PreparedCombatExalted {
        sources = List.copyOf(sources);
        damageBases = Map.copyOf(damageBases);
        resolved = Map.copyOf(resolved);
        limitations = List.copyOf(limitations);
    }

    static PreparedCombatExalted empty() {
        return new PreparedCombatExalted(List.of(), Map.of(), Map.of(), false, List.of(), false, -1, 0);
    }

    static boolean isActive(final forge.game.trigger.Trigger trigger) {
        return trigger.isKeyword(Keyword.EXALTED) && !trigger.isSuppressed()
                && trigger.zonesCheck(trigger.getHostCard().getZone());
    }

    static Source source(final Card holder, final forge.game.trigger.Trigger trigger) {
        final var grant = trigger.getKeyword().getStatic();
        // TODO: Project the lifetime of temporary grants and recheck richer recipient filters.
        final boolean supportedGrant = grant != null && grant.checkMode(StaticAbilityMode.Continuous)
                && "Creature.Other+YouCtrl".equals(grant.getParam("Affected"))
                && "Exalted".equals(grant.getParam("AddKeyword"))
                && Set.of("Mode", "Affected", "AddKeyword", "Description").containsAll(grant.getMapParams().keySet());
        return new Source(holder.getId(), holder.getController().getId(),
                grant == null ? null : grant.getHostCard().getId(),
                grant == null ? !trigger.isIntrinsic() : !supportedGrant || holder.getChangedCardTypes().iterator().hasNext());
    }

    static PreparedCombatExalted capture(final List<Source> sources, final Map<Integer, Integer> resolved,
            final List<Card> battlefield, final List<Card> publicSources,
            final boolean newDeclaration, final BooleanSupplier checkpoint) {
        if (sources.isEmpty() && resolved.isEmpty()) { return empty(); }
        final Map<Integer, DamageBasis> damage = new LinkedHashMap<>();
        for (final Card card : battlefield) {
            if (sources.isEmpty() && !resolved.containsKey(card.getId())) { continue; }
            if (!checkpoint.getAsBoolean()) { return null; }
            if (!card.isCreature() || card.isPhasedOut()) { continue; }
            final boolean none = card.assignNoCombatDamage();
            final boolean toughness = !none && card.toughnessAssignsDamage();
            final boolean inverted = !none && !toughness && card.negateCombatAssignedDamage();
            damage.put(card.getId(), new DamageBasis(none ? 0 : toughness ? card.getNetToughness()
                    : inverted ? -card.getNetPower() : card.getNetPower(), toughness, none, inverted));
        }
        final List<String> limitations = new ArrayList<>();
        boolean statDependentBlocks = false;
        for (final Card card : publicSources) {
            if (!checkpoint.getAsBoolean()) { return null; }
            if (card.isPhasedOut() || card.isFaceDown()) { continue; }
            for (final var ability : card.getStaticAbilities()) {
                if (ability.isSuppressed() || !ability.zonesCheck()) { continue; }
                if (ability.checkMode(StaticAbilityMode.Panharmonicon)) {
                    final String modes = ability.getParam("ValidMode");
                    if (modes == null || List.of(modes.split(",")).contains("Attacks")) {
                        limitations.add("Exalted trigger multiplication not projected: " + card.getId());
                    }
                }
                // TODO: Freeze and re-evaluate stat-dependent block predicates and their
                // SVar inputs per candidate, including against a newly crewed blocker.
                if ((ability.checkMode(StaticAbilityMode.CantBlock) || ability.checkMode(StaticAbilityMode.CantBlockBy)
                        || ability.checkMode(StaticAbilityMode.MinMaxBlocker)
                        || ability.checkMode(StaticAbilityMode.CantBlockUnless))
                        && (ability.hasParam("ValidAttackerRelative") || ability.getMapParams().entrySet().stream()
                                .anyMatch(entry -> !entry.getKey().equals("Description")
                                        && entry.getValue().toLowerCase(java.util.Locale.ROOT)
                                                .matches(".*(power|toughness).*")))) {
                    statDependentBlocks = true;
                    limitations.add("Exalted uses frozen stat-dependent blocking rules: " + card.getId());
                }
            }
        }
        return new PreparedCombatExalted(sources, damage, resolved, newDeclaration, limitations, statDependentBlocks, -1, 0);
    }

    PreparedCombatExalted forDeclaration(final int attackingPlayer, final Set<Integer> attackers) {
        final boolean loneAttack = newDeclaration && attackers.size() == 1 && !sources.isEmpty();
        final int id = loneAttack ? attackers.iterator().next() : -1;
        final int count = loneAttack ? (int) sources.stream().filter(source -> source.controller() == attackingPlayer).count() : 0;
        return id == recipient && count == bonus ? this
                : new PreparedCombatExalted(sources, damageBases, resolved, newDeclaration, limitations, statDependentBlocks, id, count);
    }

    PreparedCombatExalted nextTurn(final Set<Integer> gone) {
        if (sources.isEmpty()) { return empty(); }
        final List<Source> survivors = sources.stream().filter(source -> !gone.contains(source.holder())
                && (source.grantor() == null || !gone.contains(source.grantor()))).toList();
        if (survivors.isEmpty()) { return empty(); }
        final Map<Integer, DamageBasis> remainingDamageBases = new LinkedHashMap<>();
        damageBases.forEach((id, basis) -> {
            if (!gone.contains(id)) {
                remainingDamageBases.put(id, new DamageBasis(adjustDamage(id, basis.signedDamage()),
                        basis.toughness(), basis.none(), basis.inverted()));
            }
        });
        return new PreparedCombatExalted(survivors, remainingDamageBases, Map.of(), true, limitations, statDependentBlocks, -1, 0);
    }

    private int adjustDamage(final int id, final int signedDamage) {
        final int amount = resolved.getOrDefault(id, 0);
        final DamageBasis basis = damageBases.get(id);
        return basis == null || basis.none() ? signedDamage
                : CombatOutcomePredictor.add(signedDamage, basis.inverted() ? amount : -amount);
    }

    PreparedCombatStaticWorlds.CreatureState afterCleanup(final int id, final PreparedCombatStaticWorlds.CreatureState state) {
        final int amount = resolved.getOrDefault(id, 0);
        if (amount == 0) { return state; }
        final DamageBasis basis = damageBases.get(id);
        final int signed = basis.none() ? 0 : basis.toughness() ? state.toughness()
                : basis.inverted() ? -state.power() : state.power();
        return new PreparedCombatStaticWorlds.CreatureState(state.power() - amount, state.toughness() - amount,
                Math.max(0, adjustDamage(id, signed)), state.bodyDelta());
    }

    PublicCombatSnapshot.Creature afterCleanup(final PublicCombatSnapshot.Creature creature,
            final PreparedCombatStaticWorlds.CreatureState state) {
        final int id = creature.id();
        final int amount = resolved.getOrDefault(id, 0);
        if (amount == 0) { return creature; }
        final int damage = state == null
                ? Math.max(0, adjustDamage(id, damageBases.get(id).signedDamage())) : afterCleanup(id, state).combatDamage();
        return creature.withPowerToughness(damage, creature.toughness() - amount);
    }

    PublicCombatSnapshot.Creature apply(final PublicCombatSnapshot.Creature creature,
            final PreparedCombatStaticWorlds.CreatureState staticState) {
        if (bonus == 0 || creature.id() != recipient) { return creature; }
        final DamageBasis basis = damageBases.get(creature.id());
        final int toughness = CombatOutcomePredictor.add(creature.toughness(), bonus);
        int damage = CombatOutcomePredictor.add(creature.combatDamage(), bonus);
        if (basis != null) {
            final int power = staticState == null ? basis.signedDamage()
                    : basis.inverted() ? -staticState.power() : staticState.power();
            damage = basis.none() ? 0 : basis.toughness() ? toughness
                    : CombatOutcomePredictor.add(power, basis.inverted() ? -bonus : bonus);
        }
        return creature.withPowerToughness(Math.max(0, damage), toughness);
    }
}
