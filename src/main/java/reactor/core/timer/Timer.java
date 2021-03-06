/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.timer;

import java.util.concurrent.TimeUnit;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.subscriber.Subscribers;
import reactor.core.support.Exceptions;
import reactor.core.support.ReactiveState;
import reactor.fn.Consumer;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public class Timer implements ReactiveState.Timed, ReactiveState.ActiveDownstream {

	protected final int resolution;

	Timer() {
		this.resolution = 0;
	}

	protected Timer(int resolution) {
		this.resolution = resolution;
	}

	/**
	 * Schedule a recurring task. The given {@link Consumer} will be invoked once every N time units
	 * after the given delay.
	 *
	 * @param consumer            the {@code Consumer} to invoke each period
	 * @param period              the amount of time that should elapse between invocations of the given {@code
	 * Consumer}
	 * @param timeUnit            the unit of time the {@code period} is to be measured in
	 * @param delayInMilliseconds a number of milliseconds in which to delay any execution of the given {@code
	 * Consumer}
	 * @return a {@link Pausable} that can be used to {@link
	 * Pausable#cancel() cancel}, {@link Pausable#pause() pause} or
	 * {@link Pausable#resume() resume} the given task.
	 */
	public Pausable schedule(Consumer<? super Long> consumer,
	                  long period,
	                  TimeUnit timeUnit,
	                  long delayInMilliseconds){
		Subscriber<? super Long> s = Subscribers.consumer(consumer);
		Pausable p = interval(s, period, timeUnit, delayInMilliseconds);
		s.onSubscribe(p);
		return p;
	}

	/**
	 * Schedule a recurring task. The given {@link Subscriber} will receive an ever incrementing integer from 0 once
	 * every N time units after the given delay. If the {@link Subscription} has not been requested before the timed
	 * signal, a {@link Exceptions.TimerOverflow} will be signalled instead.
	 *
	 * @param subscriber the {@code Subscriber} to invoke each period
	 * @param period the amount of time that should elapse between invocations of the given {@code Subscriber}
	 * @param timeUnit the unit of time the {@code period} is to be measured in
	 * @param delayInMilliseconds a number of milliseconds in which to delay any execution of the given {@code
	 * Subscriber}
	 *
	 * @return a {@link Subscription} that can be passed onSubscribe
	 */
	public Pausable interval(Subscriber<? super Long> subscriber,
			long period,
			TimeUnit timeUnit,
			long delayInMilliseconds) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Schedule a recurring task. The given {@link Consumer} will be invoked immediately, as well as
	 * once
	 * every N time units.
	 *
	 * @param consumer the {@code Consumer} to invoke each period
	 * @param period   the amount of time that should elapse between invocations of the given {@code Consumer}
	 * @param timeUnit the unit of time the {@code period} is to be measured in
	 * @return a {@link Pausable} that can be used to {@link
	 * Pausable#cancel() cancel}, {@link Pausable#pause() pause} or
	 * {@link Pausable#resume() resume} the given task.
	 * @see #schedule(Consumer, long, TimeUnit, long)
	 */
	public Pausable schedule(Consumer<? super Long> consumer, long period,
	                  TimeUnit timeUnit){
		return schedule(consumer, period, timeUnit, 0);
	}

	/**
	 * Submit a task for arbitrary execution after the given time delay.
	 *
	 * @param consumer the {@code Consumer} to invoke
	 * @param delay    the amount of time that should elapse before invocations of the given {@code Consumer}
	 * @param timeUnit the unit of time the {@code delay} is to be measured in
	 * @return a {@link Pausable} that can be used to {@link
	 * Pausable#cancel() cancel}, {@link Pausable#pause() pause} or
	 * {@link Pausable#resume() resume} the given task.
	 */
	public Pausable submit(Consumer<? super Long> consumer, long delay, TimeUnit timeUnit) {
		Subscriber<? super Long> s = Subscribers.consumer(consumer);
		Pausable p = single(Subscribers.consumer(consumer), delay, timeUnit);
		s.onSubscribe(p);
		return p;
	}

	/**
	 * Submit a task for arbitrary execution after the delay of this timer's resolution.
	 *
	 * @param consumer the {@code Consumer} to invoke
	 *
	 * @return {@literal this}
	 */
	public Pausable submit(Consumer<? super Long> consumer) {
		return submit(consumer, resolution, TimeUnit.MILLISECONDS);
	}

	/**
	 * Submit a task for arbitrary execution after the given time delay. If the {@link Subscription} has not been
	 * requested before the timed signal, a {@link Exceptions.TimerOverflow} will be signalled
	 * instead.
	 *
	 * @param subscriber the {@code Subscriber} to invoke
	 * @param delay    the amount of time that should elapse before invocations of the given {@code Subscriber}
	 * @param timeUnit the unit of time the {@code delay} is to be measured in
	 * @return a {@link Subscription} that can be passed onSubscribe
	 */
	public Pausable single(Subscriber<? super Long> subscriber, long delay, TimeUnit timeUnit) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Start the Timer, may throw an IllegalStateException if already started.
	 */
	public void start(){

	}

	/**
	 * Cancel this timer by interrupting the task thread. No more tasks can be submitted to this timer after
	 * cancellation.
	 */
	public void cancel() {

	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public long period() {
		return resolution;
	}

}
