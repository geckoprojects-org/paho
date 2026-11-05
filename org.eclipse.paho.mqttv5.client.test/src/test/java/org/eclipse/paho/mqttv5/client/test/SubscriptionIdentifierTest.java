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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
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
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Subscription identifiers: an automatically assigned one is sent with the SUBSCRIBE, a given one
 * is read from {@link MqttProperties#getSubscriptionIdentifier()}, and the PUBLISH carrying it
 * reaches the listener of the subscription.
 */
public class SubscriptionIdentifierTest {

	private static final long TIMEOUT = 5000;

	private FakeMqttBroker broker;
	private MqttAsyncClient client;
	/** Messages that reached {@link MqttCallback#messageArrived}, as "topic". */
	private final List<String> unrouted = new CopyOnWriteArrayList<String>();
	private volatile CountDownLatch synced;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		client = new MqttAsyncClient(broker.getServerURI(), "subscriptionIds", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
			}

			public void mqttErrorOccurred(MqttException exception) {
			}

			public void messageArrived(String topic, MqttMessage message) {
				unrouted.add(topic);
			}

			public void deliveryComplete(IMqttToken token) {
			}

			public void connectComplete(boolean reconnect, String serverURI) {
			}

			public void authPacketArrived(int reasonCode, MqttProperties properties) {
			}
		});
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

	private void connect(boolean useSubscriptionIdentifiers) throws Exception {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setUseSubscriptionIdentifiers(useSubscriptionIdentifiers);
		client.connect(options).waitForCompletion(TestTiming.scaled(TIMEOUT));
		// a listener without subscription identifier, see sync()
		client.subscribe(new MqttSubscription[] { new MqttSubscription("sync", 0) }, null, null,
				new IMqttMessageListener[] { new IMqttMessageListener() {
					public void messageArrived(String topic, MqttMessage message) {
						synced.countDown();
					}
				} }, new MqttProperties()).waitForCompletion(TestTiming.scaled(TIMEOUT));
	}

	/** Returns once every message published before has been delivered: one callback thread delivers them in order. */
	private void sync() throws Exception {
		synced = new CountDownLatch(1);
		broker.publish("sync", "s", 0);
		Assert.assertTrue("sync message not delivered", synced.await(TestTiming.scaled(TIMEOUT), TimeUnit.MILLISECONDS));
	}

	private void subscribe(MqttSubscription[] subscriptions, IMqttMessageListener listener, MqttProperties properties)
			throws MqttException {
		client.subscribe(subscriptions, null, null, listener, properties).waitForCompletion(TestTiming.scaled(TIMEOUT));
	}

	private static MqttSubscription[] filters(String... topics) {
		MqttSubscription[] subscriptions = new MqttSubscription[topics.length];
		for (int i = 0; i < topics.length; i++) {
			subscriptions[i] = new MqttSubscription(topics[i], 0);
		}
		return subscriptions;
	}

	private static MqttProperties withSubscriptionIdentifier(int subscriptionId) {
		MqttProperties properties = new MqttProperties();
		properties.setSubscriptionIdentifier(Integer.valueOf(subscriptionId));
		return properties;
	}

	private int lastSubscriptionIdentifier() {
		List<Integer> ids = broker.getSubscriptionIdentifiers();
		return ids.get(ids.size() - 1).intValue();
	}

	/** Records the topics of the messages it receives. */
	private static final class Recorder implements IMqttMessageListener {
		final List<String> topics = new CopyOnWriteArrayList<String>();

		public void messageArrived(String topic, MqttMessage message) {
			topics.add(topic);
		}
	}

	@Test
	public void testAssignedIdentifierIsSent() throws Exception {
		connect(true);
		Recorder listener = new Recorder();
		subscribe(filters("a/b"), listener, new MqttProperties());
		int subscriptionId = lastSubscriptionIdentifier();
		Assert.assertTrue("no subscription identifier in the SUBSCRIBE", subscriptionId > 0);

		broker.publish("a/b", "x", 0, subscriptionId);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
		Assert.assertEquals(Collections.emptyList(), unrouted);
	}

	@Test
	public void testGivenIdentifierLinksTheListener() throws Exception {
		connect(true);
		Recorder listener = new Recorder();
		subscribe(filters("a/b"), listener, withSubscriptionIdentifier(7));
		Assert.assertEquals(7, lastSubscriptionIdentifier());

		broker.publish("a/b", "x", 0, 7);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
		Assert.assertEquals(Collections.emptyList(), unrouted);
	}

	@Test
	public void testGivenIdentifierWithoutAutomaticAssignment() throws Exception {
		connect(false);
		Recorder listener = new Recorder();
		subscribe(filters("a/b"), listener, withSubscriptionIdentifier(7));
		Assert.assertEquals(7, lastSubscriptionIdentifier());

		broker.publish("a/b", "x", 0, 7);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
	}

	@Test
	public void testNoIdentifierWithoutAutomaticAssignment() throws Exception {
		connect(false);
		Recorder listener = new Recorder();
		subscribe(filters("a/b"), listener, new MqttProperties());
		Assert.assertEquals(0, lastSubscriptionIdentifier());

		broker.publish("a/b", "x", 0);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
	}

	@Test
	public void testCallerPropertiesAreNotChanged() throws Exception {
		connect(true);
		MqttProperties properties = new MqttProperties();
		subscribe(filters("a"), new Recorder(), properties);
		subscribe(filters("b"), new Recorder(), properties);
		Assert.assertNull(properties.getSubscriptionIdentifier());
		List<Integer> ids = broker.getSubscriptionIdentifiers();
		Assert.assertNotEquals("the same identifier for two subscriptions", ids.get(ids.size() - 2),
				ids.get(ids.size() - 1));
	}

	@Test
	public void testGivenIdentifierOfAnotherFilter() throws Exception {
		connect(true);
		Recorder listener = new Recorder();
		subscribe(filters("a"), listener, withSubscriptionIdentifier(7));
		// re-subscribing the same filter keeps its identifier
		subscribe(filters("a"), listener, withSubscriptionIdentifier(7));
		try {
			subscribe(filters("b"), listener, withSubscriptionIdentifier(7));
			Assert.fail("identifier of filter a given to filter b");
		} catch (IllegalArgumentException e) {
			// expected
		}
	}

	@Test
	public void testAssignmentSkipsGivenIdentifiers() throws Exception {
		connect(true);
		subscribe(filters("a"), new Recorder(), withSubscriptionIdentifier(1));
		subscribe(filters("b"), new Recorder(), new MqttProperties());
		Assert.assertNotEquals(1, lastSubscriptionIdentifier());
	}

	@Test
	public void testOneIdentifierForSeveralFilters() throws Exception {
		connect(true);
		Recorder listener = new Recorder();
		subscribe(filters("a/+", "a/#", "b"), listener, new MqttProperties());
		int subscriptionId = lastSubscriptionIdentifier();

		// matches two filters of the SUBSCRIBE, delivered once
		broker.publish("a/x", "x", 0, subscriptionId);
		sync();
		Assert.assertEquals(Collections.singletonList("a/x"), listener.topics);

		// the identifier stays linked to the filters left
		client.unsubscribe("b").waitForCompletion(TestTiming.scaled(TIMEOUT));
		broker.publish("a/y", "y", 0, subscriptionId);
		sync();
		Assert.assertEquals(Arrays.asList("a/x", "a/y"), listener.topics);
		Assert.assertEquals(Collections.emptyList(), unrouted);
	}

	@Test
	public void testListenerWithoutIdentifierNextToOneWithIdentifier() throws Exception {
		connect(true);
		Recorder withId = new Recorder();
		subscribe(filters("a/b"), withId, withSubscriptionIdentifier(7));
		Recorder withoutId = new Recorder();
		// the array variant subscribes without identifier, as restoreSubscriptions does
		client.subscribe(filters("a/+"), null, null, new IMqttMessageListener[] { withoutId }, new MqttProperties())
				.waitForCompletion(TestTiming.scaled(TIMEOUT));

		broker.publish("a/b", "x", 0, 7);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), withId.topics);
		Assert.assertEquals(Collections.singletonList("a/b"), withoutId.topics);
	}

	@Test
	public void testUnknownIdentifierFallsBackToTopicMatching() throws Exception {
		connect(true);
		Recorder listener = new Recorder();
		subscribe(filters("a/b"), listener, new MqttProperties());

		// e.g. an identifier of an earlier session
		broker.publish("a/b", "x", 0, 4711);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
	}
	@Test
	public void testSharedSubscriptionByTopic() throws Exception {
		connect(false);
		Recorder listener = new Recorder();
		subscribe(filters("$share/g/a/+"), listener, new MqttProperties());

		broker.publish("a/b", "x", 0);
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
		Assert.assertEquals(Collections.emptyList(), unrouted);
	}

	@Test
	public void testSharedSubscriptionByIdentifier() throws Exception {
		connect(true);
		Recorder listener = new Recorder();
		subscribe(filters("$share/g/a/b"), listener, new MqttProperties());

		broker.publish("a/b", "x", 0, lastSubscriptionIdentifier());
		sync();
		Assert.assertEquals(Collections.singletonList("a/b"), listener.topics);
		Assert.assertEquals(Collections.emptyList(), unrouted);
	}
}
