/*******************************************************************************
 * Copyright (c) 2026 Data In Motion Consulting GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *    https://www.eclipse.org/legal/epl-2.0
 * and the Eclipse Distribution License is available at
 *   https://www.eclipse.org/org/documents/edl-v10.php
 *
 * Contributors:
 *    Mark Hoffmann - initial API and implementation
 *******************************************************************************/
package org.eclipse.paho.loadtest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.Assume;

/**
 * Settings, measurements and the report of the load test. The sizes come from system properties,
 * which the Gradle task {@code loadTest} takes from {@code -Ppaho.load.*}.
 */
final class Load {

	static final String BROKER = System.getProperty("paho.load.broker", "tcp://localhost:1883");
	/** Short variant ({@code -Ppaho.load.quick}): smaller defaults; sizes given explicitly still win. */
	static final boolean QUICK = Boolean.getBoolean("paho.load.quick");
	static final int CLIENTS = Integer.getInteger("paho.load.clients", QUICK ? 5 : 20);
	static final int MESSAGES = Integer.getInteger("paho.load.messages", QUICK ? 100 : 500);
	static final int ROUNDS = Integer.getInteger("paho.load.rounds", QUICK ? 1 : 5);
	static final long TIMEOUT_MS = Long.getLong("paho.load.timeoutMs", 120000);
	/** Longest time all clients may take to reconnect and resubscribe after a drop. */
	static final long RECONNECT_BOUND_MS = Long.getLong("paho.load.reconnectBoundMs", 30000);
	/**
	 * Threads of the shared executors. The client renames them while its tasks run on them
	 * ("MQTT Rec: ..."), so they are told apart by identity, not by name.
	 */
	private static final Set<Thread> EXECUTOR_THREADS = Collections.newSetFromMap(new ConcurrentHashMap<Thread, Boolean>());

	/** A run id, so that topics of runs against the same broker do not mix. */
	static final String RUN = Long.toString(System.currentTimeMillis(), 36);

	private Load() {
	}

	static String brokerHost() {
		return URI.create(BROKER).getHost();
	}

	static int brokerPort() {
		int port = URI.create(BROKER).getPort();
		return port < 0 ? 1883 : port;
	}

	/** Skips the test when no broker is listening. */
	static void assumeBroker() {
		Socket socket = new Socket();
		boolean reachable;
		try {
			socket.connect(new InetSocketAddress(brokerHost(), brokerPort()), 1000);
			reachable = true;
		} catch (IOException e) {
			reachable = false;
		} finally {
			try {
				socket.close();
			} catch (IOException e) {
				// ignore
			}
		}
		Assume.assumeTrue("no broker at " + BROKER + " (test/broker/start-broker.sh)", reachable);
	}

	static boolean await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() - deadline > 0) {
				return false;
			}
			Thread.sleep(20);
		}
		return true;
	}

	/** Live threads of the clients: Paho's own ("MQTT ...") and those of the shared executors. */
	static int clientThreads() {
		int count = 0;
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.isAlive() && (EXECUTOR_THREADS.contains(thread) || thread.getName().startsWith("MQTT "))) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Kinds of timer threads the clients whose id starts with the prefix started themselves,
	 * outside the shared executors.
	 */
	static Set<String> timerThreads(String clientIdPrefix) {
		Set<String> names = new TreeSet<String>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			String name = thread.getName();
			if (!EXECUTOR_THREADS.contains(thread) && (name.startsWith("MQTT Reconnect: " + clientIdPrefix)
					|| name.startsWith("MQTT Ping: " + clientIdPrefix))) {
				names.add(name.substring(0, name.indexOf(':')));
			}
		}
		return names;
	}

	/** Used heap after garbage collection. */
	static long usedHeap() throws InterruptedException {
		for (int i = 0; i < 3; i++) {
			System.gc();
			Thread.sleep(50);
		}
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	static ScheduledThreadPoolExecutor executor(int threads) {
		final AtomicInteger count = new AtomicInteger();
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, new ThreadFactory() {
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r, "load-exec-" + count.incrementAndGet());
				thread.setDaemon(true);
				EXECUTOR_THREADS.add(thread);
				return thread;
			}
		});
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}

	/** Prints a line of the report and appends it to generated/loadtest-report.txt. */
	static synchronized void report(String scenario, String format, Object... args) {
		String line = String.format(Locale.ROOT, "%-28s %s", scenario, String.format(Locale.ROOT, format, args));
		System.out.println(line);
		File file = new File("generated", "loadtest-report.txt");
		file.getParentFile().mkdirs();
		try {
			Writer writer = new FileWriter(file, true);
			try {
				writer.write(line + System.lineSeparator());
			} finally {
				writer.close();
			}
		} catch (IOException e) {
			// the report on stdout is enough
		}
	}

	static String kib(long bytes) {
		return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
	}
}
