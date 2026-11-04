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

/**
 * The client reads a packet that has arrived with one read on the socket stream, not one per
 * header byte (each read on a socket stream is a system call).
 */
public class BufferedReadTest {

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

	@Test
	public void testOneReadPerPacket() throws Exception {
		CountingSocketFactory factory = new CountingSocketFactory();
		final AtomicInteger arrived = new AtomicInteger();
		client = new MqttAsyncClient(broker.getServerURI(), "bufferedread", new MemoryPersistence());
		client.setCallback(new MqttCallback() {
			public void disconnected(MqttDisconnectResponse disconnectResponse) {
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
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setSocketFactory(factory);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		int messages = 20;
		int reads0 = factory.reads.get();
		for (int i = 1; i <= messages; i++) {
			// one at a time, so that no two packets arrive in one read
			broker.publish("t", "message " + i, 1);
			final int expected = i;
			Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
				public boolean met() {
					return arrived.get() == expected;
				}
			}, 5000));
		}
		int reads = factory.reads.get() - reads0;
		// unbuffered: the first byte, the remaining length and the body, three reads per packet
		Assert.assertTrue(reads + " reads for " + messages + " packets", reads <= messages + 2);
	}
}
