// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

/**
 * Executes input on the game. Implementations are responsible for hopping onto the
 * client/render thread; the caller may be any thread.
 */
public interface InputExecutor {
	void keyDown(String canonicalKey);

	void keyUp(String canonicalKey);

	void mouseButtonDown(String canonicalButton);

	void mouseButtonUp(String canonicalButton);

	/** Relative mouse movement in pixels (positive dy = down). */
	void mouseMove(int dx, int dy);

	/** Wheel scroll; positive = up. */
	void mouseScroll(double amount);

	/** Releases every key and mouse button. */
	void releaseAll();

	/**
	 * Sends a chat message exactly like the chat box would.
	 *
	 * <p>Messages starting with {@code #} are Baritone commands ({@code #help}, {@code #goal ~ ~ ~20});
	 * when Baritone is installed they are handed to its API directly, otherwise they are sent as a
	 * normal chat message (Baritone's own chat hook then picks them up). Messages starting with
	 * {@code /} are sent as a command.</p>
	 */
	void sendChat(String message);

	/**
	 * Captures the current game frame.
	 *
	 * @return the frame encoded as PNG
	 * @throws Exception when the capture failed or timed out
	 */
	byte[] captureScreenshot() throws Exception;

	/**
	 * All mods currently loaded by Fabric Loader.
	 *
	 * @return one {@code "<mod id> <version> <name>"} line per mod, sorted by mod id
	 */
	String loadedMods();

	/** @return true when the game window is up and input can be delivered (also at menus) */
	boolean isReady();

	/** @return true when a world is loaded (false at the title screen / loading screens) */
	boolean inWorld();

	/** @return a human readable reason why {@link #isReady()} is false */
	String unavailableReason();
}
