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

import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The application ends the automatic reconnect cycle with disconnect() and close(). Runs without
 * an external broker.
 */
public class ReconnectCycleTest {

	private static final int CONNECTION_TIMEOUT_S = 1;
	private static final int MAX_RECONNECT_DELAY_MS = 2000;
	/** The client's first reconnect delay (MqttAsyncClient starts the backoff at 1 s). */
	private static final int FIRST_RECONNECT_DELAY_MS = 1000;
	/**
	 * Longest a connect attempt without CONNACK takes: the deadline of connectionTimeout plus up to
	 * one read timeout of the receiver, which checks the deadline.
	 */
	private static final long ATTEMPT_MAX_MS = CONNECTION_TIMEOUT_S * 1000L + 1000;
	/**
	 * Window in which a reconnect cycle that is still running would show a new CONNECT: the longest
	 * backoff plus one attempt, with a margin.
	 */
	private static final long QUIET_WINDOW_MS = MAX_RECONNECT_DELAY_MS + ATTEMPT_MAX_MS + 2000;

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

	private MqttConnectOptions options() {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		options.setCleanSession(true);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(MAX_RECONNECT_DELAY_MS);
		options.setConnectionTimeout(CONNECTION_TIMEOUT_S);
		options.setKeepAliveInterval(30);
		return options;
	}

	/** Connects, then lets the server go silent and drops the connection: the cycle is running. */
	private void startReconnectCycle(String clientId) throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), clientId, new MemoryPersistence());
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		broker.setAnswering(false);
		broker.dropConnections();
		Assert.assertTrue("reconnect cycle did not start", TestTiming.waitFor(new TestTiming.Condition() {
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
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		broker.setRefusing(true);
		Assert.assertTrue("connection loss not noticed", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return !client.isConnected();
			}
		}, 5000));
		TestTiming.observe(FIRST_RECONNECT_DELAY_MS + 500); // the reconnect cycle is running, attempts are refused
		try {
			client.disconnect();
		} catch (MqttException e) {
			// not connected: the cycle has to end anyway
		}

		broker.setRefusing(false);
		TestTiming.observe(QUIET_WINDOW_MS);
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
		TestTiming.observe(ATTEMPT_MAX_MS + 500); // an attempt under way when disconnect() was called fails meanwhile
		int connects = broker.getConnectCount();

		broker.setAnswering(true);
		TestTiming.observe(QUIET_WINDOW_MS);
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
			Assert.assertEquals(MqttException.REASON_CODE_CONNECT_IN_PROGRESS, e.getReasonCode());
		}

		Assert.assertTrue("reconnect thread still alive after close()", TestTiming.waitFor(new TestTiming.Condition() {
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
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TestTiming.scaled(ATTEMPT_MAX_MS + 8000));
		while (true) {
			try {
				client.close(true);
				break;
			} catch (MqttException e) {
				if (e.getReasonCode() != MqttException.REASON_CODE_CONNECT_IN_PROGRESS || System.nanoTime() - deadline > 0) {
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
			Assert.assertEquals(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED, e.getReasonCode());
		}
	}
}
