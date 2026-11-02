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

import java.util.Properties;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.Assert;
import org.junit.Test;

/**
 * Diagnostic output of the connect options does not contain key or trust store passwords.
 */
public class LogSecretsTest {

	@Test
	public void testDebugOutputHidesStorePasswords() {
		Properties ssl = new Properties();
		ssl.setProperty("com.ibm.ssl.keyStore", "/etc/keystore.jks");
		ssl.setProperty("com.ibm.ssl.keyStorePassword", "s3cret-key");
		ssl.setProperty("com.ibm.ssl.trustStorePassword", "s3cret-trust");
		MqttConnectOptions options = new MqttConnectOptions();
		options.setSSLProperties(ssl);
		String debug = options.toString();
		Assert.assertFalse(debug, debug.contains("s3cret"));
		Assert.assertTrue(debug, debug.contains("/etc/keystore.jks"));
		// the options themselves are unchanged
		Assert.assertEquals("s3cret-key", options.getSSLProperties().getProperty("com.ibm.ssl.keyStorePassword"));
	}
}
