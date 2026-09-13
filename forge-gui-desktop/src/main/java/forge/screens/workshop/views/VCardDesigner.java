package forge.screens.workshop.views;

import forge.gui.cardcreator.CardCreatorKeyword;
import forge.gui.cardcreator.CardCreatorKeywords;
import forge.gui.cardcreator.CardEditorDraft;
import forge.gui.cardcreator.CustomSetInfo;
import forge.gui.UiCommand;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.screens.workshop.cardcreator.CardEditorSession;
import forge.screens.workshop.controllers.CCardCreator;
import forge.toolbox.FButton;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Structured basic-card editor used by the Workshop Card Creator. */
public enum VCardDesigner implements IVDoc<forge.screens.workshop.controllers.CCardDesigner> {
    SINGLETON_INSTANCE;

    private DragCell parentCell;
    private final DragTab tab = new DragTab(Localizer.getInstance().getMessage("lblCardDesigner"));
    private final JTextField name = new JTextField();
    private final JTextField manaCost = new JTextField();
    private final JTextField types = new JTextField();
    private final JTextField power = new JTextField();
    private final JTextField toughness = new JTextField();
    private final JTextField collectorNumber = new JTextField("1");
    private final JComboBox<CustomSetInfo> customSet = new JComboBox<>();
    private final JComboBox<String> rarity = new JComboBox<>(new String[] {"C", "U", "R", "M", "S"});
    private final Map<String, JCheckBox> keywordChecks = new HashMap<>();
    private final JLabel sourceLabel = new JLabel();
    private final JLabel validationLabel = new JLabel();
    private final JPanel keywordPanel = new JPanel(new MigLayout("insets 0, wrap 2"));
    private final FButton newButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewCard", "New Card"));
    private final FButton copyButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorEditableCopy", "Create Editable Copy"));
    private final FButton newSetButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewSet", "New Set..."));
    private final FButton revertButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorRevert", "Revert"));
    private final FButton saveButton = new FButton(Localizer.getInstance().getMessage("lblSave"));
    private final FButton saveAsButton = new FButton(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSaveAs", "Save As..."));
    private boolean refreshing;
    private Runnable changeListener = () -> { };

    VCardDesigner() {
        final DocumentListener documentListener = new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent e) { changed(); }
            @Override public void removeUpdate(final DocumentEvent e) { changed(); }
            @Override public void changedUpdate(final DocumentEvent e) { changed(); }
            private void changed() { if (!refreshing) changeListener.run(); }
        };
        name.getDocument().addDocumentListener(documentListener);
        manaCost.getDocument().addDocumentListener(documentListener);
        types.getDocument().addDocumentListener(documentListener);
        power.getDocument().addDocumentListener(documentListener);
        toughness.getDocument().addDocumentListener(documentListener);
        collectorNumber.getDocument().addDocumentListener(documentListener);
        customSet.addActionListener(e -> { if (!refreshing) changeListener.run(); });
        rarity.addActionListener(e -> { if (!refreshing) changeListener.run(); });
        for (final CardCreatorKeyword keyword : CardCreatorKeywords.SUPPORTED) {
            final JCheckBox check = new JCheckBox(keyword.displayName());
            check.addActionListener(e -> { if (!refreshing) changeListener.run(); });
            keywordChecks.put(keyword.scriptName(), check);
            keywordPanel.add(check);
        }
        keywordPanel.setBorder(BorderFactory.createTitledBorder(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorKeywords", "Keyword abilities")));
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
        body.setLayout(new MigLayout("insets 6, gap 4, wrap 2, hidemode 3"));
        body.add(new JLabel(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorName", "Name:")), "w 90!"); body.add(name, "w 100%, growx");
        body.add(new JLabel(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorManaCost", "Mana cost:"))); body.add(manaCost, "w 100%, growx");
        body.add(new JLabel(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorTypeLine", "Type line:"))); body.add(types, "w 100%, growx");
        body.add(new JLabel(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorPowerToughness", "Power / toughness:")));
        final JPanel pt = new JPanel(new MigLayout("insets 0, gap 3"));
        pt.add(power, "w 50!, growx"); pt.add(new JLabel("/")); pt.add(toughness, "w 50!, growx");
        body.add(pt, "w 100%, growx");
        body.add(new JLabel(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorCustomSet", "Custom set:"))); body.add(customSet, "w 100%, growx");
        body.add(new JLabel(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorCollectorRarity", "Collector / rarity:")));
        final JPanel printing = new JPanel(new MigLayout("insets 0, gap 3"));
        printing.add(collectorNumber, "w 70!, growx"); printing.add(rarity, "w 70!, growx");
        body.add(printing, "w 100%, growx");
        body.add(keywordPanel, "span 2, w 100%, growx");
        body.add(sourceLabel, "span 2, w 100%, growx");
        body.add(validationLabel, "span 2, w 100%, growx");
        final JPanel buttons = new JPanel(new MigLayout("insets 0, gap 3, wrap 3"));
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
            types.setText(hasDraft ? draft.getTypes() : "");
            power.setText(hasDraft ? draft.getPower() : "");
            toughness.setText(hasDraft ? draft.getToughness() : "");
            collectorNumber.setText(hasDraft ? draft.getCollectorNumber() : "1");
            rarity.setSelectedItem(hasDraft ? draft.getRarity() : "C");
            selectSet(hasDraft ? draft.getCustomSetCode() : null);
            for (final Map.Entry<String, JCheckBox> entry : keywordChecks.entrySet()) {
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
            name.setEnabled(structuredEditing); manaCost.setEnabled(structuredEditing); types.setEnabled(structuredEditing);
            power.setEnabled(structuredEditing); toughness.setEnabled(structuredEditing); customSet.setEnabled(structuredEditing);
            collectorNumber.setEnabled(structuredEditing); rarity.setEnabled(structuredEditing); keywordPanel.setEnabled(structuredEditing);
            keywordChecks.values().forEach(check -> check.setEnabled(structuredEditing));
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
        final String selected = customSet.getSelectedItem() instanceof CustomSetInfo info ? info.code() : null;
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
        draft.setName(name.getText()); draft.setManaCost(manaCost.getText()); draft.setTypes(types.getText());
        draft.setPower(power.getText()); draft.setToughness(toughness.getText());
        draft.setCollectorNumber(collectorNumber.getText()); draft.setRarity(String.valueOf(rarity.getSelectedItem()));
        if (customSet.getSelectedItem() instanceof CustomSetInfo set) draft.setCustomSetCode(set.code());
        final List<String> keywords = new ArrayList<>();
        for (final Map.Entry<String, JCheckBox> entry : keywordChecks.entrySet()) if (entry.getValue().isSelected()) keywords.add(entry.getKey());
        draft.setKeywords(keywords);
        return draft;
    }

    public void applySessionValues(final CardEditorSession session) {
        if (!refreshing) session.updateFromDesigner(readDraft());
    }
}
