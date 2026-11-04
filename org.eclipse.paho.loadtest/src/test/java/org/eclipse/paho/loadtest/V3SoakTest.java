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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClientPersistence;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Soak test of the v3 client: many rounds of publish (QoS 0, 1, 2), subscribe and unsubscribe of
 * fresh topics with message listeners, and a dropped connection, while heap, threads, file
 * descriptors and persistence files are recorded per round. Fails when they keep growing.
 */
public class V3SoakTest {

	private final List<MqttAsyncClient> clients = new ArrayList<MqttAsyncClient>();
	private DropProxy proxy;
	private File persistenceDir;

	@Before
	public void setUp() throws Exception {
		Load.assumeBroker();
		persistenceDir = Files.createTempDirectory("paho-soak-v3").toFile();
	}

	@After
	public void tearDown() throws Exception {
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
		if (proxy != null) {
			proxy.close();
		}
		Soak.delete(persistenceDir);
	}

	private static MqttConnectOptions options() {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(1000);
		options.setConnectionTimeout(10);
		options.setKeepAliveInterval(10);
		return options;
	}

	@Test
	public void testResourcesDoNotGrowOverRounds() throws Exception {
		final String scenario = "v3 soak";
		final String base = "paho/load/v3/" + Load.RUN + "/soak/";
		final int n = Load.SOAK_CLIENTS;
		final AtomicInteger reconnected = new AtomicInteger();
		final AtomicInteger received = new AtomicInteger();
		proxy = new DropProxy(Load.brokerHost(), Load.brokerPort());
		Soak.Idle baseline = Soak.idle(Load.SOAK_IDLE_SECONDS);

		List<MqttAsyncClient> soak = new ArrayList<MqttAsyncClient>();
		for (int i = 0; i < n; i++) {
			// half of the clients on the file persistence, to see files and descriptors
			MqttClientPersistence persistence = i % 2 == 0 ? new MemoryPersistence()
					: new MqttDefaultFilePersistence(persistenceDir.getAbsolutePath());
			MqttAsyncClient client = new MqttAsyncClient(proxy.getServerURI(), "load-v3-soak-" + Load.RUN + "-" + i, persistence);
			clients.add(client);
			client.setCallback(new MqttCallbackExtended() {
				public void connectionLost(Throwable cause) {
				}

				public void messageArrived(String topic, MqttMessage message) {
				}

				public void deliveryComplete(IMqttDeliveryToken token) {
				}

				public void connectComplete(boolean reconnect, String serverURI) {
					if (reconnect) {
						reconnected.incrementAndGet();
					}
				}
			});
			client.connect(options()).waitForCompletion(Load.TIMEOUT_MS);
			soak.add(client);
		}
		IMqttMessageListener listener = new IMqttMessageListener() {
			public void messageArrived(String topic, MqttMessage message) {
				received.incrementAndGet();
			}
		};

		Soak.Recorder recorder = new Soak.Recorder(Load.SOAK_ROUNDS);
		long start = System.nanoTime();
		for (int round = 0; round < Load.SOAK_ROUNDS; round++) {
			received.set(0);
			for (int i = 0; i < n; i++) {
				MqttAsyncClient client = soak.get(i);
				String topic = base + "r" + round + "/c" + i;
				client.subscribe(topic, 2, listener).waitForCompletion(Load.TIMEOUT_MS);
				for (int qos = 0; qos <= 2; qos++) {
					for (int m = 0; m < Load.SOAK_MESSAGES; m++) {
						client.publish(topic, Integer.toString(m).getBytes(StandardCharsets.UTF_8), qos, false)
								.waitForCompletion(Load.TIMEOUT_MS);
					}
				}
			}
			final int expected = 3 * n * Load.SOAK_MESSAGES;
			Assert.assertTrue("round " + round + ": received " + received.get() + " of " + expected,
					Load.await(() -> received.get() >= expected, Load.TIMEOUT_MS));
			for (int i = 0; i < n; i++) {
				soak.get(i).unsubscribe(base + "r" + round + "/c" + i).waitForCompletion(Load.TIMEOUT_MS);
			}

			final int target = n * (round + 1);
			proxy.dropAll();
			Assert.assertTrue("round " + round + ": reconnected " + reconnected.get() + " of " + target,
					Load.await(() -> reconnected.get() >= target, Load.RECONNECT_BOUND_MS));
			recorder.sample(round, Load.files(persistenceDir));
		}
		long elapsed = System.nanoTime() - start;
		Soak.Idle idle = Soak.idle(Load.SOAK_IDLE_SECONDS);

		recorder.report(scenario, n, TimeUnit.NANOSECONDS.toSeconds(elapsed), baseline, idle);
		recorder.assertNoGrowth(n);
	}

	@Test
	public void testIoPerMessage() throws Exception {
		MqttAsyncClient client = new MqttAsyncClient(Load.BROKER, "load-v3-io-" + Load.RUN, new MemoryPersistence());
		clients.add(client);
		MqttConnectOptions options = options();
		options.setAutomaticReconnect(false);
		client.connect(options).waitForCompletion(Load.TIMEOUT_MS);
		String topic = "paho/load/v3/" + Load.RUN + "/io";
		byte[] payload = new byte[100];
		// warm-up: the first packets of each kind load classes from the jar, which counts as I/O
		final AtomicInteger warm = new AtomicInteger();
		String warmup = topic + "/warmup";
		client.subscribe(warmup, 2, new IMqttMessageListener() {
			public void messageArrived(String t, MqttMessage message) {
				warm.incrementAndGet();
			}
		}).waitForCompletion(Load.TIMEOUT_MS);
		for (int qos = 0; qos <= 2; qos++) {
			for (int m = 0; m < 20; m++) {
				client.publish(warmup, payload, qos, false).waitForCompletion(Load.TIMEOUT_MS);
			}
		}
		Load.await(() -> warm.get() >= 60, Load.TIMEOUT_MS);
		client.unsubscribe(warmup).waitForCompletion(Load.TIMEOUT_MS);
		for (int qos = 0; qos <= 2; qos++) {
			long[] before = Load.io();
			int messages = Load.SOAK_MESSAGES * 20;
			for (int m = 0; m < messages; m++) {
				client.publish(topic, payload, qos, false).waitForCompletion(Load.TIMEOUT_MS);
			}
			Soak.reportIo("v3 I/O per message QoS " + qos, before, Load.io(), messages);
		}
		// with a subscription on the topic, each message also comes back to the client
		final AtomicInteger echoes = new AtomicInteger();
		client.subscribe(topic, 2, new IMqttMessageListener() {
			public void messageArrived(String t, MqttMessage message) {
				echoes.incrementAndGet();
			}
		}).waitForCompletion(Load.TIMEOUT_MS);
		for (int qos = 0; qos <= 2; qos++) {
			echoes.set(0);
			final int messages = Load.SOAK_MESSAGES * 20;
			long[] before = Load.io();
			for (int m = 0; m < messages; m++) {
				client.publish(topic, payload, qos, false).waitForCompletion(Load.TIMEOUT_MS);
			}
			Load.await(() -> echoes.get() >= messages, Load.TIMEOUT_MS);
			Thread.sleep(200);
			Load.report("v3 echo QoS " + qos, "%d messages arrived for %d published", echoes.get(), messages);
			Soak.reportIo("v3 I/O per echoed message QoS " + qos, before, Load.io(), messages);
		}
	}
}
