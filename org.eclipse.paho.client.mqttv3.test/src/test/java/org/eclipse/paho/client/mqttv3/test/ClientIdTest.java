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
package org.eclipse.paho.client.mqttv3.test;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.junit.Assert;
import org.junit.Test;

/**
 * Generated client ids are random, not a time stamp, and fit the 23 characters of MQTT 3.1.
 */
public class ClientIdTest {

	@Test
	public void testGeneratedClientIdsAreRandomAndShort() {
		Set<String> ids = new HashSet<String>();
		for (int i = 0; i < 1000; i++) {
			String id = MqttAsyncClient.generateClientId();
			Assert.assertTrue(id, id.matches("paho[0-9a-f]{16}"));
			Assert.assertTrue(id, id.length() <= 23);
			ids.add(id);
		}
		Assert.assertEquals("duplicate client ids", 1000, ids.size());
	}

	@Test
	public void testClientIdsInARowShareNoCommonPrefix() {
		// a nano time stamp keeps its leading digits for seconds
		String a = MqttAsyncClient.generateClientId();
		String b = MqttAsyncClient.generateClientId();
		String c = MqttAsyncClient.generateClientId();
		Assert.assertFalse(a + " " + b + " " + c, a.substring(0, 10).equals(b.substring(0, 10)) && b.substring(0, 10).equals(c.substring(0, 10)));
	}
}
