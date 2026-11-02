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

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientException;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The application ends the automatic reconnect cycle with disconnect() and close(). Runs without
 * an external broker.
 */
public class ReconnectCycleTest {

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

	private MqttConnectionOptions options() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(2000);
		options.setConnectionTimeout(1);
		options.setKeepAliveInterval(30);
		return options;
	}

	/** Connects, then lets the server go silent and drops the connection: the cycle is running. */
	private void startReconnectCycle(String clientId) throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), clientId, new MemoryPersistence());
		client.connect(options()).waitForCompletion(5000);
		broker.setAnswering(false);
		broker.dropConnections();
		Assert.assertTrue("reconnect cycle did not start", waitFor(new Condition() {
			public boolean met() {
				return broker.getConnectCount() >= 2;
			}
		}, 10000));
	}

	@Test
	public void testDisconnectEndsReconnectCycle() throws Exception {
		// Between two attempts the client is disconnected and the reconnect timer is waiting; a
		// disconnect() then fails (not connected), but must end the cycle.
		client = new MqttAsyncClient(broker.getServerURI(), "cycle-disconnect", new MemoryPersistence());
		client.connect(options()).waitForCompletion(5000);
		broker.setRefusing(true);
		Assert.assertTrue("connection loss not noticed", waitFor(new Condition() {
			public boolean met() {
				return !client.isConnected();
			}
		}, 5000));
		Thread.sleep(1500); // the reconnect cycle is running, attempts are refused
		try {
			client.disconnect();
		} catch (MqttException e) {
			// not connected: the cycle has to end anyway
		}

		broker.setRefusing(false);
		Thread.sleep(6000);
		Assert.assertEquals("reconnect attempts after disconnect()", 1, broker.getConnectCount());
		Assert.assertFalse(client.isConnected());
	}

	@Test
	public void testDisconnectDuringAttemptEndsReconnectCycle() throws Exception {
		startReconnectCycle("cycle-disconnect-attempt");
		try {
			client.disconnect();
		} catch (MqttException e) {
			// not connected: the cycle has to end anyway
		}
		Thread.sleep(2500); // an attempt under way when disconnect() was called fails meanwhile
		int connects = broker.getConnectCount();

		broker.setAnswering(true);
		Thread.sleep(6000);
		Assert.assertEquals("reconnect attempts after disconnect()", connects, broker.getConnectCount());
		Assert.assertFalse(client.isConnected());
	}

	@Test
	public void testCloseEndsReconnectThread() throws Exception {
		final String clientId = "cycle-close";
		startReconnectCycle(clientId);
		try {
			client.close(true);
		} catch (MqttException e) {
			// close() refuses while a connect attempt is under way; the cycle has to end anyway
			Assert.assertEquals(MqttClientException.REASON_CODE_CONNECT_IN_PROGRESS, e.getReasonCode());
		}

		Assert.assertTrue("reconnect thread still alive after close()", waitFor(new Condition() {
			public boolean met() {
				for (Thread thread : Thread.getAllStackTraces().keySet()) {
					if (thread.getName().equals("MQTT Reconnect: " + clientId)) {
						return false;
					}
				}
				return true;
			}
		}, 5000));
		// The attempt that was under way fails within connectionTimeout; then close() goes through.
		long deadline = System.currentTimeMillis() + 5000;
		while (true) {
			try {
				client.close(true);
				break;
			} catch (MqttException e) {
				if (e.getReasonCode() != MqttClientException.REASON_CODE_CONNECT_IN_PROGRESS || System.currentTimeMillis() > deadline) {
					throw e;
				}
				Thread.sleep(200);
			}
		}
		client = null;
	}

	@Test
	public void testReconnectWithoutConnect() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "cycle-never-connected", new MemoryPersistence());
		try {
			client.reconnect();
			Assert.fail("reconnect() without a previous connect() must fail");
		} catch (MqttException e) {
			Assert.assertEquals(MqttClientException.REASON_CODE_CLIENT_NOT_CONNECTED, e.getReasonCode());
		}
	}

	private interface Condition {
		boolean met();
	}

	private static boolean waitFor(Condition condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			if (condition.met()) {
				return true;
			}
			Thread.sleep(100);
		}
		return condition.met();
	}
}
