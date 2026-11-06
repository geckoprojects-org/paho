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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import javax.net.SocketFactory;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientException;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.internal.ClientComms;
import org.eclipse.paho.mqttv5.client.internal.ClientState;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * While the connection stalls, publishes wait in the send queue; once the configured number is
 * waiting, further publishes fail with MAX_INFLIGHT instead of filling the heap.
 */
public class QueuedMessageLimitTest {

	private FakeMqttBroker broker;
	private MqttAsyncClient client;
	private final Object writeLock = new Object();
	private boolean blocked;

	/** Creates sockets whose writes wait while {@link #blocked} is set, as on a stalled link. */
	private final class BlockingSocketFactory extends SocketFactory {
		@Override
		public Socket createSocket() {
			return new Socket() {
				private OutputStream stream;

				@Override
				public synchronized OutputStream getOutputStream() throws IOException {
					if (stream == null) {
						stream = new FilterOutputStream(super.getOutputStream()) {
							@Override
							public void write(byte[] b, int off, int len) throws IOException {
								awaitUnblocked();
								out.write(b, off, len);
							}

							@Override
							public void write(int b) throws IOException {
								awaitUnblocked();
								out.write(b);
							}
						};
					}
					return stream;
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

	private void awaitUnblocked() throws InterruptedIOException {
		synchronized (writeLock) {
			while (blocked) {
				try {
					writeLock.wait();
				} catch (InterruptedException e) {
					throw new InterruptedIOException();
				}
			}
		}
	}

	private void setBlocked(boolean value) {
		synchronized (writeLock) {
			blocked = value;
			writeLock.notifyAll();
		}
	}

	@Before
	public void setUp() throws Exception {
		broker = new FakeMqttBroker();
	}

	@After
	public void tearDown() throws Exception {
		setBlocked(false);
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

	private ClientState state() throws Exception {
		Field comms = MqttAsyncClient.class.getDeclaredField("comms");
		comms.setAccessible(true);
		Field state = ClientComms.class.getDeclaredField("clientState");
		state.setAccessible(true);
		return (ClientState) state.get(comms.get(client));
	}

	private Object field(String name) throws Exception {
		Field field = ClientState.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(state());
	}

	@Test(timeout = 30000)
	public void testQueueIsLimitedWhileTheLinkStalls() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "queuelimit", new MemoryPersistence());
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setSocketFactory(new BlockingSocketFactory());
		options.setMaxQueuedMessages(5);
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		setBlocked(true);
		List<IMqttToken> tokens = new ArrayList<IMqttToken>();
		tokens.add(client.publish("t", new byte[] { 0 }, 0, false));
		// the sender takes the first publish and waits in its write
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				try {
					return ((Vector<?>) field("pendingMessages")).isEmpty();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		}, 5000));
		int refused = 0;
		for (int i = 1; i <= 20; i++) {
			try {
				tokens.add(client.publish("t", new byte[] { (byte) i }, 0, false));
			} catch (MqttException e) {
				Assert.assertEquals(MqttClientException.REASON_CODE_MAX_INFLIGHT, e.getReasonCode());
				refused++;
			}
		}
		Assert.assertEquals("queued publishes", 5, ((Vector<?>) field("pendingMessages")).size());
		Assert.assertEquals("refused publishes", 15, refused);

		setBlocked(false);
		for (IMqttToken token : tokens) {
			token.waitForCompletion(TestTiming.scaled(5000));
		}
		// the queue has room again
		client.publish("t", new byte[] { 99 }, 0, false).waitForCompletion(TestTiming.scaled(5000));
	}

	@Test
	public void testDefaultLimit() {
		Assert.assertEquals(MqttConnectionOptions.MAX_QUEUED_MESSAGES_DEFAULT, new MqttConnectionOptions().getMaxQueuedMessages());
	}
}
