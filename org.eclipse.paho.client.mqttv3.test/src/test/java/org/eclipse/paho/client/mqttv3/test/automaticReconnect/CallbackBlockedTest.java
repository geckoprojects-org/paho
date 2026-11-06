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
package org.eclipse.paho.client.mqttv3.test.automaticReconnect;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * A message listener that waits for a client operation when the connection is lost must not keep
 * the client from reconnecting. Runs without an external broker.
 */
public class CallbackBlockedTest {

	private static final int KEEP_ALIVE_S = 1;

	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
	}

	@After
	public void tearDown() throws Exception {
		if (client != null) {
			try {
				client.disconnectForcibly(0, 100);
			} catch (MqttException e) {
				// not connected
			}
			client.close(true);
		}
		broker.close();
	}

	@Test
	public void testReconnectWhileListenerWaitsForPublish() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "callback-blocked", new MemoryPersistence());
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(2000);
		options.setConnectionTimeout(1);
		options.setKeepAliveInterval(KEEP_ALIVE_S);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		final CountDownLatch publishReturned = new CountDownLatch(1);
		final AtomicReference<Throwable> publishFailure = new AtomicReference<Throwable>();
		client.subscribe("in", 0, new IMqttMessageListener() {
			public void messageArrived(String topic, MqttMessage message) {
				// The server goes silent: no PUBACK, no PINGRESP. The keep-alive check takes the
				// connection down while this listener waits for its publish, without a timeout.
				broker.setAnswering(false);
				try {
					client.publish("out", new byte[] { 1 }, 1, false).waitForCompletion();
				} catch (Throwable e) {
					publishFailure.set(e);
				} finally {
					publishReturned.countDown();
				}
			}
		}).waitForCompletion(TestTiming.scaled(5000));
		broker.publish("in", "x");

		// Keep-alive timeout (1.5 x keep-alive) plus the ping check interval, with a margin.
		Assert.assertTrue("listener still waits for its publish after the connection was lost",
				publishReturned.await(TestTiming.scaled(KEEP_ALIVE_S * 3000L + 5000), TimeUnit.MILLISECONDS));
		Assert.assertTrue("publish did not fail: " + publishFailure.get(), publishFailure.get() instanceof MqttException);

		broker.setAnswering(true);
		Assert.assertTrue("client did not reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return client.isConnected() && broker.getConnectCount() >= 2;
			}
		}, 15000));
	}
}
