// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.mojang.blaze3d.platform.InputConstants;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Headless check for the key-name layer: every canonical name in {@code Keys} must resolve through
 * {@code InputConstants.getKey}, and the three semantic mouse buttons must resolve too.
 *
 * <p>Minecraft 26.3 stores SDL scancodes in those keys; this test only cares that every name
 * resolves. It needs the mod's compiled classes plus the Minecraft client jar, but never starts
 * the game.</p>
 *
 * <pre>
 * javac -cp build/classes/java/main:&lt;minecraft jar&gt; -d /tmp/keycheck tools/KeyMapCheck.java
 * java -cp /tmp/keycheck:build/classes/java/main:&lt;full runtime classpath&gt; KeyMapCheck
 * </pre>
 */
public final class KeyMapCheck {
	private KeyMapCheck() {
	}

	public static void main(String[] args) throws Exception {
		Class<?> keys = Class.forName("com.example.mcctl.Keys");
		Field field = keys.getDeclaredField("KEY_ID");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		Map<String, String> keyIds = (Map<String, String>) field.get(null);

		int missing = 0;
		for (Map.Entry<String, String> entry : keyIds.entrySet()) {
			InputConstants.Key key = InputConstants.getKey(entry.getValue());
			if (key == null) {
				missing++;
				System.out.println("MISSING " + entry.getKey() + " -> " + entry.getValue());
			}
		}

		int mouseMissing = 0;
		for (int button = 0; button <= 2; button++) {
			String id = com.example.mcctl.Keys.mouseId(button);
			InputConstants.Key key = id == null ? null : InputConstants.getKey(id);
			if (key == null) {
				mouseMissing++;
				System.out.println("MISSING mouse button " + button + " -> " + id);
			}
		}

		System.out.println("key names: " + (keyIds.size() - missing) + "/" + keyIds.size()
				+ ", mouse buttons: " + (3 - mouseMissing) + "/3");
		System.out.println("samples: W=" + InputConstants.getKey("key.keyboard.w").getValue()
				+ " ESC=" + InputConstants.getKey("key.keyboard.escape").getValue()
				+ " mouse_left=" + InputConstants.getKey("key.mouse.left").getValue());
		if (missing > 0 || mouseMissing > 0) {
			System.out.println("KEY MAP CHECK FAILED");
			System.exit(1);
		}
		System.out.println("KEY MAP CHECK OK");
	}
}
