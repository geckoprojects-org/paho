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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

import org.eclipse.paho.mqttv5.client.websocket.WebSocketFrame;
import org.eclipse.paho.mqttv5.client.websocket.WebSocketNetworkModule;
import org.eclipse.paho.mqttv5.client.test.utilities.TestTiming;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The WebSocket transport answers pings, ignores pongs and reassembles fragmented messages
 * (RFC 6455, sections 5.4 and 5.5); a server that breaks the framing rules ends the connection.
 */
public class WebSocketControlFrameTest {

	private ServerSocket serverSocket;
	private Socket server;
	private DataInputStream fromClient;
	private OutputStream toClient;
	private WebSocketNetworkModule module;

	private static WebSocketNetworkModule module(int port) {
		return new WebSocketNetworkModule(SocketFactory.getDefault(), "ws://localhost:" + port + "/mqtt", "localhost",
				port, "test");
	}

	@Before
	public void connect() throws Exception {
		serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
		final AtomicReference<Object> accepted = new AtomicReference<Object>();
		Thread acceptor = new Thread(new Runnable() {
			public void run() {
				try {
					Socket s = serverSocket.accept();
					s.setSoTimeout((int) TestTiming.scaled(10000));
					handshake(s);
					accepted.set(s);
				} catch (Exception e) {
					accepted.set(e);
				}
			}
		});
		acceptor.start();
		module = module(serverSocket.getLocalPort());
		module.start();
		acceptor.join(TestTiming.scaled(10000));
		Assert.assertTrue(String.valueOf(accepted.get()), accepted.get() instanceof Socket);
		server = (Socket) accepted.get();
		fromClient = new DataInputStream(server.getInputStream());
		toClient = server.getOutputStream();
	}

	@After
	public void close() throws Exception {
		if (server != null) {
			server.close();
		}
		if (module != null) {
			try {
				module.stop();
			} catch (IOException e) {
				// the server is gone already
			}
		}
		serverSocket.close();
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

	private void send(int... values) throws IOException {
		byte[] b = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			b[i] = (byte) values[i];
		}
		toClient.write(b);
		toClient.flush();
	}

	/** A frame the client sent: the first byte and the unmasked payload. */
	private static final class ClientFrame {
		final int head;
		final byte[] payload;

		ClientFrame(int head, byte[] payload) {
			this.head = head;
			this.payload = payload;
		}
	}

	private ClientFrame receive() throws IOException {
		int head = fromClient.readUnsignedByte();
		int second = fromClient.readUnsignedByte();
		Assert.assertTrue("client frames are masked", (second & 0x80) != 0);
		long length = second & 0x7F;
		if (length == 126) {
			length = fromClient.readUnsignedShort();
		} else if (length == 127) {
			length = fromClient.readLong();
		}
		byte[] mask = new byte[4];
		fromClient.readFully(mask);
		byte[] payload = new byte[(int) length];
		fromClient.readFully(payload);
		for (int i = 0; i < payload.length; i++) {
			payload[i] ^= mask[i % 4];
		}
		return new ClientFrame(head, payload);
	}

	@Test(timeout = 30000)
	public void testPingIsAnsweredWithPong() throws IOException {
		send(0x89, 0x03, 'a', 'b', 'c');
		ClientFrame pong = receive();
		Assert.assertEquals("FIN and pong", 0x8A, pong.head);
		Assert.assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), pong.payload);

		// the connection is still up in both directions
		send(0x82, 0x01, 0x42);
		Assert.assertEquals(0x42, module.getInputStream().read());
		module.getOutputStream().write(new byte[] { 0x0C, 0x00 });
		module.getOutputStream().flush();
		ClientFrame data = receive();
		Assert.assertEquals(0x82, data.head);
		Assert.assertArrayEquals(new byte[] { 0x0C, 0x00 }, data.payload);
	}

	@Test(timeout = 30000)
	public void testUnsolicitedPongIsIgnored() throws IOException {
		send(0x8A, 0x00, 0x82, 0x01, 0x42);
		Assert.assertEquals(0x42, module.getInputStream().read());
	}

	@Test(timeout = 30000)
	public void testFragmentedMessageIsReassembled() throws IOException {
		// binary without FIN, a ping in between, continuation without FIN, continuation with FIN
		send(0x02, 0x02, 1, 2, 0x89, 0x00, 0x00, 0x01, 3, 0x80, 0x01, 4);
		InputStream in = module.getInputStream();
		byte[] read = new byte[4];
		for (int i = 0; i < read.length; i++) {
			read[i] = (byte) in.read();
		}
		Assert.assertArrayEquals(new byte[] { 1, 2, 3, 4 }, read);
		Assert.assertEquals(0x8A, receive().head);
	}

	@Test(timeout = 30000)
	public void testContinuationWithoutMessageEndsTheConnection() throws IOException {
		send(0x80, 0x01, 1);
		Assert.assertEquals(-1, module.getInputStream().read());
	}

	@Test(timeout = 30000)
	public void testNewMessageWithinFragmentedMessageEndsTheConnection() throws IOException {
		send(0x02, 0x01, 1, 0x82, 0x01, 2);
		InputStream in = module.getInputStream();
		Assert.assertEquals(1, in.read());
		Assert.assertEquals(-1, in.read());
	}

	@Test(timeout = 60000)
	public void testPongsDoNotInterleaveWithOutgoingFrames() throws Exception {
		final int count = 200;
		final byte[] message = new byte[20000];
		for (int i = 0; i < message.length; i++) {
			message[i] = (byte) i;
		}
		final AtomicReference<Exception> failure = new AtomicReference<Exception>();
		Thread sender = new Thread(new Runnable() {
			public void run() {
				try {
					for (int i = 0; i < count; i++) {
						module.getOutputStream().write(message);
						module.getOutputStream().flush();
					}
				} catch (Exception e) {
					failure.set(e);
				}
			}
		});
		Thread pinger = new Thread(new Runnable() {
			public void run() {
				try {
					for (int i = 0; i < count; i++) {
						send(0x89, 0x03, 'p', 'n', 'g');
					}
				} catch (Exception e) {
					failure.set(e);
				}
			}
		});
		sender.start();
		pinger.start();
		int data = 0;
		int pongs = 0;
		while (data < count || pongs < count) {
			ClientFrame frame = receive();
			if (frame.head == 0x82) {
				Assert.assertTrue(Arrays.equals(message, frame.payload));
				data++;
			} else {
				Assert.assertEquals(0x8A, frame.head);
				Assert.assertArrayEquals("png".getBytes(StandardCharsets.US_ASCII), frame.payload);
				pongs++;
			}
		}
		sender.join();
		pinger.join();
		Assert.assertNull(failure.get());
	}

	private static ByteArrayInputStream bytes(int... values) {
		byte[] b = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			b[i] = (byte) values[i];
		}
		return new ByteArrayInputStream(b);
	}

	@Test(expected = IOException.class)
	public void testFragmentedControlFrameIsRejected() throws IOException {
		new WebSocketFrame(bytes(0x09, 0x00), 1024);
	}

	@Test(expected = IOException.class)
	public void testControlFrameAbove125BytesIsRejected() throws IOException {
		new WebSocketFrame(bytes(0x89, 0x7E, 0x00, 0x7E), 1024);
	}

	@Test(expected = IOException.class)
	public void testTextFrameIsRejected() throws IOException {
		// MQTT over WebSocket uses binary frames only
		new WebSocketFrame(bytes(0x81, 0x01, 'a'), 1024);
	}
}
