package forge.screens.workshop.menus;

import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import forge.localinstance.skin.FSkinProp;
import forge.menus.MenuUtil;
import forge.screens.workshop.controllers.CCardCreator;
import forge.screens.workshop.controllers.CCardScript;
import forge.toolbox.FSkin.SkinnedMenuItem;
import forge.util.Localizer;

/**
 * Returns a JMenu containing options associated with current game.
 * <p>
 * Replicates options available in Dock tab.
 */
public final class WorkshopFileMenu {
    private WorkshopFileMenu() { }

    private static boolean showIcons;

    public static JMenu getMenu(boolean showMenuIcons) {
    	showIcons = showMenuIcons;

        JMenu menu = new JMenu("File");
        menu.setMnemonic(KeyEvent.VK_F);
        menu.add(getMenuItem_NewCard());
        menu.add(getMenuItem_NewSet());
        menu.addSeparator();
        menu.add(getMenuItem_SaveCard());
        menu.add(getMenuItem_SaveCardAs());
        return menu;
    }

    private static JMenuItem menuItem_NewCard;
    private static JMenuItem menuItem_NewSet;

    private static JMenuItem getMenuItem_NewCard() {
        if (menuItem_NewCard == null) {
            menuItem_NewCard = new SkinnedMenuItem(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewCard", "New Card"));
            menuItem_NewCard.addActionListener(e -> CCardCreator.SINGLETON_INSTANCE.newCard());
        }
        return menuItem_NewCard;
    }

    private static JMenuItem getMenuItem_NewSet() {
        if (menuItem_NewSet == null) {
            menuItem_NewSet = new SkinnedMenuItem(Localizer.getInstance().getMessageorUseDefault("lblCardCreatorNewSet", "New Set..."));
            menuItem_NewSet.addActionListener(e -> CCardCreator.SINGLETON_INSTANCE.createSet());
        }
        return menuItem_NewSet;
    }
    
    private static JMenuItem menuItem_SaveCard;
    private static JMenuItem menuItem_SaveCardAs;
    
    public static void updateSaveEnabled() {
        if (menuItem_SaveCard == null)
            getMenuItem_SaveCard();
        if (menuItem_SaveCardAs == null)
            getMenuItem_SaveCardAs();
    	menuItem_SaveCard.setEnabled(CCardScript.SINGLETON_INSTANCE.hasChanges());
        menuItem_SaveCardAs.setEnabled(CCardScript.SINGLETON_INSTANCE.hasChanges());
    }

    private static JMenuItem getMenuItem_SaveCard() {
        SkinnedMenuItem menuItem = new SkinnedMenuItem("Save and Apply Card Changes");
        menuItem.setIcon(showIcons ? MenuUtil.getMenuIcon(FSkinProp.ICO_SAVE) : null);
        menuItem.setAccelerator(MenuUtil.getAcceleratorKey(KeyEvent.VK_S));
        menuItem.addActionListener(getSaveCardAction());
        menuItem_SaveCard = menuItem;
        updateSaveEnabled();
        return menuItem;
    }

    private static ActionListener getSaveCardAction() {
        return e -> CCardScript.SINGLETON_INSTANCE.saveChanges();
    }

    private static JMenuItem getMenuItem_SaveCardAs() {
        final SkinnedMenuItem menuItem = new SkinnedMenuItem(
                Localizer.getInstance().getMessageorUseDefault("lblCardCreatorSaveAs", "Save As..."));
        menuItem.setIcon(showIcons ? MenuUtil.getMenuIcon(FSkinProp.ICO_SAVE) : null);
        menuItem.addActionListener(e -> CCardCreator.SINGLETON_INSTANCE.saveAs());
        menuItem_SaveCardAs = menuItem;
        return menuItem;
    }
}
