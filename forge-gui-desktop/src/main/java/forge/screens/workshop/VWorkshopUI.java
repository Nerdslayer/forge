package forge.screens.workshop;

import javax.swing.SwingUtilities;

import forge.Singletons;
import forge.gui.framework.FScreen;
import forge.gui.framework.IVTopLevelUI;
import forge.screens.workshop.controllers.CCardCreator;
import forge.screens.workshop.controllers.CCardScript;
import forge.screens.workshop.views.VWorkshopCatalog;

/** 
/** 
 * Top level view class; instantiates and assembles
 * tabs used in deck editor UI drag layout.<br>
 *
 * <br><br><i>(V at beginning of class name denotes a view class.)</i>
 * 
 */
public enum VWorkshopUI implements IVTopLevelUI {
    /** */
    SINGLETON_INSTANCE;

    //========== Overridden methods

    /* (non-Javadoc)
     * @see forge.gui.framework.IVTopLevelUI#instantiate()
     */
    @Override
    public void instantiate() {
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVTopLevelUI#populate()
     */
    @Override
    public void populate() {
        SwingUtilities.invokeLater(() -> {
            if (CWorkshopUI.SINGLETON_INSTANCE.consumeInitialCardRequest()) {
                // Layout loading has already populated the catalog. Avoid requesting focus here:
                // its later focus event would re-fire the first-card selection after this new
                // draft is created and could prompt to save the untouched draft.
                CCardCreator.SINGLETON_INSTANCE.newCard();
            } else {
                VWorkshopCatalog.SINGLETON_INSTANCE.getCardManager().focus();
            }
        });
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVTopLevelUI#onSwitching(forge.gui.framework.FScreen)
     */
    @Override
    public boolean onSwitching(FScreen fromScreen, FScreen toScreen) {
        return CCardScript.SINGLETON_INSTANCE.canSwitchAway(false);
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.IVTopLevelUI#onClosing()
     */
    @Override
    public boolean onClosing(FScreen screen) {
        if (!CCardScript.SINGLETON_INSTANCE.canSwitchAway(false)) {
            return false;
        }
    	//don't close tab, but return to home screen if this called
        Singletons.getControl().setCurrentScreen(FScreen.HOME_SCREEN);
    	return false;
    }
}
