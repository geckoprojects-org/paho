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

import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * A QoS 1 message that arrives while the client has neither a callback nor a message listener,
 * e.g. from a persistent session right after the CONNACK, is still acknowledged. Runs without an
 * external broker.
 */
public class UnhandledMessageTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		broker.setPublishOnConnect("queued/message");
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

	private void connectWithoutCallback(boolean manualAcks) throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "unhandled", new MemoryPersistence());
		client.setManualAcks(manualAcks);
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(false);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));
	}

	private void assertAcknowledged() throws InterruptedException {
		Assert.assertTrue("no PUBACK for a message without callback or listener", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return broker.getPubAckCount() >= 1;
			}
		}, 5000));
	}

	@Test
	public void testMessageWithoutCallbackIsAcknowledged() throws Exception {
		connectWithoutCallback(false);
		assertAcknowledged();
	}

	@Test
	public void testMessageWithoutCallbackIsAcknowledgedWithManualAcks() throws Exception {
		// The application never sees the message, so it cannot acknowledge it itself.
		connectWithoutCallback(true);
		assertAcknowledged();
	}
}
