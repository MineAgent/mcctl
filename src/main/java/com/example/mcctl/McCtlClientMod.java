// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import net.fabricmc.api.ClientModInitializer;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Client entrypoint: starts the control server on 127.0.0.1:3420.
 *
 * <p>No Minecraft class is touched here - the executor resolves {@code Minecraft.getInstance()}
 * lazily on first use, which keeps startup safe.</p>
 */
public class McCtlClientMod implements ClientModInitializer {
	public static final Logger LOG = Logger.getLogger("mcctl");

	private CommandRunner runner;
	private ControlServer server;

	@Override
	public void onInitializeClient() {
		McInputExecutor executor = new McInputExecutor();
		runner = new CommandRunner(executor);
		server = new ControlServer(runner, executor);

		try {
			server.start();
		} catch (IOException e) {
			LOG.log(Level.SEVERE, "mcctl could not bind to " + ControlServer.HOST + ":" + ControlServer.PORT
					+ " - is another instance already running?", e);
			return;
		}

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			executor.releaseAll();
			runner.shutdown();
			server.stop();
		}, "mcctl-shutdown"));
	}
}
