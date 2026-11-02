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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Restoring subscriptions after an automatic reconnect without session (option
 * automaticResubscribe), against a server whose behaviour the test controls. Runs without an
 * external broker.
 */
public class ResubscribeTest {

	private static final String TOPIC = "paho/test/reconnect";

	private FakeMqttBroker broker;
	private MqttClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
	}

	@After
	public void tearDown() throws Exception {
		if (client != null) {
			try {
				client.disconnectForcibly(0, 100);
			} catch (Exception e) {
				// not connected
			}
			client.close(true);
		}
		broker.close();
	}

	private MqttConnectionOptions options() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(2000);
		options.setConnectionTimeout(1);
		options.setKeepAliveInterval(30);
		return options;
	}

	/**
	 * After an automatic reconnect without a session the client re-subscribes, and messages reach
	 * the listener registered with the original subscribe call.
	 */
	@Test
	public void testSubscriptionsRestoredAfterReconnectWithoutSession() throws Exception {
		client = new MqttClient(broker.getServerURI(), "reconnect-resubscribe", new MemoryPersistence());
		client.connect(options());
		final CountDownLatch received = new CountDownLatch(1);
		client.subscribe(TOPIC, 0, new IMqttMessageListener() {
			public void messageArrived(String topic, MqttMessage message) {
				received.countDown();
			}
		});
		Assert.assertEquals(Collections.singletonList(TOPIC), broker.getSubscriptions(1));

		broker.dropConnections();

		Assert.assertTrue("Subscription was not restored after the reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return client.isConnected() && broker.getSubscriptions(2).contains(TOPIC);
			}
		}, 10000));
		broker.publish(TOPIC, "after reconnect");
		Assert.assertTrue("Message listener did not receive after the reconnect",
				received.await(TestTiming.scaled(5000), TimeUnit.MILLISECONDS));
	}

	/** With automaticResubscribe disabled, the application stays in charge of its subscriptions. */
	@Test
	public void testNoResubscribeWhenDisabled() throws Exception {
		client = new MqttClient(broker.getServerURI(), "reconnect-no-resubscribe", new MemoryPersistence());
		MqttConnectionOptions options = options();
		options.setAutomaticResubscribe(false);
		client.connect(options);
		client.subscribe(TOPIC, 0);

		broker.dropConnections();

		Assert.assertTrue("Client did not reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return client.isConnected() && broker.getConnectCount() == 2;
			}
		}, 10000));
		// restoreSubscriptions() runs before connectComplete; its SUBSCRIBE would arrive right after
		TestTiming.observe(1000);
		Assert.assertTrue(broker.getSubscriptions(2).isEmpty());
	}

	/** A deliberate unsubscribe is not undone by a later reconnect. */
	@Test
	public void testUnsubscribedTopicNotRestored() throws Exception {
		client = new MqttClient(broker.getServerURI(), "reconnect-unsubscribed", new MemoryPersistence());
		client.connect(options());
		client.subscribe(TOPIC, 0);
		client.subscribe(TOPIC + "/other", 0);
		client.unsubscribe(TOPIC);

		broker.dropConnections();

		Assert.assertTrue("Subscription was not restored after the reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return client.isConnected() && !broker.getSubscriptions(2).isEmpty();
			}
		}, 10000));
		Assert.assertEquals(Collections.singletonList(TOPIC + "/other"), broker.getSubscriptions(2));
	}
}
