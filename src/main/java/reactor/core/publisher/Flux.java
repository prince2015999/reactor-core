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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collector;
import java.util.stream.Stream;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.flow.Cancellation;
import reactor.core.flow.Fuseable;
import reactor.core.queue.QueueSupplier;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.TimedScheduler;
import reactor.core.scheduler.Timer;
import reactor.core.state.Backpressurable;
import reactor.core.state.Introspectable;
import reactor.core.subscriber.SignalEmitter;
import reactor.core.subscriber.LambdaSubscriber;
import reactor.core.subscriber.Subscribers;
import reactor.core.tuple.Tuple;
import reactor.core.tuple.Tuple2;
import reactor.core.tuple.Tuple3;
import reactor.core.tuple.Tuple4;
import reactor.core.tuple.Tuple5;
import reactor.core.tuple.Tuple6;
import reactor.core.util.Exceptions;
import reactor.core.util.Logger;
import reactor.core.util.PlatformDependent;
import reactor.core.util.ReactiveStateUtils;

/**
 * A Reactive Streams {@link Publisher} with rx operators that emits 0 to N elements, and then completes
 * (successfully or with an error).
 *
 * <p>
 * <img width="640" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flux.png" alt="">
 * <p>
 *
 * <p>It is intended to be used in implementations and return types. Input parameters should keep using raw
 * {@link Publisher} as much as possible.
 *
 * <p>If it is known that the underlying {@link Publisher} will emit 0 or 1 element, {@link Mono} should be used
 * instead.
 *
 * @author Sebastien Deleuze
 * @author Stephane Maldini
 * @author David Karnok
 *
 * @see Mono
 * @since 2.5
 */
public abstract class Flux<T> implements Publisher<T>, Introspectable, Backpressurable{

//	 ==============================================================================================================
//	 Static Generators
//	 ==============================================================================================================

    static final Flux<?>          EMPTY                  = FluxSource.wrap(Mono.empty());

	/**
	 * Select the fastest source who won the "ambiguous" race and emitted first onNext or onComplete or onError
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/amb.png" alt="">
	 * <p> <p>
	 *
	 * @param sources The competing source publishers
	 * @param <I> The source type of the data sequence
	 *
	 * @return a new {@link Flux} eventually subscribed to one of the sources or empty
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <I> Flux<I> amb(Publisher<? extends I>... sources) {
		return new FluxAmb<>(sources);
	}

	/**
	 * Select the fastest source who won the "ambiguous" race and emitted first onNext or onComplete or onError
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/amb.png" alt="">
	 * <p> <p>
	 *
	 * @param sources The competing source publishers
	 * @param <I> The source type of the data sequence
	 *
	 * @return a new {@link Flux} eventually subscribed to one of the sources or empty
	 */
	@SuppressWarnings("unchecked")
	public static <I> Flux<I> amb(Iterable<? extends Publisher<? extends I>> sources) {
		if (sources == null) {
			return empty();
		}

		return new FluxAmb<>(sources);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param sources The upstreams {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T> type of the value from sources
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced combinations , 2.5
	 */
	@SuppressWarnings("varargs")
	@SafeVarargs
	public static <T, V> Flux<V> combineLatest(Function<Object[], V> combinator, Publisher<? extends T>... sources) {
		return combineLatest(combinator, PlatformDependent.XS_BUFFER_SIZE, sources);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param sources The upstreams {@link Publisher} to subscribe to.
	 * @param prefetch demand produced to each combined source {@link Publisher}
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T> type of the value from sources
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced combinations , 2.5
	 */
	@SuppressWarnings({"unchecked", "varargs"})
	@SafeVarargs
	public static <T, V> Flux<V> combineLatest(Function<Object[], V> combinator, int prefetch,
			Publisher<? extends T>... sources) {
		if (sources == null || sources.length == 0) {
			return empty();
		}

		if (sources.length == 1) {
            Publisher<? extends T> source = sources[0];
            if (source instanceof Fuseable) {
                return new FluxMapFuseable<>(source, v -> combinator.apply(new Object[] { v }));
            }
            return new FluxMap<>(source, v -> combinator.apply(new Object[] { v }));
		}

		return new FluxCombineLatest<>(sources,
				combinator,
				QueueSupplier.get(prefetch),
				prefetch);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
    @SuppressWarnings("unchecked")
    public static <T1, T2, V> Flux<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			BiFunction<? super T1, ? super T2, ? extends V> combinator) {
	    return combineLatest(tuple -> combinator.apply((T1)tuple[0], (T2)tuple[1]), source1, source2);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
	public static <T1, T2, T3, V> Flux<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
	public static <T1, T2, T3, T4, V> Flux<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
	public static <T1, T2, T3, T4, T5, V> Flux<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6 The sixth upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <T6> type of the value from source6
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
	public static <T1, T2, T3, T4, T5, T6, V> Flux<V> combineLatest(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6,
			Function<Object[], V> combinator) {
		return combineLatest(combinator, source1, source2, source3, source4, source5, source6);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param sources The list of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T> The common base type of the source sequences
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value , 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T, V> Flux<V> combineLatest(Iterable<? extends Publisher<? extends T>> sources,
			Function<Object[], V> combinator) {
		return combineLatest(sources, PlatformDependent.XS_BUFFER_SIZE, combinator);
	}

	/**
	 * Build a {@link Flux} whose data are generated by the combination of the most recent published values from all
	 * publishers.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/combinelatest.png"
	 * alt="">
	 *
	 * @param sources The list of upstream {@link Publisher} to subscribe to.
	 * @param prefetch demand produced to each combined source {@link Publisher}
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T> The common base type of the source sequences
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value , 2.5
	 */
	@SuppressWarnings("unchecked")
	public static <T, V> Flux<V> combineLatest(Iterable<? extends Publisher<? extends T>> sources,
			int prefetch,
			Function<Object[], V> combinator) {

		return new FluxCombineLatest<>(sources,
				combinator,
				QueueSupplier.get(prefetch),
				prefetch);
	}

	/**
	 * Concat all sources pulled from the supplied
	 * {@link Iterator} on {@link Publisher#subscribe} from the passed {@link Iterable} until {@link Iterator#hasNext}
	 * returns false. A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned Publisher.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png" alt="">
	 *
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Flux} concatenating all source sequences
	 */
	public static <T> Flux<T> concat(Iterable<? extends Publisher<? extends T>> sources) {
		return new FluxConcatIterable<>(sources);
	}

	/**
	 * Concat all sources emitted as an onNext signal from a parent {@link Publisher}.
	 * A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned {@link Publisher} which will stop listening if the main sequence has also completed.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatinner.png" alt="">
	 * <p>
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences until complete or error
	 */
	public static <T> Flux<T> concat(Publisher<? extends Publisher<? extends T>> sources) {
		return concat(sources, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Concat all sources emitted as an onNext signal from a parent {@link Publisher}.
	 * A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned {@link Publisher} which will stop listening if the main sequence has also completed.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatinner.png" alt="">
	 * <p>
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param prefetch the inner source request size
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Flux} concatenating all inner sources sequences until complete or error
	 */
	public static <T> Flux<T> concat(Publisher<? extends Publisher<? extends T>> sources, int prefetch) {
		return new FluxConcatMap<>(sources, identityFunction(),
				QueueSupplier.get(prefetch), prefetch,
				FluxConcatMap.ErrorMode.IMMEDIATE);
	}

	/**
	 * Concat all sources pulled from the given {@link Publisher} array.
	 * A complete signal from each source will delimit the individual sequences and will be eventually
	 * passed to the returned Publisher.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png" alt="">
	 * <p>
	 * @param sources The {@link Publisher} of {@link Publisher} to concat
	 * @param <T> The source type of the data sequence
	 *
	 * @return a new {@link Flux} concatenating all source sequences
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> Flux<T> concat(Publisher<? extends T>... sources) {
		return new FluxConcatArray<>(false, sources);
	}
	
	/**
	 * Creates a Flux with multi-emission capabilities (synchronous or asynchronous) through
	 * the FluxEmitter API.
	 * <p>
	 * This Flux factory is useful if one wants to adapt some other a multi-valued async API
	 * and not worry about cancellation and backpressure. For example:
	 * 
     * <pre><code>
     * Flux.&lt;String&gt;create(emitter -&gt; {
     *     // setup backpressure mode, default is BUFFER
     *     
     *     emitter.setBackpressureHandling(FluxEmitter.BackpressureHandling.LATEST);
     *     
     *     ActionListener al = e -&gt; {
     *         emitter.next(textField.getText());
     *     };
     *     // without cancellation support:
     *     
     *     button.addActionListener(al);
     *     
     *     // with cancellation support:
     *     
     *     button.addActionListener(al);
     *     emitter.setCancellation(() -> {
     *         button.removeListener(al);
     *     });
     * }); 
     * <code></pre>
     *  
	 * @param <T> the value type
	 * @param emitter the consumer that will receive a FluxEmitter for each individual Subscriber.
	 * @return a {@link Flux}
	 */
	public static <T> Flux<T> create(Consumer<? super FluxEmitter<T>> emitter) {
	    return new FluxCreate<>(emitter);
	}
	
	/**
	 * Supply a {@link Publisher} everytime subscribe is called on the returned flux. The passed {@link Supplier}
	 * will be invoked and it's up to the developer to choose to return a new instance of a {@link Publisher} or reuse
	 * one effecitvely behaving like {@link #from(Publisher)}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/defer.png" alt="">
	 *
	 * @param supplier the {@link Publisher} {@link Supplier} to call on subscribe
	 * @param <T>      the type of values passing through the {@link Flux}
	 *
	 * @return a deferred {@link Flux}
	 */
	public static <T> Flux<T> defer(Supplier<? extends Publisher<T>> supplier) {
		return new FluxDefer<>(supplier);
	}

	/**
	 * Create a {@link Flux} that completes without emitting any item.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/empty.png" alt="">
	 * <p>
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return an empty {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Flux<T> empty() {
		return (Flux<T>) EMPTY;
	}

	/**
	 * Create a {@link Flux} that completes with the specified error.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/error.png" alt="">
	 * <p>
	 * @param error the error to signal to each {@link Subscriber}
	 * @param <T> the reified type of the target {@link Subscriber}
	 *
	 * @return a new failed {@link Flux}
	 */
	public static <T> Flux<T> error(Throwable error) {
		return error(error, false);
	}

	/**
	 * Build a {@link Flux} that will only emit an error signal to any new subscriber.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/errorrequest.png" alt="">
	 *
     * @param throwable the error to signal to each {@link Subscriber}
	 * @param whenRequested if true, will onError on the first request instead of subscribe().
	 * @param <O> the output type
	 *
	 * @return a new failed {@link Flux}
	 */
	public static <O> Flux<O> error(Throwable throwable, boolean whenRequested) {
		return new FluxError<>(throwable, whenRequested);
	}

	/**
	 * Expose the specified {@link Publisher} with the {@link Flux} API.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/from.png" alt="">
	 * <p>
	 * @param source the source to decorate
	 * @param <T> the source sequence type
	 *
	 * @return a new {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Flux<T> from(Publisher<? extends T> source) {
		if (source instanceof Flux) {
			return (Flux<T>) source;
		}

		if (source instanceof Fuseable.ScalarCallable) {
            T t = ((Fuseable.ScalarCallable<T>) source).call();
            if (t != null) {
                return just(t);
            }
			return empty();
		}
		return FluxSource.wrap(source);
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Iterable}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromarray.png" alt="">
	 * <p>
	 * @param array the array to read data from
	 * @param <T> the {@link Publisher} type to stream
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromArray(T[] array) {
		if (array == null || array.length == 0) {
			return empty();
		}
		if (array.length == 1) {
			return just(array[0]);
		}
		return new FluxArray<>(array);
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Iterable}.
	 * A new iterator will be created for each subscriber.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromiterable.png" alt="">
	 * <p>
	 * @param it the {@link Iterable} to read data from
	 * @param <T> the {@link Iterable} type to stream
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromIterable(Iterable<? extends T> it) {
		return new FluxIterable<>(it);
	}
	

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Stream}.
	 * A new iterator will be created for each subscriber.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/fromstream.png" alt="">
	 * <p>
	 * @param s the {@link Stream} to read data from
	 * @param <T> the {@link Stream} type to flux
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> fromStream(Stream<? extends T> s) {
		return new FluxStream<>(s);
	}

	/**
	 * Generate signals one-by-one via a function callback.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 * <p>
	 *
	 * @param <T> the value type emitted
	 * @param <S> the custom state per subscriber
	 *
	 * @return a Reactive {@link Flux} publisher ready to be subscribed
	 */
	public static <T, S> Flux<T> generate(BiFunction<S, SignalEmitter<T>, S> generator) {
		return new FluxGenerate<>(generator);
	}

	/**
	 * Generate signals one-by-one via a function callback.
	 * The {@code stateSupplier} may return {@code null}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 * <p>
	 *
	 * @param <T> the value type emitted
	 * @param <S> the custom state per subscriber
	 *
	 * @return a Reactive {@link Flux} publisher ready to be subscribed
	 */
	public static <T, S> Flux<T> generate(Callable<S> stateSupplier, BiFunction<S, SignalEmitter<T>, S> generator) {
		return new FluxGenerate<T, S>(stateSupplier, generator);
	}

	/**
	 * Generate signals one-by-one via a function callback.
	 * The {@code stateSupplier} may return {@code null} but your {@code stateConsumer} should be prepared to
	 * handle it.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/generate.png" alt="">
	 * <p>
	 *
	 * @param <T> the value type emitted
	 * @param <S> the custom state per subscriber
	 *
	 * @return a Reactive {@link Flux} publisher ready to be subscribed
	 */
	public static <T, S> Flux<T> generate(Callable<S> stateSupplier, BiFunction<S, SignalEmitter<T>, S> generator, Consumer<? super S> stateConsumer) {
		return new FluxGenerate<>(stateSupplier, generator, stateConsumer);
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every N milliseconds on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * <p>
	 * @param period The number of milliseconds to wait before the next increment
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(long period) {
		return interval(period, Timer.global());
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every period on
	 * the global timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * <p>
	 * @param period The duration to wait before the next increment
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(Duration period) {
		return interval(period.toMillis());
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every N milliseconds on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * <p>
	 * @param period The duration in milliseconds to wait before the next increment
	 * @param timer a {@link TimedScheduler} instance
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(long period, TimedScheduler timer) {
		return new FluxInterval(period, period, TimeUnit.MILLISECONDS, timer);
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every period on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/interval.png" alt="">
	 * <p>
	 * @param period The duration to wait before the next increment
	 * @param timer a {@link TimedScheduler} instance
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(Duration period, TimedScheduler timer) {
		return interval(period.toMillis(), timer);
	}


	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * a global timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the delay in milliseconds to wait before emitting 0l
	 * @param period the period in milliseconds before each following increment
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(long delay, long period) {
		return interval(delay, period, Timer.global());
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * a global timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the delay to wait before emitting 0l
	 * @param period the period before each following increment
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(Duration delay, Duration period) {
		return interval(delay.toMillis(), period.toMillis());
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the timespan in milliseconds to wait before emitting 0l
	 * @param period the period in milliseconds before each following increment
	 * @param timer  the {@link TimedScheduler} to schedule on
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(long delay, long period, TimedScheduler timer) {
		return new FluxInterval(delay, period, TimeUnit.MILLISECONDS, timer);
	}

	/**
	 * Create a new {@link Flux} that emits an ever incrementing long starting with 0 every N period of time unit on
	 * the given timer. If demand is not produced in time, an onError will be signalled. The {@link Flux} will never
	 * complete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/intervald.png" alt="">
	 *
	 * @param delay  the timespan to wait before emitting 0l
	 * @param period the period before each following increment
	 * @param timer  the {@link TimedScheduler} to schedule on
	 *
	 * @return a new timed {@link Flux}
	 */
	public static Flux<Long> interval(Duration delay, Duration period, TimedScheduler timer) {
		return new FluxInterval(delay.toMillis(), period.toMillis(), TimeUnit.MILLISECONDS, timer);
	}
	/**
	 * Create a new {@link Flux} that emits the specified items and then complete.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/justn.png" alt="">
	 * <p>
	 * @param data the consecutive data objects to emit
	 * @param <T> the emitted data type
	 *
	 * @return a new {@link Flux}
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <T> Flux<T> just(T... data) {
		return fromArray(data);
	}

	/**
	 * Create a new {@link Flux} that will only emit the passed data then onComplete.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/just.png" alt="">
	 * <p>
	 * @param data the unique data to emit
	 * @param <T> the emitted data type
	 *
	 * @return a new {@link Flux}
	 */
	public static <T> Flux<T> just(T data) {
		return new FluxJust<>(data);
	}

	/**
	 * Merge emitted {@link Publisher} sequences by the passed {@link Publisher} into an interleaved merged sequence.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/mergeinner.png" alt="">
	 * <p>
	 * @param source a {@link Publisher} of {@link Publisher} sequence to merge
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source) {
		return merge(source, PlatformDependent.SMALL_BUFFER_SIZE, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Merge emitted {@link Publisher} sequences by the passed {@link Publisher} into an interleaved merged sequence.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/mergeinner.png" alt="">
	 * <p>
	 * @param source a {@link Publisher} of {@link Publisher} sequence to merge
	 * @param concurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source, int concurrency) {
		return merge(source, concurrency, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Merge emitted {@link Publisher} sequences by the passed {@link Publisher} into an interleaved merged sequence.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/mergeinner.png" alt="">
	 * <p>
	 * @param source a {@link Publisher} of {@link Publisher} sequence to merge
	 * @param concurrency the request produced to the main source thus limiting concurrent merge backlog
	 * @param prefetch the inner source request size
	 * @param <T> the merged type
	 *
	 * @return a merged {@link Flux}
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source, int concurrency, int prefetch) {
		return new FluxFlatMap<>(
				source,
				identityFunction(),
				false,
				concurrency,
				QueueSupplier.get(concurrency),
				prefetch,
				QueueSupplier.get(prefetch)
		);
	}

	/**
	 * Merge emitted {@link Publisher} sequences from the passed {@link Iterable} into an interleaved merged sequence.
	 * {@link Iterable#iterator()} will be called for each {@link Publisher#subscribe}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param sources the {@link Iterable} to lazily iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Flux} publisher ready to be subscribed
	 */
	public static <I> Flux<I> merge(Iterable<? extends Publisher<? extends I>> sources) {
		return merge(fromIterable(sources));
	}

	/**
	 * Merge emitted {@link Publisher} sequences from the passed {@link Publisher} array into an interleaved merged
	 * sequence.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Flux} publisher ready to be subscribed
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <I> Flux<I> merge(Publisher<? extends I>... sources) {
		return merge(PlatformDependent.XS_BUFFER_SIZE, sources);
	}

	/**
	 * Merge emitted {@link Publisher} sequences from the passed {@link Publisher} array into an interleaved merged
	 * sequence.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param prefetch the inner source request size
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive {@link Flux} publisher ready to be subscribed
	 */
	@SafeVarargs
	@SuppressWarnings({"unchecked", "varargs"})
	public static <I> Flux<I> merge(int prefetch, Publisher<? extends I>... sources) {
		if (sources == null || sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
			return from(sources[0]);
		}
		return new FluxMerge<>(sources, false, sources.length, QueueSupplier.get(sources.length), prefetch, QueueSupplier.get(prefetch));
	}

	/**
	 * Create a {@link Flux} that will never signal any data, error or completion signal.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/never.png" alt="">
	 * <p>
	 * @param <T> the {@link Subscriber} type target
	 *
	 * @return a never completing {@link Flux}
	 */
	public static <T> Flux<T> never() {
		return FluxNever.instance();
	}

	/**
	 * Create a {@link Flux} that will fallback to the produced {@link Publisher} given an onError signal.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onerrorresumewith.png" alt="">
	 * <p>
	 * @param <T> the {@link Subscriber} type target
	 * @param source the source sequence
	 * @param fallback the function called with the Throwable signal the source sequence produced that should return a fallback sequence
	 *
	 * @return a resilient {@link Flux}
	 */
	public static <T> Flux<T> onErrorResumeWith(
			Publisher<? extends T> source,
			Function<Throwable, ? extends Publisher<? extends T>> fallback) {
		return new FluxResume<>(source, fallback);
	}

	/**
	 * Build a {@link Flux} that will only emit a sequence of incrementing integer from {@code start} to {@code
	 * start + count} then complete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/range.png" alt="">
	 *
	 * @param start the first integer to be emit
	 * @param count   the number ot times to emit an increment including the first value
	 * @return a ranged {@link Flux}
	 */
	public static Flux<Integer> range(int start, int count) {
		if (count == 1) {
			return just(start);
		}
		if (count == 0) {
			return empty();
		}
		return new FluxRange(start, count);
	}


	/**
	 * Build a {@link FluxProcessor} whose data are emitted by the most recent emitted {@link Publisher}.
	 * The {@link Flux} will complete once both the publishers source and the last switched to {@link Publisher} have
	 * completed.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png" alt="">
	 *
	 * @param <T> the produced type
	 * @return a {@link FluxProcessor} accepting publishers and producing T
	 */
	public static <T> FluxProcessor<Publisher<? extends T>, T> switchOnNext() {
		UnicastProcessor<Publisher<? extends T>> emitter = UnicastProcessor.create();
		FluxProcessor<Publisher<? extends T>, T> p = FluxProcessor.wrap(emitter, switchOnNext(emitter));
		return p;
	}
	
	/**
	 * Build a {@link FluxProcessor} whose data are emitted by the most recent emitted {@link Publisher}. The {@link
	 * Flux} will complete once both the publishers source and the last switched to {@link Publisher} have completed.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png"
	 * alt="">
	 *
	 * @param mergedPublishers The {@link Publisher} of switching {@link Publisher} to subscribe to.
	 * @param <T> the produced type
	 *
	 * @return a {@link FluxProcessor} accepting publishers and producing T
	 */
	public static <T> Flux<T> switchOnNext(Publisher<Publisher<? extends T>> mergedPublishers) {
		return switchOnNext(mergedPublishers, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Build a {@link FluxProcessor} whose data are emitted by the most recent emitted {@link Publisher}. The {@link
	 * Flux} will complete once both the publishers source and the last switched to {@link Publisher} have completed.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonnext.png"
	 * alt="">
	 *
	 * @param mergedPublishers The {@link Publisher} of switching {@link Publisher} to subscribe to.
	 * @param prefetch the inner source request size
	 * @param <T> the produced type
	 *
	 * @return a {@link FluxProcessor} accepting publishers and producing T
	 */
	@SuppressWarnings("unchecked")
	public static <T> Flux<T> switchOnNext(Publisher<Publisher<? extends T>> mergedPublishers, int prefetch) {
		return new FluxSwitchMap<>(mergedPublishers,
				identityFunction(),
				QueueSupplier.get(prefetch),
				prefetch);
	}

	/**
	 * Uses a resource, generated by a supplier for each individual Subscriber, while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released if the sequence terminates or
	 * the Subscriber cancels.
	 * <p>
	 * <ul> <li>Eager resource cleanup happens just before the source termination and exceptions raised by the cleanup
	 * Consumer may override the terminal even.</li> <li>Non-eager cleanup will drop any exception.</li> </ul>
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/using.png"
	 * alt="">
	 *
	 * @param resourceSupplier a {@link Callable} that is called on subscribe
	 * @param sourceSupplier a {@link Publisher} factory derived from the supplied resource
	 * @param resourceCleanup invoked on completion
	 * @param eager true to clean before terminating downstream subscribers
	 * @param <T> emitted type
	 * @param <D> resource type
	 *
	 * @return new Stream
	 */
	public static <T, D> Flux<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup, boolean eager) {
		return new FluxUsing<>(resourceSupplier, sourceSupplier, resourceCleanup, eager);
	}

	/**
	 * Uses a resource, generated by a supplier for each individual Subscriber, while streaming the values from a
	 * Publisher derived from the same resource and makes sure the resource is released if the sequence terminates or
	 * the Subscriber cancels.
	 * <p>
	 * Eager resource cleanup happens just before the source termination and exceptions raised by the cleanup Consumer
	 * may override the terminal even.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/using.png"
	 * alt="">
	 *
	 * @param resourceSupplier a {@link Callable} that is called on subscribe
	 * @param sourceSupplier a {@link Publisher} factory derived from the supplied resource
	 * @param resourceCleanup invoked on completion
	 * @param <T> emitted type
	 * @param <D> resource type
	 *
	 * @return new {@link Flux}
	 */
	public static <T, D> Flux<T> using(Callable<? extends D> resourceSupplier, Function<? super D, ? extends
			Publisher<? extends T>> sourceSupplier, Consumer<? super D> resourceCleanup) {
		return using(resourceSupplier, sourceSupplier, resourceCleanup, true);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 * <p>
	 *
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <O> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
    public static <T1, T2, O> Flux<O> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			final BiFunction<? super T1, ? super T2, ? extends O> combinator) {

		return zip((Function<Object[], O>) tuple -> combinator.apply((T1)tuple[0], (T2)tuple[1]), source1, source2);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <T1, T2> Flux<Tuple2<T1, T2>> zip(Publisher<? extends T1> source1, Publisher<? extends T2> source2) {
		return zip(Tuple.fn2(), source1, source2);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3> Flux<Tuple3<T1, T2, T3>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3) {
		return zip(Tuple.fn3(), source1, source2, source3);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4> Flux<Tuple4<T1, T2, T3, T4>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4) {
		return zip(Tuple.fn4(), source1, source2, source3, source4);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5> Flux<Tuple5<T1, T2, T3, T4, T5>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5) {
		return zip(Tuple.fn5(), source1, source2, source3, source4, source5);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source1 The first upstream {@link Publisher} to subscribe to.
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param source3 The third upstream {@link Publisher} to subscribe to.
	 * @param source4 The fourth upstream {@link Publisher} to subscribe to.
	 * @param source5 The fifth upstream {@link Publisher} to subscribe to.
	 * @param source6 The sixth upstream {@link Publisher} to subscribe to.
	 * @param <T1> type of the value from source1
	 * @param <T2> type of the value from source2
	 * @param <T3> type of the value from source3
	 * @param <T4> type of the value from source4
	 * @param <T5> type of the value from source5
	 * @param <T6> type of the value from source6
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2, T3, T4, T5, T6> Flux<Tuple6<T1, T2, T3, T4, T5, T6>> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			Publisher<? extends T3> source3,
			Publisher<? extends T4> source4,
			Publisher<? extends T5> source5,
			Publisher<? extends T6> source6) {
		return zip(Tuple.fn6(), source1, source2, source3, source4, source5, source6);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * of the most recent items emitted by each source until any of them completes. Errors will immediately be
	 * forwarded.
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public static Flux<Tuple> zip(Iterable<? extends Publisher<?>> sources) {
		return zip(sources, Tuple.fnAny());
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 *
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <O> Flux<O> zip(Iterable<? extends Publisher<?>> sources,
			final Function<? super Object[], ? extends O> combinator) {

		return zip(sources, PlatformDependent.XS_BUFFER_SIZE, combinator);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 *
	 * The {@link Iterable#iterator()} will be called on each {@link Publisher#subscribe(Subscriber)}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 *
	 * @param sources the {@link Iterable} to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param prefetch the inner source request size
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	public static <O> Flux<O> zip(Iterable<? extends Publisher<?>> sources,
			int prefetch,
			final Function<? super Object[], ? extends O> combinator) {

		if (sources == null) {
			return empty();
		}

		return new FluxZip<>(sources, combinator, QueueSupplier.get(prefetch), prefetch);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 * <p>
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <I> the type of the input sources
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <I, O> Flux<O> zip(
			final Function<? super Object[], ? extends O> combinator, Publisher<? extends I>... sources) {
		return zip(combinator, PlatformDependent.XS_BUFFER_SIZE, sources);
	}
	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator function of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 * <p>
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the
	 * value to signal downstream
	 * @param prefetch individual source request size
	 * @param sources the {@link Publisher} array to iterate on {@link Publisher#subscribe(Subscriber)}
	 * @param <I> the type of the input sources
	 * @param <O> the combined produced type
	 *
	 * @return a zipped {@link Flux}
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public static <I, O> Flux<O> zip(final Function<? super Object[], ? extends O> combinator,
			int prefetch,
			Publisher<? extends I>... sources) {

		if (sources == null || sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
		    Publisher<? extends I> source = sources[0];
		    if (source instanceof Fuseable) {
	            return new FluxMapFuseable<>(source, v -> combinator.apply(new Object[] { v }));
		    }
            return new FluxMap<>(source, v -> combinator.apply(new Object[] { v }));
		}

		return new FluxZip<>(sources, combinator, QueueSupplier.get(prefetch), prefetch);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The {@link Publisher} of {@link Publisher} will
	 * accumulate into a list until completion before starting zip operation. The operator will forward all combinations
	 * of the most recent items emitted by each published source until any of them completes. Errors will immediately be
	 * forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png"
	 * alt="">
	 *
	 * @param sources The publisher of upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <TUPLE> the raw tuple type
	 * @param <V> The produced output after transformation by the given combinator
	 *
	 * @return a {@link Flux} based on the produced value
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
    public static <TUPLE extends Tuple, V> Flux<V> zip(Publisher<? extends Publisher<?>> sources,
			final Function<? super TUPLE, ? extends V> combinator) {

		return new FluxBuffer(sources, Integer.MAX_VALUE, LIST_SUPPLIER)
		                    .flatMap(new Function<List<? extends Publisher<?>>, Publisher<V>>() {
			                    @Override
			                    public Publisher<V> apply(List<? extends Publisher<?>> publishers) {
				                    return zip(Tuple.fnAny((Function<Tuple, V>)combinator), publishers.toArray(new Publisher[publishers
						                    .size()]));
			                    }
		                    });
	}

//	 ==============================================================================================================
//	 Instance Operators
//	 ==============================================================================================================

	protected Flux() {
	}

	/**
	 * Immediately apply the given transformation to this {@link Flux} in order to generate a target type.
	 *
	 * {@code flux.as(Mono::from).subscribe(Subscribers.unbounded()) }
	 *
	 * @param transformer the {@link Function} to immediately map this {@link Flux}
	 * into a target type
	 * instance.
	 * @param <P> the returned type
	 *
	 * @return a an instance of P
	 * @see #compose for a bounded conversion to {@link Publisher}
	 */
	public final <P> P as(Function<? super Flux<T>, P> transformer) {
		return transformer.apply(this);
	}

	/**
	 *
	 * Emit a single boolean true if all values of this sequence match
	 * the {@link Predicate}.
	 * <p>
	 * The implementation uses short-circuit logic and completes with false if
	 * the predicate doesn't match a value.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/all.png" alt="">
	 *
	 * @param predicate the {@link Predicate} to match all emitted items
	 *
	 * @return a {@link Mono} of all evaluations
	 */
	public final Mono<Boolean> all(Predicate<? super T> predicate) {
		return new MonoAll<>(this, predicate);
	}



	/**
	 * Return a {@code Mono<Void>} that completes when this {@link Flux} completes.
	 * This will actively ignore the sequence and only replay completion or error signals.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethen.png" alt="">
	 * <p>
	 * @return a new {@link Mono}
	 * @deprecated use {@link #then}
	 */
	@Deprecated
	@SuppressWarnings("unchecked")
	public final Mono<Void> after() {
		return (Mono<Void>) new MonoIgnoreThen<>(this);
	}

	/**
	 * Return a {@link Flux} that emits the sequence of the supplied {@link Publisher} when this {@link Flux} onComplete
	 * or onError. If an error occur, append after the supplied {@link Publisher} is terminated.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethens.png"
	 * alt="">
	 *
	 * @param other a {@link Publisher} to emit from after termination
	 * @param <V> the supplied produced type
	 *
	 * @return a new {@link Flux} emitting eventually from the supplied {@link Publisher}
	 * @deprecated use {@link #thenMany}
	 */
	@Deprecated
	@SuppressWarnings("unchecked")
	public final <V> Flux<V> after(Publisher<V> other) {
		return (Flux<V>)concat(ignoreElements(), other);
	}

	/**
	 * Return a {@link Flux} that emits the sequence of the supplied {@link Publisher} when this {@link Flux} onComplete
	 * or onError. If an error occur, append after the supplied {@link Publisher} is terminated.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethens.png"
	 * alt="">
	 *
	 * @param afterSupplier a {@link Supplier} of {@link Publisher} to emit from after termination
	 * @param <V> the supplied produced type
	 *
	 * @return a new {@link Flux} emitting eventually from the supplied {@link Publisher}
	 * @deprecated use {@link #thenMany}
	 */
	@Deprecated
	@SuppressWarnings("unchecked")
	public final <V> Flux<V> after(Supplier<? extends Publisher<V>> afterSupplier) {
		return (Flux<V>)concat(ignoreElements(), defer(afterSupplier));
	}


	/**
	 * Emit from the fastest first sequence between this publisher and the given publisher
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/amb.png" alt="">
	 * <p>
	 * @param other the {@link Publisher} to race with
	 *
	 * @return the fastest sequence
	 */
	public final Flux<T> ambWith(Publisher<? extends T> other) {
		if (this instanceof FluxAmb) {
			FluxAmb<T> publisherAmb = (FluxAmb<T>) this;
			
			FluxAmb<T> result = publisherAmb.ambAdditionalSource(other);
			if (result != null) {
				return result;
			}
		}
		return amb(this, other);
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Mono} on complete only.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffer.png"
	 * alt="">
	 *
	 * @return a buffered {@link Mono} of at most one {@link List}
	 */
	@SuppressWarnings("unchecked")
    public final Flux<List<T>> buffer() {
	    return buffer(Integer.MAX_VALUE);
	}

	/**
	 * Collect incoming values into multiple {@link List} buckets that will be pushed into the returned {@link Flux}
	 * when the given max size is reached or onComplete is received.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersize.png"
	 * alt="">
	 *
	 * @param maxSize the maximum collected size
	 *
	 * @return a microbatched {@link Flux} of {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final Flux<List<T>> buffer(int maxSize) {
		return new FluxBuffer<>(this, maxSize, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Flux} when the
	 * given max size is reached or onComplete is received. A new container {@link List} will be created every given
	 * skip count.
	 * <p>
	 * When Skip > Max Size : dropping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersizeskip.png"
	 * alt="">
	 * <p>
	 * When Skip < Max Size : overlapping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersizeskipover.png"
	 * alt="">
	 * <p>
	 * When Skip == Max Size : exact buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffersize.png"
	 * alt="">
	 *
	 * @param skip the number of items to skip before creating a new bucket
	 * @param maxSize the max collected size
	 *
	 * @return a microbatched {@link Flux} of possibly overlapped or gapped {@link List}
	 */
	@SuppressWarnings("unchecked")
	public final Flux<List<T>> buffer(int maxSize, int skip) {
		return new FluxBuffer<>(this, maxSize, skip, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@link Publisher} signals.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferboundary.png"
	 * alt="">
	 *
	 * @param other the other {@link Publisher}  to subscribe to for emiting and recycling receiving bucket
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by a {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final Flux<List<T>> buffer(Publisher<?> other) {
		return new FluxBufferBoundary<>(this, other, LIST_SUPPLIER);
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@link Publisher} signals. Each {@link
	 * List} bucket will last until the mapped {@link Publisher} receiving the boundary signal emits, thus releasing the
	 * bucket to the returned {@link Flux}.
	 * <p>
	 * When Open signal is strictly not overlapping Close signal : dropping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferopenclose.png"
	 * alt="">
	 * <p>
	 * When Open signal is strictly more frequent than Close signal : overlapping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferopencloseover.png"
	 * alt="">
	 * <p>
	 * When Open signal is exactly coordinated with Close signal : exact buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/bufferboundary.png"
	 * alt="">
	 *
	 * @param bucketOpening a {@link Publisher} to subscribe to for creating new receiving bucket signals.
	 * @param closeSelector a {@link Publisher} factory provided the opening signal and returning a {@link Publisher} to
	 * subscribe to for emitting relative bucket.
	 * @param <U> the element type of the bucket-opening sequence
	 * @param <V> the element type of the bucket-closing sequence
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by an opening {@link Publisher} and a relative
	 * closing {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final <U, V> Flux<List<T>> buffer(Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> closeSelector) {

		return new FluxBufferStartEnd<>(this, bucketOpening, closeSelector, LIST_SUPPLIER, QueueSupplier.<List<T>>xs());
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Flux} every
	 * timespan.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png"
	 * alt="">
	 *
	 * @param timespan the duration to use to release a buffered list
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period
	 */
	public final Flux<List<T>> buffer(Duration timespan) {
		return buffer(timespan, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} that will be pushed into the returned {@link Flux} every
	 * timespan.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png"
	 * alt="">
	 *
	 * @param timespan theduration to use to release a buffered list
	 * @param timer the {@link TimedScheduler} to schedule on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period
	 */
	public final Flux<List<T>> buffer(Duration timespan, TimedScheduler timer) {
		return buffer(interval(timespan, timer));
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@code timeshift} period. Each {@link
	 * List} bucket will last until the {@code timespan} has elapsed, thus releasing the bucket to the returned {@link
	 * Flux}.
	 * <p>
	 * When timeshift > timestamp : dropping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshift.png"
	 * alt="">
	 * <p>
	 * When timeshift < timestamp : overlapping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshiftover.png"
	 * alt="">
	 * <p>
	 * When timeshift == timestamp : exact buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png"
	 * alt="">
	 *
	 * @param timespan the duration to use to release buffered lists
	 * @param timeshift the duration to use to create a new bucket
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period timeshift and sized by timespan
	 */
	public final Flux<List<T>> buffer(Duration timespan, Duration timeshift) {
		return buffer(timespan, timeshift, getTimer());
	}

	/**
	 * Collect incoming values into multiple {@link List} delimited by the given {@code timeshift} period. Each {@link
	 * List} bucket will last until the {@code timespan} has elapsed, thus releasing the bucket to the returned {@link
	 * Flux}.
	 * <p>
	 * When timeshift > timestamp : dropping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshift.png"
	 * alt="">
	 * <p>
	 * When timeshift < timestamp : overlapping buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimeshiftover.png"
	 * alt="">
	 * <p>
	 * When timeshift == timestamp : exact buffers
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespan.png"
	 * alt="">
	 *
	 * @param timespan the duration to use to release buffered lists
	 * @param timeshift the duration to use to create a new bucket
	 * @param timer the {@link TimedScheduler} to run on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by the given period timeshift and sized by timespan
	 */
	public final Flux<List<T>> buffer(Duration timespan, final Duration timeshift, final TimedScheduler timer) {
		if (timespan.equals(timeshift)) {
			return buffer(timespan, timer);
		}
		return buffer(interval(Duration.ZERO, timeshift, timer), aLong -> Mono.delay(timespan, timer));
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Flux} every timespan OR
	 * maxSize items.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png"
	 * alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout in milliseconds to use to release a buffered list
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by given size or a given period timeout
	 */
	public final Flux<List<T>> buffer(int maxSize, long timespan) {
		return buffer(maxSize, timespan, getTimer());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Flux} every timespan OR
	 * maxSize items.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png"
	 * alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout to use to release a buffered list
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by given size or a given period timeout
	 */
	public final Flux<List<T>> buffer(int maxSize, Duration timespan) {
		return buffer(maxSize, timespan.toMillis());
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Flux} every timespan OR
	 * maxSize items
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png"
	 * alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout to use to release a buffered list
	 * @param timer the {@link TimedScheduler} to run on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by given size or a given period timeout
	 */
	public final Flux<List<T>> buffer(int maxSize, final long timespan, final TimedScheduler timer) {
		return new FluxBufferTimeOrSize<>(this, maxSize, timespan, timer);
	}

	/**
	 * Collect incoming values into a {@link List} that will be pushed into the returned {@link Flux} every timespan OR
	 * maxSize items
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/buffertimespansize.png"
	 * alt="">
	 *
	 * @param maxSize the max collected size
	 * @param timespan the timeout to use to release a buffered list
	 * @param timer the {@link TimedScheduler} to run on
	 *
	 * @return a microbatched {@link Flux} of {@link List} delimited by given size or a given period timeout
	 */
	public final Flux<List<T>> buffer(int maxSize, final Duration timespan, final TimedScheduler timer) {
		return buffer(maxSize, timespan.toMillis(), timer);
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further {@link Subscriber}. Will
	 * retain up to {@link PlatformDependent#SMALL_BUFFER_SIZE} onNext signals. Completion and Error will also be
	 * replayed.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png"
	 * alt="">
	 *
	 * @return a replaying {@link Flux}
	 */
	public final Flux<T> cache() {
		return cache(getPrefetchOrDefault(PlatformDependent.SMALL_BUFFER_SIZE));
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to the given history size onNext signals. Completion and Error will also be
	 * replayed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png" alt="">
	 *
	 * @param history number of events retained in history excluding complete and error
	 *
	 * @return a replaying {@link Flux}
	 *
	 */
	public final Flux<T> cache(int history) {
		return process(ReplayProcessor.create(history)).autoConnect();
	}
	
	/**
	 * Cast the current {@link Flux} produced type into a target produced type.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cast.png" alt="">
	 *
	 * @param <E> the {@link Flux} output type
	 * @param stream the target class to cast to
	 *
	 * @return a casted {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public final <E> Flux<E> cast(Class<E> stream) {
		return (Flux<E>) this;
	}

	/**
	 * Collect the {@link Flux} sequence with the given collector and supplied container on subscribe.
	 * The collected result will be emitted when this sequence completes.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/collect.png" alt="">

	 *
	 * @param <E> the {@link Flux} collected container type
	 * @param containerSupplier the supplier of the container instance for each Subscriber
	 * @param collector the consumer of both the container instance and the current value
	 *
	 * @return a {@link Mono} sequence of the collected value on complete
	 *
	 */
	public final <E> Mono<E> collect(Supplier<E> containerSupplier, BiConsumer<E, ? super T> collector) {
		return new MonoCollect<>(this, containerSupplier, collector);
	}

	/**
	 * Collect the {@link Flux} sequence with the given collector and supplied container on subscribe.
	 * The collected result will be emitted when this sequence completes.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/collect.png" alt="">

	 *
	 * @param collector the {@link Collector} 
	 * @param <A> The mutable accumulation type
	 * @param <R> the {@link Flux} collected container type
	 *
	 * @return a {@link Mono} sequence of the collected value on complete
	 *
	 */
	public final <R, A> Mono<R> collect(Collector<T, A, R> collector) {
		return new MonoStreamCollector<>(this, collector);
	}

	/**
	 * Defer the given transformation to this {@link Flux} in order to generate a
	 * target {@link Publisher} type. A transformation will occur for each
	 * {@link Subscriber}.
	 *
	 * {@code flux.compose(Mono::from).subscribe(Subscribers.unbounded()) }
	 *
	 * @param transformer the {@link Function} to immediately map this {@link Flux} into a target {@link Publisher}
	 * instance.
	 * @param <V> the item type in the returned {@link Publisher}
	 *
	 * @return a new {@link Flux}
	 * @see #as for a loose conversion to an arbitrary type
	 */
	public final <V> Flux<V> compose(Function<? super Flux<T>, ?
			extends Publisher<V>>
			transformer) {
		return defer(() -> transformer.apply(this));
	}

	/**
	 * Bind dynamic sequences given this input sequence like {@link #flatMap(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 * Errors will immediately short circuit current concat backlog.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png" alt="">
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final <V> Flux<V> concatMap(Function<? super T, ? extends Publisher<? extends V>>
			mapper) {
		return concatMap(mapper, getPrefetchOrDefault(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Bind dynamic sequences given this input sequence like {@link #flatMap(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 * Errors will immediately short circuit current concat backlog.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png" alt="">
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param prefetch the inner source produced demand
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final <V> Flux<V> concatMap(Function<? super T, ? extends Publisher<? extends V>>
			mapper, int prefetch) {
		return new FluxConcatMap<>(this, mapper, QueueSupplier.get(prefetch), prefetch,
				FluxConcatMap.ErrorMode.IMMEDIATE);
	}

	/**
	 * Concatenate emissions of this {@link Flux} with the provided {@link Publisher} (no interleave).
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concat.png"
	 * alt="">
	 *
	 * @param other the {@link Publisher} sequence to concat after this {@link Flux}
	 *
	 * @return a concatenated {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public final Flux<T> concatWith(Publisher<? extends T> other) {
		if (this instanceof FluxConcatArray) {
			FluxConcatArray<T> fluxConcatArray = (FluxConcatArray<T>) this;
			return fluxConcatArray.concatAdditionalSourceLast(other);
		}
		return new FluxConcatArray<>(false, this, other);
	}

	/**
	 * Bind dynamic sequences given this input sequence like {@link #flatMap(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 *
	 * Errors will be delayed after the current concat backlog.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png" alt="">
	 *
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 *
	 */
	public final <V> Flux<V> concatMapDelayError(Function<? super T, Publisher<? extends V>> mapper) {
		return concatMapDelayError(mapper, getPrefetchOrDefault(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Bind dynamic sequences given this input sequence like {@link #flatMap(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 *
	 * Errors will be delayed after the current concat backlog.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png" alt="">
	 *
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of V
	 * @param prefetch the inner source produced demand
	 * @param <V> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 *
	 */
	public final <V> Flux<V> concatMapDelayError(Function<? super T, ? extends Publisher<?
			extends V>> mapper, int prefetch) {
		return new FluxConcatMap<>(this, mapper, QueueSupplier.get(prefetch), prefetch,
				FluxConcatMap.ErrorMode.END);
	}

	/**
	 * Bind {@link Iterable} sequences given this input sequence like {@link #flatMapIterable(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 * <p>
	 * Errors will be delayed after the current concat backlog.
	 * <p>
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png"
	 * alt="">
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of R
	 * @param <R> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final <R> Flux<R> concatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper) {
		return concatMapIterable(mapper, getPrefetchOrDefault(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Bind {@link Iterable} sequences given this input sequence like {@link #flatMapIterable(Function)}, but preserve
	 * ordering and concatenate emissions instead of merging (no interleave).
	 * <p>
	 * Errors will be delayed after the current concat backlog.
	 * <p>
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/concatmap.png"
	 * alt="">
	 *
	 * @param mapper the function to transform this sequence of T into concatenated sequences of R
	 * @param prefetch the inner source produced demand
	 * @param <R> the produced concatenated type
	 *
	 * @return a concatenated {@link Flux}
	 */
	public final <R> Flux<R> concatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper,
			int prefetch) {
		return new FluxFlattenIterable<>(this, mapper, prefetch, QueueSupplier.get(prefetch));
	}

	/**
	 * Subscribe a {@link Consumer} to this {@link Flux} that will consume all the
	 * sequence.  If {@link Flux#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(java.util.function.Consumer)}
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribe.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link Cancellation} to dispose the {@link Subscription}
	 * @deprecated use {@link #subscribe}
	 */
	@Deprecated
	public final Cancellation consume(Consumer<? super T> consumer) {
		return consume(consumer, null, null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will consume all the
	 * sequence.  If {@link Flux#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see
	 * {@link #doOnNext(java.util.function.Consumer)} and {@link #doOnError(java.util.function.Consumer)}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribeerror.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on error signal
	 *
	 * @return a new {@link Cancellation} to dispose the {@link Subscription}
	 * @deprecated use {@link #subscribe}
	 */
	@Deprecated
	public final Cancellation consume(Consumer<? super T> consumer, Consumer<? super Throwable> errorConsumer) {
		return consume(consumer, errorConsumer, null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will consume all the
	 * sequence.  If {@link Flux#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(java.util.function.Consumer)},
	 * {@link #doOnError(java.util.function.Consumer)} and {@link #doOnComplete(Runnable)},
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribecomplete.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return a new {@link Cancellation} to dispose the {@link Subscription}
	 * @deprecated use {@link #subscribe}
	 */
	@Deprecated
	public final Cancellation consume(Consumer<? super T> consumer,
			Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {

		long c = Math.min(Integer.MAX_VALUE, getCapacity());

		LambdaSubscriber<T> consumerAction;
		if (c == Integer.MAX_VALUE || c == -1L) {
			consumerAction = new LambdaSubscriber<>(consumer, errorConsumer, completeConsumer);
		}
		else {
			consumerAction = Subscribers.bounded((int) c, consumer, errorConsumer, completeConsumer);
		}

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Counts the number of values in this {@link Flux}.
	 * The count will be emitted when onComplete is observed.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/count.png" alt="">
	 *
	 * @return a new {@link Mono} of {@link Long} count
	 */
	public final Mono<Long> count() {
		return new MonoCount<>(this);
	}
	
	/**
	 * Introspect this {@link Flux} graph
	 *
	 * @return {@link ReactiveStateUtils} {@literal Graph} representation of the operational flow
	 */
	public final ReactiveStateUtils.Graph debug() {
		return ReactiveStateUtils.scan(this);
	}

	/**
	 * Provide a default unique value if this sequence is completed without any data
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/defaultifempty.png" alt="">
	 * <p>
	 * @param defaultV the alternate value if this sequence is empty
	 *
	 * @return a new {@link Flux}
	 */
	public final Flux<T> defaultIfEmpty(T defaultV) {
		return new FluxDefaultIfEmpty<>(this, defaultV);
	}


	/**
	 * Delay this {@link Flux} signals to {@link Subscriber#onNext} until the given period in seconds elapses.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delayonnext.png" alt="">
	 *
	 * @param seconds period to delay each {@link Subscriber#onNext} call
	 *
	 * @return a throttled {@link Flux}
	 *
	 */
	public final Flux<T> delay(long seconds) {
		return delay(Duration.ofSeconds(seconds));
	}


	/**
	 * Delay this {@link Flux} signals to {@link Subscriber#onNext} until the given period elapses.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delayonnext.png" alt="">
	 *
	 * @param delay duration to delay each {@link Subscriber#onNext} call
	 *
	 * @return a throttled {@link Flux}
	 *
	 */
	public final Flux<T> delay(Duration delay) {
		TimedScheduler timer = getTimer();
		return concatMap(t ->  Mono.delay(delay, timer).map(i -> t));
	}

	/**
	 * Delay the {@link Flux#subscribe(Subscriber) subscription} to this {@link Flux} source until the given
	 * period elapses.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay period in seconds before subscribing this {@link Flux}
	 *
	 * @return a delayed {@link Flux}
	 *
	 */
	public final Flux<T> delaySubscription(long delay) {
		return delaySubscription(Duration.ofSeconds(delay));
	}

	/**
	 * Delay the {@link Flux#subscribe(Subscriber) subscription} to this {@link Flux} source until the given
	 * period elapses.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscription.png" alt="">
	 *
	 * @param delay duration before subscribing this {@link Flux}
	 *
	 * @return a delayed {@link Flux}
	 *
	 */
	public final Flux<T> delaySubscription(Duration delay) {
		TimedScheduler timer = getTimer();
		return delaySubscription(Mono.delay(delay, timer));
	}

	/**
	 * Delay the subscription to the main source until another Publisher
	 * signals a value or completes.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/delaysubscriptionp.png" alt="">
	 *
	 * @param subscriptionDelay a
	 * {@link Publisher} to signal by next or complete this {@link Flux#subscribe(Subscriber)}
	 * @param <U> the other source type
	 *
	 * @return a delayed {@link Flux}
	 *
	 */
	public final <U> Flux<T> delaySubscription(Publisher<U> subscriptionDelay) {
		return new FluxDelaySubscription<>(this, subscriptionDelay);
	}

	/**
	 * A "phantom-operator" working only if this
	 * {@link Flux} is a emits onNext, onError or onComplete {@link Signal}. The relative {@link Subscriber}
	 * callback will be invoked, error {@link Signal} will trigger onError and complete {@link Signal} will trigger
	 * onComplete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dematerialize.png" alt="">
	 *
	 * @param <X> the dematerialized type
	 * 
	 * @return a dematerialized {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public final <X> Flux<X> dematerialize() {
		Flux<Signal<X>> thiz = (Flux<Signal<X>>) this;
		return new FluxDematerialize<>(thiz);
	}


	/**
	 * For each {@link Subscriber}, tracks this {@link Flux} values that have been seen and
	 * filters out duplicates.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinct.png" alt="">
	 *
	 * @return a filtering {@link Flux} with unique values
	 */
	@SuppressWarnings("unchecked")
	public final Flux<T> distinct() {
		return new FluxDistinct<>(this, HASHCODE_EXTRACTOR, hashSetSupplier());
	}

	/**
	 * For each {@link Subscriber}, tracks this {@link Flux} values that have been seen and
	 * filters out duplicates given the extracted key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctk.png" alt="">
	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @param <V> the type of the key extracted from each value in this sequence
	 * 
	 * @return a filtering {@link Flux} with values having distinct keys
	 */
	public final <V> Flux<T> distinct(Function<? super T, ? extends V> keySelector) {
		if (this instanceof Fuseable) {
			return new FluxDistinctFuseable<>(this, keySelector, hashSetSupplier());
		}
		return new FluxDistinct<>(this, keySelector, hashSetSupplier());
	}

	/**
	 * Filters out subsequent and repeated elements.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctuntilchanged.png" alt="">
	 *
	 * @return a filtering {@link Flux} with conflated repeated elements
	 */
	@SuppressWarnings("unchecked")
	public final Flux<T> distinctUntilChanged() {
		return new FluxDistinctUntilChanged<T, T>(this, HASHCODE_EXTRACTOR);
	}
	
	/**
	 * Filters out subsequent and repeated elements provided a matching extracted key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/distinctuntilchangedk.png" alt="">

	 *
	 * @param keySelector function to compute comparison key for each element
	 *
	 * @param <V> the type of the key extracted from each value in this sequence
	 * 
	 * @return a filtering {@link Flux} with conflated repeated elements given a comparison key
	 */
	public final <V> Flux<T> distinctUntilChanged(Function<? super T, ? extends V> keySelector) {
		return new FluxDistinctUntilChanged<>(this, keySelector);
	}

	/**
	 * Triggered after the {@link Flux} terminates, either by completing downstream successfully or with an error.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doafterterminate.png" alt="">
	 * <p>
	 * @param afterTerminate the callback to call after {@link Subscriber#onComplete} or {@link Subscriber#onError}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doAfterTerminate(Runnable afterTerminate) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this,null, null, null, null,
					afterTerminate, null, null);
		}
		return new FluxPeek<>(this,null, null, null, null, afterTerminate, null, null);
	}

	/**
	 * Triggered when the {@link Flux} is cancelled.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dooncancel.png" alt="">
	 * <p>
	 * @param onCancel the callback to call on {@link Subscription#cancel}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnCancel(Runnable onCancel) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this,null, null, null, null, null, null, onCancel);
		}
		return new FluxPeek<>(this,null, null, null, null, null, null, onCancel);
	}

	/**
	 * Triggered when the {@link Flux} completes successfully.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/dooncomplete.png" alt="">
	 * <p>
	 * @param onComplete the callback to call on {@link Subscriber#onComplete}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnComplete(Runnable onComplete) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this,null, null, null, onComplete, null, null, null);
		}
		return new FluxPeek<>(this,null, null, null, onComplete, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} completes with an error.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerror.png" alt="">
	 * <p>
	 * @param onError the callback to call on {@link Subscriber#onError}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnError(Consumer<Throwable> onError) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this,null, null, onError, null, null, null, null);
		}
		return new FluxPeek<>(this,null, null, onError, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} completes with an error matching the given exception type.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonerrorw.png" alt="">
	 *
	 * @param exceptionType the type of exceptions to handle
	 * @param onError the error handler for each error
	 * @param <E> type of the error to handle
	 *
	 * @return an observed  {@link Flux}
	 *
	 */
	@SuppressWarnings("unchecked")
	public final <E extends Throwable> Flux<T> doOnError(Class<E> exceptionType,
			final Consumer<E> onError) {
		return doOnError( t -> { if(exceptionType.isAssignableFrom(t.getClass())){
			onError.accept((E)t);
		}});
	}

	/**
	 * Triggered when the {@link Flux} emits an item.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonnext.png" alt="">
	 * <p>
	 * @param onNext the callback to call on {@link Subscriber#onNext}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnNext(Consumer<? super T> onNext) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this, null, onNext, null, null, null,
					null, null);
		}
		return new FluxPeek<>(this, null, onNext, null, null, null, null, null);
	}

	/**
	 * Attach a {@link LongConsumer} to this {@link Flux} that will observe any request to this {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonrequest.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each request
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnRequest(LongConsumer consumer) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this,null, null, null, null, null, consumer, null);
		}
		return new FluxPeek<>(this,null, null, null, null, null, consumer, null);
	}

	/**
	 * Triggered when the {@link Flux} is subscribed.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonsubscribe.png" alt="">
	 * <p>
	 * @param onSubscribe the callback to call on {@link Subscriber#onSubscribe}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnSubscribe(Consumer<? super Subscription> onSubscribe) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this, onSubscribe, null, null, null, null, null, null);
		}
		return new FluxPeek<>(this, onSubscribe, null, null, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} terminates, either by completing successfully or with an error.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/doonterminate.png" alt="">
	 * <p>
	 * @param onTerminate the callback to call on {@link Subscriber#onComplete} or {@link Subscriber#onError}
	 *
	 * @return an observed  {@link Flux}
	 */
	public final Flux<T> doOnTerminate(Runnable onTerminate) {
		if (this instanceof Fuseable) {
			return new FluxPeekFuseable<>(this,null, null, null, onTerminate, null, null, null);
		}
		return new FluxPeek<>(this,null, null, null, onTerminate, null, null, null);
	}

	/**
	 * Map this {@link Flux} sequence into {@link reactor.core.tuple.Tuple2} of T1 {@link Long} timemillis and T2
	 * {@code T} associated data. The timemillis corresponds to the elapsed time between the subscribe and the first
	 * next signal OR between two next signals.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elapsed.png" alt="">
	 *
	 * @return a transforming {@link Flux} that emits tuples of time elapsed in milliseconds and matching data
	 */
	public final Flux<Tuple2<Long, T>> elapsed() {
		return compose(f -> f.map(new Elapsed<>()));
	}

	/**
	 * Emit only the element at the given index position or {@link IndexOutOfBoundsException} if the sequence is shorter.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elementat.png" alt="">
	 *
	 * @param index index of an item
	 *
	 * @return a {@link Mono} of the item at a specified index
	 */
	public final Mono<T> elementAt(int index) {
		return new MonoElementAt<>(this, index);
	}

	/**
	 * Emit only the element at the given index position or signals a
	 * default value if specified if the sequence is shorter.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/elementatd.png" alt="">
	 *
	 * @param index index of an item
	 * @param defaultValue supply a default value if not found
	 *
	 * @return a {@link Mono} of the item at a specified index or a default value
	 */
	public final Mono<T> elementAtOrDefault(int index, Supplier<? extends T> defaultValue) {
		return new MonoElementAt<>(this, index, defaultValue);
	}

	/**
	 * Emit only the last value of each batch counted from this {@link Flux} sequence.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/every.png" alt="">
	 *
	 * @param batchSize the batch size to count
	 *
	 * @return a new {@link Flux} whose values are the last value of each batch
	 */
	public final Flux<T> every(int batchSize) {
		return window(batchSize).flatMap(Flux::last);
	}

	/**
	 * Emit only the first value of each batch counted from this {@link Flux} sequence.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/everyfirst.png" alt="">
	 *
	 * @param batchSize the batch size to use
	 *
	 * @return a new {@link Flux} whose values are the first value of each batch
	 */
	public final Flux<T> everyFirst(int batchSize) {
		return window(batchSize).flatMap(Flux::next);
	}

	/**
	 * Emit a single boolean true if any of the values of this {@link Flux} sequence match
	 * the predicate.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true if
	 * the predicate matches a value.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/exists.png" alt="">
	 *
	 * @param predicate predicate tested upon values
	 *
	 * @return a new {@link Flux} with <code>true</code> if any value satisfies a predicate and <code>false</code>
	 * otherwise
	 *
	 */
	public final Mono<Boolean> exists(Predicate<? super T> predicate) {
		return new MonoExist<>(this, predicate);
	}

	/**
	 * Evaluate each accepted value against the given {@link Predicate}. If the predicate test succeeds, the value is
	 * passed into the new {@link Flux}. If the predicate test fails, the value is ignored and a request of 1 is
	 * emitted.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/filter.png" alt="">
	 *
	 * @param p the {@link Predicate} to test values against
	 *
	 * @return a new {@link Flux} containing only values that pass the predicate test
	 */
	public final Flux<T> filter(Predicate<? super T> p) {
		if (this instanceof Fuseable) {
			return new FluxFilterFuseable<>(this, p);
		}
		return new FluxFilter<>(this, p);
	}


	/**
	 * Transform the items emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmap.png" alt="">
	 * <p>
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param <R> the merged output sequence type
	 *
	 * @return a new {@link Flux}
	 */
	public final <R> Flux<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
		return flatMap(mapper, getPrefetchOrDefault(PlatformDependent.SMALL_BUFFER_SIZE), PlatformDependent
				.XS_BUFFER_SIZE);
	}


	/**
	 * Transform the items emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Flux} sequence
	 * @param <V> the merged output sequence type
	 *
	 * @return a new {@link Flux}
	 *
	 */
	public final <V> Flux<V> flatMap(Function<? super T, ? extends Publisher<? extends V>> mapper, int
			concurrency) {
		return flatMap(mapper, concurrency, PlatformDependent.XS_BUFFER_SIZE);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Publisher}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Flux} sequence
	 * @param prefetch the maximum in-flight elements from each inner {@link Publisher} sequence
	 * @param <V> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 *
	 */
	public final <V> Flux<V> flatMap(Function<? super T, ? extends Publisher<? extends V>> mapper, int
			concurrency, int prefetch) {
		return flatMap(mapper, concurrency, prefetch, false);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave. The concurrency argument allows to
	 * control how many merged {@link Publisher} can happen in parallel. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Publisher}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Publisher}
	 * @param concurrency the maximum in-flight elements from this {@link Flux} sequence
	 * @param prefetch the maximum in-flight elements from each inner {@link Publisher} sequence
	 * @param delayError should any error be delayed after current merge backlog
	 * @param <V> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 *
	 */
	public final <V> Flux<V> flatMap(Function<? super T, ? extends Publisher<? extends V>> mapper, int
			concurrency, int prefetch, boolean delayError) {
		return new FluxFlatMap<>(
				this,
				mapper,
				delayError,
				concurrency,
				QueueSupplier.get(concurrency),
				prefetch,
				QueueSupplier.get(prefetch)
		);
	}

	/**
	 * Transform the signals emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave.
	 * OnError will be transformed into completion signal after its mapping callback has been applied.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmaps.png" alt="">
	 * <p>
	 * @param mapperOnNext the {@link Function} to call on next data and returning a sequence to merge
	 * @param mapperOnError the {@link Function} to call on error signal and returning a sequence to merge
	 * @param mapperOnComplete the {@link Function} to call on complete signal and returning a sequence to merge
	 * @param <R> the output {@link Publisher} type target
	 *
	 * @return a new {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public final <R> Flux<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapperOnNext,
			Function<Throwable, ? extends Publisher<? extends R>> mapperOnError,
			Supplier<? extends Publisher<? extends R>> mapperOnComplete) {
		return new FluxFlatMap<>(
				new FluxMapSignal<>(this, mapperOnNext, mapperOnError, mapperOnComplete),
				identityFunction(),
				false,
				PlatformDependent.XS_BUFFER_SIZE,
				QueueSupplier.xs(),
				PlatformDependent.XS_BUFFER_SIZE,
				QueueSupplier.xs()
		);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into {@link Iterable}, then flatten the elements from those by
	 * merging them into a single {@link Flux}. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Iterable}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmap.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Iterable}
	 * @param <R> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 *
	 */
	public final <R> Flux<R> flatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper) {
		return flatMapIterable(mapper, getPrefetchOrDefault(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Transform the items emitted by this {@link Flux} into {@link Iterable}, then flatten the emissions from those by
	 * merging them into a single {@link Flux}. The prefetch argument allows to give an
	 * arbitrary prefetch size to the merged {@link Iterable}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/flatmapc.png" alt="">
	 *
	 * @param mapper the {@link Function} to transform input sequence into N sequences {@link Iterable}
	 * @param prefetch the maximum in-flight elements from each inner {@link Iterable} sequence
	 * @param <R> the merged output sequence type
	 *
	 * @return a merged {@link Flux}
	 *
	 */
	public final <R> Flux<R> flatMapIterable(Function<? super T, ? extends Iterable<? extends R>> mapper, int prefetch) {
		return new FluxFlattenIterable<>(this, mapper, prefetch, QueueSupplier.get(prefetch));
	}

	@Override
	public int getMode() {
		return FACTORY;
	}


	@Override
	public String getName() {
		return getClass().getSimpleName()
		                 .replace(Flux.class.getSimpleName(), "");
	}

	/**
	 * Get the current timer available if any or try returning the shared Environment one (which may cause an error if
	 * no Environment has been globally initialized)
	 *
	 * @return any available {@link TimedScheduler}
	 */
	public TimedScheduler getTimer() {
		return Timer.global();
	}


	/**
	 * Re-route this sequence into dynamically created {@link Flux} for each unique key evaluated by the given
	 * key mapper.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/groupby.png" alt="">
	 *
	 * @param keyMapper the key mapping {@link Function} that evaluates an incoming data and returns a key.
	 * 
	 * @param <K> the key type extracted from each value of this sequence
	 * 
	 * @return a {@link Flux} of {@link GroupedFlux} grouped sequences
	 */
	@SuppressWarnings("unchecked")
	public final <K> Flux<GroupedFlux<K, T>> groupBy(Function<? super T, ? extends K> keyMapper) {
		return groupBy(keyMapper, identityFunction());
	}

	/**
	 * Re-route this sequence into dynamically created {@link Flux} for each unique key evaluated by the given
	 * key mapper. It will use the given value mapper to extract the element to route.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/groupby.png" alt="">
	 *
	 * @param keyMapper the key mapping function that evaluates an incoming data and returns a key.
	 * @param valueMapper the value mapping function that evaluates which data to extract for re-routing.

	 * @param <K> the key type extracted from each value of this sequence
	 * @param <V> the value type extracted from each value of this sequence
	 *
	 * @return a {@link Flux} of {@link GroupedFlux} grouped sequences
	 *
	 */
	public final <K, V> Flux<GroupedFlux<K, V>> groupBy(Function<? super T, ? extends K> keyMapper,
			Function<? super T, ? extends V> valueMapper) {
		return new FluxGroupBy<>(this, keyMapper, valueMapper,
				QueueSupplier.small(),
				QueueSupplier.unbounded(),
				PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Emit a single boolean true if any of the values of this {@link Flux} sequence match
	 * the  constant.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true if
	 * the constant matches a value.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/exists.png" alt="">
	 *
	 * @param value constant compared to incoming signals
	 *
	 * @return a new {@link Flux} with <code>true</code> if any value satisfies a predicate and <code>false</code>
	 * otherwise
	 *
	 */
	public final Mono<Boolean> hasElement(T value) {
		return exists(t -> Objects.equals(value, t));
	}

	/**
	 * Emit a single boolean true if this {@link Flux} sequence has at least one element.
	 * <p>
	 * The implementation uses short-circuit logic and completes with true on onNext.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/haselements.png" alt="">
	 *
	 * @return a new {@link Mono} with <code>true</code> if any value is emitted and <code>false</code>
	 * otherwise
	 */
	public final Mono<Boolean> hasElements() {
		return new MonoHasElements<>(this);
	}

	/**
	 * Hides the identities of this {@link Flux} and its {@link Subscription}
	 * as well.
	 *
	 * @return a new {@link Flux} defeating any {@link Publisher} / {@link Subscription} feature-detection
	 */
	public final Flux<T> hide() {
		return new FluxHide<>(this);
	}

	/**
	 * Ignores onNext signals (dropping them) and only reacts on termination.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignoreelements.png" alt="">
	 * <p>
	 *
	 * @return a new completable {@link Mono}.
	 */
	public final Mono<T> ignoreElements() {
		return Mono.ignoreElements(this);
	}

	/**
	 * Returns the appropriate Mono instance for a known Supplier Flux.
	 * 
	 * @param supplier the supplier Flux
	 * @return the mono representing that Flux
	 */
	Mono<T> convertToMono(Callable<T> supplier) {
	    if (supplier instanceof Fuseable.ScalarCallable) {
            Fuseable.ScalarCallable<T> scalarCallable = (Fuseable.ScalarCallable<T>) supplier;

            T v = scalarCallable.call();
            if (v == null) {
                return Mono.empty();
            }
            return Mono.just(v);
	    }
	    return new MonoCallable<>(supplier);
	}
	
	/**
	 * Signal the last element observed before complete signal.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/last.png" alt="">
	 *
	 * @return a limited {@link Flux}
	 */
	@SuppressWarnings("unchecked")
    public final Mono<T> last() {
	    if (this instanceof Callable) {
	        return convertToMono((Callable<T>)this);
	    }
		return new MonoTakeLastOne<>(this);
	}

	/**
	 * Observe all Reactive Streams signals and use {@link Logger} support to handle trace implementation. Default will
	 * use {@link Level#INFO} and java.util.logging. If SLF4J is available, it will be used instead.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * The default log category will be "reactor.core.publisher.FluxLog".
	 *
	 * @return a new unaltered {@link Flux}
	 */
	public final Flux<T> log() {
		return log(null, Level.INFO, Logger.ALL);
	}

	/**
	 * Observe all Reactive Streams signals and use {@link Logger} support to handle trace implementation. Default will
	 * use {@link Level#INFO} and java.util.logging. If SLF4J is available, it will be used instead.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 *
	 * @return a new unaltered {@link Flux}
	 */
	public final Flux<T> log(String category) {
		return log(category, Level.INFO, Logger.ALL);
	}

	/**
	 * Observe all Reactive Streams signals and use {@link Logger} support to handle trace implementation. Default will
	 * use the passed {@link Level} and java.util.logging. If SLF4J is available, it will be used instead.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 * @param level the level to enforce for this tracing Flux
	 *
	 * @return a new unaltered {@link Flux}
	 */
	public final Flux<T> log(String category, Level level) {
		return log(category, level, Logger.ALL);
	}

	/**
	 * Observe Reactive Streams signals matching the passed flags {@code options} and use {@link Logger} support to
	 * handle trace
	 * implementation. Default will
	 * use the passed {@link Level} and java.util.logging. If SLF4J is available, it will be used instead.
	 *
	 * Options allow fine grained filtering of the traced signal, for instance to only capture onNext and onError:
	 * <pre>
	 *     flux.log("category", Level.INFO, Logger.ON_NEXT | LOGGER.ON_ERROR)
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/log.png" alt="">
	 * <p>
	 * @param category to be mapped into logger configuration (e.g. org.springframework.reactor).
	 * @param level the level to enforce for this tracing Flux
	 * @param options a flag option that can be mapped with {@link Logger#ON_NEXT} etc.
	 *
	 * @return a new unaltered {@link Flux}
	 */
	public final Flux<T> log(String category, Level level, int options) {
		return new FluxLog<>(this, category, level, options);
	}

	/**
	 * Transform the items emitted by this {@link Flux} by applying a function to each item.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/map.png" alt="">
	 * <p>
	 * @param mapper the transforming {@link Function}
	 * @param <V> the transformed type
	 *
	 * @return a transformed {@link Flux}
	 */
	public final <V> Flux<V> map(Function<? super T, ? extends V> mapper) {
		if (this instanceof Fuseable) {
			return new FluxMapFuseable<>(this, mapper);
		}
		return new FluxMap<>(this, mapper);
	}


	/**
	 * Transform the error emitted by this {@link Flux} by applying a function.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/maperror.png" alt="">
	 * <p>
	 * @param mapper the error transforming {@link Function}
	 *
	 * @return a transformed {@link Flux}
	 */
	public final Flux<T> mapError(Function<Throwable, ? extends Throwable> mapper) {
		return onErrorResumeWith(e -> Mono.error(mapper.apply(e)));
	}

	/**
	 * Transform the incoming onNext, onError and onComplete signals into {@link Signal}.
	 * Since the error is materialized as a {@code Signal}, the propagation will be stopped and onComplete will be
	 * emitted. Complete signal will first emit a {@code Signal.complete()} and then effectively complete the flux.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/materialize.png" alt="">
	 *
	 * @return a {@link Flux} of materialized {@link Signal}
	 */
	public final Flux<Signal<T>> materialize() {
		return new FluxMaterialize<>(this);
	}

	/**
	 * Merge emissions of this {@link Flux} with the provided {@link Publisher}, so that they may interleave.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/merge.png" alt="">
	 * <p>
	 * @param other the {@link Publisher} to merge with
	 *
	 * @return a new {@link Flux}
	 */
	public final Flux<T> mergeWith(Publisher<? extends T> other) {
		if (this instanceof FluxMerge) {
			FluxMerge<T> fluxMerge = (FluxMerge<T>) this;
			return fluxMerge.mergeAdditionalSource(other, QueueSupplier::get);
		}
		return merge(this, other);
	}

	/**
	 * Make this
	 * {@link Flux} subscribed N concurrency times for each child {@link Subscriber}. In effect, if this {@link Flux}
	 * is a cold replayable source, duplicate sequences will be emitted to the passed {@link GroupedFlux} partition
	 * . If this {@link Flux} is a hot sequence, {@link GroupedFlux} partitions might observe different values, e
	 * .g. subscribing to a {@link reactor.core.publisher.WorkQueueProcessor}.
	 * <p>Each partition is merged back using {@link #flatMap flatMap} and the result sequence might be interleaved.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multiplex.png" alt="">
	 * 
	 * @param concurrency the concurrency level of the operation
	 * @param fn the indexed via
	 * {@link GroupedFlux#key()} sequence transformation to be merged in the returned {@link Flux}
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return a merged {@link Flux} produced from N concurrency transformed sequences
	 *
	 */
	public final <V> Flux<V> multiplex(int concurrency,
			final Function<GroupedFlux<Integer, T>, Publisher<V>> fn) {
		if(concurrency <= 0){
			throw new IllegalArgumentException( "Must subscribe once at least, concurrency set to " + concurrency);
		}

		Publisher<V> pub;
		final List<Publisher<? extends V>> publisherList = new ArrayList<>(concurrency);

		for (int i = 0; i < concurrency; i++) {
			final int index = i;
			pub = fn.apply(new MultiplexGroupedFlux<>(index, this));

			if (concurrency == 1) {
				return from(pub);
			}
			else {
				publisherList.add(pub);
			}
		}

		return Flux.merge(publisherList);
	}
	
	/**
	 * An indexed GroupedFlux instance, delegating to a source Flux.
	 *
	 * @param <T> the value type
	 */
	static final class MultiplexGroupedFlux<T> extends GroupedFlux<Integer, T> {

	    final int index;
	    
	    final Flux<T> source;

	    public MultiplexGroupedFlux(int index, Flux<T> source) {
	        this.index = index;
	        this.source = source;
	    }

	    @Override
	    public Integer key() {
	        return index;
	    }

	    @Override
	    public long getCapacity() {
	        return source.getCapacity();
	    }

	    @Override
	    public TimedScheduler getTimer() {
	        return source.getTimer();
	    }

	    @Override
	    public void subscribe(Subscriber<? super T> s) {
	        source.subscribe(s);
	    }
	}


	/**
	 * Emit the current instance of the {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/nest.png" alt="">
	 *
	 * @return a new {@link Mono} whose value will be the current {@link Flux}
	 */
	public final Mono<Flux<T>> nest() {
		return Mono.just(this);
	}
	
	/**
	 * Emit only the first item emitted by this {@link Flux}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/next.png" alt="">
	 * <p>
	 *
	 * @return a new {@link Mono}
	 */
	public final Mono<T> next() {
		return Mono.from(this);
	}


	/**
	 * Request an unbounded demand and push the returned {@link Flux}, or park the observed elements if not enough
	 * demand is requested downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressurebuffer.png" alt="">
	 *
	 * @return a buffering {@link Flux}
	 *
	 */
	public final Flux<T> onBackpressureBuffer() {
		return new FluxBackpressureBuffer<>(this);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Flux}, or drop the observed elements if not enough
	 * demand is requested downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressuredrop.png" alt="">
	 *
	 * @return a dropping {@link Flux}
	 *
	 */
	public final Flux<T> onBackpressureDrop() {
		return new FluxDrop<>(this);
	}

	/**
	 * Request an unbounded demand and push the returned {@link Flux}, or drop and notify dropping {@link Consumer}
	 * with the observed elements if not enough demand is requested downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressuredropc.png" alt="">
	 *
	 * @param onDropped the Consumer called when an value gets dropped due to lack of downstream requests
	 * @return a dropping {@link Flux}
	 *
	 */
	public final Flux<T> onBackpressureDrop(Consumer<? super T> onDropped) {
		return new FluxDrop<>(this, onDropped);
	}

	/**
	 * Request an unbounded demand and push the returned
	 * {@link Flux}, or emit onError fom {@link Exceptions#failWithOverflow} if not enough demand is requested
	 * downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressureerror.png" alt="">
	 *
	 * @return an erroring {@link Flux} on backpressure
	 *
	 */
	public final Flux<T> onBackpressureError() {
		return onBackpressureDrop(t -> { throw Exceptions.failWithOverflow();});
	}

	/**
	 * Request an unbounded demand and push the returned {@link Flux}, or only keep the most recent observed item
	 * if not enough demand is requested downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onbackpressurelatest.png" alt="">
	 *
	 * @return a dropping {@link Flux} that will only keep a reference to the last observed item
	 *
	 */
	public final Flux<T> onBackpressureLatest() {
		return new FluxLatest<>(this);
	}
	/**
	 * Subscribe to a returned fallback publisher when any error occurs.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onerrorresumewith.png" alt="">
	 * <p>
	 * @param fallback the {@link Function} mapping the error to a new {@link Publisher} sequence
	 *
	 * @return a new {@link Flux}
	 */
	public final Flux<T> onErrorResumeWith(Function<Throwable, ? extends Publisher<? extends T>> fallback) {
		return new FluxResume<>(this, fallback);
	}

	/**
	 * Fallback to the given value if an error is observed on this {@link Flux}
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/onerrorreturn.png" alt="">
	 * <p>
	 * @param fallbackValue alternate value on fallback
	 *
	 * @return a new {@link Flux}
	 */
	public final Flux<T> onErrorReturn(T fallbackValue) {
		return switchOnError(just(fallbackValue));
	}

	/**
	 * Detaches the both the child {@link Subscriber} and the {@link Subscription} on
	 * termination or cancellation.
	 * <p>This should help with odd retention scenarios when running
	 * with non-reactor {@link Subscriber}.
	 *
	 * @return a detachable {@link Flux}
	 */
	public final Flux<T> onTerminateDetach() {
		return new FluxDetach<>(this);
	}

	/**
	 * Re-route incoming values into a dynamically created {@link Flux} for each unique key evaluated by the given
	 * key mapper. The hashcode of the incoming data will be used for partitionning over
	 * {@link Computations#DEFAULT_POOL_SIZE} number of partitions. That
	 * means that at any point of time at most {@link Computations#DEFAULT_POOL_SIZE} number of streams will be
	 * created.
	 *
	 * <p> Partition resolution happens accordingly to the positive modulo of the current hashcode over
	 * the
	 * number of
	 * buckets {@link Computations#DEFAULT_POOL_SIZE}: <code>bucket = o.hashCode() % buckets;</code>
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/partition.png" alt="">
	 *
	 *
	 * @return a partitioning {@link Flux} whose values are {@link GroupedFlux} of all active partionned sequences
	 */
	public final Flux<GroupedFlux<Integer, T>> partition() {
		return partition(Computations.DEFAULT_POOL_SIZE);
	}

	/**
	 *
	 * Re-route incoming values into a dynamically created {@link Flux} for each unique key evaluated by the given
	 * key mapper. The hashcode of the incoming data will be used for partitioning over the buckets number passed. That
	 * means that at any point of time at most {@code buckets} number of streams will be created.
	 *
	 * <p> Partition resolution happens accordingly to the positive modulo of the current hashcode over the
	 * specified number of buckets: <code>bucket = o.hashCode() % buckets;</code>
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/partition.png" alt="">
	 *
	 * @param buckets the maximum number of buckets to partition the values across
	 *
	 * @return a partitioning {@link Flux} whose values are {@link GroupedFlux} of all active partionned sequences
	 */
	public final Flux<GroupedFlux<Integer, T>> partition(int buckets) {
		return groupBy(t -> {
			int bucket = t.hashCode() % buckets;
			return bucket < 0 ? bucket + buckets : bucket;
		});
	}


	/**
	 * Prepare a
	 * {@link ConnectableFlux} which subscribes this {@link Flux} sequence to the given {@link Processor}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber} when {@link ConnectableFlux#connect()}
	 *  is invoked manually or automatically via {@link ConnectableFlux#autoConnect} and {@link ConnectableFlux#refCount}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 * <p> The {@link Processor} will not be specifically reusable and multi-connect might not work as expected
	 * depending on the {@link Processor}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processor the {@link Processor} reference to subscribe to this {@link Flux} and share.
	 *
	 * @return a new {@link ConnectableFlux} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 */
	public final ConnectableFlux<T> process(Processor<? super T, ? extends T> processor) {
		return process(() -> processor);
	}

	/**
	 * Prepare a
	 * {@link ConnectableFlux} which subscribes this {@link Flux} sequence to a supplied {@link Processor}
	 * when
	 * {@link ConnectableFlux#connect()} is invoked manually or automatically via {@link ConnectableFlux#autoConnect} and {@link ConnectableFlux#refCount}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processorSupplier the {@link Processor} {@link Supplier} to call, subscribe to this {@link Flux} and
	 * share.
	 *
	 * @return a new {@link ConnectableFlux} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 */
	public final ConnectableFlux<T> process(
			Supplier<? extends Processor<? super T, ? extends T>> processorSupplier) {
		return process(processorSupplier, identityFunction());
	}

	/**
	 * Prepare a
	 * {@link ConnectableFlux} which subscribes this {@link Flux} sequence to the given {@link Processor}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber} when {@link ConnectableFlux#connect()}
	 *  is invoked manually or automatically via {@link ConnectableFlux#autoConnect} and {@link ConnectableFlux#refCount}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 * <p> The {@link Processor} will not be specifically reusable and multi-connect might not work as expected
	 * depending on the {@link Processor}.
	 *
	 * <p> The selector will be applied once per {@link Subscriber} and can be used to blackbox pre-processing.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processor the {@link Processor} reference to subscribe to this {@link Flux} and share.
	 * @param selector a {@link Function} receiving a {@link Flux} derived from the supplied {@link Processor} and
	 * returning the end {@link Publisher} subscribed by a unique {@link Subscriber}
	 * @param <U> produced type from the given selector
	 *
	 * @return a new {@link ConnectableFlux} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 */
	public final <U> ConnectableFlux<U> process(Processor<? super T, ? extends T>
			processor, Function<Flux<T>, ? extends Publisher<? extends U>> selector) {
		return process(() -> processor, selector);
	}

	/**
	 * Prepare a
	 * {@link ConnectableFlux} which subscribes this {@link Flux} sequence to a supplied {@link Processor}
	 * when
	 * {@link ConnectableFlux#connect()} is invoked manually or automatically via {@link ConnectableFlux#autoConnect} and {@link ConnectableFlux#refCount}.
	 * The {@link Processor} will be itself subscribed by child {@link Subscriber}.
	 *  Note that some {@link Processor} do not support multi-subscribe, multicast is non opinionated in fact and
	 *  focuses on subscribe lifecycle.
	 *
	 * This will effectively turn any type of sequence into a hot sequence by sharing a single {@link Subscription}.
	 * <p> The selector will be applied once per {@link Subscriber} and can be used to blackbox pre-processing.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/multicastp.png" alt="">
	 *
	 * @param processorSupplier the {@link Processor} {@link Supplier} to createWorker, subscribe to this {@link Flux} and
	 * share.
	 * @param selector a {@link Function} receiving a {@link Flux} derived from the supplied {@link Processor} and
	 * returning the end {@link Publisher} subscribed by a unique {@link Subscriber}
	 * @param <U> produced type from the given selector
	 *
	 * @return a new {@link ConnectableFlux} whose values are broadcasted to supported subscribers once connected via {@link Processor}
	 *
	 */
	public final <U> ConnectableFlux<U> process(Supplier<? extends Processor<? super T, ? extends T>>
			processorSupplier, Function<Flux<T>, ? extends Publisher<? extends U>> selector) {
		return new ConnectableFluxProcess<>(this, processorSupplier, selector);
	}

	/**
	 * Prepare a {@link ConnectableFlux} which shares this {@link Flux} sequence and dispatches values to
	 * subscribers in a backpressure-aware manner. Prefetch will default to {@link PlatformDependent#SMALL_BUFFER_SIZE}.
	 * This will effectively turn any type of sequence into a hot sequence.
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publish.png" alt="">
	 *
	 * @return a new {@link ConnectableFlux}
	 */
	public final ConnectableFlux<T> publish() {
		return publish(getPrefetchOrDefault(PlatformDependent.SMALL_BUFFER_SIZE));
	}

	/**
	 * Prepare a {@link ConnectableFlux} which shares this {@link Flux} sequence and dispatches values to
	 * subscribers in a backpressure-aware manner. This will effectively turn any type of sequence into a hot sequence.
	 * <p>
	 * Backpressure will be coordinated on {@link Subscription#request} and if any {@link Subscriber} is missing
	 * demand (requested = 0), multicast will pause pushing/pulling.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publish.png" alt="">
	 *
	 * @param prefetch bounded requested demand
	 *
	 * @return a new {@link ConnectableFlux}
	 */
	public final ConnectableFlux<T> publish(int prefetch) {
		return new ConnectableFluxPublish<>(this, prefetch, QueueSupplier.get(prefetch));
	}

	/**
	 * Shares a sequence for the duration of a function that may transform it and
	 * consume it as many times as necessary without causing multiple subscriptions
	 * to the upstream.
	 *
	 * @param transform
	 * @param <R> the output value type
	 *
	 * @return a new {@link Flux}
	 */
	public final <R> Flux<R> publish(Function<? super Flux<T>, ? extends Publisher<?
			extends
			R>> transform) {
		return publish(transform, getPrefetchOrDefault(PlatformDependent.SMALL_BUFFER_SIZE));
	}

	/**
	 * Shares a sequence for the duration of a function that may transform it and
	 * consume it as many times as necessary without causing multiple subscriptions
	 * to the upstream.
	 *
	 * @param transform
	 * @param prefetch
	 * @param <R> the output value type
	 *
	 * @return a new {@link Flux}
	 */
	public final <R> Flux<R> publish(Function<? super Flux<T>, ? extends Publisher<?
			extends R>> transform, int prefetch) {
		return new FluxPublish<>(this, transform, prefetch, QueueSupplier.get(prefetch));
	}

	/**
	 * Prepare a {@link Mono} which shares this {@link Flux} sequence and dispatches the first observed item to
	 * subscribers in a backpressure-aware manner.
	 * This will effectively turn any type of sequence into a hot sequence when the first {@link Subscriber} subscribes.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishnext.png" alt="">
	 *
	 * @return a new {@link Mono}
	 */
	public final Mono<T> publishNext() {
		return new MonoProcessor<>(this);
	}

	/**
	 * Run onNext, onComplete and onError on a supplied {@link Scheduler}
	 * {@link reactor.core.scheduler.Scheduler.Worker}.
	 *
	 * <p>
	 * Typically used for fast publisher, slow consumer(s) scenarios.
	 * It naturally combines with {@link Computations#single} and {@link Computations#parallel} which implement
	 * fast async event loops.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * {@code flux.publishOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param scheduler a checked {@link reactor.core.scheduler.Scheduler.Worker} factory
	 *
	 * @return a {@link Flux} producing asynchronously
	 */
	public final Flux<T> publishOn(Scheduler scheduler) {
		return publishOn(scheduler, PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Run onNext, onComplete and onError on a supplied {@link Scheduler}
	 * {@link reactor.core.scheduler.Scheduler.Worker}.
	 *
	 * <p>
	 * Typically used for fast publisher, slow consumer(s) scenarios.
	 * It naturally combines with {@link Computations#single} and {@link Computations#parallel} which implement
	 * fast async event loops.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * {@code flux.publishOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param scheduler a checked {@link reactor.core.scheduler.Scheduler.Worker} factory
	 * @param prefetch the asynchronous boundary capacity
	 *
	 * @return a {@link Flux} producing asynchronously
	 */
	public final Flux<T> publishOn(Scheduler scheduler, int prefetch) {
		if (this instanceof Fuseable.ScalarCallable) {
			@SuppressWarnings("unchecked") T value = ((Fuseable.ScalarCallable<T>) this).call();
			return new FluxSubscribeOnValue<>(value, scheduler);
		}

		return new FluxPublishOn<>(this, scheduler, true, prefetch, QueueSupplier.get(prefetch));
	}

	/**
	 * Run onNext, onComplete and onError on a supplied {@link ExecutorService}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * {@code flux.publishOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService}
	 *
	 * @return a {@link Flux} producing asynchronously
	 */
	public final Flux<T> publishOn(ExecutorService executorService) {
		return publishOn(executorService, PlatformDependent.SMALL_BUFFER_SIZE);
	}

	/**
	 * Run onNext, onComplete and onError on a supplied {@link Scheduler}
	 * {@link reactor.core.scheduler.Scheduler.Worker}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/publishon.png" alt="">
	 * <p>
	 * {@code flux.publishOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService}
	 * @param prefetch the asynchronous boundary capacity
	 *
	 * @return a {@link Flux} producing asynchronously
	 */
	public final Flux<T> publishOn(ExecutorService executorService, int prefetch) {
		return publishOn(new ExecutorServiceScheduler(executorService), prefetch);
	}

	/**
	 * Aggregate the values from this {@link Flux} sequence into an object of the same type than the
	 * emitted items. The left/right {@link BiFunction} arguments are the N-1 and N item, ignoring sequence
	 * with 0 or 1 element only.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/aggregate.png" alt="">
	 *
	 * @param aggregator the aggregating {@link BiFunction}
	 *
	 * @return a reduced {@link Flux}
	 *
	 *
	 */
    @SuppressWarnings("unchecked")
	public final Mono<T> reduce(BiFunction<T, T, T> aggregator) {
		if (this instanceof Callable){
		    return convertToMono((Callable<T>)this);
		}
		return new MonoAggregate<>(this, aggregator);
	}

	/**
	 * Accumulate the values from this {@link Flux} sequence into an object matching an initial value type.
	 * The arguments are the N-1 or {@literal initial} value and N current item .
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/reduce.png" alt="">
	 *
	 * @param accumulator the reducing {@link BiFunction}
	 * @param initial the initial left argument to pass to the reducing {@link BiFunction}
	 * @param <A> the type of the initial and reduced object
	 *
	 * @return a reduced {@link Flux}
	 *
	 */
	public final <A> Mono<A> reduce(A initial, BiFunction<A, ? super T, A> accumulator) {
		return reduceWith(() -> initial, accumulator);
	}

	/**
	 * Accumulate the values from this {@link Flux} sequence into an object matching an initial value type.
	 * The arguments are the N-1 or {@literal initial} value and N current item .
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/reduce.png" alt="">
	 *
	 * @param accumulator the reducing {@link BiFunction}
	 * @param initial the initial left argument supplied on subscription to the reducing {@link BiFunction}
	 * @param <A> the type of the initial and reduced object
	 *
	 * @return a reduced {@link Flux}
	 *
	 */
	public final <A> Mono<A> reduceWith(Supplier<A> initial, BiFunction<A, ? super T, A> accumulator) {
		return new MonoReduce<>(this, initial, accumulator);
	}

	/**
	 * Repeatedly subscribe to the source completion of the previous subscription.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeat.png" alt="">
	 *
	 * @return an indefinitively repeated {@link Flux} on onComplete
	 */
	public final Flux<T> repeat() {
		return repeat(ALWAYS_BOOLEAN_SUPPLIER);
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous subscription.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatb.png" alt="">
	 *
	 * @param predicate the boolean to evaluate on onComplete.
	 *
	 * @return an eventually repeated {@link Flux} on onComplete
	 *
	 */
	public final Flux<T> repeat(BooleanSupplier predicate) {
		return repeatWhen(v -> v.takeWhile(t -> predicate.getAsBoolean()));
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous subscription.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatn.png" alt="">
	 *
	 * @param numRepeat the number of times to re-subscribe on onComplete
	 *
	 * @return an eventually repeated {@link Flux} on onComplete up to number of repeat specified
	 *
	 */
	public final Flux<T> repeat(long numRepeat) {
		return new FluxRepeat<>(this, numRepeat);
	}

	/**
	 * Repeatedly subscribe to the source if the predicate returns true after completion of the previous
	 * subscription. A specified maximum of repeat will limit the number of re-subscribe.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatnb.png" alt="">
	 *
	 * @param numRepeat the number of times to re-subscribe on complete
	 * @param predicate the boolean to evaluate on onComplete
	 *
	 * @return an eventually repeated {@link Flux} on onComplete up to number of repeat specified OR matching
	 * predicate
	 *
	 */
	public final Flux<T> repeat(long numRepeat, BooleanSupplier predicate) {
		return repeat(countingBooleanSupplier(predicate, numRepeat));
	}

	/**
	 * Repeatedly subscribe to this {@link Flux} when a companion sequence signals a number of emitted elements in
	 * response to the flux completion signal.
	 * <p>If the companion sequence signals when this {@link Flux} is active, the repeat
	 * attempt is suppressed and any terminal signal will terminate this {@link Flux} with the same signal immediately.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/repeatwhen.png" alt="">
	 *
	 * @param whenFactory the {@link Function} providing a {@link Flux} signalling an exclusive number of
	 * emitted elements on onComplete and returning a {@link Publisher} companion.
	 *
	 * @return an eventually repeated {@link Flux} on onComplete when the companion {@link Publisher} produces an
	 * onNext signal
	 *
	 */
	public final Flux<T> repeatWhen(Function<Flux<Long>, ? extends Publisher<?>> whenFactory) {
		return new FluxRepeatWhen<>(this, whenFactory);
	}



	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further {@link Subscriber}. Will
	 * retain up to {@link PlatformDependent#SMALL_BUFFER_SIZE} onNext signals. Completion and Error will also be
	 * replayed.
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png"
	 * alt="">
	 *
	 * @return a replaying {@link ConnectableFlux}
	 */
	public final ConnectableFlux<T> replay() {
		return replay(getPrefetchOrDefault(PlatformDependent.SMALL_BUFFER_SIZE));
	}

	/**
	 * Turn this {@link Flux} into a hot source and cache last emitted signals for further {@link Subscriber}.
	 * Will retain up to the given history size onNext signals. Completion and Error will also be
	 * replayed.
	 *
	 * <p>
	 * <img width="500" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/cache.png" alt="">
	 *
	 * @param history number of events retained in history excluding complete and error
	 *
	 * @return a replaying {@link ConnectableFlux}
	 *
	 */
	public final ConnectableFlux<T> replay(int history) {
		return process(ReplayProcessor.create(history));
	}

	/**
	 * Re-subscribes to this {@link Flux} sequence if it signals any error
	 * either indefinitely.
	 * <p>
	 * The times == Long.MAX_VALUE is treated as infinite retry.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retry.png" alt="">
	 *
	 * @return a re-subscribing {@link Flux} on onError
	 */
	public final Flux<T> retry() {
		return retry(Long.MAX_VALUE);
	}

	/**
	 * Re-subscribes to this {@link Flux} sequence if it signals any error
	 * either indefinitely or a fixed number of times.
	 * <p>
	 * The times == Long.MAX_VALUE is treated as infinite retry.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retryn.png" alt="">
	 *
	 * @param numRetries the number of times to tolerate an error
	 *
	 * @return a re-subscribing {@link Flux} on onError up to the specified number of retries.
	 *
	 */
	public final Flux<T> retry(long numRetries) {
		return new FluxRetry<>(this, numRetries);
	}

	/**
	 * Re-subscribes to this {@link Flux} sequence if it signals any error
	 * and the given {@link Predicate} matches otherwise push the error downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retryb.png" alt="">
	 *
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a re-subscribing {@link Flux} on onError if the predicates matches.
	 */
	public final Flux<T> retry(Predicate<Throwable> retryMatcher) {
		Flux<Integer> one = Flux.just(1);
		return retryWhen(v -> v.flatMap(e -> retryMatcher.test(e) ? one : Flux.<T>error(e)));
	}

	/**
	 * Re-subscribes to this {@link Flux} sequence up to the specified number of retries if it signals any
	 * error and the given {@link Predicate} matches otherwise push the error downstream.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retrynb.png" alt="">
	 *
	 * @param numRetries the number of times to tolerate an error
	 * @param retryMatcher the predicate to evaluate if retry should occur based on a given error signal
	 *
	 * @return a re-subscribing {@link Flux} on onError up to the specified number of retries and if the predicate
	 * matches.
	 *
	 */
	public final Flux<T> retry(long numRetries, Predicate<Throwable> retryMatcher) {
		return retry(countingPredicate(retryMatcher, numRetries));
	}

	/**
	 * Retries this {@link Flux} when a companion sequence signals
	 * an item in response to this {@link Flux} error signal
	 * <p>If the companion sequence signals when the {@link Flux} is active, the retry
	 * attempt is suppressed and any terminal signal will terminate the {@link Flux} source with the same signal
	 * immediately.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/retrywhen.png" alt="">
	 *
	 * @param whenFactory the
	 * {@link Function} providing a {@link Flux} signalling any error from the source sequence and returning a {@link Publisher} companion.
	 *
	 * @return a re-subscribing {@link Flux} on onError when the companion {@link Publisher} produces an
	 * onNext signal
	 */
	public final Flux<T> retryWhen(Function<Flux<Throwable>, ? extends Publisher<?>> whenFactory) {
		return new FluxRetryWhen<>(this, whenFactory);
	}

	/**
	 * Emit latest value for every given period of ti,e.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimespan.png" alt="">
	 *
	 * @param timespan the period in second to emit the latest observed item
	 *
	 * @return a sampled {@link Flux} by last item over a period of time
	 */
	public final Flux<T> sample(long timespan) {
		return sample(Duration.ofSeconds(timespan));
	}

	/**
	 * Emit latest value for every given period of time.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimespan.png" alt="">
	 *
	 * @param timespan the duration to emit the latest observed item
	 *
	 * @return a sampled {@link Flux} by last item over a period of time
	 */
	public final Flux<T> sample(Duration timespan) {
		return sample(interval(timespan));
	}

	/**
	 * Sample this {@link Flux} and emit its latest value whenever the sampler {@link Publisher}
	 * signals a value.
	 * <p>
	 * Termination of either {@link Publisher} will result in termination for the {@link Subscriber}
	 * as well.
	 * <p>
	 * Both {@link Publisher} will run in unbounded mode because the backpressure
	 * would interfere with the sampling precision.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sample.png" alt="">
	 *
	 * @param sampler the sampler {@link Publisher}
	 * 
	 * @param <U> the type of the sampler sequence
	 *
	 * @return a sampled {@link Flux} by last item observed when the sampler {@link Publisher} signals
	 */
	public final <U> Flux<T> sample(Publisher<U> sampler) {
		return new FluxSample<>(this, sampler);
	}

	/**
	 * Take a value from this {@link Flux} then use the duration provided to skip other values.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirsttimespan.png" alt="">
	 *
	 * @param timespan the period in seconds to exclude others values from this sequence
	 *
	 * @return a sampled {@link Flux} by first item over a period of time
	 */
	public final Flux<T> sampleFirst(long timespan) {
		return sampleFirst(Duration.ofSeconds(timespan));
	}

	/**
	 * Take a value from this {@link Flux} then use the duration provided to skip other values.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirsttimespan.png" alt="">
	 *
	 * @param timespan the duration to exclude others values from this sequence
	 *
	 * @return a sampled {@link Flux} by first item over a period of time
	 */
	public final Flux<T> sampleFirst(Duration timespan) {
		return sampleFirst(t -> {
			return Mono.delay(timespan);
		});
	}

	/**
	 * Take a value from this {@link Flux} then use the duration provided by a
	 * generated Publisher to skip other values until that sampler {@link Publisher} signals.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/samplefirst.png" alt="">
	 *
	 * @param samplerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop excluding
	 * others values from this sequence
	 * @param <U> the companion reified type
	 *
	 * @return a sampled {@link Flux} by last item observed when the sampler signals
	 */
	public final <U> Flux<T> sampleFirst(Function<? super T, ? extends Publisher<U>> samplerFactory) {
		return new FluxThrottleFirst<>(this, samplerFactory);
	}


	/**
	 * Emit the last value from this {@link Flux} only if there were no new values emitted
	 * during the time window provided by a publisher for that particular last value.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimeout.png" alt="">
	 *
	 * @param throttlerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop checking
	 * others values from this sequence and emit the selecting item
	 * @param <U> the companion reified type
	 *
	 * @return a sampled {@link Flux} by last single item observed before a companion {@link Publisher} emits
	 */
	@SuppressWarnings("unchecked")
	public final <U> Flux<T> sampleTimeout(Function<? super T, ? extends Publisher<U>> throttlerFactory) {
		return new FluxThrottleTimeout<>(this, throttlerFactory, QueueSupplier.unbounded(PlatformDependent
				.XS_BUFFER_SIZE));
	}

	/**
	 * Emit the last value from this {@link Flux} only if there were no newer values emitted
	 * during the time window provided by a publisher for that particular last value. 
	 * <p>The provided {@literal maxConcurrency} will keep a bounded maximum of concurrent timeouts and drop any new 
	 * items until at least one timeout terminates.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/sampletimeoutm.png" alt="">
	 *
	 * @param throttlerFactory select a {@link Publisher} companion to signal onNext or onComplete to stop checking
	 * others values from this sequence and emit the selecting item
	 * @param maxConcurrency the maximum number of concurrent timeouts
	 * @param <U> the throttling type
	 *
	 * @return a sampled {@link Flux} by last single item observed before a companion {@link Publisher} emits
	 */
	public final <U> Flux<T> sampleTimeout(Function<? super T, ? extends Publisher<U>> throttlerFactory, long
			maxConcurrency) {
		if(maxConcurrency == Long.MAX_VALUE){
			return sampleTimeout(throttlerFactory);
		}
		return new FluxThrottleTimeout<>(this, throttlerFactory, QueueSupplier.get(maxConcurrency));
	}

	/**
	 * Accumulate this {@link Flux} values with an accumulator {@link BiFunction} and
	 * returns the intermediate results of this function.
	 * <p>
	 * Unlike {@link #scan(Object, BiFunction)}, this operator doesn't take an initial value
	 * but treats the first {@link Flux} value as initial value.
	 * <br>
	 * The accumulation works as follows:
	 * <pre><code>
	 * result[0] = accumulator(source[0], source[1])
	 * result[1] = accumulator(result[0], source[2])
	 * result[2] = accumulator(result[1], source[3])
	 * ...
	 * </code></pre>
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/accumulate.png" alt="">
	 *
	 * @param accumulator the accumulating {@link BiFunction}
	 *
	 * @return an accumulating {@link Flux}
	 *
	 */
	public final Flux<T> scan(BiFunction<T, T, T> accumulator) {
		return new FluxAccumulate<>(this, accumulator);
	}

	/**
	 * Aggregate this {@link Flux} values with the help of an accumulator {@link BiFunction}
	 * and emits the intermediate results.
	 * <p>
	 * The accumulation works as follows:
	 * <pre><code>
	 * result[0] = initialValue;
	 * result[1] = accumulator(result[0], source[0])
	 * result[2] = accumulator(result[1], source[1])
	 * result[3] = accumulator(result[2], source[2])
	 * ...
	 * </code></pre>
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/scan.png" alt="">
	 *
	 * @param initial the initial argument to pass to the reduce function
	 * @param accumulator the accumulating {@link BiFunction}
	 * @param <A> the accumulated type
	 *
	 * @return an accumulating {@link Flux} starting with initial state
	 *
	 */
	public final <A> Flux<A> scan(A initial, BiFunction<A, ? super T, A> accumulator) {
		return new FluxScan<>(this, initial, accumulator);
	}

	
	/**
	 * Expect and emit a single item from this {@link Flux} source or signal
	 * {@link java.util.NoSuchElementException} (or a default generated value) for empty source,
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/single.png" alt="">
	 *
	 * @return a {@link Mono} with the eventual single item or an error signal
	 */
	@SuppressWarnings("unchecked")
    public final Mono<T> single() {
	    if (this instanceof Callable) {
	        if (this instanceof Fuseable.ScalarCallable) {
                Fuseable.ScalarCallable<T> scalarCallable = (Fuseable.ScalarCallable<T>) this;

                T v = scalarCallable.call();
                if (v == null) {
                    return Mono.error(new NoSuchElementException("Source was a (constant) empty"));
                }
                return Mono.just(v);
	        }
	        return new MonoCallable<>((Callable<T>)this);
	    }
		return new MonoSingle<>(this);
	}

	/**
	 *
	 * Expect and emit a single item from this {@link Flux} source or signal
	 * {@link java.util.NoSuchElementException} (or a default generated value) for empty source,
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/singleordefault.png" alt="">
	 * @param defaultSupplier a {@link Supplier} of a single fallback item if this {@link Flux} is empty
	 *
	 * @return a {@link Mono} with the eventual single item or a supplied default value
	 */
	@SuppressWarnings("unchecked")
    public final Mono<T> singleOrDefault(Supplier<? extends T> defaultSupplier) {
        if (this instanceof Callable) {
            if (this instanceof Fuseable.ScalarCallable) {
                Fuseable.ScalarCallable<T> scalarCallable = (Fuseable.ScalarCallable<T>) this;

                T v = scalarCallable.call();
                if (v == null) {
                    return new MonoSupplier<>(defaultSupplier);
                }
                return Mono.just(v);
            }
            return new MonoCallable<>((Callable<T>)this);
        }
		return new MonoSingle<>(this, defaultSupplier);
	}

	/**
	 * Expect and emit a zero or single item from this {@link Flux} source or
	 * {@link IndexOutOfBoundsException} for a multi-item source.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/singleorempty.png" alt="">
	 *
	 * @return a {@link Mono} with the eventual single item or no item
	 */
	@SuppressWarnings("unchecked")
    public final Mono<T> singleOrEmpty() {
	    if (this instanceof Callable) {
	        return convertToMono((Callable<T>)this);
	    }
		return new MonoSingle<>(this, MonoSingle.completeOnEmptySequence());
	}

	/**
	 * Skip next the specified number of elements from this {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skip.png" alt="">
	 *
	 * @param skipped the number of times to drop
	 *
	 * @return a dropping {@link Flux} until the specified skipped number of elements
	 */
	public final Flux<T> skip(long skipped) {
		if (skipped > 0) {
			return new FluxSkip<>(this, skipped);
		}
		else {
			return this;
		}
	}

	/**
	 * Skip elements from this {@link Flux} for the given time period.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skiptime.png" alt="">
	 *
	 * @param timespan the time window to exclude next signals
	 *
	 * @return a dropping {@link Flux} until the end of the given timespan
	 */
	public final Flux<T> skip(Duration timespan) {
		if(!timespan.isZero()) {
			TimedScheduler timer = Objects.requireNonNull(getTimer(), "Timer can't be found, try assigning an environment to the flux");
			return skipUntil(Mono.delay(timespan, timer));
		}
		else{
			return this;
		}
	}

	/**
	 * Skip the last specified number of elements from this {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skiplast.png" alt="">
	 *
	 * @param n the number of elements to ignore before completion
	 *
	 * @return a dropping {@link Flux} for the specified skipped number of elements before termination
	 *
	 */
	public final Flux<T> skipLast(int n) {
		return new FluxSkipLast<>(this, n);
	}

	/**
	 * Skip values from this {@link Flux} until a specified {@link Publisher} signals
	 * an onNext or onComplete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skipuntil.png" alt="">
	 *
	 * @param other the {@link Publisher} companion to coordinate with to stop skipping
	 *
	 * @return a dropping {@link Flux} until the other {@link Publisher} emits
	 *
	 */
	public final Flux<T> skipUntil(Publisher<?> other) {
		return new FluxSkipUntil<>(this, other);
	}

	/**
	 * Skips values from this {@link Flux} while a {@link Predicate} returns true for the value.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/skipwhile.png" alt="">
	 *
	 * @param skipPredicate the {@link Predicate} evaluating to true to keep skipping.
	 *
	 * @return a dropping {@link Flux} while the {@link Predicate} matches
	 */
	public final Flux<T> skipWhile(Predicate<? super T> skipPredicate) {
		return new FluxSkipWhile<>(this, skipPredicate);
	}

	/**
	 * Prepend the given {@link Iterable} before this {@link Flux} sequence.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwithi.png" alt="">
	 *
	 * @param iterable the sequence of values to start the sequence with
	 * 
	 * @return a prefixed {@link Flux} with given {@link Iterable}
	 */
	public final Flux<T> startWith(Iterable<? extends T> iterable) {
		return startWith(fromIterable(iterable));
	}

	/**
	 * Prepend the given values before this {@link Flux} sequence.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwithv.png" alt="">
	 *
	 * @param values the array of values to start with
	 * 
	 * @return a prefixed {@link Flux} with given values
	 */
	@SafeVarargs
	@SuppressWarnings("varargs")
	public final Flux<T> startWith(T... values) {
		return startWith(just(values));
	}

	/**
	 * Prepend the given {@link Publisher} sequence before this {@link Flux} sequence.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/startwith.png" alt="">
	 *
	 * @param publisher the Publisher whose values to prepend
	 * 
	 * @return a prefixed {@link Flux} with given {@link Publisher} sequence
	 */
	public final Flux<T> startWith(Publisher<? extends T> publisher) {
		if (publisher == null) {
			return this;
		}
		if (this instanceof FluxConcatArray) {
			FluxConcatArray<T> fluxConcatArray = (FluxConcatArray<T>) this;
			return fluxConcatArray.concatAdditionalSourceFirst(publisher);
		}
		return concat(publisher, this);
	}

	/**
	 * Transform this {@link Flux} into a lazy {@link Stream} blocking on next calls.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tostream.png" alt="">
	 *
	 * @return a {@link Stream} of unknown size with onClose attached to {@link Subscription#cancel()}
	 */
	public Stream<T> stream() {
		return stream(getPrefetchOrDefault(Integer.MAX_VALUE));
	}

	/**
	 * Transform this {@link Flux} into a lazy {@link Stream} blocking on next calls.
	 *
	 * @param batchSize the bounded capacity to produce to this {@link Flux} or {@code Integer.MAX_VALUE} for unbounded
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tostream.png" alt="">
	 *
	 * @return a {@link Stream} of unknown size with onClose attached to {@link Subscription#cancel()}
	 */
	public Stream<T> stream(int batchSize) {
		final Supplier<Queue<T>> provider;
		provider = QueueSupplier.get(batchSize);
		return new BlockingIterable<>(this, batchSize, provider).stream();
	}

	/**
	 * Start the chain and request unbounded demand.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/unbounded.png" alt="">
	 * <p>
	 *
	 * @return a {@link Cancellation} task to execute to dispose and cancel the underlying {@link Subscription}
	 */
	public final Cancellation subscribe() {
		return subscribe(null, null, null);
	}


	/**
	 * Subscribe a {@link Consumer} to this {@link Flux} that will consume all the
	 * sequence.  If {@link Flux#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(java.util.function.Consumer)}
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribe.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 *
	 * @return a new {@link Cancellation} to dispose the {@link Subscription}
	 */
	public final Cancellation subscribe(Consumer<? super T> consumer) {
		return subscribe(consumer, null, null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will consume all the
	 * sequence.  If {@link Flux#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see
	 * {@link #doOnNext(java.util.function.Consumer)} and {@link #doOnError(java.util.function.Consumer)}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribeerror.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each next signal
	 * @param errorConsumer the consumer to invoke on error signal
	 *
	 * @return a new {@link Cancellation} to dispose the {@link Subscription}
	 */
	public final Cancellation subscribe(Consumer<? super T> consumer, Consumer<? super Throwable> errorConsumer) {
		return subscribe(consumer, errorConsumer, null);
	}

	/**
	 * Subscribe {@link Consumer} to this {@link Flux} that will consume all the
	 * sequence.  If {@link Flux#getCapacity()} returns an integer value, the {@link Subscriber} will use it as a
	 * prefetch strategy: first request N, then when 25% of N is left to be received on onNext, request N x 0.75. <p>
	 * For a passive version that observe and forward incoming data see {@link #doOnNext(java.util.function.Consumer)},
	 * {@link #doOnError(java.util.function.Consumer)} and {@link #doOnComplete(Runnable)},
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribecomplete.png" alt="">
	 *
	 * @param consumer the consumer to invoke on each value
	 * @param errorConsumer the consumer to invoke on error signal
	 * @param completeConsumer the consumer to invoke on complete signal
	 *
	 * @return a new {@link Cancellation} to dispose the {@link Subscription}
	 */
	public final Cancellation subscribe(Consumer<? super T> consumer,
			Consumer<? super Throwable> errorConsumer,
			Runnable completeConsumer) {

		long c = Math.min(Integer.MAX_VALUE, getCapacity());

		LambdaSubscriber<T> consumerAction;
		if (c == Integer.MAX_VALUE || c == -1L) {
			consumerAction = new LambdaSubscriber<>(consumer, errorConsumer, completeConsumer);
		}
		else {
			consumerAction = Subscribers.bounded((int) c, consumer, errorConsumer, completeConsumer);
		}

		subscribe(consumerAction);
		return consumerAction;
	}

	/**
	 * Run subscribe, onSubscribe and request on a supplied
	 * {@link Consumer} {@link Runnable} factory like {@link Computations}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribeon.png" alt="">
	 * <p>
	 * Typically used for slow publisher e.g., blocking IO, fast consumer(s) scenarios.
	 * It naturally combines with {@link Computations#concurrent} which implements work-queue thread dispatching.
	 *
	 * <p>
	 * {@code flux.subscribeOn(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param scheduler a checked {@link reactor.core.scheduler.Scheduler.Worker} factory
	 *
	 * @return a {@link Flux} requesting asynchronously
	 */
	public final Flux<T> subscribeOn(Scheduler scheduler) {
		if (this instanceof Fuseable.ScalarCallable) {
            @SuppressWarnings("unchecked")
            T value = ((Fuseable.ScalarCallable<T>)this).call();
            return new FluxSubscribeOnValue<>(value, scheduler);
		}
		return new FluxSubscribeOn<>(this, scheduler);
	}

	/**
	 * Run subscribe, onSubscribe and request on a supplied {@link ExecutorService}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/subscribeon.png" alt="">
	 * <p>
	 * {@code flux.subscribeOn(ForkJoinPool.commonPool()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param executorService an {@link ExecutorService}
	 *
	 * @return a {@link Flux} requesting asynchronously
	 */
	public final Flux<T> subscribeOn(ExecutorService executorService) {
		return subscribeOn(new ExecutorServiceScheduler(executorService));
	}

	/**
	 *
	 * A chaining {@link Publisher#subscribe(Subscriber)} alternative to inline composition type conversion to a hot
	 * emitter (e.g. {@link FluxProcessor} or {@link MonoProcessor}).
	 *
	 * {@code flux.subscribeWith(WorkQueueProcessor.create()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param subscriber the {@link Subscriber} to subscribe and return
	 * @param <E> the reified type from the input/output subscriber
	 *
	 * @return the passed {@link Subscriber}
	 */
	public final <E extends Subscriber<? super T>> E subscribeWith(E subscriber) {
		subscribe(subscriber);
		return subscriber;
	}

	/**
	 * Switch to a new {@link Publisher} generated via a {@link Function} whenever this {@link Flux} produces an item.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchmap.png" alt="">
	 *
	 * @param fn the transformation function
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return an alternating {@link Flux} on source onNext
	 *
	 */
	public final <V> Flux<V> switchMap(Function<? super T, Publisher<? extends V>> fn) {
		return switchMap(fn, getPrefetchOrDefault(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Switch to a new {@link Publisher} generated via a {@link Function} whenever this {@link Flux} produces an item.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchmap.png" alt="">
	 *
	 * @param fn the transformation function
	 * @param prefetch the produced demand for inner sources
	 *
	 * @param <V> the type of the return value of the transformation function
	 *
	 * @return an alternating {@link Flux} on source onNext
	 *
	 */
	public final <V> Flux<V> switchMap(Function<? super T, Publisher<? extends V>> fn, int prefetch) {
		return new FluxSwitchMap<>(this, fn, QueueSupplier.get(prefetch), prefetch);
	}

	/**
	 * Provide an alternative if this sequence is completed without any data
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchifempty.png" alt="">
	 * <p>
	 * @param alternate the alternate publisher if this sequence is empty
	 *
	 * @return an alternating {@link Flux} on source onComplete without elements
	 */
	public final Flux<T> switchIfEmpty(Publisher<? extends T> alternate) {
		return new FluxSwitchIfEmpty<>(this, alternate);
	}

	/**
	 * Subscribe to the given fallback {@link Publisher} if an error is observed on this {@link Flux}
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/switchonerror.png" alt="">
	 * <p>
	 *
	 * @param fallback the alternate {@link Publisher}
	 *
	 * @return an alternating {@link Flux} on source onError
	 */
	public final Flux<T> switchOnError(Publisher<? extends T> fallback) {
		return onErrorResumeWith(t -> fallback);
	}

	/**
	 * Take only the first N values from this {@link Flux}.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/take.png" alt="">
	 * <p>
	 * If N is zero, the {@link Subscriber} gets completed if this {@link Flux} completes, signals an error or
	 * signals its first value (which is not not relayed though).
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/take0.png" alt="">
	 * @param n the number of items to emit from this {@link Flux}
	 *
	 * @return a size limited {@link Flux}
	 */
	public final Flux<T> take(long n) {
		if (this instanceof Fuseable) {
			return new FluxTakeFuseable<>(this, n);
		}
		return new FluxTake<>(this, n);
	}

	/**
	 * Relay values from this {@link Flux} until the given time period elapses.
	 * <p>
	 * If the time period is zero, the {@link Subscriber} gets completed if this {@link Flux} completes, signals an
	 * error or
	 * signals its first value (which is not not relayed though).
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/taketime.png" alt="">
	 *
	 * @param timespan the time window of items to emit from this {@link Flux}
	 *
	 * @return a time limited {@link Flux}
	 */
	public final Flux<T> take(Duration timespan) {
		if (!timespan.isZero()) {
			TimedScheduler timer = Objects.requireNonNull(getTimer(), "Timer can't be found, try assigning an environment to the flux");
			return takeUntil(Mono.delay(timespan, timer));
		}
		else {
			return take(0);
		}
	}

	/**
	 * Emit the last N values this {@link Flux} emitted before its completion.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takelast.png" alt="">
	 *
	 * @param n the number of items from this {@link Flux} to retain and emit on onComplete
	 *
	 * @return a terminating {@link Flux} sub-sequence
	 *
	 */
	public final Flux<T> takeLast(int n) {
		if(n == 1){
			return new FluxTakeLastOne<>(this);
		}
		return new FluxTakeLast<>(this, n);
	}

	/**
	 * Relay values from this {@link Flux} until the given {@link Publisher} emits.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takeuntil.png" alt="">
	 *
	 * @param other the {@link Publisher} to signal when to stop replaying signal from this {@link Flux}
	 *
	 * @return an eventually limited {@link Flux}
	 *
	 */
	public final Flux<T> takeUntil(Publisher<?> other) {
		return new FluxTakeUntil<>(this, other);
	}

	/**
	 * Relay values from this {@link Flux} until the given {@link Predicate} matches.
	 * Unlike {@link #takeWhile}, this will include the matched data.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takeuntil.png" alt="">
	 *
	 * @param predicate the {@link Predicate} to signal when to stop replaying signal
	 * from this {@link Flux}
	 *
	 * @return an eventually limited {@link Flux}
	 *
	 */
	public final Flux<T> takeUntil(Predicate<? super T> predicate) {
		return new FluxTakeUntilPredicate<>(this, predicate);
	}

	/**
	 * Relay values while a predicate returns
	 * {@literal FALSE} for the values (checked before each value is delivered).
	 * Unlike {@link #takeUntil}, this will exclude the matched data.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/takewhile.png" alt="">
	 *
	 * @param continuePredicate the {@link Predicate} invoked each onNext returning {@literal FALSE} to terminate
	 *
	 * @return an eventually limited {@link Flux}
	 */
	public final Flux<T> takeWhile(Predicate<? super T> continuePredicate) {
		return new FluxTakeWhile<>(this, continuePredicate);
	}

	/**
	 * Return a {@code Mono<Void>} that completes when this {@link Flux} completes.
	 * This will actively ignore the sequence and only replay completion or error signals.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethen.png" alt="">
	 * <p>
	 * @return a new {@link Mono}
	 */
	@SuppressWarnings("unchecked")
	public final Mono<Void> then() {
		return (Mono<Void>) new MonoIgnoreThen<>(this);
	}

	/**
	 * Return a {@link Flux} that emits the completion of the supplied {@link Publisher}
	 * when this {@link Flux} onComplete
	 * or onError. If an error occur, append after the supplied {@link Publisher} is terminated.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethens.png"
	 * alt="">
	 *
	 * @param other a {@link Publisher} to emit from after termination
	 *
	 * @return a new {@link Flux} emitting eventually from the supplied {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final Mono<Void> then(Publisher<Void> other) {
		return MonoSource.wrap(concat(then(), other));
	}

	/**
	 * Return a {@link Flux} that emits the completion of the supplied {@link Publisher} when this {@link Flux} onComplete
	 * or onError. If an error occur, append after the supplied {@link Publisher} is terminated.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethens.png"
	 * alt="">
	 *
	 * @param afterSupplier a {@link Supplier} of {@link Publisher} to emit from after termination
	 *
	 * @return a new {@link Flux} emitting eventually from the supplied {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final Mono<Void> then(Supplier<? extends Publisher<Void>> afterSupplier) {
		return then(defer(afterSupplier));
	}

	/**
	 * Return a {@link Flux} that emits the sequence of the supplied {@link Publisher} when this {@link Flux} onComplete
	 * or onError. If an error occur, append after the supplied {@link Publisher} is terminated.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethens.png"
	 * alt="">
	 *
	 * @param other a {@link Publisher} to emit from after termination
	 * @param <V> the supplied produced type
	 *
	 * @return a new {@link Flux} emitting eventually from the supplied {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final <V> Flux<V> thenMany(Publisher<V> other) {
		return (Flux<V>)concat(ignoreElements(), other);
	}

	/**
	 * Return a {@link Flux} that emits the sequence of the supplied {@link Publisher} when this {@link Flux} onComplete
	 * or onError. If an error occur, append after the supplied {@link Publisher} is terminated.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/ignorethens.png"
	 * alt="">
	 *
	 * @param afterSupplier a {@link Supplier} of {@link Publisher} to emit from after termination
	 * @param <V> the supplied produced type
	 *
	 * @return a new {@link Flux} emitting eventually from the supplied {@link Publisher}
	 */
	@SuppressWarnings("unchecked")
	public final <V> Flux<V> thenMany(Supplier<? extends Publisher<V>> afterSupplier) {
		return thenMany(defer(afterSupplier));
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} error in case a per-item period in milliseconds fires
	 * before the next item arrives from this {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttime.png" alt="">
	 *
	 * @param timeout the timeout in milliseconds between two signals from this {@link Flux}
	 *
	 * @return a per-item expirable {@link Flux}
	 */
	public final Flux<T> timeout(long timeout) {
		return timeout(Duration.ofMillis(timeout), null);
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a per-item period fires before the
	 * next item arrives from this {@link Flux}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttime.png" alt="">
	 *
	 * @param timeout the timeout between two signals from this {@link Flux}
	 *
	 * @return a per-item expirable {@link Flux}
	 */
	public final Flux<T> timeout(Duration timeout) {
		return timeout(timeout, null);
	}

	/**
	 * Switch to a fallback {@link Publisher} in case a per-item period
	 * fires before the next item arrives from this {@link Flux}.
	 *
	 * <p> If the given {@link Publisher} is null, signal a {@link java.util.concurrent.TimeoutException}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeouttimefallback.png" alt="">
	 *
	 * @param timeout the timeout between two signals from this {@link Flux}
	 * @param fallback the fallback {@link Publisher} to subscribe when a timeout occurs
	 *
	 * @return a per-item expirable {@link Flux} with a fallback {@link Publisher}
	 */
	public final Flux<T> timeout(Duration timeout, Publisher<? extends T> fallback) {
		final TimedScheduler timer = Objects.requireNonNull(getTimer(), "Cannot use default timer as no environment has been " +
				"provided to this " + "Stream");

		final Mono<Long> _timer = Mono.delay(timeout, timer).otherwiseReturn(0L);
		final Function<T, Publisher<Long>> rest = o -> _timer;

		if(fallback == null) {
			return timeout(_timer, rest);
		}
		return timeout(_timer, rest, fallback);
	}


	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a first item from this {@link Flux} has
	 * not been emitted before the given {@link Publisher} emits.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutfirst.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Flux}
	 *
	 * @param <U> the type of the timeout Publisher
	 * 
	 * @return an expirable {@link Flux} if the first item does not come before a {@link Publisher} signal
	 *
	 */
	public final <U> Flux<T> timeout(Publisher<U> firstTimeout) {
		return timeout(firstTimeout, t -> never());
	}

	/**
	 * Signal a {@link java.util.concurrent.TimeoutException} in case a first item from this {@link Flux} has
	 * not been emitted before the given {@link Publisher} emits. The following items will be individually timed via
	 * the factory provided {@link Publisher}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutall.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Flux}
	 * @param nextTimeoutFactory the timeout {@link Publisher} factory for each next item
	 *
	 * @param <U> the type of the elements of the first timeout Publisher
	 * @param <V> the type of the elements of the subsequent timeout Publishers
	 * 
	 * @return a first then per-item expirable {@link Flux}
	 *
	 */
	public final <U, V> Flux<T> timeout(Publisher<U> firstTimeout,
			Function<? super T, ? extends Publisher<V>> nextTimeoutFactory) {
		return new FluxTimeout<>(this, firstTimeout, nextTimeoutFactory);
	}

	/**
	 * Switch to a fallback {@link Publisher} in case a first item from this {@link Flux} has
	 * not been emitted before the given {@link Publisher} emits. The following items will be individually timed via
	 * the factory provided {@link Publisher}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timeoutallfallback.png" alt="">
	 *
	 * @param firstTimeout the timeout {@link Publisher} that must not emit before the first signal from this {@link Flux}
	 * @param nextTimeoutFactory the timeout {@link Publisher} factory for each next item
	 * @param fallback the fallback {@link Publisher} to subscribe when a timeout occurs
	 *
	 * @param <U> the type of the elements of the first timeout Publisher
	 * @param <V> the type of the elements of the subsequent timeout Publishers
	 * 
	 * @return a first then per-item expirable {@link Flux} with a fallback {@link Publisher}
	 *
	 */
	public final <U, V> Flux<T> timeout(Publisher<U> firstTimeout,
			Function<? super T, ? extends Publisher<V>> nextTimeoutFactory, Publisher<? extends T>
			fallback) {
		return new FluxTimeout<>(this, firstTimeout, nextTimeoutFactory, fallback);
	}

	/**
	 * Emit a {@link reactor.core.tuple.Tuple2} pair of T1 {@link Long} current system time in
	 * millis and T2 {@code T} associated data for each item from this {@link Flux}
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/timestamp.png" alt="">
	 *
	 * @return a timestamped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public final Flux<Tuple2<Long, T>> timestamp() {
		return map(TIMESTAMP_OPERATOR);
	}

	/**
	 * Transform this {@link Flux} into a lazy {@link Iterable} blocking on next calls.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterable.png" alt="">
	 * <p>
	 *
	 * @return a blocking {@link Iterable}
	 */
	public final Iterable<T> toIterable() {
		return toIterable(getPrefetchOrDefault(Integer.MAX_VALUE));
	}

	/**
	 * Transform this {@link Flux} into a lazy {@link Iterable} blocking on next calls.
	 *
	 * @param batchSize the bounded capacity to produce to this {@link Flux} or {@code Integer.MAX_VALUE} for unbounded
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterablen.png" alt="">
	 * <p>
	 *
	 * @return a blocking {@link Iterable}
	 */
	public final Iterable<T> toIterable(long batchSize) {
		return toIterable(batchSize, null);
	}

	/**
	 * Transform this {@link Flux} into a lazy {@link Iterable} blocking on next calls.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/toiterablen.png" alt="">
	 * 
	 * @param batchSize the bounded capacity to produce to this {@link Flux} or {@code Integer.MAX_VALUE} for unbounded
	 * @param queueProvider the supplier of the queue implementation to be used for transferring elements
	 * across threads.
	 * 
	 * @return a blocking {@link Iterable}
	 */
	public final Iterable<T> toIterable(long batchSize, Supplier<Queue<T>> queueProvider) {
		final Supplier<Queue<T>> provider;
		if(queueProvider == null){
			provider = QueueSupplier.get(batchSize);
		}
		else{
			provider = queueProvider;
		}
		return new BlockingIterable<>(this, batchSize, provider);
	}

	/**
	 * Accumulate this {@link Flux} sequence in a {@link List} that is emitted to the returned {@link Mono} on
	 * onComplete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tolist.png" alt="">
	 *
	 * @return a {@link Mono} of all values from this {@link Flux}
	 *
	 *
	 */
	@SuppressWarnings("unchecked")
	public final Mono<List<T>> toList() {
        if (this instanceof Callable) {
            if (this instanceof Fuseable.ScalarCallable) {
                Fuseable.ScalarCallable<T> scalarCallable = (Fuseable.ScalarCallable<T>) this;

                T v = scalarCallable.call();
                if (v == null) {
                    return new MonoSupplier<>(LIST_SUPPLIER);
                }
                return Mono.just(v).map(u -> {
                    List<T> list = (List<T>)LIST_SUPPLIER.get();
                    list.add(u);
                    return list;
                });

            }
            return new MonoCallable<>((Callable<T>)this).map(u -> {
                List<T> list = (List<T>)LIST_SUPPLIER.get();
                list.add(u);
                return list;
            });
        }
        return new MonoBufferAll<>(this, LIST_SUPPLIER);
	}

	/**
	 * Accumulate and sort this {@link Flux} sequence in a {@link List} that is emitted to the returned {@link Mono} on
	 * onComplete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tosortedlist.png" alt="">
	 *
	 * @return a {@link Mono} of all sorted values from this {@link Flux}
	 *
	 */
	public final Mono<List<T>> toSortedList() {
		return toSortedList(null);
	}

	/**
	 * Accumulate and sort using the given comparator this
	 * {@link Flux} sequence in a {@link List} that is emitted to the returned {@link Mono} on
	 * onComplete.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tosortedlist.png" alt="">
	 *
	 * @param comparator a {@link Comparator} to sort the items of this sequences
	 *
	 * @return a {@link Mono} of all sorted values from this {@link Flux}
	 *
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public final Mono<List<T>> toSortedList(Comparator<? super T> comparator) {
		return toList().map(list -> {
		    // Note: this assumes the list emitted by buffer() is mutable
		    if (comparator != null) {
		        Collections.sort(list, comparator);
		    } else {
		        Collections.sort((List<Comparable>)list);
		    }
		    return list;
		});
	}

	/**
	 * Convert all this
	 * {@link Flux} sequence into a hashed map where the key is extracted by the given {@link Function} and the
	 * value will be the most recent emitted item for this key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param <K> the key extracted from each value of this Flux instance
	 * 
	 * @return a {@link Mono} of all last matched key-values from this {@link Flux}
	 *
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, T>> toMap(Function<? super T, ? extends K> keyExtractor) {
		return toMap(keyExtractor, identityFunction());
	}

	/**
	 * Convert all this {@link Flux} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * the most recent extracted item for this key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * 
	 * @param <K> the key extracted from each value of this Flux instance
	 * @param <V> the value extracted from each value of this Flux instance
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Flux}
	 *
	 */
	public final <K, V> Mono<Map<K, V>> toMap(Function<? super T, ? extends K> keyExtractor,
			Function<? super T, ? extends V> valueExtractor) {
		return toMap(keyExtractor, valueExtractor, () -> new HashMap<>());
	}

	/**
	 * Convert all this {@link Flux} sequence into a supplied map where the key is extracted by the given function and the value will
	 * be the most recent extracted item for this key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @param <K> the key extracted from each value of this Flux instance
	 * @param <V> the value extracted from each value of this Flux instance
	 *
	 * @return a {@link Mono} of all last matched key-values from this {@link Flux}
	 *
	 */
	public final <K, V> Mono<Map<K, V>> toMap(
			final Function<? super T, ? extends K> keyExtractor,
			final Function<? super T, ? extends V> valueExtractor,
			Supplier<Map<K, V>> mapSupplier) {
		Objects.requireNonNull(keyExtractor, "Key extractor is null");
		Objects.requireNonNull(valueExtractor, "Value extractor is null");
		Objects.requireNonNull(mapSupplier, "Map supplier is null");
		return collect(mapSupplier, (m, d) -> m.put(keyExtractor.apply(d), valueExtractor.apply(d)));
	}

	/**
	 * Convert this {@link Flux} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the emitted item for this key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 *
	 * @param <K> the key extracted from each value of this Flux instance
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Flux}
	 *
	 */
	@SuppressWarnings("unchecked")
	public final <K> Mono<Map<K, Collection<T>>> toMultimap(Function<? super T, ? extends K> keyExtractor) {
		return toMultimap(keyExtractor, identityFunction());
	}

	/**
	 * Convert this {@link Flux} sequence into a hashed map where the key is extracted by the given function and the value will be
	 * all the extracted items for this key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 *
	 * @param <K> the key extracted from each value of this Flux instance
	 * @param <V> the value extracted from each value of this Flux instance
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Flux}
	 *
	 */
	public final <K, V> Mono<Map<K, Collection<V>>> toMultimap(Function<? super T, ? extends K> keyExtractor,
			Function<? super T, ? extends V> valueExtractor) {
		return toMultimap(keyExtractor, valueExtractor, () -> new HashMap<>());
	}

	/**
	 * Convert this {@link Flux} sequence into a supplied map where the key is extracted by the given function and the value will
	 * be all the extracted items for this key.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/tomultimap.png" alt="">
	 *
	 * @param keyExtractor a {@link Function} to route items into a keyed {@link Collection}
	 * @param valueExtractor a {@link Function} to select the data to store from each item
	 * @param mapSupplier a {@link Map} factory called for each {@link Subscriber}
	 *
	 * @param <K> the key extracted from each value of this Flux instance
	 * @param <V> the value extracted from each value of this Flux instance
	 *
	 * @return a {@link Mono} of all matched key-values from this {@link Flux}
	 *
	 */
	public final <K, V> Mono<Map<K, Collection<V>>> toMultimap(
			final Function<? super T, ? extends K> keyExtractor,
			final Function<? super T, ? extends V> valueExtractor,
			Supplier<Map<K, Collection<V>>> mapSupplier) {
		Objects.requireNonNull(keyExtractor, "Key extractor is null");
		Objects.requireNonNull(valueExtractor, "Value extractor is null");
		Objects.requireNonNull(mapSupplier, "Map supplier is null");
		return collect(mapSupplier, (m, d) -> {
			K key = keyExtractor.apply(d);
			Collection<V> values = m.get(key);
			if(values == null){
				values = new ArrayList<>();
				m.put(key, values);
			}
			values.add(valueExtractor.apply(d));
		});
	}

	/**
	 * Hint {@link Subscriber} to this {@link Flux} a preferred available capacity should be used.
	 * {@link #toIterable()} can for instance use introspect this value to supply an appropriate queueing strategy.
	 *
	 * @param capacity the maximum capacity (in flight onNext) the return {@link Publisher} should expose
	 *
	 * @return a bounded {@link Flux}
	 */
	public Flux<T> useCapacity(long capacity) {
		if (capacity == getCapacity()) {
			return this;
		}
		return FluxConfig.withCapacity(this, capacity);
	}

	/**
	 * Configure an arbitrary name for later introspection.
	 *
	 * @param name arbitrary {@link Flux} name
	 *
	 * @return a configured flux
	 */
	public Flux<T> useName(String name) {
		return FluxConfig.withName(this, name);

	}

	/**
	 * Configure a {@link TimedScheduler} that can be used by timed operators downstream.
	 *
	 * @param timer the timer
	 *
	 * @return a configured flux
	 */
	public Flux<T> useTimer(TimedScheduler timer) {
		return FluxConfig.withTimer(this, timer);

	}

	/**
	 * Split this {@link Flux} sequence into multiple {@link Flux} delimited by the given {@code maxSize}
	 * count and starting from
	 * the first item.
	 * Each {@link Flux} bucket will onComplete after {@code maxSize} items have been routed.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param maxSize the maximum routed items before emitting onComplete per {@link Flux} bucket
	 *
	 * @return a windowing {@link Flux} of sized {@link Flux} buckets
	 */
	public final Flux<Flux<T>> window(int maxSize) {
		return new FluxWindow<>(this, maxSize, QueueSupplier.get(maxSize));
	}

	/**
	 * Split this {@link Flux} sequence into multiple {@link Flux} delimited by the given {@code skip}
	 * count,
	 * starting from
	 * the first item.
	 * Each {@link Flux} bucket will onComplete after {@code maxSize} items have been routed.
	 *
	 * <p>
	 * When skip > maxSize : dropping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskip.png" alt="">
	 * <p>
	 * When maxSize < skip : overlapping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskipover.png" alt="">
	 * <p>
	 * When skip == maxSize : exact windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param maxSize the maximum routed items per {@link Flux}
	 * @param skip the number of items to count before emitting a new bucket {@link Flux}
	 *
	 * @return a windowing {@link Flux} of sized {@link Flux} buckets every skip count
	 */
	public final Flux<Flux<T>> window(int maxSize, int skip) {
		return new FluxWindow<>(this,
				maxSize,
				skip,
				QueueSupplier.xs(),
				QueueSupplier.xs());
	}

	/**
	 * Split this {@link Flux} sequence into continuous, non-overlapping windows
	 * where the window boundary is signalled by another {@link Publisher}
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowboundary.png" alt="">
	 *
	 * @param boundary a {@link Publisher} to emit any item for a split signal and complete to terminate
	 *
	 * @return a windowing {@link Flux} delimiting its sub-sequences by a given {@link Publisher}
	 */
	public final Flux<Flux<T>> window(Publisher<?> boundary) {
		return new FluxWindowBoundary<>(this,
				boundary,
				QueueSupplier.unbounded(PlatformDependent.XS_BUFFER_SIZE),
				QueueSupplier.unbounded(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Split this {@link Flux} sequence into potentially overlapping windows controlled by items of a
	 * start {@link Publisher} and end {@link Publisher} derived from the start values.
	 *
	 * <p>
	 * When Open signal is strictly not overlapping Close signal : dropping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowopenclose.png" alt="">
	 * <p>
	 * When Open signal is strictly more frequent than Close signal : overlapping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowopencloseover.png" alt="">
	 * <p>
	 * When Open signal is exactly coordinated with Close signal : exact windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowboundary.png" alt="">
	 *
	 * @param bucketOpening a {@link Publisher} to emit any item for a split signal and complete to terminate
	 * @param closeSelector a {@link Function} given an opening signal and returning a {@link Publisher} that
	 * emits to complete the window
	 * 
	 * @param <U> the type of the sequence opening windows
	 * @param <V> the type of the sequence closing windows opened by the bucketOpening Publisher's elements
	 * 
	 * @return a windowing {@link Flux} delimiting its sub-sequences by a given {@link Publisher} and lasting until
	 * a selected {@link Publisher} emits
	 */
	public final <U, V> Flux<Flux<T>> window(Publisher<U> bucketOpening,
			final Function<? super U, ? extends Publisher<V>> closeSelector) {

		long c = getCapacity();
		c = c == -1L ? Long.MAX_VALUE : c;
		/*if(c > 1 && c < 10_000_000){
			return new StreamWindowBeginEnd<>(this,
					bucketOpening,
					boundarySupplier,
					QueueSupplier.get(c),
					(int)c);
		}*/

		return new FluxWindowStartEnd<>(this,
				bucketOpening,
				closeSelector,
				QueueSupplier.unbounded(PlatformDependent.XS_BUFFER_SIZE),
				QueueSupplier.unbounded(PlatformDependent.XS_BUFFER_SIZE));
	}

	/**
	 * Split this {@link Flux} sequence into continuous, non-overlapping windows delimited by a given period.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowtimespan.png" alt="">
	 *
	 * @param timespan the duration in milliseconds to delimit {@link Flux} windows
	 *
	 * @return a windowing {@link Flux} of timed {@link Flux} buckets
	 */
	public final Flux<Flux<T>> window(long timespan) {
		TimedScheduler t = getTimer();
		return window(interval(timespan, t));
	}

	/**
	 * Split this {@link Flux} sequence into continuous, non-overlapping windows delimited by a given period.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowtimespan.png" alt="">
	 *
	 * @param timespan the duration to delimit {@link Flux} windows
	 *
	 * @return a windowing {@link Flux} of timed {@link Flux} buckets
	 */
	public final Flux<Flux<T>> window(Duration timespan) {
		return window(timespan.toMillis());
	}

	/**
	 * Split this {@link Flux} sequence into multiple {@link Flux} delimited by the given {@code timeshift}
	 * period, starting from the first item.
	 * Each {@link Flux} bucket will onComplete after {@code timespan} period has elpased.
	 *
	 * <p>
	 * When timeshift > timespan : dropping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskip.png" alt="">
	 * <p>
	 * When timeshift < timespan : overlapping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskipover.png" alt="">
	 * <p>
	 * When timeshift == timespan : exact windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param timespan the maximum {@link Flux} window duration in milliseconds
	 * @param timeshift the period of time in milliseconds to create new {@link Flux} windows
	 *
	 * @return a windowing
	 * {@link Flux} of {@link Flux} buckets delimited by an opening {@link Publisher} and a selected closing {@link Publisher}
	 *
	 */
	public final Flux<Flux<T>> window(long timespan, long timeshift) {
		if (timeshift == timespan) {
			return window(timespan);
		}

		final TimedScheduler timer = getTimer();

		return window(interval(0L, timeshift, timer), aLong -> Mono.delay(timespan, timer));
	}

	/**
	 * Split this {@link Flux} sequence into multiple {@link Flux} delimited by the given {@code timeshift}
	 * period, starting from the first item.
	 * Each {@link Flux} bucket will onComplete after {@code timespan} period has elpased.
	 *
	 * <p>
	 * When timeshift > timespan : dropping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskip.png" alt="">
	 * <p>
	 * When timeshift < timespan : overlapping windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizeskipover.png" alt="">
	 * <p>
	 * When timeshift == timespan : exact windows
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsize.png" alt="">
	 *
	 * @param timespan the maximum {@link Flux} window duration
	 * @param timeshift the period of time to create new {@link Flux} windows
	 *
	 * @return a windowing
	 * {@link Flux} of {@link Flux} buckets delimited by an opening {@link Publisher} and a selected closing {@link Publisher}
	 *
	 */
	public final Flux<Flux<T>> window(Duration timespan, Duration timeshift) {
		return window(timespan.toMillis(), timeshift.toMillis());
	}

	/**
	 * Split this {@link Flux} sequence into multiple {@link Flux} delimited by the given {@code maxSize} number
	 * of items, starting from the first item. {@link Flux} windows will onComplete after a given
	 * timespan occurs and the number of items has not be counted.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/windowsizetimeout.png" alt="">
	 *
	 * @param maxSize the maximum {@link Flux} window items to count before onComplete
	 * @param timespan the timeout to use to onComplete a given window if size is not counted yet
	 *
	 * @return a windowing {@link Flux} of sized or timed {@link Flux} buckets
	 */
	public final Flux<Flux<T>> window(int maxSize, Duration timespan) {
		return new FluxWindowTimeOrSize<>(this, maxSize, timespan.toMillis(), getTimer());
	}

	/**
	 * Combine values from this {@link Flux} with values from another
	 * {@link Publisher} through a {@link BiFunction} and emits the result.
	 * <p>
	 * The operator will drop values from this {@link Flux} until the other
	 * {@link Publisher} produces any value.
	 * <p>
	 * If the other {@link Publisher} completes without any value, the sequence is completed.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/withlatestfrom.png" alt="">
	 *
	 * @param other the {@link Publisher} to combine with
	 * @param resultSelector the bi-function called with each pair of source and other elements that should return a single value to be emitted
	 * 
	 * @param <U> the other {@link Publisher} sequence type
	 * @param <R> the result type
	 *
	 * @return a combined {@link Flux} gated by another {@link Publisher}
	 */
	public final <U, R> Flux<R> withLatestFrom(Publisher<? extends U> other, BiFunction<? super T, ? super U, ?
			extends R > resultSelector){
		return new FluxWithLatestFrom<>(this, other, resultSelector);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator from the most recent items emitted by each source until any of them
	 * completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zip.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Flux}
	 */
	public final <T2, V> Flux<V> zipWith(Publisher<? extends T2> source2,
			final BiFunction<? super T, ? super T2, ? extends V> combinator) {
		return zip(this, source2, combinator);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations
	 * produced by the passed combinator from the most recent items emitted by each source until any of them
	 * completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param prefetch the request size to use for this {@link Flux} and the other {@link Publisher}
	 * @param combinator The aggregate function that will receive a unique value from each upstream and return the value
	 * to signal downstream
	 * @param <T2> type of the value from source2
	 * @param <V> The produced output after transformation by the combinator
	 *
	 * @return a zipped {@link Flux}
	 */
	@SuppressWarnings("unchecked")
	public final <T2, V> Flux<V> zipWith(Publisher<? extends T2> source2,
			int prefetch, BiFunction<? super T, ? super T2, ? extends V> combinator) {
		return zip(objects -> combinator.apply((T)objects[0], (T2)objects[1]), prefetch, this, source2);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipt.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Flux}
	 *
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Flux<Tuple2<T, T2>> zipWith(Publisher<? extends T2> source2) {
		return Flux.<T, T2, Tuple2<T, T2>>zip(this, source2, TUPLE2_BIFUNCTION);
	}

	/**
	 * "Step-Merge" especially useful in Scatter-Gather scenarios. The operator will forward all combinations of the
	 * most recent items emitted by each source until any of them completes. Errors will immediately be forwarded.
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipp.png" alt="">
	 * <p>
	 * @param source2 The second upstream {@link Publisher} to subscribe to.
	 * @param prefetch the request size to use for this {@link Flux} and the other {@link Publisher}
	 * @param <T2> type of the value from source2
	 *
	 * @return a zipped {@link Flux}
	 *
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Flux<Tuple2<T, T2>> zipWith(Publisher<? extends T2> source2, int prefetch) {
		return zip(Tuple.fn2(), prefetch, this, source2);
	}

	/**
	 * Pairwise combines as {@link Tuple2} elements of this {@link Flux} and an {@link Iterable} sequence.
	 *
	 * @param iterable the {@link Iterable} to pair with
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipwithiterable.png" alt="">
	 *
	 * @param <T2> the value type of the other iterable sequence
	 *
	 * @return a zipped {@link Flux}
	 *
	 */
	@SuppressWarnings("unchecked")
	public final <T2> Flux<Tuple2<T, T2>> zipWithIterable(Iterable<? extends T2> iterable) {
		return new FluxZipIterable<>(this, iterable, (BiFunction<T, T2, Tuple2<T, T2>>)TUPLE2_BIFUNCTION);
	}

	/**
	 * Pairwise combines elements of this
	 * {@link Flux} and an {@link Iterable} sequence using the given zipper {@link BiFunction}.
	 *
	 * <p>
	 * <img class="marble" src="https://raw.githubusercontent.com/reactor/projectreactor.io/master/src/main/static/assets/img/marble/zipwithiterable.png" alt="">
	 *
	 * @param iterable the {@link Iterable} to pair with
	 * @param zipper the {@link BiFunction} combinator
	 *
	 * @param <T2> the value type of the other iterable sequence
	 * @param <V> the result type
	 * 
	 * @return a zipped {@link Flux}
	 *
	 */
	public final <T2, V> Flux<V> zipWithIterable(Iterable<? extends T2> iterable,
			BiFunction<? super T, ? super T2, ? extends V> zipper) {
		return new FluxZipIterable<>(this, iterable, zipper);
	}

	final int getPrefetchOrDefault(int defaultPrefetch){
		long c = getCapacity();
		if(c < 0L){
			return defaultPrefetch;
		}
		if(c >= Integer.MAX_VALUE){
			return Integer.MAX_VALUE;
		}
		return (int)c;
	}

	@SuppressWarnings("rawtypes")
	static final BiFunction      TUPLE2_BIFUNCTION       = Tuple::of;
	@SuppressWarnings("rawtypes")
	static final Supplier        LIST_SUPPLIER           = ArrayList::new;
	@SuppressWarnings("rawtypes")
	static final Function        TIMESTAMP_OPERATOR      = o -> Tuple.of(System.currentTimeMillis(), o);
	@SuppressWarnings("rawtypes")
	static final Supplier        SET_SUPPLIER            = HashSet::new;
	static final BooleanSupplier ALWAYS_BOOLEAN_SUPPLIER = () -> true;
	@SuppressWarnings("rawtypes")
	static final Function        HASHCODE_EXTRACTOR      = Object::hashCode;
	static final Function        IDENTITY_FUNCTION       = Function.identity();

	@SuppressWarnings("unchecked")
	static final <T> Function<T, T> identityFunction(){
		return (Function<T, T>)IDENTITY_FUNCTION;
	}

	static BooleanSupplier countingBooleanSupplier(BooleanSupplier predicate, long max) {
		if (max <= 0) {
			return predicate;
		}
		return new BooleanSupplier() {
			long n;

			@Override
			public boolean getAsBoolean() {
				return n++ < max && predicate.getAsBoolean();
			}
		};
	}

	static <O> Predicate<O> countingPredicate(Predicate<O> predicate, long max) {
		if (max == 0) {
			return predicate;
		}
		return new Predicate<O>() {
			long n;

			@Override
			public boolean test(O o) {
				return n++ < max && predicate.test(o);
			}
		};
	}


	@SuppressWarnings("unchecked")
	static <O> Supplier<Set<O>> hashSetSupplier() {
		return (Supplier<Set<O>>) SET_SUPPLIER;
	}
}
