// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns mcctl actions into real Minecraft input.
 *
 * <p>Everything is executed on the client (render) thread through {@link Minecraft#execute}. Two
 * mechanisms are used, exactly like the vanilla input pipeline:</p>
 *
 * <ul>
 *   <li>In game (no screen open): a key/mouse code is turned into a
 *       {@link InputConstants.Key} and fed to {@link KeyMapping}. {@code click()} feeds
 *       {@code consumeClick()}-driven actions (hotbar, inventory, drop, attack, use, ...) and
 *       {@code set(..., true/false)} feeds {@code isDown()}-driven actions (walking, sneaking,
 *       sprinting, breaking blocks, ...).</li>
 *   <li>With a screen open: events are forwarded to that {@link Screen} (so {@code E} closes the
 *       inventory, {@code esc} goes back one screen, mouse clicks hit buttons/slots).</li>
 * </ul>
 */
public final class McInputExecutor implements InputExecutor {
	private static final Logger LOG = Logger.getLogger("mcctl");

	/** How long the HTTP thread waits for the typing commands to finish on the client thread. */
	private static final long TYPE_TIMEOUT_MS = 5_000L;

	/** Reported by {@code type} / {@code typeEnter} when there is nothing to type into. */
	private static final String NO_TEXT_BOX =
			"no focused text box - open the chat box with T first (anvil, sign and book are not supported)";

	/** private MouseHandler#onScroll(long, double, double) - used for the mouse wheel. */
	private static Method onScroll;

	/** private MouseHandler#xpos / #ypos - synced after an absolute cursor move. */
	private static Field cursorX;
	private static Field cursorY;

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static void onClientThread(Runnable task) {
		Minecraft minecraft = mc();
		if (minecraft == null) {
			return;
		}
		try {
			if (minecraft.isSameThread()) {
				task.run();
			} else {
				minecraft.execute(task);
			}
		} catch (RuntimeException e) {
			LOG.log(Level.FINE, "input dropped (game shutting down?)", e);
		}
	}

	private static MouseButtonEvent mouseEvent(int button) {
		Minecraft minecraft = mc();
		double x = 0.0;
		double y = 0.0;
		if (minecraft != null && minecraft.mouseHandler != null && minecraft.getWindow() != null) {
			x = minecraft.mouseHandler.getScaledXPos(minecraft.getWindow());
			y = minecraft.mouseHandler.getScaledYPos(minecraft.getWindow());
		}
		return new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
	}

	/**
	 * Runs a task on the client thread and hands its result back to the caller.
	 *
	 * <p>Only used by the typing commands, which have to report a failure (no text box) as an HTTP
	 * status; everything else stays fire-and-forget.</p>
	 */
	private static String callOnClientThread(Supplier<String> task, long timeoutMs) {
		Minecraft minecraft = mc();
		if (minecraft == null) {
			return "Minecraft client is not running";
		}
		if (minecraft.isSameThread()) {
			return task.get();
		}

		CompletableFuture<String> result = new CompletableFuture<>();
		minecraft.execute(() -> {
			try {
				result.complete(task.get());
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});

		try {
			return result.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return "the client thread did not answer within " + timeoutMs + " ms";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted";
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			return cause == null ? String.valueOf(e) : String.valueOf(cause);
		}
	}

	/**
	 * @return true when the chat box is open <em>right now</em>. The screen is read fresh on every
	 *         call, nothing about the chat box is remembered between requests.
	 */
	private static boolean chatFocused(Minecraft minecraft) {
		return minecraft.gui.screen() instanceof ChatScreen;
	}

	/** @return the focused text box of that screen, or {@code null} (chat box yes, anvil/sign/book no) */
	private static EditBox focusedTextBox(Screen screen) {
		return screen != null && screen.getFocused() instanceof EditBox box ? box : null;
	}

	// ------------------------------------------------------------------ keys

	@Override
	public void keyDown(String canonicalKey) {
		int code = Keys.code(canonicalKey);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);

			// Global keys (fullscreen, screenshot, friends) are handled before anything else,
			// exactly like the vanilla KeyboardHandler does.
			if (minecraft.handleGlobalKeyPress(key, false)) {
				return;
			}

			Screen screen = minecraft.gui.screen();

			// While the chat box is open the routing follows intent, and the chat box state is read
			// fresh for every request (nothing is cached):
			//   * E / Q / 1-9  -> close the chat box, then run as usual. Vanilla skips
			//     handleKeybinds() while a screen is open, so the open/close/drop/select actions
			//     would otherwise be swallowed.
			//   * editing keys (backspace, arrows, enter, ...) and esc -> stay in the chat box.
			//   * everything else (WASD, space, ...) -> keep controlling the game while typing.
			if (screen != null && chatFocused(minecraft)) {
				if (Keys.isChatAction(code)) {
					screen.keyPressed(new KeyEvent(Keys.ESC, 0, 0));
					screen = minecraft.gui.screen();
				} else if (Keys.isTextEditing(code) || code == Keys.ESC) {
					screen.keyPressed(new KeyEvent(code, 0, 0));
					return;
				} else {
					screen = null;
				}
			}

			if (screen != null) {
				screen.keyPressed(new KeyEvent(code, 0, 0));
				return;
			}
			if (code == Keys.ESC) {
				minecraft.pauseGame(false);
				return;
			}

			KeyMapping.click(key);
			KeyMapping.set(key, true);

			if (code == Keys.F3) {
				// F3 alone toggles the debug overlay; that logic lives in KeyboardHandler.
				minecraft.debugEntries.toggleDebugOverlay();
			}
		});
	}

	@Override
	public void keyUp(String canonicalKey) {
		int code = Keys.code(canonicalKey);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(code);
			KeyMapping.set(key, false);

			Screen screen = minecraft.gui.screen();
			if (screen == null) {
				return;
			}
			if (chatFocused(minecraft) && !Keys.isTextEditing(code) && code != Keys.ESC) {
				// The press went to the game (movement keys and friends), so the release must too.
				return;
			}
			screen.keyReleased(new KeyEvent(code, 0, 0));
		});
	}

	// ----------------------------------------------------------------- typing

	@Override
	public String typeText(String text) {
		return callOnClientThread(() -> {
			Minecraft minecraft = mc();
			if (minecraft == null) {
				return "Minecraft client is not running";
			}
			if (text == null || text.isEmpty()) {
				return "nothing to type";
			}

			Screen screen = minecraft.gui.screen();
			if (focusedTextBox(screen) == null) {
				return NO_TEXT_BOX;
			}

			for (int i = 0; i < text.length(); ) {
				int codepoint = text.codePointAt(i);
				i += Character.charCount(codepoint);
				screen.charTyped(new CharacterEvent(codepoint));
			}
			return null;
		}, TYPE_TIMEOUT_MS);
	}

	@Override
	public String typeEnter() {
		return callOnClientThread(() -> {
			Minecraft minecraft = mc();
			if (minecraft == null) {
				return "Minecraft client is not running";
			}

			Screen screen = minecraft.gui.screen();
			if (focusedTextBox(screen) == null) {
				return NO_TEXT_BOX;
			}

			screen.keyPressed(new KeyEvent(Keys.ENTER, 0, 0));
			return null;
		}, TYPE_TIMEOUT_MS);
	}

	// ----------------------------------------------------------- mouse buttons

	@Override
	public void mouseButtonDown(String canonicalButton) {
		int button = Keys.mouseButton(canonicalButton);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();
			if (screen != null && !chatFocused(minecraft)) {
				screen.mouseClicked(mouseEvent(button), false);
				return;
			}
			InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
			KeyMapping.click(key);
			KeyMapping.set(key, true);
		});
	}

	@Override
	public void mouseButtonUp(String canonicalButton) {
		int button = Keys.mouseButton(canonicalButton);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(button);
			KeyMapping.set(key, false);

			Screen screen = minecraft.gui.screen();
			if (screen != null && !chatFocused(minecraft)) {
				screen.mouseReleased(mouseEvent(button));
			}
		});
	}

	// ------------------------------------------------------------ mouse motion

	@Override
	public void mouseMove(int dx, int dy) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();

			if (screen != null && !chatFocused(minecraft) && minecraft.mouseHandler != null
					&& !minecraft.mouseHandler.isMouseGrabbed()) {
				// GUI: move the real cursor and keep the tracked position in step. GLFW only feeds
				// the new position back through the cursor callback on the next glfwPollEvents - and
				// under Xwayland a warp often produces no callback at all - so without the sync the
				// deltas would not accumulate and a following click would use the old spot.
				Window window = minecraft.getWindow();
				MouseHandler mouse = minecraft.mouseHandler;
				int px = clamp((int) Math.round(mouse.xpos()) + dx, 0, Math.max(window.getScreenWidth() - 1, 0));
				int py = clamp((int) Math.round(mouse.ypos()) + dy, 0, Math.max(window.getScreenHeight() - 1, 0));
				GLFW.glfwSetCursorPos(window.handle(), px, py);
				syncCursorPos(mouse, px, py);
				return;
			}

			LocalPlayer player = minecraft.player;
			if (player == null) {
				return;
			}
			// Same maths as MouseHandler#turnPlayer: sensitivity curve, invert options,
			// then Entity#turn applies the final *0.15.
			double sensitivity = minecraft.options.sensitivity().get();
			double factor = sensitivity * 0.6 + 0.2;
			factor = factor * factor * factor * 8.0;
			double yaw = dx * factor;
			double pitch = dy * factor;
			if (minecraft.options.invertMouseX().get()) {
				yaw = -yaw;
			}
			if (minecraft.options.invertMouseY().get()) {
				pitch = -pitch;
			}
			player.turn(yaw, pitch);
		});
	}

	// ----------------------------------------------------------- cursor position

	/**
	 * Puts the cursor at an absolute window-pixel position (the space {@code GET /mouse} reports and
	 * the screenshots use).
	 *
	 * <p>Doing nothing while the mouse is grabbed is deliberate: in the world GLFW disables the
	 * cursor and parks it at the window centre, so there is no free cursor to move (turn the view
	 * with {@code mouse move} instead).</p>
	 */
	@Override
	public void mouseGoto(int x, int y) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			if (minecraft == null || minecraft.mouseHandler == null || minecraft.getWindow() == null) {
				return;
			}
			MouseHandler mouse = minecraft.mouseHandler;
			if (mouse.isMouseGrabbed()) {
				LOG.fine("mouse goto ignored: no screen open, the cursor is grabbed");
				return;
			}

			Window window = minecraft.getWindow();
			int px = clamp(x, 0, Math.max(window.getScreenWidth() - 1, 0));
			int py = clamp(y, 0, Math.max(window.getScreenHeight() - 1, 0));
			GLFW.glfwSetCursorPos(window.handle(), px, py);
			// GLFW only reports the new position back through the cursor callback, which runs on the
			// next glfwPollEvents, so a click in the same request would still use the old spot.
			// Mirror MouseHandler#releaseMouse (it patches xpos/ypos right after glfwSetCursorPos).
			syncCursorPos(mouse, px, py);
		});
	}

	/**
	 * @return the cursor state as {@code "<field>：<value>"} lines, read on the client thread
	 * @throws IllegalStateException when the client is gone or does not answer in time
	 */
	@Override
	public String mousePosition() {
		if (mc() == null) {
			throw new IllegalStateException("Minecraft client is not running");
		}

		CompletableFuture<String> result = new CompletableFuture<>();
		onClientThread(() -> {
			try {
				result.complete(describeMouse());
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});

		try {
			return result.get(TYPE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("the client thread did not answer within " + TYPE_TIMEOUT_MS + " ms");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(cause == null ? String.valueOf(e) : String.valueOf(cause), cause);
		}
	}

	/** Runs on the client thread: reads the cursor in window pixels and in GUI-scaled units. */
	private static String describeMouse() {
		Minecraft minecraft = mc();
		Window window = minecraft == null ? null : minecraft.getWindow();
		MouseHandler mouse = minecraft == null ? null : minecraft.mouseHandler;
		if (window == null || mouse == null) {
			throw new IllegalStateException("the client is still starting up");
		}

		Screen screen = minecraft.gui.screen();
		boolean grabbed = mouse.isMouseGrabbed();

		// GLFW only delivers cursor motion while the pointer is over the content area, so once it
		// wanders off the window MouseHandler#xpos/ypos freezes at the last in-window pixel and
		// looks like a perfectly valid position. GLFW_HOVERED is the portable answer to "is the
		// cursor over this window" (X11 Enter/Leave, Wayland wl_pointer, Win32, Cocoa), so no
		// platform check is needed; glfwGetCursorPos would not do (X11 reports out-of-window
		// coordinates, Wayland cannot know them at all).
		boolean hovered = GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_HOVERED) == GLFW.GLFW_TRUE;

		StringBuilder out = new StringBuilder();
		if (!grabbed && !hovered) {
			out.append("光标：不在窗口内，请使用 mouse goto <x> <y>\n");
		} else {
			out.append("光标：").append(decimal(mouse.xpos())).append(' ').append(decimal(mouse.ypos())).append('\n')
					.append("缩放：").append(decimal(mouse.getScaledXPos(window))).append(' ')
					.append(decimal(mouse.getScaledYPos(window))).append('\n');
		}
		return out
				.append("窗口：").append(window.getScreenWidth()).append('x').append(window.getScreenHeight()).append('\n')
				.append("GUI：").append(window.getGuiScaledWidth()).append('x')
				.append(window.getGuiScaledHeight()).append('\n')
				.append("抓取：").append(grabbed ? "是" : "否").append('\n')
				.append("界面：").append(screen == null ? "无" : screen.getClass().getSimpleName()).append('\n')
				.toString();
	}

	private static String decimal(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

	private static int clamp(int value, int min, int max) {
		return value < min ? min : Math.min(value, max);
	}

	private static void syncCursorPos(MouseHandler mouse, double x, double y) {
		try {
			if (cursorX == null) {
				cursorX = MouseHandler.class.getDeclaredField("xpos");
				cursorX.setAccessible(true);
				cursorY = MouseHandler.class.getDeclaredField("ypos");
				cursorY.setAccessible(true);
			}
			cursorX.setDouble(mouse, x);
			cursorY.setDouble(mouse, y);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Not fatal: the real cursor still moved, only a click in the same request might use the
			// previous position (put a small delay in front of the click then).
			LOG.log(Level.FINE, "could not sync the tracked cursor position", e);
		}
	}

	@Override
	public void mouseScroll(double amount) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();
			if (screen != null && !chatFocused(minecraft)) {
				screen.mouseScrolled(minecraft.mouseHandler.getScaledXPos(minecraft.getWindow()),
						minecraft.mouseHandler.getScaledYPos(minecraft.getWindow()), 0.0, amount);
				return;
			}
			try {
				if (onScroll == null) {
					onScroll = minecraft.mouseHandler.getClass().getDeclaredMethod("onScroll",
							long.class, double.class, double.class);
					onScroll.setAccessible(true);
				}
				onScroll.invoke(minecraft.mouseHandler, minecraft.getWindow().handle(), 0.0, amount);
			} catch (ReflectiveOperationException | RuntimeException e) {
				LOG.log(Level.FINE, "mouse wheel is unavailable in this Minecraft build", e);
			}
		});
	}

	// ------------------------------------------------------------------- chat

	/**
	 * Sends chat, including Baritone commands.
	 *
	 * <p>{@code #...} is handed straight to Baritone's command manager
	 * ({@code BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(...)})
	 * when Baritone is installed - exactly what typing {@code #...} into the chat box does, minus the
	 * round trip through a chat packet. Without Baritone the message is sent as normal chat, so
	 * Baritone's own chat hook (or the server) still sees it.</p>
	 *
	 * <p>Baritone types are only touched reflectively: this mod has no hard dependency on Baritone.</p>
	 */
	@Override
	public void sendChat(String message) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			if (message.startsWith("#") && minecraft.player != null
					&& baritoneCommand(message.substring(1))) {
				return;
			}

			ClientPacketListener connection = minecraft.getConnection();
			if (connection == null) {
				LOG.warning("cannot send chat message, not in a world: " + message);
				return;
			}
			if (message.startsWith("/")) {
				connection.sendCommand(message.substring(1));
			} else {
				connection.sendChat(message);
			}
		});
	}

	/** @return true when Baritone took the command (it prints its own errors for bad input) */
	private static boolean baritoneCommand(String command) {
		try {
			Object provider = Class.forName("baritone.api.BaritoneAPI")
					.getMethod("getProvider").invoke(null);
			Object baritone = Class.forName("baritone.api.IBaritoneProvider")
					.getMethod("getPrimaryBaritone").invoke(provider);
			if (baritone == null) {
				return false;
			}
			Object commandManager = Class.forName("baritone.api.IBaritone")
					.getMethod("getCommandManager").invoke(baritone);
			if (commandManager == null) {
				return false;
			}
			Class.forName("baritone.api.command.manager.ICommandManager")
					.getMethod("execute", String.class).invoke(commandManager, command);
			return true;
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			return false; // Baritone is not installed: fall back to a normal chat message
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.log(Level.WARNING, "baritone API call failed, using chat instead: " + command, e);
			return false;
		}
	}

	// ------------------------------------------------------------------- mods

	@Override
	public String loadedMods() {
		StringBuilder out = new StringBuilder();
		FabricLoader.getInstance().getAllMods().stream()
				.map(ModContainer::getMetadata)
				.sorted(Comparator.comparing(ModMetadata::getId))
				.forEach(metadata -> out
						.append(metadata.getId()).append(' ')
						.append(metadata.getVersion().getFriendlyString()).append(' ')
						.append(metadata.getName().replace('\n', ' ').replace('\r', ' '))
						.append('\n'));
		return out.toString();
	}

	// ------------------------------------------------------------- screenshot

	@Override
	public byte[] captureScreenshot() throws Exception {
		Minecraft minecraft = mc();
		if (minecraft == null) {
			throw new IllegalStateException("Minecraft client is not running");
		}

		CompletableFuture<byte[]> result = new CompletableFuture<>();
		onClientThread(() -> {
			try {
				// Same readback vanilla uses for F2, but we encode into a temporary file so the
				// HTTP thread can hand the PNG straight back to the caller.
				RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
				Screenshot.takeScreenshot(target, image -> {
					Path temp = null;
					try {
						temp = Files.createTempFile("mcctl-screenshot-", ".png");
						image.writeToFile(temp);
						result.complete(Files.readAllBytes(temp));
					} catch (Throwable t) {
						result.completeExceptionally(t);
					} finally {
						try {
							image.close();
						} catch (Throwable ignored) {
							// nothing we can do about it
						}
						if (temp != null) {
							try {
								Files.deleteIfExists(temp);
							} catch (IOException ignored) {
								// leave the temp file behind rather than failing the request
							}
						}
					}
				});
			} catch (Throwable t) {
				result.completeExceptionally(t);
			}
		});

		try {
			return result.get(15, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IOException("screenshot timed out (is the game window rendering?)", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw cause instanceof Exception exception ? exception : new IOException(cause);
		}
	}

	// ------------------------------------------------------------------ state

	@Override
	public void releaseAll() {
		onClientThread(() -> {
			KeyMapping.releaseAll();
			Minecraft minecraft = mc();
			if (minecraft != null && minecraft.options != null) {
				minecraft.options.keyAttack.setDown(false);
				minecraft.options.keyUse.setDown(false);
				minecraft.options.keyPickItem.setDown(false);
			}
		});
	}

	@Override
	public boolean isReady() {
		// The game is running: menus can be driven as well, so input is deliverable even
		// before a world is loaded.
		return mc() != null;
	}

	@Override
	public boolean inWorld() {
		Minecraft minecraft = mc();
		return minecraft != null && minecraft.player != null && minecraft.level != null;
	}

	@Override
	public String unavailableReason() {
		return mc() == null ? "Minecraft client is not running" : "unknown";
	}
}
