// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

	/** private MouseHandler#onScroll(long, double, double) - used for the mouse wheel. */
	private static Method onScroll;

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
			if (screen != null) {
				screen.keyReleased(new KeyEvent(code, 0, 0));
			}
		});
	}

	// ----------------------------------------------------------- mouse buttons

	@Override
	public void mouseButtonDown(String canonicalButton) {
		int button = Keys.mouseButton(canonicalButton);
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();
			if (screen != null) {
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
			if (screen != null) {
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

			if (screen != null && minecraft.mouseHandler != null && !minecraft.mouseHandler.isMouseGrabbed()) {
				// GUI: move the real cursor, the game picks it up on the next frame.
				long handle = minecraft.getWindow().handle();
				GLFW.glfwSetCursorPos(handle, minecraft.mouseHandler.xpos() + dx,
						minecraft.mouseHandler.ypos() + dy);
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

	@Override
	public void mouseScroll(double amount) {
		onClientThread(() -> {
			Minecraft minecraft = mc();
			Screen screen = minecraft.gui.screen();
			if (screen != null) {
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
