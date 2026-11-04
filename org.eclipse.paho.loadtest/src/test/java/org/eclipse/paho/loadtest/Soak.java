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
import java.util.concurrent.TimeUnit;

import org.junit.Assert;

/** Measurements of the soak scenarios: values per round, growth, idle cost, I/O per message. */
final class Soak {

	/** Largest heap growth per round and client that still counts as noise. */
	static final long HEAP_SLOPE_LIMIT = Long.getLong("paho.load.soakHeapSlopeLimit", 1024);

	private Soak() {
	}

	/** Records heap after GC, client threads, file descriptors and persistence files per round. */
	static final class Recorder {
		final long[] heap;
		final long[] threads;
		final long[] fds;
		final long[] files;

		Recorder(int rounds) {
			heap = new long[rounds];
			threads = new long[rounds];
			fds = new long[rounds];
			files = new long[rounds];
		}

		void sample(int round, int persistenceFiles) throws InterruptedException {
			heap[round] = Load.usedHeap();
			threads[round] = Load.clientThreads();
			fds[round] = Load.openFileDescriptors();
			files[round] = persistenceFiles;
		}

		/** Rounds left out of the growth checks: class loading, JIT and caches fill up at first. */
		int warmup() {
			return Math.max(2, heap.length / 4);
		}

		void report(String scenario, int clients, long seconds, Idle baseline, Idle idle) {
			int w = warmup();
			int last = heap.length - 1;
			Load.report(scenario, "%d clients x %d rounds (%d messages per QoS, subscribe, unsubscribe, reconnect) in %d s",
					clients, heap.length, Load.SOAK_MESSAGES, seconds);
			Load.report(scenario, "heap after GC: %s after round %d, %s after round %d; slope %.0f bytes per round",
					Load.kib(heap[w]), w, Load.kib(heap[last]), last, Load.slope(heap, w));
			Load.report(scenario, "client threads %d -> %d, file descriptors %d -> %d, persistence files %d -> %d",
					threads[w], threads[last], fds[w], fds[last], files[w], files[last]);
			Load.report(scenario, "idle %d s, above the process without clients: CPU %.1f ms and %.1f system calls per client and minute",
					idle.seconds, (idle.cpuNanos - baseline.cpuNanos) / 1e6 / clients * 60 / idle.seconds,
					(double) (idle.syscalls - baseline.syscalls) / clients * 60 / idle.seconds);
		}

		void assertNoGrowth(int clients) {
			int w = warmup();
			int last = heap.length - 1;
			double slope = Load.slope(heap, w);
			Assert.assertTrue(String.format("heap grows by %.0f bytes per round", slope), slope <= HEAP_SLOPE_LIMIT * clients);
			Assert.assertTrue("client threads grow: " + threads[w] + " -> " + threads[last], threads[last] <= max(threads, 0, w));
			Assert.assertTrue("file descriptors grow: " + fds[w] + " -> " + fds[last], fds[last] <= max(fds, 0, w));
			Assert.assertTrue("persistence files grow: " + files[w] + " -> " + files[last], files[last] <= max(files, 0, w));
		}

		private static long max(long[] values, int from, int to) {
			long max = Long.MIN_VALUE;
			for (int i = from; i <= to; i++) {
				max = Math.max(max, values[i]);
			}
			return max;
		}
	}

	/** CPU time and system calls of the process while the connected clients are idle. */
	static final class Idle {
		final int seconds;
		final long cpuNanos;
		final long syscalls;

		Idle(int seconds, long cpuNanos, long syscalls) {
			this.seconds = seconds;
			this.cpuNanos = cpuNanos;
			this.syscalls = syscalls;
		}
	}

	static Idle idle(int seconds) throws InterruptedException {
		long cpu0 = Load.processCpuNanos();
		long[] io0 = Load.io();
		Thread.sleep(TimeUnit.SECONDS.toMillis(seconds));
		long cpu1 = Load.processCpuNanos();
		long[] io1 = Load.io();
		return new Idle(seconds, cpu1 - cpu0, (io1[2] - io0[2]) + (io1[3] - io0[3]));
	}

	static void reportIo(String scenario, long[] before, long[] after, int messages) {
		Load.report(scenario, "%.2f reads (%.0f bytes) and %.2f writes (%.0f bytes) per message",
				(double) (after[2] - before[2]) / messages, (double) (after[0] - before[0]) / messages,
				(double) (after[3] - before[3]) / messages, (double) (after[1] - before[1]) / messages);
	}

	static void delete(File file) {
		File[] entries = file.listFiles();
		if (entries != null) {
			for (File entry : entries) {
				delete(entry);
			}
		}
		file.delete();
	}
}
