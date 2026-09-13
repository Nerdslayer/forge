package forge.gui.cardcreator;

import forge.ai.CardDefinitionValueEvaluator;
import forge.card.CardRules;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Broad domain tests for the structured Card Creator and its game-free score. */
public class CardCreatorDomainTest {
    @Test
    public void structuredChangesPreserveUnmanagedScriptRecords() {
        final CardScriptDocument document = CardScriptDocument.fromText(
                "Name:Old Card\nManaCost:2\nTypes:Creature Human\nPT:2/2\nK:Flying\n"
                        + "K:Some future keyword\nSVar:Custom=2\nOracle:Flying");
        final CardEditorDraft draft = CardEditorDraft.newCard();
        draft.setName("New Card");
        draft.setManaCost("3 G");
        draft.setTypes("Legendary Creature Elf");
        draft.setPower("4");
        draft.setToughness("5");
        draft.setKeywords(List.of("Lifelink"));
        document.apply(draft);

        final String result = document.getText();
        assertTrue(result.contains("Name:New Card"));
        assertTrue(result.contains("ManaCost:3 G"));
        assertTrue(result.contains("PT:4/5"));
        assertTrue(result.contains("K:Lifelink"));
        assertTrue(result.contains("K:Some future keyword"));
        assertTrue(result.contains("SVar:Custom=2"));
        assertFalse(result.contains("K:Flying\n"));
    }

    @Test
    public void newDraftUpdatesOnlyItsGeneratedOraclePreview() {
        final CardEditorDraft draft = CardEditorDraft.newCard();
        final CardScriptDocument document = CardScriptDocument.fromDraft(draft);
        draft.setKeywords(List.of("Flying"));
        document.apply(draft);
        assertTrue(document.getText().contains("Oracle:Flying"));

        draft.setKeywords(List.of("Lifelink"));
        document.apply(draft);
        assertTrue(document.getText().contains("Oracle:Lifelink"));
        assertFalse(document.getText().contains("Oracle:Flying"));
    }

    @Test
    public void definitionValueSeparatesBattlefieldValueFromCosts() {
        final CardRules rules = CardRules.fromScript(List.of(
                "Name:Test Creature", "ManaCost:2 G", "Types:Creature Elf", "PT:3/3", "K:Flying"));
        final CardDefinitionValueEvaluator.Evaluation evaluation = new CardDefinitionValueEvaluator().evaluate(rules);

        assertEquals(evaluation.battlefieldValue(), 80 + 45 + 30 + 30);
        assertEquals(evaluation.grossPointValue(), evaluation.battlefieldValue());
        assertEquals(evaluation.manaInvestment(), 3 * 35);
        assertEquals(evaluation.netRate(), evaluation.battlefieldValue()
                - evaluation.cardOpportunityCost() - evaluation.manaInvestment());
    }

    @Test
    public void customRepositoryWritesOnlyUnderItsConfiguredRoot() throws Exception {
        final Path root = Files.createTempDirectory("card-creator-cards");
        final CustomCardRepository repository = new CustomCardRepository(root);
        final Path saved = repository.save("Safe Card", "Name:Safe Card\n");

        assertTrue(repository.isOwnedPath(saved));
        assertFalse(repository.isOwnedPath(root.getParent().resolve("outside.txt")));
        assertEquals(Files.readString(saved), "Name:Safe Card\n");
    }

    @Test
    public void customEditionEntriesCanBeCreatedUpdatedAndRemoved() throws Exception {
        final Path root = Files.createTempDirectory("card-creator-editions");
        final CustomEditionRepository repository = new CustomEditionRepository(root);
        final CustomSetInfo set = repository.create("TST", "Creator Test Set");
        repository.saveCardEntry(set, "Test Card", null, "1", "C");
        assertTrue(Files.readString(set.file()).contains("1 C Test Card"));
        try {
            repository.saveCardEntry(set, "Another Card", null, "1", "C");
            throw new AssertionError("duplicate collector number should be rejected");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("Collector number"));
        }

        repository.saveCardEntry(set, "Renamed Card", "Test Card", "1", "U");
        final String renamed = Files.readString(set.file());
        assertTrue(renamed.contains("1 U Renamed Card"));
        assertFalse(renamed.contains("Test Card"));

        repository.removeCardEntry(set, "Renamed Card");
        assertFalse(Files.readString(set.file()).contains("Renamed Card"));
    }
}
