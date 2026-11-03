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
package org.eclipse.paho.mqttv5.client.test.automaticReconnect;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.internal.MonotonicTimer;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * With an application executor, the reconnect delay and the keep-alive pings run on it: the
 * client starts no timer thread of its own, and close() leaves the executor running.
 */
public class ExecutorTimersTest {

	private FakeMqttBroker broker;
	private ScheduledExecutorService executor;
	/**
	 * Threads of the application's executor. The client renames them while its tasks run on them
	 * ("MQTT Ping: ..."), so they are told apart by identity, not by name.
	 */
	private final Set<Thread> executorThreads = Collections.newSetFromMap(new ConcurrentHashMap<Thread, Boolean>());
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		final AtomicInteger count = new AtomicInteger();
		executor = new ScheduledThreadPoolExecutor(4, new ThreadFactory() {
			public Thread newThread(Runnable r) {
				Thread thread = new Thread(r, "app-" + count.incrementAndGet());
				thread.setDaemon(true);
				executorThreads.add(thread);
				return thread;
			}
		});
	}

	@After
	public void tearDown() throws Exception {
		if (client != null) {
			try {
				client.close(true);
			} catch (MqttException e) {
				// already closed
			}
		}
		broker.close();
		executor.shutdownNow();
	}

	private static MqttConnectionOptions options() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(2000);
		options.setConnectionTimeout(1);
		options.setKeepAliveInterval(1);
		return options;
	}

	/** Names of the live threads that the client starts for its timers. */
	private Set<String> timerThreads(String clientId) {
		Set<String> names = new TreeSet<String>();
		for (Thread thread : Thread.getAllStackTraces().keySet()) {
			if (executorThreads.contains(thread)) {
				continue;
			}
			String name = thread.getName();
			if (name.equals("MQTT Reconnect: " + clientId) || name.equals("MQTT Ping: " + clientId)) {
				names.add(name);
			}
		}
		return names;
	}

	@Test
	public void testTimersRunOnTheApplicationExecutor() throws Exception {
		final String clientId = "executor-timers";
		client = new MqttAsyncClient(broker.getServerURI(), clientId, new MemoryPersistence(), null, executor);
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		final Set<String> seen = new TreeSet<String>();

		// keepAlive 1 s: the pings go out
		Assert.assertTrue("no PINGREQ", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				seen.addAll(timerThreads(clientId));
				return broker.getPingCount() >= 2;
			}
		}, 10000));

		// the reconnect delay passes and the client reconnects
		broker.dropConnections();
		Assert.assertTrue("no reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				seen.addAll(timerThreads(clientId));
				return broker.getConnectCount() >= 2 && client.isConnected();
			}
		}, 10000));
		Assert.assertEquals("timer threads of the client", new TreeSet<String>(), seen);

		client.disconnect().waitForCompletion(TestTiming.scaled(5000));
		client.close();
		client = null;
		Assert.assertFalse("close() shut the application's executor down", executor.isShutdown());
		final CountDownLatch ran = new CountDownLatch(1);
		executor.execute(new Runnable() {
			public void run() {
				ran.countDown();
			}
		});
		Assert.assertTrue(ran.await(TestTiming.scaled(5000), TimeUnit.MILLISECONDS));
	}

	@Test
	public void testWithoutExecutorTheClientKeepsItsTimerThreads() throws Exception {
		final String clientId = "own-timers";
		client = new MqttAsyncClient(broker.getServerURI(), clientId, new MemoryPersistence());
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		final Set<String> seen = timerThreads(clientId);
		broker.dropConnections();
		Assert.assertTrue("no reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				seen.addAll(timerThreads(clientId));
				return broker.getConnectCount() >= 2 && client.isConnected();
			}
		}, 10000));
		Assert.assertTrue(seen.toString(), seen.contains("MQTT Ping: " + clientId));
		Assert.assertTrue(seen.toString(), seen.contains("MQTT Reconnect: " + clientId));
	}

	@Test
	public void testCancelLeavesTheExecutorRunning() throws Exception {
		MonotonicTimer timer = new MonotonicTimer(executor);
		final AtomicInteger runs = new AtomicInteger();
		Runnable task = new Runnable() {
			public void run() {
				runs.incrementAndGet();
			}
		};
		timer.schedule(task, 0);
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return runs.get() == 1;
			}
		}, 5000));
		timer.schedule(task, TestTiming.scaled(300));
		timer.cancel();
		TestTiming.observe(600);
		Assert.assertEquals("cancelled task ran", 1, runs.get());
		Assert.assertFalse(executor.isShutdown());
		try {
			timer.schedule(task, 0);
			Assert.fail("schedule() after cancel() must fail");
		} catch (RejectedExecutionException e) {
			// as for the timer with its own thread
		}
	}
}
