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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Messages received through a shared subscription ({@code $share/<group>/<filter>}) reach the
 * message listener of the subscription. Runs without an external broker.
 */
public class SharedSubscriptionTest {

	private static final long TIMEOUT = 5000;

	private FakeMqttBroker broker;
	private MqttAsyncClient client;
	/** Messages that reached {@link MqttCallback#messageArrived}, as "topic". */
	private final List<String> unrouted = new CopyOnWriteArrayList<String>();
	private volatile CountDownLatch synced;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		client = new MqttAsyncClient(broker.getServerURI(), "shared", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void connectionLost(Throwable cause) {
			}

			public void messageArrived(String topic, MqttMessage message) {
				unrouted.add(topic);
			}

			public void deliveryComplete(IMqttDeliveryToken token) {
			}
		});
		MqttConnectOptions options = new MqttConnectOptions();
		options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
		client.connect(options).waitForCompletion(TestTiming.scaled(TIMEOUT));
		subscribe("sync", new IMqttMessageListener() {
			public void messageArrived(String topic, MqttMessage message) {
				synced.countDown();
			}
		});
	}

	@After
	public void tearDown() throws Exception {
		try {
			client.disconnectForcibly(0, 100);
		} catch (MqttException e) {
			// not connected
		}
		client.close(true);
		broker.close();
	}

	private void subscribe(String topicFilter, IMqttMessageListener listener) throws MqttException {
		client.subscribe(topicFilter, 0, listener).waitForCompletion(TestTiming.scaled(TIMEOUT));
	}

	/** Returns once every message published before has been delivered: one callback thread delivers them in order. */
	private void sync() throws Exception {
		synced = new CountDownLatch(1);
		broker.publish("sync", "s", 0);
		Assert.assertTrue("sync message not delivered", synced.await(TestTiming.scaled(TIMEOUT), TimeUnit.MILLISECONDS));
	}

	/** Records the topics of the messages it receives. */
	private static final class Recorder implements IMqttMessageListener {
		final List<String> topics = new CopyOnWriteArrayList<String>();

		public void messageArrived(String topic, MqttMessage message) {
			topics.add(topic);
		}
	}

	@Test
	public void testSharedSubscription() throws Exception {
		Recorder listener = new Recorder();
		subscribe("$share/g/a/b", listener);

		broker.publish("a/b", "x", 0);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
		Assert.assertEquals(Collections.emptyList(), unrouted);
	}

	@Test
	public void testSharedSubscriptionWithWildcard() throws Exception {
		Recorder listener = new Recorder();
		subscribe("$share/g/a/+", listener);

		broker.publish("a/b", "x", 0);
		broker.publish("c/d", "y", 0);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
		Assert.assertEquals(Collections.singletonList("c/d"), unrouted);
	}

	@Test
	public void testSharedAndPlainSubscriptionOfOneFilter() throws Exception {
		Recorder shared = new Recorder();
		Recorder plain = new Recorder();
		subscribe("$share/g/a/b", shared);
		subscribe("a/b", plain);

		// neither replaces the other
		broker.publish("a/b", "x", 0);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), shared.topics);
		Assert.assertEquals(Collections.singletonList("a/b"), plain.topics);

		// nor removes it
		client.unsubscribe("$share/g/a/b").waitForCompletion(TestTiming.scaled(TIMEOUT));
		broker.publish("a/b", "y", 0);
		sync();
		Assert.assertEquals(1, shared.topics.size());
		Assert.assertEquals(2, plain.topics.size());
	}
}
