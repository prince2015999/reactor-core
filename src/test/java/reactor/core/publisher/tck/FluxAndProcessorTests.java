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

package reactor.core.publisher.tck;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import org.junit.Test;
import org.reactivestreams.Processor;
import org.testng.SkipException;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.TopicProcessor;
import reactor.core.publisher.WorkQueueProcessor;
import reactor.core.publisher.Flux;

/**
 * @author Stephane Maldini
 */
@org.testng.annotations.Test
public class FluxAndProcessorTests extends AbstractFluxVerification {

	final AtomicLong cumulated = new AtomicLong(0);

	final AtomicLong cumulatedJoin = new AtomicLong(0);

	@Override
	public Processor<Integer, Integer> createProcessor(int bufferSize) {

		Flux<String> otherStream = Flux.just("test", "test2", "test3");
		System.out.println("Providing new downstream");
		FluxProcessor<Integer, Integer> p = WorkQueueProcessor.create("fluxion-raw-fork", bufferSize);

		cumulated.set(0);
		cumulatedJoin.set(0);

		BiFunction<Integer, String, Integer> combinator = (t1, t2) -> t1;
		return FluxProcessor.wrap(p, p
		                                      .multiplex(2, stream -> stream.scan((prev, next) -> next)
		                                                                   .map(integer -> -integer)
		                                                                   .filter(integer -> integer <= 0)
		                                                                   .every(1)
		                                                                   .map(integer -> -integer)
		                                                                   .buffer(batch, Duration.ofMillis(50))
		                                                                   .flatMap(Flux::fromIterable)
		                                                                   .doOnNext(array -> cumulated.getAndIncrement())
		                                                                   .flatMap(i -> Flux.zip(Flux.just(i),
		                                                                                          otherStream,
		                                                                                          combinator))
		                                                                   .doOnNext(this::monitorThreadUse))
		                                      .doOnNext(array -> cumulatedJoin.getAndIncrement())
		                                      .subscribeWith(TopicProcessor.create("fluxion-raw-join", bufferSize))
		                                      .as(Flux::from)
		                                      .doOnError(Throwable.class, Throwable::printStackTrace));
	}

	@Override
	public boolean skipStochasticTests() {
		return false;
	}

	@Override
	public void stochastic_spec103_mustSignalOnMethodsSequentially() throws Throwable {
		//for(int i = 0 ; i < 1000 ; i++)
		super.stochastic_spec103_mustSignalOnMethodsSequentially();
	}

	@Override
	public void required_spec309_requestZeroMustSignalIllegalArgumentException()
			throws Throwable {
		throw new SkipException("TODO");
	}

	@Override
	public void required_spec309_requestNegativeNumberMustSignalIllegalArgumentException()
			throws Throwable {
		throw new SkipException("TODO");
	}

	@Test
	public void testHotIdentityProcessor() throws InterruptedException {
//		for(int i = 0; i < 1000; i++)
		try {
			super.testHotIdentityProcessor();
		}
		finally {
			System.out.println("cumulated : " + cumulated);
			System.out.println("cumulated after join : " + cumulatedJoin);
		}
	}

	@Test
	public void testColdIdentityProcessor() throws InterruptedException {
		//for (int i = 0; i < 1000; i++) {
		try {
			super.testColdIdentityProcessor();
		}
		finally {
			System.out.println("cumulated : " + cumulated);
			System.out.println("cumulated after join : " + cumulatedJoin);
		}
//		}
	}
}
