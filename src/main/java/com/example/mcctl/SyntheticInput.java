// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

/**
 * State shared between the mod and its mixins about buttons mcctl is holding down.
 *
 * <p>Minecraft only continues breaking a block while the game considers the mouse to be grabbed
 * ({@code Minecraft#handleKeybinds} passes {@code mouseHandler.isMouseGrabbed()} to
 * {@code continueAttack}). The game grabs the mouse when the window has focus and only releases it
 * when a screen opens, so a client driven from the command line can lose the grab for good: the
 * window has no focus while the terminal does, and nothing in mcctl goes through
 * {@code MouseHandler#onPress} (the only place that grabs the mouse again).</p>
 *
 * <p>{@code MinecraftMixin} therefore treats a synthetic left-button hold as "grabbed" while this
 * flag is set. Vanilla's other conditions (no screen open, {@code keyAttack.isDown()}) still apply,
 * so a stale flag cannot start breaking on its own.</p>
 *
 * <p>Written and read on the client (render) thread only; {@code volatile} is for the benefit of
 * the HTTP worker thread that might observe it during shutdown.</p>
 */
public final class SyntheticInput {
	private static volatile boolean attackHeld;

	private SyntheticInput() {
	}

	/** Called by {@link McInputExecutor} when the synthetic left mouse button goes down/up. */
	public static void setAttackHeld(boolean held) {
		attackHeld = held;
	}

	/** @return true while mcctl is holding the left mouse button down */
	public static boolean isAttackHeld() {
		return attackHeld;
	}
}
