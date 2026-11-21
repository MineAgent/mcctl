// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.mojang.blaze3d.platform.InputConstants;

import java.lang.reflect.Method;

/**
 * Resolves parser-level key names and mouse buttons to the code {@code InputConstants} uses in
 * Minecraft 26.3.
 *
 * <p>Minecraft 26.3 uses SDL scancodes and mouse button numbers starting at 1. Looking the values
 * up by translation key keeps the numeric codes out of this mod, because
 * {@code InputConstants.getKey} is the game's own name-to-code table.</p>
 *
 * <p>26.3 also carries a second, layout-level key code in {@code KeyEvent} (its second component,
 * exposed as {@code shortcutKey()}). Screens and {@code EditBox} compare editing keys against that
 * value (backspace = 8, delete = 127, arrows/home/end = {@code 0x40000000 | ...}, tab = 9), while
 * {@code key()} stays the scancode used by {@code KeyMapping}. {@link #keycode} asks SDL for it.
 * The SDL classes are looked up reflectively because they are not a compile dependency of this
 * mod.</p>
 */
final class GameKeys {
	private static final Method SDL_GET_KEY_FROM_SCANCODE;

	static {
		Method getKeyFromScancode = null;
		try {
			Class<?> sdlKeyboard = Class.forName("org.lwjgl.sdl.SDLKeyboard");
			getKeyFromScancode = sdlKeyboard.getMethod("SDL_GetKeyFromScancode",
					int.class, short.class, boolean.class);
		} catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
			// No SDL on the classpath: there is no layout-level key code to report.
		}
		SDL_GET_KEY_FROM_SCANCODE = getKeyFromScancode;
	}

	private GameKeys() {
	}

	/** @throws IllegalArgumentException when the running Minecraft does not know that key */
	static InputConstants.Key key(String canonical) {
		String id = Keys.keyId(canonical);
		InputConstants.Key key = InputConstants.getKey(id);
		if (key == null) {
			throw new IllegalArgumentException("Minecraft does not know " + id);
		}
		return key;
	}

	/** @return the numeric code of a canonical key name */
	static int code(String canonical) {
		return key(canonical).getValue();
	}

	/**
	 * @return the layout-level key code of a canonical key name, for 26.3's
	 *         {@code KeyEvent.shortcutKey()}; {@code 0} when SDL cannot answer
	 */
	static int keycode(String canonical) {
		if (SDL_GET_KEY_FROM_SCANCODE == null) {
			return 0;
		}
		try {
			return (int) SDL_GET_KEY_FROM_SCANCODE.invoke(null, code(canonical), (short) 0, true);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;
		}
	}

	/** @throws IllegalArgumentException when the semantic button is unknown or not mapped */
	static InputConstants.Key mouse(int semanticButton) {
		String id = Keys.mouseId(semanticButton);
		if (id == null) {
			throw new IllegalArgumentException("unknown mouse button: " + semanticButton);
		}
		InputConstants.Key key = InputConstants.getKey(id);
		if (key == null) {
			throw new IllegalArgumentException("Minecraft does not know " + id);
		}
		return key;
	}
}
