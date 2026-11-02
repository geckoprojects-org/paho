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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.common.packet.MqttConnect;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.Assert;
import org.junit.Test;

/**
 * What the client logs at FINE (every packet it sends, e.g. CONNECT) and the diagnostic output of
 * the connection options contain no passwords or authentication data.
 */
public class LogSecretsTest {

	@Test
	public void testConnectHidesThePassword() {
		MqttProperties properties = new MqttProperties();
		properties.setAuthenticationMethod("SCRAM-SHA-1");
		properties.setAuthenticationData("s3cret-auth".getBytes(StandardCharsets.UTF_8));
		MqttConnect connect = new MqttConnect("client", 5, true, 60, properties, new MqttProperties());
		connect.setUserName("user");
		connect.setPassword("s3cret".getBytes(StandardCharsets.UTF_8));
		String logged = connect.toString();
		Assert.assertFalse(logged, logged.contains(Arrays.toString("s3cret".getBytes(StandardCharsets.UTF_8))));
		Assert.assertFalse(logged, logged.contains(Arrays.toString("s3cret-auth".getBytes(StandardCharsets.UTF_8))));
		Assert.assertTrue(logged, logged.contains("userName=user"));
	}

	@Test
	public void testDebugOutputHidesStorePasswords() {
		Properties ssl = new Properties();
		ssl.setProperty("com.ibm.ssl.keyStore", "/etc/keystore.jks");
		ssl.setProperty("com.ibm.ssl.keyStorePassword", "s3cret-key");
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setSSLProperties(ssl);
		String debug = options.toString();
		Assert.assertFalse(debug, debug.contains("s3cret"));
		Assert.assertTrue(debug, debug.contains("/etc/keystore.jks"));
	}
}
