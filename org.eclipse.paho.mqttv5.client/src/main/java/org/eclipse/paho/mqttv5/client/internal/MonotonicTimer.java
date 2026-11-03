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
package org.eclipse.paho.mqttv5.client.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
 * with {@link #cancel()}. Created with the application's {@link ScheduledExecutorService}, the timer
 * has no thread of its own: the tasks run on that executor, and {@link #cancel()} cancels them
 * without shutting the executor down.
 */
public class MonotonicTimer {

	// Own executor, or null when the tasks run on the application's executor
	private final ScheduledThreadPoolExecutor executor;
	private final ScheduledExecutorService shared;
	// Tasks scheduled on the application's executor that may still be due
	private final List<ScheduledFuture<?>> pending;
	private boolean cancelled;

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
		shared = null;
		pending = null;
	}

	/**
	 * A timer whose tasks run on the application's executor.
	 *
	 * @param executorService the executor; not shut down by {@link #cancel()}
	 */
	public MonotonicTimer(ScheduledExecutorService executorService) {
		if (executorService == null) {
			throw new IllegalArgumentException("executorService");
		}
		executor = null;
		shared = executorService;
		pending = new ArrayList<ScheduledFuture<?>>(2);
	}

	/**
	 * Runs the task once after the delay.
	 *
	 * @throws java.util.concurrent.RejectedExecutionException after {@link #cancel()}, as
	 *             {@code Timer.schedule} throws {@code IllegalStateException}, or when the
	 *             application's executor is shut down
	 */
	public void schedule(Runnable task, long delayMillis) {
		if (shared == null) {
			executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
			return;
		}
		synchronized (pending) {
			if (cancelled) {
				throw new RejectedExecutionException("Timer cancelled");
			}
			for (Iterator<ScheduledFuture<?>> i = pending.iterator(); i.hasNext();) {
				if (i.next().isDone()) {
					i.remove();
				}
			}
			pending.add(shared.schedule(task, delayMillis, TimeUnit.MILLISECONDS));
		}
	}

	/**
	 * Discards all scheduled tasks and ends the thread. On the application's executor, the tasks
	 * that are not due yet are cancelled; a task that is running is not interrupted.
	 */
	public void cancel() {
		if (shared == null) {
			executor.shutdownNow();
			return;
		}
		synchronized (pending) {
			cancelled = true;
			for (ScheduledFuture<?> future : pending) {
				future.cancel(false);
			}
			pending.clear();
		}
	}
}
