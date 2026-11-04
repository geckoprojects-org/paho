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

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.DisconnectedBufferOptions;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientException;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.internal.ClientComms;
import org.eclipse.paho.mqttv5.client.internal.ClientState;
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Messages that leave the offline buffer, sent, discarded as the oldest or refused because the
 * buffer is full, release their message ID and their stored copy.
 */
public class BufferedMessageResourcesTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
		client = new MqttAsyncClient(broker.getServerURI(), "buffered", new MqttDefaultFilePersistence(folder.getRoot().getAbsolutePath()));
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

	private static MqttConnectionOptions options() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(false);
		options.setAutomaticReconnect(true);
		options.setAutomaticReconnectDelay(1, 1);
		return options;
	}

	private static DisconnectedBufferOptions buffer(int size, boolean deleteOldest) {
		DisconnectedBufferOptions buffer = new DisconnectedBufferOptions();
		buffer.setBufferEnabled(true);
		buffer.setPersistBuffer(true);
		buffer.setBufferSize(size);
		buffer.setDeleteOldestMessages(deleteOldest);
		return buffer;
	}

	private ClientState state() throws Exception {
		Field comms = MqttAsyncClient.class.getDeclaredField("comms");
		comms.setAccessible(true);
		Field state = ClientComms.class.getDeclaredField("clientState");
		state.setAccessible(true);
		return (ClientState) state.get(comms.get(client));
	}

	private int messageIdsInUse() throws Exception {
		Field comms = MqttAsyncClient.class.getDeclaredField("comms");
		comms.setAccessible(true);
		Field state = ClientComms.class.getDeclaredField("clientState");
		state.setAccessible(true);
		Field ids = ClientState.class.getDeclaredField("inUseMsgIds");
		ids.setAccessible(true);
		return ((Map<?, ?>) ids.get(state.get(comms.get(client)))).size();
	}

	/** Stored copies of buffered messages ("sb-" files). */
	private int storedBufferedMessages() {
		return count(folder.getRoot());
	}

	private static int count(File directory) {
		int count = 0;
		File[] entries = directory.listFiles();
		if (entries != null) {
			for (File entry : entries) {
				count += entry.isDirectory() ? count(entry) : entry.getName().startsWith("sb-") ? 1 : 0;
			}
		}
		return count;
	}

	private void goOffline() throws Exception {
		broker.setRefusing(true);
		// v5 buffers once the reconnect cycle runs (resting), not right when the connection is lost
		Field comms = MqttAsyncClient.class.getDeclaredField("comms");
		comms.setAccessible(true);
		final ClientComms clientComms = (ClientComms) comms.get(client);
		Assert.assertTrue("not resting", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return !client.isConnected() && clientComms.isResting();
			}
		}, 5000));
	}

	@Test
	public void testDiscardedMessagesReleaseTheirIdAndStoredCopy() throws Exception {
		client.setBufferOpts(buffer(2, true));
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		goOffline();
		for (int i = 0; i < 10; i++) {
			client.publish("t", new byte[] { (byte) i }, 1, false);
		}
		Assert.assertEquals("message IDs in use", 2, messageIdsInUse());
		Assert.assertEquals("stored buffered messages", 2, storedBufferedMessages());
	}

	@Test
	public void testRefusedMessagesReleaseTheirIdAndStoredCopy() throws Exception {
		client.setBufferOpts(buffer(2, false));
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		goOffline();
		int refused = 0;
		for (int i = 0; i < 5; i++) {
			try {
				client.publish("t", new byte[] { (byte) i }, 1, false);
			} catch (MqttException e) {
				Assert.assertEquals(MqttClientException.REASON_CODE_DISCONNECTED_BUFFER_FULL, e.getReasonCode());
				refused++;
			}
		}
		Assert.assertEquals(3, refused);
		Assert.assertEquals("message IDs in use", 2, messageIdsInUse());
		Assert.assertEquals("stored buffered messages", 2, storedBufferedMessages());
	}

	@Test
	public void testMessagesRefusedAtTheInflightLimitKeepTheirStoredCopy() throws Exception {
		// more buffered messages than the server's Receive Maximum, PUBACKs slow
		broker.setReceiveMaximum(2);
		broker.setPubAckDelay(50);
		client.setBufferOpts(buffer(10, false));
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		goOffline();
		for (int i = 0; i < 6; i++) {
			client.publish("t", new byte[] { (byte) i }, 1, false);
		}
		Assert.assertEquals(6, storedBufferedMessages());
		broker.setRefusing(false);
		Assert.assertTrue("buffered messages left: " + storedBufferedMessages() + ", message IDs in use: " + messageIdsInUse(),
				TestTiming.waitFor(new TestTiming.Condition() {
					public boolean met() {
						try {
							return storedBufferedMessages() == 0 && messageIdsInUse() == 0;
						} catch (Exception e) {
							throw new IllegalStateException(e);
						}
					}
				}, 10000));
	}

	@Test(timeout = 60000)
	public void testRestoredQos0MessageIsSentOnce() throws Exception {
		client.setBufferOpts(buffer(10, false));
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		goOffline();
		client.publish("t", new byte[] { 1 }, 0, false);
		Assert.assertEquals(1, storedBufferedMessages());
		try {
			client.disconnectForcibly(0, 100, false);
		} catch (MqttException e) {
			// not connected
		}
		client.close(true);

		// the next start of the application restores the stored message and sends it
		broker.setRefusing(false);
		client = new MqttAsyncClient(broker.getServerURI(), "buffered", new MqttDefaultFilePersistence(folder.getRoot().getAbsolutePath()));
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		Assert.assertTrue("restored message not sent", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return broker.getPublishCount() == 1;
			}
		}, 5000));
		final int connects = broker.getConnectCount();
		broker.dropConnections();
		Assert.assertTrue("no reconnect", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return broker.getConnectCount() > connects && client.isConnected();
			}
		}, 10000));
		TestTiming.observe(500);
		Assert.assertEquals("restored QoS 0 message sent again after the reconnect", 1, broker.getPublishCount());
		// queued again but skipped by the sender (its token is gone), it would hold an in-flight slot
		Assert.assertEquals("in-flight slots held", 0, state().getActualInFlight());
	}
}
