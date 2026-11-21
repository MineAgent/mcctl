// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;

/**
 * mcctl's logical cursor, in window pixels.
 *
 * <p>{@code MouseHandler#xpos/ypos} is normally the cursor position, but on native Wayland it can
 * briefly hold values that are not window coordinates (SDL cannot always provide a global cursor
 * position when the window has no pointer focus). The mod therefore owns a clamp-safe copy for its
 * own reads and synthetic GUI events, seeded from the game whenever the game's value is plausible.</p>
 *
 * <p>Client (render) thread only: every method reads or writes the live mouse state.</p>
 */
final class CursorTracker {
	private double x;
	private double y;
	private boolean valid;
	private boolean wasGrabbed;

	/**
	 * Refreshes the logical cursor from the current game state.
	 *
	 * <p>While the mouse is grabbed the cursor is parked at the window centre (what vanilla does).
	 * When a screen has just been released, vanilla has also parked it at the centre, so start from
	 * the game's value if it is a real window position. Elsewhere in a GUI the game's value is
	 * adopted when it is inside the window, which keeps physical mouse movement visible in
	 * {@code GET /ctl/mouse}; an out-of-window value is ignored instead of poisoning the cursor.</p>
	 */
	void update(Minecraft minecraft) {
		if (minecraft == null) {
			return;
		}
		Window window = minecraft.getWindow();
		MouseHandler mouse = minecraft.mouseHandler;
		if (window == null || mouse == null) {
			return;
		}

		boolean grabbed = mouse.isMouseGrabbed();
		double centerX = window.getScreenWidth() / 2.0;
		double centerY = window.getScreenHeight() / 2.0;

		if (grabbed) {
			x = centerX;
			y = centerY;
			valid = true;
		} else if (!valid || wasGrabbed) {
			double gameX = mouse.xpos();
			double gameY = mouse.ypos();
			if (inside(gameX, gameY, window)) {
				x = gameX;
				y = gameY;
			} else {
				x = centerX;
				y = centerY;
			}
			valid = true;
		} else {
			double gameX = mouse.xpos();
			double gameY = mouse.ypos();
			if (inside(gameX, gameY, window)) {
				x = gameX;
				y = gameY;
			} else {
				clamp(window);
			}
		}
		wasGrabbed = grabbed;
	}

	double x() {
		return x;
	}

	double y() {
		return y;
	}

	/** Moves the logical cursor to {@code (newX, newY)} and clamps it to the window. */
	void set(Window window, double newX, double newY) {
		x = newX;
		y = newY;
		valid = true;
		clamp(window);
	}

	/** @return the cursor x in GUI-scaled coordinates, as {@code Screen} events expect */
	double scaledX(Window window) {
		return MouseHandler.getScaledXPos(window, x);
	}

	/** @return the cursor y in GUI-scaled coordinates, as {@code Screen} events expect */
	double scaledY(Window window) {
		return MouseHandler.getScaledYPos(window, y);
	}

	/**
	 * Tells the open screen that the cursor moved. X11/Xwayland often generate a real motion event
	 * after a warp as well, so this may run twice; screens only recompute hover state, which is
	 * idempotent.
	 */
	void dispatchMouseMoved(Screen screen, Window window) {
		if (screen != null && window != null) {
			screen.mouseMoved(scaledX(window), scaledY(window));
		}
	}

	private void clamp(Window window) {
		if (window == null) {
			return;
		}
		x = Math.max(0.0, Math.min(x, Math.max(window.getScreenWidth() - 1, 0)));
		y = Math.max(0.0, Math.min(y, Math.max(window.getScreenHeight() - 1, 0)));
	}

	private static boolean inside(double x, double y, Window window) {
		return Double.isFinite(x) && Double.isFinite(y)
				&& x >= 0.0 && x < window.getScreenWidth()
				&& y >= 0.0 && y < window.getScreenHeight();
	}
}
