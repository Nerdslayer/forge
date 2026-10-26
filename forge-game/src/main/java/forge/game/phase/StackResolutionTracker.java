package forge.game.phase;

import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.TreeMap;

/** Tracks draining stacks separately from chains that keep replenishing themselves. */
public final class StackResolutionTracker implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final int WARNING_INTERVAL = 200;
    public static final int HEADLESS_RESOLUTION_LIMIT = 2000;
    public static final int STACK_SIZE_WARNING = 5000;
    public static final int MAX_STACK_SIZE = 10000;
    private static final int HISTORY_SIZE = 64;

    private final Deque<String> recentAbilities = new ArrayDeque<>();
    private int minimumSize = Integer.MAX_VALUE;
    private int resolutionsWithoutProgress;
    private int nextWarning = WARNING_INTERVAL;

    /** Establish the baseline before resolving the first object of an episode. */
    public void beforeResolution(final int stackSize) {
        minimumSize = Math.min(minimumSize, stackSize);
    }

    /** Called once per resolution, after state-based actions and waiting triggers settle. */
    public void afterResolution(final int stackSize, final String signature) {
        if (stackSize == 0) {
            reset();
            return;
        }
        if (stackSize < minimumSize) {
            minimumSize = stackSize;
            resolutionsWithoutProgress = 0;
            nextWarning = WARNING_INTERVAL;
            recentAbilities.clear();
        } else {
            resolutionsWithoutProgress++;
        }
        recentAbilities.addLast(signature);
        if (recentAbilities.size() > HISTORY_SIZE) {
            recentAbilities.removeFirst();
        }
    }

    public boolean warningDue() {
        return resolutionsWithoutProgress >= nextWarning;
    }

    public void acknowledgeWarning() {
        nextWarning = resolutionsWithoutProgress + WARNING_INTERVAL;
    }

    public int getResolutionsWithoutProgress() {
        return resolutionsWithoutProgress;
    }

    public boolean isRepeating(final String signature) {
        return resolutionsWithoutProgress >= WARNING_INTERVAL
                && recentAbilities.stream().filter(signature::equals).limit(2).count() == 2;
    }

    public void reset() {
        minimumSize = Integer.MAX_VALUE;
        resolutionsWithoutProgress = 0;
        nextWarning = WARNING_INTERVAL;
        recentAbilities.clear();
    }

    /** A recurrence hint, never a proof of equivalent game states or an unavoidable loop. */
    public static String signature(final SpellAbility ability) {
        final StringBuilder result = new StringBuilder();
        appendSignature(result, ability);
        return result.toString();
    }

    private static void appendSignature(final StringBuilder result, final SpellAbility ability) {
        final SpellAbility script = ability instanceof WrappedAbility wrapper ? wrapper.getWrappedAbility() : ability;
        result.append(ability.getHostCard().getId()).append(':')
                .append(ability.getActivatingPlayer() == null ? -1 : ability.getActivatingPlayer().getId()).append(':')
                .append(script.getApi()).append(':')
                .append(new TreeMap<>(script.getMapParams())).append(':');
        if (ability.getTrigger() != null) {
            result.append(new TreeMap<>(ability.getTrigger().getMapParams())).append(':');
        }
        result.append(ability.getTargets()).append(';');
        if (ability.getChosenList() != null) {
            for (final SpellAbility mode : ability.getChosenList()) {
                appendSignature(result, mode);
            }
        }
        if (ability.getSubAbility() != null) {
            appendSignature(result, ability.getSubAbility());
        }
    }
}
