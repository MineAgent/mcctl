// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keyboard key names (GLFW key codes) and mouse buttons.
 *
 * <p>This class is deliberately free of any Minecraft dependency so the parser can be tested
 * outside of the game.</p>
 */
public final class Keys {
	/** GLFW_KEY_ESCAPE */
	public static final int ESC = 256;
	/** GLFW_KEY_F3 */
	public static final int F3 = 292;
	/** GLFW_KEY_ENTER */
	public static final int ENTER = 257;
	/** GLFW_KEY_TAB */
	public static final int TAB = 258;
	/** GLFW_KEY_BACKSPACE */
	public static final int BACKSPACE = 259;
	/** GLFW_KEY_DELETE */
	public static final int DELETE = 261;
	/** GLFW_KEY_RIGHT */
	public static final int RIGHT = 262;
	/** GLFW_KEY_LEFT */
	public static final int LEFT = 263;
	/** GLFW_KEY_DOWN */
	public static final int DOWN = 264;
	/** GLFW_KEY_UP */
	public static final int UP = 265;
	/** GLFW_KEY_HOME */
	public static final int HOME = 268;
	/** GLFW_KEY_END */
	public static final int END = 269;
	/** GLFW_KEY_KP_ENTER */
	public static final int KP_ENTER = 335;
	/** GLFW_KEY_E */
	public static final int E = 69;
	/** GLFW_KEY_Q */
	public static final int Q = 81;
	/** GLFW_KEY_1 (hotbar slot 1) */
	public static final int DIGIT_1 = 49;
	/** GLFW_KEY_9 (hotbar slot 9) */
	public static final int DIGIT_9 = 57;

	/** canonical name -> GLFW key code */
	private static final Map<String, Integer> CANON = new LinkedHashMap<>();
	/** accepted alias -> canonical name */
	private static final Map<String, String> ALIAS = new HashMap<>();

	static {
		for (char c = 'A'; c <= 'Z'; c++) {
			CANON.put(String.valueOf(c), 65 + (c - 'A'));
		}
		for (char c = '0'; c <= '9'; c++) {
			CANON.put(String.valueOf(c), 48 + (c - '0'));
		}
		for (int i = 1; i <= 12; i++) {
			CANON.put("F" + i, 289 + i); // F1 = 290
		}
		for (int i = 0; i <= 9; i++) {
			CANON.put("KP_" + i, 320 + i);
		}

		CANON.put("SPACE", 32);
		CANON.put("APOSTROPHE", 39);
		CANON.put("COMMA", 44);
		CANON.put("MINUS", 45);
		CANON.put("PERIOD", 46);
		CANON.put("SLASH", 47);
		CANON.put("SEMICOLON", 59);
		CANON.put("EQUAL", 61);
		CANON.put("LBRACKET", 91);
		CANON.put("BACKSLASH", 92);
		CANON.put("RBRACKET", 93);
		CANON.put("GRAVE", 96);
		CANON.put("ESC", ESC);
		CANON.put("ENTER", 257);
		CANON.put("TAB", 258);
		CANON.put("BACKSPACE", 259);
		CANON.put("INSERT", 260);
		CANON.put("DELETE", 261);
		CANON.put("RIGHT", 262);
		CANON.put("LEFT", 263);
		CANON.put("DOWN", 264);
		CANON.put("UP", 265);
		CANON.put("PAGEUP", 266);
		CANON.put("PAGEDOWN", 267);
		CANON.put("HOME", 268);
		CANON.put("END", 269);
		CANON.put("CAPSLOCK", 280);
		CANON.put("SCROLLLOCK", 281);
		CANON.put("NUMLOCK", 282);
		CANON.put("PRINTSCREEN", 283);
		CANON.put("PAUSE", 284);
		CANON.put("KP_DECIMAL", 330);
		CANON.put("KP_DIVIDE", 331);
		CANON.put("KP_MULTIPLY", 332);
		CANON.put("KP_SUBTRACT", 333);
		CANON.put("KP_ADD", 334);
		CANON.put("KP_ENTER", 335);
		CANON.put("KP_EQUAL", 336);
		CANON.put("LSHIFT", 340);
		CANON.put("LCTRL", 341);
		CANON.put("LALT", 342);
		CANON.put("LWIN", 343);
		CANON.put("RSHIFT", 344);
		CANON.put("RCTRL", 345);
		CANON.put("RALT", 346);
		CANON.put("RWIN", 347);

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
		return CANON.containsKey(name) ? name : null;
	}

	/** @return the GLFW key code of a canonical key name */
	public static int code(String canonicalName) {
		Integer code = CANON.get(canonicalName);
		if (code == null) {
			throw new IllegalArgumentException("unknown key: " + canonicalName);
		}
		return code;
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
	public static boolean isChatAction(int code) {
		return code == E || code == Q || (code >= DIGIT_1 && code <= DIGIT_9);
	}

	/**
	 * Keys that belong to the text box itself: editing, submitting or completing. While the chat box
	 * is open they keep being forwarded to it instead of the game.
	 */
	public static boolean isTextEditing(int code) {
		return code == ENTER || code == KP_ENTER || code == TAB || code == BACKSPACE || code == DELETE
				|| code == LEFT || code == RIGHT || code == UP || code == DOWN || code == HOME || code == END;
	}

	/** Short summary of the most useful key names (used in error messages / help). */
	public static String summary() {
		return "A-Z, 0-9, F1-F12, SPACE, TAB, ENTER, BACKSPACE, SHIFT, CTRL, ALT, ESC, "
				+ "UP, DOWN, LEFT, RIGHT, PAGEUP, PAGEDOWN, HOME, END, INSERT, DELETE, "
				+ "MINUS, EQUAL, COMMA, PERIOD, SLASH, SEMICOLON, APOSTROPHE, LBRACKET, "
				+ "RBRACKET, BACKSLASH, GRAVE, KP_0-KP_9";
	}
}
