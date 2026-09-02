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

record BenchmarkDeck(String id, String name, Path path, String sha256) implements BenchmarkJson.JsonMappable {
    static BenchmarkDeck fromPath(final Path input) throws java.io.IOException {
        final Path path = input.toAbsolutePath().normalize();
        final String fileName = path.getFileName().toString();
        final String name = fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".dck")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        final String hash = BenchmarkFiles.sha256(path);
        final String id = BenchmarkFiles.sha256(path.toString() + "|" + hash).substring(0, 12);
        return new BenchmarkDeck(id, name, path, hash);
    }

    String label() {
        return name + " [" + id + "]";
    }

    @Override
    public Map<String, Object> toJsonMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("name", name);
        map.put("path", path.toString());
        map.put("sha256", sha256);
        return map;
    }
}
