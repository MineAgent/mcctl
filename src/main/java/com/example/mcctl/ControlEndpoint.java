// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.example.httpd.HttpdProvider;
import com.example.httpd.PathHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * mcctl's endpoints, mounted under the shared server's {@code /ctl} prefix.
 *
 * <ul>
 *   <li>{@code GET /ctl/} returns the manual.</li>
 *   <li>{@code POST /ctl/} parses the body and queues the commands.</li>
 *   <li>{@code GET /ctl/prtsc} returns the current frame as PNG.</li>
 *   <li>{@code GET /ctl/mouse} returns the cursor position.</li>
 * </ul>
 *
 * <p>The HTTP server itself (127.0.0.1:3420) belongs to MGHttpdProvider; this class only serves
 * the paths it is handed below the prefix, so {@code /prtsc} here means {@code /ctl/prtsc}.</p>
 */
public final class ControlEndpoint implements PathHandler {
	public static final String PREFIX = "/ctl";
	public static final String NAME = "mcctl — 客户端远程控制 (按键/鼠标/视角/截图/Baritone)";

	/** What the provider's {@code GET /} index lists for this mod. */
	public static final List<HttpdProvider.Endpoint> ENDPOINTS = List.of(
			new HttpdProvider.Endpoint("GET", "/ctl/", "使用说明"),
			new HttpdProvider.Endpoint("POST", "/ctl/", "执行命令 (text/plain, UTF-8)"),
			new HttpdProvider.Endpoint("GET", "/ctl/prtsc", "当前帧 PNG (别名 /ctl/screenshot)"),
			new HttpdProvider.Endpoint("GET", "/ctl/mouse", "当前光标位置 (窗口像素 + GUI 缩放)"));

	private static final Logger LOG = Logger.getLogger("mcctl");
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final CommandRunner runner;
	private final InputExecutor executor;

	public ControlEndpoint(CommandRunner runner, InputExecutor executor) {
		this.runner = runner;
		this.executor = executor;
	}

	@Override
	public void handle(HttpExchange exchange, String path) throws IOException {
		try {
			String method = exchange.getRequestMethod();

			if (isScreenshotPath(path)) {
				if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed: " + method + "\n");
					return;
				}
				handleScreenshot(exchange);
				return;
			}

			if (isMousePath(path)) {
				if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					respond(exchange, 405, "text/plain; charset=utf-8", "method not allowed: " + method + "\n");
					return;
				}
				handleMouse(exchange);
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
		}
		// the provider closes the exchange
	}

	private static boolean isScreenshotPath(String path) {
		return "/prtsc".equals(path) || "/prtsc.png".equals(path)
				|| "/screenshot".equals(path) || "/screenshot.png".equals(path);
	}

	private static boolean isMousePath(String path) {
		return "/mouse".equals(path) || "/mouse.txt".equals(path) || "/cursor".equals(path);
	}

	/** {@code GET /ctl/mouse}: where the cursor is right now (window pixels + GUI-scaled units). */
	private void handleMouse(HttpExchange exchange) throws IOException {
		if (!executor.isReady()) {
			respond(exchange, 409, "text/plain; charset=utf-8",
					"game not ready: " + executor.unavailableReason() + "\n");
			return;
		}

		String mouse;
		try {
			mouse = executor.mousePosition();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "mouse position failed", e);
			respond(exchange, 500, "text/plain; charset=utf-8", "mouse position failed: " + e + "\n");
			return;
		}

		respond(exchange, 200, "text/plain; charset=utf-8", mouse == null ? "" : mouse);
	}

	/** {@code GET /ctl/prtsc}: capture the current frame and hand it back as a PNG. */
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

		if (needsBaritone(plan)) {
			// bt / # commands must never fall back to a chat message: without Baritone the whole
			// request fails up front and nothing is queued.
			String reason = executor.baritoneUnavailableReason();
			if (reason != null) {
				respond(exchange, 400, "text/plain; charset=utf-8",
						"bt failed: " + reason + "\n");
				return;
			}
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

	/** True when the plan runs a Baritone command, i.e. it needs Baritone to be installed. */
	private static boolean needsBaritone(List<Action> plan) {
		for (Action action : plan) {
			if (action.kind() == Action.Kind.BARITONE) {
				return true;
			}
		}
		return false;
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
