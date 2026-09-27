// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.example.mcctl;

import com.example.httpd.HttpdProvider;
import net.fabricmc.api.ClientModInitializer;

import java.util.logging.Logger;

/**
 * Client entrypoint: mounts mcctl's endpoints under {@code /ctl} on MGHttpdProvider's shared server
 * (127.0.0.1:3420).
 *
 * <p>No Minecraft class is touched here - the executor resolves {@code Minecraft.getInstance()}
 * lazily on first use, which keeps startup safe.</p>
 */
public class McCtlClientMod implements ClientModInitializer {
	public static final Logger LOG = Logger.getLogger("mcctl");

	private McInputExecutor executor;
	private CommandRunner runner;

	@Override
	public void onInitializeClient() {
		executor = new McInputExecutor();
		runner = new CommandRunner(executor);

		HttpdProvider.register(ControlEndpoint.PREFIX, ControlEndpoint.NAME, ControlEndpoint.ENDPOINTS,
				new ControlEndpoint(runner, executor));

		// The provider owns the HTTP server and the client-exit handling; this hook only releases
		// what the executor may still be holding (real JVM shutdowns: crash, SIGTERM, ...).
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			executor.releaseAll();
			runner.shutdown();
		}, "mcctl-shutdown"));
	}
}
