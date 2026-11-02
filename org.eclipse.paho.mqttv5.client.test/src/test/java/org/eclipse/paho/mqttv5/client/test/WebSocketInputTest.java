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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.paho.mqttv5.client.websocket.WebSocketFrame;
import org.eclipse.paho.mqttv5.client.websocket.WebSocketHandshake;
import org.junit.Assert;
import org.junit.Test;

/**
 * The WebSocket transport checks the lengths it reads from the server: frame payloads and the
 * handshake response header are bounded, and an early end of stream is an IOException.
 */
public class WebSocketInputTest {

	private static ByteArrayInputStream bytes(int... values) {
		byte[] b = new byte[values.length];
		for (int i = 0; i < values.length; i++) {
			b[i] = (byte) values[i];
		}
		return new ByteArrayInputStream(b);
	}

	@Test
	public void testFrameIsRead() throws IOException {
		WebSocketFrame frame = new WebSocketFrame(bytes(0x82, 0x03, 1, 2, 3), 1024);
		Assert.assertArrayEquals(new byte[] { 1, 2, 3 }, frame.getPayload());
	}

	@Test
	public void testFrameWithTwoByteLengthIsRead() throws IOException {
		byte[] frame = new byte[4 + 300];
		frame[0] = (byte) 0x82;
		frame[1] = 0x7E;
		frame[2] = 0x01;
		frame[3] = 0x2C;
		Assert.assertEquals(300, new WebSocketFrame(new ByteArrayInputStream(frame), 1024).getPayload().length);
	}

	@Test(expected = IOException.class)
	public void testFrameAboveTheLimitIsRejected() throws IOException {
		// 2,000 bytes announced, no payload follows
		new WebSocketFrame(bytes(0x82, 0x7E, 0x07, 0xD0), 1024);
	}

	@Test(expected = IOException.class)
	public void testSixtyFourBitLengthDoesNotOverflow() throws IOException {
		// 2^63 - 1: an int length wraps around to -1 (NegativeArraySizeException)
		new WebSocketFrame(bytes(0x82, 0x7F, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF));
	}

	@Test(expected = IOException.class)
	public void testEndOfStreamWithinThePayload() throws IOException {
		// read() returning -1 must not be taken as a byte count
		new WebSocketFrame(bytes(0x82, 0x05, 1, 2), 1024);
	}

	/** A server for the handshake that answers with the accept key for the key the client sent. */
	private static final class Server extends InputStream {
		private final ByteArrayOutputStream request = new ByteArrayOutputStream();
		private final String header;
		private final byte[] trailer;
		private InputStream response;

		Server(String header, byte[] trailer) {
			this.header = header;
			this.trailer = trailer;
		}

		public int read() throws IOException {
			if (response == null) {
				Matcher m = Pattern.compile("Sec-WebSocket-Key: (\\S+)").matcher(new String(request.toByteArray(), StandardCharsets.ISO_8859_1));
				Assert.assertTrue(m.find());
				String accept;
				try {
					accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA1")
							.digest((m.group(1) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
				} catch (Exception e) {
					throw new IOException(e);
				}
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				out.write(header.replace("{accept}", accept).getBytes(StandardCharsets.ISO_8859_1));
				out.write(trailer);
				response = new ByteArrayInputStream(out.toByteArray());
			}
			return response.read();
		}
	}

	private static final String RESPONSE = "HTTP/1.1 101 Switching Protocols\r\n"
			+ "Upgrade: websocket\r\n"
			+ "Connection: Upgrade\r\n"
			+ "Sec-WebSocket-Protocol: mqtt\r\n"
			+ "Sec-WebSocket-Accept: {accept}\r\n";

	private static void handshake(Server server) throws IOException {
		new WebSocketHandshake(server, server.request, "ws://localhost:80/mqtt", "localhost", 80,
				new HashMap<String, String>()).execute();
	}

	@Test
	public void testHandshakeLeavesTheFirstFrameInTheStream() throws IOException {
		Server server = new Server(RESPONSE + "\r\n", new byte[] { (byte) 0x82, 0x01, 0x42 });
		handshake(server);
		Assert.assertArrayEquals(new byte[] { 0x42 }, new WebSocketFrame(server, 1024).getPayload());
	}

	@Test
	public void testHandshakeAcceptsHeaderWithoutSpaceAndLineWithoutColon() throws IOException {
		Server server = new Server(RESPONSE.replace("Connection: Upgrade", "Connection:Upgrade") + "X-No-Colon\r\n\r\n", new byte[0]);
		handshake(server);
	}

	@Test(expected = IOException.class)
	public void testHandshakeEndOfStreamWithinTheHeader() throws IOException {
		handshake(new Server(RESPONSE, new byte[0]));
	}

	@Test(expected = IOException.class)
	public void testHandshakeHeaderIsBounded() throws IOException {
		StringBuilder endless = new StringBuilder(RESPONSE);
		while (endless.length() < 64 * 1024) {
			endless.append("X-Filler: 0123456789012345678901234567890123456789\r\n");
		}
		handshake(new Server(endless.toString(), new byte[0]));
	}
}
