/*******************************************************************************
 * Copyright (c) 2009, 2019 IBM Corp.
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
 *    Dave Locke - initial API and implementation and/or initial documentation
 *    Ian Craggs - per subscription message handlers (bug 466579)
 *    Ian Craggs - ack control (bug 472172)
 *    James Sutton - Automatic Reconnect & Offline Buffering
 *    Mark Hoffmann - inbound QoS 2 logged at FINE
 *    Mark Hoffmann - message listeners replaced on re-subscribe, guarded by a lock
 *    Mark Hoffmann - one subscription identifier for several filters, mixed id and topic routing
 */
package org.eclipse.paho.mqttv5.client.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttActionListener;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.MqttToken;
import org.eclipse.paho.mqttv5.client.logging.Logger;
import org.eclipse.paho.mqttv5.client.logging.LoggerFactory;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttAuth;
import org.eclipse.paho.mqttv5.common.packet.MqttDisconnect;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.MqttPubAck;
import org.eclipse.paho.mqttv5.common.packet.MqttPubComp;
import org.eclipse.paho.mqttv5.common.packet.MqttPublish;
import org.eclipse.paho.mqttv5.common.packet.MqttReturnCode;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.eclipse.paho.mqttv5.common.util.MqttTopicValidator;

/**
 * Bridge between Receiver and the external API. This class gets called by
 * Receiver, and then converts the comms-centric MQTT message objects into ones
 * understood by the external API.
 */
public class CommsCallback implements Runnable {
	private static final String CLASS_NAME = CommsCallback.class.getName();
	private Logger log = LoggerFactory.getLogger(LoggerFactory.MQTT_CLIENT_MSG_CAT, CLASS_NAME);

	private static final int INBOUND_QUEUE_SIZE = 10;
	private MqttCallback mqttCallback;
	private MqttCallback reconnectInternalCallback;
	private HashMap<Integer, IMqttMessageListener> callbackMap; // Map of message handler callbacks to internal IDs
	private HashMap<String, Integer> callbackTopicMap; // Map of Topic Strings to internal callback Ids
	// Map of Subscription Ids to callback Ids: one SUBSCRIBE with several filters links them all to its identifier
	private HashMap<Integer, List<Integer>> subscriptionIdMap;
	private HashMap<Integer, Integer> callbackSubscriptionIdMap; // Map of callback Ids to their Subscription Id
	/** Guards the listener maps, changed by application threads and read by the callback thread. */
	private final Object listenerLock = new Object();
	private AtomicInteger messageHandlerId = new AtomicInteger(0);
	private ClientComms clientComms;
	private ArrayList<MqttPublish> messageQueue;
	private ArrayList<MqttToken> completeQueue;

	private enum State {STOPPED, RUNNING, QUIESCING}

	private State current_state = State.STOPPED;
	private State target_state = State.STOPPED;	
	private final Object lifecycle = new Object();
	private Thread callbackThread;
	private String threadName;
	private Future<?> callbackFuture;
	
	private final Object workAvailable = new Object();
	private final Object spaceAvailable = new Object();
	private ClientState clientState;
	private boolean manualAcks = false;


	CommsCallback(ClientComms clientComms) {
		this.clientComms = clientComms;
		this.messageQueue = new ArrayList<>(INBOUND_QUEUE_SIZE);
		this.completeQueue = new ArrayList<>(INBOUND_QUEUE_SIZE);
		this.callbackMap = new HashMap<>();
		this.callbackTopicMap = new HashMap<>();
		this.subscriptionIdMap = new HashMap<>();
		this.callbackSubscriptionIdMap = new HashMap<>();
		log.setResourceName(clientComms.getClient().getClientId());
	}

	public void setClientState(ClientState clientState) {
		this.clientState = clientState;
	}

	/**
	 * Starts up the Callback thread.
	 * 
	 * @param threadName
	 *            The name of the thread
	 * @param executorService
	 *            the {@link ExecutorService}
	 */
	public void start(String threadName, ExecutorService executorService) {
		this.threadName = threadName;
		synchronized (lifecycle) {
			if (current_state == State.STOPPED) {
				// Preparatory work before starting the background thread.
				// For safety ensure any old events are cleared.
				synchronized (workAvailable) {
					messageQueue.clear();
					completeQueue.clear();
				}
				target_state = State.RUNNING;
				if (executorService == null) {
					new Thread(this).start();
				} else {
					callbackFuture = executorService.submit(this);
				}
			}
		}
		while (!isRunning()) {
			try { Thread.sleep(100); } catch (Exception e) { }
		}			
	}

	/**
	 * Stops the callback thread. This call will block until stop has completed.
	 */
	public void stop() {
		final String methodName = "stop";
		synchronized (lifecycle) {
			if (callbackFuture != null) {
				callbackFuture.cancel(true);
			}
		}
		if (isRunning()) {
			// @TRACE 700=stopping
			log.fine(CLASS_NAME, methodName, "700");
			synchronized (lifecycle) {
				target_state = State.STOPPED;
			}
			if (!Thread.currentThread().equals(callbackThread)) {
				synchronized (workAvailable) {
					// @TRACE 701=notify workAvailable and wait for run
					// to finish
					log.fine(CLASS_NAME, methodName, "701");
					workAvailable.notifyAll();
				}
				// Wait for the thread to finish.
				while (isRunning()) {
					try { Thread.sleep(100); } catch (Exception e) { }
					clientState.notifyQueueLock();
				}
			}
			callbackThread = null;
			// @TRACE 703=stopped
			log.fine(CLASS_NAME, methodName, "703");
		}
	}

	public void setCallback(MqttCallback mqttCallback) {
		this.mqttCallback = mqttCallback;
	}

	public void setReconnectCallback(MqttCallback callback) {
		this.reconnectInternalCallback = callback;
	}

	public void setManualAcks(boolean manualAcks) {
		this.manualAcks = manualAcks;
	}

	public void run() {
		final String methodName = "run";
		callbackThread = Thread.currentThread();
		callbackThread.setName(threadName);
		
		synchronized (lifecycle) {
			current_state = State.RUNNING;
		}

		while (isRunning()) {
			try {
				// If no work is currently available, then wait until there is some...
				try {
					synchronized (workAvailable) {
						if (isRunning() && messageQueue.isEmpty()
								&& completeQueue.isEmpty()) {
							// @TRACE 704=wait for workAvailable
							log.fine(CLASS_NAME, methodName, "704");
							workAvailable.wait();
						}
					}
				} catch (InterruptedException e) {
				}

				if (isRunning()) {
					// Check for deliveryComplete callbacks...
					MqttToken token = null;
					synchronized (workAvailable) {
						if (!completeQueue.isEmpty()) {
							// First call the delivery arrived callback if needed
							token = completeQueue.get(0);
							completeQueue.remove(0);
						}
					}
					if (null != token) {
						handleActionComplete(token);
					}

					// Check for messageArrived callbacks...
					MqttPublish message = null;
					synchronized (workAvailable) {
						if (!messageQueue.isEmpty()) {
							// Note, there is a window on connect where a publish
							// could arrive before we've
							// finished the connect logic.
							message = messageQueue.get(0);
							messageQueue.remove(0);
						}
					}
					if (null != message) {
						handleMessage(message);
					}
				}

				if (isQuiescing()) {
					clientState.checkQuiesceLock();
				}

			} catch (Throwable ex) {
				// Users code could throw an Error or Exception e.g. in the case
				// of class NoClassDefFoundError
				// @TRACE 714=callback threw exception
				log.fine(CLASS_NAME, methodName, "714", null, ex);

				clientComms.shutdownConnection(null, new MqttException(ex), null);
			} finally {

			    synchronized (spaceAvailable) {
                    // Notify the spaceAvailable lock, to say that there's now
                    // some space on the queue...

					// @TRACE 706=notify spaceAvailable
					log.fine(CLASS_NAME, methodName, "706");
					spaceAvailable.notifyAll();
				}
			}
		}
		synchronized (lifecycle) {
			current_state = State.STOPPED;
		}
		callbackThread = null;
	}

	private void handleActionComplete(MqttToken token) throws MqttException {
		final String methodName = "handleActionComplete";
		synchronized (token) {
			// @TRACE 705=callback and notify for key={0}
			log.fine(CLASS_NAME, methodName, "705", new Object[] { token.internalTok.getKey() });
			if (token.isComplete()) {
				// Finish by doing any post processing such as delete
				// from persistent store but only do so if the action
				// is complete
				clientState.notifyComplete(token);
			}

			// Unblock any waiters and if pending complete now set completed
			token.internalTok.notifyComplete();

			if (!token.internalTok.isNotified()) {
				// If a callback is registered and delivery has finished
				// call delivery complete callback.
				if (mqttCallback != null && token.internalTok.isDeliveryToken() == true && token.isComplete()) {
					try {
						mqttCallback.deliveryComplete(token);
					} catch (Throwable ex) {
						// Just log the fact that an exception was thrown
						// @TRACE 726=Ignoring Exception thrown from deliveryComplete {0}
						log.fine(CLASS_NAME, methodName, "726", new Object[] { ex });
					}
				}
				// Now call async action completion callbacks
				fireActionEvent(token);
			}

			// Set notified so we don't tell the user again about this action.
			if (token.isComplete()) {
				if (token.internalTok.isDeliveryToken() == true || token.getActionCallback() instanceof MqttActionListener) {
					token.internalTok.setNotified(true);
				}
			}

		}
	}

	/**
	 * This method is called when the connection to the server is lost. If there is
	 * no cause then it was a clean disconnect. The connectionLost callback will be
	 * invoked if registered and run on the thread that requested shutdown e.g.
	 * receiver or sender thread. If the request was a user initiated disconnect
	 * then the disconnect token will be notified.
	 * 
	 * @param cause
	 *            the reason behind the loss of connection.
	 * @param message
	 *            The {@link MqttDisconnect} packet sent by the server
	 */
	public void connectionLost(MqttException cause, MqttDisconnect message) {
		final String methodName = "connectionLost";
		// If there was a problem and a client callback has been set inform
		// the connection lost listener of the problem.
		try {
			if (mqttCallback != null && message != null) {

				// @TRACE 722=Server initiated disconnect, connection closed. Disconnect={0}
				log.fine(CLASS_NAME, methodName, "722", new Object[] { message.toString() });
				MqttDisconnectResponse disconnectResponse = new MqttDisconnectResponse(message.getReturnCode(),
						message.getProperties().getReasonString(),
						(ArrayList<UserProperty>) message.getProperties().getUserProperties(),
						message.getProperties().getServerReference());
				mqttCallback.disconnected(disconnectResponse);
			} else if (mqttCallback != null && cause != null) {
				// @TRACE 708=call connectionLost
				log.fine(CLASS_NAME, methodName, "708", new Object[] { cause });
				MqttDisconnectResponse disconnectResponse = new MqttDisconnectResponse(cause);
				mqttCallback.disconnected(disconnectResponse);
			}
			if (reconnectInternalCallback != null && cause != null) {
				MqttDisconnectResponse disconnectResponse = new MqttDisconnectResponse(cause);

				reconnectInternalCallback.disconnected(disconnectResponse);
			}
		} catch (Throwable t) {
			// Just log the fact that an exception was thrown
			// @TRACE 720=Ignoring Exception thrown from connectionLost {0}
			log.fine(CLASS_NAME, methodName, "720", new Object[] { t });
		}
	}

	/**
	 * An action has completed - if a completion listener has been set on the token
	 * then invoke it with the outcome of the action.
	 * 
	 * @param token
	 *            The {@link MqttToken} that has completed
	 */
	public void fireActionEvent(MqttToken token) {
		final String methodName = "fireActionEvent";

		if (token != null) {
			MqttActionListener asyncCB = token.getActionCallback();
			if (asyncCB != null) {
				if (token.getException() == null) {
					// @TRACE 716=call onSuccess key={0}
					log.fine(CLASS_NAME, methodName, "716", new Object[] { token.internalTok.getKey() });
					asyncCB.onSuccess(token);
				} else {
					// @TRACE 717=call onFailure key {0}
					log.fine(CLASS_NAME, methodName, "716", new Object[] { token.internalTok.getKey() });
					asyncCB.onFailure(token, token.getException());
				}
			}
		}
	}

	/**
	 * This method is called when a message arrives on a topic. Messages are only
	 * added to the queue for inbound messages if the client is not quiescing.
	 * 
	 * @param sendMessage
	 *            the MQTT SEND message.
	 */
	public void messageArrived(MqttPublish sendMessage) {
		final String methodName = "messageArrived";
		if (mqttCallback != null || hasMessageListeners()) {
			// If we already have enough messages queued up in memory, wait
			// until some more queue space becomes available. This helps
			// the client protect itself from getting flooded by messages
			// from the server.
			synchronized (spaceAvailable) {
				while (isRunning() && !isQuiescing() && messageQueue.size() >= INBOUND_QUEUE_SIZE) {
					try {
						// @TRACE 709=wait for spaceAvailable
						log.fine(CLASS_NAME, methodName, "709");
						spaceAvailable.wait(200);
					} catch (InterruptedException ex) {
					}
				}
			}
			if (!isQuiescing()) {
				// Notify the CommsCallback thread that there's work to do...
				synchronized (workAvailable) {
					messageQueue.add(sendMessage);
					// @TRACE 710=new msg avail, notify workAvailable
					log.fine(CLASS_NAME, methodName, "710");
					workAvailable.notifyAll();
				}
			}
		} else {
			// No callback and no message listener (yet), e.g. a message of a persistent session
			// that arrives right after the CONNACK, before the application subscribes. Nobody can
			// take it; acknowledge it all the same, as handleMessage does for a message no listener
			// matches. Without the acknowledgement the server redelivers it on every connect.
			// @TRACE 730=no callback or message listener; message {0} on topic {1} acknowledged and discarded
			log.warning(CLASS_NAME, methodName, "730",
					new Object[] { Integer.valueOf(sendMessage.getMessageId()), sendMessage.getTopicName() });
			try {
				acknowledge(sendMessage);
			} catch (MqttException ex) {
				// @TRACE 731=could not acknowledge discarded message {0}
				log.fine(CLASS_NAME, methodName, "731", new Object[] { Integer.valueOf(sendMessage.getMessageId()) }, ex);
			}
		}
	}

	/**
	 * This method is called when an Auth Message is received.
	 * 
	 * @param authMessage
	 *            The {@link MqttAuth} message.
	 */
	public void authMessageReceived(MqttAuth authMessage) {
		String methodName = "authMessageReceived";
		if (mqttCallback != null) {
			try {
				mqttCallback.authPacketArrived(authMessage.getReturnCode(), authMessage.getProperties());
			} catch (Throwable ex) {
				// Just log the fact that an exception was thrown
				// @TRACE 727=Ignoring Exception thrown from authPacketArrived {0}
				log.fine(CLASS_NAME, methodName, "727", new Object[] { ex });
			}
		}
	}

	/**
	 * This method is called when a non-critical MQTT error has occurred in the
	 * client that the application should choose how to deal with.
	 * 
	 * @param exception
	 *            The exception that was thrown containing the cause for
	 *            disconnection.
	 */
	public void mqttErrorOccurred(MqttException exception) {
		final String methodName = "mqttErrorOccurred";
		log.warning(CLASS_NAME, methodName, "721", new Object[] { exception.getMessage() });
		if (mqttCallback != null) {
			try {
				mqttCallback.mqttErrorOccurred(exception);
			} catch (Exception ex) {
				// Just log the fact that an exception was thrown
				// @TRACE 724=Ignoring Exception thrown from mqttErrorOccurred: {0}
				log.fine(CLASS_NAME, methodName, "724", new Object[] { ex });
			}
		}
	}

	/**
	 * Let the call back thread quiesce. Prevent new inbound messages being added to
	 * the process queue and let existing work quiesce. (until the thread is told to
	 * shutdown).
	 */
	public void quiesce() {
		final String methodName = "quiesce";
		synchronized (lifecycle) {
			if (current_state == State.RUNNING)
			current_state = State.QUIESCING;
		}
		synchronized (spaceAvailable) {
			// @TRACE 711=quiesce notify spaceAvailable
			log.fine(CLASS_NAME, methodName, "711");
			// Unblock anything waiting for space...
			spaceAvailable.notifyAll();
		}
	}

	boolean areQueuesEmpty() {
		synchronized (workAvailable) {
			return completeQueue.isEmpty() && messageQueue.isEmpty();
		}
	}

	public boolean isQuiesced() {
		return (isQuiescing() && areQueuesEmpty());
	}

	private void handleMessage(MqttPublish publishMessage) throws Exception {
		final String methodName = "handleMessage";
		// If quisecing process any pending messages.
		String destName = publishMessage.getTopicName();

		// @TRACE 713=call messageArrived key={0} topic={1}
		log.fine(CLASS_NAME, methodName, "713", new Object[] { Integer.valueOf(publishMessage.getMessageId()), destName });
		deliverMessage(destName, publishMessage.getMessageId(), publishMessage.getMessage());

		// If we are not in manual ACK mode:
		if (!this.manualAcks) {
			acknowledge(publishMessage);
		}
	}

	/** Sends the acknowledgement of a received QoS 1 message (QoS 2 flows are completed by ClientState). */
	private void acknowledge(MqttPublish publishMessage) throws MqttException {
		if (publishMessage.getMessage().getQos() == 1) {
			this.clientComms.internalSend(new MqttPubAck(MqttReturnCode.RETURN_CODE_SUCCESS,
					publishMessage.getMessageId(), new MqttProperties()),
					new MqttToken(clientComms.getClient().getClientId()));
		}
	}

	public void messageArrivedComplete(int messageId, int qos) throws MqttException {
		if (qos == 1) {
			this.clientComms.internalSend(
					new MqttPubAck(MqttReturnCode.RETURN_CODE_SUCCESS, messageId, new MqttProperties()),
					new MqttToken(clientComms.getClient().getClientId()));
		} else if (qos == 2) {
			this.clientComms.deliveryComplete(messageId);
			MqttPubComp pubComp = new MqttPubComp(MqttReturnCode.RETURN_CODE_SUCCESS, messageId, new MqttProperties());
			// @TRACE 723=Creating MqttPubComp due to manual ACK: {0}
			log.fine(CLASS_NAME, "messageArrivedComplete", "723", new Object[] { pubComp });

			this.clientComms.internalSend(pubComp, new MqttToken(clientComms.getClient().getClientId()));
		}
	}

	public void asyncOperationComplete(MqttToken token) {
		final String methodName = "asyncOperationComplete";

		if (isRunning()) {
			// invoke callbacks on callback thread
			synchronized (workAvailable) {
				completeQueue.add(token);
				// @TRACE 715=new workAvailable. key={0}
				log.fine(CLASS_NAME, methodName, "715", new Object[] { token.internalTok.getKey() });
				workAvailable.notifyAll();
			}
		} else {
			// invoke async callback on invokers thread
			try {
				handleActionComplete(token);
			} catch (MqttException ex) {
				// Users code could throw an Error or Exception e.g. in the case
				// of class NoClassDefFoundError
				// @TRACE 719=callback threw ex:
				log.fine(CLASS_NAME, methodName, "719", null, ex);

				// Shutdown likely already in progress but no harm to confirm
				clientComms.shutdownConnection(null, new MqttException(ex), null);
			}

		}
	}

	/**
	 * Returns the thread used by this callback.
	 * 
	 * @return The {@link Thread}
	 */
	protected Thread getThread() {
		return callbackThread;
	}

	public void setMessageListener(Integer subscriptionId, String topicFilter, IMqttMessageListener messageListener) {
		synchronized (listenerLock) {
			// a new subscribe of the filter replaces its listener and subscription identifier
			removeListenerOfTopic(topicFilter);
			int internalId = messageHandlerId.incrementAndGet();
			this.callbackMap.put(internalId, messageListener);
			this.callbackTopicMap.put(topicFilter, internalId);

			if (subscriptionId != null) {
				List<Integer> callbackIds = this.subscriptionIdMap.get(subscriptionId);
				if (callbackIds == null) {
					callbackIds = new ArrayList<>(1);
					this.subscriptionIdMap.put(subscriptionId, callbackIds);
				}
				callbackIds.add(internalId);
				this.callbackSubscriptionIdMap.put(internalId, subscriptionId);
			}
		}
	}

	/**
	 * Removes a Message Listener by Topic. If the Topic is null or incorrect, this
	 * function will return without making any changes. It will also attempt to find
	 * any subscription IDs linked to the same message listener and will remove them
	 * too.
	 * 
	 * @param topicFilter
	 *            the topic filter that identifies the Message listener to remove.
	 */
	public void removeMessageListener(String topicFilter) {
		synchronized (listenerLock) {
			removeListenerOfTopic(topicFilter);
		}
	}

	private void removeListenerOfTopic(String topicFilter) {
		Integer callbackId = this.callbackTopicMap.remove(topicFilter);
		if (callbackId == null) {
			return;
		}
		this.callbackMap.remove(callbackId);
		// unlink the subscription ID; it stays for the other filters subscribed with it
		Integer subscriptionId = this.callbackSubscriptionIdMap.remove(callbackId);
		if (subscriptionId != null) {
			List<Integer> callbackIds = this.subscriptionIdMap.get(subscriptionId);
			callbackIds.remove(callbackId);
			if (callbackIds.isEmpty()) {
				this.subscriptionIdMap.remove(subscriptionId);
			}
		}
	}

	/**
	 * Removes a Message Listener by subscription ID. If the Subscription Identifier
	 * is null or incorrect, this function will return without making any changes.
	 * It also removes the Topic Strings linked to the subscription ID.
	 *
	 * @param subscriptionId
	 *            the subscription ID that identifies the Message listener to
	 *            remove.
	 */
	public void removeMessageListener(Integer subscriptionId) {
		synchronized (listenerLock) {
			List<Integer> callbackIds = this.subscriptionIdMap.remove(subscriptionId);
			if (callbackIds == null) {
				return;
			}
			for (Integer callbackId : callbackIds) {
				this.callbackMap.remove(callbackId);
				this.callbackSubscriptionIdMap.remove(callbackId);
				// Reverse lookup the topic to remove that as well
				removeValue(this.callbackTopicMap, callbackId);
			}
		}
	}

	private static <K> void removeValue(Map<K, Integer> map, Integer value) {
		for (Iterator<Map.Entry<K, Integer>> it = map.entrySet().iterator(); it.hasNext();) {
			if (it.next().getValue().equals(value)) {
				it.remove();
			}
		}
	}

	public void removeMessageListeners() {
		synchronized (listenerLock) {
			this.callbackMap.clear();
			this.subscriptionIdMap.clear();
			this.callbackSubscriptionIdMap.clear();
			this.callbackTopicMap.clear();
		}
	}

	private boolean hasMessageListeners() {
		synchronized (listenerLock) {
			return !callbackMap.isEmpty();
		}
	}

	protected boolean deliverMessage(String topicName, int messageId, MqttMessage aMessage) throws Exception {
		boolean delivered = false;
		String methodName = "deliverMessage";

		// collected under the lock, called outside it: a listener may subscribe or unsubscribe
		List<IMqttMessageListener> listeners = new ArrayList<>(1);
		synchronized (listenerLock) {
			// Listeners of the Subscription IDs the server sent; one listener once per ID, even
			// when it is linked to several filters of the same SUBSCRIBE
			boolean knownSubscriptionId = false;
			for (Integer subId : aMessage.getProperties().getSubscriptionIdentifiers()) {
				List<Integer> callbackIds = this.subscriptionIdMap.get(subId);
				if (callbackIds == null) {
					continue;
				}
				knownSubscriptionId = true;
				List<IMqttMessageListener> ofSubscriptionId = new ArrayList<>(1);
				for (Integer callbackId : callbackIds) {
					IMqttMessageListener listener = this.callbackMap.get(callbackId);
					if (!containsInstance(ofSubscriptionId, listener)) {
						ofSubscriptionId.add(listener);
					}
				}
				listeners.addAll(ofSubscriptionId);
			}
			// Topic filter matching for the listeners subscribed without Subscription ID; for all
			// of them when the message carries no Subscription ID this client knows
			for (Map.Entry<String, Integer> entry : this.callbackTopicMap.entrySet()) {
				if (knownSubscriptionId && this.callbackSubscriptionIdMap.containsKey(entry.getValue())) {
					continue;
				}
				if (MqttTopicValidator.isMatched(entry.getKey(), topicName)) {
					listeners.add(this.callbackMap.get(entry.getValue()));
				}
			}
		}
		for (IMqttMessageListener listener : listeners) {
			aMessage.setId(messageId);
			listener.messageArrived(topicName, aMessage);
			delivered = true;
		}

		/*
		 * if the message hasn't been delivered to a per subscription handler, give it
		 * to the default handler
		 */
		if (mqttCallback != null && !delivered) {
			aMessage.setId(messageId);
			try {
				mqttCallback.messageArrived(topicName, aMessage);
			} catch (Exception ex) {
				// Just log the fact that an exception was thrown
				// @TRACE 725=Ignoring Exception thrown from messageArrived: {0}
				log.fine(CLASS_NAME, methodName, "725", new Object[] { ex });
			}
			delivered = true;
		}

		return delivered;
	}

	private static boolean containsInstance(List<IMqttMessageListener> listeners, IMqttMessageListener listener) {
		for (IMqttMessageListener candidate : listeners) {
			if (candidate == listener) {
				return true;
			}
		}
		return false;
	}

	public boolean doesSubscriptionIdentifierExist(int subscriptionIdentifier) {
		synchronized (listenerLock) {
			return (this.subscriptionIdMap.containsKey(subscriptionIdentifier));
		}
	}

	/**
	 * Whether the subscription identifier is linked to a topic filter other than the given ones.
	 *
	 * @param subscriptionIdentifier
	 *            the subscription identifier
	 * @param topicFilters
	 *            the topic filters that may use it
	 * @return true if another topic filter uses the subscription identifier
	 */
	public boolean isSubscriptionIdentifierUsedByOtherFilter(int subscriptionIdentifier, Collection<String> topicFilters) {
		synchronized (listenerLock) {
			List<Integer> callbackIds = this.subscriptionIdMap.get(subscriptionIdentifier);
			if (callbackIds == null) {
				return false;
			}
			for (Map.Entry<String, Integer> entry : this.callbackTopicMap.entrySet()) {
				if (callbackIds.contains(entry.getValue()) && !topicFilters.contains(entry.getKey())) {
					return true;
				}
			}
			return false;
		}
	}

	public boolean isRunning() {
		boolean result;
		synchronized (lifecycle) {
			result = ((current_state == State.RUNNING || current_state == State.QUIESCING)
					&& target_state == State.RUNNING);
		}
		return result;
	}
	
	public boolean isQuiescing() {
		boolean result;
		synchronized (lifecycle) {
			result = (current_state == State.QUIESCING);
		}
		return result;
	}
	
}
