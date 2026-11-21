// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keyboard key names and mouse buttons.
 *
 * <p>This class is deliberately free of any Minecraft dependency so the parser can be tested
 * outside of the game. It maps a canonical key name to the vanilla translation key
 * ({@code key.keyboard.*}); the actual numeric code is resolved by {@link GameKeys} at runtime,
 * because Minecraft 26.3 uses SDL scancodes. Mouse buttons follow the same rule: the semantic
 * value (0 = left, 1 = right, 2 = middle) is parser-level, the numeric button number is
 * resolved by {@link GameKeys}.</p>
 */
public final class Keys {
	/** Canonical names used by the parser and by the comparison helpers. */
	public static final String ESC = "ESC";
	public static final String F3 = "F3";
	public static final String ENTER = "ENTER";
	public static final String TAB = "TAB";
	public static final String BACKSPACE = "BACKSPACE";
	public static final String DELETE = "DELETE";
	public static final String RIGHT = "RIGHT";
	public static final String LEFT = "LEFT";
	public static final String DOWN = "DOWN";
	public static final String UP = "UP";
	public static final String HOME = "HOME";
	public static final String END = "END";
	public static final String KP_ENTER = "KP_ENTER";
	public static final String E = "E";
	public static final String Q = "Q";

	/** canonical name -> {@code InputConstants.getKey} id */
	private static final Map<String, String> KEY_ID = new LinkedHashMap<>();
	/** accepted alias -> canonical name */
	private static final Map<String, String> ALIAS = new HashMap<>();

	static {
		for (char c = 'A'; c <= 'Z'; c++) {
			KEY_ID.put(String.valueOf(c), "key.keyboard." + Character.toLowerCase(c));
		}
		for (char c = '0'; c <= '9'; c++) {
			KEY_ID.put(String.valueOf(c), "key.keyboard." + c);
		}
		for (int i = 1; i <= 12; i++) {
			KEY_ID.put("F" + i, "key.keyboard.f" + i);
		}
		for (int i = 0; i <= 9; i++) {
			KEY_ID.put("KP_" + i, "key.keyboard.keypad." + i);
		}

		KEY_ID.put("SPACE", "key.keyboard.space");
		KEY_ID.put("APOSTROPHE", "key.keyboard.apostrophe");
		KEY_ID.put("COMMA", "key.keyboard.comma");
		KEY_ID.put("MINUS", "key.keyboard.minus");
		KEY_ID.put("PERIOD", "key.keyboard.period");
		KEY_ID.put("SLASH", "key.keyboard.slash");
		KEY_ID.put("SEMICOLON", "key.keyboard.semicolon");
		KEY_ID.put("EQUAL", "key.keyboard.equal");
		KEY_ID.put("LBRACKET", "key.keyboard.left.bracket");
		KEY_ID.put("BACKSLASH", "key.keyboard.backslash");
		KEY_ID.put("RBRACKET", "key.keyboard.right.bracket");
		KEY_ID.put("GRAVE", "key.keyboard.grave.accent");
		KEY_ID.put(ESC, "key.keyboard.escape");
		KEY_ID.put(ENTER, "key.keyboard.enter");
		KEY_ID.put(TAB, "key.keyboard.tab");
		KEY_ID.put(BACKSPACE, "key.keyboard.backspace");
		KEY_ID.put("INSERT", "key.keyboard.insert");
		KEY_ID.put(DELETE, "key.keyboard.delete");
		KEY_ID.put(RIGHT, "key.keyboard.right");
		KEY_ID.put(LEFT, "key.keyboard.left");
		KEY_ID.put(DOWN, "key.keyboard.down");
		KEY_ID.put(UP, "key.keyboard.up");
		KEY_ID.put("PAGEUP", "key.keyboard.page.up");
		KEY_ID.put("PAGEDOWN", "key.keyboard.page.down");
		KEY_ID.put(HOME, "key.keyboard.home");
		KEY_ID.put(END, "key.keyboard.end");
		KEY_ID.put("CAPSLOCK", "key.keyboard.caps.lock");
		KEY_ID.put("SCROLLLOCK", "key.keyboard.scroll.lock");
		KEY_ID.put("NUMLOCK", "key.keyboard.num.lock");
		KEY_ID.put("PRINTSCREEN", "key.keyboard.print.screen");
		KEY_ID.put("PAUSE", "key.keyboard.pause");
		KEY_ID.put("KP_DECIMAL", "key.keyboard.keypad.decimal");
		KEY_ID.put("KP_DIVIDE", "key.keyboard.keypad.divide");
		KEY_ID.put("KP_MULTIPLY", "key.keyboard.keypad.multiply");
		KEY_ID.put("KP_SUBTRACT", "key.keyboard.keypad.subtract");
		KEY_ID.put("KP_ADD", "key.keyboard.keypad.add");
		KEY_ID.put(KP_ENTER, "key.keyboard.keypad.enter");
		KEY_ID.put("KP_EQUAL", "key.keyboard.keypad.equal");
		KEY_ID.put("LSHIFT", "key.keyboard.left.shift");
		KEY_ID.put("LCTRL", "key.keyboard.left.control");
		KEY_ID.put("LALT", "key.keyboard.left.alt");
		KEY_ID.put("LWIN", "key.keyboard.left.win");
		KEY_ID.put("RSHIFT", "key.keyboard.right.shift");
		KEY_ID.put("RCTRL", "key.keyboard.right.control");
		KEY_ID.put("RALT", "key.keyboard.right.alt");
		KEY_ID.put("RWIN", "key.keyboard.right.win");

		alias("SHIFT", "LSHIFT");
		alias("CTRL", "LCTRL");
		alias("CONTROL", "LCTRL");
		alias("ALT", "LALT");
		alias("META", "LWIN");
		alias("SUPER", "LWIN");
		alias("WIN", "LWIN");
		alias("WINDOWS", "LWIN");
		alias("CMD", "LWIN");
		alias("RETURN", "ENTER");
		alias("ESCAPE", "ESC");
		alias("SPACEBAR", "SPACE");
		alias("DEL", "DELETE");
		alias("INS", "INSERT");
		alias("PGUP", "PAGEUP");
		alias("PGDN", "PAGEDOWN");
		alias("PAGE_UP", "PAGEUP");
		alias("PAGE_DOWN", "PAGEDOWN");
		alias("CAPS", "CAPSLOCK");
		alias("ARROWUP", "UP");
		alias("ARROWDOWN", "DOWN");
		alias("ARROWLEFT", "LEFT");
		alias("ARROWRIGHT", "RIGHT");
		alias("GRAVE_ACCENT", "GRAVE");
		alias("BACKSLASH_KEY", "BACKSLASH");
		alias("'", "APOSTROPHE");
		alias(",", "COMMA");
		alias("-", "MINUS");
		alias(".", "PERIOD");
		alias("/", "SLASH");
		alias(";", "SEMICOLON");
		alias("=", "EQUAL");
		alias("[", "LBRACKET");
		alias("\\", "BACKSLASH");
		alias("]", "RBRACKET");
		alias("`", "GRAVE");
	}

	private Keys() {
	}

	private static void alias(String from, String to) {
		ALIAS.put(from, to);
	}

	/** @return the canonical key name, or {@code null} if the token is not a known key */
	public static String canonical(String token) {
		if (token == null) {
			return null;
		}
		String name = token.trim().toUpperCase(Locale.ROOT);
		if (name.isEmpty()) {
			return null;
		}
		String target = ALIAS.get(name);
		if (target != null) {
			name = target;
		}
		return KEY_ID.containsKey(name) ? name : null;
	}

	/**
	 * @return the vanilla translation key of a canonical key name, for example
	 *         {@code key.keyboard.w} or {@code key.keyboard.escape}
	 */
	public static String keyId(String canonicalName) {
		String id = KEY_ID.get(canonicalName);
		if (id == null) {
			throw new IllegalArgumentException("unknown key: " + canonicalName);
		}
		return id;
	}

	/**
	 * Mouse buttons.
	 *
	 * @return 0 = left, 1 = right, 2 = middle, or -1 when the token is not a mouse button
	 */
	public static int mouseButton(String token) {
		if (token == null) {
			return -1;
		}
		return switch (token.trim().toLowerCase(Locale.ROOT)) {
			case "left", "l", "1", "lmb", "attack" -> 0;
			case "right", "r", "2", "rmb", "use" -> 1;
			case "mid", "middle", "m", "3", "mmb", "wheel" -> 2;
			default -> -1;
		};
	}

	/**
	 * @return the vanilla translation key of a semantic mouse button, or {@code null} for an
	 *         unknown value
	 */
	public static String mouseId(int semanticButton) {
		return switch (semanticButton) {
			case 0 -> "key.mouse.left";
			case 1 -> "key.mouse.right";
			case 2 -> "key.mouse.middle";
			default -> null;
		};
	}

	/** @return the canonical display name of a mouse button */
	public static String mouseButtonName(int button) {
		return switch (button) {
			case 0 -> "left";
			case 1 -> "right";
			case 2 -> "mid";
			default -> "button" + button;
		};
	}

	/**
	 * Keys whose action is driven by {@code Minecraft#handleKeybinds()} through
	 * {@code KeyMapping#consumeClick()} — open inventory, drop item, select a hotbar slot.
	 *
	 * <p>Vanilla skips that code path while any screen is open, so while the chat box is open mcctl
	 * closes it first and then delivers the key through the normal (screen-less) path.</p>
	 */
	public static boolean isChatAction(String canonicalName) {
		if (E.equals(canonicalName) || Q.equals(canonicalName)) {
			return true;
		}
		return canonicalName != null && canonicalName.length() == 1
				&& canonicalName.charAt(0) >= '1' && canonicalName.charAt(0) <= '9';
	}

	/**
	 * Keys that belong to the text box itself: editing, submitting or completing. While the chat box
	 * is open they keep being forwarded to it instead of the game.
	 */
	public static boolean isTextEditing(String canonicalName) {
		return ENTER.equals(canonicalName) || KP_ENTER.equals(canonicalName) || TAB.equals(canonicalName)
				|| BACKSPACE.equals(canonicalName) || DELETE.equals(canonicalName)
				|| LEFT.equals(canonicalName) || RIGHT.equals(canonicalName)
				|| UP.equals(canonicalName) || DOWN.equals(canonicalName)
				|| HOME.equals(canonicalName) || END.equals(canonicalName);
	}

	/** Short summary of the most useful key names (used in error messages / help). */
	public static String summary() {
		return "A-Z, 0-9, F1-F12, SPACE, TAB, ENTER, BACKSPACE, SHIFT, CTRL, ALT, ESC, "
				+ "UP, DOWN, LEFT, RIGHT, PAGEUP, PAGEDOWN, HOME, END, INSERT, DELETE, "
				+ "MINUS, EQUAL, COMMA, PERIOD, SLASH, SEMICOLON, APOSTROPHE, LBRACKET, "
				+ "RBRACKET, BACKSLASH, GRAVE, KP_0-KP_9";
	}
}
