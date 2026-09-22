// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import java.util.List;

/**
 * One executable step produced by {@link CommandParser}.
 *
 * @param kind        what to do
 * @param keys        canonical key names for {@link Kind#KEYS}
 * @param button      canonical mouse button name for {@link Kind#MOUSE_BUTTON}
 * @param message     chat text for {@link Kind#CHAT} (Baritone commands arrive as {@code #...})
 * @param holdMs      how long keys/buttons are held down
 * @param delayMs     how long to sleep before performing this action
 * @param dx          horizontal mouse delta in pixels
 * @param dy          vertical mouse delta in pixels (positive = down, like GLFW)
 * @param amount      scroll amount (positive = wheel up)
 */
public record Action(Kind kind, List<String> keys, String button, String message, long holdMs, long delayMs,
		int dx, int dy, double amount) {

	public enum Kind {
		/** press one or more keys, wait, release them */
		KEYS,
		/** press a mouse button, wait, release it */
		MOUSE_BUTTON,
		/** move the mouse/camera by a pixel delta */
		MOUSE_MOVE,
		/** turn the mouse wheel */
		MOUSE_SCROLL,
		/** send a chat message (Baritone commands start with '#') */
		CHAT,
		/** type text into the focused text box of the open screen */
		TYPE_TEXT,
		/** press ENTER in the focused text box of the open screen */
		TYPE_ENTER,
		/** release every held key/button */
		RELEASE_ALL
	}

	public Action {
		keys = keys == null ? List.of() : List.copyOf(keys);
	}

	public static Action keys(List<String> keys, long holdMs, long delayMs) {
		return new Action(Kind.KEYS, keys, null, null, holdMs, delayMs, 0, 0, 0.0);
	}

	public static Action mouseButton(String button, long holdMs, long delayMs) {
		return new Action(Kind.MOUSE_BUTTON, null, button, null, holdMs, delayMs, 0, 0, 0.0);
	}

	public static Action mouseMove(int dx, int dy, long delayMs) {
		return new Action(Kind.MOUSE_MOVE, null, null, null, 0, delayMs, dx, dy, 0.0);
	}

	public static Action mouseScroll(double amount, long delayMs) {
		return new Action(Kind.MOUSE_SCROLL, null, null, null, 0, delayMs, 0, 0, amount);
	}

	public static Action chat(String message, long delayMs) {
		return new Action(Kind.CHAT, null, null, message, 0, delayMs, 0, 0, 0.0);
	}

	/** One {@code type} chunk; typing happens on the client thread and reports failures back. */
	public static Action typeText(String text, long delayMs) {
		return new Action(Kind.TYPE_TEXT, null, null, text, 0, delayMs, 0, 0, 0.0);
	}

	public static Action typeEnter(long delayMs) {
		return new Action(Kind.TYPE_ENTER, null, null, null, 0, delayMs, 0, 0, 0.0);
	}

	public static Action releaseAll(long delayMs) {
		return new Action(Kind.RELEASE_ALL, null, null, null, 0, delayMs, 0, 0, 0.0);
	}

	private static String signed(int value) {
		return (value >= 0 ? "+" : "") + value;
	}

	/** Canonical textual form, also used in JSON replies. */
	public String describe() {
		String body = switch (kind) {
			case KEYS -> String.join("+", keys) + (holdMs > 0 ? " " + holdMs : "");
			case MOUSE_BUTTON -> "mouse " + button + (holdMs > 0 ? " " + holdMs : "");
			case MOUSE_MOVE -> "mouse move " + signed(dx) + " " + signed(dy);
			case MOUSE_SCROLL -> "mouse scroll " + (amount == Math.rint(amount)
					? String.valueOf((long) amount) : String.valueOf(amount));
			case CHAT -> message != null && message.startsWith("#")
					? "bt " + message.substring(1) : "chat " + message;
			case TYPE_TEXT -> "type " + message;
			case TYPE_ENTER -> "typeEnter";
			case RELEASE_ALL -> "release";
		};
		return delayMs > 0 ? "delay " + delayMs + " " + body : body;
	}

	@Override
	public String toString() {
		return describe();
	}
}
