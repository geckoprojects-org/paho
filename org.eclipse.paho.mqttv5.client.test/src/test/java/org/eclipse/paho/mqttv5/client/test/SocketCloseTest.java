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

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

import org.eclipse.paho.mqttv5.client.internal.TCPNetworkModule;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.eclipse.paho.mqttv5.client.websocket.WebSocketNetworkModule;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Stopping a network module closes its socket even when a step before the close fails, so that no
 * file descriptor is left to the garbage collector.
 */
public class SocketCloseTest {

	private ServerSocket serverSocket;
	private Socket server;

	/** Creates one socket that fails shutdownInput() or, once {@link #failWrites} is set, writes. */
	private static final class FailingSocketFactory extends SocketFactory {
		private final boolean failShutdownInput;
		volatile boolean failWrites;
		Socket socket;

		FailingSocketFactory(boolean failShutdownInput) {
			this.failShutdownInput = failShutdownInput;
		}

		@Override
		public Socket createSocket() {
			socket = new Socket() {
				private OutputStream stream;

				@Override
				public void shutdownInput() throws IOException {
					if (failShutdownInput) {
						// as SSLSocket on Java 8
						throw new UnsupportedOperationException("The method shutdownInput() is not supported in SSLSocket");
					}
					super.shutdownInput();
				}

				@Override
				public synchronized OutputStream getOutputStream() throws IOException {
					if (stream == null) {
						stream = new FilterOutputStream(super.getOutputStream()) {
							@Override
							public void write(byte[] b, int off, int len) throws IOException {
								if (failWrites) {
									throw new IOException("Broken pipe");
								}
								out.write(b, off, len);
							}

							@Override
							public void write(int b) throws IOException {
								if (failWrites) {
									throw new IOException("Broken pipe");
								}
								out.write(b);
							}
						};
					}
					return stream;
				}
			};
			return socket;
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
		serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
	}

	@After
	public void tearDown() throws Exception {
		if (server != null) {
			server.close();
		}
		serverSocket.close();
	}

	/** Accepts one connection in the background, answering the WebSocket handshake if asked. */
	private Thread accept(final boolean webSocket) {
		Thread acceptor = new Thread(new Runnable() {
			public void run() {
				try {
					Socket s = serverSocket.accept();
					s.setSoTimeout((int) TestTiming.scaled(10000));
					if (webSocket) {
						handshake(s);
					}
					server = s;
				} catch (Exception e) {
					// the test sees no server
				}
			}
		});
		acceptor.start();
		return acceptor;
	}

	private static void handshake(Socket s) throws Exception {
		InputStream in = s.getInputStream();
		ByteArrayOutputStream request = new ByteArrayOutputStream();
		while (!new String(request.toByteArray(), StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
			int b = in.read();
			if (b < 0) {
				throw new IOException("end of handshake request");
			}
			request.write(b);
		}
		Matcher m = Pattern.compile("Sec-WebSocket-Key: (\\S+)").matcher(new String(request.toByteArray(), StandardCharsets.ISO_8859_1));
		Assert.assertTrue(m.find());
		String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA1")
				.digest((m.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
		s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\n" + "Upgrade: websocket\r\n"
				+ "Connection: Upgrade\r\n" + "Sec-WebSocket-Protocol: mqtt\r\n" + "Sec-WebSocket-Accept: " + accept
				+ "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
	}

	@Test(timeout = 30000)
	public void testTcpStopClosesTheSocketWhenShutdownInputFails() throws Exception {
		FailingSocketFactory factory = new FailingSocketFactory(true);
		Thread acceptor = accept(false);
		TCPNetworkModule module = new TCPNetworkModule(factory, "localhost", serverSocket.getLocalPort(), "test");
		module.start();
		acceptor.join(TestTiming.scaled(10000));
		module.stop();
		Assert.assertTrue("socket left open", factory.socket.isClosed());
	}

	@Test(timeout = 30000)
	public void testWebSocketStopClosesTheSocketWhenTheCloseFrameFails() throws Exception {
		FailingSocketFactory factory = new FailingSocketFactory(false);
		Thread acceptor = accept(true);
		int port = serverSocket.getLocalPort();
		WebSocketNetworkModule module = new WebSocketNetworkModule(factory, "ws://localhost:" + port + "/mqtt",
				"localhost", port, "test");
		module.start();
		acceptor.join(TestTiming.scaled(10000));
		factory.failWrites = true; // the connection broke
		try {
			module.stop();
			Assert.fail("the close frame cannot be written");
		} catch (IOException e) {
			// reported, as before
		}
		Assert.assertTrue("socket left open", factory.socket.isClosed());
	}
}
