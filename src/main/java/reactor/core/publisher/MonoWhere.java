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
import java.util.function.Predicate;

import org.reactivestreams.*;

import reactor.core.flow.Fuseable;
import reactor.core.flow.Fuseable.ConditionalSubscriber;
import reactor.core.publisher.FluxFilterFuseable.*;

/**
 * Filters out values that make a filter function return false.
 *
 * @param <T> the value type
 */

/**
 * {@see <a href='https://github.com/reactor/reactive-streams-commons'>https://github.com/reactor/reactive-streams-commons</a>}
 * @since 2.5
 */
final class MonoWhere<T> extends MonoSource<T, T> {

	final Predicate<? super T> predicate;

	public MonoWhere(Publisher<? extends T> source, Predicate<? super T> predicate) {
		super(source);
		this.predicate = Objects.requireNonNull(predicate, "predicate");
	}

	public Predicate<? super T> predicate() {
		return predicate;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		if (source instanceof Fuseable) {
			if (s instanceof ConditionalSubscriber) {
				source.subscribe(new FilterFuseableConditionalSubscriber<>((ConditionalSubscriber<? super T>) s,
						predicate));
				return;
			}
			source.subscribe(new FilterFuseableSubscriber<>(s, predicate));
			return;
		}
		if (s instanceof ConditionalSubscriber) {
			source.subscribe(new FluxFilter.FilterConditionalSubscriber<>((ConditionalSubscriber<? super T>)s, predicate));
			return;
		}
		source.subscribe(new FluxFilter.FilterSubscriber<>(s, predicate));
	}
}
