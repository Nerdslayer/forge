package forge.adventure.scene;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.player.AdventureChallenges.Challenge;
import forge.adventure.player.AdventureChallenges.Snapshot;
import forge.adventure.util.ChallengeProgress;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ChallengesScene extends UIScene {
    private record ChallengeEntry(Challenge challenge, String name, String effect, String reward) { }

    private static final List<ChallengeEntry> CHALLENGES = List.of(
            new ChallengeEntry(Challenge.WASTED_POTENTIAL, "lblAdventureChallengeWastes",
                    "lblAdventureChallengeWastesDescription", "lblAdventureChallengeWastesReward"),
            new ChallengeEntry(Challenge.WELL_PREPARED, "lblAdventureChallengeHand",
                    "lblAdventureChallengeHandDescription", "lblAdventureChallengeHandReward"));

    private static ChallengesScene instance;
    private final ScrollPane levelPanel;
    private final ScrollPane listPanel;
    private final ScrollPane detailPanel;
    private final ScrollPane achievementPanel;
    private final Table achievements = new Table();
    private final Map<Challenge, TextraButton> rows = new EnumMap<>(Challenge.class);
    private final TextraButton challengesTab;
    private final TextraButton achievementsTab;
    private final TextraButton backButton;
    private final TextraButton lower;
    private final TextraButton raise;
    private final TextraButton toggleButton;
    private final TextraLabel level = paperLabel(false);
    private final TextraLabel summary = paperLabel(true);
    private final TextraLabel detailName = paperLabel(true);
    private final TextraLabel detailEffect = paperLabel(true);
    private final TextraLabel detailReward = paperLabel(true);
    private final TextraLabel requirement = paperLabel(true);
    private ChallengeEntry selected = CHALLENGES.get(0);

    private ChallengesScene() {
        super(Forge.isLandscapeMode() ? "ui/challenges.json" : "ui/challenges_portrait.json");
        levelPanel = ui.findActor("levelPanel");
        listPanel = ui.findActor("listPanel");
        detailPanel = ui.findActor("detailPanel");
        achievementPanel = ui.findActor("achievementPanel");
        challengesTab = ui.findActor("challenges");
        achievementsTab = ui.findActor("achievements");
        backButton = ui.findActor("return");
        ui.onButtonPress("challenges", () -> showTab(false));
        ui.onButtonPress("achievements", () -> showTab(true));
        ui.onButtonPress("return", this::back);

        lower = Controls.newTextButton("-", () -> changeLevel(-1));
        raise = Controls.newTextButton("+", () -> changeLevel(1));
        Table levels = new Table().top().pad(3, 8, 0, 8);
        levels.add(lower).size(30, 24);
        levels.add(level).expandX().center();
        levels.add(raise).size(30, 24).row();
        levels.add(summary).colspan(3).growX().center().padTop(4).row();
        setContents(levelPanel, levels);

        TextButtonStyle rowStyle = new TextButtonStyle(Controls.getTextButtonStyle("default"));
        rowStyle.checked = Controls.getSkin().getDrawable("unpressedFocus10Patch");
        rowStyle.checkedOver = rowStyle.checked;
        rowStyle.checkedFocused = rowStyle.checked;
        Table list = new Table().top().pad(8);
        for (ChallengeEntry entry : CHALLENGES) {
            TextraButton row = Controls.newTextButton("", () -> {
                stage.setScrollFocus(listPanel);
                selectChallenge(entry);
            });
            row.setStyle(rowStyle);
            row.getTextraLabel().setWrap(true);
            rows.put(entry.challenge(), row);
            list.add(row).growX().minHeight(26).padBottom(4).row();
        }
        setContents(listPanel, list);
        listPanel.setCancelTouchFocus(true);

        toggleButton = Controls.newTextButton("", this::toggleSelected);
        Table details = new Table().top().pad(9);
        details.add(detailName).growX().padBottom(6).row();
        details.add(detailEffect).growX().padBottom(4).row();
        details.add(detailReward).growX().padBottom(6).row();
        details.add(requirement).growX().row();
        details.add(toggleButton).left().minWidth(78).height(24).row();
        setContents(detailPanel, details);

        achievements.top().pad(8);
        setContents(achievementPanel, achievements);
    }

    public static ChallengesScene instance() {
        if (instance == null) {
            instance = new ChallengesScene();
        }
        return instance;
    }

    private static String label(String key, Object... args) {
        return Forge.getLocalizer().getMessage(key, args);
    }

    private static TextraLabel paperLabel(boolean wrap) {
        TextraLabel label = Controls.newTextraLabel("");
        label.setColor(Color.BLACK);
        label.setAlignment(Align.left);
        label.setWrap(wrap);
        return label;
    }

    private static void setContents(ScrollPane panel, Table content) {
        panel.setActor(content);
        panel.setScrollingDisabled(true, false);
    }

    private void changeLevel(int amount) {
        var progress = Current.player().getChallenges();
        progress.setLevel(progress.getLevel() + amount);
        refreshLevel();
    }

    private void selectChallenge(ChallengeEntry entry) {
        if (selected != entry) {
            selected = entry;
            detailPanel.setScrollY(0);
        }
        refreshChallenges();
    }

    private void toggleSelected() {
        var progress = Current.player().getChallenges();
        Challenge challenge = selected.challenge();
        progress.setEnabled(challenge, !progress.isEnabled(challenge));
        refreshChallenges();
    }

    private void refreshLevel() {
        var progress = Current.player().getChallenges();
        var levelOnly = new Snapshot(progress.getLevel(), false, false, null);
        level.setText(label("lblAdventureChallengeLevel", progress.getLevel(), progress.getMaxLevel()));
        lower.setDisabled(progress.getLevel() == 0);
        raise.setDisabled(progress.getLevel() == progress.getMaxLevel());
        String cards;
        if (levelOnly.guaranteedCards() == 0) {
            cards = levelOnly.bonusCardChance() == 0 ? label("lblAdventureChallengeCardsNone")
                    : label("lblAdventureChallengeCardsChance", levelOnly.bonusCardChance());
        } else {
            cards = levelOnly.bonusCardChance() == 0
                    ? label("lblAdventureChallengeCardsGuaranteed", levelOnly.guaranteedCards())
                    : label("lblAdventureChallengeCardsCombined", levelOnly.guaranteedCards(), levelOnly.bonusCardChance());
        }
        summary.setText(label("lblAdventureChallengeLevelBonuses", levelOnly.lifePercent(),
                levelOnly.goldPercent(), levelOnly.shardPercent(), cards));
    }

    private void refreshChallenges() {
        var progress = Current.player().getChallenges();
        for (ChallengeEntry entry : CHALLENGES) {
            var row = rows.get(entry.challenge());
            String state = !progress.isUnlocked(entry.challenge()) ? "lblAdventureChallengeLocked"
                    : progress.isEnabled(entry.challenge()) ? "lblAdventureChallengeActive" : "lblOff";
            row.setChecked(entry == selected);
            String marker = entry == selected ? "> " : "";
            row.setText(marker + label("lblAdventureChallengeListEntry", label(entry.name()), label(state)));
        }

        detailName.setText("[%120]" + label(selected.name()));
        detailEffect.setText(label("lblAdventureChallengeEffect", label(selected.effect())));
        detailReward.setText(label("lblAdventureChallengeReward", label(selected.reward())));
        boolean unlocked = progress.isUnlocked(selected.challenge());
        String required = switch (selected.challenge()) {
            case WASTED_POTENTIAL -> "lblAdventureChallengeBossRequired";
            case WELL_PREPARED -> ChallengeProgress.hasQuestUnlock()
                    ? "lblAdventureChallengeQuestRequired" : "lblAdventureChallengeUnavailable";
        };
        requirement.setText(unlocked ? "" : label("lblAdventureChallengeRequirement", label(required)));
        toggleButton.setVisible(unlocked);
        toggleButton.setDisabled(!unlocked);
        toggleButton.setText(label(progress.isEnabled(selected.challenge())
                ? "lblAdventureChallengeDisable" : "lblAdventureChallengeEnable"));
    }

    private void showTab(boolean showAchievements) {
        levelPanel.setVisible(!showAchievements);
        listPanel.setVisible(!showAchievements);
        detailPanel.setVisible(!showAchievements);
        achievementPanel.setVisible(showAchievements);
        if (showAchievements) {
            PlayerStatisticScene.populateAchievements(achievements, Color.BLACK);
            achievementPanel.setScrollY(0);
        } else {
            refreshLevel();
            refreshChallenges();
        }
        stage.setKeyboardFocus(null);
        stage.setScrollFocus(showAchievements ? achievementPanel : listPanel);
        challengesTab.setDisabled(!showAchievements);
        achievementsTab.setDisabled(showAchievements);
        clearSelectable();
        addToSelectable(showAchievements ? challengesTab : achievementsTab);
        addToSelectable(backButton);
        if (!showAchievements) {
            addToSelectable(lower);
            addToSelectable(raise);
            for (ChallengeEntry entry : CHALLENGES) {
                addToSelectable(new Selectable<>(rows.get(entry.challenge())) {
                    @Override
                    public void onSelect(UIScene scene) {
                        stage.setScrollFocus(listPanel);
                        selectChallenge(entry);
                    }
                });
            }
            addToSelectable(new Selectable<>(toggleButton) {
                @Override
                public void onSelect(UIScene scene) {
                    stage.setScrollFocus(detailPanel);
                }
            });
        }
    }

    @Override
    public void enter() {
        super.enter();
        showTab(false);
    }
}
