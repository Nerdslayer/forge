/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2026  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package forge.view.benchmark;

import com.google.common.eventbus.Subscribe;
import forge.LobbyPlayer;
import forge.ai.AiProfileUtil;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;
import forge.view.TimeLimitedCodeBlock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class BenchmarkGameWorker {
    private BenchmarkGameWorker() {
    }

    static int run(final String[] args) {
        final WorkerOptions options;
        try {
            options = WorkerOptions.parse(args);
        } catch (RuntimeException e) {
            System.err.println("Invalid benchmark worker arguments: " + e.getMessage());
            return 2;
        }

        final BenchmarkGameResult result = BenchmarkGameResult.forJob(options.job());
        result.evaluatedProfile = options.evaluatedProfile;
        result.baselineProfile = options.baselineProfile;
        int exitCode = 2;
        try {
            System.setProperty("java.awt.headless", "true");
            GuiBase.setInterface(new BenchmarkGuiDesktop());
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            });
            AiProfileUtil.loadAllProfiles(options.profileDirectory.toString());
            validateProfile(options.evaluatedProfile);
            validateProfile(options.baselineProfile);
            result.evaluatedProfileSha256 = BenchmarkFiles.sha256(
                    options.profileDirectory.resolve(options.evaluatedProfile + ".ai"));
            result.baselineProfileSha256 = BenchmarkFiles.sha256(
                    options.profileDirectory.resolve(options.baselineProfile + ".ai"));
            exitCode = play(options, result);
        } catch (Throwable e) {
            result.status = BenchmarkGameResult.Status.CRASH;
            result.error = describe(e);
            e.printStackTrace(System.err);
        }
        result.childExitCode = exitCode;
        try {
            BenchmarkFiles.writeAtomically(options.resultPath, result.toJson() + System.lineSeparator());
        } catch (Exception e) {
            System.err.println("Unable to write benchmark result " + options.resultPath + ": " + e.getMessage());
            e.printStackTrace(System.err);
            return 2;
        }
        return exitCode;
    }

    private static int play(final WorkerOptions options, final BenchmarkGameResult result) throws Exception {
        final Deck evaluatedDeck = DeckSerializer.fromFile(options.job.evaluatedDeck().path().toFile());
        final Deck opponentDeck = DeckSerializer.fromFile(options.job.opponentDeck().path().toFile());
        if (evaluatedDeck == null || evaluatedDeck.isEmpty()) {
            return invalid(result, "Could not load evaluated deck: " + options.job.evaluatedDeck().path());
        }
        if (opponentDeck == null || opponentDeck.isEmpty()) {
            return invalid(result, "Could not load opponent deck: " + options.job.opponentDeck().path());
        }
        if (!BenchmarkFiles.sha256(options.job.evaluatedDeck().path()).equals(options.job.evaluatedDeck().sha256())
                || !BenchmarkFiles.sha256(options.job.opponentDeck().path()).equals(options.job.opponentDeck().sha256())) {
            return invalid(result, "A deck changed after the benchmark schedule was created");
        }

        final RegisteredPlayer evaluated = registeredPlayer(evaluatedDeck, "EVALUATED", options.evaluatedProfile, 0);
        final RegisteredPlayer baseline = registeredPlayer(opponentDeck, "BASELINE", options.baselineProfile, 1);
        final List<RegisteredPlayer> players = new ArrayList<>();
        if (options.job.evaluatedInSeatZero()) {
            players.add(evaluated);
            players.add(baseline);
        } else {
            players.add(baseline);
            players.add(evaluated);
        }

        MyRandom.setRandom(new Random(options.job.seed()));
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        final Match match = new Match(rules, players, "AI Benchmark " + options.job.id());
        final Game game = match.createGame();
        final StartingPlayerRecorder recorder = new StartingPlayerRecorder(evaluated.getPlayer());
        game.subscribeToEvents(recorder);
        final long startNanos = System.nanoTime();
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game),
                    options.timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            result.status = BenchmarkGameResult.Status.TIMEOUT;
            result.error = "Game exceeded " + options.timeoutSeconds + " seconds";
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
            result.durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            populateOutcome(result, game, evaluated, baseline, recorder);
            return 3;
        } catch (Exception | StackOverflowError e) {
            result.status = BenchmarkGameResult.Status.CRASH;
            result.error = describe(e);
            if (!game.isGameOver()) {
                game.setGameOver(GameEndReason.Draw);
            }
            result.durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            populateOutcome(result, game, evaluated, baseline, recorder);
            e.printStackTrace(System.err);
            return 2;
        }

        result.durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (!game.isGameOver() || game.getOutcome() == null) {
            result.status = BenchmarkGameResult.Status.CRASH;
            result.error = "Game returned without a final outcome";
            return 2;
        }
        result.status = BenchmarkGameResult.Status.COMPLETED;
        populateOutcome(result, game, evaluated, baseline, recorder);
        return 0;
    }

    private static RegisteredPlayer registeredPlayer(final Deck deck, final String name,
            final String profile, final int avatarIndex) {
        return new RegisteredPlayer(deck).setPlayer(GamePlayerUtil.createAiPlayer(name, avatarIndex, profile));
    }

    private static void populateOutcome(final BenchmarkGameResult result, final Game game,
            final RegisteredPlayer evaluated, final RegisteredPlayer baseline,
            final StartingPlayerRecorder recorder) {
        result.startingRole = recorder.startingRole;
        final Player evaluatedPlayer = findPlayer(game, evaluated.getPlayer());
        final Player baselinePlayer = findPlayer(game, baseline.getPlayer());
        if (evaluatedPlayer != null) {
            result.evaluatedMulligans = evaluatedPlayer.getStats().getMulliganCount();
        }
        if (baselinePlayer != null) {
            result.baselineMulligans = baselinePlayer.getStats().getMulliganCount();
        }
        final GameOutcome outcome = game.getOutcome();
        if (outcome == null) {
            return;
        }
        result.gameEndReason = outcome.getWinCondition().name();
        result.turns = outcome.getLastTurnNumber();
        result.lifeDelta = outcome.getLifeDelta();
        if (outcome.isDraw()) {
            result.evaluatedResult = BenchmarkGameResult.EvaluatedResult.DRAW;
            result.winnerRole = "DRAW";
        } else if (outcome.isWinner(evaluated)) {
            result.evaluatedResult = BenchmarkGameResult.EvaluatedResult.WIN;
            result.winnerRole = "EVALUATED";
        } else {
            result.evaluatedResult = BenchmarkGameResult.EvaluatedResult.LOSS;
            result.winnerRole = "BASELINE";
        }
    }

    private static Player findPlayer(final Game game, final LobbyPlayer lobbyPlayer) {
        for (Player player : game.getPlayers()) {
            if (player.getLobbyPlayer().equals(lobbyPlayer)) {
                return player;
            }
        }
        return null;
    }

    private static void validateProfile(final String profile) {
        if (!AiProfileUtil.getAvailableProfiles().contains(profile)) {
            throw new IllegalArgumentException("Unknown AI profile: " + profile);
        }
    }

    private static int invalid(final BenchmarkGameResult result, final String message) {
        result.status = BenchmarkGameResult.Status.INVALID_INPUT;
        result.error = message;
        return 2;
    }

    private static String describe(final Throwable error) {
        return error.getClass().getName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static final class StartingPlayerRecorder {
        private final LobbyPlayer evaluated;
        private String startingRole;

        private StartingPlayerRecorder(final LobbyPlayer evaluated) {
            this.evaluated = evaluated;
        }

        @Subscribe
        public void receive(final GameEventTurnBegan event) {
            if (startingRole == null && event.turnNumber() == 1) {
                startingRole = event.turnOwner().isLobbyPlayer(evaluated) ? "EVALUATED" : "BASELINE";
            }
        }
    }

    private static final class WorkerOptions {
        private final BenchmarkJob job;
        private final String evaluatedProfile;
        private final String baselineProfile;
        private final Path profileDirectory;
        private final Path resultPath;
        private final long timeoutSeconds;

        private WorkerOptions(final BenchmarkJob job, final String evaluatedProfile,
                final String baselineProfile, final Path profileDirectory,
                final Path resultPath, final long timeoutSeconds) {
            this.job = job;
            this.evaluatedProfile = evaluatedProfile;
            this.baselineProfile = baselineProfile;
            this.profileDirectory = profileDirectory;
            this.resultPath = resultPath;
            this.timeoutSeconds = timeoutSeconds;
        }

        private BenchmarkJob job() {
            return job;
        }

        private static WorkerOptions parse(final String[] args) {
            final Map<String, String> values = new LinkedHashMap<>();
            for (int index = 1; index < args.length; index += 2) {
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing value for " + args[index]);
                }
                values.put(args[index], args[index + 1]);
            }
            final BenchmarkDeck evaluatedDeck = deck(values, "evaluated");
            final BenchmarkDeck opponentDeck = deck(values, "opponent");
            final BenchmarkJob job = new BenchmarkJob(required(values, "--job-id"), evaluatedDeck, opponentDeck,
                    Integer.parseInt(required(values, "--repetition")), Integer.parseInt(required(values, "--leg")),
                    Long.parseLong(required(values, "--seed")),
                    Integer.parseInt(required(values, "--evaluated-seat")) == 0);
            return new WorkerOptions(job, required(values, "--evaluated-profile"),
                    required(values, "--baseline-profile"), Path.of(required(values, "--profile-dir")),
                    Path.of(required(values, "--result")), Long.parseLong(required(values, "--timeout-seconds")));
        }

        private static BenchmarkDeck deck(final Map<String, String> values, final String role) {
            return new BenchmarkDeck(required(values, "--" + role + "-deck-id"),
                    required(values, "--" + role + "-deck-name"),
                    Path.of(required(values, "--" + role + "-deck")),
                    required(values, "--" + role + "-deck-sha256"));
        }

        private static String required(final Map<String, String> values, final String key) {
            final String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing " + key);
            }
            return value;
        }
    }
}
