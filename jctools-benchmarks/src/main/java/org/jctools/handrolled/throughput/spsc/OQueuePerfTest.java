/*
 * Copyright 2012 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.handrolled.throughput.spsc;

import org.jctools.queues.QueueByTypeFactory;

import java.util.Queue;


public class OQueuePerfTest {
	// 15 == 32 * 1024
	public static final int REPETITIONS = Integer.getInteger("reps", 50) * 1000 * 1000;
	public static final Integer TEST_VALUE = Integer.valueOf(777);

	public static void main(final String[] args) throws Exception {
		System.out.println("capacity:" + QueueByTypeFactory.QUEUE_CAPACITY + " reps:" + REPETITIONS);
		final Queue<Integer> queue = QueueByTypeFactory.createQueue();

		final long[] results = new long[20];
		for (int i = 0; i < 20; i++) {
			System.gc();
			results[i] = performanceRun(i, queue);
		}
		// only average last 10 results for summary
		long sum = 0;
		for(int i = 10; i < 20; i++){
		    sum+=results[i];
		}
		System.out.format("summary,QueuePerfTest,%s,%d\n", queue.getClass().getSimpleName(), sum/10);
	}


	private static long performanceRun(int runNumber, Queue<Integer> queue) throws InterruptedException {
		long start = System.nanoTime();
        
		Thread thread = new Thread(new Producer(queue));
        thread.start();
		Integer result;
		int i = REPETITIONS;
		do {
			while (null == (result = queue.poll())) {
				Thread.yield();
			}
		} while (0 != --i);
		thread.join();
		
		long duration = System.nanoTime() - start;
		long ops = (REPETITIONS * 1000L * 1000L * 1000L) / duration;
		String qName = queue.getClass().getSimpleName();
        System.out.format("%d - ops/sec=%,d - %s result=%d\n", runNumber, ops, qName, result);
		return ops;
	}

	public static class Producer implements Runnable {
		private final Queue<Integer> queue;
        
		public Producer(Queue<Integer> queue) {
			this.queue = queue;
		}

		public void run() {
		    int i = REPETITIONS;
			do {
				
                while (!queue.offer(TEST_VALUE)) {
					Thread.yield();
				}
			} while (0 != --i);
		}
	}
}
