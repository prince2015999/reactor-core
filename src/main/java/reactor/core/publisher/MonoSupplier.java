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

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;

import reactor.core.flow.Fuseable;
import reactor.core.flow.Receiver;
import reactor.core.subscriber.DeferredScalarSubscriber;
import reactor.core.util.Exceptions;

/**
 * Executes a Supplier function and emits a single value to each individual Subscriber.
 *
 * @param <T> the returned value type
 */

/**
 * {@see https://github.com/reactor/reactive-streams-commons}
 * @since 2.5
 */
final class MonoSupplier<T> 
extends Mono<T>
		implements Receiver, Callable<T>, Fuseable {

	final Supplier<? extends T> supplier;

	public MonoSupplier(Supplier<? extends T> callable) {
		this.supplier = Objects.requireNonNull(callable, "callable");
	}

	@Override
	public Object upstream() {
		return supplier;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {

		DeferredScalarSubscriber<T, T> sds = new DeferredScalarSubscriber<>(s);

		s.onSubscribe(sds);

		if (sds.isCancelled()) {
			return;
		}

		T t;
		try {
			t = supplier.get();
		} catch (Throwable e) {
			Exceptions.throwIfFatal(e);
			s.onError(Exceptions.unwrap(e));
			return;
		}

		if (t == null) {
			s.onError(new NullPointerException("The callable returned null"));
			return;
		}

		sds.complete(t);
	}
	
	@Override
	public T get() {
        return supplier.get();
	}
	
	@Override
	public T call() throws Exception {
	    return supplier.get();
	}
}
