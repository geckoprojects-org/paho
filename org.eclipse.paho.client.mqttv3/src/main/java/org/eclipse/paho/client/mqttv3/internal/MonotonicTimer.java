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
package org.eclipse.paho.client.mqttv3.internal;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A replacement for {@link java.util.Timer} that schedules on the monotonic clock.
 * <p>
 * {@code java.util.Timer} computes its due times from {@link System#currentTimeMillis()}: when the
 * system time jumps back (an NTP correction after boot on a device without a reliable clock), every
 * task is delayed by the size of the jump. For the keep-alive pings that means missed PINGREQs and
 * no detection of a half-open connection, for the reconnect cycle no reconnect attempt for that
 * long. {@link ScheduledThreadPoolExecutor} schedules on {@link System#nanoTime()}, which does not
 * follow the wall clock.
 * <p>
 * Same resources as the {@code Timer} it replaces: one thread, with the same name and, like
 * {@code Timer}, not a daemon; it is started by the first {@link #schedule(Runnable, long)} and ends
 * with {@link #cancel()}.
 */
public class MonotonicTimer {

	private final ScheduledThreadPoolExecutor executor;

	public MonotonicTimer(final String threadName) {
		executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
			public Thread newThread(Runnable runnable) {
				Thread thread = new Thread(runnable, threadName);
				thread.setDaemon(false);
				return thread;
			}
		});
		executor.setRemoveOnCancelPolicy(true);
		executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
	}

	/**
	 * Runs the task once after the delay.
	 *
	 * @throws java.util.concurrent.RejectedExecutionException after {@link #cancel()}, as
	 *             {@code Timer.schedule} throws {@code IllegalStateException}
	 */
	public void schedule(Runnable task, long delayMillis) {
		executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
	}

	/** Discards all scheduled tasks and ends the thread. */
	public void cancel() {
		executor.shutdownNow();
	}
}
