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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.util.Vector;

import javax.net.SocketFactory;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.internal.ClientComms;
import org.eclipse.paho.client.mqttv3.internal.ClientState;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.paho.client.mqttv3.test.utilities.FakeMqttBroker;
import org.eclipse.paho.client.mqttv3.test.utilities.TestTiming;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * removeMessage() takes a publish that has not been sent yet out of the queue, and leaves the
 * in-flight count alone, since that publish was never counted.
 */
public class RemoveMessageTest {

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
				client.disconnectForcibly(0, 100);
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
	public void testQueuedPublishIsRemoved() throws Exception {
		client = new MqttAsyncClient(broker.getServerURI(), "removemessage", new MemoryPersistence());
		MqttConnectOptions options = new MqttConnectOptions();
		options.setSocketFactory(new BlockingSocketFactory());
		client.connect(options).waitForCompletion(TestTiming.scaled(5000));

		setBlocked(true);
		IMqttDeliveryToken first = client.publish("t", new byte[] { 1 }, 1, false);
		// the sender takes the first publish and waits in its write
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				try {
					return ((Vector<?>) field("pendingMessages")).isEmpty() && state().getActualInFlight() == 1;
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		}, 5000));
		IMqttDeliveryToken second = client.publish("t", new byte[] { 2 }, 1, false);
		Assert.assertEquals("second publish not queued", 1, ((Vector<?>) field("pendingMessages")).size());

		Assert.assertTrue(client.removeMessage(second));
		Assert.assertEquals("publish left in the queue", 0, ((Vector<?>) field("pendingMessages")).size());
		Assert.assertEquals("in-flight count", 1, state().getActualInFlight());

		setBlocked(false);
		first.waitForCompletion(TestTiming.scaled(5000));
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				try {
					return state().getActualInFlight() == 0;
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
		}, 5000));
	}
}
