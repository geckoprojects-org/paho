package org.eclipse.paho.mqttv5.client.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

class ExtendedByteArrayOutputStream extends ByteArrayOutputStream {

	final WebSocketNetworkModule webSocketNetworkModule;
	final WebSocketSecureNetworkModule webSocketSecureNetworkModule;
	// Frames of the MQTT sender, the pongs of the receiver and the close frame
	// must not interleave on the socket
	private final Object frameLock = new Object();

	ExtendedByteArrayOutputStream(WebSocketNetworkModule module) {
		this.webSocketNetworkModule = module;
		this.webSocketSecureNetworkModule = null;
	}

	ExtendedByteArrayOutputStream(WebSocketSecureNetworkModule module) {
		this.webSocketNetworkModule = null;
		this.webSocketSecureNetworkModule = module;
	}
	
	public void flush() throws IOException {
		final ByteBuffer byteBuffer;
		synchronized (this) {
			byteBuffer = ByteBuffer.wrap(toByteArray());
			reset();
		}
		writeFrame(new WebSocketFrame(WebSocketFrame.OPCODE_BINARY, true, byteBuffer.array()));
	}

	/**
	 * Writes a frame to the socket, one frame at a time.
	 *
	 * @param frame the frame
	 * @throws IOException if the socket cannot be written
	 */
	void writeFrame(WebSocketFrame frame) throws IOException {
		byte[] rawFrame = frame.encodeFrame();
		synchronized (frameLock) {
			OutputStream out = getSocketOutputStream();
			out.write(rawFrame);
			out.flush();
		}
	}

	OutputStream getSocketOutputStream() throws IOException {
		
		if(webSocketNetworkModule != null ){
			return webSocketNetworkModule.getSocketOutputStream();
		}
		if(webSocketSecureNetworkModule != null){
			return webSocketSecureNetworkModule.getSocketOutputStream();
		}
		return null;
	}
	
}