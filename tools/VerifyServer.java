// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.example.httpd.HttpdProvider;
import com.example.mcctl.Action;
import com.example.mcctl.CommandException;
import com.example.mcctl.CommandParser;
import com.example.mcctl.CommandRunner;
import com.example.mcctl.ControlEndpoint;
import com.example.mcctl.InputExecutor;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone harness used to verify the transport + parser layer without launching Minecraft.
 *
 * Compile the Minecraft-free classes together with this file (and MGHttpdProvider's API jar) and
 * run it; it exercises the parser, then mounts mcctl's endpoints at {@code /ctl} on the real
 * 127.0.0.1:3420 server with a fake executor that logs the input it would deliver to the game.
 */
public final class VerifyServer {

	static final class FakeExecutor implements InputExecutor {
		final List<String> calls = new ArrayList<>();

		/** Simulates Baritone being missing: {@code null} = available, otherwise the reason. */
		volatile String baritoneUnavailable = null;

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
		@Override public void mouseGoto(int x, int y) { add("mouseGoto " + x + " " + y); }
		@Override public void mouseScroll(double a) { add("mouseScroll " + a); }
		@Override public void releaseAll() { add("releaseAll"); }
		@Override public void sendChat(String m) { add("chat " + m); }
		@Override public void sendBaritone(String command) { add("baritone " + command); }
		@Override public String baritoneUnavailableReason() { return baritoneUnavailable; }
		@Override public String typeText(String text) { add("typeText " + text); return null; }
		@Override public String typeEnter() { add("typeEnter"); return null; }

		@Override
		public String mousePosition() {
			add("mousePosition");
			return "光标：325.0 123.0\n"
					+ "缩放：162.5 61.5\n"
					+ "窗口：854x480\n"
					+ "GUI：427x240\n"
					+ "抓取：否\n"
					+ "界面：CraftingScreen\n";
		}

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
		HttpdProvider.register(ControlEndpoint.PREFIX, ControlEndpoint.NAME, ControlEndpoint.ENDPOINTS,
				new ControlEndpoint(runner, fake));
		httpTests(fake);
		System.out.println("READY - http://127.0.0.1:3420/ctl");
		Thread.sleep(Long.MAX_VALUE);
	}

	/**
	 * Exercises the Baritone availability rule end to end through the real HTTP handler: {@code bt}
	 * and {@code #} must reach {@link InputExecutor#sendBaritone} when Baritone is available, and
	 * must fail with 400 - without executing anything, and above all without becoming a chat
	 * message - when it is not.
	 */
	private static void httpTests(FakeExecutor fake) throws Exception {
		fake.baritoneUnavailable = null;
		fake.calls.clear();
		Response bt = post("bt goal ~ ~ ~20");
		check("bt available: 200", bt.status == 200, "status=" + bt.status + " body=" + bt.body.strip());
		check("bt available: reached sendBaritone", awaitCall(fake, "baritone goal ~ ~ ~20"),
				"calls=" + fake.calls);
		check("bt available: not sent as chat", !hasCall(fake, "chat #"), "calls=" + fake.calls);

		fake.baritoneUnavailable = null;
		fake.calls.clear();
		Response hash = post("#stop");
		check("# available: 200", hash.status == 200, "status=" + hash.status + " body=" + hash.body.strip());
		check("# available: reached sendBaritone", awaitCall(fake, "baritone stop"), "calls=" + fake.calls);

		fake.baritoneUnavailable = "Baritone is not installed (mod id 'baritone')";
		fake.calls.clear();
		Response missing = post("bt help");
		Thread.sleep(200);
		check("bt unavailable: 400", missing.status == 400,
				"status=" + missing.status + " body=" + missing.body.strip());
		check("bt unavailable: body explains", missing.body.contains("Baritone"),
				"body=" + missing.body.strip());
		check("bt unavailable: nothing executed", fake.calls.isEmpty(), "calls=" + fake.calls);

		fake.calls.clear();
		Response mixed = post("W 100\nbt stop");
		Thread.sleep(200);
		check("bt unavailable, mixed plan: 400", mixed.status == 400, "status=" + mixed.status);
		check("bt unavailable, mixed plan: nothing executed", fake.calls.isEmpty(),
				"calls=" + fake.calls);

		Response hashMissing = post("#goal ~ ~ ~20");
		check("# unavailable: 400", hashMissing.status == 400, "status=" + hashMissing.status);

		fake.baritoneUnavailable = null;
		fake.calls.clear();
		Response chat = post("chat hello");
		check("chat unaffected: 200", chat.status == 200, "status=" + chat.status);
		check("chat unaffected: reached sendChat", awaitCall(fake, "chat hello"), "calls=" + fake.calls);

		System.out.println(failures == 0 ? "HTTP TESTS: all passed" : "HTTP TESTS: " + failures + " FAILED");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private record Response(int status, String body) {
	}

	private static Response post(String body) throws Exception {
		HttpURLConnection connection =
				(HttpURLConnection) new URL("http://127.0.0.1:3420/ctl/").openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		try (OutputStream out = connection.getOutputStream()) {
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		return new Response(status, text);
	}

	private static boolean hasCall(FakeExecutor fake, String call) {
		synchronized (fake.calls) {
			return fake.calls.contains(call);
		}
	}

	/** The command queue is asynchronous, so give the worker a moment to deliver the call. */
	private static boolean awaitCall(FakeExecutor fake, String call) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			if (hasCall(fake, call)) {
				return true;
			}
			Thread.sleep(20);
		}
		return false;
	}

	private static void check(String what, boolean ok, String detail) {
		if (ok) {
			System.out.println("ok   " + what);
		} else {
			failures++;
			System.out.println("FAIL " + what + " -> " + detail);
		}
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
		expect("mouse goto 325 123", "mouse goto 325 123");
		expect("mouse goto 0 0", "mouse goto 0 0");
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
		reject("mouse goto 5");
		reject("mouse goto a b");
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
