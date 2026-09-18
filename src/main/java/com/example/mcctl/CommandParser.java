// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses request bodies into {@link Action}s.
 *
 * <pre>
 * W 100                 hold W for 100 ms
 * W+Ctrl 100            hold W and Ctrl together for 100 ms (aliases: CTRL, SHIFT, ...)
 * mouse left            left click
 * mouse left 500        hold left mouse button for 500 ms
 * mouse move +30 -80    move the mouse 30 px right and 80 px up
 * mouse scroll 3        scroll the wheel up 3 notches
 * delay 80 W 50         wait 80 ms, then hold W for 50 ms
 * release               release everything that is currently held
 * bt goal ~ ~ ~20       run the Baritone command "goal ~ ~ ~20"
 * bt help               run the Baritone command "help"      (= "#help" in chat)
 * chat hello            send a plain chat message
 * #goal ~ ~ ~20         shorthand for "bt goal ~ ~ ~20"
 * </pre>
 *
 * A body may contain several commands, separated by newlines or {@code ;}. They are executed
 * strictly in order: the second command starts after the first one finished. Lines starting with
 * {@code //} are comments.
 */
public final class CommandParser {
	public static final long DEFAULT_HOLD_MS = 50L;
	public static final long MAX_HOLD_MS = 600_000L;
	public static final long MAX_DELAY_MS = 600_000L;

	/** {@code delay <ms> <rest>} */
	private static final Pattern DELAY_PREFIX =
			Pattern.compile("^delay\\s+(\\S+)\\s+(.+)$", Pattern.CASE_INSENSITIVE);

	private CommandParser() {
	}

	public static List<Action> parse(String body) throws CommandException {
		if (body == null || body.isBlank()) {
			throw new CommandException("empty request body");
		}

		List<Action> actions = new ArrayList<>();
		String[] rawLines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n");
		int lineNo = 0;

		for (String rawLine : rawLines) {
			lineNo++;
			for (String piece : rawLine.split(";")) {
				String line = piece.trim();
				if (line.isEmpty() || line.startsWith("//")) {
					continue;
				}
				parseLine(line, lineNo, actions);
			}
		}

		if (actions.isEmpty()) {
			throw new CommandException("no command found in request body");
		}
		return List.copyOf(actions);
	}

	private static void parseLine(String line, int lineNo, List<Action> out) throws CommandException {
		String rest = line;
		long delay = 0L;

		// "delay <ms> <command>" (may be chained)
		while (true) {
			Matcher matcher = DELAY_PREFIX.matcher(rest);
			if (matcher.matches()) {
				delay += parseLong(matcher.group(1), lineNo, "delay milliseconds");
				rest = matcher.group(2).trim();
				if (rest.isEmpty()) {
					throw new CommandException(lineNo, "'delay' without a following command");
				}
				continue;
			}
			if (startsWithWord(rest, "delay")) {
				throw new CommandException(lineNo, "'delay' needs a millisecond value followed by a command");
			}
			break;
		}
		if (delay > MAX_DELAY_MS) {
			throw new CommandException(lineNo, "delay too large (max " + MAX_DELAY_MS + " ms)");
		}

		// "#goal ~ ~ ~20" = same as typing it into the chat box
		if (rest.startsWith("#")) {
			out.add(Action.chat(baritoneMessage(rest.substring(1).trim(), lineNo), delay));
			return;
		}

		String[] token = rest.split("\\s+");
		String head = token[0];
		String remainder = rest.substring(head.length()).trim();

		if (head.equalsIgnoreCase("release") || head.equalsIgnoreCase("releaseall")
				|| head.equalsIgnoreCase("release_all") || head.equalsIgnoreCase("stop")) {
			requireEnd(token, 1, lineNo, "release");
			out.add(Action.releaseAll(delay));
			return;
		}

		if (head.equalsIgnoreCase("bt") || head.equalsIgnoreCase("baritone")) {
			out.add(Action.chat(baritoneMessage(remainder, lineNo), delay));
			return;
		}

		if (head.equalsIgnoreCase("chat") || head.equalsIgnoreCase("say")) {
			if (remainder.isEmpty()) {
				throw new CommandException(lineNo, "'" + head + "' needs a message");
			}
			out.add(Action.chat(remainder, delay));
			return;
		}

		if (head.equalsIgnoreCase("mouse")) {
			parseMouse(token, lineNo, delay, out);
			return;
		}

		// key or key+key+...
		List<String> keys = new ArrayList<>();
		for (String part : head.split("\\+")) {
			if (part.isBlank()) {
				continue;
			}
			String canonical = Keys.canonical(part);
			if (canonical == null) {
				throw new CommandException(lineNo, "unknown key '" + part + "' (known keys: " + Keys.summary() + ")");
			}
			if (!keys.contains(canonical)) {
				keys.add(canonical);
			}
		}
		if (keys.isEmpty()) {
			throw new CommandException(lineNo, "no key given");
		}

		long hold = DEFAULT_HOLD_MS;
		int next = 1;
		if (next < token.length) {
			hold = parseLong(token[next], lineNo, "hold duration in ms");
			next++;
		}
		requireEnd(token, next, lineNo, "key command");

		out.add(Action.keys(keys, clampHold(hold, lineNo), delay));
	}

	/** Turns "goal ~ ~ ~20" / "#goal ~ ~ ~20" into the chat form "#goal ~ ~ ~20". */
	private static String baritoneMessage(String command, int lineNo) throws CommandException {
		String trimmed = command.startsWith("#") ? command.substring(1).trim() : command;
		if (trimmed.isEmpty()) {
			throw new CommandException(lineNo, "'bt' needs a baritone command, e.g. bt goal ~ ~ ~20");
		}
		return "#" + trimmed;
	}

	private static boolean startsWithWord(String text, String word) {
		return text.regionMatches(true, 0, word, 0, word.length())
				&& (text.length() == word.length() || Character.isWhitespace(text.charAt(word.length())));
	}

	private static void parseMouse(String[] token, int lineNo, long delay, List<Action> out)
			throws CommandException {
		if (token.length < 2) {
			throw new CommandException(lineNo, "'mouse' needs an argument: left | right | mid | move | scroll");
		}

		String sub = token[1].toLowerCase(Locale.ROOT);

		switch (sub) {
			case "move", "moveto", "look" -> {
				if (token.length < 4) {
					throw new CommandException(lineNo, "'mouse move' needs two integers: dx dy");
				}
				int dx = parseInt(token[2], lineNo, "mouse dx");
				int dy = parseInt(token[3], lineNo, "mouse dy");
				requireEnd(token, 4, lineNo, "mouse move");
				out.add(Action.mouseMove(dx, dy, delay));
			}
			case "scroll", "wheel" -> {
				double amount = 1.0;
				int next = 2;
				if (next < token.length) {
					amount = parseDouble(token[next], lineNo, "scroll amount");
					next++;
				}
				requireEnd(token, next, lineNo, "mouse scroll");
				out.add(Action.mouseScroll(amount, delay));
			}
			default -> {
				int button = Keys.mouseButton(sub);
				if (button < 0) {
					throw new CommandException(lineNo, "unknown mouse button '" + sub
							+ "' (use left | right | mid, or move / scroll)");
				}
				long hold = DEFAULT_HOLD_MS;
				int next = 2;
				if (next < token.length) {
					hold = parseLong(token[next], lineNo, "hold duration in ms");
					next++;
				}
				requireEnd(token, next, lineNo, "mouse");
				out.add(Action.mouseButton(Keys.mouseButtonName(button), clampHold(hold, lineNo), delay));
			}
		}
	}

	private static void requireEnd(String[] token, int index, int lineNo, String what) throws CommandException {
		if (index < token.length) {
			throw new CommandException(lineNo, "unexpected extra argument '" + token[index] + "' after " + what);
		}
	}

	private static long clampHold(long hold, int lineNo) throws CommandException {
		if (hold < 0) {
			throw new CommandException(lineNo, "hold duration must not be negative");
		}
		return Math.min(hold, MAX_HOLD_MS);
	}

	private static long parseLong(String value, int lineNo, String what) throws CommandException {
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new CommandException(lineNo, what + " must be an integer, got '" + value + "'");
		}
	}

	private static int parseInt(String value, int lineNo, String what) throws CommandException {
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			throw new CommandException(lineNo, what + " must be an integer, got '" + value + "'");
		}
	}

	private static double parseDouble(String value, int lineNo, String what) throws CommandException {
		try {
			return Double.parseDouble(value.trim());
		} catch (NumberFormatException e) {
			throw new CommandException(lineNo, what + " must be a number, got '" + value + "'");
		}
	}
}
