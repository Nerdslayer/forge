package forge.screens.workshop.controllers;

import forge.item.PaperCard;
import forge.screens.workshop.cardcreator.CardEditorSession;
import forge.screens.workshop.views.VCardDesigner;
import forge.screens.workshop.views.VCardEvaluation;
import forge.screens.workshop.views.VWorkshopCatalog;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;

import java.io.IOException;
import java.util.List;

/** Central controller for the structured Card Creator session and its Workshop views. */
public enum CCardCreator {
    SINGLETON_INSTANCE;

    private final CardEditorSession session = new CardEditorSession();
    private boolean updatingViews;

    CCardCreator() {
        session.setChangeListener(ignored -> refreshViews());
    }

    public CardEditorSession getSession() {
        return session;
    }

    public boolean showCard(final PaperCard card) {
        if (session.isDirty() && !confirmSave()) return false;
        session.load(card);
        refreshViews();
        return true;
    }

    public void newCard() {
        if (session.isDirty() && !confirmSave()) return;
        session.createNew();
        refreshViews();
    }

    public void createEditableCopy() {
        session.createEditableCopy();
        refreshViews();
    }

    public boolean canSwitchAway() {
        return !session.isDirty() || confirmSave();
    }

    public boolean save() {
        try {
            final PaperCard saved = session.save();
            VWorkshopCatalog.SINGLETON_INSTANCE.refreshPool(saved);
            VWorkshopCatalog.SINGLETON_INSTANCE.getCardManager().setSelectedItem(saved);
            refreshViews();
            return true;
        } catch (final IOException ex) {
            FOptionPane.showErrorDialog(ex.getMessage(), Localizer.getInstance().getMessageorUseDefault("lblCardCreatorTitle", "Card Creator"));
            return false;
        }
    }

    public void saveAs() {
        if (!session.isEditable() || session.getDraft() == null) return;
        final String currentName = session.getDraft().getName();
        final String name = FOptionPane.showInputDialog(
                Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSaveAsName", "New card name:"),
                Localizer.getInstance().getMessageorUseDefault("lblCardCreatorTitle", "Card Creator"),
                null, currentName + " Copy");
        if (name == null || name.isBlank()) return;
        session.prepareSaveAs(name);
        save();
    }

    public void createSet() {
        final String code = FOptionPane.showInputDialog(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSetCode", "Set code (letters and numbers):"), "New Custom Set", null, "");
        if (code == null) return;
        final String name = FOptionPane.showInputDialog(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSetName", "Set name:"), "New Custom Set", null, "");
        if (name == null) return;
        try {
            session.createCustomSet(code, name);
            VCardDesigner.SINGLETON_INSTANCE.reloadSets(session.getCustomSets());
            refreshViews();
        } catch (final IOException ex) {
            FOptionPane.showErrorDialog(ex.getMessage(), Localizer.getInstance().getMessageorUseDefault("lblCardCreatorTitle", "Card Creator"));
        }
    }

    public void refreshViews() {
        if (updatingViews) return;
        updatingViews = true;
        try {
            VCardDesigner.SINGLETON_INSTANCE.loadSession(session);
            VCardEvaluation.SINGLETON_INSTANCE.loadSession(session);
            CCardScript.SINGLETON_INSTANCE.refresh();
        } finally {
            updatingViews = false;
        }
    }

    private boolean confirmSave() {
        final int choice = FOptionPane.showOptionDialog(
                Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSaveChanges", "Save changes to the current card?"),
                Localizer.getInstance().getMessageorUseDefault("lblCardCreatorTitle", "Card Creator"), FOptionPane.QUESTION_ICON,
                List.of("Save", "Don't Save", "Cancel"));
        if (choice == 0) return save();
        return choice == 1;
    }
}
