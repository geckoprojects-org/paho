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

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Topic aliases belong to one network connection. A publish sent with an alias and resent after a
 * reconnect carries its topic again, since the server does not know the alias on the new
 * connection.
 */
public class TopicAliasResendTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		broker.setTopicAliasMaximum(5);
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

	@Test(timeout = 60000)
	public void testResentPublishCarriesItsTopic() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "aliasresend", new MemoryPersistence());
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(false);
		options.setAutomaticReconnect(true);
		options.setAutomaticReconnectDelay(1, 1);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		client.publish("t", new byte[] { 1 }, 1, false).waitForCompletion(TestTiming.scaled(5000));
		broker.setAckingPublishes(false);
		client.publish("t", new byte[] { 2 }, 1, false);
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return broker.getPublishedTopics().size() == 2;
			}
		}, 5000));
		Assert.assertEquals("second publish uses the alias", "|1", broker.getPublishedTopics().get(1));

		// the connection is lost before the PUBACK; the publish is sent again on the new one
		broker.setAckingPublishes(true);
		broker.dropConnections();
		Assert.assertTrue("publish not sent again", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return broker.getPublishedTopics().size() >= 3;
			}
		}, 10000));
		List<String> topics = broker.getPublishedTopics();
		Assert.assertEquals(topics.toString(), 3, topics.size());
		Assert.assertTrue("resent without its topic: " + topics, topics.get(2).startsWith("t|"));
	}
}
