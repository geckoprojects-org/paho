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
import java.util.concurrent.atomic.AtomicReference;

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

/** A PUBLISH that carries only a topic alias the server set before arrives on that topic. */
public class IncomingTopicAliasTest {

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
				client.disconnectForcibly(0, 100, false);
			} catch (MqttException e) {
				// not connected
			}
			client.close(true);
		}
		broker.close();
	}

	@Test
	public void testAliasOnlyPublishIsResolved() throws Exception {
		final List<String> topics = new CopyOnWriteArrayList<String>();
		final AtomicReference<MqttException> lost = new AtomicReference<MqttException>();
		client = new MqttAsyncClient(broker.getServerURI(), "incomingalias", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
				lost.set(disconnectResponse.getException());
			}

			public void mqttErrorOccurred(MqttException exception) {
			}

			public void messageArrived(String topic, MqttMessage message) {
				topics.add(topic);
			}

			public void deliveryComplete(IMqttToken token) {
			}

			public void connectComplete(boolean reconnect, String serverURI) {
			}

			public void authPacketArrived(int reasonCode, MqttProperties properties) {
			}
		});
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setTopicAliasMaximum(Integer.valueOf(10));
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		// PUBLISH QoS 0 to "t" setting topic alias 1, then one with an empty topic and alias 1
		broker.sendRaw(new byte[] { 0x30, 0x08, 0x00, 0x01, 't', 0x03, 0x23, 0x00, 0x01, 'x' });
		broker.sendRaw(new byte[] { 0x30, 0x07, 0x00, 0x00, 0x03, 0x23, 0x00, 0x01, 'y' });
		Assert.assertTrue("arrived: " + topics + ", lost: " + lost.get(), TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return topics.size() == 2;
			}
		}, 5000));
		Assert.assertEquals("t", topics.get(1));
		Assert.assertNull(lost.get());
	}
}
