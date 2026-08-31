package forge.card;

import forge.StaticData;
import forge.ai.AITest;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class CardDbLanguagePreferenceTest extends AITest {
    private StaticData staticData;
    private CardDb cardDb;

    @BeforeMethod
    public void setUp() {
        staticData = FModel.getMagicDb();
        cardDb = staticData.getCommonCards();
        cardDb.setPreferredCardLanguage("en-US");
    }

    @AfterClass
    public void restoreLanguagePreference() {
        if (cardDb != null) {
            cardDb.setPreferredCardLanguage("en-US");
        }
    }

    @Test
    public void automaticPrintingPrefersSelectedLanguage() {
        String cardName = "Earthquake";

        staticData.attemptToLoadCard(cardName);
        cardDb.setPreferredCardLanguage("ja-JP");

        PaperCard automaticCard = cardDb.getCard(cardName);
        assertNotNull(automaticCard);
        assertEquals(staticData.getCardEdition(automaticCard.getEdition()).getCardsLangCode(), "ja");

        PaperCard uniqueCard = cardDb.getUniqueByNameNoAlt(cardName);
        assertNotNull(uniqueCard);
        assertEquals(staticData.getCardEdition(uniqueCard.getEdition()).getCardsLangCode(), "ja");
    }

    @Test
    public void automaticPrintingFallsBackToEnglish() {
        String cardName = "Earthquake";

        staticData.attemptToLoadCard(cardName);
        cardDb.setPreferredCardLanguage("fr-FR");

        PaperCard automaticCard = cardDb.getCard(cardName);
        assertNotNull(automaticCard);
        assertEquals(staticData.getCardEdition(automaticCard.getEdition()).getCardsLangCode(), "en");

        PaperCard uniqueCard = cardDb.getUniqueByNameNoAlt(cardName);
        assertNotNull(uniqueCard);
        assertEquals(staticData.getCardEdition(uniqueCard.getEdition()).getCardsLangCode(), "en");
    }

    @Test
    public void automaticPrintingFallsBackToAnyAvailableLanguage() {
        String cardName = "Earthquake";
        String japaneseEdition = "PMDA";

        staticData.attemptToLoadCard(cardName);
        cardDb.setPreferredCardLanguage("fr-FR");

        PaperCard fallbackCard = cardDb.getCardFromEditions(cardName, cardDb.getCardArtPreference(), 1,
                card -> japaneseEdition.equals(card.getEdition()));
        assertNotNull(fallbackCard);
        assertEquals(fallbackCard.getEdition(), japaneseEdition);
        assertEquals(staticData.getCardEdition(fallbackCard.getEdition()).getCardsLangCode(), "ja");
    }

    @Test
    public void explicitEditionIsPreserved() {
        String cardName = "Earthquake";
        String japaneseEdition = "PMDA";

        staticData.attemptToLoadCard(cardName);
        cardDb.setPreferredCardLanguage("fr-FR");

        PaperCard explicitJapaneseCard = cardDb.getCard(cardName, japaneseEdition);
        assertNotNull(explicitJapaneseCard);
        assertEquals(explicitJapaneseCard.getEdition(), japaneseEdition);
        assertEquals(staticData.getCardEdition(explicitJapaneseCard.getEdition()).getCardsLangCode(), "ja");
    }
}
