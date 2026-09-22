// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny HTTP server bound to {@code 127.0.0.1:3420}.
 *
 * <ul>
 *   <li>{@code GET} returns the manual.</li>
 *   <li>{@code POST} parses the body and queues the commands.</li>
 * </ul>
 */
public final class ControlServer {
	public static final String HOST = "127.0.0.1";
	public static final int PORT = 3420;

	private static final Logger LOG = Logger.getLogger("mcctl");
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final CommandRunner runner;
	private final InputExecutor executor;
	private HttpServer server;
	private ExecutorService httpPool;

	public ControlServer(CommandRunner runner, InputExecutor executor) {
		this.runner = runner;
		this.executor = executor;
	}

	public void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(HOST, PORT), 16);
		server.createContext("/", this::handle);
		httpPool = Executors.newFixedThreadPool(2, r -> {
			Thread t = new Thread(r, "mcctl-http");
			t.setDaemon(true);
			return t;
		});
		server.setExecutor(httpPool);
		server.start();
		LOG.info("mcctl listening on http://" + HOST + ":" + PORT);
	}

	public void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		if (httpPool != null) {
			httpPool.shutdownNow();
			httpPool = null;
		}
	}

	private void handle(HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();

			if (isScreenshotPath(path)) {
				if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed: " + method + "\n");
					return;
				}
				handleScreenshot(exchange);
				return;
			}

			if (isModsPath(path)) {
				if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed: " + method + "\n");
					return;
				}
				handleMods(exchange);
				return;
			}

			switch (method) {
				case "GET", "HEAD" -> respond(exchange, 200, "text/plain; charset=utf-8", Help.text());
				case "POST" -> handlePost(exchange);
				case "OPTIONS" -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 204, "text/plain; charset=utf-8", "");
				}
				default -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8",
							"method not allowed: " + method + " (use GET for help, POST for commands)\n");
				}
			}
		} catch (BodyTooLargeException e) {
			respond(exchange, 413, "text/plain; charset=utf-8", "request body too large\n");
		} catch (Exception e) {
			LOG.log(Level.WARNING, "request failed", e);
			respond(exchange, 500, "text/plain; charset=utf-8", "internal error: " + e + "\n");
		} finally {
			exchange.close();
		}
	}

	private static boolean isScreenshotPath(String path) {
		return "/prtsc".equals(path) || "/prtsc.png".equals(path)
				|| "/screenshot".equals(path) || "/screenshot.png".equals(path);
	}

	private static boolean isModsPath(String path) {
		return "/mods".equals(path) || "/mods.txt".equals(path) || "/modlist".equals(path);
	}

	/** {@code GET /mods}: every loaded mod as {@code "<id> <version> <name>"} lines. */
	private void handleMods(HttpExchange exchange) throws IOException {
		String mods;
		try {
			mods = executor.loadedMods();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "mod list failed", e);
			respond(exchange, 500, "text/plain; charset=utf-8", "mod list failed: " + e + "\n");
			return;
		}
		if (mods == null) {
			mods = "";
		}
		respond(exchange, 200, "text/plain; charset=utf-8", mods);
	}

	/** {@code GET /prtsc}: capture the current frame and hand it back as a PNG. */
	private void handleScreenshot(HttpExchange exchange) throws IOException {
		if (!executor.isReady()) {
			respond(exchange, 409, "text/plain; charset=utf-8",
					"game not ready: " + executor.unavailableReason() + "\n");
			return;
		}

		byte[] png;
		try {
			png = executor.captureScreenshot();
		} catch (Exception e) {
			LOG.log(Level.WARNING, "screenshot failed", e);
			respond(exchange, 500, "text/plain; charset=utf-8", "screenshot failed: " + e + "\n");
			return;
		}

		if (png == null || png.length == 0) {
			respond(exchange, 500, "text/plain; charset=utf-8", "screenshot failed: empty image\n");
			return;
		}

		exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"mcctl-screenshot.png\"");
		respond(exchange, 200, "image/png", png);
	}

	private void handlePost(HttpExchange exchange) throws IOException {
		String body = new String(readBody(exchange), StandardCharsets.UTF_8);

		List<Action> plan;
		try {
			plan = CommandParser.parse(body);
		} catch (CommandException e) {
			respond(exchange, 400, "text/plain; charset=utf-8",
					"bad command: " + e.getMessage() + "\n\n" + Help.text());
			return;
		}

		if (!executor.isReady()) {
			respond(exchange, 409, "text/plain; charset=utf-8",
					"game not ready: " + executor.unavailableReason() + "\n");
			return;
		}

		if (needsTextBox(plan)) {
			// Typing reports an outcome (no text box -> 400), so that request runs synchronously on
			// the usual single worker: the actions keep their order and we wait for the result.
			String failure = runner.submitAndWait(plan, waitBudgetMs(plan));

			if (failure != null) {
				respond(exchange, 400, "text/plain; charset=utf-8", "type failed: " + failure + "\n");
				return;
			}
		} else {
			runner.submit(plan);
		}

		StringBuilder json = new StringBuilder();
		json.append("{\"ok\":true,\"queued\":").append(runner.pending())
				.append(",\"inWorld\":").append(executor.inWorld())
				.append(",\"actions\":[");
		for (int i = 0; i < plan.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append('"').append(escape(plan.get(i).describe())).append('"');
		}
		json.append("]}\n");

		respond(exchange, 200, "application/json; charset=utf-8", json.toString());
	}

	/** True when the plan types into a text box, i.e. it has an outcome worth reporting. */
	private static boolean needsTextBox(List<Action> plan) {
		for (Action action : plan) {
			if (action.kind() == Action.Kind.TYPE_TEXT || action.kind() == Action.Kind.TYPE_ENTER) {
				return true;
			}
		}
		return false;
	}

	/** How long the HTTP thread is willing to wait for a typing request (its own holds + 5 s). */
	private static long waitBudgetMs(List<Action> plan) {
		long budget = 5_000L;
		for (Action action : plan) {
			budget += action.holdMs() + action.delayMs();
		}
		return Math.min(budget, 120_000L);
	}

	private static byte[] readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int total = 0;
			int read;
			while ((read = in.read(buffer)) > 0) {
				total += read;
				if (total > MAX_BODY_BYTES) {
					throw new BodyTooLargeException();
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
		}
	}

	private static void respond(HttpExchange exchange, int status, String contentType, String body)
			throws IOException {
		respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		if ("HEAD".equals(exchange.getRequestMethod()) || body.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private static String escape(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}

	private static final class BodyTooLargeException extends IOException {
		private static final long serialVersionUID = 1L;
	}
}
