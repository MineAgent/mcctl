// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl.mixin;

import com.example.mcctl.WindowCompat;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps {@code MouseHandler#xpos/ypos} under mcctl's control on native Wayland.
 *
 * <p>{@code resyncMousePosition} derives the cursor from {@code SDL_GetGlobalMouseState} plus
 * {@code SDL_GetWindowPosition}. Wayland has no real global pointer position: SDL fakes it from
 * its own cached window-relative position while the window has pointer focus, and reports
 * {@code 0,0} when it does not, which Minecraft can turn into values like {@code -653,-480}.</p>
 *
 * <p>More importantly, the cached SDL position is only updated by a successful
 * {@code SDL_WarpMouseInWindow}; when a compositor cannot warp (or the window is not focused) it
 * still holds the old position, and {@code resyncMousePosition} would overwrite the position
 * mcctl just synced after {@code mouse goto}. {@code Gui.setScreen} calls this method every time
 * a screen is opened or closed, so that overwrite would make {@code GET /ctl/mouse} depend on the
 * compositor.</p>
 *
 * <p>The position set by {@code grabMouse}/{@code releaseMouse} (window centre) and by real
 * motion events is already correct, so on Wayland the resync is simply skipped. X11 keeps the
 * vanilla implementation.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "resyncMousePosition", at = @At("HEAD"), cancellable = true)
	private void mcctl$keepWaylandCursorPosition(CallbackInfo ci) {
		if (WindowCompat.isWayland()) {
			ci.cancel();
		}
	}
}
