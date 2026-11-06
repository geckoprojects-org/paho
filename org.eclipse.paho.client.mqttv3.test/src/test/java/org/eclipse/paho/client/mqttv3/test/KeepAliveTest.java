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
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Keep-alive pings with the default ping sender, which schedules on the monotonic clock
 * (MonotonicTimer). That the schedule does not follow the wall clock is a property of
 * ScheduledThreadPoolExecutor and cannot be tested without changing the system time; this test
 * makes sure the pings keep coming. Runs without an external broker.
 */
public class KeepAliveTest {

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
				client.close(true);
			} catch (MqttException e) {
				// already closed
			}
		}
		broker.close();
	}

	@Test
	public void testPingsWithDefaultPingSender() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "keep-alive", new MemoryPersistence());
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(true);
		options.setKeepAliveInterval(1);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		Assert.assertTrue("no PINGREQ within the keep-alive interval", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return broker.getPingCount() >= 2;
			}
		}, 5000));
		Assert.assertTrue(client.isConnected());
	}
}
