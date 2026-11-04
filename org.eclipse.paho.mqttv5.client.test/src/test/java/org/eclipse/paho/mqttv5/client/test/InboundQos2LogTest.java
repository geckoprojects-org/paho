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
package org.eclipse.paho.mqttv5.client.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Receiving QoS 2 messages logs nothing at INFO, the default level of java.util.logging: a line
 * per message would grow the application's log with the message rate.
 */
public class InboundQos2LogTest {

	/** Kept in a field: java.util.logging holds its loggers weakly. */
	private final Logger pahoLogger = Logger.getLogger("org.eclipse.paho.mqttv5");
	private final List<LogRecord> records = new CopyOnWriteArrayList<LogRecord>();
	private final Handler handler = new Handler() {
		@Override
		public void publish(LogRecord record) {
			if (record.getLevel().intValue() >= Level.INFO.intValue()) {
				records.add(record);
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}
	};
	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		pahoLogger.addHandler(handler);
		broker = new FakeMqttBroker();
	}

	@After
	public void tearDown() throws Exception {
		pahoLogger.removeHandler(handler);
		if (client != null) {
			try {
				client.disconnectForcibly(0, 100, false);
			} catch (MqttException e) {
				// not connected
			}
			client.close(true);
		}
		broker.close();
	}

	@Test
	public void testInboundQos2IsNotLoggedAtInfo() throws Exception {
		final AtomicInteger arrived = new AtomicInteger();
		client = new MqttAsyncClient(broker.getServerURI(), "qos2log", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
			}

			public void mqttErrorOccurred(MqttException exception) {
			}

			public void messageArrived(String topic, MqttMessage message) {
				arrived.incrementAndGet();
			}

			public void deliveryComplete(IMqttToken token) {
			}

			public void connectComplete(boolean reconnect, String serverURI) {
			}

			public void authPacketArrived(int reasonCode, MqttProperties properties) {
			}
		});
		client.connect(new MqttConnectionOptions()).waitForCompletion(TestTiming.scaled(5000));

		// PUBLISH QoS 2 (topic "t", packet id 1, no properties, payload "x"), then its PUBREL
		broker.sendRaw(new byte[] { 0x34, 0x07, 0x00, 0x01, 't', 0x00, 0x01, 0x00, 'x' });
		Assert.assertTrue("message not delivered", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return arrived.get() == 1;
			}
		}, 5000));
		broker.sendRaw(new byte[] { 0x62, 0x02, 0x00, 0x01 });
		TestTiming.observe(500);
		Assert.assertTrue("logged at INFO or above: " + records, records.isEmpty());
	}
}
