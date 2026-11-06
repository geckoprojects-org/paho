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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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

import com.sun.management.OperatingSystemMXBean;

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
	/** Rounds of the soak scenario: publish, subscribe/unsubscribe, reconnect. */
	static final int SOAK_ROUNDS = Integer.getInteger("paho.load.soakRounds", QUICK ? 8 : 40);
	static final int SOAK_CLIENTS = Integer.getInteger("paho.load.soakClients", QUICK ? 2 : 4);
	/** Messages per QoS level, client and soak round. */
	static final int SOAK_MESSAGES = Integer.getInteger("paho.load.soakMessages", QUICK ? 20 : 50);
	/** Seconds the soak scenario stays idle at the end to measure CPU time and system calls. */
	static final int SOAK_IDLE_SECONDS = Integer.getInteger("paho.load.soakIdleSeconds", QUICK ? 5 : 20);
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

	/** Open file descriptors of the process (Linux), -1 where unknown. */
	static int openFileDescriptors() {
		String[] fds = new File("/proc/self/fd").list();
		return fds == null ? -1 : fds.length;
	}

	/**
	 * I/O counters of the process from /proc/self/io (Linux): rchar, wchar, syscr, syscw; -1 where
	 * unknown.
	 */
	static long[] io() {
		long[] values = { -1, -1, -1, -1 };
		String[] keys = { "rchar", "wchar", "syscr", "syscw" };
		try {
			BufferedReader reader = new BufferedReader(new FileReader("/proc/self/io"));
			try {
				String line;
				while ((line = reader.readLine()) != null) {
					for (int i = 0; i < keys.length; i++) {
						if (line.startsWith(keys[i] + ":")) {
							values[i] = Long.parseLong(line.substring(keys[i].length() + 1).trim());
						}
					}
				}
			} finally {
				reader.close();
			}
		} catch (IOException e) {
			// not Linux
		}
		return values;
	}

	/** CPU time of the process in nanoseconds, -1 where unknown. */
	static long processCpuNanos() {
		Object os = ManagementFactory.getOperatingSystemMXBean();
		return os instanceof OperatingSystemMXBean ? ((OperatingSystemMXBean) os).getProcessCpuTime() : -1;
	}

	/** Files below the directory, recursively. */
	static int files(File directory) {
		File[] entries = directory.listFiles();
		if (entries == null) {
			return 0;
		}
		int count = 0;
		for (File entry : entries) {
			count += entry.isDirectory() ? files(entry) : 1;
		}
		return count;
	}

	/** Least-squares slope of the values from index {@code from} on, per index. */
	static double slope(long[] values, int from) {
		int n = values.length - from;
		if (n < 2) {
			return 0;
		}
		double meanX = 0;
		double meanY = 0;
		for (int i = from; i < values.length; i++) {
			meanX += i;
			meanY += values[i];
		}
		meanX /= n;
		meanY /= n;
		double num = 0;
		double den = 0;
		for (int i = from; i < values.length; i++) {
			num += (i - meanX) * (values[i] - meanY);
			den += (i - meanX) * (i - meanX);
		}
		return num / den;
	}

	static String kib(long bytes) {
		return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
	}
}
