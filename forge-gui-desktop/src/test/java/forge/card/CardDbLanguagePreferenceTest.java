package forge.card;

import forge.StaticData;
import forge.ai.AITest;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Set;

import static forge.card.CardDb.CardArtPreference.ORIGINAL_ART_ALL_EDITIONS;
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
    public void automaticPrintingFallsBackToUnspecifiedLanguage() {
        String cardName = "Earthquake";

        staticData.attemptToLoadCard(cardName);
        cardDb.setPreferredCardLanguage("fr-FR");

        PaperCard automaticCard = cardDb.getCard(cardName);
        assertNotNull(automaticCard);
        assertEquals(staticData.getCardEdition(automaticCard.getEdition()).getCardsLangCode(), "");

        PaperCard uniqueCard = cardDb.getUniqueByNameNoAlt(cardName);
        assertNotNull(uniqueCard);
        assertEquals(staticData.getCardEdition(uniqueCard.getEdition()).getCardsLangCode(), "");
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

    @Test
    public void mixedLanguageEditionIsOnlyUsedAsFallback() {
        String cardName = "Gush";

        staticData.attemptToLoadCard(cardName);

        PaperCard preferredCard = cardDb.getCardFromEditionsPreferNonPromo(cardName,
                ORIGINAL_ART_ALL_EDITIONS, 1, null);
        assertNotNull(preferredCard);
        assertEquals(preferredCard.getEdition(), "MMQ");

        PaperCard fallbackCard = cardDb.getCardFromEditionsPreferNonPromo(cardName,
                ORIGINAL_ART_ALL_EDITIONS, 1, card -> "PMEI".equals(card.getEdition()));
        assertNotNull(fallbackCard);
        assertEquals(fallbackCard.getEdition(), "PMEI");
        assertEquals(staticData.getCardEdition(fallbackCard.getEdition()).getCardsLangCode(), "mixed");
    }

    @Test
    public void nonPromoEditionIsPreferredBeforeOldestPromo() {
        String cardName = "Scute Swarm";
        Set<String> editions = Set.of("PRES", "ZNR");

        staticData.attemptToLoadCard(cardName);

        PaperCard nonPromoCard = cardDb.getCardFromEditionsPreferNonPromo(cardName,
                ORIGINAL_ART_ALL_EDITIONS, 1, card -> editions.contains(card.getEdition()));
        assertNotNull(nonPromoCard);
        assertEquals(nonPromoCard.getEdition(), "ZNR");

        PaperCard promoFallback = cardDb.getCardFromEditionsPreferNonPromo(cardName,
                ORIGINAL_ART_ALL_EDITIONS, 1, card -> "PRES".equals(card.getEdition()));
        assertNotNull(promoFallback);
        assertEquals(promoFallback.getEdition(), "PRES");
    }

    @Test
    public void selectedLanguageTakesPriorityOverNonPromoEdition() {
        String cardName = "Earthquake";

        staticData.attemptToLoadCard(cardName);
        cardDb.setPreferredCardLanguage("ja-JP");

        PaperCard japanesePromo = cardDb.getCardFromEditionsPreferNonPromo(cardName,
                ORIGINAL_ART_ALL_EDITIONS, 1, null);
        assertNotNull(japanesePromo);
        assertEquals(staticData.getCardEdition(japanesePromo.getEdition()).getCardsLangCode(), "ja");
        assertEquals(staticData.getCardEdition(japanesePromo.getEdition()).getType(), CardEdition.Type.PROMO);
    }
}
