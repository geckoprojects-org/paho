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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClientException;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttDataTypes;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Inbound packets are checked against the Maximum Packet Size before the client allocates memory
 * for them, and the limit is on by default. Runs without an external broker.
 */
public class PacketSizeTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;
	private final AtomicReference<MqttException> lost = new AtomicReference<MqttException>();
	private final AtomicInteger arrived = new AtomicInteger();

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
	}

	@After
	public void tearDown() throws Exception {
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

	private void connect(MqttConnectionOptions options) throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "packetsize", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
				lost.set(disconnectResponse.getException());
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
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));
	}

	private void assertLostWith(int reasonCode) throws InterruptedException {
		Assert.assertTrue("connection not closed", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return lost.get() != null;
			}
		}, 5000));
		Assert.assertEquals(reasonCode, lost.get().getReasonCode());
	}

	private void assertArrived() throws InterruptedException {
		Assert.assertTrue("message not delivered", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return arrived.get() == 1;
			}
		}, 5000));
	}

	@Test
	public void testDefaultIsOneMebibyteAndSentInConnect() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		Assert.assertEquals(Long.valueOf(1024 * 1024), options.getMaximumPacketSize());
		Assert.assertEquals(Long.valueOf(1024 * 1024), options.getConnectionProperties().getMaximumPacketSize());
	}

	@Test
	public void testForgedLengthClosesConnectionWithoutReadingTheBody() throws Exception {
		connect(new MqttConnectionOptions());
		// PUBLISH announcing 268,435,455 bytes, the protocol maximum; no body follows. Without the
		// check the client allocates 256 MiB and waits for the body.
		broker.sendRaw(new byte[] { 0x30, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x7F });
		assertLostWith(MqttClientException.REASON_CODE_INCOMING_PACKET_TOO_LARGE);
	}

	@Test
	public void testPacketAboveTheLimitClosesConnection() throws Exception {
		connect(new MqttConnectionOptions());
		broker.publish("big", new byte[1024 * 1024], 0);
		assertLostWith(MqttClientException.REASON_CODE_INCOMING_PACKET_TOO_LARGE);
		Assert.assertEquals(0, arrived.get());
	}

	@Test
	public void testPacketWithinARaisedLimitArrives() throws Exception {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setMaximumPacketSize(Long.valueOf(2 * 1024 * 1024));
		connect(options);
		broker.publish("big", new byte[1024 * 1024], 0);
		assertArrived();
		Assert.assertNull(lost.get());
	}

	@Test
	public void testNoLimit() throws Exception {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setMaximumPacketSize(null);
		connect(options);
		broker.publish("big", new byte[1024 * 1024], 0);
		assertArrived();
	}

	@Test
	public void testOwnLimitDoesNotLimitOutgoingPackets() throws Exception {
		// The client's Maximum Packet Size limits what it receives; what it sends is limited by
		// the server's (none here). Upstream mixed the two up.
		connect(new MqttConnectionOptions());
		client.publish("big", new byte[2 * 1024 * 1024], 0, false).waitForCompletion(TestTiming.scaled(5000));
		Assert.assertNull(lost.get());
	}

	@Test
	public void testVariableByteIntegerOfFourBytes() throws IOException {
		Assert.assertEquals(268435455, MqttDataTypes.readVariableByteInteger(stream(0xFF, 0xFF, 0xFF, 0x7F)).getValue());
	}

	@Test(expected = IOException.class)
	public void testVariableByteIntegerOfFiveBytesIsRejected() throws IOException {
		MqttDataTypes.readVariableByteInteger(stream(0x80, 0x80, 0x80, 0x80, 0x01));
	}

	private static DataInputStream stream(int... bytes) {
		byte[] b = new byte[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			b[i] = (byte) bytes[i];
		}
		return new DataInputStream(new ByteArrayInputStream(b));
	}
}
