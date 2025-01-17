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
package reactor.core.scheduler

import reactor.core.util.WaitStrategy
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Oleksandr Petrov
 * @author Stephane Maldini
 */
class HashWheelTimerSleepWaitStrategy extends Specification {

	def period = 50

	def "HashWheelTimer can schedule recurring tasks"() {

		given:
			"a new globalTimer"
			def timer = new Timer(10, 8, WaitStrategy.parking())
			timer.start()
			def latch = new CountDownLatch(10)

		when:
			"a task is submitted"
			timer.schedulePeriodically(
					{ latch.countDown() },
					period,
					period,
					TimeUnit.MILLISECONDS
			)

		then:
			"the latch was counted down"
			latch.await(1, TimeUnit.SECONDS)

		cleanup:
			timer.shutdown()

	}

	def "HashWheelTimer can delay submitted tasks"() {

		given:
			"a new globalTimer"
			def delay = 500
			def timer = new Timer(10, 8, WaitStrategy.parking())
			timer.start()
			def latch = new CountDownLatch(1)
			def start = System.currentTimeMillis()
			def elapsed = 0
			//def actualTimeWithinBounds = true

		when:
			"a task is submitted"
			timer.schedule(
					{
						elapsed = System.currentTimeMillis() - start
						latch.countDown()
					} ,
					delay,
					TimeUnit.MILLISECONDS
			)

		then:
			"the latch was counted down"
			latch.await(1, TimeUnit.SECONDS)
			elapsed >= delay
			elapsed < delay * 2

		cleanup:
			timer.shutdown()
	}

}

