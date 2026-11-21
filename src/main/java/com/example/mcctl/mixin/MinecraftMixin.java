// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl.mixin;

import com.example.mcctl.SyntheticInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps block breaking working when the game window does not have focus.
 *
 * <p>{@code Minecraft#handleKeybinds()} ends with</p>
 *
 * <pre>{@code
 * this.continueAttack(this.gui.screen() == null && !instantAttack
 *         && this.options.keyAttack.isDown() && this.mouseHandler.isMouseGrabbed());
 * }</pre>
 *
 * <p>In survival the break progress is only advanced by {@code continueAttack ->
 * MultiPlayerGameMode#continueDestroyBlock} (the first click only sends
 * {@code START_DESTROY_BLOCK}), and the server waits for the client to send
 * {@code STOP_DESTROY_BLOCK}. So while {@code isMouseGrabbed()} is false, a held left button can
 * never finish a block.</p>
 *
 * <p>The game only grabs the mouse while the window has focus ({@code MouseHandler#grabMouse} checks
 * {@code Minecraft#isWindowActive}), and mcctl drives the game from a terminal, i.e. without focus.
 * A synthetic left button therefore reports itself as grabbed: the other vanilla conditions (no
 * screen open, {@code !instantAttack}, {@code keyAttack.isDown()}) still gate the call, so a stale
 * flag has no effect.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Redirect(method = "handleKeybinds",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
	private boolean mcctl$continueSyntheticAttack(MouseHandler mouseHandler) {
		return mouseHandler.isMouseGrabbed() || SyntheticInput.isAttackHeld();
	}
}
