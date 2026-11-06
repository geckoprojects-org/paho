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
package org.eclipse.paho.mqttv5.client.test.utilities;

import java.util.concurrent.TimeUnit;

/**
 * Waiting in tests that run against a client with real threads and timers.
 * <p>
 * Every bound and every observation window is multiplied by the factor of the system property
 * {@code paho.test.timeFactor} (default 1), so a slow or loaded CI agent can be given more time
 * without touching the tests. Time is measured with {@link System#nanoTime()}, which does not jump
 * with the wall clock.
 */
public final class TestTiming {

	/** The factor for all waits, from {@code -Dpaho.test.timeFactor}; at least 1. */
	public static final double FACTOR = factor();

	private TestTiming() {
	}

	/** Something a test waits for. */
	public interface Condition {
		boolean met();
	}

	/** Returns {@code millis} multiplied by {@link #FACTOR}. */
	public static long scaled(long millis) {
		return (long) Math.ceil(millis * FACTOR);
	}

	/**
	 * Waits until the condition is met, at most {@code timeoutMillis} (scaled).
	 *
	 * @return whether the condition was met
	 */
	public static boolean waitFor(Condition condition, long timeoutMillis) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(scaled(timeoutMillis));
		while (System.nanoTime() - deadline < 0) {
			if (condition.met()) {
				return true;
			}
			Thread.sleep(50);
		}
		return condition.met();
	}

	/**
	 * Lets {@code millis} (scaled) pass, for assertions that something does <em>not</em> happen.
	 * The window should follow from the timeouts the test configures, so that a missed event
	 * would have occurred within it.
	 */
	public static void observe(long millis) throws InterruptedException {
		Thread.sleep(scaled(millis));
	}

	private static double factor() {
		try {
			return Math.max(1.0, Double.parseDouble(System.getProperty("paho.test.timeFactor", "1")));
		} catch (NumberFormatException e) {
			return 1.0;
		}
	}
}
