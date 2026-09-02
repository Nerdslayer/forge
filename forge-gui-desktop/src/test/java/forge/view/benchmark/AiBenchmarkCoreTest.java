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

import forge.gui.GuiBase;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AiBenchmarkCoreTest {
    @Test
    public void plannerCreatesTwoMirroredLegsForEveryOrderedDeckCell() {
        final BenchmarkDeck alpha = deck("alpha", "Alpha", "a");
        final BenchmarkDeck beta = deck("beta", "Beta", "b");

        final List<BenchmarkJob> jobs = BenchmarkPlanner.createJobs(List.of(beta, alpha), 3, 42L);

        Assert.assertEquals(jobs.size(), 24);
        for (int index = 0; index < jobs.size(); index += 2) {
            final BenchmarkJob first = jobs.get(index);
            final BenchmarkJob second = jobs.get(index + 1);
            Assert.assertEquals(second.evaluatedDeck(), first.evaluatedDeck());
            Assert.assertEquals(second.opponentDeck(), first.opponentDeck());
            Assert.assertEquals(second.repetition(), first.repetition());
            Assert.assertEquals(second.seed(), first.seed());
            Assert.assertTrue(first.evaluatedInSeatZero());
            Assert.assertFalse(second.evaluatedInSeatZero());
            Assert.assertEquals(first.leg(), 0);
            Assert.assertEquals(second.leg(), 1);
            Assert.assertNotEquals(second.id(), first.id());
        }
    }

    @Test
    public void scheduleDoesNotDependOnInputDeckOrder() {
        final BenchmarkDeck alpha = deck("alpha", "Alpha", "a");
        final BenchmarkDeck beta = deck("beta", "Beta", "b");
        final List<BenchmarkJob> forward = BenchmarkPlanner.createJobs(List.of(alpha, beta), 1, -7L);
        final List<BenchmarkJob> reverse = BenchmarkPlanner.createJobs(List.of(beta, alpha), 1, -7L);

        Assert.assertEquals(ids(reverse), ids(forward));
        for (int index = 0; index < forward.size(); index++) {
            Assert.assertEquals(reverse.get(index).seed(), forward.get(index).seed());
        }
    }

    @Test
    public void gameResultRoundTripsEscapedJsonFields() throws Exception {
        final BenchmarkJob job = BenchmarkPlanner.createJobs(List.of(deck("alpha", "Alpha", "a")), 1, 9L).get(0);
        final BenchmarkGameResult expected = BenchmarkGameResult.forJob(job);
        expected.status = BenchmarkGameResult.Status.CRASH;
        expected.error = "line one\nquoted \"value\" and slash \\";
        expected.evaluatedProfile = "Experimental";
        expected.baselineProfile = "Default";
        expected.evaluatedProfileSha256 = "eval-profile";
        expected.baselineProfileSha256 = "base-profile";
        expected.childExitCode = 2;
        final Path resultFile = java.nio.file.Files.createTempFile("forge-ai-benchmark", ".json");
        java.nio.file.Files.writeString(resultFile, expected.toJson());

        final BenchmarkGameResult actual = BenchmarkGameResult.read(resultFile);

        Assert.assertEquals(actual.jobId, expected.jobId);
        Assert.assertEquals(actual.error, expected.error);
        Assert.assertEquals(actual.seed, expected.seed);
        Assert.assertEquals(actual.status, expected.status);
        Assert.assertEquals(actual.evaluatedDeckSha256, expected.evaluatedDeckSha256);
    }

    @Test
    public void summaryIncludesDrawsButExcludesFailedGamesFromWinRate() {
        final List<BenchmarkGameResult> results = new ArrayList<>();
        results.add(result("one", BenchmarkGameResult.Status.COMPLETED, BenchmarkGameResult.EvaluatedResult.WIN));
        results.add(result("two", BenchmarkGameResult.Status.COMPLETED, BenchmarkGameResult.EvaluatedResult.LOSS));
        results.add(result("three", BenchmarkGameResult.Status.COMPLETED, BenchmarkGameResult.EvaluatedResult.DRAW));
        results.add(result("four", BenchmarkGameResult.Status.TIMEOUT, BenchmarkGameResult.EvaluatedResult.NONE));
        results.add(result("five", BenchmarkGameResult.Status.CRASH, BenchmarkGameResult.EvaluatedResult.NONE));

        final BenchmarkSummary.Stats overall = new BenchmarkSummary(results).overall();

        Assert.assertEquals(overall.completed(), 3);
        Assert.assertEquals(overall.wins, 1);
        Assert.assertEquals(overall.losses, 1);
        Assert.assertEquals(overall.draws, 1);
        Assert.assertEquals(overall.timeouts, 1);
        Assert.assertEquals(overall.errors, 1);
        Assert.assertEquals(overall.winRate(), 1.0 / 3.0, 0.000001);
    }

    @Test
    public void summaryRecordsWholeRunTiming() {
        final BenchmarkSummary summary = new BenchmarkSummary(List.of());
        final Instant startedAt = Instant.parse("2026-01-01T00:00:00Z");
        final Instant finishedAt = Instant.parse("2026-01-01T00:01:02.345Z");
        final String json = summary.toJson(startedAt, finishedAt, 62_345);
        final String markdown = summary.toMarkdown("Experimental", "Default",
                startedAt, finishedAt, 62_345);

        Assert.assertTrue(json.contains("\"wallClockDurationMillis\":62345"));
        Assert.assertTrue(markdown.contains("00:01:02.345"));
    }

    @Test
    public void optionsCombineDeckDirectoryAndExplicitDecksWithoutDuplicates() throws Exception {
        GuiBase.setInterface(new BenchmarkGuiDesktop());
        final Path root = java.nio.file.Files.createTempDirectory("forge-ai-benchmark-options");
        final Path profiles = java.nio.file.Files.createDirectory(root.resolve("profiles"));
        java.nio.file.Files.writeString(profiles.resolve("Default.ai"), "# default");
        java.nio.file.Files.writeString(profiles.resolve("Experimental.ai"), "# experimental");
        final Path decks = java.nio.file.Files.createDirectory(root.resolve("decks"));
        final Path alpha = java.nio.file.Files.writeString(decks.resolve("Alpha.dck"), "[metadata]\nName=Alpha");
        final Path beta = java.nio.file.Files.writeString(root.resolve("Beta.dck"), "[metadata]\nName=Beta");
        final BenchmarkOptions options = BenchmarkOptions.parse(new String[] {
                "benchmark", "--evaluated-profile", "default", "--baseline-profile", "Experimental",
                "--profile-dir", profiles.toString(), "--deck-dir", decks.toString(),
                "--deck", alpha.toString(), "--deck", beta.toString(), "--output", root.resolve("out").toString()
        });

        options.validate();
        final List<BenchmarkDeck> loaded = options.loadDecks();

        Assert.assertEquals(options.evaluatedProfile, "Default");
        Assert.assertEquals(loaded.size(), 2);
        Assert.assertEquals(loaded.stream().map(BenchmarkDeck::name).sorted().toList(), List.of("Alpha", "Beta"));
    }

    private static BenchmarkGameResult result(final String jobId, final BenchmarkGameResult.Status status,
            final BenchmarkGameResult.EvaluatedResult evaluatedResult) {
        final BenchmarkGameResult result = new BenchmarkGameResult();
        result.jobId = jobId;
        result.status = status;
        result.evaluatedResult = evaluatedResult;
        result.evaluatedDeckId = "alpha";
        result.evaluatedDeck = "Alpha";
        result.opponentDeckId = "beta";
        result.opponentDeck = "Beta";
        return result;
    }

    private static BenchmarkDeck deck(final String id, final String name, final String path) {
        return new BenchmarkDeck(id, name, Path.of(path + ".dck").toAbsolutePath(), id + "-sha256");
    }

    private static List<String> ids(final List<BenchmarkJob> jobs) {
        return jobs.stream().map(BenchmarkJob::id).toList();
    }
}
