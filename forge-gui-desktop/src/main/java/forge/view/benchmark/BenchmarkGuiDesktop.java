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

import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

final class BenchmarkGuiDesktop implements IGuiBase {
    @Override
    public boolean isRunningOnDesktop() {
        return true;
    }

    @Override
    public boolean isLibgdxPort() {
        return false;
    }

    @Override
    public String getCurrentVersion() {
        return BuildInfo.getVersionString();
    }

    @Override
    public void invokeInEdtNow(final Runnable runnable) {
        runnable.run();
    }

    @Override
    public void invokeInEdtLater(final Runnable runnable) {
        runnable.run();
    }

    @Override
    public void invokeInEdtAndWait(final Runnable runnable) {
        runnable.run();
    }

    @Override
    public void runBackgroundTask(final String message, final Runnable task) {
        task.run();
    }

    @Override
    public boolean isGuiThread() {
        return true;
    }

    @Override
    public String getAssetsDir() {
        return BuildInfo.isDevelopmentVersion() ? "../forge-gui/" : "";
    }

    @Override
    public ImageFetcher getImageFetcher() {
        return null;
    }

    @Override
    public ISkinImage getSkinIcon(final FSkinProp skinProp) {
        return null;
    }

    @Override
    public ISkinImage getUnskinnedIcon(final String path) {
        return null;
    }

    @Override
    public ISkinImage getCardArt(final PaperCard card, final boolean backFace) {
        return null;
    }

    @Override
    public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background,
            final String overlayFilename, final float opacity) {
        return null;
    }

    @Override
    public void clearImageCache() {
    }

    @Override
    public String encodeSymbols(final String value, final boolean formatReminderText) {
        return value;
    }

    @Override
    public int getAvatarCount() {
        return 0;
    }

    @Override
    public int getSleevesCount() {
        return 0;
    }

    @Override
    public float getScreenScale() {
        return 1;
    }

    @Override
    public void preventSystemSleep(final boolean preventSleep) {
    }

    @Override
    public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(false);
        }
    }

    @Override
    public void copyToClipboard(final String text) {
    }

    @Override
    public void browseToUrl(final String url) {
    }

    @Override
    public void showCardList(final String title, final String message, final List<PaperCard> list) {
    }

    @Override
    public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) {
        return false;
    }

    @Override
    public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        System.err.println("[AI benchmark] " + title + ": " + text);
    }

    @Override
    public void showImageDialog(final ISkinImage image, final String message, final String title) {
        unexpected(title, message);
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
            final List<String> options, final int defaultOption) {
        unexpected(title, message);
        return defaultOption;
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
            final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        unexpected(title, message);
        if (initialInput != null) {
            return initialInput;
        }
        return inputOptions != null && !inputOptions.isEmpty() ? inputOptions.get(0) : isNumeric ? "0" : "";
    }

    @Override
    public String showFileDialog(final String title, final String defaultDir) {
        return null;
    }

    @Override
    public File getSaveFile(final File defaultFile) {
        return null;
    }

    @Override
    public <T> List<T> order(final String title, final String top,
            final int remainingObjectsMin, final int remainingObjectsMax,
            final List<T> sourceChoices, final List<T> destChoices) {
        final List<T> result = new ArrayList<>();
        if (destChoices != null) {
            result.addAll(destChoices);
        }
        if (sourceChoices != null) {
            result.addAll(sourceChoices);
        }
        return result;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
            final Collection<T> choices, final Collection<T> selected,
            final FSerializableFunction<T, String> display) {
        final List<T> result = new ArrayList<>();
        if (selected != null) {
            result.addAll(selected);
        }
        if (choices != null) {
            for (T choice : choices) {
                if (max >= 0 && result.size() >= max) {
                    break;
                }
                if (!result.contains(choice)) {
                    result.add(choice);
                }
            }
        }
        return result;
    }

    @Override
    public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    public boolean isSupportedAudioFormat(final File file) {
        return false;
    }

    @Override
    public IAudioClip createAudioClip(final String filename) {
        return null;
    }

    @Override
    public IAudioMusic createAudioMusic(final String filename) {
        return null;
    }

    @Override
    public void startAltSoundSystem(final String filename, final boolean isSynchronized) {
    }

    @Override
    public void showSpellShop() {
    }

    @Override
    public void showBazaar() {
    }

    @Override
    public IGuiGame getNewGuiGame() {
        return null;
    }

    @Override
    public HostedMatch hostMatch() {
        return new HostedMatch();
    }

    @Override
    public UpnpServiceConfiguration getUpnpPlatformService() {
        return null;
    }

    @Override
    public boolean hasNetGame() {
        return false;
    }

    private static void unexpected(final String title, final String message) {
        System.err.println("[AI benchmark] Unexpected dialog: " + title + ": " + message);
    }
}
