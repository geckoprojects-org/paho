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
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;

import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * The file persistence keeps the directory with the stored messages to its owner, and the client
 * id cannot move it out of the data directory.
 */
public class FilePersistencePermissionsTest {

	private File dataDir;
	private MqttDefaultFilePersistence persistence;

	@Before
	public void setUp() throws IOException {
		Assume.assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		dataDir = Files.createTempDirectory("paho-persistence").toFile();
		persistence = new MqttDefaultFilePersistence(dataDir.getAbsolutePath());
	}

	@After
	public void tearDown() throws Exception {
		if (persistence != null) {
			persistence.close();
		}
		if (dataDir != null) {
			delete(dataDir);
		}
	}

	private static void delete(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				delete(child);
			}
		}
		file.delete();
	}

	@Test
	public void testClientDirectoryIsOwnerOnly() throws Exception {
		persistence.open("client/../x");
		File[] dirs = dataDir.listFiles();
		Assert.assertEquals(1, dirs.length);
		Assert.assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dirs[0].toPath())));
	}

	@Test
	public void testClientIdCannotLeaveTheDataDirectory() throws Exception {
		persistence.open("client/../x");
		File[] dirs = dataDir.listFiles();
		Assert.assertEquals(1, dirs.length);
		Assert.assertFalse(dirs[0].getName(), dirs[0].getName().contains("/") || dirs[0].getName().contains(".."));
	}
}
