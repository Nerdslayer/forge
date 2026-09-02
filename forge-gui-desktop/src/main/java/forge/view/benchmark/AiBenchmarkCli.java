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

public final class AiBenchmarkCli {
    private AiBenchmarkCli() {
    }

    public static int run(final String[] args) {
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new BenchmarkGuiDesktop());
        if (args.length == 0) {
            System.err.println(BenchmarkOptions.helpText());
            return 2;
        }
        if ("benchmark-game".equalsIgnoreCase(args[0])) {
            return BenchmarkGameWorker.run(args);
        }
        try {
            final BenchmarkOptions options = BenchmarkOptions.parse(args);
            if (options.help) {
                System.out.println(BenchmarkOptions.helpText());
                return 0;
            }
            options.validate();
            return new AiBenchmarkCoordinator(options).run();
        } catch (Exception e) {
            System.err.println("AI benchmark failed: " + e.getMessage());
            System.err.println();
            System.err.println(BenchmarkOptions.helpText());
            return 2;
        }
    }
}
