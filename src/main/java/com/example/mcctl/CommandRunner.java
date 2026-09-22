// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

	/** Queues a plan; returns immediately. Failures are only logged. */
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

	/**
	 * Runs a plan on the same worker and waits for it to finish, so an action that has an outcome
	 * worth reporting (typing into a text box) can turn into a proper HTTP status.
	 *
	 * @return the first failure reported by an action, or {@code null} when the plan succeeded
	 */
	public String submitAndWait(List<Action> plan, long timeoutMs) {
		CompletableFuture<String> result = new CompletableFuture<>();
		pending.incrementAndGet();
		worker.execute(() -> {
			try {
				result.complete(run(plan));
			} catch (Throwable t) {
				result.completeExceptionally(t);
			} finally {
				pending.decrementAndGet();
			}
		});

		try {
			return result.get(Math.max(timeoutMs, 1000L), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			return "timed out after " + timeoutMs + " ms (the command keeps running)";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return "interrupted";
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			return cause == null ? String.valueOf(e) : String.valueOf(cause);
		}
	}

	/** @return the first action failure, or {@code null} when the whole plan succeeded */
	private String run(List<Action> plan) {
		for (Action action : plan) {
			if (!running) {
				return "shutting down";
			}
			if (action.delayMs() > 0 && !sleep(action.delayMs())) {
				return "interrupted";
			}
			try {
				String failure = apply(action);
				if (failure != null) {
					LOG.warning("failed to execute " + action.describe() + ": " + failure);
					return failure;
				}
			} catch (RuntimeException e) {
				LOG.log(Level.WARNING, "failed to execute " + action.describe(), e);
			}
		}
		return null;
	}

	/** @return a failure message when the action reports one, otherwise {@code null} */
	private String apply(Action action) {
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
			case TYPE_TEXT -> {
				return executor.typeText(action.message());
			}
			case TYPE_ENTER -> {
				return executor.typeEnter();
			}
			case RELEASE_ALL -> executor.releaseAll();
		}
		return null;
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
