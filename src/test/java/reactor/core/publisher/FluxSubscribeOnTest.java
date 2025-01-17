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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Assert;
import org.junit.Test;
import reactor.core.test.TestSubscriber;

public class FluxSubscribeOnTest {
	
	/*@Test
	public void constructors() {
		ConstructorTestBuilder ctb = new ConstructorTestBuilder(FluxPublishOn.class);
		
		ctb.addRef("source", Flux.never());
		ctb.addRef("executor", Computations.concurrent());
		ctb.addRef("schedulerFactory", (Callable<? extends Consumer<Runnable>>)() -> r -> { });
		
		ctb.test();
	}*/
	
	@Test
	public void classic() {
		TestSubscriber<Integer> ts = new TestSubscriber<>();

		Flux.range(1, 1000).subscribeOn(Computations.concurrent()).subscribe(ts);
		
		ts.await(Duration.ofSeconds(5));
		
		ts.assertValueCount(1000)
		.assertNoError()
		.assertComplete();
	}

	@Test
	public void classicBackpressured() throws Exception {
		TestSubscriber<Integer> ts = new TestSubscriber<>(0);

		Flux.range(1, 1000).log().subscribeOn(Computations.concurrent()).subscribe(ts);
		
		Thread.sleep(100);
		
		ts.assertNoValues()
		.assertNoError()
		.assertNotComplete();
		
		ts.request(500);

		Thread.sleep(1000);

		ts.assertValueCount(500)
		.assertNoError()
		.assertNotComplete();

		ts.request(500);

		ts.await(Duration.ofSeconds(5));
		
		ts.assertValueCount(1000)
		.assertNoError()
		.assertComplete();
	}
	
	@Test
	public void classicJust() {
		TestSubscriber<Integer> ts = new TestSubscriber<>();
		
		Flux.just(1).subscribeOn(Computations.concurrent()).subscribe(ts);
		
		ts.await(Duration.ofSeconds(5));
		
		ts.assertValues(1)
		.assertNoError()
		.assertComplete();
	}

	@Test
	public void classicJustBackpressured() throws Exception {
		TestSubscriber<Integer> ts = new TestSubscriber<>(0);
		
		Flux.just(1).subscribeOn(Computations.concurrent()).subscribe(ts);
		
		Thread.sleep(100);
		
		ts.assertNoValues()
		.assertNoError()
		.assertNotComplete();
		
		ts.request(500);

		ts.await(Duration.ofSeconds(5));
		
		ts.assertValues(1)
		.assertNoError()
		.assertComplete();
	}

	@Test
	public void classicEmpty() {
		TestSubscriber<Integer> ts = new TestSubscriber<>();
		
		Flux.<Integer>empty().subscribeOn(Computations.concurrent()).subscribe(ts);
		
		ts.await(Duration.ofSeconds(5));
		
		ts.assertNoValues()
		.assertNoError()
		.assertComplete();
	}

	@Test
	public void classicEmptyBackpressured() throws Exception {
		TestSubscriber<Integer> ts = new TestSubscriber<>(0);
		
		Flux.<Integer>empty().subscribeOn(Computations.concurrent()).subscribe(ts);
		
		ts.await(Duration.ofSeconds(5));
		
		ts.assertNoValues()
		.assertNoError()
		.assertComplete();
	}

	
	@Test
	public void callableEvaluatedTheRightTime() {
		
		AtomicInteger count = new AtomicInteger();
		
		Mono<Integer> p = Mono.fromCallable(count::incrementAndGet).subscribeOn(Computations.concurrent());
		
		Assert.assertEquals(0, count.get());
		
		p.subscribeWith(new TestSubscriber<>()).await();
		
		Assert.assertEquals(1, count.get());
	}}
