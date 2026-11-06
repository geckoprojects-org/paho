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

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.mqttv5.client.internal.MqttConnectionState;
import org.junit.Assert;
import org.junit.Test;

/**
 * The outgoing topic alias counter stops when the server's aliases are taken, instead of growing
 * with every publish to a topic without alias until it overflows.
 */
public class TopicAliasCounterTest {

	@Test
	public void testCounterStopsAtTheMaximum() throws Exception {
		MqttConnectionState state = new MqttConnectionState("alias");
		state.setOutgoingTopicAliasMaximum(Integer.valueOf(2));
		Assert.assertEquals(1, state.getNextOutgoingTopicAlias().intValue());
		Assert.assertEquals(2, state.getNextOutgoingTopicAlias().intValue());
		for (int i = 0; i < 100; i++) {
			Assert.assertEquals("no alias left", 0, state.getNextOutgoingTopicAlias().intValue());
		}
		Field counter = MqttConnectionState.class.getDeclaredField("nextOutgoingTopicAlias");
		counter.setAccessible(true);
		Assert.assertEquals("counter kept growing", 3, ((AtomicInteger) counter.get(state)).get());
	}

	@Test
	public void testNoNegativeAliasAfterManyPublishes() throws Exception {
		MqttConnectionState state = new MqttConnectionState("alias");
		state.setOutgoingTopicAliasMaximum(Integer.valueOf(10));
		Field counter = MqttConnectionState.class.getDeclaredField("nextOutgoingTopicAlias");
		counter.setAccessible(true);
		// the state after 2^31 publishes to topics without alias on one connection
		((AtomicInteger) counter.get(state)).set(Integer.MAX_VALUE);
		Assert.assertEquals(0, state.getNextOutgoingTopicAlias().intValue());
		Assert.assertEquals(0, state.getNextOutgoingTopicAlias().intValue());
	}
}
