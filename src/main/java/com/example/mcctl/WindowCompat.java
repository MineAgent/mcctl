// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.mojang.blaze3d.platform.Window;

import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Window-backend bridge for the cursor operations mcctl needs.
 *
 * <p>Minecraft 26.3 uses the SDL window backend ({@code org.lwjgl.sdl}). The SDL classes are only
 * available at runtime (they are not a compile dependency of this mod), so this class never links
 * them directly: it looks the methods up with {@link Class#forName} and caches them. A missing
 * backend only disables cursor warping; it never fails class loading or the rest of mcctl.</p>
 *
 * <p>On Wayland {@code SDL_WarpMouseInWindow} uses {@code wp_pointer_warp_v1} (or pointer
 * confinement) and only works while the window has pointer focus. mcctl therefore treats it as a
 * best effort: the logical cursor in {@link CursorTracker} keeps reads and synthetic GUI events
 * identical when a compositor cannot or will not move the physical pointer.</p>
 */
public final class WindowCompat {
	private static final Logger LOG = LogManager.getLogger("mcctl");

	private static final Method WARP;
	private static final boolean WAYLAND;

	static {
		Method warp = null;
		boolean wayland = false;

		try {
			Class<?> sdlMouse = Class.forName("org.lwjgl.sdl.SDLMouse");
			warp = sdlMouse.getMethod("SDL_WarpMouseInWindow", long.class, float.class, float.class);
			try {
				Class<?> sdlVideo = Class.forName("org.lwjgl.sdl.SDLVideo");
				Method driver = sdlVideo.getMethod("SDL_GetCurrentVideoDriver");
				String name = (String) driver.invoke(null);
				wayland = name != null && name.toLowerCase(Locale.ROOT).contains("wayland");
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Assume a non-Wayland backend if the driver cannot be queried.
			}
		} catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
			LOG.warn("SDL mouse backend unavailable; cursor warping/hover detection disabled", e);
		}

		WARP = warp;
		WAYLAND = wayland;
	}

	private WindowCompat() {
	}

	/** @return true when the game is running on SDL's native Wayland driver */
	public static boolean isWayland() {
		return WAYLAND;
	}

	/**
	 * Moves the OS cursor to window-pixel {@code (x, y)}. Best effort: on Wayland this only works
	 * while the window has pointer focus. No-op when SDL is unavailable.
	 */
	public static void warpCursor(Window window, int x, int y) {
		if (window == null || WARP == null) {
			return;
		}
		try {
			WARP.invoke(null, window.handle(), (float) x, (float) y);
		} catch (ReflectiveOperationException | RuntimeException e) {
			LOG.debug("could not move the cursor", e);
		}
	}
}
