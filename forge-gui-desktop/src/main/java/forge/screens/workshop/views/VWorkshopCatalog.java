package forge.screens.workshop.views;

import javax.swing.JPanel;

import com.google.common.collect.Iterables;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.IVDoc;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.itemmanager.ItemManagerContainer;
import forge.model.FModel;
import forge.screens.match.controllers.CDetailPicture;
import forge.screens.workshop.controllers.CCardScript;
import forge.screens.workshop.controllers.CWorkshopCatalog;
import forge.toolbox.FTabbedPane;
import forge.util.ItemPool;
import forge.util.Localizer;
import net.miginfocom.swing.MigLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles Swing components of card catalog in workshop.
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 *
 */
public enum VWorkshopCatalog implements IVDoc<CWorkshopCatalog> {
    SINGLETON_INSTANCE;
    final Localizer localizer = Localizer.getInstance();
    // Fields used with interface IVDoc
    private DragCell parentCell;
    private final DragTab tab = new DragTab(localizer.getMessage("lblCardCatalog"));
    private final CDetailPicture cDetailPicture = new CDetailPicture();
    private final CardManager customCardManager;
    private final CardManager cardManager;
    private final ItemManagerContainer customCardManagerContainer;
    private final ItemManagerContainer cardManagerContainer;
    private final FTabbedPane cardCatalogTabs = new FTabbedPane();

    //========== Constructor
    VWorkshopCatalog() {
        this.customCardManager = new CardManager(cDetailPicture, true, false, false);
        this.customCardManager.setCaption(localizer.getMessageorUseDefault(
                "lblCardCreatorCustomCards", "Custom Cards"));
        final List<PaperCard> allCards = getAllCards();
        this.customCardManager.setPool(ItemPool.createFrom(getCustomCards(allCards), PaperCard.class), true);
        this.customCardManagerContainer = new ItemManagerContainer(this.customCardManager);

        this.cardManager = new CardManager(cDetailPicture, true, false, false);
        this.cardManager.setCaption(localizer.getMessage("lblCatalog"));
        this.cardManager.setPool(ItemPool.createFrom(allCards, PaperCard.class), true);
        this.cardManagerContainer = new ItemManagerContainer();
        this.cardManagerContainer.setItemManager(this.cardManager);

        this.customCardManager.addSelectionListener(e -> showSelectedCard(this.customCardManager));
        this.cardManager.addSelectionListener(e -> showSelectedCard(this.cardManager));

        this.cardCatalogTabs.addTab(localizer.getMessageorUseDefault(
                "lblCardCreatorCustomCards", "Custom Cards"), this.customCardManagerContainer);
        this.cardCatalogTabs.addTab(localizer.getMessageorUseDefault(
                "lblCardCreatorAllCards", "All Cards"), this.cardManagerContainer);
    }

    //========== Overridden from IVDoc

    @Override
    public EDocID getDocumentID() {
        return EDocID.WORKSHOP_CATALOG;
    }

    @Override
    public DragTab getTabLabel() {
        return tab;
    }

    @Override
    public CWorkshopCatalog getLayoutControl() {
        return CWorkshopCatalog.SINGLETON_INSTANCE;
    }

    @Override
    public void setParentCell(final DragCell cell0) {
        this.parentCell = cell0;
    }

    @Override
    public DragCell getParentCell() {
        return this.parentCell;
    }

    @Override
    public void populate() {
        final JPanel parentBody = parentCell.getBody();
        parentBody.setLayout(new MigLayout("insets 5, gap 0, wrap, hidemode 3"));
        parentBody.add(cardCatalogTabs, "push, grow");
    }

    public CardManager getCardManager() {
        return customCardManager;
    }

    public CardManager getCustomCardManager() {
        return customCardManager;
    }

    public CardManager getAllCardManager() {
        return cardManager;
    }

    public CDetailPicture getCDetailPicture() {
        return cDetailPicture;
    }

    public void selectCustomCardsTab() {
        cardCatalogTabs.setSelectedIndex(0);
    }

    /** Clears the detail and picture panels when the editor is showing an unsaved draft. */
    public void clearCardDisplay() {
        customCardManager.clearSelection();
        cardManager.clearSelection();
        cDetailPicture.showItem(null);
    }

    /** Selects a saved card in whichever catalog contains it. */
    public void selectCard(final PaperCard card) {
        if (card == null) return;
        customCardManager.setSelectedItem(card);
        cardManager.setSelectedItem(card);
    }

    /** Restores a prior selection after a user cancels the save-changes prompt. */
    public void restoreSelection(final PaperCard card) {
        if (card == null) return;
        customCardManager.setSelectedItem(card);
        cardManager.setSelectedItem(card);
    }

    /** Refreshes the catalog after the creator adds a custom definition to the in-memory database. */
    public void refreshPool(final PaperCard preferredCard) {
        final List<PaperCard> allCards = getAllCards();
        final List<PaperCard> customCards = getCustomCards(allCards);
        customCardManager.setPool(ItemPool.createFrom(customCards, PaperCard.class), true);
        cardManager.setPool(ItemPool.createFrom(allCards, PaperCard.class), true);
        selectCard(preferredCard);
        cardManager.repaint();
        customCardManager.repaint();
    }

    private void showSelectedCard(final CardManager source) {
        final PaperCard card = source.getSelectedItem();
        if (card == null) return;
        CCardScript.SINGLETON_INSTANCE.showCard(card, source);
        if (CCardScript.SINGLETON_INSTANCE.getCurrentCard() == card) cDetailPicture.showItem(card);
    }

    private static List<PaperCard> getAllCards() {
        final List<PaperCard> result = new ArrayList<>();
        for (final PaperCard card : Iterables.concat(FModel.getMagicDb().getCommonCards().getAllCards(),
                FModel.getMagicDb().getVariantCards().getAllCards())) {
            result.add(card);
        }
        return result;
    }

    private static List<PaperCard> getCustomCards(final List<PaperCard> allCards) {
        return allCards.stream().filter(card -> card.getRules().isCustom()).toList();
    }
}
