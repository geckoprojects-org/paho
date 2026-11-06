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
package org.eclipse.paho.client.mqttv3.test.utilities;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A minimal in-process MQTT 3.1.1 / 5.0 server for tests that need to control the server side of a
 * connection: it can stop answering (accept and read, but never reply, like a half-open or
 * black-holed link), drop all connections, and record what clients subscribed to.
 * <p>
 * It never reports a session as present and does no routing; messages are sent to the client only
 * through {@link #publish(String, String)}. Supports QoS 0 and 1 only.
 */
public class FakeMqttBroker {

	private static final int CONNECT = 1;
	private static final int PUBLISH = 3;
	private static final int PUBACK = 4;
	private static final int SUBSCRIBE = 8;
	private static final int UNSUBSCRIBE = 10;
	private static final int PINGREQ = 12;
	private static final int DISCONNECT = 14;

	private final int port;
	private volatile ServerSocket serverSocket;
	private final List<Connection> connections = new CopyOnWriteArrayList<Connection>();
	/** Entries "connectNumber:topicFilter", one per filter in each SUBSCRIBE. */
	private final List<String> subscriptions = new CopyOnWriteArrayList<String>();
	private final AtomicInteger connects = new AtomicInteger();
	private final AtomicInteger pings = new AtomicInteger();
	private final AtomicInteger pubAcks = new AtomicInteger();
	private final AtomicInteger publishes = new AtomicInteger();
	private final AtomicInteger nextPacketId = new AtomicInteger();
	/** Whether to send a QoS 1 PUBLISH right after each CONNACK, see {@link #setPublishOnConnect(String)}. */
	private volatile String publishOnConnect;
	private volatile boolean answering = true;
	private volatile long connackDelayMillis;
	private volatile long pubAckDelayMillis;
	private volatile boolean closed;

	public FakeMqttBroker() throws IOException {
		ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		port = socket.getLocalPort();
		listen(socket);
	}

	public String getServerURI() {
		return "tcp://127.0.0.1:" + port;
	}

	/**
	 * When true, the broker stops listening and drops all connections, so connects are refused
	 * like by a broker that is down; when false, it listens on the same port again.
	 */
	public void setRefusing(boolean refusing) throws IOException {
		if (refusing) {
			serverSocket.close();
			dropConnections();
		} else if (serverSocket.isClosed()) {
			ServerSocket socket = new ServerSocket();
			socket.setReuseAddress(true);
			socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 50);
			listen(socket);
		}
	}

	private void listen(final ServerSocket socket) {
		serverSocket = socket;
		Thread acceptor = new Thread(new Runnable() {
			public void run() {
				acceptLoop(socket);
			}
		}, "FakeMqttBroker-accept");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	/**
	 * When false, the broker keeps accepting connections and reading packets but sends nothing,
	 * not even a CONNACK.
	 */
	public void setAnswering(boolean answering) {
		this.answering = answering;
	}

	/** Number of CONNECT packets received so far. */
	public int getConnectCount() {
		return connects.get();
	}

	/** Number of PUBACK packets received so far (acknowledgements of QoS 1 messages sent to the client). */
	public int getPubAckCount() {
		return pubAcks.get();
	}

	/**
	 * When set, the broker sends a QoS 1 PUBLISH to this topic right after every CONNACK, like a
	 * broker delivering a message queued in a persistent session before the client subscribes.
	 */
	public void setPublishOnConnect(String topic) {
		this.publishOnConnect = topic;
	}

	/** Delays each PUBACK, so that publishes stay in flight for a while. */
	public void setPubAckDelay(long millis) {
		this.pubAckDelayMillis = millis;
	}

	/** Delays each CONNACK, so that a connect attempt stays under way for a while. */
	public void setConnackDelay(long millis) {
		this.connackDelayMillis = millis;
	}

	/** Number of PUBLISH packets received so far. */
	public int getPublishCount() {
		return publishes.get();
	}

	/** Number of PINGREQ packets received so far. */
	public int getPingCount() {
		return pings.get();
	}

	/** Topic filters subscribed on the connection opened by the n-th CONNECT (1-based). */
	public List<String> getSubscriptions(int connectNumber) {
		List<String> result = new ArrayList<String>();
		String prefix = connectNumber + ":";
		for (String entry : subscriptions) {
			if (entry.startsWith(prefix)) {
				result.add(entry.substring(prefix.length()));
			}
		}
		return result;
	}

	/** Closes all client connections; the broker keeps accepting new ones. */
	public void dropConnections() {
		for (Connection connection : connections) {
			connection.close();
		}
	}

	/** Sends a QoS 0 PUBLISH to every connected client. */
	public void publish(String topic, String payload) throws IOException {
		publish(topic, payload, 0);
	}

	/** Sends a PUBLISH with QoS 0 or 1 to every connected client. */
	public void publish(String topic, String payload, int qos) throws IOException {
		for (Connection connection : connections) {
			if (connection.connected) {
				connection.sendPublish(topic, payload.getBytes(StandardCharsets.UTF_8), qos);
			}
		}
	}

	/** Sends a PUBLISH with a binary payload and QoS 0 or 1 to every connected client. */
	public void publish(String topic, byte[] payload, int qos) throws IOException {
		for (Connection connection : connections) {
			if (connection.connected) {
				connection.sendPublish(topic, payload, qos);
			}
		}
	}

	/** Writes the bytes unchanged to every connected client, e.g. a forged packet header. */
	public void sendRaw(byte[] bytes) throws IOException {
		for (Connection connection : connections) {
			if (connection.connected) {
				connection.write(bytes);
			}
		}
	}

	public void close() {
		closed = true;
		try {
			serverSocket.close();
		} catch (IOException e) {
			// ignore
		}
		dropConnections();
	}

	private void acceptLoop(ServerSocket socket) {
		while (!closed && !socket.isClosed()) {
			try {
				final Connection connection = new Connection(socket.accept());
				connections.add(connection);
				Thread reader = new Thread(new Runnable() {
					public void run() {
						connection.readLoop();
					}
				}, "FakeMqttBroker-connection");
				reader.setDaemon(true);
				reader.start();
			} catch (IOException e) {
				// server socket closed
			}
		}
	}

	private final class Connection {
		private final Socket socket;
		private final OutputStream out;
		private int protocolLevel = 4;
		private int connectNumber;
		private volatile boolean connected;

		Connection(Socket socket) throws IOException {
			this.socket = socket;
			this.out = socket.getOutputStream();
		}

		void readLoop() {
			try {
				DataInputStream in = new DataInputStream(socket.getInputStream());
				while (true) {
					int header = in.read();
					if (header < 0) {
						return;
					}
					byte[] body = new byte[readVariableInt(in)];
					in.readFully(body);
					handle(header, new DataInputStream(new ByteArrayInputStream(body)), body.length);
				}
			} catch (IOException e) {
				// connection closed
			} finally {
				close();
			}
		}

		private void handle(int header, DataInputStream packet, int length) throws IOException {
			int type = header >> 4;
			if (type == CONNECT) {
				packet.readFully(new byte[packet.readUnsignedShort()]); // protocol name
				protocolLevel = packet.readUnsignedByte();
				connectNumber = connects.incrementAndGet();
				if (connackDelayMillis > 0) {
					try {
						Thread.sleep(connackDelayMillis);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				if (answering) {
					connected = true;
					send(0x20, protocolLevel == 5 ? new byte[] { 0, 0, 0 } : new byte[] { 0, 0 });
					if (publishOnConnect != null) {
						sendPublish(publishOnConnect, new byte[] { 'q' }, 1);
					}
				}
			} else if (type == SUBSCRIBE) {
				int packetId = packet.readUnsignedShort();
				int remaining = length - 2 - skipProperties(packet);
				ByteArrayOutputStream ack = new ByteArrayOutputStream();
				writeShort(ack, packetId);
				if (protocolLevel == 5) {
					ack.write(0); // no properties
				}
				while (remaining > 0) {
					int topicLength = packet.readUnsignedShort();
					byte[] topic = new byte[topicLength];
					packet.readFully(topic);
					int options = packet.readUnsignedByte();
					remaining -= 3 + topicLength;
					subscriptions.add(connectNumber + ":" + new String(topic, StandardCharsets.UTF_8));
					ack.write(Math.min(options & 0x03, 1)); // granted QoS, at most 1
				}
				if (answering) {
					send(0x90, ack.toByteArray());
				}
			} else if (type == UNSUBSCRIBE) {
				int packetId = packet.readUnsignedShort();
				int remaining = length - 2 - skipProperties(packet);
				ByteArrayOutputStream ack = new ByteArrayOutputStream();
				writeShort(ack, packetId);
				if (protocolLevel == 5) {
					ack.write(0); // no properties
					while (remaining > 0) {
						int topicLength = packet.readUnsignedShort();
						packet.readFully(new byte[topicLength]);
						remaining -= 2 + topicLength;
						ack.write(0); // success
					}
				}
				if (answering) {
					send(0xB0, ack.toByteArray());
				}
			} else if (type == PUBLISH) {
				publishes.incrementAndGet();
				int qos = (header >> 1) & 0x03;
				if (qos == 1) {
					packet.readFully(new byte[packet.readUnsignedShort()]); // topic
					int packetId = packet.readUnsignedShort();
					ByteArrayOutputStream ack = new ByteArrayOutputStream();
					writeShort(ack, packetId);
					if (pubAckDelayMillis > 0) {
						try {
							Thread.sleep(pubAckDelayMillis);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					if (answering) {
						send(0x40, ack.toByteArray());
					}
				}
			} else if (type == PUBACK) {
				pubAcks.incrementAndGet();
			} else if (type == PINGREQ) {
				pings.incrementAndGet();
				if (answering) {
					send(0xD0, new byte[0]);
				}
			} else if (type == DISCONNECT) {
				close();
			}
		}

		/** Skips an MQTT 5 property block and returns the number of bytes it took. */
		private int skipProperties(DataInputStream packet) throws IOException {
			if (protocolLevel != 5) {
				return 0;
			}
			int propertiesLength = readVariableInt(packet);
			packet.readFully(new byte[propertiesLength]);
			return variableIntSize(propertiesLength) + propertiesLength;
		}

		void sendPublish(String topic, byte[] payload, int qos) throws IOException {
			ByteArrayOutputStream body = new ByteArrayOutputStream();
			byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
			writeShort(body, topicBytes.length);
			body.write(topicBytes);
			if (qos > 0) {
				writeShort(body, nextPacketId.incrementAndGet() % 65535 + 1);
			}
			if (protocolLevel == 5) {
				body.write(0); // no properties
			}
			body.write(payload);
			send(0x30 | (qos << 1), body.toByteArray());
		}

		private void send(int header, byte[] body) throws IOException {
			ByteArrayOutputStream packet = new ByteArrayOutputStream();
			packet.write(header);
			int length = body.length;
			do {
				int digit = length % 128;
				length /= 128;
				packet.write(length > 0 ? digit | 0x80 : digit);
			} while (length > 0);
			packet.write(body);
			write(packet.toByteArray());
		}

		void write(byte[] bytes) throws IOException {
			synchronized (out) {
				out.write(bytes);
				out.flush();
			}
		}

		void close() {
			connected = false;
			connections.remove(this);
			try {
				socket.close();
			} catch (IOException e) {
				// ignore
			}
		}
	}

	private static int readVariableInt(DataInputStream in) throws IOException {
		int value = 0;
		int multiplier = 1;
		int digit;
		do {
			digit = in.readUnsignedByte();
			value += (digit & 0x7F) * multiplier;
			multiplier *= 128;
		} while ((digit & 0x80) != 0);
		return value;
	}

	private static int variableIntSize(int value) {
		int size = 1;
		while (value >= 128) {
			value /= 128;
			size++;
		}
		return size;
	}

	private static void writeShort(ByteArrayOutputStream out, int value) {
		out.write((value >> 8) & 0xFF);
		out.write(value & 0xFF);
	}
}
