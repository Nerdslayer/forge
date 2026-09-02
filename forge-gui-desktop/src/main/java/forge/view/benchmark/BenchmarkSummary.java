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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

final class BenchmarkSummary {
    static final class Stats implements BenchmarkJson.JsonMappable {
        int wins;
        int losses;
        int draws;
        int timeouts;
        int errors;
        long turns;
        long durationMillis;

        void add(final BenchmarkGameResult result) {
            if (!result.isCompleted()) {
                if (result.status == BenchmarkGameResult.Status.TIMEOUT) {
                    timeouts++;
                } else {
                    errors++;
                }
                return;
            }
            switch (result.evaluatedResult) {
                case WIN -> wins++;
                case LOSS -> losses++;
                case DRAW -> draws++;
                case NONE -> errors++;
            }
            turns += result.turns;
            durationMillis += result.durationMillis;
        }

        int completed() {
            return wins + losses + draws;
        }

        double winRate() {
            return completed() == 0 ? 0 : (double) wins / completed();
        }

        @Override
        public Map<String, Object> toJsonMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("completed", completed());
            map.put("wins", wins);
            map.put("losses", losses);
            map.put("draws", draws);
            map.put("winRate", winRate());
            map.put("timeouts", timeouts);
            map.put("errors", errors);
            map.put("averageTurns", completed() == 0 ? 0 : (double) turns / completed());
            map.put("averageDurationMillis", completed() == 0 ? 0 : (double) durationMillis / completed());
            return map;
        }
    }

    private final Stats overall = new Stats();
    private final Map<String, Stats> byEvaluatedDeck = new LinkedHashMap<>();
    private final Map<String, Stats> byOpponentDeck = new LinkedHashMap<>();
    private final Map<String, Stats> byMatchup = new LinkedHashMap<>();
    private final Map<String, String> evaluatedLabels = new LinkedHashMap<>();
    private final Map<String, String> opponentLabels = new LinkedHashMap<>();

    BenchmarkSummary(final List<BenchmarkGameResult> results) {
        final List<BenchmarkGameResult> ordered = new ArrayList<>(results);
        ordered.sort(Comparator.comparing(result -> result.jobId));
        for (BenchmarkGameResult result : ordered) {
            overall.add(result);
            add(byEvaluatedDeck, result.evaluatedDeckId, result);
            add(byOpponentDeck, result.opponentDeckId, result);
            add(byMatchup, matchupKey(result.evaluatedDeckId, result.opponentDeckId), result);
            evaluatedLabels.putIfAbsent(result.evaluatedDeckId,
                    result.evaluatedDeck + " [" + result.evaluatedDeckId + "]");
            opponentLabels.putIfAbsent(result.opponentDeckId,
                    result.opponentDeck + " [" + result.opponentDeckId + "]");
        }
    }

    Stats overall() {
        return overall;
    }

    Map<String, Stats> byEvaluatedDeck() {
        return byEvaluatedDeck;
    }

    Map<String, Stats> byOpponentDeck() {
        return byOpponentDeck;
    }

    private static void add(final Map<String, Stats> target, final String key,
            final BenchmarkGameResult result) {
        target.computeIfAbsent(key, ignored -> new Stats()).add(result);
    }

    private static String matchupKey(final String evaluatedDeckId, final String opponentDeckId) {
        return evaluatedDeckId + "|" + opponentDeckId;
    }

    String toJson() {
        return toJson(null, null, -1);
    }

    String toJson(final Instant startedAt, final Instant finishedAt, final long wallClockDurationMillis) {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        if (wallClockDurationMillis >= 0) {
            root.put("startedAt", startedAt.toString());
            root.put("finishedAt", finishedAt.toString());
            root.put("wallClockDurationMillis", wallClockDurationMillis);
        }
        root.put("overall", overall.toJsonMap());
        root.put("byEvaluatedDeck", mappedStats(byEvaluatedDeck, evaluatedLabels));
        root.put("byOpponentDeck", mappedStats(byOpponentDeck, opponentLabels));
        final List<Map<String, Object>> matchups = new ArrayList<>();
        for (Map.Entry<String, Stats> entry : byMatchup.entrySet()) {
            final String[] ids = entry.getKey().split("\\|", -1);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("evaluatedDeckId", ids[0]);
            row.put("evaluatedDeck", evaluatedLabels.get(ids[0]));
            row.put("opponentDeckId", ids[1]);
            row.put("opponentDeck", opponentLabels.get(ids[1]));
            row.putAll(entry.getValue().toJsonMap());
            matchups.add(row);
        }
        root.put("matchups", matchups);
        return BenchmarkJson.toJson(root);
    }

    private static List<Map<String, Object>> mappedStats(final Map<String, Stats> stats,
            final Map<String, String> labels) {
        final List<Map<String, Object>> values = new ArrayList<>();
        for (Map.Entry<String, Stats> entry : stats.entrySet()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("deckId", entry.getKey());
            row.put("deck", labels.get(entry.getKey()));
            row.putAll(entry.getValue().toJsonMap());
            values.add(row);
        }
        return values;
    }

    String toCsv() {
        final StringBuilder out = new StringBuilder();
        out.append("dimension,evaluated_deck,opponent_deck,completed,wins,losses,draws,win_rate,timeouts,errors")
                .append(System.lineSeparator());
        appendCsv(out, "overall", "", "", overall);
        appendDimension(out, "evaluated_deck", byEvaluatedDeck, evaluatedLabels, key -> key, ignored -> "");
        appendDimension(out, "opponent_deck", byOpponentDeck, opponentLabels, ignored -> "", key -> key);
        for (Map.Entry<String, Stats> entry : byMatchup.entrySet()) {
            final String[] ids = entry.getKey().split("\\|", -1);
            appendCsv(out, "matchup", evaluatedLabels.get(ids[0]), opponentLabels.get(ids[1]), entry.getValue());
        }
        return out.toString();
    }

    private static void appendDimension(final StringBuilder out, final String dimension,
            final Map<String, Stats> values, final Map<String, String> labels,
            final Function<String, String> evaluatedId, final Function<String, String> opponentId) {
        for (Map.Entry<String, Stats> entry : values.entrySet()) {
            final String evaluated = evaluatedId.apply(entry.getKey()).isEmpty()
                    ? "" : labels.get(entry.getKey());
            final String opponent = opponentId.apply(entry.getKey()).isEmpty()
                    ? "" : labels.get(entry.getKey());
            appendCsv(out, dimension, evaluated, opponent, entry.getValue());
        }
    }

    private static void appendCsv(final StringBuilder out, final String dimension,
            final String evaluatedDeck, final String opponentDeck, final Stats stats) {
        out.append(csv(dimension)).append(',').append(csv(evaluatedDeck)).append(',').append(csv(opponentDeck))
                .append(',').append(stats.completed()).append(',').append(stats.wins).append(',').append(stats.losses)
                .append(',').append(stats.draws).append(',')
                .append(String.format(Locale.ROOT, "%.6f", stats.winRate())).append(',')
                .append(stats.timeouts).append(',').append(stats.errors).append(System.lineSeparator());
    }

    private static String csv(final String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    String toMarkdown(final String evaluatedProfile, final String baselineProfile) {
        return toMarkdown(evaluatedProfile, baselineProfile, null, null, -1);
    }

    String toMarkdown(final String evaluatedProfile, final String baselineProfile,
            final Instant startedAt, final Instant finishedAt, final long wallClockDurationMillis) {
        final StringBuilder out = new StringBuilder();
        out.append("# AI Benchmark Summary\n\n")
                .append("Evaluated profile: `").append(evaluatedProfile).append("`  \n")
                .append("Baseline profile: `").append(baselineProfile).append("`");
        if (wallClockDurationMillis >= 0) {
            out.append("  \nStarted: `").append(startedAt).append("`  \n")
                    .append("Finished: `").append(finishedAt).append("`  \n")
                    .append("Wall-clock duration: **").append(formatDuration(wallClockDurationMillis))
                    .append("** (`").append(wallClockDurationMillis).append(" ms`)");
        }
        out.append("\n\n")
                .append("## Overall\n\n")
                .append("Completed: ").append(overall.completed()).append(", wins: ").append(overall.wins)
                .append(", losses: ").append(overall.losses).append(", draws: ").append(overall.draws)
                .append(", win rate: ").append(percent(overall.winRate())).append(".  \n")
                .append("Timeouts: ").append(overall.timeouts).append(", errors: ").append(overall.errors).append(".\n\n")
                .append("## By evaluated deck\n\n")
                .append("| Deck | W-L-D | Win rate | Timeouts | Errors |\n")
                .append("|---|---:|---:|---:|---:|\n");
        appendMarkdownRows(out, byEvaluatedDeck, evaluatedLabels);
        out.append("\n## By opposing deck\n\n")
                .append("| Deck | W-L-D | Win rate | Timeouts | Errors |\n")
                .append("|---|---:|---:|---:|---:|\n");
        appendMarkdownRows(out, byOpponentDeck, opponentLabels);
        out.append("\n## Matchup matrix\n\n| Evaluated deck \\ Opposing deck |");
        for (String opponentId : opponentLabels.keySet()) {
            out.append(' ').append(opponentLabels.get(opponentId)).append(" |");
        }
        out.append("\n|---|").append("---:|".repeat(opponentLabels.size())).append('\n');
        for (String evaluatedId : evaluatedLabels.keySet()) {
            out.append("| ").append(evaluatedLabels.get(evaluatedId)).append(" |");
            for (String opponentId : opponentLabels.keySet()) {
                final Stats stats = byMatchup.get(matchupKey(evaluatedId, opponentId));
                out.append(' ').append(stats == null ? "—" : percent(stats.winRate()) + " ("
                        + stats.wins + '-' + stats.losses + '-' + stats.draws + ")").append(" |");
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void appendMarkdownRows(final StringBuilder out, final Map<String, Stats> values,
            final Map<String, String> labels) {
        for (Map.Entry<String, Stats> entry : values.entrySet()) {
            final Stats stats = entry.getValue();
            out.append("| ").append(labels.get(entry.getKey())).append(" | ")
                    .append(stats.wins).append('-').append(stats.losses).append('-').append(stats.draws)
                    .append(" | ").append(percent(stats.winRate())).append(" | ")
                    .append(stats.timeouts).append(" | ").append(stats.errors).append(" |\n");
        }
    }

    private static String percent(final double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    static String formatDuration(final long durationMillis) {
        final long totalSeconds = durationMillis / 1000;
        final long hours = totalSeconds / 3600;
        final long minutes = totalSeconds % 3600 / 60;
        final long seconds = totalSeconds % 60;
        final long millis = durationMillis % 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }
}
