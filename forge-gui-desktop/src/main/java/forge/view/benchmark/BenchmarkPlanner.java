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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BenchmarkPlanner {
    private static final String SEED_VERSION = "sha256-v2";

    private BenchmarkPlanner() {
    }

    static List<BenchmarkJob> createJobs(final List<BenchmarkDeck> inputDecks,
            final int multiplier, final long masterSeed) {
        final List<BenchmarkDeck> decks = new ArrayList<>(inputDecks);
        decks.sort(Comparator.comparing(BenchmarkDeck::id));
        final List<BenchmarkJob> jobs = new ArrayList<>(2 * decks.size() * decks.size() * multiplier);
        for (BenchmarkDeck evaluatedDeck : decks) {
            for (BenchmarkDeck opponentDeck : decks) {
                for (int repetition = 0; repetition < multiplier; repetition++) {
                    final String pairKey = masterSeed + "|" + evaluatedDeck.id() + "|"
                            + evaluatedDeck.sha256() + "|" + opponentDeck.id() + "|"
                            + opponentDeck.sha256() + "|" + repetition;
                    final long seed = deriveSeed(pairKey);
                    for (int leg = 0; leg < 2; leg++) {
                        final String id = BenchmarkFiles.sha256(pairKey + "|" + leg).substring(0, 20);
                        jobs.add(new BenchmarkJob(id, evaluatedDeck, opponentDeck,
                                repetition, leg, seed, leg == 0));
                    }
                }
            }
        }
        return jobs;
    }

    static String seedVersion() {
        return SEED_VERSION;
    }

    private static long deriveSeed(final String key) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
