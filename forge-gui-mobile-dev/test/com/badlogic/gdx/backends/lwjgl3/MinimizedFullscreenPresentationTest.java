package com.badlogic.gdx.backends.lwjgl3;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.utils.Array;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWWindowCloseCallbackI;
import org.lwjgl.glfw.GLFWWindowIconifyCallbackI;
import org.lwjgl.opengl.GL11;
import org.mockito.InOrder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.testng.annotations.Test;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public final class MinimizedFullscreenPresentationTest {
    private static final long WINDOW = 1;

    @Test
    public void coalescesResizeAndRedrawWhileMinimizedAndRestoresAtCurrentSize() {
        try (Backend backend = new Backend(true)) {
            assertTrue(backend.window.update());
            backend.clearFrameHistory();

            backend.iconified = true;
            backend.resize(0, 0);
            assertFalse(backend.window.isIconified());
            verifyNoInteractions(backend.listener);
            backend.iconifyCallback.invoke(WINDOW, true);

            Runnable queuedWork = mock(Runnable.class);
            backend.window.postRunnable(queuedWork);
            backend.window.requestRendering();
            assertFalse(backend.window.update());
            assertFalse(backend.window.update());
            verify(queuedWork).run();
            verify(backend.listener).pause();
            verify(backend.listener, never()).render();
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), never());

            backend.window.getGraphics().setContinuousRendering(false);
            backend.iconified = false;
            backend.iconifyCallback.invoke(WINDOW, false);
            backend.resize(1024, 768);
            backend.resize(1280, 720);
            verify(backend.listener, never()).render();

            assertTrue(backend.window.update());
            assertFalse(backend.window.update());
            InOrder restoredFrame = inOrder(backend.listener, backend.gl);
            restoredFrame.verify(backend.listener).resume();
            restoredFrame.verify(backend.gl).glViewport(0, 0, 2560, 1440);
            restoredFrame.verify(backend.listener).resize(1280, 720);
            restoredFrame.verify(backend.listener).render();
            restoredFrame.verifyNoMoreInteractions();
            assertEquals(backend.window.getGraphics().getDeltaTime(), 0f);
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), times(1));
        }
    }

    @Test
    public void restoresOnDemandAfterMinimizeAndRestoreInOneEventPoll() {
        try (Backend backend = new Backend(true)) {
            backend.window.getGraphics().setContinuousRendering(false);
            backend.iconify(true);
            backend.iconify(false);

            assertTrue(backend.window.update());
            assertFalse(backend.window.update());
            InOrder callbacks = inOrder(backend.listener);
            callbacks.verify(backend.listener).pause();
            callbacks.verify(backend.listener).resume();
            callbacks.verify(backend.listener).resize(800, 600);
            callbacks.verify(backend.listener).render();
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), times(1));
        }
    }

    @Test
    public void reportsASpacePressOnlyOnTheFirstRestoredFrame() {
        try (Backend backend = new Backend(true)) {
            List<Boolean> spaceJustPressed = new ArrayList<>();
            doAnswer(call -> {
                spaceJustPressed.add(backend.window.getInput().isKeyJustPressed(Input.Keys.SPACE));
                return null;
            }).when(backend.listener).render();
            backend.window.getGraphics().setContinuousRendering(false);
            backend.iconify(true);
            assertFalse(backend.window.update());

            backend.iconify(false);
            backend.keyCallback.invoke(WINDOW, GLFW.GLFW_KEY_SPACE, 0, GLFW.GLFW_PRESS, 0);
            assertTrue(backend.window.update());

            backend.window.requestRendering();
            assertTrue(backend.window.update());
            assertFalse(backend.window.update());
            assertEquals(spaceJustPressed, List.of(true, false));
        }
    }

    @Test
    public void showsTheCloseListenerWhilePreservingItsVeto() {
        try (Backend backend = new Backend(true)) {
            backend.window.getGraphics().setContinuousRendering(false);
            backend.iconify(true);
            assertFalse(backend.window.update());
            backend.glfw.when(() -> GLFW.glfwRestoreWindow(WINDOW)).thenAnswer(call -> {
                backend.iconify(false);
                return null;
            });
            when(backend.windowListener.closeRequested()).thenAnswer(call -> {
                assertFalse(backend.iconified);
                return false;
            });
            backend.closeCallback.invoke(WINDOW);

            assertFalse(backend.window.update());
            verify(backend.windowListener).closeRequested();
            backend.glfw.verify(() -> GLFW.glfwSetWindowShouldClose(WINDOW, false));
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), never());
            assertTrue(backend.window.update());
            assertFalse(backend.window.update());
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), times(1));
        }
    }

    @Test
    public void stillPresentsAnUnfocusedOrWindowedFrame() {
        try (Backend backend = new Backend(true)) {
            assertFalse(backend.window.isFocused());
            assertTrue(backend.window.update());
            backend.resize(900, 600);
            verify(backend.listener).resize(900, 600);
            backend.monitor = 0;
            backend.iconify(true);
            assertTrue(backend.window.update());
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), times(3));
        }
    }

    @Test
    public void retainsTheDefaultBackendBehaviorWithoutTheLauncherOptIn() {
        try (Backend backend = new Backend(false)) {
            backend.iconify(true);
            assertTrue(backend.window.update());
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW));
            backend.glfw.verify(() -> GLFW.glfwGetWindowAttrib(WINDOW, GLFW.GLFW_ICONIFIED), never());
        }
    }

    @Test
    public void rechecksMinimizationAtTheActualSwap() {
        try (Backend backend = new Backend(true)) {
            doAnswer(call -> {
                backend.iconified = true;
                return null;
            }).when(backend.listener).render();
            assertFalse(backend.window.update());
            backend.glfw.verify(() -> GLFW.glfwSwapBuffers(WINDOW), never());
        }
    }

    // Instantiate the patched backend and its callbacks without creating a GLFW window or GL context.
    private static final class Backend implements AutoCloseable {
        final MockedStatic<GLFW> glfw = mockStatic(GLFW.class);
        final ApplicationListener listener = mock(ApplicationListener.class);
        final Lwjgl3WindowListener windowListener = mock(Lwjgl3WindowListener.class);
        final Lwjgl3Window window;
        final Lwjgl3GL20 gl;
        GLFWWindowIconifyCallbackI iconifyCallback;
        GLFWWindowCloseCallbackI closeCallback;
        GLFWKeyCallbackI keyCallback;
        long monitor = 2;
        boolean iconified;
        int width = 800;
        int height = 600;

        Backend(boolean enabled) {
            glfw.when(() -> GLFW.glfwGetWindowMonitor(WINDOW)).thenAnswer(call -> monitor);
            glfw.when(() -> GLFW.glfwGetWindowAttrib(WINDOW, GLFW.GLFW_ICONIFIED))
                    .thenAnswer(call -> iconified ? GLFW.GLFW_TRUE : GLFW.GLFW_FALSE);
            glfw.when(() -> GLFW.glfwGetWindowSize(eq(WINDOW), any(IntBuffer.class), any(IntBuffer.class)))
                    .thenAnswer(call -> {
                        call.<IntBuffer>getArgument(1).put(0, width);
                        call.<IntBuffer>getArgument(2).put(0, height);
                        return null;
                    });
            glfw.when(() -> GLFW.glfwGetFramebufferSize(eq(WINDOW), any(IntBuffer.class), any(IntBuffer.class)))
                    .thenAnswer(call -> {
                        call.<IntBuffer>getArgument(1).put(0, width * 2);
                        call.<IntBuffer>getArgument(2).put(0, height * 2);
                        return null;
                    });
            glfw.when(() -> GLFW.glfwSetWindowIconifyCallback(eq(WINDOW), any(GLFWWindowIconifyCallbackI.class)))
                    .thenAnswer(call -> {
                        iconifyCallback = call.getArgument(1);
                        return null;
                    });
            glfw.when(() -> GLFW.glfwSetWindowCloseCallback(eq(WINDOW), any(GLFWWindowCloseCallbackI.class)))
                    .thenAnswer(call -> {
                        closeCallback = call.getArgument(1);
                        return null;
                    });
            glfw.when(() -> GLFW.glfwSetKeyCallback(eq(WINDOW), any(GLFWKeyCallbackI.class)))
                    .thenAnswer(call -> {
                        keyCallback = call.getArgument(1);
                        return null;
                    });

            Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
            config.setSuspendFullscreenPresentationWhenMinimized(enabled);
            config.setWindowListener(windowListener);
            Lwjgl3ApplicationBase application = mock(Lwjgl3ApplicationBase.class);
            window = new Lwjgl3Window(listener, new Array<>(), Lwjgl3ApplicationConfiguration.copy(config), application);
            when(application.createInput(window)).thenAnswer(call -> new DefaultLwjgl3Input(window));
            try (MockedConstruction<Lwjgl3GL20> glConstruction = mockConstruction(Lwjgl3GL20.class,
                    (mockGl, context) -> when(mockGl.glGetString(GL11.GL_VERSION)).thenReturn("2.0"))) {
                window.create(WINDOW);
                gl = glConstruction.constructed().get(0);
            }
            window.initializeListener();
            clearFrameHistory();
        }

        void iconify(boolean value) {
            iconified = value;
            iconifyCallback.invoke(WINDOW, value);
        }

        void resize(int currentWidth, int currentHeight) {
            width = currentWidth;
            height = currentHeight;
            window.getGraphics().resizeCallback.invoke(WINDOW, currentWidth * 2, currentHeight * 2);
        }

        void clearFrameHistory() {
            clearInvocations(listener, gl);
            glfw.clearInvocations();
        }

        @Override
        public void close() {
            try {
                window.dispose();
            } finally {
                glfw.close();
            }
        }
    }
}
