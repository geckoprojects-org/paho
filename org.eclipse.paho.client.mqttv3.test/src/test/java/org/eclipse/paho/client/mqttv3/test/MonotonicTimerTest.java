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
package org.eclipse.paho.client.mqttv3.test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.client.mqttv3.internal.MonotonicTimer;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.junit.Assert;
import org.junit.Test;

/** MonotonicTimer, the replacement for java.util.Timer in the ping sender and the reconnect cycle. */
public class MonotonicTimerTest {

	private static Thread thread(String name) {
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (thread.getName().equals(name)) {
				return thread;
			}
		}
		return null;
	}

	@Test
	public void testRunsAfterDelayOnOneNamedThread() throws Exception {
		final String name = "MQTT Test: monotonic-timer";
		MonotonicTimer timer = new MonotonicTimer(name);
		Assert.assertNull("thread started before the first task", thread(name));

		final CountDownLatch ran = new CountDownLatch(2);
		final String[] threadName = new String[1];
		final boolean[] daemon = new boolean[1];
		long start = System.nanoTime();
		Runnable task = new Runnable() {
			public void run() {
				threadName[0] = Thread.currentThread().getName();
				daemon[0] = Thread.currentThread().isDaemon();
				ran.countDown();
			}
		};
		timer.schedule(task, 200);
		timer.schedule(task, 200);
		Assert.assertTrue(ran.await(TestTiming.scaled(5000), TimeUnit.MILLISECONDS));
		Assert.assertTrue("ran before its delay", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 200);
		Assert.assertEquals(name, threadName[0]);
		Assert.assertFalse("like java.util.Timer, the thread is not a daemon", daemon[0]);

		timer.cancel();
		Assert.assertTrue("thread still alive after cancel()", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return thread(name) == null;
			}
		}, 5000));
	}

	@Test
	public void testCancelDiscardsScheduledTasks() throws Exception {
		MonotonicTimer timer = new MonotonicTimer("MQTT Test: monotonic-timer-cancel");
		final AtomicInteger runs = new AtomicInteger();
		timer.schedule(new Runnable() {
			public void run() {
				runs.incrementAndGet();
			}
		}, 300);
		timer.cancel();
		TestTiming.observe(800);
		Assert.assertEquals(0, runs.get());
	}
}
