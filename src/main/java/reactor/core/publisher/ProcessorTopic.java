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

package reactor.core.publisher;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.queue.RequestTask;
import reactor.core.queue.RingBufferSubscriberUtils;
import reactor.core.queue.disruptor.RingBuffer;
import reactor.core.queue.disruptor.Sequence;
import reactor.core.queue.disruptor.SequenceBarrier;
import reactor.core.queue.disruptor.Sequencer;
import reactor.core.subscription.BackpressureUtils;
import reactor.core.subscription.EmptySubscription;
import reactor.core.support.Exceptions;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.core.support.ReactiveState;
import reactor.core.support.WaitStrategy;
import reactor.fn.Consumer;
import reactor.fn.LongSupplier;
import reactor.fn.Supplier;

/**
 * An implementation of a RingBuffer backed message-passing Processor. <p> The processor
 * respects the Reactive Streams contract and must not be signalled concurrently on any
 * onXXXX method. Each subscriber will be assigned a unique thread that will only stop on
 * terminal event: Complete, Error or Cancel. If Auto-Cancel is enabled, when all
 * subscribers are unregistered, a cancel signal is sent to the upstream Publisher if any.
 * Executor can be customized and will define how many concurrent subscribers are allowed
 * (fixed thread). When a Subscriber requests Long.MAX, there won't be any backpressure
 * applied and the producer will run at risk of being throttled if the subscribers don't
 * catch up. With any other strictly positive demand, a subscriber will stop reading new
 * Next signals (Complete and Error will still be read) as soon as the demand has been
 * fully consumed by the publisher. <p> When more than 1 subscriber listens to that
 * processor, they will all receive the exact same events if their respective demand is
 * still strictly positive, very much like a Fan-Out scenario. <p> When the backlog has
 * been completely booked and no subscribers is draining the signals, the publisher will
 * start throttling. In effect the smaller the backlog size is defined, the smaller the
 * difference in processing rate between subscribers must remain. Since the sequence for
 * each subscriber will point to various ringBuffer locations, the processor knows when a
 * backlog can't override the previously occupied slot.
 * @param <E> Type of dispatched signal
 * @author Stephane Maldini
 * @author Anatoly Kadyshev
 */
public final class ProcessorTopic<E> extends ProcessorExecutor<E, E>
		implements ReactiveState.Buffering, ReactiveState.LinkedDownstreams {

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and auto-cancel. <p> A new Cached ThreadExecutorPool will be
	 * implicitely created.
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create() {
		return create(ProcessorTopic.class.getSimpleName(), SMALL_BUFFER_SIZE,
				null, true);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and the passed auto-cancel setting. <p> A new Cached
	 * ThreadExecutorPool will be implicitely created.
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(boolean autoCancel) {
		return create(ProcessorTopic.class.getSimpleName(), SMALL_BUFFER_SIZE,
				null, autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and auto-cancel. <p> The passed {@link
	 * ExecutorService} will execute as many event-loop consuming the
	 * ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(ExecutorService service) {
		return create(service, SMALL_BUFFER_SIZE, null, true);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and the passed auto-cancel setting. <p> The passed {@link
	 * ExecutorService} will execute as many event-loop consuming the
	 * ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(ExecutorService service,
	                                                boolean autoCancel) {
		return create(service, SMALL_BUFFER_SIZE, null, autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and the passed auto-cancel setting. <p> A new Cached
	 * ThreadExecutorPool will be implicitely created and will use the passed name to
	 * qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E> Type of processed signals
	 */
	public static <E> ProcessorTopic<E> create(String name, int bufferSize) {
		return create(name, bufferSize, null, true);
	}

	/**
	 * Create a new ProcessorTopic using the blockingWait Strategy, passed backlog
	 * size, and auto-cancel settings. <p> The passed {@link ExecutorService}
	 * will execute as many event-loop consuming the ringbuffer as subscribers.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(String name, int bufferSize,
	                                                boolean autoCancel) {
		return create(name, bufferSize, null, autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, blockingWait Strategy
	 * and will auto-cancel. <p> The passed {@link ExecutorService}
	 * will execute as many event-loop consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(ExecutorService service,
	                                                int bufferSize) {
		return create(service, bufferSize, new WaitStrategy.LiteBlocking(), true);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, blockingWait Strategy
	 * and the auto-cancel argument. <p> The passed {@link ExecutorService}
	 * will execute as many event-loop consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(ExecutorService service,
	                                                int bufferSize, boolean autoCancel) {
		return create(service, bufferSize, new WaitStrategy.LiteBlocking(), autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and will
	 * auto-cancel. <p> A new Cached ThreadExecutorPool will be implicitely created and
	 * will use the passed name to qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(String name, int bufferSize,
	                                                WaitStrategy strategy) {
		return create(name, bufferSize, strategy, null);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy, signal
	 * supplier. The created processor is not shared and will auto-cancel. <p> A new
	 * Cached ThreadExecutorPool will be implicitely created and will use the passed name
	 * to qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param signalSupplier A supplier of dispatched signals to preallocate in the ring
	 * buffer
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(String name, int bufferSize,
	                                                WaitStrategy strategy,
	                                                Supplier<E> signalSupplier) {
		return new ProcessorTopic<E>(name, null, bufferSize, strategy, false, true,
				signalSupplier);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and
	 * auto-cancel settings. <p> A new Cached ThreadExecutorPool will be implicitely
	 * created and will use the passed name to qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(String name, int bufferSize,
	                                                WaitStrategy strategy,
	                                                boolean autoCancel) {
		return new ProcessorTopic<E>(name, null, bufferSize, strategy, false,
				autoCancel, null);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and will
	 * auto-cancel. <p> The passed {@link ExecutorService} will
	 * execute as many event-loop consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(ExecutorService service,
	                                                int bufferSize,
	                                                WaitStrategy strategy) {
		return create(service, bufferSize, strategy, true);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and
	 * auto-cancel settings. <p> The passed {@link ExecutorService}
	 * will execute as many event-loop consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> create(ExecutorService service,
	                                                int bufferSize, WaitStrategy strategy,
	                                                boolean autoCancel) {
		return new ProcessorTopic<E>(null, service, bufferSize, strategy, false,
				autoCancel, null);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and auto-cancel. <p> A Shared Processor authorizes concurrent
	 * onNext calls and is suited for multi-threaded publisher that will fan-in data. <p>
	 * A new Cached ThreadExecutorPool will be implicitely created.
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share() {
		return share(ProcessorTopic.class.getSimpleName(), SMALL_BUFFER_SIZE,
				null, true);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and the passed auto-cancel setting. <p> A Shared Processor
	 * authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data. <p> A new Cached ThreadExecutorPool will be implicitely created.
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(boolean autoCancel) {
		return share(ProcessorTopic.class.getSimpleName(), SMALL_BUFFER_SIZE,
				null, autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and auto-cancel. <p> A Shared Processor authorizes concurrent
	 * onNext calls and is suited for multi-threaded publisher that will fan-in data. <p>
	 * The passed {@link ExecutorService} will execute as many
	 * event-loop consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(ExecutorService service) {
		return share(service, SMALL_BUFFER_SIZE, null, true);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and the passed auto-cancel setting. <p> A Shared Processor
	 * authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data. <p> The passed {@link ExecutorService} will
	 * execute as many event-loop consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(ExecutorService service,
	                                               boolean autoCancel) {
		return share(service, SMALL_BUFFER_SIZE, null,
				autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using {@link ReactiveState#SMALL_BUFFER_SIZE} backlog size,
	 * blockingWait Strategy and the passed auto-cancel setting. <p> A Shared Processor
	 * authorizes concurrent onNext calls and is suited for multi-threaded publisher that
	 * will fan-in data. <p> A new Cached ThreadExecutorPool will be implicitely created
	 * and will use the passed name to qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(String name, int bufferSize) {
		return share(name, bufferSize, null, true);
	}

	/**
	 * Create a new ProcessorTopic using the blockingWait Strategy, passed backlog
	 * size, and auto-cancel settings. <p> A Shared Processor authorizes concurrent onNext
	 * calls and is suited for multi-threaded publisher that will fan-in data. <p> The
	 * passed {@link ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(String name, int bufferSize,
	                                               boolean autoCancel) {
		return share(name, bufferSize, null, autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, blockingWait Strategy
	 * and will auto-cancel. <p> A Shared Processor authorizes concurrent onNext calls and
	 * is suited for multi-threaded publisher that will fan-in data. <p> The passed {@link
	 * ExecutorService} will execute as many event-loop consuming the
	 * ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(ExecutorService service,
	                                               int bufferSize) {
		return share(service, bufferSize, null, true);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, blockingWait Strategy
	 * and the auto-cancel argument. <p> A Shared Processor authorizes concurrent onNext
	 * calls and is suited for multi-threaded publisher that will fan-in data. <p> The
	 * passed {@link ExecutorService} will execute as many event-loop
	 * consuming the ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(ExecutorService service,
	                                               int bufferSize, boolean autoCancel) {
		return share(service, bufferSize, null, autoCancel);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and will
	 * auto-cancel. <p> A Shared Processor authorizes concurrent onNext calls and is
	 * suited for multi-threaded publisher that will fan-in data. <p> A new Cached
	 * ThreadExecutorPool will be implicitely created and will use the passed name to
	 * qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(String name, int bufferSize,
	                                               WaitStrategy strategy) {
		return new ProcessorTopic<E>(name, null, bufferSize, strategy, true, true,
				null);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and
	 * signal supplier. The created processor will auto-cancel and is shared. <p> A Shared
	 * Processor authorizes concurrent onNext calls and is suited for multi-threaded
	 * publisher that will fan-in data. <p> A new Cached ThreadExecutorPool will be
	 * implicitely created and will use the passed name to qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param signalSupplier A supplier of dispatched signals to preallocate in the ring
	 * buffer
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(String name, int bufferSize,
	                                               Supplier<E> signalSupplier) {
		return new ProcessorTopic<E>(name, null, bufferSize,
				null, true, true, signalSupplier);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and
	 * signal supplier. The created processor will auto-cancel and is shared. <p> A Shared
	 * Processor authorizes concurrent onNext calls and is suited for multi-threaded
	 * publisher that will fan-in data. <p> A new Cached ThreadExecutorPool will be
	 * implicitely created and will use the passed name to qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param waitStrategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * buffer
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(String name, int bufferSize, WaitStrategy waitStrategy,
	                                               Supplier<E> signalSupplier) {
		return new ProcessorTopic<E>(name, null, bufferSize,
				waitStrategy, true, true, signalSupplier);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and
	 * auto-cancel settings. <p> A Shared Processor authorizes concurrent onNext calls and
	 * is suited for multi-threaded publisher that will fan-in data. <p> A new Cached
	 * ThreadExecutorPool will be implicitely created and will use the passed name to
	 * qualify the created threads.
	 * @param name Use a new Cached ExecutorService and assign this name to the created
	 * threads
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(String name, int bufferSize,
	                                               WaitStrategy strategy,
	                                               boolean autoCancel) {
		return new ProcessorTopic<E>(name, null, bufferSize, strategy, true,
				autoCancel, null);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and will
	 * auto-cancel. <p> A Shared Processor authorizes concurrent onNext calls and is
	 * suited for multi-threaded publisher that will fan-in data. <p> The passed {@link
	 * ExecutorService} will execute as many event-loop consuming the
	 * ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(ExecutorService service,
	                                               int bufferSize,
	                                               WaitStrategy strategy) {
		return share(service, bufferSize, strategy, true);
	}

	/**
	 * Create a new ProcessorTopic using passed backlog size, wait strategy and
	 * auto-cancel settings. <p> A Shared Processor authorizes concurrent onNext calls and
	 * is suited for multi-threaded publisher that will fan-in data. <p> The passed {@link
	 * ExecutorService} will execute as many event-loop consuming the
	 * ringbuffer as subscribers.
	 * @param service A provided ExecutorService to manage threading infrastructure
	 * @param bufferSize A Backlog Size to mitigate slow subscribers
	 * @param strategy A RingBuffer WaitStrategy to use instead of the default
	 * blocking wait strategy.
	 * @param autoCancel Should this propagate cancellation when unregistered by all
	 * subscribers ?
	 * @param <E> Type of processed signals
	 * @return a fresh processor
	 */
	public static <E> ProcessorTopic<E> share(ExecutorService service,
	                                               int bufferSize, WaitStrategy strategy,
	                                               boolean autoCancel) {
		return new ProcessorTopic<E>(null, service, bufferSize, strategy, true,
				autoCancel, null);
	}

	final SequenceBarrier barrier;

	final RingBuffer<RingBuffer.Slot<E>> ringBuffer;

	final Sequence minimum;

	final WaitStrategy readWait = new WaitStrategy.LiteBlocking();

	private ProcessorTopic(String name, ExecutorService executor, int bufferSize,
	                            WaitStrategy waitStrategy, boolean shared,
	                            boolean autoCancel, final Supplier<E> signalSupplier) {
		super(name, executor, autoCancel);

		if (!Sequencer.isPowerOfTwo(bufferSize) ){
			throw new IllegalArgumentException("bufferSize must be a power of 2 : "+bufferSize);
		}

		Supplier<RingBuffer.Slot<E>> factory = new Supplier<RingBuffer.Slot<E>>() {
			@Override
			public RingBuffer.Slot<E> get() {
				RingBuffer.Slot<E> signal = new RingBuffer.Slot<>();
				if (signalSupplier != null) {
					signal.value = signalSupplier.get();
				}
				return signal;
			}
		};

		Runnable spinObserver = new Runnable() {
			@Override
			public void run() {
				if (!alive() && SUBSCRIBER_COUNT.get(ProcessorTopic.this) == 0) {
					throw Exceptions.AlertException.INSTANCE;
				}
			}
		};

		WaitStrategy strategy = waitStrategy == null ?
				WaitStrategy.PhasedOff.withLiteLock(200, 100, TimeUnit.MILLISECONDS) :
				waitStrategy;
		if (shared) {
			this.ringBuffer = RingBuffer
					.createMultiProducer(factory, bufferSize, strategy, spinObserver);
		}
		else {
			this.ringBuffer = RingBuffer
					.createSingleProducer(factory, bufferSize, strategy, spinObserver);
		}

		this.minimum = Sequencer.newSequence(-1);
		this.barrier = ringBuffer.newBarrier();
	}

	@Override
	public void subscribe(final Subscriber<? super E> subscriber) {
		super.subscribe(subscriber);

		if (!alive()) {
			coldSource(ringBuffer, null, error, minimum).subscribe(subscriber);
			return;
		}

		//create a unique eventProcessor for this subscriber
		final Sequence pendingRequest = Sequencer.newSequence(0);
		final TopicSubscriberLoop<E> signalProcessor =
				new TopicSubscriberLoop<>(this, pendingRequest, subscriber);

		//bind eventProcessor sequence to observe the ringBuffer

		//if only active subscriber, replay missed data
		if (incrementSubscribers()) {

			signalProcessor.sequence.set(minimum.get());
			ringBuffer.addGatingSequence(signalProcessor.sequence);
			//set eventProcessor sequence to minimum index (replay)
		}
		else {
			//otherwise only listen to new data
			//set eventProcessor sequence to ringbuffer index
			signalProcessor.sequence.set(ringBuffer.getCursor());
			ringBuffer.addGatingSequence(signalProcessor.sequence);


		}

		try {
			//start the subscriber thread
			executor.execute(signalProcessor);

		}
		catch (Throwable t) {
			ringBuffer.removeGatingSequence(signalProcessor.getSequence());
			decrementSubscribers();
			if (!alive() && RejectedExecutionException.class.isAssignableFrom(t.getClass())){
				coldSource(ringBuffer, t, error, minimum).subscribe(subscriber);
			}
			else{
				EmptySubscription.error(subscriber, t);
			}
		}
	}

	@Override
	public void onNext(E o) {
		super.onNext(o);
		RingBufferSubscriberUtils.onNext(o, ringBuffer);
	}

	@Override
	protected void doError(Throwable t) {
		readWait.signalAllWhenBlocking();
		barrier.signal();
	}

	@Override
	protected void doComplete() {
		readWait.signalAllWhenBlocking();
		barrier.signal();
	}

	final static <E> Publisher<E> coldSource(RingBuffer<RingBuffer.Slot<E>> ringBuffer, Throwable t, Throwable error,
			Sequence start){
		Publisher<E> bufferIterable = fromIterable(RingBuffer.newSequencedQueue(ringBuffer, start.get()));
		if(error != null && t != null){
				t.addSuppressed(error);
				return concat(bufferIterable, Flux.<E>error(error));
		}
		return bufferIterable;
	}

	@Override
	public boolean isWork() {
		return false;
	}

	RingBuffer<RingBuffer.Slot<E>> ringBuffer() {
		return ringBuffer;
	}

	@Override
	public long pending() {
		return ringBuffer.pending();
	}

	@Override
	protected void requestTask(Subscription s) {
		minimum.set(ringBuffer.getCursor());
		ringBuffer.addGatingSequence(minimum);
		new NamedDaemonThreadFactory(name+"[request-task]", null, null, false)
				.newThread(new RequestTask(s, new Runnable() {
					@Override
					public void run() {
						if (!alive()) {
							if(cancelled){
								throw Exceptions.CancelException.INSTANCE;
							}
							else {
								throw Exceptions.AlertException.INSTANCE;
							}
						}
					}
				}, new Consumer<Long>() {
					@Override
					public void accept(Long newMin) {
						minimum.set(newMin);
					}
				}, new LongSupplier() {
					@Override
					public long get() {
						return SUBSCRIBER_COUNT.get(ProcessorTopic.this) == 0 ?
								minimum.get() :
								ringBuffer.getMinimumGatingSequence(minimum);
					}
				}, readWait, this, ringBuffer)).start();
	}

	@Override
	protected void cancel(Subscription subscription) {
		super.cancel(subscription);
		readWait.signalAllWhenBlocking();
	}

	@Override
	public String toString() {
		return "ProcessorTopic{" +
				"barrier=" + barrier +
				", remaining=" + ringBuffer.remainingCapacity() +
				'}';
	}

	@Override
	public long getAvailableCapacity() {
		return ringBuffer.remainingCapacity();
	}

	@Override
	public long getCapacity() {
		return ringBuffer.getBufferSize();
	}

	@Override
	public boolean isStarted() {
		return super.isStarted() || ringBuffer.get() != -1;
	}

	/**
	 * Get the remaining capacity for the ring buffer
	 * @return number of remaining slots
	 */
	public long remainingCapacity() {
		return ringBuffer.remainingCapacity();
	}

	@Override
	public Iterator<?> downstreams() {
		return Arrays.asList(ringBuffer.getSequencer().getGatingSequences()).iterator();
	}

	@Override
	public long downstreamsCount() {
		return ringBuffer.getSequencer().getGatingSequences().length - (isStarted() ? 1 : 0);
	}

	/**
	 * Disruptor BatchEventProcessor port that deals with pending demand. <p> Convenience
	 * class for handling the batching semantics of consuming entries from a {@link
	 * reactor.core.publisher .rb.disruptor .RingBuffer}. <p>
	 * @param <T> event implementation storing the data for sharing during exchange or
	 * parallel coordination of an event.
	 */
	private final static class TopicSubscriberLoop<T> implements Runnable, Downstream, Buffering, ActiveUpstream,
	                                                             Upstream,
	                                                             ActiveDownstream, Inner, DownstreamDemand, Subscription {

		private final AtomicBoolean running = new AtomicBoolean(false);

		private final Sequence sequence =
				Sequencer.wrap(Sequencer.INITIAL_CURSOR_VALUE, this);

		private final ProcessorTopic<T> processor;

		private final Sequence pendingRequest;

		private final Subscriber<? super T> subscriber;

		private final Runnable waiter = new Runnable() {
			@Override
			public void run() {
				if (!running.get() || processor.isTerminated()) {
					throw Exceptions.AlertException.INSTANCE;
				}
			}
		};

		/**
		 * Construct a ringbuffer consumer that will automatically track the progress by
		 * updating its sequence
		 */
		public TopicSubscriberLoop(ProcessorTopic<T> processor,
		                            Sequence pendingRequest,
		                            Subscriber<? super T> subscriber) {
			this.processor = processor;
			this.pendingRequest = pendingRequest;
			this.subscriber = subscriber;
		}

		public Sequence getSequence() {
			return sequence;
		}

		public void halt() {
			running.set(false);
			processor.barrier.alert();
		}

		/**
		 * It is ok to have another thread rerun this method after a halt().
		 */
		@Override
		public void run() {
			try {
				if (!running.compareAndSet(false, true)) {
					EmptySubscription.error(subscriber, new IllegalStateException("Thread is already running"));
					return;
				}

				if(!processor.startSubscriber(subscriber, this)){
					return;
				}

				if (!RingBufferSubscriberUtils
						.waitRequestOrTerminalEvent(pendingRequest, processor.barrier, running, sequence, waiter)) {
					if(!running.get()){
						return;
					}
					if(processor.terminated == 1 && processor.ringBuffer.get() == -1L) {
						if (processor.error != null) {
							subscriber.onError(processor.error);
							return;
						}
						subscriber.onComplete();
						return;
					}
				}

				RingBuffer.Slot<T> event = null;
				long nextSequence = sequence.get() + 1L;
				final boolean unbounded = pendingRequest.get() == Long.MAX_VALUE;

				while (true) {
					try {

						final long availableSequence = processor.barrier.waitFor(nextSequence, waiter);
						while (nextSequence <= availableSequence) {
							event = processor.ringBuffer.get(nextSequence);

								//if bounded and out of capacity
								while (!unbounded &&
										BackpressureUtils.getAndSub(pendingRequest, 1L) ==
												0) {
									//Todo Use WaitStrategy?
									if(!running.get() || processor.isTerminated()){
										throw Exceptions.AlertException.INSTANCE;
									}
									LockSupport.parkNanos(1L);
								}

								//It's an unbounded subscriber or there is enough capacity to process the signal
								subscriber.onNext(event.value);
								nextSequence++;

						}
						sequence.set(availableSequence);

						if (EmptySubscription.INSTANCE !=
								processor.upstreamSubscription) {
							processor.readWait.signalAllWhenBlocking();
						}
					}
					catch (final Exceptions.AlertException | Exceptions.CancelException ex) {
						if (!running.get()) {
							break;
						}
						else {
							if(processor.terminated == 1) {
								if (processor.error != null) {
									subscriber.onError(processor.error);
									break;
								}
								if(nextSequence > processor.ringBuffer.get()) {
									subscriber.onComplete();
									break;
								}
							}
							processor.barrier.clearAlert();
						}
					}
					catch (final Throwable ex) {
						Exceptions.throwIfFatal(ex);
						subscriber.onError(ex);
						sequence.set(nextSequence);
						nextSequence++;
					}
				}
			}
			finally {
				processor.ringBuffer.removeGatingSequence(sequence);
				processor.decrementSubscribers();
				running.set(false);
				processor.readWait.signalAllWhenBlocking();
			}
		}

		@Override
		public boolean isCancelled() {
			return !running.get();
		}

		@Override
		public boolean isStarted() {
			return sequence.get() != -1L;
		}

		@Override
		public boolean isTerminated() {
			return !running.get();
		}

		@Override
		public long requestedFromDownstream() {
			return pendingRequest.get();
		}

		@Override
		public long pending() {
			return processor.ringBuffer.getCursor() - sequence.get();
		}

		@Override
		public long getCapacity() {
			return processor.getCapacity();
		}

		@Override
		public Object downstream() {
			return subscriber;
		}

		@Override
		public Object upstream() {
			return processor;
		}

		@Override
		public void request(long n) {
			if (BackpressureUtils.checkRequest(n, subscriber)) {
				if (!running.get()) {
					return;
				}

				BackpressureUtils.getAndAdd(pendingRequest, n);
			}
		}

		@Override
		public void cancel() {
			halt();
		}
	}

}
