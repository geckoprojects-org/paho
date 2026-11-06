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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Load test of the v5 client against a real broker: throughput, a reconnect storm and many clients
 * on one executor. Reports duration, threads and heap per client (generated/loadtest-report.txt).
 */
public class V5LoadTest {

	private final List<MqttAsyncClient> clients = new ArrayList<MqttAsyncClient>();
	private ScheduledThreadPoolExecutor executor;
	private DropProxy proxy;

	@Before
	public void setUp() {
		Load.assumeBroker();
	}

	@After
	public void tearDown() throws Exception {
		closeAll();
		if (proxy != null) {
			proxy.close();
		}
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	private void closeAll() {
		for (MqttAsyncClient client : clients) {
			try {
				if (client.isConnected()) {
					client.disconnect(0).waitForCompletion(5000);
				}
			} catch (Exception e) {
				// close anyway
			}
			try {
				client.close(true);
			} catch (Exception e) {
				// already closed
			}
		}
		clients.clear();
	}

	private static MqttConnectionOptions options() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(2000);
		options.setConnectionTimeout(10);
		options.setKeepAliveInterval(30);
		options.setReceiveMaximum(100);
		return options;
	}

	private MqttAsyncClient client(String uri, String id, ScheduledExecutorService executorService) throws Exception {
		MqttAsyncClient client = new MqttAsyncClient(uri, id, new MemoryPersistence(), null, executorService);
		clients.add(client);
		return client;
	}

	/** A callback that only counts; the scenarios override what they need. */
	private static class Callback implements MqttCallback {
		public void disconnected(MqttDisconnectResponse disconnectResponse) {
		}

		public void mqttErrorOccurred(MqttException exception) {
		}

		public void messageArrived(String topic, MqttMessage message) throws Exception {
		}

		public void deliveryComplete(IMqttToken token) {
		}

		public void connectComplete(boolean reconnect, String serverURI) {
		}

		public void authPacketArrived(int reasonCode, MqttProperties properties) {
		}
	}

	@Test
	public void testThroughputQos1() throws Exception {
		throughput(1);
	}

	@Test
	public void testThroughputQos2() throws Exception {
		throughput(2);
	}

	private void throughput(int qos) throws Exception {
		final String scenario = "v5 throughput QoS " + qos;
		final String base = "paho/load/v5/" + Load.RUN + "/t" + qos + "/";
		final int total = Load.CLIENTS * Load.MESSAGES;
		final Set<String> received = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
		final AtomicInteger duplicates = new AtomicInteger();

		long heap0 = Load.usedHeap();
		int threads0 = Load.clientThreads();
		MqttAsyncClient subscriber = client(Load.BROKER, "load-v5-sub-" + Load.RUN + qos, null);
		subscriber.setCallback(new Callback() {
			public void messageArrived(String topic, MqttMessage message) {
				if (!received.add(topic + "|" + new String(message.getPayload(), StandardCharsets.UTF_8))) {
					duplicates.incrementAndGet();
				}
			}
		});
		subscriber.connect(options()).waitForCompletion(Load.TIMEOUT_MS);
		subscriber.subscribe(base + "#", qos).waitForCompletion(Load.TIMEOUT_MS);

		final List<MqttAsyncClient> publishers = new ArrayList<MqttAsyncClient>();
		for (int i = 0; i < Load.CLIENTS; i++) {
			MqttAsyncClient publisher = client(Load.BROKER, "load-v5-pub-" + Load.RUN + qos + "-" + i, null);
			publisher.connect(options()).waitForCompletion(Load.TIMEOUT_MS);
			publishers.add(publisher);
		}
		int threadsPerClient = (Load.clientThreads() - threads0) / (Load.CLIENTS + 1);
		long heapPerClient = (Load.usedHeap() - heap0) / (Load.CLIENTS + 1);

		final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
		List<Thread> senders = new ArrayList<Thread>();
		long start = System.nanoTime();
		for (int i = 0; i < Load.CLIENTS; i++) {
			final MqttAsyncClient publisher = publishers.get(i);
			final String topic = base + i;
			Thread sender = new Thread(new Runnable() {
				public void run() {
					try {
						for (int m = 0; m < Load.MESSAGES; m++) {
							// sequential, as a device publishes its readings (#10)
							publisher.publish(topic, Integer.toString(m).getBytes(StandardCharsets.UTF_8), qos, false)
									.waitForCompletion(Load.TIMEOUT_MS);
						}
					} catch (Throwable t) {
						failure.compareAndSet(null, t);
					}
				}
			}, "load-pub-" + i);
			sender.start();
			senders.add(sender);
		}
		for (Thread sender : senders) {
			sender.join();
		}
		long published = System.nanoTime() - start;
		Assert.assertNull("publish failed: " + failure.get(), failure.get());
		boolean all = Load.await(() -> received.size() == total, Load.TIMEOUT_MS);
		long elapsed = System.nanoTime() - start;

		Load.report(scenario, "%d clients x %d messages: published in %d ms, received in %d ms, %.0f msg/s, %d duplicates",
				Load.CLIENTS, Load.MESSAGES, TimeUnit.NANOSECONDS.toMillis(published),
				TimeUnit.NANOSECONDS.toMillis(elapsed), total / (elapsed / 1e9), duplicates.get());
		Load.report(scenario, "%d threads and %s heap per connected client", threadsPerClient, Load.kib(heapPerClient));
		Assert.assertTrue("received " + received.size() + " of " + total, all);
		if (qos == 2) {
			Assert.assertEquals("QoS 2 delivered twice", 0, duplicates.get());
		}
	}

	@Test
	public void testReconnectStorm() throws Exception {
		reconnectStorm("v5 reconnect storm", null);
	}

	@Test
	public void testReconnectStormOnSharedExecutor() throws Exception {
		// receiver, sender and callback per client, plus connect tasks and the timers
		executor = Load.executor(3 * Load.CLIENTS + 4);
		reconnectStorm("v5 storm, shared executor", executor);
	}

	private void reconnectStorm(String scenario, ScheduledExecutorService executorService) throws Exception {
		final String base = "paho/load/v5/" + Load.RUN + "/" + (executorService == null ? "r" : "e") + "/";
		final int n = Load.CLIENTS;
		final AtomicIntegerArray confirmed = new AtomicIntegerArray(n); // last round a client got its message
		final AtomicInteger lost = new AtomicInteger();
		final AtomicInteger reconnected = new AtomicInteger();
		proxy = new DropProxy(Load.brokerHost(), Load.brokerPort());
		final String stormId = "load-v5-storm-" + Load.RUN + (executorService == null ? "r" : "e") + "-";

		int threads0 = Load.clientThreads();
		final List<MqttAsyncClient> storm = new ArrayList<MqttAsyncClient>();
		for (int i = 0; i < n; i++) {
			final int index = i;
			MqttAsyncClient client = client(proxy.getServerURI(), stormId + i, executorService);
			client.setCallback(new Callback() {
				public void disconnected(MqttDisconnectResponse disconnectResponse) {
					lost.incrementAndGet();
				}

				public void connectComplete(boolean reconnect, String serverURI) {
					if (reconnect) {
						reconnected.incrementAndGet();
					}
				}

				public void messageArrived(String topic, MqttMessage message) {
					int round = Integer.parseInt(new String(message.getPayload(), StandardCharsets.UTF_8));
					confirmed.accumulateAndGet(index, round, Math::max);
				}
			});
			client.connect(options()).waitForCompletion(Load.TIMEOUT_MS);
			client.subscribe(base + i, 1).waitForCompletion(Load.TIMEOUT_MS);
			storm.add(client);
		}
		MqttAsyncClient feeder = client(Load.BROKER, "load-v5-feed-" + Load.RUN + (executorService == null ? "r" : "e"), null);
		feeder.connect(options()).waitForCompletion(Load.TIMEOUT_MS);

		Set<String> timers = new TreeSet<String>();
		int threadsFirstRound = -1;
		int threadsLastRound = -1;
		long worstReconnect = 0;
		long worstResubscribe = 0;
		for (int round = 1; round <= Load.ROUNDS; round++) {
			final int lostTarget = n * round;
			proxy.dropAll();
			long start = System.nanoTime();
			Assert.assertTrue("round " + round + ": connection loss noticed by " + (lost.get() - lostTarget + n) + " of " + n,
					Load.await(() -> lost.get() >= lostTarget, Load.RECONNECT_BOUND_MS));
			timers.addAll(Load.timerThreads(stormId));
			Assert.assertTrue("round " + round + ": reconnected " + (reconnected.get() - lostTarget + n) + " of " + n,
					Load.await(() -> reconnected.get() >= lostTarget, Load.RECONNECT_BOUND_MS));
			long reconnect = System.nanoTime() - start;
			timers.addAll(Load.timerThreads(stormId));

			// The subscriptions are restored after connectComplete; publish until every client got
			// its message of this round.
			long deadline = start + TimeUnit.MILLISECONDS.toNanos(Load.RECONNECT_BOUND_MS);
			while (true) {
				boolean all = true;
				for (int i = 0; i < n; i++) {
					if (confirmed.get(i) < round) {
						all = false;
						// one at a time: v5 keeps to the broker's receive maximum (Mosquitto: 20)
						feeder.publish(base + i, Integer.toString(round).getBytes(StandardCharsets.UTF_8), 1, false)
								.waitForCompletion(Load.TIMEOUT_MS);
					}
				}
				if (all) {
					break;
				}
				Assert.assertTrue("round " + round + ": subscriptions not restored within " + Load.RECONNECT_BOUND_MS + " ms",
						System.nanoTime() - deadline < 0);
				Thread.sleep(200);
			}
			long resubscribe = System.nanoTime() - start;
			worstReconnect = Math.max(worstReconnect, reconnect);
			worstResubscribe = Math.max(worstResubscribe, resubscribe);
			int threads = Load.clientThreads();
			if (round == 1) {
				threadsFirstRound = threads;
			}
			threadsLastRound = threads;
		}
		Load.report(scenario, "%d clients x %d rounds: reconnected within %d ms, resubscribed within %d ms",
				n, Load.ROUNDS, TimeUnit.NANOSECONDS.toMillis(worstReconnect), TimeUnit.NANOSECONDS.toMillis(worstResubscribe));
		Load.report(scenario, "client threads: %d before, %d after round 1, %d after round %d; own timer threads: %s",
				threads0, threadsFirstRound, threadsLastRound, Load.ROUNDS, timers);
		Assert.assertTrue("threads grow from round to round: " + threadsFirstRound + " -> " + threadsLastRound,
				threadsLastRound <= threadsFirstRound);
		if (executorService != null) {
			Assert.assertEquals("timer threads besides the shared executor", new TreeSet<String>(), timers);
		}

		closeAll();
		if (executorService == null) {
			final int expected = threads0;
			boolean released = Load.await(() -> Load.clientThreads() <= expected, 10000);
			Load.report(scenario, "client threads after close(): %d", Load.clientThreads());
			Assert.assertTrue("client threads left after close(): " + Load.clientThreads(), released);
		}
	}
}
