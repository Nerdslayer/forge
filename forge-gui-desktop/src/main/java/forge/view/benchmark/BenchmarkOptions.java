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

import forge.localinstance.properties.ForgeConstants;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class BenchmarkOptions {
    private static final DateTimeFormatter OUTPUT_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DEFAULT_DECK_POOL = "TwentyGeneticDecks";

    String evaluatedProfile;
    String baselineProfile;
    Path deckDirectory;
    final List<Path> deckFiles = new ArrayList<>();
    Path profileDirectory = Path.of(ForgeConstants.AI_PROFILE_DIR).toAbsolutePath().normalize();
    Path outputDirectory;
    long masterSeed = new java.security.SecureRandom().nextLong();
    boolean seedProvided;
    int repetitions = 1;
    int workers = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    long timeoutSeconds = 120;
    String workerHeap = "512m";
    boolean resume;
    boolean help;

    static BenchmarkOptions parse(final String[] args) {
        final BenchmarkOptions options = new BenchmarkOptions();
        for (int index = 1; index < args.length; index++) {
            final String argument = args[index];
            switch (argument) {
                case "--evaluated-profile" -> options.evaluatedProfile = value(args, ++index, argument);
                case "--baseline-profile" -> options.baselineProfile = value(args, ++index, argument);
                case "--deck-dir" -> options.deckDirectory = Path.of(value(args, ++index, argument));
                case "--deck" -> options.deckFiles.add(Path.of(value(args, ++index, argument)));
                case "--profile-dir" -> options.profileDirectory = Path.of(value(args, ++index, argument));
                case "--output" -> options.outputDirectory = Path.of(value(args, ++index, argument));
                case "--seed" -> {
                    options.masterSeed = parseLong(value(args, ++index, argument), argument);
                    options.seedProvided = true;
                }
                case "--repetitions" -> options.repetitions = parseInt(value(args, ++index, argument), argument);
                case "--workers" -> options.workers = parseInt(value(args, ++index, argument), argument);
                case "--timeout-seconds" -> options.timeoutSeconds = parseLong(value(args, ++index, argument), argument);
                case "--worker-heap" -> options.workerHeap = value(args, ++index, argument);
                case "--resume" -> options.resume = true;
                case "--help", "-h" -> options.help = true;
                default -> throw new IllegalArgumentException("Unknown benchmark option: " + argument);
            }
        }
        if (options.deckDirectory == null && options.deckFiles.isEmpty()) {
            options.deckDirectory = Path.of(ForgeConstants.RES_DIR, "benchmark", DEFAULT_DECK_POOL);
        }
        if (options.outputDirectory == null) {
            options.outputDirectory = Path.of("ai-benchmark-results",
                    "ai-benchmark-" + LocalDateTime.now().format(OUTPUT_TIMESTAMP));
        }
        options.profileDirectory = options.profileDirectory.toAbsolutePath().normalize();
        options.outputDirectory = options.outputDirectory.toAbsolutePath().normalize();
        if (options.deckDirectory != null) {
            options.deckDirectory = options.deckDirectory.toAbsolutePath().normalize();
        }
        return options;
    }

    void validate() throws IOException {
        if (help) {
            return;
        }
        if (evaluatedProfile == null || evaluatedProfile.isBlank()) {
            throw new IllegalArgumentException("--evaluated-profile is required");
        }
        if (baselineProfile == null || baselineProfile.isBlank()) {
            throw new IllegalArgumentException("--baseline-profile is required");
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException("--repetitions must be at least 1");
        }
        if (workers < 1) {
            throw new IllegalArgumentException("--workers must be at least 1");
        }
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("--timeout-seconds must be at least 1");
        }
        if (resume && !seedProvided) {
            throw new IllegalArgumentException("--resume requires the original --seed");
        }
        if (!workerHeap.matches("[1-9][0-9]*[kKmMgG]?")) {
            throw new IllegalArgumentException("--worker-heap must look like 512m or 2g");
        }
        if (!Files.isDirectory(profileDirectory)) {
            throw new IllegalArgumentException("AI profile directory does not exist: " + profileDirectory);
        }
        evaluatedProfile = profileName(profilePath(evaluatedProfile));
        baselineProfile = profileName(profilePath(baselineProfile));
        if (deckDirectory == null && deckFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one --deck-dir or --deck is required");
        }
        if (deckDirectory != null && !Files.isDirectory(deckDirectory)) {
            throw new IllegalArgumentException("Deck directory does not exist: " + deckDirectory);
        }
        if (Files.exists(outputDirectory) && !resume) {
            try (Stream<Path> children = Files.list(outputDirectory)) {
                if (children.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output directory is not empty; use --resume or another --output: "
                            + outputDirectory);
                }
            }
        }
    }

    List<BenchmarkDeck> loadDecks() throws IOException {
        final Set<Path> paths = new LinkedHashSet<>();
        if (deckDirectory != null) {
            try (Stream<Path> children = Files.list(deckDirectory)) {
                children.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dck"))
                        .map(path -> path.toAbsolutePath().normalize())
                        .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(left.toString(), right.toString()))
                        .forEach(paths::add);
            }
        }
        for (Path input : deckFiles) {
            final Path path = input.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Deck file does not exist: " + path);
            }
            if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dck")) {
                throw new IllegalArgumentException("Deck file must use the .dck extension: " + path);
            }
            paths.add(path);
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("No .dck files were found");
        }
        final List<BenchmarkDeck> decks = new ArrayList<>();
        for (Path path : paths) {
            decks.add(BenchmarkDeck.fromPath(path));
        }
        final Map<String, Path> hashes = new LinkedHashMap<>();
        for (BenchmarkDeck deck : decks) {
            final Path previous = hashes.putIfAbsent(deck.id(), deck.path());
            if (previous != null && !previous.equals(deck.path())) {
                throw new IllegalArgumentException("Deck ID collision between " + previous + " and " + deck.path());
            }
        }
        return decks;
    }

    Path profilePath(final String profile) throws IOException {
        try (Stream<Path> children = Files.list(profileDirectory)) {
            return children.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(profile + ".ai"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown AI profile '" + profile
                            + "' in " + profileDirectory));
        }
    }

    private static String profileName(final Path path) {
        final String fileName = path.getFileName().toString();
        return fileName.substring(0, fileName.length() - 3);
    }

    private static String value(final String[] args, final int index, final String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static int parseInt(final String value, final String option) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + option + ": " + value, e);
        }
    }

    private static long parseLong(final String value, final String option) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for " + option + ": " + value, e);
        }
    }

    static String helpText() {
        return """
                AI Benchmark Runner (Constructed)

                Usage:
                  forge benchmark --evaluated-profile <name> --baseline-profile <name>
                      [--deck-dir <directory>] [--deck <file> ...] [options]

                Options:
                  --deck-dir <directory>     Deck pool directory
                                             (default: res/benchmark/TwentyGeneticDecks)
                  --deck <file>              Add an explicit deck file; may be repeated
                  --profile-dir <directory>  AI profile directory (default: Forge res/ai)
                  --output <directory>       Run artifact directory
                  --seed <long>              Master seed (generated and recorded when omitted)
                  --repetitions <count>      Mirrored pairs per deck cell (default: 1)
                  --workers <count>          Maximum concurrent child JVMs (default: min(CPUs, 4))
                  --timeout-seconds <count>  Hard timeout for each game (default: 120)
                  --worker-heap <size>       Maximum heap per worker (default: 512m)
                  --resume                   Reuse completed per-game result files
                  --help                     Show this help
                """;
    }
}
