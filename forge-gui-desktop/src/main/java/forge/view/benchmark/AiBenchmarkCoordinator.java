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

import forge.util.BuildInfo;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class AiBenchmarkCoordinator {
    private final BenchmarkOptions options;
    private String evaluatedProfileHash;
    private String baselineProfileHash;

    AiBenchmarkCoordinator(final BenchmarkOptions options) {
        this.options = options;
    }

    int run() throws Exception {
        final Instant startedAt = Instant.now();
        final long startedNanos = System.nanoTime();
        final List<BenchmarkDeck> decks = options.loadDecks();
        final List<BenchmarkJob> jobs = BenchmarkPlanner.createJobs(decks, options.multiplier, options.masterSeed);
        evaluatedProfileHash = BenchmarkFiles.sha256(options.profilePath(options.evaluatedProfile));
        baselineProfileHash = BenchmarkFiles.sha256(options.profilePath(options.baselineProfile));

        Files.createDirectories(options.outputDirectory.resolve("games"));
        verifyRunSignature(decks);
        if (!options.resume || !Files.isRegularFile(options.outputDirectory.resolve("manifest.json"))) {
            writeManifest(decks, jobs);
        }
        writeSchedule(jobs);

        final List<BenchmarkGameResult> results = new ArrayList<>();
        final List<BenchmarkJob> pending = new ArrayList<>();
        for (BenchmarkJob job : jobs) {
            final BenchmarkGameResult resumed = options.resume ? readCompletedResult(job) : null;
            if (resumed == null) {
                pending.add(job);
            } else {
                results.add(resumed);
            }
        }

        System.out.printf("AI Benchmark: %s vs %s, %d decks, %d games, %d workers%n",
                options.evaluatedProfile, options.baselineProfile, decks.size(), jobs.size(), options.workers);
        if (!results.isEmpty()) {
            System.out.printf("Resuming with %d existing results; %d games remain%n", results.size(), pending.size());
        }
        execute(pending, results, jobs.size());
        results.sort(Comparator.comparing(result -> result.jobId));
        final long wallClockDurationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        final Instant finishedAt = Instant.now();
        writeReports(results, startedAt, finishedAt, wallClockDurationMillis);

        final BenchmarkSummary summary = new BenchmarkSummary(results);
        final BenchmarkSummary.Stats overall = summary.overall();
        System.out.printf("Completed %d games: %d wins, %d losses, %d draws, %.2f%% win rate, "
                        + "%d timeouts, %d errors, wall time %s%n",
                overall.completed(), overall.wins, overall.losses, overall.draws,
                overall.winRate() * 100, overall.timeouts, overall.errors,
                BenchmarkSummary.formatDuration(wallClockDurationMillis));
        System.out.println("Reports: " + options.outputDirectory);
        return overall.timeouts == 0 && overall.errors == 0 ? 0 : 1;
    }

    private void execute(final List<BenchmarkJob> pending, final List<BenchmarkGameResult> results,
            final int totalJobs) throws InterruptedException {
        if (pending.isEmpty()) {
            return;
        }
        final ExecutorService executor = Executors.newFixedThreadPool(options.workers);
        final CompletionService<BenchmarkGameResult> completion = new ExecutorCompletionService<>(executor);
        try {
            for (BenchmarkJob job : pending) {
                completion.submit(() -> runJob(job));
            }
            for (int completed = 1; completed <= pending.size(); completed++) {
                try {
                    results.add(completion.take().get());
                } catch (Exception e) {
                    throw new IllegalStateException("Coordinator task failed", e);
                }
                System.out.printf("Progress: %d/%d games%n", results.size(), totalJobs);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private BenchmarkGameResult runJob(final BenchmarkJob job) {
        final Path resultPath = job.resultPath(options.outputDirectory);
        final Path logPath = job.logPath(options.outputDirectory);
        final BenchmarkGameResult fallback = createFallback(job);
        try {
            final List<String> command = workerCommand(job, resultPath);
            final ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(new File(System.getProperty("user.dir")));
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(logPath.toFile()));
            final Process process = builder.start();
            final boolean finished = process.waitFor(options.timeoutSeconds + 15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
                fallback.status = BenchmarkGameResult.Status.TIMEOUT;
                fallback.error = "Worker exceeded hard timeout";
                fallback.childExitCode = 3;
                BenchmarkFiles.writeAtomically(resultPath, fallback.toJson() + System.lineSeparator());
                return fallback;
            }
            if (!Files.isRegularFile(resultPath)) {
                fallback.status = BenchmarkGameResult.Status.CRASH;
                fallback.error = "Worker exited with code " + process.exitValue() + " without a result file";
                fallback.childExitCode = process.exitValue();
                BenchmarkFiles.writeAtomically(resultPath, fallback.toJson() + System.lineSeparator());
                return fallback;
            }
            final BenchmarkGameResult result = BenchmarkGameResult.read(resultPath);
            if (!matches(result, job)) {
                fallback.status = BenchmarkGameResult.Status.CRASH;
                fallback.error = "Worker returned a result that does not match its scheduled job";
                fallback.childExitCode = process.exitValue();
                BenchmarkFiles.writeAtomically(resultPath, fallback.toJson() + System.lineSeparator());
                return fallback;
            }
            return result;
        } catch (Throwable e) {
            fallback.status = BenchmarkGameResult.Status.CRASH;
            fallback.error = e.getClass().getName() + ": " + e.getMessage();
            try {
                BenchmarkFiles.writeAtomically(resultPath, fallback.toJson() + System.lineSeparator());
            } catch (IOException writeError) {
                fallback.error += "; could not write result: " + writeError.getMessage();
            }
            return fallback;
        }
    }

    private List<String> workerCommand(final BenchmarkJob job, final Path resultPath) {
        final String javaHome = System.getProperty("java.home");
        final String javaBin = javaHome + File.separator + "bin" + File.separator + "java";
        final List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Djava.awt.headless=true");
        command.add("-Xmx" + options.workerHeap);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("forge.view.Main");
        command.add("benchmark-game");
        add(command, "--job-id", job.id());
        addDeck(command, "evaluated", job.evaluatedDeck());
        addDeck(command, "opponent", job.opponentDeck());
        add(command, "--evaluated-profile", options.evaluatedProfile);
        add(command, "--baseline-profile", options.baselineProfile);
        add(command, "--profile-dir", options.profileDirectory.toString());
        add(command, "--repetition", Integer.toString(job.repetition()));
        add(command, "--leg", Integer.toString(job.leg()));
        add(command, "--seed", Long.toString(job.seed()));
        add(command, "--evaluated-seat", job.evaluatedSeat());
        add(command, "--timeout-seconds", Long.toString(options.timeoutSeconds));
        add(command, "--result", resultPath.toString());
        return command;
    }

    private static void addDeck(final List<String> command, final String role, final BenchmarkDeck deck) {
        add(command, "--" + role + "-deck-id", deck.id());
        add(command, "--" + role + "-deck-name", deck.name());
        add(command, "--" + role + "-deck", deck.path().toString());
        add(command, "--" + role + "-deck-sha256", deck.sha256());
    }

    private static void add(final List<String> command, final String key, final String value) {
        command.add(key);
        command.add(value);
    }

    private BenchmarkGameResult readCompletedResult(final BenchmarkJob job) {
        final Path resultPath = job.resultPath(options.outputDirectory);
        if (!Files.isRegularFile(resultPath)) {
            return null;
        }
        try {
            final BenchmarkGameResult result = BenchmarkGameResult.read(resultPath);
            return result.isCompleted() && matches(result, job) ? result : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean matches(final BenchmarkGameResult result, final BenchmarkJob job) {
        return job.id().equals(result.jobId)
                && job.seed() == result.seed
                && job.evaluatedDeck().sha256().equals(result.evaluatedDeckSha256)
                && job.opponentDeck().sha256().equals(result.opponentDeckSha256)
                && options.evaluatedProfile.equals(result.evaluatedProfile)
                && evaluatedProfileHash.equals(result.evaluatedProfileSha256)
                && options.baselineProfile.equals(result.baselineProfile)
                && baselineProfileHash.equals(result.baselineProfileSha256)
                && job.leg() == result.leg
                && job.repetition() == result.repetition
                && (job.evaluatedInSeatZero() ? 0 : 1) == result.evaluatedSeat;
    }

    private BenchmarkGameResult createFallback(final BenchmarkJob job) {
        final BenchmarkGameResult result = BenchmarkGameResult.forJob(job);
        result.evaluatedProfile = options.evaluatedProfile;
        result.evaluatedProfileSha256 = evaluatedProfileHash;
        result.baselineProfile = options.baselineProfile;
        result.baselineProfileSha256 = baselineProfileHash;
        return result;
    }

    private void writeManifest(final List<BenchmarkDeck> decks, final List<BenchmarkJob> jobs) throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("createdAt", Instant.now().toString());
        root.put("format", "Constructed");
        root.put("evaluatedProfile", profileMap(options.evaluatedProfile, evaluatedProfileHash));
        root.put("baselineProfile", profileMap(options.baselineProfile, baselineProfileHash));
        root.put("profileDirectory", options.profileDirectory.toString());
        root.put("masterSeed", options.masterSeed);
        root.put("seedDerivation", BenchmarkPlanner.seedVersion());
        root.put("multiplier", options.multiplier);
        root.put("mirroredLegsPerCell", 2);
        root.put("gamesPerDeckPair", 2 * options.multiplier);
        root.put("workers", options.workers);
        root.put("timeoutSeconds", options.timeoutSeconds);
        root.put("workerHeap", options.workerHeap);
        root.put("totalJobs", jobs.size());
        root.put("runSignature", runSignature(decks));
        root.put("decks", BenchmarkJson.mapList(decks));
        root.put("forgeVersion", BuildInfo.getVersionString());
        root.put("gitCommit", detectGitCommit());
        root.put("javaVersion", System.getProperty("java.version"));
        root.put("javaVendor", System.getProperty("java.vendor"));
        root.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        BenchmarkFiles.writeAtomically(options.outputDirectory.resolve("manifest.json"),
                BenchmarkJson.toJson(root) + System.lineSeparator());
    }

    private void verifyRunSignature(final List<BenchmarkDeck> decks) throws IOException {
        final String signature = runSignature(decks);
        final Path signaturePath = options.outputDirectory.resolve("run.signature");
        if (options.resume && Files.isRegularFile(signaturePath)) {
            final String existing = Files.readString(signaturePath).trim();
            if (!signature.equals(existing)) {
                throw new IllegalArgumentException("The resume configuration does not match the existing benchmark run");
            }
        } else if (options.resume && Files.exists(options.outputDirectory.resolve("manifest.json"))) {
            throw new IllegalArgumentException("Existing benchmark run predates resume-signature support");
        }
        BenchmarkFiles.writeAtomically(signaturePath, signature + System.lineSeparator());
    }

    private String runSignature(final List<BenchmarkDeck> decks) {
        final StringBuilder value = new StringBuilder("Constructed|")
                .append(options.evaluatedProfile).append('|').append(evaluatedProfileHash).append('|')
                .append(options.baselineProfile).append('|').append(baselineProfileHash).append('|')
                .append(options.masterSeed).append('|').append(options.multiplier).append('|')
                .append(options.timeoutSeconds).append('|').append(BenchmarkPlanner.seedVersion());
        decks.stream().sorted(Comparator.comparing(BenchmarkDeck::id))
                .forEach(deck -> value.append('|').append(deck.id()).append('|').append(deck.sha256()));
        return BenchmarkFiles.sha256(value.toString());
    }

    private Map<String, Object> profileMap(final String name, final String hash) throws IOException {
        final Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", name);
        profile.put("path", options.profilePath(name).toAbsolutePath().normalize().toString());
        profile.put("sha256", hash);
        return profile;
    }

    private void writeSchedule(final List<BenchmarkJob> jobs) throws IOException {
        final StringBuilder out = new StringBuilder();
        for (BenchmarkJob job : jobs) {
            out.append(BenchmarkJson.toJson(job.toJsonMap())).append(System.lineSeparator());
        }
        BenchmarkFiles.writeAtomically(options.outputDirectory.resolve("schedule.jsonl"), out.toString());
    }

    private void writeReports(final List<BenchmarkGameResult> results, final Instant startedAt,
            final Instant finishedAt, final long wallClockDurationMillis) throws IOException {
        final StringBuilder games = new StringBuilder();
        for (BenchmarkGameResult result : results) {
            games.append(result.toJson()).append(System.lineSeparator());
        }
        BenchmarkFiles.writeAtomically(options.outputDirectory.resolve("games.jsonl"), games.toString());
        final BenchmarkSummary summary = new BenchmarkSummary(results);
        BenchmarkFiles.writeAtomically(options.outputDirectory.resolve("summary.json"),
                summary.toJson(startedAt, finishedAt, wallClockDurationMillis) + System.lineSeparator());
        BenchmarkFiles.writeAtomically(options.outputDirectory.resolve("summary.csv"), summary.toCsv());
        BenchmarkFiles.writeAtomically(options.outputDirectory.resolve("summary.md"),
                summary.toMarkdown(options.evaluatedProfile, options.baselineProfile,
                        startedAt, finishedAt, wallClockDurationMillis));
    }

    private static String detectGitCommit() {
        try {
            final Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true).start();
            if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return null;
            }
            return new String(process.getInputStream().readAllBytes()).trim();
        } catch (Exception ignored) {
            return null;
        }
    }
}
