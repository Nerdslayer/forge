package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.utils.Scaling;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScalingViewport;
import com.github.tommyettinger.textra.TextraButton;
import forge.Forge;
import forge.adventure.util.Controls;
import forge.adventure.util.KeyBinding;

import java.util.ArrayList;
import java.util.List;

/** A modal overlay shared by Scene2D exploration/menu screens and classic match/editor screens. */
public final class AdventureExitDialog {
    private static AdventureExitDialog current;
    private final Stage stage;
    private final Dialog dialog;
    private InputProcessor previousInput;
    private final List<TextraButton> buttons = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    private int selected;

    private AdventureExitDialog(String message, boolean canSave, Runnable cancel, Runnable save, Runnable discard) {
        previousInput = Gdx.input.getInputProcessor();
        stage = new Stage(new ScalingViewport(Scaling.fit, Scene.getIntendedWidth(), Scene.getIntendedHeight()),
                Forge.getGraphics().getBatch());
        dialog = new Dialog(text("lblAdventureUnsavedProgress"), Controls.getSkin());
        float width = Scene.getIntendedWidth() * 0.85f;
        Label label = Controls.newLabel(message);
        label.setWrap(true);
        label.setAlignment(Align.topLeft);
        label.setWidth(width);
        dialog.getContentTable().add(label).width(width).pad(8);
        addButton("lblAdventureKeepPlaying", cancel);
        if (canSave) addButton("lblAdventureSaveAndContinue", save);
        addButton("lblAdventureContinueWithoutSaving", discard);
        dialog.pack();
        float overflow = dialog.getHeight() - Scene.getIntendedHeight() * 0.94f;
        if (overflow > 0) {
            float height = Math.max(40, label.getPrefHeight() - overflow);
            dialog.getContentTable().clearChildren();
            ScrollPane messagePane = new ScrollPane(label, Controls.getSkin());
            messagePane.setScrollingDisabled(true, false);
            dialog.getContentTable().add(messagePane).width(width).height(height).pad(8);
            stage.setScrollFocus(messagePane);
        }
        dialog.show(stage);
        stage.addCaptureListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, int keycode) {
                return key(keycode);
            }
        });
        select(0); // Enter/controller activation defaults to keeping the live session.
        Gdx.input.setInputProcessor(stage);
    }

    private void addButton(String label, Runnable action) {
        int index = buttons.size();
        TextraButton button = Controls.newTextButton(text(label), () -> choose(index));
        buttons.add(button);
        actions.add(action);
        dialog.getButtonTable().add(button).growX().pad(2);
        dialog.getButtonTable().row();
    }

    private void choose(int index) {
        Runnable action = actions.get(index);
        current = null;
        if (Gdx.input.getInputProcessor() == stage) Gdx.input.setInputProcessor(previousInput);
        stage.dispose();
        action.run();
    }

    private void select(int index) {
        selected = Math.floorMod(index, buttons.size());
        for (int i = 0; i < buttons.size(); i++) buttons.get(i).setChecked(i == selected);
        stage.setKeyboardFocus(buttons.get(selected));
    }

    private boolean key(int keycode) {
        if (KeyBinding.Back.isPressed(keycode)) choose(0);
        else if (KeyBinding.Up.isPressed(keycode) || KeyBinding.Left.isPressed(keycode)) select(selected - 1);
        else if (KeyBinding.Down.isPressed(keycode) || KeyBinding.Right.isPressed(keycode) || keycode == Input.Keys.TAB) select(selected + 1);
        else if (KeyBinding.Use.isPressed(keycode)) choose(selected);
        return true; // Never pass keys through to exploration, rewards, or the editor.
    }

    public static void show(String message, boolean canSave, Runnable cancel, Runnable save, Runnable discard) {
        if (current == null) current = new AdventureExitDialog(message, canSave, cancel, save, discard);
    }

    public static boolean isOpen() { return current != null; }

    public static boolean controllerButton(Controller controller, int button, boolean pressed) {
        if (current == null) return false;
        if (pressed) current.key(KeyBinding.controllerButtonToKey(controller, button));
        return true;
    }

    public static void render(float delta) {
        AdventureExitDialog popup = current;
        if (popup == null) return;
        // A match can finish asynchronously while its exit prompt is visible.
        // Keep the overlay modal, then restore the new scene's input rather than an obsolete processor.
        if (Gdx.input.getInputProcessor() != popup.stage) {
            popup.previousInput = Gdx.input.getInputProcessor();
            Gdx.input.setInputProcessor(popup.stage);
        }
        popup.stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        popup.dialog.setPosition((popup.stage.getWidth() - popup.dialog.getWidth()) / 2,
                (popup.stage.getHeight() - popup.dialog.getHeight()) / 2);
        popup.stage.act(delta);
        if (current == popup) popup.stage.draw();
    }

    private static String text(String key) { return Forge.getLocalizer().getMessage(key); }
}
