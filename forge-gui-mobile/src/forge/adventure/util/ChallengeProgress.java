package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventureChallenges.Challenge;
import forge.adventure.player.AdventureChallenges.ArtifactChallenge;
import forge.adventure.player.AdventureChallenges.Snapshot;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerStatistic;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.card.CardRulesPredicates;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.model.FModel;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

public final class ChallengeProgress {
    private static final Predicate<PaperCard> NONBASIC_LAND =
            PaperCardPredicates.fromRules(CardRulesPredicates.IS_NONBASIC_LAND);
    private static final int PEST_CONTROL = 24;
    private static final String BASILISK = "HP Chamber Basilisk";
    private static final String XIRA_MAP = "/maps/map/minibosses/xira.tmx";
    private static final String EMRAKUL_CASTLE = "Emrakul/../common/maps/map/emrakul/emrakul.tmx";
    private static final String STRANGE_KEY = "Strange Key";
    private static final int EMRAKUL_WARD = 71;
    private static final int HARD_EMRAKUL = 72;
    private static final int NORMAL_EMRAKUL = 73;
    // Other planes may carry the template without its quest source or tagged target.
    private static final Set<String> PEST_CONTROL_PLANES = Set.of(
            "Shandalar", "Shandalar Old Border", "Crystal_Kingdoms");

    private ChallengeProgress() { }

    public static String bossId(String enemy, String encounter) {
        return Config.instance().getSettingData().plane + "/"
                + (encounter == null || encounter.isBlank() ? "enemy/" + enemy : "encounter/" + encounter);
    }

    public static boolean hasQuestUnlock() {
        return PEST_CONTROL_PLANES.contains(Config.instance().getSettingData().plane);
    }

    public static void questCompleted(AdventurePlayer player, int templateId) {
        if (templateId == PEST_CONTROL && hasQuestUnlock()) {
            player.getChallenges().unlock(Challenge.WELL_PREPARED);
        }
    }

    public static boolean hasShandalarStoryChallenges() {
        return "Shandalar".equals(Config.instance().getSettingData().plane);
    }

    public static void recordStoryItem(AdventurePlayer player, String item) {
        if (item.endsWith(" Key") && hasShandalarStoryChallenges()) {
            unlockMoxes(player, key -> item.equals(key) || STRANGE_KEY.equals(item));
        }
    }

    public static void recordStoryVictory(AdventurePlayer player, String location, String enemy) {
        if ("Emrakul".equals(enemy) && location.endsWith(EMRAKUL_CASTLE) && hasShandalarStoryChallenges()) {
            player.getChallenges().unlock(Challenge.SOL_RING);
        }
    }

    private static void unlockMoxes(AdventurePlayer player, Predicate<String> hasKey) {
        for (Challenge challenge : Challenge.values()) {
            ArtifactChallenge artifact = challenge.artifact();
            if (artifact != null && artifact.key() != null && hasKey.test(artifact.key())) {
                player.getChallenges().unlock(challenge);
            }
        }
    }

    static void recoverShandalarStoryChallenges(AdventurePlayer player, Map<String, PointOfInterestChanges> locations) {
        boolean tradedKeys = player.hasItem(STRANGE_KEY);
        boolean defeatedEmrakul = false;
        for (var location : locations.entrySet()) {
            if (!location.getKey().endsWith(EMRAKUL_CASTLE)) {
                continue;
            }
            var castle = location.getValue();
            tradedKeys |= castle.isObjectDeleted(EMRAKUL_WARD);
            defeatedEmrakul |= castle.isEnemyDefeated("Emrakul")
                    || (player.getStatistic().hasWonAgainst("Emrakul")
                    && (castle.isObjectDeleted(HARD_EMRAKUL) || castle.isObjectDeleted(NORMAL_EMRAKUL)));
        }
        if (tradedKeys) {
            unlockMoxes(player, key -> true);
        } else {
            unlockMoxes(player, player::hasItem);
        }
        if (defeatedEmrakul) {
            player.getChallenges().unlock(Challenge.SOL_RING);
        }
    }

    public static String recoverHistory(AdventurePlayer player, Map<String, PointOfInterestChanges> locations) {
        if (hasShandalarStoryChallenges()) {
            recoverShandalarStoryChallenges(player, locations);
        }
        var progress = player.getChallenges();
        if (!progress.hasLegacyHistory()) {
            return "";
        }
        int previousLevel = progress.getMaxLevel();
        boolean hadWellPrepared = progress.isUnlocked(Challenge.WELL_PREPARED);
        if (progress.beginHistoryImport(Config.instance().getSettingData().plane)) {
            if (player.checkQuestFlag("questCompleted:" + PEST_CONTROL)) {
                questCompleted(player, PEST_CONTROL);
            }
        }
        for (EnemyData enemy : WorldData.getAllEnemies()) {
            if (!enemy.boss || enemy.copyPlayerDeck || !AdventureContentPacks.get().available(enemy)
                    || !progress.beginHistoryImport(bossId(enemy.name, null))) {
                continue;
            }
            // TODO: Add reviewed legacy mappings when a boss needs a distinct authored encounter ID.
            if (hasEvidenceOfVictory(enemy.name, player.getStatistic(), locations)) {
                progress.creditBoss(bossId(enemy.name, null));
            }
        }
        int recovered = progress.getMaxLevel() - previousLevel;
        if (recovered == 0 && hadWellPrepared == progress.isUnlocked(Challenge.WELL_PREPARED)) {
            return "";
        }
        String summary = Forge.getLocalizer().getMessage("lblAdventureChallengeRecovered", recovered);
        if (previousLevel == 0 && progress.getMaxLevel() > 0) {
            summary += "\n" + Forge.getLocalizer().getMessage("lblAdventureChallengeWastes");
        }
        if (!hadWellPrepared && progress.isUnlocked(Challenge.WELL_PREPARED)) {
            summary += "\n" + Forge.getLocalizer().getMessage("lblAdventureChallengeHand");
        }
        return summary;
    }

    static boolean hasEvidenceOfVictory(String enemy, PlayerStatistic statistic,
                                        Map<String, PointOfInterestChanges> locations) {
        if (locations.values().stream().anyMatch(location -> location.isEnemyDefeated(enemy))) {
            return true;
        }
        // The Chamber Basilisk has a unique historical name and no alternate encounter source.
        if (BASILISK.equals(enemy)) {
            return statistic.hasWonAgainst(BASILISK, "Basilisk of the Chamber");
        }
        // Older Xira saves record only the removed actor. Require a matching win as well.
        if ("Xira".equals(enemy) && statistic.hasWonAgainst("Xira")) {
            return locations.entrySet().stream()
                    .anyMatch(entry -> entry.getKey().endsWith(XIRA_MAP)
                            && entry.getKey().contains("Xiras Hive/")
                            && (entry.getValue().isObjectDeleted(67) || entry.getValue().isObjectDeleted(104)));
        }
        return false;
    }

    public static void showVictoryLoot(EnemySprite enemy, Snapshot challenge) {
        Array<Reward> rewards = enemy.getRewards();
        int extraGold = 0;
        int extraShards = 0;
        int extraCards = 0;
        if (challenge.hasModifiers()) {
            Random random = MyRandom.getRandom();
            int gold = 0;
            int shards = AdventurePlayer.VICTORY_SHARDS;
            for (Reward reward : rewards) {
                if (reward.getType() == Reward.Type.Gold) {
                    gold += reward.getCount();
                } else if (reward.getType() == Reward.Type.Shards) {
                    shards += reward.getCount();
                }
            }
            extraGold = challenge.bonusGold(gold, random);
            extraShards = Snapshot.bonusCurrency(shards, challenge.shardPercent(), random);
            if (challenge.enabled().contains(Challenge.SECOND_WIND)) {
                extraGold += 60;
                extraShards += 3;
            }
            if (extraGold > 0) {
                rewards.add(new Reward(extraGold));
            }
            if (extraShards > 0) {
                rewards.add(new Reward(Reward.Type.Shards, extraShards));
            }
            int cards = challenge.bonusCards(random);
            Deck opponent = Current.latestDeck();
            if (cards > 0) {
                List<PaperCard> pool = bonusPool(opponent);
                if (!pool.isEmpty()) {
                    for (int i = 0; i < cards; i++) {
                        rewards.add(new Reward(pool.get(random.nextInt(pool.size()))));
                    }
                    extraCards = cards;
                }
            }
            if (challenge.enabled().contains(Challenge.WASTED_POTENTIAL)) {
                List<PaperCard> lands = opponent == null ? List.of() : opponent.getMain().getFilteredPool(NONBASIC_LAND).toFlatList();
                if (lands.isEmpty()) {
                    lands = FModel.getMagicDb().getCommonCards().getUniqueCards().stream().filter(NONBASIC_LAND).toList();
                }
                extraCards += addRandomCard(rewards, lands, random);
            }
            if (challenge.enabled().contains(Challenge.WELL_PREPARED) && opponent != null) {
                extraCards += addRandomCard(rewards, opponent.getMain().toFlatList(), random);
            }
            if (challenge.enabled().contains(Challenge.BACKWATER)) {
                List<PaperCard> pool = backwaterPool(opponent);
                extraCards += addRandomCard(rewards, pool, random);
                extraCards += addRandomCard(rewards, pool, random);
            }
            for (Challenge active : Challenge.values()) {
                if (active.artifact() != null && challenge.enabled().contains(active)) {
                    extraCards += addArtifactRewards(rewards, active.artifact(), random);
                }
            }
        }
        RewardScene.instance().loadChallengeRewards(rewards, challenge.bossId(), extraGold, extraShards, extraCards);
    }

    private static int addArtifactRewards(Array<Reward> rewards, ArtifactChallenge artifact, Random random) {
        var pool = new ArrayList<PaperCard>();
        for (PaperCard card : RewardData.getAllCards()) {
            if (isArtifactReward(card, artifact.color())) {
                pool.add(card);
            }
        }
        int awarded = 0;
        for (int i = 0; i < artifact.rewardCount(); i++) {
            awarded += addRandomCard(rewards, pool, random);
        }
        return awarded;
    }

    static boolean isArtifactReward(PaperCard card, byte color) {
        return PaperCardPredicates.IS_RARE_OR_MYTHIC.test(card) && (color == MagicColor.COLORLESS
                ? card.getRules().getColor().isColorless() : card.getRules().getColor().hasAnyColor(color));
    }

    private static int addRandomCard(Array<Reward> rewards, List<PaperCard> pool, Random random) {
        if (pool.isEmpty()) {
            return 0;
        }
        rewards.add(new Reward(pool.get(random.nextInt(pool.size()))));
        return 1;
    }

    private static List<PaperCard> backwaterPool(Deck enemyDeck) {
        if (enemyDeck != null) {
            var commons = enemyDeck.getMain().getFilteredPool(PaperCardPredicates.IS_COMMON).toFlatList();
            if (!commons.isEmpty()) {
                return commons;
            }
        }
        var fallback = new ArrayList<PaperCard>();
        for (PaperCard card : RewardData.getAllCards()) {
            if (PaperCardPredicates.IS_COMMON.test(card)) {
                fallback.add(card);
            }
        }
        return fallback;
    }

    private static List<PaperCard> bonusPool(Deck enemyDeck) {
        if (enemyDeck != null) {
            var allowed = PaperCardPredicates.onlyPrintedInEditions(Config.instance().getConfigData().restrictedEditions).negate();
            var eligible = allowed.and(PaperCardPredicates.fromRules(CardRulesPredicates.NOT_BASIC_LAND));
            List<PaperCard> cards = enemyDeck.getMain().getFilteredPool(eligible).toFlatList();
            if (!cards.isEmpty()) {
                return cards;
            }
        }
        var fallback = new ArrayList<PaperCard>();
        var nonbasic = PaperCardPredicates.fromRules(CardRulesPredicates.NOT_BASIC_LAND);
        for (PaperCard card : RewardData.getAllCards()) {
            if (nonbasic.test(card)) {
                fallback.add(card);
            }
        }
        return fallback;
    }
}
