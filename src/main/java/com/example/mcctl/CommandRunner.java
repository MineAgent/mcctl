// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs parsed command plans on a single background thread.
 *
 * <p>A single worker guarantees that commands are executed in the order they were received, and
 * that a request containing several lines behaves like a tiny script (each line starts after the
 * previous one finished). Every executor call itself hops onto the render thread.</p>
 */
public final class CommandRunner {
	private static final Logger LOG = Logger.getLogger("mcctl");

	private final InputExecutor executor;
	private final ExecutorService worker;
	private final AtomicInteger pending = new AtomicInteger();
	private volatile boolean running = true;

	public CommandRunner(InputExecutor executor) {
		this.executor = executor;
		this.worker = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "mcctl-runner");
			t.setDaemon(true);
			return t;
		});
	}

	/** Queues a plan; returns immediately. */
	public void submit(List<Action> plan) {
		pending.incrementAndGet();
		worker.execute(() -> {
			try {
				run(plan);
			} finally {
				pending.decrementAndGet();
			}
		});
	}

	private void run(List<Action> plan) {
		for (Action action : plan) {
			if (!running) {
				return;
			}
			if (action.delayMs() > 0 && !sleep(action.delayMs())) {
				return;
			}
			try {
				apply(action);
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "failed to execute " + action.describe(), e);
			}
		}
	}

	private void apply(Action action) {
		switch (action.kind()) {
			case KEYS -> {
				for (String key : action.keys()) {
					executor.keyDown(key);
				}
				sleep(action.holdMs());
				for (String key : action.keys()) {
					executor.keyUp(key);
				}
			}
			case MOUSE_BUTTON -> {
				executor.mouseButtonDown(action.button());
				sleep(action.holdMs());
				executor.mouseButtonUp(action.button());
			}
			case MOUSE_MOVE -> executor.mouseMove(action.dx(), action.dy());
			case MOUSE_SCROLL -> executor.mouseScroll(action.amount());
			case CHAT -> executor.sendChat(action.message());
			case RELEASE_ALL -> executor.releaseAll();
		}
	}

	/** @return false when interrupted (shutting down) */
	private boolean sleep(long ms) {
		if (ms <= 0) {
			return running;
		}
		try {
			Thread.sleep(ms);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/** Number of requests that are queued or currently running. */
	public int pending() {
		return pending.get();
	}

	public void shutdown() {
		running = false;
		worker.shutdownNow();
	}
}
