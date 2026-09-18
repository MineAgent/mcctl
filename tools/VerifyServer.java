// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.mcctl.Action;
import com.example.mcctl.CommandException;
import com.example.mcctl.CommandParser;
import com.example.mcctl.CommandRunner;
import com.example.mcctl.ControlServer;
import com.example.mcctl.InputExecutor;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone harness used to verify the transport + parser layer without launching Minecraft.
 *
 * Compile the Minecraft-free classes together with this file and run it; it exercises the parser,
 * then starts the real HTTP server on 127.0.0.1:3420 with a fake executor that logs the input it
 * would deliver to the game.
 */
public final class VerifyServer {

	static final class FakeExecutor implements InputExecutor {
		final List<String> calls = new ArrayList<>();

		private void add(String s) {
			synchronized (calls) {
				calls.add(s);
			}
			System.out.println("[fake] " + s);
		}

		@Override public void keyDown(String k) { add("keyDown " + k); }
		@Override public void keyUp(String k) { add("keyUp " + k); }
		@Override public void mouseButtonDown(String b) { add("mouseDown " + b); }
		@Override public void mouseButtonUp(String b) { add("mouseUp " + b); }
		@Override public void mouseMove(int dx, int dy) { add("mouseMove " + dx + " " + dy); }
		@Override public void mouseScroll(double a) { add("mouseScroll " + a); }
		@Override public void releaseAll() { add("releaseAll"); }
		@Override public void sendChat(String m) { add("chat " + m); }

		@Override
		public byte[] captureScreenshot() throws Exception {
			// Stand-in for the real framebuffer capture: emit a small valid PNG.
			java.awt.image.BufferedImage image =
					new java.awt.image.BufferedImage(320, 180, java.awt.image.BufferedImage.TYPE_INT_RGB);
			java.awt.Graphics2D g = image.createGraphics();
			g.setColor(new java.awt.Color(0x20, 0x20, 0x30));
			g.fillRect(0, 0, 320, 180);
			g.setColor(java.awt.Color.GREEN);
			g.drawString("mcctl fake screenshot", 20, 90);
			g.dispose();
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", out);
			byte[] png = out.toByteArray();
			add("screenshot " + png.length + " bytes");
			return png;
		}

		@Override public boolean isReady() { return true; }
		@Override public boolean inWorld() { return true; }
		@Override public String unavailableReason() { return "n/a"; }
	}

	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		parserTests();

		FakeExecutor fake = new FakeExecutor();
		CommandRunner runner = new CommandRunner(fake);
		ControlServer server = new ControlServer(runner, fake);
		server.start();
		System.out.println("READY - http://127.0.0.1:3420");
		Thread.sleep(Long.MAX_VALUE);
	}

	private static void parserTests() {
		expect("W 100", "W 100");
		expect("W+Ctrl 100", "W+LCTRL 100");
		expect("w+ctrl 100", "W+LCTRL 100");
		expect("mouse left", "mouse left 50");
		expect("mouse left 500", "mouse left 500");
		expect("mouse mid", "mouse mid 50");
		expect("mouse right 10", "mouse right 10");
		expect("mouse move +30 -80", "mouse move +30 -80");
		expect("mouse move -5 5", "mouse move -5 +5");
		expect("mouse scroll 3", "mouse scroll 3");
		expect("delay 80 W 50", "delay 80 W 50");
		expect("delay 10 delay 20 A 5", "delay 30 A 5");
		expect("1 50", "1 50");
		expect("esc", "ESC 50");
		expect("F3", "F3 50");
		expect("shift+e 20", "LSHIFT+E 20");
		expect("release", "release");
		expect("Q 100\n mouse left ; mouse move +1 -1", "Q 100", "mouse left 50", "mouse move +1 -1");
		expect("// comment\nD 200", "D 200");
		expect("bt help", "bt help");
		expect("bt goal ~ ~ ~20", "bt goal ~ ~ ~20");
		expect("baritone stop", "bt stop");
		expect("bt #help", "bt help");
		expect("#goal ~ ~ ~20", "bt goal ~ ~ ~20");
		expect("delay 80 bt help", "delay 80 bt help");
		expect("chat hello world", "chat hello world");
		expect("bt goal ~ ~ ~20 ; bt stop", "bt goal ~ ~ ~20", "bt stop");

		reject("");
		reject("XYZ");
		reject("mouse banana");
		reject("mouse move 5");
		reject("delay 80");
		reject("W 100 abc");
		reject("W -5");
		reject("bt");
		reject("bt   ");
		reject("#");

		System.out.println(failures == 0 ? "PARSER TESTS: all passed" : "PARSER TESTS: " + failures + " FAILED");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static void expect(String body, String... expected) {
		try {
			List<Action> actions = CommandParser.parse(body);
			List<String> actual = new ArrayList<>();
			for (Action a : actions) {
				actual.add(a.describe());
			}
			if (!actual.equals(List.of(expected))) {
				failures++;
				System.out.println("FAIL " + quote(body) + " -> " + actual + " (expected " + List.of(expected) + ")");
			} else {
				System.out.println("ok   " + quote(body) + " -> " + actual);
			}
		} catch (CommandException e) {
			failures++;
			System.out.println("FAIL " + quote(body) + " -> unexpected error: " + e.getMessage());
		}
	}

	private static void reject(String body) {
		try {
			CommandParser.parse(body);
			failures++;
			System.out.println("FAIL " + quote(body) + " -> expected an error");
		} catch (CommandException e) {
			System.out.println("ok   " + quote(body) + " -> rejected: " + e.getMessage());
		}
	}

	private static String quote(String s) {
		return "\"" + s.replace("\n", "\\n") + "\"";
	}
}
