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

	/**
	 * Moves the mouse cursor to an absolute window-pixel position.
	 *
	 * <p>Window pixels are the space {@code GET /mouse} reports and {@code /prtsc} screenshots use.
	 * It only does something while a screen is open: in the world the cursor is grabbed by the game
	 * (use {@link #mouseMove} there).</p>
	 */
	void mouseGoto(int x, int y);

	/** Wheel scroll; positive = up. */
	void mouseScroll(double amount);

	/** Releases every key and mouse button. */
	void releaseAll();

	/**
	 * Sends a chat message exactly like the chat box would.
	 *
	 * <p>Messages starting with {@code /} are sent as a command; everything else goes out as a
	 * normal chat message. Baritone commands do <em>not</em> come through here, they use
	 * {@link #sendBaritone(String)}.</p>
	 */
	void sendChat(String message);

	/**
	 * Runs a Baritone command through Baritone's API (the same path {@code #<command>} takes in the
	 * chat box, minus the chat packet).
	 *
	 * <p>Only call this after {@link #baritoneUnavailableReason()} returned {@code null}: the mod
	 * must not silently fall back to a chat message when Baritone is missing.</p>
	 *
	 * @param command the Baritone command without its leading {@code #} (e.g. {@code goal ~ ~ ~20})
	 */
	void sendBaritone(String command);

	/**
	 * Reports whether Baritone commands can run right now.
	 *
	 * @return {@code null} when Baritone is installed and its command manager is reachable,
	 *         otherwise a human readable reason why {@code bt} / {@code #} commands cannot run
	 */
	String baritoneUnavailableReason();

	/**
	 * Types text into the text box of the screen that is open right now.
	 *
	 * <p>The text box is looked up when this runs — no state is remembered between requests. Only a
	 * focused {@code EditBox} qualifies (the chat box does); anvil naming, signs and books are not
	 * supported on purpose.</p>
	 *
	 * @return {@code null} when the text was typed, otherwise a human readable reason (no text box)
	 */
	String typeText(String text);

	/**
	 * Presses ENTER in the text box of the screen that is open right now (sends the chat message).
	 *
	 * @return {@code null} on success, otherwise a human readable reason (no text box)
	 */
	String typeEnter();

	/**
	 * Captures the current game frame.
	 *
	 * @return the frame encoded as PNG
	 * @throws Exception when the capture failed or timed out
	 */
	byte[] captureScreenshot() throws Exception;

	/**
	 * Current mouse cursor state as plain text (the body of {@code GET /mouse}).
	 *
	 * @return one {@code "<field>：<value>"} line per field; never {@code null}
	 * @throws IllegalStateException when the game is not running or the client thread did not answer
	 */
	String mousePosition();

	/** @return true when the game window is up and input can be delivered (also at menus) */
	boolean isReady();

	/** @return true when a world is loaded (false at the title screen / loading screens) */
	boolean inWorld();

	/** @return a human readable reason why {@link #isReady()} is false */
	String unavailableReason();
}
