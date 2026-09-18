// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

/** Thrown when a request body cannot be parsed into commands. */
public class CommandException extends Exception {
	private static final long serialVersionUID = 1L;

	public CommandException(String message) {
		super(message);
	}

	public CommandException(int line, String message) {
		super("line " + line + ": " + message);
	}
}
