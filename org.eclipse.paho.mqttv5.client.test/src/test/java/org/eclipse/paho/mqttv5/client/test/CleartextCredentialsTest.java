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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
 * A client warns once when it sends a password without TLS to a host other than the loopback
 * interface.
 */
public class CleartextCredentialsTest {

	// 192.0.2.0/24 is TEST-NET-1 (RFC 5737): not routed, the connect attempt just times out
	private static final String REMOTE = "tcp://192.0.2.1:1883";

	private final Logger logger = Logger.getLogger(MqttAsyncClient.class.getName());
	private final List<String> warnings = new ArrayList<String>();
	private final Handler handler = new Handler() {
		public void publish(LogRecord record) {
			if (record.getLevel().intValue() >= Level.WARNING.intValue() && record.getMessage().contains("without TLS")) {
				synchronized (warnings) {
					warnings.add(record.getMessage());
				}
			}
		}

		public void flush() {
		}

		public void close() {
		}
	};
	private MqttAsyncClient client;

	@Before
	public void setUp() {
		logger.addHandler(handler);
	}

	@After
	public void tearDown() throws Exception {
		logger.removeHandler(handler);
		if (client != null) {
			try {
				client.disconnectForcibly(0, 100, false);
			} catch (MqttException e) {
				// not connected
			}
			try {
				client.close(true);
			} catch (MqttException e) {
				// connect still in progress
			}
		}
	}

	private void connect(String uri, boolean password) throws Exception {
		if (client == null) {
			client = new MqttAsyncClient(uri, "cleartext", new MemoryPersistence());
		}
		MqttConnectionOptions options = new MqttConnectionOptions();
		
		options.setConnectionTimeout(1);
		if (password) {
			options.setUserName("user");
			options.setPassword("secret".getBytes(StandardCharsets.UTF_8));
		}
		try {
			client.connect(options).waitForCompletion(TestTiming.scaled(5000));
		} catch (MqttException e) {
			// no broker there
		}
	}

	private int warnings() {
		synchronized (warnings) {
			return warnings.size();
		}
	}

	@Test
	public void testPasswordToRemoteHostWithoutTlsWarnsOnce() throws Exception {
		connect(REMOTE, true);
		Assert.assertEquals(1, warnings());
		Assert.assertTrue(warnings.get(0), warnings.get(0).contains(REMOTE));
		connect(REMOTE, true);
		Assert.assertEquals("warned again", 1, warnings());
	}

	@Test
	public void testNoWarningWithoutPassword() throws Exception {
		connect(REMOTE, false);
		Assert.assertEquals(0, warnings());
	}

	@Test
	public void testNoWarningForLoopback() throws Exception {
		FakeMqttBroker broker = new FakeMqttBroker();
		try {
			connect(broker.getServerURI(), true);
			Assert.assertEquals(0, warnings());
		} finally {
			broker.close();
		}
	}
}
