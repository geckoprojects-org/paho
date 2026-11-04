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

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.internal.ClientComms;
import org.eclipse.paho.mqttv5.client.internal.CommsCallback;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * A subscribe of a filter that is subscribed already replaces its message listener instead of
 * keeping the old one, and removing listeners works with several subscription identifiers.
 */
public class MessageListenerTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		client = new MqttAsyncClient(broker.getServerURI(), "listeners", new MemoryPersistence());
	}

	@After
	public void tearDown() throws Exception {
		try {
			client.disconnectForcibly(0, 100, false);
		} catch (MqttException e) {
			// not connected
		}
		client.close(true);
		broker.close();
	}

	private static Object field(Object owner, Class<?> type, String name) throws Exception {
		Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private CommsCallback callback() throws Exception {
		ClientComms comms = (ClientComms) field(client, MqttAsyncClient.class, "comms");
		return (CommsCallback) field(comms, ClientComms.class, "callback");
	}

	private int size(String map) throws Exception {
		return ((Map<?, ?>) field(callback(), CommsCallback.class, map)).size();
	}

	private static IMqttMessageListener listener(final AtomicInteger counter) {
		return new IMqttMessageListener() {
			public void messageArrived(String topic, MqttMessage message) {
				counter.incrementAndGet();
			}
		};
	}

	@Test
	public void testResubscribeReplacesTheListener() throws Exception {
		client.connect(new MqttConnectionOptions()).waitForCompletion(TestTiming.scaled(5000));
		AtomicInteger old = new AtomicInteger();
		for (int i = 0; i < 50; i++) {
			// as an application that subscribes again in connectComplete
			client.subscribe(new MqttSubscription("t", 1), listener(old)).waitForCompletion(TestTiming.scaled(5000));
		}
		final AtomicInteger current = new AtomicInteger();
		client.subscribe(new MqttSubscription("t", 1), listener(current)).waitForCompletion(TestTiming.scaled(5000));
		Assert.assertEquals("listeners kept", 1, size("callbackMap"));
		Assert.assertEquals("subscription identifiers kept", 1, size("subscriptionIdMap"));

		broker.publish("t", "x", 0);
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return current.get() == 1;
			}
		}, 5000));
		Assert.assertEquals("an old listener was called", 0, old.get());
	}

	@Test
	public void testRemoveByTopicWithSeveralSubscriptionIdentifiers() throws Exception {
		CommsCallback callback = callback();
		IMqttMessageListener listener = listener(new AtomicInteger());
		callback.setMessageListener(Integer.valueOf(10), "a", listener);
		callback.setMessageListener(Integer.valueOf(20), "b", listener);
		callback.setMessageListener(Integer.valueOf(30), "c", listener);
		callback.removeMessageListener("a");
		Assert.assertFalse(callback.doesSubscriptionIdentifierExist(10));
		Assert.assertTrue(callback.doesSubscriptionIdentifierExist(20));
		Assert.assertEquals(2, size("callbackMap"));
		Assert.assertEquals(2, size("callbackTopicMap"));
	}

	@Test
	public void testRemoveBySubscriptionIdentifier() throws Exception {
		CommsCallback callback = callback();
		IMqttMessageListener listener = listener(new AtomicInteger());
		callback.setMessageListener(Integer.valueOf(10), "a", listener);
		callback.setMessageListener(Integer.valueOf(20), "b", listener);
		callback.setMessageListener(Integer.valueOf(30), "c", listener);
		callback.removeMessageListener(Integer.valueOf(20));
		Assert.assertFalse(callback.doesSubscriptionIdentifierExist(20));
		Assert.assertTrue(callback.doesSubscriptionIdentifierExist(10));
		Assert.assertTrue(callback.doesSubscriptionIdentifierExist(30));
		Assert.assertEquals(2, size("callbackMap"));
		Assert.assertEquals(2, size("callbackTopicMap"));
	}
}
