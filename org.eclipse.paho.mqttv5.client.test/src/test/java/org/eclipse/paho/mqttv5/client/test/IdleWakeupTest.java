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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

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
 * Once connected, the receiver blocks in its read until data arrives instead of waking up with a
 * read timeout every second, and stopping the client still ends that read right away.
 */
public class IdleWakeupTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;

	/** Creates sockets whose input stream counts the read calls. */
	private static final class CountingSocketFactory extends SocketFactory {
		final AtomicInteger reads = new AtomicInteger();

		@Override
		public Socket createSocket() {
			return new Socket() {
				private InputStream in;

				@Override
				public synchronized InputStream getInputStream() throws IOException {
					if (in == null) {
						in = new FilterInputStream(super.getInputStream()) {
							@Override
							public int read() throws IOException {
								reads.incrementAndGet();
								return super.read();
							}

							@Override
							public int read(byte[] b, int off, int len) throws IOException {
								reads.incrementAndGet();
								return super.read(b, off, len);
							}
						};
					}
					return in;
				}
			};
		}

		@Override
		public Socket createSocket(String host, int port) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Socket createSocket(InetAddress host, int port) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
			throw new UnsupportedOperationException();
		}
	}

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

	private CountingSocketFactory connect() throws MqttException {
		CountingSocketFactory factory = new CountingSocketFactory();
		client = new MqttAsyncClient(broker.getServerURI(), "idle", new MemoryPersistence());
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setSocketFactory(factory);
		options.setKeepAliveInterval(0); // no pings, nothing to read
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));
		return factory;
	}

	@Test
	public void testIdleClientDoesNotWakeUp() throws Exception {
		CountingSocketFactory factory = connect();
		TestTiming.observe(300);
		int reads0 = factory.reads.get();
		TestTiming.observe(3500);
		int reads = factory.reads.get() - reads0;
		// with a read timeout of 1 s, the receiver read again every second
		Assert.assertTrue(reads + " reads in 3.5 s idle", reads <= 1);
	}

	@Test(timeout = 30000)
	public void testStopEndsTheBlockedRead() throws Exception {
		connect();
		TestTiming.observe(300);
		long start = System.nanoTime();
		client.disconnectForcibly(0, 100, false);
		long millis = (System.nanoTime() - start) / 1000000;
		Assert.assertTrue("disconnect took " + millis + " ms", millis < TestTiming.scaled(1000));
		Assert.assertFalse(client.isConnected());
		// the client connects again on a new socket
		client.connect(new MqttConnectionOptions()).waitForCompletion(TestTiming.scaled(5000));
		Assert.assertTrue(client.isConnected());
	}
}
