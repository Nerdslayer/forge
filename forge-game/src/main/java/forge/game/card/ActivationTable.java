package forge.game.card;

import com.google.common.collect.ForwardingTable;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.collect.Table;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;

import java.util.Objects;
import java.util.Optional;

public class ActivationTable extends ForwardingTable<SpellAbility, Optional<StaticAbility>, Multiset<Player>> {
    Table<SpellAbility, Optional<StaticAbility>, Multiset<Player>> dataTable = HashBasedTable.create();
    private final Card owner;

    public ActivationTable() { this(null); }

    public ActivationTable(final Card owner) { this.owner = owner; }

    private void invalidateAnalysisState() {
        if (owner != null && owner.getGame() != null) { owner.getGame().invalidateAnalysisState(owner); }
    }

    @Override
    public void clear() {
        if (!dataTable.isEmpty()) { invalidateAnalysisState(); }
        super.clear();
    }

    @Override
    public Multiset<Player> put(final SpellAbility ability, final Optional<StaticAbility> grantor, final Multiset<Player> players) {
        invalidateAnalysisState();
        return super.put(ability, grantor, players);
    }

    @Override
    public void putAll(final Table<? extends SpellAbility, ? extends Optional<StaticAbility>, ? extends Multiset<Player>> table) {
        if (!table.isEmpty()) { invalidateAnalysisState(); }
        super.putAll(table);
    }

    @Override
    public Multiset<Player> remove(final Object ability, final Object grantor) {
        if (contains(ability, grantor)) { invalidateAnalysisState(); }
        return super.remove(ability, grantor);
    }

    // TODO: Mutating forwarded row/column maps or returned activator multisets directly bypasses
    // these hooks; audit those paths before retaining numeric estimates between decisions.

    @Override
    protected Table<SpellAbility, Optional<StaticAbility>, Multiset<Player>> delegate() {
        return dataTable;
    }

    protected SpellAbility getOriginal(SpellAbility sa) {
        SpellAbility original = null;
        SpellAbility root = sa.getRootAbility();

        // because trigger spell abilities are copied, try to get original one
        if (root.isTrigger()) {
            original = root.getTrigger().getOverridingAbility();
        } else {
            original = Objects.requireNonNullElse(root.getOriginalAbility(), root);
        }
        return original;
    }

    public void add(SpellAbility sa) {
        SpellAbility root = sa.getRootAbility();
        SpellAbility original = getOriginal(sa);

        if (original != null) {
            invalidateAnalysisState();
            Optional<StaticAbility> st = Optional.ofNullable(root.getGrantorStatic());

            Multiset<Player> activators = Objects.requireNonNullElseGet(get(original, st), HashMultiset::create);
            activators.add(sa.getActivatingPlayer());
            delegate().put(original, st, activators);
        }
    }

    public int get(SpellAbility sa) {
        return getActivators(sa).size();
    }

    public Multiset<Player> getActivators(SpellAbility sa) {
        SpellAbility root = sa.getRootAbility();
        SpellAbility original = getOriginal(sa);
        Optional<StaticAbility> st = Optional.ofNullable(root.getGrantorStatic());

        if (contains(original, st)) {
            return get(original, st);
        }
        return HashMultiset.create();
    }
}
