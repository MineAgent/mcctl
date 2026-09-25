// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import net.fabricmc.api.ClientModInitializer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client entrypoint: starts the control server on 127.0.0.1:3420, and shuts it down and ends the
 * JVM when the client exits (so Minecraft's post-main watchdog cannot write a crash report).
 *
 * <p>No Minecraft class is touched here - the executor and the exit watcher resolve
 * {@code Minecraft.getInstance()} lazily on first use, which keeps startup safe.</p>
 */
public class McCtlClientMod implements ClientModInitializer {
	public static final Logger LOG = Logger.getLogger("mcctl");

	private McInputExecutor executor;
	private CommandRunner runner;
	private ControlServer server;
	private boolean stopped;

	@Override
	public void onInitializeClient() {
		executor = new McInputExecutor();
		runner = new CommandRunner(executor);
		server = new ControlServer(runner, executor);

		try {
			server.start();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "mcctl could not bind to " + ControlServer.HOST + ":" + ControlServer.PORT
					+ " - is another instance already running?", e);
			return;
		}

		// HttpServer's "HTTP-Dispatcher" thread is not a daemon, and other mods leave non-daemon
		// threads behind as well, so without an explicit exit Minecraft's post-main watchdog would
		// write a crash report 15 s after a normal quit. The watcher stops this server and ends the
		// JVM once the render thread is gone.
		ClientExitWatcher.onClientExit(this::shutdown);
		// Still tear down on a real JVM shutdown (crash, SIGTERM, System.exit, ...).
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "mcctl-shutdown"));
	}

	/** Idempotent teardown, shared by the client-exit watcher and the JVM shutdown hook. */
	private synchronized void shutdown() {
		if (stopped) {
			return;
		}
		stopped = true;
		executor.releaseAll();
		runner.shutdown();
		server.stop();
	}
}
