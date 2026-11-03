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
package org.eclipse.paho.loadtest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * A TCP proxy in front of the broker that drops all connections on request, as a network outage
 * does. Its threads are named "proxy-" and not counted as client threads.
 */
final class DropProxy implements AutoCloseable {

	private final ServerSocket server;
	private final String host;
	private final int port;
	private final List<Socket> sockets = new ArrayList<Socket>();
	private volatile boolean closed;

	DropProxy(String host, int port) throws IOException {
		this.host = host;
		this.port = port;
		server = new ServerSocket(0, 512, InetAddress.getLoopbackAddress());
		Thread acceptor = new Thread(new Runnable() {
			public void run() {
				accept();
			}
		}, "proxy-accept");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	String getServerURI() {
		return "tcp://localhost:" + server.getLocalPort();
	}

	private void accept() {
		while (!closed) {
			try {
				Socket client = server.accept();
				Socket upstream = new Socket(host, port);
				client.setTcpNoDelay(true);
				upstream.setTcpNoDelay(true);
				synchronized (sockets) {
					sockets.add(client);
					sockets.add(upstream);
				}
				pump(client, upstream);
				pump(upstream, client);
			} catch (IOException e) {
				// closed, or the broker refused: the client sees a failed connect
			}
		}
	}

	private static void pump(final Socket from, final Socket to) {
		Thread thread = new Thread(new Runnable() {
			public void run() {
				byte[] buffer = new byte[8192];
				try {
					InputStream in = from.getInputStream();
					OutputStream out = to.getOutputStream();
					int n;
					while ((n = in.read(buffer)) >= 0) {
						out.write(buffer, 0, n);
					}
				} catch (IOException e) {
					// dropped
				} finally {
					closeQuietly(from);
					closeQuietly(to);
				}
			}
		}, "proxy-pump");
		thread.setDaemon(true);
		thread.start();
	}

	/** Drops all connections; new ones are accepted again. */
	void dropAll() {
		List<Socket> current;
		synchronized (sockets) {
			current = new ArrayList<Socket>(sockets);
			sockets.clear();
		}
		for (Socket socket : current) {
			closeQuietly(socket);
		}
	}

	public void close() {
		closed = true;
		closeQuietly(server);
		dropAll();
	}

	private static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception e) {
			// ignore
		}
	}
}
