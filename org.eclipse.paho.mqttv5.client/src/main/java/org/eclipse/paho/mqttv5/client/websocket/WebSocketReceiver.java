/*******************************************************************************
 * Copyright (c) 2009, 2014 IBM Corp.
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
 *    James Sutton - Bug 459142 - WebSocket support for the Java client.
 *    Mark Hoffmann - bounded WebSocket input
 *    Mark Hoffmann - ping, pong and fragmented frames
 *    Mark Hoffmann - bulk pipe writes, one SecureRandom for the masking keys
 */
package org.eclipse.paho.mqttv5.client.websocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.SocketTimeoutException;

import org.eclipse.paho.mqttv5.client.logging.Logger;
import org.eclipse.paho.mqttv5.client.logging.LoggerFactory;

public class WebSocketReceiver implements Runnable{
	private final int maxPayloadLength;

	private static final String CLASS_NAME = WebSocketReceiver.class.getName();
	private Logger log = LoggerFactory.getLogger(LoggerFactory.MQTT_CLIENT_MSG_CAT, CLASS_NAME);

	private boolean running = false;
	private boolean stopping = false;
	private final Object lifecycle = new Object();
	private InputStream input;
	private Thread receiverThread = null;
	private volatile boolean receiving;
	private PipedOutputStream pipedOutputStream;
	// Answers pings; null: pings are ignored
	private final ExtendedByteArrayOutputStream output;
	// Within a message whose last frame is still to come
	private boolean fragmented = false;

	public WebSocketReceiver(InputStream input, PipedInputStream pipedInputStream) throws IOException{
		this(input, pipedInputStream, WebSocketFrame.MAX_PAYLOAD_LENGTH);
	}

	/**
	 * @param input the socket's input stream
	 * @param pipedInputStream the stream the payload of the frames goes to
	 * @param maxPayloadLength the largest frame payload accepted
	 * @throws IOException if the pipe cannot be connected
	 */
	public WebSocketReceiver(InputStream input, PipedInputStream pipedInputStream, int maxPayloadLength) throws IOException{
		this(input, pipedInputStream, maxPayloadLength, null);
	}

	/**
	 * @param input the socket's input stream
	 * @param pipedInputStream the stream the payload of the frames goes to
	 * @param maxPayloadLength the largest frame payload accepted
	 * @param output the module's output, which answers pings with pongs
	 * @throws IOException if the pipe cannot be connected
	 */
	WebSocketReceiver(InputStream input, PipedInputStream pipedInputStream, int maxPayloadLength, ExtendedByteArrayOutputStream output) throws IOException{
		this.output = output;
		this.maxPayloadLength = maxPayloadLength;
		this.input = input;
		this.pipedOutputStream = new PipedOutputStream();
		pipedInputStream.connect(pipedOutputStream);
	}

	/**
	 * Starts up the WebSocketReceiver's thread
	 * @param threadName The name of the thread
	 */
	public void start(String threadName){
		final String methodName = "start";
		//@TRACE 855=starting
		log.fine(CLASS_NAME, methodName, "855");
		synchronized (lifecycle) {
			if(!running) {
				running = true;
				receiverThread = new Thread(this, threadName);
				receiverThread.start();
			}
		}
	}

	/**
	 * Stops this WebSocketReceiver's thread.
	 * This call will block.
	 */
	public void stop() {
		final String methodName = "stop";
		stopping = true;
        boolean closed = false;
		synchronized (lifecycle) {
			//@TRACE 850=stopping
			log.fine(CLASS_NAME,methodName, "850");
			if(running) {
				running = false;
				receiving = false;
                closed = true;
				closeOutputStream();

			}
		}
		if(closed && !Thread.currentThread().equals(receiverThread) && (receiverThread != null)) {
			try {
				// Wait for the thread to finish
		        //This must not happen in the synchronized block, otherwise we can deadlock ourselves!
				receiverThread.join();
			} catch (InterruptedException ex) {
				// Interrupted Exception
			}
		}
		receiverThread = null;
		//@TRACE 851=stopped
		log.fine(CLASS_NAME, methodName, "851");
	}

	public void run() {
		final String methodName = "run";

		while (running && (input != null)) {
			try {
				//@TRACE 852=network read message
				log.fine(CLASS_NAME, methodName, "852");
				receiving = input.available() > 0;
				WebSocketFrame incomingFrame = new WebSocketFrame(input, maxPayloadLength);
				byte opcode = incomingFrame.getOpcode();
				if (incomingFrame.isCloseFlag()) {
					if(!stopping){
						throw new IOException("Server sent a WebSocket Frame with the Stop OpCode");
					}
				} else if (opcode == WebSocketFrame.OPCODE_PING) {
					// RFC 6455, section 5.5.2: answered with the same payload
					if (output != null && !stopping) {
						output.writeFrame(new WebSocketFrame(WebSocketFrame.OPCODE_PONG, true, incomingFrame.getPayload()));
					}
				} else if (opcode == WebSocketFrame.OPCODE_PONG) {
					// RFC 6455, section 5.5.3: an unsolicited pong is ignored
				} else {
					// Binary frame or continuation; MQTT does not depend on the frame
					// boundaries, the payload goes to the pipe as it comes
					if ((opcode == WebSocketFrame.OPCODE_CONTINUATION) != fragmented) {
						throw new IOException(fragmented ? "Invalid Frame: new message within a fragmented message"
								: "Invalid Frame: continuation without a fragmented message");
					}
					fragmented = !incomingFrame.isFin();
					// in one call: write(int) takes the pipe's lock and wakes the reader per byte
					pipedOutputStream.write(incomingFrame.getPayload());
					pipedOutputStream.flush();
				}

				receiving = false;

			} catch (SocketTimeoutException ex) {
				// Ignore SocketTimeoutException 
			} catch (IOException ex) {
				// Exception occurred whilst reading the stream.
				this.stop();
			}
		}
	}

	private void closeOutputStream(){
		try {
			pipedOutputStream.close();
		} catch (IOException e) {
		}
	}


	public boolean isRunning() {
		return running;
	}

	/**
	 * Returns the receiving state.
	 *
	 * @return true if the receiver is receiving data, false otherwise.
	 */
	public boolean isReceiving(){
		return receiving;
	}

}
