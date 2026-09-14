package forge.screens.workshop.controllers;

import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.util.Map.Entry;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Style;
import javax.swing.text.StyledDocument;

import forge.gui.card.CardScriptInfo;
import forge.gui.card.CardScriptParser;
import forge.gui.framework.ICDoc;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.screens.workshop.menus.WorkshopFileMenu;
import forge.screens.workshop.views.VCardScript;
import forge.screens.workshop.views.VWorkshopCatalog;

/**
 * Controls the "card script" panel in the workshop UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 *
 */
public enum CCardScript implements ICDoc {
    SINGLETON_INSTANCE;

    private PaperCard currentCard;
    private CardScriptInfo currentScriptInfo;
    private boolean isTextDirty;
    private boolean switchInProgress;
    private boolean refreshing;

    CCardScript() {
        VCardScript.SINGLETON_INSTANCE.getTxtScript().getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void removeUpdate(final DocumentEvent arg0) {
                importRawTextIfNeeded();
                updateDirtyFlag();
            }
            @Override
            public void insertUpdate(final DocumentEvent arg0) {
                importRawTextIfNeeded();
                updateDirtyFlag();
            }
            @Override
            public void changedUpdate(final DocumentEvent arg0) {
                //Plain text components do not fire these events
            }
        });
        VCardScript.SINGLETON_INSTANCE.getTxtScript().addFocusListener(new FocusListener() {
            @Override
            public void focusLost(final FocusEvent e) {
                refresh();
            }
            @Override
            public void focusGained(final FocusEvent e) {
            }
        });
    }

    private void updateDirtyFlag() {
        final boolean isTextNowDirty = !refreshing && CCardCreator.SINGLETON_INSTANCE.getSession().isDirty();
        if (isTextDirty == isTextNowDirty) { return; }
        isTextDirty = isTextNowDirty;
        VCardScript.SINGLETON_INSTANCE.getTabLabel().setText((isTextNowDirty ? "*" : "") + "Card Script");
        WorkshopFileMenu.updateSaveEnabled();
    }

    private void importRawTextIfNeeded() {
        if (!refreshing && !VCardScript.SINGLETON_INSTANCE.isLoadingSession()
                && CCardCreator.SINGLETON_INSTANCE.getSession().isEditable()) {
            final String editedText = VCardScript.SINGLETON_INSTANCE.getTxtScript().getText();
            // Defer the session update until the document notification completes. The session
            // listener refreshes this same text component, which Swing forbids during a callback.
            SwingUtilities.invokeLater(() -> {
                if (!refreshing && !VCardScript.SINGLETON_INSTANCE.isLoadingSession()
                        && CCardCreator.SINGLETON_INSTANCE.getSession().isEditable()
                        && editedText.equals(VCardScript.SINGLETON_INSTANCE.getTxtScript().getText())) {
                    CCardCreator.SINGLETON_INSTANCE.getSession().setRawScript(editedText);
                }
            });
        }
    }

    public PaperCard getCurrentCard() {
        return currentCard;
    }

    public void showCard(final PaperCard card) {
        showCard(card, null);
    }

    public void showCard(final PaperCard card, final CardManager source) {
        if ((currentCard == card && CCardCreator.SINGLETON_INSTANCE.getSession().getSelectedCard() == card)
                || switchInProgress) { return; }

        if (!CCardCreator.SINGLETON_INSTANCE.showCard(card)) { //ensure current card saved before changing to a different card
            if (source != null) source.setSelectedItem(currentCard);
            else VWorkshopCatalog.SINGLETON_INSTANCE.restoreSelection(currentCard);
            return;
        }

        currentCard = card;
        currentScriptInfo = card != null ? CardScriptInfo.getScriptFor(currentCard.getRules().getNormalizedName()) : null;
        CCardCreator.SINGLETON_INSTANCE.refreshViews();
    }

    /** Clears the catalog card identity when the creator switches to an unsaved draft. */
    public void clearCurrentCard() {
        currentCard = null;
        currentScriptInfo = null;
    }

    public void refresh() {
        if (refreshing) { return; }
        refreshing = true;
        final JTextPane txtScript = VCardScript.SINGLETON_INSTANCE.getTxtScript();
        VCardScript.SINGLETON_INSTANCE.loadSession(CCardCreator.SINGLETON_INSTANCE.getSession());
        txtScript.setCaretPosition(0); //keep scrolled to top

        final StyledDocument doc = VCardScript.SINGLETON_INSTANCE.getDoc();
        final Style error = VCardScript.SINGLETON_INSTANCE.getErrorStyle();
        final Style empty = VCardScript.SINGLETON_INSTANCE.getEmptyStyle();
        doc.setCharacterAttributes(0, 9999, empty, true);
        if (CCardCreator.SINGLETON_INSTANCE.getSession().getRawScript() != null) {
            for (final Entry<Integer, Integer> region : new CardScriptParser(CCardCreator.SINGLETON_INSTANCE.getSession().getRawScript()).getErrorRegions().entrySet()) {
                doc.setCharacterAttributes(region.getKey(), region.getValue(), error, true);
            }
        }
        refreshing = false;
        updateDirtyFlag();
    }

    public boolean hasChanges() {
        return CCardCreator.SINGLETON_INSTANCE.getSession().isDirty();
    }
    public boolean canSwitchAway(final boolean isCardChanging) {
        return !switchInProgress && CCardCreator.SINGLETON_INSTANCE.canSwitchAway();
    }

    public boolean saveChanges() {
        return CCardCreator.SINGLETON_INSTANCE.save();
    }

    //========== Overridden methods

    @Override
    public void register() {
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.ICDoc#initialize()
     */
    @Override
    public void initialize() {
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.ICDoc#update()
     */
    @Override
    public void update() {
    }
}
