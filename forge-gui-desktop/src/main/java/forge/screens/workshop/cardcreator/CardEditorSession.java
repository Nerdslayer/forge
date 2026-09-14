package forge.screens.workshop.cardcreator;

import forge.ai.CardDefinitionValueEvaluator;
import forge.card.CardDb;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.gui.card.CardScriptInfo;
import forge.gui.cardcreator.CardCreatorValidation;
import forge.gui.cardcreator.CardEditorDraft;
import forge.gui.cardcreator.CardScriptDocument;
import forge.gui.cardcreator.CustomCardRepository;
import forge.gui.cardcreator.CustomEditionRepository;
import forge.gui.cardcreator.CustomSetInfo;
import forge.item.PaperCard;
import forge.model.FModel;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Coordinates one structured/raw Workshop editing session. */
public final class CardEditorSession {
    private final CustomCardRepository cardRepository;
    private final CustomEditionRepository editionRepository;
    private final CardDefinitionValueEvaluator evaluator = new CardDefinitionValueEvaluator();
    private CardEditorDraft draft;
    private CardScriptDocument document;
    private CardRules previewRules;
    private PaperCard selectedCard;
    private CardCreatorValidation validation = new CardCreatorValidation(List.of(), List.of());
    private CardDefinitionValueEvaluator.Evaluation evaluation;
    private boolean editable;
    private boolean dirty;
    private boolean rawScriptInvalid;
    private Consumer<CardEditorSession> changeListener = ignored -> { };

    public CardEditorSession() {
        this(new CustomCardRepository(), new CustomEditionRepository());
    }

    public CardEditorSession(final CustomCardRepository cardRepository,
            final CustomEditionRepository editionRepository) {
        this.cardRepository = Objects.requireNonNull(cardRepository);
        this.editionRepository = Objects.requireNonNull(editionRepository);
    }

    public void setChangeListener(final Consumer<CardEditorSession> listener) {
        changeListener = listener == null ? ignored -> { } : listener;
    }

    public void load(final PaperCard card) {
        selectedCard = card;
        if (card == null) {
            clear();
            return;
        }
        draft = CardEditorDraft.from(card);
        final CardScriptInfo script = CardScriptInfo.getScriptFor(card.getRules().getNormalizedName());
        document = CardScriptDocument.fromText(script == null ? CardScriptDocument.fromDraft(draft).getText() : script.getText());
        editable = script != null && script.getFile() != null && cardRepository.isOwnedPath(script.getFile().toPath());
        dirty = false;
        rawScriptInvalid = false;
        refreshPreview(false);
    }

    public void createNew() {
        selectedCard = null;
        draft = CardEditorDraft.newCard();
        document = CardScriptDocument.fromDraft(draft);
        editable = true;
        dirty = true;
        rawScriptInvalid = false;
        refreshPreview(false);
    }

    public void createEditableCopy() {
        if (selectedCard == null) return;
        draft = CardEditorDraft.from(selectedCard);
        draft.setName(selectedCard.getName() + " Custom");
        draft.setCustomSetCode(null);
        draft.markAsNew();
        document = CardScriptDocument.fromText(draft.getSourceScript());
        editable = true;
        dirty = true;
        rawScriptInvalid = false;
        refreshPreview(false);
    }

    /** Starts a new definition from the current script without removing the original card. */
    public void prepareSaveAs(final String name) {
        if (draft == null || !editable || name == null || name.isBlank()) return;
        draft.setName(name.trim());
        draft.setSourceIdentity(null, null);
        draft.markAsNew();
        dirty = true;
        refreshPreview(false);
    }

    public void setName(final String value) { draft.setName(value); refreshPreview(true); }
    public void setManaCost(final String value) { draft.setManaCost(value); refreshPreview(true); }
    public void setTypes(final String value) { draft.setTypes(value); refreshPreview(true); }
    public void setPower(final String value) { draft.setPower(value); refreshPreview(true); }
    public void setToughness(final String value) { draft.setToughness(value); refreshPreview(true); }
    public void setKeywords(final Iterable<String> value) { draft.setKeywords(value); refreshPreview(true); }
    public void setCustomSetCode(final String value) { draft.setCustomSetCode(value); refreshPreview(true); }
    public void setCollectorNumber(final String value) { draft.setCollectorNumber(value); refreshPreview(true); }
    public void setRarity(final String value) { draft.setRarity(value); refreshPreview(true); }

    /** Selects a set for the current draft and assigns the next unused numeric collector number. */
    public void selectCustomSet(final String code) {
        if (draft == null || !editable || Objects.equals(draft.getCustomSetCode(), code)) return;
        draft.setCustomSetCode(code);
        if (code != null && !code.isBlank()) {
            try {
                final CustomSetInfo set = getCustomSets().stream()
                        .filter(candidate -> candidate.code().equalsIgnoreCase(code)).findFirst().orElse(null);
                if (set != null) draft.setCollectorNumber(editionRepository.nextCollectorNumber(set));
            } catch (final IOException ignored) {
                // Validation still reports an unavailable set; keep the user's existing number.
            }
        }
        refreshPreview(true);
    }

    public void updateFromDesigner(final CardEditorDraft values) {
        if (draft == null || values == null || !editable) return;
        draft.setName(values.getName());
        draft.setManaCost(values.getManaCost());
        draft.setCardTypes(values.getCardTypes());
        draft.setSupertypes(values.getSupertypes());
        draft.setSubtypes(values.getSubtypeInput());
        draft.setPower(values.getPower());
        draft.setToughness(values.getToughness());
        draft.setKeywords(values.getKeywords());
        draft.setCustomSetCode(values.getCustomSetCode());
        draft.setCollectorNumber(values.getCollectorNumber());
        draft.setRarity(values.getRarity());
        refreshPreview(true);
    }

    /** Replaces the source view and imports its basic fields if Forge can parse it. */
    public void setRawScript(final String text) {
        final String oldSet = draft == null ? null : draft.getCustomSetCode();
        final String oldSourceName = draft == null ? null : draft.getSourceName();
        final String oldSourceSet = draft == null ? null : draft.getSourceSetCode();
        final boolean oldNewCard = draft != null && draft.isNewCard();
        final String oldCollector = draft == null ? "1" : draft.getCollectorNumber();
        final String oldRarity = draft == null ? "C" : draft.getRarity();
        document = CardScriptDocument.fromText(text);
        try {
            final CardRules rules = CardRules.fromScript(List.of((text == null ? "" : text).split("\\R", -1)));
            draft = CardEditorDraft.fromRules(rules);
            draft.setCustomSetCode(oldSet);
            draft.setSourceIdentity(oldSourceName, oldSourceSet);
            draft.setCollectorNumber(oldCollector);
            draft.setRarity(oldRarity);
            if (oldNewCard) draft.markAsNew();
            editable = true;
            rawScriptInvalid = false;
            previewRules = rules;
            validation = CardCreatorValidation.validate(draft, text);
            evaluation = evaluator.evaluate(rules, oldSet);
        } catch (final RuntimeException ex) {
            previewRules = null;
            evaluation = null;
            rawScriptInvalid = true;
            validation = new CardCreatorValidation(List.of("Forge could not parse the card script: " + ex.getMessage()), List.of());
        }
        dirty = true;
        notifyChanged();
    }

    public String getRawScript() { return document == null ? "" : document.getText(); }
    public CardEditorDraft getDraft() { return draft; }
    public CardRules getPreviewRules() { return previewRules; }
    public CardDefinitionValueEvaluator.Evaluation getEvaluation() { return evaluation; }
    public CardCreatorValidation getValidation() { return validation; }
    public PaperCard getSelectedCard() { return selectedCard; }
    public boolean isEditable() { return editable; }
    public boolean isDirty() { return dirty; }
    public boolean isRawScriptInvalid() { return rawScriptInvalid; }

    public List<CustomSetInfo> getCustomSets() throws IOException { return editionRepository.list(); }

    public CustomSetInfo createCustomSet(final String code, final String name) throws IOException {
        final CustomSetInfo result = editionRepository.create(code, name);
        if (draft != null && editable) {
            draft.setCustomSetCode(result.code());
            draft.setCollectorNumber(editionRepository.nextCollectorNumber(result));
            refreshPreview(true);
        }
        return result;
    }

    public PaperCard save() throws IOException {
        if (!editable) throw new IOException("This bundled card is read-only. Create an editable copy first.");
        if (!validation.isValid() || previewRules == null) throw new IOException(String.join("\n", validation.errors()));
        final CustomSetInfo set = getCustomSets().stream()
                .filter(candidate -> candidate.code().equalsIgnoreCase(draft.getCustomSetCode())).findFirst().orElse(null);
        if (set == null) throw new IOException("Select an existing custom set or create one first.");
        final String oldName = draft.getSourceName();
        final String oldSetCode = draft.getSourceSetCode();
        final Path targetPath = cardRepository.pathForName(draft.getName());
        final Path oldPath = oldName == null ? null : cardRepository.pathForName(oldName);
        if (cardRepository.exists(draft.getName()) && (oldPath == null || !targetPath.equals(oldPath))) {
            throw new IOException("A custom card with that name already exists. Use a different name or edit the existing card.");
        }
        editionRepository.saveCardEntry(set, draft.getName(), oldName, draft.getCollectorNumber(), draft.getRarity());
        cardRepository.save(draft.getName(), getRawScript());

        if (oldSetCode != null && !oldSetCode.equalsIgnoreCase(set.code())) {
            final CustomSetInfo oldSet = getCustomSets().stream()
                    .filter(candidate -> candidate.code().equalsIgnoreCase(oldSetCode)).findFirst().orElse(null);
            if (oldSet != null && oldName != null) editionRepository.removeCardEntry(oldSet, oldName);
        }
        if (oldName != null && !oldName.equalsIgnoreCase(draft.getName())
                && cardRepository.isOwnedPath(cardRepository.pathForName(oldName))) {
            cardRepository.delete(oldName);
        }

        final CardRules savedRules = CardRules.fromScript(List.of(getRawScript().split("\\R", -1)));
        savedRules.setCustom();
        final CardDb db = savedRules.isVariant() ? FModel.getMagicDb().getVariantCards() : FModel.getMagicDb().getCommonCards();
        final CardRarity rarity = CardRarity.smartValueOf(draft.getRarity());
        db.getEditor().putCard(savedRules, List.of(Pair.of(set.code(), rarity)));
        final PaperCard paperCard = new PaperCard(savedRules, set.code(), rarity, 0, false,
                draft.getCollectorNumber(), PaperCard.NO_ARTIST_NAME, PaperCard.NO_FUNCTIONAL_VARIANT);
        db.addCard(paperCard);
        selectedCard = paperCard;
        draft.markAsSaved(draft.getName(), set.code(), getRawScript());
        document = CardScriptDocument.fromText(getRawScript());
        dirty = false;
        editable = true;
        refreshPreview(false);
        return paperCard;
    }

    public void revert() {
        if (selectedCard != null) load(selectedCard);
        else createNew();
    }

    public void clear() {
        draft = null;
        document = null;
        previewRules = null;
        evaluation = null;
        validation = new CardCreatorValidation(List.of(), List.of());
        editable = false;
        dirty = false;
        rawScriptInvalid = false;
        notifyChanged();
    }

    private void refreshPreview(final boolean markDirty) {
        if (draft == null) return;
        if (document == null) document = CardScriptDocument.fromDraft(draft);
        document.apply(draft);
        final String script = document.getText();
        try {
            previewRules = CardRules.fromScript(List.of(script.split("\\R", -1)));
            validation = CardCreatorValidation.validate(draft, script);
            evaluation = evaluator.evaluate(previewRules, draft.getCustomSetCode());
            rawScriptInvalid = false;
        } catch (final RuntimeException ex) {
            previewRules = null;
            evaluation = null;
            validation = new CardCreatorValidation(List.of("Forge could not parse the card script: " + ex.getMessage()), List.of());
        }
        if (markDirty) dirty = true;
        notifyChanged();
    }

    private void notifyChanged() { changeListener.accept(this); }
}
