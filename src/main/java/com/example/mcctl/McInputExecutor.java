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

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
	private static final Logger LOG = LogManager.getLogger("mcctl");

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

	/** mcctl's own cursor position, in window pixels (see {@link CursorTracker}). */
	private static final CursorTracker CURSOR = new CursorTracker();

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
			LOG.debug("input dropped (game shutting down?)", e);
		}
	}

	private static MouseButtonEvent mouseEvent(int mouseCode) {
		Minecraft minecraft = mc();
		double x = 0.0;
		double y = 0.0;
		if (minecraft != null && minecraft.getWindow() != null) {
			Window window = minecraft.getWindow();
			x = CURSOR.scaledX(window);
			y = CURSOR.scaledY(window);
		}
		return new MouseButtonEvent(x, y, new MouseButtonInfo(mouseCode, 0));
	}

	/**
	 * Builds the synthetic key event screens expect.
	 *
	 * <p>26.3 splits a {@code KeyEvent} into the scancode ({@code key()}, what {@code KeyMapping}
	 * matches) and the layout key code ({@code shortcutKey()}, what {@code Screen} and
	 * {@code EditBox} compare backspace/delete/arrow/tab against); both components are needed.</p>
	 */
	private static KeyEvent keyEvent(String canonicalKey) {
		return new KeyEvent(GameKeys.code(canonicalKey), GameKeys.keycode(canonicalKey), 0);
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
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = GameKeys.key(canonicalKey);

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
				if (Keys.isChatAction(canonicalKey)) {
					screen.keyPressed(keyEvent(Keys.ESC));
					screen = minecraft.gui.screen();
				} else if (Keys.isTextEditing(canonicalKey) || Keys.ESC.equals(canonicalKey)) {
					screen.keyPressed(keyEvent(canonicalKey));
					return;
				} else {
					screen = null;
				}
			}

			if (screen != null) {
				screen.keyPressed(keyEvent(canonicalKey));
				return;
			}
			if (Keys.ESC.equals(canonicalKey)) {
				minecraft.pauseGame(false);
				return;
			}

			KeyMapping.click(key);
			KeyMapping.set(key, true);

			if (Keys.F3.equals(canonicalKey)) {
				// F3 alone toggles the debug overlay; that logic lives in KeyboardHandler.
				minecraft.debugEntries.toggleDebugOverlay();
			}
		});
	}

	@Override
	public void keyUp(String canonicalKey) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = GameKeys.key(canonicalKey);
			KeyMapping.set(key, false);

			Screen screen = minecraft.gui.screen();
			if (screen == null) {
				return;
			}
			if (chatFocused(minecraft) && !Keys.isTextEditing(canonicalKey) && !Keys.ESC.equals(canonicalKey)) {
				// The press went to the game (movement keys and friends), so the release must too.
				return;
			}
			screen.keyReleased(keyEvent(canonicalKey));
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

			screen.keyPressed(keyEvent(Keys.ENTER));
			return null;
		}, TYPE_TIMEOUT_MS);
	}

	// ----------------------------------------------------------- mouse buttons

	@Override
	public void mouseButtonDown(String canonicalButton) {
		int button = Keys.mouseButton(canonicalButton);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = GameKeys.mouse(button);
			int mouseCode = key.getValue();
			Screen screen = minecraft.gui.screen();
			if (screen != null && !chatFocused(minecraft)) {
				CURSOR.update(minecraft);
				screen.mouseClicked(mouseEvent(mouseCode), false);
				return;
			}
			// The window usually does not have focus while the game is driven from a terminal, so
			// the game may not consider the mouse grabbed. Mark the hold so MinecraftMixin can keep
			// Minecraft#continueAttack running (survival block breaking needs it).
			if (button == 0) {
				SyntheticInput.setAttackHeld(true);
			}
			KeyMapping.click(key);
			KeyMapping.set(key, true);
		});
	}

	@Override
	public void mouseButtonUp(String canonicalButton) {
		int button = Keys.mouseButton(canonicalButton);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			InputConstants.Key key = GameKeys.mouse(button);
			int mouseCode = key.getValue();
			if (button == 0) {
				// Cleared even when the release is forwarded to a screen: the hold is over either way.
				SyntheticInput.setAttackHeld(false);
			}
			KeyMapping.set(key, false);

			Screen screen = minecraft.gui.screen();
			if (screen != null && !chatFocused(minecraft)) {
				CURSOR.update(minecraft);
				screen.mouseReleased(mouseEvent(mouseCode));
			}
		});
	}

	// ------------------------------------------------------------ mouse motion

	@Override
	public void mouseMove(int dx, int dy) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();

			if (screen != null && !chatFocused(minecraft) && minecraft.getWindow() != null
					&& minecraft.mouseHandler != null && !minecraft.mouseHandler.isMouseGrabbed()) {
				// GUI: move the logical cursor and keep the game's tracked position in step. The
				// window backend only reports a warped position back on the next event poll - and
				// under Xwayland a warp often produces no callback at all - so without the sync the
				// deltas would not accumulate and a following click would use the old spot.
				Window window = minecraft.getWindow();
				MouseHandler mouse = minecraft.mouseHandler;
				CURSOR.update(minecraft);
				int px = clamp((int) Math.round(CURSOR.x()) + dx, 0, Math.max(window.getScreenWidth() - 1, 0));
				int py = clamp((int) Math.round(CURSOR.y()) + dy, 0, Math.max(window.getScreenHeight() - 1, 0));
				CURSOR.set(window, px, py);
				WindowCompat.warpCursor(window, px, py);
				syncCursorPos(mouse, px, py);
				CURSOR.dispatchMouseMoved(screen, window);
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
	 * <p>Doing nothing while no screen is open is deliberate: in the world the window backend
	 * disables the cursor and parks it at the window centre, so there is no free cursor to move
	 * (turn the view with {@code mouse move} instead). This is decided by the open screen, not by
	 * the game's grabbed state, because that state also goes false when the window loses focus -
	 * the world would otherwise behave differently depending on focus.</p>
	 */
	@Override
	public void mouseGoto(int x, int y) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			if (minecraft == null || minecraft.mouseHandler == null || minecraft.getWindow() == null) {
				return;
			}
			MouseHandler mouse = minecraft.mouseHandler;
			if (minecraft.gui.screen() == null) {
				LOG.debug("mouse goto ignored: no screen open, the world has no free cursor");
				return;
			}

			Window window = minecraft.getWindow();
			int px = clamp(x, 0, Math.max(window.getScreenWidth() - 1, 0));
			int py = clamp(y, 0, Math.max(window.getScreenHeight() - 1, 0));
			CURSOR.update(minecraft);
			CURSOR.set(window, px, py);
			WindowCompat.warpCursor(window, px, py);
			// The backend only reports the new position back through the cursor callback, which
			// runs on the next event poll, so a click in the same request would still use the old
			// spot. Mirror MouseHandler#releaseMouse (it patches xpos/ypos right after the warp).
			syncCursorPos(mouse, px, py);
			CURSOR.dispatchMouseMoved(minecraft.gui.screen(), window);
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
		CURSOR.update(minecraft);

		return new StringBuilder()
				.append("光标：").append(decimal(CURSOR.x())).append(' ').append(decimal(CURSOR.y())).append('\n')
				.append("缩放：").append(decimal(CURSOR.scaledX(window))).append(' ')
				.append(decimal(CURSOR.scaledY(window))).append('\n')
				.append("窗口：").append(window.getScreenWidth()).append('x').append(window.getScreenHeight()).append('\n')
				.append("GUI：").append(window.getGuiScaledWidth()).append('x')
				.append(window.getGuiScaledHeight()).append('\n')
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
			// Not fatal: the logical cursor still moved, so mcctl's own reads and synthetic events
			// are correct; only the game's internal hover state may lag behind.
			LOG.debug("could not sync the tracked cursor position", e);
		}
	}

	@Override
	public void mouseScroll(double amount) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();
			if (screen != null && !chatFocused(minecraft) && minecraft.getWindow() != null) {
				CURSOR.update(minecraft);
				Window window = minecraft.getWindow();
				screen.mouseScrolled(CURSOR.scaledX(window), CURSOR.scaledY(window), 0.0, amount);
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
				LOG.debug("mouse wheel is unavailable in this Minecraft build", e);
			}
		});
	}

	// ------------------------------------------------------------------- chat

	/** Sends plain chat, or a {@code /} command. */
	@Override
	public void sendChat(String message) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			ClientPacketListener connection = minecraft.getConnection();
			if (connection == null) {
				LOG.warn("cannot send chat message, not in a world: " + message);
				return;
			}
			if (message.startsWith("/")) {
				connection.sendCommand(message.substring(1));
			} else {
				connection.sendChat(message);
			}
		});
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
			SyntheticInput.setAttackHeld(false);
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
