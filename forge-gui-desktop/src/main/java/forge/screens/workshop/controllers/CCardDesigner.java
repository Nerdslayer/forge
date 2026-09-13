package forge.screens.workshop.controllers;

import java.io.IOException;

import forge.gui.framework.ICDoc;
import forge.screens.workshop.views.VCardDesigner;

/**
 * Controls the "card designer" panel in the workshop UI.
 *
 * <br><br><i>(C at beginning of class name denotes a control class.)</i>
 *
 */
public enum CCardDesigner implements ICDoc {
    /** */
    SINGLETON_INSTANCE;

    CCardDesigner() {
        VCardDesigner.SINGLETON_INSTANCE.setChangeListener(
                () -> CCardCreator.SINGLETON_INSTANCE.getSession().updateFromDesigner(VCardDesigner.SINGLETON_INSTANCE.readDraft()));
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
        try {
            VCardDesigner.SINGLETON_INSTANCE.reloadSets(CCardCreator.SINGLETON_INSTANCE.getSession().getCustomSets());
        } catch (final IOException ignored) {
            // The form remains usable; a custom set can be created from the editor.
        }
        CCardCreator.SINGLETON_INSTANCE.refreshViews();
    }

    /* (non-Javadoc)
     * @see forge.gui.framework.ICDoc#update()
     */
    @Override
    public void update() {
    }
}
