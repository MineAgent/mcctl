// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Smoke test: loads the mod's client entrypoint and starts its HTTP server inside a plain JVM that
 * has the *real* Minecraft 26.2 runtime classpath (but no game). Verifies:
 * entrypoint instantiation, port binding, jdk.httpserver availability, GET manual,
 * GET /prtsc routing and POST handling.
 */
public final class LoaderSmokeTest {
	public static void main(String[] args) throws Exception {
		Class<?> mcInput = Class.forName("com.example.mcctl.McInputExecutor");
		Object executor = mcInput.getDeclaredConstructor().newInstance();
		System.out.println("McInputExecutor loaded: " + executor.getClass().getName());

		Class<?> modClass = Class.forName("com.example.mcctl.McCtlClientMod");
		net.fabricmc.api.ClientModInitializer mod =
				(net.fabricmc.api.ClientModInitializer) modClass.getDeclaredConstructor().newInstance();
		mod.onInitializeClient();
		System.out.println("ENTRYPOINT OK");

		Response manual = request("GET", "/", null);
		System.out.println("GET /        status=" + manual.status + " type=" + manual.type
				+ " bytes=" + manual.body.length()
				+ " title=" + manual.body.lines().findFirst().orElse(""));

		Response post = request("POST", "/", "W 100");
		System.out.println("POST /       status=" + post.status + " body=" + post.body.strip());

		Response shot = request("GET", "/prtsc", null);
		System.out.println("GET /prtsc   status=" + shot.status + " type=" + shot.type
				+ " body=" + shot.body.strip());

		System.out.println("SMOKE TEST DONE");
		System.exit(0);
	}

	private record Response(int status, String type, String body) {
	}

	private static Response request(String method, String path, String body) throws Exception {
		HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:3420" + path).openConnection();
		connection.setRequestMethod(method);
		if (body != null) {
			connection.setDoOutput(true);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		return new Response(status, connection.getContentType(), text);
	}
}
