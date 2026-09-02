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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

record BenchmarkJob(String id, BenchmarkDeck evaluatedDeck, BenchmarkDeck opponentDeck,
        int repetition, int leg, long seed, boolean evaluatedInSeatZero) implements BenchmarkJson.JsonMappable {
    String evaluatedSeat() {
        return evaluatedInSeatZero ? "0" : "1";
    }

    Path resultPath(final Path outputDirectory) {
        return outputDirectory.resolve("games").resolve(id + ".json");
    }

    Path logPath(final Path outputDirectory) {
        return outputDirectory.resolve("games").resolve(id + ".log");
    }

    @Override
    public Map<String, Object> toJsonMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobId", id);
        map.put("evaluatedDeckId", evaluatedDeck.id());
        map.put("evaluatedDeck", evaluatedDeck.name());
        map.put("opponentDeckId", opponentDeck.id());
        map.put("opponentDeck", opponentDeck.name());
        map.put("repetition", repetition);
        map.put("leg", leg);
        map.put("seed", seed);
        map.put("evaluatedSeat", Integer.parseInt(evaluatedSeat()));
        return map;
    }
}
