// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Smoke test: loads the mod's client entrypoint inside a plain JVM that has the *real* Minecraft
 * 26.2 runtime classpath plus MGHttpdProvider's jar (but no game). Verifies: entrypoint
 * instantiation, registration on the shared server, port binding, jdk.httpserver availability,
 * the provider's GET / index, mcctl's GET /ctl/ manual, and the /ctl/prtsc / POST routing.
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

		Response index = request("GET", "/", null);
		System.out.println("GET /         status=" + index.status + " type=" + index.type
				+ " bytes=" + index.body.length()
				+ " hasCtl=" + index.body.contains("/ctl"));

		Response manual = request("GET", "/ctl/", null);
		System.out.println("GET /ctl/     status=" + manual.status + " type=" + manual.type
				+ " bytes=" + manual.body.length()
				+ " title=" + manual.body.lines().findFirst().orElse(""));

		Response post = request("POST", "/ctl/", "W 100");
		System.out.println("POST /ctl/    status=" + post.status + " body=" + post.body.strip());

		Response shot = request("GET", "/ctl/prtsc", null);
		System.out.println("GET /ctl/prtsc status=" + shot.status + " type=" + shot.type
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
