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
	 * Player snapshot as plain technical text, one field per line and
	 * {@code <namespace:id> <count>} lines inside the item sections.
	 *
	 * <pre>
	 * 玩家：DSH
	 * 维度：minecraft:overworld
	 * 坐标：-219.53 105.00 112.31
	 * 方块：-220 105 112
	 * 方位：north
	 * yaw：-135.2
	 * pitch：12.4
	 * 选中：1
	 * 背包：
	 * minecraft:stone 64
	 * 副手：
	 * minecraft:torch 3
	 * 盔甲：
	 * minecraft:diamond_helmet 1
	 * </pre>
	 *
	 * <p>The {@code 副手：} and {@code 盔甲：} sections are only present when those slots hold
	 * something; an empty armor slot is skipped.</p>
	 *
	 * @return the body, or {@code null} when no world/player is loaded
	 */
	String playerInfo();

	/** @return true when the game window is up and input can be delivered (also at menus) */
	boolean isReady();

	/** @return true when a world is loaded (false at the title screen / loading screens) */
	boolean inWorld();

	/** @return a human readable reason why {@link #isReady()} is false */
	String unavailableReason();
}
