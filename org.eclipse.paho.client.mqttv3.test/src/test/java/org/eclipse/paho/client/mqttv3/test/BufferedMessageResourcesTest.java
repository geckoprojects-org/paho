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

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;
import org.eclipse.paho.client.mqttv3.internal.ClientState;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
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
			client.disconnectForcibly(0, 100);
		} catch (MqttException e) {
			// not connected
		}
		client.close(true);
		broker.close();
	}

	private static MqttConnectOptions options() {
		MqttConnectOptions options = new MqttConnectOptions();
		options.setCleanSession(false);
		options.setAutomaticReconnect(true);
		options.setMaxReconnectDelay(1000);
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
		Assert.assertTrue("still connected", TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return !client.isConnected();
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
				Assert.assertEquals(MqttException.REASON_CODE_DISCONNECTED_BUFFER_FULL, e.getReasonCode());
				refused++;
			}
		}
		Assert.assertEquals(3, refused);
		Assert.assertEquals("message IDs in use", 2, messageIdsInUse());
		Assert.assertEquals("stored buffered messages", 2, storedBufferedMessages());
	}
}
