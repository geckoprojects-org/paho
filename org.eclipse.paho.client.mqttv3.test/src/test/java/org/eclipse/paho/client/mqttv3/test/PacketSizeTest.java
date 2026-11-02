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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Inbound packets are checked against the maximum inbound packet size before the client allocates
 * memory for them. Runs without an external broker.
 */
public class PacketSizeTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;
	private final AtomicReference<Throwable> lost = new AtomicReference<Throwable>();
	private final AtomicInteger arrived = new AtomicInteger();

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

	private void connect(MqttConnectOptions options) throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "packetsize", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void connectionLost(Throwable cause) {
				lost.set(cause);
			}

			public void messageArrived(String topic, MqttMessage message) {
				arrived.incrementAndGet();
			}

			public void deliveryComplete(IMqttDeliveryToken token) {
			}
		});
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));
	}

	private void assertLostWith(int reasonCode) throws InterruptedException {
		Assert.assertTrue("connection not closed", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return lost.get() != null;
			}
		}, 5000));
		Assert.assertEquals(reasonCode, ((MqttException) lost.get()).getReasonCode());
	}

	@Test
	public void testDefaultIsOneMebibyte() {
		Assert.assertEquals(1024 * 1024, new MqttConnectOptions().getMaxInboundPacketSize());
	}

	@Test
	public void testForgedLengthClosesConnectionWithoutReadingTheBody() throws Exception {
		connect(new MqttConnectOptions());
		// PUBLISH announcing 268,435,455 bytes, the protocol maximum; no body follows. Without the
		// check the client allocates 256 MiB and waits for the body.
		broker.sendRaw(new byte[] { 0x30, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F });
		assertLostWith(MqttException.REASON_CODE_INCOMING_PACKET_TOO_LARGE);
	}

	@Test
	public void testPacketAboveTheLimitClosesConnection() throws Exception {
		connect(new MqttConnectOptions());
		broker.publish("big", new byte[1024 * 1024], 0);
		assertLostWith(MqttException.REASON_CODE_INCOMING_PACKET_TOO_LARGE);
		Assert.assertEquals(0, arrived.get());
	}

	@Test
	public void testPacketWithinARaisedLimitArrives() throws Exception {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMaxInboundPacketSize(2 * 1024 * 1024);
		connect(options);
		broker.publish("big", new byte[1024 * 1024], 0);
		Assert.assertTrue("message not delivered", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return arrived.get() == 1;
			}
		}, 5000));
		Assert.assertNull(lost.get());
	}

	@Test
	public void testNoLimit() throws Exception {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMaxInboundPacketSize(0);
		connect(options);
		broker.publish("big", new byte[1024 * 1024], 0);
		Assert.assertTrue("message not delivered", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return arrived.get() == 1;
			}
		}, 5000));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testNegativeLimitIsRejected() {
		new MqttConnectOptions().setMaxInboundPacketSize(-1);
	}

	@Test
	public void testVariableByteIntegerOfFourBytes() throws IOException {
		Assert.assertEquals(268435455, MqttWireMessage.readMBI(stream(0xFF, 0xFF, 0xFF, 0x7F)).getValue());
	}

	@Test(expected = IOException.class)
	public void testVariableByteIntegerOfFiveBytesIsRejected() throws IOException {
		MqttWireMessage.readMBI(stream(0x80, 0x80, 0x80, 0x80, 0x01));
	}

	private static DataInputStream stream(int... bytes) {
		byte[] b = new byte[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			b[i] = (byte) bytes[i];
		}
		return new DataInputStream(new ByteArrayInputStream(b));
	}
}
