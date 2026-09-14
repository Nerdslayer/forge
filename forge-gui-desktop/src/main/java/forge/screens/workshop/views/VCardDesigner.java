package forge.screens.workshop.views;

import forge.gui.cardcreator.CardCreatorKeyword;
import forge.gui.cardcreator.CardCreatorKeywords;
import forge.gui.cardcreator.CardEditorDraft;
import forge.gui.cardcreator.CustomSetInfo;
import forge.card.CardType;
import forge.gui.UiCommand;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.screens.workshop.cardcreator.CardEditorSession;
import forge.screens.workshop.controllers.CCardCreator;
import forge.toolbox.FCheckBox;
import forge.toolbox.FComboBox;
import forge.toolbox.FButton;
import forge.toolbox.FSkin;
import forge.toolbox.FTextField;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured basic-card editor used by the Workshop Card Creator. */
public enum VCardDesigner implements IVDoc<forge.screens.workshop.controllers.CCardDesigner> {
    SINGLETON_INSTANCE;

    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblCardDesigner"));
    private final FTextField name = new FTextField.Builder().build();
    private final FTextField manaCost = new FTextField.Builder().build();
    private final FTextField subtypes = new FTextField.Builder().build();
    private final FTextField power = new FTextField.Builder().build();
    private final FTextField toughness = new FTextField.Builder().build();
    private final FTextField collectorNumber = new FTextField.Builder().text("1").build();
    private final FComboBox<CustomSetInfo> customSet = new FComboBox<>();
    private final FComboBox<String> rarity = new FComboBox<>(new String[] {"C", "U", "R", "M", "S"});
    private final Map<String, FCheckBox> cardTypeChecks = new LinkedHashMap<>();
    private final Map<String, FCheckBox> supertypeChecks = new LinkedHashMap<>();
    private final Map<String, FCheckBox> keywordChecks = new LinkedHashMap<>();
    private final JPanel cardTypePanel = new JPanel(new MigLayout("insets 0, wrap 4"));
    private final JPanel supertypePanel = new JPanel(new MigLayout("insets 0, wrap 4"));
    private final JLabel sourceLabel = new JLabel();
    private final JLabel validationLabel = new JLabel();
    private final JLabel ptLabel = new JLabel();
    private final JPanel ptPanel = new JPanel(new MigLayout("insets 0, gap 3"));
    private final JPanel keywordPanel = new JPanel(new MigLayout("insets 0, wrap 2"));
    private final FButton newButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewCard", "New Card"));
    private final FButton copyButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorEditableCopy", "Create Editable Copy"));
    private final FButton newSetButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewSet", "New Set..."));
    private final FButton revertButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorRevert", "Revert"));
    private final FButton saveButton = new FButton(Localizer.getInstance().getMessage("lblSave"));
    private final FButton saveAsButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSaveAs", "Save As..."));
    private Set<String> preservedSupertypes = Set.of();
    private boolean refreshing;
    private Runnable changeListener = () -> { };

    VCardDesigner() {
        customSet.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        customSet.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2));
        rarity.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
        rarity.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2));
        cardTypePanel.setOpaque(false);
        supertypePanel.setOpaque(false);
        keywordPanel.setOpaque(false);
        styleLabel(sourceLabel);
        styleLabel(validationLabel);
        styleLabel(ptLabel);
        ptPanel.setOpaque(false);
        final DocumentListener documentListener = new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent e) { changed(); }
            @Override public void removeUpdate(final DocumentEvent e) { changed(); }
            @Override public void changedUpdate(final DocumentEvent e) { changed(); }
            private void changed() {
                if (refreshing) return;
                // Do not refresh the form from inside AbstractDocument's notification. Swing
                // forbids mutating another document until the current notification completes.
                SwingUtilities.invokeLater(() -> {
                    if (!refreshing) changeListener.run();
                });
            }
        };
        name.getDocument().addDocumentListener(documentListener);
        manaCost.getDocument().addDocumentListener(documentListener);
        subtypes.getDocument().addDocumentListener(documentListener);
        power.getDocument().addDocumentListener(documentListener);
        toughness.getDocument().addDocumentListener(documentListener);
        collectorNumber.getDocument().addDocumentListener(documentListener);
        customSet.addActionListener(e -> { if (!refreshing) changeListener.run(); });
        rarity.addActionListener(e -> { if (!refreshing) changeListener.run(); });
        for (final CardType.CoreType type : CardType.CoreType.values()) {
            addCheck(cardTypeChecks, cardTypePanel, type.name());
        }
        addCheck(supertypeChecks, supertypePanel, CardType.Supertype.Legendary.name());
        for (final CardCreatorKeyword keyword : CardCreatorKeywords.SUPPORTED) {
            final FCheckBox check = new FCheckBox(keyword.displayName());
            check.addActionListener(e -> { if (!refreshing) changeListener.run(); });
            keywordChecks.put(keyword.scriptName(), check);
            keywordPanel.add(check);
        }
        cardTypePanel.setBorder(themedBorder(Localizer.getInstance().getMessageorUseDefault(
                "lblCardCreatorCardTypes", "Card types")));
        supertypePanel.setBorder(themedBorder(Localizer.getInstance().getMessageorUseDefault(
                "lblCardCreatorSupertypes", "Supertypes")));
        keywordPanel.setBorder(themedBorder(Localizer.getInstance().getMessageorUseDefault(
                "lblCardCreatorKeywords", "Keyword abilities")));
        ptLabel.setText(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorPowerToughness", "Power / toughness:"));
        ptPanel.add(power, "w 50!, growx"); ptPanel.add(label("/")); ptPanel.add(toughness, "w 50!, growx");
        setPowerControlsVisible(false, false);
        newButton.setCommand((UiCommand) CCardCreator.SINGLETON_INSTANCE::newCard);
        copyButton.setCommand((UiCommand) CCardCreator.SINGLETON_INSTANCE::createEditableCopy);
        newSetButton.setCommand((UiCommand) CCardCreator.SINGLETON_INSTANCE::createSet);
        revertButton.setCommand((UiCommand) () -> CCardCreator.SINGLETON_INSTANCE.getSession().revert());
        saveButton.setCommand((UiCommand) () -> CCardCreator.SINGLETON_INSTANCE.save());
        saveAsButton.setCommand((UiCommand) () -> CCardCreator.SINGLETON_INSTANCE.saveAs());
    }

    public void setChangeListener(final Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    @Override
    public EDocID getDocumentID() { return EDocID.WORKSHOP_CARDDESIGNER; }
    @Override
    public DragTab getTabLabel() { return tab; }
    @Override
    public forge.screens.workshop.controllers.CCardDesigner getLayoutControl() {
        return forge.screens.workshop.controllers.CCardDesigner.SINGLETON_INSTANCE;
    }
    @Override
    public void setParentCell(final DragCell cell) { parentCell = cell; }
    @Override
    public DragCell getParentCell() { return parentCell; }

    @Override
    public void populate() {
        final JPanel body = parentCell.getBody();
        body.setOpaque(false);
        body.setLayout(new MigLayout("insets 6, gap 4, wrap 2, hidemode 3"));
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorName", "Name:")), "w 90!"); body.add(name, "w 100%, growx");
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorManaCost", "Mana cost:"))); body.add(manaCost, "w 100%, growx");
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorCardTypes", "Card types:")), "top");
        body.add(cardTypePanel, "w 100%, growx");
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSupertypes", "Supertypes:")), "top");
        body.add(supertypePanel, "w 100%, growx");
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSubtypes", "Subtypes:")));
        body.add(subtypes, "w 100%, growx");
        body.add(ptLabel, "top");
        body.add(ptPanel, "w 100%, growx");
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorCustomSet", "Custom set:"))); body.add(customSet, "w 100%, growx");
        body.add(label(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorCollectorRarity", "Collector / rarity:")));
        final JPanel printing = new JPanel(new MigLayout("insets 0, gap 3"));
        printing.setOpaque(false);
        printing.add(collectorNumber, "w 70!, growx"); printing.add(rarity, "w 70!, growx");
        body.add(printing, "w 100%, growx");
        body.add(keywordPanel, "span 2, w 100%, growx");
        body.add(sourceLabel, "span 2, w 100%, growx");
        body.add(validationLabel, "span 2, w 100%, growx");
        final JPanel buttons = new JPanel(new MigLayout("insets 0, gap 3, wrap 3"));
        buttons.setOpaque(false);
        buttons.add(newButton); buttons.add(copyButton); buttons.add(newSetButton);
        buttons.add(revertButton); buttons.add(saveButton); buttons.add(saveAsButton);
        body.add(buttons, "span 2, w 100%, growx");
        body.setPreferredSize(new Dimension(360, 420));
    }

    public void loadSession(final CardEditorSession session) {
        refreshing = true;
        try {
            final CardEditorDraft draft = session.getDraft();
            final boolean hasDraft = draft != null;
            name.setText(hasDraft ? draft.getName() : "");
            manaCost.setText(hasDraft ? draft.getManaCost() : "");
            for (final Map.Entry<String, FCheckBox> entry : cardTypeChecks.entrySet()) {
                entry.getValue().setSelected(hasDraft && draft.getCardTypes().contains(entry.getKey()));
            }
            preservedSupertypes = hasDraft
                    ? new LinkedHashSet<>(draft.getSupertypes()) : new LinkedHashSet<>();
            preservedSupertypes.remove(CardType.Supertype.Legendary.name());
            for (final Map.Entry<String, FCheckBox> entry : supertypeChecks.entrySet()) {
                entry.getValue().setSelected(hasDraft && draft.getSupertypes().contains(entry.getKey()));
            }
            subtypes.setText(hasDraft ? draft.getSubtypeInput() : "");
            power.setText(hasDraft ? draft.getPower() : "");
            toughness.setText(hasDraft ? draft.getToughness() : "");
            collectorNumber.setText(hasDraft ? draft.getCollectorNumber() : "1");
            rarity.setSelectedItem(hasDraft ? draft.getRarity() : "C");
            selectSet(hasDraft ? draft.getCustomSetCode() : null);
            for (final Map.Entry<String, FCheckBox> entry : keywordChecks.entrySet()) {
                entry.getValue().setSelected(hasDraft && draft.getKeywords().stream().anyMatch(k -> k.equalsIgnoreCase(entry.getKey())));
            }
            sourceLabel.setText(hasDraft ? (session.isEditable()
                    ? Localizer.getInstance().getMessageorUseDefault("lblCardCreatorEditableDraft", "Editable custom draft")
                    : Localizer.getInstance().getMessageorUseDefault("lblCardCreatorBundledReadOnly", "Bundled card (read-only)"))
                    : Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewDraft", "Create a new custom card"));
            final String validation = hasDraft && !session.getValidation().errors().isEmpty()
                    ? String.join(" ", session.getValidation().errors()) : hasDraft
                    ? Localizer.getInstance().getMessageorUseDefault("lblCardCreatorReady", "Ready to save.") : "";
            validationLabel.setText(validation);
            final boolean editable = session.isEditable();
            final boolean structuredEditing = editable && !session.isRawScriptInvalid();
            name.setEnabled(structuredEditing); manaCost.setEnabled(structuredEditing); subtypes.setEnabled(structuredEditing);
            power.setEnabled(structuredEditing); toughness.setEnabled(structuredEditing); customSet.setEnabled(structuredEditing);
            collectorNumber.setEnabled(structuredEditing); rarity.setEnabled(structuredEditing); keywordPanel.setEnabled(structuredEditing);
            cardTypePanel.setEnabled(structuredEditing); supertypePanel.setEnabled(structuredEditing);
            cardTypeChecks.values().forEach(check -> check.setEnabled(structuredEditing));
            supertypeChecks.values().forEach(check -> check.setEnabled(structuredEditing));
            keywordChecks.values().forEach(check -> check.setEnabled(structuredEditing));
            setPowerControlsVisible(hasDraft && draft.isCreature(), structuredEditing);
            copyButton.setEnabled(session.getSelectedCard() != null && !session.isDirty());
            revertButton.setEnabled(session.isDirty());
            saveButton.setEnabled(editable && session.isDirty() && session.getValidation().isValid());
            saveAsButton.setEnabled(editable && session.getDraft() != null && session.getValidation().isValid());
        } finally {
            refreshing = false;
        }
    }

    private void selectSet(final String code) {
        if (code == null) { customSet.setSelectedItem(null); return; }
        for (int i = 0; i < customSet.getItemCount(); i++) {
            if (customSet.getItemAt(i).code().equalsIgnoreCase(code)) {
                customSet.setSelectedIndex(i); return;
            }
        }
        customSet.setSelectedItem(null);
    }

    public void reloadSets(final List<CustomSetInfo> sets) {
        final CustomSetInfo selectedSet = customSet.getSelectedItem();
        final String selected = selectedSet == null ? null : selectedSet.code();
        refreshing = true;
        try {
            customSet.removeAllItems();
            for (final CustomSetInfo set : sets) customSet.addItem(set);
            selectSet(selected);
        } finally {
            refreshing = false;
        }
    }

    public CardEditorDraft readDraft() {
        final CardEditorDraft draft = CardEditorDraft.newCard();
        draft.setName(name.getText()); draft.setManaCost(manaCost.getText());
        draft.setCardTypes(selectedChecks(cardTypeChecks));
        final List<String> selectedSupertypes = new ArrayList<>(preservedSupertypes);
        selectedSupertypes.remove(CardType.Supertype.Legendary.name());
        selectedSupertypes.addAll(selectedChecks(supertypeChecks));
        draft.setSupertypes(selectedSupertypes);
        draft.setSubtypes(subtypes.getText());
        draft.setPower(power.getText()); draft.setToughness(toughness.getText());
        draft.setCollectorNumber(collectorNumber.getText()); draft.setRarity(String.valueOf(rarity.getSelectedItem()));
        final CustomSetInfo set = customSet.getSelectedItem();
        if (set != null) draft.setCustomSetCode(set.code());
        final List<String> keywords = new ArrayList<>();
        for (final Map.Entry<String, FCheckBox> entry : keywordChecks.entrySet()) if (entry.getValue().isSelected()) keywords.add(entry.getKey());
        draft.setKeywords(keywords);
        return draft;
    }

    public void applySessionValues(final CardEditorSession session) {
        if (!refreshing) session.updateFromDesigner(readDraft());
    }

    private static JLabel label(final String text) {
        final JLabel label = new JLabel(text);
        styleLabel(label);
        return label;
    }

    private static void addCheck(final Map<String, FCheckBox> checks, final JPanel panel, final String value) {
        final FCheckBox check = new FCheckBox(value);
        checks.put(value, check);
        panel.add(check);
    }

    private static List<String> selectedChecks(final Map<String, FCheckBox> checks) {
        final List<String> selected = new ArrayList<>();
        for (final Map.Entry<String, FCheckBox> entry : checks.entrySet()) {
            if (entry.getValue().isSelected()) selected.add(entry.getKey());
        }
        return selected;
    }

    private static TitledBorder themedBorder(final String title) {
        final TitledBorder border = BorderFactory.createTitledBorder(title);
        border.setTitleColor(FSkin.getColor(FSkin.Colors.CLR_TEXT).getColor());
        return border;
    }

    private void setPowerControlsVisible(final boolean visible, final boolean enabled) {
        ptLabel.setVisible(visible);
        ptPanel.setVisible(visible);
        power.setEnabled(visible && enabled);
        toughness.setEnabled(visible && enabled);
    }

    private static void styleLabel(final JLabel label) {
        label.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT).getColor());
        label.setFont(FSkin.getFont().getBaseFont());
    }
}
