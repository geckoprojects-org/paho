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
 * The in-flight window is free again as soon as a QoS 1 publish has completed, so an application
 * that publishes one message after the other never gets "Too many publishes in progress". Runs
 * without an external broker.
 */
public class InflightTest {

	private static final int MESSAGES = 300;

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
	public void testSequentialPublishesFitAWindowOfOne() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "inflight", new MemoryPersistence());
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setMaxInflight(1);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		for (int i = 0; i < MESSAGES; i++) {
			try {
				client.publish("inflight/" + i, new byte[] { 1 }, 1, false).waitForCompletion(TestTiming.scaled(5000));
			} catch (MqttException e) {
				Assert.fail("publish " + i + " failed although the previous one had completed: " + e);
			}
			Assert.assertEquals("in-flight count after publish " + i + " completed", 0, client.getInFlightMessageCount());
		}
	}
}
