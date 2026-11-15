package forge.adventure.player;

import forge.adventure.data.EffectData;
import forge.adventure.util.SaveFileContent;
import forge.adventure.util.SaveFileData;

import java.util.EnumSet;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

public final class AdventureChallenges implements SaveFileContent {
    public enum Challenge {
        WASTED_POTENTIAL,
        WELL_PREPARED
    }

    private static final int HISTORY_VERSION = 1;
    private final Set<String> bosses = new TreeSet<>();
    private final Set<String> importedSources = new TreeSet<>();
    private final EnumSet<Challenge> unlocked = EnumSet.noneOf(Challenge.class);
    private final EnumSet<Challenge> enabled = EnumSet.noneOf(Challenge.class);
    private boolean legacyHistory;
    private int level;

    public int getLevel() {
        return level;
    }

    public int getMaxLevel() {
        return bosses.size();
    }

    public void setLevel(int value) {
        level = Math.max(0, Math.min(value, getMaxLevel()));
    }

    public boolean creditBoss(String id) {
        if (id == null || !bosses.add(id)) {
            return false;
        }
        unlock(Challenge.WASTED_POTENTIAL);
        return true;
    }

    public boolean isUnlocked(Challenge challenge) {
        return unlocked.contains(challenge);
    }

    public boolean isEnabled(Challenge challenge) {
        return enabled.contains(challenge);
    }

    public boolean hasEnabledChallenges() {
        return !enabled.isEmpty();
    }

    public void unlock(Challenge challenge) {
        unlocked.add(challenge);
    }

    public void setEnabled(Challenge challenge, boolean active) {
        if (active && unlocked.contains(challenge)) {
            enabled.add(challenge);
        } else {
            enabled.remove(challenge);
        }
    }

    public boolean beginHistoryImport(String source) {
        return legacyHistory && importedSources.add(HISTORY_VERSION + ":" + source);
    }

    public boolean hasLegacyHistory() {
        return legacyHistory;
    }

    public void clear() {
        bosses.clear();
        importedSources.clear();
        unlocked.clear();
        enabled.clear();
        level = 0;
        legacyHistory = false;
    }

    @Override
    public void load(SaveFileData data) {
        clear();
        if (data == null) {
            legacyHistory = true;
            return;
        }
        legacyHistory = data.containsKey("legacyHistory");
        if (data.containsKey("bosses")) {
            for (String id : (String[]) data.readObject("bosses")) {
                creditBoss(id);
            }
        }
        if (data.containsKey("historySources")) {
            for (String source : (String[]) data.readObject("historySources")) {
                importedSources.add(source);
            }
        }
        for (Challenge challenge : Challenge.values()) {
            if (data.containsKey("unlocked:" + challenge.name())) {
                unlock(challenge);
            }
            setEnabled(challenge, data.containsKey("enabled:" + challenge.name()));
        }
        setLevel(data.containsKey("level") ? data.readInt("level") : 0);
    }

    @Override
    public SaveFileData save() {
        SaveFileData data = new SaveFileData();
        data.store("level", level);
        data.storeObject("bosses", bosses.toArray(new String[0]));
        if (legacyHistory) {
            data.store("legacyHistory", true);
            data.storeObject("historySources", importedSources.toArray(new String[0]));
        }
        for (Challenge challenge : unlocked) {
            data.store("unlocked:" + challenge.name(), true);
        }
        for (Challenge challenge : enabled) {
            data.store("enabled:" + challenge.name(), true);
        }
        return data;
    }

    public Snapshot snapshot(String bossId) {
        return new Snapshot(level, isEnabled(Challenge.WASTED_POTENTIAL), isEnabled(Challenge.WELL_PREPARED), bossId);
    }

    public record Snapshot(int level, boolean wastes, boolean extraHand, String bossId) {
        public static final Snapshot NONE = new Snapshot(0, false, false, null);

        public int lifePercent() {
            return level * 10;
        }

        public int goldPercent() {
            return shardPercent() + (extraHand ? 20 : 0);
        }

        public int shardPercent() {
            return level * 5;
        }

        public int guaranteedCards() {
            return shardPercent() / 100 + (wastes ? 1 : 0);
        }

        public int bonusCardChance() {
            return shardPercent() % 100;
        }

        public int startingLife(int base, float difficulty) {
            return Math.round(base * difficulty * (1 + lifePercent() / 100f));
        }

        public boolean hasModifiers() {
            return level > 0 || wastes || extraHand;
        }

        public EffectData startingEffect() {
            EffectData effect = new EffectData();
            if (wastes) {
                effect.startBattleWithCard = new String[] {"Wastes"};
            }
            effect.changeStartCards = extraHand ? 1 : 0;
            return effect;
        }

        public int bonusCards(Random random) {
            return guaranteedCards() + rollPercentage(bonusCardChance(), random);
        }

        public static int bonusCurrency(int amount, int percent, Random random) {
            long scaled = (long) amount * percent;
            return (int) (scaled / 100) + rollPercentage((int) (scaled % 100), random);
        }

        private static int rollPercentage(int chance, Random random) {
            return chance > 0 && random.nextInt(100) < chance ? 1 : 0;
        }
    }
}
