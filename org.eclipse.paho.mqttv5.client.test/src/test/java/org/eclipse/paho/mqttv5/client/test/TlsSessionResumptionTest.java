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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.internal.ClientComms;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.client.test.utilities.FakeMqttBroker;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * The way the client closes a TLS connection keeps the TLS session resumable: the next connection
 * of the same SSL context resumes it instead of a full handshake. The client counts full
 * handshakes by the server certificate checks of its trust manager, which a resumption skips.
 */
@RunWith(Parameterized.class)
public class TlsSessionResumptionTest {

	@Parameters(name = "{0}")
	public static Collection<Object[]> protocols() {
		return Arrays.asList(new Object[][] { { "TLSv1.2" }, { "TLSv1.3" } });
	}

	private final String protocol;
	private FakeMqttBroker broker;
	private TlsProxy proxy;
	private MqttAsyncClient client;
	private final AtomicInteger fullHandshakes = new AtomicInteger();
	private SSLContext clientContext;

	public TlsSessionResumptionTest(String protocol) {
		this.protocol = protocol;
	}

	/** Terminates TLS in front of the fake broker, one pair of pump threads per connection. */
	private final class TlsProxy implements Runnable {
		private final SSLServerSocket serverSocket;
		private final Thread acceptor;

		TlsProxy(SSLContext context) throws IOException {
			serverSocket = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 50,
					InetAddress.getLoopbackAddress());
			serverSocket.setEnabledProtocols(new String[] { protocol });
			acceptor = new Thread(this, "tls-proxy");
			acceptor.setDaemon(true);
			acceptor.start();
		}

		int getPort() {
			return serverSocket.getLocalPort();
		}

		public void run() {
			while (!serverSocket.isClosed()) {
				try {
					final SSLSocket tls = (SSLSocket) serverSocket.accept();
					final Socket plain = new Socket(InetAddress.getLoopbackAddress(), URI.create(broker.getServerURI()).getPort());
					pump(tls, plain);
					pump(plain, tls);
				} catch (IOException e) {
					// closed
				}
			}
		}

		private void pump(final Socket from, final Socket to) {
			Thread pump = new Thread(new Runnable() {
				public void run() {
					byte[] buffer = new byte[4096];
					try {
						InputStream in = from.getInputStream();
						OutputStream out = to.getOutputStream();
						int n;
						while ((n = in.read(buffer)) >= 0) {
							out.write(buffer, 0, n);
							out.flush();
						}
					} catch (IOException e) {
						// one side ended
					} finally {
						closeQuietly(from);
						closeQuietly(to);
					}
				}
			}, "tls-proxy-pump");
			pump.setDaemon(true);
			pump.start();
		}

		void close() throws IOException {
			serverSocket.close();
		}
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException e) {
			// closed already
		}
	}

	/** Accepts the test server's certificate, counting the checks: one per full handshake. */
	private final class CountingTrustManager extends X509ExtendedTrustManager {
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
			fullHandshakes.incrementAndGet();
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
			fullHandshakes.incrementAndGet();
		}

		public void checkServerTrusted(X509Certificate[] chain, String authType) {
			fullHandshakes.incrementAndGet();
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
		}

		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}

	@Before
	public void setUp() throws Exception {
		KeyStore keyStore = KeyStore.getInstance("JKS");
		InputStream in = getClass().getResourceAsStream("/clientkeystore.jks");
		try {
			keyStore.load(in, "password".toCharArray());
		} finally {
			in.close();
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keyStore, "password".toCharArray());
		SSLContext serverContext = SSLContext.getInstance("TLS");
		serverContext.init(keyManagers.getKeyManagers(), null, null);

		clientContext = SSLContext.getInstance(protocol);
		clientContext.init(null, new TrustManager[] { new CountingTrustManager() }, null);

		broker = new FakeMqttBroker();
		proxy = new TlsProxy(serverContext);
		client = new MqttAsyncClient("ssl://localhost:" + proxy.getPort(), "tlsresume", new MemoryPersistence());
	}

	@After
	public void tearDown() throws Exception {
		try {
			client.disconnectForcibly(0, 100, false);
		} catch (MqttException e) {
			// not connected
		}
		client.close(true);
		proxy.close();
		broker.close();
	}

	private MqttConnectionOptions options() {
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setSocketFactory(clientContext.getSocketFactory());
		options.setHttpsHostnameVerificationEnabled(false);
		return options;
	}

	private ClientComms comms() throws Exception {
		Field comms = MqttAsyncClient.class.getDeclaredField("comms");
		comms.setAccessible(true);
		return (ClientComms) comms.get(client);
	}

	private void connect() throws MqttException {
		client.connect(options()).waitForCompletion(TestTiming.scaled(5000));
		Assert.assertTrue(client.isConnected());
		// a round trip, so that a TLS 1.3 session ticket sent after the handshake has arrived
		client.subscribe("t", 1).waitForCompletion(TestTiming.scaled(5000));
	}

	@Test(timeout = 60000)
	public void testEveryFullHandshakeIsCounted() throws Exception {
		connect();
		client.disconnect().waitForCompletion(TestTiming.scaled(5000));
		// a new SSL context has no session to resume
		clientContext = SSLContext.getInstance(protocol);
		clientContext.init(null, new TrustManager[] { new CountingTrustManager() }, null);
		connect();
		Assert.assertEquals("full TLS handshakes without a session to resume", 2, fullHandshakes.get());
	}

	@Test(timeout = 60000)
	public void testSessionResumedAfterDisconnect() throws Exception {
		connect();
		client.disconnect().waitForCompletion(TestTiming.scaled(5000));
		connect();
		client.disconnect().waitForCompletion(TestTiming.scaled(5000));
		connect();
		Assert.assertEquals("full TLS handshakes for three connections", 1, fullHandshakes.get());
	}

	@Test(timeout = 60000)
	public void testSessionResumedAfterForcedDisconnect() throws Exception {
		connect();
		client.disconnectForcibly(0, 100, false);
		connect();
		Assert.assertEquals("full TLS handshakes for two connections", 1, fullHandshakes.get());
	}

	@Test(timeout = 60000)
	public void testSessionResumedAfterConnectionLoss() throws Exception {
		connect();
		broker.dropConnections();
		final ClientComms comms = comms();
		Assert.assertTrue(TestTiming.waitFor(new TestTiming.Condition() {
			public boolean met() {
				return comms.isDisconnected();
			}
		}, 5000));
		connect();
		Assert.assertEquals("full TLS handshakes for two connections", 1, fullHandshakes.get());
	}
}
