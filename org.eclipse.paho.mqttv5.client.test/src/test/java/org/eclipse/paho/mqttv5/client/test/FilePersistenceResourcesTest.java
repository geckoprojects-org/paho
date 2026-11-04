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

import java.io.File;

import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.common.MqttPersistable;
import org.eclipse.paho.mqttv5.common.MqttPersistenceException;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The file persistence closes its files when writing or locking fails, so that errors (a full
 * disk, a second client with the same client ID) do not use up file descriptors.
 */
public class FilePersistenceResourcesTest {

	private static final int ATTEMPTS = 50;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Before
	public void setUp() {
		Assume.assumeTrue("needs /proc/self/fd", new File("/proc/self/fd").isDirectory());
	}

	private static int openFileDescriptors() {
		return new File("/proc/self/fd").list().length;
	}

	/** A message whose header length exceeds its bytes: the write throws. */
	private static final MqttPersistable BROKEN = new MqttPersistable() {
		public byte[] getHeaderBytes() {
			return new byte[1];
		}

		public int getHeaderLength() {
			return 10;
		}

		public int getHeaderOffset() {
			return 0;
		}

		public byte[] getPayloadBytes() {
			return null;
		}

		public int getPayloadLength() {
			return 0;
		}

		public int getPayloadOffset() {
			return 0;
		}
	};

	@Test
	public void testFailedPutClosesTheFile() throws Exception {
		MqttDefaultFilePersistence persistence = new MqttDefaultFilePersistence(folder.getRoot().getAbsolutePath());
		persistence.open("put");
		int before = openFileDescriptors();
		for (int i = 0; i < ATTEMPTS; i++) {
			try {
				persistence.put("s-" + i, BROKEN);
				Assert.fail("the write cannot succeed");
			} catch (MqttPersistenceException | IndexOutOfBoundsException e) {
				// as a full disk
			}
		}
		int leaked = openFileDescriptors() - before;
		persistence.close();
		Assert.assertTrue(leaked + " file descriptors left open by " + ATTEMPTS + " failed writes", leaked < 5);
	}

	@Test
	public void testFailedLockClosesTheFile() throws Exception {
		MqttDefaultFilePersistence holder = new MqttDefaultFilePersistence(folder.getRoot().getAbsolutePath());
		holder.open("same");
		int before = openFileDescriptors();
		for (int i = 0; i < ATTEMPTS; i++) {
			// a second client with the same client ID, opening on every reconnect attempt
			MqttDefaultFilePersistence second = new MqttDefaultFilePersistence(folder.getRoot().getAbsolutePath());
			try {
				second.open("same");
			} catch (MqttPersistenceException e) {
				// in use
			}
		}
		int leaked = openFileDescriptors() - before;
		holder.close();
		Assert.assertTrue(leaked + " file descriptors left open by " + ATTEMPTS + " failed locks", leaked < 5);
	}
}
