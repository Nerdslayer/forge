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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class BenchmarkGameResult implements BenchmarkJson.JsonMappable {
    static final int SCHEMA_VERSION = 1;

    enum Status {
        COMPLETED,
        TIMEOUT,
        CRASH,
        INVALID_INPUT
    }

    enum EvaluatedResult {
        WIN,
        LOSS,
        DRAW,
        NONE
    }

    String jobId;
    Status status = Status.CRASH;
    EvaluatedResult evaluatedResult = EvaluatedResult.NONE;
    String evaluatedDeckId;
    String evaluatedDeck;
    String evaluatedDeckSha256;
    String opponentDeckId;
    String opponentDeck;
    String opponentDeckSha256;
    String evaluatedProfile;
    String evaluatedProfileSha256;
    String baselineProfile;
    String baselineProfileSha256;
    int repetition;
    int leg;
    int evaluatedSeat;
    long seed;
    String startingRole;
    String winnerRole;
    String gameEndReason;
    int turns;
    long durationMillis;
    int lifeDelta;
    int evaluatedMulligans;
    int baselineMulligans;
    int childExitCode;
    String error;

    static BenchmarkGameResult forJob(final BenchmarkJob job) {
        final BenchmarkGameResult result = new BenchmarkGameResult();
        result.jobId = job.id();
        result.evaluatedDeckId = job.evaluatedDeck().id();
        result.evaluatedDeck = job.evaluatedDeck().name();
        result.evaluatedDeckSha256 = job.evaluatedDeck().sha256();
        result.opponentDeckId = job.opponentDeck().id();
        result.opponentDeck = job.opponentDeck().name();
        result.opponentDeckSha256 = job.opponentDeck().sha256();
        result.repetition = job.repetition();
        result.leg = job.leg();
        result.evaluatedSeat = job.evaluatedInSeatZero() ? 0 : 1;
        result.seed = job.seed();
        return result;
    }

    static BenchmarkGameResult read(final Path path) throws IOException {
        final Map<String, String> values = BenchmarkJson.parseFlatObject(Files.readString(path));
        final BenchmarkGameResult result = new BenchmarkGameResult();
        if (integer(values, "schemaVersion") != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported benchmark result schema in " + path);
        }
        result.jobId = values.get("jobId");
        result.status = Status.valueOf(values.get("status"));
        result.evaluatedResult = EvaluatedResult.valueOf(values.get("evaluatedResult"));
        result.evaluatedDeckId = values.get("evaluatedDeckId");
        result.evaluatedDeck = values.get("evaluatedDeck");
        result.evaluatedDeckSha256 = values.get("evaluatedDeckSha256");
        result.opponentDeckId = values.get("opponentDeckId");
        result.opponentDeck = values.get("opponentDeck");
        result.opponentDeckSha256 = values.get("opponentDeckSha256");
        result.evaluatedProfile = values.get("evaluatedProfile");
        result.evaluatedProfileSha256 = values.get("evaluatedProfileSha256");
        result.baselineProfile = values.get("baselineProfile");
        result.baselineProfileSha256 = values.get("baselineProfileSha256");
        result.repetition = integer(values, "repetition");
        result.leg = integer(values, "leg");
        result.evaluatedSeat = integer(values, "evaluatedSeat");
        result.seed = longValue(values, "seed");
        result.startingRole = values.get("startingRole");
        result.winnerRole = values.get("winnerRole");
        result.gameEndReason = values.get("gameEndReason");
        result.turns = integer(values, "turns");
        result.durationMillis = longValue(values, "durationMillis");
        result.lifeDelta = integer(values, "lifeDelta");
        result.evaluatedMulligans = integer(values, "evaluatedMulligans");
        result.baselineMulligans = integer(values, "baselineMulligans");
        result.childExitCode = integer(values, "childExitCode");
        result.error = values.get("error");
        return result;
    }

    boolean isCompleted() {
        return status == Status.COMPLETED;
    }

    @Override
    public Map<String, Object> toJsonMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", SCHEMA_VERSION);
        map.put("jobId", jobId);
        map.put("status", status.name());
        map.put("evaluatedResult", evaluatedResult.name());
        map.put("evaluatedDeckId", evaluatedDeckId);
        map.put("evaluatedDeck", evaluatedDeck);
        map.put("evaluatedDeckSha256", evaluatedDeckSha256);
        map.put("opponentDeckId", opponentDeckId);
        map.put("opponentDeck", opponentDeck);
        map.put("opponentDeckSha256", opponentDeckSha256);
        map.put("evaluatedProfile", evaluatedProfile);
        map.put("evaluatedProfileSha256", evaluatedProfileSha256);
        map.put("baselineProfile", baselineProfile);
        map.put("baselineProfileSha256", baselineProfileSha256);
        map.put("repetition", repetition);
        map.put("leg", leg);
        map.put("evaluatedSeat", evaluatedSeat);
        map.put("seed", seed);
        map.put("startingRole", startingRole);
        map.put("winnerRole", winnerRole);
        map.put("gameEndReason", gameEndReason);
        map.put("turns", turns);
        map.put("durationMillis", durationMillis);
        map.put("lifeDelta", lifeDelta);
        map.put("evaluatedMulligans", evaluatedMulligans);
        map.put("baselineMulligans", baselineMulligans);
        map.put("childExitCode", childExitCode);
        map.put("error", error);
        return map;
    }

    String toJson() {
        return BenchmarkJson.toJson(toJsonMap());
    }

    private static int integer(final Map<String, String> values, final String key) {
        final String value = values.get(key);
        return value == null || value.isEmpty() ? 0 : Integer.parseInt(value);
    }

    private static long longValue(final Map<String, String> values, final String key) {
        final String value = values.get(key);
        return value == null || value.isEmpty() ? 0 : Long.parseLong(value);
    }
}
